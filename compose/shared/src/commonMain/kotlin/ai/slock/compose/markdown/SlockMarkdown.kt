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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.extension.nativeRef
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.text.InlineTextContent
import com.tencent.kuikly.compose.foundation.text.appendInlineContent
import com.tencent.kuikly.compose.material3.Text as KuiklyText
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.platform.LocalDensity
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
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.isSpecified
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.base.ViewConst
import com.tencent.kuikly.core.views.TextConst
import com.tencent.kuikly.core.views.shadow.TextShadow
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
import com.tencent.kuiklybase.markdown.model.prepareMarkdownContent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.math.abs
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

internal enum class MarkdownInlineTokenKind {
    ChannelThread,
    Channel,
    Task,
    UserMention,
    InlineCode
}

internal data class MarkdownInlineToken(
    val id: String,
    val kind: MarkdownInlineTokenKind,
    val range: IntRange,
    val label: String,
    val tag: ThreadMarkdownTag? = null,
    val code: String? = null,
    val highlighted: Boolean = false
)

internal data class MarkdownInlineTokenPlan(
    val content: String,
    val tokens: List<MarkdownInlineToken>,
    private val inlineCodeMatches: List<ThreadInlineCodeMatch> = emptyList()
) {
    private val tagTokensByStart: List<MarkdownInlineToken> =
        tokens.filter { it.tag != null }.sortedBy { it.range.first }
    private val inlineCodeMatchesByStart: Map<Int, ThreadInlineCodeMatch> =
        inlineCodeMatches.associateBy { it.range.first }

    val signature: String =
        tokens.joinToString(separator = "|") { token ->
            "${token.id}:${token.kind}:${token.range.first}-${token.range.last}:${token.label}:${token.highlighted}"
        }

    fun tagTokens(): List<MarkdownInlineToken> =
        tagTokensByStart

    fun tagTokensInRange(start: Int, endExclusive: Int): List<MarkdownInlineToken> {
        if (tagTokensByStart.isEmpty()) return emptyList()
        var low = 0
        var high = tagTokensByStart.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tagTokensByStart[mid].range.first < start) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        var index = low
        var result: MutableList<MarkdownInlineToken>? = null
        while (index < tagTokensByStart.size) {
            val token = tagTokensByStart[index]
            if (token.range.first >= endExclusive) break
            if (token.range.last < endExclusive) {
                val values = result ?: mutableListOf<MarkdownInlineToken>().also { result = it }
                values += token
            }
            index++
        }
        return result ?: emptyList()
    }

    fun inlineCodeTokens(): List<MarkdownInlineToken> =
        tokens.filter { it.kind == MarkdownInlineTokenKind.InlineCode }

    fun inlineCodeMatchForNode(start: Int): ThreadInlineCodeMatch? =
        inlineCodeMatchesByStart[start]
            ?: inlineCodeMatches.firstOrNull { start in it.range }

    fun inlineCodeMatchCount(): Int =
        inlineCodeMatches.size
}

internal object SlockMarkdownInlineTokenRegistry {
    fun plan(
        content: String,
        highlightedMentions: Set<String> = emptySet(),
        knownChannelNames: Set<String>? = null,
        inlineContentScope: String? = null
    ): MarkdownInlineTokenPlan {
        val highlightedMentionKeys =
            highlightedMentions.mapTo(mutableSetOf()) { threadMarkdownMentionKey(it) }
        val knownChannelKeys = knownChannelNames?.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val inlineScan = content.threadMarkdownInlineScan(inlineContentScope = inlineContentScope)
        val tagTokens =
            inlineScan.tagMatches
                .filter { match -> match.tag.matchesKnownMarkdownChannelNames(knownChannelKeys) }
                .map { match ->
                    MarkdownInlineToken(
                        id = match.id,
                        kind = match.tag.kind.toMarkdownInlineTokenKind(),
                        range = match.range,
                        label = match.tag.label,
                        tag = match.tag,
                        highlighted =
                            match.tag.kind == ThreadMarkdownTagKind.UserMention &&
                                highlightedMentionKeys.containsThreadMention(match.tag.label)
                    )
                }
        return MarkdownInlineTokenPlan(
            content = content,
            tokens = tagTokens.sortedBy { it.range.first },
            inlineCodeMatches = inlineScan.inlineCodeMatches
        )
    }
}

private fun ThreadMarkdownTagKind.toMarkdownInlineTokenKind(): MarkdownInlineTokenKind =
    when (this) {
        ThreadMarkdownTagKind.ChannelThread -> MarkdownInlineTokenKind.ChannelThread
        ThreadMarkdownTagKind.Channel -> MarkdownInlineTokenKind.Channel
        ThreadMarkdownTagKind.Task -> MarkdownInlineTokenKind.Task
        ThreadMarkdownTagKind.UserMention -> MarkdownInlineTokenKind.UserMention
    }

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

private data class ThreadMarkdownInlineScan(
    val inlineCodeMatches: List<ThreadInlineCodeMatch>,
    val tagMatches: List<ThreadInlineTagMatch>
)

private fun String.threadMarkdownInlineScan(
    baseOffset: Int = 0,
    inlineContentScope: String? = null
): ThreadMarkdownInlineScan {
    val inlineCodeMatches = scanThreadInlineCodeMatches(baseOffset)
    val protectedRanges = threadMarkdownCodeProtectionRanges(inlineCodeMatches)
    val inlineContentIdPrefix = inlineContentScope.toSlockMarkdownInlineContentIdPrefix()
    val tagMatches =
        threadInlineTagRanges(protectedRanges).mapNotNull { range ->
            substring(range).toThreadMarkdownTagOrNull()?.let { tag ->
                ThreadInlineTagMatch(
                    id = "$inlineContentIdPrefix:${baseOffset + range.first}",
                    tag = tag,
                    range = range
                )
            }
        }
    return ThreadMarkdownInlineScan(
        inlineCodeMatches = inlineCodeMatches,
        tagMatches = tagMatches
    )
}

