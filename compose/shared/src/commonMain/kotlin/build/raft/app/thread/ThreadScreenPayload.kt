package build.raft.app.thread

import build.raft.app.data.KnownEntityScope
import build.raft.app.data.RaftKnownEntityResolver
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.map
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeShellDestinationSearch
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.get
import build.raft.app.memberDetailIdentity
import build.raft.app.memberDetailIdentityFromWire
import build.raft.app.memberKindWireValue
import build.raft.app.messages
import build.raft.app.model.AvatarReference
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.providerLabel
import build.raft.app.nonBlank
import build.raft.app.normalizeThreadAttachmentUploadDraftKey
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.parseConversationKindOrNull
import build.raft.app.resolveOpenRequest
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import build.raft.app.data.HomeChannelRow
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import build.raft.app.data.ResolvedActor
import build.raft.app.model.isInternalJointStorageIdentity
import androidx.compose.foundation.layout.size
import build.raft.app.ConversationOpenResolution
import build.raft.app.ConversationOpenSource
import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.settings.HOME_SEARCH_FOCUS_INPUT
import build.raft.app.settings.HOME_SEARCH_PAGE
import build.raft.app.settings.HOME_SEARCH_SELECTED_CHANNEL_ID
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.members.MEMBER_DETAIL_PAGE
import build.raft.app.MemberDetailIdentity
import build.raft.app.MemberDetailIdentitySource
import build.raft.app.members.MemberDetailProfileChannelIdKey
import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.RaftThreadComposerPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.SharedThreadComposerAttachment
import build.raft.app.SharedThreadComposerDraftState
import build.raft.app.available
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
internal data class RaftThreadScreenPayload(
    val instanceId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val status: String = "loading",
    val backgroundRefreshing: Boolean = false,
    /**
     * True while `messages` come from the durable last-known frame (offline cache read entry,
     * task #1123) rather than from a network response. Cleared by the first network content
     * slice; the frame is a first-frame stand-in, never an authority for paging or reactions.
     */
    val durableLastKnown: Boolean = false,
    /**
     * Task #168: the durable stand-in shows the stored window down to the conversation's first
     * message (the window's cursor has loaded the oldest and nothing older is stored), so the top of
     * the list is the real beginning, not an offline cut. Meaningful only with [durableLastKnown].
     */
    val durableReachedOldest: Boolean = false,
    /**
     * #proj-mobile:#388: whether more older stored rows remain inside the SAME proven contiguous
     * SQLite segment below the currently shown stand-in slice. Meaningful only with [durableLastKnown];
     * gates offline older paging when [mainLoadFailed] flips true so offline views stop cleanly at
     * the contiguous segment edge with the single outer boundary row.
     */
    @Transient
    val durableHasOlderStored: Boolean = false,
    /**
     * #proj-mobile:#388: symmetric mirror of [durableHasOlderStored] for anchored offline stand-in
     * newer paging inside the same proven contiguous segment.
     */
    @Transient
    val durableHasNewerStored: Boolean = false,
    /**
     * True while the last main timeline load (not a page of older/newer messages) failed transiently —
     * a network error or an unusable answer. Set and cleared only by the refresh result, so recovery
     * never mistakes a paging failure or a permission answer for it (LiBai #2782).
     */
    val mainLoadFailed: Boolean = false,
    val surfaceKind: String = RaftThreadSurfaceKind.Channel,
    val channelKind: String = "channel",
    val jointChannelId: String? = null,
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val parentSurfaceKind: String? = null,
    val parentChannelKind: String? = null,
    val parentNavigationHint: String? = null,
    val memberScopedMentions: Boolean = false,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val showModeTabs: Boolean = true,
    val alignOwnMessagesRight: Boolean = false,
    val compactConsecutiveMessages: Boolean = false,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = RaftTimeFormat24Hour,
    val messages: List<RaftThreadMessageItem> = emptyList(),
    val originMessage: RaftThreadMessageItem? = null,
    val originPreview: String? = null,
    val originParentMessageId: String? = null,
    val focusedMessageId: String? = null,
    val focusedMessageRequestNonce: Int = 0,
    /**
     * Web #16398 (bug #555): the focused message's context load returned a final not-found
     * (`message_not_found` / 404, not `history_limited`) and fell back quietly to the latest
     * messages / replies. Tells [withRouteContext] and [SharedThreadStore] not to re-apply the
     * stale route's [focusedMessageId]. Page-local, never stored.
     */
    @Transient
    val focusedTargetMissing: Boolean = false,
    /**
     * Bumped by [SharedThreadIntent.FocusHistoryStart]: the list scrolls to where the shown history
     * starts (the history paywall card). Page-local, never stored.
     */
    @Transient
    val historyStartFocusRequest: Int = 0,
    val originStatus: String = "none",
    val originErrorCode: String? = null,
    /**
     * With [originStatus] "error": the original behind the plan's history window, as the
     * `messages/context` refusal shows it (sender, first words). It names the title
     * (ThreadHeaderOriginalTitle.kt); never a row of the list.
     */
    val originHiddenTarget: build.raft.app.data.MessageContextHiddenTarget? = null,
    val tasks: List<RaftThreadTaskItem> = emptyList(),
    val knownMarkdownChannelNames: List<String> = emptyList(),
    val knownMarkdownMentionNames: List<String> = emptyList(),
    val knownMarkdownDirectMessagePeerNames: List<String> = emptyList(),
    val knownMarkdownTaskNumbers: List<Int> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val olderMessagesStatus: String = "idle",
    val newerMessagesStatus: String = "idle",
    val errorCode: String? = null,
    val errorServerMessage: String? = null,
    @Transient
    val errorTraceId: String? = null,
    val emptyTitle: String = "No messages yet",
    val emptyDetail: String = "This conversation has no messages yet.",
    val composer: RaftThreadComposerPayload = RaftThreadComposerPayload(),
    val showComposer: Boolean = true,
    val joined: Boolean = true,
    val canSend: Boolean = true,
    /**
     * The channel this page shows (a reply thread: its parent) is archived: read-only, its
     * composer replaced by the archived banner (web ChatPanel `isArchived`). From the canonical
     * load (`GET /messages/...` `channelArchived`, `GET /channels/:id` `archivedAt`), never from
     * the Home directory: `GET /channels` leaves archived channels out.
     */
    val channelArchived: Boolean = false,
    /**
     * Web `effectiveChannelCapabilities.archiveChannels` from `GET /channels/:id`; null when the
     * load carried no capabilities (the banner then falls back to the server role).
     */
    val channelCanUnarchive: Boolean? = null,
    val membersCount: Int = 0,
    val members: List<RaftThreadMemberItem> = emptyList(),
    val memberCandidates: List<RaftThreadMemberItem> = emptyList(),
    val canAddMembers: Boolean = true,
    val addMemberStatus: String = "idle",
    val addMemberErrorCode: String? = null,
    val removeMemberStatus: String = "idle",
    val removingMemberId: String? = null,
    val removeMemberErrorCode: String? = null,
    val settingsActions: List<RaftThreadSettingsAction> = emptyList(),
    val showMembersAction: Boolean = true,
    val showSettingsAction: Boolean = true,
    val showOpenInChannelAction: Boolean = false,
    val showMessageSaveAction: Boolean = true,
    val showReactionActions: Boolean = true,
    val showTaskOpenActions: Boolean = true,
    val showTaskStatusActions: Boolean = true,
    val joinLabel: String = "Join channel",
    val dmPeerIdentity: MemberDetailIdentity? = null,
    val dmPeerName: String? = null,
    val dmPeerAvatarUrl: String? = null,
    val dmPeerAvatarLabel: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false,
    /** In-process transport provenance; never route/pageData state. */
    @Transient
    val replySummariesAuthority: build.raft.app.data.ThreadReplySummariesAuthority =
        build.raft.app.data.ThreadReplySummariesAuthority.Unavailable,
    /**
     * In-process access provenance: false when the canonical snapshot came from an endpoint that
     * does not attest membership (a synthetic `type = "thread"` detail or a task conversation
     * whose parent detail fetch failed). Never overwrite already-known membership or render Join
     * from an unknown answer.
     */
    @Transient
    val canonicalAccessKnown: Boolean = true,
    /** #proj-mobile:#437: pre-read-advance unread witness for `mobile.push.opened` (`landing_unread`). */
    @Transient
    val landingUnreadWitness: ThreadLandingUnreadWitness = ThreadLandingUnreadWitness.None,
) {
    /** Derived from [surfaceKind] and [channelKind]; never stored, so no constructor can forget it. */
    val showReplyActions: Boolean get() = RaftThreadSurfaceKind.allowsReplyInThread(surfaceKind, channelKind)
}

