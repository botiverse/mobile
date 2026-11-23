package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.JOINT_CHANNEL_PLAN_LIMIT_REACHED
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JointChannelConnectionsStoreTest {
    @Test
    fun loadPublishesConnectionsAndServerLevelPermission() = runTest {
        val dataSource = FakeJointConnectionsDataSource()
        val store = loadedStore(dataSource)

        val state = store.state.value
        assertEquals(JointChannelConnectionsLoadStatus.Content, state.status)
        assertEquals(listOf("srv-host", "srv-target"), state.connections?.servers?.map { it.serverId })
        assertTrue(state.canFederate)
        assertEquals(1, dataSource.loadCalls)
        store.clear()
    }

    @Test
    fun failedRefreshKeepsLoadedListAndColdFailureShowsError() = runTest {
        val dataSource = FakeJointConnectionsDataSource()
        val store = loadedStore(dataSource)
        dataSource.loadResult = DataResult.Error(ScreenError(code = "network_unavailable"))

        store.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        assertEquals(JointChannelConnectionsLoadStatus.Content, store.state.value.status)
        assertEquals(2, store.state.value.connections?.servers?.size)
        assertEquals("network_unavailable", store.state.value.loadFailure?.code)
        store.clear()

        val cold = JointChannelConnectionsStore(FakeJointConnectionsDataSource(loadResult = DataResult.NoPermission(PermissionBlocker("channel_forbidden"))), this)
        cold.dispatch(JointChannelConnectionsIntent.Load)
        runCurrent()
        assertEquals(JointChannelConnectionsLoadStatus.Error, cold.state.value.status)
        assertEquals("channel_forbidden", cold.state.value.loadFailure?.code)
        cold.clear()
    }

    @Test
    fun failureCopyFollowsWebPrecedence() {
        val copy = JointFailureCopy(freeServerLimit = "free", serverLimit = "max", hostLimit = "host", fallback = "fallback")
        val pipeline: (JointChannelFailure) -> String = { "pipeline:${it.code}" }

        assertEquals("free", jointChannelFailureText(JointChannelFailure.Blocked(PermissionBlocker(JOINT_FREE_SERVER_LIMIT, serverMessage = "raw")), copy, pipeline))
        assertEquals("max", jointChannelFailureText(JointChannelFailure.Blocked(PermissionBlocker(JOINT_SERVER_LIMIT)), copy, pipeline))
        // Web jointInviteAcceptErrorMessage: the host's plan limit (botiverse/slock #15952) is its own sentence.
        assertEquals("host", jointChannelFailureText(JointChannelFailure.Blocked(PermissionBlocker(JOINT_CHANNEL_PLAN_LIMIT_REACHED)), copy, pipeline))
        assertEquals("pipeline:joint_http_409", jointChannelFailureText(JointChannelFailure.Error(ScreenError("joint_http_409")), copy, pipeline))
        assertEquals("fallback", jointChannelFailureText(JointChannelFailure.Error(ScreenError("x")), copy) { "" })
    }

    private fun TestScope.loadedStore(dataSource: FakeJointConnectionsDataSource): JointChannelConnectionsStore {
        val store = JointChannelConnectionsStore(dataSource, this)
        store.dispatch(JointChannelConnectionsIntent.Load)
        runCurrent()
        return store
    }
}

internal fun jointTestConnections(serverCount: Int = 2): JointChannelConnections =
    JointChannelConnections(
        channelId = ChannelId("ch-host"),
        channelName = "partner-room",
        isJoint = true,
        archived = false,
        servers =
            (0 until serverCount).map { index ->
                JointChannelServer(
                    serverId = if (index == 0) "srv-host" else if (index == 1) "srv-target" else "srv-$index",
                    name = "Server $index",
                    slug = "server-$index",
                    active = index == 0,
                    isCurrentServer = index == 0,
                    plan = if (index == 0) JointChannelServerPlan.Paid else JointChannelServerPlan.Free,
                )
            },
        hasCurrentServerPendingInvites = true,
    )

internal class FakeJointConnectionsDataSource(
    connections: JointChannelConnections = jointTestConnections(),
    canFederate: Boolean = true,
    var loadResult: DataResult<JointChannelConnectionsSnapshot> =
        DataResult.Content(JointChannelConnectionsSnapshot(connections, canFederate)),
    var disconnectResult: DataResult<Unit> = DataResult.Content(Unit),
) : JointChannelConnectionsDataSource {
    /** When set, each per-row disconnect waits for it. */
    var disconnectGate: CompletableDeferred<Unit>? = null
    val disconnectedServerIds = mutableListOf<String>()
    /** When set, each load waits for it (pull-to-refresh in flight). */
    var loadGate: CompletableDeferred<Unit>? = null
    var loadCalls = 0

    override suspend fun load(): DataResult<JointChannelConnectionsSnapshot> {
        loadCalls += 1
        loadGate?.await()
        return loadResult
    }

    override suspend fun disconnectServer(serverId: String): DataResult<Unit> {
        disconnectedServerIds += serverId
        disconnectGate?.await()
        return disconnectResult
    }

    /** Connect your servers: the answer each call gets, and the server lists sent. */
    var memberConnectResult: DataResult<build.raft.app.data.MemberConnectResponse> =
        DataResult.Content(build.raft.app.data.MemberConnectResponse.Answered(emptyList(), traceId = null))
    var memberConnectGate: CompletableDeferred<Unit>? = null
    val memberConnectCalls = mutableListOf<List<String>>()

    override suspend fun connectMyServers(serverIds: List<String>): DataResult<build.raft.app.data.MemberConnectResponse> {
        memberConnectCalls += serverIds
        memberConnectGate?.await()
        return memberConnectResult
    }

    var memberConnectLimits: Map<String, build.raft.app.data.MemberConnectServerLimit> = emptyMap()
    var memberConnectLimitsGate: CompletableDeferred<Unit>? = null
    var memberConnectLimitsCalls = 0

    override suspend fun listMemberConnectLimits(): Map<String, build.raft.app.data.MemberConnectServerLimit> {
        memberConnectLimitsCalls += 1
        memberConnectLimitsGate?.await()
        return memberConnectLimits
    }
}
