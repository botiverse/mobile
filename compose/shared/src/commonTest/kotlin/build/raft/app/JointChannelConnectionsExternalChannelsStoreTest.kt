package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelRole
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Connected servers of an external channel (web EditChannelDialog, botiverse/slock staging
 * f3c3905046d1: #13705 host first / Host badge / no plan chips, #13856 per-row Disconnect; the
 * server invite form and Resend are gone since botiverse/slock #15939). Who may disconnect another
 * server is only what the server sent (`channelCapabilities.federateChannels == true`, #wg-rbac:#133,
 * regardless of `jointRole` or local `canFederate`), excluding the host row, the current server,
 * and pending rows.
 */
class JointChannelConnectionsExternalChannelsStoreTest {
    @Test
    fun leadsWithTheHostAndBadgesOnlyPendingRows() = runTest {
        listOf(JointChannelRole.Host, JointChannelRole.Participant).forEach { role ->
            val dataSource = FakeJointConnectionsDataSource(connections = externalTestConnections(role))
            val store = loadedStore(dataSource)
            val state = store.state.value

            assertEquals(listOf("srv-host", "srv-active", "srv-pending"), state.displayServers.map { it.serverId }, "role=$role")
            assertEquals(listOf("srv-host"), state.displayServers.filter(state::showsHostBadge).map { it.serverId })
            assertEquals(listOf("srv-pending"), state.displayServers.filter(state::showsStatusBadge).map { it.serverId })
            store.clear()
        }
    }

    @Test
    fun channelsFederateCapabilityGatesDisconnectingAnotherActiveServerOnHostOrParticipantCopy() = runTest {
        // #wg-rbac:#133: a host owner/admin viewing from a connected participant server where they
        // are only a local member (`canFederate = false`) still gets `federateChannels = true` and
        // may disconnect another active participant (`srv-other`), never the current server
        // (`srv-active`), the host (`srv-host`), or a pending row (`srv-pending`).
        val otherParticipant =
            JointChannelServer("srv-other", "Other Co", "other-co", active = true, isCurrentServer = false, plan = JointChannelServerPlan.Free, role = JointChannelRole.Participant)
        data class Case(
            val role: JointChannelRole?,
            val federate: Boolean?,
            val localCanFederate: Boolean,
            val expected: List<String>,
            val confirmTarget: String,
        )
        listOf(
            // Host copy: can disconnect both other active participant rows when federateChannels == true.
            Case(JointChannelRole.Host, federate = true, localCanFederate = true, expected = listOf("srv-active", "srv-other"), confirmTarget = "srv-active"),
            Case(JointChannelRole.Host, federate = true, localCanFederate = false, expected = listOf("srv-active", "srv-other"), confirmTarget = "srv-active"),
            Case(JointChannelRole.Host, federate = false, localCanFederate = true, expected = emptyList(), confirmTarget = "srv-active"),
            // Legacy server without channelCapabilities: never falls back to server-wide canFederate.
            Case(JointChannelRole.Host, federate = null, localCanFederate = true, expected = emptyList(), confirmTarget = "srv-active"),
            // Participant copy: local member + federateChannels == true -> can disconnect srv-other (not srv-active, which isCurrentServer).
            Case(JointChannelRole.Participant, federate = true, localCanFederate = false, expected = listOf("srv-other"), confirmTarget = "srv-other"),
            // Participant copy: local owner/admin + federateChannels == false -> denied.
            Case(JointChannelRole.Participant, federate = false, localCanFederate = true, expected = emptyList(), confirmTarget = "srv-other"),
            // Participant copy: legacy server without channelCapabilities -> denied even for local admin.
            Case(JointChannelRole.Participant, federate = null, localCanFederate = true, expected = emptyList(), confirmTarget = "srv-other"),
            // Missing jointRole still honors channelCapabilities.federateChannels.
            Case(null, federate = true, localCanFederate = false, expected = listOf("srv-other"), confirmTarget = "srv-other"),
        ).forEach { case ->
            val base = externalTestConnections(case.role, federateChannels = case.federate)
            val withOther = base.copy(servers = base.servers + otherParticipant)
            val dataSource = FakeJointConnectionsDataSource(connections = withOther, canFederate = case.localCanFederate)
            val store = loadedStore(dataSource)
            val state = store.state.value

            assertEquals(case.expected, state.displayServers.filter(state::canDisconnectServer).map { it.serverId }, "case=$case")
            store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer(case.confirmTarget))
            assertEquals(case.confirmTarget.takeIf { case.expected.isNotEmpty() }, store.state.value.disconnectConfirmServerId, "case=$case")
            // The host's own row, the current server's row (on participant copy), and a pending row never open a confirm.
            store.dispatch(JointChannelConnectionsIntent.DismissDisconnectServer)
            store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer("srv-host"))
            store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer("srv-pending"))
            if (case.role != JointChannelRole.Host) {
                store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer("srv-active"))
            }
            assertNull(store.state.value.disconnectConfirmServerId, "non-disconnectable rows case=$case")
            store.clear()
        }
    }

    @Test
    fun confirmedDisconnectRemovesTheRowSaysSoAndReadsTheListAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dataSource =
            FakeJointConnectionsDataSource(connections = externalTestConnections(JointChannelRole.Host)).apply { disconnectGate = gate }
        val store = loadedStore(dataSource)

        store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer("srv-active"))
        store.dispatch(JointChannelConnectionsIntent.DisconnectServer)
        runCurrent()
        assertEquals(JointChannelConnectionsAction.DisconnectServer, store.state.value.actionInFlight)
        assertEquals(listOf("srv-active"), dataSource.disconnectedServerIds)
        // Single flight: Cancel and a second confirm wait for the answer.
        store.dispatch(JointChannelConnectionsIntent.DismissDisconnectServer)
        store.dispatch(JointChannelConnectionsIntent.DisconnectServer)
        runCurrent()
        assertEquals("srv-active", store.state.value.disconnectConfirmServerId)
        assertEquals(1, dataSource.disconnectedServerIds.size)

        dataSource.loadResult =
            DataResult.Content(
                JointChannelConnectionsSnapshot(
                    externalTestConnections(JointChannelRole.Host).let { it.copy(servers = it.servers.filterNot { s -> s.serverId == "srv-active" }) },
                    canFederate = true,
                )
            )
        gate.complete(Unit)
        runCurrent()

        val state = store.state.value
        assertNull(state.actionInFlight)
        assertNull(state.disconnectConfirmServerId)
        assertEquals(listOf("srv-host", "srv-pending"), state.displayServers.map { it.serverId })
        assertEquals(JointChannelConnectionsEffect.ServerDisconnected(serverName = "Active Co", channelName = "partner-room"), state.effect)
        assertEquals(2, dataSource.loadCalls)
        store.clear()
    }

    @Test
    fun durableRecordKeepsRolesAndTheChannelCapability() {
        val snapshot = JointChannelConnectionsSnapshot(externalTestConnections(JointChannelRole.Host), canFederate = true)

        assertEquals(snapshot, snapshot.toJointConnectionsDurableRecord().toJointConnectionsSnapshot())
    }

    private fun TestScope.loadedStore(dataSource: FakeJointConnectionsDataSource): JointChannelConnectionsStore {
        val store = JointChannelConnectionsStore(dataSource, this)
        store.dispatch(JointChannelConnectionsIntent.Load)
        runCurrent()
        return store
    }
}

/**
 * A joint seen from [jointRole]'s side: another active server listed before the host (the
 * server's order), the host, and a pending invitee. The current server is the host on the
 * host's side, the active participant otherwise.
 */
internal fun externalTestConnections(
    jointRole: JointChannelRole?,
    federateChannels: Boolean? = true,
): JointChannelConnections {
    val hostSide = jointRole == JointChannelRole.Host
    return JointChannelConnections(
        channelId = ChannelId("ch-host"),
        channelName = "partner-room",
        isJoint = true,
        archived = false,
        servers =
            listOf(
                JointChannelServer("srv-active", "Active Co", "active-co", active = true, isCurrentServer = !hostSide, plan = JointChannelServerPlan.Free, role = JointChannelRole.Participant),
                JointChannelServer("srv-host", "Host Co", "host-co", active = true, isCurrentServer = hostSide, plan = JointChannelServerPlan.Paid, role = JointChannelRole.Host),
                JointChannelServer("srv-pending", "Pending Co", "pending-co", active = false, isCurrentServer = false, plan = JointChannelServerPlan.Free, role = null),
            ),
        hasCurrentServerPendingInvites = true,
        jointRole = jointRole,
        federateChannels = federateChannels,
    )
}
