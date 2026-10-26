package com.tencent.kuiklybase.markdown.compose.elements

import build.raft.app.markdown.raftInlineMathContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import androidx.compose.ui.geometry.Size
import com.tencent.kuiklybase.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.tencent.kuiklybase.markdown.annotator.AnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.annotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.LocalImageLinkHandler
import com.tencent.kuiklybase.markdown.compose.LocalImageTransformer
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownAnimations
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownExtendedSpans
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownAnnotationRevision
import com.tencent.kuiklybase.markdown.compose.raftMarkdownAnnotatedStringMemoKeys
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownRenderTiming
import com.tencent.kuiklybase.markdown.compose.raftMarkdownLogSubTrace
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.elements.material.MarkdownBasicText
import com.tencent.kuiklybase.markdown.compose.extendedspans.ExtendedSpans
import com.tencent.kuiklybase.markdown.compose.extendedspans.drawBehind
import com.tencent.kuiklybase.markdown.model.ImageTransformer
import com.tencent.kuiklybase.markdown.model.PlaceholderConfig
import com.tencent.kuiklybase.markdown.model.rememberMarkdownImageState
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType


@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalMarkdownTypography.current.text,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    MarkdownText(AnnotatedString(content), modifier, style, annotatorSettings = annotatorSettings)
}

@Composable
fun MarkdownText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    val childNode = contentChildType?.run(node::findChildOfType) ?: node
    // Memoization is OPT-IN via the host annotation revision — same contract
    // and same reasoning as MarkdownParagraph: no revision = legacy
    // rebuild-every-recomposition (identity-stable annotators may be
    // dynamic); revision provided = remember by actual inputs + revision.
    // Parse-tree identity is stable per content, so node works as a key.
    val nodeTiming = LocalRaftMarkdownRenderTiming.current
    val memoKeys = raftMarkdownAnnotatedStringMemoKeys(
        content, childNode, style, annotatorSettings, LocalRaftMarkdownAnnotationRevision.current
    )
    val buildStyledText = {
        buildAnnotatedString {
            pushStyle(style.toSpanStyle())
            buildMarkdownAnnotatedString(
                content = content,
                node = childNode,
                annotatorSettings = annotatorSettings
            )
            pop()
        }
    }
    val styledText =
        if (memoKeys == null) {
            buildStyledText()
        } else {
            var rebuilt = false
            val remembered = remember(*memoKeys) {
                rebuilt = true
                buildStyledText()
            }
            if (rebuilt) nodeTiming?.recordAnnotatedStringRebuild() else nodeTiming?.recordAnnotatedStringHit()
            remembered
        }

    MarkdownText(styledText, modifier = modifier, style = style, annotatorSettings = annotatorSettings)
}

@Composable
fun MarkdownText(
    content: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalMarkdownTypography.current.text,
    extendedSpans: ExtendedSpans? = LocalMarkdownExtendedSpans.current.extendedSpans?.invoke(),
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    // extend the annotated string with `extended-spans` styles if provided
    val extendedStyledText = if (extendedSpans != null) {
        remember(content) {
            extendedSpans.extend(content)
        }
    } else {
        content
    }
    raftMarkdownLogSubTrace {
        "phase=markdown.text.annotated_input textHash=${extendedStyledText.text.hashCode().toString(16)} " +
            "textLength=${extendedStyledText.text.length} spanStyles=${extendedStyledText.spanStyles.size} " +
            "stringAnnotations=${extendedStyledText.getStringAnnotations(0, extendedStyledText.length).size} " +
            "linkAnnotations=${extendedStyledText.getLinkAnnotations(0, extendedStyledText.length).size}"
    }

    // forward the `onTextLayout` to `extended-spans` if provided
    val onTextLayout: ((TextLayoutResult, Color?) -> Unit)? = if (extendedSpans != null) {
        { layoutResult, color ->
            extendedSpans.onTextLayout(layoutResult, color)
        }
    } else {
        null
    }

    // call drawBehind with the `extended-spans` if provided
    val extendedModifier = if (extendedSpans != null) {
        modifier.drawBehind(extendedSpans)
    } else modifier

    MarkdownText(content = extendedStyledText, modifier = extendedModifier, style = style, onTextLayout = onTextLayout)
}

