package build.raft.designsystem

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.Key
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftSafeArea
import build.raft.designsystem.components.raftCounterAlarm
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.components.raftTextLinks
import build.raft.designsystem.components.raftMenuKeyTarget
import build.raft.designsystem.components.raftMenuMaxSurfaceHeight
import build.raft.designsystem.components.raftMenuMaxSurfaceWidth
import build.raft.designsystem.components.raftMenuPosition
import build.raft.designsystem.components.raftRevealScrollTarget
import build.raft.designsystem.components.raftSheetConstraints
import build.raft.designsystem.components.raftToggleableState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftFieldLookTest {
    @Test
    fun theCounterTurnsRedPastTheLimitNotAtIt() {
        assertFalse(raftCounterAlarm(280, 280, invalid = false))
        assertTrue(raftCounterAlarm(281, 280, invalid = false))
        assertTrue(raftCounterAlarm(3, 280, invalid = true))
    }
}

class RaftValueSelectionTest {
    @Test
    fun toggleableStates() {
        assertEquals(ToggleableState.Off, raftToggleableState(checked = false, indeterminate = false))
        assertEquals(ToggleableState.On, raftToggleableState(checked = true, indeterminate = false))
        assertEquals(ToggleableState.Indeterminate, raftToggleableState(checked = true, indeterminate = true))
    }
}

class RaftNavigationItemTest {
    @Test
    fun anInlineTextLinkIsFoundWithItsVariantAndWrapsWithTheSentence() {
        var clicked = 0
        val text =
            buildAnnotatedString {
                append("Don't have an account? ")
                appendRaftTextLink("Create One", onClick = { clicked++ })
                append(" ")
                appendRaftTextLink("Log Out", onClick = {}, variant = RaftTextLinkVariant.Muted, enabled = false)
            }
        val links = raftTextLinks(text)
        assertEquals(2, links.size)
        assertEquals("Create One", text.text.substring(links[0].start, links[0].end), "plain spaces: it wraps with the sentence (raft-design #1306)")
        assertEquals(RaftTextLinkVariant.Strong, links[0].variant, "strong by default inside a sentence")
        assertTrue(links[0].enabled)
        assertEquals(RaftTextLinkVariant.Muted, links[1].variant)
        assertFalse(links[1].enabled)
        links[0].annotation.linkInteractionListener?.onClick(links[0].annotation)
        assertEquals(1, clicked)
    }

    @Test
    fun aTabStripScrollsOnlyAsFarAsTheSelectedTabNeeds() {
        // A 200px strip scrolled to 100, content scrolls up to 500; 8px room round the tab.
        fun target(start: Int, end: Int) = raftRevealScrollTarget(start, end, room = 8, viewport = 200, current = 100, max = 500)
        assertEquals(100, target(150, 250), "already in view: no scroll")
        assertEquals(42, target(50, 120), "past the start edge: its start (less the room) at the edge")
        assertEquals(158, target(250, 350), "past the end edge: its end (plus the room) at the edge")
        assertEquals(500, target(600, 700), "clamped to the furthest scroll")
        assertEquals(0, target(4, 60), "never before the start")
        assertEquals(292, target(300, 600), "wider than the strip: its start shows")
    }
}

class RaftOverlayTest {
    private val insets = RaftPopupMargins(horizontal = 16, top = 8, bottom = 24)
    private val window = IntSize(400, 800)

    /** A popup [w] x [h] at its surface, plus the shadow room. */
    private fun popup(w: Int, h: Int) = IntSize(w + 32, h + 32)

    @Test
    fun aMenuStaysInsideTheWindowAndFlipsAboveWhenThereIsMoreRoom() {
        val anchor = IntRect(300, 700, 380, 732)
        val at = raftMenuPosition(anchor, window, popup(240, 200), gap = 6, windowMargin = 8, insets = insets)
        // Pushed left to end 8px inside the window; above the anchor: 700 - 6 - 200.
        assertEquals(IntOffset(400 - 8 - 240 - 16, 494 - 8), at)
    }