private fun String.threadMarkdownCodeProtectionRanges(
    inlineCodeMatches: List<ThreadInlineCodeMatch> = scanThreadInlineCodeMatches()
): List<IntRange> =
    buildList {
        addAll(threadMarkdownFenceRanges())
        addAll(inlineCodeMatches.map { it.range })
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

internal fun String?.toSlockMarkdownInlineContentIdPrefix(): String =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { scope -> "slock-$scope" }
        ?: "slock"

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
    setOf('\\', '\n', '\r', '`', '*', '_', '~', '[', ']', '<', '>', '|')

private fun String.shouldUseSlockMarkdownDirectInlineTagFastPath(): Boolean {
    if (isEmpty() || contains("://")) return false
    return none { it in SlockMarkdownDirectInlineTagFastPathBlockers }
}

private val SlockMarkdownDirectInlineTagFastPathBlockers =
    setOf('\\', '\n', '\r', '`', '*', '_', '~', '[', ']', '<', '>', '|')

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

internal const val DEBUG_SLOCK_MARKDOWN_PERF_LOG = false
private const val SlockMarkdownPerformanceSlowThresholdMs = 8L

internal fun shouldLogSlockMarkdownPerformance(durationMs: Long): Boolean =
    DEBUG_SLOCK_MARKDOWN_PERF_LOG || durationMs >= SlockMarkdownPerformanceSlowThresholdMs

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
        startsWith('#') && drop(1).isBareThreadShortIdLikeReference() -> null
        startsWith('#') -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.Channel, channelName = drop(1))
        else -> null
    }

private fun ThreadMarkdownTag.matchesKnownMarkdownChannelNames(knownChannelKeys: Set<String>?): Boolean {
    if (knownChannelKeys == null ||
        (kind != ThreadMarkdownTagKind.Channel && kind != ThreadMarkdownTagKind.ChannelThread)
    ) {
        return true
    }
    val key =
        (channelName ?: label.removePrefix("#").substringBefore(":"))
            .trim()
            .lowercase()
    return key.isNotBlank() && key in knownChannelKeys
}

private fun String.isThreadChannelReference(): Boolean {
    if (!startsWith('#')) return false
    val colonIndex = indexOf(':')
    if (colonIndex <= 1 || colonIndex == lastIndex) return false
    val shortId = substring(colonIndex + 1)
    return shortId.length in 6..8 && shortId.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

fun String.threadInlineTagMatches(baseOffset: Int = 0): List<ThreadInlineTagMatch> =
    threadMarkdownInlineScan(baseOffset).tagMatches

private fun String.threadInlineTagRanges(protectedRanges: List<IntRange>): List<IntRange> {
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
        if (endExclusive < length && this[endExclusive].isThreadTaskTrailingPart()) return null
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
        return null
    }
    return start until nameEndExclusive
}

private fun Char.isThreadMentionPart(): Boolean =
    isLetterOrDigit() || this == '_' || this == '.' || this == '-'

private fun Char.isThreadChannelPart(): Boolean =
    isLetterOrDigit() || this == '_' || this == '-'

private fun Char.isThreadShortIdPart(): Boolean =
    isDigit() || this in 'a'..'f' || this in 'A'..'F'

private fun Char.isThreadTaskTrailingPart(): Boolean =
    isLetterOrDigit() || this == '_'

private fun String.isBareThreadShortIdLikeReference(): Boolean =
    length in 6..8 &&
        any { it.isDigit() } &&
        any { it in 'a'..'f' || it in 'A'..'F' } &&
        all { it.isThreadShortIdPart() }

private fun String.isThreadInlineTagBoundary(index: Int): Boolean =
    index < 0 || !(this[index].isLetterOrDigit() || this[index] == '_')

fun String.threadInlineCodeMatches(baseOffset: Int = 0): List<ThreadInlineCodeMatch> {
    return scanThreadInlineCodeMatches(baseOffset)
}

