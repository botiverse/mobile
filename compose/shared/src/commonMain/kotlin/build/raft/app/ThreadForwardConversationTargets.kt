package build.raft.app

import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId

/**
 * The message Forward picker's "Threads & tasks" group (slock #12621 `useForwardConversationTargets`
 * @ b088f8467e): fetched once as the picker opens. One [load] per picker session reaches the
 * repository; the same session asking again (a recomposition, a keystroke, a retry, the note step
 * and back) gets the answer it already has. A new open is a new session and asks once more.
 * External share never builds one (web's Artifact host offers no conversations either).
 */
internal class ThreadForwardConversationTargetsLoader(
    private val repository: ForwardMessageTargetRepository
) {
    private var loadedSession: RaftForwardPickerSessionKey? = null
    private var loadedTargets: List<ForwardMessageTarget> = emptyList()

    suspend fun load(
        sessionKey: RaftForwardPickerSessionKey,
        serverId: ServerId,
        currentChannelId: ChannelId?
    ): List<ForwardMessageTarget> {
        if (loadedSession == sessionKey) return loadedTargets
        val targets = repository.conversationTargets(serverId, currentChannelId)
        loadedSession = sessionKey
        loadedTargets = targets
        return targets
    }
}

/**
 * web `ForwardComposerDialog`: `currentChannelId` is sent when the source is a thread-typed channel,
 * which in the web store is a reply thread or a task discussion. Here: the reply thread's or the
 * task conversation's own channel id (never its parent); null from a channel or a DM.
 */
internal fun RaftThreadScreenPayload.threadForwardConversationCurrentChannelId(): ChannelId? {
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread && !isTaskConversationSurface()) return null
    return (sourceThreadId?.trim()?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.trim()?.takeIf { it.isNotBlank() })
        ?.let(::ChannelId)
}

/**
 * The group rows the picker shows: the server's `current` row (first) may be the source conversation
 * itself; any other row that is the source is left out, like Recent and search rows are.
 */
internal fun List<ForwardMessageTarget>.threadForwardConversationPickerTargets(
    sourceServerId: ServerId?,
    sourceChannelId: ChannelId?
): List<ForwardMessageTarget> =
    filterIndexed { index, target ->
        (index == 0 && target.current) ||
            sourceServerId == null ||
            sourceChannelId == null ||
            target.serverId != sourceServerId ||
            target.channelId != sourceChannelId
    }.mapIndexed { index, target -> if (index > 0 && target.current) target.copy(current = false) else target }
