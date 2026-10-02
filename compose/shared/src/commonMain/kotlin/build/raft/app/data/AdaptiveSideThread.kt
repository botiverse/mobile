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

/**
 * Opens [target] as the side thread when it is a reply thread under the channel/DM shown in the
 * main detail and the column has room. [source] is the close request of the page asking — the main
 * detail or the current side thread. Returns null when the caller should fall back to the
 * existing in-pane replace ([navigateDetail]).
 */
internal fun SharedAdaptiveWorkspaceSession.openSideThread(
    source: SlockAdaptiveDetailCloseRequest,
    target: WireThreadLaunchPayload,
    serverId: String,
    columnAvailable: Boolean,
): SharedAdaptiveWorkspaceSession? {
    if (!columnAvailable) return null
    val current = workspace ?: return null
    val main = current.selection ?: return null
    if (current.shellInstanceId != source.shellInstanceId || !current.parentVisible ||
        current.pane.layout != SlockAdaptivePaneLayout.DualPane || current.serverId != serverId
    ) return null
    val fromMain = current.acceptsDetailCallback(source.identityKey, source.generation)
    val fromSide = sideThread?.let { it.identity.key == source.identityKey && it.generation == source.generation } == true
    if (!fromMain && !fromSide) return null
    if (main.identity.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) return null
    val identity = target.toSlockAdaptiveDetailIdentity(serverId) ?: return null
    if (identity.surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return null
    val mainChannelId = main.identity.channelId ?: main.identity.threadId
    if (identity.parentChannelId != mainChannelId) return null
    if (sideThread?.identity == identity) return this
    val generation = sideThreadGenerationCursor + 1
    return copy(
        sideThread = AdaptiveSideThread(main, identity, target, generation),
        sideThreadGenerationCursor = generation,
    )
}

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
