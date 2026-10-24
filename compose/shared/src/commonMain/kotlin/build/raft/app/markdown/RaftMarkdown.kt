package build.raft.app.markdown

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import build.raft.app.RaftTextSize
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMessagePermalinkTarget
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppThemeState
import com.tencent.kuiklybase.markdown.compose.elements.material.MarkdownBasicText
import build.raft.app.raftArgbHex
import build.raft.app.raftMultiWeightFontFamily
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import build.raft.app.model.TaskStatus
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftType
import build.raft.app.withRaftUserContentLocale
import build.raft.app.ReplyCacheProvenance
import build.raft.app.data.THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.toRaftMessagePermalinkTarget
import build.raft.app.components.RaftCheckMarker
import build.raft.app.components.RaftCheckMarkerTone
import build.raft.app.resources.MR
import build.raft.app.raftFontFamilyForWeight
import build.raft.app.raftSpanStyleForWeight
import build.raft.app.raftMonoFamilyForWeight
import build.raft.app.withResolvedRaftFontFamily
import build.raft.app.withRaftFontWeight
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text as KuiklyText
import androidx.compose.ui.Alignment
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import build.raft.app.compat.pager.Pager
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownRenderTiming
import com.tencent.kuiklybase.markdown.compose.LocalTextProvider
import com.tencent.kuiklybase.markdown.compose.RaftMarkdownVendorTraceThresholdUs
import com.tencent.kuiklybase.markdown.compose.raftMarkdownLogSubTrace
import com.tencent.kuiklybase.markdown.compose.raftMarkdownMonotonicNanosSince
import com.tencent.kuiklybase.markdown.compose.raftMarkdownRenderTimingOrNull
import com.tencent.kuiklybase.markdown.compose.components.markdownComponents
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownColors
import com.tencent.kuiklybase.markdown.model.DefaultMarkdownTypography
import com.tencent.kuiklybase.markdown.model.ImageLinkHandlerImpl
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownAnnotationRevision
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownTableCellPadding
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownTableClosedGrid
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownParagraphFillWidth
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownRowRenderCostSink
import com.tencent.kuiklybase.markdown.compose.RaftMarkdownNodeTrace
import com.tencent.kuiklybase.markdown.compose.RaftMarkdownNodeTraceSink
import com.tencent.kuiklybase.markdown.compose.RaftMarkdownSubTrace
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
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownBlockQuote
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownListIndentEm
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownListItems
import com.tencent.kuiklybase.markdown.compose.elements.listDepth
import androidx.compose.ui.platform.LocalDensity
import org.intellij.markdown.ast.findChildOfType
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
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
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

internal typealias RaftMessagePermalinkLabelResolver =
    (RaftMessagePermalinkTarget) -> String?

internal val LocalRaftMessagePermalinkLabelResolver =
    compositionLocalOf<RaftMessagePermalinkLabelResolver> { { null } }

internal data class RaftMarkdownRenderConfig(
    val renderMessagePermalinksAsTag: Boolean = false,
    // #367 light path (diagnostic override, default OFF): single-paragraph
    // text-only bodies may skip the MarkdownSuccess scaffold — see
    // raftMarkdownSingleParagraphLightNodeOrNull.
    val singleParagraphLightPath: Boolean = false,
)

internal val LocalRaftMarkdownRenderConfig =
    compositionLocalOf { RaftMarkdownRenderConfig() }

