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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import build.raft.app.RaftIosHardwareKeyboardState
import build.raft.app.raftIosAppOnMacDensity
import build.raft.app.raftIosAppOnMacImeInsets
import build.raft.app.raftIosIgnoresKeyboardFrame
import build.raft.app.raftPlatformIsiOSAppOnMac
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectGetMaxX
import platform.CoreGraphics.CGRectGetMaxY
import platform.CoreGraphics.CGRectGetMinX
import platform.CoreGraphics.CGRectGetMinY
import platform.CoreGraphics.CGRectIsEmpty
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSValue
import platform.QuartzCore.CATransaction
import platform.UIKit.CGRectValue
import platform.UIKit.UIKeyboardAnimationCurveUserInfoKey
import platform.UIKit.UIKeyboardAnimationDurationUserInfoKey
import platform.UIKit.UIKeyboardDidChangeFrameNotification
import platform.UIKit.UIKeyboardFrameEndUserInfoKey
import platform.UIKit.UIKeyboardWillChangeFrameNotification
import platform.UIKit.UIView

/*
 * Single-source iOS keyboard tracking with UIKit's real keyboard curve (artin 11-03 / 11-27).
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
 * Matching Android's `AndroidImeAnimationTracker` (#5962), this drives `RaftHostImeInsets.imeBottomPx`
 * as the single authoritative IME height on iOS (`imeAnimationRunning` stays `false` permanently):
 * - No dual-source handoff back to CMP's `WindowInsets.ime` at slide completion (no 1-frame snap).
 * - Distinguishes `cancelled` vs `completed` so interrupting slides continue from the exact live
 *   pixel position instead of snapping to the cancelled target or CMP's wrong-curve value.
 * - Reuses a single persistent hidden probe view attached to the host view and flushes
 *   `CATransaction` before animating so `presentationLayer()` is populated on Frame 1 at 120 Hz.
 * - Handles `duration <= 0.0` (interactive drag-dismiss / instant frame changes) and
 *   `UIKeyboardDidChangeFrameNotification` directly, skips no-op probes when `fromPx == toPx`, and
 *   keeps one stable `RaftHostImeInsets` instance in `LocalRaftHostImeInsets` when hardware-keyboard
 *   suppression toggles (zero full-subtree recomposition).
 * Delete this file (and the expect) after moving to CMP 1.13.
 */

/** `UIViewAnimationOptions` for a `UIViewAnimationCurve` raw value: the curve sits in bits 16..19. */
internal fun raftKeyboardCurveAnimationOptions(curve: ULong): ULong = curve shl 16

/**
 * The part of the keyboard that covers the page's view, in points.
 *
 * Matches CMP's formula for bottom-docked keyboards while returning `0.0` for floating/undocked
 * iPad keyboards (whose bottom edge sits above the screen bottom) or keyboards that do not
 * horizontally intersect the host view (e.g. Split View / Stage Manager).
 */
internal fun raftKeyboardOverlapPoints(
    screenHeight: Double,
    keyboardEmpty: Boolean,
    keyboardMinY: Double,
    viewBottomInScreen: Double,
    keyboardMaxY: Double = screenHeight,
    keyboardIntersectsViewHorizontally: Boolean = true,
): Double {
    if (keyboardEmpty || !keyboardIntersectsViewHorizontally) return 0.0
    if (keyboardMaxY < screenHeight - 1.0) return 0.0
    val keyboardHeight = max(0.0, screenHeight - keyboardMinY)
    return max(0.0, keyboardHeight - (screenHeight - viewBottomInScreen))
}

/** The keyboard inset between [fromPx] and [toPx] at curve [progress] (0..1). */
internal fun raftKeyboardInsetAt(fromPx: Int, toPx: Int, progress: Double): Int =
    (fromPx + (toPx - fromPx) * progress.coerceIn(0.0, 1.0)).roundToInt()

