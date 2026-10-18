package build.raft.app.shared

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
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
 * - **Off the critical path.** Scheduled at a posted message-queue idle slot, so the first
 *   frame and any startup work already queued win. There is no warm-up attempt before the
 *   main thread goes idle once.
 * - **Single shot.** A process-level [AtomicBoolean] guarantees at most one construction for
 *   the lifetime of the process, regardless of how many callers schedule it.
 * - **Silent on failure.** A provider that refuses to initialise must never surface as a
 *   crash at startup; the real web surface still works exactly as before (it just pays the
 *   cold cost). Never rethrows.
 * - **No behavioural dependency.** Nothing waits for this. The created instance is discarded;
 *   it exists only to trigger provider initialisation. It is detached from any window
 *   (never added to a `ViewGroup`), so it cannot draw, intercept input, or hold a
 *   `WebViewClient`.
 *
 * The warm-up does not change any security-relevant setting on the real surfaces: those are
 * applied per-instance in `SlockSandboxWebViewRenderView.configureWebView()`.
 */
internal object SlockAndroidWebViewWarmup {
    private const val LOG_TAG = "SlockWebViewWarmup"

    /**
     * Test seam: production points at the real construction path. Tests replace this to count
     * attempts and to simulate a provider that throws, without needing a WebView on the JVM.
     */
    @Volatile
    internal var createWarmInstance: (Any?) -> Any = { context -> WebView(context as Context) }

    /**
     * Test seam: how a scheduled warm-up is dispatched. Production posts to the main looper;
     * tests substitute a synchronous runner.
     */
    @Volatile
    internal var dispatch: (() -> Unit) -> Boolean =
            { task -> Handler(Looper.getMainLooper()).post(task) }

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

    private val attempted = AtomicBoolean(false)

    @Volatile
    private var warmInstance: Any? = null

    /**
     * Schedules a one-shot warm-up on the next main-thread idle slot. Safe to call from any
     * thread and safe to call repeatedly; only the first call has an effect.
     */
    fun schedule(context: Context) {
        if (!attempted.compareAndSet(false, true)) return
        val startedNs = System.nanoTime()
        val queued =
            dispatch {
                runWarmup(resolveContext(context), startedNs)
            }
        if (!queued) {
            log("webview_warmup skipped reason=post_rejected")
        }
    }

    fun hasScheduled(): Boolean = attempted.get()

    /**
     * Test-only entry that mirrors [schedule] but takes an opaque context sentinel, so JVM unit
     * tests can drive the same gate and seams without an Android `Context` implementation.
     * Production code always goes through [schedule].
     */
    internal fun scheduleForTest(contextSentinel: Any?) {
        if (!attempted.compareAndSet(false, true)) return
        val startedNs = System.nanoTime()
        val queued =
            dispatch {
                runWarmup(resolveContext(contextSentinel), startedNs)
            }
        if (!queued) {
            log("webview_warmup skipped reason=post_rejected")
        }
    }

    private fun runWarmup(context: Any?, startedNs: Long) {
        runCatching {
                val warm =
                    runCatching { createWarmInstance(context) }
                        .getOrElse { error ->
                            log("webview_warmup failed stage=construct errorType=" +
                                    (error::class.simpleName ?: "unknown")
                            )
                            return
                        }
                warmInstance = warm
                // Load an empty document so the provider completes its own bootstrap, then
                // keep the instance detached. No client/JS/navigation configuration is
                // applied: this instance must never be able to reach product content.
                (warm as? WebView)?.loadUrl("about:blank")
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                log("webview_warmup completed elapsedMs=$elapsedMs")
            }
            .onFailure { error ->
                log("webview_warmup failed stage=load errorType=" +
                        (error::class.simpleName ?: "unknown")
                )
            }
    }

    /** Test-only: restore the single-shot gate and drop the warmed instance. */
    internal fun resetForTest() {
        attempted.set(false)
        warmInstance = null
        createWarmInstance = { context -> WebView(context as Context) }
        dispatch = { task -> Handler(Looper.getMainLooper()).post(task) }
        resolveContext = { context -> (context as Context).applicationContext ?: context }
        log = { message -> Log.i(LOG_TAG, message) }
    }
}
