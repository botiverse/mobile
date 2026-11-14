package build.raft.app.model

import build.raft.app.isRaftHumanPixelAvatarUrl
import build.raft.app.toRaftAvatarLabel
import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.network.sync.ReplyPreview
import build.raft.designsystem.tokens.RaftThemeId

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
    val hideHumansFromMembers: Boolean = false,
    /** See [build.raft.app.data.StoredAuthServer.publicPageLive]. */
    val publicPageLive: Boolean = false,
    /**
     * The member's own theme choice on this server; null is the default, Raft Yellow. This is
     * the stored choice, not what is shown: a Pro theme on a Free server shows as Raft Yellow
     * (see [themeLook]). From [build.raft.app.data.StoredAuthServer.theme].
     */
    @Serializable(with = RaftThemeIdWireSerializer::class)
    val theme: RaftThemeId? = null,
    /** Whether the member keeps the theme's decorations on; on by default. */
    val themeDecor: Boolean = true
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
        // Web `serverPermissions.ts`: `inviteMembers` is owner/admin only.
        canInviteMember = canAdministerServer,
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
    System,
    /**
     * Task v2: a task's own conversation (slock `channels.type = 'task'`). A flat stream with no
     * root message whose parent is the task (and the task's channel); it renders on the channel
     * surface with route channel kind `task`.
     */
    Task
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
    val jointBillingLocked: Boolean? = null,
    val jointOverLimitGraceEndsAt: String? = null,
    val bridgeProvider: String? = null,
    /**
     * Channel row `guestVisible` (see [build.raft.app.data.HomeChannelWireDto.guestVisible]).
     * Only meaningful for `type == "channel"`; never true for DMs / threads.
     */
    val guestVisible: Boolean = false,
    /** Channel row `guestJoinable` (see [build.raft.app.data.HomeChannelWireDto.guestJoinable]). */
    val guestJoinable: Boolean = false,
    /** Channel row `hasExternalRelation` (see [build.raft.app.data.HomeChannelWireDto.hasExternalRelation]). */
    val hasExternalRelation: Boolean = false,
    val activityNotificationPreference: ChannelActivityNotificationPreference = ChannelActivityNotificationPreference.Unsupported,
    val permissions: ConversationPermissions = ConversationPermissions(),
    /**
     * Server-resolved channel-level capabilities for the current receiver
     * (web `channelCapabilities` on the channel record). `null` when the wire
     * payload never carried the object — consumers must treat that as "unknown"
     * and hide capability-gated affordances rather than assume permission.
     */
    val channelCapabilities: ChannelCapabilities? = null,
    /**
     * Agent Lounge v0: the agent this channel is the Lounge of (web `Channel.loungeAgentId`).
     * Null: not a Lounge. A Lounge is a plain channel on the wire, kept out of every channel list.
     */
    val loungeAgentId: String? = null
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

/**
 * Channel-scoped capabilities resolved server-side for the current member
 * (web parity: `channelCapabilities` consumed by `canUseChannelMemberAction` in
 * ChannelMembers.tsx). Unlike [ConversationPermissions], every field is
 * nullable: `null` means the server did not resolve it, which gates the
 * affordance OFF — the members page only shows add/remove when the value is
 * explicitly `true`.
 */
@Serializable
data class ChannelCapabilities(
    val addChannelMembers: Boolean? = null,
    val removeChannelMembers: Boolean? = null,
    val changeChannelMemberRoles: Boolean? = null,
    /** Channel settings (web EditChannelDialog `effectiveCapabilities`): rename / describe. */
    val editChannelMetadata: Boolean? = null,
    val archiveChannels: Boolean? = null,
    val deleteChannels: Boolean? = null,
    val changeChannelVisibility: Boolean? = null,
    val manageGuestAccess: Boolean? = null,
    val federateChannels: Boolean? = null,
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
 * RFC 077: the personal assistant that sent a message for its owner (the
 * message's `senderType=user` sender). Projection of the server's
 * `DelegatedByLabel` the same way web `delegatedMessage.ts` `readDelegatedBy`
 * reads it: [name] is the display name, falling back to the handle; a label with
 * no name left reads as [deleted] so "sent by" never goes blank.
 */
@Serializable
data class MessageDelegatedBy(
    /** The assistant's agent id; also the grouping key ([delegatedGroupKey]). */
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    /** The assistant has been deleted: "sent by a deleted assistant". */
    val deleted: Boolean = false,
)

/** Web `delegatedGroupKey`: a delegated message never continues its sender's own run. */
fun MessageDelegatedBy?.delegatedGroupKey(): String = this?.id.orEmpty()

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
    /** The external workspace's name ("Acme" for a Slack workspace); the identity card's "From …". */
    val workspaceName: String? = null,
    /**
     * The wire carried no display name (no `externalAuthor.displayName`, no `senderName`):
     * [displayName] is an English stand-in, and every surface that shows the sender name shows
     * the localized "External user" instead (`shownAuthorName()` / `localizedAuthorName()`).
     */
    val displayNameIsFallback: Boolean = false,
)

/** Provider subtitle label, web parity (`formatExternalProviderLabel`):
 *  "slack" renders as "Slack", other providers pass through verbatim, blank is null. */
fun ExternalMessageAuthor.providerLabel(): String? =
    provider.trim().takeIf { it.isNotEmpty() }?.let { if (it.equals("slack", ignoreCase = true)) "Slack" else it }

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

    /**
     * A person's built pixel avatar, `pixel-human:v1:...` as stored (web `HumanPixelAvatar`).
     * Like [Url], the person's own concrete avatar, not a render fallback.
     */
    @Serializable
    data class HumanPixel(val value: String) : AvatarReference() {
        override val wireValue: String = value
    }

    /** The person's or agent's own avatar (a photo or a built pixel avatar), not a render fallback. */
    val isConcrete: Boolean
        get() = this is Url || this is HumanPixel

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
                isRaftHumanPixelAvatarUrl(normalized) -> HumanPixel(normalized)
                else -> null
            }
        }
    }
}

