package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadLazyWindowBeyondBoundsTest {
    @Test
    fun measuredCountIsAboutOneViewportOfRows() {
        // (viewportDp, averageRowDp) -> rows; device case: 2116px viewport, 1500-2260px rows at 2.625x.
        val cases =
            listOf(
                Triple(806f, 800f, 2), // tall markdown rows ~ one screen each
                Triple(806f, 2000f, 1), // a row taller than the viewport still keeps one
                Triple(806f, 300f, 3),
                Triple(806f, 120f, 7),
                Triple(806f, 48f, 8), // short rows hit the cap
                Triple(10_000f, 64f, 8)
            )
        cases.forEach { (viewport, row, expected) ->
            assertEquals(expected, threadMeasuredBeyondBoundsItemCount(viewport, row), "viewport=$viewport row=$row")
        }
    }

    @Test
    fun measuredCountIsZeroWithoutMeasurements() {
        listOf(0f to 100f, Float.NaN to 100f, 800f to 0f, 800f to Float.POSITIVE_INFINITY).forEach { (viewport, row) ->
            assertEquals(0, threadMeasuredBeyondBoundsItemCount(viewport, row), "viewport=$viewport row=$row")
        }
    }

    @Test
    fun consumerCountsUseMeasuredCountForTheListOnly() {
        assertEquals(
            ThreadLazyWindowConsumerCounts(
                listBeyondBoundsItems = 2,
                markdownPreparseOverscanItems = 8,
                diagnosticsBeyondBoundsItems = 2
            ),
            threadLazyWindowConsumerCounts(itemCount = 80, viewportHeightDp = 720f, measuredListBeyondBoundsItems = 2)
        )
        // Clamped to the items that exist.
        assertEquals(
            1,
            threadLazyWindowConsumerCounts(itemCount = 1, viewportHeightDp = 720f, measuredListBeyondBoundsItems = 5)
                .listBeyondBoundsItems
        )
        // Not measured yet (or no viewport): the representative-row count, as before.
        assertEquals(8, threadLazyWindowConsumerCounts(itemCount = 80, viewportHeightDp = 720f).listBeyondBoundsItems)
        assertEquals(
            4,
            threadLazyWindowConsumerCounts(itemCount = 80, viewportHeightDp = 0f, measuredListBeyondBoundsItems = 2)
                .listBeyondBoundsItems
        )
    }

    @Test
    fun rowHeightAverageAndSmoothing() {
        assertEquals(0f, threadAverageVisibleRowHeightPx(emptyList()))
        assertEquals(1000f, threadSmoothedRowHeightPx(previousPx = 0f, samplePx = 1000f))
        assertEquals(1500f, threadSmoothedRowHeightPx(previousPx = 1000f, samplePx = 2000f))
    }
}
