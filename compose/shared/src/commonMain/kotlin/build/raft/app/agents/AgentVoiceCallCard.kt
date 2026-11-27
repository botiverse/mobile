package build.raft.app.agents

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.RaftAppAvatarSticker
import build.raft.app.shell.RaftAppBottomTabBarHostZIndex
import build.raft.app.RaftAvatarKind
import build.raft.app.RaftContentLucideIcon
import build.raft.app.shell.RaftHorizontalSafeArea
import build.raft.app.RaftLucideIconName
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.data.AGENT_VOICE_CALL_MAX_MINUTES
import build.raft.app.data.AgentVoiceCallEndReason
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallProblem
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.data.SharedAgentVoiceCallStore
import build.raft.app.data.formatAgentVoiceCallClock
import build.raft.app.data.raftNowEpochMillis
import build.raft.app.model.MemberKind
import build.raft.app.shell.raftBottomSafeAreaPadding
import build.raft.app.shell.raftHorizontalSafeArea
import build.raft.app.raftMemberAvatarCacheSubject
import build.raft.app.shell.raftWindowTopSafeAreaPadding
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconToggleButton
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Floating Agent Voice call card overlay (`#wg-mobile-sync:#95`), mirroring web
// `AgentVoiceCallWindow.tsx`, `AgentVoiceCallCard.tsx`, and `AgentVoiceCallPanel.tsx`.
// Mounted once at the app shell root (and on standalone pushed pages when outside the shell)
// so an active call survives tab switches, navigation, and server switches without blocking
// interaction with the conversation behind it.

/** True inside an ancestor host that already mounts [AgentVoiceCallOverlayHost]. */
internal val LocalAgentVoiceCallHostMounted = compositionLocalOf { false }

/** Above content and bottom tab bar, below modals/popovers/banners/toasts (mirrors web `z-app`). */
internal const val AgentVoiceCallOverlayZIndex: Float = RaftAppBottomTabBarHostZIndex + 0.25f

private val AgentVoiceCallCardMaxWidth = 360.dp
private val AgentVoiceCallCardHorizontalInset = 12.dp
private val AgentVoiceCallCardTopGap = 12.dp
private val AgentVoiceCallCardBottomGap = 16.dp
private val AgentVoiceCallDragMaxOverscroll = 20.dp
private val AgentVoiceCallDragHandleWidth = 32.dp
private val AgentVoiceCallDragHandleHeight = 4.dp
private val AgentVoiceCallDragHandleRadius = 2.dp
private val AgentVoiceCallDragHandleTopInset = 6.dp
private const val AgentVoiceCallDragHandleAlpha = 0.35f
private val AgentVoiceCallCardRestElevationLight = 4.dp
private val AgentVoiceCallCardRestElevationDark = 8.dp
private val AgentVoiceCallCardDragElevationLight = 10.dp
private val AgentVoiceCallCardDragElevationDark = 14.dp
private const val AgentVoiceCallOverscrollSpringDamping = 0.78f
private val AgentVoiceCallSpeakingRingStroke = 2.dp
private val AgentVoiceCallSpeakingRingPadding = 2.dp

/** Physical top-left pixel coordinate of the floating voice call card inside its overlay host. */
internal data class AgentVoiceCallFloatingOffset(
    val xPx: Float,
    val yPx: Float,
)

/**
 * Container, card, safe-area, and keyboard geometry (in physical pixels) used to compute the
 * default placement and clamp dragged coordinates of the floating Agent Voice call card.
 */
internal data class AgentVoiceCallFloatingBounds(
    val hostWidthPx: Float,
    val hostHeightPx: Float,
    val cardWidthPx: Float,
    val cardHeightPx: Float,
    val leftSafeAreaPx: Float = 0f,
    val rightSafeAreaPx: Float = 0f,
    val topSafeAreaPx: Float = 0f,
    val bottomSafeAreaPx: Float = 0f,
    val imeBottomPx: Float = 0f,
    val horizontalInsetPx: Float = 0f,
    val topGapPx: Float = 0f,
    val bottomGapPx: Float = 0f,
    val wide: Boolean = false,
    val isLtr: Boolean = true,
)

/** Single-axis result of applying an incremental pointer drag delta. */
internal data class AgentVoiceCallDragAxisStep(
    val positionPx: Float,
    val rawOverscrollPx: Float,
)

/** Saved floating-card placement (in dp, top-left inside the overlay host) for an active call. */
internal data class AgentVoiceCallSavedPosition(
    val sessionKey: String,
    val xDp: Float,
    val yDp: Float,
)

/**
 * Session-scoped holder for the user's dragged floating-card position so repositioning the card
 * on one screen (for example a pushed phone DM) carries across navigation back to the app shell
 * or another conversation while the same call stays live. Backed by [MutableStateFlow] rather
 * than top-level Compose snapshot state.
 */
