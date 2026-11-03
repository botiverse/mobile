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
        assertEquals(800.dp * 0.92f, RaftSearchPanelDefaults.width(800.dp), "92% of a window under 783dp")
        assertEquals(760.dp, RaftSearchPanelDefaults.width(1440.dp, anchorWidth = 760.dp), "never narrower than the field")
        assertEquals(600.dp, RaftSearchPanelDefaults.width(600.dp, anchorWidth = 700.dp), "never wider than the window")
    }

    @Test
    fun heightIsFixedAndKeeps24UnderThePanel() {
        assertEquals(680.dp, RaftSearchPanelDefaults.height(1000.dp, top = 8.dp))
        assertEquals(800.dp * 0.8f, RaftSearchPanelDefaults.height(800.dp, top = 8.dp))
        assertEquals(500.dp - 300.dp - 24.dp, RaftSearchPanelDefaults.height(500.dp, top = 300.dp), "a low anchor")
        assertEquals(0.dp, RaftSearchPanelDefaults.height(100.dp, top = 200.dp))
    }

    @Test
    fun fallbackTopMatchesTheWebsCentredCard() {
        // searchOverlayAnchor.ts: 1000px window -> 160px, 800px -> 80px, 700px -> 72px.
        assertEquals(160f, RaftSearchPanelDefaults.fallbackTop(1000.dp).value, 0.01f)
        assertEquals(80f, RaftSearchPanelDefaults.fallbackTop(800.dp).value, 0.01f)
        assertEquals(72f, RaftSearchPanelDefaults.fallbackTop(700.dp).value, 0.01f)
    }

    @Test
    fun searchShortcutUsesCommandOnApplePlatforms() {
        assertEquals("⌘K", RaftKeyGlyph.searchShortcut(apple = true))
        assertEquals("Ctrl+K", RaftKeyGlyph.searchShortcut(apple = false))
    }
}
