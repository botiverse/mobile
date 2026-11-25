package build.raft.app

import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesScopeIdentity
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * The reply-count projection of [SharedThreadDataSource] (reply-count fact writes, `thread:updated`
 * and the sender's own reply landing on the parent conversation's cached payload), moved out of it
 * unchanged; the data source forwards to it. Takes the lock owned by [ThreadReplyCacheProvenanceOwner]
 * at the same points as before and holds no state of its own.
 */
internal class ThreadReplyCountProjection(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val payloadCache: SharedThreadPayloadStore,
    private val recordReplyWindows: (Collection<ThreadRepliesAcceptedProjection>) -> Unit,
) {
    private val replyCacheProvenanceLock: SynchronizedObject get() = provenanceOwner.replyCacheProvenanceLock
    private val replyCacheProvenance: StateFlow<ReplyCacheProvenance?> get() = provenanceOwner.replyCacheProvenanceState

    private fun currentReplyCacheProvenance(): ReplyCacheProvenance? = provenanceOwner.currentReplyCacheProvenance()

    /**
     * Linearizes the matching Store publication and reply-count fact write under
     * the same exact owner. `null` means owner rejection; `false` means the Store
     * declined a stale route; `true` means the owner-bound commit was admitted.
     */
    fun cacheReplyCountProjection(
        threadId: String,
        snapshot: ThreadReplySummariesSnapshot,
        observedAt: TimeSource.Monotonic.ValueTimeMark,
        serverId: String,
        principalId: String,
        expectedProvenance: ReplyCacheProvenance,
        commit: () -> Boolean,
    ): Boolean? {
        // The caller supplies the instant the canonical summaries request was
        // issued; the response reflects the server-side snapshot at request
        // time, so facts newer than that mark must not be overwritten.
        val facts =
            snapshot.threads.mapNotNull { summary ->
                val parentMessageId = summary.parentMessageId?.value ?: return@mapNotNull null
                parentMessageId to
                    SharedThreadReplyCountFact(
                        threadChannelId = (summary.threadChannelId ?: summary.channelId).value,
                        replyCount = summary.replyCount,
                        observedAt = observedAt
                    )
            }
        if (serverId != expectedProvenance.serverId || principalId != expectedProvenance.principalId) {
            return null
        }
        return synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != expectedProvenance) return@synchronized null
            if (!commit()) return@synchronized false
            if (facts.isNotEmpty()) {
                payloadCache.recordReplyCountFacts(
                    serverId = serverId,
                    threadId = threadId,
                    environmentId = expectedProvenance.environmentId,
                    principalId = principalId,
                    facts = facts
                )
            }
            true
        }
    }

    /**
     * Best-effort reply-count fact write for a realtime [ThreadUpdated] event.
     * Keeps the fact ledger fresh so a later canonical reprojection cannot
     * clobber a newer realtime reply count. Silently no-ops when the
     * provenance is unavailable or rotates mid-call.
     */
    fun recordReplyCountRealtimeFact(
        threadId: String,
        parentMessageId: String,
        fact: SharedThreadReplyCountFact,
    ) {
        val provenance = currentReplyCacheProvenance() ?: return
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value != provenance) return@synchronized
            payloadCache.recordReplyCountFacts(
                serverId = provenance.serverId,
                threadId = threadId,
                environmentId = provenance.environmentId,
                principalId = provenance.principalId,
                facts = listOf(parentMessageId to fact)
            )
        }
    }

    /**
     * Hands bc6e392e: a [ThreadUpdated] whose parent lives in another conversation than the open
     * page (the server sends DM / private parents to `user:<id>`, so it reaches whichever page is in
     * the foreground). The open page's own handling keys its facts by the open page, so the parent's
     * conversation kept its old reply count and preview until a refetch.
     *
     * This lands the event on the parent's own conversation, named by the reply-window envelope's
     * `parentScopeId` (server: `parentMsgForThread.channelId`): the reply-count fact is recorded
     * under it, and its cached payload is patched in place at the current freshness generation.
     * Returns `null` when the event names no other conversation of the current server (the open
     * page owns it), otherwise whether a cached payload was patched.
     */
    internal fun applyThreadUpdatedToParentConversation(
        event: ThreadUpdated,
        openPageThreadId: String?,
    ): Boolean? {
        val envelope = event.syncCoreReplyWindow ?: return null
        val parentScopeId = envelope.parentScopeId.trim().takeIf { it.isNotBlank() } ?: return null
        if (parentScopeId == openPageThreadId) return null
        if (envelope.rootMessageId != event.parentMessageId.value) return null
        val provenance = currentReplyCacheProvenance() ?: return null
        if (envelope.parentScopeServerId != provenance.serverId) return null
        val parentMessageId = event.parentMessageId.value
        val observedAt = TimeSource.Monotonic.markNow()
        var acceptedWindow: ThreadRepliesAcceptedProjection? = null
        val patched =
            synchronized(replyCacheProvenanceLock) {
                if (replyCacheProvenance.value != provenance) return@synchronized false
                payloadCache.recordReplyCountFacts(
                    serverId = provenance.serverId,
                    threadId = parentScopeId,
                    environmentId = provenance.environmentId,
                    principalId = provenance.principalId,
                    facts =
                        listOf(
                            parentMessageId to
                                SharedThreadReplyCountFact(
                                    threadChannelId = event.threadChannelId.value,
                                    replyCount = event.replyCount,
                                    observedAt = observedAt,
                                )
                        ),
                )
                val ownerScopeId = provenance.threadPayloadOwnerScopeId ?: return@synchronized false
                val generation = payloadCache.captureGeneration(provenance.serverId, parentScopeId, ownerScopeId)
                val cached =
                    payloadCache.get(
                        provenance.serverId,
                        parentScopeId,
                        provenance.environmentId,
                        provenance.principalId,
                        ownerScopeId,
                    ) ?: return@synchronized false
                val next = cached.withOffPageThreadUpdated(event)
                if (next == cached) return@synchronized false
                if (!payloadCache.putIfCurrent(generation, next, ownerScopeId)) return@synchronized false
                // Persist the folded preview for the next cold start only when it grew from a window
                // this conversation already showed: a lone frame must not replace a fuller stored one.
                val before = (cached.messages + listOfNotNull(cached.originMessage)).firstOrNull { it.id == parentMessageId }
                val after = (next.messages + listOfNotNull(next.originMessage)).firstOrNull { it.id == parentMessageId }
                val model = after?.inlineReplies
                if (model != null && before?.inlineReplies?.replies?.isNotEmpty() == true) {
                    acceptedWindow =
                        ThreadRepliesAcceptedProjection(
                            identity =
                                ThreadRepliesScopeIdentity(
                                    page =
                                        ThreadRepliesPageIdentity(
                                            serverId = provenance.serverId,
                                            principalId = provenance.principalId,
                                            parentScopeKind = envelope.parentScopeKind,
                                            parentScopeId = parentScopeId,
                                        ),
                                    parentMessageId = parentMessageId,
                                    threadChannelId = event.threadChannelId.value,
                                ),
                            model = model,
                            revision = 0L,
                        )
                }
                true
            }
        acceptedWindow?.takeIf { it.identity.isValid() }?.let { recordReplyWindows(listOf(it)) }
        return patched
    }

    /**
     * #proj-mobile #120 / #wg-mobile-sync #88: the sender's own reply, confirmed by the send's HTTP
     * answer, lands on the parent conversation's cached payload (reply count, inline preview) the way
     * web's thread panel updates the parent summary when a send resolves. Until now the parent only
     * moved on `thread:updated`; a joint channel's frame is fanned out per server projection to that
     * server's `channel:<local parent>` room, and mobile holds a socket for the current server only,
     * so a reply sent from a window whose server is not the socket's (or whose projection frame never
     * reached it) left the parent's reply count and preview stale.
     *
     * Keyed by the reply page's own parent channel under [provenance] (the server that page belongs to),
     * not by the realtime envelope. [replyCountAtLeast] is what the reply page itself proves; the
     * parent's count also grows by one when its inline window shows this reply is newer than anything
     * it has counted. A later `thread:updated` still overwrites with the server's total.
     */
    internal fun applySentReplyToParentConversation(
        provenance: ReplyCacheProvenance,
        parentScopeId: String,
        parentMessageId: String,
        threadChannelId: String,
        reply: ReplyPreview,
        replyCountAtLeast: Int,
    ): Boolean =
        synchronized(replyCacheProvenanceLock) {
            if (replyCacheProvenance.value?.isCurrentOrSuccessorForLocalSend(provenance) != true) {
                return@synchronized false
            }
            val current = replyCacheProvenance.value ?: return@synchronized false
            val ownerScopeId = current.threadPayloadOwnerScopeId ?: return@synchronized false
            val generation = payloadCache.captureGeneration(current.serverId, parentScopeId, ownerScopeId)
            val cached =
                payloadCache.get(
                    current.serverId,
                    parentScopeId,
                    current.environmentId,
                    current.principalId,
                    ownerScopeId,
                ) ?: return@synchronized false
            val next = cached.withSentReplyOnParent(parentMessageId, threadChannelId, reply, replyCountAtLeast)
            if (next == cached) return@synchronized false
            if (!payloadCache.putIfCurrent(generation, next, ownerScopeId)) return@synchronized false
            // The cache read re-applies reply-count facts; record the new total as the newest one so an
            // older summary/realtime fact cannot pull the parent back to its pre-send count.
            val nextParent = (next.messages + listOfNotNull(next.originMessage)).firstOrNull { it.id == parentMessageId }
            if (nextParent != null) {
                payloadCache.recordReplyCountFacts(
                    serverId = current.serverId,
                    threadId = parentScopeId,
                    environmentId = current.environmentId,
                    principalId = current.principalId,
                    facts =
                        listOf(
                            parentMessageId to
                                SharedThreadReplyCountFact(
                                    threadChannelId = nextParent.threadChannelId ?: threadChannelId,
                                    replyCount = nextParent.replyCount,
                                    observedAt = TimeSource.Monotonic.markNow(),
                                )
                        ),
                )
            }
            true
        }
}