/** A server a person participates through in a channel (server `ChannelMemberServer`). */
@Serializable
data class MemberServerRef(
    val id: String,
    val name: String = "",
    val slug: String = "",
)

/**
 * A Guest's bound channel (slock shared `GuestChannelField.guestChannel`): set only for a Guest,
 * and only for a viewer who may read that channel, so a private channel's name never leaks.
 */
@Serializable
data class MemberGuestChannelRef(
    val id: String,
    val name: String = "",
)

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
    /**
     * The person's one avatar (uploaded image URL or pixel face). There is deliberately no second
     * `avatarUrl` field: a URL-only copy was null for pixel avatars, and screens reading it drew
     * initials. Render through [avatarWireValueForRenderedAvatar] / `RaftMemberAvatar`.
     */
    val avatar: AvatarReference? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
    val presence: Presence = Presence.Offline,
    val agentStatus: AgentStatus? = null,
    /**
     * The agent row's raw lifecycle `status` (`active` / `inactive` / `stopped` ...), trimmed and
     * lowercased; null on humans and identity seeds. [agentStatus] deliberately reads `active` as
     * unknown (it is lifecycle, not liveness), and the roster presence projection never turns it
     * into a presence fact (it only logs it in its `skipped=` samples). Its one display use is
     * the "Likely online" fallback (artin, #triage:#97; web botiverse/slock #11642): when the
     * member status read finds no presence fact at all for a managed agent and this is `active`,
     * it shows a green "Likely online" guess that any real fact replaces
     * (`likelyOnlineMemberStatusOrNull` in SharedServerMembersStore).
     */
    val agentLifecycleStatus: String? = null,
    /**
     * The agent list's `blockedReason` (`GET /agents` and its incremental-sync rows; never
     * `GET /agents/:id`): why the server runs this agent nowhere, raw. Only
     * `computer_access_revoked` changes what the app shows ([build.raft.app.data.agentBlockedState]);
     * any other value keeps the agent's ordinary state, as the web does for one it doesn't know.
     */
    val agentBlockedReason: String? = null,
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
    /**
     * A personal assistant paused by a server owner or admin: `assistantPausedAt` (ISO-8601) from
     * `GET /agents` / `GET /agents/:id`; null while it runs. `agent:updated` refreshes the roster.
     */
    val assistantPausedAt: String? = null,
    /**
     * Who paused it (`assistantPausedBy.name`), for the viewers the server tells; null otherwise.
     * The owner's paused notice names them (web `useAssistantPausedLine`).
     */
    val assistantPausedByName: String? = null,
    /**
     * A personal assistant's owner (`assistantOwnerUserId` on `GET /agents`); null for every other
     * agent and on payloads without it. The message author row shows no model for one (web
     * MessageItem `isPersonalAssistantSender`).
     */
    val assistantOwnerUserId: String? = null,
    /**
     * App-managed bot (botiverse/slock `agents.managed_by_app_install_id`, sent as
     * `managedByAppInstallId` on `GET /agents` and `GET /agents/:id` for non-guests). Web hides
     * the read_receipts_v0 mention badge for these (`MentionLink.tsx` `isManagedBot`).
     */
    val managedByAppInstallId: String? = null,
    /**
     * The person who created this agent (`creatorId` on `GET /agents` when `creatorType` is
     * `user`); null for an agent made by an agent and on payloads without it. The Members page's
     * row menu reads it for the creator's rights (web `canViewAgentPrivateSurfaces`).
     */
    val agentCreatorUserId: String? = null,
    /**
     * App Bots only: the owning App's name (`managedByApp.name` on `GET /agents` and
     * `GET /agents/:id`, botiverse/slock #11273). Web `AppBadge` reads it for "App · <App>".
     */
    val managedByAppName: String? = null,
    /**
     * App Bots only: `inMemberLists` from the agent directory (botiverse/slock #11295). `false`
     * while the Bot is removed from this server: member lists leave it out and its DM is
     * read-only. `null` for ordinary agents (the server omits the key).
     */
    val inMemberLists: Boolean? = null,
    val createdAgents: List<AgentProfile> = emptyList(),
    val permissionBadges: List<String> = emptyList(),
    val serverId: ServerId? = null,
    val serverName: String? = null,
    val serverSlug: String? = null,
    /**
     * The servers the person is from in a channel, as the server answers them, an empty list
     * included (the channel lists them through no server): a human profile read from a channel
     * (`GET /servers/:id/members/:memberId/profile?channelId=`) or a joint channel roster row
     * (`GET /channels/:id/members` humans). Null otherwise (or an older server); then the single
     * [serverId] / [serverName] / [serverSlug] stand. Read through [build.raft.app.data.memberOrigin].
     */
    val servers: List<MemberServerRef>? = null,
    /**
     * The channel a Guest belongs to (`guestChannel` on `GET /servers/:id/members` rows and the
     * member profile), shown as "Guest in #x" in the profile's Info. Null for anyone else, or a
     * channel the viewer may not read.
     */
    val guestChannel: MemberGuestChannelRef? = null,
    /**
     * Joint channel roster rows only (`GET /channels/:id/members`, slock `getChannelMembers`):
     * the server's own answer to "is this one of the viewed server's members". Authoritative over
     * [serverId], which on computed joint rows is only the person's first source. Null elsewhere
     * and on older servers.
     */
    val inViewerServer: Boolean? = null,
    /**
     * Joint channel roster rows only (`buildChannelMembersView` `canRemoveFromChannel`, sent when
     * the viewer has joint authority): whether the viewer may remove this member. Null elsewhere;
     * then only the viewed server's own members are removable. Never stored offline.
     */
    val canRemoveFromChannel: Boolean? = null,
    /**
     * Channel-membership projection (web `channel_members` join row fields on
     * `useChannelMembers` results): the role the member effectively has inside
     * THIS channel after admin-basis resolution. Only populated by channel
     * member snapshots — server-roster producers leave it null.
     */
    val effectiveChannelRole: String? = null,
    /** Raw `channel_role` the member holds in this channel, before effective-role resolution. */
    val channelRole: String? = null,
    /** Why a channel admin is an admin (`server_role` | `channel_role` | `both`); display-only. */
    val channelAdminBasis: String? = null,
    /**
     * Server-resolved "may the current member change THIS member's channel
     * role" (web `canChangeChannelRole`). Drives the flag-gated role-action UI;
     * kept as data so the gate can be enabled without another wire change.
     */
    val canChangeChannelRole: Boolean? = null,
    /**
     * Only on the answer of an agent profile update (`PATCH /agents/:id` `configApply`): how the
     * server applies a runtime config change. Never stored or cached (web #13372 keeps it off the
     * agent too): stores drop it with [withoutConfigApply].
     */
    @Transient
    val configApply: build.raft.app.data.AgentConfigApply? = null
) {
    /** This profile as stored: without the update answer's [configApply]. */
    fun withoutConfigApply(): MemberProfile = if (configApply == null) this else copy(configApply = null)
}

