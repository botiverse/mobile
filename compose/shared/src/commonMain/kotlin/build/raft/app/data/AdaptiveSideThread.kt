package build.raft.app.data

import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.SlockAdaptiveDetailCloseRequest
import build.raft.app.SlockAdaptiveDetailIdentity
import build.raft.app.SlockAdaptiveDetailSelection
import build.raft.app.SlockAdaptivePaneLayout
import build.raft.app.SlockAdaptivePaneState
import build.raft.app.SlockAdaptiveWorkspaceIntent
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.acceptsDetailCallback
import build.raft.app.nextAdaptiveGeneration
import build.raft.app.reduceSlockAdaptiveWorkspace
import build.raft.app.toSlockAdaptiveDetailCloseRequestOrNull
import build.raft.app.toSlockAdaptiveDetailIdentity
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * task #36: below this window width the reply thread keeps replacing the channel in the detail
 * pane (Back returns). It is only the first gate — the actual detail width is checked too.
 */
internal const val SLOCK_SIDE_THREAD_MIN_WINDOW_DP = 1200f

/** Fixed width of the reply-thread column beside the channel. */
internal const val SLOCK_SIDE_THREAD_COLUMN_DP = 400f

/** Narrowest the channel body may get once the thread column takes its share. */
internal const val SLOCK_SIDE_THREAD_MAIN_MIN_DP = 440f

/** Divider between the channel body and the thread column. */
internal const val SLOCK_SIDE_THREAD_DIVIDER_DP = 1f

/**
 * A reply thread shown in its own column beside the selected channel/DM ([owner]). It has its own
 * identity and generation, so its close request only ever closes the column.
 */
internal data class AdaptiveSideThread(
    val owner: SlockAdaptiveDetailSelection,
    val identity: SlockAdaptiveDetailIdentity,
    val payload: WireThreadLaunchPayload,
    val generation: Long,
) {
    fun closeRequest(shellInstanceId: String): SlockAdaptiveDetailCloseRequest =
        SlockAdaptiveDetailCloseRequest(shellInstanceId, identity.key, generation)
}

/**
 * Whether the side column fits. [detailPaneWidthDp] is the measured width of the detail pane
 * itself — after the rail, the list, dividers and safe-area insets — so a window that only just
 * reaches the breakpoint cannot squeeze the channel body below its minimum. Unknown width (null or
 * not yet measured) means no column.
 */
internal fun slockSideThreadColumnAvailable(
    desktopModeOnLargeScreen: Boolean,
    pane: SlockAdaptivePaneState,
    detailPaneWidthDp: Float?,
): Boolean =
    desktopModeOnLargeScreen &&
        pane.layout == SlockAdaptivePaneLayout.DualPane &&
        pane.density > 0f &&
        pane.widthPx / pane.density >= SLOCK_SIDE_THREAD_MIN_WINDOW_DP &&
        detailPaneWidthDp != null &&
        detailPaneWidthDp >=
        SLOCK_SIDE_THREAD_MAIN_MIN_DP + SLOCK_SIDE_THREAD_DIVIDER_DP + SLOCK_SIDE_THREAD_COLUMN_DP

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
    source: SlockAdaptiveDetailCloseRequest,
    target: WireThreadLaunchPayload,
    serverId: String,
    columnAvailable: Boolean,
): AdaptiveSideThreadOpen {
    val current = workspace ?: return AdaptiveSideThreadOpen.Ignored
    val main = current.selection ?: return AdaptiveSideThreadOpen.Ignored
    if (current.shellInstanceId != source.shellInstanceId || !current.parentVisible ||
        current.pane.layout != SlockAdaptivePaneLayout.DualPane || current.serverId != serverId
    ) return AdaptiveSideThreadOpen.Ignored
    val fromMain = current.acceptsDetailCallback(source.identityKey, source.generation)
    val side = sideThread
    val fromSide = side != null && side.owner == main &&
        side.identity.key == source.identityKey && side.generation == source.generation
    if (!fromMain && !fromSide) return AdaptiveSideThreadOpen.Ignored
    if (!columnAvailable || main.identity.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        return AdaptiveSideThreadOpen.FallbackToReplace
    }
    val identity = target.toSlockAdaptiveDetailIdentity(serverId) ?: return AdaptiveSideThreadOpen.FallbackToReplace
    val mainChannelId = main.identity.channelId ?: main.identity.threadId
    if (identity.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread || identity.parentChannelId != mainChannelId) {
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
    return slockSideThreadColumnAvailable(desktopModeOnLargeScreen, pane, detailPaneWidthDp)
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
    source: SlockAdaptiveDetailCloseRequest,
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
                    SlockAdaptiveDetailCloseRequest(source.shellInstanceId, main.identity.key, main.generation)
                } else source
            navigateDetail(replaceSource, target, serverId)
        }
    }