internal class AgentVoiceCallFloatingPositionHolder {
    private val mutablePosition = MutableStateFlow<AgentVoiceCallSavedPosition?>(null)

    val position: StateFlow<AgentVoiceCallSavedPosition?> = mutablePosition.asStateFlow()

    fun currentOffsetPx(
        sessionKey: String,
        density: Density,
    ): AgentVoiceCallFloatingOffset? {
        val saved = mutablePosition.value ?: return null
        if (saved.sessionKey != sessionKey) return null
        if (!saved.xDp.isFinite() || !saved.yDp.isFinite() || density.density <= 0f) return null
        return AgentVoiceCallFloatingOffset(
            xPx = saved.xDp * density.density,
            yPx = saved.yDp * density.density,
        )
    }

    fun save(
        sessionKey: String,
        xDp: Float,
        yDp: Float,
    ) {
        if (sessionKey.isBlank() || !xDp.isFinite() || !yDp.isFinite()) return
        mutablePosition.value = AgentVoiceCallSavedPosition(sessionKey = sessionKey, xDp = xDp, yDp = yDp)
    }

    fun clear() {
        if (mutablePosition.value != null) {
            mutablePosition.value = null
        }
    }
}

internal val DefaultAgentVoiceCallFloatingPositionHolder = AgentVoiceCallFloatingPositionHolder()

/** Valid horizontal range for the floating card's physical left edge in [bounds]. */
internal fun agentVoiceCallHorizontalRange(
    bounds: AgentVoiceCallFloatingBounds,
): ClosedFloatingPointRange<Float> {
    val hostW = bounds.hostWidthPx.coerceAtLeast(0f)
    val cardW = bounds.cardWidthPx.coerceAtLeast(0f)
    val minX = bounds.leftSafeAreaPx.coerceAtLeast(0f) + bounds.horizontalInsetPx.coerceAtLeast(0f)
    val maxX = hostW - cardW - bounds.rightSafeAreaPx.coerceAtLeast(0f) - bounds.horizontalInsetPx.coerceAtLeast(0f)
    if (maxX < minX) {
        val centered = ((hostW - cardW) / 2f).coerceAtLeast(0f)
        return centered..centered
    }
    return minX..maxX
}

/** Valid vertical range for the floating card's physical top edge in [bounds]. */
internal fun agentVoiceCallVerticalRange(
    bounds: AgentVoiceCallFloatingBounds,
): ClosedFloatingPointRange<Float> {
    val hostH = bounds.hostHeightPx.coerceAtLeast(0f)
    val cardH = bounds.cardHeightPx.coerceAtLeast(0f)
    val minY = bounds.topSafeAreaPx.coerceAtLeast(0f) + bounds.topGapPx.coerceAtLeast(0f)
    val effectiveBottomInset = maxOf(bounds.bottomSafeAreaPx.coerceAtLeast(0f), bounds.imeBottomPx.coerceAtLeast(0f))
    val maxY = hostH - cardH - effectiveBottomInset - bounds.bottomGapPx.coerceAtLeast(0f)
    if (maxY < minY) {
        // When a keyboard or split-screen window compresses vertical height below the card height,
        // keep the card right below the top safe area unless even that would push it off the host.
        val pinned = minY.coerceAtMost((hostH - cardH).coerceAtLeast(0f))
        return pinned..pinned
    }
    return minY..maxY
}

/**
 * Untouched initial placement matching the static layout: bottom-center on compact viewports,
 * bottom-end (right in LTR, left in RTL) on wide viewports, resting above safe-area + IME insets.
 */
internal fun defaultVoiceCallFloatingOffset(
    bounds: AgentVoiceCallFloatingBounds,
): AgentVoiceCallFloatingOffset {
    val xRange = agentVoiceCallHorizontalRange(bounds)
    val yRange = agentVoiceCallVerticalRange(bounds)
    val xPx =
        when {
            bounds.wide && bounds.isLtr -> xRange.endInclusive
            bounds.wide -> xRange.start
            else -> (xRange.start + xRange.endInclusive) / 2f
        }
    return AgentVoiceCallFloatingOffset(xPx = xPx, yPx = yRange.endInclusive)
}

/**
 * Clamps [offset] strictly inside the visible safe-area viewport defined by [bounds].
 * Non-finite coordinates fall back to [defaultVoiceCallFloatingOffset].
 */
