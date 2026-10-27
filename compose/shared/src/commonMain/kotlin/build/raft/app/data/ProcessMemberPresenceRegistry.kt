package build.raft.app.data

import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.parseExternalAgentTimestampOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * task #136: the process-wide single source of truth for member presence
 * facts — "one process registry, owner-scoped keys, zero per-shell mirrors"
 * (locked SSOT definition, #members专修:badc2562).
 *
 * Exactly ONE instance exists per provider/process; every realtime owner
 * writes into it and every scope store reads from it. Facts are partitioned
 * by the same typed owner tuple the process members core uses —
 * [ProcessServerMembersOwner] (environment, account, server) — so all shells
 * viewing the same owner share one liveness truth, while different servers,
 * accounts, and environments can never pollute each other: apply/evict/seq
 * rules act only within their own partition, and an auth transition clears
 * every partition.
 *
 * Presence is NEVER copied into any store state (the mirror design lost
 * updates when a stale roster publication raced the copy — Cindy's P0);
 * read edges cold-combine [factsFlow] instead.
 *
 * Replacement rules (moved verbatim from the store): serverSeq never goes
 * backwards; an equal visible presence keeps the stored fact (emission
 * thrift); a seq-less write — realtime OR projection — never displaces a
 * seq-carrying fact (task #137(a): the wire contract is "realtime presence
 * always carries serverSeq", so a seq-less realtime event is demoted to
 * projection semantics instead of the legacy else→true displacement).
 */
/**
 * Observable atomic auth binding for presence: which (environment, account)
 * the process is currently signed into, plus the registry [writeGeneration]
 * that was current when this binding was published. Null between Cleared and
 * the next Authenticated — the write edge drops events in that window and
 * read edges rebind via flatMapLatest when it changes.
 *
 * The context is IMMUTABLE: a write edge captures one instance and derives
 * both the owner partition and the write generation from that single
 * snapshot, so a concurrent auth transition can never tear owner/generation
 * apart (post-merge audit blocker on PR #1054).
 */
internal data class ProcessPresenceAuthContext(
    val environmentId: String,
    val accountId: String,
    /**
     * The authority registry's authRevision generation this binding was
     * published under. The write edge requires EXACT equality with the
     * admission's authGeneration — a delivery admitted under an earlier
     * session (same principal or not) can never write into this binding.
     */
    val authorityGeneration: Long,
    val writeGeneration: Long
) {
    /** The owner partition this auth binding maps a server to (null-safe on blanks). */
    fun ownerFor(rawServerId: String): ProcessServerMembersOwner? {
        val environment = environmentId.trim().takeIf { it.isNotBlank() } ?: return null
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val serverId = rawServerId.trim().takeIf { it.isNotBlank() } ?: return null
        return ProcessServerMembersOwner(environment, account, serverId)
    }

    /** Owner + generation frozen together from this one immutable snapshot. */
    fun writeFor(rawServerId: String): ProcessPresenceWrite? =
        ownerFor(rawServerId)?.let { owner -> ProcessPresenceWrite(owner, writeGeneration) }
}

/** A presence write binding: owner partition + the generation it was frozen under. */
internal data class ProcessPresenceWrite(
    val owner: ProcessServerMembersOwner,
    val writeGeneration: Long
)

/**
 * Immutable admission identity of one realtime presence delivery, derived
 * from the [RealtimeGrantRecord] the event was accepted under. The grant is
 * minted by the authority registry when a connection slot is granted under an
 * auth revision, so principal and server are frozen at admission — never
 * re-resolved from mutable state at delivery time.
 */
internal data class RealtimePresenceAdmission(
    val serverId: String,
    val principalId: String,
    val authGeneration: Long,
    val connectionFamilyId: String,
    val transportEpoch: Long? = null
)

internal fun RealtimeGrantRecord.presenceAdmission(
    transportEpoch: Long? = null
): RealtimePresenceAdmission =
    RealtimePresenceAdmission(
        serverId = serverId,
        principalId = principalId,
        authGeneration = authGeneration,
        connectionFamilyId = connectionFamilyId,
        transportEpoch = transportEpoch
    )

/**
 * The ONE production realtime write edge for presence (installed on every
 * realtime owner; tests drive this same function, not a re-made helper).
 *
 * Freeze rules (post-merge audit contract):
 *  - the admission identity comes from the GRANT (immutable, minted at
 *    authority-registry admission) — not resolved at delivery;
 *  - exactly one [ProcessPresenceAuthContext] snapshot is captured per
 *    delivery; owner AND write generation both derive from it;
 *  - the write happens only when the admission's authority generation AND
 *    principal both exactly match the bound context — a delivery admitted
 *    under an earlier session (late cross-account callback, same-principal
 *    relogin/rotation, environment switch) can never be attributed to the
 *    current binding, even in the window where the old grant is still
 *    current (post-merge audit round 2 on PR #1075: principal-only
 *    comparison let same-principal rotations through with the NEW registry
 *    generation, which the CAS fence cannot catch);
 *  - the registry rejects the write anyway if its generation was retired by
 *    a concurrent clearAll (checked inside the same CAS domain).
 */
internal fun processMemberPresenceRealtimeSink(
    currentPresenceAuthContext: () -> ProcessPresenceAuthContext?,
    registry: ProcessMemberPresenceRegistry,
    diagnosticsLogger: (String) -> Unit = {}
): (RealtimePresenceAdmission, MemberPresenceUpdated) -> Unit = { admission, event ->
    val context = currentPresenceAuthContext()
    val dropReason = when {
        context == null -> "signed_out"
        // `not_ready` is a PRODUCTION INVARIANT VIOLATION, never an accepted
        // drop: the binding transition completes inside the same authority
        // transition that makes this admission's grant visible (see
        // RealtimeAuthorityProvider.onAuthRevisionAdvanced). If this reason
        // ever appears in device logs, readiness is broken — that is the
        // observable difference between "rejected correctly" and "lost".
        context.authorityGeneration < admission.authGeneration -> "not_ready"
        context.authorityGeneration > admission.authGeneration -> "retired"
        context.accountId != admission.principalId -> "foreign_principal"
        else -> null
    }
    if (dropReason == null && context != null) {
        context.ownerFor(admission.serverId)?.let { owner ->
            registry.apply(
                owner = owner,
                event = event,
                writeGeneration = context.writeGeneration,
                transport =
                    admission.transportEpoch?.let { epoch ->
                        ProcessPresenceTransportEpoch(
                            connectionFamilyId = admission.connectionFamilyId,
                            epoch = epoch
                        )
                    }
            )
        }
    } else {
        try {
            diagnosticsLogger(
                "presence_write_dropped reason=$dropReason serverId=${admission.serverId} " +
                    "admissionGeneration=${admission.authGeneration} " +
                    "boundGeneration=${context?.authorityGeneration ?: "none"}"
            )
        } catch (_: Throwable) {
            // D.17: a throwing logger must never break the write edge.
        }
    }
}

/**
 * The ONE production presence-binding transition, installed as the authority
 * provider's [RealtimeAuthorityProvider.onAuthRevisionAdvanced] hook — it
 * runs INSIDE the authority lifecycle mutex, after the revision advances and
 * BEFORE the revision's grants become obtainable, so a deliverable grant
 * always finds its binding ready (no not-ready loss window, including the
 * initial session). Tests drive this same function through the real
 * provider.
 *
 * Rules per revision:
 *  - null session / blank environment → binding null, partitions cleared;
 *  - same (environment, principal) — token-only rotation / duplicate advance
 *    → keep partitions AND writeGeneration (facts never blink empty), only
 *    authorityGeneration moves so retired-session deliveries keep failing
 *    the sink equality;
 *  - different principal or environment → atomic clearAll retires the old
 *    writeGeneration, then the new immutable quadruple publishes.
 *
 * The body is intentionally non-suspending (StateFlow set + registry CAS +
 * one key-value read).  [onBindingRetired] may perform a synchronous,
 * fail-closed durable cleanup, but it must not suspend or re-enter authority
 * APIs; [onBindingBound] may synchronously retire stale durable rows while
 * preserving the exact current generation.  Both callbacks must not suspend
 * or re-enter authority APIs; the registry transition itself remains lock/CAS
 * based.
 */
internal fun processPresenceBindingTransition(
    presenceAuthContext: MutableStateFlow<ProcessPresenceAuthContext?>,
    registry: ProcessMemberPresenceRegistry,
    onBindingRetired: (ProcessPresenceAuthContext) -> Unit = {},
    onBindingBound: (ProcessPresenceAuthContext) -> Unit = {},
    /**
     * Same-binding auth revision rotation (transparent token refresh): the binding identity
     * (environment + principal) is unchanged, only the authority generation advanced. Defaults
     * to [onBindingRetired] (delete semantics) so existing callers keep their behavior; the
     * pending-send journal wires this to rehome rows into the successor generation instead.
     * Declared BEFORE [currentEnvironmentId] so existing trailing-lambda call sites (which bind
     * the trailing lambda to currentEnvironmentId) keep compiling unchanged.
     */
    onBindingAuthRevisionRotated: (retired: ProcessPresenceAuthContext, bound: ProcessPresenceAuthContext) -> Unit =
        { retired, _ -> onBindingRetired(retired) },
    currentEnvironmentId: () -> String,
): (RealtimeAuthRevision) -> Unit = { revision ->
    val principal = revision.session?.principalId?.trim()?.takeIf { it.isNotBlank() }
    val environmentId = currentEnvironmentId()
    val previous = presenceAuthContext.value
    if (principal == null || environmentId.isBlank()) {
        previous?.let(onBindingRetired)
        presenceAuthContext.value = null
        registry.clearAll(reason = "authority_session_cleared")
    } else if (
        previous != null &&
        previous.accountId == principal &&
        previous.environmentId == environmentId
    ) {
        // A token/auth-generation rotation keeps the presence facts and the
        // registry write generation alive. Durable client-owned projections keyed by the old
        // authority generation are either rehomed into the successor (pending sends, via
        // onBindingAuthRevisionRotated) or retired (default), never silently orphaned.
        val bound = previous.copy(authorityGeneration = revision.generation)
        if (previous.authorityGeneration != revision.generation) {
            onBindingAuthRevisionRotated(previous, bound)
        }
        presenceAuthContext.value = bound
        onBindingBound(bound)
    } else {
        previous?.let(onBindingRetired)
        val writeGeneration = registry.clearAll(reason = "authority_binding_changed")
        val bound =
            ProcessPresenceAuthContext(
                environmentId = environmentId,
                accountId = principal,
                authorityGeneration = revision.generation,
                writeGeneration = writeGeneration
            )
        presenceAuthContext.value = bound
        onBindingBound(bound)
    }
}

internal data class ProcessPresenceTransportEpoch(
    val connectionFamilyId: String,
    val epoch: Long
) {
    fun normalizedOrNull(): ProcessPresenceTransportEpoch? {
        val family = connectionFamilyId.trim().takeIf { it.isNotBlank() } ?: return null
        if (epoch <= 0L) return null
        return copy(connectionFamilyId = family)
    }
}

internal enum class ProcessPresenceTransportRebaselineResult {
    Applied,
    Current,
    Rejected
}

/**
 * REST projection authority is captured before the request leaves. Current
 * detail is stronger than the server roster; durable Activity is historical
 * evidence only. Requests captured from the same accepted base compete by
 * source rank, while a request captured after an accepted projection is a
 * causal refresh. This prevents both completion-order wins and permanent
 * source-rank pinning.
 */
internal enum class ProcessPresenceProjectionSource(
    val authorityRank: Int,
    val isCurrent: Boolean,
    val replacesSequencedFact: Boolean
) {
    Historical(authorityRank = 0, isCurrent = false, replacesSequencedFact = false),
    RosterCurrent(authorityRank = 1, isCurrent = true, replacesSequencedFact = false),
    DetailCurrent(authorityRank = 2, isCurrent = true, replacesSequencedFact = true)
}

private data class ProcessPresenceCurrentProjectionStamp(
    val source: ProcessPresenceProjectionSource,
    val requestVersion: Long,
    /** Accepted projection visible when this request was captured. */
    val baseRequestVersion: Long?
)

/**
 * Request-time fence for one REST presence projection. It freezes the auth
 * generation, current transport epoch, and member mutation revision before
 * the request leaves. A reconnect or realtime write during the request makes
 * the response stale and therefore unable to overwrite current state.
 */
internal data class ProcessPresenceProjectionRequest(
    val owner: ProcessServerMembersOwner,
    val memberId: String,
    val writeGeneration: Long,
    val transport: ProcessPresenceTransportEpoch?,
    val mutationVersion: Long,
    val source: ProcessPresenceProjectionSource,
    val requestVersion: Long,
    val baseRequestVersion: Long?
)

/** Server-roster request fence captured before a forced current-status refresh. */
internal data class ProcessPresenceRosterProjectionRequest(
    val owner: ProcessServerMembersOwner,
    val writeGeneration: Long,
    val transport: ProcessPresenceTransportEpoch?,
    val mutationVersions: Map<String, Long>,
    val requestVersion: Long,
    val baseRequestVersions: Map<String, Long>
) {
    fun memberRequest(memberId: String?): ProcessPresenceProjectionRequest? {
        val key = memberId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ProcessPresenceProjectionRequest(
            owner = owner,
            memberId = key,
            writeGeneration = writeGeneration,
            transport = transport,
            mutationVersion = mutationVersions[key] ?: 0L,
            source = ProcessPresenceProjectionSource.RosterCurrent,
            requestVersion = requestVersion,
            baseRequestVersion = baseRequestVersions[key]
        )
    }
}

internal data class ProcessPresenceOwnerSnapshot(
    val facts: Map<String, MemberPresenceUpdated>,
    val transport: ProcessPresenceTransportEpoch?
)

internal class ProcessMemberPresenceRegistry {

    private data class Snapshot(
        val writeGeneration: Long,
        val partitions: Map<ProcessServerMembersOwner, Map<String, MemberPresenceUpdated>>,
        /**
         * task #137(b): per-owner/member freshness watermark for projection
         * writes — the newest REST snapshot evidence (server-assigned
         * trajectory timestamp, epoch millis) seen so far. Lives in the same
         * CAS domain as the facts so the watermark check and the fact write
         * are one atomic decision. NOT reset by [clearAll]: the evidence is
         * epoch-millis monotone, so keeping it across an auth transition
         * still rejects a pre-clear in-flight snapshot that lands afterwards
         * (same-account stale window); owner-keyed, so different accounts /
         * environments / servers never share a watermark.
         */
        val projectionEvidence: Map<ProcessServerMembersOwner, Map<String, Long>> = emptyMap(),
        /** Current native transport family/connection epoch for each owner. */
        val transportEpochs: Map<ProcessServerMembersOwner, ProcessPresenceTransportEpoch> = emptyMap(),
        /** Accepted current REST authority/order; its keys are also the current-fact marker. */
        val currentProjectionStamps:
            Map<ProcessServerMembersOwner, Map<String, ProcessPresenceCurrentProjectionStamp>> = emptyMap(),
        /** Process-monotonic request order for current-state REST projections. */
        val nextProjectionRequestVersion: Long = 0L,
        /**
         * Per-owner/member non-REST mutation fence. Realtime and eviction
         * advance it; REST responses are ordered separately by source and
         * request version. Request tokens compare it atomically so a response
         * captured before a realtime/delete mutation can never publish.
         */
        val memberMutationVersions: Map<ProcessServerMembersOwner, Map<String, Long>> = emptyMap(),
        /**
         * Owners whose facts a transport advance wiped and that no authoritative roster read has
         * refilled since (Hands 81ad5dae). While set, any successful network roster load writes
         * agent status back, not only the forced reconnect refill (which can fail and is then
         * retried at most once).
         */
        val rosterRefillPending: Set<ProcessServerMembersOwner> = emptySet()
    )

    private val snapshot = MutableStateFlow(Snapshot(writeGeneration = 0L, partitions = emptyMap()))

    /** Read-only view of every partition (test observability; no second store). */
    val factsByOwner: Map<ProcessServerMembersOwner, Map<String, MemberPresenceUpdated>>
        get() = snapshot.value.partitions

    /** The generation new auth bindings freeze into their write edges. */
    val writeGeneration: Long
        get() = snapshot.value.writeGeneration

    /** Auth-clear generation used to invalidate other owner-bound read projections. */
    fun writeGenerationFlow(): Flow<Long> =
        snapshot.map { it.writeGeneration }.distinctUntilChanged()

    fun factsFlow(owner: ProcessServerMembersOwner): Flow<Map<String, MemberPresenceUpdated>> =
        snapshot.map { it.partitions[owner].orEmpty() }.distinctUntilChanged()

    fun ownerSnapshotFlow(owner: ProcessServerMembersOwner): Flow<ProcessPresenceOwnerSnapshot> =
        snapshot
            .map { current ->
                ProcessPresenceOwnerSnapshot(
                    facts = current.partitions[owner].orEmpty(),
                    transport = current.transportEpochs[owner]
                )
            }
            .distinctUntilChanged()

    fun current(owner: ProcessServerMembersOwner): Map<String, MemberPresenceUpdated> =
        snapshot.value.partitions[owner].orEmpty()

    /** The value [ownerSnapshotFlow] emits right now (a page's first frame). */
    fun currentOwnerSnapshot(owner: ProcessServerMembersOwner): ProcessPresenceOwnerSnapshot =
        snapshot.value.let { current ->
            ProcessPresenceOwnerSnapshot(
                facts = current.partitions[owner].orEmpty(),
                transport = current.transportEpochs[owner]
            )
        }

    fun factFor(owner: ProcessServerMembersOwner, memberId: String?): MemberPresenceUpdated? =
        memberId?.trim()?.takeIf { it.isNotBlank() }?.let { current(owner)[it] }

    /**
     * Realtime facts (may carry serverSeq). Thread-safe via CAS update; the
     * [writeGeneration] the caller froze at binding time is re-checked inside
     * EVERY CAS attempt, so a write racing [clearAll] can never resurrect a
     * retired fact on retry (post-merge audit blocker on PR #1054).
     */
    fun apply(
        owner: ProcessServerMembersOwner,
        event: MemberPresenceUpdated,
        writeGeneration: Long,
        transport: ProcessPresenceTransportEpoch? = null
    ) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        val normalizedTransport = transport?.normalizedOrNull()
        if (transport != null && normalizedTransport == null) return
        while (true) {
            val current = snapshot.value
            if (writeGeneration != current.writeGeneration) return
            val currentTransport = current.transportEpochs[owner]
            val transportDisposition =
                currentTransport.presenceTransportDisposition(normalizedTransport)
            if (transportDisposition == PresenceTransportDisposition.Reject) return
            val transportAdvanced = transportDisposition == PresenceTransportDisposition.Advance
            val ownerFacts =
                if (transportAdvanced) emptyMap()
                else current.partitions[owner].orEmpty()
            val previous = ownerFacts[memberId]
            val hasCurrentProjection =
                !transportAdvanced &&
                    current.currentProjectionStamps[owner]?.containsKey(memberId) == true
            val isSeenEvent = event.isAgentSeenFact()
            if (isSeenEvent && previous != null && !event.advancesLastSeenAtOf(previous)) return
            if (
                // agent:seen carries no seq, so the seq gate below would drop every one that
                // follows an agent:activity. It is ordered by its own timestamp instead (web
                // `applyAgentSeen`: forward only) and keeps the previous fact's serverSeq.
                !isSeenEvent &&
                !event.shouldReplaceRegisteredPresenceFact(
                    previous = previous,
                    requiresStrictlyNewerSequence = hasCurrentProjection
                )
            ) {
                return
            }
            // agent:seen is a seq-less presence fact whose only payload is
            // lastSeenAt + derived Active presence; merge it onto the previous
            // fact so it never clobbers an agentStatus/statusLabel that came
            // from agent:activity. Same visible presence still stores the
            // newer canonical fact so its seq watermark advances. The
            // current-state mutation fence advances in the same CAS, making
            // an in-flight REST response stale.
            val mergedEvent =
                if (isSeenEvent && previous != null) {
                    event.copy(
                        // The previous fact's seq watermark survives, so an older agent:activity
                        // still cannot roll its status back after this merge.
                        serverSeq = previous.serverSeq,
                        agentStatus = event.agentStatus ?: previous.agentStatus,
                        statusLabel = event.statusLabel ?: previous.statusLabel,
                        detailKind = if (event.statusLabel != null) event.detailKind else previous.detailKind,
                        activityToolName =
                            if (event.statusLabel != null) event.activityToolName else previous.activityToolName,
                        wakeCrashLoop = if (event.statusLabel != null) event.wakeCrashLoop else previous.wakeCrashLoop
                    )
                } else {
                    event
                }
            val nextOwnerFacts = ownerFacts + (memberId to mergedEvent)
            val nextPartitions =
                current.partitions
                    .let { partitions -> if (transportAdvanced) partitions - owner else partitions }
                    .let { partitions -> partitions + (owner to nextOwnerFacts) }
            val nextTransportEpochs =
                if (!transportAdvanced || normalizedTransport == null) current.transportEpochs
                else current.transportEpochs + (owner to normalizedTransport)
            val currentProjectionStamps =
                if (transportAdvanced) current.currentProjectionStamps - owner
                else current.currentProjectionStamps
            val nextCurrentProjectionStamps =
                currentProjectionStamps.withoutPresenceMember(owner, memberId)
            val nextMutationVersions =
                current.memberMutationVersions.withAdvancedPresenceMutation(owner, memberId)
            if (
                snapshot.compareAndSet(
                    current,
                    current.copy(
                        partitions = nextPartitions,
                        transportEpochs = nextTransportEpochs,
                        currentProjectionStamps = nextCurrentProjectionStamps,
                        memberMutationVersions = nextMutationVersions,
                        rosterRefillPending =
                            if (transportAdvanced) current.rosterRefillPending + owner
                            else current.rosterRefillPending
                    )
                )
            ) {
                return
            }
        }
    }

    /**
     * Marks a freshly connected native transport as the current epoch and
     * drops only that owner's old seq baselines. The connection family is part
     * of the identity because auth refresh creates a new family whose native
     * epoch counter may legitimately restart at 1.
     */
    fun rebaselineTransport(
        owner: ProcessServerMembersOwner,
        writeGeneration: Long,
        transport: ProcessPresenceTransportEpoch
    ): ProcessPresenceTransportRebaselineResult {
        val normalizedTransport = transport.normalizedOrNull()
            ?: return ProcessPresenceTransportRebaselineResult.Rejected
        while (true) {
            val current = snapshot.value
            if (writeGeneration != current.writeGeneration) {
                return ProcessPresenceTransportRebaselineResult.Rejected
            }
            when (
                current.transportEpochs[owner]
                    .presenceTransportDisposition(normalizedTransport)
            ) {
                PresenceTransportDisposition.Reject ->
                    return ProcessPresenceTransportRebaselineResult.Rejected
                PresenceTransportDisposition.Same ->
                    return ProcessPresenceTransportRebaselineResult.Current
                PresenceTransportDisposition.Advance -> Unit
            }
            val next =
                current.copy(
                    partitions = current.partitions - owner,
                    transportEpochs = current.transportEpochs + (owner to normalizedTransport),
                    currentProjectionStamps = current.currentProjectionStamps - owner,
                    rosterRefillPending = current.rosterRefillPending + owner
                )
            if (snapshot.compareAndSet(current, next)) {
                return ProcessPresenceTransportRebaselineResult.Applied
            }
        }
    }

    /** A transport advance wiped [owner]'s facts and no authoritative roster read refilled them yet. */
    fun needsRosterRefill(owner: ProcessServerMembersOwner): Boolean =
        owner in snapshot.value.rosterRefillPending

    /**
     * An authoritative roster read captured by [request] was applied. Clears the pending refill
     * only when the request was captured on the owner's current transport and auth generation:
     * a read that started before a newer advance does not refill that newer one.
     */
    fun completeRosterRefill(request: ProcessPresenceRosterProjectionRequest) {
        while (true) {
            val current = snapshot.value
            if (request.owner !in current.rosterRefillPending) return
            if (request.writeGeneration != current.writeGeneration) return
            if (current.transportEpochs[request.owner] != request.transport) return
            if (
                snapshot.compareAndSet(
                    current,
                    current.copy(rosterRefillPending = current.rosterRefillPending - request.owner)
                )
            ) {
                return
            }
        }
    }

    fun captureHistoricalProjectionRequest(
        owner: ProcessServerMembersOwner,
        memberId: String?,
        writeGeneration: Long
    ): ProcessPresenceProjectionRequest? =
        captureProjectionRequest(
            owner = owner,
            memberId = memberId,
            writeGeneration = writeGeneration,
            source = ProcessPresenceProjectionSource.Historical
        )

    fun captureDetailProjectionRequest(
        owner: ProcessServerMembersOwner,
        memberId: String?,
        writeGeneration: Long
    ): ProcessPresenceProjectionRequest? =
        captureProjectionRequest(
            owner = owner,
            memberId = memberId,
            writeGeneration = writeGeneration,
            source = ProcessPresenceProjectionSource.DetailCurrent
        )

    private fun captureProjectionRequest(
        owner: ProcessServerMembersOwner,
        memberId: String?,
        writeGeneration: Long,
        source: ProcessPresenceProjectionSource
    ): ProcessPresenceProjectionRequest? {
        val key = memberId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        while (true) {
            val current = snapshot.value
            if (writeGeneration != current.writeGeneration) return null
            val requestVersion =
                if (source.isCurrent) current.nextProjectionRequestVersion + 1L else 0L
            val request =
                ProcessPresenceProjectionRequest(
                    owner = owner,
                    memberId = key,
                    writeGeneration = writeGeneration,
                    transport = current.transportEpochs[owner],
                    mutationVersion = current.memberMutationVersions[owner]?.get(key) ?: 0L,
                    source = source,
                    requestVersion = requestVersion,
                    baseRequestVersion =
                        current.currentProjectionStamps[owner]?.get(key)?.requestVersion
                )
            if (!source.isCurrent) return request
            if (
                snapshot.compareAndSet(
                    current,
                    current.copy(nextProjectionRequestVersion = requestVersion)
                )
            ) {
                return request
            }
        }
    }

    fun captureRosterProjectionRequest(
        owner: ProcessServerMembersOwner,
        writeGeneration: Long
    ): ProcessPresenceRosterProjectionRequest? {
        while (true) {
            val current = snapshot.value
            if (writeGeneration != current.writeGeneration) return null
            val requestVersion = current.nextProjectionRequestVersion + 1L
            val request =
                ProcessPresenceRosterProjectionRequest(
                    owner = owner,
                    writeGeneration = writeGeneration,
                    transport = current.transportEpochs[owner],
                    mutationVersions = current.memberMutationVersions[owner].orEmpty(),
                    requestVersion = requestVersion,
                    baseRequestVersions =
                        current.currentProjectionStamps[owner]
                            .orEmpty()
                            .mapValues { (_, stamp) -> stamp.requestVersion }
                )
            if (
                snapshot.compareAndSet(
                    current,
                    current.copy(nextProjectionRequestVersion = requestVersion)
                )
            ) {
                return request
            }
        }
    }

    /**
     * Seq-less projection fallback (e.g. trajectory snapshots): fills gaps
     * only — never displaces a seq-carrying realtime fact. Generation-gated
     * exactly like [apply].
     *
     * task #137(b): projections additionally carry freshness evidence — the
     * server-assigned trajectory timestamp of the entry they were projected
     * from. Within an owner/member the newest evidence seen is kept as a
     * watermark (same CAS domain as the facts): a projection with OLDER
     * evidence (a late, stale REST snapshot) is rejected, and a projection
     * with NO evidence never displaces an evidenced one — the REST-edge
     * mirror of the realtime seq gate. Equal evidence re-applies (>= rule,
     * same as serverSeq).
     */
    fun applyProjection(
        owner: ProcessServerMembersOwner,
        event: MemberPresenceUpdated,
        writeGeneration: Long,
        evidenceTimestamp: Long? = null
    ) {
        val request =
            captureHistoricalProjectionRequest(owner, event.memberId.value, writeGeneration)
                ?: return
        applyProjection(request, event, evidenceTimestamp)
    }

    fun applyProjection(
        request: ProcessPresenceProjectionRequest,
        event: MemberPresenceUpdated,
        evidenceTimestamp: Long? = null
    ) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank() || memberId != request.memberId) return
        while (true) {
            val current = snapshot.value
            if (request.writeGeneration != current.writeGeneration) return
            if (current.transportEpochs[request.owner] != request.transport) return
            val mutationVersion = current.memberMutationVersions[request.owner]?.get(memberId) ?: 0L
            if (mutationVersion != request.mutationVersion) return
            val ownerFacts = current.partitions[request.owner].orEmpty()
            val previous = ownerFacts[memberId]
            val watermark = current.projectionEvidence[request.owner]?.get(memberId)
            val currentProjectionStamp =
                current.currentProjectionStamps[request.owner]?.get(memberId)
            if (
                request.source.isCurrent &&
                currentProjectionStamp != null &&
                !request.canSupersede(currentProjectionStamp)
            ) {
                return
            }
            if (!request.source.isCurrent) {
                if (evidenceTimestamp == null && watermark != null) return
                if (evidenceTimestamp != null && watermark != null && evidenceTimestamp < watermark) return
            }
            val acceptsCurrentProjection =
                request.source.isCurrent &&
                    (request.source.replacesSequencedFact || previous?.serverSeq == null)
            val nextOwnerFacts =
                when {
                    // A fresh agent-detail summary is the server's current
                    // state, not trajectory history. Mutation/transport fences
                    // plus the captured source/request order make replacing an
                    // old seq fact safe. Within one transport the old seq
                    // remains a floor; after a confirmed transport advance the
                    // owner has already been cleared, so the new epoch begins
                    // seq-less.
                    acceptsCurrentProjection ->
                        ownerFacts +
                            (
                                memberId to
                                    event.copy(
                                        // A current-detail response may repair the visible status inside
                                        // the same transport, but it must not erase that transport's seq
                                        // floor: a delayed lower event could otherwise roll the repair
                                        // back. A confirmed transport advance clears the whole owner before
                                        // this path, so a restarted low sequence still begins from null.
                                        serverSeq = event.serverSeq ?: previous?.serverSeq
                                    )
                            )
                    request.source.isCurrent -> ownerFacts
                    // A seq-carrying realtime fact is projection-proof. The
                    // evidence watermark STILL advances below: it records
                    // what the REST edge has seen, so a stale snapshot cannot
                    // refill the slot if the fact is later evicted.
                    previous?.serverSeq != null || currentProjectionStamp != null -> ownerFacts
                    !event.shouldReplaceRegisteredPresenceFact(previous) -> ownerFacts
                    previous != null && event.hasSameVisibleRegisteredPresenceAs(previous) -> ownerFacts
                    else -> ownerFacts + (memberId to event)
                }
            // The watermark advances on every accepted newer evidence even
            // when the visible fact did not change — same doctrine as the
            // seq watermark advance (a stale snapshot must not pass later
            // just because the fresher one looked identical).
            val advanceEvidence =
                evidenceTimestamp != null && (watermark == null || evidenceTimestamp > watermark)
            val nextCurrentProjectionStamps =
                if (!acceptsCurrentProjection) {
                    current.currentProjectionStamps
                } else {
                    current.currentProjectionStamps +
                        (
                            request.owner to
                                (
                                    current.currentProjectionStamps[request.owner].orEmpty() +
                                        (
                                            memberId to
                                                ProcessPresenceCurrentProjectionStamp(
                                                    source = request.source,
                                                    requestVersion = request.requestVersion,
                                                    baseRequestVersion = request.baseRequestVersion
                                                )
                                        )
                                )
                        )
                }
            val currentProjectionStampChanged =
                nextCurrentProjectionStamps != current.currentProjectionStamps
            if (
                nextOwnerFacts === ownerFacts &&
                !advanceEvidence &&
                !currentProjectionStampChanged
            ) {
                return
            }
            val nextPartitions =
                if (nextOwnerFacts.isEmpty()) current.partitions - request.owner
                else current.partitions + (request.owner to nextOwnerFacts)
            val nextEvidence =
                if (!advanceEvidence) current.projectionEvidence
                else current.projectionEvidence +
                    (
                        request.owner to
                            (current.projectionEvidence[request.owner].orEmpty() + (memberId to evidenceTimestamp!!))
                    )
            if (
                snapshot.compareAndSet(
                    current,
                    current.copy(
                        partitions = nextPartitions,
                        projectionEvidence = nextEvidence,
                        currentProjectionStamps = nextCurrentProjectionStamps
                    )
                )
            ) {
                return
            }
        }
    }

    /** Deleted members must not keep a live fact — in THIS owner only. */
    fun evict(
        owner: ProcessServerMembersOwner,
        memberId: String?,
        writeGeneration: Long? = null
    ) {
        val key = memberId?.trim()?.takeIf { it.isNotBlank() } ?: return
        while (true) {
            val current = snapshot.value
            if (writeGeneration != null && writeGeneration != current.writeGeneration) return
            val ownerFacts = current.partitions[owner].orEmpty()
            val nextOwnerFacts = ownerFacts - key
            val nextPartitions =
                if (nextOwnerFacts.isEmpty()) current.partitions - owner
                else current.partitions + (owner to nextOwnerFacts)
            val next =
                current.copy(
                    partitions = nextPartitions,
                    currentProjectionStamps =
                        current.currentProjectionStamps.withoutPresenceMember(owner, key),
                    memberMutationVersions =
                        current.memberMutationVersions.withAdvancedPresenceMutation(owner, key)
                )
            if (snapshot.compareAndSet(current, next)) return
        }
    }

    /**
     * Auth transition (clear / re-login / account or environment switch).
     * Clearing and retiring the previous generation happen in ONE atomic
     * swap: any concurrent apply that loses the CAS race re-reads the new
     * generation on retry and rejects itself. Returns the new generation for
     * the caller to freeze into the next auth binding.
     *
     * The projection evidence watermarks SURVIVE the clear (task #137(b)):
     * they are epoch-millis server timestamps, so a stale pre-clear snapshot
     * landing after the clear is still recognized as stale for a recurring
     * owner instead of being accepted as a fresh gap-fill.
     */
    fun clearAll(reason: String): Long {
        while (true) {
            val current = snapshot.value
            val next =
                Snapshot(
                    writeGeneration = current.writeGeneration + 1L,
                    partitions = emptyMap(),
                    projectionEvidence = current.projectionEvidence,
                    transportEpochs = emptyMap(),
                    currentProjectionStamps = emptyMap(),
                    nextProjectionRequestVersion = current.nextProjectionRequestVersion,
                    memberMutationVersions = emptyMap()
                )
            if (snapshot.compareAndSet(current, next)) {
                return next.writeGeneration
            }
        }
    }

}

