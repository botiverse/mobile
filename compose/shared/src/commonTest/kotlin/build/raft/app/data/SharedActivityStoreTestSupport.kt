package build.raft.app.data

import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.FakeChannelRepository
import build.raft.app.toConversationSummary
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Shared SharedActivityStore test fixtures (moved out of SharedActivityStoreTest so
// facet suites such as SharedActivityStoreDoneOverlayTest reuse them).

/**
 * The store clock for a test scope: the virtual time of the TestCoroutineScheduler
 * in [scope]'s context (`runTest`'s `this` or `backgroundScope`). Never real
 * monotonic time. Legacy real-dispatcher (`runBlocking`) suites carry no scheduler
 * and get a clock frozen at 0, so holds never lapse and throttle windows never
 * elapse there; any case that asserts on time must run under `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun activityTestClock(scope: CoroutineScope): () -> Long {
    val scheduler = scope.coroutineContext[TestCoroutineScheduler] ?: return { 0L }
    return { scheduler.currentTime }
}

internal fun activityStore(
    scope: CoroutineScope,
    inboxRepository: InboxRepository = FakeInboxRepository(initialSnapshots = listOf(snapshot(emptyList()))),
    readReceiptRepository: FakeReadReceiptRepository = FakeReadReceiptRepository(),
    conversationRepository: FakeConversationRepository = FakeConversationRepository(),
    memberRepository: MemberRepository? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    channelRepository: ChannelRepository? = null,
    persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = emptyList(),
    filterPreferences: ActivityFilterPreferenceRepository? = null,
    tasksDataStore: SharedTasksDataStore? = null,
    perReceiverStateVersionStore: PerReceiverStateVersionStore =
        PerReceiverStateVersionStore(InMemoryRaftKeyValueStore()),
    authStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
    prewarmReplyThread: suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    replyThreadFreshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
    foregroundLoadGate: SharedForegroundLoadGate = SharedForegroundLoadGate(),
    replyThreadContentCached: suspend (ActivityReplyThreadPrewarmMarker) -> Boolean = { false },
    tailPrewarmDepthCap: Int = 20,
    readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    readStateV1Canary: ReadStateV1ProductionCanary? = null,
    onUnreadCountsChanged: () -> Unit = {},
    onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = {},
    onSummaryInvalidated: (ActivityUnreadSummaryInvalidation) -> Unit = {},
    realtimeBackgroundRefreshIntervalMillis: Long = 60_000L,
    nowMillis: () -> Long = activityTestClock(scope),
    localMutationHoldMillis: Long = ActivityLocalMutationHoldMillis,
    beforeRealtimeHighWaterFold: () -> Unit = {},
    onRealtimePatchOwnerEntered: () -> Unit = {},
    onReadReceiptPredictionOwnerEntered: () -> Unit = {},
    staleRecoveryDelayMillis: (attempt: Int, retryAfterMillis: Long?) -> Long =
        { attempt, retryAfter -> raftStaleRecoveryDelayMillis(attempt, retryAfter) },
    diagnosticsLogger: (String) -> Unit = {}
): SharedActivityStore =
    SharedActivityStore(
        authStore = authStore,
        inboxRepository = inboxRepository,
        readReceiptRepository = readReceiptRepository,
        conversationRepository = conversationRepository,
        memberRepository = memberRepository,
        serverMembersStore = serverMembersStore,
        channelRepository = channelRepository,
        tasksDataStore = tasksDataStore,
        perReceiverStateVersionStore = perReceiverStateVersionStore,
        persistSlices = persistSlices,
        filterPreferences = filterPreferences,
        prewarmReplyThread = prewarmReplyThread,
        replyThreadFreshnessLedger = replyThreadFreshnessLedger,
        foregroundLoadGate = foregroundLoadGate,
        replyThreadContentCached = replyThreadContentCached,
        tailPrewarmDepthCap = tailPrewarmDepthCap,
        readReceiptOwnerFactory = readReceiptOwnerFactory,
        readStateV1Canary = readStateV1Canary,
        onUnreadCountsChanged = onUnreadCountsChanged,
        onSummaryFact = onSummaryFact,
        onSummaryInvalidated = onSummaryInvalidated,
        scope = scope,
        realtimeBackgroundRefreshIntervalMillis = realtimeBackgroundRefreshIntervalMillis,
        nowMillis = nowMillis,
        localMutationHoldMillis = localMutationHoldMillis,
        beforeRealtimeHighWaterFold = beforeRealtimeHighWaterFold,
        onRealtimePatchOwnerEntered = onRealtimePatchOwnerEntered,
        onReadReceiptPredictionOwnerEntered = onReadReceiptPredictionOwnerEntered,
        staleRecoveryDelayMillis = staleRecoveryDelayMillis,
        diagnosticsLogger = diagnosticsLogger
    )

internal class FakeInboxRepository(
    private val initialSnapshots: List<InboxSnapshot>,
    private val moreSnapshots: List<InboxSnapshot> = emptyList(),
    private val failDone: Boolean = false,
    /** Suspends `loadInitial` call number N (1-based) until it returns: a load still on the network. */
    private val beforeInitialLoad: suspend (callNumber: Int) -> Unit = {}
) : InboxRepository {
    var initialLoadCount = 0
    var moreLoadCount = 0
    val initialLoadServerIds = mutableListOf<ServerId>()
    val initialLoadLimits = mutableListOf<Int>()
    val loadMoreOffsets = mutableListOf<Int>()
    val doneChannelIds = mutableListOf<ChannelId>()
    val doneFrontiers = mutableListOf<InboxDoneFrontier>()

    override suspend fun loadInitial(
        serverId: ServerId,
        filter: InboxFilter,
        limit: Int
    ): DataResult<InboxSnapshot> {
        val snapshot =
            initialSnapshots.getOrElse(initialLoadCount++) { initialSnapshots.last() }
                .also {
                    initialLoadServerIds += serverId
                    initialLoadLimits += limit
                }
        beforeInitialLoad(initialLoadCount)
        return DataResult.Content(snapshot)
    }

    override suspend fun loadMore(
        serverId: ServerId,
        filter: InboxFilter,
        offset: Int,
        limit: Int
    ): DataResult<InboxSnapshot> {
        loadMoreOffsets += offset
        return DataResult.Content(moreSnapshots.getOrElse(moreLoadCount++) { moreSnapshots.last() })
    }

    override suspend fun markDone(channelId: ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> {
        doneChannelIds += channelId
        doneFrontiers += frontier
        return if (failDone) {
            DataResult.Error(ScreenError("done_failed"))
        } else {
            DataResult.Content(InboxDoneResult(channelId))
        }
    }
}

internal class FakeReadReceiptRepository(
    private val pendingInboxReadAllResult: CompletableDeferred<DataResult<ReadReceiptResult>>? = null
) : ReadReceiptRepository {
    val readAllChannelIds = mutableListOf<ChannelId>()
    val unreadChannelIds = mutableListOf<ChannelId>()
    val inboxReadAllServerIds = mutableListOf<ServerId>()

    override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
        error("unused")

    override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> =
        error("unused")

    override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
        readAllChannelIds += channelId
        return DataResult.Content(ReadReceiptResult(channelId = channelId))
    }

    override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
        unreadChannelIds += channelId
        return DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 1))
    }

    override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
        inboxReadAllServerIds += serverId
        pendingInboxReadAllResult?.let { return it.await() }
        return DataResult.Content(ReadReceiptResult())
    }
}

