package build.raft.app.data

import build.raft.app.model.*
import build.raft.app.network.sync.ReplyPreview
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray

@Serializable
data class InboxItem(
    val key: String,
    val kind: InboxItemKind,
    val channelId: ChannelId,
    val channelName: String,
    val channelKind: ConversationKind? = null,
    val unreadCount: Int = 0,
    val hasMention: Boolean = false,
    val hasDraft: Boolean = false,
    val firstUnreadMessageId: MessageId? = null,
    val lastMessageId: MessageId? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageSenderId: String? = null,
    val lastMessageSenderKind: MemberKind? = null,
    val lastMessageSenderAvatarWireValue: String? = null,
    val threadChannelId: ChannelId? = null,
    val parentChannelId: ChannelId? = null,
    val parentChannelName: String? = null,
    val parentChannelKind: ConversationKind? = null,
    val parentMessageId: MessageId? = null,
    val parentMessagePreview: String? = null,
    val parentMessageSenderId: String? = null,
    val parentMessageSenderKind: MemberKind? = null,
    val latestActivityMessageId: MessageId? = null,
    val latestActivityPreview: String? = null,
    val latestActivityAt: String? = null,
    val latestActivitySenderName: String? = null,
    val latestActivitySenderId: String? = null,
    val latestActivitySenderKind: MemberKind? = null,
    val latestActivitySenderAvatarWireValue: String? = null,
    val replyCount: Int = 0,
    /** Bounded server-derived latest replies; never a second message authority. */
    val latestReplies: List<ReplyPreview> = emptyList(),
    val participantIds: List<MemberId> = emptyList(),
    val taskNumber: Int? = null,
    val taskStatus: TaskStatus? = null,
    val taskClaimedByName: String? = null,
    /**
     * Server-owned follow state for thread Activity rows. Older payloads that
     * predate this projection remain unknown; only an explicit server `false`
     * is Unfollowed. We deliberately do not model a separate history source
     * on Mobile.
     */
    val isFollowing: Boolean? = null,
    val mentionActionId: String? = null,
    val pendingMentionActions: List<PendingMentionAction> = emptyList(),
    /**
     * Authority activity frontier for this row's own scope: wire
     * `readState.latestActivity.seq` (canonical decimal string) when
     * `readState.kind == "present"`, else null. Not the display
     * `latestActivitySeq`, which keeps a zero-reply parent fallback in another
     * seq domain. Used as the local Done/read overlay marker.
     */
    val readStateLatestActivitySeq: String? = null
)

/** True only for an explicitly server-projected, unfollowed thread row. */
internal val InboxItem.isUnfollowedActivityThread: Boolean
    get() = kind == InboxItemKind.Thread && isFollowing == false

/** Legacy payloads omit the optional field; keep their existing followed-row controls. */
internal val InboxItem.isFollowedActivityThread: Boolean
    get() = kind == InboxItemKind.Thread && isFollowing != false

/**
 * Activity attention is derived from the follow-state projection.  A stale or
 * over-eager server/client payload must not make an Unfollowed row re-enter
 * ordinary unread/mention attention.
 */
internal fun InboxItem.withActivityFollowStateProjection(): InboxItem =
    if (isUnfollowedActivityThread) {
        copy(
            unreadCount = 0,
            firstUnreadMessageId = null,
            hasMention = false
        )
    } else {
        this
    }

/**
 * The Activity All projection may retain unfollowed rows, while attention
 * filters must never surface them.  Keep this rule next to the model so every
 * repository/fixture consumer applies the same membership contract.
 */
internal fun InboxSnapshot.projectActivityFilter(filter: InboxFilter): InboxSnapshot {
    val rawUnread = items.sumOf(InboxItem::unreadCount)
    val normalizedItems = items.map { item -> item.withActivityFollowStateProjection() }
    val normalizedUnread = normalizedItems.sumOf(InboxItem::unreadCount)
    val suppressedUnfollowedUnread = (rawUnread - normalizedUnread).coerceAtLeast(0)
    val projectedTotalUnread =
        if (totalUnreadCount >= rawUnread) {
            (totalUnreadCount - suppressedUnfollowedUnread).coerceAtLeast(0)
        } else {
            maxOf(totalUnreadCount, normalizedUnread)
        }
    val visibleItems =
        if (filter == InboxFilter.All) {
            normalizedItems
        } else {
            normalizedItems.filterNot { item -> item.isUnfollowedActivityThread }
        }
    val removedCount = normalizedItems.size - visibleItems.size
    if (removedCount == 0 && normalizedItems == items && projectedTotalUnread == totalUnreadCount) return this
    return copy(
        items = visibleItems,
        totalCount = (totalCount - removedCount).coerceAtLeast(0),
        totalUnreadCount = projectedTotalUnread
    )
}

/**
 * Applies the local, reversible projection for a Follow/Unfollow mutation.
 * The network mutation remains authoritative; this helper only makes the
 * visible row respond immediately while preserving the server aggregate and
 * the All-vs-attention-filter membership contract.
 */
internal fun InboxSnapshot.projectActivityFollowMutation(
    itemKey: String,
    following: Boolean,
    filter: InboxFilter
): InboxSnapshot {
    val previousItem = items.firstOrNull { item -> item.key == itemKey }
    val mutated = items.map { item ->
        if (item.key != itemKey) {
            item
        } else {
            item.copy(
                isFollowing = following,
                unreadCount = 0,
                firstUnreadMessageId = null,
                hasMention = false
            )
        }
    }
    val projected =
        copy(
            items = mutated,
            totalUnreadCount =
                (totalUnreadCount - (previousItem?.unreadCount ?: 0)).coerceAtLeast(0)
        ).projectActivityFilter(filter)
    if (filter == InboxFilter.All) return projected
    val targetPresent = projected.items.any { it.key == itemKey }
    if (!targetPresent) return projected
    return projected.copy(
        items = projected.items.filterNot { it.key == itemKey },
        totalCount = (projected.totalCount - 1).coerceAtLeast(0)
    )
}

internal fun InboxItem.hasUnopenableInternalJointParentSource(): Boolean =
    kind == InboxItemKind.Thread &&
        parentChannelKind == ConversationKind.Joint &&
        isInternalJointStorageIdentity(parentChannelId?.value)

@Serializable
sealed interface HomeConversationRow {
    val threadId: ThreadId
    val channelId: ChannelId?
    val channelType: String?
    val thread: ThreadConversation?
    val kind: ConversationKind
    val name: String
    val subtitle: String?
    val directMessagePeerType: String?
    val directMessagePeerAvatarWireValue: String?
    val unreadCount: Int
    val unreadCountKnown: Boolean
    val mentionCount: Int
    val mentionCountKnown: Boolean
    val lastMessageId: MessageId?
    val lastMessageSeq: Long?
    val firstUnreadMessageId: MessageId?
    val readStateMaxReadSeq: Long?
    val readStateV1Hydrate: ReadStateV1HydrateCarrier?
    val lastMessagePreview: String?
    val lastMessageAt: String?
    val lastMessageSenderName: String?
    val presenceMemberId: MemberId?
    val hasDraft: Boolean
    val hasDraftKnown: Boolean
    val joined: Boolean
    val archived: Boolean
    val memberCount: Int?
    val jointChannelId: String?
    val jointRole: String?
    val jointPeerServerId: ServerId?
    val jointPeerServerName: String?
    val jointPeerServerSlug: String?
    val jointPeerStatus: String?
    val jointBillingLocked: Boolean?
    val jointOverLimitGraceEndsAt: String?
    /** task #396: external bridge provider (e.g. "slack"); only channels carry one. */
    val bridgeProvider: String?
        get() = null
    /** Channel `guestVisible` (see [HomeChannelWireDto.guestVisible]); only channels carry one. */
    val guestVisible: Boolean
        get() = false
    val activityNotificationPreference: ChannelActivityNotificationPreference
    val permissions: ConversationPermissions
    /**
     * Server-resolved channel-level capabilities (see [ConversationSummary.channelCapabilities]).
     * `null` on DMs and on payloads that pre-date the field.
     */
    val channelCapabilities: ChannelCapabilities?

