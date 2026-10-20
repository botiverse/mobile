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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Home load whose unread response was read before a realtime message (device: #all 3 → 4 locally, then a
 * summary taken before the message put it back to 3). The unread endpoints carry no message seq, so such a
 * load keeps the local counts and a second load, sent after the message, decides.
 */
class SharedHomeRealtimeUnreadLoadRaceTest {
    @Test
    fun aLoadInFlightWhenAMessageArrivesKeepsTheLocalCountAndASecondLoadDecides() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Server answers: 3 at start, 3 for the load that raced the message, then 0 (read on another device).
        val api = GatedHomeApi(serverUnread = mutableListOf(3, 3, 0))
        val store = homeStore(api, scope)
        store.accept(SharedHomeIntent.Start)
        assertEquals(3, store.unread())

        val gate = api.blockNextLoad()
        val refresh = async { store.accept(SharedHomeIntent.Refresh) }
        withTimeout(1_000) { gate.started.await() }
        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", message("m-a6", 6)))
        assertEquals(4, store.unread())

        gate.release.complete(Unit)
        refresh.await()
        withTimeout(2_000) { while (api.loads < 3) delay(10) }
        withTimeout(2_000) { while (store.unread() != 0) delay(10) }

        assertEquals(3, api.loads, "the raced load is followed by exactly one reconcile load")
        assertEquals(0, store.unread(), "the reconcile load's count applies, including a drop from another device")
        scope.cancel()
    }

    @Test
    fun theRacedResponseNeverLowersTheLocalCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = GatedHomeApi(serverUnread = mutableListOf(3, 3, 4))
        val store = homeStore(api, scope)
        store.accept(SharedHomeIntent.Start)

        val gate = api.blockNextLoad()
        val reconcileGate = api.blockLoad(number = 3)
        val refresh = async { store.accept(SharedHomeIntent.Refresh) }
        withTimeout(1_000) { gate.started.await() }
        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", message("m-a6", 6)))
        gate.release.complete(Unit)
        refresh.await()

        // The raced response (3) has landed and the reconcile load is in flight: the badge still shows 4.
        withTimeout(1_000) { reconcileGate.started.await() }
        assertEquals(4, store.unread())
        reconcileGate.release.complete(Unit)
        withTimeout(2_000) { while (api.loads < 3) delay(10) }
        assertEquals(4, store.unread())
        scope.cancel()
    }

    @Test
    fun aLoadWithoutARealtimeMessageReplacesTheCountOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = GatedHomeApi(serverUnread = mutableListOf(3, 1))
        val store = homeStore(api, scope)
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.Refresh)
        withTimeout(2_000) { while (store.unread() != 1) delay(10) }
        delay(50)

        assertEquals(2, api.loads)
        assertEquals(1, store.unread())
        scope.cancel()
    }

    @Test
    fun messagesDuringEveryLoadStopAfterTheReconcileCapAndKeepTheLocalCount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = GatedHomeApi(serverUnread = MutableList(20) { 0 })
        lateinit var store: SharedHomeStore
        var seq = 10L
        api.duringLoad = { number ->
            if (number >= 2) store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", message("m-$seq", seq++)))
        }
        store = homeStore(api, scope)
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.Refresh)
        withTimeout(3_000) { while (api.loads < 5) delay(10) }
        delay(100)

        // Start + raced Refresh + at most 3 reconcile loads, every one raced by a new message.
        assertEquals(5, api.loads)
        assertEquals(4, store.unread(), "each raced load kept the messages counted locally")
        scope.cancel()
    }

    @Test
    fun aRacedLoadThatOutlivesASessionResetSchedulesNothing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = GatedHomeApi(serverUnread = mutableListOf(3, 3, 0))
        val store = homeStore(api, scope)
        store.accept(SharedHomeIntent.Start)

        val gate = api.blockNextLoad()
        val refresh = async { store.accept(SharedHomeIntent.Refresh) }
        withTimeout(1_000) { gate.started.await() }
        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", message("m-a6", 6)))
        store.reset()
        gate.release.complete(Unit)
        refresh.await()
        delay(100)

        assertEquals(2, api.loads, "the old session's raced load must not start a reconcile load")
        scope.cancel()
    }

    @Test
    fun aRacedLoadThatOutlivesAServerSwitchSchedulesNothingForTheNewServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = GatedHomeApi(serverUnread = mutableListOf(3, 3, 0))
        val authStore = InMemoryRaftAuthSessionStore(twoServerSession())
        val store = homeStore(api, scope, authStore)
        store.accept(SharedHomeIntent.Start)

        val gate = api.blockNextLoad()
        val refresh = async { store.accept(SharedHomeIntent.Refresh) }
        withTimeout(1_000) { gate.started.await() }
        store.accept(SharedHomeIntent.ApplyRealtimeEvent("server-1", message("m-a6", 6)))
        authStore.updateSelectedServer(serverId = "server-2", serverSlug = "other")
        gate.release.complete(Unit)
        refresh.await()
        delay(100)

        assertEquals(2, api.loads, "a message on server-1 must not start a reconcile load on server-2")
        scope.cancel()
    }

    private fun session() = StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
        accessToken = "token-a",
        refreshToken = "token-r",
        selectedServerId = "server-1",
        servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock")),
    )

    private fun twoServerSession() =
        session().copy(
            servers = listOf(
                StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"),
                StoredAuthServer(id = "server-2", name = "Other", slug = "other"),
            ),
        )

    private fun homeStore(
        api: GatedHomeApi,
        scope: CoroutineScope,
        authStore: InMemoryRaftAuthSessionStore = InMemoryRaftAuthSessionStore(session()),
    ): SharedHomeStore {
        return SharedHomeStore(
            authStore = authStore,
            dataSource = SharedHomeDataSource(
                sessionStore = authStore,
                api = api,
                parseHome = { payload, _ -> DataResult.Content(home(payload.serverId, api.takeUnread())) },
            ),
            scope = scope,
        )
    }

    private fun SharedHomeStore.unread(): Int =
        (state.value.content as SharedHomeContentState.Content).snapshot.channels.single().unreadCount

    private val me = MemberProfile(
        id = MemberId("user-1"),
        displayName = "Artin",
        handle = "artin@slock.ai",
        kind = MemberKind.Human,
        presence = Presence.Active,
    )

    private fun home(serverId: ServerId, unread: Int) = HomeSnapshot(
        server = ServerSummary(serverId, "Slock", unreadCount = unread),
        currentMember = me,
        channels = listOf(
            HomeChannelRow(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                name = "all",
                unreadCount = unread,
                unreadCountKnown = true,
            )
        ),
        directMessages = emptyList(),
        unreadTotal = unread,
    )

    private fun message(id: String, seq: Long) =
        MessageNew(
            Message(
                id = MessageId(id),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                seq = seq,
                author = me.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy@slock.ai"),
                timestampLabel = "12:01",
                body = "body-$id",
            )
        )

    private class GatedHomeApi(private val serverUnread: MutableList<Int>) : RaftHomeApi {
        var loads = 0
            private set
        var duringLoad: suspend (Int) -> Unit = {}
        private val gates = mutableMapOf<Int, CallGate>()

        fun blockNextLoad(): CallGate = blockLoad(loads + 1)

        fun blockLoad(number: Int): CallGate = CallGate().also { gates[number] = it }

        fun takeUnread(): Int = if (serverUnread.size > 1) serverUnread.removeAt(0) else serverUnread.single()

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            val number = ++loads
            duringLoad(number)
            gates.remove(number)?.let { gate ->
                gate.started.complete(Unit)
                gate.release.await()
            }
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "{}")

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
    }

    private class CallGate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }
}