private enum class PresenceTransportDisposition {
    Advance,
    Same,
    Reject
}

private fun ProcessPresenceTransportEpoch?.presenceTransportDisposition(
    incoming: ProcessPresenceTransportEpoch?
): PresenceTransportDisposition =
    when {
        this == null && incoming == null -> PresenceTransportDisposition.Same
        this == null && incoming != null -> PresenceTransportDisposition.Advance
        this != null && incoming == null -> PresenceTransportDisposition.Reject
        this!!.connectionFamilyId != incoming!!.connectionFamilyId -> PresenceTransportDisposition.Advance
        incoming.epoch > epoch -> PresenceTransportDisposition.Advance
        incoming.epoch == epoch -> PresenceTransportDisposition.Same
        else -> PresenceTransportDisposition.Reject
    }

private fun Map<ProcessServerMembersOwner, Map<String, Long>>.withAdvancedPresenceMutation(
    owner: ProcessServerMembersOwner,
    memberId: String
): Map<ProcessServerMembersOwner, Map<String, Long>> {
    val ownerVersions = this[owner].orEmpty()
    val nextVersion = (ownerVersions[memberId] ?: 0L) + 1L
    return this + (owner to (ownerVersions + (memberId to nextVersion)))
}

private fun ProcessPresenceProjectionRequest.canSupersede(
    current: ProcessPresenceCurrentProjectionStamp
): Boolean =
    when {
        // This request started after the currently accepted projection, so it
        // is a causal refresh even when its source has lower authority.
        baseRequestVersion == current.requestVersion -> true
        // Same-source requests have an unambiguous process-monotonic order.
        source == current.source -> requestVersion >= current.requestVersion
        // Different sources compete by authority only when they were captured
        // from the same accepted base. A stronger but older sibling must not
        // resurrect after a later causal refresh has already advanced state.
        baseRequestVersion == current.baseRequestVersion ->
            source.authorityRank > current.source.authorityRank
        else -> false
    }