    val activityMuted: Boolean
        get() = activityNotificationPreference.activityMuted
    val muteFromSeq: Long?
        get() = activityNotificationPreference.muteFromSeq
    val activityMuteSupported: Boolean
        get() = activityNotificationPreference.activityMuteSupported
}

@Serializable
data class HomeChannelRow(
    override val threadId: ThreadId,
    override val channelId: ChannelId,
    override val channelType: String? = null,
    override val thread: ThreadConversation? = null,
    override val kind: ConversationKind = ConversationKind.Channel,
    override val name: String,
    override val subtitle: String? = null,
    override val directMessagePeerType: String? = null,
    override val directMessagePeerAvatarWireValue: String? = null,
    override val unreadCount: Int = 0,
    override val unreadCountKnown: Boolean = false,
    override val mentionCount: Int = 0,
    override val mentionCountKnown: Boolean = false,
    override val lastMessageId: MessageId? = null,
    override val lastMessageSeq: Long? = null,
    override val firstUnreadMessageId: MessageId? = null,
    override val readStateMaxReadSeq: Long? = null,
    @Transient override val readStateV1Hydrate: ReadStateV1HydrateCarrier? = null,
    override val lastMessagePreview: String? = null,
    override val lastMessageAt: String? = null,
    override val lastMessageSenderName: String? = null,
    override val presenceMemberId: MemberId? = null,
    override val hasDraft: Boolean = false,
    override val hasDraftKnown: Boolean = false,
    override val joined: Boolean = true,
    override val archived: Boolean = false,
    override val memberCount: Int? = null,
    override val jointChannelId: String? = null,
    override val jointRole: String? = null,
    override val jointPeerServerId: ServerId? = null,
    override val jointPeerServerName: String? = null,
    override val jointPeerServerSlug: String? = null,
    override val jointPeerStatus: String? = null,
    override val jointBillingLocked: Boolean? = null,
    override val jointOverLimitGraceEndsAt: String? = null,
    override val bridgeProvider: String? = null,
    override val guestVisible: Boolean = false,
    override val activityNotificationPreference: ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference.Unsupported,
    override val permissions: ConversationPermissions = ConversationPermissions(),
    override val channelCapabilities: ChannelCapabilities? = null
) : HomeConversationRow

@Serializable
data class HomeDirectMessageRow(
    override val threadId: ThreadId,
    override val channelId: ChannelId,
    override val channelType: String? = null,
    override val thread: ThreadConversation? = null,
    override val kind: ConversationKind = ConversationKind.DirectMessage,
    override val name: String,
    override val subtitle: String? = null,
    override val directMessagePeerType: String? = null,
    override val directMessagePeerAvatarWireValue: String? = null,
    override val unreadCount: Int = 0,
    override val unreadCountKnown: Boolean = false,
    override val mentionCount: Int = 0,
    override val mentionCountKnown: Boolean = false,
    override val lastMessageId: MessageId? = null,
    override val lastMessageSeq: Long? = null,
    override val firstUnreadMessageId: MessageId? = null,
    override val readStateMaxReadSeq: Long? = null,
    @Transient override val readStateV1Hydrate: ReadStateV1HydrateCarrier? = null,
    override val lastMessagePreview: String? = null,
    override val lastMessageAt: String? = null,
    override val lastMessageSenderName: String? = null,
    override val presenceMemberId: MemberId? = null,
    override val hasDraft: Boolean = false,
    override val hasDraftKnown: Boolean = false,
    override val joined: Boolean = true,
    override val archived: Boolean = false,
    override val memberCount: Int? = null,
    override val jointChannelId: String? = null,
    override val jointRole: String? = null,
    override val jointPeerServerId: ServerId? = null,
    override val jointPeerServerName: String? = null,
    override val jointPeerServerSlug: String? = null,
    override val jointPeerStatus: String? = null,
    override val jointBillingLocked: Boolean? = null,
    override val jointOverLimitGraceEndsAt: String? = null,
    override val activityNotificationPreference: ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference.Unsupported,
    override val permissions: ConversationPermissions = ConversationPermissions(),
    override val channelCapabilities: ChannelCapabilities? = null
) : HomeConversationRow

fun HomeConversationRow.toConversationSummary(): ConversationSummary =
    ConversationSummary(
        threadId = threadId,
        channelId = channelId,
        channelType = channelType,
        thread = thread,
        kind = kind,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        directMessagePeerAvatarWireValue = directMessagePeerAvatarWireValue,
        unreadCount = unreadCount,
        unreadCountKnown = unreadCountKnown,
        mentionCount = mentionCount,
        mentionCountKnown = mentionCountKnown,
        lastMessageId = lastMessageId,
        lastMessageSeq = lastMessageSeq,
        firstUnreadMessageId = firstUnreadMessageId,
        readStateMaxReadSeq = readStateMaxReadSeq,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        presenceMemberId = presenceMemberId,
        hasDraft = hasDraft,
        hasDraftKnown = hasDraftKnown,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        jointBillingLocked = jointBillingLocked,
        jointOverLimitGraceEndsAt = jointOverLimitGraceEndsAt,
        bridgeProvider = bridgeProvider,
        guestVisible = guestVisible,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities
    )

fun ConversationSummary.toHomeChannelRow(): HomeChannelRow =
    HomeChannelRow(
        threadId = threadId,
        channelId = channelId ?: ChannelId(threadId.value),
        channelType = channelType,
        thread = thread,
        kind = if (kind == ConversationKind.Joint) ConversationKind.Joint else ConversationKind.Channel,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        directMessagePeerAvatarWireValue = directMessagePeerAvatarWireValue,
        unreadCount = unreadCount,
        unreadCountKnown = unreadCountKnown,
        mentionCount = mentionCount,
        mentionCountKnown = mentionCountKnown,
        lastMessageId = lastMessageId,
        lastMessageSeq = lastMessageSeq,
        firstUnreadMessageId = firstUnreadMessageId,
        readStateMaxReadSeq = readStateMaxReadSeq,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        presenceMemberId = presenceMemberId,
        hasDraft = hasDraft,
        hasDraftKnown = hasDraftKnown,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        jointBillingLocked = jointBillingLocked,
        jointOverLimitGraceEndsAt = jointOverLimitGraceEndsAt,
        bridgeProvider = bridgeProvider,
        guestVisible = guestVisible,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities
    )

