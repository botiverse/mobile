package build.raft.app.data

import build.raft.app.activityRowTimeValue
import build.raft.app.shownAuthorName
import build.raft.app.withKnownLatestDelegatedLabelsFrom
import build.raft.app.SharedThreadContentGeneration
import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.SharedThreadSummaryFact
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageSystemProvenance
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskSummary
import build.raft.app.model.mentionsUser
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.realtime.PerReceiverReadStateFactResult
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionDisposition
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import build.raft.app.network.sync.ReplyPreview
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val SharedActivityPageSize = 30
private const val SharedActivityRefreshPageLimit = 100
private const val SharedActivityRefreshContinuationSlack = 2
private const val SharedActivityRefreshMaxNoProgressPages = 2
private const val SharedActivityReplyPrewarmConcurrency = 2
private const val SharedActivityTailPrewarmDepthCap = 20

private data class ActivityRealtimeHighWater(
    val marker: MessageId?,
    val replyCount: Int,
    /**
     * The `message:new` seq the row was patched to. A thread row patched only by `thread:updated`
     * has none.
     */
    val seq: String? = null,
    /**
     * The patch stops holding over older served rows after this time (the stale-list bound,
     * [ActivityLocalMutationHoldMillis]): channel/DM rows (web `preserveNewerChannelActivity`) and
     * thread rows (web `ThreadActivityHighWater.until`, slock #14247).
     */
    val holdUntilMillis: Long? = null,
    /**
     * `mobile_inbox_inplace_v0`: the patch also moved the row's unread and mention, so a served row
     * still behind it keeps them (with the message fields) instead of dropping back.
     */
    val inPlaceUnread: Boolean = false
)

/** What a live message did to the Activity row it names (`mobile_inbox_inplace_v0`). */
private enum class RealtimeRowPatch {
    /** The list has no row for it: only a re-read can add one. */
    Absent,
    /** The row took it in place (preview, unread, mention): no re-read. */
    Patched,
    /** The row already shows it or something newer: nothing to do. */
    Unchanged,
    /** The row exists but this frame cannot be applied in place (no seq to order it): re-read. */
    NeedsRead,
}

/**
 * Who reads (`principalId`) and the conversation open on screen, which a live message there does
 * not make unread (web `liveActivityUnread`).
 */
private class InPlaceUnreadContext(val principalId: String, val activeConversationId: String?)

/**
 * Web `InboxLiveUnread` + `liveUnreadFields`: [counts] one more unread, [mention] the message
 * @-mentions the reader (a thread system line the server does not count still lights the @).
 */
private data class InPlaceLiveUnread(val counts: Boolean, val mention: Boolean)

/**
 * The followed-thread unread rule (slock infra/risingwave/sql/070-followed-thread-stats-v3.sql,
 * web `UNCOUNTED_THREAD_SYSTEM_SUBTYPES`): these system lines never count on a thread row.
 */
private val UncountedThreadSystemSubtypes = setOf("channel.self_unfollow_thread", "task.deleted_summary")

/**
 * Web `threadSystemMessageCounts` (slock #14247): a system message counts on a thread row as the
 * server counts it: not when the reader caused it, nor one of [UncountedThreadSystemSubtypes], and
 * never on a task conversation's row (its count is chat replies only). Without its provenance
 * ([provenance] null: the frame carried no `systemSubtype`, e.g. a `thread:updated` reply) it
 * cannot be judged and does not count; the re-read brings the server's count.
 */
private fun threadSystemMessageCounts(row: InboxItem, provenance: MessageSystemProvenance?, principalId: String): Boolean {
    if (row.isTaskConversation) return false
    if (provenance == null) return false
    if (provenance.systemSubtype != null && provenance.systemSubtype in UncountedThreadSystemSubtypes) return false
    return !(provenance.causalActorType == "user" && provenance.causalActorId == principalId)
}

/**
 * Web `liveActivityUnread` + `liveUnreadFields`: a live message counts unless it is the reader's
 * own, it lands in the conversation open on screen, or the row is an unfollowed thread (the server
 * counts none for it); a system line on a thread row counts only as [threadSystemMessageCounts]
 * says. Null: cannot tell here (a thread row or reply without a seq), the row is re-read.
 */
private fun InPlaceUnreadContext.unreadFor(
    row: InboxItem,
    message: Message,
    snapshot: InboxSnapshot,
    provenance: MessageSystemProvenance?
): InPlaceLiveUnread? {
    val thread = row.kind == InboxItemKind.Thread
    if (thread && (row.latestActivitySeq == null || message.seq == null)) return null
    val streamChannelId = (message.channelId ?: message.threadChannelId)?.value ?: return null
    val live =
        message.author.id.value != principalId &&
            !isActiveActivityConversation(streamChannelId, activeConversationId, snapshot) &&
            !row.isUnfollowedActivityThread
    val counts =
        live && !(thread && message.messageType == MessageType.System && !threadSystemMessageCounts(row, provenance, principalId))
    return InPlaceLiveUnread(counts = counts, mention = live && message.mentions.mentionsUser(principalId))
}

private fun InboxItem.withLiveUnread(live: InPlaceLiveUnread?, messageId: MessageId): InboxItem {
    if (live == null || (!live.counts && !live.mention)) return this
    return copy(
        unreadCount = if (live.counts) unreadCount + 1 else unreadCount,
        firstUnreadMessageId = if (live.counts && unreadCount == 0) messageId else firstUnreadMessageId,
        hasMention = hasMention || live.mention,
        firstMentionMessageId = if (live.mention && !hasMention) messageId else firstMentionMessageId
    )
}

private fun InboxSnapshot.threadRowIndex(streamChannelId: ChannelId): Int =
    items.indexOfFirst { item ->
        item.kind == InboxItemKind.Thread &&
            (item.threadChannelId == streamChannelId || item.channelId == streamChannelId)
    }

/** The `thread:updated` frame's latest reply as the `message:new` it stands for. */
private fun ReplyPreview.toActivityLiveMessage(event: ThreadUpdated): Message {
    val kind =
        when (senderType.trim().lowercase()) {
            "agent" -> MemberKind.Agent
            "system" -> MemberKind.System
            "external", "external_projection" -> MemberKind.External
            else -> MemberKind.Human
        }
    return Message(
        id = MessageId(messageId),
        threadId = ThreadId(event.threadChannelId.value),
        channelId = event.threadChannelId,
        seq = seq,
        author =
            MemberProfile(
                id = MemberId(senderId),
                displayName = senderDisplayName?.takeIf { it.isNotBlank() } ?: senderName,
                handle = senderName,
                kind = kind
            ),
        timestampLabel = createdAt,
        body = content,
        messageType = if (kind == MemberKind.System) MessageType.System else MessageType.Chat,
        mentions = event.latestReplyMentions
    )
}

/**
 * [served] (the server's order, newest first) with each [held] row (in the order shown now) put above
 * the first served row older than it: a held row is the newest only until newer activity reaches
 * other rows, so it never goes back above a newer one (web sorts every row by the time it shows,
 * `sortInboxRowsByTime`). A served row with no readable time counts as older, like the web lists it
 * last; a held row with no readable time stays on top, as it was held before. Held rows going to the
 * same place keep their shown order unless their times disagree.
 */
internal fun activityRowsWithHeldRowsAtTheirTime(served: List<InboxItem>, held: List<InboxItem>): List<InboxItem> {
    if (held.isEmpty()) return served
    val servedTimes = served.map { item -> parseRaftTimestampEpochMillisOrNull(item.activityRowTimeValue()) }
    val heldBySlot =
        held
            .map { item -> item to parseRaftTimestampEpochMillisOrNull(item.activityRowTimeValue()) }
            .groupBy { (_, time) ->
                if (time == null) {
                    0
                } else {
                    servedTimes.indexOfFirst { servedTime -> servedTime == null || servedTime < time }
                        .takeIf { it >= 0 } ?: served.size
                }
            }
            .mapValues { (_, rows) -> rows.sortedByDescending { (_, time) -> time ?: Long.MAX_VALUE }.map { it.first } }
    return buildList(served.size + held.size) {
        served.forEachIndexed { index, item ->
            heldBySlot[index]?.let(::addAll)
            add(item)
        }
        heldBySlot[served.size]?.let(::addAll)
    }
}

private data class ActivityRealtimeCatchUpTarget(val channelId: ChannelId, val seq: String)

/**
 * A canonical refresh read ([SharedActivityStore.loadCanonicalDepth]). [partialFailureCode] is set
 * when a page after the first failed: [result] then holds the rows fetched before it.
 */
private data class ActivityCanonicalDepthResult(
    val result: DataResult<InboxSnapshot>,
    val partialFailureCode: String? = null
)

/**
 * How long a pull joins a foreground refresh already in flight instead of restarting it. Older
 * loads may have hung across a background (see [ActivityRefreshCoalesceWindowMillis]); the pull
 * then supersedes them.
 */
internal const val ActivityPullJoinsInFlightRefreshWindowMillis: Long = 10_000L

/**
 * Ticket 67609860 (artin): a visible-route Refresh shows the Activity header indicator only when
 * the rows shown were last read at least this long ago (wall clock), like the app shell's
 * [build.raft.app.AppShellResumeStaleRefreshMillis] resume backstop.
 */
internal const val ActivityHeaderRefreshStaleAfterMillis: Long = 60_000L

private data class ActivityRealtimeReconcileResult(
    val items: List<InboxItem>,
    val preserveTotalUnreadFloor: Boolean = false
)

data class ActivityReplyThreadPrewarmKey(
    val serverId: String,
    val parentChannelId: String,
    val parentMessageId: String,
    val threadChannelId: String
)

data class ActivityReplyThreadPrewarmMarker(
    val key: ActivityReplyThreadPrewarmKey,
    // Owner-less content token: "did canonical content change since this marker was stamped".
    // The marker never selects a cached payload, so it carries no payload-cache owner.
    val contentGeneration: SharedThreadContentGeneration? = null
)

