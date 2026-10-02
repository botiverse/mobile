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
import build.raft.app.reduceSlockAdaptiveWorkspace
import build.raft.app.toSlockAdaptiveDetailIdentity

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
    val generation = sideThreadGenerationCursor + 1
    return AdaptiveSideThreadOpen.Opened(
        copy(
            sideThread = AdaptiveSideThread(main, identity, target, generation),
            sideThreadGenerationCursor = generation,
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
