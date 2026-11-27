package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.mentionsUser
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home's followed-thread unread, mirroring the web sidebar (botiverse/slock
 * `packages/web/src/store/socketBridge.ts` `threadUpdated`, `store/threadStore.ts`
 * `applyServerThreadUnread` / `clearThreadUnreadLocally`, `utils/threadUnreadByParent.ts`).
 *
 * The web keeps the followed-thread list as the live source and derives each channel's mark from it.
 * Home keeps the followed threads WITH unread replies from `channels/unread?summary=1`
 * ([HomeSnapshot.followedThreadUnread]) and updates them the same way:
 *  - a `thread:updated` reply into a known thread adds one unread and lights its @ when the reply
 *    mentions the user;
 *  - a reply into a thread Home does not know (a read followed thread, an auto-follow) reloads the
 *    summary through [HomeCoalescedRefresh], as the web reloads its followed list;
 *  - a read-state push lowers a thread to the server's count, and a local read clears it
 *    (`withConversationUnread(threadId, 0)`, web `clearThreadUnreadLocally`).
 */

/** Followed-thread mark of one parent channel (web `summarizeParentThreadUnread`). */
fun HomeSnapshot.parentThreadUnread(parentChannelId: ChannelId): HomeParentThreadUnread? =
    followedThreadUnread.foldThreadUnreadByParent()[parentChannelId]

@RaftCoreInternalApi enum class HomeThreadReplyDecision {
    /** Own reply, already counted, or the thread is open: nothing new to count. */
    Ignore,

    /** A known followed thread got a reply: one more unread (and maybe a mention). */
    Bump,

    /**
     * `slock #16493`: a Lounge thread's `thread:updated` never bumps unread (its own `message:new`
     * owns the count), but may still light `@` if it arrived first with a mention.
     */
    MentionOnly,

    /** The thread is not in the snapshot: reload the summary to learn whether it is followed. */
    Refresh,
}

/**
 * Where a thread hangs for live reply unread (`slock #16493` `loungeFollowListing.ts::threadParentPlace`):
 * - [Lounge]: a known Agent Lounge; replies are counted solely from their own `message:new`.
 * - [Unplaced]: a public channel in none of this server's lists (while Lounges are enabled and not a Guest);
 *   may be a Lounge the viewer holds nothing of yet, so unread replies trigger a coalesced summary reload.
 * - [Other]: ordinary channel (joined or not — both appear in `channels`), DM, private/joint channel, task,
 *   or Lounges disabled / Guest.
 */
@RaftCoreInternalApi enum class HomeThreadParentPlace {
    Lounge,
    Unplaced,
    Other,
}

@RaftCoreInternalApi fun HomeSnapshot.isKnownAgentLounge(channelId: String): Boolean {
    if (channelId.isBlank()) return false
    if (directMessages.any { it.lounge?.channelId?.value == channelId }) return true
    if (standaloneLounges.any { it.lounge.channelId.value == channelId }) return true
    return RaftAgentLounges.lounge(server.id.value, channelId) != null
}

/**
 * Whether Home already knows where [channelId] is listed (`slock #16474` `loungeFollowListing.ts::isPlaced`):
 * an ordinary channel or DM in the lists, or a Lounge with a DM/standalone summary or a known `listed` boolean.
 */
@RaftCoreInternalApi fun HomeSnapshot.isAgentLoungePlaced(channelId: String): Boolean {
    if (channelId.isBlank()) return true
    if (channels.any { it.channelId.value == channelId }) return true
    if (directMessages.any { it.channelId.value == channelId }) return true
    if (directMessages.any { it.lounge?.channelId?.value == channelId }) return true
    if (standaloneLounges.any { it.lounge.channelId.value == channelId }) return true
    return RaftAgentLounges.lounge(server.id.value, channelId)?.listed != null
}

/**
 * Whether a `GET /channels/dm?include=lounges` read could change which Lounges are listed
 * (`slock #16474` `loungeFollowListing.ts::listingCouldChange`): Lounges are enabled for this
 * non-Guest member AND the server speaks `listed` (last DM list carried `lounges`, or any DM,
 * standalone, or known Lounge carries an explicit `listed` boolean).
 */
