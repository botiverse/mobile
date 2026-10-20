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

/** Upstream `SERVER_GUEST_FEATURE_FLAG_KEY` (packages/shared/src/featureFlags.ts:12). */
const val SERVER_GUEST_FEATURE_FLAG_KEY: String = "server_guest_v0"

/**
 * `GET /api/servers/:id/public-visibility` (servers.ts:4535-4587).
 * `exposedChannels` is the exact set that becomes world-readable: non-deleted, non-archived
 * `type = "channel"` rows with `guestVisible = true`, ordered by name.
 */
@Serializable
data class ServerPublicVisibilityWireDto(
    val publiclyVisible: Boolean,
    val publicGuestJoinEnabled: Boolean,
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
 * `PATCH /api/servers/:id/public-visibility` response (servers.ts:4589-4640): the updated
 * `{ publiclyVisible, publicGuestJoinEnabled, publicSearchEnabled }`. Turning visibility off
 * also clears `publicGuestJoinEnabled` in the same update.
 */
@Serializable
data class ServerPublicVisibilityUpdateWireDto(
    val publiclyVisible: Boolean,
    val publicGuestJoinEnabled: Boolean,
    val publicSearchEnabled: Boolean? = null,
)

/** `PATCH /api/servers/:id/public-search` response `{ publicSearchEnabled }` (servers.ts:4646-4691). */
@Serializable
data class ServerPublicSearchUpdateWireDto(
    val publicSearchEnabled: Boolean,
)

/**
 * `PATCH /api/servers/:id/public-guest-join` response `{ publicGuestJoinEnabled }`
 * (servers.ts:4693-4740). 404 when `server_guest_v0` or `public_server_v0` is off; 409 when
 * enabling while the server is not public.
 */
@Serializable
data class ServerPublicGuestJoinUpdateWireDto(
    val publicGuestJoinEnabled: Boolean,
)

/** Request body for `PATCH /servers/:id/public-visibility` (`publiclyVisible` must be a boolean). */
@Serializable
data class ServerPublicVisibilityPatchBody(val publiclyVisible: Boolean)

/** Request body for `PATCH /servers/:id/public-search`. */
@Serializable
data class ServerPublicSearchPatchBody(val publicSearchEnabled: Boolean)

/** Request body for `PATCH /servers/:id/public-guest-join`. */
@Serializable
data class ServerPublicGuestJoinPatchBody(val publicGuestJoinEnabled: Boolean)

/**
 * `POST /api/feature-flags/evaluate` body used only to decide whether the Guest-join switch is
 * applicable. It evaluates `server_guest_v0` exactly as `PATCH public-guest-join` gates it
 * (servers.ts:4700-4703: `{ key, serverId, userId, platform: "web" }`, no client facts), so the
 * switch is shown iff the server would accept the write. Contract:
 * packages/server/src/routes/featureFlags.ts:28-75 (`{ serverId, keys, platform }` →
 * `{ evaluations: [{ key, enabled }] }`).
 */
@Serializable
data class ServerPublicGuestGateEvaluateBody(
    val serverId: String,
    val keys: List<String> = listOf(SERVER_GUEST_FEATURE_FLAG_KEY),
    val platform: String = "web",
)

@Serializable
private data class ServerPublicGuestGateEvaluateResponse(
    val evaluations: List<ServerPublicGuestGateEvaluation> = emptyList(),
)

@Serializable
private data class ServerPublicGuestGateEvaluation(
    val key: String,
    val enabled: Boolean = false,
)

@Serializable
private data class ServerPublicVisibilityErrorBody(val error: String? = null)

data class ServerPublicExposedChannel(
    val id: String,
    val name: String,
    val description: String?,
)

data class ServerPublicVisibilityReadback(
    val publiclyVisible: Boolean,
    val publicGuestJoinEnabled: Boolean,
    val publicSearchEnabled: Boolean,
    val slug: String,
    val exposedChannels: List<ServerPublicExposedChannel>,
)

data class ServerPublicVisibilityUpdate(
    val publiclyVisible: Boolean,
    val publicGuestJoinEnabled: Boolean,
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

fun encodeServerPublicGuestJoinPatchBody(publicGuestJoinEnabled: Boolean): String =
    serverPublicVisibilityJson.encodeToString(
        ServerPublicGuestJoinPatchBody.serializer(),
        ServerPublicGuestJoinPatchBody(publicGuestJoinEnabled),
    )

fun encodeServerPublicGuestGateEvaluateBody(serverId: ServerId): String =
    serverPublicVisibilityJson.encodeToString(
        ServerPublicGuestGateEvaluateBody.serializer(),
        ServerPublicGuestGateEvaluateBody(serverId = serverId.value),
    )

fun parseSharedServerPublicVisibility(bodyText: String): ServerPublicVisibilityReadback? =
    runCatching {
        val wire = serverPublicVisibilityJson.decodeFromString(ServerPublicVisibilityWireDto.serializer(), bodyText)
        ServerPublicVisibilityReadback(
            publiclyVisible = wire.publiclyVisible,
            publicGuestJoinEnabled = wire.publicGuestJoinEnabled,
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
            publicGuestJoinEnabled = wire.publicGuestJoinEnabled,
            publicSearchEnabled = wire.publicSearchEnabled,
        )
    }.getOrNull()

fun parseSharedServerPublicSearchUpdate(bodyText: String): Boolean? =
    runCatching {
        serverPublicVisibilityJson.decodeFromString(ServerPublicSearchUpdateWireDto.serializer(), bodyText).publicSearchEnabled
    }.getOrNull()

fun parseSharedServerPublicGuestJoinUpdate(bodyText: String): Boolean? =
    runCatching {
        serverPublicVisibilityJson.decodeFromString(ServerPublicGuestJoinUpdateWireDto.serializer(), bodyText).publicGuestJoinEnabled
    }.getOrNull()

/** `true` only when the evaluation for `server_guest_v0` is present and enabled (defaults off). */
fun parseSharedServerGuestGateEnabled(bodyText: String): Boolean =
    runCatching {
        serverPublicVisibilityJson.decodeFromString(ServerPublicGuestGateEvaluateResponse.serializer(), bodyText)
            .evaluations
            .firstOrNull { it.key == SERVER_GUEST_FEATURE_FLAG_KEY }
            ?.enabled == true
    }.getOrDefault(false)

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
    data class Loaded(
        val readback: ServerPublicVisibilityReadback,
        val guestJoinApplicable: Boolean,
    ) : ServerPublicVisibilityLoadResult

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

    suspend fun setPublicGuestJoinEnabled(serverId: ServerId, publicGuestJoinEnabled: Boolean): ServerPublicMutationResult<Boolean>
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
        return ServerPublicVisibilityLoadResult.Loaded(
            readback = readback,
            guestJoinApplicable = guestJoinGateEnabled(serverId),
        )
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

    override suspend fun setPublicGuestJoinEnabled(
        serverId: ServerId,
        publicGuestJoinEnabled: Boolean,
    ): ServerPublicMutationResult<Boolean> =
        patch(
            serverId = serverId,
            path = "servers/${serverId.value}/public-guest-join",
            bodyText = encodeServerPublicGuestJoinPatchBody(publicGuestJoinEnabled),
            parse = ::parseSharedServerPublicGuestJoinUpdate,
        )

    /**
     * Web shows the Guest-join switch iff `useServerFeatureFlag(SERVER_GUEST_FEATURE_FLAG_KEY)`
     * (SettingsPanel.tsx:2165, 2354), evaluated with platform "web". The mobile flag store reports
     * platform "mobile", which can disagree with the server's own gate, so evaluate the one key
     * with platform "web" here. Any failure keeps the switch hidden (flags default off).
     */
    private suspend fun guestJoinGateEnabled(serverId: ServerId): Boolean {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "feature-flags/evaluate",
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = encodeServerPublicGuestGateEvaluateBody(serverId),
                )
            }.getOrNull() ?: return false
        if (!response.isSuccessful) return false
        return response.bodyText?.let(::parseSharedServerGuestGateEnabled) == true
    }

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
