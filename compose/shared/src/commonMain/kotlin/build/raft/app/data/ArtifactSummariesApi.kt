package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `POST /api/objects/summaries` for Artifacts (botiverse/slock feat/workspace-artifacts 542e922d6,
 * `packages/shared/src/webObjectsContract.ts` `objectWebPaths.summaries`, `ObjectSummariesInput`,
 * `ObjectSummaries`; the summary is `workspaceArtifacts.ts` `ArtifactCard`).
 *
 * - Request (X-Server-Id: the reader's server): `{ kind: "artifact", refs: [{ id, version? }] }`, 1 to
 *   [OBJECT_SUMMARIES_MAX_REFS] refs, `version` a whole number from 1.
 * - Answer: `{ kind, summaries: [{ ref, ok: true, summary } | { ref, ok: false, reason: "unavailable" }
 *   | { ref, ok: false, reason: "deleted", deleted }] }`, one per reference in order, echoing it.
 *   `unavailable` covers every reason (no access, missing, the app off for either server);
 *   `deleted` (additive, slock staging 84e41d9be `webObjectsContract.ts` `ObjectSummaryAnswer`) is
 *   an `ArtifactDeletedReference` (`title`, `deletedBy`, `deletedAt`), sent only to a reader who
 *   could see the Artifact before it was deleted. Per viewer: `Cache-Control: private, no-store`.
 * - 400 INVALID_REQUEST / UNKNOWN_OBJECT_KIND, 429 RATE_LIMITED (300 a minute per user).
 */
@Serializable
data class ArtifactObjectRefWireDto(
    val id: String,
    val version: Int? = null,
)

@Serializable
data class ArtifactSummariesRequestWireDto(
    val kind: String = "artifact",
    val refs: List<ArtifactObjectRefWireDto>,
)

/** `ArtifactShownActor` (`ArtifactActor`, with no `id` for a reader outside the Artifact's server). */
@Serializable
data class ArtifactActorWireDto(
    val type: String,
    val id: String? = null,
    val name: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

/** `ArtifactHome`: `conversation` (with its conversation), `server` or `public`. */
@Serializable
data class ArtifactHomeWireDto(
    val kind: String,
    val conversation: ArtifactHomeConversationWireDto? = null,
)

@Serializable
data class ArtifactHomeConversationWireDto(
    val id: String? = null,
    val name: String,
    val type: String,
    /** A DM's peer by display name, as the reader's sidebar shows it (absent for other types). */
    val peerDisplayName: String? = null,
)

/** `ArtifactFacts`: raw counts for a card's fact line (web `artifactFacts.ts` formats them). */
@Serializable
data class ArtifactFactsWireDto(
    val sizeBytes: Long = 0,
    val fileCount: Int = 0,
    val pages: Int? = null,
    val rows: Int? = null,
    val columns: Int? = null,
    val columnNames: List<String>? = null,
    val lines: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * `ArtifactCard`: what a card shows wherever it is drawn. Of `preview`, only the excerpt's text is
 * read (a publisher cover is a file of the Artifact, fetched with the viewer's credentials: not
 * read by this client yet).
 */
@Serializable
data class ArtifactCardWireDto(
    val id: String,
    val url: String,
    val title: String,
    val description: String? = null,
    val kind: String,
    val version: Int,
    val pinned: Boolean,
    val latestVersion: Int,
    val author: ArtifactActorWireDto,
    val createdAt: String,
    val updatedAt: String,
    val commentCount: Int,
    val home: ArtifactHomeWireDto,
    val preview: ArtifactPreviewWireDto? = null,
    val facts: ArtifactFactsWireDto? = null,
)

/** `ArtifactPreview`: only the excerpt's text is read (the description's fallback). */
@Serializable
data class ArtifactPreviewWireDto(
    val kind: String,
    val text: String? = null,
)

/** `ArtifactKind`; an unknown one reads as [Other]. */
enum class ArtifactKind(val wire: String) {
    Html("html"), Markdown("markdown"), Text("text"), Csv("csv"), Image("image"), Pdf("pdf"), Video("video"), Other("other");

    /** web `ARTIFACT_KIND_BADGE`: the file type the card's glyph names; none for [Other]. */
    val badge: String?
        get() = when (this) {
            Html -> "HTML"
            Markdown -> "MD"
            Text -> "TXT"
            Csv -> "CSV"
            Image -> "IMG"
            Pdf -> "PDF"
            Video -> "VIDEO"
            Other -> null
        }

    companion object {
        fun fromWire(value: String?): ArtifactKind = entries.firstOrNull { it.wire == value } ?: Other
    }
}

/** Where an Artifact lives, as its card names it. */
sealed interface ArtifactHome {
    /**
     * A channel (`channel`, `private` ([private]), `joint`) or a DM ([dm], its peer
     * [peerDisplayName] when sent); [name] empty for a deleted one.
     */
    data class Conversation(
        val name: String,
        val dm: Boolean,
        val private: Boolean = false,
        val peerDisplayName: String? = null,
    ) : ArtifactHome
    data object Server : ArtifactHome
    data object Public : ArtifactHome
}

/** `ArtifactFacts` as this client reads them (web `formatArtifactFacts`). */
data class ArtifactFacts(
    val sizeBytes: Long = 0,
    val pages: Int? = null,
    val rows: Int? = null,
    val columns: Int? = null,
    val columnNames: List<String> = emptyList(),
    val lines: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** One Artifact's card, as this client shows it. */
data class ArtifactSummary(
    val id: String,
    val title: String,
    /** The card's description, else the start of its text (the excerpt; [descriptionIsExcerpt]). */
    val description: String?,
    val kind: ArtifactKind,
    val version: Int,
    val pinned: Boolean,
    val latestVersion: Int,
    val home: ArtifactHome,
    /** [description] is the excerpt (no description of its own): the card clamps it at 2 lines. */
    val descriptionIsExcerpt: Boolean = false,
    /** `author.displayName`: a pinned card's version's publisher, else the Artifact's author. */
    val authorName: String = "",
    /** ISO 8601: a pinned card's version's publish time, else the Artifact's last update. */
    val updatedAt: String? = null,
    val commentCount: Int = 0,
    val facts: ArtifactFacts = ArtifactFacts(),
)

/**
 * A deleted Artifact as its card shows it (web `artifactFacts.ts` `ArtifactDeletedShown`): the
 * title, who deleted it (display name, else handle; null when the answer names no one) and when
 * (ISO 8601, null when missing).
 */
data class ArtifactDeletedShown(
    val title: String,
    val deletedBy: String?,
    val deletedAt: String?,
)

/** One reference's answer. */
sealed interface ArtifactSummaryAnswer {
    data class Ok(val summary: ArtifactSummary) : ArtifactSummaryAnswer
    data object Unavailable : ArtifactSummaryAnswer

    /** Deleted, to a reader who could see it before (web `artifactDeletedOf`: only with a title). */
    data class Deleted(val deleted: ArtifactDeletedShown) : ArtifactSummaryAnswer

    /**
     * The last request for it failed and nothing is kept (web loader `error`): never stored, only
     * reported by [SharedArtifactSummaryState.answersFor] until it is asked again.
     */
    data object Failed : ArtifactSummaryAnswer
}

private val artifactSummariesJson = Json {
    ignoreUnknownKeys = true
}

/**
 * The ref by [ArtifactLinkTarget.canonicalId]: the summaries route reads `id` as written (slock
 * `webCards.ts` `parseArtifactObjectRef`, core `readObjectRefs` requires the same id back, and
 * `visibility.ts` `homesWhere` looks it up as `pt_<uuid>`), so a short id would answer `unavailable`.
 */
internal fun ArtifactLinkTarget.toWireRef(): ArtifactObjectRefWireDto = ArtifactObjectRefWireDto(id = canonicalId, version = version)

/** `kind` always written; an unpinned ref has no `version` key (never `null`). */
private val artifactSummariesRequestJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

internal fun encodeArtifactSummariesRequest(targets: List<ArtifactLinkTarget>): String =
    artifactSummariesRequestJson.encodeToString(
        ArtifactSummariesRequestWireDto.serializer(),
        ArtifactSummariesRequestWireDto(refs = targets.map { it.toWireRef() }),
    )

/**
 * Reads an answer to one summaries request, by [ArtifactLinkTarget.key]. Null when the answer as a
 * whole can't be read (a failed request). A summary this client can't read answers [Unavailable]:
 * the link stays a plain link.
 */
fun parseArtifactSummariesAnswer(bodyText: String?): Map<String, ArtifactSummaryAnswer>? {
    val root = bodyText?.let { runCatching { artifactSummariesJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
    if ((root["kind"] as? JsonPrimitive)?.content != "artifact") return null
    val summaries = root["summaries"] as? kotlinx.serialization.json.JsonArray ?: return null
    val answers = LinkedHashMap<String, ArtifactSummaryAnswer>()
    for (element in summaries) {
        val entry = element as? JsonObject ?: continue
        val ref = entry["ref"]?.let { runCatching { artifactSummariesJson.decodeFromJsonElement(ArtifactObjectRefWireDto.serializer(), it) }.getOrNull() } ?: continue
        val key = ArtifactLinkTarget(ref.id, ref.version).key
        val ok = (entry["ok"] as? JsonPrimitive)?.booleanOrNull
        answers[key] =
            when {
                ok == true -> entry["summary"]?.toArtifactSummaryOrNull()?.let(ArtifactSummaryAnswer::Ok) ?: ArtifactSummaryAnswer.Unavailable
                (entry["reason"] as? JsonPrimitive)?.content == "deleted" ->
                    artifactDeletedShownOrNull(entry["deleted"])?.let(ArtifactSummaryAnswer::Deleted) ?: ArtifactSummaryAnswer.Unavailable
                // Any other reason, including one this client doesn't know.
                else -> ArtifactSummaryAnswer.Unavailable
            }
    }
    return answers
}

/**
 * web `artifactDeletedOf`: a deleted answer read into what its card shows; null without a title
 * (then drawn as unavailable). Who and when may be missing: the card then says less.
 */
internal fun artifactDeletedShownOrNull(answer: JsonElement?): ArtifactDeletedShown? {
    val deleted = answer as? JsonObject ?: return null
    val title = (deleted["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (title.isBlank()) return null
    val actor = deleted["deletedBy"] as? JsonObject
    val deletedBy =
        listOf("displayName", "name")
            .firstNotNullOfOrNull { field -> (actor?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } }
    val deletedAt = (deleted["deletedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ArtifactDeletedShown(title = title, deletedBy = deletedBy, deletedAt = deletedAt)
}

private fun JsonElement.toArtifactSummaryOrNull(): ArtifactSummary? =
    runCatching { artifactSummariesJson.decodeFromJsonElement(ArtifactCardWireDto.serializer(), this) }.getOrNull()?.toArtifactSummaryOrNull()

/** The card as this client shows it; null for a home it can't read. */
internal fun ArtifactCardWireDto.toArtifactSummaryOrNull(): ArtifactSummary? {
    val card = this
    val home =
        when (card.home.kind) {
            "conversation" ->
                card.home.conversation?.let {
                    ArtifactHome.Conversation(
                        it.name,
                        dm = it.type == "dm",
                        private = it.type == "private",
                        peerDisplayName = it.peerDisplayName?.takeIf { peer -> peer.isNotBlank() },
                    )
                } ?: return null
            "server" -> ArtifactHome.Server
            "public" -> ArtifactHome.Public
            else -> return null
        }
    val excerpt = card.preview?.takeIf { it.kind == "excerpt" }?.text?.takeIf { it.isNotBlank() }
    val description = card.description?.takeIf { it.isNotBlank() }
    val facts = card.facts
    return ArtifactSummary(
        id = card.id,
        title = card.title,
        description = description ?: excerpt,
        kind = ArtifactKind.fromWire(card.kind),
        version = card.version,
        pinned = card.pinned,
        latestVersion = card.latestVersion,
        home = home,
        descriptionIsExcerpt = description == null && excerpt != null,
        authorName = card.author.displayName,
        updatedAt = card.updatedAt,
        commentCount = card.commentCount,
        facts =
            ArtifactFacts(
                sizeBytes = facts?.sizeBytes ?: 0,
                pages = facts?.pages,
                rows = facts?.rows,
                columns = facts?.columns,
                columnNames = facts?.columnNames.orEmpty(),
                lines = facts?.lines,
                width = facts?.width,
                height = facts?.height,
            ),
    )
}

internal interface RaftArtifactSummariesApi {
    suspend fun summaries(serverId: String, targets: List<ArtifactLinkTarget>): HomeNetworkResponse
}

internal class NetworkRaftArtifactSummariesApi(private val networkClient: RaftNetworkClient) : RaftArtifactSummariesApi {
    override suspend fun summaries(serverId: String, targets: List<ArtifactLinkTarget>): HomeNetworkResponse =
        networkClient.request(
            // An on-screen message's own decoration, as App link previews.
            priority = DataRequestPriority.Visible,
            path = "objects/summaries",
            method = "POST",
            serverIdOverride = ServerId(serverId),
            bodyText = encodeArtifactSummariesRequest(targets),
        )
}
