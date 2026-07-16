package build.raft.app.data

import build.raft.app.model.MemberPresenceUpdated
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * task #136: the process-wide single source of truth for member presence
 * facts ("真正的 SSOT" — artin).
 *
 * ProcessServerMembersCore deliberately owns only roster snapshots; presence
 * used to live in each scope store's own state, which meant every shell held
 * its own copy of liveness truth — a machine-offline fact delivered to one
 * shell's realtime owner was invisible to another shell's Members surface.
 * This registry is created once per provider (next to the process members
 * core) and shared by every scope store and every realtime owner: owners
 * write facts here, stores read here. Member ids are globally unique, so the
 * registry keys by member id alone and needs no server/scope threading.
 *
 * Replacement rules are the ones the store always used: a fact with a
 * serverSeq never goes backwards; seq-less facts (projection fallbacks)
 * never displace a seq-carrying realtime fact.
 */
internal class ProcessMemberPresenceRegistry {

    private val _facts = MutableStateFlow<Map<String, MemberPresenceUpdated>>(emptyMap())

    val facts: StateFlow<Map<String, MemberPresenceUpdated>> = _facts

    fun current(): Map<String, MemberPresenceUpdated> = _facts.value

    fun factFor(memberId: String?): MemberPresenceUpdated? =
        memberId?.trim()?.takeIf { it.isNotBlank() }?.let { _facts.value[it] }

    /** Realtime facts (may carry serverSeq). Thread-safe via CAS update. */
    fun apply(event: MemberPresenceUpdated) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        update { facts ->
            val previous = facts[memberId]
            if (!event.shouldReplaceRegisteredPresenceFact(previous)) return@update facts
            facts + (memberId to event)
        }
    }

    /**
     * Seq-less projection fallback (e.g. trajectory snapshots): only fills a
     * gap — never displaces a seq-carrying realtime fact, and is dropped when
     * it would not change the visible presence.
     */
    fun applyProjection(event: MemberPresenceUpdated) {
        val memberId = event.memberId.value.trim()
        if (memberId.isBlank()) return
        update { facts ->
            val previous = facts[memberId]
            if (previous?.serverSeq != null) return@update facts
            if (!event.shouldReplaceRegisteredPresenceFact(previous)) return@update facts
            if (previous != null && event.hasSameVisibleRegisteredPresenceAs(previous)) return@update facts
            facts + (memberId to event)
        }
    }

    /** Deleted members must not keep a live fact around. */
    fun evict(memberId: String?) {
        val key = memberId?.trim()?.takeIf { it.isNotBlank() } ?: return
        update { facts -> if (facts.containsKey(key)) facts - key else facts }
    }

    private inline fun update(transform: (Map<String, MemberPresenceUpdated>) -> Map<String, MemberPresenceUpdated>) {
        while (true) {
            val current = _facts.value
            val next = transform(current)
            if (next === current || _facts.compareAndSet(current, next)) return
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
