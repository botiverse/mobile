package build.raft.app.shell

import androidx.compose.foundation.layout.WindowInsetsSides
import build.raft.designsystem.components.raftConsumeSafeAreaHorizontal
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.focus.LocalRaftFocusInteractionObserverInstalled
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.raftObserveFocusInteractions
import build.raft.app.resources.*
import build.raft.app.settings.RaftScreenshotFeedbackRuntime
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.nativeEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.designsystem.components.RaftLaidAside
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.get
import build.raft.app.origin
import build.raft.app.raftDialogContentPointerBarrier
import build.raft.app.raftNativeDispatchCapture
import build.raft.app.rememberRaftUnifiedDialogHostState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.LocalRaftAppShellNetworkOffline
import build.raft.app.LocalRaftInputFocusOwnerRegistry
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftBackHandler
import build.raft.app.RaftColor
import build.raft.app.RaftInputFocusDismissHost
import build.raft.app.RaftType
import build.raft.app.RaftUnifiedDialogHost
import build.raft.app.RaftUnifiedDialogHostProvider
import build.raft.app.RaftUnifiedDialogHostState
import kotlin.math.abs

internal data class RaftAppShellScope(
    val topSafeArea: Dp,
    val bottomSafeArea: Dp,
    val bottomTabBarContentInset: Dp,
    val bottomTabBarOverlaysContent: Boolean,
    val adaptivePane: RaftAdaptivePaneState = RaftAdaptivePaneState()
) {
    fun topInset(includeTopSafeArea: Boolean): Dp =
        if (includeTopSafeArea) topSafeArea else 0.dp

    fun bottomInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar) bottomTabBarContentInset else bottomSafeArea + RaftAppSpacing.Lg

    fun bottomTabBarHostInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar && !bottomTabBarOverlaysContent) bottomTabBarContentInset - RaftAppSpacing.Lg else 0.dp

    // Global dialogs must keep their action rows clear of native/floating tab
    // bands. On Android/iOS floating renderers the native tab can draw
    // above Compose siblings, so zIndex alone is not a sufficient clearance
    // contract for bottom-pinned dialog actions.
    fun globalDialogBottomInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar) bottomTabBarContentInset else 0.dp

    // Trailing space scrollable content must reserve so its last row clears the
    // tab bar. When the bar overlays content (floating docks), the host pads
    // nothing and content needs the renderer's full clearance; otherwise the
    // host already reserves bottomTabBarContentInset - Lg and content adds Lg.
    fun contentBottomInset(includeBottomTabBar: Boolean): Dp =
        when {
            !includeBottomTabBar -> bottomSafeArea + RaftAppSpacing.Lg
            bottomTabBarOverlaysContent -> bottomTabBarContentInset
            else -> RaftAppSpacing.Lg
        }

    // Full-height embedded child pages (adaptive detail thread) host their own
    // safe-area padding internally, so the slot only needs to lift their
    // bottom edge above a floating tab dock. When the bar overlays content the
    // child must clear the whole native band minus the safe area it already
    // pads; otherwise the host reserves the band itself and the child needs
    // nothing extra.
    fun embeddedChildBottomInset(includeBottomTabBar: Boolean): Dp =
        when {
            !includeBottomTabBar -> 0.dp
            bottomTabBarOverlaysContent -> (bottomTabBarContentInset - bottomSafeArea).coerceAtLeast(0.dp)
            else -> 0.dp
        }
}

@Composable
internal fun rememberRaftAppShellScope(
    adaptivePane: RaftAdaptivePaneState = RaftAdaptivePaneState()
): RaftAppShellScope {
    // A host tab bar laid out along the top edge covers the status bar area too.
    // Under the minimum build gate's notice the band above the page cleared both already.
    val topSafeArea =
        if (LocalRaftTopSafeAreaClearedAbovePage.current) 0.dp else maxOf(raftWindowTopSafeAreaPadding(), raftNativeTabBarEdgeInsets().top)
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val bottomTabBarContentInset = raftAppBottomTabBarContentInset(bottomSafeArea)
    val bottomTabBarOverlaysContent = raftAppBottomTabBarOverlaysContent()
    return remember(topSafeArea, bottomSafeArea, bottomTabBarContentInset, bottomTabBarOverlaysContent, adaptivePane) {
        RaftAppShellScope(
            topSafeArea = topSafeArea,
            bottomSafeArea = bottomSafeArea,
            bottomTabBarContentInset = bottomTabBarContentInset,
            bottomTabBarOverlaysContent = bottomTabBarOverlaysContent,
            adaptivePane = adaptivePane
        )
    }
}

