package build.raft.app.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.RaftThreadComposerPayload
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.ThreadComposerContent
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAssistantOtterHosts
import build.raft.app.runRobolectricComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import androidx.activity.ComponentDialog
import build.raft.designsystem.text.RaftText

/**
 * The otter on a real composer (ThreadComposerContent's `frameEdge`, as ThreadComposerRoute wires
 * it) and its list-end band: it shows on the conversation's composer, moves to a thread opened
 * over it and back when that leaves, takes a focused composer, never shows where the page has
 * no otter, and a press opens the assistant. Web parity: assistantOtterState.ts `useOtterHost`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h880dp-xxhdpi")
class ThreadAssistantOtterUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun otter(hosts: RaftAssistantOtterHosts, hostId: String, onOpen: () -> Unit = {}) =
        ThreadAssistantOtter(
            hostId = hostId,
            hosts = hosts,
            mood = RaftOtterMood.Idle,
            scale = RaftAssistantOtterDefaults.PhoneScale,
            endInset = RaftAssistantOtterDefaults.PhoneEndInset,
            onOpen = onOpen,
        )

    /** One conversation: its list's end, then its composer, as ThreadPage lays them out. */
    @Composable
    private fun Conversation(tag: String, otter: ThreadAssistantOtter?) {
        Column(Modifier.fillMaxWidth().testTag(tag)) {
            Spacer(Modifier.height(24.dp))
            ThreadAssistantOtterListEnd(otter)
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = tag, placeholder = "Message #$tag"),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
                frameEdge =
                    otter?.let {
                        val edge: @Composable (Boolean, Boolean) -> Unit = { composing, focused ->
                            ThreadAssistantOtterFrameEdge(it, composing, focused, animateEntrance = false)
                        }
                        edge
                    },
            )
        }
    }

    private fun ComposeUiTest.show(content: @Composable () -> Unit) =
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { Column(Modifier.fillMaxSize()) { content() } }
            }
        }

    private fun ComposeUiTest.otterIn(conversation: String): SemanticsNodeInteraction =
        onNode(hasTestTag(ThreadAssistantOtterTestTag) and hasAnyAncestor(hasTestTag(conversation)))

    private fun ComposeUiTest.bandIn(conversation: String): SemanticsNodeInteraction =
        onNode(hasTestTag(ThreadAssistantOtterListEndTestTag) and hasAnyAncestor(hasTestTag(conversation)))

    @Test
    fun showsOnTheComposerAndMovesToAThreadOpenedOverIt() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var threadOpen by mutableStateOf(false)
            show {
                Conversation(CHANNEL, otter(hosts, CHANNEL))
                if (threadOpen) Conversation(THREAD, otter(hosts, THREAD))
            }
            otterIn(CHANNEL).assertExists()
            bandIn(CHANNEL).assertExists()

            runOnIdle { threadOpen = true }
            otterIn(THREAD).assertExists()
            bandIn(THREAD).assertExists()
            otterIn(CHANNEL).assertDoesNotExist()
            bandIn(CHANNEL).assertDoesNotExist()

            runOnIdle { threadOpen = false }
            otterIn(CHANNEL).assertExists()
            bandIn(CHANNEL).assertExists()
            runOnIdle { assertEquals(listOf(CHANNEL), hosts.order.value) }
        }

    @Test
    fun theComposerUsedLastTakesIt() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            show {
                Conversation(CHANNEL, otter(hosts, CHANNEL))
                Conversation(THREAD, otter(hosts, THREAD))
            }
            otterIn(THREAD).assertExists()
            // Focus in the channel's composer (the editor's onFocus) moves it there.
            runOnIdle { hosts.activate(CHANNEL) }
            otterIn(CHANNEL).assertExists()
            otterIn(THREAD).assertDoesNotExist()
            bandIn(THREAD).assertDoesNotExist()
        }

    @Test
    fun aPageWithoutTheOtterShowsNothing() =
        runRobolectricComposeUiTest {
            show { Conversation(CHANNEL, otter = null) }
            otterIn(CHANNEL).assertDoesNotExist()
            bandIn(CHANNEL).assertDoesNotExist()
        }

    @Test
    fun aPressOpensTheAssistant() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var opened = 0
            show { Conversation(CHANNEL, otter(hosts, CHANNEL) { opened++ }) }
            onNode(hasClickAction() and hasAnyAncestor(hasTestTag(ThreadAssistantOtterTestTag))).performClick()
            runOnIdle { assertEquals(1, opened) }
        }

    /** The otter as rememberThreadAssistantOtter makes it: a press opens its panel, held in [open]. */
    private fun panelOtter(hosts: RaftAssistantOtterHosts, open: Boolean, onOpenChange: (Boolean) -> Unit) =
        ThreadAssistantOtter(
            hostId = CHANNEL,
            hosts = hosts,
            mood = RaftOtterMood.Idle,
            scale = RaftAssistantOtterDefaults.PhoneScale,
            endInset = RaftAssistantOtterDefaults.PhoneEndInset,
            onOpen = { onOpenChange(true) },
            assistantName = "Ada",
            agentId = "agent-ada",
            serverId = "server-1",
            dmChannelId = "dm-ada",
            panelOpen = open,
            onPanelOpenChange = onOpenChange,
        )

    /** A channel with the otter on its composer, and its panel as ThreadPage draws it over the page. */
    private fun ComposeUiTest.showWithPanel(hosts: RaftAssistantOtterHosts, isOpen: () -> Boolean, setOpen: (Boolean) -> Unit) =
        show {
            val otter = panelOtter(hosts, isOpen(), setOpen)
            Conversation(CHANNEL, otter)
            ThreadAssistantPanelSheet(otter) {
                RaftText("Morning brief", Modifier.testTag(PANEL_DM))
            }
        }

    private fun ComposeUiTest.pressOtter() =
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag(ThreadAssistantOtterTestTag))).performClick()

    @Test
    fun aPressOpensThePanelOverThePageAndBackClosesIt() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var open by mutableStateOf(false)
            showWithPanel(hosts, { open }, { open = it })
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()

            pressOtter()
            // The sheet: the panel's head with the assistant's name, and its DM under it.
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertExists()
            onNode(hasText("Ada") and hasAnyAncestor(hasTestTag(ThreadAssistantPanelHeaderTestTag))).assertExists()
            onNode(hasTestTag(PANEL_DM)).assertExists()
            // The otter stays on the composer (tucked in), under the sheet.
            otterIn(CHANNEL).assertExists()
            runOnIdle { assertTrue(open) }

            // System Back goes to the sheet's window and closes it.
            runOnIdle {
                val sheetWindow = assertNotNull(ShadowDialog.getLatestDialog() as? ComponentDialog, "the sheet's window")
                sheetWindow.onBackPressedDispatcher.onBackPressed()
            }
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
            runOnIdle { assertFalse(open) }
            otterIn(CHANNEL).assertExists()
        }

    @Test
    fun collapseClosesThePanel() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var open by mutableStateOf(false)
            showWithPanel(hosts, { open }, { open = it })
            pressOtter()
            onNode(hasTestTag(ThreadAssistantPanelCollapseTestTag)).performClick()
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
            runOnIdle { assertFalse(open) }
        }

    private companion object {
        const val CHANNEL = "channel"
        const val THREAD = "thread"
        const val PANEL_DM = "panel-dm"
    }
}
