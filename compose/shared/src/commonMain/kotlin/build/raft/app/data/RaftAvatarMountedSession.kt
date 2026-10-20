package build.raft.app.data

import build.raft.app.raftSha256Hex
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.random.Random

/**
 * Durable native commit coordinate carried through the shared write boundary.
 *
 * This is identity only. Shared never owns encoded/decoded bytes, native paths, file handles, or
 * a durable cache database. Under the best-effort C contract, a terminal clear may forget every
 * historical identity; the next namespace starts from a full canonical response.
 */
data class RaftAvatarCommitIdentity(
    val commitGeneration: Long,
    val nonce: String
) {
    init {
        require(commitGeneration > 0L)
        require(nonce.matches(RaftAvatarCommitNoncePattern))
    }
}

internal data class RaftAvatarValidators(
    val eTag: String? = null,
    val lastModified: String? = null
) {
    init {
        require(eTag == null || (eTag.isNotBlank() && eTag == eTag.trim()))
        require(lastModified == null || (lastModified.isNotBlank() && lastModified == lastModified.trim()))
        require(eTag != null || lastModified != null)
    }
}

internal data class RaftAvatarMountedTerminalEpochs(
    val global: Long,
    val owner: Long,
    val subject: Long
) {
    init {
        require(global >= 0L)
        require(owner >= 0L)
        require(subject >= 0L)
    }
}

internal class RaftAvatarMountToken internal constructor(
    internal val mountId: Long,
    val key: RaftAvatarCacheKey,
    val sessionEpoch: Long,
    val terminalEpochs: RaftAvatarMountedTerminalEpochs
)

internal data class RaftAvatarMountedSnapshot(
    val key: RaftAvatarCacheKey,
    val commitIdentity: RaftAvatarCommitIdentity?,
    val validators: RaftAvatarValidators?,
    val modelRevision: Long
) {
    init {
        require(modelRevision >= 0L)
        require((commitIdentity == null) == (modelRevision == 0L))
        require(commitIdentity != null || validators == null) {
            "avatar validators require a committed identity"
        }
    }

    val requiresFullCanonicalBody: Boolean
        get() = commitIdentity == null
}

/**
 * One request generation shared by currently mounted views for the same exact owner/subject/content
 * key. A flight is process-local and can only be resolved by the [RaftAvatarMountedSession] that
 * issued this exact object.
 */
internal class RaftAvatarMountedFlight internal constructor(
    val key: RaftAvatarCacheKey,
    val requestGeneration: Long,
    val requestNonce: String,
    val expectedCommitIdentity: RaftAvatarCommitIdentity?,
    val validators: RaftAvatarValidators?,
    val expectedModelRevision: Long,
    val terminalEpochs: RaftAvatarMountedTerminalEpochs
) {
    init {
        require(requestGeneration > 0L)
        require(requestNonce.matches(RaftAvatarCommitNoncePattern))
        require(expectedModelRevision >= 0L)
        require((expectedCommitIdentity == null) == (expectedModelRevision == 0L))
        require(expectedCommitIdentity != null || validators == null)
    }

    val requiresFullCanonicalBody: Boolean
        get() = validators == null
}

internal enum class RaftAvatarMountedKeepReason {
    NotModified,
    TemporaryFailure
}

internal enum class RaftAvatarMountedClearReason {
    ResourceAbsent,
    InvalidContent,
    Terminal
}

internal sealed interface RaftAvatarMountedOutcome {
    data class Reload(
        val expectedFromCommitIdentity: RaftAvatarCommitIdentity?,
        val committedToCommitIdentity: RaftAvatarCommitIdentity,
        val validators: RaftAvatarValidators?,
        val newModelRevision: Long
    ) : RaftAvatarMountedOutcome {
        init {
            require(newModelRevision > 0L)
            require(
                expectedFromCommitIdentity == null ||
                    committedToCommitIdentity.commitGeneration > expectedFromCommitIdentity.commitGeneration
            ) {
                "avatar reload must advance the live commit generation"
            }
        }
    }

