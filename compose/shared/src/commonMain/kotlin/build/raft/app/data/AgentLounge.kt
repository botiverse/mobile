package build.raft.app.data

import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
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

/**
 * The Lounge an agent DM row carries (`GET /channels/dm` `lounge`), with its unread from the unread
 * summary. [lastMessageId] / [lastMessageSeq]: the newest message Home counted live (a repeat
 * delivery of it, or of an older one, counts no unread again); the server sends neither.
 */
@Serializable
data class AgentLoungeSummary(
    val channelId: ChannelId,
    val joined: Boolean = false,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val lastMessageId: MessageId? = null,
    val lastMessageSeq: Long? = null,
)

/**
 * The server has Lounges on and this person is not a Guest (web `useAgentLoungeEnabled`). Unknown
 * (the flag unresolved, or the role not loaded yet): off.
 */
fun agentLoungeEnabled(flagEnabled: Boolean, serverRole: String?): Boolean {
    val role = serverRole?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return flagEnabled && !role.equals("guest", ignoreCase = true)
}

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
    /**
     * This person's Activity mute of the Lounge, from its own row (`GET /channels/:id`); null until
     * the client loaded it (a Lounge known only from its agent's DM row). Sidebar row and page Mute.
     */
    val activityNotificationPreference: ChannelActivityNotificationPreference? = null,
    /** The Lounge's latest activity (its row's `lastMessageAt`, then live messages): the sidebar's recency. */
    val lastMessageAt: String? = null,
) {
    /** The agent's name for "{Name}'s Lounge". */
    val displayAgentName: String
        get() = loungeAgentDisplayName(agentName, channelName)

    /** May this person post here: not archived and not refused by the open answer. */
    val postable: Boolean
        get() = !archived && canPost != false

    /** Its own row was loaded (mute known); a Lounge known only from a DM row is not. */
    val loaded: Boolean
        get() = activityNotificationPreference != null

    val activityMuted: Boolean
        get() = activityNotificationPreference?.activityMuted == true

    /**
     * Web `canToggleActivityMute` for a Lounge (a public channel): only once joined, and only once
     * its row (the mute it carries) is loaded. A stored mute keeps its Unmute.
     */
    val canToggleActivityMute: Boolean
        get() {
            val preference = activityNotificationPreference ?: return false
            return preference.activityMuted || (joined && preference.activityMuteSupported)
        }
}

/** The Lounge's own sidebar row (web `agentLoungeSidebarEntry`). */
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

    /**
     * `GET channels/:id` for a listed Lounge this client never loaded (web `ensureChannel` in
     * useSidebarItems, slock #13876): its row says whether you joined, your mute and its latest
     * activity, so its sidebar menu offers Mute and it sorts by recency. Remembered on success.
     */
    suspend fun loadLounge(serverId: ServerId, channelId: ChannelId): DataResult<HomeChannelRow> {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "channels/${channelId.value}",
                serverIdOverride = serverId,
            )
        if (!response.isSuccessful) {
            return DataResult.Error(
                ScreenError(code = "http_${response.code}", retryable = response.code >= 500, traceId = response.traceId)
            )
        }
        val row =
            response.bodyText
                ?.let(::parseSharedChannelRowResult)
                ?.takeIf { it.channelId == channelId && !it.loungeAgentId.isNullOrBlank() }
                ?: return DataResult.Error(ScreenError(code = "agent_lounge_load_contract_mismatch", retryable = false))
        RaftAgentLounges.rememberRow(serverId.value, row)
        return DataResult.Content(row)
    }

    /**
     * `POST channels/:id/unarchive` for an archived Lounge (web `LoungeUnarchiveAction`: no settings
     * sheet). The server answers with the row; the Lounge is known unarchived from then on.
     */
    suspend fun unarchive(serverId: ServerId, channelId: ChannelId): DataResult<HomeChannelRow> {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "channels/${channelId.value}/unarchive",
                method = "POST",
                serverIdOverride = serverId,
            )
        if (!response.isSuccessful) {
            val wireError = response.agentLoungeWireErrorOrNull()
            if (response.code == 403) {
                return DataResult.NoPermission(
                    PermissionBlocker(
                        code = wireError?.code?.takeIf { it.isNotBlank() } ?: "http_403",
                        requiredCapability = "archiveChannels",
                        serverMessage = wireError?.error,
                        traceId = response.traceId,
                    )
                )
            }
            return DataResult.Error(
                ScreenError(
                    code = "http_${response.code}",
                    retryable = response.code >= 500,
                    serverMessage = wireError?.error,
                    serverCode = wireError?.code,
                    traceId = response.traceId,
                )
            )
        }
        val row = response.bodyText?.let(::parseSharedChannelRowResult)?.takeIf { it.channelId == channelId }
            ?: return DataResult.Error(ScreenError(code = "agent_lounge_unarchive_contract_mismatch", retryable = false))
        RaftAgentLounges.markArchived(serverId.value, channelId.value, archived = row.archived)
        return DataResult.Content(row)
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
    val wireError = response.agentLoungeWireErrorOrNull()
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

