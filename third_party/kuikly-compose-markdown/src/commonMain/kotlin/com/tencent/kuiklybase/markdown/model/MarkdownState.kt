package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.tencent.kuiklybase.markdown.compose.raftMarkdownLogSubTrace
import com.tencent.kuiklybase.markdown.utils.lookupLinkDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * A [MarkdownState] that executes the parsing of the markdown content with the [MarkdownParser] asynchronously.
 *
 * @param content The markdown content to parse.
 * @param lookupLinks Whether to lookup links in the parsed tree or not.
 * @param flavour The [MarkdownFlavourDescriptor] to use for parsing.
 * @param parser The [MarkdownParser] to use for parsing.
 * @param referenceLinkHandler The [ReferenceLinkHandler] to use for storing links.
 * @param immediate Whether to parse the content immediately or not. (WARNING: This is not advices, as it will block the composition!)
 */
@Composable
fun rememberMarkdownState(
    lookupLinks: Boolean = true,
    isShareMode: Boolean = false,
    initialContent: String? = null
): MarkdownState {
    val initialParserContent = remember(initialContent) {
        initialContent?.let(MarkdownParserContent::from)
    }
    return rememberMarkdownParserState(
        lookupLinks = lookupLinks,
        isShareMode = isShareMode,
        initialContent = initialParserContent
    )
}

@Composable
internal fun rememberMarkdownParserState(
    lookupLinks: Boolean = true,
    isShareMode: Boolean = false,
    initialContent: MarkdownParserContent? = null
): MarkdownState {
    val state = remember(lookupLinks, isShareMode, initialContent) {
        val flavour = RaftGFMFlavourDescriptor()
        val initialParserContent = initialContent?.parserContent
        MarkdownState(
            Input(
                content = initialParserContent.orEmpty(),
                shareModel = isShareMode,
                lookupLinks = lookupLinks,
                flavour = flavour,
                parser = MarkdownParser(flavour),
                referenceLinkHandler = ReferenceLinkHandlerImpl(),
            )
        ).also { state ->
            if (initialParserContent != null) {
                raftMarkdownLogSubTrace {
                    "phase=markdown.state.initial_sync_start " +
                        "hash=${initialParserContent.hashCode().toString(16)} length=${initialParserContent.length}"
                }
                val cacheHit = state.applyInitialContentSynchronously(initialParserContent)
                raftMarkdownLogSubTrace {
                    "phase=markdown.state.initial_sync_end " +
                        "hash=${initialParserContent.hashCode().toString(16)} length=${initialParserContent.length} " +
                        "cacheHit=$cacheHit nodeListSize=${state.nodeListState.size}"
                }
            }
        }
    }
    return state
}

suspend fun prepareMarkdownContent(content: String, lookupLinks: Boolean = true): Boolean {
    return prepareMarkdownContent(MarkdownParserContent.from(content), lookupLinks)
}

suspend fun prepareMarkdownContent(content: MarkdownParserContent, lookupLinks: Boolean = true): Boolean {
    val parserContent = content.parserContent
    val cacheKey = MarkdownParseResultCacheKey(parserContent, lookupLinks)
    MarkdownParseResultCache.get(cacheKey)?.let {
        raftMarkdownLogSubTrace {
            "phase=markdown.preparse.cache_hit " +
                "hash=${parserContent.hashCode().toString(16)} length=${parserContent.length} " +
                "nodeCount=${it.nodes.size} lookupLinks=$lookupLinks"
        }
        return true
    }
    raftMarkdownLogSubTrace {
        "phase=markdown.preparse.parse_start " +
            "hash=${parserContent.hashCode().toString(16)} length=${parserContent.length} lookupLinks=$lookupLinks"
    }
    val parsed =
        withContext(RaftMarkdownParseDispatcher) {
            val flavour = RaftGFMFlavourDescriptor()
            parseMarkdownContent(
                content = parserContent,
                lookupLinks = lookupLinks,
                parser = MarkdownParser(flavour)
            )
        }
    val (cached, cacheHit) = MarkdownParseResultCache.putIfAbsent(cacheKey, parsed)
    raftMarkdownLogSubTrace {
        "phase=markdown.preparse.parse_end " +
            "hash=${parserContent.hashCode().toString(16)} length=${parserContent.length} " +
            "cacheHit=$cacheHit nodeCount=${cached.nodes.size} lookupLinks=$lookupLinks"
    }
    return cacheHit
}

