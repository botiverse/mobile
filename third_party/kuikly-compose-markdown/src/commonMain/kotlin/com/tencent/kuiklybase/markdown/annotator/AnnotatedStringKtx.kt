package com.tencent.kuiklybase.markdown.annotator

import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.foundation.text.appendInlineContent
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.LinkInteractionListener
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.Composable
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandler
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.tencent.kuiklybase.markdown.utils.findChildOfTypeRecursive
import com.tencent.kuiklybase.markdown.utils.getUnescapedTextInNode
import com.tencent.kuiklybase.markdown.utils.innerList
import com.tencent.kuiklybase.markdown.utils.mapAutoLinkToType
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.withLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import build.raft.app.slockInlineCodeCharWrapBridgeThreshold

private const val SLOCK_INLINE_CODE_ANNOTATION_TAG = "raft.build.markdown.inlineCode"
private const val SLOCK_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG =
    "raft.build.markdown.inlineCodeTrailingMargin"
private const val SLOCK_INLINE_CODE_EDGE_PADDING = '\u00A0'
private const val SLOCK_INLINE_CODE_CHAR_WRAP_BREAK = '\u200B'

// task #401 A-bridge: interleave zero-width spaces into non-whitespace runs
// longer than [threshold] so renders without native inline-code atomization
// (OHOS/iOS) can wrap long code at any character boundary, matching web and
// the Android render's char-level behavior. Never splits a surrogate pair.
internal fun String.withSlockInlineCodeCharWrapBreaks(threshold: Int): String {
    if (length <= threshold) return this
    val result = StringBuilder(length * 2)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char.isWhitespace()) {
            result.append(char)
            index++
            continue
        }
        var runEnd = index
        while (runEnd < length && !this[runEnd].isWhitespace()) {
            runEnd++
        }
        val run = substring(index, runEnd)
        if (run.length > threshold) {
            run.forEachIndexed { runIndex, runChar ->
                if (runIndex > 0 && !runChar.isLowSurrogate()) {
                    result.append(SLOCK_INLINE_CODE_CHAR_WRAP_BREAK)
                }
                result.append(runChar)
            }
        } else {
            result.append(run)
        }
        index = runEnd
    }
    return result.toString()
}

/**
 * Extension function to build an `AnnotatedString` from a Markdown string.
 * This function will parse the Markdown content and apply the given styles to the text.
 *
 * It only supports TEXT and PARAGRAPH nodes.
 *
 * @param style The base text style to apply.
 * @param linkTextSpanStyle The style to apply to link text.
 * @param codeSpanStyle The style to apply to code spans.
 * @param flavour The Markdown flavour descriptor to use (default is GFM).
 * @param annotator An optional annotator for additional processing.
 * @return The constructed `AnnotatedString`.
 */
@Deprecated(
    message = "This function is deprecated. Use the new `annotatorSettings` function to create a settings object.",
    replaceWith = ReplaceWith("buildMarkdownAnnotatedString(style, annotatorSettings, flavour)")
)
@Composable
fun String.buildMarkdownAnnotatedString(
    style: TextStyle,
    linkTextSpanStyle: SpanStyle = style.toSpanStyle(),
    codeSpanStyle: SpanStyle = style.toSpanStyle(),
    flavour: MarkdownFlavourDescriptor = GFMFlavourDescriptor(),
    annotator: MarkdownAnnotator = markdownAnnotator(),
    referenceLinkHandler: ReferenceLinkHandler? = null,
    linkInteractionListener: LinkInteractionListener? = null,
) = buildMarkdownAnnotatedString(
    style = style,
    annotatorSettings = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(style = linkTextSpanStyle),
        codeSpanStyle = codeSpanStyle,
        annotator = annotator,
        referenceLinkHandler = referenceLinkHandler,
        linkInteractionListener = linkInteractionListener
    ),
    flavour = flavour
)

