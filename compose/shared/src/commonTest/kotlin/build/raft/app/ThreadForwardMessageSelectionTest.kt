package build.raft.app

import build.raft.app.model.ForwardMessageBundle
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadForwardMessageSelectionTest {
    @Test
    fun originalMessagesAreAllForwardable() {
        val selection =
            listOf(
                message("message-1"),
                message("message-2")
            ).threadForwardMessageSelection()

        assertEquals(listOf("message-1", "message-2"), selection.forwardableMessages.map { it.id })
        assertEquals(0, selection.skippedCount)
        assertEquals(0, selection.nestedForwardCount)
    }

    @Test
    fun forwardedBundlesAreNotForwardable() {
        val selection =
            listOf(
                message("forwarded-1", forwardedBundle = true),
                message("forwarded-2", forwardedBundle = true)
            ).threadForwardMessageSelection()

        assertEquals(emptyList(), selection.forwardableMessages)
        assertEquals(2, selection.skippedCount)
        assertEquals(2, selection.nestedForwardCount)
        assertEquals(ThreadNestedForwardUnsupportedMessage, selection.threadForwardSelectionFailureMessage())
    }

    @Test
    fun taskLinkedChatMessagesAreForwardableLikeWeb() {
        // Web getForwardableMessages and the server forward route accept task-linked chat rows.
        val selection =
            listOf(
                message("task-1", taskStatus = "todo"),
                message("task-2", taskId = "task-id-2", taskNumber = 7)
            ).threadForwardMessageSelection()

        assertEquals(listOf("task-1", "task-2"), selection.forwardableMessages.map { it.id })
        assertEquals(0, selection.skippedCount)
    }

    @Test
    fun projectedActionCardMetadataIsNotForwardable() {
        val selection =
            listOf(
                message("message-1").copy(metadata = mapOf("actionCard.kind" to "action-card", "actionCard.type" to "channel:create"))
            ).threadForwardMessageSelection()

        assertEquals(emptyList(), selection.forwardableMessages)
        assertEquals(ThreadUnsupportedSourceMessage, selection.threadForwardSelectionFailureMessage())
    }

    @Test
    fun actualTaskSystemAndActionCardMessagesAreNotForwardable() {
        val selection =
            listOf(
                message("task-system-1", taskStatus = "todo", messageType = "System"),
                message("action-1", actionCard = true)
            ).threadForwardMessageSelection()

        assertEquals(emptyList(), selection.forwardableMessages)
        assertEquals(2, selection.skippedCount)
        assertEquals(0, selection.nestedForwardCount)
        assertEquals(ThreadUnsupportedSourceMessage, selection.threadForwardSelectionFailureMessage())
    }

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
    fun unsupportedServerCodesUseNestedForwardGuidance() {
        assertEquals(ThreadNestedForwardUnsupportedMessage, "unsupport".threadForwardErrorMessage())
        assertEquals(ThreadNestedForwardUnsupportedMessage, " Unsupported ".threadForwardErrorMessage())
        assertEquals(ThreadNestedForwardUnsupportedMessage, "forwarded_source_not_supported".threadForwardErrorMessage())
        assertEquals(
            ThreadUnsupportedSourceMessage,
            "unsupported_source_message".threadForwardErrorMessage()
        )
        assertEquals(
            ThreadForwardSourceTargetForbiddenMessage,
            "forward_source_target_forbidden".threadForwardErrorMessage()
        )
        assertEquals(
            ThreadForwardCrossServerUnavailableMessage,
            "forward_joint_projection_unavailable".threadForwardErrorMessage()
        )
        assertEquals(
            ThreadForwardServerUnauthorizedMessage,
            "forward_target_server_unauthorized".threadForwardErrorMessage()
        )
        assertEquals("server_unavailable", "server_unavailable".threadForwardErrorMessage())
    }

    @Test
    fun mixedSelectionWarningExplainsSkippedSources() {
        assertEquals(
            "2 selected messages were skipped because forwarded messages can't be forwarded again.",
            threadForwardSkippedWarning(skippedCount = 2, nestedForwardCount = 2)
        )
        assertEquals(
            "3 selected messages were skipped. Forwarded bundles, system messages, and action messages can't be forwarded.",
            threadForwardSkippedWarning(skippedCount = 3, nestedForwardCount = 1)
        )
        assertEquals(null, threadForwardSkippedWarning(skippedCount = 0, nestedForwardCount = 0))
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
