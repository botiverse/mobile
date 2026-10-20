package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource

/** Widest the task thread panel grows over the board (matches the web task modal width band). */
internal val RaftTasksDetailOverlayMaxWidth = 880.dp

private val RaftTasksDetailOverlayScrimColor = Color(0x66000000)

/** Share of the height / width the panel takes; the rest is scrim around it. */
private const val RaftTasksDetailOverlayPanelFraction = 0.92f

/**
 * task #34: wide desktop-mode task detail. The task's thread renders in a bounded panel above
 * the Tasks board, so the board keeps its filters, column scroll and selection underneath —
 * the web desktop task modal, instead of a full-page push. Tapping the scrim or the close
 * button dismisses the detail; Back is routed by the app shell (dismiss detail first).
 *
 * The dismissing scrim is laid out only AROUND the panel (left / right columns and the strips
 * above and below it), never underneath it: the panel hosts a native child page that does not
 * take part in Compose hit testing, so a scrim behind the panel would receive every tap meant
 * for the composer or the message list and close the overlay.
 */
@Composable
internal fun RaftTasksDetailOverlay(
    onDismiss: () -> Unit,
    bottomInset: Dp,
    content: @Composable (Modifier) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(bottom = bottomInset)) {
    // Touch guard under the whole overlay: Compose-level events that the panel does not consume
    // (it hosts a native child page outside Compose hit testing) stop here instead of reaching
    // the board underneath — a tap on the panel's send button must not also open a task card.
    // It only swallows; dismissing is the scrim regions' job, so the panel never closes itself.
    Box(
        modifier =
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.all { !it.pressed }) break
                        }
                    }
                }
    )
    Row(modifier = Modifier.fillMaxSize()) {
        RaftTasksDetailOverlayScrim(onDismiss, Modifier.weight(1f).fillMaxHeight())
        Column(
            modifier =
                Modifier
                    .widthIn(max = RaftTasksDetailOverlayMaxWidth)
                    .fillMaxWidth(RaftTasksDetailOverlayPanelFraction)
                    .fillMaxHeight()
        ) {
            val edge = (1f - RaftTasksDetailOverlayPanelFraction) / 2f
            RaftTasksDetailOverlayScrim(onDismiss, Modifier.fillMaxWidth().weight(edge))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(RaftTasksDetailOverlayPanelFraction)
                        .background(RaftColor.SurfaceWhite)
                        .border(RaftAppStroke.Default, RaftColor.BorderBlack)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftBrutalIconButton(
                        iconName = RaftLucideIconName.X,
                        onClick = onDismiss,
                        contentDescription = stringResource(MR.strings.common_close),
                        size = 32.dp,
                        shadowOffset = 2.dp,
                    )
                }
                content(Modifier.fillMaxWidth().weight(1f))
            }
            RaftTasksDetailOverlayScrim(onDismiss, Modifier.fillMaxWidth().weight(edge))
        }
        RaftTasksDetailOverlayScrim(onDismiss, Modifier.weight(1f).fillMaxHeight())
    }
    }
}

/** A scrim region beside the panel: dims the board and turns a tap into a dismiss. */
@Composable
private fun RaftTasksDetailOverlayScrim(onDismiss: () -> Unit, modifier: Modifier) {
    Box(
        modifier =
            modifier
                .background(RaftTasksDetailOverlayScrimColor)
                .raftNativeDispatchCapture()
                .clickable(onClick = onDismiss)
    )
}
