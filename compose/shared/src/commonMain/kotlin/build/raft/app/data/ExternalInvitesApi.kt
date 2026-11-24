package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.reportProjectionRowDropped
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * External channel invites, the host side (#wg-rbac:#16): a host server owner or admin invites
 * people outside the server to a channel by email and manages the open invites.
 *
 * Server contract, botiverse/slock `staging` @ 0e8b4fab7440307f58c64e3448e820f517359194
 * (`packages/server/src/routes/channels.ts` "External channel invites",
 * `services/externalInviteService.ts`):
 *  - `POST channels/:id/external-invites/preview` `{ emails: string[] }` (1 to 20) →
 *    `{ valid, duplicates, invalid, alreadyPending, alreadyMembers, alreadyInChannel,
 *    guestsElsewhere: [{ email, channelName | null }], slotsLeft }`.
 *  - `POST channels/external-invites/preview` `{ emails }` (staging 97a3e293d71a, Create
 *    Channel's External): the same answer for the current server, before the channel exists.
 *  - `POST channels/:id/external-invites` `{ emails, makePrivate?, connectInviteMemberEmails? }` →
 *    `{ sent: [{ id, email, expiresAt }], added: string[], skipped: { duplicates, invalid,
 *    alreadyPending, alreadyMembers, alreadyInChannel } }` (`guestsElsewhere` are now sent an
 *    ordinary emailed invite to connect or create a server, slock #16294).
 *  - `GET channels/:id/external-invites` → `{ invites: [{ id, email, awaitingApproval, connecting,
 *    connectPhase, revocable, invitedBy: { id, name } | null, createdAt, expiresAt }], slotsLeft }`.
 *  - `POST channels/:id/external-invites/:inviteId/resend` → `{ id, email, expiresAt }`.
 *  - `DELETE channels/:id/external-invites/:inviteId` → `{ ok: true }`.
 *  - `GET channels/external-connect-servers` ("Your servers", botiverse/slock `staging` @
 *    97a3e293d71a924eb9910929f1f2e0ed696d26b8, `externalInviteService.listOwnConnectableServers`):
 *    the servers the host's owner/admin owns or administers that could connect to one of its
 *    channels → `{ servers: ConnectableServer[] }` (`{ id, name, slug, avatarUrl,
 *    canConnectDirectly, needsApproval, approvers, atLimit, canUpgrade }`, the invite page's shape).
 *    Not read against a channel.
 *  - `POST channels/:id/external-connect-servers` `{ serverIds: string[], makePrivate? }` (same SHA):
 *    the host owner/admin connects those servers now, no email, no approval →
 *    `{ servers: ServerConnectOutcome[] }`, one per chosen server (see [ServerConnectOutcome]); the
 *    whole request refused is `{ error, code }` as the routes below (400 `invalid_input` for the list).
 *  - `GET channels/external-availability` (same SHA, `externalInviteService.getExternalChannelAvailability`)
 *    → `{ freeLimitReached: boolean }`: the current server's one Free external channel is taken.
 *    Host owner/admin only.
 *  - A refusal is `{ error, code }`
 *    (`EXTERNAL_INVITE_ERROR_STATUS`: not_found 404, forbidden 403, channel_membership_required
 *    403, invalid_input 400, channel_not_invitable 400, make_private_required 400,
 *    channel_archived 409, invite_not_pending 409, connecting 409, daily_cap 429, invite_open 409)
 *    or a send-time limit (`sendExternalLimitError`): 409 `{ error, code: "free_external_limit" |
 *    "channel_full", limitSide, limitServerId }`.
 *
 * Web: `packages/web/src/components/channel/externalInvites.ts`. Field names are exact camelCase.
 */
private val externalInvitesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** The server's `EXTERNAL_INVITE_MAX_ENTRIES`: raw entries per send, duplicates included. */
internal const val EXTERNAL_INVITE_MAX_EMAILS: Int = 20

/** The server's `EXTERNAL_INVITE_EXPIRY_DAYS`: how long an invite link works. */
internal const val EXTERNAL_INVITE_EXPIRY_DAYS: Int = 14

@Serializable
data class ExternalInviteGuestElsewhere(val email: String, val channelName: String? = null)

/** What the server makes of the entries before anything is sent (web `ExternalInvitePreview`). */
@Serializable
data class ExternalInvitePreview(
    val valid: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
    val alreadyPending: List<String> = emptyList(),
    /** Members of the host server not in the channel yet: the send adds them to it. */
    val alreadyMembers: List<String> = emptyList(),
    val alreadyInChannel: List<String> = emptyList(),
    /**
     * The host server's members (not Guests) among [alreadyInChannel], who may still be sent an
     * invite to connect a server of theirs (slock #15279). Absent from an older server.
     */
    val alreadyInChannelMembers: List<String> = emptyList(),
    val guestsElsewhere: List<ExternalInviteGuestElsewhere> = emptyList(),
    /** How many people the channel can still be invited, before these. Absent from an older server. */
    val slotsLeft: Int? = null,
)

@Serializable
data class SentExternalInvite(val id: String, val email: String, val expiresAt: String = "")

@Serializable
data class SendExternalInvitesResult(
    val sent: List<SentExternalInvite> = emptyList(),
    /** The host server's members the send added to the channel. */
    val added: List<String> = emptyList(),
)

@Serializable
data class ExternalInviteInviter(val id: String, val name: String)

/** One open invite in the channel's Invited group (web `ChannelExternalInvite`). */
@Serializable
data class ChannelExternalInvite(
    val id: String,
    val email: String,
    val awaitingApproval: Boolean = false,
    /** A server is being connected with it; revoking is refused (409 `connecting`) until the conversion ends. */
    val connecting: Boolean = false,
    val connectPhase: String? = null,
    /** False while the conversion it started can't be interrupted. Absent from older servers: revocable. */
    val revocable: Boolean = true,
    val invitedBy: ExternalInviteInviter? = null,
    val createdAt: String = "",
    val expiresAt: String,
)

@Serializable
data class ChannelExternalInviteList(
    val invites: List<ChannelExternalInvite> = emptyList(),
    /** How many more people the channel can be invited; absent from an older server. */
    val slotsLeft: Int? = null,
)

/** `GET channels/external-availability`. */
@Serializable
data class ExternalChannelAvailability(val freeLimitReached: Boolean = false)

internal fun parseSharedExternalChannelAvailability(bodyText: String): ExternalChannelAvailability? =
    runCatching { externalInvitesJson.decodeFromString(ExternalChannelAvailability.serializer(), bodyText) }.getOrNull()

/** "Your servers" (`GET channels/external-connect-servers`). */
@Serializable
data class OwnConnectableServers(val servers: List<InviteConnectableServer> = emptyList())

@Serializable
internal data class ExternalConnectServersBody(val serverIds: List<String>, val makePrivate: Boolean? = null)

internal fun buildSharedExternalConnectServersBody(serverIds: List<String>, makePrivate: Boolean = false): String =
    externalInvitesJson.encodeToString(
        ExternalConnectServersBody.serializer(),
        ExternalConnectServersBody(serverIds, makePrivate.takeIf { it }),
    )

/** Rows without an id are dropped and logged (they could not be chosen or connected). */
internal fun parseSharedOwnConnectableServers(bodyText: String): OwnConnectableServers? =
    runCatching { externalInvitesJson.decodeFromString(OwnConnectableServers.serializer(), bodyText) }
        .getOrNull()
        ?.let { list ->
            list.copy(
                servers =
                    list.servers.filter { server ->
                        server.id.isNotBlank().also { kept ->
                            if (!kept) reportProjectionRowDropped("external_connect_servers", "server_id_blank")
                        }
                    }
            )
        }

@Serializable
internal data class ExternalInviteEmailsBody(
    val emails: List<String>,
    val makePrivate: Boolean? = null,
    /**
     * The host's members among [emails] who are also sent an invite to connect a server of theirs
     * ("Invite to connect their server", slock #15279); every member is added to the channel either
     * way. Left out when empty, as the web does.
     */
    val connectInviteMemberEmails: List<String>? = null,
)

@Serializable
private data class WireExternalInviteErrorBody(
    val error: String? = null,
    val code: String? = null,
    val limitSide: String? = null,
)

/** A refused external-invite request: its status, the server's code and the answer's trace ID. */
data class ExternalInviteRefusal(
    val status: Int,
    val code: String?,
    val error: String?,
    /** `"host"` or `"connecting"` on a limit refusal. */
    val limitSide: String? = null,
    val traceId: String? = null,
)

/** What an external-invite route answered: its body, or a refusal (any non-2xx other than 401). */
sealed interface ExternalInviteAnswer<out T> {
    /** [traceId]: the answer's, for a refusal shown from inside it (a refused server of connect-servers). */
    data class Ok<T>(val value: T, val traceId: String? = null) : ExternalInviteAnswer<T>

    data class Refused(val refusal: ExternalInviteRefusal) : ExternalInviteAnswer<Nothing>
}

internal fun buildSharedExternalInviteEmailsBody(
    emails: List<String>,
    makePrivate: Boolean = false,
    connectInviteMemberEmails: List<String> = emptyList(),
): String =
    externalInvitesJson.encodeToString(
        ExternalInviteEmailsBody.serializer(),
        ExternalInviteEmailsBody(emails, makePrivate.takeIf { it }, connectInviteMemberEmails.takeIf { it.isNotEmpty() }),
    )

internal fun parseSharedExternalInvitePreview(bodyText: String): ExternalInvitePreview? =
    runCatching { externalInvitesJson.decodeFromString(ExternalInvitePreview.serializer(), bodyText) }.getOrNull()

internal fun parseSharedSendExternalInvitesResult(bodyText: String): SendExternalInvitesResult? =
    runCatching { externalInvitesJson.decodeFromString(SendExternalInvitesResult.serializer(), bodyText) }.getOrNull()

internal fun parseSharedChannelExternalInviteList(bodyText: String): ChannelExternalInviteList? =
    runCatching { externalInvitesJson.decodeFromString(ChannelExternalInviteList.serializer(), bodyText) }
        .getOrNull()
        ?.let { list -> list.copy(invites = list.invites.filter { it.id.isNotBlank() && it.email.isNotBlank() }) }

internal fun parseSharedSentExternalInvite(bodyText: String): SentExternalInvite? =
    runCatching { externalInvitesJson.decodeFromString(SentExternalInvite.serializer(), bodyText) }.getOrNull()

/** A refused request's body; codes and sides blank-trimmed to null. */
internal fun parseSharedExternalInviteRefusal(status: Int, bodyText: String?, traceId: String?): ExternalInviteRefusal {
    val body =
        bodyText?.takeIf(String::isNotBlank)?.let { text ->
            runCatching { externalInvitesJson.decodeFromString(WireExternalInviteErrorBody.serializer(), text) }.getOrNull()
        }
    return ExternalInviteRefusal(
        status = status,
        code = body?.code?.trim()?.takeIf(String::isNotBlank),
        error = body?.error?.trim()?.takeIf(String::isNotBlank),
        limitSide = body?.limitSide?.trim()?.takeIf(String::isNotBlank),
        traceId = traceId,
    )
}

/**
 * The host side's calls, through the caller's [RaftNetworkClient]. Network failures and 401 stay the
 * app's usual [DataResult] errors; every other answer is an [ExternalInviteAnswer].
 */
internal class ExternalInvitesApi(
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {},
) {
    suspend fun list(channelId: ChannelId): DataResult<ExternalInviteAnswer<ChannelExternalInviteList>> =
        call("list", DataRequestPriority.Visible, "${base(channelId)}", "GET", null, ::parseSharedChannelExternalInviteList)

    suspend fun preview(channelId: ChannelId, emails: List<String>): DataResult<ExternalInviteAnswer<ExternalInvitePreview>> =
        call(
            "preview",
            DataRequestPriority.Foreground,
            "${base(channelId)}/preview",
            "POST",
            buildSharedExternalInviteEmailsBody(emails),
            ::parseSharedExternalInvitePreview,
        )

    /** Create Channel's External review: the current server's, before the channel exists. */
    suspend fun previewForNewChannel(emails: List<String>): DataResult<ExternalInviteAnswer<ExternalInvitePreview>> =
        call(
            "preview_new",
            DataRequestPriority.Foreground,
            "channels/external-invites/preview",
            "POST",
            buildSharedExternalInviteEmailsBody(emails),
            ::parseSharedExternalInvitePreview,
        )

    suspend fun send(
        channelId: ChannelId,
        emails: List<String>,
        makePrivate: Boolean,
        connectInviteMemberEmails: List<String> = emptyList(),
    ): DataResult<ExternalInviteAnswer<SendExternalInvitesResult>> =
        call(
            "send",
            DataRequestPriority.Foreground,
            base(channelId),
            "POST",
            buildSharedExternalInviteEmailsBody(emails, makePrivate, connectInviteMemberEmails),
            ::parseSharedSendExternalInvitesResult,
        )

    suspend fun resend(channelId: ChannelId, inviteId: String): DataResult<ExternalInviteAnswer<SentExternalInvite>> =
        call(
            "resend",
            DataRequestPriority.Foreground,
            "${base(channelId)}/${inviteId.encodePathSegment()}/resend",
            "POST",
            null,
            ::parseSharedSentExternalInvite,
        )

    suspend fun revoke(channelId: ChannelId, inviteId: String): DataResult<ExternalInviteAnswer<Unit>> =
        call("revoke", DataRequestPriority.Foreground, "${base(channelId)}/${inviteId.encodePathSegment()}", "DELETE", null) { Unit }

    /** Whether the current server's one Free external channel is taken (Foreground: read on a tap). */
    suspend fun externalAvailability(): DataResult<ExternalInviteAnswer<ExternalChannelAvailability>> =
        call(
            "availability",
            DataRequestPriority.Foreground,
            "channels/external-availability",
            "GET",
            null,
            ::parseSharedExternalChannelAvailability,
        )

    /** "Your servers": not read against a channel (the caller leaves out a joint channel's own). */
    suspend fun ownConnectableServers(): DataResult<ExternalInviteAnswer<OwnConnectableServers>> =
        call("own_servers", DataRequestPriority.Visible, "channels/external-connect-servers", "GET", null, ::parseSharedOwnConnectableServers)

    /** Connects the host owner/admin's own [serverIds] to the channel now: each server's outcome. */
    suspend fun connectOwnServers(
        channelId: ChannelId,
        serverIds: List<String>,
        makePrivate: Boolean,
    ): DataResult<ExternalInviteAnswer<List<ServerConnectOutcome>>> =
        call(
            "connect_servers",
            DataRequestPriority.Foreground,
            "channels/${channelId.value.encodePathSegment()}/external-connect-servers",
            "POST",
            buildSharedExternalConnectServersBody(serverIds, makePrivate),
            ::parseSharedMemberConnectOutcomes,
        )

    private fun base(channelId: ChannelId): String = "channels/${channelId.value.encodePathSegment()}/external-invites"

    private suspend fun <T> call(
        source: String,
        priority: DataRequestPriority,
        path: String,
        method: String,
        bodyText: String?,
        parse: (String) -> T?,
    ): DataResult<ExternalInviteAnswer<T>> {
        val response =
            try {
                networkClient.request(priority = priority, path = path, method = method, bodyText = bodyText)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                warningLogger("request_failed source=external_invites_$source exception=${exception::class.simpleName}")
                return DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
            }
        if (response.isSuccessful) {
            val parsed = parse(response.bodyText.orEmpty())
            if (parsed == null) {
                warningLogger("parse_failed source=external_invites_$source status=${response.code}")
                return DataResult.Content(
                    ExternalInviteAnswer.Refused(ExternalInviteRefusal(response.code, code = null, error = null, traceId = response.traceId))
                )
            }
            return DataResult.Content(ExternalInviteAnswer.Ok(parsed, response.traceId))
        }
        val refusal = parseSharedExternalInviteRefusal(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
        if (response.code == 401) return authSessionRequiredResult(refusal.error)
        return DataResult.Content(ExternalInviteAnswer.Refused(refusal))
    }
}

/** A path segment as the web's `encodeURIComponent` writes it (ids are uuids; this only guards). */
private fun String.encodePathSegment(): String =
    buildString {
        for (byte in this@encodePathSegment.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.~") {
                append(char)
            } else {
                append('%')
                append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((byte.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }

/**
 * Splits pasted text into one entry per address (web `parseEmailEntries`): commas, semicolons,
 * spaces and new lines all separate. Entries stay as typed; the server normalizes them.
 */
internal fun parseExternalInviteEmailEntries(text: String): List<String> =
    text.split(Regex("[\\s,;]+")).filter(String::isNotEmpty)