private fun <T> Map<ProcessServerMembersOwner, Map<String, T>>.withoutPresenceMember(
    owner: ProcessServerMembersOwner,
    memberId: String
): Map<ProcessServerMembersOwner, Map<String, T>> {
    val nextOwnerEntries = this[owner].orEmpty() - memberId
    return if (nextOwnerEntries.isEmpty()) this - owner else this + (owner to nextOwnerEntries)
}

/** An `agent:seen` fact (see `AgentSeenDto.toDomain`): seq-less, carrying only `lastSeenAt`. */
internal fun MemberPresenceUpdated.isAgentSeenFact(): Boolean =
    serverSeq == null && !lastSeenAt.isNullOrBlank() && statusLabel == null && agentStatus == null

/**
 * Whether this `agent:seen` moves [previous]'s `lastSeenAt` forward. An unparseable timestamp
 * never displaces a parseable one; with no previous timestamp any value advances.
 */
internal fun MemberPresenceUpdated.advancesLastSeenAtOf(previous: MemberPresenceUpdated): Boolean {
    val known = previous.lastSeenAt?.takeIf { it.isNotBlank() } ?: return true
    val incoming = parseExternalAgentTimestampOrNull(lastSeenAt) ?: return false
    val knownMillis = parseExternalAgentTimestampOrNull(known) ?: return true
    return incoming > knownMillis
}

