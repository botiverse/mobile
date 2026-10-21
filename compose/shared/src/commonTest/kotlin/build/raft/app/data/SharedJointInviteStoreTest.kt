package build.raft.app.data

import build.raft.app.homeJointInviteRows
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedJointInviteStoreTest {
    @Test
    fun onlyOwnersAndAdminsLoadPendingInvites() = runTest {
        val dataSource = FakeJointInviteDataSource()
        val store = SharedJointInviteStore(dataSource, this)

        store.dispatch(SharedJointInviteIntent.Load("srv-target", canBeInvited = false))
        store.dispatch(SharedJointInviteIntent.Load(null, canBeInvited = true))
        runCurrent()
        assertEquals(0, dataSource.loadCalls.size)
        assertTrue(store.state.value.invites.isEmpty())

        store.dispatch(SharedJointInviteIntent.Load("srv-target", canBeInvited = true))
        store.dispatch(SharedJointInviteIntent.Load("srv-target", canBeInvited = true))
        runCurrent()
        // Single flight per server.
        assertEquals(listOf(ServerId("srv-target")), dataSource.loadCalls)
        assertEquals(listOf("inv-1", "inv-2"), store.state.value.invites.map { it.id })
        assertEquals(listOf("inv-1", "inv-2"), store.state.value.homeJointInviteRowIds("srv-target"))
        assertEquals(emptyList(), store.state.value.homeJointInviteRowIds("srv-other"))
        store.clear()
    }

    @Test
    fun serverSwitchDropsTheOtherServersRowsAndLateResponses() = runTest {
        val gate = CompletableDeferred<DataResult<List<PendingJointInvite>>>()
        val dataSource = FakeJointInviteDataSource(loadGate = gate)
        val store = SharedJointInviteStore(dataSource, this)

        store.dispatch(SharedJointInviteIntent.Load("srv-a", canBeInvited = true))
        runCurrent()
        store.dispatch(SharedJointInviteIntent.Load("srv-b", canBeInvited = false))
        gate.complete(DataResult.Content(listOf(invite("late"))))
        runCurrent()

        assertEquals("srv-b", store.state.value.serverId)
        assertTrue(store.state.value.invites.isEmpty())
        store.clear()
    }

    @Test
    fun acceptSuccessRemovesInviteAndEmitsAcceptedChannel() = runTest {
        val acceptGate = CompletableDeferred<DataResult<JointChannelAccepted>>()
        val dataSource = FakeJointInviteDataSource(acceptGate = acceptGate)
        val store = loadedStore(dataSource)

        store.dispatch(SharedJointInviteIntent.Accept("inv-1"))
        runCurrent()
        assertEquals("inv-1", store.state.value.acceptingInviteId)
        // One accept at a time (web disables every Accept while one runs).
        store.dispatch(SharedJointInviteIntent.Accept("inv-2"))
        runCurrent()
        assertEquals(listOf("srv-target" to "inv-1"), dataSource.acceptCalls)

        acceptGate.complete(DataResult.Content(JointChannelAccepted(ChannelId("ch-target"), "partner-room")))
        runCurrent()

        val state = store.state.value
        assertNull(state.acceptingInviteId)
        assertEquals(listOf("inv-2"), state.invites.map { it.id })
        val effect = assertIs<SharedJointInviteEffect.Accepted>(state.effect)
        assertEquals(ChannelId("ch-target"), effect.channel.channelId)
        assertEquals("srv-target", effect.serverId)
        store.dispatch(SharedJointInviteIntent.ConsumeEffect)
        assertNull(store.state.value.effect)
        store.clear()
    }

    @Test
    fun acceptFailureKeepsCodeAndReloadsTheList() = runTest {
        listOf(
            DataResult.NoPermission(PermissionBlocker(code = JOINT_FREE_SERVER_LIMIT, serverMessage = "free cap")) to JOINT_FREE_SERVER_LIMIT,
            DataResult.NoPermission(PermissionBlocker(code = JOINT_CHANNEL_FREE_LIMIT_REACHED)) to JOINT_CHANNEL_FREE_LIMIT_REACHED,
            DataResult.Error(ScreenError(code = "joint_http_409", retryable = false, serverMessage = "Joint channel invite expired")) to "joint_http_409",
        ).forEach { (result, code) ->
            val dataSource = FakeJointInviteDataSource(acceptResult = result)
            val store = loadedStore(dataSource)

            store.dispatch(SharedJointInviteIntent.Accept("inv-1"))
            runCurrent()

            val effect = assertIs<SharedJointInviteEffect.AcceptFailed>(store.state.value.effect)
            assertEquals(code, effect.result.jointFailureCode())
            assertNull(store.state.value.acceptingInviteId)
            assertEquals(2, dataSource.loadCalls.size, "web refreshes the list after a failed accept")
            store.clear()
        }
    }

    @Test
    fun dismissHidesLocallyWithoutCallingTheServerAndUnknownAcceptIsIgnored() = runTest {
        val dataSource = FakeJointInviteDataSource()
        val store = loadedStore(dataSource)

        store.dispatch(SharedJointInviteIntent.Dismiss("inv-1"))
        store.dispatch(SharedJointInviteIntent.Accept("missing"))
        runCurrent()

        assertEquals(listOf("inv-2"), store.state.value.homeJointInviteRowIds("srv-target"))
        assertEquals(listOf("inv-1", "inv-2"), store.state.value.invites.map { it.id })
        assertTrue(dataSource.acceptCalls.isEmpty())
        store.clear()
    }

    @Test
    fun notificationIdsRoundTrip() {
        assertEquals("joint-invite:inv-1", jointInviteHomeNotificationId("inv-1"))
        assertEquals("inv-1", "joint-invite:inv-1".jointInviteIdFromHomeNotificationIdOrNull())
        assertNull("joint-invite:".jointInviteIdFromHomeNotificationIdOrNull())
        assertNull("machine-offline:m1".jointInviteIdFromHomeNotificationIdOrNull())
    }

    private fun TestScope.loadedStore(dataSource: FakeJointInviteDataSource): SharedJointInviteStore {
        val store = SharedJointInviteStore(dataSource, this)
        store.dispatch(SharedJointInviteIntent.Load("srv-target", canBeInvited = true))
        runCurrent()
        return store
    }

    private fun SharedJointInviteState.homeJointInviteRowIds(currentServerId: String): List<String> =
        homeJointInviteRows(currentServerId).map { it.id }
}

