package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * People a full invite or join link turned away from a server (`botiverse/slock` `#11576`,
 * `packages/shared/src/index.ts` `SeatJoinBlock` / `SeatJoinSeatUsage` / `SeatJoinBlocksResponse`,
 * `packages/server/src/routes/servers.ts` `GET /api/servers/:id/seat-join-blocks`):
 *
 *     GET /api/servers/:id/seat-join-blocks
 *
 * Behind `pro_seat_add` (`PRO_SEAT_ADD_FEATURE_FLAG_KEY`); only non-guest members fetch it.
 */
@RaftCoreInternalApi const val SEAT_JOIN_BLOCKED_HOME_NOTIFICATION_PREFIX: String = "seat-join-blocked:"

/** Web `PINGED_LIST_SAFETY_POLL_MS` (5 min): safety poll while visible/active. */
@RaftCoreInternalApi const val SEAT_JOIN_BLOCKS_POLL_MS: Long = 300_000L

/** Minimum elapsed interval between foreground/reconnect rereads (`RefreshIfStale`). */
@RaftCoreInternalApi const val SEAT_JOIN_BLOCKS_MIN_REREAD_MS: Long = 30_000L

@RaftCoreInternalApi fun seatJoinBlockedHomeNotificationId(blockId: String): String =
    SEAT_JOIN_BLOCKED_HOME_NOTIFICATION_PREFIX + blockId

@RaftCoreInternalApi fun String.seatJoinBlockIdFromHomeNotificationIdOrNull(): String? =
    takeIf { it.startsWith(SEAT_JOIN_BLOCKED_HOME_NOTIFICATION_PREFIX) }
        ?.removePrefix(SEAT_JOIN_BLOCKED_HOME_NOTIFICATION_PREFIX)
        ?.takeIf(String::isNotBlank)

/**
 * Web `useSeatJoinBlocks`: `role !== null && role !== "guest"`. Owners and admins see all blocked
 * joiners; regular members see people turned away by links they created; guests never request.
 */
@RaftCoreInternalApi fun serverRoleCanReadSeatJoinBlocks(role: String?): Boolean {
    val clean = role?.trim()?.lowercase()
    return !clean.isNullOrEmpty() && clean != "guest"
}

@RaftCoreInternalApi data class SeatJoinBlock(
    val id: String,
    val userId: String,
    val name: String,
    val inviterUserId: String? = null,
    val inviterName: String? = null,
    val createdAt: String = "",
)

@RaftCoreInternalApi data class SeatJoinSeatUsage(
    val seats: Int,
    val humans: Int,
    val agents: Int,
    val pricing: ProPricing = ProPricing.SeatV1,
)

@RaftCoreInternalApi data class SeatJoinBlocksResponse(
    val blocks: List<SeatJoinBlock> = emptyList(),
    val usage: SeatJoinSeatUsage? = null,
)

private val seatJoinBlocksJson = Json { ignoreUnknownKeys = true }

/**
 * Parses `GET /api/servers/:id/seat-join-blocks`.
 * Fails closed (`DataResult.Error`) on non-2xx, blank/malformed JSON, or missing `blocks` array,
 * while skipping malformed individual block rows inside a valid envelope.
 */
@RaftCoreInternalApi fun parseSeatJoinBlocksResponse(
    statusCode: Int,
    bodyText: String?,
    traceId: String? = null,
): DataResult<SeatJoinBlocksResponse> {
    if (statusCode !in 200..299) {
        val envelope = if (statusCode > 0) parseHttpErrorEnvelope(bodyText) else null
        return DataResult.Error(
            ScreenError(
                code = "seat_join_blocks_http_$statusCode",
                retryable = statusCode >= 500,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = traceId,
            )
        )
    }
    val raw = bodyText?.trim()?.takeIf { it.isNotEmpty() }
        ?: return DataResult.Error(ScreenError(code = "seat_join_blocks_contract_mismatch", retryable = false, traceId = traceId))
    val root =
        runCatching { seatJoinBlocksJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return DataResult.Error(ScreenError(code = "seat_join_blocks_contract_mismatch", retryable = false, traceId = traceId))
    val blocksArray = root["blocks"] as? JsonArray
        ?: return DataResult.Error(ScreenError(code = "seat_join_blocks_contract_mismatch", retryable = false, traceId = traceId))
    val blocks = blocksArray.mapNotNull { element -> (element as? JsonObject)?.toSeatJoinBlockOrNull() }
    if (blocks.isEmpty()) {
        return DataResult.Content(SeatJoinBlocksResponse(blocks = emptyList(), usage = null))
    }
    val usage = (root["usage"] as? JsonObject)?.toSeatJoinSeatUsageOrNull()
    return DataResult.Content(SeatJoinBlocksResponse(blocks = blocks, usage = usage))
}

private fun JsonObject.toSeatJoinBlockOrNull(): SeatJoinBlock? {
    val id = nonBlankStringOrNull("id") ?: return null
    val userId = nonBlankStringOrNull("userId") ?: return null
    val name = nonBlankStringOrNull("name") ?: return null
    return SeatJoinBlock(
        id = id,
        userId = userId,
        name = name,
        inviterUserId = nonBlankStringOrNull("inviterUserId"),
        inviterName = nonBlankStringOrNull("inviterName"),
        createdAt = nonBlankStringOrNull("createdAt").orEmpty(),
    )
}

private fun JsonObject.toSeatJoinSeatUsageOrNull(): SeatJoinSeatUsage? {
    val seats = nonNegativeIntOrNull("seats") ?: return null
    val humans = nonNegativeIntOrNull("humans") ?: return null
    val agents = nonNegativeIntOrNull("agents") ?: return null
    val pricing = ProPricing.fromWireOrNull(nonBlankStringOrNull("pricing")) ?: ProPricing.SeatV1
    return SeatJoinSeatUsage(seats = seats, humans = humans, agents = agents, pricing = pricing)
}

private fun JsonObject.nonBlankStringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.nonNegativeIntOrNull(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.intOrNull?.takeIf { it >= 0 }
}

@RaftCoreInternalApi interface SeatJoinBlocksDataSource {
    suspend fun list(serverId: ServerId): DataResult<SeatJoinBlocksResponse>
}

@RaftCoreInternalApi class NetworkSeatJoinBlocksDataSource(
    private val networkClient: RaftNetworkClient,
) : SeatJoinBlocksDataSource {
    override suspend fun list(serverId: ServerId): DataResult<SeatJoinBlocksResponse> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Background,
                    path = "servers/${encodeSystemAttentionPathSegment(serverId.value)}/seat-join-blocks",
                    serverIdOverride = serverId,
                )
            parseSeatJoinBlocksResponse(
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
