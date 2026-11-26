package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent

internal data class SharedThreadState(
    val payload: RaftThreadScreenPayload = RaftThreadScreenPayload(),
    val timelineSlice: ThreadTimelineSlice = payload.toThreadTimelineSlice(),
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata> = payload.toThreadReplyMetadataMap(),
    val highlightedMentions: Set<String> = emptySet(),
    val accessResolution: ThreadAccessResolution = ThreadAccessResolution.Loading,
    val canonicalAccessResolved: Boolean = false,
    /**
     * Access was taken from a canonical result whose content generation was superseded (see
     * `applySupersededCanonicalAccess`). Server truth, so a Home candidate cannot override it, but
     * provisional: the refresh's final result still records its own access, positive or negative.
     */
    val provisionalCanonicalAccess: Boolean = false,
    val initialHomeAccessLookupComplete: Boolean = false,
    val initialHomeAccessCandidate: ConversationAccessCandidate? = null,
    /**
     * task #326 diagnostics: result kind + EmptyReason of the last canonical
     * refresh, so a stuck "loading" shell can be told apart from a resolved-but-
     * empty thread without a repro bundle.
     */
    val lastRefreshResultReason: String? = null,
    val replyParentAuthority: ReplyParentAuthorityStatus = ReplyParentAuthorityStatus.Resolving,
    val writeActionsEnabled: Boolean = false,
    val channelProfileUpdateStatus: ChannelProfileUpdateStatus = ChannelProfileUpdateStatus.Idle,
    val channelProfileUpdateErrorCode: String? = null,
    val membersLoadStatus: LoadMembersStatus = LoadMembersStatus.Idle,
    /**
     * Key (typically source thread id) the [membersLoadStatus] is bound to.
     * Switching threads resets the status to Idle for the new key so we
     * never reuse a previous thread's "Loaded" verdict.
     */
    val membersLoadKey: String? = null,
    /**
     * Tenny rule 2: a background validation of a conversation that was painted from cache answered
     * 403 / 404 (see [isConversationAccessLost]). Its cached timeline is already evicted; the page
     * leaves with the "conversation is no longer available" notice. Reset by the next route start.
     */
    val conversationAccessLost: Boolean = false,
    /**
     * With [conversationAccessLost]: the answer was web's channel denial ([isChannelAccessDenial],
     * #12696), so a channel says "You're no longer in this channel" and drops its Activity rows; any
     * other lost access keeps the generic notice.
     */
    val channelAccessDenied: Boolean = false,
    /**
     * The unread-thread pill's parents of this channel's unread followed threads, by thread channel
     * id, resolved from the followed-threads list for parents the page has not loaded (web
     * `FollowedThread.parentMessageId`). Filled by [SharedThreadIntent.ResolveUnreadThreadParents].
     */
    val unreadThreadParents: Map<String, ThreadUnreadThreadParent> = emptyMap(),
    /**
     * The focus request nonce ([RaftThreadScreenPayload.focusedMessageRequestNonce]) issued by the
     * last unread-thread pill jump ([SharedThreadIntent.JumpToMessage]); 0 for none. The page lands
     * that one focus request on the parent's thread replies summary instead of its top (artin
     * task #73); every other focus keeps its own placement. Nonces are process-unique, so a stale
     * value never matches a later focus.
     */
    val unreadThreadJumpFocusNonce: Int = 0,
    /**
     * Web ThreadUnreadJumpPill busy (#11358): a pill jump is loading the messages around a parent
     * that was not loaded. The pill shows "Loading Thread…" and a further tap starts no second
     * jump until the parent lands (or the load fails).
     */
    val unreadThreadJumpLoading: Boolean = false,
    /**
     * #proj-mobile:#265: true only once an initial loading state has remained without renderable
     * content past [ThreadInitialLoadingRevealDelayMs]. Stays false during fast SQLite/cache reads
     * so cached opens do not flash [ThreadLoadingState], and resets on route start / clear.
     */
    val loadingIndicatorRevealed: Boolean = false,
)

/**
 * A background validation result that proves this account can no longer read the conversation:
 * a non-auth 403 (removed / kicked / private) or a 404 (deleted). 401 is an auth-session problem
 * handled by the auth route, and an edge block is a network fact, not an access fact.
 */
