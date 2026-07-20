package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadPerformanceGenerationCounterTest {
    @Test
    fun layoutReportsOncePerReconcileUnlessRootSizeChanges() {
        val counter = ThreadPerformanceGenerationCounter()

        assertEquals(1L, counter.nextReconcile())
        assertEquals(ThreadLayoutGeneration(1L, 1L), counter.nextLayout(1080, 1920))
        assertNull(counter.nextLayout(1080, 1920))
        assertEquals(ThreadLayoutGeneration(2L, 1L), counter.nextLayout(1080, 1800))

        assertEquals(2L, counter.nextReconcile())
        assertEquals(ThreadLayoutGeneration(3L, 2L), counter.nextLayout(1080, 1800))
    }
}
