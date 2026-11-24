package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Owner-only "Public access" server settings (public server phase 1).
 *
 * Contract source: botiverse/slock@9df0d1b3f packages/server/src/routes/servers.ts:4524-4740.
 * Every route answers 403 for a non-owner and 404 while the `public_server_v0` gate (evaluated
 * server-side with platform "web") is off for the server. Web UI reference:
 * packages/web/src/components/settings/SettingsPanel.tsx:2069-2436 (PublicVisibilitySection).
 */

private val serverPublicVisibilityJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * `GET /api/servers/:id/public-visibility` (servers.ts:4535-4587).
 * `exposedChannels` is the exact set that becomes world-readable: non-deleted, non-archived
 * `type = "channel"` rows with `guestVisible = true`, ordered by name.
 */
@Serializable
data class ServerPublicVisibilityWireDto(
    val publiclyVisible: Boolean,
    val publicSearchEnabled: Boolean,
    val slug: String,
    val exposedChannels: List<ServerPublicExposedChannelWireDto> = emptyList(),
)

/** One `exposedChannels` row: `{ id, name, description }` (servers.ts:4566-4576); description is nullable. */
@Serializable
data class ServerPublicExposedChannelWireDto(
    val id: String,
    val name: String,
    val description: String? = null,
)

/**
 * `PATCH /api/servers/:id/public-visibility` response: the updated
 * `{ publiclyVisible, publicSearchEnabled }`. Public Guest join was retired (botiverse/slock
 * #13829): the server no longer sends `publicGuestJoinEnabled`.
 */
@Serializable
data class ServerPublicVisibilityUpdateWireDto(
    val publiclyVisible: Boolean,
    val publicSearchEnabled: Boolean? = null,
)

/** `PATCH /api/servers/:id/public-search` response `{ publicSearchEnabled }` (servers.ts:4646-4691). */
@Serializable
data class ServerPublicSearchUpdateWireDto(
    val publicSearchEnabled: Boolean,
)

/** Request body for `PATCH /servers/:id/public-visibility` (`publiclyVisible` must be a boolean). */
@Serializable
data class ServerPublicVisibilityPatchBody(val publiclyVisible: Boolean)

/** Request body for `PATCH /servers/:id/public-search`. */
@Serializable
data class ServerPublicSearchPatchBody(val publicSearchEnabled: Boolean)

@Serializable
private data class ServerPublicVisibilityErrorBody(val error: String? = null)

data class ServerPublicExposedChannel(
    val id: String,
    val name: String,
    val description: String?,
)

data class ServerPublicVisibilityReadback(
    val publiclyVisible: Boolean,
    val publicSearchEnabled: Boolean,
    val slug: String,
    val exposedChannels: List<ServerPublicExposedChannel>,
)

data class ServerPublicVisibilityUpdate(
    val publiclyVisible: Boolean,
    val publicSearchEnabled: Boolean?,
)

fun encodeServerPublicVisibilityPatchBody(publiclyVisible: Boolean): String =
    serverPublicVisibilityJson.encodeToString(
        ServerPublicVisibilityPatchBody.serializer(),
        ServerPublicVisibilityPatchBody(publiclyVisible),
    )

fun encodeServerPublicSearchPatchBody(publicSearchEnabled: Boolean): String =
    serverPublicVisibilityJson.encodeToString(
        ServerPublicSearchPatchBody.serializer(),
        ServerPublicSearchPatchBody(publicSearchEnabled),
    )

fun parseSharedServerPublicVisibility(bodyText: String): ServerPublicVisibilityReadback? =
    runCatching {
        val wire = serverPublicVisibilityJson.decodeFromString(ServerPublicVisibilityWireDto.serializer(), bodyText)
        ServerPublicVisibilityReadback(
            publiclyVisible = wire.publiclyVisible,
            publicSearchEnabled = wire.publicSearchEnabled,
            slug = wire.slug,
            exposedChannels =
                wire.exposedChannels.map { channel ->
                    ServerPublicExposedChannel(
                        id = channel.id,
                        name = channel.name,
                        description = channel.description?.takeIf { it.isNotBlank() },
                    )
                },
        )
    }.getOrNull()

fun parseSharedServerPublicVisibilityUpdate(bodyText: String): ServerPublicVisibilityUpdate? =
    runCatching {
        val wire = serverPublicVisibilityJson.decodeFromString(ServerPublicVisibilityUpdateWireDto.serializer(), bodyText)
        ServerPublicVisibilityUpdate(
            publiclyVisible = wire.publiclyVisible,
            publicSearchEnabled = wire.publicSearchEnabled,
        )
    }.getOrNull()