/**
 * Slack-bridge (or future provider) participant projected into a channel's
 * member roster (web parity: `externalMembers` in ChannelMembers.tsx).
 * Display-only rows — never selectable, never removable, never a member-detail
 * target; [MemberKind.External] remains the message-attribution kind, this is
 * the roster-side sibling.
 */
@Serializable
data class ChannelExternalMemberProfile(
    val id: String,
    val provider: String? = null,
    val displayName: String,
    val handles: List<String> = emptyList(),
    val actorKind: String? = null,
    val avatarUrl: String? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
    /** External-agent credential last use; Web considers this online for 120s. */
    val lastSeenAt: String? = null,
    /** The Slack workspace's name (slock `getChannelExternalMembers` `workspaceName`). */
    val workspaceName: String? = null,
)

@Serializable
data class AgentProfile(
    val id: MemberId,
    val userId: String,
    val displayName: String,
    val handle: String,
    val description: String? = null,
    val roleDescription: String? = null,
    val avatar: AvatarReference? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
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
    val parentChannelKind: ConversationKind? = null,
    /** Task v2: the task a `channelType: "task"` conversation belongs to (slock `buildFrontendConversationContext`). */
    val taskId: TaskId? = null,
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
    /** RFC 077: set when the sender's assistant sent this message for them (wire `delegatedBy`). */
    val delegatedBy: MessageDelegatedBy? = null,
    /**
     * RFC 077 PR 2b: set (ISO time) when the owner retracted this delegated message. The server
     * keeps the row and replaces `content` with a fixed placeholder (wire `deletedAt`).
     */
    val deletedAt: String? = null,
    val randomId: String? = null,
    /** Canonical numeric creation time used for ordering; never derived from localized display text. */
    @Transient val createdAtEpochMillis: Long? = null,
    /** Wire `mentions` of `message:*` emits; empty when the payload carried none. */
    val mentions: List<MessageMention> = emptyList(),
)

/**
 * One `mentions[]` entry of a message payload (botiverse/slock `packages/web/src/store/messageStore.ts`
 * `MessageMention`: `{ type: "user" | "agent" | "external"; id; name; displayName? }`; server
 * `messageRealtimeProducerRegistry.ts` lists `mentions` among the `message:new` payload keys).
 */
@Serializable
data class MessageMention(
    val type: String,
    val id: String,
    val name: String = "",
)

/** Web `mentionsCurrentUser`: a `user` mention of the signed-in human. */
fun List<MessageMention>.mentionsUser(userId: String): Boolean =
    userId.isNotBlank() && any { it.type == "user" && it.id == userId }

@Serializable
data class ForwardMessageBundle(
    val id: String? = null,
    val title: String = "",
    val sourceTitle: String = "",
    val totalCount: Int? = null,
    val messages: List<ForwardMessageItem> = emptyList(),
    /**
     * The source the card may name (web ForwardedBundleCard `sourceLabel`): set only when the
     * first item's provenance is available and its target is a public, non-DM conversation.
     */
    val visibleSource: ForwardMessageSource? = null
)

@Serializable
data class ForwardMessageSource(
    val label: String,
    val thread: Boolean = false,
    /** A public task discussion source's title (slock #12620 `sourceTaskTitle`). */
    val taskTitle: String? = null,
    /** Where the source opens (web `canOpenSourceLabel`): the conversation and message, or null. */
    val channelId: String? = null,
    val messageId: String? = null
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
    val attachments: List<ForwardMessageAttachment> = emptyList(),
    // web `attachmentPolicy === "projected"`: the files were copied with the forward, so the
    // card may load them (images as a gallery) by their snapshot ids.
    val attachmentsProjected: Boolean = false
)