/**
 * Extension function to build an `AnnotatedString` from a Markdown string.
 * This function will parse the Markdown content and apply the given styles to the text.
 *
 * It only supports TEXT and PARAGRAPH nodes.
 *
 * @param style The base text style to apply.
 * @param flavour The Markdown flavour descriptor to use (default is GFM).
 * @param annotatorSettings Settings object to adjust different behavior of this annotated string.
 * @return The constructed `AnnotatedString`.
 */
@Composable
fun String.buildMarkdownAnnotatedString(
    style: TextStyle,
    annotatorSettings: AnnotatorSettings,
    flavour: MarkdownFlavourDescriptor = GFMFlavourDescriptor(),
): AnnotatedString {
    val content = MarkdownParserContent.from(this).parserContent
    val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(content)
    val textNode = parsedTree.children.firstOrNull { node ->
        node.type == MarkdownTokenTypes.TEXT || node.type == MarkdownElementTypes.PARAGRAPH
    }
    if (textNode == null) return buildAnnotatedString { }
    return content.buildMarkdownAnnotatedString(
        textNode = textNode,
        style = style,
        annotatorSettings = annotatorSettings
    )
}

/**
 * Extension function to build an `AnnotatedString` from a Markdown string.
 * This function will parse the Markdown content and apply the given styles to the text.
 *
 * It only supports TEXT and PARAGRAPH nodes.
 *
 * @param textNode The AST node representing the text.
 * @param style The base text style to apply.
 * @param annotatorSettings Settings object to adjust different behavior of this annotated string.
 * @return The constructed `AnnotatedString`.
 */
@Composable
fun String.buildMarkdownAnnotatedString(
    textNode: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings,
): AnnotatedString = buildAnnotatedString {
    pushStyle(style.toSpanStyle())
    buildMarkdownAnnotatedString(this@buildMarkdownAnnotatedString, textNode, annotatorSettings)
    pop()
}

/**
 * Appends a Markdown link to the `AnnotatedString.Builder`.
 *
 * @param content The content string.
 * @param node The AST node representing the link.
 * @param annotatorSettings Settings object to adjust different behavior of this annotated string.
 */
internal fun AnnotatedString.Builder.appendMarkdownLink(
    content: String,
    node: ASTNode,
    annotatorSettings: AnnotatorSettings,
) {
    val linkText = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.children?.innerList()
    if (linkText == null) {
        append(node.getUnescapedTextInNode(content))
        return
    }
    val text = linkText.firstOrNull()?.getUnescapedTextInNode(content)
    val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(content)
    val linkLabel = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.getUnescapedTextInNode(content)
    val annotation = destination ?: linkLabel

    if (annotation != null) {
        if (text != null) annotatorSettings.referenceLinkHandler?.store(text, annotation)
        withLink(
            LinkAnnotation.Url(
                annotation,
                annotatorSettings.linkTextSpanStyle,
                annotatorSettings.linkInteractionListener
            )
        ) {
            buildMarkdownAnnotatedString(content, linkText.mapAutoLinkToType(), annotatorSettings)
        }
    } else {
        buildMarkdownAnnotatedString(content, linkText, annotatorSettings)
    }
}

/**
 * Appends a Markdown reference to the `AnnotatedString.Builder`.
 *
 * @param content The content string.
 * @param node The AST node representing the link.
 * @param annotatorSettings Settings object to adjust different behavior of this annotated string.
 */
internal fun AnnotatedString.Builder.appendMarkdownReference(
    content: String,
    node: ASTNode,
    annotatorSettings: AnnotatorSettings,
) {
    val full = node.type == MarkdownElementTypes.FULL_REFERENCE_LINK
    val labelNode = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
    val linkText = if (full) {
        node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.children?.innerList()
    } else {
        labelNode?.children?.innerList()
    }

    if (linkText == null || labelNode == null) {
        append(node.getUnescapedTextInNode(content))
        return
    }

    val label = labelNode.getUnescapedTextInNode(content)
    val url = annotatorSettings.referenceLinkHandler?.find(label)?.takeIf { it.isNotEmpty() }

    if (url != null) {
        withLink(
            LinkAnnotation.Url(
                url,
                annotatorSettings.linkTextSpanStyle,
                annotatorSettings.linkInteractionListener
            )
        ) {
            buildMarkdownAnnotatedString(content, linkText.mapAutoLinkToType(), annotatorSettings)
        }
    } else {
        // if no reference is found, reference links are just rendered as normal text.
        append(node.getUnescapedTextInNode(content))
    }
}

