package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #185 / PR #3147: pins which geometry the message long-press menu is hosted by.
 *
 * Why this file exists: the condition it tests (`adaptiveDetailPage && (!measured || x > 0f)`) has
 * already regressed once — single-column phone pages were being routed to the shell host, which
 * emptied the reaction emoji row in every layout. That regression was caught by a person looking at
 * a device, not by a test. If someone later "simplifies" the expression back to plain `x > 0f`, the
 * consequence is a menu that clips intermittently rather than always, and *every other test in this
 * repo stays green*.
 *
 * Three cells, matching the criteria agreed in review:
 *
 * | unmeasured | x     | host | why                                                        |
 * |------------|-------|------|------------------------------------------------------------|
 * | true       | any   | yes  | unmeasured is indistinguishable from x = 0; take the safe side |
 * | false      | 0     | no   | full-width page: the in-page menu fits                     |
 * | false      | > 0   | yes  | narrow pane: the in-page modal would clip                  |
 */
class ThreadMenuShellHostPredicateTest {

    @Test
    fun anUnmeasuredAdaptivePageUsesTheShellHost() {
        // The cell the `measured` flag exists for. origin defaults to zero and is only filled in by
        // the first layout pass, so before that an adaptive page looks identical to a full-width one.
        // Routing it in-page would clip the menu for exactly one frame -- intermittent, and therefore
        // harder to diagnose than the stable bug being fixed.
        assertTrue(
            threadMenuUsesShellHost(adaptiveDetailPage = true, originMeasured = false, windowOriginX = 0f),
            "unmeasured must take the safe branch: the window host renders whole at any width"
        )
        // This second assertion is NOT what catches the regression -- with the guard removed both
        // versions return true here, because x > 0 already routes to the host on its own. It is kept
        // as a contract statement ("the value is ignored until measured"), not as the safety net.
        // The load-bearing cell is the x = 0 assertion above.
        assertTrue(
            threadMenuUsesShellHost(adaptiveDetailPage = true, originMeasured = false, windowOriginX = 312f),
            "the measured value must be ignored until `measured` is true"
        )
    }

    @Test
    fun aMeasuredFullWidthPageKeepsTheInPageMenu() {
        // x = 0 means the page starts at the window's left edge, so it is full-width and its own
        // modal can draw the entire menu. This is the case that regressed: routing it to the host
        // emptied the emoji row in single-column layouts.
        assertFalse(
            threadMenuUsesShellHost(adaptiveDetailPage = true, originMeasured = true, windowOriginX = 0f),
            "a full-width page must keep its in-page menu; hosting it there is the known regression"
        )
    }

    @Test
    fun aMeasuredOffsetPageUsesTheShellHost() {
        // x > 0 means the page sits inside a narrower pane, whose modal surface clips the menu.
        assertTrue(
            threadMenuUsesShellHost(adaptiveDetailPage = true, originMeasured = true, windowOriginX = 161f),
            "a page inside a narrower pane must use the host, or its menu is clipped"
        )
    }

    @Test
    fun aPageOutsideAnAdaptiveLayoutIsNeverHosted() {
        // The adaptive check is not redundant with the origin check: a page can be offset for
        // reasons unrelated to the adaptive layout, and only adaptive pages have the clipping
        // problem this predicate addresses.
        assertFalse(
            threadMenuUsesShellHost(adaptiveDetailPage = false, originMeasured = true, windowOriginX = 400f)
        )
        assertFalse(
            threadMenuUsesShellHost(adaptiveDetailPage = false, originMeasured = false, windowOriginX = 0f)
        )
    }
}
