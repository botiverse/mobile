package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        assertEquals(listOf("card.v2", "inline.v1", "inline.tone.v2", "button.v1", "confirm.v1", "undo.v1", "block.v1", "preview.v3", "unreviewed_app.v1"), body.capabilities)
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
    fun rereadsStopWhenTheRowLeavesAndAnotherViewReadsTheWholeMessage() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { cardReadResponses += answer(card(Pr1), limited(Pr2, 1)) }
        val store = store(api)
        val batch = AppLinkBatchRead(AppLinkCardView.create(), "en")
        store.read(BViewer, BServer, BMessage, BContent, BConversation, batch)
        runCurrent()
        val job = store.keepReadingLimited(BViewer, BServer, BMessage, BContent, batch)
        job.cancel()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, api.cardReads.size, "no re-read off screen")
        // Mounted again: a new view, and a link still rate_limited means the whole message is read again.
        val again = AppLinkBatchRead(AppLinkCardView.create(), "en")
        assertTrue(store.view(again).wantsRead)
        store.read(BViewer, BServer, BMessage, BContent, BConversation, again)
        runCurrent()
        assertEquals(2, api.cardReads.size)
        assertEquals(again.view.id, api.cardReads[1].viewInstanceId)
        assertNull(api.cardReads[1].urls)
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
}