/**
 * Appends an auto-detected link to the `AnnotatedString.Builder`.
 *
 * @param content The content string.
 * @param node The AST node representing the auto link.
 * @param annotatorSettings The style to apply to the link text.
 */
internal fun AnnotatedString.Builder.appendAutoLink(
    content: String,
    node: ASTNode,
    annotatorSettings: AnnotatorSettings,
) {
    val targetNode = node.children.firstOrNull {
        it.type.name == MarkdownElementTypes.AUTOLINK.name
    } ?: node
    val destination = targetNode.getUnescapedTextInNode(content)

    annotatorSettings.referenceLinkHandler?.store(destination, destination)
    withLink(
        LinkAnnotation.Url(
            destination,
            annotatorSettings.linkTextSpanStyle,
            linkInteractionListener = annotatorSettings.linkInteractionListener
        )
    ) {
        append(destination)
    }
}

/**
 * Builds an [AnnotatedString] with the contents of the given Markdown [ASTNode] node.
 *
 * This method automatically constructs the string with child components like:
 * - Paragraph
 * - Image
 * - Strong
 * - ...
 */
fun AnnotatedString.Builder.buildMarkdownAnnotatedString(
    content: String,
    node: ASTNode,
    annotatorSettings: AnnotatorSettings,
) = buildMarkdownAnnotatedString(
    content = content,
    children = node.children,
    annotatorSettings = annotatorSettings,
)

/**
 * Builds an [AnnotatedString] with the contents of the given Markdown [ASTNode] node.
 *
 * This method automatically constructs the string with child components like:
 * - Paragraph
 * - Image
 * - Strong
 * - ...
 */
