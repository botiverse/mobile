package ai.slock.android.core.data.repository

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadReceiptCoordinatorTest {
    @Test
    fun markReadToSeqKeepsSingleFlightAndFollowsWithHighestPendingSeq() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val optimisticReads = mutableListOf<ChannelId>()
        val results = mutableListOf<ReadReceiptRequest>()
        val coordinator =
                ReadReceiptCoordinator(
                        scope = scope,
                        repository = repository,
                        onOptimisticRead = { optimisticReads += it },
                        onResult = { request, _ -> results += request }
                )
        val channelId = ChannelId("channel-1")

        coordinator.markReadToSeq(channelId, 10L)
        assertEquals(listOf(ReadCall.ToSeq(channelId, 10L)), repository.calls)

        coordinator.markReadToSeq(channelId, 9L)
        coordinator.markReadToSeq(channelId, 12L)
        assertEquals("only the first request should be in flight", 1, repository.calls.size)

        repository.completeNext()
        assertEquals(
                listOf(ReadCall.ToSeq(channelId, 10L), ReadCall.ToSeq(channelId, 12L)),
                repository.calls
        )

        repository.completeNext()
        assertEquals(listOf(ChannelId("channel-1"), channelId, channelId), optimisticReads)
        assertEquals(
                listOf(
                        ReadReceiptRequest.ToSeq(channelId, 10L),
                        ReadReceiptRequest.ToSeq(channelId, 12L)
                ),
                results
        )
        scope.cancel()
    }

    @Test
    fun failedReadInvokesFailureCallback() = runBlocking {
        val repository = FakeReadReceiptRepository(nextResult = DataResult.Empty(ai.slock.android.core.state.EmptyReason.NoMessages))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<ReadReceiptRequest>()
        val coordinator =
                ReadReceiptCoordinator(
                        scope = scope,
                        repository = repository,
                        onFailure = { request, _ -> failures += request }
                )
        val channelId = ChannelId("channel-2")

        coordinator.markReadToSeq(channelId, 3L)
        repository.completeNext()

        assertEquals(listOf(ReadReceiptRequest.ToSeq(channelId, 3L)), failures)
        scope.cancel()
    }

    @Test
    fun nonPositiveSeqFallsBackToReadAll() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = ReadReceiptCoordinator(scope = scope, repository = repository)
        val channelId = ChannelId("channel-3")

        coordinator.markReadToSeq(channelId, 0L)
        repository.completeNext()

        assertEquals(listOf(ReadCall.All(channelId)), repository.calls)
        scope.cancel()
    }

    private class FakeReadReceiptRepository(
            private val nextResult: DataResult<ReadReceiptResult> =
                    DataResult.Content(ReadReceiptResult())
    ) : ReadReceiptRepository {
        val calls = mutableListOf<ReadCall>()
        private val pending = ArrayDeque<CompletableDeferred<DataResult<ReadReceiptResult>>>()

        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
                throw UnsupportedOperationException("not used")

        override suspend fun markChannelReadToSeq(
                channelId: ChannelId,
                seq: Long
        ): DataResult<ReadReceiptResult> {
            calls += ReadCall.ToSeq(channelId, seq)
            val deferred = CompletableDeferred<DataResult<ReadReceiptResult>>()
            pending += deferred
            return deferred.await()
        }

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
            calls += ReadCall.All(channelId)
            val deferred = CompletableDeferred<DataResult<ReadReceiptResult>>()
            pending += deferred
            return deferred.await()
        }

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> =
                throw UnsupportedOperationException("not used")

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
                throw UnsupportedOperationException("not used")

        fun completeNext(result: DataResult<ReadReceiptResult> = nextResult) {
            assertTrue("expected pending read receipt request", pending.isNotEmpty())
            pending.removeFirst().complete(result)
        }
    }

    private sealed interface ReadCall {
        data class ToSeq(val channelId: ChannelId, val seq: Long) : ReadCall
        data class All(val channelId: ChannelId) : ReadCall
    }
}
