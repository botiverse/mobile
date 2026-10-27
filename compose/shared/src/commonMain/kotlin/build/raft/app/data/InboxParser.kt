package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.parseConversationKindOrNull
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.network.sync.ReplyPreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val firstMentionMessageId: String? = null,
    // Display content frontier and the storage-space Done frontier (decimal
    // strings, channelService.ts InboxItem). `doneFrontierSeq` presence is read
    // from the raw object: absent (older server) differs from present-null.
    val latestActivitySeq: String? = null,
    val doneFrontierSeq: String? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderType: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageSenderName: String? = null,
    // The avatar of the sender this row shows (channel/dm: the last message's sender; thread: the
    // latest replier), so a joint channel's sender from another server still has a face when this
    // server's member directory doesn't know them. Pending server PR (botiverse/slock, Activity row
    // `senderAvatarUrl`); absent from older servers.
    val senderAvatarUrl: String? = null,
    // thread
    val threadChannelId: String? = null,
    val parentMessageId: String? = null,
    val parentChannelId: String? = null,
    val parentChannelName: String? = null,
    val parentChannelType: String? = null,
    val parentMessagePreview: String? = null,
    val parentMessageSenderType: String? = null,
    val parentMessageSenderId: String? = null,
    // Followed-thread rows (channelService.ts FollowedThread): when the parent message was posted.
    val parentMessageCreatedAt: String? = null,
    val latestActivityPreview: String? = null,
    val latestActivitySenderType: String? = null,
    val latestActivitySenderId: String? = null,
    val latestActivitySenderName: String? = null,
    val latestActivityMessageId: String? = null,
    // RFC 077 (`withLatestDelegatedBy`, channels.ts GET /inbox): the assistant that sent the
    // row's latest message (thread: `latestActivityMessageId`, else `lastMessageId`).
    val latestDelegatedBy: WireDelegatedBy? = null,
    // RFC 077 PR 2b (messageService.ts `withLatestMessageFacts`): the row's latest message
    // (thread: `latestActivityMessageId`, else `lastMessageId`) / a thread's parent was retracted.
    val latestRetracted: Boolean = false,
    val parentRetracted: Boolean = false,
    val lastActivityAt: String? = null,
    val lastReplyAt: String? = null,
    val replyCount: Int = 0,
    val latestReplies: List<WireThreadReplyPreview> = emptyList(),
    val participantIds: List<String> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskClaimedByName: String? = null,
    // task v2 conversation rows (kind "task", channelService.ts TaskConversationInboxItem)
    val taskId: String? = null,
    val taskTitle: String? = null,
    // thread follow state; only an explicit false means Unfollowed
    val isFollowing: Boolean? = null,
    val unfollowedAt: String? = null,
    // common
    val unreadCount: Int = 0,
    val hasMention: Boolean = false,
    // SSOT per-scope read state (#632): InboxScopeReadFrontier in
    // packages/shared/src/inboxScopeReadFrontier.ts. Kept as raw JSON and read
    // per row so a malformed frontier only drops that row's marker, never the
    // whole response.
    val readState: JsonElement? = null,
)

/**
 * `InboxScopeReadFrontier` (`{kind:"absent"} | {kind:"corrupt"} |
 * {kind:"present", readStateVersion, maxReadSeq, latestActivity}`). Only the
 * present frontier's `latestActivity` pair is consumed: the same-source pair or
 * nothing. Fails closed to null on absent/corrupt/half pairs and on any
 * malformed shape (non-object, numeric seq, string latestActivity, ...).
 */
private fun JsonElement.latestActivitySeqOrNull(): String? {
    val frontier = this as? JsonObject ?: return null
    if (frontier.stringFieldOrNull("kind") != "present") return null
    val pair = frontier["latestActivity"] as? JsonObject ?: return null
    if (pair.stringFieldOrNull("messageId").isNullOrBlank()) return null
    return pair.stringFieldOrNull("seq")?.takeIf(::isCanonicalActivitySeq)
}

/** A JSON string value only: numbers, booleans, null and containers yield null. */
private fun JsonObject.stringFieldOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

@Serializable
private data class WireThreadReplyPreview(
    val messageId: String = "",
    val seq: Long = 0L,
    val preview: String = "",
    val senderId: String = "",
    val senderType: String = "user",
    val senderName: String = "",
    val senderDisplayName: String? = null,
    val senderAvatarUrl: String? = null,
    val createdAt: String = "",
)

