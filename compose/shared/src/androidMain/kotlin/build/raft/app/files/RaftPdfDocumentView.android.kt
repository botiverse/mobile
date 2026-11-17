package build.raft.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android PDF viewer: [PdfRenderer] over the cached file, drawn as a vertical column of pages
 * with pinch-zoom (1x-5x), pan, fling and double-tap zoom.
 *
 * Memory: only the pages of the render window around the viewport hold a bitmap
 * ([RaftPdfPageLayout.renderPlan]), visible pages at a resolution that follows the zoom and the
 * rest at 1x, within a heap-derived byte budget. Past the per-page cap, a viewport-sized sharp
 * patch is rendered at the exact zoom once the gesture settles ([RaftPdfPageLayout.detailRegions]).
 * Bitmaps leaving the window are recycled two frames after the last frame that drew them.
 *
 * Threading: one single-thread executor owns the renderer (PdfRenderer allows one open page at a
 * time); all bitmap bookkeeping happens on the main thread. The scroll position is owned by this
 * viewer alone and moves only from the user's gestures (no programmatic scroll).
 */
@Composable
internal actual fun RaftPdfDocumentView(
    path: String,
    onPageChanged: (currentPage: Int, pageCount: Int) -> Unit,
    onError: (RaftPdfDocumentError) -> Unit,
    modifier: Modifier,
    bottomInset: Dp,
) {
    val pageCallback by rememberUpdatedState(onPageChanged)
    val errorCallback by rememberUpdatedState(onError)
    key(path) {
        val document = remember { RaftAndroidPdfDocument(path) }
        DisposableEffect(document) { onDispose { document.close() } }
        var pageSizes by remember { mutableStateOf<List<Pair<Int, Int>>?>(null) }
        LaunchedEffect(document) {
            when (val result = document.open()) {
                is RaftAndroidPdfOpenResult.Opened -> pageSizes = result.pageSizes
                is RaftAndroidPdfOpenResult.Failed -> errorCallback(result.error)
            }
        }
        Box(modifier) {
            pageSizes?.let { sizes ->
                RaftAndroidPdfPages(
                    document = document,
                    pageSizes = sizes,
                    onPageChanged = { current, count -> pageCallback(current, count) },
                    bottomInset = bottomInset,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Renders page bitmaps for [RaftAndroidPdfPages]; PdfRenderer in production, fakes in tests. */
internal interface RaftPdfPageSource {
    /** Page [index] scaled to fill a [width] x [height] bitmap; null when it cannot be rendered. */
    suspend fun renderPage(index: Int, width: Int, height: Int): Bitmap?

    /** The part of a page in [region] (content px) at zoom [scale], one bitmap pixel per screen pixel. */
    suspend fun renderRegion(layout: RaftPdfPageLayout, region: RaftPdfDetailRegion, scale: Float): Bitmap?
}

/**
 * The page column of the viewer over [source]. [initialOffsetY] (screen px at zoom 1) is only the
 * starting scroll position, used by screenshot tests; afterwards only gestures move it.
 */
@Composable
internal fun RaftAndroidPdfPages(
    document: RaftPdfPageSource,
    pageSizes: List<Pair<Int, Int>>,
    onPageChanged: (Int, Int) -> Unit,
    bottomInset: Dp,
    modifier: Modifier,
    initialOffsetY: Float = 0f,
) {
    val density = LocalDensity.current
    val sideMargin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    val bottomPadding = with(density) { bottomInset.toPx() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    val layout =
        remember(pageSizes, viewport.width, sideMargin, gap, bottomPadding) {
            if (viewport.width <= 0) {
                null
            } else {
                raftPdfPageLayout(pageSizes, viewport.width.toFloat(), sideMargin, gap, bottomPadding)
            }
        }
    val scope = rememberCoroutineScope()
    val store = remember { RaftPdfBitmapStore() }
    DisposableEffect(store) { onDispose { store.releaseAll() } }
    val budgetBytes = remember { raftPdfBitmapBudgetBytes(Runtime.getRuntime().maxMemory()) }
    val decay = rememberSplineBasedDecay<Float>()
    var motionJob by remember { mutableStateOf<Job?>(null) }

    fun clamp() {
        val current = layout ?: return
        val (maxX, maxY) = current.maxOffsets(viewport.height.toFloat(), scale)
        offsetX = offsetX.coerceIn(0f, maxX)
        offsetY = offsetY.coerceIn(0f, maxY)
    }

    fun zoomBy(factor: Float, focus: Offset) {
        val next = (scale * factor).coerceIn(RaftPdfMinZoom, RaftPdfMaxZoom)
        val ratio = next / scale
        if (ratio == 1f) return
        offsetX = (offsetX + focus.x) * ratio - focus.x
        offsetY = (offsetY + focus.y) * ratio - focus.y
        scale = next
        clamp()
    }

    // A width change (rotation, split screen) keeps the reading position proportionally.
    var lastContentHeight by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(layout) {
        val current = layout ?: return@LaunchedEffect
        if (lastContentHeight > 0f && current.contentHeight > 0f && lastContentHeight != current.contentHeight) {
            offsetY *= current.contentHeight / lastContentHeight
        }
        lastContentHeight = current.contentHeight
        clamp()
    }

    // Page indicator: the page under the viewport centre.
    LaunchedEffect(layout) {
        val current = layout ?: return@LaunchedEffect
        snapshotFlow { current.currentPage(offsetY, viewport.height.toFloat(), scale) }
            .distinctUntilChanged()
            .collect { page -> onPageChanged(page, current.pageCount) }
    }

    // Whole-page bitmaps of the render window.
    LaunchedEffect(layout, budgetBytes) {
        val current = layout ?: return@LaunchedEffect
        snapshotFlow { current.renderPlan(offsetY, viewport.height.toFloat(), scale, budgetBytes) }
            .distinctUntilChanged()
            .collectLatest { plan ->
                store.retainPages(plan.mapTo(HashSet()) { it.pageIndex }) { bitmap -> scope.recycleAfterFrames(bitmap) }
                for (render in plan) {
                    val existing = store.pages[render.pageIndex]
                    if (existing != null && existing.width == render.width && existing.height == render.height) continue
                    val bitmap = document.renderPage(render.pageIndex, render.width, render.height) ?: continue
                    store.putPage(render.pageIndex, bitmap) { old -> scope.recycleAfterFrames(old) }
                }
            }
    }

    // Sharp patches past the per-page cap, once the zoom and position have settled.
    LaunchedEffect(layout, budgetBytes) {
        val current = layout ?: return@LaunchedEffect
        snapshotFlow { Triple(scale, offsetX, offsetY) }
            .distinctUntilChanged()
            .collectLatest { (zoom, x, y) ->
                delay(RaftPdfDetailSettleMillis)
                val viewportHeight = viewport.height.toFloat()
                val top = y / zoom
                val visibleCount = current.pagesIntersecting(top, top + viewportHeight / zoom).count()
                val regions =
                    current.detailRegions(
                        offsetX = x,
                        offsetY = y,
                        viewportWidth = viewport.width.toFloat(),
                        viewportHeight = viewportHeight,
                        scale = zoom,
                        maxPixels = raftPdfVisiblePageMaxPixels(budgetBytes, visibleCount),
                    )
                val rendered = ArrayList<RaftPdfDetailBitmap>(regions.size)
                try {
                    for (region in regions) {
                        val bitmap = document.renderRegion(current, region, zoom) ?: continue
                        rendered += RaftPdfDetailBitmap(region, zoom, bitmap, bitmap.asImageBitmap())
                    }
                } catch (cancelled: CancellationException) {
                    rendered.forEach { it.bitmap.recycle() }
                    throw cancelled
                }
                store.replaceDetails(rendered) { old -> scope.recycleAfterFrames(old) }
            }
    }

    Canvas(
        modifier
            // Pages scrolled past the top must not paint over the page header.
            .clipToBounds()
            .onSizeChanged { size -> if (viewport != size) viewport = size }
            .pointerInput(layout) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        motionJob?.cancel()
                        motionJob =
                            scope.launch {
                                val target = if (scale > RaftPdfMinZoom + 0.05f) RaftPdfMinZoom else RaftPdfDoubleTapZoom
                                val animatable = Animatable(scale)
                                animatable.animateTo(target, tween(durationMillis = 220)) {
                                    zoomBy(value / scale, tap)
                                }
                            }
                    }
                )
            }
            .pointerInput(layout) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    motionJob?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var pointerCount = 1
                    var travelled = 0f
                    var transforming = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size != pointerCount) {
                            // A finger joined or left: the centroid jumps, so restart velocity.
                            pointerCount = pressed.size
                            tracker.resetTracking()
                        }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (!transforming) {
                            travelled += pan.getDistance()
                            transforming = travelled > touchSlop || abs(zoom - 1f) > 0.01f
                        }
                        if (transforming) {
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f && centroid != Offset.Unspecified) zoomBy(zoom, centroid)
                            offsetX -= pan.x
                            offsetY -= pan.y
                            clamp()
                            event.changes.forEach { change: PointerInputChange ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (centroid != Offset.Unspecified) {
                            tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                        }
                    }
                    if (transforming && pointerCount == 1) {
                        val velocity = tracker.calculateVelocity()
                        motionJob =
                            scope.launch {
                                fling(
                                    velocityX = -velocity.x,
                                    velocityY = -velocity.y,
                                    decay = decay,
                                    readX = { offsetX },
                                    readY = { offsetY },
                                    writeX = { value -> offsetX = value; clamp(); offsetX },
                                    writeY = { value -> offsetY = value; clamp(); offsetY },
                                )
                            }
                    }
                }
            }
    ) {
        val current = layout ?: return@Canvas
        val zoom = scale
        val x = offsetX
        val y = offsetY
        val top = y / zoom
        val visible = current.pagesIntersecting(top, top + size.height / zoom)
        val pageWidth = current.pageWidth * zoom
        val left = current.sideMargin * zoom - x
        for (index in visible) {
            val pageTop = current.pageTops[index] * zoom - y
            val pageHeight = current.pageHeights[index] * zoom
            drawRect(Color.White, topLeft = Offset(left, pageTop), size = Size(pageWidth, pageHeight))
            val page = store.pages[index] ?: continue
            drawImage(
                image = page.image,
                srcSize = IntSize(page.width, page.height),
                dstOffset = IntOffset(left.roundToInt(), pageTop.roundToInt()),
                dstSize = IntSize(pageWidth.roundToInt(), pageHeight.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
        for (detail in store.details) {
            if (detail.region.pageIndex !in visible) continue
            val region = detail.region
            drawImage(
                image = detail.image,
                srcSize = IntSize(detail.bitmap.width, detail.bitmap.height),
                dstOffset = IntOffset((region.left * zoom - x).roundToInt(), (region.top * zoom - y).roundToInt()),
                dstSize =
                    IntSize(
                        ((region.right - region.left) * zoom).roundToInt(),
                        ((region.bottom - region.top) * zoom).roundToInt(),
                    ),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private const val RaftPdfDetailSettleMillis = 150L

/** Decays both axes from the release velocity; an axis stops when it reaches its scroll bound. */
private suspend fun fling(
    velocityX: Float,
    velocityY: Float,
    decay: DecayAnimationSpec<Float>,
    readX: () -> Float,
    readY: () -> Float,
    writeX: (Float) -> Float,
    writeY: (Float) -> Float,
) = coroutineScope {
    if (abs(velocityY) > 1f) {
        launch {
            AnimationState(initialValue = readY(), initialVelocity = velocityY).animateDecay(decay) {
                if (writeY(value) != value) cancelAnimation()
            }
        }
    }
    if (abs(velocityX) > 1f) {
        launch {
            AnimationState(initialValue = readX(), initialVelocity = velocityX).animateDecay(decay) {
                if (writeX(value) != value) cancelAnimation()
            }
        }
    }
}

/**
 * A bitmap the viewer stopped drawing is recycled two frames later: the frame that dropped it
 * must have been recorded and replaced before its pixels go.
 */
private fun kotlinx.coroutines.CoroutineScope.recycleAfterFrames(bitmap: Bitmap) {
    launch {
        try {
            withFrameNanos {}
            withFrameNanos {}
        } finally {
            // Also on cancellation (the viewer left): nothing draws it any more.
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

private class RaftPdfPageBitmap(val bitmap: Bitmap) {
    val image: ImageBitmap = bitmap.asImageBitmap()
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

private class RaftPdfDetailBitmap(
    val region: RaftPdfDetailRegion,
    val scale: Float,
    val bitmap: Bitmap,
    val image: ImageBitmap,
)

/** Main-thread bookkeeping of the bitmaps on screen; draws read it as snapshot state. */
private class RaftPdfBitmapStore {
    val pages = mutableStateMapOf<Int, RaftPdfPageBitmap>()
    var details by mutableStateOf<List<RaftPdfDetailBitmap>>(emptyList())
        private set

    fun putPage(index: Int, bitmap: Bitmap, release: (Bitmap) -> Unit) {
        pages.put(index, RaftPdfPageBitmap(bitmap))?.let { old -> release(old.bitmap) }
    }

    fun retainPages(keep: Set<Int>, release: (Bitmap) -> Unit) {
        val drop = pages.keys.filter { it !in keep }
        drop.forEach { index -> pages.remove(index)?.let { old -> release(old.bitmap) } }
    }

    fun replaceDetails(next: List<RaftPdfDetailBitmap>, release: (Bitmap) -> Unit) {
        val old = details
        details = next
        old.forEach { release(it.bitmap) }
    }

    /** The viewer is gone: nothing draws any more. */
    fun releaseAll() {
        pages.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        pages.clear()
        details.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        details = emptyList()
    }
}

private sealed interface RaftAndroidPdfOpenResult {
    class Opened(val pageSizes: List<Pair<Int, Int>>) : RaftAndroidPdfOpenResult

    class Failed(val error: RaftPdfDocumentError) : RaftAndroidPdfOpenResult
}

/** The renderer of one file, confined to its own render thread. */
private class RaftAndroidPdfDocument(private val path: String) : RaftPdfPageSource {
    private val executor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "raft-pdf-render").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageWidthsPt: IntArray = IntArray(0)

    @Volatile
    private var closed = false

    suspend fun open(): RaftAndroidPdfOpenResult =
        withContext(dispatcher) {
            val file = File(path)
            if (closed || !file.isFile || file.length() <= 0L) {
                return@withContext RaftAndroidPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
            }
            val head =
                try {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(1024)
                        var read = 0
                        while (read < buffer.size) {
                            val count = input.read(buffer, read, buffer.size - read)
                            if (count < 0) break
                            read += count
                        }
                        buffer.copyOf(read)
                    }
                } catch (_: IOException) {
                    ByteArray(0)
                }
            if (!raftPdfHasHeader(head)) {
                return@withContext RaftAndroidPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
            }
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor = fd
                val pdf = PdfRenderer(fd)
                renderer = pdf
                val count = pdf.pageCount
                if (count <= 0) return@withContext RaftAndroidPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
                val sizes =
                    List(count) { index ->
                        val page = pdf.openPage(index)
                        try {
                            page.width to page.height
                        } finally {
                            page.close()
                        }
                    }
                pageWidthsPt = IntArray(count) { sizes[it].first }
                RaftAndroidPdfOpenResult.Opened(sizes)
            } catch (_: SecurityException) {
                // PdfRenderer: "the file requires a password or the security scheme is not supported".
                RaftAndroidPdfOpenResult.Failed(RaftPdfDocumentError.PasswordProtected)
            } catch (_: Exception) {
                RaftAndroidPdfOpenResult.Failed(RaftPdfDocumentError.Corrupt)
            }
        }

    override suspend fun renderPage(index: Int, width: Int, height: Int): Bitmap? =
        render { pdf ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val page = pdf.openPage(index)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bitmap
        }

    override suspend fun renderRegion(layout: RaftPdfPageLayout, region: RaftPdfDetailRegion, scale: Float): Bitmap? =
        render { pdf ->
            val (width, height) = region.bitmapSize(scale)
            val widthPt = pageWidthsPt.getOrNull(region.pageIndex)?.takeIf { it > 0 } ?: return@render null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val pointsToPixels = layout.pageWidth * scale / widthPt
            val transform =
                Matrix().apply {
                    setScale(pointsToPixels, pointsToPixels)
                    postTranslate(
                        -(region.left - layout.sideMargin) * scale,
                        -(region.top - layout.pageTops[region.pageIndex]) * scale,
                    )
                }
            val page = pdf.openPage(region.pageIndex)
            try {
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bitmap
        }

    private suspend fun render(block: (PdfRenderer) -> Bitmap?): Bitmap? =
        withContext(dispatcher) {
            val pdf = renderer?.takeIf { !closed } ?: return@withContext null
            try {
                block(pdf)
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
        }

    fun close() {
        if (closed) return
        closed = true
        // Queued behind any render in progress: the renderer closes after its last page.
        executor.execute {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
        executor.shutdown()
    }
}