private fun HomeNetworkResponse.agentLoungeWireErrorOrNull(): AgentLoungeErrorWireDto? {
    val body = (errorBodyText ?: bodyText)?.takeIf { it.isNotBlank() } ?: return null
    return try {
        agentLoungeJson.decodeFromString(AgentLoungeErrorWireDto.serializer(), body)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
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
                // The Lounge's own row (GET /channels/:id) carries your mute; the open answer
                // ([canPost] set) may not, so it keeps the mute already known.
                activityNotificationPreference =
                    if (canPost == null) {
                        row.activityNotificationPreference
                    } else {
                        row.activityNotificationPreference
                            .takeIf { it.activityMuteSupported || it.activityMuted }
                            ?: previous?.activityNotificationPreference
                    },
                lastMessageAt = latestLoungeActivity(previous?.lastMessageAt, row.lastMessageAt),
            )
        }
    }

    /** This person's mute of a known Lounge changed (the row menu, the page, another device). */
    fun applyActivityMute(serverId: String, channelId: String, preference: ChannelActivityNotificationPreference) {
        remember(serverId, channelId) { previous ->
            previous?.copy(
                // A mute echo may omit `activityMuteSupported`; a Lounge (a public channel) supports it.
                activityNotificationPreference =
                    preference.copy(activityMuteSupported = preference.activityMuteSupported || previous.activityNotificationPreference?.activityMuteSupported == true)
            )
        }
    }

    /** A message arrived in a known Lounge at [at]: its recency in the sidebar. */
    fun noteActivity(serverId: String, channelId: String, at: String?) {
        if (at.isNullOrBlank()) return
        remember(serverId, channelId) { previous ->
            previous?.copy(lastMessageAt = latestLoungeActivity(previous.lastMessageAt, at))
        }
    }

    private val loading = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Single flight for [AgentLoungeApi.loadLounge]: true when this call may load the Lounge
     * (nothing else is loading it); [endLoad] when done. A failed load is tried again next time
     * the sidebar asks.
     */
    fun beginLoad(serverId: String, channelId: String): Boolean {
        val key = "$serverId:$channelId"
        var began = false
        loading.update { current ->
            began = key !in current
            if (began) current + key else current
        }
        return began
    }

    fun endLoad(serverId: String, channelId: String) {
        val key = "$serverId:$channelId"
        loading.update { it - key }
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

    /**
     * The Lounge was unarchived (or archived) here. The open answer's `canPost` was computed from
     * `archivedAt` too, so unarchiving forgets it (absent = may post) until the next open says otherwise.
     */
    fun markArchived(serverId: String, channelId: String, archived: Boolean) {
        remember(serverId, channelId) { previous ->
            previous?.copy(archived = archived, canPost = if (archived) previous.canPost else null)
        }
    }

    /** Your first post joined it. */
    fun markJoined(serverId: String, channelId: String) {
        remember(serverId, channelId) { previous -> previous?.copy(joined = true) }
    }

    /** Sign-out (and test hook): forget every Lounge, and the account they were known for. */
    fun clear() {
        byServer.value = emptyMap()
        boundAccountId.value = null
        loading.value = emptySet()
    }

    /** Removed from (or deleted) server [serverId]: its Lounges go. */
    fun forgetServer(serverId: String) {
        byServer.update { all -> if (serverId in all) all - serverId else all }
    }

    /**
     * The signed-in account the Lounges are known for. Another account's joined / canPost / archived
     * state must never show: a different account forgets every Lounge first.
     */
    fun bindAccount(accountId: String?) {
        val clean = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val previous = boundAccountId.value
        if (previous == clean) return
        if (previous != null) byServer.value = emptyMap()
        boundAccountId.value = clean
    }

    private val boundAccountId = MutableStateFlow<String?>(null)

    private fun remember(serverId: String, channelId: String, transform: (RaftAgentLounge?) -> RaftAgentLounge?) {
        byServer.update { all ->
            val current = all[serverId].orEmpty()
            val next = transform(current[channelId]) ?: return@update all
            if (current[channelId] == next) all else all + (serverId to (current + (channelId to next)))
        }
    }
}

