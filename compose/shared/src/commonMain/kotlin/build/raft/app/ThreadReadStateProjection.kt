package build.raft.app

import build.raft.app.model.PerReceiverReadStateScope

/** Read pushes lower reply badges even when this page has not loaded the replies.
 * Kept by the visible store, bound to its existing account/environment owner and route.
 * A later hydration of the same reply range cannot resurrect a badge; additional replies
 * can still contribute unread. This is not a replacement for canonical refresh.
 */
internal class ThreadReadStateProjection {
    private data class Fact(val version: Long, val maxReadSeq: Long, val unreadCount: Int?, val replyCount: Int?)
    private var owner: String? = null
    private var route: ThreadRouteIdentity? = null
    private val facts = linkedMapOf<String, Fact>()

    fun observe(payload: KuiklyThreadScreenPayload, ownerScopeId: String, scopes: List<PerReceiverReadStateScope>) {
        val identity = payload.resolveThreadRouteIdentity()
        if (owner != ownerScopeId || route != identity) {
            facts.clear()
            owner = ownerScopeId
            route = identity
        }
        val parents = payload.messages + listOfNotNull(payload.originMessage)
        for (scope in scopes) {
            val maxReadSeq = scope.maxReadSeq ?: continue
            if (maxReadSeq < 0 || scope.readStateVersion <= 0) continue
            val previous = facts[scope.scopeId.value]
            if (previous != null && (scope.readStateVersion <= previous.version || maxReadSeq < previous.maxReadSeq)) continue
            val baseline = parents.filter { it.threadChannelId == scope.scopeId.value }.maxOfOrNull { it.replyCount }
            facts[scope.scopeId.value] = Fact(scope.readStateVersion, maxReadSeq, scope.unreadCount?.takeIf { it >= 0 }, baseline)
        }
        while (facts.size > 2048) facts.remove(facts.keys.first())
    }

    // A newer content event owns unread again; retain its read-version checkpoint only.
    fun contentChanged(threadChannelId: String) {
        val fact = facts[threadChannelId] ?: return
        facts[threadChannelId] = fact.copy(unreadCount = null)
    }

    fun project(payload: KuiklyThreadScreenPayload, ownerScopeId: String?): KuiklyThreadScreenPayload {
        if (ownerScopeId == null || ownerScopeId != owner || route != payload.resolveThreadRouteIdentity()) return payload
        fun KuiklyThreadMessageItem.project(): KuiklyThreadMessageItem {
            val fact = facts[threadChannelId] ?: return this
            val count = fact.unreadCount ?: return this
            val baseline = fact.replyCount ?: return this
            val upperBound = (count.toLong() + (replyCount.toLong() - baseline).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (unreadReplyCount <= upperBound) return this
            return copy(unreadReplyCount = upperBound,
                firstUnreadReplyMessageId = if (upperBound == 0) null else firstUnreadReplyMessageId)
        }
        val nextMessages = payload.messages.map { it.project() }
        val nextOrigin = payload.originMessage?.project()
        return if (nextMessages == payload.messages && nextOrigin == payload.originMessage) payload
        else payload.copy(messages = nextMessages, originMessage = nextOrigin)
    }
}