/**
 * Web `isChannelAccessDenial` (botiverse/slock store/channelAccessLoss.ts, #12696 / #16896):
 * only the server's answers that mean "this channel, for this person" — either the listed codes
 * (`403 channel_access_lost` / `404 channel_not_found`) or the legacy codeless `denyChannelAccess`
 * bodies. Every other 403 / 404 (not a member of the server, email verification, device sessions,
 * an unknown server) or 401 auth blocker is not a removal. The realtime catch-up reads this one,
 * so a heartbeat never leaves a conversation on another kind of refusal.
 */
internal fun DataResult<*>.isChannelAccessDenial(): Boolean =
    when (this) {
        is DataResult.NoPermission -> {
            if (blocker.code == "auth_required" || blocker.requiredCapability == "auth_session") {
                false
            } else {
                val serverCode = blocker.serverCode?.trim()?.takeIf { it.isNotEmpty() }
                if (serverCode != null) {
                    serverCode == CHANNEL_ACCESS_LOST_REFUSAL_CODE
                } else {
                    blocker.serverMessage?.trim() in ChannelAccessDenial403Messages
                }
            }
        }
        is DataResult.Error -> {
            // The repository's uncoded 404; the thread API's uncoded 404 takes the caller's fallback.
            val is404 = error.code == "http_404" || (error.code.startsWith("thread_") && error.code.endsWith("_network"))
            if (!is404) {
                false
            } else {
                val serverCode = error.serverCode?.trim()?.takeIf { it.isNotEmpty() }
                if (serverCode != null) {
                    serverCode == CHANNEL_NOT_FOUND_REFUSAL_CODE
                } else {
                    error.serverMessage?.trim() in ChannelAccessDenial404Messages
                }
            }
        }
        else -> false
    }

private const val CHANNEL_ACCESS_LOST_REFUSAL_CODE = "channel_access_lost"
private const val CHANNEL_NOT_FOUND_REFUSAL_CODE = "channel_not_found"
private val ChannelAccessDenial403Messages = setOf("Access denied", "You do not have access to this channel")
private val ChannelAccessDenial404Messages = setOf("Channel not found or not visible", "Channel not found")

internal enum class ThreadAccessResolution {
    Loading,
    Resolved,
    Error,
    NoPermission
}

/**
 * Task #228 successor authority model for reply-thread parent write access.
 *
 * "The parent conversation cannot currently be proven writable" is a distinct
 * state from "the parent conversation is provably not writable". Collapsing
 * both into `joined=false` hid the composer permanently whenever the parent
 * directory lookup timed out. The composer stays disabled in every non-
 * [Writable] state; the difference is what the UI explains and whether
 * event-driven re-evaluation may still upgrade the state.
 */
internal enum class ReplyParentAuthorityStatus {
    /** Initial lookup still in flight. */
    Resolving,

    /** Parent directory/server attests joined + canSend. */
    Writable,

    /** Provably not writable: terminal NoPermission/Error or an accepted
     *  candidate that denies join/send. Only realtime membership changes to
     *  the parent row may reopen this; it never times out into Writable. */
    Denied,

    /** Lookup completed without denial and without a candidate: canonical
     *  content is readable but parent write authority is undetermined
     *  (legacy server without parentAccess, or a conversation-list miss).
     *  Event-driven re-evaluation upgrades this as soon as the parent row
     *  or a server-provided parentAccess fact arrives. */
    Unknown,

    /** Network-authoritative Home directory attests the parent row is gone:
     *  the channel was deleted. A later [ApplyParentDirectoryHint] with
     *  `present = true` (recreated/restored row) recomputes from here. */
    ParentDeleted,

}

/**
 * Agent Lounge v0: the reply-parent write authority of a Lounge (web useThreadParentChannel:
 * `parentJoined = parentIsLounge && !parentLoungeReadOnly`; server `canUserPostToChannel`: any
 * non-guest member may post, the first post joins). A Lounge has no Join step, so "joined" is
 * whether you may post here: not archived and not refused by `POST /agents/:id/lounge`.
 */
internal fun agentLoungeParentAccessCandidate(parentId: String, postable: Boolean): ConversationAccessCandidate =
    ConversationAccessCandidate(
        ids = setOf(parentId),
        conversationKind = ConversationKind.Channel,
        surfaceKind = "channel",
        joined = postable,
        archived = false,
        permissions =
            ConversationPermissions(
                canRead = true,
                canSend = postable,
                canAttach = postable,
                canCreateTask = postable,
            ),
    )

