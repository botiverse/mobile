package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.data.SlockListPaneDragSession
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
    val session = remember { SlockListPaneDragSession(onEnd = { currentOnDragEnd() }) }
    LaunchedEffect(active) { if (!active) session.end() }
    // A density change restarts the gesture below; the drag in flight ends here first, so the
    // next drag converts with the new density.
    DisposableEffect(density) { onDispose { session.end() } }
    Box(
        modifier =
            Modifier
                .offset(x = (listWidthDp - SlockListPaneResizeHandleWidthDp / 2f).dp)
                .width(SlockListPaneResizeHandleWidthDp.dp)
                .fillMaxHeight()
                .slockNativeDispatchCapture()
                .pointerInput(density) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            session.start(currentWidthDp)
                            currentOnDragStart()
                        },
                        onDragEnd = { session.end() },
                        onDragCancel = { session.end() },
                    ) { change, dragAmount ->
                        change.consume()
                        // Null once the drag was ended (tab switch etc.) with the finger still down.
                        val (startWidthDp, deltaDp) = session.move(dragAmount, density.density)
                            ?: return@detectHorizontalDragGestures
                        currentOnDrag(startWidthDp, deltaDp)
                    }
                }
    )
}
