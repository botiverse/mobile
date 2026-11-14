package build.raft.app.data

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.RaftAdaptiveDetailIdentity
import build.raft.app.RaftAdaptiveDetailSelection
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftAdaptiveWorkspaceIntent
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.acceptsDetailCallback
import build.raft.app.nextAdaptiveGeneration
import build.raft.app.optString
import build.raft.app.reduceRaftAdaptiveWorkspace
import build.raft.app.toRaftAdaptiveDetailCloseRequestOrNull
import build.raft.app.toRaftAdaptiveDetailIdentity
import build.raft.app.RaftPaneGridColumns
import build.raft.app.withEntry
import build.raft.designsystem.components.RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP
import build.raft.designsystem.components.RAFT_ROW_THREAD
import build.raft.designsystem.components.RaftRowPlanColumn
import build.raft.designsystem.components.RaftRowPlanInput
import build.raft.designsystem.components.RaftRowPlanSize
import build.raft.designsystem.components.raftRowDecideFold
import kotlinx.serialization.json.JsonObject

/** The reply-thread column's default width beside the channel (web `COLUMN_BOUNDS.thread`); it is kept per device. */
internal const val RAFT_SIDE_THREAD_COLUMN_DP = 400f

/** Divider between the channel body and the thread column. */
internal const val RAFT_SIDE_THREAD_DIVIDER_DP = 1f

/**
 * A reply thread shown in its own column beside the selected channel/DM ([owner]). It has its own
 * identity and generation, so its close request only ever closes the column.
 */
internal data class AdaptiveSideThread(
    val owner: RaftAdaptiveDetailSelection,
    val identity: RaftAdaptiveDetailIdentity,
    val payload: WireThreadLaunchPayload,
    val generation: Long,
) {
    fun closeRequest(shellInstanceId: String): RaftAdaptiveDetailCloseRequest =
        RaftAdaptiveDetailCloseRequest(shellInstanceId, identity.key, generation)
}

/**
 * #189 rule 3 (web rowPlan `decideFold`) where only the conversation and the thread share [roomDp], both at their
 * defaults (the conversation what the thread leaves, at least 400; the thread 400): the thread folds where the two
 * can't keep 360 each, under 720.
 */
internal fun raftSideThreadFoldsAlone(roomDp: Float): Boolean {
    val thread = RaftPaneGridColumns.Thread
    return raftRowDecideFold(
        RaftRowPlanInput(
            available = roomDp,
            conversation =
                RaftRowPlanSize(
                    saved = maxOf(RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP, roomDp - thread.defaultWidthDp),
                    contentMin = RaftPaneGridColumns.Conversation.minWidthDp,
                ),
            thread = RaftRowPlanSize(thread.defaultWidthDp, thread.minWidthDp),
            columns = listOf(RaftRowPlanColumn(thread.id, listOf(RAFT_ROW_THREAD), listOf(thread.minWidthDp), thread.defaultWidthDp)),
        )
    ).fold
}

/**
 * Whether the side column stands beside the conversation (#189 rule 3, web rowPlan `decideFold`): in desktop mode
 * and a dual pane, where the row plan does not fold the thread into the conversation. [threadFolds] is the plan's
 * answer for the row the detail stands in ([build.raft.app.raftPaneGridFolds]: every column in it and the
 * conversation at their kept widths); without one, [detailPaneWidthDp] (the room the detail and its columns have,
 * after the rail, the list and insets) decides with the conversation and the thread alone at their defaults. Unknown
 * width (not yet measured) means no column.
 *
 * (Before #189 the column needed a window of 1200 and a detail of 841: 440 + 1 + 400.)
 */
internal fun raftSideThreadColumnAvailable(
    desktopModeOnLargeScreen: Boolean,
    pane: RaftAdaptivePaneState,
    detailPaneWidthDp: Float?,
    threadFolds: Boolean? = null,
): Boolean =
    desktopModeOnLargeScreen &&
        pane.layout == RaftAdaptivePaneLayout.DualPane &&
        pane.density > 0f &&
        detailPaneWidthDp != null &&
        !(threadFolds ?: raftSideThreadFoldsAlone(detailPaneWidthDp))

