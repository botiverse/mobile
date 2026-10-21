package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed parsers for unread/read-receipt routes.
 *
 * Server contracts (`packages/server/src/routes/channels.ts`):
 *  - GET  /api/channels/unread?summary=1       → [ChannelUnreadSummaryWireDto]
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
 * The query that selects the sidebar summary shape of `GET /api/channels/unread`. Every Mobile caller
 * asks for the summary; the bare `{[channelId]: number}` map is not parsed anywhere.
 */
internal val CHANNEL_UNREAD_SUMMARY_QUERY: List<Pair<String, String>> = listOf("summary" to "1")

/**
 * `readState` of one summary entry: botiverse/slock `packages/shared/src/inboxScopeReadFrontier.ts`
 * `InboxScopeReadFrontier` (`{kind:"absent"} | {kind:"corrupt"} | {kind:"present", readStateVersion,
 * maxReadSeq: string, latestActivity: {messageId, seq: string} | null}`). Carried for contract
 * completeness; Home reads its read cursors from the read-state path, not from here.
 */
@Serializable
data class ChannelUnreadSummaryReadStateWireDto(
    val kind: String,
    val readStateVersion: Long? = null,
    val maxReadSeq: String? = null,
    val latestActivity: ChannelUnreadSummaryLatestActivityWireDto? = null,
)

@Serializable
data class ChannelUnreadSummaryLatestActivityWireDto(
    val messageId: String,
    val seq: String,
)

/**
 * One value of `channels` — server `channelService.ts` `ChannelUnreadSummaryEntry`
 * (web `messageStore.ts` `UnreadSummaryWireEntry`). Keys cover channels, DMs and followed threads.
 * [parentChannelId] is set only on followed-thread rows with unread replies.
 */
@Serializable
data class ChannelUnreadSummaryEntryWireDto(
    val unreadCount: Int,
    val hasMention: Boolean,
    val hasAnyMention: Boolean,
    val readState: ChannelUnreadSummaryReadStateWireDto? = null,
    val hasNew: Boolean? = null,
    val parentChannelId: String? = null,
)

/** One value of `threadUnread` — server `channelService.ts` `ThreadUnreadByParent`. */
@Serializable
data class ThreadUnreadByParentWireDto(
    val count: Int,
    val hasMention: Boolean,
)

/**
 * `GET /api/channels/unread?summary=1` body (`routes/channels.ts` `/unread`):
 * `{ channels: Record<id, ChannelUnreadSummaryEntry>, threadUnread: ThreadUnreadByParent, stale? }`.
 * Both maps are required, so the bare count map is a contract mismatch, not an empty summary.
 */
@Serializable
data class ChannelUnreadSummaryWireDto(
    val channels: Map<String, ChannelUnreadSummaryEntryWireDto>,
    val threadUnread: Map<String, ThreadUnreadByParentWireDto>,
    /** Set when the server answered from its overload cache. */
    val stale: Boolean = false,
)

/** A followed thread with unread replies, folded onto [parentChannelId] for the Home row mark. */
@Serializable
data class HomeFollowedThreadUnread(
    val parentChannelId: ChannelId,
    val unreadCount: Int,
    val hasMention: Boolean = false,
)

/** Web `ParentThreadUnread`: followed threads under one channel with unread replies (threads, not replies). */
data class HomeParentThreadUnread(
    val count: Int,
    val hasMention: Boolean,
)

/** Domain projection of [ChannelUnreadSummaryWireDto]. */
data class ChannelUnreadSummary(
    /** Every summary entry's exact count, zero included (key presence = the server reported the scope). */
    val unreadCounts: Map<ChannelId, Int>,
    /** Scopes whose unread messages mention the user (`hasMention`). */
    val mentionedChannelIds: Set<ChannelId>,
    /** Followed threads with unread replies, keyed by thread channel id value. */
    val followedThreadUnread: Map<String, HomeFollowedThreadUnread>,
    /** The server's own fold of [followedThreadUnread] (`threadUnread`). */
    val threadUnreadByParent: Map<ChannelId, HomeParentThreadUnread>,
)

fun ChannelUnreadSummaryWireDto.toChannelUnreadSummary(): ChannelUnreadSummary {
    val unreadCounts = LinkedHashMap<ChannelId, Int>()
    val mentioned = LinkedHashSet<ChannelId>()
    val threads = LinkedHashMap<String, HomeFollowedThreadUnread>()
    for ((key, entry) in channels) {
        val id = key.trim().takeIf { it.isNotEmpty() }?.let(::ChannelId) ?: continue
        val count = entry.unreadCount.coerceAtLeast(0)
        unreadCounts[id] = count
        if (entry.hasMention) mentioned += id
        val parent = entry.parentChannelId?.trim()?.takeIf { it.isNotEmpty() }
        if (parent != null && count > 0) {
            threads[id.value] = HomeFollowedThreadUnread(ChannelId(parent), count, entry.hasMention)
        }
    }
    return ChannelUnreadSummary(
        unreadCounts = unreadCounts,
        mentionedChannelIds = mentioned,
        followedThreadUnread = threads,
        threadUnreadByParent =
            threadUnread
                .mapNotNull { (key, value) ->
                    key.trim().takeIf { it.isNotEmpty() }?.let { ChannelId(it) to HomeParentThreadUnread(value.count, value.hasMention) }
                }
                .toMap(),
    )
}

/**
 * Server `summarizeThreadUnreadByParent` / web `groupThreadUnreadByParent`: per parent channel, how many
 * followed threads have unread replies and whether one of them has an unread mention.
 */
fun Map<String, HomeFollowedThreadUnread>.foldThreadUnreadByParent(): Map<ChannelId, HomeParentThreadUnread> {
    if (isEmpty()) return emptyMap()
    val byParent = LinkedHashMap<ChannelId, HomeParentThreadUnread>()
    for (thread in values) {
        if (thread.unreadCount <= 0) continue
        val current = byParent[thread.parentChannelId]
        byParent[thread.parentChannelId] =
            HomeParentThreadUnread(
                count = (current?.count ?: 0) + 1,
                hasMention = current?.hasMention == true || thread.hasMention,
            )
    }
    return byParent
}

/** Parse `/api/channels/unread?summary=1`; null on contract mismatch (including the bare count map). */
fun parseChannelUnreadSummary(bodyText: String): ChannelUnreadSummary? = runCatching {
    unreadJson.decodeFromString(ChannelUnreadSummaryWireDto.serializer(), bodyText).toChannelUnreadSummary()
}.getOrNull()

/**
 * Parse `/api/channels/unread?summary=1` into the per-scope counts. Returns null on contract mismatch so
 * the caller can collapse to `DataResult.Error`.
 */
fun parseChannelUnreadSnapshot(
    bodyText: String,
    server: ServerSummary,
): ChannelUnreadSnapshot? {
    val summary = parseChannelUnreadSummary(bodyText) ?: return null
    return ChannelUnreadSnapshot(
        server = server.copy(unreadCount = summary.unreadCounts.values.sum()),
        unreadCounts = summary.unreadCounts,
    )
}

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
