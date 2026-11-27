package build.raft.app

import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.key
import build.raft.app.thread.threadReactionPendingKey
import build.raft.app.thread.threadVisibleReactionsWithPending
import build.raft.app.thread.toPendingReactionVisual
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadChipLoadingTest {
    @Test
    fun lastReactionRemovalDisappearsImmediatelyWhileRequestIsPending() {
        val key = threadReactionPendingKey("message-1", "👍")
        val original = RaftThreadReactionItem(emoji = "👍", count = 1, selected = true)

        assertEquals(
            emptyList(),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = emptyList(),
                pendingVisuals = mapOf(key to listOf(original).toPendingReactionVisual("👍", selected = false))
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
    fun failedLastRemovalUsesRestoredCanonicalRowAfterPendingClears() {
        val restored = RaftThreadReactionItem(emoji = "👍", count = 1, selected = true)

        assertEquals(
            listOf(restored),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(restored),
                pendingVisuals = emptyMap()
            )
        )
    }

    @Test
    fun addAndMultiCountRemovalKeepOptimisticRowWithoutDuplicate() {
        val key = threadReactionPendingKey("message-1", "👍")
        val optimisticAdd = RaftThreadReactionItem(emoji = "👍", count = 1, selected = true)
        val originalRemove = RaftThreadReactionItem(emoji = "👍", count = 3, selected = true)
        val optimisticRemove = RaftThreadReactionItem(emoji = "👍", count = 2, selected = false)

        assertEquals(
            listOf(optimisticAdd),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(optimisticAdd),
                pendingVisuals = mapOf(key to emptyList<RaftThreadReactionItem>().toPendingReactionVisual("👍", selected = true))
            )
        )
        assertEquals(
            listOf(optimisticRemove),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(originalRemove),
                pendingVisuals =
                    mapOf(
                        key to
                            listOf(originalRemove).toPendingReactionVisual("👍", selected = false)
                    )
            )
        )
    }
}