@Serializable
data class ForwardMessageAttachment(
    val filename: String = "",
    val mimeType: String? = null,
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null
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
/**
 * Task status. Wire values are slock `packages/shared` `TASK_STATUSES`
 * (`"backlog" | "todo" | "in_progress" | "in_review" | "done" | "closed"`, slock #12962);
 * entries run in board order (raft-ui `TASK_STATUS_IDS`: Backlog to Closed).
 *
 * [Backlog] ("not now", raft-design decision 0042) is task v2 only and not active work.
 * [Unknown] is never sent by the server today: it holds a status a newer server added that
 * this build does not know, so the task keeps showing (neutral, last) instead of dropping
 * out of every list. It is never written back and never offered as a choice.
 */
@Serializable
enum class TaskStatus {
    Backlog,
    Todo,
    InProgress,
    InReview,
    Done,
    Closed,
    Unknown;

    fun toWireValue(): String =
        when (this) {
            Backlog -> "backlog"
            Todo -> "todo"
            InProgress -> "in_progress"
            InReview -> "in_review"
            Done -> "done"
            Closed -> "closed"
            Unknown -> UNKNOWN_WIRE_VALUE
        }

    /** A status the server knows and the app may write or filter by ([Unknown] is not one). */
    val isKnown: Boolean
        get() = this != Unknown

    companion object {
        /** App-side token for [Unknown]; not a server status. */
        const val UNKNOWN_WIRE_VALUE = "unknown"

        /** Statuses the server knows, in board order (excludes [Unknown]). */
        val known: List<TaskStatus> = entries.filter { it.isKnown }

        /** Exact wire values only; null for anything else (see [fromWireValueOrUnknown]). */
        fun fromWireValue(value: String?): TaskStatus? =
            when (value?.trim()?.lowercase()) {
                "backlog" -> Backlog
                "todo" -> Todo
                "in_progress" -> InProgress
                "in_review" -> InReview
                "done" -> Done
                "closed" -> Closed
                else -> null
            }

        /**
         * A present status string the app does not know (a status added on the server after
         * this build) reads as [Unknown], so the task stays visible; a missing or blank one is
         * still null.
         */
        fun fromWireValueOrUnknown(value: String?): TaskStatus? =
            fromWireValue(value) ?: value?.takeIf { it.isNotBlank() }?.let { Unknown }
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
    val revision: Long? = null,
    /**
     * Projection-local authority from the server (e.g. "historical_joint_task" for a pre-conversion
     * task seen from a joint participant). Non-null means read-only: no claim, status or assignee
     * changes, matching web's authorizeTaskAction / canChangeTaskStatus.
     */
    val readOnlyReason: String? = null,
    /** Raw wire kind of the task's channel (`channel` / `private` / `dm` / `joint`). */
    val channelType: String? = null,
    /** Task v2: the pre-v2 host message (its thread is the earlier discussion); null for a v2 task. */
    val hostMessageId: MessageId? = null,
    /**
     * Task v2: the message the task hangs under (slock `taskService.ts` `sourceMessageId`: the
     * message it was created from, or a pre-v2 task's host message). Null: none (a channel-only
     * task, or [sourceMessageReported] false: an older server or frame).
     */
    val sourceMessageId: MessageId? = null,
    /**
     * The DTO carried `sourceMessageId` (even as null). Web reads an explicit null as "hangs under no
     * message" and only a missing field as "fall back to the host message".
     */
    val sourceMessageReported: Boolean = false,
    /** Task v2: the task's CANONICAL conversation channel (`channels.type = 'task'`), when it exists. */
    val conversationChannelId: ChannelId? = null,
    /** Task v2: the discussion is the task's own conversation (exists, or is created on first use). */
    val conversationAnchored: Boolean = false,
    val conversationReplyCount: Int = 0,
    /** A pre-message orphan task: neither a host message nor a conversation. */
    val isLegacy: Boolean = false,
    /**
     * Task v2: whether the reader follows the task's conversation as seen through this surface
     * (slock `routes/tasks.ts` `withConversationFollowed`, joint: the local projection). Only the
     * per-reader task lists carry it; `task:*` realtime frames do not, so null = unknown.
     */
    val conversationFollowed: Boolean? = null,
    /** The task DTO's `createdAt` (ISO), as the server sent it; null from a source without it. */
    val createdAt: String? = null,
    /**
     * False for a `detail=summary` row that has a description it did not send (`hasDescription`
     * without `description`): [description] null does not mean "none" there, so the head must not
     * offer to add one over it (web TaskModalHead `descriptionLoaded`).
     */
    val descriptionLoaded: Boolean = true,
    /**
     * When the task last moved (slock `activityAt`: lastActivityAt ?? updatedAt), what the Tasks
     * page's `sort=activity` orders by; null from a source without it.
     */
    val activityAt: String? = null,
    /**
     * Who sent the task v2 source message (slock `ServerTaskSummary.sourceSenderType` /
     * `sourceSenderId`: `user` / `agent` / `external_projection`, null for none or a deleted one),
     * what the Tasks page's `from_my_message` relation reads. Only `detail=summary` rows carry it:
     * [sourceSenderReported] false means unknown (a socket frame, a full row).
     */
    val sourceSenderType: String? = null,
    val sourceSenderId: String? = null,
    val sourceSenderReported: Boolean = false,
    /**
     * When the task was done (slock `completedAt`, ISO; set only while it is done), what the Tasks
     * page's `completed_since` bound reads; null from a source without it.
     */
    val completedAt: String? = null,
)

// Inbox Models
@Serializable
enum class InboxItemKind {
    Channel,
    DirectMessage,
    Thread,
    MentionAction,
    System,
    /**
     * A first-party object's row (slock `activityObjectsContract.ts` `ObjectInboxItem`, Artifacts):
     * [build.raft.app.data.InboxItem.activityObject] carries it; its `channelId` names no conversation.
     */
    Object
}

// Realtime Event Models
@Serializable
sealed interface RealtimeEvent

@Serializable
data class MessageNew(
    val message: Message,
    // In-process carry (sync-core messages): the raw-wire canonical decode produced ONCE by the
    // projection gateway (MessageShadowObserver), threaded to the SharedRealtimeDomainEventOwner
    // shadow fold+persist lane so it is not re-decoded there. @Transient — never serialized (not part of the
    // wire/cache shape), so a deserialized event carries null and the shadow lane simply skips it (fail-open).
    @Transient val decodedCanonical: CanonicalMessageCarry? = null,
    /**
     * The persisted row's provenance columns when the frame carries them (rich `message:new`
     * payloads keep `systemSubtype` / `causalActorType` / `causalActorId`; the allowlisted
     * projection drops them). Null: the frame had no `systemSubtype` key, so a system message's
     * unread on a thread row cannot be judged here (web `threadSystemMessageCounts`). In-process
     * only, like [decodedCanonical].
     */
    @Transient val systemProvenance: MessageSystemProvenance? = null,
) : RealtimeEvent

/** Wire `systemSubtype` / `causalActorType` / `causalActorId` of a `message:new` frame (each may be null). */
data class MessageSystemProvenance(
    val systemSubtype: String?,
    val causalActorType: String?,
    val causalActorId: String?,
)

@Serializable
data class MessageUpdated(
    val message: Message,
    @Transient val decodedCanonical: CanonicalMessageCarry? = null,
) : RealtimeEvent

@Serializable
data class DirectMessageCreated(val channelId: ChannelId) : RealtimeEvent

@Serializable
data class ThreadFollowersUpdated(val threadChannelId: ChannelId) : RealtimeEvent

/** Read receipts (`read_receipts_v0`) peer kind; the server exposes only agents. */
@Serializable
enum class ReadReceiptPeerKind { Human, Agent }

/**
 * `scope_read:updated` (botiverse/slock `services/readReceiptService.ts` `emitScopeReadUpdated`):
 * either one peer's advanced watermark ([peerKind], [peerId], [maxReadSeq] all set) or, for a scope
 * above the per-peer limit, [summaryChanged] (re-fetch the anonymous summary). The mapper only
 * builds those two shapes.
 */
@Serializable
data class ScopeReadUpdated(
    val scopeId: String,
    val peerKind: ReadReceiptPeerKind? = null,
    val peerId: String? = null,
    val maxReadSeq: Long? = null,
    val summaryChanged: Boolean = false,
) : RealtimeEvent

/** `agent:typing` `state`: a live call (`start`, also its refreshes) or its end. */
@Serializable
enum class AgentTypingPhase { Start, End }

/**
 * `agent:typing` (botiverse/slock `packages/shared/src/agentTyping.ts` `AgentTypingEvent`), emitted
 * to `channel:<channelId>`: an agent is about to post there. For a thread reply [channelId] is the
 * parent channel and [parentMessageId] the thread's root message; null means the channel itself.
 * [taskId] is set only for a task v2 reply. [expiresAtEpochMillis] is the server's clock (0 when
 * the frame carried none).
 */
@Serializable
data class AgentTypingUpdated(
    val agentId: MemberId,
    val serverId: ServerId,
    val channelId: ChannelId,
    val parentMessageId: MessageId?,
    val callId: String,
    val phase: AgentTypingPhase,
    val expiresAtEpochMillis: Long,
    val taskId: String? = null,
) : RealtimeEvent

/**
 * `thread:reply_updated` (slock `messageService.emitExternalLinkedMessageUpdateToFrontend`, sent to the
 * parent channel's room when a bridged (Slack) thread reply is edited): the edited reply, for the
 * parent's inline replies. Never a new reply, never a count change.
 */
@Serializable
data class ThreadReplyUpdated(
    val parentMessageId: MessageId,
    val parentChannelId: ChannelId,
    val parentChannelType: String,
    val threadChannelId: ChannelId,
    val messageId: MessageId,
    val seq: Long,
    val content: String,
    val updatedAt: String,
) : RealtimeEvent

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
    /** `latestReply.mentions` (the reply is the full message payload); feeds Home's thread @ mark. */
    val latestReplyMentions: List<MessageMention> = emptyList(),
) : RealtimeEvent

@Serializable
data class TaskCreated(val tasks: List<TaskSummary>) : RealtimeEvent

@Serializable
data class TaskUpdated(val task: TaskSummary) : RealtimeEvent

@Serializable
/**
 * `task:deleted` (slock `taskRealtimeEvents.ts`). [conversationChannelId]: task v2, the task's
 * conversation as this surface saw it (joint: its local projection), deleted with the task.
 */
data class TaskDeleted(val taskId: TaskId, val conversationChannelId: ChannelId? = null) : RealtimeEvent

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
    val refTextEnabled: Boolean = false,
    /** Server `detailKind` of a status entry (`subagent_activity` marks a sub-agent's lifecycle row). */
    val statusDetailKind: String? = null,
    /** A `tool_start`'s call id; a sub-agent's lineage names the call that started it. */
    val callId: String? = null,
    /** The row's sub-agent lineage marker; null for the agent's own rows. */
    val subagent: AgentActivitySubagentLineage? = null
)