/** task #36: what the caller of [openSideThread] must do. */
internal sealed interface AdaptiveSideThreadOpen {
    /** The thread now shows in the side column; store [session]. */
    data class Opened(val session: SharedAdaptiveWorkspaceSession) : AdaptiveSideThreadOpen

    /**
     * A valid, current source whose thread cannot go in the column (no room, not a reply under the
     * shown channel, or the main detail is itself a thread): use the existing in-pane replace.
     */
    data object FallbackToReplace : AdaptiveSideThreadOpen

    /**
     * Stale or foreign source (an old generation, another shell/server/account, a hidden
     * workspace): do nothing — no replace, no column change, no history.
     */
    data object Ignored : AdaptiveSideThreadOpen
}

/**
 * Opens [target] as the side thread when it is a reply thread under the channel/DM shown in the
 * main detail and the column has room. [source] is the close request of the page asking — the main
 * detail or the current side thread.
 */
internal fun SharedAdaptiveWorkspaceSession.openSideThread(
    source: RaftAdaptiveDetailCloseRequest,
    target: WireThreadLaunchPayload,
    serverId: String,
    columnAvailable: Boolean,
): AdaptiveSideThreadOpen {
    val current = workspace ?: return AdaptiveSideThreadOpen.Ignored
    val main = current.selection ?: return AdaptiveSideThreadOpen.Ignored
    if (current.shellInstanceId != source.shellInstanceId || !current.parentVisible ||
        current.pane.layout != RaftAdaptivePaneLayout.DualPane || current.serverId != serverId
    ) return AdaptiveSideThreadOpen.Ignored
    val fromMain = current.acceptsDetailCallback(source.identityKey, source.generation)
    val side = sideThread
    val fromSide = side != null && side.owner == main &&
        side.identity.key == source.identityKey && side.generation == source.generation
    if (!fromMain && !fromSide) return AdaptiveSideThreadOpen.Ignored
    if (!columnAvailable || main.identity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        return AdaptiveSideThreadOpen.FallbackToReplace
    }
    val identity = target.toRaftAdaptiveDetailIdentity(serverId) ?: return AdaptiveSideThreadOpen.FallbackToReplace
    val mainChannelId = main.identity.channelId ?: main.identity.threadId
    if (identity.surfaceKind != RaftThreadSurfaceKind.ReplyThread || identity.parentChannelId != mainChannelId) {
        return AdaptiveSideThreadOpen.FallbackToReplace
    }
    if (side?.identity == identity) return AdaptiveSideThreadOpen.Opened(this)
    // Side generations come from the workspace's single cursor, so a side thread never shares a
    // generation with any main selection (e.g. after it folds into the main detail) and a stale
    // side close/open can never be taken for a main-detail request.
    val generation = current.generationCursor.nextAdaptiveGeneration()
    return AdaptiveSideThreadOpen.Opened(
        copy(
            workspace = current.copy(generationCursor = generation),
            sideThread = AdaptiveSideThread(main, identity, target, generation),
        )
    )
}

/** Whether this session's current host can show the side column right now. */
internal fun SharedAdaptiveWorkspaceSession.sideThreadColumnAvailable(desktopModeOnLargeScreen: Boolean): Boolean {
    val pane = workspace?.pane ?: return false
    return raftSideThreadColumnAvailable(desktopModeOnLargeScreen, pane, detailPaneWidthDp, sideThreadFolds)
}

/**
 * The side thread to render beside the main detail, or null when there is none for the current
 * selection.
 */
internal fun SharedAdaptiveWorkspaceSession.visibleSideThread(): AdaptiveSideThread? =
    sideThread?.takeIf { it.owner == workspace?.selection }

/**
 * Routes a thread open requested from inside the wide detail (main page or side column):
 * side column first, then the existing in-pane replace for a valid source that cannot use it.
 * An ignored (stale/foreign) source leaves the session untouched.
 */
