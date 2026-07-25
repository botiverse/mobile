package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadChipLoadingTest {
    private fun pending(
        reaction: KuiklyThreadReactionItem,
        originalIndex: Int = 0
    ): ThreadReactionPendingVisual =
        ThreadReactionPendingVisual(reaction = reaction, originalIndex = originalIndex)

    @Test
    fun pendingReactionKeyBindsMessageAndEmojiIdentity() {
        assertEquals(
            ThreadReactionPendingKey("message-1", "👍"),
            threadReactionPendingKey("message-1", "👍")
        )
        assertEquals(
            ThreadReactionPendingKey("message-2", "👍"),
            threadReactionPendingKey("message-2", "👍")
        )
        assertEquals(
            ThreadReactionPendingKey("message-1", "❤️"),
            threadReactionPendingKey("message-1", "❤️")
        )
    }

    @Test
    fun lastReactionRemovalKeepsExactPendingChipUntilSuccess() {
        val key = threadReactionPendingKey("message-1", "👍")
        val original = KuiklyThreadReactionItem(emoji = "👍", count = 1, selected = true)

        assertEquals(
            listOf(original),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = emptyList(),
                pendingVisuals = mapOf(key to pending(original))
            )
        )
        assertEquals(
            emptyList(),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = emptyList(),
                pendingVisuals = emptyMap()
            )
        )
    }

    @Test
    fun failedLastRemovalUsesRestoredCanonicalRowWithoutDuplicate() {
        val key = threadReactionPendingKey("message-1", "👍")
        val restored = KuiklyThreadReactionItem(emoji = "👍", count = 1, selected = true)

        assertEquals(
            listOf(restored),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(restored),
                pendingVisuals = mapOf(key to pending(restored))
            )
        )
    }

    @Test
    fun addAndMultiCountRemovalKeepOptimisticRowWithoutDuplicate() {
        val key = threadReactionPendingKey("message-1", "👍")
        val optimisticAdd = KuiklyThreadReactionItem(emoji = "👍", count = 1, selected = true)
        val optimisticRemove = KuiklyThreadReactionItem(emoji = "👍", count = 2, selected = false)

        assertEquals(
            listOf(optimisticAdd),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(optimisticAdd),
                pendingVisuals = mapOf(key to pending(optimisticAdd))
            )
        )
        assertEquals(
            listOf(optimisticRemove),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(optimisticRemove),
                pendingVisuals =
                    mapOf(
                        key to
                            pending(
                                KuiklyThreadReactionItem(emoji = "👍", count = 3, selected = true)
                            )
                    )
            )
        )
    }

    @Test
    fun pendingVisualNeverLeaksAcrossMessages() {
        val key = threadReactionPendingKey("message-1", "👍")
        assertEquals(
            emptyList(),
            threadVisibleReactionsWithPending(
                messageId = "message-2",
                reactions = emptyList(),
                pendingVisuals =
                    mapOf(
                        key to pending(KuiklyThreadReactionItem(emoji = "👍", count = 1))
                    )
            )
        )
    }

    @Test
    fun pendingLastReactionKeepsFooterMounted() {
        val message =
            KuiklyThreadMessageItem(
                id = "message-1",
                authorId = "member-1",
                authorName = "Member"
            )

        assertFalse(message.hasThreadFooterMeta())
        assertTrue(message.hasThreadFooterMeta(hasPendingReaction = true))
    }

    @Test
    fun lastRemovalPreservesOriginalNeighborOrder() {
        val thumbs = KuiklyThreadReactionItem(emoji = "👍", count = 1, selected = true)
        val heart = KuiklyThreadReactionItem(emoji = "❤️", count = 2)
        val key = threadReactionPendingKey("message-1", thumbs.emoji)

        assertEquals(
            listOf(thumbs, heart),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(heart),
                pendingVisuals = mapOf(key to pending(thumbs, originalIndex = 0))
            )
        )
    }

    @Test
    fun taskChipLoadsOnlyForTheExactPendingTask() {
        assertTrue(threadTaskChipLoading(taskId = "task-1", pendingTaskId = "task-1"))
        assertFalse(threadTaskChipLoading(taskId = "task-2", pendingTaskId = "task-1"))
        assertFalse(threadTaskChipLoading(taskId = null, pendingTaskId = null))
        assertFalse(threadTaskChipLoading(taskId = "task-1", pendingTaskId = null))
    }
}
