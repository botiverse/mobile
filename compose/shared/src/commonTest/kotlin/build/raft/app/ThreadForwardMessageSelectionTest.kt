package build.raft.app

import build.raft.app.messageRes
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadForwardFailure
import build.raft.app.thread.ThreadForwardFailureCodePartial
import build.raft.app.thread.ThreadForwardFailureCodeSourceUnavailable
import build.raft.app.thread.ThreadForwardFailureCodeTargetUnavailable
import build.raft.app.thread.ThreadForwardFailureCodeUnavailable
import build.raft.app.thread.messageRes
import build.raft.app.thread.threadForwardFailure
import build.raft.app.thread.threadForwardMessageSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadForwardMessageSelectionTest {
    @Test
    fun mixedSelectionKeepsOnlyOrdinaryMessagesInTimelineOrder() {
        val selection =
            listOf(
                message("forwarded-1", forwardedBundle = true),
                message("message-1"),
                message("task-1", taskStatus = "in_review"),
                message("action-1", actionCard = true),
                message("message-2")
            ).threadForwardMessageSelection()

        assertEquals(listOf("message-1", "task-1", "message-2"), selection.forwardableMessages.map { it.id })
        assertEquals(2, selection.skippedCount)
        assertEquals(1, selection.nestedForwardCount)
    }

    @Test
    fun forwardFailureCodesMapToTheirLocalizedToast() {
        assertEquals(ThreadForwardFailure.NestedForward, threadForwardFailure("unsupport"))
        assertEquals(ThreadForwardFailure.NestedForward, threadForwardFailure(" Unsupported "))
        assertEquals(ThreadForwardFailure.NestedForward, threadForwardFailure("forwarded_source_not_supported"))
        assertEquals(ThreadForwardFailure.UnsupportedSource, threadForwardFailure("unsupported_source_message"))
        assertEquals(ThreadForwardFailure.UnsupportedSource, threadForwardFailure("unsupported_source"))
        // Web forwardRequestFailureMessage codes.
        assertEquals(ThreadForwardFailure.CrossSource, threadForwardFailure("cross_source_bundle"))
        assertEquals(ThreadForwardFailure.SourceNotFound, threadForwardFailure("source_not_found"))
        assertEquals(ThreadForwardFailure.CrossServerSource, threadForwardFailure("cross_server_source"))
        assertEquals(ThreadForwardFailure.SourceTargetForbidden, threadForwardFailure("forward_source_target_forbidden"))
        assertEquals(ThreadForwardFailure.CrossServerUnavailable, threadForwardFailure("forward_joint_projection_unavailable"))
        assertEquals(ThreadForwardFailure.CrossServerUnavailable, threadForwardFailure("forward_cross_server_batch_unsupported"))
        assertEquals(ThreadForwardFailure.ServerUnauthorized, threadForwardFailure("forward_target_server_unauthorized"))
        assertEquals(ThreadForwardFailure.TargetChanged, threadForwardFailure("forward_directory_refreshed"))
        assertEquals(ThreadForwardFailure.DestinationsUnavailable, threadForwardFailure(ThreadForwardFailureCodeTargetUnavailable))
        assertEquals(ThreadForwardFailure.PartialFailure, threadForwardFailure(ThreadForwardFailureCodePartial))
    }

    @Test
    fun unknownForwardFailureCodesNeverReachTheToast() {
        // An unknown code reads as the generic "Unable to forward"; the code only goes to the log.
        assertEquals(ThreadForwardFailure.Generic, threadForwardFailure("server_unavailable"))
        assertEquals(ThreadForwardFailure.Generic, threadForwardFailure(ThreadForwardFailureCodeUnavailable))
        assertEquals(ThreadForwardFailure.Generic, threadForwardFailure(ThreadForwardFailureCodeSourceUnavailable))
        assertEquals(ThreadForwardFailure.Generic, threadForwardFailure(""))
        assertEquals(ThreadForwardFailure.Generic, threadForwardFailure(null))
        assertEquals(Res.string.thread_error_forward, ThreadForwardFailure.Generic.messageRes)
        assertEquals(Res.string.thread_forward_error_nested, ThreadForwardFailure.NestedForward.messageRes)
        assertEquals(
            ThreadForwardFailure.entries.size,
            ThreadForwardFailure.entries.map { it.messageRes }.toSet().size,
            "each failure has its own toast"
        )
    }

    private fun message(
        id: String,
        forwardedBundle: Boolean = false,
        taskStatus: String? = null,
        taskId: String? = null,
        taskNumber: Int? = null,
        actionCard: Boolean = false,
        messageType: String = "Chat"
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "member-1",
            authorName = "Member",
            metadata = if (actionCard) mapOf("kind" to "action-card") else emptyMap(),
            taskStatus = taskStatus,
            taskId = taskId,
            taskNumber = taskNumber,
            messageType = messageType,
            forwardBundle = ForwardMessageBundle(id = "bundle-$id").takeIf { forwardedBundle }
        )
}
