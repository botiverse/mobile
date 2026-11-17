package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** raft-ui `continuationTime`: font-mono text-mono-meta, 12/16 regular, ink-3. */
internal val ThreadMessageGutterClockStyle: TextStyle
    get() =
        RaftType.Caption.copy(
            fontFamily = RaftMonoFamily,
            fontWeight = FontWeight.Normal,
            color = RaftColor.TextBlack.copy(alpha = 0.38f)
        )

/**
 * How far a continuation row's clock may run past the 35dp avatar column into the 12dp gap before
 * the body (web lets "23:45" in Space Mono 12px overflow its 35px gutter the same way).
 */
private val ThreadMessageGutterClockGapAllowance = 4.dp

/**
 * The width of the message rows' avatar column: the avatar's [minWidth], widened when the widest
 * gutter clock ("00:00": every Space Mono digit is as wide) at the current font scale would not fit
 * it. One value for every row, header rows too, so the bodies stay aligned while the clock is never
 * cut (artin: 得保证这个时间能完全展示出来).
 */
@Composable
internal fun rememberThreadMessageGutterWidth(minWidth: Dp): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val density = LocalDensity.current
    val style = ThreadMessageGutterClockStyle
    return remember(measurer, density, style, minWidth) {
        val clockWidth = with(density) { measurer.measure("00:00", style, maxLines = 1).size.width.toDp() }
        maxOf(minWidth, clockWidth - ThreadMessageGutterClockGapAllowance)
    }
}
