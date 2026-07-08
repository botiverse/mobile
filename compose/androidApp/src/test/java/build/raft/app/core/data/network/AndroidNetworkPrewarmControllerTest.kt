package build.raft.app.core.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNetworkPrewarmControllerTest {
    @Test
    fun prewarmStartsWhenThereIsNoPreviousAttempt() {
        assertTrue(
            AndroidNetworkPrewarmController.shouldStartPrewarm(
                nowMillis = 1_000L,
                lastAttemptAtMillis = null,
                minIntervalMillis = 10_000L
            )
        )
    }

    @Test
    fun prewarmIsRateLimitedUntilIntervalElapses() {
        assertFalse(
            AndroidNetworkPrewarmController.shouldStartPrewarm(
                nowMillis = 10_999L,
                lastAttemptAtMillis = 1_000L,
                minIntervalMillis = 10_000L
            )
        )
        assertTrue(
            AndroidNetworkPrewarmController.shouldStartPrewarm(
                nowMillis = 11_000L,
                lastAttemptAtMillis = 1_000L,
                minIntervalMillis = 10_000L
            )
        )
    }
}
