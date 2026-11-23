package build.raft.app

import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    fun take(
        key: String,
        hostContext: Context,
        events: RaftWebViewEvents,
        storagePartition: RaftWebViewStoragePartition = RaftWebViewStoragePartition.Default,
    ): RaftAndroidSandboxWebView? {
        if (storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty) return null
        dropIfOtherThemeMode()
        val view = parked ?: return null
        handler.removeCallbacks(expire)
        parked = null
        if (
            view.isDestroyed ||
            view.keepAliveKey != key ||
            view.storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty
        ) {
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
        if (
            view.isDestroyed ||
            view.keepAliveKey == null ||
            view.storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty
        ) {
            return false
        }
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
        if (config.effectiveStoragePartition == RaftWebViewStoragePartition.IsolatedThirdParty) return
        dropIfOtherThemeMode()
        val current = parked
        if (current != null && !current.isDestroyed && current.keepAliveKey == key) return
        // Built on the page's (Activity) context, whose theme carries the app's light/dark mode
        // (the application context's does not on API 29-30); park() switches it to the
        // application context at once, so no Activity is held.
        val view =
            RaftAndroidSandboxWebView(
                MutableContextWrapper(context),
                RaftWebViewEvents(),
                initialStoragePartition = config.effectiveStoragePartition,
            )
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

internal interface RaftAndroidWebStorageClearTarget {
    fun deleteAllData()
}

internal interface RaftAndroidCookieClearTarget {
    suspend fun clearOrigin(origin: String)
    suspend fun clearAll()
    fun flush()
}

internal suspend fun clearAndroidWebViewAuthStorage(
    origins: Set<String>,
    dropKeepAlive: () -> Unit,
    webStorage: RaftAndroidWebStorageClearTarget?,
    cookies: RaftAndroidCookieClearTarget?,
) {
    runCatching { dropKeepAlive() }
    // Chromium's WebStorage.deleteOrigin() (aw_quota_manager_bridge.cc DeleteOriginFramework)
    // only clears FILE_SYSTEMS | INDEXEDDB and omits LOCAL_STORAGE; only deleteAllData()
    // (DeleteAllDataFramework) clears localStorage where slock_access_token is stored.
    // Always call deleteAllData() so sign-out / account switch never leaves a web access token
    // behind on shared devices, while still clearing cookies per-origin so third-party cookie
    // logins in InAppBrowserPage survive.
    runCatching { webStorage?.deleteAllData() }
    if (cookies != null) {
        if (origins.isNotEmpty()) {
            for (origin in origins) {
                try {
                    cookies.clearOrigin(origin)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                }
            }
        } else {
            try {
                cookies.clearAll()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
            }
        }
        runCatching { cookies.flush() }
    }
}

internal actual suspend fun raftClearPlatformWebViewAuthStorage(origins: Set<String>) {
    val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
    val clearAction: suspend () -> Unit = {
        val storage = runCatching { android.webkit.WebStorage.getInstance() }.getOrNull()
        val cookieManager = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
        clearAndroidWebViewAuthStorage(
            origins = origins,
            dropKeepAlive = { RaftAndroidWebViewKeepAlive.drop("logout") },
            webStorage =
                storage?.let { instance ->
                    object : RaftAndroidWebStorageClearTarget {
                        override fun deleteAllData() = instance.deleteAllData()
                    }
                },
            cookies =
                cookieManager?.let { manager ->
                    object : RaftAndroidCookieClearTarget {
                        override suspend fun clearOrigin(origin: String) {
                            clearAndroidCookiesForOrigin(manager, origin)
                        }

                        override suspend fun clearAll() {
                            withTimeoutOrNull(2_000L) {
                                suspendCancellableCoroutine<Unit> { cont ->
                                    runCatching {
                                        manager.removeAllCookies {
                                            if (cont.isActive) cont.resume(Unit)
                                        }
                                    }.onFailure {
                                        if (cont.isActive) cont.resume(Unit)
                                    }
                                }
                            }
                        }

                        override fun flush() {
                            manager.flush()
                        }
                    }
                },
        )
    }
    if (Looper.myLooper() == mainLooper) {
        clearAction()
    } else {
        try {
            withContext(Dispatchers.Main.immediate) { clearAction() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // Fallback when Main dispatcher is absent (unit tests without Robolectric Main).
            Handler(mainLooper).post {
                runCatching { RaftAndroidWebViewKeepAlive.drop("logout") }
                runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
            }
        }
    }
}

private suspend fun clearAndroidCookiesForOrigin(
    cookieManager: android.webkit.CookieManager,
    origin: String,
) {
    val cookieHeader = runCatching { cookieManager.getCookie(origin) }.getOrNull().orEmpty()
    if (cookieHeader.isBlank()) return
    val host = origin.substringAfter("://", "").substringBefore(':').lowercase().trimEnd('.')
    val secureSuffix = if (origin.startsWith("https://", ignoreCase = true)) "; Secure" else ""
    val cookieNames =
        cookieHeader
            .split(';')
            .mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
            .distinct()
    val domains =
        buildList {
            add(null)
            if (host.isNotEmpty()) {
                add(host)
                val dotIndex = host.indexOf('.')
                if (dotIndex > 0 && host.indexOf('.', dotIndex + 1) > 0) {
                    add(host.substring(dotIndex + 1))
                }
            }
        }
    for (name in cookieNames) {
        for (domain in domains) {
            val domainAttr = if (domain != null) "; Domain=.$domain" else ""
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$domainAttr$secureSuffix"
            withTimeoutOrNull(500L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    runCatching {
                        cookieManager.setCookie(origin, expired) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }.onFailure {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }
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
