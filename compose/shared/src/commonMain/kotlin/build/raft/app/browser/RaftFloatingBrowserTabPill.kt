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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.resources.*
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
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Option B Right-Edge Magnetic Floating Tab Pill and Multi-Page Switcher Sheet
 * (#proj-mobile:#398 / #proj-mobile:7420d9de, floating-pill-v2.html):
 * - Docked flush to the right viewport edge across Home & Conversations whenever ≥1 Raft App tab
 *   is open and suspended (hidden while an App page is visible in the foreground).
 * - Vertically draggable within safe bounds (status bar + header at top; bottom nav/composer, IME,
 *   and active Agent Voice Call card at bottom) with gentle horizontal pull resistance that snaps
 *   magnetically back to the right edge on release.
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

internal fun raftAppBrowserSwitcherRowTag(tabId: Long): String = "raft_app_browser_switcher_row_$tabId"

internal fun raftAppBrowserSwitcherCloseTag(tabId: Long): String = "raft_app_browser_switcher_close_$tabId"

private class RaftFloatingPillMeasuredHeightBox(
    var valuePx: Float,
)

private data class RaftFloatingPillHostMetrics(
    val viewportHeightPx: Float,
    val topBoundPx: Float,
    val bottomSafeAreaPx: Float,
    val bottomExtraPx: Float,
    val maxPullLeftPx: Float,
    val density: Density,
) {
    fun resolveBottomBoundPx(imeBottomPx: Float): Float =
        maxOf(imeBottomPx.coerceAtLeast(0f), bottomSafeAreaPx.coerceAtLeast(0f)) + bottomExtraPx.coerceAtLeast(0f)
}

private data class RaftFloatingPillLiveYBounds(
    val minY: Float,
    val maxY: Float,
    val bottomBoundPx: Float,
)

/**
 * Window-level host installed by [ProvideRaftLinkOpening] on non-browser mobile pages. Observes
 * account/server/environment switches (clearing all tabs cleanly on any boundary change once auth
 * is hydrated, warming the installed-App origin cache on startup) and renders the right-edge
 * magnetic floating tab pill + multi-page switcher sheet.
 */
@Composable
internal fun RaftFloatingBrowserTabPillHost(
    pager: Pager,
    bridge: BridgeModule,
    coordinator: RaftMobileWebPanelCoordinator = RaftMobileWebPanelCoordinator.process,
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

    LaunchedEffect(coordinator, sharedStores, authSnapshot.hydrated, accountId, currentServerId, environmentKey) {
        if (!authSnapshot.hydrated) return@LaunchedEffect
        coordinator.onSessionContextChanged(
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

    val tabsState by coordinator.state.collectAsState()
    val activeVoiceCall by sharedStores.agentVoiceCallStore.call.collectAsState()
    var switcherOpen by remember { mutableStateOf(false) }

    LaunchedEffect(tabsState.tabs.isEmpty(), tabsState.activeTabId) {
        if (tabsState.tabs.isEmpty() || tabsState.activeTabId != null) {
            switcherOpen = false
        }
    }

    if (!tabsState.showFloatingPill) return

    fun resumeTab(tab: RaftAppBrowserTab) {
        val activated = coordinator.activateTab(tab.tabId) ?: return
        val opened =
            pager.openRaftInAppBrowserTab(
                tab = activated,
                storeScopeOverride = storeScope,
            )
        if (!opened) {
            coordinator.suspendTab(activated.tabId)
        }
    }

    RaftDesignSystemTheme {
        RaftFloatingBrowserTabPillOverlay(
            tabs = tabsState.tabs,
            pillYFraction = tabsState.pillYFraction,
            onPillYFractionChange = coordinator::setPillYFraction,
            onClick = {
                val tabs = tabsState.tabs
                if (tabs.size == 1) {
                    resumeTab(tabs.first())
                } else if (tabs.size >= 2) {
                    switcherOpen = true
                }
            },
            voiceCallExtraDp = if (activeVoiceCall != null) 88.dp else 0.dp,
        )

        if (switcherOpen && tabsState.tabs.isNotEmpty()) {
            RaftAppBrowserSwitcherSheet(
                tabs = tabsState.tabs,
                activeTabId = tabsState.activeTabId,
                onSelectTab = { tab ->
                    switcherOpen = false
                    resumeTab(tab)
                },
                onCloseTab = { tabId ->
                    coordinator.closeTab(tabId)
                },
                onCloseAll = {
                    switcherOpen = false
                    coordinator.closeAll()
                },
                onDismiss = { switcherOpen = false },
            )
        }
    }
}

/**
 * Draggable overlay surface hosting [RaftFloatingBrowserTabPill] on the right viewport edge.
 * Placement reads live IME, drag, and spring state inside `.layout` so keyboard and drag frames
 * skip composition/measurement, while `.pointerInput(Unit)` + `.raftKeepsBackSwipe()` keep drags
 * immune to IME updates and system edge back-swipe gestures.
 */
@Composable
internal fun RaftFloatingBrowserTabPillOverlay(
    tabs: List<RaftAppBrowserTab>,
    pillYFraction: Float,
    onPillYFractionChange: (Float) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topSafeArea: Dp = raftWindowTopSafeAreaPadding(),
    bottomSafeArea: Dp = raftBottomSafeAreaPadding(),
    voiceCallExtraDp: Dp = 0.dp,
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val hostImeInsets = LocalRaftHostImeInsets.current
    val reducedMotion = rememberRaftReducedMotion()
    val coroutineScope = rememberCoroutineScope()
    val topBoundPx = with(density) { (topSafeArea.coerceAtLeast(0.dp) + 64.dp).toPx() }
    val bottomSafeAreaPx = with(density) { bottomSafeArea.coerceAtLeast(0.dp).toPx() }
    val bottomExtraPx = with(density) { (84.dp + voiceCallExtraDp.coerceAtLeast(0.dp)).toPx() }
    val maxPullLeftPx = with(density) { 24.dp.toPx() }
    val defaultPillHeightPx = with(density) { 48.dp.toPx() }

    val measuredPillHeight = remember { RaftFloatingPillMeasuredHeightBox(defaultPillHeightPx) }
    var dragging by remember { mutableStateOf(false) }
    var dragYPx by remember { mutableFloatStateOf(0f) }
    var dragPullXPx by remember { mutableFloatStateOf(0f) }
    val pullOffsetX = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().zIndex(RaftFloatingBrowserTabPillZIndex),
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val hostMetrics =
            RaftFloatingPillHostMetrics(
                viewportHeightPx = viewportHeightPx,
                topBoundPx = topBoundPx,
                bottomSafeAreaPx = bottomSafeAreaPx,
                bottomExtraPx = bottomExtraPx,
                maxPullLeftPx = maxPullLeftPx,
                density = density,
            )
        val latestMetrics by rememberUpdatedState(hostMetrics)
        val latestPillYFraction by rememberUpdatedState(pillYFraction)
        val latestReducedMotion by rememberUpdatedState(reducedMotion)
        val latestOnPillYFractionChange by rememberUpdatedState(onPillYFractionChange)

        RaftFloatingBrowserTabPill(
            tabs = tabs,
            onClick = onClick,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (placeable.height > 0) {
                            measuredPillHeight.valuePx = placeable.height.toFloat()
                        }
                        layout(placeable.width, placeable.height) {
                            // Read live IME, drag, and spring state strictly in placement so drag
                            // and keyboard frames skip composition and measurement.
                            val liveImeBottomPx = raftImeBottomPx(imeInsets, hostImeInsets, hostMetrics.density).toFloat()
                            val liveBottomBoundPx = hostMetrics.resolveBottomBoundPx(liveImeBottomPx)
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
                                        yFraction = pillYFraction,
                                    )
                                }
                            val xPx = if (dragging) dragPullXPx else pullOffsetX.value
                            placeable.place(
                                x = xPx.roundToInt(),
                                y = yPx.roundToInt(),
                            )
                        }
                    }
                    .raftKeepsBackSwipe()
                    .pointerInput(Unit) {
                        fun resolveLiveYBounds(): RaftFloatingPillLiveYBounds {
                            val metrics = latestMetrics
                            val liveImeBottomPx = raftImeBottomPx(imeInsets, hostImeInsets, metrics.density).toFloat()
                            val bottomBoundPx = metrics.resolveBottomBoundPx(liveImeBottomPx)
                            val minY = metrics.topBoundPx.coerceAtLeast(0f)
                            val maxY =
                                (metrics.viewportHeightPx - bottomBoundPx - measuredPillHeight.valuePx)
                                    .coerceAtLeast(minY)
                            return RaftFloatingPillLiveYBounds(
                                minY = minY,
                                maxY = maxY,
                                bottomBoundPx = bottomBoundPx,
                            )
                        }

                        fun finishDrag(animatePullBack: Boolean) {
                            val metrics = latestMetrics
                            val bounds = resolveLiveYBounds()
                            val finalY = dragYPx.coerceIn(bounds.minY, bounds.maxY)
                            val releasePullX = dragPullXPx
                            val fraction =
                                clampRaftFloatingPillYFraction(
                                    offsetYPx = finalY,
                                    viewportHeightPx = metrics.viewportHeightPx,
                                    pillHeightPx = measuredPillHeight.valuePx,
                                    topBoundPx = metrics.topBoundPx,
                                    bottomBoundPx = bounds.bottomBoundPx,
                                )
                            latestOnPillYFractionChange(fraction)
                            dragging = false
                            dragPullXPx = 0f
                            if (releasePullX != 0f || pullOffsetX.value != 0f) {
                                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    pullOffsetX.snapTo(releasePullX)
                                    if (!animatePullBack || latestReducedMotion) {
                                        pullOffsetX.snapTo(0f)
                                    } else {
                                        pullOffsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                        )
                                    }
                                }
                            }
                        }

                        detectDragGestures(
                            onDragStart = {
                                val metrics = latestMetrics
                                val bounds = resolveLiveYBounds()
                                val currentDockedY =
                                    resolveRaftFloatingPillOffsetYPx(
                                        viewportHeightPx = metrics.viewportHeightPx,
                                        pillHeightPx = measuredPillHeight.valuePx,
                                        topBoundPx = metrics.topBoundPx,
                                        bottomBoundPx = bounds.bottomBoundPx,
                                        yFraction = latestPillYFraction,
                                    )
                                dragYPx = currentDockedY.coerceIn(bounds.minY, bounds.maxY)
                                dragPullXPx = pullOffsetX.value.coerceIn(-metrics.maxPullLeftPx, 0f)
                                dragging = true
                                if (pullOffsetX.isRunning) {
                                    coroutineScope.launch { pullOffsetX.stop() }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val metrics = latestMetrics
                                val bounds = resolveLiveYBounds()
                                dragYPx = (dragYPx + dragAmount.y).coerceIn(bounds.minY, bounds.maxY)
                                dragPullXPx = (dragPullXPx + dragAmount.x * 0.45f).coerceIn(-metrics.maxPullLeftPx, 0f)
                            },
                            onDragEnd = { finishDrag(animatePullBack = true) },
                            onDragCancel = { finishDrag(animatePullBack = false) },
                        )
                    },
        )
    }
}

