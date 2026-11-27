package build.raft.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import build.raft.app.thread.key
import build.raft.webcontainer.AndroidWebContainerProfileStore
import build.raft.webcontainer.WebContainerAndroidKeepAlive
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.parseWebContainerOrigin
import build.raft.webcontainer.pool.WebMemoryTrimLevel
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
    /**
     * Safety ceiling for a parked background prewarm (e.g. `raft-app-warm`) to reach
     * `onPageFinished`. If it has not finished by then, abort and drop it so a slow/stuck
     * background load cannot clog Android's single Chromium renderer process for tens of seconds.
     */
    internal const val PREWARM_LOAD_TIMEOUT_MS = 12_000L

    private var parked: RaftAndroidSandboxWebView? = null
    private val appTabPool = LinkedHashMap<String, RaftAndroidSandboxWebView>()
    private var parkedPrewarmStartedAtMs: Long = 0L
    private var parkedPrewarmTraceHandle: MobileWebViewLoadAttemptHandle? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val expire = Runnable { drop("expired") }
    private val prewarmLoadTimeout = Runnable { abortInFlightPrewarmOnTimeout() }

    private fun isAppTabKey(key: String?): Boolean =
        key != null && key.startsWith(RAFT_APP_BROWSER_KEEP_ALIVE_PREFIX)

    internal var nowMs: () -> Long = { SystemClock.elapsedRealtime() }
    internal var prewarmLoadTimeoutMs: Long = PREWARM_LOAD_TIMEOUT_MS
    internal var logInfo: (String) -> Unit = { message -> raftLogInfo(LOG_TAG, message) }
    internal var startPrewarmTrace: (diagnosticsSurface: String?) -> MobileWebViewLoadAttemptHandle = { surface ->
        MobileWebViewLoadTrace.startPrewarm(diagnosticsSurface = surface, useWebContainer = false)
    }

    /** The view parked under [key] (now owned by the caller), or null. A view under another key or failed load is destroyed. */
    fun take(
        key: String,
        hostContext: Context,
        events: RaftWebViewEvents,
        storagePartition: RaftWebViewStoragePartition = RaftWebViewStoragePartition.Default,
    ): RaftAndroidSandboxWebView? {
        if (storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty) return null
        if (isAppTabKey(key)) {
            dropAppTabsIfOtherThemeMode()
            val tabView = appTabPool.remove(key) ?: return null
            if (
                tabView.isDestroyed ||
                tabView.mainFrameLoadFailed ||
                tabView.hasPendingRenderProcessRestore ||
                tabView.storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty
            ) {
                tabView.destroy()
                logInfo("keep_alive result=dropped scope=app_tab reason=unhealthy")
                return null
            }
            tabView.attachFromKeepAlive(hostContext, events)
            logInfo("keep_alive result=reused scope=app_tab remaining=${appTabPool.size}")
            return tabView
        }
        dropIfOtherThemeMode()
        val view = parked ?: return null
        val prewarmHandle = parkedPrewarmTraceHandle
        handler.removeCallbacks(expire)
        handler.removeCallbacks(prewarmLoadTimeout)
        parked = null
        parkedPrewarmStartedAtMs = 0L
        parkedPrewarmTraceHandle = null
        if (
            view.isDestroyed ||
            view.mainFrameLoadFailed ||
            view.hasPendingRenderProcessRestore ||
            view.keepAliveKey != key ||
            view.storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty
        ) {
            val reason =
                when {
                    view.mainFrameLoadFailed -> "load_error"
                    view.hasPendingRenderProcessRestore -> "render_process_gone"
                    else -> "other_key"
                }
            finishPrewarmHandleForDrop(prewarmHandle, reason)
            view.destroy()
            logInfo("keep_alive result=dropped reason=$reason")
            return null
        }
        if (!view.isMainFrameLoadInFlight) {
            prewarmHandle?.finish(
                outcome = MobileWebViewLoadOutcome.Ok,
                errorCode = MobileWebViewLoadErrorCode.None,
                uiErrorShown = false,
            )
        }
        view.attachFromKeepAlive(hostContext, events)
        logInfo("keep_alive result=reused")
        return view
    }

    /** Parks [view] when it has a key and has not failed its main-frame load; false when the caller must destroy it. */
    fun park(view: RaftAndroidSandboxWebView): Boolean {
        val viewKey = view.keepAliveKey
        if (isAppTabKey(viewKey)) {
            val key = viewKey!!
            if (
                view.isDestroyed ||
                view.mainFrameLoadFailed ||
                view.hasPendingRenderProcessRestore ||
                view.storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty ||
                view.builtDark != RaftAppThemeState.isDark ||
                !RaftMobileWebPanelCoordinator.process.isKeepAliveEligible(key)
            ) {
                if (appTabPool[key] === view) {
                    appTabPool.remove(key)
                }
                return false
            }
            dropAppTabsIfOtherThemeMode()
            appTabPool.remove(key)?.takeIf { it !== view }?.destroy()
            while (appTabPool.size >= RAFT_APP_BROWSER_LIVE_POOL_SIZE) {
                val eldestKey = appTabPool.keys.firstOrNull() ?: break
                appTabPool.remove(eldestKey)?.destroy()
            }
            view.detachForKeepAlive()
            appTabPool[key] = view
            logInfo("keep_alive result=parked scope=app_tab size=${appTabPool.size}")
            return true
        }
        if (
            view.isDestroyed ||
            view.mainFrameLoadFailed ||
            view.hasPendingRenderProcessRestore ||
            view.keepAliveKey == null ||
            view.storagePartition == RaftWebViewStoragePartition.IsolatedThirdParty
        ) {
            if (parked === view) {
                val prewarmHandle = parkedPrewarmTraceHandle
                handler.removeCallbacks(expire)
                handler.removeCallbacks(prewarmLoadTimeout)
                parked = null
                parkedPrewarmStartedAtMs = 0L
                parkedPrewarmTraceHandle = null
                val reason =
                    when {
                        view.hasPendingRenderProcessRestore -> "render_process_gone"
                        view.mainFrameLoadFailed -> "load_error"
                        else -> "refused"
                    }
                finishPrewarmHandleForDrop(prewarmHandle, reason)
            }
            if (view.mainFrameLoadFailed) {
                logInfo("keep_alive result=refused reason=load_error")
            } else if (view.hasPendingRenderProcessRestore) {
                logInfo("keep_alive result=refused reason=render_process_gone")
            }
            return false
        }
        val previous = parked
        if (previous != null && previous !== view) {
            val previousHandle = parkedPrewarmTraceHandle
            parkedPrewarmTraceHandle = null
            if (previous.isMainFrameLoadInFlight) {
                finishPrewarmHandleForDrop(previousHandle, "other_key")
            }
            previous.destroy()
        }
        view.detachForKeepAlive()
        parked = view
        handler.removeCallbacks(expire)
        handler.removeCallbacks(prewarmLoadTimeout)
        handler.postDelayed(expire, KEEP_ALIVE_MS)
        if (view.isMainFrameLoadInFlight) {
            parkedPrewarmStartedAtMs = nowMs()
            handler.postDelayed(prewarmLoadTimeout, prewarmLoadTimeoutMs)
        } else {
            parkedPrewarmStartedAtMs = 0L
        }
        logInfo("keep_alive result=parked")
        return true
    }

    fun prewarm(context: Context, config: RaftWebViewConfig) {
        val key = config.keepAliveKey ?: return
        if (config.effectiveStoragePartition == RaftWebViewStoragePartition.IsolatedThirdParty) return
        dropIfOtherThemeMode()
        dropIfLoadFailed()
        val current = parked
        if (
            current != null &&
            !current.isDestroyed &&
            !current.mainFrameLoadFailed &&
            !current.hasPendingRenderProcessRestore &&
            current.keepAliveKey == key
        ) {
            return
        }
        val prewarmHandle = startPrewarmTrace(config.diagnosticsSurface)
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
        if (park(view)) {
            if (view.isMainFrameLoadInFlight) {
                parkedPrewarmTraceHandle = prewarmHandle
            } else {
                prewarmHandle.finish(
                    outcome = MobileWebViewLoadOutcome.Ok,
                    errorCode = MobileWebViewLoadErrorCode.None,
                    uiErrorShown = false,
                )
            }
            logInfo("keep_alive result=prewarmed")
        } else {
            val errorCode =
                when {
                    view.hasPendingRenderProcessRestore -> MobileWebViewLoadErrorCode.RenderProcessGone
                    view.mainFrameLoadFailed -> MobileWebViewLoadErrorCode.NetworkError
                    else -> MobileWebViewLoadErrorCode.Other
                }
            prewarmHandle.finish(
                outcome = MobileWebViewLoadOutcome.Failed,
                errorCode = errorCode,
                uiErrorShown = false,
            )
            view.destroy()
        }
    }

    /**
     * Yields any still-loading parked prewarm when a user-initiated foreground WebView opens on
     * Android (e.g. `settings_marketplace`, `in_app_browser`). A cleanly finished parked view
     * (`!isMainFrameLoadInFlight`) is preserved so normal warm-hit reuse stays intact, and a
     * foreground page opening for the exact same [foregroundKeepAliveKey] keeps its own entry to
     * adopt via [take].
     */
    fun abortInFlightPrewarmForForeground(
        foregroundSurface: String,
        foregroundKeepAliveKey: String? = null,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val view = parked ?: return false
        if (foregroundKeepAliveKey != null && view.keepAliveKey == foregroundKeepAliveKey) return false
        if (!view.isMainFrameLoadInFlight) return false
        val elapsedMs =
            if (parkedPrewarmStartedAtMs > 0L) (nowMs() - parkedPrewarmStartedAtMs).coerceAtLeast(0L) else -1L
        val prewarmHandle = parkedPrewarmTraceHandle
        handler.removeCallbacks(expire)
        handler.removeCallbacks(prewarmLoadTimeout)
        parked = null
        parkedPrewarmStartedAtMs = 0L
        parkedPrewarmTraceHandle = null
        prewarmHandle?.finish(
            outcome = MobileWebViewLoadOutcome.CancelledForForeground,
            errorCode = MobileWebViewLoadErrorCode.Other,
            uiErrorShown = false,
        )
        view.destroy()
        logInfo(
            "keep_alive result=aborted_for_foreground reason=prewarm_aborted_for_foreground " +
                "surface=${sanitizeKeepAliveSurfaceToken(foregroundSurface)} elapsedMs=$elapsedMs",
        )
        return true
    }

    /** Clears the prewarm load timeout once a parked view finishes its main-frame load cleanly. */
    fun onParkedLoadFinished(view: RaftAndroidSandboxWebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { onParkedLoadFinished(view) }
            return
        }
        if (parked !== view) return
        val prewarmHandle = parkedPrewarmTraceHandle
        handler.removeCallbacks(prewarmLoadTimeout)
        parkedPrewarmStartedAtMs = 0L
        parkedPrewarmTraceHandle = null
        prewarmHandle?.finish(
            outcome = MobileWebViewLoadOutcome.Ok,
            errorCode = MobileWebViewLoadErrorCode.None,
            uiErrorShown = false,
        )
    }

    private fun abortInFlightPrewarmOnTimeout() {
        val view = parked ?: return
        if (!view.isMainFrameLoadInFlight) {
            parkedPrewarmStartedAtMs = 0L
            return
        }
        val elapsedMs =
            if (parkedPrewarmStartedAtMs > 0L) (nowMs() - parkedPrewarmStartedAtMs).coerceAtLeast(0L) else -1L
        val prewarmHandle = parkedPrewarmTraceHandle
        handler.removeCallbacks(expire)
        handler.removeCallbacks(prewarmLoadTimeout)
        parked = null
        parkedPrewarmStartedAtMs = 0L
        parkedPrewarmTraceHandle = null
        prewarmHandle?.finish(
            outcome = MobileWebViewLoadOutcome.Timeout,
            errorCode = MobileWebViewLoadErrorCode.Timeout,
            uiErrorShown = false,
        )
        view.destroy()
        logInfo("keep_alive result=aborted_timeout reason=prewarm_load_timeout elapsedMs=$elapsedMs")
    }

    fun kept(key: String): RaftAndroidSandboxWebView? {
        dropIfOtherThemeMode()
        dropIfLoadFailed()
        return parked?.takeIf {
            !it.isDestroyed && !it.mainFrameLoadFailed && !it.hasPendingRenderProcessRestore && it.keepAliveKey == key
        }
    }

    /** Drops the parked view when the app's light/dark mode changed since it was built. */
    private fun dropIfOtherThemeMode() {
        val view = parked ?: return
        if (view.builtDark != RaftAppThemeState.isDark) drop("theme_mode_changed")
    }

    /** Drops the parked view when its main-frame load failed while parked or before reuse. */
    private fun dropIfLoadFailed() {
        val view = parked ?: return
        if (view.mainFrameLoadFailed) drop("load_error")
    }

    /** Drops any parked App tab views whose light/dark mode no longer matches. */
    private fun dropAppTabsIfOtherThemeMode() {
        val staleKeys =
            appTabPool.entries
                .filter { (_, view) -> view.builtDark != RaftAppThemeState.isDark || view.isDestroyed || view.mainFrameLoadFailed }
                .map { it.key }
        for (key in staleKeys) {
            appTabPool.remove(key)?.destroy()
            RaftMobileWebPanelCoordinator.process.onTabKeepAliveDropped(key)
        }
    }

    /** Drops and destroys [view] if it is currently parked (artifact slot or App tab pool). */
    fun dropIfSame(view: RaftAndroidSandboxWebView, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { dropIfSame(view, reason) }
            return
        }
        val matchingTabKey = appTabPool.entries.firstOrNull { it.value === view }?.key
        if (matchingTabKey != null) {
            appTabPool.remove(matchingTabKey)
            view.destroy()
            RaftMobileWebPanelCoordinator.process.onTabKeepAliveDropped(matchingTabKey)
            logInfo("keep_alive result=dropped scope=app_tab reason=$reason")
            return
        }
        if (parked !== view) return
        val prewarmHandle = parkedPrewarmTraceHandle
        handler.removeCallbacks(expire)
        handler.removeCallbacks(prewarmLoadTimeout)
        parked = null
        parkedPrewarmStartedAtMs = 0L
        parkedPrewarmTraceHandle = null
        finishPrewarmHandleForDrop(prewarmHandle, reason)
        view.destroy()
        logInfo("keep_alive result=dropped reason=$reason")
    }

    /** Drops and destroys the parked view only if its key matches [key]. */
    fun dropIfKey(key: String, reason: String) {
        if (appTabPool.containsKey(key)) {
            evict(key, reason)
        }
        if (parked?.keepAliveKey == key) drop(reason)
    }

    /** Evicts and destroys any parked view under [key]. */
    fun evict(key: String, reason: String = "evicted") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { evict(key, reason) }
            return
        }
        appTabPool.remove(key)?.let { tabView ->
            tabView.destroy()
            logInfo("keep_alive result=dropped scope=app_tab reason=$reason")
        }
        if (parked?.keepAliveKey == key) {
            drop(reason)
        }
    }

    /** Drops and destroys every parked App tab WebView without disturbing artifact prewarm. */
    fun dropAppTabs(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { dropAppTabs(reason) }
            return
        }
        if (appTabPool.isEmpty()) return
        val views = appTabPool.values.toList()
        appTabPool.clear()
        views.forEach { it.destroy() }
        logInfo("keep_alive result=dropped_app_tabs count=${views.size} reason=$reason")
    }

    /** Drops and destroys the legacy single Artifact keep-alive slot without disturbing App tabs. */
    fun drop(reason: String) {
        val prewarmHandle = parkedPrewarmTraceHandle
        handler.removeCallbacks(expire)
        handler.removeCallbacks(prewarmLoadTimeout)
        parkedPrewarmStartedAtMs = 0L
        parkedPrewarmTraceHandle = null
        val view = parked ?: return
        parked = null
        finishPrewarmHandleForDrop(prewarmHandle, reason)
        view.destroy()
        logInfo("keep_alive result=dropped reason=$reason")
    }

    internal fun resetTestHooks() {
        nowMs = { SystemClock.elapsedRealtime() }
        prewarmLoadTimeoutMs = PREWARM_LOAD_TIMEOUT_MS
        logInfo = { message -> raftLogInfo(LOG_TAG, message) }
        parkedPrewarmTraceHandle = null
        startPrewarmTrace = { surface ->
            MobileWebViewLoadTrace.startPrewarm(diagnosticsSurface = surface, useWebContainer = false)
        }
    }

    private fun finishPrewarmHandleForDrop(
        handle: MobileWebViewLoadAttemptHandle?,
        reason: String,
    ) {
        if (handle == null) return
        when (reason) {
            "render_process_gone" ->
                handle.finish(
                    outcome = MobileWebViewLoadOutcome.Failed,
                    errorCode = MobileWebViewLoadErrorCode.RenderProcessGone,
                    uiErrorShown = false,
                )
            "ssl_error" ->
                handle.finish(
                    outcome = MobileWebViewLoadOutcome.Failed,
                    errorCode = MobileWebViewLoadErrorCode.SslError,
                    uiErrorShown = false,
                )
            "http_error" ->
                handle.finish(
                    outcome = MobileWebViewLoadOutcome.Failed,
                    errorCode = MobileWebViewLoadErrorCode.HttpError,
                    uiErrorShown = false,
                )
            "load_error" ->
                handle.finish(
                    outcome = MobileWebViewLoadOutcome.Failed,
                    errorCode = MobileWebViewLoadErrorCode.NetworkError,
                    uiErrorShown = false,
                )
            "other_key" ->
                handle.finish(
                    outcome = MobileWebViewLoadOutcome.CancelledForForeground,
                    errorCode = MobileWebViewLoadErrorCode.Other,
                    uiErrorShown = false,
                )
            else -> Unit
        }
    }

    private fun sanitizeKeepAliveSurfaceToken(surface: String): String =
        surface.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "unknown" }
}

internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? =
    runOnMainOrNull {
        RaftAndroidWebViewKeepAlive.kept(key)?.keptDocument()
            ?: WebContainerAndroidKeepAlive.keptDocument(
                keepAliveKey = key,
                isolationScope = WebIsolationScope.Default,
                isDarkTheme = RaftAppThemeState.isDark,
            )?.let { snapshot ->
                RaftWebViewKeptDocument(
                    src = snapshot.configuredUrl,
                    currentUrl = snapshot.currentUrl,
                    loaded = snapshot.loaded,
                )
            }
    }

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean =
    runOnMainOrNull {
        if (RaftAndroidWebViewKeepAlive.kept(key)?.evaluateInTopDocument(origin, script) == true) {
            true
        } else {
            val expectedOrigin = parseWebContainerOrigin(origin) ?: return@runOnMainOrNull false
            WebContainerAndroidKeepAlive.evaluateInKept(
                keepAliveKey = key,
                expectedOrigin = expectedOrigin,
                script = script,
                isolationScope = WebIsolationScope.Default,
                isDarkTheme = RaftAppThemeState.isDark,
            )
        }
    } == true

internal actual fun raftWebViewKeepAliveDrop(key: String) {
    val dropAction = {
        RaftAndroidWebViewKeepAlive.dropIfKey(key, "dropped")
        runCatching { WebContainerAndroidKeepAlive.dropAllThemes(key, WebIsolationScope.Default) }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        dropAction()
    } else {
        Handler(Looper.getMainLooper()).post { dropAction() }
    }
}

