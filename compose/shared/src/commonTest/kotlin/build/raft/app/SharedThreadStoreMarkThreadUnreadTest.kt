package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.localPendingMessage
import build.raft.app.SharedThreadStoreTestFixtures.startLocalSendForCurrentOwner
import build.raft.app.data.ChannelUnreadSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.ReadReceiptRepository
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * botiverse/slock #11687: Mark as Unread from an open reply thread (its header ⋯ and its messages'
 * menus) writes `POST /channels/:threadChannelId/unread` through the read-receipt coordinator; the
 * open page then holds its visible reads for that thread, so it is not read again at once. The
 * answer reads like the Activity row's (MarkUnreadOutcome): Counted / Marked keep the hold, Nothing
 * to mark says so and the page reads as usual again; a failed write ends the hold too.
 */
class SharedThreadStoreMarkThreadUnreadTest {
    @Test
    fun countedMarkHoldsTheOpenThreadsVisibleReads() = runTest {
        val harness = harness(unreadAnswer = DataResult.Content(ReadReceiptResult(unreadCount = 3, readStateVersion = 7L)))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        assertEquals(listOf("read:channel-1"), harness.repository.calls)

        harness.store.accept(SharedThreadIntent.MarkThreadUnread("channel-1"))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("read:channel-1", "unread:channel-1"), harness.repository.calls)
        assertTrue(harness.notices.isEmpty())
        harness.close()
    }

    @Test
    fun nothingToMarkSaysSoAndTheThreadReadsAsUsual() = runTest {
        val harness =
            harness(unreadAnswer = DataResult.Content(ReadReceiptResult(unreadCount = 0, markedUnread = false)))

        harness.store.accept(SharedThreadIntent.MarkThreadUnread("channel-1"))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("unread:channel-1", "read:channel-1"), harness.repository.calls)
        assertEquals(listOf(RaftReadToggleNotice.MarkUnreadNothing), harness.notices)
        harness.close()
    }

    // #proj-mobile:#70 (contract cases R1-202-*): a 202 is pending; the read-state event decides.
    @Test
    fun pendingAnswerSaysNothingAndKeepsTheHold() = runTest {
        val harness = harness(unreadAnswer = pendingAnswer)

        harness.store.accept(SharedThreadIntent.MarkThreadUnread("channel-1"))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceTimeBy(60_000)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("unread:channel-1"), harness.repository.calls, "the hold stays: no read goes out")
        assertTrue(harness.notices.isEmpty(), "no toast for a pending answer")
        harness.close()
    }

    @Test
    fun failedMarkEndsTheHold() = runTest {
        val harness = harness(unreadAnswer = DataResult.Error(ScreenError(code = "network", retryable = true)))

        harness.store.accept(SharedThreadIntent.MarkThreadUnread("channel-1"))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("unread:channel-1", "read:channel-1"), harness.repository.calls)
        assertTrue(harness.notices.isEmpty())
        harness.close()
    }

    @Test
    fun coordinatorForgetsTheAcknowledgedReadSoTheNextReadWritesAgain() = runTest {
        val repository = RecordingReadReceiptRepository(DataResult.Content(ReadReceiptResult(unreadCount = 2)))
        val coordinator = ReadReceiptCoordinator(scope = backgroundScope, repository = repository)
        val owner = ReadReceiptOwner("production", "u1", "server-1")
        val channel = ChannelId("thread-ch-1")

        coordinator.awaitReadToSeq(owner, channel, 10L)
        // Acknowledged: the same read is answered locally, nothing goes out.
        coordinator.awaitReadToSeq(owner, channel, 10L)
        assertEquals(listOf("read:thread-ch-1@10"), repository.calls)

        coordinator.markUnread(owner, channel)
        coordinator.awaitReadToSeq(owner, channel, 10L)

        assertEquals(listOf("read:thread-ch-1@10", "unread:thread-ch-1", "read:thread-ch-1@10"), repository.calls)
    }

    // botiverse/slock #14222: Mark channel/DM as unread from a message's menu in the list.
    @Test
    fun markConversationUnreadMarksThePagesConversationAndHoldsItsReads() = runTest {
        val harness = harness(unreadAnswer = DataResult.Content(ReadReceiptResult(unreadCount = 1, readStateVersion = 9L)))

        harness.store.accept(SharedThreadIntent.MarkConversationUnread)
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("unread:channel-1"), harness.repository.calls, "the page's conversation, its reads held")
        assertTrue(harness.notices.isEmpty())
        harness.close()
    }

    // botiverse/slock #16250 (web messageStore.sendMessage): sending in it ends the hold as the send
    // starts, so the person's own message reads the conversation and later ones are read on sight.
    @Test
    fun sendingInTheMarkedConversationEndsItsHold() = runTest {
        val harness = harness(unreadAnswer = DataResult.Content(ReadReceiptResult(unreadCount = 1, readStateVersion = 9L)))
        harness.store.accept(SharedThreadIntent.MarkConversationUnread)

        harness.store.startLocalSendForCurrentOwner(harness.dataSource, localPendingMessage("local-1", "on it"))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("unread:channel-1", "read:channel-1"), harness.repository.calls, "the send ended the hold")
        harness.close()
    }

    @Test
    fun aFollowedThreadMarkedFromTheListIsWrittenWithoutHoldingThePage() = runTest {
        val harness = harness(unreadAnswer = DataResult.Content(ReadReceiptResult(unreadCount = 2, readStateVersion = 9L)))

        harness.store.accept(SharedThreadIntent.MarkThreadUnread("thread-ch-9"))
        harness.store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("unread:thread-ch-9", "read:channel-1"), harness.repository.calls, "the list still reads")
        harness.close()
    }

    private val pendingAnswer =
        DataResult.Content(ReadReceiptResult(channelId = ChannelId("channel-1"), markUnreadPending = true))

    private class Harness(
        val store: SharedThreadStore,
        val dataSource: SharedThreadDataSource,
        val repository: RecordingReadReceiptRepository,
        val notices: List<RaftReadToggleNotice>,
        private val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    private suspend fun TestScope.harness(unreadAnswer: DataResult<ReadReceiptResult>): Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository = RecordingReadReceiptRepository(unreadAnswer)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false },
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
                readReceiptCoordinator = ReadReceiptCoordinator(scope = scope, repository = repository),
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, readCoalesceTimeSource = testScheduler.timeSource)
        val notices = mutableListOf<RaftReadToggleNotice>()
        scope.launch { store.readToggleNotices.collect { notices += it } }
        store.accept(SharedThreadIntent.Start(basePayload()))
        return Harness(store, dataSource, repository, notices, scope)
    }

    internal class RecordingReadReceiptRepository(
        private val unreadAnswer: DataResult<ReadReceiptResult>,
    ) : ReadReceiptRepository {
        val calls = mutableListOf<String>()

        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            throw UnsupportedOperationException("not used")

        override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> {
            calls += "read:${channelId.value}@$seq"
            return DataResult.Content(ReadReceiptResult(channelId = channelId, seq = seq))
        }

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
            calls += "read:${channelId.value}"
            return DataResult.Content(ReadReceiptResult(channelId = channelId))
        }

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
            calls += "unread:${channelId.value}"
            return unreadAnswer
        }

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            throw UnsupportedOperationException("not used")
    }
}
