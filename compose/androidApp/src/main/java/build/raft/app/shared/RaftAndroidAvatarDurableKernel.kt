package build.raft.app.shared

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android-native durable avatar byte authority.
 *
 * The class deliberately has no adapter, downloader, shared-store, or Settings dependency. Phase
 * 3 wires these primitives to the existing image owner. All mutation and recovery is serialized by
 * this kernel; encoded files are immutable and a key can own at most one durable transaction.
 */
internal class RaftAndroidAvatarDurableKernel(
    private val files: RaftAndroidAvatarDurableFileSystem,
    private val identityFactory: RaftAndroidAvatarIdentityFactory =
        RaftAndroidAvatarSecureIdentityFactory(),
    private val decodedStore: RaftAndroidAvatarDecodedIdentityStore =
        RaftAndroidAvatarInMemoryDecodedIdentityStore(),
    private val durableObserver: RaftAndroidAvatarDurableObserver =
        RaftAndroidAvatarDurableObserver { _, _ -> },
    private val terminalObserver: RaftAndroidAvatarTerminalObserver =
        RaftAndroidAvatarTerminalObserver { _, _ -> }
) {
    private data class RequestSlot(
        val requestGeneration: Long,
        val sessionIdentity: String,
        val epochs: RaftAndroidAvatarTerminalEpochs
    )

    private data class ActiveLease(
        val key: RaftAndroidAvatarOpaqueKey,
        val kind: RaftAndroidAvatarLeaseKind
    )

    private enum class RecordStatus {
        Missing,
        Valid,
        Corrupt,
        Failed
    }

    private data class LoadedRecord(
        val status: RecordStatus,
        val state: RaftAndroidAvatarDurableState? = null
    )

    private data class ResolvedState(
        val state: RaftAndroidAvatarDurableState?,
        val needsRewrite: Boolean,
        val corrupt: Boolean,
        val failed: Boolean
    )

    private sealed interface EntryInspection {
        data class Present(val bytes: ByteArray) : EntryInspection
        data object Missing : EntryInspection
        data object Invalid : EntryInspection
        data object Failed : EntryInspection
    }

    private enum class CandidateRenameOutcome {
        Ready,
        MissingOrInvalid,
        DurabilityPending,
        StorageFailure
    }

    private val lock = ReentrantLock(true)
    private val leaseDrained = lock.newCondition()
    private val requestSlots = mutableMapOf<RaftAndroidAvatarOpaqueKey, RequestSlot>()
    private val activeLeases = mutableMapOf<Long, ActiveLease>()
    private val closedScopes = linkedSetOf<RaftAndroidAvatarTerminalScope>()
    private val pendingDurabilityPaths = linkedSetOf<String>()
    private val ownerEpochs = mutableMapOf<Pair<String, String>, Long>()
    private val subjectEpochs = mutableMapOf<Triple<String, String, String>, Long>()
    private var globalEpoch = 0L
    private var nextLeaseId = 1L
    private var nextAccessOrdinal = 1L
    private var recoveryAdmissionBlocked = false

    val startupRecovery: RaftAndroidAvatarOperationResult

    init {
        startupRecovery =
            lock.withLock {
                recoverStartupLocked().also { result ->
                    recoveryAdmissionBlocked =
                        result.status != RaftAndroidAvatarOperationStatus.Applied
                }
            }
    }

    fun beginRequest(
        key: RaftAndroidAvatarOpaqueKey,
        requestGeneration: Long
    ): RaftAndroidAvatarRequestResult =
        lock.withLock {
            require(requestGeneration > 0L)
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                return@withLock RaftAndroidAvatarRequestResult(
                    RaftAndroidAvatarRequestStatus.AdmissionClosed
                )
            }
            val epochs = epochsForLocked(key)
            val existing = requestSlots[key]
            if (existing != null && existing.epochs == epochs) {
                if (requestGeneration < existing.requestGeneration) {
                    return@withLock RaftAndroidAvatarRequestResult(
                        RaftAndroidAvatarRequestStatus.Stale
                    )
                }
                if (requestGeneration == existing.requestGeneration) {
                    return@withLock RaftAndroidAvatarRequestResult(
                        status = RaftAndroidAvatarRequestStatus.Accepted,
                        fence = existing.toFence(key)
                    )
                }
            }
            val slot =
                RequestSlot(
                    requestGeneration = requestGeneration,
                    sessionIdentity = nextIdentity(),
                    epochs = epochs
                )
            requestSlots[key] = slot
            RaftAndroidAvatarRequestResult(
                status = RaftAndroidAvatarRequestStatus.Accepted,
                fence = slot.toFence(key)
            )
        }

    /**
     * Mints the next native commit identity for one admitted request without persisting a second
     * reservation ledger. The controller owns same-fence response-loss idempotence in memory; the
     * generation becomes durable only when [stageCandidate] records the candidate. Consequently a
     * process death before staging may re-reserve, while rollback after a durable stage always
     * advances beyond the recovered high-water mark.
     */
    fun reserveCandidateIdentity(
        fence: RaftAndroidAvatarRequestFence,
        expectedCurrentCommitIdentity: RaftAndroidAvatarCommitIdentity?
    ): RaftAndroidAvatarIdentityReservationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(fence)) {
                return@withLock RaftAndroidAvatarIdentityReservationResult(
                    staleOrClosedResult(fence.key).status
                )
            }
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed) {
                return@withLock RaftAndroidAvatarIdentityReservationResult(
                    RaftAndroidAvatarOperationStatus.StorageFailure
                )
            }
            if (resolved.corrupt) {
                return@withLock RaftAndroidAvatarIdentityReservationResult(
                    RaftAndroidAvatarOperationStatus.CorruptState
                )
            }
            val state = resolved.state ?: RaftAndroidAvatarDurableState.idle(fence.key)
            if (state.phase != RaftAndroidAvatarDurablePhase.Idle ||
                files.exists(fence.key.journalPath) ||
                hasPendingDurabilityLocked(fence.key)
            ) {
                return@withLock RaftAndroidAvatarIdentityReservationResult(
                    RaftAndroidAvatarOperationStatus.Busy
                )
            }
            val currentIdentity = state.current?.identity
            if (currentIdentity?.commitIdentity != expectedCurrentCommitIdentity) {
                return@withLock RaftAndroidAvatarIdentityReservationResult(
                    RaftAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            currentIdentity?.let { current ->
                when (inspectEntryLocked(current)) {
                    is EntryInspection.Present -> Unit
                    EntryInspection.Failed ->
                        return@withLock RaftAndroidAvatarIdentityReservationResult(
                            RaftAndroidAvatarOperationStatus.StorageFailure
                        )
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        retireCurrentLocked(
                            state = state,
                            identity = current,
                            reason = RaftAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock RaftAndroidAvatarIdentityReservationResult(
                            RaftAndroidAvatarOperationStatus.CleanupPending
                        )
                    }
                }
            }
            if (state.commitGenerationHighWater == Long.MAX_VALUE) {
                return@withLock RaftAndroidAvatarIdentityReservationResult(
                    RaftAndroidAvatarOperationStatus.StorageFailure
                )
            }
            RaftAndroidAvatarIdentityReservationResult(
                status = RaftAndroidAvatarOperationStatus.Applied,
                commitIdentity =
                    RaftAndroidAvatarCommitIdentity(
                        commitGeneration = state.commitGenerationHighWater + 1L,
                        nonce = nextIdentity()
                    )
            )
        }

    fun acquireLease(
        fence: RaftAndroidAvatarRequestFence,
        kind: RaftAndroidAvatarLeaseKind
    ): RaftAndroidAvatarLease? =
        lock.withLock {
            acquireLeaseLocked(fence, kind)
        }

    fun stageCandidate(
        fence: RaftAndroidAvatarRequestFence,
        expectedCurrentCommitIdentity: RaftAndroidAvatarCommitIdentity?,
        candidateCommitIdentity: RaftAndroidAvatarCommitIdentity,
        bytes: ByteArray,
        contentType: String
    ): RaftAndroidAvatarOperationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(fence)) return@withLock staleOrClosedResult(fence.key)
            if (bytes.isEmpty()) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.StorageFailure
                )
            }
            val candidateSha256 = bytes.raftSha256Hex()
            val candidateByteLength = bytes.size.toLong()
            val candidateContentType = contentType.raftNormalizedAvatarContentType()
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val currentState = resolved.state ?: RaftAndroidAvatarDurableState.idle(fence.key)
            currentState.candidate?.identity
                ?.takeIf { identity -> identity.commitIdentity == candidateCommitIdentity }
                ?.let { activeCandidate ->
                    if (!activeCandidate.matchesPayload(
                            sha256 = candidateSha256,
                            byteLength = candidateByteLength,
                            contentType = candidateContentType
                        )
                    ) {
                        return@withLock RaftAndroidAvatarOperationResult(
                            RaftAndroidAvatarOperationStatus.IdentityMismatch
                        )
                    }
                    when (currentState.phase) {
                        RaftAndroidAvatarDurablePhase.CandidateDurable,
                        RaftAndroidAvatarDurablePhase.SharedCommitted,
                        RaftAndroidAvatarDurablePhase.CandidateRenamed,
                        RaftAndroidAvatarDurablePhase.IndexSwitched,
                        RaftAndroidAvatarDurablePhase.CommitCleanup ->
                            when (inspectCandidateSourceLocked(activeCandidate)) {
                                is EntryInspection.Present ->
                                    return@withLock RaftAndroidAvatarOperationResult(
                                        status = RaftAndroidAvatarOperationStatus.AlreadyApplied,
                                        entry = activeCandidate
                                    )
                                EntryInspection.Failed -> return@withLock storageFailure()
                                EntryInspection.Missing,
                                EntryInspection.Invalid -> return@withLock cleanupPending()
                            }
                        RaftAndroidAvatarDurablePhase.Prepared ->
                            return@withLock cleanupPending()
                        RaftAndroidAvatarDurablePhase.RollbackRepointed,
                        RaftAndroidAvatarDurablePhase.RollbackCleanup ->
                            return@withLock RaftAndroidAvatarOperationResult(
                                RaftAndroidAvatarOperationStatus.IdentityMismatch
                            )
                        RaftAndroidAvatarDurablePhase.Idle -> return@withLock corruptState()
                    }
                }
            currentState.current?.identity
                ?.takeIf { identity -> identity.commitIdentity == candidateCommitIdentity }
                ?.let { committed ->
                    if (!committed.matchesPayload(
                            sha256 = candidateSha256,
                            byteLength = candidateByteLength,
                            contentType = candidateContentType
                        )
                    ) {
                        return@withLock RaftAndroidAvatarOperationResult(
                            RaftAndroidAvatarOperationStatus.IdentityMismatch
                        )
                    }
                    when (inspectEntryLocked(committed)) {
                        is EntryInspection.Present ->
                            return@withLock RaftAndroidAvatarOperationResult(
                                status = RaftAndroidAvatarOperationStatus.AlreadyApplied,
                                entry = committed
                            )
                        EntryInspection.Failed -> return@withLock storageFailure()
                        EntryInspection.Missing,
                        EntryInspection.Invalid -> {
                            retireCurrentLocked(
                                state = currentState,
                                identity = committed,
                                reason = RaftAndroidAvatarTombstoneReason.InvalidBytes,
                                notifyCuts = false
                            )
                            return@withLock cleanupPending()
                        }
                    }
                }
            if (candidateCommitIdentity.commitGeneration <=
                currentState.commitGenerationHighWater
            ) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            if (hasPendingDurabilityLocked(fence.key)) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.Busy
                )
            }
            val keyFiles = managedFilesForKeyLocked(fence.key) ?: return@withLock storageFailure()
            val allowedIdleFiles =
                buildSet {
                    if (resolved.state != null) add(fence.key.indexPath)
                    if (currentState.current != null) {
                        add(currentState.current.identity.entryPath)
                    }
                }
            if (currentState.phase != RaftAndroidAvatarDurablePhase.Idle ||
                files.exists(fence.key.journalPath) ||
                keyFiles.any { path -> path !in allowedIdleFiles }
            ) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.Busy
                )
            }
            currentState.current?.let { current ->
                when (inspectEntryLocked(current.identity)) {
                    is EntryInspection.Present -> Unit
                    EntryInspection.Failed -> return@withLock storageFailure()
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        retireCurrentLocked(
                            state = currentState,
                            identity = current.identity,
                            reason = RaftAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock cleanupPending()
                    }
                }
            }
            if (currentState.current?.identity?.commitIdentity != expectedCurrentCommitIdentity) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            val txId = nextIdentity()
            val candidateIdentity =
                RaftAndroidAvatarEntryIdentity(
                    key = fence.key,
                    nativeRevision = nextIdentity(),
                    txId = txId,
                    commitIdentity = candidateCommitIdentity,
                    fileIdentity = nextIdentity(),
                    sha256 = candidateSha256,
                    byteLength = candidateByteLength,
                    contentType = candidateContentType
                )
            val candidate =
                RaftAndroidAvatarEntryRecord(
                    identity = candidateIdentity,
                    lastAccessOrdinal = nextAccessOrdinalLocked()
                )
            val prepared =
                RaftAndroidAvatarDurableState(
                    key = fence.key,
                    commitGenerationHighWater =
                        maxOf(
                            currentState.commitGenerationHighWater,
                            candidateCommitIdentity.commitGeneration
                        ),
                    phase = RaftAndroidAvatarDurablePhase.Prepared,
                    transactionId = txId,
                    capturedEpochs = fence.terminalEpochs,
                    current = currentState.current,
                    previous = currentState.current,
                    candidate = candidate,
                    cleanupEntries = emptyList(),
                    cleanupArtifacts = emptySet(),
                    tombstoneReason = null
                )
            if (!persistJournalThenIndexLocked(
                    state = prepared,
                    cut = RaftAndroidAvatarDurableCut.Prepared,
                    notifyCuts = true
                )
            ) {
                return@withLock cleanupPending()
            }
            if (!files.writeNewDurably(candidateIdentity.stagePath, bytes)) {
                return@withLock cleanupPending()
            }
            durableObserver.afterDurableCut(
                fence.key,
                RaftAndroidAvatarDurableCut.CandidateFileDurable
            )
            val durable = prepared.copy(phase = RaftAndroidAvatarDurablePhase.CandidateDurable)
            if (!persistJournalThenIndexLocked(
                    state = durable,
                    cut = RaftAndroidAvatarDurableCut.CandidateDurable,
                    notifyCuts = true
                )
            ) {
                return@withLock cleanupPending()
            }
            RaftAndroidAvatarOperationResult(
                status = RaftAndroidAvatarOperationStatus.Applied,
                entry = candidateIdentity
            )
        }

    fun finalizeCandidate(
        fence: RaftAndroidAvatarRequestFence,
        candidateCommitIdentity: RaftAndroidAvatarCommitIdentity
    ): RaftAndroidAvatarOperationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(fence)) return@withLock staleOrClosedResult(fence.key)
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.NotFound
                )
            if (state.phase == RaftAndroidAvatarDurablePhase.Idle) {
                if (hasPendingDurabilityLocked(fence.key)) return@withLock cleanupPending()
                return@withLock if (state.current?.identity?.commitIdentity == candidateCommitIdentity) {
                    RaftAndroidAvatarOperationResult(
                        RaftAndroidAvatarOperationStatus.AlreadyApplied,
                        state.current.identity
                    )
                } else {
                    RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.NotFound)
                }
            }
            val candidate = state.candidate
                ?: return@withLock corruptState()
            if (candidate.identity.commitIdentity != candidateCommitIdentity ||
                state.transactionId != candidate.identity.txId
            ) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            if (state.capturedEpochs != fence.terminalEpochs) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.StaleRequest
                )
            }
            val sharedCommitted =
                if (state.phase == RaftAndroidAvatarDurablePhase.CandidateDurable) {
                    when (inspectCandidateSourceLocked(candidate.identity)) {
                        is EntryInspection.Present -> Unit
                        EntryInspection.Failed -> return@withLock storageFailure()
                        EntryInspection.Missing,
                        EntryInspection.Invalid ->
                            return@withLock RaftAndroidAvatarOperationResult(
                                RaftAndroidAvatarOperationStatus.StorageFailure
                            )
                    }
                    state.copy(phase = RaftAndroidAvatarDurablePhase.SharedCommitted).also { next ->
                        if (!persistJournalThenIndexLocked(
                                state = next,
                                cut = RaftAndroidAvatarDurableCut.SharedCommitted,
                                notifyCuts = true
                            )
                        ) {
                            return@withLock cleanupPending()
                        }
                    }
                } else {
                    if (state.phase == RaftAndroidAvatarDurablePhase.Prepared) {
                        return@withLock cleanupPending()
                    }
                    state
                }
            continueCommitLocked(sharedCommitted, notifyCuts = true)
        }

    fun rollbackCandidate(
        key: RaftAndroidAvatarOpaqueKey,
        candidateCommitIdentity: RaftAndroidAvatarCommitIdentity
    ): RaftAndroidAvatarOperationResult =
        lock.withLock {
            val resolved = loadResolvedStateLocked(key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.NotFound
                )
            if (state.phase == RaftAndroidAvatarDurablePhase.Idle) {
                if (hasPendingDurabilityLocked(key)) return@withLock cleanupPending()
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.NotFound
                )
            }
            if (state.candidate?.identity?.commitIdentity != candidateCommitIdentity) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            continueRollbackLocked(state, notifyCuts = true)
        }

    /**
     * Exact current-entry read used by the Android adapter/controller. The controller surrounds
     * this copy with its own render-delivery lease so terminal retirement cannot complete until
     * decode/callback preparation releases it; bytes never cross into shared state.
     */
    fun readCurrent(
        key: RaftAndroidAvatarOpaqueKey
    ): RaftAndroidAvatarCurrentReadResult =
        lock.withLock {
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                return@withLock RaftAndroidAvatarCurrentReadResult(
                    RaftAndroidAvatarCurrentReadStatus.AdmissionClosed
                )
            }
            val resolved = loadResolvedStateLocked(key)
            if (resolved.failed) {
                return@withLock RaftAndroidAvatarCurrentReadResult(
                    RaftAndroidAvatarCurrentReadStatus.StorageFailure
                )
            }
            if (resolved.corrupt) {
                return@withLock RaftAndroidAvatarCurrentReadResult(
                    RaftAndroidAvatarCurrentReadStatus.RecoveryPending
                )
            }
            val state = resolved.state
                ?: return@withLock RaftAndroidAvatarCurrentReadResult(
                    RaftAndroidAvatarCurrentReadStatus.Empty
                )
            val current = state.current
                ?: return@withLock RaftAndroidAvatarCurrentReadResult(
                    RaftAndroidAvatarCurrentReadStatus.Empty
                )
            val bytes =
                when (val inspection = inspectEntryLocked(current.identity)) {
                    is EntryInspection.Present -> inspection.bytes
                    EntryInspection.Failed ->
                        return@withLock RaftAndroidAvatarCurrentReadResult(
                            RaftAndroidAvatarCurrentReadStatus.StorageFailure
                        )
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        retireCurrentLocked(
                            state = state,
                            identity = current.identity,
                            reason = RaftAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock RaftAndroidAvatarCurrentReadResult(
                            RaftAndroidAvatarCurrentReadStatus.Empty
                        )
                    }
                }
            val touched = current.copy(lastAccessOrdinal = nextAccessOrdinalLocked())
            persistCurrentTouchLocked(
                state.copy(
                    current = touched,
                    previous =
                        state.previous?.let { previous ->
                            if (previous.identity == current.identity) touched else previous
                        },
                    candidate =
                        state.candidate?.let { candidate ->
                            if (candidate.identity == current.identity) touched else candidate
                        }
                ),
                state
            )
            RaftAndroidAvatarCurrentReadResult(
                status = RaftAndroidAvatarCurrentReadStatus.Available,
                snapshot =
                    RaftAndroidAvatarCurrentReadSnapshot(
                        entryIdentity = current.identity,
                        bytes = bytes
                    )
            )
        }

    fun readSnapshot(
        fence: RaftAndroidAvatarRequestFence,
        expectedCommitIdentity: RaftAndroidAvatarCommitIdentity?
    ): RaftAndroidAvatarReadResult =
        lock.withLock {
            if (expectedCommitIdentity == null) {
                return@withLock RaftAndroidAvatarReadResult.Miss(
                    RaftAndroidAvatarReadMissReason.NoExpectedCommitIdentity
                )
            }
            if (!isFenceCurrentLocked(fence)) {
                return@withLock RaftAndroidAvatarReadResult.Miss(
                    if (recoveryAdmissionBlocked || closedScopes.any(fence.key::matches)) {
                        RaftAndroidAvatarReadMissReason.AdmissionClosed
                    } else {
                        RaftAndroidAvatarReadMissReason.StaleRequest
                    }
                )
            }
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed || resolved.corrupt) {
                return@withLock RaftAndroidAvatarReadResult.Miss(
                    RaftAndroidAvatarReadMissReason.RecoveryPending
                )
            }
            val state = resolved.state
            val current = state?.current
                ?: return@withLock RaftAndroidAvatarReadResult.Miss(
                    RaftAndroidAvatarReadMissReason.Empty
                )
            if (current.identity.commitIdentity != expectedCommitIdentity) {
                return@withLock RaftAndroidAvatarReadResult.Miss(
                    RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
                )
            }
            val lease = acquireLeaseLocked(fence, RaftAndroidAvatarLeaseKind.Read)
                ?: return@withLock RaftAndroidAvatarReadResult.Miss(
                    RaftAndroidAvatarReadMissReason.StaleRequest
                )
            val bytes =
                when (val inspection = inspectEntryLocked(current.identity)) {
                    is EntryInspection.Present -> inspection.bytes
                    EntryInspection.Failed -> {
                        lease.close()
                        return@withLock RaftAndroidAvatarReadResult.Miss(
                            RaftAndroidAvatarReadMissReason.RecoveryPending
                        )
                    }
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        lease.close()
                        retireCurrentLocked(
                            state = state,
                            identity = current.identity,
                            reason = RaftAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock RaftAndroidAvatarReadResult.Miss(
                            RaftAndroidAvatarReadMissReason.MissingOrInvalidBytes
                        )
                    }
            }
            val touched = current.copy(lastAccessOrdinal = nextAccessOrdinalLocked())
            persistCurrentTouchLocked(
                state.copy(
                    current = touched,
                    previous =
                        state.previous?.let { previous ->
                            if (previous.identity == current.identity) touched else previous
                        },
                    candidate =
                        state.candidate?.let { candidate ->
                            if (candidate.identity == current.identity) touched else candidate
                        }
                ),
                state
            )
            RaftAndroidAvatarReadResult.Hit(
                snapshot =
                    RaftAndroidAvatarReadSnapshot(
                        requestFence = fence,
                        entryIdentity = current.identity,
                        bytes = bytes,
                        capturedEpochs = fence.terminalEpochs
                    ),
                lease = lease
            )
        }

    fun registerDecoded(
        snapshot: RaftAndroidAvatarReadSnapshot,
        lease: RaftAndroidAvatarLease,
        variant: RaftAndroidAvatarDecodedVariant
    ): Boolean =
        lock.withLock {
            if (lease.leaseId !in activeLeases || lease.requestFence != snapshot.requestFence) {
                return@withLock false
            }
            if (!isFenceCurrentLocked(snapshot.requestFence)) return@withLock false
            val state = loadResolvedStateLocked(snapshot.entryIdentity.key)
            if (state.failed || state.corrupt || state.state?.current?.identity != snapshot.entryIdentity) {
                return@withLock false
            }
            decodedStore.register(
                RaftAndroidAvatarDecodedIdentity(
                    entryIdentity = snapshot.entryIdentity,
                    variant = variant
                )
            )
        }

    fun registerDecodedCurrent(
        entryIdentity: RaftAndroidAvatarEntryIdentity,
        variant: RaftAndroidAvatarDecodedVariant
    ): Boolean =
        lock.withLock {
            if (recoveryAdmissionBlocked || closedScopes.any(entryIdentity.key::matches)) {
                return@withLock false
            }
            val resolved = loadResolvedStateLocked(entryIdentity.key)
            if (resolved.failed || resolved.corrupt || resolved.state?.current?.identity != entryIdentity) {
                return@withLock false
            }
            decodedStore.register(
                RaftAndroidAvatarDecodedIdentity(
                    entryIdentity = entryIdentity,
                    variant = variant
                )
            )
        }

    fun invalidateIfCurrent(
        snapshot: RaftAndroidAvatarReadSnapshot,
        reason: RaftAndroidAvatarTombstoneReason =
            RaftAndroidAvatarTombstoneReason.AuthoritativeRetirement
    ): RaftAndroidAvatarOperationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(snapshot.requestFence) ||
                snapshot.capturedEpochs != epochsForLocked(snapshot.entryIdentity.key)
            ) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.StaleRequest
                )
            }
            val resolved = loadResolvedStateLocked(snapshot.entryIdentity.key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.NotFound
                )
            if (state.current?.identity != snapshot.entryIdentity) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.StaleRequest
                )
            }
            retireCurrentLocked(state, snapshot.entryIdentity, reason, notifyCuts = true)
        }

    fun retireCurrentExact(
        key: RaftAndroidAvatarOpaqueKey,
        expectedCommitIdentity: RaftAndroidAvatarCommitIdentity?
    ): RaftAndroidAvatarOperationResult =
        lock.withLock {
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.AdmissionClosed
                )
            }
            val resolved = loadResolvedStateLocked(key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.AlreadyApplied
                )
            if (state.phase != RaftAndroidAvatarDurablePhase.Idle) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.Busy
                )
            }
            val current = state.current
                ?: return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.AlreadyApplied
                )
            if (expectedCommitIdentity != null && current.identity.commitIdentity != expectedCommitIdentity) {
                return@withLock RaftAndroidAvatarOperationResult(
                    RaftAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            retireCurrentLocked(
                state = state,
                identity = current.identity,
                reason = RaftAndroidAvatarTombstoneReason.AuthoritativeRetirement,
                notifyCuts = true
            )
        }

    /**
     * Terminal operations close matching admission, drain leases, and physically remove the
     * matching native cache namespace. They intentionally do not retain commit-generation history
     * after physical zero. Product wiring must clear matching metadata/validators so the next
     * request starts with no expected native identity and supplies a canonical full body.
     */
    fun clearAll(): RaftAndroidAvatarTerminalResult =
        retireScope(RaftAndroidAvatarTerminalScope.All)

    fun retireOwner(
        partitionKey: String,
        ownerKey: String
    ): RaftAndroidAvatarTerminalResult =
        retireScope(RaftAndroidAvatarTerminalScope.Owner(partitionKey, ownerKey))

    fun retireSubject(
        partitionKey: String,
        ownerKey: String,
        subjectKey: String
    ): RaftAndroidAvatarTerminalResult =
        retireScope(
            RaftAndroidAvatarTerminalScope.Subject(
                partitionKey = partitionKey,
                ownerKey = ownerKey,
                subjectKey = subjectKey
            )
        )

    fun retryRecovery(): RaftAndroidAvatarOperationResult =
        lock.withLock {
            recoverStartupLocked().also { result ->
                recoveryAdmissionBlocked =
                    result.status != RaftAndroidAvatarOperationStatus.Applied
            }
        }

    fun managedBytes(): Long? =
        lock.withLock {
            managedDataFilesLocked()?.let(::sizeOfLocked)
        }

    fun trimToBudget(maxBytes: Long): RaftAndroidAvatarBudgetResult =
        lock.withLock {
            require(maxBytes >= 0L)
            val initialFiles = managedDataFilesLocked()
                ?: return@withLock RaftAndroidAvatarBudgetResult(
                    RaftAndroidAvatarBudgetStatus.StorageFailure,
                    managedBytes = -1L,
                    evictedEntries = 0
                )
            var total = sizeOfLocked(initialFiles)
                ?: return@withLock RaftAndroidAvatarBudgetResult(
                    RaftAndroidAvatarBudgetStatus.StorageFailure,
                    managedBytes = -1L,
                    evictedEntries = 0
                )
            if (total <= maxBytes) {
                return@withLock RaftAndroidAvatarBudgetResult(
                    RaftAndroidAvatarBudgetStatus.WithinBudget,
                    managedBytes = total,
                    evictedEntries = 0
                )
            }
            val keys = managedKeysLocked()
                ?: return@withLock RaftAndroidAvatarBudgetResult(
                    RaftAndroidAvatarBudgetStatus.StorageFailure,
                    managedBytes = total,
                    evictedEntries = 0
                )
            val candidates = mutableListOf<RaftAndroidAvatarEntryRecord>()
            for (key in keys) {
                val resolved = loadResolvedStateLocked(key)
                if (resolved.failed) {
                    return@withLock RaftAndroidAvatarBudgetResult(
                        RaftAndroidAvatarBudgetStatus.StorageFailure,
                        managedBytes = total,
                        evictedEntries = 0
                    )
                }
                if (resolved.corrupt) {
                    return@withLock RaftAndroidAvatarBudgetResult(
                        RaftAndroidAvatarBudgetStatus.CleanupPending,
                        managedBytes = total,
                        evictedEntries = 0
                    )
                }
                if (resolved.state == null) {
                    val keyFiles =
                        managedFilesForKeyLocked(key)
                            ?: return@withLock RaftAndroidAvatarBudgetResult(
                                RaftAndroidAvatarBudgetStatus.StorageFailure,
                                managedBytes = total,
                                evictedEntries = 0
                            )
                    if (keyFiles.isNotEmpty()) {
                        return@withLock RaftAndroidAvatarBudgetResult(
                            RaftAndroidAvatarBudgetStatus.CleanupPending,
                            managedBytes = total,
                            evictedEntries = 0
                        )
                    }
                }
                resolved.state
                    ?.takeIf { state -> state.phase == RaftAndroidAvatarDurablePhase.Idle }
                    ?.current
                    ?.let(candidates::add)
            }
            candidates.sortWith(
                compareBy<RaftAndroidAvatarEntryRecord> { it.lastAccessOrdinal }
                    .thenBy { it.identity.nativeRevision }
            )
            var evicted = 0
            for (candidate in candidates) {
                if (total <= maxBytes) break
                val resolved = loadResolvedStateLocked(candidate.identity.key)
                val state = resolved.state ?: continue
                if (state.phase != RaftAndroidAvatarDurablePhase.Idle ||
                    state.current?.identity != candidate.identity
                ) {
                    continue
                }
                val result =
                    retireCurrentLocked(
                        state = state,
                        identity = candidate.identity,
                        reason = RaftAndroidAvatarTombstoneReason.BudgetEviction,
                        notifyCuts = false
                    )
                if (result.status != RaftAndroidAvatarOperationStatus.Applied) {
                    val remaining = managedBytesLockedOrNegative()
                    return@withLock RaftAndroidAvatarBudgetResult(
                        RaftAndroidAvatarBudgetStatus.CleanupPending,
                        managedBytes = remaining,
                        evictedEntries = evicted
                    )
                }
                evicted += 1
                total = managedBytesLockedOrNegative()
                if (total < 0L) {
                    return@withLock RaftAndroidAvatarBudgetResult(
                        RaftAndroidAvatarBudgetStatus.StorageFailure,
                        managedBytes = total,
                        evictedEntries = evicted
                    )
                }
            }
            RaftAndroidAvatarBudgetResult(
                status =
                    if (total <= maxBytes) {
                        RaftAndroidAvatarBudgetStatus.Evicted
                    } else {
                        RaftAndroidAvatarBudgetStatus.Pinned
                    },
                managedBytes = total,
                evictedEntries = evicted
            )
        }

    internal fun durableStateForTest(
        key: RaftAndroidAvatarOpaqueKey
    ): RaftAndroidAvatarDurableState? =
        lock.withLock { loadResolvedStateLocked(key).state }

    internal fun decodedIdentityCountForTest(): Int = decodedStore.count()

    internal fun activeLeaseCountForTest(): Int = lock.withLock { activeLeases.size }

    private fun retireScope(
        scope: RaftAndroidAvatarTerminalScope
    ): RaftAndroidAvatarTerminalResult =
        lock.withLock {
            if (!ensureTerminalMarkerLocked(scope)) {
                closeTerminalAdmissionLocked(scope, notifyObserver = true)
                return@withLock RaftAndroidAvatarTerminalResult(
                    RaftAndroidAvatarTerminalStatus.StorageFailure,
                    retiredKeys = 0
                )
            }
            closeTerminalAdmissionLocked(scope, notifyObserver = true)
            completeTerminalScopeLocked(scope, notifyObserver = true)
        }

    private fun closeTerminalAdmissionLocked(
        scope: RaftAndroidAvatarTerminalScope,
        notifyObserver: Boolean
    ) {
        if (!closedScopes.add(scope)) return
        incrementEpochLocked(scope)
        requestSlots.keys.removeAll { key -> key.matches(scope) }
        if (notifyObserver) {
            terminalObserver.afterTerminalCut(
                scope,
                RaftAndroidAvatarTerminalCut.AdmissionClosed
            )
        }
    }

    private fun completeTerminalScopeLocked(
        scope: RaftAndroidAvatarTerminalScope,
        notifyObserver: Boolean
    ): RaftAndroidAvatarTerminalResult {
        try {
            while (activeLeases.values.any { lease -> lease.key.matches(scope) }) {
                leaseDrained.await()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return RaftAndroidAvatarTerminalResult(
                RaftAndroidAvatarTerminalStatus.Interrupted,
                retiredKeys = 0
            )
        }
        if (notifyObserver) {
            terminalObserver.afterTerminalCut(scope, RaftAndroidAvatarTerminalCut.LeasesDrained)
        }
        val keys = managedKeysLocked()
            ?: return RaftAndroidAvatarTerminalResult(
                RaftAndroidAvatarTerminalStatus.StorageFailure,
                retiredKeys = 0
            )
        val matchingKeys = keys.filter { it.matches(scope) }.sortedBy { it.relativeDirectory }
        for (key in matchingKeys) {
            if (!prepareTerminalTombstoneLocked(key)) {
                return RaftAndroidAvatarTerminalResult(
                    RaftAndroidAvatarTerminalStatus.CleanupPending,
                    retiredKeys = 0
                )
            }
        }
        if (!decodedStore.evictMatching(scope) || decodedStore.containsMatching(scope)) {
            return RaftAndroidAvatarTerminalResult(
                RaftAndroidAvatarTerminalStatus.CleanupPending,
                retiredKeys = 0
            )
        }
        var retired = 0
        for (key in matchingKeys) {
            if (!completeTerminalRetireKeyLocked(key)) {
                return RaftAndroidAvatarTerminalResult(
                    RaftAndroidAvatarTerminalStatus.CleanupPending,
                    retiredKeys = retired
                )
            }
            retired += 1
        }
        val remaining = files.listFiles()
            ?: return RaftAndroidAvatarTerminalResult(
                RaftAndroidAvatarTerminalStatus.StorageFailure,
                retiredKeys = retired
            )
        if (remaining.any { path ->
                RaftAndroidAvatarOpaqueKey.fromManagedPath(path)?.matches(scope) == true &&
                    RaftAndroidAvatarOpaqueKey.fromManagedPath(path)?.ownsManagedPath(path) == true
            }
        ) {
            return RaftAndroidAvatarTerminalResult(
                RaftAndroidAvatarTerminalStatus.CleanupPending,
                retiredKeys = retired
            )
        }
        val completedScopes =
            (closedScopes +
                remaining.mapNotNull(::raftAndroidAvatarTerminalScopeFromMarkerPath) +
                scope)
                .filter { candidate -> scope.containsTerminalScope(candidate) }
                .distinctBy { candidate -> candidate.markerPath }
                .sortedByDescending { candidate -> candidate.markerPath.length }
        for (completedScope in completedScopes) {
            if (!deleteDurablyLocked(completedScope.markerPath)) {
                return RaftAndroidAvatarTerminalResult(
                    RaftAndroidAvatarTerminalStatus.CleanupPending,
                    retiredKeys = retired
                )
            }
        }
        closedScopes.removeAll(completedScopes.toSet())
        if (notifyObserver) {
            terminalObserver.afterTerminalCut(scope, RaftAndroidAvatarTerminalCut.PhysicalZero)
        }
        return RaftAndroidAvatarTerminalResult(
            RaftAndroidAvatarTerminalStatus.Complete,
            retiredKeys = retired
        )
    }

    private fun prepareTerminalTombstoneLocked(key: RaftAndroidAvatarOpaqueKey): Boolean {
        val keyFiles = managedFilesForKeyLocked(key) ?: return false
        val resolved = loadResolvedStateLocked(key)
        if (resolved.failed) return false
        val dataAndTemps =
            keyFiles.filter { path ->
                path.isRaftAndroidAvatarDataPath() ||
                    path.substringAfterLast('/').startsWith("metadata-tmp-")
            }.toSet()
        val cleanupEntries =
            (resolved.state?.allEntryIdentities().orEmpty() +
                resolved.state?.cleanupEntries.orEmpty())
                .distinct()
                .sortedBy(RaftAndroidAvatarEntryIdentity::entryPath)
                .take(RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
        val cleanupArtifacts =
            (resolved.state?.cleanupArtifacts.orEmpty() + dataAndTemps)
                .sorted()
                .take(RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                .toSet()
        val existingTerminal =
            resolved.state?.takeIf { state ->
                state.phase == RaftAndroidAvatarDurablePhase.RollbackCleanup &&
                    state.current == null &&
                    state.tombstoneReason == RaftAndroidAvatarTombstoneReason.TerminalRetirement
            }
        val tombstone =
            RaftAndroidAvatarDurableState(
                key = key,
                commitGenerationHighWater =
                    resolved.state?.commitGenerationHighWater ?: 0L,
                phase = RaftAndroidAvatarDurablePhase.RollbackCleanup,
                transactionId = existingTerminal?.transactionId ?: nextIdentity(),
                capturedEpochs = existingTerminal?.capturedEpochs ?: epochsForLocked(key),
                current = null,
                previous = null,
                candidate = null,
                cleanupEntries = cleanupEntries,
                cleanupArtifacts = cleanupArtifacts,
                tombstoneReason = RaftAndroidAvatarTombstoneReason.TerminalRetirement
            )
        return persistJournalThenIndexLocked(tombstone, cut = null, notifyCuts = false)
    }

    private fun completeTerminalRetireKeyLocked(key: RaftAndroidAvatarOpaqueKey): Boolean {
        val resolved = loadResolvedStateLocked(key)
        if (resolved.failed || resolved.corrupt) return false
        val tombstone = resolved.state ?: return false
        if (tombstone.phase != RaftAndroidAvatarDurablePhase.RollbackCleanup ||
            tombstone.current != null ||
            tombstone.tombstoneReason != RaftAndroidAvatarTombstoneReason.TerminalRetirement
        ) {
            return false
        }
        tombstone.cleanupEntries.forEach { identity ->
            if (!decodedStore.evictExact(identity)) return false
        }
        if (!cleanupArtifactsLocked(tombstone.cleanupArtifacts)) return false
        return finishIdleLocked(
            tombstone.copy(cleanupArtifacts = emptySet()),
            current = null,
            notifyCuts = false,
            resetCommitNamespace = true
        )
    }

    private fun retireCurrentLocked(
        state: RaftAndroidAvatarDurableState,
        identity: RaftAndroidAvatarEntryIdentity,
        reason: RaftAndroidAvatarTombstoneReason,
        notifyCuts: Boolean
    ): RaftAndroidAvatarOperationResult {
        if (state.current?.identity != identity) {
            return RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.StaleRequest)
        }
        if (state.phase != RaftAndroidAvatarDurablePhase.Idle) {
            val cleanupEntries = (state.allEntryIdentities() + identity).distinct()
            val cleanupArtifacts =
                buildSet {
                    addAll(state.cleanupArtifacts)
                    cleanupEntries.forEach { cleanupIdentity ->
                        add(cleanupIdentity.entryPath)
                    }
                    state.candidate?.identity?.stagePath?.let(::add)
                }
            if (cleanupEntries.size > RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS ||
                cleanupArtifacts.size > RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS
            ) {
                return corruptState()
            }
            val repointed =
                state.copy(
                    phase = RaftAndroidAvatarDurablePhase.RollbackRepointed,
                    current = null,
                    previous = null,
                    cleanupEntries = cleanupEntries,
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason = reason
                )
            if (!persistIndexThenJournalLocked(
                    repointed,
                    firstCut = RaftAndroidAvatarDurableCut.RollbackRepointed,
                    completeCut = null,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            return continueRollbackLocked(repointed, notifyCuts = notifyCuts)
        }
        val tombstone =
            RaftAndroidAvatarDurableState(
                key = identity.key,
                commitGenerationHighWater = state.commitGenerationHighWater,
                phase = RaftAndroidAvatarDurablePhase.RollbackCleanup,
                transactionId = nextIdentity(),
                capturedEpochs = epochsForLocked(identity.key),
                current = null,
                previous = null,
                candidate = null,
                cleanupEntries = listOf(identity),
                cleanupArtifacts = setOf(identity.entryPath),
                tombstoneReason = reason
            )
        if (!persistJournalThenIndexLocked(
                tombstone,
                cut = RaftAndroidAvatarDurableCut.RollbackCleanup,
                notifyCuts = notifyCuts
            )
        ) {
            return cleanupPending()
        }
        if (!decodedStore.evictExact(identity)) return cleanupPending()
        if (!cleanupArtifactsLocked(tombstone.cleanupArtifacts)) return cleanupPending()
        return if (finishIdleLocked(tombstone.copy(cleanupArtifacts = emptySet()), null, notifyCuts)) {
            RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.Applied, identity)
        } else {
            cleanupPending()
        }
    }

    private fun continueCommitLocked(
        initial: RaftAndroidAvatarDurableState,
        notifyCuts: Boolean
    ): RaftAndroidAvatarOperationResult {
        var state = initial
        val candidate = state.candidate ?: return corruptState()
        if (state.phase == RaftAndroidAvatarDurablePhase.SharedCommitted) {
            when (ensureCandidateRenamedLocked(candidate.identity, notifyCuts)) {
                CandidateRenameOutcome.Ready -> Unit
                CandidateRenameOutcome.DurabilityPending -> return cleanupPending()
                CandidateRenameOutcome.StorageFailure -> return storageFailure()
                CandidateRenameOutcome.MissingOrInvalid ->
                    return continueRollbackLocked(
                        state.copy(tombstoneReason = RaftAndroidAvatarTombstoneReason.RecoveryFailure),
                        notifyCuts
                    )
            }
            state = state.copy(phase = RaftAndroidAvatarDurablePhase.CandidateRenamed)
            if (!persistJournalThenIndexLocked(
                    state,
                    cut = RaftAndroidAvatarDurableCut.CandidateRenamedRecorded,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
        }
        if (state.phase == RaftAndroidAvatarDurablePhase.CandidateRenamed) {
            when (inspectEntryLocked(candidate.identity)) {
                is EntryInspection.Present -> Unit
                EntryInspection.Failed -> return storageFailure()
                EntryInspection.Missing,
                EntryInspection.Invalid ->
                    return continueRollbackLocked(
                        state.copy(tombstoneReason = RaftAndroidAvatarTombstoneReason.RecoveryFailure),
                        notifyCuts
                    )
            }
            val switched =
                state.copy(
                    phase = RaftAndroidAvatarDurablePhase.IndexSwitched,
                    current = candidate,
                    cleanupEntries = listOfNotNull(state.previous?.identity),
                    cleanupArtifacts =
                        buildSet {
                            state.previous?.identity?.entryPath?.let(::add)
                            add(candidate.identity.stagePath)
                        }
                )
            if (!persistIndexThenJournalLocked(
                    switched,
                    firstCut = RaftAndroidAvatarDurableCut.IndexSwitched,
                    completeCut = RaftAndroidAvatarDurableCut.IndexSwitchedRecorded,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = switched
        }
        if (state.phase == RaftAndroidAvatarDurablePhase.IndexSwitched) {
            when (inspectEntryLocked(candidate.identity)) {
                is EntryInspection.Present -> Unit
                EntryInspection.Failed -> return storageFailure()
                EntryInspection.Missing,
                EntryInspection.Invalid ->
                    return continueRollbackLocked(
                        state.copy(tombstoneReason = RaftAndroidAvatarTombstoneReason.RecoveryFailure),
                        notifyCuts
                    )
            }
            val cleanup = state.copy(phase = RaftAndroidAvatarDurablePhase.CommitCleanup)
            if (!persistJournalThenIndexLocked(
                    cleanup,
                    cut = RaftAndroidAvatarDurableCut.CommitCleanup,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = cleanup
        }
        if (state.phase != RaftAndroidAvatarDurablePhase.CommitCleanup) {
            return corruptState()
        }
        state.cleanupEntries.forEach { previous ->
            if (!decodedStore.evictExact(previous)) return cleanupPending()
        }
        if (!cleanupArtifactsLocked(state.cleanupArtifacts)) return cleanupPending()
        return if (finishIdleLocked(state.copy(cleanupArtifacts = emptySet()), candidate, notifyCuts)) {
            RaftAndroidAvatarOperationResult(
                RaftAndroidAvatarOperationStatus.Applied,
                candidate.identity
            )
        } else {
            cleanupPending()
        }
    }

    private fun continueRollbackLocked(
        initial: RaftAndroidAvatarDurableState,
        notifyCuts: Boolean
    ): RaftAndroidAvatarOperationResult {
        var state = initial
        val candidate = state.candidate
        if (state.phase != RaftAndroidAvatarDurablePhase.RollbackRepointed &&
            state.phase != RaftAndroidAvatarDurablePhase.RollbackCleanup
        ) {
            val previous =
                state.previous?.let { record ->
                    when (inspectEntryLocked(record.identity)) {
                        is EntryInspection.Present -> record
                        EntryInspection.Missing,
                        EntryInspection.Invalid -> null
                        EntryInspection.Failed -> return storageFailure()
                    }
                }
            val cleanupEntries =
                buildList {
                    addAll(
                        state.cleanupEntries.filter { identity ->
                            identity != previous?.identity
                        }
                    )
                    candidate?.identity?.let(::add)
                    if (state.previous != null && previous == null) add(state.previous.identity)
                }
            val cleanupArtifacts =
                buildSet {
                    candidate?.identity?.stagePath?.let(::add)
                    candidate?.identity?.entryPath?.let(::add)
                    if (state.previous != null && previous == null) add(state.previous.identity.entryPath)
                    addAll(
                        state.cleanupArtifacts.filter { path ->
                            path != previous?.identity?.entryPath
                        }
                    )
                }
            val distinctCleanupEntries = cleanupEntries.distinct()
            if (distinctCleanupEntries.size > RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS ||
                cleanupArtifacts.size > RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS
            ) {
                return corruptState()
            }
            val repointed =
                state.copy(
                    phase = RaftAndroidAvatarDurablePhase.RollbackRepointed,
                    current = previous,
                    cleanupEntries = distinctCleanupEntries,
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason =
                        state.tombstoneReason ?: RaftAndroidAvatarTombstoneReason.RecoveryFailure
                )
            if (!persistIndexThenJournalLocked(
                    repointed,
                    firstCut = RaftAndroidAvatarDurableCut.RollbackRepointed,
                    completeCut = null,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = repointed
        }
        if (state.phase == RaftAndroidAvatarDurablePhase.RollbackRepointed) {
            val cleanup = state.copy(phase = RaftAndroidAvatarDurablePhase.RollbackCleanup)
            if (!persistJournalThenIndexLocked(
                    cleanup,
                    cut = RaftAndroidAvatarDurableCut.RollbackCleanup,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = cleanup
        }
        if (state.phase != RaftAndroidAvatarDurablePhase.RollbackCleanup) return corruptState()
        state.cleanupEntries.forEach { identity ->
            if (!decodedStore.evictExact(identity)) return cleanupPending()
        }
        if (!cleanupArtifactsLocked(state.cleanupArtifacts)) return cleanupPending()
        val current = state.current
        return if (finishIdleLocked(state.copy(cleanupArtifacts = emptySet()), current, notifyCuts)) {
            RaftAndroidAvatarOperationResult(
                RaftAndroidAvatarOperationStatus.Applied,
                current?.identity
            )
        } else {
            cleanupPending()
        }
    }

    private fun recoverStartupLocked(): RaftAndroidAvatarOperationResult {
        retryPendingDurabilityPathsLocked()
        val firstTerminal = recoverTerminalScopesLocked()
        if (firstTerminal.status == RaftAndroidAvatarOperationStatus.StorageFailure) {
            return storageFailure()
        }
        val ordinary = recoverAllLocked(notifyCuts = false)
        val secondTerminal =
            if (firstTerminal.status == RaftAndroidAvatarOperationStatus.CleanupPending) {
                recoverTerminalScopesLocked()
            } else {
                firstTerminal
            }
        val pendingDurability = retryPendingDurabilityPathsLocked()
        return combineRecoveryResults(
            combineRecoveryResults(ordinary, secondTerminal),
            pendingDurability
        )
    }

    private fun recoverTerminalScopesLocked(): RaftAndroidAvatarOperationResult {
        val listed = files.listFiles() ?: return storageFailure()
        val discovered = listed.mapNotNull(::raftAndroidAvatarTerminalScopeFromMarkerPath).toSet()
        val scopes = (closedScopes + discovered).sortedBy { it.markerPath }
        var pending = false
        for (scope in scopes) {
            if (scope !in closedScopes && !files.exists(scope.markerPath)) continue
            closeTerminalAdmissionLocked(scope, notifyObserver = false)
            if (!ensureTerminalMarkerLocked(scope)) return storageFailure()
            when (completeTerminalScopeLocked(scope, notifyObserver = false).status) {
                RaftAndroidAvatarTerminalStatus.Complete -> Unit
                RaftAndroidAvatarTerminalStatus.StorageFailure -> return storageFailure()
                RaftAndroidAvatarTerminalStatus.CleanupPending,
                RaftAndroidAvatarTerminalStatus.Interrupted -> pending = true
            }
        }
        return if (pending) cleanupPending() else RaftAndroidAvatarOperationResult(
            RaftAndroidAvatarOperationStatus.Applied
        )
    }

    private fun ensureTerminalMarkerLocked(scope: RaftAndroidAvatarTerminalScope): Boolean =
        when (files.read(scope.markerPath)) {
            is RaftAndroidAvatarFileRead.Present -> files.syncParent(scope.markerPath)
            RaftAndroidAvatarFileRead.Failed -> false
            RaftAndroidAvatarFileRead.Missing -> {
                files.writeNewDurably(scope.markerPath, TerminalMarkerBytes) ||
                    (files.exists(scope.markerPath) && files.syncParent(scope.markerPath))
            }
        }

    private fun combineRecoveryResults(
        first: RaftAndroidAvatarOperationResult,
        second: RaftAndroidAvatarOperationResult
    ): RaftAndroidAvatarOperationResult {
        if (first.status == RaftAndroidAvatarOperationStatus.StorageFailure ||
            second.status == RaftAndroidAvatarOperationStatus.StorageFailure
        ) {
            return storageFailure()
        }
        if (first.status == RaftAndroidAvatarOperationStatus.CleanupPending ||
            first.status == RaftAndroidAvatarOperationStatus.CorruptState ||
            second.status == RaftAndroidAvatarOperationStatus.CleanupPending ||
            second.status == RaftAndroidAvatarOperationStatus.CorruptState
        ) {
            return cleanupPending()
        }
        return RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.Applied)
    }

    private fun recoverAllLocked(notifyCuts: Boolean): RaftAndroidAvatarOperationResult {
        val keys = managedKeysLocked() ?: return storageFailure()
        var pending = false
        keys.forEach { key ->
            if (closedScopes.any(key::matches)) return@forEach
            val result = recoverKeyLocked(key, notifyCuts)
            when (result.status) {
                RaftAndroidAvatarOperationStatus.StorageFailure -> return result
                RaftAndroidAvatarOperationStatus.CleanupPending,
                RaftAndroidAvatarOperationStatus.CorruptState -> pending = true
                else -> Unit
            }
        }
        refreshNextAccessOrdinalLocked(keys)
        return if (pending) cleanupPending() else RaftAndroidAvatarOperationResult(
            RaftAndroidAvatarOperationStatus.Applied
        )
    }

    private fun recoverKeyLocked(
        key: RaftAndroidAvatarOpaqueKey,
        notifyCuts: Boolean
    ): RaftAndroidAvatarOperationResult {
        val keyFiles = managedFilesForKeyLocked(key) ?: return storageFailure()
        val resolved = loadResolvedStateLocked(key)
        if (resolved.failed) return storageFailure()
        if (resolved.corrupt || resolved.state == null) {
            val allDataAndTemps =
                keyFiles.filter { path ->
                    path.isRaftAndroidAvatarDataPath() ||
                        path.substringAfterLast('/').startsWith("metadata-tmp-")
                }.sorted()
            val dataAndTemps =
                allDataAndTemps
                    .take(RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                    .toSet()
            val hasMoreArtifacts = allDataAndTemps.size > dataAndTemps.size
            if (keyFiles.isEmpty()) return RaftAndroidAvatarOperationResult(
                RaftAndroidAvatarOperationStatus.Applied
            )
            val tombstone =
                RaftAndroidAvatarDurableState(
                    key = key,
                    commitGenerationHighWater = 0L,
                    phase = RaftAndroidAvatarDurablePhase.RollbackCleanup,
                    transactionId = nextIdentity(),
                    capturedEpochs = epochsForLocked(key),
                    current = null,
                    previous = null,
                    candidate = null,
                    cleanupEntries = emptyList(),
                    cleanupArtifacts = dataAndTemps,
                    tombstoneReason =
                        if (resolved.corrupt) {
                            RaftAndroidAvatarTombstoneReason.CorruptMetadata
                        } else {
                            RaftAndroidAvatarTombstoneReason.OrphanArtifact
                        }
                )
            if (!persistJournalThenIndexLocked(tombstone, null, false)) return cleanupPending()
            if (!decodedStore.evictKey(key) || decodedStore.containsKey(key)) {
                return cleanupPending()
            }
            if (!cleanupArtifactsLocked(dataAndTemps)) return cleanupPending()
            return if (finishIdleLocked(tombstone.copy(cleanupArtifacts = emptySet()), null, false)) {
                if (hasMoreArtifacts) {
                    cleanupPending()
                } else {
                    RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.Applied)
                }
            } else {
                cleanupPending()
            }
        }
        var state = resolved.state
        if (resolved.needsRewrite &&
            !persistJournalThenIndexLocked(state, cut = null, notifyCuts = false)
        ) {
            return cleanupPending()
        }
        return when (state.phase) {
            RaftAndroidAvatarDurablePhase.Idle -> recoverIdleLocked(state)
            RaftAndroidAvatarDurablePhase.Prepared,
            RaftAndroidAvatarDurablePhase.CandidateDurable ->
                continueRollbackLocked(state, notifyCuts)
            RaftAndroidAvatarDurablePhase.SharedCommitted,
            RaftAndroidAvatarDurablePhase.CandidateRenamed,
            RaftAndroidAvatarDurablePhase.IndexSwitched,
            RaftAndroidAvatarDurablePhase.CommitCleanup ->
                continueCommitLocked(state, notifyCuts)
            RaftAndroidAvatarDurablePhase.RollbackRepointed,
            RaftAndroidAvatarDurablePhase.RollbackCleanup ->
                continueRollbackLocked(state, notifyCuts)
        }
    }

    private fun recoverIdleLocked(
        state: RaftAndroidAvatarDurableState
    ): RaftAndroidAvatarOperationResult {
        if (!deleteDurablyLocked(state.key.journalPath)) {
            return cleanupPending()
        }
        val keyFiles = managedFilesForKeyLocked(state.key) ?: return storageFailure()
        val currentPath = state.current?.identity?.entryPath
        val allOrphans =
            keyFiles.filter { path ->
                (path.isRaftAndroidAvatarDataPath() && path != currentPath) ||
                    path.substringAfterLast('/').startsWith("metadata-tmp-")
            }.sorted()
        val currentInspection = state.current?.let { inspectEntryLocked(it.identity) }
        if (currentInspection == EntryInspection.Failed) return storageFailure()
        if (state.current != null &&
            (currentInspection == EntryInspection.Missing || currentInspection == EntryInspection.Invalid)
        ) {
            val allCleanupArtifacts =
                (listOf(state.current.identity.entryPath) + allOrphans).distinct()
            val cleanupArtifacts =
                allCleanupArtifacts
                    .take(RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                    .toSet()
            val hasMoreArtifacts = allCleanupArtifacts.size > cleanupArtifacts.size
            val tombstone =
                RaftAndroidAvatarDurableState(
                    key = state.key,
                    commitGenerationHighWater = state.commitGenerationHighWater,
                    phase = RaftAndroidAvatarDurablePhase.RollbackCleanup,
                    transactionId = nextIdentity(),
                    capturedEpochs = epochsForLocked(state.key),
                    current = null,
                    previous = null,
                    candidate = null,
                    cleanupEntries = listOf(state.current.identity),
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason = RaftAndroidAvatarTombstoneReason.InvalidBytes
                )
            if (!persistJournalThenIndexLocked(tombstone, null, false)) return cleanupPending()
            if (!decodedStore.evictExact(state.current.identity)) return cleanupPending()
            if (!cleanupArtifactsLocked(tombstone.cleanupArtifacts)) return cleanupPending()
            return if (finishIdleLocked(tombstone.copy(cleanupArtifacts = emptySet()), null, false)) {
                if (hasMoreArtifacts) {
                    cleanupPending()
                } else {
                    RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.Applied)
                }
            } else {
                cleanupPending()
            }
        }
        if (allOrphans.isNotEmpty()) {
            val orphans =
                allOrphans
                    .take(RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                    .toSet()
            val hasMoreArtifacts = allOrphans.size > orphans.size
            val tombstone =
                RaftAndroidAvatarDurableState(
                    key = state.key,
                    commitGenerationHighWater = state.commitGenerationHighWater,
                    phase = RaftAndroidAvatarDurablePhase.RollbackCleanup,
                    transactionId = nextIdentity(),
                    capturedEpochs = epochsForLocked(state.key),
                    current = state.current,
                    previous = null,
                    candidate = null,
                    cleanupEntries = emptyList(),
                    cleanupArtifacts = orphans,
                    tombstoneReason = RaftAndroidAvatarTombstoneReason.OrphanArtifact
                )
            if (!persistJournalThenIndexLocked(tombstone, null, false)) return cleanupPending()
            if (!cleanupArtifactsLocked(orphans)) return cleanupPending()
            if (!finishIdleLocked(tombstone.copy(cleanupArtifacts = emptySet()), state.current, false)) {
                return cleanupPending()
            }
            if (hasMoreArtifacts) return cleanupPending()
        }
        if (state.current == null &&
            state.commitGenerationHighWater == 0L &&
            !deleteDurablyLocked(state.key.indexPath)
        ) {
            return cleanupPending()
        }
        return RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.Applied)
    }

    private fun ensureCandidateRenamedLocked(
        identity: RaftAndroidAvatarEntryIdentity,
        notifyCuts: Boolean
    ): CandidateRenameOutcome {
        when (inspectEntryLocked(identity)) {
            is EntryInspection.Present ->
                return if (files.syncParent(identity.entryPath)) {
                    CandidateRenameOutcome.Ready
                } else {
                    CandidateRenameOutcome.DurabilityPending
                }
            EntryInspection.Failed -> return CandidateRenameOutcome.StorageFailure
            EntryInspection.Invalid -> return CandidateRenameOutcome.MissingOrInvalid
            EntryInspection.Missing -> Unit
        }
        when (inspectPathLocked(identity.stagePath, identity)) {
            is EntryInspection.Present -> Unit
            EntryInspection.Failed -> return CandidateRenameOutcome.StorageFailure
            EntryInspection.Missing,
            EntryInspection.Invalid -> return CandidateRenameOutcome.MissingOrInvalid
        }
        if (!files.moveAtomically(identity.stagePath, identity.entryPath)) {
            return when (inspectEntryLocked(identity)) {
                is EntryInspection.Present -> CandidateRenameOutcome.DurabilityPending
                EntryInspection.Failed -> CandidateRenameOutcome.StorageFailure
                EntryInspection.Missing,
                EntryInspection.Invalid -> CandidateRenameOutcome.DurabilityPending
            }
        }
        if (notifyCuts) {
            durableObserver.afterDurableCut(
                identity.key,
                RaftAndroidAvatarDurableCut.CandidateRenamed
            )
        }
        return when (inspectEntryLocked(identity)) {
            is EntryInspection.Present -> CandidateRenameOutcome.Ready
            EntryInspection.Failed -> CandidateRenameOutcome.StorageFailure
            EntryInspection.Missing,
            EntryInspection.Invalid -> CandidateRenameOutcome.MissingOrInvalid
        }
    }

    private fun inspectCandidateSourceLocked(
        identity: RaftAndroidAvatarEntryIdentity,
    ): EntryInspection =
        when (val entry = inspectEntryLocked(identity)) {
            is EntryInspection.Present,
            EntryInspection.Failed,
            EntryInspection.Invalid -> entry
            EntryInspection.Missing -> inspectPathLocked(identity.stagePath, identity)
        }

    private fun inspectEntryLocked(
        identity: RaftAndroidAvatarEntryIdentity
    ): EntryInspection = inspectPathLocked(identity.entryPath, identity)

    private fun RaftAndroidAvatarEntryIdentity.matchesPayload(
        sha256: String,
        byteLength: Long,
        contentType: String
    ): Boolean =
        this.sha256 == sha256 &&
            this.byteLength == byteLength &&
            this.contentType == contentType

    private fun inspectPathLocked(
        path: String,
        identity: RaftAndroidAvatarEntryIdentity
    ): EntryInspection =
        when (val read = files.read(path)) {
            is RaftAndroidAvatarFileRead.Present ->
                if (read.bytes.size.toLong() == identity.byteLength &&
                    read.bytes.raftSha256Hex() == identity.sha256
                ) {
                    EntryInspection.Present(read.bytes)
                } else {
                    EntryInspection.Invalid
                }
            RaftAndroidAvatarFileRead.Missing -> EntryInspection.Missing
            RaftAndroidAvatarFileRead.Failed -> EntryInspection.Failed
        }

    private fun persistJournalThenIndexLocked(
        state: RaftAndroidAvatarDurableState,
        cut: RaftAndroidAvatarDurableCut?,
        notifyCuts: Boolean
    ): Boolean {
        val persisted = withNextStateSequenceLocked(state) ?: return false
        val journal =
            RaftAndroidAvatarDurableStateCodec.encode(
                RaftAndroidAvatarDurableRecordKind.Journal,
                persisted
            )
        val index =
            RaftAndroidAvatarDurableStateCodec.encode(
                RaftAndroidAvatarDurableRecordKind.Index,
                persisted
            )
        if (!files.writeAtomically(persisted.key.journalPath, journal)) return false
        if (!files.writeAtomically(persisted.key.indexPath, index)) return false
        if (notifyCuts && cut != null) durableObserver.afterDurableCut(persisted.key, cut)
        return true
    }

    private fun persistIndexThenJournalLocked(
        state: RaftAndroidAvatarDurableState,
        firstCut: RaftAndroidAvatarDurableCut,
        completeCut: RaftAndroidAvatarDurableCut?,
        notifyCuts: Boolean
    ): Boolean {
        val persisted = withNextStateSequenceLocked(state) ?: return false
        val index =
            RaftAndroidAvatarDurableStateCodec.encode(
                RaftAndroidAvatarDurableRecordKind.Index,
                persisted
            )
        val journal =
            RaftAndroidAvatarDurableStateCodec.encode(
                RaftAndroidAvatarDurableRecordKind.Journal,
                persisted
            )
        if (!files.writeAtomically(persisted.key.indexPath, index)) return false
        if (notifyCuts) durableObserver.afterDurableCut(persisted.key, firstCut)
        if (!files.writeAtomically(persisted.key.journalPath, journal)) return false
        if (notifyCuts && completeCut != null) {
            durableObserver.afterDurableCut(persisted.key, completeCut)
        }
        return true
    }

    private fun finishIdleLocked(
        cleanupState: RaftAndroidAvatarDurableState,
        current: RaftAndroidAvatarEntryRecord?,
        notifyCuts: Boolean,
        resetCommitNamespace: Boolean = false
    ): Boolean {
        if (current == null &&
            (resetCommitNamespace || cleanupState.commitGenerationHighWater == 0L)
        ) {
            if (!deleteDurablyLocked(cleanupState.key.indexPath)) return false
            if (!deleteDurablyLocked(cleanupState.key.journalPath)) return false
        } else {
            val idle =
                RaftAndroidAvatarDurableState.idle(
                    key = cleanupState.key,
                    current = current,
                    commitGenerationHighWater = cleanupState.commitGenerationHighWater
                )
            if (!persistJournalThenIndexLocked(idle, cut = null, notifyCuts = false)) return false
            if (!deleteDurablyLocked(cleanupState.key.journalPath)) return false
        }
        if (notifyCuts) {
            durableObserver.afterDurableCut(cleanupState.key, RaftAndroidAvatarDurableCut.Idle)
        }
        return true
    }

    private fun persistCurrentTouchLocked(
        touched: RaftAndroidAvatarDurableState,
        original: RaftAndroidAvatarDurableState
    ) {
        val persisted = withNextStateSequenceLocked(touched) ?: return
        val index =
            RaftAndroidAvatarDurableStateCodec.encode(
                RaftAndroidAvatarDurableRecordKind.Index,
                persisted
            )
        if (!files.writeAtomically(persisted.key.indexPath, index)) return
        if (original.phase != RaftAndroidAvatarDurablePhase.Idle) {
            val journal =
                RaftAndroidAvatarDurableStateCodec.encode(
                    RaftAndroidAvatarDurableRecordKind.Journal,
                    persisted
                )
            files.writeAtomically(persisted.key.journalPath, journal)
        }
    }

    private fun withNextStateSequenceLocked(
        state: RaftAndroidAvatarDurableState
    ): RaftAndroidAvatarDurableState? {
        val index =
            loadRecordLocked(
                state.key.indexPath,
                RaftAndroidAvatarDurableRecordKind.Index,
                state.key
            )
        val journal =
            loadRecordLocked(
                state.key.journalPath,
                RaftAndroidAvatarDurableRecordKind.Journal,
                state.key
            )
        if (index.status == RecordStatus.Failed || journal.status == RecordStatus.Failed) return null
        val latest = maxOf(
            state.stateSequence,
            index.state?.stateSequence ?: 0L,
            journal.state?.stateSequence ?: 0L
        )
        if (latest == Long.MAX_VALUE) return null
        return state.copy(stateSequence = latest + 1L)
    }

    private fun cleanupArtifactsLocked(paths: Set<String>): Boolean =
        paths.sorted().all(::deleteDurablyLocked)

    private fun deleteDurablyLocked(path: String): Boolean {
        val deleted = files.deleteDurably(path)
        if (deleted) {
            pendingDurabilityPaths.remove(path)
        } else {
            pendingDurabilityPaths.add(path)
        }
        return deleted
    }

    private fun hasPendingDurabilityLocked(key: RaftAndroidAvatarOpaqueKey): Boolean =
        pendingDurabilityPaths.any(key::ownsManagedPath)

    private fun retryPendingDurabilityPathsLocked(): RaftAndroidAvatarOperationResult {
        var pending = false
        pendingDurabilityPaths.toList().sorted().forEach { path ->
            if (!deleteDurablyLocked(path)) pending = true
        }
        return if (pending) cleanupPending() else RaftAndroidAvatarOperationResult(
            RaftAndroidAvatarOperationStatus.Applied
        )
    }

    private fun loadResolvedStateLocked(key: RaftAndroidAvatarOpaqueKey): ResolvedState {
        val index = loadRecordLocked(key.indexPath, RaftAndroidAvatarDurableRecordKind.Index, key)
        val journal = loadRecordLocked(key.journalPath, RaftAndroidAvatarDurableRecordKind.Journal, key)
        if (index.status == RecordStatus.Failed || journal.status == RecordStatus.Failed) {
            return ResolvedState(null, needsRewrite = false, corrupt = false, failed = true)
        }
        val validIndex = index.state
        val validJournal = journal.state
        if (validIndex == null && validJournal == null) {
            return ResolvedState(
                state = null,
                needsRewrite = false,
                corrupt = index.status == RecordStatus.Corrupt || journal.status == RecordStatus.Corrupt,
                failed = false
            )
        }
        if (validIndex == null) {
            return ResolvedState(
                state = validJournal,
                needsRewrite = true,
                corrupt = false,
                failed = false
            )
        }
        if (validJournal == null) {
            return ResolvedState(
                state = validIndex,
                needsRewrite = validIndex.phase != RaftAndroidAvatarDurablePhase.Idle,
                corrupt = false,
                failed = false
            )
        }
        if (validIndex == validJournal) {
            return ResolvedState(validIndex, needsRewrite = false, corrupt = false, failed = false)
        }
        val chosen = chooseMoreAuthoritative(validIndex, validJournal)
            ?: return ResolvedState(null, needsRewrite = false, corrupt = true, failed = false)
        return ResolvedState(chosen, needsRewrite = true, corrupt = false, failed = false)
    }

    private fun loadRecordLocked(
        path: String,
        kind: RaftAndroidAvatarDurableRecordKind,
        expectedKey: RaftAndroidAvatarOpaqueKey
    ): LoadedRecord =
        when (val read = files.read(path)) {
            RaftAndroidAvatarFileRead.Missing -> LoadedRecord(RecordStatus.Missing)
            RaftAndroidAvatarFileRead.Failed -> LoadedRecord(RecordStatus.Failed)
            is RaftAndroidAvatarFileRead.Present -> {
                val decoded = RaftAndroidAvatarDurableStateCodec.decode(kind, read.bytes)
                if (decoded == null || decoded.key != expectedKey) {
                    LoadedRecord(RecordStatus.Corrupt)
                } else {
                    LoadedRecord(RecordStatus.Valid, decoded)
                }
            }
        }

    private fun chooseMoreAuthoritative(
        first: RaftAndroidAvatarDurableState,
        second: RaftAndroidAvatarDurableState
    ): RaftAndroidAvatarDurableState? {
        if (first.key != second.key) return null
        if (first.phase != RaftAndroidAvatarDurablePhase.Idle &&
            second.phase != RaftAndroidAvatarDurablePhase.Idle &&
            first.transactionId != second.transactionId
        ) {
            return null
        }
        if (first.stateSequence != second.stateSequence) {
            return if (first.stateSequence > second.stateSequence) first else second
        }
        return null
    }

    private fun managedKeysLocked(): Set<RaftAndroidAvatarOpaqueKey>? =
        files.listFiles()?.mapNotNull(RaftAndroidAvatarOpaqueKey::fromManagedPath)?.toSet()

    private fun managedFilesForKeyLocked(key: RaftAndroidAvatarOpaqueKey): Set<String>? =
        files.listFiles()?.filter { path -> key.ownsManagedPath(path) }?.toSet()

    private fun managedDataFilesLocked(): Set<String>? =
        files.listFiles()?.filter(String::isRaftAndroidAvatarDataPath)?.filter { path ->
            RaftAndroidAvatarOpaqueKey.fromManagedPath(path) != null
        }?.toSet()

    private fun sizeOfLocked(paths: Set<String>): Long? {
        var total = 0L
        paths.forEach { path ->
            val size = files.fileSize(path) ?: return null
            if (size < 0L) return null
            if (Long.MAX_VALUE - total < size) return null
            total += size
        }
        return total
    }

    private fun managedBytesLockedOrNegative(): Long =
        managedDataFilesLocked()?.let(::sizeOfLocked) ?: -1L

    private fun refreshNextAccessOrdinalLocked(keys: Set<RaftAndroidAvatarOpaqueKey>) {
        val max = keys.maxOfOrNull { key ->
            loadResolvedStateLocked(key).state?.allEntryRecords()?.maxOfOrNull {
                it.lastAccessOrdinal
            } ?: 0L
        } ?: 0L
        nextAccessOrdinal = max.coerceAtMost(Long.MAX_VALUE - 1L) + 1L
    }

    private fun nextAccessOrdinalLocked(): Long =
        nextAccessOrdinal.also { current ->
            nextAccessOrdinal = if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
        }

    private fun acquireLeaseLocked(
        fence: RaftAndroidAvatarRequestFence,
        kind: RaftAndroidAvatarLeaseKind
    ): RaftAndroidAvatarLease? {
        if (!isFenceCurrentLocked(fence)) return null
        val id = nextLeaseId++
        activeLeases[id] = ActiveLease(fence.key, kind)
        return RaftAndroidAvatarLease(kind, fence, id, ::releaseLease)
    }

    private fun releaseLease(id: Long) {
        lock.withLock {
            if (activeLeases.remove(id) != null) leaseDrained.signalAll()
        }
    }

    private fun isFenceCurrentLocked(fence: RaftAndroidAvatarRequestFence): Boolean {
        if (recoveryAdmissionBlocked || closedScopes.any(fence.key::matches)) return false
        val slot = requestSlots[fence.key] ?: return false
        return slot.requestGeneration == fence.requestGeneration &&
            slot.sessionIdentity == fence.sessionIdentity &&
            slot.epochs == fence.terminalEpochs &&
            fence.terminalEpochs == epochsForLocked(fence.key)
    }

    private fun epochsForLocked(key: RaftAndroidAvatarOpaqueKey): RaftAndroidAvatarTerminalEpochs =
        RaftAndroidAvatarTerminalEpochs(
            global = globalEpoch,
            owner = ownerEpochs[key.partitionKey to key.ownerKey] ?: 0L,
            subject =
                subjectEpochs[Triple(key.partitionKey, key.ownerKey, key.subjectKey)] ?: 0L
        )

    private fun RaftAndroidAvatarTerminalScope.containsTerminalScope(
        candidate: RaftAndroidAvatarTerminalScope
    ): Boolean =
        when (this) {
            RaftAndroidAvatarTerminalScope.All -> true
            is RaftAndroidAvatarTerminalScope.Owner ->
                when (candidate) {
                    RaftAndroidAvatarTerminalScope.All -> false
                    is RaftAndroidAvatarTerminalScope.Owner ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                    is RaftAndroidAvatarTerminalScope.Subject ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                }
            is RaftAndroidAvatarTerminalScope.Subject -> this == candidate
        }

    private fun incrementEpochLocked(scope: RaftAndroidAvatarTerminalScope) {
        when (scope) {
            RaftAndroidAvatarTerminalScope.All -> globalEpoch = globalEpoch.incrementEpoch()
            is RaftAndroidAvatarTerminalScope.Owner -> {
                val key = scope.partitionKey to scope.ownerKey
                ownerEpochs[key] = (ownerEpochs[key] ?: 0L).incrementEpoch()
            }
            is RaftAndroidAvatarTerminalScope.Subject -> {
                val key = Triple(scope.partitionKey, scope.ownerKey, scope.subjectKey)
                subjectEpochs[key] = (subjectEpochs[key] ?: 0L).incrementEpoch()
            }
        }
    }

    private fun Long.incrementEpoch(): Long =
        also { require(it < Long.MAX_VALUE) } + 1L

    private fun RequestSlot.toFence(key: RaftAndroidAvatarOpaqueKey): RaftAndroidAvatarRequestFence =
        RaftAndroidAvatarRequestFence(
            key = key,
            requestGeneration = requestGeneration,
            sessionIdentity = sessionIdentity,
            terminalEpochs = epochs
        )

    private fun nextIdentity(): String =
        identityFactory.nextOpaqueIdentity().also { identity ->
            require(identity.matches(RaftAndroidAvatarOpaqueComponentPattern))
        }

    private fun staleOrClosedResult(key: RaftAndroidAvatarOpaqueKey): RaftAndroidAvatarOperationResult =
        RaftAndroidAvatarOperationResult(
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                RaftAndroidAvatarOperationStatus.AdmissionClosed
            } else {
                RaftAndroidAvatarOperationStatus.StaleRequest
            }
        )

    private fun cleanupPending(): RaftAndroidAvatarOperationResult =
        RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.CleanupPending)

    private fun storageFailure(): RaftAndroidAvatarOperationResult =
        RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.StorageFailure)

    private fun corruptState(): RaftAndroidAvatarOperationResult =
        RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.CorruptState)

    private companion object {
        val TerminalMarkerBytes = "raft-avatar-terminal-v1".toByteArray(Charsets.UTF_8)
    }
}
