package build.raft.app.compat.pager

import build.raft.app.data.RaftAppAppearanceRuntime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.RaftProvideTextRendering
import build.raft.app.components.RaftProvideDesignSystemMisuse
import build.raft.app.components.RaftProvideDesignSystemStrings
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.cmp.RaftPageProvider
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.LocalRaftPageArgs
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.LocalRaftNativeTabBarBridge
import build.raft.app.RaftNativeTabBarHostIdPageDataKey
import build.raft.app.RaftNativeTabBarRegistry
import build.raft.app.compat.json.JSONObject
import build.raft.app.image.raftInstallImageLoader
import build.raft.app.toKuiklyJson
import kotlinx.atomicfu.atomic

/**
 * Every page `:shared` can render, by page name: `pageName -> @Composable` for the CMP hosts.
 *
 * Built from the generated [raftProductPageFactories] / [raftVisualFixturePageFactories] (see
 * scripts/cmp_generate_page_registry.py). Page names and pageData contracts are the ones the
 * Kuikly `@Page` classes declared. Hosts call [install] once at startup so `RaftCmpRoot` can
 * render these pages.
 */
object RaftPageRegistry {
    private val factories: Map<String, () -> Pager> by lazy {
        raftProductPageFactories + raftVisualFixturePageFactories
    }

    val pageNames: Set<String> get() = factories.keys

    fun hasPage(pageName: String): Boolean = factories.containsKey(pageName)

    /** Creates a fresh page instance for [pageName] (null if unknown). */
    fun factory(pageName: String): (() -> Pager)? = factories[pageName]

    /** The page body for [pageName], or null if no page has that name. */
    fun content(pageName: String): (@Composable () -> Unit)? {
        val factory = factories[pageName] ?: return null
        // Hosts load the stored theme at startup; this covers a host that did not (idempotent).
        RaftAppAppearanceRuntime.ensureLoaded()
        return { RaftPagerHost(factory) }
    }

    /** Hosts call this once at startup (Android: `RaftApplication.onCreate`). */
    fun install() {
        raftInstallImageLoader()
        RaftCmpRouter.installPageProvider(RaftPageProvider { pageName -> content(pageName) })
    }
}

private val pagerIdSequence = atomic(0)

/**
 * Runs one Kuikly-style page: creates the [Pager] for [args] (by default the page this
 * `RaftCmpRoot` was opened with), calls `willInit()` / `created()` (the page sets its content
 * there), feeds window insets into [PageData], sends the `viewDidAppear` / `viewDidDisappear` pager
 * events while the host lifecycle is resumed and [active] is true, and provides [LocalActivity]
 * (for `getPager()` callers) and [LocalRaftPageArgs] (= [args]) to the page.
 *
 * Child pages (`RaftChildPage`) nest a second host with their own [args].
 */
