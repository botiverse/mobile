package build.raft.app.model

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.network.sync.ReplyPreview

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Session and Server Models
@Serializable
data class SessionSnapshot(
    val currentServerId: ServerId,
    val currentMember: MemberProfile,
    val availableServers: List<ServerSummary>
)

@Serializable
data class ServerSummary(
    val id: ServerId,
    val name: String,
    val slug: String? = null,
    val role: String? = null,
    val plan: String? = null,
    val onboardingAgentId: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val unreadCount: Int = 0,
    val permissions: ServerPermissions = ServerPermissions(),
    val hideHumansFromMembers: Boolean = false
)

@Serializable
data class ServerPermissions(
    val canCreateChannel: Boolean = true,
    val canInviteMember: Boolean = true,
    val canManageSettings: Boolean = false,
    val canManageMachines: Boolean = false
)

internal fun serverPermissionsForRole(role: String?): ServerPermissions {
    val canAdministerServer = role == "owner" || role == "admin"
    return ServerPermissions(
        canManageSettings = canAdministerServer,
        canManageMachines = canAdministerServer
    )
}

fun ServerSummary.hidesHumanDirectoryForViewer(): Boolean =
    hideHumansFromMembers && role != "owner" && role != "admin"

// Conversation Models
@Serializable
enum class ConversationKind {
    Channel,
    Joint,
    Thread,
    DirectMessage,
    Inbox,
    System
}

@Serializable
data class ThreadConversation(
    val parentChannelId: ChannelId,
    val parentMessageId: MessageId,
    val streamChannelId: ChannelId? = null,
    val parentSurfaceKind: String? = null,
    val parentChannelKind: String? = null
)

/**
 * Per-receiver notification preference for channel activity promotion.
 *
 * The server resolves these fields for the current receiver; callers should
 * treat the three wire fields as one domain value instead of independent row
 * flags. This mirrors the web channel-store `mute` gate while keeping scalar
 * read projections available for existing UI surfaces.
 */
@Serializable
data class ChannelActivityNotificationPreference(
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false
) {
    companion object {
        val Unsupported = ChannelActivityNotificationPreference()
    }
}

@Serializable
data class ConversationSummary(
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val channelType: String? = null,
    val thread: ThreadConversation? = null,
    val kind: ConversationKind,
    val name: String,
    val subtitle: String? = null,
    val directMessagePeerType: String? = null,
    val directMessagePeerAvatarWireValue: String? = null,
    val unreadCount: Int = 0,
    val unreadCountKnown: Boolean = false,
    val mentionCount: Int = 0,
    val mentionCountKnown: Boolean = false,
    val lastMessageId: MessageId? = null,
    val lastMessageSeq: Long? = null,
    val firstUnreadMessageId: MessageId? = null,
    val readStateMaxReadSeq: Long? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null,
    val presenceMemberId: MemberId? = null,
    val hasDraft: Boolean = false,
    val hasDraftKnown: Boolean = false,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val memberCount: Int? = null,
    val jointChannelId: String? = null,
    val jointRole: String? = null,
    val jointPeerServerId: ServerId? = null,
    val jointPeerServerName: String? = null,
    val jointPeerServerSlug: String? = null,
    val jointPeerStatus: String? = null,
    val activityNotificationPreference: ChannelActivityNotificationPreference = ChannelActivityNotificationPreference.Unsupported,
    val permissions: ConversationPermissions = ConversationPermissions()
) {
    val activityMuted: Boolean
        get() = activityNotificationPreference.activityMuted
    val muteFromSeq: Long?
        get() = activityNotificationPreference.muteFromSeq
    val activityMuteSupported: Boolean
        get() = activityNotificationPreference.activityMuteSupported
}

@Serializable
data class ConversationPermissions(
    val canRead: Boolean = true,
    val canSend: Boolean = true,
    val canAttach: Boolean = true,
    val canCreateTask: Boolean = true
)

// Member Models
@Serializable
enum class MemberKind {
    Human,
    Agent,
    System,
    /** Slack-bridge / provider external projection: never a Raft member, never
     *  in member-detail or roster flows; surfaces read [Message.externalAuthor]. */
    External,
}

