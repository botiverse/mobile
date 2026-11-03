package build.raft.app.visual

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Full-length ("long screenshot") capture, opt-in per case through slock's
 * sharedCases.json `capture.fullLength` (the workflow passes the flagged ids as
 * the `visualFullLengthCases` instrumentation argument).
 *
 * Same contract as the React provider (react-provider.spec.ts
 * expandToFullLength): find the largest visible vertical scroller that still
 * has content below the fold, grow the display by exactly that overflow, repeat
 * until nothing overflows or the cap is hit, then capture with the case's
 * normal crop extended by the growth. Fixed chrome keeps its real top/bottom
 * position; a case with nothing to scroll stays one screen.
 *
 * Everything here reads the Compose semantics tree and must run on the main
 * thread. Lazy lists only report an index-based ScrollAxisRange estimate, so
 * their overflow is measured by scrolling and tracking how far composed
 * descendant nodes move ([anchorTops]); `verticalScroll` reports exact pixels.
 */
internal object VisualFullLength {
    const val ARG_CASES = "visualFullLengthCases"
    const val DEFAULT_MAX_HEIGHT_DP = 2700
    const val MAX_ITERATIONS = 6

    data class Scroller(
        val nodeId: Int,
        val visibleArea: Float,
        val heightPx: Float,
        val value: Float,
        val maxValue: Float,
        val lazy: Boolean,
        val reversed: Boolean,
        val label: String
    )

    fun requestedCases(raw: String?): Set<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun composeRoots(windowRoots: List<View>): List<ViewRootForTest> {
        val found = mutableListOf<ViewRootForTest>()
        fun walk(view: View) {
            if (view is ViewRootForTest) {
                found += view
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        windowRoots.forEach(::walk)
        return found
    }

    private fun allNodes(windowRoots: List<View>): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        composeRoots(windowRoots).forEach { walk(it.semanticsOwner.unmergedRootSemanticsNode) }
        return nodes
    }

    private fun findNode(windowRoots: List<View>, nodeId: Int): SemanticsNode? =
        allNodes(windowRoots).firstOrNull { it.id == nodeId }

    /**
     * Largest visible vertical scroller that has content outside its viewport,
     * or null. Bottom-anchored lists (reverseLayout message lists, newest at the
     * bottom) count too: their hidden content is the history ABOVE the fold.
     * Growing the display reveals it upward while the header stays on top and
     * the composer at the bottom; [scrollToTop] returns them to their start
     * (the newest message), i.e. their normal anchored position.
     */
    fun mainScroller(windowRoots: List<View>): Scroller? =
        allNodes(windowRoots)
            .mapNotNull { node ->
                val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return@mapNotNull null
                if (node.config.getOrNull(SemanticsActions.ScrollBy) == null) return@mapNotNull null
                val value = range.value()
                val maxValue = range.maxValue()
                if (maxValue <= value) return@mapNotNull null
                val bounds = node.boundsInWindow
                val area = bounds.width * bounds.height
                if (area <= 0f) return@mapNotNull null
                val lazy = node.config.getOrNull(SemanticsProperties.CollectionInfo) != null
                Scroller(
                    nodeId = node.id,
                    visibleArea = area,
                    heightPx = bounds.height,
                    value = value,
                    maxValue = maxValue,
                    lazy = lazy,
                    reversed = range.reverseScrolling,
                    label = "${if (lazy) "lazy" else "scroll"}${if (range.reverseScrolling) "-reversed" else ""}#${node.id}@${bounds.top.toInt()}..${bounds.bottom.toInt()}"
                )
            }
            .maxByOrNull { it.visibleArea }

    /** Unclipped window y of every composed descendant of the scroller, by semantics id. */
    fun anchorTops(windowRoots: List<View>, scrollerId: Int): Map<Int, Float> {
        val scroller = findNode(windowRoots, scrollerId) ?: return emptyMap()
        val tops = mutableMapOf<Int, Float>()
        fun walk(node: SemanticsNode) {
            for (child in node.children) {
                if (child.size.width > 0 && child.size.height > 0) {
                    tops[child.id] = child.positionInWindow.y
                }
                walk(child)
            }
        }
        walk(scroller)
        return tops
    }

    fun scrollBy(windowRoots: List<View>, scrollerId: Int, dyPx: Float): Boolean {
        val node = findNode(windowRoots, scrollerId) ?: return false
        val action = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
        return action(0f, dyPx)
    }

    fun scrollToTop(windowRoots: List<View>, scrollerId: Int): Boolean {
        val node = findNode(windowRoots, scrollerId) ?: return false
        node.config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.let { toIndex ->
            if (toIndex(0)) return true
        }
        val action = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
        return action(0f, -1_000_000f)
    }

    /**
     * Median displacement of the anchors present in both snapshots, or null
     * when none overlap. Signed: a reversed list moves its content down when
     * scrolled forward, so callers use the magnitude.
     */
    fun displacement(before: Map<Int, Float>, after: Map<Int, Float>): Float? {
        val deltas = before.keys.intersect(after.keys).map { before.getValue(it) - after.getValue(it) }.sorted()
        return if (deltas.isEmpty()) null else deltas[deltas.size / 2]
    }
}
