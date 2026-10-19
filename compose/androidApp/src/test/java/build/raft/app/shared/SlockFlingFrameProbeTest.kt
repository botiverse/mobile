package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SlockFlingFrameProbeTest {
    private fun span(top: Int, bottom: Int) = intArrayOf(top, bottom)

    @Test
    fun fullyCoveredViewportHasNoGap() {
        val coverage = flingFrameCoverage(listOf(span(0, 600), span(600, 1200)), rows = 2, top = 100, bottom = 1100)

        assertEquals(1000, coverage.covered)
        assertEquals(0, coverage.largestGap)
        assertEquals(-1, coverage.largestGapAt)
        assertEquals(2, coverage.rowsInView)
    }

    @Test
    fun gapBetweenRowsIsMeasuredFromViewportTop() {
        // Rows cover 100..400 and 700..1100 of a 100..1100 viewport: a 300px hole starting 300px down.
        val coverage = flingFrameCoverage(listOf(span(700, 1100), span(0, 400)), rows = 2, top = 100, bottom = 1100)

        assertEquals(700, coverage.covered)
        assertEquals(300, coverage.largestGap)
        assertEquals(300, coverage.largestGapAt)
    }

    @Test
    fun viewportPastTheLastRowLeavesATrailingGap() {
        // Overscrolled past the end: only the first 200px of the viewport still has a row.
        val coverage = flingFrameCoverage(listOf(span(0, 500)), rows = 1, top = 300, bottom = 1300)

        assertEquals(200, coverage.covered)
        assertEquals(800, coverage.largestGap)
        assertEquals(200, coverage.largestGapAt)
    }

    @Test
    fun overlappingRowsAreNotDoubleCounted() {
        val coverage = flingFrameCoverage(listOf(span(0, 700), span(500, 1000)), rows = 2, top = 0, bottom = 1000)

        assertEquals(1000, coverage.covered)
        assertEquals(0, coverage.largestGap)
    }

    @Test
    fun noRowsMeansTheWholeViewportIsAGap() {
        val coverage = flingFrameCoverage(emptyList(), rows = 0, top = 0, bottom = 900)

        assertEquals(0, coverage.covered)
        assertEquals(900, coverage.largestGap)
        assertEquals(0, coverage.largestGapAt)
        assertEquals(0, coverage.rowsInView)
    }
}