fun ConversationSummary.toHomeDirectMessageRow(): HomeDirectMessageRow =
    HomeDirectMessageRow(
        threadId = threadId,
        channelId = channelId ?: ChannelId(threadId.value),
        channelType = channelType,
        thread = thread,
        kind = ConversationKind.DirectMessage,
        name = name,
        subtitle = subtitle,
        directMessagePeerType = directMessagePeerType,
        directMessagePeerAvatarWireValue = directMessagePeerAvatarWireValue,
        unreadCount = unreadCount,
        unreadCountKnown = unreadCountKnown,
        mentionCount = mentionCount,
        mentionCountKnown = mentionCountKnown,
        lastMessageId = lastMessageId,
        lastMessageSeq = lastMessageSeq,
        firstUnreadMessageId = firstUnreadMessageId,
        readStateMaxReadSeq = readStateMaxReadSeq,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        lastMessageSenderName = lastMessageSenderName,
        presenceMemberId = presenceMemberId,
        hasDraft = hasDraft,
        hasDraftKnown = hasDraftKnown,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions,
        channelCapabilities = channelCapabilities
    )

@Serializable
data class HomeSnapshot(
    val server: ServerSummary,
    val currentMember: MemberProfile,
    val channels: List<HomeChannelRow>,
    val directMessages: List<HomeDirectMessageRow>,
    val unreadTotal: Int,
    /**
     * #81: explicit source provenance for [unreadTotal]. Matches
     * `HomeParser.kt:97-103` derivation:
     * - `SERVER_UNREAD_SUMMARY` when `payload.serverUnreadSummary` had a
     *   non-null entry for the active server.
     * - `CHANNEL_DM_FALLBACK` when the server summary was missing for the
     *   active server and the count was derived from the channel + DM
     *   unread sum.
     * - `UNKNOWN` only for synthetic / preview snapshots that bypass the
     *   parser.
     *
     * Default is `UNKNOWN` so existing call sites that don't set it (test
     * fixtures, preview builders) keep compiling.
     */
    val unreadSource: AppBadgeSource = AppBadgeSource.UNKNOWN,
    /**
     * Cross-server unread counts from `/servers/unread-summary`, keyed by
     * server id. The selected server still exposes its projected count through
     * [server] / [unreadTotal]; this map is for surfaces such as the Home
     * server switcher that need other servers' counts without route payloads.
     */
    val serverUnreadCounts: Map<String, Int> = emptyMap(),
    /**
     * Known per-server Activity unread counts from the optional
     * `activityUnreadCount` summary field. Map membership is the knowledge
     * bit: a present zero is authoritative, while a missing key is unknown.
     * Legacy [serverUnreadCounts] must never be substituted into this map.
     */
    val serverActivityUnreadCounts: Map<String, Long> = emptyMap(),
    /** Notification delivery preference carried beside, never folded into, Activity count authority. */
    val serverPushMutedByServer: Map<String, Boolean> = emptyMap(),
    /** False when the server-summary request failed or its wire shape was rejected. */
    val serverUnreadSummaryAvailable: Boolean = true,
    /**
     * task #831: false when this snapshot's unread projection came from
     * unavailable auxiliary endpoints (error/timeout/hung/contract-mismatch),
     * so [unreadTotal] / [serverUnreadCounts] are an honest empty rather than an
     * authoritative zero. Consumers must keep the prior unread badges (mark them
     * stale) instead of clobbering them to read. Default true so existing
     * fixtures / preview builders keep compiling.
     */
    val unreadEnrichmentAvailable: Boolean = true,
    /**
     * #211: per-server sidebar order snapshot, including pinned conversation
     * ids, pinned order, and pinned sort mode. `null` means the sidebar
     * order has not been loaded for this snapshot yet; the visual layer
     * falls back to "no pinned items" until a non-null value arrives.
     */
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null
)

data class ServerListSnapshot(
    val servers: List<ServerSummary>,
    val selectedServer: ServerSummary?
)

@Serializable
data class ThreadSnapshot(
    val conversation: ConversationSummary,
    val messages: List<Message>,
    val parentMessage: Message? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val hasGap: Boolean = false,
    val historyLimited: Boolean = false,
    val highlightedMessageId: MessageId? = null,
    val newUpdateCount: Int = 0,
    val lastSeq: Long = messages.maxOfOrNull { it.seq ?: 0L } ?: 0L,
    /** Page-coherent inline reply summaries returned with the same canonical message response. */
    @Transient
    val replySummaries: ThreadReplySummariesSnapshot = ThreadReplySummariesSnapshot(emptyList()),
    /**
     * Provenance for [replySummaries]. Missing is distinct from an authoritative empty result:
     * old Servers omit the embedded field and must use the targeted-summary compatibility
     * request before a replies-domain rebaseline can publish.
     */
    @Transient
    val replySummariesAuthority: ThreadReplySummariesAuthority = ThreadReplySummariesAuthority.Unavailable,
    /**
     * Destination-owned channel/DM detail loaded with this snapshot. Reply Threads keep their
     * stream identity in [conversation], so this separate witness retains the canonical parent
     * conversation without turning the reply stream into its parent.
     */
    @Transient
    val canonicalConversationDetail: ConversationSummary? = null,
    /**
     * Raw `messages` array of the REST page this snapshot was mapped from (task #168). Set ONLY by
     * the older / newer paging fetches; every other path leaves it [RawMessagePageWitness.None]. It
     * exists so a paging page can also be persisted into the durable V2 window; see
     * [RawMessagePageWitness] for why it never takes part in equality.
     */
    @Transient
    val rawMessagePage: RawMessagePageWitness = RawMessagePageWitness.None,
)

/** One fetched REST message page: the server's raw `messages` array and the `limit` the request sent. */
data class RawMessagePage(
    val messages: JsonArray,
    val requestedLimit: Int,
)

/**
 * Write-side witness carrying a [RawMessagePage] from the repository mapper to the thread store's
 * paging call site (task #168), which hands it to the durable window extender.
 *
 * It must never affect state equality or dedupe: two snapshots that map to the same messages are
 * the same snapshot whether or not one of them still carries its raw page. So [equals] is `true`
 * for every other witness (present or absent alike) and [hashCode] is constant. The field is
 * non-null with a [None] sentinel precisely so that "absent vs present" is not a difference either
 * (a nullable field would make `null != page` under the data-class equality).
 */
class RawMessagePageWitness(val page: RawMessagePage?) {
    override fun equals(other: Any?): Boolean = other is RawMessagePageWitness

    override fun hashCode(): Int = 0

    override fun toString(): String =
        page?.let { "RawMessagePageWitness(size=${it.messages.size}, requestedLimit=${it.requestedLimit})" }
            ?: "RawMessagePageWitness(None)"

    companion object {
        val None: RawMessagePageWitness = RawMessagePageWitness(null)
    }
}