@Composable
fun MarkdownText(
    content: AnnotatedString,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalMarkdownTypography.current.text,
    onTextLayout: ((TextLayoutResult, Color?) -> Unit)?,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    val baseColor = LocalMarkdownColors.current.text
    val animations = LocalMarkdownAnimations.current
    val transformer = LocalImageTransformer.current

    val hasImageInlineContent = remember(content) { content.hasMarkdownImageInlineContent() }
    val imageState = rememberMarkdownImageState()
    val placeholderState by remember(hasImageInlineContent, imageState) {
        derivedStateOf {
            if (hasImageInlineContent) {
                transformer.placeholderConfig(
                    imageState.containerSize,
                    imageState.intrinsicImageSize
                )
            } else {
                null
            }
        }
    }

    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val currentPlaceholderState = placeholderState
    if (currentPlaceholderState != null) {
        inlineContent[MARKDOWN_TAG_IMAGE_URL] = createImageInlineTextContent(currentPlaceholderState, transformer)
    }
    val extendedInlineContent: Map<String, InlineTextContent>? = annotatorSettings.annotator.inlineContent?.invoke()
    if (extendedInlineContent != null) {
        inlineContent.putAll(extendedInlineContent)
    }
    // Task #775: formulas (INLINE_MATH placeholders), in this text's size and colour.
    inlineContent.putAll(raftInlineMathContent(content, style, baseColor))

    MarkdownBasicText(
        text = content,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
            .onPlaced { it ->
                if (hasImageInlineContent) {
                    imageState.updateContainerSize(Size(it.size.width.toFloat(), it.size.height.toFloat()))
                }
            }
            .also {
                @Suppress("DEPRECATION")
                if (placeholderState?.animate == true) animations.animateTextSize(it) else it
            },
        style = style,
        inlineContent = inlineContent,
        onTextLayout = {
            onTextLayout?.invoke(it, baseColor)
        }
    )
}

private fun AnnotatedString.hasMarkdownImageInlineContent(): Boolean =
    getStringAnnotations(start = 0, end = length).any { it.item == MARKDOWN_TAG_IMAGE_URL }

@Composable
internal fun createImageInlineTextContent(
    placeholderState: PlaceholderConfig,
    transformer: ImageTransformer,
): InlineTextContent {
    val density = LocalDensity.current
    // The image's measured height (px) narrows the placeholder once it loads. Kept in px and
    // converted with the density's own px -> sp, so sp -> px gives the same px back.
    var heightPx by remember { mutableStateOf(markdownInlineImagePlaceholderPx(placeholderState.size.height)) }

    val imageHandler = LocalImageLinkHandler.current
    return InlineTextContent(
        markdownInlineImagePlaceholder(
            widthPx = placeholderState.size.width,
            heightPx = heightPx,
            density = density,
        )
    ) { link ->
        transformer.transform(link, placeholderState.size)?.let { imageData ->
            imageHandler.store(imageData)

            Image(
                modifier = Modifier
                    .clickable { imageHandler.preview(imageData) }
                    .then(imageData.modifier)
                    .onSizeChanged {
                        heightPx = nextMarkdownInlineImagePlaceholderHeightPx(heightPx, it.height)
                    },
                painter = imageData.painter,
                contentDescription = imageData.contentDescription,
                alignment = imageData.alignment,
                contentScale = imageData.contentScale,
                alpha = imageData.alpha,
                colorFilter = imageData.colorFilter
            )
        }
    }
}

/*
 * TestFlight crash cluster C (iOS 1.15.5, 2026-10-25): `Can't represent a width of 1216 and
 * height of 262285 in Constraints`, thrown by TextMeasurePolicy measuring this placeholder.
 * The height used to go px -> dp -> `dp.value.sp`, and sp -> px multiplies by the font scale, so
 * under Dynamic Type every frame grew the placeholder by the font scale whenever the Image
 * filled it (still loading, failed, or taller than wide) until Constraints overflowed; the width
 * (the container width) stayed put. Now px go through the density's px -> sp, the measured
 * height only replaces the placeholder's when it differs by more than rounding, and both sides
 * stay below a size Constraints can always hold.
 */
internal fun markdownInlineImagePlaceholder(
    widthPx: Float,
    heightPx: Float,
    density: Density,
): Placeholder =
    with(density) {
        Placeholder(
            width = markdownInlineImagePlaceholderPx(widthPx).toSp(),
            height = markdownInlineImagePlaceholderPx(heightPx).toSp(),
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline
        )
    }

internal fun nextMarkdownInlineImagePlaceholderHeightPx(currentPx: Float, measuredPx: Int): Float =
    if (abs(measuredPx - currentPx) > 1f) markdownInlineImagePlaceholderPx(measuredPx.toFloat()) else currentPx

private fun markdownInlineImagePlaceholderPx(px: Float): Float =
    if (px.isNaN()) 1f else px.coerceIn(1f, MarkdownInlineImagePlaceholderMaxPx)

/** Constraints can hold 32766 px on both sides at once (15 + 16 bits). */
internal const val MarkdownInlineImagePlaceholderMaxPx = 32_766f
