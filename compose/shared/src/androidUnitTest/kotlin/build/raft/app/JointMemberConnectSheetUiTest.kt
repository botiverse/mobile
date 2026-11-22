package build.raft.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.MemberConnectResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connected servers ⋯ > Connect your servers on a real composition, wired as the page wires it
 * (the store's state into [JointConnectedServersSection] and [JointMemberConnectSheet]): the menu only
 * with a server to connect, the sheet's rows with why each can or can't be chosen, the earlier
 * messages notice for a private channel, a partial answer's lines with the trace ID, and
 * "Connected by you" on the list once the sheet closes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class JointMemberConnectSheetUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.show(
        connections: JointChannelConnections = memberTestConnections(),
        answer: MemberConnectResponse? = null,
    ): Pair<JointChannelConnectionsStore, FakeJointConnectionsDataSource> {
        val dataSource = FakeJointConnectionsDataSource(connections = connections)
        answer?.let { dataSource.memberConnectResult = DataResult.Content(it) }
        val store =
            JointChannelConnectionsStore(
                dataSource,
                CoroutineScope(Dispatchers.Main.immediate),
                connectedByYouSlot = MemberConnectedByYouSlot(MemberConnectedByYouMemory(), "ui"),
            )
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberTestAccount()))
        store.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointConnectedServersSection(
                    state = state,
                    onConnectYourServers = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect) },
                )
                JointMemberConnectSheet(
                    state = state,
                    onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                    onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                )
            }
        }
        waitForIdle()
        return store to dataSource
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.openSheet() {
        onNodeWithTag(JointMemberConnectMenuTag, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag(JointMemberConnectMenuItemTag, useUnmergedTree = true).performClick()
        waitForIdle()
    }

    @Test
    fun aPrivateChannelSaysTheConnectedServersSeeItsEarlierMessages() = runRobolectricComposeUiTest {
        show(connections = privateTestConnections())
        // This server is the host row.
        assertEquals(1, count(JointConnectionsHostBadgeTag))
        openSheet()
        onNodeWithText("Alpha Co", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("#secret-room becomes a channel shared with other servers, and the servers you connect see its earlier messages."))
    }
}
