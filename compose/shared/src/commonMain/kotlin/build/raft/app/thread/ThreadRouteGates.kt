package build.raft.app.thread

import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.nonBlank
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.parseConversationKindOrNull
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.threadPayloadOwnerScopeId
import build.raft.app.typedReplyParentConversationKindOrNull
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataResult
import build.raft.app.data.map
import build.raft.app.data.realtime.RealtimeRoutePolicy
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.get
import build.raft.app.messages
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.TaskSummary
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.role
import build.raft.app.RaftBackHandler
import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadComposerDropAttachmentsAction
import build.raft.app.ThreadComposerReorderAttachmentsAction
import build.raft.app.ThreadComposerSuggestionDemand
import build.raft.app.ThreadRouteIdentity
import build.raft.app.action
import kotlinx.coroutines.flow.map

private const val ThreadPerformanceSlowThresholdMs = 4L
internal enum class ThreadTransientBackTarget(val label: String) {
    /** Reply thread head pull-down over the replies and search bar: Back closes it first. */
    OriginalPullDown("thread_original_pull_down"),
    /** Task v2: the Tasks / Files cards covering the conversation; Back closes them all (web). */
    SideCards("conversation_side_cards"),
    ShareSelection("thread_share_selection"),
    InlineSearch("thread_inline_search")
}

internal fun threadTransientBackTarget(
    activeModal: String?,
    shareSelectionActive: Boolean,
    inlineSearchVisible: Boolean,
    sideCardsOpen: Boolean = false,
    originalPullDownOpen: Boolean = false,
): ThreadTransientBackTarget? =
    when {
        activeModal != null -> null
        originalPullDownOpen -> ThreadTransientBackTarget.OriginalPullDown
        // The cards cover the conversation: what is under them (a selection) waits its turn.
        sideCardsOpen -> ThreadTransientBackTarget.SideCards
        shareSelectionActive -> ThreadTransientBackTarget.ShareSelection
        inlineSearchVisible -> ThreadTransientBackTarget.InlineSearch
        else -> null
    }

/** Back for the page's transient states ([threadTransientBackTarget]), the most recent first. */
@Composable
internal fun ThreadTransientBackHandler(target: ThreadTransientBackTarget?, onBack: (ThreadTransientBackTarget) -> Unit) {
    RaftBackHandler(enabled = target != null, label = target?.label) { target?.let(onBack) }
}

internal fun shouldLogThreadPerformance(durationMs: Long): Boolean =
    DEBUG_THREAD_VIRTUAL_RENDER_LOG || durationMs >= ThreadPerformanceSlowThresholdMs

internal fun RaftThreadScreenPayload.shouldRenderThreadEarlyLoadingShell(): Boolean =
    shouldRenderThreadEarlyLoadingShell(toThreadTimelineSlice())

internal fun RaftThreadScreenPayload.shouldRenderThreadEarlyLoadingShell(
    timelineSlice: ThreadTimelineSlice
): Boolean =
    (backgroundRefreshing && timelineSlice.messages.isEmpty() && timelineSlice.originMessage == null && originPreview.isNullOrBlank()) ||
        (
            status == "loading" &&
                timelineSlice.messages.isEmpty() &&
                timelineSlice.originMessage == null &&
                originPreview.isNullOrBlank() &&
                !hasProgressiveThreadLoadingShell()
        )

internal fun RaftThreadScreenPayload.shouldRenderThreadLoadingShell(
    timelineSlice: ThreadTimelineSlice,
    accessResolution: ThreadAccessResolution
): Boolean =
    // An owner/identity-accepted timeline cache is safe to show while canonical access refreshes.
    // Composer admission and every write action remain independently fail-closed on Resolved.
    (
        accessResolution == ThreadAccessResolution.Loading &&
            (
                RaftThreadTab.fromWireValue(selectedTab) != RaftThreadTab.Chat ||
                    timelineSlice.status !in ThreadResolvedContentStatuses
            )
    ) ||
        shouldRenderThreadEarlyLoadingShell(timelineSlice)

internal enum class ThreadRouteMountPlan {
    LoadingShell,
    FeatureSurfaces
}

internal data class ThreadRouteScaffoldPlan(
    val rootIdentity: String,
    val mountLoadingBody: Boolean,
    val mountFeatureBody: Boolean
)

internal fun ThreadRouteMountPlan.toThreadRouteScaffoldPlan(): ThreadRouteScaffoldPlan =
    ThreadRouteScaffoldPlan(
        rootIdentity = "thread-route-scaffold",
        mountLoadingBody = this == ThreadRouteMountPlan.LoadingShell,
        mountFeatureBody = this == ThreadRouteMountPlan.FeatureSurfaces
    )

internal data class ThreadFeatureOwnerMountPlan(
    val mountChatTimeline: Boolean,
    val mountComposer: Boolean,
    val mountTasksOwner: Boolean,
    val mountFilesOwner: Boolean,
    val mountMemberSuggestionsOwner: Boolean,
    val mountChannelSuggestionsOwner: Boolean
)

