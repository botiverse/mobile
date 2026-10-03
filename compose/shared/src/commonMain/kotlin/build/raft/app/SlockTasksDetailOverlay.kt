package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.awaitEachGesture
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource

/** Widest the task thread panel grows over the board (matches the web task modal width band). */
internal val SlockTasksDetailOverlayMaxWidth = 880.dp

private val SlockTasksDetailOverlayScrimColor = Color(0x66000000)

/** Share of the height / width the panel takes; the rest is scrim around it. */
private const val SlockTasksDetailOverlayPanelFraction = 0.92f

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
internal fun SlockTasksDetailOverlay(
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
        SlockTasksDetailOverlayScrim(onDismiss, Modifier.weight(1f).fillMaxHeight())
        Column(
            modifier =
                Modifier
                    .widthIn(max = SlockTasksDetailOverlayMaxWidth)
                    .fillMaxWidth(SlockTasksDetailOverlayPanelFraction)
                    .fillMaxHeight()
        ) {
            val edge = (1f - SlockTasksDetailOverlayPanelFraction) / 2f
            SlockTasksDetailOverlayScrim(onDismiss, Modifier.fillMaxWidth().weight(edge))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(SlockTasksDetailOverlayPanelFraction)
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.X,
                        onClick = onDismiss,
                        contentDescription = stringResource(MR.strings.common_close),
                        size = 32.dp,
                        shadowOffset = 2.dp,
                    )
                }
                content(Modifier.fillMaxWidth().weight(1f))
            }
            SlockTasksDetailOverlayScrim(onDismiss, Modifier.fillMaxWidth().weight(edge))
        }
        SlockTasksDetailOverlayScrim(onDismiss, Modifier.weight(1f).fillMaxHeight())
    }
    }
}

/** A scrim region beside the panel: dims the board and turns a tap into a dismiss. */
@Composable
private fun SlockTasksDetailOverlayScrim(onDismiss: () -> Unit, modifier: Modifier) {
    Box(
        modifier =
            modifier
                .background(SlockTasksDetailOverlayScrimColor)
                .slockNativeDispatchCapture()
                .clickable(onClick = onDismiss)
    )
}