/**
 * Immutable, human-facing attribution frozen on a `senderType=external_projection`
 * message (Web parity: `ExternalMessageAuthorProjection` in
 * `packages/shared/src/externalProjection.ts`). It is display/search data only —
 * never a Raft principal, member, task assignee, or mention handle.
 */
@Serializable
data class ExternalMessageAuthor(
    val displayName: String,
    val provider: String,
    val actorKind: String,
    val avatarUrl: String? = null,
    val avatarDigest: String? = null,
    val projectionId: String? = null,
    val externalActorId: String? = null,
    val externalConversationId: String? = null,
    val externalMessageId: String? = null,
    val workspaceId: String? = null,
    val installId: String? = null,
    val appRegistrationId: String? = null,
    val actorProjectionRevision: Long? = null,
)

/** Provider subtitle label, web parity (`formatProviderLabel` in MessageItem.tsx):
 *  "slack" renders as "Slack", other providers pass through verbatim. */
fun ExternalMessageAuthor.providerLabel(): String =
    provider.trim().let { if (it.equals("slack", ignoreCase = true)) "Slack" else it }

@Serializable
enum class Presence {
    Active,
    Away,
    Offline
}

/** Wire runtime id of external agents (parity with shared `EXTERNAL_AGENT_RUNTIME_ID`). */
const val ExternalAgentRuntimeId: String = "external"

/** Parity with shared `isExternalAgentRuntime`: exact match, no trimming or casing. */
fun isExternalAgentRuntime(runtime: String?): Boolean = runtime == ExternalAgentRuntimeId

/** Web keeps the projected boolean and the runtime sentinel as parallel sources. */
fun isExternalAgent(external: Boolean?, runtime: String?): Boolean =
    external == true || isExternalAgentRuntime(runtime)

@Serializable
enum class AgentStatus {
    Online,
    Working,
    Thinking,
    Idle,
    Disconnected,
    Offline,
    Error;

    val label: String
        get() = when (this) {
            Online -> "Online"
            Working -> "Working"
            Thinking -> "Thinking"
            Idle -> "Idle"
            Disconnected -> "Disconnected"
            Offline -> "Offline"
            Error -> "Error"
        }

    val presence: Presence
        get() = when (this) {
            Online, Working, Thinking -> Presence.Active
            Idle -> Presence.Away
            Disconnected, Offline, Error -> Presence.Offline
        }

    companion object {
        fun fromWireValue(value: String?): AgentStatus? =
            when (value?.trim()?.lowercase()?.replace('-', '_')) {
                "online", "active", "available", "connected" -> Online
                "working", "running", "running_extension", "busy", "compacting", "compacting_context" -> Working
                "thinking" -> Thinking
                "idle", "away" -> Idle
                "disconnected", "not_connected", "notconnected" -> Disconnected
                "offline", "inactive" -> Offline
                "error", "failed", "failure" -> Error
                else -> null
            }

        fun fromAgentActivityWireValue(value: String?): AgentStatus? =
            when (value?.trim()?.lowercase()?.replace('-', '_')) {
                "online" -> Online
                "thinking" -> Thinking
                "working" -> Working
                "error" -> Error
                "offline" -> Offline
                else -> null
            }
    }
}

// Avatar Models
@Serializable
sealed class AvatarReference {
    abstract val wireValue: String

    @Serializable
    data class Url(val url: String) : AvatarReference() {
        override val wireValue: String = url
    }

    @Serializable
    data class Pixel(val key: String) : AvatarReference() {
        override val wireValue: String = "pixel:$key"
    }

    @Serializable
    data class Random(val seed: String) : AvatarReference() {
        override val wireValue: String = "pixel:random:$seed"
    }

    companion object {
        fun fromWireValue(value: String?): AvatarReference? {
            val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return when {
                normalized.startsWith("pixel:random:") ->
                    normalized
                        .removePrefix("pixel:random:")
                        .takeIf { it.isNotBlank() }
                        ?.let(::Random)
                normalized.startsWith("pixel:") ->
                    Pixel(normalized.removePrefix("pixel:").ifBlank { "robot" })
                normalized.startsWith("http://") || normalized.startsWith("https://") ->
                    normalized.takeUnless { it.isLegacyGravatarAvatarUrl() }?.let(::Url)
                else -> null
            }
        }
    }
}

