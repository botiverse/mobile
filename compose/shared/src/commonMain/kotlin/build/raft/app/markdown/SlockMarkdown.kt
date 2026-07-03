package build.raft.app.markdown

import build.raft.app.SlockColor
import build.raft.app.SlockSpacing
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.slockFontFamilyForWeight
import build.raft.app.withResolvedSlockFontFamily
import build.raft.app.withSlockFontWeight
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
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.LinkInteractionListener
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
import com.tencent.kuikly.compose.ui.text.withLink
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.isSpecified
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
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
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
    UserMention
}

internal data class MarkdownInlineToken(
    val id: String,
    val kind: MarkdownInlineTokenKind,
    val range: IntRange,
    val label: String,
    val tag: ThreadMarkdownTag? = null,
    val highlighted: Boolean = false
)

internal data class MarkdownInlineTokenPlan(
    val content: String,
    val tokens: List<MarkdownInlineToken>,
    private val inlineCodeMatches: List<ThreadInlineCodeMatch> = emptyList()
) {
    private val tagTokensByStart: List<MarkdownInlineToken> =
        tokens.filter { it.tag != null }.sortedBy { it.range.first }

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

    fun tagTokensStartingInRange(start: Int, endExclusive: Int): List<MarkdownInlineToken> {
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
            val values = result ?: mutableListOf<MarkdownInlineToken>().also { result = it }
            values += token
            index++
        }
        return result ?: emptyList()
    }

    fun inlineCodeMatchCount(): Int =
        inlineCodeMatches.size
}