private fun String.scanThreadInlineCodeMatches(baseOffset: Int = 0): List<ThreadInlineCodeMatch> {
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

internal fun slockMarkdownAnnotator(
    content: String,
    highlightedMentions: Set<String> = emptySet(),
    knownChannelNames: Set<String>? = null,
    inlineContentScope: String? = null,
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    inlineCodeWidths: Map<String, TextUnit> = emptyMap(),
    inlineCodeTextStyle: TextStyle = slockMarkdownInlineCodeTextStyle(SlockType.Body.withResolvedSlockFontFamily()),
    tokenPlan: MarkdownInlineTokenPlan =
        SlockMarkdownInlineTokenRegistry.plan(
            content,
            highlightedMentions,
            knownChannelNames,
            inlineContentScope = inlineContentScope
        )
): MarkdownAnnotator {
    val diagnosticsStartedAt = onDiagnostics?.let { TimeSource.Monotonic.markNow() }
    val inlineTagTokens = tokenPlan.tagTokens()
    val inlineTagById = inlineTagTokens.associateBy { it.id }
    val highlightedMentionById = inlineTagTokens.filter { it.highlighted }.associateBy { it.id }
    val inlineContentById =
        if (inlineTagById.isEmpty()) {
            null
        } else {
            buildMap<String, InlineTextContent> {
                inlineTagById.forEach { (id, token) ->
                    val tag = token.tag ?: return@forEach
                    val label =
                        resolveTagLabel?.invoke(tag)?.takeIf { it.isNotBlank() }
                            ?: threadMarkdownTagDisplayLabel(tag.label)
                    put(
                        id,
                        InlineTextContent(
                            Placeholder(
                                width = slockMarkdownInlineTagWidthSp(
                                    label,
                                    tag.kind,
                                    highlightedMentionById.containsKey(token.id)
                                ),
                                height = SlockMarkdownInlineChipHeight
                            )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { onOpenTag(tag) },
                                contentAlignment = Alignment.Center
                            ) {
                                val isHighlightedMention = highlightedMentionById.containsKey(token.id)
                                if (tag.kind == ThreadMarkdownTagKind.UserMention &&
                                    !isHighlightedMention
                                ) {
                                    SlockMarkdownMentionText(label)
                                } else {
                                    val chipColor = slockMarkdownTagColor(tag.kind, highlighted = isHighlightedMention)
                                    SlockMarkdownInlineChip(label, chipColor)
                                }
                            }
                        }
                    )
                }
            }
        }
    if (diagnosticsStartedAt != null) {
        onDiagnostics.invoke(
            inlineTagTokens.size,
            tokenPlan.inlineCodeMatchCount(),
            diagnosticsStartedAt.elapsedNow().inWholeMilliseconds
        )
    }
    return markdownAnnotator(
        annotate = { markdownContent, child, _ ->
            if (child.type == MarkdownElementTypes.CODE_SPAN) {
                val codeToken =
                    tokenPlan.inlineCodeMatchForNode(child.startOffset)
                val code =
                    codeToken?.code
                        ?: child.getTextInNode(markdownContent).toString().markdownInlineCodeText()
                pushStyle(inlineCodeTextStyle.toSpanStyle())
                pushStringAnnotation(
                    tag = SlockMarkdownInlineCodeAnnotationTag,
                    annotation = SlockMarkdownInlineCodeAnnotationValue
                )
                append(code)
                pop()
                pop()
                return@markdownAnnotator true
            }
            if (child.type != MarkdownTokenTypes.TEXT ||
                child.parent?.type == MarkdownElementTypes.CODE_SPAN
            ) {
                return@markdownAnnotator false
            }

            val nodeText = child.getTextInNode(markdownContent).toString()
            val nodeStart = child.startOffset
            val nodeEndExclusive = nodeStart + nodeText.length
            val nodeTokens = tokenPlan.tagTokensInRange(nodeStart, nodeEndExclusive)
            if (nodeTokens.isEmpty()) return@markdownAnnotator false

            var cursor = 0
            nodeTokens.forEach { token ->
                val relativeStart = token.range.first - nodeStart
                val relativeEndExclusive = token.range.last - nodeStart + 1
                if (relativeStart > cursor) {
                    append(nodeText.substring(cursor, relativeStart))
                }
                appendInlineContent(token.id, token.label)
                cursor = relativeEndExclusive
            }
            if (cursor < nodeText.length) append(nodeText.substring(cursor))
            true
        },
        inlineContent = inlineContentById?.let { contentMap -> { contentMap } },
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
        val markdownOverflow = slockMarkdownContentOverflow(overflow)
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
            overflow = markdownOverflow,
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
        val markdownOverflow = slockMarkdownContentOverflow(overflow)
        val resolvedText = remember(text, resolvedStyle) {
            text.withSlockMarkdownSpanFonts(resolvedStyle)
        }
        val resolvedInlineContent = resolvedText.filteredSlockMarkdownInlineContent(inlineContent)
        var lastLayoutSignature by remember { mutableStateOf<SlockMarkdownTextLayoutSignature?>(null) }
        val resolvedOnTextLayout: (TextLayoutResult) -> Unit =
            if (resolvedInlineContent.isEmpty()) {
                onTextLayout
            } else {
                { result ->
                    val signature =
                        SlockMarkdownTextLayoutSignature(
                            size = result.size,
                            placeholderRects = result.placeholderRects.toList()
                        )
                    if (signature != lastLayoutSignature) {
                        lastLayoutSignature = signature
                        onTextLayout(result)
                    }
                }
            }
        KuiklyText(
            text = resolvedText,
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
            overflow = markdownOverflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            inlineContent = resolvedInlineContent,
            onTextLayout = resolvedOnTextLayout,
            style = resolvedStyle
        )
    }
}

internal fun slockMarkdownContentOverflow(overflow: TextOverflow): TextOverflow =
    if (overflow == TextOverflow.Ellipsis) TextOverflow.Clip else overflow

private data class SlockMarkdownTextLayoutSignature(
    val size: IntSize,
    val placeholderRects: List<Rect?>
)

private fun AnnotatedString.hasSlockMarkdownInlineContentPlaceholders(): Boolean =
    hasStringAnnotations(SlockMarkdownInlineContentTag, 0, text.length)

internal fun AnnotatedString.slockMarkdownInlineContentIds(): Set<String> =
    getStringAnnotations(SlockMarkdownInlineContentTag, 0, text.length)
        .mapTo(mutableSetOf()) { it.item }

internal fun AnnotatedString.filteredSlockMarkdownInlineContent(
    inlineContent: Map<String, InlineTextContent>
): Map<String, InlineTextContent> {
    if (inlineContent.isEmpty() || !hasSlockMarkdownInlineContentPlaceholders()) {
        return emptyMap()
    }
    val ids = slockMarkdownInlineContentIds()
    if (ids.isEmpty()) return emptyMap()
    return if (ids.size == inlineContent.size && inlineContent.keys.containsAll(ids)) {
        inlineContent
    } else {
        inlineContent.filterKeys { it in ids }
    }
}

private const val SlockMarkdownInlineContentTag = "androidx.compose.foundation.text.inlineContent"
internal const val SlockMarkdownInlineCodeAnnotationTag = "ai.slock.markdown.inlineCode"
private const val SlockMarkdownInlineCodeAnnotationValue = "true"