@Serializable
data class MemberProfile(
    val id: MemberId,
    val displayName: String,
    val handle: String,
    val kind: MemberKind,
    val description: String? = null,
    val roleDescription: String? = null,
    val serverRole: String? = null,
    val email: String? = null,
    val gravatarHash: String? = null,
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val presence: Presence = Presence.Offline,
    val agentStatus: AgentStatus? = null,
    /**
     * External agents (runtime `external`) have no managed liveness. Web keeps
     * `agent.external === true || runtime === "external"` as parallel sources
     * (agentStore.ts resolveAgentDisplayState); producers fold both via
     * [isExternalAgent] so a payload missing one key cannot silently demote the
     * agent to a managed one.
     *
     * Defaulted on purpose — boundary (task #1119): every producer whose wire
     * payload carries `external`/`runtime` sets it explicitly
     * (MemberAgentParser agent mappers, [AgentProfile.toMemberProfile],
     * MembersPage static conversions, Settings JSON profiles). The remaining
     * constructors are identity seeds (message authors, DM peers, task
     * assignees, auth session user, visual fixtures) whose payloads carry
     * neither key; they are never a status source — the status dot reads the
     * roster/detail profile through SharedServerMembersStore, where the flag
     * is required downstream ([build.raft.app.data.SharedServerMemberStatus]).
     */
    val isExternal: Boolean = false,
    /**
     * Agent model id (e.g. `claude-sonnet-4-6`). Mirrors Web `agent.model`.
     * Resolved at render time via the members store (Web `store.agents`
     * lookup by `senderId`); paired with [roleDescription] (which carries
     * the runtime) to produce `getModelLabel(runtime, model)`.
     * `null` on humans, system senders, and identity seeds whose payloads
     * never carried a model — the header label stays absent, never a raw id.
     */
    val model: String? = null,
    val joinedAt: String? = null,
    val membershipStatus: String? = null,
    val agentDeletedAt: String? = null,
    /**
     * External agents only: `lastSeenAt` from `GET /agents` — when the agent's
     * credential was last used (ISO-8601). Presence is derived from it:
     * online while `now - lastSeenAt < 120s` (web `EXTERNAL_AGENT_ONLINE_WINDOW_MS`),
     * otherwise "Last active {time}". Realtime `agent:seen` updates it.
     */
    val lastSeenAt: String? = null,
    val createdAgents: List<AgentProfile> = emptyList(),
    val permissionBadges: List<String> = emptyList(),
    val serverId: ServerId? = null,
    val serverName: String? = null,
    val serverSlug: String? = null
)

@Serializable
data class AgentProfile(
    val id: MemberId,
    val userId: String,
    val displayName: String,
    val handle: String,
    val description: String? = null,
    val roleDescription: String? = null,
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val presence: Presence = Presence.Offline,
    val agentStatus: AgentStatus? = null,
    val statusLabel: String? = null,
    val runtime: String? = null,
    val isExternal: Boolean = false,
    val model: String? = null,
    val reasoning: String? = null,
    val hasOnlineMachine: Boolean = false,
    val permissionBadges: List<String> = emptyList()
)

// Message Models

/**
 * Server-provided conversation identity attached to realtime `message:*`
 * events (server task #86, mobile task #425). Identity only, no display
 * state. Null on payloads from servers that pre-date the field; consumers
 * must keep the projection-wait path as fallback while those exist.
 */
@Serializable
data class MessageConversationContext(
    val channelKind: ConversationKind? = null,
    val parentMessageId: MessageId? = null,
    val parentChannelId: ChannelId? = null,
    val parentChannelKind: ConversationKind? = null
)

