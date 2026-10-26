package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Viewer = "user-1:server-1"
private const val Server = "server-1"
private const val Conversation = "channel-1"
private const val MessageA = "11111111-1111-1111-1111-111111111111"
private const val MessageB = "22222222-2222-2222-2222-222222222222"
private const val Content = "Look at https://alpha.example.test/pr/1 and https://alpha.example.test/pr/2"

private val alpha = AppLinkPromptApp(id = "client-alpha", name = "prompt-alpha", icon = null)

private const val PromptBody =
    """{"previews":[],"prompts":[{"url":"https://alpha.example.test/pr/1","app":{"id":"client-alpha","name":"prompt-alpha","icon":null}}],"appIcons":{}}"""
private const val CardsBody =
    """{"previews":[{"version":1,"title":"Private PR","url":"https://alpha.example.test/pr/1","appName":"prompt-alpha"},""" +
        """{"version":1,"title":"Other PR","url":"https://alpha.example.test/pr/2","appName":"prompt-alpha"}],"prompts":[],"appIcons":{}}"""

/** The web's `statusCard` (botiverse/slock 3652d3703 `packages/web/tests/appLinkChips.behavior.test.tsx`). */
private const val StatusCardBody =
    """{"previews":[{"version":1,"url":"https://alpha.example.test/pr/1","appName":"Sample App","title":"PR 123: Fix sign-in",""" +
        """"card":{"root":"stack","elements":{"stack":{"type":"Stack","props":{},"children":["state","detail"]},""" +
        """"state":{"type":"Badge","props":{"text":"Approved"}},"detail":{"type":"Text","props":{"text":"Ready for review","tone":"muted"}}}}}],""" +
        """"prompts":[],"appIcons":{"https://alpha.example.test/pr/1":null}}"""

