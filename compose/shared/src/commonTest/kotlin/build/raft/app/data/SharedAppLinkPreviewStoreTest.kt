package build.raft.app.data

import build.raft.app.links.raftAppLinkReadUrls
import build.raft.app.links.raftMessageBareLinks
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

internal class FakeAppLinkPreviewApi : RaftAppLinkPreviewApi {
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

    /** The batched reads' bodies, in order. */
    val cardReads = mutableListOf<AppLinkCardReadRequestBody>()
    val cardReadResponses = ArrayDeque<HomeNetworkResponse>()

    /** The dispatcher each batched read was sent from, in order. */
    val cardReadDispatchers = mutableListOf<kotlin.coroutines.ContinuationInterceptor?>()

    override suspend fun readLinkCards(messageId: String, serverId: String, body: AppLinkCardReadRequestBody): HomeNetworkResponse {
        cardReads += body
        cardReadDispatchers += kotlin.coroutines.coroutineContext[kotlin.coroutines.ContinuationInterceptor]
        readGate?.await()
        return cardReadResponses.removeFirstOrNull() ?: HomeNetworkResponse(200, """{"items":[],"prompts":[]}""")
    }

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse {
        answers += "allow:$appId"
        return allowResponse
    }

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse {
        answers += "decline:$appId"
        return declineResponse
    }

    val prepareRequests = mutableListOf<AppLinkPrepareRequestBody>()
    val executeRequests = mutableListOf<AppLinkExecuteRequestBody>()
    var prepareResponse =
        HomeNetworkResponse(
            200,
            """{"prepared_action_id":"6b0a3c1e-2f4d-4a8b-9c7e-1d2e3f4a5b6c","prepared_digest":"${"a".repeat(64)}","description":{"title":"Confirm","confirm_label":"Save","cancel_label":"Cancel"}}""",
        )
    var executeResponse = HomeNetworkResponse(200, """{"operation_id":"op-1","state":"done"}""")

    override suspend fun prepareLinkCardAction(serverId: String, body: AppLinkPrepareRequestBody): HomeNetworkResponse {
        prepareRequests += body
        return prepareResponse
    }

