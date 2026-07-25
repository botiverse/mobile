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
import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.RaftMarkdownTokenTypes
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
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import build.raft.app.markdown.SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
import build.raft.app.markdown.SLOCK_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK
import build.raft.app.markdown.SLOCK_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG
import build.raft.app.markdown.slockMarkdownInlineCodeEdgeStyle
import build.raft.app.slockInlineCodeCharWrapBridgeThreshold
import build.raft.app.slockInlineCodeUsesEdgePaddingBridge
import kotlin.text.CharCategory

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
                    result.append(SLOCK_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK)
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
    flavour: MarkdownFlavourDescriptor = SlockGFMFlavourDescriptor(),
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
    flavour: MarkdownFlavourDescriptor = SlockGFMFlavourDescriptor(),
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
        when (this[index]) {
            ' ', '\t', '\n', '\r' -> {
                endExclusive = index
                break
            }
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

    while (endExclusive > 0 && this[endExclusive - 1] in ".,;:!?") {
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
                        val charWrapThreshold = slockInlineCodeCharWrapBridgeThreshold()
                        val usesEdgePaddingBridge = slockInlineCodeUsesEdgePaddingBridge()
                        val codeText =
                            if (charWrapThreshold != null) {
                                // task #401 A-bridge (see the expect in
                                // SlockKuiklyPlatformCapabilities.kt): on platforms whose
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
                                rawCodeText.withSlockInlineCodeCharWrapBreaks(charWrapThreshold)
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
                            appendSlockInlineCodeWithEdgeBoxes(codeText)
                        } else {
                            append(codeText)
                        }
                        pop()
                        addStringAnnotation(
                            tag = SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
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
                    // `artin <artin@cat.ms>` as "artin <>" (task #493). Verbatim in
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
                                tag = SLOCK_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG,
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

private fun AnnotatedString.Builder.appendSlockInlineCodeWithEdgeBoxes(codeText: String) {
    if (codeText.isEmpty()) return
    val firstEnd = codeText.slockExtendedGraphemeEnd(0)
    if (firstEnd == codeText.length) {
        // A one-child InlineBoxGroup lowers to an atomic ReplacementSpan whose
        // background is line-height tall. Prefer clean, exact single-glyph
        // rendering over reintroducing that taller gray layer.
        append(codeText)
        return
    }

    // One transparent group reserves both 4dp edges. The empty style boundary
    // produces two native TextSpan children without splitting the first
    // extended grapheme, keeping the group non-atomic so its ordinary black/5
    // child backgrounds retain the same height as the rest of inline code.
    pushStyle(slockInlineCodeEdgeSpanStyle(leading = true, trailing = true))
    pushStyle(SpanStyle())
    append(codeText.substring(0, firstEnd))
    pop()
    append(codeText.substring(firstEnd))
    pop()
}

private fun slockInlineCodeEdgeSpanStyle(
    leading: Boolean,
    trailing: Boolean = !leading,
): SpanStyle =
    SpanStyle(
        // The edge box only reserves the Web-aligned 4dp advance. Its native
        // fill stays transparent so the ordinary code span remains the sole
        // black/5 painter and cannot produce an offset outer gray step.
        inlineBoxStyle = slockMarkdownInlineCodeEdgeStyle(leading, trailing),
    )

private data class SlockCodePoint(
    val value: Int,
    val end: Int,
)

private fun String.slockCodePointAt(index: Int): SlockCodePoint {
    val first = this[index]
    if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        val value =
            0x10000 +
                ((first.code - 0xD800) shl 10) +
                (this[index + 1].code - 0xDC00)
        return SlockCodePoint(value = value, end = index + 2)
    }
    return SlockCodePoint(value = first.code, end = index + 1)
}

private fun String.slockExtendedGraphemeEnd(start: Int): Int {
    var current = slockCodePointAt(start)
    var end = current.end
    var regionalIndicatorCount = if (current.value.isSlockRegionalIndicator()) 1 else 0
    while (end < length) {
        val next = slockCodePointAt(end)
        val joins =
            (current.value == SLOCK_CARRIAGE_RETURN && next.value == SLOCK_LINE_FEED) ||
                current.value.isSlockHangulL() && next.value.isSlockHangulLvtFollower() ||
                current.value.isSlockHangulLvOrV() && next.value.isSlockHangulVtFollower() ||
                current.value.isSlockHangulLvtOrT() && next.value.isSlockHangulT() ||
                current.value.isSlockGraphemePrepend() ||
                next.value.isSlockGraphemeExtend() ||
                next.value.isSlockSpacingMark() ||
                current.value == SLOCK_ZERO_WIDTH_JOINER ||
                (current.value.isSlockRegionalIndicator() &&
                    next.value.isSlockRegionalIndicator() &&
                    regionalIndicatorCount % 2 == 1)
        if (!joins) break
        end = next.end
        current = next
        regionalIndicatorCount =
            if (next.value.isSlockRegionalIndicator()) regionalIndicatorCount + 1 else 0
    }
    return end
}

private fun Int.isSlockGraphemeExtend(): Boolean =
    this == SLOCK_ZERO_WIDTH_JOINER ||
        this in 0xFE00..0xFE0F ||
        this in 0xE0100..0xE01EF ||
        this in 0x1F3FB..0x1F3FF ||
        this in 0xE0020..0xE007F ||
        (this <= Char.MAX_VALUE.code &&
            when (toChar().category) {
                CharCategory.NON_SPACING_MARK,
                CharCategory.ENCLOSING_MARK -> true
                else -> false
            })

private fun Int.isSlockSpacingMark(): Boolean =
    this <= Char.MAX_VALUE.code && toChar().category == CharCategory.COMBINING_SPACING_MARK

private fun Int.isSlockRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

private fun Int.isSlockHangulL(): Boolean = this in 0x1100..0x115F || this in 0xA960..0xA97C

private fun Int.isSlockHangulV(): Boolean = this in 0x1160..0x11A7 || this in 0xD7B0..0xD7C6

private fun Int.isSlockHangulT(): Boolean = this in 0x11A8..0x11FF || this in 0xD7CB..0xD7FB

private fun Int.isSlockHangulLv(): Boolean = this in 0xAC00..0xD7A3 && (this - 0xAC00) % 28 == 0

private fun Int.isSlockHangulLvt(): Boolean = this in 0xAC00..0xD7A3 && !isSlockHangulLv()

private fun Int.isSlockHangulLvtFollower(): Boolean =
    isSlockHangulL() || isSlockHangulV() || isSlockHangulLv() || isSlockHangulLvt()

private fun Int.isSlockHangulLvOrV(): Boolean = isSlockHangulLv() || isSlockHangulV()

private fun Int.isSlockHangulVtFollower(): Boolean = isSlockHangulV() || isSlockHangulT()

private fun Int.isSlockHangulLvtOrT(): Boolean = isSlockHangulLvt() || isSlockHangulT()

private fun Int.isSlockGraphemePrepend(): Boolean =
    this in 0x0600..0x0605 ||
        this == 0x06DD ||
        this == 0x070F ||
        this == 0x08E2 ||
        this == 0x110BD ||
        this == 0x110CD

private const val SLOCK_CARRIAGE_RETURN = 0x000D
private const val SLOCK_LINE_FEED = 0x000A
private const val SLOCK_ZERO_WIDTH_JOINER = 0x200D
