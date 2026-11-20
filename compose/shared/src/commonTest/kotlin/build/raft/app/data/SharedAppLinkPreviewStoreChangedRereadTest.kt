package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val CServer = "server-1"
private const val CViewer = "user-1:$CServer"
private const val COtherServer = "server-2"
private const val COtherViewer = "user-1:$COtherServer"
private const val CConversation = "channel-1"
private const val CMessageA = "44444444-4444-4444-4444-444444444441"
private const val CMessageB = "44444444-4444-4444-4444-444444444442"
private const val CMessageC = "44444444-4444-4444-4444-444444444443"
private const val CMessageD = "44444444-4444-4444-4444-444444444444"
private const val CPr1 = "https://stamp.example.test/botiverse/slock/pull/1"
private const val CPr2 = "https://stamp.example.test/botiverse/slock/pull/2"
private const val CContent = "See $CPr1 and $CPr2"
private const val CStamp = """{"id":"app-stamp","name":"Stamp","icon":null}"""
private val KeyA = "a1".repeat(32)
private val KeyB = "b2".repeat(32)
private val KeyC = "c3".repeat(32)

/**
 * A v2 card item as Raft sends it to a client that declared `card.changed.v1` (botiverse/slock
 * staging 080597aea `linkCardReadService.ts`: `{ url, kind, app, preview, ticket, actions,
 * object_key_sha256? }`); [objectKey] null is an App that named no object.
 */
private fun changedCard(url: String, objectKey: String?, title: String = "Open") =
    """{"kind":"v2","url":"$url","app":$CStamp,"ticket":null,"actions":[],${objectKey?.let { "\"object_key_sha256\":\"$it\"," }.orEmpty()}
      "preview":{"version":2,"display":"card","title":"$title","card_revision":"cr-1","business_revision":"br-1",
      "card":{"root":"r","elements":{"r":{"type":"Badge","props":{"text":"$title","tone":"done"}}}}}}"""

private fun changedLimited(url: String, retryAfter: Int) = """{"kind":"rate_limited","url":"$url","retry_after":$retryAfter}"""

private fun changedAnswer(vararg items: String) = HomeNetworkResponse(200, """{"items":[${items.joinToString(",")}],"prompts":[]}""")

