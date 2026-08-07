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
internal class SlockAndroidAvatarDurableKernel(
    private val files: SlockAndroidAvatarDurableFileSystem,
    private val identityFactory: SlockAndroidAvatarIdentityFactory =
        SlockAndroidAvatarSecureIdentityFactory(),
    private val decodedStore: SlockAndroidAvatarDecodedIdentityStore =
        SlockAndroidAvatarInMemoryDecodedIdentityStore(),
    private val durableObserver: SlockAndroidAvatarDurableObserver =
        SlockAndroidAvatarDurableObserver { _, _ -> },
    private val terminalObserver: SlockAndroidAvatarTerminalObserver =
        SlockAndroidAvatarTerminalObserver { _, _ -> }
) {
    private data class RequestSlot(
        val requestGeneration: Long,
        val sessionIdentity: String,
        val epochs: SlockAndroidAvatarTerminalEpochs
    )

    private data class ActiveLease(
        val key: SlockAndroidAvatarOpaqueKey,
        val kind: SlockAndroidAvatarLeaseKind
    )

    private enum class RecordStatus {
        Missing,
        Valid,
        Corrupt,
        Failed
    }

    private data class LoadedRecord(
        val status: RecordStatus,
        val state: SlockAndroidAvatarDurableState? = null
    )

    private data class ResolvedState(
        val state: SlockAndroidAvatarDurableState?,
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
    private val requestSlots = mutableMapOf<SlockAndroidAvatarOpaqueKey, RequestSlot>()
    private val activeLeases = mutableMapOf<Long, ActiveLease>()
    private val closedScopes = linkedSetOf<SlockAndroidAvatarTerminalScope>()
    private val pendingDurabilityPaths = linkedSetOf<String>()
    private val ownerEpochs = mutableMapOf<Pair<String, String>, Long>()
    private val subjectEpochs = mutableMapOf<Triple<String, String, String>, Long>()
    private var globalEpoch = 0L
    private var nextLeaseId = 1L
    private var nextAccessOrdinal = 1L
    private var recoveryAdmissionBlocked = false

    val startupRecovery: SlockAndroidAvatarOperationResult

    init {
        startupRecovery =
            lock.withLock {
                recoverStartupLocked().also { result ->
                    recoveryAdmissionBlocked =
                        result.status != SlockAndroidAvatarOperationStatus.Applied
                }
            }
    }

    fun beginRequest(
        key: SlockAndroidAvatarOpaqueKey,
        requestGeneration: Long
    ): SlockAndroidAvatarRequestResult =
        lock.withLock {
            require(requestGeneration > 0L)
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                return@withLock SlockAndroidAvatarRequestResult(
                    SlockAndroidAvatarRequestStatus.AdmissionClosed
                )
            }
            val epochs = epochsForLocked(key)
            val existing = requestSlots[key]
            if (existing != null && existing.epochs == epochs) {
                if (requestGeneration < existing.requestGeneration) {
                    return@withLock SlockAndroidAvatarRequestResult(
                        SlockAndroidAvatarRequestStatus.Stale
                    )
                }
                if (requestGeneration == existing.requestGeneration) {
                    return@withLock SlockAndroidAvatarRequestResult(
                        status = SlockAndroidAvatarRequestStatus.Accepted,
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
            SlockAndroidAvatarRequestResult(
                status = SlockAndroidAvatarRequestStatus.Accepted,
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
        fence: SlockAndroidAvatarRequestFence,
        expectedCurrentCommitIdentity: SlockAndroidAvatarCommitIdentity?
    ): SlockAndroidAvatarIdentityReservationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(fence)) {
                return@withLock SlockAndroidAvatarIdentityReservationResult(
                    staleOrClosedResult(fence.key).status
                )
            }
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed) {
                return@withLock SlockAndroidAvatarIdentityReservationResult(
                    SlockAndroidAvatarOperationStatus.StorageFailure
                )
            }
            if (resolved.corrupt) {
                return@withLock SlockAndroidAvatarIdentityReservationResult(
                    SlockAndroidAvatarOperationStatus.CorruptState
                )
            }
            val state = resolved.state ?: SlockAndroidAvatarDurableState.idle(fence.key)
            if (state.phase != SlockAndroidAvatarDurablePhase.Idle ||
                files.exists(fence.key.journalPath) ||
                hasPendingDurabilityLocked(fence.key)
            ) {
                return@withLock SlockAndroidAvatarIdentityReservationResult(
                    SlockAndroidAvatarOperationStatus.Busy
                )
            }
            val currentIdentity = state.current?.identity
            if (currentIdentity?.commitIdentity != expectedCurrentCommitIdentity) {
                return@withLock SlockAndroidAvatarIdentityReservationResult(
                    SlockAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            currentIdentity?.let { current ->
                when (inspectEntryLocked(current)) {
                    is EntryInspection.Present -> Unit
                    EntryInspection.Failed ->
                        return@withLock SlockAndroidAvatarIdentityReservationResult(
                            SlockAndroidAvatarOperationStatus.StorageFailure
                        )
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        retireCurrentLocked(
                            state = state,
                            identity = current,
                            reason = SlockAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock SlockAndroidAvatarIdentityReservationResult(
                            SlockAndroidAvatarOperationStatus.CleanupPending
                        )
                    }
                }
            }
            if (state.commitGenerationHighWater == Long.MAX_VALUE) {
                return@withLock SlockAndroidAvatarIdentityReservationResult(
                    SlockAndroidAvatarOperationStatus.StorageFailure
                )
            }
            SlockAndroidAvatarIdentityReservationResult(
                status = SlockAndroidAvatarOperationStatus.Applied,
                commitIdentity =
                    SlockAndroidAvatarCommitIdentity(
                        commitGeneration = state.commitGenerationHighWater + 1L,
                        nonce = nextIdentity()
                    )
            )
        }

    fun acquireLease(
        fence: SlockAndroidAvatarRequestFence,
        kind: SlockAndroidAvatarLeaseKind
    ): SlockAndroidAvatarLease? =
        lock.withLock {
            acquireLeaseLocked(fence, kind)
        }

    fun stageCandidate(
        fence: SlockAndroidAvatarRequestFence,
        expectedCurrentCommitIdentity: SlockAndroidAvatarCommitIdentity?,
        candidateCommitIdentity: SlockAndroidAvatarCommitIdentity,
        bytes: ByteArray,
        contentType: String
    ): SlockAndroidAvatarOperationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(fence)) return@withLock staleOrClosedResult(fence.key)
            if (bytes.isEmpty()) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.StorageFailure
                )
            }
            val candidateSha256 = bytes.slockSha256Hex()
            val candidateByteLength = bytes.size.toLong()
            val candidateContentType = contentType.slockNormalizedAvatarContentType()
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val currentState = resolved.state ?: SlockAndroidAvatarDurableState.idle(fence.key)
            currentState.candidate?.identity
                ?.takeIf { identity -> identity.commitIdentity == candidateCommitIdentity }
                ?.let { activeCandidate ->
                    if (!activeCandidate.matchesPayload(
                            sha256 = candidateSha256,
                            byteLength = candidateByteLength,
                            contentType = candidateContentType
                        )
                    ) {
                        return@withLock SlockAndroidAvatarOperationResult(
                            SlockAndroidAvatarOperationStatus.IdentityMismatch
                        )
                    }
                    when (currentState.phase) {
                        SlockAndroidAvatarDurablePhase.CandidateDurable,
                        SlockAndroidAvatarDurablePhase.SharedCommitted,
                        SlockAndroidAvatarDurablePhase.CandidateRenamed,
                        SlockAndroidAvatarDurablePhase.IndexSwitched,
                        SlockAndroidAvatarDurablePhase.CommitCleanup ->
                            when (inspectCandidateSourceLocked(activeCandidate)) {
                                is EntryInspection.Present ->
                                    return@withLock SlockAndroidAvatarOperationResult(
                                        status = SlockAndroidAvatarOperationStatus.AlreadyApplied,
                                        entry = activeCandidate
                                    )
                                EntryInspection.Failed -> return@withLock storageFailure()
                                EntryInspection.Missing,
                                EntryInspection.Invalid -> return@withLock cleanupPending()
                            }
                        SlockAndroidAvatarDurablePhase.Prepared ->
                            return@withLock cleanupPending()
                        SlockAndroidAvatarDurablePhase.RollbackRepointed,
                        SlockAndroidAvatarDurablePhase.RollbackCleanup ->
                            return@withLock SlockAndroidAvatarOperationResult(
                                SlockAndroidAvatarOperationStatus.IdentityMismatch
                            )
                        SlockAndroidAvatarDurablePhase.Idle -> return@withLock corruptState()
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
                        return@withLock SlockAndroidAvatarOperationResult(
                            SlockAndroidAvatarOperationStatus.IdentityMismatch
                        )
                    }
                    when (inspectEntryLocked(committed)) {
                        is EntryInspection.Present ->
                            return@withLock SlockAndroidAvatarOperationResult(
                                status = SlockAndroidAvatarOperationStatus.AlreadyApplied,
                                entry = committed
                            )
                        EntryInspection.Failed -> return@withLock storageFailure()
                        EntryInspection.Missing,
                        EntryInspection.Invalid -> {
                            retireCurrentLocked(
                                state = currentState,
                                identity = committed,
                                reason = SlockAndroidAvatarTombstoneReason.InvalidBytes,
                                notifyCuts = false
                            )
                            return@withLock cleanupPending()
                        }
                    }
                }
            if (candidateCommitIdentity.commitGeneration <=
                currentState.commitGenerationHighWater
            ) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            if (hasPendingDurabilityLocked(fence.key)) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.Busy
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
            if (currentState.phase != SlockAndroidAvatarDurablePhase.Idle ||
                files.exists(fence.key.journalPath) ||
                keyFiles.any { path -> path !in allowedIdleFiles }
            ) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.Busy
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
                            reason = SlockAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock cleanupPending()
                    }
                }
            }
            if (currentState.current?.identity?.commitIdentity != expectedCurrentCommitIdentity) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            val txId = nextIdentity()
            val candidateIdentity =
                SlockAndroidAvatarEntryIdentity(
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
                SlockAndroidAvatarEntryRecord(
                    identity = candidateIdentity,
                    lastAccessOrdinal = nextAccessOrdinalLocked()
                )
            val prepared =
                SlockAndroidAvatarDurableState(
                    key = fence.key,
                    commitGenerationHighWater =
                        maxOf(
                            currentState.commitGenerationHighWater,
                            candidateCommitIdentity.commitGeneration
                        ),
                    phase = SlockAndroidAvatarDurablePhase.Prepared,
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
                    cut = SlockAndroidAvatarDurableCut.Prepared,
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
                SlockAndroidAvatarDurableCut.CandidateFileDurable
            )
            val durable = prepared.copy(phase = SlockAndroidAvatarDurablePhase.CandidateDurable)
            if (!persistJournalThenIndexLocked(
                    state = durable,
                    cut = SlockAndroidAvatarDurableCut.CandidateDurable,
                    notifyCuts = true
                )
            ) {
                return@withLock cleanupPending()
            }
            SlockAndroidAvatarOperationResult(
                status = SlockAndroidAvatarOperationStatus.Applied,
                entry = candidateIdentity
            )
        }

    fun finalizeCandidate(
        fence: SlockAndroidAvatarRequestFence,
        candidateCommitIdentity: SlockAndroidAvatarCommitIdentity
    ): SlockAndroidAvatarOperationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(fence)) return@withLock staleOrClosedResult(fence.key)
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.NotFound
                )
            if (state.phase == SlockAndroidAvatarDurablePhase.Idle) {
                if (hasPendingDurabilityLocked(fence.key)) return@withLock cleanupPending()
                return@withLock if (state.current?.identity?.commitIdentity == candidateCommitIdentity) {
                    SlockAndroidAvatarOperationResult(
                        SlockAndroidAvatarOperationStatus.AlreadyApplied,
                        state.current.identity
                    )
                } else {
                    SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.NotFound)
                }
            }
            val candidate = state.candidate
                ?: return@withLock corruptState()
            if (candidate.identity.commitIdentity != candidateCommitIdentity ||
                state.transactionId != candidate.identity.txId
            ) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            if (state.capturedEpochs != fence.terminalEpochs) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.StaleRequest
                )
            }
            val sharedCommitted =
                if (state.phase == SlockAndroidAvatarDurablePhase.CandidateDurable) {
                    when (inspectCandidateSourceLocked(candidate.identity)) {
                        is EntryInspection.Present -> Unit
                        EntryInspection.Failed -> return@withLock storageFailure()
                        EntryInspection.Missing,
                        EntryInspection.Invalid ->
                            return@withLock SlockAndroidAvatarOperationResult(
                                SlockAndroidAvatarOperationStatus.StorageFailure
                            )
                    }
                    state.copy(phase = SlockAndroidAvatarDurablePhase.SharedCommitted).also { next ->
                        if (!persistJournalThenIndexLocked(
                                state = next,
                                cut = SlockAndroidAvatarDurableCut.SharedCommitted,
                                notifyCuts = true
                            )
                        ) {
                            return@withLock cleanupPending()
                        }
                    }
                } else {
                    if (state.phase == SlockAndroidAvatarDurablePhase.Prepared) {
                        return@withLock cleanupPending()
                    }
                    state
                }
            continueCommitLocked(sharedCommitted, notifyCuts = true)
        }

    fun rollbackCandidate(
        key: SlockAndroidAvatarOpaqueKey,
        candidateCommitIdentity: SlockAndroidAvatarCommitIdentity
    ): SlockAndroidAvatarOperationResult =
        lock.withLock {
            val resolved = loadResolvedStateLocked(key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.NotFound
                )
            if (state.phase == SlockAndroidAvatarDurablePhase.Idle) {
                if (hasPendingDurabilityLocked(key)) return@withLock cleanupPending()
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.NotFound
                )
            }
            if (state.candidate?.identity?.commitIdentity != candidateCommitIdentity) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.IdentityMismatch
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
        key: SlockAndroidAvatarOpaqueKey
    ): SlockAndroidAvatarCurrentReadResult =
        lock.withLock {
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                return@withLock SlockAndroidAvatarCurrentReadResult(
                    SlockAndroidAvatarCurrentReadStatus.AdmissionClosed
                )
            }
            val resolved = loadResolvedStateLocked(key)
            if (resolved.failed) {
                return@withLock SlockAndroidAvatarCurrentReadResult(
                    SlockAndroidAvatarCurrentReadStatus.StorageFailure
                )
            }
            if (resolved.corrupt) {
                return@withLock SlockAndroidAvatarCurrentReadResult(
                    SlockAndroidAvatarCurrentReadStatus.RecoveryPending
                )
            }
            val state = resolved.state
                ?: return@withLock SlockAndroidAvatarCurrentReadResult(
                    SlockAndroidAvatarCurrentReadStatus.Empty
                )
            val current = state.current
                ?: return@withLock SlockAndroidAvatarCurrentReadResult(
                    SlockAndroidAvatarCurrentReadStatus.Empty
                )
            val bytes =
                when (val inspection = inspectEntryLocked(current.identity)) {
                    is EntryInspection.Present -> inspection.bytes
                    EntryInspection.Failed ->
                        return@withLock SlockAndroidAvatarCurrentReadResult(
                            SlockAndroidAvatarCurrentReadStatus.StorageFailure
                        )
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        retireCurrentLocked(
                            state = state,
                            identity = current.identity,
                            reason = SlockAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock SlockAndroidAvatarCurrentReadResult(
                            SlockAndroidAvatarCurrentReadStatus.Empty
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
            SlockAndroidAvatarCurrentReadResult(
                status = SlockAndroidAvatarCurrentReadStatus.Available,
                snapshot =
                    SlockAndroidAvatarCurrentReadSnapshot(
                        entryIdentity = current.identity,
                        bytes = bytes
                    )
            )
        }

    fun readSnapshot(
        fence: SlockAndroidAvatarRequestFence,
        expectedCommitIdentity: SlockAndroidAvatarCommitIdentity?
    ): SlockAndroidAvatarReadResult =
        lock.withLock {
            if (expectedCommitIdentity == null) {
                return@withLock SlockAndroidAvatarReadResult.Miss(
                    SlockAndroidAvatarReadMissReason.NoExpectedCommitIdentity
                )
            }
            if (!isFenceCurrentLocked(fence)) {
                return@withLock SlockAndroidAvatarReadResult.Miss(
                    if (recoveryAdmissionBlocked || closedScopes.any(fence.key::matches)) {
                        SlockAndroidAvatarReadMissReason.AdmissionClosed
                    } else {
                        SlockAndroidAvatarReadMissReason.StaleRequest
                    }
                )
            }
            val resolved = loadResolvedStateLocked(fence.key)
            if (resolved.failed || resolved.corrupt) {
                return@withLock SlockAndroidAvatarReadResult.Miss(
                    SlockAndroidAvatarReadMissReason.RecoveryPending
                )
            }
            val state = resolved.state
            val current = state?.current
                ?: return@withLock SlockAndroidAvatarReadResult.Miss(
                    SlockAndroidAvatarReadMissReason.Empty
                )
            if (current.identity.commitIdentity != expectedCommitIdentity) {
                return@withLock SlockAndroidAvatarReadResult.Miss(
                    SlockAndroidAvatarReadMissReason.CommitIdentityMismatch
                )
            }
            val lease = acquireLeaseLocked(fence, SlockAndroidAvatarLeaseKind.Read)
                ?: return@withLock SlockAndroidAvatarReadResult.Miss(
                    SlockAndroidAvatarReadMissReason.StaleRequest
                )
            val bytes =
                when (val inspection = inspectEntryLocked(current.identity)) {
                    is EntryInspection.Present -> inspection.bytes
                    EntryInspection.Failed -> {
                        lease.close()
                        return@withLock SlockAndroidAvatarReadResult.Miss(
                            SlockAndroidAvatarReadMissReason.RecoveryPending
                        )
                    }
                    EntryInspection.Missing,
                    EntryInspection.Invalid -> {
                        lease.close()
                        retireCurrentLocked(
                            state = state,
                            identity = current.identity,
                            reason = SlockAndroidAvatarTombstoneReason.InvalidBytes,
                            notifyCuts = false
                        )
                        return@withLock SlockAndroidAvatarReadResult.Miss(
                            SlockAndroidAvatarReadMissReason.MissingOrInvalidBytes
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
            SlockAndroidAvatarReadResult.Hit(
                snapshot =
                    SlockAndroidAvatarReadSnapshot(
                        requestFence = fence,
                        entryIdentity = current.identity,
                        bytes = bytes,
                        capturedEpochs = fence.terminalEpochs
                    ),
                lease = lease
            )
        }

    fun registerDecoded(
        snapshot: SlockAndroidAvatarReadSnapshot,
        lease: SlockAndroidAvatarLease,
        variant: SlockAndroidAvatarDecodedVariant
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
                SlockAndroidAvatarDecodedIdentity(
                    entryIdentity = snapshot.entryIdentity,
                    variant = variant
                )
            )
        }

    fun registerDecodedCurrent(
        entryIdentity: SlockAndroidAvatarEntryIdentity,
        variant: SlockAndroidAvatarDecodedVariant
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
                SlockAndroidAvatarDecodedIdentity(
                    entryIdentity = entryIdentity,
                    variant = variant
                )
            )
        }

    fun invalidateIfCurrent(
        snapshot: SlockAndroidAvatarReadSnapshot,
        reason: SlockAndroidAvatarTombstoneReason =
            SlockAndroidAvatarTombstoneReason.AuthoritativeRetirement
    ): SlockAndroidAvatarOperationResult =
        lock.withLock {
            if (!isFenceCurrentLocked(snapshot.requestFence) ||
                snapshot.capturedEpochs != epochsForLocked(snapshot.entryIdentity.key)
            ) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.StaleRequest
                )
            }
            val resolved = loadResolvedStateLocked(snapshot.entryIdentity.key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.NotFound
                )
            if (state.current?.identity != snapshot.entryIdentity) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.StaleRequest
                )
            }
            retireCurrentLocked(state, snapshot.entryIdentity, reason, notifyCuts = true)
        }

    fun retireCurrentExact(
        key: SlockAndroidAvatarOpaqueKey,
        expectedCommitIdentity: SlockAndroidAvatarCommitIdentity?
    ): SlockAndroidAvatarOperationResult =
        lock.withLock {
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.AdmissionClosed
                )
            }
            val resolved = loadResolvedStateLocked(key)
            if (resolved.failed) return@withLock storageFailure()
            if (resolved.corrupt) return@withLock corruptState()
            val state = resolved.state
                ?: return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.AlreadyApplied
                )
            if (state.phase != SlockAndroidAvatarDurablePhase.Idle) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.Busy
                )
            }
            val current = state.current
                ?: return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.AlreadyApplied
                )
            if (expectedCommitIdentity != null && current.identity.commitIdentity != expectedCommitIdentity) {
                return@withLock SlockAndroidAvatarOperationResult(
                    SlockAndroidAvatarOperationStatus.IdentityMismatch
                )
            }
            retireCurrentLocked(
                state = state,
                identity = current.identity,
                reason = SlockAndroidAvatarTombstoneReason.AuthoritativeRetirement,
                notifyCuts = true
            )
        }

    /**
     * Terminal operations close matching admission, drain leases, and physically remove the
     * matching native cache namespace. They intentionally do not retain commit-generation history
     * after physical zero. Product wiring must clear matching metadata/validators so the next
     * request starts with no expected native identity and supplies a canonical full body.
     */
    fun clearAll(): SlockAndroidAvatarTerminalResult =
        retireScope(SlockAndroidAvatarTerminalScope.All)

    fun retireOwner(
        partitionKey: String,
        ownerKey: String
    ): SlockAndroidAvatarTerminalResult =
        retireScope(SlockAndroidAvatarTerminalScope.Owner(partitionKey, ownerKey))

    fun retireSubject(
        partitionKey: String,
        ownerKey: String,
        subjectKey: String
    ): SlockAndroidAvatarTerminalResult =
        retireScope(
            SlockAndroidAvatarTerminalScope.Subject(
                partitionKey = partitionKey,
                ownerKey = ownerKey,
                subjectKey = subjectKey
            )
        )

    fun retryRecovery(): SlockAndroidAvatarOperationResult =
        lock.withLock {
            recoverStartupLocked().also { result ->
                recoveryAdmissionBlocked =
                    result.status != SlockAndroidAvatarOperationStatus.Applied
            }
        }

    fun managedBytes(): Long? =
        lock.withLock {
            managedDataFilesLocked()?.let(::sizeOfLocked)
        }

    fun trimToBudget(maxBytes: Long): SlockAndroidAvatarBudgetResult =
        lock.withLock {
            require(maxBytes >= 0L)
            val initialFiles = managedDataFilesLocked()
                ?: return@withLock SlockAndroidAvatarBudgetResult(
                    SlockAndroidAvatarBudgetStatus.StorageFailure,
                    managedBytes = -1L,
                    evictedEntries = 0
                )
            var total = sizeOfLocked(initialFiles)
                ?: return@withLock SlockAndroidAvatarBudgetResult(
                    SlockAndroidAvatarBudgetStatus.StorageFailure,
                    managedBytes = -1L,
                    evictedEntries = 0
                )
            if (total <= maxBytes) {
                return@withLock SlockAndroidAvatarBudgetResult(
                    SlockAndroidAvatarBudgetStatus.WithinBudget,
                    managedBytes = total,
                    evictedEntries = 0
                )
            }
            val keys = managedKeysLocked()
                ?: return@withLock SlockAndroidAvatarBudgetResult(
                    SlockAndroidAvatarBudgetStatus.StorageFailure,
                    managedBytes = total,
                    evictedEntries = 0
                )
            val candidates = mutableListOf<SlockAndroidAvatarEntryRecord>()
            for (key in keys) {
                val resolved = loadResolvedStateLocked(key)
                if (resolved.failed) {
                    return@withLock SlockAndroidAvatarBudgetResult(
                        SlockAndroidAvatarBudgetStatus.StorageFailure,
                        managedBytes = total,
                        evictedEntries = 0
                    )
                }
                if (resolved.corrupt) {
                    return@withLock SlockAndroidAvatarBudgetResult(
                        SlockAndroidAvatarBudgetStatus.CleanupPending,
                        managedBytes = total,
                        evictedEntries = 0
                    )
                }
                if (resolved.state == null) {
                    val keyFiles =
                        managedFilesForKeyLocked(key)
                            ?: return@withLock SlockAndroidAvatarBudgetResult(
                                SlockAndroidAvatarBudgetStatus.StorageFailure,
                                managedBytes = total,
                                evictedEntries = 0
                            )
                    if (keyFiles.isNotEmpty()) {
                        return@withLock SlockAndroidAvatarBudgetResult(
                            SlockAndroidAvatarBudgetStatus.CleanupPending,
                            managedBytes = total,
                            evictedEntries = 0
                        )
                    }
                }
                resolved.state
                    ?.takeIf { state -> state.phase == SlockAndroidAvatarDurablePhase.Idle }
                    ?.current
                    ?.let(candidates::add)
            }
            candidates.sortWith(
                compareBy<SlockAndroidAvatarEntryRecord> { it.lastAccessOrdinal }
                    .thenBy { it.identity.nativeRevision }
            )
            var evicted = 0
            for (candidate in candidates) {
                if (total <= maxBytes) break
                val resolved = loadResolvedStateLocked(candidate.identity.key)
                val state = resolved.state ?: continue
                if (state.phase != SlockAndroidAvatarDurablePhase.Idle ||
                    state.current?.identity != candidate.identity
                ) {
                    continue
                }
                val result =
                    retireCurrentLocked(
                        state = state,
                        identity = candidate.identity,
                        reason = SlockAndroidAvatarTombstoneReason.BudgetEviction,
                        notifyCuts = false
                    )
                if (result.status != SlockAndroidAvatarOperationStatus.Applied) {
                    val remaining = managedBytesLockedOrNegative()
                    return@withLock SlockAndroidAvatarBudgetResult(
                        SlockAndroidAvatarBudgetStatus.CleanupPending,
                        managedBytes = remaining,
                        evictedEntries = evicted
                    )
                }
                evicted += 1
                total = managedBytesLockedOrNegative()
                if (total < 0L) {
                    return@withLock SlockAndroidAvatarBudgetResult(
                        SlockAndroidAvatarBudgetStatus.StorageFailure,
                        managedBytes = total,
                        evictedEntries = evicted
                    )
                }
            }
            SlockAndroidAvatarBudgetResult(
                status =
                    if (total <= maxBytes) {
                        SlockAndroidAvatarBudgetStatus.Evicted
                    } else {
                        SlockAndroidAvatarBudgetStatus.Pinned
                    },
                managedBytes = total,
                evictedEntries = evicted
            )
        }

    internal fun durableStateForTest(
        key: SlockAndroidAvatarOpaqueKey
    ): SlockAndroidAvatarDurableState? =
        lock.withLock { loadResolvedStateLocked(key).state }

    internal fun decodedIdentityCountForTest(): Int = decodedStore.count()

    internal fun activeLeaseCountForTest(): Int = lock.withLock { activeLeases.size }

    private fun retireScope(
        scope: SlockAndroidAvatarTerminalScope
    ): SlockAndroidAvatarTerminalResult =
        lock.withLock {
            if (!ensureTerminalMarkerLocked(scope)) {
                closeTerminalAdmissionLocked(scope, notifyObserver = true)
                return@withLock SlockAndroidAvatarTerminalResult(
                    SlockAndroidAvatarTerminalStatus.StorageFailure,
                    retiredKeys = 0
                )
            }
            closeTerminalAdmissionLocked(scope, notifyObserver = true)
            completeTerminalScopeLocked(scope, notifyObserver = true)
        }

    private fun closeTerminalAdmissionLocked(
        scope: SlockAndroidAvatarTerminalScope,
        notifyObserver: Boolean
    ) {
        if (!closedScopes.add(scope)) return
        incrementEpochLocked(scope)
        requestSlots.keys.removeAll { key -> key.matches(scope) }
        if (notifyObserver) {
            terminalObserver.afterTerminalCut(
                scope,
                SlockAndroidAvatarTerminalCut.AdmissionClosed
            )
        }
    }

    private fun completeTerminalScopeLocked(
        scope: SlockAndroidAvatarTerminalScope,
        notifyObserver: Boolean
    ): SlockAndroidAvatarTerminalResult {
        try {
            while (activeLeases.values.any { lease -> lease.key.matches(scope) }) {
                leaseDrained.await()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return SlockAndroidAvatarTerminalResult(
                SlockAndroidAvatarTerminalStatus.Interrupted,
                retiredKeys = 0
            )
        }
        if (notifyObserver) {
            terminalObserver.afterTerminalCut(scope, SlockAndroidAvatarTerminalCut.LeasesDrained)
        }
        val keys = managedKeysLocked()
            ?: return SlockAndroidAvatarTerminalResult(
                SlockAndroidAvatarTerminalStatus.StorageFailure,
                retiredKeys = 0
            )
        val matchingKeys = keys.filter { it.matches(scope) }.sortedBy { it.relativeDirectory }
        for (key in matchingKeys) {
            if (!prepareTerminalTombstoneLocked(key)) {
                return SlockAndroidAvatarTerminalResult(
                    SlockAndroidAvatarTerminalStatus.CleanupPending,
                    retiredKeys = 0
                )
            }
        }
        if (!decodedStore.evictMatching(scope) || decodedStore.containsMatching(scope)) {
            return SlockAndroidAvatarTerminalResult(
                SlockAndroidAvatarTerminalStatus.CleanupPending,
                retiredKeys = 0
            )
        }
        var retired = 0
        for (key in matchingKeys) {
            if (!completeTerminalRetireKeyLocked(key)) {
                return SlockAndroidAvatarTerminalResult(
                    SlockAndroidAvatarTerminalStatus.CleanupPending,
                    retiredKeys = retired
                )
            }
            retired += 1
        }
        val remaining = files.listFiles()
            ?: return SlockAndroidAvatarTerminalResult(
                SlockAndroidAvatarTerminalStatus.StorageFailure,
                retiredKeys = retired
            )
        if (remaining.any { path ->
                SlockAndroidAvatarOpaqueKey.fromManagedPath(path)?.matches(scope) == true &&
                    SlockAndroidAvatarOpaqueKey.fromManagedPath(path)?.ownsManagedPath(path) == true
            }
        ) {
            return SlockAndroidAvatarTerminalResult(
                SlockAndroidAvatarTerminalStatus.CleanupPending,
                retiredKeys = retired
            )
        }
        val completedScopes =
            (closedScopes +
                remaining.mapNotNull(::slockAndroidAvatarTerminalScopeFromMarkerPath) +
                scope)
                .filter { candidate -> scope.containsTerminalScope(candidate) }
                .distinctBy { candidate -> candidate.markerPath }
                .sortedByDescending { candidate -> candidate.markerPath.length }
        for (completedScope in completedScopes) {
            if (!deleteDurablyLocked(completedScope.markerPath)) {
                return SlockAndroidAvatarTerminalResult(
                    SlockAndroidAvatarTerminalStatus.CleanupPending,
                    retiredKeys = retired
                )
            }
        }
        closedScopes.removeAll(completedScopes.toSet())
        if (notifyObserver) {
            terminalObserver.afterTerminalCut(scope, SlockAndroidAvatarTerminalCut.PhysicalZero)
        }
        return SlockAndroidAvatarTerminalResult(
            SlockAndroidAvatarTerminalStatus.Complete,
            retiredKeys = retired
        )
    }

    private fun prepareTerminalTombstoneLocked(key: SlockAndroidAvatarOpaqueKey): Boolean {
        val keyFiles = managedFilesForKeyLocked(key) ?: return false
        val resolved = loadResolvedStateLocked(key)
        if (resolved.failed) return false
        val dataAndTemps =
            keyFiles.filter { path ->
                path.isSlockAndroidAvatarDataPath() ||
                    path.substringAfterLast('/').startsWith("metadata-tmp-")
            }.toSet()
        val cleanupEntries =
            (resolved.state?.allEntryIdentities().orEmpty() +
                resolved.state?.cleanupEntries.orEmpty())
                .distinct()
                .sortedBy(SlockAndroidAvatarEntryIdentity::entryPath)
                .take(SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
        val cleanupArtifacts =
            (resolved.state?.cleanupArtifacts.orEmpty() + dataAndTemps)
                .sorted()
                .take(SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                .toSet()
        val existingTerminal =
            resolved.state?.takeIf { state ->
                state.phase == SlockAndroidAvatarDurablePhase.RollbackCleanup &&
                    state.current == null &&
                    state.tombstoneReason == SlockAndroidAvatarTombstoneReason.TerminalRetirement
            }
        val tombstone =
            SlockAndroidAvatarDurableState(
                key = key,
                commitGenerationHighWater =
                    resolved.state?.commitGenerationHighWater ?: 0L,
                phase = SlockAndroidAvatarDurablePhase.RollbackCleanup,
                transactionId = existingTerminal?.transactionId ?: nextIdentity(),
                capturedEpochs = existingTerminal?.capturedEpochs ?: epochsForLocked(key),
                current = null,
                previous = null,
                candidate = null,
                cleanupEntries = cleanupEntries,
                cleanupArtifacts = cleanupArtifacts,
                tombstoneReason = SlockAndroidAvatarTombstoneReason.TerminalRetirement
            )
        return persistJournalThenIndexLocked(tombstone, cut = null, notifyCuts = false)
    }

    private fun completeTerminalRetireKeyLocked(key: SlockAndroidAvatarOpaqueKey): Boolean {
        val resolved = loadResolvedStateLocked(key)
        if (resolved.failed || resolved.corrupt) return false
        val tombstone = resolved.state ?: return false
        if (tombstone.phase != SlockAndroidAvatarDurablePhase.RollbackCleanup ||
            tombstone.current != null ||
            tombstone.tombstoneReason != SlockAndroidAvatarTombstoneReason.TerminalRetirement
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
        state: SlockAndroidAvatarDurableState,
        identity: SlockAndroidAvatarEntryIdentity,
        reason: SlockAndroidAvatarTombstoneReason,
        notifyCuts: Boolean
    ): SlockAndroidAvatarOperationResult {
        if (state.current?.identity != identity) {
            return SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.StaleRequest)
        }
        if (state.phase != SlockAndroidAvatarDurablePhase.Idle) {
            val cleanupEntries = (state.allEntryIdentities() + identity).distinct()
            val cleanupArtifacts =
                buildSet {
                    addAll(state.cleanupArtifacts)
                    cleanupEntries.forEach { cleanupIdentity ->
                        add(cleanupIdentity.entryPath)
                    }
                    state.candidate?.identity?.stagePath?.let(::add)
                }
            if (cleanupEntries.size > SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS ||
                cleanupArtifacts.size > SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS
            ) {
                return corruptState()
            }
            val repointed =
                state.copy(
                    phase = SlockAndroidAvatarDurablePhase.RollbackRepointed,
                    current = null,
                    previous = null,
                    cleanupEntries = cleanupEntries,
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason = reason
                )
            if (!persistIndexThenJournalLocked(
                    repointed,
                    firstCut = SlockAndroidAvatarDurableCut.RollbackRepointed,
                    completeCut = null,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            return continueRollbackLocked(repointed, notifyCuts = notifyCuts)
        }
        val tombstone =
            SlockAndroidAvatarDurableState(
                key = identity.key,
                commitGenerationHighWater = state.commitGenerationHighWater,
                phase = SlockAndroidAvatarDurablePhase.RollbackCleanup,
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
                cut = SlockAndroidAvatarDurableCut.RollbackCleanup,
                notifyCuts = notifyCuts
            )
        ) {
            return cleanupPending()
        }
        if (!decodedStore.evictExact(identity)) return cleanupPending()
        if (!cleanupArtifactsLocked(tombstone.cleanupArtifacts)) return cleanupPending()
        return if (finishIdleLocked(tombstone.copy(cleanupArtifacts = emptySet()), null, notifyCuts)) {
            SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.Applied, identity)
        } else {
            cleanupPending()
        }
    }

    private fun continueCommitLocked(
        initial: SlockAndroidAvatarDurableState,
        notifyCuts: Boolean
    ): SlockAndroidAvatarOperationResult {
        var state = initial
        val candidate = state.candidate ?: return corruptState()
        if (state.phase == SlockAndroidAvatarDurablePhase.SharedCommitted) {
            when (ensureCandidateRenamedLocked(candidate.identity, notifyCuts)) {
                CandidateRenameOutcome.Ready -> Unit
                CandidateRenameOutcome.DurabilityPending -> return cleanupPending()
                CandidateRenameOutcome.StorageFailure -> return storageFailure()
                CandidateRenameOutcome.MissingOrInvalid ->
                    return continueRollbackLocked(
                        state.copy(tombstoneReason = SlockAndroidAvatarTombstoneReason.RecoveryFailure),
                        notifyCuts
                    )
            }
            state = state.copy(phase = SlockAndroidAvatarDurablePhase.CandidateRenamed)
            if (!persistJournalThenIndexLocked(
                    state,
                    cut = SlockAndroidAvatarDurableCut.CandidateRenamedRecorded,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
        }
        if (state.phase == SlockAndroidAvatarDurablePhase.CandidateRenamed) {
            when (inspectEntryLocked(candidate.identity)) {
                is EntryInspection.Present -> Unit
                EntryInspection.Failed -> return storageFailure()
                EntryInspection.Missing,
                EntryInspection.Invalid ->
                    return continueRollbackLocked(
                        state.copy(tombstoneReason = SlockAndroidAvatarTombstoneReason.RecoveryFailure),
                        notifyCuts
                    )
            }
            val switched =
                state.copy(
                    phase = SlockAndroidAvatarDurablePhase.IndexSwitched,
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
                    firstCut = SlockAndroidAvatarDurableCut.IndexSwitched,
                    completeCut = SlockAndroidAvatarDurableCut.IndexSwitchedRecorded,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = switched
        }
        if (state.phase == SlockAndroidAvatarDurablePhase.IndexSwitched) {
            when (inspectEntryLocked(candidate.identity)) {
                is EntryInspection.Present -> Unit
                EntryInspection.Failed -> return storageFailure()
                EntryInspection.Missing,
                EntryInspection.Invalid ->
                    return continueRollbackLocked(
                        state.copy(tombstoneReason = SlockAndroidAvatarTombstoneReason.RecoveryFailure),
                        notifyCuts
                    )
            }
            val cleanup = state.copy(phase = SlockAndroidAvatarDurablePhase.CommitCleanup)
            if (!persistJournalThenIndexLocked(
                    cleanup,
                    cut = SlockAndroidAvatarDurableCut.CommitCleanup,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = cleanup
        }
        if (state.phase != SlockAndroidAvatarDurablePhase.CommitCleanup) {
            return corruptState()
        }
        state.cleanupEntries.forEach { previous ->
            if (!decodedStore.evictExact(previous)) return cleanupPending()
        }
        if (!cleanupArtifactsLocked(state.cleanupArtifacts)) return cleanupPending()
        return if (finishIdleLocked(state.copy(cleanupArtifacts = emptySet()), candidate, notifyCuts)) {
            SlockAndroidAvatarOperationResult(
                SlockAndroidAvatarOperationStatus.Applied,
                candidate.identity
            )
        } else {
            cleanupPending()
        }
    }

    private fun continueRollbackLocked(
        initial: SlockAndroidAvatarDurableState,
        notifyCuts: Boolean
    ): SlockAndroidAvatarOperationResult {
        var state = initial
        val candidate = state.candidate
        if (state.phase != SlockAndroidAvatarDurablePhase.RollbackRepointed &&
            state.phase != SlockAndroidAvatarDurablePhase.RollbackCleanup
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
            if (distinctCleanupEntries.size > SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS ||
                cleanupArtifacts.size > SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS
            ) {
                return corruptState()
            }
            val repointed =
                state.copy(
                    phase = SlockAndroidAvatarDurablePhase.RollbackRepointed,
                    current = previous,
                    cleanupEntries = distinctCleanupEntries,
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason =
                        state.tombstoneReason ?: SlockAndroidAvatarTombstoneReason.RecoveryFailure
                )
            if (!persistIndexThenJournalLocked(
                    repointed,
                    firstCut = SlockAndroidAvatarDurableCut.RollbackRepointed,
                    completeCut = null,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = repointed
        }
        if (state.phase == SlockAndroidAvatarDurablePhase.RollbackRepointed) {
            val cleanup = state.copy(phase = SlockAndroidAvatarDurablePhase.RollbackCleanup)
            if (!persistJournalThenIndexLocked(
                    cleanup,
                    cut = SlockAndroidAvatarDurableCut.RollbackCleanup,
                    notifyCuts = notifyCuts
                )
            ) {
                return cleanupPending()
            }
            state = cleanup
        }
        if (state.phase != SlockAndroidAvatarDurablePhase.RollbackCleanup) return corruptState()
        state.cleanupEntries.forEach { identity ->
            if (!decodedStore.evictExact(identity)) return cleanupPending()
        }
        if (!cleanupArtifactsLocked(state.cleanupArtifacts)) return cleanupPending()
        val current = state.current
        return if (finishIdleLocked(state.copy(cleanupArtifacts = emptySet()), current, notifyCuts)) {
            SlockAndroidAvatarOperationResult(
                SlockAndroidAvatarOperationStatus.Applied,
                current?.identity
            )
        } else {
            cleanupPending()
        }
    }

    private fun recoverStartupLocked(): SlockAndroidAvatarOperationResult {
        retryPendingDurabilityPathsLocked()
        val firstTerminal = recoverTerminalScopesLocked()
        if (firstTerminal.status == SlockAndroidAvatarOperationStatus.StorageFailure) {
            return storageFailure()
        }
        val ordinary = recoverAllLocked(notifyCuts = false)
        val secondTerminal =
            if (firstTerminal.status == SlockAndroidAvatarOperationStatus.CleanupPending) {
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

    private fun recoverTerminalScopesLocked(): SlockAndroidAvatarOperationResult {
        val listed = files.listFiles() ?: return storageFailure()
        val discovered = listed.mapNotNull(::slockAndroidAvatarTerminalScopeFromMarkerPath).toSet()
        val scopes = (closedScopes + discovered).sortedBy { it.markerPath }
        var pending = false
        for (scope in scopes) {
            if (scope !in closedScopes && !files.exists(scope.markerPath)) continue
            closeTerminalAdmissionLocked(scope, notifyObserver = false)
            if (!ensureTerminalMarkerLocked(scope)) return storageFailure()
            when (completeTerminalScopeLocked(scope, notifyObserver = false).status) {
                SlockAndroidAvatarTerminalStatus.Complete -> Unit
                SlockAndroidAvatarTerminalStatus.StorageFailure -> return storageFailure()
                SlockAndroidAvatarTerminalStatus.CleanupPending,
                SlockAndroidAvatarTerminalStatus.Interrupted -> pending = true
            }
        }
        return if (pending) cleanupPending() else SlockAndroidAvatarOperationResult(
            SlockAndroidAvatarOperationStatus.Applied
        )
    }

    private fun ensureTerminalMarkerLocked(scope: SlockAndroidAvatarTerminalScope): Boolean =
        when (files.read(scope.markerPath)) {
            is SlockAndroidAvatarFileRead.Present -> files.syncParent(scope.markerPath)
            SlockAndroidAvatarFileRead.Failed -> false
            SlockAndroidAvatarFileRead.Missing -> {
                files.writeNewDurably(scope.markerPath, TerminalMarkerBytes) ||
                    (files.exists(scope.markerPath) && files.syncParent(scope.markerPath))
            }
        }

    private fun combineRecoveryResults(
        first: SlockAndroidAvatarOperationResult,
        second: SlockAndroidAvatarOperationResult
    ): SlockAndroidAvatarOperationResult {
        if (first.status == SlockAndroidAvatarOperationStatus.StorageFailure ||
            second.status == SlockAndroidAvatarOperationStatus.StorageFailure
        ) {
            return storageFailure()
        }
        if (first.status == SlockAndroidAvatarOperationStatus.CleanupPending ||
            first.status == SlockAndroidAvatarOperationStatus.CorruptState ||
            second.status == SlockAndroidAvatarOperationStatus.CleanupPending ||
            second.status == SlockAndroidAvatarOperationStatus.CorruptState
        ) {
            return cleanupPending()
        }
        return SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.Applied)
    }

    private fun recoverAllLocked(notifyCuts: Boolean): SlockAndroidAvatarOperationResult {
        val keys = managedKeysLocked() ?: return storageFailure()
        var pending = false
        keys.forEach { key ->
            if (closedScopes.any(key::matches)) return@forEach
            val result = recoverKeyLocked(key, notifyCuts)
            when (result.status) {
                SlockAndroidAvatarOperationStatus.StorageFailure -> return result
                SlockAndroidAvatarOperationStatus.CleanupPending,
                SlockAndroidAvatarOperationStatus.CorruptState -> pending = true
                else -> Unit
            }
        }
        refreshNextAccessOrdinalLocked(keys)
        return if (pending) cleanupPending() else SlockAndroidAvatarOperationResult(
            SlockAndroidAvatarOperationStatus.Applied
        )
    }

    private fun recoverKeyLocked(
        key: SlockAndroidAvatarOpaqueKey,
        notifyCuts: Boolean
    ): SlockAndroidAvatarOperationResult {
        val keyFiles = managedFilesForKeyLocked(key) ?: return storageFailure()
        val resolved = loadResolvedStateLocked(key)
        if (resolved.failed) return storageFailure()
        if (resolved.corrupt || resolved.state == null) {
            val allDataAndTemps =
                keyFiles.filter { path ->
                    path.isSlockAndroidAvatarDataPath() ||
                        path.substringAfterLast('/').startsWith("metadata-tmp-")
                }.sorted()
            val dataAndTemps =
                allDataAndTemps
                    .take(SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                    .toSet()
            val hasMoreArtifacts = allDataAndTemps.size > dataAndTemps.size
            if (keyFiles.isEmpty()) return SlockAndroidAvatarOperationResult(
                SlockAndroidAvatarOperationStatus.Applied
            )
            val tombstone =
                SlockAndroidAvatarDurableState(
                    key = key,
                    commitGenerationHighWater = 0L,
                    phase = SlockAndroidAvatarDurablePhase.RollbackCleanup,
                    transactionId = nextIdentity(),
                    capturedEpochs = epochsForLocked(key),
                    current = null,
                    previous = null,
                    candidate = null,
                    cleanupEntries = emptyList(),
                    cleanupArtifacts = dataAndTemps,
                    tombstoneReason =
                        if (resolved.corrupt) {
                            SlockAndroidAvatarTombstoneReason.CorruptMetadata
                        } else {
                            SlockAndroidAvatarTombstoneReason.OrphanArtifact
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
                    SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.Applied)
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
            SlockAndroidAvatarDurablePhase.Idle -> recoverIdleLocked(state)
            SlockAndroidAvatarDurablePhase.Prepared,
            SlockAndroidAvatarDurablePhase.CandidateDurable ->
                continueRollbackLocked(state, notifyCuts)
            SlockAndroidAvatarDurablePhase.SharedCommitted,
            SlockAndroidAvatarDurablePhase.CandidateRenamed,
            SlockAndroidAvatarDurablePhase.IndexSwitched,
            SlockAndroidAvatarDurablePhase.CommitCleanup ->
                continueCommitLocked(state, notifyCuts)
            SlockAndroidAvatarDurablePhase.RollbackRepointed,
            SlockAndroidAvatarDurablePhase.RollbackCleanup ->
                continueRollbackLocked(state, notifyCuts)
        }
    }

    private fun recoverIdleLocked(
        state: SlockAndroidAvatarDurableState
    ): SlockAndroidAvatarOperationResult {
        if (!deleteDurablyLocked(state.key.journalPath)) {
            return cleanupPending()
        }
        val keyFiles = managedFilesForKeyLocked(state.key) ?: return storageFailure()
        val currentPath = state.current?.identity?.entryPath
        val allOrphans =
            keyFiles.filter { path ->
                (path.isSlockAndroidAvatarDataPath() && path != currentPath) ||
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
                    .take(SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                    .toSet()
            val hasMoreArtifacts = allCleanupArtifacts.size > cleanupArtifacts.size
            val tombstone =
                SlockAndroidAvatarDurableState(
                    key = state.key,
                    commitGenerationHighWater = state.commitGenerationHighWater,
                    phase = SlockAndroidAvatarDurablePhase.RollbackCleanup,
                    transactionId = nextIdentity(),
                    capturedEpochs = epochsForLocked(state.key),
                    current = null,
                    previous = null,
                    candidate = null,
                    cleanupEntries = listOf(state.current.identity),
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason = SlockAndroidAvatarTombstoneReason.InvalidBytes
                )
            if (!persistJournalThenIndexLocked(tombstone, null, false)) return cleanupPending()
            if (!decodedStore.evictExact(state.current.identity)) return cleanupPending()
            if (!cleanupArtifactsLocked(tombstone.cleanupArtifacts)) return cleanupPending()
            return if (finishIdleLocked(tombstone.copy(cleanupArtifacts = emptySet()), null, false)) {
                if (hasMoreArtifacts) {
                    cleanupPending()
                } else {
                    SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.Applied)
                }
            } else {
                cleanupPending()
            }
        }
        if (allOrphans.isNotEmpty()) {
            val orphans =
                allOrphans
                    .take(SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
                    .toSet()
            val hasMoreArtifacts = allOrphans.size > orphans.size
            val tombstone =
                SlockAndroidAvatarDurableState(
                    key = state.key,
                    commitGenerationHighWater = state.commitGenerationHighWater,
                    phase = SlockAndroidAvatarDurablePhase.RollbackCleanup,
                    transactionId = nextIdentity(),
                    capturedEpochs = epochsForLocked(state.key),
                    current = state.current,
                    previous = null,
                    candidate = null,
                    cleanupEntries = emptyList(),
                    cleanupArtifacts = orphans,
                    tombstoneReason = SlockAndroidAvatarTombstoneReason.OrphanArtifact
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
        return SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.Applied)
    }

    private fun ensureCandidateRenamedLocked(
        identity: SlockAndroidAvatarEntryIdentity,
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
                SlockAndroidAvatarDurableCut.CandidateRenamed
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
        identity: SlockAndroidAvatarEntryIdentity,
    ): EntryInspection =
        when (val entry = inspectEntryLocked(identity)) {
            is EntryInspection.Present,
            EntryInspection.Failed,
            EntryInspection.Invalid -> entry
            EntryInspection.Missing -> inspectPathLocked(identity.stagePath, identity)
        }

    private fun inspectEntryLocked(
        identity: SlockAndroidAvatarEntryIdentity
    ): EntryInspection = inspectPathLocked(identity.entryPath, identity)

    private fun SlockAndroidAvatarEntryIdentity.matchesPayload(
        sha256: String,
        byteLength: Long,
        contentType: String
    ): Boolean =
        this.sha256 == sha256 &&
            this.byteLength == byteLength &&
            this.contentType == contentType

    private fun inspectPathLocked(
        path: String,
        identity: SlockAndroidAvatarEntryIdentity
    ): EntryInspection =
        when (val read = files.read(path)) {
            is SlockAndroidAvatarFileRead.Present ->
                if (read.bytes.size.toLong() == identity.byteLength &&
                    read.bytes.slockSha256Hex() == identity.sha256
                ) {
                    EntryInspection.Present(read.bytes)
                } else {
                    EntryInspection.Invalid
                }
            SlockAndroidAvatarFileRead.Missing -> EntryInspection.Missing
            SlockAndroidAvatarFileRead.Failed -> EntryInspection.Failed
        }

    private fun persistJournalThenIndexLocked(
        state: SlockAndroidAvatarDurableState,
        cut: SlockAndroidAvatarDurableCut?,
        notifyCuts: Boolean
    ): Boolean {
        val persisted = withNextStateSequenceLocked(state) ?: return false
        val journal =
            SlockAndroidAvatarDurableStateCodec.encode(
                SlockAndroidAvatarDurableRecordKind.Journal,
                persisted
            )
        val index =
            SlockAndroidAvatarDurableStateCodec.encode(
                SlockAndroidAvatarDurableRecordKind.Index,
                persisted
            )
        if (!files.writeAtomically(persisted.key.journalPath, journal)) return false
        if (!files.writeAtomically(persisted.key.indexPath, index)) return false
        if (notifyCuts && cut != null) durableObserver.afterDurableCut(persisted.key, cut)
        return true
    }

    private fun persistIndexThenJournalLocked(
        state: SlockAndroidAvatarDurableState,
        firstCut: SlockAndroidAvatarDurableCut,
        completeCut: SlockAndroidAvatarDurableCut?,
        notifyCuts: Boolean
    ): Boolean {
        val persisted = withNextStateSequenceLocked(state) ?: return false
        val index =
            SlockAndroidAvatarDurableStateCodec.encode(
                SlockAndroidAvatarDurableRecordKind.Index,
                persisted
            )
        val journal =
            SlockAndroidAvatarDurableStateCodec.encode(
                SlockAndroidAvatarDurableRecordKind.Journal,
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
        cleanupState: SlockAndroidAvatarDurableState,
        current: SlockAndroidAvatarEntryRecord?,
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
                SlockAndroidAvatarDurableState.idle(
                    key = cleanupState.key,
                    current = current,
                    commitGenerationHighWater = cleanupState.commitGenerationHighWater
                )
            if (!persistJournalThenIndexLocked(idle, cut = null, notifyCuts = false)) return false
            if (!deleteDurablyLocked(cleanupState.key.journalPath)) return false
        }
        if (notifyCuts) {
            durableObserver.afterDurableCut(cleanupState.key, SlockAndroidAvatarDurableCut.Idle)
        }
        return true
    }

    private fun persistCurrentTouchLocked(
        touched: SlockAndroidAvatarDurableState,
        original: SlockAndroidAvatarDurableState
    ) {
        val persisted = withNextStateSequenceLocked(touched) ?: return
        val index =
            SlockAndroidAvatarDurableStateCodec.encode(
                SlockAndroidAvatarDurableRecordKind.Index,
                persisted
            )
        if (!files.writeAtomically(persisted.key.indexPath, index)) return
        if (original.phase != SlockAndroidAvatarDurablePhase.Idle) {
            val journal =
                SlockAndroidAvatarDurableStateCodec.encode(
                    SlockAndroidAvatarDurableRecordKind.Journal,
                    persisted
                )
            files.writeAtomically(persisted.key.journalPath, journal)
        }
    }

    private fun withNextStateSequenceLocked(
        state: SlockAndroidAvatarDurableState
    ): SlockAndroidAvatarDurableState? {
        val index =
            loadRecordLocked(
                state.key.indexPath,
                SlockAndroidAvatarDurableRecordKind.Index,
                state.key
            )
        val journal =
            loadRecordLocked(
                state.key.journalPath,
                SlockAndroidAvatarDurableRecordKind.Journal,
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

    private fun hasPendingDurabilityLocked(key: SlockAndroidAvatarOpaqueKey): Boolean =
        pendingDurabilityPaths.any(key::ownsManagedPath)

    private fun retryPendingDurabilityPathsLocked(): SlockAndroidAvatarOperationResult {
        var pending = false
        pendingDurabilityPaths.toList().sorted().forEach { path ->
            if (!deleteDurablyLocked(path)) pending = true
        }
        return if (pending) cleanupPending() else SlockAndroidAvatarOperationResult(
            SlockAndroidAvatarOperationStatus.Applied
        )
    }

    private fun loadResolvedStateLocked(key: SlockAndroidAvatarOpaqueKey): ResolvedState {
        val index = loadRecordLocked(key.indexPath, SlockAndroidAvatarDurableRecordKind.Index, key)
        val journal = loadRecordLocked(key.journalPath, SlockAndroidAvatarDurableRecordKind.Journal, key)
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
                needsRewrite = validIndex.phase != SlockAndroidAvatarDurablePhase.Idle,
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
        kind: SlockAndroidAvatarDurableRecordKind,
        expectedKey: SlockAndroidAvatarOpaqueKey
    ): LoadedRecord =
        when (val read = files.read(path)) {
            SlockAndroidAvatarFileRead.Missing -> LoadedRecord(RecordStatus.Missing)
            SlockAndroidAvatarFileRead.Failed -> LoadedRecord(RecordStatus.Failed)
            is SlockAndroidAvatarFileRead.Present -> {
                val decoded = SlockAndroidAvatarDurableStateCodec.decode(kind, read.bytes)
                if (decoded == null || decoded.key != expectedKey) {
                    LoadedRecord(RecordStatus.Corrupt)
                } else {
                    LoadedRecord(RecordStatus.Valid, decoded)
                }
            }
        }

    private fun chooseMoreAuthoritative(
        first: SlockAndroidAvatarDurableState,
        second: SlockAndroidAvatarDurableState
    ): SlockAndroidAvatarDurableState? {
        if (first.key != second.key) return null
        if (first.phase != SlockAndroidAvatarDurablePhase.Idle &&
            second.phase != SlockAndroidAvatarDurablePhase.Idle &&
            first.transactionId != second.transactionId
        ) {
            return null
        }
        if (first.stateSequence != second.stateSequence) {
            return if (first.stateSequence > second.stateSequence) first else second
        }
        return null
    }

    private fun managedKeysLocked(): Set<SlockAndroidAvatarOpaqueKey>? =
        files.listFiles()?.mapNotNull(SlockAndroidAvatarOpaqueKey::fromManagedPath)?.toSet()

    private fun managedFilesForKeyLocked(key: SlockAndroidAvatarOpaqueKey): Set<String>? =
        files.listFiles()?.filter { path -> key.ownsManagedPath(path) }?.toSet()

    private fun managedDataFilesLocked(): Set<String>? =
        files.listFiles()?.filter(String::isSlockAndroidAvatarDataPath)?.filter { path ->
            SlockAndroidAvatarOpaqueKey.fromManagedPath(path) != null
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

    private fun refreshNextAccessOrdinalLocked(keys: Set<SlockAndroidAvatarOpaqueKey>) {
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
        fence: SlockAndroidAvatarRequestFence,
        kind: SlockAndroidAvatarLeaseKind
    ): SlockAndroidAvatarLease? {
        if (!isFenceCurrentLocked(fence)) return null
        val id = nextLeaseId++
        activeLeases[id] = ActiveLease(fence.key, kind)
        return SlockAndroidAvatarLease(kind, fence, id, ::releaseLease)
    }

    private fun releaseLease(id: Long) {
        lock.withLock {
            if (activeLeases.remove(id) != null) leaseDrained.signalAll()
        }
    }

    private fun isFenceCurrentLocked(fence: SlockAndroidAvatarRequestFence): Boolean {
        if (recoveryAdmissionBlocked || closedScopes.any(fence.key::matches)) return false
        val slot = requestSlots[fence.key] ?: return false
        return slot.requestGeneration == fence.requestGeneration &&
            slot.sessionIdentity == fence.sessionIdentity &&
            slot.epochs == fence.terminalEpochs &&
            fence.terminalEpochs == epochsForLocked(fence.key)
    }

    private fun epochsForLocked(key: SlockAndroidAvatarOpaqueKey): SlockAndroidAvatarTerminalEpochs =
        SlockAndroidAvatarTerminalEpochs(
            global = globalEpoch,
            owner = ownerEpochs[key.partitionKey to key.ownerKey] ?: 0L,
            subject =
                subjectEpochs[Triple(key.partitionKey, key.ownerKey, key.subjectKey)] ?: 0L
        )

    private fun SlockAndroidAvatarTerminalScope.containsTerminalScope(
        candidate: SlockAndroidAvatarTerminalScope
    ): Boolean =
        when (this) {
            SlockAndroidAvatarTerminalScope.All -> true
            is SlockAndroidAvatarTerminalScope.Owner ->
                when (candidate) {
                    SlockAndroidAvatarTerminalScope.All -> false
                    is SlockAndroidAvatarTerminalScope.Owner ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                    is SlockAndroidAvatarTerminalScope.Subject ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                }
            is SlockAndroidAvatarTerminalScope.Subject -> this == candidate
        }

    private fun incrementEpochLocked(scope: SlockAndroidAvatarTerminalScope) {
        when (scope) {
            SlockAndroidAvatarTerminalScope.All -> globalEpoch = globalEpoch.incrementEpoch()
            is SlockAndroidAvatarTerminalScope.Owner -> {
                val key = scope.partitionKey to scope.ownerKey
                ownerEpochs[key] = (ownerEpochs[key] ?: 0L).incrementEpoch()
            }
            is SlockAndroidAvatarTerminalScope.Subject -> {
                val key = Triple(scope.partitionKey, scope.ownerKey, scope.subjectKey)
                subjectEpochs[key] = (subjectEpochs[key] ?: 0L).incrementEpoch()
            }
        }
    }

    private fun Long.incrementEpoch(): Long =
        also { require(it < Long.MAX_VALUE) } + 1L

    private fun RequestSlot.toFence(key: SlockAndroidAvatarOpaqueKey): SlockAndroidAvatarRequestFence =
        SlockAndroidAvatarRequestFence(
            key = key,
            requestGeneration = requestGeneration,
            sessionIdentity = sessionIdentity,
            terminalEpochs = epochs
        )

    private fun nextIdentity(): String =
        identityFactory.nextOpaqueIdentity().also { identity ->
            require(identity.matches(SlockAndroidAvatarOpaqueComponentPattern))
        }

    private fun staleOrClosedResult(key: SlockAndroidAvatarOpaqueKey): SlockAndroidAvatarOperationResult =
        SlockAndroidAvatarOperationResult(
            if (recoveryAdmissionBlocked || closedScopes.any(key::matches)) {
                SlockAndroidAvatarOperationStatus.AdmissionClosed
            } else {
                SlockAndroidAvatarOperationStatus.StaleRequest
            }
        )

    private fun cleanupPending(): SlockAndroidAvatarOperationResult =
        SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.CleanupPending)

    private fun storageFailure(): SlockAndroidAvatarOperationResult =
        SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.StorageFailure)

    private fun corruptState(): SlockAndroidAvatarOperationResult =
        SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.CorruptState)

    private companion object {
        val TerminalMarkerBytes = "raft-avatar-terminal-v1".toByteArray(Charsets.UTF_8)
    }
}