internal class FakeConversationRepository : ConversationRepository {
    val doneThreadChannelIds = mutableListOf<ChannelId>()
    val doneThreadFrontiers = mutableListOf<InboxDoneFrontier>()
    val followedParentMessageIds = mutableListOf<MessageId>()
    val unfollowedThreadChannelIds = mutableListOf<ChannelId>()
    var followResult: DataResult<ThreadActionResult> =
        DataResult.Content(ThreadActionResult(ChannelId("thread-channel"), changed = true))
    var unfollowResult: DataResult<ThreadActionResult> =
        DataResult.Content(ThreadActionResult(ChannelId("thread-channel"), changed = true))
    /** When set, `unfollowThread` waits for it: an Unfollow still on the network. */
    var unfollowGate: CompletableDeferred<Unit>? = null

    /** `GET /channels/:id` calls (the access check) with their priority, and how each answers. */
    val detailRequests = mutableListOf<Pair<ChannelId, DataRequestPriority>>()
    var detailResponder: suspend (ChannelId) -> DataResult<ConversationSummary> =
        { DataResult.Empty(EmptyReason.NoMessages) }

    override suspend fun getConversationDetail(
        channelId: ChannelId,
        priority: DataRequestPriority
    ): DataResult<ConversationSummary> {
        detailRequests += channelId to priority
        return detailResponder(channelId)
    }