/**
 * Whether [source] is a live adaptive detail page of this session: the current main detail or the
 * current side thread of that detail. Retired pages (an older main generation, a closed or folded
 * side thread) are not.
 */
internal fun SharedAdaptiveWorkspaceSession.acceptsAdaptiveSource(source: SlockAdaptiveDetailCloseRequest): Boolean {
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
    source: SlockAdaptiveDetailCloseRequest?,
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
    request: SlockAdaptiveDetailCloseRequest,
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
    request: SlockAdaptiveDetailCloseRequest,
): SharedAdaptiveWorkspaceSession {
    val side = sideThread ?: return this
    if (workspace?.shellInstanceId != request.shellInstanceId ||
        side.identity.key != request.identityKey || side.generation != request.generation
    ) return this
    return copy(sideThread = null)
}

/**
 * When the window can no longer hold the column, the side thread becomes the main detail with the
 * channel in its Back history — the same shape the in-pane replace produces — so nothing is lost
 * and Back still returns to the channel.
 */
internal fun SharedAdaptiveWorkspaceSession.foldSideThreadIntoDetail(): SharedAdaptiveWorkspaceSession {
    val side = sideThread ?: return this
    val current = workspace ?: return copy(sideThread = null)
    val mainPayload = launchPayload ?: return copy(sideThread = null)
    if (current.selection != side.owner) return copy(sideThread = null)
    val next = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.ParkSelection(side.identity))
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
internal const val SLOCK_ADAPTIVE_DETAIL_FOCUS_MESSAGE_EVENT = "slockAdaptiveDetailFocusMessage"
internal const val SLOCK_ADAPTIVE_DETAIL_FOCUS_MESSAGE_ID = "slockAdaptiveDetailFocusMessageId"

/** Focus [messageId] in the detail page identified by [target] (its own key/generation). */
internal data class AdaptiveParentFocusRequest(
    val target: SlockAdaptiveDetailCloseRequest,
    val messageId: String,
) {
    fun toJson(): JSONObject = target.toJson().put(SLOCK_ADAPTIVE_DETAIL_FOCUS_MESSAGE_ID, messageId)
}

internal fun JSONObject.toAdaptiveParentFocusRequestOrNull(): AdaptiveParentFocusRequest? {
    val target = toSlockAdaptiveDetailCloseRequestOrNull() ?: return null
    val messageId = optString(SLOCK_ADAPTIVE_DETAIL_FOCUS_MESSAGE_ID).trim().takeIf(String::isNotBlank) ?: return null
    return AdaptiveParentFocusRequest(target, messageId)
}

/**
 * The side column's parent action: when [source] is the live side thread, the request that makes
 * the main detail (its parent channel) focus the parent message — the column stays open. Null for
 * any other page, so the existing parent navigation applies.
 */
internal fun SharedAdaptiveWorkspaceSession.sideThreadParentFocus(
    source: SlockAdaptiveDetailCloseRequest,
): AdaptiveParentFocusRequest? {
    val current = workspace ?: return null
    val main = current.selection ?: return null
    val side = visibleSideThread() ?: return null
    if (current.shellInstanceId != source.shellInstanceId ||
        side.identity.key != source.identityKey || side.generation != source.generation
    ) return null
    val messageId = side.payload.parentMessageId?.trim()?.takeIf(String::isNotBlank) ?: return null
    return AdaptiveParentFocusRequest(
        SlockAdaptiveDetailCloseRequest(current.shellInstanceId, main.identity.key, main.generation),
        messageId,
    )
}