@Serializable
data class Message(
    val id: MessageId,
    val threadId: ThreadId,
    val channelId: ChannelId? = null,
    val threadChannelId: ChannelId? = null,
    val seq: Long? = null,
    val author: MemberProfile,
    val timestampLabel: String,
    val body: String,
    val messageType: MessageType = MessageType.Chat,
    val systemEventType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val deliveryState: MessageDeliveryState = MessageDeliveryState.Sent,
    val task: TaskSummary? = null,
    val replyCount: Int = 0,
    val unreadReplyCount: Int = 0,
    val firstUnreadReplyMessageId: MessageId? = null,
    val isThreadFollowed: Boolean = false,
    val isSaved: Boolean = false,
    val parentMessageId: MessageId? = null,
    val updatedAtLabel: String? = null,
    val reactions: List<MessageReaction>? = null,
    val senderMembershipStatus: String? = null,
    val commentRef: MessageCommentRef? = null,
    val forwardBundle: ForwardMessageBundle? = null,
    val conversationContext: MessageConversationContext? = null,
    /** Set when `author.kind == MemberKind.External` (senderType=external_projection). */
    val externalAuthor: ExternalMessageAuthor? = null,
    val randomId: String? = null,
    /** Canonical numeric creation time used for ordering; never derived from localized display text. */
    @Transient val createdAtEpochMillis: Long? = null,
)

@Serializable
data class ForwardMessageBundle(
    val id: String? = null,
    val title: String = "",
    val sourceTitle: String = "",
    val totalCount: Int? = null,
    val messages: List<ForwardMessageItem> = emptyList()
)

@Serializable
data class ForwardMessageItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorKind: String = "",
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    val timestampLabel: String = "",
    val body: String = "",
    val attachmentCount: Int = 0,
    // Snapshot metadata (filename + mime) when the wire provides it; the web
    // ForwardedBundleCard renders these as per-file chips. Legacy payloads
    // carry only a count and leave this empty.
    val attachments: List<ForwardMessageAttachment> = emptyList()
)

@Serializable
data class ForwardMessageAttachment(
    val filename: String = "",
    val mimeType: String? = null
)

@Serializable
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList()
)

@Serializable
enum class MessageType {
    Chat,
    System
}

@Serializable
enum class MessageDeliveryState {
    Sending,
    Sent,
    Failed
}

@Serializable
data class Attachment(
    val id: AttachmentId,
    val name: String,
    val mimeType: String,
    /** Compatibility-only display value for old snapshots/callers that do not carry [sizeBytes]. */
    val sizeLabel: String,
    /** Raw wire fact. Presentation layers format this value for display. */
    val sizeBytes: Long = 0L,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val commentCount: Int = 0
)

@Serializable
data class MessageCommentRef(
    val attachmentId: AttachmentId,
    val filename: String,
    val hostMessageId: MessageId? = null,
    val hostSource: MessageCommentHostSource? = null,
    val anchorLabel: String? = null
)

@Serializable
data class MessageCommentHostSource(
    val type: String,
    val routeKind: String,
    val channelId: ChannelId,
    val parentMessageId: MessageId? = null,
    val threadChannelId: ChannelId? = null,
    val rootThreadChannelId: ChannelId? = null
)

// Task Models
@Serializable
enum class TaskStatus {
    Todo,
    InProgress,
    InReview,
    Done,
    Closed;

    fun toWireValue(): String =
        when (this) {
            Todo -> "todo"
            InProgress -> "in_progress"
            InReview -> "in_review"
            Done -> "done"
            Closed -> "closed"
        }

    companion object {
        fun fromWireValue(value: String?): TaskStatus? =
            when (value?.trim()?.lowercase()) {
                "todo" -> Todo
                "in_progress" -> InProgress
                "in_review" -> InReview
                "done" -> Done
                "closed" -> Closed
                else -> null
            }
    }
}

@Serializable
data class TaskSummary(
    val id: TaskId,
    val title: String,
    val status: TaskStatus,
    val taskNumber: Int? = null,
    val messageId: MessageId? = null,
    val parentMessageId: MessageId? = null,
    val channelId: ChannelId? = null,
    val threadId: ThreadId? = null,
    val isThreadTask: Boolean = false,
    val assignee: MemberProfile? = null,
    /** Task creator (sender of the original task message). Mirrors React `createdBy*`
     *  fields so the Tasks panel can show a Creator filter chip. Nullable for
     *  backward-compat with cached snapshots that pre-date this field. */
    val creator: MemberProfile? = null,
    val channelName: String? = null,
    val updatedAtLabel: String,
    val canClaim: Boolean = true,
    val description: String? = null,
    /** Monotonic revision of the canonical task fact. Projection-local channel fields are not part of it. */
    val revision: Long? = null
)

