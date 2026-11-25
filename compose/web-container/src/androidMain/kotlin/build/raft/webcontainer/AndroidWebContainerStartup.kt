package build.raft.webcontainer

import android.content.Context
import android.os.SystemClock
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Asynchronous startup status of the Android WebView Chromium provider. */
enum class AndroidWebContainerStartupState {
    NOT_STARTED,
    STARTING,
    READY,
    FAILED,
}

/**
 * Idempotent off-main-thread WebView startup helper backed by `androidx.webkit`
 * [WebViewCompat.startUpWebView] (stable since AndroidX WebKit 1.16+).
 *
 * Call early from `Application.onCreate` (or first idle frame) so Chromium provider initialization
 * runs off the UI thread instead of blocking first WebView inflation. Concurrent callers arriving
 * while startup is in flight all receive their `onCompleted` callback upon completion.
 */
object AndroidWebContainerStartup {
    private val lock = Any()
    private var currentState: AndroidWebContainerStartupState = AndroidWebContainerStartupState.NOT_STARTED
    private var attemptGeneration: Long = 0L
    private val pendingCallbacks = mutableListOf<(Boolean, String?) -> Unit>()

    private val defaultBackgroundExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "raft-webcontainer-startup").apply { isDaemon = true }
        }
    }

    private val defaultStartUpInvoker: (
        Context?,
        WebViewStartUpConfig,
        WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>,
    ) -> Unit = { ctx, cfg, callback ->
        WebViewCompat.startUpWebView(requireNotNull(ctx), cfg, callback)
    }

    @Volatile
    internal var startUpInvoker: (
        Context?,
        WebViewStartUpConfig,
        WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>,
    ) -> Unit = defaultStartUpInvoker

    val state: AndroidWebContainerStartupState
        get() = synchronized(lock) { currentState }

    /**
     * Triggers asynchronous Chromium WebView initialization once per process.
     *
     * @return `true` if startup was initiated by this call, `false` if already starting/ready.
     */
    fun startUpAsync(
        context: Context,
        shouldRunUiThreadStartUpTasks: Boolean = true,
        backgroundExecutor: Executor = defaultBackgroundExecutor,
        telemetrySink: WebContainerTelemetrySink? = null,
        onCompleted: ((success: Boolean, errorReason: String?) -> Unit)? = null,
    ): Boolean {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        return startUpAsyncInternal(
            appContext = appContext,
            shouldRunUiThreadStartUpTasks = shouldRunUiThreadStartUpTasks,
            backgroundExecutor = backgroundExecutor,
            telemetrySink = telemetrySink,
            onCompleted = onCompleted,
        )
    }

    internal fun startUpAsyncInternal(
        appContext: Context?,
        shouldRunUiThreadStartUpTasks: Boolean = true,
        backgroundExecutor: Executor = defaultBackgroundExecutor,
        telemetrySink: WebContainerTelemetrySink? = null,
        onCompleted: ((success: Boolean, errorReason: String?) -> Unit)? = null,
    ): Boolean {
        var activeGeneration = 0L
        val shouldStart =
            synchronized(lock) {
                when (currentState) {
                    AndroidWebContainerStartupState.READY -> {
                        false
                    }
                    AndroidWebContainerStartupState.STARTING -> {
                        if (onCompleted != null) pendingCallbacks += onCompleted
                        return false
                    }
                    AndroidWebContainerStartupState.NOT_STARTED,
                    AndroidWebContainerStartupState.FAILED,
                    -> {
                        currentState = AndroidWebContainerStartupState.STARTING
                        attemptGeneration += 1L
                        activeGeneration = attemptGeneration
                        if (onCompleted != null) pendingCallbacks += onCompleted
                        true
                    }
                }
            }
        if (!shouldStart) {
            runCatching { onCompleted?.invoke(true, null) }
            return false
        }

        val startedAtMs = monotonicMillis()
        // AndroidX WebKit 1.18.0 dispatches provider loading onto `backgroundExecutor` without a
        // try/catch around provider lookup; guard both submission and execution so a broken/updating
        // WebView package or shut-down executor never kills the process or wedges `STARTING`.
        val safeBackgroundExecutor =
            Executor { command ->
                try {
                    backgroundExecutor.execute {
                        try {
                            command.run()
                        } catch (error: Throwable) {
                            completeFailure(
                                expectedGeneration = activeGeneration,
                                reason = formatStartupErrorReason(error, "startup_bg_exception"),
                                telemetrySink = telemetrySink,
                            )
                        }
                    }
                } catch (error: Throwable) {
                    completeFailure(
                        expectedGeneration = activeGeneration,
                        reason = formatStartupErrorReason(error, "startup_executor_rejected"),
                        telemetrySink = telemetrySink,
                    )
                }
            }
        return try {
            val config =
                WebViewStartUpConfig.Builder(safeBackgroundExecutor)
                    .setShouldRunUiThreadStartUpTasks(shouldRunUiThreadStartUpTasks)
                    .build()
            startUpInvoker(
                appContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        val elapsed = (monotonicMillis() - startedAtMs).coerceAtLeast(0L)
                        completeSuccess(
                            expectedGeneration = activeGeneration,
                            elapsedMs = elapsed,
                            telemetrySink = telemetrySink,
                        )
                    }

                    override fun onError(error: WebViewStartupException) {
                        completeFailure(
                            expectedGeneration = activeGeneration,
                            reason = formatStartupErrorReason(error, "startup_failed"),
                            telemetrySink = telemetrySink,
                        )
                    }
                },
            )
            true
        } catch (error: Throwable) {
            completeFailure(
                expectedGeneration = activeGeneration,
                reason = formatStartupErrorReason(error, "startup_exception"),
                telemetrySink = telemetrySink,
            )
            false
        }
    }

    private fun completeSuccess(
        expectedGeneration: Long,
        elapsedMs: Long,
        telemetrySink: WebContainerTelemetrySink?,
    ) {
        val listeners =
            synchronized(lock) {
                if (currentState != AndroidWebContainerStartupState.STARTING || attemptGeneration != expectedGeneration) {
                    return
                }
                currentState = AndroidWebContainerStartupState.READY
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
        runCatching { telemetrySink?.onEvent(WebContainerTelemetryEvent.StartupCompleted(elapsedMs)) }
        listeners.forEach { listener -> runCatching { listener(true, null) } }
    }

    private fun completeFailure(
        expectedGeneration: Long,
        reason: String,
        telemetrySink: WebContainerTelemetrySink?,
    ) {
        val listeners =
            synchronized(lock) {
                if (currentState != AndroidWebContainerStartupState.STARTING || attemptGeneration != expectedGeneration) {
                    return
                }
                currentState = AndroidWebContainerStartupState.FAILED
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
        runCatching { telemetrySink?.onEvent(WebContainerTelemetryEvent.StartupFailed(reason)) }
        listeners.forEach { listener -> runCatching { listener(false, reason) } }
    }

    private fun formatStartupErrorReason(
        error: Throwable,
        fallback: String,
    ): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
            ?: (error::class.simpleName ?: fallback)

    private fun monotonicMillis(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }

    internal fun resetForTest() {
        synchronized(lock) {
            currentState = AndroidWebContainerStartupState.NOT_STARTED
            attemptGeneration += 1L
            pendingCallbacks.clear()
        }
        startUpInvoker = defaultStartUpInvoker
    }
}