    data class Keep(
        val expectedCommitIdentity: RaftAvatarCommitIdentity?,
        val reason: RaftAvatarMountedKeepReason
    ) : RaftAvatarMountedOutcome

    data class Clear(
        val expectedCommitIdentity: RaftAvatarCommitIdentity?,
        val reason: RaftAvatarMountedClearReason
    ) : RaftAvatarMountedOutcome {
        init {
            require(reason != RaftAvatarMountedClearReason.Terminal) {
                "terminal clear is issued by the session retirement boundary"
            }
        }
    }
}

internal sealed interface RaftAvatarMountedTransition {
    val token: RaftAvatarMountToken

    data class Reload(
        override val token: RaftAvatarMountToken,
        val expectedFromCommitIdentity: RaftAvatarCommitIdentity?,
        val committedToCommitIdentity: RaftAvatarCommitIdentity,
        val newModelRevision: Long
    ) : RaftAvatarMountedTransition

    data class Keep(
        override val token: RaftAvatarMountToken,
        val reason: RaftAvatarMountedKeepReason,
        val modelRevision: Long
    ) : RaftAvatarMountedTransition

    data class Clear(
        override val token: RaftAvatarMountToken,
        val reason: RaftAvatarMountedClearReason
    ) : RaftAvatarMountedTransition
}

internal sealed interface RaftAvatarMountedResolution {
    data class Applied(val transitions: List<RaftAvatarMountedTransition>) :
        RaftAvatarMountedResolution

    data object RejectedStaleFlight : RaftAvatarMountedResolution
    data object RejectedCommitCas : RaftAvatarMountedResolution
}

/**
 * Process-local mounted-avatar presentation authority.
 *
 * The session owns only identity, validators, revisions, and lifecycle epochs. Native remains the
 * sole encoded/decoded byte owner. No state here is written to SQL, route/pageData, or a platform
 * cache. Phase 3 may adapt this boundary to the native kernel without moving those responsibilities
 * into shared.
 */