internal fun threadFeatureOwnerMountPlan(
    routeMountPlan: ThreadRouteMountPlan,
    selectedTab: String?,
    joined: Boolean,
    showComposer: Boolean,
    suggestionDemand: ThreadComposerSuggestionDemand
): ThreadFeatureOwnerMountPlan {
    if (ThreadRouteMountPlan.LoadingShell == routeMountPlan) {
        return ThreadFeatureOwnerMountPlan(
            mountChatTimeline = false,
            mountComposer = false,
            mountTasksOwner = false,
            mountFilesOwner = false,
            mountMemberSuggestionsOwner = false,
            mountChannelSuggestionsOwner = false
        )
    }
    val normalizedTab = selectedTab?.trim()?.lowercase().orEmpty().ifBlank { "chat" }
    val mountChat = normalizedTab == "chat"
    val mountComposer = mountChat && joined && showComposer
    return ThreadFeatureOwnerMountPlan(
        mountChatTimeline = mountChat,
        mountComposer = mountComposer,
        // keepItemAlive pre-composes inactive tabs, so both owners must stay
        // mounted regardless of the selected tab; each VM keeps its store lazy.
        mountTasksOwner = true,
        mountFilesOwner = true,
        mountMemberSuggestionsOwner = mountComposer && suggestionDemand == ThreadComposerSuggestionDemand.Members,
        mountChannelSuggestionsOwner = mountComposer && suggestionDemand == ThreadComposerSuggestionDemand.Channels
    )
}

/**
 * #proj-mobile:#171: a conversation whose content is ready (a memory or durable candidate frame)
 * is shown at once; "Loading" is shown only while there is nothing to show. Two holds used to keep
 * ready content behind the loading shell:
 * - while the local Home access lookup had not answered (Hands e0a5248d), so the timeline would not
 *   appear a frame before its composer. The composer's slot is now reserved at its resting height
 *   while a composer is still expected ([threadReservesPendingComposerSlot], #4207), so the list is
 *   laid out at its final height on the first content frame without waiting;
 * - while the page was sliding in on the Android page stack (#4184), because the first content build
 *   landed on the slide's frames. That build is now kept to the visible rows until the slide settles
 *   (ThreadMessagesList enables its scroll-ahead window only after the motion), and the reveal is
 *   logged (`thread_first_reveal_in_motion`) so nav_transition_frames can be compared.
 */
internal fun RaftThreadScreenPayload.threadRouteMountPlan(
    timelineSlice: ThreadTimelineSlice,
    accessResolution: ThreadAccessResolution
): ThreadRouteMountPlan {
    val showLoadingShell = shouldRenderThreadLoadingShell(timelineSlice, accessResolution)
    return if (showLoadingShell) {
        ThreadRouteMountPlan.LoadingShell
    } else {
        ThreadRouteMountPlan.FeatureSurfaces
    }
}

internal fun ThreadAccessResolution.shouldRenderThreadAccessRetry(): Boolean =
    this == ThreadAccessResolution.Error

internal fun threadRetryAction(canonicalAccessResolved: Boolean): String =
    if (canonicalAccessResolved) "retryThreadAccess" else "retryThreadLoad"

internal fun threadEventRequiresWriteAccess(action: String): Boolean =
    action in
        setOf(
            "followMessageThread",
            "unfollowMessageThread",
            "toggleMessageSaved",
            "commitActionCard",
            "toggleReaction",
            "submitAddMember",
            "removeMember",
            "convertMessageToTask",
            "setTaskStatus",
            "moveTaskToBacklog",
            "setTaskAssignee",
            "markTaskDone",
            "reopenTask",
            "joinChannel",
            "retryLocalSend",
            "cancelLocalSend",
            "deleteLocalFailedSend"
        ) || (action == "settingsAction")

// Task #258: pure selection rules for the inline task chip's combined detail
// panel, extracted so the fail-closed fallback and the assignee ordering contract
// (unassigned sentinel first, humans before agents, System excluded, display-name
// search) are unit-testable without a Composable host.
internal fun threadTaskDetailPanelUseLegacy(linkedTask: TaskSummary?): Boolean = linkedTask == null

/** artin 2026-09-01/02: task assignee picker scope = current channel, never server.
 *  Before the channel member slice loads, reuse the synchronous channel roster
 *  carried by the thread payload (no server-wide flash). */
internal fun taskAssigneeIdentityOptions(
    channelMembers: List<MemberProfile>,
    channelMembersLoaded: Boolean,
    payloadMembers: List<MemberProfile>
): List<MemberProfile> = if (channelMembersLoaded) channelMembers else payloadMembers

