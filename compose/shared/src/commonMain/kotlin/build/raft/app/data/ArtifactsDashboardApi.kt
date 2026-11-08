package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Artifacts home page's reads (botiverse/slock staging aae2b7a89; contract
 * `packages/shared/src/webArtifactsContract.ts` `artifactWebPaths.dashboardAll` / `dashboardAuthors` /
 * `dashboardConversations` and the types `ArtifactWebDashboardAll`, `ArtifactDashboardAllRow`,
 * `ArtifactDashboardAllNews`, `ArtifactDashboardAllCounts`, `ArtifactWebDashboardAuthors`,
 * `ArtifactWebDashboardConversations`; served by `packages/server/src/apps/artifacts/webDashboard.ts`;
 * the web page is `packages/web/src/apps/artifacts/ArtifactsDashboard.tsx`).
 *
 * - `GET /api/artifacts/dashboard/all?home=&conversation=&author=&q=&sort=&relevant=1&cursor=`, in the
 *   reader's server (X-Server-Id): every Artifact the reader can see there, 50 a page. `sort` absent is
 *   `updated` (the only order older servers know); `relevant=1` keeps what concerns the reader and
 *   fixes the order. A cursor continues only the exact query it came from (else 400).
 *   Answer `{ rows: [{ card, openedAt?, news? }], nextCursor, counts? }`; `counts` on a first page only.
 * - `GET /api/artifacts/dashboard/authors?q=` → `{ authors: [ArtifactActor & { count }] }`.
 * - `GET /api/artifacts/dashboard/conversations?q=` → `{ conversations: [{ id, name, type,
 *   peerDisplayName?, count, updatedAt, peer? }] }`.
 * - 403 `{ code: "ARTIFACTS_NOT_ENABLED" }` (workspace_artifacts_v0 off), 400 INVALID_REQUEST, 429
 *   RATE_LIMITED (120 a minute per user for the three). Reads only: no read position moves.
 */
@Serializable
data class ArtifactDashboardAllWireDto(
    val rows: List<ArtifactDashboardAllRowWireDto>,
    val nextCursor: String? = null,
    val counts: ArtifactDashboardAllCountsWireDto? = null,
)

@Serializable
data class ArtifactDashboardAllRowWireDto(
    val card: ArtifactCardWireDto,
    val openedAt: String? = null,
    val news: ArtifactDashboardAllNewsWireDto? = null,
)

@Serializable
data class ArtifactDashboardAllNewsWireDto(
    val mentions: Int = 0,
    val versions: Int = 0,
    val comments: Int = 0,
)

@Serializable
data class ArtifactDashboardAllCountsWireDto(
    val total: Int,
    val filtered: Int,
    val relevant: Int,
)

@Serializable
data class ArtifactDashboardAuthorsWireDto(
    val authors: List<ArtifactActorWireDto>,
)

@Serializable
data class ArtifactDashboardConversationWireDto(
    val id: String,
    val name: String,
    val type: String,
    val peerDisplayName: String? = null,
    val count: Int = 0,
    val updatedAt: String? = null,
    val peer: ArtifactActorWireDto? = null,
)

@Serializable
data class ArtifactDashboardConversationsWireDto(
    val conversations: List<ArtifactDashboardConversationWireDto>,
)

/** `ArtifactShownActor` as the home page names it (web `artifactAuthorText.ts`). */
data class ArtifactsHomeActor(
    val type: String,
    /** Absent for a reader outside the Artifact's server. */
    val id: String?,
    val name: String,
    val displayName: String,
    val avatarUrl: String? = null,
    /** `deleted` / `system`: no one stands behind it any more. */
    val gone: String? = null,
    /** The person whose personal assistant this agent is. */
    val onBehalfOf: ArtifactsHomeBehalf? = null,
) {
    /** The `author` filter value (`user:<id>` / `agent:<id>`), or null without an id. */
    val filterValue: String? get() = id?.takeIf { it.isNotBlank() }?.let { "$type:$it" }
}

data class ArtifactsHomeBehalf(val id: String, val displayName: String)

/** web `ArtifactNews` (`artifactNews.ts`): the one pink sticker's facts. */
data class ArtifactsHomeNews(
    val mentioned: Boolean = false,
    val newerVersion: Boolean = false,
    val newComments: Int = 0,
) {
    val any: Boolean get() = mentioned || newerVersion || newComments > 0
}

