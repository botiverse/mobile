package build.raft.app.markdown

import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockMessagePermalinkTarget
import build.raft.app.SlockSpacing
import build.raft.app.SlockType
import build.raft.app.toSlockMessagePermalinkTarget
import build.raft.app.slockFontFamilyForWeight
import build.raft.app.slockMonoFamilyForWeight
import build.raft.app.withResolvedSlockFontFamily
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import build.raft.app.base.Utils
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.text.InlineTextContent
import com.tencent.kuikly.compose.foundation.text.appendInlineContent
import com.tencent.kuikly.compose.material3.Text as KuiklyText
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.InlineBoxSpanStyle
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
import com.tencent.kuikly.compose.ui.text.withStyle
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.isSpecified
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuiklybase.markdown.annotator.LocalUriHandler
import com.tencent.kuiklybase.markdown.annotator.UriHandler
import com.tencent.kuiklybase.markdown.compose.Markdown
import com.tencent.kuiklybase.markdown.compose.LocalTextProvider
import com.tencent.kuiklybase.markdown.compose.components.markdownComponents
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.TextProvider
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownAnnotatorConfig
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownCode
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownCodeBlock
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownCodeFence
import com.tencent.kuiklybase.markdown.model.MarkdownDimens
import com.tencent.kuiklybase.markdown.model.markdownPadding
import com.tencent.kuiklybase.markdown.model.prepareMarkdownContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import kotlin.time.TimeSource

data class ThreadMarkdownTag(
    val label: String,
    val kind: ThreadMarkdownTagKind,
    val channelName: String? = null,
    val threadShortId: String? = null
)

internal typealias SlockMessagePermalinkLabelResolver =
    (SlockMessagePermalinkTarget) -> String?

internal val LocalSlockMessagePermalinkLabelResolver =
    compositionLocalOf<SlockMessagePermalinkLabelResolver> { { null } }

internal data class SlockMarkdownRenderConfig(
    val renderMessagePermalinksAsTag: Boolean = false,
)

internal val LocalSlockMarkdownRenderConfig =
    compositionLocalOf { SlockMarkdownRenderConfig() }

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
    // Presentation-only. Click identity remains the source tag below.
    val resolvedLabel: String? = null,
    val tag: ThreadMarkdownTag? = null,
    val highlighted: Boolean = false
)