internal fun AnnotatedString.withSlockMarkdownSpanFonts(baseStyle: TextStyle): AnnotatedString {
    if (spanStyles.isEmpty()) return this

    val resolvedBase = baseStyle.withResolvedSlockFontFamily()
    val builder = AnnotatedString.Builder(length)
    builder.append(this)
    spanStyles.forEach { range ->
        val explicitWeight = range.item.fontWeight
        val shouldNormalizeFamily =
            range.item.fontFamily == null ||
                range.item.fontFamily == FontFamily.Monospace
        val family =
            if (shouldNormalizeFamily) {
                explicitWeight?.let(::slockFontFamilyForWeight) ?: resolvedBase.fontFamily
            } else {
                range.item.fontFamily
            }
        if (shouldNormalizeFamily &&
            (range.item.fontFamily == FontFamily.Monospace || family != resolvedBase.fontFamily)
        ) {
            builder.addStyle(SpanStyle(fontFamily = family, fontWeight = FontWeight.Normal), range.start, range.end)
        }
    }
    return builder.toAnnotatedString()
}

private object SlockMarkdownPreparedBodyCache {
    private const val MaxEntries = 256
    private val entries = LinkedHashMap<String, String>()

    fun getOrPut(
        body: String,
        value: () -> String
    ): Pair<String, Boolean> {
        entries[body]?.let { cached ->
            return cached to true
        }
        val prepared = value()
        entries[body] = prepared
        if (entries.size > MaxEntries) {
            val firstKey = entries.keys.firstOrNull()
            if (firstKey != null) {
                entries.remove(firstKey)
            }
        }
        return prepared to false
    }
}

internal data class SlockMarkdownInlineTextCacheKey(
    val content: String,
    val textStyle: TextStyle,
    val tokenPlanSignature: String
)

internal data class SlockMarkdownInlineTextRender(
    val fontResolvedText: AnnotatedString
)

internal object SlockMarkdownInlineTextRenderCache {
    private const val MaxEntries = 256
    private val entries = LinkedHashMap<SlockMarkdownInlineTextCacheKey, SlockMarkdownInlineTextRender>()

    fun getOrPut(
        key: SlockMarkdownInlineTextCacheKey,
        value: () -> AnnotatedString
    ): Pair<SlockMarkdownInlineTextRender, Boolean> {
        entries[key]?.let { cached ->
            return cached to true
        }
        val annotated = value()
        val rendered =
            SlockMarkdownInlineTextRender(
                fontResolvedText = annotated.withSlockMarkdownSpanFonts(key.textStyle)
            )
        entries[key] = rendered
        if (entries.size > MaxEntries) {
            val firstKey = entries.keys.firstOrNull()
            if (firstKey != null) {
                entries.remove(firstKey)
            }
        }
        return rendered to false
    }

    fun clearForTest() {
        entries.clear()
    }
}

internal data class SlockMarkdownPreparseResult(
    val requestedCount: Int,
    val parsedCount: Int,
    val cacheHitCount: Int
)

internal suspend fun prepareSlockMarkdownFullRendererBodies(
    bodies: List<String>,
    maxBodies: Int = SlockMarkdownDefaultPreparseLimit
): SlockMarkdownPreparseResult {
    if (bodies.isEmpty() || maxBodies <= 0) {
        return SlockMarkdownPreparseResult(requestedCount = 0, parsedCount = 0, cacheHitCount = 0)
    }
    val contents =
        bodies
            .asSequence()
            .mapNotNull { body -> body.preparedSlockMarkdownFullRendererContentOrNull() }
            .distinct()
            .take(maxBodies)
            .toList()
    var cacheHitCount = 0
    contents.forEach { content ->
        if (prepareMarkdownContent(content)) {
            cacheHitCount += 1
        }
    }
    return SlockMarkdownPreparseResult(
        requestedCount = contents.size,
        parsedCount = contents.size - cacheHitCount,
        cacheHitCount = cacheHitCount
    )
}

internal fun String.preparedSlockMarkdownFullRendererContentOrNull(): String? {
    val (content, _) =
        SlockMarkdownPreparedBodyCache.getOrPut(this) {
            normalizeThreadMarkdownBody().escapeThreadMarkdownHtmlTagsForThread()
        }
    if (content.isBlank()) return null
    if (content.shouldUseSlockMarkdownPlainTextFastPath()) return null
    if (content.shouldUseSlockMarkdownInlineTextFastPath()) return null
    return content
}

private const val SlockMarkdownDefaultPreparseLimit = 20

internal fun buildSlockMarkdownInlineTextAnnotatedString(
    content: String,
    style: TextStyle,
    annotator: MarkdownAnnotator
): AnnotatedString {
    val parsedTree = traceMarkdownSectionNonComposable(SlockMarkdownTraceNames.PARSE) {
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    }
    val textNode =
        parsedTree.children.firstOrNull { node ->
            node.type == MarkdownTokenTypes.TEXT || node.type == MarkdownElementTypes.PARAGRAPH
        } ?: return AnnotatedString(content)
    val settings =
        DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = slockMarkdownLinkSpanStyle(style)),
            codeSpanStyle = slockMarkdownInlineCodeTextStyle(style).toSpanStyle(),
            annotator = annotator
        )
    return buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, textNode, settings)
        pop()
    }
}

internal fun buildSlockMarkdownDirectInlineTagAnnotatedString(
    content: String,
    style: TextStyle,
    tokenPlan: MarkdownInlineTokenPlan
): AnnotatedString {
    val tagTokens = tokenPlan.tagTokens()
    if (tagTokens.isEmpty()) return AnnotatedString(content)
    return buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        var cursor = 0
        tagTokens.forEach { token ->
            val start = token.range.first.coerceIn(0, content.length)
            val endExclusive = (token.range.last + 1).coerceIn(start, content.length)
            if (start < cursor) return@forEach
            if (start > cursor) append(content.substring(cursor, start))
            appendInlineContent(token.id, token.label)
            cursor = endExclusive
        }
        if (cursor < content.length) append(content.substring(cursor))
        pop()
    }
}

