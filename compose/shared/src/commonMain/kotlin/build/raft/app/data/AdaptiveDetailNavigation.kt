package build.raft.app.data

import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.RaftAdaptiveDetailIdentity
import build.raft.app.RaftAdaptiveDetailSelection
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptiveWorkspaceIntent
import build.raft.app.RaftAdaptiveWorkspacePresentation
import build.raft.app.RaftAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.acceptsDetailCallback
import build.raft.app.reduceRaftAdaptiveWorkspace
import build.raft.app.toRaftAdaptiveDetailIdentity

internal fun shouldOpenWithinAdaptiveDetail(
    source: RaftAdaptiveDetailCloseRequest?,
    workspace: RaftAdaptiveWorkspaceState?,
    desktopModeEnabled: Boolean,
    targetServerId: String?,
): Boolean = source != null && desktopModeEnabled &&
    workspace?.pane?.layout == RaftAdaptivePaneLayout.DualPane &&
    targetServerId == workspace.serverId

/** Only route identity/intent is retained; conversation state stays in its canonical store. */
internal data class AdaptiveDetailHistory(
    val owner: RaftAdaptiveDetailSelection,
    val previous: List<WireThreadLaunchPayload>,
)

internal fun SharedAdaptiveWorkspaceSession.navigateDetail(
    source: RaftAdaptiveDetailCloseRequest,
    target: WireThreadLaunchPayload,
    serverId: String,
): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    if (current.shellInstanceId != source.shellInstanceId ||
        !current.parentVisible || current.pane.layout != RaftAdaptivePaneLayout.DualPane ||
        current.serverId != serverId ||
        !current.acceptsDetailCallback(source.identityKey, source.generation)
    ) return this
    val previousPayload = launchPayload ?: return this
    val identity = target.toRaftAdaptiveDetailIdentity(serverId) ?: return this
    if (identity == current.selection?.identity) return this
    val next = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.OpenDetail(identity))
    val selection = next.selection ?: return this
    val previous = detailHistory?.takeIf { it.owner == current.selection }?.previous.orEmpty()
    // Opening the parent from a reply header returns to its existing position in this pane.
    val parentIndex = previous.indexOfLast {
        it.toRaftAdaptiveDetailIdentity(serverId)?.copy(focusedMessageId = null) ==
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
    source: RaftAdaptiveDetailCloseRequest,
): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    if (current.shellInstanceId != source.shellInstanceId ||
        !current.acceptsDetailCallback(source.identityKey, source.generation)
    ) return this
    val previous = detailHistory?.takeIf { it.owner == current.selection }?.previous.orEmpty()
    val target = previous.lastOrNull()
    val identity = target?.toRaftAdaptiveDetailIdentity(current.serverId)
    if (identity != null) {
        // A resize may have made the retained detail compact; Back still unwinds its history.
        val next = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.ParkSelection(identity))
        return copy(
            workspace = next,
            launchPayload = target,
            detailHistory = AdaptiveDetailHistory(next.selection!!, previous.dropLast(1)),
        )
    }
    return copy(
        workspace = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.CloseDetail(source.identityKey, source.generation)),
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
    identity: RaftAdaptiveDetailIdentity,
    target: WireThreadLaunchPayload,
): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    // The wide pane must be able to host the detail. Checked up front: a compact workspace that
    // already retains this identity would otherwise look "opened" and skip the full-page route.
    if (current.pane.layout != RaftAdaptivePaneLayout.DualPane || current.serverId != identity.serverId) {
        return this
    }
    val next = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.OpenDetail(identity))
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
        workspace = reduceRaftAdaptiveWorkspace(current, RaftAdaptiveWorkspaceIntent.DismissDetail),
        launchPayload = null,
        detailHistory = null,
    )
}

internal fun SharedAdaptiveWorkspaceSession.detailListSelectionId(): String? =
    detailHistory?.takeIf { it.owner == workspace?.selection }?.previous?.firstOrNull()
        ?.toRaftAdaptiveDetailIdentity(workspace?.serverId.orEmpty())?.threadId
        ?: workspace?.selection?.identity?.let { it.parentChannelId ?: it.threadId }

internal fun SharedAdaptiveWorkspaceSession.hasVisibleDetailBackHistory(): Boolean =
    workspace?.parentVisible == true && detailHistory?.let {
        it.owner == workspace.selection && it.previous.isNotEmpty()
    } == true

/**
 * The conversation Home should treat as "being viewed" once the app shell page is visible again.
 *
 * Home suppresses realtime unread increments for its selected conversation. On a phone (and with desktop
 * mode off) an opened conversation is a pushed page; when the shell is visible again that page is gone, so
 * nothing is being viewed and the selection must clear, or the last opened channel never gains unread until
 * the next server summary. A detail still showing inside the shell (desktop mode, split or detail-only)
 * keeps its list selection.
 */
internal fun SharedAdaptiveWorkspaceSession.selectedConversationWhenShellReturns(
    desktopModeOnLargeScreen: Boolean,
): String? {
    val current = workspace ?: return null
    if (!desktopModeOnLargeScreen || current.selection == null) return null
    return when (current.presentation) {
        RaftAdaptiveWorkspacePresentation.Split,
        RaftAdaptiveWorkspacePresentation.DetailOnly -> detailListSelectionId()
        RaftAdaptiveWorkspacePresentation.ListOnly,
        RaftAdaptiveWorkspacePresentation.Inactive -> null
    }
}
