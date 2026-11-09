package build.raft.app.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppAppearance
import build.raft.app.RaftRobolectricScreenshot
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
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.roundToInt
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The assistant's panel on a wide window (web AssistantOtter's Popover at 768px and up): a 1280 x
 * 800dp conversation with the otter on its composer and the panel 380 x 540dp above it, its end
 * on the otter's end, 8dp off it, no scrim. In the app the popover is its own popup window, which
 * the capture can't see, so it is drawn in place here at the bounds the popup uses
 * ([threadAssistantPanelPopoverBounds] from the otter's reported rest box).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class ThreadAssistantPanelPopoverScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun wideLight() = capture("assistant.panel.wide", RaftAppAppearance.Light)

    @Test
    fun wideDark() = capture("assistant.panel.wide.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            val rest = remember { ThreadAssistantOtterRest() }
            val otter =
                remember {
                    ThreadAssistantOtter(
                        hostId = "channel",
                        hosts = RaftAssistantOtterHosts(),
                        mood = RaftOtterMood.Idle,
                        scale = RaftAssistantOtterDefaults.WideScale,
                        endInset = RaftAssistantOtterDefaults.WideEndInset,
                        onOpen = {},
                        assistantName = "Otto",
                        readsDms = true,
                        panelOpen = true,
                        rest = rest,
                    )
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    val colors = RaftTheme.colors
                    // The popup places in window coordinates; this page starts below the status bar.
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    Box(Modifier.fillMaxSize().background(colors.layerCanvas).onGloballyPositioned { origin = it.positionInWindow() }) {
                        Column(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                            ) {
                                RaftText("Ada  Shipping the release notes after lunch.", color = colors.foreground)
                                RaftText("Grace  Sounds good, I'll review them.", color = colors.foreground)
                            }
                            ThreadComposerContent(
                                payload = RaftThreadComposerPayload(instanceId = "channel", placeholder = "Message #launch-week"),
                                onEvent = { _, _, _, _ -> },
                                attachmentPreviewEnabled = false,
                                frameEdge = { composing, focused, covered ->
                                    ThreadAssistantOtterFrameEdge(otter, composing, focused, covered, animateEntrance = false)
                                },
                            )
                        }
                        val density = LocalDensity.current
                        val window = LocalWindowInfo.current.containerSize
                        val bounds =
                            with(density) {
                                threadAssistantPanelPopoverBounds(
                                    rest = rest.bounds,
                                    window = window,
                                    width = ThreadAssistantPanelPopoverDefaults.Width.toPx(),
                                    height = ThreadAssistantPanelPopoverDefaults.Height.toPx(),
                                    sideOffset = ThreadAssistantPanelPopoverDefaults.SideOffset.toPx(),
                                    padding = ThreadAssistantPanelPopoverDefaults.CollisionPadding.toPx(),
                                )
                            }
                        Box(Modifier.offset { IntOffset((bounds.left - origin.x).roundToInt(), (bounds.top - origin.y).roundToInt()) }) {
                            with(density) {
                                ThreadAssistantPanelPopoverSurface(bounds.width.toDp(), bounds.height.toDp()) {
                                    ThreadAssistantPanel(
                                        name = otter.assistantName,
                                        mood = otter.mood,
                                        readsDms = otter.readsDms,
                                        paused = false,
                                        onCollapse = {},
                                    ) {
                                        Column(
                                            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                                        ) {
                                            RaftText("Morning brief: 3 tasks due today, 2 reviews waiting.", color = colors.foreground)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