// Inbox Models
@Serializable
enum class InboxItemKind {
    Channel,
    DirectMessage,
    Thread,
    MentionAction,
    System
}

// Realtime Event Models
@Serializable
sealed interface RealtimeEvent

@Serializable
data class MessageNew(
    val message: Message,
    // In-process carry (sync_core_messages_v0): the raw-wire canonical decode produced ONCE by the
    // flag-gated projection gateway (MessageShadowObserver), threaded to the SharedRealtimeDomainEventOwner
    // shadow fold+persist lane so it is not re-decoded there. @Transient — never serialized (not part of the
    // wire/cache shape), so a deserialized event carries null and the shadow lane simply skips it (fail-open).
    @Transient val decodedCanonical: CanonicalMessageCarry? = null,
) : RealtimeEvent

@Serializable
data class MessageUpdated(
    val message: Message,
    @Transient val decodedCanonical: CanonicalMessageCarry? = null,
) : RealtimeEvent

@Serializable
data class DirectMessageCreated(val channelId: ChannelId) : RealtimeEvent

@Serializable
data class ThreadUpdated(
    val parentMessageId: MessageId,
    val threadChannelId: ChannelId,
    val replyCount: Int,
    val lastReplyAt: String?,
    val participantIds: List<MemberId>,
    val unreadCount: Int? = null,
    val firstUnreadMessageId: MessageId? = null,
    /** Server-derived bounded reply-window delta; not canonical message authority. */
    val latestReply: ReplyPreview? = null,
    /** Existing replies SyncCore v1 producer envelope; validated again against local identity by the domain host. */
    val syncCoreReplyWindow: build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope? = null,
) : RealtimeEvent

@Serializable
data class TaskCreated(val tasks: List<TaskSummary>) : RealtimeEvent

@Serializable
data class TaskUpdated(val task: TaskSummary) : RealtimeEvent

@Serializable
data class TaskDeleted(val taskId: TaskId) : RealtimeEvent

@Serializable
data class RealtimeAgentActivityDetail(
    val label: String,
    val value: String
)

@Serializable
data class RealtimeAgentActivityEntry(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val timestamp: String? = null,
    val details: List<RealtimeAgentActivityDetail> = emptyList(),
    val toolName: String? = null,
    val toolInput: String? = null,
    val statusActivity: String? = null,
    val statusDetail: String? = null,
    val isSubagent: Boolean = false,
    val subagentType: String? = null,
    val expandable: Boolean = false,
    val refTextEnabled: Boolean = false
)

@Serializable
data class MemberPresenceUpdated(
    val memberId: MemberId,
    val presence: Presence,
    val kind: MemberKind? = null,
    val agentStatus: AgentStatus? = null,
    val statusLabel: String? = null,
    val serverSeq: Long? = null,
    val activityEntries: List<RealtimeAgentActivityEntry> = emptyList(),
    /**
     * `agent:seen` carries the server's `lastSeenAt` (ISO-8601) for an external
     * agent — the credential-use timestamp that drives the online window.
     * Seq-less and merged onto the registered presence fact (see
     * [ProcessMemberPresenceRegistry]): it updates presence to Active and
     * this field, never displacing a seq-carrying fact.
     */
    val lastSeenAt: String? = null
) : RealtimeEvent

@Serializable
data class RealtimeRefreshRequired(
    val scope: RealtimeRefreshScope,
    val reason: String,
    val serverId: ServerId? = null,
    val channelId: ChannelId? = null,
    val memberId: MemberId? = null,
    val subjectId: String? = null
) : RealtimeEvent

@Serializable
data class ServerMembershipRemoved(
    val serverId: ServerId
) : RealtimeEvent

@Serializable
data class ServerDeleted(
    val serverId: ServerId
) : RealtimeEvent

