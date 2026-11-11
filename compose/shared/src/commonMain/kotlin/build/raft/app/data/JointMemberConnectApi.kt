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
 *  - The whole request refused: 404 `{ error }` (external_channels_v1 off for the host, not in
 *    the channel, unknown channel), 400 `{ code: "channel_not_invitable" }` (a public channel, #all),
 *    400 `{ code: "invalid_input" }` (the list), 409 `{ code: "guest_has_computer" }`, 409
 *    `{ code }` (`ExternalInviteError`), 500.
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
private data class WireMemberConnectErrorBody(val error: String? = null, val code: String? = null)

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