internal fun threadTaskAssigneeRows(
    assigneeOptions: List<MemberProfile>,
    search: String
): List<MemberProfile?> {
    val normalized = search.trim()
    return (listOf<MemberProfile?>(null) + assigneeOptions)
        .filter { row -> row == null || (row.kind != MemberKind.System && row.kind != MemberKind.External) }
        .filter { row -> row == null || normalized.isEmpty() || row.displayName.contains(normalized, ignoreCase = true) }
        .sortedWith(
            Comparator<MemberProfile?> { a, b ->
                val aUnassigned = a == null
                val bUnassigned = b == null
                when {
                    aUnassigned != bUnassigned -> if (aUnassigned) -1 else 1
                    aUnassigned -> 0
                    (a!!.kind == MemberKind.Human) != (b!!.kind == MemberKind.Human) ->
                        if (a.kind == MemberKind.Human) -1 else 1
                    else -> a.displayName.lowercase().compareTo(b.displayName.lowercase())
                }
            }
        )
}

internal fun threadTaskAssigneeRowId(row: MemberProfile?): String =
    row?.tasksIdentityFilterId() ?: TasksUnassignedAssigneeFilterId

internal fun resolveThreadTaskAssignee(
    selectionId: String,
    options: List<MemberProfile>
): MemberProfile? =
    options.firstOrNull { it.tasksIdentityFilterId() == selectionId }

internal fun threadComposerEventRequiresWriteAccess(action: String): Boolean =
    action in
        setOf(
            "send",
            "inviteMention",
            "notifyMention",
            "attachImage",
            "toggleOriginalImage",
            "attachFile",
            ThreadComposerDropAttachmentsAction,
            "removeAttachment",
            ThreadComposerReorderAttachmentsAction,
            "retryAttachments"
        )

/** Joint channel billing notice (contract #contracts:97dbd451 v0.3 §18.8). */
internal enum class JointBillingNotice { None, Grace, Locked }

/**
 * Free plan external channel freeze state (botiverse/slock #15653 `utils/jointChannelLimit.ts`).
 * Applies to ANY channel type with `externalFreezeAt` (public, private, joint); reply threads
 * inherit from their parent channel projection.
 */
internal sealed interface ExternalFreezeState {
    data object None : ExternalFreezeState
    data class Upcoming(val deadlineIso: String) : ExternalFreezeState
    data object Active : ExternalFreezeState
}

/**
 * Action label for `ExternalFreezeNotice` / `ExternalFrozenBanner` (web `ChannelLimitBanners.tsx`):
 * matches `raftUpgradeOutcome` so the button label and its action always agree.
 */
internal enum class ExternalFreezeActionKind { ViewBilling, UpgradeToPro, AskOwner }

internal fun externalFreezeActionKind(plan: String?, role: String?): ExternalFreezeActionKind =
    when {
        !plan.isNullOrBlank() && plan != "free" -> ExternalFreezeActionKind.ViewBilling
        role == "owner" -> ExternalFreezeActionKind.UpgradeToPro
        else -> ExternalFreezeActionKind.AskOwner
    }

/** Web `ExternalFrozenBanner`: owners and admins may upgrade or leave extra channels; others ask an owner. */
internal fun externalFrozenUsesManagerCopy(role: String?): Boolean =
    role == "owner" || role == "admin"

internal fun threadLimitLocksWrites(
    jointBillingNotice: JointBillingNotice,
    externalFreezeState: ExternalFreezeState,
): Boolean =
    jointBillingNotice == JointBillingNotice.Locked || externalFreezeState == ExternalFreezeState.Active

/**
 * Resolve the free-plan external channel freeze state for the rendered channel or reply thread.
 * Unlike [jointBillingNoticeState], `externalFreezeAt` applies to any channel type (not only
 * `ConversationKind.Joint`). Reply threads inherit from their parent channel projection; DMs never
 * carry an external freeze.
 */
internal fun externalFreezeState(
    surfaceKind: String?,
    parentChannelId: String?,
    lookupIds: List<String>,
    projections: Map<String, SharedConversationDirectoryProjection>,
    nowEpochMillis: Long? = null,
    taskParentChannelId: String? = null,
): ExternalFreezeState {
    if (surfaceKind == RaftThreadSurfaceKind.DirectMessage) return ExternalFreezeState.None
    fun freezeFor(id: String?): ExternalFreezeState {
        val projection = id?.let { projections[it] } ?: return ExternalFreezeState.None
        if (projection.conversationKind == ConversationKind.DirectMessage ||
            projection.conversationKind == ConversationKind.Thread
        ) {
            return ExternalFreezeState.None
        }
        val freezeAt = projection.externalFreezeAt?.takeIf { it.isNotBlank() }
            ?: return ExternalFreezeState.None
        val deadlineMillis = parseRaftTimestampEpochMillisOrNull(freezeAt, 0)
            ?: return ExternalFreezeState.None
        if (nowEpochMillis != null && nowEpochMillis >= deadlineMillis) {
            return ExternalFreezeState.Active
        }
        return ExternalFreezeState.Upcoming(freezeAt)
    }
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val parent = freezeFor(parentChannelId)
        if (parent != ExternalFreezeState.None) return parent
    }
    if (taskParentChannelId != null) {
        val taskParent = freezeFor(taskParentChannelId)
        if (taskParent != ExternalFreezeState.None) return taskParent
    }
    return lookupIds.firstNotNullOfOrNull { freezeFor(it).takeIf { s -> s != ExternalFreezeState.None } }
        ?: ExternalFreezeState.None
}

