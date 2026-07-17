package build.raft.app.data

import build.raft.app.model.MemberPresenceUpdated
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
 * thrift); seq-less projection fallbacks never displace a seq-carrying
 * realtime fact.
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
    val authGeneration: Long
)

internal fun RealtimeGrantRecord.presenceAdmission(): RealtimePresenceAdmission =
    RealtimePresenceAdmission(
        serverId = serverId,
        principalId = principalId,
        authGeneration = authGeneration
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
            registry.apply(owner, event, context.writeGeneration)
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
 * one key-value read): no IO, no locks, no re-entry into authority APIs.
 */
internal fun processPresenceBindingTransition(
    presenceAuthContext: MutableStateFlow<ProcessPresenceAuthContext?>,
    registry: ProcessMemberPresenceRegistry,
    currentEnvironmentId: () -> String
): (RealtimeAuthRevision) -> Unit = { revision ->
    val principal = revision.session?.principalId?.trim()?.takeIf { it.isNotBlank() }
    val environmentId = currentEnvironmentId()
    val previous = presenceAuthContext.value
    if (principal == null || environmentId.isBlank()) {
        presenceAuthContext.value = null
        registry.clearAll(reason = "authority_session_cleared")
    } else if (
        previous != null &&
        previous.accountId == principal &&
        previous.environmentId == environmentId
    ) {
        presenceAuthContext.value = previous.copy(authorityGeneration = revision.generation)
    } else {
        val writeGeneration = registry.clearAll(reason = "authority_binding_changed")
        presenceAuthContext.value =
            ProcessPresenceAuthContext(
                environmentId = environmentId,
                accountId = principal,
                authorityGeneration = revision.generation,
                writeGeneration = writeGeneration
            )
    }
}

internal class ProcessMemberPresenceRegistry {

    private data class Snapshot(
        val writeGeneration: Long,
        val partitions: Map<ProcessServerMembersOwner, Map<String, MemberPresenceUpdated>>
    )

    private val snapshot = MutableStateFlow(Snapshot(writeGeneration = 0L, partitions = emptyMap()))

    /** Read-only view of every partition (test observability; no second store). */
    val factsByOwner: Map<ProcessServerMembersOwner, Map<String, MemberPresenceUpdated>>
        get() = snapshot.value.partitions

    /** The generation new auth bindings freeze into their write edges. */
    val writeGeneration: Long
        get() = snapshot.value.writeGeneration

    fun factsFlow(owner: ProcessServerMembersOwner): Flow<Map<String, MemberPresenceUpdated>> =
        snapshot.map { it.partitions[owner].orEmpty() }.distinctUntilChanged()

    fun current(owner: ProcessServerMembersOwner): Map<String, MemberPresenceUpdated> =
        snapshot.value.partitions[owner].orEmpty()

    fun factFor(owner: ProcessServerMembersOwner, memberId: String?): MemberPresenceUpdated? =
        memberId?.trim()?.takeIf { it.isNotBlank() }?.let { current(owner)[it] }

    /**
     * Realtime facts (may carry serverSeq). Thread-safe via CAS update; the
     * [writeGeneration] the caller froze at binding time is re-checked inside
     * EVERY CAS attempt, so a write racing [clearAll] can never resurrect a
     * retired fact on retry (post-merge audit blocker on PR #1054).
     */
    fun apply(owner: ProcessServerMembersOwner, event: MemberPresenceUpdated, writeGeneration: Long) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        updateOwner(owner, writeGeneration) { facts ->
            val previous = facts[memberId]
            if (!event.shouldReplaceRegisteredPresenceFact(previous)) return@updateOwner facts
            // Same visible presence: the CANONICAL FACT (and with it the seq
            // watermark) must still advance — keeping the old seq let an
            // out-of-order older event pass the >= gate later and roll status
            // backwards (QA blocker on task #136: seq1 online → seq3 online
            // kept watermark 1, then seq2 offline was accepted). Read edges
            // absorb the equal-visible emission via distinctUntilChanged, so
            // advancing costs no visible churn.
            facts + (memberId to event)
        }
    }

    /**
     * Seq-less projection fallback (e.g. trajectory snapshots): fills gaps
     * only — never displaces a seq-carrying realtime fact. Generation-gated
     * exactly like [apply].
     */
    fun applyProjection(owner: ProcessServerMembersOwner, event: MemberPresenceUpdated, writeGeneration: Long) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        updateOwner(owner, writeGeneration) { facts ->
            val previous = facts[memberId]
            if (previous?.serverSeq != null) return@updateOwner facts
            if (!event.shouldReplaceRegisteredPresenceFact(previous)) return@updateOwner facts
            if (previous != null && event.hasSameVisibleRegisteredPresenceAs(previous)) return@updateOwner facts
            facts + (memberId to event)
        }
    }

    /** Deleted members must not keep a live fact — in THIS owner only. */
    fun evict(owner: ProcessServerMembersOwner, memberId: String?) {
        val key = memberId?.trim()?.takeIf { it.isNotBlank() } ?: return
        // Removal-only: needs no generation gate — after a clear there is
        // nothing to evict, and eviction can never resurrect a fact.
        updateOwner(owner, writeGeneration = null) { facts ->
            if (facts.containsKey(key)) facts - key else facts
        }
    }

    /**
     * Auth transition (clear / re-login / account or environment switch).
     * Clearing and retiring the previous generation happen in ONE atomic
     * swap: any concurrent apply that loses the CAS race re-reads the new
     * generation on retry and rejects itself. Returns the new generation for
     * the caller to freeze into the next auth binding.
     */
    fun clearAll(reason: String): Long {
        while (true) {
            val current = snapshot.value
            val next = Snapshot(writeGeneration = current.writeGeneration + 1L, partitions = emptyMap())
            if (snapshot.compareAndSet(current, next)) {
                return next.writeGeneration
            }
        }
    }

    private inline fun updateOwner(
        owner: ProcessServerMembersOwner,
        writeGeneration: Long?,
        transform: (Map<String, MemberPresenceUpdated>) -> Map<String, MemberPresenceUpdated>
    ) {
        while (true) {
            val current = snapshot.value
            // The generation gate lives INSIDE the CAS loop on purpose: a
            // retry after losing the race to clearAll re-evaluates it against
            // the post-clear snapshot and drops the retired write.
            if (writeGeneration != null && writeGeneration != current.writeGeneration) return
            val ownerFacts = current.partitions[owner].orEmpty()
            val nextOwnerFacts = transform(ownerFacts)
            if (nextOwnerFacts === ownerFacts) return
            val nextPartitions =
                if (nextOwnerFacts.isEmpty()) current.partitions - owner
                else current.partitions + (owner to nextOwnerFacts)
            if (snapshot.compareAndSet(current, current.copy(partitions = nextPartitions))) return
        }
    }
}

internal fun MemberPresenceUpdated.shouldReplaceRegisteredPresenceFact(
    previous: MemberPresenceUpdated?
): Boolean =
    when {
        previous == null -> true
        serverSeq != null && previous.serverSeq != null -> serverSeq >= previous.serverSeq
        serverSeq != null && previous.serverSeq == null -> true
        else -> true
    }

internal fun MemberPresenceUpdated.hasSameVisibleRegisteredPresenceAs(
    previous: MemberPresenceUpdated
): Boolean =
    presence == previous.presence &&
        agentStatus == previous.agentStatus &&
        statusLabel == previous.statusLabel &&
        kind == previous.kind
