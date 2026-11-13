package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A server join link (`/join/:token`) or invite email, the invitee's side: the page the link opens
 * (web `InviteAcceptPage`).
 *
 * Server contract, botiverse/slock `staging` (`packages/server/src/routes/auth.ts`,
 * `services/inviteService.ts`):
 *  - `GET auth/invite-info?token=` → `InviteInfo` (public); 404 for an invalid or expired invite,
 *    429 rate limit.
 *  - `POST auth/accept-invite` `{ token, agreementId? }` → `{ serverId, serverName }`. 409
 *    `{ error: "agreement_required" | "agreement_changed", agreement }` when the server has an
 *    agreement to accept first; 400 `{ error, code? }` for an invite that can't be used (revoked,
 *    expired, used up, seat limit), whose `error` the web shows as it is.
 *  - `POST invites/seat-blocked` `{ token }`: a signed-in person's page of a full link tells its
 *    owners (best effort).
 *
 * Field names are exact camelCase.
 */
private val serverJoinJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** The server's agreement to accept before joining (`PublicAgreement`). */
@Serializable
data class ServerJoinAgreement(
    val id: String,
    val title: String = "",
    val bodyMarkdown: String = "",
    val version: Int = 0,
)

/** What the join page shows (web `InviteInfo`). */
@Serializable
data class ServerJoinInviteInfo(
    /** `email` or `join_link`. */
    val kind: String = "join_link",
    val serverName: String = "",
    val inviterName: String? = null,
    val memberCount: Int = 0,
    val agentCount: Int = 0,
    val insideCountsHidden: Boolean = false,
    val humanSeatLimitReached: Boolean = false,
    /** The seats are full and the owners are told (pro_seat_add on for this server). */
    val seatNoticeEnabled: Boolean = false,
    val deploymentUserLimitReached: Boolean = false,
    val agreement: ServerJoinAgreement? = null,
)

@Serializable
data class ServerJoinAcceptResult(val serverId: String, val serverName: String = "")

@Serializable
private data class ServerJoinAcceptBody(val token: String, val agreementId: String? = null)

@Serializable
private data class ServerJoinTokenBody(val token: String)

@Serializable
private data class WireServerJoinErrorBody(
    val error: String? = null,
    val code: String? = null,
    val agreement: ServerJoinAgreement? = null,
)

/** A refused join request: its status, the server's `error` text and code, a new agreement, the trace ID. */
data class ServerJoinRefusal(
    val status: Int,
    val error: String? = null,
    val code: String? = null,
    /** `agreement_required` / `agreement_changed`: the agreement to accept, then join again. */
    val agreement: ServerJoinAgreement? = null,
    val traceId: String? = null,
)

sealed interface ServerJoinAnswer<out T> {
    data class Ok<T>(val value: T) : ServerJoinAnswer<T>

    data class Refused(val refusal: ServerJoinRefusal) : ServerJoinAnswer<Nothing>
}

internal fun parseSharedServerJoinInviteInfo(bodyText: String): ServerJoinInviteInfo? =
    runCatching { serverJoinJson.decodeFromString(ServerJoinInviteInfo.serializer(), bodyText) }.getOrNull()

internal fun parseSharedServerJoinAcceptResult(bodyText: String): ServerJoinAcceptResult? =
    runCatching { serverJoinJson.decodeFromString(ServerJoinAcceptResult.serializer(), bodyText) }.getOrNull()
        ?.takeIf { it.serverId.isNotBlank() }

internal fun parseSharedServerJoinRefusal(status: Int, bodyText: String?, traceId: String?): ServerJoinRefusal {
    val body =
        bodyText?.takeIf(String::isNotBlank)?.let { text ->
            runCatching { serverJoinJson.decodeFromString(WireServerJoinErrorBody.serializer(), text) }.getOrNull()
        }
    val error = body?.error?.trim()?.takeIf(String::isNotBlank)
    val agreement = body?.agreement?.takeIf { error == "agreement_required" || error == "agreement_changed" }
    return ServerJoinRefusal(
        status = status,
        error = error,
        code = body?.code?.trim()?.takeIf(String::isNotBlank),
        agreement = agreement,
        traceId = traceId,
    )
}

internal fun buildSharedServerJoinAcceptBody(token: String, agreementId: String?): String =
    serverJoinJson.encodeToString(ServerJoinAcceptBody.serializer(), ServerJoinAcceptBody(token, agreementId))

internal fun buildSharedServerJoinTokenBody(token: String): String =
    serverJoinJson.encodeToString(ServerJoinTokenBody.serializer(), ServerJoinTokenBody(token))

/**
 * The join page's calls through the caller's [RaftNetworkClient] (Foreground: the page is waiting).
 * Network failures and 401 stay the app's usual [DataResult] errors; every other answer is a
 * [ServerJoinAnswer].
 */
internal class ServerJoinLinkApi(
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {},
) {
    suspend fun info(token: String) =
        call("info", "auth/invite-info?token=${token.encodeInviteTokenSegment()}", "GET", null, ::parseSharedServerJoinInviteInfo)

    suspend fun accept(token: String, agreementId: String?) =
        call("accept", "auth/accept-invite", "POST", buildSharedServerJoinAcceptBody(token, agreementId), ::parseSharedServerJoinAcceptResult)

    suspend fun tellOwnersSeatsFull(token: String) =
        call("seat_blocked", "invites/seat-blocked", "POST", buildSharedServerJoinTokenBody(token)) { Unit }

    private suspend fun <T> call(
        source: String,
        path: String,
        method: String,
        bodyText: String?,
        parse: (String) -> T?,
    ): DataResult<ServerJoinAnswer<T>> {
        val response =
            try {
                networkClient.request(priority = DataRequestPriority.Foreground, path = path, method = method, bodyText = bodyText)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                warningLogger("request_failed source=server_join_$source exception=${exception::class.simpleName}")
                return DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
            }
        if (response.isSuccessful) {
            val parsed = parse(response.bodyText.orEmpty())
            if (parsed == null) {
                warningLogger("parse_failed source=server_join_$source status=${response.code}")
                return DataResult.Content(ServerJoinAnswer.Refused(ServerJoinRefusal(response.code, traceId = response.traceId)))
            }
            return DataResult.Content(ServerJoinAnswer.Ok(parsed))
        }
        if (response.code == 401) return authSessionRequiredResult()
        return DataResult.Content(
            ServerJoinAnswer.Refused(parseSharedServerJoinRefusal(response.code, response.bodyText ?: response.errorBodyText, response.traceId))
        )
    }
}