    @Test
    fun aMenuIsNoTallerThanTheSafeAreaAboveTheKeyboard() {
        // A 640px window with a 300px keyboard and a 40px status bar: 8px inside both.
        val keyboard = RaftSafeArea(top = 40, bottom = 300)
        assertEquals(640 - 300 - 8 - (40 + 8), raftMenuMaxSurfaceHeight(640, windowMargin = 8, insets = insets, safeArea = keyboard))
        // No insets: the shadow room (8 above, 24 below) stays inside the window too.
        assertEquals(640 - 24 - 8, raftMenuMaxSurfaceHeight(640, windowMargin = 8, insets = insets, safeArea = RaftSafeArea()))
        // A capped popup placed under an anchor ends above the keyboard.
        val surface = raftMenuMaxSurfaceHeight(640, windowMargin = 8, insets = insets, safeArea = keyboard)
        val at = raftMenuPosition(IntRect(20, 60, 140, 92), IntSize(400, 640), popup(240, surface), gap = 6, windowMargin = 8, insets = insets, safeArea = keyboard)
        assertTrue(at.y + insets.top + surface <= 640 - 300 - 8, "surface ends at ${at.y + insets.top + surface}")
    }

    @Test
    fun aMenuIsNoWiderThanTheSafeArea() {
        // A 400px window with a 44px left cutout and a 57px right frame inset: 8px inside both.
        val sideInsets = RaftSafeArea(left = 44, right = 57)
        assertEquals(400 - 57 - 8 - (44 + 8), raftMenuMaxSurfaceWidth(400, windowMargin = 8, insets = insets, safeArea = sideInsets))
        // No safe-area insets: the shadow room (16px each side) stays inside the window too.
        assertEquals(400 - 16 - 16, raftMenuMaxSurfaceWidth(400, windowMargin = 8, insets = insets, safeArea = RaftSafeArea()))
        // A tiny window narrower than margins coerces to 0 rather than going negative.
        assertEquals(0, raftMenuMaxSurfaceWidth(20, windowMargin = 8, insets = insets, safeArea = RaftSafeArea()))
        // A capped-width menu placed under a right-edge anchor stays inside both safe edges.
        val surface = raftMenuMaxSurfaceWidth(400, windowMargin = 8, insets = insets, safeArea = sideInsets)
        val at = raftMenuPosition(IntRect(300, 100, 340, 132), IntSize(400, 640), popup(surface, 160), gap = 6, windowMargin = 8, insets = insets, safeArea = sideInsets)
        val surfaceLeft = at.x + insets.horizontal
        val surfaceRight = surfaceLeft + surface
        assertTrue(surfaceLeft >= sideInsets.left + 8, "surface starts at $surfaceLeft")
        assertTrue(surfaceRight <= 400 - sideInsets.right - 8, "surface ends at $surfaceRight")
    }

    @Test
    fun aSheetCapNeverGoesBelowTheIncomingMinimum() {
        assertEquals(656, raftSheetConstraints(Constraints(minHeight = 0, maxHeight = 800)).maxHeight)
        // A caller's heightIn(min = 700) on an 800px window: the cap gives way, no crash.
        assertEquals(700, raftSheetConstraints(Constraints(minHeight = 700, maxHeight = 800)).maxHeight)
        assertFalse(raftSheetConstraints(Constraints(minHeight = 0)).hasBoundedHeight)
    }

    @Test
    fun menuKeysMoveThroughTheRows() {
        val rows = listOf("Archive", "Mark as Read", "Mute", "Notify Me")
        assertEquals(0, raftMenuKeyTarget(rows, -1, Key.DirectionDown, 0))
        assertEquals(3, raftMenuKeyTarget(rows, -1, Key.DirectionUp, 0))
        assertEquals(0, raftMenuKeyTarget(rows, 3, Key.DirectionDown, 0), "down wraps")
        assertEquals(3, raftMenuKeyTarget(rows, 0, Key.DirectionUp, 0), "up wraps")
        assertEquals(3, raftMenuKeyTarget(rows, 1, Key.MoveEnd, 0))
        assertEquals(0, raftMenuKeyTarget(rows, 2, Key.MoveHome, 0))
        // Type-ahead: the next row after the focused one starting with the letter.
        assertEquals(1, raftMenuKeyTarget(rows, 0, Key.M, 'm'.code))
        assertEquals(2, raftMenuKeyTarget(rows, 1, Key.M, 'M'.code))
        assertEquals(1, raftMenuKeyTarget(rows, 2, Key.M, 'm'.code), "wraps round")
        // From the search field, Home / End and letters edit the text.
        assertEquals(null, raftMenuKeyTarget(rows, -1, Key.MoveHome, 0))
        assertEquals(null, raftMenuKeyTarget(rows, -1, Key.M, 'm'.code))
    }
}
