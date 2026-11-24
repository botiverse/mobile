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
    private val pendingCallbacks = mutableListOf<(Boolean, String?) -> Unit>()

    private val defaultBackgroundExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "raft-webcontainer-startup").apply { isDaemon = true }
        }
    }

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
                        if (onCompleted != null) pendingCallbacks += onCompleted
                        true
                    }
                }
            }
        if (!shouldStart) {
            onCompleted?.invoke(true, null)
            return false
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext ?: context
        return try {
            val config =
                WebViewStartUpConfig.Builder(backgroundExecutor)
                    .setShouldRunUiThreadStartUpTasks(shouldRunUiThreadStartUpTasks)
                    .build()
            WebViewCompat.startUpWebView(
                appContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        val elapsed = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
                        val listeners =
                            synchronized(lock) {
                                currentState = AndroidWebContainerStartupState.READY
                                pendingCallbacks.toList().also { pendingCallbacks.clear() }
                            }
                        telemetrySink?.onEvent(WebContainerTelemetryEvent.StartupCompleted(elapsed))
                        listeners.forEach { it(true, null) }
                    }

                    override fun onError(error: WebViewStartupException) {
                        val reason = error.message?.take(160) ?: (error::class.simpleName ?: "startup_failed")
                        val listeners =
                            synchronized(lock) {
                                currentState = AndroidWebContainerStartupState.FAILED
                                pendingCallbacks.toList().also { pendingCallbacks.clear() }
                            }
                        telemetrySink?.onEvent(WebContainerTelemetryEvent.StartupFailed(reason))
                        listeners.forEach { it(false, reason) }
                    }
                },
            )
            true
        } catch (error: Throwable) {
            val reason = error.message?.take(160) ?: (error::class.simpleName ?: "startup_exception")
            val listeners =
                synchronized(lock) {
                    currentState = AndroidWebContainerStartupState.FAILED
                    pendingCallbacks.toList().also { pendingCallbacks.clear() }
                }
            telemetrySink?.onEvent(WebContainerTelemetryEvent.StartupFailed(reason))
            listeners.forEach { it(false, reason) }
            false
        }
    }
}