    override suspend fun getThread(threadId: ThreadId, includeFollowedSummaries: Boolean, limit: Int): DataResult<ThreadSnapshot> =
        error("unused")

    override suspend fun getReplyThread(parentChannelId: ChannelId, parentMessageId: MessageId, includeFollowedSummaries: Boolean, limit: Int): DataResult<ThreadSnapshot> =
        error("unused")

    override suspend fun getMessageContext(messageId: MessageId, channelId: ChannelId?): DataResult<ThreadSnapshot> =
        error("unused")

    override suspend fun loadOlderMessages(threadId: ThreadId, beforeSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
        error("unused")

    override suspend fun loadNewerMessages(threadId: ThreadId, afterSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
        error("unused")

    override suspend fun syncNewerMessages(threadId: ThreadId, sinceSeq: Long, limit: Int): DataResult<ThreadSnapshot> =
        error("unused")

    override suspend fun getChannelThreads(channelId: ChannelId, limit: Int): DataResult<ThreadListSnapshot> =
        error("unused")

    override suspend fun getFollowedThreads(serverId: ServerId, limit: Int): DataResult<ThreadListSnapshot> =
        error("unused")

    override suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: MessageId): DataResult<ThreadActionResult> =
        error("unused")

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> {
        followedParentMessageIds += parentMessageId
        return followResult
    }

    override suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> {
        unfollowedThreadChannelIds += threadChannelId
        unfollowGate?.await()
        return unfollowResult
    }

    override suspend fun markThreadDone(threadChannelId: ChannelId, frontier: InboxDoneFrontier): DataResult<ThreadActionResult> {
        doneThreadChannelIds += threadChannelId
        doneThreadFrontiers += frontier
        return DataResult.Content(ThreadActionResult(threadChannelId = threadChannelId, changed = true))
    }

    override suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
        error("unused")

    override suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult> =
        error("unused")

    override suspend fun getAttachmentOpenTarget(attachmentId: AttachmentId): DataResult<AttachmentOpenTarget> =
        error("unused")

    override suspend fun getChannelFiles(channelId: ChannelId, limit: Int, cursor: String?): DataResult<ChannelFilesSnapshot> =
        error("unused")

    override suspend fun sendMessage(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean,
        randomId: String?
    ): DataResult<SendMessageResult> =
        error("unused")
}

internal fun activityItem(
    channelId: String,
    unreadCount: Int = 0
): InboxItem =
    InboxItem(
        key = "channel:$channelId",
        kind = InboxItemKind.Channel,
        channelId = ChannelId(channelId),
        channelName = channelId,
        unreadCount = unreadCount,
        firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$channelId") else null,
        lastMessageId = MessageId("last-$channelId"),
        latestActivityMessageId = MessageId("latest-$channelId")
    )

internal fun message(
    id: String,
    channelId: String = "channel-1",
    authorId: String = "user-2",
    body: String = "message $id",
    timestamp: String = "2026-07-13T03:00:00Z",
    seq: Long? = null,
    conversationContext: MessageConversationContext? = null
): Message =
    Message(
        id = MessageId(id),
        threadId = ThreadId(channelId),
        channelId = ChannelId(channelId),
        author =
            MemberProfile(
                id = MemberId(authorId),
                displayName = "User $authorId",
                handle = authorId,
                kind = MemberKind.Human
            ),
        timestampLabel = timestamp,
        body = body,
        seq = seq,
        conversationContext = conversationContext
    )

internal fun threadItem(
    channelId: String,
    threadChannelId: String? = channelId,
    unreadCount: Int = 0
): InboxItem =
    InboxItem(
        key = "thread:$channelId",
        kind = InboxItemKind.Thread,
        channelId = ChannelId(channelId),
        channelName = "general",
        threadChannelId = threadChannelId?.let(::ChannelId),
        parentChannelId = ChannelId("parent-channel"),
        parentMessageId = MessageId("parent-message"),
        unreadCount = unreadCount,
        firstUnreadMessageId = if (unreadCount > 0) MessageId("first-$channelId") else null,
        latestActivityMessageId = MessageId("latest-thread")
    )

internal fun mentionActionItem(resolutionId: String): InboxItem =
    InboxItem(
        key = "mention_action:$resolutionId",
        kind = InboxItemKind.MentionAction,
        channelId = ChannelId("channel-1"),
        channelName = "android-dev",
        channelKind = ConversationKind.Channel,
        lastMessageId = MessageId("message-1"),
        lastMessagePreview = "Can someone ping @missing-human?",
        lastMessageAt = "2026-06-26T02:40:00Z",
        mentionActionId = resolutionId,
        pendingMentionActions =
            listOf(
                PendingMentionAction(
                    resolutionId = resolutionId,
                    messageId = MessageId("message-1"),
                    channelId = ChannelId("channel-1"),
                    channelName = "android-dev",
                    targetType = "user",
                    targetHandle = "missing-human",
                    availableActions = listOf("notify", "invite")
                )
            )
    )

internal fun snapshot(
    items: List<InboxItem>,
    serverId: String = "server-1",
    hasMore: Boolean = false,
    nextOffset: Int? = null,
    totalCount: Int = items.size,
    totalUnreadCount: Int = items.sumOf { it.unreadCount }
): InboxSnapshot =
    InboxSnapshot(
        server = ServerSummary(id = ServerId(serverId), name = "Slock"),
        items = items,
        totalCount = totalCount,
        totalUnreadCount = totalUnreadCount,
        hasMore = hasMore,
        nextOffset = nextOffset
    )

internal fun sampleSession(
    userId: String = "user-1",
    serverId: String = "server-1"
): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = userId, email = "$userId@slock.local", displayName = "User One"),
        accessToken = "access",
        refreshToken = "refresh",
        selectedServerId = serverId,
        servers = listOf(StoredAuthServer(id = serverId, name = "Slock"))
    )