/** Source-accurate inline-code node emitted by the canonical Markdown parse. */
data class MarkdownInlineCodeSpan(
    val sourceText: String,
    val codeText: String,
    val startOffset: Int,
    val endOffset: Int,
)

/** Inline syntax facts shared by the app token planner and Markdown renderer. */
data class MarkdownInlineSyntax(
    val raftRefs: List<RaftMarkdownInlineRef>,
    val inlineCodeSpans: List<MarkdownInlineCodeSpan>,
)

/**
 * Returns inline syntax nodes from the same cached parse result consumed by
 * [MarkdownState]. A cache miss performs the canonical flavour parse once and
 * publishes that result for the renderer; there is no app-side second parser
 * and no app-side delimiter scanner.
 */
fun parseMarkdownInlineSyntax(
    content: String,
    lookupLinks: Boolean = true,
): MarkdownInlineSyntax {
    if (!content.contains('#') && !content.contains('@') && !content.contains('`')) {
        return MarkdownInlineSyntax(emptyList(), emptyList())
    }
    val key = MarkdownParseResultCacheKey(content, lookupLinks)
    val (cached, _) =
        MarkdownParseResultCache.getOrPut(key) {
            val flavour = RaftGFMFlavourDescriptor()
            parseMarkdownContent(
                content = content,
                lookupLinks = lookupLinks,
                parser = MarkdownParser(flavour),
            )
        }
    return MarkdownInlineSyntax(
        raftRefs = cached.inlineRefs,
        inlineCodeSpans = cached.inlineCodeSpans,
    )
}

/**
 * Slock (#367 light path): top-level parsed nodes for [content] from the SAME
 * cached parse result consumed by [MarkdownState] and [parseMarkdownInlineSyntax].
 * A cache miss performs the canonical flavour parse once and publishes it for
 * the renderer; there is no second cache and no second parse. Returned nodes
 * are the cache-shared AST — identity-stable per content (safe as remember
 * keys) and must not be mutated. Same thread-safety contract as
 * [parseMarkdownInlineSyntax]: call from the Kuikly/Compose coroutine thread.
 */
internal fun parseMarkdownTopLevelNodes(
    content: String,
    lookupLinks: Boolean = true,
): List<ASTNode> {
    val key = MarkdownParseResultCacheKey(content, lookupLinks)
    val (cached, _) =
        MarkdownParseResultCache.getOrPut(key) {
            val flavour = RaftGFMFlavourDescriptor()
            parseMarkdownContent(
                content = content,
                lookupLinks = lookupLinks,
                parser = MarkdownParser(flavour),
            )
        }
    return cached.nodes.map { it.node }
}

/**
 * A [MarkdownState] that that executes the parsing of the markdown content with the [MarkdownParser] asynchronously.
 */
