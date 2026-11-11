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
 * [JointConnectionsContent], its confirm into [JointServerDisconnectConfirmDialog]): with
 * `external_channels_v1` on, the host's Host badge, no plan chips / Active badge / invite form /
 * Resend, and the host's per-row Disconnect asking first; off, the page as before.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class JointChannelConnectionsExternalChannelsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.show(externalChannels: Boolean, role: JointChannelRole = JointChannelRole.Host): FakeJointConnectionsDataSource {
        val dataSource = FakeJointConnectionsDataSource(connections = externalTestConnections(role))
        val store = JointChannelConnectionsStore(dataSource, CoroutineScope(Dispatchers.Main.immediate))
        store.dispatch(JointChannelConnectionsIntent.SetExternalChannelsEnabled(externalChannels))
        store.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointConnectionsContent(
                    state = state,
                    slug = "",
                    people = "",
                    sameAdminConfirmed = false,
                    statusMessage = null,
                    onSlugChange = {},
                    onPeopleChange = {},
                    onSameAdminConfirmedChange = {},
                    onSendInvite = {},
                    onResend = {},
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
    fun hostSeesTheHostBadgeOnlyPendingBadgedAndNoInviteOrResend() = runRobolectricComposeUiTest {
        show(externalChannels = true)

        assertEquals(1, count(JointConnectionsHostBadgeTag))
        assertEquals(0, count(JointConnectionsPlanBadgeTag))
        // Only the pending row says so; a connected server needs no badge.
        assertEquals(1, count(JointConnectionsStatusBadgeTag))
        assertEquals(0, count(JointConnectionsInviteTag))
        assertEquals(0, count(JointConnectionsResendTag))
        // Disconnect on the other active server only: not the host's row, not the pending one.
        assertEquals(1, count(JointConnectionsDisconnectTag))
    }

    @Test
    fun flagOffKeepsPlanChipsStatusBadgesInviteAndResend() = runRobolectricComposeUiTest {
        show(externalChannels = false)

        assertEquals(0, count(JointConnectionsHostBadgeTag))
        assertEquals(3, count(JointConnectionsPlanBadgeTag))
        assertEquals(3, count(JointConnectionsStatusBadgeTag))
        assertEquals(1, count(JointConnectionsInviteTag))
        assertEquals(1, count(JointConnectionsResendTag))
        assertEquals(0, count(JointConnectionsDisconnectTag))
    }

    @Test
    fun participantGetsNoPerRowDisconnect() = runRobolectricComposeUiTest {
        show(externalChannels = true, role = JointChannelRole.Participant)

        assertEquals(1, count(JointConnectionsHostBadgeTag))
        assertEquals(0, count(JointConnectionsDisconnectTag))
    }

    @Test
    fun perRowDisconnectAsksFirstThenCallsTheHostRoute() = runRobolectricComposeUiTest {
        val dataSource = show(externalChannels = true)

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
