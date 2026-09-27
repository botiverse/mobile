package build.raft.app.markdown

import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockMessagePermalinkTarget
import build.raft.app.SlockSpacing
import build.raft.app.SlockType
import build.raft.app.ReplyCacheProvenance
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.toSlockMessagePermalinkTarget
import build.raft.app.components.SlockCheckMarker
import build.raft.app.components.SlockCheckMarkerTone
import build.raft.app.resources.MR
import build.raft.app.slockFontFamilyForWeight
import build.raft.app.slockMonoFamilyForWeight
import build.raft.app.withResolvedSlockFontFamily
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.tencent.kuikly.compose.foundation.layout.wrapContentWidth
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.text.InlineTextContent
import com.tencent.kuikly.compose.foundation.text.appendInlineContent
import com.tencent.kuikly.compose.material3.Text as KuiklyText
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.semantics.role
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.semantics.stateDescription
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.boundsInRoot
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
import com.tencent.kuiklybase.markdown.compose.LocalImageLinkHandler
import com.tencent.kuiklybase.markdown.compose.LocalImageTransformer
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownAnimations
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownAnnotator
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownExtendedSpans
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownTypography
import com.tencent.kuiklybase.markdown.compose.LocalReferenceLinkHandler
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownRenderTiming
import com.tencent.kuiklybase.markdown.compose.LocalTextProvider
import com.tencent.kuiklybase.markdown.compose.SlockMarkdownVendorTraceThresholdUs
import com.tencent.kuiklybase.markdown.compose.slockMarkdownLogSubTrace
import com.tencent.kuiklybase.markdown.compose.slockMarkdownMonotonicNanosSince
import com.tencent.kuiklybase.markdown.compose.slockMarkdownRenderTimingOrNull
import com.tencent.kuiklybase.markdown.compose.components.markdownComponents
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.ImageLinkHandlerImpl
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownAnnotationRevision
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownParagraphFillWidth
import com.tencent.kuiklybase.markdown.compose.LocalSlockMarkdownRowRenderCostSink
import com.tencent.kuiklybase.markdown.compose.SlockMarkdownNodeTrace
import com.tencent.kuiklybase.markdown.compose.SlockMarkdownNodeTraceSink
import com.tencent.kuiklybase.markdown.compose.SlockMarkdownSubTrace
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.NoOpImageTransformerImpl
import com.tencent.kuiklybase.markdown.model.RaftMarkdownTokenTypes
import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandlerImpl
import com.tencent.kuiklybase.markdown.model.TextProvider
import com.tencent.kuiklybase.markdown.model.markdownAnimations
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownAnnotatorConfig
import com.tencent.kuiklybase.markdown.model.markdownExtendedSpans
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import com.tencent.kuiklybase.markdown.model.parseMarkdownTopLevelNodes
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownCode
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownCodeBlock
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownCodeFence
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownHeader
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownTable
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownTableHeader
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownParagraph
import com.tencent.kuiklybase.markdown.compose.components.MarkdownComponentModel
import com.tencent.kuiklybase.markdown.model.MarkdownDimens
import com.tencent.kuiklybase.markdown.model.markdownPadding
import com.tencent.kuiklybase.markdown.model.prepareMarkdownContent
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.time.TimeSource

data class ThreadMarkdownTag(
    val label: String,
    val kind: ThreadMarkdownTagKind,
    val channelName: String? = null,
    val threadShortId: String? = null,
    val directMessagePeerName: String? = null
)

internal typealias SlockMessagePermalinkLabelResolver =
    (SlockMessagePermalinkTarget) -> String?

internal val LocalSlockMessagePermalinkLabelResolver =
    compositionLocalOf<SlockMessagePermalinkLabelResolver> { { null } }

internal data class SlockMarkdownRenderConfig(
    val renderMessagePermalinksAsTag: Boolean = false,
    // #367 light path (diagnostic override, default OFF): single-paragraph
    // text-only bodies may skip the MarkdownSuccess scaffold — see
    // slockMarkdownSingleParagraphLightNodeOrNull.
    val singleParagraphLightPath: Boolean = false,
)

internal val LocalSlockMarkdownRenderConfig =
    compositionLocalOf { SlockMarkdownRenderConfig() }

