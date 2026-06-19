package ai.slock.compose.markdown

import ai.slock.compose.SlockColor
import ai.slock.compose.SlockSpacing
import ai.slock.compose.SlockStroke
import ai.slock.compose.SlockType
import ai.slock.compose.slockFontFamilyForWeight
import ai.slock.compose.withResolvedSlockFontFamily
import ai.slock.compose.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.text.InlineTextContent
import com.tencent.kuikly.compose.foundation.text.appendInlineContent
import com.tencent.kuikly.compose.material3.Text as KuiklyText
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.Placeholder
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.TextLayoutResult
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.LocalUriHandler
import com.tencent.kuiklybase.markdown.annotator.UriHandler
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.Markdown
import com.tencent.kuiklybase.markdown.compose.LocalTextProvider
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.TextProvider
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownAnnotatorConfig
import com.tencent.kuiklybase.markdown.model.markdownPadding
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.time.TimeSource

data class ThreadMarkdownTag(
    val label: String,
    val kind: ThreadMarkdownTagKind,
    val channelName: String? = null,
    val threadShortId: String? = null
)

enum class ThreadMarkdownTagKind {
    ChannelThread,
    Channel,
    Task,
    UserMention
}

data class ThreadInlineTagMatch(
    val id: String,
    val tag: ThreadMarkdownTag,
    val range: IntRange
)

data class ThreadInlineCodeMatch(
    val id: String,
    val code: String,
    val range: IntRange
)

data class SlockMarkdownPreviewSample(
    val title: String,
    val body: String,
    val description: String
)

object SlockMarkdownPreviewSamples {
    val inlineTags =
        SlockMarkdownPreviewSample(
            title = "Inline tags",
            description = "Channels, task references, and user mentions should render as inline chips.",
            body = "Please review #22 with @alice in #android-dev, then compare #382-#383."
        )

    val markdownBlocks =
        SlockMarkdownPreviewSample(
            title = "Markdown blocks",
            description = "Lists, quotes, paragraphs, and inline code should keep normal markdown behavior.",
            body =
                """
                **Markdown** body under the sender:

                - lists stay left aligned
                - `inline code #999` stays code, not a task chip
                - tags like #283 and #android-dev become clickable chips

                > quoted text remains inside the message column
                """.trimIndent()
        )

    val codeFence =
        SlockMarkdownPreviewSample(
            title = "Code fence",
            description = "Code fences should keep syntax highlighting and avoid inline tag replacement.",
            body =
                """
                ```kotlin
                val task = "#123 should stay code"
                println(task)
                ```
                """.trimIndent()
        )

    val image =
        SlockMarkdownPreviewSample(
            title = "Image",
            description = "Remote markdown images should render through the platform image pipeline.",
            body = "![Raft preview](https://picsum.photos/seed/raft-markdown/480/240)"
        )

    val all = listOf(inlineTags, markdownBlocks, codeFence, image)
}

fun String.normalizeThreadMarkdownBody(): String {
    if (!contains("\\n") && !contains("\\r")) return this

    val shouldDecodeEscapedLineBreaks =
        threadEscapedMarkdownLineBreakRegex().containsMatchIn(this) ||
            (!contains('\n') && !contains('\r') && contains("\\n"))
    if (!shouldDecodeEscapedLineBreaks) return this

    return replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\r")
}

private fun threadEscapedMarkdownLineBreakRegex(): Regex =
    Regex("""\\r?\\n(?:\\r?\\n|[-*+] |\d+\. |> |#|```|~~~)""")

fun String.escapeThreadMarkdownHtmlTagsForThread(): String {
    val protectedRanges = threadMarkdownCodeProtectionRanges()
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val tagMatch = ThreadMarkdownHtmlTagRegex.find(this, index)
        if (tagMatch == null) {
            builder.append(substring(index))
            break
        }
        builder.append(substring(index, tagMatch.range.first))
        if (protectedRanges.any { tagMatch.range.first in it }) {
            builder.append(tagMatch.value)
        } else {
            builder.append(tagMatch.value.escapeMarkdownHtmlTagText())
        }
        index = tagMatch.range.last + 1
    }
    return builder.toString()
}