/**
 * #proj-mobile:#437: non-equality-affecting carrier for pre-read-advance unread count observed on
 * initial canonical load (before `MarkVisibleRead` runs). Mirrors [build.raft.app.data.RawMessagePageWitness].
 */
internal class ThreadLandingUnreadWitness(
    val unreadCount: Int?,
    val serverMaxReadSeq: Long?,
    val truncatedWindow: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is ThreadLandingUnreadWitness

    override fun hashCode(): Int = 0

    override fun toString(): String =
        "ThreadLandingUnreadWitness(unreadCount=$unreadCount, serverMaxReadSeq=$serverMaxReadSeq, truncatedWindow=$truncatedWindow)"

    companion object {
        val None: ThreadLandingUnreadWitness = ThreadLandingUnreadWitness(null, null, false)
    }
}

internal data class ThreadActivityMuteUiState(
    val channelId: String,
    val muted: Boolean,
    val supported: Boolean,
    val muteFromSeq: Long?
)

internal data class ThreadConversationPinUiState(
    val conversationId: String,
    val pinnedKind: String,
    val pinnedId: String,
    val pinned: Boolean,
    val available: Boolean
)

internal fun RaftThreadScreenPayload.threadActivityMuteState(
    projection: SharedConversationDirectoryProjection?
): ThreadActivityMuteUiState? {
    // Route payload self-identity is authoritative (§5): projection enriches mute data,
    // but must not rewrite what surface this page is.
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) return null
    val supported = projection?.activityMuteSupported ?: activityMuteSupported
    val muted = projection?.activityMuted ?: activityMuted
    // Unmute stays reachable while muted even once mute is no longer offered (removed App bot DM).
    if (!supported && !muted) return null
    val resolvedJoined = projection?.joined ?: joined
    if (surfaceKind != RaftThreadSurfaceKind.DirectMessage && !resolvedJoined) return null
    val channelId =
        projection?.canonicalChannelId?.takeIf { it.isNotBlank() }
            ?: streamChannelId?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return null
    return ThreadActivityMuteUiState(
        channelId = channelId,
        muted = muted,
        supported = supported,
        muteFromSeq = projection?.muteFromSeq ?: muteFromSeq
    )
}

