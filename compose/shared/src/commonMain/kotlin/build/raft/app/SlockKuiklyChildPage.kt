package build.raft.app

import build.raft.app.base.BasePager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import build.raft.app.cmp.host.SlockCmpPageArgs
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.compat.pager.RaftPagerHost
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.json.JSONObject

const val SLOCK_CHILD_PAGE_HOST_SMOKE_PAGE = "compose_child_page_host_smoke"
private const val SLOCK_CHILD_PAGE_SMOKE_ONE_PAGE = "compose_child_page_smoke_one"
private const val SLOCK_CHILD_PAGE_SMOKE_TWO_PAGE = "compose_child_page_smoke_two"

@Composable
internal fun SlockKuiklyChildPage(
    pageName: String,
    pageData: JSONObject,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    glassStyle: String? = null,
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: () -> Unit = {}
) {
    val parentParams = LocalActivity.current.pageData.params
    val parentWindowId = parentParams.optString("slockWindowId")
    val parentKeyboardExcludes = parentParams.optBoolean("slockKeyboardInsetExcludesBottomSafeArea", false)
    val parentHostExcludesNav = parentParams.optBoolean("slockHostExcludesNavigationBar", false)
    val pageDataText =
        remember(pageData.toString(), parentWindowId, parentKeyboardExcludes, parentHostExcludesNav) {
            slockEmbeddedChildPageData(pageData)
                .apply {
                    // task #482: the child shares the host window; inherit its id so the
                    // per-window live inset snapshot resolves for this pager too, plus
                    // the host consumption flags that gate how the live snapshot is
                    // interpreted (host-excluded nav bar, watcher's nav-subtracted IME).
                    if (parentWindowId.isNotBlank() && !has("slockWindowId")) {
                        put("slockWindowId", parentWindowId)
                    }
                    if (parentKeyboardExcludes && !has("slockKeyboardInsetExcludesBottomSafeArea")) {
                        put("slockKeyboardInsetExcludesBottomSafeArea", true)
                    }
                    if (parentHostExcludesNav && !has("slockHostExcludesNavigationBar")) {
                        put("slockHostExcludesNavigationBar", true)
                    }
                }
                .toString()
        }
    // task #180: publish this page's measured window origin for overlays that must place in WINDOW
    // space (a page-local 384 drew the thread menu at the centre pane's top-left instead of 1904).
    // Measured on the HOST-provided modifier -- that one belongs to the parent composition, so
    // positionInRoot() is the page's real position in its parent. Do NOT measure a node created
    // inside this page: that reports the page against itself, always (0,0).
    val originKey = slockChildPageOriginKey(pageName, pageData)
    val childPageOriginState = remember(originKey) { SlockChildPageOriginRegistry.stateFor(originKey) }
    SideEffect {
        // A child page's own root view cannot report the window, so the parent's key is not
        // reachable here; the enclosing tab page carries its own origin and is linked by ITS host.
        childPageOriginState.linkParentOrigin(null)
    }
    DisposableEffect(originKey) {
        onDispose { SlockChildPageOriginRegistry.forget(originKey) }
    }
    // CMP: the child page is composed inline (it was a nested native Kuikly render view).
    // TODO(cmp-switch): glassStyle (iOS native glass chrome) is not rendered.
    val factory = RaftPageRegistry.factory(pageName)
    val childArgs =
        remember(pageName, pageDataText) {
            SlockCmpPageArgs.fromJsonText(pageName, pageDataText)
        }
    LaunchedEffect(pageName, pageDataText, factory != null) {
        if (factory != null) onLoadSuccess() else onLoadFailure()
    }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val measured = coordinates.positionInRoot()
            childPageOriginState.updateOrigin(measured)
            // task #180: pairs with the page's `page_post originKey=` line, so a key mismatch is
            // visible in one capture instead of costing another device round.
            slockTraceToPlatformLog(
                "SlockShellOverlay",
                "host_measure originKey=$originKey origin=$measured windowOrigin=" +
                    "${childPageOriginState.windowOrigin}"
            )
        }
    ) {
        if (factory != null) {
            RaftPagerHost(factory = factory, args = childArgs, active = active)
        }
    }
}

/**
 * Stable key linking a child page's host measurement to the state read inside that page.
 *
 * Both sides must compute the SAME key from the same pageData: the host passes the thread launch
 * payload into SlockKuiklyChildPage and the page reads its own params, which is that payload. The
 * "default" fallback keeps them equal when instanceId is absent -- a mismatch would be invisible
 * because the origin would simply read as a fresh zero state.
 */
internal fun slockChildPageOriginKey(pageName: String, pageData: JSONObject): String {
    val instance = pageData.optString("instanceId").trim().ifBlank { "default" }
    return "$pageName|$instance"
}

