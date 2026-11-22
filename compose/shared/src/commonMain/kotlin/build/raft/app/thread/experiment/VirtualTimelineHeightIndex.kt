package build.raft.app.thread.experiment

/**
 * Key-indexed persistent height cache backed by a Fenwick tree (Binary Indexed Tree) for
 * zero-allocation hot-path offset queries and O(log N) visible-range binary search.
 *
 * Design invariants ported from web `@botiverse/raft-ui` `VirtualList` and `fluid-scroll`:
 * - Measured row heights persist across list mutations (prepends, appends, system-group folds)
 *   keyed by stable row key, avoiding re-estimation jitter on already-seen rows.
 * - Unmeasured rows fall back to [estimatedItemHeightPx] until composed and measured.
 * - [offsetOf], [indexAtOffset], and [visibleRange] allocate zero objects on the scroll hot path.
 */
internal class VirtualTimelineHeightIndex(
    initialEstimatedHeightPx: Int = DefaultEstimatedItemHeightPx,
) {
    var estimatedItemHeightPx: Int = initialEstimatedHeightPx.coerceAtLeast(1)
        private set

    private val heightCacheByKey = HashMap<Any, Int>()
    private val keyToIndex = HashMap<Any, Int>()
    private var keys: List<Any> = emptyList()
    private var itemHeights: IntArray = IntArray(0)
    private var fenwick: IntArray = IntArray(1)
    private var maxBitPower: Int = 1

    var itemCount: Int = 0
        private set

    var totalHeightPx: Int = 0
        private set

    /** Diagnostics counter: total hot-path [visibleRange] / [indexAtOffset] lookups executed. */
    var hotPathLookupCount: Long = 0L
        private set

    /** Diagnostics counter: total Fenwick tree steps executed on the last [indexAtOffset] call. */
    var lastBinaryLiftSteps: Int = 0
        private set

    fun keyAt(index: Int): Any = keys[index]

    fun indexOfKey(key: Any): Int = keyToIndex[key] ?: -1

    fun hasMeasuredHeight(key: Any): Boolean = heightCacheByKey.containsKey(key)

    fun cachedMeasuredCount(): Int = heightCacheByKey.size

    fun heightOf(index: Int): Int =
        if (index in 0 until itemCount) itemHeights[index] else estimatedItemHeightPx

    fun updateEstimatedItemHeight(newEstimatePx: Int) {
        val clamped = newEstimatePx.coerceAtLeast(1)
        if (clamped == estimatedItemHeightPx) return
        estimatedItemHeightPx = clamped
        if (itemCount > 0) {
            rebuildHeightsAndFenwick()
        }
    }

    /**
     * Synchronizes the ordered virtual row keys in O(N), preserving every previously measured
     * height from [heightCacheByKey]. Returns `true` when the key sequence changed.
     */
    fun syncKeys(newKeys: List<Any>): Boolean {
        if (newKeys.size == itemCount && keysEqual(keys, newKeys)) {
            return false
        }
        keys = newKeys
        itemCount = newKeys.size
        keyToIndex.clear()
        for (i in 0 until itemCount) {
            keyToIndex[newKeys[i]] = i
        }
        if (itemHeights.size < itemCount) {
            itemHeights = IntArray(itemCount)
        }
        if (fenwick.size < itemCount + 1) {
            fenwick = IntArray(itemCount + 1)
        }
        var bit = 1
        while ((bit shl 1) <= itemCount) {
            bit = bit shl 1
        }
        maxBitPower = bit
        rebuildHeightsAndFenwick()
        return true
    }

    /**
     * Records a real measured pixel height for row [index] / [key], updating the Fenwick tree in
     * O(log N). Returns the height delta (`newHeight - previousHeight`).
     */
    fun recordMeasuredHeight(index: Int, key: Any, measuredHeightPx: Int): Int {
        val clamped = measuredHeightPx.coerceAtLeast(0)
        heightCacheByKey[key] = clamped
        if (index !in 0 until itemCount) return 0
        val previous = itemHeights[index]
        val delta = clamped - previous
        if (delta == 0) return 0
        itemHeights[index] = clamped
        totalHeightPx += delta
        bitAdd(index + 1, delta)
        return delta
    }

    /**
     * Cumulative top offset in pixels of row [index] (sum of heights `0 until index`).
     * Runs in O(log N) with zero allocations.
     */
    fun offsetOf(index: Int): Int {
        if (index <= 0 || itemCount == 0) return 0
        if (index >= itemCount) return totalHeightPx
        var sum = 0
        var i = index
        while (i > 0) {
            sum += fenwick[i]
            i -= i and -i
        }
        return sum
    }

    /**
     * Finds the first row index whose bottom (`offsetOf(i) + heightOf(i)`) is strictly greater
     * than [offsetPx], clamped to `0 until itemCount`. Returns -1 when empty.
     *
     * Uses Fenwick binary lifting: strictly O(log N) and zero heap allocations.
     */
    fun indexAtOffset(offsetPx: Int): Int {
        hotPathLookupCount += 1L
        if (itemCount == 0) {
            lastBinaryLiftSteps = 0
            return -1
        }
        if (offsetPx <= 0) {
            lastBinaryLiftSteps = 1
            return 0
        }
        if (offsetPx >= totalHeightPx) {
            lastBinaryLiftSteps = 1
            return itemCount - 1
        }
        var pos = 0
        var accumulated = 0
        var step = maxBitPower
        var iterations = 0
        while (step > 0) {
            iterations += 1
            val next = pos + step
            if (next <= itemCount && accumulated + fenwick[next] <= offsetPx) {
                accumulated += fenwick[next]
                pos = next
            }
            step = step shr 1
        }
        lastBinaryLiftSteps = iterations
        return pos.coerceIn(0, itemCount - 1)
    }

    /**
     * Writes the inclusive visible index range `[firstIndex, lastIndex]` covering
     * `[viewportTopPx, viewportBottomPx)` into caller-owned [outRange] (length >= 2).
     * Zero allocations on the scroll hot path. Returns `false` when no items exist.
     */
    fun visibleRange(
        viewportTopPx: Int,
        viewportBottomPx: Int,
        outRange: IntArray,
    ): Boolean {
        if (itemCount == 0 || viewportBottomPx <= viewportTopPx) {
            outRange[0] = 0
            outRange[1] = -1
            return false
        }
        val first = indexAtOffset(viewportTopPx.coerceAtLeast(0))
        val last = indexAtOffset((viewportBottomPx - 1).coerceAtLeast(0))
        outRange[0] = first
        outRange[1] = if (last < first) first else last
        return true
    }

    private fun rebuildHeightsAndFenwick() {
        var total = 0
        for (i in 0 until itemCount) {
            val h = heightCacheByKey[keys[i]] ?: estimatedItemHeightPx
            itemHeights[i] = h
            total += h
        }
        totalHeightPx = total
        // Linear O(N) Fenwick tree construction.
        fenwick[0] = 0
        for (i in 1..itemCount) {
            fenwick[i] = itemHeights[i - 1]
        }
        for (i in 1..itemCount) {
            val parent = i + (i and -i)
            if (parent <= itemCount) {
                fenwick[parent] += fenwick[i]
            }
        }
    }

    private fun bitAdd(oneBasedIndex: Int, delta: Int) {
        var i = oneBasedIndex
        while (i <= itemCount) {
            fenwick[i] += delta
            i += i and -i
        }
    }

    private fun keysEqual(a: List<Any>, b: List<Any>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    companion object {
        const val DefaultEstimatedItemHeightPx: Int = 72
    }
}
