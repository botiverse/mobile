package build.raft.app

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * botiverse/slock #14482 (cindyz #triage f4835750): creating an agent from the Members page or a
 * Computer no longer opens its profile. It stays put with a "Created <name>" toast whose View opens
 * the agent's DM; an external agent still opens its profile (setup guide).
 */
class RaftCreatedAgentReceiptTest {
    private val copy =
        RaftCreatedAgentToastCopy(
            titleTemplate = "Created %1\$s",
            view = "View",
            openFailedTemplate = "Couldn't open your conversation with %1\$s. Try again.",
        )
    private val agent =
        MemberProfile(id = MemberId("agent-reef"), displayName = "Reef", handle = "reef", kind = MemberKind.Agent, serverId = ServerId("server-1"))
    private val dm =
        DataResult.Content(
            OpenDirectMessageResult(
                conversation = HomeDirectMessageRow(threadId = ThreadId("dm-reef"), channelId = ChannelId("dm-reef"), name = "Reef"),
                directMessages = emptyList(),
            )
        )

    private fun submit(external: Boolean = false, hostedProvider: String? = null) =
        SharedCreateAgentDialogSubmit(name = "reef", description = null, machineId = null, external = external, hostedProvider = hostedProvider)

    private class Recorder {
        val setupGuides = mutableListOf<MemberProfile>()
        val dmFetches = mutableListOf<MemberProfile>()
        val shownDms = mutableListOf<Pair<DataResult<OpenDirectMessageResult>, MemberProfile>>()
        val fallbackToasts = mutableListOf<String>()
    }

    private fun receipt(
        scope: CoroutineScope,
        host: RaftToastHostState?,
        recorder: Recorder,
        dmResult: DataResult<OpenDirectMessageResult>? = dm,
        currentServerId: String? = "server-1",
        awaitDm: CompletableDeferred<Unit>? = null,
        showDirectMessage: (DataResult<OpenDirectMessageResult>, MemberProfile) -> Unit = { result, member ->
            recorder.shownDms += result to member
        },
    ) = RaftCreatedAgentReceipt(
        scope = scope,
        toastHost = host,
        copy = copy,
        openSetupGuide = { recorder.setupGuides += it },
        openDirectMessage = { member ->
            recorder.dmFetches += member
            awaitDm?.await()
            dmResult
        },
        showDirectMessage = showDirectMessage,
        fallbackToast = { recorder.fallbackToasts += it },
        currentServerId = { currentServerId },
    )

    private fun shellPager(opened: MutableList<String>): Pager {
        val transport = HostBridgeTransport { _, _, _ -> }
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) {
                    opened += pageName
                }

