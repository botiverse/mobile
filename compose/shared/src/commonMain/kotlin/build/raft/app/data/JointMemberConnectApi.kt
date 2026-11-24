package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A channel member connects servers they own or administer to the channel, no host approval
 * (#wg-rbac:#36/#37, Connected servers ⋯ > Connect your servers).
 *
 * Server contract, botiverse/slock `staging` @ 44ce160f14951fcd7555c880f5066155bf756e91:
 *  - `POST channels/:id/connect-my-servers` (`packages/server/src/routes/channels.ts`), `:id` the
 *    caller's own server's copy; body `{ serverIds: string[] }` (1 to 29 uuids,
 *    `externalInviteConnectService.parseServerSelections`).
 *  - 200 `{ servers: ServerConnectOutcome[] }`, one per chosen server in the order chosen
 *    (`routes/externalInvites.ts` `serializeServerConnectOutcome`):
 *    `{ serverId, state: "connected", channelId }`, `{ serverId, state: "connecting" }` (a private
 *    channel is converted first), `{ serverId, state: "awaiting_approval", requestId }` (never on
 *    this route: it is `directOnly`), or `{ serverId, state: "refused", status, error, code?,
 *    limitSide?, limitServerId? }`, the refusal that server would have met alone
 *    (`CONNECT_ERROR_STATUS`: `server_admin_required`, `server_membership_required`,
 *    `already_connected`, `membership_changed`, `channel_archived`, `invalid_server`, ...; limits
 *    `free_external_limit` / `joint_free_server_limit` with `limitSide` / `limitServerId`;
 *    `joint_server_limit`, `channel_conversion_*`), `internal_error` (500) for anything else.
 *  - The whole request refused: 404 `{ code: "channel_not_found", error }` (not in the channel,
 *    unknown channel — `routes/channels.ts`), 400 `{ code: "channel_not_invitable" }` (a public
 *    channel, #all), 400 `{ code: "invalid_input" }` (the list), 409 `{ code: "guest_has_computer" }`,
 *    409 `{ code }` (`ExternalInviteError`), 500.
 *
 * Web: `packages/web/src/components/channel/memberConnect.ts` (`connectMyServers`) and
 * `components/externalInvite/externalInviteAccept.ts` (`ServerConnectOutcome`). Field names are
 * exact camelCase.
 */
private val memberConnectJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class MemberConnectRequestBody(val serverIds: List<String>)

internal fun buildSharedMemberConnectBody(serverIds: List<String>): String =
    memberConnectJson.encodeToString(MemberConnectRequestBody.serializer(), MemberConnectRequestBody(serverIds))

@Serializable
private data class WireServerConnectOutcome(
    val serverId: String,
    val state: String,
    val channelId: String? = null,
    val requestId: String? = null,
    val status: Int? = null,
    val error: String? = null,
    val code: String? = null,
    val limitSide: String? = null,
    val limitServerId: String? = null,
)

@Serializable
private data class WireMemberConnectResponse(val servers: List<WireServerConnectOutcome>)

@Serializable
private data class WireMemberConnectErrorBody(
    val error: String? = null,
    val code: String? = null,
    val limitSide: String? = null,
    val limitServerId: String? = null,
    val serverId: String? = null,
)

@Serializable
internal data class MemberConnectNewServerRequestBody(val name: String, val slug: String)

internal fun buildSharedMemberConnectNewServerBody(name: String, slug: String): String =
    memberConnectJson.encodeToString(MemberConnectNewServerRequestBody.serializer(), MemberConnectNewServerRequestBody(name, slug))

@Serializable
private data class WireMemberConnectNewServerResponse(
    val serverId: String = "",
    val servers: List<WireServerConnectOutcome> = emptyList(),
)

@Serializable
private data class WireMemberConnectOwnerRef(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
)

@Serializable
private data class WireMemberConnectableServer(
    val id: String = "",
    val atLimit: Boolean = false,
    val canUpgrade: Boolean = false,
    val owners: List<WireMemberConnectOwnerRef> = emptyList(),
)

@Serializable
private data class WireMemberAskServer(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val avatarUrl: String? = null,
    val approvers: List<WireMemberConnectOwnerRef> = emptyList(),
)

@Serializable
private data class WireMemberConnectableServersResponse(
    val servers: List<WireMemberConnectableServer>,
    val askServers: List<WireMemberAskServer> = emptyList(),
)

/** Billing owner or approver returned by `GET channels/:id/connect-my-servers`. */
data class MemberConnectOwnerRef(
    val id: String,
    val name: String = "",
    val avatarUrl: String? = null,
)

/** Per-server Free external-channel limit status from `GET channels/:id/connect-my-servers`. */
data class MemberConnectServerLimit(
    val serverId: String,
    val atLimit: Boolean,
    val canUpgrade: Boolean,
    val owners: List<MemberConnectOwnerRef> = emptyList(),
)

/**
 * A server the member only belongs to (cannot connect directly); its owners or admins can
 * (`GET channels/:id/connect-my-servers` `askServers`, slock #16361).
 */
data class MemberAskServer(
    val id: String,
    val name: String,
    val slug: String = "",
    val avatarUrl: String? = null,
    val approvers: List<MemberConnectOwnerRef> = emptyList(),
)

/** Both lists returned by `GET channels/:id/connect-my-servers` (slock #16361). */
data class MemberConnectOptions(
    val limits: Map<String, MemberConnectServerLimit> = emptyMap(),
    val askServers: List<MemberAskServer> = emptyList(),
)

private fun List<WireMemberConnectOwnerRef>.toDomainOwnerRefs(): List<MemberConnectOwnerRef> =
    mapNotNull { ref ->
        val refId = ref.id.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        MemberConnectOwnerRef(
            id = refId,
            name = ref.name.trim(),
            avatarUrl = ref.avatarUrl?.trim()?.takeIf(String::isNotBlank),
        )
    }

/**
 * Parses `GET channels/:id/connect-my-servers` (`{ servers: [...], askServers?: [...] }`,
 * slock #16361), returning null on malformed payloads and dropping entries with blank server IDs.
 */
internal fun parseSharedMemberConnectOptions(bodyText: String): MemberConnectOptions? =
    runCatching {
        val parsed = memberConnectJson.decodeFromString(WireMemberConnectableServersResponse.serializer(), bodyText)
        val limits =
            buildMap {
                parsed.servers.forEach { row ->
                    val id = row.id.trim().takeIf(String::isNotBlank) ?: return@forEach
                    put(
                        id,
                        MemberConnectServerLimit(
                            serverId = id,
                            atLimit = row.atLimit,
                            canUpgrade = row.canUpgrade,
                            owners = row.owners.toDomainOwnerRefs(),
                        ),
                    )
                }
            }
        val askServers =
            parsed.askServers.mapNotNull { row ->
                val id = row.id.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                MemberAskServer(
                    id = id,
                    name = row.name.trim(),
                    slug = row.slug.trim(),
                    avatarUrl = row.avatarUrl?.trim()?.takeIf(String::isNotBlank),
                    approvers = row.approvers.toDomainOwnerRefs(),
                )
            }
        MemberConnectOptions(limits = limits, askServers = askServers)
    }.getOrNull()

/**
 * Parses `GET channels/:id/connect-my-servers` (`{ servers: [{ id, atLimit, canUpgrade, owners }] }`),
 * returning null on malformed payloads and dropping entries with blank server IDs.
 */
internal fun parseSharedMemberConnectLimits(bodyText: String): Map<String, MemberConnectServerLimit>? =
    parseSharedMemberConnectOptions(bodyText)?.limits

/**
 * `GET channels/:id/connect-my-servers`: per-server Free external-channel limits (`servers`) and
 * member-only servers whose owners/admins can connect (`askServers`, slock #16361). Older servers
 * may return 404 or omit `askServers`; any non-2xx or parse/transport error silently degrades to
 * empty options.
 */
internal suspend fun RaftNetworkClient.listMemberConnectOptions(
    channelId: ChannelId,
    warningLogger: (String) -> Unit = {},
): MemberConnectOptions {
    val response =
        try {
            request(
                priority = DataRequestPriority.Visible,
                path = "channels/${channelId.value}/connect-my-servers",
                method = "GET",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger("request_failed source=member_connect_limits exception=${exception::class.simpleName}")
            return MemberConnectOptions()
        }
    if (!response.isSuccessful) return MemberConnectOptions()
    return parseSharedMemberConnectOptions(response.bodyText.orEmpty()).also { parsed ->
        if (parsed == null) warningLogger("parse_failed source=member_connect_limits status=${response.code}")
    } ?: MemberConnectOptions()
}

/**
 * Web `useMemberConnectLimits`: `GET channels/:id/connect-my-servers` to discover which eligible
 * servers are already at Free's 1-external-channel limit.
 */
internal suspend fun RaftNetworkClient.listMemberConnectLimits(
    channelId: ChannelId,
    warningLogger: (String) -> Unit = {},
): Map<String, MemberConnectServerLimit> = listMemberConnectOptions(channelId, warningLogger).limits

/** One chosen server's answer (web `ServerConnectOutcome`). */
sealed interface ServerConnectOutcome {
    val serverId: String

    data class Connected(override val serverId: String, val channelId: String) : ServerConnectOutcome

    /** A private channel is converted first; the server connects after it. */
    data class Connecting(override val serverId: String) : ServerConnectOutcome

    /** Never sent by connect-my-servers (`directOnly`); kept so the answer reads as sent. */
    data class AwaitingApproval(override val serverId: String, val requestId: String) : ServerConnectOutcome

    data class Refused(
        override val serverId: String,
        val status: Int,
        val code: String?,
        val error: String?,
        /** `"host"` or `"connecting"`: whose limit a limit refusal is. */
        val limitSide: String? = null,
        val limitServerId: String? = null,
    ) : ServerConnectOutcome
}

/** What `connect-my-servers` answered. */
sealed interface MemberConnectResponse {
    /** 200: each server on its own; [traceId] is that response's, for a refusal shown from it. */
    data class Answered(val servers: List<ServerConnectOutcome>, val traceId: String?) : MemberConnectResponse

    /** The whole request refused (non-2xx other than 401): its status, the server's code and trace ID. */
    data class Refused(val status: Int, val code: String?, val error: String?, val traceId: String?) : MemberConnectResponse
}

/** Null for a body that is not the `{ servers: [...] }` answer. An unknown state reads as a refusal. */
internal fun parseSharedMemberConnectOutcomes(bodyText: String): List<ServerConnectOutcome>? = runCatching {
    memberConnectJson.decodeFromString(WireMemberConnectResponse.serializer(), bodyText).servers.mapNotNull { it.toDomain() }
}.getOrNull()

private fun WireServerConnectOutcome.toDomain(): ServerConnectOutcome? {
    val id = serverId.trim().takeIf(String::isNotBlank) ?: return null
    return when (state) {
        "connected" -> ServerConnectOutcome.Connected(id, channelId.orEmpty())
        "connecting" -> ServerConnectOutcome.Connecting(id)
        "awaiting_approval" -> ServerConnectOutcome.AwaitingApproval(id, requestId.orEmpty())
        else ->
            ServerConnectOutcome.Refused(
                serverId = id,
                status = status ?: 0,
                code = code?.trim()?.takeIf(String::isNotBlank),
                error = error,
                limitSide = limitSide,
                limitServerId = limitServerId?.takeIf(String::isNotBlank),
            )
    }
}

/** A refused request's body (`{ error, code? }`); null when it is not one. */
internal fun parseSharedMemberConnectError(bodyText: String?): Pair<String?, String?>? =
    bodyText?.takeIf(String::isNotBlank)?.let { body ->
        runCatching { memberConnectJson.decodeFromString(WireMemberConnectErrorBody.serializer(), body) }
            .getOrNull()
            ?.let { it.code?.trim()?.takeIf(String::isNotBlank) to it.error?.trim()?.takeIf(String::isNotBlank) }
    }

/**
 * `POST channels/:id/connect-my-servers` through the caller's [RaftNetworkClient] (Foreground: a
 * user's commit). Network failures and 401 stay the app's usual [DataResult] errors; every other
 * answer is a [MemberConnectResponse].
 */
internal suspend fun RaftNetworkClient.connectMyServers(
    channelId: ChannelId,
    serverIds: List<String>,
    warningLogger: (String) -> Unit = {},
): DataResult<MemberConnectResponse> {
    val response =
        try {
            request(
                priority = DataRequestPriority.Foreground,
                path = "channels/${channelId.value}/connect-my-servers",
                method = "POST",
                bodyText = buildSharedMemberConnectBody(serverIds),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger("request_failed source=member_connect exception=${exception::class.simpleName}")
            return DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
    if (response.isSuccessful) {
        val outcomes = parseSharedMemberConnectOutcomes(response.bodyText.orEmpty())
        if (outcomes == null) {
            warningLogger("parse_failed source=member_connect status=${response.code}")
            return DataResult.Content(MemberConnectResponse.Refused(response.code, code = null, error = null, traceId = response.traceId))
        }
        return DataResult.Content(MemberConnectResponse.Answered(outcomes, response.traceId))
    }
    val body = parseSharedMemberConnectError(response.bodyText ?: response.errorBodyText)
    if (response.code == 401) return authSessionRequiredResult(body?.second)
    return DataResult.Content(MemberConnectResponse.Refused(response.code, body?.first, body?.second, response.traceId))
}

/**
 * What `POST channels/:id/connect-new-server` answered (botiverse/slock #16353,
 * `routes/channels.ts` `channelRouter.post("/:id/connect-new-server")`).
 *
 * Once the new server exists, every refusal (including 500) carries [Refused.serverId] so the
 * caller knows they keep the created server.
 */
sealed interface MemberConnectNewServerResponse {
    /** 200 `{ serverId, servers: ServerConnectOutcome[] }`. */
    data class Answered(
        val serverId: String,
        val servers: List<ServerConnectOutcome>,
        val traceId: String?,
    ) : MemberConnectNewServerResponse

    /** Non-2xx (other than 401): status, code, error, limit fields, and optional [serverId] when kept. */
    data class Refused(
        val status: Int,
        val code: String?,
        val error: String?,
        val limitSide: String? = null,
        val limitServerId: String? = null,
        val serverId: String? = null,
        val traceId: String?,
    ) : MemberConnectNewServerResponse
}

/** Parses 200 `{ serverId, servers }` from `POST channels/:id/connect-new-server`; null if malformed. */
internal fun parseSharedMemberConnectNewServerResponse(bodyText: String): Pair<String, List<ServerConnectOutcome>>? =
    runCatching {
        val parsed = memberConnectJson.decodeFromString(WireMemberConnectNewServerResponse.serializer(), bodyText)
        val id = parsed.serverId.trim().takeIf(String::isNotBlank) ?: return@runCatching null
        id to parsed.servers.mapNotNull { it.toDomain() }
    }.getOrNull()

/** Parses a refused `POST channels/:id/connect-new-server` error body, preserving `serverId` when kept. */
internal fun parseSharedMemberConnectNewServerError(
    status: Int,
    bodyText: String?,
    traceId: String?,
): MemberConnectNewServerResponse.Refused {
    val parsed =
        bodyText?.takeIf(String::isNotBlank)?.let { body ->
            runCatching { memberConnectJson.decodeFromString(WireMemberConnectErrorBody.serializer(), body) }.getOrNull()
        }
    fun String?.clean() = this?.trim()?.takeIf(String::isNotBlank)
    return MemberConnectNewServerResponse.Refused(
        status = status,
        code = parsed?.code.clean(),
        error = parsed?.error.clean(),
        limitSide = parsed?.limitSide.clean(),
        limitServerId = parsed?.limitServerId.clean(),
        serverId = parsed?.serverId.clean(),
        traceId = traceId,
    )
}

/**
 * `POST channels/:id/connect-new-server` (slock #16353): creates a new server owned by the caller
 * and connects it to the channel without approval. Network failures and 401 stay [DataResult]
 * errors; every other answer is a [MemberConnectNewServerResponse].
 */
internal suspend fun RaftNetworkClient.connectNewServer(
    channelId: ChannelId,
    name: String,
    slug: String,
    warningLogger: (String) -> Unit = {},
): DataResult<MemberConnectNewServerResponse> {
    val response =
        try {
            request(
                priority = DataRequestPriority.Foreground,
                path = "channels/${channelId.value}/connect-new-server",
                method = "POST",
                bodyText = buildSharedMemberConnectNewServerBody(name, slug),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger("request_failed source=member_connect_new_server exception=${exception::class.simpleName}")
            return DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
    if (response.isSuccessful) {
        val parsed = parseSharedMemberConnectNewServerResponse(response.bodyText.orEmpty())
        if (parsed == null) {
            warningLogger("parse_failed source=member_connect_new_server status=${response.code}")
            return DataResult.Content(
                MemberConnectNewServerResponse.Refused(
                    status = response.code,
                    code = null,
                    error = null,
                    traceId = response.traceId,
                )
            )
        }
        return DataResult.Content(MemberConnectNewServerResponse.Answered(parsed.first, parsed.second, response.traceId))
    }
    val refused = parseSharedMemberConnectNewServerError(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    if (response.code == 401) return authSessionRequiredResult(refused.error)
    return DataResult.Content(refused)
}