private class FakeAppLinkPreviewApi : RaftAppLinkPreviewApi {
    val reads = mutableListOf<String>()
    val readResponses = ArrayDeque<HomeNetworkResponse>()
    var allowResponse = HomeNetworkResponse(200, "{}")
    var declineResponse = HomeNetworkResponse(200, "{}")
    val answers = mutableListOf<String>()
    /** When set, a read waits for it: the read is on its way meanwhile. */
    var readGate: CompletableDeferred<Unit>? = null

    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse {
        reads += messageId
        readGate?.await()
        return readResponses.removeFirstOrNull() ?: HomeNetworkResponse(200, """{"previews":[],"prompts":[]}""")
    }

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse {
        answers += "allow:$appId"
        return allowResponse
    }

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse {
        answers += "decline:$appId"
        return declineResponse
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppLinkPreviewStoreTest {
    private fun TestScope.store(api: FakeAppLinkPreviewApi) =
        SharedAppLinkPreviewStore(api = api, scope = backgroundScope)

    private fun SharedAppLinkPreviewStore.view(messageId: String = MessageA, content: String = Content) =
        state.value.messageView(Viewer, messageId, content, Conversation)

    @Test
    fun aMessageIsReadOnceAndItsAnswerIsKept() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, CardsBody) }
        val store = store(api)
        assertTrue(store.view().wantsRead)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        assertEquals(listOf(MessageA), api.reads)
        val view = store.view()
        assertFalse(view.wantsRead)
        assertEquals("Private PR", (view.results["https://alpha.example.test/pr/1"] as AppLinkResult.Card).title)
    }

    @Test
    fun aMessageWithoutAnHttpsLinkIsNeverRead() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi()
        val store = store(api)
        store.read(Viewer, Server, MessageA, "see http://example.com and #general", Conversation)
        runCurrent()
        assertTrue(api.reads.isEmpty())
    }

    @Test
    fun anAppIsAskedOnceInAConversation() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply {
            readResponses += HomeNetworkResponse(200, PromptBody)
            readResponses += HomeNetworkResponse(200, PromptBody)
        }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        store.read(Viewer, Server, MessageB, Content, Conversation)
        runCurrent()
        assertTrue(store.view(MessageA).results["https://alpha.example.test/pr/1"] is AppLinkResult.Consent)
        // The second message keeps the author's link.
        assertTrue(store.view(MessageB).results.isEmpty())
        // Another conversation asks again.
        assertTrue(store.state.value.messageView(Viewer, MessageB, Content, "channel-2").results.isEmpty())
    }

    @Test
    fun aPromptOnALinkTheAuthorGaveWordsToIsDropped() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, PromptBody) }
        val store = store(api)
        val worded = "[the PR](https://alpha.example.test/pr/1)"
        store.read(Viewer, Server, MessageA, worded, Conversation)
        runCurrent()
        assertTrue(store.view(content = worded).results.isEmpty())
        assertTrue(store.state.value.claims.isEmpty())
    }

    @Test
    fun allowShowsPendingChipsThenReadsAgain() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply {
            readResponses += HomeNetworkResponse(200, PromptBody)
            readResponses += HomeNetworkResponse(200, CardsBody)
        }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        assertEquals(AppLinkConsentAnswer.Allowed, store.allow(Viewer, Server, alpha).await())
        assertEquals(listOf("allow:client-alpha"), api.answers)
        val waiting = store.view()
        assertTrue(waiting.wantsRead)
        // The App's bare links wait as pending chips while they are read again.
        assertEquals(
            listOf(AppLinkResult.Pending("https://alpha.example.test/pr/1"), AppLinkResult.Pending("https://alpha.example.test/pr/2")),
            waiting.results.values.toList(),
        )
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        assertEquals(listOf(MessageA, MessageA), api.reads)
        val read = store.view()
        assertFalse(read.wantsRead)
        assertEquals(listOf("Private PR", "Other PR"), read.results.values.map { (it as AppLinkResult.Card).title })
    }

    @Test
    fun aFailedReadAgainAfterAllowLeavesThePlainLinks() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply {
            readResponses += HomeNetworkResponse(200, PromptBody)
            readResponses += HomeNetworkResponse(503, null)
        }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        store.allow(Viewer, Server, alpha).await()
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        val view = store.view()
        assertTrue(view.results.isEmpty())
        assertFalse(view.wantsRead)
    }

    @Test
    fun declineHidesThePromptAndAFailedDeclinePutsItBack() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, PromptBody) }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        api.declineResponse = HomeNetworkResponse(500, null)
        assertEquals(AppLinkConsentAnswer.Failed, store.decline(Viewer, Server, alpha).await())
        assertTrue(store.view().results.values.single() is AppLinkResult.Consent)
        api.declineResponse = HomeNetworkResponse(200, "{}")
        assertEquals(AppLinkConsentAnswer.Declined, store.decline(Viewer, Server, alpha).await())
        assertTrue(store.view().results.isEmpty())
        assertFalse(store.view().wantsRead)
    }

    @Test
    fun aFailedAllowKeepsTheQuestionAndAnUnavailableAppStopsAsking() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, PromptBody) }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        api.allowResponse = HomeNetworkResponse(502, null)
        assertEquals(AppLinkConsentAnswer.Failed, store.allow(Viewer, Server, alpha).await())
        assertEquals(AppLinkConsentStatus.Failed, store.state.value.status[appLinkAppKey(Viewer, alpha.id)])
        assertTrue(store.view().results.values.single() is AppLinkResult.Consent)
        api.allowResponse = HomeNetworkResponse(409, null)
        assertEquals(AppLinkConsentAnswer.Unavailable, store.allow(Viewer, Server, alpha).await())
        assertTrue(store.view().results.isEmpty())
    }

    @Test
    fun answersAndReadsBelongToOneViewerOnOneServer() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, CardsBody) }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        assertTrue(store.state.value.messageView("user-1:server-2", MessageA, Content, Conversation).wantsRead)
        // An edited message is another read.
        assertTrue(store.view(content = "$Content edited").wantsRead)
    }

    @Test
    fun aMessagesCardReachesItsRowThroughTheSelector() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, StatusCardBody) }
        val store = store(api)
        val seen = mutableListOf<AppLinkMessageView>()
        backgroundScope.launch { store.messageViewFlow(Viewer, MessageA, Content, Conversation).toList(seen) }
        runCurrent()
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        // Another message's answer doesn't reach this row's selector.
        store.read(Viewer, Server, MessageB, Content, Conversation)
        runCurrent()
        assertEquals(listOf(true, false), seen.map { it.wantsRead })
        val card = seen.last().cards.single()
        assertEquals("https://alpha.example.test/pr/1", card.url)
        assertEquals(AppLinkApp("Sample App", null), card.app)
        assertEquals(RaftAppLinkCardElement.Badge("Approved"), card.card!!.element("state"))
        assertEquals(RaftAppLinkTextTone.Muted, (card.card!!.element("detail") as RaftAppLinkCardElement.Text).tone)
    }

    @Test
    fun signingOutDropsEveryAnswerAndAReadOnItsWayIsNotKept() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, CardsBody) }
        val store = store(api)
        store.read(Viewer, Server, MessageA, Content, Conversation)
        runCurrent()
        assertEquals(2, store.view().results.size)
        store.clearAll()
        assertTrue(store.state.value.entries.isEmpty())
        assertTrue(store.view().wantsRead)

        api.readGate = CompletableDeferred()
        api.readResponses += HomeNetworkResponse(200, CardsBody)
        store.read(Viewer, Server, MessageB, Content, Conversation)
        runCurrent()
        store.clearAll()
        api.readGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(MessageA, MessageB), api.reads)
        assertNull(store.state.value.entries.values.firstOrNull())
        assertTrue(store.messageViewFlow(Viewer, MessageB, Content, Conversation).first().wantsRead)
    }
}