fun parseSharedServerPublicSearchUpdate(bodyText: String): Boolean? =
    runCatching {
        serverPublicVisibilityJson.decodeFromString(ServerPublicSearchUpdateWireDto.serializer(), bodyText).publicSearchEnabled
    }.getOrNull()

/** The server's `{ error }` text, which web shows in preference to its localized fallback. */
fun parseSharedServerPublicVisibilityErrorText(bodyText: String?): String? =
    bodyText
        ?.takeIf { it.isNotBlank() }
        ?.let { text ->
            runCatching {
                serverPublicVisibilityJson.decodeFromString(ServerPublicVisibilityErrorBody.serializer(), text).error
            }.getOrNull()
        }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

sealed interface ServerPublicVisibilityLoadResult {
    data class Loaded(val readback: ServerPublicVisibilityReadback) : ServerPublicVisibilityLoadResult

    /**
     * 404 (`public_server_v0` off for this server) or 403 (caller is not the owner): the section
     * is hidden, matching web which renders nothing when the gate is off or role != owner.
     */
    data object Unavailable : ServerPublicVisibilityLoadResult

    data class Failed(val serverMessage: String?) : ServerPublicVisibilityLoadResult
}

sealed interface ServerPublicMutationResult<out T> {
    data class Success<T>(val value: T) : ServerPublicMutationResult<T>
    data class Failed(val code: Int, val serverMessage: String?) : ServerPublicMutationResult<Nothing>
}

/** Shared API orchestration for the owner Public access section. */
interface ServerPublicVisibilityRepository {
    suspend fun load(serverId: ServerId): ServerPublicVisibilityLoadResult

    suspend fun setPubliclyVisible(serverId: ServerId, publiclyVisible: Boolean): ServerPublicMutationResult<ServerPublicVisibilityUpdate>

    suspend fun setPublicSearchEnabled(serverId: ServerId, publicSearchEnabled: Boolean): ServerPublicMutationResult<Boolean>
}

class NetworkServerPublicVisibilityRepository(
    private val networkClient: RaftNetworkClient,
) : ServerPublicVisibilityRepository {
    override suspend fun load(serverId: ServerId): ServerPublicVisibilityLoadResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "servers/${serverId.value}/public-visibility",
                    serverIdOverride = serverId,
                )
            }.getOrNull() ?: return ServerPublicVisibilityLoadResult.Failed(serverMessage = null)
        if (response.code == 404 || response.code == 403) return ServerPublicVisibilityLoadResult.Unavailable
        if (!response.isSuccessful) {
            return ServerPublicVisibilityLoadResult.Failed(response.serverErrorText())
        }
        val readback =
            response.bodyText?.let(::parseSharedServerPublicVisibility)
                ?: return ServerPublicVisibilityLoadResult.Failed(serverMessage = null)
        return ServerPublicVisibilityLoadResult.Loaded(readback)
    }

    override suspend fun setPubliclyVisible(
        serverId: ServerId,
        publiclyVisible: Boolean,
    ): ServerPublicMutationResult<ServerPublicVisibilityUpdate> =
        patch(
            serverId = serverId,
            path = "servers/${serverId.value}/public-visibility",
            bodyText = encodeServerPublicVisibilityPatchBody(publiclyVisible),
            parse = ::parseSharedServerPublicVisibilityUpdate,
        )

    override suspend fun setPublicSearchEnabled(
        serverId: ServerId,
        publicSearchEnabled: Boolean,
    ): ServerPublicMutationResult<Boolean> =
        patch(
            serverId = serverId,
            path = "servers/${serverId.value}/public-search",
            bodyText = encodeServerPublicSearchPatchBody(publicSearchEnabled),
            parse = ::parseSharedServerPublicSearchUpdate,
        )

    private suspend fun <T> patch(
        serverId: ServerId,
        path: String,
        bodyText: String,
        parse: (String) -> T?,
    ): ServerPublicMutationResult<T> {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = path,
                    method = "PATCH",
                    serverIdOverride = serverId,
                    bodyText = bodyText,
                )
            }.getOrNull() ?: return ServerPublicMutationResult.Failed(code = 0, serverMessage = null)
        if (!response.isSuccessful) {
            return ServerPublicMutationResult.Failed(response.code, response.serverErrorText())
        }
        val value =
            response.bodyText?.let(parse)
                ?: return ServerPublicMutationResult.Failed(response.code, serverMessage = null)
        return ServerPublicMutationResult.Success(value)
    }

    private fun HomeNetworkResponse.serverErrorText(): String? =
        parseSharedServerPublicVisibilityErrorText(bodyText ?: errorBodyText)
}
