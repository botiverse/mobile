package build.raft.app

import build.raft.app.data.AcceptedExternalInvite
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteForInvitee
import build.raft.app.data.GuestAcceptance
import build.raft.app.data.InviteApprover
import build.raft.app.data.InviteConnectOptions
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.InviteOptions
import build.raft.app.data.InviteServerProgress
import build.raft.app.data.InviteServerSelection
import build.raft.app.data.InviteeAnswer
import build.raft.app.data.InviteeRefusal
import build.raft.app.data.NewServerConnectResult
import build.raft.app.data.PendingConnectRequest
import build.raft.app.data.ServerConnectOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeExternalInviteDataSource(
    var pages: ArrayDeque<DataResult<InviteeAnswer<ExternalInviteForInvitee>>> = ArrayDeque(),
) : ExternalInviteDataSource {
    var page: DataResult<InviteeAnswer<ExternalInviteForInvitee>> = DataResult.Content(InviteeAnswer.Ok(inviteTestPage()))
    var guestAnswer: DataResult<InviteeAnswer<GuestAcceptance>> = DataResult.Content(InviteeAnswer.Ok(GuestAcceptance("host", "c1")))
    var connectAnswer: DataResult<InviteeAnswer<List<ServerConnectOutcome>>> =
        DataResult.Content(InviteeAnswer.Ok(listOf(ServerConnectOutcome.Connected("s1", "p1"))))
    var newServerAnswer: DataResult<InviteeAnswer<NewServerConnectResult>> =
        DataResult.Content(InviteeAnswer.Ok(NewServerConnectResult("connected", "n1", "p9")))
    var cancelAnswer: DataResult<InviteeAnswer<Unit>> = DataResult.Content(InviteeAnswer.Ok(Unit))
    var getCalls = 0
    val connectCalls = mutableListOf<List<InviteServerSelection>>()
    val newServerCalls = mutableListOf<Pair<String, String>>()
    val cancelCalls = mutableListOf<String?>()

    override suspend fun get(): DataResult<InviteeAnswer<ExternalInviteForInvitee>> {
        getCalls++
        return pages.removeFirstOrNull() ?: page
    }

    override suspend fun acceptAsGuest() = guestAnswer

    override suspend fun connectServers(servers: List<InviteServerSelection>) = connectAnswer.also { connectCalls += servers }

    override suspend fun connectNewServer(name: String, slug: String) = newServerAnswer.also { newServerCalls += name to slug }

    override suspend fun cancelRequest(requestId: String?) = cancelAnswer.also { cancelCalls += requestId }
}

internal fun inviteTestPage(
    status: String = "pending",
    guest: Boolean = true,
    newServer: Boolean = true,
    servers: List<InviteConnectableServer> =
        listOf(
            InviteConnectableServer(id = "s1", name = "Contoso", slug = "contoso", canConnectDirectly = true),
            InviteConnectableServer(
                id = "s2",
                name = "Fabrikam",
                slug = "fabrikam",
                needsApproval = true,
                approvers = listOf(InviteApprover("u1", "Sam"), InviteApprover("u2", "Kai")),
            ),
            InviteConnectableServer(id = "s3", name = "Northwind", slug = "northwind", atLimit = true),
        ),
    slotsLeft: Int? = null,
) = ExternalInviteForInvitee(
    channelName = "launch",
    serverName = "Acme",
    inviterName = "Lena",
    expiresAt = "2026-11-25T00:00:00.000Z",
    status = status,
    options = InviteOptions(guest = guest, connect = InviteConnectOptions(servers, slotsLeft), newServer = newServer),
)

private const val TraceId = "0123456789abcdef0123456789abcdef"

/** The invite page (web ExternalInvitePage, externalInviteAccept.ts, inviteSteps.ts; botiverse/slock staging 0e8b4fab7440). */
class ExternalInviteStoreTest {
    private fun TestScope.loaded(source: FakeExternalInviteDataSource): ExternalInviteStore {
        val store = ExternalInviteStore(this, source)
        store.dispatch(ExternalInviteIntent.SetMyServers(listOf(InviteMyServer("s1", "Contoso", "contoso", "free"))))
        store.dispatch(ExternalInviteIntent.Load)
        runCurrent()
        return store
    }

