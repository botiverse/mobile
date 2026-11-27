package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.replyThreadConversation
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadOriginResolution
import build.raft.app.thread.threadHeaderReplyCount
import build.raft.app.thread.toThreadReplyMetadataMap
import build.raft.app.thread.toThreadTimelineSlice
import build.raft.app.thread.withReplyThreadReplyCountOnParent
import build.raft.app.thread.withoutThreadReplyMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/**
 * The reply count at the top of a reply thread is the server's total, not the number of replies
 * the page has loaded. A long thread opened from its row in the channel said "30 replies" (one
 * page): the row handed over as the thread's parent carries no reply facts, so the remembered
 * parent said 0 and the header fell back to the loaded replies.
 */
class SharedThreadReplyThreadReplyTotalTest {
    @Test
    fun aThreadOpenedFromItsRowCountsWhatTheRowKnew() = runTest {
        // rowReplyCount to the header's count, with two replies loaded.
        listOf(412 to 412, 0 to 2).forEach { (rowReplyCount, expected) ->
            val repository = FakeConversationRepository(threadResultsById = mapOf(ThreadId("thread-m1") to twoReplies()))
            val dataSource = dataSource(backgroundScope, repository)
            // A timeline row: no reply facts on the message itself (withoutThreadReplyMetadata).
            val row = contentPayload("parent body").messages.single().withoutThreadReplyMetadata()
            assertEquals(
                true,
                dataSource.rememberReplyParentCandidate(
                    provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance,
                    serverId = "server-1",
                    parentChannelId = "channel-1",
                    parentMessage = row,
                    threadChannelId = "thread-m1",
                    replyCount = rowReplyCount
                )
            )

            val payload =
                assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                    dataSource.fetchThreadPayload(openThreadRoute(streamChannelId = "thread-m1"), persistResult = false)
                ).data

            // The thread channel was known, so nothing asked the server to open it.
            assertEquals(emptyList(), repository.getReplyThreadRequests, "rowReplyCount=$rowReplyCount")
            assertEquals("parent body", payload.originMessage?.body, "rowReplyCount=$rowReplyCount")
            assertEquals(expected, payload.headerReplyCount(), "rowReplyCount=$rowReplyCount")
        }
    }

    @Test
    fun theOpenAnswersTotalReplacesAnOlderRememberedCount() = runTest {
        // The open answer's `replyCount` (server getThreadInfoForChannel) rides the reply snapshot.
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        twoReplies().data.copy(replyThreadReplyCount = 412)
                    )
            )
        val dataSource = dataSource(backgroundScope, repository)
        // Remembered from a row without its thread channel: the open has to ask the server.
        dataSource.rememberReplyParentCandidate(
            provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("parent body").messages.single().withoutThreadReplyMetadata(),
            threadChannelId = null,
            replyCount = 3
        )
        val route = openThreadRoute(streamChannelId = null)

        val payload =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.fetchThreadPayload(route, persistResult = false)
            ).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(412, payload.originMessage?.replyCount)
        assertEquals(412, payload.headerReplyCount())
        // The remembered parent follows, so the next open (whichever way) starts from the total.
        val remembered = assertIs<SharedThreadOriginResolution.Loaded>(dataSource.resolveReplyOrigin(route)).message
        assertEquals(412, remembered.replyCount)
    }

    @Test
    fun theOpenAnswersTotalNeverLowersTheParentsCount() {
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body", replyCount = 30)
        // (the open answer's total, or null when the load asked none) to the parent's count after it.
        listOf(null to 30, 412 to 412, 2 to 30).forEach { (total, expected) ->
            val snapshot = threadSnapshot(threadId = "thread-m1", parentMessage = parent).copy(replyThreadReplyCount = total)
            assertEquals(expected, snapshot.withReplyThreadReplyCountOnParent().parentMessage?.replyCount, "total=$total")
        }
        // No parent resolved yet: nothing to put the total on.
        assertNull(
            threadSnapshot(threadId = "thread-m1").copy(replyThreadReplyCount = 412).withReplyThreadReplyCountOnParent().parentMessage
        )
    }

    private fun dataSource(storeScope: CoroutineScope, repository: FakeConversationRepository): SharedThreadDataSource =
        testSharedThreadDataSource(
            storeScope = storeScope,
            networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            conversationRepository = repository
        )

    /** The reply thread of `m1` in `channel-1`, its parent not loaded yet. */
    private fun openThreadRoute(streamChannelId: String?): RaftThreadScreenPayload =
        unresolvedReplyPayload("m1").copy(
            sourceThreadId = streamChannelId,
            streamChannelId = streamChannelId,
            originMessage = null,
            originStatus = "loading"
        )

    private fun twoReplies(): DataResult.Content<build.raft.app.data.ThreadSnapshot> =
        DataResult.Content(
            threadSnapshot(
                threadId = "thread-m1",
                messages =
                    listOf(
                        message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "first reply"),
                        message(threadId = "thread-m1", id = "reply-2", seq = 3, body = "second reply")
                    ),
                parentMessage = null,
                conversation = replyThreadConversation(joined = true, canSend = true)
            )
        )

    private fun RaftThreadScreenPayload.headerReplyCount(): Int? =
        threadHeaderReplyCount(toThreadTimelineSlice(), toThreadReplyMetadataMap())
}
