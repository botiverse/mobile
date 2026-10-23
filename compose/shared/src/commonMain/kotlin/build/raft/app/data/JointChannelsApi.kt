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
 *  - `POST channels/:id/joint-invites` body `{ targetServerSlug, invitedPeople: string[] }`
 *    → the channel with joint metadata plus `jointInvites` / `jointInvite`. 400 carries
 *    `{ error, code: JointChannelInviteValidationCode, targetServerSlug?, invitee? }`; 403 is
 *    either the `federateChannels` gate (`{ error }`) or a limit
 *    (`{ error, code: joint_free_server_limit | joint_server_limit | joint_channel_free_limit_reached }`).
 *  - `POST channels/:id/joint-invite/resend` → `{ ok: true, resentCount }`; 409 `No pending …`.
 *  - `POST channels/:id/disconnect` → `{ ok: true }`.
 *  - `GET channels/joint-invites` → `{ invites: [{ id, jointChannelId, fromServerId,
 *    fromServerName, fromServerSlug, channelName, channelDescription, invitedByUserId,
 *    invitedUserId, expiresAt, createdAt }] }` (`channelService.listPendingJointChannelInvites`).
 *  - `POST channels/joint-invites/:inviteId/accept` → the accepted channel (with joint metadata);
 *    403/404/409 `{ error }` or a limit `{ error, code }`.
 *
 * Web reference: `packages/web/src/components/channel/EditChannelDialog.tsx` (connected servers,
 * resend, invite form, disconnect) and `components/layout/usePendingJointInvites.ts` +
 * `useSystemNotifications.tsx` (pending invite → Accept).
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */
internal const val JOINT_CHANNEL_MAX_SERVERS = 30
internal const val JOINT_CHANNEL_MAX_FREE_SERVERS = 2

/** `JointChannelLimitErrorCode` (`packages/server/src/services/jointChannelLimitService.ts`). */
internal const val JOINT_FREE_SERVER_LIMIT = "joint_free_server_limit"
internal const val JOINT_SERVER_LIMIT = "joint_server_limit"
internal const val JOINT_CHANNEL_FREE_LIMIT_REACHED = "joint_channel_free_limit_reached"

/** `JointChannelInviteValidationCode` (`packages/server/src/services/channelService.ts`). */
internal const val JOINT_TARGET_SERVER_INVALID = "joint_target_server_invalid"
internal const val JOINT_INVITEE_NOT_FOUND = "joint_invitee_not_found"
internal const val JOINT_INVITEE_NOT_ADMIN = "joint_invitee_not_admin"
internal const val JOINT_INVITE_REQUIRED = "joint_invite_required"
internal const val JOINT_INVITE_LIMIT_EXCEEDED = "joint_invite_limit_exceeded"

/**
 * 429 from `POST channels` with joint invites and `POST channels/:id/joint-invites`: the inviter
 * spent their hourly budget of invited people. Body `{ error, code, retryAfterSeconds }` plus a
 * `Retry-After` header (botiverse/slock `agent/joint-invite-rate-limit` 4ab05c6e3,
 * `routes/channels.ts`). Pending server PR: verify against the merged route before release.
 */
internal const val JOINT_INVITE_RATE_LIMITED = "joint_invite_rate_limited"

internal val JOINT_LIMIT_ERROR_CODES =
    setOf(JOINT_FREE_SERVER_LIMIT, JOINT_SERVER_LIMIT, JOINT_CHANNEL_FREE_LIMIT_REACHED)