internal class RaftAvatarMountedSession(
    private val requestNonceGenerator: () -> String = ::raftAvatarMountedRequestNonce
) {
    private val lock = SynchronizedObject()
    private var nextMountId = 0L
    private var nextSessionEpoch = 0L
    private var nextRequestGeneration = 0L
    private var globalTerminalEpoch = 0L
    private val ownerTerminalEpochs = mutableMapOf<RaftAvatarCacheOwner, Long>()
    private val subjectTerminalEpochs = mutableMapOf<RaftAvatarMountedSlot, Long>()
    private val metadata = mutableMapOf<RaftAvatarMountedSlot, RaftAvatarMountedMetadata>()
    private val mounts = mutableMapOf<Long, RaftAvatarMountedRecord>()
    private val activeFlights = mutableMapOf<RaftAvatarMountedSlot, RaftAvatarMountedActiveFlight>()

    fun mount(key: RaftAvatarCacheKey): RaftAvatarMountToken =
        synchronized(lock) {
            val slot = key.mountedSlot()
            val occupiedByDifferentContent =
                metadata[slot]?.key?.let { it != key } == true ||
                    activeFlights[slot]?.flight?.key?.let { it != key } == true ||
                    mounts.values.any { record ->
                        record.token.key.mountedSlot() == slot && record.token.key != key
                    }
            if (occupiedByDifferentContent) {
                clearSlotLocked(slot, RaftAvatarMountedClearReason.Terminal)
            }
            val token =
                RaftAvatarMountToken(
                    mountId = nextPositiveMountId(),
                    key = key,
                    sessionEpoch = nextPositiveSessionEpoch(),
                    terminalEpochs = epochsForLocked(slot)
                )
            mounts[token.mountId] = RaftAvatarMountedRecord(token)
            token
        }

    fun dispose(token: RaftAvatarMountToken): Boolean =
        synchronized(lock) {
            val current = mounts[token.mountId] ?: return@synchronized false
            if (current.token !== token) return@synchronized false
            mounts.remove(token.mountId)
            true
        }

    fun isCurrent(token: RaftAvatarMountToken): Boolean =
        synchronized(lock) { isCurrentLocked(token) }

    fun snapshot(token: RaftAvatarMountToken): RaftAvatarMountedSnapshot? =
        synchronized(lock) {
            if (!isCurrentLocked(token)) return@synchronized null
            snapshotForLocked(token.key)
        }

    fun snapshotFor(
        owner: RaftAvatarCacheOwner,
        subject: RaftAvatarCacheSubject
    ): RaftAvatarMountedSnapshot? =
        synchronized(lock) {
            val slot = RaftAvatarMountedSlot(owner, subject)
            val key =
                metadata[slot]?.key
                    ?: activeFlights[slot]?.flight?.key
                    ?: mounts.values.firstOrNull { it.token.key.mountedSlot() == slot }?.token?.key
                    ?: return@synchronized null
            snapshotForLocked(key)
        }

    fun snapshotForSubject(
        subject: RaftAvatarCacheSubject,
        content: RaftAvatarContentIdentity
    ): RaftAvatarMountedSnapshot? =
        synchronized(lock) {
            val entry = metadata.entries.firstOrNull {
                it.key.subject == subject && it.value.key.content == content
            }
            val key = entry?.value?.key
                ?: mounts.values.firstOrNull {
                    it.token.key.subject == subject && it.token.key.content == content
                }?.token?.key
                ?: return@synchronized null
            snapshotForLocked(key)
        }

    /**
     * Seeds an exact durable commit after process restart. It may only initialize an untouched
     * current mount: an active flight or any in-memory commit wins and cannot be overwritten by a
     * delayed SQL read.
     */
    fun seedDurable(
        token: RaftAvatarMountToken,
        durable: RaftAvatarCacheMetadata
    ): Boolean =
        synchronized(lock) {
            if (!isCurrentLocked(token) || durable.key != token.key) return@synchronized false
            val identity = durable.commitIdentity ?: return@synchronized false
            if (durable.modelRevision <= 0L) return@synchronized false
            val slot = token.key.mountedSlot()
            if (activeFlights.containsKey(slot) || metadata.containsKey(slot)) {
                return@synchronized false
            }
            metadata[slot] =
                RaftAvatarMountedMetadata(
                    key = token.key,
                    commitIdentity = identity,
                    validators =
                        if (durable.eTag != null || durable.lastModified != null) {
                            RaftAvatarValidators(
                                eTag = durable.eTag,
                                lastModified = durable.lastModified
                            )
                        } else {
                            null
                        },
                    modelRevision = durable.modelRevision
                )
            true
        }

    /**
     * task #343: cold-start prewarm. Seeds exact durable authority into a slot only when the slot
     * is completely untouched in this process (no metadata, flight, or mount), atomically, so a
     * concurrent live mount always wins and prewarm can never clear or displace it. Respects owner
     * and subject terminal epochs the same way a fresh mount would.
     */
    fun seedIdle(
        key: RaftAvatarCacheKey,
        commitIdentity: RaftAvatarCommitIdentity,
        validators: RaftAvatarValidators?,
        modelRevision: Long
    ): Boolean =
        synchronized(lock) {
            if (modelRevision <= 0L) return@synchronized false
            val slot = key.mountedSlot()
            if (
                metadata.containsKey(slot) ||
                    activeFlights.containsKey(slot) ||
                    mounts.values.any { it.token.key.mountedSlot() == slot }
            ) {
                return@synchronized false
            }
            metadata[slot] =
                RaftAvatarMountedMetadata(
                    key = key,
                    commitIdentity = commitIdentity,
                    validators = validators,
                    modelRevision = modelRevision
                )
            true
        }

    /** Seeds exact SQL authority while allowing a missing native body to suppress old validators. */
    fun seedPrepared(
        token: RaftAvatarMountToken,
        commitIdentity: RaftAvatarCommitIdentity?,
        validators: RaftAvatarValidators?,
        modelRevision: Long
    ): Boolean =
        synchronized(lock) {
            if (!isCurrentLocked(token) || commitIdentity == null || modelRevision <= 0L) {
                return@synchronized false
            }
            val slot = token.key.mountedSlot()
            if (activeFlights.containsKey(slot) || metadata.containsKey(slot)) {
                return@synchronized false
            }
            metadata[slot] =
                RaftAvatarMountedMetadata(
                    key = token.key,
                    commitIdentity = commitIdentity,
                    validators = validators,
                    modelRevision = modelRevision
                )
            true
        }

    /**
     * Joins the current exact flight or creates a new request generation. Validators are emitted
     * only with the exact committed identity they belong to. A missing identity always forces a
     * full canonical response.
     */
    fun beginFlight(token: RaftAvatarMountToken): RaftAvatarMountedFlight? =
        synchronized(lock) {
            if (!isCurrentLocked(token)) return@synchronized null
            val slot = token.key.mountedSlot()
            val currentEpochs = epochsForLocked(slot)
            if (currentEpochs != token.terminalEpochs) return@synchronized null
            val current = snapshotForLocked(token.key)
            activeFlights[slot]?.takeIf { active ->
                active.flight.key == token.key &&
                    active.flight.terminalEpochs == currentEpochs &&
                    active.flight.expectedCommitIdentity == current.commitIdentity &&
                    active.flight.expectedModelRevision == current.modelRevision
            }?.let { active ->
                active.participantMountIds += token.mountId
                active.flight
            } ?: RaftAvatarMountedFlight(
                key = token.key,
                requestGeneration = nextPositiveRequestGeneration(),
                requestNonce = requestNonceGenerator(),
                expectedCommitIdentity = current.commitIdentity,
                validators = current.validators,
                expectedModelRevision = current.modelRevision,
                terminalEpochs = currentEpochs
            ).also { flight ->
                activeFlights[slot] =
                    RaftAvatarMountedActiveFlight(
                        flight = flight,
                        participantMountIds = linkedSetOf(token.mountId)
                    )
            }
        }

    fun isFlightCurrent(flight: RaftAvatarMountedFlight): Boolean =
        synchronized(lock) {
            val slot = flight.key.mountedSlot()
            val active = activeFlights[slot] ?: return@synchronized false
            if (active.flight !== flight || epochsForLocked(slot) != flight.terminalEpochs) {
                return@synchronized false
            }
            val current = snapshotForLocked(flight.key)
            current.commitIdentity == flight.expectedCommitIdentity &&
                current.modelRevision == flight.expectedModelRevision &&
                active.participantMountIds.any { mountId ->
                    mounts[mountId]?.token?.let(::isCurrentLocked) == true
                }
        }

    /**
     * Removes a dead flight from the slot when it is still the registered one, so a later attempt
     * starts a fresh request generation instead of joining a generation nobody will resolve. A
     * flight whose transport was cancelled by its caller would otherwise stay parked in
     * activeFlights forever (cancellation never reaches resolve), poisoning every later refresh:
     * the next attempt would join the dead flight, complete, and be rejected as stale.
     */
    fun abandonFlight(flight: RaftAvatarMountedFlight): Boolean =
        synchronized(lock) {
            val slot = flight.key.mountedSlot()
            val active = activeFlights[slot] ?: return@synchronized false
            if (active.flight !== flight) return@synchronized false
            activeFlights.remove(slot)
            true
        }

    fun resolve(
        flight: RaftAvatarMountedFlight,
        outcome: RaftAvatarMountedOutcome
    ): RaftAvatarMountedResolution =
        synchronized(lock) {
            val slot = flight.key.mountedSlot()
            val active = activeFlights[slot]
            if (active?.flight !== flight || epochsForLocked(slot) != flight.terminalEpochs) {
                return@synchronized RaftAvatarMountedResolution.RejectedStaleFlight
            }
            activeFlights.remove(slot)
            val hasCurrentParticipant = active.participantMountIds.any { mountId ->
                mounts[mountId]?.token?.let(::isCurrentLocked) == true
            }
            if (!hasCurrentParticipant) {
                return@synchronized RaftAvatarMountedResolution.RejectedStaleFlight
            }
            val current = snapshotForLocked(flight.key)
            if (
                current.commitIdentity != flight.expectedCommitIdentity ||
                current.modelRevision != flight.expectedModelRevision
            ) {
                return@synchronized RaftAvatarMountedResolution.RejectedCommitCas
            }
            when (outcome) {
                is RaftAvatarMountedOutcome.Reload ->
                    resolveReloadLocked(slot, flight, current, outcome)
                is RaftAvatarMountedOutcome.Keep ->
                    resolveKeepLocked(flight, current, outcome)
                is RaftAvatarMountedOutcome.Clear ->
                    resolveClearLocked(slot, flight, outcome)
            }
        }

    fun retireSubject(
        owner: RaftAvatarCacheOwner,
        subject: RaftAvatarCacheSubject
    ): List<RaftAvatarMountedTransition.Clear> =
        synchronized(lock) {
            val slot = RaftAvatarMountedSlot(owner, subject)
            subjectTerminalEpochs[slot] = nextEpoch(subjectTerminalEpochs[slot] ?: 0L)
            clearSlotLocked(slot, RaftAvatarMountedClearReason.Terminal)
        }

    fun retireOwner(owner: RaftAvatarCacheOwner): List<RaftAvatarMountedTransition.Clear> =
        synchronized(lock) {
            ownerTerminalEpochs[owner] = nextEpoch(ownerTerminalEpochs[owner] ?: 0L)
            slotsForOwnerLocked(owner).flatMap { slot ->
                clearSlotLocked(slot, RaftAvatarMountedClearReason.Terminal)
            }
        }

    fun clearAll(): List<RaftAvatarMountedTransition.Clear> =
        synchronized(lock) {
            globalTerminalEpoch = nextEpoch(globalTerminalEpoch)
            allSlotsLocked().flatMap { slot ->
                clearSlotLocked(slot, RaftAvatarMountedClearReason.Terminal)
            }
        }

    private fun resolveReloadLocked(
        slot: RaftAvatarMountedSlot,
        flight: RaftAvatarMountedFlight,
        current: RaftAvatarMountedSnapshot,
        outcome: RaftAvatarMountedOutcome.Reload
    ): RaftAvatarMountedResolution {
        if (
            outcome.expectedFromCommitIdentity != flight.expectedCommitIdentity ||
            outcome.newModelRevision <= current.modelRevision
        ) {
            return RaftAvatarMountedResolution.RejectedCommitCas
        }
        metadata[slot] =
            RaftAvatarMountedMetadata(
                key = flight.key,
                commitIdentity = outcome.committedToCommitIdentity,
                validators = outcome.validators,
                modelRevision = outcome.newModelRevision
            )
        return RaftAvatarMountedResolution.Applied(
            currentTokensForLocked(flight.key).map { token ->
                RaftAvatarMountedTransition.Reload(
                    token = token,
                    expectedFromCommitIdentity = outcome.expectedFromCommitIdentity,
                    committedToCommitIdentity = outcome.committedToCommitIdentity,
                    newModelRevision = outcome.newModelRevision
                )
            }
        )
    }

    private fun resolveKeepLocked(
        flight: RaftAvatarMountedFlight,
        current: RaftAvatarMountedSnapshot,
        outcome: RaftAvatarMountedOutcome.Keep
    ): RaftAvatarMountedResolution {
        if (outcome.expectedCommitIdentity != flight.expectedCommitIdentity) {
            return RaftAvatarMountedResolution.RejectedCommitCas
        }
        // Keep never mutates identity, validators, or model revision.
        return RaftAvatarMountedResolution.Applied(
            currentTokensForLocked(flight.key).map { token ->
                RaftAvatarMountedTransition.Keep(token, outcome.reason, current.modelRevision)
            }
        )
    }

    private fun resolveClearLocked(
        slot: RaftAvatarMountedSlot,
        flight: RaftAvatarMountedFlight,
        outcome: RaftAvatarMountedOutcome.Clear
    ): RaftAvatarMountedResolution {
        if (outcome.expectedCommitIdentity != flight.expectedCommitIdentity) {
            return RaftAvatarMountedResolution.RejectedCommitCas
        }
        return RaftAvatarMountedResolution.Applied(clearSlotLocked(slot, outcome.reason))
    }

    private fun clearSlotLocked(
        slot: RaftAvatarMountedSlot,
        reason: RaftAvatarMountedClearReason
    ): List<RaftAvatarMountedTransition.Clear> {
        activeFlights.remove(slot)
        metadata.remove(slot)
        val matching =
            mounts.values
                .filter { it.token.key.mountedSlot() == slot }
                .map { it.token }
        matching.forEach { token -> mounts.remove(token.mountId) }
        return matching.map { token -> RaftAvatarMountedTransition.Clear(token, reason) }
    }

    private fun currentTokensForLocked(key: RaftAvatarCacheKey): List<RaftAvatarMountToken> =
        mounts.values
            .asSequence()
            .map { it.token }
            .filter { token -> token.key == key && token.terminalEpochs == epochsForLocked(key.mountedSlot()) }
            .toList()

    private fun snapshotForLocked(key: RaftAvatarCacheKey): RaftAvatarMountedSnapshot {
        val selected = metadata[key.mountedSlot()]?.takeIf { it.key == key }
        return RaftAvatarMountedSnapshot(
            key = key,
            commitIdentity = selected?.commitIdentity,
            validators = selected?.validators,
            modelRevision = selected?.modelRevision ?: 0L
        )
    }

    private fun isCurrentLocked(token: RaftAvatarMountToken): Boolean =
        mounts[token.mountId]?.token === token &&
            token.terminalEpochs == epochsForLocked(token.key.mountedSlot())

    private fun epochsForLocked(slot: RaftAvatarMountedSlot): RaftAvatarMountedTerminalEpochs =
        RaftAvatarMountedTerminalEpochs(
            global = globalTerminalEpoch,
            owner = ownerTerminalEpochs[slot.owner] ?: 0L,
            subject = subjectTerminalEpochs[slot] ?: 0L
        )

    private fun slotsForOwnerLocked(owner: RaftAvatarCacheOwner): Set<RaftAvatarMountedSlot> =
        allSlotsLocked().filterTo(linkedSetOf()) { it.owner == owner }

    private fun allSlotsLocked(): Set<RaftAvatarMountedSlot> =
        buildSet {
            addAll(metadata.keys)
            addAll(activeFlights.keys)
            mounts.values.forEach { add(it.token.key.mountedSlot()) }
        }

    private fun nextPositiveMountId(): Long {
        check(nextMountId < Long.MAX_VALUE) { "avatar mount id exhausted" }
        return ++nextMountId
    }

    private fun nextPositiveSessionEpoch(): Long {
        check(nextSessionEpoch < Long.MAX_VALUE) { "avatar session epoch exhausted" }
        return ++nextSessionEpoch
    }

    private fun nextPositiveRequestGeneration(): Long {
        check(nextRequestGeneration < Long.MAX_VALUE) { "avatar request generation exhausted" }
        return ++nextRequestGeneration
    }

    private fun nextEpoch(current: Long): Long {
        check(current < Long.MAX_VALUE) { "avatar terminal epoch exhausted" }
        return current + 1L
    }
}

private data class RaftAvatarMountedSlot(
    val owner: RaftAvatarCacheOwner,
    val subject: RaftAvatarCacheSubject
)

private data class RaftAvatarMountedMetadata(
    val key: RaftAvatarCacheKey,
    val commitIdentity: RaftAvatarCommitIdentity,
    val validators: RaftAvatarValidators?,
    val modelRevision: Long
)

private data class RaftAvatarMountedRecord(
    val token: RaftAvatarMountToken
)

private data class RaftAvatarMountedActiveFlight(
    val flight: RaftAvatarMountedFlight,
    val participantMountIds: MutableSet<Long>
)

private fun RaftAvatarCacheKey.mountedSlot(): RaftAvatarMountedSlot =
    RaftAvatarMountedSlot(owner, subject)

private fun raftAvatarMountedRequestNonce(): String =
    raftSha256Hex(Random.Default.nextBytes(ByteArray(32)))

private val RaftAvatarCommitNoncePattern = Regex("[0-9a-f]{64}")
