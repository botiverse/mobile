package build.raft.app

import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInviteRefusal
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import build.raft.app.data.ScreenError
import build.raft.app.data.SendExternalInvitesResult
import build.raft.app.data.SentExternalInvite
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeCreateExternalChannelDataSource : CreateExternalChannelDataSource {
    var freeLimit: Boolean? = false
    var servers: List<InviteConnectableServer>? = emptyList()
    var previewAnswer: DataResult<ExternalInviteAnswer<ExternalInvitePreview>> =
        DataResult.Content(ExternalInviteAnswer.Ok(ExternalInvitePreview(valid = listOf("new@example.com"))))
    var createAnswer: DataResult<CreateChannelResult> =
        DataResult.Content(CreateChannelResult(HomeChannelRow(threadId = ThreadId("c1"), channelId = ChannelId("c1"), name = "vendors")))
    var sendAnswers =
        ArrayDeque<DataResult<ExternalInviteAnswer<SendExternalInvitesResult>>>()
    var connectAnswer: DataResult<ExternalInviteAnswer<List<ServerConnectOutcome>>> =
        DataResult.Content(ExternalInviteAnswer.Ok(emptyList()))
    /** Holds every send until completed (the send still running). */
    var sendGate: CompletableDeferred<Unit>? = null
    var freeLimitCalls = 0
    var serversCalls = 0
    val previewCalls = mutableListOf<List<String>>()
    val createCalls = mutableListOf<CreateChannelRequest>()
    val sendCalls = mutableListOf<Pair<ChannelId, List<String>>>()
    val connectCalls = mutableListOf<Pair<ChannelId, List<String>>>()

    override suspend fun freeLimitReached() = freeLimit.also { freeLimitCalls++ }

    override suspend fun ownServers() = servers.also { serversCalls++ }

    override suspend fun previewForNewChannel(emails: List<String>) = previewAnswer.also { previewCalls += emails }

    override suspend fun create(request: CreateChannelRequest) = createAnswer.also { createCalls += request }

    override suspend fun send(
        channelId: ChannelId,
        emails: List<String>,
        connectMemberEmails: List<String>,
    ): DataResult<ExternalInviteAnswer<SendExternalInvitesResult>> {
        sendCalls += channelId to emails
        sendGate?.await()
        return sendAnswers.removeFirstOrNull()
            ?: DataResult.Content(ExternalInviteAnswer.Ok(SendExternalInvitesResult(sent = listOf(SentExternalInvite("i1", emails.first())))))
    }

    override suspend fun connect(channelId: ChannelId, serverIds: List<String>) = connectAnswer.also { connectCalls += channelId to serverIds }
}

private val draft = CreateExternalChannelDraft(name = " vendors ", description = " Shared ", agentIds = listOf("a1"), userIds = listOf("u1"))

/**
 * Create Channel's External (web `CreateChannelDialog.tsx` `commitExternal`,
 * `useExternalChannelAvailability`, botiverse/slock staging 97a3e293d71a).
 */
class CreateExternalChannelStoreTest {
    private fun TestScope.started(source: FakeCreateExternalChannelDataSource): CreateExternalChannelStore {
        val store = CreateExternalChannelStore(this, source)
        store.dispatch(CreateExternalChannelIntent.Start)
        runCurrent()
        return store
    }

    @Test
    fun externalIsOfferedToOwnersAndAdmins() {
        assertTrue(externalChannelCreateAccess("owner"))
        assertTrue(externalChannelCreateAccess("admin"))
        assertFalse(externalChannelCreateAccess("member"))
        assertFalse(externalChannelCreateAccess(null))
    }

