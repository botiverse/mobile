package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.markdown.lucideIcon
import build.raft.app.resources.*
import build.raft.app.settings.rememberRaftPagerAppeared
import build.raft.app.settings.rememberSettingsWebViewAuthSnapshot
import build.raft.app.settings.settingsWebViewAuthEventSource
import build.raft.app.shell.RaftAppBottomTabBarHostZIndex
import build.raft.app.shell.RaftHorizontalSafeArea
import build.raft.app.shell.openRaftInAppBrowserTab
import build.raft.app.shell.raftBottomSafeAreaPadding
import build.raft.app.shell.raftHorizontalSafeArea
import build.raft.app.shell.raftShellStoreScopeOrNull
import build.raft.app.shell.raftWindowTopSafeAreaPadding
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftCloseButtonSize
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.gesture.raftKeepsEdgeTouches
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Edge-Docked Magnetic Floating Tab Pill and Multi-Page Switcher Sheet
 * (#proj-mobile:#398 / #proj-mobile:7420d9de, floating-pill-v2.html):
 * - Docked flush to either the left or right viewport edge (defaulting to Right) across Home &
 *   Conversations whenever ≥1 Raft App tab is open and suspended (hidden while an App page is
 *   visible in the foreground).
 * - Freely draggable horizontally and vertically within safe bounds (status bar + header at top;
 *   bottom nav/composer, IME, and active Agent Voice Call card at bottom; horizontal cutouts on
 *   landscape devices) and snaps magnetically to the closer left or right edge on release.
 * - Single suspended page: tapping immediately pushes & resumes that App page (warm WebView intact).
 * - Multiple suspended pages (≥2): shows stacked App icon + count badge [N]; tapping opens the
 *   multi-page switcher sheet listing all open pages (up to 5) with Live/Hibernated badges, per-page
 *   close ×, and Close all.
 */

/** Above content, bottom tab bar, and voice call card (+0.25f), below global modals (+0.5f). */
internal const val RaftFloatingBrowserTabPillZIndex: Float = RaftAppBottomTabBarHostZIndex + 0.35f

/** True inside an ancestor host that already mounts [RaftFloatingBrowserTabPillHost]. */
internal val LocalRaftFloatingBrowserPillHostMounted = compositionLocalOf { false }

internal const val RAFT_FLOATING_TAB_PILL_TAG = "raft_floating_browser_tab_pill"
internal const val RAFT_FLOATING_TAB_PILL_COUNT_TAG = "raft_floating_browser_tab_pill_count"
internal const val RAFT_APP_BROWSER_SWITCHER_SHEET_TAG = "raft_app_browser_switcher_sheet"
internal const val RAFT_APP_BROWSER_SWITCHER_CLOSE_ALL_TAG = "raft_app_browser_switcher_close_all"

internal val RaftFloatingPillCornerRadius: Dp = 22.dp

internal fun raftAppBrowserSwitcherRowTag(tabId: Long): String = "raft_app_browser_switcher_row_$tabId"

internal fun raftAppBrowserSwitcherCloseTag(tabId: Long): String = "raft_app_browser_switcher_close_$tabId"

internal fun raftFloatingArtifactSwitcherRowTag(windowKey: String): String = "raft_floating_artifact_switcher_row_$windowKey"

internal fun raftFloatingArtifactSwitcherCloseTag(windowKey: String): String = "raft_floating_artifact_switcher_close_$windowKey"

internal fun raftFloatingSwitcherExpandTag(idKey: String): String = "raft_floating_switcher_expand_$idKey"

/** Physical corner radii and horizontal padding for [RaftFloatingBrowserTabPill] by dock/drag state. */
internal data class RaftFloatingPillChromeSpec(
    val topLeftDp: Dp,
    val topRightDp: Dp,
    val bottomRightDp: Dp,
    val bottomLeftDp: Dp,
    val leftPaddingDp: Dp,
    val rightPaddingDp: Dp,
) {
    /** Converts physical corner radii into a Compose [RoundedCornerShape] respecting [layoutDirection]. */
    fun toShape(layoutDirection: LayoutDirection = LayoutDirection.Ltr): RoundedCornerShape =
        if (layoutDirection == LayoutDirection.Rtl) {
            RoundedCornerShape(
                topStart = topRightDp,
                topEnd = topLeftDp,
                bottomEnd = bottomLeftDp,
                bottomStart = bottomRightDp,
            )
        } else {
            RoundedCornerShape(
                topStart = topLeftDp,
                topEnd = topRightDp,
                bottomEnd = bottomRightDp,
                bottomStart = bottomLeftDp,
            )
        }
}

/**
 * Resolves the floating pill's physical corner radii and horizontal padding:
 * - While detached (actively dragging or settling across the screen): fully rounded capsule with
 *   symmetric horizontal padding (`9.dp` each side, preserving identical total width).
 * - Docked Left: flat physical left edge (`0.dp`), rounded right edge (`22.dp`), `8.dp` / `10.dp`.
 * - Docked Right: rounded physical left edge (`22.dp`), flat right edge (`0.dp`), `10.dp` / `8.dp`.
 */
internal fun resolveRaftFloatingPillChromeSpec(
    edge: RaftFloatingPillEdge,
    isDetached: Boolean,
): RaftFloatingPillChromeSpec =
    when {
        isDetached ->
            RaftFloatingPillChromeSpec(
                topLeftDp = RaftFloatingPillCornerRadius,
                topRightDp = RaftFloatingPillCornerRadius,
                bottomRightDp = RaftFloatingPillCornerRadius,
                bottomLeftDp = RaftFloatingPillCornerRadius,
                leftPaddingDp = 9.dp,
                rightPaddingDp = 9.dp,
            )
        edge == RaftFloatingPillEdge.Left ->
            RaftFloatingPillChromeSpec(
                topLeftDp = 0.dp,
                topRightDp = RaftFloatingPillCornerRadius,
                bottomRightDp = RaftFloatingPillCornerRadius,
                bottomLeftDp = 0.dp,
                leftPaddingDp = 8.dp,
                rightPaddingDp = 10.dp,
            )
        else ->
            RaftFloatingPillChromeSpec(
                topLeftDp = RaftFloatingPillCornerRadius,
                topRightDp = 0.dp,
                bottomRightDp = 0.dp,
                bottomLeftDp = RaftFloatingPillCornerRadius,
                leftPaddingDp = 10.dp,
                rightPaddingDp = 8.dp,
            )
    }

private class RaftFloatingPillMeasuredSizeBox(
    var widthPx: Float,
    var heightPx: Float,
)

private data class RaftFloatingPillHostMetrics(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val leftSafeAreaPx: Float,
    val rightSafeAreaPx: Float,
    val topBoundPx: Float,
    val bottomSafeAreaPx: Float,
    val bottomExtraPx: Float,
    val density: Density,
) {
    fun resolveBottomBoundPx(imeBottomPx: Float): Float =
        maxOf(imeBottomPx.coerceAtLeast(0f), bottomSafeAreaPx.coerceAtLeast(0f)) + bottomExtraPx.coerceAtLeast(0f)

    fun horizontalRange(pillWidthPx: Float): RaftFloatingPillHorizontalRange =
        resolveRaftFloatingPillHorizontalRange(
            viewportWidthPx = viewportWidthPx,
            pillWidthPx = pillWidthPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
        )

    fun dockedOffsetXPx(
        edge: RaftFloatingPillEdge,
        pillWidthPx: Float,
    ): Float =
        resolveRaftFloatingPillDockedOffsetXPx(
            edge = edge,
            viewportWidthPx = viewportWidthPx,
            pillWidthPx = pillWidthPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
        )
}

private data class RaftFloatingPillLiveYBounds(
    val minY: Float,
    val maxY: Float,
    val bottomBoundPx: Float,
)

/**
 * Window-level host installed by [ProvideRaftLinkOpening] on non-browser/non-artifact mobile pages.
 * Observes account/server/environment switches (clearing all floating Apps and Artifacts cleanly on
 * any boundary change once auth is hydrated, warming the installed-App origin cache on startup) and
 * renders EITHER the interactive resizable Mini-Window OR the bilateral magnetic Edge Pill +
 * unified App/Artifact switcher sheet.
 */
@Composable
internal fun RaftFloatingBrowserTabPillHost(
    pager: Pager,
    bridge: BridgeModule,
    floatingCoordinator: RaftFloatingWindowCoordinator = RaftFloatingWindowCoordinator.process,
    coordinator: RaftMobileWebPanelCoordinator = floatingCoordinator.webPanelCoordinator,
) {
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val activeEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val storeScope =
        LocalRaftArtifactOpenStoreScope.current
            ?: remember(pager.pageData.params.toString()) { pager.pageData.params.raftShellStoreScopeOrNull() }
    val authStore =
        remember(sharedStores, storeScope) {
            if (storeScope != null) sharedStores.authStore(storeScope) else sharedStores.authStore
        }
    val authEvents =
        remember(authStore, sharedStores.authStore) {
            settingsWebViewAuthEventSource(authStore, sharedStores.authStore)
        }
    val initialSession =
        remember(sharedStores.authStore, storeScope) {
            val committed = runCatching { sharedStores.authStore.readCommittedSessionSnapshot() }.getOrNull()
            val expectedServerId = storeScope?.initialServerId?.trim()?.takeIf { it.isNotEmpty() }
            if (committed != null && (expectedServerId == null || committed.currentServerId == expectedServerId)) {
                committed
            } else {
                null
            }
        }
    val authSnapshot =
        rememberSettingsWebViewAuthSnapshot(
            shellAuthStore = authStore,
            processAuthEvents = authEvents,
            initialSession = initialSession,
        )
    val environmentKey =
        remember(activeEnvironment.webUrl, activeEnvironment.apiBaseUrl) {
            "${activeEnvironment.webUrl}|${activeEnvironment.apiBaseUrl}"
        }
    val accountId = authSnapshot.session?.user?.id
    val currentServerId = authSnapshot.session?.currentServerId

    LaunchedEffect(floatingCoordinator, sharedStores, authSnapshot.hydrated, accountId, currentServerId, environmentKey) {
        if (!authSnapshot.hydrated) return@LaunchedEffect
        floatingCoordinator.onSessionContextChanged(
            accountId = accountId,
            serverId = currentServerId,
            environmentKey = environmentKey,
        )
        sharedStores.onInstalledAppAccountOrEnvironmentChanged(accountId, environmentKey)
        val cleanAccountId = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServerId = currentServerId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanAccountId != null && cleanServerId != null && raftPlatformSupportsInAppBrowser()) {
            sharedStores.installedAppOriginsCache.warm(
                RaftInstalledAppScope(
                    accountId = cleanAccountId,
                    environmentKey = environmentKey,
                    serverId = cleanServerId,
                ),
            )
        }
    }

    val pageVisible = rememberRaftPagerAppeared(pager)
    val tabsState by coordinator.state.collectAsState()
    val windowState by floatingCoordinator.state.collectAsState()
    val snapshot =
        remember(tabsState, windowState) {
            resolveRaftFloatingWindowSnapshot(tabsState, windowState)
        }
    val activeVoiceCall by sharedStores.agentVoiceCallStore.call.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var switcherOpen by remember { mutableStateOf(false) }

    LaunchedEffect(snapshot.minimizedEntries.isEmpty(), snapshot.hasFullScreenSurface, snapshot.showMiniWindow) {
        if (snapshot.minimizedEntries.isEmpty() || snapshot.hasFullScreenSurface || snapshot.showMiniWindow) {
            switcherOpen = false
        }
    }

    if (!pageVisible) return
    if (!snapshot.showMiniWindow && !snapshot.showEdgePill) return

    fun expandAppTabToFullScreen(tab: RaftAppBrowserTab) {
        val activated = floatingCoordinator.prepareExpandAppTabToFullScreen(tab.tabId) ?: return
        coroutineScope.launch {
            // Wait 1 frame so any unmounting Mini-Window finishes parking its warm WebView before
            // the full-screen page takes the same keepAliveKey.
            withFrameNanos { }
            val opened =
                pager.openRaftInAppBrowserTab(
                    tab = activated,
                    storeScopeOverride = storeScope,
                )
            if (!opened) {
                floatingCoordinator.floatAppTabInMiniWindow(activated.tabId)
            }
        }
    }

    fun expandArtifactToFullScreen(artifact: RaftFloatingArtifactWindow) {
        val promoted = floatingCoordinator.prepareExpandArtifactToFullScreen(artifact.windowKey) ?: return
        RaftArtifactViewerMemory.cache.remember(
            promoted.scope,
            RaftArtifactViewerHeader(
                id = promoted.artifactId,
                title = promoted.title,
                kind = promoted.kind,
                version = promoted.version,
            ),
        )
        coroutineScope.launch {
            withFrameNanos { }
            val opened =
                pager.openRaftArtifactViewerPage(
                    target = promoted.target,
                    storeScope = promoted.storeScope ?: storeScope,
                    conversationId = promoted.conversationId,
                )
            if (!opened) {
                floatingCoordinator.floatArtifactInMiniWindow(promoted)
            }
        }
    }

    val voiceCallExtra = if (activeVoiceCall != null) 88.dp else 0.dp
    val horizontalSafe = raftHorizontalSafeArea()

    RaftDesignSystemTheme {
        val activeMiniEntry = snapshot.activeMiniWindowEntry
        if (snapshot.showMiniWindow && activeMiniEntry != null) {
            RaftFloatingMiniWindowLiveHost(
                entry = activeMiniEntry,
                pager = pager,
                bridge = bridge,
                storeScope = storeScope,
                floatingCoordinator = floatingCoordinator,
                placement = snapshot.miniWindowPlacement,
                onPlacementChange = floatingCoordinator::updateMiniWindowPlacement,
                onCollapseToPill = { floatingCoordinator.collapseMiniWindowToEdgePill() },
                onExpandToFullScreen = {
                    when (activeMiniEntry) {
                        is RaftFloatingWindowEntry.AppTab -> expandAppTabToFullScreen(activeMiniEntry.tab)
                        is RaftFloatingWindowEntry.Artifact -> expandArtifactToFullScreen(activeMiniEntry.artifact)
                    }
                },
                onClose = { floatingCoordinator.closeWindow(activeMiniEntry.id) },
                horizontalSafeArea = horizontalSafe,
                voiceCallExtraDp = voiceCallExtra,
            )
        } else if (snapshot.showEdgePill) {
            RaftFloatingBrowserTabPillOverlay(
                tabs = snapshot.appTabs,
                artifacts = snapshot.artifacts,
                leadIsArtifactOverride = snapshot.minimizedEntries.firstOrNull() is RaftFloatingWindowEntry.Artifact,
                pillYFraction = snapshot.pillYFraction,
                onPillYFractionChange = floatingCoordinator::setPillYFraction,
                onClick = {
                    val entries = snapshot.minimizedEntries
                    if (entries.size == 1) {
                        floatingCoordinator.openInMiniWindow(entries.first().id)
                    } else if (entries.size >= 2) {
                        switcherOpen = true
                    }
                },
                pillEdge = snapshot.pillEdge,
                onPillEdgeChange = floatingCoordinator::setPillEdge,
                onPillDockPositionChange = floatingCoordinator::setPillDockPosition,
                horizontalSafeArea = horizontalSafe,
                voiceCallExtraDp = voiceCallExtra,
            )
        }

        if (switcherOpen && snapshot.minimizedEntries.isNotEmpty()) {
            RaftAppBrowserSwitcherSheet(
                tabs = snapshot.appTabs,
                activeTabId = snapshot.activeFullScreenAppTabId,
                onSelectTab = { tab ->
                    switcherOpen = false
                    floatingCoordinator.floatAppTabInMiniWindow(tab.tabId)
                },
                onCloseTab = { tabId ->
                    floatingCoordinator.closeAppTab(tabId)
                },
                onCloseAll = {
                    switcherOpen = false
                    floatingCoordinator.closeAll()
                },
                onDismiss = { switcherOpen = false },
                artifacts = snapshot.artifacts,
                orderedEntries = snapshot.minimizedEntries,
                onSelectArtifact = { artifact ->
                    switcherOpen = false
                    floatingCoordinator.openInMiniWindow(artifact.windowId)
                },
                onExpandTab = { tab ->
                    switcherOpen = false
                    expandAppTabToFullScreen(tab)
                },
                onExpandArtifact = { artifact ->
                    switcherOpen = false
                    expandArtifactToFullScreen(artifact)
                },
                onCloseArtifact = { windowKey ->
                    floatingCoordinator.closeArtifact(windowKey)
                },
            )
        }
    }
}