private fun String.threadMarkdownCodeProtectionRanges(): List<IntRange> =
    buildList {
        addAll(threadMarkdownFenceRanges())
        addAll(threadInlineCodeMatches().map { it.range })
        addAll(threadMarkdownAutolinkRanges())
    }

private fun String.threadMarkdownFenceRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    val fenceRegex = Regex("""(?m)^(```|~~~).*$""")
    var searchIndex = 0
    while (searchIndex < length) {
        val start = fenceRegex.find(this, searchIndex) ?: break
        val marker = start.value.take(3)
        val end = fenceRegex.find(this, start.range.last + 1) {
            it.value.startsWith(marker)
        }
        if (end == null) {
            ranges += start.range.first until length
            break
        }
        ranges += start.range.first..end.range.last
        searchIndex = end.range.last + 1
    }
    return ranges
}

private fun Regex.find(input: CharSequence, startIndex: Int, predicate: (MatchResult) -> Boolean): MatchResult? {
    var match = find(input, startIndex)
    while (match != null) {
        if (predicate(match)) return match
        match = match.next()
    }
    return null
}

private fun String.threadMarkdownAutolinkRanges(): List<IntRange> =
    ThreadMarkdownAutolinkRegex.findAll(this).map { it.range }.toList()

private val ThreadMarkdownHtmlTagRegex =
    Regex("""</?[A-Za-z][A-Za-z0-9:-]*(?:\s+[^<>]*)?>""")

private val ThreadMarkdownAutolinkRegex =
    Regex("""<https?://[^>\s]+>""")

internal fun String.shouldUseSlockMarkdownPlainTextFastPath(): Boolean {
    if (isEmpty() || contains("://")) return false
    return none { it in SlockMarkdownPlainTextFastPathBlockers }
}

private val SlockMarkdownPlainTextFastPathBlockers =
    setOf('\\', '\n', '\r', '`', '*', '_', '~', '[', ']', '<', '>', '#', '@', '|')

internal fun String.shouldUseSlockMarkdownInlineTextFastPath(): Boolean {
    if (isEmpty() || contains("://")) return false
    if (any { it in SlockMarkdownInlineTextFastPathBlockers }) return false
    return !hasSlockMarkdownBlockShape()
}

private val SlockMarkdownInlineTextFastPathBlockers =
    setOf('\\', '\n', '\r', '[', ']', '<', '>', '|')

private fun String.hasSlockMarkdownBlockShape(): Boolean {
    val trimmed = trimStart()
    return SlockMarkdownHeadingPrefixRegex.containsMatchIn(trimmed) ||
        trimmed.startsWith("> ") ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.startsWith("+ ") ||
        trimmed.startsWith("```") ||
        trimmed.startsWith("~~~") ||
        SlockMarkdownOrderedListPrefixRegex.containsMatchIn(trimmed) ||
        SlockMarkdownThematicBreakRegex.containsMatchIn(trimmed)
}

private val SlockMarkdownHeadingPrefixRegex = Regex("""^#{1,6}\s""")
private val SlockMarkdownOrderedListPrefixRegex = Regex("""^\d+[.)]\s""")
private val SlockMarkdownThematicBreakRegex = Regex("""^(?:-{3,}|\*{3,}|_{3,})\s*$""")

private fun String.escapeMarkdownHtmlTagText(): String =
    replace("<", "\\<").replace(">", "\\>")

fun threadMarkdownTaskReferenceKey(label: String): String =
    label.removePrefix("#").trim().lowercase()

fun threadMarkdownTagDisplayLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return trimmed
    val prefix = if (trimmed.startsWith("@")) "@" else if (trimmed.startsWith("#")) "#" else ""
    if (prefix.isEmpty()) return trimmed
    return prefix + trimmed.dropWhile { it == prefix.first() }
}

private fun threadMarkdownMentionKey(label: String): String =
    label.removePrefix("@").trim().lowercase()

private fun Set<String>.containsThreadMention(label: String): Boolean {
    val key = threadMarkdownMentionKey(label)
    return key.isNotBlank() && contains(key)
}

