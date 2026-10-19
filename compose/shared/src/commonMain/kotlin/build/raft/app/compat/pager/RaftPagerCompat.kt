package build.raft.app.compat.pager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.cmp.host.SlockCmpHost
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.CodecModule
import build.raft.app.compat.module.Module
import build.raft.app.compat.module.PerformanceModule
import build.raft.app.compat.module.RouterModule
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/*
 * TODO(cmp-switch): Kuikly page model on top of the CMP hosts.
 *
 * Kuikly pages are `@Page("name") class X : BasePager()` objects whose `willInit()` calls
 * `setContent { ... }`, and composables reach their page through
 * `LocalActivity.current.getPager()` (pageData, pagerId, `acquireModule<RouterModule>()`, pager
 * events). This file keeps those names and semantics so the ~130 page classes and ~70
 * `getPager()` call sites compile unchanged; [RaftPagerHost] instantiates the page inside a
 * `SlockCmpRoot` and drives its lifecycle. New code should read `LocalSlockPageArgs` /
 * `LocalSlockHost` directly; this layer goes away once pages are plain composables.
 */

/** Kuikly `@Page`: marks a page class. Registration is [build.raft.app.compat.pager.RaftPageRegistry]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Page(val name: String = "", val supportInLocal: Boolean = false, val moduleId: String = "")

/** Kuikly `BaseObject` (reactive base): nothing is needed from it any more. */
open class BaseObject

interface IPagerId {
    var pagerId: String
}

