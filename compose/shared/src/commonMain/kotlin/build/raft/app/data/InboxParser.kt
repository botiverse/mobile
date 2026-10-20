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
    val id: String? = null,
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
    val latestReplies: List<WireThreadReplyPreview> = emptyList(),
    val participantIds: List<String> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskClaimedByName: String? = null,
    // thread follow state; only an explicit false means Unfollowed
    val isFollowing: Boolean? = null,
    val unfollowedAt: String? = null,
    // mention_action
    val messageId: String? = null,
    val messagePreview: String? = null,
    val createdAt: String? = null,
    val pendingMentionActions: List<WireInboxPendingMentionAction> = emptyList(),
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
private data class WireInboxPendingMentionAction(
    val resolutionId: String? = null,
    val id: String? = null,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val targetAvatarUrl: String? = null,
    val reason: String = "Mention target was not notified at send time.",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null,
)

@Serializable
private data class WireInboxResponse(
    val items: List<WireInboxItem> = emptyList(),
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
    val totalUnreadCount: Int? = null,
    val activeUnreadCount: Int? = null,
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
        .map { it.copy(kind = "thread") }
        .mapNotNull { it.toThreadDomain(diagnostics) }
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
                val doneFrontierPresent =
                    (rawItemObjects?.getOrNull(index) as? JsonObject)?.containsKey("doneFrontierSeq") == true
                wire.toDomain(diagnostics)?.copy(doneFrontierSeqPresent = doneFrontierPresent)
            }
            .distinctBy { it.key }
    val items = rawItems.map(InboxItem::withActivityFollowStateProjection)
    val derivedCount = items.size
    val derivedUnread = items.sumOf { it.unreadCount }
    // Calculate the suppressed contribution from the same parsed rows that
    // can reach the domain model. An invalid/duplicate wire row must not alter
    // the aggregate for a row that was discarded by the strict parser.
    val rawItemsUnread = rawItems.sumOf { it.unreadCount }
    val rawUnfollowedUnread = rawItems
        .asSequence()
        .filter(InboxItem::isUnfollowedActivityThread)
        .sumOf { it.unreadCount }
    val wireUnread = decoded.totalUnreadCount
    // A compliant server already reports zero attention for an unfollowed
    // row. If an older/lagging serving path includes that row's raw unread
    // value in the aggregate, remove just that projected contribution. When
    // the aggregate is lower than the page's raw sum it is already an
    // active-only aggregate, so retain it rather than subtracting twice.
    val projectedUnread =
        when {
            wireUnread == null -> derivedUnread
            rawUnfollowedUnread <= 0 -> maxOf(wireUnread, derivedUnread)
            wireUnread >= rawItemsUnread ->
                maxOf(derivedUnread, (wireUnread - rawUnfollowedUnread).coerceAtLeast(0))
            else -> maxOf(wireUnread, derivedUnread)
        }
    SharedInboxSnapshot(
        items = items,
        hasMore = decoded.hasMore,
        totalCount = maxOf(decoded.totalCount ?: derivedCount, derivedCount),
        totalUnreadCount = projectedUnread,
        rawItemCount = decoded.items.size,
        activeUnreadCount = decoded.activeUnreadCount,
    )
}.getOrNull()

private fun WireInboxItem.toDomain(diagnostics: (String) -> Unit): InboxItem? = when (kind) {
    "channel", "dm" -> toChannelOrDmDomain(diagnostics)
    "thread" -> toThreadDomain(diagnostics)
    "mention_action" -> toMentionActionDomain()
    else -> null
}

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
        unreadCount = unreadCount,
        hasMention = hasMention,
        firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
        lastMessageId = lastMessageId?.let(::MessageId),
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        lastMessageSenderId = lastMessageSenderId,
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
        parentMessageId = parentMessageId?.let(::MessageId),
        parentMessagePreview = parentMessagePreview,
        parentMessageSenderId = parentMessageSenderId,
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

private fun WireInboxItem.toMentionActionDomain(): InboxItem? {
    val actionId = id?.takeIf { it.isNotBlank() }
        ?: pendingMentionActions.firstNotNullOfOrNull { action -> action.resolutionId ?: action.id }?.takeIf { it.isNotBlank() }
        ?: return null
    val targetChannelId = channelId?.takeIf { it.isNotBlank() }
        ?: pendingMentionActions.firstNotNullOfOrNull { action -> action.channelId }?.takeIf { it.isNotBlank() }
        ?: return null
    val targetMessageId = messageId?.takeIf { it.isNotBlank() }
        ?: pendingMentionActions.firstNotNullOfOrNull { action -> action.messageId }?.takeIf { it.isNotBlank() }
    val mappedActions = pendingMentionActions.mapNotNull { it.toDomain() }
    return InboxItem(
        key = "mention_action:$actionId",
        kind = InboxItemKind.MentionAction,
        channelId = ChannelId(targetChannelId),
        channelName = channelName?.takeIf { it.isNotBlank() }
            ?: mappedActions.firstNotNullOfOrNull { action -> action.channelName?.takeIf { it.isNotBlank() } }
            ?: targetChannelId,
        channelKind = parseConversationKindOrNull(channelType),
        unreadCount = 0,
        hasMention = false,
        lastMessageId = targetMessageId?.let(::MessageId),
        lastMessagePreview = messagePreview,
        lastMessageAt = createdAt,
        mentionActionId = actionId,
        pendingMentionActions = mappedActions,
    )
}

private fun WireInboxPendingMentionAction.toDomain(): PendingMentionAction? {
    val resolvedId = resolutionId?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: return null
    return PendingMentionAction(
        resolutionId = resolvedId,
        messageId = messageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        channelId = channelId?.takeIf { it.isNotBlank() }?.let(::ChannelId),
        channelName = channelName,
        targetType = targetType,
        targetHandle = targetHandle,
        targetAvatarUrl = targetAvatarUrl.normalizedPendingMentionAvatarUrl(),
        reason = reason,
        availableActions = availableActions,
        expiresAt = expiresAt,
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

fun parseSharedSearchItems(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): SharedSearchItems? = runCatching {
    val decoded = inboxJson.decodeFromString(WireSearchResponse.serializer(), bodyText)
    val items = decoded.results.mapNotNull { it.toInboxItem(diagnostics) }.distinctBy { it.key }
    SharedSearchItems(items = items, hasMore = decoded.hasMore)
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
        parentMessageId = parentMessageId?.let(::MessageId),
        parentMessagePreview = parentMessageContent,
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
        null -> null
    }