                override fun closePage() = Unit
            }
        return object : Pager() {}.also {
            it.pagerId = "created-agent-toast-test"
            it.pageData = PageData(JSONObject())
            it.host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        }
    }

    private class Toasts {
        val plain = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, String?>>()
    }

    private fun DataResult<OpenDirectMessageResult>.openForTest(opened: MutableList<String>, toasts: Toasts) =
        openCreatedAgentDirectMessage(
            agent = agent,
            pager = shellPager(opened),
            storeScope = RaftShellStoreScope.create("shell-created-agent", "server-1"),
            openInShell = null,
            timeContext = null,
            copy = copy,
            errorTemplate = "Couldn't open: %1\$s",
            permissionMessage = "You can't message this agent.",
            errorStrings = englishErrorPresentationStringsForTest(),
            toast = { toasts.plain += it },
            errorToast = { message, traceId -> toasts.errors += message to traceId },
        )

    @Test
    fun landingIsTheToastExceptForAnExternalAgent() {
        assertEquals(CreatedAgentLanding.CreatedToast(agent), createdAgentLanding(submit(), agent))
        // A cloud agent is sent as external too, but has nothing to set up.
        assertEquals(CreatedAgentLanding.CreatedToast(agent), createdAgentLanding(submit(external = true, hostedProvider = "anthropic"), agent))
        assertEquals(CreatedAgentLanding.SetupGuide(agent), createdAgentLanding(submit(external = true), agent))
    }

    @Test
    fun creatingShowsCreatedWithViewAndOpensNothing() = runTest {
        val host = RaftToastHostState()
        val recorder = Recorder()
        receipt(this, host, recorder).land(createdAgentLanding(submit(), agent))
        advanceUntilIdle()

        val toast = host.visible.single()
        assertEquals("Created Reef", toast.title)
        assertEquals("View", toast.actionLabel)
        assertEquals(RaftToastIntent.Success, toast.intent)
        assertTrue(recorder.setupGuides.isEmpty(), "no profile opens on create")
        assertTrue(recorder.dmFetches.isEmpty(), "no DM opens until View is tapped")
        assertTrue(recorder.shownDms.isEmpty())
    }

    @Test
    fun viewOpensTheAgentsDirectMessage() = runTest {
        val host = RaftToastHostState()
        val recorder = Recorder()
        receipt(this, host, recorder).land(createdAgentLanding(submit(), agent))

        checkNotNull(host.visible.single().onAction).invoke()
        advanceUntilIdle()

        assertEquals(listOf(agent), recorder.dmFetches)
        assertEquals(listOf<Pair<DataResult<OpenDirectMessageResult>, MemberProfile>>(dm to agent), recorder.shownDms)
        assertTrue(recorder.setupGuides.isEmpty())
    }

    @Test
    fun viewWhileAnEarlierOpenIsInFlightOpensNoSecondPage() = runTest {
        val recorder = Recorder()
        receipt(this, RaftToastHostState(), recorder, dmResult = null).view(agent)
        advanceUntilIdle()

        assertEquals(listOf(agent), recorder.dmFetches)
        assertTrue(recorder.shownDms.isEmpty(), "the tap already opening it opens the page")
    }

    @Test
    fun viewRoutesTheDirectMessageToTheThreadPageAsMessageDoes() = runTest {
        val opened = mutableListOf<String>()
        val toasts = Toasts()
        val host = RaftToastHostState()
        val recorder = Recorder()
        receipt(this, host, recorder) { result, _ -> result.openForTest(opened, toasts) }
            .land(createdAgentLanding(submit(), agent))

        checkNotNull(host.visible.single().onAction).invoke()
        advanceUntilIdle()

        assertEquals(emptyList(), toasts.plain)
        assertEquals(emptyList(), toasts.errors)
        assertEquals(listOf(THREAD_PAGE), opened)
    }

    @Test
    fun aDirectMessageErrorOrRefusalShowsAnErrorToastWithItsTraceId() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val opened = mutableListOf<String>()
        val toasts = Toasts()

        DataResult.Error(ScreenError(code = "dm_open_failed", traceId = traceId)).openForTest(opened, toasts)
        DataResult.NoPermission(PermissionBlocker(code = "forbidden", traceId = traceId)).openForTest(opened, toasts)

        assertEquals(listOf<String?>(traceId, traceId), toasts.errors.map { it.second })
        assertTrue(toasts.errors[0].first.startsWith("Couldn't open: "), "the error names what failed: ${toasts.errors[0]}")
        assertEquals("You can't message this agent.", toasts.errors[1].first)
        assertEquals(emptyList(), toasts.plain)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun anEmptyResultSaysTheConversationCouldNotBeOpened() {
        val toasts = Toasts()
        DataResult.Empty(build.raft.app.data.EmptyReason.NoMembers).openForTest(mutableListOf(), toasts)
        assertEquals(listOf("Couldn't open your conversation with Reef. Try again."), toasts.plain)
    }

    @Test
    fun viewAfterTheSessionMovedToAnotherServerRequestsNothing() = runTest {
        val recorder = Recorder()
        receipt(this, RaftToastHostState(), recorder, currentServerId = "server-2").view(agent)
        advanceUntilIdle()

        assertTrue(recorder.dmFetches.isEmpty(), "no DM is requested on the wrong server")
        assertTrue(recorder.shownDms.isEmpty())
    }

    @Test
    fun theCreatingPageLeavingClosesTheToastAndViewOpensNothing() = runTest {
        val host = RaftToastHostState()
        val recorder = Recorder()
        val receipt = receipt(this, host, recorder)
        receipt.land(createdAgentLanding(submit(), agent))
        val view = checkNotNull(host.visible.single().onAction)

        receipt.dispose()
        view()
        advanceUntilIdle()

        assertTrue(host.visible.isEmpty(), "the toast closes with the page that would open the DM")
        assertTrue(recorder.dmFetches.isEmpty())
        assertTrue(recorder.shownDms.isEmpty())
    }

    @Test
    fun aViewUnderWayWhenThePageLeavesOpensNothing() = runTest {
        val recorder = Recorder()
        val answer = CompletableDeferred<Unit>()
        val receipt = receipt(this, RaftToastHostState(), recorder, awaitDm = answer)
        receipt.view(agent)
        runCurrent()
        assertEquals(listOf(agent), recorder.dmFetches)

        receipt.dispose()
        answer.complete(Unit)
        advanceUntilIdle()

        assertTrue(recorder.shownDms.isEmpty(), "the person went on: no page opens over where they are")
    }

    @Test
    fun anExternalAgentOpensItsSetupGuideWithoutAToast() = runTest {
        val host = RaftToastHostState()
        val recorder = Recorder()
        receipt(this, host, recorder).land(createdAgentLanding(submit(external = true), agent))
        advanceUntilIdle()

        assertEquals(listOf(agent), recorder.setupGuides)
        assertTrue(host.visible.isEmpty())
        assertTrue(recorder.dmFetches.isEmpty())
    }

    @Test
    fun withoutAToastHostTheTitleAloneIsShown() = runTest {
        val recorder = Recorder()
        receipt(this, null, recorder).land(createdAgentLanding(submit(), agent))
        advanceUntilIdle()

        assertEquals(listOf("Created Reef"), recorder.fallbackToasts)
        assertTrue(recorder.setupGuides.isEmpty())
        assertTrue(recorder.dmFetches.isEmpty())
    }

    @Test
    fun theOpenFailureNamesTheAgent() {
        assertEquals("Couldn't open your conversation with Reef. Try again.", copy.openFailed("Reef"))
    }
}
