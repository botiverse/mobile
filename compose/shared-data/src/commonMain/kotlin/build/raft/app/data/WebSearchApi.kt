package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import build.raft.app.reportProjectionRowDropped
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Core's search across first-party apps, `GET /api/search?q=&app=&cursor=&limit=` (botiverse/slock
 * staging 93207e216; contract `packages/shared/src/webSearchContract.ts` `WebSearchResponse`,
 * `WebSearchGroup`, `WebSearchItemsByApp`, `webSearchPath`; the Artifacts items are
 * `webArtifactsContract.ts` `ArtifactSearchHit` = `{ card: ArtifactCard, matches:
 * AgentApiArtifactSearchMatch[] }`; served by `packages/server/src/routes/search.ts` and
 * `apps/artifacts/webSearch.ts`; drawn by the web's `features/search/useAppSearchGroups.ts` and
 * `AppSearchGroupSection.tsx`).
 *
 * - In the reader's server (X-Server-Id). Without `app`: one group per app that searches and is on
 *   for the server (an app switched off has no group). With `app`: that app's group only, paged
 *   with `cursor` (the group's `nextCursor`, which continues only the same `q`).
 * - Answer `{ groups: [ { app, items, nextCursor } | { app, error: "timeout" | "failed" } ] }`: a
 *   group that took too long or failed says so; the other groups are answered regardless.
 * - Refusals `{ error, code }`: 400 INVALID_QUERY / INVALID_REQUEST / INVALID_CURSOR, 404
 *   APP_NOT_FOUND (with `app`: unknown or switched off), 429 RATE_LIMITED.
 *
 * Only the Artifacts group is read here (the only app the contract has); a group of an app this
 * client doesn't know is left out, with a trace.
 */
@Serializable
data class WebSearchArtifactsPageWireDto(
    val app: String,
    val items: List<JsonElement>,
    val nextCursor: String? = null,
)

@Serializable
data class WebSearchGroupErrorWireDto(
    val app: String,
    val error: String,
)

/** `ArtifactSearchHit`. */
@Serializable
data class ArtifactSearchHitWireDto(
    val card: ArtifactCardWireDto,
    val matches: List<ArtifactSearchMatchWireDto> = emptyList(),
)

/** `AgentApiArtifactSearchMatch`: where the words were found (search reads titles only today). */
@Serializable
data class ArtifactSearchMatchWireDto(
    val field: String,
    val path: String? = null,
    val snippet: String,
    val highlights: List<ArtifactSearchHighlightWireDto> = emptyList(),
)

@Serializable
data class ArtifactSearchHighlightWireDto(
    val start: Int,
    val length: Int,
)

/** web `WEB_SEARCH_QUERY_MAX_CHARS`: the longest `q` in code points, after trimming (else 400). */
const val WEB_SEARCH_QUERY_MAX_CHARS: Int = 200

/** web `WEB_SEARCH_MAX_LIMIT`: the most results one group holds a page. */
const val WEB_SEARCH_MAX_LIMIT: Int = 50

/** The Artifacts app's id (`WebSearchItemsByApp.artifacts`). */
const val WEB_SEARCH_APP_ARTIFACTS: String = "artifacts"

/** One Artifact found: its card as the Artifacts home page reads it, and where the words matched. */
data class SearchArtifactHit(
    val row: ArtifactsHomeRow,
    val matches: List<ArtifactSearchMatch> = emptyList(),
) {
    val id: String get() = row.summary.id
}

data class ArtifactSearchMatch(
    val field: String,
    val snippet: String,
    val highlights: List<IntRange>,
)

/** The Artifacts group of one answer. */
sealed interface WebSearchArtifactsGroup {
    data class Page(val hits: List<SearchArtifactHit>, val nextCursor: String?) : WebSearchArtifactsGroup

    /** `{ error }`: [timedOut] for `"timeout"`, else it failed (web: anything else reads as `"failed"`). */
    data class Failed(val timedOut: Boolean) : WebSearchArtifactsGroup
}

/** One answer as this client reads it: the Artifacts group, or null when the answer has none. */
data class WebSearchAnswer(val artifacts: WebSearchArtifactsGroup?)

private val webSearchJson = Json { ignoreUnknownKeys = true }

/** web `webSearchPath`: `q`, then `app`, `cursor` and `limit` only when given. */
@RaftCoreInternalApi fun webSearchQueryParams(q: String, app: String? = null, cursor: String? = null, limit: Int? = null): List<Pair<String, String>> =
    buildList {
        add("q" to q)
        app?.let { add("app" to it) }
        cursor?.takeIf { it.isNotEmpty() }?.let { add("cursor" to it) }
        limit?.let { add("limit" to it.toString()) }
    }

/** web `searchable`: words to search for, of at most [WEB_SEARCH_QUERY_MAX_CHARS] code points. */
@RaftCoreInternalApi fun webSearchQueryOrNull(raw: String): String? {
    val q = raw.trim()
    if (q.isEmpty()) return null
    val codePoints = q.length - q.count { it.isLowSurrogate() }
    return q.takeIf { codePoints <= WEB_SEARCH_QUERY_MAX_CHARS }
}

/**
 * Reads one answer of `GET /api/search`; null when it can't be read at all. Items decode one by
 * one: an unreadable one (or one whose card has a home this client can't read) is left out with
 * a trace; a group whose every item was unreadable is a failed group, not "nothing found".
 */
fun parseWebSearchAnswer(bodyText: String?): WebSearchAnswer? {
    val root = bodyText?.let { runCatching { webSearchJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
    val groups = root["groups"] as? JsonArray ?: return null
    var artifacts: WebSearchArtifactsGroup? = null
    groups.forEachIndexed { index, element ->
        val group = element as? JsonObject
        val app = (group?.get("app") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (group == null || app == null) {
            reportProjectionRowDropped("web_search_group", "decode_failed", index)
            return@forEachIndexed
        }
        if (app != WEB_SEARCH_APP_ARTIFACTS) {
            reportUnknownTypeDropped("web_search_group", "app", app)
            return@forEachIndexed
        }
        artifacts = readArtifactsGroup(group, index) ?: return@forEachIndexed
    }
    return WebSearchAnswer(artifacts)
}

private fun readArtifactsGroup(group: JsonObject, index: Int): WebSearchArtifactsGroup? {
    if (group["items"] is JsonArray) {
        val page =
            runCatching { webSearchJson.decodeFromJsonElement(WebSearchArtifactsPageWireDto.serializer(), group) }.getOrNull()
                ?: run {
                    reportProjectionRowDropped("web_search_group", "decode_failed", index)
                    return null
                }
        val hits = page.items.mapIndexedNotNull { itemIndex, item -> item.toSearchArtifactHitOrNull(itemIndex) }
        if (page.items.isNotEmpty() && hits.isEmpty()) return WebSearchArtifactsGroup.Failed(timedOut = false)
        return WebSearchArtifactsGroup.Page(hits = hits, nextCursor = page.nextCursor?.takeIf { it.isNotEmpty() })
    }
    val failed =
        runCatching { webSearchJson.decodeFromJsonElement(WebSearchGroupErrorWireDto.serializer(), group) }.getOrNull()
            ?: run {
                reportProjectionRowDropped("web_search_group", "decode_failed", index)
                return null
            }
    return when (failed.error) {
        "timeout" -> WebSearchArtifactsGroup.Failed(timedOut = true)
        "failed" -> WebSearchArtifactsGroup.Failed(timedOut = false)
        else -> {
            reportUnknownTypeDropped("web_search_group", "error", failed.error, fallback = "failed")
            WebSearchArtifactsGroup.Failed(timedOut = false)
        }
    }
}

private fun JsonElement.toSearchArtifactHitOrNull(index: Int): SearchArtifactHit? {
    val hit =
        runCatching { webSearchJson.decodeFromJsonElement(ArtifactSearchHitWireDto.serializer(), this) }.getOrNull()
            ?: run {
                reportProjectionRowDropped("web_search_artifact", "decode_failed", index)
                return null
            }
    // A home this client can't read is reported where the card is read.
    val summary = hit.card.toArtifactSummaryOrNull() ?: return null
    return SearchArtifactHit(
        row =
            ArtifactsHomeRow(
                summary = summary,
                author = hit.card.author.toArtifactsHomeActor(),
                openedAt = null,
                news = ArtifactsHomeNews(),
                fromAnotherServer = hit.card.fromAnotherServer == true,
            ),
        matches =
            hit.matches.map { match ->
                ArtifactSearchMatch(
                    field = match.field,
                    snippet = match.snippet,
                    highlights = match.highlights.filter { it.start >= 0 && it.length > 0 }.map { it.start until it.start + it.length },
                )
            },
    )
}

/** One read of the Artifacts group, as the store takes it. */
sealed interface WebSearchArtifactsLoad {
    data class Page(val hits: List<SearchArtifactHit>, val nextCursor: String?) : WebSearchArtifactsLoad

    /** The answer's group says it took too long or failed; [error] carries the answer's trace id. */
    data class GroupFailed(val timedOut: Boolean, val error: ScreenError) : WebSearchArtifactsLoad

    /**
     * Nothing to show and nothing to say: the answer has no Artifacts group (switched off for the
     * server), 404 APP_NOT_FOUND, or a 400 (a server that reads the words differently; web: "no
     * groups, no alarm").
     */
    data object NoGroup : WebSearchArtifactsLoad

    /** No answer at all: offline, 429, a server error, an unreadable body. */
    data class Failed(val error: ScreenError) : WebSearchArtifactsLoad
}

@RaftCoreInternalApi interface RaftWebSearchApi {
    suspend fun search(
        serverId: String,
        q: String,
        app: String?,
        cursor: String?,
        limit: Int?,
        priority: DataRequestPriority,
    ): HomeNetworkResponse
}

@RaftCoreInternalApi class NetworkRaftWebSearchApi(private val networkClient: RaftNetworkClient) : RaftWebSearchApi {
    override suspend fun search(
        serverId: String,
        q: String,
        app: String?,
        cursor: String?,
        limit: Int?,
        priority: DataRequestPriority,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "search",
            query = webSearchQueryParams(q, app, cursor, limit),
            serverIdOverride = ServerId(serverId),
        )
}

@RaftCoreInternalApi fun HomeNetworkResponse.toWebSearchArtifactsLoad(): WebSearchArtifactsLoad {
    if (isSuccessful) {
        val answer =
            parseWebSearchAnswer(bodyText)
                ?: return WebSearchArtifactsLoad.Failed(ScreenError("web_search_unreadable", retryable = true, traceId = traceId))
        return when (val group = answer.artifacts) {
            null -> WebSearchArtifactsLoad.NoGroup
            is WebSearchArtifactsGroup.Page -> WebSearchArtifactsLoad.Page(group.hits, group.nextCursor)
            is WebSearchArtifactsGroup.Failed ->
                WebSearchArtifactsLoad.GroupFailed(
                    timedOut = group.timedOut,
                    error =
                        ScreenError(
                            if (group.timedOut) "web_search_group_timeout" else "web_search_group_failed",
                            retryable = true,
                            traceId = traceId,
                        ),
                )
        }
    }
    if (code == 400 || code == 404) return WebSearchArtifactsLoad.NoGroup
    val envelope = parseHttpErrorEnvelope()
    return WebSearchArtifactsLoad.Failed(
        ScreenError(
            code = "web_search_http_$code",
            retryable = true,
            serverMessage = envelope?.serverMessage,
            serverCode = envelope?.serverCode,
            traceId = traceId,
        ),
    )
}

/** The store's reads of the Artifacts group (a test hands answers directly). */
@RaftCoreInternalApi fun interface SearchArtifactsSource {
    suspend fun search(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority): WebSearchArtifactsLoad
}

@RaftCoreInternalApi fun RaftWebSearchApi.asSearchArtifactsSource(): SearchArtifactsSource {
    val api = this
    return SearchArtifactsSource { serverId, q, cursor, limit, priority ->
        // The group alone (`app=artifacts`): the web asks for every app, but this client draws only
        // Artifacts, and a cursor needs `app` anyway.
        runCatchingPreservingCancellation { api.search(serverId, q, WEB_SEARCH_APP_ARTIFACTS, cursor, limit, priority) }
            .fold(
                { it.toWebSearchArtifactsLoad() },
                { WebSearchArtifactsLoad.Failed(ScreenError("web_search_network", retryable = true, serverMessage = it.message, traceId = null)) },
            )
    }
}
