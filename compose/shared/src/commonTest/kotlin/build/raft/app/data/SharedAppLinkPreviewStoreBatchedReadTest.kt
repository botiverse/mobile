package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BViewer = "user-1:server-1"
private const val BServer = "server-1"
private const val BConversation = "channel-1"
private const val BMessage = "33333333-3333-3333-3333-333333333333"
private const val Pr1 = "https://stamp.example.test/botiverse/slock/pull/1"
private const val Pr2 = "https://stamp.example.test/botiverse/slock/pull/2"
private const val BContent = "See $Pr1 and $Pr2"
private const val Stamp = """{"id":"app-stamp","name":"Stamp","icon":null}"""

/** A v2 card item shaped like the web's `v2Item` (botiverse/slock 8de484350 appLinkCardsBatchedRead.behavior.test.tsx). */
private fun card(url: String, ticket: String? = null) =
    """{"kind":"v2","url":"$url","app":$Stamp,"ticket":${ticket?.let { "\"$it\"" } ?: "null"},"actions":[],
      "preview":{"version":2,"display":"card","title":"PR","card_revision":"cr-1","business_revision":"br-1",
      "card":{"root":"r","elements":{"r":{"type":"Badge","props":{"text":"Open","tone":"done"}}}}}}"""

private fun limited(url: String, retryAfter: Int) = """{"kind":"rate_limited","url":"$url","retry_after":$retryAfter}"""

private fun answer(vararg items: String) = HomeNetworkResponse(200, """{"items":[${items.joinToString(",")}],"prompts":[]}""")