    override suspend fun executeLinkCardAction(serverId: String, body: AppLinkExecuteRequestBody): HomeNetworkResponse {
        executeRequests += body
        return executeResponse
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppLinkPreviewStoreTest {
    private fun TestScope.store(api: FakeAppLinkPreviewApi) =
        SharedAppLinkPreviewStore(api = api, scope = backgroundScope)

    private fun SharedAppLinkPreviewStore.view(messageId: String = MessageA, content: String = Content) =
        state.value.messageView(Viewer, messageId, content, Conversation)

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

    /**
     * The server's v1 read leaves Artifact links out before it takes its first three
     * (botiverse/slock `appLinkPreviewService.ts` `appLinkReadUrls`): three Artifact links, then a
     * Linear link. The server reads (and asks about) Linear; so must the App.
     */
    @Test
    fun artifactLinksNeverTakeOneOfTheFirstThree() = runTest(StandardTestDispatcher()) {
        val linear = "https://linear.app/raft/issue/RAFT-812"
        val content =
            "https://app.raft.build/artifacts/pt_a https://artifacts.raft.build/pt_b " +
                "https://a-pt-0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11.raftusercontent.com/index.html $linear"
        val prompt =
            """{"previews":[],"prompts":[{"url":"$linear","app":{"id":"client-linear","name":"Linear","icon":null}}],"appIcons":{}}"""
        val api = FakeAppLinkPreviewApi().apply { readResponses += HomeNetworkResponse(200, prompt) }
        val store = SharedAppLinkPreviewStore(api = api, scope = backgroundScope, artifactHosts = { listOf("app.raft.build") })
        assertEquals(listOf(linear), raftAppLinkReadUrls(content, listOf("app.raft.build"), 3))
        store.read(Viewer, Server, MessageA, content, Conversation)
        runCurrent()
        val view = store.state.value.messageView(Viewer, MessageA, content, Conversation, listOf("app.raft.build"))
        assertTrue(view.results[linear] is AppLinkResult.Consent, "the prompt is kept: results=${view.results}")
        // Before this fix the App counted the Artifact links and dropped Linear's prompt.
        assertTrue(raftMessageBareLinks(content).filter { appLinkPreviewHref(it) != null }.take(3).none { it == linear })
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

    @Test
    fun diskSanitizerStripsPromptsTicketsHandlesConnectAccountAndRoundTripsBatchedV3ColdStart() = runTest(StandardTestDispatcher()) {
        val cardTree =
            """{"root":"root","elements":{""" +
                """"root":{"type":"Stack","props":{},"children":["state"]},""" +
                """"state":{"type":"Badge","props":{"text":"In review","tone":"review"}}}}"""
        val v3CardItem =
            """{"kind":"v3","url":"https://alpha.example.test/pr/1","app":{"id":"a1","name":"Alpha","icon":null},""" +
                """"preview":{"version":3,"items":[""" +
                """{"type":"inline","title":"PR 1 Chip"},""" +
                """{"type":"card","placement":"hover","title":"PR 1: Fix sign-in","card_revision":"cr-1","business_revision":"br-1","card":$cardTree}""" +
                """]},"ticket":"secret-ticket","actions":[{"action_id":"approve_pr","handle":"signed-handle-1"}]}"""
        val v3ConnectItem =
            """{"kind":"v3","url":"https://alpha.example.test/pr/2","app":{"id":"a1","name":"Alpha","icon":null},""" +
                """"preview":{"version":3,"items":[{"type":"connect_account","connect_url":"https://alpha.example.test/connect"}]},""" +
                """"ticket":"connect-ticket","actions":[]}"""
        val rawV3Batch =
            """{"items":[$v3CardItem,$v3ConnectItem,""" +
                """{"kind":"rate_limited","url":"https://alpha.example.test/pr/3","retry_after":30}""" +
                """],"prompts":[{"url":"https://alpha.example.test/pr/4","app":{"id":"a2","name":"Beta","icon":null}}]}"""
        val liveParsed = parseAppLinkCardReadAnswer(rawV3Batch)
        assertTrue(liveParsed != null)
        val liveCard1 = liveParsed.results["https://alpha.example.test/pr/1"] as AppLinkResult.Card
        assertEquals(mapOf("approve_pr" to "signed-handle-1"), liveCard1.handles)
        val liveConnect = liveParsed.results["https://alpha.example.test/pr/2"] as AppLinkResult.Card
        assertEquals("https://alpha.example.test/connect", liveConnect.connectUrl)

        val sanitizedV3 = sanitizeAppLinkPreviewBodyForDisk(PERSISTED_APP_LINK_KIND_BATCH, rawV3Batch)
        assertTrue(sanitizedV3 != null)
        assertFalse("secret-ticket" in sanitizedV3)
        assertFalse("signed-handle-1" in sanitizedV3)
        assertFalse("connect_account" in sanitizedV3)
        assertFalse("rate_limited" in sanitizedV3)
        assertFalse("prompts" in sanitizedV3)
        val reparsed = parseAppLinkCardReadAnswer(sanitizedV3)
        assertTrue(reparsed != null)
        assertEquals(setOf("https://alpha.example.test/pr/1"), reparsed.results.keys)
        val reparsedCard = reparsed.results.values.single() as AppLinkResult.Card
        assertEquals("PR 1: Fix sign-in", reparsedCard.title)
        assertTrue(reparsedCard.handles.isEmpty())
        assertNull(reparsedCard.connectUrl)

        assertNull(sanitizeAppLinkPreviewBodyForDisk(PERSISTED_APP_LINK_KIND_GET, PromptBody))

        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { "https://api.example" }, scope = backgroundScope)
        val resource = offline.resource(AppLinkPreviewResourceSpec)

        val batchContent = "Check https://alpha.example.test/pr/1 and https://alpha.example.test/pr/2"
        val batchOnlyValid = """{"items":[$v3CardItem,$v3ConnectItem],"prompts":[]}"""
        val api1 = FakeAppLinkPreviewApi().apply { cardReadResponses += HomeNetworkResponse(200, batchOnlyValid) }
        val store1 =
            SharedAppLinkPreviewStore(
                api = api1,
                scope = backgroundScope,
                offlineResource = resource,
                currentAccountId = { "user-1" },
            )
        val batch1 = AppLinkBatchRead(AppLinkCardView.create(), locale = "en")
        store1.read(Viewer, Server, MessageA, batchContent, Conversation, batch = batch1)
        runCurrent()
        assertEquals(2, store1.state.value.messageView(Viewer, MessageA, batchContent, Conversation, mode = batch1.mode, viewId = batch1.view.id).results.size)

        // Cold start: disk cache hydrates display-only card immediately (no handles, no readAtMillis, no connect_account, staleFromDisk=true, wantsRead=true).
        val api2 = FakeAppLinkPreviewApi().apply {
            readGate = CompletableDeferred()
            cardReadResponses += HomeNetworkResponse(200, """{"items":[],"prompts":[]}""")
        }
        val store2 =
            SharedAppLinkPreviewStore(
                api = api2,
                scope = backgroundScope,
                offlineResource = resource,
                currentAccountId = { "user-1" },
            )
        val batch2 = AppLinkBatchRead(AppLinkCardView.create(), locale = "en")
        store2.read(Viewer, Server, MessageA, batchContent, Conversation, batch = batch2)
        runCurrent()
        val hydratedScope = appLinkReadScope(Viewer, MessageA, batchContent, batch2.mode)
        val hydratedLoaded = store2.state.value.entries[hydratedScope]
        assertTrue(hydratedLoaded != null && hydratedLoaded.staleFromDisk, "hydrated entry must be marked staleFromDisk")
        val hydratedView = store2.state.value.messageView(Viewer, MessageA, batchContent, Conversation, mode = batch2.mode, viewId = batch2.view.id)
        assertTrue(hydratedView.wantsRead, "staleFromDisk view must still request live network revalidation")
        assertEquals(setOf("https://alpha.example.test/pr/1"), hydratedView.results.keys)
        val hydratedCard = hydratedView.results.values.single() as AppLinkResult.Card
        assertTrue(hydratedCard.handles.isEmpty(), "disk-hydrated card must not carry signed handles")
        assertNull(hydratedCard.readAtMillis, "disk-hydrated card must not carry live readAtMillis")

        // Network revalidation returns empty (revoked/no cards) -> evicts persisted disk row.
        api2.readGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, api2.cardReads.size)
        val diskKey = resource.key("user-1", Server, "$MessageA:${batchContent.length}:${batchContent.hashCode()}:${batch2.mode}")!!
        assertNull(resource.read(diskKey), "empty revalidation must evict stale disk entry")

        // dropServer clears that server's in-memory entries without touching another server.
        val api3 = FakeAppLinkPreviewApi().apply {
            readResponses += HomeNetworkResponse(200, CardsBody)
            readResponses += HomeNetworkResponse(200, CardsBody)
        }
        val store3 = SharedAppLinkPreviewStore(api = api3, scope = backgroundScope)
        store3.read("user-1:server-1", "server-1", MessageA, Content, Conversation)
        store3.read("user-1:server-2", "server-2", MessageA, Content, Conversation)
        runCurrent()
        assertEquals(2, store3.state.value.entries.size)
        store3.dropServer("server-1")
        assertEquals(1, store3.state.value.entries.size)
        assertEquals("server-2", store3.state.value.entries.values.single().serverId)
    }
}
