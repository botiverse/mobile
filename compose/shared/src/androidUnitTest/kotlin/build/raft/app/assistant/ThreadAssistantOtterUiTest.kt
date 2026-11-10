package build.raft.app.assistant

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftPendingMentionActionItem
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
 * it): it shows on the conversation's composer, lying over the list's end without moving it, moves to a thread opened
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
    private fun Conversation(
        tag: String,
        otter: ThreadAssistantOtter?,
        pendingMentionActions: List<RaftPendingMentionActionItem> = emptyList(),
        onStackedAbove: (Dp) -> Unit = {},
    ) {
        Column(Modifier.fillMaxWidth().testTag(tag)) {
            // The message list's end: the otter lies over it and takes no space there.
            Spacer(Modifier.height(24.dp).testTag("$tag-list-end"))
            ThreadComposerContent(
                payload =
                    RaftThreadComposerPayload(
                        instanceId = tag,
                        placeholder = "Message #$tag",
                        pendingMentionActions = pendingMentionActions,
                    ),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
                frameEdge =
                    otter?.let {
                        val edge: @Composable (Boolean, Boolean, Dp) -> Unit = { composing, focused, stackedAbove ->
                            onStackedAbove(stackedAbove)
                            ThreadAssistantOtterFrameEdge(it, composing, focused, stackedAbove, animateEntrance = false)
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

            runOnIdle { threadOpen = true }
            otterIn(THREAD).assertExists()
            otterIn(CHANNEL).assertDoesNotExist()

            runOnIdle { threadOpen = false }
            otterIn(CHANNEL).assertExists()
            runOnIdle { assertEquals(listOf(CHANNEL), hosts.order.value) }
        }

    /**
     * artin 11-03: the otter lies over the message list; its coming or going neither resizes nor
     * moves the list (no list-end band). Here the channel's list end keeps its place while the
     * otter leaves the channel's composer for another one and comes back.
     */
    @Test
    fun theOtterComingOrGoingNeverMovesTheList() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var threadOpen by mutableStateOf(false)
            show {
                Conversation(CHANNEL, otter(hosts, CHANNEL))
                if (threadOpen) Conversation(THREAD, otter(hosts, THREAD))
            }
            otterIn(CHANNEL).assertExists()
            val listEnd = onNode(hasTestTag("$CHANNEL-list-end"))
            val withOtter = listEnd.fetchSemanticsNode().boundsInRoot
            val composerTop = onNode(hasTestTag(CHANNEL)).fetchSemanticsNode().boundsInRoot

            runOnIdle { threadOpen = true }
            otterIn(CHANNEL).assertDoesNotExist()
            assertEquals(withOtter, listEnd.fetchSemanticsNode().boundsInRoot)
            assertEquals(composerTop, onNode(hasTestTag(CHANNEL)).fetchSemanticsNode().boundsInRoot)

            runOnIdle { threadOpen = false }
            otterIn(CHANNEL).assertExists()
            assertEquals(withOtter, listEnd.fetchSemanticsNode().boundsInRoot)
            assertEquals(composerTop, onNode(hasTestTag(CHANNEL)).fetchSemanticsNode().boundsInRoot)
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
        }

    /**
     * Artea #proj-assistant:#24 (web `useNoticesLift`): while the "not in #channel — Add / Notify /
     * Ignore" card sits above the composer, the otter lies on top of the card, over none of its
     * buttons, and the composer and the list keep their places; when the card goes, it lies on the
     * frame again.
     */
    @Test
    fun aMentionCardAboveTheComposerLiftsTheOtterClearOfItsButtons() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var actions by mutableStateOf(listOf(RaftPendingMentionActionItem(resolutionId = "res-1", targetHandle = "Volta", availableActions = listOf("add", "notify_only"))))
            var stackedAbove: Dp? = null
            show { Conversation(CHANNEL, otter(hosts, CHANNEL), pendingMentionActions = actions, onStackedAbove = { stackedAbove = it }) }
            runOnIdle { assertTrue(assertNotNull(stackedAbove) > 0.dp) }
            val lifted = otterButton(CHANNEL).fetchSemanticsNode().boundsInRoot
            // Every button outside the otter (the card's, the composer's) starts below the otter's edge.
            val buttons =
                onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(CHANNEL)) and !hasAnyAncestor(hasTestTag(ThreadAssistantOtterTestTag)))
                    .fetchSemanticsNodes()
            assertTrue(buttons.isNotEmpty())
            val slack = with(density) { 2.dp.toPx() }
            buttons.forEach { button ->
                assertTrue(button.boundsInRoot.top >= lifted.bottom - slack, "${button.config} under the otter")
            }

            runOnIdle { actions = emptyList() }
            runOnIdle { assertEquals(0.dp, stackedAbove) }
            val down = otterButton(CHANNEL).fetchSemanticsNode().boundsInRoot
            assertTrue(down.top > lifted.top, "back on the frame's edge: $down vs $lifted")
        }

    /**
     * Web `data-composing`: while the owner types it goes into the composer (most of it under the
     * frame's edge), and once the message is sent or the composer cleared it comes back out where
     * it was. It stays this composer's all along.
     */
    @Test
    fun typingTucksItInAndSendingBringsItBack() =
        runRobolectricComposeUiTest {
            val hosts = RaftAssistantOtterHosts()
            var composing by mutableStateOf(false)
            val otter = otter(hosts, CHANNEL)
            show {
                Column(Modifier.fillMaxWidth().testTag(CHANNEL)) {
                    Spacer(Modifier.height(160.dp))
                    SampleFrame { ThreadAssistantOtterFrameEdge(otter, composing = composing, focused = true, animateEntrance = false) }
                }
            }
            val resting = otterButton(CHANNEL).fetchSemanticsNode().boundsInRoot

            // Typing.
            runOnIdle { composing = true }
            val typing = otterButton(CHANNEL).fetchSemanticsNode().boundsInRoot
            assertTrue(typing.top - resting.top > resting.height * 0.4f, "into the composer: $typing vs $resting")
            otterIn(CHANNEL).assertExists()

            // Sent (or cleared): the composer is empty again.
            runOnIdle { composing = false }
            assertEquals(resting, otterButton(CHANNEL).fetchSemanticsNode().boundsInRoot)
            runOnIdle { assertEquals(listOf(CHANNEL), hosts.order.value) }
        }

    /** A frame-like box whose top edge carries [edge], as the composer's frame does. */
    @Composable
    private fun SampleFrame(edge: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth()) {
            Spacer(Modifier.fillMaxWidth().height(48.dp))
            edge()
        }
    }

    /** The otter's own button (the pose), as a reader presses it. */
    private fun ComposeUiTest.otterButton(conversation: String): SemanticsNodeInteraction =
        onNode(hasClickAction() and hasAnyAncestor(hasTestTag(ThreadAssistantOtterTestTag)) and hasAnyAncestor(hasTestTag(conversation)))

    @Test
    fun aPageWithoutTheOtterShowsNothing() =
        runRobolectricComposeUiTest {
            show { Conversation(CHANNEL, otter = null) }
            otterIn(CHANNEL).assertDoesNotExist()
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