/**
 * Earliest upcoming deadline ISO across external freeze and legacy joint grace so a single timer
 * flips the open conversation to read-only locally at the deadline (web `useRerenderAtJointDeadline`).
 * Evaluates both candidates even while an upcoming external freeze suppresses the joint grace
 * banner, so an earlier joint grace expiry still flips the channel to locked on time.
 */
internal fun nextChannelLimitDeadlineIso(
    externalFreeze: ExternalFreezeState,
    jointBillingNotice: JointBillingNotice,
    jointGraceDeadlineIso: String?,
): String? {
    val freezeCandidate = (externalFreeze as? ExternalFreezeState.Upcoming)?.deadlineIso
    val graceCandidate =
        jointGraceDeadlineIso
            ?.takeIf { it.isNotBlank() && jointBillingNotice != JointBillingNotice.Locked }
    val candidates =
        listOfNotNull(freezeCandidate, graceCandidate)
            .mapNotNull { iso -> parseRaftTimestampEpochMillisOrNull(iso, 0)?.let { millis -> iso to millis } }
    return candidates.minByOrNull { it.second }?.first
}

/**
 * The rendered surface is a joint channel, or a reply thread / task conversation in one: the
 * route's own kinds and task channel type first, then the conversation directory projection of
 * the parent or any lookup id.
 */
internal fun threadMessageSurfaceIsJoint(
    payload: RaftThreadScreenPayload,
    lookupIds: List<String>,
    projections: Map<String, SharedConversationDirectoryProjection>,
    taskChannelType: String? = null,
    taskParentChannelId: String? = null,
): Boolean {
    if (payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage) return false
    if (parseConversationKindOrNull(payload.channelKind) == ConversationKind.Joint) return true
    if (parseConversationKindOrNull(taskChannelType) == ConversationKind.Joint) return true
    if (
        payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        typedReplyParentConversationKindOrNull(payload.parentSurfaceKind, payload.parentChannelKind) == ConversationKind.Joint
    ) {
        return true
    }
    return (listOfNotNull(payload.parentChannelId, taskParentChannelId) + lookupIds).any { id ->
        projections[id]?.conversationKind == ConversationKind.Joint
    }
}

/**
 * Resolve the shared joint-channel UUID (`jointChannelId`) for the rendered composer surface so
 * joint channels and reply threads inside them share one personal draft across participating
 * servers. Reply threads inherit `jointChannelId` from their parent joint channel projection.
 */
internal fun threadComposerJointChannelId(
    payload: RaftThreadScreenPayload,
    lookupIds: List<String>,
    projections: Map<String, SharedConversationDirectoryProjection>,
    stableHeaderProjection: SharedConversationDirectoryProjection? = null,
): String? {
    if (payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage || payload.isTaskConversationSurface()) return null
    val isReplyThread = payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread
    if (isReplyThread) {
        return payload.parentChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let { parentId -> projections[parentId]?.jointChannelId?.takeIf { it.isNotBlank() } }
            ?: payload.jointChannelId?.takeIf { it.isNotBlank() }
            ?: lookupIds.firstNotNullOfOrNull { id ->
                projections[id]
                    ?.takeUnless { it.surfaceKind == RaftThreadSurfaceKind.ReplyThread }
                    ?.jointChannelId
                    ?.takeIf { it.isNotBlank() }
            }
    }
    return stableHeaderProjection?.jointChannelId?.takeIf { it.isNotBlank() }
        ?: payload.jointChannelId?.takeIf { it.isNotBlank() }
        ?: lookupIds.firstNotNullOfOrNull { id ->
            projections[id]?.jointChannelId?.takeIf { it.isNotBlank() }
        }
}

/**
 * Resolve the joint over-limit state for the rendered surface. Reply threads
 * inherit the notice from their parent joint channel (thread projections carry
 * no billing metadata). `parentChannelId` takes precedence for reply threads;
 * a channel surface falls back to any joint projection among its lookup ids.
 */