internal fun slockEmbeddedChildPageData(pageData: JSONObject): JSONObject =
    JSONObject(pageData.toString()).apply {
        put(SLOCK_EMBEDDED_CHILD_PAGE_PARAM, true)
    }

internal const val SLOCK_CHILD_LIFECYCLE_DID_APPEAR = "didAppear"
internal const val SLOCK_CHILD_LIFECYCLE_DID_DISAPPEAR = "didDisappear"
internal const val SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR = "viewDidAppear"
internal const val SLOCK_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR = "viewDidDisappear"

internal fun slockChildLifecycleShouldBeVisible(viewAppeared: Boolean, active: Boolean): Boolean =
    viewAppeared && active

internal fun slockChildLifecyclePagerAppearState(pagerEvent: String): Boolean? =
    when (pagerEvent) {
        SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR -> true
        SLOCK_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR -> false
        else -> null
    }

/**
 * Native nested hosts require both the view-visibility event (`didAppear`) and
 * the pager appear event (`viewDidAppear`) before they call child `onResume`.
 * The first AppShell child (Home) is created while the parent pager is still
 * appearing, so sending only one of the pair leaves the native surface blank.
 */
internal fun slockChildLifecycleEvents(
    previouslyVisible: Boolean,
    shouldBeVisible: Boolean
): List<String> {
    if (previouslyVisible == shouldBeVisible) {
        return emptyList()
    }
    return if (shouldBeVisible) {
        listOf(SLOCK_CHILD_LIFECYCLE_DID_APPEAR, SLOCK_CHILD_LIFECYCLE_VIEW_DID_APPEAR)
    } else {
        listOf(SLOCK_CHILD_LIFECYCLE_DID_DISAPPEAR, SLOCK_CHILD_LIFECYCLE_VIEW_DID_DISAPPEAR)
    }
}

@Page(SLOCK_CHILD_PAGE_HOST_SMOKE_PAGE, supportInLocal = true)
internal class SlockKuiklyChildPageHostSmokePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockKuiklyChildPageHostSmokeScreen() }
    }
}

@Composable
private fun SlockKuiklyChildPageHostSmokeScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    var firstStatus by remember { mutableStateOf("loading") }
    var secondStatus by remember { mutableStateOf("loading") }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(start = 16.dp, top = (statusBarHeight + 16).dp, end = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Child Page Host Smoke", style = SlockType.PageTitle, color = SlockColor.TextBlack)
        Text(
            text = "Android proof for nested Kuikly child pages with independent child delegators.",
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray
        )
        ChildPageStatusRow("first", firstStatus)
        SlockKuiklyChildPage(
            pageName = SLOCK_CHILD_PAGE_SMOKE_ONE_PAGE,
            pageData = childPageSmokeData("First child", "alpha"),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            onLoadSuccess = { firstStatus = "loaded" },
            onLoadFailure = { firstStatus = "failed" }
        )
        ChildPageStatusRow("second", secondStatus)
        SlockKuiklyChildPage(
            pageName = SLOCK_CHILD_PAGE_SMOKE_TWO_PAGE,
            pageData = childPageSmokeData("Second child", "beta"),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            onLoadSuccess = { secondStatus = "loaded" },
            onLoadFailure = { secondStatus = "failed" }
        )
    }
}

@Composable
private fun ChildPageStatusRow(label: String, status: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
        Text(status, style = SlockType.Caption, color = SlockColor.TextMutedGray)
    }
}

@Page(SLOCK_CHILD_PAGE_SMOKE_ONE_PAGE, supportInLocal = true)
internal class SlockKuiklyChildSmokeOnePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockKuiklyChildSmokeContent(tone = SlockColor.SurfaceWhite) }
    }
}

@Page(SLOCK_CHILD_PAGE_SMOKE_TWO_PAGE, supportInLocal = true)
internal class SlockKuiklyChildSmokeTwoPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockKuiklyChildSmokeContent(tone = SlockColor.BrandYellow) }
    }
}

@Composable
private fun SlockKuiklyChildSmokeContent(tone: androidx.compose.ui.graphics.Color) {
    val params = LocalActivity.current.pageData.params
    val label = params.optString("label", "Child page")
    val marker = params.optString("marker", "unknown")
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(tone)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = SlockType.ButtonBold, color = SlockColor.TextBlack)
        Text("pageData.marker = $marker", style = SlockType.Caption, color = SlockColor.TextMutedGray)
        Text(
            text = "If this renders inside the parent page, Android host registration and child onAttach pageData are working.",
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack
        )
    }
}

private fun childPageSmokeData(label: String, marker: String): JSONObject =
    JSONObject().apply {
        put("label", label)
        put("marker", marker)
        put("instanceId", "child-page-smoke-$marker")
    }