/**
 * Draggable overlay surface hosting [RaftFloatingBrowserTabPill] docked to either the left or right
 * viewport edge. Placement reads live IME, drag, and spring state strictly inside `.layout` so
 * keyboard, drag, and snap frames skip composition/measurement, while `.pointerInput(Unit)` +
 * `.raftKeepsEdgeTouches()` (superset of `raftKeepsBackSwipe`) keep drags and left-edge taps immune
 * to IME updates and system/iOS edge back-swipe gestures.
 */
@Composable
internal fun RaftFloatingBrowserTabPillOverlay(
    tabs: List<RaftAppBrowserTab>,
    pillYFraction: Float,
    onPillYFractionChange: (Float) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pillEdge: RaftFloatingPillEdge = RaftFloatingPillEdge.Right,
    onPillEdgeChange: (RaftFloatingPillEdge) -> Unit = {},
    onPillDockPositionChange: ((RaftFloatingPillEdge, Float) -> Unit)? = null,
    topSafeArea: Dp = raftWindowTopSafeAreaPadding(),
    bottomSafeArea: Dp = raftBottomSafeAreaPadding(),
    horizontalSafeArea: RaftHorizontalSafeArea = RaftHorizontalSafeArea.Zero,
    voiceCallExtraDp: Dp = 0.dp,
    artifacts: List<RaftFloatingArtifactWindow> = emptyList(),
    leadIsArtifactOverride: Boolean? = null,
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val hostImeInsets = LocalRaftHostImeInsets.current
    val reducedMotion = rememberRaftReducedMotion()
    val coroutineScope = rememberCoroutineScope()
    val topBoundPx = with(density) { (topSafeArea.coerceAtLeast(0.dp) + 64.dp).toPx() }
    val bottomSafeAreaPx = with(density) { bottomSafeArea.coerceAtLeast(0.dp).toPx() }
    val bottomExtraPx = with(density) { (84.dp + voiceCallExtraDp.coerceAtLeast(0.dp)).toPx() }
    val leftSafeAreaPx = with(density) { horizontalSafeArea.left.coerceAtLeast(0.dp).toPx() }
    val rightSafeAreaPx = with(density) { horizontalSafeArea.right.coerceAtLeast(0.dp).toPx() }
    val defaultPillWidthPx = with(density) { 48.dp.toPx() }
    val defaultPillHeightPx = with(density) { 48.dp.toPx() }

    val measuredPillSize = remember { RaftFloatingPillMeasuredSizeBox(defaultPillWidthPx, defaultPillHeightPx) }
    var localPillEdge by remember { mutableStateOf(pillEdge) }
    var localPillYFraction by remember { mutableFloatStateOf(pillYFraction) }
    var dragging by remember { mutableStateOf(false) }
    var snapAnimating by remember { mutableStateOf(false) }
    var dragXPx by remember { mutableFloatStateOf(0f) }
    var dragYPx by remember { mutableFloatStateOf(0f) }
    val snapOffsetFromDockedX = remember { Animatable(0f) }

    LaunchedEffect(pillEdge) {
        if (!dragging && localPillEdge != pillEdge) {
            localPillEdge = pillEdge
            snapAnimating = false
            if (snapOffsetFromDockedX.isRunning || snapOffsetFromDockedX.value != 0f) {
                snapOffsetFromDockedX.snapTo(0f)
            }
        }
    }

    LaunchedEffect(pillYFraction) {
        if (!dragging) {
            localPillYFraction = pillYFraction
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().zIndex(RaftFloatingBrowserTabPillZIndex),
    ) {
        val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val hostMetrics =
            RaftFloatingPillHostMetrics(
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                leftSafeAreaPx = leftSafeAreaPx,
                rightSafeAreaPx = rightSafeAreaPx,
                topBoundPx = topBoundPx,
                bottomSafeAreaPx = bottomSafeAreaPx,
                bottomExtraPx = bottomExtraPx,
                density = density,
            )
        val latestMetrics by rememberUpdatedState(hostMetrics)
        val latestReducedMotion by rememberUpdatedState(reducedMotion)
        val latestOnPillYFractionChange by rememberUpdatedState(onPillYFractionChange)
        val latestOnPillEdgeChange by rememberUpdatedState(onPillEdgeChange)
        val latestOnPillDockPositionChange by rememberUpdatedState(onPillDockPositionChange)

        RaftFloatingBrowserTabPill(
            tabs = tabs,
            onClick = onClick,
            edge = localPillEdge,
            isDetached = dragging || snapAnimating,
            artifacts = artifacts,
            leadIsArtifactOverride = leadIsArtifactOverride,
            modifier =
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (placeable.width > 0) {
                            measuredPillSize.widthPx = placeable.width.toFloat()
                        }
                        if (placeable.height > 0) {
                            measuredPillSize.heightPx = placeable.height.toFloat()
                        }
                        layout(placeable.width, placeable.height) {
                            // Read live IME, drag, and spring state strictly in placement so drag,
                            // snap, and keyboard frames skip composition and measurement.
                            val liveImeBottomPx = raftImeBottomPx(imeInsets, hostImeInsets, hostMetrics.density).toFloat()
                            val liveBottomBoundPx = hostMetrics.resolveBottomBoundPx(liveImeBottomPx)
                            val pillWidthPx = placeable.width.toFloat()
                            val pillHeightPx = placeable.height.toFloat()
                            val minY = hostMetrics.topBoundPx.coerceAtLeast(0f)
                            val maxY = (hostMetrics.viewportHeightPx - liveBottomBoundPx - pillHeightPx).coerceAtLeast(minY)
                            val yPx =
                                if (dragging) {
                                    dragYPx.coerceIn(minY, maxY)
                                } else {
                                    resolveRaftFloatingPillOffsetYPx(
                                        viewportHeightPx = hostMetrics.viewportHeightPx,
                                        pillHeightPx = pillHeightPx,
                                        topBoundPx = hostMetrics.topBoundPx,
                                        bottomBoundPx = liveBottomBoundPx,
                                        yFraction = localPillYFraction,
                                    )
                                }
                            val activeEdge = localPillEdge
                            val dockedX = hostMetrics.dockedOffsetXPx(activeEdge, pillWidthPx)
                            val xPx =
                                if (dragging) {
                                    clampRaftFloatingPillOffsetXPx(
                                        offsetXPx = dragXPx,
                                        viewportWidthPx = hostMetrics.viewportWidthPx,
                                        pillWidthPx = pillWidthPx,
                                        leftSafeAreaPx = hostMetrics.leftSafeAreaPx,
                                        rightSafeAreaPx = hostMetrics.rightSafeAreaPx,
                                        fallbackEdge = activeEdge,
                                    )
                                } else {
                                    clampRaftFloatingPillOffsetXPx(
                                        offsetXPx = dockedX + snapOffsetFromDockedX.value,
                                        viewportWidthPx = hostMetrics.viewportWidthPx,
                                        pillWidthPx = pillWidthPx,
                                        leftSafeAreaPx = hostMetrics.leftSafeAreaPx,
                                        rightSafeAreaPx = hostMetrics.rightSafeAreaPx,
                                        fallbackEdge = activeEdge,
                                    )
                                }
                            placeable.place(
                                x = xPx.roundToInt(),
                                y = yPx.roundToInt(),
                            )
                        }
                    }
                    .raftKeepsEdgeTouches()
                    .pointerInput(Unit) {
                        var snapGeneration = 0

                        fun resolveLiveYBounds(): RaftFloatingPillLiveYBounds {
                            val metrics = latestMetrics
                            val liveImeBottomPx = raftImeBottomPx(imeInsets, hostImeInsets, metrics.density).toFloat()
                            val bottomBoundPx = metrics.resolveBottomBoundPx(liveImeBottomPx)
                            val minY = metrics.topBoundPx.coerceAtLeast(0f)
                            val maxY =
                                (metrics.viewportHeightPx - bottomBoundPx - measuredPillSize.heightPx)
                                    .coerceAtLeast(minY)
                            return RaftFloatingPillLiveYBounds(
                                minY = minY,
                                maxY = maxY,
                                bottomBoundPx = bottomBoundPx,
                            )
                        }

                        fun finishDrag(
                            commitTargetEdge: Boolean,
                            animateSnap: Boolean,
                        ) {
                            val metrics = latestMetrics
                            val bounds = resolveLiveYBounds()
                            val currentEdge = localPillEdge
                            val finalY = dragYPx.coerceIn(bounds.minY, bounds.maxY)
                            val finalX =
                                clampRaftFloatingPillOffsetXPx(
                                    offsetXPx = dragXPx,
                                    viewportWidthPx = metrics.viewportWidthPx,
                                    pillWidthPx = measuredPillSize.widthPx,
                                    leftSafeAreaPx = metrics.leftSafeAreaPx,
                                    rightSafeAreaPx = metrics.rightSafeAreaPx,
                                    fallbackEdge = currentEdge,
                                )
                            val fraction =
                                clampRaftFloatingPillYFraction(
                                    offsetYPx = finalY,
                                    viewportHeightPx = metrics.viewportHeightPx,
                                    pillHeightPx = measuredPillSize.heightPx,
                                    topBoundPx = metrics.topBoundPx,
                                    bottomBoundPx = bounds.bottomBoundPx,
                                )
                            val targetEdge =
                                if (commitTargetEdge) {
                                    resolveRaftFloatingPillSnapEdge(
                                        offsetXPx = finalX,
                                        viewportWidthPx = metrics.viewportWidthPx,
                                        pillWidthPx = measuredPillSize.widthPx,
                                        leftSafeAreaPx = metrics.leftSafeAreaPx,
                                        rightSafeAreaPx = metrics.rightSafeAreaPx,
                                        currentEdge = currentEdge,
                                    )
                                } else {
                                    currentEdge
                                }
                            val targetDockedX = metrics.dockedOffsetXPx(targetEdge, measuredPillSize.widthPx)
                            val deltaFromTarget = finalX - targetDockedX

                            localPillEdge = targetEdge
                            localPillYFraction = fraction
                            val dockCallback = latestOnPillDockPositionChange
                            if (dockCallback != null) {
                                dockCallback(targetEdge, fraction)
                            } else {
                                latestOnPillEdgeChange(targetEdge)
                                latestOnPillYFractionChange(fraction)
                            }
                            dragging = false

                            val myGeneration = ++snapGeneration
                            if (!animateSnap || latestReducedMotion || abs(deltaFromTarget) < 0.5f) {
                                snapAnimating = false
                                if (snapOffsetFromDockedX.isRunning || snapOffsetFromDockedX.value != 0f) {
                                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        snapOffsetFromDockedX.snapTo(0f)
                                    }
                                }
                            } else {
                                snapAnimating = true
                                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    try {
                                        snapOffsetFromDockedX.snapTo(deltaFromTarget)
                                        snapOffsetFromDockedX.animateTo(
                                            targetValue = 0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                        )
                                    } finally {
                                        if (snapGeneration == myGeneration) {
                                            snapAnimating = false
                                        }
                                    }
                                }
                            }
                        }

                        detectDragGestures(
                            onDragStart = {
                                val metrics = latestMetrics
                                val bounds = resolveLiveYBounds()
                                val currentEdge = localPillEdge
                                val currentDockedY =
                                    resolveRaftFloatingPillOffsetYPx(
                                        viewportHeightPx = metrics.viewportHeightPx,
                                        pillHeightPx = measuredPillSize.heightPx,
                                        topBoundPx = metrics.topBoundPx,
                                        bottomBoundPx = bounds.bottomBoundPx,
                                        yFraction = localPillYFraction,
                                    )
                                val currentDockedX = metrics.dockedOffsetXPx(currentEdge, measuredPillSize.widthPx)
                                val currentVisualX =
                                    clampRaftFloatingPillOffsetXPx(
                                        offsetXPx = currentDockedX + snapOffsetFromDockedX.value,
                                        viewportWidthPx = metrics.viewportWidthPx,
                                        pillWidthPx = measuredPillSize.widthPx,
                                        leftSafeAreaPx = metrics.leftSafeAreaPx,
                                        rightSafeAreaPx = metrics.rightSafeAreaPx,
                                        fallbackEdge = currentEdge,
                                    )
                                dragXPx = currentVisualX
                                dragYPx = currentDockedY.coerceIn(bounds.minY, bounds.maxY)
                                snapGeneration += 1
                                snapAnimating = false
                                dragging = true
                                if (snapOffsetFromDockedX.isRunning || snapOffsetFromDockedX.value != 0f) {
                                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        snapOffsetFromDockedX.snapTo(0f)
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val metrics = latestMetrics
                                val bounds = resolveLiveYBounds()
                                val xRange = metrics.horizontalRange(measuredPillSize.widthPx)
                                dragXPx = (dragXPx + dragAmount.x).coerceIn(xRange.minX, xRange.maxX)
                                dragYPx = (dragYPx + dragAmount.y).coerceIn(bounds.minY, bounds.maxY)
                            },
                            onDragEnd = { finishDrag(commitTargetEdge = true, animateSnap = true) },
                            onDragCancel = { finishDrag(commitTargetEdge = false, animateSnap = false) },
                        )
                    },
        )
    }
}