internal fun clampVoiceCallFloatingOffset(
    offset: AgentVoiceCallFloatingOffset,
    bounds: AgentVoiceCallFloatingBounds,
): AgentVoiceCallFloatingOffset {
    val xRange = agentVoiceCallHorizontalRange(bounds)
    val yRange = agentVoiceCallVerticalRange(bounds)
    val fallback = defaultVoiceCallFloatingOffset(bounds)
    val safeX = if (offset.xPx.isFinite()) offset.xPx else fallback.xPx
    val safeY = if (offset.yPx.isFinite()) offset.yPx else fallback.yPx
    return AgentVoiceCallFloatingOffset(
        xPx = safeX.coerceIn(xRange.start, xRange.endInclusive),
        yPx = safeY.coerceIn(yRange.start, yRange.endInclusive),
    )
}

/**
 * Soft rubber-band displacement for dragging past a safe-area edge: strictly monotonic, bounded
 * within `(-maxOverscrollPx, maxOverscrollPx)`, and zero when [excessPx] is zero.
 */
internal fun dampVoiceCallFloatingOverscrollPx(
    excessPx: Float,
    maxOverscrollPx: Float,
): Float {
    if (!excessPx.isFinite() || !maxOverscrollPx.isFinite() || maxOverscrollPx <= 0f || excessPx == 0f) {
        return 0f
    }
    val absExcess = abs(excessPx)
    val damped = maxOverscrollPx * (absExcess / (absExcess + maxOverscrollPx * 2.5f))
    return if (excessPx > 0f) damped else -damped
}

/**
 * Applies one pointer drag delta along a single axis. Axes with meaningful travel
 * (`maxPx - minPx >= maxOverscrollPx`) follow the finger 1:1 inside `[minPx, maxPx]` and gently
 * rubber-band past the edges; narrow locked axes (such as full-width portrait phone X) stay
 * strictly clamped with zero sideways wobble.
 */
internal fun stepVoiceCallFloatingDragAxis(
    positionPx: Float,
    rawOverscrollPx: Float,
    deltaPx: Float,
    minPx: Float,
    maxPx: Float,
    maxOverscrollPx: Float,
): AgentVoiceCallDragAxisStep {
    val clampedMin = minPx.coerceAtMost(maxPx)
    val clampedMax = maxPx.coerceAtLeast(minPx)
    if (!deltaPx.isFinite()) {
        return AgentVoiceCallDragAxisStep(positionPx = positionPx.coerceIn(clampedMin, clampedMax), rawOverscrollPx = 0f)
    }
    val span = (clampedMax - clampedMin).coerceAtLeast(0f)
    val allowOverscroll = maxOverscrollPx.isFinite() && maxOverscrollPx > 0f && span >= maxOverscrollPx
    val clampedPos = if (positionPx.isFinite()) positionPx.coerceIn(clampedMin, clampedMax) else clampedMin
    val safeOverscroll = if (allowOverscroll && rawOverscrollPx.isFinite()) rawOverscrollPx else 0f
    val nextVirtual = clampedPos + safeOverscroll + deltaPx
    return when {
        nextVirtual < clampedMin -> {
            val excess =
                if (allowOverscroll) {
                    (nextVirtual - clampedMin).coerceAtLeast(-maxOverscrollPx * 3f)
                } else {
                    0f
                }
            AgentVoiceCallDragAxisStep(positionPx = clampedMin, rawOverscrollPx = excess)
        }
        nextVirtual > clampedMax -> {
            val excess =
                if (allowOverscroll) {
                    (nextVirtual - clampedMax).coerceAtMost(maxOverscrollPx * 3f)
                } else {
                    0f
                }
            AgentVoiceCallDragAxisStep(positionPx = clampedMax, rawOverscrollPx = excess)
        }
        else -> {
            AgentVoiceCallDragAxisStep(positionPx = nextVirtual, rawOverscrollPx = 0f)
        }
    }
}

private class AgentVoiceCallMeasuredSizeBox(
    var value: IntSize = IntSize.Zero,
)

private data class AgentVoiceCallHostMetrics(
    val hostWidthPx: Float,
    val hostHeightPx: Float,
    val leftSafeAreaPx: Float,
    val rightSafeAreaPx: Float,
    val topSafeAreaPx: Float,
    val bottomSafeAreaPx: Float,
    val horizontalInsetPx: Float,
    val topGapPx: Float,
    val bottomGapPx: Float,
    val maxOverscrollPx: Float,
    val wide: Boolean,
    val isLtr: Boolean,
    val density: Density,
) {
    fun toBounds(
        cardWidthPx: Float,
        cardHeightPx: Float,
        imeBottomPx: Float,
    ): AgentVoiceCallFloatingBounds =
        AgentVoiceCallFloatingBounds(
            hostWidthPx = hostWidthPx,
            hostHeightPx = hostHeightPx,
            cardWidthPx = cardWidthPx,
            cardHeightPx = cardHeightPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
            topSafeAreaPx = topSafeAreaPx,
            bottomSafeAreaPx = bottomSafeAreaPx,
            imeBottomPx = imeBottomPx,
            horizontalInsetPx = horizontalInsetPx,
            topGapPx = topGapPx,
            bottomGapPx = bottomGapPx,
            wide = wide,
            isLtr = isLtr,
        )
}

