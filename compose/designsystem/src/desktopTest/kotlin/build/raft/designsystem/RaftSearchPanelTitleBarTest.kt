package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.LocalRaftWindowTitleBar
import build.raft.designsystem.components.RaftSearchPanel
import build.raft.designsystem.components.RaftWindowTitleBar
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Under a window title bar (macOS desktop) the search panel hangs into the title bar's band: its
 * presses must all reach the title bar's hit test as the app's (consumed), so a press on the
 * field, on the card's blank parts or on the scrim never drags the window. The hit test here
 * records, like the desktop host's, whether each press was consumed after the panel saw it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RaftSearchPanelTitleBarTest {
    @Test
    fun everyPressInThePanelIsTheAppsNotTheTitleBars() {
        val presses = mutableListOf<Boolean>()
        var dismissed = 0
        val hitTest =
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type == PointerEventType.Press) presses += event.changes.any { it.isConsumed }
                    }
                }
            }
        ImageComposeScene(width = 1000, height = 700, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                CompositionLocalProvider(LocalRaftWindowTitleBar provides RaftWindowTitleBar(height = 48.dp, modifier = hitTest)) {
                    RaftSearchPanel(
                        onDismissRequest = { dismissed++ },
                        anchorTop = 6.dp,
                        head = { Box(Modifier.fillMaxWidth().height(60.dp)) },
                    ) {}
                }
            }
        }.use { scene ->
            scene.render()
            var time = 16_000_000L
            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse)
                scene.render(time)
                time += 16_000_000L
                scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse)
                scene.render(time)
                time += 16_000_000L
            }
            click(Offset(500f, 20f)) // the card's head, in the band
            click(Offset(500f, 300f)) // the card's blank body
            click(Offset(30f, 20f)) // the scrim, in the band
            assertEquals(listOf(true, true, true), presses, "every press is the app's")
            assertEquals(1, dismissed, "only the scrim click dismisses")
        }
    }
}