@Serializable
private data class WireInboxResponse(
    val items: List<WireInboxItem> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
    val totalUnreadCount: Int? = null,
    val activeUnreadCount: Int? = null,
    // Workspace Artifacts (activityObjectsContract.ts ObjectInboxFields): absent with every object app off.
    val objectUnreadCount: Int? = null,
    val objectMentionUnreadCount: Int? = null,
    val objectCount: Int? = null,
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
fun parseFollowedThreadListResponse(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedThreadListItems? = runCatching {
    val root = inboxJson.parseToJsonElement(bodyText) as? JsonObject ?: return@runCatching null
    if ("threads" !in root) return@runCatching null
    val decoded = inboxJson.decodeFromJsonElement(WireThreadListResponse.serializer(), root)
    val items = decoded.threads
        .mapNotNull { wire ->
            // Task v2: a followed task conversation keeps its own arm (no parent message).
            if (wire.kind == "task") {
                wire.toTaskConversationDomain(diagnostics)
            } else {
                wire.copy(kind = "thread").toThreadDomain(diagnostics)
            }
        }
        .map { item -> item.withActivityFollowStateProjection() }
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
fun parseChannelThreadSummaryRecord(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedThreadListItems? = runCatching {
    val root = inboxJson.parseToJsonElement(bodyText) as? JsonObject ?: return@runCatching null
    parseChannelThreadSummaryRecordElement(root, diagnostics)
}.getOrNull()

/**
 * Parses the same parent-message keyed summary record when it is embedded in another canonical
 * response, such as the additive summaries carried by a messages page. Keeping one decoder avoids
 * a second Mobile-only interpretation of the server's `/channels/:id/threads` contract.
 */
internal fun parseChannelThreadSummaryRecordElement(
    root: JsonObject,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedThreadListItems? = runCatching {
    if ("threads" in root) return@runCatching null
    if (root.values.any { it !is JsonObject }) return@runCatching null
    val projected = mutableListOf<InboxItem>()
    for ((parentMessageId, value) in root) {
        val cleanParentMessageId = parentMessageId.trim().takeIf { it.isNotBlank() } ?: return@runCatching null
        val record = value as? JsonObject ?: return@runCatching null
        val decoded =
            inboxJson.decodeFromJsonElement(WireInboxItem.serializer(), record)
                .copy(kind = "thread", parentMessageId = cleanParentMessageId)
        // A summary response is one authority record. A malformed child cannot be silently
        // omitted and reinterpreted as an authoritative empty/partial snapshot.
        projected += decoded.toThreadDomain(diagnostics)?.withActivityFollowStateProjection()
            ?: return@runCatching null
    }
    val items = projected.distinctBy { it.key }
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
    /** Number of server rows consumed for this page before client projection. */
    val rawItemCount: Int = items.size,
    /** Wire `activeUnreadCount` (web rail dot); null on servers that omit it. */
    val activeUnreadCount: Int? = null,
    /** The object rows' counts (workspace Artifacts); null when the server sent none. */
    val objectCounts: ActivityObjectCounts? = null,
)

fun parseSharedInboxSnapshot(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedInboxSnapshot? = runCatching {
    val root = inboxJson.parseToJsonElement(bodyText) as? JsonObject ?: return@runCatching null
    val decoded = inboxJson.decodeFromJsonElement(WireInboxResponse.serializer(), root)
    val rawItemObjects = root["items"] as? JsonArray
    val rawItems =
        decoded.items
            .mapIndexedNotNull { index, wire ->
                val raw = rawItemObjects?.getOrNull(index) as? JsonObject
                // Workspace Artifacts: an object row is read by its own contract.
                if (wire.kind == "object") return@mapIndexedNotNull raw?.let(::parseActivityObjectInboxItem)
                val doneFrontierPresent = raw?.containsKey("doneFrontierSeq") == true
                wire.toDomain(diagnostics)?.copy(doneFrontierSeqPresent = doneFrontierPresent)
            }
            .distinctBy { it.key }
    val items = rawItems.map(InboxItem::withActivityFollowStateProjection)
    val conversationItems = items.filterNot { it.kind == InboxItemKind.Object }
    // The server's aggregates are authoritative (it already zeroes unfollowed
    // rows); derive them from the page only when an older server omits them.
    SharedInboxSnapshot(
        items = items,
        hasMore = decoded.hasMore,
        totalCount = decoded.totalCount ?: conversationItems.size,
        totalUnreadCount = decoded.totalUnreadCount ?: conversationItems.sumOf { it.unreadCount },
        rawItemCount = decoded.items.size,
        activeUnreadCount = decoded.activeUnreadCount,
        objectCounts =
            if (decoded.objectUnreadCount == null && decoded.objectCount == null && decoded.objectMentionUnreadCount == null) {
                null
            } else {
                ActivityObjectCounts(
                    objectUnreadCount = decoded.objectUnreadCount?.coerceAtLeast(0) ?: 0,
                    objectMentionUnreadCount = decoded.objectMentionUnreadCount?.coerceAtLeast(0) ?: 0,
                    objectCount = decoded.objectCount?.coerceAtLeast(0) ?: 0,
                )
            },
    )
}.getOrNull()

private fun WireInboxItem.toDomain(diagnostics: (String) -> Unit): InboxItem? = when (kind) {
    "channel", "dm" -> toChannelOrDmDomain(diagnostics)
    "thread" -> toThreadDomain(diagnostics)
    "task" -> toTaskConversationDomain(diagnostics)
    else -> null
}

/**
 * Task v2 (slock `TaskConversationInboxItem`): the thread arm's fields with no parent message,
 * folded into a thread-shaped row like web `normalizeTaskConversationRow` so the thread-keyed
 * paths (key, follow, Done by `threadChannelId`) apply unchanged. The row title is the task
 * address `#channel:#7 title` (web `formatTaskConversationTitle`).
 */
private fun WireInboxItem.toTaskConversationDomain(diagnostics: (String) -> Unit): InboxItem? {
    val base = copy(kind = "thread", parentMessageId = null).toThreadDomain(diagnostics) ?: return null
    val title = taskTitle?.trim()?.takeIf { it.isNotBlank() } ?: parentMessagePreview?.trim()?.takeIf { it.isNotBlank() }
    val displayTitle =
        taskNumber?.let { number ->
            formatTaskConversationTitle(
                ref = formatTaskConversationRef(number, parentChannelName, base.parentChannelKind),
                title = title,
            )
        } ?: title
    return base.copy(
        parentMessageId = null,
        parentMessagePreview = displayTitle,
        parentMessageSenderId = null,
        parentMessageSenderKind = null,
        taskConversation =
            InboxTaskConversation(
                taskId = taskId?.trim()?.takeIf { it.isNotBlank() }?.let(::TaskId),
                taskTitle = title,
            ),
    )
}

/** The row's `senderAvatarUrl`, as ThreadPage takes a reply's `senderAvatarUrl`: trimmed, blank → none. */
private fun WireInboxItem.senderAvatarWireValueOrNull(): String? =
    senderAvatarUrl?.trim()?.takeIf { it.isNotBlank() }

private fun WireInboxItem.toChannelOrDmDomain(diagnostics: (String) -> Unit): InboxItem? {
    val id = channelId ?: return null
    val name = channelName ?: id
    val itemKind =
        when (parseConversationKindOrNull(kind)) {
            ConversationKind.DirectMessage -> InboxItemKind.DirectMessage
            ConversationKind.Channel -> InboxItemKind.Channel
            else -> return null
        }
    return InboxItem(
        key = if (itemKind == InboxItemKind.DirectMessage) "dm:$id" else "channel:$id",
        kind = itemKind,
        channelId = ChannelId(id),
        channelName = name,
        channelKind =
            if (itemKind == InboxItemKind.DirectMessage) {
                ConversationKind.DirectMessage
            } else {
                parseConversationKindOrNull(channelType)
            },
        channelType = channelType?.takeIf { it.isNotBlank() },
        unreadCount = unreadCount,
        hasMention = hasMention,
        firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
        lastMessageId = lastMessageId?.let(::MessageId),
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        lastMessageSenderId = lastMessageSenderId,
        lastMessageSenderAvatarWireValue = senderAvatarWireValueOrNull(),
        lastMessageSenderKind =
            lastMessageSenderType.toSenderKind(
                source = "inbox_last_message_sender",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.InboxActor,
            ),
        readStateLatestActivitySeq = readState?.latestActivitySeqOrNull(),
        latestActivitySeq = latestActivitySeq?.takeIf(::isCanonicalActivitySeq),
        doneFrontierSeq = doneFrontierSeq,
        firstMentionMessageId = firstMentionMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        latestDelegatedBy = latestDelegatedBy.toMessageDelegatedBy(),
        latestRetracted = latestRetracted,
    )
}

private fun WireInboxItem.toThreadDomain(diagnostics: (String) -> Unit): InboxItem? {
    val threadId = threadChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
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
        parentChannelKind = parseConversationKindOrNull(parentChannelType),
        parentChannelType = parentChannelType?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId?.let(::MessageId),
        parentMessagePreview = parentMessagePreview,
        parentMessageSenderId = parentMessageSenderId,
        parentMessageCreatedAt = parentMessageCreatedAt?.takeIf { it.isNotBlank() },
        parentMessageSenderKind =
            parentMessageSenderType.toSenderKind(
                source = "inbox_parent_message_sender",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.InboxActor,
            ),
        latestActivityMessageId = latestActivityMessageId?.let(::MessageId),
        latestActivityPreview = latestActivityPreview,
        latestActivityAt = lastActivityAt ?: lastReplyAt,
        latestActivitySenderName = latestActivitySenderName,
        latestActivitySenderId = latestActivitySenderId,
        latestActivitySenderAvatarWireValue = senderAvatarWireValueOrNull(),
        latestDelegatedBy = latestDelegatedBy.toMessageDelegatedBy(),
        latestRetracted = latestRetracted,
        parentRetracted = parentRetracted,
        latestActivitySenderKind =
            latestActivitySenderType.toSenderKind(
                source = "inbox_latest_activity_sender",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.InboxActor,
            ),
        replyCount = replyCount,
        latestReplies = latestReplies.mapNotNull(WireThreadReplyPreview::toDomain),
        participantIds = participantIds.mapNotNull { id -> id.takeIf { it.isNotBlank() }?.let(::MemberId) },
        taskNumber = taskNumber,
        taskStatus =
            taskStatus?.takeIf { it.isNotBlank() }
                ?.strictTaskStatusOrNull("inbox_task", diagnostics),
        taskClaimedByName = taskClaimedByName,
        isFollowing = isFollowing,
        readStateLatestActivitySeq = readState?.latestActivitySeqOrNull(),
        latestActivitySeq = latestActivitySeq?.takeIf(::isCanonicalActivitySeq),
        doneFrontierSeq = doneFrontierSeq,
        firstMentionMessageId = firstMentionMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        unfollowedAt = unfollowedAt?.takeIf { it.isNotBlank() },
    )
}

private fun WireThreadReplyPreview.toDomain(): ReplyPreview? {
    val cleanMessageId = messageId.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanSenderType = senderType.trim().lowercase()
    if (cleanSenderType !in setOf("user", "agent", "system")) return null
    return ReplyPreview(
        messageId = cleanMessageId,
        seq = seq,
        content = preview,
        senderId = senderId.trim(),
        senderType = cleanSenderType,
        senderName = senderName.trim(),
        senderAvatarUrl = senderAvatarUrl?.trim()?.takeIf { it.isNotBlank() },
        createdAt = createdAt.trim(),
        senderDisplayName = senderDisplayName?.trim()?.takeIf { it.isNotBlank() },
    )
}

private fun String?.toSenderKind(
    source: String,
    diagnostics: (String) -> Unit,
    contract: MemberKindWireContract,
): MemberKind? =
    takeIf { !it.isNullOrBlank() }
        ?.strictMemberKindOrNull(
            source = source,
            diagnostics = diagnostics,
            contract = contract,
        )

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
    // GET /channels/saved reports the true saved count as `total` (page-filtered)
    // and `globalTotal` (unfiltered); `totalCount` never existed on this route and
    // is kept only as a legacy fallback.
    val total: Int? = null,
    val globalTotal: Int? = null,
    val totalCount: Int? = null,
)

data class SharedSavedItems(
    val items: List<InboxItem>,
    val hasMore: Boolean,
    val totalCount: Int,
)

fun parseSharedSavedItems(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedSavedItems? = runCatching {
    val decoded = inboxJson.decodeFromString(WireSavedResponse.serializer(), bodyText)
    val items = decoded.saved.mapNotNull { it.toInboxItem(diagnostics) }.distinctBy { it.key }
    SharedSavedItems(
        items = items,
        hasMore = decoded.hasMore,
        totalCount = decoded.globalTotal ?: decoded.total ?: decoded.totalCount ?: items.size,
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

private fun WireSavedEntry.toInboxItem(diagnostics: (String) -> Unit): InboxItem? {
    val conversationKind = parseConversationKindOrNull(channelType)
    val itemKind = conversationKind.toInboxItemKind() ?: return null
    val key = "saved:$messageId"
    return InboxItem(
        key = key,
        kind = itemKind,
        channelId = ChannelId(channelId),
        channelName = channelName,
        channelKind = conversationKind,
        channelType = channelType.takeIf { it.isNotBlank() },
        lastMessageId = MessageId(messageId),
        // The saved message itself is this row's open anchor. Thread rows must
        // carry it in latestActivityMessageId: resolveInboxAnchorMessageId
        // deliberately never falls back to lastMessageId for threads (task #231
        // — without this, a saved thread message opened unanchored at the
        // bottom instead of focusing the saved message like Activity rows do).
        latestActivityMessageId = MessageId(messageId),
        lastMessagePreview = content,
        lastMessageAt = createdAt,
        lastMessageSenderName = senderName,
        lastMessageSenderId = senderId,
        lastMessageSenderKind =
            senderType.toSenderKind(
                source = "saved_message_sender",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.MessageSender,
            ),
        threadChannelId = if (itemKind == InboxItemKind.Thread) ChannelId(channelId) else null,
        parentChannelId = parentChannelId?.let(::ChannelId),
        parentChannelName = parentChannelName,
        parentChannelKind = parseConversationKindOrNull(parentChannelType),
        parentChannelType = parentChannelType?.takeIf { it.isNotBlank() },
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
    /** RFC 077 `searchService.ts` `MessageSearchResult.delegatedBy`. */
    val delegatedBy: WireDelegatedBy? = null,
)

/**
 * `GET /messages/search` page (botiverse/slock searchService.ts `MessageSearchPage` plus the
 * route's `historyLimited`). `nextCursor` and `incomplete` come only from the recent-sort text
 * walk: `nextCursor` is set when `hasMore` or `incomplete`, and continues below this page.
 */
@Serializable
private data class WireSearchResponse(
    val results: List<WireSearchResult> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val incomplete: Boolean = false,
)

data class SharedSearchItems(
    val items: List<InboxItem>,
    /**
     * More results can be loaded: the server's `hasMore`, or a walk that stopped at its scan
     * budget (`incomplete`) with a cursor to continue from (web `hasMore || searchIncomplete`).
     */
    val hasMore: Boolean,
    /** Rows the server sent, before dropping unknown kinds or duplicates: what an offset advances by. */
    val rawResultCount: Int = items.size,
    val nextCursor: String? = null,
)

fun parseSharedSearchItems(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedSearchItems? = runCatching {
    val decoded = inboxJson.decodeFromString(WireSearchResponse.serializer(), bodyText)
    val items = decoded.results.mapNotNull { it.toInboxItem(diagnostics) }.distinctBy { it.key }
    val nextCursor = decoded.nextCursor?.takeIf { it.isNotEmpty() }
    SharedSearchItems(
        items = items,
        hasMore = decoded.hasMore || (decoded.incomplete && nextCursor != null),
        rawResultCount = decoded.results.size,
        nextCursor = nextCursor,
    )
}.getOrNull()

private fun WireSearchResult.toInboxItem(diagnostics: (String) -> Unit): InboxItem? {
    val conversationKind = parseConversationKindOrNull(channelType)
    val itemKind = conversationKind.toInboxItemKind() ?: return null
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
        channelKind = conversationKind,
        channelType = channelType.takeIf { it.isNotBlank() },
        lastMessageId = MessageId(id),
        // The matched message is this row's open anchor — same contract as the
        // saved mapping above: thread rows anchor only via
        // latestActivityMessageId (task #231).
        latestActivityMessageId = MessageId(id),
        lastMessagePreview = snippet.ifBlank { content },
        lastMessageAt = createdAt,
        lastMessageSenderName = senderName,
        lastMessageSenderId = senderId,
        lastMessageSenderKind =
            senderType.toSenderKind(
                source = "search_message_sender",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.MessageSender,
            ),
        threadChannelId = if (itemKind == InboxItemKind.Thread) ChannelId(channelId) else null,
        parentChannelId = ChannelId(parentChannelId),
        parentChannelName = parentChannelName,
        parentChannelKind = parseConversationKindOrNull(parentChannelType),
        parentChannelType = parentChannelType.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId?.let(::MessageId),
        parentMessagePreview = parentMessageContent,
        latestDelegatedBy = delegatedBy.toMessageDelegatedBy(),
    )
}

private fun ConversationKind?.toInboxItemKind(): InboxItemKind? =
    when (this) {
        ConversationKind.DirectMessage -> InboxItemKind.DirectMessage
        ConversationKind.Thread -> InboxItemKind.Thread
        ConversationKind.Channel,
        ConversationKind.Joint -> InboxItemKind.Channel
        ConversationKind.System,
        ConversationKind.Inbox,
        ConversationKind.Task,
        null -> null
    }
