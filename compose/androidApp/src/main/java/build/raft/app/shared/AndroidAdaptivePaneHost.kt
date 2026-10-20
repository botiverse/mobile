package build.raft.app.shared

import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import build.raft.app.core.data.diagnostics.RaftAppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class AndroidAdaptivePanePosture(val wireName: String) {
    Unknown("unknown"),
    Flat("flat"),
    HalfFolded("half_folded"),
}

internal data class AndroidAdaptivePaneSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val posture: AndroidAdaptivePanePosture,
) {
    fun isValid(): Boolean = widthPx > 0 && heightPx > 0 && density > 0f

    fun toPagerEventData(): Map<String, Any> =
        mapOf(
            "widthPx" to widthPx,
            "heightPx" to heightPx,
            "density" to density,
            "posture" to posture.wireName,
        )
}

internal class AndroidAdaptivePanePublisher(
    private val sendEvent: (event: String, data: Map<String, Any>) -> Unit,
) {
    private var latest: AndroidAdaptivePaneSnapshot? = null

    fun publish(snapshot: AndroidAdaptivePaneSnapshot): Boolean {
        if (!snapshot.isValid() || snapshot == latest) return false
        latest = snapshot
        sendEvent(EventName, snapshot.toPagerEventData())
        return true
    }

    companion object {
        const val EventName = "slockAdaptivePaneChanged"
    }
}

internal fun androidAdaptivePanePosture(
    foldingStates: Iterable<FoldingFeature.State>,
): AndroidAdaptivePanePosture =
    when {
        foldingStates.any { it == FoldingFeature.State.HALF_OPENED } ->
            AndroidAdaptivePanePosture.HalfFolded
        else -> AndroidAdaptivePanePosture.Flat
    }

/**
 * Bridges Android window facts into the root Kuikly pager event contract. WindowInfoTracker owns
 * fold posture while a decor layout listener supplies resize triggers for freeform and split-screen
 * windows. The shared reducer remains the only owner of Compact vs DualPane policy.
 */
internal class AndroidAdaptivePaneHost(
    private val activity: ComponentActivity,
    sendEvent: (event: String, data: Map<String, Any>) -> Unit,
    private val windowInfoTracker: WindowInfoTracker = WindowInfoTracker.getOrCreate(activity),
    private val windowMetricsCalculator: WindowMetricsCalculator = WindowMetricsCalculator.getOrCreate(),
) {
    private val publisher = AndroidAdaptivePanePublisher(sendEvent)
    private var posture = AndroidAdaptivePanePosture.Unknown
    private var observerJob: Job? = null
    private var started = false
    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (
                right - left != oldRight - oldLeft ||
                    bottom - top != oldBottom - oldTop
            ) {
                publishCurrentWindow()
            }
        }

    fun start() {
        if (started) return
        started = true
        activity.window.decorView.addOnLayoutChangeListener(layoutChangeListener)
        activity.window.decorView.post { publishCurrentWindow() }
        observerJob =
            activity.lifecycleScope.launch {
                activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    windowInfoTracker
                        .windowLayoutInfo(activity)
                        .catch { throwable ->
                            posture = AndroidAdaptivePanePosture.Unknown
                            publishCurrentWindow()
                            RaftAppLog.w(
                                "SlockAdaptivePane",
                                "android_window_layout_observer_failed " +
                                    "type=${throwable.javaClass.simpleName}"
                            )
                        }
                        .collect { layoutInfo ->
                            posture =
                                androidAdaptivePanePosture(
                                    layoutInfo.displayFeatures
                                        .filterIsInstance<FoldingFeature>()
                                        .map(FoldingFeature::state)
                                )
                            publishCurrentWindow()
                        }
                }
            }
    }

    fun stop() {
        if (!started) return
        started = false
        activity.window.decorView.removeOnLayoutChangeListener(layoutChangeListener)
        observerJob?.cancel()
        observerJob = null
    }

    private fun publishCurrentWindow() {
        if (!started) return
        val bounds: Rect =
            runCatching { windowMetricsCalculator.computeCurrentWindowMetrics(activity).bounds }
                .getOrElse { throwable ->
                    RaftAppLog.w(
                        "SlockAdaptivePane",
                        "android_window_metrics_failed type=${throwable.javaClass.simpleName}"
                    )
                    return
                }
        val snapshot =
            AndroidAdaptivePaneSnapshot(
                widthPx = bounds.width(),
                heightPx = bounds.height(),
                density = activity.resources.displayMetrics.density,
                posture = posture,
            )
        if (publisher.publish(snapshot)) {
            RaftAppLog.d(
                "SlockAdaptivePane",
                "android_window_facts widthPx=${snapshot.widthPx} heightPx=${snapshot.heightPx} " +
                    "density=${snapshot.density} posture=${snapshot.posture.wireName}"
            )
        }
    }
}
