package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CViewer = "user-alice:server-1"
private const val CServer = "server-1"
private const val CMessage = "44444444-4444-4444-4444-444444444444"

private fun handles(ticket: String) =
    """[{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"$ticket","handle":"h-approve"},""" +
        """{"element_id":"deny","action_id":"deny","url":"$MessageCardActingPage","ticket":"$ticket","handle":"h-deny"}]"""

/**
 * A card message's acting buttons in the store (RFC 086 §4.2; botiverse/slock #14723
 * `messageCardActions.api.test.ts` "consent, one rule for both kinds of card"): the read declares
 * `card.execute.v1`, keeps the answer per viewer and message and the tickets per fallback page; a
 * viewer who never agreed is asked, allows (the App's epoch moves, so the card reads again) and
 * then gets the handles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppLinkPreviewStoreMessageCardTest {
    private fun TestScope.store(api: FakeAppLinkPreviewApi) = SharedAppLinkPreviewStore(api = api, scope = backgroundScope, retryJitterMs = { 0L })

    private fun SharedAppLinkPreviewStore.live() = state.value.messageCards[appLinkMessageCardKey(CViewer, CMessage)]

    @Test
    fun consentNeededThenAllowedReadsAgainAndGetsTheHandlesWithTheViewsTicket() = runTest(StandardTestDispatcher()) {
        val api =
            FakeAppLinkPreviewApi().apply {
                cardReadResponses +=
                    HomeNetworkResponse(
                        200,
                        messageCardReadBody(
                            """{"card_revision":"1","actions":[],"consent":"needed","app":$MessageCardActingApp}""",
                            prompts = """[{"url":"$MessageCardActingPage","app":$MessageCardActingApp}]""",
                        ),
                    )
                cardReadResponses += HomeNetworkResponse(200, messageCardReadBody("""{"card_revision":"1","app":$MessageCardActingApp,"actions":${handles("t-1")}}"""))
                cardReadResponses += HomeNetworkResponse(200, messageCardReadBody("""{"card_revision":"1","app":$MessageCardActingApp,"actions":${handles("t-2")}}"""))
            }
        val store = store(api)
        val view = AppLinkCardView.create()
        assertTrue(store.readMessageCard(CViewer, CServer, CMessage, view, "en"))
        val first = api.cardReads.single()
        assertEquals(view.id, first.viewInstanceId)
        assertTrue("card.execute.v1" in first.capabilities, "the read declares card.execute.v1")
        assertTrue("unreviewed_app.v1" in first.capabilities, "own-Server Apps need unreviewed_app.v1 too")
        assertNull(first.tickets)
        assertEquals(RaftMessageCardConsent.Needed, store.live()?.consent)
        val app = AppLinkPromptApp("client-vault", "Vault", null)
        assertEquals(app, store.live()?.prompt?.app)

        // Allow (the link card prompt's own call): the App's epoch moves, which is what makes the row read again.
        val epochBefore = store.state.value.epoch[appLinkAppKey(CViewer, app.id)]
        assertEquals(AppLinkConsentAnswer.Allowed, store.allow(CViewer, CServer, app).also { runCurrent() }.await())
        assertEquals(listOf("allow:client-vault"), api.answers)
        assertTrue(store.state.value.epoch[appLinkAppKey(CViewer, app.id)] != epochBefore)

        assertTrue(store.readMessageCard(CViewer, CServer, CMessage, view, "en"))
        assertEquals(listOf("h-approve", "h-deny"), store.live()?.handles?.map { it.handle })
        assertNull(store.live()?.consent)
        // The view presents the ticket Raft gave for the fallback page on its next read.
        assertTrue(store.readMessageCard(CViewer, CServer, CMessage, view, "en"))
        assertEquals(mapOf(MessageCardActingPage to "t-1"), api.cardReads[2].tickets)
        assertEquals("t-2", view.tickets[MessageCardActingPage])
    }

    @Test
    fun aDeclinedAppIsSaidAndAFailedReadKeepsWhatIsShown() = runTest(StandardTestDispatcher()) {
        val api =
            FakeAppLinkPreviewApi().apply {
                cardReadResponses += HomeNetworkResponse(200, messageCardReadBody("""{"card_revision":"1","actions":[],"consent":"declined","app":$MessageCardActingApp}"""))
                cardReadResponses += HomeNetworkResponse(500, null)
                // An older server reads the card message as a link's: no message_card.
                cardReadResponses += HomeNetworkResponse(200, """{"items":[],"prompts":[]}""")
            }
        val store = store(api)
        val view = AppLinkCardView.create()
        assertTrue(store.readMessageCard(CViewer, CServer, CMessage, view, "en"))
        assertEquals(RaftMessageCardConsent.Declined, store.live()?.consent)
        assertTrue(store.live()?.handles.orEmpty().isEmpty())
        assertFalse(store.readMessageCard(CViewer, CServer, CMessage, view, "en"))
        assertFalse(store.readMessageCard(CViewer, CServer, CMessage, view, "en"))
        assertEquals(RaftMessageCardConsent.Declined, store.live()?.consent)
    }
}
