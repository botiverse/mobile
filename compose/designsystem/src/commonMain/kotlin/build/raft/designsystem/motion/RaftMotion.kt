package build.raft.designsystem.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable

/**
 * raft-ui Motion (design/foundations/motion.md): `ease-slide` is the one curve for things that
 * move (slides, sheets, an avatar stack), and slides take 220ms to 380ms. With reduced motion
 * nothing moves ([rememberRaftReducedMotion]).
 */
object RaftMotion {
    /** raft-ui `--ease-slide` (styles.css): `cubic-bezier(0.32, 0.72, 0, 1)`. */
    val EaseSlide: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** A short slide (raft-ui `duration-220`: the Switch knob, a panel pulled down from a bar). */
    const val SlideMillis: Int = 220
}

/**
 * Whether the reader asked the system for less motion (web `prefers-reduced-motion`): Android's
 * Remove animations (the animator duration scale at 0), iOS Reduce Motion. Desktop: never.
 */
@Composable
expect fun rememberRaftReducedMotion(): Boolean
