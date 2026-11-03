package build.raft.app.shared

import build.raft.app.core.data.diagnostics.RaftPerformanceDiagnostics
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.os.Trace
import android.view.ActionMode
import android.view.View
import build.raft.app.RaftFloatingToolbarAnchorGuard
import android.util.AttributeSet
import android.widget.FrameLayout

internal class RaftTraceFrameLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

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

    // Every Compose page sits under this container: its text fields' floating toolbars (the
    // Cut / Copy / Paste bar) stay where they are while they close (RaftFloatingToolbarAnchorGuard).
    override fun startActionModeForChild(
            originalView: View?,
            callback: ActionMode.Callback?,
            type: Int
    ): ActionMode? =
            super.startActionModeForChild(
                    originalView,
                    callback?.let { RaftFloatingToolbarAnchorGuard.wrap(it, type) },
                    type
            )

    private fun reportHostTraversal(stage: String, startedAt: Long) {
        RaftPerformanceDiagnostics.recordHostTraversal(
                stage = stage,
                durationNanos = SystemClock.elapsedRealtimeNanos() - startedAt,
                width = width,
                height = height,
                childCount = childCount
        )
    }
}
