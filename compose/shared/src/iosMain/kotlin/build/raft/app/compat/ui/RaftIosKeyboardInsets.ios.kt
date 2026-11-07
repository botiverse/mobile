package build.raft.app.compat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import build.raft.app.raftIosAppOnMacDensity
import build.raft.app.raftIosAppOnMacImeInsets
import build.raft.app.raftPlatformIsiOSAppOnMac
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectGetMinY
import platform.CoreGraphics.CGRectIsEmpty
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSValue
import platform.UIKit.CGRectValue
import platform.UIKit.UIKeyboardAnimationCurveUserInfoKey
import platform.UIKit.UIKeyboardAnimationDurationUserInfoKey
import platform.UIKit.UIKeyboardFrameEndUserInfoKey
import platform.UIKit.UIKeyboardWillChangeFrameNotification
import platform.UIKit.UIView

/*
 * The keyboard's slide, followed with the keyboard's own curve (artin 11-03: the keyboard rise in
 * a conversation does not feel 120 Hz).
 *
 * CMP 1.12.2 tracks the keyboard by animating a hidden view with the keyboard notification's
 * duration and curve and sampling its presentation layer on every display link frame
 * (ComposeSceneKeyboardOffsetManager). It passes `UIKeyboardAnimationCurveUserInfoKey` (a
 * `UIViewAnimationCurve`, 7 for the keyboard) as `UIViewAnimationOptions` without shifting it into
 * the curve bits, so the curve bits are 0 (ease-in-out) and the low bits turn on unrelated options.
 * The system keyboard leaves fast and decelerates; ease-in-out starts slowly. Early in the slide
 * the composer sits a third of the keyboard's height below the keyboard's top edge, then catches
 * up: every frame lands, but not where the keyboard is, so the motion reads as stuttering.
 * Upstream fix: CMP-10448 (compose-multiplatform-core #3183), released in 1.13.0-beta01 only.
 *
 * While the keyboard animates, this follows it with the same hidden-view technique and the shifted
 * curve, sampled on Compose's own frame clock (the same 120 Hz display link that renders), and
 * reports the value through [LocalRaftHostImeInsets]. Once the slide ends it hands back to
 * `WindowInsets.ime` (`imeAnimationRunning = true`), so the settled keyboard stays CMP's value.
 * Delete this file (and the expect) after moving to CMP 1.13.
 */

/** `UIViewAnimationOptions` for a `UIViewAnimationCurve` raw value: the curve sits in bits 16..19. */
internal fun raftKeyboardCurveAnimationOptions(curve: ULong): ULong = curve shl 16

/**
 * The part of the keyboard that covers the page's view, in points: CMP's formula
 * (ComposeSceneKeyboardOffsetManager.adjustViewBounds), so the value this hands back on settling
 * is the one CMP lands on.
 */
internal fun raftKeyboardOverlapPoints(
    screenHeight: Double,
    keyboardEmpty: Boolean,
    keyboardMinY: Double,
    viewBottomInScreen: Double,
): Double {
    val keyboardHeight = if (keyboardEmpty) 0.0 else max(0.0, screenHeight - keyboardMinY)
    return max(0.0, keyboardHeight - (screenHeight - viewBottomInScreen))
}

/** The keyboard inset between [fromPx] and [toPx] at curve [progress] (0..1). */
internal fun raftKeyboardInsetAt(fromPx: Int, toPx: Int, progress: Double): Int =
    (fromPx + (toPx - fromPx) * progress.coerceIn(0.0, 1.0)).roundToInt()

/** One keyboard slide: the target overlap and the curve's progress, read from a hidden view. */
private class RaftKeyboardSlide(val targetPoints: Double, private val probe: UIView) {
    var finished = false

    @OptIn(ExperimentalForeignApi::class)
    fun progress(): Double {
        if (finished) return 1.0
        val layer = probe.layer.presentationLayer() ?: return 0.0
        return layer.frame.useContents { size.height } / PROBE_HEIGHT
    }

    fun cancel() {
        UIView.performWithoutAnimation {
            probe.layer.removeAllAnimations()
            probe.removeFromSuperview()
        }
        finished = true
    }

    companion object {
        const val PROBE_HEIGHT = 1000.0
    }
}

/** Starts a [RaftKeyboardSlide] on every keyboard frame change while [view] is in a window. */
private class RaftKeyboardSlideTracker(private val view: UIView) {
    var slide: RaftKeyboardSlide? by mutableStateOf(null)
        private set

