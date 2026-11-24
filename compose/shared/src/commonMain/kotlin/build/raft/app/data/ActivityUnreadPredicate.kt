package build.raft.app.data

/*
 * Does a live message count as unread on its Activity row, for this viewer (#triage:#244)? A pure port
 * of slock `packages/shared/src/activityUnreadPredicate.ts` (`activityLiveUnread`), the rule web and the
 * server agree on, pinned by the `predicate_*` contract cases in
 * `src/commonTest/resources/contract-cases/activity-row/`. Its verdict is the `counts` / `mentionsViewer`
 * input of [reduceActivityRow]'s live event. SharedActivityStore judges live messages with it when
 * `inbox_row_position_v1` is on ([activityRowLiveVerdict]); off, it keeps its own
 * `InPlaceUnreadContext.unreadFor` (thread-row-only system rule, no mute input).
 *
 * Derived from the rules that serve the count (slock RisingWave `unread_count`, 075/070/078): not the
 * viewer's own message, not a system message the viewer caused, not a noise subtype; a muted
 * conversation counts nothing past the mute; a task conversation counts chat messages only.
 */

/** System subtypes never unread for anyone. Web `ACTIVITY_UNCOUNTED_SYSTEM_SUBTYPES`. */
internal val ActivityUncountedSystemSubtypes: Set<String> = setOf("channel.self_unfollow_thread", "task.deleted_summary")

/**
 * Receiver-independent unread activity predicate (slock #16667 `messageCountsAsUnreadActivity`):
 * false when `messages.counts_unread` is explicitly `false` (history-only / skip notice) or when
 * [systemSubtype] is one of the legacy uncounted noise subtypes. Absent `countsUnread` (`null`)
 * counts as true for older servers.
 */
internal fun messageCountsAsUnreadActivity(countsUnread: Boolean?, systemSubtype: String?): Boolean {
    if (countsUnread == false) return false
    return systemSubtype == null || systemSubtype !in ActivityUncountedSystemSubtypes
}

/** Who reads the Activity: [type] `user` or `agent`. Web `ActivityUnreadViewer`. */
internal data class ActivityUnreadViewer(val type: String, val id: String)

internal data class ActivityUnreadTarget(val type: String, val id: String)

/**
 * The message fields the rule reads, as `message:new` carries them. Web `ActivityUnreadMessage`.
 *
 * Web tells "no subtype" (`systemSubtype: null`) from "the event did not carry the system provenance"
 * (`systemSubtype` absent); here [carriesSystemProvenance] false is the absent case and [systemSubtype]
 * is then ignored.
 */
internal data class ActivityUnreadMessage(
    val senderType: String,
    val senderId: String,
    /** `chat`, `system`, or null (not sent). */
    val messageType: String? = null,
    val causalActorType: String? = null,
    val causalActorId: String? = null,
    val systemSubtype: String? = null,
    val carriesSystemProvenance: Boolean = true,
    /** `messages.counts_unread`; false: history only (`messageCountsAsUnreadActivity`). Absent on older events. */
    val countsUnread: Boolean? = null,
    val mentions: List<ActivityUnreadTarget> = emptyList(),
)

internal enum class ActivityUnreadRowKind { Channel, Dm, Thread, Task }

/** What the client knows about the row's conversation when the message arrives. Web `ActivityUnreadScope`. */
internal data class ActivityUnreadScope(
    val rowKind: ActivityUnreadRowKind,
    /** Thread and task rows: the viewer follows it. An unfollowed thread row counts nothing. */
    val threadFollowed: Boolean = true,
    /** Channel and DM rows: the viewer muted the conversation's Activity; a live message is past the mute (a mention still lights the @). */
    val activityMuted: Boolean = false,
    /** The client reads the message as it arrives (open at its bottom with the read saved, a queued read, a read hold). */
    val readOnArrival: Boolean = false,
)

/**
 * Web `ActivityUnreadVerdict`: [counts] null = cannot be judged from the event (re-read the row).
 * [moves]: it becomes the row's latest activity (position, preview, order). False for every message
 * past a channel or DM mute, a mention included (slock #15111): RisingWave keeps that row at its last
 * message before the mute. The position rule then leaves the row as it is, except that a mention
 * lights its @ (and moves a mention-only row, whose position is its highest mention).
 */
internal data class ActivityUnreadVerdict(val counts: Boolean?, val mention: Boolean, val moves: Boolean = true)

/** Web `activityLiveUnread`. */
internal fun activityLiveUnread(
    message: ActivityUnreadMessage,
    viewer: ActivityUnreadViewer,
    scope: ActivityUnreadScope,
): ActivityUnreadVerdict {
    val own = message.senderType == viewer.type && message.senderId == viewer.id
    val isThreadRow = scope.rowKind == ActivityUnreadRowKind.Thread || scope.rowKind == ActivityUnreadRowKind.Task
    // The mute decides whether the row moves, whoever sent it and whether it is read.
    val moves = isThreadRow || !scope.activityMuted
    if (own || scope.readOnArrival) return ActivityUnreadVerdict(counts = false, mention = false, moves = moves)
    // An unfollowed thread row: no count and no @, even for a mention of the viewer. Correct only because
    // a mention makes the server follow the thread for them and send the change that makes the client
    // re-read the row; mentions in unfollowed threads are not dropped.
    if (isThreadRow && !scope.threadFollowed) return ActivityUnreadVerdict(counts = false, mention = false, moves = moves)
    val mention = message.mentions.any { it.type == viewer.type && it.id == viewer.id }
    return ActivityUnreadVerdict(
        counts = countsForViewer(message, viewer, scope),
        mention = mention,
        moves = moves,
    )
}

private fun countsForViewer(message: ActivityUnreadMessage, viewer: ActivityUnreadViewer, scope: ActivityUnreadScope): Boolean? {
    if (scope.rowKind == ActivityUnreadRowKind.Task) return (message.messageType == null || message.messageType == "chat") && message.countsUnread != false
    if (scope.activityMuted && scope.rowKind != ActivityUnreadRowKind.Thread) return false
    if (message.countsUnread == false) return false
    if (message.messageType != "system") return true
    if (!message.carriesSystemProvenance) return null
    if (message.causalActorType == viewer.type && message.causalActorId == viewer.id) return false
    return messageCountsAsUnreadActivity(message.countsUnread, message.systemSubtype)
}