/** Kuikly `pagerId { }` delegate: lazily creates a value per pagerId. */
fun <T> pagerId(initializer: (String) -> T): ReadOnlyProperty<IPagerId, T> =
    object : ReadOnlyProperty<IPagerId, T> {
        private var lastPagerId: String? = null
        private var value: T? = null

        override fun getValue(thisRef: IPagerId, property: KProperty<*>): T {
            if (lastPagerId != thisRef.pagerId) {
                lastPagerId = thisRef.pagerId
                value = initializer(thisRef.pagerId)
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

interface IPagerEventObserver {
    fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {}
}

fun interface IModuleCreator {
    fun createModule(): Module
}

/** Kuikly `EdgeInsets` as carried by [PageData.safeAreaInsets] (dp). */
data class EdgeInsets(val top: Float, val left: Float, val bottom: Float, val right: Float) {
    companion object {
        val default = EdgeInsets(0f, 0f, 0f, 0f)
    }
}

/**
 * Kuikly `PageData`. [params] is the page's pageData JSON (the `openPage` contract, unchanged).
 * Window facts that Kuikly's native host injected (status bar, safe area, sizes) are filled in
 * by [RaftPagerHost] from Compose window insets and are snapshot state, so readers recompose.
 */
class PageData internal constructor(val params: JSONObject) {
    var statusBarHeight: Float by mutableStateOf(0f)
    var safeAreaInsets: EdgeInsets by mutableStateOf(EdgeInsets.default)
    var androidBottomBavBarHeight: Float by mutableStateOf(0f)
    var pageViewWidth: Float by mutableStateOf(0f)
    var pageViewHeight: Float by mutableStateOf(0f)
    var deviceWidth: Float by mutableStateOf(0f)
    var deviceHeight: Float by mutableStateOf(0f)
    var activityWidth: Float by mutableStateOf(0f)
    var activityHeight: Float by mutableStateOf(0f)
    var density: Float = 3f
    val navigationBarHeight: Float get() = statusBarHeight + 44f
    val platform: String get() = raftPlatformName()
    val isAndroid: Boolean get() = platform == "android"
    val isIOS: Boolean get() = platform == "iOS"
    val isIphoneX: Boolean get() = isIOS && statusBarHeight > 30f
    var appVersion: String = ""
    var osVersion: String = ""
    var isAccessibilityRunning: Boolean = false

    fun isDebug(): Boolean = params.optBoolean("isDebug", false)
}

internal expect fun raftPlatformName(): String

/** Kuikly `Pager`: one page instance; created and driven by [RaftPagerHost]. */
abstract class Pager : IPagerId {
    override var pagerId: String = ""
    lateinit var pageData: PageData
        internal set

    /** The CMP host this page renders in (navigation, bridge transport, capabilities). */
    var host: SlockCmpHost? = null
        internal set

    var isAppeared: Boolean = false
        private set

    private val modules = HashMap<String, Module>()
    private val moduleCreators = HashMap<String, IModuleCreator>()
    private val observers = LinkedHashSet<IPagerEventObserver>()

    open fun createExternalModules(): Map<String, Module>? = null

    fun registerModule(moduleName: String, moduleCreator: IModuleCreator) {
        moduleCreators[moduleName] = moduleCreator
    }

    open fun willInit() {}

    open fun created() {}

    open fun pageDidAppear() {}

    open fun pageDidDisappear() {}

    open fun pageWillDestroy() {}

    open fun themeDidChanged(data: JSONObject) {}

    open fun isNightMode(): Boolean = false

    open fun debugUIInspector(): Boolean = false

    open fun onReceivePagerEvent(pagerEvent: String, eventData: JSONObject) {
        observers.toList().forEach { it.onPagerEvent(pagerEvent, eventData) }
        when (pagerEvent) {
            PAGER_EVENT_DID_APPEAR -> {
                isAppeared = true
                pageDidAppear()
            }
            PAGER_EVENT_DID_DISAPPEAR -> {
                isAppeared = false
                pageDidDisappear()
            }
            PAGER_EVENT_THEME_DID_CHANGED -> themeDidChanged(eventData)
            PAGER_EVENT_WILL_DESTROY -> pageWillDestroy()
        }
    }

    fun addPagerEventObserver(observer: IPagerEventObserver) {
        observers.add(observer)
    }

    fun removePagerEventObserver(observer: IPagerEventObserver) {
        observers.remove(observer)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Module> acquireModule(moduleName: String): T =
        (getModule<T>(moduleName) ?: error("Module $moduleName is not registered on page $pagerId"))

    @Suppress("UNCHECKED_CAST")
    fun <T : Module> getModule(moduleName: String): T? {
        modules[moduleName]?.let { return it as T }
        val module =
            createExternalModules()?.get(moduleName)
                ?: moduleCreators[moduleName]?.createModule()
                ?: builtInModule(moduleName)
                ?: return null
        module.attach(this)
        modules[moduleName] = module
        return module as T
    }

    private fun builtInModule(moduleName: String): Module? =
        when (moduleName) {
            RouterModule.MODULE_NAME -> RouterModule()
            CodecModule.MODULE_NAME -> CodecModule()
            PerformanceModule.MODULE_NAME -> PerformanceModule()
            else -> null
        }

    companion object {
        /** Kuikly thread verification has no CMP equivalent; kept so callers compile. */
        var VERIFY_THREAD: Boolean = false
        var VERIFY_REACTIVE_OBSERVER: Boolean = false

        fun verifyFailed(handler: (Throwable) -> Unit) = Unit

        const val PAGER_EVENT_DID_APPEAR = "viewDidAppear"
        const val PAGER_EVENT_DID_DISAPPEAR = "viewDidDisappear"
        const val PAGER_EVENT_THEME_DID_CHANGED = "themeDidChanged"
        const val PAGER_EVENT_WILL_DESTROY = "pageWillDestroy"
        const val PAGER_EVENT_ON_BACK_PRESSED = "onBackPressed"
    }
}

/** Kuikly `ComposeContainer`: a [Pager] whose UI is a composable set from `willInit()`. */
abstract class ComposeContainer : Pager() {
    // Not named `content`: its private setter would clash with setContent on the JVM.
    internal var composedContent: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    open fun setContent(content: @Composable () -> Unit) {
        composedContent = content
    }

    companion object {
        /** Kuikly snapshot-consumption switch; meaningless on CMP. */
        var enableConsumeSnapshot: Boolean = false
    }
}

/** Kuikly `PagerManager`: live pages by id. */
object PagerManager {
    private val pagers = LinkedHashMap<String, Pager>()

    fun getPager(pagerId: String): Pager =
        pagers[pagerId] ?: pagers.values.lastOrNull() ?: error("No live page (pagerId=$pagerId)")

    fun getCurrentPager(): Pager = getPager(BridgeManager.currentPageId)

    fun containsPager(pagerId: String): Boolean = pagers.containsKey(pagerId)

    internal fun register(pager: Pager) {
        pagers[pager.pagerId] = pager
        BridgeManager.currentPageId = pager.pagerId
    }

    internal fun unregister(pager: Pager) {
        pagers.remove(pager.pagerId)
        if (BridgeManager.currentPageId == pager.pagerId) {
            BridgeManager.currentPageId = pagers.keys.lastOrNull().orEmpty()
        }
    }
}

/** Kuikly `BridgeManager`: only the "current page" pointer survives. */
object BridgeManager {
    var currentPageId: String = ""
        internal set
}

/** What `LocalActivity.current` returns on Kuikly: the page's activity facade. */
class RaftPageActivity internal constructor(private val pager: Pager) {
    fun getPager(): Pager = pager

    val pageData: PageData get() = pager.pageData
}

/** Kuikly `LocalActivity` (`LocalActivity.current.getPager()`); provided by [RaftPagerHost]. */
val LocalActivity =
    staticCompositionLocalOf<RaftPageActivity> {
        error("LocalActivity is not provided; render the page through RaftPagerHost")
    }

/** Kuikly `urlParams`: `a=1&b=2` query pairs of [url]. */
fun urlParams(url: String): Map<String, String> {
    val params = LinkedHashMap<String, String>()
    url.split("?").last().split("&").forEach { pair ->
        val keyValue = pair.split("=")
        if (keyValue.size == 2) params[keyValue.first()] = keyValue.last()
    }
    return params
}
