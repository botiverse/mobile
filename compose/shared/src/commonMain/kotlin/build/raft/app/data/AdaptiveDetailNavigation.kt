package build.raft.app.data

import build.raft.app.*

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
    val parentIndex = previous.indexOfLast { it.toSlockAdaptiveDetailIdentity(serverId) == identity }
    val history = if (parentIndex >= 0) previous.take(parentIndex) else previous + previousPayload
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

internal fun SharedAdaptiveWorkspaceSession.detailListSelectionId(): String? =
    detailHistory?.takeIf { it.owner == workspace?.selection }?.previous?.firstOrNull()
        ?.toSlockAdaptiveDetailIdentity(workspace?.serverId.orEmpty())?.threadId
        ?: workspace?.selection?.identity?.let { it.parentChannelId ?: it.threadId }
