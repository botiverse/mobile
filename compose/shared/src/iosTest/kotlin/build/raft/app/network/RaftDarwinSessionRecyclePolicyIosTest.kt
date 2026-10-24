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

    @Test
    fun anAnswerInBetweenResetsTheCount() {
        val policy = RaftDarwinSessionRecyclePolicy()
        assertNull(policy.record(0, timedOut = true, nowMillis = 0))
        assertNull(policy.record(0, timedOut = false, nowMillis = 10))
        assertNull(policy.record(0, timedOut = true, nowMillis = 20))
        assertEquals(0L, policy.generation)
    }

    @Test
    fun timeoutsDrainingFromTheReplacedSessionDoNotCountAgainstTheNewOne() {
        val policy = RaftDarwinSessionRecyclePolicy()
        policy.record(0, timedOut = true, nowMillis = 0)
        assertNotNull(policy.record(0, timedOut = true, nowMillis = 1))
        // The rest of the 12:06:50 burst: five more Visible timeouts from the old session.
        repeat(5) { assertNull(policy.record(callGeneration = 0, timedOut = true, nowMillis = 2L + it)) }
        assertEquals(1L, policy.generation)
        // The new session answers.
        assertNull(policy.record(callGeneration = 1, timedOut = false, nowMillis = 30_000))
        assertEquals(1L, policy.generation)
    }

    @Test
    fun aNetworkDownForEveryoneDoesNotChurnSessions() {
        val policy = RaftDarwinSessionRecyclePolicy(timeoutsToRecycle = 2, minIntervalMillis = 10_000)
        policy.record(0, true, 0)
        assertNotNull(policy.record(0, true, 0))
        policy.record(1, true, 4_000)
        assertNull(policy.record(1, true, 5_000), "within the interval")
        assertEquals(1L, policy.generation)
        val later = assertNotNull(policy.record(1, true, 10_000))
        assertEquals(3, later.consecutiveTimeouts)
        assertEquals(2L, policy.generation)
    }
}