internal actual fun raftWebViewKeepAliveHasPrefix(prefix: String): Boolean =
    runOnMainOrNull {
        WebContainerAndroidKeepAlive.hasAnyWithPrefix(
            prefix = prefix,
            isolationScope = WebIsolationScope.Default,
            isDarkTheme = RaftAppThemeState.isDark,
        )
    } == true

internal actual fun raftWebViewKeepAlivePromote(fromKey: String, toKey: String): Boolean =
    runOnMainOrNull {
        WebContainerAndroidKeepAlive.promote(
            fromKey = fromKey,
            toKey = toKey,
            isolationScope = WebIsolationScope.Default,
            isDarkTheme = RaftAppThemeState.isDark,
        )
    } == true

internal actual fun raftWebViewKeepAliveClear() {
    val clearAction = {
        RaftAndroidWebViewKeepAlive.dropAppTabs("cleared")
        RaftAndroidWebViewKeepAlive.drop("cleared")
        runCatching { WebContainerAndroidKeepAlive.clearAll() }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        clearAction()
    } else {
        Handler(Looper.getMainLooper()).post { clearAction() }
    }
}

internal actual fun raftWebViewKeepAliveEvict(key: String) {
    val clean = key.trim()
    if (clean.isEmpty()) return
    val evictAction = {
        RaftAndroidWebViewKeepAlive.evict(clean, "evicted")
        runCatching { WebContainerAndroidKeepAlive.evictKey(clean) }
    }
    val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
    if (Looper.myLooper() == mainLooper) {
        evictAction()
    } else {
        Handler(mainLooper).post { evictAction() }
    }
}

