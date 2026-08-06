package build.raft.app.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Evidence-only classifier for the Thread white-viewport investigation.
 *
 * This lives at the Android host boundary deliberately: none of these fields are product state,
 * route payload, pageData, or MVI state. The common Thread caller supplies and later revalidates a
 * generation; this seam only captures the Android Window, the current local View tree, and the
 * native descendants intersecting the exact requested viewport.
 */
// The frozen same-target samples separate a healthy 615‰ frame from physical-white 899‰ and
// 1000‰ frames. Keep the raw counters in every receipt; this threshold only chooses a diagnostic
// stage hint and must never become a product rendering decision.
internal const val THREAD_VIEWPORT_BLANK_LIKE_WHITE_PERMILLE = 850

private const val THREAD_VIEWPORT_SAMPLE_STEP_PX = 4
private const val THREAD_VIEWPORT_CAPTURE_TIMEOUT_MS = 2_000L
private const val THREAD_VIEWPORT_EFFECTIVE_ALPHA_EPSILON = 0.001f
private const val THREAD_VIEWPORT_DESCENDANT_EXAMPLE_LIMIT = 8

internal data class ThreadViewportCaptureRequest(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val generation: Long,
)

internal data class ThreadViewportPixelStats(
    val sampleCount: Int,
    val nonWhiteCount: Int,
    val darkCount: Int,
    val whitePermille: Int,
) {
    val isBlankLike: Boolean
        get() = sampleCount > 0 && whitePermille >= THREAD_VIEWPORT_BLANK_LIKE_WHITE_PERMILLE
}

internal data class ThreadViewportDescendantCensus(
    val descendants: Int,
    val visibleDescendants: Int,
    val attachedDescendants: Int,
    val layoutRequestedDescendants: Int,
    val viewportIntersectingDescendants: Int,
    val viewportIntersectingLeafDescendants: Int,
    val viewportIntersectingPaintCandidates: Int,
    val viewportIntersectionAreaPx: Long,
    val intersectingSummary: String,
)

internal enum class ThreadViewportRenderStage(val wireValue: String) {
    MATERIALIZATION_OR_VISIBILITY("materialization_or_visibility"),
    NATIVE_SUBTREE_DRAW("native_subtree_draw"),
    WINDOW_OR_SURFACE_PRESENTATION("window_or_surface_presentation"),
    HEALTHY_CONTROL("healthy_control"),
    INCONCLUSIVE("inconclusive"),
    LOCAL_ONLY_LEGACY("local_only_legacy"),
}

internal data class ThreadViewportRenderEvidence(
    val generation: Long,
    val pixelCopyResult: Int,
    val captureMode: String,
    val captureOrder: String,
    val sourceRectInWindow: Rect,
    val geometryStable: Boolean,
    val windowPixels: ThreadViewportPixelStats,
    val localPixels: ThreadViewportPixelStats,
    val descendants: ThreadViewportDescendantCensus,
    val stage: ThreadViewportRenderStage,
    val rootChildClassName: String,
) {
    fun toBridgePayload(): Map<String, Any> =
        mapOf(
            "status" to "ok",
            "pixelCopyResult" to pixelCopyResult,
            "captureMode" to captureMode,
            "captureOrder" to captureOrder,
            "blankLikeWhitePermille" to THREAD_VIEWPORT_BLANK_LIKE_WHITE_PERMILLE,
            "classifier" to stage.wireValue,
            "geometryStable" to geometryStable,
            "sourceLeft" to sourceRectInWindow.left,
            "sourceTop" to sourceRectInWindow.top,
            "sourceWidth" to sourceRectInWindow.width(),
            "sourceHeight" to sourceRectInWindow.height(),
            // Keep the original unprefixed Window fields stable for old Hands readers.
            "sampleCount" to windowPixels.sampleCount,
            "nonWhiteCount" to windowPixels.nonWhiteCount,
            "darkCount" to windowPixels.darkCount,
            "whitePermille" to windowPixels.whitePermille,
            "windowSampleCount" to windowPixels.sampleCount,
            "windowNonWhiteCount" to windowPixels.nonWhiteCount,
            "windowDarkCount" to windowPixels.darkCount,
            "windowWhitePermille" to windowPixels.whitePermille,
            "localSampleCount" to localPixels.sampleCount,
            "localNonWhiteCount" to localPixels.nonWhiteCount,
            "localDarkCount" to localPixels.darkCount,
            "localWhitePermille" to localPixels.whitePermille,
            "descendants" to descendants.descendants,
            "visibleDescendants" to descendants.visibleDescendants,
            "attachedDescendants" to descendants.attachedDescendants,
            "layoutRequestedDescendants" to descendants.layoutRequestedDescendants,
            "viewportIntersectingDescendants" to descendants.viewportIntersectingDescendants,
            "viewportIntersectingLeafDescendants" to descendants.viewportIntersectingLeafDescendants,
            "viewportIntersectingPaintCandidates" to descendants.viewportIntersectingPaintCandidates,
            "viewportIntersectionAreaPx" to descendants.viewportIntersectionAreaPx,
            "viewportDescendantSummary" to descendants.intersectingSummary,
            "rootChild" to rootChildClassName,
        )
}