internal fun SharedAdaptiveWorkspaceSession.openWithinWideDetail(
    source: RaftAdaptiveDetailCloseRequest,
    target: WireThreadLaunchPayload,
    serverId: String,
    desktopModeOnLargeScreen: Boolean,
): SharedAdaptiveWorkspaceSession =
    when (val outcome = openSideThread(source, target, serverId, sideThreadColumnAvailable(desktopModeOnLargeScreen))) {
        is AdaptiveSideThreadOpen.Opened -> outcome.session
        AdaptiveSideThreadOpen.Ignored -> this
        AdaptiveSideThreadOpen.FallbackToReplace -> {
            // A valid side-column source replaces the main detail on behalf of its owner.
            val main = workspace?.selection
            val replaceSource =
                if (main != null && workspace?.acceptsDetailCallback(source.identityKey, source.generation) != true) {
                    RaftAdaptiveDetailCloseRequest(source.shellInstanceId, main.identity.key, main.generation)
                } else source
            navigateDetail(replaceSource, target, serverId)
        }
    }

/**
 * Whether [source] is a live adaptive detail page of this session: the current main detail or the
 * current side thread of that detail. Retired pages (an older main generation, a closed or folded
 * side thread) are not.
 */
internal fun SharedAdaptiveWorkspaceSession.acceptsAdaptiveSource(source: RaftAdaptiveDetailCloseRequest): Boolean {
    val current = workspace ?: return false
    if (current.shellInstanceId != source.shellInstanceId) return false
    if (current.acceptsDetailCallback(source.identityKey, source.generation)) return true
    val side = sideThread ?: return false
    return side.owner == current.selection &&
        side.identity.key == source.identityKey && side.generation == source.generation
}

/** Where a thread page routes a thread open. */
internal enum class AdaptiveThreadOpenRoute { WithinDetail, Standalone, Ignored }

/**
 * A page outside the adaptive detail ([source] null) opens a standalone page as before. An adaptive
 * page is validated first: a retired one opens nothing, whatever the desktop mode, pane or server;
 * only a live one may use the standalone fallback when the wide detail cannot take the open.
 */
internal fun SharedAdaptiveWorkspaceSession.routeThreadOpen(
    source: RaftAdaptiveDetailCloseRequest?,
    desktopModeEnabled: Boolean,
    targetServerId: String?,
): AdaptiveThreadOpenRoute = when {
    source == null -> AdaptiveThreadOpenRoute.Standalone
    !acceptsAdaptiveSource(source) -> AdaptiveThreadOpenRoute.Ignored
    shouldOpenWithinAdaptiveDetail(source, workspace, desktopModeEnabled, targetServerId) ->
        AdaptiveThreadOpenRoute.WithinDetail
    else -> AdaptiveThreadOpenRoute.Standalone
}

/**
 * The shell's handler for a detail page's close event: a side-column page closes only the column;
 * otherwise the existing one-level Back of the main detail. A retired request changes nothing.
 */
internal fun SharedAdaptiveWorkspaceSession.closeFromDetailPage(
    request: RaftAdaptiveDetailCloseRequest,
): SharedAdaptiveWorkspaceSession {
    val closedSide = closeSideThread(request)
    return if (closedSide !== this) closedSide else backFromDetail(request)
}

/** Back on a visible detail with its side column showing closes the column first. */
internal fun SharedAdaptiveWorkspaceSession.backClosesSideThreadFirst(desktopModeOnLargeScreen: Boolean): Boolean =
    workspace?.parentVisible == true &&
        visibleSideThread() != null &&
        sideThreadColumnAvailable(desktopModeOnLargeScreen)

/** Closes the side column when [request] is the current side thread's own close request. */
internal fun SharedAdaptiveWorkspaceSession.closeSideThread(
    request: RaftAdaptiveDetailCloseRequest,
): SharedAdaptiveWorkspaceSession {
    val side = sideThread ?: return this
    if (workspace?.shellInstanceId != request.shellInstanceId ||
        side.identity.key != request.identityKey || side.generation != request.generation
    ) return this
    return copy(sideThread = null)
}

/**
 * When the row can no longer hold the column (#189 rule 3), the side thread becomes the main detail with the
 * channel in its Back history — the same shape the in-pane replace produces — so nothing is lost
 * and Back still returns to the channel.
 */
