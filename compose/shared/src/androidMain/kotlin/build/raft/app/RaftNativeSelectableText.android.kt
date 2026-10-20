package build.raft.app

import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import build.raft.app.shared.R
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun RaftNativeSelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val typeface =
        style.fontFamily?.let { family ->
            LocalFontFamilyResolver.current.resolve(
                fontFamily = family,
                fontWeight = style.fontWeight ?: FontWeight.Normal,
                fontStyle = style.fontStyle ?: FontStyle.Normal,
                fontSynthesis = style.fontSynthesis ?: FontSynthesis.All,
            ).value as? Typeface
        }
    val fontSizePx = style.fontSize.toPxOrNull(density)
    val lineHeightPx = style.lineHeight.toPxOrNull(density, fontSizePx)
    val textColor = style.color.takeIf { it != Color.Unspecified } ?: Color.Black
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                // Stable native id for UIAutomator (task #924 physical admission); the Compose
                // testTag is additionally exposed as resource-id via testTagsAsResourceId below.
                id = R.id.thread_message_selection_text
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                // System-owned selection: platform handles + floating toolbar incl. PROCESS_TEXT.
                setTextIsSelectable(true)
            }
        },
        modifier =
            modifier
                .semantics { testTagsAsResourceId = true }
                .fillMaxWidth()
                .wrapContentHeight(),
        update = { view ->
            if (view.text?.toString() != text) view.text = text
            view.setTextColor(textColor.toArgb())
            if (fontSizePx != null) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            if (typeface != null) view.typeface = typeface
            if (lineHeightPx != null) TextViewCompat.setLineHeight(view, lineHeightPx.roundToInt().coerceAtLeast(1))
        },
    )
}

private fun TextUnit.toPxOrNull(density: androidx.compose.ui.unit.Density, fontSizePx: Float? = null): Float? =
    when {
        !isSpecified -> null
        isSp -> with(density) { toPx() }
        isEm -> fontSizePx?.let { it * value }
        else -> null
    }