class SharedActivityStore internal constructor(
    private var authStore: RaftAuthSessionStore,
    private var inboxRepository: InboxRepository,
    private var readReceiptRepository: ReadReceiptRepository,
    private var conversationRepository: ConversationRepository,
    private var memberRepository: MemberRepository? = null,
    private var serverMembersStore: SharedServerMembersStore? = null,
    private var tasksDataStore: SharedTasksDataStore? = null,
    // task #399: optional; resolves DM-parent thread rows' peer name from the
    // Home DM list (channels.name is not a reliable peer projection for DMs).
    private var channelRepository: ChannelRepository? = null,
    private val perReceiverStateVersionStore: PerReceiverStateVersionStore,
    private var readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private var readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private var readStateV1Canary: ReadStateV1ProductionCanary? = null,
    private var prewarmReplyThread: suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    /**
     * True when the shared thread cache already holds this reply thread's content at its current
     * freshness generation. Only then may a queued prewarm be dropped because the user opened it.
     */
    private var replyThreadContentCached: suspend (ActivityReplyThreadPrewarmMarker) -> Boolean = { false },
    private var replyThreadFreshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
    // Shared with every visible thread page (owned by SharedThreadPayloadStore): while one is loading
    // its first content, reply prewarm does not START new loads.
    private var foregroundLoadGate: SharedForegroundLoadGate = SharedForegroundLoadGate(),
    private val tailPrewarmDepthCap: Int = SharedActivityTailPrewarmDepthCap,
    private var persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = emptyList(),
    /**
     * The Filter menu's last choice per server (slock #11383): a server the store binds to
     * or switches to starts on it, and every pick is written back. Null keeps no choice
     * (every server starts on All, as before).
     */
    private val filterPreferences: ActivityFilterPreferenceRepository? = null,
    /** Workspace Artifacts: Done / Undo / Read / Unfollow on object rows. Null: those writes refresh instead. */
    private val activityObjectsRepository: ActivityObjectsRepository? = null,
    onUnreadCountsChanged: () -> Unit = {},
    /**
     * Web #11687: how a thread's Unfollow from Activity ends, for Home's followed-thread unread
     * (the panel clears it when the person unfollows; this confirms or restores it).
     */
    onThreadUnfollowProjection: (SharedHomeIntent.ProjectThreadUnfollow) -> Unit = {},
    onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = {},
    onSummaryInvalidated: (ActivityUnreadSummaryInvalidation) -> Unit = {},
    private val scope: CoroutineScope,
    private var diagnosticsLogger: (String) -> Unit = {},
    /** Realtime-triggered refetch throttle window (leading + trailing); user loads are never throttled. */
    private val realtimeBackgroundRefreshIntervalMillis: Long = ActivityRealtimeBackgroundRefreshIntervalMillis,
    /**
     * The same window while the Activity surface is hidden ([SharedActivityIntent.SurfaceHidden]: another
     * tab, a pushed conversation). Null keeps [realtimeBackgroundRefreshIntervalMillis]. The process host
     * passes [ActivityHiddenRealtimeBackgroundRefreshIntervalMillis]: nobody sees the list then, and the
     * entry Refresh reads it anyway (feedback fb-52cc0499: 183 of 201 background re-reads of the 141 KB
     * first page ran while a conversation covered Activity).
     */
    private val hiddenRealtimeBackgroundRefreshIntervalMillis: Long? = null,
    /**
     * Incremental sync's reload ledger: the inbox read settles an Activity reload a sync change
     * asked for, so a failed one is read again by the next pull (#proj-mobile:#112). Null: no sync.
     */
    private val syncReloads: RaftSyncReloadLedger? = null,
    /** Monotonic clock for the throttle window and the local Done/read hold; injectable for virtual time. */
    private val nowMillis: () -> Long = activityMonotonicClock(),
    /**
     * Wall clock for [ActivityHeaderRefreshStaleAfterMillis]: the monotonic clock does not advance
     * while an iOS device sleeps, and the resume after a suspension is the refresh that must show.
     */
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** See [ActivityRefreshCoalesceWindowMillis]. */
    private val refreshCoalesceWindowMillis: Long = ActivityRefreshCoalesceWindowMillis,
    /** Task #164: the backoff of the stale-Activity recovery read; injectable for virtual time. */
    staleRecoveryDelayMillis: (attempt: Int, retryAfterMillis: Long?) -> Long =
        { attempt, retryAfter -> raftStaleRecoveryDelayMillis(attempt, retryAfter) },
    localMutationHoldMillis: Long = ActivityLocalMutationHoldMillis,
    /**
     * The process is in the foreground ([RaftStaleReadRecoverySignals.foreground]). The realtime
     * background reconcile never reads while it is false; it owes one read for the return.
     */
    private val appForeground: StateFlow<Boolean> = RaftStaleReadRecoverySignals.foreground,
    private val beforeRealtimeHighWaterFold: () -> Unit = {},
    private val onRealtimePatchOwnerEntered: () -> Unit = {},
    private val onReadReceiptPredictionOwnerEntered: () -> Unit = {},
    /**
     * `mobile_inbox_inplace_v0` for the server the store is bound to (Inbox refresh step 4,
     * #triage 6db2bd32). Off (the default): every read is today's.
     */
    private val inboxInPlaceEnabled: (serverId: String) -> Boolean = { false },
    /** See [ActivityInPlaceReconcileIntervalMillis]; injectable for virtual time. */
    private val inPlaceReconcileIntervalMillis: Long = ActivityInPlaceReconcileIntervalMillis,
    /**
     * `mobile_inbox_inplace_v0`: where each `activity:inplace_check` goes (web
     * `emitStateTransitionTrace`; [RaftClientTraceEvents.emitInPlaceCheck] in the app). Called under
     * the realtime owner lock: it must only queue.
     */
    private val inPlaceCheckSink: (serverId: String, check: ActivityInPlaceCheck) -> Unit = { _, _ -> }
) {
    private val _state = raftStoreState(SharedActivityState(), "SharedActivityStore")
    val state: StateFlow<SharedActivityState> = _state.asStateFlow()
    private val _readToggleNotices = MutableSharedFlow<RaftReadToggleNotice>(extraBufferCapacity = 4)

    /** One-shot notices of the row read toggles (the page shows them as a toast). */
    val readToggleNotices: SharedFlow<RaftReadToggleNotice> = _readToggleNotices.asSharedFlow()
    internal val presentationStore = SharedActivityPresentationStore()

    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L
    /** Task #164: the one pending recovery read while the shown Activity is stale. */
    private val staleRecovery =
        RaftStaleReadRecovery(
            scope = scope,
            label = "activity",
            diagnosticsLogger = { diagnosticsLogger(it) },
            delayMillis = staleRecoveryDelayMillis
        )
    /** Between [SharedActivityIntent.SurfaceHidden] and the next load the surface asks for. */
    // Written from the UI thread (dispatch), read by the reconcile loop under realtimeOwnerLock.
    @Volatile
    private var activitySurfaceHidden = false
    private var onUnreadCountsChanged: () -> Unit = onUnreadCountsChanged
    private var onThreadUnfollowProjection: (SharedHomeIntent.ProjectThreadUnfollow) -> Unit = onThreadUnfollowProjection
    private var onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = onSummaryFact
    private var onSummaryInvalidated: (ActivityUnreadSummaryInvalidation) -> Unit = onSummaryInvalidated
    // Local read / Done overlays: keep a confirmed mutation applied while the
    // lagging Activity view still serves the click-time activity marker.
    private val readOverlay = ActivityLocalMutationOverlay(nowMillis, localMutationHoldMillis)
    private val doneOverlay = ActivityLocalMutationOverlay(nowMillis, localMutationHoldMillis)
    private var lastResetLoadStartedAtMillis: Long? = null
    /** Wall time the shown rows were last read from the server (any refresh, background too). */
    private var lastRowsReadAtEpochMillis: Long? = null
    /** Start time of [loadJob] when it is a foreground reset load (not LoadMore / background). */
    private var foregroundResetLoadStartedAtMillis: Long? = null
    /**
     * The generation of the refresh whose first page is already shown while its later pages are
     * still being read (Hands f6c3f3a6). A LoadMore then waits for those pages instead of
     * cancelling them, and a pull restarts the read instead of joining it.
     */
    private var progressiveTailGeneration: Long? = null
    /** The generation of a non-pull refresh a pull joined: it shows the pull spinner until it lands. */
    private var pullJoinedGeneration: Long? = null
    /**
     * Local duplicate-request fence for Activity Follow/Unfollow actions.
     * The server remains the authority for the eventual boolean; this set only
     * prevents two taps from dispatching the same mutation concurrently.
     */
    private var pendingThreadFollowMutations: Set<String> = emptySet()
    /**
     * A Follow asked for while that row's Unfollow is still in flight (a swipe's Undo tapped right
     * away): run once the Unfollow settles, instead of being dropped by the fence.
     */
    private var deferredThreadFollows: Map<String, InboxItem> = emptyMap()
    private var readPredictionRevisions: Map<ChannelId, Long> = emptyMap()
    private var readPredictionItemKeys: Map<ChannelId, Set<String>> = emptyMap()
    /** Channel -> (registering prediction revision, pending read-overlay generations). */
    private var readPredictionHolds: Map<ChannelId, Pair<Long, Map<String, Long>>> = emptyMap()
    /**
     * Locally resolved mention-action rows. Never expire (pre-overlay mobile
     * behaviour): the resolution has no server write and no newer marker that
     * could retire it, so a timed hold would let the row come back.
     */
    private var resolvedMentionActionKeys: Map<ActivityOverlayOwner, Set<String>> = emptyMap()
    private val replyPrewarmSemaphore = Semaphore(SharedActivityReplyPrewarmConcurrency)
    // Activity intents can be dispatched from the Kuikly frame thread while app-scope prewarm
    // jobs complete on a coroutine worker. Keep every reply-prewarm collection behind one owner
    // lock so reset cannot iterate a HashMap while a job's finally block mutates it.
    private val replyPrewarmOwnerLock = SynchronizedObject()
    private var visibleReplyPrewarmMarkers: Map<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker> = emptyMap()
    private var tailReplyPrewarmMarkers: Map<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker> = emptyMap()
    private val completedReplyPrewarmGenerations =
        mutableMapOf<ActivityReplyThreadPrewarmKey, SharedThreadContentGeneration>()
    private val failedReplyPrewarmGenerations =
        mutableMapOf<ActivityReplyThreadPrewarmKey, SharedThreadContentGeneration>()
    private val replyPrewarmJobs = mutableMapOf<ActivityReplyThreadPrewarmKey, Job>()
    /** Prewarms past the semaphore and the foreground gate, i.e. actually loading. */
    private var replyPrewarmFetchingCount = 0
    private var tailPrewarmLoopJob: Job? = null
    private var tailInFlightJob: Job? = null
    private var tailDrainBarrier: Job? = null
    private var realtimeBackgroundReconcileJob: Job? = null
    /** Per channel: the in-flight readability check (single-flight) and the latest event generation. */
    private val channelAccessChecks = mutableMapOf<String, Job>()
    private val channelAccessGenerations = mutableMapOf<String, Long>()
    private var realtimeBackgroundReconcileDirty = false
    /**
     * #13126 (observability only): the asks folded into the pending background reconcile, first
     * trigger and count, sent with its realtime-fallback read. Set and cleared with
     * [realtimeBackgroundReconcileDirty] (guarded by realtimeOwnerLock).
     */
    private var realtimeBackgroundReconcileTrigger: InboxRefreshTriggerCount? = null
    /**
     * #triage 84d44d75: the newest patchable `message:new` (channel + seq) seen since the inbox last
     * answered at or past it (guarded by realtimeOwnerLock). One target for the whole inbox; many
     * events raise it, they never add reads (web inboxStore `inboxCatchUpTarget`, slock #11208).
     */
    private var realtimeCatchUpTarget: ActivityRealtimeCatchUpTarget? = null
    /** Re-reads already spent on [realtimeCatchUpTarget]; bounded by [ActivityRealtimeCatchUpRetryDelaysMillis]. */
    private var realtimeCatchUpRetries = 0
    /** The one pending catch-up re-read. */
    private var realtimeCatchUpRetryJob: Job? = null
    // task #399: DM-parent thread rows whose peer could not be resolved from the
    // last DM list fetch; retried only when a NEW unresolved id appears.
    private var unresolvedDmPeerChannelIds: Set<ChannelId> = emptySet()
    private var dmPeerNamesFetchInFlight = false
    private val realtimeHighWater = mutableMapOf<String, ActivityRealtimeHighWater>()

    /** Test hook: the row keys a realtime patch still holds over lagging served rows. */
    internal fun realtimeHighWaterKeysForTest(): Set<String> = synchronized(realtimeOwnerLock) { realtimeHighWater.keys.toSet() }
    // One owner for live row patches, version-ledger changes, background reconcile scheduling,
    // and canonical load folds.
    private val realtimeOwnerLock = SynchronizedObject()
    // Seqs the summary already counted from realtime (guarded by realtimeOwnerLock). Exact catch-up
    // (slock #10982) can deliver a message twice — live, then in a `sync:resume` batch — and the
    // summary path never patches rows, so the rows alone cannot tell. Seqs are server-global.
    private val realtimeCountedUnreadSeqs = linkedSetOf<Long>()
    /** Reset loads started so far (guarded by realtimeOwnerLock). */
    private var resetLoadsStarted = 0L
    /** A reconcile was asked for while the app was in the background: one read is owed for the return. */
    private var realtimeReconcileOwedOnForeground = false
    /**
     * After a return to the foreground: [resetLoadsStarted] then, and the earliest the owed read may
     * run. Until it runs, every reconcile asked for folds into it, and any reset load started since
     * the return (the page's own resume refresh) answers it: one read per return.
     */
    private var resumeFoldLoads: Long? = null
    private var resumeNotBeforeMillis: Long? = null
    /**
     * [resetLoadsStarted] at the latest reconcile ask made while the surface was hidden; null once a
     * visible ask came. A reset load started after it (the entry Refresh) answers those asks, the same
     * way [resumeFoldLoads] folds the asks made in the background into the return's read.
     */
    private var hiddenFoldLoads: Long? = null
    /**
     * `mobile_inbox_inplace_v0` (guarded by realtimeOwnerLock): [resetLoadsStarted] at the latest
     * live message patched in place, null when none is owed a re-read. A reset load started after it
     * covers it; otherwise [inPlaceReconcileJob] reads once, [inPlaceReconcileIntervalMillis] later.
     */
    private var inPlaceReconcileMark: Long? = null
    private var inPlaceReconcileJob: Job? = null
    /**
     * `mobile_inbox_inplace_v0` (guarded by realtimeOwnerLock): rows patched in place (or found
     * absent) by a live message, judged against the next first-window All answer.
     */
    private val inPlaceDriftCheck = ActivityInPlaceDriftCheck(nowMillis)

    init {
        scope.launch {
            var previous = appForeground.value
            appForeground.collect { foreground ->
                val returned = foreground && !previous
                previous = foreground
                if (returned) onAppForegroundReturned()
            }
        }
    }

    private fun onAppForegroundReturned() {
        val job =
            synchronized(realtimeOwnerLock) {
                if (!realtimeReconcileOwedOnForeground) return@synchronized null
                realtimeReconcileOwedOnForeground = false
                resumeFoldLoads = resetLoadsStarted
                resumeNotBeforeMillis = nowMillis() + realtimeBackgroundRefreshIntervalMillis
                scheduleRealtimeBackgroundReconcileLocked(trigger = null)
            }
        job?.start()
    }

    internal fun updateRuntimeIntegrations(
        authStore: RaftAuthSessionStore = this.authStore,
        inboxRepository: InboxRepository = this.inboxRepository,
        readReceiptRepository: ReadReceiptRepository = this.readReceiptRepository,
        conversationRepository: ConversationRepository = this.conversationRepository,
        memberRepository: MemberRepository? = this.memberRepository,
        serverMembersStore: SharedServerMembersStore? = this.serverMembersStore,
        tasksDataStore: SharedTasksDataStore? = this.tasksDataStore,
        channelRepository: ChannelRepository? = this.channelRepository,
        prewarmReplyThread: (suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit>)? = null,
        replyThreadContentCached: (suspend (ActivityReplyThreadPrewarmMarker) -> Boolean)? = null,
        replyThreadFreshnessLedger: SharedThreadFreshnessLedger? = null,
        foregroundLoadGate: SharedForegroundLoadGate? = null,
        persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = this.persistSlices,
        readReceiptCoordinator: ReadReceiptCoordinator? = this.readReceiptCoordinator,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = this.readReceiptOwnerFactory,
        readStateV1Canary: ReadStateV1ProductionCanary? = this.readStateV1Canary,
        onUnreadCountsChanged: (() -> Unit)? = null,
        onThreadUnfollowProjection: ((SharedHomeIntent.ProjectThreadUnfollow) -> Unit)? = null,
        onSummaryFact: ((ActivityUnreadSummaryFact) -> Unit)? = null,
        onSummaryInvalidated: ((ActivityUnreadSummaryInvalidation) -> Unit)? = null,
        diagnosticsLogger: ((String) -> Unit)? = null
    ) {
        this.authStore = authStore
        this.inboxRepository = inboxRepository
        this.readReceiptRepository = readReceiptRepository
        this.conversationRepository = conversationRepository
        this.memberRepository = memberRepository
        this.serverMembersStore = serverMembersStore
        this.tasksDataStore = tasksDataStore
        this.channelRepository = channelRepository
        if (prewarmReplyThread != null) {
            this.prewarmReplyThread = prewarmReplyThread
        }
        if (replyThreadContentCached != null) {
            this.replyThreadContentCached = replyThreadContentCached
        }
        if (replyThreadFreshnessLedger != null) {
            this.replyThreadFreshnessLedger = replyThreadFreshnessLedger
        }
        if (foregroundLoadGate != null) {
            this.foregroundLoadGate = foregroundLoadGate
        }
        this.persistSlices = persistSlices
        this.readReceiptCoordinator = readReceiptCoordinator
        this.readReceiptOwnerFactory = readReceiptOwnerFactory
        this.readStateV1Canary = readStateV1Canary
        if (onUnreadCountsChanged != null) {
            this.onUnreadCountsChanged = onUnreadCountsChanged
        }
        if (onThreadUnfollowProjection != null) {
            this.onThreadUnfollowProjection = onThreadUnfollowProjection
        }
        if (onSummaryFact != null) {
            this.onSummaryFact = onSummaryFact
        }
        if (onSummaryInvalidated != null) {
            this.onSummaryInvalidated = onSummaryInvalidated
        }
        if (diagnosticsLogger != null) {
            this.diagnosticsLogger = diagnosticsLogger
        }
    }

    fun dispatch(intent: SharedActivityIntent) {
        when (intent) {
            // On the store scope: the shell dispatches from its UI thread, and the switch writes state.
            is SharedActivityIntent.ServerSelected -> scope.launch { selectServer(intent) }
            SharedActivityIntent.LoadInitial -> {
                activitySurfaceHidden = false
                load(refresh = true, headerIndicator = true, intent = intent, fetchReason = InboxFetchReason.Initial)
            }
            SharedActivityIntent.Refresh -> {
                activitySurfaceHidden = false
                load(refresh = true, pullRefresh = false, headerIndicator = shownRowsAreOld(), intent = intent, fetchReason = InboxFetchReason.Visibility)
            }
            SharedActivityIntent.PullRefresh -> {
                activitySurfaceHidden = false
                // Pull-to-refresh is the user moving on: the rows read this visit leave with it.
                releaseRetainedReadRows(intent)
                load(refresh = true, pullRefresh = true, intent = intent, fetchReason = InboxFetchReason.Manual)
            }
            SharedActivityIntent.LoadMore -> load(refresh = false, intent = intent)
            SharedActivityIntent.MarkAllRead -> markAllRead()
            is SharedActivityIntent.SetFilter -> setFilter(intent.filter)
            is SharedActivityIntent.MarkDone ->
                if (intent.item.kind == InboxItemKind.Object) markObjectDone(intent.item) else markDone(intent.item)
            is SharedActivityIntent.UnfollowThread -> unfollowThread(intent)
            is SharedActivityIntent.FollowThread -> followThread(intent.item)
            is SharedActivityIntent.MarkRead ->
                if (intent.item.kind == InboxItemKind.Object) markObjectRead(intent.item) else markRead(intent.item)
            // An object row has no Mark as unread (the contract has no such route).
            is SharedActivityIntent.MarkUnread -> if (intent.item.kind != InboxItemKind.Object) markUnread(intent.item)
            is SharedActivityIntent.UndoObjectDone -> undoObjectDone(intent.item)
            is SharedActivityIntent.UnfollowObject -> unfollowObject(intent.item)
            is SharedActivityIntent.ProjectThreadUnfollow ->
                intent.item.threadChannelId?.value?.let { threadId ->
                    onThreadUnfollowProjection(SharedHomeIntent.ProjectThreadUnfollow(threadId, intent.phase))
                }
            is SharedActivityIntent.ApplyReadReceiptPrediction -> applyReadReceiptPrediction(intent.prediction)
            is SharedActivityIntent.ApplyReadReceiptFailure -> handleReadReceiptFailure(intent.request)
            is SharedActivityIntent.ApplyReadReceiptResult ->
                handleReadReceiptResult(intent.request, intent.succeeded)
            is SharedActivityIntent.PrewarmVisibleReplyThreads -> prewarmVisibleReplyThreads(intent.markers)
            is SharedActivityIntent.PrewarmActivityTail -> prewarmActivityTail(intent.orderedMarkers)
            SharedActivityIntent.DetachPrewarmViewport -> prewarmVisibleReplyThreads(emptySet())
            SharedActivityIntent.SurfaceHidden -> onSurfaceHidden()
            is SharedActivityIntent.RowOpened -> onRowOpened(intent)
            is SharedActivityIntent.ChannelAccessLost -> dropRetainedRows(intent) { item ->
                item.touchesChannel(intent.channelIds)
            }
            is SharedActivityIntent.ApplyRealtimeEvent -> {
                if (intent.event is PerReceiverReadStateChanged) {
                    scope.launch { applyPerReceiverReadStateChanged(intent.event) }
                } else {
                    applyRealtimeEventImmediately(intent)
                }
            }
            is SharedActivityIntent.ApplyRealtimeSummaryEvent ->
                scope.launch { acceptRealtime(intent) }
            is SharedActivityIntent.ApplyRemoteDone ->
                if (applyRemoteDone(intent.channelId, intent.doneFrontierSeq) == ActivityRemoteDoneOutcome.CannotTell) {
                    synchronized(realtimeOwnerLock) {
                        scheduleRealtimeBackgroundReconcileLocked(InboxRefreshTrigger.Other)
                    }.start()
                }
        }
    }

    /** Applies a realtime intent and waits until the reconcile / load it may have started has settled. */
    suspend fun acceptRealtime(intent: SharedActivityIntent) {
        applyLiveRealtime(intent)
        if (intent is SharedActivityIntent.ApplyRealtimeEvent) {
            synchronized(realtimeOwnerLock) { realtimeBackgroundReconcileJob }?.join()
            awaitCurrentLoadIdle()
        }
    }

    /**
     * Live realtime ingress for the AppShell domain sink. It applies the realtime patch and returns; a
     * background reconcile or Activity load keeps running in the store scope. The sink runs under the
     * realtime authority registry lock, so waiting for that network work here would hold every later
     * realtime event (feedback bc6e392e: up to 17 s).
     */
    suspend fun applyLiveRealtime(intent: SharedActivityIntent) {
        when (intent) {
            is SharedActivityIntent.ApplyRealtimeEvent -> applyRealtimeEvent(intent)
            is SharedActivityIntent.ApplyRealtimeSummaryEvent ->
                applyRealtimeSummaryEvent(intent.event, intent.activeConversationId)
            else -> error("applyLiveRealtime only accepts realtime intents")
        }
    }

    private fun onSurfaceHidden() {
        activitySurfaceHidden = true
        synchronized(realtimeOwnerLock) {
            foregroundResetLoadStartedAtMillis = null
        }
        // Leaving the Activity tab or route ends the visit: the rows read in it leave.
        releaseRetainedReadRows(SharedActivityIntent.SurfaceHidden)
    }

    private fun onRowOpened(intent: SharedActivityIntent.RowOpened) {
        synchronized(realtimeOwnerLock) {
            retainReadRowLocked(intent, intent.item)
        }
    }

    /**
     * Access loss (left or removed from a channel, channel deleted, server left or
     * deleted): the retained rows [matching] cannot stay as "read" rows. Each leaves the list and the
     * retained set at once. A row that was merely read keeps its place (see [withRetainedReadRows]).
     */
    private fun dropRetainedRows(intent: SharedActivityIntent, matching: (InboxItem) -> Boolean) {
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            if (current.retainedReadRowKeys.isEmpty()) return@synchronized
            val next = current.withRetainedRowsDropped(matching)
            if (next !== current) reduceState(intent, next)
        }
    }

    /** True when the realtime [event] means the current user lost access to the whole Server. */
    private fun RealtimeEvent.dropsServerAccess(current: SharedActivityState): Boolean =
        when (this) {
            is ServerMembershipRemoved -> serverId.value == current.ownerServerId
            is ServerDeleted -> serverId.value == current.ownerServerId
            is RealtimeRefreshRequired ->
                scope == RealtimeRefreshScope.ServerMembers &&
                    (reason == RealtimeSocketEvents.ServerMemberLeft || reason == RealtimeSocketEvents.ServerMemberRemoved) &&
                    memberId?.value != null && memberId?.value == current.ownerUserId &&
                    (serverId == null || serverId?.value == current.ownerServerId)
            else -> false
        }

    /**
     * `channel:updated` / `channel:members-updated` never mean access loss by themselves (web
     * re-reads the channel on them). For a channel with retained rows, re-read `GET /channels/:id`:
     * the server answers 404 only when the user can no longer read the channel (removed from a
     * private channel, deleted, wrong server). Only a 404 drops that channel's retained rows; a 500,
     * a network failure or any other answer is not access loss. One request per channel at a time;
     * a later event for the channel bumps its generation, so a result that was in flight over it is
     * stale and the check runs again (a stale 404 never drops rows after access was regained).
     */
    private fun checkRetainedChannelReadableLocked(channelId: String) {
        val current = _state.value
        if (!current.hasRetainedRowsFor(channelId)) return
        channelAccessGenerations[channelId] = (channelAccessGenerations[channelId] ?: 0L) + 1L
        if (channelAccessChecks[channelId]?.isActive == true) return
        val ownerServerId = current.ownerServerId
        val ownerUserId = current.ownerUserId
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (true) {
                        val generation = synchronized(realtimeOwnerLock) { channelAccessGenerations[channelId] ?: 0L }
                        val result = conversationRepository.getConversationDetail(ChannelId(channelId), DataRequestPriority.Background)
                        val lost = result is DataResult.Error && result.error.code == "http_404"
                        val rerun =
                            synchronized(realtimeOwnerLock) {
                                val latest = _state.value
                                when {
                                    latest.ownerServerId != ownerServerId || latest.ownerUserId != ownerUserId -> false
                                    (channelAccessGenerations[channelId] ?: 0L) != generation -> latest.hasRetainedRowsFor(channelId)
                                    else -> {
                                        if (lost) {
                                            dropRetainedRows(SharedActivityIntent.ChannelAccessLost(setOf(channelId))) { item ->
                                                item.touchesChannel(setOf(channelId))
                                            }
                                        }
                                        false
                                    }
                                }
                            }
                        if (!rerun) break
                    }
                } finally {
                    synchronized(realtimeOwnerLock) {
                        if (channelAccessChecks[channelId] === coroutineContext[Job]) channelAccessChecks.remove(channelId)
                    }
                }
            }
        channelAccessChecks[channelId] = job
        job.start()
    }

    private fun SharedActivityState.hasRetainedRowsFor(channelId: String): Boolean =
        retainedReadRowKeys.isNotEmpty() &&
            items.any { item -> item.key in retainedReadRowKeys && item.touchesChannel(setOf(channelId)) }

    private fun RealtimeEvent.channelUpdatedId(): String? =
        (this as? RealtimeRefreshRequired)
            ?.takeIf { it.scope == RealtimeRefreshScope.Home || it.scope == RealtimeRefreshScope.ChannelMembers }
            ?.takeIf {
                it.reason == RealtimeSocketEvents.ChannelUpdated || it.reason == RealtimeSocketEvents.ChannelMembersUpdated
            }
            ?.channelId?.value?.takeIf { it.isNotBlank() }

    private fun releaseRetainedReadRows(intent: SharedActivityIntent) {
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            if (current.retainedReadRowKeys.isEmpty()) return@synchronized
            reduceState(intent, current.withRetainedReadRowsReleased())
        }
    }

    /**
     * [item] joins the rows retained for this visit when this filter drops read rows and [item] is
     * still unread (it is about to be read). Rows retained earlier in the visit stay. Opening a row
     * that is already read, or already retained, changes nothing.
     */
    private fun retainReadRowLocked(intent: SharedActivityIntent, item: InboxItem) {
        val current = _state.value
        if (current.selectedFilter.keepsReadActivityRows() || !item.showsUnread) return
        if (item.key in current.retainedReadRowKeys) return
        reduceState(intent, current.copy(retainedReadRowKeys = current.retainedReadRowKeys + item.key))
    }

    fun clear() {
        val ownerServerId = synchronized(realtimeOwnerLock) {
            val owner = _state.value.ownerServerId
            loadGeneration += 1L
            loadJob?.cancel()
            loadJob = null
            foregroundResetLoadStartedAtMillis = null
            realtimeBackgroundReconcileJob?.cancel()
            realtimeBackgroundReconcileJob = null
            realtimeBackgroundReconcileDirty = false
            realtimeBackgroundReconcileTrigger = null
            realtimeReconcileOwedOnForeground = false
            resumeFoldLoads = null
            resumeNotBeforeMillis = null
            staleRecovery.reset()
            channelAccessChecks.values.forEach(Job::cancel)
            channelAccessChecks.clear()
            channelAccessGenerations.clear()
            realtimeHighWater.clear()
            resetRealtimeCatchUpLocked()
            resetInPlaceReconcileLocked()
            pendingThreadFollowMutations = emptySet()
            deferredThreadFollows = emptyMap()
            owner
        }
        ownerServerId?.let { serverId -> replyThreadFreshnessLedger.invalidateServer(serverId, cause = "activity_cleared") }
        clearReplyPrewarmState()
    }

    fun reset() {
        clear()
        presentationStore.clear()
        synchronized(realtimeOwnerLock) {
            readOverlay.clear()
            doneOverlay.clear()
            lastResetLoadStartedAtMillis = null
            lastRowsReadAtEpochMillis = null
            readPredictionRevisions = emptyMap()
            readPredictionItemKeys = emptyMap()
            readPredictionHolds = emptyMap()
            resolvedMentionActionKeys = emptyMap()
            pendingThreadFollowMutations = emptySet()
            deferredThreadFollows = emptyMap()
            // task #399 review: parked ids and the fetch flag belong to the
            // old owner/server; a stale park would suppress hydration for the
            // next session's DM rows.
            unresolvedDmPeerChannelIds = emptySet()
            dmPeerNamesFetchInFlight = false
            _state.value = SharedActivityState()
        }
    }

    private fun prewarmVisibleReplyThreads(markers: Set<ActivityReplyThreadPrewarmMarker>) {
        val nextVisible = markers.map(::stampReplyThreadPrewarmMarker).associateBy { it.key }
        val previousVisible =
            synchronized(replyPrewarmOwnerLock) {
                val previous = visibleReplyPrewarmMarkers
                visibleReplyPrewarmMarkers = nextVisible
                previous
            }

        val activeKeys =
            synchronized(replyPrewarmOwnerLock) { nextVisible.keys + tailReplyPrewarmMarkers.keys }
        cancelReplyPrewarmJobsNotIn(activeKeys)

        nextVisible.values.forEach { marker ->
            val key = marker.key
            val enteredViewport = key !in previousVisible
            val versionChanged = previousVisible[key]?.contentGeneration !== marker.contentGeneration
            val shouldLaunch =
                synchronized(replyPrewarmOwnerLock) {
                    if (completedReplyPrewarmGenerations[key] === marker.contentGeneration) return@synchronized false
                    if (replyPrewarmJobs[key]?.isCompleted == false) return@synchronized false
                    if (
                        !enteredViewport &&
                        !versionChanged &&
                        failedReplyPrewarmGenerations[key] === marker.contentGeneration
                    ) {
                        return@synchronized false
                    }
                    activeReplyPrewarmMarkerLocked(key)?.contentGeneration === marker.contentGeneration
                }
            if (!shouldLaunch) {
                return@forEach
            }
            launchReplyThreadPrewarm(marker)
        }
    }

    private fun launchReplyThreadPrewarm(marker: ActivityReplyThreadPrewarmMarker): Job {
        val key = marker.key
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val enqueuedAtMillis = foregroundLoadGate.nowMillis()
                try {
                    val result =
                        replyPrewarmSemaphore.withPermit {
                            awaitForegroundLoadIdle(key)
                            if (foregroundOpenOwnsLoad(marker, enqueuedAtMillis)) return@withPermit null
                            synchronized(replyPrewarmOwnerLock) { replyPrewarmFetchingCount += 1 }
                            try {
                                prewarmReplyThread(marker)
                            } finally {
                                synchronized(replyPrewarmOwnerLock) { replyPrewarmFetchingCount -= 1 }
                            }
                        }
                    if (activeReplyPrewarmMarker(key)?.contentGeneration !== marker.contentGeneration) return@launch
                    when (result) {
                        null -> {
                            // The user opened this thread while its prewarm was still queued and that
                            // open left the thread's current content in the shared cache, so a second
                            // identical request afterwards would be pure waste.
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { completedReplyPrewarmGenerations[key] = it }
                                    failedReplyPrewarmGenerations.remove(key)
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=skipped reason=foreground_open_owns_load " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Content -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { completedReplyPrewarmGenerations[key] = it }
                                    failedReplyPrewarmGenerations.remove(key)
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=success serverId=${key.serverId} " +
                                    "parentChannelId=${key.parentChannelId} parentMessageId=${key.parentMessageId} " +
                                    "threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Empty -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=empty reason=${result.reason.name} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Error -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=error code=${result.error.code} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.NoPermission -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=no_permission code=${result.blocker.code} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    synchronized(replyPrewarmOwnerLock) {
                        if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration === marker.contentGeneration) {
                            marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                        }
                    }
                    diagnosticsLogger(
                        "activity_reply_prewarm_result result=exception errorType=${throwable::class.simpleName.orEmpty()} " +
                            "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                            "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                    )
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    val retryMarker =
                        synchronized(replyPrewarmOwnerLock) {
                            if (replyPrewarmJobs[key] !== currentJob) return@synchronized null
                            replyPrewarmJobs.remove(key)
                            visibleReplyPrewarmMarkers[key]
                                ?.takeIf { latest -> latest.contentGeneration !== marker.contentGeneration }
                        }
                    retryMarker?.let(::launchReplyThreadPrewarm)
                }
            }
        val incumbent =
            synchronized(replyPrewarmOwnerLock) {
                if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                    return@synchronized null
                }
                replyPrewarmJobs[key]
                    ?.takeUnless(Job::isCompleted)
                    ?: run {
                        replyPrewarmJobs[key] = job
                        job
                    }
            }
        if (incumbent !== job) {
            job.cancel()
            return incumbent ?: job
        }
        diagnosticsLogger(
            "activity_reply_prewarm_start serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
        job.start()
        return job
    }

    /**
     * Foreground priority: a visible conversation / thread page that is loading its first content
     * must not compete with background prewarm. Prewarms already loading finish; this one waits
     * (bounded by the gate's lease timeout) before it starts.
     */
    private suspend fun awaitForegroundLoadIdle(key: ActivityReplyThreadPrewarmKey) {
        val gate = foregroundLoadGate
        val foregroundLoads = gate.activeCount()
        if (foregroundLoads == 0) return
        val startedAt = gate.nowMillis()
        diagnosticsLogger(
            "activity_reply_prewarm_deferred reason=foreground_open queued=${queuedReplyPrewarmCount()} " +
                "foregroundLoads=$foregroundLoads serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
        val outcome = gate.awaitIdle()
        val reason = if (outcome == SharedForegroundGateWait.TimedOut) "timeout" else "released"
        diagnosticsLogger(
            "activity_reply_prewarm_resumed reason=$reason waitedMs=${gate.nowMillis() - startedAt} " +
                "queued=${queuedReplyPrewarmCount()} serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
    }

    /**
     * Drop a queued prewarm only when BOTH hold: the user opened this thread after it was queued, and
     * the shared cache now has that thread's current content. An open that failed or closed before
     * loading leaves no current entry, so the prewarm runs normally.
     */
    private suspend fun foregroundOpenOwnsLoad(marker: ActivityReplyThreadPrewarmMarker, sinceMillis: Long): Boolean {
        val key = marker.key
        val opened =
            foregroundLoadGate.foregroundOpenedSince(
                sinceMillis = sinceMillis,
                serverId = key.serverId,
                threadId = key.threadChannelId,
                parentChannelId = key.parentChannelId,
                parentMessageId = key.parentMessageId,
            )
        return opened && replyThreadContentCached(marker)
    }

    /** Registered prewarms that have not started loading (waiting on the semaphore or the gate). */
    private fun queuedReplyPrewarmCount(): Int =
        synchronized(replyPrewarmOwnerLock) {
            (replyPrewarmJobs.values.count { job -> !job.isCompleted } - replyPrewarmFetchingCount).coerceAtLeast(0)
        }

    // Lower-priority click-progressive "tail": after row N is consumed, warm the remaining
    // eligible rows from N+1 top-first, reusing the exact
    // visible-prewarm machinery (same marker/key, same prewarmReplyThread, same replyPrewarmSemaphore,
    // same completed/active/failed dedup maps, same shared candidate cache). Visible keys are handled
    // by the visible flow and skipped here. A single serialized loop launches one tail item at a
    // time and awaits it, so
    // the tail never holds more than one permit — it can only run when the visible set is not
    // saturating the shared Semaphore, and total concurrency stays bounded by that Semaphore.
    private fun prewarmActivityTail(orderedMarkers: List<ActivityReplyThreadPrewarmMarker>) {
        val nextTail = LinkedHashMap<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker>()
        orderedMarkers.map(::stampReplyThreadPrewarmMarker).forEach { marker ->
            nextTail.getOrPut(marker.key) { marker }
        }
        synchronized(replyPrewarmOwnerLock) { tailReplyPrewarmMarkers = nextTail }

        tailPrewarmLoopJob?.cancel()
        tailPrewarmLoopJob = null
        // The tail is generation-owned: it holds at most ONE in-flight launched job at a time. That
        // job is launched in the store scope (a sibling of the loop), so cancelling the loop alone
        // would orphan it. On replan we therefore also cancel the still-alive tail job. A version
        // change keeps the same key in nextTail (so the key-based cancelReplyPrewarmJobsNotIn below
        // will NOT reap it), which is exactly the case that would otherwise leave A(v1) running
        // while A(v2) is skipped as "already active". Cancelling this Activity-level waiter only
        // unwinds the local await(); the shared app-scope canonical Deferred keeps running in the
        // pool for any other observers joined to it.
        //
        // Persistent drain barrier: rapid consecutive replans (v1 -> v2 -> v3 ...) can arrive while
        // an earlier cancelled job is still unwinding AND the superseded loop is still parked before
        // it published a fresh tailInFlightJob. A plain snapshot of tailInFlightJob would be null in
        // that window, letting the newest generation run alongside the still-draining job. Instead
        // we track the one job that must drain in tailDrainBarrier: the current in-flight job if any,
        // otherwise the barrier already in progress. Every generation cancels and re-joins that SAME
        // job before launching, so all replans serialize behind it and tail concurrency stays <= 1.
        val draining = tailInFlightJob ?: tailDrainBarrier
        draining?.cancel()
        tailDrainBarrier = draining
        tailInFlightJob = null
        val activeKeys =
            synchronized(replyPrewarmOwnerLock) { visibleReplyPrewarmMarkers.keys + nextTail.keys }
        cancelReplyPrewarmJobsNotIn(activeKeys)

        if (nextTail.isEmpty() && draining == null) return
        val ordered = nextTail.values.toList()
        tailPrewarmLoopJob =
            scope.launch {
                // Await the drain barrier (chained across generations) before evaluating this order,
                // so tail concurrency never exceeds one and a re-versioned item is re-launched (not
                // skipped because its old-version job still looked "active"). Clear the barrier by
                // identity only after it has actually completed.
                draining?.join()
                if (tailDrainBarrier === draining) tailDrainBarrier = null
                runReplyPrewarmTail(ordered)
            }
    }

    private suspend fun runReplyPrewarmTail(orderedMarkers: List<ActivityReplyThreadPrewarmMarker>) {
        var launched = 0
        for (marker in orderedMarkers) {
            if (launched >= tailPrewarmDepthCap) return
            if (!shouldLaunchTailPrewarm(marker)) continue
            launched++
            val job = launchReplyThreadPrewarm(marker)
            tailInFlightJob = job
            job.join()
            if (tailInFlightJob === job) tailInFlightJob = null
        }
    }

    private fun shouldLaunchTailPrewarm(marker: ActivityReplyThreadPrewarmMarker): Boolean {
        return synchronized(replyPrewarmOwnerLock) {
            val key = marker.key
            if (key in visibleReplyPrewarmMarkers) return@synchronized false
            if (completedReplyPrewarmGenerations[key] === marker.contentGeneration) return@synchronized false
            if (replyPrewarmJobs[key]?.isCompleted == false) return@synchronized false
            if (failedReplyPrewarmGenerations[key] === marker.contentGeneration) return@synchronized false
            activeReplyPrewarmMarkerLocked(key)?.contentGeneration === marker.contentGeneration
        }
    }

    private fun activeReplyPrewarmMarker(
        key: ActivityReplyThreadPrewarmKey
    ): ActivityReplyThreadPrewarmMarker? =
        synchronized(replyPrewarmOwnerLock) { activeReplyPrewarmMarkerLocked(key) }

    private fun activeReplyPrewarmMarkerLocked(
        key: ActivityReplyThreadPrewarmKey
    ): ActivityReplyThreadPrewarmMarker? =
        visibleReplyPrewarmMarkers[key] ?: tailReplyPrewarmMarkers[key]

    private fun stampReplyThreadPrewarmMarker(
        marker: ActivityReplyThreadPrewarmMarker
    ): ActivityReplyThreadPrewarmMarker {
        _state.value.items
            .firstOrNull { item -> item.replyThreadPrewarmKey(marker.key.serverId) == marker.key }
            ?.let { item ->
                replyThreadFreshnessLedger.acceptSummary(
                    marker.key.serverId,
                    marker.key.threadChannelId,
                    item.replyThreadSummaryFact()
                )
            }
        val generation = replyThreadFreshnessLedger.captureContent(marker.key.serverId, marker.key.threadChannelId)
        return marker.copy(contentGeneration = generation)
    }

    private fun advanceReplyThreadContent(existing: InboxItem, patched: InboxItem, realtimeMessageId: String?) {
        val serverId = _state.value.ownerServerId ?: return
        val key = existing.replyThreadPrewarmKey(serverId) ?: return
        replyThreadFreshnessLedger.acceptSummary(
            key.serverId,
            key.threadChannelId,
            patched.replyThreadSummaryFact(),
            realtimeMessageId = realtimeMessageId,
        )
    }

    private fun acceptMessageUpdatedFreshness(message: Message) {
        val serverId = _state.value.ownerServerId ?: return
        val candidateThreadIds =
            setOfNotNull(
                message.channelId?.value,
                message.threadChannelId?.value,
                message.threadId.value
            )
        val item =
            _state.value.items.firstOrNull { candidate ->
                candidate.kind == InboxItemKind.Thread &&
                    candidateThreadIds.any { threadId ->
                        candidate.threadChannelId?.value == threadId || candidate.channelId.value == threadId
                    }
            } ?: return
        val key = item.replyThreadPrewarmKey(serverId) ?: return
        replyThreadFreshnessLedger.acceptMessageMutation(key.serverId, key.threadChannelId, message)
    }

    private fun cancelReplyPrewarmJobsNotIn(activeKeys: Set<ActivityReplyThreadPrewarmKey>) {
        val jobsToCancel =
            synchronized(replyPrewarmOwnerLock) {
                replyPrewarmJobs
                    .filterKeys { key -> key !in activeKeys }
                    .values
                    .toList()
            }
        jobsToCancel.forEach(Job::cancel)
    }

    private fun clearReplyPrewarmState() {
        tailPrewarmLoopJob?.cancel()
        tailPrewarmLoopJob = null
        tailInFlightJob?.cancel()
        tailInFlightJob = null
        tailDrainBarrier?.cancel()
        tailDrainBarrier = null
        val jobsToCancel =
            synchronized(replyPrewarmOwnerLock) {
                val jobs = replyPrewarmJobs.values.toList()
                replyPrewarmJobs.clear()
                visibleReplyPrewarmMarkers = emptyMap()
                tailReplyPrewarmMarkers = emptyMap()
                completedReplyPrewarmGenerations.clear()
                failedReplyPrewarmGenerations.clear()
                jobs
            }
        jobsToCancel.forEach(Job::cancel)
    }

    private fun rememberedFilter(serverId: String): InboxFilter =
        filterPreferences?.read(serverId) ?: InboxFilter.All

    private fun setFilter(filter: InboxFilter) {
        // The pick is this server's filter from now on (web writeActivityFilterChoice).
        filterPreferences?.write(_state.value.ownerServerId, filter)
        val changed =
            synchronized(realtimeOwnerLock) {
                if (_state.value.selectedFilter == filter) return@synchronized false
                realtimeHighWater.clear()
                // Switching filter releases the rows read this visit: they leave the filter they
                // were read in, and that filter's cached page too.
                val released = _state.value.withRetainedReadRowsReleased()
                val leftFilterCache =
                    released.snapshot?.let { left -> released.filterSnapshots + (released.selectedFilter to left) }
                        ?: released.filterSnapshots
                val cachedSnapshot = leftFilterCache[filter]
                _state.value =
                    released.copy(
                        filterSnapshots = leftFilterCache,
                        selectedFilter = filter,
                        snapshot = cachedSnapshot,
                        loaded = cachedSnapshot != null,
                        loading = cachedSnapshot == null,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        loadMoreErrorCode = null
                    )
                true
            }
        if (!changed) return
        load(refresh = true, headerIndicator = true, intent = SharedActivityIntent.SetFilter(filter), fetchReason = InboxFetchReason.Manual)
    }

    /**
     * The shell switched to [SharedActivityIntent.ServerSelected.serverId] (Hands 20db9f61).
     *
     * One store serves the shell across server switches and the panel's mount-time LoadInitial
     * does not run again, so the switch owns the feed: the rows cached for that server (persist
     * key = user + server + filter) show at once, or the loading state when there are none, and a
     * load always revalidates them.
     *
     * Repeated intents for the server the store already owns are no-ops; the load started here
     * is the single flight for the switch and a later Refresh joins it.
     */
    private fun selectServer(intent: SharedActivityIntent.ServerSelected) {
        val serverId = intent.serverId.trim().takeIf { it.isNotBlank() } ?: return
        val switched =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                if (current.ownerServerId == serverId) return@synchronized false
                // Never bound to a server and nothing loading: the first LoadInitial binds the store.
                if (current.ownerServerId == null && loadJob?.isActive != true) return@synchronized false
                current.ownerServerId?.let { previous ->
                    replyThreadFreshnessLedger.invalidateServer(previous, cause = "activity_server_switch")
                }
                realtimeHighWater.clear()
                resetRealtimeCatchUpLocked()
                resetInPlaceReconcileLocked()
                presentationStore.clear()
                lastRowsReadAtEpochMillis = null
                // Web registerServerReset (slock #11383) starts the new server on its last
                // Activity filter (All when none); the persist slice hydrates that server's
                // cached rows for it.
                reduceState(
                    intent,
                    SharedActivityState(
                        ownerServerId = serverId,
                        ownerUserId = current.ownerUserId,
                        selectedFilter = rememberedFilter(serverId),
                        loading = true
                    )
                )
                true
            }
        if (!switched) return
        diagnosticsLogger(
            "store_load domain=activity phase=server_selected serverId=$serverId " +
                "cached=${_state.value.snapshot != null}"
        )
        load(refresh = true, headerIndicator = true, intent = SharedActivityIntent.LoadInitial, fetchReason = InboxFetchReason.Initial)
    }

    private suspend fun applyRealtimeEvent(intent: SharedActivityIntent.ApplyRealtimeEvent) {
        if (intent.event is PerReceiverReadStateChanged) {
            applyPerReceiverReadStateChanged(intent.event)
            return
        }
        applyRealtimeEventImmediately(intent)
    }

    private fun applyRealtimeEventImmediately(intent: SharedActivityIntent.ApplyRealtimeEvent) {
        val reconcileJob = synchronized(realtimeOwnerLock) { applyRealtimeEventLocked(intent) }
        reconcileJob?.start()
        ensureDmPeerNames()
    }

    private fun applyRealtimeEventLocked(intent: SharedActivityIntent.ApplyRealtimeEvent): Job? {
        val event = intent.event
        if (event.dropsServerAccess(_state.value)) {
            dropRetainedRows(intent) { true }
        }
        event.channelUpdatedId()?.let(::checkRetainedChannelReadableLocked)
        if (event is ThreadFollowersUpdated) {
            // A task conversation row shows the reader's follow state (Unfollow / Follow). The frame
            // (slock threadFollowerRealtimeService, to the conversation's room) does not say whose
            // follow changed — another device, a server auto-follow, or an agent — so only a row
            // for that conversation re-reads Activity, through the debounced single-flight
            // background reconcile. Thread rows keep today's behavior (web refetches agent
            // followers only).
            val threadChannelId = event.threadChannelId
            val hasRow = _state.value.items.any { it.isTaskConversation && it.threadChannelId == threadChannelId }
            return if (hasRow) scheduleRealtimeBackgroundReconcileLocked(InboxRefreshTrigger.Other) else null
        }
        if (!event.refreshesActivity()) return null
        // Web socketBridge messageNew: a message the list already shows (a repeat delivery) is an echo.
        val trigger =
            if (event is MessageNew && _state.value.showsMessage(event.message.id)) {
                InboxRefreshTrigger.MessageEcho
            } else {
                event.inboxRefreshTrigger()
            }
        // `mobile_inbox_inplace_v0`: non-null when this live message may patch its row in place.
        val inPlace = inPlaceUnreadContextLocked(intent)
        when (event) {
            // Not past a mute (the shell's allowLocalPatch): the served row stops there. In place,
            // the shown row a target names is patched instead of re-read, so nothing is caught up.
            is MessageNew -> if (intent.allowLocalPatch && inPlace == null) raiseRealtimeCatchUpTargetLocked(event.message)
            is MessageUpdated -> {
                acceptMessageUpdatedFreshness(event.message)
                applyChannelMessageUpdatedPatch(event.message, intent)
            }
            is SyncResumeResponse ->
                _state.value.ownerServerId?.let { serverId ->
                    replyThreadFreshnessLedger.invalidateServer(serverId, cause = "activity_sync_resume")
                }
            // Only a Home refresh (catch-up, horizon reset) can mean missed messages. An Agents /
            // Machines / members / settings refresh changes no timeline; invalidating the server for
            // it superseded every thread fetch in flight on a busy server (Hands 7e198cab: a task
            // conversation opened from Activity refetched round after round and never showed the
            // tapped message).
            is RealtimeRefreshRequired ->
                if (event.scope == RealtimeRefreshScope.Home) {
                    _state.value.ownerServerId?.let { serverId ->
                        replyThreadFreshnessLedger.invalidateServer(serverId, cause = "activity_refresh_required")
                    }
                }
            else -> Unit
        }
        // `activity:changed`, and incremental sync's Activity refetch (a change it could not patch):
        // read this server's list again, like web's ACTIVITY_CHANGED_EVENT -> scheduleInboxRefresh
        // (botiverse/slock bab9dd8c0 socketBridge.ts), through the same throttled single-flight.
        if (event is ActivityChanged) {
            val owner = _state.value.ownerServerId
            return if (owner != null && owner == event.serverId.value) scheduleRealtimeBackgroundReconcileLocked(trigger) else null
        }
        // dm:new refreshes like web (channelRealtimeSync.ts dmNew -> scheduleInboxRefresh):
        // a brand-new DM row can only arrive through the canonical projection.
        if (event is MessageNew || event is SyncResumeResponse || event is ThreadUpdated || event is DirectMessageCreated) {
            if (inPlace != null) {
                // Inbox refresh step 4: a row the list shows takes the message in place (preview,
                // unread, mention) and is not re-read; only a row it lacks, or a frame it cannot
                // order, asks for the throttled re-read below.
                val patch =
                    when (event) {
                        is MessageNew -> applyMessageNewPatch(event.message, intent, inPlace)
                        is ThreadUpdated -> applyThreadUpdatedInPlace(event, intent, inPlace)
                        else -> RealtimeRowPatch.NeedsRead
                    }
                diagnosticsLogger(
                    "activity_inplace event=${event::class.simpleName.orEmpty()} result=${patch.name.lowercase()}"
                )
                return when (patch) {
                    RealtimeRowPatch.Patched -> {
                        armInPlaceReconcileLocked()
                        null
                    }
                    RealtimeRowPatch.Unchanged -> null
                    RealtimeRowPatch.Absent -> {
                        noteInPlaceAbsentLocked(event)
                        scheduleRealtimeBackgroundReconcileLocked(trigger)
                    }
                    RealtimeRowPatch.NeedsRead -> scheduleRealtimeBackgroundReconcileLocked(trigger)
                }
            }
            // Only All has stable membership under a message-only payload. Unread
            // and Mentions depend on receiver-specific read/mention facts that are
            // not fully carried by every realtime frame; patching those filters
            // exposes a transient row that the background projection may remove.
            if (intent.allowLocalPatch && _state.value.selectedFilter == InboxFilter.All) {
                when (event) {
                    is MessageNew -> applyMessageNewPatch(event.message, intent)
                    is ThreadUpdated -> applyThreadUpdatedPatch(event, intent)
                    else -> Unit
                }
            }
            // Match Web's Activity ownership: realtime first applies every safe
            // narrow patch, then schedules one debounced background canonical
            // projection. This admits brand-new rows and filter membership
            // changes without showing foreground refresh chrome or requiring a
            // pull gesture. Read-state and broad identity-only events never use
            // this owner.
            return scheduleRealtimeBackgroundReconcileLocked(trigger)
        }
        // Activity is snapshot-on-entry and patch-while-visible. Events whose
        // identity-only payload cannot safely produce a narrow row patch are
        // folded by their domain owners. They must never take full-list refresh
        // ownership while the user is reading Activity.
        return null
    }

    /**
     * [inPlace] (`mobile_inbox_inplace_v0` on): the row's unread and mention follow the message too,
     * and the result says whether the list still has to be re-read. Null: today's patch (preview
     * only, unread from the reconcile); the result is then unused.
     */
    private fun applyMessageNewPatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent,
        inPlace: InPlaceUnreadContext? = null
    ): RealtimeRowPatch {
        onRealtimePatchOwnerEntered()
        val current = _state.value
        val snapshot = current.snapshot ?: return RealtimeRowPatch.Absent
        val streamChannelId = message.channelId ?: message.threadChannelId ?: return RealtimeRowPatch.Absent
        val existingIndex = snapshot.threadRowIndex(streamChannelId)
        if (existingIndex < 0) return applyChannelMessageNewPatch(message, intent, current, snapshot, inPlace)
        return patchThreadRowWithMessage(existingIndex, message, intent, current, snapshot, inPlace)
    }

    private fun patchThreadRowWithMessage(
        existingIndex: Int,
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent,
        current: SharedActivityState,
        snapshot: InboxSnapshot,
        inPlace: InPlaceUnreadContext?
    ): RealtimeRowPatch {
        val existing = snapshot.items[existingIndex]
        if (existing.lastMessageId == message.id || existing.latestActivityMessageId == message.id) {
            return RealtimeRowPatch.Unchanged
        }
        // Web receiveThreadReply (slock 1bb70cbeb, inboxStore.ts:1712-1777): a row
        // carrying a content frontier advances only on a strictly newer socket
        // seq (fail closed when the frame has none); the canonical refetch
        // supplies the row otherwise. Unread is never touched locally (flag off):
        // the server owns it and the scheduled reconcile brings it.
        val incomingSeq = message.seq?.takeIf { it >= 0L }?.toString()
        if (existing.latestActivitySeq != null && compareActivitySeq(incomingSeq, existing.latestActivitySeq) <= 0) {
            return if (incomingSeq == null) RealtimeRowPatch.NeedsRead else RealtimeRowPatch.Unchanged
        }
        // In place: a thread row or reply without a seq is left to the re-read (web
        // liveUnreadFields: its recency guard is by message id only, so a late older reply would
        // count twice).
        val unread = inPlace?.unreadFor(existing, message, snapshot, (intent.event as? MessageNew)?.systemProvenance)

        val patched =
            existing.copy(
                latestActivityMessageId = message.id,
                latestActivityPreview = message.body,
                latestActivityAt = message.timestampLabel,
                latestActivitySenderName = message.shownAuthorName(),
                latestActivitySenderId = message.author.id.value,
                latestActivitySenderKind = message.author.kind,
                latestActivitySenderAvatarWireValue = message.author.avatar?.wireValue,
                // Web inboxStore receiveThreadReply: `latestDelegatedBy` follows the new message.
                latestDelegatedBy = message.delegatedBy,
                replyCount = existing.replyCount + 1,
                latestActivitySeq = incomingSeq ?: existing.latestActivitySeq,
                // Keep the Done/read overlay marker in step with the patched row
                // (legacy Done only; see advancedActivityFrontier).
                readStateLatestActivitySeq = existing.advancedActivityFrontier(message.seq)
            ).withLiveUnread(unread, message.id).withActivityFollowStateProjection()
        advanceReplyThreadContent(existing, patched, realtimeMessageId = message.id.value)
        rememberRealtimeHighWater(patched, seq = incomingSeq, inPlaceUnread = unread != null)
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        if (unread != null) inPlaceDriftCheck.notePatched(patched, intent.inPlaceTrigger())
        return if (inPlace == null || unread != null) RealtimeRowPatch.Patched else RealtimeRowPatch.NeedsRead
    }

    /**
     * `message:new` on an ordinary/joint channel or a DM (#triage 84d44d75; web
     * `receiveChannelMessage`, slock #11208). `GET /channels/inbox` is a
     * warehouse view that lags the write by ~1-2 s, so the re-read right after the event is often
     * one message behind. The event moves the EXISTING row's preview, sender and time only, and
     * only on a strictly newer seq than the row's `latestActivitySeq` (fail closed when either side
     * has none, like [applyMessageNewPatch]'s thread rows). Unread, mention, mute and visibility stay
     * with the server answer, and so do the Done frontiers; a channel without a row gets none here.
     * Messages past the channel's Activity mute (a mention included) never arrive here: the shell
     * passes `allowLocalPatch = false` for them (SharedHomeShellStore.allowsLocalActivityPatch).
     */
    private fun applyChannelMessageNewPatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent,
        current: SharedActivityState,
        snapshot: InboxSnapshot,
        inPlace: InPlaceUnreadContext?
    ): RealtimeRowPatch {
        // A thread reply carries its thread channel as `channelId`, which never names a channel row.
        val channelId = message.channelId ?: return RealtimeRowPatch.Absent
        val existingIndex =
            snapshot.items.indexOfFirst { item -> item.isChannelOrDmRow() && item.channelId == channelId }
        if (existingIndex < 0) return RealtimeRowPatch.Absent
        val existing = snapshot.items[existingIndex]
        if (existing.lastMessageId == message.id) return RealtimeRowPatch.Unchanged
        // Fail closed when either side has no seq: the re-read supplies the row.
        val incomingSeq = message.seq?.takeIf { it >= 0L }?.toString() ?: return RealtimeRowPatch.NeedsRead
        if (existing.latestActivitySeq == null) return RealtimeRowPatch.NeedsRead
        if (compareActivitySeq(incomingSeq, existing.latestActivitySeq) <= 0) return RealtimeRowPatch.Unchanged
        val unread = inPlace?.unreadFor(existing, message, snapshot, (intent.event as? MessageNew)?.systemProvenance)
        val patched =
            existing.copy(
                lastMessageId = message.id,
                lastMessagePreview = message.body,
                lastMessageAt = message.timestampLabel,
                lastMessageSenderName = message.shownAuthorName(),
                lastMessageSenderId = message.author.id.value,
                lastMessageSenderKind = message.author.kind,
                lastMessageSenderAvatarWireValue = message.author.avatar?.wireValue,
                latestDelegatedBy = message.delegatedBy,
                latestActivitySeq = incomingSeq
            ).withLiveUnread(unread, message.id)
        realtimeHighWater[patched.key] =
            ActivityRealtimeHighWater(
                marker = patched.lastMessageId,
                replyCount = 0,
                seq = incomingSeq,
                holdUntilMillis = nowMillis() + ActivityLocalMutationHoldMillis,
                inPlaceUnread = unread != null
            )
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        if (unread != null) inPlaceDriftCheck.notePatched(patched, intent.inPlaceTrigger())
        return if (inPlace == null || unread != null) RealtimeRowPatch.Patched else RealtimeRowPatch.NeedsRead
    }

    /**
     * `message:updated` for the message a channel/DM row shows (`lastMessageId`), usually one
     * [applyChannelMessageNewPatch] put there and the lagging inbox view is still behind. The row's
     * preview follows the updated body in place, like the patch derives it; the held fields
     * ([reconcileRealtimeChannelRow] copies them from the shown row) then keep the new text until
     * the server catches up. Sender, time, seq, counts and order stay: a message's author and
     * creation time never change. A blank body is a sparse update and keeps the preview (thread
     * page merge, `body.ifBlank { body }`). A retraction (RFC 077, `deletedAt` set) goes to
     * [applyRetractedMessagePatch]: the server's placeholder `content` is authoritative there.
     * No filter/mute gate: the update moves no row in or out, it only corrects text already shown.
     */
    private fun applyChannelMessageUpdatedPatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ) {
        if (!message.deletedAt.isNullOrBlank()) {
            applyRetractedMessagePatch(message, intent)
            return
        }
        val body = message.body.takeIf { it.isNotBlank() } ?: return
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val existingIndex =
            snapshot.items.indexOfFirst { item -> item.isChannelOrDmRow() && item.lastMessageId == message.id }
        if (existingIndex < 0) return
        val existing = snapshot.items[existingIndex]
        if (existing.lastMessagePreview == body) return
        val items = snapshot.items.toMutableList().apply { set(existingIndex, existing.copy(lastMessagePreview = body)) }
        reduceState(intent, current.copy(snapshot = snapshot.copy(items = items)))
    }

    /**
     * RFC 077 PR 2b, web socketBridge.messageUpdated -> inboxStore.retractPreview: a retracted
     * message's server placeholder replaces every preview of it — a channel/DM row's last message,
     * a thread row's latest reply and its parent, and the thread row's reply window that seeds
     * the thread page. No filter/mute gate, like [applyChannelMessageUpdatedPatch].
     */
    private fun applyRetractedMessagePatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val items = snapshot.items.map { item -> item.withRetractedPreview(message.id, message.body) }
        if (items.indices.none { index -> items[index] !== snapshot.items[index] }) return
        reduceState(intent, current.copy(snapshot = snapshot.copy(items = items)))
    }

    /**
     * `thread:updated` with `mobile_inbox_inplace_v0` on (web socketBridge threadUpdated:
     * `receiveThreadReply(latestReply, live)` then `updateThreadActivityMeta`): the frame's latest
     * reply patches the row like its `message:new` would (seq-guarded, so whichever of the two comes
     * second changes nothing), then the reply count and time move. A frame without its reply says
     * nothing about unread: the row is re-read.
     */
    private fun applyThreadUpdatedInPlace(
        event: ThreadUpdated,
        intent: SharedActivityIntent.ApplyRealtimeEvent,
        inPlace: InPlaceUnreadContext
    ): RealtimeRowPatch {
        val current = _state.value
        val snapshot = current.snapshot ?: return RealtimeRowPatch.Absent
        val existingIndex = snapshot.threadRowIndex(event.threadChannelId)
        if (existingIndex < 0) return RealtimeRowPatch.Absent
        val reply =
            event.latestReply?.toActivityLiveMessage(event)
                ?: return RealtimeRowPatch.NeedsRead.also { applyThreadUpdatedPatch(event, intent) }
        val replyPatch = patchThreadRowWithMessage(existingIndex, reply, intent, current, snapshot, inPlace)
        val metaPatched = applyThreadUpdatedPatch(event, intent)
        return if (replyPatch == RealtimeRowPatch.Unchanged && metaPatched) RealtimeRowPatch.Patched else replyPatch
    }

    /** True when the row's reply count or time moved. */
    private fun applyThreadUpdatedPatch(
        event: ThreadUpdated,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ): Boolean {
        val current = _state.value
        val snapshot = current.snapshot ?: return false
        val existingIndex = snapshot.threadRowIndex(event.threadChannelId)
        if (existingIndex < 0) return false
        val existing = snapshot.items[existingIndex]
        if (event.replyCount < existing.replyCount) return false
        // Web updateThreadActivityMeta (inboxStore.ts:1779-1795) moves only the
        // reply count and time; the event's unread never overwrites the row.
        val patched =
            existing.copy(
                replyCount = event.replyCount,
                latestActivityAt =
                    if (event.replyCount > existing.replyCount && event.lastReplyAt != null) {
                        event.lastReplyAt
                    } else {
                        existing.latestActivityAt
                    },
                // The reply this event counts carries its seq: keep the overlay
                // marker in step so a Done on the patched row covers it.
                readStateLatestActivitySeq =
                    if (event.replyCount > existing.replyCount) {
                        existing.advancedActivityFrontier(event.latestReply?.seq)
                    } else {
                        existing.readStateLatestActivitySeq
                    }
            ).withActivityFollowStateProjection()
        if (patched == existing) return false
        advanceReplyThreadContent(existing, patched, realtimeMessageId = event.latestReply?.messageId)
        rememberRealtimeHighWater(patched)
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        return true
    }

    /**
     * Advances the row's authority frontier with a realtime message seq, but only
     * when the row already carries one: a row served without `readState` (old
     * server) keeps its anchor/reply-count overlay comparison, and a socket seq
     * must not turn it into a half-seq marker the next served copy cannot match.
     */
    private fun InboxItem.advancedActivityFrontier(realtimeSeq: Long?): String? {
        val existing = readStateLatestActivitySeq ?: return null
        // A storage-frontier Done (`doneFrontierSeq` present) covers only the
        // served frontier, so the marker stays on the served row (web never
        // moves it on a socket patch); legacy Done covers latest-at-request.
        if (doneFrontierSeqPresent) return existing
        val candidate = realtimeSeq?.takeIf { it >= 0L }?.toString() ?: return existing
        return if (compareActivitySeq(candidate, existing) > 0) candidate else existing
    }

    private fun applyPatchedActivityItem(
        patched: InboxItem,
        existingIndex: Int,
        current: SharedActivityState,
        snapshot: InboxSnapshot,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ) {
        val previous = snapshot.items[existingIndex]
        val projected = patched.withActivityFollowStateProjection()
        val items = listOf(projected) + snapshot.items.filterIndexed { index, _ -> index != existingIndex }
        reduceState(
            intent,
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = items,
                        totalUnreadCount =
                            maxOf(0, snapshot.totalUnreadCount + projected.unreadCount - previous.unreadCount)
                    ),
                errorCode = null
            )
        )
    }

    /** [seq] is the `message:new` seq of the patched reply; a `thread:updated` patch keeps the one it has. */
    private fun rememberRealtimeHighWater(item: InboxItem, seq: String? = null, inPlaceUnread: Boolean = false) {
        val previous = realtimeHighWater[item.key]
        if (previous != null && item.replyCount < previous.replyCount) return
        realtimeHighWater[item.key] =
            ActivityRealtimeHighWater(
                marker = item.latestActivityMessageId,
                replyCount = item.replyCount,
                seq = seq ?: previous?.seq?.takeIf { previous.marker == item.latestActivityMessageId },
                holdUntilMillis = nowMillis() + ActivityLocalMutationHoldMillis,
                // A later `thread:updated` meta patch keeps the unread the reply put there.
                inPlaceUnread = inPlaceUnread || previous?.inPlaceUnread == true
            )
    }

    /**
     * [trigger] names the ask for the read's `X-Raft-Fetch-Trigger` (#13126); null only for the
     * return's own read, which is a `visibility` read and carries none.
     */
    private fun scheduleRealtimeBackgroundReconcileLocked(trigger: InboxRefreshTrigger?): Job {
        if (!appForeground.value) {
            // In the background nothing reads: the return reads once (onAppForegroundReturned).
            realtimeReconcileOwedOnForeground = true
            return realtimeBackgroundReconcileJob ?: Job().apply { complete() }
        }
        if (activitySurfaceHidden && inboxInPlaceOnLocked()) {
            // Inbox refresh step 4, rule 3: nobody sees the list, so nothing reads it; the Refresh
            // that shows it again reads it once (`visibility`).
            diagnosticsLogger("store_load domain=activity phase=deferred reason=surface_hidden trigger=${trigger?.wireValue}")
            return realtimeBackgroundReconcileJob ?: Job().apply { complete() }
        }
        realtimeBackgroundReconcileDirty = true
        if (trigger != null) realtimeBackgroundReconcileTrigger = realtimeBackgroundReconcileTrigger.foldWith(trigger)
        // Only with a hidden window configured: without one the store behaves exactly as before.
        hiddenFoldLoads =
            if (activitySurfaceHidden && hiddenRealtimeBackgroundRefreshIntervalMillis != null) resetLoadsStarted else null
        realtimeBackgroundReconcileJob?.let { return it }
        // Leading + trailing throttle (web requestInboxBackgroundRefresh): the
        // first request after a quiet window fetches immediately; requests inside
        // the window coalesce into one trailing fetch at its end, so the latest
        // state still arrives. The window is measured from the last reset load of
        // ANY kind, and user-initiated loads never wait on it.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                while (true) {
                    // Waits in steps of the visible window, so a hidden (longer) wait ends soon after
                    // the surface shows again.
                    while (true) {
                        val waitMillis =
                            synchronized(realtimeOwnerLock) {
                                val resumeWait = resumeNotBeforeMillis?.let { it - nowMillis() } ?: 0L
                                maxOf(realtimeBackgroundRefreshWaitMillisLocked(), resumeWait)
                            }
                        if (waitMillis <= 0L) break
                        delay(minOf(waitMillis, realtimeBackgroundRefreshIntervalMillis.coerceAtLeast(1L)))
                    }
                    // The asks this round answers; asks made from here on count for the next round.
                    var roundTrigger: InboxRefreshTriggerCount? = null
                    val shouldReconcile =
                        synchronized(realtimeOwnerLock) {
                            roundTrigger = realtimeBackgroundReconcileTrigger
                            realtimeBackgroundReconcileTrigger = null
                            if (realtimeBackgroundReconcileDirty) {
                                realtimeBackgroundReconcileDirty = false
                                true
                            } else {
                                realtimeBackgroundReconcileJob = null
                                false
                            }
                        }
                    if (!shouldReconcile) return@launch
                    awaitCurrentLoadIdle()
                    var returnRead = false
                    val next =
                        synchronized(realtimeOwnerLock) {
                            val foldBase = resumeFoldLoads
                            resumeFoldLoads = null
                            resumeNotBeforeMillis = null
                            when {
                                !appForeground.value -> {
                                    // Went to the background while waiting: no read now, one on the return.
                                    realtimeReconcileOwedOnForeground = true
                                    realtimeBackgroundReconcileDirty = false
                                    realtimeBackgroundReconcileTrigger = null
                                    realtimeBackgroundReconcileJob = null
                                    ReconcileStep.Stop
                                }
                                // Step 4: hidden while waiting; the Refresh that shows it reads.
                                activitySurfaceHidden && inboxInPlaceOnLocked() -> {
                                    realtimeBackgroundReconcileDirty = false
                                    realtimeBackgroundReconcileTrigger = null
                                    realtimeBackgroundReconcileJob = null
                                    ReconcileStep.Stop
                                }
                                // The return's one read: a reset load started since the return (the
                                // visible page's resume refresh) already was it; asks folded into it.
                                foldBase != null ->
                                    if (resetLoadsStarted > foldBase) {
                                        realtimeBackgroundReconcileDirty = false
                                        realtimeBackgroundReconcileTrigger = null
                                        ReconcileStep.Again
                                    } else {
                                        // The one read owed for the return to the foreground.
                                        returnRead = true
                                        ReconcileStep.Load
                                    }
                                // Asked only while hidden, and the entry Refresh (a reset load) started
                                // since: that read was it.
                                hiddenFoldLoads?.let { fold -> resetLoadsStarted > fold } == true -> {
                                    hiddenFoldLoads = null
                                    realtimeBackgroundReconcileDirty = false
                                    realtimeBackgroundReconcileTrigger = null
                                    ReconcileStep.Again
                                }
                                realtimeBackgroundRefreshWaitMillisLocked() > 0L -> {
                                    // A load started while we waited; fetch on that window's trailing edge.
                                    realtimeBackgroundReconcileDirty = true
                                    // Still owed: this round's asks stay first in the next one.
                                    realtimeBackgroundReconcileTrigger = roundTrigger.foldWith(realtimeBackgroundReconcileTrigger)
                                    ReconcileStep.Again
                                }
                                else -> ReconcileStep.Load
                            }
                        }
                    if (next == ReconcileStep.Stop) return@launch
                    if (next == ReconcileStep.Again) continue
                    load(
                        refresh = true,
                        pullRefresh = false,
                        backgroundRefresh = true,
                        intent = SharedActivityIntent.Refresh,
                        fetchReason =
                            if (returnRead) InboxFetchReason.Visibility else InboxFetchReason.RealtimeFallback,
                        fetchTrigger = roundTrigger
                    )
                    awaitCurrentLoadIdle()
                }
            }
        realtimeBackgroundReconcileJob = job
        return job
    }

    private enum class ReconcileStep { Load, Again, Stop }

    /** `mobile_inbox_inplace_v0` for the server the list shows. */
    private fun inboxInPlaceOnLocked(): Boolean =
        _state.value.ownerServerId?.let(inboxInPlaceEnabled) == true

    /**
     * Non-null when [intent]'s live message may patch its row in place: the flag is on, it is a
     * `message:new` / `thread:updated`, not past a mute, on the All list (the only one whose
     * membership a message alone decides) and the reader is known.
     */
    private fun inPlaceUnreadContextLocked(intent: SharedActivityIntent.ApplyRealtimeEvent): InPlaceUnreadContext? {
        if (intent.event !is MessageNew && intent.event !is ThreadUpdated) return null
        if (!intent.allowLocalPatch) return null
        val current = _state.value
        if (current.selectedFilter != InboxFilter.All || current.snapshot == null) return null
        if (!inboxInPlaceOnLocked()) return null
        val principalId = current.ownerUserId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return InPlaceUnreadContext(principalId, intent.activeConversationId)
    }

    /**
     * Rule 5: rows patched in place are re-read once, [inPlaceReconcileIntervalMillis] after the
     * patch, while the list is visible; a reset load of any kind started after the latest patch
     * covers it, and a hidden or backgrounded list is read by its `visibility` read instead.
     */
    private fun armInPlaceReconcileLocked() {
        inPlaceReconcileMark = resetLoadsStarted
        if (inPlaceReconcileJob != null) return
        inPlaceReconcileJob =
            scope.launch {
                val self = currentCoroutineContext()[Job]
                while (true) {
                    delay(inPlaceReconcileIntervalMillis)
                    awaitCurrentLoadIdle()
                    val read =
                        synchronized(realtimeOwnerLock) {
                            if (inPlaceReconcileJob !== self) return@synchronized false
                            val mark = inPlaceReconcileMark
                            val owed = mark != null && resetLoadsStarted <= mark
                            inPlaceReconcileMark = null
                            val visible = appForeground.value && !activitySurfaceHidden
                            if (!owed || !visible) inPlaceReconcileJob = null
                            owed && visible
                        }
                    if (!read) return@launch
                    diagnosticsLogger("store_load domain=activity phase=inplace_reconcile")
                    load(
                        refresh = true,
                        pullRefresh = false,
                        backgroundRefresh = true,
                        intent = SharedActivityIntent.Refresh,
                        fetchReason = InboxFetchReason.InPlaceReconcile
                    )
                }
            }
    }

    private fun resetInPlaceReconcileLocked() {
        inPlaceReconcileJob?.cancel()
        inPlaceReconcileJob = null
        inPlaceReconcileMark = null
        inPlaceDriftCheck.clear()
    }

    /** The trigger of a row a live message patched in place (web `InPlaceTrigger`). */
    private fun SharedActivityIntent.ApplyRealtimeEvent.inPlaceTrigger(): InboxRefreshTrigger =
        if (event is ThreadUpdated) InboxRefreshTrigger.ThreadReply else InboxRefreshTrigger.MessageNew

    /** A live message for a row the list lacks: the check notes whether the re-read brings it. */
    private fun noteInPlaceAbsentLocked(event: RealtimeEvent) {
        when (event) {
            is MessageNew -> {
                val scopeId = (event.message.channelId ?: event.message.threadChannelId)?.value ?: return
                inPlaceDriftCheck.noteAbsent(
                    scopeId,
                    InboxRefreshTrigger.MessageNew,
                    event.message.seq?.takeIf { it >= 0L }?.toString()
                )
            }
            is ThreadUpdated ->
                inPlaceDriftCheck.noteAbsent(
                    event.threadChannelId.value,
                    InboxRefreshTrigger.ThreadReply,
                    event.latestReply?.seq?.takeIf { it >= 0L }?.toString()
                )
            else -> Unit
        }
    }

    /**
     * Web `checkInPlaceDrift` (inboxStore.ts: a reset answer of the All list, before the local
     * holds of newer rows): judges the rows patched in place against [served] and sends the checks.
     * Flag off, nothing was noted and nothing is sent.
     */
    private fun checkInPlaceDriftLocked(current: SharedActivityState, served: List<InboxItem>) {
        if (inPlaceDriftCheck.isEmpty || current.selectedFilter != InboxFilter.All) return
        val serverId = current.ownerServerId
        if (serverId == null || !inboxInPlaceEnabled(serverId)) {
            inPlaceDriftCheck.clear()
            return
        }
        for (check in inPlaceDriftCheck.check(current.items, served)) {
            diagnosticsLogger(
                "activity_inplace_check entity=${check.entityId} trigger=${check.trigger.wireValue} " +
                    "outcome=${check.outcome.wireValue} result=${check.result}"
            )
            inPlaceCheckSink(serverId, check)
        }
    }

    private fun realtimeBackgroundRefreshWaitMillisLocked(): Long {
        val lastStartedAt = lastResetLoadStartedAtMillis ?: return 0L
        val interval =
            if (activitySurfaceHidden) {
                hiddenRealtimeBackgroundRefreshIntervalMillis ?: realtimeBackgroundRefreshIntervalMillis
            } else {
                realtimeBackgroundRefreshIntervalMillis
            }
        return (lastStartedAt + interval - nowMillis()).coerceAtLeast(0L)
    }

    private suspend fun awaitCurrentLoadIdle() {
        while (true) {
            val observed = synchronized(realtimeOwnerLock) { loadJob }
            observed?.join()
            if (synchronized(realtimeOwnerLock) { loadJob === observed }) return
        }
    }

    private suspend fun applyRealtimeSummaryEvent(
        event: RealtimeEvent,
        activeConversationId: String?
    ) {
        if (event is PerReceiverReadStateChanged) {
            applyPerReceiverReadStateChanged(event)
            return
        }
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val currentUserId = currentUserId() ?: return
        val increment =
            synchronized(realtimeOwnerLock) {
                event.activitySummaryUnreadIncrement(snapshot, activeConversationId, currentUserId) { seq ->
                    // false = already counted; else record it (oldest dropped past the cap).
                    realtimeCountedUnreadSeqs.add(seq).also {
                        while (realtimeCountedUnreadSeqs.size > ActivityRealtimeCountedSeqLimit) {
                            realtimeCountedUnreadSeqs.remove(realtimeCountedUnreadSeqs.first())
                        }
                    }
                }
            }
        if (increment <= 0) return
        reduceState(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId),
            current.copy(
                snapshot =
                    snapshot.copy(
                        totalUnreadCount = snapshot.totalUnreadCount + increment
                ),
                errorCode = null
            )
        )
    }

    private suspend fun applyPerReceiverReadStateChanged(event: PerReceiverReadStateChanged) {
        val session = authStore.readSession() ?: return
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        if (event.serverId != null && event.serverId != serverId) return
        val memberId = session.user.id.trim().takeIf { it.isNotBlank() }?.let(::MemberId) ?: return
        var acceptedHere = false
        val reconcileJobs = mutableListOf<Job>()
        event.scopes.forEach { readScope ->
            val productionOwner =
                readReceiptOwnerFactory
                    ?.invoke(serverId.value, memberId.value)
                    ?.toReadStateV1ProductionOwner()
            if (productionOwner != null) {
                val disposition =
                    readStateV1Canary?.applyOwnerRealtime(
                        owner = productionOwner,
                        channelId = readScope.scopeId.value,
                        readStateVersion = readScope.readStateVersion,
                        maxReadSeq = readScope.maxReadSeq,
                    )
                if (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy) {
                    return@forEach
                }
            }
            synchronized(realtimeOwnerLock) {
                val maxReadSeq = readScope.maxReadSeq ?: return@forEach
                val result =
                    perReceiverStateVersionStore.applyOrObserveReadStateFact(
                        serverId = serverId,
                        memberId = memberId,
                        scopeId = readScope.scopeId,
                        incomingVersion = readScope.readStateVersion,
                        incomingMaxReadSeq = maxReadSeq
                    )
                diagnosticsLogger(
                    "activity_read_state_apply serverId=${serverId.value} scopeId=${readScope.scopeId.value} " +
                        "version=${readScope.readStateVersion} maxReadSeq=$maxReadSeq result=${result.name.lowercase()}"
                )
                if (result == PerReceiverReadStateFactResult.Accepted) {
                    acceptedHere = true
                    projectRemoteReadLocked(
                        channelId = readScope.scopeId.value,
                        maxReadSeq = maxReadSeq,
                        intent = SharedActivityIntent.ApplyRealtimeEvent(event)
                    )?.let(reconcileJobs::add)
                }
                // botiverse/slock #11795: the fact's "marked unread" flag, also at the version already
                // held (Home shares these checkpoints and may have taken the fact first).
                if (result == PerReceiverReadStateFactResult.Accepted || result == PerReceiverReadStateFactResult.Current) {
                    projectRemoteMarkedUnreadLocked(
                        channelId = readScope.scopeId.value,
                        markedUnread = readScope.markedUnread,
                        intent = SharedActivityIntent.ApplyRealtimeEvent(event)
                    )?.let(reconcileJobs::add)
                }
            }
        }
        reconcileJobs.forEach(Job::start)
        if (acceptedHere) onUnreadCountsChanged()
    }

    /**
     * Cross-device / cross-tab read (web applyReadStateUpdate ->
     * inboxStore.applyReadStateProjection, botiverse/slock 1bb70cbeb
     * applyReadStateUpdate.ts:8-30, inboxStore.ts:1666-1707). Web projects a
     * read only when it can prove what it covers; here that proof is the row's
     * own authority frontier (`readState.latestActivity.seq`, same read-state
     * domain as `maxReadSeq`). A covered row is cleared (and leaves Unread)
     * with a read hold so a lagging refresh cannot resurrect it; a row the read
     * may not fully cover gets a throttled canonical reconcile instead.
     */
    private fun projectRemoteReadLocked(
        channelId: String,
        maxReadSeq: Long,
        intent: SharedActivityIntent
    ): Job? {
        val current = _state.value
        val snapshot = current.snapshot ?: return null
        val matching =
            snapshot.items.filter { item -> item.unreadCount > 0 && item.readReceiptChannelId()?.value == channelId }
        if (matching.isEmpty()) return null
        val readSeq = maxReadSeq.takeIf { it >= 0L }?.toString()
        val (covered, uncovered) =
            matching.partition { item ->
                val frontier = item.readStateLatestActivitySeq?.takeIf(::isCanonicalActivitySeq)
                frontier != null && compareActivitySeq(readSeq, frontier) >= 0
            }
        if (covered.isNotEmpty()) {
            // The read is already persisted server-side: start the hold now.
            val remoteReadOwner = overlayOwner(current)
            readOverlay.confirmAll(remoteReadOwner, rememberUnreadCleared(remoteReadOwner, covered))
            val coveredKeys = covered.mapTo(mutableSetOf(), InboxItem::key)
            val keepsRows = current.selectedFilter.keepsReadActivityRows()
            val items =
                snapshot.items.mapNotNull { item ->
                    when {
                        item.key !in coveredKeys -> item
                        current.keepsReadRow(item.key) ->
                            item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                        else -> null
                    }
                }
            reduceState(
                intent,
                current.copy(
                    snapshot =
                        snapshot.copy(
                            items = items,
                            totalCount = if (keepsRows) snapshot.totalCount else maxOf(0, snapshot.totalCount - covered.size),
                            totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - covered.sumOf(InboxItem::unreadCount))
                        )
                )
            )
        }
        diagnosticsLogger(
            "activity_read_state_patch scopeId=$channelId maxReadSeq=$maxReadSeq " +
                "projected=${covered.size} reconcile=${uncovered.isNotEmpty()}"
        )
        return if (uncovered.isNotEmpty()) scheduleRealtimeBackgroundReconcileLocked(InboxRefreshTrigger.Other) else null
    }

    /**
     * botiverse/slock #11795: an accepted read state's "marked unread" flag on this scope's rows. A mark
     * shows the dot on a row with no count; a newer read state without it clears the dot, and in a
     * filter that drops read rows a row left with nothing unread leaves (unless retained this visit).
     * A mark for a scope Unread does not list yet asks for a background reconcile.
     */
    private fun projectRemoteMarkedUnreadLocked(
        channelId: String,
        markedUnread: Boolean,
        intent: SharedActivityIntent
    ): Job? {
        val current = _state.value
        val snapshot = current.snapshot ?: return null
        val matching = snapshot.items.filter { item -> item.readReceiptChannelId()?.value == channelId }
        if (matching.isEmpty()) {
            return if (markedUnread && current.selectedFilter == InboxFilter.Unread) {
                scheduleRealtimeBackgroundReconcileLocked(InboxRefreshTrigger.Other)
            } else {
                null
            }
        }
        if (matching.all { it.markedUnread == markedUnread }) return null
        var removed = 0
        val items =
            snapshot.items.mapNotNull { item ->
                when {
                    item.readReceiptChannelId()?.value != channelId || item.markedUnread == markedUnread -> item
                    !markedUnread && item.unreadCount <= 0 && !current.keepsReadRow(item.key) -> {
                        removed += 1
                        null
                    }
                    else -> item.copy(markedUnread = markedUnread)
                }
            }
        diagnosticsLogger("activity_marked_unread_patch scopeId=$channelId marked=$markedUnread removed=$removed")
        reduceState(
            intent,
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = items,
                        totalCount = (snapshot.totalCount - removed).coerceAtLeast(0)
                    )
            )
        )
        return null
    }

    internal fun applyReadStateV1Projection(owner: ReadStateV1ProductionOwner, fact: ReadStateV1Fact) {
        var reconcileJob: Job? = null
        val accepted = synchronized(realtimeOwnerLock) {
            val current = _state.value
            val serverId = current.ownerServerId ?: return@synchronized false
            val userId = current.ownerUserId ?: return@synchronized false
            val currentOwner =
                readReceiptOwnerFactory
                    ?.invoke(serverId, userId)
                    ?.toReadStateV1ProductionOwner()
                    ?: return@synchronized false
            if (currentOwner != owner) return@synchronized false
            diagnosticsLogger(
                "activity_read_state_patch result=durable_v1_projection scopeId=${fact.channelId} " +
                    "version=${fact.readStateVersion} maxReadSeq=${fact.maxReadSeq}"
            )
            reconcileJob =
                projectRemoteReadLocked(
                    channelId = fact.channelId,
                    maxReadSeq = fact.maxReadSeq,
                    intent =
                        SharedActivityIntent.ApplyRealtimeEvent(
                            PerReceiverReadStateChanged(
                                serverId = ServerId(serverId),
                                scopes =
                                    listOf(
                                        PerReceiverReadStateScope(
                                            scopeId = ChannelId(fact.channelId),
                                            maxReadSeq = fact.maxReadSeq,
                                            readStateVersion = fact.readStateVersion
                                        )
                                    )
                            )
                        )
                )
            true
        }
        reconcileJob?.start()
        if (accepted) onUnreadCountsChanged()
    }

    internal fun requestReadStateV1Rebaseline(owner: ReadStateV1ProductionOwner, channelId: String) {
        val accepted = synchronized(realtimeOwnerLock) {
            val current = _state.value
            val serverId = current.ownerServerId ?: return@synchronized false
            val userId = current.ownerUserId ?: return@synchronized false
            val currentOwner =
                readReceiptOwnerFactory
                    ?.invoke(serverId, userId)
                    ?.toReadStateV1ProductionOwner()
                    ?: return@synchronized false
            if (currentOwner != owner) return@synchronized false
            diagnosticsLogger("activity_read_state_patch result=rebaseline_requested scopeId=$channelId")
            true
        }
        if (accepted) onUnreadCountsChanged()
    }

    /**
     * Ticket 67609860: a visible-route Refresh (resume, re-entry, page did appear) shows the header
     * indicator only when the rows shown were last read [ActivityHeaderRefreshStaleAfterMillis]
     * ago or never; a re-entry right after a load or realtime refresh revalidates silently.
     */
    private fun shownRowsAreOld(): Boolean {
        val lastReadAt = synchronized(realtimeOwnerLock) { lastRowsReadAtEpochMillis } ?: return true
        return nowEpochMillis() - lastReadAt >= ActivityHeaderRefreshStaleAfterMillis
    }

    /**
     * [headerIndicator]: this refresh may show the header's top-right indicator
     * ([SharedActivityState.headerRefreshing]). Only the open / server or filter switch / visible
     * Refresh over old rows pass it; background loads (realtime, sync, stale recovery) and the
     * store's own corrective refreshes never show it, and a pull shows only the pull spinner.
     */
    private fun load(
        refresh: Boolean,
        pullRefresh: Boolean = false,
        backgroundRefresh: Boolean = false,
        headerIndicator: Boolean = false,
        intent: SharedActivityIntent,
        /** Why the first page is read; sent as `X-Raft-Fetch-Reason` (see [InboxFetchReason]). */
        fetchReason: InboxFetchReason? = null,
        /** What asked for a realtime-fallback read; sent as `X-Raft-Fetch-Trigger` (see [InboxRefreshTrigger]). */
        fetchTrigger: InboxRefreshTriggerCount? = null
    ) {
        val job =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                val inFlightLoad = loadJob?.takeUnless { job -> job.isCompleted }
                if (backgroundRefresh && inFlightLoad != null) return@synchronized null
                if (backgroundRefresh && !appForeground.value) {
                    // No inbox read in the background (stale recovery, reconcile): one is owed for the return.
                    realtimeReconcileOwedOnForeground = true
                    diagnosticsLogger("store_load domain=activity phase=deferred reason=app_background")
                    return@synchronized null
                }
                if (pullRefresh && inFlightLoad != null) {
                    // Hands f6c3f3a6: a pull over a visible refresh still in flight joins it and
                    // shows the pull spinner, instead of throwing the pages it already read away.
                    val inFlightAgeMillis = foregroundResetLoadStartedAtMillis?.let { startedAt -> nowMillis() - startedAt }
                    val supersedeReason =
                        when {
                            inFlightAgeMillis == null -> "not_visible_refresh"
                            progressiveTailGeneration == loadGeneration -> "first_page_shown"
                            current.snapshot == null -> "no_rows"
                            inFlightAgeMillis >= ActivityPullJoinsInFlightRefreshWindowMillis -> "stale_in_flight"
                            else -> null
                        }
                    if (supersedeReason == null) {
                        pullJoinedGeneration = loadGeneration
                        reduceState(intent, current.copy(refreshing = true, pullRefreshing = true))
                        diagnosticsLogger(
                            "store_load domain=activity phase=pull_joined generation=$loadGeneration " +
                                "inFlightAgeMs=$inFlightAgeMillis"
                        )
                        return@synchronized null
                    }
                    diagnosticsLogger(
                        "store_load domain=activity phase=pull_supersedes reason=$supersedeReason " +
                            "generation=$loadGeneration inFlightAgeMs=${inFlightAgeMillis ?: -1}"
                    )
                }
                if (!refresh && inFlightLoad != null && progressiveTailGeneration == loadGeneration) {
                    // The refresh is still reading the pages below the first: they are the rows a
                    // LoadMore would ask for, and a LoadMore would cancel that read.
                    return@synchronized null
                }
                if (refresh && !pullRefresh && intent == SharedActivityIntent.Refresh && inFlightLoad != null) {
                    // Keyed on the job, not on loading/refreshing: those flags are only set once
                    // the launched load has read the session, so a same-frame second Refresh used
                    // to cancel and restart the first.
                    val inFlightAgeMillis = foregroundResetLoadStartedAtMillis?.let { startedAt -> nowMillis() - startedAt }
                    if (activityRefreshJoinsInFlightLoad(inFlightAgeMillis, refreshCoalesceWindowMillis)) {
                        diagnosticsLogger(
                            "store_load domain=activity phase=coalesced intent=refresh " +
                                "generation=$loadGeneration inFlightAgeMs=$inFlightAgeMillis"
                        )
                        return@synchronized null
                    }
                    if (inFlightAgeMillis != null) {
                        diagnosticsLogger(
                            "store_load domain=activity phase=supersede_stale intent=refresh " +
                                "generation=$loadGeneration inFlightAgeMs=$inFlightAgeMillis"
                        )
                    }
                }
                if (!refresh && (current.loading || current.refreshing || current.loadingMore || !current.hasMore)) {
                    return@synchronized null
                }
                val generation = ++loadGeneration
                val startedAtMillis = nowMillis()
                if (refresh) {
                    lastResetLoadStartedAtMillis = startedAtMillis
                    resetLoadsStarted += 1
                }
                foregroundResetLoadStartedAtMillis = startedAtMillis.takeIf { refresh && !backgroundRefresh }
                loadJob?.cancel()
                // This load owns the background flag now: a superseded background load's own
                // clean-up below is fenced off by the generation.
                val backgroundRefreshing = refresh && backgroundRefresh && current.snapshot != null
                if (_state.value.backgroundRefreshing != backgroundRefreshing) {
                    _state.update { state -> state.copy(backgroundRefreshing = backgroundRefreshing) }
                }
                val showsHeaderIndicator = refresh && headerIndicator && !pullRefresh && !backgroundRefresh
                scope.launch(start = CoroutineStart.LAZY) {
                    val startedAt = TimeSource.Monotonic.markNow()
                    val previous = _state.value
                    var filter = previous.selectedFilter
                    var refreshLimit = SharedActivityPageSize
                    try {
                        logActivityLoadStart(
                            refresh = refresh,
                            pullRefresh = pullRefresh,
                            backgroundRefresh = backgroundRefresh,
                            headerIndicator = showsHeaderIndicator,
                            filter = filter,
                            generation = generation
                        )
                        val session = authStore.readSession()
                        if (session == null) {
                            synchronized(realtimeOwnerLock) {
                                if (!backgroundRefresh) setLoadError("unauthenticated", append = !refresh)
                            }
                            logActivityLoad(
                                result = "error",
                                code = "unauthenticated",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        val serverId = session.currentServerId?.let(::ServerId)
                        if (serverId == null) {
                            synchronized(realtimeOwnerLock) {
                                if (!backgroundRefresh) setLoadError("server_missing", append = !refresh)
                            }
                            logActivityLoad(
                                result = "error",
                                code = "server_missing",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        val userId = session.user.id
                        val server = session.servers.firstOrNull { it.id == serverId.value }?.toServerSummary()
                        if (server == null) {
                            synchronized(realtimeOwnerLock) {
                                if (!backgroundRefresh) setLoadError("server_missing", append = !refresh)
                            }
                            // task #436: this exit previously logged nothing. After a start
                            // line, silence is read as "never terminated" — the stuck
                            // signature — so an unlogged exit makes the diagnostic
                            // manufacture the very false positive it exists to rule out.
                            logActivityLoad(
                                result = "error",
                                code = "server_missing",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        synchronized(realtimeOwnerLock) {
                            val stateBeforeOwner = _state.value
                            val ownerChanged =
                                stateBeforeOwner.ownerServerId?.let { it != serverId.value } == true ||
                                    stateBeforeOwner.ownerUserId?.let { it != userId } == true
                            if (ownerChanged) {
                                stateBeforeOwner.ownerServerId?.let { previousServerId ->
                                    replyThreadFreshnessLedger.invalidateServer(
                                        previousServerId,
                                        cause = "activity_server_switch",
                                    )
                                }
                                realtimeHighWater.clear()
                                resetRealtimeCatchUpLocked()
                                presentationStore.clear()
                            }
                            val stateForOwner =
                                if (ownerChanged) {
                                    // Web registerServerReset (slock #11383) starts the new
                                    // server on its last Activity filter (All when none).
                                    filter = rememberedFilter(serverId.value)
                                    SharedActivityState(selectedFilter = filter)
                                } else if (stateBeforeOwner.isUnboundStart()) {
                                    // The first load binds the store: it opens on the server's
                                    // last Activity filter, not the default All.
                                    filter = rememberedFilter(serverId.value)
                                    stateBeforeOwner.copy(selectedFilter = filter)
                                } else {
                                    stateBeforeOwner
                                }
                            refreshLimit =
                                maxOf(
                                    SharedActivityPageSize,
                                    stateForOwner.nextOffset ?: 0,
                                    stateForOwner.items.size
                                )
                            reduceState(
                                intent,
                                stateForOwner.copy(
                                    ownerServerId = serverId.value,
                                    ownerUserId = userId
                                )
                            )
                            val stateBeforeRequest = _state.value
                            val stateWithLocalProfiles =
                                if (refresh && stateBeforeRequest.snapshot != null) {
                                    stateBeforeRequest.copy(
                                        senderProfiles = stateBeforeRequest.senderProfiles + currentSenderProfiles(serverId)
                                    )
                                } else {
                                    stateBeforeRequest
                                }
                            if (backgroundRefresh) {
                                reduceState(intent, stateWithLocalProfiles)
                            } else {
                                reduceState(
                                    intent,
                                    stateWithLocalProfiles.copy(
                                        loading = refresh && stateWithLocalProfiles.snapshot == null,
                                        refreshing = refresh && stateWithLocalProfiles.snapshot != null,
                                        headerRefreshAllowed = showsHeaderIndicator,
                                        pullRefreshing =
                                            refresh &&
                                                (pullRefresh || pullJoinedGeneration == generation) &&
                                                stateWithLocalProfiles.snapshot != null,
                                        loadingMore = !refresh,
                                        errorCode = null,
                                        loadMoreErrorCode = null
                                    )
                                )
                            }
                        }
                        // Hands b91f94e5: the rows never wait for the member list. It runs beside
                        // the inbox read on the store scope (a superseded load does not cancel the
                        // shared members flight); on a server switch it took 11s and held the new
                        // server's rows behind the old server's cached ones.
                        val senderProfilesLoad = scope.async { loadSenderProfiles(serverId) }
                        var partialFailureCode: String? = null
                        val result =
                            if (refresh) {
                                val depth =
                                    loadCanonicalDepth(serverId, filter, refreshLimit, fetchReason, fetchTrigger) { fetched ->
                                        publishRefreshPage(
                                            fetched = fetched,
                                            generation = generation,
                                            serverId = serverId,
                                            userId = userId,
                                            server = server,
                                            senderProfilesLoad = senderProfilesLoad,
                                            intent = intent,
                                            backgroundRefresh = backgroundRefresh,
                                            pullRefresh = pullRefresh,
                                            durationMs = startedAt.elapsedNow().inWholeMilliseconds
                                        )
                                    }
                                partialFailureCode = depth.partialFailureCode
                                depth.result
                            } else {
                                inboxRepository.loadMore(
                                    serverId,
                                    filter,
                                    previous.nextOffset ?: previous.items.size,
                                    SharedActivityPageSize
                                )
                            }
                        // task #302: guard on identity (user + server), not the access
                        // token. A 401 inside loadCanonicalDepth triggers a token
                        // rotation and the retry already returns fresh data; comparing
                        // tokens here discards that successful result as stale_session
                        // and leaves the visible feed on the pre-refresh snapshot.
                        val currentSession = authStore.readSession()
                        val requestSessionStillCurrent =
                            currentSession != null &&
                                currentSession.user.id == userId &&
                                currentSession.currentServerId == serverId.value
                        if (!requestSessionStillCurrent) {
                            synchronized(realtimeOwnerLock) {
                                if (generation == loadGeneration && !backgroundRefresh) {
                                    val current = _state.value
                                    reduceState(
                                        intent,
                                        current.copy(
                                            loading = false,
                                            refreshing = false,
                                            pullRefreshing = false,
                                            loadingMore = false
                                        )
                                    )
                                }
                            }
                            logActivityLoad(
                                result = "stale_session",
                                code = null,
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        val readySenderProfiles = if (senderProfilesLoad.isCompleted) senderProfilesLoad.await() else null
                        val applied =
                            synchronized(realtimeOwnerLock) {
                                if (generation != loadGeneration) {
                                    false
                                } else {
                                    // The owner was reset above when the server changed, so the
                                    // fallback is this server's profiles known so far.
                                    val senderProfiles =
                                        readySenderProfiles
                                            ?: (_state.value.senderProfiles + currentSenderProfiles(serverId))
                                    applyLoadResult(
                                        result = result,
                                        server = server,
                                        append = !refresh,
                                        senderProfiles = senderProfiles,
                                        intent = intent,
                                        backgroundRefresh = backgroundRefresh,
                                        // A later page failed: the rows below the fetched ones stay
                                        // (the list does not shrink), the refresh is not an error.
                                        keepUnfetchedTail = partialFailureCode != null
                                    )
                                    if (refresh && result is DataResult.Content && result.data.staleReason == null) {
                                        lastRowsReadAtEpochMillis = nowEpochMillis()
                                    }
                                    true
                                }
                            }
                        if (!applied) {
                            logActivityLoadAbandoned(
                                reason = "superseded_before_apply",
                                generation = generation,
                                pullRefresh = pullRefresh,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                            return@launch
                        }
                        if (readySenderProfiles == null) {
                            applyLateSenderProfiles(intent, serverId, userId, senderProfilesLoad)
                        }
                        ensureDmPeerNames()
                        logActivityLoad(
                            result = if (partialFailureCode != null) "content_partial" else result.activityDiagnosticsResult(),
                            code = partialFailureCode,
                            refresh = refresh,
                            append = !refresh,
                            filter = filter,
                            backgroundRefresh = backgroundRefresh,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                            generation = generation,
                            pullRefresh = pullRefresh
                        )
                    } catch (cancellation: CancellationException) {
                        logActivityLoadAbandoned(
                            reason = "cancelled",
                            generation = generation,
                            pullRefresh = pullRefresh,
                            backgroundRefresh = backgroundRefresh,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds
                        )
                        throw cancellation
                    } catch (_: Throwable) {
                        val handled =
                            synchronized(realtimeOwnerLock) {
                                if (generation != loadGeneration) {
                                    false
                                } else {
                                    if (!backgroundRefresh) setLoadError("activity_network", append = !refresh)
                                    // A failed read ends the realtime catch-up (web parity, slock #11208).
                                    if (refresh) resetRealtimeCatchUpLocked()
                                    true
                                }
                            }
                        if (!handled) {
                            logActivityLoadAbandoned(
                                reason = "superseded_before_error",
                                generation = generation,
                                pullRefresh = pullRefresh,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                            return@launch
                        }
                        logActivityLoad(
                            result = "exception",
                            code = "activity_network",
                            refresh = refresh,
                            append = !refresh,
                            filter = filter,
                            backgroundRefresh = backgroundRefresh,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                            generation = generation,
                            pullRefresh = pullRefresh
                        )
                    } finally {
                        synchronized(realtimeOwnerLock) {
                            if (progressiveTailGeneration == generation) progressiveTailGeneration = null
                            if (pullJoinedGeneration == generation) pullJoinedGeneration = null
                            if (backgroundRefresh && generation == loadGeneration && _state.value.backgroundRefreshing) {
                                _state.update { state -> state.copy(backgroundRefreshing = false) }
                            }
                        }
                    }
                }.also { loadJob = it }
            }
        job?.start()
    }

    /**
     * Shows the rows a refresh has read so far while it reads the next page (Hands f6c3f3a6): the
     * first page (totals, unread counts, the head of the list) lands at once and ends the
     * refresh / pull spinner; the rows below it stay as they were until their page arrives
     * ([applyCanonicalLoadResult] keepUnfetchedTail). The last page replaces the whole list with
     * the full served order, so rows that moved out of the first page resolve there. A remembered
     * (`X-Raft-Stale`) first page is not shown early: the final answer decides as before.
     */
    private suspend fun publishRefreshPage(
        fetched: InboxSnapshot,
        generation: Long,
        serverId: ServerId,
        userId: String,
        server: ServerSummary,
        senderProfilesLoad: Deferred<Map<String, SharedActivitySenderProfile>>,
        intent: SharedActivityIntent,
        backgroundRefresh: Boolean,
        pullRefresh: Boolean,
        durationMs: Long
    ) {
        if (fetched.staleReason != null) return
        val session = authStore.readSession()
        if (session == null || session.user.id != userId || session.currentServerId != serverId.value) return
        val readySenderProfiles = if (senderProfilesLoad.isCompleted) senderProfilesLoad.await() else null
        val published =
            synchronized(realtimeOwnerLock) {
                if (generation != loadGeneration) return@synchronized false
                val senderProfiles =
                    readySenderProfiles ?: (_state.value.senderProfiles + currentSenderProfiles(serverId))
                applyLoadResult(
                    result = DataResult.Content(fetched),
                    server = server,
                    append = false,
                    senderProfiles = senderProfiles,
                    intent = intent,
                    backgroundRefresh = backgroundRefresh,
                    keepUnfetchedTail = true,
                    settle = false
                )
                progressiveTailGeneration = generation
                lastRowsReadAtEpochMillis = nowEpochMillis()
                true
            }
        if (!published) return
        diagnosticsLogger(
            "store_load domain=activity phase=page_published fetched=${fetched.items.size} " +
                "shown=${_state.value.items.size} unread=${_state.value.totalUnreadCount} durationMs=$durationMs " +
                "generation=$generation background=$backgroundRefresh pullRefresh=$pullRefresh " +
                "pullRefreshingAfter=${_state.value.pullRefreshing}"
        )
    }

    /**
     * Hands f6c3f3a6 (iOS 1.17.0): a refresh re-reads as many rows as are loaded (270 rows = three
     * sequential 100-row pages, ~1.2-1.9 s each) and used to publish only after the last one, so
     * the pull spinner and the list looked stuck for 5-6 s, and a later page cancelled by the app
     * leaving the foreground (`http_0`) threw the pages already fetched away.
     *
     * [onPageMerged] receives the rows fetched so far after every page that is not the last
     * (offset 0 first: it carries the totals and unread counts), so the caller can publish them
     * at once. A later page that fails, stalls or throws ends the read with what was fetched:
     * [ActivityCanonicalDepthResult.partialFailureCode] names it and the result stays Content.
     * Only the first page's failure fails the read.
     */
    private suspend fun loadCanonicalDepth(
        serverId: ServerId,
        filter: InboxFilter,
        depth: Int,
        fetchReason: InboxFetchReason? = null,
        fetchTrigger: InboxRefreshTriggerCount? = null,
        onPageMerged: suspend (InboxSnapshot) -> Unit = {}
    ): ActivityCanonicalDepthResult {
        val targetDepth = depth.coerceAtLeast(SharedActivityPageSize)
        val syncReadTicket = syncReloads?.beginListRead(serverId.value, RaftSyncListRead.Activity)
        val initial =
            try {
                withInboxFetchReason(fetchReason, fetchTrigger) {
                    inboxRepository.loadInitial(serverId, filter, minOf(targetDepth, SharedActivityRefreshPageLimit))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                syncReadTicket?.let { syncReloads?.endListRead(serverId.value, RaftSyncListRead.Activity, it, RaftSyncSnapshotRead.Failed) }
                throw throwable
            }
        syncReadTicket?.let { syncReloads?.endListRead(serverId.value, RaftSyncListRead.Activity, it, initial.syncSnapshotRead()) }
        if (initial !is DataResult.Content) return ActivityCanonicalDepthResult(initial)

        var combined = initial.data.copy(items = initial.data.items.dedupeActivityItems())
        val seenOffsets = mutableSetOf<Int>()
        val maxContinuationRequests = targetDepth.toLong() + SharedActivityRefreshContinuationSlack
        var continuationRequests = 0
        var consecutiveNoProgressPages = 0
        fun partial(code: String) = ActivityCanonicalDepthResult(DataResult.Content(combined), partialFailureCode = code)
        while (combined.items.size < targetDepth && combined.hasMore) {
            val offset = combined.nextOffset ?: combined.items.size
            if (!seenOffsets.add(offset) || continuationRequests.toLong() >= maxContinuationRequests) {
                return partial("activity_pagination_stalled")
            }
            onPageMerged(combined)
            continuationRequests += 1
            val remaining = targetDepth - combined.items.size
            val page =
                try {
                    inboxRepository.loadMore(
                        serverId,
                        filter,
                        offset,
                        minOf(remaining, SharedActivityRefreshPageLimit)
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return partial("activity_network")
                }
            when (page) {
                is DataResult.Content -> {
                    val inferredNextOffset = offset.toLong() + page.data.items.size.toLong()
                    val nextOffset = page.data.nextOffset ?: inferredNextOffset.takeIf { it <= Int.MAX_VALUE }?.toInt()
                    // Offset pages are read one after another, so a row that moved up between two
                    // reads comes again on the later page: the first (newer) position wins, the
                    // same key merge as an append (dedupeActivityItems).
                    val mergedItems = (combined.items + page.data.items).dedupeActivityItems()
                    // A page can be empty after the repository projects an
                    // unfollowed row out of an attention filter.  The raw
                    // server cursor still advanced, so that page is real
                    // pagination progress even though no visible item was
                    // appended.
                    val rawPageAdvancedWithNoVisibleRows =
                        page.data.items.isEmpty() && nextOffset != null && nextOffset > offset
                    consecutiveNoProgressPages =
                        if (mergedItems.size == combined.items.size && !rawPageAdvancedWithNoVisibleRows) {
                            consecutiveNoProgressPages + 1
                        } else {
                            0
                        }
                    val invalidNonTerminalPage =
                        page.data.hasMore &&
                            (nextOffset == null || nextOffset <= offset)
                    val exhaustedNoProgressBudget =
                        page.data.hasMore &&
                            mergedItems.size < targetDepth &&
                            consecutiveNoProgressPages > SharedActivityRefreshMaxNoProgressPages
                    if (invalidNonTerminalPage || exhaustedNoProgressBudget) {
                        return partial("activity_pagination_stalled")
                    }
                    combined =
                        page.data.copy(
                            items = mergedItems,
                            nextOffset = nextOffset
                        )
                }
                is DataResult.Empty -> {
                    combined = combined.copy(hasMore = false, nextOffset = null)
                    break
                }
                is DataResult.Error -> return partial(page.error.code)
                is DataResult.NoPermission -> return partial(page.blocker.code)
            }
        }
        return ActivityCanonicalDepthResult(DataResult.Content(combined))
    }

    private fun logActivityLoad(
        result: String,
        code: String?,
        refresh: Boolean,
        append: Boolean,
        filter: InboxFilter,
        backgroundRefresh: Boolean = false,
        durationMs: Long,
        generation: Long,
        pullRefresh: Boolean
    ) {
        diagnosticsLogger(
            "store_load domain=activity refresh=$refresh background=$backgroundRefresh append=$append filter=${filter.name} " +
                "result=$result code=${code.orEmpty()} durationMs=$durationMs " +
                "generation=$generation pullRefresh=$pullRefresh pullRefreshingAfter=${_state.value.pullRefreshing}"
        )
    }

    /**
     * task #436: a load that never reaches a terminal branch previously left no
     * trace at all, so "no line" could not be told apart from "never started".
     *
     * Every post-start exit must emit exactly one terminal or abandoned line.
     * An unlogged exit is worse than no instrumentation: after a start line,
     * silence is the stuck signature, so a silent return makes this diagnostic
     * manufacture the false positive it exists to rule out.
     *
     * These lines are positive evidence only. A missing line means "not
     * observed" — delivery crosses two bounded queues that drop silently — and
     * never proves the load failed to terminate.
     */
    private fun logActivityLoadStart(
        refresh: Boolean,
        pullRefresh: Boolean,
        backgroundRefresh: Boolean,
        headerIndicator: Boolean,
        filter: InboxFilter,
        generation: Long
    ) {
        diagnosticsLogger(
            "store_load domain=activity phase=start refresh=$refresh background=$backgroundRefresh " +
                "pullRefresh=$pullRefresh headerIndicator=$headerIndicator filter=${filter.name} generation=$generation " +
                "pullRefreshingBefore=${_state.value.pullRefreshing}"
        )
    }

    private fun logActivityLoadAbandoned(
        reason: String,
        generation: Long,
        pullRefresh: Boolean,
        backgroundRefresh: Boolean,
        durationMs: Long
    ) {
        diagnosticsLogger(
            "store_load domain=activity phase=abandoned reason=$reason generation=$generation " +
                "currentGeneration=$loadGeneration pullRefresh=$pullRefresh background=$backgroundRefresh " +
                "durationMs=$durationMs pullRefreshingAfter=${_state.value.pullRefreshing}"
        )
    }

    private fun applyLoadResult(
        result: DataResult<InboxSnapshot>,
        server: ServerSummary,
        append: Boolean,
        senderProfiles: Map<String, SharedActivitySenderProfile>,
        intent: SharedActivityIntent,
        backgroundRefresh: Boolean = false,
        keepUnfetchedTail: Boolean = false,
        settle: Boolean = true
    ) {
        val current = _state.value
        // Task #164: the server's remembered page (at most 5 minutes old) never replaces rows this
        // filter already shows; it only fills a list that has nothing yet.
        val keepShownOverStale =
            !append && result is DataResult.Content && result.data.staleReason != null && current.snapshot != null
        val projectedContentData =
            (result as? DataResult.Content)?.data?.projectActivityFilter(current.selectedFilter)
        val owner = overlayOwner(current)
        val nextState =
            when {
                keepShownOverStale ->
                    current.copy(
                        loaded = true,
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        loadMoreErrorCode = null,
                        stale = true
                    )
                else -> applyCanonicalLoadResult(
                    result = result,
                    current = current,
                    projectedContentData = projectedContentData,
                    owner = owner,
                    server = server,
                    append = append,
                    senderProfiles = senderProfiles,
                    backgroundRefresh = backgroundRefresh,
                    keepUnfetchedTail = keepUnfetchedTail
                )
            }
        acceptCanonicalReplyThreadFacts(nextState)
        val freshAnswer = !keepShownOverStale && (result as? DataResult.Content)?.data?.staleReason == null
        reduceState(
            intent = intent,
            nextState = nextState,
            summaryAuthoritative =
                !append &&
                    freshAnswer &&
                    current.selectedFilter == InboxFilter.All &&
                    (result is DataResult.Content || result is DataResult.Empty),
            summaryFactCounts =
                if (!append && freshAnswer && current.selectedFilter == InboxFilter.All) {
                    when (result) {
                        // The app badge / Home summary gets exactly the counts the
                        // snapshot shows: overlay-suppressed rows subtracted and the
                        // realtime unread floor respected, so totals do not bounce.
                        is DataResult.Content -> nextState.totalUnreadCount to nextState.totalCount
                        is DataResult.Empty -> 0 to 0
                        is DataResult.Error,
                        is DataResult.NoPermission -> null
                    }
                } else {
                    null
                },
            summarySource = "activity_canonical:${current.selectedFilter.wireValue}"
        )
        if (!append && settle) {
            settleStaleRecovery(nextState, result)
            settleRealtimeCatchUpLocked(nextState, result)
        }
    }

    private fun raiseRealtimeCatchUpTargetLocked(message: Message) {
        // Only a message on a channel/DM row this list shows. A thread or task reply carries its
        // own conversation as `channelId` (web skips channelType thread/task before
        // noteInboxCatchUpTarget, inboxStore.ts:2117-2122); as a target it would name no served
        // channel row, read as "not behind" and end the catch-up of the channel just before it.
        val contextKind = message.conversationContext?.channelKind
        if (contextKind == ConversationKind.Thread || contextKind == ConversationKind.Task) return
        if (message.conversationContext?.parentMessageId != null || message.parentMessageId != null) return
        val channelId = message.channelId ?: return
        val shown = _state.value.snapshot?.items.orEmpty()
        if (shown.none { item -> item.isChannelOrDmRow() && item.channelId == channelId }) return
        val incoming = message.seq?.takeIf { it >= 0L }?.toString() ?: return
        val target = realtimeCatchUpTarget
        if (target != null && compareActivitySeq(incoming, target.seq) <= 0) return
        realtimeCatchUpTarget = ActivityRealtimeCatchUpTarget(channelId, incoming)
        realtimeCatchUpRetries = 0
    }

    private fun resetRealtimeCatchUpLocked() {
        realtimeCatchUpRetryJob?.cancel()
        realtimeCatchUpRetryJob = null
        realtimeCatchUpTarget = null
        realtimeCatchUpRetries = 0
    }

    /**
     * #triage 84d44d75 (web `settleInboxCatchUp`, slock #11208): `GET /channels/inbox` lags the
     * write by ~1-2 s, so the read a `message:new` triggers can still serve that channel's row one
     * message behind. While a clean answer serves the target channel's row behind the target seq,
     * the list is re-read after [ActivityRealtimeCatchUpRetryDelaysMillis] (at most twice), each
     * through the realtime throttle, then the catch-up stops. A row the answer does not contain is
     * not behind (the filter may leave it out). A stale (`X-Raft-Stale`) or failed answer, 503
     * `rw_overloaded` included, ends it: [settleStaleRecovery] then re-reads at Retry-After pace.
     */
    private fun settleRealtimeCatchUpLocked(nextState: SharedActivityState, result: DataResult<InboxSnapshot>) {
        val target = realtimeCatchUpTarget ?: return
        val servedRow =
            (result as? DataResult.Content)
                ?.takeIf { !nextState.stale }
                ?.data?.items
                ?.firstOrNull { item -> item.isChannelOrDmRow() && item.channelId == target.channelId }
        val servedSeq = servedRow?.latestActivitySeq
        val behind = servedRow != null && compareActivitySeq(servedSeq, target.seq) < 0
        if (!behind || realtimeCatchUpRetries >= ActivityRealtimeCatchUpRetryDelaysMillis.size) {
            if (behind || nextState.stale || result !is DataResult.Content) {
                diagnosticsLogger(
                    "store_load domain=activity phase=catch_up_end target=${target.seq} " +
                        "behind=$behind stale=${nextState.stale} retries=$realtimeCatchUpRetries"
                )
            }
            resetRealtimeCatchUpLocked()
            return
        }
        if (realtimeCatchUpRetryJob != null) return
        val waitMillis = ActivityRealtimeCatchUpRetryDelaysMillis[realtimeCatchUpRetries]
        realtimeCatchUpRetries += 1
        diagnosticsLogger(
            "store_load domain=activity phase=catch_up_scheduled target=${target.seq} served=$servedSeq " +
                "attempt=$realtimeCatchUpRetries waitMs=$waitMillis"
        )
        realtimeCatchUpRetryJob =
            scope.launch {
                delay(waitMillis)
                val self = currentCoroutineContext()[Job]
                val reconcileJob =
                    synchronized(realtimeOwnerLock) {
                        if (realtimeCatchUpRetryJob !== self) return@synchronized null
                        realtimeCatchUpRetryJob = null
                        if (activitySurfaceHidden) {
                            null
                        } else {
                            scheduleRealtimeBackgroundReconcileLocked(InboxRefreshTrigger.CatchUpRetry)
                        }
                    }
                reconcileJob?.start()
            }
    }

    /**
     * Task #164: while the shown Activity is stale, one background recovery read is armed (backoff,
     * see [RaftStaleReadRecovery]); a fresh canonical answer (Content or Empty) disarms it. Other
     * failures change nothing: they are not the read warehouse.
     */
    private fun settleStaleRecovery(nextState: SharedActivityState, result: DataResult<InboxSnapshot>) {
        when {
            nextState.stale -> {
                val retryAfterMillis =
                    (result as? DataResult.Content)?.data?.staleRetryAfterMillis
                        ?: (result as? DataResult.Error)?.error?.rwRetryAfterMillis
                staleRecovery.schedule(retryAfterMillis) { recoverStaleActivity() }
            }
            result is DataResult.Content || result is DataResult.Empty -> staleRecovery.reset()
        }
    }

    /** Returns whether a load runs whose answer settles recovery. */
    private fun recoverStaleActivity(): Boolean {
        val current = _state.value
        if (!current.stale) return false
        if (activitySurfaceHidden) {
            // Entering Activity always refreshes; that read settles recovery.
            diagnosticsLogger("store_load domain=activity phase=stale_recovery_skip reason=surface_hidden")
            return false
        }
        // Single-flight: a background load joins nothing while another load runs (that one settles).
        load(
            refresh = true,
            pullRefresh = false,
            backgroundRefresh = true,
            intent = SharedActivityIntent.Refresh,
            fetchReason = InboxFetchReason.RealtimeFallback,
            // A stale answer's recovery, not a realtime event (no shared trigger value).
            fetchTrigger = InboxRefreshTriggerCount(InboxRefreshTrigger.Other)
        )
        return true
    }

    private fun applyCanonicalLoadResult(
        result: DataResult<InboxSnapshot>,
        current: SharedActivityState,
        projectedContentData: InboxSnapshot?,
        owner: ActivityOverlayOwner?,
        server: ServerSummary,
        append: Boolean,
        senderProfiles: Map<String, SharedActivitySenderProfile>,
        backgroundRefresh: Boolean,
        keepUnfetchedTail: Boolean = false
    ): SharedActivityState =
            when (result) {
                is DataResult.Content -> {
                    val projectedData = projectedContentData
                        ?: error("content projection missing")
                    val pageItems =
                        projectedData.items
                            .withActivityThreadTaskProjection(result.data.server.id.value, tasksDataStore)
                            .reconcileLocallyClearedUnreadItems(current.selectedFilter, owner)
                            .reconcileLocallyDoneItems(owner)
                    // A remembered (`X-Raft-Stale`) answer can be minutes old: it judges nothing.
                    if (!append && result.data.staleReason == null) checkInPlaceDriftLocked(current, pageItems.items)
                    beforeRealtimeHighWaterFold()
                    val realtimeReconcile =
                        reconcileRealtimeHighWater(
                            canonicalItems = pageItems.items,
                            append = append,
                            preservePendingRealtimeUnread = backgroundRefresh,
                            // Rows below the fetched pages were not answered yet: their realtime
                            // high-water waits for the page that serves them.
                            pruneUnlisted = !keepUnfetchedTail
                        )
                    val fetchedItems =
                        if (append) {
                            (current.items + realtimeReconcile.items).dedupeActivityItems()
                        } else {
                            realtimeReconcile.items.withRetainedReadRows(current).dedupeActivityItems()
                        }
                    // Hands f6c3f3a6: a refresh shown page by page keeps the rows it has not read
                    // yet below the fetched ones, in their shown order; a fetched row is listed
                    // once, at its served position.
                    val unfetchedTail =
                        if (append || !keepUnfetchedTail) {
                            emptyList()
                        } else {
                            val fetchedKeys = fetchedItems.mapTo(mutableSetOf(), InboxItem::key)
                            current.items.filterNot { item -> item.key in fetchedKeys }
                        }
                    val items =
                        (if (unfetchedTail.isEmpty()) fetchedItems else (fetchedItems + unfetchedTail).dedupeActivityItems())
                            // RFC 077: a re-fetched row of the same latest message without
                            // `latestDelegatedBy` keeps the known label.
                            .withKnownLatestDelegatedLabelsFrom(current.items)
                    val adjustedCanonicalUnreadCount =
                        maxOf(
                            0,
                            projectedData.totalUnreadCount -
                                pageItems.suppressedUnreadCount -
                                pageItems.suppressedDoneUnreadCount
                        )
                    val canonicalUnreadCount =
                        if (realtimeReconcile.preserveTotalUnreadFloor) {
                            maxOf(current.totalUnreadCount, adjustedCanonicalUnreadCount)
                        } else {
                            adjustedCanonicalUnreadCount
                        }
                    val nextState =
                        current.copy(
                            snapshot =
                                result.data.copy(
                                    items = items,
                                    totalCount =
                                        if (current.selectedFilter == InboxFilter.Unread) {
                                            maxOf(0, projectedData.totalCount - pageItems.removedUnreadRows - pageItems.removedDoneRows)
                                        } else {
                                            maxOf(0, projectedData.totalCount - pageItems.removedDoneRows)
                                        },
                                    totalUnreadCount =
                                        canonicalUnreadCount,
                                    hasMore =
                                        result.data.hasMore || (unfetchedTail.isNotEmpty() && current.hasMore),
                                    // The kept rows below were read up to the shown offset; a
                                    // LoadMore continues after them (an overlap dedupes by key).
                                    nextOffset =
                                        if (unfetchedTail.isEmpty()) {
                                            result.data.nextOffset
                                        } else {
                                            listOfNotNull(result.data.nextOffset, current.nextOffset).maxOrNull()
                                        }
                                ),
                            senderProfiles = if (append) current.senderProfiles + senderProfiles else senderProfiles,
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = null,
                            loadMoreErrorCode = null,
                            // A page appended after the first is never remembered by the server.
                            stale = if (append) current.stale else result.data.staleReason != null,
                            unavailable = if (append) current.unavailable else false
                        )
                    nextState
                }
                is DataResult.Empty -> {
                    if (!append) checkInPlaceDriftLocked(current, emptyList())
                    val realtimeReconcile =
                        if (!append) {
                            reconcileRealtimeHighWater(
                                canonicalItems = emptyList(),
                                append = false,
                                preservePendingRealtimeUnread = backgroundRefresh
                            )
                        } else {
                            ActivityRealtimeReconcileResult(items = emptyList())
                        }
                    if (!append && !realtimeReconcile.preserveTotalUnreadFloor) {
                        realtimeHighWater.clear()
                    }
                    val snapshot =
                        if (append) {
                            current.snapshot?.copy(hasMore = false, nextOffset = null)
                                ?: InboxSnapshot(server = server, items = emptyList())
                        } else {
                            InboxSnapshot(
                                server = server,
                                items = realtimeReconcile.items.withRetainedReadRows(current),
                                totalCount =
                                    if (realtimeReconcile.preserveTotalUnreadFloor) {
                                        maxOf(current.totalCount, realtimeReconcile.items.size)
                                    } else {
                                        0
                                    },
                                totalUnreadCount =
                                    if (realtimeReconcile.preserveTotalUnreadFloor) {
                                        current.totalUnreadCount
                                    } else {
                                        0
                                    }
                            )
                        }
                    val nextState =
                        current.copy(
                            snapshot = snapshot,
                            senderProfiles =
                                if (append || realtimeReconcile.preserveTotalUnreadFloor) {
                                    current.senderProfiles + senderProfiles
                                } else {
                                    senderProfiles
                                },
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = null,
                            loadMoreErrorCode = null,
                            stale = if (append) current.stale else false,
                            unavailable = if (append) current.unavailable else false
                        )
                    nextState
                }
                is DataResult.Error -> {
                    // Task #164: an overload while rows are shown keeps them and marks them stale;
                    // with nothing shown the page keeps its load-failed state with Retry.
                    val stale = current.stale || (!append && result.error.isRwOverloaded && current.snapshot != null)
                    // botiverse/slock #12066: a server without the read warehouse (`503 rw_unconfigured`)
                    // will not catch up on its own; the page says Activity is temporarily unavailable.
                    val unavailable = if (append) current.unavailable else result.error.isRwUnconfigured
                    if (backgroundRefresh) {
                        current.copy(stale = stale, unavailable = unavailable)
                    } else {
                        current.copy(
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = if (append) null else result.error.code,
                            loadMoreErrorCode = if (append) result.error.code else null,
                            stale = stale,
                            unavailable = unavailable
                        )
                    }
                }
                is DataResult.NoPermission ->
                    if (backgroundRefresh) {
                        current
                    } else {
                        current.copy(
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = if (append) null else result.blocker.code,
                            loadMoreErrorCode = if (append) result.blocker.code else null
                        )
                    }
            }

    private fun acceptCanonicalReplyThreadFacts(nextState: SharedActivityState) {
        val serverId = nextState.ownerServerId ?: return
        nextState.items.forEach { item ->
            val key = item.replyThreadPrewarmKey(serverId) ?: return@forEach
            replyThreadFreshnessLedger.acceptSummary(
                key.serverId,
                key.threadChannelId,
                item.replyThreadSummaryFact()
            )
        }
    }

    private fun reconcileRealtimeHighWater(
        canonicalItems: List<InboxItem>,
        append: Boolean,
        preservePendingRealtimeUnread: Boolean,
        pruneUnlisted: Boolean = true
    ): ActivityRealtimeReconcileResult {
        if (append || realtimeHighWater.isEmpty()) {
            return ActivityRealtimeReconcileResult(items = canonicalItems)
        }
        val currentThreads =
            _state.value.items
                .asSequence()
                .filter { item -> item.kind == InboxItemKind.Thread }
                .associateBy { item -> item.key }
        val currentChannels =
            _state.value.items
                .asSequence()
                .filter { item -> item.isChannelOrDmRow() }
                .associateBy { item -> item.key }
        val keptChannelKeys = mutableSetOf<String>()
        // Thread rows whose served copy lags the realtime patch (they keep the patched reply).
        val keptThreadKeys = mutableSetOf<String>()
        val canonicalKeys = canonicalItems.mapTo(mutableSetOf()) { item -> item.key }
        var preserveTotalUnreadFloor = false
        val reconciled =
            canonicalItems.map { canonical ->
                val localVersion = realtimeHighWater[canonical.key] ?: return@map canonical
                if (canonical.isChannelOrDmRow()) {
                    val reconciledRow = reconcileRealtimeChannelRow(canonical, localVersion, currentChannels[canonical.key])
                    if (reconciledRow !== canonical) {
                        keptChannelKeys += canonical.key
                        // The kept unread is not in the answer's total yet.
                        if (localVersion.inPlaceUnread) preserveTotalUnreadFloor = true
                    }
                    return@map reconciledRow
                }
                val current = currentThreads[canonical.key]
                // Past the stale-list bound the answer is not lag any more: it wins (web
                // preserveNewerThreadActivity `until`, slock #14247).
                val holdLapsed = localVersion.holdUntilMillis?.let { until -> nowMillis() > until } ?: false
                if (current == null || holdLapsed) {
                    realtimeHighWater.remove(canonical.key)
                    return@map canonical
                }
                // A canonical row that reached (or passed) the local patch wins
                // outright, unread included: patches never invent unread, so
                // there is no local unread to preserve (web takes the incoming
                // row, preserveNewerThreadActivity, inboxStore.ts:813-870).
                // A canonical row whose latest activity is strictly newer (by seq) than the shown
                // row's also wins. A `thread:updated`-only patch (a joint channel's projected
                // thread, whose `message:new` arrives under the origin thread id and is deduped)
                // moves the count and time but keeps the old preview and marker, so the
                // count/marker rule alone never let the served row back in (fb-c04c1199: the row
                // kept a 03:44 reply with a fresh time while newer replies, the reader's own
                // included, were served).
                if (
                    canonical.replyCount > localVersion.replyCount ||
                    (
                        canonical.replyCount == localVersion.replyCount &&
                            canonical.latestActivityMessageId == localVersion.marker
                        ) ||
                    canonical.servesNewerActivityThan(current) ||
                    canonical.reachesRealtimeSeq(localVersion)
                ) {
                    realtimeHighWater.remove(canonical.key)
                    return@map canonical
                }
                preserveTotalUnreadFloor = true
                keptThreadKeys += canonical.key
                canonical.withRealtimeActivityProjection(current).let { held ->
                    if (localVersion.inPlaceUnread) held.withUnreadOf(current) else held
                }
            }
        val missingCurrent =
            if (preservePendingRealtimeUnread) {
                currentThreads.values.filter { current ->
                    current.key !in canonicalKeys && current.key in realtimeHighWater
                }
            } else {
                emptyList()
            }
        if (missingCurrent.isNotEmpty()) preserveTotalUnreadFloor = true
        realtimeHighWater.keys.toList().forEach { key ->
            if (
                pruneUnlisted &&
                key !in canonicalKeys &&
                (!preservePendingRealtimeUnread || key !in currentThreads)
            ) {
                realtimeHighWater.remove(key)
            }
        }
        return ActivityRealtimeReconcileResult(
            // A serving-row projection can lag the message event that produced
            // the local high-water. Keep that row in its current position until
            // a canonical response carries the same marker; otherwise the UI
            // exposes an appear→disappear flash between the two stages.
            items =
                (missingCurrent + reconciled).dedupeActivityItems().let { items ->
                    // A kept row holds the newest message, which the served order (the view's,
                    // without it) does not know: it stays on top instead of dropping back (web
                    // preserveNewerChannelActivity / preserveNewerThreadActivity). Rows held from
                    // realtime (kept channel rows, kept thread rows and missing thread rows) keep
                    // the order they are shown in now, so a stale answer never flips them.
                    // Hands 62498f69: a thread row patched to the top dropped back under the old
                    // head on the lagging re-read and returned when the server caught up, so the
                    // first row alternated between the two.
                    // A held row goes back above the served rows its time is newer than, not above
                    // all of them: a row held after newer activity reached other rows sat over
                    // them, older rows on top (artin, 1.23.0: three 25-60 min threads over
                    // "just now" rows).
                    if (keptChannelKeys.isEmpty() && keptThreadKeys.isEmpty()) {
                        items
                    } else {
                        val shownOrder = _state.value.items.withIndex().associate { (index, item) -> item.key to index }
                        val heldKeys = keptChannelKeys + keptThreadKeys + missingCurrent.map(InboxItem::key)
                        val held = items.filter { it.key in heldKeys }.sortedBy { shownOrder[it.key] ?: Int.MAX_VALUE }
                        activityRowsWithHeldRowsAtTheirTime(served = items.filterNot { it.key in heldKeys }, held = held)
                    }
                },
            preserveTotalUnreadFloor = preserveTotalUnreadFloor
        )
    }

    /**
     * A channel/DM row patched from `message:new` keeps its newer message until the server's row
     * reaches that seq or the stale-list bound ([ActivityLocalMutationHoldMillis]) lapses (web
     * `preserveNewerChannelActivity`, slock #11208). Only the message fields come from the patch:
     * unread, mention and every other server fact are the answer's.
     */
    private fun reconcileRealtimeChannelRow(
        canonical: InboxItem,
        localVersion: ActivityRealtimeHighWater,
        current: InboxItem?
    ): InboxItem {
        val localSeq = localVersion.seq
        val holdLapsed = localVersion.holdUntilMillis?.let { until -> nowMillis() > until } ?: true
        if (
            current == null ||
            localSeq == null ||
            current.latestActivitySeq != localSeq ||
            holdLapsed ||
            canonical.latestActivitySeq == null ||
            compareActivitySeq(canonical.latestActivitySeq, localSeq) >= 0
        ) {
            realtimeHighWater.remove(canonical.key)
            return canonical
        }
        return canonical.copy(
            lastMessageId = current.lastMessageId,
            lastMessagePreview = current.lastMessagePreview,
            lastMessageAt = current.lastMessageAt,
            lastMessageSenderName = current.lastMessageSenderName,
            lastMessageSenderId = current.lastMessageSenderId,
            lastMessageSenderKind = current.lastMessageSenderKind,
            lastMessageSenderAvatarWireValue = current.lastMessageSenderAvatarWireValue,
            latestDelegatedBy = current.latestDelegatedBy,
            latestActivitySeq = current.latestActivitySeq
        ).let { held -> if (localVersion.inPlaceUnread) held.withUnreadOf(current) else held }
    }

    /** `mobile_inbox_inplace_v0`: a served row behind an in-place patch keeps the patched unread. */
    private fun InboxItem.withUnreadOf(current: InboxItem): InboxItem =
        copy(
            unreadCount = current.unreadCount,
            firstUnreadMessageId = current.firstUnreadMessageId,
            hasMention = current.hasMention,
            firstMentionMessageId = current.firstMentionMessageId
        )

    /**
     * This served row reached the reply a `message:new` patch showed: its seq is past it, or the
     * same reply (web preserveNewerThreadActivity decides by seq when both sides carry one). The
     * reply count does not decide then: a `thread:updated` counted before the `message:new` of the
     * same reply counts it twice, and the higher local count held the row forever.
     */
    private fun InboxItem.reachesRealtimeSeq(localVersion: ActivityRealtimeHighWater): Boolean {
        val local = localVersion.seq ?: return false
        val served = latestActivitySeq ?: return false
        val comparison = compareActivitySeq(served, local)
        return comparison > 0 || (comparison == 0 && latestActivityMessageId == localVersion.marker)
    }

    /** Both rows carry a content frontier and this (served) one is strictly past the shown one. */
    private fun InboxItem.servesNewerActivityThan(current: InboxItem): Boolean {
        val served = latestActivitySeq ?: return false
        val shown = current.latestActivitySeq ?: return false
        return compareActivitySeq(served, shown) > 0
    }

    private fun InboxItem.withRealtimeActivityProjection(current: InboxItem): InboxItem =
        // Until the canonical row reaches the same message/reply high-water, it
        // predates the local message fact. Preserve the locally-derived unread
        // range as part of that same atomic projection; otherwise the stale
        // snapshot briefly flips the row back to read before its successor.
        copy(
            latestActivityMessageId = current.latestActivityMessageId,
            latestActivityPreview = current.latestActivityPreview,
            latestActivityAt = current.latestActivityAt,
            latestActivitySenderName = current.latestActivitySenderName,
            latestActivitySenderId = current.latestActivitySenderId,
            latestActivitySenderKind = current.latestActivitySenderKind,
            latestActivitySenderAvatarWireValue = current.latestActivitySenderAvatarWireValue,
            latestDelegatedBy = current.latestDelegatedBy,
            replyCount = current.replyCount,
            unreadCount = current.unreadCount,
            firstUnreadMessageId = current.firstUnreadMessageId
        ).withActivityFollowStateProjection()

    private fun markAllRead() {
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        // A row marked unread with no count (botiverse/slock #11795) is read by it too.
        if (snapshot.totalUnreadCount <= 0 && snapshot.items.none(InboxItem::markedUnread)) return
        val keepsRows = previous.selectedFilter.keepsReadActivityRows()
        val updatedSnapshot =
            snapshot.copy(
                // The list does not change: every listed row stays, shown read, until the user
                // moves on. In Unread they all join the rows retained for this visit (stdrc 10-23).
                items =
                    snapshot.items.map {
                        it.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false, markedUnread = false)
                    },
                totalCount = if (keepsRows) snapshot.totalCount else 0,
                totalUnreadCount = 0
            )
        val retainedReadRowKeys =
            if (keepsRows) {
                previous.retainedReadRowKeys
            } else {
                snapshot.items.mapTo(previous.retainedReadRowKeys.toMutableSet(), InboxItem::key)
            }
        val overlayOwner = overlayOwner(previous)
        val readGenerations = rememberUnreadCleared(overlayOwner, snapshot.items)
        reduceState(
            SharedActivityIntent.MarkAllRead,
            previous.copy(snapshot = updatedSnapshot, errorCode = null, retainedReadRowKeys = retainedReadRowKeys)
        )
        scope.launch {
            withOverlaySettlement(readOverlay, overlayOwner, readGenerations) {
                val serverId = currentServerId()
                val result = serverId?.let { readReceiptRepository.markInboxReadAll(it) }
                    ?: DataResult.NoPermission(PermissionBlocker(code = "server_missing"))
                val owner =
                    readReceiptOwnerFactory
                        ?.invoke(previous.ownerServerId.orEmpty(), previous.ownerUserId.orEmpty())
                        ?.toReadStateV1ProductionOwner()
                var selected = false
                if (result is DataResult.Content && owner != null) {
                    result.data.scopes.forEach { ack ->
                        val disposition =
                            readStateV1Canary?.applyMutationAck(
                                owner = owner,
                                channelId = ack.channelId.value,
                                readStateVersion = ack.readStateVersion,
                                maxReadSeq = ack.maxReadSeq,
                            )
                        selected = selected || (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy)
                    }
                }
                if (selected || result is DataResult.Content || result is DataResult.Empty) {
                    readOverlay.confirmAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    // Web refreshes in the background after a successful read-all
                    // (slock 1bb70cbeb, inboxStore.ts:1405-1408); the read hold keeps a
                    // lagging copy from undoing the local clear.
                    load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
                } else {
                    readOverlay.forgetAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    refreshAfterFailure(previous, "activity_mark_all_read_failed")
                }
            }
        }
    }

    private fun markDone(requestedItem: InboxItem) {
        val previous = _state.value
        if (previous.snapshot == null) return
        val overlayOwner = overlayOwner(previous)
        if (requestedItem.kind == InboxItemKind.MentionAction) {
            // Local-only resolution: there is no server write and no newer marker
            // that could ever retire it, so it lives outside the expiring overlay
            // (a 90s hold would let the unresolved row come back).
            if (overlayOwner != null) rememberResolvedMentionAction(overlayOwner, requestedItem.key)
            removeItemOptimistically(requestedItem)
            return
        }
        // Web inboxStore.markDone (slock 1bb70cbeb, inboxStore.ts:1417-1470):
        // bind Done to the row this store currently holds and name its
        // storage-space frontier. A row from a server that predates
        // `doneFrontierSeq` takes the legacy body; a present-but-unusable
        // frontier (or a row no longer held) refreshes and writes nothing.
        val item = previous.items.firstOrNull { it.key == requestedItem.key }
        val frontier = item?.activityDoneRequestFrontier()?.toInboxDoneFrontier()
        if (item == null || frontier == null) {
            diagnosticsLogger(
                "activity_mark_done result=refused reason=${if (item == null) "row_not_held" else "invalid_done_frontier"} " +
                    "itemKey=${requestedItem.key}"
            )
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
            return
        }
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        // The overlay entry is pending until the server confirms the Done;
        // confirmation starts this row's own hold.
        val doneGenerations =
            overlayOwner
                ?.let { owner -> doneOverlay.remember(owner, item) }
                ?.let { generation -> mapOf(item.key to generation) }
                .orEmpty()
        removeItemOptimistically(item)
        scope.launch {
            withOverlaySettlement(doneOverlay, overlayOwner, doneGenerations) {
                val result =
                    if (item.kind == InboxItemKind.Thread) {
                        conversationRepository.markThreadDone(targetChannelId, frontier)
                    } else {
                        inboxRepository.markDone(targetChannelId, frontier)
                    }
                if (result !is DataResult.Content && result !is DataResult.Empty) {
                    // 409/412 (frontier moved / space required) land here too: like
                    // web, drop the hold and take the refreshed row; never replay.
                    doneOverlay.forgetAll(overlayOwner, doneGenerations)
                    refreshAfterFailure(previous, "activity_mark_done_failed")
                } else {
                    doneOverlay.confirmAll(overlayOwner, doneGenerations)
                    onUnreadCountsChanged()
                    // Web refreshes after a confirmed Done with the hold still armed.
                    load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
                }
            }
        }
    }

    /**
     * Done on an object row (web `markObjectRowDone`): out of the list at once, through its newest
     * event (`doneFrontierSeq`), held against a lagging list by the Done overlay; a failure brings it
     * back with a refresh. The object counts move with it.
     */
    private fun markObjectDone(requestedItem: InboxItem) {
        val previous = _state.value
        val item = previous.items.firstOrNull { it.key == requestedItem.key } ?: requestedItem
        val row = item.activityObject ?: return
        val serverId = previous.ownerServerId ?: previous.snapshot?.server?.id?.value ?: return
        val overlayOwner = overlayOwner(previous)
        val doneGenerations =
            overlayOwner
                ?.let { owner -> doneOverlay.remember(owner, item) }
                ?.let { generation -> mapOf(item.key to generation) }
                .orEmpty()
        removeObjectRowOptimistically(item)
        scope.launch {
            withOverlaySettlement(doneOverlay, overlayOwner, doneGenerations) {
                val result = activityObjectsRepository?.act(serverId, ActivityObjectAction.Done, row)
                if (result !is DataResult.Content) {
                    doneOverlay.forgetAll(overlayOwner, doneGenerations)
                    refreshAfterFailure(previous, "activity_object_done_failed")
                } else {
                    doneOverlay.confirmAll(overlayOwner, doneGenerations)
                    onUnreadCountsChanged()
                }
            }
        }
    }

    /** Undo an object row's Done (its toast): the row shows again from the server. */
    private fun undoObjectDone(item: InboxItem) {
        val row = item.activityObject ?: return
        val current = _state.value
        val serverId = current.ownerServerId ?: current.snapshot?.server?.id?.value ?: return
        overlayOwner(current)?.let { owner -> doneOverlay.forget(owner, item.key) }
        scope.launch {
            val result = activityObjectsRepository?.act(serverId, ActivityObjectAction.Undone, row)
            if (result !is DataResult.Content) diagnosticsLogger("activity_object_undone result=failed key=${item.key}")
            onUnreadCountsChanged()
            load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
        }
    }

    /** Read on an object row (opening it, or the menu): unread clears at once; a failure refreshes. */
    private fun markObjectRead(item: InboxItem) {
        if (item.unreadCount <= 0) return
        val row = item.activityObject ?: return
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        val serverId = previous.ownerServerId ?: snapshot.server.id.value
        synchronized(realtimeOwnerLock) { retainReadRowLocked(SharedActivityIntent.MarkRead(item), item) }
        val retained = _state.value
        val retainedSnapshot = retained.snapshot ?: return
        val overlayOwner = overlayOwner(retained)
        val readGenerations = rememberUnreadCleared(overlayOwner, listOf(item))
        val keepsRow = retained.keepsReadRow(item.key)
        reduceState(
            SharedActivityIntent.MarkRead(item),
            retained.copy(
                snapshot =
                    retainedSnapshot.copy(
                        items =
                            if (keepsRow) {
                                retainedSnapshot.items.map { existing ->
                                    if (existing.key == item.key) existing.copy(unreadCount = 0, hasMention = false) else existing
                                }
                            } else {
                                retainedSnapshot.items.filterNot { it.key == item.key }
                            },
                        objectCounts = retainedSnapshot.objectCounts?.withoutUnreadRow(item, retained.selectedFilter),
                    ),
                errorCode = null,
            ),
        )
        scope.launch {
            withOverlaySettlement(readOverlay, overlayOwner, readGenerations) {
                val result = activityObjectsRepository?.act(serverId, ActivityObjectAction.Read, row)
                if (result !is DataResult.Content) {
                    readOverlay.forgetAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    refreshAfterFailure(previous, "activity_object_read_failed")
                } else {
                    readOverlay.confirmAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                }
            }
        }
    }

    /**
     * Unfollow an object (Artifacts `DELETE /api/artifacts/:id/subscription`). The panel sends it only
     * once its Undo toast has ended; the row then says it is no longer followed.
     */
    private fun unfollowObject(item: InboxItem) {
        val row = item.activityObject ?: return
        val current = _state.value
        val serverId = current.ownerServerId ?: current.snapshot?.server?.id?.value ?: return
        scope.launch {
            val result = activityObjectsRepository?.unfollow(serverId, row)
            if (result !is DataResult.Content) {
                diagnosticsLogger("activity_object_unfollow result=failed key=${item.key}")
                return@launch
            }
            val latest = _state.value
            val snapshot = latest.snapshot ?: return@launch
            reduceState(
                SharedActivityIntent.UnfollowObject(item),
                latest.copy(
                    snapshot =
                        snapshot.copy(
                            items =
                                snapshot.items.map { existing ->
                                    if (existing.key != item.key) {
                                        existing
                                    } else {
                                        existing.copy(isFollowing = false, activityObject = existing.activityObject?.copy(isFollowing = false))
                                    }
                                },
                        ),
                ),
            )
        }
    }

    private fun removeObjectRowOptimistically(item: InboxItem) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        reduceState(
            SharedActivityIntent.MarkDone(item),
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = snapshot.items.filterNot { it.key == item.key },
                        objectCounts =
                            snapshot.objectCounts?.let { counts ->
                                val unread = item.unreadCount > 0
                                counts.copy(
                                    objectUnreadCount = (counts.objectUnreadCount - if (unread) 1 else 0).coerceAtLeast(0),
                                    objectMentionUnreadCount =
                                        (counts.objectMentionUnreadCount - if (unread && item.hasMention) 1 else 0).coerceAtLeast(0),
                                    objectCount = (counts.objectCount - 1).coerceAtLeast(0),
                                )
                            },
                    ),
                errorCode = null,
                retainedReadRowKeys = current.retainedReadRowKeys - item.key,
            ),
        )
    }

    /**
     * Apply an Activity thread follow mutation through the existing shared
     * thread transport.  The optimistic projection is intentionally limited
     * to the server-owned boolean and attention clearing; it never invents
     * unread/mention state when a row is followed again.
     */
    private fun unfollowThread(intent: SharedActivityIntent.UnfollowThread) {
        val item = intent.item
        if (!item.isThreadActivityItem() || item.isUnfollowedActivityThread) return
        val state = _state.value
        val heldItem = state.items.firstOrNull { it.key == item.key }
        // A deferred Unfollow (Activity's Undo toast) can land after the list moved to another
        // filter: still unfollow the swiped row, but only for the account and server it was listed for.
        val sameListOwner =
            intent.listOwnerUserId != null &&
                intent.listOwnerServerId != null &&
                intent.listOwnerUserId == state.ownerUserId &&
                intent.listOwnerServerId == state.ownerServerId
        val threadId = item.threadChannelId?.value
        fun settleHome(phase: HomeThreadUnfollowPhase) {
            threadId?.let { onThreadUnfollowProjection(SharedHomeIntent.ProjectThreadUnfollow(it, phase)) }
        }
        // Nothing is written for a row of another list: Home gets back the unread it cleared.
        val currentItem = heldItem ?: item.takeIf { sameListOwner } ?: return settleHome(HomeThreadUnfollowPhase.Restored)
        // Already unfollowed (the server counts no unread for it): the clear stands.
        if (currentItem.isUnfollowedActivityThread) return settleHome(HomeThreadUnfollowPhase.Confirmed)
        if (!beginThreadFollowMutation(item.key)) return
        val previous = _state.value
        if (heldItem != null) updateThreadFollowProjection(item.key, following = false)
        scope.launch {
            val threadChannelId = currentItem.threadChannelId
            val result = try {
                if (threadChannelId == null) {
                    DataResult.Error(ScreenError("activity_thread_channel_missing"))
                } else {
                    conversationRepository.unfollowThread(threadChannelId)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DataResult.Error(ScreenError("activity_unfollow_failed", serverMessage = error.message))
            } finally {
                // A cancelled request must release the duplicate-request fence;
                // otherwise a transient scope cancellation disables the row's
                // next explicit action until the store is reset.
                finishThreadFollowMutation(item.key)
            }
            settleHome(
                if (result.isAcceptedThreadFollowMutation()) HomeThreadUnfollowPhase.Confirmed else HomeThreadUnfollowPhase.Restored
            )
            if (result.isAcceptedThreadFollowMutation()) {
                onUnreadCountsChanged()
                // The mutation response is the server acknowledgement for
                // this explicit user action.  A later Activity refresh (or
                // re-entry) remains the canonical reconciliation point; do
                // not issue a second request here that could race the action
                // and briefly resurrect a stale follow state.
            } else {
                refreshAfterFailure(previous, result.failureCodeOr("activity_unfollow_failed"))
            }
            takeDeferredThreadFollow(item.key)?.let(::followThread)
        }
    }

    /**
     * Follow an unfollowed thread row again (the menu's Follow, or Undo of a swiped Unfollow).
     *
     * A row this filter still lists is followed in place. A row the filter dropped when it was
     * unfollowed (Unread, Mentions) is followed from [item] and the list reloads in the background,
     * so the row comes back from the server rather than from a guessed position. A Follow that
     * arrives while the row's Unfollow is in flight runs once that settles.
     */
    private fun followThread(item: InboxItem) {
        if (!item.isThreadActivityItem() || !item.isUnfollowedActivityThread) return
        val currentItem = _state.value.items.firstOrNull { it.key == item.key }
        if (currentItem != null && !currentItem.isUnfollowedActivityThread) return
        if (!beginThreadFollowMutation(item.key)) {
            // Not deferred: the mutation in flight settled in between, so follow now.
            if (!deferThreadFollow(item)) followThread(item)
            return
        }
        val previous = _state.value
        if (currentItem != null) updateThreadFollowProjection(item.key, following = true)
        val target = currentItem ?: item
        scope.launch {
            val parentMessageId = target.parentMessageId
            val taskConversationId = target.threadChannelId?.takeIf { target.isTaskConversation }
            val result = try {
                when {
                    // Task v2 (web `followActivityThread`): a task conversation has no parent
                    // message; it is followed by its own (local) channel id.
                    taskConversationId != null -> conversationRepository.followTaskConversation(taskConversationId)
                    parentMessageId == null -> DataResult.Error(ScreenError("activity_parent_message_missing"))
                    else -> conversationRepository.followThread(parentMessageId)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DataResult.Error(ScreenError("activity_follow_failed", serverMessage = error.message))
            } finally {
                finishThreadFollowMutation(item.key)
            }
            if (result.isAcceptedThreadFollowMutation()) {
                onUnreadCountsChanged()
                if (currentItem == null) {
                    load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
                }
            } else {
                refreshAfterFailure(previous, result.failureCodeOr("activity_follow_failed"))
            }
            takeDeferredThreadFollow(item.key)?.let(::followThread)
        }
    }

    /** Parks [item]'s Follow behind the row's mutation in flight; false when none is in flight any more. */
    private fun deferThreadFollow(item: InboxItem): Boolean =
        synchronized(realtimeOwnerLock) {
            if (item.key !in pendingThreadFollowMutations) return@synchronized false
            deferredThreadFollows = deferredThreadFollows + (item.key to item)
            true
        }

    private fun takeDeferredThreadFollow(key: String): InboxItem? =
        synchronized(realtimeOwnerLock) {
            deferredThreadFollows[key]?.also { deferredThreadFollows = deferredThreadFollows - key }
        }

    private fun DataResult<ThreadActionResult>.failureCodeOr(fallback: String): String =
        when (this) {
            is DataResult.Error -> error.code.ifBlank { fallback }
            is DataResult.NoPermission -> blocker.code.ifBlank { fallback }
            is DataResult.Content,
            is DataResult.Empty -> fallback
        }

    /** A successful response with `ok=false` did not acknowledge the mutation. */
    private fun DataResult<ThreadActionResult>.isAcceptedThreadFollowMutation(): Boolean =
        when (this) {
            is DataResult.Content -> data.changed
            // Empty is the deployed legacy success shape for an idempotent
            // action; retain the optimistic state until the next refresh.
            is DataResult.Empty -> true
            is DataResult.Error,
            is DataResult.NoPermission -> false
        }

    private fun InboxItem.isThreadActivityItem(): Boolean =
        kind == InboxItemKind.Thread

    private fun beginThreadFollowMutation(key: String): Boolean =
        synchronized(realtimeOwnerLock) {
            if (key in pendingThreadFollowMutations) {
                false
            } else {
                pendingThreadFollowMutations += key
                true
            }
        }

    private fun finishThreadFollowMutation(key: String) {
        synchronized(realtimeOwnerLock) {
            pendingThreadFollowMutations -= key
        }
    }

    private fun updateThreadFollowProjection(key: String, following: Boolean) {
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            val snapshot = current.snapshot ?: return@synchronized
            val updatedSnapshot =
                snapshot.projectActivityFollowMutation(
                    itemKey = key,
                    following = following,
                    filter = current.selectedFilter
                )
            reduceState(
                if (following) {
                    SharedActivityIntent.FollowThread(snapshot.items.firstOrNull { it.key == key } ?: return@synchronized)
                } else {
                    SharedActivityIntent.UnfollowThread(snapshot.items.firstOrNull { it.key == key } ?: return@synchronized)
                },
                current.copy(
                    snapshot = updatedSnapshot,
                    errorCode = null
                )
            )
        }
    }

    private fun markRead(item: InboxItem) {
        // A row marked unread with no count is read like any unread row: the read clears the mark.
        if (item.isUnfollowedActivityThread || !item.showsUnread) return
        val previous = _state.value
        if (previous.snapshot == null) return
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        val owner =
            _state.value.ownerServerId?.let { serverId ->
                _state.value.ownerUserId?.let { userId ->
                    readReceiptOwnerFactory?.invoke(serverId, userId)
                }
            }
        // Reading a row (opened or marked read) in Unread keeps it listed, read, until the user
        // moves on; rows read earlier in this visit stay too.
        synchronized(realtimeOwnerLock) { retainReadRowLocked(SharedActivityIntent.MarkRead(item), item) }
        val coordinator = readReceiptCoordinator
        if (owner != null && coordinator != null) {
            val knownSeq = item.readStateLatestActivitySeq?.toLongOrNull()
            applyReadReceiptPrediction(coordinator.markReadAll(owner, targetChannelId, knownSeq))
            return
        }
        val key = item.key
        val retainedState = _state.value
        val retainedSnapshot = retainedState.snapshot ?: return
        val overlayOwner = overlayOwner(retainedState)
        val readGenerations = rememberUnreadCleared(overlayOwner, listOf(item))
        val keepsRow = retainedState.keepsReadRow(key)
        val updatedItems =
            if (keepsRow) {
                retainedSnapshot.items.map { existing ->
                    if (existing.key == key) {
                        existing.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false, markedUnread = false)
                    } else {
                        existing
                    }
                }
            } else {
                retainedSnapshot.items.filterNot { it.key == key }
            }
        reduceState(
            SharedActivityIntent.MarkRead(item),
            retainedState.copy(
                snapshot =
                    retainedSnapshot.copy(
                        items = updatedItems,
                        // The filter's count drops at once even while the read row stays listed.
                        totalCount =
                            if (retainedState.selectedFilter.keepsReadActivityRows()) {
                                retainedSnapshot.totalCount
                            } else {
                                maxOf(0, retainedSnapshot.totalCount - 1)
                            },
                        totalUnreadCount = maxOf(0, retainedSnapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null
            )
        )
        scope.launch {
            withOverlaySettlement(readOverlay, overlayOwner, readGenerations) {
                val serverId = currentServerId()
                val result = readReceiptRepository.markChannelReadAll(targetChannelId)
                if (result !is DataResult.Content && result !is DataResult.Empty) {
                    readOverlay.forgetAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    refreshAfterFailure(previous, "activity_mark_read_failed")
                } else {
                    readOverlay.confirmAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                }
            }
        }
    }

    internal fun applyReadReceiptPrediction(prediction: ReadReceiptPrediction) {
        synchronized(realtimeOwnerLock) {
            onReadReceiptPredictionOwnerEntered()
            val current = _state.value
            if (!current.matchesReadReceiptOwner(prediction.owner)) return@synchronized
            val previousRevision = readPredictionRevisions[prediction.channelId]
            if (previousRevision != null && previousRevision >= prediction.revision) return@synchronized
            val snapshot = current.snapshot ?: return@synchronized
            val matchingUnread =
                snapshot.items.filter { item ->
                    item.showsUnread && item.readReceiptChannelId() == prediction.channelId
                }
            readPredictionRevisions = readPredictionRevisions + (prediction.channelId to prediction.revision)
            if (matchingUnread.isEmpty()) return@synchronized
            // Pending until the coordinator reports this channel's write result
            // (handleReadReceiptResult); the hold starts at server confirmation.
            val generations = rememberUnreadCleared(overlayOwner(current), matchingUnread)
            if (generations.isNotEmpty()) {
                readPredictionHolds = readPredictionHolds + (prediction.channelId to (prediction.revision to generations))
            }
            val matchingKeys = matchingUnread.mapTo(mutableSetOf(), InboxItem::key)
            readPredictionItemKeys = readPredictionItemKeys + (prediction.channelId to matchingKeys)
            val removedUnreadCount = matchingUnread.sumOf(InboxItem::unreadCount)
            val updatedItems =
                snapshot.items.mapNotNull { item ->
                    when {
                        item.key !in matchingKeys -> item
                        current.keepsReadRow(item.key) ->
                            item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false, markedUnread = false)
                        else -> null
                    }
                }
            reduceState(
                SharedActivityIntent.ApplyReadReceiptPrediction(prediction),
                current.copy(
                    snapshot =
                        snapshot.copy(
                            items = updatedItems,
                            totalCount =
                                if (current.selectedFilter.keepsReadActivityRows()) {
                                    snapshot.totalCount
                                } else {
                                    maxOf(0, snapshot.totalCount - matchingKeys.size)
                                },
                            totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - removedUnreadCount)
                        ),
                    errorCode = null
                )
            )
        }
    }

    internal fun handleReadReceiptFailure(request: ReadReceiptRequest) {
        val shouldRefresh =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                if (!current.matchesReadReceiptOwner(request.owner)) return@synchronized false
                if (readPredictionRevisions[request.channelId] != request.revision) return@synchronized false
                readPredictionRevisions = readPredictionRevisions - request.channelId
                readPredictionItemKeys[request.channelId].orEmpty().forEach(::forgetUnreadCleared)
                readPredictionItemKeys = readPredictionItemKeys - request.channelId
                readPredictionHolds = readPredictionHolds - request.channelId
                true
            }
        if (shouldRefresh) {
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
        }
    }

    /**
     * The coordinator finished a read write for ([ReadReceiptRequest.owner],
     * channel, revision). A success at or after the revision that registered the
     * channel's local read starts that hold now; a failure is left to
     * [handleReadReceiptFailure] (current revision) or to the superseding
     * request's own result. An entry no result ever reaches lapses at its
     * pending backstop.
     */
    internal fun handleReadReceiptResult(request: ReadReceiptRequest, succeeded: Boolean) {
        if (!succeeded) return
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            if (!current.matchesReadReceiptOwner(request.owner)) return@synchronized
            val (revision, generations) = readPredictionHolds[request.channelId] ?: return@synchronized
            if (request.revision < revision) return@synchronized
            readOverlay.confirmAll(overlayOwner(current), generations)
            readPredictionHolds = readPredictionHolds - request.channelId
        }
    }

    private fun SharedActivityState.matchesReadReceiptOwner(owner: ReadReceiptOwner): Boolean {
        val serverId = ownerServerId ?: return false
        val accountId = ownerUserId ?: return false
        if (serverId != owner.serverId || accountId != owner.accountId) return false
        val ownerFactory = readReceiptOwnerFactory ?: return true
        return ownerFactory(serverId, accountId) == owner
    }

    private fun markUnread(item: InboxItem) {
        if (item.isUnfollowedActivityThread) return
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        val wasUnread = item.unreadCount > 0
        val optimisticUnreadCount = item.unreadCount.takeIf { it > 0 } ?: 1
        val key = item.key
        forgetUnreadCleared(key)
        val updatedItems =
            snapshot.items.map { existing ->
                if (existing.key == key) {
                    existing.copy(
                        unreadCount = optimisticUnreadCount,
                        firstUnreadMessageId = existing.firstUnreadMessageId ?: existing.activityUnreadAnchor()
                    )
                } else {
                    existing
                }
            }
        reduceState(
            SharedActivityIntent.MarkUnread(item),
            previous.copy(
                snapshot =
                    snapshot.copy(
                        items = updatedItems,
                        totalUnreadCount =
                            if (wasUnread) {
                                snapshot.totalUnreadCount
                            } else {
                                snapshot.totalUnreadCount + optimisticUnreadCount
                            }
                    ),
                errorCode = null
            )
        )
        scope.launch {
            val readOwner =
                readReceiptOwnerFactory?.invoke(previous.ownerServerId.orEmpty(), currentUserId().orEmpty())
            if (readOwner != null) readReceiptCoordinator?.settleReadsBeforeUnread(readOwner, targetChannelId)
            val result = readReceiptRepository.markChannelUnread(targetChannelId)
            if (result is DataResult.Content) {
                // The read point moved back, or stayed and the conversation was marked unread: the next
                // open must send its read again (that is what clears it), not reuse an acknowledgement.
                if (readOwner != null) {
                    readReceiptCoordinator?.forgetAcknowledged(readOwner, targetChannelId)
                    readReceiptCoordinator?.noteUnreadAnswer(readOwner, targetChannelId, result.data)
                }
                applyMarkUnreadOutcome(item, optimisticUnreadCount, targetChannelId, result.data)
            }
            val owner =
                readReceiptOwnerFactory
                    ?.invoke(previous.ownerServerId.orEmpty(), currentUserId().orEmpty())
                    ?.toReadStateV1ProductionOwner()
            val disposition =
                if (result is DataResult.Content && owner != null) {
                    readStateV1Canary?.applyMutationAck(
                        owner = owner,
                        channelId = targetChannelId.value,
                        readStateVersion = result.data.readStateVersion,
                        maxReadSeq = result.data.maxReadSeq,
                    )
                } else {
                    null
                }
            if (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy) {
                onUnreadCountsChanged()
            } else if (result is DataResult.Content || result is DataResult.Empty) {
                onUnreadCountsChanged()
            } else {
                onUnreadCountsChanged()
                refreshAfterFailure(previous, "activity_mark_unread_failed")
            }
        }
    }

    /**
     * botiverse/slock #11792 / #11795: Mark as Unread's answer. A rewind keeps the row unread (the next
     * load brings the exact count). A mark takes back the optimistic count and shows the dot, through
     * the same version-ordered read state as its `read_state:updated` (an answer later than a newer
     * read never brings the mark back). Nothing to mark takes the row back to read and says why.
     */
    private fun applyMarkUnreadOutcome(
        item: InboxItem,
        optimisticUnreadCount: Int,
        targetChannelId: ChannelId,
        result: ReadReceiptResult
    ) {
        val outcome = result.markUnreadOutcome()
        diagnosticsLogger("activity_mark_unread_outcome key=${item.key} outcome=${outcome.name}")
        // Pending (202): the optimistic count stays and the `read_state:updated` that follows corrects it.
        if (outcome == MarkUnreadOutcome.Counted || outcome == MarkUnreadOutcome.Pending) return
        val reconcileJob =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                val snapshot = current.snapshot ?: return@synchronized null
                var restored = 0
                val items =
                    snapshot.items.map { existing ->
                        // Only the optimistic count goes back: a message that arrived meanwhile keeps its count.
                        if (existing.key == item.key && item.unreadCount <= 0 && existing.unreadCount == optimisticUnreadCount) {
                            restored += optimisticUnreadCount
                            existing.copy(unreadCount = item.unreadCount, firstUnreadMessageId = item.firstUnreadMessageId)
                        } else {
                            existing
                        }
                    }
                if (restored > 0) {
                    reduceState(
                        SharedActivityIntent.MarkUnread(item),
                        current.copy(
                            snapshot =
                                snapshot.copy(
                                    items = items,
                                    totalUnreadCount = (snapshot.totalUnreadCount - restored).coerceAtLeast(0)
                                )
                        )
                    )
                }
                if (outcome != MarkUnreadOutcome.Marked) return@synchronized null
                val serverId = current.ownerServerId?.takeIf { it.isNotBlank() } ?: return@synchronized null
                val userId = current.ownerUserId?.takeIf { it.isNotBlank() } ?: return@synchronized null
                val version = result.readStateVersion ?: return@synchronized null
                val maxReadSeq = result.maxReadSeq ?: return@synchronized null
                val fact =
                    perReceiverStateVersionStore.applyOrObserveReadStateFact(
                        serverId = ServerId(serverId),
                        memberId = MemberId(userId),
                        scopeId = targetChannelId,
                        incomingVersion = version,
                        incomingMaxReadSeq = maxReadSeq
                    )
                if (fact == PerReceiverReadStateFactResult.Stale) return@synchronized null
                projectRemoteMarkedUnreadLocked(
                    channelId = targetChannelId.value,
                    markedUnread = true,
                    intent = SharedActivityIntent.MarkUnread(item)
                )
            }
        reconcileJob?.start()
        if (outcome == MarkUnreadOutcome.NothingToMark) {
            _readToggleNotices.tryEmit(RaftReadToggleNotice.MarkUnreadNothing)
        }
    }

    /**
     * Another device marked conversation [channelId] Done (incremental sync), exactly like web
     * `inboxStore.applyRemoteDone` (botiverse/slock bab9dd8c0, inboxStore.ts:2592; server #11685
     * pins `done.doneFrontierSeq` equal to the frontier a Done stores, web #11696): the
     * conversation's rows leave when the Done covers every one of them, compared exactly as decimal
     * seqs with the row's `latestActivitySeq` (equal = covered), and are held against a lagging
     * list like a Done made here. A row with newer activity stays as shown. Unread and total counts
     * move with the rows, as for a local Done.
     *
     * [ActivityRemoteDoneOutcome.CannotTell] (the caller reads the list again): no row of the
     * conversation is held, no frontier, a thread row (a thread's Done comes as `follow`), or a row
     * without `latestActivitySeq`.
     */
    internal fun applyRemoteDone(channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome =
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            val snapshot = current.snapshot ?: return@synchronized ActivityRemoteDoneOutcome.CannotTell
            val rows =
                snapshot.items.filter { item ->
                    item.kind != InboxItemKind.Object && item.kind != InboxItemKind.MentionAction &&
                        item.readReceiptChannelId()?.value == channelId
                }
            val outcome =
                when {
                    rows.isEmpty() || doneFrontierSeq == null -> ActivityRemoteDoneOutcome.CannotTell
                    rows.any { it.kind == InboxItemKind.Thread } -> ActivityRemoteDoneOutcome.CannotTell
                    rows.any { it.latestActivitySeq == null } -> ActivityRemoteDoneOutcome.CannotTell
                    rows.any { compareActivitySeq(it.latestActivitySeq, doneFrontierSeq) > 0 } ->
                        ActivityRemoteDoneOutcome.KeptNewerActivity
                    else -> ActivityRemoteDoneOutcome.Removed
                }
            if (outcome == ActivityRemoteDoneOutcome.Removed) {
                // Already on the server: the hold starts now, as for a confirmed Done made here.
                overlayOwner(current)?.let { owner ->
                    rows.forEach { row ->
                        doneOverlay.remember(owner, row)?.let { generation -> doneOverlay.confirm(owner, row.key, generation) }
                    }
                }
                val keys = rows.mapTo(HashSet()) { it.key }
                reduceState(
                    SharedActivityIntent.ApplyRemoteDone(channelId, doneFrontierSeq),
                    current.copy(
                        snapshot =
                            snapshot.copy(
                                items = snapshot.items.filterNot { it.key in keys },
                                totalCount = maxOf(0, snapshot.totalCount - rows.size),
                                totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - rows.sumOf { it.unreadCount })
                            ),
                        retainedReadRowKeys = current.retainedReadRowKeys - keys
                    )
                )
            }
            diagnosticsLogger(
                "activity_remote_done channelId=$channelId rows=${rows.size} outcome=${outcome.name}"
            )
            outcome
        }

    private fun removeItemOptimistically(item: InboxItem) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        reduceState(
            SharedActivityIntent.MarkDone(item),
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = snapshot.items.filterNot { it.key == item.key },
                        totalCount = maxOf(0, snapshot.totalCount - 1),
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null,
                // Done leaves at once, retained or not.
                retainedReadRowKeys = current.retainedReadRowKeys - item.key
            )
        )
    }

    private fun refreshAfterFailure(
        previous: SharedActivityState,
        code: String
    ) {
        _state.value = previous.copy(loading = false, refreshing = false, pullRefreshing = false, loadingMore = false, errorCode = code)
        load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh, fetchReason = InboxFetchReason.Manual)
    }

    private fun setLoadError(code: String, append: Boolean = false) {
        _state.value =
            _state.value.copy(
                loading = false,
                refreshing = false,
                pullRefreshing = false,
                loadingMore = false,
                errorCode = if (append) null else code,
                loadMoreErrorCode = if (append) code else null
            )
    }

    private fun reduceState(
        intent: SharedActivityIntent,
        nextState: SharedActivityState,
        summaryAuthoritative: Boolean = false,
        summaryFactCounts: Pair<Int, Int>? = null,
        summarySource: String = "activity:${intent.activitySummarySource()}"
    ): Boolean {
        val previousState = _state.value
        val stateWithFilterCache =
            if (nextState.snapshot != null) {
                val updatedMap = nextState.filterSnapshots.toMutableMap()
                updatedMap[nextState.selectedFilter] = nextState.snapshot
                if (intent is SharedActivityIntent.MarkRead ||
                    // The swipe's / menu's Unread (#793): an Unread page cached before it lacks the row.
                    intent is SharedActivityIntent.MarkUnread ||
                    intent is SharedActivityIntent.MarkDone ||
                    intent is SharedActivityIntent.ApplyRemoteDone ||
                    intent is SharedActivityIntent.MarkAllRead ||
                    intent is SharedActivityIntent.ApplyReadReceiptPrediction
                ) {
                    // Invalidate other filter caches when mutations occur so switching to them re-fetches fresh data
                    val keysToRemove = updatedMap.keys.filter { it != nextState.selectedFilter }
                    keysToRemove.forEach { updatedMap.remove(it) }
                }
                nextState.copy(filterSnapshots = updatedMap)
            } else {
                nextState
            }
        val finalState =
            persistSlices.fold(stateWithFilterCache) { state, slice ->
                slice.reduce(previous = previousState, intent = intent, reduced = state)
            }
        if (finalState == previousState) return false
        _state.value = finalState
        persistSlices.forEach { slice ->
            slice.onTransition(previous = previousState, intent = intent, next = finalState)
        }
        emitSummarySignalIfChanged(
            intent = intent,
            previous = previousState,
            next = finalState,
            authoritative = summaryAuthoritative,
            source = summarySource,
            countOverride = summaryFactCounts
        )
        return true
    }

    private fun emitSummarySignalIfChanged(
        intent: SharedActivityIntent,
        previous: SharedActivityState,
        next: SharedActivityState,
        authoritative: Boolean,
        source: String,
        countOverride: Pair<Int, Int>?
    ) {
        val serverId = next.ownerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val accountId = next.ownerUserId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val snapshot = next.snapshot ?: return
        val previousVisibleCounts = previous.snapshot?.activityVisibleSummaryCounts()
        val visibleCounts =
            snapshot.activityVisibleSummaryCounts(
                totalUnreadCountOverride = countOverride?.first,
                totalCountOverride = countOverride?.second
            )
        val summaryChanged =
            previous.ownerServerId != next.ownerServerId ||
                previous.ownerUserId != next.ownerUserId ||
                previousVisibleCounts != visibleCounts
        if (!summaryChanged && countOverride == null) return
        if (next.selectedFilter != InboxFilter.All) {
            if (!intent.invalidatesGlobalActivitySummary()) return
            runCatching {
                onSummaryInvalidated(
                    ActivityUnreadSummaryInvalidation(
                        accountId = accountId,
                        serverId = serverId,
                        source = source,
                        trigger = intent.summaryInvalidationTrigger()
                    )
                )
            }.onFailure { error ->
                diagnosticsLogger(
                    "activity_summary_invalidation result=failure serverId=$serverId source=$source " +
                        "errorType=${error::class.simpleName.orEmpty()}"
                )
            }
            return
        }
        runCatching {
            onSummaryFact(
                    ActivityUnreadSummaryFact(
                        accountId = accountId,
                        serverId = serverId,
                        totalUnreadCount = visibleCounts.first,
                        totalCount = visibleCounts.second,
                    authoritative = authoritative,
                    source = source
                )
            )
        }.onFailure { error ->
            diagnosticsLogger(
                "activity_summary_fact result=failure serverId=$serverId source=$source " +
                    "errorType=${error::class.simpleName.orEmpty()}"
            )
        }
    }

    private suspend fun currentServerId(): ServerId? =
        authStore.readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)

    private suspend fun currentUserId(): String? =
        authStore.readSession()
            ?.user
            ?.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * The rows went out before the member list (Hands b91f94e5); its profiles join the rows
     * when it lands, as long as the store still shows that server for that user. Off the load
     * job, so a late member list never holds a later refresh back.
     */
    private fun applyLateSenderProfiles(
        intent: SharedActivityIntent,
        serverId: ServerId,
        userId: String,
        senderProfilesLoad: Deferred<Map<String, SharedActivitySenderProfile>>
    ) {
        diagnosticsLogger("store_load domain=activity phase=sender_profiles_pending serverId=${serverId.value}")
        scope.launch {
            val profiles = senderProfilesLoad.await()
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                if (current.ownerServerId != serverId.value || current.ownerUserId != userId) return@synchronized
                val merged = current.senderProfiles + profiles
                if (merged != current.senderProfiles) reduceState(intent, current.copy(senderProfiles = merged))
            }
        }
    }

    private suspend fun loadSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        try {
            val cachedProfiles = currentSenderProfiles(serverId)
            val profilesById =
                serverMembersStore?.let { store ->
                    when (store.refreshMembers(serverId, DataRequestPriority.Visible)) {
                        is DataResult.Content -> store.currentKnownMemberProfilesById(serverId)
                        else -> emptyMap()
                    }
                } ?: when (val result = memberRepository?.getMembers(DataRequestPriority.Visible, serverId)) {
                    is DataResult.Content ->
                        result.data.members
                            .filter { it.id.value.trim().isNotBlank() }
                            .associateBy { it.id.value.trim() }
                    else -> emptyMap()
                }
            cachedProfiles + profilesById.toActivitySenderProfiles()
        } catch (_: Throwable) {
            currentSenderProfiles(serverId)
        }

    private fun currentSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        serverMembersStore
            ?.currentKnownMemberProfilesById(serverId)
            ?.toActivitySenderProfiles()
            .orEmpty()

    private fun Map<String, MemberProfile>.toActivitySenderProfiles(): Map<String, SharedActivitySenderProfile> =
        mapValues { (_, profile) ->
            SharedActivitySenderProfile(
                displayName = profile.displayName.ifBlank { profile.handle.ifBlank { profile.id.value } }
            )
        }

    /**
     * task #399: hydrate peer display names for DM-parent thread rows. The
     * inbox row's `parentChannelName` is the raw channel name, which is not a
     * reliable peer projection for DMs — the Home `directMessages` snapshot is
     * the authority (web `itemFilterChannelLabel` joins the same way).
     * Runs after each canonical load and safe realtime patch; a channel that
     * stays missing after a fetch is parked so a flood of DM rows cannot
     * refetch the DM list per row.
     */
    private fun ensureDmPeerNames() {
        val repository = channelRepository ?: return
        val missing: Set<ChannelId> =
            synchronized(realtimeOwnerLock) {
                if (dmPeerNamesFetchInFlight) return
                val state = _state.value
                val alreadyResolved = state.dmPeerNames
                val unresolved =
                    dmParentThreadChannelIds(state)
                        .filter {
                            alreadyResolved[it.value].isNullOrBlank() && it !in unresolvedDmPeerChannelIds
                        }
                        .toSet()
                if (unresolved.isEmpty()) return
                dmPeerNamesFetchInFlight = true
                unresolved
            }
        scope.launch {
            // true only when the DM list actually came back; a failed fetch
            // must NOT park ids (Mahua review: one network blip would
            // otherwise disable hydration for those rows for the store's
            // lifetime) and must not tail-retry (that would hot-loop against
            // an unreachable server — the next load/patch trigger retries).
            var fetchSucceeded = false
            try {
                val serverId = currentServerId() ?: return@launch
                val resolved: Map<String, String>? =
                    when (val result = repository.getDirectMessages(serverId)) {
                        is DataResult.Content ->
                            result.data.directMessages.associate { row ->
                                row.channelId.value to row.name
                            }
                        else -> null
                    }
                if (resolved != null) {
                    fetchSucceeded = true
                    synchronized(realtimeOwnerLock) {
                        val current = _state.value
                        val merged = current.dmPeerNames + resolved
                        // Park only the ids a successful fetch could not
                        // resolve; ids that arrived while the fetch was in
                        // flight stay eligible for the tail-call ensure below.
                        unresolvedDmPeerChannelIds =
                            unresolvedDmPeerChannelIds +
                                missing.filter { merged[it.value].isNullOrBlank() }
                        if (merged != current.dmPeerNames) {
                            _state.value = current.copy(dmPeerNames = merged)
                        }
                    }
                }
            } finally {
                synchronized(realtimeOwnerLock) {
                    dmPeerNamesFetchInFlight = false
                }
            }
            if (fetchSucceeded) {
                ensureDmPeerNames()
            }
        }
    }

    private fun dmParentThreadChannelIds(state: SharedActivityState): Set<ChannelId> =
        buildSet {
            state.snapshot?.items?.let(::addAll)
            state.filterSnapshots.values.forEach { addAll(it.items) }
        }
            .asSequence()
            .filter {
                it.kind == InboxItemKind.Thread &&
                    it.parentChannelKind == ConversationKind.DirectMessage &&
                    it.parentChannelId != null
            }
            .mapNotNull { it.parentChannelId }
            .toSet()

    /**
     * Null only before the first load has resolved a server (no rows to hold yet).
     * An unknown account keys by server alone rather than disabling the overlay.
     */
    private fun overlayOwner(state: SharedActivityState = _state.value): ActivityOverlayOwner? {
        val serverId = state.ownerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ActivityOverlayOwner(serverId, state.ownerUserId?.trim()?.takeIf { it.isNotBlank() })
    }

    private fun rememberResolvedMentionAction(owner: ActivityOverlayOwner, key: String) {
        val keys = resolvedMentionActionKeys[owner].orEmpty()
        resolvedMentionActionKeys = resolvedMentionActionKeys + (owner to (keys + key))
    }

    /**
     * Runs a mutation's write and guarantees its pending overlay entries settle:
     * if [block] throws or is cancelled before it confirmed or forgot them, they
     * are forgotten (server truth shows on the next load) instead of staying
     * pending until the backstop.
     */
    private suspend inline fun withOverlaySettlement(
        overlay: ActivityLocalMutationOverlay,
        owner: ActivityOverlayOwner?,
        generations: Map<String, Long>,
        block: () -> Unit
    ) {
        var settled = false
        try {
            block()
            settled = true
        } finally {
            if (!settled) overlay.forgetAll(owner, generations)
        }
    }

    /** Records a local read for every unread row; returns key -> overlay generation. */
    private fun rememberUnreadCleared(
        owner: ActivityOverlayOwner?,
        items: List<InboxItem>
    ): Map<String, Long> {
        if (owner == null) return emptyMap()
        return items
            .filter { it.unreadCount > 0 }
            .mapNotNull { item -> readOverlay.remember(owner, item)?.let { item.key to it } }
            .toMap()
    }

    private fun ActivityLocalMutationOverlay.confirmAll(
        owner: ActivityOverlayOwner?,
        generations: Map<String, Long>
    ) {
        if (owner == null) return
        generations.forEach { (key, generation) -> confirm(owner, key, generation) }
    }

    private fun ActivityLocalMutationOverlay.forgetAll(
        owner: ActivityOverlayOwner?,
        generations: Map<String, Long>
    ) {
        if (owner == null) return
        generations.forEach { (key, generation) -> forget(owner, key, generation) }
    }

    private fun forgetUnreadCleared(key: String) {
        overlayOwner()?.let { owner -> readOverlay.forget(owner, key) }
    }

    private fun List<InboxItem>.reconcileLocallyClearedUnreadItems(
        filter: InboxFilter,
        owner: ActivityOverlayOwner?
    ): ActivityUnreadReconcileResult {
        if (owner == null || !readOverlay.hasEntries(owner)) {
            return ActivityUnreadReconcileResult(items = this)
        }
        val reconciled = mutableListOf<InboxItem>()
        var suppressedUnreadCount = 0
        var removedUnreadRows = 0
        for (item in this) {
            // An already-read row keeps its entry: a later stale copy may still
            // carry the pre-read unread state for the same activity marker.
            if (item.unreadCount <= 0 || !readOverlay.holds(owner, item)) {
                reconciled += item
                continue
            }
            suppressedUnreadCount += item.unreadCount
            val clearedItem = item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
            if (filter.keepsReadActivityRows()) {
                reconciled += clearedItem
            } else {
                removedUnreadRows += 1
            }
        }
        return ActivityUnreadReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows
        )
    }

    private fun ActivityUnreadReconcileResult.reconcileLocallyDoneItems(
        owner: ActivityOverlayOwner?
    ): ActivityReconcileResult {
        val resolvedMentionActions = owner?.let { resolvedMentionActionKeys[it] }.orEmpty()
        if (owner == null || (resolvedMentionActions.isEmpty() && !doneOverlay.hasEntries(owner))) {
            return ActivityReconcileResult(
                items = items,
                suppressedUnreadCount = suppressedUnreadCount,
                removedUnreadRows = removedUnreadRows
            )
        }
        val reconciled = mutableListOf<InboxItem>()
        var suppressedDoneUnreadCount = 0
        var removedDoneRows = 0
        for (item in items) {
            if (item.key in resolvedMentionActions || doneOverlay.holds(owner, item)) {
                suppressedDoneUnreadCount += item.unreadCount
                removedDoneRows += 1
            } else {
                reconciled += item
            }
        }
        return ActivityReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows,
            suppressedDoneUnreadCount = suppressedDoneUnreadCount,
            removedDoneRows = removedDoneRows
        )
    }
}

