package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val serverArchiveJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Settings › Server's Archived channels and Archived agents (botiverse/slock staging
 * 33f441958f82ce0178cc7f48232d41b20907e50c, web `components/settings/ArchivedChannelsSection.tsx`,
 * `ArchivedAgentsSection.tsx`, `features/settings/server/ServerTabContent.tsx`):
 *
 *     GET  /api/channels?archived=include  -> channel rows; the archived ones carry `archivedAt`
 *     POST /api/channels/:id/unarchive     -> the channel row (archiveChannels capability)
 *     GET  /api/agents/archived            -> { agents: ArchivedAgentEntry[] } (403 for a guest)
 *
 * Archived channels show only to someone who may archive (owner or admin, `getServerCapabilities`
 * `archiveChannels`); archived agents to anyone the list answers (a failure hides it).
 */
private const val CHANNELS_PATH = "channels"
private const val ARCHIVED_AGENTS_PATH = "agents/archived"

@Serializable
@RaftCoreInternalApi data class ArchivedChannelWireDto(
    val id: String,
    val name: String = "",
    val type: String = "",
    val archivedAt: String? = null,
    val hasExternalRelation: Boolean = false,
    val loungeAgentId: String? = null,
    /** `channelService.attachExternalBridgeMetadata` on the same list: [HomeChannelBridgeWireDto]. */
    val bridge: HomeChannelBridgeWireDto? = null,
)

@Serializable
@RaftCoreInternalApi data class ArchivedAgentWireDto(
    val agentId: String,
    val name: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val archivedAt: String = "",
    val workspaceState: String? = null,
    val pendingOp: String? = null,
    val machineName: String? = null,
)

@Serializable
@RaftCoreInternalApi data class ArchivedAgentsResponseDto(val agents: List<ArchivedAgentWireDto>)

/** An archived channel of the server (web `ArchivedChannel`): public, private or joint, with when. */
data class ArchivedChannel(
    val id: String,
    val name: String,
    /** The wire type: `channel`, `private` or `joint`. */
    val type: String,
    val archivedAt: String,
    val hasExternalRelation: Boolean,
    /** `bridge.provider` (`slack`): its row draws the Slack glyph, like the web's `ChannelIcon`. */
    val bridgeProvider: String? = null,
)

/** Where an archived agent's workspace is (web `ArchivedAgentEntry.workspaceState` / `pendingOp`). */
enum class ArchivedAgentWorkspace { OnComputer, Deleted, Unknown, Deleting }

data class ArchivedAgent(
    val agentId: String,
    /** `displayName || name`. */
    val name: String,
    val avatarUrl: String?,
    val archivedAt: String,
    val workspace: ArchivedAgentWorkspace,
    val machineName: String?,
)

private val ARCHIVED_CHANNEL_TYPES = setOf("channel", "private", "joint")

/**
 * The archived channels of a `GET /channels?archived=include` answer (web `isArchivedChannel`):
 * public, private or joint with `archivedAt`, newest archive first, then by name. Agent Lounges are
 * not channels of the list. Null when the body is not the list.
 */
fun parseSharedArchivedChannels(bodyText: String?): List<ArchivedChannel>? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val rows = runCatching { serverArchiveJson.decodeFromString(ListSerializer(ArchivedChannelWireDto.serializer()), body) }.getOrNull() ?: return null
    return rows
        .mapNotNull { row ->
            val archivedAt = row.archivedAt?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = row.type.trim().lowercase()
            if (type !in ARCHIVED_CHANNEL_TYPES || !row.loungeAgentId.isNullOrBlank()) return@mapNotNull null
            ArchivedChannel(row.id, row.name, type, archivedAt, row.hasExternalRelation, row.bridge.normalizedBridgeProviderOrNull())
        }
        .sortedWith(compareByDescending<ArchivedChannel> { it.archivedAt }.thenBy { it.name })
}

/** `GET /agents/archived`'s `{ agents }`; null when the body is not that shape. */
fun parseSharedArchivedAgents(bodyText: String?): List<ArchivedAgent>? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val response = runCatching { serverArchiveJson.decodeFromString(ArchivedAgentsResponseDto.serializer(), body) }.getOrNull() ?: return null
    return response.agents.map { entry ->
        ArchivedAgent(
            agentId = entry.agentId,
            name = entry.displayName?.takeIf { it.isNotBlank() } ?: entry.name,
            avatarUrl = entry.avatarUrl?.takeIf { it.isNotBlank() },
            archivedAt = entry.archivedAt,
            workspace =
                when {
                    entry.pendingOp != null -> ArchivedAgentWorkspace.Deleting
                    entry.workspaceState == "on_computer" -> ArchivedAgentWorkspace.OnComputer
                    entry.workspaceState == "deleted" -> ArchivedAgentWorkspace.Deleted
                    else -> ArchivedAgentWorkspace.Unknown
                },
            machineName = entry.machineName,
        )
    }
}

@RaftCoreInternalApi interface ServerArchiveDataSource {
    suspend fun archivedChannels(serverId: ServerId): DataResult<List<ArchivedChannel>>
    suspend fun archivedAgents(serverId: ServerId): DataResult<List<ArchivedAgent>>
    suspend fun unarchiveChannel(serverId: ServerId, channelId: String): DataResult<Unit>
}

