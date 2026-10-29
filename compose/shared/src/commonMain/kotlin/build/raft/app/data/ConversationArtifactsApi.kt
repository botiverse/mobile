package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A conversation's Artifacts, for its Files tab (botiverse/slock #11590 ee4eb83ff, served at
 * `packages/server/src/apps/artifacts/webConversation.ts`; the contract is
 * `packages/shared/src/webArtifactsContract.ts` `artifactWebPaths.conversationArtifacts` and
 * `workspaceArtifacts.ts` `ArtifactWebConversationArtifacts`; web #11580 aebe2ab30 reads it in
 * `ChannelFilesPanel.tsx`).
 *
 * - `GET /api/artifacts/conversations/<percent-encoded channel id>[?cursor=<nextCursor>]`, in the
 *   reader's server (X-Server-Id). The live Artifacts whose home is that conversation (a thread
 *   means its conversation), with a published current version, that the reader can see; newest
 *   first by `card.updatedAt`; `ARTIFACT_CONVERSATION_PAGE_LIMIT` (20) a page.
 * - Answer: `{ rows: [{ card: ArtifactCard }], nextCursor: string | null }`.
 * - 403 ARTIFACTS_NOT_ENABLED (workspace_artifacts_v0 off), 400 INVALID_REQUEST (bad cursor),
 *   404 TARGET_NOT_FOUND (a conversation the reader can't see, or none), 429 RATE_LIMITED (120 a
 *   minute per user, shared with the dashboard). This client shows none for every one of them.
 */
@Serializable
data class ArtifactWebConversationArtifactsWireDto(
    val rows: List<ArtifactWebConversationArtifactRowWireDto>,
    val nextCursor: String? = null,
)

/** One row: an object so the server can say more later (why it is here) without a new shape. */
@Serializable
data class ArtifactWebConversationArtifactRowWireDto(
    val card: ArtifactCardWireDto,
)

/** One of a conversation's Artifacts: its card, and when it was last updated (`card.updatedAt`). */
data class ConversationArtifact(
    val summary: ArtifactSummary,
    val updatedAt: String,
)

/** One page of a conversation's Artifacts; [nextCursor] non-null while there are more. */
data class ConversationArtifactsPage(
    val artifacts: List<ConversationArtifact>,
    val nextCursor: String?,
)

private val conversationArtifactsJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Reads one page of a conversation's Artifacts; null when the answer can't be read. A card with a
 * home this client can't read is left out (the rest still show).
 */
fun parseConversationArtifactsPage(bodyText: String?): ConversationArtifactsPage? {
    val wire =
        bodyText?.let {
            runCatching { conversationArtifactsJson.decodeFromString(ArtifactWebConversationArtifactsWireDto.serializer(), it) }.getOrNull()
        } ?: return null
    return ConversationArtifactsPage(
        artifacts =
            wire.rows.mapNotNull { row ->
                row.card.toArtifactSummaryOrNull()?.let { ConversationArtifact(summary = it, updatedAt = row.card.updatedAt) }
            },
        nextCursor = wire.nextCursor?.takeIf { it.isNotBlank() },
    )
}

/** `artifactWebPaths.conversationArtifacts` without the `/api/` prefix the network client adds. */
internal fun conversationArtifactsPath(conversationId: String): String = "artifacts/conversations/${conversationId.appLinkPathSegment()}"

internal interface RaftConversationArtifactsApi {
    suspend fun conversationArtifacts(serverId: String, conversationId: String, cursor: String?): HomeNetworkResponse
}

internal class NetworkRaftConversationArtifactsApi(private val networkClient: RaftNetworkClient) : RaftConversationArtifactsApi {
    override suspend fun conversationArtifacts(serverId: String, conversationId: String, cursor: String?): HomeNetworkResponse =
        networkClient.request(
            // The Files tab on screen, read with its files.
            priority = DataRequestPriority.Visible,
            path = conversationArtifactsPath(conversationId),
            query = listOfNotNull(cursor?.takeIf { it.isNotBlank() }?.let { "cursor" to it }),
            serverIdOverride = ServerId(serverId),
        )
}

/**
 * One page, or null when it couldn't be had (any refusal, a network failure, an answer that can't
 * be read): the Files tab then shows no Artifacts, and its files as ever.
 */
internal suspend fun RaftConversationArtifactsApi.loadConversationArtifacts(
    serverId: String,
    conversationId: String,
    cursor: String?,
): ConversationArtifactsPage? {
    val response = runCatchingPreservingCancellation { conversationArtifacts(serverId, conversationId, cursor) }.getOrNull() ?: return null
    if (!response.isSuccessful) return null
    return parseConversationArtifactsPage(response.bodyText)
}
