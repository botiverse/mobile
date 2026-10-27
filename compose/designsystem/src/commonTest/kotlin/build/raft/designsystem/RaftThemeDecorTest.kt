package build.raft.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftThemeDecorSlot
import build.raft.designsystem.theme.raftThemeDecorArt
import build.raft.designsystem.theme.raftThemeDecorShown
import build.raft.designsystem.tokens.RaftThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Theme decor gating (raft-ui `frame-decor-top` / `frame-decor-rail`) and the generated art. */
class RaftThemeDecorTest {
    private val wide = 1280.dp
    private val withoutArt = setOf(RaftThemeId.Raft, RaftThemeId.Boring)
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
    fun themesWithoutArtDrawNothing() {
        withoutArt.forEach { theme ->
            slots.forEach { slot ->
                listOf(false, true).forEach { dark ->
                    assertFalse(raftThemeDecorShown(theme, dark, decor = true, slot, wide), "$theme $slot dark=$dark")
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
    fun theRailStripHasNoWidthGate() {
        // raft-ui frame-decor-rail has no media query; the rail itself only shows on wide layouts.
        listOf(Dp.Unspecified, 840.dp, wide).forEach { width ->
            assertTrue(raftThemeDecorShown(RaftThemeId.Space, false, decor = true, RaftThemeDecorSlot.Rail, width), "$width")
        }
    }

    @Test
    fun everyOtherThemeHasBothStripsInBothModes() {
        (RaftThemeId.entries - withoutArt).forEach { theme ->
            listOf(false, true).forEach { dark ->
                val top = assertNotNull(raftThemeDecorArt(theme, dark, decor = true, RaftThemeDecorSlot.Top, wide), "$theme top")
                assertEquals(190 to 48, top.width to top.height, "$theme top size")
                val rail = assertNotNull(raftThemeDecorArt(theme, dark, decor = true, RaftThemeDecorSlot.Rail), "$theme rail")
                assertEquals(48 to 112, rail.width to rail.height, "$theme rail size")
                listOf(top, rail).forEach { art ->
                    assertTrue(art.layers.isNotEmpty(), "$theme has layers")
                    art.layers.forEach { layer ->
                        assertEquals(1f, layer.color.alpha, "$theme opaque fills")
                        layer.rects.toList().chunked(4).forEach { (x, y, w, h) ->
                            assertTrue(w > 0 && h > 0 && x >= 0 && y >= 0 && x + w <= art.width && y + h <= art.height, "$theme rect in bounds")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun weatherAndSpookyRepaintForDarkMode() {
        // themes.css `.dark[data-theme="weather"]` / `"spooky"` set their own decor; the rest share it.
        listOf(RaftThemeId.Weather, RaftThemeId.Spooky).forEach { theme ->
            slots.forEach { slot ->
                assertNotSame(
                    raftThemeDecorArt(theme, false, decor = true, slot, wide),
                    raftThemeDecorArt(theme, true, decor = true, slot, wide),
                    "$theme $slot",
                )
            }
        }
        slots.forEach { slot ->
            assertSame(
                raftThemeDecorArt(RaftThemeId.Bots, false, decor = true, slot, wide),
                raftThemeDecorArt(RaftThemeId.Bots, true, decor = true, slot, wide),
                "bots $slot",
            )
        }
    }
}