internal fun RaftThreadScreenPayload.threadActivityMutedIndicatorVisible(
    projection: SharedConversationDirectoryProjection?
): Boolean {
    return projection?.activityMuted ?: activityMuted
}

internal data class ThreadConversationPinTarget(
    val conversationId: String,
    val kind: String,
    val id: String
)

internal fun RaftThreadScreenPayload.threadConversationPinTarget(
    projection: SharedConversationDirectoryProjection?
): ThreadConversationPinTarget? {
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) return null
    val conversationId =
        projection?.canonicalChannelId?.trim()?.takeIf { it.isNotBlank() }
            ?: streamChannelId?.trim()?.takeIf { it.isNotBlank() }
            ?: sourceThreadId?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
    if (surfaceKind == RaftThreadSurfaceKind.Channel) {
        return ThreadConversationPinTarget(
            conversationId = conversationId,
            kind = "channel",
            id = conversationId
        )
    }
    if (surfaceKind != RaftThreadSurfaceKind.DirectMessage) return null
    val peer = projection?.peerIdentity ?: dmPeerIdentity ?: return null
    val kind =
        when (peer.kind) {
            MemberKind.Agent -> "agent"
            MemberKind.Human -> "human"
            MemberKind.System -> return null
            MemberKind.External -> return null
        }
    return ThreadConversationPinTarget(
        conversationId = conversationId,
        kind = kind,
        id = peer.memberId.value
    )
}

internal fun ThreadConversationPinTarget.toUiState(
    sidebarOrder: SettingsServerSidebarOrderSnapshot?
): ThreadConversationPinUiState {
    val pinned =
        sidebarOrder?.let { order ->
            order.typedPinnedOrLegacy().any { item ->
                item.kind.equals(kind, ignoreCase = true) && item.id == id
            } ||
                conversationId in order.pinnedChannelIds ||
                (kind == "agent" && id in order.pinnedAgentIds) ||
                id in order.pinnedOrder ||
                conversationId in order.pinnedOrder
        } == true
    return ThreadConversationPinUiState(
        conversationId = conversationId,
        pinnedKind = kind,
        pinnedId = id,
        pinned = pinned,
        available = sidebarOrder != null
    )
}

internal fun threadHeaderSettingsActionVisible(
    payloadShowSettingsAction: Boolean,
    surfaceKind: String
): Boolean =
    payloadShowSettingsAction || surfaceKind == RaftThreadSurfaceKind.DirectMessage