/**
 * slock `SubagentLineage.subagent` (packages/shared/src/index.ts): the explicit marker a
 * sub-agent's activity rows carry. Every field is a bounded id or token, never content.
 * [phase]: `started` / `progress` / `notification` / `active` / `ended`; [outcome]: `done` /
 * `failed` / `stopped` / `ceiling`.
 */
@Serializable
data class AgentActivitySubagentLineage(
    val parentToolUseId: String? = null,
    val subagentType: String? = null,
    val taskId: String? = null,
    val phase: String? = null,
    val outcome: String? = null
)

@Serializable
data class MemberPresenceUpdated(
    val memberId: MemberId,
    val presence: Presence,
    val kind: MemberKind? = null,
    val agentStatus: AgentStatus? = null,
    val statusLabel: String? = null,
    /** agent:activity `detailKind` behind [statusLabel] ("starting", "compacting_context", ...). */
    val detailKind: String? = null,
    /** Normalized tool name when [statusLabel] is a client-built tool label (a `tool_start` entry). */
    val activityToolName: String? = null,
    val serverSeq: Long? = null,
    val activityEntries: List<RealtimeAgentActivityEntry> = emptyList(),
    /**
     * `agent:seen` carries the server's `lastSeenAt` (ISO-8601) for an external
     * agent — the credential-use timestamp that drives the online window.
     * Seq-less and merged onto the registered presence fact (see
     * [ProcessMemberPresenceRegistry]): it updates presence to Active and
     * this field, never displacing a seq-carrying fact.
     */
    val lastSeenAt: String? = null,
    /**
     * Why automatic wakes are paused, with [detailKind] `wake_crash_loop_blocked` (the
     * `wakeCrashLoop` carrier of `agent:activity`); null for every other status.
     */
    val wakeCrashLoop: AgentWakeCrashLoop? = null,
    /**
     * Why the last start failed, with [detailKind] `runtime_unavailable` (the `spawnFailure`
     * carrier of `agent:activity`); null for every other status.
     */
    val spawnFailure: AgentSpawnFailure? = null,
    /**
     * The agent is stuck on one tool call (the `toolStuck` carrier of `agent:activity`). The daemon
     * attaches it to every frame while that is true, so a frame without it means "not stuck".
     */
    val toolStuck: AgentToolStuck? = null,
    /**
     * Why the daemon holds the agent for an unusable sign-in another agent on the same credential
     * saw rejected, with [detailKind] `idle` (the `credentialHold` carrier of `agent:activity`,
     * botiverse/slock #13276); null for every other status, and from older daemons.
     */
    val credentialHold: AgentCredentialHoldReason? = null,
    /**
     * The code of the runtime error this frame shows (the `errorCode` carrier of `agent:activity`,
     * web #13985), only with [agentStatus] Error; null otherwise or for an unknown code.
     */
    val errorCode: build.raft.app.data.AgentRuntimeErrorCode? = null,
    /**
     * From `agent:activity_state` (botiverse/slock 968d102d): the coarse state of the detailed frame with
     * the same [serverSeq], without its label or entries. It never replaces that frame or a newer one
     * (see `shouldReplaceRegisteredPresenceFact`).
     */
    val coarseState: Boolean = false
) : RealtimeEvent

