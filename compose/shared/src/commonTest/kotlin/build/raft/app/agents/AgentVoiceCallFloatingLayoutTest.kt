package build.raft.app.agents

import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentVoiceCallFloatingLayoutTest {
    private fun assertNear(
        expected: Float,
        actual: Float,
        tolerance: Float = 0.01f,
        message: String? = null,
    ) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            message ?: "Expected $expected ± $tolerance but was $actual",
        )
    }

    @Test
    fun defaultOffsetPlacesCardBottomCenterOnCompactAndBottomEndOnWide() {
        val compactPortrait =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 390f,
                hostHeightPx = 844f,
                cardWidthPx = 360f,
                cardHeightPx = 160f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
                wide = false,
                isLtr = true,
            )
        val compactDefault = defaultVoiceCallFloatingOffset(compactPortrait)
        assertNear(15f, compactDefault.xPx)
        assertNear(634f, compactDefault.yPx)

        val narrowPhone =
            compactPortrait.copy(
                hostWidthPx = 360f,
                cardWidthPx = 336f,
            )
        val narrowDefault = defaultVoiceCallFloatingOffset(narrowPhone)
        assertNear(12f, narrowDefault.xPx)
        assertNear(634f, narrowDefault.yPx)

        val wideLtr =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 1024f,
                hostHeightPx = 768f,
                cardWidthPx = 360f,
                cardHeightPx = 160f,
                topSafeAreaPx = 24f,
                bottomSafeAreaPx = 20f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
                wide = true,
                isLtr = true,
            )
        val wideLtrDefault = defaultVoiceCallFloatingOffset(wideLtr)
        assertNear(652f, wideLtrDefault.xPx)
        assertNear(572f, wideLtrDefault.yPx)

        val wideRtlDefault = defaultVoiceCallFloatingOffset(wideLtr.copy(isLtr = false))
        assertNear(12f, wideRtlDefault.xPx)
        assertNear(572f, wideRtlDefault.yPx)
    }

    @Test
    fun clampEnforcesSafeAreaVerticalBoundsOnPortraitPhoneDrag() {
        val portraitBounds =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 360f,
                hostHeightPx = 800f,
                cardWidthPx = 336f,
                cardHeightPx = 150f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
                wide = false,
            )

        val draggedAboveStatusBar =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = -80f, yPx = -200f),
                portraitBounds,
            )
        assertNear(12f, draggedAboveStatusBar.xPx)
        assertNear(56f, draggedAboveStatusBar.yPx)

        val draggedToMiddle =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = 12f, yPx = 260f),
                portraitBounds,
            )
        assertNear(12f, draggedToMiddle.xPx)
        assertNear(260f, draggedToMiddle.yPx)

        val draggedBelowNavBar =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = 400f, yPx = 999f),
                portraitBounds,
            )
        assertNear(12f, draggedBelowNavBar.xPx)
        assertNear(600f, draggedBelowNavBar.yPx)
    }

    @Test
    fun clampSupportsFullTwoDimensionalDragOnWideViewportAndNarrowCard() {
        val wideBounds =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 1024f,
                hostHeightPx = 768f,
                cardWidthPx = 360f,
                cardHeightPx = 160f,
                topSafeAreaPx = 24f,
                bottomSafeAreaPx = 16f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
                wide = true,
            )

        val freeMid =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = 210f, yPx = 180f),
                wideBounds,
            )
        assertNear(210f, freeMid.xPx)
        assertNear(180f, freeMid.yPx)

        val topLeft =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = -40f, yPx = -10f),
                wideBounds,
            )
        assertNear(12f, topLeft.xPx)
        assertNear(36f, topLeft.yPx)

        val bottomRight =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = 950f, yPx = 800f),
                wideBounds,
            )
        assertNear(652f, bottomRight.xPx)
        assertNear(576f, bottomRight.yPx)

        // A narrower minimized pill on a phone viewport also gets full 2D freedom.
        val narrowPillOnPhone =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 390f,
                hostHeightPx = 844f,
                cardWidthPx = 140f,
                cardHeightPx = 48f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
                wide = false,
            )
        val pillDraggedRight =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = 220f, yPx = 120f),
                narrowPillOnPhone,
            )
        assertNear(220f, pillDraggedRight.xPx)
        assertNear(120f, pillDraggedRight.yPx)
        val pillClampedRight =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(xPx = 350f, yPx = 120f),
                narrowPillOnPhone,
            )
        assertNear(238f, pillClampedRight.xPx)
    }

    @Test
    fun clampReclampsWhenKeyboardOpensAndKeepsTopPlacementUndisturbed() {
        val noKeyboard =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 390f,
                hostHeightPx = 844f,
                cardWidthPx = 360f,
                cardHeightPx = 160f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 34f,
                imeBottomPx = 0f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
            )
        val withKeyboard = noKeyboard.copy(imeBottomPx = 320f)

        // Card dragged near the top stays put when the keyboard opens.
        val topSaved = AgentVoiceCallFloatingOffset(xPx = 15f, yPx = 80f)
        val topWithKeyboard = clampVoiceCallFloatingOffset(topSaved, withKeyboard)
        assertNear(80f, topWithKeyboard.yPx)

        // Card dragged near the bottom lifts above the keyboard and restores cleanly once closed.
        val lowerSaved = AgentVoiceCallFloatingOffset(xPx = 15f, yPx = 560f)
        val liftedForKeyboard = clampVoiceCallFloatingOffset(lowerSaved, withKeyboard)
        assertNear(348f, liftedForKeyboard.yPx)
        val restoredAfterKeyboard = clampVoiceCallFloatingOffset(lowerSaved, noKeyboard)
        assertNear(560f, restoredAfterKeyboard.yPx)

        // Compressed viewport where keyboard + card exceed safe height pins cleanly below status bar.
        val compressedWithTallKeyboard =
            noKeyboard.copy(
                hostHeightPx = 580f,
                cardHeightPx = 220f,
                imeBottomPx = 340f,
            )
        val compressedPos = clampVoiceCallFloatingOffset(lowerSaved, compressedWithTallKeyboard)
        assertNear(56f, compressedPos.yPx)
    }

    @Test
    fun clampReclampsAcrossOrientationChangeHorizontalNotchesAndTinySplitWindow() {
        val portraitSaved = AgentVoiceCallFloatingOffset(xPx = 15f, yPx = 540f)
        val landscapeWithNotch =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 844f,
                hostHeightPx = 390f,
                cardWidthPx = 360f,
                cardHeightPx = 160f,
                leftSafeAreaPx = 44f,
                rightSafeAreaPx = 44f,
                topSafeAreaPx = 0f,
                bottomSafeAreaPx = 21f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
            )

        val reclampedLandscape = clampVoiceCallFloatingOffset(portraitSaved, landscapeWithNotch)
        assertNear(56f, reclampedLandscape.xPx)
        assertNear(193f, reclampedLandscape.yPx)

        val tinySplitWindow =
            AgentVoiceCallFloatingBounds(
                hostWidthPx = 320f,
                hostHeightPx = 200f,
                cardWidthPx = 360f,
                cardHeightPx = 180f,
                topSafeAreaPx = 44f,
                bottomSafeAreaPx = 20f,
                horizontalInsetPx = 12f,
                topGapPx = 12f,
                bottomGapPx = 16f,
            )
        val tinyClamped = clampVoiceCallFloatingOffset(AgentVoiceCallFloatingOffset(500f, 500f), tinySplitWindow)
        assertNear(0f, tinyClamped.xPx)
        assertNear(20f, tinyClamped.yPx)

        val nonFiniteClamped =
            clampVoiceCallFloatingOffset(
                AgentVoiceCallFloatingOffset(Float.NaN, Float.POSITIVE_INFINITY),
                landscapeWithNotch,
            )
        val expectedDefault = defaultVoiceCallFloatingOffset(landscapeWithNotch)
        assertNear(expectedDefault.xPx, nonFiniteClamped.xPx)
        assertNear(expectedDefault.yPx, nonFiniteClamped.yPx)
    }

    @Test
    fun dragStepTracksLinearlyInBoundsRubberBandsFreeEdgesAndLocksNarrowAxis() {
        // Locked narrow-phone X axis (span < maxOverscroll) produces zero horizontal overscroll.
        val lockedStep =
            stepVoiceCallFloatingDragAxis(
                positionPx = 15f,
                rawOverscrollPx = 0f,
                deltaPx = 40f,
                minPx = 12f,
                maxPx = 18f,
                maxOverscrollPx = 20f,
            )
        assertNear(18f, lockedStep.positionPx)
        assertNear(0f, lockedStep.rawOverscrollPx)

        // Free vertical axis tracks 1:1 inside bounds.
        val freeStep =
            stepVoiceCallFloatingDragAxis(
                positionPx = 400f,
                rawOverscrollPx = 0f,
                deltaPx = -120f,
                minPx = 56f,
                maxPx = 634f,
                maxOverscrollPx = 20f,
            )
        assertNear(280f, freeStep.positionPx)
        assertNear(0f, freeStep.rawOverscrollPx)

        // Pulling past top safe edge clamps position to minPx and accumulates bounded overscroll.
        val overscrollStep =
            stepVoiceCallFloatingDragAxis(
                positionPx = 70f,
                rawOverscrollPx = 0f,
                deltaPx = -100f,
                minPx = 56f,
                maxPx = 634f,
                maxOverscrollPx = 20f,
            )
        assertNear(56f, overscrollStep.positionPx)
        assertTrue(overscrollStep.rawOverscrollPx < 0f)
        val damped = dampVoiceCallFloatingOverscrollPx(overscrollStep.rawOverscrollPx, 20f)
        assertTrue(damped < 0f && damped > -20f)

        // Reversing direction immediately unwinds overscroll and moves back into bounds.
        val reverseStep =
            stepVoiceCallFloatingDragAxis(
                positionPx = overscrollStep.positionPx,
                rawOverscrollPx = overscrollStep.rawOverscrollPx,
                deltaPx = 90f,
                minPx = 56f,
                maxPx = 634f,
                maxOverscrollPx = 20f,
            )
        assertTrue(reverseStep.positionPx > 56f)
        assertNear(0f, reverseStep.rawOverscrollPx)

        assertNear(0f, dampVoiceCallFloatingOverscrollPx(0f, 20f))
        assertNear(0f, dampVoiceCallFloatingOverscrollPx(Float.NaN, 20f))
    }

    @Test
    fun positionHolderPersistsActiveSessionRejectsNonFiniteAndClearsOnDismiss() {
        val holder = AgentVoiceCallFloatingPositionHolder()
        val density = Density(density = 2f)

        assertNull(holder.currentOffsetPx("srv-1:agent-lagoon", density))

        holder.save(sessionKey = "srv-1:agent-lagoon", xDp = 15f, yDp = 84f)
        val saved = assertNotNull(holder.currentOffsetPx("srv-1:agent-lagoon", density))
        assertNear(30f, saved.xPx)
        assertNear(168f, saved.yPx)

        // Another call session does not inherit the prior session's position.
        assertNull(holder.currentOffsetPx("srv-1:agent-other", density))

        // Non-finite writes are ignored and do not corrupt the saved position.
        holder.save(sessionKey = "srv-1:agent-lagoon", xDp = Float.NaN, yDp = 10f)
        assertEquals(84f, holder.position.value?.yDp)

        holder.clear()
        assertNull(holder.position.value)
        assertNull(holder.currentOffsetPx("srv-1:agent-lagoon", density))
    }
}