@Composable
private fun SlockMarkdownMeasuredInlineCodeBox(
    modifier: Modifier,
    tokenPlan: MarkdownInlineTokenPlan,
    inlineCodeTextStyle: TextStyle,
    measuredInlineCodeWidths: MutableMap<String, TextUnit>,
    content: @Composable (Modifier) -> Unit
) {
    val inlineCodeTokens = tokenPlan.inlineCodeTokens()
    if (inlineCodeTokens.isEmpty()) {
        content(modifier)
        return
    }

    val density = LocalDensity.current
    val activeIds = remember(tokenPlan.signature) {
        inlineCodeTokens.mapTo(mutableSetOf()) { it.id }
    }
    Box(
        modifier =
            modifier.nativeRef { viewRef ->
                val fontScale = density.density / getPager().pagerDensity()
                val measuredByCode = mutableMapOf<String, TextUnit>()
                inlineCodeTokens.forEach { token ->
                    val code = token.code.orEmpty()
                    val measuredWidth =
                        measuredByCode.getOrPut(code) {
                            measureSlockMarkdownInlineCodeWidthSp(
                                pagerId = viewRef.pagerId,
                                nativeRef = viewRef.nativeRef,
                                code = code,
                                style = inlineCodeTextStyle,
                                fontScale = fontScale
                            )
                        }
                    val previousWidth = measuredInlineCodeWidths[token.id]
                    if (previousWidth == null ||
                        abs(previousWidth.value - measuredWidth.value) > SlockMarkdownMeasuredWidthEpsilonSp
                    ) {
                        measuredInlineCodeWidths[token.id] = measuredWidth
                    }
                }
                measuredInlineCodeWidths.keys
                    .filterNot { it in activeIds }
                    .forEach { measuredInlineCodeWidths.remove(it) }
            }
    ) {
        content(Modifier.fillMaxWidth())
    }
}

private fun measureSlockMarkdownInlineCodeWidthSp(
    pagerId: String,
    nativeRef: Int,
    code: String,
    style: TextStyle,
    fontScale: Float
): TextUnit {
    val fallbackWidth = slockMarkdownInlineCodeWidthSp(code)
    val shadow = TextShadow(pagerId, nativeRef, ViewConst.TYPE_RICH_TEXT)
    return try {
        shadow.setProp(TextConst.VALUE, code)
        if (style.fontSize.isSpecified) {
            shadow.setProp(TextConst.FONT_SIZE, style.fontSize.value * fontScale)
        }
        if (style.lineHeight.isSpecified) {
            shadow.setProp(TextConst.LINE_HEIGHT, style.lineHeight.value * fontScale)
        }
        shadow.setProp(TextConst.FONT_WEIGHT, style.fontWeight.slockMarkdownTextConstWeight())
        shadow.setProp(TextConst.TEXT_USE_DP_FONT_SIZE_DIM, 1)
        val measuredWidth = shadow.calculateRenderViewSize(100000f, 100000f).width
        if (measuredWidth.isFinite() && measuredWidth > 0f) {
            (measuredWidth + SlockMarkdownInlineCodeHorizontalChromeSp + SlockMarkdownMeasuredWidthSafetySp)
                .coerceAtLeast(fallbackWidth.value)
                .coerceAtLeast(SlockMarkdownInlineCodeMinWidthSp)
                .sp
        } else {
            fallbackWidth
        }
    } catch (_: Throwable) {
        fallbackWidth
    } finally {
        shadow.removeFromParentComponent()
    }
}

private fun FontWeight?.slockMarkdownTextConstWeight(): String =
    when (this) {
        FontWeight.W100 -> "300"
        FontWeight.W200 -> "200"
        FontWeight.W300 -> "300"
        FontWeight.W400, null -> "400"
        FontWeight.W500 -> "500"
        FontWeight.W600 -> "600"
        FontWeight.W700, FontWeight.W800, FontWeight.W900 -> "700"
        else -> "400"
    }

private val SlockMarkdownBodyFontSize = 14.sp
private val SlockMarkdownBodyLineHeight = 22.sp