private fun String.toThreadMarkdownTagOrNull(): ThreadMarkdownTag? =
    when {
        startsWith('@') -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.UserMention)
        drop(1).all { it.isDigit() } -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.Task)
        isThreadChannelReference() -> {
            val channel = substringAfter("#").substringBefore(":")
            val shortId = substringAfter(":")
            ThreadMarkdownTag(
                label = this,
                kind = ThreadMarkdownTagKind.ChannelThread,
                channelName = channel,
                threadShortId = shortId
            )
        }
        startsWith('#') -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.Channel)
        else -> null
    }

private fun String.isThreadChannelReference(): Boolean {
    if (!startsWith('#')) return false
    val colonIndex = indexOf(':')
    if (colonIndex <= 1 || colonIndex == lastIndex) return false
    val shortId = substring(colonIndex + 1)
    return shortId.length in 6..8 && shortId.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

fun String.threadInlineTagMatches(baseOffset: Int = 0): List<ThreadInlineTagMatch> =
    threadInlineTagRanges().mapNotNull { range ->
        substring(range).toThreadMarkdownTagOrNull()?.let { tag ->
            ThreadInlineTagMatch(
                id = "slock:${baseOffset + range.first}",
                tag = tag,
                range = range
            )
        }
    }

private fun String.threadInlineTagRanges(): List<IntRange> {
    val protectedRanges = threadMarkdownCodeProtectionRanges()
    val ranges = mutableListOf<IntRange>()
    var index = 0
    while (index < length) {
        if (protectedRanges.any { index in it }) {
            index++
            continue
        }
        val char = this[index]
        val range =
            when (char) {
                '@' -> scanThreadMentionRange(index)
                '#' -> scanThreadHashRange(index)
                else -> null
            }
        if (range != null && isThreadInlineTagBoundary(index - 1)) {
            ranges += range
            index = range.last + 1
        } else {
            index++
        }
    }
    return ranges
}

private fun String.scanThreadMentionRange(start: Int): IntRange? {
    val nameStart = start + 1
    if (nameStart >= length || !this[nameStart].isLetterOrDigit()) return null
    var endExclusive = nameStart + 1
    var count = 1
    while (endExclusive < length && count < 64 && this[endExclusive].isThreadMentionPart()) {
        endExclusive++
        count++
    }
    return start until endExclusive
}

private fun String.scanThreadHashRange(start: Int): IntRange? {
    val valueStart = start + 1
    if (valueStart >= length) return null
    if (this[valueStart].isDigit()) {
        var endExclusive = valueStart + 1
        while (endExclusive < length && this[endExclusive].isDigit()) endExclusive++
        return start until endExclusive
    }
    if (!this[valueStart].isLetter()) return null

    var nameEndExclusive = valueStart + 1
    var count = 1
    while (nameEndExclusive < length && count < 64 && this[nameEndExclusive].isThreadChannelPart()) {
        nameEndExclusive++
        count++
    }
    val shortStart = nameEndExclusive + 1
    if (nameEndExclusive < length && this[nameEndExclusive] == ':' && shortStart < length) {
        var shortEndExclusive = shortStart
        while (shortEndExclusive < length &&
            shortEndExclusive - shortStart < 8 &&
            this[shortEndExclusive].isThreadShortIdPart()
        ) {
            shortEndExclusive++
        }
        val shortLength = shortEndExclusive - shortStart
        if (shortLength in 6..8 && (shortEndExclusive >= length || !this[shortEndExclusive].isThreadShortIdPart())) {
            return start until shortEndExclusive
        }
    }
    return start until nameEndExclusive
}

private fun Char.isThreadMentionPart(): Boolean =
    isLetterOrDigit() || this == '_' || this == '.' || this == '-'

private fun Char.isThreadChannelPart(): Boolean =
    isLetterOrDigit() || this == '_' || this == '-'

private fun Char.isThreadShortIdPart(): Boolean =
    isDigit() || this in 'a'..'f' || this in 'A'..'F'

private fun String.isThreadInlineTagBoundary(index: Int): Boolean =
    index < 0 || !(this[index].isLetterOrDigit() || this[index] == '_')

fun String.threadInlineCodeMatches(baseOffset: Int = 0): List<ThreadInlineCodeMatch> {
    val matches = mutableListOf<ThreadInlineCodeMatch>()
    var cursor = 0
    while (cursor < length) {
        val start = indexOf('`', startIndex = cursor)
        if (start < 0) break
        val tickCount = countBackticks(start)
        val fence = "`".repeat(tickCount)
        val end = indexOf(fence, startIndex = start + tickCount)
        if (end < 0) break
        val rawCode = substring(start + tickCount, end)
        matches +=
            ThreadInlineCodeMatch(
                id = "slock-code:${baseOffset + start}",
                code = rawCode.normalizeMarkdownInlineCodeText(),
                range = start until end + tickCount
            )
        cursor = end + tickCount
    }
    return matches
}

private fun String.countBackticks(start: Int): Int {
    var count = 0
    while (start + count < length && this[start + count] == '`') count++
    return count
}

private fun String.normalizeMarkdownInlineCodeText(): String =
    if (length >= 2 && first() == ' ' && last() == ' ' && any { it != ' ' }) {
        substring(1, lastIndex)
    } else {
        this
    }

fun slockMarkdownAnnotator(
    content: String,
    highlightedMentions: Set<String> = emptySet(),
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null
): MarkdownAnnotator {
    val diagnosticsStartedAt = onDiagnostics?.let { TimeSource.Monotonic.markNow() }
    val highlightedMentionKeys = highlightedMentions.mapTo(mutableSetOf()) { threadMarkdownMentionKey(it) }
    val inlineTagMatches = content.threadInlineTagMatches()
    val inlineTagById = inlineTagMatches.associateBy { it.id }
    val highlightedMentionById =
        inlineTagMatches
            .filter { it.tag.kind == ThreadMarkdownTagKind.UserMention && highlightedMentionKeys.containsThreadMention(it.tag.label) }
            .associateBy { it.id }
    val inlineCodeMatches = content.threadInlineCodeMatches()
    val inlineCodeChipById =
        inlineCodeMatches
            .filter { it.code.shouldRenderThreadInlineCodeAsChip() }
            .associateBy { it.id }
    if (diagnosticsStartedAt != null) {
        onDiagnostics.invoke(
            inlineTagMatches.size,
            inlineCodeMatches.size,
            diagnosticsStartedAt.elapsedNow().inWholeMilliseconds
        )
    }
    return markdownAnnotator(
        annotate = { markdownContent, child, _ ->
            if (child.type == MarkdownElementTypes.CODE_SPAN) {
                val codeMatch =
                    inlineCodeChipById["slock-code:${child.startOffset}"]
                        ?: inlineCodeChipById.values.firstOrNull { child.startOffset in it.range }
                if (codeMatch != null) {
                    appendInlineContent(codeMatch.id, codeMatch.code)
                    return@markdownAnnotator true
                }
            }
            if (child.type != MarkdownTokenTypes.TEXT ||
                child.parent?.type == MarkdownElementTypes.CODE_SPAN
            ) {
                return@markdownAnnotator false
            }

            val nodeText = child.getTextInNode(markdownContent).toString()
            val nodeMatches =
                nodeText.threadInlineTagMatches(baseOffset = child.startOffset)
                    .filter { inlineTagById.containsKey(it.id) }
            if (nodeMatches.isEmpty()) return@markdownAnnotator false

            var cursor = 0
            nodeMatches.forEach { match ->
                if (match.range.first > cursor) {
                    append(nodeText.substring(cursor, match.range.first))
                }
                appendInlineContent(match.id, match.tag.label)
                cursor = match.range.last + 1
            }
            if (cursor < nodeText.length) append(nodeText.substring(cursor))
            true
        },
        inlineContent = {
            inlineTagById.mapValues { (_, match) ->
                val label =
                    resolveTagLabel?.invoke(match.tag)?.takeIf { it.isNotBlank() }
                        ?: threadMarkdownTagDisplayLabel(match.tag.label)
                InlineTextContent(
                    Placeholder(
                        width = slockMarkdownInlineTagWidthSp(
                            label,
                            match.tag.kind,
                            highlightedMentionById.containsKey(match.id)
                        ),
                        height = SlockMarkdownInlineChipHeight
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable { onOpenTag(match.tag) },
                        contentAlignment = Alignment.Center
                    ) {
                        val isHighlightedMention = highlightedMentionById.containsKey(match.id)
                        if (match.tag.kind == ThreadMarkdownTagKind.UserMention &&
                            !isHighlightedMention
                        ) {
                            SlockMarkdownMentionText(label)
                        } else {
                            val chipColor = slockMarkdownTagColor(match.tag.kind, highlighted = isHighlightedMention)
                            SlockMarkdownInlineChip(label, chipColor)
                        }
                    }
                }
            } + inlineCodeChipById.mapValues { (_, match) ->
                InlineTextContent(
                    Placeholder(
                        width = slockMarkdownInlineCodeWidthSp(match.code),
                        height = SlockMarkdownInlineChipHeight
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SlockMarkdownInlineCodeChip(match.code)
                    }
                }
            }
        },
        config = markdownAnnotatorConfig(eolAsNewLine = true)
    )
}

private object SlockMarkdownTextProvider : TextProvider {
    @Composable
    override fun Text(
        text: String,
        modifier: Modifier,
        color: Color,
        fontSize: TextUnit,
        fontStyle: FontStyle?,
        fontWeight: FontWeight?,
        fontFamily: com.tencent.kuikly.compose.ui.text.font.FontFamily?,
        letterSpacing: TextUnit,
        textDecoration: TextDecoration?,
        textAlign: TextAlign?,
        lineHeight: TextUnit,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        minLines: Int,
        onTextLayout: ((TextLayoutResult) -> Unit)?,
        style: TextStyle
    ) {
        val resolvedStyle = style.withResolvedSlockFontFamily()
        KuiklyText(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily ?: resolvedStyle.fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout,
            style = resolvedStyle
        )
    }

    @Composable
    override fun RichText(
        text: AnnotatedString,
        modifier: Modifier,
        color: Color,
        fontSize: TextUnit,
        fontStyle: FontStyle?,
        fontWeight: FontWeight?,
        fontFamily: com.tencent.kuikly.compose.ui.text.font.FontFamily?,
        letterSpacing: TextUnit,
        textDecoration: TextDecoration?,
        textAlign: TextAlign?,
        lineHeight: TextUnit,
        overflow: TextOverflow,
        softWrap: Boolean,
        maxLines: Int,
        minLines: Int,
        inlineContent: Map<String, InlineTextContent>,
        onTextLayout: (TextLayoutResult) -> Unit,
        style: TextStyle
    ) {
        val resolvedStyle = style.withResolvedSlockFontFamily()
        KuiklyText(
            text = text.withSlockMarkdownSpanFonts(resolvedStyle),
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily ?: resolvedStyle.fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            inlineContent = inlineContent,
            onTextLayout = onTextLayout,
            style = resolvedStyle
        )
    }
}

internal fun AnnotatedString.withSlockMarkdownSpanFonts(baseStyle: TextStyle): AnnotatedString {
    if (spanStyles.isEmpty()) return this

    val resolvedBase = baseStyle.withResolvedSlockFontFamily()
    val builder = AnnotatedString.Builder(length)
    builder.append(this)
    spanStyles.forEach { range ->
        val weight = range.item.fontWeight ?: resolvedBase.fontWeight
        val shouldNormalizeFamily =
            range.item.fontFamily == null ||
                range.item.fontFamily == FontFamily.Monospace
        val family =
            if (shouldNormalizeFamily) {
                slockFontFamilyForWeight(weight)
            } else {
                range.item.fontFamily
            }
        if (shouldNormalizeFamily &&
            (range.item.fontFamily == FontFamily.Monospace || family != resolvedBase.fontFamily)
        ) {
            builder.addStyle(SpanStyle(fontFamily = family), range.start, range.end)
        }
    }
    return builder.toAnnotatedString()
}

private data class SlockMarkdownInlineTextCacheKey(
    val content: String,
    val textStyle: TextStyle
)

private object SlockMarkdownInlineTextRenderCache {
    private const val MaxEntries = 256
    private val entries = LinkedHashMap<SlockMarkdownInlineTextCacheKey, AnnotatedString>()

    fun getOrPut(
        key: SlockMarkdownInlineTextCacheKey,
        value: () -> AnnotatedString
    ): Pair<AnnotatedString, Boolean> {
        entries[key]?.let { cached ->
            return cached to true
        }
        val rendered = value()
        entries[key] = rendered
        if (entries.size > MaxEntries) {
            val firstKey = entries.keys.firstOrNull()
            if (firstKey != null) {
                entries.remove(firstKey)
            }
        }
        return rendered to false
    }
}

private fun buildSlockMarkdownInlineTextAnnotatedString(
    content: String,
    style: TextStyle,
    annotator: MarkdownAnnotator
): AnnotatedString {
    val parsedTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val textNode =
        parsedTree.children.firstOrNull { node ->
            node.type == MarkdownTokenTypes.TEXT || node.type == MarkdownElementTypes.PARAGRAPH
        } ?: return AnnotatedString(content)
    val settings =
        DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = style.toSpanStyle().copy(color = SlockMarkdownLinkBlue)),
            codeSpanStyle = slockMarkdownInlineCodeTextStyle(style).toSpanStyle(),
            annotator = annotator
        )
    return buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, textNode, settings)
        pop()
    }
}

