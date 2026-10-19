package build.raft.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftSafeArea
import build.raft.designsystem.components.RaftToastSurface
import build.raft.designsystem.components.raftCheckboxLook
import build.raft.designsystem.components.raftCounterAlarm
import build.raft.designsystem.components.raftFieldBoxLook
import build.raft.designsystem.components.raftLineHeightDp
import build.raft.designsystem.components.raftMenuKeyTarget
import build.raft.designsystem.components.raftMenuMaxSurfaceHeight
import build.raft.designsystem.components.raftMenuPosition
import build.raft.designsystem.components.raftNormalCdf
import build.raft.designsystem.components.raftPickerTriggerLook
import build.raft.designsystem.components.raftRadioLook
import build.raft.designsystem.components.raftRevealScrollTarget
import build.raft.designsystem.components.raftSelectTriggerLook
import build.raft.designsystem.components.raftSheetConstraints
import build.raft.designsystem.components.raftStickerItemLook
import build.raft.designsystem.components.raftSwitchLook
import build.raft.designsystem.components.raftToastLook
import build.raft.designsystem.components.raftToggleableState
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val light = RaftUiThemeColors.RaftLight
private val dark = RaftUiThemeColors.RaftDark

class RaftFieldLookTest {
    @Test
    fun aReadOnlyFieldIsMutedNotDisabled() {
        val look = raftFieldBoxLook(enabled = true, invalid = false, colors = light, readOnly = true)
        assertEquals(light.fillMuted, look.fill)
        assertEquals(light.lineHairline, look.border)
        assertEquals(light.foregroundMuted, look.text)
    }

    @Test
    fun theInkBorderIsAlwaysDrawnAndInvalidOnlyRecoloursIt() {
        val rest = raftFieldBoxLook(enabled = true, invalid = false, colors = light)
        assertEquals(light.lineStrong, rest.border)
        assertEquals(light.layerCard, rest.fill)
        val invalid = raftFieldBoxLook(enabled = true, invalid = true, colors = light)
        assertEquals(light.dangerStrong, invalid.border)
        assertEquals(rest.copy(border = light.dangerStrong), invalid)
    }

    @Test
    fun disabledWinsOverInvalid() {
        val look = raftFieldBoxLook(enabled = false, invalid = true, colors = dark)
        assertEquals(dark.lineHairline, look.border)
        assertEquals(dark.fillMuted, look.fill)
        assertEquals(dark.foregroundDisabled, look.text)
    }

    @Test
    fun theCounterTurnsRedPastTheLimitNotAtIt() {
        assertFalse(raftCounterAlarm(280, 280, invalid = false))
        assertTrue(raftCounterAlarm(281, 280, invalid = false))
        assertTrue(raftCounterAlarm(3, 280, invalid = true))
    }
}

class RaftValueSelectionTest {
    @Test
    fun aCheckedCheckboxIsAnInkBlockNeverYellow() {
        val look = raftCheckboxLook(checked = true, enabled = true, colors = light)
        assertEquals(light.foreground, look.fill)
        assertEquals(light.foreground, look.border)
        assertEquals(light.foregroundInverse, look.mark)
        listOf(light, dark).forEach { colors ->
            listOf(true, false).forEach { enabled ->
                assertNotEquals(RaftUiPalette.brand, raftCheckboxLook(true, enabled, colors).fill)
                assertNotEquals(RaftUiPalette.brand, raftRadioLook(true, enabled, colors).fill)
            }
        }
    }

    @Test
    fun uncheckedBoxesKeepTheirInkEdgeAndShowNoMark() {
        val box = raftCheckboxLook(checked = false, enabled = true, colors = light)
        assertEquals(light.lineStrong, box.border)
        assertEquals(0f, box.mark.alpha)
        assertEquals(0f, raftRadioLook(selected = false, enabled = true, colors = light).mark.alpha)
        assertEquals(light.foreground, raftRadioLook(selected = true, enabled = true, colors = light).mark)
    }

    @Test
    fun disabledChecksGreyOut() {
        val look = raftCheckboxLook(checked = true, enabled = false, colors = light)
        assertEquals(light.foregroundDisabled, look.fill)
        assertEquals(light.layerCard, look.mark)
        assertEquals(light.foregroundDisabled, raftRadioLook(selected = true, enabled = false, colors = light).mark)
    }