@Composable
fun SlockMarkdownBody(
    body: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SlockType.Body.copy(
        fontSize = SlockMarkdownBodyFontSize,
        lineHeight = SlockMarkdownBodyLineHeight
    ),
    highlightedMentions: Set<String> = emptySet(),
    knownChannelNames: Set<String>? = null,
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    inlineContentScope: String? = null,
    performanceKey: String? = null,
    performanceLogger: (String) -> Unit = {}
) {
    val content = remember(body, performanceKey) {
        val startedAt = performanceKey?.let { TimeSource.Monotonic.markNow() }
        val (prepared, cacheHit) =
            traceMarkdownSectionNonComposable(SlockMarkdownTraceNames.PREPARE) {
                SlockMarkdownPreparedBodyCache.getOrPut(body) {
                    body.normalizeThreadMarkdownBody().escapeThreadMarkdownHtmlTagsForThread()
                }
            }
        if (performanceKey != null) {
            val durationMs = startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
            if (shouldLogSlockMarkdownPerformance(durationMs)) {
                performanceLogger(
                    "thread_markdown_prepare stage=normalize key=$performanceKey " +
                        "bodyLength=${body.length} contentLength=${prepared.length} " +
                        "cacheHit=$cacheHit durationMs=$durationMs"
                )
            }
        }
        prepared
    }
    val markdownModifier =
        if (DEBUG_SLOCK_MARKDOWN_PERF_LOG && performanceKey != null) {
            val key = performanceKey
            var layoutLogged by remember(content, performanceKey) { mutableStateOf(false) }
            val layoutStartedAt = remember(content, performanceKey) { TimeSource.Monotonic.markNow() }
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
        } else {
            modifier.fillMaxWidth()
        }
    val markdownTextStyle = remember(textStyle) {
        slockMarkdownTextStyleWithInlineLineHeight(textStyle)
    }
    if (content.shouldUseSlockMarkdownPlainTextFastPath()) {
        val resolvedStyle = markdownTextStyle
        traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT, body.length) {
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
        }
        return
    }
    val currentOnOpenTag by rememberUpdatedState(onOpenTag)
    val currentOnOpenUrl = rememberUpdatedState(onOpenUrl)
    val currentResolveTagLabel by rememberUpdatedState(resolveTagLabel)
    val colors = SlockMarkdownColorsDefault
    val typography = remember(markdownTextStyle) { slockMarkdownTypography(markdownTextStyle) }
    val inlineCodeTextStyle =
        remember(markdownTextStyle) {
            slockMarkdownInlineCodeTextStyle(markdownTextStyle)
        }
    val tokenPlan = remember(content, highlightedMentions, knownChannelNames, inlineContentScope) {
        SlockMarkdownInlineTokenRegistry.plan(
            content,
            highlightedMentions,
            knownChannelNames,
            inlineContentScope = inlineContentScope
        )
    }
    val measuredInlineCodeWidths = remember(content, tokenPlan.signature) {
        mutableStateMapOf<String, TextUnit>()
    }
    val inlineCodeWidthOverrides = measuredInlineCodeWidths.toMap()
    val annotator = remember(content, highlightedMentions, performanceKey, tokenPlan, inlineCodeWidthOverrides, inlineCodeTextStyle) {
        slockMarkdownAnnotator(
            content,
            highlightedMentions = highlightedMentions,
            knownChannelNames = knownChannelNames,
            inlineContentScope = inlineContentScope,
            onOpenTag = { tag -> currentOnOpenTag(tag) },
            resolveTagLabel = { tag -> currentResolveTagLabel?.invoke(tag) ?: threadMarkdownTagDisplayLabel(tag.label) },
            onDiagnostics =
                performanceKey?.let { key ->
                    { inlineTagCount, inlineCodeCount, durationMs ->
                        if (shouldLogSlockMarkdownPerformance(durationMs)) {
                            performanceLogger(
                                "thread_markdown_prepare stage=annotator key=$key " +
                                    "inlineTags=$inlineTagCount inlineCode=$inlineCodeCount " +
                                    "contentLength=${content.length} durationMs=$durationMs"
                            )
                        }
                    }
                },
            inlineCodeWidths = inlineCodeWidthOverrides,
            inlineCodeTextStyle = inlineCodeTextStyle,
            tokenPlan = tokenPlan
        )
    }
    if (content.shouldUseSlockMarkdownInlineTextFastPath()) {
        val resolvedStyle = markdownTextStyle
        val renderStartedAt = TimeSource.Monotonic.markNow()
        val cacheKey = SlockMarkdownInlineTextCacheKey(content, resolvedStyle, tokenPlan.signature)
        val (renderedText, cacheHit) =
            traceMarkdownSection(SlockMarkdownTraceNames.ANNOTATE) {
                SlockMarkdownInlineTextRenderCache.getOrPut(cacheKey) {
                    if (tokenPlan.tagTokens().isNotEmpty() && content.shouldUseSlockMarkdownDirectInlineTagFastPath()) {
                        buildSlockMarkdownDirectInlineTagAnnotatedString(content, resolvedStyle, tokenPlan)
                    } else {
                        buildSlockMarkdownInlineTextAnnotatedString(content, resolvedStyle, annotator)
                    }
                }
            }
        val inlineContent = remember(annotator) {
            annotator.inlineContent?.invoke().orEmpty()
        }
        if (performanceKey != null && tokenPlan.tagTokens().isNotEmpty()) {
            val placeholderIds = renderedText.fontResolvedText.slockMarkdownInlineContentIds()
            val inlineContentIds = inlineContent.keys
            val missingInlineContentIds = placeholderIds - inlineContentIds
            val missingPlaceholderIds = inlineContentIds - placeholderIds
            if (placeholderIds.isEmpty() || missingInlineContentIds.isNotEmpty() || missingPlaceholderIds.isNotEmpty()) {
                performanceLogger(
                    "thread_markdown_inline_content_mismatch key=$performanceKey " +
                        "contentLength=${content.length} tagTokens=${tokenPlan.tagTokens().size} " +
                        "placeholderIds=${placeholderIds.sorted()} inlineContentIds=${inlineContentIds.sorted()} " +
                        "missingInlineContentIds=${missingInlineContentIds.sorted()} " +
                        "missingPlaceholderIds=${missingPlaceholderIds.sorted()}"
                )
            }
        }
        val renderDurationMs = renderStartedAt.elapsedNow().inWholeMilliseconds
        if (performanceKey != null && shouldLogSlockMarkdownPerformance(renderDurationMs)) {
            performanceLogger(
                "thread_markdown_render stage=inline_text key=$performanceKey contentLength=${content.length} " +
                    "cacheHit=$cacheHit durationMs=$renderDurationMs"
            )
        }
        traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT, body.length) {
            SlockMarkdownMeasuredInlineCodeBox(
                modifier = markdownModifier,
                tokenPlan = tokenPlan,
                inlineCodeTextStyle = inlineCodeTextStyle,
                measuredInlineCodeWidths = measuredInlineCodeWidths
            ) { contentModifier ->
                KuiklyText(
                    text = renderedText.fontResolvedText,
                    modifier = contentModifier,
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
                    inlineContent = inlineContent,
                    onTextLayout = {},
                    style = resolvedStyle
                )
            }
        }
        return
    }
    if (DEBUG_SLOCK_MARKDOWN_PERF_LOG && performanceKey != null) {
        performanceLogger(
            "thread_markdown_render stage=markdown_renderer key=$performanceKey contentLength=${content.length}"
        )
    }
    val padding = remember {
        markdownPadding(
            block = 0.dp,
            paragraphVerPadding = PaddingValues(vertical = 0.dp),
            codeBlock = PaddingValues(SlockSpacing.Sm)
        )
    }
    val uriHandler =
        remember {
            object : UriHandler {
                override suspend fun openUri(uri: String) {
                    currentOnOpenUrl.value(uri)
                }

                override suspend fun openRef(ref: List<String>, x: Float, y: Float) {
                    val reference = ref.joinToString("#")
                    if (reference.isNotBlank()) currentOnOpenUrl.value(reference)
                }
            }
        }
    CompositionLocalProvider(
        LocalTextProvider provides SlockMarkdownTextProvider,
        LocalUriHandler provides uriHandler
    ) {
        traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT, body.length) {
            // #192 long-body sub-trace: split layout phase into pre-block setup
            // vs the third-party `Markdown()` call so 5-10ms spikes can be
            // localized. Fires only when the wrapped block exceeds the
            // MarkdownSpikeThresholdUs (200us default), keeping logcat clean for
            // short bodies. Wrap in `traceMarkdownSection` (not non-composable)
            // because the call is inside an existing @Composable scope.
            traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT_INNER, body.length) {
                // Local-reference assignments: cheap but measuring them is useful
                // to confirm KuiklyText-measure is the dominant cost. The
                // assignment is a single val read; the sub-trace will be near
                // zero unless something heavier is being done implicitly.
                val refContent = content
                val refTypography = typography
                val refAnnotator = annotator
                val refPadding = padding
                val refModifier = markdownModifier
                val refColors = colors
                val refTextProvider = SlockMarkdownTextProvider
                // Pass-through, no-op; just to keep the locals from being flagged unused.
                @Suppress("UNUSED_EXPRESSION") refContent; refTypography; refAnnotator; refPadding; refModifier; refColors; refTextProvider
            }
            traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT_BLOCK, body.length) {
                SlockMarkdownMeasuredInlineCodeBox(
                    modifier = markdownModifier,
                    tokenPlan = tokenPlan,
                    inlineCodeTextStyle = inlineCodeTextStyle,
                    measuredInlineCodeWidths = measuredInlineCodeWidths
                ) { contentModifier ->
                    Markdown(
                        content = content,
                        colors = colors,
                        typography = typography,
                        textProvider = SlockMarkdownTextProvider,
                        annotator = annotator,
                        padding = padding,
                        modifier = contentModifier
                    )
                }
            }
        }
    }
}

