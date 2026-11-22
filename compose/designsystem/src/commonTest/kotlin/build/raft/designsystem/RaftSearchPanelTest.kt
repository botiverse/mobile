package build.raft.designsystem

import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftKeyGlyph
import build.raft.designsystem.components.RaftSearchPanelDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

/** The web search panel's placement (searchOverlayAnchor.ts) and the shortcut text. */
class RaftSearchPanelTest {
    @Test
    fun widthIsTheAnchorOrTheReadablePanelWhicheverIsWider() {
        assertEquals(720.dp, RaftSearchPanelDefaults.width(1440.dp))
        assertEquals(700.dp * 0.92f, RaftSearchPanelDefaults.width(700.dp), "92% of a window under 783dp")
        assertEquals(760.dp, RaftSearchPanelDefaults.width(1440.dp, anchorWidth = 760.dp), "never narrower than the field")
        assertEquals(600.dp, RaftSearchPanelDefaults.width(600.dp, anchorWidth = 700.dp), "never wider than the window")
    }

    @Test
    fun searchShortcutUsesCommandOnApplePlatforms() {
        assertEquals("⌘K", RaftKeyGlyph.searchShortcut(apple = true))
        assertEquals("Ctrl+K", RaftKeyGlyph.searchShortcut(apple = false))
    }
}
