package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Hands 43837e84: when a lane's NSURLSession is replaced after its reused connection stops answering. */
class RaftDarwinSessionRecyclePolicyIosTest {
    @Test
    fun oneTimeoutIsASlowEndpointTwoInARowReplaceTheSession() {
        val policy = RaftDarwinSessionRecyclePolicy()
        assertNull(policy.record(callGeneration = 0, timedOut = true, nowMillis = 1_000))
        val recycle = assertNotNull(policy.record(callGeneration = 0, timedOut = true, nowMillis = 1_100))
        assertEquals("consecutive_timeouts", recycle.reason)
        assertEquals(2, recycle.consecutiveTimeouts)
        assertEquals(1L, recycle.generation)
        assertEquals(1L, policy.generation)
    }
}