internal fun threadHeaderInlineActivityMuteVisible(surfaceKind: String): Boolean =
    surfaceKind != RaftThreadSurfaceKind.DirectMessage

internal fun RaftThreadScreenPayload.joinChannelTargetId(): ChannelId? =
    (parentChannelId?.takeIf { it.isNotBlank() }
        ?: sourceThreadId?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.takeIf { it.isNotBlank() })
        ?.let(::ChannelId)

/**
 * What Home hears of a join here. The row in case Home doesn't list the channel comes only from
 * the channel's own page: a reply's title or a task sheet's other channel isn't that channel's name.
 */
internal fun channelJoinedHomeIntent(
    payload: RaftThreadScreenPayload,
    serverId: String,
    channelId: ChannelId,
): SharedHomeIntent.ChannelJoined =
    SharedHomeIntent.ChannelJoined(
        serverId = serverId,
        channelId = channelId,
        rowIfMissing =
            payload.takeIf {
                it.surfaceKind == RaftThreadSurfaceKind.Channel &&
                    it.joinChannelTargetId() == channelId &&
                    it.title.isNotBlank() &&
                    // Agent Lounge v0: a Lounge is never listed, joined or not.
                    RaftAgentLounges.lounge(serverId, channelId.value) == null
            }?.let {
                HomeChannelRow(
                    threadId = ThreadId(channelId.value),
                    channelId = channelId,
                    channelType = it.channelKind,
                    kind = parseConversationKindOrNull(it.channelKind) ?: ConversationKind.Channel,
                    name = it.title.removePrefix("#"),
                    subtitle = it.subtitle,
                    joined = true,
                )
            },
    )

internal fun RaftThreadScreenPayload.threadForwardSourceChannelId(): ChannelId? =
    (sourceThreadId?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.takeIf { it.isNotBlank() }
        ?: parentChannelId?.takeIf { it.isNotBlank() })
        ?.let(::ChannelId)

/** web `targetLabel(sourceChannel)`: "#design" for a channel or thread, "@peer" for a DM. */
internal fun RaftThreadScreenPayload.threadForwardSourceLabel(): String? {
    val name = title.trim().removePrefix("#").removePrefix("@").takeIf { it.isNotBlank() } ?: return null
    return if (surfaceKind == RaftThreadSurfaceKind.DirectMessage) "@$name" else "#$name"
}

internal fun RaftThreadScreenPayload.activeRealtimeRoomIds(): List<String> =
    listOfNotNull(
        parentChannelId,
        sourceThreadId,
        streamChannelId
    )
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

/**
 * Task #14c: the rooms of [activeRealtimeRoomIds] that are this reply thread's own channel (never its parent
 * channel; empty for a channel / DM page). The page keeps them joined after it closes
 * ([build.raft.app.data.SharedRealtimeRoomLeaseRegistry.keepJoinedUntilClear]).
 */
internal fun RaftThreadScreenPayload.replyThreadRealtimeRoomIds(): Set<String> {
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) return emptySet()
    val parent = parentChannelId?.trim()
    return listOfNotNull(sourceThreadId, streamChannelId)
        .map(String::trim)
        .filter { it.isNotBlank() && it != parent }
        .toSet()
}

/** This page's [message] as a web link ([threadMessageLink] built by [buildRaftMessageLink]). */
internal fun RaftThreadScreenPayload.threadMessagePermalink(
    message: RaftThreadMessageItem,
    serverSlug: String?,
    webBaseUrl: String? = null
): String? = threadMessageLink(message.id, serverSlug)?.let { buildRaftMessageLink(it, webBaseUrl) }

internal fun RaftThreadScreenPayload.consumeComposerSeedForSend(
    sentBody: String,
    sentAttachmentIds: Set<String>
): RaftThreadScreenPayload {
    val sentBodyTrimmed = sentBody.trim()
    val nextSeedText =
        if (composer.seedText.trim() == sentBodyTrimmed) {
            ""
        } else {
            composer.seedText
        }
    val nextPendingAttachments =
        if (sentAttachmentIds.isEmpty()) {
            composer.pendingAttachments
        } else {
            composer.pendingAttachments.filterNot { attachment -> attachment.id in sentAttachmentIds }
        }
    if (nextSeedText == composer.seedText && nextPendingAttachments == composer.pendingAttachments) {
        return this
    }
    return copy(
        composer =
            composer.copy(
                seedText = nextSeedText,
                seedAsTask = if (nextSeedText.isBlank()) false else composer.seedAsTask,
                syncNonce = composer.syncNonce + 1,
                pendingAttachments = nextPendingAttachments
            )
    )
}

