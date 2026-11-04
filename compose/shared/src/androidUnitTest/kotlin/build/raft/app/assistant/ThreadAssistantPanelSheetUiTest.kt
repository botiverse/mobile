package build.raft.app.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.RaftForegroundBannerHost
import build.raft.app.RaftForegroundMessageBanner
import build.raft.app.RaftForegroundMessageBannerIconKind
import build.raft.app.RaftForegroundMessageBannerView
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.ThreadLaunchConversationIdentity
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAssistantOtterHosts
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import build.raft.app.runRobolectricComposeUiTest
import build.raft.designsystem.text.RaftText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The assistant's panel sheet (ThreadAssistantPanelSheet) as ThreadPage draws it over a
 * conversation: the collapse chevron takes a finger's tap, the drawer slides up and back down,
 * and one notification banner shows while the panel is open, over the panel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadAssistantPanelSheetUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun otter(open: Boolean, onOpenChange: (Boolean) -> Unit) =
        ThreadAssistantOtter(
            hostId = PAGE,
            hosts = RaftAssistantOtterHosts(),
            mood = RaftOtterMood.Working,
            scale = RaftAssistantOtterDefaults.PhoneScale,
            endInset = RaftAssistantOtterDefaults.PhoneEndInset,
            onOpen = { onOpenChange(true) },
            assistantName = "Ottie",
            agentId = "agent-ottie",
            serverId = "server-1",
            dmChannelId = "dm-ottie",
            panelOpen = open,
            onPanelOpenChange = onOpenChange,
            panelEmbed = ThreadAssistantPanelEmbed(agentId = "agent-ottie", onWaitingActionCard = {}),
        )

    private val banner =
        ThreadLaunchConversationIdentity(
            threadId = ThreadId("thread-antiproton"),
            channelId = ChannelId("channel-antiproton"),
            kind = ConversationKind.Channel,
        ).let { conversation ->
            RaftForegroundMessageBanner(
                id = "banner-1",
                serverId = "server-1",
                focusedMessageId = "message-1",
                conversation = conversation,
                openRequest = HomeMessageOpenRequest(conversation, "message-1"),
                title = "#raft-antiproton",
                context = "Raft",
                threadContext = null,
                body = "Antx: @Grace got it",
                iconKind = RaftForegroundMessageBannerIconKind.Channel,
                iconName = null,
                senderMemberId = null,
                avatarWireValue = null,
                avatarLabel = "A",
            )
        }

    /**
     * A conversation page with its banner host, and its assistant panel, wired as ThreadPage wires
     * them: the page's host and the DM's own host (inside the panel) both filter through
     * [threadForegroundBannerOnPage]; the panel's window draws the page's banner while it is open.
     */
    @Composable
    private fun Page(open: Boolean, setOpen: (Boolean) -> Unit, banner: RaftForegroundMessageBanner?) {
        val otter = otter(open, setOpen)
        RaftForegroundBannerHost(
            banner = banner?.takeIf { threadForegroundBannerOnPage(inAssistantPanel = false, assistantPanelOpen = otter.panelOpen) },
            topSafeArea = 0.dp,
            onBannerClick = {},
        ) {
            Box(Modifier.fillMaxSize().testTag(PAGE)) {
                RaftText("Channel history")
            }
            ThreadAssistantPanelSheet(
                otter,
                windowOverlay = {
                    banner?.takeIf { otter.panelOpen }?.let {
                        RaftForegroundMessageBannerView(banner = it, topSafeArea = 0.dp, onClick = {})
                    }
                },
            ) {
                // The DM is a conversation page too, with its own banner host.
                RaftForegroundBannerHost(
                    banner =
                        banner?.takeIf {
                            threadForegroundBannerOnPage(
                                inAssistantPanel = LocalThreadAssistantPanelEmbed.current != null,
                                assistantPanelOpen = false,
                            )
                        },
                    topSafeArea = 0.dp,
                    onBannerClick = {},
                ) {
                    RaftText("Morning brief", Modifier.testTag(PANEL_DM))
                }
            }
        }
    }

    private fun ComposeUiTest.show(content: @Composable () -> Unit) =
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }

    @Test
    fun theCollapseChevronIsAFingerWideAndATapNearItsEdgeCloses() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(true)
            show { Page(open, { open = it }, banner = null) }
            val collapse = onNode(hasTestTag(ThreadAssistantPanelCollapseTestTag))
            collapse.assertWidthIsAtLeast(44.dp).assertHeightIsAtLeast(44.dp)
            // A real touch, 4dp in from the top-end corner: outside the old 32dp button, which sat
            // centred in this box, so this tap used to land on nothing.
            collapse.performTouchInput { click(Offset(width - 4.dp.toPx(), 4.dp.toPx())) }
            runOnIdle { assertFalse(open) }
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
        }

    @Test
    fun oneBannerShowsAndItIsOverThePanelWhileThePanelIsOpen() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(false)
            show { Page(open, { open = it }, banner = banner) }
            val bannerText = hasText("#raft-antiproton")
            // Closed: the page's own banner.
            onAllNodes(bannerText, useUnmergedTree = true).assertCountEquals(1)

            runOnIdle { open = true }
            onNode(hasTestTag(PANEL_DM)).assertExists()
            // Open: one banner, drawn by the panel's window, not the page under the scrim nor the DM.
            onAllNodes(bannerText, useUnmergedTree = true).assertCountEquals(1)
            onNode(bannerText and hasAnyAncestor(hasTestTag(ThreadAssistantPanelSheetTestTag)), useUnmergedTree = true)
                .assertDoesNotExist()
            val sheetTop = onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).getBoundsInRoot().top
            val bannerTop = onNode(bannerText, useUnmergedTree = true).getBoundsInRoot().top
            assertTrue(bannerTop < sheetTop, "the banner sits at the window's top, above the sheet ($bannerTop < $sheetTop)")

            // Closed again: back on the page, still one.
            onNode(hasTestTag(ThreadAssistantPanelCollapseTestTag)).performTouchInput { click() }
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
            onAllNodes(bannerText, useUnmergedTree = true).assertCountEquals(1)
        }

    @Test
    fun theDrawerSlidesUpAfterItsFirstFrameAndSlidesDownBeforeItGoes() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(false)
            show { Page(open, { open = it }, banner = null) }
            mainClock.autoAdvance = false
            runOnIdle { open = true }
            // The panel inside the drawer surface: its bounds carry the slide (the sheet's own tag
            // sits outside the surface's translation).
            val sheet = onNode(hasTestTag(ThreadAssistantPanelTestTag))
            // The window's first frame composes and measures the content; the slide starts after
            // it, so a heavy first frame (a whole conversation) cannot eat it.
            var frames = 0
            while (onAllNodes(hasTestTag(ThreadAssistantPanelSheetTestTag)).fetchSemanticsNodes().isEmpty()) {
                assertTrue(++frames < 20, "the sheet's window never showed")
                mainClock.advanceTimeByFrame()
            }
            val start = sheet.getBoundsInRoot()
            mainClock.advanceTimeByFrame()
            assertEquals(start.top, sheet.getBoundsInRoot().top, "still not moving on the frame after the first")
            // 120ms into the 240ms slide.
            mainClock.advanceTimeBy(120)
            val midway = sheet.getBoundsInRoot().top
            mainClock.advanceTimeBy(300)
            val seated = sheet.getBoundsInRoot().top
            val height = start.bottom - start.top
            assertTrue(start.top >= seated + height * 0.98f, "below the window first: ${start.top} vs seated $seated + $height")
            assertTrue(midway > seated && midway < start.top, "on its way up: $seated < $midway < ${start.top}")

            // Collapse: it slides back down while still composed, then the window goes.
            runOnIdle { open = false }
            // Frame by frame until the window goes: on the way it shows the surface part way down.
            val tops = mutableListOf<Float>()
            while (onAllNodes(hasTestTag(ThreadAssistantPanelTestTag)).fetchSemanticsNodes().isNotEmpty()) {
                assertTrue(tops.size < 40, "the drawer never went: $tops")
                tops += sheet.getBoundsInRoot().top.value
                mainClock.advanceTimeByFrame()
            }
            assertTrue(
                tops.count { it > seated.value + 1f && it < start.top.value } >= 3,
                "it slid down over several frames before going: $tops",
            )
            mainClock.advanceTimeBy(300)
            onNode(hasTestTag(ThreadAssistantPanelSheetTestTag)).assertDoesNotExist()
        }

    private companion object {
        const val PAGE = "page"
        const val PANEL_DM = "panel-dm"
    }
}