@RaftCoreInternalApi class NetworkServerArchiveDataSource(private val networkClient: RaftNetworkClient) : ServerArchiveDataSource {
    private suspend fun <T> guarded(block: suspend () -> DataResult<T>): DataResult<T> =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }

    override suspend fun archivedChannels(serverId: ServerId): DataResult<List<ArchivedChannel>> =
        guarded {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = CHANNELS_PATH,
                    query = CHANNELS_INCLUDING_ARCHIVED_QUERY,
                    serverIdOverride = serverId,
                )
            val channels = if (response.isSuccessful) parseSharedArchivedChannels(response.bodyText) else null
            if (channels != null) {
                DataResult.Content(channels)
            } else {
                val envelope = response.parseHttpErrorEnvelope()
                DataResult.Error(
                    ScreenError(
                        code = "archived_channels_http_${response.code}",
                        retryable = response.code == 0 || response.code >= 500,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
        }

    override suspend fun archivedAgents(serverId: ServerId): DataResult<List<ArchivedAgent>> =
        guarded {
            val response = networkClient.request(DataRequestPriority.Visible, ARCHIVED_AGENTS_PATH, serverIdOverride = serverId)
            val agents = if (response.isSuccessful) parseSharedArchivedAgents(response.bodyText) else null
            if (agents != null) {
                DataResult.Content(agents)
            } else {
                val envelope = response.parseHttpErrorEnvelope()
                DataResult.Error(
                    ScreenError(
                        code = "archived_agents_http_${response.code}",
                        retryable = response.code == 0 || response.code >= 500,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
        }

    override suspend fun unarchiveChannel(serverId: ServerId, channelId: String): DataResult<Unit> =
        guarded {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "$CHANNELS_PATH/${encodeSignedInDevicePathSegment(channelId)}/unarchive",
                    method = "POST",
                    serverIdOverride = serverId,
                )
            if (response.isSuccessful) {
                DataResult.Content(Unit)
            } else {
                val envelope = response.parseHttpErrorEnvelope()
                DataResult.Error(
                    ScreenError(
                        code = "channel_unarchive_http_${response.code}",
                        retryable = response.code == 0 || response.code >= 500,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = response.traceId,
                    )
                )
            }
        }
}

@RaftCoreInternalApi data class SharedServerArchiveState(
    val serverId: String? = null,
    val channels: List<ArchivedChannel> = emptyList(),
    val agents: List<ArchivedAgent> = emptyList(),
    /** The channel whose Unarchive is running; every Unarchive waits meanwhile. */
    val unarchivingChannelId: String? = null,
    /** The last Unarchive failed: the server's sentence, or [unarchiveFailedGeneric]. */
    val unarchiveError: String? = null,
    val unarchiveFailedGeneric: Boolean = false,
)

@RaftCoreInternalApi sealed interface SharedServerArchiveIntent {
    /** The page shown for [serverId] (null: none selected); [canArchiveChannels]: owner or admin. */
    data class Load(val serverId: String?, val canArchiveChannels: Boolean) : SharedServerArchiveIntent
    data class Unarchive(val channelId: String) : SharedServerArchiveIntent
}

/**
 * Web ArchivedChannelsSection / ArchivedAgentsSection: both lists are advisory, so a failed read
 * keeps them hidden (empty). Archived channels are read only for someone who may archive. Unarchive
 * runs one at a time; the channel leaves the list on success, the error shows above the list otherwise.
 */
@RaftCoreInternalApi class SharedServerArchiveStore(
    private val dataSource: ServerArchiveDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SharedServerArchiveState())
    val state: StateFlow<SharedServerArchiveState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    fun dispatch(intent: SharedServerArchiveIntent) {
        when (intent) {
            is SharedServerArchiveIntent.Load -> load(intent.serverId, intent.canArchiveChannels)
            is SharedServerArchiveIntent.Unarchive -> unarchive(intent.channelId)
        }
    }

    private fun load(serverId: String?, canArchiveChannels: Boolean) {
        loadJob?.cancel()
        // Another server: nothing of the previous one stays on screen.
        if (serverId != mutableState.value.serverId) mutableState.value = SharedServerArchiveState(serverId = serverId)
        val id = serverId?.takeIf { it.isNotBlank() } ?: return
        loadJob =
            scope.launch {
                val channels =
                    if (canArchiveChannels) {
                        (dataSource.archivedChannels(ServerId(id)) as? DataResult.Content<List<ArchivedChannel>>)?.data.orEmpty()
                    } else {
                        emptyList()
                    }
                val agents = (dataSource.archivedAgents(ServerId(id)) as? DataResult.Content<List<ArchivedAgent>>)?.data.orEmpty()
                if (mutableState.value.serverId == serverId) {
                    mutableState.value = mutableState.value.copy(channels = channels, agents = agents)
                }
            }
    }

    private fun unarchive(channelId: String) {
        val current = mutableState.value
        val serverId = current.serverId ?: return
        if (current.unarchivingChannelId != null) return
        mutableState.value = current.copy(unarchivingChannelId = channelId, unarchiveError = null, unarchiveFailedGeneric = false)
        scope.launch {
            val result = dataSource.unarchiveChannel(ServerId(serverId), channelId)
            val state = mutableState.value
            if (state.serverId != serverId) return@launch
            mutableState.value =
                when (result) {
                    is DataResult.Content -> state.copy(channels = state.channels.filterNot { it.id == channelId }, unarchivingChannelId = null)
                    else -> {
                        val message = (result as? DataResult.Error)?.error?.serverMessage
                        state.copy(unarchivingChannelId = null, unarchiveError = message, unarchiveFailedGeneric = message == null)
                    }
                }
        }
    }
}

/** Web `getServerCapabilities(role).archiveChannels`: owner and admin. */
@RaftCoreInternalApi fun serverRoleCanArchiveChannels(role: String?): Boolean =
    when (role?.trim()?.lowercase()) {
        "owner", "admin" -> true
        else -> false
    }