@RaftCoreInternalApi fun HomeSnapshot.agentLoungeListingCouldChange(loungeFeatureEnabled: Boolean): Boolean {
    if (!agentLoungeEnabled(loungeFeatureEnabled, server.role)) return false
    if (dmListNamesLounges) return true
    if (directMessages.any { it.lounge?.listed != null }) return true
    if (standaloneLounges.any { it.lounge.listed != null }) return true
    return RaftAgentLounges.lounges.value[server.id.value]?.values?.any { it.listed != null } == true
}

@RaftCoreInternalApi fun HomeSnapshot.threadParentPlace(
    parentChannelId: String?,
    parentChannelKind: ConversationKind?,
    loungeFeatureEnabled: Boolean,
): HomeThreadParentPlace {
    val cleanId = parentChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return HomeThreadParentPlace.Other
    if (isKnownAgentLounge(cleanId)) return HomeThreadParentPlace.Lounge
    if (parentChannelKind != ConversationKind.Channel || !agentLoungeEnabled(loungeFeatureEnabled, server.role)) {
        return HomeThreadParentPlace.Other
    }
    val inLists =
        channels.any { it.channelId.value == cleanId } ||
            directMessages.any { it.channelId.value == cleanId }
    return if (inLists) HomeThreadParentPlace.Other else HomeThreadParentPlace.Unplaced
}

/**
 * Decides what a live `message:new` does to followed-thread unread for an Agent Lounge thread
 * (`slock #16493` `loungeThreadReply.ts::applyLoungeThreadReply`).
 *
 * Nobody except the agent is a member of a Lounge, so while the Lounge is closed only the reply's
 * own `message:new` reaches its followers (never `thread:updated`). That frame is the sole owner of
 * a Lounge thread's live unread count.
 */
@RaftCoreInternalApi fun HomeSnapshot.decideLoungeThreadReplyMessage(
    message: Message,
    openConversationIds: Set<String>,
    openParentMessageId: String? = null,
    loungeFeatureEnabled: Boolean = false,
    acceptedMaxReadSeq: (threadId: String) -> Long? = { null },
    pendingReadSeq: (threadId: String) -> Long? = { null },
): HomeThreadReplyDecision {
    val context = message.conversationContext ?: return HomeThreadReplyDecision.Ignore
    if (context.channelKind != ConversationKind.Thread) return HomeThreadReplyDecision.Ignore
    val threadId = (message.channelId ?: ChannelId(message.threadId.value)).value
    if (threadId.isBlank()) return HomeThreadReplyDecision.Ignore
    val followed = followedThreadUnread[threadId]
    val parentChannelId = followed?.parentChannelId?.value ?: context.parentChannelId?.value
    val place = threadParentPlace(parentChannelId, context.parentChannelKind, loungeFeatureEnabled)
    if (place == HomeThreadParentPlace.Other) return HomeThreadReplyDecision.Ignore
    if (message.author.id == currentMember.id) return HomeThreadReplyDecision.Ignore
    if (!message.countsUnread && !message.mentions.mentionsUser(currentMember.id.value)) {
        return HomeThreadReplyDecision.Ignore
    }
    if (
        threadId in openConversationIds ||
        (openParentMessageId != null && context.parentMessageId?.value == openParentMessageId)
    ) {
        return HomeThreadReplyDecision.Ignore
    }
    val seq = message.seq
    if (seq != null) {
        val accepted = acceptedMaxReadSeq(threadId)
        if (accepted != null && accepted >= seq) return HomeThreadReplyDecision.Ignore
        val pending = pendingReadSeq(threadId)
        if (pending != null && pending >= seq) return HomeThreadReplyDecision.Ignore
        val latest = unreadLatestActivitySeqs[threadId]
        if (latest != null && latest >= seq) return HomeThreadReplyDecision.Ignore
    }
    if (followed == null || place == HomeThreadParentPlace.Unplaced) return HomeThreadReplyDecision.Refresh
    return HomeThreadReplyDecision.Bump
}

