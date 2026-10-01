package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadReceiptCoordinatorTest {
    // Owner-less markRead overloads were deleted (#1121): every receipt names its owner.
    private val testOwner = ReadReceiptOwner("production", "user-1", "server-1")

    @Test
    fun onResultPreservesAuthoritativeAckPairAfterOptimisticPrediction() = runBlocking {
        val ack = ReadReceiptResult(ChannelId("channel-ack"), seq = 40, readStateVersion = 7, maxReadSeq = 41)
        val repository = FakeReadReceiptRepository(nextResult = DataResult.Content(ack))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val predictions = mutableListOf<ReadReceiptPrediction>()
        val results = mutableListOf<DataResult<ReadReceiptResult>>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onPrediction = predictions::add,
                onResult = { _, result -> results += result },
            )

        coordinator.markReadToSeq(testOwner, ChannelId("channel-ack"), 40)
        repository.completeNext()

        assertEquals(ReadReceiptTarget.ToSeq(40), predictions.single().target)
        assertEquals(DataResult.Content(ack), results.single())
        scope.cancel()
    }

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

        coordinator.markReadToSeq(testOwner, channelId, 10L)
        assertEquals(listOf<ReadCall>(ReadCall.ToSeq(channelId, 10L)), repository.calls)

        coordinator.markReadToSeq(testOwner, channelId, 9L)
        coordinator.markReadToSeq(testOwner, channelId, 10L)
        coordinator.markReadToSeq(testOwner, channelId, 12L)
        assertEquals(1, repository.calls.size, "only the first request should be in flight")

        repository.completeNext()
        assertEquals(
            listOf<ReadCall>(ReadCall.ToSeq(channelId, 10L), ReadCall.ToSeq(channelId, 12L)),
            repository.calls
        )

        repository.completeNext()
        assertEquals(listOf(channelId, channelId), optimisticReads)
        assertEquals(
            listOf<ReadReceiptRequest>(
                ReadReceiptRequest.ToSeq(channelId, 10L, testOwner, revision = 1L),
                ReadReceiptRequest.ToSeq(channelId, 12L, testOwner, revision = 2L)
            ),
            results
        )
        scope.cancel()
    }

    @Test
    fun duplicateSeqAfterSuccessDoesNotSendAnotherRequest() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val optimisticReads = mutableListOf<ChannelId>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onOptimisticRead = { optimisticReads += it }
            )
        val channelId = ChannelId("channel-dedupe")

        coordinator.markReadToSeq(testOwner, channelId, 42L)
        repository.completeNext()
        coordinator.markReadToSeq(testOwner, channelId, 42L)
        coordinator.markReadToSeq(testOwner, channelId, 41L)

        assertEquals(listOf<ReadCall>(ReadCall.ToSeq(channelId, 42L)), repository.calls)
        assertEquals(listOf(channelId), optimisticReads)
        scope.cancel()
    }

    @Test
    fun duplicateSeqAfterFailureCanRetryLater() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = ReadReceiptCoordinator(scope = scope, repository = repository)
        val channelId = ChannelId("channel-retry")

        coordinator.markReadToSeq(testOwner, channelId, 42L)
        repository.completeNext(DataResult.Error(ScreenError(code = "read_failed")))
        coordinator.markReadToSeq(testOwner, channelId, 42L)

        assertEquals(
            listOf<ReadCall>(ReadCall.ToSeq(channelId, 42L), ReadCall.ToSeq(channelId, 42L)),
            repository.calls
        )
        scope.cancel()
    }

    @Test
    fun emptyReadResponseIsSuccessfulAndAcknowledgesSeq() = runBlocking {
        val repository = FakeReadReceiptRepository(nextResult = DataResult.Empty(EmptyReason.NoMessages))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<ReadReceiptRequest>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onFailure = { request, _ -> failures += request }
            )
        val channelId = ChannelId("channel-empty-success")

        coordinator.markReadToSeq(testOwner, channelId, 3L)
        repository.completeNext()
        coordinator.markReadToSeq(testOwner, channelId, 3L)

        assertEquals(listOf<ReadCall>(ReadCall.ToSeq(channelId, 3L)), repository.calls)
        assertEquals(emptyList(), failures)
        scope.cancel()
    }

    @Test
    fun failedReadInvokesFailureCallback() = runBlocking {
        val repository = FakeReadReceiptRepository(nextResult = DataResult.Error(ScreenError(code = "read_failed")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<ReadReceiptRequest>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onFailure = { request, _ -> failures += request }
            )
        val channelId = ChannelId("channel-2")

        coordinator.markReadToSeq(testOwner, channelId, 3L)
        repository.completeNext()

        assertEquals(
            listOf<ReadReceiptRequest>(ReadReceiptRequest.ToSeq(channelId, 3L, testOwner, revision = 1L)),
            failures
        )
        scope.cancel()
    }

    @Test
    fun nonPositiveSeqFallsBackToReadAll() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = ReadReceiptCoordinator(scope = scope, repository = repository)
        val channelId = ChannelId("channel-3")

        coordinator.markReadToSeq(testOwner, channelId, 0L)
        repository.completeNext()

        assertEquals(listOf<ReadCall>(ReadCall.All(channelId)), repository.calls)
        scope.cancel()
    }

    @Test
    fun requestsRunThroughInjectedRunner() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runnerRequests = mutableListOf<ReadReceiptRequest>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                requestRunner = { request, block ->
                    runnerRequests += request
                    block()
                }
            )
        val channelId = ChannelId("channel-4")

        coordinator.markReadToSeq(testOwner, channelId, 7L)
        repository.completeNext()

        assertEquals(
            listOf<ReadReceiptRequest>(ReadReceiptRequest.ToSeq(channelId, 7L, testOwner, revision = 1L)),
            runnerRequests
        )
        scope.cancel()
    }

    @Test
    fun activityReadAllAndThreadVisibleSeqJoinOneOwnerFlight() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val predictions = mutableListOf<ReadReceiptPrediction>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onPrediction = { predictions += it }
            )
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val channelId = ChannelId("channel-shared")

        coordinator.markReadAll(owner, channelId)
        val threadResult = async(start = CoroutineStart.UNDISPATCHED) { coordinator.awaitReadToSeq(owner, channelId, 42L) }

        assertEquals(listOf<ReadCall>(ReadCall.All(channelId)), repository.calls)
        assertEquals(1, predictions.size)
        repository.completeNext()
        assertTrue(threadResult.await() is DataResult.Content)
        scope.cancel()
    }

    @Test
    fun newerReadAllPredictionPreventsOlderSeqFailureRollback() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<ReadReceiptRequest>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onFailure = { request, _ -> failures += request }
            )
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val channelId = ChannelId("channel-supersede")

        coordinator.markReadToSeq(owner, channelId, 10L)
        coordinator.markReadAll(owner, channelId)
        repository.completeNext(DataResult.Error(ScreenError(code = "old_failed")))

        assertEquals(emptyList(), failures)
        assertEquals(
            listOf<ReadCall>(ReadCall.ToSeq(channelId, 10L), ReadCall.All(channelId)),
            repository.calls
        )
        repository.completeNext()
        scope.cancel()
    }

    @Test
    fun sameChannelAcrossDifferentOwnersDoesNotCoalesce() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = ReadReceiptCoordinator(scope = scope, repository = repository)
        val channelId = ChannelId("channel-owner")

        coordinator.markReadAll(ReadReceiptOwner("production", "user-1", "server-1"), channelId)
        coordinator.markReadAll(ReadReceiptOwner("staging", "user-1", "server-1"), channelId)

        assertEquals(listOf<ReadCall>(ReadCall.All(channelId), ReadCall.All(channelId)), repository.calls)
        repository.completeNext()
        repository.completeNext()
        scope.cancel()
    }

    @Test
    fun staleOwnerIsRejectedBeforeTransport() = runBlocking {
        val repository = FakeReadReceiptRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<ReadReceiptRequest>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                ownerValidator = { false },
                onFailure = { request, _ -> failures += request }
            )
        val owner = ReadReceiptOwner("production", "old-user", "server-1")

        coordinator.markReadAll(owner, ChannelId("channel-stale-owner"))

        assertEquals(emptyList(), repository.calls)
        assertEquals(1, failures.size)
        scope.cancel()
    }

    @Test
    fun clearSuppressesLateCompletionFromOldGeneration() = runBlocking {
        val repository = FakeReadReceiptRepository(ignoreCancellation = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val results = mutableListOf<ReadReceiptRequest>()
        val failures = mutableListOf<ReadReceiptRequest>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onResult = { request, _ -> results += request },
                onFailure = { request, _ -> failures += request }
            )

        coordinator.markReadAll(
            ReadReceiptOwner("production", "user-1", "server-1"),
            ChannelId("channel-cleared")
        )
        coordinator.clear()
        repository.completeNext(DataResult.Error(ScreenError(code = "late_failure")))

        assertEquals(emptyList(), results)
        assertEquals(emptyList(), failures)
        scope.cancel()
    }

    @Test
    fun revokeClearsOnlyOneOwnersPendingOverlayAndSuppressesItsLateCompletion() = runBlocking {
        val repository = FakeReadReceiptRepository(ignoreCancellation = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val results = mutableListOf<ReadReceiptRequest>()
        val revoked = mutableListOf<ReadReceiptOwner>()
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = repository,
                onOwnerRevoked = { revoked += it },
                onResult = { request, _ -> results += request },
            )
        val ownerA = ReadReceiptOwner("production", "user-a", "server-1")
        val ownerB = ReadReceiptOwner("production", "user-b", "server-1")
        val channelId = ChannelId("channel-scoped-revoke")

        coordinator.markReadAll(ownerA, channelId)
        coordinator.markReadAll(ownerB, channelId)
        coordinator.revoke(ownerA)
        repository.completeNext()
        repository.completeNext()

        assertEquals(listOf(ownerA), revoked)
        assertEquals(listOf(ownerB), results.map { it.owner })
        scope.cancel()
    }

    @Test
    fun readReceiptOwnerRejectsBlankIdentitySegmentsInsteadOfLegacyBucket() {
        // Positive control: a fully named owner is constructible.
        assertEquals("server-1", ReadReceiptOwner("production", "user-1", "server-1").serverId)

        // No segment may be blank: there is no owner-less "legacy" receipt owner any more (#1121).
        assertFailsWith<IllegalArgumentException> { ReadReceiptOwner("", "user-1", "server-1") }
        assertFailsWith<IllegalArgumentException> { ReadReceiptOwner("production", " ", "server-1") }
        assertFailsWith<IllegalArgumentException> { ReadReceiptOwner("production", "user-1", "") }
    }

    private class FakeReadReceiptRepository(
        private val nextResult: DataResult<ReadReceiptResult> =
            DataResult.Content(ReadReceiptResult()),
        private val ignoreCancellation: Boolean = false
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
            return if (ignoreCancellation) withContext(NonCancellable) { deferred.await() } else deferred.await()
        }

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
            calls += ReadCall.All(channelId)
            val deferred = CompletableDeferred<DataResult<ReadReceiptResult>>()
            pending += deferred
            return if (ignoreCancellation) withContext(NonCancellable) { deferred.await() } else deferred.await()
        }

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> =
            throw UnsupportedOperationException("not used")

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            throw UnsupportedOperationException("not used")

        fun completeNext(result: DataResult<ReadReceiptResult> = nextResult) {
            assertTrue(pending.isNotEmpty(), "expected pending read receipt request")
            pending.removeFirst().complete(result)
        }
    }

    private sealed interface ReadCall {
        data class ToSeq(val channelId: ChannelId, val seq: Long) : ReadCall
        data class All(val channelId: ChannelId) : ReadCall
    }
}