/** One row of the list: the card, who made it, when the reader last opened it, what is new. */
data class ArtifactsHomeRow(
    val summary: ArtifactSummary,
    val author: ArtifactsHomeActor,
    /** ISO 8601; null if never opened, or the server is older. */
    val openedAt: String?,
    val news: ArtifactsHomeNews,
    /** Another server's public Artifact read as an outsider. */
    val fromAnotherServer: Boolean = false,
)

/** web `ArtifactDashboardAllCounts`: the header's "5 of 24" and the toggle's number. */
data class ArtifactsHomeCounts(
    val total: Int,
    val filtered: Int,
    val relevant: Int,
)

data class ArtifactsHomePage(
    val rows: List<ArtifactsHomeRow>,
    val nextCursor: String?,
    val counts: ArtifactsHomeCounts?,
)

/** A conversation the Conversation filter offers (`dashboardConversations`). */
data class ArtifactsHomeConversation(
    val id: String,
    val name: String,
    /** `channel`, `private`, `joint` or `dm`. */
    val type: String,
    val peerDisplayName: String? = null,
    val peer: ArtifactsHomeActor? = null,
) {
    /** web `conversationName`: a DM by its peer's display name, else the handle. */
    val shownName: String get() = peerDisplayName?.takeIf { it.isNotBlank() } ?: name

    /** web `conversationLabel`: `#name` for a channel or private channel, a DM by its peer, a joint by its name. */
    val label: String
        get() = when (type) {
            "dm" -> shownName
            "channel", "private" -> "#$name"
            else -> name
        }
}

/** The two homes the Conversation filter offers above the conversations (web `ARTIFACT_PLACE_HOMES`). */
enum class ArtifactsHomeScope(val wire: String) { Public("public"), Server("server") }

/** The Conversation filter's one value. */
sealed interface ArtifactsHomePlace {
    data class Home(val scope: ArtifactsHomeScope) : ArtifactsHomePlace
    data class Conversation(val conversation: ArtifactsHomeConversation) : ArtifactsHomePlace
}

/** The sorts the page offers (web `ARTIFACT_HOME_SORTS`), recently opened the default. */
enum class ArtifactsHomeSort(val wire: String) {
    Opened("opened"), Updated("updated");

    companion object {
        val Default = Opened
        fun fromWire(value: String?): ArtifactsHomeSort = entries.firstOrNull { it.wire == value } ?: Default
    }
}

/** web `WEB_SEARCH_QUERY_MAX_CHARS`: a longer `q` is a 400. */
const val ARTIFACTS_HOME_SEARCH_MAX_CHARS: Int = 200

/** What one read of the list asks for (the cursor aside: a page continues only its own query). */
data class ArtifactsHomeQuery(
    val place: ArtifactsHomePlace? = null,
    /** One `user:<id>` / `agent:<id>`. */
    val author: String? = null,
    val q: String = "",
    val sort: ArtifactsHomeSort = ArtifactsHomeSort.Default,
    val relevant: Boolean = false,
) {
    /** Any filter, search or the toggle: the count line says "5 of 24". */
    val narrowed: Boolean get() = place != null || author != null || q.isNotEmpty() || relevant

    /**
     * web `useAllArtifacts` options: each sort in its default direction (no `dir`), `updated` asked
     * for by no `sort`, and no `sort` under `relevant`.
     */
    fun params(cursor: String? = null): List<Pair<String, String>> =
        buildList {
            when (val p = place) {
                is ArtifactsHomePlace.Home -> add("home" to p.scope.wire)
                is ArtifactsHomePlace.Conversation -> add("conversation" to p.conversation.id)
                null -> Unit
            }
            author?.let { add("author" to it) }
            q.trim().takeIf { it.isNotEmpty() }?.let { add("q" to it) }
            if (relevant) add("relevant" to "1") else if (sort != ArtifactsHomeSort.Updated) add("sort" to sort.wire)
            cursor?.takeIf { it.isNotBlank() }?.let { add("cursor" to it) }
        }
}

/** One read's outcome. */
sealed interface ArtifactsHomeLoad<out T> {
    data class Ok<T>(val value: T) : ArtifactsHomeLoad<T>

