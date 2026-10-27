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
 * - Answer: `{ kind, summaries: [{ ref, ok: true, summary } | { ref, ok: false, reason: "unavailable" }] }`,
 *   one per reference in order, echoing it. `unavailable` covers every reason (no access, missing,
 *   the app off for either server). Per viewer: `Cache-Control: private, no-store`.
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

/** `ArtifactActor`. */
@Serializable
data class ArtifactActorWireDto(
    val type: String,
    val id: String,
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
)

/**
 * `ArtifactCard`: what a card shows wherever it is drawn. `facts` and `preview` are not read by
 * this client yet (its item card shows the type, version and home, then the description).
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
    /** A channel (`channel`, `private`, `joint`) or a DM ([dm]); [name] empty for a deleted one. */
    data class Conversation(val name: String, val dm: Boolean) : ArtifactHome
    data object Server : ArtifactHome
    data object Public : ArtifactHome
}

/** One Artifact's card, as this client shows it. */
data class ArtifactSummary(
    val id: String,
    val title: String,
    val description: String?,
    val kind: ArtifactKind,
    val version: Int,
    val pinned: Boolean,
    val latestVersion: Int,
    val home: ArtifactHome,
)

/** One reference's answer. */
sealed interface ArtifactSummaryAnswer {
    data class Ok(val summary: ArtifactSummary) : ArtifactSummaryAnswer
    data object Unavailable : ArtifactSummaryAnswer
}

private val artifactSummariesJson = Json {
    ignoreUnknownKeys = true
}

internal fun ArtifactLinkTarget.toWireRef(): ArtifactObjectRefWireDto = ArtifactObjectRefWireDto(id = id, version = version)

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
            if (ok == true) {
                entry["summary"]?.toArtifactSummaryOrNull()?.let(ArtifactSummaryAnswer::Ok) ?: ArtifactSummaryAnswer.Unavailable
            } else {
                ArtifactSummaryAnswer.Unavailable
            }
    }
    return answers
}

private fun JsonElement.toArtifactSummaryOrNull(): ArtifactSummary? {
    val card = runCatching { artifactSummariesJson.decodeFromJsonElement(ArtifactCardWireDto.serializer(), this) }.getOrNull() ?: return null
    val home =
        when (card.home.kind) {
            "conversation" -> card.home.conversation?.let { ArtifactHome.Conversation(it.name, dm = it.type == "dm") } ?: return null
            "server" -> ArtifactHome.Server
            "public" -> ArtifactHome.Public
            else -> return null
        }
    val excerpt = card.preview?.takeIf { it.kind == "excerpt" }?.text
    return ArtifactSummary(
        id = card.id,
        title = card.title,
        description = card.description?.takeIf { it.isNotBlank() } ?: excerpt?.takeIf { it.isNotBlank() },
        kind = ArtifactKind.fromWire(card.kind),
        version = card.version,
        pinned = card.pinned,
        latestVersion = card.latestVersion,
        home = home,
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
