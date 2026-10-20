package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadChipLoadingTest {
    private fun pending(
        reaction: RaftThreadReactionItem,
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
                        key to pending(RaftThreadReactionItem(emoji = "👍", count = 1))
                    )
            )
        )
    }

    @Test
    fun footerMountFollowsOptimisticPendingReactionCount() {
        val message =
            RaftThreadMessageItem(
                id = "message-1",
                authorId = "member-1",
                authorName = "Member"
            )

        assertFalse(message.hasThreadFooterMeta())
        assertTrue(message.hasThreadFooterMeta(hasPendingReaction = true))
        assertFalse(message.hasThreadFooterMeta(hasPendingReaction = false))
    }

    @Test
    fun pendingRemovalUpdatesStaleSnapshotAndPreservesNeighborOrder() {
        val thumbs = RaftThreadReactionItem(emoji = "👍", count = 1, selected = true)
        val heart = RaftThreadReactionItem(emoji = "❤️", count = 2)
        val key = threadReactionPendingKey("message-1", thumbs.emoji)

        assertEquals(
            listOf(heart),
            threadVisibleReactionsWithPending(
                messageId = "message-1",
                reactions = listOf(thumbs, heart),
                pendingVisuals = mapOf(key to listOf(thumbs, heart).toPendingReactionVisual(thumbs.emoji, selected = false))
            )
        )
    }
}