/**
 * Pure presentation for the edge-docked magnetic floating tab pill across Apps and Artifacts:
 * - 1 minimized item: compact App logo or Artifact icon + subtle live-state indicator dot.
 * - ≥2 minimized items: lead App/Artifact icon + crisp `[N]` count badge.
 * - Mirrors its flat edge and horizontal padding for [RaftFloatingPillEdge.Left] vs
 *   [RaftFloatingPillEdge.Right], and uses a fully rounded capsule while [isDetached] is true.
 */
@Composable
internal fun RaftFloatingBrowserTabPill(
    tabs: List<RaftAppBrowserTab>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    edge: RaftFloatingPillEdge = RaftFloatingPillEdge.Right,
    isDetached: Boolean = false,
    artifacts: List<RaftFloatingArtifactWindow> = emptyList(),
    leadIsArtifactOverride: Boolean? = null,
) {
    val totalCount = tabs.size + artifacts.size
    if (totalCount == 0) return
    val leadTab = tabs.firstOrNull()
    val leadArtifact = artifacts.firstOrNull()
    val leadIsArtifact =
        when {
            leadArtifact == null -> false
            leadTab == null -> true
            leadIsArtifactOverride != null -> leadIsArtifactOverride
            else -> leadArtifact.lastUsedSeq > leadTab.lastUsedSeq
        }
    val colors = RaftTheme.colors
    val dark = RaftTheme.isDark
    val layoutDirection = LocalLayoutDirection.current
    val chromeSpec = resolveRaftFloatingPillChromeSpec(edge = edge, isDetached = isDetached)
    val pillShape = chromeSpec.toShape(layoutDirection)
    val untitled = stringResource(Res.string.artifacts_activity_untitled)
    val leadLabel =
        if (leadIsArtifact) {
            leadArtifact!!.displayTitle.ifBlank { untitled }
        } else {
            leadTab!!.displayName
        }
    val a11yLabel =
        if (totalCount == 1) {
            stringResource(Res.string.in_app_browser_floating_pill_single_a11y, leadLabel)
        } else {
            stringResource(Res.string.in_app_browser_floating_pill_multi_a11y, totalCount)
        }

    Row(
        modifier =
            modifier
                .testTag(RAFT_FLOATING_TAB_PILL_TAG)
                .semantics { contentDescription = a11yLabel }
                .shadow(elevation = if (dark) 10.dp else 6.dp, shape = pillShape)
                .clip(pillShape)
                .background(colors.layerPopover, pillShape)
                .border(RaftStroke.Hairline, colors.lineSeam, pillShape)
                .raftClickable(onClick = onClick, shape = pillShape, tier = RaftHoverTier.Subtle)
                .absolutePadding(
                    left = chromeSpec.leftPaddingDp,
                    right = chromeSpec.rightPaddingDp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            if (leadIsArtifact) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(RaftRadius.Chip))
                            .background(colors.fillSubtle)
                            .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Chip)),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftSlotIcon(
                        name = leadArtifact!!.kind.lucideIcon(),
                        size = 14.dp,
                        color = colors.foreground,
                    )
                }
            } else {
                RaftConnectedAppLogo(
                    name = leadTab!!.displayName,
                    logoUrl = leadTab.appLogoUrl,
                    size = RaftAvatarSize.Xs,
                )
            }
            if (totalCount == 1) {
                val isLive = if (leadIsArtifact) true else leadTab!!.isLive
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.layerPopover, CircleShape)
                            .padding(1.5.dp)
                            .clip(CircleShape)
                            .background(
                                if (isLive) RaftUiPalette.presenceOnline else colors.foregroundHint,
                                CircleShape,
                            ),
                )
            }
        }
        if (totalCount >= 2) {
            RaftBadge(
                text = totalCount.toString(),
                appearance = RaftBadgeAppearance.Solid,
                variant = RaftBadgeVariant.Primary,
                modifier = Modifier.testTag(RAFT_FLOATING_TAB_PILL_COUNT_TAG),
            )
        }
    }
}

