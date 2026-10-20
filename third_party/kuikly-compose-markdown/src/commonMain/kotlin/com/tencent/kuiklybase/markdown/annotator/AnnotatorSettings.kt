package com.tencent.kuiklybase.markdown.annotator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownAnnotator
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.LocalReferenceLinkHandler
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandler
import com.tencent.kuiklybase.markdown.utils.codeSpanStyle
import androidx.compose.ui.text.TextLinkStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
interface AnnotatorSettings {
    /** Represents the [TextLinkStyles] applied onto links within this annotated string */
    val linkTextSpanStyle: TextLinkStyles

    /** Represents the [SpanStyle] applied onto code inline blocks within this annotated string */
    val codeSpanStyle: SpanStyle

    /** Provides custom interception logic for building this annotated string */
    val annotator: MarkdownAnnotator

    /** Represents the [ReferenceLinkHandler] used to store and find links when clicks on links occur. */
    val referenceLinkHandler: ReferenceLinkHandler?

    /** Represents the [LinkInteractionListener] used for links in this annotated string */
    val linkInteractionListener: LinkInteractionListener?
}

@Immutable
class DefaultAnnotatorSettings(
    override val linkTextSpanStyle: TextLinkStyles,
    override val codeSpanStyle: SpanStyle,
    override val annotator: MarkdownAnnotator,
    override val referenceLinkHandler: ReferenceLinkHandler? = null,
    override val linkInteractionListener: LinkInteractionListener? = null,
) : AnnotatorSettings

@Composable
fun annotatorSettings(
    linkTextSpanStyle: TextLinkStyles = LocalMarkdownTypography.current.textLink,
    codeSpanStyle: SpanStyle = LocalMarkdownTypography.current.codeSpanStyle,
    annotator: MarkdownAnnotator = LocalMarkdownAnnotator.current,
    referenceLinkHandler: ReferenceLinkHandler? = LocalReferenceLinkHandler.current,
    uriHandler: UriHandler = LocalUriHandler.current,
    scope: CoroutineScope = rememberCoroutineScope(),
    // Remembered: the listener default used to allocate a fresh lambda every
    // composition, which made every AnnotatorSettings instance identity-unequal
    // and forced full re-runs of MarkdownText/MarkdownParagraph for unchanged
    // rows (same instability class as the #192 colors fix).
    linkInteractionListener: LinkInteractionListener? = remember(referenceLinkHandler, uriHandler, scope) {
        LinkInteractionListener { link ->
            val annotationUrl = (link as? LinkAnnotation.Url)?.url
            if (annotationUrl != null) {
                val foundReference = referenceLinkHandler?.find(annotationUrl)?.takeIf { it.isNotEmpty() } ?: annotationUrl
                // wait for finger up to navigate to the link
                try {
                    scope.launch { uriHandler.openUri(foundReference) }
                } catch (t: Throwable) {
                    println("Could not open the provided url: $foundReference // ${t.message}")
                }
            }
        }
    },
): AnnotatorSettings = remember(linkTextSpanStyle, codeSpanStyle, annotator, referenceLinkHandler, linkInteractionListener) {
    DefaultAnnotatorSettings(
        linkTextSpanStyle = linkTextSpanStyle,
        codeSpanStyle = codeSpanStyle,
        annotator = annotator,
        referenceLinkHandler = referenceLinkHandler,
        linkInteractionListener = linkInteractionListener,
    )
}