/**
 * Link cards that update themselves (botiverse/slock docs/development/app-link-previews.md "Telling
 * Raft a card changed" and "Web display"; web `linkCardChangedRereads.ts`, `useAppLinkPreviews.ts`),
 * and the app's own catch-up when it comes back ([RaftFreshnessSignal]): which cards are read again,
 * when, and that the card shown stays until a new one comes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppLinkPreviewStoreChangedRereadTest {
    /** A store on the test's virtual clock, with a fixed jitter of one second. */
    private class Harness(scope: TestScope) {
        val api = FakeAppLinkPreviewApi()
        val nudges = MutableSharedFlow<RaftLinkCardChangedHint>(extraBufferCapacity = 16)
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 16)
        val diagnostics = mutableListOf<String>()
        val store =
            SharedAppLinkPreviewStore(
                api = api,
                scope = scope.backgroundScope,
                diagnosticsLogger = diagnostics::add,
                retryJitterMs = { 0L },
                monotonicMillis = { scope.testScheduler.currentTime },
                changedJitterMs = { 1_000L },
                changedHints = nudges,
                freshness = freshness,
            )

        /** One row of a message: its view of the batched read and its presence on the page. */
        inner class Row(val messageId: String = CMessageA, val content: String = CContent, val viewerKey: String = CViewer, val serverId: String = CServer) {
            val batch = AppLinkBatchRead(AppLinkCardView.create(), "en")
            val presence = AppLinkRowPresence(viewerKey, serverId, messageId, content, batch)
            private var job: Job? = null

            /** The row's first read, as the message row asks for it. */
            fun read() = store.read(viewerKey, serverId, messageId, content, CConversation, batch, presence)

            fun show(onScreen: Boolean = true) {
                job = store.keepFresh(presence)
                presence.onScreen(onScreen)
            }

            fun leave() {
                presence.left()
                job?.cancel()
            }

            val reads: Int get() = api.cardReads.count { it.viewInstanceId == batch.view.id }

            fun title(url: String): String? =
                (store.state.value.entries[appLinkReadScope(viewerKey, messageId, content, batch.mode)]?.results?.get(url) as? AppLinkResult.Card)?.title

            val changed: Int get() = store.state.value.entries[appLinkReadScope(viewerKey, messageId, content, batch.mode)]?.changed ?: 0
        }
    }

    /** A harness whose store already collects its nudges, and a row of [CMessageA] read and shown. */
    private fun TestScope.shown(vararg items: String, onScreen: Boolean = true): Pair<Harness, Harness.Row> {
        val harness = Harness(this)
        harness.api.cardReadResponses += changedAnswer(*items)
        val row = harness.Row()
        row.read()
        row.show(onScreen)
        runCurrent()
        assertEquals(1, harness.api.cardReads.size, "the first read")
        return harness to row
    }

    private fun TestScope.after(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun aNudgeReadsOnlyTheCardOfThatObjectAgainAfterTheJitterAndTheCardStaysUntilThen() = runTest(StandardTestDispatcher()) {
        val (harness, row) = shown(changedCard(CPr1, KeyA), changedCard(CPr2, KeyB))
        // Another Server's nudge, and an object no card here shows: nothing is read.
        harness.nudges.emit(RaftLinkCardChangedHint(COtherServer, KeyA))
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyC))
        after(5_000)
        assertEquals(1, harness.api.cardReads.size)

        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, KeyA, title = "Merged"))
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(999)
        assertEquals(1, harness.api.cardReads.size, "not before the jitter")
        assertEquals("Open", row.title(CPr1), "the card shown stays while it waits")
        after(1)
        assertEquals(2, harness.api.cardReads.size)
        assertEquals(listOf(CPr1), harness.api.cardReads.last().urls, "only the link of that object, as Refresh reads it")
        assertEquals(row.batch.view.id, harness.api.cardReads.last().viewInstanceId)
        assertEquals("Merged", row.title(CPr1))
        assertEquals("Open", row.title(CPr2))
    }

    @Test
    fun anObjectIsReadAgainAtMostOncePerTenSecondsAndNudgesMeanwhileAreThatOneRead() = runTest(StandardTestDispatcher()) {
        val (harness, _) = shown(changedCard(CPr1, KeyA))
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(1_000)
        assertEquals(2, harness.api.cardReads.size)
        // One second later: it waits for the ten seconds after the last re-read (at 1 s), so until 11 s.
        after(1_000)
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(3_000)
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(5_999)
        assertEquals(2, harness.api.cardReads.size, "at 10.999 s")
        after(1)
        assertEquals(3, harness.api.cardReads.size, "one read for both nudges")
        after(19_000)
        assertEquals(3, harness.api.cardReads.size)

        // A nudge that comes while it reads starts another read after it (the interval applies).
        val gate = CompletableDeferred<Unit>()
        harness.api.readGate = gate
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(1_000)
        assertEquals(4, harness.api.cardReads.size, "sent, not answered yet")
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        runCurrent()
        harness.api.readGate = null
        gate.complete(Unit)
        after(9_999)
        assertEquals(4, harness.api.cardReads.size)
        after(1)
        assertEquals(5, harness.api.cardReads.size)
    }

    @Test
    fun aRowThatIsNotOnScreenWaitsForTheScreen() = runTest(StandardTestDispatcher()) {
        // Composed ahead of the scroll, not drawn.
        val (harness, row) = shown(changedCard(CPr1, KeyA), onScreen = false)
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(60_000)
        assertEquals(1, harness.api.cardReads.size, "nothing is read for a row off screen")
        row.presence.onScreen(true)
        after(999)
        assertEquals(1, harness.api.cardReads.size)
        after(1)
        assertEquals(2, harness.api.cardReads.size)
    }

    @Test
    fun aNudgeForAnAnswerNoRowShowsIsReadOnceWhenItsRowIsNextOnScreen() = runTest(StandardTestDispatcher()) {
        val (harness, first) = shown(changedCard(CPr1, KeyA))
        first.leave()
        runCurrent()
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(60_000)
        assertEquals(1, harness.api.cardReads.size, "no row, no read")
        assertNotEquals(0, first.changed, "the answer is marked")

        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, KeyA, title = "Merged"))
        val back = harness.Row()
        back.show(onScreen = false)
        after(5_000)
        assertEquals(0, back.reads, "composed, not on screen yet")
        assertEquals("Open", back.title(CPr1), "the answer it had is shown meanwhile")
        back.presence.onScreen(true)
        runCurrent()
        assertEquals(1, back.reads, "read at once when it is on screen")
        assertEquals(listOf(CPr1), harness.api.cardReads.last().urls)
        assertEquals("Merged", back.title(CPr1))
        assertEquals(0, back.changed)

        // Scrolled away and back again: it was read, nothing more is.
        back.leave()
        val again = harness.Row()
        again.show()
        after(60_000)
        assertEquals(2, harness.api.cardReads.size)
    }

    @Test
    fun afterTheAppComesBackKeyedCardsOnScreenAreReadOnceAndTheOthersWhenNextShown() = runTest(StandardTestDispatcher()) {
        val harness = Harness(this)
        // A: on screen. B: read, its row gone. C: on screen, its App named no object. D: on screen, another Server.
        val a = harness.Row(CMessageA, "See $CPr1")
        val b = harness.Row(CMessageB, "See $CPr2")
        val c = harness.Row(CMessageC, "See $CPr1")
        val d = harness.Row(CMessageD, "See $CPr1", COtherViewer, COtherServer)
        listOf(a to changedCard(CPr1, KeyA), b to changedCard(CPr2, KeyB), c to changedCard(CPr1, null), d to changedCard(CPr1, KeyA)).forEach { (row, item) ->
            harness.api.cardReadResponses += changedAnswer(item)
            row.read()
            runCurrent()
        }
        listOf(a, c, d).forEach { it.show() }
        runCurrent()
        assertEquals(4, harness.api.cardReads.size)

        harness.freshness.emit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        after(999)
        assertEquals(4, harness.api.cardReads.size, "after the jitter, like a nudge")
        after(1)
        assertEquals(listOf(2, 1, 1, 2), listOf(a, b, c, d).map { it.reads }, "the keyed cards on screen, once")
        assertEquals(0, a.changed)

        // The same return reported again, and the network alone: no second read.
        harness.freshness.emit(RaftFreshnessEvent(RaftFreshnessTrigger.Foreground))
        harness.freshness.emit(RaftFreshnessEvent(RaftFreshnessTrigger.NetworkRestored))
        after(2_000)
        assertEquals(listOf(2, 1, 1, 2), listOf(a, b, c, d).map { it.reads })

        // The other Server's socket came back: only its cards, and not sooner than ten seconds after their last re-read.
        harness.freshness.emit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, COtherServer, transportEpoch = 7))
        after(7_999)
        assertEquals(listOf(2, 1, 1, 2), listOf(a, b, c, d).map { it.reads }, "at 10.999 s")
        after(1)
        assertEquals(listOf(2, 1, 1, 3), listOf(a, b, c, d).map { it.reads })

        // B was read before the app came back: read once when its row is next on screen, then no more.
        val bBack = harness.Row(CMessageB, "See $CPr2")
        bBack.show()
        runCurrent()
        assertEquals(1, bBack.reads)
        bBack.leave()
        val bAgain = harness.Row(CMessageB, "See $CPr2")
        bAgain.show()
        after(60_000)
        assertEquals(0, bAgain.reads)
        assertEquals(8, harness.api.cardReads.size)
    }

    @Test
    fun aRereadThatFailsKeepsTheCardAndIsTriedAgainOnlyWhenTheRowIsBackAfterTheCooldown() = runTest(StandardTestDispatcher()) {
        val (harness, row) = shown(changedCard(CPr1, KeyA))
        harness.api.cardReadResponses += HomeNetworkResponse(500, null)
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(1_000)
        assertEquals(2, harness.api.cardReads.size)
        assertEquals("Open", row.title(CPr1), "a failed read keeps the card")
        // The row stays: it does not try again on its own.
        after(5_000)
        assertEquals(2, harness.api.cardReads.size)

        // Scrolled away and back at once: not before 30 s after the failed read went out (at 1 s).
        row.leave()
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, KeyA, title = "Merged"))
        val back = harness.Row()
        back.show()
        after(24_999)
        assertEquals(0, back.reads, "at 30.999 s")
        assertEquals("Open", back.title(CPr1))
        after(1)
        assertEquals(1, back.reads)
        assertEquals("Merged", back.title(CPr1))
        assertEquals(0, back.changed)
    }

    @Test
    fun aMessageWhoseFirstReadFailedIsNotReadOnEveryReturnOfItsRow() = runTest(StandardTestDispatcher()) {
        val harness = Harness(this)
        harness.api.cardReadResponses += HomeNetworkResponse(500, null)
        harness.api.cardReadResponses += HomeNetworkResponse(500, null)
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, KeyA))
        val first = harness.Row()
        first.read()
        runCurrent()
        assertEquals(1, harness.api.cardReads.size)
        first.leave()

        // Back five seconds later: it waits for the rest of the 30 s, and leaving again ends the wait.
        after(5_000)
        val second = harness.Row()
        second.read()
        after(20_000)
        assertEquals(1, harness.api.cardReads.size, "no read on a quick return")
        second.leave()
        after(60_000)
        assertEquals(1, harness.api.cardReads.size, "nor after it left")

        // Back after the cooldown: read at once (it fails again, at 85 s).
        val third = harness.Row()
        third.read()
        runCurrent()
        assertEquals(2, harness.api.cardReads.size)
        third.leave()

        // A row that stays is read when the cooldown ends.
        after(1_000)
        val fourth = harness.Row()
        fourth.read()
        after(28_999)
        assertEquals(2, harness.api.cardReads.size, "at 114.999 s")
        after(1)
        assertEquals(3, harness.api.cardReads.size)
        assertEquals("Open", fourth.title(CPr1))
    }

    @Test
    fun noCardChangesWhileThePersonIsOnTheMessageAndABusyCardIsLeftToItsWork() = runTest(StandardTestDispatcher()) {
        val (harness, row) = shown(changedCard(CPr1, KeyA))
        // A card of the message is open (or Raft's confirmation on it).
        row.presence.engaged(true)
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(5_000)
        assertEquals(1, harness.api.cardReads.size, "it waits while the person is on the message")
        row.presence.engaged(false)
        runCurrent()
        assertEquals(2, harness.api.cardReads.size)

        // Its buttons are at work: that work reads the card again when it ends.
        row.presence.busy(setOf(CPr1))
        after(10_000)
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(30_000)
        assertEquals(2, harness.api.cardReads.size)
    }

    @Test
    fun aRateLimitedRereadKeepsTheCardAndGoesAgainAfterRetryAfter() = runTest(StandardTestDispatcher()) {
        val (harness, row) = shown(changedCard(CPr1, KeyA))
        harness.api.cardReadResponses += changedAnswer(changedLimited(CPr1, 2))
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, KeyA, title = "Merged"))
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(1_000)
        assertEquals(2, harness.api.cardReads.size)
        assertEquals("Open", row.title(CPr1), "rate_limited keeps the card")
        after(1_999)
        assertEquals(2, harness.api.cardReads.size, "never sooner than retry_after")
        after(1)
        assertEquals(3, harness.api.cardReads.size)
        assertEquals("Merged", row.title(CPr1))
        assertEquals(1, row.batch.view.rereads, "one of the view's rate-limited re-reads")
    }

    /**
     * The report on 1.27.0: a card still said "waiting for approval" long after the request was done,
     * and Refresh changed nothing however often it was pressed. The row had been composed again since
     * the message was read (scrolled away, or the conversation left and reopened), so its view was a
     * new one, and the answer to its Refresh was applied only to an answer read by that same view.
     */
    @Test
    fun aRefreshOnARowShownAgainReplacesTheCardAlthoughItsRevisionsAreTheSame() = runTest(StandardTestDispatcher()) {
        // No object key: an App that never tells Raft of changes, so only Refresh can update the card.
        val (harness, first) = shown(changedCard(CPr1, null, title = "Waiting for approval"))
        first.leave()
        // The same link posted in another message: its own card, read on its own.
        val other = harness.Row(CMessageB, "Again $CPr1")
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Waiting for approval"))
        other.read()
        runCurrent()
        other.leave()

        // Both rows come back: new views, the same answers. The App now answers the same card_revision
        // and business_revision with other content.
        val back = harness.Row()
        val otherBack = harness.Row(CMessageB, "Again $CPr1")
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Completed"))
        assertEquals(true, harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, back.batch, CPr1))
        assertEquals(listOf(CPr1), harness.api.cardReads.last().urls)
        assertEquals("Completed", back.title(CPr1), "the answer replaces the card")
        assertEquals("Waiting for approval", otherBack.title(CPr1), "the other message's card is its own (as on the web)")
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Completed"))
        assertEquals(true, harness.store.rereadLink(CViewer, CServer, CMessageB, "Again $CPr1", otherBack.batch, CPr1))
        assertEquals("Completed", otherBack.title(CPr1))
        // The next report can be read from a log: each press says what became of its answer, and names no link.
        val presses = harness.diagnostics.filter { "app_link_card_reread" in it && "why=press" in it }
        assertEquals(2, presses.size)
        assertEquals(true, presses.all { "result=applied" in it })
        assertEquals(false, harness.diagnostics.any { "https://" in it || "stamp.example" in it })
    }

    @Test
    fun aRefreshRaftAnswersWithNoCardShowsTheAuthorsLinkAndAFailedOneKeepsTheCard() = runTest(StandardTestDispatcher()) {
        val (harness, row) = shown(changedCard(CPr1, KeyA), changedCard(CPr2, KeyB))
        // Failed or rate_limited: false (the press says so in a toast), and the card stays.
        harness.api.cardReadResponses += HomeNetworkResponse(500, null)
        assertEquals(false, harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1))
        harness.api.cardReadResponses += changedAnswer(changedLimited(CPr1, 5))
        assertEquals(false, harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1))
        assertEquals("Open", row.title(CPr1))
        // No item for the link: it is no longer previewed (web `reread`), never an old card kept in silence.
        harness.api.cardReadResponses += changedAnswer()
        assertEquals(true, harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1))
        assertEquals(null, row.title(CPr1))
        assertEquals("Open", row.title(CPr2), "only the link that was read again")
        assertEquals(
            listOf("result=failed", "result=kept reason=rate_limited", "result=applied"),
            harness.diagnostics.filter { "app_link_card_reread" in it }.map { line -> line.substringAfter("links=1 ").substringBefore(" cards=") },
            "each press leaves its outcome in the diagnostics",
        )
        // An answer with nowhere to go (no answer of that message is held) is a failure too, never a silent "read".
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, KeyA))
        val unread = harness.Row(CMessageC, "See $CPr1")
        assertEquals(false, harness.store.rereadLink(CViewer, CServer, CMessageC, "See $CPr1", unread.batch, CPr1))

        // A nudge's re-read with no card keeps the card: only the person's own Refresh shows it gone.
        harness.api.cardReadResponses += changedAnswer()
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyB))
        after(1_000)
        assertEquals(6, harness.api.cardReads.size)
        assertEquals("Open", row.title(CPr2))
    }

    /** Two quick presses: the first read's place is taken by the second, whose answer is what shows. Neither says it failed. */
    @Test
    fun aRefreshSupersededByANewerOneReportsThatOnesOutcome() = runTest(StandardTestDispatcher()) {
        val (harness, row) = shown(changedCard(CPr1, null, title = "Waiting for approval"))
        val gate = CompletableDeferred<Unit>()
        harness.api.readGate = gate
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Dropped"))
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Completed"))
        val first = async { harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1) }
        runCurrent()
        val second = async { harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1) }
        runCurrent()
        harness.api.readGate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, true), listOf(first.await(), second.await()), "no failure toast while the card updates")
        assertEquals("Completed", row.title(CPr1))
        assertEquals(3, harness.api.cardReads.size, "the superseded read is not sent again")

        // The newer read fails: its press says so, and the superseded one is sent again (web `readSome`).
        val again = CompletableDeferred<Unit>()
        harness.api.readGate = again
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Dropped"))
        harness.api.cardReadResponses += HomeNetworkResponse(500, null)
        harness.api.cardReadResponses += changedAnswer(changedCard(CPr1, null, title = "Reopened"))
        val third = async { harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1) }
        runCurrent()
        val fourth = async { harness.store.rereadLink(CViewer, CServer, CMessageA, CContent, row.batch, CPr1) }
        runCurrent()
        harness.api.readGate = null
        again.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false), listOf(third.await(), fourth.await()))
        assertEquals("Reopened", row.title(CPr1))
        assertEquals(6, harness.api.cardReads.size)
    }

    /**
     * A server without the batched read answers the GET, whose `objectKeys` carries the digests (slock
     * shared `appLinkPreviewListResponseSchema`: preview `url` -> `object_key_sha256`): those cards are
     * read again too, with the GET.
     */
    @Test
    fun aCardReadWithTheGetIsReadAgainByItsObjectKey() = runTest(StandardTestDispatcher()) {
        fun getAnswer(title: String) =
            HomeNetworkResponse(
                200,
                """{"previews":[{"version":1,"title":"$title","url":"$CPr1","appName":"Stamp"}],"prompts":[],"appIcons":{"$CPr1":null},"objectKeys":{"$CPr1":"$KeyA"}}""",
            )
        val harness = Harness(this)
        harness.api.cardReadResponses += HomeNetworkResponse(404, null)
        harness.api.readResponses += getAnswer("Open")
        val row = harness.Row(CMessageA, "See $CPr1")
        row.read()
        row.show()
        runCurrent()
        assertEquals(listOf(CMessageA), harness.api.reads)
        assertEquals("Open", row.title(CPr1))

        harness.api.readResponses += getAnswer("Merged")
        harness.nudges.emit(RaftLinkCardChangedHint(CServer, KeyA))
        after(999)
        assertEquals(1, harness.api.reads.size)
        after(1)
        assertEquals(2, harness.api.reads.size, "read again with the GET")
        assertEquals(1, harness.api.cardReads.size, "the batched read is not tried again")
        assertEquals("Merged", row.title(CPr1))
    }
}
