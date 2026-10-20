package com.tencent.kuiklybase.markdown.compose.elements.material

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalTextProvider
import com.tencent.kuiklybase.markdown.compose.RaftMarkdownVendorTraceThresholdUs
import com.tencent.kuiklybase.markdown.compose.raftMarkdownLogSubTrace
import com.tencent.kuiklybase.markdown.compose.raftMarkdownMonotonicNanosSince
import kotlin.time.TimeSource

@Composable
fun MarkdownBasicText(
    text: String,
    style: TextStyle,
    modifier: Modifier,
    color: Color? = null,
    fontSize: TextUnit? = null,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign = TextAlign.Start,
    lineHeight: TextUnit? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    // Note: This component is ported over from Material2 Text - to remove the dependency on Material
    val overrideColorOrUnspecified: Color =
        color.markdownSpecifiedOrNull()
            ?: style.color.markdownSpecifiedOrNull()
            ?: LocalMarkdownColors.current.text
    val styleMergeStartMark = TimeSource.Monotonic.markNow()
    val mergedStyle = style.merge(
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        textDecoration = textDecoration,
        fontStyle = fontStyle,
        letterSpacing = letterSpacing
    )
    val styleMergeDurationUs = raftMarkdownMonotonicNanosSince(styleMergeStartMark) / 1_000L
    if (styleMergeDurationUs >= RaftMarkdownVendorTraceThresholdUs) {
        raftMarkdownLogSubTrace {
            "phase=markdown.text.merge_style durationUs=$styleMergeDurationUs textLength=${text.length}"
        }
    }

    val textProviderStartMark = TimeSource.Monotonic.markNow()
    LocalTextProvider.current.Text(
        text = text,
        modifier = modifier,
        color = overrideColorOrUnspecified,
        fontSize = TextUnit.Unspecified,
        fontStyle = null,
        fontWeight = null,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing ?: TextUnit.Unspecified,
        textDecoration = null,
        textAlign = null,
        lineHeight = TextUnit.Unspecified,
        style = mergedStyle,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
    val textProviderDurationUs = raftMarkdownMonotonicNanosSince(textProviderStartMark) / 1_000L
    if (textProviderDurationUs >= RaftMarkdownVendorTraceThresholdUs) {
        raftMarkdownLogSubTrace {
            "phase=markdown.text.provider_text durationUs=$textProviderDurationUs textLength=${text.length}"
        }
    }
}

@Composable
fun MarkdownBasicText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontSize: TextUnit? = null,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    // Note: This component is ported over from Material2 Text - to remove the dependency on Material
    val overrideColorOrUnspecified: Color =
        color.markdownSpecifiedOrNull()
            ?: style.color.markdownSpecifiedOrNull()
            ?: LocalMarkdownColors.current.text

    val styleMergeStartMark = TimeSource.Monotonic.markNow()
    val mergedStyle = style.merge(
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        textDecoration = textDecoration,
        fontStyle = fontStyle,
        letterSpacing = letterSpacing
    )
    val styleMergeDurationUs = raftMarkdownMonotonicNanosSince(styleMergeStartMark) / 1_000L
    if (styleMergeDurationUs >= RaftMarkdownVendorTraceThresholdUs) {
        raftMarkdownLogSubTrace {
            "phase=markdown.text.merge_style durationUs=$styleMergeDurationUs textLength=${text.text.length}"
        }
    }

    val textProviderStartMark = TimeSource.Monotonic.markNow()
    LocalTextProvider.current.RichText(
        text = text,
        modifier = modifier,
        color = overrideColorOrUnspecified,
        fontSize = TextUnit.Unspecified,
        fontStyle = null,
        fontWeight = null,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing ?: TextUnit.Unspecified,
        textDecoration = null,
        textAlign = null,
        lineHeight = TextUnit.Unspecified,
        style = mergedStyle,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
    )
    val textProviderDurationUs = raftMarkdownMonotonicNanosSince(textProviderStartMark) / 1_000L
    // Annotation scans are O(annotations) and only feed the log line, so they
    // live inside the lazily-evaluated message lambdas (skipped when the
    // RaftMarkdownSubTrace gate is off).
    raftMarkdownLogSubTrace {
        val stringAnnotationCount = text.getStringAnnotations(start = 0, end = text.length).size
        val linkAnnotationCount = text.getLinkAnnotations(start = 0, end = text.length).size
        "phase=markdown.text.provider_rich_text_all durationUs=$textProviderDurationUs textHash=${text.text.hashCode().toString(16)} " +
            "textLength=${text.text.length} spanStyles=${text.spanStyles.size} paragraphStyles=${text.paragraphStyles.size} " +
            "stringAnnotations=$stringAnnotationCount linkAnnotations=$linkAnnotationCount inlineContent=${inlineContent.size}"
    }
    if (textProviderDurationUs >= RaftMarkdownVendorTraceThresholdUs) {
        raftMarkdownLogSubTrace {
            val stringAnnotationCount = text.getStringAnnotations(start = 0, end = text.length).size
            val linkAnnotationCount = text.getLinkAnnotations(start = 0, end = text.length).size
            "phase=markdown.text.provider_rich_text durationUs=$textProviderDurationUs textLength=${text.text.length} " +
                "spanStyles=${text.spanStyles.size} paragraphStyles=${text.paragraphStyles.size} " +
                "stringAnnotations=$stringAnnotationCount linkAnnotations=$linkAnnotationCount inlineContent=${inlineContent.size}"
        }
    }
}

private fun Color?.markdownSpecifiedOrNull(): Color? =
    if (this == null || isUnspecified) null else this


/**
 * Fast merge non-default values and parameters.
 *
 * This is the same algorithm as [merge] but does not require allocating it's parameter and may
 * return this instead of allocating a result when all values are default.
 *
 * This is a similar algorithm to [copy] but when either this or a parameter are set to a
 * default value, the other value will take precedent.
 *
 * To explain better, consider the following examples:
 *
 * Example 1:
 * - this.color = [Color.Unspecified]
 * - [color] = [Color.Red]
 * - result => [Color.Red]
 *
 * Example 2:
 * - this.color = [Color.Red]
 * - [color] = [Color.Unspecified]
 * - result => [Color.Red]
 *
 * Example 3:
 * - this.color = [Color.Red]
 * - [color] = [Color.Blue]
 * - result => [Color.Blue]
 *
 * You should _always_ use this method over the [merge]([TextStyle]) overload when you do not
 * already have a TextStyle allocated. You should chose this over [copy] when building a theming
 * system and applying styling information to a specific usage.
 *
 * @return this or a new TextLayoutResult with all parameters chosen to the non-default option
 * provided.
 *
 * @see merge
 */
@Stable
fun TextStyle.merge(
    fontSize: TextUnit? = null,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit? = null,
    color: Color? = null,
): TextStyle {
    return TextStyle(
        color = color ?: this.color,
        fontSize = fontSize ?: this.fontSize,
        fontWeight = fontWeight ?: this.fontWeight,
        fontStyle = fontStyle ?: this.fontStyle,
        fontFamily = fontFamily ?: this.fontFamily,
        letterSpacing = letterSpacing ?: this.letterSpacing,
//        placeholderColor = placeholderColor,
        textDecoration = textDecoration ?: this.textDecoration,
        shadow = shadow,
        textAlign = textAlign ?: this.textAlign,
        lineHeight = lineHeight ?: this.lineHeight,
//        textIndent = textIndent,
//        lineSpacing = lineSpacing,
//        stroke = stroke,
//        paragraphSpacing = paragraphSpacing,
    )
}