internal fun jointBillingNoticeState(
    surfaceKind: String?,
    parentChannelId: String?,
    lookupIds: List<String>,
    projections: Map<String, SharedConversationDirectoryProjection>,
    nowEpochMillis: Long? = null,
    taskParentChannelId: String? = null,
): JointBillingNotice {
    fun noticeFor(id: String?): JointBillingNotice {
        val projection = id?.let { projections[it] } ?: return JointBillingNotice.None
        if (projection.conversationKind != ConversationKind.Joint) return JointBillingNotice.None
        if (projection.jointBillingLocked == true) return JointBillingNotice.Locked
        val graceEndsAt = projection.jointOverLimitGraceEndsAt?.takeIf { it.isNotBlank() }
            ?: return JointBillingNotice.None
        // §18.8: "now >= graceEndsAt" means the grace period has ended and the
        // channel is read-only — fall back to Locked even if the server has not
        // flipped jointBillingLocked yet, and even if a later externalFreezeAt is
        // present. When no clock is available, keep the deadline-agnostic Grace
        // (fail-open for an unknown now is safer than wrongly locking a still-writable channel).
        val deadlineMillis = parseRaftTimestampEpochMillisOrNull(graceEndsAt, 0)
        if (nowEpochMillis != null && deadlineMillis != null && nowEpochMillis >= deadlineMillis) {
            return JointBillingNotice.Locked
        }
        // Web `jointChannelGraceEndsAt` (botiverse/slock #15653): one notice at a time; when the
        // channel carries a valid `externalFreezeAt`, the external freeze notice replaces joint grace.
        if (projection.externalFreezeAt?.takeIf { it.isNotBlank() }?.let { parseRaftTimestampEpochMillisOrNull(it, 0) } != null) {
            return JointBillingNotice.None
        }
        return JointBillingNotice.Grace
    }
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val parent = noticeFor(parentChannelId)
        if (parent != JointBillingNotice.None) return parent
    }
    if (taskParentChannelId != null) {
        val taskParent = noticeFor(taskParentChannelId)
        if (taskParent != JointBillingNotice.None) return taskParent
    }
    return lookupIds.firstNotNullOfOrNull { noticeFor(it).takeIf { n -> n != JointBillingNotice.None } }
        ?: JointBillingNotice.None
}

private val ThreadLimitLockedBlockedActions =
    setOf(
        "followMessageThread",
        "unfollowMessageThread",
        "toggleMessageSaved",
        "commitActionCard",
        "toggleReaction",
        "submitAddMember",
        "convertMessageToTask",
        "setTaskStatus",
        "moveTaskToBacklog",
        "setTaskAssignee",
        "markTaskDone",
        "reopenTask",
        "joinChannel",
        "retryLocalSend",
        "cancelLocalSend",
        "deleteLocalFailedSend",
    )

internal fun threadEventAllowed(
    writeActionsEnabled: Boolean,
    action: String,
    limitLocked: Boolean = false,
): Boolean {
    if (!writeActionsEnabled && threadEventRequiresWriteAccess(action)) return false
    if (limitLocked && action in ThreadLimitLockedBlockedActions) return false
    return true
}

internal fun threadComposerEventAllowed(writeActionsEnabled: Boolean, action: String): Boolean =
    writeActionsEnabled || !threadComposerEventRequiresWriteAccess(action)

internal data class ThreadLocalSendAuthority(
    val provenance: ReplyCacheProvenance,
    val route: ThreadRouteIdentity
)

/**
 * Whether a user-initiated send may go straight to the server while [SharedThreadState.accessResolution]
 * is still [ThreadAccessResolution.Loading] (e.g. Home directory lookup slow/in-flight on VPN or warm
 * open), letting the server act as the write-permission authority instead of deferring into a
 * client-side timeout.
 *
 * Admits only when there is no known blocker (not limit-locked, not archived, not error/no_permission,
 * not a known-denied/undetermined-after-lookup reply parent, and not a known-unjoined non-reply route)
 * AND the route already carries a concrete send target (`route.timelineChannelId != null`, which
 * excludes brand-new reply threads whose stream channel has not been created yet).
 */
internal fun SharedThreadState.allowsOptimisticSendWhileAccessLoading(
    sendChannelId: String? = null,
    limitLocked: Boolean = false,
): Boolean {
    if (!canDeferOptimisticSendWhileAccessLoading(limitLocked = limitLocked)) return false
    val route = payload.resolveThreadRouteIdentity()
    if (!route.hasCompleteReplyIdentity) return false
    val targetChannelId = route.timelineChannelId ?: return false
    if (sendChannelId != null && sendChannelId != targetChannelId) return false
    return true
}

/**
 * Whether a send tapped during [ThreadAccessResolution.Loading] is eligible to wait briefly in the
 * single-flight optimistic-send slot when no concrete `timelineChannelId` exists yet (for example, a
 * brand-new reply thread waiting for the server to create/resolve its stream channel id).
 */
internal fun SharedThreadState.canDeferOptimisticSendWhileAccessLoading(
    limitLocked: Boolean = false,
): Boolean {
    if (limitLocked) return false
    if (accessResolution != ThreadAccessResolution.Loading) return false
    if (payload.channelArchived) return false
    if (payload.status == "error" || payload.status == "no_permission") return false
    if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        if (
            replyParentAuthority != ReplyParentAuthorityStatus.Resolving &&
            replyParentAuthority != ReplyParentAuthorityStatus.Writable
        ) {
            return false
        }
    } else if (!payload.joined) {
        return false
    }
    return true
}

internal fun SharedThreadState.canAdmitLocalSend(limitLocked: Boolean = false): Boolean {
    if (limitLocked) return false
    if (writeActionsEnabled) return true
    return allowsOptimisticSendWhileAccessLoading(sendChannelId = null, limitLocked = false)
}

