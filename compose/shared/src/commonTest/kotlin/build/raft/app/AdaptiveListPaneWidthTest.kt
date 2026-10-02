package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

/** task #37: user-resizable list column of the wide list + detail split. */
class AdaptiveListPaneWidthTest {
    @Test fun savedWidthIsUsedOnlyWhenValid() {
        assertEquals(SLOCK_LIST_PANE_DEFAULT_DP, slockListPanePreferredWidthDp(null))
        assertEquals(SLOCK_LIST_PANE_DEFAULT_DP, slockListPanePreferredWidthDp("wide"))
        assertEquals(SLOCK_LIST_PANE_DEFAULT_DP, slockListPanePreferredWidthDp("NaN"))
        assertEquals(SLOCK_LIST_PANE_DEFAULT_DP, slockListPanePreferredWidthDp("100"))
        assertEquals(SLOCK_LIST_PANE_DEFAULT_DP, slockListPanePreferredWidthDp("9000"))
        assertEquals(420f, slockListPanePreferredWidthDp("420"))
        // Round trip through what a finished drag saves.
        assertEquals(433f, slockListPanePreferredWidthDp(slockListPaneStoredWidth(433.7f)))
    }

    @Test fun defaultKeepsTodaysListWidthOnEveryDualPaneWindow() {
        // Narrowest dual-pane row (840dp window minus the rail) still fits the default 360dp list.
        assertEquals(360f, slockListPaneWidthDp(SLOCK_LIST_PANE_DEFAULT_DP, 768f))
        assertEquals(360f, slockListPaneWidthDp(SLOCK_LIST_PANE_DEFAULT_DP, 1600f))
        assertEquals(360f, slockListPaneWidthDp(SLOCK_LIST_PANE_DEFAULT_DP, null))
    }

    @Test fun renderedWidthStaysWithinLimitsAndLeavesTheDetailItsMinimum() {
        assertEquals(SLOCK_LIST_PANE_MAX_DP, slockListPaneWidthDp(900f, 2000f))
        assertEquals(SLOCK_LIST_PANE_MIN_DP, slockListPaneWidthDp(10f, 2000f))
        // A 500dp preference in a 800dp row: detail keeps 400dp, list gets 399dp.
        val clamped = slockListPaneWidthDp(500f, 800f)
        assertEquals(800f - SLOCK_LIST_PANE_DIVIDER_DP - SLOCK_LIST_PANE_DETAIL_MIN_DP, clamped)
        assertTrue(800f - SLOCK_LIST_PANE_DIVIDER_DP - clamped >= SLOCK_LIST_PANE_DETAIL_MIN_DP)
        // A row too small for both minimums keeps the list minimum rather than collapsing it.
        assertEquals(SLOCK_LIST_PANE_MIN_DP, slockListPaneWidthDp(360f, 500f))
    }

    @Test fun windowClampDoesNotChangeThePreference() {
        val preferred = 500f
        // Shrunk window, then grown back: the user's width returns.
        assertTrue(slockListPaneWidthDp(preferred, 800f) < preferred)
        assertEquals(preferred, slockListPaneWidthDp(preferred, 1400f))
    }

    @Test fun dragStartsFromTheRenderedWidthAndIsClamped() {
        assertEquals(400f, slockListPaneDraggedWidthDp(360f, 40f, 1400f))
        assertEquals(SLOCK_LIST_PANE_MIN_DP, slockListPaneDraggedWidthDp(360f, -500f, 1400f))
        assertEquals(SLOCK_LIST_PANE_MAX_DP, slockListPaneDraggedWidthDp(360f, 500f, 1400f))
        // In a clamped window, a drag from the rendered 399dp cannot push the detail under 400dp.
        assertEquals(399f, slockListPaneDraggedWidthDp(399f, 50f, 800f))
    }

    @Test fun wideningTheListCanTakeTheRoomTheSideColumnNeeds() {
        // F4 needs 841dp of detail; a 1440dp row with a 520dp list leaves 919dp, a row of 1300dp
        // leaves 779dp, so the side thread folds once the drag ends (SettingsHomePage).
        val needed = SLOCK_SIDE_THREAD_MAIN_MIN_DP + SLOCK_SIDE_THREAD_DIVIDER_DP + SLOCK_SIDE_THREAD_COLUMN_DP
        fun detail(row: Float) = row - SLOCK_LIST_PANE_DIVIDER_DP - slockListPaneWidthDp(SLOCK_LIST_PANE_MAX_DP, row)
        assertTrue(detail(1440f) >= needed)
        assertTrue(detail(1300f) < needed)
    }
}