internal class InMemoryPersistStorage : MviPersistStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

/** The same inbox body served from the server's remembered copy (slock `routes/rwStaleRead.ts`). */
internal fun rwStaleInboxResponse(response: HomeNetworkResponse): HomeNetworkResponse =
    response.copy(responseHeaders = mapOf("X-Raft-Stale" to listOf("rw_overloaded")))

/** slock `routes/errorResponse.ts`: `503` + `Retry-After: 2` + code `rw_overloaded`. */
internal fun rwOverloadedInboxResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 503,
        bodyText = null,
        errorBodyText = """{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}""",
        responseHeaders = mapOf("Retry-After" to listOf("2"))
    )

/**
 * `GET channels/inbox` answered from [responses] in order (the last one repeats), for the real
 * [RealInboxRepository] under virtual time; [requestTimes] records each request's virtual time.
 */
internal class ScriptedInboxNetwork(
    private val testScope: TestScope,
    private val responses: List<HomeNetworkResponse>,
    private val gate: CompletableDeferred<Unit>? = null
) : RaftNetworkClient {
    val requestTimes = mutableListOf<Long>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        check(path == "channels/inbox" && method == "GET") { "unexpected $method $path" }
        requestTimes += testScope.testScheduler.currentTime
        gate?.await()
        return responses.getOrElse(requestTimes.size - 1) { responses.last() }
    }
}
