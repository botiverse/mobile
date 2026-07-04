package build.raft.app.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.tencent.kuikly.core.render.android.expand.component.KRView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class SlockNativeTiltedBadgeChromeRenderView(context: Context) : KRView(context) {
    private var fillColor: Int = Color.BLACK
    private var borderColor: Int = Color.BLACK
    private var shadowColor: Int = Color.rgb(20, 17, 17)
    private var strokeWidthDp: Float = 2f
    private var shadowOffsetDp: Float = 2f
    private var tiltDegrees: Float = -2f
    private var supersample: Int = 3
    private var useDrawFilter: Boolean = false
    private var layerTypeMode: String = LAYER_TYPE_NONE
    private var geometryMode: String = GEOMETRY_PATH
    private var viewRotationDegrees: Float = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        isDither = true
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
        isDither = true
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val antiAliasDrawFilter =
        PaintFlagsDrawFilter(
            0,
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
        )

    init {
        setWillNotDraw(false)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        val changed =
            when (propKey) {
                PROP_FILL -> updateFill(propValue.toString())
                PROP_BORDER -> updateBorder(propValue.toString())
                PROP_SHADOW -> updateShadow(propValue.toString())
                PROP_STROKE_WIDTH -> updateStrokeWidth(propValue)
                PROP_SHADOW_OFFSET -> updateShadowOffset(propValue)
                PROP_TILT_DEGREES -> updateTiltDegrees(propValue)
                PROP_SUPERSAMPLE -> updateSupersample(propValue)
                PROP_DRAW_FILTER -> updateDrawFilter(propValue)
                PROP_LAYER_TYPE -> updateLayerType(propValue.toString())
                PROP_GEOMETRY -> updateGeometry(propValue.toString())
                PROP_VIEW_ROTATION -> updateViewRotation(propValue)
                else -> return super.setProp(propKey, propValue)
            }
        if (changed) invalidate()
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        val changed =
            when (propKey) {
                PROP_FILL -> updateFill("#FF000000")
                PROP_BORDER -> updateBorder("#FF000000")
                PROP_SHADOW -> updateShadow("#FF141111")
                PROP_STROKE_WIDTH -> updateStrokeWidth(2f)
                PROP_SHADOW_OFFSET -> updateShadowOffset(2f)
                PROP_TILT_DEGREES -> updateTiltDegrees(-2f)
                PROP_SUPERSAMPLE -> updateSupersample(3)
                PROP_DRAW_FILTER -> updateDrawFilter(false)
                PROP_LAYER_TYPE -> updateLayerType(LAYER_TYPE_NONE)
                PROP_GEOMETRY -> updateGeometry(GEOMETRY_PATH)
                PROP_VIEW_ROTATION -> updateViewRotation(0f)
                else -> return super.resetProp(propKey)
            }
        if (changed) invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0) return
        val scale = supersample.coerceAtLeast(1)
        if (scale == 1) {
            drawChromeWithFilter(canvas, viewWidth.toFloat(), viewHeight.toFloat())
            return
        }
        val bitmap = Bitmap.createBitmap(viewWidth * scale, viewHeight * scale, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { offscreen ->
            offscreen.scale(scale.toFloat(), scale.toFloat())
            drawChromeWithFilter(offscreen, viewWidth.toFloat(), viewHeight.toFloat())
        }
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()), bitmapPaint)
        bitmap.recycle()
    }

    private fun drawChromeWithFilter(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val previous = canvas.drawFilter
        if (useDrawFilter) {
            canvas.drawFilter = antiAliasDrawFilter
        }
        drawChrome(canvas, viewWidth, viewHeight)
        if (useDrawFilter) {
            canvas.drawFilter = previous
        }
    }

    private fun drawChrome(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val density = resources.displayMetrics.density
        val strokeWidth = (strokeWidthDp * density).coerceAtLeast(1f)
        val shadowOffset = shadowOffsetDp * density
        val inset = strokeWidth / 2f
        if (geometryMode == GEOMETRY_RECT) {
            drawRectChrome(canvas, viewWidth, viewHeight, strokeWidth, shadowOffset)
            return
        }
        if (geometryMode == GEOMETRY_ROTATED_RECT) {
            drawRotatedRectChrome(canvas, viewWidth, viewHeight, strokeWidth, shadowOffset)
            return
        }
        if (geometryMode == GEOMETRY_FILLED_PATH) {
            drawFilledPathChrome(canvas, viewWidth, viewHeight, strokeWidth, shadowOffset)
            return
        }
        val shadowPath =
            tiltedBadgePath(
                width = viewWidth,
                height = viewHeight,
                inset = inset,
                offsetX = shadowOffset,
                offsetY = shadowOffset
            )
        val bodyPath = tiltedBadgePath(width = viewWidth, height = viewHeight, inset = inset)
        fillPaint.color = shadowColor
        canvas.drawPath(shadowPath, fillPaint)
        fillPaint.color = fillColor
        canvas.drawPath(bodyPath, fillPaint)
        strokePaint.color = borderColor
        strokePaint.strokeWidth = strokeWidth
        canvas.drawPath(bodyPath, strokePaint)
    }

    private fun drawFilledPathChrome(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        strokeWidth: Float,
        shadowOffset: Float
    ) {
        val shadowPath =
            tiltedBadgePath(
                width = viewWidth,
                height = viewHeight,
                inset = 0f,
                offsetX = shadowOffset,
                offsetY = shadowOffset
            )
        val outerPath = tiltedBadgePath(width = viewWidth, height = viewHeight, inset = 0f)
        val innerPath = tiltedBadgePath(width = viewWidth, height = viewHeight, inset = strokeWidth)
        fillPaint.color = shadowColor
        canvas.drawPath(shadowPath, fillPaint)
        fillPaint.color = borderColor
        canvas.drawPath(outerPath, fillPaint)
        fillPaint.color = fillColor
        canvas.drawPath(innerPath, fillPaint)
    }

    private fun drawRotatedRectChrome(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        strokeWidth: Float,
        shadowOffset: Float
    ) {
        val contentWidth = (viewWidth - shadowOffset).coerceAtLeast(1f)
        val contentHeight = (viewHeight - shadowOffset).coerceAtLeast(1f)
        val rect = RectF(0f, 0f, contentWidth, contentHeight)
        val shadowRect = RectF(shadowOffset, shadowOffset, shadowOffset + contentWidth, shadowOffset + contentHeight)
        val pivotX = contentWidth / 2f
        val pivotY = contentHeight / 2f
        canvas.save()
        canvas.rotate(tiltDegrees, pivotX, pivotY)
        fillPaint.color = shadowColor
        canvas.drawRect(shadowRect, fillPaint)
        fillPaint.color = fillColor
        canvas.drawRect(rect, fillPaint)
        strokePaint.color = borderColor
        strokePaint.strokeWidth = strokeWidth
        canvas.drawRect(rect, strokePaint)
        canvas.restore()
    }

    private fun drawRectChrome(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        strokeWidth: Float,
        shadowOffset: Float
    ) {
        val contentWidth = (viewWidth - shadowOffset).coerceAtLeast(1f)
        val contentHeight = (viewHeight - shadowOffset).coerceAtLeast(1f)
        val rect = RectF(0f, 0f, contentWidth, contentHeight)
        val shadowRect = RectF(shadowOffset, shadowOffset, shadowOffset + contentWidth, shadowOffset + contentHeight)
        fillPaint.color = shadowColor
        canvas.drawRect(shadowRect, fillPaint)
        fillPaint.color = fillColor
        canvas.drawRect(rect, fillPaint)
        strokePaint.color = borderColor
        strokePaint.strokeWidth = strokeWidth
        canvas.drawRect(rect, strokePaint)
    }

    private fun tiltedBadgePath(
        width: Float,
        height: Float,
        inset: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): Path {
        val safeWidth = (width - offsetX).coerceAtLeast(1f)
        val safeHeight = (height - offsetY).coerceAtLeast(1f)
        val targetWidth = (safeWidth - inset * 2f).coerceAtLeast(1f)
        val targetHeight = (safeHeight - inset * 2f).coerceAtLeast(1f)
        val angle = (tiltDegrees * PI / 180.0).toFloat()
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val denominator = cosAngle * cosAngle - abs(sinAngle) * abs(sinAngle)
        val rectWidth = ((targetWidth * cosAngle - targetHeight * abs(sinAngle)) / denominator).coerceAtLeast(1f)
        val rectHeight = ((targetHeight * cosAngle - targetWidth * abs(sinAngle)) / denominator).coerceAtLeast(1f)
        val centerX = offsetX + safeWidth / 2f
        val centerY = offsetY + safeHeight / 2f
        val halfWidth = rectWidth / 2f
        val halfHeight = rectHeight / 2f
        val corners =
            listOf(
                rotateBadgePoint(-halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
                rotateBadgePoint(halfWidth, -halfHeight, cosAngle, sinAngle, centerX, centerY),
                rotateBadgePoint(halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY),
                rotateBadgePoint(-halfWidth, halfHeight, cosAngle, sinAngle, centerX, centerY)
            )
        return Path().apply {
            moveTo(corners[0].x, corners[0].y)
            lineTo(corners[1].x, corners[1].y)
            lineTo(corners[2].x, corners[2].y)
            lineTo(corners[3].x, corners[3].y)
            close()
        }
    }

    private fun rotateBadgePoint(
        x: Float,
        y: Float,
        cosAngle: Float,
        sinAngle: Float,
        centerX: Float,
        centerY: Float
    ): BadgePoint =
        BadgePoint(
            x = centerX + x * cosAngle - y * sinAngle,
            y = centerY + x * sinAngle + y * cosAngle
        )

    private fun updateFill(value: String): Boolean {
        val next = parseColor(value)
        if (fillColor == next) return false
        fillColor = next
        return true
    }

    private fun updateBorder(value: String): Boolean {
        val next = parseColor(value)
        if (borderColor == next) return false
        borderColor = next
        return true
    }

    private fun updateShadow(value: String): Boolean {
        val next = parseColor(value)
        if (shadowColor == next) return false
        shadowColor = next
        return true
    }

    private fun updateStrokeWidth(value: Any): Boolean {
        val next = value.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: return false
        if (strokeWidthDp == next) return false
        strokeWidthDp = next
        return true
    }

    private fun updateShadowOffset(value: Any): Boolean {
        val next = value.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: return false
        if (shadowOffsetDp == next) return false
        shadowOffsetDp = next
        return true
    }

    private fun updateTiltDegrees(value: Any): Boolean {
        val next = value.toString().toFloatOrNull() ?: return false
        if (tiltDegrees == next) return false
        tiltDegrees = next
        return true
    }

    private fun updateSupersample(value: Any): Boolean {
        val next = value.toString().toFloatOrNull()?.roundToInt()?.coerceIn(1, 4) ?: return false
        if (supersample == next) return false
        supersample = next
        return true
    }

    private fun updateDrawFilter(value: Any): Boolean {
        val next = value.toString().toBooleanStrictOrNull() ?: false
        if (useDrawFilter == next) return false
        useDrawFilter = next
        return true
    }

    private fun updateLayerType(value: String): Boolean {
        val next =
            when (value.lowercase()) {
                LAYER_TYPE_HARDWARE -> LAYER_TYPE_HARDWARE
                LAYER_TYPE_SOFTWARE -> LAYER_TYPE_SOFTWARE
                else -> LAYER_TYPE_NONE
            }
        if (layerTypeMode == next) return false
        layerTypeMode = next
        val nativeLayerType =
            when (next) {
                LAYER_TYPE_HARDWARE -> View.LAYER_TYPE_HARDWARE
                LAYER_TYPE_SOFTWARE -> View.LAYER_TYPE_SOFTWARE
                else -> View.LAYER_TYPE_NONE
            }
        setLayerType(nativeLayerType, null)
        return true
    }

    private fun updateGeometry(value: String): Boolean {
        val next =
            when (value.lowercase()) {
                GEOMETRY_ROTATED_RECT -> GEOMETRY_ROTATED_RECT
                GEOMETRY_RECT -> GEOMETRY_RECT
                GEOMETRY_FILLED_PATH -> GEOMETRY_FILLED_PATH
                else -> GEOMETRY_PATH
            }
        if (geometryMode == next) return false
        geometryMode = next
        return true
    }

    private fun updateViewRotation(value: Any): Boolean {
        val next = value.toString().toFloatOrNull() ?: return false
        if (viewRotationDegrees == next) return false
        viewRotationDegrees = next
        rotation = next
        return true
    }

    private fun parseColor(value: String): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(Color.TRANSPARENT)

    private data class BadgePoint(val x: Float, val y: Float)

    companion object {
        const val VIEW_NAME = "SlockNativeTiltedBadgeChromeView"
        private const val PROP_FILL = "fill"
        private const val PROP_BORDER = "border"
        private const val PROP_SHADOW = "shadow"
        private const val PROP_STROKE_WIDTH = "strokeWidth"
        private const val PROP_SHADOW_OFFSET = "shadowOffset"
        private const val PROP_TILT_DEGREES = "tiltDegrees"
        private const val PROP_SUPERSAMPLE = "supersample"
        private const val PROP_DRAW_FILTER = "drawFilter"
        private const val PROP_LAYER_TYPE = "layerType"
        private const val PROP_GEOMETRY = "geometry"
        private const val PROP_VIEW_ROTATION = "viewRotation"
        private const val LAYER_TYPE_NONE = "none"
        private const val LAYER_TYPE_HARDWARE = "hardware"
        private const val LAYER_TYPE_SOFTWARE = "software"
        private const val GEOMETRY_PATH = "path"
        private const val GEOMETRY_FILLED_PATH = "filledpath"
        private const val GEOMETRY_ROTATED_RECT = "rotatedrect"
        private const val GEOMETRY_RECT = "rect"
    }
}
