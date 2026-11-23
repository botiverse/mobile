package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.JointChannelRole
import build.raft.app.data.JointChannelServer
import build.raft.app.data.MemberConnectResponse
import build.raft.app.data.MemberConnectServerLimit
import build.raft.app.data.ScreenError
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.model.ChannelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connect your servers (#13981; web memberConnect.ts / ConnectYourServersDialog.tsx, botiverse/slock
 * staging 44ce160f1495). Which servers are offered comes from what the server sent: the account's
 * servers with its role on each and the channel's `jointServers`; each outcome from the route's answer.
 */
class JointMemberConnectStoreTest {
    @Test
    fun rowsAreEligibleThenConnectedThenNotAdminAndNeverTheHostAGuestSeatOrACommunity() {
        val rows = memberConnectRows(memberTestConnections().memberConnectChannel(), memberTestAccount())

        assertEquals(
            listOf(
                "srv-alpha" to MemberConnectRowState.Eligible,
                "srv-zed" to MemberConnectRowState.Eligible,
                // This server is in the channel too (web lists it as connected).
                "srv-cur" to MemberConnectRowState.Connected,
                "srv-mine" to MemberConnectRowState.Connected,
                "srv-member" to MemberConnectRowState.NotAdmin,
            ),
            rows.map { it.server.id to it.state },
        )
    }

    @Test
    fun aChannelThatTakesNoServerOffersNone() {
        val account = memberTestAccount()
        listOf(
            memberTestConnections().copy(joined = false),
            memberTestConnections().copy(joined = null),
            memberTestConnections().copy(archived = true),
            memberTestConnections().copy(servers = memberTestConnections().servers.map { it.copy(active = false) }),
            privateTestConnections().copy(channelName = "all"),
            // A public channel.
            privateTestConnections().copy(isPrivate = false),
        ).forEach { connections ->
            assertEquals(emptyList(), memberConnectRows(connections.memberConnectChannel(), account), "$connections")
        }
    }

    @Test
    fun aPrivateChannelHasThisServerAsItsHostAndOffersTheOthers() {
        val rows = memberConnectRows(privateTestConnections().memberConnectChannel(), memberTestAccount())
        // The current server is the host: not listed. Every other is open (none is connected yet),
        // the joint's host of the other test channel included.
        assertEquals(listOf("srv-alpha", "srv-host", "srv-mine", "srv-zed", "srv-member"), rows.map { it.server.id })
        assertEquals(MemberConnectRowState.Eligible, rows.first { it.server.id == "srv-mine" }.state)
    }

