package build.raft.app

import io.github.kdroidfilter.webview.wry.JavaScriptCallback
import io.github.kdroidfilter.webview.wry.Rgba
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import java.awt.Canvas
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * Snapshot of Wry engine state read on the dedicated off-EDT worker thread and delivered back to
 * the AWT EDT as one immutable value.
 */
data class RaftDesktopWryPollSnapshot(
    val ipcMessages: List<String>,
    val isLoading: Boolean,
    val title: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

/**
 * The desktop WebView's single off-EDT gateway contract.
 *
 * On macOS, AppKit / WKWebView runs on macOS Thread 0 while Compose Desktop runs on the AWT EDT.
 * During Chinese IME (`firstRectForCharacterRange`) and window live-resize, macOS Thread 0
 * synchronously waits on the AWT EDT. To keep waiting strictly unidirectional (Thread 0 -> EDT
 * only, never EDT -> Thread 0), every Wry native call that may synchronously dispatch to Thread 0
 * (`_dispatch_sync_f`) or acquire Rust engine state locks MUST run on a dedicated off-EDT worker
 * thread and deliver results back via [SwingUtilities.invokeLater].
 */
interface RaftDesktopWryBridge {
    /** The Swing component Compose embeds inside `SwingPanel`. */
    val component: Component

    /** Whether the native WebView has finished async creation and has a live native handle. */
    fun isReady(): Boolean

    /** Registers the navigation filter (invoked on Wry's native thread). */
    fun addNavigateListener(listener: (String) -> Boolean)

    /** Moves the native view on screen once the page is past its loading frame (AWT EDT). */
    fun reveal()

    /** Moves the native view back off screen while a Compose cover hides it (AWT EDT). */
    fun hide() = Unit

    /**
     * Reads IPC messages, loading state, title, and back/forward capability off the AWT EDT and
     * delivers [onResult] back on the AWT EDT. Returns false without scheduling if not ready or
     * already disposed.
     */
    fun poll(onResult: (RaftDesktopWryPollSnapshot) -> Unit): Boolean

    /**
     * Evaluates [script] off the AWT EDT and delivers the raw JSON-encoded result (or `""` on error
     * or when not yet ready) back on the AWT EDT.
     */
    fun evaluateJavaScript(script: String, onResult: (String) -> Unit)

    fun loadUrl(url: String)

    fun goBackIfPossible(onCompleted: () -> Unit = {})

    fun goForwardIfPossible(onCompleted: () -> Unit = {})

    fun reload(onCompleted: () -> Unit = {})

    fun stopLoading(onCompleted: () -> Unit = {})

    fun dispose()
}

/**
 * Low-level native FFI operations invoked strictly on the off-EDT Wry worker thread.
 *
 * Notice we call the top-level UniFFI functions in `io.github.kdroidfilter.webview.wry` directly
 * rather than `WryWebViewPanel`'s command methods (`loadUrl`, `goBack`, `goForward`, `reload`,
 * `stopLoading`, `requestWebViewFocus`), because `WryWebViewPanel`'s bytecode re-dispatches all of
 * those back onto the AWT EDT via `SwingUtilities.invokeLater` before invoking the blocking native
 * `_dispatch_sync_f` call.
 */
interface RaftDesktopWryNativeOps {
    fun drainIpcMessages(id: ULong): List<String>

    fun isLoading(id: ULong): Boolean

    fun getTitle(id: ULong): String?

    fun canGoBack(id: ULong): Boolean

    fun canGoForward(id: ULong): Boolean

    fun evaluateJavascript(id: ULong, script: String, onResult: (String) -> Unit)

    fun loadUrl(id: ULong, url: String)

    fun goBack(id: ULong)

    fun goForward(id: ULong)

    fun reload(id: ULong)

    fun stopLoading(id: ULong)

    fun focus(id: ULong)

    fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int)
}

private object RealRaftDesktopWryNativeOps : RaftDesktopWryNativeOps {
    override fun drainIpcMessages(id: ULong): List<String> =
        io.github.kdroidfilter.webview.wry.drainIpcMessages(id)

    override fun isLoading(id: ULong): Boolean =
        io.github.kdroidfilter.webview.wry.isLoading(id)

    override fun getTitle(id: ULong): String =
        io.github.kdroidfilter.webview.wry.getTitle(id)

    override fun canGoBack(id: ULong): Boolean =
        io.github.kdroidfilter.webview.wry.canGoBack(id)

