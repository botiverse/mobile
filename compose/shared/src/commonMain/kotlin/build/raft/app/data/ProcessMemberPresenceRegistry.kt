package build.raft.app.data

import build.raft.app.model.MemberPresenceUpdated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * the process is currently signed into. Null between Cleared and the next
 * Authenticated — the write edge drops events in that window and read edges
 * rebind via flatMapLatest when it changes.
 */
internal data class ProcessPresenceAuthContext(
    val environmentId: String,
    val accountId: String
) {
    /** The owner partition this auth binding maps a server to (null-safe on blanks). */
    fun ownerFor(rawServerId: String): ProcessServerMembersOwner? {
        val environment = environmentId.trim().takeIf { it.isNotBlank() } ?: return null
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val serverId = rawServerId.trim().takeIf { it.isNotBlank() } ?: return null
        return ProcessServerMembersOwner(environment, account, serverId)
    }
}

internal class ProcessMemberPresenceRegistry {

    private val _factsByOwner =
        MutableStateFlow<Map<ProcessServerMembersOwner, Map<String, MemberPresenceUpdated>>>(emptyMap())

    val factsByOwner: StateFlow<Map<ProcessServerMembersOwner, Map<String, MemberPresenceUpdated>>> =
        _factsByOwner

    fun factsFlow(owner: ProcessServerMembersOwner): Flow<Map<String, MemberPresenceUpdated>> =
        _factsByOwner.map { it[owner].orEmpty() }.distinctUntilChanged()

    fun current(owner: ProcessServerMembersOwner): Map<String, MemberPresenceUpdated> =
        _factsByOwner.value[owner].orEmpty()

    fun factFor(owner: ProcessServerMembersOwner, memberId: String?): MemberPresenceUpdated? =
        memberId?.trim()?.takeIf { it.isNotBlank() }?.let { current(owner)[it] }

    /** Realtime facts (may carry serverSeq). Thread-safe via CAS update. */
    fun apply(owner: ProcessServerMembersOwner, event: MemberPresenceUpdated) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        updateOwner(owner) { facts ->
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
     * only — never displaces a seq-carrying realtime fact.
     */
    fun applyProjection(owner: ProcessServerMembersOwner, event: MemberPresenceUpdated) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        updateOwner(owner) { facts ->
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
        updateOwner(owner) { facts -> if (facts.containsKey(key)) facts - key else facts }
    }

    /** Auth transition (clear / re-login / account or environment switch). */
    fun clearAll(reason: String) {
        _factsByOwner.value = emptyMap()
    }

    private inline fun updateOwner(
        owner: ProcessServerMembersOwner,
        transform: (Map<String, MemberPresenceUpdated>) -> Map<String, MemberPresenceUpdated>
    ) {
        while (true) {
            val current = _factsByOwner.value
            val ownerFacts = current[owner].orEmpty()
            val nextOwnerFacts = transform(ownerFacts)
            if (nextOwnerFacts === ownerFacts) return
            val next =
                if (nextOwnerFacts.isEmpty()) current - owner
                else current + (owner to nextOwnerFacts)
            if (_factsByOwner.compareAndSet(current, next)) return
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