internal sealed interface ThreadViewportRenderCaptureResult {
    data class Success(val evidence: ThreadViewportRenderEvidence) :
        ThreadViewportRenderCaptureResult

    data class Failure(
        val status: String,
        val message: String,
        val pixelCopyResult: Int? = null,
    ) : ThreadViewportRenderCaptureResult {
        fun toBridgePayload(): Map<String, Any> =
            buildMap {
                put("status", status)
                put("message", message)
                pixelCopyResult?.let { put("pixelCopyResult", it) }
            }
    }
}

internal fun classifyThreadViewportRenderStage(
    viewportIntersectingLeafDescendants: Int,
    windowPixels: ThreadViewportPixelStats,
    localPixels: ThreadViewportPixelStats,
): ThreadViewportRenderStage =
    when {
        viewportIntersectingLeafDescendants == 0 ->
            ThreadViewportRenderStage.MATERIALIZATION_OR_VISIBILITY
        localPixels.isBlankLike && windowPixels.isBlankLike ->
            ThreadViewportRenderStage.NATIVE_SUBTREE_DRAW
        !localPixels.isBlankLike && windowPixels.isBlankLike ->
            ThreadViewportRenderStage.WINDOW_OR_SURFACE_PRESENTATION
        !localPixels.isBlankLike && !windowPixels.isBlankLike ->
            ThreadViewportRenderStage.HEALTHY_CONTROL
        else -> ThreadViewportRenderStage.INCONCLUSIVE
    }

/**
 * Captures the exact production evidence seam used by `probeThreadViewportPixels`.
 *
 * PixelCopy is requested first. Only after its callback returns do we invoke the local software
 * `container.draw(Canvas)` capture. That fixed ordering prevents the diagnostic local draw from
 * changing the Window frame that PixelCopy is intended to classify. The result is still a stage
 * hint rather than a causal proof: local draw actively traverses the current View tree, so the
 * payload records the ordering explicitly.
 */