@Composable
internal fun RaftAppShellRootBackHandler(
    onLifecycleLog: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    RaftBackHandler(
        enabled = true,
        label = "app_shell_root",
        blocksBackSwipe = false,
        onLifecycleLog = onLifecycleLog,
        onBack = onBack
    )
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun RaftAppShellSurface(
    shellScope: RaftAppShellScope,
    bottomTabs: List<RaftBottomTabItemPayload>,
    onBottomTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    networkStatus: RaftAppShellNetworkStatus = RaftAppShellNetworkStatus.Online,
    showBottomTabs: Boolean = true,
    interactionLocked: Boolean = false,
    modalHostState: RaftUnifiedDialogHostState? = null,
    leadingNavigation: (@Composable () -> Unit)? = null,
    /** The wide layout's top bar, above [leadingNavigation] and the content (web AppTopBar). */
    topNavigation: (@Composable () -> Unit)? = null,
    /** Optional signed-in notice banner (under [topNavigation] on wide layouts; via body top banner on compact). */
    topNoticeBanner: (@Composable () -> Unit)? = null,
    /** Drawn over the whole shell, top bar and rail included (the top bar's server panel). */
    shellOverlay: (@Composable () -> Unit)? = null,
    /** D1a: draws the bar's current-server tab (see [RaftBottomTabBar]). */
    bottomServerTab: (@Composable (Modifier) -> Unit)? = null,
    /** Android `tab_bar_places_v0`: long-pressing a place tab opens the server popover in Edit mode. */
    onBottomTabLongPress: ((String) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val ownedModalHostState = rememberRaftUnifiedDialogHostState()
    // Jasper 2026-10-20: the bottom bar steps aside while the keyboard is open (it would ride
    // on top of the keyboard or cover the focused field otherwise).
    val imeVisible = raftImeVisible()
    val bottomTabsShown = raftAppShellBottomTabsShown(showBottomTabs, imeVisible)
    // #338 (Kirby 11-24): the wide shell's top bar steps aside the same way, back at once when the keyboard closes.
    val wideKeyboardRoom = rememberRaftWideKeyboardRoom(shellScope.topSafeArea)
    val wideKeyboardUp by remember(wideKeyboardRoom) { derivedStateOf { wideKeyboardRoom.value != null } }
    val topBarShown = raftAppShellTopBarShown(topNavigation != null, wideKeyboardUp)
    // #338 step 3 (Kirby 640ad7a2, "通栏全宽"): only the composer fits above the keyboard and it is what is typed in,
    // so the rail and the list column step aside and the composer runs the window's width. Laid aside, not removed:
    // nothing is recomposed or reparented, so the composer keeps its draft, its focus and the keyboard.
    val composerFocus = remember { RaftWideKeyboardComposerFocus() }
    val composerOnlyRoom by
        remember(wideKeyboardRoom, composerFocus) {
            derivedStateOf { composerFocus.focused && raftWideKeyboardComposerOnly(wideKeyboardRoom.value) }
        }
    val wideComposerOnly = topNavigation != null && !topBarShown && composerOnlyRoom
    val effectiveModalHostState = modalHostState ?: ownedModalHostState
    val effectiveTopNoticeBanner: (@Composable () -> Unit)? =
        if (networkStatus == RaftAppShellNetworkStatus.Offline) null else topNoticeBanner
    val bodyTopBanner: (@Composable () -> Unit)? =
        when {
            networkStatus == RaftAppShellNetworkStatus.Offline -> ({ RaftAppShellNetworkBanner() })
            topNavigation == null -> effectiveTopNoticeBanner
            else -> null
        }
    RaftInputFocusDismissHost { focusDismissModifier ->
        val drawerOpenGesture = LocalRaftHomeDrawerOpenGesture.current
        val focusOwnerRegistry = LocalRaftInputFocusOwnerRegistry.current
        val drawerGestureModifier =
            Modifier.raftHomeDrawerOpenGesture(
                admission =
                    drawerOpenGesture.admission.copy(
                        inputFocused = focusOwnerRegistry?.hasActiveOwner == true
                    ),
                onOpen = drawerOpenGesture.onOpen
            )
        Box(
            modifier =
                modifier
                    .raftObserveFocusInteractions()
                    .then(focusDismissModifier)
                    .fillMaxSize()
                    .background(RaftColor.SurfaceWhite)
        ) {
            CompositionLocalProvider(LocalRaftFocusInteractionObserverInstalled provides true) {
            RaftUnifiedDialogHostProvider(effectiveModalHostState) {
                Column(
                    modifier =
                        Modifier
                            .then(drawerGestureModifier)
                            .zIndex(RaftAppContentHostZIndex)
                            .fillMaxSize()
                            .padding(bottom = shellScope.bottomTabBarHostInset(bottomTabsShown))
                            .then(
                                if (interactionLocked) {
                                    Modifier.clearAndSetSemantics { invisibleToUser() }
                                } else {
                                    Modifier
                                }
                            )
                ) {
                    if (topNavigation != null && topBarShown) {
                        RaftFocusRegionHost(region = RaftFocusRegion.TopBar, modifier = Modifier.fillMaxWidth()) {
                            topNavigation()
                        }
                    } else if (topNavigation != null) {
                        // The top bar is aside for the keyboard: its status bar strip stays, as the pages below
                        // still count on the shell to clear the status bar.
                        RaftDesignSystemTheme {
                            RaftStatusBarBackdropClaim(RaftTheme.colors.layerFrame, enabled = shellScope.topSafeArea > 0.dp)
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(shellScope.topSafeArea)
                                        .background(RaftTheme.colors.layerFrame)
                            )
                        }
                    }
                    if (topNavigation != null && topBarShown && effectiveTopNoticeBanner != null) {
                        effectiveTopNoticeBanner()
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(RaftAppStroke.Thin)
                                    .background(RaftColor.BorderSeam.compositeOver(RaftColor.Canvas))
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (leadingNavigation != null) {
                                // #338: aside while only the composer fits above the keyboard (wideComposerOnly).
                                RaftLaidAside(aside = wideComposerOnly, modifier = Modifier.fillMaxHeight()) {
                                    RaftFocusRegionHost(region = RaftFocusRegion.Navigation, modifier = Modifier.fillMaxHeight()) {
                                        leadingNavigation()
                                    }
                                }
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        // The rail beside it takes the start safe area (its frame
                                        // bleeds to the edge); the content starts past the rail.
                                        .then(
                                            if (leadingNavigation != null && !wideComposerOnly) {
                                                Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.Start)
                                            } else {
                                                Modifier
                                            }
                                        )
                            ) {
                                CompositionLocalProvider(
                                    LocalRaftAppShellBodyTopBanner provides bodyTopBanner,
                                    LocalRaftAppShellNetworkOffline provides (networkStatus == RaftAppShellNetworkStatus.Offline),
                                    // The top bar above cleared the status bar: pages here (list
                                    // and detail panes) must not pad it again (xxchan 11-02).
                                    LocalRaftTopSafeAreaConsumed provides (topNavigation != null),
                                    LocalRaftWideKeyboardRoom provides (if (topNavigation != null && !topBarShown) wideKeyboardRoom else null),
                                    LocalRaftWideKeyboardComposerFocus provides (if (topNavigation != null) composerFocus else null),
                                    LocalRaftWideKeyboardComposerOnly provides wideComposerOnly,
                                ) {
                                    content()
                                }
                            }
                        }
                    }
                }
                if (shellOverlay != null) {
                    Box(modifier = Modifier.fillMaxSize().zIndex(RaftAppContentHostZIndex)) {
                        CompositionLocalProvider(
                            LocalRaftAppShellNetworkOffline provides (networkStatus == RaftAppShellNetworkStatus.Offline),
                        ) { shellOverlay() }
                    }
                }
                RaftAppShellScreenshotBottomBarReporter(
                    bottomTabsShown = bottomTabsShown,
                    bottomSafeArea = shellScope.bottomSafeArea,
                )
                if (showBottomTabs) {
                    // raft-design #620: gone at once when the keyboard opens, back with a short
                    // slide/fade up when it closes. A page that starts showing the bar (keyboard
                    // closed) gets it at once: AnimatedVisibility starts at its first value.
                    AnimatedVisibility(
                        visible = !imeVisible,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .zIndex(RaftAppBottomTabBarHostZIndex),
                        enter = RaftAppShellBottomBarReturn,
                        exit = RaftAppShellBottomBarLeave
                    ) {
                        RaftFocusRegionHost(region = RaftFocusRegion.Navigation, modifier = Modifier.fillMaxWidth()) {
                            RaftAppBottomTabBarHost(
                                items = bottomTabs,
                                onSelect = onBottomTabSelected,
                                modifier = Modifier.fillMaxWidth(),
                                bottomSafeArea = shellScope.bottomSafeArea,
                                serverTab = bottomServerTab,
                                onLongPress = onBottomTabLongPress,
                            )
                        }
                    }
                }
                if (interactionLocked) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .zIndex(RaftAppShellInteractionBlockerZIndex)
                                .raftNativeDispatchCapture()
                                .raftDialogContentPointerBarrier()
                                .clearAndSetSemantics { }
                    )
                }
                RaftUnifiedDialogHost(
                    state = effectiveModalHostState,
                    modifier = Modifier.fillMaxSize(),
                    bottomInset = shellScope.globalDialogBottomInset(bottomTabsShown)
                )
            }
            }
        }
    }
}