    @Test
    fun toggleableStates() {
        assertEquals(ToggleableState.Off, raftToggleableState(checked = false, indeterminate = false))
        assertEquals(ToggleableState.On, raftToggleableState(checked = true, indeterminate = false))
        assertEquals(ToggleableState.Indeterminate, raftToggleableState(checked = true, indeterminate = true))
    }
}

class RaftSwitchLookTest {
    @Test
    fun onIsABrandStickerWithTheDieCutEdgeInDark() {
        val on = raftSwitchLook(checked = true, enabled = true, colors = light)
        assertEquals(RaftUiPalette.brand, on.track)
        assertEquals(RaftUiPalette.stickerInk, on.trackEdge)
        assertEquals(RaftUiPalette.stickerInk, on.thumbRing)
        assertEquals(0f, on.dieCut.alpha)
        assertTrue(raftSwitchLook(checked = true, enabled = true, colors = dark).dieCut.alpha > 0f)
    }

    @Test
    fun offIsAMutedTrackWithAnInkEdge() {
        val off = raftSwitchLook(checked = false, enabled = true, colors = light)
        assertEquals(light.fillMuted, off.track)
        assertEquals(light.lineStrong, off.trackEdge)
        assertEquals(0f, off.dieCut.alpha)
    }

    @Test
    fun aDisabledSwitchIsNoLongerASticker() {
        val look = raftSwitchLook(checked = true, enabled = false, colors = dark)
        assertEquals(dark.fillMuted, look.track)
        assertEquals(dark.foregroundDisabled, look.trackEdge)
        assertEquals(0f, look.dieCut.alpha)
    }
}

class RaftNavigationItemTest {
    @Test
    fun theSelectedTabOrSegmentIsTheActiveSticker() {
        val active = raftStickerItemLook(active = true, enabled = true, colors = dark)
        assertEquals(RaftUiPalette.brand, active.fill)
        assertEquals(RaftUiPalette.stickerInk, active.edge)
        assertEquals(RaftUiPalette.stickerInk, active.ink)
        assertTrue(active.dieCut.alpha > 0f)
    }

    @Test
    fun restingItemsAreMutedGhostsAndDisabledOnesTextOnly() {
        val rest = raftStickerItemLook(active = false, enabled = true, colors = light)
        assertEquals(Color.Transparent, rest.fill)
        assertEquals(Color.Transparent, rest.edge)
        assertEquals(light.foregroundMuted, rest.ink)
        val disabled = raftStickerItemLook(active = true, enabled = false, colors = light)
        assertEquals(Color.Transparent, disabled.fill)
        assertEquals(light.foregroundDisabled, disabled.ink)
    }

