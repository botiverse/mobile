package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import build.raft.app.data.SlockListPaneDragSession
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.dp

/** Touch target of the list divider; the visible divider stays 1dp. */
private const val SlockListPaneResizeHandleWidthDp = 16f

/** Handle width while dragging; clamped by width() to the split row, i.e. the whole row. */
private val SlockListPaneResizeHandleDragWidth = 10_000.dp

/**
 * task #37: an invisible drag handle centred on the list/detail divider (placed over the split
 * row, so it also takes touches above the native detail child's first few dp). Dragging reports
 * the width the drag started from (the rendered list width) and the total horizontal movement;
 * touch and mouse share the same pointer path.
 *
 * A drag always ends with exactly one [onDragEnd]: on lift, on gesture cancel, when the host goes
 * inactive (tab switch) and when the handle leaves composition (rotation to compact, desktop mode
 * off), so the shell never keeps a "resizing" state that holds an unfitting side column open.
 */
@Composable
internal fun SlockListPaneResizeHandle(
    active: Boolean,
    listWidthDp: Float,
    onDragStart: () -> Unit,
    onDrag: (startWidthDp: Float, deltaDp: Float) -> Unit,
    onDragEnd: (reason: String) -> Unit,
) {
    // Keyed on the density value, not the Density instance, so a recreated-but-equal Density
    // during recomposition never ends a drag in flight.
    val densityValue = LocalDensity.current.density
    val currentWidthDp by rememberUpdatedState(listWidthDp)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    var dragging by remember { mutableStateOf(false) }
    val session = remember {
        SlockListPaneDragSession(onEnd = { reason ->
            dragging = false
            currentOnDragEnd(reason)
        })
    }
    LaunchedEffect(active) { if (!active) session.end("inactive") }
    // A density change restarts the gesture below; the drag in flight ends here first, so the
    // next drag converts with the new density.
    DisposableEffect(densityValue) { onDispose { session.end("disposed_or_density") } }
    // While a drag is active the handle covers the whole split row: the list stops at its limit
    // while the finger keeps going, and a 16dp handle would otherwise lose the touch (device run on
    // 10001018 ended the drag ~0.4s before lift at 512dp). The delta is taken in window
    // coordinates from the drag's origin, so moving/resizing the handle never skews it.
    var handleRootX by remember { mutableStateOf(0f) }
    Box(
        modifier =
            // Same modifier elements in both states (only their values change), so widening the
            // handle updates the existing nodes and never recreates the gesture detector below.
            Modifier
                .offset(x = if (dragging) 0.dp else (listWidthDp - SlockListPaneResizeHandleWidthDp / 2f).dp)
                // width() is clamped to the row's max width, so the drag state covers the row.
                .width(if (dragging) SlockListPaneResizeHandleDragWidth else SlockListPaneResizeHandleWidthDp.dp)
                .fillMaxHeight()
                .onGloballyPositioned { handleRootX = it.positionInRoot().x }
                .slockNativeDispatchCapture()
                .pointerInput(densityValue) {
                    var dragOriginRootX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragOriginRootX = handleRootX + offset.x
                            session.start(currentWidthDp)
                            dragging = true
                            currentOnDragStart()
                        },
                        onDragEnd = { session.end("lift") },
                        onDragCancel = { session.end("cancel") },
                    ) { change, _ ->
                        change.consume()
                        // Null once the drag was ended (tab switch etc.) with the finger still down.
                        val (startWidthDp, deltaDp) =
                            session.moveTo(handleRootX + change.position.x - dragOriginRootX, densityValue)
                                ?: return@detectHorizontalDragGestures
                        currentOnDrag(startWidthDp, deltaDp)
                    }
                }
    )
}