/** Increments a known followed Lounge thread from its own `message:new` (`slock #16493`). */
@RaftCoreInternalApi fun HomeSnapshot.withLoungeThreadReplyMessage(message: Message): HomeSnapshot {
    val threadId = (message.channelId ?: ChannelId(message.threadId.value)).value
    val known = followedThreadUnread[threadId] ?: return this
    val next =
        known.copy(
            unreadCount = if (message.countsUnread) known.unreadCount + 1 else known.unreadCount,
            hasMention = known.hasMention || message.mentions.mentionsUser(currentMember.id.value),
        )
    val nextLatestSeqs =
        message.seq?.let { seq ->
            val existing = unreadLatestActivitySeqs[threadId]
            if (existing == null || seq > existing) unreadLatestActivitySeqs + (threadId to seq) else unreadLatestActivitySeqs
        } ?: unreadLatestActivitySeqs
    return copy(
        followedThreadUnread = followedThreadUnread + (threadId to next),
        unreadLatestActivitySeqs = nextLatestSeqs,
    )
}

@RaftCoreInternalApi fun HomeSnapshot.decideThreadReply(
    event: ThreadUpdated,
    openConversationId: String?
): HomeThreadReplyDecision =
    decideThreadReply(
        event = event,
        openConversationIds = setOfNotNull(openConversationId),
        openParentMessageId = null,
    )

@RaftCoreInternalApi fun HomeSnapshot.decideThreadReply(
    event: ThreadUpdated,
    openConversationIds: Set<String>,
    openParentMessageId: String? = null,
    acceptedMaxReadSeq: (threadId: String) -> Long? = { null },
    pendingReadSeq: (threadId: String) -> Long? = { null },
): HomeThreadReplyDecision {
    val me = currentMember.id.value
    // The server never counts the user's own reply as unread; nothing to bump or reload.
    if (event.latestReply?.senderId == me) return HomeThreadReplyDecision.Ignore
    // Notifications noise cut step 1a (slock #16667): history-only replies never add unread;
    // a mention of the viewer can still light `@`.
    val mentionsMe = event.latestReplyMentions.mentionsUser(me)
    if (!event.latestReplyCountsUnread && !mentionsMe) return HomeThreadReplyDecision.Ignore
    // A thread currently open on screen (main detail or side pane) is being read live:
    // neither bump its unread nor schedule a summary reload that could race its read receipt.
    val threadId = event.threadChannelId.value
    if (
        threadId in openConversationIds ||
        (openParentMessageId != null && event.parentMessageId.value == openParentMessageId)
    ) {
        return HomeThreadReplyDecision.Ignore
    }
    val replySeq = event.latestReply?.seq
    if (replySeq != null) {
        val accepted = acceptedMaxReadSeq(threadId)
        if (accepted != null && accepted >= replySeq) return HomeThreadReplyDecision.Ignore
        val pending = pendingReadSeq(threadId)
        if (pending != null && pending >= replySeq) return HomeThreadReplyDecision.Ignore
    }
    val known = followedThreadUnread[threadId] ?: return HomeThreadReplyDecision.Refresh
    if (!event.latestReplyCountsUnread) {
        return if (!known.hasMention && mentionsMe) {
            HomeThreadReplyDecision.MentionOnly
        } else {
            HomeThreadReplyDecision.Ignore
        }
    }
    // `slock #16493` (`countedByItsMessage`): a Lounge thread's own `message:new` owns its live
    // unread count so an open Lounge that receives both frames never double-counts.
    val parentId = known.parentChannelId.value.takeIf { it.isNotBlank() } ?: event.parentChannelId?.value
    if (parentId != null && isKnownAgentLounge(parentId)) {
        return if (!known.hasMention && mentionsMe) {
            HomeThreadReplyDecision.MentionOnly
        } else {
            HomeThreadReplyDecision.Ignore
        }
    }
    // `slock #16497` (`replyAlreadyCounted`): a seq already recorded on this thread is not counted twice.
    if (replySeq != null) {
        val latest = unreadLatestActivitySeqs[threadId]
        if (latest != null && latest >= replySeq) return HomeThreadReplyDecision.Ignore
    }
    return HomeThreadReplyDecision.Bump
}

