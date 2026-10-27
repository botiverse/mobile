package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home row menu "Leave Channel" (web botiverse/slock #11289 Sidebar -> channelStore.leaveChannel
 * -> `POST /channels/:id/leave`), dispatched after its confirmation.
 */
class SharedHomeStoreLeaveChannelTest {
    // A successful leave hands the channel to onChannelLeft, which the provider wires to
    // publishChannelAccessLost: the same Activity ChannelAccessLost hand-off as settings' Leave.
    @Test
    fun publicChannelStaysListedAsNotJoinedAndCallsLeaveOnce() = runTest {
        val response = CompletableDeferred<DataResult<ChannelLeaveResult>>()
        val calls = mutableListOf<ChannelId>()
        val left = mutableListOf<ChannelId>()
        val store =
            store(
                leaveChannel = { channelId ->
                    calls += channelId
                    response.await()
                },
                onChannelLeft = { left += it },
            )
        store.accept(SharedHomeIntent.Start)

        val action = launch { store.accept(SharedHomeIntent.LeaveChannel("channel-public")) }
        runCurrent()

        val row = store.snapshot().channels.single { it.channelId == ChannelId("channel-public") }
        assertFalse(row.joined)
        assertEquals(listOf(ChannelId("channel-public")), calls)
        // Activity hears of it only once the server has acknowledged the leave.
        assertTrue(left.isEmpty())

        response.complete(DataResult.Content(ChannelLeaveResult(ChannelId("channel-public"))))
        action.join()
        assertFalse(store.snapshot().channels.single { it.channelId == ChannelId("channel-public") }.joined)
        assertEquals(listOf(ChannelId("channel-public")), left)
    }

    @Test
    fun privateAndJointChannelsLeaveTheListAndTheSelection() = runTest {
        for (conversationId in listOf("channel-private", "channel-joint")) {
            val calls = mutableListOf<ChannelId>()
            val left = mutableListOf<ChannelId>()
            val store =
                store(
                    leaveChannel = { channelId ->
                        calls += channelId
                        DataResult.Content(ChannelLeaveResult(channelId))
                    },
                    onChannelLeft = { left += it },
                )
            store.accept(SharedHomeIntent.Start)
            store.accept(SharedHomeIntent.SelectConversation(conversationId))

            store.accept(SharedHomeIntent.LeaveChannel(conversationId))

            assertEquals(listOf(ChannelId(conversationId)), calls)
            assertEquals(listOf(ChannelId(conversationId)), left, conversationId)
            assertTrue(store.snapshot().channels.none { it.channelId == ChannelId(conversationId) }, conversationId)
            assertNull(store.state.value.selectedConversationId, conversationId)
        }
    }

    @Test
    fun failedLeaveRestoresTheRowAndReportsIt() = runTest {
        val failures = mutableListOf<String>()
        val left = mutableListOf<ChannelId>()
        val store =
            store(
                leaveChannel = { DataResult.Error(ScreenError(code = "http_403", retryable = false)) },
                onFailure = { failures += it },
                onChannelLeft = { left += it },
            )
        store.accept(SharedHomeIntent.Start)
        val before = store.snapshot()

        store.accept(SharedHomeIntent.LeaveChannel("channel-private"))

        assertEquals(before.channels, store.snapshot().channels)
        assertEquals(listOf("Unable to leave channel"), failures)
        assertTrue(left.isEmpty())
    }

    @Test
    fun unknownConversationSendsNoRequest() = runTest {
        val calls = mutableListOf<ChannelId>()
        val store =
            store(leaveChannel = { channelId ->
                calls += channelId
                DataResult.Content(ChannelLeaveResult(channelId))
            })
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.LeaveChannel("missing"))

        assertTrue(calls.isEmpty())
    }

    private fun TestScope.store(
        leaveChannel: suspend (ChannelId) -> DataResult<ChannelLeaveResult>,
        onFailure: (String) -> Unit = {},
        onChannelLeft: (ChannelId) -> Unit = {},
    ): SharedHomeStore {
        val session = sampleSession()
        val home = sampleHome()
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = EmptyHomeApi(),
                    parseHome = { payload, _ -> DataResult.Content(home.copy(server = home.server.copy(id = payload.serverId))) },
                ),
            scope = backgroundScope,
            leaveChannel = leaveChannel,
            pinUpdateFailureCallback = onFailure,
            onChannelLeft = onChannelLeft,
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

    private fun row(id: String, channelType: String? = null, kind: ConversationKind = ConversationKind.Channel) =
        HomeChannelRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            channelType = channelType,
            kind = kind,
            name = id.removePrefix("channel-"),
            unreadCountKnown = true,
        )

    private fun sampleHome() =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "Slock"),
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
                    row("channel-public", channelType = "channel"),
                    row("channel-private", channelType = "private"),
                    row("channel-joint", kind = ConversationKind.Joint),
                ),
            directMessages = emptyList(),
            unreadTotal = 0,
        )

    private class EmptyHomeApi : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun directMessages(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)
        override suspend fun serverUnreadSummary(priority: DataRequestPriority) = HomeNetworkResponse(code = 200, bodyText = "[]")
    }
}
