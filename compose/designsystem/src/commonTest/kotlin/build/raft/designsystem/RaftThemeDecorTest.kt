package build.raft.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftThemeDecorSlot
import build.raft.designsystem.theme.raftThemeDecorShown
import build.raft.designsystem.theme.raftThemeDecorTopPlacement
import build.raft.designsystem.tokens.RaftThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Theme decor gating (raft-ui `frame-decor-top` / `frame-decor-rail`) and the generated art. */
class RaftThemeDecorTest {
    private val wide = 1280.dp
    private val slots = RaftThemeDecorSlot.entries

    @Test
    fun decorOffDrawsNothing() {
        RaftThemeId.entries.forEach { theme ->
            slots.forEach { slot ->
                listOf(false, true).forEach { dark ->
                    assertFalse(raftThemeDecorShown(theme, dark, decor = false, slot, wide), "$theme $slot dark=$dark")
                }
            }
        }
    }

    @Test
    fun theTopStripNeedsA1024dpWindow() {
        val top = RaftThemeDecorSlot.Top
        assertFalse(raftThemeDecorShown(RaftThemeId.Bots, false, decor = true, top, 390.dp), "phone portrait")
        assertFalse(raftThemeDecorShown(RaftThemeId.Bots, false, decor = true, top, 840.dp), "narrow tablet")
        assertFalse(raftThemeDecorShown(RaftThemeId.Bots, false, decor = true, top, 1023.dp))
        assertFalse(raftThemeDecorShown(RaftThemeId.Bots, false, decor = true, top, Dp.Unspecified), "unknown width")
        assertTrue(raftThemeDecorShown(RaftThemeId.Bots, false, decor = true, top, 1024.dp))
        assertTrue(raftThemeDecorShown(RaftThemeId.Bots, true, decor = true, top, wide))
    }

    @Test
    fun theTopStripDrawsOnlyWhenItFitsWhole() {
        val art = 190.dp
        // Room to the centre's end: 332dp at a 1024dp bar with a 360dp search field.
        assertEquals(72.dp, raftThemeDecorTopPlacement(0.dp, 0.dp, centerEndOffset = 332.dp, artWidth = art))
        assertEquals(142.dp, raftThemeDecorTopPlacement(126.dp, 0.dp, centerEndOffset = 332.dp, artWidth = art), "just fits")
        assertNull(raftThemeDecorTopPlacement(127.dp, 0.dp, centerEndOffset = 332.dp, artWidth = art), "1dp short: skipped, never cut")
        assertNull(raftThemeDecorTopPlacement(141.dp, 0.dp, centerEndOffset = 332.dp, artWidth = art), "actions at 1024dp")
        // With a 138dp end inset the centre's end moves in too (the bar's content shrinks by it).
        assertNull(raftThemeDecorTopPlacement(141.dp, 138.dp, centerEndOffset = 263.dp + 138.dp, artWidth = art))
        assertEquals(295.dp, raftThemeDecorTopPlacement(141.dp, 138.dp, centerEndOffset = 504.dp, artWidth = art), "1280dp bar")
        // Nothing measured at the centre: only the offset applies.
        assertEquals(72.dp, raftThemeDecorTopPlacement(0.dp, 0.dp, centerEndOffset = Dp.Infinity, artWidth = art))
    }
}
