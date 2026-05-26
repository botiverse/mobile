package ai.slock.compose.markdown

import ai.slock.compose.SlockColor
import ai.slock.compose.SlockSpacing
import ai.slock.compose.SlockStroke
import ai.slock.compose.SlockType
import ai.slock.compose.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.Placeholder
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuiklybase.markdown.annotator.LocalUriHandler
import com.tencent.kuiklybase.markdown.annotator.UriHandler
import com.tencent.kuiklybase.markdown.compose.Markdown
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownPadding
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

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
            body = "![Slock preview](https://picsum.photos/seed/slock-markdown/480/240)"
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
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null
): MarkdownAnnotator {
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
                        width = slockMarkdownInlineChipWidthSp(label),
                        height = SlockMarkdownInlineChipHeight
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable { onOpenTag(match.tag) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (match.tag.kind == ThreadMarkdownTagKind.UserMention &&
                            !highlightedMentionById.containsKey(match.id)
                        ) {
                            SlockMarkdownMentionText(label)
                        } else {
                            val chipColor = slockMarkdownTagColor(match.tag.kind)
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
        }
    )
}

@Composable
fun SlockMarkdownBody(
    body: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    highlightedMentions: Set<String> = emptySet(),
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null
) {
    val content = body.normalizeThreadMarkdownBody().escapeThreadMarkdownHtmlTagsForThread()
    CompositionLocalProvider(
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
            colors = slockMarkdownColors(),
            typography = slockMarkdownTypography(textStyle),
            annotator = slockMarkdownAnnotator(
                content,
                highlightedMentions = highlightedMentions,
                onOpenTag = onOpenTag,
                resolveTagLabel = resolveTagLabel
            ),
            padding = markdownPadding(
                block = 0.dp,
                paragraphVerPadding = PaddingValues(vertical = 0.dp),
                codeBlock = PaddingValues(SlockSpacing.Sm)
            ),
            modifier = modifier.fillMaxWidth()
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

@Composable
private fun slockMarkdownTypography(base: TextStyle): DefaultMarkdownTypography {
    val code =
        SlockType.Caption.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    return DefaultMarkdownTypography(
        h1 = SlockType.PageTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
        h2 = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
        h3 = SlockType.Button.withSlockFontWeight(FontWeight.SemiBold).copy(fontSize = 16.sp, lineHeight = 22.sp),
        h4 = SlockType.Button,
        h5 = SlockType.Button,
        h6 = SlockType.Button,
        text = base,
        code = code,
        inlineCode = code,
        quote = base.copy(color = SlockColor.TextMutedGray),
        paragraph = base,
        ordered = base,
        bullet = base,
        list = base,
        link = base.copy(color = SlockMarkdownLinkBlue),
        textLink = TextLinkStyles(style = SpanStyle(color = SlockMarkdownLinkBlue)),
        table = base
    )
}

private val SlockMarkdownInlineChipHeight = 21.sp
private val SlockMarkdownLinkBlue = Color(0xFF1D4ED8)
private val SlockMarkdownCodeBlockBackground = Color(0xFF111827)

private val SlockMarkdownMentionSpanStyle =
    SpanStyle(
        color = SlockColor.TextBlack,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline
    )

private fun slockMarkdownInlineChipWidthSp(label: String) =
    (label.slockInlineTextWidthUnits() * 9 + 36).coerceAtLeast(56).sp

private fun slockMarkdownInlineCodeWidthSp(code: String) =
    (code.slockInlineTextWidthUnits() * 9 + 36).coerceAtLeast(56).sp

internal fun String.shouldRenderThreadInlineCodeAsChip(): Boolean =
    slockInlineTextWidthUnits() <= SlockMarkdownInlineCodeChipMaxWidthUnits

private const val SlockMarkdownInlineCodeChipMaxWidthUnits = 28

private fun String.slockInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

private fun slockMarkdownTagColor(kind: ThreadMarkdownTagKind): Color =
    when (kind) {
        ThreadMarkdownTagKind.ChannelThread -> SlockColor.AccentCyan
        ThreadMarkdownTagKind.Channel -> SlockColor.AccentCyan
        ThreadMarkdownTagKind.Task -> SlockColor.AccentOrange
        ThreadMarkdownTagKind.UserMention -> SlockColor.AccentLavender
    }

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
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = SlockType.Caption.copy(
                fontWeight = FontWeight.Bold,
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
    Text(
        text = label,
        style = SlockType.Body.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = SlockColor.TextBlack,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline
        ),
        color = SlockColor.TextBlack,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
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
        Text(
            code,
            modifier = Modifier.fillMaxWidth(),
            style =
                SlockType.Caption.copy(
                    fontFamily = FontFamily.Monospace,
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