@Stable
class MarkdownState internal constructor(
    private val input: Input,
) {
    private val stateFlow: MutableStateFlow<State?> = MutableStateFlow(null)
    val state: StateFlow<State?> = stateFlow.asStateFlow()

    private val linkStateFlow: MutableStateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())
    val links: StateFlow<Map<String, String?>> = linkStateFlow.asStateFlow()

    val nodeListState: SnapshotStateList<ParseResult> = mutableStateListOf()

    private val mutex = Mutex()

    internal fun getContent() = input.content

    fun skipLinkDefinition() = input.lookupLinks

    fun shareMode() = input.shareModel

    internal fun isContentApplied(content: String): Boolean =
        input.content == content && nodeListState.isNotEmpty()

    internal fun applyCachedInitialContent(content: String): Boolean {
        input.content = content
        val cached =
            MarkdownParseResultCache.get(
                MarkdownParseResultCacheKey(content, input.lookupLinks)
            ) ?: return false
        applyParseResult(cached)
        return true
    }

    internal fun applyInitialContentSynchronously(content: String): Boolean {
        input.content = content
        val cacheKey = currentCacheKey()
        val (cached, cacheHit) =
            try {
                MarkdownParseResultCache.getOrPut(cacheKey) {
                    parseContent(content)
                }
            } catch (error: Throwable) {
                return false
            }
        raftMarkdownLogSubTrace {
            "phase=markdown.state.initial_apply " +
                "hash=${content.hashCode().toString(16)} length=${content.length} " +
                "cacheHit=$cacheHit nodeCount=${cached.nodes.size} before=${nodeListState.size}"
        }
        applyParseResult(cached)
        return cacheHit
    }

    suspend fun parse(content: String, immediate: Boolean): Boolean {
        raftMarkdownLogSubTrace {
            "phase=markdown.state.parse_request " +
                "hash=${content.hashCode().toString(16)} length=${content.length} " +
                "immediate=$immediate currentHash=${input.content.hashCode().toString(16)} " +
                "currentLength=${input.content.length} nodeListSize=${nodeListState.size}"
        }
        if (content.length < input.content.length) {
            nodeListState.clear()
        }
        input.content = content
        val cacheKey = currentCacheKey()
        if (applyCachedParseIfAvailable()) {
            return true
        }
        if (immediate) {
            val (cached, cacheHit) =
                mutex.withLock {
                    parseBlocking(cacheKey, content)
                } ?: return false
            applyParseResult(cached)
            return cacheHit
        } else {
            return parse()
        }
    }


    /**
     * Parses the markdown content asynchronously using the MarkdownParse dispatcher.
     * When a result is available it will be emitted to the [state] flow.
     */
    suspend fun parse(): Boolean {
        val content = input.content
        val cacheKey = currentCacheKey()
        MarkdownParseResultCache.get(cacheKey)?.let { cached ->
            raftMarkdownLogSubTrace {
                "phase=markdown.state.async_cache_hit " +
                    "hash=${content.hashCode().toString(16)} length=${content.length} nodeCount=${cached.nodes.size}"
            }
            applyParseResult(cached)
            return true
        }
        raftMarkdownLogSubTrace {
            "phase=markdown.state.async_parse_start " +
                "hash=${content.hashCode().toString(16)} length=${content.length}"
        }
        val parsed =
            try {
                withContext(RaftMarkdownParseDispatcher) {
                    mutex.withLock {
                        parseContent(content)
                    }
                }
            } catch (error: Throwable) {
                State.Error(error, input.referenceLinkHandler)
                null
            } ?: return false
        val (cached, cacheHit) = MarkdownParseResultCache.putIfAbsent(cacheKey, parsed)
        raftMarkdownLogSubTrace {
            "phase=markdown.state.async_parse_end " +
                "hash=${content.hashCode().toString(16)} length=${content.length} " +
                "cacheHit=$cacheHit nodeCount=${cached.nodes.size}"
        }
        applyParseResult(cached)
        return cacheHit
    }

    private suspend fun applyCachedParseIfAvailable(): Boolean =
        mutex.withLock {
            val cached =
                MarkdownParseResultCache.get(
                    MarkdownParseResultCacheKey(input.content, input.lookupLinks)
                ) ?: return@withLock false
            applyParseResult(cached)
            true
        }

    private fun currentCacheKey(): MarkdownParseResultCacheKey =
        MarkdownParseResultCacheKey(input.content, input.lookupLinks)

    /**
     * Parses the markdown content synchronously.
     */
    private fun parseBlocking(
        key: MarkdownParseResultCacheKey,
        content: String
    ): Pair<MarkdownCachedParseResult, Boolean>? =
        try {
            MarkdownParseResultCache.getOrPut(
                key = key
            ) {
                parseContent(content)
            }
        } catch (error: Throwable) {
            State.Error(error, input.referenceLinkHandler)
            null
        }

    private fun parseContent(content: String): MarkdownCachedParseResult =
        parseMarkdownContent(
            content = content,
            lookupLinks = input.lookupLinks,
            parser = input.parser
        )

    private fun applyParseResult(cached: MarkdownCachedParseResult) {
        val beforeSize = nodeListState.size
        if (input.lookupLinks) {
            cached.links.onEach { (key, value) -> input.referenceLinkHandler.store(key, value) }
            linkStateFlow.value = cached.links
        }

        cached.nodes
            .forEachIndexed { index, result ->
                if (index < nodeListState.size) {
                    nodeListState.set(index, result)
                } else {
                    nodeListState.add(result)
                }
            }

        if (cached.nodes.size < nodeListState.size) {
            nodeListState.removeRange(cached.nodes.size, nodeListState.size)
        }
        raftMarkdownLogSubTrace {
            "phase=markdown.state.apply_result " +
                "hash=${input.content.hashCode().toString(16)} length=${input.content.length} " +
                "before=$beforeSize after=${nodeListState.size} nodeCount=${cached.nodes.size} links=${cached.links.size}"
        }
    }
}