// #192 layout hot path: hoist `slockMarkdownColors()` to a file-level `val` so the
// same `DefaultMarkdownColors` instance is reused across every recomposition. The
// original `@Composable fun slockMarkdownColors()` allocated a fresh instance every
// recomposition, which caused the downstream `Markdown(content, ...)` composable
// to see fresh `colors` references each time and run full recomposition + initial
// Text measure even when nothing actually changed.
private val SlockMarkdownLinkBlue = Color(0xFF1D4ED8)
private val SlockMarkdownCodeBlockBackground = Color(0xFF111827)
private val SlockMarkdownMinimumInlineLineHeight = 22.sp

internal fun slockMarkdownLinkSpanStyle(base: TextStyle): SpanStyle =
    base.toSpanStyle().copy(
        color = SlockMarkdownLinkBlue,
        textDecoration = TextDecoration.Underline
    )

private val SlockMarkdownColorsDefault: DefaultMarkdownColors =
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

internal fun slockMarkdownTextStyleWithInlineLineHeight(base: TextStyle): TextStyle {
    val resolvedBase = base.withResolvedSlockFontFamily()
    val minLineHeight =
        maxOf(
            SlockMarkdownMinimumInlineLineHeight.value,
            resolvedBase.fontSize.takeIf { it.isSpecified }?.value ?: 0f
        )
    val lineHeight = resolvedBase.lineHeight.takeIf { it.isSpecified }?.value
    return if (lineHeight != null && lineHeight >= minLineHeight) {
        resolvedBase
    } else {
        resolvedBase.copy(lineHeight = minLineHeight.sp)
    }
}

internal fun slockMarkdownTypography(base: TextStyle): DefaultMarkdownTypography {
    val resolvedBase = slockMarkdownTextStyleWithInlineLineHeight(base)
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
        link = resolvedBase.copy(
            color = SlockMarkdownLinkBlue,
            textDecoration = TextDecoration.Underline
        ),
        textLink = TextLinkStyles(style = slockMarkdownLinkSpanStyle(resolvedBase)),
        table = resolvedBase
    )
}

private val SlockMarkdownInlineChipHeight = 24.sp
private val SlockMarkdownChannelChipBackground = SlockColor.AccentPink.copy(alpha = 0.3f)
private val SlockMarkdownThreadChipBackground = SlockColor.AccentCyan.copy(alpha = 0.3f)
private val SlockMarkdownTaskChipBackground = SlockColor.BrandYellow.copy(alpha = 0.4f)

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
    (label.sumOf { it.slockPlainMentionWidthSp().toDouble() }.toFloat() + label.slockPlainMentionWidthSafetySp())
        .coerceAtLeast(28f)
        .sp

internal fun slockMarkdownInlineChipWidthSp(label: String) =
    (label.slockInlineTextWidthUnits() * 7.6f + SlockMarkdownInlineChipHorizontalChromeSp)
        .coerceAtLeast(40f)
        .sp

internal fun slockMarkdownInlineCodeWidthSp(code: String) =
    (code.sumOf { it.slockInlineCodeWidthSp().toDouble() }.toFloat() + SlockMarkdownInlineCodeHorizontalChromeSp)
        .coerceAtLeast(SlockMarkdownInlineCodeMinWidthSp)
        .sp

