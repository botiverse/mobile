package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Immutable
import com.tencent.kuikly.compose.foundation.text.InlineTextContent
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuiklybase.markdown.annotator.AnnotatorSettings
import org.intellij.markdown.ast.ASTNode

interface MarkdownAnnotator {

    /**
     * Use the [AnnotatedString.Builder] to build the string to display.
     * Return `true` to consume the child, false to allow default handling.
     *
     * @param content contains the whole content, and requires the `child` [ASTNode] to extract relevant text.
     */
    val annotate: (AnnotatedString.Builder.(content: String, child: ASTNode, annotatorSettings: AnnotatorSettings) -> Boolean)?

    val inlineContent: (() -> Map<String, InlineTextContent>)?

    /** Defines static configuration for the [com.tencent.kuikly.ntcompose.ui.text.AnnotatedString] annotator */
    val config: MarkdownAnnotatorConfig
}

@Immutable
class DefaultMarkdownAnnotator(
    override val annotate: (AnnotatedString.Builder.(content: String, child: ASTNode, annotatorSettings: AnnotatorSettings) -> Boolean)?,
    override val inlineContent: (() -> Map<String, InlineTextContent>)?,
    override val config: MarkdownAnnotatorConfig,
) : MarkdownAnnotator

fun markdownAnnotator(
    annotate: (AnnotatedString.Builder.(content: String, child: ASTNode, annotatorSettings: AnnotatorSettings) -> Boolean)? = null,
    inlineContent: (() -> Map<String, InlineTextContent>)? = null,
    config: MarkdownAnnotatorConfig = markdownAnnotatorConfig(),
): MarkdownAnnotator = DefaultMarkdownAnnotator(
    annotate = annotate,
    inlineContent = inlineContent,
    config = config
)
