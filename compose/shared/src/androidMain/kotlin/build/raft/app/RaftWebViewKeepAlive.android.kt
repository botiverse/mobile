package build.raft.app

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Android keep-alive for [RaftWebViewConfig.keepAliveKey]: one parked [RaftAndroidSandboxWebView]
 * at a time, created on a [MutableContextWrapper] so a parked view holds the application context,
 * never a finished Activity. Main thread only (every caller is a composable or a UI callback).
 *
 * A parked view is dropped (destroyed) when another key asks, on [clear], or after [KEEP_ALIVE_MS]
 * unused.
 */
internal object RaftAndroidWebViewKeepAlive {
    private const val LOG_TAG = "SlockWebViewKeepAlive"
    private const val KEEP_ALIVE_MS = 5 * 60 * 1000L

    private var parked: RaftAndroidSandboxWebView? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val expire = Runnable { drop("expired") }

    /** The view parked under [key] (now owned by the caller), or null. A view under another key is destroyed. */
    fun take(key: String, hostContext: Context, events: RaftWebViewEvents): RaftAndroidSandboxWebView? {
        val view = parked ?: return null
        handler.removeCallbacks(expire)
        parked = null
        if (view.isDestroyed || view.keepAliveKey != key) {
            view.destroy()
            Log.i(LOG_TAG, "keep_alive result=dropped reason=other_key")
            return null
        }
        view.attachFromKeepAlive(hostContext, events)
        Log.i(LOG_TAG, "keep_alive result=reused")
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
        Log.i(LOG_TAG, "keep_alive result=parked")
        return true
    }

    fun prewarm(context: Context, config: RaftWebViewConfig) {
        val key = config.keepAliveKey ?: return
        val current = parked
        if (current != null && !current.isDestroyed && current.keepAliveKey == key) return
        val view = RaftAndroidSandboxWebView(MutableContextWrapper(context.applicationContext), RaftWebViewEvents())
        view.applyConfig(config)
        park(view)
        Log.i(LOG_TAG, "keep_alive result=prewarmed")
    }

    fun kept(key: String): RaftAndroidSandboxWebView? =
        parked?.takeIf { !it.isDestroyed && it.keepAliveKey == key }

    fun drop(reason: String) {
        handler.removeCallbacks(expire)
        val view = parked ?: return
        parked = null
        view.destroy()
        Log.i(LOG_TAG, "keep_alive result=dropped reason=$reason")
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
