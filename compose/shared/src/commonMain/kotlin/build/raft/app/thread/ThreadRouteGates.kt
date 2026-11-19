package build.raft.app

import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataResult
import build.raft.app.data.map
import build.raft.app.data.realtime.RealtimeRoutePolicy
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.tasksIdentityFilterId
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
import kotlinx.coroutines.flow.map

private const val ThreadPerformanceSlowThresholdMs = 4L
internal enum class ThreadTransientBackTarget(val label: String) {
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
): ThreadTransientBackTarget? =
    when {
        activeModal != null -> null
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

/**
 * Hands e0a5248d: whether this thread open still holds its first content frame behind the loading
 * shell. A memory or durable candidate frame is often ready a few frames before the local Home
 * access lookup answers; revealing it then shows the timeline without its composer, which mounts
 * one emission later and pushes the bottom-anchored list up by the composer height. The hold lasts
 * only until that bounded, offline-safe local lookup has answered (or access settled another way),
 * so timeline and composer appear in the same frame. Once revealed, a later refresh that re-runs
 * the lookup never puts the shell back over the content.
 *
 * [pageInMotion] ([LocalRaftStackPageInMotion]): the page is still sliding in on the Android page
 * stack. The first content build (the message list's rows, markdown, avatars, the composer) takes
 * 80-340 ms on a mid-range phone; landing mid slide it froze the push (nav_transition_frames: 3-19
 * frames in 440-840 ms). The light shell slides in instead and the content is built the frame the
 * slide settles, when the page also turns active. Only the first reveal waits: content already
 * shown (a pop back onto the chat) is never hidden again.
 */
internal fun threadHoldsFirstContentReveal(
    contentRevealed: Boolean,
    accessResolution: ThreadAccessResolution,
    initialHomeAccessLookupComplete: Boolean,
    pageInMotion: Boolean = false
): Boolean =
    !contentRevealed &&
        (
            pageInMotion ||
                (accessResolution == ThreadAccessResolution.Loading && !initialHomeAccessLookupComplete)
        )

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

internal fun RaftThreadScreenPayload.threadRouteMountPlan(
    timelineSlice: ThreadTimelineSlice,
    accessResolution: ThreadAccessResolution,
    holdFirstContentReveal: Boolean = false
): ThreadRouteMountPlan {
    val showLoadingShell =
        holdFirstContentReveal || shouldRenderThreadLoadingShell(timelineSlice, accessResolution)
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
 * The rendered surface is a joint channel, or a reply thread in one: the route's own kinds first,
 * then the conversation directory projection of the parent or any lookup id.
 */
internal fun threadMessageSurfaceIsJoint(
    payload: RaftThreadScreenPayload,
    lookupIds: List<String>,
    projections: Map<String, SharedConversationDirectoryProjection>
): Boolean {
    if (payload.surfaceKind == RaftThreadSurfaceKind.DirectMessage) return false
    if (parseConversationKindOrNull(payload.channelKind) == ConversationKind.Joint) return true
    if (
        payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        typedReplyParentConversationKindOrNull(payload.parentSurfaceKind, payload.parentChannelKind) == ConversationKind.Joint
    ) {
        return true
    }
    return (listOfNotNull(payload.parentChannelId) + lookupIds).any { id ->
        projections[id]?.conversationKind == ConversationKind.Joint
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
    nowEpochMillis: Long? = null
): JointBillingNotice {
    fun noticeFor(id: String?): JointBillingNotice {
        val projection = id?.let { projections[it] } ?: return JointBillingNotice.None
        if (projection.conversationKind != ConversationKind.Joint) return JointBillingNotice.None
        if (projection.jointBillingLocked == true) return JointBillingNotice.Locked
        val graceEndsAt = projection.jointOverLimitGraceEndsAt?.takeIf { it.isNotBlank() }
            ?: return JointBillingNotice.None
        // §18.8: "now >= graceEndsAt" means the grace period has ended and the
        // channel is read-only — fall back to Locked even if the server has not
        // flipped jointBillingLocked yet. When no clock is available, keep the
        // deadline-agnostic Grace (fail-open for an unknown now is safer than
        // wrongly locking a still-writable channel).
        val deadlineMillis = parseRaftTimestampEpochMillisOrNull(graceEndsAt, 0)
        if (nowEpochMillis != null && deadlineMillis != null && nowEpochMillis >= deadlineMillis) {
            return JointBillingNotice.Locked
        }
        return JointBillingNotice.Grace
    }
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val parent = noticeFor(parentChannelId)
        if (parent != JointBillingNotice.None) return parent
    }
    return lookupIds.firstNotNullOfOrNull { noticeFor(it).takeIf { n -> n != JointBillingNotice.None } }
        ?: JointBillingNotice.None
}

internal fun threadEventAllowed(writeActionsEnabled: Boolean, action: String): Boolean =
    writeActionsEnabled || !threadEventRequiresWriteAccess(action)

internal fun threadComposerEventAllowed(writeActionsEnabled: Boolean, action: String): Boolean =
    writeActionsEnabled || !threadComposerEventRequiresWriteAccess(action)

internal data class ThreadLocalSendAuthority(
    val provenance: ReplyCacheProvenance,
    val route: ThreadRouteIdentity
)

internal fun SharedThreadDataSource.captureThreadLocalSendAuthority(
    state: SharedThreadState,
    mountedOwnerProvenance: ReplyCacheProvenance?
): ThreadLocalSendAuthority? {
    if (!state.writeActionsEnabled) return null
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
    enabled: Boolean
): RaftThreadScreenPayload =
    if (enabled) {
        this
    } else {
        copy(
            canAddMembers = false,
            members = members.map { member -> member.copy(canRemove = false) },
            settingsActions = emptyList(),
            showSettingsAction = false,
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
    forwardSourceAvailable: Boolean
): ThreadTimelineSlice {
    val forward = !viewerIsGuest && forwardSourceAvailable
    // Keep the instance stable when nothing changes so slice-keyed remembers do not churn.
    if (this.viewerIsGuest == viewerIsGuest && showForwardAction == forward) return this
    return copy(viewerIsGuest = viewerIsGuest, showForwardAction = forward)
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

private val ThreadResolvedContentStatuses = setOf("content", "empty")

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

internal fun shouldDeliverToActiveThreadRealtimeSignal(event: RealtimeEvent): Boolean =
    RealtimeRoutePolicy.shouldForwardToActiveThread(event) || event is Heartbeat