@Composable
fun SlockMarkdownBody(
    body: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    highlightedMentions: Set<String> = emptySet(),
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    performanceKey: String? = null,
    performanceLogger: (String) -> Unit = {}
) {
    val content = remember(body, performanceKey) {
        if (performanceKey == null) {
            body.normalizeThreadMarkdownBody().escapeThreadMarkdownHtmlTagsForThread()
        } else {
            val startedAt = TimeSource.Monotonic.markNow()
            body.normalizeThreadMarkdownBody().escapeThreadMarkdownHtmlTagsForThread()
                .also { normalized ->
                    performanceLogger(
                        "thread_markdown_prepare stage=normalize key=$performanceKey " +
                            "bodyLength=${body.length} contentLength=${normalized.length} " +
                            "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                }
        }
    }
    var layoutLogged by remember(content, performanceKey) { mutableStateOf(false) }
    val layoutStartedAt = remember(content, performanceKey) { TimeSource.Monotonic.markNow() }
    val markdownModifier =
        performanceKey?.let { key ->
            modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    if (!layoutLogged) {
                        layoutLogged = true
                        performanceLogger(
                            "thread_markdown_layout key=$key contentLength=${content.length} " +
                                "durationMs=${layoutStartedAt.elapsedNow().inWholeMilliseconds}"
                        )
                    }
                }
        } ?: modifier.fillMaxWidth()
    if (content.shouldUseSlockMarkdownPlainTextFastPath()) {
        val resolvedStyle = textStyle.withResolvedSlockFontFamily()
        performanceKey?.let { key ->
            performanceLogger(
                "thread_markdown_render stage=plain_text key=$key contentLength=${content.length} durationMs=0"
            )
        }
        KuiklyText(
            text = content,
            modifier = markdownModifier,
            color = resolvedStyle.color,
            fontSize = resolvedStyle.fontSize,
            fontStyle = resolvedStyle.fontStyle,
            fontWeight = resolvedStyle.fontWeight,
            fontFamily = resolvedStyle.fontFamily,
            letterSpacing = resolvedStyle.letterSpacing,
            textDecoration = resolvedStyle.textDecoration,
            textAlign = resolvedStyle.textAlign,
            lineHeight = resolvedStyle.lineHeight,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            minLines = 1,
            style = resolvedStyle
        )
        return
    }
    val currentOnOpenTag by rememberUpdatedState(onOpenTag)
    val currentResolveTagLabel by rememberUpdatedState(resolveTagLabel)
    val colors = slockMarkdownColors()
    val typography = remember(textStyle) { slockMarkdownTypography(textStyle) }
    val annotator = remember(content, highlightedMentions, performanceKey) {
        slockMarkdownAnnotator(
            content,
            highlightedMentions = highlightedMentions,
            onOpenTag = { tag -> currentOnOpenTag(tag) },
            resolveTagLabel = { tag -> currentResolveTagLabel?.invoke(tag) ?: threadMarkdownTagDisplayLabel(tag.label) },
            onDiagnostics =
                performanceKey?.let { key ->
                    { inlineTagCount, inlineCodeCount, durationMs ->
                        performanceLogger(
                            "thread_markdown_prepare stage=annotator key=$key " +
                                "inlineTags=$inlineTagCount inlineCode=$inlineCodeCount " +
                                "contentLength=${content.length} durationMs=$durationMs"
                        )
                    }
                }
        )
    }
    if (content.shouldUseSlockMarkdownInlineTextFastPath()) {
        val resolvedStyle = textStyle.withResolvedSlockFontFamily()
        val renderStartedAt = TimeSource.Monotonic.markNow()
        val cacheKey = SlockMarkdownInlineTextCacheKey(content, resolvedStyle)
        val (annotated, cacheHit) =
            SlockMarkdownInlineTextRenderCache.getOrPut(cacheKey) {
                buildSlockMarkdownInlineTextAnnotatedString(content, resolvedStyle, annotator)
            }
        performanceKey?.let { key ->
            performanceLogger(
                "thread_markdown_render stage=inline_text key=$key contentLength=${content.length} " +
                    "cacheHit=$cacheHit durationMs=${renderStartedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        KuiklyText(
            text = annotated.withSlockMarkdownSpanFonts(resolvedStyle),
            modifier = markdownModifier,
            color = resolvedStyle.color,
            fontSize = resolvedStyle.fontSize,
            fontStyle = resolvedStyle.fontStyle,
            fontWeight = resolvedStyle.fontWeight,
            fontFamily = resolvedStyle.fontFamily,
            letterSpacing = resolvedStyle.letterSpacing,
            textDecoration = resolvedStyle.textDecoration,
            textAlign = resolvedStyle.textAlign,
            lineHeight = resolvedStyle.lineHeight,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            minLines = 1,
            inlineContent = annotator.inlineContent?.invoke().orEmpty(),
            onTextLayout = {},
            style = resolvedStyle
        )
        return
    }
    performanceKey?.let { key ->
        performanceLogger(
            "thread_markdown_render stage=markdown_renderer key=$key contentLength=${content.length}"
        )
    }
    val padding = remember {
        markdownPadding(
            block = 0.dp,
            paragraphVerPadding = PaddingValues(vertical = 0.dp),
            codeBlock = PaddingValues(SlockSpacing.Sm)
        )
    }
    CompositionLocalProvider(
        LocalTextProvider provides SlockMarkdownTextProvider,
        LocalUriHandler provides
            object : UriHandler {
                override suspend fun openUri(uri: String) {
                    onOpenUrl(uri)
                }

                override suspend fun openRef(ref: List<String>, x: Float, y: Float) {
                    val reference = ref.joinToString("#")
                    if (reference.isNotBlank()) onOpenUrl(reference)
                }
            }
    ) {
        Markdown(
            content = content,
            colors = colors,
            typography = typography,
            textProvider = SlockMarkdownTextProvider,
            annotator = annotator,
            padding = padding,
            modifier = markdownModifier
        )
    }
}

@Composable
private fun slockMarkdownColors() =
    DefaultMarkdownColors(
        text = SlockColor.TextBlack,
        codeText = SlockColor.SurfaceWhite,
        inlineCodeText = SlockColor.TextBlack,
        linkText = SlockMarkdownLinkBlue,
        codeBackground = SlockMarkdownCodeBlockBackground,
        inlineCodeBackground = SlockColor.BrandYellow.copy(alpha = 0.4f),
        dividerColor = SlockColor.BorderBlack,
        tableText = SlockColor.TextBlack,
        tableBackground = SlockColor.SurfaceWhite,
        tableHeaderBackground = SlockColor.BrandYellow,
        tableStroke = SlockColor.BorderBlack
    )

internal fun slockMarkdownTypography(base: TextStyle): DefaultMarkdownTypography {
    val resolvedBase = base.withResolvedSlockFontFamily()
    val codeBlock =
        SlockType.Caption.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    val inlineCode = slockMarkdownInlineCodeTextStyle(resolvedBase)
    return DefaultMarkdownTypography(
        h1 = SlockType.PageTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
        h2 = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
        h3 = SlockType.Button.withSlockFontWeight(FontWeight.SemiBold).copy(fontSize = 16.sp, lineHeight = 22.sp),
        h4 = SlockType.Button,
        h5 = SlockType.Button,
        h6 = SlockType.Button,
        text = resolvedBase,
        code = codeBlock,
        inlineCode = inlineCode,
        quote = resolvedBase.copy(color = SlockColor.TextMutedGray),
        paragraph = resolvedBase,
        ordered = resolvedBase,
        bullet = resolvedBase,
        list = resolvedBase,
        link = resolvedBase.copy(color = SlockMarkdownLinkBlue),
        textLink = TextLinkStyles(style = resolvedBase.toSpanStyle().copy(color = SlockMarkdownLinkBlue)),
        table = resolvedBase
    )
}

private val SlockMarkdownInlineChipHeight = 21.sp
private val SlockMarkdownLinkBlue = Color(0xFF1D4ED8)
private val SlockMarkdownCodeBlockBackground = Color(0xFF111827)
private val SlockMarkdownReferenceChipBackground = SlockColor.SurfaceCream

internal fun slockMarkdownInlineTagWidthSp(
    label: String,
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false
) =
    if (kind == ThreadMarkdownTagKind.UserMention && !highlighted) {
        slockMarkdownPlainMentionWidthSp(label)
    } else {
        slockMarkdownInlineChipWidthSp(label)
    }

private fun slockMarkdownPlainMentionWidthSp(label: String) =
    (label.slockInlineTextWidthUnits() * 7.8f + 8f).coerceAtLeast(28f).sp

internal fun slockMarkdownInlineChipWidthSp(label: String) =
    (label.slockInlineTextWidthUnits() * 7.6f + 14f).coerceAtLeast(40f).sp

private fun slockMarkdownInlineCodeWidthSp(code: String) =
    (code.slockInlineTextWidthUnits() * 8.2f + 14f).coerceAtLeast(40f).sp

internal fun slockMarkdownInlineCodeTextStyle(base: TextStyle): TextStyle =
    base.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

internal fun String.shouldRenderThreadInlineCodeAsChip(): Boolean =
    slockInlineTextWidthUnits() <= SlockMarkdownInlineCodeChipMaxWidthUnits

private const val SlockMarkdownInlineCodeChipMaxWidthUnits = 28

private fun String.slockInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

internal fun slockMarkdownTagColor(kind: ThreadMarkdownTagKind, highlighted: Boolean = false): Color =
    SlockMarkdownReferenceChipBackground

@Composable
private fun SlockMarkdownInlineChip(label: String, color: Color) {
    Box(
        modifier =
            Modifier
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        KuiklyText(
            label,
            modifier = Modifier.fillMaxWidth(),
            style =
                SlockType.Caption
                    .withSlockFontWeight(FontWeight.SemiBold)
                    .copy(
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    ),
            color = SlockColor.TextBlack,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun SlockMarkdownMentionText(label: String) {
    KuiklyText(
        text = label,
        style =
            SlockType.Body
                .withSlockFontWeight(FontWeight.SemiBold)
                .copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = SlockColor.TextBlack,
                    textDecoration = TextDecoration.Underline
                ),
        color = SlockColor.TextBlack,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}

@Composable
private fun SlockMarkdownInlineCodeChip(code: String) {
    Box(
        modifier =
            Modifier
                .background(SlockColor.BrandYellow.copy(alpha = 0.4f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        KuiklyText(
            code,
            modifier = Modifier.fillMaxWidth(),
            style =
                SlockType.Caption.copy(
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                ),
            color = SlockColor.TextBlack,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}
