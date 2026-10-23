package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ErrorPresentationKey
import build.raft.app.data.JOINT_CHANNEL_FREE_LIMIT_REACHED
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_INVITEE_NOT_FOUND
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JOINT_TARGET_SERVER_INVALID
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.errorPresentation
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointChannelConnectionsStoreTest {
    @Test
    fun loadPublishesConnectionsAndServerLevelPermission() = runTest {
        val dataSource = FakeJointConnectionsDataSource()
        val store = loadedStore(dataSource)

        val state = store.state.value
        assertEquals(JointChannelConnectionsLoadStatus.Content, state.status)
        assertEquals(listOf("srv-host", "srv-target"), state.connections?.servers?.map { it.serverId })
        assertTrue(state.canManageConnections)
        assertTrue(state.canResend)
        assertEquals(1, dataSource.loadCalls)
        store.clear()
    }

    @Test
    fun membersAndArchivedChannelsSeeTheListButNeverReachMutations() = runTest {
        listOf(
            FakeJointConnectionsDataSource(canFederate = false),
            FakeJointConnectionsDataSource(connections = jointTestConnections(archived = true)),
        ).forEach { dataSource ->
            val store = loadedStore(dataSource)

            assertFalse(store.state.value.canManageConnections)
            assertFalse(store.state.value.canResend)
            store.dispatch(JointChannelConnectionsIntent.Invite("target-co", "@ada"))
            store.dispatch(JointChannelConnectionsIntent.Resend)
            runCurrent()
            assertEquals(0, dataSource.inviteCalls)
            assertEquals(0, dataSource.resendCalls)
            assertNull(store.state.value.actionInFlight)
            store.clear()
        }
    }

    @Test
    fun inviteValidatesLocallyBeforeAnyRequest() = runTest {
        data class Case(val slug: String, val people: String, val expected: Set<JointInviteValidation>, val servers: Int = 2)
        listOf(
            Case("", "", setOf(JointInviteValidation.SlugRequired, JointInviteValidation.PeopleRequired)),
            Case("Partner Co", "@ada", setOf(JointInviteValidation.SlugPattern)),
            Case("partner-co", " , \n", setOf(JointInviteValidation.PeopleRequired)),
            Case("partner-co", "@ada", setOf(JointInviteValidation.ServerLimitReached), servers = JOINT_CHANNEL_MAX_SERVERS),
        ).forEach { case ->
            val dataSource = FakeJointConnectionsDataSource(connections = jointTestConnections(serverCount = case.servers))
            val store = loadedStore(dataSource)

            store.dispatch(JointChannelConnectionsIntent.Invite(case.slug, case.people))
            runCurrent()

            assertEquals(case.expected, store.state.value.inviteValidation, "case=$case")
            assertEquals(0, dataSource.inviteCalls)
            store.dispatch(JointChannelConnectionsIntent.ClearInviteFeedback)
            assertTrue(store.state.value.inviteValidation.isEmpty())
            store.clear()
        }
    }

    @Test
    fun inviteSuccessPatchesServerListAndEmitsSentReceipt() = runTest {
        val gate = CompletableDeferred<DataResult<JointChannelConnections>>()
        val dataSource = FakeJointConnectionsDataSource(inviteGate = gate)
        val store = loadedStore(dataSource)

        store.dispatch(JointChannelConnectionsIntent.Invite(" partner-co ", "@ada, bo@example.com"))
        runCurrent()
        assertEquals(JointChannelConnectionsAction.Invite, store.state.value.actionInFlight)
        assertEquals(listOf("partner-co" to listOf("@ada", "bo@example.com")), dataSource.invites)
        // Single flight: a second submit and a resend while inviting never reach the data source.
        store.dispatch(JointChannelConnectionsIntent.Invite("partner-co", "@ada"))
        store.dispatch(JointChannelConnectionsIntent.Resend)
        runCurrent()
        assertEquals(1, dataSource.inviteCalls)
        assertEquals(0, dataSource.resendCalls)

        gate.complete(DataResult.Content(jointTestConnections(serverCount = 3)))
        runCurrent()

        val state = store.state.value
        assertNull(state.actionInFlight)
        assertEquals(3, state.connections?.servers?.size)
        assertEquals(JointChannelConnectionsEffect.InviteSent(emailedCount = 2), state.effect)
        store.dispatch(JointChannelConnectionsIntent.ConsumeEffect)
        assertNull(store.state.value.effect)
        store.clear()
    }

    @Test
    fun inviteFailureKeepsListAndRoutesServerCodeToItsField() = runTest {
        data class Case(val result: DataResult<JointChannelConnections>, val code: String, val field: JointInviteField)
        listOf(
            Case(DataResult.NoPermission(PermissionBlocker(code = JOINT_FREE_SERVER_LIMIT, serverMessage = "free cap")), JOINT_FREE_SERVER_LIMIT, JointInviteField.Form),
            Case(DataResult.NoPermission(PermissionBlocker(code = JOINT_SERVER_LIMIT)), JOINT_SERVER_LIMIT, JointInviteField.Form),
            Case(DataResult.Error(ScreenError(code = JOINT_TARGET_SERVER_INVALID, retryable = false, serverMessage = "Target server not found")), JOINT_TARGET_SERVER_INVALID, JointInviteField.Slug),
            Case(DataResult.Error(ScreenError(code = JOINT_INVITEE_NOT_FOUND, retryable = false)), JOINT_INVITEE_NOT_FOUND, JointInviteField.People),
            Case(DataResult.NoPermission(PermissionBlocker(code = "joint_forbidden")), "joint_forbidden", JointInviteField.Form),
        ).forEach { case ->
            val dataSource = FakeJointConnectionsDataSource(inviteResult = case.result)
            val store = loadedStore(dataSource)
            val before = store.state.value.connections

            store.dispatch(JointChannelConnectionsIntent.Invite("partner-co", "@ada"))
            runCurrent()

            val state = store.state.value
            assertNull(state.actionInFlight)
            assertEquals(before, state.connections)
            assertEquals(case.code, state.inviteFailure?.code)
            assertEquals(case.field, jointInviteFailureField(case.code))
            assertNull(state.effect)
            store.clear()
        }
    }

    @Test
    fun resendSuccessAndConflictPaths() = runTest {
        val ok = FakeJointConnectionsDataSource(resendResult = DataResult.Content(2))
        val okStore = loadedStore(ok)
        okStore.dispatch(JointChannelConnectionsIntent.Resend)
        runCurrent()
        assertEquals(1, ok.resendCalls)
        assertEquals(JointChannelConnectionsEffect.Resent(2), okStore.state.value.effect)
        assertNull(okStore.state.value.resendFailure)
        okStore.clear()

        val conflict =
            FakeJointConnectionsDataSource(
                resendResult = DataResult.Error(ScreenError(code = "joint_http_409", retryable = false, serverMessage = "No pending joint channel invite found"))
            )
        val conflictStore = loadedStore(conflict)
        conflictStore.dispatch(JointChannelConnectionsIntent.Resend)
        runCurrent()
        val failure = assertIs<JointChannelFailure.Error>(conflictStore.state.value.resendFailure)
        assertEquals("No pending joint channel invite found", failure.error.serverMessage)
        assertNull(conflictStore.state.value.effect)
        assertEquals(2, conflictStore.state.value.connections?.servers?.size)
        conflictStore.clear()

        val nothingPending = FakeJointConnectionsDataSource(connections = jointTestConnections(pendingFromCurrent = false))
        val nothingPendingStore = loadedStore(nothingPending)
        assertFalse(nothingPendingStore.state.value.canResend)
        nothingPendingStore.dispatch(JointChannelConnectionsIntent.Resend)
        runCurrent()
        assertEquals(0, nothingPending.resendCalls)
        nothingPendingStore.clear()
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
    fun pullRefreshReloadsInPlaceSingleFlight() = runTest {
        val dataSource = FakeJointConnectionsDataSource()
        val store = loadedStore(dataSource)
        val gate = CompletableDeferred<Unit>()
        dataSource.loadGate = gate
        dataSource.loadResult = DataResult.Content(JointChannelConnectionsSnapshot(jointTestConnections(serverCount = 3), canFederate = true))

        store.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        store.dispatch(JointChannelConnectionsIntent.Refresh)
        runCurrent()
        assertTrue(store.state.value.refreshing)
        assertEquals(2, store.state.value.connections?.servers?.size, "the loaded list stays while it reloads")
        assertEquals(2, dataSource.loadCalls, "a refresh in flight is not asked again")

        gate.complete(Unit)
        runCurrent()
        assertFalse(store.state.value.refreshing)
        assertEquals(3, store.state.value.connections?.servers?.size)
        store.clear()
    }

    @Test
    fun failureCopyFollowsWebPrecedence() {
        val copy = JointFailureCopy(freeServerLimit = "free", serverLimit = "max", hostFreeLimit = "host", fallback = "fallback", inviteRateLimited = { "wait:$it" })
        val pipeline: (JointChannelFailure) -> String = { "pipeline:${it.code}" }

        assertEquals("free", jointChannelFailureText(JointChannelFailure.Blocked(PermissionBlocker(JOINT_FREE_SERVER_LIMIT, serverMessage = "raw")), copy, pipeline))
        assertEquals("max", jointChannelFailureText(JointChannelFailure.Blocked(PermissionBlocker(JOINT_SERVER_LIMIT)), copy, pipeline))
        assertEquals("host", jointChannelFailureText(JointChannelFailure.Blocked(PermissionBlocker(JOINT_CHANNEL_FREE_LIMIT_REACHED)), copy, pipeline))
        assertEquals(
            "Invited person not found in target server: @ghost",
            jointChannelFailureText(
                JointChannelFailure.Error(ScreenError(JOINT_INVITEE_NOT_FOUND, serverMessage = "Invited person not found in target server: @ghost")),
                copy,
                pipeline,
            ),
        )
        assertEquals("fallback", jointChannelFailureText(JointChannelFailure.Error(ScreenError(JOINT_INVITEE_NOT_FOUND)), copy, pipeline))
        assertEquals("pipeline:joint_http_409", jointChannelFailureText(JointChannelFailure.Error(ScreenError("joint_http_409")), copy, pipeline))
        assertEquals("fallback", jointChannelFailureText(JointChannelFailure.Error(ScreenError("x")), copy) { "" })
    }

    @Test
    fun unmappedJointCodesLetTheServerReasonWinInTheErrorPipeline() {
        // A 409 keeps `joint_http_409`, which the pipeline does not localize: the server sentence wins.
        val presentation = ScreenError("joint_http_409", serverMessage = "No pending joint channel invite found").errorPresentation()
        assertEquals(ErrorPresentationKey.Generic, presentation.key)
        assertEquals("No pending joint channel invite found", presentation.rawServerMessage)
    }

    private fun TestScope.loadedStore(dataSource: FakeJointConnectionsDataSource): JointChannelConnectionsStore {
        val store = JointChannelConnectionsStore(dataSource, this)
        store.dispatch(JointChannelConnectionsIntent.Load)
        runCurrent()
        return store
    }
}

internal fun jointTestConnections(
    serverCount: Int = 2,
    archived: Boolean = false,
    pendingFromCurrent: Boolean = true,
): JointChannelConnections =
    JointChannelConnections(
        channelId = ChannelId("ch-host"),
        channelName = "partner-room",
        isJoint = true,
        archived = archived,
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
        hasCurrentServerPendingInvites = pendingFromCurrent,
    )

private class FakeJointConnectionsDataSource(
    connections: JointChannelConnections = jointTestConnections(),
    canFederate: Boolean = true,
    var loadResult: DataResult<JointChannelConnectionsSnapshot> =
        DataResult.Content(JointChannelConnectionsSnapshot(connections, canFederate)),
    private val inviteResult: DataResult<JointChannelConnections>? = null,
    private val inviteGate: CompletableDeferred<DataResult<JointChannelConnections>>? = null,
    private val resendResult: DataResult<Int> = DataResult.Content(1),
) : JointChannelConnectionsDataSource {
    /** When set, each load waits for it (pull-to-refresh in flight). */
    var loadGate: CompletableDeferred<Unit>? = null
    var loadCalls = 0
    var inviteCalls = 0
    var resendCalls = 0
    val invites = mutableListOf<Pair<String, List<String>>>()

    override suspend fun load(): DataResult<JointChannelConnectionsSnapshot> {
        loadCalls += 1
        loadGate?.await()
        return loadResult
    }

    override suspend fun invite(targetServerSlug: String, invitedPeople: List<String>): DataResult<JointChannelConnections> {
        inviteCalls += 1
        invites += targetServerSlug to invitedPeople
        return inviteGate?.await() ?: inviteResult ?: DataResult.Content(jointTestConnections(serverCount = 3))
    }

    override suspend fun resend(): DataResult<Int> {
        resendCalls += 1
        return resendResult
    }
}