/** What [SharedThreadDataSource.replyParentAccessFallback] learned from reading the parent itself. */
internal sealed interface ReplyParentAccessFallbackResult {
    /** The parent answered: [candidate] is its write authority; [lounge] when it is an agent's Lounge. */
    data class Resolved(val candidate: ConversationAccessCandidate, val lounge: Boolean) : ReplyParentAccessFallbackResult

    /** The read failed or did not match the parent; [reason] is the diagnostics reason code. */
    data class Failed(val reason: String) : ReplyParentAccessFallbackResult

    /** Nothing could be asked (no identity / repository / owner); [reason] is the diagnostics reason code. */
    data class Unavailable(val reason: String) : ReplyParentAccessFallbackResult
}

/** Resolving / Unknown: nothing has decided the parent's write authority yet. */
internal fun ReplyParentAuthorityStatus.isUndeterminedReplyParentAuthority(): Boolean =
    this == ReplyParentAuthorityStatus.Resolving || this == ReplyParentAuthorityStatus.Unknown

internal enum class ChannelProfileUpdateStatus {
    Idle,
    InFlight,
    Succeeded,
    Failed
}

internal data class ThreadHomeContextResolution(
    val payload: RaftThreadScreenPayload,
    val accessCandidate: ConversationAccessCandidate? = null,
    val ownerCurrent: Boolean = true,
    val requiresBackgroundHomeContextHydration: Boolean = false,
)

/**
 * UI-owned projection that keeps access/composer facts and timeline load facts
 * in the same StateFlow emission. Collecting these fields independently can
 * briefly pair a resolved membership payload with a still-loading timeline (or
 * vice versa), which makes the footer and LazyColumn change on separate frames.
 */
internal data class SharedThreadRenderSlice(
    val payload: RaftThreadScreenPayload,
    val timelineSlice: ThreadTimelineSlice,
    val replyMetadataByParentId: Map<String, ThreadReplyMetadata>,
    val highlightedMentions: Set<String>,
    val accessResolution: ThreadAccessResolution,
    val canonicalAccessResolved: Boolean,
    val initialHomeAccessLookupComplete: Boolean,
    val lastRefreshResultReason: String?,
    val replyParentAuthority: ReplyParentAuthorityStatus,
    val writeActionsEnabled: Boolean,
    val channelProfileUpdateStatus: ChannelProfileUpdateStatus,
    val channelProfileUpdateErrorCode: String?,
)

internal fun SharedThreadState.toRenderSlice(): SharedThreadRenderSlice =
    SharedThreadRenderSlice(
        payload = payload.withoutThreadTimelineContent(),
        timelineSlice = timelineSlice,
        replyMetadataByParentId = replyMetadataByParentId,
        highlightedMentions = highlightedMentions,
        accessResolution = accessResolution,
        canonicalAccessResolved = canonicalAccessResolved,
        initialHomeAccessLookupComplete = initialHomeAccessLookupComplete,
        lastRefreshResultReason = lastRefreshResultReason,
        replyParentAuthority = replyParentAuthority,
        writeActionsEnabled = writeActionsEnabled,
        channelProfileUpdateStatus = channelProfileUpdateStatus,
        channelProfileUpdateErrorCode = channelProfileUpdateErrorCode,
    )

/**
 * Per-thread members load status. Keyed by [SharedThreadState.membersLoadKey]
 * so that switching to another thread resets the loaded state instead of
 * re-using the previous thread's "Loaded" status.
 */
internal enum class LoadMembersStatus { Idle, InFlight, Loaded, Error }

/**
 * #proj-mobile:#265: true when this store state is in a loading phase that will display
 * [ThreadLoadingState] once [ThreadInitialLoadingRevealDelayMs] elapses (initial load with no
 * messages yet, a durable empty stand-in awaiting the first canonical load, or a non-Chat tab
 * waiting on initial access resolution).
 */
internal fun SharedThreadState.awaitsInitialLoadingIndicator(): Boolean {
    if (accessResolution == ThreadAccessResolution.Error || accessResolution == ThreadAccessResolution.NoPermission) {
        return false
    }
    if (payload.status == "error" || payload.status == "no_permission") return false
    if (
        payload.shouldRenderThreadLoadingShell(
            timelineSlice = payload.toThreadTimelineSlice(),
            accessResolution = accessResolution,
        )
    ) {
        return true
    }
    if (payload.messages.isNotEmpty()) return false
    return payload.status == "loading" ||
        threadEmptyStandInAwaitsFirstLoad(
            status = payload.status,
            durableLastKnown = payload.durableLastKnown,
            mainLoadFailed = payload.mainLoadFailed,
        )
}