fun AnnotatedString.Builder.buildMarkdownAnnotatedString(
    content: String,
    children: List<ASTNode>,
    annotatorSettings: AnnotatorSettings,
) {
    val annotate = annotatorSettings.annotator.annotate
    val annotateSequence = annotatorSettings.annotator.annotateSequence
    val eolAsNewLine = annotatorSettings.annotator.config.eolAsNewLine
    var skipIfNext: Any? = null
    var childIndex = 0
    while (childIndex < children.size) {
        val child = children[childIndex]
        if (skipIfNext == null) {
            val consumed = annotateSequence?.invoke(this, content, children, childIndex, annotatorSettings) ?: 0
            if (consumed > 0) {
                childIndex += consumed
                continue
            }
        }
        if (skipIfNext == null || skipIfNext != child.type) {
            if (annotate == null || !annotate(content, child, annotatorSettings)) {
                val parentType = child.parent?.type

                when (child.type) {
                    // Element types
                    MarkdownElementTypes.PARAGRAPH -> buildMarkdownAnnotatedString(
                        content = content,
                        node = child,
                        annotatorSettings = annotatorSettings
                    )

                    MarkdownElementTypes.IMAGE -> child.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)
                        ?.let {
                            appendInlineContent(MARKDOWN_TAG_IMAGE_URL, it.getUnescapedTextInNode(content))
                        }

                    MarkdownElementTypes.EMPH -> {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        buildMarkdownAnnotatedString(content, child, annotatorSettings)
                        pop()
                    }

                    MarkdownElementTypes.STRONG -> {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        buildMarkdownAnnotatedString(content, child, annotatorSettings)
                        pop()
                    }

                    GFMElementTypes.STRIKETHROUGH -> {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        buildMarkdownAnnotatedString(content, child, annotatorSettings)
                        pop()
                    }

                    MarkdownElementTypes.CODE_SPAN -> {
                        val codeStart = length
                        pushStyle(annotatorSettings.codeSpanStyle)
                        append(SLOCK_INLINE_CODE_EDGE_PADDING)
                        val charWrapThreshold = slockInlineCodeCharWrapBridgeThreshold()
                        if (charWrapThreshold != null) {
                            // task #401 A-bridge (see the expect in
                            // SlockKuiklyPlatformCapabilities.kt): on platforms whose
                            // native render lacks the inline-code chip, append the code
                            // text with zero-width-space break opportunities so long
                            // runs wrap at character boundaries. Code spans carry no
                            // nested markup, so flattening the recursion's output text
                            // is lossless here.
                            val codeText =
                                AnnotatedString.Builder().apply {
                                    buildMarkdownAnnotatedString(
                                        content,
                                        child.children.innerList(),
                                        annotatorSettings
                                    )
                                }.toAnnotatedString().text
                            append(codeText.withSlockInlineCodeCharWrapBreaks(charWrapThreshold))
                        } else {
                            buildMarkdownAnnotatedString(content, child.children.innerList(), annotatorSettings)
                        }
                        append(SLOCK_INLINE_CODE_EDGE_PADDING)
                        pop()
                        addStringAnnotation(
                            tag = SLOCK_INLINE_CODE_ANNOTATION_TAG,
                            annotation = "true",
                            start = codeStart,
                            end = length
                        )
                    }

                    MarkdownElementTypes.AUTOLINK -> appendAutoLink(content, child, annotatorSettings)
                    MarkdownElementTypes.INLINE_LINK -> appendMarkdownLink(content, child, annotatorSettings)
                    MarkdownElementTypes.SHORT_REFERENCE_LINK -> appendMarkdownReference(
                        content,
                        child,
                        annotatorSettings
                    )

                    MarkdownElementTypes.FULL_REFERENCE_LINK -> appendMarkdownReference(
                        content,
                        child,
                        annotatorSettings
                    )

                    // Token Types
                    MarkdownTokenTypes.TEXT -> append(child.getUnescapedTextInNode(content))
                    GFMTokenTypes.GFM_AUTOLINK -> if (child.parent == MarkdownElementTypes.LINK_TEXT) {
                        append(child.getUnescapedTextInNode(content))
                    } else appendAutoLink(content, child, annotatorSettings)

                    MarkdownTokenTypes.SINGLE_QUOTE -> append('\'')
                    MarkdownTokenTypes.DOUBLE_QUOTE -> append('\"')
                    MarkdownTokenTypes.LPAREN -> append('(')
                    MarkdownTokenTypes.RPAREN -> append(')')
                    MarkdownTokenTypes.LBRACKET -> append('[')
                    MarkdownTokenTypes.RBRACKET -> append(']')
                    MarkdownTokenTypes.LT -> append('<')
                    MarkdownTokenTypes.GT -> append('>')
                    MarkdownTokenTypes.COLON -> append(':')
                    MarkdownTokenTypes.EXCLAMATION_MARK -> append('!')
                    MarkdownTokenTypes.BACKTICK -> append('`')
                    MarkdownTokenTypes.HARD_LINE_BREAK -> {
                        append('\n')
                        skipIfNext = MarkdownTokenTypes.EOL
                    }

                    MarkdownTokenTypes.EMPH -> if (parentType != MarkdownElementTypes.EMPH && parentType != MarkdownElementTypes.STRONG) append(
                        '*'
                    )

                    MarkdownTokenTypes.EOL -> if (eolAsNewLine) append('\n') else append(' ')
                    MarkdownTokenTypes.WHITE_SPACE -> if (length > 0) {
                        val whitespaceStart = length
                        append(' ')
                        if (children.getOrNull(childIndex - 1)?.type == MarkdownElementTypes.CODE_SPAN) {
                            addStringAnnotation(
                                tag = SLOCK_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG,
                                annotation = "true",
                                start = whitespaceStart,
                                end = length
                            )
                        }
                    }
                    MarkdownTokenTypes.BLOCK_QUOTE -> {
                        skipIfNext = MarkdownTokenTypes.WHITE_SPACE
                    }
                }
            }
        } else {
            skipIfNext = null
        }
        childIndex++
    }
}