/** The shell's bottom bar is drawn only when the page wants it and no keyboard is open. */
internal fun raftAppShellBottomTabsShown(showBottomTabs: Boolean, imeVisible: Boolean): Boolean =
    showBottomTabs && !imeVisible

/**
 * The wide shell's top bar is drawn unless a keyboard is open ([raftWideKeyboardRoom]; #338, Kirby 11-24: it steps
 * aside like the phone's bottom bar, and comes back at once, without animation, when the keyboard closes).
 */
internal fun raftAppShellTopBarShown(hasTopBar: Boolean, keyboardUp: Boolean): Boolean =
    hasTopBar && !keyboardUp

/** How long the bottom bar takes to come back when the keyboard closes (raft-design #620). */
internal const val RaftAppShellBottomBarReturnMillis = 200

/**
 * The bottom bar coming back after the keyboard closes: the platform's short fade and slide up
 * (a quarter of its height), [RaftAppShellBottomBarReturnMillis]. Compose scales it by the
 * system animation scale, so Android's "Remove animations" makes it instant.
 */
internal val RaftAppShellBottomBarReturn: EnterTransition =
    fadeIn(tween(RaftAppShellBottomBarReturnMillis, easing = LinearOutSlowInEasing)) +
        slideInVertically(tween(RaftAppShellBottomBarReturnMillis, easing = LinearOutSlowInEasing)) { height ->
            height / 4
        }

