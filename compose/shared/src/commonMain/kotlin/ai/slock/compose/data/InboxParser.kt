package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.TaskStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Strict parser for `GET /api/channels/inbox`.
 *
 * Wire contract is the discriminated union `InboxItem` defined in
 * `packages/server/src/services/channelService.ts` (see the `export type
 * InboxItem` block). Every field name here matches that contract exactly —
 * no snake_case aliases, no envelope sniffing, no `firstObject("metadata",
 * "meta", "payload")` style guessing. Wrong field names blow up at decode
 * time, which is the whole point.
 */
private val inboxJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireInboxItem(
    // discriminator (optional on thread-list endpoints; defaulted by callers)
    val kind: String? = null,
    // channel | dm
    val channelId: String? = null,
    val channelName: String? = null,
    val channelType: String? = null,
    val lastMessageId: String? = null,
    val firstUnreadMessageId: String? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderType: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageSenderName: String? = null,
    // thread
    val threadChannelId: String? = null,
    val parentMessageId: String? = null,
    val parentChannelId: String? = null,
    val parentChannelName: String? = null,
    val parentChannelType: String? = null,
    val parentMessagePreview: String? = null,
    val parentMessageSenderType: String? = null,
    val parentMessageSenderId: String? = null,
    val latestActivityPreview: String? = null,
    val latestActivitySenderType: String? = null,
    val latestActivitySenderId: String? = null,
    val latestActivitySenderName: String? = null,
    val latestActivityMessageId: String? = null,
    val lastActivityAt: String? = null,
    val lastReplyAt: String? = null,
    val replyCount: Int = 0,
    val participantIds: List<String> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskClaimedByName: String? = null,
    // common
    val unreadCount: Int = 0,
    val hasMention: Boolean = false,
)

@Serializable
private data class WireInboxResponse(
    val items: List<WireInboxItem> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
    val totalUnreadCount: Int? = null,
)

@Serializable
private data class WireThreadListResponse(
    val threads: List<WireInboxItem> = emptyList(),
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
)

data class SharedThreadListItems(
    val items: List<InboxItem>,
    val totalCount: Int,
    val hasMore: Boolean,
    val nextOffset: Int?,
)

/**
 * Strict parser for `GET /api/channels/threads/followed`.
 *
 * The endpoint wraps followed thread rows under `threads`. Do not add channel
 * thread record/map fallback here; `GET /api/channels/:id/threads` has its own
 * endpoint parser below.
 */
fun parseFollowedThreadListResponse(bodyText: String): SharedThreadListItems? = runCatching {
    val root = inboxJson.parseToJsonElement(bodyText) as? JsonObject ?: return@runCatching null
    if ("threads" !in root) return@runCatching null
    val decoded = inboxJson.decodeFromJsonElement(WireThreadListResponse.serializer(), root)
    val items = decoded.threads
        .map { it.copy(kind = "thread") }
        .mapNotNull { it.toThreadDomain() }
        .distinctBy { it.key }
    SharedThreadListItems(
        items = items,
        totalCount = decoded.totalCount ?: items.size,
        hasMore = decoded.hasMore,
        nextOffset = decoded.nextOffset,
    )
}.getOrNull()

/**
 * Strict parser for `GET /api/channels/:id/threads`.
 *
 * The endpoint returns a `parentMessageId -> summary` record. Do not add
 * followed-thread `{threads:[...]}` fallback here; that is a different route.
 */
fun parseChannelThreadSummaryRecord(bodyText: String): SharedThreadListItems? = runCatching {
    val root = inboxJson.parseToJsonElement(bodyText) as? JsonObject ?: return@runCatching null
    if ("threads" in root) return@runCatching null
    if (root.values.any { it !is JsonObject }) return@runCatching null
    val items = root.entries
        .asSequence()
        .mapNotNull { (parentMessageId, value) ->
            val record = value as JsonObject
            inboxJson.decodeFromJsonElement(WireInboxItem.serializer(), record)
                .copy(kind = "thread", parentMessageId = parentMessageId)
        }
        .mapNotNull { it.toThreadDomain() }
        .distinctBy { it.key }
        .toList()
    SharedThreadListItems(
        items = items,
        totalCount = items.size,
        hasMore = false,
        nextOffset = null,
    )
}.getOrNull()

data class SharedInboxSnapshot(
    val items: List<InboxItem>,
    val hasMore: Boolean,
    val totalCount: Int,
    val totalUnreadCount: Int,
)