    @Test
    fun theStatusDecidesTheFirstStep() {
        assertEquals(InviteStep.WrongAccount("l•••@x.com"), inviteStepForStatus(ExternalInviteForInvitee(addressedToYou = false, invitedEmailMasked = "l•••@x.com"), null))
        assertEquals(InviteStep.Options, inviteStepForStatus(inviteTestPage(), null))
        val landing = AcceptedExternalInvite("guest", "host", "c1", "launch")
        assertEquals(InviteStep.Connected("host", landing), inviteStepForStatus(inviteTestPage(status = "accepted").copy(accepted = landing), null))
        assertEquals(InviteStep.Connecting("s9"), inviteStepForStatus(inviteTestPage(status = "connecting").copy(connectServerId = "s9"), null))
        val waiting = inviteTestPage(status = "awaiting_approval").copy(pendingRequest = PendingConnectRequest("r1", "s2", "Fabrikam", "u1", "Sam", "x"))
        assertEquals(InviteStep.Waiting(InviteWaitingRequest("s2", "Fabrikam", "Sam", "x")), inviteStepForStatus(waiting, null))
        val several =
            inviteTestPage(status = "connecting").copy(
                servers = listOf(InviteServerProgress("s1", "Contoso", "connecting"), InviteServerProgress("s2", "Fabrikam", "awaiting_approval"))
            )
        assertEquals(InviteStep.Progress(emptyList()), inviteStepForStatus(several, null))
    }