internal actual fun raftWebViewKeepAliveOnMemoryPressure(critical: Boolean) {
    trimAndroidAppAndLegacyPools(critical = critical)
    runCatching {
        WebContainerAndroidKeepAlive.onMemoryPressure(
            if (critical) WebMemoryTrimLevel.CRITICAL else WebMemoryTrimLevel.MODERATE,
        )
    }
}

private fun trimAndroidAppAndLegacyPools(critical: Boolean) {
    RaftFloatingWindowCoordinator.process.hibernateBackgroundWindows()
    val trimAction = {
        RaftAndroidWebViewKeepAlive.dropAppTabs("memory_pressure")
        if (critical) {
            RaftAndroidWebViewKeepAlive.drop("memory_pressure")
        }
    }
    val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
    if (Looper.myLooper() == mainLooper) {
        trimAction()
    } else {
        Handler(mainLooper).post { trimAction() }
    }
}

/**
 * Public Android `Application.onTrimMemory` / `onLowMemory` hook into `:shared` App-tab and legacy
 * WebView keep-alive trimming (`WebContainerAndroidKeepAlive.onTrimMemory(level)` is invoked
 * separately in `RaftApplication` so `:web-container` is never double-trimmed). Preserves warm
 * tabs on `TRIM_MEMORY_UI_HIDDEN` to match `#5922`.
 */
