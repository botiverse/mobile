package build.raft.app.data

import build.raft.app.model.ProductFeedbackUnreadUpdated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings / Feedback dot's total, summed over every app (slock #14722, one list across apps):
 * `unread_total` of `GET tickets?sources=all&limit=1`, read on start and reconnect, and read again
 * when any app's push (one app's whole total, not the sum) or a ticket read here says it moved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftFeedbackUnreadStoreTest {
    /** The `GET tickets?sources=all&limit=1` reads: each takes the next answer, waiting on its gate when it has one. */
    private class FakeReads {
        val answers = ArrayDeque<Pair<FeedbackResult<Int?>, CompletableDeferred<Unit>?>>()
        var count = 0

        fun answer(total: Int?, gate: CompletableDeferred<Unit>? = null) {
            answers += FeedbackResult.Ok(total) to gate
        }

        suspend fun read(): FeedbackResult<Int?> {
            count++
            val (result, gate) = answers.removeFirstOrNull() ?: error("unexpected read #$count")
            gate?.await()
            return result
        }
    }

    private fun TestScope.store(reads: FakeReads, clientKind: String = "android") =
        RaftFeedbackUnreadStore(
            clientKind = clientKind,
            scope = backgroundScope,
            readTotal = reads::read,
            timeSource = testScheduler.timeSource,
        )

    private fun app(source: String, total: Int) = ProductFeedbackUnreadUpdated(source, userId = null, unreadTotal = total)

    private fun web(userId: String, total: Int) = ProductFeedbackUnreadUpdated(ProductFeedbackUnreadUpdated.WebSource, userId, total)

    @Test
    fun signedOutThereIsNoDotAndNoRequest() = runTest {
        val reads = FakeReads()
        val store = store(reads)
        val session = MutableStateFlow<String?>(null)
        store.follow(session)
        runCurrent()
        store.onReconnected()
        store.applyRead(3)
        assertFalse(store.applyRealtime("user-1", app("android", 2)))
        runCurrent()
        assertEquals(0, reads.count)
        assertEquals(0, store.unreadTotal.value)

        // Signing in reads that person's total; signing out drops it, with no request.
        reads.answer(2)
        session.value = "user-1"
        runCurrent()
        assertEquals(1, reads.count)
        assertEquals(2, store.unreadTotal.value)
        session.value = null
        runCurrent()
        assertEquals(0, store.unreadTotal.value)
        store.onReconnected()
        runCurrent()
        assertEquals(1, reads.count)
    }

    @Test
    fun everyAppsPushOfThePersonReadsTheSumAgain() = runTest {
        // clientKind, event, taken: every connection of the person receives every one of these, and
        // the dot counts every app, so each of the person's own moves the sum.
        val cases =
            listOf(
                Triple("android", app("android", 4), true),
                Triple("android", app("ios", 4), true),
                Triple("android", app("ohos", 4), true),
                Triple("android", web("user-1", 4), true),
                Triple("android", web("user-2", 4), false),
                Triple("ios", app("android", 4), true),
                Triple("ios", web("user-1", 4), true),
                Triple("web", web("user-1", 4), true),
                Triple("web", web("user-2", 4), false),
                Triple("web", app("android", 4), true),
            )
        cases.forEach { (clientKind, event, taken) ->
            val reads = FakeReads().also { it.answer(1) }
            val store = store(reads, clientKind)
            store.onSession("user-1")
            runCurrent()
            // The push holds one app's total (4), never the sum: the sum is read (7).
            if (taken) reads.answer(7)
            assertEquals(taken, store.applyRealtime("user-1", event), "$clientKind <- $event")
            runCurrent()
            assertEquals(if (taken) 2 else 1, reads.count, "$clientKind <- $event")
            assertEquals(if (taken) 7 else 1, store.unreadTotal.value, "$clientKind <- $event")
        }
    }

    @Test
    fun anEventOnAnotherAccountsConnectionIsIgnored() = runTest {
        val reads = FakeReads().also { it.answer(1) }
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()
        assertFalse(store.applyRealtime("user-2", app("android", 5)))
        runCurrent()
        assertEquals(1, reads.count)
        assertEquals(1, store.unreadTotal.value)
        // The sum read again can clear the dot.
        reads.answer(0)
        assertTrue(store.applyRealtime("user-1", app("android", 0)))
        runCurrent()
        assertEquals(0, store.unreadTotal.value)
    }

    @Test
    fun theSumIsWhatTheDotShowsWhicheverAppsRepliesAreUnread() = runTest {
        // Two unread on the web, one on iOS, in the Android app: the merged first page says 3.
        val reads = FakeReads().also { it.answer(3) }
        val store = store(reads, "android")
        store.onSession("user-1")
        runCurrent()
        assertEquals(3, store.unreadTotal.value)
        // The Feedback list's first page says the same sum.
        store.applyRead(3)
        assertEquals(3, store.unreadTotal.value)
        // Reading the iOS ticket here: its answer is iOS's own total (0), so the sum is read again.
        reads.answer(2)
        store.onTicketRead()
        runCurrent()
        assertEquals(2, reads.count)
        assertEquals(2, store.unreadTotal.value)
    }

    @Test
    fun aChangeWhileAReadIsOnItsWayIsReadOnceMoreAfterIt() = runTest {
        val reads = FakeReads()
        val gate = CompletableDeferred<Unit>()
        reads.answer(3)
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()

        // A push starts a read; two more pushes land while it is on its way: one more read after it.
        reads.answer(2, gate)
        reads.answer(1)
        assertTrue(store.applyRealtime("user-1", app("ios", 0)))
        runCurrent()
        assertTrue(store.applyRealtime("user-1", web("user-1", 1)))
        store.onTicketRead()
        runCurrent()
        assertEquals(2, reads.count)
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, reads.count)
        assertEquals(1, store.unreadTotal.value)
    }

    @Test
    fun aReconnectReadsAgainOnceForSocketsJoiningTogether() = runTest {
        val reads = FakeReads()
        reads.answer(1)
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()
        assertEquals(1, reads.count)

        // A reply came while the socket was down: the reconnect read brings it.
        reads.answer(2)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        assertEquals(2, reads.count)
        assertEquals(2, store.unreadTotal.value)

        // Another server's socket joining just after shares that read.
        advanceTimeBy(1_000)
        store.onReconnected()
        runCurrent()
        assertEquals(2, reads.count)

        // A later reconnect reads again.
        reads.answer(0)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS)
        store.onReconnected()
        runCurrent()
        assertEquals(3, reads.count)
        assertEquals(0, store.unreadTotal.value)
    }

    @Test
    fun anUnknownOrFailedReadKeepsTheTotalHeld() = runTest {
        val reads = FakeReads()
        reads.answer(3)
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()
        assertEquals(3, store.unreadTotal.value)

        // `degraded`: unread_total null is "not known", never 0.
        reads.answer(null)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        assertEquals(3, store.unreadTotal.value)

        reads.answers += FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "feedback_service_unavailable", 503)) to null
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        assertEquals(3, reads.count)
        assertEquals(3, store.unreadTotal.value)
    }

    @Test
    fun aLocalReadNewerThanAReadInFlightWins() = runTest {
        val reads = FakeReads()
        reads.answer(2)
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()

        val second = CompletableDeferred<Unit>()
        reads.answer(5, second)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        // The Feedback list's first page answered its sum meanwhile.
        store.applyRead(1)
        second.complete(Unit)
        runCurrent()
        assertEquals(1, store.unreadTotal.value)
    }

    @Test
    fun anotherAccountStartsFromItsOwnReadAndAnOldReadDoesNotLand() = runTest {
        val reads = FakeReads()
        val gate = CompletableDeferred<Unit>()
        reads.answer(7, gate)
        reads.answer(1)
        val store = store(reads)
        val session = MutableStateFlow<String?>("user-1")
        store.follow(session)
        runCurrent()
        session.value = "user-2"
        runCurrent()
        assertEquals(2, reads.count)
        assertEquals(1, store.unreadTotal.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, store.unreadTotal.value)
        assertFalse(store.applyRealtime("user-1", app("android", 9)))
    }
}