fun parseSharedInboxSnapshot(bodyText: String): SharedInboxSnapshot? = runCatching {
    val decoded = inboxJson.decodeFromString(WireInboxResponse.serializer(), bodyText)
    val items = decoded.items.mapNotNull { it.toDomain() }.distinctBy { it.key }
    val derivedCount = items.size
    val derivedUnread = items.sumOf { it.unreadCount }
    SharedInboxSnapshot(
        items = items,
        hasMore = decoded.hasMore,
        totalCount = maxOf(decoded.totalCount ?: derivedCount, derivedCount),
        totalUnreadCount = maxOf(decoded.totalUnreadCount ?: derivedUnread, derivedUnread),
    )
}.getOrNull()

private fun WireInboxItem.toDomain(): InboxItem? = when (kind) {
    "channel", "dm" -> toChannelOrDmDomain()
    "thread" -> toThreadDomain()
    else -> null
}

private fun WireInboxItem.toChannelOrDmDomain(): InboxItem? {
    val id = channelId ?: return null
    val name = channelName ?: id
    val itemKind = if (kind == "dm") InboxItemKind.DirectMessage else InboxItemKind.Channel
    return InboxItem(
        key = if (itemKind == InboxItemKind.DirectMessage) "dm:$id" else "channel:$id",
        kind = itemKind,
        channelId = ChannelId(id),
        channelName = name,
        unreadCount = unreadCount,
        hasMention = hasMention,
        firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
        lastMessageId = lastMessageId?.let(::MessageId),
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        lastMessageSenderId = lastMessageSenderId,
        lastMessageSenderKind = lastMessageSenderType.toSenderKind(),
    )
}

private fun WireInboxItem.toThreadDomain(): InboxItem? {
    val threadId = threadChannelId ?: return null
    return InboxItem(
        key = "thread:$threadId",
        kind = InboxItemKind.Thread,
        channelId = ChannelId(threadId),
        channelName = parentChannelName ?: threadId,
        unreadCount = unreadCount,
        hasMention = hasMention,
        firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
        threadChannelId = ChannelId(threadId),
        parentChannelId = parentChannelId?.let(::ChannelId),
        parentChannelName = parentChannelName,
        parentChannelKind = parentChannelType?.toConversationKind(),
        parentMessageId = parentMessageId?.let(::MessageId),
        parentMessagePreview = parentMessagePreview,
        parentMessageSenderId = parentMessageSenderId,
        parentMessageSenderKind = parentMessageSenderType.toSenderKind(),
        latestActivityMessageId = latestActivityMessageId?.let(::MessageId),
        latestActivityPreview = latestActivityPreview,
        latestActivityAt = lastActivityAt ?: lastReplyAt,
        latestActivitySenderName = latestActivitySenderName,
        latestActivitySenderId = latestActivitySenderId,
        latestActivitySenderKind = latestActivitySenderType.toSenderKind(),
        replyCount = replyCount,
        participantIds = participantIds.mapNotNull { id -> id.takeIf { it.isNotBlank() }?.let(::MemberId) },
        taskNumber = taskNumber,
        taskStatus = TaskStatus.fromWireValue(taskStatus),
        taskClaimedByName = taskClaimedByName,
    )
}

private fun String.toConversationKind(): ConversationKind? = when (trim().lowercase()) {
    "dm", "direct", "direct_message", "direct-message", "directmessage" -> ConversationKind.DirectMessage
    "joint" -> ConversationKind.Joint
    "channel", "private", "public", "group" -> ConversationKind.Channel
    else -> null
}

private fun String?.toSenderKind(): MemberKind? = when {
    equals("agent", ignoreCase = true) -> MemberKind.Agent
    equals("user", ignoreCase = true) || equals("human", ignoreCase = true) -> MemberKind.Human
    else -> null
}

// -----------------------------------------------------------------------------
// Saved messages — GET /api/saved
//
// Wire contract: `SavedEntry` in
// `packages/server/src/services/savedService.ts`. Response shape is
// `{ saved: SavedEntry[], hasMore: boolean }`.
// -----------------------------------------------------------------------------

@Serializable
private data class WireSavedEntry(
    val messageId: String,
    val channelId: String,
    val channelName: String,
    val channelType: String,
    val content: String,
    val senderType: String,
    val senderId: String,
    val senderName: String? = null,
    val createdAt: String,
    val savedAt: String,
    val parentChannelId: String? = null,
    val parentChannelName: String? = null,
    val parentChannelType: String? = null,
    val parentMessageId: String? = null,
)

@Serializable
private data class WireSavedResponse(
    val saved: List<WireSavedEntry> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
)

data class SharedSavedItems(
    val items: List<InboxItem>,
    val hasMore: Boolean,
    val totalCount: Int,
)