    @Test
    fun theNeutral404IsNotValidAndAFailedReadCanBeRetried() = runTest {
        val source = FakeExternalInviteDataSource()
        source.page = DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(404)))
        val store = loaded(source)
        assertEquals(InviteStep.Invalid, store.state.value.step)

        source.page = DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(500, traceId = TraceId)))
        store.dispatch(ExternalInviteIntent.Load)
        runCurrent()
        assertEquals(InviteStep.LoadFailed(TraceId), store.state.value.step)
    }

    @Test
    fun anotherAccountIsToldWhichAddressAndCanSwitch() = runTest {
        val source = FakeExternalInviteDataSource()
        source.page = DataResult.Content(InviteeAnswer.Ok(ExternalInviteForInvitee(addressedToYou = false, invitedEmailMasked = "l•••@contoso.com")))
        val store = loaded(source)
        assertEquals(InviteStep.WrongAccount("l•••@contoso.com"), store.state.value.step)
        store.dispatch(ExternalInviteIntent.SignOut)
        assertEquals(listOf<ExternalInviteEffect>(ExternalInviteEffect.SignOut), store.state.value.effects)
    }

    @Test
    fun aGuestJoinOpensTheChannelAndARefusalStaysOnThePage() = runTest {
        val source = FakeExternalInviteDataSource()
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.ShowGuest)
        source.guestAnswer = DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(409, "guest_elsewhere", traceId = TraceId)))
        store.dispatch(ExternalInviteIntent.JoinAsGuest)
        runCurrent()
        assertEquals(InviteeError(InviteeMessage.GuestElsewhere, traceId = TraceId), store.state.value.error)
        assertEquals(false, store.state.value.busy)

        source.guestAnswer = DataResult.Content(InviteeAnswer.Ok(GuestAcceptance("host", "c1")))
        store.dispatch(ExternalInviteIntent.JoinAsGuest)
        runCurrent()
        assertEquals(
            listOf(ExternalInviteEffect.ServersChanged, ExternalInviteEffect.OpenChannel("host", "c1")),
            store.state.value.effects,
        )
    }

    @Test
    fun aRefusalWithTheNeutral404MeansTheInviteIsNotThisAccounts() = runTest {
        val source = FakeExternalInviteDataSource()
        val store = loaded(source)
        source.guestAnswer = DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(404)))
        store.dispatch(ExternalInviteIntent.JoinAsGuest)
        runCurrent()
        assertEquals(InviteStep.WrongAccount(null), store.state.value.step)
    }

    @Test
    fun aServerAtItsLimitCannotBeChosenAndADirectServerConnectsAtOnce() = runTest {
        val source = FakeExternalInviteDataSource()
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.ShowConnect)
        store.dispatch(ExternalInviteIntent.SetPicked(listOf("s3", "s1")))
        assertEquals(listOf("s1"), store.state.value.pickedServerIds)
        store.dispatch(ExternalInviteIntent.Connect)
        runCurrent()
        assertEquals(listOf(listOf(InviteServerSelection("s1"))), source.connectCalls)
        assertEquals(ExternalInviteEffect.OpenChannel("s1", "p1"), store.state.value.effects.last())
    }

    @Test
    fun aServerThatNeedsApprovalAsksItsPickedApproverThenWaits() = runTest {
        val source = FakeExternalInviteDataSource()
        source.connectAnswer = DataResult.Content(InviteeAnswer.Ok(listOf(ServerConnectOutcome.AwaitingApproval("s2", "r1"))))
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.SetPicked(listOf("s2")))
        store.dispatch(ExternalInviteIntent.Connect)
        assertEquals(InviteStep.Approval(listOf("s2")), store.state.value.step)
        assertEquals(emptyList(), source.connectCalls)

        store.dispatch(ExternalInviteIntent.PickApprover("s2", "u2"))
        store.dispatch(ExternalInviteIntent.RequestApproval)
        runCurrent()
        assertEquals(listOf(listOf(InviteServerSelection("s2", "u2"))), source.connectCalls)
        assertEquals(InviteStep.Waiting(InviteWaitingRequest("s2", "Fabrikam", "Kai", "2026-11-25T00:00:00.000Z")), store.state.value.step)
    }

    @Test
    fun theWaitingPagePollsAndADeclineGoesBackToTheWaysIn() = runTest {
        val source = FakeExternalInviteDataSource()
        source.page =
            DataResult.Content(
                InviteeAnswer.Ok(
                    inviteTestPage(status = "awaiting_approval").copy(
                        pendingRequest = PendingConnectRequest("r1", "s2", "Fabrikam", "u1", "Sam", "x")
                    )
                )
            )
        val store = loaded(source)
        assertIs<InviteStep.Waiting>(store.state.value.step)
        val reads = source.getCalls
        advanceTimeBy(ExternalInvitePollMillis + 1)
        assertEquals(reads + 1, source.getCalls)
        assertIs<InviteStep.Waiting>(store.state.value.step)

        source.page = DataResult.Content(InviteeAnswer.Ok(inviteTestPage().copy(lastRequest = build.raft.app.data.EndedConnectRequest("rejected", "Fabrikam"))))
        advanceTimeBy(ExternalInvitePollMillis + 1)
        assertEquals(InviteStep.Options, store.state.value.step)
        // A request declined is not a failed connection.
        assertEquals(false, store.state.value.connectFailedNotice)
        store.clear()
    }

    @Test
    fun aHiddenPageDoesNotPollAndReadsAgainWhenShown() = runTest {
        val source = FakeExternalInviteDataSource()
        source.page = DataResult.Content(InviteeAnswer.Ok(inviteTestPage(status = "connecting").copy(connectServerId = "s1")))
        val store = loaded(source)
        assertEquals(InviteStep.Connecting("s1"), store.state.value.step)
        store.dispatch(ExternalInviteIntent.SetVisible(false))
        val reads = source.getCalls
        advanceTimeBy(ExternalInvitePollMillis * 3)
        assertEquals(reads, source.getCalls)

        store.dispatch(ExternalInviteIntent.SetVisible(true))
        runCurrent()
        assertEquals(reads + 1, source.getCalls)
        advanceTimeBy(ExternalInvitePollMillis + 1)
        assertEquals(reads + 2, source.getCalls)
        store.clear()
    }

    @Test
    fun aConnectionThatDidNotGoThroughSaysSoAndOneThatDidOpensTheChannel() = runTest {
        val source = FakeExternalInviteDataSource()
        source.connectAnswer = DataResult.Content(InviteeAnswer.Ok(listOf(ServerConnectOutcome.Connecting("s1"))))
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.SetPicked(listOf("s1")))
        store.dispatch(ExternalInviteIntent.Connect)
        runCurrent()
        assertEquals(InviteStep.Connecting("s1"), store.state.value.step)

        source.page = DataResult.Content(InviteeAnswer.Ok(inviteTestPage()))
        advanceTimeBy(ExternalInvitePollMillis + 1)
        assertEquals(InviteStep.Options, store.state.value.step)
        assertTrue(store.state.value.connectFailedNotice)
        assertEquals("s1", store.state.value.connectFailedServerId)

        store.dispatch(ExternalInviteIntent.Connect)
        runCurrent()
        val landing = AcceptedExternalInvite("server", "s1", "p1", "launch")
        source.page = DataResult.Content(InviteeAnswer.Ok(inviteTestPage(status = "accepted").copy(accepted = landing)))
        advanceTimeBy(ExternalInvitePollMillis + 1)
        assertEquals(InviteStep.Connected("s1", landing), store.state.value.step)
        assertEquals(ExternalInviteEffect.OpenChannel("s1", "p1"), store.state.value.effects.last())
        store.clear()
    }

    @Test
    fun severalServersGoTogetherAndTheListSaysWhereEachStands() = runTest {
        val source = FakeExternalInviteDataSource()
        source.connectAnswer =
            DataResult.Content(
                InviteeAnswer.Ok(
                    listOf(
                        ServerConnectOutcome.Connected("s1", "p1"),
                        ServerConnectOutcome.Refused("s2", 409, "free_external_limit", "x", limitSide = "connecting", limitServerId = "s2"),
                    )
                )
            )
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.SetPicked(listOf("s1", "s2")))
        store.dispatch(ExternalInviteIntent.Connect)
        store.dispatch(ExternalInviteIntent.RequestApproval)
        runCurrent()
        val step = assertIs<InviteStep.Progress>(store.state.value.step)
        assertEquals(listOf("Contoso", "Fabrikam"), step.chosen.map { it.name })
        val rows = inviteProgressRows(step.chosen, store.state.value.invite!!)
        assertEquals(listOf("connected", "refused"), rows.map { it.state })
        // Fabrikam is not the caller's own server: the connecting server's limit reads as theirs only when it is.
        assertEquals(
            InviteeMessage.TargetFreeLimit,
            inviteeErrorMessage(rows[1].refusal!!.asRefusal(), InviteeAction.Connect, null, "s2"),
        )
    }

    @Test
    fun limitRefusalsNameWhoseLimitItIs() {
        val host = InviteeRefusal(409, "free_external_limit", limitSide = "host", limitServerId = "h")
        assertEquals(InviteeMessage.HostLimit, inviteeErrorMessage(host, InviteeAction.Connect, InviteMyServer("s1", "C", null, "free")))
        assertEquals(InviteeMessage.HostLimit, inviteeErrorMessage(host.copy(limitSide = "connecting", limitServerId = "s1"), InviteeAction.Guest, null))
        val mine = InviteeRefusal(409, "free_external_limit", limitSide = "connecting", limitServerId = "s1")
        assertEquals(InviteeMessage.TargetFreeLimit, inviteeErrorMessage(mine, InviteeAction.Connect, InviteMyServer("s1", "C", null, "free")))
        val unborn = InviteeRefusal(409, "joint_free_server_limit", limitSide = "connecting")
        assertEquals(InviteeMessage.JointServerLimit, inviteeErrorMessage(unborn, InviteeAction.NewServer, null, null))
        assertEquals(InviteeMessage.TooManyAttempts, inviteeErrorMessage(InviteeRefusal(429), InviteeAction.Guest, null))
        assertEquals(InviteeMessage.Busy, inviteeErrorMessage(InviteeRefusal(409, "channel_conversion_in_progress"), InviteeAction.Connect, null))
    }

    @Test
    fun aNewServerIsCheckedFirstAndASlugTakenStaysOnItsField() = runTest {
        val source = FakeExternalInviteDataSource()
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.ShowNewServer)
        store.dispatch(ExternalInviteIntent.SetNewServerName("My Team"))
        assertEquals("my-team", store.state.value.newServerSlug)
        store.dispatch(ExternalInviteIntent.SetNewServerSlug("ab"))
        store.dispatch(ExternalInviteIntent.CreateServer)
        assertEquals(NewServerFieldError.SlugTooShort, store.state.value.fieldErrors.slug)
        assertEquals(emptyList(), source.newServerCalls)

        store.dispatch(ExternalInviteIntent.SetNewServerSlug("my-team"))
        source.newServerAnswer = DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(409)))
        store.dispatch(ExternalInviteIntent.CreateServer)
        runCurrent()
        assertEquals(listOf("My Team" to "my-team"), source.newServerCalls)
        assertEquals(NewServerFieldError.SlugTaken, store.state.value.fieldErrors.slug)

        source.newServerAnswer = DataResult.Content(InviteeAnswer.Ok(NewServerConnectResult("connecting", "n1"), status = 202))
        store.dispatch(ExternalInviteIntent.CreateServer)
        runCurrent()
        assertEquals(InviteStep.Connecting("n1"), store.state.value.step)
        store.clear()
    }

    @Test
    fun aServerCreatedThenRefusedIsKept() = runTest {
        val source = FakeExternalInviteDataSource()
        source.newServerAnswer =
            DataResult.Content(
                InviteeAnswer.Refused(InviteeRefusal(409, "joint_free_server_limit", limitSide = "connecting", limitServerId = "n1", serverId = "n1", traceId = TraceId))
            )
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.ShowNewServer)
        store.dispatch(ExternalInviteIntent.SetNewServerName("Northwind Team"))
        store.dispatch(ExternalInviteIntent.CreateServer)
        runCurrent()
        assertEquals(InviteKeptServer("n1", "Northwind Team"), store.state.value.keptServer)
        assertEquals(InviteeMessage.JointFreeServerLimit, store.state.value.error?.message)
        assertEquals(TraceId, store.state.value.error?.traceId)
        store.dispatch(ExternalInviteIntent.OpenKeptServer)
        assertEquals(ExternalInviteEffect.OpenServer("n1"), store.state.value.effects.last())
    }

    @Test
    fun cancellingTheWaitingRequestIsConfirmedThenTheInviteIsReadAgain() = runTest {
        val source = FakeExternalInviteDataSource()
        source.page =
            DataResult.Content(
                InviteeAnswer.Ok(
                    inviteTestPage(status = "awaiting_approval").copy(pendingRequest = PendingConnectRequest("r1", "s2", "Fabrikam", "u1", "Sam", "x"))
                )
            )
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.AskCancel(null, "Sam"))
        assertEquals(InviteCancelConfirm(null, "Sam"), store.state.value.cancelConfirm)
        source.page = DataResult.Content(InviteeAnswer.Ok(inviteTestPage()))
        store.dispatch(ExternalInviteIntent.ConfirmCancel)
        runCurrent()
        assertEquals(listOf<String?>(null), source.cancelCalls)
        assertNull(store.state.value.cancelConfirm)
        assertEquals(InviteStep.Options, store.state.value.step)
        store.clear()
    }

    @Test
    fun anInviteAlreadyAcceptedOpensWhereItLandedOnce() = runTest {
        val landing = AcceptedExternalInvite("guest", "host", "c1", "launch")
        val source = FakeExternalInviteDataSource()
        source.page = DataResult.Content(InviteeAnswer.Ok(inviteTestPage(status = "accepted").copy(accepted = landing)))
        val store = loaded(source)
        store.dispatch(ExternalInviteIntent.Load)
        runCurrent()
        assertEquals(1, store.state.value.effects.count { it is ExternalInviteEffect.OpenChannel })
    }

    @Test
    fun newServerSlugRulesMatchTheServers() {
        assertEquals(NewServerFieldErrors(NewServerFieldError.NameRequired, NewServerFieldError.SlugRequired), newServerFieldErrors(" ", ""))
        assertEquals(NewServerFieldError.SlugPattern, newServerFieldErrors("x", "1team").slug)
        assertNull(newServerFieldErrors("x", "team-1").slug)
        assertEquals("my-team-", toServerSlug("My Team!"))
    }
}
