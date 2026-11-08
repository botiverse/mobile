package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Agent Lounge v0 (zh 会客厅; botiverse/slock #proj-chat:30a58e4d): the one public conversation per
 * agent where the server's members talk with that agent; other agents may read it but never take
 * part. On the wire it is a plain `type: "channel"` named `<agent>-lounge` that carries
 * `loungeAgentId`; it is never listed (`GET /channels` leaves it out, and so does every channel
 * list here), it has no Join step (the first post joins), and it is always named after its agent:
 * "{Name}'s Lounge", never the raw channel name.
 *
 * Web source: packages/web/src/components/agent/agentLounge.ts, store/channelStore.ts `openLounge`;
 * server: packages/server/src/routes/agents.ts `POST /:id/lounge`, services/agentLoungeService.ts.
 * Gated by [AGENT_LOUNGE_FEATURE_FLAG_KEY]; a Guest never gets it.
 */

private val agentLoungeJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** The Lounge an agent DM row carries (`GET /channels/dm` `lounge`), with its unread from the unread summary. */
@Serializable
data class AgentLoungeSummary(
    val channelId: ChannelId,
    val joined: Boolean = false,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
)

/** The server has Lounges on and this person is not a Guest (web `useAgentLoungeEnabled`). Unknown: off. */
fun agentLoungeEnabled(flagEnabled: Boolean, serverRole: String?): Boolean =
    flagEnabled && !serverRole.equals("guest", ignoreCase = true)

/**
 * Whether an agent's profile offers its Lounge (web `agentOffersLounge` plus the profile's bounded
 * public projection): not a deleted agent, not someone's personal assistant (it works for one
 * person), and not a profile shown from another server (a remote joint agent).
 */
fun agentOffersLounge(deleted: Boolean, personalAssistant: Boolean, remoteProfile: Boolean): Boolean =
    !deleted && !personalAssistant && !remoteProfile

/** The agent's name from a Lounge's own channel name when nothing better is known (web `agentNameFromLoungeChannel`). */
fun loungeAgentNameFromChannelName(channelName: String): String = channelName.removeSuffix("-lounge")

/** The name a Lounge is shown under: its agent's display name, else from the channel name (web `loungeAgentDisplayName`). */
fun loungeAgentDisplayName(agentDisplayName: String?, channelName: String): String =
    agentDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: loungeAgentNameFromChannelName(channelName.trim().removePrefix("#"))

/** A Lounge the client knows: opened (POST /agents/:id/lounge), loaded by id, or carried on its agent's DM row. */
data class RaftAgentLounge(
    val channelId: ChannelId,
    val agentId: String,
    /** The agent's display name when known (its DM row, the member directory); else null. */
    val agentName: String? = null,
    /** The Lounge's own channel name (`<agent>-lounge`), for the name fallback; may be blank. */
    val channelName: String = "",
    val joined: Boolean = false,
    /** From POST /agents/:id/lounge; null when this client never opened it (absent = may post, as on the web). */
    val canPost: Boolean? = null,
    val archived: Boolean = false,
) {
    /** The agent's name for "{Name}'s Lounge". */
    val displayAgentName: String
        get() = loungeAgentDisplayName(agentName, channelName)

    /** May this person post here: not archived and not refused by the open answer. */
    val postable: Boolean
        get() = !archived && canPost != false
}

/** The sidebar sub-row under an agent's DM row (web `agentLoungeSidebarEntry`). */
data class AgentLoungeSidebarEntry(
    val channelId: ChannelId,
    val unreadCount: Int,
    val mentioned: Boolean,
)

/**
 * Whether an agent's Lounge has a sidebar row: only once you joined it, or someone mentioned you
 * there (not a row for every agent). [known] is the Lounge the client holds (fresher, e.g. right
 * after your first post); [summary] is what the agent's DM row carried.
 */
fun agentLoungeSidebarEntry(summary: AgentLoungeSummary?, known: RaftAgentLounge?): AgentLoungeSidebarEntry? {
    val channelId = known?.channelId ?: summary?.channelId ?: return null
    val joined = known?.joined == true || summary?.joined == true
    val sameChannel = summary?.channelId == channelId
    val mentionCount = if (sameChannel) summary?.mentionCount ?: 0 else 0
    if (!joined && mentionCount <= 0) return null
    return AgentLoungeSidebarEntry(
        channelId = channelId,
        unreadCount = if (sameChannel) summary?.unreadCount ?: 0 else 0,
        mentioned = mentionCount > 0,
    )
}

/** POST /agents/:id/lounge answered: the Lounge's row (always a Lounge), and this person's standing in it. */
data class AgentLoungeOpenResult(
    val channel: HomeChannelRow,
    val joined: Boolean,
    val canPost: Boolean,
)

@Serializable
private data class AgentLoungeOpenWireDto(
    val channel: HomeChannelWireDto,
    val joined: Boolean? = null,
    val canPost: Boolean? = null,
)

@Serializable
private data class AgentLoungeErrorWireDto(
    val error: String? = null,
    val code: String? = null,
)

/** The server's refusal code for a Guest (`403 {code: "agent_lounge_guest"}`). */
const val AGENT_LOUNGE_GUEST_ERROR_CODE: String = "agent_lounge_guest"

/** `ScreenError.code` for a Lounge that cannot be opened: flag off, unknown agent, an assistant (all 404). */
const val AGENT_LOUNGE_NOT_FOUND_ERROR_CODE: String = "agent_lounge_not_found"

/**
 * Parse `POST /agents/:id/lounge` (`{ channel: {...row, loungeAgentId, joined}, joined, canPost }`).
 * The answer's own `joined` / `canPost` win over the row's, and the row is always taken as the
 * Lounge of [agentId] even from a server that left `loungeAgentId` off it (web `openLounge`).
 */
fun parseSharedAgentLoungeOpenResult(bodyText: String, agentId: String): AgentLoungeOpenResult? {
    val wire =
        try {
            agentLoungeJson.decodeFromString(AgentLoungeOpenWireDto.serializer(), bodyText)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (wire.channel.id.isBlank()) return null
    val channelWire =
        wire.channel.copy(
            loungeAgentId = wire.channel.loungeAgentId?.takeIf { it.isNotBlank() } ?: agentId,
            joined = wire.joined ?: wire.channel.joined,
        )
    val row = channelWire.toChannelRow()
    return AgentLoungeOpenResult(
        channel = row,
        joined = row.joined,
        canPost = wire.canPost ?: !row.archived,
    )
}

/** POST /agents/:id/lounge: open (create on first open) an agent's Lounge. */
class AgentLoungeApi(private val networkClient: RaftNetworkClient) {
    suspend fun openLounge(serverId: ServerId?, agentId: String): DataResult<AgentLoungeOpenResult> {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "agents/$agentId/lounge",
                method = "POST",
                serverIdOverride = serverId,
            )
        return agentLoungeOpenResponse(response, agentId)
    }
}

/** Map POST /agents/:id/lounge's answer (exposed for tests). */
internal fun agentLoungeOpenResponse(response: HomeNetworkResponse, agentId: String): DataResult<AgentLoungeOpenResult> {
    if (response.isSuccessful) {
        val body = response.bodyText?.takeIf { it.isNotBlank() }
        val parsed = body?.let { parseSharedAgentLoungeOpenResult(it, agentId) }
        return parsed?.let { DataResult.Content(it) }
            ?: DataResult.Error(ScreenError(code = "agent_lounge_contract_mismatch", retryable = false, traceId = response.traceId))
    }
    val errorBody = (response.errorBodyText ?: response.bodyText)?.takeIf { it.isNotBlank() }
    val wireError =
        errorBody?.let {
            try {
                agentLoungeJson.decodeFromString(AgentLoungeErrorWireDto.serializer(), it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    return when (response.code) {
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = wireError?.code?.takeIf { it.isNotBlank() } ?: "http_403",
                    serverMessage = wireError?.error,
                    traceId = response.traceId,
                )
            )
        404 ->
            DataResult.Error(
                ScreenError(
                    code = AGENT_LOUNGE_NOT_FOUND_ERROR_CODE,
                    retryable = false,
                    serverMessage = wireError?.error,
                    traceId = response.traceId,
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = "http_${response.code}",
                    retryable = response.code >= 500,
                    serverMessage = wireError?.error,
                    serverCode = wireError?.code,
                    traceId = response.traceId,
                )
            )
    }
}

/** The trace id of a failed open, for the error toast (AGENTS.md "Errors carry the trace ID"). */
fun DataResult<AgentLoungeOpenResult>.agentLoungeOpenFailureTraceId(): String? =
    when (this) {
        is DataResult.Error -> error.traceId
        is DataResult.NoPermission -> blocker.traceId
        else -> null
    }

/**
 * The Lounges this client knows, per server: the ones it opened or loaded by id, and the ones its
 * agents' DM rows carry. A Lounge never enters a channel list, so this is where every surface
 * that has only a channel id (Activity, search, drafts, permalinks, a thread's parent) finds out a
 * conversation is a Lounge and whose. Process-wide, like the web's store slice.
 */
object RaftAgentLounges {
    private val byServer = MutableStateFlow<Map<String, Map<String, RaftAgentLounge>>>(emptyMap())

    /** Every known Lounge, by server id, then by channel id. */
    val lounges: StateFlow<Map<String, Map<String, RaftAgentLounge>>> = byServer.asStateFlow()

    fun lounge(serverId: String?, channelId: String?): RaftAgentLounge? {
        if (serverId.isNullOrBlank() || channelId.isNullOrBlank()) return null
        return byServer.value[serverId]?.get(channelId)
    }

    /** The Lounge with [channelId] in any server (a surface that does not know its server). */
    fun loungeAnyServer(channelId: String?): RaftAgentLounge? {
        if (channelId.isNullOrBlank()) return null
        return byServer.value.values.firstNotNullOfOrNull { it[channelId] }
    }

    fun loungeOfAgent(serverId: String?, agentId: String?): RaftAgentLounge? {
        if (serverId.isNullOrBlank() || agentId.isNullOrBlank()) return null
        return byServer.value[serverId]?.values?.firstOrNull { it.agentId == agentId }
    }

    /** Remember a Lounge row (opened, or loaded by id). [canPost] from the open answer; null keeps what was known. */
    fun rememberRow(serverId: String, row: HomeConversationRow, canPost: Boolean? = null, agentName: String? = null) {
        val agentId = row.loungeAgentId?.takeIf { it.isNotBlank() } ?: return
        val channelId = row.channelId ?: return
        remember(serverId, channelId.value) { previous ->
            RaftAgentLounge(
                channelId = channelId,
                agentId = agentId,
                agentName = agentName ?: previous?.agentName,
                channelName = row.name.ifBlank { previous?.channelName.orEmpty() },
                joined = row.joined,
                canPost = canPost ?: previous?.canPost,
                archived = row.archived,
            )
        }
    }

    /** Remember the Lounges an agent DM list carries (the agent's name is the DM row's). */
    fun rememberDirectMessages(serverId: String, directMessages: List<HomeDirectMessageRow>) {
        val carried =
            directMessages.mapNotNull { dm ->
                val lounge = dm.lounge ?: return@mapNotNull null
                val agentId = dm.presenceMemberId?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Triple(lounge, agentId, dm.name)
            }
        if (carried.isEmpty()) return
        byServer.update { all ->
            val current = all[serverId].orEmpty()
            val next = current.toMutableMap()
            for ((summary, agentId, agentName) in carried) {
                val previous = current[summary.channelId.value]
                next[summary.channelId.value] =
                    (previous ?: RaftAgentLounge(channelId = summary.channelId, agentId = agentId))
                        .copy(
                            agentId = agentId,
                            agentName = agentName.takeIf { it.isNotBlank() } ?: previous?.agentName,
                            joined = summary.joined || previous?.joined == true,
                        )
            }
            if (next == current) all else all + (serverId to next)
        }
    }

    /** The Lounge was unarchived (or archived) here. */
    fun markArchived(serverId: String, channelId: String, archived: Boolean) {
        remember(serverId, channelId) { previous -> previous?.copy(archived = archived) }
    }

    /** Your first post joined it. */
    fun markJoined(serverId: String, channelId: String) {
        remember(serverId, channelId) { previous -> previous?.copy(joined = true) }
    }

    /** Test hook and sign-out: forget every Lounge. */
    fun clear() {
        byServer.value = emptyMap()
    }

    private fun remember(serverId: String, channelId: String, transform: (RaftAgentLounge?) -> RaftAgentLounge?) {
        byServer.update { all ->
            val current = all[serverId].orEmpty()
            val next = transform(current[channelId]) ?: return@update all
            if (current[channelId] == next) all else all + (serverId to (current + (channelId to next)))
        }
    }
}