/** Web `updateFollowedThread(..., { unreadCount: followed.unreadCount + 1, hasMention })`. */
@RaftCoreInternalApi fun HomeSnapshot.withFollowedThreadReply(event: ThreadUpdated): HomeSnapshot {
    val threadId = event.threadChannelId.value
    val known = followedThreadUnread[threadId] ?: return this
    val next =
        known.copy(
            unreadCount = if (event.latestReplyCountsUnread) known.unreadCount + 1 else known.unreadCount,
            hasMention = known.hasMention || event.latestReplyMentions.mentionsUser(currentMember.id.value)
        )
    val nextLatestSeqs =
        event.latestReply?.seq?.let { seq ->
            val existing = unreadLatestActivitySeqs[threadId]
            if (existing == null || seq > existing) unreadLatestActivitySeqs + (threadId to seq) else unreadLatestActivitySeqs
        } ?: unreadLatestActivitySeqs
    return copy(
        followedThreadUnread = followedThreadUnread + (threadId to next),
        unreadLatestActivitySeqs = nextLatestSeqs,
    )
}

/** Lights `@` on a known followed thread without bumping its unread count (`slock #16493`). */
@RaftCoreInternalApi fun HomeSnapshot.withFollowedThreadMentionOnly(event: ThreadUpdated): HomeSnapshot {
    val threadId = event.threadChannelId.value
    val known = followedThreadUnread[threadId] ?: return this
    if (known.hasMention || !event.latestReplyMentions.mentionsUser(currentMember.id.value)) return this
    return copy(followedThreadUnread = followedThreadUnread + (threadId to known.copy(hasMention = true)))
}

/** Marks known Lounge summaries in [channelIds] as `listed = true` locally (`slock #16474` `markLoungesListed`). */
@RaftCoreInternalApi fun HomeSnapshot.withLoungesMarkedListed(channelIds: Set<String>): HomeSnapshot {
    if (channelIds.isEmpty()) return this
    var changed = false
    val nextDirectMessages =
        directMessages.map { dm ->
            val lounge = dm.lounge
            if (lounge != null && lounge.channelId.value in channelIds && lounge.listed != true) {
                changed = true
                dm.copy(lounge = lounge.copy(listed = true))
            } else {
                dm
            }
        }
    val nextStandaloneLounges =
        standaloneLounges.map { row ->
            if (row.lounge.channelId.value in channelIds && row.lounge.listed != true) {
                changed = true
                row.copy(lounge = row.lounge.copy(listed = true))
            } else {
                row
            }
        }
    return if (changed) copy(directMessages = nextDirectMessages, standaloneLounges = nextStandaloneLounges) else this
}

/**
 * Protects local follows stamped after [requestSeq] from being overwritten with `listed = false`
 * by a `GET /channels/dm?include=lounges` answer that was requested earlier (`slock #16474`).
 */
@RaftCoreInternalApi fun HomeSnapshot.withNewerLocalLoungeFollows(serverId: String, requestSeq: Long): HomeSnapshot {
    if (requestSeq == Long.MAX_VALUE) return this
    var changed = false
    val nextDirectMessages =
        directMessages.map { dm ->
            val lounge = dm.lounge
            if (lounge != null && lounge.listed == false && RaftAgentLounges.followedSince(serverId, lounge.channelId.value, requestSeq)) {
                changed = true
                dm.copy(lounge = lounge.copy(listed = true))
            } else {
                dm
            }
        }
    val nextStandaloneLounges =
        standaloneLounges.map { row ->
            if (row.lounge.listed == false && RaftAgentLounges.followedSince(serverId, row.lounge.channelId.value, requestSeq)) {
                changed = true
                row.copy(lounge = row.lounge.copy(listed = true))
            } else {
                row
            }
        }
    return if (changed) copy(directMessages = nextDirectMessages, standaloneLounges = nextStandaloneLounges) else this
}