/**
 * Observes [store] and floats a draggable [AgentVoiceCallCard] inside visible safe-area bounds
 * while a call is active, ended, or failed. Emits nothing while idle.
 */
@Composable
internal fun AgentVoiceCallOverlayHost(
    store: SharedAgentVoiceCallStore?,
    bottomClearance: Dp = raftBottomSafeAreaPadding(),
    modifier: Modifier = Modifier,
    topSafeArea: Dp? = null,
    horizontalSafeArea: RaftHorizontalSafeArea? = null,
    positionHolder: AgentVoiceCallFloatingPositionHolder = DefaultAgentVoiceCallFloatingPositionHolder,
) {
    if (store == null) return
    val call by store.call.collectAsState()
    val currentCall = call
    LaunchedEffect(currentCall == null, positionHolder) {
        if (currentCall == null) {
            positionHolder.clear()
        }
    }
    if (currentCall == null) return
    val runningClock = currentCall.phase == AgentVoiceCallPhase.Live || currentCall.phase == AgentVoiceCallPhase.Reconnecting
    var nowEpochMs by remember(currentCall.callId, currentCall.connectedAtEpochMs) {
        mutableStateOf(raftNowEpochMillis())
    }
    LaunchedEffect(runningClock, currentCall.callId, currentCall.connectedAtEpochMs) {
        if (!runningClock) return@LaunchedEffect
        nowEpochMs = raftNowEpochMillis()
        while (true) {
            delay(1_000L)
            nowEpochMs = raftNowEpochMillis()
        }
    }
    AgentVoiceCallDraggableOverlay(
        call = currentCall,
        nowEpochMs = nowEpochMs,
        onToggleMute = store::toggleMute,
        onToggleSpeaker = store::toggleSpeaker,
        onHangUp = store::hangUp,
        onClose = store::dismissEnded,
        onRetry = store::retry,
        bottomClearance = bottomClearance,
        modifier = modifier,
        topSafeArea = topSafeArea,
        horizontalSafeArea = horizontalSafeArea,
        positionHolder = positionHolder,
    )
}

/**
 * Draggable overlay surface hosting [AgentVoiceCallCard] with safe-area + IME clamping, smooth
 * rubber-band edge feedback, and session-scoped position persistence.
 */
