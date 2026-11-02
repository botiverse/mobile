package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.raftToastAvoid
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui Toast in its viewport (`toast/compositions/above-composer`): on a 375dp phone frame the
 * toast sits bottom centre, 16dp above the bar that holds the bottom of the page
 * ([raftToastAvoid]). One toast (raft-ui's `limit` 1), and two stacked (a `limit` 2 host:
 * newest at the bottom, 8dp apart).
 */
class ToastHostScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun toastHost() =
        appearances.forEach { appearance ->
            // Two frames: the avoided bar reports its edge after the first layout.
            RaftScreenshot.capture("toast_host_${appearance.name.lowercase()}", appearance, settleFrames = 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption("One toast (limit 1)")
                        PhoneFrame(limit = 1) { show("Link copied.") }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Caption("Two toasts (limit 2), newest at the bottom")
                        PhoneFrame(limit = 2) {
                            show("Settings saved.", intent = RaftToastIntent.Success)
                            show("Wug finished reviewing #25.", actionLabel = "View", onAction = {})
                        }
                    }
                }
            }
        }

    /** A 375 x 240dp page with a 56dp bar on its bottom edge, and the toast host over both. */
    @Composable
    private fun PhoneFrame(limit: Int, toasts: RaftToastHostState.() -> Unit) {
        // Shown before the host composes: they are on screen at once, without the slide.
        val state = remember { RaftToastHostState(limit = limit).apply(toasts) }
        CompositionLocalProvider(LocalRaftToastHostState provides state) {
            Box(Modifier.size(375.dp, 240.dp).border(1.dp, RaftTheme.colors.lineSeam).background(RaftTheme.colors.layerCanvas)) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(RaftTheme.colors.layerCard)
                        .border(1.dp, RaftTheme.colors.lineSeam)
                        .raftToastAvoid(),
                    contentAlignment = Alignment.Center,
                ) { Caption("Composer / tab bar") }
                RaftToastHost(state)
            }
        }
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
