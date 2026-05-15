package ai.slock.android.shared

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import ai.slock.android.ui.icons.LucideIcons
import com.tencent.kuikly.core.render.android.expand.component.KRView

internal class SlockLucideIconRenderView(context: Context) : KRView(context) {
    private val composeView = ComposeView(context)
    private var iconName: String = ""
    private var tint: Color = Color.Black

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
            else -> return super.setProp(propKey, propValue)
        }
        renderIcon()
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        when (propKey) {
            PROP_NAME -> iconName = ""
            PROP_COLOR -> tint = Color.Black
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
        val vector = LucideIcons.byName(iconName)
        composeView.setContent {
            if (vector != null) {
                LucideVectorIcon(vector = vector, tint = tint)
            }
        }
    }

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

    companion object {
        const val VIEW_NAME = "SlockLucideIconView"
        private const val PROP_NAME = "name"
        private const val PROP_COLOR = "color"
    }
}
