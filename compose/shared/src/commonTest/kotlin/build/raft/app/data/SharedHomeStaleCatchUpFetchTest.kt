package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Home is served from a read model that lags the primary (median ~2.6 s, p99 > 20 s). A catch-up
 * re-fetch after foreground / socket reconnect that is older than a realtime message already applied
 * must not roll the row back; a fetch that reached the message replaces it as before.
 */
class SharedHomeStaleCatchUpFetchTest {
    private data class Row(val seq: Long?, val unread: Int, val name: String = "all")

    @Test
    fun aStaleRefreshKeepsTheNewerRealtimeRowUntilAFetchReachesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Start (seq 5, read), then a lagging catch-up (still seq 5, renamed), then a caught-up one (seq 6, read elsewhere).
        val answers = ArrayDeque(listOf(Row(5, 0), Row(5, 0, name = "all-renamed"), Row(6, 0)))
        val store = homeStore(scope) { answers.removeFirst() }
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", message(seq = 6)))
        assertEquals(Triple(6L, "body-6", 1), store.row().let { Triple(it.lastMessageSeq, it.lastMessagePreview, it.unreadCount) })

        store.accept(SharedHomeIntent.Refresh)
        val afterStale = store.row()
        assertEquals(6L, afterStale.lastMessageSeq, "the lagging fetch does not roll the message back")
        assertEquals("body-6", afterStale.lastMessagePreview)
        assertEquals(1, afterStale.unreadCount, "nor the unread it brought")
        assertEquals("all-renamed", afterStale.name, "other fields follow the fetch")

        store.accept(SharedHomeIntent.Refresh)
        val afterCaughtUp = store.row()
        assertEquals(6L, afterCaughtUp.lastMessageSeq)
        assertEquals(0, afterCaughtUp.unreadCount, "a fetch that reached the message decides (read on another device)")
        assertEquals("server-6", afterCaughtUp.lastMessagePreview)
        scope.cancel()
    }

    private fun homeStore(scope: CoroutineScope, next: () -> Row): SharedHomeStore {
        val authStore = InMemoryRaftAuthSessionStore(session())
        return SharedHomeStore(
            authStore = authStore,
            dataSource =
                SharedHomeDataSource(
                    sessionStore = authStore,
                    api = EmptyHomeApi,
                    parseHome = { payload, _ -> DataResult.Content(home(payload.serverId, next())) },
                ),
            scope = scope,
        )
    }

    private fun SharedHomeStore.row(): HomeChannelRow =
        (state.value.content as SharedHomeContentState.Content).snapshot.channels.single()

    private fun session() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock")),
        )

    private val me =
        MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin@slock.ai",
            kind = MemberKind.Human,
            presence = Presence.Active,
        )

    private fun home(serverId: ServerId, row: Row) =
        HomeSnapshot(
            server = ServerSummary(serverId, "Slock", unreadCount = row.unread),
            currentMember = me,
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        name = row.name,
                        unreadCount = row.unread,
                        unreadCountKnown = true,
                        lastMessageId = row.seq?.let { MessageId("m-$it") },
                        lastMessageSeq = row.seq,
                        lastMessagePreview = row.seq?.let { "server-$it" },
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = row.unread,
        )

    private fun message(seq: Long) =
        MessageNew(
            Message(
                id = MessageId("m-$seq"),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                seq = seq,
                author = me.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy@slock.ai"),
                timestampLabel = "12:01",
                body = "body-$seq",
            )
        )

    private object EmptyHomeApi : RaftHomeApi {
        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
    }
}