private fun parseMarkdownContent(
    content: String,
    lookupLinks: Boolean,
    parser: MarkdownParser
): MarkdownCachedParseResult {
    val parsedResult: ASTNode = parser.buildMarkdownTreeFromString(content)
    val links =
        if (lookupLinks) {
            mutableMapOf<String, String?>().also { values ->
                lookupLinkDefinition(values, parsedResult, content, recursive = true)
            }
        } else {
            emptyMap()
        }
    return MarkdownCachedParseResult(
        links = links,
        inlineRefs = RaftMarkdownDialect.inlineRefs(parsedResult, content),
        inlineCodeSpans = parsedResult.markdownInlineCodeSpans(content),
        nodes =
            parsedResult.children.map {
                ParseResult(it.startOffset, it.endOffset).apply { this.node = it }
            }
    )
}

private data class MarkdownParseResultCacheKey(
    val content: String,
    val lookupLinks: Boolean
)

private data class MarkdownCachedParseResult(
    val links: Map<String, String?>,
    val inlineRefs: List<RaftMarkdownInlineRef>,
    val inlineCodeSpans: List<MarkdownInlineCodeSpan>,
    val nodes: List<ParseResult>
)

private fun ASTNode.markdownInlineCodeSpans(content: String): List<MarkdownInlineCodeSpan> =
    buildList {
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.CODE_SPAN) {
                val sourceText = content.substring(node.startOffset, node.endOffset)
                val markerLength = sourceText.takeWhile { it == '`' }.length
                val rawCode =
                    if (markerLength > 0 && sourceText.length >= markerLength * 2) {
                        sourceText.substring(markerLength, sourceText.length - markerLength)
                    } else {
                        sourceText
                    }
                val normalizedCode =
                    rawCode
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .replace('\n', ' ')
                val codeText =
                    if (
                        normalizedCode.length >= 2 &&
                        normalizedCode.first() == ' ' &&
                        normalizedCode.last() == ' ' &&
                        normalizedCode.any { it != ' ' }
                    ) {
                        normalizedCode.substring(1, normalizedCode.lastIndex)
                    } else {
                        normalizedCode
                    }
                add(
                    MarkdownInlineCodeSpan(
                        sourceText = sourceText,
                        codeText = codeText,
                        startOffset = node.startOffset,
                        endOffset = node.endOffset,
                    ),
                )
                return
            }
            node.children.forEach(::collect)
        }
        collect(this@markdownInlineCodeSpans)
    }

private object MarkdownParseResultCache {
    // #192 / #195: increased from 256 → 1024 to reduce cold-parse jank
    // on chat scroll. With 256, scrolling past ~10 long-body messages evicts
    // the oldest entry and triggers a 1ms+ re-parse on next viewport entry.
    // 1024 is large enough to hold a typical chat scroll backlog of
    // 200-400 messages (only the long-body ones are expensive, and only
    // ~50% of messages in a typical chat exceed 200 chars).
    private const val MaxEntries = 1024
    private val entries = LinkedHashMap<MarkdownParseResultCacheKey, MarkdownCachedParseResult>()

