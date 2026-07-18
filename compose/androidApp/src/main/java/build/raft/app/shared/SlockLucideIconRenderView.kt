package build.raft.app.shared

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import build.raft.app.ui.icons.LucideIcons
import com.tencent.kuikly.core.render.android.expand.component.KRView

internal class SlockLucideIconRenderView(context: Context) : KRView(context) {
    private val composeView = ComposeView(context)
    private var iconName: String = ""
    private var tint: Color = Color.Black
    private var fillTint: Color? = null
    private var strokeWidthOverride: Float? = null
    private var verticalOffsetPx: Float = 0f
    private var renderedVectorKey: IconVectorKey? = null
    private var renderedVector: ImageVector? by mutableStateOf<ImageVector?>(null)
    private var renderedTint: Color by mutableStateOf(Color.Black)
    private var renderedVerticalOffsetPx: Float by mutableStateOf(0f)
    private var spinning: Boolean = false
    private var rotationDegrees: Float = 0f
    private var spinAnimator: ObjectAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(
                composeView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.setContent {
            val vector = renderedVector
            if (vector != null) {
                LucideVectorIcon(vector = vector, tint = renderedTint, verticalOffsetPx = renderedVerticalOffsetPx)
            }
        }
        renderIcon()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        val changed =
                when (propKey) {
                    PROP_NAME -> updateIconName(propValue.toString())
                    PROP_COLOR -> updateTint(parseColor(propValue))
                    PROP_FILL -> updateFillTint(parseOptionalColor(propValue))
                    PROP_STROKE_WIDTH -> updateStrokeWidth(parseStrokeWidth(propValue))
                    PROP_VERTICAL_OFFSET_DP -> updateVerticalOffset(parseVerticalOffset(propValue))
                    PROP_SPINNING -> updateSpinning(parseBoolean(propValue))
                    PROP_ROTATION_DEGREES -> updateRotationDegrees(parseRotationDegrees(propValue))
                    else -> return super.setProp(propKey, propValue)
                }
        if (changed) {
            renderIcon()
        }
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        val changed =
                when (propKey) {
                    PROP_NAME -> updateIconName("")
                    PROP_COLOR -> updateTint(Color.Black)
                    PROP_FILL -> updateFillTint(null)
                    PROP_STROKE_WIDTH -> updateStrokeWidth(null)
                    PROP_VERTICAL_OFFSET_DP -> updateVerticalOffset(0f)
                    PROP_SPINNING -> updateSpinning(false)
                    PROP_ROTATION_DEGREES -> updateRotationDegrees(0f)
                    else -> return super.resetProp(propKey)
                }
        if (changed) {
            renderIcon()
        }
        return true
    }

    private fun updateIconName(next: String): Boolean {
        if (iconName == next) {
            return false
        }
        iconName = next
        return true
    }

    private fun updateTint(next: Color): Boolean {
        if (tint == next) {
            return false
        }
        tint = next
        return true
    }

    private fun updateFillTint(next: Color?): Boolean {
        if (fillTint == next) {
            return false
        }
        fillTint = next
        return true
    }

    private fun updateStrokeWidth(next: Float?): Boolean {
        if (strokeWidthOverride == next) {
            return false
        }
        strokeWidthOverride = next
        return true
    }

    private fun updateVerticalOffset(nextDp: Float): Boolean {
        val nextPx = nextDp * resources.displayMetrics.density
        if (verticalOffsetPx == nextPx) {
            return false
        }
        verticalOffsetPx = nextPx
        renderedVerticalOffsetPx = nextPx
        return true
    }

    private fun updateSpinning(next: Boolean): Boolean {
        if (spinning == next) {
            return false
        }
        spinning = next
        applyRotationState()
        return false
    }

    private fun updateRotationDegrees(next: Float): Boolean {
        if (rotationDegrees == next) {
            return false
        }
        rotationDegrees = next
        applyRotationState()
        return false
    }

