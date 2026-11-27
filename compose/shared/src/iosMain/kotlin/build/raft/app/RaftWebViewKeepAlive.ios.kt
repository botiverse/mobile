@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.thread.key
import build.raft.webcontainer.IosWebContainerDataStoreRegistry
import build.raft.webcontainer.WebContainerIosKeepAlive
import build.raft.webcontainer.WebContainerIosSparePool
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.parseWebContainerOrigin
import build.raft.webcontainer.pool.WebMemoryTrimLevel
import kotlin.coroutines.resume
import kotlin.time.TimeSource
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIColor
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebsiteDataRecord
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * 2-slot LRU keep-alive pool for suspended iOS installed Raft App `WKWebView` containers
 * (`keepAliveKey` prefixed with [RAFT_APP_BROWSER_KEEP_ALIVE_PREFIX]). Main thread only.
 */
internal object RaftIosAppBrowserWebViewPool {
    private const val LOG_TAG = "SlockWebViewKeepAlive"
    private val pool = LinkedHashMap<String, RaftIosSandboxWebViewContainer>()

    fun take(key: String, events: RaftWebViewEvents): RaftIosSandboxWebViewContainer? {
        if (!NSThread.isMainThread || !key.startsWith(RAFT_APP_BROWSER_KEEP_ALIVE_PREFIX)) return null
        dropIfOtherThemeMode()
        val container = pool.remove(key) ?: return null
        if (!container.webViewHost.isHealthyForKeepAlive()) {
            container.webViewHost.dispose()
            return null
        }
        container.webViewHost.attachFromKeepAlive(events)
        raftLogInfo(LOG_TAG, "ios_app_pool result=reused remaining=${pool.size}")
        return container
    }

    fun park(container: RaftIosSandboxWebViewContainer): Boolean {
        if (!NSThread.isMainThread) return false
        val key = container.webViewHost.keepAliveKey ?: return false
        if (!key.startsWith(RAFT_APP_BROWSER_KEEP_ALIVE_PREFIX)) return false
        if (
            !container.webViewHost.isHealthyForKeepAlive() ||
            container.webViewHost.builtDark != RaftAppThemeState.isDark ||
            !RaftMobileWebPanelCoordinator.process.isKeepAliveEligible(key)
        ) {
            if (pool[key] === container) pool.remove(key)
            return false
        }
        dropIfOtherThemeMode()
        pool.remove(key)?.takeIf { it !== container }?.webViewHost?.dispose()
        while (pool.size >= RAFT_APP_BROWSER_LIVE_POOL_SIZE) {
            val eldestKey = pool.keys.firstOrNull() ?: break
            pool.remove(eldestKey)?.webViewHost?.dispose()
        }
        container.webViewHost.detachForKeepAlive()
        pool[key] = container
        raftLogInfo(LOG_TAG, "ios_app_pool result=parked size=${pool.size}")
        return true
    }

    fun dropIfSame(container: RaftIosSandboxWebViewContainer, reason: String) {
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { dropIfSame(container, reason) }
            return
        }
        val key = pool.entries.firstOrNull { it.value === container }?.key ?: return
        pool.remove(key)
        container.webViewHost.dispose()
        RaftMobileWebPanelCoordinator.process.onTabKeepAliveDropped(key)
        raftLogInfo(LOG_TAG, "ios_app_pool result=dropped reason=$reason")
    }

    fun evict(key: String, reason: String = "evicted") {
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { evict(key, reason) }
            return
        }
        pool.remove(key)?.let { container ->
            container.webViewHost.dispose()
            raftLogInfo(LOG_TAG, "ios_app_pool result=dropped reason=$reason")
        }
    }

    fun clearAll(reason: String) {
        if (!NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { clearAll(reason) }
            return
        }
        if (pool.isEmpty()) return
        val containers = pool.values.toList()
        pool.clear()
        containers.forEach { it.webViewHost.dispose() }
        raftLogInfo(LOG_TAG, "ios_app_pool result=cleared count=${containers.size} reason=$reason")
    }

    private fun dropIfOtherThemeMode() {
        val stale =
            pool.entries
                .filter { (_, container) ->
                    container.webViewHost.builtDark != RaftAppThemeState.isDark ||
                        !container.webViewHost.isHealthyForKeepAlive()
                }
                .map { it.key }
        for (key in stale) {
            pool.remove(key)?.webViewHost?.dispose()
            RaftMobileWebPanelCoordinator.process.onTabKeepAliveDropped(key)
        }
    }
}