    /** 403 ARTIFACTS_NOT_ENABLED: the page says Artifacts aren't on for this server. */
    data object Off : ArtifactsHomeLoad<Nothing>

    /** 404: an older server without the route (the author filter falls back to the rows on screen). */
    data class Missing(val error: ScreenError) : ArtifactsHomeLoad<Nothing>

    data class Failed(val error: ScreenError) : ArtifactsHomeLoad<Nothing>
}

private val artifactsDashboardJson = Json { ignoreUnknownKeys = true }

internal fun ArtifactActorWireDto.toArtifactsHomeActor(): ArtifactsHomeActor =
    ArtifactsHomeActor(
        type = type,
        id = id?.takeIf { it.isNotBlank() },
        name = name,
        displayName = displayName,
        avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
        gone = gone?.takeIf { it.isNotBlank() },
        onBehalfOf = onBehalfOf?.takeIf { it.id.isNotBlank() }?.let { ArtifactsHomeBehalf(it.id, it.displayName) },
    )

/** One page of the list; null when the answer can't be read. A card with a home this client can't read is left out. */
fun parseArtifactsHomePage(bodyText: String?): ArtifactsHomePage? {
    val wire =
        bodyText?.let { runCatching { artifactsDashboardJson.decodeFromString(ArtifactDashboardAllWireDto.serializer(), it) }.getOrNull() }
            ?: return null
    return ArtifactsHomePage(
        rows =
            wire.rows.mapNotNull { row ->
                val summary = row.card.toArtifactSummaryOrNull() ?: return@mapNotNull null
                ArtifactsHomeRow(
                    summary = summary,
                    author = row.card.author.toArtifactsHomeActor(),
                    openedAt = row.openedAt?.takeIf { it.isNotBlank() },
                    news =
                        ArtifactsHomeNews(
                            mentioned = (row.news?.mentions ?: 0) > 0,
                            newerVersion = (row.news?.versions ?: 0) > 0,
                            newComments = (row.news?.comments ?: 0).coerceAtLeast(0),
                        ),
                    fromAnotherServer = row.card.fromAnotherServer == true,
                )
            },
        nextCursor = wire.nextCursor?.takeIf { it.isNotBlank() },
        counts = wire.counts?.let { ArtifactsHomeCounts(total = it.total, filtered = it.filtered, relevant = it.relevant) },
    )
}

fun parseArtifactsHomeAuthors(bodyText: String?): List<ArtifactsHomeActor>? =
    bodyText
        ?.let { runCatching { artifactsDashboardJson.decodeFromString(ArtifactDashboardAuthorsWireDto.serializer(), it) }.getOrNull() }
        ?.authors
        ?.map { it.toArtifactsHomeActor() }

fun parseArtifactsHomeConversations(bodyText: String?): List<ArtifactsHomeConversation>? =
    bodyText
        ?.let { runCatching { artifactsDashboardJson.decodeFromString(ArtifactDashboardConversationsWireDto.serializer(), it) }.getOrNull() }
        ?.conversations
        ?.filter { it.id.isNotBlank() }
        ?.map {
            ArtifactsHomeConversation(
                id = it.id,
                name = it.name,
                type = it.type,
                peerDisplayName = it.peerDisplayName?.takeIf { peer -> peer.isNotBlank() },
                peer = it.peer?.toArtifactsHomeActor(),
            )
        }

