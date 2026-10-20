package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.text.SelectionContainer
import com.tencent.kuikly.compose.text.SelectionFrame
import com.tencent.kuikly.compose.text.TextSelectionType
import com.tencent.kuikly.compose.text.rememberSelectionContainerState
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.semantics.testTag
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.dp

/**
 * Makes rendered text inside [content] selectable in place.
 *
 * Kuikly's `SelectionContainer` only marks the subtree selectable: none of the
 * Android / iOS / OHOS renderers start a selection on long-press or show a copy
 * menu. So this starts the selection from a long-press (`createSelection`) and
 * draws a small Copy / Select All bar above it; Copy hands the selected plain
 * text to [onCopy]. A tap outside the bar clears the selection.
 */
@Composable
internal fun SlockInPlaceTextSelection(
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable () -> Unit
) {
    val state = rememberSelectionContainerState()
    var active by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf(SelectionFrame.Zero) }
    val barGapPx = with(LocalDensity.current) { 44.dp.toPx() }
    fun clear() {
        state.clearSelection()
        active = false
        menuVisible = false
    }
    Box(modifier = modifier) {
        SelectionContainer(
            state = state,
            modifier =
                Modifier
                    // Re-keyed on [active] so taps only reach the body (links) while no
                    // selection is showing.
                    .pointerInput(state, active) {
                        detectTapGestures(
                            onLongPress = { offset ->
                                state.createSelection(offset.x, offset.y, TextSelectionType.WORD)
                            },
                            onTap = if (active) { _ -> clear() } else null
                        )
                    }
                    .let { if (testTag != null) it.testTag(testTag) else it },
            onSelectStart = {
                frame = it
                active = true
                menuVisible = true
            },
            onSelectChange = {
                frame = it
                menuVisible = false
            },
            onSelectEnd = {
                frame = it
                menuVisible = true
            },
            onSelectCancel = {
                active = false
                menuVisible = false
            }
        ) {
            content()
        }
        if (active && menuVisible) {
            Row(
                modifier =
                    Modifier
                        .offset {
                            IntOffset(
                                frame.x.toInt().coerceAtLeast(0),
                                (frame.y - barGapPx).toInt().coerceAtLeast(0)
                            )
                        }
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                SlockInPlaceTextSelectionAction(stringResource(MR.strings.thread_new_message_menu_copy)) {
                    state.getSelection { selected ->
                        val text = selected.joinToString(separator = "")
                        if (text.isNotEmpty()) onCopy(text)
                    }
                    clear()
                }
                SlockInPlaceTextSelectionAction(stringResource(MR.strings.text_selection_select_all)) {
                    state.createSelectionAll()
                }
            }
        }
    }
}

@Composable
private fun SlockInPlaceTextSelectionAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = SlockType.Caption,
        color = SlockColor.TextBlack,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