internal fun captureThreadViewportRenderEvidence(
    window: Window,
    container: ViewGroup,
    request: ThreadViewportCaptureRequest,
    onResult: (ThreadViewportRenderCaptureResult) -> Unit,
) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "Thread viewport render evidence must be captured on the main thread"
    }
    if (!container.isAttachedToWindow || container.width <= 0 || container.height <= 0) {
        onResult(
            ThreadViewportRenderCaptureResult.Failure(
                status = "failed",
                message = "Thread view is not ready",
            )
        )
        return
    }
    if (
        request.left < 0 ||
            request.top < 0 ||
            request.width <= 0 ||
            request.height <= 0 ||
            request.left + request.width > container.width ||
            request.top + request.height > container.height
    ) {
        onResult(
            ThreadViewportRenderCaptureResult.Failure(
                status = "failed",
                message = "Invalid Thread viewport bounds",
            )
        )
        return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        captureLegacyLocalThreadViewport(container, request, onResult)
        return
    }

    val locationInWindow = IntArray(2)
    container.getLocationInWindow(locationInWindow)
    val sourceRect =
        Rect(
            locationInWindow[0] + request.left,
            locationInWindow[1] + request.top,
            locationInWindow[0] + request.left + request.width,
            locationInWindow[1] + request.top + request.height,
        )
    val requestStamp = container.threadViewportGeometryStamp()
    val windowBitmap =
        runCatching {
            Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            onResult(
                ThreadViewportRenderCaptureResult.Failure(
                    status = "failed",
                    message = "Unable to allocate Window pixel probe",
                )
            )
            return
        }
    val handler = Handler(Looper.getMainLooper())
    val delivered = AtomicBoolean(false)
    val timeout =
        Runnable {
            if (delivered.compareAndSet(false, true)) {
                onResult(
                    ThreadViewportRenderCaptureResult.Failure(
                        status = "timeout",
                        message = "PixelCopy callback timed out",
                    )
                )
            }
        }
    handler.postDelayed(timeout, THREAD_VIEWPORT_CAPTURE_TIMEOUT_MS)

    fun finish(result: ThreadViewportRenderCaptureResult) {
        if (!delivered.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        if (!windowBitmap.isRecycled) windowBitmap.recycle()
        onResult(result)
    }

    container.postOnAnimation {
        container.postOnAnimation secondFrame@{
            if (delivered.get()) {
                if (!windowBitmap.isRecycled) windowBitmap.recycle()
                return@secondFrame
            }
            runCatching {
                PixelCopy.request(
                    window,
                    sourceRect,
                    windowBitmap,
                    pixelCopyCallback@{ pixelCopyResult ->
                        if (delivered.get()) {
                            if (!windowBitmap.isRecycled) windowBitmap.recycle()
                            return@pixelCopyCallback
                        }
                        if (pixelCopyResult != PixelCopy.SUCCESS) {
                            finish(
                                ThreadViewportRenderCaptureResult.Failure(
                                    status = "failed",
                                    message = "PixelCopy request failed",
                                    pixelCopyResult = pixelCopyResult,
                                )
                            )
                            return@pixelCopyCallback
                        }
                        val result =
                            runCatching {
                                val census =
                                    censusThreadViewportDescendants(
                                        container = container,
                                        request = request,
                                    )
                                val localBitmap =
                                    Bitmap.createBitmap(
                                        request.width,
                                        request.height,
                                        Bitmap.Config.ARGB_8888,
                                    )
                                try {
                                    val localCanvas = Canvas(localBitmap)
                                    localCanvas.drawColor(Color.WHITE)
                                    localCanvas.translate(
                                        -request.left.toFloat(),
                                        -request.top.toFloat(),
                                    )
                                    container.draw(localCanvas)
                                    val windowPixels = analyzeThreadViewportBitmap(windowBitmap)
                                    val localPixels = analyzeThreadViewportBitmap(localBitmap)
                                    val geometryStable =
                                        requestStamp == container.threadViewportGeometryStamp() &&
                                            !container.isLayoutRequested
                                    ThreadViewportRenderCaptureResult.Success(
                                        ThreadViewportRenderEvidence(
                                            generation = request.generation,
                                            pixelCopyResult = pixelCopyResult,
                                            captureMode = "window_pixel_copy_and_local_draw",
                                            captureOrder = "window_then_local",
                                            sourceRectInWindow = Rect(sourceRect),
                                            geometryStable = geometryStable,
                                            windowPixels = windowPixels,
                                            localPixels = localPixels,
                                            descendants = census,
                                            stage =
                                                if (geometryStable) {
                                                    classifyThreadViewportRenderStage(
                                                        viewportIntersectingLeafDescendants =
                                                            census.viewportIntersectingLeafDescendants,
                                                        windowPixels = windowPixels,
                                                        localPixels = localPixels,
                                                    )
                                                } else {
                                                    ThreadViewportRenderStage.INCONCLUSIVE
                                                },
                                            rootChildClassName =
                                                container.getChildAt(0)?.javaClass?.name.orEmpty(),
                                        )
                                    )
                                } finally {
                                    if (!localBitmap.isRecycled) localBitmap.recycle()
                                }
                            }.getOrElse {
                                ThreadViewportRenderCaptureResult.Failure(
                                    status = "failed",
                                    message = "Unable to analyze Thread render evidence",
                                )
                            }
                        finish(result)
                    },
                    handler,
                )
            }.onFailure {
                finish(
                    ThreadViewportRenderCaptureResult.Failure(
                        status = "failed",
                        message = "PixelCopy request failed",
                    )
                )
            }
        }
    }
}