internal fun MemberPresenceUpdated.shouldReplaceRegisteredPresenceFact(
    previous: MemberPresenceUpdated?,
    requiresStrictlyNewerSequence: Boolean = false
): Boolean =
    when {
        previous == null -> true
        requiresStrictlyNewerSequence && serverSeq != null && previous.serverSeq != null ->
            serverSeq > previous.serverSeq
        serverSeq != null && previous.serverSeq != null -> serverSeq >= previous.serverSeq
        serverSeq != null && previous.serverSeq == null -> true
        // task #137(a): the wire contract is "realtime presence always
        // carries serverSeq" (the server's only agent:activity emit site
        // stamps a per-agent monotonic serverSeq on every broadcast). A
        // seq-less write is therefore demoted to projection semantics: it
        // may fill a seq-less slot but NEVER displaces a seq-carrying fact
        // (the legacy else→true rule let a stale seq-less event roll a
        // newer seq-carrying fact backwards).
        else -> previous.serverSeq == null
    }

internal fun MemberPresenceUpdated.hasSameVisibleRegisteredPresenceAs(
    previous: MemberPresenceUpdated
): Boolean =
    presence == previous.presence &&
        agentStatus == previous.agentStatus &&
        statusLabel == previous.statusLabel &&
        detailKind == previous.detailKind &&
        activityToolName == previous.activityToolName &&
        wakeCrashLoop == previous.wakeCrashLoop &&
        kind == previous.kind &&
        lastSeenAt == previous.lastSeenAt