internal fun threadComposerReplacementSlotId(
    replaceAttachmentId: String?,
    localAttachmentId: String
): String = replaceAttachmentId?.takeIf { it.isNotBlank() } ?: localAttachmentId

/**
 * Validate the exact post-state receipt for a direct-upload Gallery commit.
 *
 * The reducer is the first ownership fence; this second check is the handoff boundary for the
 * caller which still owns the server reservations.  It compares the set of newly introduced
 * rows with the completed batch, rejects missing/subset/duplicate/unexpected ids, and verifies
 * that every unrelated row present before the commit survives unchanged.
 */
internal fun threadComposerAggregateReceiptIsValid(
    accepted: Boolean,
    before: SharedThreadComposerDraftState,
    after: SharedThreadComposerDraftState,
    expectedDraftKey: String,
    readyAttachments: List<SharedThreadComposerAttachment>,
    expectedLocalAttachmentIds: Collection<String>,
    expectedReplacementAttachmentIds: Collection<String>
): Boolean {
    if (!accepted) return false
    val normalizedExpectedKey = normalizeThreadAttachmentUploadDraftKey(expectedDraftKey) ?: return false
    if (normalizeThreadAttachmentUploadDraftKey(after.draftKey) != normalizedExpectedKey) return false

    val expectedIds = readyAttachments.map { attachment -> attachment.id.trim() }
    if (
        expectedIds.isEmpty() ||
            expectedIds.any(String::isBlank) ||
            expectedIds.toSet().size != expectedIds.size ||
            readyAttachments.any { attachment ->
                attachment.status != "ready" || attachment.errorCode != null
            }
    ) {
        return false
    }
    val expectedIdSet = expectedIds.toSet()
    val localIds = expectedLocalAttachmentIds.map(String::trim)
    val replacementIds = expectedReplacementAttachmentIds.map(String::trim)
    if (
        localIds.isEmpty() ||
            localIds.any(String::isBlank) ||
            localIds.toSet().size != localIds.size ||
            replacementIds.isEmpty() ||
            replacementIds.any(String::isBlank) ||
            replacementIds.toSet().size != replacementIds.size ||
            replacementIds.size != localIds.size
    ) {
        return false
    }
    val localIdSet = localIds.toSet()
    val replacementIdSet = replacementIds.toSet()
    val retiredIdSet = localIdSet + replacementIdSet

    val beforeIds = before.uploadedAttachments.map { attachment -> attachment.id.trim() }
    val afterIds = after.uploadedAttachments.map { attachment -> attachment.id.trim() }
    // Do not use associateBy/filter as a receipt parser: duplicate or blank rows are malformed
    // even when one copy happens to have an expected id.
    if (
        beforeIds.any(String::isBlank) ||
            beforeIds.toSet().size != beforeIds.size ||
            afterIds.any(String::isBlank) ||
            afterIds.toSet().size != afterIds.size
    ) {
        return false
    }
    val beforeIdSet = beforeIds.toSet()
    val afterIdSet = afterIds.toSet()
    val removedIds = after.removedAttachmentIds.map(String::trim).toSet()
    if (
        !retiredIdSet.all { id -> id in beforeIdSet } ||
            replacementIdSet.any { id -> id in afterIdSet } ||
            localIdSet.any { id -> id in afterIdSet && id !in removedIds }
    ) {
        return false
    }

    // Every new row must be one of the exact server ids returned by this batch.  This rejects a
    // forged/extra server id while allowing unrelated pre-existing remote rows to remain.
    if (afterIdSet - beforeIdSet != expectedIdSet) return false
    if (expectedIdSet intersect beforeIdSet != emptySet<String>()) return false

    val afterRowsById = after.uploadedAttachments.associateBy { attachment -> attachment.id.trim() }
    if (
        expectedIdSet.any { id ->
            val row = afterRowsById[id] ?: return@any true
            row.status != "ready" || row.errorCode != null
        }
    ) {
        return false
    }

    // The reducer records both the admitted local rows and their exact replacement slots as
    // removed identities.  Requiring that witness in the receipt prevents an append-only or
    // wrong-slot implementation from being mistaken for a successful replacement.
    if (!removedIds.containsAll(retiredIdSet)) return false

    val beforeRowsById = before.uploadedAttachments.associateBy { attachment -> attachment.id.trim() }
    val unaffectedIds = beforeIdSet - retiredIdSet
    if (
        unaffectedIds.any { id ->
            val afterRow = afterRowsById[id] ?: return@any true
            afterRow != beforeRowsById[id]
        }
    ) {
        return false
    }
    return true
}