private fun captureLegacyLocalThreadViewport(
    container: ViewGroup,
    request: ThreadViewportCaptureRequest,
    onResult: (ThreadViewportRenderCaptureResult) -> Unit,
) {
    val localBitmap =
        runCatching {
            Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            onResult(
                ThreadViewportRenderCaptureResult.Failure(
                    status = "failed",
                    message = "Unable to allocate local pixel probe",
                )
            )
            return
        }
    val result =
        runCatching {
            val canvas = Canvas(localBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.translate(-request.left.toFloat(), -request.top.toFloat())
            container.draw(canvas)
            val pixels = analyzeThreadViewportBitmap(localBitmap)
            val census = censusThreadViewportDescendants(container, request)
            val locationInWindow = IntArray(2)
            container.getLocationInWindow(locationInWindow)
            ThreadViewportRenderCaptureResult.Success(
                ThreadViewportRenderEvidence(
                    generation = request.generation,
                    pixelCopyResult = PixelCopy.SUCCESS,
                    captureMode = "local_draw_legacy",
                    captureOrder = "local_only",
                    sourceRectInWindow =
                        Rect(
                            locationInWindow[0] + request.left,
                            locationInWindow[1] + request.top,
                            locationInWindow[0] + request.left + request.width,
                            locationInWindow[1] + request.top + request.height,
                        ),
                    geometryStable = !container.isLayoutRequested,
                    windowPixels = pixels,
                    localPixels = pixels,
                    descendants = census,
                    stage = ThreadViewportRenderStage.LOCAL_ONLY_LEGACY,
                    rootChildClassName = container.getChildAt(0)?.javaClass?.name.orEmpty(),
                )
            )
        }.getOrElse {
            ThreadViewportRenderCaptureResult.Failure(
                status = "failed",
                message = "Unable to draw local pixel probe",
            )
        }
    if (!localBitmap.isRecycled) localBitmap.recycle()
    onResult(result)
}

internal fun analyzeThreadViewportBitmap(bitmap: Bitmap): ThreadViewportPixelStats {
    var sampleCount = 0
    var nonWhiteCount = 0
    var darkCount = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            sampleCount += 1
            if (red < 248 || green < 248 || blue < 248) nonWhiteCount += 1
            if (red <= 64 && green <= 64 && blue <= 64) darkCount += 1
            x += THREAD_VIEWPORT_SAMPLE_STEP_PX
        }
        y += THREAD_VIEWPORT_SAMPLE_STEP_PX
    }
    val whiteCount = (sampleCount - nonWhiteCount).coerceAtLeast(0)
    val whitePermille =
        if (sampleCount > 0) {
            ((whiteCount.toLong() * 1_000L) / sampleCount.toLong()).toInt()
        } else {
            1_000
        }
    return ThreadViewportPixelStats(
        sampleCount = sampleCount,
        nonWhiteCount = nonWhiteCount,
        darkCount = darkCount,
        whitePermille = whitePermille,
    )
}