/** The later of two Lounge activity timestamps (unparseable ones lose to parseable ones). */
internal fun latestLoungeActivity(a: String?, b: String?): String? {
    val cleanA = a?.takeIf { it.isNotBlank() }
    val cleanB = b?.takeIf { it.isNotBlank() }
    if (cleanA == null) return cleanB
    if (cleanB == null) return cleanA
    val millisA = build.raft.app.model.parseRaftTimestampEpochMillisOrNull(cleanA)
    val millisB = build.raft.app.model.parseRaftTimestampEpochMillisOrNull(cleanB)
    return when {
        millisA == null -> cleanB
        millisB == null -> cleanA
        millisB > millisA -> cleanB
        else -> cleanA
    }
}

/**
 * A Lounge as a Home row (it is in no list): the Lounge [channelId] carried by one of this
 * snapshot's agent DM rows, or known by id here. Its unread and @ come from the DM row's summary,
 * its mute from the Lounge's own row. Lets the sidebar row's menu (read, unread, mute) and the
 * Lounge page's mute reach a conversation no list holds. Null for anything that is not a Lounge.
 */
internal fun HomeSnapshot.agentLoungeConversationRow(channelId: String): HomeChannelRow? {
    if (channelId.isBlank()) return null
    val carriedBy = directMessages.firstOrNull { it.lounge?.channelId?.value == channelId }
    val summary = carriedBy?.lounge
    val known = RaftAgentLounges.lounge(server.id.value, channelId)
    val agentId = known?.agentId ?: carriedBy?.presenceMemberId?.value?.takeIf { it.isNotBlank() } ?: return null
    return HomeChannelRow(
        threadId = ThreadId(channelId),
        channelId = ChannelId(channelId),
        channelType = "channel",
        name = known?.channelName.orEmpty(),
        unreadCount = summary?.unreadCount ?: 0,
        unreadCountKnown = summary != null,
        mentionCount = summary?.mentionCount ?: 0,
        mentionCountKnown = summary != null,
        lastMessageId = summary?.lastMessageId,
        lastMessageSeq = summary?.lastMessageSeq,
        lastMessageAt = known?.lastMessageAt,
        joined = known?.joined == true || summary?.joined == true,
        archived = known?.archived == true,
        activityNotificationPreference = known?.activityNotificationPreference ?: ChannelActivityNotificationPreference.Unsupported,
        loungeAgentId = agentId,
    )
}
