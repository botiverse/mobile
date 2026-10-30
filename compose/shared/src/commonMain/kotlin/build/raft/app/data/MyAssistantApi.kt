package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * "My assistant" (RFC 075 v4): the signed-in person's own personal assistant in one server.
 *
 * Server contract (botiverse/slock `packages/server/src/routes/agents.ts` `myAssistantRouter`,
 * mounted at `/api/servers/:id/assistant` in `routes/servers.ts`;
 * `services/assistantService.ts` `MyAssistant` / `getMyAssistant` / `isMyAssistantAvailable`):
 *  - `GET servers/:id/assistant` →
 *    `{ enabled: boolean, assistant: null | { id, name, agentId, dmChannelId: string | null,
 *       readsDms, sendAsOwner, ownerNotes } }`.
 *  - `enabled` is false when the server's `personal_assistant` flag is off or the hosted runtime
 *    is unavailable. `dmChannelId` is null until the owner's DM with the assistant exists.
 *  - 403 `assistant_not_enabled`, or 404 from a server that predates the route: not available.
 *
 * Web reads it in `packages/web/src/assistant/assistantClient.ts` (`useMyAssistant`): only while
 * the `personal_assistant` flag is on, never for a guest; any failure of the first read fails
 * closed (not enabled); a refetch keeps what it had on a transient failure (not 403 / 404).
 *
 * Only the fields the app uses are modelled; the owner's switches (`readsDms`, `sendAsOwner`,
 * `ownerNotes`) are ignored until a screen needs them. Field names are exact camelCase.
 */
private val myAssistantJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class WireMyAssistantRecord(
    val id: String,
    val name: String,
    val agentId: String,
    val dmChannelId: String? = null,
)

@Serializable
private data class WireMyAssistantResponse(
    val enabled: Boolean = false,
    val assistant: WireMyAssistantRecord? = null,
)

/** The person's assistant (web `AssistantRecord`). */
data class MyAssistantRecord(
    /** Equals [agentId] in v4. */
    val id: String,
    val name: String,
    /** The assistant's agent in this server, the one its owner DMs. */
    val agentId: String,
    /** The owner's DM with that agent; null while it is still being set up. */
    val dmChannelId: String?,
)

/** One answer of `GET servers/:id/assistant` (web `MyAssistantResponse`, without the settings). */
data class MyAssistantAnswer(
    val enabled: Boolean,
    val assistant: MyAssistantRecord?,
) {
    companion object {
        /** Not available here (web `NOT_ENABLED`). */
        val NotEnabled: MyAssistantAnswer = MyAssistantAnswer(enabled = false, assistant = null)
    }
}

/**
 * Parses a `GET servers/:id/assistant` body. Null for a body that is not this shape (the caller
 * fails closed, as web does for any unreadable answer).
 */
fun parseSharedMyAssistantResponse(body: String): MyAssistantAnswer? {
    val wire =
        try {
            myAssistantJson.decodeFromString(WireMyAssistantResponse.serializer(), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val record = wire.assistant
    if (record != null && (record.id.isBlank() || record.agentId.isBlank())) return null
    return MyAssistantAnswer(
        enabled = wire.enabled,
        assistant =
            record?.let {
                MyAssistantRecord(
                    id = it.id,
                    name = it.name,
                    agentId = it.agentId,
                    dmChannelId = it.dmChannelId?.takeIf(String::isNotBlank),
                )
            },
    )
}

/** What one read of the route gave. */
sealed interface MyAssistantFetchResult {
    data class Answer(val answer: MyAssistantAnswer) : MyAssistantFetchResult

    /** 403 `assistant_not_enabled`, or 404 from a server without the route. */
    data object NotAvailable : MyAssistantFetchResult

    /** Offline, 5xx, an unreadable body: transient. [code] is the HTTP status, null for transport. */
    data class Failed(val code: Int?) : MyAssistantFetchResult
}

interface RaftMyAssistantApi {
    suspend fun fetch(serverId: ServerId): MyAssistantFetchResult
}

internal class RaftMyAssistantNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftMyAssistantApi {
    override suspend fun fetch(serverId: ServerId): MyAssistantFetchResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    // Warm-up read behind the composer's otter; nothing waits on it.
                    priority = DataRequestPriority.Background,
                    path = "servers/${serverId.value}/assistant",
                    serverIdOverride = serverId,
                )
            }.getOrElse { return MyAssistantFetchResult.Failed(code = null) }
        return myAssistantFetchResultOf(response.code, response.bodyText)
    }
}

internal fun myAssistantFetchResultOf(code: Int, bodyText: String?): MyAssistantFetchResult =
    when {
        code == 403 || code == 404 -> MyAssistantFetchResult.NotAvailable
        code !in 200..299 || bodyText == null -> MyAssistantFetchResult.Failed(code)
        else ->
            parseSharedMyAssistantResponse(bodyText)
                ?.let(MyAssistantFetchResult::Answer)
                ?: MyAssistantFetchResult.Failed(code)
    }