/**
 * Pure slide lifecycle state: strictly separates [cancelled] (interrupted by a newer keyboard
 * notification or teardown) from [completed] (finished normally), so an interrupted slide can
 * never snap `imeBottomPx` to its cancelled target or poison the next slide's start pixel.
 */
internal class RaftIosKeyboardSlideState(
    val fromPx: Int,
    val toPx: Int,
    val targetPoints: Double,
    private val readPresentationProgress: () -> Double?,
) {
    var cancelled: Boolean = false
        private set

    var completed: Boolean = false
        private set

    val isActive: Boolean
        get() = !cancelled && !completed

    /**
     * Returns the interpolated IME height for this frame, or `null` when cancelled or when
     * CoreAnimation has not yet populated a presentation layer (preserving the last live pixel).
     */
    fun sampleInsetPx(): Int? {
        if (cancelled) return null
        if (completed) return toPx
        val progress = readPresentationProgress() ?: return null
        return raftKeyboardInsetAt(fromPx, toPx, progress)
    }

    /** Final pixel to write after the frame loop exits, or `null` when interrupted/cancelled. */
    fun finalSettledInsetPx(): Int? = if (completed && !cancelled) toPx else null

    fun markCompleted() {
        if (!cancelled) {
            completed = true
        }
    }

    fun cancel() {
        cancelled = true
    }
}

/**
 * Tracks `UIKeyboardWillChangeFrameNotification` and `UIKeyboardDidChangeFrameNotification` for
 * [view], writing directly into the single authoritative [insets] instance.
 */
