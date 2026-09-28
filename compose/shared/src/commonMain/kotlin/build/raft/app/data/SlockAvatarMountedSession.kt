package build.raft.app.data

import build.raft.app.slockSha256Hex
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
data class SlockAvatarCommitIdentity(
    val commitGeneration: Long,
    val nonce: String
) {
    init {
        require(commitGeneration > 0L)
        require(nonce.matches(SlockAvatarCommitNoncePattern))
    }
}

internal data class SlockAvatarValidators(
    val eTag: String? = null,
    val lastModified: String? = null
) {
    init {
        require(eTag == null || (eTag.isNotBlank() && eTag == eTag.trim()))
        require(lastModified == null || (lastModified.isNotBlank() && lastModified == lastModified.trim()))
        require(eTag != null || lastModified != null)
    }
}

internal data class SlockAvatarMountedTerminalEpochs(
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

internal class SlockAvatarMountToken internal constructor(
    internal val mountId: Long,
    val key: SlockAvatarCacheKey,
    val sessionEpoch: Long,
    val terminalEpochs: SlockAvatarMountedTerminalEpochs
)

internal data class SlockAvatarMountedSnapshot(
    val key: SlockAvatarCacheKey,
    val commitIdentity: SlockAvatarCommitIdentity?,
    val validators: SlockAvatarValidators?,
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
 * key. A flight is process-local and can only be resolved by the [SlockAvatarMountedSession] that
 * issued this exact object.
 */
internal class SlockAvatarMountedFlight internal constructor(
    val key: SlockAvatarCacheKey,
    val requestGeneration: Long,
    val requestNonce: String,
    val expectedCommitIdentity: SlockAvatarCommitIdentity?,
    val validators: SlockAvatarValidators?,
    val expectedModelRevision: Long,
    val terminalEpochs: SlockAvatarMountedTerminalEpochs
) {
    init {
        require(requestGeneration > 0L)
        require(requestNonce.matches(SlockAvatarCommitNoncePattern))
        require(expectedModelRevision >= 0L)
        require((expectedCommitIdentity == null) == (expectedModelRevision == 0L))
        require(expectedCommitIdentity != null || validators == null)
    }

    val requiresFullCanonicalBody: Boolean
        get() = validators == null
}

internal enum class SlockAvatarMountedKeepReason {
    NotModified,
    TemporaryFailure
}

internal enum class SlockAvatarMountedClearReason {
    ResourceAbsent,
    InvalidContent,
    Terminal
}

internal sealed interface SlockAvatarMountedOutcome {
    data class Reload(
        val expectedFromCommitIdentity: SlockAvatarCommitIdentity?,
        val committedToCommitIdentity: SlockAvatarCommitIdentity,
        val validators: SlockAvatarValidators?,
        val newModelRevision: Long
    ) : SlockAvatarMountedOutcome {
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
        val expectedCommitIdentity: SlockAvatarCommitIdentity?,
        val reason: SlockAvatarMountedKeepReason
    ) : SlockAvatarMountedOutcome

    data class Clear(
        val expectedCommitIdentity: SlockAvatarCommitIdentity?,
        val reason: SlockAvatarMountedClearReason
    ) : SlockAvatarMountedOutcome {
        init {
            require(reason != SlockAvatarMountedClearReason.Terminal) {
                "terminal clear is issued by the session retirement boundary"
            }
        }
    }
}

internal sealed interface SlockAvatarMountedTransition {
    val token: SlockAvatarMountToken

    data class Reload(
        override val token: SlockAvatarMountToken,
        val expectedFromCommitIdentity: SlockAvatarCommitIdentity?,
        val committedToCommitIdentity: SlockAvatarCommitIdentity,
        val newModelRevision: Long
    ) : SlockAvatarMountedTransition

    data class Keep(
        override val token: SlockAvatarMountToken,
        val reason: SlockAvatarMountedKeepReason,
        val modelRevision: Long
    ) : SlockAvatarMountedTransition

    data class Clear(
        override val token: SlockAvatarMountToken,
        val reason: SlockAvatarMountedClearReason
    ) : SlockAvatarMountedTransition
}

internal sealed interface SlockAvatarMountedResolution {
    data class Applied(val transitions: List<SlockAvatarMountedTransition>) :
        SlockAvatarMountedResolution

    data object RejectedStaleFlight : SlockAvatarMountedResolution
    data object RejectedCommitCas : SlockAvatarMountedResolution
}

/**
 * Process-local mounted-avatar presentation authority.
 *
 * The session owns only identity, validators, revisions, and lifecycle epochs. Native remains the
 * sole encoded/decoded byte owner. No state here is written to SQL, route/pageData, or a platform
 * cache. Phase 3 may adapt this boundary to the native kernel without moving those responsibilities
 * into shared.
 */
internal class SlockAvatarMountedSession(
    private val requestNonceGenerator: () -> String = ::slockAvatarMountedRequestNonce
) {
    private val lock = SynchronizedObject()
    private var nextMountId = 0L
    private var nextSessionEpoch = 0L
    private var nextRequestGeneration = 0L
    private var globalTerminalEpoch = 0L
    private val ownerTerminalEpochs = mutableMapOf<SlockAvatarCacheOwner, Long>()
    private val subjectTerminalEpochs = mutableMapOf<SlockAvatarMountedSlot, Long>()
    private val metadata = mutableMapOf<SlockAvatarMountedSlot, SlockAvatarMountedMetadata>()
    private val mounts = mutableMapOf<Long, SlockAvatarMountedRecord>()
    private val activeFlights = mutableMapOf<SlockAvatarMountedSlot, SlockAvatarMountedActiveFlight>()

    fun mount(key: SlockAvatarCacheKey): SlockAvatarMountToken =
        synchronized(lock) {
            val slot = key.mountedSlot()
            val occupiedByDifferentContent =
                metadata[slot]?.key?.let { it != key } == true ||
                    activeFlights[slot]?.flight?.key?.let { it != key } == true ||
                    mounts.values.any { record ->
                        record.token.key.mountedSlot() == slot && record.token.key != key
                    }
            if (occupiedByDifferentContent) {
                clearSlotLocked(slot, SlockAvatarMountedClearReason.Terminal)
            }
            val token =
                SlockAvatarMountToken(
                    mountId = nextPositiveMountId(),
                    key = key,
                    sessionEpoch = nextPositiveSessionEpoch(),
                    terminalEpochs = epochsForLocked(slot)
                )
            mounts[token.mountId] = SlockAvatarMountedRecord(token)
            token
        }

    fun dispose(token: SlockAvatarMountToken): Boolean =
        synchronized(lock) {
            val current = mounts[token.mountId] ?: return@synchronized false
            if (current.token !== token) return@synchronized false
            mounts.remove(token.mountId)
            true
        }

    fun isCurrent(token: SlockAvatarMountToken): Boolean =
        synchronized(lock) { isCurrentLocked(token) }

    fun snapshot(token: SlockAvatarMountToken): SlockAvatarMountedSnapshot? =
        synchronized(lock) {
            if (!isCurrentLocked(token)) return@synchronized null
            snapshotForLocked(token.key)
        }

    fun snapshotFor(
        owner: SlockAvatarCacheOwner,
        subject: SlockAvatarCacheSubject
    ): SlockAvatarMountedSnapshot? =
        synchronized(lock) {
            val slot = SlockAvatarMountedSlot(owner, subject)
            val key =
                metadata[slot]?.key
                    ?: activeFlights[slot]?.flight?.key
                    ?: mounts.values.firstOrNull { it.token.key.mountedSlot() == slot }?.token?.key
                    ?: return@synchronized null
            snapshotForLocked(key)
        }

    fun snapshotForSubject(
        subject: SlockAvatarCacheSubject,
        content: SlockAvatarContentIdentity
    ): SlockAvatarMountedSnapshot? =
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
        token: SlockAvatarMountToken,
        durable: SlockAvatarCacheMetadata
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
                SlockAvatarMountedMetadata(
                    key = token.key,
                    commitIdentity = identity,
                    validators =
                        if (durable.eTag != null || durable.lastModified != null) {
                            SlockAvatarValidators(
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
        key: SlockAvatarCacheKey,
        commitIdentity: SlockAvatarCommitIdentity,
        validators: SlockAvatarValidators?,
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
                SlockAvatarMountedMetadata(
                    key = key,
                    commitIdentity = commitIdentity,
                    validators = validators,
                    modelRevision = modelRevision
                )
            true
        }

    /** Seeds exact SQL authority while allowing a missing native body to suppress old validators. */
    fun seedPrepared(
        token: SlockAvatarMountToken,
        commitIdentity: SlockAvatarCommitIdentity?,
        validators: SlockAvatarValidators?,
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
                SlockAvatarMountedMetadata(
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
    fun beginFlight(token: SlockAvatarMountToken): SlockAvatarMountedFlight? =
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
            } ?: SlockAvatarMountedFlight(
                key = token.key,
                requestGeneration = nextPositiveRequestGeneration(),
                requestNonce = requestNonceGenerator(),
                expectedCommitIdentity = current.commitIdentity,
                validators = current.validators,
                expectedModelRevision = current.modelRevision,
                terminalEpochs = currentEpochs
            ).also { flight ->
                activeFlights[slot] =
                    SlockAvatarMountedActiveFlight(
                        flight = flight,
                        participantMountIds = linkedSetOf(token.mountId)
                    )
            }
        }

    fun isFlightCurrent(flight: SlockAvatarMountedFlight): Boolean =
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
    fun abandonFlight(flight: SlockAvatarMountedFlight): Boolean =
        synchronized(lock) {
            val slot = flight.key.mountedSlot()
            val active = activeFlights[slot] ?: return@synchronized false
            if (active.flight !== flight) return@synchronized false
            activeFlights.remove(slot)
            true
        }

    fun resolve(
        flight: SlockAvatarMountedFlight,
        outcome: SlockAvatarMountedOutcome
    ): SlockAvatarMountedResolution =
        synchronized(lock) {
            val slot = flight.key.mountedSlot()
            val active = activeFlights[slot]
            if (active?.flight !== flight || epochsForLocked(slot) != flight.terminalEpochs) {
                return@synchronized SlockAvatarMountedResolution.RejectedStaleFlight
            }
            activeFlights.remove(slot)
            val hasCurrentParticipant = active.participantMountIds.any { mountId ->
                mounts[mountId]?.token?.let(::isCurrentLocked) == true
            }
            if (!hasCurrentParticipant) {
                return@synchronized SlockAvatarMountedResolution.RejectedStaleFlight
            }
            val current = snapshotForLocked(flight.key)
            if (
                current.commitIdentity != flight.expectedCommitIdentity ||
                current.modelRevision != flight.expectedModelRevision
            ) {
                return@synchronized SlockAvatarMountedResolution.RejectedCommitCas
            }
            when (outcome) {
                is SlockAvatarMountedOutcome.Reload ->
                    resolveReloadLocked(slot, flight, current, outcome)
                is SlockAvatarMountedOutcome.Keep ->
                    resolveKeepLocked(flight, current, outcome)
                is SlockAvatarMountedOutcome.Clear ->
                    resolveClearLocked(slot, flight, outcome)
            }
        }

    fun retireSubject(
        owner: SlockAvatarCacheOwner,
        subject: SlockAvatarCacheSubject
    ): List<SlockAvatarMountedTransition.Clear> =
        synchronized(lock) {
            val slot = SlockAvatarMountedSlot(owner, subject)
            subjectTerminalEpochs[slot] = nextEpoch(subjectTerminalEpochs[slot] ?: 0L)
            clearSlotLocked(slot, SlockAvatarMountedClearReason.Terminal)
        }

    fun retireOwner(owner: SlockAvatarCacheOwner): List<SlockAvatarMountedTransition.Clear> =
        synchronized(lock) {
            ownerTerminalEpochs[owner] = nextEpoch(ownerTerminalEpochs[owner] ?: 0L)
            slotsForOwnerLocked(owner).flatMap { slot ->
                clearSlotLocked(slot, SlockAvatarMountedClearReason.Terminal)
            }
        }

    fun clearAll(): List<SlockAvatarMountedTransition.Clear> =
        synchronized(lock) {
            globalTerminalEpoch = nextEpoch(globalTerminalEpoch)
            allSlotsLocked().flatMap { slot ->
                clearSlotLocked(slot, SlockAvatarMountedClearReason.Terminal)
            }
        }

    private fun resolveReloadLocked(
        slot: SlockAvatarMountedSlot,
        flight: SlockAvatarMountedFlight,
        current: SlockAvatarMountedSnapshot,
        outcome: SlockAvatarMountedOutcome.Reload
    ): SlockAvatarMountedResolution {
        if (
            outcome.expectedFromCommitIdentity != flight.expectedCommitIdentity ||
            outcome.newModelRevision <= current.modelRevision
        ) {
            return SlockAvatarMountedResolution.RejectedCommitCas
        }
        metadata[slot] =
            SlockAvatarMountedMetadata(
                key = flight.key,
                commitIdentity = outcome.committedToCommitIdentity,
                validators = outcome.validators,
                modelRevision = outcome.newModelRevision
            )
        return SlockAvatarMountedResolution.Applied(
            currentTokensForLocked(flight.key).map { token ->
                SlockAvatarMountedTransition.Reload(
                    token = token,
                    expectedFromCommitIdentity = outcome.expectedFromCommitIdentity,
                    committedToCommitIdentity = outcome.committedToCommitIdentity,
                    newModelRevision = outcome.newModelRevision
                )
            }
        )
    }

    private fun resolveKeepLocked(
        flight: SlockAvatarMountedFlight,
        current: SlockAvatarMountedSnapshot,
        outcome: SlockAvatarMountedOutcome.Keep
    ): SlockAvatarMountedResolution {
        if (outcome.expectedCommitIdentity != flight.expectedCommitIdentity) {
            return SlockAvatarMountedResolution.RejectedCommitCas
        }
        // Keep never mutates identity, validators, or model revision.
        return SlockAvatarMountedResolution.Applied(
            currentTokensForLocked(flight.key).map { token ->
                SlockAvatarMountedTransition.Keep(token, outcome.reason, current.modelRevision)
            }
        )
    }

    private fun resolveClearLocked(
        slot: SlockAvatarMountedSlot,
        flight: SlockAvatarMountedFlight,
        outcome: SlockAvatarMountedOutcome.Clear
    ): SlockAvatarMountedResolution {
        if (outcome.expectedCommitIdentity != flight.expectedCommitIdentity) {
            return SlockAvatarMountedResolution.RejectedCommitCas
        }
        return SlockAvatarMountedResolution.Applied(clearSlotLocked(slot, outcome.reason))
    }

    private fun clearSlotLocked(
        slot: SlockAvatarMountedSlot,
        reason: SlockAvatarMountedClearReason
    ): List<SlockAvatarMountedTransition.Clear> {
        activeFlights.remove(slot)
        metadata.remove(slot)
        val matching =
            mounts.values
                .filter { it.token.key.mountedSlot() == slot }
                .map { it.token }
        matching.forEach { token -> mounts.remove(token.mountId) }
        return matching.map { token -> SlockAvatarMountedTransition.Clear(token, reason) }
    }

    private fun currentTokensForLocked(key: SlockAvatarCacheKey): List<SlockAvatarMountToken> =
        mounts.values
            .asSequence()
            .map { it.token }
            .filter { token -> token.key == key && token.terminalEpochs == epochsForLocked(key.mountedSlot()) }
            .toList()

    private fun snapshotForLocked(key: SlockAvatarCacheKey): SlockAvatarMountedSnapshot {
        val selected = metadata[key.mountedSlot()]?.takeIf { it.key == key }
        return SlockAvatarMountedSnapshot(
            key = key,
            commitIdentity = selected?.commitIdentity,
            validators = selected?.validators,
            modelRevision = selected?.modelRevision ?: 0L
        )
    }

    private fun isCurrentLocked(token: SlockAvatarMountToken): Boolean =
        mounts[token.mountId]?.token === token &&
            token.terminalEpochs == epochsForLocked(token.key.mountedSlot())

    private fun epochsForLocked(slot: SlockAvatarMountedSlot): SlockAvatarMountedTerminalEpochs =
        SlockAvatarMountedTerminalEpochs(
            global = globalTerminalEpoch,
            owner = ownerTerminalEpochs[slot.owner] ?: 0L,
            subject = subjectTerminalEpochs[slot] ?: 0L
        )

    private fun slotsForOwnerLocked(owner: SlockAvatarCacheOwner): Set<SlockAvatarMountedSlot> =
        allSlotsLocked().filterTo(linkedSetOf()) { it.owner == owner }

    private fun allSlotsLocked(): Set<SlockAvatarMountedSlot> =
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

private data class SlockAvatarMountedSlot(
    val owner: SlockAvatarCacheOwner,
    val subject: SlockAvatarCacheSubject
)

private data class SlockAvatarMountedMetadata(
    val key: SlockAvatarCacheKey,
    val commitIdentity: SlockAvatarCommitIdentity,
    val validators: SlockAvatarValidators?,
    val modelRevision: Long
)

private data class SlockAvatarMountedRecord(
    val token: SlockAvatarMountToken
)

private data class SlockAvatarMountedActiveFlight(
    val flight: SlockAvatarMountedFlight,
    val participantMountIds: MutableSet<Long>
)

private fun SlockAvatarCacheKey.mountedSlot(): SlockAvatarMountedSlot =
    SlockAvatarMountedSlot(owner, subject)

private fun slockAvatarMountedRequestNonce(): String =
    slockSha256Hex(Random.Default.nextBytes(ByteArray(32)))

private val SlockAvatarCommitNoncePattern = Regex("[0-9a-f]{64}")
