package build.raft.app.data

import build.raft.app.RaftAdaptiveDetailSelection
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftAdaptiveWorkspaceState
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.toRaftAdaptiveDetailIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for `#wg-mobile-sync:#257`:
 * 1. `SharedHomeStore.refreshFollowedThreadUnread` applies known read-state projections so a
 *    lagging `channels/unread?summary=1` response never briefly brings back a thread just read.
 * 2. Home's "thread is open" check includes a reply thread open in the wide side pane (and stops
 *    counting it as open when the host is hidden or the workspace is not visible in dual-pane).
 */
class SharedHomeFollowedThreadUnreadProjectionTest {
    private val serverId = ServerId("server-1")
    private val me =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin@slock.ai",
            kind = MemberKind.Human,
            presence = Presence.Active,
        )
    private val general = ChannelId("channel-1")
    private val threadJustRead = ChannelId("thread-1")
    private val threadNewer = ChannelId("thread-2")

    @Test
    fun laggingFollowedThreadReloadDoesNotResurrectThreadWithPendingOrAcceptedReadState() = runTest {
        val pendingReadGate = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        // thread-3 already has an accepted read-state checkpoint ahead of the lagging summary.
        versionStore.writeReadStateCheckpoint(
            serverId = serverId,
            memberId = me.id,
            scopeId = ChannelId("thread-3"),
            checkpoint = PerReceiverReadStateCheckpoint(version = 5L, maxReadSeq = 30L),
        )
        val owner = ReadReceiptOwner(environmentId = "env", accountId = me.id.value, serverId = serverId.value)
        val pendingStore = ReadReceiptPendingStore(InMemoryRaftKeyValueStore())
        val coordinator =
            ReadReceiptCoordinator(
                scope = backgroundScope,
                repository =
                    object : ReadReceiptRepository {
                        override suspend fun getUnreadCounts(serverId: ServerId) =
                            DataResult.Empty(EmptyReason.NoChannels)

                        override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long) =
                            pendingReadGate.await()

                        override suspend fun markChannelReadAll(channelId: ChannelId) =
                            pendingReadGate.await()

                        override suspend fun markInboxReadAll(serverId: ServerId) =
                            DataResult.Content(ReadReceiptResult(channelId = general))

                        override suspend fun markChannelUnread(channelId: ChannelId) =
                            DataResult.Content(ReadReceiptResult(channelId = channelId))
                    },
                pendingStore = pendingStore,
            )
        var summaryCalls = 0
        val api =
            StubHomeApi(
                unreadSummaryBody = {
                    summaryCalls += 1
                    """
                    {
                      "channels": {
                        "thread-1": {
                          "unreadCount": 2,
                          "hasMention": false,
                          "hasAnyMention": false,
                          "parentChannelId": "channel-1",
                          "readState": {
                            "kind": "present",
                            "maxReadSeq": "8",
                            "readStateVersion": 1,
                            "markedUnread": false,
                            "latestActivity": { "messageId": "msg-10", "seq": "10" }
                          }
                        },
                        "thread-2": {
                          "unreadCount": 1,
                          "hasMention": false,
                          "hasAnyMention": false,
                          "parentChannelId": "channel-1",
                          "readState": {
                            "kind": "present",
                            "maxReadSeq": "15",
                            "readStateVersion": 1,
                            "markedUnread": false,
                            "latestActivity": { "messageId": "msg-16", "seq": "16" }
                          }
                        },
                        "thread-3": {
                          "unreadCount": 1,
                          "hasMention": false,
                          "hasAnyMention": false,
                          "parentChannelId": "channel-1",
                          "readState": {
                            "kind": "present",
                            "maxReadSeq": "20",
                            "readStateVersion": 4,
                            "markedUnread": false,
                            "latestActivity": { "messageId": "msg-30", "seq": "30" }
                          }
                        }
                      },
                      "threadUnread": {
                        "channel-1": { "count": 3, "hasMention": false }
                      }
                    }
                    """.trimIndent()
                }
            )
        val store =
            createStore(
                api = api,
                versionStore = versionStore,
                coordinator = coordinator,
                owner = owner,
            )
        store.accept(SharedHomeIntent.Start)
        assertEquals(setOf("thread-1"), store.snapshot().followedThreadUnread.keys)
        summaryCalls = 0

        // User just read thread-1 to seq 10 (still pending ack on server).
        store.applyReadReceiptPrediction(coordinator.markReadToSeq(owner, threadJustRead, 10L))
        assertEquals(emptyMap(), store.snapshot().followedThreadUnread)

        // An unknown thread gets a reply -> coalesced followed-thread summary refresh fires.
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId.value, replyEvent(threadNewer, "parent-2")))
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()

        assertEquals(1, summaryCalls)
        // thread-1 (pending read seq 10 >= latest 10) and thread-3 (accepted v5 seq 30 >= latest 30)
        // stay read; thread-2 (genuine new unread at seq 16) is kept.
        assertEquals(setOf("thread-2"), store.snapshot().followedThreadUnread.keys)
        assertEquals(HomeParentThreadUnread(count = 1, hasMention = false), store.snapshot().parentThreadUnread(general))
        assertEquals(16L, store.snapshot().unreadLatestActivitySeqs["thread-2"])

        // Even after the pending read is acknowledged (clearing pendingStore), a subsequent lagging
        // summary still respects the coordinator's acknowledged seq and does not resurrect thread-1.
        pendingReadGate.complete(DataResult.Content(ReadReceiptResult(channelId = threadJustRead, seq = 10L)))
        runCurrent()
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId.value, replyEvent(ChannelId("thread-4"), "parent-4")))
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()

        assertEquals(2, summaryCalls)
        assertEquals(setOf("thread-2"), store.snapshot().followedThreadUnread.keys)
    }

    @Test
    fun sidePaneOpenThreadSuppressesUnreadBumpAndReloadWhileSeen() = runTest {
        var hostSeen = true
        var sideThread: AdaptiveOpenSideThread? =
            AdaptiveOpenSideThread(conversationIds = setOf(threadJustRead.value), parentMessageId = "parent-1")
        var summaryCalls = 0
        val api =
            StubHomeApi(
                unreadSummaryBody = {
                    summaryCalls += 1
                    EMPTY_CHANNEL_UNREAD_SUMMARY_BODY
                }
            )
        val store =
            createStore(
                api = api,
                selectedConversationSeen = { hostSeen },
                openSideThread = { sideThread },
            )
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.SelectConversation(general.value))
        summaryCalls = 0

        // Main detail is #general, while thread-1 is open in the side pane: reply into thread-1 is ignored.
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId.value, replyEvent(threadJustRead, "parent-1")))
        assertEquals(2, store.snapshot().followedThreadUnread[threadJustRead.value]?.unreadCount)

        // Reply into a read thread currently open in the side pane by parentMessageId does not schedule a reload.
        sideThread = AdaptiveOpenSideThread(conversationIds = emptySet(), parentMessageId = "parent-new")
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId.value, replyEvent(threadNewer, "parent-new")))
        advanceTimeBy(HOME_FOLLOWED_THREAD_REFRESH_DELAY_MS)
        runCurrent()
        assertEquals(0, summaryCalls)

        // When the window/host is not seen, a reply into the side-pane thread DOES count as unread.
        sideThread = AdaptiveOpenSideThread(conversationIds = setOf(threadJustRead.value), parentMessageId = "parent-1")
        hostSeen = false
        store.accept(SharedHomeIntent.ApplyRealtimeEvent(serverId.value, replyEvent(threadJustRead, "parent-1")))
        assertEquals(3, store.snapshot().followedThreadUnread[threadJustRead.value]?.unreadCount)
    }

    @Test
    fun adaptiveSessionOpenSideThreadMatchRequiresVisibleDualPaneMatchingOwner() {
        val mainPayload =
            WireThreadLaunchPayload(
                threadId = general.value,
                surfaceKind = RaftThreadSurfaceKind.Channel,
            )
        val mainIdentity = checkNotNull(mainPayload.toRaftAdaptiveDetailIdentity(serverId.value))
        val mainSelection = RaftAdaptiveDetailSelection(identity = mainIdentity, generation = 1L)
        val sidePayload =
            WireThreadLaunchPayload(
                threadId = threadJustRead.value,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = general.value,
                parentMessageId = "parent-1",
            )
        val sideIdentity = checkNotNull(sidePayload.toRaftAdaptiveDetailIdentity(serverId.value))
        val side = AdaptiveSideThread(owner = mainSelection, identity = sideIdentity, payload = sidePayload, generation = 2L)
        val dualPane =
            RaftAdaptiveWorkspaceState(
                shellInstanceId = "shell-1",
                serverId = serverId.value,
                pane = RaftAdaptivePaneState(widthPx = 1280, heightPx = 800, density = 1f, layout = RaftAdaptivePaneLayout.DualPane),
                parentVisible = true,
                selection = mainSelection,
                generationCursor = 2L,
            )

        val visibleSession = SharedAdaptiveWorkspaceSession(workspace = dualPane, sideThread = side)
        val matched = visibleSession.openSideThreadMatch()
        assertNotNull(matched)
        assertTrue(threadJustRead.value in matched.conversationIds)
        assertEquals("parent-1", matched.parentMessageId)

        // Hidden workspace or compact layout does not treat the side thread as seen on screen.
        assertNull(visibleSession.copy(workspace = dualPane.copy(parentVisible = false)).openSideThreadMatch())
        assertNull(
            visibleSession
                .copy(workspace = dualPane.copy(pane = dualPane.pane.copy(layout = RaftAdaptivePaneLayout.Compact)))
                .openSideThreadMatch()
        )
    }

    private fun TestScope.createStore(
        api: RaftHomeApi,
        versionStore: PerReceiverStateVersionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore()),
        coordinator: ReadReceiptCoordinator? = null,
        owner: ReadReceiptOwner? = null,
        selectedConversationSeen: () -> Boolean = { true },
        openSideThread: () -> AdaptiveOpenSideThread? = { null },
    ): SharedHomeStore {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = me.id.value, email = "artin@slock.ai"),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = serverId.value,
                servers = listOf(StoredAuthServer(id = serverId.value, name = "Slock", slug = "slock")),
            )
        val initialHome =
            HomeSnapshot(
                server = ServerSummary(serverId, "Slock", unreadCount = 0),
                currentMember = me,
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId(general.value),
                            channelId = general,
                            name = "general",
                            unreadCount = 0,
                            unreadCountKnown = true,
                        )
                    ),
                directMessages = emptyList(),
                unreadTotal = 0,
                followedThreadUnread =
                    mapOf(
                        threadJustRead.value to HomeFollowedThreadUnread(general, unreadCount = 2, hasMention = false)
                    ),
                unreadLatestActivitySeqs = mapOf(threadJustRead.value to 10L),
            )
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = api,
                    parseHome = { payload, _ ->
                        DataResult.Content(initialHome.copy(server = initialHome.server.copy(id = payload.serverId)))
                    },
                    parseDispatcher = Dispatchers.Unconfined,
                ),
            scope = backgroundScope,
            perReceiverStateVersionStore = versionStore,
            readReceiptCoordinator = coordinator,
            readReceiptOwnerFactory = owner?.let { resolved -> { _, _ -> resolved } },
            selectedConversationSeen = selectedConversationSeen,
            openSideThread = openSideThread,
        )
    }

    private fun replyEvent(thread: ChannelId, parentMessageId: String) =
        ThreadUpdated(
            parentMessageId = MessageId(parentMessageId),
            threadChannelId = thread,
            replyCount = 3,
            lastReplyAt = "2026-11-23T10:00:00Z",
            participantIds = listOf(MemberId("user-2")),
            latestReply =
                ReplyPreview(
                    messageId = "reply-3",
                    seq = 16L,
                    content = "hello",
                    senderId = "user-2",
                    senderType = "user",
                    senderName = "Cindy",
                    createdAt = "2026-11-23T10:00:00Z",
                ),
        )

    private fun SharedHomeStore.snapshot(): HomeSnapshot =
        (state.value.content as SharedHomeContentState.Content).snapshot

    private class StubHomeApi(
        private val unreadSummaryBody: () -> String,
    ) : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun directMessages(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = unreadSummaryBody())
        override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
    }
}