private data class ActivityUnreadReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0
)

private data class ActivityReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0,
    val suppressedDoneUnreadCount: Int = 0,
    val removedDoneRows: Int = 0
)

/** The counts once [item], unread, is read: it leaves every unread count (all filters list it under `objectCount` when unread-only). */
internal fun ActivityObjectCounts.withoutUnreadRow(item: InboxItem, filter: InboxFilter): ActivityObjectCounts =
    if (item.unreadCount <= 0) {
        this
    } else {
        copy(
            objectUnreadCount = (objectUnreadCount - 1).coerceAtLeast(0),
            objectMentionUnreadCount = (objectMentionUnreadCount - if (item.hasMention) 1 else 0).coerceAtLeast(0),
            objectCount = if (filter == InboxFilter.Unread) (objectCount - 1).coerceAtLeast(0) else objectCount,
        )
    }

internal fun List<InboxItem>.dedupeActivityItems(): List<InboxItem> =
    if (size <= 1) {
        this
    } else {
        distinctBy { it.key }
    }

data class SharedActivityState(
    val selectedFilter: InboxFilter = InboxFilter.All,
    val snapshot: InboxSnapshot? = null,
    val filterSnapshots: Map<InboxFilter, InboxSnapshot> = emptyMap(),
    val senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    /** task #399: DM channel id → peer display name, hydrated from the Home DM
     * list for `parentChannelKind == DirectMessage` thread rows. */
    val dmPeerNames: Map<String, String> = emptyMap(),
    val ownerServerId: String? = null,
    val ownerUserId: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val pullRefreshing: Boolean = false,
    /**
     * A background load (realtime reconcile, sync-triggered or periodic refresh) is reading the
     * list behind rows already shown. It never sets [refreshing], so the page keeps its rows and
     * error state, and shows no refresh chrome at all (ticket 67609860).
     */
    val backgroundRefreshing: Boolean = false,
    /**
     * The current [refreshing] load may show the header indicator ([headerRefreshing]): it is the
     * open, a server / filter switch, or a visible-route Refresh (resume, re-entry) over rows last
     * read [ActivityHeaderRefreshStaleAfterMillis] ago. Each foreground load sets it afresh.
     */
    val headerRefreshAllowed: Boolean = false,
    val loadingMore: Boolean = false,
    val errorCode: String? = null,
    val loadMoreErrorCode: String? = null,
    /**
     * The rows the user read during this visit to a filter that drops read rows (Unread): opened,
     * marked read, or listed at "Mark all read". They stay listed, shown read, until the user moves
     * on (switches filter, leaves Activity, pulls to refresh, switches server). Background loads,
     * reconciles and realtime keep them; Done removes a row at once. Gmail / Apple Mail unread view
     * (stdrc 10-23).
     */
    val retainedReadRowKeys: Set<String> = emptySet(),
    /**
     * Task #164: the rows and counts shown may be old because the read warehouse is behind: the
     * last canonical read was the server's remembered body (`X-Raft-Stale`) or a `503
     * rw_overloaded` while rows were shown. The list keeps its last good rows; the page shows the
     * stale notice until a fresh read clears it.
     */
    val stale: Boolean = false,
    /**
     * botiverse/slock #12066: the last canonical read failed with `503 rw_unconfigured` (a
     * self-hosted server without RisingWave). Waiting will not help, so the page says Activity is
     * temporarily unavailable (web `thread.unavailableNotice`) instead of "may be out of date".
     */
    val unavailable: Boolean = false
) {
    val items: List<InboxItem>
        get() = snapshot?.items.orEmpty()
    val totalCount: Int
        get() = snapshot?.totalCount ?: 0
    val totalUnreadCount: Int
        get() = snapshot?.totalUnreadCount ?: 0
    val hasMore: Boolean
        get() = snapshot?.hasMore == true
    val nextOffset: Int?
        get() = snapshot?.nextOffset

    /**
     * The Activity header shows its top-right refresh indicator (ticket 67609860, artin 10-28):
     * only for a foreground refresh over shown rows that [headerRefreshAllowed] (open, server /
     * filter switch, resume or re-entry after a while). Background loads (realtime, sync,
     * periodic: [backgroundRefreshing]) show nothing, and a user pull owns the refresh chrome
     * alone (the pull indicator, artin task #22), so this is false while [pullRefreshing].
     */
    val headerRefreshing: Boolean
        get() = refreshing && headerRefreshAllowed && !pullRefreshing
}