internal fun SharedThreadState.withSynchronizedTimelineSlice(): SharedThreadState {
    val nextTimelineSlice =
        payload.toThreadTimelineSlice().copy(loadingIndicatorRevealed = loadingIndicatorRevealed)
    val nextReplyMetadataByParentId = payload.toThreadReplyMetadataMap()
    val timelineChanged = timelineSlice != nextTimelineSlice
    val replyMetadataChanged = replyMetadataByParentId != nextReplyMetadataByParentId
    return if (!timelineChanged && !replyMetadataChanged) {
        this
    } else {
        copy(
            timelineSlice = nextTimelineSlice,
            replyMetadataByParentId = nextReplyMetadataByParentId
        )
    }
}

internal data class ThreadVisibleReadMarker(
    val channelId: String,
    val latestMessageId: String,
    val latestSeq: Long?
)

internal data class ThreadLocalSendComposerSeed(
    val sentBody: String,
    val sentAttachmentIds: Set<String>
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: RaftThreadScreenPayload) : SharedThreadIntent
    data class ApplyRouteIntent(val payload: RaftThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent

    /**
     * Task #228 successor: the foreground Thread page observes the shared Home
     * directory (the same source the initial parent-authority lookup consults)
     * and forwards the row state for this reply thread's exact parent whenever
     * it appears or changes. Pure event-driven re-evaluation - no network, no
     * retry counter.
     */
    data class ApplyParentDirectoryHint(
        val parentChannelId: String,
        val parentMessageId: String,
        val present: Boolean,
        val joined: Boolean,
        val canSend: Boolean,
        /** True only when the hint's snapshot is network-authoritative
         *  (not a durable identity cache and not pending a refresh); a row
         *  absence read from such a snapshot means the channel is deleted. */
        val authoritative: Boolean = false,
    ) : SharedThreadIntent
    data object RetryAccessResolution : SharedThreadIntent
    data class AppendSentPage(val page: ThreadMessagesPage) : SharedThreadIntent
    data class ApplyLocalSendFailure(
        val messageId: String,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val expectedProvenance: ReplyCacheProvenance,
        val expectedRoute: ThreadRouteIdentity,
        /** The send's idempotency key; names its journal row even when the page no longer shows it. */
        val randomId: String? = null,
    ) : SharedThreadIntent
    data class CancelLocalSend(val messageId: String) : SharedThreadIntent
    data class DismissLocalFailedSend(val messageId: String) : SharedThreadIntent
    data class ReplaceLocalSendWithSentPage(
        val messageId: String,
        val page: ThreadMessagesPage,
        val expectedProvenance: ReplyCacheProvenance,
        val expectedRoute: ThreadRouteIdentity
    ) : SharedThreadIntent
    data class ApplyMessageReactionSelection(
        val messageId: String,
        val emoji: String,
        val selected: Boolean
    ) : SharedThreadIntent
    data class ApplyMessageReactions(
        val messageId: String,
        val reactions: List<RaftThreadReactionItem>
    ) : SharedThreadIntent
    data class ApplyReplyThreadRead(
        val parentMessageId: String,
        val threadChannelId: String? = null
    ) : SharedThreadIntent
    data class ApplyMessageThreadFollowed(
        val parentMessageId: String,
        val threadChannelId: String? = null,
        val followed: Boolean,
        val replyCount: Int = 0
    ) : SharedThreadIntent
    data class ApplyMessageMutation(val summary: MessageMutationSummary) : SharedThreadIntent
    data class ApplyActionCard(val messageId: String, val card: ThreadActionCard) : SharedThreadIntent
    data class ApplyMessageSaved(val messageId: String, val saved: Boolean) : SharedThreadIntent
    data class ApplyChannelJoined(val channelId: String) : SharedThreadIntent
    /** Left this public channel from its settings: it stays open, with the composer's Join. */
    data class ApplyChannelLeft(val channelId: String) : SharedThreadIntent
    data class UpdateChannelProfile(
        val channelId: String,
        val originalName: String,
        val originalDescription: String,
        val name: String,
        val description: String
    ) : SharedThreadIntent
    data object ClearChannelProfileUpdateStatus : SharedThreadIntent

    /** The channel's name / description were saved elsewhere (the settings drawer's store): show them. */
    data class ApplyChannelProfile(val channelId: String, val name: String, val description: String?) : SharedThreadIntent
    data class SelectTab(val tab: String) : SharedThreadIntent
    data class FocusMessage(val messageId: String, val switchToTab: String? = null) : SharedThreadIntent

    /**
     * Scroll to where the shown history starts: older pages load until there are none, then the
     * list pins its top, where a Free plan's history paywall card is. A thread beside this
     * conversation asks for it when its original is behind the history window (ThreadHeaderOriginalTitle.kt).
     */
    data class FocusHistoryStart(val switchToTab: String? = null) : SharedThreadIntent

    /**
     * The unread-thread pill could not place these unread threads: their parents are not loaded.
     * Looks them up once in the followed-threads list (single-flight; an id is asked for at most
     * once per page) into [SharedThreadState.unreadThreadParents].
     */
    data class ResolveUnreadThreadParents(val threadChannelIds: Set<String>) : SharedThreadIntent

    /**
     * Web ChatPanel `jumpToMessage`: focus (scroll to and highlight) [messageId], on [switchToTab]
     * when set; when it is not loaded, first replace the window with the messages around it.
     */
    data class JumpToMessage(
        val messageId: String,
        val switchToTab: String? = null,
        /**
         * The unread-thread pill's target thread (the reply thread under [messageId]). After the
         * jump's own load, its replies are prewarmed in the background into the shared thread
         * payload cache the reply page opens from (artin: opening it right after the jump is instant).
         */
        val prewarmThreadChannelId: String? = null,
        /**
         * The unread-thread pill's jump (the default): it lands on the parent's replies summary and
         * holds the pill busy while it loads. False for any other jump (the thread timeline,
         * botiverse/slock #13534): the target lands top-aligned as a link's focus does. (The
         * thread's first message is not a reply: the page scrolls to its row itself.)
         */
        val unreadThreadPill: Boolean = true,
    ) : SharedThreadIntent
    data class ApplyMembersPatch(
        val membersCount: Int,
        val members: List<RaftThreadMemberItem>,
        val memberCandidates: List<RaftThreadMemberItem>,
        val canAddMembers: Boolean,
        val addMemberStatus: String = "idle",
        val addMemberErrorCode: String? = null,
        val removeMemberStatus: String = "idle",
        val removingMemberId: String? = null,
        val removeMemberErrorCode: String? = null
    ) : SharedThreadIntent
    data class SetAddMemberStatus(val status: String, val errorCode: String? = null) : SharedThreadIntent
    data class SetRemoveMemberStatus(
        val status: String,
        val removingMemberId: String?,
        val errorCode: String? = null
    ) : SharedThreadIntent
    data class SetPagingAvailability(
        val canLoadMoreBefore: Boolean,
        val canLoadMoreAfter: Boolean
    ) : SharedThreadIntent
    data class ApplyRealtimeEvent(val event: RealtimeEvent, val activeReplyParentMessageId: MessageId? = null) :
        SharedThreadIntent
    data class MarkVisibleRead(val visibleTailIndex: Int? = null) : SharedThreadIntent

    /**
     * fb-4440a18c: the page no longer shows this conversation (left, covered by another page, app in
     * the background). A deferred or queued visible read is dropped and none goes out until the next
     * [MarkVisibleRead], so messages that arrive after leaving stay unread.
     */
    data object VisibleReadHidden : SharedThreadIntent

    /**
     * botiverse/slock #11687: Mark as Unread from inside an open reply thread (its header ⋯ and its
     * messages' menus). The whole thread, as the server's `/unread` marks it; it then stays unread
     * while this page shows it (its visible reads are held for [threadChannelId]).
     */
    data class MarkThreadUnread(val threadChannelId: String) : SharedThreadIntent

    /**
     * botiverse/slock #14222 (web messageMenuMarkUnread): "Mark channel/DM as unread" from a message's
     * menu in a channel's or DM's list: the conversation this page shows (the sidebar row's Mark as
     * Unread, the same `/unread` write). It stays unread while the person stays on it.
     */
    data object MarkConversationUnread : SharedThreadIntent
    data object FirstContentReady : SharedThreadIntent
    data object LoadMembers : SharedThreadIntent
    data object LoadOlder : SharedThreadIntent
    data object LoadNewer : SharedThreadIntent
    data object RefreshLatestAfterVisibleMaxSeq : SharedThreadIntent
    data class RecoverRealtimeGap(val serverSeq: Long) : SharedThreadIntent
}