/**
 * Merges a lightweight `GET /channels/dm?include=lounges` refresh into this snapshot (`slock #16474` / `#16479`):
 * - Existing DM rows keep their live unread/mention/preview/read-cursor state while taking the fresh `lounge`
 *   summary (subject to `followedSince(requestSeq)` protection).
 * - When [parsed.namesLounges] is true, [HomeSnapshot.standaloneLounges] is replaced authoritatively; when false
 *   (`#16479`), existing [HomeSnapshot.standaloneLounges] are preserved untouched.
 */
@RaftCoreInternalApi fun HomeSnapshot.withDirectMessagesRefreshedForLoungeListing(
    parsed: ParsedHomeDirectMessagesRefresh,
    serverId: String,
    requestSeq: Long,
): HomeSnapshot {
    val existingById = directMessages.associateBy { it.channelId }
    val refreshedDms =
        parsed.directMessages.map { fresh ->
            val adjustedLounge =
                fresh.lounge?.let { lounge ->
                    if (lounge.listed == false && RaftAgentLounges.followedSince(serverId, lounge.channelId.value, requestSeq)) {
                        lounge.copy(listed = true)
                    } else {
                        lounge
                    }
                }
            val existing = existingById[fresh.channelId]
            if (existing != null) {
                val mergedLounge =
                    if (adjustedLounge != null && existing.lounge != null && adjustedLounge.channelId == existing.lounge.channelId) {
                        adjustedLounge.copy(
                            lastMessageId = existing.lounge.lastMessageId,
                            lastMessageSeq = existing.lounge.lastMessageSeq,
                            unreadCount =
                                if (adjustedLounge.listed != null) adjustedLounge.unreadCount
                                else existing.lounge.unreadCount,
                            mentionCount =
                                if (adjustedLounge.listed != null) adjustedLounge.mentionCount
                                else existing.lounge.mentionCount,
                        )
                    } else {
                        adjustedLounge
                    }
                existing.copy(
                    name = fresh.name.ifBlank { existing.name },
                    directMessagePeerAvatarWireValue = fresh.directMessagePeerAvatarWireValue ?: existing.directMessagePeerAvatarWireValue,
                    hasAnnouncement = fresh.hasAnnouncement,
                    lounge = mergedLounge,
                )
            } else {
                fresh.copy(lounge = adjustedLounge)
            }
        }
    val refreshedIds = refreshedDms.mapTo(HashSet()) { it.channelId }
    val localOnly = directMessages.filter { it.channelId !in refreshedIds }
    val nextDms = refreshedDms + localOnly
    val nextStandalone =
        if (parsed.namesLounges) {
            parsed.standaloneLounges.map { row ->
                if (row.lounge.listed == false && RaftAgentLounges.followedSince(serverId, row.lounge.channelId.value, requestSeq)) {
                    row.copy(lounge = row.lounge.copy(listed = true))
                } else {
                    row
                }
            }
        } else {
            standaloneLounges
        }
    val nextNamesLounges = dmListNamesLounges || parsed.namesLounges
    if (nextDms == directMessages && nextStandalone == standaloneLounges && nextNamesLounges == dmListNamesLounges) {
        return this
    }
    return copy(
        directMessages = nextDms,
        standaloneLounges = nextStandalone,
        dmListNamesLounges = nextNamesLounges,
    )
}

/** Web `applyServerThreadUnread`: only ever lowers a known thread to the server's count. */
@RaftCoreInternalApi fun HomeSnapshot.withFollowedThreadServerUnread(
    threadChannelId: ChannelId,
    serverUnreadCount: Int
): HomeSnapshot {
    val known = followedThreadUnread[threadChannelId.value] ?: return this
    if (known.unreadCount <= serverUnreadCount) return this
    return if (serverUnreadCount <= 0) {
        copy(followedThreadUnread = followedThreadUnread - threadChannelId.value)
    } else {
        copy(
            followedThreadUnread =
                followedThreadUnread + (threadChannelId.value to known.copy(unreadCount = serverUnreadCount))
        )
    }
}

/**
 * Projects a served `channels/unread?summary=1` followed-thread map against locally known read
 * state before applying it to Home (web `applyKnownReadStateProjectionsToFollowedThreads` /
 * `isServedThreadRowRead`, `#wg-mobile-sync:#257`), so a lagging read-model summary cannot briefly
 * resurrect a thread the user just read or unfollowed.
 */
