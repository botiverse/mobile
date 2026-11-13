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
        val answers = ArrayDeque<Pair<FeedbackResult<FeedbackUnreadRead>, CompletableDeferred<Unit>?>>()
        var count = 0

        fun answer(total: Int?, gate: CompletableDeferred<Unit>? = null) {
            answers += FeedbackResult.Ok(FeedbackUnreadRead(total)) to gate
        }

        /** A first page with `unread_by_source` (an app that could not be read has no key). */
        fun answerBySource(bySource: Map<String, Int>, total: Int? = null, gate: CompletableDeferred<Unit>? = null) {
            answers += FeedbackResult.Ok(FeedbackUnreadRead(total, bySource)) to gate
        }

        suspend fun read(): FeedbackResult<FeedbackUnreadRead> {
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
        store.applyRead(FeedbackUnreadRead(3))
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
        store.applyRead(FeedbackUnreadRead(3))
        assertEquals(3, store.unreadTotal.value)
        // Reading the iOS ticket here: its answer is iOS's own total (0); with only the sum known
        // (no `unread_by_source`), the sum is read again.
        reads.answer(2)
        store.onTicketRead("ios", 0)
        runCurrent()
        assertEquals(2, reads.count)
        assertEquals(2, store.unreadTotal.value)
    }

    @Test
    fun aPushReplacesItsOwnAppsTotalAndTheDotShowsTheSum() = runTest {
        // unread_by_source (Archer, #triage:#364): each app's total; a push is one app's whole total.
        val reads = FakeReads().also { it.answerBySource(mapOf("web" to 2, "android" to 1, "ios" to 0), total = 3) }
        val store = store(reads, "android")
        store.onSession("user-1")
        runCurrent()
        assertEquals(3, store.unreadTotal.value)

        // A reply on an iOS ticket: iOS's entry becomes 4, the others stay, no read.
        assertTrue(store.applyRealtime("user-1", app("ios", 4)))
        runCurrent()
        assertEquals(7, store.unreadTotal.value)
        // The web's event is the web app's entry.
        assertTrue(store.applyRealtime("user-1", web("user-1", 0)))
        assertEquals(5, store.unreadTotal.value)
        // An app not named before (HarmonyOS) joins the sum.
        assertTrue(store.applyRealtime("user-1", app("ohos", 1)))
        assertEquals(6, store.unreadTotal.value)
        // Another person's web event changes nothing.
        assertFalse(store.applyRealtime("user-1", web("user-2", 9)))
        assertEquals(6, store.unreadTotal.value)
        assertEquals(1, reads.count)
    }

    @Test
    fun anAppMissingFromAFirstPageKeepsItsTotal() = runTest {
        val reads = FakeReads().also { it.answerBySource(mapOf("web" to 2, "android" to 1, "ios" to 3), total = 6) }
        val store = store(reads, "ios")
        store.onSession("user-1")
        runCurrent()
        assertEquals(6, store.unreadTotal.value)

        // iOS could not be read this time (no key, and `unread_total` null): its 3 stays.
        reads.answerBySource(mapOf("web" to 0, "android" to 1), total = null)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        assertEquals(4, store.unreadTotal.value)

        // The Feedback list's first page names apps the same way.
        store.applyRead(FeedbackUnreadRead(null, mapOf("android" to 0)))
        assertEquals(3, store.unreadTotal.value)
    }

    @Test
    fun aFirstPageWithAnAppUnavailableShowsTheSumOfTheAppsThatAnswered() = runTest {
        // `unread_total` null (iOS unavailable) but `unread_by_source` sent: the dot is the map's sum,
        // never 0 and never an older total.
        val reads = FakeReads().also { it.answer(9) }
        val store = store(reads, "android")
        store.onSession("user-1")
        runCurrent()
        assertEquals(9, store.unreadTotal.value)
        reads.answerBySource(mapOf("web" to 2, "android" to 1), total = null)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        assertEquals(3, store.unreadTotal.value)

        // The same from a fresh start, and from the Feedback list's own first page.
        val fresh = FakeReads().also { it.answerBySource(mapOf("web" to 2, "android" to 1), total = null) }
        val other = store(fresh, "ios")
        other.onSession("user-1")
        runCurrent()
        assertEquals(3, other.unreadTotal.value)
        other.applyRead(FeedbackUnreadRead(null, mapOf("web" to 0, "ios" to 5)))
        assertEquals(6, other.unreadTotal.value)
    }

    @Test
    fun aTicketReadReplacesItsAppsTotal() = runTest {
        val reads = FakeReads().also { it.answerBySource(mapOf("web" to 2, "android" to 1), total = 3) }
        val store = store(reads, "android")
        store.onSession("user-1")
        runCurrent()
        // A web ticket read here: the web's total is now 0.
        store.onTicketRead("web", 0)
        assertEquals(1, store.unreadTotal.value)
        // A ticket of this app (no source named): this app's.
        store.onTicketRead(null, 0)
        assertEquals(0, store.unreadTotal.value)
        runCurrent()
        assertEquals(1, reads.count)
        // No total in the answer: the sum is read again.
        reads.answerBySource(mapOf("web" to 1, "android" to 0), total = 1)
        store.onTicketRead("web", null)
        runCurrent()
        assertEquals(2, reads.count)
        assertEquals(1, store.unreadTotal.value)
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
        store.onTicketRead("android", null)
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
    fun aPushNewerThanAReadInFlightWins() = runTest {
        // Each app's total known: a push replaces one app's while a reconnect read is on its way;
        // that read (sent before the push) does not land over it.
        val reads = FakeReads().also { it.answerBySource(mapOf("web" to 1, "android" to 2), total = 3) }
        val store = store(reads, "android")
        store.onSession("user-1")
        runCurrent()
        assertEquals(3, store.unreadTotal.value)

        val gate = CompletableDeferred<Unit>()
        reads.answerBySource(mapOf("web" to 1, "android" to 2), total = 3, gate = gate)
        advanceTimeBy(FEEDBACK_UNREAD_RECONNECT_WINDOW_MILLIS + 1)
        store.onReconnected()
        runCurrent()
        // The Android ticket's reply was read on another device meanwhile.
        assertTrue(store.applyRealtime("user-1", app("android", 0)))
        assertEquals(1, store.unreadTotal.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, reads.count)
        assertEquals(1, store.unreadTotal.value)
    }

    @Test
    fun aListReadSentBeforeAPushOrATicketReadDoesNotLandOverIt() = runTest {
        // Web captureFeedbackUnreadListRead: the Feedback list's figures are as old as its request.
        val reads = FakeReads().also { it.answerBySource(mapOf("web" to 2, "ios" to 1), total = 3) }
        val store = store(reads, "ios")
        store.onSession("user-1")
        runCurrent()

        val listRead = store.captureListRead()
        // While the list request is out: a push, then the list answers with the older figures.
        assertTrue(store.applyRealtime("user-1", web("user-1", 0)))
        assertEquals(1, store.unreadTotal.value)
        listRead(FeedbackUnreadRead(3, mapOf("web" to 2, "ios" to 1)))
        assertEquals(1, store.unreadTotal.value, "the push wins")

        val afterTicket = store.captureListRead()
        store.onTicketRead("ios", 0)
        afterTicket(FeedbackUnreadRead(1, mapOf("web" to 0, "ios" to 1)))
        assertEquals(0, store.unreadTotal.value, "the ticket read wins")

        // Nothing newer: a list read lands.
        val quiet = store.captureListRead()
        quiet(FeedbackUnreadRead(4, mapOf("web" to 3, "ios" to 1)))
        assertEquals(4, store.unreadTotal.value)
        assertEquals(1, reads.count)
    }

    @Test
    fun aPushOfAnAppTheServerDoesNotHaveAddsNothing() = runTest {
        val reads = FakeReads().also { it.answerBySource(mapOf("web" to 1, "android" to 1), total = 2) }
        val store = store(reads, "android")
        store.onSession("user-1")
        runCurrent()
        assertFalse(store.applyRealtime("user-1", app("windows", 5)))
        // A ticket read naming an unknown app replaces nothing (the sum is read again instead).
        reads.answerBySource(mapOf("web" to 1, "android" to 1), total = 2)
        store.onTicketRead("windows", 7)
        runCurrent()
        assertEquals(2, store.unreadTotal.value)
        assertEquals(2, reads.count)
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
        store.applyRead(FeedbackUnreadRead(1))
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