    private var observer: Any? = null

    fun start() {
        observer =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIKeyboardWillChangeFrameNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { notification ->
                val info = notification?.userInfo ?: return@addObserverForName
                val endFrame = info[UIKeyboardFrameEndUserInfoKey] as? NSValue ?: return@addObserverForName
                // Read as CMP's KeyboardVisibilityListener reads them.
                val duration = info[UIKeyboardAnimationDurationUserInfoKey] as? Double ?: 0.0
                val curve = (info[UIKeyboardAnimationCurveUserInfoKey] as? NSNumber)?.unsignedIntegerValue() ?: 0uL
                onKeyboardWillChangeFrame(endFrame, duration, curve)
            }
    }

    fun stop() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
        slide?.cancel()
        slide = null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun onKeyboardWillChangeFrame(endFrame: NSValue, duration: Double, curve: ULong) {
        slide?.cancel()
        slide = null
        val screen = view.window?.screen ?: return
        // No slide (a keyboard that appears without animation): CMP sets the value at once.
        if (duration <= 0.0) return
        val keyboard = endFrame.CGRectValue()
        val viewBottomInScreen =
            screen.coordinateSpace
                .convertPoint(
                    point = CGPointMake(0.0, view.bounds.useContents { size.height }),
                    fromCoordinateSpace = view.coordinateSpace,
                ).useContents { y }
        val target =
            raftKeyboardOverlapPoints(
                screenHeight = screen.bounds.useContents { size.height },
                keyboardEmpty = CGRectIsEmpty(keyboard),
                keyboardMinY = CGRectGetMinY(keyboard),
                viewBottomInScreen = viewBottomInScreen,
            )
        val probe = UIView()
        probe.userInteractionEnabled = false
        view.addSubview(probe)
        val next = RaftKeyboardSlide(target, probe)
        UIView.animateWithDuration(
            duration = duration,
            delay = 0.0,
            options = raftKeyboardCurveAnimationOptions(curve),
            animations = { probe.setFrame(CGRectMake(0.0, 0.0, 0.0, RaftKeyboardSlide.PROBE_HEIGHT)) },
            completion = { _ ->
                probe.removeFromSuperview()
                next.finished = true
            },
        )
        slide = next
    }
}

@Composable
internal actual fun ProvideRaftPlatformImeInsets(content: @Composable () -> Unit) {
    if (raftPlatformIsiOSAppOnMac()) ProvideRaftIosAppOnMacPage(content) else ProvideRaftIosKeyboardInsets(content)
}

/**
 * The iPad app on a Mac (see RaftIosAppOnMac.kt): no software keyboard, so a 0 keyboard inset, and
 * the desktop's text size, by laying the page out in Mac points.
 */
@Composable
private fun ProvideRaftIosAppOnMacPage(content: @Composable () -> Unit) {
    val insets = remember { raftIosAppOnMacImeInsets() }
    CompositionLocalProvider(
        LocalDensity provides raftIosAppOnMacDensity(LocalDensity.current, onMac = true),
        LocalRaftHostImeInsets provides insets,
        content = content,
    )
}

@Composable
private fun ProvideRaftIosKeyboardInsets(content: @Composable () -> Unit) {
    val view = LocalUIViewController.current.view
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val insets = remember { RaftHostImeInsets().apply { imeAnimationRunning = true } }
    val tracker = remember(view) { RaftKeyboardSlideTracker(view) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    val slide = tracker.slide
    LaunchedEffect(slide) {
        if (slide == null) {
            insets.imeAnimationRunning = true
            return@LaunchedEffect
        }
        // Platform insets are pixels of the view (CMP: points x contentScaleFactor).
        val toPx = (slide.targetPoints * view.contentScaleFactor).roundToInt()
        // Start where the composer is now: CMP's settled value, or the slide this one interrupts.
        val fromPx = if (insets.imeAnimationRunning) ime.getBottom(density) else insets.imeBottomPx
        insets.imeBottomPx = fromPx
        insets.imeAnimationRunning = false
        // Sampled inside the frame callback, so this frame's layout already uses it.
        while (!slide.finished) {
            withFrameNanos { insets.imeBottomPx = raftKeyboardInsetAt(fromPx, toPx, slide.progress()) }
        }
        insets.imeBottomPx = toPx
        // Settled: CMP's own value (same formula) takes over again.
        insets.imeAnimationRunning = true
    }
    CompositionLocalProvider(LocalRaftHostImeInsets provides insets, content = content)
}
