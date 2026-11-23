package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed joint-channel (cross-server) connection boundary.
 *
 * Server contract, botiverse/slock `staging` @ 7d349b509d6511eb922f7870099017fdda0d3f61:
 *  - `GET channels/:id` (`packages/server/src/routes/channels.ts` `channelRouter.get("/:id")`)
 *    → the channel row run through `channelService.attachJointChannelMetadata`, i.e. the
 *    `JointChannelMetadata` fields (`jointServers[]`, `jointPendingInvites[]`, `jointPeer*`).
 *  - `POST channels/:id/disconnect` → `{ ok: true }`.
 *  - `POST channels/:id/joint-servers/:serverId/disconnect` (botiverse/slock `staging` @
 *    f3c3905046d16cd4a7225a533a071f667b687647, `routes/channels.ts`): the host's owner/admin takes
 *    another server out of the joint → `{ ok: true }`; 400 `not_joint_host` /
 *    `host_cannot_be_disconnected`, 403 `forbidden`, 404 `server_not_connected`, 409 `guests_present`
 *    (`routes/channels.jointHostDisconnect.api.test.ts`).
 *  - The channel row's `jointRole` (`"host" | "participant" | null`), `jointServers[].role` (same
 *    union, null on pending rows) and `channelCapabilities.federateChannels`
 *    (`services/channelService.ts` `JointChannelMetadata` / `JointServerMetadata`,
 *    `services/channelListItems.ts` `withHumanChannelAuthorization`), same SHA.
 *  - `GET channels/joint-invites` → `{ invites: [{ id, jointChannelId, fromServerId,
 *    fromServerName, fromServerSlug, channelName, channelDescription, invitedByUserId,
 *    invitedUserId, expiresAt, createdAt }] }` (`channelService.listPendingJointChannelInvites`).
 *  - `POST channels/joint-invites/:inviteId/accept` → the accepted channel (with joint metadata);
 *    403/404/409 `{ error }` or a limit `{ error, code }`.
 *
 * Creating a joint channel (`POST channels` with `visibility: "joint"`), inviting another server
 * (`POST channels/:id/joint-invites`) and resending those invites answer 404 since botiverse/slock
 * #15939: another server joins through the external channel flow instead (ExternalInvitesApi.kt).
 *
 * Web reference: `packages/web/src/components/channel/EditChannelDialog.tsx` (connected servers,
 * disconnect) and `components/layout/usePendingJointInvites.ts` + `useSystemNotifications.tsx`
 * (pending invite → Accept).
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */
internal const val JOINT_CHANNEL_MAX_SERVERS = 30

/** `JointChannelLimitErrorCode` (`packages/server/src/services/jointChannelLimitService.ts`). */
internal const val JOINT_FREE_SERVER_LIMIT = "joint_free_server_limit"
internal const val JOINT_SERVER_LIMIT = "joint_server_limit"

/** A host on a plan with a hosted-joint limit (AppSumo) is at it (botiverse/slock #15952). */
internal const val JOINT_CHANNEL_PLAN_LIMIT_REACHED = "joint_channel_plan_limit_reached"

/** Joint-invite accept refusal codes (botiverse/slock #16117, `jointChannelLimit.ts`). */
internal const val JOINT_INVITE_NOT_FOUND = "joint_invite_not_found"
internal const val JOINT_INVITE_ACCEPT_FORBIDDEN = "joint_invite_accept_forbidden"
internal const val JOINT_INVITE_NOT_ACCEPTABLE = "joint_invite_not_acceptable"
internal const val JOINT_INVITE_CHANNEL_ARCHIVED = "channel_archived"

private val jointChannelsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class WireJointServer(
    val serverId: String,
    val serverName: String = "",
    val serverSlug: String = "",
    val serverAvatarUrl: String? = null,
    val role: String? = null,
    val status: String,
    val isCurrentServer: Boolean = false,
    val plan: String? = null,
)

@Serializable
private data class WireJointPendingInvite(
    val id: String,
    val fromServerId: String,
    val toServerId: String,
    val serverName: String = "",
    val serverSlug: String = "",
    val invitedUserId: String = "",
    val status: String = "pending",
)

