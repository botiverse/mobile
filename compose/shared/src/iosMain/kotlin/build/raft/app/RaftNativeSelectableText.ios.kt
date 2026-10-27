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
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSAttributedString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSLayoutManager
import platform.UIKit.NSMutableParagraphStyle
import platform.UIKit.NSParagraphStyleAttributeName
import platform.UIKit.NSTextContainer
import platform.UIKit.NSTextStorage
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
    // The rendered UITextView, with its NSTextStorage kept alive by the stack holder (see
    // raftSelectableUITextView: storage → layoutManager is the only strong edge, so the
    // storage would dealloc if nothing held it). Two safeguards against the TextKit 2
    // stale-fragment bug that drew the text twice:
    // 1) the view stack is TextKit 1, so layout invalidation is complete;
    // 2) height is measured on a separate throwaway instance, so the displayed view is
    //    never laid out at a foreign width.
    val stack = remember { raftSelectableUITextView() }
    val textView = stack.view
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
            raftAttributedText(text, fontName, fontSizePt, lineHeightPt, color)
        }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // CMP iOS density == UIScreen scale, so 1.dp == 1pt.
        val widthPt = constraints.maxWidth.toDouble() / density.density
        val heightPt =
            remember(attributed, widthPt) {
                // The stack keeps the measuring view's storage alive for the call; it is
                // dropped when the remember block leaves composition.
                raftMeasuringTextView(attributed)
                    .view
                    .sizeThatFits(CGSizeMake(widthPt, Double.MAX_VALUE))
                    .useContents { height }
            }
        UIKitView(
            factory = { textView },
            modifier = Modifier.fillMaxWidth().height(heightPt.dp),
            update = { view ->
                if (view.attributedText != attributed) view.attributedText = attributed
                view.tintColor = handleColor.toUIColor()
            },
        )
    }
}

private fun Color.toUIColor(): UIColor =
    UIColor.colorWithRed(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())

/**
 * Keeps a TextKit 1 UITextView's NSTextStorage alive. In UIKit's ownership graph the view
 * holds its text container, but container → layoutManager and layoutManager → textStorage
 * are both weak; only the storage owns the layout manager. Returning just the view would
 * let the storage (and with it the layout manager) dealloc as soon as the factory returns.
 */
private class RaftSelectableTextStack(val view: UITextView, val storage: NSTextStorage)

/**
 * Off-screen UITextView used only for sizeThatFits measuring. Kept identical in configuration to
 * the on-screen view so the measured height matches what the displayed view wraps to.
 */
private fun raftMeasuringTextView(attributed: NSAttributedString): RaftSelectableTextStack =
    raftSelectableUITextView().also { it.view.attributedText = attributed }

/**
 * Non-editable, selectable, non-scrolling text view: the system edit menu owns
 * Copy/Look Up/Translate/Share.
 *
 * Built on an explicit NSTextStorage + NSLayoutManager + NSTextContainer stack so the view
 * uses TextKit 1. UITextView's default TextKit 2 path caches layout fragments across frame
 * changes and leaves the old fragment rendered under the new one — the sheet's text drew
 * twice with two different wrappings (iOS-only). TextKit 1 re-lays out cleanly.
 */
private fun raftSelectableUITextView(): RaftSelectableTextStack {
    val storage = NSTextStorage()
    val layoutManager = NSLayoutManager()
    val container =
        NSTextContainer(size = CGSizeMake(0.0, Double.MAX_VALUE)).apply {
            widthTracksTextView = true
            heightTracksTextView = false
            lineFragmentPadding = 0.0
        }
    storage.addLayoutManager(layoutManager)
    layoutManager.addTextContainer(container)
    val view =
        UITextView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), textContainer = container).apply {
            setEditable(false)
            setSelectable(true)
            setScrollEnabled(false)
            textContainerInset = UIEdgeInsetsMake(0.0, 0.0, 0.0, 0.0)
            backgroundColor = UIColor.clearColor
        }
    return RaftSelectableTextStack(view = view, storage = storage)
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
                // min/maxLineHeight *shrinks* the line box when the style's line height is
                // smaller than the font's natural height — every line then draws on top of
                // the previous one, which is what the "two overlaid copies" in the sheet
                // actually were. lineSpacing only adds space between lines: it honours the
                // design when lineHeight is looser and can never make lines overlap.
                val extra = lineHeightPt - font.lineHeight
                if (extra > 0.0) lineSpacing = extra
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