/**
 * The batched link-card read in the store (docs/development/app-link-previews.md "the batched read
 * (v2)"; web `useAppLinkPreviews` / `linkCardRead.ts`): one view id per mounted message, only the
 * latest request's answer, tickets sent back, rate_limited links read again with backoff, and
 * answers kept per mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppLinkPreviewStoreBatchedReadTest {
    private fun TestScope.store(api: FakeAppLinkPreviewApi) =
        SharedAppLinkPreviewStore(api = api, scope = backgroundScope, retryJitterMs = { 0L })

    private fun SharedAppLinkPreviewStore.view(batch: AppLinkBatchRead?) =
        state.value.messageView(BViewer, BMessage, BContent, BConversation, mode = batch?.mode ?: APP_LINK_READ_MODE_V1, viewId = batch?.view?.id)

    @Test
    fun aBatchedReadPostsOnceWithTheViewAndIsKeptPerMode() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { cardReadResponses += answer(card(Pr1), card(Pr2)) }
        val store = store(api)
        val batch = AppLinkBatchRead(AppLinkCardView("6f1c1f86-5a83-4b1e-9b1a-0d2f0e0c0a01"), "en")
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        assertEquals(1, api.cardReads.size)
        assertTrue(api.reads.isEmpty(), "no GET with the flag on")
        val body = api.cardReads.single()
        assertEquals("6f1c1f86-5a83-4b1e-9b1a-0d2f0e0c0a01", body.viewInstanceId)
        assertEquals(listOf("card.v2", "inline.v1", "inline.tone.v2", "button.v1", "confirm.v1", "undo.v1", "block.v1", "preview.v3", "unreviewed_app.v1", "badge.color.v1", "execute.toast.v1", "card.changed.v1"), body.capabilities)
        assertNull(body.urls)
        assertNull(body.tickets)
        val view = store.view(batch)
        assertFalse(view.wantsRead)
        assertTrue((view.results[Pr1] as AppLinkResult.Card).cardV2 != null)
        // Another message view (scrolled back) reuses the answer: no request.
        assertFalse(store.view(AppLinkBatchRead(AppLinkCardView.create(), "en")).wantsRead)
        // The GET's answer is its own: the flag off reads with the GET.
        assertTrue(store.view(null).wantsRead)
    }

    @Test
    fun aServerWithoutTheBatchedReadIsReadWithTheGet() = runTest(StandardTestDispatcher()) {
        val api =
            FakeAppLinkPreviewApi().apply {
                cardReadResponses += HomeNetworkResponse(404, null)
                readResponses += HomeNetworkResponse(200, """{"previews":[{"version":1,"title":"PR 1","url":"$Pr1","appName":"Stamp"}],"prompts":[]}""")
            }
        val store = store(api)
        val batch = AppLinkBatchRead(AppLinkCardView.create(), "en")
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        assertEquals(1, api.cardReads.size)
        assertEquals(listOf(BMessage), api.reads)
        assertEquals("PR 1", (store.view(batch).results[Pr1] as AppLinkResult.Card).title)
    }

    @Test
    fun rateLimitedLinksAreReadAgainAloneWithBackoffAndTickets() = runTest(StandardTestDispatcher()) {
        val api =
            FakeAppLinkPreviewApi().apply {
                cardReadResponses += answer(card(Pr1, ticket = "t-1"), limited(Pr2, 2))
                cardReadResponses += answer(limited(Pr2, 1))
                cardReadResponses += answer(card(Pr2))
            }
        val store = store(api)
        val batch = AppLinkBatchRead(AppLinkCardView.create(), "en")
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        val first = store.view(batch)
        assertTrue(first.readsLimited)
        assertFalse(first.wantsRead)
        assertNull(first.results[Pr2], "a rate_limited link stays plain")
        val job = store.keepReadingLimited(BViewer, BServer, BMessage, BContent, batch)
        // Never sooner than retry_after (2 s).
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, api.cardReads.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, api.cardReads.size)
        assertEquals(listOf(Pr2), api.cardReads[1].urls)
        // Only the re-read links' tickets go back (Pr1's ticket is not one of them).
        assertNull(api.cardReads[1].tickets)
        // Still rate_limited (retry_after 1 s): the next wait doubles the last, 4 s.
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(2, api.cardReads.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, api.cardReads.size)
        val done = store.view(batch)
        assertFalse(done.readsLimited)
        assertTrue(done.results[Pr2] is AppLinkResult.Card)
        assertTrue(done.results[Pr1] is AppLinkResult.Card)
        job.cancel()
        // The view keeps the ticket for its next read of the whole message.
        assertEquals(mapOf(Pr1 to "t-1"), batch.view.tickets)
    }

    @Test
    fun onlyTheViewsLatestRequestIsApplied() = runTest(StandardTestDispatcher()) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api =
            FakeAppLinkPreviewApi().apply {
                cardReadResponses += answer(card(Pr1), limited(Pr2, 1))
            }
        val store = store(api)
        val batch = AppLinkBatchRead(AppLinkCardView.create(), "en")
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        // A re-read goes out and waits; meanwhile the view sends a later request (seq moves on).
        api.readGate = gate
        api.cardReadResponses += answer(card(Pr2))
        val job = store.keepReadingLimited(BViewer, BServer, BMessage, BContent, batch)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, api.cardReads.size)
        batch.view.seq += 1
        gate.complete(Unit)
        runCurrent()
        job.cancel()
        assertNull(store.view(batch).results[Pr2], "the stale answer is dropped")
    }

    @Test
    fun aRefreshSaysWhenTheCardCouldNotBeReadAgain() = runTest(StandardTestDispatcher()) {
        // Web `LinkCardMessageView.reread`: a failed read and a rate_limited link are `failed`; the card stays as it was.
        val api = FakeAppLinkPreviewApi().apply { cardReadResponses += answer(card(Pr1), card(Pr2)) }
        val store = store(api)
        val batch = AppLinkBatchRead(AppLinkCardView.create(), "en")
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        api.cardReadResponses += answer(card(Pr1, ticket = "t-2"))
        assertTrue(store.rereadLink(BViewer, BServer, BMessage, BContent, batch, Pr1), "read again")
        assertEquals(listOf(Pr1), api.cardReads.last().urls)
        assertEquals(mapOf(Pr1 to "t-2"), batch.view.tickets)
        api.cardReadResponses += answer(limited(Pr1, 5))
        assertFalse(store.rereadLink(BViewer, BServer, BMessage, BContent, batch, Pr1), "rate_limited")
        api.cardReadResponses += HomeNetworkResponse(500, null)
        assertFalse(store.rereadLink(BViewer, BServer, BMessage, BContent, batch, Pr1), "the read failed")
        assertEquals(3, api.cardReads.size - 1)
        assertTrue(store.view(batch).results[Pr1] is AppLinkResult.Card, "the card stays as it was")
    }

    @Test
    fun concurrentInitialBatchedReadsCoalesceStateEmissionsWhileSingleReadCommitsImmediately() = runTest(StandardTestDispatcher()) {
        val gatesByMessageId = linkedMapOf<String, CompletableDeferred<Unit>>()
        val responsesByMessageId = linkedMapOf<String, HomeNetworkResponse>()
        val api =
            object : RaftAppLinkPreviewApi {
                override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = error("unused")

                override suspend fun readLinkCards(messageId: String, serverId: String, body: AppLinkCardReadRequestBody): HomeNetworkResponse {
                    gatesByMessageId[messageId]?.await()
                    return responsesByMessageId.getValue(messageId)
                }

                override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = error("unused")
                override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = error("unused")
            }

        val store = SharedAppLinkPreviewStore(api = api, scope = backgroundScope, retryJitterMs = { 0L })
        val emissions = mutableListOf<Int>()
        val collectJob = backgroundScope.launch {
            store.state.collect { emissions += it.entries.size }
        }
        runCurrent()
        assertEquals(listOf(0), emissions)

        // 1. Single-message read commits immediately with 0ms delay.
        val singleId = "33333333-3333-3333-3333-333333333300"
        val singleGate = CompletableDeferred<Unit>()
        gatesByMessageId[singleId] = singleGate
        responsesByMessageId[singleId] = answer(card(Pr1))
        val singleBatch = AppLinkBatchRead(AppLinkCardView.create(), "en")
        store.read(BViewer, BServer, singleId, "See $Pr1", BConversation, singleBatch)
        runCurrent()
        singleGate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1), emissions, "single read must commit immediately without waiting for batch window")

        // 2. Six concurrent DM-open reads arriving staggered collapse into 2 atomic commits instead of 6.
        val msgIds = (1..6).map { "33333333-3333-3333-3333-33333333330$it" }
        msgIds.forEach { id ->
            gatesByMessageId[id] = CompletableDeferred()
            responsesByMessageId[id] = answer(card(Pr1))
            store.read(BViewer, BServer, id, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        }
        runCurrent()
        emissions.clear()

        // First 4 finish staggered within the 120ms micro-batch window while msgs 5..6 remain in flight.
        gatesByMessageId.getValue(msgIds[0]).complete(Unit)
        runCurrent()
        advanceTimeBy(30)
        gatesByMessageId.getValue(msgIds[1]).complete(Unit)
        runCurrent()
        advanceTimeBy(40)
        gatesByMessageId.getValue(msgIds[2]).complete(Unit)
        runCurrent()
        advanceTimeBy(30)
        gatesByMessageId.getValue(msgIds[3]).complete(Unit)
        runCurrent()
        assertTrue(emissions.isEmpty(), "first 4 stay staged while batch window (100ms < 120ms) and siblings remain active")

        // Window cap (120ms from msg 1) flushes msgs 1..4 in ONE atomic state commit.
        advanceTimeBy(20)
        runCurrent()
        assertEquals(listOf(5), emissions, "msgs 1..4 commit together in a single state emission")

        // Msgs 5..6 finish 50ms apart: msg 6 drains activeInitialReads to 0 and flushes both immediately!
        advanceTimeBy(50)
        gatesByMessageId.getValue(msgIds[4]).complete(Unit)
        runCurrent()
        assertEquals(listOf(5), emissions, "msg 5 stages while msg 6 is still in flight")
        advanceTimeBy(50)
        gatesByMessageId.getValue(msgIds[5]).complete(Unit)
        runCurrent()
        assertEquals(listOf(5, 7), emissions, "msg 6 drains in-flight batch and flushes msgs 5..6 immediately")

        collectJob.cancel()
    }

    @Test
    fun stagedCommitsFlushWhenLastSiblingFailsAndDropOnClearAllOrDropServer() = runTest(StandardTestDispatcher()) {
        val gatesByMessageId = linkedMapOf<String, CompletableDeferred<Unit>>()
        val responsesByMessageId = linkedMapOf<String, HomeNetworkResponse>()
        val api =
            object : RaftAppLinkPreviewApi {
                override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = error("unused")

                override suspend fun readLinkCards(messageId: String, serverId: String, body: AppLinkCardReadRequestBody): HomeNetworkResponse {
                    gatesByMessageId[messageId]?.await()
                    return responsesByMessageId.getValue(messageId)
                }

                override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = error("unused")
                override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = error("unused")
            }

        val store = SharedAppLinkPreviewStore(api = api, scope = backgroundScope, retryJitterMs = { 0L })

        // Case 1: Msg A succeeds and stages while Msg B is in flight; Msg B fails (500) -> Msg A flushes immediately.
        val msgA = "33333333-3333-3333-3333-333333333311"
        val msgB = "33333333-3333-3333-3333-333333333312"
        gatesByMessageId[msgA] = CompletableDeferred()
        gatesByMessageId[msgB] = CompletableDeferred()
        responsesByMessageId[msgA] = answer(card(Pr1))
        responsesByMessageId[msgB] = HomeNetworkResponse(500, null)
        store.read(BViewer, BServer, msgA, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        store.read(BViewer, BServer, msgB, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        runCurrent()
        gatesByMessageId.getValue(msgA).complete(Unit)
        runCurrent()
        assertEquals(0, store.state.value.entries.size, "msgA staged while msgB in flight")
        gatesByMessageId.getValue(msgB).complete(Unit)
        runCurrent()
        assertEquals(1, store.state.value.entries.size, "msgA flushes immediately when msgB fails and drains batch")

        // Case 2: dropServer discards staged commits for that server without dropping another server's staged commit.
        val msgS1 = "33333333-3333-3333-3333-333333333321"
        val msgS2 = "33333333-3333-3333-3333-333333333322"
        val msgHold = "33333333-3333-3333-3333-333333333323"
        listOf(msgS1, msgS2, msgHold).forEach { id ->
            gatesByMessageId[id] = CompletableDeferred()
            responsesByMessageId[id] = answer(card(Pr1))
        }
        store.read(BViewer, BServer, msgS1, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        store.read("user-1:server-2", "server-2", msgS2, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        store.read("user-1:server-2", "server-2", msgHold, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        runCurrent()
        gatesByMessageId.getValue(msgS1).complete(Unit)
        gatesByMessageId.getValue(msgS2).complete(Unit)
        runCurrent()
        store.dropServer(BServer)
        advanceTimeBy(APP_LINK_READ_COMMIT_BATCH_WINDOW_MS)
        runCurrent()
        assertEquals(1, store.state.value.entries.size)
        assertEquals("server-2", store.state.value.entries.values.single().serverId)

        // Case 3: clearAll discards staged commits before timer fires.
        val msgC1 = "33333333-3333-3333-3333-333333333331"
        gatesByMessageId[msgC1] = CompletableDeferred()
        responsesByMessageId[msgC1] = answer(card(Pr1))
        store.read(BViewer, BServer, msgC1, "See $Pr1", BConversation, AppLinkBatchRead(AppLinkCardView.create(), "en"))
        runCurrent()
        gatesByMessageId.getValue(msgC1).complete(Unit)
        runCurrent()
        store.clearAll()
        advanceTimeBy(APP_LINK_READ_COMMIT_BATCH_WINDOW_MS)
        runCurrent()
        assertTrue(store.state.value.entries.isEmpty(), "clearAll must drop all staged commits")
        gatesByMessageId.getValue(msgHold).complete(Unit)
        runCurrent()
        assertTrue(store.state.value.entries.isEmpty(), "in-flight read from before clearAll must not repopulate state")
    }
}