@RaftCoreInternalApi fun projectFollowedThreadUnread(
    served: Map<String, HomeFollowedThreadUnread>,
    servedLatestActivitySeqs: Map<String, Long> = emptyMap(),
    servedReadFrontiers: Map<String, ReadReceiptFrontier> = emptyMap(),
    servedMarkedUnreadIds: Set<String> = emptySet(),
    pendingUnfollows: Set<String> = emptySet(),
    pendingReadSeqs: Map<ChannelId, Long> = emptyMap(),
    acknowledgedReadSeqs: Map<ChannelId, Long> = emptyMap(),
    currentFollowedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
    currentLatestActivitySeqs: Map<String, Long> = emptyMap(),
    acceptedCheckpoint: (threadId: String) -> PerReceiverReadStateCheckpoint? = { null },
): Map<String, HomeFollowedThreadUnread> {
    if (served.isEmpty()) return emptyMap()
    val filtered = LinkedHashMap<String, HomeFollowedThreadUnread>(served.size)
    for ((threadId, row) in served) {
        if (threadId in pendingUnfollows) continue
        val latestSeq = servedLatestActivitySeqs[threadId]
        val markedUnread = threadId in servedMarkedUnreadIds || servedReadFrontiers[threadId]?.markedUnread == true
        if (latestSeq != null) {
            val channelId = ChannelId(threadId)
            val pendingRead = pendingReadSeqs[channelId]
            if (pendingRead != null && pendingRead >= latestSeq) continue
            val acknowledgedRead = acknowledgedReadSeqs[channelId]
            if (!markedUnread && acknowledgedRead != null && acknowledgedRead >= latestSeq) continue
            val checkpoint = acceptedCheckpoint(threadId)
            val servedVersion = servedReadFrontiers[threadId]?.readStateVersion ?: 0L
            if (checkpoint != null && checkpoint.version > servedVersion && checkpoint.maxReadSeq >= latestSeq) {
                continue
            }
            if (
                !markedUnread &&
                threadId !in currentFollowedThreadUnread &&
                currentLatestActivitySeqs[threadId] == latestSeq
            ) {
                continue
            }
        }
        filtered[threadId] = row
    }
    return filtered
}

/** A reloaded summary replaces the followed threads wholesale (web `loadFollowedThreads`). */
@RaftCoreInternalApi fun HomeSnapshot.withFollowedThreadUnread(
    threads: Map<String, HomeFollowedThreadUnread>,
    latestActivitySeqs: Map<String, Long> = emptyMap(),
    readFrontiers: Map<String, ReadReceiptFrontier> = emptyMap(),
): HomeSnapshot {
    val nextLatestSeqs =
        if (latestActivitySeqs.isEmpty()) {
            unreadLatestActivitySeqs
        } else {
            val merged = LinkedHashMap(unreadLatestActivitySeqs)
            for ((scopeId, seq) in latestActivitySeqs) {
                val existing = merged[scopeId]
                if (existing == null || seq > existing) merged[scopeId] = seq
            }
            if (merged == unreadLatestActivitySeqs) unreadLatestActivitySeqs else merged
        }
    val nextFrontiers =
        if (readFrontiers.isEmpty()) {
            unreadReadFrontiers
        } else {
            val merged = LinkedHashMap(unreadReadFrontiers)
            for ((scopeId, frontier) in readFrontiers) {
                val existing = merged[scopeId]
                if (existing == null || (frontier.readStateVersion ?: -1L) >= (existing.readStateVersion ?: -1L)) {
                    merged[scopeId] = frontier
                }
            }
            if (merged == unreadReadFrontiers) unreadReadFrontiers else merged
        }
    if (threads == followedThreadUnread && nextLatestSeqs === unreadLatestActivitySeqs && nextFrontiers === unreadReadFrontiers) {
        return this
    }
    return copy(
        followedThreadUnread = threads,
        unreadLatestActivitySeqs = nextLatestSeqs,
        unreadReadFrontiers = nextFrontiers,
    )
}