@Composable
internal fun AgentVoiceCallDraggableOverlay(
    call: AgentVoiceCallState,
    nowEpochMs: Long,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    bottomClearance: Dp = raftBottomSafeAreaPadding(),
    modifier: Modifier = Modifier,
    topSafeArea: Dp? = null,
    horizontalSafeArea: RaftHorizontalSafeArea? = null,
    positionHolder: AgentVoiceCallFloatingPositionHolder = DefaultAgentVoiceCallFloatingPositionHolder,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isWide = RaftTheme.scale == RaftScale.Wide
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val resolvedTopSafeArea = (topSafeArea ?: raftWindowTopSafeAreaPadding()).coerceAtLeast(0.dp)
    val resolvedBottomClearance = bottomClearance.coerceAtLeast(0.dp)
    val resolvedHorizontalSafeArea = horizontalSafeArea ?: raftHorizontalSafeArea()
    val imeInsets = WindowInsets.ime
    val hostImeInsets = LocalRaftHostImeInsets.current
    val scope = rememberCoroutineScope()
    val sessionKey = "${call.serverId}:${call.agent.id}"

    val savedPosition by positionHolder.position.collectAsState()
    var localPositionPx by remember(sessionKey) {
        mutableStateOf(positionHolder.currentOffsetPx(sessionKey, density))
    }
    var isDragging by remember(sessionKey) { mutableStateOf(false) }
    val overscrollOffset = remember(sessionKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val measuredCardSize = remember { AgentVoiceCallMeasuredSizeBox() }

    LaunchedEffect(savedPosition, sessionKey, density.density) {
        if (!isDragging) {
            val saved = savedPosition?.takeIf { it.sessionKey == sessionKey }
            localPositionPx =
                saved?.let {
                    if (it.xDp.isFinite() && it.yDp.isFinite() && density.density > 0f) {
                        AgentVoiceCallFloatingOffset(
                            xPx = it.xDp * density.density,
                            yPx = it.yDp * density.density,
                        )
                    } else {
                        null
                    }
                }
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().zIndex(AgentVoiceCallOverlayZIndex),
    ) {
        val maxAvailableCardWidth =
            (maxWidth - resolvedHorizontalSafeArea.left - resolvedHorizontalSafeArea.right - AgentVoiceCallCardHorizontalInset * 2)
                .coerceAtLeast(0.dp)
                .coerceAtMost(AgentVoiceCallCardMaxWidth)
        val hostMetrics =
            AgentVoiceCallHostMetrics(
                hostWidthPx = with(density) { maxWidth.toPx() },
                hostHeightPx = with(density) { maxHeight.toPx() },
                leftSafeAreaPx = with(density) { resolvedHorizontalSafeArea.left.coerceAtLeast(0.dp).toPx() },
                rightSafeAreaPx = with(density) { resolvedHorizontalSafeArea.right.coerceAtLeast(0.dp).toPx() },
                topSafeAreaPx = with(density) { resolvedTopSafeArea.toPx() },
                bottomSafeAreaPx = with(density) { resolvedBottomClearance.toPx() },
                horizontalInsetPx = with(density) { AgentVoiceCallCardHorizontalInset.toPx() },
                topGapPx = with(density) { AgentVoiceCallCardTopGap.toPx() },
                bottomGapPx = with(density) { AgentVoiceCallCardBottomGap.toPx() },
                maxOverscrollPx = with(density) { AgentVoiceCallDragMaxOverscroll.toPx() },
                wide = isWide,
                isLtr = isLtr,
                density = density,
            )
        val latestMetrics by rememberUpdatedState(hostMetrics)
        val latestPositionHolder by rememberUpdatedState(positionHolder)

        AgentVoiceCallCard(
            call = call,
            nowEpochMs = nowEpochMs,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker,
            onHangUp = onHangUp,
            onClose = onClose,
            onRetry = onRetry,
            draggable = true,
            isDragging = isDragging,
            modifier =
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        measuredCardSize.value = IntSize(placeable.width, placeable.height)
                        layout(placeable.width, placeable.height) {
                            // Read live IME, drag, and spring state inside placement so drag and
                            // keyboard frames skip composition and measurement.
                            val liveImeBottomPx = raftImeBottomPx(imeInsets, hostImeInsets, hostMetrics.density).toFloat()
                            val liveBounds =
                                hostMetrics.toBounds(
                                    cardWidthPx = placeable.width.toFloat(),
                                    cardHeightPx = placeable.height.toFloat(),
                                    imeBottomPx = liveImeBottomPx,
                                )
                            val desired = localPositionPx ?: defaultVoiceCallFloatingOffset(liveBounds)
                            val clamped = clampVoiceCallFloatingOffset(desired, liveBounds)
                            val visualOverscroll = overscrollOffset.value
                            placeable.place(
                                x = (clamped.xPx + visualOverscroll.x).roundToInt(),
                                y = (clamped.yPx + visualOverscroll.y).roundToInt(),
                            )
                        }
                    }
                    .widthIn(max = maxAvailableCardWidth)
                    .fillMaxWidth()
                    .raftKeepsBackSwipe()
                    .pointerInput(sessionKey) {
                        var rawOverscrollX = 0f
                        var rawOverscrollY = 0f

                        fun resolveLiveBounds(): AgentVoiceCallFloatingBounds {
                            val metrics = latestMetrics
                            val measured = measuredCardSize.value
                            val liveImeBottomPx = raftImeBottomPx(imeInsets, hostImeInsets, metrics.density).toFloat()
                            return metrics.toBounds(
                                cardWidthPx = measured.width.toFloat(),
                                cardHeightPx = measured.height.toFloat(),
                                imeBottomPx = liveImeBottomPx,
                            )
                        }

                        fun finishDrag() {
                            val bounds = resolveLiveBounds()
                            val finalPos = localPositionPx?.let { clampVoiceCallFloatingOffset(it, bounds) }
                            if (finalPos != null) {
                                localPositionPx = finalPos
                                val d = latestMetrics.density.density
                                if (d > 0f) {
                                    latestPositionHolder.save(
                                        sessionKey = sessionKey,
                                        xDp = finalPos.xPx / d,
                                        yDp = finalPos.yPx / d,
                                    )
                                }
                            }
                            val hadOverscroll =
                                rawOverscrollX != 0f || rawOverscrollY != 0f || overscrollOffset.value != Offset.Zero
                            isDragging = false
                            rawOverscrollX = 0f
                            rawOverscrollY = 0f
                            if (hadOverscroll) {
                                scope.launch {
                                    overscrollOffset.animateTo(
                                        targetValue = Offset.Zero,
                                        animationSpec =
                                            spring(
                                                dampingRatio = AgentVoiceCallOverscrollSpringDamping,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                    )
                                }
                            }
                        }

                        detectDragGestures(
                            onDragStart = {
                                val bounds = resolveLiveBounds()
                                val currentOverscroll = overscrollOffset.value
                                val currentBase =
                                    clampVoiceCallFloatingOffset(
                                        localPositionPx ?: defaultVoiceCallFloatingOffset(bounds),
                                        bounds,
                                    )
                                localPositionPx =
                                    clampVoiceCallFloatingOffset(
                                        AgentVoiceCallFloatingOffset(
                                            xPx = currentBase.xPx + currentOverscroll.x,
                                            yPx = currentBase.yPx + currentOverscroll.y,
                                        ),
                                        bounds,
                                    )
                                rawOverscrollX = 0f
                                rawOverscrollY = 0f
                                isDragging = true
                                if (currentOverscroll != Offset.Zero) {
                                    scope.launch { overscrollOffset.snapTo(Offset.Zero) }
                                }
                            },
                            onDragEnd = { finishDrag() },
                            onDragCancel = { finishDrag() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val bounds = resolveLiveBounds()
                                val current =
                                    localPositionPx
                                        ?: clampVoiceCallFloatingOffset(defaultVoiceCallFloatingOffset(bounds), bounds)
                                val xRange = agentVoiceCallHorizontalRange(bounds)
                                val yRange = agentVoiceCallVerticalRange(bounds)
                                val maxOverscroll = latestMetrics.maxOverscrollPx
                                val nextX =
                                    stepVoiceCallFloatingDragAxis(
                                        positionPx = current.xPx,
                                        rawOverscrollPx = rawOverscrollX,
                                        deltaPx = dragAmount.x,
                                        minPx = xRange.start,
                                        maxPx = xRange.endInclusive,
                                        maxOverscrollPx = maxOverscroll,
                                    )
                                val nextY =
                                    stepVoiceCallFloatingDragAxis(
                                        positionPx = current.yPx,
                                        rawOverscrollPx = rawOverscrollY,
                                        deltaPx = dragAmount.y,
                                        minPx = yRange.start,
                                        maxPx = yRange.endInclusive,
                                        maxOverscrollPx = maxOverscroll,
                                    )
                                localPositionPx = AgentVoiceCallFloatingOffset(xPx = nextX.positionPx, yPx = nextY.positionPx)
                                rawOverscrollX = nextX.rawOverscrollPx
                                rawOverscrollY = nextY.rawOverscrollPx
                                val damped =
                                    Offset(
                                        x = dampVoiceCallFloatingOverscrollPx(rawOverscrollX, maxOverscroll),
                                        y = dampVoiceCallFloatingOverscrollPx(rawOverscrollY, maxOverscroll),
                                    )
                                if (damped != overscrollOffset.value) {
                                    scope.launch { overscrollOffset.snapTo(damped) }
                                }
                            },
                        )
                    },
        )
    }
}

/**
 * Pure presentation for the floating Agent Voice call card (mirrors web `AgentVoiceCallPanel.tsx`):
 * agent avatar (with a still `presence-online` ring while speaking), agent name + live phase / clock,
 * last two caption lines, mid-call mic warning banner, typed failure/end notes, and Mute / Speaker /
 * Hang up (or Retry / Close once over).
 */
@Composable
internal fun AgentVoiceCallCard(
    call: AgentVoiceCallState,
    nowEpochMs: Long,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
    isDragging: Boolean = false,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val agentName = call.agent.name
    val phase = call.phase
    val inCall = phase == AgentVoiceCallPhase.Live || phase == AgentVoiceCallPhase.Reconnecting
    val over = !call.isActive

    val announcement =
        when (phase) {
            AgentVoiceCallPhase.Starting,
            AgentVoiceCallPhase.Connecting -> stringResource(Res.string.agent_voice_call_connecting)
            AgentVoiceCallPhase.Reconnecting -> stringResource(Res.string.agent_voice_call_reconnecting)
            AgentVoiceCallPhase.Live -> stringResource(Res.string.agent_voice_call_connected)
            AgentVoiceCallPhase.Ended ->
                stringResource(Res.string.agent_voice_call_ended, formatAgentVoiceCallClock(call.durationMs ?: 0L))
            AgentVoiceCallPhase.Failed -> stringResource(Res.string.agent_voice_call_not_connected)
        }
    val stateLine =
        if (phase == AgentVoiceCallPhase.Live) {
            val liveSince = call.connectedAtEpochMs ?: nowEpochMs
            formatAgentVoiceCallClock((nowEpochMs - liveSince).coerceAtLeast(0L))
        } else {
            announcement
        }

    val blockedMicReason = call.micBlockedReason?.takeIf { it.isNotBlank() }
    val micWarningText =
        if (inCall && blockedMicReason != null) {
            agentVoiceLiveMicWarningText(blockedMicReason, agentName)
        } else {
            null
        }

    val notes = buildCallNotes(call = call, agentName = agentName)
    val windowLabel = stringResource(Res.string.agent_voice_call_window, agentName)
    val muteLabel = stringResource(Res.string.agent_voice_call_mute)
    val soundOffLabel = stringResource(Res.string.agent_voice_call_sound_off)
    val micUnavailableLabel = stringResource(Res.string.agent_voice_call_mic_unavailable)
    val hangUpLabel = stringResource(Res.string.agent_voice_call_hang_up)
    val youLabel = stringResource(Res.string.agent_voice_call_you)
    val cardShape = RoundedCornerShape(RaftRadius.Container)
    val targetElevation =
        when {
            isDragging && RaftTheme.isDark -> AgentVoiceCallCardDragElevationDark
            isDragging -> AgentVoiceCallCardDragElevationLight
            RaftTheme.isDark -> AgentVoiceCallCardRestElevationDark
            else -> AgentVoiceCallCardRestElevationLight
        }
    val shadowElevation by animateDpAsState(
        targetValue = targetElevation,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
    )

    CompositionLocalProvider(
        LocalRaftContentColor provides colors.foreground,
        LocalRaftSurfaceColor provides colors.layerPopover,
    ) {
        Box(
            modifier =
                modifier
                    .testTag("agent-voice-call-window")
                    .semantics { contentDescription = windowLabel }
                    .shadow(elevation = shadowElevation, shape = cardShape)
                    .clip(cardShape)
                    .background(colors.layerPopover, cardShape)
                    .border(RaftStroke.Hairline, colors.lineSeam, cardShape),
        ) {
            if (draggable) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = AgentVoiceCallDragHandleTopInset)
                            .width(AgentVoiceCallDragHandleWidth)
                            .height(AgentVoiceCallDragHandleHeight)
                            .clip(RoundedCornerShape(AgentVoiceCallDragHandleRadius))
                            .background(colors.foregroundHint.copy(alpha = AgentVoiceCallDragHandleAlpha))
                            .testTag("agent-voice-call-drag-handle"),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ringShape = RoundedCornerShape(RaftRadius.Control)
                    val ringModifier =
                        if (call.agentSpeaking) {
                            Modifier
                                .testTag("agent-voice-call-speaking-ring")
                                .border(AgentVoiceCallSpeakingRingStroke, RaftUiPalette.presenceOnline, ringShape)
                                .padding(AgentVoiceCallSpeakingRingPadding)
                        } else {
                            Modifier.padding(AgentVoiceCallSpeakingRingPadding)
                        }
                    Box(modifier = ringModifier) {
                        RaftAppAvatarSticker(
                            kind = RaftAvatarKind.Agent,
                            avatarWireValue = call.agent.avatarWireValue,
                            label = agentName,
                            size = RaftAvatarSize.Md,
                            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, call.agent.id),
                            cacheServerId = call.serverId,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RaftText(
                            text = agentName,
                            modifier = Modifier.testTag("agent-voice-call-name"),
                            style = typography.cardTitle,
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RaftText(
                            text = stateLine,
                            modifier =
                                Modifier
                                    .testTag("agent-voice-call-state")
                                    .semantics {
                                        if (phase != AgentVoiceCallPhase.Live) {
                                            liveRegion = LiveRegionMode.Polite
                                        }
                                    },
                            style = typography.callout.copy(fontFeatureSettings = "tnum"),
                            color = colors.foregroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (call.captions.isNotEmpty() && !over) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("agent-voice-call-captions"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        call.captions.forEach { line ->
                            val who =
                                stringResource(
                                    Res.string.agent_voice_call_caption_who,
                                    if (line.mine) youLabel else agentName,
                                )
                            val annotated =
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                        append(who)
                                    }
                                    append(' ')
                                    append(line.text)
                                }
                            RaftText(
                                text = annotated,
                                style = typography.footnote,
                                color = if (line.mine) colors.foregroundMuted else colors.foreground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (micWarningText != null) {
                    RaftBanner(
                        description = micWarningText,
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        showIcon = true,
                        modifier = Modifier.testTag("agent-voice-call-mic"),
                    )
                }

                if (notes.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("agent-voice-call-notes"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        notes.forEach { note ->
                            RaftText(
                                text = note.text,
                                style = typography.footnote,
                                color = if (note.muted) colors.foregroundMuted else colors.foreground,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (over) {
                        if (phase == AgentVoiceCallPhase.Failed && call.problem?.recoverable != false) {
                            RaftButton(
                                text = stringResource(Res.string.common_retry),
                                onClick = onRetry,
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                modifier = Modifier.testTag("agent-voice-call-retry"),
                            )
                        }
                        RaftButton(
                            text = stringResource(Res.string.common_close),
                            onClick = onClose,
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            modifier = Modifier.testTag("agent-voice-call-close"),
                        )
                    } else {
                        if (inCall) {
                            val micBlocked = blockedMicReason != null
                            RaftIconToggleButton(
                                checked = call.muted,
                                onCheckedChange = { onToggleMute() },
                                tooltip = if (micBlocked) micUnavailableLabel else muteLabel,
                                size = RaftIconButtonSize.Lg,
                                enabled = !micBlocked,
                                modifier = Modifier.testTag("agent-voice-call-mute"),
                            ) {
                                RaftContentLucideIcon(
                                    name = if (call.muted || micBlocked) RaftLucideIconName.MicOff else RaftLucideIconName.Mic,
                                    size = RaftTheme.controls.iconSize,
                                )
                            }
                            RaftIconToggleButton(
                                checked = !call.speakerOn,
                                onCheckedChange = { onToggleSpeaker() },
                                tooltip = soundOffLabel,
                                size = RaftIconButtonSize.Lg,
                                modifier = Modifier.testTag("agent-voice-call-sound"),
                            ) {
                                RaftContentLucideIcon(
                                    name = if (!call.speakerOn) RaftLucideIconName.VolumeOff else RaftLucideIconName.Volume2,
                                    size = RaftTheme.controls.iconSize,
                                )
                            }
                        }
                        RaftIconButton(
                            onClick = onHangUp,
                            tooltip = hangUpLabel,
                            variant = RaftButtonVariant.Warning,
                            size = RaftIconButtonSize.Lg,
                            modifier = Modifier.testTag("agent-voice-call-hang-up"),
                        ) {
                            RaftContentLucideIcon(
                                name = RaftLucideIconName.PhoneOff,
                                size = RaftTheme.controls.iconSize,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class AgentVoiceCallNoteLine(
    val text: String,
    val muted: Boolean,
)

@Composable
private fun buildCallNotes(
    call: AgentVoiceCallState,
    agentName: String,
): List<AgentVoiceCallNoteLine> =
    buildList {
        if (call.phase == AgentVoiceCallPhase.Failed) {
            val problem = call.problem ?: AgentVoiceCallProblem.Failed
            add(
                AgentVoiceCallNoteLine(
                    text = agentVoiceProblemText(problem, agentName),
                    muted = problem.recoverable,
                )
            )
        }
        if (call.phase == AgentVoiceCallPhase.Ended) {
            if (call.endReason == AgentVoiceCallEndReason.Lost) {
                add(
                    AgentVoiceCallNoteLine(
                        text = stringResource(Res.string.agent_voice_call_connection_lost),
                        muted = false,
                    )
                )
            }
            if (call.limitReached) {
                add(
                    AgentVoiceCallNoteLine(
                        text = stringResource(Res.string.agent_voice_call_limit, AGENT_VOICE_CALL_MAX_MINUTES),
                        muted = false,
                    )
                )
            }
            add(
                AgentVoiceCallNoteLine(
                    text = stringResource(Res.string.agent_voice_call_record, agentName),
                    muted = false,
                )
            )
        }
    }

@Composable
private fun agentVoiceProblemText(
    problem: AgentVoiceCallProblem,
    agentName: String,
): String =
    when (problem) {
        AgentVoiceCallProblem.NotAllowed -> stringResource(Res.string.agent_voice_call_problem_not_allowed)
        AgentVoiceCallProblem.AgentOffline -> stringResource(Res.string.agent_voice_call_problem_agent_offline, agentName)
        AgentVoiceCallProblem.Unavailable -> stringResource(Res.string.agent_voice_call_problem_unavailable)
        AgentVoiceCallProblem.UnsupportedRuntime -> stringResource(Res.string.agent_voice_call_problem_unsupported_runtime, agentName)
        AgentVoiceCallProblem.RateLimited -> stringResource(Res.string.agent_voice_call_problem_rate_limited)
        AgentVoiceCallProblem.Failed -> stringResource(Res.string.agent_voice_call_problem_failed)
        AgentVoiceCallProblem.MicPermission -> stringResource(Res.string.agent_voice_call_mic_permission)
        AgentVoiceCallProblem.MicBusy -> stringResource(Res.string.agent_voice_call_mic_busy)
        AgentVoiceCallProblem.MicNone -> stringResource(Res.string.agent_voice_call_mic_none)
    }

@Composable
private fun agentVoiceLiveMicWarningText(
    reason: String,
    agentName: String,
): String =
    when (reason.trim().lowercase()) {
        "busy", "mic_busy", "in_use" -> stringResource(Res.string.agent_voice_call_mic_live_busy, agentName)
        "none", "mic_none", "unavailable", "disconnected" -> stringResource(Res.string.agent_voice_call_mic_live_none, agentName)
        else -> stringResource(Res.string.agent_voice_call_mic_live_permission, agentName)
    }
