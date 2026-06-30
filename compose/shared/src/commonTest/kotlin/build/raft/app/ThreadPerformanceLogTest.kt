package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadPerformanceLogTest {
    @Test
    fun threadPerformanceLogsOnlySlowSamplesByDefault() {
        assertFalse(shouldLogThreadPerformance(0))
        assertFalse(shouldLogThreadPerformance(3))
        assertTrue(shouldLogThreadPerformance(4))
    }
}