data class ThreadListSnapshot(
    val server: ServerSummary,
    val threads: List<InboxItem>,
    val totalCount: Int = threads.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class ThreadReplySummariesSnapshot(
    val threads: List<InboxItem>,
    val followedThreadKeys: Set<String> = emptySet()
)

enum class ThreadReplySummariesAuthority {
    Unavailable,
    EmbeddedInvalid,
    Embedded,
    Targeted,
}

data class AttachmentUploadRequest(
    val channelId: ChannelId,
    val files: List<PlatformFile>
)

/**
 * Platform-owned local file handle selected by a picker or produced by a native editor.
 *
 * Product/upload code may carry this model through shared state, but only platform
 * file-reader capabilities may dereference [fileRef]. Multipart field names, auth,
 * endpoints, and HTTP headers belong to shared upload/network code, not this model.
 */
data class PlatformFile(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val previewUri: String? = null,
    val bytes: ByteArray = ByteArray(0),
    val fileRef: String? = null,
    val source: String = ""
)

data class AttachmentUploadResult(
    val attachments: List<Attachment>
) {
    val attachmentIds: List<AttachmentId> = attachments.map { it.id }
}

data class AttachmentUploadCapabilities(
    val maxBytes: Long,
    /** Server-authoritative direct-session availability.  Never infer this from the route. */
    val directUploadEnabled: Boolean = false,
    /** Optional threshold advertised by the server; null means no threshold contract. */
    val directUploadThresholdBytes: Long? = null,
    /** Optional session lifetime hint, in seconds. */
    val sessionExpiresInSeconds: Int? = null,
)

/** Input for the authenticated direct attachment-session create operation. */
data class AttachmentUploadSessionCreateRequest(
    val channelId: ChannelId,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val clientRequestId: String,
)

/** Presigned PUT instructions returned by the server.  [url] is never persisted or logged. */
data class AttachmentUploadSessionPut(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
)

data class AttachmentUploadSessionCreateResult(
    val uploadId: String,
    val attachmentId: AttachmentId,
    val state: String,
    val expiresAt: String,
    val upload: AttachmentUploadSessionPut,
)

data class AttachmentUploadSessionView(
    val uploadId: String,
    val state: String,
    val expiresAt: String,
    val attachment: Attachment? = null,
    val terminalReason: String? = null,
    val reservationState: String? = null,
)

data class AttachmentUploadSessionCompleteResult(
    val uploadId: String,
    val state: String,
    val attachment: Attachment,
)

/** One completed direct upload kept private until the enclosing Gallery batch commits. */
data class DirectAttachmentUpload(
    val localId: String,
    val uploadId: String,
    val attachment: Attachment,
)

internal data class DirectAttachmentUploadBatch(
    val uploads: List<DirectAttachmentUpload>,
    val sessionIds: List<String>,
    /** Process-local compensation handle; never persisted or sent over the wire. */
    internal val compensationHandle: DirectAttachmentUploadCompensationHandle? = null,
)

data class AttachmentOpenTarget(
    val attachmentId: AttachmentId,
    val url: String,
    val expiresAt: String? = null
)

data class ChannelFileItem(
    val attachment: Attachment,
    val createdAt: String? = null,
    val messageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val uploaderType: String? = null,
    val uploaderId: String? = null,
    val uploaderName: String? = null,
    val uploaderDisplayName: String? = null,
    val sourceType: String? = null,
    val sourceChannelId: ChannelId? = null,
    val sourceParentMessageId: MessageId? = null,
    val sourceParentMessageShortId: String? = null
)

data class ChannelFilesSnapshot(
    val channelId: ChannelId,
    val items: List<ChannelFileItem>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null
)

data class TaskSnapshot(
    val tasks: List<TaskSummary>,
    val summaryCounts: Map<TaskStatus, Int>
)

data class MemberSnapshot(
    val server: ServerSummary,
    val members: List<MemberProfile>
)

data class AgentDetailSnapshot(
    val agentId: MemberId,
    val summary: AgentDetailSummary? = null,
    val directMessages: List<AgentDmSummary>,
    val channels: List<AgentChannelSummary> = emptyList(),
    val reminders: List<AgentReminderSummary>,
    val activity: List<AgentActivityEntry>,
    val skills: AgentSkillsSnapshot = AgentSkillsSnapshot(agentId = agentId),
    val appLogins: AgentAppLoginSnapshot = AgentAppLoginSnapshot(agentId = agentId),
    /**
     * `GET /agents/{id}/external-status` for external agents (React
     * AgentDetailPanel parity). Null means *not provided*: the agent is not
     * external, the caller lacks `editAgents`/creator authority (403), or the
     * request failed. Consumers must not read null as "waiting for login".
     */
    val externalStatus: ExternalAgentStatusSnapshot? = null
)

/** Wire `setupState` of `GET /agents/{id}/external-status`. */
enum class ExternalAgentSetupState {
    WaitingForLogin,
    CredentialMinted,
    Connected,
    Unknown;

    companion object {
        fun fromWireValue(value: String?): ExternalAgentSetupState =
            when (value?.trim()?.lowercase()) {
                "waiting_for_login" -> WaitingForLogin
                "credential_minted" -> CredentialMinted
                "connected" -> Connected
                else -> Unknown
            }
    }
}

data class ExternalAgentStatusSnapshot(
    val setupState: ExternalAgentSetupState,
    val credentialLastUsedAt: String? = null,
    val lastActivityAt: String? = null
)

data class AgentDetailSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val serverRole: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val isExternal: Boolean = false,
    val model: String? = null,
    val runtimeModelKind: String? = null,
    val runtimeProvider: build.raft.app.model.RuntimeProviderConfig? = null,
    val reasoning: String? = null,
    val executionMode: String? = null,
    val machineId: String? = null,
    val machineName: String? = null,
    val daemonVersion: String? = null,
    val avatarUrl: String? = null,
    val creatorId: String? = null,
    val creatorType: String? = null,
    val creator: AgentCreatorSummary? = null,
    val createdAt: String? = null,
    val envVarNames: List<String> = emptyList(),
    val envVars: Map<String, String> = emptyMap(),
    val createdAgents: List<AgentCreatedSummary> = emptyList(),
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null,
    /** External agents only: `lastSeenAt` — credential last-use; realtime `agent:seen` advances it (task #410). */
    val lastSeenAt: String? = null
)

data class AgentCreatorSummary(
    val type: String,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null
)

data class AgentCreatedSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val status: String? = null,
    /** Required: producers must fold both sources via isExternalAgent(external, runtime). */
    val isExternal: Boolean
)

data class AgentDmSnapshot(
    val agentId: MemberId,
    val directMessages: List<AgentDmSummary>
)

data class AgentChannelSnapshot(
    val agentId: MemberId,
    val channels: List<AgentChannelSummary>
)

data class AgentReminderSnapshot(
    val agentId: MemberId,
    val reminders: List<AgentReminderSummary>
)

data class AgentActivitySnapshot(
    val agentId: MemberId,
    val activity: List<AgentActivityEntry>
)

// #89 React parity: agent permissions (scope grants) snapshot.
// Mirrors React `AgentScopeSet` returned by `GET /api/agents/:id/scopes`.
// `granted` is a list of `AgentGrantableScope` strings (e.g. "inbox:receive",
// "server:read", "channel:read", ...). `mode` is "default" vs "custom".
data class AgentScopeSet(
    val agentId: MemberId,
    val granted: List<String> = emptyList(),
    val mode: String = "default",
    val revision: Long = 0L,
    val updatedAt: String? = null
)

