package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reads made offline: saved before the POST, cleared by the ack, replayed by a later process as
 * "read to seq". Each [AppProcess] reads the same key-value store afresh, as a restarted app does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadReceiptOfflineOutboxTest {
    private val owner = ReadReceiptOwner("env", "user-1", "server-1")
    private val otherAccount = ReadReceiptOwner("env", "user-2", "server-1")
    private val channel = ChannelId("channel-1")
    private val otherChannel = ChannelId("channel-2")
    private val disk = InMemoryRaftKeyValueStore()
    private val api = ScriptedReadApi()

    private val offline: DataResult<ReadReceiptResult> =
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    private val serverDown: DataResult<ReadReceiptResult> =
        DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))

    @Test
    fun aKillBeforeThePostStillSendsTheReadAfterRestart() = runTest {
        val first = appProcess()
        first.coordinator.markReadToSeq(owner, channel, 10L)
        assertEquals(emptyList(), api.calls, "the POST has not started")
        assertEquals(mapOf(channel to 10L), first.store.seqsFor(owner), "the read is already saved")
        first.kill()

        val restarted = appProcess()
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@10"), api.calls)
        assertEquals(emptyList(), restarted.store.entries(), "the ack clears it")
        restarted.kill()
    }

    @Test
    fun aKillAfterThePostBeforeTheAckSendsTheReadAgain() = runTest {
        val first = appProcess()
        api.holdNext()
        first.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        assertEquals(listOf("read:channel-1@10"), api.calls)
        first.kill()

        val restarted = appProcess()
        assertEquals(mapOf(channel to 10L), restarted.store.seqsFor(owner), "no ack, so still saved")
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@10", "read:channel-1@10"), api.calls)
        assertEquals(emptyList(), restarted.store.entries())
        restarted.kill()
    }

    @Test
    fun restartReplayKeepsTheHighestSeqAndIsNotANewRead() = runTest {
        val first = appProcess()
        first.coordinator.markReadToSeq(owner, channel, 12L)
        first.coordinator.markReadToSeq(owner, channel, 9L)
        first.kill()

        val predictions = mutableListOf<ReadReceiptPrediction>()
        val restarted = appProcess(onPrediction = predictions::add)
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@12"), api.calls)
        assertEquals(emptyList(), predictions, "a replay does not dismiss notifications of newer messages")
        restarted.kill()
    }

    @Test
    fun anAckClearsOnlyWhatItCovers() = runTest {
        val process = appProcess()
        val firstAck = api.holdNext()
        process.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        val secondAck = api.holdNext()
        process.coordinator.markReadToSeq(owner, channel, 12L)

        firstAck.complete(DataResult.Content(ReadReceiptResult(channelId = channel, seq = 10L, maxReadSeq = 10L)))
        runCurrent()
        assertEquals(mapOf(channel to 12L), process.store.seqsFor(owner), "the newer read is still waiting")

        secondAck.complete(DataResult.Content(ReadReceiptResult(channelId = channel, seq = 12L, maxReadSeq = 12L)))
        advanceUntilIdle()
        assertEquals(emptyList(), process.store.entries())
        process.kill()
    }

    @Test
    fun aNetworkFailureKeepsTheReadAndStopsRetryingAfterTheBound() = runTest {
        val failures = mutableListOf<ReadReceiptRequest>()
        val process = appProcess(onFailure = { request, _ -> failures += request })
        api.answer = { offline }

        process.coordinator.markReadToSeq(owner, channel, 10L)
        advanceUntilIdle()

        assertEquals(1 + READ_RECEIPT_REPLAY_MAX_ATTEMPTS, api.calls.size, "the first send, then the bounded retries")
        assertEquals(mapOf(channel to 10L), process.store.seqsFor(owner))
        assertEquals(emptyList(), failures, "a kept read has nothing to roll back")

        api.answer = { DataResult.Content(ReadReceiptResult(channelId = channel, seq = 10L)) }
        process.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(emptyList(), process.store.entries(), "the next reconnect sends it")
        process.kill()
    }

    @Test
    fun aServerErrorARateLimitOrAnExpiredSignInKeepsTheRead() = runTest {
        val process = appProcess(retryDelayMillis = { 60_000_000L })
        api.answer = { serverDown }
        process.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        assertEquals(mapOf(channel to 10L), process.store.seqsFor(owner))

        api.answer = { DataResult.Error(ScreenError(code = "http_429", retryable = true)) }
        process.coordinator.replayPendingReads()
        runCurrent()
        assertEquals(mapOf(channel to 10L), process.store.seqsFor(owner), "429 is not a refusal")

        api.answer = { DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session")) }
        process.coordinator.replayPendingReads()
        runCurrent()
        assertEquals(mapOf(channel to 10L), process.store.seqsFor(owner), "an expired sign-in is not lost access")
        process.kill()
    }

    @Test
    fun markUnreadCancelsTheSavedReadSoARestartDoesNotReplayIt() = runTest {
        val first = appProcess()
        api.answer = { offline }
        first.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        api.calls.clear()
        api.answer = { DataResult.Content(ReadReceiptResult(channelId = channel)) }

        first.coordinator.markUnread(owner, channel)
        assertEquals(listOf("unread:channel-1"), api.calls)
        first.kill()

        val restarted = appProcess()
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(listOf("unread:channel-1"), api.calls, "nothing to replay over the mark")
        restarted.kill()
    }

    @Test
    fun aKillBetweenTheCancelAndTheUnreadPostDoesNotReplayTheRead() = runTest {
        val first = appProcess()
        api.answer = { offline }
        first.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        api.calls.clear()

        first.coordinator.settleReadsBeforeUnread(owner, channel)
        first.kill()

        val restarted = appProcess()
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(emptyList(), api.calls)
        restarted.kill()
    }

    @Test
    fun unreadGoesOutAfterTheReadAlreadyOnItsWayAndAFailedReadStaysCancelled() = runTest {
        val process = appProcess()
        val readAnswer = api.holdNext()
        process.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()

        val unread = process.scope.launch { process.coordinator.markUnread(owner, channel) }
        runCurrent()
        assertEquals(listOf("read:channel-1@10"), api.calls, "a read landing after Unread would clear the mark")

        readAnswer.complete(offline)
        advanceUntilIdle()
        unread.join()

        assertEquals(listOf("read:channel-1@10", "unread:channel-1"), api.calls)
        assertEquals(emptyList(), process.store.entries(), "the failed read is not brought back")
        process.kill()
    }

    @Test
    fun markUnreadCancelsAReplayThatHasNotGoneOutYet() = runTest {
        val first = appProcess()
        first.coordinator.markReadToSeq(owner, channel, 10L)
        first.coordinator.markReadToSeq(owner, otherChannel, 20L)
        first.kill()

        val restarted = appProcess()
        val firstAnswer = api.holdNext()
        restarted.coordinator.replayPendingReads()
        runCurrent()
        assertEquals(listOf("read:channel-1@10"), api.calls)

        restarted.coordinator.settleReadsBeforeUnread(owner, otherChannel)
        firstAnswer.complete(DataResult.Content(ReadReceiptResult(channelId = channel, seq = 10L)))
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@10"), api.calls, "channel-2 was marked unread: its read stays unsent")
        assertEquals(emptyList(), restarted.store.entries())
        restarted.kill()
    }

    @Test
    fun aReadIsNeverSentUnderAnotherAccountAndSurvivesSignOut() = runTest {
        var signedIn: String? = "user-1"
        val validator: suspend (ReadReceiptOwner) -> Boolean = { candidate -> candidate.accountId == signedIn }
        val first = appProcess(ownerValidator = validator)
        api.answer = { offline }
        first.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        first.kill()
        api.calls.clear()
        api.answer = { DataResult.Content(ReadReceiptResult()) }

        signedIn = "user-2"
        val secondAccount = appProcess(ownerValidator = validator)
        secondAccount.coordinator.markReadToSeq(otherAccount, channel, 30L)
        advanceUntilIdle()
        api.calls.clear()
        secondAccount.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(emptyList(), api.calls, "user-1's read does not go out while user-2 is signed in")
        assertEquals(mapOf(channel to 10L), secondAccount.store.seqsFor(owner), "and it is kept")
        assertEquals(emptyMap(), secondAccount.coordinator.pendingReadSeqs(otherAccount), "user-2's Home sees none of it")
        secondAccount.kill()

        signedIn = null
        val signedOut = appProcess(ownerValidator = validator)
        signedOut.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(emptyList(), api.calls)
        assertEquals(mapOf(channel to 10L), signedOut.store.seqsFor(owner), "sign-out keeps it")
        signedOut.kill()

        signedIn = "user-1"
        val back = appProcess(ownerValidator = validator)
        back.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(listOf("read:channel-1@10"), api.calls)
        assertEquals(emptyList(), back.store.entries())
        back.kill()
    }

    @Test
    fun theSameChannelIsKeptApartPerEnvironmentAccountAndServer() {
        val store = ReadReceiptPendingStore(disk)
        val owners =
            listOf(
                owner,
                otherAccount,
                ReadReceiptOwner("staging", "user-1", "server-1"),
                ReadReceiptOwner("env", "user-1", "server-2"),
            )
        owners.forEachIndexed { index, candidate -> store.record(candidate, channel, 10L + index) }

        val reopened = ReadReceiptPendingStore(disk)
        owners.forEachIndexed { index, candidate ->
            assertEquals(mapOf(channel to 10L + index), reopened.seqsFor(candidate))
        }

        reopened.dropServer("server-1", "user-1")
        assertEquals(emptyMap(), reopened.seqsFor(owner))
        assertEquals(emptyMap(), reopened.seqsFor(owners[2]))
        assertEquals(mapOf(channel to 11L), reopened.seqsFor(otherAccount), "another account keeps its own")
        assertEquals(mapOf(channel to 13L), reopened.seqsFor(owners[3]), "another server keeps its own")
    }

    @Test
    fun aDamagedRecordIsTreatedAsEmpty() {
        disk.putString(READ_RECEIPT_PENDING_STORAGE_KEY, "{not json")
        assertEquals(emptyList(), ReadReceiptPendingStore(disk).entries())
    }

    @Test
    fun losingTheServerDropsTheReadWithoutSendingIt() = runTest {
        val first = appProcess()
        first.coordinator.markReadToSeq(owner, channel, 10L)
        first.kill()

        val restarted = appProcess(ownerValidator = { false }, ownerAccessLost = { true })
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(emptyList(), api.calls)
        assertEquals(emptyList(), restarted.store.entries())
        restarted.kill()
    }

    @Test
    fun aRevokedOwnerKeepsItsSavedReadForLater() = runTest {
        val process = appProcess()
        api.holdNext()
        process.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()

        process.coordinator.revoke(owner)
        runCurrent()

        assertEquals(mapOf(channel to 10L), process.store.seqsFor(owner))
        process.kill()
    }

    @Test
    fun aPermanentRefusalDropsTheReadAndReportsIt() = runTest {
        val refusals: List<DataResult<ReadReceiptResult>> =
            listOf(
                DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access")),
                DataResult.Error(ScreenError(code = "http_404", retryable = false)),
                DataResult.Error(ScreenError(code = "http_400", retryable = true)),
            )
        for (refusal in refusals) {
            val failures = mutableListOf<ReadReceiptRequest>()
            val first = appProcess()
            first.store.record(owner, channel, 10L)
            first.kill()

            val restarted = appProcess(onFailure = { request, _ -> failures += request })
            api.answer = { refusal }
            api.calls.clear()
            restarted.coordinator.replayPendingReads()
            advanceUntilIdle()

            assertEquals(listOf("read:channel-1@10"), api.calls, "one attempt, no retries: $refusal")
            assertEquals(emptyList(), restarted.store.entries(), "dropped on $refusal")
            assertEquals(1, failures.size, "reported on $refusal")
            restarted.kill()
        }
    }

    @Test
    fun aMarkAllWithNoKnownSeqIsNeverSaved() = runTest {
        val failures = mutableListOf<ReadReceiptRequest>()
        val first = appProcess(onFailure = { request, _ -> failures += request })
        api.answer = { offline }

        first.coordinator.markReadAll(owner, channel)
        first.coordinator.markReadToSeq(owner, otherChannel, 0L)
        advanceUntilIdle()

        assertEquals(listOf("all:channel-1", "all:channel-2"), api.calls)
        assertEquals(emptyList(), first.store.entries())
        assertEquals(2, failures.size, "not kept, so it fails as before")
        first.kill()
        api.calls.clear()

        val restarted = appProcess()
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(emptyList(), api.calls, "All is not replayed")
        restarted.kill()
    }

    @Test
    fun aMarkAllWithAKnownSeqReplaysToThatSeqNeverAll() = runTest {
        val first = appProcess(retryDelayMillis = { 60_000_000L })
        api.answer = { offline }
        first.coordinator.markReadAll(owner, channel, knownSeq = 30L)
        runCurrent()
        assertEquals(listOf("all:channel-1"), api.calls, "online, it is still the read-all it was")
        assertEquals(mapOf(channel to 30L), first.store.seqsFor(owner))
        first.kill()
        api.calls.clear()
        api.answer = { DataResult.Content(ReadReceiptResult(channelId = channel, seq = 30L)) }

        val restarted = appProcess()
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@30"), api.calls, "newer messages than 30 stay unread")
        assertEquals(emptyList(), restarted.store.entries())
        restarted.kill()
    }

    @Test
    fun anAcknowledgedMarkAllClearsItsSavedSeq() = runTest {
        val process = appProcess()
        process.coordinator.markReadAll(owner, channel, knownSeq = 30L)
        advanceUntilIdle()

        assertEquals(listOf("all:channel-1"), api.calls)
        assertEquals(emptyList(), process.store.entries())
        process.kill()
    }

    @Test
    fun aHomeResponseRequestedBeforeTheAckStillShowsTheRead() = runTest {
        val process = appProcess()
        val answer = api.holdNext()
        process.coordinator.markReadToSeq(owner, channel, 10L)
        runCurrent()
        val atRequest = process.coordinator.pendingReadSeqs(owner)
        assertEquals(mapOf(channel to 10L), atRequest)

        answer.complete(DataResult.Content(ReadReceiptResult(channelId = channel, seq = 10L, maxReadSeq = 10L)))
        runCurrent()

        assertEquals(emptyMap(), process.coordinator.pendingReadSeqs(owner), "acknowledged")
        assertEquals(mapOf(channel to 10L), process.coordinator.pendingReadOverlay(owner, atRequest), "response may predate it")
        assertEquals(emptyMap(), process.coordinator.pendingReadOverlay(owner, emptyMap()), "a later request needs no help")

        process.coordinator.settleReadsBeforeUnread(owner, channel)
        assertEquals(emptyMap(), process.coordinator.pendingReadOverlay(owner, atRequest), "Mark as Unread ends it")
        process.kill()
    }

    /** This device saw the cursor at 50 (version 5), then read to 60 offline; the app restarts online. */
    private fun TestScope.replayAgainst(
        current: Map<ChannelId, ReadReceiptFrontier>?,
        known: ReadReceiptFrontier? = ReadReceiptFrontier(50L, 5L),
        dropped: MutableList<ChannelId> = mutableListOf(),
    ): AppProcess {
        val first = appProcess()
        if (known != null) first.coordinator.noteReadFrontier(owner, channel, known)
        first.coordinator.markReadToSeq(owner, channel, 60L)
        first.kill()
        val restarted =
            appProcess(frontierLoader = { current }, onReplayDropped = { _, channelId -> dropped += channelId })
        restarted.coordinator.replayPendingReads()
        return restarted
    }

    @Test
    fun anotherDeviceMovingTheCursorBackDropsTheReplay() = runTest {
        val dropped = mutableListOf<ChannelId>()
        val restarted = replayAgainst(mapOf(channel to ReadReceiptFrontier(30L, 6L)), dropped = dropped)
        advanceUntilIdle()

        assertEquals(emptyList(), api.calls, "the read must not raise the cursor back over that Mark as Unread")
        assertEquals(emptyList(), restarted.store.entries())
        assertEquals(listOf(channel), dropped, "Home is told, so the count comes back")
        restarted.kill()
    }

    @Test
    fun anotherDeviceMarkingUnreadDropsTheReplay() = runTest {
        val restarted = replayAgainst(mapOf(channel to ReadReceiptFrontier(50L, 6L, markedUnread = true)))
        advanceUntilIdle()

        assertEquals(emptyList(), api.calls)
        assertEquals(emptyList(), restarted.store.entries())
        restarted.kill()
    }

    @Test
    fun readingARowAlreadyMarkedUnreadStillReplays() = runTest {
        val restarted =
            replayAgainst(
                mapOf(channel to ReadReceiptFrontier(50L, 5L, markedUnread = true)),
                known = ReadReceiptFrontier(50L, 5L, markedUnread = true),
            )
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls, "the read is what clears that mark")
        restarted.kill()
    }

    @Test
    fun aSummaryLaggingBehindTheBaseDoesNotDropTheReplay() = runTest {
        val restarted = replayAgainst(mapOf(channel to ReadReceiptFrontier(40L, 4L)))
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls, "an older version says nothing about a rewind")
        restarted.kill()
    }

    @Test
    fun anUnchangedOrRaisedCursorReplays() = runTest {
        val restarted = replayAgainst(mapOf(channel to ReadReceiptFrontier(55L, 7L)))
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls)
        restarted.kill()
    }

    @Test
    fun withNoBaseTheLastWriteWins() = runTest {
        val restarted = replayAgainst(mapOf(channel to ReadReceiptFrontier(30L, 6L, markedUnread = true)), known = null)
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls)
        restarted.kill()
    }

    @Test
    fun aScopeTheSummaryDoesNotListReplays() = runTest {
        val restarted = replayAgainst(emptyMap())
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls, "the summary only lists unread or marked scopes")
        restarted.kill()
    }

    @Test
    fun withNoFreshSummaryTheReadWaitsUnsent() = runTest {
        val restarted = replayAgainst(current = null)
        advanceUntilIdle()

        assertEquals(emptyList(), api.calls)
        assertEquals(mapOf(channel to 60L), restarted.store.seqsFor(owner))
        restarted.kill()
    }

    @Test
    fun aReadMadeAfterSeeingTheRewindReplays() = runTest {
        val first = appProcess()
        first.coordinator.noteReadFrontier(owner, channel, ReadReceiptFrontier(50L, 5L))
        first.coordinator.markReadToSeq(owner, channel, 60L)
        first.coordinator.noteReadFrontier(owner, channel, ReadReceiptFrontier(30L, 6L))
        first.coordinator.markReadToSeq(owner, channel, 60L)
        first.kill()

        val restarted = appProcess(frontierLoader = { mapOf(channel to ReadReceiptFrontier(30L, 6L)) })
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls)
        restarted.kill()
    }

    @Test
    fun aLaggingAnswerCannotReplaceANewerBase() = runTest {
        val process = appProcess()
        process.coordinator.noteReadFrontier(owner, channel, ReadReceiptFrontier(50L, 5L))
        process.coordinator.noteReadFrontier(owner, channel, ReadReceiptFrontier(40L, 4L))
        process.coordinator.markReadToSeq(owner, channel, 60L)

        assertEquals(ReadReceiptFrontier(50L, 5L), process.store.entry(owner, channel)?.base)
        process.kill()
    }

    @Test
    fun thisDevicesOwnMarkUnreadDoesNotDropItsNextRead() = runTest {
        val first = appProcess()
        first.coordinator.noteReadFrontier(owner, channel, ReadReceiptFrontier(50L, 5L))
        first.coordinator.markUnread(owner, channel)
        first.coordinator.markReadToSeq(owner, channel, 60L)
        first.kill()
        api.calls.clear()

        val restarted = appProcess(frontierLoader = { mapOf(channel to ReadReceiptFrontier(30L, 6L)) })
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()

        assertEquals(listOf("read:channel-1@60"), api.calls)
        restarted.kill()
    }

    @Test
    fun theSummaryIsFetchedOnlyWithReadsWaitingAndOncePerPass() = runTest {
        var fetches = 0
        val loader: suspend (ReadReceiptOwner) -> Map<ChannelId, ReadReceiptFrontier>? = {
            fetches += 1
            emptyMap()
        }
        val idle = appProcess(frontierLoader = loader)
        idle.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(0, fetches, "nothing waiting, nothing fetched")
        idle.kill()

        val first = appProcess()
        first.coordinator.markReadToSeq(owner, channel, 10L)
        first.coordinator.markReadToSeq(owner, otherChannel, 20L)
        first.kill()

        val restarted = appProcess(frontierLoader = loader)
        restarted.coordinator.replayPendingReads()
        advanceUntilIdle()
        assertEquals(1, fetches)
        assertEquals(listOf("read:channel-1@10", "read:channel-2@20"), api.calls)
        restarted.kill()
    }

    @Test
    fun theSummaryParserReadsTheCursorAndTheMark() {
        val body =
            """{"channels":{""" +
                """"a":{"unreadCount":2,"hasMention":false,"hasAnyMention":false,"readState":{"kind":"present","readStateVersion":7,"maxReadSeq":"30","latestActivity":null}},""" +
                """"b":{"unreadCount":0,"hasMention":false,"hasAnyMention":false,"readState":{"kind":"present","readStateVersion":8,"maxReadSeq":"40","latestActivity":null,"markedUnread":true}},""" +
                """"c":{"unreadCount":1,"hasMention":false,"hasAnyMention":false,"readState":{"kind":"absent"}},""" +
                """"d":{"unreadCount":1,"hasMention":false,"hasAnyMention":false,"readState":{"kind":"corrupt"}}""" +
                """},"threadUnread":{}}"""

        assertEquals(
            mapOf(
                ChannelId("a") to ReadReceiptFrontier(30L, 7L),
                ChannelId("b") to ReadReceiptFrontier(40L, 8L, markedUnread = true),
            ),
            parseFreshReadFrontiers(body),
        )
        assertEquals(null, parseFreshReadFrontiers(body.dropLast(1) + ""","stale":true}"""), "a remembered body says nothing about now")
        assertEquals(null, parseFreshReadFrontiers("{}"))
    }

    @Test
    fun theRetryDelayGrowsAndIsBounded() {
        assertEquals(5_000L, readReceiptReplayRetryDelayMillis(1))
        assertEquals(15_000L, readReceiptReplayRetryDelayMillis(2))
        assertEquals(135_000L, readReceiptReplayRetryDelayMillis(4))
        assertEquals(300_000L, readReceiptReplayRetryDelayMillis(5))
        assertEquals(300_000L, readReceiptReplayRetryDelayMillis(100))
    }

    private fun TestScope.appProcess(
        ownerValidator: suspend (ReadReceiptOwner) -> Boolean = { true },
        ownerAccessLost: suspend (ReadReceiptOwner) -> Boolean = { false },
        onPrediction: (ReadReceiptPrediction) -> Unit = {},
        onFailure: (ReadReceiptRequest, DataResult<ReadReceiptResult>) -> Unit = { _, _ -> },
        retryDelayMillis: (Int) -> Long = { attempt -> readReceiptReplayRetryDelayMillis(attempt) },
        frontierLoader: suspend (ReadReceiptOwner) -> Map<ChannelId, ReadReceiptFrontier>? = { emptyMap() },
        onReplayDropped: (ReadReceiptOwner, ChannelId) -> Unit = { _, _ -> },
    ): AppProcess {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = ReadReceiptPendingStore(disk)
        val coordinator =
            ReadReceiptCoordinator(
                scope = scope,
                repository = api,
                ownerValidator = ownerValidator,
                onPrediction = onPrediction,
                onFailure = onFailure,
                pendingStore = store,
                ownerAccessLost = ownerAccessLost,
                replayRetryDelayMillis = retryDelayMillis,
                replayFrontierLoader = frontierLoader,
                onReplayDropped = onReplayDropped,
            )
        return AppProcess(scope, store, coordinator)
    }

    private class AppProcess(
        val scope: CoroutineScope,
        val store: ReadReceiptPendingStore,
        val coordinator: ReadReceiptCoordinator,
    ) {
        fun kill() = scope.cancel()
    }

    private class ScriptedReadApi : ReadReceiptRepository {
        val calls = mutableListOf<String>()
        var answer: suspend (String) -> DataResult<ReadReceiptResult> = {
            DataResult.Content(ReadReceiptResult())
        }
        private val held = ArrayDeque<CompletableDeferred<DataResult<ReadReceiptResult>>>()

        /** The next read POST goes out and waits for the returned answer. */
        fun holdNext(): CompletableDeferred<DataResult<ReadReceiptResult>> =
            CompletableDeferred<DataResult<ReadReceiptResult>>().also { held.addLast(it) }

        private suspend fun send(call: String): DataResult<ReadReceiptResult> {
            calls += call
            return held.removeFirstOrNull()?.await() ?: answer(call)
        }

        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            throw UnsupportedOperationException("not used")

        override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> =
            send("read:${channelId.value}@$seq")

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> =
            send("all:${channelId.value}")

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
            calls += "unread:${channelId.value}"
            return DataResult.Content(ReadReceiptResult(channelId = channelId))
        }

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            throw UnsupportedOperationException("not used")
    }
}
