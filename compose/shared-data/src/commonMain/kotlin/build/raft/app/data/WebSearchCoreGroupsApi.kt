package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportProjectionRowDropped
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Core's own groups of global search, `GET /api/search?q=&app=channels|people&cursor=&limit=`
 * (botiverse/slock staging df474620 + #15387; contract `packages/shared/src/webSearchContract.ts`
 * `ChannelSearchHit`, `PersonSearchHit`, `WebSearchCoreGroup`, `WEB_SEARCH_CORE_DEFAULT_LIMIT`;
 * `docs/contracts/web-search.md`; served by `packages/server/src/services/coreSearchService.ts`;
 * drawn by the web's `features/search/useCoreSearchGroups.ts` and `searchEntities.ts`
 * `composeSearchEntityGroups`).
 *
 * - The server decides which channels and people a query finds, in which order and with which
 *   status (#proj-mobile:#177): this client draws them as they come, never filters or re-ranks.
 * - `app=channels` / `app=people`: that group alone, `{ groups: [ { app, items, nextCursor, total } |
 *   { app, error: "timeout" | "failed" } ] }`. A page holds 10 without `limit`
 *   (`WEB_SEARCH_CORE_DEFAULT_LIMIT`), at most 50; `nextCursor` pages on; `total` counts every match.
 * - Status fields are additive only: unknown ones are ignored, an unknown `type`, `kind` or
 *   `requiredAction` value is reported ([reportUnknownTypeDropped]).
 * - A server that predates the groups answers 404 APP_NOT_FOUND: [WebSearchCoreGroupLoad.Unsupported]
 *   (the local rows stay, without a failure note).
 */

/** `WebSearchCoreGroupId`: the groups core searches itself. */
enum class SearchCoreGroup(val wire: String) {
    Channels("channels"),
    People("people"),
}

/** `WebSearchCoreGroup` page: `{ app, items, nextCursor, total }`. */
@Serializable
data class WebSearchCorePageWireDto(
    val app: String,
    val items: List<JsonElement>,
    val nextCursor: String? = null,
    val total: Int,
)

/** `ChannelSearchHit`. */
@Serializable
data class ChannelSearchHitWireDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String,
    val archived: Boolean,
    val joined: Boolean,
    val external: Boolean,
    val guest: Boolean,
    val canPost: Boolean,
    val requiredAction: String? = null,
)

/** `PersonSearchHit`. */
@Serializable
data class PersonSearchHitWireDto(
    val kind: String,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val guest: Boolean,
    val dmChannelId: String? = null,
    val canPost: Boolean,
    val requiredAction: String? = null,
)

/** `WebSearchRequiredAction`: what the reader has to do before posting there. */
enum class SearchRequiredAction(val wire: String) {
    JoinChannel("join_channel"),
    CreateDm("create_dm"),
}

/** `ChannelSearchHit` as this client draws it. [type] is the wire type (`channel` / `private` / `joint`). */
data class SearchChannelHit(
    val id: String,
    val name: String,
    val description: String?,
    val type: String,
    val archived: Boolean,
    val joined: Boolean,
    val external: Boolean,
    val guest: Boolean,
    val canPost: Boolean,
    val requiredAction: SearchRequiredAction?,
)

/** `PersonSearchHit` as this client draws it. */
data class SearchPersonHit(
    val agent: Boolean,
    val id: String,
    val name: String,
    val displayName: String?,
    val avatarUrl: String?,
    val role: String?,
    val guest: Boolean,
    val dmChannelId: String?,
    val canPost: Boolean,
    val requiredAction: SearchRequiredAction?,
) {
    /** web `itemKey`: `kind:id` (a human and an agent may share an id). */
    val key: String get() = "${if (agent) "agent" else "human"}:$id"
}

/** One read of a core group. */
sealed interface WebSearchCoreGroupLoad<out T> {
    data class Page<T>(val hits: List<T>, val nextCursor: String?, val total: Int) : WebSearchCoreGroupLoad<T>

