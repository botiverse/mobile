package build.raft.app.network.sync

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun emptyWindowsAreDroppedAndTheBoundKeepsRecentParents() = runTest {
        val storage = RaftInMemoryThreadReplyWindowStorage()
        val cache = RaftThreadReplyWindowCache(storage, this, maxParentsPerPage = 2)
        cache.record(listOf(projection("p1", "t1", 1, 1), projection("p2", "t2", 1, 1)))
        cache.record(listOf(projection("p3", "t3", 1, 1)))
        cache.record(listOf(projection("p2", "t2", replies = 0, replyCount = 0)))
        advanceUntilIdle()

        val windows = RaftThreadReplyWindowCache(storage, this).load(page())
        assertEquals(setOf("p3"), windows.keys)
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

    @Test
    fun hostSeedsOnlyUnseenScopesAndAuthoritativeSummariesConfirmThem() = runTest {
        val host = ThreadRepliesSyncHost()
        val cached = mapOf("parent-1" to RaftCachedThreadReplyWindow("thread-1", model(replies = 2, replyCount = 4)))

        val seeded = host.seedCachedWindows(page(), cached)
        assertEquals(setOf("parent-1"), seeded.keys)
        assertTrue(seeded.getValue("parent-1").fromLocalCache)
        val identity = seeded.getValue("parent-1").identity

        val current = host.withCurrentAcceptedProjections(listOf(identity)) { it }
        assertEquals(4, current.getValue("parent-1").model.replyCount)
        assertTrue(current.getValue("parent-1").fromLocalCache)
        assertTrue(host.seedCachedWindows(page(), cached).isEmpty(), "a known scope is never re-seeded")

        assertTrue(host.beginAuthoritativeRebaseline(page(), generation = 1L))
        val authoritative =
            host.ingestAuthoritativeSnapshots(
                page(),
                generation = 1L,
                snapshots = listOf(ThreadRepliesAuthoritativeSnapshot(identity, model(3, 6).replies, replyCount = 6)),
            )
        assertFalse(authoritative!!.getValue("parent-1").fromLocalCache)
        val confirmed = host.withCurrentAcceptedProjections(listOf(identity)) { it }
        assertEquals(6, confirmed.getValue("parent-1").model.replyCount)
        assertFalse(confirmed.getValue("parent-1").fromLocalCache)
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

