@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.PlatformFont
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.viewinterop.UIKitView
import build.raft.app.compat.ui.RaftIosFallbackFontName
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSAttributedString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSMutableParagraphStyle
import platform.UIKit.NSParagraphStyleAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UIFont
import platform.UIKit.UITextView

@Composable
actual fun RaftNativeSelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier,
) {
    val fontSizePt = style.fontSize.ptOrNull() ?: 17.0
    val lineHeightPt = style.lineHeight.ptOrNull(fontSizePt)
    val fontName = style.raftIosFontName()
    val color = style.color.takeIf { it != Color.Unspecified } ?: Color.Black
    // One UITextView instance serves as both the rendered view and the height measurer.
    val textView = remember { raftSelectableUITextView() }
    val attributed =
        remember(text, fontName, fontSizePt, lineHeightPt, color) {
            textView.attributedText = raftAttributedText(text, fontName, fontSizePt, lineHeightPt, color)
            textView.attributedText
        }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // CMP iOS density == UIScreen scale, so 1.dp == 1pt.
        val widthPt = constraints.maxWidth.toDouble() / density.density
        val heightPt =
            remember(attributed, widthPt) {
                textView.sizeThatFits(CGSizeMake(widthPt, Double.MAX_VALUE)).useContents { height }
            }
        UIKitView(
            factory = { textView },
            modifier = Modifier.fillMaxWidth().height(heightPt.dp),
            update = { view ->
                if (view.attributedText != attributed) view.attributedText = attributed
            },
        )
    }
}

/** Non-editable, selectable, non-scrolling text view: the system edit menu owns Copy/Look Up/Translate/Share. */
private fun raftSelectableUITextView(): UITextView =
    UITextView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
        setEditable(false)
        setSelectable(true)
        setScrollEnabled(false)
        textContainerInset = UIEdgeInsetsMake(0.0, 0.0, 0.0, 0.0)
        textContainer.lineFragmentPadding = 0.0
        backgroundColor = UIColor.clearColor
    }

private fun raftAttributedText(
    text: String,
    fontName: String?,
    fontSizePt: Double,
    lineHeightPt: Double?,
    color: Color,
): NSAttributedString {
    // An unknown bundled name falls back to Space Grotesk Regular, as KuiklyFont does on the Compose
    // side; only text with no Raft family at all (System font preference) uses the system face.
    val font =
        fontName?.let { UIFont.fontWithName(it, fontSizePt) ?: UIFont.fontWithName(RaftIosFallbackFontName, fontSizePt) }
            ?: UIFont.systemFontOfSize(fontSizePt)
    val attributes = mutableMapOf<Any?, Any?>(
        NSFontAttributeName to font,
        NSForegroundColorAttributeName to
            UIColor.colorWithRed(
                red = color.red.toDouble(),
                green = color.green.toDouble(),
                blue = color.blue.toDouble(),
                alpha = color.alpha.toDouble(),
            ),
    )
    if (lineHeightPt != null) {
        attributes[NSParagraphStyleAttributeName] =
            NSMutableParagraphStyle().apply {
                setMinimumLineHeight(lineHeightPt)
                setMaximumLineHeight(lineHeightPt)
            }
    }
    return NSAttributedString.create(string = text, attributes = attributes)
}

/**
 * The app font registered via UIAppFonts, matched by weight: KuiklyFont's identity is the face's
 * PostScript name, which UIFont(name:) resolves (the Compose side loads the same TTF's bytes).
 */
@OptIn(ExperimentalTextApi::class)
private fun TextStyle.raftIosFontName(): String? {
    val fonts = (fontFamily as? FontListFontFamily)?.fonts ?: return null
    val weight = fontWeight ?: FontWeight.Normal
    val match = fonts.firstOrNull { it.weight == weight } ?: fonts.firstOrNull()
    return (match as? PlatformFont)?.identity
}

private fun TextUnit.ptOrNull(fontSizePt: Double? = null): Double? =
    when {
        !isSpecified -> null
        isSp -> value.toDouble()
        isEm -> fontSizePt?.let { it * value }
        else -> null
    }
