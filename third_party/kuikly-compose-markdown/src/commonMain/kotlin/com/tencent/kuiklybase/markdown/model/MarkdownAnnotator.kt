package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.AnnotatedString
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

    /**
     * Allows an annotator to consume a sequence of sibling AST nodes starting at [startIndex].
     * Return the number of consumed siblings, or 0 to continue with the normal single-child path.
     */
    val annotateSequence: (
        AnnotatedString.Builder.(
            content: String,
            children: List<ASTNode>,
            startIndex: Int,
            annotatorSettings: AnnotatorSettings
        ) -> Int
    )?

    val inlineContent: (() -> Map<String, InlineTextContent>)?

    /** Defines static configuration for the [com.tencent.kuikly.ntcompose.ui.text.AnnotatedString] annotator */
    val config: MarkdownAnnotatorConfig
}

@Immutable
class DefaultMarkdownAnnotator(
    override val annotate: (AnnotatedString.Builder.(content: String, child: ASTNode, annotatorSettings: AnnotatorSettings) -> Boolean)?,
    override val annotateSequence: (
        AnnotatedString.Builder.(
            content: String,
            children: List<ASTNode>,
            startIndex: Int,
            annotatorSettings: AnnotatorSettings
        ) -> Int
    )?,
    override val inlineContent: (() -> Map<String, InlineTextContent>)?,
    override val config: MarkdownAnnotatorConfig,
) : MarkdownAnnotator

fun markdownAnnotator(
    annotate: (AnnotatedString.Builder.(content: String, child: ASTNode, annotatorSettings: AnnotatorSettings) -> Boolean)? = null,
    annotateSequence: (
        AnnotatedString.Builder.(
            content: String,
            children: List<ASTNode>,
            startIndex: Int,
            annotatorSettings: AnnotatorSettings
        ) -> Int
    )? = null,
    inlineContent: (() -> Map<String, InlineTextContent>)? = null,
    config: MarkdownAnnotatorConfig = markdownAnnotatorConfig(),
): MarkdownAnnotator = DefaultMarkdownAnnotator(
    annotate = annotate,
    annotateSequence = annotateSequence,
    inlineContent = inlineContent,
    config = config
)
