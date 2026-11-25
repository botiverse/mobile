package build.raft.app.shared

import android.content.Context
import android.os.Looper
import android.os.MessageQueue
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import build.raft.app.RaftDetachedWebViewClient
import build.raft.app.core.data.diagnostics.RaftAndroidLocalDiagnostics
import build.raft.app.isRaftAndroidWebContainerEnabledSnapshot
import build.raft.webcontainer.AndroidWebContainerStartup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Warms the Android WebView provider process so the first real web surface does not pay
 * the cold `WebView(context)` initialisation cost on the UI thread.
 *
 * Why this exists (task #6): Android's first `WebView` construction loads the WebView
 * provider and starts its renderer process. That is a several-hundred-millisecond cost
 * paid synchronously by whichever call site constructs the first `WebView`, and Raft's
 * web surfaces are reached from `SettingsStandaloneWebViewHost` /
 * `SlockSandboxWebViewRenderView`, i.e. directly on the UI thread at page entry. Users see
 * this as "the first web surface I open is slow; later ones are fine".
 *
 * Design constraints:
 * - **Off the critical path.** Scheduled from the first CMP frame (`RaftCmpActivity`), not from
 *   `Application.onCreate`, and run from a main-looper [MessageQueue.IdleHandler], so the first
 *   frame and any startup work already queued win. A cold start used to post it straight from
 *   `Application.onCreate`, which ran the provider load (300 ms+ interpreted, over a second on
 *   a loaded two-core device) before the launch activity was even created and started the
 *   renderer process while the first composition was still running.
 * - **Single shot.** A process-level [AtomicBoolean] guarantees at most one construction for
 *   the lifetime of the process, regardless of how many callers schedule it.
 * - **Silent on failure.** A provider that refuses to initialise must never surface as a
 *   crash at startup; the real web surface still works exactly as before (it just pays the
 *   cold cost). Never rethrows.
 * - **No behavioural dependency.** Nothing waits for this. The instance is kept (in
 *   `warmInstance`) for the life of the process so its renderer process stays up for the first
 *   real surface: a renderer lives only while some WebView uses it, so a destroyed warm-up
 *   would leave only the provider load (and its compiled code) warm, not the renderer.
 *   It is detached from any window (never added to a `ViewGroup`), so it cannot draw or
 *   intercept input, and it never reaches product content: no JS, no navigation, no page hooks.
 * - **Survives its renderer.** A kept WebView shares the app's one renderer process with every
 *   product WebView, and when that renderer dies Chromium kills the whole app unless *every*
 *   WebView on it handles `onRenderProcessGone`. So the warm instance does hold a
 *   [RaftDetachedWebViewClient]: a client with no product behaviour (it refuses every navigation
 *   and loads nothing) whose only job is that callback. It reports the death (shared
 *   `RaftWebViewRendererGone`), drops `warmInstance`, destroys the WebView and returns true. The
 *   warm-up is not redone: the provider stays loaded and the next product WebView starts a new
 *   renderer itself.
 *
 * The warm-up does not change any security-relevant setting on the real surfaces: those are
 * applied per-instance in `SlockSandboxWebViewRenderView.configureWebView()`.
 */
internal object RaftAndroidWebViewWarmup {
    private const val LOG_TAG = "SlockWebViewWarmup"

    /** Surface name of the warm instance in renderer-death logs and tickets. */
    internal const val WARMUP_SURFACE = "warmup"

    /**
     * Test seam: production points at the real construction path. Tests replace this to count
     * attempts and to simulate a provider that throws, without needing a WebView on the JVM.
     */
    @Volatile
    internal var createWarmInstance: (Any?) -> Any = { context -> newWarmWebView(context as Context) }

    /**
     * Test seam: how a scheduled warm-up is dispatched. Production waits for the main looper's
     * next idle slot; tests substitute a synchronous runner.
     */
    @Volatile
    internal var dispatch: (() -> Unit) -> Boolean = ::dispatchOnMainIdle

    /**
     * Test seam: resolves the context a warm-up run receives. Production passes the application
     * context through; tests avoid touching a real `Context` (the JVM unit-test classpath has no
     * Android framework implementation). Typed `Any?` on purpose: production strong-types it to
     * `Context` at the [schedule] boundary, while tests may hand through an opaque sentinel.
     */
    @Volatile
    internal var resolveContext: (Any?) -> Any? = { context ->
        (context as Context).applicationContext ?: context
    }

    /**
     * Test seam: log sink. `android.util.Log` is a throwing stub on the JVM unit-test
     * classpath, so tests replace this; production keeps the real logger.
     */
    @Volatile
    internal var log: (String) -> Unit = { message -> Log.i(LOG_TAG, message) }

    /**
     * Test seam: the local diagnostics log for the renderer-death line (kept in the diagnostics
     * ring, unlike [log]). Tests replace it.
     */
    @Volatile
    internal var logWarn: (String) -> Unit = { message -> RaftAndroidLocalDiagnostics.recordLog("warn", LOG_TAG, message) }

    /**
     * Test seam for the opt-in asynchronous WebView provider startup path
     * ([AndroidWebContainerStartup.startUpAsync]).
     *
     * Contract: unless throwing an exception, every implementation MUST invoke `onCompleted`
     * (either synchronously or asynchronously), even when returning `false` (for example when
     * `AndroidWebContainerStartup.startUpAsync` is already `STARTING` or `READY`).
     */
    @Volatile
    internal var runAsyncStartup: (Any?, (Boolean, String?) -> Unit) -> Boolean = ::defaultRunAsyncStartup

    /**
     * Test seam resolving whether the current process should run off-main-thread
     * `WebViewCompat.startUpWebView` ahead of detached renderer warm-up (gated behind
     * `mobile_web_container_v1`, default OFF).
     */
    @Volatile
    internal var resolveAsyncStartupPreferred: () -> Boolean = ::isRaftAndroidWebContainerEnabledSnapshot

    private fun defaultRunAsyncStartup(
        context: Any?,
        onCompleted: (Boolean, String?) -> Unit,
    ): Boolean {
        val androidContext = context as? Context
        if (androidContext == null) {
            onCompleted(false, "context_unavailable")
            return false
        }
        return AndroidWebContainerStartup.startUpAsync(
            context = androidContext,
            onCompleted = onCompleted,
        )
    }

    private val attempted = AtomicBoolean(false)

    @Volatile
    private var warmInstance: Any? = null

    /** Test-only view of the kept instance. */
    internal val warmInstanceForTest: Any? get() = warmInstance

    /** The warm WebView: no settings beyond the defaults, a client that only survives its renderer. */
    internal fun newWarmWebView(context: Context): WebView =
        WebView(context).apply {
            webViewClient = RaftDetachedWebViewClient(WARMUP_SURFACE) { view, detail -> onWarmRendererGone(view, detail) }
        }

    /**
     * The warm instance's renderer is gone: let it go (drop and destroy it). Main thread, from
     * [RaftDetachedWebViewClient.onRenderProcessGone], which returns true.
     */
    internal fun onWarmRendererGone(view: WebView?, detail: RenderProcessGoneDetail?) {
        val held = view != null && warmInstance === view
        if (held) warmInstance = null
        val didCrash = detail?.didCrash()
        val priority = detail?.rendererPriorityAtExit()
        runCatching { logWarn("webview_warmup released reason=render_process_gone didCrash=$didCrash priority=$priority held=$held") }
        runCatching { view?.destroy() }
    }

    /**
     * Schedules a one-shot warm-up on the next main-thread idle slot. Safe to call from any
     * thread and safe to call repeatedly; only the first call has an effect.
     *
     * When `mobile_web_container_v1` is active ([resolveAsyncStartupPreferred]), Chromium provider
     * initialization runs off the UI thread first via `WebViewCompat.startUpWebView` and queues the
     * detached warm renderer afterward (with automatic single-shot fallback on error).
     */
    fun schedule(context: Context) {
        scheduleInternal(context)
    }

    fun hasScheduled(): Boolean = attempted.get()

    /**
     * Opt-in off-main-thread Chromium provider startup backed by `WebViewCompat.startUpWebView`
     * ([AndroidWebContainerStartup.startUpAsync]). Disabled by default until enabled by feature
     * flag so existing warm-up behaviour stays unchanged during rollout. Never throws.
     */
    fun scheduleAsyncProviderStartup(
        context: Context,
        enabled: Boolean,
    ): Boolean = scheduleAsyncProviderStartupForTest(context, enabled)

    internal fun scheduleAsyncProviderStartupForTest(
        contextSentinel: Any?,
        enabled: Boolean,
    ): Boolean {
        if (!enabled) return false
        return runCatching {
            val resolved = resolveContext(contextSentinel)
            val startedNs = System.nanoTime()
            runAsyncStartup(resolved) { success, errorReason ->
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                if (success) {
                    runCatching { log("webview_async_startup status=ready elapsedMs=$elapsedMs") }
                } else {
                    runCatching { logWarn("webview_async_startup status=failed reason=${errorReason ?: "unknown"}") }
                }
            }
        }.getOrElse { error ->
            runCatching {
                logWarn(
                    "webview_async_startup status=exception errorType=${error::class.simpleName ?: "unknown"}",
                )
            }
            false
        }
    }

    /**
     * Test-only entry that mirrors [schedule] but takes an opaque context sentinel, so JVM unit
     * tests can drive the same gate and seams without an Android `Context` implementation.
     * Production code always goes through [schedule].
     */
    internal fun scheduleForTest(contextSentinel: Any?) {
        scheduleInternal(contextSentinel)
    }

    private fun scheduleInternal(contextSentinel: Any?) {
        if (!attempted.compareAndSet(false, true)) return
        val startedNs = System.nanoTime()
        val preferAsyncStartup = runCatching { resolveAsyncStartupPreferred() }.getOrDefault(false)
        if (!preferAsyncStartup) {
            queueDetachedWarmRenderer(contextSentinel, startedNs)
            return
        }
        val rendererQueued = AtomicBoolean(false)
        fun queueRendererOnce(resolvedContext: Any?, alreadyResolved: Boolean) {
            if (!rendererQueued.compareAndSet(false, true)) return
            queueDetachedWarmRenderer(resolvedContext, startedNs, alreadyResolved = alreadyResolved)
        }
        val resolved =
            runCatching { resolveContext(contextSentinel) }
                .getOrElse { error ->
                    runCatching {
                        logWarn(
                            "webview_async_startup status=exception errorType=${error::class.simpleName ?: "unknown"}",
                        )
                    }
                    queueRendererOnce(contextSentinel, alreadyResolved = false)
                    return
                }
        runCatching {
            runAsyncStartup(resolved) { success, errorReason ->
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                if (success) {
                    runCatching { log("webview_async_startup status=ready elapsedMs=$elapsedMs") }
                } else {
                    runCatching { logWarn("webview_async_startup status=failed reason=${errorReason ?: "unknown"}") }
                }
                queueRendererOnce(resolved, alreadyResolved = true)
            }
        }.onFailure { error ->
            runCatching {
                logWarn(
                    "webview_async_startup status=exception errorType=${error::class.simpleName ?: "unknown"}",
                )
            }
            queueRendererOnce(resolved, alreadyResolved = true)
        }
    }

    private fun queueDetachedWarmRenderer(
        contextSentinel: Any?,
        startedNs: Long,
        alreadyResolved: Boolean = false,
    ) {
        val queued =
            runCatching {
                dispatch {
                    val targetContext =
                        if (alreadyResolved) {
                            contextSentinel
                        } else {
                            runCatching { resolveContext(contextSentinel) }
                                .getOrElse { error ->
                                    runCatching {
                                        log(
                                            "webview_warmup failed stage=resolve_context errorType=" +
                                                (error::class.simpleName ?: "unknown")
                                        )
                                    }
                                    return@dispatch
                                }
                        }
                    runWarmup(targetContext, startedNs)
                }
            }.getOrElse { error ->
                runCatching {
                    logWarn(
                        "webview_warmup skipped reason=dispatch_exception errorType=" +
                            (error::class.simpleName ?: "unknown")
                    )
                }
                return
            }
        if (!queued) {
            runCatching { log("webview_warmup skipped reason=post_rejected") }
        }
    }

    private fun runWarmup(context: Any?, startedNs: Long) {
        runCatching {
                val warm =
                    runCatching { createWarmInstance(context) }
                        .getOrElse { error ->
                            runCatching {
                                log(
                                    "webview_warmup failed stage=construct errorType=" +
                                        (error::class.simpleName ?: "unknown")
                                )
                            }
                            return
                        }
                warmInstance = warm
                // Load an empty document so the provider completes its own bootstrap (and starts
                // the renderer), then keep the instance detached. Defaults only (no JS, no
                // navigation): this instance must never be able to reach product content.
                (warm as? WebView)?.loadUrl("about:blank")
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                runCatching { log("webview_warmup completed elapsedMs=$elapsedMs") }
            }
            .onFailure { error ->
                runCatching {
                    log(
                        "webview_warmup failed stage=load errorType=" +
                            (error::class.simpleName ?: "unknown")
                    )
                }
            }
    }

    /** Test-only: restore the single-shot gate and drop the warmed instance. */
    internal fun resetForTest() {
        attempted.set(false)
        warmInstance = null
        createWarmInstance = { context -> newWarmWebView(context as Context) }
        dispatch = ::dispatchOnMainIdle
        resolveContext = { context -> (context as Context).applicationContext ?: context }
        log = { message -> Log.i(LOG_TAG, message) }
        logWarn = { message -> RaftAndroidLocalDiagnostics.recordLog("warn", LOG_TAG, message) }
        runAsyncStartup = ::defaultRunAsyncStartup
        resolveAsyncStartupPreferred = ::isRaftAndroidWebContainerEnabledSnapshot
    }

    /** Runs [task] once, the next time the main looper has nothing due. Any thread. */
    private fun dispatchOnMainIdle(task: () -> Unit): Boolean {
        Looper.getMainLooper().queue.addIdleHandler(
            MessageQueue.IdleHandler {
                task()
                false
            }
        )
        return true
    }
}