/**
 * Web `isUnreadInboxFilter` (slock 1bb70cbeb, inboxStore.ts:124): only Unread
 * drops a row once it is read. The server keeps read rows in Mentions (an
 * independent `message_mentions` EXISTS, channelService.ts:13136-13168), so a
 * read / mark-all-read clears the row's attention there instead of removing it.
 */
private fun InboxFilter.keepsReadActivityRows(): Boolean =
    this != InboxFilter.Unread

/** A read row stays listed (shown read) when the filter keeps read rows or it was read this visit. */
private fun SharedActivityState.keepsReadRow(key: String): Boolean =
    selectedFilter.keepsReadActivityRows() || key in retainedReadRowKeys

/**
 * Forgets the rows retained for this visit; the read ones leave the list, as they no longer match
 * the filter (they were read in a filter that drops read rows). A retained row that turned unread
 * again stays. The counts already left them when they were read, so they stay.
 */
internal fun SharedActivityState.withRetainedReadRowsReleased(): SharedActivityState {
    if (retainedReadRowKeys.isEmpty()) return this
    val current = snapshot
    val leaving =
        if (current == null || selectedFilter.keepsReadActivityRows()) {
            emptyList()
        } else {
            current.items.filter { item -> item.key in retainedReadRowKeys && item.unreadCount <= 0 }
        }
    return copy(
        retainedReadRowKeys = emptySet(),
        snapshot = if (leaving.isEmpty()) current else current?.copy(items = current.items - leaving.toSet())
    )
}

