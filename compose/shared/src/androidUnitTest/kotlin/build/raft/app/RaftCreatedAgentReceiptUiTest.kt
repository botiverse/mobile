package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #4621 review: the "Created <name>" toast lives on the window, but View opens the DM through the
 * page that created the agent. On a real composition: while the page is there, View opens the
 * DM; once it has left, its toast is gone and a View captured before opens nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftCreatedAgentReceiptUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val agent =
        MemberProfile(id = MemberId("agent-reef"), displayName = "Reef", handle = "reef", kind = MemberKind.Agent, serverId = ServerId("server-1"))
    private val dm =
        DataResult.Content(
            OpenDirectMessageResult(
                conversation = HomeDirectMessageRow(threadId = ThreadId("dm-reef"), channelId = ChannelId("dm-reef"), name = "Reef"),
                directMessages = emptyList(),
            )
        )

    private class Calls {
        val fetched = mutableListOf<MemberProfile>()
        val opened = mutableListOf<MemberProfile>()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showPage(
        state: RaftToastHostState,
        calls: Calls,
        pageShown: () -> Boolean,
        onReceipt: (RaftCreatedAgentReceipt) -> Unit,
    ) {
        // Outside the composition: the receipt is kept per scope.
        val viewScope = CoroutineScope(Dispatchers.Unconfined)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftToastHostState provides state) {
                    Box(Modifier.fillMaxSize()) {
                        if (pageShown()) {
                            val receipt =
                                rememberCreatedAgentReceipt(
                                    toastHost = state,
                                    copy = raftCreatedAgentToastCopy(),
                                    openSetupGuide = {},
                                    openDirectMessage = { member ->
                                        calls.fetched += member
                                        dm
                                    },
                                    showDirectMessage = { _, member -> calls.opened += member },
                                    fallbackToast = {},
                                    currentServerId = { "server-1" },
                                    scope = viewScope,
                                )
                            onReceipt(receipt)
                        }
                        RaftToastHost(state)
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun viewOpensTheDirectMessageWhileThePageIsShown() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        val calls = Calls()
        var receipt: RaftCreatedAgentReceipt? = null
        showPage(state, calls, pageShown = { true }) { receipt = it }

        runOnIdle { checkNotNull(receipt).land(CreatedAgentLanding.CreatedToast(agent)) }
        waitForIdle()
        onNodeWithText("Created Reef").assertIsDisplayed()

        runOnIdle { checkNotNull(state.visible.single().onAction).invoke() }
        waitForIdle()
        assertEquals(listOf(agent), calls.fetched)
        assertEquals(listOf(agent), calls.opened)
    }

    @Test
    fun afterThePageLeavesTheToastIsGoneAndViewOpensNothing() = runRobolectricComposeUiTest {
        val state = RaftToastHostState()
        val calls = Calls()
        var shown by mutableStateOf(true)
        var receipt: RaftCreatedAgentReceipt? = null
        showPage(state, calls, pageShown = { shown }) { receipt = it }

        runOnIdle { checkNotNull(receipt).land(CreatedAgentLanding.CreatedToast(agent)) }
        waitForIdle()
        val view = runOnIdle { checkNotNull(state.visible.single().onAction) }

        runOnIdle { shown = false }
        waitForIdle()
        assertTrue(state.visible.isEmpty(), "the toast closes with the page that would open the DM")

        runOnIdle { view() }
        waitForIdle()
        assertTrue(calls.fetched.isEmpty(), "no DM request after the page left")
        assertTrue(calls.opened.isEmpty())
    }
}