@Composable
fun RaftPagerHost(
    factory: () -> Pager,
    args: RaftCmpPageArgs = LocalRaftPageArgs.current,
    active: Boolean = true,
) {
    val host = LocalRaftHost.current
    // The Raft Coil loader must be the singleton before any page asks Coil for an image.
    remember { raftInstallImageLoader() }
    val density = LocalDensity.current
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    // System bars + cutout, deliberately without the IME: Kuikly's pageData safe area never
    // carried the keyboard (composers add it via keyboardHeightChange), and reading the IME here
    // would recompose the host on every keyboard animation frame.
    val safeDrawing = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val windowSize = LocalWindowInfo.current.containerSize
    val metrics =
        with(density) {
            val width = windowSize.width.toDp().value
            val height = windowSize.height.toDp().value
            RaftPageWindowMetrics(
                density = density.density,
                statusBarHeight = statusBars.getTop(this).toDp().value,
                navigationBarHeight = navigationBars.getBottom(this).toDp().value,
                safeAreaInsets =
                    EdgeInsets(
                        top = safeDrawing.getTop(this).toDp().value,
                        left = safeDrawing.getLeft(this, LayoutDirection.Ltr).toDp().value,
                        bottom = safeDrawing.getBottom(this).toDp().value,
                        right = safeDrawing.getRight(this, LayoutDirection.Ltr).toDp().value,
                    ),
                width = width,
                height = height,
            )
        }
    val pager =
        remember(args) {
            factory().also { pager ->
                pager.pagerId = "raft-${pagerIdSequence.incrementAndGet()}"
                pager.pageData = PageData(args.pageData.toKuiklyJson())
                // Window facts before willInit/first composition: Kuikly's native host injected
                // them before the page's first render.
                pager.pageData.applyRaftWindowMetrics(metrics)
                pager.host = host
                PagerManager.register(pager)
                pager.willInit()
                pager.created()
            }
        }
    // Applied during composition, ahead of the page content that reads them. Applying them in a
    // SideEffect (after composition) gave every newly opened page one frame laid out with a zero
    // status bar / safe area / page size, after which its content jumped down by the status bar
    // height: a pushed page (e.g. Activity) looked like it slid down from the top, and a newly
    // mounted app-shell tab flickered. Same-value writes do not invalidate anything.
    pager.pageData.applyRaftWindowMetrics(metrics)

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> resumed = true
                    Lifecycle.Event.ON_PAUSE -> resumed = false
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val visible = resumed && active
    LaunchedEffect(pager, visible) {
        if (visible != pager.isAppeared) {
            val event = if (visible) Pager.PAGER_EVENT_DID_APPEAR else Pager.PAGER_EVENT_DID_DISAPPEAR
            pager.onReceivePagerEvent(event, JSONObject())
        }
    }
    // Native host events (Kuikly `sendEvent`: window insets, adaptive panes, ...) reach the page as
    // pager events, the way the Kuikly renderer delivered them.
    DisposableEffect(pager, host) {
        val unsubscribe =
            host.events.subscribe { event, data -> pager.onReceivePagerEvent(event, data.toKuiklyJson()) }
        onDispose { unsubscribe() }
    }
    DisposableEffect(pager) {
        onDispose {
            if (pager.isAppeared) pager.onReceivePagerEvent(Pager.PAGER_EVENT_DID_DISAPPEAR, JSONObject())
            pager.onReceivePagerEvent(Pager.PAGER_EVENT_WILL_DESTROY, JSONObject())
            PagerManager.unregister(pager)
        }
    }

    // One composition per pager. [args] changing (e.g. the wide detail pane switching to another
    // conversation) creates a NEW pager above; without this key its content would run in the old
    // pager's slot table and every unkeyed `remember` in the page (ThreadPageScreen's payload,
    // instanceId and adaptive close request) would keep the PREVIOUS page's values: the detail
    // stayed on the old channel while the shell had already selected the new one. Kuikly gave each
    // page its own render view, so this is that contract.
    key(pager) {
    CompositionLocalProvider(
        LocalActivity provides remember(pager) { RaftPageActivity(pager) },
        LocalRaftPageArgs provides args,
        // The host-drawn tab bar registered for this page (iOS app shell), if any.
        LocalRaftNativeTabBarBridge provides
            remember(pager) {
                RaftNativeTabBarRegistry.bridgeFor(pager.pageData.params.optString(RaftNativeTabBarHostIdPageDataKey))
            },
    ) {
        // Back handlers of an inactive (kept-alive, hidden) page must not fire.
        RaftProvidePageActive(active) {
            // Raft text rendering defaults (CSS line box, ...) for Text calls not built from a token.
            RaftProvideTextRendering {
                // The design-system components' own words (optional, close, clear, loading).
                RaftProvideDesignSystemStrings {
                    // Debug builds fail on design-system misuse (a nameless icon button, ...).
                    RaftProvideDesignSystemMisuse {
                        (pager as? ComposeContainer)?.composedContent?.invoke()
                    }
                }
            }
        }
    }
    }
}

/** Window facts a page host reports into [PageData] (dp). */
internal data class RaftPageWindowMetrics(
    val density: Float,
    val statusBarHeight: Float,
    val navigationBarHeight: Float,
    val safeAreaInsets: EdgeInsets,
    val width: Float,
    val height: Float,
)

internal fun PageData.applyRaftWindowMetrics(metrics: RaftPageWindowMetrics) {
    density = metrics.density
    statusBarHeight = metrics.statusBarHeight
    androidBottomBavBarHeight = metrics.navigationBarHeight
    safeAreaInsets = metrics.safeAreaInsets
    pageViewWidth = metrics.width
    pageViewHeight = metrics.height
    deviceWidth = metrics.width
    deviceHeight = metrics.height
    activityWidth = metrics.width
    activityHeight = metrics.height
}
