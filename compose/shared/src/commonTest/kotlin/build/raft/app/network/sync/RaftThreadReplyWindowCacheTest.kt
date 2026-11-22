package build.raft.app.network.sync

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hands dd79da45: reply-preview windows accepted in one process must be readable by the next
 * (cold-started) process, scoped to the replies page (server + principal + parent scope).
 */
class RaftThreadReplyWindowCacheTest {
    @Test
    fun recordedWindowsSurviveAColdStart() = runTest {
        val storage = RaftInMemoryThreadReplyWindowStorage()
        val firstProcess = RaftThreadReplyWindowCache(storage, this)
        firstProcess.record(listOf(projection("parent-1", "thread-1", replies = 2, replyCount = 5)))
        advanceUntilIdle()

        val coldProcess = RaftThreadReplyWindowCache(storage, this)
        val windows = coldProcess.load(page())

        assertEquals(setOf("parent-1"), windows.keys)
        assertEquals("thread-1", windows.getValue("parent-1").threadChannelId)
        assertEquals(5, windows.getValue("parent-1").model.replyCount)
        assertEquals(listOf("reply-1", "reply-2"), windows.getValue("parent-1").model.replies.map { it.messageId })
        assertTrue(coldProcess.load(page(principalId = "someone-else")).isEmpty(), "pages are principal scoped")
        assertTrue(coldProcess.load(page(parentScopeId = "channel-2")).isEmpty())
    }

    @Test
    fun aPartialInMemoryPageNeverClobbersOlderDiskEntries() = runTest {
        val storage = RaftInMemoryThreadReplyWindowStorage()
        RaftThreadReplyWindowCache(storage, this).apply {
            record(listOf(projection("parent-1", "thread-1", replies = 1, replyCount = 1)))
        }
        advanceUntilIdle()

        // A new process records another parent before ever loading the page.
        val secondProcess = RaftThreadReplyWindowCache(storage, this)
        secondProcess.record(listOf(projection("parent-2", "thread-2", replies = 1, replyCount = 1)))
        advanceUntilIdle()

        val windows = RaftThreadReplyWindowCache(storage, this).load(page())
        assertEquals(setOf("parent-1", "parent-2"), windows.keys)
    }

    @Test
    fun clearAllWipesMemoryAndDisk() = runTest {
        val storage = RaftInMemoryThreadReplyWindowStorage()
        val cache = RaftThreadReplyWindowCache(storage, this)
        cache.record(listOf(projection("parent-1", "thread-1", 1, 1)))
        advanceUntilIdle()

        cache.clearAll()
        advanceUntilIdle()

        assertTrue(cache.load(page()).isEmpty())
        assertTrue(RaftThreadReplyWindowCache(storage, this).load(page()).isEmpty())
        assertTrue(storage.values.isEmpty(), storage.values.toString())
    }

    private fun page(
        principalId: String = "u-a",
        parentScopeId: String = "channel-1",
    ): ThreadRepliesPageIdentity =
        ThreadRepliesPageIdentity(
            serverId = "server-1",
            principalId = principalId,
            parentScopeKind = "channel",
            parentScopeId = parentScopeId,
        )

    private fun projection(
        parentMessageId: String,
        threadChannelId: String,
        replies: Int,
        replyCount: Int,
    ): ThreadRepliesAcceptedProjection =
        ThreadRepliesAcceptedProjection(
            identity = ThreadRepliesScopeIdentity(page(), parentMessageId, threadChannelId),
            model = model(replies, replyCount),
            revision = 1L,
        )

    private fun model(replies: Int, replyCount: Int): ThreadRepliesReadModel =
        initialThreadRepliesReadModel(
            replies =
                (1..replies).map { index ->
                    ReplyPreview(
                        messageId = "reply-$index",
                        seq = index.toLong(),
                        content = "reply $index",
                        senderId = "u-$index",
                        senderType = "user",
                        senderName = "User $index",
                        createdAt = "2026-10-20T08:0$index:00Z",
                    )
                },
            replyCount = replyCount,
            limit = InlineThreadReplyPreviewLimit,
        )
}

internal class RaftInMemoryThreadReplyWindowStorage : RaftThreadReplyWindowStorage {
    val values = mutableMapOf<String, String>()

    override suspend fun read(pageKey: String): String? = values[pageKey]

    override suspend fun write(pageKey: String, value: String) {
        values[pageKey] = value
    }

    override suspend fun clearAll() {
        values.clear()
    }

    override suspend fun pageKeys(): List<String> = values.keys.toList()

    override suspend fun delete(pageKeys: List<String>) {
        pageKeys.forEach { values.remove(it) }
    }
}