data class AgentWorkspaceSnapshot(
    val agentId: MemberId,
    val computerId: String? = null,
    val computerName: String? = null,
    val workspaces: List<SettingsComputerWorkspaceSnapshot> = emptyList()
)

data class AgentWorkspaceTreeSnapshot(
    val agentId: MemberId,
    val rootPath: String,
    val root: RaftWorkspaceNode
)

data class AgentWorkspaceChildrenSnapshot(
    val agentId: MemberId,
    val dirPath: String,
    val children: List<RaftWorkspaceNode>
)

data class AgentWorkspaceFilePreviewSnapshot(
    val agentId: MemberId,
    val path: String,
    val preview: RaftWorkspaceFilePreview
)

data class AgentSkillsSnapshot(
    val agentId: MemberId,
    val groups: List<AgentSkillGroup> = emptyList()
)

data class AgentAppLoginSnapshot(
    val agentId: MemberId,
    val items: List<AgentAppLoginItem> = emptyList(),
    val loadState: AgentAppLoginLoadState = AgentAppLoginLoadState.Content,
    val errorCode: String? = null,
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null
)

enum class AgentAppLoginLoadState {
    Content,
    Empty,
    NoPermission,
    Error
}

data class AgentAppLoginItem(
    val id: String,
    val type: AgentAppLoginType,
    val clientId: String,
    val clientKey: String? = null,
    val clientName: String,
    val clientDescription: String? = null,
    val clientHomepageUrl: String? = null,
    val clientReturnUrl: String? = null,
    val clientAgentManifestUrl: String? = null,
    val scopes: List<String> = emptyList(),
    val remember: Boolean = false,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
    val resolvedByUserId: String? = null,
    val revokedAt: String? = null
)

enum class AgentAppLoginType {
    Pending,
    Active
}

data class AgentSkillGroup(
    val title: String,
    val count: Int = 0,
    val paths: List<AgentSkillPathGroup> = emptyList()
)

data class AgentSkillPathGroup(
    val path: String,
    val count: Int = 0,
    val skills: List<AgentSkillSummary> = emptyList()
)

data class AgentSkillSummary(
    val name: String,
    val detail: String? = null
)

data class AgentDmSummary(
    val id: String,
    val peerId: String?,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null
)

/** An agent's channel membership as returned by GET /agents/:id/channels. */
data class AgentChannelSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String = "channel",
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null
)

data class AgentReminderSummary(
    val id: String,
    val title: String,
    val fireAt: String? = null,
    val recurrenceDescription: String? = null,
    val messageRef: String? = null,
    val messagePermalink: String? = null,
    val ownerAgentId: String? = null,
    val status: String? = null
)

data class AgentActivityEntry(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val timestamp: String? = null,
    val details: List<Pair<String, String>> = emptyList(),
    val toolName: String? = null,
    val toolInput: String? = null,
    val statusActivity: String? = null,
    val statusDetail: String? = null,
    /** Server `detailKind` of a status entry ("starting", "compacting_context", ...). */
    val statusDetailKind: String? = null,
    val isSubagent: Boolean = false,
    val subagentType: String? = null,
    val expandable: Boolean = false,
    val refTextEnabled: Boolean = false
)

data class ChannelMemberSnapshot(
    val channelId: ChannelId,
    val members: List<MemberProfile>,
    val canAddMembers: Boolean = false,
    val canRemoveMembers: Boolean = false,
    /** Slack-bridge (or other provider) participants — display-only roster rows
     *  (web `channelExternalMembers`). Never selectable/removable. */
    val externalMembers: List<ChannelExternalMemberProfile> = emptyList()
)

data class AgentSnapshot(
    val server: ServerSummary,
    val agents: List<AgentProfile>,
    val members: List<MemberProfile> = agents.map { it.toMemberProfile() }
)

@Serializable
data class InboxSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val totalUnreadCount: Int = items.sumOf { it.unreadCount },
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

internal fun InboxSnapshot.activityVisibleSummaryCounts(
    totalUnreadCountOverride: Int? = null,
    totalCountOverride: Int? = null
): Pair<Int, Int> {
    val suppressed = items.filter { item -> item.hasUnopenableInternalJointParentSource() }
    val visibleItems = items.filterNot { item -> item.hasUnopenableInternalJointParentSource() }
    return maxOf(
        visibleItems.sumOf(InboxItem::unreadCount),
        (totalUnreadCountOverride ?: totalUnreadCount) - suppressed.sumOf(InboxItem::unreadCount)
    ) to maxOf(
        visibleItems.size,
        (totalCountOverride ?: totalCount) - suppressed.size
    )
}

data class InboxDoneResult(
    val channelId: ChannelId
)

data class ChannelSnapshot(
    val server: ServerSummary,
    val channels: List<HomeChannelRow>
)

data class CreateChannelResult(
    val channel: HomeChannelRow,
    val addedAgentMemberIds: List<String> = emptyList(),
    val addedUserMemberIds: List<String> = emptyList()
)

data class ChannelJoinResult(
    val channelId: ChannelId,
    val joined: Boolean = true,
    val channel: HomeChannelRow? = null
)

data class ChannelLeaveResult(
    val channelId: ChannelId,
    val left: Boolean = true
)

data class ChannelArchiveResult(
    val channelId: ChannelId,
    val archived: Boolean = true,
    val channel: HomeChannelRow? = null
)

data class ChannelDeleteResult(
    val channelId: ChannelId,
    val deleted: Boolean = true
)

@Serializable
data class ChannelUnreadSnapshot(
    val server: ServerSummary,
    val unreadCounts: Map<ChannelId, Int>,
    val totalUnreadCount: Int = unreadCounts.values.sum()
)

data class ReadReceiptResult(
    val channelId: ChannelId? = null,
    val seq: Long? = null,
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
    val scopes: List<ReadStateV1AckScope> = emptyList(),
    val unreadCount: Int = 0,
    val changed: Boolean = true
)

@Serializable
data class ReadStateV1AckScope(
    val channelId: ChannelId,
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
)

@Serializable
data class ReadStateV1HydrateCarrier(
    val readStateVersion: Long? = null,
    val maxReadSeq: Long? = null,
)

data class DirectMessageSnapshot(
    val server: ServerSummary,
    val directMessages: List<HomeDirectMessageRow>
)

data class OpenDirectMessageResult(
    val conversation: HomeDirectMessageRow,
    val directMessages: List<HomeDirectMessageRow>
)

data class SavedSnapshot(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class SavedMessageMutationResult(
    val messageId: MessageId
)

data class SavedMessageStatusSnapshot(
    val savedIds: Set<MessageId>
)

data class SearchSnapshot(
    val server: ServerSummary,
    val query: String,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

data class SettingsSnapshot(
    val settings: SettingsSummary
)

data class SettingsConnectedAppsSnapshot(
    val marketplace: List<SettingsConnectedAppMarketplaceSnapshot> = emptyList(),
    val builtIn: List<SettingsConnectedAppBuiltInSnapshot> = emptyList(),
    val installed: List<SettingsConnectedAppInstalledSnapshot> = emptyList(),
    val registered: List<SettingsConnectedAppClientSnapshot> = emptyList(),
    val canManage: Boolean = true,
    val oneTimeClientSecret: SettingsConnectedAppSecretSnapshot? = null
)

data class SettingsConnectedAppMarketplaceSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val category: String,
    val developer: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val returnUrl: String? = null,
    val callbackDomain: String? = null,
    val logoUrl: String? = null,
    val installed: Boolean = false,
    val dataAccessSummary: String = "Profile and identity"
)

data class SettingsConnectedAppBuiltInSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val agentManifestUrl: String? = null
)