/** Whether the detail page [page] applies [request]: addressed to it and it is still the main detail. */
internal fun SharedAdaptiveWorkspaceSession.acceptsParentFocus(
    page: SlockAdaptiveDetailCloseRequest,
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
    page: SlockAdaptiveDetailCloseRequest?,
    request: AdaptiveParentFocusRequest,
    keyboardClosed: Boolean,
): AdaptiveParentFocusOutcome = when {
    page == null || !acceptsParentFocus(page, request) -> AdaptiveParentFocusOutcome.StaleTarget
    !keyboardClosed -> AdaptiveParentFocusOutcome.KeyboardStillOpen
    else -> AdaptiveParentFocusOutcome.Applied
}

/**
 * task #180 (web parity #9172/#9335): can the side thread be expanded into Focus mode right now?
 *
 * Focus mode only relaxes the LAYOUT — it does not create a side column where the window cannot
 * hold one. So it is offered exactly when the column is already available; a window that cannot
 * show the two columns keeps today's fold-into-detail behaviour instead of a half-broken layout.
 */
internal fun SharedAdaptiveWorkspaceSession.sideThreadFocusAvailable(
    desktopModeOnLargeScreen: Boolean,
): Boolean = visibleSideThread() != null && sideThreadColumnAvailable(desktopModeOnLargeScreen)

/** Whether the thread is currently taking the whole row. False whenever there is no side thread. */
internal fun SharedAdaptiveWorkspaceSession.sideThreadFocusActive(): Boolean =
    sideThread != null && sideThreadFocused

/**
 * Toggle Focus mode. Ignored when no thread is in the column or the window cannot host one, so a
 * stale menu item can never leave the workspace in a layout it cannot draw.
 */
internal fun SharedAdaptiveWorkspaceSession.toggleSideThreadFocus(
    desktopModeOnLargeScreen: Boolean,
): SharedAdaptiveWorkspaceSession =
    if (!sideThreadFocusAvailable(desktopModeOnLargeScreen)) this
    else copy(sideThreadFocused = !sideThreadFocused)

/**
 * Leaving Focus mode when the thread goes away or the window stops fitting. Keeping the flag set on
 * a session that has no side thread would make the next thread open already-maximised.
 */
internal fun SharedAdaptiveWorkspaceSession.normalizeSideThreadFocus(
    desktopModeOnLargeScreen: Boolean,
): SharedAdaptiveWorkspaceSession =
    if (sideThreadFocused && !sideThreadFocusAvailable(desktopModeOnLargeScreen)) {
        copy(sideThreadFocused = false)
    } else {
        this
    }

/**
 * task #180: whether the thread panel should render the Focus-mode menu entry.
 *
 * The side page decides this for itself because it never receives the shell's
 * desktopModeOnLargeScreen flag. Being the column's owner ([isSideColumnOwner], i.e. the page was
 * adopted by the column) AND having a visible side thread in the session is the equivalent fact:
 * a column can only exist if it already passed the width/posture gates.
 */
internal fun SharedAdaptiveWorkspaceSession.showsSideThreadFocusEntry(
    isSideColumnOwner: Boolean,
): Boolean = isSideColumnOwner && visibleSideThread() != null

/** Toggle Focus, but only from a page that actually owns the column. */
internal fun SharedAdaptiveWorkspaceSession.toggleSideThreadFocusFromPanel(
    isSideColumnOwner: Boolean,
): SharedAdaptiveWorkspaceSession =
    if (!showsSideThreadFocusEntry(isSideColumnOwner)) this
    else if (sideThreadFocusActive()) copy(sideThreadFocused = false) else copy(sideThreadFocused = true)