private class RaftKeyboardSlideTracker(
    private val view: UIView,
    private val insets: RaftHostImeInsets,
) {
    var slide: RaftIosKeyboardSlideState? by mutableStateOf(null)
        private set

    /** The latest keyboard frame's overlap of the view, in points, animated or not (0: none). */
    var overlapPoints: Double by mutableStateOf(0.0)
        private set

    var hasObservedNotification: Boolean = false
        private set

    private var willChangeObserver: Any? = null
    private var didChangeObserver: Any? = null
    private var probeView: UIView? = null
    private var slideGeneration: Long = 0L

    @OptIn(ExperimentalForeignApi::class)
    fun start() {
        if (probeView == null) {
            val probe = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0))
            probe.userInteractionEnabled = false
            view.addSubview(probe)
            probeView = probe
        }
        willChangeObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIKeyboardWillChangeFrameNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { notification ->
                val info = notification?.userInfo ?: return@addObserverForName
                val endFrame = info[UIKeyboardFrameEndUserInfoKey] as? NSValue ?: return@addObserverForName
                // Read as CMP's KeyboardVisibilityListener reads them.
                val duration = (info[UIKeyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?: 0.0
                val curve = (info[UIKeyboardAnimationCurveUserInfoKey] as? NSNumber)?.unsignedIntegerValue() ?: 0uL
                onKeyboardWillChangeFrame(endFrame, duration, curve)
            }
        didChangeObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIKeyboardDidChangeFrameNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { notification ->
                val info = notification?.userInfo ?: return@addObserverForName
                val endFrame = info[UIKeyboardFrameEndUserInfoKey] as? NSValue ?: return@addObserverForName
                onKeyboardDidChangeFrame(endFrame)
            }
    }

    fun stop() {
        willChangeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        willChangeObserver = null
        didChangeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        didChangeObserver = null
        cancelActiveSlide()
        probeView?.let { probe ->
            UIView.performWithoutAnimation {
                probe.layer.removeAllAnimations()
                probe.removeFromSuperview()
            }
        }
        probeView = null
    }

    fun seedInitialOverlapIfUnobserved(initialOverlapPoints: Double, initialInsetPx: Int) {
        if (hasObservedNotification) return
        overlapPoints = initialOverlapPoints.coerceAtLeast(0.0)
        val suppressed =
            raftIosIgnoresKeyboardFrame(
                hardwareKeyboard = RaftIosHardwareKeyboardState.connected,
                overlapPoints = overlapPoints,
            )
        insets.imeBottomPx = if (suppressed) 0 else initialInsetPx.coerceAtLeast(0)
    }

    fun clearCompletedSlide(completedSlide: RaftIosKeyboardSlideState) {
        if (slide === completedSlide && !completedSlide.isActive) {
            slide = null
        }
    }

    fun cancelActiveSlide() {
        slideGeneration++
        slide?.cancel()
        slide = null
        probeView?.let { probe ->
            UIView.performWithoutAnimation {
                probe.layer.removeAllAnimations()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun computeOverlapPoints(endFrame: NSValue): Double? {
        val screen = view.window?.screen ?: return null
        val keyboard = endFrame.CGRectValue()
        val viewTopLeftInScreen =
            screen.coordinateSpace
                .convertPoint(
                    point = CGPointMake(0.0, 0.0),
                    fromCoordinateSpace = view.coordinateSpace,
                )
        val viewBottomRightInScreen =
            screen.coordinateSpace
                .convertPoint(
                    point = CGPointMake(
                        view.bounds.useContents { size.width },
                        view.bounds.useContents { size.height },
                    ),
                    fromCoordinateSpace = view.coordinateSpace,
                )
        val viewMinX = viewTopLeftInScreen.useContents { x }
        val viewMaxX = viewBottomRightInScreen.useContents { x }
        val viewBottomInScreen = viewBottomRightInScreen.useContents { y }
        val intersectsHorizontally =
            CGRectGetMaxX(keyboard) > viewMinX && CGRectGetMinX(keyboard) < viewMaxX
        return raftKeyboardOverlapPoints(
            screenHeight = screen.bounds.useContents { size.height },
            keyboardEmpty = CGRectIsEmpty(keyboard),
            keyboardMinY = CGRectGetMinY(keyboard),
            viewBottomInScreen = viewBottomInScreen,
            keyboardMaxY = CGRectGetMaxY(keyboard),
            keyboardIntersectsViewHorizontally = intersectsHorizontally,
        )
    }

    private fun effectiveTargetPx(targetPoints: Double): Int {
        val suppressed =
            raftIosIgnoresKeyboardFrame(
                hardwareKeyboard = RaftIosHardwareKeyboardState.connected,
                overlapPoints = targetPoints,
            )
        if (suppressed) return 0
        val scale = view.window?.screen?.scale ?: view.contentScaleFactor
        return (targetPoints * scale).roundToInt().coerceAtLeast(0)
    }

    private fun writeSettledInsetPx(px: Int) {
        val clamped = px.coerceAtLeast(0)
        if (insets.imeBottomPx != clamped) {
            insets.imeBottomPx = clamped
            Snapshot.sendApplyNotifications()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun onKeyboardWillChangeFrame(endFrame: NSValue, duration: Double, curve: ULong) {
        val target = computeOverlapPoints(endFrame) ?: return
        hasObservedNotification = true
        overlapPoints = target
        val toPx = effectiveTargetPx(target)
        val fromPx = insets.imeBottomPx.coerceAtLeast(0)

        cancelActiveSlide()

        // Instant/interactive frame change or redundant notification at the exact current height:
        // update settled height directly and skip spawning a redundant 120 Hz frame loop.
        if (duration <= 0.0 || toPx == fromPx) {
            writeSettledInsetPx(toPx)
            return
        }

        val probe =
            probeView ?: UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).also { created ->
                created.userInteractionEnabled = false
                view.addSubview(created)
                probeView = created
            }
        val generation = ++slideGeneration
        UIView.performWithoutAnimation {
            probe.layer.removeAllAnimations()
            probe.setFrame(CGRectMake(0.0, 0.0, 0.0, 0.0))
        }
        CATransaction.flush()

        val next =
            RaftIosKeyboardSlideState(
                fromPx = fromPx,
                toPx = toPx,
                targetPoints = target,
                readPresentationProgress = {
                    probe.layer.presentationLayer()?.frame?.useContents { size.height }?.div(PROBE_HEIGHT)
                },
            )
        UIView.animateWithDuration(
            duration = duration,
            delay = 0.0,
            options = raftKeyboardCurveAnimationOptions(curve),
            animations = { probe.setFrame(CGRectMake(0.0, 0.0, 0.0, PROBE_HEIGHT)) },
            completion = { _ ->
                if (generation == slideGeneration && !next.cancelled) {
                    next.markCompleted()
                }
            },
        )
        slide = next
    }

    private fun onKeyboardDidChangeFrame(endFrame: NSValue) {
        val target = computeOverlapPoints(endFrame) ?: return
        val toPx = effectiveTargetPx(target)
        val active = slide
        if (active != null && active.isActive) {
            // Only settle the active slide if this DidChangeFrame matches its target; an
            // earlier interrupted animation can deliver a stale DidChangeFrame after Slide B
            // has already started, which must never abort Slide B.
            if (active.toPx == toPx) {
                hasObservedNotification = true
                overlapPoints = target
                active.markCompleted()
            }
            return
        }
        hasObservedNotification = true
        overlapPoints = target
        if (active != null) {
            slide = null
        }
        writeSettledInsetPx(toPx)
    }

    companion object {
        const val PROBE_HEIGHT = 1000.0
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
    ) {
        RaftToastHostImeBottomInset()
        content()
    }
}

@Composable
private fun ProvideRaftIosKeyboardInsets(content: @Composable () -> Unit) {
    val view = LocalUIViewController.current.view
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Single authoritative IME inset source on iOS (imeAnimationRunning stays false permanently).
    val insets = remember { RaftHostImeInsets() }
    val tracker = remember(view, insets) { RaftKeyboardSlideTracker(view, insets) }
    DisposableEffect(tracker) {
        tracker.start()
        onDispose { tracker.stop() }
    }
    // Seed initial height and overlap once if a page mounts while the keyboard is already settled.
    LaunchedEffect(tracker) {
        if (!tracker.hasObservedNotification) {
            val initialPx = ime.getBottom(density).coerceAtLeast(0)
            val initialPoints = if (density.density > 0f) initialPx / density.density.toDouble() else 0.0
            tracker.seedInitialOverlapIfUnobserved(initialPoints, initialPx)
        }
    }
    val slide = tracker.slide
    LaunchedEffect(slide) {
        val active = slide ?: return@LaunchedEffect
        // Sampled inside the frame callback, so this frame's layout already uses it.
        while (active.isActive) {
            withFrameNanos {
                val sampled = active.sampleInsetPx()
                if (sampled != null) {
                    insets.imeBottomPx = sampled
                }
            }
        }
        active.finalSettledInsetPx()?.let { settledPx ->
            insets.imeBottomPx = settledPx
        }
        tracker.clearCompletedSlide(active)
    }
    // A hardware keyboard's shortcuts bar (RaftIosHardwareKeyboard.kt): zero the same insets
    // instance instead of swapping LocalRaftHostImeInsets (staticCompositionLocalOf) to avoid
    // recomposing the entire page subtree on every focus/blur.
    val ignoresFrame =
        raftIosIgnoresKeyboardFrame(
            hardwareKeyboard = RaftIosHardwareKeyboardState.connected,
            overlapPoints = tracker.overlapPoints,
        )
    LaunchedEffect(ignoresFrame, tracker.overlapPoints, density) {
        val active = tracker.slide
        if (ignoresFrame) {
            if (active == null || !active.isActive || active.toPx != 0) {
                tracker.cancelActiveSlide()
                insets.imeBottomPx = 0
            }
        } else if (active?.isActive != true && (tracker.hasObservedNotification || tracker.overlapPoints > 0.0)) {
            val scale = view.window?.screen?.scale ?: density.density.toDouble()
            insets.imeBottomPx = (tracker.overlapPoints * scale).roundToInt().coerceAtLeast(0)
        }
    }
    CompositionLocalProvider(
        LocalRaftHostImeInsets provides insets,
    ) {
        RaftToastHostImeBottomInset()
        content()
    }
}