@Immutable
internal data class MarkdownInlineTokenPlan(
    val content: String,
    val tokens: List<MarkdownInlineToken>,
    private val inlineCodeMatches: List<ThreadInlineCodeMatch> = emptyList()
) {
    private val tagTokensByStart: List<MarkdownInlineToken> =
        tokens.filter { it.tag != null }.sortedBy { it.range.first }

    val signature: String =
        tokens.joinToString(separator = "|") { token ->
            "${token.id}:${token.kind}:${token.range.first}-${token.range.last}:" +
                "${token.label}:${token.resolvedLabel.orEmpty()}:${token.highlighted}"
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

    internal fun inlineCodeMatchesForRichText(): List<ThreadInlineCodeMatch> =
        inlineCodeMatches
}

private data class MarkdownInlineScanCacheKey(
    val content: String,
    val inlineContentScope: String?,
    val baseOffset: Int
)

private data class MarkdownInlineTokenState(
    val valid: Boolean,
    val highlighted: Boolean,
    val resolvedLabel: String?
)

private data class MarkdownInlineTokenPlanCacheKey(
    val scanKey: MarkdownInlineScanCacheKey,
    val tokenStates: List<MarkdownInlineTokenState>
)

/**
 * Keeps Markdown scanning and token construction stable across unrelated
 * directory hydration. The validator can be replaced when members/candidates
 * arrive, but a body only receives a new plan when one of its own tags changes
 * validity, highlight state, or resolved presentation label.
 */
internal class SlockMarkdownInlineTokenPlanner(
    private val maxEntries: Int = 512
) {
    private val lock = SynchronizedObject()
    private val scanEntries = LinkedHashMap<MarkdownInlineScanCacheKey, ThreadMarkdownInlineScan>()
    private val planEntries = LinkedHashMap<MarkdownInlineTokenPlanCacheKey, MarkdownInlineTokenPlan>()

    internal var planBuildCount: Int = 0
        private set

    fun plan(
        content: String,
        highlightedMentions: Set<String> = emptySet(),
        isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
        resolveTagLabel: ((ThreadMarkdownTag) -> String?)? = null,
        inlineContentScope: String? = null,
        baseOffset: Int = 0
    ): MarkdownInlineTokenPlan {
        val scanKey =
            MarkdownInlineScanCacheKey(
                content = content,
                inlineContentScope = inlineContentScope,
                baseOffset = baseOffset
            )
        val inlineScan =
            synchronized(lock) {
                scanEntries.remove(scanKey)?.also { cached -> scanEntries[scanKey] = cached }
            } ?: content.threadMarkdownInlineScan(
                baseOffset = baseOffset,
                inlineContentScope = inlineContentScope
            ).also { computed ->
                synchronized(lock) {
                    scanEntries.remove(scanKey)?.let { cached ->
                        scanEntries[scanKey] = cached
                    } ?: run {
                        scanEntries[scanKey] = computed
                        scanEntries.trimMarkdownCacheTo(maxEntries)
                    }
                }
            }
        val highlightedMentionKeys =
            highlightedMentions.mapTo(mutableSetOf()) { threadMarkdownMentionKey(it) }
        val tokenStates =
            inlineScan.tagMatches.map { match ->
                val valid = isTagValid(match.tag)
                MarkdownInlineTokenState(
                    valid = valid,
                    highlighted =
                        valid &&
                            match.tag.kind == ThreadMarkdownTagKind.UserMention &&
                            highlightedMentionKeys.containsThreadMention(match.tag.label),
                    resolvedLabel =
                        if (valid) {
                            resolveTagLabel
                                ?.invoke(match.tag)
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: threadMarkdownTagDisplayLabel(match.tag.label)
                        } else {
                            null
                        }
                )
            }
        val planKey = MarkdownInlineTokenPlanCacheKey(scanKey = scanKey, tokenStates = tokenStates)
        synchronized(lock) {
            planEntries.remove(planKey)?.let { cached ->
                planEntries[planKey] = cached
                return cached
            }
        }
        val tagTokens =
            inlineScan.tagMatches
                .zip(tokenStates)
                .mapNotNull { (match, state) ->
                    if (!state.valid) return@mapNotNull null
                    MarkdownInlineToken(
                        id = match.id,
                        kind = match.tag.kind.toMarkdownInlineTokenKind(),
                        range = match.range,
                        label = match.tag.label,
                        resolvedLabel = state.resolvedLabel,
                        tag = match.tag,
                        highlighted = state.highlighted
                    )
                }
        val plan =
            MarkdownInlineTokenPlan(
                content = content,
                tokens = tagTokens.sortedBy { it.range.first },
                inlineCodeMatches = inlineScan.inlineCodeMatches
            )
        return synchronized(lock) {
            planEntries.remove(planKey)?.let { cached ->
                planEntries[planKey] = cached
                cached
            } ?: plan.also {
                planEntries[planKey] = it
                planEntries.trimMarkdownCacheTo(maxEntries)
                planBuildCount += 1
            }
        }
    }

    private fun <K, V> LinkedHashMap<K, V>.trimMarkdownCacheTo(limit: Int) {
        val safeLimit = limit.coerceAtLeast(1)
        while (size > safeLimit) {
            keys.firstOrNull()?.let(::remove) ?: return
        }
    }
}

internal object SlockMarkdownInlineTokenRegistry {
    private val planner = SlockMarkdownInlineTokenPlanner()

    fun plan(
        content: String,
        highlightedMentions: Set<String> = emptySet(),
        isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
        resolveTagLabel: ((ThreadMarkdownTag) -> String?)? = null,
        inlineContentScope: String? = null,
        baseOffset: Int = 0
    ): MarkdownInlineTokenPlan =
        planner.plan(
            content = content,
            highlightedMentions = highlightedMentions,
            isTagValid = isTagValid,
            resolveTagLabel = resolveTagLabel,
            inlineContentScope = inlineContentScope,
            baseOffset = baseOffset
        )
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
            description = "Channels, task references, and user mentions should render as clickable inline refs.",
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
                - tags like #283 and #android-dev become clickable inline refs

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
        // A colon followed by whitespace belongs to the prose, not to a
        // channel-thread short id (for example `#design: next`).
        // Keep the channel token and leave the colon outside its range. A
        // non-empty but malformed short id remains rejected below.
        if (this[shortStart].isWhitespace()) {
            return start until nameEndExclusive
        }
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
    activeTagKey: String? = null,
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    tokenPlan: MarkdownInlineTokenPlan =
        SlockMarkdownInlineTokenRegistry.plan(
            content,
            highlightedMentions,
            isTagValid,
            resolveTagLabel = resolveTagLabel,
            inlineContentScope = inlineContentScope
        )
): MarkdownAnnotator {
    val document =
        buildSlockRichTextDocumentFromMarkdownPlan(
            source = content,
            content = content,
            tokenPlan = tokenPlan,
            activeTagKey = activeTagKey
        )
    return slockMarkdownAnnotator(
        document = document,
        onOpenAction = { action -> onOpenTag(action.toThreadMarkdownTag()) },
        resolveActionLabel =
            resolveTagLabel?.let { resolver ->
                { action: SlockRichTextAction -> resolver(action.toThreadMarkdownTag()) }
            },
        onDiagnostics = onDiagnostics
    )
}

internal fun slockMarkdownAnnotator(
    document: SlockRichTextDocument,
    onOpenAction: (SlockRichTextAction) -> Unit = {},
    resolveActionLabel: ((SlockRichTextAction) -> String?)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    capabilities: SlockRichTextCapabilities = SlockMarkdownRendererRichTextCapabilities
): MarkdownAnnotator {
    val diagnosticsStartedAt = onDiagnostics?.let { TimeSource.Monotonic.markNow() }
    val rendererInput = SlockRichTextRendererInput(document, capabilities)
    if (diagnosticsStartedAt != null) {
        onDiagnostics.invoke(
            rendererInput.slockRefTokenCount,
            rendererInput.inlineCodeTokenCount,
            diagnosticsStartedAt.elapsedNow().inWholeMilliseconds
        )
    }
    return markdownAnnotator(
        annotateSequence = { markdownContent, children, startIndex, _ ->
            consumeSlockRichTextSplitTagSequence(
                markdownContent = markdownContent,
                children = children,
                startIndex = startIndex,
                rendererInput = rendererInput,
                onOpenAction = onOpenAction,
                resolveActionLabel = resolveActionLabel
            )
        },
        annotate = { markdownContent, child, _ ->
            if (child.parent?.type == MarkdownElementTypes.CODE_SPAN) {
                return@markdownAnnotator false
            }

            if (child.type != MarkdownTokenTypes.TEXT) {
                return@markdownAnnotator false
            }

            val nodeText = child.getTextInNode(markdownContent).toString()
            val nodeStart = child.startOffset
            appendSlockRichTextSegment(
                text = nodeText,
                documentOffset = nodeStart,
                rendererInput = rendererInput,
                onOpenAction = onOpenAction,
                resolveActionLabel = resolveActionLabel
            )
        },
        inlineContent = { emptyMap() },
        config = markdownAnnotatorConfig(eolAsNewLine = true)
    )
}

private data class SlockRichTextRendererInput(
    val document: SlockRichTextDocument,
    val capabilities: SlockRichTextCapabilities
) {
    private val slockRefTokens: List<SlockRichTextInlineToken> =
        document.inlineTokens
            .filter { token -> token.kind == SlockRichTextInlineKind.SlockRef && token.actionId != null }
            .sortedBy { token -> token.range.start }
    private val actionsById: Map<String, SlockRichTextAction> =
        document.actions.associateBy { action -> action.id }

    val slockRefTokenCount: Int = slockRefTokens.size
    val inlineCodeTokenCount: Int =
        document.inlineTokens.count { token -> token.kind == SlockRichTextInlineKind.InlineCode }

    fun actionFor(token: SlockRichTextInlineToken): SlockRichTextAction? =
        token.actionId?.let(actionsById::get)

    fun slockRefTokensStartingInRange(start: Int, endExclusive: Int): List<SlockRichTextInlineToken> =
        slockRefTokens.filter { token ->
            token.range.start >= start && token.range.start < endExclusive && token.range.endExclusive <= endExclusive
        }

    fun slockRefTokensStartingInTextNode(start: Int, endExclusive: Int): List<SlockRichTextInlineToken> =
        slockRefTokens.filter { token ->
            token.range.start >= start && token.range.start < endExclusive
        }

    fun slockRefTokensMatchingText(text: String, minStart: Int): List<SlockRichTextInlineToken> {
        val matches =
            slockRefTokens.filter { token ->
                token.range.start >= minStart && token.sourceText.isNotEmpty() && text.contains(token.sourceText)
            }
        return matches.ifEmpty {
            slockRefTokens.filter { token -> token.sourceText.isNotEmpty() && text.contains(token.sourceText) }
        }
    }
}

private data class SlockRichTextSplitTagStart(
    val token: SlockRichTextInlineToken,
    val relativeStart: Int,
    val consumedPrefix: String
)

private fun AnnotatedString.Builder.consumeSlockRichTextSplitTagSequence(
    markdownContent: String,
    children: List<ASTNode>,
    startIndex: Int,
    rendererInput: SlockRichTextRendererInput,
    onOpenAction: (SlockRichTextAction) -> Unit,
    resolveActionLabel: ((SlockRichTextAction) -> String?)?
): Int {
    val child = children.getOrNull(startIndex) ?: return 0
    if (child.parent?.type == MarkdownElementTypes.CODE_SPAN || child.type != MarkdownTokenTypes.TEXT) {
        return 0
    }
    val nodeText = child.getTextInNode(markdownContent).toString()
    val nodeStart = child.startOffset
    val splitStart = nodeText.slockRichTextSplitTagStart(nodeStart, rendererInput) ?: return 0
    var consumedCount = 1
    var remaining = splitStart.token.sourceText.drop(splitStart.consumedPrefix.length)
    var tailText: String? = null
    var tailDocumentOffset = 0
    var nextIndex = startIndex + 1
    while (remaining.isNotEmpty() && nextIndex < children.size) {
        val nextChild = children[nextIndex]
        val nextText = nextChild.getTextInNode(markdownContent).toString()
        when {
            remaining.startsWith(nextText) -> {
                remaining = remaining.drop(nextText.length)
                consumedCount++
                nextIndex++
            }
            nextText.startsWith(remaining) -> {
                tailText = nextText.drop(remaining.length).takeIf { it.isNotEmpty() }
                tailDocumentOffset = nextChild.startOffset + remaining.length
                remaining = ""
                consumedCount++
            }
            else -> return 0
        }
    }
    if (remaining.isNotEmpty()) return 0

    val prefixEnd = splitStart.relativeStart
    if (prefixEnd > 0) {
        appendSlockRichTextSegmentOrText(
            text = nodeText.substring(0, prefixEnd),
            documentOffset = nodeStart,
            rendererInput = rendererInput,
            onOpenAction = onOpenAction,
            resolveActionLabel = resolveActionLabel
        )
    }
    appendSlockRichTextToken(
        token = splitStart.token,
        rendererInput = rendererInput,
        onOpenAction = onOpenAction,
        resolveActionLabel = resolveActionLabel
    )
    tailText?.let { tail ->
        appendSlockRichTextSegmentOrText(
            text = tail,
            documentOffset = tailDocumentOffset,
            rendererInput = rendererInput,
            onOpenAction = onOpenAction,
            resolveActionLabel = resolveActionLabel
        )
    }
    return consumedCount
}

private fun AnnotatedString.Builder.appendSlockRichTextSegmentOrText(
    text: String,
    documentOffset: Int,
    rendererInput: SlockRichTextRendererInput,
    onOpenAction: (SlockRichTextAction) -> Unit,
    resolveActionLabel: ((SlockRichTextAction) -> String?)?
) {
    if (!appendSlockRichTextSegment(
            text = text,
            documentOffset = documentOffset,
            rendererInput = rendererInput,
            onOpenAction = onOpenAction,
            resolveActionLabel = resolveActionLabel
        )
    ) {
        append(text)
    }
}

private fun String.slockRichTextSplitTagStart(
    nodeStart: Int,
    rendererInput: SlockRichTextRendererInput
): SlockRichTextSplitTagStart? {
    val nodeEndExclusive = nodeStart + length
    return rendererInput
        .slockRefTokensStartingInTextNode(nodeStart, nodeEndExclusive)
        .firstNotNullOfOrNull { token ->
            val relativeStartFromRange = token.range.start - nodeStart
            val relativeStart =
                if (relativeStartFromRange in indices) {
                    relativeStartFromRange
                } else {
                    indices.firstOrNull { index ->
                        val char = this[index]
                        (char == '#' || char == '@') && token.sourceText.startsWith(substring(index))
                    } ?: return@firstNotNullOfOrNull null
                }
            val available = substring(relativeStart)
            if (available.isNotEmpty() &&
                token.sourceText.startsWith(available) &&
                available.length < token.sourceText.length
            ) {
                SlockRichTextSplitTagStart(
                    token = token,
                    relativeStart = relativeStart,
                    consumedPrefix = available
                )
            } else {
                null
            }
        }
}

private fun AnnotatedString.Builder.appendSlockRichTextSegment(
    text: String,
    documentOffset: Int,
    rendererInput: SlockRichTextRendererInput,
    onOpenAction: (SlockRichTextAction) -> Unit,
    resolveActionLabel: ((SlockRichTextAction) -> String?)?
): Boolean {
    val documentTokens = rendererInput.slockRefTokensStartingInRange(documentOffset, documentOffset + text.length)
    val localTokens =
        if (documentTokens.isEmpty()) {
            // Some markdown-renderer paths, including list-item paragraphs,
            // expose child offsets in a local node coordinate space. Match
            // already-built IR tokens back to that text instead of reparsing
            // Slock refs in the renderer adapter.
            rendererInput.slockRefTokensMatchingText(text, minStart = documentOffset)
        } else {
            emptyList()
        }
    if (documentTokens.isEmpty() && localTokens.isEmpty()) return false

    var cursor = 0
    val useDocumentOffsets = documentTokens.isNotEmpty()
    val tokens = if (useDocumentOffsets) documentTokens else localTokens
    tokens.forEach { token ->
        val relativeRange =
            if (useDocumentOffsets) {
                (token.range.start - documentOffset) until (token.range.endExclusive - documentOffset)
            } else {
                0 until text.length
            }
        val sourceStart = text.indexOf(token.sourceText, startIndex = cursor).takeIf { it >= 0 }
        val relativeStart = sourceStart ?: relativeRange.first
        val relativeEndExclusive =
            (sourceStart?.let { it + token.sourceText.length } ?: relativeRange.last + 1)
                .coerceAtMost(text.length)
        if (relativeStart < cursor || relativeStart !in text.indices) return@forEach
        if (relativeStart > cursor) {
            append(text.substring(cursor, relativeStart))
        }
        appendSlockRichTextToken(
            token = token,
            rendererInput = rendererInput,
            onOpenAction = onOpenAction,
            resolveActionLabel = resolveActionLabel
        )
        cursor = relativeEndExclusive
    }
    if (cursor < text.length) append(text.substring(cursor))
    return true
}

private fun AnnotatedString.Builder.appendSlockRichTextToken(
    token: SlockRichTextInlineToken,
    rendererInput: SlockRichTextRendererInput,
    onOpenAction: (SlockRichTextAction) -> Unit,
    resolveActionLabel: ((SlockRichTextAction) -> String?)?
) {
    val action = rendererInput.actionFor(token) ?: return append(token.text)
    val label =
        resolveActionLabel?.invoke(action)?.takeIf { it.isNotBlank() }
            ?: token.text
    pushStringAnnotation(
        tag = SlockMarkdownTagAnnotationTag,
        annotation = action.id
    )
    withLink(
        LinkAnnotation.Clickable(
            tag = action.id,
            styles = slockMarkdownRichTextTagLinkStyles(token.chrome),
            linkInteractionListener = LinkInteractionListener { onOpenAction(action) }
        )
    ) {
        append(label)
    }
    pop()
}

private fun SlockRichTextAction.toThreadMarkdownTag(): ThreadMarkdownTag =
    ThreadMarkdownTag(
        label = label,
        kind =
            when (refKind) {
                SlockRichTextRefKind.Channel -> ThreadMarkdownTagKind.Channel
                SlockRichTextRefKind.Thread -> ThreadMarkdownTagKind.ChannelThread
                SlockRichTextRefKind.Task -> ThreadMarkdownTagKind.Task
                SlockRichTextRefKind.UserMention -> ThreadMarkdownTagKind.UserMention
            },
        channelName = channelName,
        threadShortId = threadShortId
    )

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
        val permalinkLabelResolver = LocalSlockMessagePermalinkLabelResolver.current
        val renderMessagePermalinksAsTag =
            LocalSlockMarkdownRenderConfig.current.renderMessagePermalinksAsTag
        val permalinkUriHandler = LocalUriHandler.current
        val permalinkCoroutineScope = rememberCoroutineScope()
        val permalinkContent =
            remember(
                text,
                permalinkLabelResolver,
                permalinkUriHandler,
                renderMessagePermalinksAsTag,
            ) {
                text.withSlockMessagePermalinkRenderConfig(
                    config =
                        SlockMarkdownRenderConfig(
                            renderMessagePermalinksAsTag = renderMessagePermalinksAsTag,
                        ),
                    resolveLabel = permalinkLabelResolver,
                ) { url ->
                    permalinkCoroutineScope.launch { permalinkUriHandler.openUri(url) }
                }
            }
        val resolvedText = remember(permalinkContent.text, resolvedStyle) {
            permalinkContent.text.withSlockMarkdownSpanFonts(resolvedStyle)
        }
        val resolvedInlineContent =
            resolvedText.filteredSlockMarkdownInlineContent(
                inlineContent + permalinkContent.inlineContent
            )
        logMarkdownSpike(
            phase = "markdown.diagnostics.rich_text",
            durationUs = 0L,
            bodyLength = resolvedText.length,
            extra =
                "textLength=${resolvedText.text.length} " +
                    "spanStyles=${resolvedText.spanStyles.size} " +
                    "stringAnnotations=${resolvedText.getStringAnnotations(0, resolvedText.length).size} " +
                    "linkAnnotations=${resolvedText.getLinkAnnotations(0, resolvedText.length).size} " +
                    "incomingInlineContent=${inlineContent.size} " +
                    "resolvedInlineContent=${resolvedInlineContent.size} " +
                    "inlineIds=${resolvedText.slockMarkdownInlineContentIds().size}"
        )
        var lastLayoutSignature by remember { mutableStateOf<SlockMarkdownTextLayoutSignature?>(null) }
        val resolvedOnTextLayout: (TextLayoutResult) -> Unit =
            if (resolvedInlineContent.isEmpty()) {
                { result ->
                    logMarkdownSpike(
                        phase = "markdown.diagnostics.rich_text_layout",
                        durationUs = 0L,
                        bodyLength = resolvedText.length,
                        extra =
                            "size=${result.size.width}x${result.size.height} " +
                                "placeholderRects=${result.placeholderRects.size} " +
                                "inlineContent=0"
                    )
                    onTextLayout(result)
                }
            } else {
                { result ->
                    val signature =
                        SlockMarkdownTextLayoutSignature(
                            size = result.size,
                            placeholderRects = result.placeholderRects.toList()
                        )
                    if (signature != lastLayoutSignature) {
                        lastLayoutSignature = signature
                        logMarkdownSpike(
                            phase = "markdown.diagnostics.rich_text_layout",
                            durationUs = 0L,
                            bodyLength = resolvedText.length,
                            extra =
                                "size=${result.size.width}x${result.size.height} " +
                                    "placeholderRects=${result.placeholderRects.size} " +
                                    "nullPlaceholderRects=${result.placeholderRects.count { it == null }} " +
                                    "inlineContent=${resolvedInlineContent.size}"
                        )
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
private val SlockMessagePermalinkChipBackground = Color(0xFFFCEFBD)
// Chrome-related fields are platform-derived (task #441): declaring
// consumesChromeTokens/hasDrawAdapter=true in commonMain overclaimed platforms
// whose native renderer doesn't draw the chip chrome (it was hardcoded true
// while OHOS had no chip at all). Document/action/style-token consumption is
// shared-side behavior and genuinely platform-independent.
internal val SlockMarkdownRendererRichTextCapabilities: SlockRichTextCapabilities
    get() {
        val platformCaps = slockRichTextPlatformCaps()
        val chromeRenders = platformCaps.inlineCodeChrome && platformCaps.slockRefChrome
        return SlockRichTextCapabilities(
            consumesDocument = true,
            consumesChromeTokens = chromeRenders,
            consumesActions = true,
            consumesStyleTokens = true,
            hasMeasurementAdapter = chromeRenders,
            hasDrawAdapter = chromeRenders,
            hasHitTestAdapter = true,
            declaredDivergences = platformCaps.declaredDivergences
        )
    }

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
            when {
                // Monospace intent (code spans) maps to Space Mono for react
                // parity (task #407) — NOT the Slock body family, which made
                // inline code render in Space Grotesk (bigger x-height, denser)
                // than react's Space Mono.
                range.item.fontFamily == FontFamily.Monospace ->
                    slockMonoFamilyForWeight(explicitWeight)
                shouldNormalizeFamily ->
                    explicitWeight?.let(::slockFontFamilyForWeight) ?: resolvedBase.fontFamily
                else -> range.item.fontFamily
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

/**
 * Rewrites canonical Raft message-link annotations into one generic boxed
 * text span. The URL remains the click identity; renderer input contains only
 * ordinary text/link data plus [InlineBoxSpanStyle].
 */
internal fun AnnotatedString.withSlockMessagePermalinkChips(
    resolveLabel: SlockMessagePermalinkLabelResolver = { null }
): AnnotatedString = withSlockMessagePermalinkChipContent(resolveLabel).text

internal data class SlockMessagePermalinkChipContent(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

internal fun AnnotatedString.withSlockMessagePermalinkRenderConfig(
    config: SlockMarkdownRenderConfig = SlockMarkdownRenderConfig(),
    resolveLabel: SlockMessagePermalinkLabelResolver = { null },
    openUri: (String) -> Unit = {},
): SlockMessagePermalinkChipContent =
    if (config.renderMessagePermalinksAsTag) {
        withSlockMessagePermalinkChipContent(resolveLabel, openUri)
    } else {
        SlockMessagePermalinkChipContent(
            text = withoutLinksInsideSlockInlineCode(),
            inlineContent = emptyMap(),
        )
    }

private fun AnnotatedString.withSlockMessagePermalinkChipContent(
    resolveLabel: SlockMessagePermalinkLabelResolver = { null },
    openUri: (String) -> Unit = {},
): SlockMessagePermalinkChipContent {
    val source = withoutLinksInsideSlockInlineCode()
    val permalinkLinks =
        source.getLinkAnnotations(0, source.length)
            .mapNotNull { range ->
                val link = range.item as? LinkAnnotation.Url ?: return@mapNotNull null
                val target = link.url.toSlockMessagePermalinkTarget() ?: return@mapNotNull null
                Triple(range, link, target)
            }
            .sortedBy { (range, _, _) -> range.start }
    if (permalinkLinks.isEmpty()) {
        return SlockMessagePermalinkChipContent(source, emptyMap())
    }

    val builder = AnnotatedString.Builder(source.length)
    val inlineContent = linkedMapOf<String, InlineTextContent>()
    var cursor = 0
    permalinkLinks.forEach { (range, link, target) ->
        if (range.start < cursor || range.end <= range.start) return@forEach
        builder.append(source, cursor, range.start)
        val targetLabel = target.slockMessagePermalinkChipTargetLabel(resolveLabel(target))
        val kindLabel = target.slockMessagePermalinkChipKindLabel()
        val inlinePrefix = "slock-message-permalink-${range.start}-${range.end}"
        val iconId = "$inlinePrefix-icon"
        val iconGapId = "$inlinePrefix-icon-gap"
        val suffixGapId = "$inlinePrefix-suffix-gap"
        builder.withLink(
            LinkAnnotation.Url(
                url = link.url,
                styles = slockMessagePermalinkChipLinkStyles(),
                linkInteractionListener = LinkInteractionListener { clickedLink: LinkAnnotation ->
                    val clickedUrl = clickedLink as? LinkAnnotation.Url ?: return@LinkInteractionListener
                    link.linkInteractionListener?.onClick(clickedUrl) ?: openUri(clickedUrl.url)
                }
            )
        ) {
            appendInlineContent(iconId, "\uFFFC")
            appendInlineContent(iconGapId, "\uFFFC")
            withStyle(
                SpanStyle(
                    color = SlockColor.TextBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = slockFontFamilyForWeight(FontWeight.Bold),
                )
            ) {
                append(targetLabel)
            }
            appendInlineContent(suffixGapId, " ")
            withStyle(
                SpanStyle(
                    color = SlockColor.TextBlack.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = slockFontFamilyForWeight(FontWeight.Normal),
                )
            ) {
                append(kindLabel)
            }
        }
        inlineContent[iconId] = slockMessagePermalinkIconInlineContent()
        inlineContent[iconGapId] = slockMessagePermalinkSpacerInlineContent(4.sp)
        inlineContent[suffixGapId] = slockMessagePermalinkSpacerInlineContent(6.sp)
        cursor = range.end
    }
    builder.append(source, cursor, source.length)
    return SlockMessagePermalinkChipContent(
        text = builder.toAnnotatedString(),
        inlineContent = inlineContent
    )
}

private fun slockMessagePermalinkIconInlineContent(): InlineTextContent {
    return InlineTextContent(
        placeholder =
            Placeholder(
                width = 12.sp,
                height = 12.sp,
            )
    ) {
        SlockLucideIcon(
            name = SlockLucideIconName.Link,
            modifier = Modifier.size(12.dp),
            color = SlockColor.TextBlackArgb,
            strokeWidth = 2f,
        )
    }
}

private fun slockMessagePermalinkSpacerInlineContent(width: TextUnit): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(width = width, height = 1.sp),
    ) {}

private fun SlockMessagePermalinkTarget.slockMessagePermalinkChipTargetLabel(
    resolvedLabel: String?
): String {
    val prefix = if (routeKind == build.raft.app.model.ConversationKind.DirectMessage) "@" else "#"
    val name =
        resolvedLabel
            ?.trim()
            ?.removePrefix("#")
            ?.removePrefix("@")
            ?.takeIf(String::isNotBlank)
            ?: return "linked message"
    return "$prefix$name"
}

private fun SlockMessagePermalinkTarget.slockMessagePermalinkChipKindLabel(): String =
    if (threadParentMessageId == null) "msg" else "thread"

/** Markdown parsers may retain autolink annotations inside code spans. */
private fun AnnotatedString.withoutLinksInsideSlockInlineCode(): AnnotatedString {
    val links = getLinkAnnotations(0, length)
    if (links.none { range ->
            getStringAnnotations(
                SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
                range.start,
                range.end
            ).isNotEmpty()
        }
    ) {
        return this
    }

    return AnnotatedString.Builder(length)
        .apply {
            append(text)
            spanStyles.forEach { range -> addStyle(range.item, range.start, range.end) }
            paragraphStyles.forEach { range -> addStyle(range.item, range.start, range.end) }
            getStringAnnotations(0, length).forEach { range ->
                addStringAnnotation(range.tag, range.item, range.start, range.end)
            }
            links.forEach { range ->
                val isInlineCode =
                    getStringAnnotations(
                        SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
                        range.start,
                        range.end
                    ).isNotEmpty()
                if (!isInlineCode) {
                    when (val link = range.item) {
                        is LinkAnnotation.Url -> addLink(link, range.start, range.end)
                        is LinkAnnotation.Clickable -> addLink(link, range.start, range.end)
                    }
                }
            }
        }
        .toAnnotatedString()
}

private fun slockMessagePermalinkChipLinkStyles(): TextLinkStyles {
    val base =
        slockMarkdownTagBaseSpanStyle().copy(
            color = SlockColor.TextBlack,
            fontWeight = FontWeight.Bold,
            textDecoration = null,
            background = Color.Unspecified,
            inlineBoxStyle =
                InlineBoxSpanStyle(
                    backgroundColor = SlockMessagePermalinkChipBackground,
                    borderColor = SlockColor.BorderBlack,
                    borderWidth = 1.dp,
                    paddingStart = 4.dp,
                    paddingEnd = 4.dp,
                    paddingTop = 1.dp,
                    paddingBottom = 1.dp,
                    marginStart = 2.dp,
                    marginEnd = 2.dp
                )
        )
    return TextLinkStyles(
        style = base,
        pressedStyle =
            base.copy(
                inlineBoxStyle = base.inlineBoxStyle?.copy(backgroundColor = SlockColor.BrandYellow)
            )
    )
}

private object SlockMarkdownPreparedBodyCache {
    private const val MaxEntries = 256
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, String>()

    fun getOrPut(
        body: String,
        value: () -> String
    ): Pair<String, Boolean> {
        synchronized(lock) { entries[body] }?.let { cached ->
            return cached to true
        }
        val prepared = value()
        return synchronized(lock) {
            entries[body]?.let { cached ->
                return@synchronized cached to true
            }
            entries[body] = prepared
            if (entries.size > MaxEntries) {
                entries.keys.firstOrNull()?.let(entries::remove)
            }
            prepared to false
        }
    }
}

private object SlockMarkdownPreparseWarmCache {
    private const val MaxEntries = 512
    private val lock = SynchronizedObject()
    private val preparedKeys = LinkedHashSet<String>()
    private val inFlightKeys = mutableSetOf<String>()

    data class Claim(
        val contents: List<MarkdownParserContent>,
        val coveredCount: Int
    )

    fun claim(contents: List<MarkdownParserContent>): Claim = synchronized(lock) {
        val claimed = mutableListOf<MarkdownParserContent>()
        var coveredCount = 0
        contents.forEach { content ->
            val key = content.sourceContent
            if (key in preparedKeys || !inFlightKeys.add(key)) {
                coveredCount += 1
            } else {
                claimed += content
            }
        }
        Claim(contents = claimed, coveredCount = coveredCount)
    }

    fun complete(content: MarkdownParserContent) = synchronized(lock) {
        val key = content.sourceContent
        inFlightKeys.remove(key)
        preparedKeys.remove(key)
        preparedKeys.add(key)
        while (preparedKeys.size > MaxEntries) {
            preparedKeys.firstOrNull()?.let(preparedKeys::remove)
        }
    }

    fun release(content: MarkdownParserContent) = synchronized(lock) {
        inFlightKeys.remove(content.sourceContent)
    }
}

internal data class SlockMarkdownPreparseResult(
    val requestedCount: Int,
    val parsedCount: Int,
    val cacheHitCount: Int,
    val scheduledCount: Int
)

internal suspend fun prepareSlockMarkdownFullRendererBodies(
    bodies: List<String>,
    maxBodies: Int = SlockMarkdownDefaultPreparseLimit
): SlockMarkdownPreparseResult = coroutineScope {
    if (bodies.isEmpty() || maxBodies <= 0) {
        return@coroutineScope SlockMarkdownPreparseResult(
            requestedCount = 0,
            parsedCount = 0,
            cacheHitCount = 0,
            scheduledCount = 0
        )
    }
    val contents =
        bodies
            .asSequence()
            .mapNotNull { body -> body.preparedSlockMarkdownFullRendererContentOrNull() }
            .distinct()
            .take(maxBodies)
            .toList()
    val claim = SlockMarkdownPreparseWarmCache.claim(contents)
    val scheduledCacheHitCount =
        try {
            claim.contents
                .map { content ->
                    async {
                        try {
                            prepareMarkdownContent(content).also {
                                SlockMarkdownPreparseWarmCache.complete(content)
                            }
                        } catch (error: Throwable) {
                            SlockMarkdownPreparseWarmCache.release(content)
                            throw error
                        }
                    }
                }
                .awaitAll()
                .count { cacheHit -> cacheHit }
        } catch (error: Throwable) {
            claim.contents.forEach(SlockMarkdownPreparseWarmCache::release)
            throw error
        }
    SlockMarkdownPreparseResult(
        requestedCount = contents.size,
        parsedCount = claim.contents.size - scheduledCacheHitCount,
        cacheHitCount = claim.coveredCount + scheduledCacheHitCount,
        scheduledCount = claim.contents.size
    )
}

internal fun String.preparedSlockMarkdownFullRendererContentOrNull(): MarkdownParserContent? {
    val (content, _) =
        SlockMarkdownPreparedBodyCache.getOrPut(this) {
            normalizeThreadMarkdownBody()
        }
    if (content.isBlank()) return null
    return MarkdownParserContent.from(content)
}

private const val SlockMarkdownDefaultPreparseLimit = 20

private val SlockMarkdownBodyFontSize = 14.sp
private val SlockMarkdownBodyLineHeight = 22.sp

/**
 * Derives the immutable presentation plan for one Markdown body. Global member
 * directories may replace the validator/resolver lambdas, but the bounded
 * planner returns the same plan instance when none of this body's own tags
 * changed. Passing that stable plan into [SlockMarkdownBody] lets Compose skip
 * the expensive renderer body for unrelated member hydration.
 */
@Composable
internal fun rememberSlockMarkdownBodyTokenPlan(
    body: String,
    highlightedMentions: Set<String> = emptySet(),
    isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    inlineContentScope: String? = null
): MarkdownInlineTokenPlan {
    val content =
        remember(body) {
            SlockMarkdownPreparedBodyCache.getOrPut(body) {
                body.normalizeThreadMarkdownBody()
            }.first
        }
    val parserContent =
        remember(content) {
            MarkdownParserContent.from(content).parserContent
        }
    return remember(
        parserContent,
        highlightedMentions,
        isTagValid,
        resolveTagLabel,
        inlineContentScope
    ) {
        SlockMarkdownInlineTokenRegistry.plan(
            content = parserContent,
            highlightedMentions = highlightedMentions,
            isTagValid = isTagValid,
            resolveTagLabel = resolveTagLabel,
            inlineContentScope = inlineContentScope
        )
    }
}

@Composable
internal fun SlockMarkdownBody(
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
    activeTagKey: String? = null,
    precomputedTokenPlan: MarkdownInlineTokenPlan? = null,
    performanceKey: String? = null,
    performanceLogger: (String) -> Unit = {}
) {
    // task #441: print the factual renderer-capability marker so visual capture
    // records this platform's caps (collector: scripts/collect_richtext_caps.py;
    // missing marker = unknown, repeated lines are fine — the last one wins).
    LaunchedEffect(Unit) {
        println(slockRichTextCapsLine())
    }
    SlockMarkdownBodyRecomposeMarker(
        performanceKey = performanceKey,
        bodyLength = body.length,
        performanceLogger = performanceLogger
    )
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
    val currentOnOpenTag by rememberUpdatedState(onOpenTag)
    val currentOnOpenUrl = rememberUpdatedState(onOpenUrl)
    val currentResolveTagLabel by rememberUpdatedState(resolveTagLabel)
    val colors = SlockMarkdownColorsDefault
    val typography = remember(markdownTextStyle) { slockMarkdownTypography(markdownTextStyle) }
    val markdownParserContent = remember(content) {
        MarkdownParserContent.from(content)
    }
    val parserContent = markdownParserContent.parserContent
    val tokenPlan =
        precomputedTokenPlan
            ?: remember(
                markdownParserContent,
                highlightedMentions,
                isTagValid,
                resolveTagLabel,
                inlineContentScope
            ) {
                SlockMarkdownInlineTokenRegistry.plan(
                    parserContent,
                    highlightedMentions,
                    isTagValid,
                    resolveTagLabel = resolveTagLabel,
                    inlineContentScope = inlineContentScope
                )
            }
    val bodyActiveTagKey =
        remember(tokenPlan, activeTagKey) {
            activeTagKey?.takeIf { targetKey ->
                tokenPlan.tagTokens().any { token ->
                    token.tag?.let(::threadMarkdownTagActivationKey) == targetKey
                }
            }
        }
    val richTextDocument: SlockRichTextDocument = remember(body, parserContent, tokenPlan, bodyActiveTagKey) {
        buildSlockRichTextDocumentFromMarkdownPlan(
            source = body,
            content = parserContent,
            tokenPlan = tokenPlan,
            activeTagKey = bodyActiveTagKey
        )
    }
    if (performanceKey != null) {
        LaunchedEffect(performanceKey, parserContent, highlightedMentions, richTextDocument, inlineContentScope) {
            performanceLogger(
                "thread_markdown_body key=$performanceKey contentLength=${content.length} " +
                    "parserHash=${parserContent.hashCode().toString(16)} highlighted=${highlightedMentions.size} " +
                    "tokenCount=${richTextDocument.inlineTokens.size} tagTokens=${richTextDocument.actions.size} " +
                    "inlineCode=${richTextDocument.inlineTokens.count { it.kind == SlockRichTextInlineKind.InlineCode }} " +
                    "scope=${inlineContentScope.orEmpty()}"
            )
        }
    }
    if (performanceKey != null) {
        logMarkdownSpike(
            phase = "markdown.diagnostics.body",
            durationUs = 0L,
            bodyLength = content.length,
            extra =
                "key=$performanceKey " +
                    "tokens=${richTextDocument.inlineTokens.size} " +
                    "tagTokens=${richTextDocument.actions.size} " +
                    "inlineCode=${richTextDocument.inlineTokens.count { it.kind == SlockRichTextInlineKind.InlineCode }} " +
                    "scope=${inlineContentScope.orEmpty()}"
        )
    }
    val annotator: MarkdownAnnotator = remember(richTextDocument, performanceKey) {
        slockMarkdownAnnotator(
            document = richTextDocument,
            onOpenAction = { action: SlockRichTextAction -> currentOnOpenTag(action.toThreadMarkdownTag()) },
            resolveActionLabel = { action: SlockRichTextAction ->
                currentResolveTagLabel?.invoke(action.toThreadMarkdownTag())
            },
            onDiagnostics =
                performanceKey?.let { key ->
                    { inlineTagCount: Int, inlineCodeCount: Int, durationMs: Long ->
                        if (shouldLogSlockMarkdownPerformance(durationMs)) {
                            performanceLogger(
                                "thread_markdown_prepare stage=annotator key=$key " +
                                    "inlineTags=$inlineTagCount inlineCode=$inlineCodeCount " +
                                    "contentLength=${content.length} durationMs=$durationMs"
                            )
                        }
                    }
                }
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
            codeBlock = PaddingValues(SlockSpacing.Sm),
            // Blockquote rail must span EXACTLY the quoted content's height
            // (task #62): the library defaults pad the quote text by 4dp
            // vertically and inset the bar by 2dp, so the rail overshot the
            // text above and below. Web renders border-l-2 with no vertical
            // padding — the border is precisely the content box.
            blockQuoteText = PaddingValues(0.dp),
            // Horizontal geometry mirrors web too (artin, task #62 follow-up):
            // border-l-2 sits flush at the quote origin and pl-3 puts the text
            // 12dp after it — total 14dp indent, not the library's 4+2+4+16.
            blockQuote = PaddingValues(start = 12.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
            blockQuoteBar = PaddingValues.Absolute(left = 0.dp, top = 0.dp, right = 0.dp, bottom = 0.dp)
        )
    }
    val components = remember {
        markdownComponents(
            horizontalRule = {
                SlockMarkdownHorizontalRule()
            },
            // task #66: code blocks carry a persistent transparent copy
            // affordance (top-right lucide copy, no plate — May task #9 native
            // style), tap copies the whole block; brief Check as feedback.
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, language, style ->
                    SlockMarkdownCodeBlockWithCopy(code, language, style)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, language, style ->
                    SlockMarkdownCodeBlockWithCopy(code, language, style)
                }
            }
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
                val refComponents = components
                val refModifier = markdownModifier
                val refColors = colors
                val refTextProvider = SlockMarkdownTextProvider
                // Pass-through, no-op; just to keep the locals from being flagged unused.
                @Suppress("UNUSED_EXPRESSION") refContent; refTypography; refAnnotator; refPadding; refComponents; refModifier; refColors; refTextProvider
            }
            traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT_BLOCK, body.length) {
                Markdown(
                    content = markdownParserContent,
                    colors = colors,
                    typography = typography,
                    textProvider = SlockMarkdownTextProvider,
                    annotator = annotator,
                    padding = padding,
                    dimens = SlockMarkdownDimens,
                    components = components,
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

// File-level singleton for the same #192 stable-reference reason as the colors
// above (`markdownDimens(...)` is @Composable and would allocate a fresh
// instance every recomposition). Values mirror the library defaults except the
// blockquote rail: web renders border-l-2 (2px), the 1dp default reads
// hairline-thin on device (task #62).
private object SlockMarkdownDimens : MarkdownDimens {
    override val dividerThickness: Dp = 0.5.dp
    override val codeBackgroundCornerSize: Dp = 8.dp
    override val blockQuoteThickness: Dp = 2.dp
    override val tableMaxWidth: Dp = Dp.Unspecified
    override val tableCellWidth: Dp = 160.dp
    override val tableSeparatorThickness: Dp = 1.dp
    override val tableCellPadding: Dp = 12.dp
    override val tableCornerSize: Dp = 8.dp
}
// web MarkdownContent pre: bg-[#07111f] (artin: 对齐, task #67 thread).
private val SlockMarkdownCodeBlockBackground = Color(0xFF07111F)
private val SlockMarkdownMinimumInlineLineHeight = 22.sp
private val SlockMarkdownDividerColor = SlockColor.BorderBlack.copy(alpha = 0.22f)
private val SlockMarkdownDividerThickness = 2.dp
private val SlockMarkdownDividerVerticalPadding = SlockSpacing.Sm

// task #66: persistent transparent copy affordance on code blocks — the old
// native Android app had this (May task #9: transparent tap area + lucide copy
// icon, no plate) and the KMP rewrite dropped it, leaving no way to copy code
// on phones. Shared implementation = Android/iOS/OHOS behave identically.
@Composable
private fun SlockMarkdownCodeBlockWithCopy(
    code: String,
    language: String?,
    style: TextStyle
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        MarkdownCode(code = code, language = language, style = style)
        Box(
            // Generous transparent hit target hugging the block's top-right
            // (the code surface is dark #111827 — translucent white icon).
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .clickable {
                        bridge.copyToPasteboard(code)
                        copied = true
                    }
                    .padding(top = 14.dp, end = 10.dp, bottom = 10.dp, start = 10.dp)
        ) {
            SlockLucideIcon(
                name = if (copied) SlockLucideIconName.Check else SlockLucideIconName.Copy,
                color = "#B3FFFFFF",
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SlockMarkdownHorizontalRule() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = SlockMarkdownDividerVerticalPadding)
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(SlockMarkdownDividerThickness)
                .background(SlockMarkdownDividerColor)
        )
    }
}

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
        inlineCodeBackground = SlockRichTextChromeKind.InlineCode.styleToken.background.toSlockMarkdownColor(),
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
        SlockRichTextChromeKind.Channel.styleToken.horizontalChromeSp.orZero())
        .coerceAtLeast(40f)
        .sp

// react parity (task #407): web inline code is `font-mono` = Space Mono at the
// inherited body size (MarkdownContent.tsx / index.css --font-mono). The old
// "keep the Slock body font" behavior predated the Space Mono wiring (#130) —
// back then FontFamily.Monospace meant the platform Roboto Mono, so pinning
// the body font was the lesser evil. Space Mono exists now; use it.
internal fun slockMarkdownInlineCodeTextStyle(base: TextStyle): TextStyle =
    SlockRichTextChromeKind.InlineCode.styleToken.applyToTextStyle(base)

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
    slockRichTextChromeKind(kind, highlighted = highlighted)
        .styleToken
        .chipFill
        .toSlockMarkdownColor()

internal val SlockMarkdownTagFontSize = SlockRichTextChromeKind.OrdinaryMention.styleToken.fontSizeSp.orZero().sp

internal fun threadMarkdownTagActivationKey(tag: ThreadMarkdownTag): String =
    buildString {
        append(tag.kind.name)
        append(':')
        append(tag.label.trim().lowercase())
        tag.channelName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { channelName ->
            append(":channel=")
            append(channelName)
        }
        tag.threadShortId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { threadShortId ->
            append(":thread=")
            append(threadShortId)
        }
    }

private fun slockMarkdownTagBaseSpanStyle(): SpanStyle =
    SlockType.ButtonBold
        .withSlockFontWeight(FontWeight.Bold)
        .toSpanStyle()
        .copy(fontSize = SlockMarkdownTagFontSize)

// Mention/ref visual styling mirrors react (packages/web/src/components/message/MentionLink.tsx):
// every non-self reference — a normal @mention, a #channel, a channel-thread and a task
// ref — renders as bold black text with a 2sp underline in black-at-30%-opacity offset
// ~2sp below the baseline (react: `underline decoration-black/30 decoration-2
// underline-offset-2`). Only the current-user self-mention gets the soft-signal chip
// (react: `MSG_REF_CHIP bg-soft-signal`). A text SpanStyle can express the chip fill but
// not react's 1px black border / px-1 padding / square corners, so the fill is the closest
// platform match; a bordered inline chip would require a dedicated composable.
internal fun slockMarkdownTagSpanStyle(
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false,
    active: Boolean = false
): SpanStyle {
    if (active) {
        return slockMarkdownSelfMentionChipSpanStyle()
    }
    val isSelfMention = kind == ThreadMarkdownTagKind.UserMention && highlighted
    return if (isSelfMention) {
        slockMarkdownSelfMentionChipSpanStyle()
    } else {
        slockMarkdownTagUnderlineSpanStyle()
    }
}

private fun slockMarkdownTagUnderlineSpanStyle(): SpanStyle =
    SlockRichTextChromeKind.OrdinaryMention.styleToken.toTagSpanStyle()

private fun slockMarkdownSelfMentionChipSpanStyle(): SpanStyle =
    SlockRichTextChromeKind.SelfMention.styleToken.toTagSpanStyle()

internal fun slockMarkdownTagLinkStyles(
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false,
    active: Boolean = false
): TextLinkStyles {
    val defaultStyle = slockMarkdownTagSpanStyle(kind, highlighted = highlighted, active = active)
    val pressedStyle =
        slockMarkdownTagSpanStyle(kind, highlighted = highlighted, active = true)
    return TextLinkStyles(
        style = defaultStyle,
        pressedStyle = pressedStyle
    )
}

internal fun slockMarkdownRichTextTagLinkStyles(
    chromeKind: SlockRichTextChromeKind?
): TextLinkStyles {
    val defaultStyle = slockMarkdownRichTextTagSpanStyle(chromeKind)
    val pressedStyle = slockMarkdownRichTextTagSpanStyle(SlockRichTextChromeKind.Active)
    return TextLinkStyles(
        style = defaultStyle,
        pressedStyle = pressedStyle
    )
}

private fun slockMarkdownRichTextTagSpanStyle(
    chromeKind: SlockRichTextChromeKind?
): SpanStyle =
    (chromeKind ?: SlockRichTextChromeKind.OrdinaryMention)
        .styleToken
        .toTagSpanStyle()

private fun slockRichTextChromeKind(
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false,
    active: Boolean = false
): SlockRichTextChromeKind =
    when {
        active -> SlockRichTextChromeKind.Active
        kind == ThreadMarkdownTagKind.UserMention && highlighted -> SlockRichTextChromeKind.SelfMention
        kind == ThreadMarkdownTagKind.UserMention -> SlockRichTextChromeKind.OrdinaryMention
        kind == ThreadMarkdownTagKind.ChannelThread -> SlockRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.Channel -> SlockRichTextChromeKind.Channel
        kind == ThreadMarkdownTagKind.Task -> SlockRichTextChromeKind.Task
        else -> SlockRichTextChromeKind.OrdinaryMention
    }

private fun SlockRichTextChromeStyleToken.applyToTextStyle(base: TextStyle): TextStyle =
    base.copy(
        fontFamily = fontFamily.toSlockFontFamily(fontWeight.toSlockFontWeight()),
        fontWeight = fontWeight.toSlockFontWeight()
    )

private fun SlockRichTextChromeStyleToken.toTagSpanStyle(): SpanStyle {
    val inlineBoxStyle = toInlineBoxSpanStyle()
    return slockMarkdownTagBaseSpanStyle()
        .copy(
            color = foreground.toSlockMarkdownColor(),
            textDecoration = if (inlineBoxStyle == null) textDecoration.toComposeTextDecoration() else null,
            textDecorationColor =
                if (inlineBoxStyle == null) textDecorationColor.toSlockMarkdownColorOrUnspecified()
                else Color.Unspecified,
            textDecorationThickness =
                if (inlineBoxStyle == null) textDecorationThicknessSp.toSpOrUnspecified()
                else TextUnit.Unspecified,
            textDecorationOffset =
                if (inlineBoxStyle == null) textDecorationOffsetSp.toSpOrUnspecified()
                else TextUnit.Unspecified,
            background = background.toSlockMarkdownColorOrUnspecified(),
            inlineBoxStyle = inlineBoxStyle
        )
}

/**
 * Compatibility lowering for the original Slock semantic chrome tokens.
 *
 * New consumers should construct [InlineBoxSpanStyle] directly. Existing
 * channel/thread/task/self-mention/inline-code tokens keep their current
 * pixels while render backends migrate away from understanding Slock kinds.
 */
private fun SlockRichTextChromeStyleToken.toInlineBoxSpanStyle(): InlineBoxSpanStyle? {
    if (id == SlockRichTextChromeStyleTokens.OrdinaryMention.id) return null
    val fill = chipFill ?: return null
    return InlineBoxSpanStyle(
        backgroundColor = fill.toSlockMarkdownColor(),
        borderColor = SlockColor.BorderBlack,
        borderWidth = 1.dp,
        paddingStart = 4.dp,
        paddingEnd = 4.dp,
        paddingTop = 2.dp,
        paddingBottom = 2.dp,
        marginStart = 2.dp,
        marginEnd = 2.dp,
        cornerRadius = 0.dp
    )
}

private fun SlockRichTextColorToken?.toSlockMarkdownColor(): Color =
    requireNotNull(this) { "Slock richtext color token is required for this chrome path" }
        .toSlockMarkdownColorOrUnspecified()

private fun SlockRichTextColorToken?.toSlockMarkdownColorOrUnspecified(): Color =
    when (this) {
        SlockRichTextColorToken.TextBlack -> SlockColor.TextBlack
        SlockRichTextColorToken.BrandYellow -> SlockColor.BrandYellow
        SlockRichTextColorToken.BrandYellow40 -> SlockColor.BrandYellow.copy(alpha = 0.4f)
        SlockRichTextColorToken.AccentPink30 -> SlockColor.AccentPink.copy(alpha = 0.3f)
        SlockRichTextColorToken.AccentCyan30 -> SlockColor.AccentCyan.copy(alpha = 0.3f)
        SlockRichTextColorToken.SoftSignal -> SlockColor.SoftSignal
        SlockRichTextColorToken.BorderBlack30 -> SlockColor.BorderBlack.copy(alpha = 0.3f)
        null -> Color.Unspecified
    }

private fun SlockRichTextFontFamilyToken.toSlockFontFamily(weight: FontWeight): FontFamily =
    when (this) {
        SlockRichTextFontFamilyToken.SlockMono -> slockMonoFamilyForWeight(weight)
        SlockRichTextFontFamilyToken.SlockUi -> slockFontFamilyForWeight(weight)
    }

private fun SlockRichTextFontWeightToken.toSlockFontWeight(): FontWeight =
    when (this) {
        SlockRichTextFontWeightToken.Normal -> FontWeight.Normal
        SlockRichTextFontWeightToken.SemiBold -> FontWeight.SemiBold
        SlockRichTextFontWeightToken.Bold -> FontWeight.Bold
    }

private fun SlockRichTextTextDecorationToken?.toComposeTextDecoration(): TextDecoration? =
    when (this) {
        SlockRichTextTextDecorationToken.Underline -> TextDecoration.Underline
        null -> null
    }

private fun Float?.orZero(): Float = this ?: 0f

private fun Float?.toSpOrUnspecified(): TextUnit = this?.sp ?: TextUnit.Unspecified
