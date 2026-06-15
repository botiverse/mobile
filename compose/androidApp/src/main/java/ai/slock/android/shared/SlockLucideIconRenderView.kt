package ai.slock.android.shared

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.icons.LucideIcons
import com.tencent.kuikly.core.render.android.expand.component.KRView

internal class SlockLucideIconRenderView(context: Context) : KRView(context) {
    private val composeView = ComposeView(context)
    private var iconName: String = ""
    private var tint: Color = Color.Black
    private var fillTint: Color? = null
    private var strokeWidthOverride: Float? = null

    init {
        addView(
                composeView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        renderIcon()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            PROP_NAME -> iconName = propValue.toString()
            PROP_COLOR -> tint = parseColor(propValue)
            PROP_FILL -> fillTint = parseOptionalColor(propValue)
            PROP_STROKE_WIDTH -> strokeWidthOverride = parseStrokeWidth(propValue)
            else -> return super.setProp(propKey, propValue)
        }
        renderIcon()
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        when (propKey) {
            PROP_NAME -> iconName = ""
            PROP_COLOR -> tint = Color.Black
            PROP_FILL -> fillTint = null
            PROP_STROKE_WIDTH -> strokeWidthOverride = null
            else -> return super.resetProp(propKey)
        }
        renderIcon()
        return true
    }

    override fun onDestroy() {
        composeView.disposeComposition()
        super.onDestroy()
    }

    private fun renderIcon() {
        val resolvedFillTint = fillTint
        val vector =
                if (resolvedFillTint != null && iconName.equals("bookmark", ignoreCase = true)) {
                    FilledBookmarkIcon
                } else {
                    vectorFor(iconName, strokeWidthOverride)
                }
        composeView.setContent {
            if (vector != null) {
                LucideVectorIcon(vector = vector, tint = resolvedFillTint ?: tint)
            }
        }
    }

    private fun vectorFor(name: String, strokeWidth: Float?): ImageVector? {
        val requestedWidth = strokeWidth?.takeIf { it > 0f }
        if (name.toIconKey() == "check" && requestedWidth != null) {
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
    private fun LucideVectorIcon(vector: ImageVector, tint: Color) {
        Icon(
                imageVector = vector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxSize()
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

    private fun String.toIconKey(): String =
            trim()
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
                    .replace("_", "-")
                    .lowercase()

    companion object {
        const val VIEW_NAME = "SlockLucideIconView"
        private const val PROP_NAME = "name"
        private const val PROP_COLOR = "color"
        private const val PROP_FILL = "fill"
        private const val PROP_STROKE_WIDTH = "strokeWidth"

        private val FilledBookmarkIcon: ImageVector by lazy {
            ImageVector.Builder(
                            name = "SlockFilledBookmark",
                            defaultWidth = 24.0.dp,
                            defaultHeight = 24.0.dp,
                            viewportWidth = 24.0f,
                            viewportHeight = 24.0f,
                    )
                    .apply {
                        path(
                                fill = SolidColor(Color.Black),
                                stroke = SolidColor(Color.Black),
                                strokeLineCap = StrokeCap.Round,
                                strokeLineJoin = StrokeJoin.Round,
                                strokeLineWidth = 2.0f,
                        ) {
                            moveTo(x = 17.0f, y = 3.0f)
                            arcToRelative(
                                    a = 2.0f,
                                    b = 2.0f,
                                    theta = 0.0f,
                                    isMoreThanHalf = false,
                                    isPositiveArc = true,
                                    dx1 = 2.0f,
                                    dy1 = 2.0f,
                            )
                            verticalLineToRelative(dy = 15.0f)
                            arcToRelative(
                                    a = 1.0f,
                                    b = 1.0f,
                                    theta = 0.0f,
                                    isMoreThanHalf = false,
                                    isPositiveArc = true,
                                    dx1 = -1.496f,
                                    dy1 = 0.868f,
                            )
                            lineToRelative(dx = -4.512f, dy = -2.578f)
                            arcToRelative(
                                    a = 2.0f,
                                    b = 2.0f,
                                    theta = 0.0f,
                                    isMoreThanHalf = false,
                                    isPositiveArc = false,
                                    dx1 = -1.984f,
                                    dy1 = 0.0f,
                            )
                            lineToRelative(dx = -4.512f, dy = 2.578f)
                            arcTo(
                                    horizontalEllipseRadius = 1.0f,
                                    verticalEllipseRadius = 1.0f,
                                    theta = 0.0f,
                                    isMoreThanHalf = false,
                                    isPositiveArc = true,
                                    x1 = 5.0f,
                                    y1 = 20.0f,
                            )
                            verticalLineTo(y = 5.0f)
                            arcToRelative(
                                    a = 2.0f,
                                    b = 2.0f,
                                    theta = 0.0f,
                                    isMoreThanHalf = false,
                                    isPositiveArc = true,
                                    dx1 = 2.0f,
                                    dy1 = -2.0f,
                            )
                            close()
                        }
                    }
                    .build()
        }
    }
}