internal fun RaftThreadScreenPayload.toThreadMemberDetailPageData(
    identity: MemberDetailIdentity
): JSONObject? {
    val selected = identity.memberId.value.takeIf { it.isNotBlank() } ?: return null
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "thread-member-detail-${instanceId.ifBlank { sourceThreadId.orEmpty() }}-$selected")
        put("memberId", selected)
        put("memberKind", identity.kind.memberKindWireValue())
        identity.sourceServerId?.value?.let { put("sourceServerId", it) }
        if (identity.kind == MemberKind.Human) profileChannelId()?.let { put(MemberDetailProfileChannelIdKey, it) }
        members
            .firstOrNull { it.id == selected && it.sourceServerId == identity.sourceServerId?.value }
            ?.sourceServerLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { put("sourceServerName", it) }
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
    }
}

/**
 * The channel a person's profile opened from this page names their servers in (web MessageItem /
 * TaskConversationDialog `channelId`): a channel's (or task conversation's) own id, a reply
 * thread's parent channel; none from a DM.
 */
internal fun RaftThreadScreenPayload.profileChannelId(): String? =
    when (surfaceKind) {
        RaftThreadSurfaceKind.Channel -> sourceThreadId.nonBlank()
        RaftThreadSurfaceKind.ReplyThread ->
            parentChannelId.nonBlank()?.takeUnless {
                parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage || parentChannelKind == "dm"
            }
        else -> null
    }

/**
 * The conversation the header's Search opens its own search page for (web ChatPanel
 * `handleSearchThisChannel`, on a phone `nav.toConversationSearch(channelType === "dm" ? "dm" :
 * "channel", id)`): a channel's or a DM's own id; none for a reply thread or a task's conversation
 * (they search in place, [ThreadHeaderSearchAction.InlineThreadSearch]).
 */
internal fun RaftThreadScreenPayload.threadChannelSearchChannelId(): String? =
    sourceThreadId
        ?.trim()
        ?.takeIf {
            it.isNotBlank() &&
                (surfaceKind == RaftThreadSurfaceKind.Channel || surfaceKind == RaftThreadSurfaceKind.DirectMessage) &&
                !isTaskConversationSurface()
        }

/** Task v2: this page is a task's own conversation (`channels.type = 'task'`). */
internal fun RaftThreadScreenPayload.isTaskConversationSurface(): Boolean =
    channelKind.normalizedThreadChannelKind() == RaftTaskConversationChannelKind

internal enum class ThreadHeaderSearchAction {
    /** The conversation's own search page (a channel's or a DM's), [threadChannelSearchChannelId]. */
    ChannelSearch,
    /** Search in this thread / task: the find bar over the top of the message list. */
    InlineThreadSearch
}

internal fun RaftThreadScreenPayload.threadHeaderSearchAction(): ThreadHeaderSearchAction? =
    when {
        threadChannelSearchChannelId() != null -> ThreadHeaderSearchAction.ChannelSearch
        // Web Search in Task runs the conversation's own search (ThreadPanel searchCommandRef).
        surfaceKind == RaftThreadSurfaceKind.ReplyThread || isTaskConversationSurface() -> ThreadHeaderSearchAction.InlineThreadSearch
        else -> null
    }

internal fun RaftThreadScreenPayload.threadHeaderOpenInParentActionVisible(): Boolean =
    showOpenInChannelAction ||
        (
            resolveThreadRouteIdentity().surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                parentChannelId?.isNotBlank() == true &&
                (parentMessageId?.isNotBlank() == true || originParentMessageId?.isNotBlank() == true)
            )

