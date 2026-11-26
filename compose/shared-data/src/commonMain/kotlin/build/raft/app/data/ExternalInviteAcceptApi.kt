package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * External channel invites, the invitee's side (#wg-rbac:#16; the page the invite email links to,
 * `/external-invite/:token`).
 *
 * Server contract, botiverse/slock `staging` @ 0e8b4fab7440307f58c64e3448e820f517359194
 * (`packages/server/src/routes/externalInvites.ts`, `services/externalInviteService.ts`):
 *  - `GET external-invites/:token` → `ExternalInviteForInvitee`, or for an account the invite was
 *    not sent to `{ addressedToYou: false, invitedEmailMasked }` only. 404
 *    `{ error: "This invite link is not valid" }` for every invite the caller may not use
 *    (unknown, expired, withdrawn, used).
 *  - `POST external-invites/:token/accept-as-guest` → `{ serverId, channelId }`.
 *  - `POST external-invites/:token/connect-servers` `{ servers: [{ serverId, approverUserId? }] }`
 *    → `{ servers: ServerConnectOutcome[] }` (each decided on its own: connected, connecting,
 *    awaiting_approval, or refused with the body it would have had alone).
 *  - `POST external-invites/:token/connect-new-server` `{ name, slug }` → 200
 *    `{ state: "connected", serverId, channelId }` or 202 `{ state: "connecting", serverId }`; a
 *    refusal after the server was made names it (`serverId`); a name/slug refusal is POST
 *    /api/servers' 400 / 409 `{ error }` without a code.
 *  - `POST external-invites/:token/cancel-request` `{ requestId? }` → `{ status: "cancelled" }`.
 *  - Refusals: `{ error, code?, limitSide?, limitServerId?, serverId? }` (`externalConnectRefusal`:
 *    the accept codes `invalid` 404, `approval_in_progress`, `connect_in_progress`,
 *    `guest_unavailable`, `channel_archived`, `already_member`, `guest_elsewhere` 409, the connect
 *    codes, limits `free_external_limit` / `joint_free_server_limit` with their side), 429 rate limit.
 *
 * Web: `packages/web/src/components/externalInvite/externalInviteAccept.ts`. Field names are exact
 * camelCase.
 */
private val inviteeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

@Serializable
data class InviteApprover(val id: String, val name: String = "", val avatarUrl: String? = null)

/** A server the invitee belongs to that could connect to the channel (`options.connect.servers`). */
@Serializable
data class InviteConnectableServer(
    val id: String,
    val name: String = "",
    val slug: String? = null,
    val avatarUrl: String? = null,
    /** They own or administer it: connecting needs nobody's approval. */
    val canConnectDirectly: Boolean = false,
    /** A member only: an owner or admin of it must approve. */
    val needsApproval: Boolean = false,
    val approvers: List<InviteApprover> = emptyList(),
    /** The Free plan's one external channel keeps it out: listed, not chosen. */
    val atLimit: Boolean = false,
    val canUpgrade: Boolean = false,
    /** At its limit: who can upgrade it (`owners`). Absent from an older server. */
    val owners: List<InviteApprover> = emptyList(),
)

@Serializable
data class InviteConnectOptions(
    val servers: List<InviteConnectableServer> = emptyList(),
    /** How many of the servers the channel can still take; absent from an older server. */
    val slotsLeft: Int? = null,
)

@Serializable
data class InviteOptions(
    val guest: Boolean = false,
    val connect: InviteConnectOptions = InviteConnectOptions(),
    val newServer: Boolean = false,
)

@Serializable
data class PendingConnectRequest(
    val id: String,
    val serverId: String,
    val serverName: String = "",
    val approverUserId: String = "",
    val approverName: String = "",
    val expiresAt: String = "",
)

@Serializable
data class InviteServerProgress(
    val serverId: String,
    val serverName: String = "",
    /** `connected`, `connecting` or `awaiting_approval`. */
    val state: String,
    val requestId: String? = null,
    val approverName: String? = null,
)

@Serializable
data class EndedConnectRequest(val status: String, val serverName: String = "")

@Serializable
data class AcceptedExternalInvite(
    /** `guest`, `server` or `new_server`. */
    val outcome: String = "",
    val serverId: String,
    val channelId: String,
    val channelName: String = "",
)

@Serializable
data class InviteOpenChannel(
    val serverId: String,
    val channelId: String,
    val channelName: String = "",
)

@Serializable
data class InvitePublicChannel(
    val canMakePrivate: Boolean = false,
)

