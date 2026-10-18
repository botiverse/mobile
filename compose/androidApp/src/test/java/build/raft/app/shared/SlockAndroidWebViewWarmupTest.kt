package build.raft.app.shared

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * task #6: the warm-up must construct the WebView provider at most once per process and must
 * never let a failed attempt escape. These drive the public [SlockAndroidWebViewWarmup.schedule]
 * entry point through its test seams; no production source text is inspected.
 */
class SlockAndroidWebViewWarmupTest {
    /**
     * The JVM unit-test classpath has no Android framework implementation, so the seams are
     * redirected before any real `Context` is touched and the `schedule` boundary receives an
     * opaque sentinel standing in for the context it would normally carry.
     */
    private val context = Any()

    @After
    fun tearDown() {
        SlockAndroidWebViewWarmup.resetForTest()
    }

    @Before
    fun setUp() {
        // `android.util.Log` throws on the JVM unit-test classpath; capture instead.
        SlockAndroidWebViewWarmup.log = { }
    }

    /** Drives [SlockAndroidWebViewWarmup.schedule] without a real `Context`. */
    private fun scheduleWarmup() {
        SlockAndroidWebViewWarmup.scheduleForTest(context)
    }

    private fun installSyncDispatch(onRan: () -> Unit = {}) {
        SlockAndroidWebViewWarmup.resolveContext = { context }
        SlockAndroidWebViewWarmup.dispatch = { task ->
            task()
            onRan()
            true
        }
    }

    @Test
    fun scheduleConstructsTheWarmInstanceExactlyOnce() {
        val constructions = AtomicInteger(0)
        var ran = false
        SlockAndroidWebViewWarmup.createWarmInstance = {
            constructions.incrementAndGet()
            Any()
        }
        installSyncDispatch { ran = true }

        scheduleWarmup()

        assertTrue("warm-up must actually run", ran)
        assertEquals("provider must be constructed once", 1, constructions.get())
    }

    @Test
    fun repeatedScheduleDoesNotConstructAgain() {
        val constructions = AtomicInteger(0)
        SlockAndroidWebViewWarmup.createWarmInstance = {
            constructions.incrementAndGet()
            Any()
        }
        installSyncDispatch()

        repeat(5) { scheduleWarmup() }

        assertEquals("single-shot gate must hold across repeats", 1, constructions.get())
        assertTrue(SlockAndroidWebViewWarmup.hasScheduled())
    }

    @Test
    fun scheduleFromConcurrentCallersStillConstructsOnce() {
        val constructions = AtomicInteger(0)
        val entered = CountDownLatch(1)
        SlockAndroidWebViewWarmup.createWarmInstance = {
            constructions.incrementAndGet()
            entered.countDown()
            Thread.sleep(50)
            Any()
        }
        SlockAndroidWebViewWarmup.resolveContext = { context }
        SlockAndroidWebViewWarmup.dispatch = { task ->
            Thread { task() }.apply { isDaemon = true }.start()
            true
        }

        val callers =
            (1..8).map {
                Thread { scheduleWarmup() }.apply {
                    isDaemon = true
                    start()
                }
            }
        callers.forEach { it.join(2_000) }

        assertTrue("warm-up never started", entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(250)
        assertEquals("only one caller may win the gate", 1, constructions.get())
    }

    @Test
    fun failedConstructionIsSwallowedAndNotThrown() {
        SlockAndroidWebViewWarmup.createWarmInstance = {
            throw IllegalStateException("provider unavailable")
        }
        var ran = false
        installSyncDispatch { ran = true }

        // Must not propagate: a provider that cannot initialise may never crash startup.
        scheduleWarmup()

        assertTrue("warm-up path must be attempted", ran)
        assertTrue(
            "the single-shot gate still closes on failure",
            SlockAndroidWebViewWarmup.hasScheduled()
        )
    }

    @Test
    fun rejectedDispatchStillClosesTheGate() {
        var dispatched = false
        SlockAndroidWebViewWarmup.resolveContext = { context }
        SlockAndroidWebViewWarmup.dispatch = {
            dispatched = true
            false
        }

        scheduleWarmup()

        assertTrue("dispatch must be attempted", dispatched)
        assertFalse(
            "a rejected post must not leave the gate open to retry on every launch",
            !SlockAndroidWebViewWarmup.hasScheduled()
        )
    }
}
