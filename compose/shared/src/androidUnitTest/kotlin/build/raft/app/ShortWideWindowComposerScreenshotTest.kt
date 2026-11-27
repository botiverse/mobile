package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.thread.ThreadComposerDock
import build.raft.app.thread.raftThreadComposerLiveKeyboardPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #338 (Reef's patrol item 8): a large phone in landscape (914 x 411) lays out wide, so under the 48 top bar and the
 * conversation's head the keyboard (250 here, drawn as a grey block; the host reports it, as Robolectric dispatches no
 * IME inset) leaves the composer about 40. The composer keeps its height on top of the keyboard, over the messages
 * and the head ([raftThreadComposerKeptPlacement]); before, it was squashed into those 40 and its field cut in half.
 * `keyboard-down` is the same page without it. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w914dp-h411dp-mdpi")
class ShortWideWindowComposerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun keyboardUp() = capture("conversation.short-wide.keyboard-up", keyboardDp = 250)

    @Test fun keyboardDown() = capture("conversation.short-wide.keyboard-down", keyboardDp = 0)

    private fun capture(name: String, keyboardDp: Int) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/short-wide", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            // mdpi: a px is a dp.
            val ime = remember { RaftHostImeInsets().apply { imeBottomPx = keyboardDp } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftHostImeInsets provides ime) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        Column(Modifier.fillMaxSize()) {
                            // The wide shell's top bar.
                            Box(Modifier.fillMaxWidth().height(48.dp).background(RaftTheme.colors.layerFrame))
                            RaftPanelBackHeader(title = "# all", subtitle = "General chat", onBack = null)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                Column(Modifier.fillMaxSize()) {
                                    Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite).padding(16.dp)) {
                                        RaftText("qa-test33: 1.29-cda6-smoke-2230", style = RaftTheme.typography.body)
                                        RaftText("alex: the composer keeps its height above the keyboard", style = RaftTheme.typography.body)
                                    }
                                    ThreadComposerDock(
                                        composerModifier =
                                            Modifier.raftThreadComposerLiveKeyboardPadding(
                                                bottomSafeArea = 0.dp,
                                                keyboardInsetExcludesBottomSafeArea = true,
                                            ),
                                    ) {
                                        ThreadComposerContent(
                                            payload = RaftThreadComposerPayload(instanceId = "short-wide-dock", placeholder = "Message #all"),
                                            onEvent = { _, _, _, _ -> },
                                            attachmentPreviewEnabled = false,
                                        )
                                    }
                                }
                            }
                        }
                        if (keyboardDp > 0) {
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardDp.dp).background(RaftColor.TextMutedGray))
                        }
                    }
                }
            }
        }
}
