package build.raft.app.shared

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import build.raft.app.core.data.diagnostics.SlockAndroidLocalDiagnostics
import build.raft.app.core.data.diagnostics.SlockPerformanceTraceRing
import java.lang.ref.WeakReference

/**
 * Per-frame geometry of every Kuikly list while it scrolls (task #37/#39: large white areas on a
 * fast fling). For each frame it records where the native list is (content offset, range, and the
 * overscroll translation) and how much of the list's viewport is actually covered by placed row
 * views. That separates the three explanations still open:
 * - rows were not placed in time            -> uncovered viewport (gap > 0) during the fling;
 * - rows placed but not drawn               -> gap == 0 while the screen shows white;
 * - the native scroll ran past the content  -> offset outside [0, max] or overscroll != 0.
 *
 * Kuikly's Android list is a RecyclerView whose single child is a large content view: its `top` is
 * minus the scroll offset and its `translationY` is the overscroll; row views are its children.
 *
 * Alpha-only (gated by the performance trace switch the alpha build type forces on) and budgeted:
 * at most [MaxFramesPerFling] lines per fling and [MaxFramesPerProcess] per process, written to the
 * persistent diagnostics JSONL so a later feedback submission still carries them.
 */
internal object SlockFlingFrameProbe {
    private const val Tag = "thread_fling_frame"
    private const val MaxFramesPerFling = 90
    private const val MaxFramesPerProcess = 600
    private const val IdleFramesToEndFling = 8
    private const val RescanIntervalNanos = 1_000_000_000L

    private class Tracked(view: RecyclerView) {
        val ref = WeakReference(view)
        var lastOffset = Int.MIN_VALUE
        var lastTranslation = Float.NaN
        var idleFrames = 0
        var framesThisFling = 0
        var flingId = 0
    }

    private val tracked = mutableListOf<Tracked>()
    private var lastScanNanos = 0L
    private var framesThisProcess = 0
    private var nextFlingId = 1
    private val location = IntArray(2)

    fun onPreDraw(root: View) {
        if (!SlockPerformanceTraceRing.isEnabled()) return
        if (framesThisProcess >= MaxFramesPerProcess) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (tracked.isEmpty() || now - lastScanNanos > RescanIntervalNanos) {
            rescan(root)
            lastScanNanos = now
        }
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val list = entry.ref.get()
            if (list == null || !list.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            sample(entry, list, now)
            if (framesThisProcess >= MaxFramesPerProcess) return
        }
    }

    private fun rescan(root: View) {
        val known = tracked.mapNotNull { it.ref.get() }.toSet()
        collectLists(root) { list ->
            if (list !in known) tracked += Tracked(list)
        }
    }

    private fun collectLists(view: View, onList: (RecyclerView) -> Unit) {
        if (view is RecyclerView) {
            onList(view)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectLists(view.getChildAt(index), onList)
            }
        }
    }

    private fun sample(entry: Tracked, list: RecyclerView, now: Long) {
        val content = list.getChildAt(0) ?: return
        val translation = content.translationY
        val offset = -content.top
        val moving =
            list.scrollState != RecyclerView.SCROLL_STATE_IDLE ||
                (entry.lastOffset != Int.MIN_VALUE && offset != entry.lastOffset) ||
                (!entry.lastTranslation.isNaN() && translation != entry.lastTranslation)
        entry.lastOffset = offset
        entry.lastTranslation = translation
        if (!moving) {
            entry.idleFrames += 1
            if (entry.idleFrames >= IdleFramesToEndFling) entry.framesThisFling = 0
            return
        }
        if (entry.framesThisFling == 0) entry.flingId = nextFlingId++
        entry.idleFrames = 0
        if (entry.framesThisFling >= MaxFramesPerFling) return
        entry.framesThisFling += 1
        framesThisProcess += 1

        val viewportHeight = list.height
        val maxOffset = (content.height - viewportHeight).coerceAtLeast(0)
        // Viewport in content coordinates; the overscroll translation moves the content, not the
        // viewport, so subtract it.
        val viewportTop = offset - translation.toInt()
        val viewportBottom = viewportTop + viewportHeight
        val coverage = coverage(content, viewportTop, viewportBottom)
        list.getLocationOnScreen(location)

        SlockAndroidLocalDiagnostics.recordLog(
            level = "info",
            tag = Tag,
            message =
                "thread_fling_frame t=${now / 1_000_000} fling=${entry.flingId} " +
                    "frame=${entry.framesThisFling} list=${System.identityHashCode(list).toString(16)} " +
                    "listClass=${list.javaClass.simpleName} contentClass=${content.javaClass.simpleName} " +
                    "screenX=${location[0]} screenY=${location[1]} w=${list.width} h=$viewportHeight " +
                    "state=${list.scrollState} offset=$offset max=$maxOffset overscroll=$translation " +
                    "outOfRange=${offset < 0 || offset > maxOffset} contentH=${content.height} " +
                    "rows=${coverage.rows} rowsInView=${coverage.rowsInView} " +
                    "covered=${coverage.covered} gap=${viewportHeight - coverage.covered} " +
                    "gapMax=${coverage.largestGap} gapMaxAt=${coverage.largestGapAt}"
        )
    }

    /** Union of the content's visible children over [top, bottom), in content coordinates. */
    private fun coverage(content: View, top: Int, bottom: Int): FlingFrameCoverage {
        val group = content as? ViewGroup ?: return flingFrameCoverage(emptyList(), 0, top, bottom)
        val spans = ArrayList<IntArray>(group.childCount)
        for (index in 0 until group.childCount) {
            val row = group.getChildAt(index)
            if (row.visibility != View.VISIBLE || row.alpha <= 0f || row.height <= 0) continue
            val rowTop = row.top + row.translationY.toInt()
            spans += intArrayOf(rowTop, rowTop + row.height)
        }
        return flingFrameCoverage(spans, group.childCount, top, bottom)
    }
}

internal class FlingFrameCoverage(
    val rows: Int,
    val rowsInView: Int,
    val covered: Int,
    val largestGap: Int,
    /** Start of the largest gap, relative to the viewport top; -1 when fully covered. */
    val largestGapAt: Int
)

/**
 * How much of the viewport [top, bottom) the row spans (each `[rowTop, rowBottom)`, any order,
 * possibly overlapping) cover, and where the largest uncovered stretch starts.
 */
internal fun flingFrameCoverage(spans: List<IntArray>, rows: Int, top: Int, bottom: Int): FlingFrameCoverage {
    val visible =
        spans
            .map { intArrayOf(maxOf(it[0], top), minOf(it[1], bottom)) }
            .filter { it[1] > it[0] }
            .sortedBy { it[0] }
    var covered = 0
    var cursor = top
    var largestGap = 0
    var largestGapAt = -1
    for (span in visible) {
        if (span[0] > cursor) {
            val gap = span[0] - cursor
            if (gap > largestGap) {
                largestGap = gap
                largestGapAt = cursor - top
            }
        }
        if (span[1] > cursor) {
            covered += span[1] - maxOf(span[0], cursor)
            cursor = span[1]
        }
    }
    if (bottom > cursor) {
        val gap = bottom - cursor
        if (gap > largestGap) {
            largestGap = gap
            largestGapAt = cursor - top
        }
    }
    return FlingFrameCoverage(rows, visible.size, covered, largestGap, largestGapAt)
}