private val jointChannelsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class WireJointServer(
    val serverId: String,
    val serverName: String = "",
    val serverSlug: String = "",
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
    val jointPeerServerId: String? = null,
    val jointPeerServerName: String? = null,
    val jointPeerServerSlug: String? = null,
    val jointPeerStatus: String? = null,
    val jointServers: List<WireJointServer>? = null,
    val jointPendingInvites: List<WireJointPendingInvite>? = null,
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
private data class WireJointResendResponse(
    val ok: Boolean = false,
    val resentCount: Int,
)

@Serializable
private data class WireJointOkResponse(
    val ok: Boolean,
)

@Serializable
private data class WireJointErrorBody(
    val error: String? = null,
    val code: String? = null,
    val targetServerSlug: String? = null,
    val invitee: String? = null,
    val archivedChannelId: String? = null,
    val archivedChannelName: String? = null,
    val archivedChannelType: String? = null,
    val canUnarchiveArchivedChannel: Boolean = false,
    val blockingChannel: WireJointBlockingChannel? = null,
    /** `joint_invite_rate_limited`: seconds until the inviter's budget frees up. */
    val retryAfterSeconds: Long? = null,
)

@Serializable
private data class WireJointBlockingChannel(val name: String? = null)

/**
 * `POST channels` with `visibility: "joint"` (`routes/channels.ts` `channelRouter.post("/")`): name,
 * optional description, initial current-server members and one `jointInvites` entry per server.
 */
@Serializable
internal data class CreateJointChannelRequestBody(
    val name: String,
    val description: String? = null,
    val visibility: String = "joint",
    val agentIds: List<String> = emptyList(),
    val userIds: List<String> = emptyList(),
    val jointInvites: List<JointChannelInviteRequestBody>,
)

/** The server's structured details of a rejected joint request (invite target, archived collision). */
data class JointChannelErrorDetails(
    val code: String?,
    val targetServerSlug: String?,
    val invitee: String?,
    val archivedChannelId: String?,
    val archivedChannelName: String?,
    val archivedChannelType: String?,
    val canUnarchiveArchivedChannel: Boolean,
    /** `joint_channel_free_limit_reached`: the hosted joint that already uses the free slot. */
    val blockingChannelName: String? = null,
)

/** `archived_name_collision`: 409 from `POST channels` when an archived channel holds the name. */
internal const val ARCHIVED_NAME_COLLISION = "archived_name_collision"

internal fun parseSharedJointErrorDetails(bodyText: String?): JointChannelErrorDetails? =
    bodyText?.takeIf(String::isNotBlank)?.let { payload ->
        runCatching { jointChannelsJson.decodeFromString(WireJointErrorBody.serializer(), payload) }.getOrNull()
    }?.let { wire ->
        JointChannelErrorDetails(
            code = wire.code?.trim()?.takeIf(String::isNotBlank),
            targetServerSlug = wire.targetServerSlug?.trim()?.takeIf(String::isNotBlank),
            invitee = wire.invitee?.trim()?.takeIf(String::isNotBlank),
            archivedChannelId = wire.archivedChannelId?.trim()?.takeIf(String::isNotBlank),
            archivedChannelName = wire.archivedChannelName?.trim()?.takeIf(String::isNotBlank),
            archivedChannelType = wire.archivedChannelType,
            canUnarchiveArchivedChannel = wire.canUnarchiveArchivedChannel,
            blockingChannelName = wire.blockingChannel?.name?.trim()?.takeIf(String::isNotBlank),
        )
    }

/** One invite draft as sent: slug + invitee list. */
data class JointInviteTarget(val targetServerSlug: String, val invitedPeople: List<String>)

sealed interface CreateJointChannelOutcome {
    data class Created(val channel: JointChannelAccepted) : CreateJointChannelOutcome

    /** Web: an archived joint channel holds the name; offer Unarchive (when allowed) or Change Name. */
    data class ArchivedNameCollision(
        val archivedChannelId: ChannelId,
        val archivedChannelName: String,
        val canUnarchive: Boolean,
    ) : CreateJointChannelOutcome

    data class Failed(val result: DataResult<*>, val details: JointChannelErrorDetails?) : CreateJointChannelOutcome
}

internal fun buildSharedCreateJointChannelBody(
    name: String,
    description: String?,
    agentIds: List<String>,
    userIds: List<String>,
    invites: List<JointInviteTarget>,
): String =
    jointChannelsJson.encodeToString(
        CreateJointChannelRequestBody.serializer(),
        CreateJointChannelRequestBody(
            name = name,
            description = description,
            agentIds = agentIds,
            userIds = userIds,
            jointInvites = invites.map { JointChannelInviteRequestBody(it.targetServerSlug, it.invitedPeople) },
        ),
    )

/** `POST channels/:id/joint-invites` request body (no `sameAdmin`: the direct same-admin connect is web-only). */
@Serializable
internal data class JointChannelInviteRequestBody(
    val targetServerSlug: String,
    val invitedPeople: List<String>,
)

enum class JointChannelServerPlan { Free, Paid }

data class JointChannelServer(
    val serverId: String,
    val name: String,
    val slug: String,
    val active: Boolean,
    val isCurrentServer: Boolean,
    /** Null when the server omits it (older servers) — the web then shows no plan badge. */
    val plan: JointChannelServerPlan?,
)

data class JointChannelConnections(
    val channelId: ChannelId,
    val channelName: String,
    val isJoint: Boolean,
    val archived: Boolean,
    /** Active servers first, then pending targets, exactly as the server orders `jointServers`. */
    val servers: List<JointChannelServer>,
    /** Web `hasCurrentServerPendingJointInvites`: this server has invites it may resend. */
    val hasCurrentServerPendingInvites: Boolean,
) {
    /** Web `jointServerLimitReached`: active + pending servers count toward the 30-server cap. */
    val serverLimitReached: Boolean
        get() = servers.size >= JOINT_CHANNEL_MAX_SERVERS
}

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

internal fun parseSharedJointResendCount(bodyText: String): Int? = runCatching {
    jointChannelsJson.decodeFromString(WireJointResendResponse.serializer(), bodyText).resentCount
}.getOrNull()

internal fun parseSharedJointDisconnectOk(bodyText: String): Boolean = runCatching {
    jointChannelsJson.decodeFromString(WireJointOkResponse.serializer(), bodyText).ok
}.getOrDefault(false)

internal fun parseSharedJointAcceptedChannel(bodyText: String): JointChannelAccepted? = runCatching {
    val wire = jointChannelsJson.decodeFromString(WireJointChannel.serializer(), bodyText)
    val id = wire.id.trim().takeIf(String::isNotBlank) ?: return@runCatching null
    JointChannelAccepted(channelId = ChannelId(id), channelName = wire.name)
}.getOrNull()

internal fun buildSharedJointChannelInviteBody(targetServerSlug: String, invitedPeople: List<String>): String =
    jointChannelsJson.encodeToString(
        JointChannelInviteRequestBody.serializer(),
        JointChannelInviteRequestBody(targetServerSlug = targetServerSlug, invitedPeople = invitedPeople),
    )

/** Web `parseInvitedPeopleText` (`packages/web/src/utils/jointSameAdmin.ts`): commas or new lines. */
internal fun parseJointInvitedPeopleText(text: String): List<String> =
    text.split(',', '\n').map(String::trim).filter(String::isNotEmpty)

internal enum class JointServerSlugIssue { Required, Pattern }

private val JOINT_SERVER_SLUG_PATTERN = Regex("^[a-z][a-z0-9-]*$")

/** Shared `validateServerSlugReferenceReason` (`packages/shared/src/serverSlugValidation.ts`). */
internal fun jointServerSlugIssue(slug: String): JointServerSlugIssue? =
    when {
        slug.isEmpty() -> JointServerSlugIssue.Required
        !JOINT_SERVER_SLUG_PATTERN.matches(slug) -> JointServerSlugIssue.Pattern
        else -> null
    }

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

    suspend fun inviteServer(
        channelId: ChannelId,
        targetServerSlug: String,
        invitedPeople: List<String>,
    ): DataResult<JointChannelConnections> =
        execute(
            "joint_invite",
            {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels/${channelId.value}/joint-invites",
                    method = "POST",
                    bodyText = buildSharedJointChannelInviteBody(targetServerSlug, invitedPeople),
                )
            },
        ) { body -> parseSharedJointChannelConnections(body) }

    suspend fun resendInvites(channelId: ChannelId): DataResult<Int> =
        execute(
            "joint_resend",
            {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels/${channelId.value}/joint-invite/resend",
                    method = "POST",
                )
            },
        ) { body -> parseSharedJointResendCount(body) }

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

    suspend fun createJointChannel(
        name: String,
        description: String?,
        agentIds: List<String>,
        userIds: List<String>,
        invites: List<JointInviteTarget>,
    ): CreateJointChannelOutcome {
        val response =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels",
                    method = "POST",
                    bodyText = buildSharedCreateJointChannelBody(name, description, agentIds, userIds, invites),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                warningLogger("request_failed source=joint_create exception=${exception::class.simpleName}")
                return CreateJointChannelOutcome.Failed(
                    DataResult.Error(ScreenError(code = "network_unavailable", retryable = true)),
                    details = null,
                )
            }
        if (response.isSuccessful) {
            val created = parseSharedJointAcceptedChannel(response.bodyText.orEmpty())
            return if (created != null) {
                CreateJointChannelOutcome.Created(created)
            } else {
                warningLogger("parse_failed source=joint_create status=${response.code}")
                CreateJointChannelOutcome.Failed(
                    DataResult.Error(ScreenError(code = "joint_response_parse_failed", retryable = true)),
                    details = null,
                )
            }
        }
        val details = parseSharedJointErrorDetails(response.bodyText ?: response.errorBodyText)
        val archivedId = details?.archivedChannelId
        val archivedName = details?.archivedChannelName
        // Web offers the collision recovery only when the archived holder is itself a joint channel.
        if (
            response.code == 409 && details?.code == ARCHIVED_NAME_COLLISION &&
            archivedId != null && archivedName != null && details.archivedChannelType == "joint"
        ) {
            return CreateJointChannelOutcome.ArchivedNameCollision(
                archivedChannelId = ChannelId(archivedId),
                archivedChannelName = archivedName,
                canUnarchive = details.canUnarchiveArchivedChannel,
            )
        }
        return CreateJointChannelOutcome.Failed(response.toJointDataError<Unit>(), details)
    }

    suspend fun unarchiveChannel(channelId: ChannelId): DataResult<JointChannelAccepted> =
        execute(
            "joint_unarchive",
            {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "channels/${channelId.value}/unarchive",
                    method = "POST",
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
 * Maps a failed joint response. The server's `code` (limit or invite-validation code) becomes the
 * result code so the UI can pick the web's localized copy; otherwise the HTTP status is kept as
 * `joint_http_<status>` and the server's `error` text is carried for the existing error pipeline.
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
                )
            )
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true, serverMessage = serverMessage))
        else ->
            DataResult.Error(
                ScreenError(
                    code = serverCode ?: "joint_http_$code",
                    retryable = false,
                    serverMessage = serverMessage,
                    retryAfterSeconds = if (code == 429) body?.retryAfterSeconds ?: retryAfterHeaderSeconds() else null,
                )
            )
    }
}

/** `Retry-After` as delta-seconds (the form the joint routes send); an HTTP-date reads as null. */
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
 * when the response named no wait (the copy then says "later").
 */
internal fun jointInviteRateLimitMinutes(retryAfterSeconds: Long?): Int? =
    retryAfterSeconds?.let { seconds -> ((seconds + 59) / 60).coerceAtLeast(1).toInt() }

/** The rate-limit wait carried by a failed joint result. */
internal fun DataResult<*>.jointRetryAfterSeconds(): Long? = (this as? DataResult.Error)?.error?.retryAfterSeconds

/** The server/limit code of a failed result, or null (network, parse, auth). */
internal fun DataResult<*>.jointFailureCode(): String? =
    when (this) {
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
        else -> null
    }