    @Test
    fun aChoiceRowLineHeightResolvesEveryUnit() {
        val density = Density(2f, fontScale = 1.5f)
        assertEquals(30f, raftLineHeightDp(20.sp, 15.sp, density).value, 0.01f, "sp, font-scaled")
        assertEquals(27f, raftLineHeightDp(1.2.em, 15.sp, density).value, 0.01f, "em of an sp font size")
        assertEquals(22.5f, raftLineHeightDp(TextUnit.Unspecified, 15.sp, density).value, 0.01f, "no line height: the font size")
        assertEquals(20.dp, raftLineHeightDp(1.2.em, 1.em, density), "unresolvable: the control's own size")
        assertEquals(20.dp, raftLineHeightDp(TextUnit.Unspecified, TextUnit.Unspecified, density))
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

class RaftPickerLookTest {
    @Test
    fun anAppliedFilterIsInfoSoftWithNoBorder() {
        val applied = raftPickerTriggerLook(hasValue = true, invalid = false, enabled = true, colors = light)
        assertEquals(light.infoSoft, applied.fill)
        assertEquals(0f, applied.ring.alpha)
        assertEquals(light.foreground, applied.ink)
        val rest = raftPickerTriggerLook(hasValue = false, invalid = false, enabled = true, colors = light)
        assertEquals(light.lineSeam, rest.ring)
        assertEquals(light.foregroundMuted, rest.ink)
    }

    @Test
    fun invalidPickersAndSelectsGoDangerStrong() {
        assertEquals(light.dangerStrong, raftPickerTriggerLook(hasValue = true, invalid = true, enabled = true, colors = light).ring)
        assertEquals(light.dangerStrong, raftSelectTriggerLook(enabled = true, invalid = true, hasValue = false, colors = light).border)
        assertEquals(light.foregroundHint, raftSelectTriggerLook(enabled = true, invalid = false, hasValue = false, colors = light).ink)
        assertEquals(light.lineHairline, raftSelectTriggerLook(enabled = false, invalid = true, hasValue = true, colors = light).border)
    }
}

class RaftOverlayTest {
    private val insets = RaftPopupMargins(horizontal = 16, top = 8, bottom = 24)
    private val window = IntSize(400, 800)

    /** A popup [w] x [h] at its surface, plus the shadow room. */
    private fun popup(w: Int, h: Int) = IntSize(w + 32, h + 32)

    @Test
    fun aMenuOpensUnderItsAnchorStartAligned() {
        val anchor = IntRect(20, 100, 140, 132)
        val at = raftMenuPosition(anchor, window, popup(240, 200), gap = 6, windowMargin = 8, insets = insets)
        // Surface at (20, 138); the popup window starts its insets earlier.
        assertEquals(IntOffset(20 - 16, 138 - 8), at)
    }

    @Test
    fun aMenuStaysInsideTheWindowAndFlipsAboveWhenThereIsMoreRoom() {
        val anchor = IntRect(300, 700, 380, 732)
        val at = raftMenuPosition(anchor, window, popup(240, 200), gap = 6, windowMargin = 8, insets = insets)
        // Pushed left to end 8px inside the window; above the anchor: 700 - 6 - 200.
        assertEquals(IntOffset(400 - 8 - 240 - 16, 494 - 8), at)
    }

    @Test
    fun rightToLeftAlignsTheEndEdges() {
        val anchor = IntRect(260, 100, 380, 132)
        val at = raftMenuPosition(anchor, window, popup(240, 200), gap = 6, windowMargin = 8, insets = insets, rtl = true)
        assertEquals(IntOffset(380 - 240 - 16, 138 - 8), at)
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
    fun aSheetCapNeverGoesBelowTheIncomingMinimum() {
        assertEquals(656, raftSheetConstraints(Constraints(minHeight = 0, maxHeight = 800)).maxHeight)
        // A caller's heightIn(min = 700) on an 800px window: the cap gives way, no crash.
        assertEquals(700, raftSheetConstraints(Constraints(minHeight = 700, maxHeight = 800)).maxHeight)
        assertFalse(raftSheetConstraints(Constraints(minHeight = 0)).hasBoundedHeight)
    }

    @Test
    fun aMenuKeepsClearOfTheKeyboardAndTheBars() {
        // The keyboard covers the bottom 300px: under the anchor there is no room, so the
        // menu flips above it rather than going under the keyboard.
        val anchor = IntRect(20, 400, 140, 432)
        val keyboard = RaftSafeArea(top = 40, bottom = 300)
        val at = raftMenuPosition(anchor, window, popup(240, 200), gap = 6, windowMargin = 8, insets = insets, safeArea = keyboard)
        assertEquals(IntOffset(20 - 16, 400 - 6 - 200 - 8), at)
        // Too tall for either side: clamped below the status bar.
        val tall = raftMenuPosition(anchor, window, popup(240, 500), gap = 6, windowMargin = 8, insets = insets, safeArea = keyboard)
        assertEquals(40 + 8 - 8, tall.y)
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

    @Test
    fun theFloatShadowFollowsTheGaussianEdge() {
        assertTrue(abs(raftNormalCdf(0f) - 0.5f) < 1e-4f)
        assertTrue(abs(raftNormalCdf(1f) - 0.8413f) < 1e-3f)
        assertTrue(abs(raftNormalCdf(-2f) - 0.0228f) < 1e-3f)
    }

    @Test
    fun toastsAreNeutral() {
        val hud = raftToastLook(RaftToastSurface.Hud, light)
        assertEquals(light.layerHud, hud.fill)
        assertEquals(light.foregroundOnHud, hud.ink)
        assertEquals(light.lineSeam, raftToastLook(RaftToastSurface.Popover, light).ring)
    }
}
