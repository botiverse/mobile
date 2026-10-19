package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.delay

/** raft-ui Spinner `size`: a 2x2 grid of [pixel] squares [gap] apart (`--spinner-pixel` / `--spinner-gap`). */
enum class RaftSpinnerSize(val pixel: Dp, val gap: Dp) {
    Xs(4.dp, 1.5.dp),
    Sm(5.dp, 2.dp),
    Md(6.dp, 2.dp),
    Lg(8.dp, 3.dp),
    ;

    /** Edge of the grid: two pixels and the gap between them. */
    val extent: Dp get() = pixel * 2 + gap
}

/** raft-ui Spinner `variant`: [Default] takes the current ink, [Inverse] `--foreground-inverse`. */
enum class RaftSpinnerVariant {
    Default,
    Inverse,
}

/** `raft-spinner-pixel`: a 1s cycle in four `steps(1)` quarters. */
const val RaftSpinnerCycleMillis = 1000
const val RaftSpinnerStepMillis = RaftSpinnerCycleMillis / 4

/** Resting opacity of the three unlit pixels (`opacity-20`). */
const val RaftSpinnerRestAlpha = 0.2f

/**
 * Grid cell lit at [step] (cells in DOM order: 0 top-left, 1 top-right, 2 bottom-left,
 * 3 bottom-right). raft-ui delays children 1/2/3/4 by 0 / 0.25 / 0.75 / 0.5s, so the
 * light walks clockwise: top-left, top-right, bottom-right, bottom-left.
 */
fun raftSpinnerLitCell(step: Int): Int = ClockwiseCells[((step % 4) + 4) % 4]

private val ClockwiseCells = intArrayOf(0, 1, 3, 2)

/**
 * Freezes every [RaftSpinner] in the subtree that has no `stillStep` of its own on this
 * step: screenshot fixtures of components that hold a spinner (a busy button).
 */
internal val LocalRaftSpinnerStillStep = staticCompositionLocalOf<Int?> { null }

/**
 * raft-ui 0.8.0 Spinner: four square pixels (1dp radius) in a 2x2 grid, in the current
 * ink ([LocalRaftContentColor], CSS `text-current`) or `--foreground-inverse`. Three rest
 * at 20%; one at a time is lit at full ink for a quarter of a 1s cycle, stepping (no fade)
 * clockwise from the top-left.
 *
 * [stillStep] freezes the spinner on one step: screenshots, and reduced motion (raft-ui
 * stops the pixels with the first one lit, i.e. `stillStep = 0`).
 */
@Composable
fun RaftSpinner(
    modifier: Modifier = Modifier,
    size: RaftSpinnerSize = RaftSpinnerSize.Sm,
    variant: RaftSpinnerVariant = RaftSpinnerVariant.Default,
    color: Color = Color.Unspecified,
    contentDescription: String = "Loading",
    stillStep: Int? = null,
) {
    val ink =
        when {
            color.isSpecified -> color
            variant == RaftSpinnerVariant.Inverse -> RaftTheme.colors.foregroundInverse
            else -> LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
        }
    val still = stillStep ?: LocalRaftSpinnerStillStep.current
    var animatedStep by remember { mutableIntStateOf(0) }
    if (still == null) {
        LaunchedEffect(Unit) {
            // Sleep between steps instead of waiting on every frame, so the frame clock
            // is only woken 4 times a second.
            while (true) {
                delay(RaftSpinnerStepMillis.toLong())
                animatedStep = (animatedStep + 1) % 4
            }
        }
    }
    Canvas(modifier.size(size.extent).semantics { this.contentDescription = contentDescription }) {
        val lit = raftSpinnerLitCell(still ?: animatedStep)
        val pixel = size.pixel.toPx()
        val pitch = pixel + size.gap.toPx()
        val radius = CornerRadius(RaftRadius.Pixel.toPx())
        for (cell in 0 until 4) {
            drawRoundRect(
                color = if (cell == lit) ink else ink.copy(alpha = ink.alpha * RaftSpinnerRestAlpha),
                topLeft = Offset((cell % 2) * pitch, (cell / 2) * pitch),
                size = Size(pixel, pixel),
                cornerRadius = radius,
            )
        }
    }
}