data class SettingsConnectedAppInstalledSnapshot(
    val id: String,
    val clientId: String,
    val name: String,
    val developer: String,
    val description: String? = null,
    val homepageDomain: String? = null,
    val callbackDomain: String? = null,
    val logoUrl: String? = null,
    val origin: String,
    val editable: Boolean = false
)

data class SettingsConnectedAppClientSnapshot(
    val id: String,
    val serverId: String,
    val clientId: String,
    val appType: String,
    val publishStatus: String,
    val category: String,
    val dataAccessSummary: String? = null,
    val publishRejectionReason: String? = null,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val homepageDomain: String? = null,
    val returnUrl: String? = null,
    val callbackDomain: String? = null,
    val agentManifestUrl: String? = null,
    val logoUrl: String? = null
)

data class SettingsConnectedAppSecretSnapshot(
    val clientId: String,
    val clientSecret: String
)

data class AccountSettingsSnapshot(
    val profile: AccountProfileSnapshot,
    val connectedAccounts: List<ConnectedAccountSnapshot>,
    val canEditProfile: Boolean = true,
    val canUploadAvatar: Boolean = true,
    val canChangePassword: Boolean = true
)

data class AccountProfileSnapshot(
    val userId: String,
    val displayName: String,
    val handle: String,
    val email: String,
    val avatarLabel: String,
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val roleLabel: String? = null
)

data class ConnectedAccountSnapshot(
    val providerId: String,
    val provider: String,
    val identifier: String,
    val status: ConnectedAccountStatus,
    val canConnect: Boolean = status == ConnectedAccountStatus.NotConnected
)

enum class ConnectedAccountStatus {
    Connected,
    NotConnected,
    Loading,
    Failed
}

data class SettingsBillingSnapshot(
    val currentPlan: SettingsBillingCurrentPlanSnapshot,
    val plans: List<SettingsBillingPlanSnapshot>,
    val notices: List<SettingsBillingNoticeSnapshot> = emptyList(),
    val actions: List<SettingsBillingActionSnapshot> = emptyList(),
    val comparePlansUrl: String = "https://raft.build/#pricing"
)

data class SettingsBillingSubscriptionSnapshot(
    val status: String? = null,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val stripeConfigured: Boolean = false,
    val billingInterval: String? = null,
    val canManageBilling: Boolean = false,
    val displayName: String? = null,
    val effectivePlan: String? = null
)

data class SettingsServerUsageSnapshot(
    val agents: Int,
    val machines: Int,
    val channels: Int
)

data class SettingsBillingCurrentPlanSnapshot(
    val planName: String,
    val usageRows: List<SettingsPlanUsageSnapshot>,
    val trialActive: Boolean = false,
    val description: String = "",
    val includedFeatures: List<String> = emptyList(),
    val excludedFeatures: List<String> = emptyList()
)

data class SettingsPlanUsageSnapshot(
    val label: String,
    val value: String
)

data class SettingsBillingPlanSnapshot(
    val name: String,
    val price: String,
    val features: List<String>,
    val current: Boolean = false,
    val comingSoon: Boolean = false,
    val badgeLabel: String? = null
)

data class SettingsBillingNoticeSnapshot(
    val key: String,
    val title: String,
    val detail: String,
    val tone: String = "info",
    val rows: List<SettingsPlanUsageSnapshot> = emptyList()
)

data class SettingsBillingActionSnapshot(
    val key: String,
    val label: String,
    val detail: String,
    val enabled: Boolean = false,
    val style: String = "secondary"
)

data class SettingsComputersSnapshot(
    val computers: List<SettingsComputerSnapshot>,
    val canAddComputer: Boolean = false,
    val addComputerUnavailableReason: String? = null,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null
)

data class SettingsComputerSnapshot(
    val id: String,
    val name: String,
    val daemonVersion: String,
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
    val online: Boolean,
    val hostname: String? = null,
    val platform: String? = null,
    val description: String? = null,
    val lastSeenLabel: String? = null,
    val runtimes: List<String> = emptyList(),
    val createdAt: String? = null,
    val lastHeartbeat: String? = null,
    val apiKeyPrefix: String? = null,
    val statusVersion: Long = 0L,
    /** `machines.userId` — the machine creator, who may edit/remove it without a capability. */
    val creatorUserId: String? = null,
    /** The current user attached the Computer backing this machine (web counts this as manage authority). */
    val attachedByCurrentUser: Boolean = false,
    /** Active agents assigned to this machine (server `agentCount`). */
    val agentCount: Int = 0,
    /**
     * The server's policy-aware `computerUpgradeAvailable` decision, only ever true for a managed
     * Computer (web `shouldShowComputerUpgradeIndicator`); raw daemons never claim an update.
     */
    val upgradeAvailable: Boolean = false
)

data class SettingsComputerDetailSnapshot(
    val computer: SettingsComputerSnapshot,
    val workspaces: List<SettingsComputerWorkspaceSnapshot>,
    val agents: List<SettingsComputerAgentSnapshot>,
    val sections: List<SettingsServerSectionSnapshot>,
    val workspacesScanned: Boolean = false,
    val workspacesError: SettingsSectionErrorSnapshot? = null,
    val agentsError: SettingsSectionErrorSnapshot? = null,
    /**
     * Either [canEditMachine] or [canRemoveMachine]: the page shows its Actions section. The
     * server still enforces 403 on the API endpoints, so a permissive client cannot bypass it.
     */
    val canManageMachines: Boolean = false,
    /** Rename / description: creator, attaching user, or the `editMachines` capability (owner/admin). */
    val canEditMachine: Boolean = false,
    /** Delete: creator, attaching user, or the `removeMachines` capability (owner/admin). */
    val canRemoveMachine: Boolean = false
)

data class SettingsSectionErrorSnapshot(
    val code: String,
    val retryable: Boolean = false,
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null
)

data class SettingsComputerWorkspaceSnapshot(
    val id: String,
    val name: String,
    val path: String,
    val statusLabel: String? = null,
    val lastActiveLabel: String? = null,
    val totalSizeBytes: Long? = null,
    val fileCount: Int? = null
)

data class SettingsComputerAgentSnapshot(
    val id: String,
    val name: String,
    val statusLabel: String? = null,
    val ownerLabel: String? = null,
    val runtime: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val memberProfile: MemberProfile? = null
)

data class SettingsServerSnapshot(
    val profile: SettingsServerProfileSnapshot,
    val sections: List<SettingsServerSectionSnapshot>,
    val admins: List<SettingsServerAdminSnapshot> = emptyList(),
    val usageRows: List<SettingsPlanUsageSnapshot> = emptyList(),
    val pendingInvites: List<SettingsServerInviteSnapshot> = emptyList(),
    val joinLinks: List<SettingsServerJoinLinkSnapshot> = emptyList(),
    val agreement: SettingsServerAgreementSnapshot? = null,
    val onboarding: SettingsServerOnboardingSnapshot? = null,
    val translation: SettingsServerTranslationSnapshot? = null,
    val sidebarOrder: SettingsServerSidebarOrderSnapshot? = null,
    val systemChannels: SettingsServerSystemChannelSnapshot? = null
)

