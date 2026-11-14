package build.raft.realtime.socket

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RaftReconnectPolicyTest {
    @Test
    fun withoutJitterTheDelayDoublesFromOneSecondUpToThirty() {
        val policy = RaftReconnectPolicy(jitter = 0.0)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map { policy.delayMillis(it, Random(1)) },
        )
        assertEquals(30_000L, policy.delayMillis(Int.MAX_VALUE, Random(1)))
    }

    @Test
    fun jitterStaysWithinHalfOfTheBaseAndInsideTheBounds() {
        val policy = RaftReconnectPolicy()
        val random = Random(42)
        repeat(500) { index ->
            val failures = index % 8
            val base = minOf(30_000L, 1_000L shl failures)
            val delay = policy.delayMillis(failures, random)
            assertTrue(delay >= maxOf(500L, base / 2) && delay <= minOf(30_000L, base * 3 / 2), "failures=$failures delay=$delay")
        }
        val spread = (0 until 200).map { policy.delayMillis(3, random) }.toSet()
        assertTrue(spread.size > 50, "jitter must actually vary the delay, got ${spread.size} values")
    }

    @Test
    fun invalidPoliciesAreRejected() {
        assertFailsWith<IllegalArgumentException> { RaftReconnectPolicy(initialDelayMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RaftReconnectPolicy(initialDelayMillis = 10, maxDelayMillis = 5) }
        assertFailsWith<IllegalArgumentException> { RaftReconnectPolicy(jitter = 1.5) }
    }
}