private fun InboxItem.touchesChannel(channelIds: Set<String>): Boolean =
    channelId.value in channelIds ||
        threadChannelId?.value in channelIds ||
        parentChannelId?.value in channelIds

/**
 * Drops the retained rows [matching] from the list and from the retained set; the other retained rows
 * stay. A read row already left the counts; a retained row that turned unread leaves them now. Returns this when nothing is retained and matches.
 */
internal fun SharedActivityState.withRetainedRowsDropped(matching: (InboxItem) -> Boolean): SharedActivityState {
    val current = snapshot
    val leaving = current?.items.orEmpty().filter { item -> item.key in retainedReadRowKeys && matching(item) }
    val leavingKeys = leaving.mapTo(mutableSetOf(), InboxItem::key)
    if (leavingKeys.isEmpty()) return this
    return copy(
        retainedReadRowKeys = retainedReadRowKeys - leavingKeys,
        snapshot =
            current?.copy(
                items = current.items.filterNot { it.key in leavingKeys },
                totalCount = (current.totalCount - leaving.count { it.unreadCount > 0 }).coerceAtLeast(0),
                totalUnreadCount = (current.totalUnreadCount - leaving.sumOf { it.unreadCount }).coerceAtLeast(0)
            )
    )
}

/**
 * A canonical page in a filter that drops read rows no longer lists the retained rows once they
 * are read; each is put back, read, where it stood (before the first row that followed it and is
 * still on the page). Background loads and reconciles never drop a row read this visit.
 *
 * Only rows read here are put back: a retained row that is unread locally (the user marked it unread
 * again, #793) and that the page no longer lists was read elsewhere or is gone, so it drops; putting
 * back its stale unread copy would show an unread row the server no longer has.
 */
