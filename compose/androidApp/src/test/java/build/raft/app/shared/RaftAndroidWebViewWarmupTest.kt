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
 * never let a failed attempt escape. These drive the public [RaftAndroidWebViewWarmup.schedule]
 * entry point through its test seams; no production source text is inspected.
 */
class RaftAndroidWebViewWarmupTest {
    /**
     * The JVM unit-test classpath has no Android framework implementation, so the seams are
     * redirected before any real `Context` is touched and the `schedule` boundary receives an
     * opaque sentinel standing in for the context it would normally carry.
     */
    private val context = Any()

    @After
    fun tearDown() {
        RaftAndroidWebViewWarmup.resetForTest()
    }

    @Before
    fun setUp() {
        // `android.util.Log` throws on the JVM unit-test classpath; capture instead.
        RaftAndroidWebViewWarmup.log = { }
    }

    /** Drives [RaftAndroidWebViewWarmup.schedule] without a real `Context`. */
    private fun scheduleWarmup() {
        RaftAndroidWebViewWarmup.scheduleForTest(context)
    }

    private fun installSyncDispatch(onRan: () -> Unit = {}) {
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            onRan()
            true
        }
    }

    @Test
    fun scheduleConstructsTheWarmInstanceExactlyOnce() {
        val constructions = AtomicInteger(0)
        var ran = false
        RaftAndroidWebViewWarmup.createWarmInstance = {
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
        RaftAndroidWebViewWarmup.createWarmInstance = {
            constructions.incrementAndGet()
            Any()
        }
        installSyncDispatch()

        repeat(5) { scheduleWarmup() }

        assertEquals("single-shot gate must hold across repeats", 1, constructions.get())
        assertTrue(RaftAndroidWebViewWarmup.hasScheduled())
    }

    @Test
    fun concurrentCallersAreRejectedAtTheGateBeforeConstruction() {
        // Proves the stronger property that distinguishes this from a "create then discard"
        // design: the single-shot gate closes BEFORE any construction work starts, so callers
        // that lose the race never enter the factory at all. A design that constructs first and
        // publishes under a lock would still show constructions > 1 here, because the slow first
        // construction is what every later caller would run into.
        val constructions = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val releaseConstruction = CountDownLatch(1)
        RaftAndroidWebViewWarmup.createWarmInstance = {
            constructions.incrementAndGet()
            entered.countDown()
            // Block inside the factory so a gate that is not closed up-front has every
            // opportunity to let another caller in.
            releaseConstruction.await(2, TimeUnit.SECONDS)
            Any()
        }
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            Thread { task() }.apply { isDaemon = true }.start()
            true
        }

        val first = Thread { scheduleWarmup() }.apply { isDaemon = true; start() }
        assertTrue("first warm-up never entered construction", entered.await(2, TimeUnit.SECONDS))

        // While the first caller is still blocked inside the factory, the gate is already shut.
        assertTrue(RaftAndroidWebViewWarmup.hasScheduled())
        assertEquals("gate must close before construction begins", 1, constructions.get())

        val latecomers =
            (1..8).map {
                Thread { scheduleWarmup() }.apply { isDaemon = true; start() }
            }
        latecomers.forEach { it.join(2_000) }

        // Latecomers must return immediately without ever reaching the factory.
        assertEquals("lost callers must not construct", 1, constructions.get())

        releaseConstruction.countDown()
        first.join(2_000)
        assertEquals("exactly one provider construction", 1, constructions.get())
    }

    @Test
    fun failedConstructionIsSwallowedAndNotThrown() {
        RaftAndroidWebViewWarmup.createWarmInstance = {
            throw IllegalStateException("provider unavailable")
        }
        var ran = false
        installSyncDispatch { ran = true }

        // Must not propagate: a provider that cannot initialise may never crash startup.
        scheduleWarmup()

        assertTrue("warm-up path must be attempted", ran)
        assertTrue(
            "the single-shot gate still closes on failure",
            RaftAndroidWebViewWarmup.hasScheduled()
        )
    }

    @Test
    fun rejectedDispatchStillClosesTheGate() {
        var dispatched = false
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.dispatch = {
            dispatched = true
            false
        }

        scheduleWarmup()

        assertTrue("dispatch must be attempted", dispatched)
        assertFalse(
            "a rejected post must not leave the gate open to retry on every launch",
            !RaftAndroidWebViewWarmup.hasScheduled()
        )
    }
}
