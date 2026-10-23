package build.raft.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure geometry and render-window policy of the Android PDF viewer ([RaftPdfDocumentView]).
 *
 * Coordinates: "content" pixels are the column laid out at zoom 1, where every page fits the
 * viewport width minus [RaftPdfPageLayout.sideMargin] on each side, separated by
 * [RaftPdfPageLayout.gap]. At zoom `scale` a content point (x, y) is drawn at
 * (x * scale - offsetX, y * scale - offsetY) on screen.
 */
internal class RaftPdfPageLayout(
    val viewportWidth: Float,
    val sideMargin: Float,
    val gap: Float,
    /** Top of each page, in content pixels. */
    val pageTops: FloatArray,
    /** Height of each page, in content pixels. */
    val pageHeights: FloatArray,
) {
    val pageCount: Int get() = pageTops.size
    val pageWidth: Float get() = (viewportWidth - 2f * sideMargin).coerceAtLeast(1f)
    val contentHeight: Float =
        if (pageTops.isEmpty()) 0f else pageTops.last() + pageHeights.last() + gap

    fun pageBottom(index: Int): Float = pageTops[index] + pageHeights[index]
}

/**
 * Lays out pages of the given point sizes ([pageSizes] = width to height) in one column that fits
 * [viewportWidth]. A degenerate size falls back to A4 portrait proportions.
 */
internal fun raftPdfPageLayout(
    pageSizes: List<Pair<Int, Int>>,
    viewportWidth: Float,
    sideMargin: Float,
    gap: Float,
): RaftPdfPageLayout {
    val pageWidth = (viewportWidth - 2f * sideMargin).coerceAtLeast(1f)
    val tops = FloatArray(pageSizes.size)
    val heights = FloatArray(pageSizes.size)
    var y = gap
    pageSizes.forEachIndexed { index, (widthPt, heightPt) ->
        val ratio = if (widthPt > 0 && heightPt > 0) heightPt.toFloat() / widthPt.toFloat() else RaftPdfA4Ratio
        tops[index] = y
        heights[index] = pageWidth * ratio
        y += heights[index] + gap
    }
    return RaftPdfPageLayout(viewportWidth, sideMargin, gap, tops, heights)
}

private const val RaftPdfA4Ratio = 297f / 210f