    private fun applyRotationState() {
        if (spinning) {
            if (spinAnimator == null) {
                spinAnimator =
                        ObjectAnimator.ofFloat(composeView, View.ROTATION, 0f, 360f).apply {
                            duration = SPIN_DURATION_MS
                            repeatCount = ObjectAnimator.INFINITE
                            repeatMode = ObjectAnimator.RESTART
                            interpolator = LinearInterpolator()
                        }
            }
            composeView.rotation = 0f
            spinAnimator?.start()
        } else {
            spinAnimator?.cancel()
            composeView.rotation = rotationDegrees
        }
    }

    override fun onDestroy() {
        spinAnimator?.cancel()
        spinAnimator = null
        composeView.disposeComposition()
        super.onDestroy()
    }

    private fun renderIcon() {
        val resolvedFillTint = fillTint
        val vectorKey = IconVectorKey(iconName, strokeWidthOverride, resolvedFillTint != null)
        if (renderedVectorKey != vectorKey) {
            renderedVectorKey = vectorKey
            renderedVector = vectorFor(iconName, strokeWidthOverride, resolvedFillTint != null)
        }
        val nextTint = resolvedFillTint ?: tint
        if (renderedTint != nextTint) {
            renderedTint = nextTint
        }
    }

    private fun vectorFor(name: String, strokeWidth: Float?, fillEnabled: Boolean): ImageVector? {
        val iconKey = name.toIconKey()
        if (fillEnabled && iconKey == "bookmark") {
            return LucideIcons.FilledBookmark
        }
        val requestedWidth = strokeWidth?.takeIf { it > 0f }
        if (iconKey == "check" && requestedWidth != null) {
            return checkVector(requestedWidth)
        }
        return LucideIcons.byName(name)
    }

    private fun checkVector(strokeWidth: Float): ImageVector =
            ImageVector.Builder(
                            name = "LucideCheckStroke$strokeWidth",
                            defaultWidth = 24.0.dp,
                            defaultHeight = 24.0.dp,
                            viewportWidth = 24.0f,
                            viewportHeight = 24.0f,
                    )
                    .apply {
                        path(
                                stroke = SolidColor(Color(0xFF000000)),
                                strokeLineCap = StrokeCap.Round,
                                strokeLineJoin = StrokeJoin.Round,
                                strokeLineWidth = strokeWidth,
                        ) {
                            moveTo(x = 20.0f, y = 6.0f)
                            lineTo(x = 9.0f, y = 17.0f)
                            lineToRelative(dx = -5.0f, dy = -5.0f)
                        }
                    }
                    .build()

    @Composable
    private fun LucideVectorIcon(vector: ImageVector, tint: Color, verticalOffsetPx: Float) {
        Icon(
                imageVector = vector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = verticalOffsetPx }
        )
    }

    private fun parseColor(value: Any): Color {
        return runCatching {
            when (value) {
                is Number -> Color(value.toInt())
                is String -> Color(AndroidColor.parseColor(value))
                else -> Color.Black
            }
        }.getOrDefault(Color.Black)
    }

    private fun parseOptionalColor(value: Any): Color? {
        val raw = value.toString()
        if (raw.isBlank()) return null
        return parseColor(raw)
    }

    private fun parseStrokeWidth(value: Any): Float? {
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }?.takeIf { it > 0f }
    }

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

    private fun String.toIconKey(): String =
            trim()
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
                    .replace("_", "-")
                    .lowercase()

    private data class IconVectorKey(
            val name: String,
            val strokeWidth: Float?,
            val fillEnabled: Boolean,
    )

    companion object {
        const val VIEW_NAME = "SlockLucideIconView"
        private const val PROP_NAME = "name"
        private const val PROP_COLOR = "color"
        private const val PROP_FILL = "fill"
        private const val PROP_STROKE_WIDTH = "strokeWidth"
        private const val PROP_VERTICAL_OFFSET_DP = "verticalOffsetDp"
        private const val PROP_SPINNING = "spinning"
        private const val PROP_ROTATION_DEGREES = "rotationDegrees"

        /** Matches `SlockIconSpinDurationSeconds` in shared SlockLucideIcon.kt. */
        private const val SPIN_DURATION_MS = 900L
    }
}