internal fun slockMarkdownInlineCodePlaceholderWidthSp(code: String, measuredWidth: TextUnit? = null): TextUnit {
    val fallbackWidth = slockMarkdownInlineCodeWidthSp(code)
    return measuredWidth?.value
        ?.coerceAtLeast(fallbackWidth.value)
        ?.sp
        ?: fallbackWidth
}

internal fun slockMarkdownInlineCodeChipHeightSp(textStyle: TextStyle): TextUnit {
    val lineHeight = textStyle.lineHeight.takeIf { it.isSpecified }?.value
    val fontSize = textStyle.fontSize.takeIf { it.isSpecified }?.value
    return ((lineHeight ?: fontSize ?: 20f) + SlockMarkdownInlineCodeVerticalChromeSp)
        .coerceAtLeast(SlockMarkdownInlineCodeMinHeightSp)
        .sp
}

internal fun slockMarkdownInlineCodeTextStyle(base: TextStyle): TextStyle =
    base.withSlockFontWeight(FontWeight.SemiBold).withResolvedSlockFontFamily()

internal fun String.shouldRenderThreadInlineCodeAsChip(): Boolean =
    slockInlineTextWidthUnits() <= SlockMarkdownInlineCodeChipMaxWidthUnits

private fun String.markdownInlineCodeText(): String =
    threadInlineCodeMatches().firstOrNull()?.code
        ?: removeSurroundingMarkdownBackticks().normalizeMarkdownInlineCodeText()

private fun String.removeSurroundingMarkdownBackticks(): String {
    if (isEmpty() || first() != '`' || last() != '`') return this
    val openingTickCount = countBackticks(0)
    if (openingTickCount >= length) return ""
    val closingStart = length - openingTickCount
    if (closingStart <= openingTickCount) return ""
    if (substring(closingStart) != "`".repeat(openingTickCount)) return this
    return substring(openingTickCount, closingStart)
}

private const val SlockMarkdownInlineCodeChipMaxWidthUnits = 28
private const val SlockMarkdownInlineChipHorizontalChromeSp = 18f
private const val SlockMarkdownInlineCodeHorizontalChromeSp = 14f
private const val SlockMarkdownInlineCodeMinWidthSp = 24f
private const val SlockMarkdownInlineCodeMinHeightSp = 24f
private const val SlockMarkdownInlineCodeVerticalChromeSp = 4f
private const val SlockMarkdownMeasuredWidthEpsilonSp = 0.25f
private const val SlockMarkdownMeasuredWidthSafetySp = 6f
private val SlockMarkdownInlineChipTextHeight = 22.dp
private val SlockMarkdownMentionUnderlineInset = 3.dp

private fun String.slockInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

private fun String.slockPlainMentionWidthSafetySp(): Float =
    when {
        slockInlineTextWidthUnits() <= 10 -> 12f
        length <= 14 -> 18f
        else -> 24f
    }

private fun Char.slockPlainMentionWidthSp(): Float =
    when {
        code > 0x7F -> 14f
        isDigit() -> 7.2f
        isUpperCase() ->
            when (this) {
                'M', 'W' -> 10.4f
                'I' -> 4.8f
                else -> 8.6f
            }
        isLowerCase() ->
            when (this) {
                'f', 'i', 'j', 'l', 'r', 't' -> 4.8f
                'm', 'w' -> 10.4f
                else -> 7.4f
            }
        this == '@' -> 13.2f
        this in "_-" -> 5.8f
        this == '.' -> 3.8f
        else -> 6.6f
    }

private fun Char.slockInlineCodeWidthSp(): Float =
    when {
        code > 0x7F -> 14f
        isDigit() -> 7.2f
        isUpperCase() -> 8f
        isLowerCase() ->
            when (this) {
                'f', 'i', 'j', 'l', 'r', 't' -> 4.4f
                'm', 'w' -> 10.2f
                else -> 7.1f
            }
        this == '.' -> 3.6f
        this == ':' -> 3.8f
        this == ' ' -> 4f
        this in "_-/" -> 5.6f
        this in "()[]{}" -> 5f
        else -> 6.4f
    }

internal fun slockMarkdownTagColor(kind: ThreadMarkdownTagKind, highlighted: Boolean = false): Color =
    when (kind) {
        ThreadMarkdownTagKind.ChannelThread -> SlockMarkdownThreadChipBackground
        ThreadMarkdownTagKind.Channel -> SlockMarkdownChannelChipBackground
        ThreadMarkdownTagKind.Task -> SlockMarkdownTaskChipBackground
        ThreadMarkdownTagKind.UserMention -> if (highlighted) SlockColor.BrandYellow else SlockMarkdownTaskChipBackground
    }

@Composable
private fun SlockMarkdownInlineChip(label: String, color: Color) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(start = 5.dp, end = 5.dp, top = 0.dp, bottom = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        KuiklyText(
            label,
            modifier = Modifier.fillMaxWidth().height(SlockMarkdownInlineChipTextHeight),
            style =
                SlockType.Caption
                    .withSlockFontWeight(FontWeight.SemiBold)
                    .copy(
                        fontSize = 14.sp,
                        lineHeight = 22.sp
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SlockMarkdownMentionUnderlineInset)
                        .height(2.dp)
                        .background(SlockColor.EmptyStateGray)
                        .align(Alignment.BottomCenter)
            )
            KuiklyText(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                style =
                    SlockType.Body
                        .withSlockFontWeight(FontWeight.SemiBold)
                        .copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = SlockColor.TextBlack
                        ),
                color = SlockColor.TextBlack,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible
            )
        }
    }
}

@Composable
private fun SlockMarkdownInlineCodeChip(code: String, textStyle: TextStyle) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.BrandYellow.copy(alpha = 0.4f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 4.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        KuiklyText(
            text = code,
            modifier = Modifier.fillMaxWidth(),
            style = textStyle.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}