internal fun censusThreadViewportDescendants(
    container: ViewGroup,
    request: ThreadViewportCaptureRequest,
): ThreadViewportDescendantCensus {
    val containerLocationOnScreen = IntArray(2)
    container.getLocationOnScreen(containerLocationOnScreen)
    val viewportOnScreen =
        Rect(
            containerLocationOnScreen[0] + request.left,
            containerLocationOnScreen[1] + request.top,
            containerLocationOnScreen[0] + request.left + request.width,
            containerLocationOnScreen[1] + request.top + request.height,
        )
    val pending = ArrayDeque<DescendantTraversal>()
    for (index in 0 until container.childCount) {
        pending.addLast(
            DescendantTraversal(
                view = container.getChildAt(index),
                parentEffectivelyVisible = true,
                parentEffectiveAlpha = 1f,
            )
        )
    }

    var descendants = 0
    var visibleDescendants = 0
    var attachedDescendants = 0
    var layoutRequestedDescendants = 0
    var viewportIntersectingDescendants = 0
    var viewportIntersectingLeafDescendants = 0
    var viewportIntersectingPaintCandidates = 0
    var viewportIntersectionAreaPx = 0L
    val examples = mutableListOf<DescendantIntersectionExample>()

    while (pending.isNotEmpty()) {
        val traversal = pending.removeFirst()
        val view = traversal.view
        descendants += 1
        if (view.visibility == View.VISIBLE && view.alpha > 0f) visibleDescendants += 1
        if (view.isAttachedToWindow) attachedDescendants += 1
        if (view.isLayoutRequested) layoutRequestedDescendants += 1

        val effectivelyVisible =
            traversal.parentEffectivelyVisible && view.visibility == View.VISIBLE
        val effectiveAlpha = traversal.parentEffectiveAlpha * view.alpha.coerceAtLeast(0f)
        val globalVisibleRect = Rect()
        val hasGlobalVisibleRect = view.getGlobalVisibleRect(globalVisibleRect)
        val intersectionRect = Rect(globalVisibleRect)
        val intersectsViewport =
            hasGlobalVisibleRect &&
                intersectionRect.intersect(viewportOnScreen) &&
                !intersectionRect.isEmpty
        val effectiveIntersection =
            view.isAttachedToWindow &&
                effectivelyVisible &&
                effectiveAlpha > THREAD_VIEWPORT_EFFECTIVE_ALPHA_EPSILON &&
                intersectsViewport
        val isLeaf = view !is ViewGroup || view.childCount == 0
        val isPaintCandidate =
            isLeaf || !view.willNotDraw() || view.background != null || view.foreground != null

        if (effectiveIntersection) {
            val area = intersectionRect.width().toLong() * intersectionRect.height().toLong()
            viewportIntersectingDescendants += 1
            if (isLeaf) viewportIntersectingLeafDescendants += 1
            if (isPaintCandidate) viewportIntersectingPaintCandidates += 1
            viewportIntersectionAreaPx += area

            val locationInWindow = IntArray(2)
            view.getLocationInWindow(locationInWindow)
            examples +=
                DescendantIntersectionExample(
                    className = view.javaClass.name.substringAfterLast('.'),
                    identity = System.identityHashCode(view),
                    left = locationInWindow[0],
                    top = locationInWindow[1],
                    right = locationInWindow[0] + view.width,
                    bottom = locationInWindow[1] + view.height,
                    intersectionAreaPx = area,
                    effectiveAlphaPermille =
                        (effectiveAlpha.coerceIn(0f, 1f) * 1_000f).roundToInt(),
                    leaf = isLeaf,
                    paintCandidate = isPaintCandidate,
                    layoutRequested = view.isLayoutRequested,
                )
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                pending.addLast(
                    DescendantTraversal(
                        view = view.getChildAt(index),
                        parentEffectivelyVisible = effectivelyVisible,
                        parentEffectiveAlpha = effectiveAlpha,
                    )
                )
            }
        }
    }

    val intersectingSummary =
        examples
            .sortedWith(
                compareByDescending<DescendantIntersectionExample> { it.leaf }
                    .thenByDescending { it.intersectionAreaPx }
                    .thenBy { it.className }
            )
            .take(THREAD_VIEWPORT_DESCENDANT_EXAMPLE_LIMIT)
            .joinToString(separator = ";") { example -> example.toStableSummary() }

    return ThreadViewportDescendantCensus(
        descendants = descendants,
        visibleDescendants = visibleDescendants,
        attachedDescendants = attachedDescendants,
        layoutRequestedDescendants = layoutRequestedDescendants,
        viewportIntersectingDescendants = viewportIntersectingDescendants,
        viewportIntersectingLeafDescendants = viewportIntersectingLeafDescendants,
        viewportIntersectingPaintCandidates = viewportIntersectingPaintCandidates,
        viewportIntersectionAreaPx = viewportIntersectionAreaPx,
        intersectingSummary = intersectingSummary,
    )
}

private data class DescendantTraversal(
    val view: View,
    val parentEffectivelyVisible: Boolean,
    val parentEffectiveAlpha: Float,
)

private data class DescendantIntersectionExample(
    val className: String,
    val identity: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val intersectionAreaPx: Long,
    val effectiveAlphaPermille: Int,
    val leaf: Boolean,
    val paintCandidate: Boolean,
    val layoutRequested: Boolean,
) {
    fun toStableSummary(): String =
        "$className@$identity:" +
            "bounds=$left,$top,$right,$bottom:" +
            "area=$intersectionAreaPx:" +
            "alphaPermille=$effectiveAlphaPermille:" +
            "leaf=$leaf:paint=$paintCandidate:layoutRequested=$layoutRequested"
}

private data class ThreadViewportGeometryStamp(
    val attached: Boolean,
    val width: Int,
    val height: Int,
    val leftInWindow: Int,
    val topInWindow: Int,
    val childCount: Int,
    val windowTokenIdentity: Int,
)

private fun ViewGroup.threadViewportGeometryStamp(): ThreadViewportGeometryStamp {
    val locationInWindow = IntArray(2)
    getLocationInWindow(locationInWindow)
    return ThreadViewportGeometryStamp(
        attached = isAttachedToWindow,
        width = width,
        height = height,
        leftInWindow = locationInWindow[0],
        topInWindow = locationInWindow[1],
        childCount = childCount,
        windowTokenIdentity = windowToken?.let(System::identityHashCode) ?: 0,
    )
}
