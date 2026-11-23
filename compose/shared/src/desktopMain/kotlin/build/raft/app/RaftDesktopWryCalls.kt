package build.raft.app

import io.github.kdroidfilter.webview.wry.JavaScriptCallback
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import javax.swing.SwingUtilities

/**
 * The only place desktop code makes a Wry call that runs on the macOS main thread (#317).
 *
 * On macOS Wry runs load, stop, back / forward, reload, focus, JavaScript and cookie calls on the
 * main thread and waits for it (a synchronous `dispatch_sync` to the main queue). The AWT event
 * thread, Compose desktop's UI thread, must never make such a call: while the user types with an
 * input method (the candidate window asks where the text is) or resizes the window, AppKit's main
 * thread waits for the AWT event thread (`LWCToolkit.invokeAndWait`) in a run loop mode that does
 * not run the main queue. Both threads then wait for each other and the app freezes for good
 * (artin's 1.28 sample: the AWT event thread in `evaluate_javascript` → `_dispatch_sync_f_slow`,
 * the main thread in `LWCToolkit.doAWTRunLoopImpl`).
 *
 * So these calls run on one background thread ([RaftSerialCallLane]), in order, and nothing waits
 * for them; answers come back on the AWT event thread. The WebView id is read on the AWT event
 * thread, which owns the panel. A call on a WebView that is gone fails quietly, as the panel's own
 * methods do. The panel methods that make these calls (`loadUrl`, `evaluateJavaScript`, ...) hop
 * back to the AWT event thread themselves, so they are never used: the static gate
 * `check_wry_main_thread_calls_through_owner` (scripts/ci/check_source_constraints.py) keeps every
 * such call in this file.
 *
 * What stays on the AWT event thread: reads of Wry's own state (`isLoading`, `getTitle`,
 * `canGoBack`, `canGoForward`, `drainIpcMessages`), and `setBounds` / `destroyWebview`, which Wry
 * hands to the main thread without waiting.
 */
internal object RaftDesktopWryCalls {
    private val lane = RaftSerialCallLane("raft-wry-calls") { SwingUtilities.invokeLater(it) }

    /**
     * Runs [script] in the page. [answer] gets Wry's answer (a JSON literal; "" when the WebView is
     * gone or the call fails, as the panel's own method) on whichever thread has it: the main
     * thread for a page answer, the calling or the lane's thread for a failure. Callers hand it on
     * with `invokeLater`.
     */
    fun evaluateJavaScript(
        panel: WryWebViewPanel,
        script: String,
        answer: (String) -> Unit,
    ) {
        val id = RaftDesktopWryPanelAccess.webviewId(panel)
        if (id == null) {
            answer("")
            return
        }
        lane.post {
            try {
                io.github.kdroidfilter.webview.wry.evaluateJavascript(
                    id,
                    script,
                    object : JavaScriptCallback {
                        override fun onResult(result: String) = answer(result)
                    },
                )
            } catch (_: Exception) {
                answer("")
            }
        }
    }

    fun loadUrl(
        panel: WryWebViewPanel,
        url: String,
    ) = runOnLane(panel) { id -> io.github.kdroidfilter.webview.wry.loadUrl(id, url) }

    fun stopLoading(panel: WryWebViewPanel) = runOnLane(panel) { id -> io.github.kdroidfilter.webview.wry.stopLoading(id) }

    fun goBack(panel: WryWebViewPanel) = runOnLane(panel) { id -> io.github.kdroidfilter.webview.wry.goBack(id) }

    fun goForward(panel: WryWebViewPanel) = runOnLane(panel) { id -> io.github.kdroidfilter.webview.wry.goForward(id) }

    fun reload(panel: WryWebViewPanel) = runOnLane(panel) { id -> io.github.kdroidfilter.webview.wry.reload(id) }

    /** Keyboard focus into the page (the panel's click-to-focus, rerouted by [RaftDesktopWryPanelAccess.routeClickFocus]). */
    fun focus(panel: WryWebViewPanel) = runOnLane(panel) { id -> io.github.kdroidfilter.webview.wry.focus(id) }

    /** No-op until the native WebView exists, as the panel's own methods. */
    private fun runOnLane(
        panel: WryWebViewPanel,
        call: (ULong) -> Unit,
    ) {
        val id = RaftDesktopWryPanelAccess.webviewId(panel) ?: return
        lane.post { call(id) }
    }
}