/** The bar leaves at once when the keyboard opens: the keyboard is already covering it. */
internal val RaftAppShellBottomBarLeave: ExitTransition = ExitTransition.None

/**
 * True while a software keyboard covers part of the window: `WindowInsets.ime`, or the host's own
 * report when no platform animation drives it (see `RaftHostImeInsets`).
 */
@Composable
internal fun raftImeVisible(): Boolean = raftImeBottomPx(LocalDensity.current) > 0

// task #23/#45: the left navigation rail owns tab selection whenever the user's
// desktop-mode setting is on and the window is in the dual-pane (flat, >=768dp)
// layout. The setting default is ON; compact layouts always keep the bottom bar.
internal fun raftAdaptiveNavRailActive(
    desktopModeEnabled: Boolean,
    paneState: RaftAdaptivePaneState
): Boolean =
    desktopModeEnabled && paneState.layout == RaftAdaptivePaneLayout.DualPane

private const val RaftAppShellInteractionBlockerZIndex = RaftGlobalModalHostZIndex - 0.25f

internal data class HomeServerDrawerOpenGestureAdmission(
    val homeSurfaceActive: Boolean = false,
    val pointerOwnerActive: Boolean = false,
    val modalPresented: Boolean = false,
    val inputFocused: Boolean = false,
    val descendantConsumed: Boolean = false,
    val multiplePointers: Boolean = false
) {
    val allowed: Boolean
        get() =
            homeSurfaceActive &&
                pointerOwnerActive &&
                !modalPresented &&
                !inputFocused &&
                !descendantConsumed &&
                !multiplePointers
}

private const val HomeServerDrawerOpenThresholdDp = 72f
private const val HomeServerDrawerOpenHorizontalDominanceRatio = 1.25f

internal fun shouldOpenHomeServerDrawerFromContentGesture(
    admission: HomeServerDrawerOpenGestureAdmission,
    horizontalDragDp: Float,
    verticalDragDp: Float,
    thresholdDp: Float = HomeServerDrawerOpenThresholdDp
): Boolean =
    admission.allowed &&
        horizontalDragDp >= thresholdDp &&
        horizontalDragDp >= abs(verticalDragDp) * HomeServerDrawerOpenHorizontalDominanceRatio

