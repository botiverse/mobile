package build.raft.app.shared

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        RaftAndroidWebViewWarmup.resetForTest()
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

    @Test
    fun scheduleUsesAsyncProviderStartupFirstWhenPreferredThenWarmsRendererOnce() {
        val order = mutableListOf<String>()
        val warmCreations = AtomicInteger(0)
        var capturedCallback: ((Boolean, String?) -> Unit)? = null
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = { true }
        RaftAndroidWebViewWarmup.runAsyncStartup = { _, onCompleted ->
            order += "async_start"
            capturedCallback = onCompleted
            true
        }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            order += "idle_dispatch"
            task()
            true
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            order += "warm_create"
            Any()
        }

        RaftAndroidWebViewWarmup.scheduleForTest(context)

        // Renderer warm-up must wait until async Chromium provider startup completes.
        assertEquals(listOf("async_start"), order)
        assertEquals(0, warmCreations.get())

        // Second schedule call must be a no-op while async startup is in flight.
        RaftAndroidWebViewWarmup.scheduleForTest(context)
        assertEquals(listOf("async_start"), order)

        capturedCallback!!.invoke(true, null)

        assertEquals(listOf("async_start", "idle_dispatch", "warm_create"), order)
        assertEquals(1, warmCreations.get())
    }

    @Test
    fun scheduleFallsBackToDetachedWarmupOnceWhenAsyncStartupFails() {
        val warnLogs = mutableListOf<String>()
        val warmCreations = AtomicInteger(0)
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = { true }
        RaftAndroidWebViewWarmup.logWarn = { warnLogs += it }
        RaftAndroidWebViewWarmup.runAsyncStartup = { _, onCompleted ->
            onCompleted(false, "provider_too_old")
            true
        }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            true
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            Any()
        }

        RaftAndroidWebViewWarmup.scheduleForTest(context)

        assertEquals(1, warmCreations.get())
        assertTrue(warnLogs.any { it.contains("webview_async_startup status=failed reason=provider_too_old") })
    }

    @Test
    fun scheduleFallsBackToDetachedWarmupAtMostOnceWhenAsyncStartupThrows() {
        val warnLogs = mutableListOf<String>()
        val warmCreations = AtomicInteger(0)
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = { true }
        RaftAndroidWebViewWarmup.logWarn = { warnLogs += it }
        RaftAndroidWebViewWarmup.runAsyncStartup = { _, onCompleted ->
            // Even if an implementation fires onCompleted and then throws, warm WebView is created once.
            onCompleted(false, "partial_failure")
            throw IllegalStateException("boom")
        }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            true
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            Any()
        }

        RaftAndroidWebViewWarmup.scheduleForTest(context)

        assertEquals(1, warmCreations.get())
        assertTrue(warnLogs.any { it.contains("webview_async_startup status=exception") })
    }

    @Test
    fun scheduleSkipsAsyncStartupWhenFlagDisabled() {
        val asyncCalls = AtomicInteger(0)
        val warmCreations = AtomicInteger(0)
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = { false }
        RaftAndroidWebViewWarmup.runAsyncStartup = { _, _ ->
            asyncCalls.incrementAndGet()
            true
        }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            true
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            Any()
        }

        RaftAndroidWebViewWarmup.scheduleForTest(context)

        assertEquals(0, asyncCalls.get())
        assertEquals(1, warmCreations.get())
    }

    @Test
    fun scheduleFailsClosedToLegacyWarmupWhenPreferenceCheckThrowsAndSwallowsDispatchException() {
        val preferenceCalls = AtomicInteger(0)
        val warmCreations = AtomicInteger(0)
        val warnLogs = mutableListOf<String>()
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.logWarn = { warnLogs += it }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = {
            preferenceCalls.incrementAndGet()
            throw IllegalStateException("registry unavailable")
        }
        RaftAndroidWebViewWarmup.dispatch = { _ ->
            throw IllegalStateException("looper unavailable")
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            Any()
        }

        // Must never throw even if both preference check and dispatch throw.
        RaftAndroidWebViewWarmup.scheduleForTest(context)
        // Second call must short-circuit BEFORE re-evaluating resolveAsyncStartupPreferred.
        RaftAndroidWebViewWarmup.scheduleForTest(context)

        assertEquals(1, preferenceCalls.get())
        assertEquals(0, warmCreations.get())
        assertTrue(warnLogs.any { it.contains("webview_warmup skipped reason=dispatch_exception") })
    }

    @Test
    fun scheduleIgnoresDuplicateAsyncCallbacksAndSwallowsResolveContextFailure() {
        val warmCreations = AtomicInteger(0)
        val warnLogs = mutableListOf<String>()
        RaftAndroidWebViewWarmup.resolveContext = { context }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = { true }
        RaftAndroidWebViewWarmup.logWarn = { warnLogs += it }
        RaftAndroidWebViewWarmup.runAsyncStartup = { _, onCompleted ->
            onCompleted(true, null)
            onCompleted(true, null)
            true
        }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            true
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            Any()
        }

        RaftAndroidWebViewWarmup.scheduleForTest(context)
        assertEquals(1, warmCreations.get())

        // Now verify resolveContext failure in async path still swallows and never throws.
        RaftAndroidWebViewWarmup.resetForTest()
        RaftAndroidWebViewWarmup.log = { }
        RaftAndroidWebViewWarmup.logWarn = { warnLogs += it }
        RaftAndroidWebViewWarmup.resolveAsyncStartupPreferred = { true }
        RaftAndroidWebViewWarmup.resolveContext = { throw IllegalStateException("bad context") }
        RaftAndroidWebViewWarmup.dispatch = { task ->
            task()
            true
        }
        RaftAndroidWebViewWarmup.createWarmInstance = {
            warmCreations.incrementAndGet()
            Any()
        }

        RaftAndroidWebViewWarmup.scheduleForTest(context)
        assertEquals(1, warmCreations.get())
        assertTrue(warnLogs.any { it.contains("webview_async_startup status=exception") })
    }
}
