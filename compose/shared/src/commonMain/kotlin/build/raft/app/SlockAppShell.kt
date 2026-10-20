package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.input.pointer.PointerEventPass
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChangedIgnoreConsumed
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.ExperimentalComposeUiApi
import com.tencent.kuikly.compose.ui.semantics.clearAndSetSemantics
import com.tencent.kuikly.compose.ui.semantics.invisibleToUser
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.zIndex
import kotlin.math.abs

internal data class SlockAppShellScope(
    val topSafeArea: Dp,
    val bottomSafeArea: Dp,
    val bottomTabBarContentInset: Dp,
    val bottomTabBarOverlaysContent: Boolean,
    val adaptivePane: SlockAdaptivePaneState = SlockAdaptivePaneState()
) {
    fun topInset(includeTopSafeArea: Boolean): Dp =
        if (includeTopSafeArea) topSafeArea else 0.dp

    fun bottomInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar) bottomTabBarContentInset else bottomSafeArea + SlockSpacing.Lg

    fun bottomTabBarHostInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar && !bottomTabBarOverlaysContent) bottomTabBarContentInset - SlockSpacing.Lg else 0.dp

    // Global dialogs must keep their action rows clear of native/floating tab
    // bands. On Android/iOS/OHOS floating renderers the native tab can draw
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
            !includeBottomTabBar -> bottomSafeArea + SlockSpacing.Lg
            bottomTabBarOverlaysContent -> bottomTabBarContentInset
            else -> SlockSpacing.Lg
        }
}

@Composable
internal fun rememberSlockAppShellScope(
    adaptivePane: SlockAdaptivePaneState = SlockAdaptivePaneState()
): SlockAppShellScope {
    val topSafeArea = slockTopSafeAreaPadding()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val bottomTabBarContentInset = slockAppBottomTabBarContentInset(bottomSafeArea)
    val bottomTabBarOverlaysContent = slockAppBottomTabBarOverlaysContent()
    return remember(topSafeArea, bottomSafeArea, bottomTabBarContentInset, bottomTabBarOverlaysContent, adaptivePane) {
        SlockAppShellScope(
            topSafeArea = topSafeArea,
            bottomSafeArea = bottomSafeArea,
            bottomTabBarContentInset = bottomTabBarContentInset,
            bottomTabBarOverlaysContent = bottomTabBarOverlaysContent,
            adaptivePane = adaptivePane
        )
    }
}

@Composable
internal fun SlockAppShellRootBackHandler(
    onLifecycleLog: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    SlockBackHandler(
        enabled = true,
        label = "app_shell_root",
        onLifecycleLog = onLifecycleLog,
        onBack = onBack
    )
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun SlockAppShellSurface(
    shellScope: SlockAppShellScope,
    bottomTabs: List<SlockBottomTabItemPayload>,
    onBottomTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    networkStatus: SlockAppShellNetworkStatus = SlockAppShellNetworkStatus.Online,
    showBottomTabs: Boolean = true,
    interactionLocked: Boolean = false,
    modalHostState: SlockUnifiedDialogHostState? = null,
    content: @Composable () -> Unit
) {
    val ownedModalHostState = rememberSlockUnifiedDialogHostState()
    val effectiveModalHostState = modalHostState ?: ownedModalHostState
    val bodyTopBanner: (@Composable () -> Unit)? =
        if (networkStatus == SlockAppShellNetworkStatus.Offline) {
            { SlockAppShellNetworkBanner() }
        } else {
            null
        }
    SlockInputFocusDismissHost { focusDismissModifier ->
        val drawerOpenGesture = LocalSlockHomeDrawerOpenGesture.current
        val focusOwnerRegistry = LocalSlockInputFocusOwnerRegistry.current
        val drawerGestureModifier =
            Modifier.slockHomeDrawerOpenGesture(
                admission =
                    drawerOpenGesture.admission.copy(
                        inputFocused = focusOwnerRegistry?.hasActiveOwner == true
                    ),
                onOpen = drawerOpenGesture.onOpen
            )
        Box(
            modifier =
                modifier
                    .then(focusDismissModifier)
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite)
        ) {
            SlockUnifiedDialogHostProvider(effectiveModalHostState) {
                Column(
                    modifier =
                        Modifier
                            .then(drawerGestureModifier)
                            .zIndex(SlockAppContentHostZIndex)
                            .fillMaxSize()
                            .padding(bottom = shellScope.bottomTabBarHostInset(showBottomTabs))
                            .then(
                                if (interactionLocked) {
                                    Modifier.clearAndSetSemantics { invisibleToUser() }
                                } else {
                                    Modifier
                                }
                            )
                ) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        CompositionLocalProvider(LocalSlockAppShellBodyTopBanner provides bodyTopBanner) {
                            content()
                        }
                    }
                }
                if (showBottomTabs) {
                    SlockAppBottomTabBarHost(
                        items = bottomTabs,
                        onSelect = onBottomTabSelected,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        bottomSafeArea = shellScope.bottomSafeArea
                    )
                }
                if (interactionLocked) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .zIndex(SlockAppShellInteractionBlockerZIndex)
                                .slockNativeDispatchCapture()
                                .slockDialogContentPointerBarrier()
                                .clearAndSetSemantics { }
                    )
                }
                SlockUnifiedDialogHost(
                    state = effectiveModalHostState,
                    modifier = Modifier.fillMaxSize(),
                    bottomInset = shellScope.globalDialogBottomInset(showBottomTabs)
                )
            }
        }
    }
}

private const val SlockAppShellInteractionBlockerZIndex = SlockGlobalModalHostZIndex - 0.25f

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

internal data class SlockHomeDrawerOpenGesture(
    val admission: HomeServerDrawerOpenGestureAdmission = HomeServerDrawerOpenGestureAdmission(),
    val onOpen: () -> Unit = {}
)

internal val LocalSlockHomeDrawerOpenGesture =
    staticCompositionLocalOf { SlockHomeDrawerOpenGesture() }

@Composable
private fun Modifier.slockHomeDrawerOpenGesture(
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

internal enum class SlockAppShellNetworkStatus {
    Online,
    Offline
}

/**
 * App-shell alerts belong to the page body, immediately below page chrome.
 *
 * The shell provides the current alert through this local, while each page-header
 * pattern consumes it after its own safe-area/header divider. Keeping the slot out
 * of [SlockAppShellSurface]'s root column prevents status bars from covering alerts
 * and avoids placing body state above navigation chrome.
 */
internal val LocalSlockAppShellBodyTopBanner =
    staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
internal fun SlockAppShellBodyTopBanner() {
    LocalSlockAppShellBodyTopBanner.current?.invoke()
}

@Composable
private fun SlockAppShellNetworkBanner() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(SlockColor.AccentRed)
                .padding(horizontal = SlockSpacing.Md),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(MR.strings.app_shell_offline_banner),
            style = SlockType.ButtonBold,
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}