data class SettingsServerSystemChannelSnapshot(
    val allChannelId: String = "",
    val allChannelHidden: Boolean = false,
    val canManage: Boolean = false
)

data class SettingsServerProfileSnapshot(
    val name: String,
    val slug: String,
    val hideHumansFromMembers: Boolean = false,
    val canSave: Boolean = false
)

data class SettingsServerSectionSnapshot(
    val key: String,
    val title: StringDesc,
    val detail: String,
    val state: SettingsSectionAvailability = SettingsSectionAvailability.Unavailable,
    val errorPresentation: ErrorPresentation? = null
)

data class SettingsServerAdminSnapshot(
    val id: String,
    val name: String,
    val detail: String? = null,
    val role: String? = null
)

data class SettingsServerInviteSnapshot(
    val id: String,
    val email: String,
    val inviterName: String? = null,
    val createdAtLabel: String? = null
)

data class SettingsServerJoinLinkSnapshot(
    val id: String,
    val label: String,
    val url: String? = null,
    val maxUsesLabel: String? = null,
    val usageLabel: String? = null,
    val expiresAtLabel: String? = null
)

data class SettingsServerAgreementSnapshot(
    val enabled: Boolean,
    val title: String,
    val bodyMarkdown: String,
    val statusLabel: String,
    val detail: String,
    val canSave: Boolean = false
)

data class SettingsServerOnboardingSnapshot(
    val setupModalReminderOptOut: Boolean,
    val onboardingAgentId: String? = null,
    val agentAllChannelGreetingEnabled: Boolean = true,
    val agents: List<SettingsServerOnboardingAgentSnapshot> = emptyList(),
    val statusLabel: String,
    val detail: String,
    val canSave: Boolean = false
)

data class SettingsServerOnboardingAgentSnapshot(
    val id: String,
    val name: String,
    val displayName: String? = null
)

data class SettingsServerTranslationSnapshot(
    val translationEnabled: Boolean,
    val translationAvailable: Boolean,
    val canManageTranslation: Boolean,
    val statusLabel: String,
    val detail: String
)

@Serializable
data class SettingsServerSidebarOrderSnapshot(
    val channelOrder: List<String> = emptyList(),
    val agentOrder: List<String> = emptyList(),
    val dmOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmSortMode: String = "manual",
    val pinnedSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinned: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val hiddenDmIds: List<String> = emptyList(),
    val channelPanelTabOrder: List<String> = emptyList(),
    val agentPanelTabOrder: List<String> = emptyList(),
    // Web keeps the Joint Channels sort mode separate from Channels
    // (sidebar-order `jointChannelSortMode`).
    val jointChannelSortMode: String = "manual",
    // task #512: web "channel sections" port. Mirrors the wire fields of
    // GET/PATCH /servers/:id/sidebar-order (web packages/web/src/store/sidebarSections.ts).
    val customSections: List<SettingsServerSidebarCustomSection> = emptyList(),
    val sectionOrder: List<String> = emptyList(),
    val sectionPlacements: List<SettingsServerSidebarSectionPlacement> = emptyList(),
    val sectionsVersion: Long = 0L
)

@Serializable
data class SettingsServerSidebarPinnedItem(
    val kind: String,
    val id: String
)

@Serializable
data class SettingsServerSidebarCustomSection(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val sortMode: String = "manual"
)

@Serializable
data class SettingsServerSidebarSectionPlacement(
    val kind: String,
    val id: String,
    val sectionId: String,
    val position: Int
)

data class SettingsMobileSnapshot(
    val pushNotifications: SettingsMobileToggleSnapshot,
    val muteServer: SettingsMobileToggleSnapshot,
    val serverNotificationMode: SettingsMobileSelectionSnapshot =
        ServerPushMode.All.toSettingsMobileSelectionSnapshot(available = false),
    val pushMessagePreviews: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = true,
            available = false,
            statusLabel = "Unsupported",
            detail = "Message preview privacy is not available on this server."
        ),
    val sendTestEnabled: Boolean = false,
    val notificationPermission: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "Notification permission is not available on this platform."
        ),
    val notificationSettings: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "System notification settings are not available on this platform."
        ),
    val testNotification: SettingsMobileActionSnapshot =
        SettingsMobileActionSnapshot(
            available = false,
            statusLabel = "Unsupported",
            detail = "Test notifications are not available on this platform."
        ),
    val localePreference: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = AppLocale.Default.languageTag,
            selectedLabel = AppLocale.Default.englishLabel,
            statusLabel = AppLocale.Default.englishLabel,
            detail = "Stored locally on this device.",
            options =
                AppLocale.entries.map {
                    SettingsMobileSelectionOptionSnapshot(
                        key = it.languageTag,
                        label = it.englishLabel
                    )
                }
        ),
    val messageAlignmentPreference: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = MyMessageAlignmentPreference.Default.storageKey,
            selectedLabel = "Left",
            statusLabel = "Left",
            detail = "Stored locally on this device.",
            options =
                MyMessageAlignmentPreference.entries.map {
                    SettingsMobileSelectionOptionSnapshot(
                        key = it.storageKey,
                        label = it.name
                    )
                }
        ),
    val compactConsecutiveMessages: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = true,
            available = true,
            statusLabel = "Enabled",
            detail = "Hide repeated avatars for consecutive messages from the same sender. Message times appear while pressing a row."
        ),
    val longMessageCollapse: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED,
            available = true,
            statusLabel = if (RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED) "Enabled" else "Off",
            detail = RAFT_LONG_MESSAGE_COLLAPSE_SETTINGS_DETAIL
        ),
    val singleTapOpensThread: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Open a message Thread by tapping its body once. When off, use the replies action or message menu."
        ),
    val threadUnreadJump: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Show the floating unread-message jump control in Thread. Keep this off while the reading-window behavior is experimental."
        ),
    val newMessageMenu: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Long-press a message to show quick reactions and common actions in a compact two-row menu. Turn it off to use the classic message menu."
        ),
    val conversationForwardGesture: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Swipe left from the right edge of Home to reopen the most recent channel or direct message after going back."
        ),
    val foregroundMessageBanners: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Show an in-app banner for new messages while Raft is open. When off, foreground messages stay quiet."
        ),
    val activityTaskStatusBadges: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Show task status badges at the bottom of Activity rows. Keep this off for a quieter Activity list."
        ),
    val composerSendKey: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "New line",
            detail = "Use the keyboard action key to insert new lines. Turn on Send key to send messages from the keyboard."
        ),
    val multiShellHost: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Open different workspaces in separate Android app windows. Existing windows remain open until you close them."
        ),
    val serverDrawer: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Use the new multi-server left drawer on Home. When off, keep the current server switcher popover."
        ),
    val desktopModeOnLargeScreen: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = true,
            available = true,
            statusLabel = "Enabled",
            detail = "On large screens, show the left rail with the list and detail side by side. Turn off to keep the single-pane mobile layout with the bottom tab bar."
        ),
    val newDesignSettings: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = true,
            statusLabel = "Off",
            detail = "Open the new standalone channel settings page. When off, keep the current Thread settings dialog."
        ),
    val homeServerAvatar: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = DEFAULT_HOME_SERVER_AVATAR_ENABLED,
            available = true,
            statusLabel = if (DEFAULT_HOME_SERVER_AVATAR_ENABLED) "Enabled" else "Off",
            detail = "Show the current workspace avatar in the Home header. When off, keep the existing compact title badge."
        ),
    /** Task #154: effective value (user choice, else the current server's flag); see RaftCurlSocketIoPolicy. */
    val realtimeCurlSocketIo: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = false,
            statusLabel = "Unsupported",
            detail = "Realtime over the new network stack (curl) is not available on this platform."
        ),
    /** Bottom-up thread list: effective value (user choice, else the flag default); see ThreadReverseLayoutPreferenceStore. */
    val threadReverseLayout: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = false,
            statusLabel = "Unsupported",
            detail = "The bottom-up message list is not available on this build."
        ),
    /** Native pull-to-refresh switch (off = Raft's custom one); see RaftNativePullToRefreshPreferenceStore. */
    val nativePullToRefresh: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = RAFT_NATIVE_PULL_TO_REFRESH_DEFAULT,
            available = false,
            statusLabel = "Off",
            detail = "Native pull-to-refresh is not available on this build."
        ),
    /** Task #3: effective value (user choice, else the server-sent transport variant); see RaftHttp3Policy. */
    val http3: SettingsMobileToggleSnapshot =
        SettingsMobileToggleSnapshot(
            enabled = false,
            available = false,
            statusLabel = "Unsupported",
            detail = "HTTP/3 is not available on this build."
        ),
    val linkOpenMode: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = LinkOpenMode.Default.storageKey,
            selectedLabel = "Default browser",
            statusLabel = "Default browser",
            detail = "Choose whether web links open in the default browser or inside Raft.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot(
                        key = LinkOpenMode.SystemBrowser.storageKey,
                        label = "Default browser"
                    ),
                    SettingsMobileSelectionOptionSnapshot(
                        key = LinkOpenMode.InAppBrowser.storageKey,
                        label = "In-app browser"
                    )
                )
        )
)