/**
 * Where a thread's Unfollow is (web #11687 `markThreadUnfollowed`: unfollowing a thread clears its
 * unread at once; the server counts none for a thread the person doesn't follow).
 */
enum class HomeThreadUnfollowPhase {
    /** Unfollow chosen (sent, or waiting for its undo toast): the thread's unread leaves Home now. */
    Unfollowed,

    /** The server accepted it: nothing to put back any more. */
    Confirmed,

    /** Undo, or the request failed: the unread it had comes back. */
    Restored,
}

/** The optimistic half of an Unfollow: the thread's unread leaves its channel's mark. */
@RaftCoreInternalApi fun HomeSnapshot.withThreadUnfollowed(threadChannelId: String): HomeSnapshot =
    if (threadChannelId !in followedThreadUnread) this else copy(followedThreadUnread = followedThreadUnread - threadChannelId)

/**
 * Undo / failure of an Unfollow: puts back what [cleared] held, unless the thread has been listed
 * again since (a newer count stands).
 */
@RaftCoreInternalApi fun HomeSnapshot.withThreadUnfollowRestored(threadChannelId: String, cleared: HomeFollowedThreadUnread?): HomeSnapshot =
    if (cleared == null || threadChannelId in followedThreadUnread) {
        this
    } else {
        copy(followedThreadUnread = followedThreadUnread + (threadChannelId to cleared))
    }

/**
 * Sends an Unfollow through [unfollow] with its Home projection around it: [project] clears the
 * thread's unread first, then confirms it when the server accepts or restores it when it doesn't
 * (or the request is cancelled).
 */
@RaftCoreInternalApi suspend fun unfollowThreadProjectingHome(
    threadChannelId: ChannelId,
    project: (SharedHomeIntent.ProjectThreadUnfollow) -> Unit,
    unfollow: suspend (ChannelId) -> DataResult<ThreadActionResult>,
    parentChannelId: String? = null,
): DataResult<ThreadActionResult> {
    val threadId = threadChannelId.value
    project(SharedHomeIntent.ProjectThreadUnfollow(threadId, HomeThreadUnfollowPhase.Unfollowed, parentChannelId))
    var settled = false
    try {
        val result = unfollow(threadChannelId)
        val accepted = result is DataResult.Content || result is DataResult.Empty
        project(
            SharedHomeIntent.ProjectThreadUnfollow(
                threadId,
                if (accepted) HomeThreadUnfollowPhase.Confirmed else HomeThreadUnfollowPhase.Restored,
                parentChannelId,
            )
        )
        settled = true
        return result
    } finally {
        if (!settled) project(SharedHomeIntent.ProjectThreadUnfollow(threadId, HomeThreadUnfollowPhase.Restored, parentChannelId))
    }
}

/** Web `FOLLOWED_THREADS_REFRESH_DELAY_MS`. */
@RaftCoreInternalApi const val HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS: Long = 1_500L

/**
 * Web `createCoalescedRefresh`, single-flight: the first [request] opens a [delayMillis] window that
 * every later request joins; one [run] follows the window. A request that arrives while [run] is in
 * flight schedules exactly one more window after it, so a reply that raced the response is not lost.
 * Hidden-page deferral is not needed here: a hidden Home marks itself dirty and reloads everything.
 */
@RaftCoreInternalApi class HomeCoalescedRefresh(
    private val scope: CoroutineScope,
    private val delayMillis: Long = HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS,
    private val run: suspend () -> Unit
) {
    private val lock = SynchronizedObject()
    private var job: Job? = null
    private var requested = false

    fun request() {
        synchronized(lock) {
            requested = true
            if (job?.isActive == true) return
            job = scope.launch { drain() }
        }
    }

    fun cancel() {
        synchronized(lock) {
            requested = false
            job?.cancel()
            job = null
        }
    }

    private suspend fun drain() {
        while (true) {
            delay(delayMillis)
            val proceed =
                synchronized(lock) {
                    if (!requested) {
                        job = null
                        false
                    } else {
                        requested = false
                        true
                    }
                }
            if (!proceed) return
            run()
        }
    }
}
