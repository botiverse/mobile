package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabPagerSwipeLandingTest {
    @Test
    fun swipeToAnotherPageReportsThatPage() {
        assertEquals(1, tabPagerSwipeLanding(startPage = 0, endPage = 1, pageCount = 3))
        assertEquals(0, tabPagerSwipeLanding(startPage = 2, endPage = 0, pageCount = 3))
    }

    @Test
    fun springBackToTheStartPageReportsNothing() {
        assertNull(tabPagerSwipeLanding(startPage = 1, endPage = 1, pageCount = 3))
    }

    @Test
    fun scrollNotDrivenByTheUserReportsNothing() {
        assertNull(tabPagerSwipeLanding(startPage = null, endPage = 2, pageCount = 3))
    }

    @Test
    fun outOfRangeLandingReportsNothing() {
        assertNull(tabPagerSwipeLanding(startPage = 0, endPage = 3, pageCount = 3))
        assertNull(tabPagerSwipeLanding(startPage = 1, endPage = -1, pageCount = 3))
    }
}