private fun List<InboxItem>.withRetainedReadRows(current: SharedActivityState): List<InboxItem> {
    val keys = current.retainedReadRowKeys
    if (keys.isEmpty()) return this
    val pageKeys = mapTo(mutableSetOf(), InboxItem::key)
    if (current.items.none { item -> item.key in keys && item.key !in pageKeys && item.unreadCount <= 0 }) return this
    // Anchor each missing retained row to the next row after it that the page still lists (null:
    // the end), walking the previous list backwards so rows sharing an anchor keep their order.
    val anchored = linkedMapOf<String?, MutableList<InboxItem>>()
    var anchor: String? = null
    for (item in current.items.asReversed()) {
        if (item.key in pageKeys) {
            anchor = item.key
        } else if (item.key in keys && item.unreadCount <= 0) {
            anchored.getOrPut(anchor) { mutableListOf() }
                .add(0, item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false))
        }
    }
    return buildList(size + anchored.values.sumOf { it.size }) {
        for (item in this@withRetainedReadRows) {
            anchored[item.key]?.let(::addAll)
            add(item)
        }
        anchored[null]?.let(::addAll)
    }
}

sealed interface SharedActivityIntent {
    /** The shell switched server: show [serverId]'s cached feed and revalidate it. */
    data class ServerSelected(val serverId: String) : SharedActivityIntent
    data object LoadInitial : SharedActivityIntent
    data object Refresh : SharedActivityIntent
    data object PullRefresh : SharedActivityIntent
    data object LoadMore : SharedActivityIntent
    data object MarkAllRead : SharedActivityIntent
    data class SetFilter(val filter: InboxFilter) : SharedActivityIntent
    data class MarkDone(val item: InboxItem) : SharedActivityIntent
    /**
     * Unfollow [item]'s thread. A row the store no longer lists (a deferred Unfollow sent after the
     * filter changed) is still unfollowed when [listOwnerUserId] / [listOwnerServerId] name the
     * account and server the store holds now; without them, a row not held writes nothing.
     */
    data class UnfollowThread(
        val item: InboxItem,
        val listOwnerUserId: String? = null,
        val listOwnerServerId: String? = null,
    ) : SharedActivityIntent
    data class FollowThread(val item: InboxItem) : SharedActivityIntent
    data class MarkRead(val item: InboxItem) : SharedActivityIntent
    data class MarkUnread(val item: InboxItem) : SharedActivityIntent
    data class ApplyReadReceiptPrediction(val prediction: ReadReceiptPrediction) : SharedActivityIntent
    data class ApplyReadReceiptFailure(val request: ReadReceiptRequest) : SharedActivityIntent
    /** A coordinator read write completed; a success starts the local read hold. */
    data class ApplyReadReceiptResult(val request: ReadReceiptRequest, val succeeded: Boolean) : SharedActivityIntent
    data class PrewarmVisibleReplyThreads(
        val markers: Set<ActivityReplyThreadPrewarmMarker>
    ) : SharedActivityIntent
    data class PrewarmActivityTail(
        val orderedMarkers: List<ActivityReplyThreadPrewarmMarker>
    ) : SharedActivityIntent
    data object DetachPrewarmViewport : SharedActivityIntent
    /**
     * The Activity surface stopped being visible (another tab, a pushed page, the app went to the
     * background). A load already in flight then is never joined by the next entry's Refresh: it
     * may have hung across the background, and the monotonic clock does not advance while the
     * device sleeps, so its age alone cannot tell.
     */
    data object SurfaceHidden : SharedActivityIntent

