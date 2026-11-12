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
 * The Settings / Feedback dot's total (slock `docs/development/feedback-native-entry.md` "Unread
 * replies", f0f773995): read on start and reconnect, then pushed, each app taking only its own event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftFeedbackUnreadStoreTest {
    /** The `GET tickets?limit=1` reads: each takes the next answer, waiting on its gate when it has one. */
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
    fun eachAppTakesOnlyItsOwnEvent() = runTest {
        // clientKind, event, taken: every connection of the person receives every one of these.
        val cases =
            listOf(
                Triple("android", app("android", 4), true),
                Triple("android", app("ios", 4), false),
                Triple("android", app("ohos", 4), false),
                Triple("android", web("user-1", 4), false),
                Triple("ios", app("ios", 4), true),
                Triple("ios", app("android", 4), false),
                Triple("ios", web("user-1", 4), false),
                // The desktop app files into the web app: the web's event, for the signed-in person only.
                Triple("web", web("user-1", 4), true),
                Triple("web", web("user-2", 4), false),
                Triple("web", app("android", 4), false),
            )
        cases.forEach { (clientKind, event, taken) ->
            val reads = FakeReads().also { it.answer(1) }
            val store = store(reads, clientKind)
            store.onSession("user-1")
            runCurrent()
            assertEquals(taken, store.applyRealtime("user-1", event), "$clientKind <- $event")
            assertEquals(if (taken) 4 else 1, store.unreadTotal.value, "$clientKind <- $event")
        }
    }

    @Test
    fun anEventOnAnotherAccountsConnectionIsIgnored() = runTest {
        val reads = FakeReads().also { it.answer(1) }
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()
        assertFalse(store.applyRealtime("user-2", app("android", 5)))
        assertEquals(1, store.unreadTotal.value)
        // The whole total, never a change: 0 clears the dot.
        assertTrue(store.applyRealtime("user-1", app("android", 0)))
        assertEquals(0, store.unreadTotal.value)
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
    fun aPushOrALocalReadNewerThanAReadInFlightWins() = runTest {
        val reads = FakeReads()
        val gate = CompletableDeferred<Unit>()
        reads.answer(2, gate)
        val store = store(reads)
        store.onSession("user-1")
        runCurrent()
        // The ticket was read on another device while the start read was on its way.
        assertTrue(store.applyRealtime("user-1", app("android", 0)))
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, store.unreadTotal.value)

        val second = CompletableDeferred<Unit>()
        reads.answer(5, second)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        // Opening the ticket here answered its own total.
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
