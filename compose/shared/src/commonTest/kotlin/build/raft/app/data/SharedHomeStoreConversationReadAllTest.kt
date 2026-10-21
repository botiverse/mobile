package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sidebar "Mark All as Read" (web botiverse/slock d60367a1
 * `SidebarReadToggleMenuItem` -> `messageStore.markConversationRead` ->
 * `POST /channels/:id/conversation-read-all`).
 */
class SharedHomeStoreConversationReadAllTest {
    @Test
    fun clearsRowAndItsFollowedThreadsBeforeTheAckThenHandsTheAckToActivity() = runTest {
        val response = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val calls = mutableListOf<ChannelId>()
        val acks = mutableListOf<Pair<String, PerReceiverReadStateChanged>>()
        val store =
            store(
                markConversationReadAll = { channelId ->
                    calls += channelId
                    response.await()
                },
                onAck = { accountId, event -> acks += accountId to event }
            )
        store.accept(SharedHomeIntent.Start)

        val action = launch { store.accept(SharedHomeIntent.MarkConversationReadAll("channel-1")) }
        runCurrent()

        val optimistic = store.snapshot()
        val row = optimistic.channels.first { it.channelId == ChannelId("channel-1") }
        assertEquals(0, row.unreadCount)
        assertEquals(0, row.mentionCount)
        assertEquals(1, optimistic.unreadTotal)
        assertEquals(setOf("thread-other"), optimistic.followedThreadUnread.keys)
        assertEquals(listOf(ChannelId("channel-1")), calls)
        assertTrue(acks.isEmpty())

        response.complete(
            DataResult.Content(
                ReadReceiptResult(
                    channelId = ChannelId("channel-1"),
                    scopes =
                        listOf(
                            ReadStateV1AckScope(ChannelId("channel-1"), readStateVersion = 4, maxReadSeq = 12),
                            ReadStateV1AckScope(ChannelId("thread-1"), readStateVersion = 2, maxReadSeq = 7),
                            ReadStateV1AckScope(ChannelId("thread-unversioned"), readStateVersion = null, maxReadSeq = 3),
                        )
                )
            )
        )
        action.join()

        val (accountId, event) = acks.single()
        assertEquals("user-1", accountId)
        assertEquals(ServerId("server-1"), event.serverId)
        assertEquals(
            listOf(
                PerReceiverReadStateScope(ChannelId("channel-1"), maxReadSeq = 12, readStateVersion = 4),
                PerReceiverReadStateScope(ChannelId("thread-1"), maxReadSeq = 7, readStateVersion = 2),
            ),
            event.scopes
        )
        assertEquals(0, store.snapshot().channels.first { it.channelId == ChannelId("channel-1") }.unreadCount)
    }

    @Test
    fun failedRequestPutsBackWhatTheActionClearedAndKeepsNewerUnread() = runTest {
        val response = CompletableDeferred<DataResult<ReadReceiptResult>>()
        val failures = mutableListOf<String>()
        val acks = mutableListOf<PerReceiverReadStateChanged>()
        val store =
            store(
                markConversationReadAll = { response.await() },
                onAck = { _, event -> acks += event },
                onFailure = { failures += it }
            )
        store.accept(SharedHomeIntent.Start)
        val before = store.snapshot()

        val action = launch { store.accept(SharedHomeIntent.MarkConversationReadAll("channel-1")) }
        runCurrent()
        assertEquals(0, store.snapshot().channels.first { it.channelId == ChannelId("channel-1") }.unreadCount)

        response.complete(DataResult.Error(ScreenError(code = "server_unavailable", retryable = true)))
        action.join()

        val restored = store.snapshot()
        val row = restored.channels.first { it.channelId == ChannelId("channel-1") }
        assertEquals(3, row.unreadCount)
        assertEquals(1, row.mentionCount)
        assertEquals(MessageId("m-first"), row.firstUnreadMessageId)
        assertEquals(before.unreadTotal, restored.unreadTotal)
        assertEquals(before.followedThreadUnread, restored.followedThreadUnread)
        assertEquals(listOf("Unable to mark conversation read"), failures)
        assertTrue(acks.isEmpty())
    }

    @Test
    fun conversationWithNothingUnreadSendsNoRequest() = runTest {
        val calls = mutableListOf<ChannelId>()
        val store =
            store(
                markConversationReadAll = { channelId ->
                    calls += channelId
                    DataResult.Empty(EmptyReason.NoMessages)
                }
            )
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.MarkConversationReadAll("channel-2"))

        assertTrue(calls.isEmpty())
        assertNull(store.snapshot().channels.first { it.channelId == ChannelId("channel-2") }.firstUnreadMessageId)
    }

    @Test
    fun threadOnlyUnreadIsStillReadThroughTheConversation() = runTest {
        val calls = mutableListOf<ChannelId>()
        val store =
            store(
                home = sampleHome().let { home ->
                    home.copy(
                        followedThreadUnread =
                            home.followedThreadUnread +
                                ("thread-3" to HomeFollowedThreadUnread(ChannelId("channel-2"), unreadCount = 5))
                    )
                },
                markConversationReadAll = { channelId ->
                    calls += channelId
                    DataResult.Content(ReadReceiptResult(channelId = channelId))
                }
            )
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.MarkConversationReadAll("channel-2"))

        assertEquals(listOf(ChannelId("channel-2")), calls)
        assertEquals(setOf("thread-1", "thread-other"), store.snapshot().followedThreadUnread.keys)
    }

    private fun TestScope.store(
        home: HomeSnapshot = sampleHome(),
        markConversationReadAll: suspend (ChannelId) -> DataResult<ReadReceiptResult>,
        onAck: (String, PerReceiverReadStateChanged) -> Unit = { _, _ -> },
        onFailure: (String) -> Unit = {}
    ): SharedHomeStore {
        val session = sampleSession()
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = EmptyHomeApi(),
                    parseHome = { payload, _ -> DataResult.Content(home.copy(server = home.server.copy(id = payload.serverId))) },
                ),
            scope = backgroundScope,
            markConversationReadAll = markConversationReadAll,
            pinUpdateFailureCallback = onFailure,
            onConversationReadAllAcknowledged = onAck,
        )
    }

    private fun SharedHomeStore.snapshot(): HomeSnapshot =
        (state.value.content as SharedHomeContentState.Content).snapshot

    private fun sampleSession() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock")),
        )

    private fun sampleHome() =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "Slock", unreadCount = 4),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin@slock.ai",
                    kind = MemberKind.Human,
                    presence = Presence.Active,
                ),
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        name = "general",
                        unreadCount = 3,
                        unreadCountKnown = true,
                        mentionCount = 1,
                        firstUnreadMessageId = MessageId("m-first"),
                    ),
                    HomeChannelRow(
                        threadId = ThreadId("channel-2"),
                        channelId = ChannelId("channel-2"),
                        name = "random",
                        unreadCount = 0,
                        unreadCountKnown = true,
                    ),
                    HomeChannelRow(
                        threadId = ThreadId("channel-3"),
                        channelId = ChannelId("channel-3"),
                        name = "other",
                        unreadCount = 1,
                        unreadCountKnown = true,
                    ),
                ),
            directMessages = emptyList(),
            unreadTotal = 4,
            followedThreadUnread =
                mapOf(
                    "thread-1" to HomeFollowedThreadUnread(ChannelId("channel-1"), unreadCount = 2, hasMention = true),
                    "thread-other" to HomeFollowedThreadUnread(ChannelId("channel-3"), unreadCount = 1),
                ),
        )

    private class EmptyHomeApi : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun directMessages(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)
        override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
    }
}