/** The invite page (web `ExternalInviteForInvitee`). */
@Serializable
data class ExternalInviteForInvitee(
    val channelName: String = "",
    val serverName: String = "",
    val inviterName: String = "",
    val expiresAt: String = "",
    /** `pending`, `awaiting_approval`, `connecting` or `accepted`. */
    val status: String = "pending",
    val connectPhase: String? = null,
    val connectServerId: String? = null,
    val pendingRequest: PendingConnectRequest? = null,
    val pendingRequests: List<PendingConnectRequest> = emptyList(),
    /** Absent from an older server (null), or the invitee's servers under way with it. */
    val servers: List<InviteServerProgress>? = null,
    val lastRequest: EndedConnectRequest? = null,
    val accepted: AcceptedExternalInvite? = null,
    /** False: the invite wasn't sent to this account's verified address (an older server leaves it out). */
    val addressedToYou: Boolean = true,
    /** To an account it isn't addressed to: the invited address, masked ("l•••@contoso.com"). */
    val invitedEmailMasked: String? = null,
    val options: InviteOptions? = null,
    val channelFull: Boolean = false,
    /**
     * The invitee is a member of the host server (not a Guest) already in the channel (slock #16635):
     * the page reads "You're in #channel" with [openChannel] alongside connect options. Absent from older servers.
     */
    val alreadyInChannel: Boolean = false,
    /** With [alreadyInChannel]: the channel to open ("Open #channel") on the host server. */
    val openChannel: InviteOpenChannel? = null,
    /**
     * The channel is public (a member's invite leaves it public, slock #16635): connecting a server
     * makes it private, which only an owner or admin of the host in the channel may do ([InvitePublicChannel.canMakePrivate],
     * confirmed with `makePrivate: true`); anyone else is refused `channel_public_admin_required`.
     */
    val publicChannel: InvitePublicChannel? = null,
)

@Serializable
data class GuestAcceptance(val serverId: String, val channelId: String)

/** `POST external-invites/:token/connect`: connected now (200), or the channel is converted first (202). */
@Serializable
data class InviteConnectResult(val state: String = "", val serverId: String? = null, val channelId: String? = null)

/** connect-new-server: connected now, or the channel is converted first. */
@Serializable
data class NewServerConnectResult(val state: String = "", val serverId: String, val channelId: String? = null)

@Serializable
@RaftCoreInternalApi data class InviteServerSelection(val serverId: String, val approverUserId: String? = null)

@Serializable
private data class InviteConnectBody(val serverId: String, val makePrivate: Boolean? = null)

@Serializable
private data class InviteConnectServersBody(val servers: List<InviteServerSelection>, val makePrivate: Boolean? = null)

@Serializable
private data class InviteNewServerBody(val name: String, val slug: String, val makePrivate: Boolean? = null)

@Serializable
private data class InviteCancelRequestBody(val requestId: String? = null)

@Serializable
private data class WireInviteeErrorBody(
    val error: String? = null,
    val code: String? = null,
    val errorCode: String? = null,
    val limitSide: String? = null,
    val limitServerId: String? = null,
    val serverId: String? = null,
)

/** A refused invitee request: its status, the server's code, whose limit, the kept server, the trace ID. */
data class InviteeRefusal(
    val status: Int,
    val code: String? = null,
    /** `"host"` or `"connecting"` on a limit refusal. */
    val limitSide: String? = null,
    val limitServerId: String? = null,
    /** connect-new-server refused after creating the server: the server the caller keeps. */
    val serverId: String? = null,
    val traceId: String? = null,
    /** The server's own sentence (`error`), surfaced through `resolveServerRefusal` for unknown/dynamic refusals. */
    val error: String? = null,
)

sealed interface InviteeAnswer<out T> {
    /** [status]: 202 for a connect-new-server still connecting; [traceId]: the answer's, for a refusal shown from it. */
    data class Ok<T>(val value: T, val status: Int = 200, val traceId: String? = null) : InviteeAnswer<T>

    data class Refused(val refusal: InviteeRefusal) : InviteeAnswer<Nothing>
}

@RaftCoreInternalApi fun parseSharedExternalInviteForInvitee(bodyText: String): ExternalInviteForInvitee? =
    runCatching { inviteeJson.decodeFromString(ExternalInviteForInvitee.serializer(), bodyText) }.getOrNull()

@RaftCoreInternalApi fun parseSharedGuestAcceptance(bodyText: String): GuestAcceptance? =
    runCatching { inviteeJson.decodeFromString(GuestAcceptance.serializer(), bodyText) }.getOrNull()
        ?.takeIf { it.serverId.isNotBlank() && it.channelId.isNotBlank() }

@RaftCoreInternalApi fun parseSharedInviteConnectResult(bodyText: String): InviteConnectResult? =
    runCatching { inviteeJson.decodeFromString(InviteConnectResult.serializer(), bodyText) }.getOrNull()
        ?.takeIf { it.state == "connecting" || (!it.serverId.isNullOrBlank() && !it.channelId.isNullOrBlank()) }

@RaftCoreInternalApi fun parseSharedNewServerConnectResult(bodyText: String): NewServerConnectResult? =
    runCatching { inviteeJson.decodeFromString(NewServerConnectResult.serializer(), bodyText) }.getOrNull()
        ?.takeIf { it.serverId.isNotBlank() }