/**
 * Multi-page switcher bottom sheet shared by the edge-docked floating pill (≥2 minimized Apps and/or
 * Artifacts) and the top-bar `[N]` trigger inside an open Raft App page.
 */
@Composable
internal fun RaftAppBrowserSwitcherSheet(
    tabs: List<RaftAppBrowserTab>,
    activeTabId: Long?,
    onSelectTab: (RaftAppBrowserTab) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
    artifacts: List<RaftFloatingArtifactWindow> = emptyList(),
    orderedEntries: List<RaftFloatingWindowEntry>? = null,
    onSelectArtifact: (RaftFloatingArtifactWindow) -> Unit = {},
    onExpandTab: ((RaftAppBrowserTab) -> Unit)? = null,
    onExpandArtifact: ((RaftFloatingArtifactWindow) -> Unit)? = null,
    onCloseArtifact: (String) -> Unit = {},
) {
    val mergedEntries =
        remember(tabs, artifacts, orderedEntries) {
            when {
                orderedEntries != null -> orderedEntries
                artifacts.isEmpty() -> tabs.map(RaftFloatingWindowEntry::AppTab)
                else ->
                    (tabs.map(RaftFloatingWindowEntry::AppTab) + artifacts.map(RaftFloatingWindowEntry::Artifact))
                        .sortedByDescending { it.lastUsedSeq }
            }
        }
    val totalCount = mergedEntries.size

    RaftSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.in_app_browser_open_pages_title, totalCount),
        showClose = true,
        actions = {
            RaftButton(
                text = stringResource(Res.string.in_app_browser_close_all_pages),
                onClick = onCloseAll,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Trash2) },
                modifier = Modifier.fillMaxWidth().testTag(RAFT_APP_BROWSER_SWITCHER_CLOSE_ALL_TAG),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(RAFT_APP_BROWSER_SWITCHER_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mergedEntries.forEach { entry ->
                when (entry) {
                    is RaftFloatingWindowEntry.AppTab ->
                        RaftAppBrowserSwitcherRow(
                            tab = entry.tab,
                            isActive = entry.tab.tabId == activeTabId,
                            onSelect = { onSelectTab(entry.tab) },
                            onExpand = onExpandTab?.let { expand -> { expand(entry.tab) } },
                            onClose = { onCloseTab(entry.tab.tabId) },
                        )
                    is RaftFloatingWindowEntry.Artifact ->
                        RaftFloatingArtifactSwitcherRow(
                            artifact = entry.artifact,
                            onSelect = { onSelectArtifact(entry.artifact) },
                            onExpand = onExpandArtifact?.let { expand -> { expand(entry.artifact) } },
                            onClose = { onCloseArtifact(entry.artifact.windowKey) },
                        )
                }
            }
        }
    }
}