@Suppress("DEPRECATION")
fun raftAndroidOnTrimMemory(level: Int) {
    val critical =
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> true
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            -> false
            else -> if (level > ComponentCallbacks2.TRIM_MEMORY_COMPLETE) true else return
        }
    trimAndroidAppAndLegacyPools(critical = critical)
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
    clearThirdPartyProfiles: suspend () -> Unit = {},
) {
    runCatching { dropKeepAlive() }
    // Chromium's WebStorage.deleteOrigin() (aw_quota_manager_bridge.cc DeleteOriginFramework)
    // only clears FILE_SYSTEMS | INDEXEDDB and omits LOCAL_STORAGE; only deleteAllData()
    // (DeleteAllDataFramework) clears localStorage where slock_access_token is stored.
    // Always call deleteAllData() so sign-out / account switch never leaves a web access token
    // behind on shared devices, while still clearing cookies per-origin so third-party cookie
    // logins in InAppBrowserPage survive when [origins] is explicitly scoped.
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
    if (origins.isEmpty()) {
        try {
            clearThirdPartyProfiles()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
        }
    }
}

/** Chromium `MULTI_PROFILE` names holding isolated third-party WebView data across both Android engines. */
internal fun raftAndroidIsolatedThirdPartyProfileNames(): List<String> =
    listOf(
        RAFT_ANDROID_THIRD_PARTY_PROFILE_NAME,
        AndroidWebContainerProfileStore.profileNameFor(
            WebIsolationScope.Named(RAFT_THIRD_PARTY_STORAGE_SCOPE_ID),
        ),
    ).distinct()

