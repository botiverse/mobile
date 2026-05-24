package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed parsers for unread/read-receipt routes.
 *
 * Server contracts (`packages/server/src/routes/unread.ts`,
 * `packages/server/src/routes/messages.ts`):
 *  - GET  /api/unread/counts                  → `{ channels: {[channelId]: number} }`
 *  - GET  /api/servers/:id/unread/summary     → `{ servers: ServerUnreadSummary[],
 *                                                  totalUnreadCount, mentionCount }`
 *  - POST /api/messages/channels/:id/read     → `{ channelId, seq, unreadCount, changed }`
 *  - POST /api/messages/channels/:id/read/all → same shape
 *  - POST /api/messages/channels/:id/unread   → same shape
 *  - POST /api/inbox/read/all                 → same shape (channelId may be absent)
 *
 * Field names are exact camelCase. No snake_case fallbacks, no nested
 * `data` wrapper sniffing.
 */
private val unreadJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireChannelUnreadCounts(
    val channels: Map<String, Int> = emptyMap(),
)

@Serializable
private data class WireServerUnreadSummary(
    val serverId: String,
    val totalUnreadCount: Int = 0,
    val channelUnreadCount: Int = 0,
    val directMessageUnreadCount: Int = 0,
    val mentionCount: Int = 0,
    val taskUnreadCount: Int = 0,
    val updatedAt: String? = null,
)

@Serializable
private data class WireServerUnreadSummaryResponse(
    val servers: List<WireServerUnreadSummary> = emptyList(),
    val totalUnreadCount: Int? = null,
    val mentionCount: Int? = null,
)

@Serializable
private data class WireReadReceipt(
    val channelId: String? = null,
    val seq: Long? = null,
    val unreadCount: Int = 0,
    val changed: Boolean = true,
)

/**
 * Parse `/api/unread/counts`. Returns null on contract mismatch so the caller
 * can collapse to `DataResult.Error`.
 */
fun parseChannelUnreadSnapshot(
    bodyText: String,
    server: ServerSummary,
): ChannelUnreadSnapshot? = runCatching {
    val decoded = unreadJson.decodeFromString(WireChannelUnreadCounts.serializer(), bodyText)
    val unreadCounts = decoded.channels.mapKeys { (key, _) -> ChannelId(key) }
    ChannelUnreadSnapshot(
        server = server.copy(unreadCount = unreadCounts.values.sum()),
        unreadCounts = unreadCounts,
    )
}.getOrNull()

/**
 * Parse `/api/servers/:id/unread/summary`.
 */
fun parseServerUnreadSummarySnapshot(
    bodyText: String,
    selectedServer: ServerSummary,
): ServerUnreadSummarySnapshot? = runCatching {
    val decoded =
        unreadJson.decodeFromString(WireServerUnreadSummaryResponse.serializer(), bodyText)
    val summaries = decoded.servers.map { it.toDomain() }
    val selectedSummary = summaries.firstOrNull { it.serverId == selectedServer.id }
    val totalUnread =
        decoded.totalUnreadCount
            ?: selectedSummary?.totalUnreadCount
            ?: summaries.sumOf { it.totalUnreadCount }
    val mentionCount =
        decoded.mentionCount ?: selectedSummary?.mentionCount ?: 0
    ServerUnreadSummarySnapshot(
        server = selectedServer.copy(unreadCount = selectedSummary?.totalUnreadCount ?: totalUnread),
        summaries = summaries,
        selectedServerSummary = selectedSummary,
        totalUnreadCount = totalUnread,
        mentionCount = mentionCount,
    )
}.getOrNull()

/**
 * Parse a read-receipt mutation response. `fallbackChannelId` / `fallbackSeq`
 * cover the inbox-read-all case where the server omits those fields.
 */
fun parseReadReceiptResult(
    bodyText: String,
    fallbackChannelId: ChannelId? = null,
    fallbackSeq: Long? = null,
): ReadReceiptResult? = runCatching {
    val decoded = unreadJson.decodeFromString(WireReadReceipt.serializer(), bodyText)
    ReadReceiptResult(
        channelId = decoded.channelId?.let(::ChannelId) ?: fallbackChannelId,
        seq = decoded.seq ?: fallbackSeq,
        unreadCount = decoded.unreadCount,
        changed = decoded.changed,
    )
}.getOrNull()

private fun WireServerUnreadSummary.toDomain(): ServerUnreadSummary =
    ServerUnreadSummary(
        serverId = ServerId(serverId),
        totalUnreadCount = totalUnreadCount,
        channelUnreadCount = channelUnreadCount,
        directMessageUnreadCount = directMessageUnreadCount,
        mentionCount = mentionCount,
        taskUnreadCount = taskUnreadCount,
        updatedAt = updatedAt,
    )
