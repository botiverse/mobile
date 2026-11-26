package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Non-owner Pro seat request API (`botiverse/slock` `#11507`, `packages/shared/src/actionCards.ts`
 * `seatsAddPurposeSchema` / `SEAT_REQUEST_REASON_MAX_LENGTH`, `packages/server/src/routes/actions.ts`
 * `POST /api/actions/seat-requests`).
 *
 * Behind `pro_seat_add` (`PRO_SEAT_ADD_FEATURE_FLAG_KEY`); Android/Desktop ONLY (never offered on
 * iOS under App Store Guideline 3.1.1).
 */
@RaftCoreInternalApi const val SEAT_REQUEST_REASON_MAX_LENGTH: Int = 500

@RaftCoreInternalApi const val SEAT_REQUEST_MAX_INVITES: Int = 20

/**
 * Typed `purpose` discriminator matching `seatsAddPurposeSchema` (`z.strictObject` discriminated
 * union on `kind`).
 */
@RaftCoreInternalApi sealed interface SeatRequestPurpose {
    data class Join(val userId: String, val name: String) : SeatRequestPurpose

    data class Invite(val emails: List<String>) : SeatRequestPurpose

    data class AgentCreate(val name: String) : SeatRequestPurpose

    data object AssistantCreate : SeatRequestPurpose

    data class MemberPromote(val userId: String, val name: String, val role: String) : SeatRequestPurpose
}

@RaftCoreInternalApi data class SeatRequestResponse(
    val channelId: String,
    val messageId: String? = null,
    val existing: Boolean = false,
)

private val seatRequestJson = Json { ignoreUnknownKeys = true }

/**
 * Serializes the strict `POST /api/actions/seat-requests` request body:
 * `{ ownerUserId?, purpose, reason? }` (omitting `ownerUserId` when null so hidden-roster servers
 * pick the owner, and omitting `reason` when null or blank).
 */
@RaftCoreInternalApi fun encodeSeatRequestBody(
    purpose: SeatRequestPurpose,
    ownerUserId: String? = null,
    reason: String? = null,
): String {
    val cleanOwnerId = ownerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val cleanReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(SEAT_REQUEST_REASON_MAX_LENGTH)
    val payload =
        buildJsonObject {
            if (cleanOwnerId != null) put("ownerUserId", JsonPrimitive(cleanOwnerId))
            put("purpose", purpose.toWireJsonObject())
            if (cleanReason != null) put("reason", JsonPrimitive(cleanReason))
        }
    return payload.toString()
}

private fun SeatRequestPurpose.toWireJsonObject(): JsonObject =
    when (this) {
        is SeatRequestPurpose.Join ->
            buildJsonObject {
                put("kind", JsonPrimitive("join"))
                put("userId", JsonPrimitive(userId.trim()))
                put("name", JsonPrimitive(name.trim().take(120)))
            }
        is SeatRequestPurpose.Invite ->
            buildJsonObject {
                put("kind", JsonPrimitive("invite"))
                put(
                    "emails",
                    JsonArray(
                        emails.asSequence()
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .take(SEAT_REQUEST_MAX_INVITES)
                            .map(::JsonPrimitive)
                            .toList()
                    ),
                )
            }
        is SeatRequestPurpose.AgentCreate ->
            buildJsonObject {
                put("kind", JsonPrimitive("agent:create"))
                put("name", JsonPrimitive(name.trim().take(60)))
            }
        SeatRequestPurpose.AssistantCreate ->
            buildJsonObject {
                put("kind", JsonPrimitive("assistant:create"))
            }
        is SeatRequestPurpose.MemberPromote ->
            buildJsonObject {
                put("kind", JsonPrimitive("member:promote"))
                put("userId", JsonPrimitive(userId.trim()))
                put("name", JsonPrimitive(name.trim().take(120)))
                put("role", JsonPrimitive(if (role.trim().lowercase() == "admin") "admin" else "member"))
            }
    }

/**
 * Parses `POST /api/actions/seat-requests` (200 existing / 201 created returning
 * `{ channelId, messageId?, existing? }`, or 400/403/404/409/429/5xx error envelopes).
 */
@RaftCoreInternalApi fun parseSeatRequestResponse(
    statusCode: Int,
    bodyText: String?,
    traceId: String? = null,
): DataResult<SeatRequestResponse> {
    if (statusCode !in 200..299) {
        val envelope = if (statusCode > 0) parseHttpErrorEnvelope(bodyText) else null
        val mappedCode =
            when (statusCode) {
                400 -> "seat_request_invalid"
                403 -> "seat_request_forbidden"
                404 -> "seat_request_not_found"
                409 -> "seat_request_conflict"
                429 -> "seat_request_too_many_open"
                else -> "seat_request_http_$statusCode"
            }
        return DataResult.Error(
            ScreenError(
                code = mappedCode,
                retryable = statusCode >= 500,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = traceId,
            )
        )
    }
    val raw = bodyText?.trim()?.takeIf { it.isNotEmpty() }
        ?: return DataResult.Error(ScreenError(code = "seat_request_contract_mismatch", retryable = false, traceId = traceId))
    val root =
        runCatching { seatRequestJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return DataResult.Error(ScreenError(code = "seat_request_contract_mismatch", retryable = false, traceId = traceId))
    val channelId = root.nonBlankSeatRequestStringOrNull("channelId")
        ?: return DataResult.Error(ScreenError(code = "seat_request_contract_mismatch", retryable = false, traceId = traceId))
    val messageId = root.nonBlankSeatRequestStringOrNull("messageId")
    val existing = (root["existing"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    return DataResult.Content(
        SeatRequestResponse(
            channelId = channelId,
            messageId = messageId,
            existing = existing,
        )
    )
}

private fun JsonObject.nonBlankSeatRequestStringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

@RaftCoreInternalApi interface SeatRequestApi {
    suspend fun requestSeats(
        serverId: ServerId,
        purpose: SeatRequestPurpose,
        ownerUserId: String? = null,
        reason: String? = null,
    ): DataResult<SeatRequestResponse>
}

@RaftCoreInternalApi class NetworkSeatRequestApi(
    private val networkClient: RaftNetworkClient,
) : SeatRequestApi {
    override suspend fun requestSeats(
        serverId: ServerId,
        purpose: SeatRequestPurpose,
        ownerUserId: String?,
        reason: String?,
    ): DataResult<SeatRequestResponse> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "actions/seat-requests",
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = encodeSeatRequestBody(purpose = purpose, ownerUserId = ownerUserId, reason = reason),
                )
            parseSeatRequestResponse(
                statusCode = response.code,
                bodyText = if (response.isSuccessful) response.bodyText else response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText,
                traceId = response.traceId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        }
}
