package com.tencent.kuiklybase.markdown.annotator

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.Composable
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandler
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.RaftMarkdownTokenTypes
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.utils.CJK_URL_BOUNDARY_PUNCTUATION
import com.tencent.kuiklybase.markdown.utils.isCjkTextOrWhitespace
import com.tencent.kuiklybase.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.tencent.kuiklybase.markdown.utils.findChildOfTypeRecursive
import com.tencent.kuiklybase.markdown.utils.getUnescapedTextInNode
import com.tencent.kuiklybase.markdown.utils.innerList
import com.tencent.kuiklybase.markdown.utils.mapAutoLinkToType
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import build.raft.app.markdown.RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
import build.raft.app.markdown.RAFT_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK
import build.raft.app.markdown.RAFT_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG
import build.raft.app.markdown.RAFT_INLINE_BOX_ANNOTATION_TAG
import build.raft.app.markdown.raftMarkdownInlineCodeEdgeStyle
import build.raft.app.raftFirstComposedCharacterEnd
import build.raft.app.raftInlineCodeCharWrapBridgeThreshold
import build.raft.app.raftInlineCodeUsesEdgePaddingBridge

// task #401 A-bridge: interleave zero-width spaces into non-whitespace runs
// longer than [threshold] so renders without native inline-code atomization
// (OHOS/iOS) can wrap long code at any character boundary, matching web and
// the Android render's char-level behavior. Never splits a surrogate pair.
internal fun String.withRaftInlineCodeCharWrapBreaks(threshold: Int): String {
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
                    result.append(RAFT_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK)
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
    flavour: MarkdownFlavourDescriptor = RaftGFMFlavourDescriptor(),
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
    flavour: MarkdownFlavourDescriptor = RaftGFMFlavourDescriptor(),
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
    followingNodes: List<ASTNode> = emptyList(),
    visiblePrefixAlreadyAppended: String = "",
): Int {
    val targetNode = node.children.firstOrNull {
        it.type.name == MarkdownElementTypes.AUTOLINK.name
    } ?: node
    val rawDestination = targetNode.getUnescapedTextInNode(content)
    val (boundedDestination, trailingText) = rawDestination.splitAutoLinkDestination()
    val expectedClosers = boundedDestination.unclosedAutoLinkDelimiters()
    var consumedFollowingNodes = 0
    var destination = visiblePrefixAlreadyAppended + boundedDestination
    for (expected in expectedClosers) {
        val following = followingNodes.getOrNull(consumedFollowingNodes) ?: break
        if (following.getUnescapedTextInNode(content) != expected.toString()) break
        destination += expected
        consumedFollowingNodes++
    }

    annotatorSettings.referenceLinkHandler?.store(destination, destination)
    val link =
        LinkAnnotation.Url(
            destination,
            annotatorSettings.linkTextSpanStyle,
            linkInteractionListener = annotatorSettings.linkInteractionListener
        )
    if (visiblePrefixAlreadyAppended.isEmpty()) {
        withLink(link) {
            append(destination)
        }
    } else {
        val linkStart = length - visiblePrefixAlreadyAppended.length
        append(destination.removePrefix(visiblePrefixAlreadyAppended))
        addLink(link, linkStart, length)
    }
    append(trailingText)
    return consumedFollowingNodes
}

/**
 * GFM's permissive auto-link token can include following prose when a URL is
 * adjacent to an unmatched closing delimiter (for example `[https://x] now`).
 * Keep balanced delimiters inside the URL, but return unmatched closers,
 * whitespace tails, and terminal prose punctuation to normal visible text.
 */
private fun String.splitAutoLinkDestination(): Pair<String, String> {
    if (isEmpty()) return this to ""

    var roundDepth = 0
    var squareDepth = 0
    var curlyDepth = 0
    var angleDepth = 0
    var endExclusive = length
    for (index in indices) {
        val c = this[index]
        if (c in " \t\n\r" || c in CJK_URL_BOUNDARY_PUNCTUATION) {
            endExclusive = index
            break
        }
        if (c == ',' && index + 1 < length && this[index + 1].isCjkTextOrWhitespace()) {
            endExclusive = index
            break
        }
        when (c) {
            '(' -> roundDepth++
            '[' -> squareDepth++
            '{' -> curlyDepth++
            '<' -> angleDepth++
            ')' ->
                if (roundDepth > 0) {
                    roundDepth--
                } else {
                    endExclusive = index
                    break
                }
            ']' ->
                if (squareDepth > 0) {
                    squareDepth--
                } else {
                    endExclusive = index
                    break
                }
            '}' ->
                if (curlyDepth > 0) {
                    curlyDepth--
                } else {
                    endExclusive = index
                    break
                }
            '>' ->
                if (angleDepth > 0) {
                    angleDepth--
                } else {
                    endExclusive = index
                    break
                }
        }
    }

    while (endExclusive > 0 && (this[endExclusive - 1] in ".,;:!?" || this[endExclusive - 1] in CJK_URL_BOUNDARY_PUNCTUATION)) {
        endExclusive--
    }
    return substring(0, endExclusive) to substring(endExclusive)
}