    override fun canGoForward(id: ULong): Boolean =
        io.github.kdroidfilter.webview.wry.canGoForward(id)

    override fun evaluateJavascript(id: ULong, script: String, onResult: (String) -> Unit) {
        io.github.kdroidfilter.webview.wry.evaluateJavascript(
            id,
            script,
            object : JavaScriptCallback {
                override fun onResult(result: String) {
                    onResult(result)
                }
            },
        )
    }

    override fun loadUrl(id: ULong, url: String) =
        io.github.kdroidfilter.webview.wry.loadUrl(id, url)

    override fun goBack(id: ULong) =
        io.github.kdroidfilter.webview.wry.goBack(id)

    override fun goForward(id: ULong) =
        io.github.kdroidfilter.webview.wry.goForward(id)

    override fun reload(id: ULong) =
        io.github.kdroidfilter.webview.wry.reload(id)

    override fun stopLoading(id: ULong) =
        io.github.kdroidfilter.webview.wry.stopLoading(id)

    override fun focus(id: ULong) =
        io.github.kdroidfilter.webview.wry.focus(id)

    override fun setBounds(id: ULong, x: Int, y: Int, width: Int, height: Int) =
        io.github.kdroidfilter.webview.wry.setBounds(id, x, y, width, height)
}

/**
 * Single dedicated off-EDT daemon worker serializing all blocking Wry native calls in FIFO order.
 *
 * The AWT EDT submits work strictly fire-and-forget and never blocks waiting on this executor.
 */
internal object RaftDesktopWryWorker {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "raft-desktop-wry-worker").apply { isDaemon = true }
        }

    fun submit(task: () -> Unit) {
        executor.execute {
            check(!SwingUtilities.isEventDispatchThread()) {
                "Wry native work must never run on the AWT EDT"
            }
            task()
        }
    }
}

/**
 * Production off-EDT gateway around [WryWebViewPanel].
 *
 * This file is the ONLY production source allowed to reference [WryWebViewPanel] or
 * `io.github.kdroidfilter.webview.wry.*` (enforced by `scripts/ci/check_source_constraints.py`).
 */