internal data class RaftHomeDrawerOpenGesture(
    val admission: HomeServerDrawerOpenGestureAdmission = HomeServerDrawerOpenGestureAdmission(),
    val onOpen: () -> Unit = {}
)

internal val LocalRaftHomeDrawerOpenGesture =
    staticCompositionLocalOf { RaftHomeDrawerOpenGesture() }

@Composable
private fun Modifier.raftHomeDrawerOpenGesture(
    admission: HomeServerDrawerOpenGestureAdmission,
    onOpen: () -> Unit
): Modifier {
    if (!admission.allowed) return this
    val currentAdmission by rememberUpdatedState(admission)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val density = LocalDensity.current
    return pointerInput(Unit) {
        awaitEachGesture {
            var start: Offset? = null
            var latest = Offset.Zero
            var descendantConsumed = false
            var multiplePointers = false
            var terminal = false
            while (!terminal) {
                val initial = awaitPointerEvent(PointerEventPass.Initial)
                val activePointerCount = initial.changes.count { it.pressed || it.previousPressed }
                if (activePointerCount > 1) multiplePointers = true
                val change = initial.changes.firstOrNull()
                if (change != null) {
                    if (start == null && change.pressed && !change.previousPressed) {
                        start = change.position
                    }
                    latest = change.position
                    terminal = start != null && change.previousPressed && !change.pressed
                }
                val main = awaitPointerEvent(PointerEventPass.Main)
                descendantConsumed =
                    descendantConsumed ||
                        main.changes.any { it.isConsumed && it.positionChangedIgnoreConsumed() }
                if (main.nativeEvent == "cancel") return@awaitEachGesture
            }
            val origin = start ?: return@awaitEachGesture
            val admissionAtTerminal =
                currentAdmission.copy(
                    descendantConsumed = descendantConsumed,
                    multiplePointers = multiplePointers
                )
            if (
                shouldOpenHomeServerDrawerFromContentGesture(
                    admission = admissionAtTerminal,
                    horizontalDragDp = (latest.x - origin.x) / density.density,
                    verticalDragDp = (latest.y - origin.y) / density.density
                )
            ) {
                currentOnOpen()
            }
        }
    }
}

internal enum class RaftAppShellNetworkStatus {
    Online,
    Offline
}

/**
 * App-shell alerts belong to the page body, immediately below page chrome.
 *
 * The shell provides the current alert through this local, while each page-header
 * pattern consumes it after its own safe-area/header divider. Keeping the slot out
 * of [RaftAppShellSurface]'s root column prevents status bars from covering alerts
 * and avoids placing body state above navigation chrome.
 */
internal val LocalRaftAppShellBodyTopBanner =
    staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
internal fun RaftAppShellBodyTopBanner() {
    LocalRaftAppShellBodyTopBanner.current?.invoke()
}

@Composable
private fun RaftAppShellNetworkBanner() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(RaftColor.AccentRed)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftAppSpacing.Md),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(Res.string.app_shell_offline_banner),
            style = RaftType.ButtonBold,
            color = RaftColor.StickerInk,
            maxLines = 1
        )
    }
}

/**
 * Leaf reporter isolating [raftAppActiveBottomTabBarBandHeight]'s `StateFlow` collections from
 * [RaftAppShellSurface] so native tab-bar state ticks (badges, selection) never recompose the
 * shell body, while scoping reported heights to this root's `RaftCmpHost` (`pager.host`) for
 * multi-window safety (no-op in standalone unit tests without a `RaftCmpRoot` host).
 */
@Composable
private fun RaftAppShellScreenshotBottomBarReporter(
    bottomTabsShown: Boolean,
    bottomSafeArea: Dp,
) {
    val host = (LocalActivity.current.getPager() as? Pager)?.host ?: return
    val activeBottomBarBandDp =
        if (bottomTabsShown) {
            raftAppActiveBottomTabBarBandHeight(bottomSafeArea)
        } else {
            0.dp
        }
    val token = remember { Any() }
    DisposableEffect(host, token) {
        onDispose {
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(host, token, null)
        }
    }
    SideEffect {
        RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(
            hostKey = host,
            token = token,
            heightDp = activeBottomBarBandDp.value,
        )
    }
}