/**
 * Pure presentation for the right-edge magnetic floating tab pill (Option B):
 * - 1 tab: compact App logo + subtle live-state indicator dot.
 * - ≥2 tabs: stacked App logo + crisp `[N]` count badge.
 */
@Composable
internal fun RaftFloatingBrowserTabPill(
    tabs: List<RaftAppBrowserTab>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leadTab = tabs.firstOrNull() ?: return
    val colors = RaftTheme.colors
    val dark = RaftTheme.isDark
    val pillShape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 0.dp, bottomEnd = 0.dp)
    val a11yLabel =
        if (tabs.size == 1) {
            stringResource(Res.string.in_app_browser_floating_pill_single_a11y, leadTab.displayName)
        } else {
            stringResource(Res.string.in_app_browser_floating_pill_multi_a11y, tabs.size)
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
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            RaftConnectedAppLogo(
                name = leadTab.displayName,
                logoUrl = leadTab.appLogoUrl,
                size = RaftAvatarSize.Xs,
            )
            if (tabs.size == 1) {
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
                                if (leadTab.isLive) RaftUiPalette.presenceOnline else colors.foregroundHint,
                                CircleShape,
                            ),
                )
            }
        }
        if (tabs.size >= 2) {
            RaftBadge(
                text = tabs.size.toString(),
                appearance = RaftBadgeAppearance.Solid,
                variant = RaftBadgeVariant.Primary,
                modifier = Modifier.testTag(RAFT_FLOATING_TAB_PILL_COUNT_TAG),
            )
        }
    }
}

/**
 * Multi-page switcher bottom sheet shared by the right-edge floating pill (≥2 tabs) and the top-bar
 * `[N]` trigger inside an open Raft App page.
 */
@Composable
internal fun RaftAppBrowserSwitcherSheet(
    tabs: List<RaftAppBrowserTab>,
    activeTabId: Long?,
    onSelectTab: (RaftAppBrowserTab) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    RaftSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.in_app_browser_open_pages_title, tabs.size),
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
            tabs.forEach { tab ->
                RaftAppBrowserSwitcherRow(
                    tab = tab,
                    isActive = tab.tabId == activeTabId,
                    onSelect = { onSelectTab(tab) },
                    onClose = { onCloseTab(tab.tabId) },
                )
            }
        }
    }
}

@Composable
private fun RaftAppBrowserSwitcherRow(
    tab: RaftAppBrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
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
        Spacer(Modifier.width(2.dp))
        RaftCloseButton(
            onClick = onClose,
            label = stringResource(Res.string.in_app_browser_close_page),
            size = RaftCloseButtonSize.Dialog,
            modifier = Modifier.testTag(raftAppBrowserSwitcherCloseTag(tab.tabId)),
        )
    }
}