/** Pages that intersect the content band [top, bottom) (content pixels); empty when none. */
internal fun RaftPdfPageLayout.pagesIntersecting(top: Float, bottom: Float): IntRange {
    if (pageCount == 0 || bottom <= top) return IntRange.EMPTY
    // First page whose bottom is below `top`.
    var lo = 0
    var hi = pageCount - 1
    var first = pageCount
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (pageBottom(mid) > top) {
            first = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    if (first == pageCount) return IntRange.EMPTY
    var last = first - 1
    var index = first
    while (index < pageCount && pageTops[index] < bottom) {
        last = index
        index++
    }
    return if (last < first) IntRange.EMPTY else first..last
}

/**
 * The pages whose bitmaps should exist for a viewport: the visible pages plus a prefetch band of
 * [prefetchViewports] viewport heights above and below, never more than [maxPages] (trimmed from
 * the far side of the band, so visible pages always stay). [offsetY] and [viewportHeight] are
 * screen pixels at zoom [scale].
 */
internal fun RaftPdfPageLayout.renderWindow(
    offsetY: Float,
    viewportHeight: Float,
    scale: Float,
    prefetchViewports: Float = 0.5f,
    maxPages: Int = RaftPdfMaxWindowPages,
): IntRange {
    if (pageCount == 0 || viewportHeight <= 0f || scale <= 0f) return IntRange.EMPTY
    val top = offsetY / scale
    val height = viewportHeight / scale
    val visible = pagesIntersecting(top, top + height)
    val band = pagesIntersecting(top - height * prefetchViewports, top + height * (1f + prefetchViewports))
    if (band.isEmpty()) return visible
    if (band.count() <= maxPages) return band
    if (visible.isEmpty()) return band.first until (band.first + maxPages)
    // Keep the visible pages, then grow one page at a time, nearest side first (below wins ties:
    // reading direction).
    var first = visible.first
    var last = visible.last
    if (last - first + 1 > maxPages) return first until (first + maxPages)
    while (last - first + 1 < maxPages) {
        val canDown = last < band.last
        val canUp = first > band.first
        if (!canDown && !canUp) break
        if (canDown) last++
        if (last - first + 1 < maxPages && canUp) first--
    }
    return first..last
}

/**
 * The page the reader is on: the page under the viewport's vertical centre, or the nearest page
 * when the centre falls in a gap. Zero-based; 0 for an empty document.
 */
internal fun RaftPdfPageLayout.currentPage(offsetY: Float, viewportHeight: Float, scale: Float): Int {
    if (pageCount == 0 || scale <= 0f) return 0
    val centre = (offsetY + viewportHeight / 2f) / scale
    val hit = pagesIntersecting(centre, centre + 0.5f)
    if (!hit.isEmpty()) return hit.first
    // In a gap or past the ends: the first page that starts below the centre, else the last page.
    for (index in 0 until pageCount) {
        if (pageTops[index] >= centre) return index
    }
    return pageCount - 1
}

/**
 * Bitmap size of a whole page rendered for zoom [scale]: the page drawn at its on-screen size,
 * capped to [maxPixels] (keeping the aspect ratio), so a high zoom never allocates a huge bitmap;
 * past the cap the viewer adds a viewport-sized detail bitmap instead ([detailRegions]).
 */
internal fun RaftPdfPageLayout.pageBitmapSize(
    index: Int,
    scale: Float,
    maxPixels: Long = RaftPdfMaxPageBitmapPixels,
): Pair<Int, Int> {
    val width = pageWidth * scale
    val height = pageHeights[index] * scale
    val pixels = width.toDouble() * height.toDouble()
    val shrink = if (pixels > maxPixels) sqrt(maxPixels / pixels).toFloat() else 1f
    return max(1, (width * shrink).toInt()) to max(1, (height * shrink).toInt())
}

/**
 * Zoom levels the whole-page bitmaps are rendered at: the current zoom rounded up to the next
 * step, so pinching does not re-render on every frame and a bitmap is never upscaled by more than
 * one step.
 */
internal fun raftPdfRenderScaleStep(scale: Float): Float {
    val clamped = scale.coerceIn(RaftPdfMinZoom, RaftPdfMaxZoom)
    return RaftPdfRenderScaleSteps.firstOrNull { it >= clamped - 0.01f } ?: RaftPdfMaxZoom
}

private val RaftPdfRenderScaleSteps = floatArrayOf(1f, 1.5f, 2f, 3f, 4f, 5f)

/** One sharp patch: the part of page [pageIndex] inside the viewport, in content pixels. */
internal data class RaftPdfDetailRegion(
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Bitmap size of this patch at zoom [scale] (screen pixels, rounded out). */
    fun bitmapSize(scale: Float): Pair<Int, Int> =
        max(1, ceil((right - left) * scale).toInt()) to max(1, ceil((bottom - top) * scale).toInt())
}

/**
 * The viewport-sized patches to render at the exact zoom when whole-page bitmaps are capped by
 * [pageBitmapSize] (pages whose full bitmap at [scale] fits [maxPixels] need none). Patches cover
 * only what is on screen, so their pixels add up to at most the viewport.
 */
internal fun RaftPdfPageLayout.detailRegions(
    offsetX: Float,
    offsetY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    scale: Float,
    maxPixels: Long = RaftPdfMaxPageBitmapPixels,
): List<RaftPdfDetailRegion> {
    if (pageCount == 0 || scale <= 0f) return emptyList()
    val left = offsetX / scale
    val top = offsetY / scale
    val right = (offsetX + viewportWidth) / scale
    val bottom = (offsetY + viewportHeight) / scale
    return pagesIntersecting(top, bottom).mapNotNull { index ->
        val (fullWidth, fullHeight) = pageBitmapSize(index, scale, Long.MAX_VALUE)
        if (fullWidth.toLong() * fullHeight.toLong() <= maxPixels) return@mapNotNull null
        val pageLeft = sideMargin
        val pageRight = sideMargin + pageWidth
        val patchLeft = max(left, pageLeft)
        val patchRight = min(right, pageRight)
        val patchTop = max(top, pageTops[index])
        val patchBottom = min(bottom, pageBottom(index))
        if (patchRight <= patchLeft || patchBottom <= patchTop) return@mapNotNull null
        RaftPdfDetailRegion(
            pageIndex = index,
            left = floor(patchLeft),
            top = floor(patchTop),
            right = ceil(patchRight),
            bottom = ceil(patchBottom),
        )
    }
}

/** One whole-page bitmap the viewer should hold: page [pageIndex] at [width] x [height] px. */
internal data class RaftPdfPageRender(val pageIndex: Int, val width: Int, val height: Int) {
    val bytes: Long get() = width.toLong() * height.toLong() * 4L
}

/**
 * Pixel cap of one visible page: visible pages share at most half of [budgetBytes] (ARGB_8888),
 * and no page exceeds [RaftPdfMaxPageBitmapPixels].
 */
internal fun raftPdfVisiblePageMaxPixels(budgetBytes: Long, visibleCount: Int): Long =
    min(RaftPdfMaxPageBitmapPixels, budgetBytes / 2L / 4L / max(1, visibleCount))

/**
 * The whole-page bitmaps for a viewport, nearest the reading position first: visible pages at the
 * zoom's render step ([raftPdfRenderScaleStep]) capped by [raftPdfVisiblePageMaxPixels]; the
 * prefetch pages of [renderWindow] at zoom 1, while they fit in what is left of [budgetBytes].
 * The total never exceeds [budgetBytes]; everything not listed is released by the viewer.
 */
internal fun RaftPdfPageLayout.renderPlan(
    offsetY: Float,
    viewportHeight: Float,
    scale: Float,
    budgetBytes: Long,
): List<RaftPdfPageRender> {
    val window = renderWindow(offsetY, viewportHeight, scale)
    if (window.isEmpty()) return emptyList()
    val top = offsetY / scale
    val visible = pagesIntersecting(top, top + viewportHeight / scale)
    val visibleCap = raftPdfVisiblePageMaxPixels(budgetBytes, visible.count())
    val centre = currentPage(offsetY, viewportHeight, scale)
    val renderStep = raftPdfRenderScaleStep(scale)
    val ordered = window.sortedWith(compareBy<Int>({ it !in visible }, { kotlin.math.abs(it - centre) }, { it }))
    var used = 0L
    val plan = ArrayList<RaftPdfPageRender>(ordered.size)
    for (index in ordered) {
        val isVisible = index in visible
        val (width, height) = pageBitmapSize(index, if (isVisible) renderStep else RaftPdfMinZoom, visibleCap)
        val render = RaftPdfPageRender(index, width, height)
        if (used + render.bytes > budgetBytes) {
            if (isVisible) continue else break
        }
        used += render.bytes
        plan += render
    }
    return plan
}

/** Largest scroll offsets at zoom [scale] for a viewport of the given size. */
internal fun RaftPdfPageLayout.maxOffsets(viewportHeight: Float, scale: Float): Pair<Float, Float> =
    max(0f, viewportWidth * scale - viewportWidth) to max(0f, contentHeight * scale - viewportHeight)

/**
 * Byte budget for whole-page bitmaps: an eighth of the heap, between 24 and 96 MiB. The window
 * itself is capped by [RaftPdfMaxWindowPages], and bitmaps outside the window are released.
 */
internal fun raftPdfBitmapBudgetBytes(maxHeapBytes: Long): Long =
    (maxHeapBytes / 8).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)

internal const val RaftPdfMinZoom = 1f
internal const val RaftPdfMaxZoom = 5f
internal const val RaftPdfDoubleTapZoom = 2.5f
internal const val RaftPdfMaxWindowPages = 6

/** Whole-page bitmap cap: 4 MP (16 MiB ARGB_8888). */
internal const val RaftPdfMaxPageBitmapPixels: Long = 4L * 1024 * 1024