internal fun SharedAdaptiveWorkspaceSession.foldSideThreadIntoDetail(): SharedAdaptiveWorkspaceSession {
    val side = sideThread ?: return this
    val current = workspace ?: return copy(sideThread = null)
    val mainPayload = launchPayload ?: return copy(sideThread = null)
    if (current.selection != side.owner) return copy(sideThread = null)
    val next = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.ParkSelection(side.identity))
    val selection = next.selection?.takeIf { it.identity == side.identity } ?: return copy(sideThread = null)
    val previous = detailHistory?.takeIf { it.owner == current.selection }?.previous.orEmpty()
    return copy(
        workspace = next,
        launchPayload = side.payload,
        detailHistory = AdaptiveDetailHistory(selection, previous + mainPayload),
        sideThread = null,
    )
}

/** task #36: the side thread's "open parent channel" hands focus to the main detail's parent message. */
internal const val RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT = "slockAdaptiveDetailFocusMessage"
internal const val RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_ID = "slockAdaptiveDetailFocusMessageId"

/** Focus [messageId] in the detail page identified by [target] (its own key/generation). */
internal data class AdaptiveParentFocusRequest(
    val target: RaftAdaptiveDetailCloseRequest,
    val messageId: String,
) {
    fun toJson(): JsonObject = target.toJson().withEntry(RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_ID, messageId)
}

internal fun JsonObject.toAdaptiveParentFocusRequestOrNull(): AdaptiveParentFocusRequest? {
    val target = toRaftAdaptiveDetailCloseRequestOrNull() ?: return null
    val messageId = optString(RAFT_ADAPTIVE_DETAIL_FOCUS_MESSAGE_ID).trim().takeIf(String::isNotBlank) ?: return null
    return AdaptiveParentFocusRequest(target, messageId)
}

/**
 * The side column's parent action: when [source] is the live side thread, the request that makes
 * the main detail (its parent channel) focus the parent message — the column stays open. Null for
 * any other page, so the existing parent navigation applies.
 */
internal fun SharedAdaptiveWorkspaceSession.sideThreadParentFocus(
    source: RaftAdaptiveDetailCloseRequest,
): AdaptiveParentFocusRequest? {
    val current = workspace ?: return null
    val main = current.selection ?: return null
    val side = visibleSideThread() ?: return null
    if (current.shellInstanceId != source.shellInstanceId ||
        side.identity.key != source.identityKey || side.generation != source.generation
    ) return null
    val messageId = side.payload.parentMessageId?.trim()?.takeIf(String::isNotBlank) ?: return null
    return AdaptiveParentFocusRequest(
        RaftAdaptiveDetailCloseRequest(current.shellInstanceId, main.identity.key, main.generation),
        messageId,
    )
}

/** Whether the detail page [page] applies [request]: addressed to it and it is still the main detail. */
internal fun SharedAdaptiveWorkspaceSession.acceptsParentFocus(
    page: RaftAdaptiveDetailCloseRequest,
    request: AdaptiveParentFocusRequest,
): Boolean {
    val current = workspace ?: return false
    return request.target == page &&
        current.shellInstanceId == page.shellInstanceId &&
        current.acceptsDetailCallback(page.identityKey, page.generation)
}

/** What the main detail does with a parent-focus request once it stops waiting for the keyboard. */
internal enum class AdaptiveParentFocusOutcome(val logValue: String) {
    Applied("applied"),
    StaleTarget("stale_target"),
    KeyboardStillOpen("keyboard_still_open"),
}

/**
 * Re-validated after the wait: a target retired meanwhile (channel switch, fold, new selection,
 * page gone) or a keyboard still open does not focus; only [AdaptiveParentFocusOutcome.Applied]
 * dispatches the focus.
 */
internal fun SharedAdaptiveWorkspaceSession.parentFocusOutcomeAfterWait(
    page: RaftAdaptiveDetailCloseRequest?,
    request: AdaptiveParentFocusRequest,
    keyboardClosed: Boolean,
): AdaptiveParentFocusOutcome = when {
    page == null || !acceptsParentFocus(page, request) -> AdaptiveParentFocusOutcome.StaleTarget
    !keyboardClosed -> AdaptiveParentFocusOutcome.KeyboardStillOpen
    else -> AdaptiveParentFocusOutcome.Applied
}