/**
 * `agent:activity:subscribed` (botiverse/slock 968d102d `socket/index.ts` `subscribeSocketToAgentActivity`): the
 * answer to one `agent:activity:subscribe`, naming the ids this socket now gets detailed `agent:activity` for
 * (ids it may not see are left out).
 */
@Serializable
data class AgentActivitySubscribed(
    val agentIds: List<String>
) : RealtimeEvent

/**
 * Why an agent cannot run until someone fixes its provider credential (shared
 * `CredentialHoldReason`, botiverse/slock #13276 packages/shared/src/index.ts): its sign-in needs
 * renewing, its plan does not include its model, or the provider balance is exhausted. The same
 * values name an own error's `lastRuntimeError.requiredAction` (`user_reauth`,
 * `user_model_access`).
 */
@Serializable
enum class AgentCredentialHoldReason(val wireValue: String) {
    UserReauth("user_reauth"),
    UserModelAccess("user_model_access"),
    Billing("billing");

    companion object {
        /** Shared `isCredentialHoldReason`: a known reason, else null (a newer daemon's: no reason). */
        fun fromWireValue(value: String?): AgentCredentialHoldReason? =
            value?.let { wire -> entries.firstOrNull { it.wireValue == wire } }
    }
}

/**
 * Why an agent's start failed (shared `SpawnFailureActivityDiagnostic`, botiverse/slock task #1123):
 * the fields the client shows. [lookupReason] says how the Computer's lookup for the runtime ended
 * (`not_on_path`, `lookup_timed_out`, ...), only with [reason] `runtime_not_found`; absent means
 * no verdict (an older daemon), never "confirmed missing".
 */
@Serializable
data class AgentSpawnFailure(
    val reason: String,
    val lookupReason: String? = null,
    /** The agent's model the start failed on (`model_not_found`, `model_unsupported`); null when not sent. */
    val model: String? = null,
    /** The server paused automatic restarts after this failure (shared `autoRestartPaused`). */
    val autoRestartPaused: Boolean = false,
    /**
     * When the paused agent's next automatic try starts, epoch ms (shared `pauseRetryAtMs`,
     * botiverse/slock #13460): server-written, only while a pause that retries by itself is in
     * effect; null when no time is known.
     */
    val pauseRetryAtMs: Long? = null
)

/**
 * The server paused automatic wakes after the agent stopped several times in a row (shared
 * `WakeCrashLoopActivityDiagnostic`, botiverse/slock 24873fd5be37 packages/shared/src/index.ts):
 * the fields the client shows. [lastExitReason] is `memory_limit` when the last counted stop was
 * at the Computer's per-agent memory limit (#10860); absent from older servers.
 */
@Serializable
data class AgentWakeCrashLoop(
    val earlyExitCount: Int,
    val lastExitReason: String? = null
) {
    /** Web AgentStatusBadge: paused after repeated stops at the per-agent memory limit. */
    val pausedAtMemoryLimit: Boolean
        get() = lastExitReason == "memory_limit"
}

/**
 * The agent is stuck on one tool call: no output for the daemon's threshold, so new messages queue
 * behind it (shared `ToolStuckActivityDiagnostic`, botiverse/slock #10409; shown since web #10446).
 * Not tied to a detail kind. Times are the agent's Computer clock (ms).
 */
@Serializable
data class AgentToolStuck(
    val toolName: String,
    val toolDetailKind: String? = null,
    val startedAtMs: Long = 0L,
    val quietSinceMs: Long,
    val thresholdMs: Long = 0L,
    val queuedMessages: Int = 0,
    /**
     * How long the call had been quiet when the server sent this frame (the frame's server
     * `timestamp` minus [quietSinceMs]), and when this device received it ([receivedAtMs], its
     * own clock). The shown minutes add the time since receipt, so this device's clock never
     * meets the agent's Computer clock (Lagoon's review). Null: not known (a REST snapshot).
     */
    val quietMillisAtFrame: Long? = null,
    val receivedAtMs: Long = 0L
)

@Serializable
data class RealtimeRefreshRequired(
    val scope: RealtimeRefreshScope,
    val reason: String,
    val serverId: ServerId? = null,
    val channelId: ChannelId? = null,
    val memberId: MemberId? = null,
    val subjectId: String? = null,
    /**
     * Incremental sync (`mobile_sync_changes_v0`): the change behind this refresh may be newer than
     * a reload already in flight, and it will not come again, so that reload is replaced instead of
     * joined (latest request wins, Tenny 10-27). Realtime socket refreshes keep coalescing.
     */
    val supersedesInFlightLoad: Boolean = false
) : RealtimeEvent