@Composable
private fun RaftAppBrowserSwitcherRow(
    tab: RaftAppBrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onExpand: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val rowBg = if (isActive) colors.fillSubtle else colors.layerCard
    val borderColor = if (isActive) colors.lineStrong else colors.lineHairline

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(raftAppBrowserSwitcherRowTag(tab.tabId))
                .clip(shape)
                .background(rowBg, shape)
                .border(RaftStroke.Hairline, borderColor, shape)
                .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .raftClickable(onClick = onSelect, shape = shape, tier = RaftHoverTier.Subtle)
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RaftConnectedAppLogo(
                name = tab.displayName,
                logoUrl = tab.appLogoUrl,
                size = RaftAvatarSize.Sm,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RaftText(
                        text = tab.switcherPrimaryLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftBadge(
                        text =
                            stringResource(
                                if (tab.isLive) {
                                    Res.string.in_app_browser_tab_state_live
                                } else {
                                    Res.string.in_app_browser_tab_state_hibernated
                                },
                            ),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = if (tab.isLive) RaftBadgeVariant.Success else RaftBadgeVariant.Muted,
                    )
                }
                if (tab.displayHost.isNotBlank()) {
                    RaftSiteDomainText(
                        host = tab.displayHost,
                        style = RaftTheme.typography.caption,
                        color = colors.foregroundMuted,
                    )
                }
            }
        }
        if (onExpand != null) {
            RaftIconButton(
                onClick = onExpand,
                tooltip = stringResource(Res.string.floating_mini_window_expand_full_screen),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Sm,
                modifier = Modifier.testTag(raftFloatingSwitcherExpandTag("app_${tab.tabId}")),
            ) {
                RaftSlotIcon(RaftLucideIconName.Maximize2, size = 16.dp)
            }
        }
        Spacer(Modifier.width(2.dp))
        RaftCloseButton(
            onClick = onClose,
            label = stringResource(Res.string.in_app_browser_close_page),
            size = RaftCloseButtonSize.Dialog,
            modifier = Modifier.testTag(raftAppBrowserSwitcherCloseTag(tab.tabId)),
        )
    }
}

