package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Small thread-safe LRU (least-recently-used first out). Lookups move the entry to the newest end.
 */
internal class RaftMarkdownLruCache<K : Any, V : Any>(private val maxEntries: Int) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<K, V>()

    val size: Int get() = synchronized(lock) { entries.size }

    fun get(key: K): V? = synchronized(lock) {
        entries.remove(key)?.also { cached -> entries[key] = cached }
    }

    /** The value for [key] and whether it was already cached. [build] runs outside the lock. */
    fun getOrPut(key: K, build: () -> V): Pair<V, Boolean> {
        get(key)?.let { cached -> return cached to true }
        val built = build()
        return synchronized(lock) {
            entries.remove(key)?.let { cached ->
                entries[key] = cached
                return@synchronized cached to true
            }
            entries[key] = built
            while (entries.size > maxEntries.coerceAtLeast(1)) {
                entries.keys.firstOrNull()?.let(entries::remove) ?: break
            }
            built to false
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }
}

/**
 * Every input of [buildRaftRichTextDocumentFromMarkdownPlan]. The token plan carries the
 * RESULTS of the tag context (each tag's validity, highlight and resolved label, plus the
 * inline-content scope in its ids), so a members / mentions / channels / tasks change that
 * alters this body's chips yields a different plan and therefore a different key: stale chips
 * cannot be served. Theme, type scale and locale do not reach the document (it is plain data;
 * colours and sizes are applied later by the annotator and the renderer).
 */
internal data class RaftRichTextDocumentCacheKey(
    val source: String,
    val content: String,
    val tokenPlan: MarkdownInlineTokenPlan,
    val activeTagKey: String?,
    val activeActionId: String?,
    val activeActionIndicator: String,
)

/**
 * Process-wide caches for the pure, per-body work [RaftMarkdownBody] does before rendering.
 * `remember` is lost when a LazyColumn row leaves composition, so without these a tall row
 * scrolled back into view redid the HTML-literal escape and the rich-text document build on the
 * main thread. Both builds are pure functions of their keys, so sharing them is safe.
 */
internal object RaftMarkdownRenderInputCache {
    private const val MaxEntries = 256
    private val parserContents = RaftMarkdownLruCache<String, MarkdownParserContent>(MaxEntries)
    private val documents = RaftMarkdownLruCache<RaftRichTextDocumentCacheKey, RaftRichTextDocument>(MaxEntries)

    fun parserContent(content: String): MarkdownParserContent =
        parserContents.getOrPut(content) { MarkdownParserContent.from(content) }.first

    fun document(
        source: String,
        content: String,
        tokenPlan: MarkdownInlineTokenPlan,
        activeTagKey: String? = null,
        activeActionId: String? = null,
        activeActionIndicator: String = "",
    ): RaftRichTextDocument = documentWithHit(source, content, tokenPlan, activeTagKey, activeActionId, activeActionIndicator).first

    fun documentWithHit(
        source: String,
        content: String,
        tokenPlan: MarkdownInlineTokenPlan,
        activeTagKey: String? = null,
        activeActionId: String? = null,
        activeActionIndicator: String = "",
    ): Pair<RaftRichTextDocument, Boolean> =
        documents.getOrPut(
            RaftRichTextDocumentCacheKey(source, content, tokenPlan, activeTagKey, activeActionId, activeActionIndicator)
        ) {
            buildRaftRichTextDocumentFromMarkdownPlan(
                source = source,
                content = content,
                tokenPlan = tokenPlan,
                activeTagKey = activeTagKey,
                activeActionId = activeActionId,
                activeActionIndicator = activeActionIndicator
            )
        }

    internal val documentCount: Int get() = documents.size

    internal fun clearForTest() {
        parserContents.clear()
        documents.clear()
    }
}

/** The tag context a surface renders its bodies with (the same values it passes to [RaftMarkdownBody]). */
internal class RaftMarkdownDocumentWarmContext(
    val highlightedMentions: Set<String>,
    val isTagValid: (ThreadMarkdownTag) -> Boolean,
    val resolveTagLabel: ((ThreadMarkdownTag) -> String)?,
)

internal data class RaftMarkdownDocumentWarmRequest(
    val body: String,
    val inlineContentScope: String?,
)

internal data class RaftMarkdownDocumentWarmResult(
    val requestedCount: Int,
    val builtCount: Int,
    val cacheHitCount: Int,
)

/**
 * Builds the rich-text documents [RaftMarkdownBody] will ask for, on [Dispatchers.Default], so a
 * row entering the viewport finds them cached. Everything on this path is pure and thread-safe:
 * normalisation, the HTML-literal escape and the document build are pure functions; the token
 * planner and every cache here guard their maps with a lock; the tag validators / label
 * resolvers a surface passes are closures over immutable sets and maps. No Compose state is
 * written. Only rows with no active tag/action are warmed (the resting state).
 */
internal suspend fun warmRaftMarkdownRichTextDocuments(
    requests: List<RaftMarkdownDocumentWarmRequest>,
    context: RaftMarkdownDocumentWarmContext,
): RaftMarkdownDocumentWarmResult =
    withContext(Dispatchers.Default) {
        var built = 0
        var hits = 0
        requests.forEach { request ->
            ensureActive()
            val content = raftMarkdownPreparedBody(request.body)
            if (content.isBlank()) return@forEach
            val parserContent = RaftMarkdownRenderInputCache.parserContent(content).parserContent
            val plan =
                RaftMarkdownInlineTokenRegistry.plan(
                    parserContent,
                    context.highlightedMentions,
                    context.isTagValid,
                    resolveTagLabel = context.resolveTagLabel,
                    inlineContentScope = request.inlineContentScope
                )
            val (_, hit) =
                RaftMarkdownRenderInputCache.documentWithHit(
                    source = request.body,
                    content = parserContent,
                    tokenPlan = plan
                )
            if (hit) hits += 1 else built += 1
        }
        RaftMarkdownDocumentWarmResult(requestedCount = requests.size, builtCount = built, cacheHitCount = hits)
    }