    /**
     * The user opened [item] (a row pick, or the wide layout reopening it beside the list). In a
     * filter that drops read rows, an unread [item] joins the rows retained for this visit, so
     * reading it keeps it in place; rows retained earlier stay.
     */
    data class RowOpened(val item: InboxItem) : SharedActivityIntent
    /**
     * The user lost access to [channelIds] (left or deleted through the app; an archived channel is still readable).
     * Retained rows of those channels, their threads, or threads under them leave at once.
     */
    data class ChannelAccessLost(val channelIds: Set<String>) : SharedActivityIntent
    /** Workspace Artifacts: undo an object row's Done (`POST /api/objects/activity/undone`). */
    data class UndoObjectDone(val item: InboxItem) : SharedActivityIntent
    /** Workspace Artifacts: stop following the row's object (sent after its Undo toast ends). */
    data class UnfollowObject(val item: InboxItem) : SharedActivityIntent

    /**
     * Web #11687: the person unfollowed [item]'s thread here (its request waits for the undo toast:
     * [HomeThreadUnfollowPhase.Unfollowed]) or took it back with Undo
     * ([HomeThreadUnfollowPhase.Restored]); Home's unread for the thread follows at once.
     */
    data class ProjectThreadUnfollow(val item: InboxItem, val phase: HomeThreadUnfollowPhase) : SharedActivityIntent
    data class ApplyRealtimeEvent(
        val event: RealtimeEvent,
        val allowLocalPatch: Boolean = true,
        /**
         * The conversation open on screen (the shell's seen selection): with
         * `mobile_inbox_inplace_v0` a live message there patches its row without one more unread.
         */
        val activeConversationId: String? = null
    ) : SharedActivityIntent
    data class ApplyRealtimeSummaryEvent(
        val event: RealtimeEvent,
        val activeConversationId: String?
    ) : SharedActivityIntent

