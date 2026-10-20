package build.raft.app

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Compose replacement for Kuikly's `MoveableDrawer` (a two-page native pager: page 0 is the
 * drawer panel, page 1 the full-width content). The drawer slides in from the start edge and
 * pushes the content by `drawerWidth * progress`, matching the Kuikly geometry, so call sites
 * that size overlays from [RaftMoveableDrawerState.progress] keep working.
 */
@Stable
internal class RaftMoveableDrawerState(initiallyOpen: Boolean, val drawerWidth: Dp) {
    private var progressValue by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)

    internal var drawerWidthPx by mutableFloatStateOf(0f)

    /** 0 = fully closed, 1 = fully open. */
    val progress: Float
        get() = progressValue

    /** Mirrors the Kuikly pager's `currentPage == 0` (nearest snap page is the drawer). */
    val isOpen: Boolean
        get() = progressValue >= 0.5f

    internal val draggableState =
        DraggableState { deltaPx ->
            val width = drawerWidthPx
            if (width > 0f) {
                progressValue = (progressValue + deltaPx / width).coerceIn(0f, 1f)
            }
        }

    suspend fun open() = animateTo(1f, priority = MutatePriority.PreventUserInput)

    suspend fun close() = animateTo(0f, priority = MutatePriority.PreventUserInput)

    suspend fun toggle() {
        if (isOpen) close() else open()
    }

    internal suspend fun settle(velocityPx: Float) {
        val width = drawerWidthPx
        val velocityProgress = if (width > 0f) velocityPx / width else 0f
        val target =
            when {
                abs(velocityPx) > RaftMoveableDrawerFlingVelocityPx -> if (velocityPx > 0f) 1f else 0f
                else -> if (progressValue >= 0.5f) 1f else 0f
            }
        animateTo(target, initialVelocity = velocityProgress, priority = MutatePriority.Default)
    }

    private suspend fun animateTo(
        target: Float,
        initialVelocity: Float = 0f,
        priority: MutatePriority,
        spec: AnimationSpec<Float> = spring(stiffness = 600f),
    ) {
        draggableState.drag(priority) {
            animate(
                initialValue = progressValue,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = spec,
            ) { value, _ ->
                progressValue = value.coerceIn(0f, 1f)
            }
        }
    }
}

private const val RaftMoveableDrawerFlingVelocityPx = 800f

@Composable
internal fun rememberRaftMoveableDrawerState(
    initiallyOpen: Boolean = false,
    drawerWidth: Dp = 300.dp,
): RaftMoveableDrawerState {
    val state = remember(drawerWidth) { RaftMoveableDrawerState(initiallyOpen, drawerWidth) }
    val widthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    SideEffect { state.drawerWidthPx = widthPx }
    return state
}

/**
 * @param userScrollEnabled enables the horizontal swipe gesture; programmatic
 * [RaftMoveableDrawerState.open]/[RaftMoveableDrawerState.close] always work.
 */
@Composable
internal fun RaftMoveableDrawer(
    state: RaftMoveableDrawerState,
    modifier: Modifier = Modifier,
    scrimColor: Color = Color.Black.copy(alpha = 0.3f), // raft-color: black dimming scrim reads in both modes
    userScrollEnabled: Boolean = true,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { state.drawerWidth.toPx() }
    Box(
        modifier
            .fillMaxSize()
            .draggable(
                state = state.draggableState,
                orientation = Orientation.Horizontal,
                enabled = userScrollEnabled,
                onDragStopped = { velocity -> state.settle(velocity) },
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((widthPx * state.progress).roundToInt(), 0) }
        ) {
            content()
            val progress = state.progress
            if (progress > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor.copy(alpha = scrimColor.alpha * progress))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            scope.launch { state.close() }
                        }
                )
            }
        }
        // Always composed (Kuikly kept the drawer page alive via beyondViewportPageCount = 1).
        Box(
            Modifier
                .width(state.drawerWidth)
                .fillMaxHeight()
                .offset { IntOffset((widthPx * (state.progress - 1f)).roundToInt(), 0) }
                // Swallow taps that land on empty drawer areas so they never reach the content.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            drawerContent()
        }
    }
}
