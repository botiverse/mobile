package build.raft.app

import build.raft.app.data.ChannelJoinResult
import build.raft.app.data.DataResult
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.model.ChannelId

/**
 * What the composer's Join on a conversation page joins (web ThreadPanel `handleJoinParentChannel`).
 * [joinedChannelId] is the channel `POST /channels/:id/join` joined: the page's own channel, a
 * reply's parent, or a task conversation's task channel. [pageChannelId] is the id this page knows
 * itself by ([joinChannelTargetId]); [withChannelJoined] matches it to show the composer.
 */
internal data class ThreadJoinedChannel(
    val joinedChannelId: ChannelId,
    val pageChannelId: ChannelId,
)

internal sealed interface ThreadJoinChannelOutcome {
    data class Joined(val channel: ThreadJoinedChannel) : ThreadJoinChannelOutcome

    /** No channel to join is known on this page (no request was sent). */
    data object Unresolved : ThreadJoinChannelOutcome

    /** A refused or failed request: the task conversation's ref or the join itself. */
    data class Failed(val result: DataResult<*>) : ThreadJoinChannelOutcome
}

/**
 * Joins the channel this page's Join stands for. A task v2 conversation is a sub-conversation
 * (type `task`) with no members of its own: the server refuses a join of it (400
 * `thread_membership`), and its write authority is its task channel's
 * ([build.raft.app.data.withTaskConversationContext]), so Join joins that channel, read from the
 * conversation's ref.
 */
internal suspend fun RaftThreadScreenPayload.joinThreadChannel(
    loadTaskConversationRef: suspend (ChannelId) -> DataResult<TaskConversationChannelRef>,
    joinChannel: suspend (ChannelId) -> DataResult<ChannelJoinResult>,
): ThreadJoinChannelOutcome {
    val pageChannelId = joinChannelTargetId() ?: return ThreadJoinChannelOutcome.Unresolved
    val joinChannelId =
        if (isTaskConversationSurface()) {
            when (val ref = loadTaskConversationRef(pageChannelId)) {
                is DataResult.Content ->
                    ref.data.parentChannelId.takeIf { ref.data.conversationChannelId == pageChannelId && it.value.isNotBlank() }
                        ?: return ThreadJoinChannelOutcome.Unresolved
                else -> return ThreadJoinChannelOutcome.Failed(ref)
            }
        } else {
            pageChannelId
        }
    return when (val result = joinChannel(joinChannelId)) {
        is DataResult.Content -> ThreadJoinChannelOutcome.Joined(ThreadJoinedChannel(result.data.channelId, pageChannelId))
        else -> ThreadJoinChannelOutcome.Failed(result)
    }
}

/** [channelId]'s page now offers its composer in place of Join (the opposite of [withChannelLeft]). */
internal fun RaftThreadScreenPayload.withChannelJoined(channelId: String): RaftThreadScreenPayload =
    if (joinChannelTargetId()?.value != channelId) {
        this
    } else {
        copy(
            joined = true,
            canSend = true,
            composer = composer.copy(enabled = true)
        )
    }
