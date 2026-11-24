package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ResolvedInlineQuote
import build.raft.app.model.ServerId
import build.raft.app.reportProjectionRowDropped
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * The quotes of a quote reply as the server resolved them for this viewer (botiverse/slock staging
 * b0b65a4d1263c9bd39adf28a2f2553c5bacb1913, `packages/server/src/services/quoteReplyService.ts`
 * `resolveInlineQuotesMapForViewer`, `packages/shared/src/quoteReply.ts` `ResolvedInlineQuotesMap`):
 *
 *  - On reads, each message row carries `resolvedQuotes: { "<blockIndex>": { messageId, shortId,
 *    author: { name, type }, createdAt, isPartial } }`, only when at least one quote resolved
 *    (`routes/messages.ts`: `GET /messages/channel/:id`, `/messages/context/:id`, `/messages/sync`,
 *    and the sender's own `POST /messages` / `POST /v2/messages` answer). A read without it resolved
 *    none: every quote block shows as a plain blockquote.
 *  - Realtime broadcasts and the sync log's message rows never carry it (per viewer: history windows
 *    and visibility differ), so such a copy reads its quotes from `GET /messages/:messageId/resolved-quotes`
 *    → `{ resolvedQuotes }` (`{}` when none resolved; 404 for a message this viewer can't see).
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */

private val resolvedQuotesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireResolvedInlineQuoteAuthor(
    val name: String = "",
    val type: String = "",
)

@Serializable
private data class WireResolvedInlineQuote(
    val messageId: String = "",
    val shortId: String = "",
    val author: WireResolvedInlineQuoteAuthor? = null,
    val createdAt: String = "",
    val isPartial: Boolean = false,
)

@Serializable
private data class WireResolvedQuotesResponse(
    val resolvedQuotes: JsonElement? = null,
)

/**
 * A message row's `resolvedQuotes`, entry by entry: an entry that doesn't read (no block index, no
 * quoted message) is dropped and logged, the others kept. [absentMeansNone]: the row came from a read
 * that attaches them, so a row without the field resolved none (an empty map); otherwise (a copy
 * that never carries them) its quotes are unknown (null).
 */
@RaftCoreInternalApi fun decodeResolvedInlineQuotes(
    element: JsonElement?,
    absentMeansNone: Boolean,
    source: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Map<String, ResolvedInlineQuote>? {
    val entries = element as? JsonObject
    if (entries == null) {
        if (element != null && element !is JsonNull) reportProjectionRowDropped(source, "not_object", diagnostics = diagnostics)
        return if (absentMeansNone) emptyMap() else null
    }
    return buildMap {
        for ((key, value) in entries) {
            val blockIndex = key.toIntOrNull()?.takeIf { it >= 0 }
            val wire =
                (value as? JsonObject)?.let { row ->
                    runCatching { resolvedQuotesJson.decodeFromJsonElement(WireResolvedInlineQuote.serializer(), row) }.getOrNull()
                }
            val messageId = wire?.messageId?.trim().orEmpty()
            if (blockIndex == null || wire == null || messageId.isEmpty()) {
                reportProjectionRowDropped(source, "decode_failed", blockIndex, diagnostics)
                continue
            }
            put(
                blockIndex.toString(),
                ResolvedInlineQuote(
                    messageId = messageId,
                    shortId = wire.shortId.trim().lowercase().ifEmpty { messageId.take(8).lowercase() },
                    authorName = wire.author?.name.orEmpty(),
                    authorType = wire.author?.type.orEmpty(),
                    createdAt = wire.createdAt,
                    isPartial = wire.isPartial,
                ),
            )
        }
    }
}

/** `GET /messages/:messageId/resolved-quotes` → its quotes by block index (empty: none resolved); null when the answer doesn't read. */
@RaftCoreInternalApi fun parseSharedResolvedQuotes(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Map<String, ResolvedInlineQuote>? {
    val response =
        runCatching { resolvedQuotesJson.decodeFromString(WireResolvedQuotesResponse.serializer(), bodyText) }.getOrNull()
            ?: return null
    return decodeResolvedInlineQuotes(response.resolvedQuotes, absentMeansNone = true, source = "rest_resolved_quotes", diagnostics = diagnostics)
}

@RaftCoreInternalApi interface RaftResolvedQuotesApi {
    /** `GET messages/:messageId/resolved-quotes`, as this viewer on [serverId]. */
    suspend fun resolvedQuotes(serverId: String, messageId: String): HomeNetworkResponse
}

@RaftCoreInternalApi class NetworkRaftResolvedQuotesApi(
    private val networkClient: RaftNetworkClient,
) : RaftResolvedQuotesApi {
    override suspend fun resolvedQuotes(serverId: String, messageId: String): HomeNetworkResponse =
        networkClient.request(
            // An on-screen message's own decoration, like its link previews.
            priority = DataRequestPriority.Visible,
            path = "messages/$messageId/resolved-quotes",
            serverIdOverride = ServerId(serverId),
        )
}

/** web `MAX_HYDRATED_ENTRIES`: the most messages whose quotes are kept. */
@RaftCoreInternalApi const val RESOLVED_QUOTES_MAX_ENTRIES: Int = 250

/** By [resolvedQuotesKey]: the quotes the server resolved for one message's words (empty: none). */
data class SharedResolvedQuotesState(
    val entries: Map<String, Map<String, ResolvedInlineQuote>> = emptyMap(),
    /** Moves on [SharedResolvedQuotesStore.clearAll]: an answer asked for before it is not kept. */
    val generation: Int = 0,
)

/** Per viewer Server, message and its words: an edited message reads its quotes again. */
@RaftCoreInternalApi fun resolvedQuotesKey(serverId: String, messageId: String, content: String): String =
    "$serverId\u0000$messageId\u0000${content.length}:${content.hashCode()}"

/**
 * The quotes of the quote replies on screen that came without them (web `resolvedQuotesHydrationStore`):
 * a live message (`message:new`, `message:updated`) or one restored from the sync log. Each message
 * with a same-conversation quote line ([hasSameConversationQuoteRef]) is read once per Server and
 * words; the answer, `{}` included, is kept in memory for this viewer (no-store, per viewer), the
 * oldest dropped past [maxEntries], and all of it on [clearAll] (sign-out, account or environment
 * switch). A 404 (a message this viewer can't see) keeps "none"; any other failure keeps nothing,
 * so the message asks again the next time it shows. Meanwhile its quotes show as plain blockquotes.
 */
class SharedResolvedQuotesStore @RaftCoreInternalApi constructor(
    private val api: RaftResolvedQuotesApi,
    private val scope: CoroutineScope,
    private val maxEntries: Int = RESOLVED_QUOTES_MAX_ENTRIES,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SharedResolvedQuotesState())
    val state: StateFlow<SharedResolvedQuotesState> = mutableState.asStateFlow()

    private val lock = SynchronizedObject()

    /** Asked and not answered yet, by [resolvedQuotesKey]. */
    private val inFlight = mutableSetOf<String>()

    /** The quotes read for [messageId] with [content] in [serverId]; null while not read (yet). */
    fun quotesFor(serverId: String, messageId: String, content: String): Map<String, ResolvedInlineQuote>? =
        mutableState.value.entries[resolvedQuotesKey(serverId, messageId, content)]

    /** [quotesFor], emitted only when it changes: what a message row collects. */
    fun quotesFlow(serverId: String, messageId: String, content: String): Flow<Map<String, ResolvedInlineQuote>?> {
        val key = resolvedQuotesKey(serverId, messageId, content)
        return state.map { it.entries[key] }.distinctUntilChanged()
    }

    /** Reads [messageId]'s quotes unless they are read already or on their way; nothing for a message without a quote line. */
    fun request(serverId: String, messageId: String, content: String) {
        if (serverId.isBlank() || !isQuoteReplyMessageId(messageId) || !hasSameConversationQuoteRef(content)) return
        val key = resolvedQuotesKey(serverId, messageId, content)
        if (key in mutableState.value.entries) return
        if (!synchronized(lock) { inFlight.add(key) }) return
        val generation = mutableState.value.generation
        scope.launch {
            try {
                read(serverId, messageId, key, generation)
            } finally {
                synchronized(lock) { inFlight.remove(key) }
            }
        }
    }

    /** Drops every answer (sign-out, account or environment switch). An answer on its way is not kept. */
    fun clearAll() {
        synchronized(lock) { inFlight.clear() }
        mutableState.update { SharedResolvedQuotesState(generation = it.generation + 1) }
    }

    private suspend fun read(serverId: String, messageId: String, key: String, generation: Int) {
        val response =
            try {
                api.resolvedQuotes(serverId, messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                diagnosticsLogger("resolved_quotes_error message=${messageId.take(8)} error=${error::class.simpleName}")
                return
            }
        val quotes: Map<String, ResolvedInlineQuote>? =
            when {
                response.isSuccessful -> response.bodyText?.let { parseSharedResolvedQuotes(it, diagnosticsLogger) }
                // Not a message this viewer can see: its quotes never resolve here.
                response.code == 404 -> emptyMap()
                else -> null
            }
        if (quotes == null) {
            diagnosticsLogger("resolved_quotes_failed message=${messageId.take(8)} code=${response.code}")
            return
        }
        mutableState.update { state ->
            if (state.generation != generation) return@update state
            val entries = LinkedHashMap(state.entries)
            entries.remove(key)
            entries[key] = quotes
            while (entries.size > maxEntries) entries.remove(entries.keys.first())
            state.copy(entries = entries)
        }
    }
}