private fun invite(id: String): PendingJointInvite =
    PendingJointInvite(
        id = id,
        fromServerName = "Host Co",
        fromServerSlug = "host-co",
        channelName = "partner-room",
        expiresAt = "2026-10-28T08:00:00.000Z",
    )

private class FakeJointInviteDataSource(
    private val loadGate: CompletableDeferred<DataResult<List<PendingJointInvite>>>? = null,
    private val acceptGate: CompletableDeferred<DataResult<JointChannelAccepted>>? = null,
    private val acceptResult: DataResult<JointChannelAccepted> =
        DataResult.Content(JointChannelAccepted(ChannelId("ch-target"), "partner-room")),
) : JointInviteDataSource {
    val loadCalls = mutableListOf<ServerId>()
    val acceptCalls = mutableListOf<Pair<String, String>>()

    override suspend fun pendingInvites(serverId: ServerId): DataResult<List<PendingJointInvite>> {
        loadCalls += serverId
        return loadGate?.await() ?: DataResult.Content(listOf(invite("inv-1"), invite("inv-2")))
    }

    override suspend fun accept(serverId: ServerId, inviteId: String): DataResult<JointChannelAccepted> {
        acceptCalls += serverId.value to inviteId
        return acceptGate?.await() ?: acceptResult
    }
}
