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
}
