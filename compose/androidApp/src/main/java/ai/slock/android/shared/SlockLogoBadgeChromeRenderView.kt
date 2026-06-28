package ai.slock.android.shared

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.tencent.kuikly.core.render.android.expand.component.KRView

internal class SlockLogoBadgeChromeRenderView(context: Context) : KRView(context) {
    private var fillColor: Int = Color.BLACK
    private var borderColor: Int = Color.BLACK
    private var shadowColor: Int = Color.BLACK
    private var strokeWidthDp: Float = 1f
    private var shadowOffsetDp: Float = 2f
    private var rotationDegrees: Float = -2f

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            isDither = true
        }
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            isDither = true
        }

    init {
        setWillNotDraw(false)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        val changed =
            when (propKey) {
                PROP_FILL_COLOR -> updateFillColor(propValue.toString())
                PROP_BORDER_COLOR -> updateBorderColor(propValue.toString())
                PROP_SHADOW_COLOR -> updateShadowColor(propValue.toString())
                PROP_STROKE_WIDTH_DP -> updateStrokeWidth(propValue)
                PROP_SHADOW_OFFSET_DP -> updateShadowOffset(propValue)
                PROP_ROTATION_DEGREES -> updateRotation(propValue)
                else -> return super.setProp(propKey, propValue)
            }
        if (changed) {
            invalidate()
        }
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        val changed =
            when (propKey) {
                PROP_FILL_COLOR -> updateFillColor("#FF000000")
                PROP_BORDER_COLOR -> updateBorderColor("#FF000000")
                PROP_SHADOW_COLOR -> updateShadowColor("#FF000000")
                PROP_STROKE_WIDTH_DP -> updateStrokeWidth(1f)
                PROP_SHADOW_OFFSET_DP -> updateShadowOffset(2f)
                PROP_ROTATION_DEGREES -> updateRotation(-2f)
                else -> return super.resetProp(propKey)
            }
        if (changed) {
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return

        val density = resources.displayMetrics.density
        val shadow = (shadowOffsetDp * density).coerceAtLeast(0f)
        val stroke = (strokeWidthDp * density).coerceAtLeast(0f)
        val halfStroke = stroke / 2f
        val badgeWidth = (widthPx - shadow).coerceAtLeast(0f)
        val badgeHeight = (heightPx - shadow).coerceAtLeast(0f)
        if (badgeWidth <= 0f || badgeHeight <= 0f) return

        canvas.save()
        canvas.rotate(rotationDegrees, badgeWidth / 2f, badgeHeight / 2f)
        fillPaint.color = shadowColor
        canvas.drawRect(shadow, shadow, shadow + badgeWidth, shadow + badgeHeight, fillPaint)

        fillPaint.color = fillColor
        canvas.drawRect(0f, 0f, badgeWidth, badgeHeight, fillPaint)
        if (stroke > 0f && Color.alpha(borderColor) > 0) {
            strokePaint.color = borderColor
            strokePaint.strokeWidth = stroke
            canvas.drawRect(halfStroke, halfStroke, badgeWidth - halfStroke, badgeHeight - halfStroke, strokePaint)
        }
        canvas.restore()
    }

    private fun updateFillColor(value: String): Boolean {
        val next = parseColor(value, Color.BLACK)
        if (fillColor == next) return false
        fillColor = next
        return true
    }

    private fun updateBorderColor(value: String): Boolean {
        val next = parseColor(value, Color.BLACK)
        if (borderColor == next) return false
        borderColor = next
        return true
    }

    private fun updateShadowColor(value: String): Boolean {
        val next = parseColor(value, Color.BLACK)
        if (shadowColor == next) return false
        shadowColor = next
        return true
    }

    private fun updateStrokeWidth(value: Any): Boolean {
        val next = value.toFloatValue(default = 1f).coerceAtLeast(0f)
        if (strokeWidthDp == next) return false
        strokeWidthDp = next
        return true
    }

    private fun updateShadowOffset(value: Any): Boolean {
        val next = value.toFloatValue(default = 2f).coerceAtLeast(0f)
        if (shadowOffsetDp == next) return false
        shadowOffsetDp = next
        return true
    }

    private fun updateRotation(value: Any): Boolean {
        val next = value.toFloatValue(default = -2f)
        if (rotationDegrees == next) return false
        rotationDegrees = next
        return true
    }

    private fun parseColor(value: String, default: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(default)

    private fun Any.toFloatValue(default: Float): Float =
        when (this) {
            is Number -> toFloat()
            is String -> toFloatOrNull() ?: default
            else -> default
        }

    companion object {
        const val VIEW_NAME = "SlockLogoBadgeChromeView"
        private const val PROP_FILL_COLOR = "fillColor"
        private const val PROP_BORDER_COLOR = "borderColor"
        private const val PROP_SHADOW_COLOR = "shadowColor"
        private const val PROP_STROKE_WIDTH_DP = "strokeWidthDp"
        private const val PROP_SHADOW_OFFSET_DP = "shadowOffsetDp"
        private const val PROP_ROTATION_DEGREES = "rotationDegrees"
    }
}