    /** The group took too long or failed, no answer at all, or an unreadable one; [error] carries the trace id. */
    data class Failed(val error: ScreenError) : WebSearchCoreGroupLoad<Nothing>

    /** The server has no such group (404 APP_NOT_FOUND: an older server). */
    data object Unsupported : WebSearchCoreGroupLoad<Nothing>
}

private val webSearchCoreJson = Json { ignoreUnknownKeys = true }

private const val ChannelTypeChannel = "channel"
private val KnownChannelTypes = setOf(ChannelTypeChannel, "private", "joint")

/**
 * Reads one answer of `GET /api/search?app=<group>` for [group]; null when it can't be read at all
 * or has no page for the group. Items decode one by one: an unreadable one is left out with a
 * trace; a page whose every item was unreadable is a failed group, not "nothing found".
 */
@RaftCoreInternalApi fun <T> parseWebSearchCoreGroup(
    bodyText: String?,
    group: SearchCoreGroup,
    readItem: (JsonElement, Int) -> T?,
): WebSearchCoreGroupLoad<T>? {
    val root = bodyText?.let { runCatching { webSearchCoreJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
    val groups = root["groups"] as? JsonArray ?: return null
    groups.forEachIndexed { index, element ->
        val entry = element as? JsonObject
        val app = (entry?.get("app") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (entry == null || app == null) {
            reportProjectionRowDropped("web_search_core_group", "decode_failed", index)
            return@forEachIndexed
        }
        if (app != group.wire) {
            reportUnknownTypeDropped("web_search_core_group", "app", app)
            return@forEachIndexed
        }
        if (entry["items"] !is JsonArray) {
            val error = (entry["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (error != "timeout" && error != "failed") {
                reportUnknownTypeDropped("web_search_core_group", "error", error.orEmpty(), fallback = "failed")
            }
            return WebSearchCoreGroupLoad.Failed(
                ScreenError(if (error == "timeout") "web_search_group_timeout" else "web_search_group_failed", retryable = true),
            )
        }
        val page =
            runCatching { webSearchCoreJson.decodeFromJsonElement(WebSearchCorePageWireDto.serializer(), entry) }.getOrNull()
                ?: run {
                    reportProjectionRowDropped("web_search_core_group", "decode_failed", index)
                    return null
                }
        val hits = page.items.mapIndexedNotNull { itemIndex, item -> readItem(item, itemIndex) }
        if (page.items.isNotEmpty() && hits.isEmpty()) {
            return WebSearchCoreGroupLoad.Failed(ScreenError("web_search_group_unreadable", retryable = true))
        }
        return WebSearchCoreGroupLoad.Page(hits = hits, nextCursor = page.nextCursor?.takeIf { it.isNotEmpty() }, total = page.total)
    }
    return null
}

@RaftCoreInternalApi fun readChannelSearchHit(element: JsonElement, index: Int): SearchChannelHit? {
    val hit =
        runCatching { webSearchCoreJson.decodeFromJsonElement(ChannelSearchHitWireDto.serializer(), element) }.getOrNull()
            ?: run {
                reportProjectionRowDropped("web_search_channel", "decode_failed", index)
                return null
            }
    // A type this client doesn't know is drawn as a generic channel.
    val type =
        hit.type.takeIf { it in KnownChannelTypes } ?: run {
            reportUnknownTypeDropped("web_search_channel", "type", hit.type, fallback = ChannelTypeChannel)
            ChannelTypeChannel
        }
    return SearchChannelHit(
        id = hit.id,
        name = hit.name,
        description = hit.description?.takeIf { it.isNotBlank() },
        type = type,
        archived = hit.archived,
        joined = hit.joined,
        external = hit.external,
        guest = hit.guest,
        canPost = hit.canPost,
        requiredAction = hit.requiredAction.toSearchRequiredAction("web_search_channel"),
    )
}

@RaftCoreInternalApi fun readPersonSearchHit(element: JsonElement, index: Int): SearchPersonHit? {
    val hit =
        runCatching { webSearchCoreJson.decodeFromJsonElement(PersonSearchHitWireDto.serializer(), element) }.getOrNull()
            ?: run {
                reportProjectionRowDropped("web_search_person", "decode_failed", index)
                return null
            }
    val agent =
        when (hit.kind) {
            "agent" -> true
            "human" -> false
            else -> {
                // Neither a person's nor an agent's page can open it: left out, with a trace.
                reportUnknownTypeDropped("web_search_person", "kind", hit.kind)
                return null
            }
        }
    return SearchPersonHit(
        agent = agent,
        id = hit.id,
        name = hit.name,
        displayName = hit.displayName?.takeIf { it.isNotBlank() },
        avatarUrl = hit.avatarUrl?.takeIf { it.isNotBlank() },
        role = hit.role,
        guest = hit.guest,
        dmChannelId = hit.dmChannelId?.takeIf { it.isNotBlank() },
        canPost = hit.canPost,
        requiredAction = hit.requiredAction.toSearchRequiredAction("web_search_person"),
    )
}

private fun String?.toSearchRequiredAction(source: String): SearchRequiredAction? {
    val value = this ?: return null
    return SearchRequiredAction.entries.firstOrNull { it.wire == value } ?: run {
        reportUnknownTypeDropped(source, "requiredAction", value)
        null
    }
}

@RaftCoreInternalApi fun <T> HomeNetworkResponse.toWebSearchCoreGroupLoad(
    group: SearchCoreGroup,
    readItem: (JsonElement, Int) -> T?,
): WebSearchCoreGroupLoad<T> {
    if (isSuccessful) {
        return when (val load = parseWebSearchCoreGroup(bodyText, group, readItem)) {
            null -> WebSearchCoreGroupLoad.Failed(ScreenError("web_search_unreadable", retryable = true, traceId = traceId))
            is WebSearchCoreGroupLoad.Failed -> WebSearchCoreGroupLoad.Failed(load.error.copy(traceId = traceId))
            else -> load
        }
    }
    val envelope = parseHttpErrorEnvelope()
    val serverCode = envelope?.serverCode
    if (code == 404 && serverCode == "APP_NOT_FOUND") return WebSearchCoreGroupLoad.Unsupported
    return WebSearchCoreGroupLoad.Failed(
        ScreenError(
            code = "web_search_http_$code",
            retryable = true,
            serverMessage = envelope?.serverMessage,
            serverCode = serverCode,
            traceId = traceId,
        ),
    )
}

/** The store's reads of core's groups (a test hands answers directly). */
@RaftCoreInternalApi interface SearchCoreGroupsSource {
    suspend fun channels(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority): WebSearchCoreGroupLoad<SearchChannelHit>

    suspend fun people(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority): WebSearchCoreGroupLoad<SearchPersonHit>
}

@RaftCoreInternalApi fun RaftWebSearchApi.asSearchCoreGroupsSource(): SearchCoreGroupsSource {
    val api = this
    return object : SearchCoreGroupsSource {
        override suspend fun channels(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority) =
            read(serverId, q, SearchCoreGroup.Channels, cursor, limit, priority, ::readChannelSearchHit)

        override suspend fun people(serverId: String, q: String, cursor: String?, limit: Int?, priority: DataRequestPriority) =
            read(serverId, q, SearchCoreGroup.People, cursor, limit, priority, ::readPersonSearchHit)

        private suspend fun <T> read(
            serverId: String,
            q: String,
            group: SearchCoreGroup,
            cursor: String?,
            limit: Int?,
            priority: DataRequestPriority,
            readItem: (JsonElement, Int) -> T?,
        ): WebSearchCoreGroupLoad<T> =
            runCatchingPreservingCancellation { api.search(serverId, q, group.wire, cursor, limit, priority) }
                .fold(
                    { it.toWebSearchCoreGroupLoad(group, readItem) },
                    { WebSearchCoreGroupLoad.Failed(ScreenError("web_search_network", retryable = true, serverMessage = it.message, traceId = null)) },
                )
    }
}
