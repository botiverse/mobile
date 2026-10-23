@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.math.PI
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.QuartzCore.CAShapeLayer
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIActivityIndicatorViewStyle
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UIView

/**
 * The system spinner in the pull-to-refresh gap: a `UIActivityIndicatorView` (`.medium`, its
 * default colour, which follows the window's light/dark style) inside a clipping container sized
 * by Compose to the gap. The container is placed as an overlay and never takes touches, so the
 * list keeps every gesture; it never covers content because the gap it fills is empty.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun RaftCupertinoActivityIndicator(
    revealedTicks: Int,
    animating: Boolean,
    modifier: Modifier,
) {
    UIKitView(
        factory = { RaftIosRefreshIndicatorView() },
        modifier = modifier,
        update = { view -> view.render(revealedTicks, animating) },
        onRelease = { view -> view.render(revealedTicks = 0, animating = false) },
        properties =
            UIKitInteropProperties(
                interactionMode = null,
                isNativeAccessibilityEnabled = false,
                placedAsOverlay = true,
            ),
    )
}

/** Clips the spinner to the gap and keeps it centred in it. */
internal class RaftIosRefreshIndicatorView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val spinner = UIActivityIndicatorView(activityIndicatorStyle = UIActivityIndicatorViewStyle.UIActivityIndicatorViewStyleMedium)
    private val tickMask = CAShapeLayer()
    private var spinning = false
    private var maskedTicks = -1

    init {
        setUserInteractionEnabled(false)
        setClipsToBounds(true)
        backgroundColor = UIColor.clearColor
        spinner.setHidesWhenStopped(false)
        spinner.setUserInteractionEnabled(false)
        spinner.setHidden(true)
        addSubview(spinner)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        bounds.useContents { spinner.setCenter(CGPointMake(size.width / 2.0, size.height / 2.0)) }
    }

    fun render(revealedTicks: Int, animating: Boolean) {
        if (animating) {
            setTicks(RaftActivityIndicatorTickCount)
            if (!spinning) {
                spinning = true
                spinner.startAnimating()
            }
        } else {
            if (spinning) {
                spinning = false
                spinner.stopAnimating()
            }
            setTicks(revealedTicks.coerceIn(0, RaftActivityIndicatorTickCount))
        }
    }

    /**
     * Shows the first [ticks] spokes clockwise from 12 o'clock: a pie-wedge mask whose edges fall
     * halfway between spokes, so every spoke appears whole.
     */
    private fun setTicks(ticks: Int) {
        if (ticks == maskedTicks) return
        maskedTicks = ticks
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        spinner.setHidden(ticks == 0)
        if (ticks >= RaftActivityIndicatorTickCount) {
            spinner.layer.setMask(null)
        } else if (ticks > 0) {
            spinner.bounds.useContents {
                val cx = size.width / 2.0
                val cy = size.height / 2.0
                val spoke = 2.0 * PI / RaftActivityIndicatorTickCount
                val start = -PI / 2.0 - spoke / 2.0
                val wedge = UIBezierPath.bezierPath()
                wedge.moveToPoint(CGPointMake(cx, cy))
                wedge.addArcWithCenter(
                    center = CGPointMake(cx, cy),
                    radius = size.width + size.height,
                    startAngle = start,
                    endAngle = start + spoke * ticks,
                    clockwise = true,
                )
                wedge.closePath()
                tickMask.setFrame(CGRectMake(0.0, 0.0, size.width, size.height))
                tickMask.setPath(wedge.CGPath)
            }
            spinner.layer.setMask(tickMask)
        }
        CATransaction.commit()
    }
}

@Composable
internal actual fun rememberRaftCupertinoRefreshHaptic(): RaftCupertinoRefreshHaptic =
    remember { RaftIosCupertinoRefreshHaptic() }

private class RaftIosCupertinoRefreshHaptic : RaftCupertinoRefreshHaptic {
    private val generator = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)

    override fun prepare() {
        generator.prepare()
    }

    override fun trigger() {
        generator.impactOccurred()
    }
}