@Composable
private fun RaftFloatingArtifactSwitcherRow(
    artifact: RaftFloatingArtifactWindow,
    onSelect: () -> Unit,
    onExpand: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val untitled = stringResource(Res.string.artifacts_activity_untitled)
    val artifactKindLabel = stringResource(Res.string.floating_window_kind_artifact)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(raftFloatingArtifactSwitcherRowTag(artifact.windowKey))
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .raftClickable(onClick = onSelect, shape = shape, tier = RaftHoverTier.Subtle)
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(RaftRadius.Chip))
                        .background(colors.fillSubtle)
                        .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Chip)),
                contentAlignment = Alignment.Center,
            ) {
                RaftSlotIcon(
                    name = artifact.kind.lucideIcon(),
                    size = 16.dp,
                    color = colors.foregroundMuted,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RaftText(
                        text = artifact.displayTitle.ifBlank { untitled },
                        modifier = Modifier.weight(1f, fill = false),
                        style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftBadge(
                        text = artifact.kind.badge ?: artifactKindLabel,
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Muted,
                    )
                }
                RaftText(
                    text = artifactKindLabel,
                    style = RaftTheme.typography.caption,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onExpand != null) {
            RaftIconButton(
                onClick = onExpand,
                tooltip = stringResource(Res.string.floating_mini_window_expand_full_screen),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Sm,
                modifier = Modifier.testTag(raftFloatingSwitcherExpandTag("artifact_${artifact.windowKey}")),
            ) {
                RaftSlotIcon(RaftLucideIconName.Maximize2, size = 16.dp)
            }
        }
        Spacer(Modifier.width(2.dp))
        RaftCloseButton(
            onClick = onClose,
            label = stringResource(Res.string.in_app_browser_close_page),
            size = RaftCloseButtonSize.Dialog,
            modifier = Modifier.testTag(raftFloatingArtifactSwitcherCloseTag(artifact.windowKey)),
        )
    }
}
