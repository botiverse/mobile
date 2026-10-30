package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.Res
import build.raft.app.resources.stringResource
import build.raft.app.resources.thread_action_back_to_bottom
import build.raft.designsystem.components.RaftMessageListJumpControls
import build.raft.designsystem.components.RaftMessageListJumpPill
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The channel's bottom jump controls on a phone: Back to Bottom with "Next Unread Thread (3)"
 * stacked above it (raft-ui MessageList jump controls, `phone` composition), light and dark; and
 * busy while a tap loads the parent (web #11358 ThreadUnreadJumpPill: the `sm` spinner in place
 * of the arrow, "Loading Thread…").
 *
 * Goldens: `src/androidUnitTest/screenshots/<name>.png`; record with `:shared:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w375dp-h140dp-xxhdpi")
class ThreadUnreadJumpPillScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun idleLight() = capture("thread.unread-jump-pill", RaftAppAppearance.Light)

    @Test fun idleDark() = capture("thread.unread-jump-pill.dark", RaftAppAppearance.Dark)

    @Test fun busyLight() = capture("thread.unread-jump-pill.busy", RaftAppAppearance.Light, busy = true)

    @Test fun busyDark() = capture("thread.unread-jump-pill.busy.dark", RaftAppAppearance.Dark, busy = true)

    private fun capture(name: String, appearance: RaftAppAppearance, busy: Boolean = false) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    RaftMessageListJumpControls(Modifier.align(Alignment.BottomCenter)) {
                        RaftMessageListJumpPill(text = stringResource(Res.string.thread_action_back_to_bottom), onClick = {})
                        ThreadUnreadThreadJumpPill(jump = Jump, onJump = {}, onDismiss = {}, busy = busy)
                    }
                }
            }
        }

    private companion object {
        const val ReportDir = "build/reports/thread-screenshots"
        val Jump =
            ThreadUnreadThreadJump(
                direction = ThreadUnreadThreadJumpDirection.Next,
                count = 3,
                targetMessageId = "m-parent",
                targetThreadChannelId = "t-1",
            )
    }
}