@RaftCoreInternalApi fun parseSharedInviteeRefusal(status: Int, bodyText: String?, traceId: String?): InviteeRefusal {
    val body =
        bodyText?.takeIf(String::isNotBlank)?.let { text ->
            runCatching { inviteeJson.decodeFromString(WireInviteeErrorBody.serializer(), text) }.getOrNull()
        }
    fun String?.clean() = this?.trim()?.takeIf(String::isNotBlank)
    return InviteeRefusal(
        status = status,
        code = looseRefusalCode(code = body?.code, errorCode = body?.errorCode),
        limitSide = body?.limitSide.clean(),
        limitServerId = body?.limitServerId.clean(),
        serverId = body?.serverId.clean(),
        traceId = traceId,
        error = body?.error.clean(),
    )
}

@RaftCoreInternalApi fun buildSharedInviteConnectBody(serverId: String, makePrivate: Boolean = false): String =
    inviteeJson.encodeToString(InviteConnectBody.serializer(), InviteConnectBody(serverId, makePrivate.takeIf { it }))

@RaftCoreInternalApi fun buildSharedInviteConnectServersBody(
    servers: List<InviteServerSelection>,
    makePrivate: Boolean = false,
): String =
    inviteeJson.encodeToString(
        InviteConnectServersBody.serializer(),
        InviteConnectServersBody(servers, makePrivate.takeIf { it }),
    )

@RaftCoreInternalApi fun buildSharedInviteNewServerBody(
    name: String,
    slug: String,
    makePrivate: Boolean = false,
): String =
    inviteeJson.encodeToString(
        InviteNewServerBody.serializer(),
        InviteNewServerBody(name, slug, makePrivate.takeIf { it }),
    )

@RaftCoreInternalApi fun buildSharedInviteCancelRequestBody(requestId: String?): String =
    inviteeJson.encodeToString(InviteCancelRequestBody.serializer(), InviteCancelRequestBody(requestId))

/**
 * The invitee's calls through the caller's [RaftNetworkClient] (Foreground: the page is waiting).
 * Network failures and 401 stay the app's usual [DataResult] errors; every other answer is an
 * [InviteeAnswer].
 */
@RaftCoreInternalApi class ExternalInviteAcceptApi(
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {},
) {
    suspend fun get(token: String, priority: DataRequestPriority = DataRequestPriority.Foreground) =
        call("get", priority, base(token), "GET", null, ::parseSharedExternalInviteForInvitee)

    suspend fun acceptAsGuest(token: String) =
        call("guest", DataRequestPriority.Foreground, "${base(token)}/accept-as-guest", "POST", "{}", ::parseSharedGuestAcceptance)

    suspend fun connect(token: String, serverId: String, makePrivate: Boolean = false) =
        call(
            "connect",
            DataRequestPriority.Foreground,
            "${base(token)}/connect",
            "POST",
            buildSharedInviteConnectBody(serverId, makePrivate),
            ::parseSharedInviteConnectResult,
        )

    suspend fun connectServers(token: String, servers: List<InviteServerSelection>, makePrivate: Boolean = false) =
        call(
            "connect_servers",
            DataRequestPriority.Foreground,
            "${base(token)}/connect-servers",
            "POST",
            buildSharedInviteConnectServersBody(servers, makePrivate),
            ::parseSharedMemberConnectOutcomes,
        )

    suspend fun connectNewServer(token: String, name: String, slug: String, makePrivate: Boolean = false) =
        call(
            "new_server",
            DataRequestPriority.Foreground,
            "${base(token)}/connect-new-server",
            "POST",
            buildSharedInviteNewServerBody(name, slug, makePrivate),
            ::parseSharedNewServerConnectResult,
        )

    suspend fun cancelRequest(token: String, requestId: String?) =
        call(
            "cancel_request",
            DataRequestPriority.Foreground,
            "${base(token)}/cancel-request",
            "POST",
            buildSharedInviteCancelRequestBody(requestId),
        ) { Unit }

    private fun base(token: String): String = "external-invites/${token.encodeInviteTokenSegment()}"

    private suspend fun <T> call(
        source: String,
        priority: DataRequestPriority,
        path: String,
        method: String,
        bodyText: String?,
        parse: (String) -> T?,
    ): DataResult<InviteeAnswer<T>> {
        val response =
            try {
                networkClient.request(priority = priority, path = path, method = method, bodyText = bodyText)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                warningLogger("request_failed source=external_invite_$source exception=${exception::class.simpleName}")
                return DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
            }
        if (response.isSuccessful) {
            val parsed = parse(response.bodyText.orEmpty())
            if (parsed == null) {
                warningLogger("parse_failed source=external_invite_$source status=${response.code}")
                return DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(response.code, traceId = response.traceId)))
            }
            return DataResult.Content(InviteeAnswer.Ok(parsed, response.code, response.traceId))
        }
        val refusal = parseSharedInviteeRefusal(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
        if (response.code == 401) return authSessionRequiredResult()
        return DataResult.Content(InviteeAnswer.Refused(refusal))
    }
}

/** The token as a path segment (web `encodeURIComponent`). */
@RaftCoreInternalApi fun String.encodeInviteTokenSegment(): String =
    buildString {
        for (byte in this@encodeInviteTokenSegment.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.!~*'()") {
                append(char)
            } else {
                append('%')
                append("0123456789ABCDEF"[value shr 4])
                append("0123456789ABCDEF"[value and 0xF])
            }
        }
    }