internal fun SharedThreadDataSource.captureThreadLocalSendAuthority(
    state: SharedThreadState,
    mountedOwnerProvenance: ReplyCacheProvenance?
): ThreadLocalSendAuthority? {
    if (!state.canAdmitLocalSend()) return null
    val provenance = mountedOwnerProvenance ?: return null
    if (provenance.threadPayloadOwnerScopeId == null) return null
    if (!isCurrentReplyCacheProvenance(provenance)) return null
    val route = state.payload.resolveThreadRouteIdentity()
    if (!route.hasCompleteReplyIdentity) return null
    return ThreadLocalSendAuthority(
        provenance = provenance,
        route = route
    )
}

internal suspend fun <T> runThreadWriteActionIfEnabled(
    writeActionsEnabled: () -> Boolean,
    blockedCode: String,
    action: suspend () -> DataResult<T>
): DataResult<T> =
    if (writeActionsEnabled()) {
        action()
    } else {
        DataResult.Error(ScreenError(code = blockedCode, retryable = false))
    }

internal fun ThreadAttachmentCommentsDialogState?.retainForThreadWriteAuthority(
    writeActionsEnabled: Boolean
): ThreadAttachmentCommentsDialogState? = takeIf { writeActionsEnabled }

internal fun RaftThreadScreenPayload.withThreadWriteActionsEnabled(
    enabled: Boolean,
    preserveSettingsAccess: Boolean = false,
): RaftThreadScreenPayload =
    if (enabled) {
        this
    } else {
        copy(
            canAddMembers = false,
            members = if (preserveSettingsAccess) members else members.map { member -> member.copy(canRemove = false) },
            settingsActions = if (preserveSettingsAccess) settingsActions else emptyList(),
            showSettingsAction = if (preserveSettingsAccess) showSettingsAction else false,
            showMessageSaveAction = false,
            showReactionActions = false,
            showTaskStatusActions = false
        )
    }

internal fun ThreadTimelineSlice.withThreadWriteActionsEnabled(enabled: Boolean): ThreadTimelineSlice =
    if (enabled) {
        this
    } else {
        copy(
            showMessageSaveAction = false,
            showReactionActions = false,
            showTaskStatusActions = false,
            canConvertMessagesToTasks = false
        )
    }

/**
 * Web parity for the per-message menu: Forward needs a non-guest viewer and a source channel
 * (ChatPanel/ThreadPanel `showForwardAction = !isGuest && canForwardFromSource(channel)`), and
 * guests cannot enter Select Message (MessageItem hides it for `currentUserServerRole === "guest"`).
 */
internal fun ThreadTimelineSlice.withThreadViewerMessageActions(
    viewerIsGuest: Boolean,
    forwardSourceAvailable: Boolean,
    hideThreadReplyStart: Boolean = false,
): ThreadTimelineSlice {
    val forward = !viewerIsGuest && forwardSourceAvailable
    // Keep the instance stable when nothing changes so slice-keyed remembers do not churn.
    if (
        this.viewerIsGuest == viewerIsGuest &&
        showForwardAction == forward &&
        this.hideThreadReplyStart == hideThreadReplyStart
    ) {
        return this
    }
    return copy(
        viewerIsGuest = viewerIsGuest,
        showForwardAction = forward,
        hideThreadReplyStart = hideThreadReplyStart,
    )
}

/**
 * DMs without threads, a Lab (botiverse/slock #16664, `dm_main_timeline_lab_v0`):
 * when enabled for the current server and this conversation is a DM with an agent, hide/disable
 * every entry that starts a new thread reply (hover toolbar, classic/context/More menu, row tap,
 * and the Compact menu's Reply tool). Existing threads still open from their replies badge /
 * inline replies; human DMs and channels are unaffected.
 */
internal fun threadDmMainTimelineLabHidesReplyStart(
    flagEnabled: Boolean,
    surfaceKind: String,
    channelKind: String?,
    dmPeerKind: MemberKind?,
): Boolean {
    if (!flagEnabled || !RaftThreadSurfaceKind.allowsReplyInThread(surfaceKind, channelKind)) return false
    val isDmConversation =
        surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
            channelKind.normalizedThreadChannelKind() == "dm"
    return isDmConversation && dmPeerKind == MemberKind.Agent
}

internal fun StoredAuthSession?.threadViewerIsGuest(serverId: String?): Boolean {
    val session = this ?: return false
    val resolvedServerId = serverId?.takeIf { it.isNotBlank() } ?: session.currentServerId
    return session.servers
        .firstOrNull { it.id == resolvedServerId }
        ?.role
        ?.trim()
        ?.equals("guest", ignoreCase = true) == true
}

internal fun handleThreadStoreRetryEvent(
    action: String,
    dispatch: (SharedThreadIntent) -> Unit,
): Boolean {
    val intent =
        when (action) {
            "retryThreadLoad" -> SharedThreadIntent.Refresh
            "retryThreadAccess" -> SharedThreadIntent.RetryAccessResolution
            "retryLoadOlder" -> SharedThreadIntent.LoadOlder
            "retryLoadNewer" -> SharedThreadIntent.LoadNewer
            else -> return false
        }
    dispatch(intent)
    return true
}

