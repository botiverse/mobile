package build.raft.app.thread

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.RaftThreadApi
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentCommentAnchor
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.traceId
import kotlinx.coroutines.CancellationException

/**
 * The single-item actions of [SharedThreadDataSource] (save / unsave a message, set a reaction,
 * load / send attachment comments, follow / unfollow a thread or task conversation, read a task
 * conversation's title, mark a reply thread unread), moved out of it unchanged; the data source
 * forwards to them. Holds only the immutable collaborators it was constructed with and reads the
 * session through [readSession].
 */
internal class ThreadItemActions(
    private val api: RaftThreadApi,
    private val conversationRepository: ConversationRepository?,
    private val selectedServerStore: SelectedServerStore?,
    private val readReceiptCoordinator: ReadReceiptCoordinator?,
    private val readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)?,
    private val readSession: suspend () -> StoredAuthSession?,
) {

    suspend fun saveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false, traceId = null))
        }
        val response =
            try {
                api.saveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_save_network", retryable = true, traceId = null))
        }
        return response.toUnitDataResult("thread_save_network")
    }

    suspend fun unsaveMessage(messageId: String): DataResult<Unit> {
        if (messageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false, traceId = null))
        }
        val response =
            try {
                api.unsaveMessage(messageId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_unsave_network", retryable = true, traceId = null))
            }
        return response.toUnitDataResult("thread_unsave_network")
    }

    suspend fun setMessageReaction(messageId: String, emoji: String, selected: Boolean): DataResult<MessageMutationSummary> {
        val cleanMessageId = messageId.trim()
        val cleanEmoji = emoji.trim()
        if (cleanMessageId.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_id_missing", retryable = false, traceId = null))
        }
        if (cleanEmoji.isBlank()) {
            return DataResult.Error(ScreenError(code = "message_reaction_missing", retryable = false, traceId = null))
        }
        val response =
            try {
                if (selected) {
                    api.addReaction(cleanMessageId, cleanEmoji)
                } else {
                    api.removeReaction(cleanMessageId, cleanEmoji)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_reaction_network", retryable = true, traceId = null))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_reaction_network")
        }
    }

    suspend fun loadAttachmentComments(attachmentId: String): DataResult<ThreadAttachmentCommentsPage> {
        val cleanAttachmentId = attachmentId.trim()
        if (cleanAttachmentId.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_id_missing", retryable = false, traceId = null))
        }
        val response =
            try {
                api.attachmentComments(cleanAttachmentId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comments_network", retryable = true, traceId = null))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(response.data)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comments_network")
        }
    }

    suspend fun sendAttachmentComment(
        attachmentId: String,
        body: String,
        anchor: ThreadAttachmentCommentAnchor? = null
    ): DataResult<Unit> {
        val cleanAttachmentId = attachmentId.trim()
        val cleanBody = body.trim()
        if (cleanAttachmentId.isBlank() || cleanBody.isBlank()) {
            return DataResult.Error(ScreenError(code = "attachment_comment_missing", retryable = false, traceId = null))
        }
        val response =
            try {
                api.sendAttachmentComment(cleanAttachmentId, cleanBody, anchor)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_attachment_comment_send_network", retryable = true, traceId = null))
            }
        return when (response) {
            is ThreadApiResult.Success -> DataResult.Content(Unit)
            is ThreadApiResult.Failure -> response.toThreadDataError("thread_attachment_comment_send_network")
        }
    }

    suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_follow_network", retryable = true, traceId = null))
        return try {
            repository.followThread(parentMessageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "thread_follow_network", retryable = true, traceId = null))
        }
    }

    suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_unfollow_network", retryable = true, traceId = null))
        return try {
            repository.unfollowThread(threadChannelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "thread_unfollow_network", retryable = true, traceId = null))
        }
    }

    /** Task v2: follow a task conversation, named by its own (local) channel id. */
    suspend fun followTaskConversation(conversationChannelId: ChannelId): DataResult<ThreadActionResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "task_conversation_follow_network", retryable = true, traceId = null))
        return try {
            repository.followTaskConversation(conversationChannelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "task_conversation_follow_network", retryable = true, traceId = null))
        }
    }

    /** Task v2: a task conversation's header title; `null` when it cannot be read. */
    suspend fun loadTaskConversationTitle(conversationChannelId: ChannelId): String? {
        val repository = conversationRepository ?: return null
        return try {
            repository.loadTaskConversationTitle(conversationChannelId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * botiverse/slock #11687: Mark as Unread on an open reply thread (its header ⋯ and its messages'
     * menus): `POST /channels/:threadChannelId/unread`, the same write as the thread's Activity row,
     * through the process read-receipt coordinator so it goes out after this channel's reads.
     */
    suspend fun markThreadChannelUnread(threadChannelId: String): DataResult<ReadReceiptResult> {
        if (threadChannelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
        }
        val coordinator = readReceiptCoordinator
        val ownerFactory = readReceiptOwnerFactory
        if (coordinator == null || ownerFactory == null) {
            return DataResult.Error(ScreenError(code = "thread_mark_unread_unavailable", retryable = false, traceId = null))
        }
        val session = readSession() ?: return authSessionRequiredResult()
        val serverId =
            selectedServerStore?.selectedServerId?.value
                ?: session.currentServerId
                ?: return DataResult.Error(ScreenError(code = "server_missing", retryable = false, traceId = null))
        return coordinator.markUnread(ownerFactory(serverId, session.user.id), ChannelId(threadChannelId))
    }
}
