package build.raft.app.data

import build.raft.app.SharedHomeConversationRowProjector
import build.raft.app.model.ChannelId
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Persistence audit item 6: with `sync_core_read_state_v1` off, a cold start with no network keeps
 * the last-known read state (row @ marks, server switcher badges, followed-thread marks) from the
 * row-hint record keyed by (user, server). The first network Home replaces all of it.
 *
 * Runs the real [SharedHomeDataSource] and [parseRaftHome] under [SharedHomeStore] on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeOfflineReadStateRestoreTest {

    @Test
    fun anOfflineColdStartShowsTheLastKnownMentionServerBadgesAndThreadMarks() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        homeStore(ReadStateHomeApi.online(), storage).accept(SharedHomeIntent.Start)

        val reopened = homeStore(ReadStateHomeApi.offline(), storage)
        reopened.accept(SharedHomeIntent.Start)

        val content = reopened.content()
        assertTrue(content.isDurableIdentityCache, "offline: Home is still the durable cache")
        val item =
            SharedHomeConversationRowProjector(restoredRowHints = content.displayRowHints())
                .project(content.snapshot.channels.single { it.channelId == ChannelId("channel-1") })
        assertEquals(3, item.unreadCount)
        assertTrue(item.mentioned, "the last-known @ mark comes back with its count")
        assertEquals(7, reopened.state.value.serverUnreadCounts["server-2"], "the other server's switcher badge")
        assertEquals(
            HomeFollowedThreadUnread(ChannelId("channel-1"), unreadCount = 2, hasMention = true),
            content.snapshot.followedThreadUnread["thread-1"],
            "the followed-thread mark under channel-1"
        )
        assertFalse(content.snapshot.channels.single { it.channelId == ChannelId("channel-1") }.unreadCountKnown, "a hint is never a known count")
    }

    @Test
    fun theFirstNetworkHomeReplacesEveryRestoredReadStateValue() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        homeStore(ReadStateHomeApi.online(), storage).accept(SharedHomeIntent.Start)

        val reopened = homeStore(ReadStateHomeApi.online(channelUnread = 0, otherServerUnread = 0, threadUnread = 0), storage)
        reopened.accept(SharedHomeIntent.Start)

        val content = reopened.content()
        assertFalse(content.isDurableIdentityCache)
        val item =
            SharedHomeConversationRowProjector(restoredRowHints = content.displayRowHints())
                .project(content.snapshot.channels.single { it.channelId == ChannelId("channel-1") })
        assertEquals(0, item.unreadCount)
        assertFalse(item.mentioned)
        assertEquals(0, reopened.state.value.serverUnreadCounts["server-2"] ?: 0)
        assertEquals(emptyMap(), content.snapshot.followedThreadUnread)
    }

    @Test
    fun anotherUserOrALostServerGetsNothingRestored() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        homeStore(ReadStateHomeApi.online(), storage).accept(SharedHomeIntent.Start)

        val otherUser = homeStore(ReadStateHomeApi.offline(), storage, session(userId = "user-2"))
        otherUser.accept(SharedHomeIntent.Start)
        assertTrue(otherUser.state.value.serverUnreadCounts.isEmpty(), "user-2 never sees user-1's badges")
        val otherContent = otherUser.state.value.content as? SharedHomeContentState.Content
        assertTrue(otherContent == null || otherContent.displayRowHints().isEmpty())

        val lostServer = homeStore(ReadStateHomeApi.offline(), storage, session(servers = listOf("server-1")))
        lostServer.accept(SharedHomeIntent.Start)
        assertEquals(null, lostServer.state.value.serverUnreadCounts["server-2"], "no badge for a server the session left")
        assertEquals(3, lostServer.content().displayRowHints()["channel-1"]?.unreadCount, "server-1's own hints still restore")
    }

    @Test
    fun aRealtimeReadFactPersistsTheMarkItLoweredWithItsVersion() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        val first = homeStore(ReadStateHomeApi.online(), storage, reloadsHang = true)
        first.accept(SharedHomeIntent.Start)
        assertEquals(2, first.content().snapshot.followedThreadUnread["thread-1"]?.unreadCount)

        // Read on another device; the reconcile it schedules never answers (the app dies first).
        first.accept(
            SharedHomeIntent.ApplyRealtimeEvent(
                "server-1",
                PerReceiverReadStateChanged(
                    ServerId("server-1"),
                    listOf(PerReceiverReadStateScope(ChannelId("thread-1"), maxReadSeq = 20, readStateVersion = 9, unreadCount = 0))
                )
            )
        )
        runCurrent()
        assertEquals(null, first.content().snapshot.followedThreadUnread["thread-1"], "the live mark is lowered")

        val reopened = homeStore(ReadStateHomeApi.offline(), storage)
        reopened.accept(SharedHomeIntent.Start)

        assertEquals(
            9L,
            build.raft.app.data.realtime.PerReceiverStateVersionStore(storage)
                .readReadStateCheckpoint(ServerId("server-1"), build.raft.app.model.MemberId("user-1"), ChannelId("thread-1"))
                .version,
            "the version is on disk"
        )
        assertEquals(
            null,
            reopened.content().snapshot.followedThreadUnread["thread-1"],
            "and so is the value it brought: the thread mark does not come back under the newer version"
        )
    }

    private fun TestScope.homeStore(
        api: ReadStateHomeApi,
        storage: InMemoryRaftKeyValueStore,
        session: StoredAuthSession = session(),
        reloadsHang: Boolean = false,
    ): SharedHomeStore {
        if (reloadsHang) api.hangAfterFirstLoad = true
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = api,
                    parseHome = { payload, s -> parseRaftHome(payload, s) },
                    parseDispatcher = UnconfinedTestDispatcher(testScheduler),
                ),
            scope = backgroundScope,
            perReceiverStateVersionStore = build.raft.app.data.realtime.PerReceiverStateVersionStore(storage),
            persistSlices =
                listOf(
                    createSharedHomeIdentityPersistSlice(SharedPreferencePersistStorage(storage)),
                    createSharedHomeRowHintPersistSlice(HomeRowHintPersistStorage(storage)),
                ),
        )
    }

    private fun SharedHomeStore.content(): SharedHomeContentState.Content =
        state.value.content as? SharedHomeContentState.Content ?: error("Home has no content: ${state.value.content}")

    private fun session(
        userId: String = "user-1",
        servers: List<String> = listOf("server-1", "server-2"),
    ): StoredAuthSession = sampleSession(userId).copy(servers = servers.map { StoredAuthServer(id = it, name = it) })

    /** Two joined channels; channel-1 has unread with a mention and a followed thread with unread replies. */
    private class ReadStateHomeApi(
        private val channelUnread: Int,
        private val otherServerUnread: Int,
        private val threadUnread: Int,
        private val offline: Boolean,
    ) : RaftHomeApi {
        var hangAfterFirstLoad = false
        private var loads = 0

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse {
            if (hangAfterFirstLoad && loads++ > 0) awaitCancellation()
            return answer(
                """[{"id":"channel-1","name":"general","type":"channel","joined":true},""" +
                    """{"id":"channel-2","name":"random","type":"channel","joined":true}]"""
            )
        }

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = answer("[]")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            answer(
                """{"channels":{""" +
                    """"channel-1":{"unreadCount":$channelUnread,"hasMention":${channelUnread > 0},"hasAnyMention":${channelUnread > 0},"readState":{"kind":"absent"}},""" +
                    """"thread-1":{"unreadCount":$threadUnread,"hasMention":${threadUnread > 0},"hasAnyMention":${threadUnread > 0},"readState":{"kind":"absent"},"parentChannelId":"channel-1"}""" +
                    """},"threadUnread":{}}"""
            )

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            answer(
                """[{"serverId":"server-1","unreadCount":$channelUnread},""" +
                    """{"serverId":"server-2","unreadCount":$otherServerUnread}]"""
            )

        private fun answer(body: String): HomeNetworkResponse =
            if (offline) HomeNetworkResponse(code = 0, bodyText = null) else HomeNetworkResponse(200, body)

        companion object {
            fun online(channelUnread: Int = 3, otherServerUnread: Int = 7, threadUnread: Int = 2) =
                ReadStateHomeApi(channelUnread, otherServerUnread, threadUnread, offline = false)

            fun offline() = ReadStateHomeApi(0, 0, 0, offline = true)
        }
    }
}
