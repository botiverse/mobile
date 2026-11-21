package build.raft.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.data.JointChannelRole
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connected servers on a real composition, wired as the page wires it (the store's state into
 * [JointConnectedServersSection], its confirm into [JointServerDisconnectConfirmDialog]): the Host
 * badge, the pending badge, and the host's per-row Disconnect asking first (web).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class JointChannelConnectionsExternalChannelsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.show(role: JointChannelRole = JointChannelRole.Host): FakeJointConnectionsDataSource {
        val dataSource = FakeJointConnectionsDataSource(connections = externalTestConnections(role))
        val store = JointChannelConnectionsStore(dataSource, CoroutineScope(Dispatchers.Main.immediate))
        store.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointConnectedServersSection(
                    state = state,
                    onDisconnectServer = { store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer(it)) },
                )
                state.disconnectConfirmServer?.let { server ->
                    JointServerDisconnectConfirmDialog(
                        serverName = server.name,
                        channelName = "partner-room",
                        busy = state.actionInFlight == JointChannelConnectionsAction.DisconnectServer,
                        failure = state.disconnectFailure,
                        onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissDisconnectServer) },
                        onConfirm = { store.dispatch(JointChannelConnectionsIntent.DisconnectServer) },
                    )
                }
            }
        }
        waitForIdle()
        return dataSource
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun hostSeesTheHostBadgeAndOnlyThePendingRowBadged() = runRobolectricComposeUiTest {
        show()

        assertEquals(1, count(JointConnectionsHostBadgeTag))
        // Only the pending row says so; a connected server needs no badge.
        assertEquals(1, count(JointConnectionsStatusBadgeTag))
        // Disconnect on the other active server only: not the host's row, not the pending one.
        assertEquals(1, count(JointConnectionsDisconnectTag))
    }

    @Test
    fun participantGetsNoPerRowDisconnect() = runRobolectricComposeUiTest {
        show(role = JointChannelRole.Participant)

        assertEquals(1, count(JointConnectionsHostBadgeTag))
        assertEquals(0, count(JointConnectionsDisconnectTag))
    }

    @Test
    fun perRowDisconnectAsksFirstThenCallsTheHostRoute() = runRobolectricComposeUiTest {
        val dataSource = show()

        onNodeWithTag(JointConnectionsDisconnectTag, useUnmergedTree = true).performClick()
        waitForIdle()
        // The confirm names the server and the channel; nothing is sent yet.
        assertEquals(true, shown("Disconnect Active Co?"))
        assertEquals(true, shown("#partner-room"))
        assertEquals(emptyList(), dataSource.disconnectedServerIds)

        onNodeWithTag(JointConnectionsDisconnectConfirmTag, useUnmergedTree = true).performClick()
        waitForIdle()

        assertEquals(listOf("srv-active"), dataSource.disconnectedServerIds)
        assertNull(onAllNodes(hasTestTag(JointConnectionsDisconnectConfirmTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull())
    }
}