internal fun RaftThreadScreenPayload.resolveReplyParentConversationOpenRequest(
    serverId: String,
    conversationIdentityProvider: SharedConversationIdentityProvider?
): HomeMessageOpenRequest? {
    val routeIdentity = resolveThreadRouteIdentity()
    if (routeIdentity.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    val parentKind = routeIdentity.parentConversationKind ?: return null
    val parentId = parentChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val server = threadParentNavigationServerId(serverId)?.let(::ServerId) ?: return null
    // The directory is a candidate projection and may be cold when Activity opens a reply
    // thread. The route's paired parent id + typed kind is sufficient to rebuild the parent
    // identity; if a projection is present, it must still agree with that typed kind.
    val parentProjection = conversationIdentityProvider?.resolveProjection(server, parentId)
    if (
        parentProjection != null &&
        parentProjection.serverId.isNotBlank() &&
        parentProjection.serverId != server.value
    ) {
        return null
    }
    if (
        parentKind == ConversationKind.Joint &&
        isInternalJointStorageIdentity(parentId)
    ) {
        return null
    }
    if (
        parentProjection != null &&
        parentProjection.conversationKind != parentKind
    ) {
        return null
    }
    val directory =
        listOfNotNull(parentProjection)
            .toSharedConversationDirectoryState()
    val sourceIdentity =
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.ParentConversation,
            messageId = null,
            channelId = parentId,
            conversationKind = parentKind
        )
    return (resolveOpenRequest(server.value, sourceIdentity, directory) as? ConversationOpenResolution.Resolved)
        ?.request
}

internal fun threadParentNavigationServerId(currentSessionServerId: String?): String? =
    currentSessionServerId?.trim()?.takeIf { it.isNotBlank() }

internal fun threadHeaderProjectionServerId(
    sessionServerId: String?,
    scopeInitialServerId: String?
): String? =
    sessionServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: scopeInitialServerId?.trim()?.takeIf { it.isNotBlank() }

internal fun threadHeaderInitialProjectionMap(
    provider: SharedConversationIdentityProvider?,
    serverId: ServerId?,
    input: ThreadHeaderProjectionInput
): Map<String, SharedConversationDirectoryProjection> {
    if (provider == null || serverId == null) return emptyMap()
    return input.lookupIds
        .mapNotNull { id ->
            provider.resolveProjection(serverId, id)
                ?.takeIf(input::acceptsForDisplay)
                ?.let { projection -> id to projection }
        }
        .toMap()
}

/** The known-entity candidate for the header's lookup ids, only if it is display-valid. */
internal fun threadHeaderKnownCandidate(
    resolver: RaftKnownEntityResolver,
    accountId: String?,
    serverId: ServerId?,
    input: ThreadHeaderProjectionInput
): SharedConversationDirectoryProjection? {
    val account = accountId?.takeIf { it.isNotBlank() } ?: return null
    val server = serverId ?: return null
    return resolver
        .conversation(KnownEntityScope(accountId = account, serverId = server), input.lookupIds)
        ?.takeIf(input::acceptsForDisplay)
}

internal class ThreadHeaderProjectionStateOwner(
    private val input: ThreadHeaderProjectionInput,
    initialProjections: Map<String, SharedConversationDirectoryProjection>
) {
    private val _state = MutableStateFlow(input.acceptedHeaderProjectionMap(initialProjections))
    val state: StateFlow<Map<String, SharedConversationDirectoryProjection>> = _state.asStateFlow()
    private var lastRejectedProjections: Map<String, SharedConversationDirectoryProjection>? = null

    fun accept(
        projections: Map<String, SharedConversationDirectoryProjection>
    ): ThreadHeaderProjectionAcceptance? {
        val accepted = input.acceptedHeaderProjectionMap(projections)
        if (accepted.isEmpty()) {
            if (lastRejectedProjections == projections) return null
            lastRejectedProjections = projections
            return ThreadHeaderProjectionAcceptance.PreservedNoDisplayValidProjection
        }
        lastRejectedProjections = null
        val merged = _state.value + accepted
        if (_state.value == merged) return null
        _state.value = merged
        return ThreadHeaderProjectionAcceptance.AcceptedDisplayValidProjection
    }

    fun selectedProjection(): SharedConversationDirectoryProjection? =
        input.lookupIds.firstNotNullOfOrNull(_state.value::get)
}

internal enum class ThreadHeaderProjectionAcceptance {
    AcceptedDisplayValidProjection,
    PreservedNoDisplayValidProjection
}

private fun ThreadHeaderProjectionInput.acceptedHeaderProjectionMap(
    projections: Map<String, SharedConversationDirectoryProjection>
): Map<String, SharedConversationDirectoryProjection> =
    lookupIds
        .mapNotNull { id ->
            projections[id]
                ?.takeIf(::acceptsForDisplay)
                ?.let { projection -> id to projection }
        }
        .toMap()

internal fun RaftThreadScreenPayload.toThreadChannelSearchPageData(): JSONObject? {
    val channelId = threadChannelSearchChannelId() ?: return null
    return JSONObject().apply {
        put("pageName", HOME_SEARCH_PAGE)
        put("destination", SharedHomeShellDestinationSearch)
        put(HOME_SEARCH_SELECTED_CHANNEL_ID, channelId)
        put(HOME_SEARCH_FOCUS_INPUT, true)
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
    }
}