@Serializable
data class ServerPlanUpdated(
    val serverId: ServerId,
    val plan: String
) : RealtimeEvent

@Serializable
data class AgentCreated(
    val agent: AgentProfile
) : RealtimeEvent

@Serializable
enum class RealtimeRefreshScope {
    Home,
    ChannelMembers,
    ServerMembers,
    Agents,
    Machines,
    Announcements,
    Reminders,
    Settings
}

@Serializable
data class SyncResumeResponse(
    val messages: List<Message>,
    val currentSeq: Long,
    val hasMore: Boolean
) : RealtimeEvent

@Serializable
data class Heartbeat(val seq: Long) : RealtimeEvent

/**
 * RFC 041 per-receiver read-state push (server PR #4175,
 * `routes/channels.ts::emitReadStateUpdated[Bulk]`). Identity-only per §2.3:
 * the payload carries the watermark (`maxReadSeq`) and its version, never
 * derived display state. The receiving member is implied by the user-room the
 * socket is joined to, so no memberId travels on the wire; `readStateVersion`
 * is per-(user, channel) — `user_channel_read_cursors.read_state_version`,
 * PK (user_id, channel_id) — so version gating must key on the scope, not the
 * family (see [build.raft.app.data.realtime.PerReceiverStateVersionStore]).
 */
@Serializable
data class PerReceiverReadStateScope(
    val scopeId: ChannelId,
    val maxReadSeq: Long?,
    val readStateVersion: Long
)

@Serializable
data class PerReceiverReadStateChanged(
    val serverId: ServerId? = null,
    val scopes: List<PerReceiverReadStateScope>
) : RealtimeEvent

/**
 * RFC 041 per-receiver notification-prefs push (server PR #4175,
 * `routes/channels.ts::emitNotificationPrefsUpdated`). `prefsVersion` is
 * per-(user, scope) like the read-state version.
 */
@Serializable
data class PerReceiverNotificationPrefsChanged(
    val serverId: ServerId? = null,
    val scopeId: ChannelId,
    val preference: ChannelActivityNotificationPreference,
    val prefsVersion: Long
) : RealtimeEvent

@Serializable
data class ServerNotificationPreferencesChanged(
    val serverId: ServerId,
    val serverPushMode: String,
    val prefsVersion: Long,
    val pushPreviewMode: String? = null
) : RealtimeEvent

/**
 * Per-receiver pinned sidebar update. The wire event is emitted to the
 * receiving user's room; the receiving member is inferred from the active
 * session and [pinnedVersion] is gated as a user-global server/member scope.
 */
@Serializable
data class PerReceiverPinnedChanged(
    val serverId: ServerId? = null,
    val pinned: List<SettingsServerSidebarPinnedItem>,
    val pinnedVersion: Long
) : RealtimeEvent

/**
 * task #459: server-qualified notification event (server PR #4233 mirror of
 * the web-push targets to `user:<id>` rooms). The server has already decided
 * this message deserves attention (DM membership, per-channel notification
 * scope incl. mentions-only, thread followers, mute exclusion) — the client
 * never re-derives eligibility. Identity is typed (same seven-field family
 * as MessageConversationContext); display strings are server-formatted.
 */
@Serializable
data class NotificationPushReceived(
    val serverId: ServerId,
    val kind: ConversationKind,
    val channelId: ChannelId? = null,
    val threadId: ChannelId? = null,
    val parentChannelId: ChannelId? = null,
    val parentMessageId: MessageId? = null,
    val messageId: MessageId,
    val title: String,
    val body: String,
    val tag: String,
    val serverName: String? = null,
    val channelName: String? = null,
    val parentChannelKind: ConversationKind? = null,
    val senderId: MemberId? = null,
    val senderKind: MemberKind? = null,
    val senderName: String? = null,
    val messagePreview: String? = null,
    val mentioned: Boolean? = null
) : RealtimeEvent

// Settings Models
@Serializable
data class SettingsSummary(
    val server: ServerSummary,
    val notificationEnabled: Boolean,
    val compactModeEnabled: Boolean,
    val manageableSections: List<String>
)