private fun RaftThreadScreenPayload.hasProgressiveThreadLoadingShell(): Boolean {
    if (status != "loading") return false
    val routeIdentity = resolveThreadRouteIdentity()
    val timelineKey = routeIdentity.timelineKey?.takeIf { it.isNotBlank() }
    if (timelineKey != null) return true
    val replyParentId = parentMessageId?.takeIf { it.isNotBlank() }
        ?: originParentMessageId?.takeIf { it.isNotBlank() }
    return surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        parentChannelId?.isNotBlank() == true &&
        replyParentId != null
}

/**
 * Load [contextChannelId]'s tasks for this conversation's task refs only when nothing else does: the
 * write-gated [taskChannelId] load covers the same channel, and a task conversation's host channel
 * is loaded by its header's tasks store.
 */
internal fun shouldLoadMarkdownTaskContextTasks(
    payload: RaftThreadScreenPayload,
    contextChannelId: ChannelId,
    taskChannelId: ChannelId?
): Boolean =
    (payload.status == "content" || payload.status == "empty") &&
        payload.channelKind != RaftTaskConversationChannelKind &&
        contextChannelId != taskChannelId

internal fun RaftThreadScreenPayload.replyParentChannelIdForNestedOpen(): String? =
    if (isReplyThreadWithExplicitParentPair()) {
        parentChannelId.nonBlank()
    } else {
        sourceThreadId.nonBlank()
            ?: streamChannelId.nonBlank()
            ?: parentChannelId.nonBlank()
    }

internal fun RaftThreadScreenPayload.isReplyThreadWithExplicitParentPair(): Boolean =
    surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        parentChannelId.nonBlank() != null &&
        replyParentMessageIdForAuxiliary() != null

private fun RaftThreadScreenPayload.replyParentMessageIdForAuxiliary(): String? =
    parentMessageId.nonBlank() ?: originParentMessageId.nonBlank()

internal fun RaftThreadScreenPayload.isReadyForThreadPerformanceGate(): Boolean =
    status == "content" || isRenderableEmptyReplyThread()

internal const val ThreadPayloadReadyBudgetMs = 1_500L
internal const val ThreadFirstLayoutBudgetMs = 100L
internal const val ThreadFirstDrawBudgetMs = 100L

internal class ThreadPerformanceGenerationCounter {
    private var reconcileGeneration = 0L
    private var layoutGeneration = 0L
    private var lastLayoutReconcileGeneration = -1L
    private var lastWidth = -1
    private var lastHeight = -1

    fun nextReconcile(): Long = ++reconcileGeneration

    fun nextLayout(width: Int, height: Int): ThreadLayoutGeneration? {
        if (
            lastLayoutReconcileGeneration == reconcileGeneration &&
            lastWidth == width &&
            lastHeight == height
        ) {
            return null
        }
        lastLayoutReconcileGeneration = reconcileGeneration
        lastWidth = width
        lastHeight = height
        return ThreadLayoutGeneration(
            generation = ++layoutGeneration,
            reconcileGeneration = reconcileGeneration
        )
    }
}

internal data class ThreadLayoutGeneration(
    val generation: Long,
    val reconcileGeneration: Long
)

internal data class ThreadFirstContentPerformanceBudgetResult(
    val payloadReadyWithinBudget: Boolean?,
    val layoutWithinBudget: Boolean?,
    val drawWithinBudget: Boolean?
) {
    val passed: Boolean
        get() {
            val measurements =
                listOfNotNull(
                    payloadReadyWithinBudget,
                    layoutWithinBudget,
                    drawWithinBudget
                )
            return measurements.isNotEmpty() && measurements.all { it }
        }
}

internal fun evaluateThreadFirstContentPerformanceBudget(
    payloadReadyMs: Long? = null,
    layoutMs: Long? = null,
    drawMs: Long? = null
): ThreadFirstContentPerformanceBudgetResult =
    ThreadFirstContentPerformanceBudgetResult(
        payloadReadyWithinBudget = payloadReadyMs?.let { it <= ThreadPayloadReadyBudgetMs },
        layoutWithinBudget = layoutMs?.let { it <= ThreadFirstLayoutBudgetMs },
        drawWithinBudget = drawMs?.let { it <= ThreadFirstDrawBudgetMs }
    )

internal enum class RaftThreadTab(val wireValue: String, val label: String) {
    Chat("chat", "Chat"),
    Tasks("tasks", "Tasks"),
    Files("files", "Files");

    companion object {
        fun fromWireValue(value: String?): RaftThreadTab =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Chat
    }
}

internal val ThreadResolvedContentStatuses = setOf("content", "empty")

internal fun shouldShowThreadComposerForTab(
    joined: Boolean,
    showComposer: Boolean,
    selectedTab: String?
): Boolean =
    joined &&
        showComposer &&
        RaftThreadTab.fromWireValue(selectedTab) == RaftThreadTab.Chat