class RaftDesktopWryGateway(
    override val component: Component,
    private val readWebviewId: () -> ULong?,
    private val registerNavigateListener: ((String) -> Boolean) -> Unit,
    private val revealComponent: () -> Unit,
    private val hideComponent: () -> Unit = {},
    private val nativeOps: RaftDesktopWryNativeOps = RealRaftDesktopWryNativeOps,
    private val worker: (() -> Unit) -> Unit = RaftDesktopWryWorker::submit,
    private val postToEdt: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
) : RaftDesktopWryBridge {
    @Volatile private var disposed = false
    private val pollInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val visibilityGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    override fun isReady(): Boolean = !disposed && readWebviewId() != null

    override fun addNavigateListener(listener: (String) -> Boolean) {
        registerNavigateListener(listener)
    }

    override fun reveal() {
        if (disposed) return
        visibilityGeneration.incrementAndGet()
        revealComponent()
    }

    override fun hide() {
        if (disposed) return
        hideComponent()
    }

    override fun poll(onResult: (RaftDesktopWryPollSnapshot) -> Unit): Boolean {
        if (disposed) return false
        val id = readWebviewId() ?: return false
        // Coalesce overlapping polls while the off-EDT worker is waiting on macOS Thread 0 (e.g.
        // during Chinese IME or live window resize) so pending polls never pile up in the queue.
        if (!pollInFlight.compareAndSet(false, true)) return false
        worker {
            if (disposed) {
                pollInFlight.set(false)
                return@worker
            }
            val snapshot =
                RaftDesktopWryPollSnapshot(
                    ipcMessages = runCatching { nativeOps.drainIpcMessages(id) }.getOrDefault(emptyList()),
                    isLoading = runCatching { nativeOps.isLoading(id) }.getOrDefault(true),
                    title = runCatching { nativeOps.getTitle(id) }.getOrNull().orEmpty(),
                    canGoBack = runCatching { nativeOps.canGoBack(id) }.getOrDefault(false),
                    canGoForward = runCatching { nativeOps.canGoForward(id) }.getOrDefault(false),
                )
            postToEdt {
                pollInFlight.set(false)
                if (!disposed) onResult(snapshot)
            }
        }
        return true
    }

    override fun evaluateJavaScript(script: String, onResult: (String) -> Unit) {
        if (disposed) return
        val id = readWebviewId()
        if (id == null) {
            postToEdt { if (!disposed) onResult("") }
            return
        }
        worker {
            if (disposed) return@worker
            var delivered = false
            val deliverOnce: (String) -> Unit = { result ->
                if (!delivered) {
                    delivered = true
                    postToEdt { if (!disposed) onResult(result) }
                }
            }
            val failed =
                runCatching {
                    nativeOps.evaluateJavascript(id, script, deliverOnce)
                }.isFailure
            if (failed) {
                deliverOnce("")
            }
        }
    }

    override fun loadUrl(url: String) {
        if (disposed) return
        val id = readWebviewId() ?: return
        worker {
            if (!disposed) runCatching { nativeOps.loadUrl(id, url) }
        }
    }

    override fun goBackIfPossible(onCompleted: () -> Unit) {
        if (disposed) return
        val id = readWebviewId() ?: return
        worker {
            if (disposed) return@worker
            runCatching {
                if (nativeOps.canGoBack(id)) nativeOps.goBack(id)
            }
            postToEdt { if (!disposed) onCompleted() }
        }
    }

    override fun goForwardIfPossible(onCompleted: () -> Unit) {
        if (disposed) return
        val id = readWebviewId() ?: return
        worker {
            if (disposed) return@worker
            runCatching {
                if (nativeOps.canGoForward(id)) nativeOps.goForward(id)
            }
            postToEdt { if (!disposed) onCompleted() }
        }
    }

    override fun reload(onCompleted: () -> Unit) {
        if (disposed) return
        val id = readWebviewId() ?: return
        worker {
            if (disposed) return@worker
            runCatching { nativeOps.reload(id) }
            postToEdt { if (!disposed) onCompleted() }
        }
    }

    override fun stopLoading(onCompleted: () -> Unit) {
        if (disposed) return
        val id = readWebviewId() ?: return
        worker {
            if (disposed) return@worker
            runCatching { nativeOps.stopLoading(id) }
            postToEdt { if (!disposed) onCompleted() }
        }
    }

    fun requestFocus() {
        if (disposed) return
        val id = readWebviewId() ?: return
        worker {
            if (!disposed) runCatching { nativeOps.focus(id) }
        }
    }

    fun moveOffScreen(width: Int, height: Int) {
        val id = readWebviewId() ?: return
        val generation = visibilityGeneration.incrementAndGet()
        val clampedWidth = width.coerceAtLeast(1)
        val clampedHeight = height.coerceAtLeast(1)
        worker {
            if (visibilityGeneration.get() != generation) return@worker
            runCatching {
                nativeOps.setBounds(
                    id,
                    -OffscreenOrigin,
                    -OffscreenOrigin,
                    clampedWidth,
                    clampedHeight,
                )
            }
        }
    }

    override fun dispose() {
        disposed = true
    }

    companion object {
        private const val OffscreenOrigin = 32_000

        fun create(
            policy: RaftDesktopWebViewPolicy,
            ephemeral: Boolean,
            dataDirectory: String? =
                if (ephemeral) {
                    null
                } else {
                    File(build.raft.app.data.RaftDesktopPaths.dataDir, "webview").apply { mkdirs() }.path
                },
        ): RaftDesktopWryGateway {
            val initialUrl = policy.initialUrl() ?: "about:blank"
            val panel =
                WryWebViewPanel(
                    initialUrl = initialUrl,
                    dataDirectory = dataDirectory,
                    initScript = policy.documentStartScript(),
                    supportZoom = false,
                    // #wg-mobile-sync:#242: Wry's native createWebview places a new WKWebView at
                    // (0, 0) until its 16 ms boundsTimer moves it off-screen. Keep the WKWebView
                    // backdrop transparent so it never flashes a white rectangle over the top-left
                    // of the window before placement; RaftDesktopWryNativeView supplies the white
                    // backdrop once revealed in the target pane.
                    backgroundColor = Rgba(0u, 0u, 0u, 0u),
                    transparent = true,
                    enableNavigationGestures = false,
                    incognito = ephemeral,
                    autoplayWithoutUserInteraction = false,
                )
            var gatewayRef: RaftDesktopWryGateway? = null
            // WryWebViewPanel's constructor attaches an internal MouseAdapter (`WryWebViewPanel$2`)
            // to `host` whose `mousePressed` runs `requestWebViewFocus()` -> `_dispatch_sync_f`
            // directly on the AWT EDT. Replace it with our off-EDT focus dispatch.
            RaftDesktopWryPanelAccess.replaceBlockingFocusMouseListener(panel) {
                gatewayRef?.requestFocus()
            }
            val nativeView =
                RaftDesktopWryNativeView(
                    content = panel,
                    onMoveOffScreen = { width, height ->
                        RaftDesktopWryPanelAccess.clearLastBounds(panel)
                        gatewayRef?.moveOffScreen(width, height)
                    },
                    onResyncBounds = {
                        RaftDesktopWryPanelAccess.resyncBounds(panel)
                    },
                )
            return RaftDesktopWryGateway(
                component = nativeView,
                readWebviewId = { RaftDesktopWryPanelAccess.readWebviewId(panel) },
                registerNavigateListener = { listener -> panel.addNavigateListener(listener) },
                revealComponent = { nativeView.setRevealed(true) },
                hideComponent = { nativeView.setRevealed(false) },
            ).also { gatewayRef = it }
        }
    }
}

