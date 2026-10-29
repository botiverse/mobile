package build.raft.designsystem.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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

    /**
     * Material's fade through, the Android platform transition between top-level destinations
     * (the bottom tabs). raft-ui leaves platform transitions to the platform (motion.md: a page
     * pushed on a phone uses the platform default), so these are Material's numbers, not
     * raft-ui tokens: 300ms in all, the outgoing page fades out over the first 35%, then the
     * incoming page fades in and grows from 92% over the rest, on Material's standard curve.
     * iOS has no tab transition (the system tab bar switches at once), so don't use it there.
     */
    const val FadeThroughMillis: Int = 300

    /** The share of [FadeThroughMillis] the outgoing page takes to fade out. */
    const val FadeThroughOutgoingFraction: Float = 0.35f

    /** The incoming page's starting scale in a fade through. */
    const val FadeThroughStartScale: Float = 0.92f

    /** Material's standard easing, `cubic-bezier(0.4, 0, 0.2, 1)` (fade through only). */
    val EaseStandard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** The outgoing page's fade-out length in a fade through (105ms). */
    val FadeThroughOutgoingMillis: Int
        get() = (FadeThroughMillis * FadeThroughOutgoingFraction).toInt()

    /** The incoming page's fade-in length in a fade through, after the outgoing one (195ms). */
    val FadeThroughIncomingMillis: Int
        get() = FadeThroughMillis - FadeThroughOutgoingMillis

    /**
     * The fade through for an `AnimatedContent` keyed on the selected tab (Android's bottom tabs).
     * With [reducedMotion] the page swaps at once.
     */
    fun fadeThrough(reducedMotion: Boolean): ContentTransform {
        if (reducedMotion) return EnterTransition.None togetherWith ExitTransition.None
        val incoming = tween<Float>(
            durationMillis = FadeThroughIncomingMillis,
            delayMillis = FadeThroughOutgoingMillis,
            easing = EaseStandard,
        )
        return (fadeIn(incoming) + scaleIn(incoming, initialScale = FadeThroughStartScale)) togetherWith
            fadeOut(tween(durationMillis = FadeThroughOutgoingMillis, easing = EaseStandard))
    }
}

/**
 * Whether the reader asked the system for less motion (web `prefers-reduced-motion`): Android's
 * Remove animations (the animator duration scale at 0), iOS Reduce Motion. Desktop: never.
 */
@Composable
expect fun rememberRaftReducedMotion(): Boolean