/** The refusal's `code` (top level, as `webCommon.ts` `refuse` writes it). */
internal fun HomeNetworkResponse.artifactsErrorCodeOrNull(): String? {
    val text = errorBodyText ?: bodyText ?: return null
    val root = runCatching { artifactsDashboardJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    return (root["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private fun <T> HomeNetworkResponse.toArtifactsHomeLoad(parse: (String?) -> T?): ArtifactsHomeLoad<T> {
    if (isSuccessful) {
        return parse(bodyText)?.let { ArtifactsHomeLoad.Ok(it) }
            ?: ArtifactsHomeLoad.Failed(ScreenError("artifacts_home_unreadable", retryable = true, traceId = traceId))
    }
    val code = artifactsErrorCodeOrNull()
    val error = ScreenError("artifacts_home_http_${this.code}", retryable = true, serverCode = code, traceId = traceId)
    return when {
        this.code == 403 && code == "ARTIFACTS_NOT_ENABLED" -> ArtifactsHomeLoad.Off
        this.code == 404 -> ArtifactsHomeLoad.Missing(error)
        else -> ArtifactsHomeLoad.Failed(error)
    }
}

internal interface RaftArtifactsDashboardApi {
    suspend fun all(serverId: String, query: ArtifactsHomeQuery, cursor: String?, priority: DataRequestPriority): HomeNetworkResponse

    suspend fun authors(serverId: String, q: String): HomeNetworkResponse

    suspend fun conversations(serverId: String, q: String): HomeNetworkResponse
}

private fun searchParam(q: String): List<Pair<String, String>> =
    listOfNotNull(q.trim().take(ARTIFACTS_HOME_SEARCH_MAX_CHARS).takeIf { it.isNotEmpty() }?.let { "q" to it })

internal class NetworkRaftArtifactsDashboardApi(private val networkClient: RaftNetworkClient) : RaftArtifactsDashboardApi {
    override suspend fun all(serverId: String, query: ArtifactsHomeQuery, cursor: String?, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "artifacts/dashboard/all",
            query = query.params(cursor),
            serverIdOverride = ServerId(serverId),
        )

    override suspend fun authors(serverId: String, q: String): HomeNetworkResponse =
        networkClient.request(
            // An open filter's list, on screen.
            priority = DataRequestPriority.Visible,
            path = "artifacts/dashboard/authors",
            query = searchParam(q),
            serverIdOverride = ServerId(serverId),
        )

    override suspend fun conversations(serverId: String, q: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "artifacts/dashboard/conversations",
            query = searchParam(q),
            serverIdOverride = ServerId(serverId),
        )
}

/** A failure to reach the server at all: no response, so no trace id. */
private fun unreachable(error: Throwable): ScreenError =
    ScreenError("artifacts_home_network", retryable = true, serverMessage = error.message, traceId = null)

internal suspend fun RaftArtifactsDashboardApi.loadArtifactsHomePage(
    serverId: String,
    query: ArtifactsHomeQuery,
    cursor: String?,
    priority: DataRequestPriority,
): ArtifactsHomeLoad<ArtifactsHomePage> =
    runCatchingPreservingCancellation { all(serverId, query, cursor, priority) }
        .fold({ it.toArtifactsHomeLoad(::parseArtifactsHomePage) }, { ArtifactsHomeLoad.Failed(unreachable(it)) })

internal suspend fun RaftArtifactsDashboardApi.loadArtifactsHomeAuthors(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeActor>> =
    runCatchingPreservingCancellation { authors(serverId, q) }
        .fold({ it.toArtifactsHomeLoad(::parseArtifactsHomeAuthors) }, { ArtifactsHomeLoad.Failed(unreachable(it)) })

internal suspend fun RaftArtifactsDashboardApi.loadArtifactsHomeConversations(
    serverId: String,
    q: String,
): ArtifactsHomeLoad<List<ArtifactsHomeConversation>> =
    runCatchingPreservingCancellation { conversations(serverId, q) }
        .fold({ it.toArtifactsHomeLoad(::parseArtifactsHomeConversations) }, { ArtifactsHomeLoad.Failed(unreachable(it)) })

/** The store's typed reads: the routes through their response mapping (a test hands answers directly). */
internal interface ArtifactsHomeSource {
    suspend fun page(serverId: String, query: ArtifactsHomeQuery, cursor: String?, priority: DataRequestPriority): ArtifactsHomeLoad<ArtifactsHomePage>

    suspend fun authors(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeActor>>

    suspend fun conversations(serverId: String, q: String): ArtifactsHomeLoad<List<ArtifactsHomeConversation>>
}

internal fun RaftArtifactsDashboardApi.asArtifactsHomeSource(): ArtifactsHomeSource {
    val api = this
    return object : ArtifactsHomeSource {
        override suspend fun page(serverId: String, query: ArtifactsHomeQuery, cursor: String?, priority: DataRequestPriority) =
            api.loadArtifactsHomePage(serverId, query, cursor, priority)

        override suspend fun authors(serverId: String, q: String) = api.loadArtifactsHomeAuthors(serverId, q)

        override suspend fun conversations(serverId: String, q: String) = api.loadArtifactsHomeConversations(serverId, q)
    }
}
