package build.raft.app.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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
import build.raft.designsystem.text.RaftText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A wide window (tablet, Compose 版, landscape; web `!phone`, 768px and up): the otter opens the
 * assistant's panel as web AssistantOtter's Popover, not the phone's bottom sheet: 380 x 540dp,
 * above where the otter rests, its end on the otter's end, 8dp off it; no scrim, and it goes at
 * once when collapsed (raft-ui Popover has no motion).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class ThreadAssistantPanelPopoverUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val hosts = RaftAssistantOtterHosts()
    private val rest = ThreadAssistantOtterRest()

    private fun otter(open: Boolean, onOpenChange: (Boolean) -> Unit) =
        ThreadAssistantOtter(
            hostId = CHANNEL,
            hosts = hosts,
            mood = RaftOtterMood.Idle,
            scale = RaftAssistantOtterDefaults.WideScale,
            endInset = RaftAssistantOtterDefaults.WideEndInset,
            onOpen = { onOpenChange(true) },
            assistantName = "Ottie",
            agentId = "agent-ottie",
            serverId = "server-1",
            dmChannelId = "dm-ottie",
            panelOpen = open,
            onPanelOpenChange = onOpenChange,
            panelEmbed = ThreadAssistantPanelEmbed(agentId = "agent-ottie", onWaitingActionCard = {}),
            rest = rest,
        )

    @Composable
    private fun Page(open: Boolean, setOpen: (Boolean) -> Unit) {
        val otter = otter(open, setOpen)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = CHANNEL, placeholder = "Message #$CHANNEL"),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
                frameEdge = { composing, focused, covered ->
                    ThreadAssistantOtterFrameEdge(otter, composing, focused, covered, animateEntrance = false)
                },
            )
        }
        ThreadAssistantPanelSheet(otter) {
            RaftText("Morning brief", Modifier.testTag(PANEL_DM))
        }
    }

    private lateinit var pageDensity: Density

    private fun ComposeUiTest.show(content: @Composable () -> Unit) =
        setContent {
            pageDensity = LocalDensity.current
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }

    @Test
    fun aWideWindowOpensThePopoverAboveTheOtterWithoutTheSheet() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(false)
            show { Page(open, { open = it }) }
            runOnIdle { open = true }
            onNode(hasTestTag(PANEL_DM)).assertExists()
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
            // The popover is its own popup window: compare on screen (the page's window is at the origin).
            val node = onNode(hasTestTag(ThreadAssistantPanelPopoverTestTag)).fetchSemanticsNode()
            val popover = androidx.compose.ui.geometry.Rect(node.positionOnScreen, node.size.toSize())
            val otterRest = assertNotNull(rest.bounds, "the otter reported where it rests")
            val px = { dp: Float -> dp * pageDensity.density }
            assertTrue(abs(popover.width - px(380f)) <= 1f, "380dp wide: ${popover.width}")
            assertTrue(abs(popover.height - px(540f)) <= 1f, "540dp tall: ${popover.height}")
            assertTrue(abs(popover.right - otterRest.right) <= 1f, "its end on the otter's end: ${popover.right} vs ${otterRest.right}")
            assertTrue(abs(otterRest.top - popover.bottom - px(8f)) <= 1f, "8dp above the otter: ${popover.bottom} vs ${otterRest.top}")
        }

    @Test
    fun collapseClosesThePopoverAtOnce() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(false)
            show { Page(open, { open = it }) }
            runOnIdle { open = true }
            onNode(hasTestTag(ThreadAssistantPanelPopoverTestTag)).assertExists()
            // No slide or fade (raft-ui Popover has no motion): two frames after the tap it is gone,
            // while the otter, still easing back out of the composer, keeps the clock running.
            mainClock.autoAdvance = false
            onNode(hasTestTag(ThreadAssistantPanelCollapseTestTag)).performClick()
            repeat(2) {
                mainClock.advanceTimeByFrame()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertFalse(open)
            onNode(hasTestTag(ThreadAssistantPanelPopoverTestTag)).assertDoesNotExist()
            onNode(hasTestTag(PANEL_DM)).assertDoesNotExist()
        }

    private companion object {
        const val CHANNEL = "channel"
        const val PANEL_DM = "panel-dm"
    }
}