    @Test
    fun onlyEligibleRowsCanBeChosenAndAllConnectedClosesWithAToastAndRereads() = runTest {
        val dataSource = FakeJointConnectionsDataSource(connections = memberTestConnections())
        dataSource.memberConnectResult =
            DataResult.Content(
                MemberConnectResponse.Answered(
                    listOf(ServerConnectOutcome.Connected("srv-zed", "c1"), ServerConnectOutcome.Connected("srv-alpha", "c2")),
                    traceId = null,
                )
            )
        val memory = MemberConnectedByYouMemory()
        val store = memberStore(dataSource, connectedByYou = MemberConnectedByYouSlot(memory, "k"))
        store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect)
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(setOf("srv-zed", "srv-member", "srv-mine", "srv-alpha")))
        assertEquals(setOf("srv-zed", "srv-alpha"), store.state.value.memberConnectSheet?.selected)
        val loadsBefore = dataSource.loadCalls

        store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect)
        runCurrent()

        assertEquals(listOf(listOf("srv-zed", "srv-alpha")), dataSource.memberConnectCalls)
        val state = store.state.value
        assertNull(state.memberConnectSheet)
        assertEquals(JointChannelConnectionsEffect.MemberConnectFinished(connected = listOf("Zed Co", "Alpha Co")), state.effect)
        assertEquals(setOf("srv-zed", "srv-alpha"), state.connectedByYou)
        assertEquals(setOf("srv-zed", "srv-alpha"), memory.get("k"))
        assertEquals(loadsBefore + 1, dataSource.loadCalls)
        store.clear()

        // The marker outlives the page: a new store for the same key starts with it.
        val again = JointChannelConnectionsStore(dataSource, this, connectedByYouSlot = MemberConnectedByYouSlot(memory, "k"))
        assertEquals(setOf("srv-zed", "srv-alpha"), again.state.value.connectedByYou)
        again.clear()
    }

    @Test
    fun aPartialAnswerStaysOpenWithALinePerServerItsTraceIdAndOnlyRetriableRowsChosen() = runTest {
        val dataSource = FakeJointConnectionsDataSource(connections = memberTestConnections())
        dataSource.memberConnectResult =
            DataResult.Content(
                MemberConnectResponse.Answered(
                    listOf(
                        ServerConnectOutcome.Connected("srv-alpha", "c1"),
                        ServerConnectOutcome.Refused("srv-zed", 403, "server_admin_required", "x"),
                        ServerConnectOutcome.Refused("srv-two", 409, "channel_conversion_in_progress", "x"),
                    ),
                    traceId = TRACE,
                )
            )
        val store =
            memberStore(
                dataSource,
                account = memberTestAccount().let { it.copy(servers = it.servers + MemberConnectServer("srv-two", "Two Co", "two-co", null, "admin")) },
            )
        store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect)
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(setOf("srv-alpha", "srv-zed", "srv-two")))
        store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect)
        runCurrent()

        val state = store.state.value
        val sheet = assertNotNull(state.memberConnectSheet)
        assertEquals(
            listOf(
                MemberConnectLine.Connected(listOf("srv-alpha")),
                MemberConnectLine.Refused("srv-zed", MemberConnectMessage.NotAdmin),
                MemberConnectLine.Refused("srv-two", MemberConnectMessage.Busy),
            ),
            sheet.lines,
        )
        assertEquals(TRACE, sheet.traceId)
        assertEquals(setOf("srv-alpha"), sheet.done)
        // No longer an admin: the row says so and can't be chosen; a busy channel can be tried again.
        assertEquals(mapOf("srv-zed" to MemberConnectRowState.NotAdmin), sheet.rowStates)
        assertEquals(setOf("srv-two"), sheet.selected)
        assertFalse(sheet.busy)
        assertEquals(setOf("srv-alpha"), state.connectedByYou)
        assertNull(state.effect)
        store.clear()
    }

    @Test
    fun networkFailureSaysTryAgainWithItsTraceIdAndTheSheetCannotBeClosedMidRequest() = runTest {
        val dataSource = FakeJointConnectionsDataSource(connections = memberTestConnections())
        dataSource.memberConnectResult = DataResult.Error(ScreenError(code = "network_unavailable", traceId = null))
        val gate = CompletableDeferred<Unit>()
        dataSource.memberConnectGate = gate
        val store = memberStore(dataSource)
        store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect)
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(setOf("srv-zed")))
        store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect)
        runCurrent()
        assertTrue(store.state.value.memberConnectSheet?.busy == true)
        store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect)
        assertNotNull(store.state.value.memberConnectSheet)

        gate.complete(Unit)
        runCurrent()
        val sheet = assertNotNull(store.state.value.memberConnectSheet)
        assertEquals(listOf(MemberConnectLine.Request(MemberConnectMessage.Generic)), sheet.lines)
        assertFalse(sheet.closed)
        store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect)
        assertNull(store.state.value.memberConnectSheet)
        store.clear()
    }

    @Test
    fun aGuestOfTheHostIsToldTheirSeatEnds() = runTest {
        val guestAccount = memberTestAccount().copy(currentServerRole = "guest")
        val host = memberStore(FakeJointConnectionsDataSource(connections = memberTestConnections(jointRole = JointChannelRole.Host)), account = guestAccount)
        assertTrue(host.state.value.memberConnectGuest)
        host.clear()
        val participant = memberStore(FakeJointConnectionsDataSource(connections = memberTestConnections()), account = guestAccount)
        assertFalse(participant.state.value.memberConnectGuest)
        participant.clear()
    }

    @Test
    fun limitsDisableAtLimitRowsPruneSelectionAndConnectionsNotifyChangesOnPartialAndFullConnect() = runTest {
        val dataSource = FakeJointConnectionsDataSource(connections = memberTestConnections())
        val limitsGate = CompletableDeferred<Unit>()
        dataSource.memberConnectLimitsGate = limitsGate
        dataSource.memberConnectLimits =
            mapOf(
                "srv-zed" to MemberConnectServerLimit(serverId = "srv-zed", atLimit = true, canUpgrade = true),
                "srv-alpha" to MemberConnectServerLimit(serverId = "srv-alpha", atLimit = false, canUpgrade = false),
            )
        var connectionsChangedCount = 0
        val store = memberStore(dataSource, onConnectionsChanged = { connectionsChangedCount += 1 })
        store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect)
        // Before limits arrive, user checks both servers.
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(setOf("srv-zed", "srv-alpha")))
        assertEquals(setOf("srv-zed", "srv-alpha"), store.state.value.memberConnectSheet?.selected)

        // When limits resolve, at-limit srv-zed becomes unselectable and is pruned from selection.
        limitsGate.complete(Unit)
        runCurrent()
        assertEquals(1, dataSource.memberConnectLimitsCalls)
        assertEquals(setOf("srv-alpha"), store.state.value.memberConnectSheet?.selected)
        val zedRow = store.state.value.memberConnectRows.first { it.server.id == "srv-zed" }
        assertFalse(store.state.value.memberConnectRowSelectable(zedRow))
        assertEquals(true, store.state.value.memberConnectLimit(zedRow)?.canUpgrade)

        // Partial success still triggers onConnectionsChanged so parent projections refresh.
        dataSource.memberConnectResult =
            DataResult.Content(
                MemberConnectResponse.Answered(
                    listOf(
                        ServerConnectOutcome.Connected("srv-alpha", "c1"),
                        ServerConnectOutcome.Refused("srv-zed", 409, "free_external_limit", "limit"),
                    ),
                    traceId = TRACE,
                )
            )
        store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect)
        runCurrent()
        assertEquals(1, connectionsChangedCount)
        store.clear()
    }

    private fun TestScope.memberStore(
        dataSource: FakeJointConnectionsDataSource,
        account: MemberConnectAccount = memberTestAccount(),
        connectedByYou: MemberConnectedByYouSlot? = null,
        onConnectionsChanged: () -> Unit = {},
    ): JointChannelConnectionsStore {
        val store = JointChannelConnectionsStore(dataSource, this, connectedByYouSlot = connectedByYou, onConnectionsChanged = onConnectionsChanged)
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(account))
        store.dispatch(JointChannelConnectionsIntent.Load)
        runCurrent()
        return store
    }

    private companion object {
        const val TRACE = "0123456789abcdef0123456789abcdef"
    }
}