internal fun shouldShowThreadComposerForReadyContent(
    joined: Boolean,
    showComposer: Boolean,
    selectedTab: String?
): Boolean =
    shouldShowThreadComposerForTab(joined, showComposer, selectedTab)

internal fun RaftThreadScreenPayload.hasRenderableContentForComposer(): Boolean =
    hasRenderableContentForComposer(
        timelineSlice = toThreadTimelineSlice(),
        accessResolution = ThreadAccessResolution.Resolved
    )

internal fun RaftThreadScreenPayload.hasRenderableContentForComposer(
    timelineSlice: ThreadTimelineSlice,
    accessResolution: ThreadAccessResolution = ThreadAccessResolution.Resolved
): Boolean =
    isThreadPrimaryFooterReady(timelineSlice, accessResolution)

internal fun RaftThreadScreenPayload.isThreadPrimaryFooterReady(
    timelineSlice: ThreadTimelineSlice,
    accessResolution: ThreadAccessResolution
): Boolean =
    accessResolution == ThreadAccessResolution.Resolved &&
        status in ThreadResolvedContentStatuses &&
        timelineSlice.status in ThreadResolvedContentStatuses &&
        !shouldRenderThreadEarlyLoadingShell(timelineSlice)

/**
 * Whether a conversation whose primary footer is not yet resolved ([isThreadPrimaryFooterReady] is
 * false — e.g. warm/durable frame while access refreshes, or cold loading shell) should show the
 * optimistic first-frame composer instead of an empty blank placeholder.
 *
 * Admits only when there is NO known blocker: no access/load error, not permission-denied, not
 * limit-locked/frozen, not archived, not in an Agent Lounge footer state, on the Chat tab, with
 * composer enabled on the route surface, and not already known to be unjoined (reply threads whose
 * parent authority is still [ReplyParentAuthorityStatus.Resolving] are admitted optimistically
 * because cached/route reply payloads start with `joined = false` until parent authority resolves).
 */
internal fun threadShowsOptimisticComposer(
    accessResolution: ThreadAccessResolution,
    payloadStatus: String,
    surfaceKind: String,
    joined: Boolean,
    showComposer: Boolean,
    selectedTab: String?,
    composerLocked: Boolean,
    channelArchived: Boolean = false,
    externalFrozen: Boolean = false,
    loungeFooterActive: Boolean = false,
    replyParentAuthority: ReplyParentAuthorityStatus = ReplyParentAuthorityStatus.Resolving,
): Boolean {
    if (accessResolution == ThreadAccessResolution.Error || accessResolution == ThreadAccessResolution.NoPermission) {
        return false
    }
    if (payloadStatus == "error" || payloadStatus == "no_permission") return false
    if (composerLocked || channelArchived || externalFrozen || loungeFooterActive) return false
    if (!showComposer) return false
    if (RaftThreadTab.fromWireValue(selectedTab) != RaftThreadTab.Chat) return false
    return joined ||
        (surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
            replyParentAuthority == ReplyParentAuthorityStatus.Resolving)
}

/**
 * Channel id used to key the composer's draft and surface during optimistic pre-resolution.
 * Matches [RaftThreadScreenPayload.resolvedAuxiliaryThreadId] whenever available; during a cold
 * reply-thread load (`status == "loading"`) where `resolvedAuxiliaryThreadId()` returns null,
 * falls back to the route's known `timelineChannelId` (e.g. opened from Activity or reply badge),
 * or to `parentChannelId` (matching `readWithReplyFallback` so any draft typed before the server
 * assigns a reply stream id migrates automatically).
 */
internal fun RaftThreadScreenPayload.optimisticComposerChannelId(
    resolvedAuxiliaryThreadId: String? = resolvedAuxiliaryThreadId(),
): String? =
    resolvedAuxiliaryThreadId?.takeIf { it.isNotBlank() }
        ?: resolveThreadRouteIdentity().timelineChannelId?.takeIf { it.isNotBlank() }
        ?: parentChannelId?.takeIf { surfaceKind == RaftThreadSurfaceKind.ReplyThread && it.isNotBlank() }

/**
 * Authoritative send and attachment-upload target channel id for the composer: matches
 * [resolvedAuxiliaryThreadId], or the route's concrete `timelineChannelId` while a reply thread
 * with a known stream id is still loading. Unlike [optimisticComposerChannelId], this never falls
 * back to `parentChannelId` for a reply thread whose stream channel id has not resolved yet.
 */
internal fun RaftThreadScreenPayload.composerSendTargetChannelId(
    resolvedAuxiliaryThreadId: String? = resolvedAuxiliaryThreadId(),
): String? =
    resolvedAuxiliaryThreadId?.takeIf { it.isNotBlank() }
        ?: resolveThreadRouteIdentity().timelineChannelId?.takeIf { it.isNotBlank() }

internal fun shouldDeliverToActiveThreadRealtimeSignal(event: RealtimeEvent): Boolean =
    RealtimeRoutePolicy.shouldForwardToActiveThread(event) || event is Heartbeat
