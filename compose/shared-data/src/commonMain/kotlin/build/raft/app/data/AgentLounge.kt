package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Agent Lounge v0 (zh 会客厅; botiverse/slock #proj-chat:30a58e4d): the one public conversation per
 * agent where the server's members talk with that agent; other agents may read it but never take
 * part. On the wire it is a plain `type: "channel"` that carries `loungeAgentId`, named with a
 * reserved slug fixed at creation (`_lounge-<agent>-<id4>`, slock #16272; older ones `<agent>-lounge`);
 * it is never listed (`GET /channels` leaves it out, and so does every channel list here), it has no
 * Join step (the first post joins), and it is always named after its agent's current name:
 * "{Name}'s Lounge", the bare "Lounge" while that name is unknown, never anything read from the
 * channel name (slock #16282). Agents address it `lounge:@<agent>` (slock #16279).
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
    /** Explicit sidebar visibility from server (`#proj-chat:#44`): null on older servers (fall back to [joined]). */
    val listed: Boolean? = null,
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

/**
 * The name a Lounge is shown under (web `loungeAgentDisplayName`, slock #16282): its agent's name,
 * or null while it is unknown; the caller then says the bare "Lounge". Never read from the
 * Lounge's channel name: that is a reserved slug fixed at creation, which does not follow a rename.
 */
fun loungeAgentDisplayName(agentName: String?): String? = agentName?.trim()?.takeIf { it.isNotEmpty() }

/** A Lounge the client knows: opened (POST /agents/:id/lounge), loaded by id, or carried on its agent's DM row. */
data class RaftAgentLounge(
    val channelId: ChannelId,
    val agentId: String,
    /**
     * The agent's display name when known: its DM row (by the Lounge's `loungeAgentId`, kept
     * current as Home reloads it), or the name the client opened it under; else null.
     */
    val agentName: String? = null,
    /** The Lounge's own channel name (a reserved slug): its Home row's name, never shown. May be blank. */
    val channelName: String = "",
    val joined: Boolean = false,
    /** Explicit sidebar visibility (`#proj-chat:#44`); null on older servers (fall back to [joined]). */
    val listed: Boolean? = null,
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
    /** The agent's name for "{Name}'s Lounge"; null while it is unknown (the bare "Lounge"). */
    val displayAgentName: String?
        get() = loungeAgentDisplayName(agentName)

    /** May this person post here: not archived and not refused by the open answer. */
    val postable: Boolean
        get() = !archived && canPost != false

    /** Its own row was loaded (mute known); a Lounge known only from a DM row is not. */
    val loaded: Boolean
        get() = activityNotificationPreference != null

    val activityMuted: Boolean
        get() = activityNotificationPreference?.activityMuted == true

    /**
     * #proj-chat:#44: Lounge has no channel membership, so Mute is removed; an already-muted
     * Lounge keeps its Unmute action so users can clear stored mute state.
     */
    val canToggleActivityMute: Boolean
        get() = activityNotificationPreference?.activityMuted == true
}

/**
 * Standalone listed Lounge for a member who has no DM row with that agent
 * (`GET /channels/dm?include=lounges` `lounges[]`, `#proj-chat:#44`).
 */
@Serializable
data class HomeStandaloneLoungeRow(
    val agentId: String,
    val agentName: String,
    val avatarWireValue: String? = null,
    val canPost: Boolean? = null,
    val lounge: AgentLoungeSummary,
)

/** The Lounge's own sidebar row (web `agentLoungeSidebarEntry`). */
data class AgentLoungeSidebarEntry(
    val channelId: ChannelId,
    val unreadCount: Int,
    val mentioned: Boolean,
)

/**
 * Whether an agent's Lounge has a sidebar row: reads the server's explicit `listed` boolean
 * (`#proj-chat:#44`, true when this reader follows a thread in the Lounge or is still a member),
 * falling back to `joined` when `listed` is absent on older servers, or shown whenever someone
 * mentioned you there. [known] is the Lounge the client holds (fresher, e.g. right after your
 * first post or thread follow); [summary] is what the DM or standalone Lounge entry carried.
 * When [followedThreadUnreadCount] is provided on the explicit-`listed` model, non-member Lounges
 * (`!joined`) track live followed-thread unread locally without waiting for a DM-list reload.
 */
fun agentLoungeSidebarEntry(
    summary: AgentLoungeSummary?,
    known: RaftAgentLounge?,
    followedThreadUnreadCount: Int? = null,
    followedThreadHasMention: Boolean? = null,
): AgentLoungeSidebarEntry? {
    val channelId = known?.channelId ?: summary?.channelId ?: return null
    val hasExplicitListed = known?.listed != null || summary?.listed != null
    val listed =
        when {
            known?.listed == true || summary?.listed == true -> true
            hasExplicitListed -> false
            else -> known?.joined == true || summary?.joined == true
        }
    val sameChannel = summary?.channelId == channelId
    val summaryUnread = if (sameChannel) summary?.unreadCount ?: 0 else 0
    val summaryMentioned = sameChannel && (summary?.mentionCount ?: 0) > 0
    val joined = known?.joined == true || summary?.joined == true
    val useLiveThreadOnly = hasExplicitListed && !joined && followedThreadUnreadCount != null
    val unreadCount =
        when {
            useLiveThreadOnly -> followedThreadUnreadCount.coerceAtLeast(0)
            hasExplicitListed && followedThreadUnreadCount != null ->
                maxOf(summaryUnread, followedThreadUnreadCount.coerceAtLeast(0))
            else -> summaryUnread
        }
    val mentioned =
        when {
            useLiveThreadOnly -> followedThreadHasMention == true
            hasExplicitListed && followedThreadHasMention != null -> summaryMentioned || followedThreadHasMention
            else -> summaryMentioned
        }
    if (!listed && !mentioned) return null
    return AgentLoungeSidebarEntry(
        channelId = channelId,
        unreadCount = unreadCount,
        mentioned = mentioned,
    )
}

/** POST /agents/:id/lounge answered: the Lounge's row (always a Lounge), and this person's standing in it. */
data class AgentLoungeOpenResult(
    val channel: HomeChannelRow,
    val joined: Boolean,
    val canPost: Boolean,
    val listed: Boolean? = null,
    val unreadCount: Int? = null,
    val mentionCount: Int? = null,
)

@Serializable
private data class AgentLoungeOpenWireDto(
    val channel: HomeChannelWireDto,
    val joined: Boolean? = null,
    val canPost: Boolean? = null,
    val listed: Boolean? = null,
    val unreadCount: Int? = null,
    val mentionCount: Int? = null,
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
        listed = wire.listed,
        unreadCount = wire.unreadCount,
        mentionCount = wire.mentionCount,
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
     * `GET /agents/:id/lounge` (slock #16297): the agent's Lounge when it has one this viewer may see,
     * in POST's shape, and never creates one: no Lounge, one the viewer can't see, the flag off, an
     * assistant or another server's agent all answer 404 [AGENT_LOUNGE_NOT_FOUND_ERROR_CODE]; a Guest
     * 403. For a `lounge:@agent` ref, which names an existing Lounge only (slock #16279); POST stays the
     * agent profile's explicit Lounge button.
     */
    suspend fun lookupLounge(serverId: ServerId?, agentId: String): DataResult<AgentLoungeOpenResult> {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "agents/$agentId/lounge",
                method = "GET",
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
            val envelope = response.parseHttpErrorEnvelope()
            return DataResult.Error(
                ScreenError(
                    code = "http_${response.code}",
                    retryable = response.code == 0 || response.code >= 500,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        }
        val row =
            response.bodyText
                ?.let(::parseSharedChannelRowResult)
                ?.takeIf { it.channelId == channelId && !it.loungeAgentId.isNullOrBlank() }
                ?: return DataResult.Error(ScreenError(code = "agent_lounge_load_contract_mismatch", retryable = false, traceId = response.traceId))
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
            val envelope = response.parseHttpErrorEnvelope()
            if (response.code == 403) {
                return DataResult.NoPermission(
                    PermissionBlocker(
                        code = envelope?.serverCode ?: "http_403",
                        requiredCapability = "archiveChannels",
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
            return DataResult.Error(
                ScreenError(
                    code = "http_${response.code}",
                    retryable = response.code == 0 || response.code >= 500,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        }
        val row = response.bodyText?.let(::parseSharedChannelRowResult)?.takeIf { it.channelId == channelId }
            ?: return DataResult.Error(ScreenError(code = "agent_lounge_unarchive_contract_mismatch", retryable = false, traceId = response.traceId))
        RaftAgentLounges.markArchived(serverId.value, channelId.value, archived = row.archived)
        return DataResult.Content(row)
    }
}

/** Map POST /agents/:id/lounge's answer (exposed for tests). */
@RaftCoreInternalApi fun agentLoungeOpenResponse(response: HomeNetworkResponse, agentId: String): DataResult<AgentLoungeOpenResult> {
    if (response.isSuccessful) {
        val body = response.bodyText?.takeIf { it.isNotBlank() }
        val parsed = body?.let { parseSharedAgentLoungeOpenResult(it, agentId) }
        return parsed?.let { DataResult.Content(it) }
            ?: DataResult.Error(ScreenError(code = "agent_lounge_contract_mismatch", retryable = false, traceId = response.traceId))
    }
    val envelope = response.parseHttpErrorEnvelope()
    return when (response.code) {
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = envelope?.serverCode ?: "http_403",
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        404 ->
            DataResult.Error(
                ScreenError(
                    code = AGENT_LOUNGE_NOT_FOUND_ERROR_CODE,
                    retryable = false,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
                    traceId = response.traceId,
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = "http_${response.code}",
                    retryable = response.code == 0 || response.code >= 500,
                    serverMessage = envelope?.serverMessage,
                    serverCode = envelope?.serverCode,
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

    /** Remember a Lounge row (opened, or loaded by id). [canPost] / [listed] from the open answer; null keeps what was known. */
    fun rememberRow(
        serverId: String,
        row: HomeConversationRow,
        canPost: Boolean? = null,
        agentName: String? = null,
        listed: Boolean? = null,
    ) {
        val agentId = row.loungeAgentId?.takeIf { it.isNotBlank() } ?: return
        val channelId = row.channelId ?: return
        remember(serverId, channelId.value) { previous ->
            RaftAgentLounge(
                channelId = channelId,
                agentId = agentId,
                agentName = agentName ?: previous?.agentName,
                channelName = row.name.ifBlank { previous?.channelName.orEmpty() },
                joined = row.joined,
                listed = listed ?: previous?.listed,
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

    private val followSeqClock = atomic(0L)
    private val followedAtByServer = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())

    /** Monotonic clock for local Lounge follows vs DM-list request ordering (`slock #16474` `nextPatchSeq`). */
    fun nextFollowSequence(): Long = followSeqClock.incrementAndGet()

    /** True if a local follow/post in [channelId] on [serverId] happened after [requestSeq]. */
    fun followedSince(serverId: String, channelId: String, requestSeq: Long): Boolean {
        if (requestSeq == Long.MAX_VALUE) return false
        val stamp = followedAtByServer.value[serverId]?.get(channelId) ?: return false
        return stamp > requestSeq
    }

    /** Remember the Lounges an agent DM list carries (the agent's name is the DM row's). */
    fun rememberDirectMessages(serverId: String, directMessages: List<HomeDirectMessageRow>) {
        reconcileDirectMessages(
            serverId = serverId,
            directMessages = directMessages,
            standaloneLounges = emptyList(),
            namesLounges = false,
            requestSeq = Long.MAX_VALUE,
        )
    }

    /** Remember listed Lounges returned outside DM rows (`GET /channels/dm?include=lounges` `lounges[]`). */
    fun rememberStandaloneLounges(serverId: String, standaloneLounges: List<HomeStandaloneLoungeRow>) {
        if (standaloneLounges.isEmpty()) return
        reconcileDirectMessages(
            serverId = serverId,
            directMessages = emptyList(),
            standaloneLounges = standaloneLounges,
            namesLounges = false,
            requestSeq = Long.MAX_VALUE,
        )
    }

    /**
     * Authoritatively reconciles `RaftAgentLounges` for [serverId] with a `GET /channels/dm?include=lounges`
     * answer (`slock #16474` `hydrateDmChannels` + `#16479`):
     * - Updates/inserts every DM Lounge summary and standalone Lounge entry.
     * - If a local follow was stamped AFTER [requestSeq], keeps `listed = true` even if this older
     *   in-flight answer still says `listed = false` or omits the standalone Lounge.
     * - When [namesLounges] is true (the answer carried an explicit `lounges` array), any known Lounge
     *   whose `listed != null` is absent from both [directMessages] and [standaloneLounges] (and not
     *   followed since [requestSeq]) is settled to `listed = false` so unfollowing the last thread
     *   cleanly unlists standalone Lounges.
     * - When [namesLounges] is false (`#16479`, bare array or object without `lounges`), absent Lounges
     *   are left untouched (unlists nothing).
     */
    fun reconcileDirectMessages(
        serverId: String,
        directMessages: List<HomeDirectMessageRow>,
        standaloneLounges: List<HomeStandaloneLoungeRow>,
        namesLounges: Boolean,
        requestSeq: Long = Long.MAX_VALUE,
    ) {
        val carried =
            directMessages.mapNotNull { dm ->
                val lounge = dm.lounge ?: return@mapNotNull null
                val agentId = dm.presenceMemberId?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Triple(lounge, agentId, dm.name)
            }
        val stamps = followedAtByServer.value[serverId].orEmpty()
        fun isFollowedSince(channelId: String): Boolean =
            requestSeq != Long.MAX_VALUE && (stamps[channelId] ?: 0L) > requestSeq

        val saidIds = HashSet<String>(carried.size + standaloneLounges.size)
        if (carried.isNotEmpty() || standaloneLounges.isNotEmpty() || namesLounges) {
            byServer.update { all ->
                val current = all[serverId].orEmpty()
                val next = current.toMutableMap()
                for ((summary, agentId, agentName) in carried) {
                    val id = summary.channelId.value
                    saidIds += id
                    val previous = current[id]
                    val resolvedListed =
                        when {
                            summary.listed == false && isFollowedSince(id) -> true
                            summary.listed != null -> summary.listed
                            else -> previous?.listed
                        }
                    next[id] =
                        (previous ?: RaftAgentLounge(channelId = summary.channelId, agentId = agentId))
                            .copy(
                                agentId = agentId,
                                agentName = agentName.takeIf { it.isNotBlank() } ?: previous?.agentName,
                                joined = if (summary.listed != null) summary.joined else (summary.joined || previous?.joined == true),
                                listed = resolvedListed,
                            )
                }
                for (row in standaloneLounges) {
                    val summary = row.lounge
                    val id = summary.channelId.value
                    saidIds += id
                    val previous = current[id]
                    val resolvedListed =
                        when {
                            summary.listed == false && isFollowedSince(id) -> true
                            summary.listed != null -> summary.listed
                            else -> previous?.listed
                        }
                    next[id] =
                        (previous ?: RaftAgentLounge(channelId = summary.channelId, agentId = row.agentId))
                            .copy(
                                agentId = row.agentId,
                                agentName = row.agentName.takeIf { it.isNotBlank() } ?: previous?.agentName,
                                joined = if (summary.listed != null) summary.joined else (summary.joined || previous?.joined == true),
                                listed = resolvedListed,
                                canPost = row.canPost ?: previous?.canPost,
                            )
                }
                if (namesLounges) {
                    for ((id, previous) in current) {
                        if (id !in saidIds && previous.listed == true && !isFollowedSince(id)) {
                            next[id] = previous.copy(listed = false)
                        }
                    }
                }
                if (next == current) all else all + (serverId to next)
            }
        }
        if (stamps.isNotEmpty()) {
            followedAtByServer.update { all ->
                val current = all[serverId] ?: return@update all
                val remaining =
                    if (requestSeq == Long.MAX_VALUE) emptyMap()
                    else current.filterValues { seq -> seq > requestSeq }
                if (remaining.size == current.size) {
                    all
                } else if (remaining.isEmpty()) {
                    all - serverId
                } else {
                    all + (serverId to remaining)
                }
            }
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

    /** Your first post joined it (older servers) or auto-followed your thread (`listed = true`, `#proj-chat:#44`). */
    fun markJoined(serverId: String, channelId: String) {
        stampLocalFollow(serverId, channelId)
        remember(serverId, channelId) { previous ->
            previous?.copy(
                joined = if (previous.listed != null) previous.joined else true,
                listed = true,
            )
        }
    }

    /**
     * Following a thread in this Lounge lists it locally without waiting for `GET /channels/dm`
     * (`#proj-chat:#44`, `slock #16474`). When [localFollow] is true, stamps a local follow sequence
     * so an older in-flight DM list answer does not undo it.
     */
    fun markListed(serverId: String, channelId: String, localFollow: Boolean = true) {
        if (localFollow) stampLocalFollow(serverId, channelId)
        remember(serverId, channelId) { previous ->
            previous?.copy(listed = true)
        }
    }

    private fun stampLocalFollow(serverId: String, channelId: String) {
        if (serverId.isBlank() || channelId.isBlank()) return
        val seq = nextFollowSequence()
        followedAtByServer.update { all ->
            val current = all[serverId].orEmpty()
            all + (serverId to (current + (channelId to seq)))
        }
    }

    /** Sign-out (and test hook): forget every Lounge, and the account they were known for. */
    fun clear() {
        byServer.value = emptyMap()
        followedAtByServer.value = emptyMap()
        boundAccountId.value = null
        loading.value = emptySet()
    }

    /** Removed from (or deleted) server [serverId]: its Lounges go. */
    fun forgetServer(serverId: String) {
        byServer.update { all -> if (serverId in all) all - serverId else all }
        followedAtByServer.update { all -> if (serverId in all) all - serverId else all }
    }

    /**
     * The signed-in account the Lounges are known for. Another account's joined / canPost / archived
     * state must never show: a different account forgets every Lounge first.
     */
    fun bindAccount(accountId: String?) {
        val clean = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val previous = boundAccountId.value
        if (previous == clean) return
        if (previous != null) {
            byServer.value = emptyMap()
            followedAtByServer.value = emptyMap()
        }
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
@RaftCoreInternalApi fun latestLoungeActivity(a: String?, b: String?): String? {
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
@RaftCoreInternalApi fun HomeSnapshot.agentLoungeConversationRow(channelId: String): HomeChannelRow? {
    if (channelId.isBlank()) return null
    val carriedBy = directMessages.firstOrNull { it.lounge?.channelId?.value == channelId }
    val standalone = if (carriedBy == null) standaloneLounges.firstOrNull { it.lounge.channelId.value == channelId } else null
    val summary = carriedBy?.lounge ?: standalone?.lounge
    val known = RaftAgentLounges.lounge(server.id.value, channelId)
    val agentId =
        known?.agentId
            ?: carriedBy?.presenceMemberId?.value?.takeIf { it.isNotBlank() }
            ?: standalone?.agentId?.takeIf { it.isNotBlank() }
            ?: return null
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

/** Server acknowledgement of `join:channel` (`channel:joined { channelId }`, `slock #16493`). */
@RaftCoreInternalApi data class RaftChannelJoinedSignal(
    val serverId: String,
    val channelId: String,
)

/**
 * Process-wide `channel:joined` acknowledgements so an open Agent Lounge catches up from its tail
 * when its on-demand socket room finishes joining (`slock #16493` `socketBridge.ts` `channelJoined`).
 */
@RaftCoreInternalApi class RaftChannelJoinedSignals {
    private val _events =
        MutableSharedFlow<RaftChannelJoinedSignal>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RaftChannelJoinedSignal> = _events.asSharedFlow()

    fun publish(serverId: String, channelId: String) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanChannel = channelId.trim().takeIf { it.isNotEmpty() } ?: return
        _events.tryEmit(RaftChannelJoinedSignal(cleanServer, cleanChannel))
    }
}

/**
 * `slock #16493` (`agentLoungeRoomCatchUp.test.ts`): only the `channel:joined` ack of the currently
 * open Agent Lounge re-reads from its tail. Acknowledgements of ordinary channels, reply threads,
 * other Lounges, or other servers read nothing.
 */
@RaftCoreInternalApi fun shouldCatchUpOpenLoungeOnChannelJoined(
    openServerId: String?,
    openChannelId: String?,
    isOpenChannelLounge: Boolean,
    ackServerId: String?,
    ackChannelId: String?,
): Boolean {
    if (!isOpenChannelLounge) return false
    val server = openServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val channel = openChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return ackServerId?.trim() == server && ackChannelId?.trim() == channel
}