enum class ThreadMarkdownTagKind {
    ChannelThread,
    DirectMessageThread,
    DirectMessage,
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
    DirectMessage,
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
internal class RaftMarkdownInlineTokenPlanner(
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
        isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultRaftMarkdownTagValidity,
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

internal object RaftMarkdownInlineTokenRegistry {
    private val planner = RaftMarkdownInlineTokenPlanner()

    fun plan(
        content: String,
        highlightedMentions: Set<String> = emptySet(),
        isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultRaftMarkdownTagValidity,
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

fun defaultRaftMarkdownTagValidity(tag: ThreadMarkdownTag): Boolean =
    tag.kind == ThreadMarkdownTagKind.Task

private fun ThreadMarkdownTagKind.toMarkdownInlineTokenKind(): MarkdownInlineTokenKind =
    when (this) {
        ThreadMarkdownTagKind.ChannelThread -> MarkdownInlineTokenKind.ChannelThread
        ThreadMarkdownTagKind.DirectMessageThread -> MarkdownInlineTokenKind.DirectMessageThread
        ThreadMarkdownTagKind.DirectMessage -> MarkdownInlineTokenKind.DirectMessage
        ThreadMarkdownTagKind.Channel -> MarkdownInlineTokenKind.Channel
        ThreadMarkdownTagKind.Task -> MarkdownInlineTokenKind.Task
        ThreadMarkdownTagKind.UserMention -> MarkdownInlineTokenKind.UserMention
    }

data class RaftMarkdownPreviewSample(
    val title: String,
    val body: String,
    val description: String
)

object RaftMarkdownPreviewSamples {
    val inlineTags =
        RaftMarkdownPreviewSample(
            title = "Inline tags",
            description = "Channels, task references, and user mentions should render as clickable inline refs.",
            body = "Please review #22 with @alice in #android-dev, then compare #382-#383."
        )

    val markdownBlocks =
        RaftMarkdownPreviewSample(
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
        RaftMarkdownPreviewSample(
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
        RaftMarkdownPreviewSample(
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

    val literal = threadEscapedLineBreakLiteralRanges()
    if (literal.isEmpty()) {
        return replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\r")
    }
    // Task #775: `\neq`, `\nu` in a formula and `\n` in code are source, not escaped
    // line breaks; decode only outside them.
    return buildString(length) {
        var cursor = 0
        for (range in literal) {
            append(this@normalizeThreadMarkdownBody.substring(cursor, range.first).decodeThreadEscapedLineBreaks())
            append(this@normalizeThreadMarkdownBody, range.first, range.last + 1)
            cursor = range.last + 1
        }
        append(this@normalizeThreadMarkdownBody.substring(cursor).decodeThreadEscapedLineBreaks())
    }
}

private fun String.decodeThreadEscapedLineBreaks(): String =
    replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\r")

/**
 * Source ranges of the still-escaped body that are literal: code spans, and inline math the
 * Markdown parser finds (same rules as rendering). A `$$` span whose text starts with an
 * escaped line break is left out: once decoded it is a `$$` block, whose lines are decoded.
 */
private fun String.threadEscapedLineBreakLiteralRanges(): List<IntRange> {
    if (!contains('$') && !contains('`')) return emptyList()
    val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(this)
    val ranges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_SPAN -> {
                ranges += node.startOffset until node.endOffset
                return
            }
            GFMElementTypes.INLINE_MATH -> {
                val opening = node.children.firstOrNull()
                val single = opening != null && opening.endOffset - opening.startOffset == 1
                if (single || !startsWith("\\n", opening?.endOffset ?: node.startOffset) &&
                    !startsWith("\\r", opening?.endOffset ?: node.startOffset)
                ) {
                    ranges += node.startOffset until node.endOffset
                }
                return
            }
        }
        node.children.forEach(::visit)
    }
    visit(tree)
    return ranges.sortedBy { it.first }
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
    val inlineContentIdPrefix = inlineContentScope.toRaftMarkdownInlineContentIdPrefix()
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

internal fun String?.toRaftMarkdownInlineContentIdPrefix(): String =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { scope -> "slock-$scope" }
        ?: "slock"

internal const val DEBUG_RAFT_MARKDOWN_PERF_LOG = false
private const val RaftMarkdownPerformanceSlowThresholdMs = 8L


// t6-a: app-owned sink for the vendor per-node timing; same println channel as
// the other SlockMarkdownSpike lines so existing collection tooling applies.
internal object RaftMarkdownNodeSpikeSink : RaftMarkdownNodeTraceSink {
    override fun emit(line: String) {
        println(line)
    }
}

// Pure selection (reviewed stale-sink tooth): true -> the spike sink,
// false -> null; assigning the result on every RaftMarkdownBody composition
// guarantees a disabled gate clears any previously installed sink.
internal fun raftMarkdownNodeSinkFor(
    diagnosticsEnabled: Boolean,
    suppressedForOverheadTest: Boolean = RaftMarkdownNodeTrace.suppressedForOverheadTest,
): RaftMarkdownNodeTraceSink? =
    if (diagnosticsEnabled && !suppressedForOverheadTest) RaftMarkdownNodeSpikeSink else null

internal fun shouldLogRaftMarkdownPerformance(durationMs: Long): Boolean =
    DEBUG_RAFT_MARKDOWN_PERF_LOG || durationMs >= RaftMarkdownPerformanceSlowThresholdMs

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
        isDirectMessageReference() ->
            ThreadMarkdownTag(
                label = this,
                kind = ThreadMarkdownTagKind.DirectMessage,
                directMessagePeerName = substring(4)
            )
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

/** `dm:@peer` / `dm:@peer~agent` / `dm:@peer~human` with no thread short id (Web `createRaftDmRefRegex`). */
private fun String.isDirectMessageReference(): Boolean {
    if (!startsWith("dm:@", ignoreCase = true) || length <= 4 || indexOf(':', 4) >= 0) return false
    val peerName = parseRaftDirectMessagePeerRef(substring(4))?.peerName ?: return false
    return peerName.isNotEmpty() &&
        peerName.first().isAsciiWordCharacter() &&
        peerName.all { it.isRaftDirectMessagePeerPart() }
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

internal fun raftMarkdownAnnotator(
    content: String,
    highlightedMentions: Set<String> = emptySet(),
    isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultRaftMarkdownTagValidity,
    inlineContentScope: String? = null,
    activeTagKey: String? = null,
    onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    softLineBreaksAsNewLines: Boolean = true,
    taskStatuses: Map<Int, TaskStatus>? = null,
    tokenPlan: MarkdownInlineTokenPlan =
        RaftMarkdownInlineTokenRegistry.plan(
            content,
            highlightedMentions,
            isTagValid,
            resolveTagLabel = resolveTagLabel,
            inlineContentScope = inlineContentScope
        )
): MarkdownAnnotator {
    val document =
        buildRaftRichTextDocumentFromMarkdownPlan(
            source = content,
            content = content,
            tokenPlan = tokenPlan,
            activeTagKey = activeTagKey
        )
    return raftMarkdownAnnotator(
        document = document,
        onOpenAction = { action -> onOpenTag(action.toThreadMarkdownTag()) },
        resolveActionLabel =
            resolveTagLabel?.let { resolver ->
                { action: RaftRichTextAction -> resolver(action.toThreadMarkdownTag()) }
            },
        onDiagnostics = onDiagnostics,
        softLineBreaksAsNewLines = softLineBreaksAsNewLines,
        taskStatuses = { taskStatuses }
    )
}

internal fun raftMarkdownAnnotator(
    document: RaftRichTextDocument,
    onOpenAction: (RaftRichTextAction) -> Unit = {},
    resolveActionLabel: ((RaftRichTextAction) -> String?)? = null,
    onDiagnostics: ((Int, Int, Long) -> Unit)? = null,
    capabilities: RaftRichTextCapabilities = RaftMarkdownRendererRichTextCapabilities,
    softLineBreaksAsNewLines: Boolean = true,
    taskStatuses: () -> Map<Int, TaskStatus>? = { null },
    mentionReadStates: () -> RaftMarkdownMentionReadStates? = { null },
    mentionReadFontSize: TextUnit = RaftMarkdownTagFontSize
): MarkdownAnnotator {
    val diagnosticsStartedAt = onDiagnostics?.let { TimeSource.Monotonic.markNow() }
    val rendererInput = RaftRichTextRendererInput(document, capabilities, taskStatuses, mentionReadStates, mentionReadFontSize)
    if (diagnosticsStartedAt != null) {
        onDiagnostics.invoke(
            rendererInput.raftRefTokenCount,
            rendererInput.inlineCodeTokenCount,
            diagnosticsStartedAt.elapsedNow().inWholeMilliseconds
        )
    }
    return markdownAnnotator(
        annotateSequence = { markdownContent, children, startIndex, _ ->
            consumeRaftRichTextSplitTagSequence(
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
            appendRaftRichTextSegment(
                text = nodeText,
                documentOffset = nodeStart,
                rendererInput = rendererInput,
                onOpenAction = onOpenAction,
                resolveActionLabel = resolveActionLabel,
                allowLocalFallback = child.type != RaftMarkdownTokenTypes.REFERENCE,
            )
        },
        inlineContent = {
            rendererInput.activeActionSpinnerInlineContent() +
                rendererInput.taskChipInlineContent() +
                rendererInput.mentionReadInlineContent()
        },
        config = markdownAnnotatorConfig(eolAsNewLine = softLineBreaksAsNewLines)
    )
}

private data class RaftRichTextRendererInput(
    val document: RaftRichTextDocument,
    val capabilities: RaftRichTextCapabilities,
    val taskStatuses: () -> Map<Int, TaskStatus>? = { null },
    val mentionReadStates: () -> RaftMarkdownMentionReadStates? = { null },
    val mentionReadFontSize: TextUnit = RaftMarkdownTagFontSize
) {
    val raftRefTokens: List<RaftRichTextInlineToken> =
        document.inlineTokens
            .filter { token -> token.kind == RaftRichTextInlineKind.RaftRef && token.actionId != null }
            .sortedBy { token -> token.range.start }
    private val actionsById: Map<String, RaftRichTextAction> =
        document.actions.associateBy { action -> action.id }

    val raftRefTokenCount: Int = raftRefTokens.size
    val inlineCodeTokenCount: Int =
        document.inlineTokens.count { token -> token.kind == RaftRichTextInlineKind.InlineCode }

    fun activeActionSpinnerInlineContent(): Map<String, InlineTextContent> {
        val activeTokens = raftRefTokens.filter { it.active && it.trailingIndicator.isEmpty() && it.actionId != null }
        if (activeTokens.isEmpty()) return emptyMap()
        return activeTokens.associate { token ->
            val actionId = requireNotNull(token.actionId)
            raftMarkdownActionSpinnerInlineId(actionId) to raftMarkdownActionSpinnerInlineTextContent()
        }
    }

    fun actionFor(token: RaftRichTextInlineToken): RaftRichTextAction? =
        token.actionId?.let(actionsById::get)

    private val hasTaskRefs: Boolean =
        document.actions.any { action -> action.refKind == RaftRichTextRefKind.Task }

    /** Status-coloured task chip styles are in use (web parity) only when the surface has a task source. */
    fun taskStatusesOrNull(): Map<Int, TaskStatus>? =
        if (hasTaskRefs) taskStatuses() else null

    fun taskChipInlineContent(): Map<String, InlineTextContent> {
        if (taskStatusesOrNull() == null) return emptyMap()
        return raftMarkdownTaskChipInlineContent(dark = RaftAppThemeState.isDark)
    }

    private val hasMentionRefs: Boolean =
        document.actions.any { action -> action.refKind == RaftRichTextRefKind.UserMention }

    /** read_receipts_v0: the @mentioned agent's read badge, when the message row provides states. */
    fun mentionReadStateFor(action: RaftRichTextAction): Boolean? =
        if (action.refKind == RaftRichTextRefKind.UserMention) mentionReadStates()?.readStateFor(action.label) else null

    fun mentionReadInlineContent(): Map<String, InlineTextContent> =
        if (hasMentionRefs && mentionReadStates() != null) raftMarkdownMentionReadInlineContent(mentionReadFontSize) else emptyMap()

    fun raftRefTokensStartingInRange(start: Int, endExclusive: Int): List<RaftRichTextInlineToken> =
        raftRefTokens.filter { token ->
            token.range.start >= start && token.range.start < endExclusive && token.range.endExclusive <= endExclusive
        }

    fun raftRefTokensStartingInTextNode(start: Int, endExclusive: Int): List<RaftRichTextInlineToken> =
        raftRefTokens.filter { token ->
            token.range.start >= start && token.range.start < endExclusive
        }

    fun raftRefTokensMatchingText(text: String, minStart: Int): List<RaftRichTextInlineToken> {
        val matches =
            raftRefTokens.filter { token ->
                token.range.start >= minStart && token.sourceText.isNotEmpty() && text.contains(token.sourceText)
            }
        return matches.ifEmpty {
            raftRefTokens.filter { token -> token.sourceText.isNotEmpty() && text.contains(token.sourceText) }
        }
    }
}

internal fun raftMarkdownActionSpinnerInlineId(actionId: String): String =
    "slock-markdown-action-spinner:$actionId"

internal fun raftMarkdownActionSpinnerInlineTextContent(): InlineTextContent =
    InlineTextContent(
        placeholder =
            Placeholder(
                width = 12.sp,
                height = 12.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
    ) {
        RaftLucideIcon(
            name = RaftLucideIconName.LoaderCircle,
            modifier = Modifier.size(12.dp),
            color = RaftColor.TextBlackArgb,
            strokeWidth = 2f,
            spinning = true,
        )
    }

private data class RaftRichTextSplitTagStart(
    val token: RaftRichTextInlineToken,
    val relativeStart: Int,
    val consumedPrefix: String
)

private fun AnnotatedString.Builder.consumeRaftRichTextSplitTagSequence(
    markdownContent: String,
    children: List<ASTNode>,
    startIndex: Int,
    rendererInput: RaftRichTextRendererInput,
    onOpenAction: (RaftRichTextAction) -> Unit,
    resolveActionLabel: ((RaftRichTextAction) -> String?)?
): Int {
    val child = children.getOrNull(startIndex) ?: return 0
    if (child.parent?.type == MarkdownElementTypes.CODE_SPAN || child.type != MarkdownTokenTypes.TEXT) {
        return 0
    }
    val nodeText = child.getTextInNode(markdownContent).toString()
    val nodeStart = child.startOffset
    val splitStart = nodeText.raftRichTextSplitTagStart(nodeStart, rendererInput) ?: return 0
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
        appendRaftRichTextSegmentOrText(
            text = nodeText.substring(0, prefixEnd),
            documentOffset = nodeStart,
            rendererInput = rendererInput,
            onOpenAction = onOpenAction,
            resolveActionLabel = resolveActionLabel
        )
    }
    appendRaftRichTextToken(
        token = splitStart.token,
        rendererInput = rendererInput,
        onOpenAction = onOpenAction,
        resolveActionLabel = resolveActionLabel
    )
    tailText?.let { tail ->
        appendRaftRichTextSegmentOrText(
            text = tail,
            documentOffset = tailDocumentOffset,
            rendererInput = rendererInput,
            onOpenAction = onOpenAction,
            resolveActionLabel = resolveActionLabel
        )
    }
    return consumedCount
}

private fun AnnotatedString.Builder.appendRaftRichTextSegmentOrText(
    text: String,
    documentOffset: Int,
    rendererInput: RaftRichTextRendererInput,
    onOpenAction: (RaftRichTextAction) -> Unit,
    resolveActionLabel: ((RaftRichTextAction) -> String?)?
) {
    if (!appendRaftRichTextSegment(
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

private fun String.raftRichTextSplitTagStart(
    nodeStart: Int,
    rendererInput: RaftRichTextRendererInput
): RaftRichTextSplitTagStart? {
    val nodeEndExclusive = nodeStart + length
    return rendererInput
        .raftRefTokensStartingInTextNode(nodeStart, nodeEndExclusive)
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
                RaftRichTextSplitTagStart(
                    token = token,
                    relativeStart = relativeStart,
                    consumedPrefix = available
                )
            } else {
                null
            }
        }
}

private fun AnnotatedString.Builder.appendRaftRichTextSegment(
    text: String,
    documentOffset: Int,
    rendererInput: RaftRichTextRendererInput,
    onOpenAction: (RaftRichTextAction) -> Unit,
    resolveActionLabel: ((RaftRichTextAction) -> String?)?,
    allowLocalFallback: Boolean = true,
): Boolean {
    val documentTokens = rendererInput.raftRefTokensStartingInRange(documentOffset, documentOffset + text.length)
    val localTokens =
        if (documentTokens.isEmpty() && allowLocalFallback) {
            // Some markdown-renderer paths, including list-item paragraphs,
            // expose child offsets in a local node coordinate space. Match
            // already-built IR tokens back to that text instead of reparsing
            // Slock refs in the renderer adapter.
            rendererInput.raftRefTokensMatchingText(text, minStart = documentOffset)
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
        appendRaftRichTextToken(
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

private fun AnnotatedString.Builder.appendRaftRichTextToken(
    token: RaftRichTextInlineToken,
    rendererInput: RaftRichTextRendererInput,
    onOpenAction: (RaftRichTextAction) -> Unit,
    resolveActionLabel: ((RaftRichTextAction) -> String?)?
) {
    val action = rendererInput.actionFor(token) ?: return append(token.text)
    val label = resolveRaftRichTextTokenLabel(token, resolveActionLabel?.invoke(action))
    if (action.refKind == RaftRichTextRefKind.Task) {
        val taskStatuses = rendererInput.taskStatusesOrNull()
        if (taskStatuses != null) {
            appendRaftRichTextTaskChipToken(token, action, label, taskStatuses, onOpenAction)
            return
        }
    }
    val boxStyle = raftMarkdownRichTextTagInlineBoxStyle(token.chrome)
    withRaftInlineChipGaps(boxStyle) {
        pushStringAnnotation(
            tag = RaftMarkdownTagAnnotationTag,
            annotation = action.id
        )
        withLink(
            LinkAnnotation.Clickable(
                tag = action.id,
                styles = raftMarkdownRichTextTagLinkStyles(token.chrome),
                linkInteractionListener = LinkInteractionListener { onOpenAction(action) }
            )
        ) {
            // The chip box is an annotation painted by raftInlineDecorations.
            withRaftInlineBox(boxStyle) {
                val fontSizeStyle = raftMarkdownRichTextTagFontSizeStyle(token.chrome)
                if (fontSizeStyle == null) append(label) else withStyle(fontSizeStyle) { append(label) }
            }
            if (token.active && token.trailingIndicator.isEmpty()) {
                append(" ")
                appendInlineContent(raftMarkdownActionSpinnerInlineId(action.id), "\uFFFC")
            }
        }
        pop()
    }
    rendererInput.mentionReadStateFor(action)?.let { read -> appendRaftMarkdownMentionReadBadge(read) }
}

/**
 * Task ref on a surface with a task source: raft-ui inline `TaskChip` in the task's status colours,
 * or the neutral muted badge for an unknown task (web MessageItem `renderTaskBadge`).
 */
private fun AnnotatedString.Builder.appendRaftRichTextTaskChipToken(
    token: RaftRichTextInlineToken,
    action: RaftRichTextAction,
    label: String,
    taskStatuses: Map<Int, TaskStatus>,
    onOpenAction: (RaftRichTextAction) -> Unit,
) {
    val style = raftMarkdownTaskChipStyle(taskNumberFromRefLabel(action.label)?.let(taskStatuses::get))
    withRaftInlineChipGaps(style.box) {
        pushStringAnnotation(
            tag = RaftMarkdownTagAnnotationTag,
            annotation = action.id
        )
        withLink(
            LinkAnnotation.Clickable(
                tag = action.id,
                styles = style.linkStyles(),
                linkInteractionListener = LinkInteractionListener { onOpenAction(action) }
            )
        ) {
            appendRaftMarkdownTaskChip(style, label) {
                if (token.active && token.trailingIndicator.isEmpty()) {
                    append(" ")
                    appendInlineContent(raftMarkdownActionSpinnerInlineId(action.id), "\uFFFC")
                }
            }
        }
        pop()
    }
}

internal fun resolveRaftRichTextTokenLabel(
    token: RaftRichTextInlineToken,
    resolvedLabel: String?
): String =
    resolvedLabel
        ?.takeIf { it.isNotBlank() }
        ?.plus(token.trailingIndicator)
        ?: token.text

private fun RaftRichTextAction.toThreadMarkdownTag(): ThreadMarkdownTag =
    ThreadMarkdownTag(
        label = label,
        kind =
            when (refKind) {
                RaftRichTextRefKind.Channel -> ThreadMarkdownTagKind.Channel
                RaftRichTextRefKind.Thread -> ThreadMarkdownTagKind.ChannelThread
                RaftRichTextRefKind.DirectMessageThread -> ThreadMarkdownTagKind.DirectMessageThread
                RaftRichTextRefKind.DirectMessage -> ThreadMarkdownTagKind.DirectMessage
                RaftRichTextRefKind.Task -> ThreadMarkdownTagKind.Task
                RaftRichTextRefKind.UserMention -> ThreadMarkdownTagKind.UserMention
            },
        channelName = channelName,
        threadShortId = threadShortId,
        directMessagePeerName = directMessagePeerName
    )

private object RaftMarkdownTextProvider : TextProvider {
    @Composable
    override fun Text(
        text: String,
        modifier: Modifier,
        color: Color,
        fontSize: TextUnit,
        fontStyle: FontStyle?,
        fontWeight: FontWeight?,
        fontFamily: androidx.compose.ui.text.font.FontFamily?,
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
        val resolvedStyle = style.withResolvedRaftFontFamily()
        val markdownOverflow = raftMarkdownContentOverflow(overflow)
        val bodyClick = LocalRaftMarkdownBodyClick.current
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
                text = AnnotatedString(text).withRaftMarkdownBodyClick(bodyClick),
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
        fontFamily: androidx.compose.ui.text.font.FontFamily?,
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
        val resolvedStyle = style.withResolvedRaftFontFamily()
        val markdownOverflow = raftMarkdownContentOverflow(overflow)
        val bodyClick = LocalRaftMarkdownBodyClick.current
        val permalinkLabelResolver = LocalRaftMessagePermalinkLabelResolver.current
        val renderMessagePermalinksAsTag =
            LocalRaftMarkdownRenderConfig.current.renderMessagePermalinksAsTag
        val permalinkUriHandler = LocalUriHandler.current
        val permalinkCoroutineScope = rememberCoroutineScope()
        // The permalink chip box and link styles carry theme colours, so the theme is a key.
        val permalinkDark = RaftAppThemeState.isDark
        val permalinkContent =
            remember(
                text,
                permalinkLabelResolver,
                permalinkUriHandler,
                renderMessagePermalinksAsTag,
                permalinkDark,
            ) {
                text.withRaftMessagePermalinkRenderConfig(
                    config =
                        RaftMarkdownRenderConfig(
                            renderMessagePermalinksAsTag = renderMessagePermalinksAsTag,
                        ),
                    resolveLabel = permalinkLabelResolver,
                ) { url ->
                    permalinkCoroutineScope.launch { permalinkUriHandler.openUri(url) }
                }
            }
        val styledText = remember(permalinkContent.text, resolvedStyle, permalinkDark) {
            permalinkContent.text.withRaftMarkdownSpanFonts(resolvedStyle).withRaftMarkdownLinkUnderlines()
        }
        val resolvedText =
            remember(styledText, bodyClick) {
                styledText.withRaftMarkdownBodyClick(bodyClick)
            }
        val resolvedInlineContent =
            remember(resolvedText, inlineContent, permalinkContent) {
                resolvedText.filteredRaftMarkdownInlineContent(
                    inlineContent + permalinkContent.inlineContent +
                        raftInlineGapInlineContent(resolvedText.raftMarkdownInlineContentIds())
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
                    "inlineIds=${resolvedText.raftMarkdownInlineContentIds().size}"
            }
        )
        var lastLayoutSignature by remember { mutableStateOf<RaftMarkdownTextLayoutSignature?>(null) }
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
                        RaftMarkdownTextLayoutSignature(
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
        val inlineDecorations =
            remember(resolvedText, resolvedStyle.fontSize, fontSize) {
                resolvedText.raftInlineDecorations(
                    if (fontSize.isSpecified) fontSize else resolvedStyle.fontSize
                )
            }
        var decorationLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        KuiklyText(
            text = resolvedText,
            modifier = modifier.raftInlineDecorations(inlineDecorations) { decorationLayout },
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
            onTextLayout = { result ->
                if (!inlineDecorations.isEmpty) decorationLayout = result
                resolvedOnTextLayout(result)
            },
            style = resolvedStyle
        )
    }
}

internal fun AnnotatedString.withRaftMarkdownBodyClick(onClick: (() -> Unit)?): AnnotatedString {
    if (onClick == null || isEmpty()) return this
    val occupiedRanges =
        getLinkAnnotations(0, length)
            .map { it.start until it.end }
            .sortedBy { it.first }
    val openBody =
        LinkAnnotation.Clickable(
            tag = RaftMarkdownBodyClickTag,
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

internal fun raftMarkdownContentOverflow(overflow: TextOverflow): TextOverflow =
    if (overflow == TextOverflow.Ellipsis) TextOverflow.Clip else overflow

private data class RaftMarkdownTextLayoutSignature(
    val size: IntSize,
    val placeholderRects: List<Rect?>
)

private fun AnnotatedString.hasRaftMarkdownInlineContentPlaceholders(): Boolean =
    hasStringAnnotations(RaftMarkdownInlineContentTag, 0, text.length)

internal fun AnnotatedString.raftMarkdownInlineContentIds(): Set<String> =
    getStringAnnotations(RaftMarkdownInlineContentTag, 0, text.length)
        .mapTo(mutableSetOf()) { it.item }

internal fun AnnotatedString.filteredRaftMarkdownInlineContent(
    inlineContent: Map<String, InlineTextContent>
): Map<String, InlineTextContent> {
    if (inlineContent.isEmpty() || !hasRaftMarkdownInlineContentPlaceholders()) {
        return emptyMap()
    }
    val ids = raftMarkdownInlineContentIds()
    if (ids.isEmpty()) return emptyMap()
    return if (ids.size == inlineContent.size && inlineContent.keys.containsAll(ids)) {
        inlineContent
    } else {
        inlineContent.filterKeys { it in ids }
    }
}

private const val RaftMarkdownInlineContentTag = "androidx.compose.foundation.text.inlineContent"
internal const val RaftMarkdownTagAnnotationTag = "raft.build.markdown.tag"
// Chrome-related fields are platform-derived (task #441): declaring
// consumesChromeTokens/hasDrawAdapter=true in commonMain overclaimed platforms
// whose native renderer doesn't draw the chip chrome (it was hardcoded true
// while one renderer had no chip at all). Document/action/style-token consumption is
// shared-side behavior and genuinely platform-independent.
internal val RaftMarkdownRendererRichTextCapabilities: RaftRichTextCapabilities
    get() {
        val platformCaps = raftRichTextPlatformCaps()
        val chromeRenders = platformCaps.inlineCodeChrome && platformCaps.raftRefChrome
        return RaftRichTextCapabilities(
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

internal fun AnnotatedString.withRaftMarkdownSpanFonts(baseStyle: TextStyle): AnnotatedString {
    if (spanStyles.isEmpty()) return this

    val resolvedBase = baseStyle.withResolvedRaftFontFamily()
    val inlineCodeRanges =
        getStringAnnotations(
            RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
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
                    raftMonoFamilyForWeight(explicitWeight)
                shouldNormalizeFamily ->
                    explicitWeight?.let(::raftFontFamilyForWeight) ?: resolvedBase.fontFamily
                else -> range.item.fontFamily
            }
        if (shouldNormalizeFamily &&
            (range.item.fontFamily == FontFamily.Monospace || family != resolvedBase.fontFamily)
        ) {
            // Space Grotesk spans carry the weight their face encodes (when the platform keeps
            // it) so CJK fallback glyphs in **bold** render bold too; mono stays Regular-weighted.
            val spanWeight =
                when {
                    range.item.fontFamily == FontFamily.Monospace -> FontWeight.Normal
                    explicitWeight != null ->
                        build.raft.app.raftSpanStyleForWeight(explicitWeight).fontWeight ?: FontWeight.Normal
                    else -> resolvedBase.fontWeight ?: FontWeight.Normal
                }
            builder.addStyle(
                SpanStyle(
                    fontFamily = family,
                    fontWeight = spanWeight
                ),
                range.start,
                range.end
            )
        }
    }
    inlineCodeRanges.forEach { range ->
        builder.addStyle(
            SpanStyle(
                fontFamily = raftMonoFamilyForWeight(FontWeight.Normal),
                fontWeight = FontWeight.Normal,
                // raft-ui InlineCode appearance="message": 0.875em of the sentence, so it
                // follows the message font-size setting and headings.
                fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em,
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
 * ordinary text/link data plus a [RaftInlineBoxStyle] annotation.
 */
internal fun AnnotatedString.withRaftMessagePermalinkChips(
    resolveLabel: RaftMessagePermalinkLabelResolver = { null }
): AnnotatedString = withRaftMessagePermalinkChipContent(resolveLabel).text

internal data class RaftMessagePermalinkChipContent(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

internal fun AnnotatedString.withRaftMessagePermalinkRenderConfig(
    config: RaftMarkdownRenderConfig = RaftMarkdownRenderConfig(),
    resolveLabel: RaftMessagePermalinkLabelResolver = { null },
    openUri: (String) -> Unit = {},
): RaftMessagePermalinkChipContent =
    if (config.renderMessagePermalinksAsTag) {
        withRaftMessagePermalinkChipContent(resolveLabel, openUri)
    } else {
        RaftMessagePermalinkChipContent(
            text = withoutLinksInsideRaftInlineCode(),
            inlineContent = emptyMap(),
        )
    }

private fun AnnotatedString.withRaftMessagePermalinkChipContent(
    resolveLabel: RaftMessagePermalinkLabelResolver = { null },
    openUri: (String) -> Unit = {},
): RaftMessagePermalinkChipContent {
    val source = withoutLinksInsideRaftInlineCode()
    val permalinkLinks =
        source.getLinkAnnotations(0, source.length)
            .mapNotNull { range ->
                val link = range.item as? LinkAnnotation.Url ?: return@mapNotNull null
                val target = link.url.toRaftMessagePermalinkTarget() ?: return@mapNotNull null
                Triple(range, link, target)
            }
            .sortedBy { (range, _, _) -> range.start }
    if (permalinkLinks.isEmpty()) {
        return RaftMessagePermalinkChipContent(source, emptyMap())
    }

    val builder = AnnotatedString.Builder(source.length)
    val inlineContent = linkedMapOf<String, InlineTextContent>()
    var cursor = 0
    permalinkLinks.forEach { (range, link, target) ->
        if (range.start < cursor || range.end <= range.start) return@forEach
        builder.append(source, cursor, range.start)
        val targetLabel = target.raftMessagePermalinkChipTargetLabel(resolveLabel(target))
        val kindLabel = target.raftMessagePermalinkChipKindLabel()
        val inlinePrefix = "slock-message-permalink-${range.start}-${range.end}"
        val iconId = "$inlinePrefix-icon"
        val iconGapId = "$inlinePrefix-icon-gap"
        val suffixGapId = "$inlinePrefix-suffix-gap"
        val chipBox = raftMessagePermalinkChipBoxStyle()
        builder.withRaftInlineChipGaps(chipBox) {
        withRaftInlineBox(chipBox) {
          withLink(
            LinkAnnotation.Url(
                url = link.url,
                styles = raftMessagePermalinkChipLinkStyles(),
                linkInteractionListener = LinkInteractionListener { clickedLink: LinkAnnotation ->
                    val clickedUrl = clickedLink as? LinkAnnotation.Url ?: return@LinkInteractionListener
                    link.linkInteractionListener?.onClick(clickedUrl) ?: openUri(clickedUrl.url)
                }
            )
        ) {
            // web PermalinkReferenceChip: MessageReferenceChip variant="link" — a 0.875em inline
            // chip with the Link icon, the label and a mono "msg" note, each 4px apart.
            withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                appendInlineContent(iconId, "\uFFFC")
                appendInlineContent(iconGapId, "\uFFFC")
                withStyle(raftSpanStyleForWeight(FontWeight.SemiBold).copy(color = RaftColor.TextBlack)) {
                    append(targetLabel)
                }
                appendInlineContent(suffixGapId, " ")
                withStyle(
                    SpanStyle(
                        color = RaftColor.ForegroundHint,
                        // raft-ui `font-mono text-label font-normal`.
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = raftMonoFamilyForWeight(FontWeight.Normal),
                    )
                ) {
                    append(kindLabel)
                }
            }
          }
        }
        }
        inlineContent[iconId] = raftMessagePermalinkIconInlineContent()
        inlineContent[iconGapId] = raftMessagePermalinkSpacerInlineContent(4.sp)
        inlineContent[suffixGapId] = raftMessagePermalinkSpacerInlineContent(4.sp)
        cursor = range.end
    }
    builder.append(source, cursor, source.length)
    return RaftMessagePermalinkChipContent(
        text = builder.toAnnotatedString(),
        inlineContent = inlineContent
    )
}

private fun raftMessagePermalinkIconInlineContent(): InlineTextContent {
    return InlineTextContent(
        placeholder =
            Placeholder(
                width = 12.sp,
                height = 12.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
    ) {
        RaftLucideIcon(
            name = RaftLucideIconName.Link,
            modifier = Modifier.size(12.dp),
            // raft-ui reference chip: `[&_svg]:text-foreground-muted`.
            color = raftArgbHex(RaftColor.ForegroundMuted),
            strokeWidth = 2f,
        )
    }
}

private fun raftMessagePermalinkSpacerInlineContent(width: TextUnit): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(width = width, height = 1.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {}

private fun RaftMessagePermalinkTarget.raftMessagePermalinkChipTargetLabel(
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

private fun RaftMessagePermalinkTarget.raftMessagePermalinkChipKindLabel(): String =
    if (threadParentMessageId == null) "msg" else "thread"

/** Markdown parsers may retain autolink annotations inside code spans. */
private fun AnnotatedString.withoutLinksInsideRaftInlineCode(): AnnotatedString {
    val links = getLinkAnnotations(0, length)
    if (links.none { range ->
            getStringAnnotations(
                RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
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
                        RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
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

// raft-ui inline chip with a leading mark: `bg-info-soft`, 4.5px at each end, `rounded-sticker`,
// no edge (plus mobile's 2dp outer gap, see toInlineBoxSpanStyle).
internal fun raftMessagePermalinkChipBoxStyle(): RaftInlineBoxStyle =
    RaftInlineBoxStyle(
        backgroundColor = RaftColor.InfoSoft,
        paddingStart = 4.5.dp,
        paddingEnd = 4.5.dp,
        paddingTop = RaftRichTextInlineChipVerticalPaddingDp.dp,
        paddingBottom = RaftRichTextInlineChipVerticalPaddingDp.dp,
        marginStart = RaftRichTextInlineChipMarginDp.dp,
        marginEnd = RaftRichTextInlineChipMarginDp.dp,
        cornerRadius = RaftAppRadius.Sticker,
    )

private fun raftMessagePermalinkChipLinkStyles(): TextLinkStyles {
    val base =
        raftSpanStyleForWeight(FontWeight.SemiBold).copy(
            color = RaftColor.TextBlack,
            textDecoration = null,
            background = Color.Unspecified,
        )
    // raft-ui `active:overlay-medium`; link styles cannot reach the box annotation, so a span wash
    // over the chip's text stands in for the overlay.
    return TextLinkStyles(
        style = base,
        pressedStyle = base.copy(background = RaftAppThemeState.colors.fillPressed)
    )
}

private object RaftMarkdownPreparedBodyCache {
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

private object RaftMarkdownPreparseWarmCache {
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

internal data class RaftMarkdownPreparseResult(
    val requestedCount: Int,
    val parsedCount: Int,
    val cacheHitCount: Int,
    val scheduledCount: Int
)

internal suspend fun prepareRaftMarkdownFullRendererBodies(
    bodies: List<String>,
    maxBodies: Int = RaftMarkdownDefaultPreparseLimit
): RaftMarkdownPreparseResult = coroutineScope {
    if (bodies.isEmpty() || maxBodies <= 0) {
        return@coroutineScope RaftMarkdownPreparseResult(
            requestedCount = 0,
            parsedCount = 0,
            cacheHitCount = 0,
            scheduledCount = 0
        )
    }
    val contents =
        bodies
            .asSequence()
            .mapNotNull { body -> body.preparedRaftMarkdownFullRendererContentOrNull() }
            .distinct()
            .take(maxBodies)
            .toList()
    val claim = RaftMarkdownPreparseWarmCache.claim(contents)
    val scheduledCacheHitCount =
        try {
            claim.contents
                .map { content ->
                    async {
                        try {
                            prepareMarkdownContent(content).also {
                                RaftMarkdownPreparseWarmCache.complete(content)
                            }
                        } catch (error: Throwable) {
                            RaftMarkdownPreparseWarmCache.release(content)
                            throw error
                        }
                    }
                }
                .awaitAll()
                .count { cacheHit -> cacheHit }
        } catch (error: Throwable) {
            claim.contents.forEach(RaftMarkdownPreparseWarmCache::release)
            throw error
        }
    RaftMarkdownPreparseResult(
        requestedCount = contents.size,
        parsedCount = claim.contents.size - scheduledCacheHitCount,
        cacheHitCount = claim.coveredCount + scheduledCacheHitCount,
        scheduledCount = claim.contents.size
    )
}

internal fun String.preparedRaftMarkdownFullRendererContentOrNull(): MarkdownParserContent? {
    val (content, _) =
        RaftMarkdownPreparedBodyCache.getOrPut(this) {
            normalizeThreadMarkdownBody()
        }
    if (content.isBlank()) return null
    return MarkdownParserContent.from(content)
}

private const val RaftMarkdownDefaultPreparseLimit = 20

private val RaftMarkdownBodyFontSize get() = RaftTextSize.Content
// `text-body` 16/24, the Web's message text.
private val RaftMarkdownBodyLineHeight get() = RaftTextSize.ContentLineHeight
internal val RaftMarkdownTaskCheckboxSize = 14.dp
internal val RaftMarkdownTaskCheckboxFirstLineOffset =
    ((RaftTextSize.ContentLineHeight.value.dp - RaftMarkdownTaskCheckboxSize) / 2).coerceAtLeast(0.dp)
internal const val RaftMarkdownPendingActionIndicator = " …"
private const val RaftMarkdownBodyClickTag = "slock-markdown-body-click"
private val LocalRaftMarkdownBodyClick = compositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun RaftMarkdownTaskCheckbox(model: MarkdownComponentModel) {
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
                    .raftMarkdownTaskCheckboxSemantics(
                        checkboxLabel = checkboxLabel,
                        stateLabel = stateLabel
                    )
                    // 14dp marker inside the 22dp first text line: pin the marker
                    // to that line instead of centering it in the whole list item,
                    // which may contain wrapped text or nested lists (task #210).
                    .padding(top = RaftMarkdownTaskCheckboxFirstLineOffset, end = 4.dp)
        ) {
            RaftCheckMarker(
                checked = checked,
                tone = RaftCheckMarkerTone.BlackFill,
                size = RaftMarkdownTaskCheckboxSize
            )
        }
    }
}

internal fun Modifier.raftMarkdownTaskCheckboxSemantics(
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
internal fun raftMarkdownSingleParagraphLightNodeOrNull(parserContent: String): ASTNode? {
    val nodes = parseMarkdownTopLevelNodes(parserContent)
    val single = nodes.singleOrNull() ?: return null
    if (single.type != MarkdownElementTypes.PARAGRAPH) return null
    return single.takeIf { raftMarkdownLightParagraphSubtreeSafe(it) }
}

private fun raftMarkdownLightParagraphSubtreeSafe(root: ASTNode): Boolean {
    if (root.type !in RaftMarkdownLightSubtreeAllowedTypes) return false
    root.children.forEach { child ->
        if (!raftMarkdownLightParagraphSubtreeSafe(child)) return false
    }
    return true
}

private val RaftMarkdownLightSubtreeAllowedTypes: Set<IElementType> =
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
fun RaftMarkdownBody(
    body: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = RaftType.Body.copy(
        fontSize = RaftMarkdownBodyFontSize,
        lineHeight = RaftMarkdownBodyLineHeight
    ),
    highlightedMentions: Set<String> = emptySet(),
    isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultRaftMarkdownTagValidity,
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
    fillWidth: Boolean = true,
    // The floor under the text's line height that keeps inline chips (mentions, refs) clear of
    // the next line. Surfaces whose web line box is tighter than the chips (attachment comment
    // cards, footnote 13/18) pass their own line height.
    minInlineLineHeight: TextUnit = RaftMarkdownMinimumInlineLineHeight
) {
    // task #441: print the factual renderer-capability marker so visual capture
    // records this platform's caps (collector: scripts/collect_richtext_caps.py;
    // missing marker = unknown, repeated lines are fine — the last one wins).
    LaunchedEffect(Unit) {
        println(raftRichTextCapsLine())
    }
    RaftMarkdownBodyRecomposeMarker(
        performanceKey = performanceKey,
        bodyLength = body.length,
        performanceLogger = performanceLogger
    )
    // t6-a: install/clear the vendor node-timing sink keyed to the same
    // diagnostics gate as the phase traces. Assignment only — with the gate
    // OFF the vendor stays on its untimed path (executable counterfactual:
    // sink == null before any clock read; see RaftMarkdownNodeTiming.kt).
    RaftMarkdownNodeTrace.sink = raftMarkdownNodeSinkFor(raftTraceDiagnosticsEnabled())
    // Same gate for the vendor SlockMarkdownSpike println channel: it used to
    // be an unconditional println, so production paid string building +
    // annotation scans + a logcat round-trip per text composition.
    RaftMarkdownSubTrace.enabled = raftTraceDiagnosticsEnabled()
    val content = remember(body, performanceKey) {
        val startedAt = performanceKey?.let { TimeSource.Monotonic.markNow() }
        val (prepared, cacheHit) =
            traceMarkdownSectionNonComposable(RaftMarkdownTraceNames.PREPARE) {
                RaftMarkdownPreparedBodyCache.getOrPut(body) {
                    body.normalizeThreadMarkdownBody()
                }
            }
        if (performanceKey != null) {
            val durationMs = startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
            if (shouldLogRaftMarkdownPerformance(durationMs)) {
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
        if (raftTraceDiagnosticsEnabled()) LocalRaftMarkdownPlainCandidateSession.current else null
    val rowCensusToken =
        if (rowCensusSession != null && performanceKey != null) {
            remember(rowCensusSession, performanceKey, content) {
                RaftMarkdownRowCensusToken(
                    session = rowCensusSession,
                    rowKey = performanceKey,
                    candidate = RaftMarkdownPlainCandidatePredicate.isConservativePlainCandidate(content),
                    chars = content.length
                )
            }
        } else {
            null
        }
    val markdownModifier =
        if (DEBUG_RAFT_MARKDOWN_PERF_LOG && performanceKey != null) {
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
    val markdownTextStyle = remember(textStyle, minInlineLineHeight) {
        raftMarkdownTextStyleWithInlineLineHeight(textStyle, minInlineLineHeight)
    }
    val currentOnOpenTag by rememberUpdatedState(onOpenTag)
    val currentOnOpenTagAction by rememberUpdatedState(onOpenTagAction)
    val currentOnOpenUrl = rememberUpdatedState(onOpenUrl)
    val currentResolveTagLabel by rememberUpdatedState(resolveTagLabel)
    val taskStatuses = LocalRaftMarkdownTaskStatuses.current
    val currentTaskStatuses by rememberUpdatedState(taskStatuses)
    val mentionReadStates = LocalRaftMarkdownMentionReadStates.current
    val currentMentionReadStates by rememberUpdatedState(mentionReadStates)
    val colors = RaftMarkdownColorsDefault
    // The typography carries theme colours (h6, quotes); the theme is part of its key.
    val markdownDark = RaftAppThemeState.isDark
    val typography = remember(markdownTextStyle, markdownDark, minInlineLineHeight) {
        raftMarkdownTypography(markdownTextStyle, minInlineLineHeight)
    }
    val markdownParserContent = remember(content) {
        MarkdownParserContent.from(content)
    }
    val parserContent = markdownParserContent.parserContent
    // #367 light path (override-gated, default OFF): gate-first — with the
    // override OFF the ambient read is the only added work, the tree lookup
    // never runs, and the render below is byte-identical to the full path.
    val lightParagraphNode =
        if (LocalRaftMarkdownRenderConfig.current.singleParagraphLightPath) {
            remember(markdownParserContent) {
                raftMarkdownSingleParagraphLightNodeOrNull(parserContent)
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
            RaftMarkdownInlineTokenRegistry.plan(
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
    val richTextDocument: RaftRichTextDocument =
        remember(body, parserContent, tokenPlan, bodyActiveTagKey, bodyActiveActionId, activeActionIndicator) {
        buildRaftRichTextDocumentFromMarkdownPlan(
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
                    "inlineCode=${richTextDocument.inlineTokens.count { it.kind == RaftRichTextInlineKind.InlineCode }} " +
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
                    "inlineCode=${richTextDocument.inlineTokens.count { it.kind == RaftRichTextInlineKind.InlineCode }} " +
                    "scope=${inlineContentScope.orEmpty()}"
            }
        )
    }
    val mentionReadFontSize = markdownTextStyle.fontSize.takeIf { it.isSp } ?: RaftMarkdownBodyFontSize
    val annotator: MarkdownAnnotator = remember(richTextDocument, performanceKey, softLineBreaksAsNewLines, mentionReadFontSize) {
        raftMarkdownAnnotator(
            document = richTextDocument,
            onOpenAction = { action: RaftRichTextAction ->
                val tag = action.toThreadMarkdownTag()
                currentOnOpenTagAction?.invoke(tag, action.id) ?: currentOnOpenTag(tag)
            },
            resolveActionLabel = { action: RaftRichTextAction ->
                currentResolveTagLabel?.invoke(action.toThreadMarkdownTag())
            },
            onDiagnostics =
                performanceKey?.let { key ->
                    { inlineTagCount: Int, inlineCodeCount: Int, durationMs: Long ->
                        if (shouldLogRaftMarkdownPerformance(durationMs)) {
                            performanceLogger(
                                "thread_markdown_prepare stage=annotator key=$key " +
                                    "inlineTags=$inlineTagCount inlineCode=$inlineCodeCount " +
                                    "contentLength=${content.length} durationMs=$durationMs"
                            )
                        }
                    }
                },
            softLineBreaksAsNewLines = softLineBreaksAsNewLines,
            taskStatuses = { currentTaskStatuses },
            mentionReadStates = { currentMentionReadStates },
            // A read mark follows someone else's @mention, which is set at the sentence's size.
            mentionReadFontSize = mentionReadFontSize
        )
    }
    if (DEBUG_RAFT_MARKDOWN_PERF_LOG && performanceKey != null) {
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
            // Link and task-chip spans resolve theme colours at annotation time.
            add(if (markdownDark) "theme:dark" else "theme:light")
            if (bodyActiveActionId != null) add("activeAction:$bodyActiveActionId")
            if (bodyActiveTagKey != null) add("activeTag:$bodyActiveTagKey")
            if (activeActionIndicator.isNotEmpty()) add("indicator:$activeActionIndicator")
            if (resolveTagLabel != null && richTextDocument.actions.isNotEmpty()) {
                richTextDocument.actions.forEach { action ->
                    add(currentResolveTagLabel?.invoke(action.toThreadMarkdownTag()))
                }
            }
            // Task chips read the status map and the theme at annotation time.
            if (taskStatuses != null) {
                addAll(raftMarkdownTaskChipRevision(richTextDocument.actions, taskStatuses, markdownDark))
            }
            // read_receipts_v0 mention badges read the provided states at annotation time.
            if (mentionReadStates != null) {
                addAll(raftMarkdownMentionReadRevision(richTextDocument.actions, mentionReadStates))
            }
        }
    val padding = remember {
        markdownPadding(
            block = 0.dp,
            paragraphVerPadding = PaddingValues(vertical = 0.dp),
            codeBlock = PaddingValues(RaftAppSpacing.Sm),
            // web `li mb-0.5`. The marker slot carries the whole `pl-5` indent
            // (MarkdownListIndentEm), so the list adds none of its own.
            listItemBottom = 2.dp,
            listIndent = 0.dp,
            // Blockquote rail must span EXACTLY the quoted content's height
            // (task #62): web renders border-l-2 with no vertical padding — the
            // border is precisely the content box.
            blockQuoteText = PaddingValues(0.dp),
            // web `border-l-2 pl-2.5 my-1`: the rail flush at the quote origin,
            // the text 10dp after it, and 4dp above and below the whole quote
            // (the rail is inset by the same 4dp so it stays the text's height).
            blockQuote = PaddingValues(start = 10.dp, top = 4.dp, end = 0.dp, bottom = 4.dp),
            blockQuoteBar = PaddingValues.Absolute(left = 0.dp, top = 4.dp, right = 0.dp, bottom = 4.dp)
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
                RaftMarkdownHorizontalRule()
            },
            // web blockquote: `border-l-2 border-line-seam`, text `text-foreground-muted`.
            blockQuote = { model ->
                MarkdownBlockQuote(
                    model.content,
                    model.node,
                    style = model.typography.quote,
                    barColor = RaftColor.BorderSeam
                )
            },
            orderedList = { model ->
                RaftMarkdownOrderedList(model)
            },
            // task #212: upstream's table header derives its own style by setting
            // fontWeight to Bold, which changes the weight but leaves the font FAMILY
            // alone. Slock encodes weight in the family name (Kuikly drops weight from
            // the serialized family, see withRaftFontWeight), so that derivation asks
            // the platform to synthesise bold on top of the Regular face instead of
            // using the real 700 cut — the header rendered heavier and blurrier than
            // React's `th`, which uses a true SpaceGrotesk Bold file. Handing the header
            // a style that already carries the Bold family makes upstream's weight
            // override redundant and yields the real cut.
            // Overriding the component here keeps the vendored fork free of a new
            // divergence.
            table = { model ->
                // web MarkdownContent table: `border-collapse border border-line-hairline`,
                // th/td `border border-line-hairline px-2 py-1` — a closed 1px hairline grid.
                CompositionLocalProvider(
                    LocalRaftMarkdownTableCellPadding provides RaftMarkdownTableCellPadding,
                    LocalRaftMarkdownTableClosedGrid provides true,
                ) {
                    MarkdownTable(
                        model.content,
                        model.node,
                        style = model.typography.table,
                        headerBlock = { content, header, style, columnWidths, rowWidths ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                style = style.withRaftFontWeight(FontWeight.Bold),
                                columnWidths = columnWidths,
                                rowWidths = rowWidths
                            )
                        }
                    )
                }
            },
            // task #66: code blocks carry a persistent transparent copy
            // affordance (top-right lucide copy, no plate — May task #9 native
            // style), tap copies the whole block; brief Check as feedback.
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, language, style ->
                    RaftMarkdownCodeBlockWithCopy(code, language, style)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, language, style ->
                    RaftMarkdownCodeBlockWithCopy(code, language, style)
                }
            },
            checkbox = { model ->
                RaftMarkdownTaskCheckbox(model)
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
        traceMarkdownSection(RaftMarkdownTraceNames.LAYOUT, body.length) {
            traceMarkdownSection(RaftMarkdownTraceNames.LAYOUT_BLOCK, body.length) {
                RaftMarkdownSingleParagraphLightBody(
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
        val markdownProvidedValues = raftMarkdownProvidedValuesWith(
            arrayOf(
                LocalTextProvider provides RaftMarkdownTextProvider,
                LocalUriHandler provides uriHandler,
                LocalRaftMarkdownBodyClick provides onClick,
                LocalRaftMarkdownAnnotationRevision provides annotationRevision,
                // task #800: top-level paragraphs wrap when the body opts out of
                // fill (right-aligned bubbles); list/quote paragraphs pin fill.
                LocalRaftMarkdownParagraphFillWidth provides fillWidth
            ),
            LocalRaftMarkdownRowRenderCostSink,
            rowCensusToken
        )
        CompositionLocalProvider(*markdownProvidedValues) {
            traceMarkdownSection(RaftMarkdownTraceNames.LAYOUT, body.length) {
                // #192 long-body sub-trace: split layout phase into pre-block setup
                // vs the third-party `Markdown()` call so 5-10ms spikes can be
                // localized. Fires only when the wrapped block exceeds the
                // MarkdownSpikeThresholdUs (200us default), keeping logcat clean for
                // short bodies. Wrap in `traceMarkdownSection` (not non-composable)
                // because the call is inside an existing @Composable scope.
                traceMarkdownSection(RaftMarkdownTraceNames.LAYOUT_INNER, body.length) {
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
                    val refTextProvider = RaftMarkdownTextProvider
                    // Pass-through, no-op; just to keep the locals from being flagged unused.
                    @Suppress("UNUSED_EXPRESSION") refContent; refTypography; refAnnotator; refPadding; refComponents; refModifier; refColors; refTextProvider
                }
                traceMarkdownSection(RaftMarkdownTraceNames.LAYOUT_BLOCK, body.length) {
                    Markdown(
                        content = markdownParserContent,
                        colors = colors,
                        typography = typography,
                        textProvider = RaftMarkdownTextProvider,
                        annotator = annotator,
                        padding = padding,
                        dimens = RaftMarkdownDimens,
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
 * [RaftMarkdownRenderConfig.singleParagraphLightPath], default OFF).
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
 * reads via RaftMarkdownTextProvider — LocalRaftMarkdownRenderConfig
 * (permalink projection + this very gate) and
 * LocalRaftMessagePermalinkLabelResolver — are intentionally NOT shadowed:
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
private fun RaftMarkdownSingleParagraphLightBody(
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
    rowCensusToken: RaftMarkdownRowCensusToken?,
) {
    val referenceLinkHandler = remember(paragraphNode) { ReferenceLinkHandlerImpl() }
    val extendedSpans = markdownExtendedSpans()
    val animations = markdownAnimations()
    val imageTransformer = remember { NoOpImageTransformerImpl() }
    val imageLinkHandler = remember { ImageLinkHandlerImpl() }
    val nodeTiming = raftMarkdownRenderTimingOrNull()
    val renderStartMark = TimeSource.Monotonic.markNow()
    raftMarkdownLogSubTrace {
        "phase=markdown.success_light.compose contentHash=${content.hashCode().toString(16)} " +
            "contentLength=${content.length} nodeListSize=1"
    }
    val lightProvidedValues = raftMarkdownProvidedValuesWith(
        arrayOf(
            LocalMarkdownColors provides colors,
            LocalMarkdownTypography provides typography,
            LocalMarkdownExtendedSpans provides extendedSpans,
            LocalMarkdownAnimations provides animations,
            LocalImageTransformer provides imageTransformer,
            LocalImageLinkHandler provides imageLinkHandler,
            LocalMarkdownAnnotator provides annotator,
            LocalReferenceLinkHandler provides referenceLinkHandler,
            LocalTextProvider provides RaftMarkdownTextProvider,
            LocalUriHandler provides uriHandler,
            LocalRaftMarkdownBodyClick provides onClick,
            LocalRaftMarkdownAnnotationRevision provides annotationRevision,
            LocalRaftMarkdownParagraphFillWidth provides fillWidth,
            LocalRaftMarkdownRenderTiming provides nodeTiming
        ),
        LocalRaftMarkdownRowRenderCostSink,
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
    val renderDurationUs = raftMarkdownMonotonicNanosSince(renderStartMark) / 1_000L
    if (renderDurationUs >= RaftMarkdownVendorTraceThresholdUs) {
        raftMarkdownLogSubTrace {
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
// web MarkdownLink: `text-foreground-link` (light #1447E6 / dark #8EC5FF).
private val RaftMarkdownLinkBlue: Color get() = RaftAppThemeState.colors.foregroundLink

// File-level singleton for the same #192 stable-reference reason as the colors
// above (`markdownDimens(...)` is @Composable and would allocate a fresh
// instance every recomposition). Values mirror the library defaults except the
// blockquote rail: web renders border-l-2 (2px), the 1dp default reads
// hairline-thin on device (task #62).
private object RaftMarkdownDimens : MarkdownDimens {
    override val dividerThickness: Dp = 0.5.dp
    override val codeBackgroundCornerSize: Dp = 8.dp
    override val blockQuoteThickness: Dp = 2.dp
    override val tableMaxWidth: Dp = Dp.Unspecified
    override val tableCellWidth: Dp = 160.dp
    override val tableSeparatorThickness: Dp = 1.dp
    // Legacy uniform inset; Raft tables use RaftMarkdownTableCellPadding (web px-2 py-1).
    override val tableCellPadding: Dp = 6.dp
    // web chat table has no radius (and the vendor table never read this).
    override val tableCornerSize: Dp = 0.dp
}
// web MarkdownContent BaseCodePre: `bg-layer-code text-code-foreground border
// border-line-hairline rounded-container px-3 py-2 pr-12 font-mono
// [font-size:inherit]` (light: white surface, dark: #0A0C10).
private val RaftMarkdownCodeBlockBackground: Color get() = RaftAppThemeState.colors.layerCode
private val RaftMarkdownCodeBlockForeground: Color get() = RaftAppThemeState.colors.codeForeground
/** web MarkdownContent th/td `px-2 py-1`. */
internal val RaftMarkdownTableCellPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
// The end column holds the 28dp copy action 6dp from the edge, plus 6dp before the code.
private val RaftMarkdownCodeBlockPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 40.dp, bottom = 8.dp)
internal val RaftMarkdownMinimumInlineLineHeight = 22.sp
// web hr: raft-ui Separator (1px `line-hairline`) with `my-2`.
private val RaftMarkdownDividerColor: Color get() = RaftColor.BorderHairline
private val RaftMarkdownDividerThickness = 1.dp
private val RaftMarkdownDividerVerticalPadding = RaftAppSpacing.Sm

// task #66: persistent transparent copy affordance on code blocks — the old
// native Android app had this (May task #9: transparent tap area + lucide copy
// icon, no plate) and the KMP rewrite dropped it, leaving no way to copy code
// on phones. Shared implementation = Android/iOS behave identically.
@Composable
private fun RaftMarkdownCodeBlockWithCopy(
    code: String,
    language: String?,
    style: TextStyle
) {
    if (language.equals("mermaid", ignoreCase = true)) {
        // Diagram labels follow the message body font (not the code font), in the multi-weight
        // family so the scene's bold labels pick the bundled Bold face.
        val paragraph = LocalMarkdownTypography.current.paragraph
        val diagramTextStyle =
            remember(paragraph) { paragraph.copy(fontFamily = raftMultiWeightFontFamily("sans")) }
        RaftMermaidDiagramBlock(
            code = code,
            diagramTextStyle = diagramTextStyle,
            fallback = {
                RaftMarkdownCodeBlockContent(code, language, style)
            }
        )
    } else {
        RaftMarkdownCodeBlockContent(code, language, style)
    }
}

@Composable
private fun RaftMarkdownCodeBlockContent(
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
        RaftMarkdownCodeSurface(code = code, style = style)
        val pressSource = remember { MutableInteractionSource() }
        val pressed by pressSource.collectIsPressedAsState()
        Box(
            // raft-ui 0.20 CodeBlock: the copy action is a 28dp ghost button 6dp from the block's
            // corner at radius-chip, in a column of its own (the code keeps its end padding), and
            // presses with the medium tier.
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = RaftMarkdownCodeSurfaceVerticalInset + RaftMarkdownCodeActionInset, end = RaftMarkdownCodeActionInset)
                    .size(RaftMarkdownCodeActionSize)
                    .clip(RoundedCornerShape(RaftMarkdownCodeActionRadius))
                    .background(if (pressed) RaftAppThemeState.colors.fillPressed else Color.Transparent)
                    .clickable(interactionSource = pressSource, indication = null) {
                        bridge.copyToPasteboard(code)
                        copied = true
                    },
            contentAlignment = Alignment.Center,
        ) {
            RaftLucideIcon(
                name = if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy,
                // web CopyIconButton surface="code": follows the code palette.
                color = raftArgbHex(RaftMarkdownCodeBlockForeground.copy(alpha = 0.7f)),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private val RaftMarkdownCodeSurfaceVerticalInset = 8.dp
private val RaftMarkdownCodeActionInset = 6.dp
private val RaftMarkdownCodeActionSize = 28.dp
private val RaftMarkdownCodeActionRadius = 6.dp

/**
 * Code block surface. Replaces the vendor `MarkdownCode`, whose background is
 * drawn before the clip (square corners) and which has no border.
 */
@Composable
private fun RaftMarkdownCodeSurface(code: String, style: TextStyle) {
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = RaftMarkdownCodeSurfaceVerticalInset)
                .clip(shape)
                .background(RaftMarkdownCodeBlockBackground)
                .border(1.dp, RaftColor.BorderHairline, shape)
    ) {
        MarkdownBasicText(
            text = code,
            color = RaftMarkdownCodeBlockForeground,
            style = style,
            modifier = Modifier.padding(RaftMarkdownCodeBlockPadding),
        )
    }
}

/**
 * web `ol list-decimal pl-5`: the number hangs outside the text, right-aligned in the same
 * 1.25em marker slot the bullets use, so numbered and bulleted items start their text at the same
 * indent. Numbering starts at the first item's number, as `<ol start>` does.
 */
@Composable
private fun RaftMarkdownOrderedList(model: MarkdownComponentModel) {
    val style = model.typography.ordered
    val density = LocalDensity.current
    val fontSize = with(density) { (if (style.fontSize.isSpecified) style.fontSize else RaftMarkdownBodyFontSize).toDp() }
    val start = remember(model.node, model.content) { raftMarkdownOrderedListStart(model.node, model.content) }
    MarkdownListItems(
        content = model.content,
        node = model.node,
        depth = model.listDepth,
        markerModifier = { Modifier.width(fontSize * MarkdownListIndentEm) },
    ) { index, _ ->
        MarkdownBasicText(
            text = raftMarkdownOrderedListMarker(start, index),
            style = style,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = fontSize * RaftMarkdownOrderedMarkerGapEm)
                    .wrapContentWidth(Alignment.End, unbounded = true),
            softWrap = false,
        )
    }
}

// Chromium's outside decimal marker ends with a space before the text (about 0.25em).
private const val RaftMarkdownOrderedMarkerGapEm = 0.25f

internal fun raftMarkdownOrderedListStart(node: ASTNode, content: String): Int =
    node.children
        .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        ?.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)
        ?.getTextInNode(content)
        ?.toString()
        ?.trim()
        ?.takeWhile(Char::isDigit)
        ?.toIntOrNull()
        ?: 1

internal fun raftMarkdownOrderedListMarker(start: Int, index: Int): String = "${start + index}."

@Composable
private fun RaftMarkdownHorizontalRule() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = RaftMarkdownDividerVerticalPadding)
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(RaftMarkdownDividerThickness)
                .background(RaftMarkdownDividerColor)
        )
    }
}

// raft-ui markdown link: `text-foreground-link underline decoration-2 underline-offset-2
// decoration-foreground-link/40`. Upstream SpanStyle has no underline colour, thickness or offset,
// so the underline is a RaftInlineUnderlineStyle annotation ([withRaftMarkdownLinkUnderlines]).
internal fun raftMarkdownLinkSpanStyle(base: TextStyle): SpanStyle =
    base.toSpanStyle().copy(
        color = RaftMarkdownLinkBlue,
        textDecoration = null,
    )

internal fun raftMarkdownLinkUnderlineStyle(): RaftInlineUnderlineStyle =
    RaftInlineUnderlineStyle(
        color = RaftMarkdownLinkBlue.copy(alpha = 0.4f),
        thickness = RAFT_RICHTEXT_LINK_UNDERLINE_THICKNESS_SP.sp,
        offset = RAFT_RICHTEXT_LINK_UNDERLINE_OFFSET_SP.sp,
    )

/**
 * Underlines every web link (a [LinkAnnotation.Url] outside a chip box) with
 * [raftMarkdownLinkUnderlineStyle]. Chips (permalinks) keep no underline; tags are
 * [LinkAnnotation.Clickable] and are left alone.
 */
internal fun AnnotatedString.withRaftMarkdownLinkUnderlines(): AnnotatedString {
    val links = getLinkAnnotations(0, length).filter { it.item is LinkAnnotation.Url && it.end > it.start }
    if (links.isEmpty()) return this
    val boxes = raftInlineBoxRanges()
    val underlined = links.filter { link -> boxes.none { box -> box.start < link.end && link.start < box.end } }
    if (underlined.isEmpty()) return this
    val encoded = raftMarkdownLinkUnderlineStyle().encode()
    return AnnotatedString.Builder(this)
        .apply {
            underlined.forEach { link ->
                addStringAnnotation(RAFT_INLINE_UNDERLINE_ANNOTATION_TAG, encoded, link.start, link.end)
            }
        }
        .toAnnotatedString()
}

// A getter, not a val: text/line colours follow RaftAppThemeState.
internal val RaftMarkdownColorsDefault: DefaultMarkdownColors
    get() = DefaultMarkdownColors(
        text = RaftColor.TextBlack,
        codeText = RaftMarkdownCodeBlockForeground,
        inlineCodeText = RaftColor.TextBlack,
        linkText = RaftMarkdownLinkBlue,
        codeBackground = RaftMarkdownCodeBlockBackground,
        // RaftInlineBoxStyle is the single inline-code chrome painter.
        inlineCodeBackground = Color.Unspecified,
        dividerColor = RaftColor.BorderBlack,
        tableText = RaftColor.TextBlack,
        // web table has no fill of its own (it shows the message surface); unused by the vendor table.
        tableBackground = Color.Transparent,
        // web th `bg-fill-muted`, every cell `border border-line-hairline`.
        tableHeaderBackground = RaftAppThemeState.colors.fillMuted,
        tableStroke = RaftColor.BorderHairline
    )

internal fun raftMarkdownTextStyleWithInlineLineHeight(
    base: TextStyle,
    minInlineLineHeight: TextUnit = RaftMarkdownMinimumInlineLineHeight
): TextStyle {
    val resolvedBase = base.withResolvedRaftFontFamily()
    val minLineHeight =
        maxOf(
            minInlineLineHeight.value,
            resolvedBase.fontSize.takeIf { it.isSpecified }?.value ?: 0f
        )
    val lineHeight = resolvedBase.lineHeight.takeIf { it.isSpecified }?.value
    return if (lineHeight != null && lineHeight >= minLineHeight) {
        resolvedBase
    } else {
        resolvedBase.copy(lineHeight = minLineHeight.sp)
    }
}

/** web MarkdownContent h1..h6: `text-[1.286em]`, 1.143em, 1.071em, 1em ×3; bold, leading-tight. */
internal val RaftMarkdownHeadingScale = listOf(1.286f, 1.143f, 1.071f, 1f, 1f, 1f)

internal fun raftMarkdownHeadingStyle(base: TextStyle, scale: Float): TextStyle {
    val baseSize = base.fontSize.takeIf { it.isSpecified }?.value ?: 14f
    val size = baseSize * scale
    return base.withRaftFontWeight(FontWeight.Bold).copy(
        fontSize = size.sp,
        // leading-tight (1.25)
        lineHeight = (size * 1.25f).sp,
    )
}

internal fun raftMarkdownTypography(
    base: TextStyle,
    minInlineLineHeight: TextUnit = RaftMarkdownMinimumInlineLineHeight
): DefaultMarkdownTypography {
    // Markdown is user content: every style derives from this base, so Han characters render in
    // Simplified Chinese forms throughout.
    val resolvedBase =
        raftMarkdownTextStyleWithInlineLineHeight(base, minInlineLineHeight).withRaftUserContentLocale()
    // web pre: `font-mono [font-size:inherit]` — Space Mono at the body size.
    val codeBlock =
        resolvedBase.copy(
            fontFamily = raftMonoFamilyForWeight(FontWeight.Normal),
            fontWeight = FontWeight.Normal,
        )
    val inlineCode = raftMarkdownInlineCodeTextStyle(resolvedBase)
    return DefaultMarkdownTypography(
        h1 = raftMarkdownHeadingStyle(resolvedBase, RaftMarkdownHeadingScale[0]),
        h2 = raftMarkdownHeadingStyle(resolvedBase, RaftMarkdownHeadingScale[1]),
        h3 = raftMarkdownHeadingStyle(resolvedBase, RaftMarkdownHeadingScale[2]),
        h4 = raftMarkdownHeadingStyle(resolvedBase, RaftMarkdownHeadingScale[3]),
        h5 = raftMarkdownHeadingStyle(resolvedBase, RaftMarkdownHeadingScale[4]),
        h6 = raftMarkdownHeadingStyle(resolvedBase, RaftMarkdownHeadingScale[5])
            .copy(color = RaftAppThemeState.colors.foregroundMuted),
        text = resolvedBase,
        code = codeBlock,
        inlineCode = inlineCode,
        // web blockquote `text-foreground-muted`.
        quote = resolvedBase.copy(color = RaftColor.TextMuted),
        paragraph = resolvedBase,
        ordered = resolvedBase,
        bullet = resolvedBase,
        list = resolvedBase,
        link = resolvedBase.copy(color = RaftMarkdownLinkBlue),
        textLink = TextLinkStyles(style = raftMarkdownLinkSpanStyle(resolvedBase)),
        table = resolvedBase
    )
}

internal fun raftMarkdownInlineTagWidthSp(
    label: String,
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false
) =
    if (kind == ThreadMarkdownTagKind.UserMention && !highlighted) {
        raftMarkdownPlainMentionWidthSp(label)
    } else {
        raftMarkdownInlineChipWidthSp(label, kind)
    }

private fun raftMarkdownPlainMentionWidthSp(label: String) =
    (label.sumOf { it.raftPlainMentionWidthSp().toDouble() }.toFloat() + label.raftPlainMentionWidthSafetySp())
        .coerceAtLeast(28f)
        .sp

internal fun raftMarkdownInlineChipWidthSp(
    label: String,
    kind: ThreadMarkdownTagKind? = null
) =
    (label.sumOf { it.raftInlineChipWidthSp().toDouble() }.toFloat() +
        label.raftInlineChipWidthSafetySp(kind) +
        RaftRichTextChromeKind.Channel.styleToken.horizontalChromeSp.orZero())
        .coerceAtLeast(40f)
        .sp

// Web's canonical InlineCode uses Space Mono while inheriting the surrounding
// font size. Keep that override scoped to the semantic code range so adjacent
// message prose remains on the resolved Slock UI family (task #816).
internal fun raftMarkdownInlineCodeTextStyle(base: TextStyle): TextStyle =
    RaftRichTextChromeKind.InlineCode.styleToken.applyToTextStyle(base)

private fun String.raftInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

private fun String.raftInlineChipWidthSafetySp(kind: ThreadMarkdownTagKind?): Float =
    when {
        kind == ThreadMarkdownTagKind.Channel ||
            kind == ThreadMarkdownTagKind.ChannelThread ||
            kind == ThreadMarkdownTagKind.DirectMessageThread ||
            kind == ThreadMarkdownTagKind.DirectMessage -> 16f
        startsWith("#") && drop(1).any { !it.isDigit() } -> 16f
        else -> 0f
    }

private fun Char.raftInlineChipWidthSp(): Float =
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

private fun String.raftPlainMentionWidthSafetySp(): Float =
    when {
        raftInlineTextWidthUnits() <= 10 -> 12f
        length <= 14 -> 18f
        else -> 24f
    }

private fun Char.raftPlainMentionWidthSp(): Float =
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

internal fun raftMarkdownTagColor(kind: ThreadMarkdownTagKind, highlighted: Boolean = false): Color =
    raftRichTextChromeKind(kind, highlighted = highlighted)
        .styleToken
        .chipFill
        .toRaftMarkdownColorOrUnspecified()

/**
 * Chip text size at the default 16sp message body (raft-ui inline chip: 0.875em). Chips render at
 * [RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM] of the message body size, so this is only the default.
 */
internal val RaftMarkdownTagFontSize = (16f * RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM).sp

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

// Reference styling mirrors raft-ui (message-reference.recipe.ts, lib/inline-chip.ts):
// - a #channel / DM ref is a solid `attention-soft` chip, a thread / message ref an `info-soft`
//   one: 0.875em semibold ink, 6px at each end, `rounded-sticker`, no underline;
// - the reader's own @mention is the yellow `bg-brand` sticker with a 1.5px `sticker-ink` inset
//   ring in the same box;
// - anyone else's @mention is bold ink at the sentence's size, underlined only while pressed.
// The chip box is a RaftInlineBoxStyle annotation; the 0.875em size is a span inside it
// ([raftMarkdownRichTextTagFontSizeStyle]) so the box painter measures the chip's own text.
internal fun raftMarkdownTagSpanStyle(
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false,
    active: Boolean = false
): SpanStyle =
    raftRichTextChromeKind(kind, highlighted = highlighted, active = active)
        .styleToken
        .toTagSpanStyle()

internal fun raftMarkdownTagLinkStyles(
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false,
    active: Boolean = false
): TextLinkStyles =
    raftMarkdownRichTextTagLinkStyles(raftRichTextChromeKind(kind, highlighted = highlighted, active = active))

internal fun raftMarkdownRichTextTagLinkStyles(
    chromeKind: RaftRichTextChromeKind?
): TextLinkStyles {
    val token = (chromeKind ?: RaftRichTextChromeKind.OrdinaryMention).styleToken
    return TextLinkStyles(
        style = token.toTagSpanStyle(),
        pressedStyle = token.toPressedTagSpanStyle()
    )
}

/** The chip's 0.875em text size, or null when the tag keeps the sentence's size. */
internal fun raftMarkdownRichTextTagFontSizeStyle(chromeKind: RaftRichTextChromeKind?): SpanStyle? =
    (chromeKind ?: RaftRichTextChromeKind.OrdinaryMention).styleToken.fontSizeEm?.let { SpanStyle(fontSize = it.em) }

private fun raftRichTextChromeKind(
    kind: ThreadMarkdownTagKind,
    highlighted: Boolean = false,
    active: Boolean = false
): RaftRichTextChromeKind =
    when {
        active && (kind == ThreadMarkdownTagKind.ChannelThread || kind == ThreadMarkdownTagKind.DirectMessageThread) ->
            RaftRichTextChromeKind.Thread
        active -> RaftRichTextChromeKind.Active
        kind == ThreadMarkdownTagKind.UserMention && highlighted -> RaftRichTextChromeKind.SelfMention
        kind == ThreadMarkdownTagKind.UserMention -> RaftRichTextChromeKind.OrdinaryMention
        kind == ThreadMarkdownTagKind.DirectMessageThread -> RaftRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.DirectMessage -> RaftRichTextChromeKind.Channel
        kind == ThreadMarkdownTagKind.ChannelThread -> RaftRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.Channel -> RaftRichTextChromeKind.Channel
        kind == ThreadMarkdownTagKind.Task -> RaftRichTextChromeKind.Task
        else -> RaftRichTextChromeKind.OrdinaryMention
    }

private fun RaftRichTextChromeStyleToken.applyToTextStyle(base: TextStyle): TextStyle =
    base.copy(
        fontFamily = fontFamily.toRaftFontFamily(fontWeight.toRaftFontWeight()),
        fontWeight = fontWeight.toRaftFontWeight()
    )

/**
 * Text attributes of a tag. The box chrome ([toInlineBoxSpanStyle]) is not a SpanStyle attribute on
 * upstream Compose: it travels as an annotation (see [appendRaftRichTextToken]) and
 * [raftInlineDecorations] paints it.
 */
private fun RaftRichTextChromeStyleToken.toTagSpanStyle(): SpanStyle =
    raftSpanStyleForWeight(fontWeight.toRaftFontWeight()).copy(
        // Ink type on the yellow sticker stays dark in dark mode.
        color = if (inkEdge) RaftColor.StickerInk else foreground.toRaftMarkdownColor(),
        textDecoration = textDecoration.toComposeTextDecoration(),
    )

/**
 * Pressed tag: raft-ui presses a chip with `overlay-medium` (`overlay-sticker-medium` on the
 * sticker) and underlines a plain mention. Link styles cannot reach the box annotation, so a span
 * wash over the chip's text stands in for the overlay.
 */
private fun RaftRichTextChromeStyleToken.toPressedTagSpanStyle(): SpanStyle =
    toTagSpanStyle().copy(
        textDecoration = pressedTextDecoration.toComposeTextDecoration() ?: textDecoration.toComposeTextDecoration(),
        background =
            when {
                chipFill == null -> Color.Unspecified
                inkEdge -> RaftColor.StickerPressed
                else -> RaftAppThemeState.colors.fillPressed
            },
    )

internal fun raftMarkdownRichTextTagInlineBoxStyle(chromeKind: RaftRichTextChromeKind?): RaftInlineBoxStyle? =
    (chromeKind ?: RaftRichTextChromeKind.OrdinaryMention).styleToken.toInlineBoxSpanStyle()

/**
 * Compatibility lowering for the original Slock semantic chrome tokens.
 *
 * New consumers should construct [RaftInlineBoxStyle] directly. Existing
 * semantic tokens lower through this adapter while render backends migrate
 * away from understanding Slock kinds.
 */
internal fun raftMarkdownInlineBoxStyle(kind: RaftRichTextChromeKind): RaftInlineBoxStyle =
    requireNotNull(kind.styleToken.toInlineBoxSpanStyle())

internal fun raftMarkdownInlineCodeEdgeStyle(
    leading: Boolean,
    trailing: Boolean = !leading,
): RaftInlineBoxStyle =
    raftMarkdownInlineBoxStyle(RaftRichTextChromeKind.InlineCode).copy(
        paddingStart = if (leading) RaftRichTextInlineCodePaddingXDp.dp else 0.dp,
        paddingEnd = if (trailing) RaftRichTextInlineCodePaddingXDp.dp else 0.dp,
    )

private fun RaftRichTextChromeStyleToken.toInlineBoxSpanStyle(): RaftInlineBoxStyle? {
    val fill = chipFill ?: return null
    val isInlineCode = id == RaftRichTextChromeStyleTokens.InlineCode.id
    val paddingX = if (isInlineCode) RaftRichTextInlineCodePaddingXDp else RaftRichTextInlineChipPaddingXDp
    return RaftInlineBoxStyle(
        backgroundColor = fill.toRaftMarkdownColor(),
        borderColor = if (inkEdge) RaftColor.StickerInk else Color.Transparent,
        borderWidth = if (inkEdge) RaftRichTextInlineChipInkEdgeDp.dp else 0.dp,
        paddingStart = paddingX.dp,
        paddingEnd = paddingX.dp,
        paddingTop = (if (isInlineCode) RaftRichTextInlineCodePaddingTopDp else RaftRichTextInlineChipVerticalPaddingDp).dp,
        paddingBottom = (if (isInlineCode) RaftRichTextInlineCodePaddingBottomDp else RaftRichTextInlineChipVerticalPaddingDp).dp,
        // Mobile keeps a 2dp outer gap so a chip glued to CJK text or punctuation, or to another
        // chip, never touches it (the web chip has none; see RaftMarkdownTaskChipMargin).
        marginStart = if (isInlineCode) 0.dp else RaftRichTextInlineChipMarginDp.dp,
        marginEnd = if (isInlineCode) 0.dp else RaftRichTextInlineChipMarginDp.dp,
        cornerRadius = RaftAppRadius.Sticker
    )
}

// raft-ui inline chip: `px-1.5`, and `inset-ring-[1.5px]` on the sticker.
private const val RaftRichTextInlineChipPaddingXDp = 6f
private const val RaftRichTextInlineChipInkEdgeDp = 1.5f
internal const val RaftRichTextInlineChipMarginDp = 2f
// raft-ui inline chip: the body line less 2px (22 on the phone's 16/24 body) with no padding of
// its own. The painter boxes the chip's 0.875em text at 1.18em (see drawRaftInlineBoxSegment), so
// (22 - 1.18 * 14) / 2 is left above and below.
internal const val RaftRichTextInlineChipVerticalPaddingDp = 2.75f
// raft-ui InlineCode appearance="message": `px-1`, and a fill one pixel past Space Mono's content
// area (1.12em ascent, 0.36em descent), nudged 0.0275em down. The painter estimates the sans
// 0.92em / 0.26em box, so the extra Space Mono extent is added here.
private const val RaftRichTextInlineCodePaddingXDp = 4f
private const val RaftRichTextInlineCodePaddingTopDp = 3.25f
private const val RaftRichTextInlineCodePaddingBottomDp = 2.75f

/**
 * Top-level Markdown nodes do not reserve space for inline-box paint outside
 * the text line. A following heading must clear the box's bottom padding plus
 * border; this keeps the document opt-in tied to the actual chrome geometry.
 */
internal fun raftMarkdownSectionHeadingGapClearsInlineCodeChrome(gap: Dp): Boolean =
    raftMarkdownInlineBoxStyle(RaftRichTextChromeKind.InlineCode).let { style ->
        gap.value >= style.paddingBottom.value + style.borderWidth.value
    }

private fun RaftRichTextColorToken?.toRaftMarkdownColor(): Color =
    requireNotNull(this) { "Slock richtext color token is required for this chrome path" }
        .toRaftMarkdownColorOrUnspecified()

private fun RaftRichTextColorToken?.toRaftMarkdownColorOrUnspecified(): Color =
    when (this) {
        RaftRichTextColorToken.TextBlack -> RaftColor.TextBlack
        RaftRichTextColorToken.FillMuted -> RaftColor.FillMuted
        RaftRichTextColorToken.BrandYellow -> RaftColor.BrandYellow
        RaftRichTextColorToken.AttentionSoft -> RaftColor.AttentionSoft
        RaftRichTextColorToken.InfoSoft -> RaftColor.InfoSoft
        RaftRichTextColorToken.InactiveSoft -> RaftColor.InactiveSoft
        RaftRichTextColorToken.SoftSignal -> RaftColor.SoftSignal
        null -> Color.Unspecified
    }

private fun RaftRichTextFontFamilyToken.toRaftFontFamily(weight: FontWeight): FontFamily =
    when (this) {
        RaftRichTextFontFamilyToken.RaftMono -> raftMonoFamilyForWeight(weight)
        RaftRichTextFontFamilyToken.RaftUi -> raftFontFamilyForWeight(weight)
    }

private fun RaftRichTextFontWeightToken.toRaftFontWeight(): FontWeight =
    when (this) {
        RaftRichTextFontWeightToken.Normal -> FontWeight.Normal
        RaftRichTextFontWeightToken.SemiBold -> FontWeight.SemiBold
        RaftRichTextFontWeightToken.Bold -> FontWeight.Bold
    }

private fun RaftRichTextTextDecorationToken?.toComposeTextDecoration(): TextDecoration? =
    when (this) {
        RaftRichTextTextDecorationToken.Underline -> TextDecoration.Underline
        null -> null
    }

private fun Float?.orZero(): Float = this ?: 0f
