package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
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
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource

/** Widest the task thread panel grows over the board (matches the web task modal width band). */
internal val SlockTasksDetailOverlayMaxWidth = 880.dp

/**
 * task #34: wide desktop-mode task detail. The task's thread renders in a bounded panel above
 * the Tasks board, so the board keeps its filters, column scroll and selection underneath —
 * the web desktop task modal, instead of a full-page push. Tapping the scrim or the close
 * button dismisses the detail; Back is routed by the app shell (dismiss detail first).
 */
@Composable
internal fun SlockTasksDetailOverlay(
    onDismiss: () -> Unit,
    bottomInset: Dp,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        // Scrim: keeps the board visible behind the panel and swallows taps meant for it.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(Color(0x66000000))
                    .slockNativeDispatchCapture()
                    .clickable(onClick = onDismiss)
        )
        Column(
            modifier =
                Modifier
                    .widthIn(max = SlockTasksDetailOverlayMaxWidth)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.92f)
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
    }
}
