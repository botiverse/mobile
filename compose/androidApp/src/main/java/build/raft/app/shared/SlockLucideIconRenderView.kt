package build.raft.app.shared

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import build.raft.app.shared.generated.SlockLucideFillRule
import build.raft.app.shared.generated.SlockLucideIconSpec
import build.raft.app.shared.generated.SlockLucidePathIcons
import build.raft.app.shared.generated.SlockLucidePathSpec
import build.raft.app.shared.generated.SlockLucideStrokeLineCap
import build.raft.app.shared.generated.SlockLucideStrokeLineJoin
import com.tencent.kuikly.core.render.android.expand.component.KRView

internal class SlockLucideIconRenderView(context: Context) : KRView(context) {
    private val iconView = SlockLucideCanvasView(context)

    init {
        clipChildren = false
        clipToPadding = false
        addView(iconView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            PROP_NAME -> iconView.iconName = propValue.toString()
            PROP_COLOR -> iconView.tint = parseColor(propValue)
            PROP_FILL -> iconView.fillTint = parseOptionalColor(propValue)
            PROP_STROKE_WIDTH -> iconView.strokeWidthOverride = parseStrokeWidth(propValue)
            PROP_VERTICAL_OFFSET_DP ->
                iconView.verticalOffsetPx = parseVerticalOffset(propValue) * resources.displayMetrics.density
            PROP_SPINNING -> iconView.spinning = parseBoolean(propValue)
            PROP_ROTATION_DEGREES -> iconView.staticRotationDegrees = parseRotationDegrees(propValue)
            else -> return super.setProp(propKey, propValue)
        }
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        when (propKey) {
            PROP_NAME -> iconView.iconName = ""
            PROP_COLOR -> iconView.tint = Color.BLACK
            PROP_FILL -> iconView.fillTint = null
            PROP_STROKE_WIDTH -> iconView.strokeWidthOverride = null
            PROP_VERTICAL_OFFSET_DP -> iconView.verticalOffsetPx = 0f
            PROP_SPINNING -> iconView.spinning = false
            PROP_ROTATION_DEGREES -> iconView.staticRotationDegrees = 0f
            else -> return super.resetProp(propKey)
        }
        return true
    }

    override fun onDestroy() {
        iconView.destroy()
        super.onDestroy()
    }

    private fun parseColor(value: Any): Int =
        runCatching {
            when (value) {
                is Number -> value.toInt()
                is String -> Color.parseColor(value)
                else -> Color.BLACK
            }
        }.getOrDefault(Color.BLACK)

    private fun parseOptionalColor(value: Any): Int? {
        val raw = value.toString()
        return raw.takeIf { it.isNotBlank() }?.let(::parseColor)
    }

    private fun parseStrokeWidth(value: Any): Float? =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }?.takeIf { it > 0f }

    private fun parseVerticalOffset(value: Any): Float =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: 0f
            else -> 0f
        }

    private fun parseBoolean(value: Any): Boolean =
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }

    private fun parseRotationDegrees(value: Any): Float =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: 0f
            else -> 0f
        }

    companion object {
        const val VIEW_NAME = "SlockLucideIconView"
        private const val PROP_NAME = "name"
        private const val PROP_COLOR = "color"
        private const val PROP_FILL = "fill"
        private const val PROP_STROKE_WIDTH = "strokeWidth"
        private const val PROP_VERTICAL_OFFSET_DP = "verticalOffsetDp"
        private const val PROP_SPINNING = "spinning"
        private const val PROP_ROTATION_DEGREES = "rotationDegrees"
    }
}

