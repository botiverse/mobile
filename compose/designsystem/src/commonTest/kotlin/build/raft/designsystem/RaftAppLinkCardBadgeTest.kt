package build.raft.designsystem

import androidx.compose.ui.graphics.Color
import build.raft.designsystem.components.raftContrastRatio
import build.raft.designsystem.components.raftFillOnSurface
import build.raft.designsystem.components.raftParseHexColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** raft-ui 0.95.4 `lib/fill-contrast.ts`, as AppLinkCardBadge uses it. */
class RaftAppLinkCardBadgeTest {
    private val whiteCard = Color(0xFFFFFFFF)
    private val darkCard = Color(0xFF242422)

    @Test
    fun onlyRrggbbParses() {
        assertEquals(Color(0xFF8250DF), raftParseHexColor("#8250DF"))
        assertEquals(Color(0xFF8250DF), raftParseHexColor("#8250df"))
        assertNull(raftParseHexColor("#fff"))
        assertNull(raftParseHexColor("8250df"))
        assertNull(raftParseHexColor("#8250dfff"))
        assertNull(raftParseHexColor("#+250df"))
        assertNull(raftParseHexColor("purple"))
        assertNull(raftParseHexColor(null))
    }

    @Test
    fun contrastIsWcag2() {
        assertTrue(abs(raftContrastRatio(Color.Black, Color.White) - 21f) < 0.01f)
        assertTrue(abs(raftContrastRatio(Color.White, Color.White) - 1f) < 0.001f)
    }

    @Test
    fun theTypeIsWhicheverReadsBetterAndARingPartsAFillCloseToTheCard() {
        // Stamp's merged purple: white type, clear of both cards.
        val purple = Color(0xFF8250DF)
        assertTrue(raftFillOnSurface(purple, whiteCard).whiteText)
        assertFalse(raftFillOnSurface(purple, whiteCard).ring)
        assertFalse(raftFillOnSurface(purple, darkCard).ring)
        // A pale yellow on a white card: black type and a ring; none on the dark card.
        val pale = Color(0xFFFFF3B0)
        assertFalse(raftFillOnSurface(pale, whiteCard).whiteText)
        assertTrue(raftFillOnSurface(pale, whiteCard).ring)
        assertFalse(raftFillOnSurface(pale, darkCard).ring)
        // A near-black on the dark card: white type and a ring.
        val nearBlack = Color(0xFF1A1A1A)
        assertTrue(raftFillOnSurface(nearBlack, darkCard).whiteText)
        assertTrue(raftFillOnSurface(nearBlack, darkCard).ring)
        assertFalse(raftFillOnSurface(nearBlack, whiteCard).ring)
    }
}
