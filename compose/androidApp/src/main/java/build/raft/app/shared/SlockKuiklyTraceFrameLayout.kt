package build.raft.app.shared

import build.raft.app.core.data.diagnostics.SlockPerformanceDiagnostics
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.os.Trace
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.widget.FrameLayout

internal class SlockKuiklyTraceFrameLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // task #37/#39: per-frame list geometry while scrolling. A pre-draw listener fires on every
    // frame the window draws; this container's own dispatchDraw does not re-run when only a
    // hardware-accelerated child list redraws, so it cannot drive the probe.
    private val flingFrameProbe =
        ViewTreeObserver.OnPreDrawListener {
            SlockFlingFrameProbe.onPreDraw(this)
            true
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(flingFrameProbe)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(flingFrameProbe)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("slock_kuikly_container_measure")
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } finally {
            Trace.endSection()
            reportHostTraversal("measure", startedAt)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("slock_kuikly_container_layout")
        try {
            super.onLayout(changed, left, top, right, bottom)
        } finally {
            Trace.endSection()
            reportHostTraversal("layout", startedAt)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("slock_kuikly_container_dispatchDraw")
        try {
            super.dispatchDraw(canvas)
        } finally {
            Trace.endSection()
            reportHostTraversal("dispatchDraw", startedAt)
        }
    }

    private fun reportHostTraversal(stage: String, startedAt: Long) {
        SlockPerformanceDiagnostics.recordHostTraversal(
                stage = stage,
                durationNanos = SystemClock.elapsedRealtimeNanos() - startedAt,
                width = width,
                height = height,
                childCount = childCount
        )
    }
}