private class SlockLucideCanvasView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var renderedIcon: SlockRenderedLucideIcon? = null
    private var renderedIconKey: String? = null
    private var spinAnimator: ObjectAnimator? = null
    private var destroyed = false

    var iconName: String = ""
        set(value) {
            if (field == value) return
            field = value
            resolveIcon()
        }

    var tint: Int = Color.BLACK
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var fillTint: Int? = null
        set(value) {
            if (field == value) return
            field = value
            resolveIcon()
        }

    var strokeWidthOverride: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var verticalOffsetPx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var spinning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncRotationAnimation()
        }

    var staticRotationDegrees: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            if (!spinning) rotation = value
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val icon = renderedIcon ?: return
        val viewBox = icon.spec.viewBox
        if (width <= 0 || height <= 0 || viewBox.width <= 0f || viewBox.height <= 0f) return

        val scale = minOf(width / viewBox.width, height / viewBox.height)
        val offsetX = (width - viewBox.width * scale) / 2f - viewBox.minX * scale
        val offsetY = (height - viewBox.height * scale) / 2f - viewBox.minY * scale + verticalOffsetPx
        val drawTint = fillTint ?: tint

        val checkpoint = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        icon.paths.forEach { renderedPath ->
            val spec = renderedPath.spec
            renderedPath.fillPaint?.let { paintValue ->
                configureFillPaint(paintValue, drawTint, spec.fillOpacity)
                canvas.drawPath(renderedPath.path, fillPaint)
            }
            renderedPath.strokePaint?.let { paintValue ->
                configureStrokePaint(
                    paintValue = paintValue,
                    tint = drawTint,
                    opacity = spec.strokeOpacity,
                    width = resolvedStrokeWidth(icon.spec.name, spec),
                    cap = spec.strokeLineCap,
                    join = spec.strokeLineJoin,
                )
                canvas.drawPath(renderedPath.path, strokePaint)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncRotationAnimation()
    }

    override fun onDetachedFromWindow() {
        stopSpinAnimator()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncRotationAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Lazy rows can attach while an ancestor is not yet aggregated-visible. In that case
        // `spinning = true` and `onAttachedToWindow()` both observe `isShown == false`; the
        // animator otherwise stays stopped after the row becomes visible. Aggregated visibility
        // is the missing lifecycle edge and is delivered independently for every optimistic row.
        syncRotationAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncRotationAnimation()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stopSpinAnimator()
        renderedIcon = null
        renderedIconKey = null
    }

    private fun resolveIcon() {
        val requestedSpec =
            if (fillTint != null) {
                SlockLucidePathIcons.filledByName(iconName) ?: SlockLucidePathIcons.byName(iconName)
            } else {
                SlockLucidePathIcons.byName(iconName)
            }
        val key = requestedSpec?.name
        if (renderedIconKey == key) {
            invalidate()
            return
        }
        renderedIconKey = key
        renderedIcon = key?.let(SlockLucidePathCache::get)
        invalidate()
    }

    private fun resolvedStrokeWidth(iconName: String, spec: SlockLucidePathSpec): Float {
        val override = strokeWidthOverride
        return if (iconName == CHECK_ICON_NAME && override != null && override > 0f) override else spec.strokeWidth
    }

    private fun configureFillPaint(paintValue: SlockCompiledLucidePaint, tint: Int, opacity: Float) {
        val color = paintValue.resolve(tint)
        fillPaint.color = color
        fillPaint.alpha = resolvedAlpha(color, opacity)
    }

    private fun configureStrokePaint(
        paintValue: SlockCompiledLucidePaint,
        tint: Int,
        opacity: Float,
        width: Float,
        cap: SlockLucideStrokeLineCap,
        join: SlockLucideStrokeLineJoin,
    ) {
        val color = paintValue.resolve(tint)
        strokePaint.color = color
        strokePaint.alpha = resolvedAlpha(color, opacity)
        strokePaint.strokeWidth = width
        strokePaint.strokeCap = cap.toPaintCap()
        strokePaint.strokeJoin = join.toPaintJoin()
    }

    private fun syncRotationAnimation() {
        if (destroyed) {
            stopSpinAnimator()
            return
        }
        val shouldSpin =
            spinning && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE && isShown
        if (!shouldSpin) {
            stopSpinAnimator()
            rotation = staticRotationDegrees
            return
        }
        if (spinAnimator?.isStarted == true) return
        rotation = 0f
        spinAnimator =
            ObjectAnimator.ofFloat(this, ROTATION, 0f, 360f).apply {
                duration = SPIN_DURATION_MS
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopSpinAnimator() {
        spinAnimator?.cancel()
        spinAnimator = null
    }

    companion object {
        private const val CHECK_ICON_NAME = "check"

        /** Matches `SlockIconSpinDurationSeconds` in shared SlockLucideIcon.kt. */
        private const val SPIN_DURATION_MS = 900L
    }
}

private data class SlockRenderedLucidePath(
    val spec: SlockLucidePathSpec,
    val path: Path,
    val fillPaint: SlockCompiledLucidePaint?,
    val strokePaint: SlockCompiledLucidePaint?,
)

private data class SlockRenderedLucideIcon(
    val spec: SlockLucideIconSpec,
    val paths: List<SlockRenderedLucidePath>,
)

private object SlockLucidePathCache {
    private val cache = mutableMapOf<String, SlockRenderedLucideIcon>()

    fun get(name: String): SlockRenderedLucideIcon? =
        cache[name] ?: create(name)?.also { cache[name] = it }

    private fun create(name: String): SlockRenderedLucideIcon? {
        val spec = SlockLucidePathIcons.byName(name) ?: return null
        val paths =
            spec.paths.map { pathSpec ->
                val path = requireNotNull(PathParser.createPathFromPathData(pathSpec.pathData)) {
                    "Pinned Lucide path failed to parse: ${spec.name}"
                }
                path.fillType =
                    when (pathSpec.fillRule) {
                        SlockLucideFillRule.NonZero -> Path.FillType.WINDING
                        SlockLucideFillRule.EvenOdd -> Path.FillType.EVEN_ODD
                    }
                SlockRenderedLucidePath(
                    spec = pathSpec,
                    path = path,
                    fillPaint = pathSpec.fillPaint?.let(::compileLucidePaint),
                    strokePaint = pathSpec.strokePaint?.let(::compileLucidePaint),
                )
            }
        return SlockRenderedLucideIcon(spec, paths)
    }
}

private sealed interface SlockCompiledLucidePaint {
    data object CurrentColor : SlockCompiledLucidePaint

    @JvmInline
    value class Direct(val argb: Int) : SlockCompiledLucidePaint
}

private fun compileLucidePaint(value: String): SlockCompiledLucidePaint =
    if (value == "currentColor") {
        SlockCompiledLucidePaint.CurrentColor
    } else {
        SlockCompiledLucidePaint.Direct(parseLucideDirectPaintColor(value))
    }

internal fun parseLucideDirectPaintColor(value: String): Int {
    val expanded =
        if (value.length == 4 && value[0] == '#') {
            charArrayOf('#', value[1], value[1], value[2], value[2], value[3], value[3]).concatToString()
        } else {
            value
        }
    return Color.parseColor(expanded)
}

private fun SlockCompiledLucidePaint.resolve(tint: Int): Int =
    when (this) {
        SlockCompiledLucidePaint.CurrentColor -> tint
        is SlockCompiledLucidePaint.Direct -> argb
    }

private fun resolvedAlpha(color: Int, opacity: Float): Int =
    (Color.alpha(color) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

private fun SlockLucideStrokeLineCap.toPaintCap(): Paint.Cap =
    when (this) {
        SlockLucideStrokeLineCap.Butt -> Paint.Cap.BUTT
        SlockLucideStrokeLineCap.Round -> Paint.Cap.ROUND
        SlockLucideStrokeLineCap.Square -> Paint.Cap.SQUARE
    }

private fun SlockLucideStrokeLineJoin.toPaintJoin(): Paint.Join =
    when (this) {
        SlockLucideStrokeLineJoin.Miter -> Paint.Join.MITER
        SlockLucideStrokeLineJoin.Round -> Paint.Join.ROUND
        SlockLucideStrokeLineJoin.Bevel -> Paint.Join.BEVEL
    }