@Serializable
data class ServerMembershipRemoved(
    val serverId: ServerId
) : RealtimeEvent

@Serializable
data class ServerDeleted(
    val serverId: ServerId
) : RealtimeEvent

/**
 * `computer:restart:done` (slock packages/server/src/services/agentOrchestrator.ts relays the
 * Computer's frame to the `server:<id>` room with the machine id added): the result of a web
 * restart, correlated by [requestId].
 */
@Serializable
data class ComputerRestartDone(
    val machineId: String,
    val requestId: String,
    val ok: Boolean,
    val error: String? = null
) : RealtimeEvent

@Serializable
data class ServerPlanUpdated(
    val serverId: ServerId,
    val plan: String
) : RealtimeEvent

/**
 * `appearance:updated` (botiverse/slock@aed2ba714 packages/server/src/routes/servers.ts:2010-2012):
 * the member changed their own theme or decorations on [serverId] on another tab or device.
 * Sent to the member's `user:<id>` room only (themes are per member per server), with the
 * saved `{ theme, themeDecor }` of `PATCH /api/servers/:id/appearance-settings`.
 * [theme] is the wire id (`null` = Raft Yellow); [themeDecor] null keeps the stored value.
 */
@Serializable
data class ServerAppearanceUpdated(
    val serverId: ServerId,
    val theme: String?,
    val themeDecor: Boolean?
) : RealtimeEvent

/**
 * `user:profile-updated` (botiverse/slock d311e6f0f9 #12688, `packages/shared/src/actorResolveContract.ts`
 * `userProfileUpdatedEventSchema`, sent by `services/userProfileEvents.ts`): the person [userId]
 * changed their name or avatar. Exactly the public profile a server's member list shows (never
 * email), sent per server to the viewers who may see them in [serverId]'s member list. Clients
 * patch that one member in place; the member list is not read again.
 */
@Serializable
data class UserProfileUpdated(
    val serverId: ServerId,
    val userId: MemberId,
    val name: String,
    val displayName: String?,
    val avatarUrl: String?,
    val gravatarHash: String,
) : RealtimeEvent

/**
 * `link_card.operation_changed` `{ operation_id, receipt_revision }` (RFC 076 §6.8; botiverse/slock
 * `services/linkCardReceiptHints.ts`): one of the viewer's link card operations on the connection's
 * server has a new receipt. A hint only, no state: a card following [operationId] reads
 * `POST /link-cards/status` again when [receiptRevision] is newer than what it showed (web
 * `linkCardExecute.tsx` `onOperationChanged`).
 */
@Serializable
data class LinkCardOperationChanged(
    val operationId: String,
    val receiptRevision: Long,
) : RealtimeEvent

/**
 * `actor:updated` (botiverse/slock `actorResolveContract.ts` `actorUpdatedEventSchema`): an agent whose
 * home is another server, seen through a joint channel, changed its display identity. Exactly the
 * external projection `POST /api/actors/resolve` gives; only agents emit it today.
 */
/**
 * `message_display_prefs:updated` (botiverse/slock `routes/channels.ts`): the person's display
 * preferences of conversation [scopeId] on [serverId] changed elsewhere (today only "Collapse long
 * messages"). Applied like web `setMessageDisplayPrefsState`, never older than the held version.
 */
@Serializable
data class MessageDisplayPrefsChanged(
    val serverId: ServerId,
    val scopeId: String,
    val collapseLongMessages: Boolean,
    val prefsVersion: Long?,
) : RealtimeEvent

@Serializable
data class ActorUpdated(
    val kind: MemberKind,
    val id: String,
    val homeServerId: String,
    val name: String,
    val displayName: String?,
    val avatarUrl: String?,
) : RealtimeEvent

/**
 * The person's product-feedback unread total (unread public replies on their own tickets) in one
 * Hands app (botiverse/slock f0f773995 `packages/shared/src/productFeedbackUnread.ts`, sent by
 * `services/productFeedbackUnreadRealtime.ts` to their own `user:<id>` room):
 * - `product_feedback:unread` `{ userId, unreadTotal }`: the web app's total ([source] [WebSource],
 *   [userId] set). The desktop app files into the web app, so it is the desktop app's.
 * - `product_feedback:app_unread` `{ source, unreadTotal }`: a mobile app's ([source] `android`,
 *   `ios` or `ohos`; [userId] null: the room says whose).
 * Always one app's whole total, never a change. Every connection of the person receives every
 * one; the dot sums every app (slock #14722 / #14732), so each of the person's own replaces that
 * app's total in the dot (build.raft.app.data.feedbackUnreadEventIsMine, RaftFeedbackUnreadStore).
 */
@Serializable
data class ProductFeedbackUnreadUpdated(
    val source: String,
    val userId: String?,
    val unreadTotal: Int,
) : RealtimeEvent {
    companion object {
        /** [source] of `product_feedback:unread` (the web app's Hands source name). */
        const val WebSource = "web"
    }
}

/**
 * `server:features-changed` (botiverse/slock `packages/shared/src/featuresChanged.ts`
 * `ServerFeaturesChangedEvent`, sent by `services/featureFlagChangeNotifier.ts`): this Server's
 * feature flags may have changed. A refetch signal, not values: [keys] is only a hint (never
 * treat a key missing from it as unchanged), and a missed one is never replayed.
 */
@Serializable
data class ServerFeaturesChanged(
    val serverId: ServerId,
    val keys: List<String>,
) : RealtimeEvent

/**
 * `activity:changed` (botiverse/slock `packages/shared/src/activityObjectsContract.ts`
 * `ActivityChangedEvent`), to the person's own room: their Activity in [serverId] changed (an
 * object event, or their own Done / undo / read of an object row elsewhere). Carries no content:
 * refetch that server's Activity and unread summary. [source] is `object` today.
 */