private fun String.unclosedAutoLinkDelimiters(): List<Char> {
    val stack = mutableListOf<Char>()
    forEach { char ->
        when (char) {
            '(' -> stack += ')'
            '[' -> stack += ']'
            '{' -> stack += '}'
            '<' -> stack += '>'
            ')', ']', '}', '>' -> if (stack.lastOrNull() == char) stack.removeAt(stack.lastIndex)
        }
    }
    return stack.asReversed()
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
    insideCodeSpan: Boolean = false,
) = buildMarkdownAnnotatedString(
    content = content,
    children = node.children,
    annotatorSettings = annotatorSettings,
    insideCodeSpan = insideCodeSpan,
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
    insideCodeSpan: Boolean = false,
) {
    val annotate = annotatorSettings.annotator.annotate
    val annotateSequence = annotatorSettings.annotator.annotateSequence
    val eolAsNewLine = annotatorSettings.annotator.config.eolAsNewLine
    var skipIfNext: Any? = null
    var childIndex = 0
    while (childIndex < children.size) {
        val child = children[childIndex]
        // A code span is verbatim markdown content. Its delimiter nodes have
        // already been removed by innerList(), so preserve every remaining
        // AST node literally instead of routing it through the normal markup
        // dispatcher. Generic angle-bracket placeholders such as <app> lex as
        // HTML_TAG and were previously dropped because that token deliberately
        // has no normal-rendering branch (task #502).
        if (insideCodeSpan) {
            append(child.getUnescapedTextInNode(content))
            childIndex++
            continue
        }
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
                        val charWrapThreshold = raftInlineCodeCharWrapBridgeThreshold()
                        val usesEdgePaddingBridge = raftInlineCodeUsesEdgePaddingBridge()
                        val codeText =
                            if (charWrapThreshold != null) {
                                // task #401 A-bridge (see the expect in
                                // RaftPlatformCapabilities.kt): on platforms whose
                                // native layout cannot atomize long inline-code runs, append
                                // zero-width-space break opportunities so they wrap at
                                // character boundaries. Code spans carry no
                                // nested markup, so flattening the recursion's output text
                                // is lossless here.
                                val rawCodeText =
                                    AnnotatedString.Builder().apply {
                                        buildMarkdownAnnotatedString(
                                            content,
                                            child.children.innerList(),
                                            annotatorSettings,
                                            insideCodeSpan = true
                                        )
                                    }.toAnnotatedString().text
                                rawCodeText.withRaftInlineCodeCharWrapBreaks(charWrapThreshold)
                            } else {
                                AnnotatedString.Builder().apply {
                                    buildMarkdownAnnotatedString(
                                        content,
                                        child.children.innerList(),
                                        annotatorSettings,
                                        insideCodeSpan = true
                                    )
                                }.toAnnotatedString().text
                            }
                        if (usesEdgePaddingBridge) {
                            appendRaftInlineCodeWithEdgeBoxes(codeText)
                        } else {
                            append(codeText)
                        }
                        pop()
                        addStringAnnotation(
                            tag = RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
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
                    RaftMarkdownTokenTypes.REFERENCE -> append(child.getUnescapedTextInNode(content))
                    GFMTokenTypes.GFM_AUTOLINK -> if (insideCodeSpan || child.parent == MarkdownElementTypes.LINK_TEXT) {
                        // Code spans are verbatim (task #493): no linkification.
                        append(child.getUnescapedTextInNode(content))
                    } else {
                        // GFM can tokenize a CJK-adjacent `https://www...` as
                        // visible `https`, `:`, `//`, then a `www...` autolink.
                        // Extend the annotation back over that already-rendered
                        // scheme so display text and clickable destination agree.
                        val sourcePrefix = content.substring(0, child.startOffset)
                        val visibleScheme =
                            when {
                                sourcePrefix.endsWith("https://") -> "https://"
                                sourcePrefix.endsWith("http://") -> "http://"
                                else -> ""
                            }
                        childIndex +=
                            appendAutoLink(
                                content = content,
                                node = child,
                                annotatorSettings = annotatorSettings,
                                followingNodes = children.drop(childIndex + 1),
                                visiblePrefixAlreadyAppended = visibleScheme,
                            )
                    }

                    // The lexer emits EMAIL_AUTOLINK for "user@host" between angle
                    // brackets even inside code spans. Without this branch the token
                    // fell through the when and was silently DROPPED, rendering
                    // `artin <artin@example.test>` as "artin <>" (task #493). Verbatim in
                    // code spans; a mailto link elsewhere (GFM behavior, web parity).
                    MarkdownTokenTypes.EMAIL_AUTOLINK ->
                        if (insideCodeSpan) {
                            append(child.getUnescapedTextInNode(content))
                        } else {
                            skipIfNext = MarkdownTokenTypes.GT
                            val email = child.getUnescapedTextInNode(content)
                            annotatorSettings.referenceLinkHandler?.store(email, "mailto:$email")
                            withLink(
                                LinkAnnotation.Url(
                                    "mailto:$email",
                                    annotatorSettings.linkTextSpanStyle,
                                    linkInteractionListener = annotatorSettings.linkInteractionListener
                                )
                            ) {
                                append(email)
                            }
                        }

                    // A backslash-escaped backtick run ("\`") lexes as its own token.
                    // Without a branch it fell through the when and was silently
                    // DROPPED (patrol renderer-coverage, same class as EMAIL_AUTOLINK
                    // above); unescaping renders the literal backtick(s).
                    MarkdownTokenTypes.ESCAPED_BACKTICKS -> append(child.getUnescapedTextInNode(content))

                    // GFM lexes every "~" as TILDE. Paired runs become a
                    // STRIKETHROUGH element whose children still include the
                    // delimiter TILDE tokens (the element branch recurses), so only
                    // an unpaired tilde in plain text should render — mirroring the
                    // EMPH delimiter guard below. Without this branch "~5 min"
                    // rendered as "5 min".
                    GFMTokenTypes.TILDE -> if (parentType != GFMElementTypes.STRIKETHROUGH) append('~')

                    MarkdownTokenTypes.SINGLE_QUOTE -> append('\'')
                    MarkdownTokenTypes.DOUBLE_QUOTE -> append('\"')
                    MarkdownTokenTypes.LPAREN -> append('(')
                    MarkdownTokenTypes.RPAREN -> append(')')
                    MarkdownTokenTypes.LBRACKET -> append('[')
                    MarkdownTokenTypes.RBRACKET -> append(']')
                    MarkdownTokenTypes.LT ->
                        // GFM consumes the angle brackets around an email
                        // autolink (web renders just the linked address); keep
                        // them only inside code spans where content is verbatim.
                        if (insideCodeSpan || children.getOrNull(childIndex + 1)?.type != MarkdownTokenTypes.EMAIL_AUTOLINK) {
                            append('<')
                        }
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
                                tag = RAFT_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG,
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

private fun AnnotatedString.Builder.appendRaftInlineCodeWithEdgeBoxes(codeText: String) =
    appendRaftInlineCodeWithEdgeBoxes(codeText, raftFirstComposedCharacterEnd(codeText))

internal fun AnnotatedString.Builder.appendRaftInlineCodeWithEdgeBoxes(
    codeText: String,
    firstComposedCharacterEnd: Int?,
) {
    if (codeText.isEmpty()) return
    // Raft (CMP switch): upstream SpanStyle has no inlineBoxStyle; the chip box is a string
    // annotation painted by build.raft.app.markdown.raftInlineDecorations. Both pop() calls
    // below close this annotation.
    pushStringAnnotation(
        tag = RAFT_INLINE_BOX_ANNOTATION_TAG,
        annotation = raftMarkdownInlineCodeEdgeStyle(leading = true, trailing = true).encode()
    )
    // task #104: a platform without a composed-character segmenter (OHOS) must
    // still own the chip chrome. A single-child group is the exact shape a
    // one-grapheme code span already produces on every platform; wrap
    // opportunities come from the char-wrap bridge, not from this split.
    if (firstComposedCharacterEnd == null || firstComposedCharacterEnd !in 1 until codeText.length) {
        append(codeText)
        pop()
        return
    }
    val firstEnd = firstComposedCharacterEnd

    // Force at least two children without splitting the first composed
    // character. The group remains wrapping/non-atomic while one box owns both
    // edge insets and a consistent background height.
    pushStyle(SpanStyle())
    append(codeText.substring(0, firstEnd))
    pop()
    append(codeText.substring(firstEnd))
    pop()
}
