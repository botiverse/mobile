package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Typed parsers for unread/read-receipt routes.
 *
 * Server contracts (`packages/server/src/routes/channels.ts`):
 *  - GET  /api/channels/unread                 → `{[channelId]: number}`
 *  - POST /api/channels/:id/read               → `{ ok }`
 *  - POST /api/channels/:id/read-all           → `{ ok, seq }`
 *  - POST /api/channels/:id/unread             → `{ ok, unreadCount }`
 *  - POST /api/channels/inbox/read-all         → `{ ok, markedCount }`
 *
 * Field names are exact camelCase. No snake_case fallbacks, no nested
 * `data` wrapper sniffing.
 */
private val unreadJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireReadReceipt(
    val channelId: String? = null,
    val seq: Long? = null,
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
    val unreadCount: Int = 0,
    val changed: Boolean = true,
    val ok: Boolean = true,
    val markedCount: Int = 0,
    val scopes: List<WireReadStateAckScope> = emptyList(),
)

@Serializable
private data class WireReadStateAckScope(
    val scopeId: String? = null,
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
)

/**
 * Parse `/api/channels/unread`. Returns null on contract mismatch so the caller
 * can collapse to `DataResult.Error`.
 */
fun parseChannelUnreadSnapshot(
    bodyText: String,
    server: ServerSummary,
): ChannelUnreadSnapshot? = runCatching {
    val unreadCounts = unreadJson
        .decodeFromString(MapSerializer(String.serializer(), Int.serializer()), bodyText)
        .mapKeys { (key, _) -> ChannelId(key) }
    ChannelUnreadSnapshot(
        server = server.copy(unreadCount = unreadCounts.values.sum()),
        unreadCounts = unreadCounts,
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
    if (decoded.ok) {
        ReadReceiptResult(
            channelId = decoded.channelId?.let(::ChannelId) ?: fallbackChannelId,
            seq = decoded.seq ?: fallbackSeq,
            readStateVersion = decoded.readStateVersion,
            maxReadSeq = decoded.maxReadSeq ?: decoded.seq,
            scopes =
                decoded.scopes.mapNotNull { scope ->
                    scope.scopeId?.takeIf { it.isNotBlank() }?.let { channelId ->
                        ReadStateV1AckScope(ChannelId(channelId), scope.readStateVersion, scope.maxReadSeq)
                    }
                },
            unreadCount = decoded.unreadCount,
            changed = decoded.changed,
        )
    } else {
        null
    }
}.getOrNull()