@Serializable
data class ActivityChanged(
    val serverId: ServerId,
    val source: String,
) : RealtimeEvent

/**
 * `joint-invites:changed` (botiverse/slock #12939 `listChangedEvents.ts`): the pending joint-channel
 * invites to this person on [serverId] changed (web `usePendingJointInvites` reads them again).
 */
@Serializable
data class JointInvitesChanged(
    val serverId: ServerId,
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
    val hasMore: Boolean,
    /**
     * The read's sync horizon (decimal xid8 text, never a number); null from a server that predates it.
     * See `build.raft.app.data.realtime.RealtimeSyncHorizon`.
     */
    val horizon: String? = null,
    /** More late messages qualified than the server sends: the client re-fetches its visible scope. */
    val horizonReset: Boolean = false,
    /**
     * Message storage Phase 3b: the raw-wire canonical decode of each message the response carries (in order; a
     * row the decoder refuses is absent). The server's resume rows carry neither `conversationContext` nor
     * `randomId` (slock `syncMessagesWithHorizon`), so these carries have no context and a null randomId: the
     * durable writer supplies the context from the stored scope header. In-process only, never serialized.
     */
    @Transient val decodedCanonical: List<CanonicalMessageCarry> = emptyList(),
    /**
     * Phase 3b: one entry per row the canonical decode refused — its channel id when the row names one, else
     * `null`. Those rows can never be stored, so the durable writer reports them as continuity holes.
     */
    @Transient val canonicalDecodeMisses: List<String?> = emptyList(),
) : RealtimeEvent

@Serializable
data class Heartbeat(val seq: Long) : RealtimeEvent

/**
 * RFC 041 per-receiver read-state push (server PR #4175,
 * `routes/channels.ts::emitReadStateUpdated[Bulk]`). Identity-only per §2.3:
 * the payload carries the watermark (`maxReadSeq`) and its version. Server
 * PR #9113 additionally supplies an optional unread count for incomplete reply projections. The receiving member is implied by the user-room the
 * socket is joined to, so no memberId travels on the wire; `readStateVersion`
 * is per-(user, channel) — `user_channel_read_cursors.read_state_version`,
 * PK (user_id, channel_id) — so version gating must key on the scope, not the
 * family (see [build.raft.app.data.realtime.PerReceiverStateVersionStore]).
 */
@Serializable
data class PerReceiverReadStateScope(
    val scopeId: ChannelId,
    val maxReadSeq: Long?,
    val readStateVersion: Long,
    val unreadCount: Int? = null,
    /**
     * botiverse/slock #11795 (`emitReadStateUpdated[Bulk]`): the scope is marked unread with nothing
     * countable ("unread, no number"). Sent only when true; a newer version without it clears the mark.
     */
    val markedUnread: Boolean = false
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
    val mentioned: Boolean? = null,
    /**
     * Unified notification envelope additions (slock `docs/contracts/notifications.md` §4.1): the
     * dedupe [notificationId], the real [category] (`dm`, `mention`, `thread`, ...), the message
     * [seq] in [conversationId], [groupKey] (`<serverId>:<conversationId>`, what a retraction names),
     * the click target [routeJson] (a `NotificationRoute` object as JSON text) and [silent] (the
     * account sound setting is off). Null on servers that predate the contract.
     */
    val notificationId: String? = null,
    val category: String? = null,
    val seq: Long? = null,
    val groupKey: String? = null,
    val conversationId: String? = null,
    val routeJson: String? = null,
    val silent: Boolean? = null,
    /**
     * Banner copy (contract §1.5, slock #12277 / #12279): [subtitle] is the context line (the server
     * label for a receiver of 2+ servers), still joined into [title] as `title · subtitle` on the
     * socket; [localization] keys the fixed phrases of title / subtitle / body.
     */
    val subtitle: String? = null,
    val localization: build.raft.app.data.RaftNotificationLocalization? = null
) : RealtimeEvent

/**
 * `notification:push` of an event without a message (contract §4.1: `reminder`, `system_notice`,
 * `test`): no conversation identity, a `groupKey` of `<serverId>:reminder|notice|test`, and the open
 * target in [routeJson] (null: the app's Activity).
 */
@Serializable
data class NotificationNoticeReceived(
    val serverId: ServerId,
    val category: String,
    val groupKey: String,
    val notificationId: String? = null,
    val title: String,
    val body: String,
    val routeJson: String? = null,
    val silent: Boolean? = null,
    /** As [NotificationPushReceived.subtitle] / [NotificationPushReceived.localization]. */
    val subtitle: String? = null,
    val localization: build.raft.app.data.RaftNotificationLocalization? = null
) : RealtimeEvent

/**
 * `notification_prefs:updated` with `type: "channel" | "thread" | "server"` (contract §2.3): one
 * explicit level changed. [level] is a `NotificationLevelSetting` wire value; [scopeId] is the
 * server, channel (or DM) or thread channel id. Drop it when [prefsVersion] is not newer.
 */
@Serializable
data class NotificationLevelChanged(
    val serverId: ServerId,
    val scope: String,
    val scopeId: String,
    val level: String,
    val prefsVersion: Long
) : RealtimeEvent

/** `notification_prefs:updated` with `type: "account"`: the account-wide settings changed. */
@Serializable
data class NotificationAccountSettingsChanged(
    val settings: build.raft.app.data.RaftNotificationAccountSettings
) : RealtimeEvent

/**
 * `notification:retract` (contract §5): the read position of [conversationId] moved forward on some
 * device; close notifications of [groupKey] whose seq is at most [throughSeq] (null: all of them).
 */
@Serializable
data class NotificationRetracted(
    val serverId: ServerId,
    val groupKey: String,
    val conversationId: String,
    val throughSeq: Long? = null
) : RealtimeEvent
