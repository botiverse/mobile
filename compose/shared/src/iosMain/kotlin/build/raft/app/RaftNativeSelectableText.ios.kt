@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
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
    selection: RaftNativeTextSelection?,
) {
    val fontSizePt = style.fontSize.ptOrNull() ?: 17.0
    val lineHeightPt = style.lineHeight.ptOrNull(fontSizePt)
    val fontName = style.raftIosFontName()
    val color = style.color.takeIf { it != Color.Unspecified } ?: Color.Black
    // One UITextView instance serves as both the rendered view and the height measurer.
    val textView = remember { raftSelectableUITextView() }
    // CMP's UIKitView interop punches a hole in the Metal canvas behind the native view,
    // so a transparent background lets whatever is behind the sheet (the message list's
    // own copy of this same text!) bleed through and look like the text drew twice.
    // The view must paint an opaque background matching the surface it sits on.
    val surfaceColor = LocalRaftSurfaceColor.current
    val themeCanvas = RaftTheme.colors.layerCanvas
    val backgroundColor =
        remember(surfaceColor, themeCanvas) {
            val fill = if (surfaceColor != Color.Unspecified) surfaceColor else themeCanvas
            fill.toUIColor()
        }
    // UIKit draws the handles and the highlight from one tint: the design system's handle colour
    // (RaftTheme `foreground`), not the app's system tint (Artea: follow the mobile design).
    val handleColor = LocalTextSelectionColors.current.handleColor
    DisposableEffect(selection, textView) {
        selection?.reader = {
            val current = textView.text.orEmpty()
            textView.selectedRange.useContents {
                raftNativeSelectedSlice(current, location.toInt(), (location + length).toInt())
            }
        }
        onDispose { selection?.reader = null }
    }
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
                view.tintColor = handleColor.toUIColor()
                view.backgroundColor = backgroundColor
            },
        )
    }
}

private fun Color.toUIColor(): UIColor =
    UIColor.colorWithRed(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())

/** Non-editable, selectable, non-scrolling text view: the system edit menu owns Copy/Look Up/Translate/Share. */
private fun raftSelectableUITextView(): UITextView =
    UITextView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
        setEditable(false)
        setSelectable(true)
        setScrollEnabled(false)
        textContainerInset = UIEdgeInsetsMake(0.0, 0.0, 0.0, 0.0)
        textContainer.lineFragmentPadding = 0.0
        // The opaque surface colour is applied in UIKitView's update (see the caller);
        // clearColor here would let whatever is behind the interop hole bleed through.
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
        NSForegroundColorAttributeName to color.toUIColor(),
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
