package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 */
@Composable
internal fun SlockListPaneResizeHandle(
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
                            currentOnDragStart()
                        },
                        onDragEnd = { currentOnDragEnd() },
                        onDragCancel = { currentOnDragEnd() },
                    ) { change, dragAmount ->
                        change.consume()
                        deltaPx += dragAmount
                        currentOnDrag(startWidthDp, with(density) { deltaPx.toDp().value })
                    }
                }
    )
}
