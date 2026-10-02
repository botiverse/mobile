package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.dp

/** Touch target of the list divider; the visible divider stays 1dp. */
private const val SlockListPaneResizeHandleWidthDp = 16f

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
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val currentWidthDp by rememberUpdatedState(listWidthDp)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    var dragging by remember { mutableStateOf(false) }
    val endDrag = {
        if (dragging) {
            dragging = false
            currentOnDragEnd()
        }
    }
    LaunchedEffect(active) { if (!active) endDrag() }
    DisposableEffect(Unit) { onDispose { endDrag() } }
    Box(
        modifier =
            Modifier
                .offset(x = (listWidthDp - SlockListPaneResizeHandleWidthDp / 2f).dp)
                .width(SlockListPaneResizeHandleWidthDp.dp)
                .fillMaxHeight()
                .slockNativeDispatchCapture()
                .pointerInput(Unit) {
                    var startWidthDp = 0f
                    var deltaPx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            startWidthDp = currentWidthDp
                            deltaPx = 0f
                            dragging = true
                            currentOnDragStart()
                        },
                        onDragEnd = { endDrag() },
                        onDragCancel = { endDrag() },
                    ) { change, dragAmount ->
                        change.consume()
                        // Ended by a tab switch or similar while the finger is still down.
                        if (!dragging) return@detectHorizontalDragGestures
                        deltaPx += dragAmount
                        currentOnDrag(startWidthDp, with(density) { deltaPx.toDp().value })
                    }
                }
    )
}