fun parseSharedSavedItems(bodyText: String): SharedSavedItems? = runCatching {
    val decoded = inboxJson.decodeFromString(WireSavedResponse.serializer(), bodyText)
    val items = decoded.saved.map { it.toInboxItem() }.distinctBy { it.key }
    SharedSavedItems(
        items = items,
        hasMore = decoded.hasMore,
        totalCount = decoded.totalCount ?: items.size,
    )
}.getOrNull()

@Serializable
private data class WireSavedCheckResponse(
    val savedIds: List<String> = emptyList()
)

fun parseSharedSavedMessageStatus(bodyText: String): SavedMessageStatusSnapshot? = runCatching {
    val decoded = inboxJson.decodeFromString(WireSavedCheckResponse.serializer(), bodyText)
    SavedMessageStatusSnapshot(
        savedIds =
            decoded.savedIds
                .filter { it.isNotBlank() }
                .map(::MessageId)
                .toSet()
    )
}.getOrNull()

private fun WireSavedEntry.toInboxItem(): InboxItem {
    val itemKind = when (channelType) {
        "dm" -> InboxItemKind.DirectMessage
        "thread" -> InboxItemKind.Thread
        else -> InboxItemKind.Channel
    }
    val key = "saved:$messageId"
    return InboxItem(
        key = key,
        kind = itemKind,
        channelId = ChannelId(channelId),
        channelName = channelName,
        lastMessageId = MessageId(messageId),
        lastMessagePreview = content,
        lastMessageAt = createdAt,
        lastMessageSenderName = senderName,
        lastMessageSenderId = senderId,
        lastMessageSenderKind = senderType.toSenderKind(),
        threadChannelId = if (itemKind == InboxItemKind.Thread) ChannelId(channelId) else null,
        parentChannelId = parentChannelId?.let(::ChannelId),
        parentChannelName = parentChannelName,
        parentChannelKind = parentChannelType?.toConversationKind(),
        parentMessageId = parentMessageId?.let(::MessageId),
    )
}

// -----------------------------------------------------------------------------
// Message search — GET /api/messages/search
//
// Wire contract: `MessageSearchResult` in
// `packages/server/src/services/searchService.ts`. Response shape is
// `{ results: MessageSearchResult[], hasMore: boolean }`.
// -----------------------------------------------------------------------------

@Serializable
private data class WireSearchResult(
    val id: String,
    val seq: Long? = null,
    val channelId: String,
    val threadId: String? = null,
    val parentMessageId: String? = null,
    val parentMessageContent: String? = null,
    val parentChannelId: String,
    val parentChannelName: String,
    val parentChannelType: String,
    val parentChannelArchivedAt: String? = null,
    val senderId: String,
    val senderType: String,
    val senderName: String,
    val channelName: String,
    val channelType: String,
    val channelArchivedAt: String? = null,
    val content: String,
    val snippet: String,
    val createdAt: String,
)

@Serializable
private data class WireSearchResponse(
    val results: List<WireSearchResult> = emptyList(),
    val hasMore: Boolean = false,
)

data class SharedSearchItems(
    val items: List<InboxItem>,
    val hasMore: Boolean,
)

fun parseSharedSearchItems(bodyText: String): SharedSearchItems? = runCatching {
    val decoded = inboxJson.decodeFromString(WireSearchResponse.serializer(), bodyText)
    val items = decoded.results.map { it.toInboxItem() }.distinctBy { it.key }
    SharedSearchItems(items = items, hasMore = decoded.hasMore)
}.getOrNull()

private fun WireSearchResult.toInboxItem(): InboxItem {
    val itemKind = when (channelType) {
        "dm" -> InboxItemKind.DirectMessage
        "thread" -> InboxItemKind.Thread
        else -> InboxItemKind.Channel
    }
    val key = when (itemKind) {
        InboxItemKind.DirectMessage -> "dm:$channelId:$id"
        InboxItemKind.Thread -> "thread:$channelId:$id"
        else -> "channel:$channelId:$id"
    }
    return InboxItem(
        key = key,
        kind = itemKind,
        channelId = ChannelId(channelId),
        channelName = channelName,
        lastMessageId = MessageId(id),
        lastMessagePreview = snippet.ifBlank { content },
        lastMessageAt = createdAt,
        lastMessageSenderName = senderName,
        lastMessageSenderId = senderId,
        lastMessageSenderKind = senderType.toSenderKind(),
        threadChannelId = if (itemKind == InboxItemKind.Thread) ChannelId(channelId) else null,
        parentChannelId = ChannelId(parentChannelId),
        parentChannelName = parentChannelName,
        parentChannelKind = parentChannelType.toConversationKind(),
        parentMessageId = parentMessageId?.let(::MessageId),
        parentMessagePreview = parentMessageContent,
    )
}
