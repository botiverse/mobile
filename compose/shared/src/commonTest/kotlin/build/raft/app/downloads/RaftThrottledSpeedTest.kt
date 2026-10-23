package build.raft.app.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTime::class)
class RaftThrottledSpeedTest {
    private val time = TestTimeSource()
    private val smoother = RaftSpeedSmoother(1000L, time)

    @Test
    fun firstSampleShowsImmediately() {
        assertEquals(100L, smoother.update(100L))
    }

    @Test
    fun valueChangesAtMostOncePerSecond() {
        assertEquals(100L, smoother.update(100L))
        repeat(9) {
            time.plusAssign(100.milliseconds)
            assertEquals(100L, smoother.update(900L + it), "no change inside the second")
        }
        time.plusAssign(100.milliseconds)
        assertEquals(904L, smoother.update(900L + 9))
        time.plusAssign(500.milliseconds)
        assertEquals(904L, smoother.update(5L))
    }

    @Test
    fun publishesAverageOfTheWindow() {
        smoother.update(10L)
        time.plusAssign(400.milliseconds)
        smoother.update(200L)
        time.plusAssign(400.milliseconds)
        smoother.update(400L)
        time.plusAssign(400.milliseconds)
        assertEquals(400L, smoother.update(600L))
    }

    @Test
    fun clearsImmediatelyOnNullOrZero() {
        smoother.update(100L)
        time.plusAssign(100.milliseconds)
        assertNull(smoother.update(null))
        assertEquals(50L, smoother.update(50L), "restarts fresh after a clear")
        time.plusAssign(10.milliseconds)
        assertNull(smoother.update(0L))
    }
}
