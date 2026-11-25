package build.raft.webcontainer

import androidx.webkit.WebViewStartUpResult
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidWebContainerStartupTest {
    @BeforeTest
    fun setUp() {
        AndroidWebContainerStartup.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        AndroidWebContainerStartup.resetForTest()
    }

    @Test
    fun backgroundExecutorExceptionIsCaughtTransitionsToFailedAndNotifiesAllCallers() {
        val uncaught = AtomicReference<Throwable?>(null)
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "test-wc-startup-bg").apply {
                    isDaemon = true
                    uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
                }
            }
        try {
            val releaseWorker = CountDownLatch(1)
            val callbackDone = CountDownLatch(2)
            val results = mutableListOf<Pair<Boolean, String?>>()
            val telemetry = mutableListOf<WebContainerTelemetryEvent>()

            AndroidWebContainerStartup.startUpInvoker = { _, config, _ ->
                config.backgroundExecutor.execute {
                    releaseWorker.await(5, TimeUnit.SECONDS)
                    throw RuntimeException("MissingWebViewPackageException: WebView updating")
                }
            }

            val startedFirst =
                AndroidWebContainerStartup.startUpAsyncInternal(
                    appContext = null,
                    backgroundExecutor = executor,
                    telemetrySink = WebContainerTelemetrySink { telemetry += it },
                ) { ok, reason ->
                    synchronized(results) { results += ok to reason }
                    callbackDone.countDown()
                    // Verify one throwing listener never blocks remaining pending listeners.
                    throw IllegalStateException("listener boom")
                }
            assertTrue(startedFirst)
            assertEquals(AndroidWebContainerStartupState.STARTING, AndroidWebContainerStartup.state)

            // Concurrent second caller joins pendingCallbacks while STARTING.
            val startedSecond =
                AndroidWebContainerStartup.startUpAsyncInternal(
                    appContext = null,
                    backgroundExecutor = executor,
                ) { ok, reason ->
                    synchronized(results) { results += ok to reason }
                    callbackDone.countDown()
                }
            assertFalse(startedSecond)

            releaseWorker.countDown()
            assertTrue(callbackDone.await(5, TimeUnit.SECONDS), "both pending callbacks must be woken on bg failure")
            assertNull(uncaught.get(), "background WebView startup exception must never escape uncaught")
            assertEquals(AndroidWebContainerStartupState.FAILED, AndroidWebContainerStartup.state)
            assertEquals(2, results.size)
            assertTrue(results.all { !it.first && it.second?.contains("MissingWebViewPackageException") == true })
            assertEquals(1, telemetry.count { it is WebContainerTelemetryEvent.StartupFailed })

            // Subsequent call after FAILED can retry cleanly and transition to READY.
            val retryDone = CountDownLatch(1)
            var retryOutcome: Pair<Boolean, String?>? = null
            val fakeResult =
                Proxy.newProxyInstance(
                    WebViewStartUpResult::class.java.classLoader,
                    arrayOf(WebViewStartUpResult::class.java),
                ) { _, _, _ -> null } as WebViewStartUpResult
            AndroidWebContainerStartup.startUpInvoker = { _, config, receiver ->
                config.backgroundExecutor.execute {
                    receiver.onResult(fakeResult)
                }
            }
            assertTrue(
                AndroidWebContainerStartup.startUpAsyncInternal(
                    appContext = null,
                    backgroundExecutor = executor,
                    telemetrySink = WebContainerTelemetrySink { telemetry += it },
                ) { ok, reason ->
                    retryOutcome = ok to reason
                    retryDone.countDown()
                },
            )
            assertTrue(retryDone.await(5, TimeUnit.SECONDS))
            assertEquals(true to null, retryOutcome)
            assertEquals(AndroidWebContainerStartupState.READY, AndroidWebContainerStartup.state)
            assertEquals(1, telemetry.count { it is WebContainerTelemetryEvent.StartupCompleted })

            // Once READY, callers receive immediate success without re-invoking startUpWebView.
            var alreadyReadyResult: Boolean? = null
            assertFalse(
                AndroidWebContainerStartup.startUpAsyncInternal(appContext = null) { ok, _ ->
                    alreadyReadyResult = ok
                },
            )
            assertEquals(true, alreadyReadyResult)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun staleAttemptCallbackCannotCorruptRetryAndRejectedExecutorWithBlankMessageFailsCleanly() {
        val fakeResult =
            Proxy.newProxyInstance(
                WebViewStartUpResult::class.java.classLoader,
                arrayOf(WebViewStartUpResult::class.java),
            ) { _, _, _ -> null } as WebViewStartUpResult

        var attempt1Receiver: androidx.webkit.WebViewOutcomeReceiver<WebViewStartUpResult, androidx.webkit.WebViewStartupException>? = null
        var attempt1Executor: Executor? = null
        val rejectingExecutor = Executor { throw RejectedExecutionException("   ") }

        // Attempt 1: async submission to a rejecting executor after startUpInvoker returns fails cleanly
        // with fallback class name (not blank string).
        var attempt1Outcome: Pair<Boolean, String?>? = null
        AndroidWebContainerStartup.startUpInvoker = { _, config, receiver ->
            attempt1Executor = config.backgroundExecutor
            attempt1Receiver = receiver
        }
        assertTrue(
            AndroidWebContainerStartup.startUpAsyncInternal(
                appContext = null,
                backgroundExecutor = rejectingExecutor,
            ) { ok, reason ->
                attempt1Outcome = ok to reason
            },
        )
        attempt1Executor!!.execute { }
        assertEquals(AndroidWebContainerStartupState.FAILED, AndroidWebContainerStartup.state)
        assertEquals(false, attempt1Outcome?.first)
        assertEquals("RejectedExecutionException", attempt1Outcome?.second)

        // Attempt 2 starts cleanly; late callback from Attempt 1 must be ignored by attemptGeneration.
        var attempt2Outcome: Pair<Boolean, String?>? = null
        var attempt2Receiver: androidx.webkit.WebViewOutcomeReceiver<WebViewStartUpResult, androidx.webkit.WebViewStartupException>? = null
        AndroidWebContainerStartup.startUpInvoker = { _, _, receiver ->
            attempt2Receiver = receiver
        }
        assertTrue(
            AndroidWebContainerStartup.startUpAsyncInternal(appContext = null) { ok, reason ->
                attempt2Outcome = ok to reason
            },
        )
        assertEquals(AndroidWebContainerStartupState.STARTING, AndroidWebContainerStartup.state)

        // Fire stale Attempt 1 result and stale Attempt 1 executor throw -> Attempt 2 must stay STARTING.
        attempt1Receiver!!.onResult(fakeResult)
        attempt1Executor!!.execute { throw IllegalStateException("") }
        assertEquals(AndroidWebContainerStartupState.STARTING, AndroidWebContainerStartup.state)
        assertNull(attempt2Outcome)

        // Attempt 2's own receiver completes it cleanly.
        attempt2Receiver!!.onResult(fakeResult)
        assertEquals(AndroidWebContainerStartupState.READY, AndroidWebContainerStartup.state)
        assertEquals(true to null, attempt2Outcome)
    }
}