    /**
     * Thread-safety note: this cache is shared across visible MarkdownState rows,
     * so callers must only access it from the Kuikly/Compose coroutine thread.
     * Expensive parse work may run off-thread, but cache lookup and mutation must
     * stay outside the parse dispatcher.
     *
     * We deliberately do NOT wrap the body in `synchronized(...)` here because:
     * 1. JVM `synchronized(Any)` does not exist on Kotlin/Native (OHOS target) —
     *    it would break OHOS shared compile with "Unresolved reference 'synchronized'".
     * 2. `kotlinx.atomicfu.locks` / `kotlin.concurrent.locks` would add a dependency
     *    for a lock the parent [MarkdownState.mutex] already provides.
     * 3. The earlier Android-only experiment using `synchronized(LinkedHashMap)`
     *    regressed real-device jank 8% → 22% (GPU p99 21ms → 4950ms) and was reverted.
     *
     * If a future change needs to allow concurrent cache access, use a portable
     * cross-platform lock; do not re-add JVM-only `synchronized`.
     */
    fun get(key: MarkdownParseResultCacheKey): MarkdownCachedParseResult? =
        entries[key]

    fun getOrPut(
        key: MarkdownParseResultCacheKey,
        value: () -> MarkdownCachedParseResult
    ): Pair<MarkdownCachedParseResult, Boolean> {
        entries[key]?.let { cached ->
            return cached to true
        }

        val parsed = value()

        entries[key]?.let { cached ->
            return cached to true
        }
        entries[key] = parsed
        if (entries.size > MaxEntries) {
            val firstKey = entries.keys.firstOrNull()
            if (firstKey != null) {
                entries.remove(firstKey)
            }
        }
        return parsed to false
    }

    fun putIfAbsent(
        key: MarkdownParseResultCacheKey,
        value: MarkdownCachedParseResult
    ): Pair<MarkdownCachedParseResult, Boolean> {
        entries[key]?.let { cached ->
            return cached to true
        }
        entries[key] = value
        if (entries.size > MaxEntries) {
            val firstKey = entries.keys.firstOrNull()
            if (firstKey != null) {
                entries.remove(firstKey)
            }
        }
        return value to false
    }
}

/**
 * The input for the [MarkdownState].
 *
 * @param content The markdown content to parse.
 * @param lookupLinks Whether to lookup links in the parsed tree or not.
 * @param flavour The [MarkdownFlavourDescriptor] to use for parsing.
 * @param parser The [MarkdownParser] to use for parsing.
 * @param referenceLinkHandler The [ReferenceLinkHandler] to use for storing links.
 */
data class Input(
    var content: String,
    val lookupLinks: Boolean,
    val shareModel: Boolean,
    val flavour: MarkdownFlavourDescriptor,
    val parser: MarkdownParser,
    val referenceLinkHandler: ReferenceLinkHandler,
)

/**
 * The current state of the [MarkdownState].
 */
@Stable
sealed interface State {

    /** The [ReferenceLinkHandler] to store links in. */
    val referenceLinkHandler: ReferenceLinkHandler

    /** The parsing is in-progress. */
//    data class Loading(
//        override val referenceLinkHandler: ReferenceLinkHandler,
//    ) : State

    /** The parsing was successful. */
    data class Success(
        val node: SnapshotStateList<ParseResult>,
        val content: String,
        val linksLookedUp: Boolean,
        val cost: Long,
        override val referenceLinkHandler: ReferenceLinkHandler,
    ) : State

    /** The parsing failed due to [Throwable]. */
    data class Error(
        val result: Throwable,
        override val referenceLinkHandler: ReferenceLinkHandler,
    ) : State
}

@Stable
data class ParseResult(
    val startIndex: Int,
    val endIndex: Int
) {
    lateinit var node: ASTNode
}