internal suspend fun clearAndroidIsolatedThirdPartyProfiles(
    supportsMultiProfile: () -> Boolean = {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    },
    clearWebContainerScope: () -> Unit = {
        runCatching {
            AndroidWebContainerProfileStore.clearIsolationScope(
                WebIsolationScope.Named(RAFT_THIRD_PARTY_STORAGE_SCOPE_ID),
            )
        }
    },
    clearNamedProfile: suspend (String) -> Unit = ::clearAndroidChromiumNamedProfile,
) {
    if (!supportsMultiProfile()) return
    runCatching { clearWebContainerScope() }
    for (profileName in raftAndroidIsolatedThirdPartyProfileNames()) {
        try {
            clearNamedProfile(profileName)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
        }
    }
}

private suspend fun clearAndroidChromiumNamedProfile(profileName: String) {
    val profileStore = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
    val profile = runCatching { profileStore.getProfile(profileName) }.getOrNull()
    if (profile != null) {
        runCatching { profile.webStorage.deleteAllData() }
        val profileCookies = runCatching { profile.cookieManager }.getOrNull()
        if (profileCookies != null) {
            withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    runCatching {
                        profileCookies.removeAllCookies {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }.onFailure {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            runCatching { profileCookies.flush() }
        }
    }
    // Best-effort profile directory deletion (Chromium rejects deleteProfile if a WebView on
    // this profile is still finishing destroy, in which case WebStorage + cookies above are
    // already wiped).
    runCatching { profileStore.deleteProfile(profileName) }
}

internal actual suspend fun raftClearPlatformWebViewAuthStorage(origins: Set<String>) {
    val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
    val clearAction: suspend () -> Unit = {
        val storage = runCatching { android.webkit.WebStorage.getInstance() }.getOrNull()
        val cookieManager = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
        clearAndroidWebViewAuthStorage(
            origins = origins,
            dropKeepAlive = {
                RaftAndroidWebViewKeepAlive.dropAppTabs("logout")
                RaftAndroidWebViewKeepAlive.drop("logout")
                runCatching { WebContainerAndroidKeepAlive.clearAll() }
            },
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
            clearThirdPartyProfiles = { clearAndroidIsolatedThirdPartyProfiles() },
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
                runCatching { WebContainerAndroidKeepAlive.clearAll() }
                runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
                if (origins.isEmpty() && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)) {
                    runCatching {
                        AndroidWebContainerProfileStore.clearIsolationScope(
                            WebIsolationScope.Named(RAFT_THIRD_PARTY_STORAGE_SCOPE_ID),
                        )
                    }
                    val store = runCatching { ProfileStore.getInstance() }.getOrNull()
                    if (store != null) {
                        for (profileName in raftAndroidIsolatedThirdPartyProfileNames()) {
                            runCatching {
                                store.getProfile(profileName)?.let { profile ->
                                    runCatching { profile.webStorage.deleteAllData() }
                                    runCatching { profile.cookieManager.removeAllCookies(null) }
                                    runCatching { profile.cookieManager.flush() }
                                }
                            }
                            runCatching { store.deleteProfile(profileName) }
                        }
                    }
                }
            }
        }
    }
}