    @Test
    fun theCreateEntryIsTheExternalChannelFlowWhateverTheRetiredFlagSaid() = runTest {
        // botiverse/slock #15939 retired external_channels_v1 (always on): the app no longer asks for it,
        // so no answer of it can send Home's "+" anywhere but Create Channel with External chosen.
        assertFalse("external_channels_v1" in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        data class Case(val plan: String?, val freeLimit: Boolean?, val entry: ExternalCreateEntry, val reads: Int)
        listOf(
            // Not Free, or the plan not known yet: nothing is read.
            Case("pro", true, ExternalCreateEntry.CreateChannel, reads = 0),
            Case(null, true, ExternalCreateEntry.CreateChannel, reads = 0),
            Case("free", false, ExternalCreateEntry.CreateChannel, reads = 1),
            // A failed read: Create Channel, which reads it again itself.
            Case("free", null, ExternalCreateEntry.CreateChannel, reads = 1),
            // The Free plan's one external channel is taken: the upgrade instead.
            Case("free", true, ExternalCreateEntry.FreeLimit, reads = 1),
        ).forEach { case ->
            val source = FakeCreateExternalChannelDataSource().apply { freeLimit = case.freeLimit }
            assertEquals(case.entry, externalCreateEntry(case.plan, source), "case=$case")
            assertEquals(case.reads, source.freeLimitCalls, "case=$case")
        }
    }

    @Test
    fun theAvailabilityIsReadOnceAndAFailedReadIsOpen() = runTest {
        assertEquals(ExternalChannelCreateAvailability.Loading, CreateExternalChannelStore(this, FakeCreateExternalChannelDataSource()).state.value.availability)
        val taken = FakeCreateExternalChannelDataSource().apply { freeLimit = true }
        val store = started(taken)
        store.dispatch(CreateExternalChannelIntent.Start)
        runCurrent()
        assertEquals(ExternalChannelCreateAvailability.FreeLimit, store.state.value.availability)
        assertEquals(1, taken.freeLimitCalls)
        // Servers wait for External to be chosen.
        assertEquals(0, taken.serversCalls)
        val failed = FakeCreateExternalChannelDataSource().apply { freeLimit = null }
        assertEquals(ExternalChannelCreateAvailability.Open, started(failed).state.value.availability)
    }

    @Test
    fun theReviewCreatesNothingAndOnlyServersNeedNoPreview() = runTest {
        val source = FakeCreateExternalChannelDataSource().apply { servers = listOf(InviteConnectableServer(id = "s2", name = "Beta")) }
        val store = started(source)
        assertFalse(store.state.value.canReview)
        store.dispatch(CreateExternalChannelIntent.LoadServers)
        store.dispatch(CreateExternalChannelIntent.LoadServers)
        runCurrent()
        assertEquals(1, source.serversCalls)
        store.dispatch(CreateExternalChannelIntent.SetServers(setOf("s2")))
        store.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        assertEquals(emptyList(), source.previewCalls)
        assertEquals(ExternalInvitePreview(), store.state.value.invite.preview)
        assertEquals(emptyList(), source.createCalls)

        store.dispatch(CreateExternalChannelIntent.BackToPeople)
        store.dispatch(CreateExternalChannelIntent.SetText("new@example.com"))
        store.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        assertEquals(listOf(listOf("new@example.com")), source.previewCalls)
        assertEquals(emptyList(), source.createCalls)
    }

    @Test
    fun theConfirmCreatesItPrivateThenSendsThenConnects() = runTest {
        val source =
            FakeCreateExternalChannelDataSource().apply {
                servers = listOf(InviteConnectableServer(id = "s2", name = "Beta"))
                connectAnswer = DataResult.Content(ExternalInviteAnswer.Ok(listOf(ServerConnectOutcome.Connected("s2", "c2"))))
            }
        val store = started(source)
        store.dispatch(CreateExternalChannelIntent.LoadServers)
        runCurrent()
        store.dispatch(CreateExternalChannelIntent.SetText("new@example.com"))
        store.dispatch(CreateExternalChannelIntent.SetServers(setOf("s2")))
        store.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        store.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()

        val request = source.createCalls.single()
        assertEquals("vendors", request.name)
        assertEquals("Shared", request.description)
        assertEquals("private", request.visibility)
        assertEquals(listOf("a1"), request.agentMemberIds)
        assertEquals(listOf("u1"), request.userMemberIds)
        assertEquals(listOf(ChannelId("c1") to listOf("new@example.com")), source.sendCalls)
        assertEquals(listOf(ChannelId("c1") to listOf("s2")), source.connectCalls)
        val effects = store.state.value.effects
        assertEquals(
            listOf<CreateExternalChannelEffect>(
                CreateExternalChannelEffect.Toast(ExternalInvitesEffect.Sent(1)),
                CreateExternalChannelEffect.Toast(ExternalInvitesEffect.ServersConnected(1)),
            ),
            effects.dropLast(1),
        )
        assertEquals("c1", assertIs<CreateExternalChannelEffect.Finished>(effects.last()).channel.channel.channelId.value)
    }

    /** slock #15136: "created, but the invites weren't sent" waits for a send that came back unsent. */
    @Test
    fun theCreatedNoticeShowsOnlyOnceASendAfterTheCreateFailed() = runTest {
        val source =
            FakeCreateExternalChannelDataSource().apply {
                sendGate = CompletableDeferred()
                sendAnswers.addLast(DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(429, "daily_cap", "cap"))))
            }
        val store = started(source)
        store.dispatch(CreateExternalChannelIntent.SetText("new@example.com"))
        store.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        store.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()
        // Created, the invites still sending: no notice.
        assertNotNull(store.state.value.created)
        assertTrue(store.state.value.invite.busy)
        assertFalse(store.state.value.showCreatedNotice)