enum class ServerPushMode(val wireName: String) {
    All("all"),
    Mentions("mentions"),
    None("none");

    companion object {
        fun fromWire(value: String?): ServerPushMode? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PushPreviewMode(val wireName: String) {
    Full("full"),
    Private("private");

    companion object {
        fun fromWire(value: String?): PushPreviewMode? = entries.firstOrNull { it.wireName == value }
    }
}

fun ServerPushMode.toSettingsMobileSelectionSnapshot(
    available: Boolean = true,
    mentionsSupported: Boolean = true,
    detail: String = "Choose which messages from the selected workspace can send push notifications."
): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = wireName,
        selectedLabel = wireName,
        statusLabel = wireName,
        detail = detail,
        options =
            ServerPushMode.entries.filter { mode -> mentionsSupported || mode != ServerPushMode.Mentions }.map { mode ->
                SettingsMobileSelectionOptionSnapshot(key = mode.wireName, label = mode.wireName)
            },
        available = available
    )

data class SettingsMobileToggleSnapshot(
    val enabled: Boolean,
    val available: Boolean,
    val statusLabel: String,
    val detail: String,
    val errorPresentation: ErrorPresentation? = null
)

data class SettingsMobileActionSnapshot(
    val available: Boolean,
    val statusLabel: String,
    val detail: String
)

data class SettingsMobileSelectionSnapshot(
    val selectedKey: String,
    val selectedLabel: String,
    val statusLabel: String,
    val detail: String,
    val options: List<SettingsMobileSelectionOptionSnapshot>,
    val available: Boolean = true
)

data class SettingsMobileMultiSelectionSnapshot(
    val selectedKeys: Set<String>,
    val statusLabel: String,
    val detail: String,
    val options: List<SettingsMobileSelectionOptionSnapshot>,
    val available: Boolean = true
)

data class SettingsMobileSelectionOptionSnapshot(
    val key: String,
    val label: String
)

data class SettingsLanguageRegionSnapshot(
    val serverTranslation: SettingsMobileToggleSnapshot,
    val autoTranslation: SettingsMobileToggleSnapshot,
    val browserLanguage: SettingsMobileSelectionSnapshot,
    val timezone: SettingsMobileSelectionSnapshot,
    val timeFormat: SettingsMobileSelectionSnapshot,
    val translationDisplay: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = "manual",
            selectedLabel = "Manual",
            statusLabel = "Manual",
            detail = "Stored locally on this device.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot("automatic", "Automatic"),
                    SettingsMobileSelectionOptionSnapshot("manual", "Manual"),
                    SettingsMobileSelectionOptionSnapshot("original", "Original")
                )
        )
)

data class SettingsLanguageRegionAccountPreferences(
    val preferredLanguage: String? = null,
    val preferredTimezone: String? = null,
    val autoTranslationEnabled: Boolean = true,
    val preferredTimeFormat: String? = null,
    val preferredTranslationDisplay: String? = null
)

data class SettingsLanguageRegionPreferenceUpdateRequest(
    val preferredLanguage: String? = null,
    val clearPreferredLanguage: Boolean = false,
    val preferredTimezone: String? = null,
    val clearPreferredTimezone: Boolean = false,
    val autoTranslationEnabled: Boolean? = null,
    val preferredTimeFormat: String? = null,
    val clearPreferredTimeFormat: Boolean = false,
    val preferredTranslationDisplay: String? = null
)

data class SettingsAppearanceSnapshot(
    val messageBodyFontSize: SettingsMobileSelectionSnapshot =
        SettingsMobileSelectionSnapshot(
            selectedKey = "md",
            selectedLabel = "Medium",
            statusLabel = "Medium",
            detail = "Adjusts message body text only.",
            options =
                listOf(
                    SettingsMobileSelectionOptionSnapshot("sm", "Small"),
                    SettingsMobileSelectionOptionSnapshot("md", "Medium"),
                    SettingsMobileSelectionOptionSnapshot("lg", "Large")
                )
        )
)

data class SettingsAppearanceUpdateRequest(
    val preferredMessageBodyFontSize: String
)

enum class SettingsSectionAvailability {
    Available,
    Empty,
    Unavailable,
    NoPermission
}

@Serializable
data class PendingMentionAction(
    val resolutionId: String,
    val messageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val channelName: String? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val targetAvatarUrl: String? = null,
    val reason: String = "",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

data class PendingMentionActionExecuteResult(
    val resolutionId: String,
    val status: String,
    val reason: String? = null
)

data class SendMessageResult(
    val message: Message,
    val pendingMentionActions: List<PendingMentionAction> = emptyList()
)

data class ThreadActionResult(
    val threadChannelId: ChannelId,
    val changed: Boolean,
    val parentMessageId: MessageId? = null,
    val replyCount: Int = 0
)

data class TaskActionResult(
    val task: TaskSummary
)

data class TaskConvertResult(
    val task: TaskSummary?
)