enum class ThreadMarkdownTagKind {
    ChannelThread,
    DirectMessageThread,
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
    DirectMessageThread,
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

private data class MarkdownInlineSyntaxCacheKey(
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
    val syntaxKey: MarkdownInlineSyntaxCacheKey,
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
    private val syntaxEntries = LinkedHashMap<MarkdownInlineSyntaxCacheKey, MarkdownInlineTokenInputs>()
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
        val syntaxKey =
            MarkdownInlineSyntaxCacheKey(
                content = content,
                inlineContentScope = inlineContentScope,
                baseOffset = baseOffset
            )
        val tokenInputs =
            synchronized(lock) {
                syntaxEntries.remove(syntaxKey)?.also { cached -> syntaxEntries[syntaxKey] = cached }
            } ?: content.markdownInlineTokenInputs(
                baseOffset = baseOffset,
                inlineContentScope = inlineContentScope
            ).also { computed ->
                synchronized(lock) {
                    syntaxEntries.remove(syntaxKey)?.let { cached ->
                        syntaxEntries[syntaxKey] = cached
                    } ?: run {
                        syntaxEntries[syntaxKey] = computed
                        syntaxEntries.trimMarkdownCacheTo(maxEntries)
                    }
                }
            }
        val highlightedMentionKeys =
            highlightedMentions.mapTo(mutableSetOf()) { threadMarkdownMentionKey(it) }
        val tokenStates =
            tokenInputs.validatedTagMatches.map { match ->
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
        val planKey = MarkdownInlineTokenPlanCacheKey(syntaxKey = syntaxKey, tokenStates = tokenStates)
        synchronized(lock) {
            planEntries.remove(planKey)?.let { cached ->
                planEntries[planKey] = cached
                return cached
            }
        }
        val tagTokens =
            tokenInputs.validatedTagMatches
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
                inlineCodeMatches = tokenInputs.inlineCodeMatches
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
        ThreadMarkdownTagKind.DirectMessageThread -> MarkdownInlineTokenKind.DirectMessageThread
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

private data class MarkdownInlineTokenInputs(
    val inlineCodeMatches: List<ThreadInlineCodeMatch>,
    val validatedTagMatches: List<ThreadInlineTagMatch>
)

private fun String.markdownInlineTokenInputs(
    baseOffset: Int = 0,
    inlineContentScope: String? = null
): MarkdownInlineTokenInputs {
    val inlineSyntax = parseMarkdownInlineSyntax(this)
    val inlineContentIdPrefix = inlineContentScope.toSlockMarkdownInlineContentIdPrefix()
    val validatedTagMatches =
        inlineSyntax.raftRefs.mapNotNull { ref ->
            ref.sourceText.toThreadMarkdownTagOrNull()?.let { tag ->
                val range = ref.startOffset until ref.endOffset
                ThreadInlineTagMatch(
                    id = "$inlineContentIdPrefix:${baseOffset + range.first}",
                    tag = tag,
                    range = range
                )
            }
        }
    val inlineCodeMatches =
        inlineSyntax.inlineCodeSpans.map { span ->
            ThreadInlineCodeMatch(
                id = "slock-code:${baseOffset + span.startOffset}",
                code = span.codeText,
                range = span.startOffset until span.endOffset,
            )
        }
    return MarkdownInlineTokenInputs(
        inlineCodeMatches = inlineCodeMatches,
        validatedTagMatches = validatedTagMatches
    )
}

internal fun String?.toSlockMarkdownInlineContentIdPrefix(): String =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { scope -> "slock-$scope" }
        ?: "slock"

internal const val DEBUG_SLOCK_MARKDOWN_PERF_LOG = false
private const val SlockMarkdownPerformanceSlowThresholdMs = 8L


// t6-a: app-owned sink for the vendor per-node timing; same println channel as
// the other SlockMarkdownSpike lines so existing collection tooling applies.
internal object SlockMarkdownNodeSpikeSink : SlockMarkdownNodeTraceSink {
    override fun emit(line: String) {
        println(line)
    }
}

// Pure selection (reviewed stale-sink tooth): true -> the spike sink,
// false -> null; assigning the result on every SlockMarkdownBody composition
// guarantees a disabled gate clears any previously installed sink.
internal fun slockMarkdownNodeSinkFor(
    diagnosticsEnabled: Boolean,
    suppressedForOverheadTest: Boolean = SlockMarkdownNodeTrace.suppressedForOverheadTest,
): SlockMarkdownNodeTraceSink? =
    if (diagnosticsEnabled && !suppressedForOverheadTest) SlockMarkdownNodeSpikeSink else null

internal fun shouldLogSlockMarkdownPerformance(durationMs: Long): Boolean =
    DEBUG_SLOCK_MARKDOWN_PERF_LOG || durationMs >= SlockMarkdownPerformanceSlowThresholdMs

fun threadMarkdownTaskReferenceKey(label: String): String =
    label.removePrefix("#").trim().lowercase()

/**
 * GFM task-list markers are parsed as a dedicated CHECK_BOX token. Keep the
 * state test exact while accepting the standard upper-case `[X]` spelling.
 * Bare `[x]` text never reaches this helper because the parser only emits the
 * token inside a list item.
 */
internal fun markdownTaskListCheckboxChecked(markerText: String): Boolean =
    markerText.trim().equals("[x]", ignoreCase = true)

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
        isThreadDirectMessageReference() -> {
            val peerStart = indexOf('@') + 1
            val colonIndex = indexOf(':', peerStart)
            ThreadMarkdownTag(
                label = this,
                kind = ThreadMarkdownTagKind.DirectMessageThread,
                threadShortId = substring(colonIndex + 1),
                directMessagePeerName = substring(peerStart, colonIndex)
            )
        }
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

private fun String.isThreadDirectMessageReference(): Boolean {
    if (!startsWith("dm:@", ignoreCase = true)) return false
    val peerStart = 4
    val colonIndex = indexOf(':', peerStart)
    if (colonIndex <= peerStart || colonIndex == lastIndex) return false
    val peerRef = parseRaftDirectMessagePeerRef(substring(peerStart, colonIndex)) ?: return false
    val peerName = peerRef.peerName
    if (!peerName.first().isAsciiWordCharacter() || peerName.any { !it.isRaftDirectMessagePeerPart() }) return false
    val shortId = substring(colonIndex + 1)
    return shortId.length in 6..8 && shortId.all(Char::isThreadShortIdPart)
}

private fun Char.isRaftDirectMessagePeerPart(): Boolean =
    isAsciiWordCharacter() || this == '.' || this == '-'

private fun Char.isAsciiWordCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || isDigit() || this == '_'

private fun String.isThreadChannelReference(): Boolean {
    if (!startsWith('#')) return false
    val colonIndex = indexOf(':')
    if (colonIndex <= 1 || colonIndex == lastIndex) return false
    val shortId = substring(colonIndex + 1)
    return shortId.length in 6..8 && shortId.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

fun String.threadInlineTagMatches(baseOffset: Int = 0): List<ThreadInlineTagMatch> =
    markdownInlineTokenInputs(baseOffset).validatedTagMatches

private fun Char.isThreadShortIdPart(): Boolean =
    isDigit() || this in 'a'..'f' || this in 'A'..'F'

private fun String.isBareThreadShortIdLikeReference(): Boolean =
    length in 6..8 &&
        any { it.isDigit() } &&
        any { it in 'a'..'f' || it in 'A'..'F' } &&
        all { it.isThreadShortIdPart() }

internal fun slockMarkdownAnnotator(
    content: String,
    highlightedMentions: Set<String> = emptySet(),
    isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultSlockMarkdownTagValidity,
    inlineContentScope: String? = null,
    activeTagKey: String? = null,
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    softLineBreaksAsNewLines: Boolean = true,
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
        onDiagnostics = onDiagnostics,
        softLineBreaksAsNewLines = softLineBreaksAsNewLines
    )
}

internal fun slockMarkdownAnnotator(
    document: SlockRichTextDocument,
    onOpenAction: (SlockRichTextAction) -> Unit = {},
    resolveActionLabel: ((SlockRichTextAction) -> String?)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    capabilities: SlockRichTextCapabilities = SlockMarkdownRendererRichTextCapabilities,
    softLineBreaksAsNewLines: Boolean = true
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

            if (child.type != MarkdownTokenTypes.TEXT && child.type != RaftMarkdownTokenTypes.REFERENCE) {
                return@markdownAnnotator false
            }

            val nodeText = child.getTextInNode(markdownContent).toString()
            val nodeStart = child.startOffset
            appendSlockRichTextSegment(
                text = nodeText,
                documentOffset = nodeStart,
                rendererInput = rendererInput,
                onOpenAction = onOpenAction,
                resolveActionLabel = resolveActionLabel,
                allowLocalFallback = child.type != RaftMarkdownTokenTypes.REFERENCE,
            )
        },
        inlineContent = {
            rendererInput.activeActionSpinnerInlineContent()
        },
        config = markdownAnnotatorConfig(eolAsNewLine = softLineBreaksAsNewLines)
    )
}

private data class SlockRichTextRendererInput(
    val document: SlockRichTextDocument,
    val capabilities: SlockRichTextCapabilities
) {
    val slockRefTokens: List<SlockRichTextInlineToken> =
        document.inlineTokens
            .filter { token -> token.kind == SlockRichTextInlineKind.SlockRef && token.actionId != null }
            .sortedBy { token -> token.range.start }
    private val actionsById: Map<String, SlockRichTextAction> =
        document.actions.associateBy { action -> action.id }

    val slockRefTokenCount: Int = slockRefTokens.size
    val inlineCodeTokenCount: Int =
        document.inlineTokens.count { token -> token.kind == SlockRichTextInlineKind.InlineCode }

    fun activeActionSpinnerInlineContent(): Map<String, InlineTextContent> {
        val activeTokens = slockRefTokens.filter { it.active && it.trailingIndicator.isEmpty() && it.actionId != null }
        if (activeTokens.isEmpty()) return emptyMap()
        return activeTokens.associate { token ->
            val actionId = requireNotNull(token.actionId)
            slockMarkdownActionSpinnerInlineId(actionId) to slockMarkdownActionSpinnerInlineTextContent()
        }
    }

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

internal fun slockMarkdownActionSpinnerInlineId(actionId: String): String =
    "slock-markdown-action-spinner:$actionId"

internal fun slockMarkdownActionSpinnerInlineTextContent(): InlineTextContent =
    InlineTextContent(
        placeholder =
            Placeholder(
                width = 12.sp,
                height = 12.sp,
            )
    ) {
        SlockLucideIcon(
            name = SlockLucideIconName.LoaderCircle,
            modifier = Modifier.size(12.dp),
            color = SlockColor.TextBlackArgb,
            strokeWidth = 2f,
            spinning = true,
        )
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
    resolveActionLabel: ((SlockRichTextAction) -> String?)?,
    allowLocalFallback: Boolean = true,
): Boolean {
    val documentTokens = rendererInput.slockRefTokensStartingInRange(documentOffset, documentOffset + text.length)
    val localTokens =
        if (documentTokens.isEmpty() && allowLocalFallback) {
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
    val label = resolveSlockRichTextTokenLabel(token, resolveActionLabel?.invoke(action))
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
        if (token.active && token.trailingIndicator.isEmpty()) {
            append(" ")
            appendInlineContent(slockMarkdownActionSpinnerInlineId(action.id), "\uFFFC")
        }
    }
    pop()
}

internal fun resolveSlockRichTextTokenLabel(
    token: SlockRichTextInlineToken,
    resolvedLabel: String?
): String =
    resolvedLabel
        ?.takeIf { it.isNotBlank() }
        ?.plus(token.trailingIndicator)
        ?: token.text

private fun SlockRichTextAction.toThreadMarkdownTag(): ThreadMarkdownTag =
    ThreadMarkdownTag(
        label = label,
        kind =
            when (refKind) {
                SlockRichTextRefKind.Channel -> ThreadMarkdownTagKind.Channel
                SlockRichTextRefKind.Thread -> ThreadMarkdownTagKind.ChannelThread
                SlockRichTextRefKind.DirectMessageThread -> ThreadMarkdownTagKind.DirectMessageThread
                SlockRichTextRefKind.Task -> ThreadMarkdownTagKind.Task
                SlockRichTextRefKind.UserMention -> ThreadMarkdownTagKind.UserMention
            },
        channelName = channelName,
        threadShortId = threadShortId,
        directMessagePeerName = directMessagePeerName
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
        val bodyClick = LocalSlockMarkdownBodyClick.current
        if (bodyClick == null) {
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
        } else {
            KuiklyText(
                text = AnnotatedString(text).withSlockMarkdownBodyClick(bodyClick),
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
                inlineContent = emptyMap(),
                onTextLayout = onTextLayout ?: {},
                style = resolvedStyle
            )
        }
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
        val bodyClick = LocalSlockMarkdownBodyClick.current
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
        val styledText = remember(permalinkContent.text, resolvedStyle) {
            permalinkContent.text.withSlockMarkdownSpanFonts(resolvedStyle)
        }
        val resolvedText =
            remember(styledText, bodyClick) {
                styledText.withSlockMarkdownBodyClick(bodyClick)
            }
        val resolvedInlineContent =
            remember(resolvedText, inlineContent, permalinkContent) {
                resolvedText.filteredSlockMarkdownInlineContent(
                    inlineContent + permalinkContent.inlineContent
                )
            }
        logMarkdownSpike(
            phase = "markdown.diagnostics.rich_text",
            durationUs = 0L,
            bodyLength = resolvedText.length,
            extra = {
                "textLength=${resolvedText.text.length} " +
                    "spanStyles=${resolvedText.spanStyles.size} " +
                    "stringAnnotations=${resolvedText.getStringAnnotations(0, resolvedText.length).size} " +
                    "linkAnnotations=${resolvedText.getLinkAnnotations(0, resolvedText.length).size} " +
                    "incomingInlineContent=${inlineContent.size} " +
                    "resolvedInlineContent=${resolvedInlineContent.size} " +
                    "inlineIds=${resolvedText.slockMarkdownInlineContentIds().size}"
            }
        )
        var lastLayoutSignature by remember { mutableStateOf<SlockMarkdownTextLayoutSignature?>(null) }
        val resolvedOnTextLayout: (TextLayoutResult) -> Unit =
            if (resolvedInlineContent.isEmpty()) {
                { result ->
                    logMarkdownSpike(
                        phase = "markdown.diagnostics.rich_text_layout",
                        durationUs = 0L,
                        bodyLength = resolvedText.length,
                        extra = {
                            "size=${result.size.width}x${result.size.height} " +
                                "placeholderRects=${result.placeholderRects.size} " +
                                "inlineContent=0"
                        }
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
                            extra = {
                                "size=${result.size.width}x${result.size.height} " +
                                    "placeholderRects=${result.placeholderRects.size} " +
                                    "nullPlaceholderRects=${result.placeholderRects.count { it == null }} " +
                                    "inlineContent=${resolvedInlineContent.size}"
                            }
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

internal fun AnnotatedString.withSlockMarkdownBodyClick(onClick: (() -> Unit)?): AnnotatedString {
    if (onClick == null || isEmpty()) return this
    val occupiedRanges =
        getLinkAnnotations(0, length)
            .map { it.start until it.end }
            .sortedBy { it.first }
    val openBody =
        LinkAnnotation.Clickable(
            tag = SlockMarkdownBodyClickTag,
            linkInteractionListener = LinkInteractionListener { onClick() }
        )
    return AnnotatedString.Builder(length)
        .apply {
            append(text)
            spanStyles.forEach { range -> addStyle(range.item, range.start, range.end) }
            paragraphStyles.forEach { range -> addStyle(range.item, range.start, range.end) }
            getStringAnnotations(0, length).forEach { range ->
                addStringAnnotation(range.tag, range.item, range.start, range.end)
            }
            getLinkAnnotations(0, length).forEach { range ->
                when (val link = range.item) {
                    is LinkAnnotation.Url -> addLink(link, range.start, range.end)
                    is LinkAnnotation.Clickable -> addLink(link, range.start, range.end)
                }
            }
            var cursor = 0
            occupiedRanges.forEach { range ->
                val start = range.first.coerceIn(0, length)
                val endExclusive = (range.last + 1).coerceIn(start, length)
                if (start > cursor) addLink(openBody, cursor, start)
                cursor = maxOf(cursor, endExclusive)
            }
            if (cursor < length) addLink(openBody, cursor, length)
        }
        .toAnnotatedString()
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
    val inlineCodeRanges =
        getStringAnnotations(
            SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
            start = 0,
            end = length,
        )
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
    inlineCodeRanges.forEach { range ->
        builder.addStyle(
            SpanStyle(
                fontFamily = slockMonoFamilyForWeight(FontWeight.Normal),
                fontWeight = FontWeight.Normal,
                fontSize = SlockMarkdownBodyFontSize * 0.875f,
            ),
            range.start,
            range.end,
        )
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
internal val SlockMarkdownTaskCheckboxSize = 14.dp
internal val SlockMarkdownTaskCheckboxFirstLineOffset =
    ((22.dp - SlockMarkdownTaskCheckboxSize) / 2).coerceAtLeast(0.dp)
internal const val SlockMarkdownPendingActionIndicator = " …"
private const val SlockMarkdownBodyClickTag = "slock-markdown-body-click"
private val LocalSlockMarkdownBodyClick = compositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun SlockMarkdownTaskCheckbox(model: MarkdownComponentModel) {
    val checked = markdownTaskListCheckboxChecked(model.node.getTextInNode(model.content).toString())
    val checkboxLabel = stringResource(MR.strings.markdown_task_list_checkbox)
    val stateLabel =
        stringResource(
            if (checked) MR.strings.markdown_task_list_checked
            else MR.strings.markdown_task_list_unchecked
        )
    Row(modifier = Modifier.wrapContentWidth()) {
        Box(
            modifier =
                Modifier
                    .slockMarkdownTaskCheckboxSemantics(
                        checkboxLabel = checkboxLabel,
                        stateLabel = stateLabel
                    )
                    // 14dp marker inside the 22dp first text line: pin the marker
                    // to that line instead of centering it in the whole list item,
                    // which may contain wrapped text or nested lists (task #210).
                    .padding(top = SlockMarkdownTaskCheckboxFirstLineOffset, end = 4.dp)
        ) {
            SlockCheckMarker(
                checked = checked,
                tone = SlockCheckMarkerTone.BlackFill,
                size = SlockMarkdownTaskCheckboxSize
            )
        }
    }
}

internal fun Modifier.slockMarkdownTaskCheckboxSemantics(
    checkboxLabel: String,
    stateLabel: String,
): Modifier =
    semantics {
        role = Role.Checkbox
        contentDescription = checkboxLabel
        stateDescription = stateLabel
    }

/**
 * #367 light-path override resolution, mirroring resolveActivityStableOrderEnabled:
 * the diagnostics "Local Feature Overrides" latch wins over the (default-off)
 * server evaluation; an unresolved server and no latch means OFF.
 */
internal fun resolveThreadMarkdownSingleParagraphLightPathEnabled(
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverEffective: Boolean,
    environmentId: String,
    accountId: String,
    serverId: String,
): Boolean =
    registry.decide(
        key = THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY,
        serverEffective = serverEffective,
        scope =
            DiagnosticFeatureFlagScope(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId,
                serverId = serverId,
            ),
    ).finalEffective

/**
 * #367 gate identity (review round 1): the route renders the REPLY-CACHE
 * provenance's server — `selectedServerStore.selectedServerId ?:
 * session.currentServerId` — so the flag query and the ServerExact
 * diagnostics scope must be built from that same provenance, never from
 * `session.currentServerId` alone. When the shell selects B while the session
 * current is A, deciding A's flag while rendering B would open/close the
 * experiment on the wrong server. Fail CLOSED: provenance not yet captured
 * (or any identity field blank) means no gate decision at all — the override
 * ForceOn latch must not fire with an empty principal/server scope.
 */
internal data class ThreadMarkdownLightGateInputs(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    // Authority epoch: the provenance's `generation`. The per-route
    // `remember(overrideRevision, gateInputs)` decision cache must re-fire
    // when the same identity triple rotates to a new generation — without
    // this field the data class compares equal and the stale decision is
    // reused (review round 2).
    val authorityEpoch: Long,
)

internal fun threadMarkdownLightGateInputsOrNull(
    provenance: ReplyCacheProvenance?,
): ThreadMarkdownLightGateInputs? {
    val inputs =
        provenance?.let {
            ThreadMarkdownLightGateInputs(
                environmentId = it.environmentId,
                accountId = it.principalId,
                serverId = it.serverId,
                authorityEpoch = it.generation,
            )
        } ?: return null
    return inputs.takeIf {
        it.environmentId.isNotBlank() && it.accountId.isNotBlank() && it.serverId.isNotBlank()
    }
}

/**
 * #367 light-path eligibility, decided from the PARSED node tree — never
 * raw-string heuristics ("*not a paragraph*", fenced code and friends must
 * not be misdetected). Exactly one top-level PARAGRAPH whose subtree holds
 * only inline node types that render through the plain
 * paragraph → MarkdownText → MarkdownBasicText chain qualifies. Anything
 * that could need the full scaffold — inline images (placeholder span +
 * container-size plumbing), inline HTML that survived the parser-input
 * escaping, reference-style links (SHORT_/FULL_REFERENCE_LINK nodes appear
 * even for undefined references and their resolution belongs to the full
 * scaffold, so they stay excluded on principle), or any node type not
 * enumerated — falls back to the full path.
 * Over-rejecting is safe (full path is always correct); a wrong admission is
 * the failure mode.
 *
 * The lookup runs through the renderer's own content-hash parse cache
 * (parseMarkdownTopLevelNodes), so a cold row pays exactly one parse — the
 * same parse MarkdownState would run — and a warm row pays a map lookup.
 */
internal fun slockMarkdownSingleParagraphLightNodeOrNull(parserContent: String): ASTNode? {
    val nodes = parseMarkdownTopLevelNodes(parserContent)
    val single = nodes.singleOrNull() ?: return null
    if (single.type != MarkdownElementTypes.PARAGRAPH) return null
    return single.takeIf { slockMarkdownLightParagraphSubtreeSafe(it) }
}

private fun slockMarkdownLightParagraphSubtreeSafe(root: ASTNode): Boolean {
    if (root.type !in SlockMarkdownLightSubtreeAllowedTypes) return false
    root.children.forEach { child ->
        if (!slockMarkdownLightParagraphSubtreeSafe(child)) return false
    }
    return true
}

private val SlockMarkdownLightSubtreeAllowedTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.EMPH,
        MarkdownElementTypes.STRONG,
        MarkdownElementTypes.CODE_SPAN,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.LINK_TEXT,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE,
        MarkdownElementTypes.AUTOLINK,
        GFMElementTypes.STRIKETHROUGH,
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HARD_LINE_BREAK,
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LT,
        MarkdownTokenTypes.GT,
        MarkdownTokenTypes.AUTOLINK,
        MarkdownTokenTypes.EMAIL_AUTOLINK,
        GFMTokenTypes.TILDE,
        GFMTokenTypes.GFM_AUTOLINK,
        RaftMarkdownTokenTypes.REFERENCE,
    )

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
    onOpenTagAction: ((ThreadMarkdownTag, String) -> Unit)? = null,
    onOpenUrl: (String) -> Unit = {},
    onClick: (() -> Unit)? = null,
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    inlineContentScope: String? = null,
    activeTagKey: String? = null,
    activeActionId: String? = null,
    activeActionIndicator: String = "",
    sectionHeadingTopPadding: Dp = 0.dp,
    onSectionHeadingPositioned: ((Rect) -> Unit)? = null,
    softLineBreaksAsNewLines: Boolean = true,
    performanceKey: String? = null,
    performanceLogger: (String) -> Unit = {},
    // task #800: right-aligned own messages wrap short paragraphs so a brief
    // message hugs the End edge; block children that fill width still expand
    // to the parent cap, so long/ranged content is unaffected.
    fillWidth: Boolean = true
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
    // t6-a: install/clear the vendor node-timing sink keyed to the same
    // diagnostics gate as the phase traces. Assignment only — with the gate
    // OFF the vendor stays on its untimed path (executable counterfactual:
    // sink == null before any clock read; see SlockMarkdownNodeTiming.kt).
    SlockMarkdownNodeTrace.sink = slockMarkdownNodeSinkFor(slockTraceDiagnosticsEnabled())
    // Same gate for the vendor SlockMarkdownSpike println channel: it used to
    // be an unconditional println, so production paid string building +
    // annotation scans + a logcat round-trip per text composition.
    SlockMarkdownSubTrace.enabled = slockTraceDiagnosticsEnabled()
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
    // Option B P1 v3 reconnaissance census token (task #20, reviewed):
    // gate-before-scan — with diagnostics OFF even the session Local read is
    // skipped, so neither the predicate's per-char scan nor any token work
    // executes and the vendor cost-sink Local carries null (zero work). The
    // session instance exists only inside the Thread page subtree (null
    // elsewhere — non-Thread markdown surfaces such as file preview never
    // participate). The token commits to its session ONLY when this
    // composition commits AND a valid render cost arrived; abandoned passes
    // and rows without a valid render leave zero trace, and the session's
    // seen-key set keeps lazy re-entries counted once per open.
    val rowCensusSession =
        if (slockTraceDiagnosticsEnabled()) LocalSlockMarkdownPlainCandidateSession.current else null
    val rowCensusToken =
        if (rowCensusSession != null && performanceKey != null) {
            remember(rowCensusSession, performanceKey, content) {
                SlockMarkdownRowCensusToken(
                    session = rowCensusSession,
                    rowKey = performanceKey,
                    candidate = SlockMarkdownPlainCandidatePredicate.isConservativePlainCandidate(content),
                    chars = content.length
                )
            }
        } else {
            null
        }
    val markdownModifier =
        if (DEBUG_SLOCK_MARKDOWN_PERF_LOG && performanceKey != null) {
            val key = performanceKey
            var layoutLogged by remember(content, performanceKey) { mutableStateOf(false) }
            val layoutStartedAt = remember(content, performanceKey) { TimeSource.Monotonic.markNow() }
            modifier
                .let { if (fillWidth) it.fillMaxWidth() else it }
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
            modifier.let { if (fillWidth) it.fillMaxWidth() else it }
        }
    val markdownTextStyle = remember(textStyle) {
        slockMarkdownTextStyleWithInlineLineHeight(textStyle)
    }
    val currentOnOpenTag by rememberUpdatedState(onOpenTag)
    val currentOnOpenTagAction by rememberUpdatedState(onOpenTagAction)
    val currentOnOpenUrl = rememberUpdatedState(onOpenUrl)
    val currentResolveTagLabel by rememberUpdatedState(resolveTagLabel)
    val colors = SlockMarkdownColorsDefault
    val typography = remember(markdownTextStyle) { slockMarkdownTypography(markdownTextStyle) }
    val markdownParserContent = remember(content) {
        MarkdownParserContent.from(content)
    }
    val parserContent = markdownParserContent.parserContent
    // #367 light path (override-gated, default OFF): gate-first — with the
    // override OFF the ambient read is the only added work, the tree lookup
    // never runs, and the render below is byte-identical to the full path.
    val lightParagraphNode =
        if (LocalSlockMarkdownRenderConfig.current.singleParagraphLightPath) {
            remember(markdownParserContent) {
                slockMarkdownSingleParagraphLightNodeOrNull(parserContent)
            }
        } else {
            null
        }
    val tokenPlan =
        remember(
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
    val bodyActiveActionId =
        remember(tokenPlan, activeActionId, activeTagKey) {
            (activeActionId ?: activeTagKey)?.takeIf { targetId ->
                tokenPlan.tagTokens().any { token -> token.id == targetId }
            }
        }
    val bodyActiveTagKey =
        remember(tokenPlan, activeTagKey, bodyActiveActionId) {
            activeTagKey?.takeIf { targetKey ->
                bodyActiveActionId == null &&
                tokenPlan.tagTokens().any { token ->
                    token.tag?.let(::threadMarkdownTagActivationKey) == targetKey
                }
            }
        }
    val richTextDocument: SlockRichTextDocument =
        remember(body, parserContent, tokenPlan, bodyActiveTagKey, bodyActiveActionId, activeActionIndicator) {
        buildSlockRichTextDocumentFromMarkdownPlan(
            source = body,
            content = parserContent,
            tokenPlan = tokenPlan,
            activeTagKey = bodyActiveTagKey,
            activeActionId = bodyActiveActionId,
            activeActionIndicator = activeActionIndicator
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
            extra = {
                "key=$performanceKey " +
                    "tokens=${richTextDocument.inlineTokens.size} " +
                    "tagTokens=${richTextDocument.actions.size} " +
                    "inlineCode=${richTextDocument.inlineTokens.count { it.kind == SlockRichTextInlineKind.InlineCode }} " +
                    "scope=${inlineContentScope.orEmpty()}"
            }
        )
    }
    val annotator: MarkdownAnnotator = remember(richTextDocument, performanceKey, softLineBreaksAsNewLines) {
        slockMarkdownAnnotator(
            document = richTextDocument,
            onOpenAction = { action: SlockRichTextAction ->
                val tag = action.toThreadMarkdownTag()
                currentOnOpenTagAction?.invoke(tag, action.id) ?: currentOnOpenTag(tag)
            },
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
                },
            softLineBreaksAsNewLines = softLineBreaksAsNewLines
        )
    }
    if (DEBUG_SLOCK_MARKDOWN_PERF_LOG && performanceKey != null) {
        performanceLogger(
            "thread_markdown_render stage=markdown_renderer key=$performanceKey contentLength=${content.length}"
        )
    }
    // Annotation revision for the vendor's opt-in AnnotatedString memo.
    // Dynamic tag/channel label resolution reaches the built string through a
    // stable annotator identity (currentResolveTagLabel is a
    // rememberUpdatedState read), so the memo key must carry the RESULTS: the
    // resolved label snapshot for THIS document's actions, recomputed inline
    // each composition as a plain list — no Compose state is read or written
    // here (a read-then-write generation counter was rejected in review as a
    // backward write scheduling an extra recomposition pass). Structural list
    // equality gives the memo semantics: identity-stable resolver with
    // unchanged results reuses, a result change rebuilds exactly once.
    // CONTRACT: any FUTURE dynamic annotation input added to the Slock
    // annotator MUST be folded into this revision value, or the vendor memo
    // will serve stale output for it.
    val annotationRevision: List<String?> =
        buildList {
            if (bodyActiveActionId != null) add("activeAction:$bodyActiveActionId")
            if (bodyActiveTagKey != null) add("activeTag:$bodyActiveTagKey")
            if (activeActionIndicator.isNotEmpty()) add("indicator:$activeActionIndicator")
            if (resolveTagLabel != null && richTextDocument.actions.isNotEmpty()) {
                richTextDocument.actions.forEach { action ->
                    add(currentResolveTagLabel?.invoke(action.toThreadMarkdownTag()))
                }
            }
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
    val components = remember(sectionHeadingTopPadding, onSectionHeadingPositioned) {
        val sectionHeadingModifier =
            Modifier
                .padding(top = sectionHeadingTopPadding)
                .let { modifier ->
                    if (onSectionHeadingPositioned == null) {
                        modifier
                    } else {
                        modifier.onGloballyPositioned { coordinates ->
                            onSectionHeadingPositioned(coordinates.boundsInRoot())
                        }
                    }
                }
        markdownComponents(
            // Kuikly's Markdown renderer stacks top-level nodes without a
            // spacer. Most message surfaces intentionally keep that dense
            // layout, but document surfaces can opt into a section gap. This
            // also reserves the paint overflow of an inline-code box before
            // the following heading at large system font scales.
            heading2 = { model ->
                MarkdownHeader(
                    model.content,
                    model.node,
                    style = model.typography.h2,
                    modifier = sectionHeadingModifier
                )
            },
            heading3 = { model ->
                MarkdownHeader(
                    model.content,
                    model.node,
                    style = model.typography.h3,
                    modifier = sectionHeadingModifier
                )
            },
            horizontalRule = {
                SlockMarkdownHorizontalRule()
            },
            // task #212: upstream's table header derives its own style by setting
            // fontWeight to Bold, which changes the weight but leaves the font FAMILY
            // alone. Slock encodes weight in the family name (Kuikly drops weight from
            // the serialized family, see withSlockFontWeight), so that derivation asks
            // the platform to synthesise bold on top of the Regular face instead of
            // using the real 700 cut — the header rendered heavier and blurrier than
            // React's `th`, which uses a true SpaceGrotesk Bold file. Handing the header
            // a style that already carries the Bold family makes upstream's weight
            // override redundant and yields the real cut.
            // Overriding the component here keeps the vendored fork free of a new
            // divergence.
            table = { model ->
                MarkdownTable(
                    model.content,
                    model.node,
                    style = model.typography.table,
                    headerBlock = { content, header, style, columnWidths, rowWidths ->
                        MarkdownTableHeader(
                            content = content,
                            header = header,
                            style = style.withSlockFontWeight(FontWeight.Bold),
                            columnWidths = columnWidths,
                            rowWidths = rowWidths
                        )
                    }
                )
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
            },
            checkbox = { model ->
                SlockMarkdownTaskCheckbox(model)
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
    if (lightParagraphNode != null) {
        // #367 light path: render the single eligible paragraph directly. The
        // same LAYOUT / LAYOUT_BLOCK trace sections wrap the render so phase
        // attribution stays comparable with the full path during A/B.
        traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT, body.length) {
            traceMarkdownSection(SlockMarkdownTraceNames.LAYOUT_BLOCK, body.length) {
                SlockMarkdownSingleParagraphLightBody(
                    content = parserContent,
                    paragraphNode = lightParagraphNode,
                    colors = colors,
                    typography = typography,
                    annotator = annotator,
                    markdownModifier = markdownModifier,
                    fillWidth = fillWidth,
                    annotationRevision = annotationRevision,
                    onClick = onClick,
                    uriHandler = uriHandler,
                    rowCensusToken = rowCensusToken
                )
            }
        }
    } else {
        // OFF-zero-work provider set (reviewed round 4): with diagnostics OFF the
        // census Local is NOT part of the vararg at all — the base array is used
        // by identity, so the production provider scope is unchanged by P1.
        val markdownProvidedValues = slockMarkdownProvidedValuesWith(
            arrayOf(
                LocalTextProvider provides SlockMarkdownTextProvider,
                LocalUriHandler provides uriHandler,
                LocalSlockMarkdownBodyClick provides onClick,
                LocalSlockMarkdownAnnotationRevision provides annotationRevision,
                // task #800: top-level paragraphs wrap when the body opts out of
                // fill (right-aligned bubbles); list/quote paragraphs pin fill.
                LocalSlockMarkdownParagraphFillWidth provides fillWidth
            ),
            LocalSlockMarkdownRowRenderCostSink,
            rowCensusToken
        )
        CompositionLocalProvider(*markdownProvidedValues) {
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
}

/**
 * #367 single-paragraph light body (override-gated via
 * [SlockMarkdownRenderConfig.singleParagraphLightPath], default OFF).
 *
 * Renders the ONE eligible paragraph node directly, skipping the scaffold the
 * full path pays per row: the MarkdownSuccess wrapper Column, the
 * handleElement dispatch loop, the per-document MarkdownState object, and the
 * 12-slot vendor provider scope. The single provider scope below carries
 * exactly the locals the paragraph → MarkdownText → MarkdownBasicText chain
 * reads transitively (verified against the vendored sources):
 * typography/colors/extendedSpans/animations/imageTransformer/imageLinkHandler,
 * annotator + reference link handler + uri handler + text provider, and the
 * Slock locals bodyClick/annotationRevision/paragraphFillWidth/renderTiming.
 * LocalMarkdownPadding/LocalMarkdownDimens/LocalMarkdownComponents are
 * deliberately absent — the chain never reads them (padding is only consumed
 * by handleElement's spacer path, dimens by code/quote/table leaves,
 * components by the dispatch loop this skips). Two further locals the chain
 * reads via SlockMarkdownTextProvider — LocalSlockMarkdownRenderConfig
 * (permalink projection + this very gate) and
 * LocalSlockMessagePermalinkLabelResolver — are intentionally NOT shadowed:
 * they flow in ambiently from the page provider, exactly as on the full path.
 *
 * Parity contracts mirrored from Markdown()/MarkdownSuccess:
 * - the reference link handler is remembered per node (per document), never
 *   hoisted — the impl carries a mutable stored map;
 * - the render timing / row-census forward fires STRICTLY on the valid
 *   finishRender path; with the diagnostics sink absent nodeTiming is null
 *   and the forward is skipped, same as MarkdownSuccess;
 * - the 套娃 Column inside MarkdownParagraph stays (documented placeholder-span
 *   fix) — only the outer MarkdownSuccess Column is skipped;
 * - markdownModifier carries fillMaxWidth in the same fillWidth cases the
 *   full path's outer Column had it; Thread-row callers pass no extra
 *   modifier, so the text-node placement is layout-identical.
 */
@Composable
private fun SlockMarkdownSingleParagraphLightBody(
    content: String,
    paragraphNode: ASTNode,
    colors: DefaultMarkdownColors,
    typography: DefaultMarkdownTypography,
    annotator: MarkdownAnnotator,
    markdownModifier: Modifier,
    fillWidth: Boolean,
    annotationRevision: List<String?>,
    onClick: (() -> Unit)?,
    uriHandler: UriHandler,
    rowCensusToken: SlockMarkdownRowCensusToken?,
) {
    val referenceLinkHandler = remember(paragraphNode) { ReferenceLinkHandlerImpl() }
    val extendedSpans = markdownExtendedSpans()
    val animations = markdownAnimations()
    val imageTransformer = remember { NoOpImageTransformerImpl() }
    val imageLinkHandler = remember { ImageLinkHandlerImpl() }
    val nodeTiming = slockMarkdownRenderTimingOrNull()
    val renderStartMark = TimeSource.Monotonic.markNow()
    slockMarkdownLogSubTrace {
        "phase=markdown.success_light.compose contentHash=${content.hashCode().toString(16)} " +
            "contentLength=${content.length} nodeListSize=1"
    }
    val lightProvidedValues = slockMarkdownProvidedValuesWith(
        arrayOf(
            LocalMarkdownColors provides colors,
            LocalMarkdownTypography provides typography,
            LocalMarkdownExtendedSpans provides extendedSpans,
            LocalMarkdownAnimations provides animations,
            LocalImageTransformer provides imageTransformer,
            LocalImageLinkHandler provides imageLinkHandler,
            LocalMarkdownAnnotator provides annotator,
            LocalReferenceLinkHandler provides referenceLinkHandler,
            LocalTextProvider provides SlockMarkdownTextProvider,
            LocalUriHandler provides uriHandler,
            LocalSlockMarkdownBodyClick provides onClick,
            LocalSlockMarkdownAnnotationRevision provides annotationRevision,
            LocalSlockMarkdownParagraphFillWidth provides fillWidth,
            LocalSlockMarkdownRenderTiming provides nodeTiming
        ),
        LocalSlockMarkdownRowRenderCostSink,
        rowCensusToken
    )
    CompositionLocalProvider(*lightProvidedValues) {
        nodeTiming?.enterNode(paragraphNode.type.name)
        MarkdownParagraph(
            content = content,
            node = paragraphNode,
            modifier = markdownModifier,
            style = typography.paragraph,
        )
        nodeTiming?.exitNode()
    }
    val renderDurationUs = slockMarkdownMonotonicNanosSince(renderStartMark) / 1_000L
    if (renderDurationUs >= SlockMarkdownVendorTraceThresholdUs) {
        slockMarkdownLogSubTrace {
            "phase=markdown.layout.render_nodes durationUs=$renderDurationUs nodeCount=1"
        }
    }
    val renderValid = nodeTiming?.finishRender(renderDurationUs)
    if (renderValid == true) {
        rowCensusToken?.recordValidRender(renderDurationUs)
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
    if (language.equals("mermaid", ignoreCase = true)) {
        SlockMermaidDiagramBlock(
            code = code,
            style = style,
            fallback = {
                SlockMarkdownCodeBlockContent(code, language, style)
            }
        )
    } else {
        SlockMarkdownCodeBlockContent(code, language, style)
    }
}

@Composable
private fun SlockMarkdownCodeBlockContent(
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
        textDecoration = TextDecoration.Underline,
        textDecorationThickness = SLOCK_RICHTEXT_REFERENCE_UNDERLINE_THICKNESS_SP.sp,
        textDecorationOffset = SLOCK_RICHTEXT_REFERENCE_UNDERLINE_OFFSET_SP.sp
    )

private val SlockMarkdownColorsDefault: DefaultMarkdownColors =
    DefaultMarkdownColors(
        text = SlockColor.TextBlack,
        codeText = SlockColor.SurfaceWhite,
        inlineCodeText = SlockColor.TextBlack,
        linkText = SlockMarkdownLinkBlue,
        codeBackground = SlockMarkdownCodeBlockBackground,
        // InlineBoxSpanStyle is the single inline-code chrome painter.
        inlineCodeBackground = Color.Unspecified,
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

// Web's canonical InlineCode uses Space Mono while inheriting the surrounding
// font size. Keep that override scoped to the semantic code range so adjacent
// message prose remains on the resolved Slock UI family (task #816).
internal fun slockMarkdownInlineCodeTextStyle(base: TextStyle): TextStyle =
    SlockRichTextChromeKind.InlineCode.styleToken.applyToTextStyle(base)

private fun String.slockInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

private fun String.slockInlineChipWidthSafetySp(kind: ThreadMarkdownTagKind?): Float =
    when {
        kind == ThreadMarkdownTagKind.Channel ||
            kind == ThreadMarkdownTagKind.ChannelThread ||
            kind == ThreadMarkdownTagKind.DirectMessageThread -> 16f
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
        tag.directMessagePeerName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { peerName ->
            append(":dm=")
            append(peerName)
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
        active && (kind == ThreadMarkdownTagKind.ChannelThread || kind == ThreadMarkdownTagKind.DirectMessageThread) ->
            SlockRichTextChromeKind.Thread
        active -> SlockRichTextChromeKind.Active
        kind == ThreadMarkdownTagKind.UserMention && highlighted -> SlockRichTextChromeKind.SelfMention
        kind == ThreadMarkdownTagKind.UserMention -> SlockRichTextChromeKind.OrdinaryMention
        kind == ThreadMarkdownTagKind.DirectMessageThread -> SlockRichTextChromeKind.Thread
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
 * semantic tokens lower through this adapter while render backends migrate
 * away from understanding Slock kinds.
 */
internal fun slockMarkdownInlineBoxStyle(kind: SlockRichTextChromeKind): InlineBoxSpanStyle =
    requireNotNull(kind.styleToken.toInlineBoxSpanStyle())

internal fun slockMarkdownInlineCodeEdgeStyle(
    leading: Boolean,
    trailing: Boolean = !leading,
): InlineBoxSpanStyle =
    InlineBoxSpanStyle(
        backgroundColor = SlockRichTextChromeKind.InlineCode.styleToken.chipFill.toSlockMarkdownColor(),
        borderColor = Color.Transparent,
        borderWidth = 0.dp,
        paddingStart = if (leading) 4.dp else 0.dp,
        paddingEnd = if (trailing) 4.dp else 0.dp,
        paddingTop = SlockRichTextInlineCodeVerticalPaddingDp.dp,
        paddingBottom = SlockRichTextInlineCodeVerticalPaddingDp.dp,
        marginStart = 0.dp,
        marginEnd = 0.dp,
        cornerRadius = 0.dp,
    )

private fun SlockRichTextChromeStyleToken.toInlineBoxSpanStyle(): InlineBoxSpanStyle? {
    if (id == SlockRichTextChromeStyleTokens.OrdinaryMention.id) return null
    val fill = chipFill ?: return null
    val isInlineCode = id == SlockRichTextChromeStyleTokens.InlineCode.id
    val verticalPadding =
        if (isInlineCode) SlockRichTextInlineCodeVerticalPaddingDp
        else SlockRichTextInlineBoxVerticalPaddingDp
    return InlineBoxSpanStyle(
        backgroundColor = fill.toSlockMarkdownColor(),
        borderColor = if (isInlineCode) Color.Transparent else SlockColor.BorderBlack,
        borderWidth = if (isInlineCode) 0.dp else SlockRichTextInlineBoxBorderWidthDp.dp,
        paddingStart = 4.dp,
        paddingEnd = 4.dp,
        paddingTop = verticalPadding.dp,
        paddingBottom = verticalPadding.dp,
        marginStart = if (isInlineCode) 0.dp else 2.dp,
        marginEnd = if (isInlineCode) 0.dp else 2.dp,
        cornerRadius = 0.dp
    )
}

private const val SlockRichTextInlineBoxBorderWidthDp = 1f
private const val SlockRichTextInlineCodeVerticalPaddingDp = 1f
private const val SlockRichTextInlineBoxVerticalPaddingDp = 2f

/**
 * Top-level Markdown nodes do not reserve space for inline-box paint outside
 * the text line. A following heading must clear the box's bottom padding plus
 * border; this keeps the document opt-in tied to the actual chrome geometry.
 */
internal fun slockMarkdownSectionHeadingGapClearsInlineCodeChrome(gap: Dp): Boolean =
    slockMarkdownInlineBoxStyle(SlockRichTextChromeKind.InlineCode).let { style ->
        gap.value >= style.paddingBottom.value + style.borderWidth.value
    }

private fun SlockRichTextColorToken?.toSlockMarkdownColor(): Color =
    requireNotNull(this) { "Slock richtext color token is required for this chrome path" }
        .toSlockMarkdownColorOrUnspecified()

private fun SlockRichTextColorToken?.toSlockMarkdownColorOrUnspecified(): Color =
    when (this) {
        SlockRichTextColorToken.TextBlack -> SlockColor.TextBlack
        SlockRichTextColorToken.Black5 -> SlockColor.TextBlack.copy(alpha = 0.05f)
        SlockRichTextColorToken.BrandYellow -> SlockColor.BrandYellow
        // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1045
        SlockRichTextColorToken.BrandYellow40 -> SlockColor.BrandYellow.copy(alpha = 0.4f)
        // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1045
        SlockRichTextColorToken.AccentPink30 -> SlockColor.AccentPink.copy(alpha = 0.3f)
        // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1045
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
