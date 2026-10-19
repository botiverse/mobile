package build.raft.app.compat.pager

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.cmp.RaftPageProvider
import build.raft.app.cmp.SlockCmpRouter
import build.raft.app.cmp.host.LocalSlockHost
import build.raft.app.cmp.host.LocalSlockPageArgs
import build.raft.app.cmp.host.SlockCmpPageArgs
import build.raft.app.compat.json.JSONObject
import build.raft.app.image.raftInstallImageLoader
import build.raft.app.toKuiklyJson
import kotlinx.atomicfu.atomic

/**
 * Every page `:shared` can render, by page name: `pageName -> @Composable` for the CMP hosts.
 *
 * Built from the generated [raftProductPageFactories] / [raftVisualFixturePageFactories] (see
 * scripts/cmp_generate_page_registry.py). Page names and pageData contracts are the ones the
 * Kuikly `@Page` classes declared. Hosts call [install] once at startup so `SlockCmpRoot` can
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
        return { RaftPagerHost(factory) }
    }

    /** TODO(cmp-switch): hosts call this at startup (not wired yet). */
    fun install() {
        raftInstallImageLoader()
        SlockCmpRouter.installPageProvider(RaftPageProvider { pageName -> content(pageName) })
    }
}

private val pagerIdSequence = atomic(0)

/**
 * Runs one Kuikly-style page: creates the [Pager] for [args] (by default the page this
 * `SlockCmpRoot` was opened with), calls `willInit()` / `created()` (the page sets its content
 * there), feeds window insets into [PageData], sends the `viewDidAppear` / `viewDidDisappear` pager
 * events while the host lifecycle is resumed and [active] is true, and provides [LocalActivity]
 * (for `getPager()` callers) and [LocalSlockPageArgs] (= [args]) to the page.
 *
 * Child pages (`SlockKuiklyChildPage`) nest a second host with their own [args].
 */
@Composable
fun RaftPagerHost(
    factory: () -> Pager,
    args: SlockCmpPageArgs = LocalSlockPageArgs.current,
    active: Boolean = true,
) {
    val host = LocalSlockHost.current
    // The Raft Coil loader must be the singleton before any page asks Coil for an image.
    remember { raftInstallImageLoader() }
    val pager =
        remember(args) {
            factory().also { pager ->
                pager.pagerId = "raft-${pagerIdSequence.incrementAndGet()}"
                pager.pageData = PageData(args.pageData.toKuiklyJson())
                pager.host = host
                PagerManager.register(pager)
                pager.willInit()
                pager.created()
            }
        }

    val density = LocalDensity.current
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val safeDrawing = WindowInsets.safeDrawing
    val windowSize = LocalWindowInfo.current.containerSize
    SideEffect {
        with(density) {
            val pageData = pager.pageData
            pageData.density = density.density
            pageData.statusBarHeight = statusBars.getTop(this).toDp().value
            pageData.androidBottomBavBarHeight = navigationBars.getBottom(this).toDp().value
            pageData.safeAreaInsets =
                EdgeInsets(
                    top = safeDrawing.getTop(this).toDp().value,
                    left = safeDrawing.getLeft(this, LayoutDirection.Ltr).toDp().value,
                    bottom = safeDrawing.getBottom(this).toDp().value,
                    right = safeDrawing.getRight(this, LayoutDirection.Ltr).toDp().value,
                )
            val width = windowSize.width.toDp().value
            val height = windowSize.height.toDp().value
            pageData.pageViewWidth = width
            pageData.pageViewHeight = height
            pageData.deviceWidth = width
            pageData.deviceHeight = height
            pageData.activityWidth = width
            pageData.activityHeight = height
        }
    }

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
    DisposableEffect(pager) {
        onDispose {
            if (pager.isAppeared) pager.onReceivePagerEvent(Pager.PAGER_EVENT_DID_DISAPPEAR, JSONObject())
            pager.onReceivePagerEvent(Pager.PAGER_EVENT_WILL_DESTROY, JSONObject())
            PagerManager.unregister(pager)
        }
    }

    CompositionLocalProvider(
        LocalActivity provides remember(pager) { RaftPageActivity(pager) },
        LocalSlockPageArgs provides args,
    ) {
        (pager as? ComposeContainer)?.composedContent?.invoke()
    }
}