        source.sendGate!!.complete(Unit)
        runCurrent()
        // The send came back unsent and the review stays open: the notice.
        assertEquals(ExternalInviteError.DailyCap, store.state.value.invite.failure?.error)
        assertTrue(store.state.value.showCreatedNotice)

        // The retry: hidden while it sends, and none once it succeeded (the review closes).
        source.sendGate = CompletableDeferred()
        store.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()
        assertTrue(store.state.value.invite.busy)
        assertFalse(store.state.value.showCreatedNotice)
        val seen = mutableListOf<Boolean>()
        val watcher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect { seen += it.showCreatedNotice } }
        runCurrent()
        source.sendGate!!.complete(Unit)
        runCurrent()
        watcher.cancel()
        assertIs<CreateExternalChannelEffect.Finished>(store.state.value.effects.last())
        assertFalse(store.state.value.showCreatedNotice)
        assertEquals(listOf(false), seen.distinct())
    }

    @Test
    fun aSendThatFailsAfterTheCreateKeepsTheChannelAndARetrySendsToIt() = runTest {
        val source =
            FakeCreateExternalChannelDataSource().apply {
                sendAnswers.addLast(DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(429, "daily_cap", "cap"))))
            }
        val store = started(source)
        store.dispatch(CreateExternalChannelIntent.SetText("new@example.com"))
        store.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        store.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()
        assertNotNull(store.state.value.created)
        assertEquals(ExternalInviteError.DailyCap, store.state.value.invite.failure?.error)
        // Back is gone once the channel exists.
        store.dispatch(CreateExternalChannelIntent.BackToPeople)
        assertNotNull(store.state.value.invite.preview)

        store.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()
        assertEquals(1, source.createCalls.size)
        assertEquals(2, source.sendCalls.size)
        assertIs<CreateExternalChannelEffect.Finished>(store.state.value.effects.last())
    }

    @Test
    fun leavingAfterTheCreateGoesToTheChannelAndARefusedCreateGoesBack() = runTest {
        val failing =
            FakeCreateExternalChannelDataSource().apply {
                createAnswer = DataResult.Error(ScreenError(code = "channel_name_taken", retryable = false))
            }
        val store = started(failing)
        store.dispatch(CreateExternalChannelIntent.SetText("new@example.com"))
        store.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        store.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()
        assertNull(store.state.value.invite.preview)
        assertEquals(emptyList(), failing.sendCalls)
        assertIs<CreateExternalChannelEffect.CreateFailed>(store.state.value.effects.single())
        // Nothing made: leaving finishes nothing.
        store.dispatch(CreateExternalChannelIntent.Leave)
        assertEquals(1, store.state.value.effects.size)

        val sendFails =
            FakeCreateExternalChannelDataSource().apply {
                sendAnswers.addLast(DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(500, null, "boom"))))
            }
        val created = started(sendFails)
        created.dispatch(CreateExternalChannelIntent.SetText("new@example.com"))
        created.dispatch(CreateExternalChannelIntent.Review)
        runCurrent()
        created.dispatch(CreateExternalChannelIntent.Commit(draft))
        runCurrent()
        created.dispatch(CreateExternalChannelIntent.Leave)
        assertIs<CreateExternalChannelEffect.Finished>(created.state.value.effects.single())
    }
}
