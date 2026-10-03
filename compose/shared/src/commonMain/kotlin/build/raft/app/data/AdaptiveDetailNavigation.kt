package build.raft.app.data

import build.raft.app.SlockAdaptiveDetailCloseRequest
import build.raft.app.SlockAdaptiveDetailIdentity
import build.raft.app.SlockAdaptiveDetailSelection
import build.raft.app.SlockAdaptivePaneLayout
import build.raft.app.SlockAdaptiveWorkspaceIntent
import build.raft.app.SlockAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.acceptsDetailCallback
import build.raft.app.reduceSlockAdaptiveWorkspace
import build.raft.app.toSlockAdaptiveDetailIdentity

internal fun shouldOpenWithinAdaptiveDetail(
    source: SlockAdaptiveDetailCloseRequest?,
    workspace: SlockAdaptiveWorkspaceState?,
    desktopModeEnabled: Boolean,
    targetServerId: String?,
): Boolean = source != null && desktopModeEnabled &&
    workspace?.pane?.layout == SlockAdaptivePaneLayout.DualPane &&
    targetServerId == workspace.serverId

/** Only route identity/intent is retained; conversation state stays in its canonical store. */
internal data class AdaptiveDetailHistory(
    val owner: SlockAdaptiveDetailSelection,
    val previous: List<WireThreadLaunchPayload>,
)

internal fun SharedAdaptiveWorkspaceSession.navigateDetail(
    source: SlockAdaptiveDetailCloseRequest,
    target: WireThreadLaunchPayload,
    serverId: String,
): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    if (current.shellInstanceId != source.shellInstanceId ||
        !current.parentVisible || current.pane.layout != SlockAdaptivePaneLayout.DualPane ||
        current.serverId != serverId ||
        !current.acceptsDetailCallback(source.identityKey, source.generation)
    ) return this
    val previousPayload = launchPayload ?: return this
    val identity = target.toSlockAdaptiveDetailIdentity(serverId) ?: return this
    if (identity == current.selection?.identity) return this
    val next = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.OpenDetail(identity))
    val selection = next.selection ?: return this
    val previous = detailHistory?.takeIf { it.owner == current.selection }?.previous.orEmpty()
    // Opening the parent from a reply header returns to its existing position in this pane.
    val parentIndex = previous.indexOfLast {
        it.toSlockAdaptiveDetailIdentity(serverId)?.copy(focusedMessageId = null) ==
            identity.copy(focusedMessageId = null)
    }
    val history = if (parentIndex >= 0) previous.take(parentIndex) else previous + previousPayload.copy(
        originFallbackPreview = null, originFallbackSenderId = null, originFallbackSenderKind = null,
        composerSeedText = "", composerFocusRequestNonce = 0,
    )
    return copy(
        workspace = next,
        launchPayload = target,
        detailHistory = AdaptiveDetailHistory(selection, history),
    )
}

internal fun SharedAdaptiveWorkspaceSession.backFromDetail(
    source: SlockAdaptiveDetailCloseRequest,
): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    if (current.shellInstanceId != source.shellInstanceId ||
        !current.acceptsDetailCallback(source.identityKey, source.generation)
    ) return this
    val previous = detailHistory?.takeIf { it.owner == current.selection }?.previous.orEmpty()
    val target = previous.lastOrNull()
    val identity = target?.toSlockAdaptiveDetailIdentity(current.serverId)
    if (identity != null) {
        // A resize may have made the retained detail compact; Back still unwinds its history.
        val next = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.ParkSelection(identity))
        return copy(
            workspace = next,
            launchPayload = target,
            detailHistory = AdaptiveDetailHistory(next.selection!!, previous.dropLast(1)),
        )
    }
    return copy(
        workspace = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.CloseDetail(source.identityKey, source.generation)),
        launchPayload = null,
        detailHistory = null,
    )
}

/**
 * Wide-pane open applied to the store's CURRENT session (inside its atomic update). Reducing from a
 * compose snapshot and writing the whole workspace back is a lost update: a visibility/window
 * change that landed in between (e.g. the DMs tab just became the visible host) would be reverted.
 * Returns this session unchanged when the pane cannot host the detail.
 */
internal fun SharedAdaptiveWorkspaceSession.withOpenedDetail(
    identity: SlockAdaptiveDetailIdentity,
    target: WireThreadLaunchPayload,
): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    // The wide pane must be able to host the detail. Checked up front: a compact workspace that
    // already retains this identity would otherwise look "opened" and skip the full-page route.
    if (current.pane.layout != SlockAdaptivePaneLayout.DualPane || current.serverId != identity.serverId) {
        return this
    }
    val next = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.OpenDetail(identity))
    if (next.selection?.identity != identity) return this
    return copy(workspace = next, launchPayload = target)
}

/**
 * task #34: close the whole detail, including any in-detail navigation history (the task overlay's
 * close button / scrim). Back keeps using [backFromDetail], which unwinds one level at a time.
 */
internal fun SharedAdaptiveWorkspaceSession.closeDetailCompletely(): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    if (current.selection == null) return copy(launchPayload = null, detailHistory = null)
    return copy(
        workspace = reduceSlockAdaptiveWorkspace(current, SlockAdaptiveWorkspaceIntent.DismissDetail),
        launchPayload = null,
        detailHistory = null,
    )
}

internal fun SharedAdaptiveWorkspaceSession.detailListSelectionId(): String? =
    detailHistory?.takeIf { it.owner == workspace?.selection }?.previous?.firstOrNull()
        ?.toSlockAdaptiveDetailIdentity(workspace?.serverId.orEmpty())?.threadId
        ?: workspace?.selection?.identity?.let { it.parentChannelId ?: it.threadId }

internal fun SharedAdaptiveWorkspaceSession.hasVisibleDetailBackHistory(): Boolean =
    workspace?.parentVisible == true && detailHistory?.let {
        it.owner == workspace.selection && it.previous.isNotEmpty()
    } == true