internal fun RaftThreadMemberItem.toMemberProfile(): MemberProfile? {
    val identity = memberDetailIdentity() ?: return null
    return MemberProfile(
        id = identity.memberId,
        displayName = displayName.ifBlank { handle.ifBlank { id } },
        handle = handle.ifBlank { displayName.ifBlank { id } },
        kind = identity.kind,
        description = roleLabel.takeIf { it.isNotBlank() },
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = avatarLabel.ifBlank { displayName.ifBlank { handle }.toRaftAvatarLabel() },
        serverId = identity.sourceServerId,
        serverName = sourceServerLabel?.takeIf { it.isNotBlank() }
    )
}

internal fun RaftThreadMessageItem.toMemberProfile(): MemberProfile? {
    val identity = memberDetailIdentity() ?: return null
    return MemberProfile(
        id = identity.memberId,
        displayName = authorName.ifBlank { authorId },
        handle = authorHandle.ifBlank { authorName.ifBlank { authorId } },
        kind = identity.kind,
        avatar = avatarWireValue?.takeIf { it.isNotBlank() }?.let(AvatarReference::fromWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.toRaftAvatarLabel() },
        presence = Presence.Offline
    )
}

internal fun RaftThreadMemberItem.memberDetailIdentity(): MemberDetailIdentity? =
    memberDetailIdentityFromWire(
        memberId = id,
        kindWireValue = kind,
        sourceServerId = sourceServerId,
        source = MemberDetailIdentitySource.ThreadMember
    )

/**
 * A joint channel's sender from another server (web `isRemoteJointAgent`): the resolved actor's
 * home server is the identity's source server, so the profile opens as the remote profile.
 */
internal fun MemberDetailIdentity.withJointAuthorHomeServer(jointAuthor: ResolvedActor?): MemberDetailIdentity {
    if (sourceServerId != null) return this
    val home = jointAuthor
        ?.takeIf { it.id == memberId.value && it.kind == kind }
        ?.homeServerId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return this
    return copy(sourceServerId = ServerId(home))
}

internal fun RaftThreadMessageItem.memberDetailIdentity(): MemberDetailIdentity? {
    // External authors are never member-detail identities; short-circuit before the
    // Identity contract rejects "external" with a diagnostic on every tap.
    if (externalAuthor != null) return null
    return memberDetailIdentityFromWire(
        memberId = authorId,
        kindWireValue = authorKind,
        source = MemberDetailIdentitySource.ThreadMessageAuthor
    )
}

/** What an author avatar/name tap does. */
internal sealed interface ThreadAuthorTapAction {
    data class OpenProfile(val identity: MemberDetailIdentity) : ThreadAuthorTapAction
    /** External (Slack-bridge) author: toast the external hint; never a profile or navigation. */
    data object ExternalHint : ThreadAuthorTapAction
    data object None : ThreadAuthorTapAction
}

/**
 * An External author never opens a profile, whatever its (possibly synthetic) [RaftThreadMessageItem.authorId]:
 * its tap shows the external hint. Other authors open [identity] when they have one.
 */
internal fun RaftThreadMessageItem.threadAuthorTapAction(
    identity: MemberDetailIdentity? = memberDetailIdentity(),
): ThreadAuthorTapAction =
    when {
        externalAuthor != null -> ThreadAuthorTapAction.ExternalHint
        identity != null -> ThreadAuthorTapAction.OpenProfile(identity)
        else -> ThreadAuthorTapAction.None
    }

/**
 * Toast for an External author's avatar/name tap: "External Slack user" when the provider is
 * known, else the generic "External user" (the fallback author of a message the bridge sent
 * without attribution).
 */
internal fun externalAuthorHintText(
    message: RaftThreadMessageItem,
    providerTemplate: String,
    genericHint: String,
): String =
    message.externalAuthor?.providerLabel()
        ?.let { providerTemplate.replace(ThreadLocalizedFeedbackPlaceholder, it) }
        ?: genericHint

internal fun RaftThreadMessageItem.threadAuthorGestureIdentity(): String =
    "${id.trim()}|${authorId.trim()}|${authorKind.trim().lowercase()}"

internal fun RaftThreadScreenPayload.replyThreadHeaderSubtitle(): String? =
    threadChannelHeaderSubtitle(title)