// Legacy RaftIosSandboxWebView parks up to 2 installed Raft App WebViews via
// RaftIosAppBrowserWebViewPool; `compose/web-container` backs multi-slot keep-alive on iOS via
// WebContainerIosKeepAlive.
internal actual fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument? =
    if (!NSThread.isMainThread) {
        null
    } else {
        WebContainerIosKeepAlive.keptDocument(
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

internal actual fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean {
    if (!NSThread.isMainThread) return false
    val expectedOrigin = parseWebContainerOrigin(origin) ?: return false
    return WebContainerIosKeepAlive.evaluateInKept(
        keepAliveKey = key,
        expectedOrigin = expectedOrigin,
        script = script,
        isolationScope = WebIsolationScope.Default,
        isDarkTheme = RaftAppThemeState.isDark,
    )
}

internal actual fun raftWebViewKeepAliveDrop(key: String) {
    RaftIosAppBrowserWebViewPool.evict(key, "dropped")
    runCatching { WebContainerIosKeepAlive.dropAllThemes(key, WebIsolationScope.Default) }
}

internal actual fun raftWebViewKeepAliveHasPrefix(prefix: String): Boolean =
    if (!NSThread.isMainThread) {
        false
    } else {
        WebContainerIosKeepAlive.hasAnyWithPrefix(
            prefix = prefix,
            isolationScope = WebIsolationScope.Default,
            isDarkTheme = RaftAppThemeState.isDark,
        )
    }

internal actual fun raftWebViewKeepAlivePromote(fromKey: String, toKey: String): Boolean =
    if (!NSThread.isMainThread) {
        false
    } else {
        WebContainerIosKeepAlive.promote(
            fromKey = fromKey,
            toKey = toKey,
            isolationScope = WebIsolationScope.Default,
            isDarkTheme = RaftAppThemeState.isDark,
        )
    }

internal actual fun raftWebViewKeepAliveClear() {
    RaftIosAppBrowserWebViewPool.clearAll("cleared")
    runCatching { WebContainerIosKeepAlive.clearAll() }
}

internal actual fun raftWebViewKeepAliveEvict(key: String) {
    val clean = key.trim()
    if (clean.isEmpty()) return
    RaftIosAppBrowserWebViewPool.evict(clean, "evicted")
    runCatching { WebContainerIosKeepAlive.evictKey(clean) }
}

internal actual fun raftWebViewKeepAliveOnMemoryPressure(critical: Boolean) {
    RaftMobileWebPanelCoordinator.process.hibernateBackgroundTabs()
    RaftIosAppBrowserWebViewPool.clearAll("memory_warning")
    runCatching {
        WebContainerIosKeepAlive.onMemoryPressure(
            if (critical) WebMemoryTrimLevel.CRITICAL else WebMemoryTrimLevel.MODERATE,
        )
    }
}

internal actual suspend fun raftClearPlatformWebViewAuthStorage(origins: Set<String>) {
    val hosts =
        origins
            .mapNotNull { origin ->
                origin.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
            }
            .toSet()
    val runOnMain: suspend () -> Unit = {
        runCatching { RaftIosAppBrowserWebViewPool.clearAll("logout") }
        runCatching { RaftIosWebViewSpare.drop("logout") }
        runCatching { WebContainerIosSparePool.drop("logout") }
        runCatching { WebContainerIosKeepAlive.clearAll() }
        val epoch = NSDate.dateWithTimeIntervalSince1970(0.0)
        val allTypes = WKWebsiteDataStore.allWebsiteDataTypes()
        withTimeoutOrNull(2_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                runCatching {
                    val dataStore = WKWebsiteDataStore.defaultDataStore()
                    if (hosts.isEmpty()) {
                        dataStore.removeDataOfTypes(
                            dataTypes = allTypes,
                            modifiedSince = epoch,
                            completionHandler = {
                                if (cont.isActive) cont.resume(Unit)
                            },
                        )
                    } else {
                        dataStore.fetchDataRecordsOfTypes(allTypes) { records ->
                            val matched =
                                records
                                    ?.filterIsInstance<WKWebsiteDataRecord>()
                                    ?.filter { raftWebKitDisplayNameMatchesHosts(it.displayName, hosts) }
                                    .orEmpty()
                            if (matched.isEmpty()) {
                                if (cont.isActive) cont.resume(Unit)
                            } else {
                                dataStore.removeDataOfTypes(
                                    dataTypes = allTypes,
                                    forDataRecords = matched,
                                    completionHandler = {
                                        if (cont.isActive) cont.resume(Unit)
                                    },
                                )
                            }
                        }
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        if (hosts.isEmpty()) {
            clearIosIsolatedThirdPartyDataStores(allTypes = allTypes, epoch = epoch)
        }
    }
    if (NSThread.isMainThread) {
        runOnMain()
    } else {
        withContext(Dispatchers.Main.immediate) { runOnMain() }
    }
}

private suspend fun clearIosIsolatedThirdPartyDataStores(
    allTypes: Set<*>,
    epoch: NSDate,
) {
    val majorVersion =
        runCatching {
            NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }
        }.getOrDefault(0L)
    if (majorVersion < 17L) {
        // iOS 15–16 uses nonPersistentDataStore() for IsolatedThirdParty, which is already
        // destroyed when active web panels close.
        return
    }
    // 1. Clear :web-container's named third-party store through the registry's serialized await
    // helper so cache eviction and identifier removal never race with WKErrorDataStoreInUse.
    runCatching {
        IosWebContainerDataStoreRegistry.clearIsolationScopeAndAwait(
            scope = WebIsolationScope.Named(RAFT_THIRD_PARTY_STORAGE_SCOPE_ID),
            timeoutMs = 2_000L,
            osMajorVersion = majorVersion,
        )
    }
    // 2. Clear legacy RaftSandboxWebView's separate fixed-UUID third-party store.
    val legacyUuid = runCatching { NSUUID(uUIDString = RAFT_IOS_THIRD_PARTY_DATA_STORE_UUID) }.getOrNull() ?: return
    withTimeoutOrNull(2_000L) {
        suspendCancellableCoroutine<Unit> { cont ->
            runCatching {
                val legacyStore = WKWebsiteDataStore.dataStoreForIdentifier(legacyUuid)
                legacyStore.removeDataOfTypes(
                    dataTypes = allTypes,
                    modifiedSince = epoch,
                    completionHandler = {
                        WKWebsiteDataStore.removeDataStoreForIdentifier(legacyUuid) { _ ->
                            if (cont.isActive) cont.resume(Unit)
                        }
                    },
                )
            }.onFailure {
                if (cont.isActive) cont.resume(Unit)
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

@Composable
internal actual fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?) = Unit

/**
 * One spare WKWebView, made ahead of the first web page (Plans & Billing, Changelog, the in-app
 * browser, the Artifact viewer, ...), so that page does not pay WebKit's start-up when it opens:
 * the web content and networking processes, the default data store. iOS's counterpart of Android's
 * `RaftAndroidWebViewWarmup` (which only warms the provider); here the page takes the warm view
 * itself ([take]), when its configuration is the spare's ([raftIosWebViewConfiguration] with the
 * persistent store, scripts on, and default viewport scale limits — surfaces that set
 * `ignoresViewportScaleLimits = true`, such as the Artifact viewer, build their own WKWebView
 * while still reusing the warmed networking process and default data store). It carries no page
 * script, bridge or session: the page adds its own to the view's user content controller before
 * it loads anything.
 *
 * At most one spare exists. It is made once the app is active and settled ([schedule], after
 * [WARM_DELAY_SECONDS]), made again a while after a page takes it ([REFILL_DELAY_SECONDS]), and
 * dropped on a memory warning (made again on the next activation). It is handed out only after its
 * blank document finished loading ([RaftWebViewSpareSlot]), with its warm-up delegate detached:
 * none of that load's callbacks can reach the page. Main thread only.
 */
internal object RaftIosWebViewSpare {
    /** The spare's own document (an empty one: it only starts WebKit). */
    const val BLANK_URL = RAFT_WEB_VIEW_SPARE_BLANK_URL
    private const val WARM_DELAY_SECONDS = 2.0
    private const val REFILL_DELAY_SECONDS = 5.0
    private const val LOG_TAG = "SlockWebViewWarmup"

    private val slot = RaftWebViewSpareSlot<RaftIosWebView> { view -> retire(view) }
    /** When the current spare became ready: `idleMs` at take (an idle spare's process may be suspended). */
    private var readyAt: TimeSource.Monotonic.ValueTimeMark? = null
    /** Retained while a spare warms up (WKWebView holds its navigation delegate weakly). */
    private var warmupDelegate: RaftIosWebViewSpareWarmupDelegate? = null
    private var timer: NSTimer? = null
    private var installed = false

    /** Makes the spare shortly after launch, once the app is active. Idempotent. */
    fun schedule() {
        if (installed) return
        installed = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidReceiveMemoryWarningNotification, null, NSOperationQueue.mainQueue) { _ ->
            drop("memory_warning")
            raftWebViewKeepAliveOnMemoryPressure(critical = true)
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            refillLater(WARM_DELAY_SECONDS)
        }
        refillLater(WARM_DELAY_SECONDS)
    }

    /** The ready spare (now the caller's), or null (none, or still warming up); a new one is made later. */
    fun take(): RaftIosWebView? {
        val view = slot.take()
        if (view == null) {
            if (!slot.isEmpty) raftLogInfo(LOG_TAG, "webview_spare result=not_ready")
            return null
        }
        view.navigationDelegate = null
        warmupDelegate = null
        raftLogInfo(LOG_TAG, "webview_spare result=taken idleMs=${readyAt?.elapsedNow()?.inWholeMilliseconds ?: -1L}")
        readyAt = null
        if (installed) refillLater(REFILL_DELAY_SECONDS)
        return view
    }

    private fun refillLater(delaySeconds: Double) {
        if (!slot.isEmpty || timer != null) return
        timer =
            NSTimer.scheduledTimerWithTimeInterval(delaySeconds, repeats = false) { _ ->
                timer = null
                fill()
            }
    }

    private fun fill() {
        if (!slot.isEmpty) return
        // A background launch (a push, a background fetch) makes nothing; the next activation does.
        if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) return
        val started = TimeSource.Monotonic.markNow()
        runCatching {
            val view =
                RaftIosWebView(
                    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                    configuration = raftIosWebViewConfiguration(ephemeral = false, allowScripts = true),
                )
            // Transparent before its blank document paints, as a page's own new WKWebView is
            // (RaftIosSandboxWebView.applyBackground): no white layer left over for the page.
            view.opaque = false
            view.backgroundColor = UIColor.clearColor
            view.scrollView.backgroundColor = UIColor.clearColor
            val generation = slot.put(view)
            val delegate = RaftIosWebViewSpareWarmupDelegate { ended ->
                slot.markReady(generation)
                readyAt = TimeSource.Monotonic.markNow()
                raftLogInfo(LOG_TAG, "webview_spare result=ready load=$ended elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
            }
            warmupDelegate = delegate
            view.navigationDelegate = delegate
            // An empty document starts the web content process; the page's load replaces it, and
            // the host does not count it as history (RaftIosSandboxWebView.canGoBack).
            view.loadHTMLString("", baseURL = null)
        }.onSuccess {
            raftLogInfo(LOG_TAG, "webview_spare result=created elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
        }.onFailure { error ->
            raftLogWarn(LOG_TAG, "webview_spare result=failed errorType=${error::class.simpleName ?: "unknown"}")
        }
    }

    internal fun drop(reason: String) {
        timer?.invalidate()
        timer = null
        if (slot.drop()) raftLogInfo(LOG_TAG, "webview_spare result=dropped reason=$reason")
    }

    private fun retire(view: WKWebView) {
        view.navigationDelegate = null
        view.stopLoading()
        warmupDelegate = null
    }
}

/** Hears the end of the spare's blank warm-up load ([onEnded] once: `finished` / `failed`). */
private class RaftIosWebViewSpareWarmupDelegate(
    private val onEnded: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private var ended = false

    private fun end(how: String) {
        if (ended) return
        ended = true
        onEnded(how)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) = end("finished")

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) = end("failed")

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) = end("failed")
}
