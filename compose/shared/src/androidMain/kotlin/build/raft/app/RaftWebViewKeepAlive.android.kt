package build.raft.app

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Android keep-alive for [RaftWebViewConfig.keepAliveKey]: one parked [RaftAndroidSandboxWebView]
 * at a time, created on a [MutableContextWrapper] so a parked view holds the application context,
 * never a finished Activity. Main thread only (every caller is a composable or a UI callback).
 *
 * A parked view is dropped (destroyed) when another key asks, on [clear], after [KEEP_ALIVE_MS]
 * unused, or once the app's light/dark mode is no longer the one it was built in
 * ([RaftAndroidSandboxWebView.builtDark]): its document read `prefers-color-scheme` from the
 * construction context, so a page gets a fresh WebView in the current mode instead.
 */
internal object RaftAndroidWebViewKeepAlive {
    private const val LOG_TAG = "SlockWebViewKeepAlive"
    private const val KEEP_ALIVE_MS = 5 * 60 * 1000L

    private var parked: RaftAndroidSandboxWebView? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val expire = Runnable { drop("expired") }

    /** The view parked under [key] (now owned by the caller), or null. A view under another key is destroyed. */
    fun take(key: String, hostContext: Context, events: RaftWebViewEvents): RaftAndroidSandboxWebView? {
        dropIfOtherThemeMode()
        val view = parked ?: return null
        handler.removeCallbacks(expire)
        parked = null
        if (view.isDestroyed || view.keepAliveKey != key) {
            view.destroy()
            raftLogInfo(LOG_TAG, "keep_alive result=dropped reason=other_key")
            return null
        }
        view.attachFromKeepAlive(hostContext, events)
        raftLogInfo(LOG_TAG, "keep_alive result=reused")
        return view
    }

    /** Parks [view] when it has a key; false when the caller must destroy it. */
    fun park(view: RaftAndroidSandboxWebView): Boolean {
        if (view.isDestroyed || view.keepAliveKey == null) return false
        val previous = parked
        if (previous != null && previous !== view) previous.destroy()
        view.detachForKeepAlive()
        parked = view
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, KEEP_ALIVE_MS)
        raftLogInfo(LOG_TAG, "keep_alive result=parked")
        return true
    }

    fun prewarm(context: Context, config: RaftWebViewConfig) {
        val key = config.keepAliveKey ?: return
        dropIfOtherThemeMode()
        val current = parked
        if (current != null && !current.isDestroyed && current.keepAliveKey == key) return
        // Built on the page's (Activity) context, whose theme carries the app's light/dark mode
        // (the application context's does not on API 29-30); park() switches it to the
        // application context at once, so no Activity is held.
        val view = RaftAndroidSandboxWebView(MutableContextWrapper(context), RaftWebViewEvents())
        view.applyConfig(config)
        park(view)
        raftLogInfo(LOG_TAG, "keep_alive result=prewarmed")
    }

    fun kept(key: String): RaftAndroidSandboxWebView? {
        dropIfOtherThemeMode()
        return parked?.takeIf { !it.isDestroyed && it.keepAliveKey == key }
    }

    /** Drops the parked view when the app's light/dark mode changed since it was built. */
    private fun dropIfOtherThemeMode() {
        val view = parked ?: return
        if (view.builtDark != RaftAppThemeState.isDark) drop("theme_mode_changed")
    }

    fun drop(reason: String) {
        handler.removeCallbacks(expire)
        val view = parked ?: return
        parked = null
        view.destroy()
        raftLogInfo(LOG_TAG, "keep_alive result=dropped reason=$reason")
    }
}

internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? =
    runOnMainOrNull { RaftAndroidWebViewKeepAlive.kept(key)?.keptDocument() }

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean =
    runOnMainOrNull { RaftAndroidWebViewKeepAlive.kept(key)?.evaluateInTopDocument(origin, script) } == true

internal actual fun raftWebViewKeepAliveClear() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        RaftAndroidWebViewKeepAlive.drop("cleared")
    } else {
        Handler(Looper.getMainLooper()).post { RaftAndroidWebViewKeepAlive.drop("cleared") }
    }
}

@Composable
internal actual fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?) {
    val context = LocalContext.current
    LaunchedEffect(config) {
        if (config?.keepAliveKey != null) RaftAndroidWebViewKeepAlive.prewarm(context, config)
    }
}

private inline fun <T> runOnMainOrNull(block: () -> T?): T? =
    if (Looper.myLooper() == Looper.getMainLooper()) block() else null