internal actual suspend fun raftClearPlatformInAppBrowserStorage(): Boolean {
    return try {
        raftClearPlatformWebViewAuthStorage(emptySet())
        true
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        false
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
    val useWebContainer = rememberRaftWebContainerV1Enabled()
    val isDarkTheme = RaftAppThemeState.isDark
    val standardJsbHost = LocalRaftWebContainerJsbHost.current
    val offlineBundleSpec = LocalRaftWebContainerOfflineBundle.current
    val customResourceInterceptor = LocalRaftWebContainerResourceInterceptor.current
    val platformResourceInterceptor = rememberRaftWebContainerPlatformResourceInterceptor()
    LaunchedEffect(
        config,
        useWebContainer,
        isDarkTheme,
        standardJsbHost,
        offlineBundleSpec,
        customResourceInterceptor,
        platformResourceInterceptor,
    ) {
        if (config?.keepAliveKey != null) {
            if (useWebContainer) {
                val standardJsbCapabilities =
                    if (standardJsbHost != null) {
                        resolveRaftSurfaceStandardJsbCapabilities(
                            surfaceId = config.diagnosticsSurface,
                            partition = config.effectiveStoragePartition,
                        )
                    } else {
                        emptySet()
                    }
                val containerConfig =
                    config.toWebContainerConfig(
                        isDarkTheme = isDarkTheme,
                        standardJsbCapabilities = standardJsbCapabilities,
                        offlineBundle = offlineBundleSpec?.descriptor,
                    )
                val interceptor =
                    buildRaftWebContainerResourceInterceptor(
                        surfaceId = config.diagnosticsSurface,
                        offlineBundleInterceptor = if (containerConfig.offlineBundle != null) offlineBundleSpec?.interceptor else null,
                        customInterceptor = customResourceInterceptor,
                        platformInterceptor = platformResourceInterceptor,
                    )
                val jsbRegistry =
                    buildRaftWebContainerJsbRegistry(
                        hostEventsEnabled = config.hostEventsEnabled,
                        dispatchRawHostMessage = {},
                        standardHost = if (standardJsbCapabilities.isNotEmpty()) standardJsbHost else null,
                    )
                WebContainerAndroidKeepAlive.prewarm(
                    context = context,
                    config = containerConfig,
                    jsbRegistry = jsbRegistry,
                    resourceInterceptor = interceptor,
                    telemetrySink = RaftWebContainerTelemetrySink,
                )
            } else {
                RaftAndroidWebViewKeepAlive.prewarm(context, config)
            }
        }
    }
}

private inline fun <T> runOnMainOrNull(block: () -> T?): T? =
    if (Looper.myLooper() == Looper.getMainLooper()) block() else null