internal object SlockMarkdownInlineTokenRegistry {
    fun plan(
        content: String,
        highlightedMentions: Set<String> = emptySet(),
        isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
        inlineContentScope: String? = null
    ): MarkdownInlineTokenPlan {
        val highlightedMentionKeys =
            highlightedMentions.mapTo(mutableSetOf()) { threadMarkdownMentionKey(it) }
        val inlineScan = content.threadMarkdownInlineScan(inlineContentScope = inlineContentScope)
        val tagTokens =
            inlineScan.tagMatches
                .filter { match -> isTagValid(match.tag) }
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

fun defaultSlockMarkdownTagValidity(tag: ThreadMarkdownTag): Boolean =
    tag.kind == ThreadMarkdownTagKind.Task

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
    setOf('\\', '\n', '\r', '`', '*', '_', '~', '[', ']', '#', '@', '|')

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
    isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
    inlineContentScope: String? = null,
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    tokenPlan: MarkdownInlineTokenPlan =
        SlockMarkdownInlineTokenRegistry.plan(
            content,
            highlightedMentions,
            isTagValid,
            inlineContentScope = inlineContentScope
        )
): MarkdownAnnotator {
    val diagnosticsStartedAt = onDiagnostics?.let { TimeSource.Monotonic.markNow() }
    val plannedInlineTagTokens = tokenPlan.tagTokens()
    if (diagnosticsStartedAt != null) {
        onDiagnostics.invoke(
            plannedInlineTagTokens.size,
            tokenPlan.inlineCodeMatchCount(),
            diagnosticsStartedAt.elapsedNow().inWholeMilliseconds
        )
    }
    var consumedTagEndExclusive = -1
    return markdownAnnotator(
        annotate = { markdownContent, child, _ ->
            if (child.startOffset < consumedTagEndExclusive) {
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
            val nodeTokens = tokenPlan.tagTokensStartingInRange(nodeStart, nodeEndExclusive)
            if (nodeTokens.isEmpty()) return@markdownAnnotator false

            var cursor = 0
            nodeTokens.forEach { token ->
                val relativeStart = token.range.first - nodeStart
                val relativeEndExclusive = (token.range.last - nodeStart + 1).coerceAtMost(nodeText.length)
                if (relativeStart > cursor) {
                    append(nodeText.substring(cursor, relativeStart))
                }
                appendSlockMarkdownTagToken(
                    token = token,
                    onOpenTag = onOpenTag,
                    resolveTagLabel = resolveTagLabel
                )
                consumedTagEndExclusive = maxOf(consumedTagEndExclusive, token.range.last + 1)
                cursor = relativeEndExclusive
            }
            if (cursor < nodeText.length) append(nodeText.substring(cursor))
            true
        },
        inlineContent = {
            tokenPlan.slockMarkdownInlineContent(
                onOpenTag = onOpenTag,
                resolveTagLabel = resolveTagLabel
            )
        },
        config = markdownAnnotatorConfig(eolAsNewLine = true)
    )
}

private fun MarkdownInlineTokenPlan.slockMarkdownInlineContent(
    onOpenTag: (ThreadMarkdownTag) -> Unit,
    resolveTagLabel: ((ThreadMarkdownTag) -> String)?
): Map<String, InlineTextContent> {
    val inlineTokens = tokens.filter { it.tag != null }
    if (inlineTokens.isEmpty()) return emptyMap()
    return inlineTokens.associateNotNull { token ->
        val tag = token.tag
        if (tag != null) {
            val label =
                resolveTagLabel?.invoke(tag)?.takeIf { it.isNotBlank() }
                    ?: threadMarkdownTagDisplayLabel(tag.label)
            token.id to
                InlineTextContent(
                    Placeholder(
                        width = slockMarkdownInlineTagWidthSp(label, tag.kind, token.highlighted),
                        height = SlockMarkdownInlineChipHeight
                    )
                ) {
                    if (tag.kind == ThreadMarkdownTagKind.UserMention && !token.highlighted) {
                        SlockMarkdownMentionText(label, onClick = { onOpenTag(tag) })
                    } else {
                        SlockMarkdownInlineChip(
                            label = label,
                            color = slockMarkdownTagColor(tag.kind, highlighted = token.highlighted),
                            onClick = { onOpenTag(tag) }
                        )
                    }
                }
        } else {
            null
        }
    }
}

private inline fun <T, R : Any> Iterable<T>.associateNotNull(transform: (T) -> Pair<String, R>?): Map<String, R> {
    val destination = LinkedHashMap<String, R>()
    for (element in this) {
        val pair = transform(element)
        if (pair != null) {
            destination[pair.first] = pair.second
        }
    }
    return destination
}

private fun AnnotatedString.Builder.appendSlockMarkdownTagToken(
    token: MarkdownInlineToken,
    onOpenTag: (ThreadMarkdownTag) -> Unit,
    resolveTagLabel: ((ThreadMarkdownTag) -> String)?
) {
    val tag = token.tag ?: return append(token.label)
    val label =
        resolveTagLabel?.invoke(tag)?.takeIf { it.isNotBlank() }
            ?: threadMarkdownTagDisplayLabel(tag.label)
    pushStringAnnotation(
        tag = SlockMarkdownTagAnnotationTag,
        annotation = token.id
    )
    appendInlineContent(token.id, label)
    pop()
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
internal const val SlockMarkdownTagAnnotationTag = "raft.build.markdown.tag"

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
            builder.addStyle(
                SpanStyle(
                    fontFamily = family,
                    fontWeight = FontWeight.Normal
                ),
                range.start,
                range.end
            )
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

internal fun String.preparedSlockMarkdownFullRendererContentOrNull(): MarkdownParserContent? {
    val (content, _) =
        SlockMarkdownPreparedBodyCache.getOrPut(this) {
            normalizeThreadMarkdownBody()
        }
    if (content.isBlank()) return null
    if (content.shouldUseSlockMarkdownPlainTextFastPath()) return null
    return MarkdownParserContent.from(content)
}

private const val SlockMarkdownDefaultPreparseLimit = 20

internal fun buildSlockMarkdownInlineTextAnnotatedString(
    content: String,
    style: TextStyle,
    annotator: MarkdownAnnotator
): AnnotatedString {
    val parserContent = MarkdownParserContent.from(content).parserContent
    val parsedTree = traceMarkdownSectionNonComposable(SlockMarkdownTraceNames.PARSE) {
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
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
        buildMarkdownAnnotatedString(parserContent, textNode, settings)
        pop()
    }
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
    isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
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
                    body.normalizeThreadMarkdownBody()
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
    val markdownParserContent = remember(content) {
        MarkdownParserContent.from(content)
    }
    val parserContent = markdownParserContent.parserContent
    val tokenPlan = remember(markdownParserContent, highlightedMentions, isTagValid, inlineContentScope) {
        SlockMarkdownInlineTokenRegistry.plan(
            parserContent,
            highlightedMentions,
            isTagValid,
            inlineContentScope = inlineContentScope
        )
    }
    val annotator = remember(parserContent, highlightedMentions, performanceKey, tokenPlan) {
        slockMarkdownAnnotator(
            parserContent,
            highlightedMentions = highlightedMentions,
            isTagValid = isTagValid,
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
            tokenPlan = tokenPlan
        )
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
                Markdown(
                    content = markdownParserContent,
                    colors = colors,
                    typography = typography,
                    textProvider = SlockMarkdownTextProvider,
                    annotator = annotator,
                    padding = padding,
                    modifier = markdownModifier
                )
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
        slockMarkdownInlineChipWidthSp(label, kind)
    }

private fun slockMarkdownPlainMentionWidthSp(label: String) =
    (label.sumOf { it.slockPlainMentionWidthSp().toDouble() }.toFloat() + label.slockPlainMentionWidthSafetySp())
        .coerceAtLeast(28f)
        .sp

internal fun slockMarkdownInlineChipWidthSp(
    label: String,
    kind: ThreadMarkdownTagKind? = null
) =
    (label.sumOf { it.slockInlineChipWidthSp().toDouble() }.toFloat() +
        label.slockInlineChipWidthSafetySp(kind) +
        SlockMarkdownInlineChipHorizontalChromeSp)
        .coerceAtLeast(40f)
        .sp

internal fun slockMarkdownInlineCodeTextStyle(base: TextStyle): TextStyle =
    base.withSlockFontWeight(FontWeight.Normal).withResolvedSlockFontFamily()

private const val SlockMarkdownInlineChipHorizontalChromeSp = 12f
private val SlockMarkdownInlineChipTextHeight = 22.dp
private val SlockMarkdownMentionUnderlineInset = 3.dp

private fun String.slockInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

private fun String.slockInlineChipWidthSafetySp(kind: ThreadMarkdownTagKind?): Float =
    when {
        kind == ThreadMarkdownTagKind.Channel || kind == ThreadMarkdownTagKind.ChannelThread -> 16f
        startsWith("#") && drop(1).any { !it.isDigit() } -> 16f
        else -> 0f
    }

private fun Char.slockInlineChipWidthSp(): Float =
    when {
        code > 0x7F -> 15f
        isDigit() -> 7.2f
        isUpperCase() ->
            when (this) {
                'M', 'W' -> 10.8f
                'I' -> 5.2f
                else -> 8.8f
            }
        isLowerCase() ->
            when (this) {
                'f', 'i', 'j', 'l', 'r', 't' -> 5.2f
                'm', 'w' -> 10.8f
                else -> 8.2f
            }
        this == '@' -> 13.2f
        this == '#' -> 8.2f
        this == ':' -> 4.8f
        this in "_-" -> 6f
        this == '.' -> 4.2f
        else -> 7f
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

internal fun slockMarkdownTagColor(kind: ThreadMarkdownTagKind, highlighted: Boolean = false): Color =
    when (kind) {
        ThreadMarkdownTagKind.ChannelThread -> SlockMarkdownThreadChipBackground
        ThreadMarkdownTagKind.Channel -> SlockMarkdownChannelChipBackground
        ThreadMarkdownTagKind.Task -> SlockMarkdownTaskChipBackground
        ThreadMarkdownTagKind.UserMention -> if (highlighted) SlockColor.BrandYellow else SlockMarkdownTaskChipBackground
    }

@Composable
private fun SlockMarkdownInlineChip(label: String, color: Color, onClick: () -> Unit = {}) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color, RectangleShape)
                .border(SlockStroke.Thin, SlockColor.BorderBlack, RectangleShape)
                .clickable { onClick() }
                .padding(start = 3.dp, end = 3.dp, top = 0.dp, bottom = 0.dp),
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
private fun SlockMarkdownMentionText(label: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.fillMaxSize().clickable { onClick() },
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