@Serializable
private data class WireJointChannel(
    val id: String,
    val serverId: String? = null,
    val name: String = "",
    val type: String,
    val archivedAt: String? = null,
    /** Whether the caller is in the channel (`GET channels/:id` sends it, as the list does). */
    val joined: Boolean? = null,
    val jointRole: String? = null,
    val channelCapabilities: WireJointChannelCapabilities? = null,
    val jointPeerServerId: String? = null,
    val jointPeerServerName: String? = null,
    val jointPeerServerSlug: String? = null,
    val jointPeerStatus: String? = null,
    val jointServers: List<WireJointServer>? = null,
    val jointPendingInvites: List<WireJointPendingInvite>? = null,
)

/** Only the key the Connected servers page reads; the rest of `channelCapabilities` is ignored here. */
@Serializable
private data class WireJointChannelCapabilities(
    val federateChannels: Boolean? = null,
)

@Serializable
private data class WirePendingJointInvite(
    val id: String,
    val jointChannelId: String? = null,
    val fromServerId: String? = null,
    val fromServerName: String = "",
    val fromServerSlug: String = "",
    val channelName: String = "",
    val channelDescription: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
private data class WirePendingJointInvitesResponse(
    val invites: List<WirePendingJointInvite>,
)

@Serializable
private data class WireJointOkResponse(
    val ok: Boolean,
)

@Serializable
private data class WireJointErrorBody(
    val error: String? = null,
    val code: String? = null,
)

/** `archived_name_collision`: 409 from `POST channels` when an archived channel holds the name. */
internal const val ARCHIVED_NAME_COLLISION = "archived_name_collision"

enum class JointChannelServerPlan { Free, Paid }

/** `jointRole` / `jointServers[].role`: `"host" | "participant"`; null when the server sent neither. */
enum class JointChannelRole {
    Host,
    Participant,
    ;

    companion object {
        fun fromWire(value: String?): JointChannelRole? =
            when (value) {
                "host" -> Host
                "participant" -> Participant
                else -> null
            }
    }
}

data class JointChannelServer(
    val serverId: String,
    val name: String,
    val slug: String,
    val active: Boolean,
    val isCurrentServer: Boolean,
    /** Null when the server omits it (older servers) — the web then shows no plan badge. */
    val plan: JointChannelServerPlan?,
    /** Null on pending rows and on the legacy single-peer fallback. */
    val role: JointChannelRole? = null,
    /** The server's logo (`jointServers[].serverAvatarUrl`, slock #15809); null for pending or none. */
    val avatarUrl: String? = null,
)

data class JointChannelConnections(
    val channelId: ChannelId,
    val channelName: String,
    val isJoint: Boolean,
    val archived: Boolean,
    /** Active servers first, then pending targets, exactly as the server orders `jointServers`. */
    val servers: List<JointChannelServer>,
    /** Web `hasCurrentServerPendingJointInvites`: invites this server sent are still pending. */
    val hasCurrentServerPendingInvites: Boolean,
    /** This server's role in the joint (`jointRole`); null on a payload without it. */
    val jointRole: JointChannelRole? = null,
    /** The channel's own `channelCapabilities.federateChannels`; null when the payload has none. */
    val federateChannels: Boolean? = null,
    /** `type == "private"`: a member may connect a server, which makes it joint (web memberConnect). */
    val isPrivate: Boolean = false,
    /** The channel row's `joined`; null when the payload has none. */
    val joined: Boolean? = null,
)

data class PendingJointInvite(
    val id: String,
    val fromServerName: String,
    val fromServerSlug: String,
    val channelName: String,
    val expiresAt: String?,
)

data class JointChannelAccepted(
    val channelId: ChannelId,
    val channelName: String,
)

internal fun parseSharedJointChannelConnections(bodyText: String): JointChannelConnections? = runCatching {
    jointChannelsJson.decodeFromString(WireJointChannel.serializer(), bodyText).toConnections()
}.getOrNull()

private fun WireJointChannel.toConnections(): JointChannelConnections? {
    val channelId = id.trim().takeIf(String::isNotBlank) ?: return null
    val metadataServers = jointServers.orEmpty()
    // Web EditChannelDialog: older payloads without `jointServers` still name one peer.
    val servers =
        if (metadataServers.isNotEmpty()) {
            metadataServers.mapNotNull { it.toDomain() }
        } else {
            val peerId = jointPeerServerId?.takeIf(String::isNotBlank) ?: jointPeerServerSlug?.takeIf(String::isNotBlank)
            if (peerId == null) {
                emptyList()
            } else {
                listOf(
                    JointChannelServer(
                        serverId = peerId,
                        name = jointPeerServerName?.takeIf(String::isNotBlank) ?: jointPeerServerSlug.orEmpty(),
                        slug = jointPeerServerSlug.orEmpty(),
                        active = jointPeerStatus != "pending",
                        isCurrentServer = false,
                        plan = null,
                    )
                )
            }
        }
    val pendingFromCurrent =
        if (jointPendingInvites != null) {
            jointPendingInvites.any { it.fromServerId == serverId && it.status == "pending" }
        } else {
            jointPeerStatus == "pending"
        }
    return JointChannelConnections(
        channelId = ChannelId(channelId),
        channelName = name,
        isJoint = type == "joint",
        archived = !archivedAt.isNullOrBlank(),
        servers = servers,
        hasCurrentServerPendingInvites = pendingFromCurrent,
        jointRole = JointChannelRole.fromWire(jointRole),
        federateChannels = channelCapabilities?.federateChannels,
        isPrivate = type == "private",
        joined = joined,
    )
}

private fun WireJointServer.toDomain(): JointChannelServer? {
    val id = serverId.trim().takeIf(String::isNotBlank) ?: return null
    return JointChannelServer(
        serverId = id,
        name = serverName.takeIf(String::isNotBlank) ?: serverSlug,
        slug = serverSlug,
        active = status == "active",
        isCurrentServer = isCurrentServer,
        plan =
            when (plan) {
                "free" -> JointChannelServerPlan.Free
                "paid" -> JointChannelServerPlan.Paid
                else -> null
            },
        role = JointChannelRole.fromWire(role),
        avatarUrl = serverAvatarUrl?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun parseSharedPendingJointInvites(bodyText: String): List<PendingJointInvite>? = runCatching {
    jointChannelsJson.decodeFromString(WirePendingJointInvitesResponse.serializer(), bodyText)
        .invites
        .filter { it.id.isNotBlank() }
        .map { wire ->
            PendingJointInvite(
                id = wire.id,
                fromServerName = wire.fromServerName.ifBlank { wire.fromServerSlug },
                fromServerSlug = wire.fromServerSlug,
                channelName = wire.channelName,
                expiresAt = wire.expiresAt,
            )
        }
}.getOrNull()

internal fun parseSharedJointDisconnectOk(bodyText: String): Boolean = runCatching {
    jointChannelsJson.decodeFromString(WireJointOkResponse.serializer(), bodyText).ok
}.getOrDefault(false)

internal fun parseSharedJointAcceptedChannel(bodyText: String): JointChannelAccepted? = runCatching {
    val wire = jointChannelsJson.decodeFromString(WireJointChannel.serializer(), bodyText)
    val id = wire.id.trim().takeIf(String::isNotBlank) ?: return@runCatching null
    JointChannelAccepted(channelId = ChannelId(id), channelName = wire.name)
}.getOrNull()

/**
 * Repository over the joint-channel routes. Every call goes through the caller's
 * [RaftNetworkClient] with an explicit priority; failures keep the server's `code` (as the
 * [ScreenError] / [PermissionBlocker] code) and its `error` text (as `serverMessage`).
 */
internal class RealJointChannelRepository(
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {},
) {
    suspend fun loadConnections(channelId: ChannelId, priority: DataRequestPriority): DataResult<JointChannelConnections> =
        execute("joint_connections", { networkClient.request(priority = priority, path = "channels/${channelId.value}") }) { body ->
            parseSharedJointChannelConnections(body)
        }

    suspend fun disconnect(channelId: ChannelId): DataResult<Unit> =
        execute(
            "joint_disconnect",
            {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels/${channelId.value}/disconnect",
                    method = "POST",
                )
            },
        ) { body -> Unit.takeIf { parseSharedJointDisconnectOk(body) } }

    /** The host's admin takes [serverId] out of the joint (`POST channels/:id/joint-servers/:serverId/disconnect`). */
    suspend fun disconnectServer(channelId: ChannelId, serverId: String): DataResult<Unit> =
        execute(
            "joint_server_disconnect",
            {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels/${channelId.value}/joint-servers/${serverId.trim()}/disconnect",
                    method = "POST",
                )
            },
        ) { body -> Unit.takeIf { parseSharedJointDisconnectOk(body) } }

    /** A member connects servers they own or administer (`POST channels/:id/connect-my-servers`). */
    suspend fun connectMyServers(channelId: ChannelId, serverIds: List<String>): DataResult<MemberConnectResponse> =
        networkClient.connectMyServers(channelId, serverIds, warningLogger)

    suspend fun pendingInvites(serverId: ServerId, priority: DataRequestPriority): DataResult<List<PendingJointInvite>> =
        execute(
            "joint_pending_invites",
            { networkClient.request(priority = priority, path = "channels/joint-invites", serverIdOverride = serverId) },
        ) { body -> parseSharedPendingJointInvites(body) }

    suspend fun acceptInvite(serverId: ServerId, inviteId: String): DataResult<JointChannelAccepted> =
        execute(
            "joint_accept",
            {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels/joint-invites/${inviteId.trim()}/accept",
                    method = "POST",
                    serverIdOverride = serverId,
                )
            },
        ) { body -> parseSharedJointAcceptedChannel(body) }

    private suspend fun <T> execute(
        source: String,
        block: suspend () -> HomeNetworkResponse,
        mapper: (String) -> T?,
    ): DataResult<T> {
        var status: Int? = null
        return try {
            val response = block()
            status = response.code
            if (!response.isSuccessful) return response.toJointDataError()
            val parsed = mapper(response.bodyText.orEmpty())
            if (parsed == null) {
                warningLogger("parse_failed source=$source status=${response.code}")
                DataResult.Error(ScreenError(code = "joint_response_parse_failed", retryable = true))
            } else {
                DataResult.Content(parsed)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger("request_failed source=$source status=${status ?: "unknown"} exception=${exception::class.simpleName}")
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
    }
}

/**
 * Maps a failed joint response. The server's `code` (a limit code) becomes the result code so the
 * UI can pick the web's localized copy; otherwise the HTTP status is kept as `joint_http_<status>`
 * and the server's `error` text is carried for the existing error pipeline.
 */
internal fun <T> HomeNetworkResponse.toJointDataError(): DataResult<T> {
    val body =
        (bodyText ?: errorBodyText)
            ?.takeIf { it.isNotBlank() }
            ?.let { payload -> runCatching { jointChannelsJson.decodeFromString(WireJointErrorBody.serializer(), payload) }.getOrNull() }
    val serverMessage = body?.error?.trim()?.takeIf(String::isNotBlank)
    val serverCode = body?.code?.trim()?.takeIf(String::isNotBlank)
    return when (code) {
        401 -> authSessionRequiredResult(serverMessage)
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = serverCode ?: "joint_forbidden",
                    requiredCapability = "federateChannels",
                    serverMessage = serverMessage,
                    traceId = traceId,
                )
            )
        in 500..599 ->
            DataResult.Error(
                ScreenError(
                    code = "server_unavailable",
                    retryable = true,
                    serverMessage = serverMessage,
                    traceId = traceId,
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = serverCode ?: "joint_http_$code",
                    retryable = false,
                    serverMessage = serverMessage,
                    traceId = traceId,
                )
            )
    }
}

/** `Retry-After` as delta-seconds (the form Raft's routes send); an HTTP-date reads as null. */
internal fun HomeNetworkResponse.retryAfterHeaderSeconds(): Long? =
    responseHeaders.entries
        .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }

/**
 * Web `formatJointInviteRateLimited`: the wait in whole minutes, rounded up and at least 1; null
 * when the response named no wait (the copy then says "later"). The action card's feedback rate
 * limit words its wait the same way.
 */
internal fun jointInviteRateLimitMinutes(retryAfterSeconds: Long?): Int? =
    retryAfterSeconds?.let { seconds -> ((seconds + 59) / 60).coerceAtLeast(1).toInt() }

/** The server/limit code of a failed result, or null (network, parse, auth). */
internal fun DataResult<*>.jointFailureCode(): String? =
    when (this) {
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
        else -> null
    }