    /**
     * Another device marked conversation [channelId] Done, through [doneFrontierSeq] (incremental
     * sync): [SharedActivityStore.applyRemoteDone]; a list that can't tell reads again.
     */
    data class ApplyRemoteDone(val channelId: String, val doneFrontierSeq: String?) : SharedActivityIntent
}

/** What [SharedActivityStore.applyRemoteDone] did with another device's Done. */
enum class ActivityRemoteDoneOutcome {
    /** The Done covered the conversation's rows: they left the list. */
    Removed,

    /** A row shows activity newer than the Done covered: it stays as shown. */
    KeptNewerActivity,

    /** This list can't judge it (no row, no frontier, a thread row, no `latestActivitySeq`): read again. */
    CannotTell,
}

private fun SharedActivityIntent.activitySummarySource(): String =
    when (this) {
        is SharedActivityIntent.ServerSelected -> "server_selected"
        SharedActivityIntent.LoadInitial -> "load_initial"
        SharedActivityIntent.Refresh -> "refresh"
        SharedActivityIntent.PullRefresh -> "pull_refresh"
        SharedActivityIntent.LoadMore -> "load_more"
        SharedActivityIntent.MarkAllRead -> "mark_all_read"
        is SharedActivityIntent.SetFilter -> "set_filter:${filter.wireValue}"
        is SharedActivityIntent.MarkDone -> "mark_done"
        is SharedActivityIntent.UnfollowThread -> "unfollow_thread"
        is SharedActivityIntent.FollowThread -> "follow_thread"
        is SharedActivityIntent.MarkRead -> "mark_read"
        is SharedActivityIntent.MarkUnread -> "mark_unread"
        is SharedActivityIntent.ApplyReadReceiptPrediction -> "read_prediction"
        is SharedActivityIntent.ApplyReadReceiptFailure -> "read_failure"
        is SharedActivityIntent.ApplyReadReceiptResult -> "read_result"
        is SharedActivityIntent.PrewarmVisibleReplyThreads -> "prewarm_visible"
        is SharedActivityIntent.PrewarmActivityTail -> "prewarm_tail"
        SharedActivityIntent.DetachPrewarmViewport -> "prewarm_detach"
        SharedActivityIntent.SurfaceHidden -> "surface_hidden"
        is SharedActivityIntent.RowOpened -> "row_opened"
        is SharedActivityIntent.ChannelAccessLost -> "channel_access_lost"
        is SharedActivityIntent.UndoObjectDone -> "undo_object_done"
        is SharedActivityIntent.UnfollowObject -> "unfollow_object"
        is SharedActivityIntent.ProjectThreadUnfollow -> "project_thread_unfollow"
        is SharedActivityIntent.ApplyRealtimeEvent -> "realtime"
        is SharedActivityIntent.ApplyRealtimeSummaryEvent -> "realtime_summary"
        is SharedActivityIntent.ApplyRemoteDone -> "remote_done"
    }

/** The realtime event behind a summary invalidation (#13126); null (`other`) for the person's own actions. */
internal fun SharedActivityIntent.summaryInvalidationTrigger(): InboxRefreshTrigger? =
    when (this) {
        is SharedActivityIntent.ApplyRealtimeEvent -> event.inboxRefreshTrigger()
        is SharedActivityIntent.ApplyRealtimeSummaryEvent -> event.inboxRefreshTrigger()
        else -> null
    }

/** The list already shows [messageId] as a row's latest message (web: the message store already had it). */
private fun SharedActivityState.showsMessage(messageId: MessageId): Boolean =
    snapshot?.items?.any { item -> item.lastMessageId == messageId || item.latestActivityMessageId == messageId } == true

internal fun SharedActivityIntent.invalidatesGlobalActivitySummary(): Boolean =
    when (this) {
        SharedActivityIntent.MarkAllRead,
        is SharedActivityIntent.MarkDone,
        is SharedActivityIntent.UnfollowThread,
        is SharedActivityIntent.FollowThread,
        is SharedActivityIntent.MarkRead,
        is SharedActivityIntent.MarkUnread,
        is SharedActivityIntent.ApplyReadReceiptFailure,
        is SharedActivityIntent.ApplyRealtimeEvent,
        is SharedActivityIntent.UndoObjectDone,
        is SharedActivityIntent.ApplyRemoteDone,
        is SharedActivityIntent.ApplyRealtimeSummaryEvent -> true
        is SharedActivityIntent.UnfollowObject,
        is SharedActivityIntent.ProjectThreadUnfollow,
        is SharedActivityIntent.ServerSelected,
        SharedActivityIntent.LoadInitial,
        SharedActivityIntent.Refresh,
        SharedActivityIntent.PullRefresh,
        SharedActivityIntent.LoadMore,
        is SharedActivityIntent.SetFilter,
        is SharedActivityIntent.ApplyReadReceiptPrediction,
        is SharedActivityIntent.ApplyReadReceiptResult,
        is SharedActivityIntent.PrewarmVisibleReplyThreads,
        is SharedActivityIntent.PrewarmActivityTail,
        SharedActivityIntent.DetachPrewarmViewport,
        SharedActivityIntent.SurfaceHidden,
        is SharedActivityIntent.RowOpened,
        is SharedActivityIntent.ChannelAccessLost -> false
    }

private fun RealtimeEvent.refreshesActivity(): Boolean = affectsActivityContent()

private fun InboxItem.replyThreadPrewarmKey(serverId: String): ActivityReplyThreadPrewarmKey? {
    if (kind != InboxItemKind.Thread) return null
    val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedParentChannelId = parentChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedParentMessageId = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedThreadChannelId = threadChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (normalizedThreadChannelId == normalizedParentChannelId) return null
    return ActivityReplyThreadPrewarmKey(
        serverId = normalizedServerId,
        parentChannelId = normalizedParentChannelId,
        parentMessageId = normalizedParentMessageId,
        threadChannelId = normalizedThreadChannelId
    )
}

private fun InboxItem.replyThreadSummaryFact(): SharedThreadSummaryFact =
    SharedThreadSummaryFact(
        replyCount = replyCount,
        latestActivityMessageId = latestActivityMessageId?.value,
        latestActivityPreview = latestActivityPreview,
        latestActivityAt = latestActivityAt,
        lastMessageId = lastMessageId?.value,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt
    )

private fun List<InboxItem>.withActivityThreadTaskProjection(
    serverId: String,
    tasksDataStore: SharedTasksDataStore?
): List<InboxItem> {
    tasksDataStore ?: return this
    val projection = tasksDataStore.projection(serverId, SharedTasksScope.Server)
    if (projection.status == SharedTasksDataStoreStatus.Idle || projection.tasks.isEmpty()) return this
    // Only tasks bound to a Thread row on this page can change it. Filtering first keeps
    // the work proportional to the page instead of the Server's whole task list.
    val pageParentMessageIds =
        mapNotNullTo(mutableSetOf()) { item ->
            item.parentMessageId?.value?.trim()?.takeIf { item.kind == InboxItemKind.Thread && it.isNotBlank() }
        }
    if (pageParentMessageIds.isEmpty()) return this
    val pageTasks =
        projection.tasks.filter { task ->
            listOfNotNull(task.messageId, task.parentMessageId).any { messageId ->
                messageId.value.trim() in pageParentMessageIds
            }
        }
    if (pageTasks.isEmpty()) return this
    val notificationProjections = tasksDataStore.notificationProjections(pageTasks.map { it.id })
    val taskBindingsByMessageId =
        pageTasks
            .flatMap { task ->
                val notificationProjection = notificationProjections[task.id]
                val localRoute =
                    notificationProjection
                        ?.routes
                        ?.singleOrNull { route -> route.serverId == serverId }
                listOfNotNull(task.messageId, task.parentMessageId)
                    .mapNotNull { messageId -> messageId.value.trim().takeIf { it.isNotBlank() } }
                    .distinct()
                    .map { messageId ->
                        messageId to
                            ActivityTaskNotificationBinding(
                                task = task,
                                canonicalMessageId = notificationProjection?.messageId,
                                localRoute = localRoute
                            )
                    }
            }
            .toMap()
    if (taskBindingsByMessageId.isEmpty()) return this

    // The Server normally persists one personal inbox fact for one canonical
    // message. Keep the client fail-safe too: if host + peer candidates are
    // both present during a refresh/realtime overlap, retain one row and
    // prefer the row already carrying this Server's local parent route.
    val preferredIndexByCanonicalMessage = mutableMapOf<MessageId, Int>()
    forEachIndexed { index, item ->
        val binding = item.activityTaskNotificationBinding(taskBindingsByMessageId) ?: return@forEachIndexed
        val canonicalMessageId = binding.canonicalMessageId ?: return@forEachIndexed
        val currentPreferred = preferredIndexByCanonicalMessage[canonicalMessageId]
        if (currentPreferred == null) {
            preferredIndexByCanonicalMessage[canonicalMessageId] = index
        } else {
            val preferredItem = this[currentPreferred]
            val localChannelId = binding.localRoute?.channelId
            val preferredIsLocal = preferredItem.parentChannelId == localChannelId
            val incomingIsLocal = item.parentChannelId == localChannelId
            if (!preferredIsLocal && incomingIsLocal) {
                preferredIndexByCanonicalMessage[canonicalMessageId] = index
            }
        }
    }
    return mapIndexedNotNull { index, item ->
        val binding = item.activityTaskNotificationBinding(taskBindingsByMessageId)
        val canonicalMessageId = binding?.canonicalMessageId
        if (canonicalMessageId != null && preferredIndexByCanonicalMessage[canonicalMessageId] != index) {
            null
        } else {
            item.withActivityThreadTaskProjection(binding)
        }
    }
}

private data class ActivityTaskNotificationBinding(
    val task: TaskSummary,
    val canonicalMessageId: MessageId?,
    val localRoute: SharedTaskNotificationRoute?
)

private fun InboxItem.activityTaskNotificationBinding(
    bindingsByMessageId: Map<String, ActivityTaskNotificationBinding>
): ActivityTaskNotificationBinding? {
    if (kind != InboxItemKind.Thread) return null
    val parentMessageKey = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return bindingsByMessageId[parentMessageKey]
}

private fun InboxItem.withActivityThreadTaskProjection(binding: ActivityTaskNotificationBinding?): InboxItem {
    if (kind != InboxItemKind.Thread || binding == null) return this
    val task = binding.task
    return copy(
        parentChannelId = binding.localRoute?.channelId ?: parentChannelId,
        taskNumber = task.taskNumber ?: taskNumber,
        taskStatus = task.status,
        taskClaimedByName = task.assignee?.displayName?.takeIf { it.isNotBlank() }
    )
}

private const val ActivityRealtimeCountedSeqLimit = 512

/**
 * [markCounted] records a counted message seq and answers false when it was counted before: a message
 * delivered live and again in a `sync:resume` batch is one unread.
 */
private fun RealtimeEvent.activitySummaryUnreadIncrement(
    snapshot: InboxSnapshot,
    activeConversationId: String?,
    currentUserId: String,
    markCounted: (seq: Long) -> Boolean,
): Int =
    when (this) {
        is MessageNew -> snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId, markCounted)
        is SyncResumeResponse ->
            messages
                .distinctBy { it.id }
                .sumOf { message ->
                    snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId, markCounted)
                }
        is ThreadUpdated ->
            unreadCount
                ?.takeIf {
                    it > 0 &&
                        !isActiveActivityConversation(threadChannelId.value, activeConversationId, snapshot) &&
                        snapshot.items.none {
                            it.isUnfollowedActivityThread &&
                                (it.threadChannelId == threadChannelId || it.channelId == threadChannelId)
                        }
                }
                ?: 0
        else -> 0
    }

private fun InboxSnapshot.unreadIncrementForMessage(
    message: Message,
    activeConversationId: String?,
    currentUserId: String,
    markCounted: (seq: Long) -> Boolean,
): Int {
    val channelId = message.channelId ?: message.threadChannelId ?: return 0
    if (message.author.id.value == currentUserId) return 0
    if (isActiveActivityConversation(channelId.value, activeConversationId, this)) return 0
    if (items.any { item ->
            item.isUnfollowedActivityThread &&
                (item.threadChannelId == channelId || item.channelId == channelId)
        }) return 0
    if (items.any { item -> item.latestActivityMessageId == message.id || item.lastMessageId == message.id }) return 0
    val seq = message.seq ?: return 1
    return if (markCounted(seq)) 1 else 0
}

private fun isActiveActivityConversation(
    streamChannelId: String,
    activeConversationId: String?,
    snapshot: InboxSnapshot
): Boolean {
    if (activeConversationId.isNullOrBlank()) return false
    if (streamChannelId == activeConversationId) return true
    return snapshot.items.any { item ->
        item.channelId.value == streamChannelId &&
            (
                item.key == activeConversationId ||
                    item.channelId.value == activeConversationId ||
                    item.threadChannelId?.value == activeConversationId ||
                    item.parentMessageId?.value == activeConversationId
                )
    }
}

private fun DataResult<InboxSnapshot>.activityDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content items=${data.items.size} unread=${data.totalUnreadCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

@Serializable
data class SharedActivitySenderProfile(
    val displayName: String
)

internal fun InboxItem.readReceiptChannelId(): ChannelId? =
    if (kind == InboxItemKind.Thread) threadChannelId else channelId

private fun InboxItem.activityUnreadAnchor(): MessageId? =
    firstUnreadMessageId ?: latestActivityMessageId ?: lastMessageId ?: parentMessageId

/** Web inboxStore `retractInboxItemPreview`: this row with [placeholder] for [messageId]'s previews. */
internal fun InboxItem.withRetractedPreview(messageId: MessageId, placeholder: String): InboxItem {
    val next =
        when {
            isChannelOrDmRow() ->
                if (lastMessageId == messageId) copy(lastMessagePreview = placeholder, latestRetracted = true) else this
            kind == InboxItemKind.Thread ->
                copy(
                    lastMessagePreview = if (lastMessageId == messageId) placeholder else lastMessagePreview,
                    latestActivityPreview =
                        if (latestActivityMessageId == messageId) placeholder else latestActivityPreview,
                    latestRetracted = latestRetracted || latestActivityMessageId == messageId,
                    parentMessagePreview = if (parentMessageId == messageId) placeholder else parentMessagePreview,
                    parentRetracted = parentRetracted || parentMessageId == messageId,
                    latestReplies =
                        latestReplies.map { reply ->
                            if (reply.messageId == messageId.value) reply.copy(content = placeholder) else reply
                        },
                )
            else -> this
        }
    return if (next == this) this else next
}

private fun InboxItem.isChannelOrDmRow(): Boolean =
    kind == InboxItemKind.Channel || kind == InboxItemKind.DirectMessage

/**
 * Never bound to a server and nothing shown or picked yet: the state the store is created
 * with. Its filter is the default, not a choice, so the first load may replace it with the
 * server's remembered one.
 */
internal fun SharedActivityState.isUnboundStart(): Boolean =
    ownerServerId == null && snapshot == null && filterSnapshots.isEmpty() && selectedFilter == InboxFilter.All
