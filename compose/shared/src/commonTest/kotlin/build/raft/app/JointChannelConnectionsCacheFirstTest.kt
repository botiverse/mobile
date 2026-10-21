package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DataResult
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.MapRowStorage
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftDurableRowStorage
import build.raft.app.data.RaftKeyedDurableSnapshotStore
import build.raft.app.data.RaftKeyedSnapshotCache
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Data-layer plan step 5 (joint connections): the Connected servers page paints the cached list
 * for this account + server + channel on its first frame (memory, or disk on a cold start),
 * refreshes once in the background, keeps late answers under their key, evicts on lost access
 * and caps entries per server (Tenny rules 1-3).
 */
class JointChannelConnectionsCacheFirstTest {
    @Test
    fun reopeningAfterServerSwitchPaintsCachedListOnFirstFrameAndRefreshesOnce() = runTest {
        val cache = cache(this)
        val dataSource = ScriptedJointDataSource()
        val first = store(this, cache, dataSource, SERVER_A)
        first.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        first.clear()
        // Server B's page in between: its own key.
        store(this, cache, ScriptedJointDataSource(), SERVER_B).apply {
            dispatch(JointChannelConnectionsIntent.Refresh)
            runCurrent()
            clear()
        }

        // Back on A: the very first state is the cached list, before any request.
        val gate = CompletableDeferred<Unit>()
        dataSource.gate = gate
        dataSource.connections = jointTestConnections(serverCount = 3)
        val reopened = store(this, cache, dataSource, SERVER_A)
        assertEquals(JointChannelConnectionsLoadStatus.Content, reopened.state.value.status)
        assertEquals(2, reopened.state.value.connections?.servers?.size)

        reopened.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        assertEquals(2, reopened.state.value.connections?.servers?.size, "no blank while refreshing")
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, reopened.state.value.connections?.servers?.size)
        assertEquals(2, dataSource.loadCalls, "exactly one background refresh on reopen")
        reopened.clear()
    }

    @Test
    fun coldStartPaintsTheDurableRowBeforeTheNetworkAnswers() = runTest {
        val storage = MapRowStorage()
        store(this, cache(this, storage), ScriptedJointDataSource(), SERVER_A).apply {
            dispatch(JointChannelConnectionsIntent.Refresh)
            runCurrent()
            clear()
        }

        val coldCache = cache(this, storage)
        val dataSource = ScriptedJointDataSource().apply { gate = CompletableDeferred() }
        val cold = store(this, coldCache, dataSource, SERVER_A)
        cold.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        assertEquals(JointChannelConnectionsLoadStatus.Content, cold.state.value.status)
        assertEquals("partner-room", cold.state.value.connections?.channelName)
        assertEquals(true, cold.state.value.canFederate)
        assertEquals(JointChannelServerPlanCheck, cold.state.value.connections?.servers?.map { it.plan?.name })
        dataSource.gate?.complete(Unit)
        runCurrent()
        assertEquals(1, dataSource.loadCalls)

        // Another account's slot never sees it.
        assertNull(cache(this, storage).read(slot(cache(this, storage), SERVER_A, account = "user-2").key))
        cold.clear()
    }

    @Test
    fun lateAnswerAfterThePageLeftIsStillCachedButNotAfterSignOut() = runTest {
        val cache = cache(this)
        val dataSource = ScriptedJointDataSource().apply { gate = CompletableDeferred() }
        val page = store(this, cache, dataSource, SERVER_A)
        page.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        // The user switched server: the page reloads nothing, but A's answer still lands on A.
        page.dispatch(JointChannelConnectionsIntent.Refresh) // ignored while in flight
        dataSource.gate?.complete(Unit)
        runCurrent()
        assertNotNull(cache.peek(slot(cache, SERVER_A).key))

        val signedOut = cache(this)
        val pending = ScriptedJointDataSource().apply { gate = CompletableDeferred() }
        store(this, signedOut, pending, SERVER_A).dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        signedOut.clearAll()
        pending.gate?.complete(Unit)
        runCurrent()
        assertNull(signedOut.peek(slot(signedOut, SERVER_A).key), "a write across sign-out is fenced")
    }

    @Test
    fun tokenRefreshMidRequestKeepsTheListAndAsksOnceMore() = runTest {
        val cache = cache(this)
        val dataSource = ScriptedJointDataSource()
        val page = store(this, cache, dataSource, SERVER_A)
        page.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()

        dataSource.script += DataResult.Error(ScreenError(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, retryable = false))
        dataSource.connections = jointTestConnections(serverCount = 3)
        page.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        assertEquals(JointChannelConnectionsLoadStatus.Content, page.state.value.status)
        assertEquals(3, page.state.value.connections?.servers?.size)
        assertNull(page.state.value.loadFailure)
        assertEquals(3, dataSource.loadCalls)
        page.clear()
    }

    @Test
    fun lostAccessEvictsAndShowsTheTerminalState() = runTest {
        val cache = cache(this)
        val dataSource = ScriptedJointDataSource()
        val page = store(this, cache, dataSource, SERVER_A)
        page.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()

        dataSource.script += DataResult.NoPermission(PermissionBlocker("joint_forbidden"))
        page.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        assertEquals(JointChannelConnectionsLoadStatus.Error, page.state.value.status)
        assertNull(page.state.value.connections)
        assertNull(cache.read(slot(cache, SERVER_A).key))

        // 404 (channel deleted) too; a transient failure keeps the list.
        val other = store(this, cache, ScriptedJointDataSource(), SERVER_B)
        other.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        val flaky = ScriptedJointDataSource().apply { script += DataResult.Error(ScreenError("server_unavailable")) }
        store(this, cache, flaky, SERVER_B).apply {
            dispatch(JointChannelConnectionsIntent.Refresh)
            runCurrent()
            assertEquals(2, state.value.connections?.servers?.size)
            clear()
        }
        val gone = ScriptedJointDataSource().apply { script += DataResult.Error(ScreenError("joint_http_404")) }
        store(this, cache, gone, SERVER_B).apply {
            dispatch(JointChannelConnectionsIntent.Refresh)
            runCurrent()
            assertNull(state.value.connections)
            clear()
        }
        assertNull(cache.read(slot(cache, SERVER_B).key))
        page.clear()
        other.clear()
    }

    @Test
    fun removedFromServerDropsItsEntriesAndEntriesAreCappedPerServer() = runTest {
        val storage = MapRowStorage()
        val cache = cache(this, storage, maxPerServer = 2)
        listOf("c1", "c2", "c3").forEach { channel ->
            store(this, cache, ScriptedJointDataSource(channelId = channel), SERVER_A, channel).apply {
                dispatch(JointChannelConnectionsIntent.Refresh)
                runCurrent()
                clear()
            }
        }
        assertNull(cache.peek(slot(cache, SERVER_A, channel = "c1").key), "least recently used left")
        assertNotNull(cache.peek(slot(cache, SERVER_A, channel = "c3").key))
        assertEquals(2, cache.sizeForTest())

        cache.dropServer(SERVER_A, "user-1")
        assertNull(cache.read(slot(cache, SERVER_A, channel = "c3").key))
        assertEquals(0, cache.sizeForTest())
    }

    private fun cache(
        scope: TestScope,
        storage: RaftDurableRowStorage = MapRowStorage(),
        maxPerServer: Int = 32,
    ): RaftJointConnectionsCache =
        RaftKeyedSnapshotCache(
            durable =
                RaftKeyedDurableSnapshotStore(
                    storage = storage,
                    serializer = RaftJointConnectionsDurableRecord.serializer(),
                    label = "joint_test",
                    maxRowsPerServer = maxPerServer,
                    scope = scope.backgroundScope,
                ),
            toDurable = { it.toJointConnectionsDurableRecord() },
            fromDurable = { it.toJointConnectionsSnapshot() },
            maxPerServer = maxPerServer,
        )

    private fun slot(
        cache: RaftJointConnectionsCache,
        serverId: String,
        channel: String = "ch-host",
        account: String = "user-1",
    ): JointChannelConnectionsCacheSlot =
        assertNotNull(
            jointChannelConnectionsCacheSlot(
                cache,
                AuthenticatedRouteOwner(ENVIRONMENT, account, serverId),
                ChannelSettingsRoute(ServerId(serverId), ChannelId(channel)),
            )
        )

    private fun store(
        scope: TestScope,
        cache: RaftJointConnectionsCache,
        dataSource: ScriptedJointDataSource,
        serverId: String,
        channel: String = "ch-host",
    ): JointChannelConnectionsStore =
        JointChannelConnectionsStore(dataSource, scope, slot(cache, serverId, channel))

    private class ScriptedJointDataSource(private val channelId: String = "ch-host") : JointChannelConnectionsDataSource {
        var connections: JointChannelConnections = jointTestConnections()
        var gate: CompletableDeferred<Unit>? = null
        val script = ArrayDeque<DataResult<JointChannelConnectionsSnapshot>>()
        var loadCalls = 0

        override suspend fun load(): DataResult<JointChannelConnectionsSnapshot> {
            loadCalls += 1
            gate?.await()
            script.removeFirstOrNull()?.let { return it }
            return DataResult.Content(
                JointChannelConnectionsSnapshot(connections.copy(channelId = ChannelId(channelId)), canFederate = true)
            )
        }

        override suspend fun invite(targetServerSlug: String, invitedPeople: List<String>): DataResult<JointChannelConnections> =
            error("unused")

        override suspend fun resend(): DataResult<Int> = error("unused")
    }

    private companion object {
        const val ENVIRONMENT = "https://api.example"
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
        val JointChannelServerPlanCheck = listOf("Paid", "Free")
    }
}