/**
 * Reflection into [WryWebViewPanel]'s private handle / bounds / host members (pinned artifact
 * verified by `:desktopApp:verifyPinnedDesktopJars` and unit-tested by [available]).
 */
object RaftDesktopWryPanelAccess {
    private val webviewIdField: Field? = member { WryWebViewPanel::class.java.getDeclaredField("webviewId") }
    private val lastBoundsField: Field? = member { WryWebViewPanel::class.java.getDeclaredField("lastBounds") }
    private val updateBoundsMethod: Method? = member { WryWebViewPanel::class.java.getDeclaredMethod("updateBounds") }
    private val hostField: Field? = member { WryWebViewPanel::class.java.getDeclaredField("host") }

    /** Whether every member this gateway needs exists on the pinned Wry jar. */
    val available: Boolean
        get() = webviewIdField != null && lastBoundsField != null && updateBoundsMethod != null && hostField != null

    /**
     * Reads [WryWebViewPanel.webviewId]. Must be called on the AWT EDT (the sole writer of that
     * non-volatile field inside [WryWebViewPanel]).
     */
    internal fun readWebviewId(panel: WryWebViewPanel): ULong? =
        runCatching { webviewIdField?.get(panel) as? ULong }.getOrNull()

    /** Clears Wry's cached bounds so the next [resyncBounds] always pushes fresh on-screen bounds. */
    internal fun clearLastBounds(panel: WryWebViewPanel) {
        runCatching { lastBoundsField?.set(panel, null) }
    }

    /**
     * Re-runs [WryWebViewPanel.updateBounds] on the AWT EDT. On macOS/Linux that schedules Wry's
     * 16 ms bounds timer which calls `set_bounds` (`_dispatch_async_f`, never `_dispatch_sync_f`).
     */
    internal fun resyncBounds(panel: WryWebViewPanel) {
        runCatching {
            lastBoundsField?.set(panel, null)
            updateBoundsMethod?.invoke(panel)
        }
    }

    /**
     * Strips `WryWebViewPanel`'s built-in `MouseAdapter` on `host` (which calls
     * `requestWebViewFocus()` -> `_dispatch_sync_f` on the AWT EDT) and installs a non-blocking
     * replacement that delegates to [onFocusRequested]. Returns how many blocking listeners were
     * removed.
     */
    fun replaceBlockingFocusMouseListener(
        panel: WryWebViewPanel,
        onFocusRequested: () -> Unit,
    ): Int {
        val host =
            runCatching { hostField?.get(panel) as? Component }.getOrNull()
                ?: panel.components.firstOrNull { it is Canvas }
                ?: return 0
        return replaceBlockingFocusMouseListenersOnHost(
            host = host,
            blockedListenerPrefix = WryBlockingFocusListenerPrefix,
            onFocusRequested = onFocusRequested,
        )
    }

    fun replaceBlockingFocusMouseListenersOnHost(
        host: Component,
        blockedListenerPrefix: String = WryBlockingFocusListenerPrefix,
        onFocusRequested: () -> Unit,
    ): Int {
        var removed = 0
        for (listener in host.mouseListeners) {
            if (listener.javaClass.name.startsWith(blockedListenerPrefix)) {
                host.removeMouseListener(listener)
                removed += 1
            }
        }
        host.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    onFocusRequested()
                }
            },
        )
        return removed
    }

    const val WryBlockingFocusListenerPrefix = "io.github.kdroidfilter.webview.wry.WryWebViewPanel"

    private fun <T : java.lang.reflect.AccessibleObject> member(get: () -> T): T? =
        runCatching { get().apply { isAccessible = true } }.getOrNull()
}