/**
 * The viewer's servers: the current one (a member of it), two they administer (out of name
 * order), one connected already, one they only belong to, the joint's host, a Guest seat and
 * the community server.
 */
internal fun memberTestAccount(): MemberConnectAccount =
    MemberConnectAccount(
        currentServerId = "srv-cur",
        currentServerName = "Current Co",
        currentServerSlug = "current-co",
        currentServerRole = "member",
        servers =
            listOf(
                MemberConnectServer("srv-cur", "Current Co", "current-co", null, "member"),
                MemberConnectServer("srv-zed", "Zed Co", "zed-co", null, "owner"),
                MemberConnectServer("srv-alpha", "Alpha Co", "alpha-co", null, "admin"),
                MemberConnectServer("srv-mine", "Mine Co", "mine-co", null, "owner"),
                MemberConnectServer("srv-member", "Member Co", "member-co", null, "member"),
                MemberConnectServer("srv-host", "Host Co", "host-co", null, "admin"),
                MemberConnectServer("srv-guest", "Guest Co", "guest-co", null, "guest"),
                MemberConnectServer("srv-community", "Raft Community", "community", null, "admin"),
            ),
    )

/** A joint the viewer is in, seen from the current server: the host, this server and Mine Co connected. */
internal fun memberTestConnections(jointRole: JointChannelRole = JointChannelRole.Participant): JointChannelConnections =
    JointChannelConnections(
        channelId = ChannelId("ch-copy"),
        channelName = "partner-room",
        isJoint = true,
        archived = false,
        servers =
            listOf(
                JointChannelServer("srv-host", "Host Co", "host-co", active = true, isCurrentServer = false, plan = null, role = JointChannelRole.Host),
                JointChannelServer("srv-cur", "Current Co", "current-co", active = true, isCurrentServer = true, plan = null, role = JointChannelRole.Participant),
                JointChannelServer("srv-mine", "Mine Co", "mine-co", active = true, isCurrentServer = false, plan = null, role = JointChannelRole.Participant),
            ),
        hasCurrentServerPendingInvites = false,
        jointRole = jointRole,
        federateChannels = false,
        joined = true,
    )

/** This server's private channel, the viewer in it. */
internal fun privateTestConnections(): JointChannelConnections =
    JointChannelConnections(
        channelId = ChannelId("ch-private"),
        channelName = "secret-room",
        isJoint = false,
        archived = false,
        servers = emptyList(),
        hasCurrentServerPendingInvites = false,
        isPrivate = true,
        joined = true,
    )
