package build.raft.app

import build.raft.app.data.ChannelExternalInvite
import build.raft.app.data.ChannelExternalInviteList
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInviteGuestElsewhere
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInviteRefusal
import build.raft.app.data.ScreenError
import build.raft.app.data.SendExternalInvitesResult
import build.raft.app.data.SentExternalInvite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakeExternalInvitesDataSource : ExternalInvitesDataSource {
    var listResults = ArrayDeque<CompletableDeferred<DataResult<ExternalInviteAnswer<ChannelExternalInviteList>>>>()
    var listAnswer: DataResult<ExternalInviteAnswer<ChannelExternalInviteList>> =
        DataResult.Content(ExternalInviteAnswer.Ok(ChannelExternalInviteList(listOf(externalTestInvite("i1", "a@example.com")), slotsLeft = 28)))
    var previewAnswer: DataResult<ExternalInviteAnswer<ExternalInvitePreview>> =
        DataResult.Content(ExternalInviteAnswer.Ok(ExternalInvitePreview(valid = listOf("new@example.com"), slotsLeft = 28)))
    var sendAnswer: DataResult<ExternalInviteAnswer<SendExternalInvitesResult>> =
        DataResult.Content(
            ExternalInviteAnswer.Ok(SendExternalInvitesResult(sent = listOf(SentExternalInvite("i9", "new@example.com")), added = listOf("m@example.com")))
        )
    var resendAnswer: DataResult<ExternalInviteAnswer<SentExternalInvite>> =
        DataResult.Content(ExternalInviteAnswer.Ok(SentExternalInvite("i10", "a@example.com")))
    var revokeAnswer: DataResult<ExternalInviteAnswer<Unit>> = DataResult.Content(ExternalInviteAnswer.Ok(Unit))
    val previewCalls = mutableListOf<List<String>>()
    val sendCalls = mutableListOf<Pair<List<String>, Boolean>>()
    val resendCalls = mutableListOf<String>()
    val revokeCalls = mutableListOf<String>()
    var listCalls = 0

    override suspend fun list(): DataResult<ExternalInviteAnswer<ChannelExternalInviteList>> {
        listCalls++
        return listResults.removeFirstOrNull()?.await() ?: listAnswer
    }

    override suspend fun preview(emails: List<String>) = previewAnswer.also { previewCalls += emails }

    override suspend fun send(emails: List<String>, makePrivate: Boolean) = sendAnswer.also { sendCalls += emails to makePrivate }

    override suspend fun resend(inviteId: String) = resendAnswer.also { resendCalls += inviteId }

    override suspend fun revoke(inviteId: String) = revokeAnswer.also { revokeCalls += inviteId }
}

internal fun externalTestInvite(
    id: String,
    email: String,
    connecting: Boolean = false,
    awaitingApproval: Boolean = false,
    revocable: Boolean = true,
    expiresAt: String = "2026-11-25T00:00:00.000Z",
) = ChannelExternalInvite(
    id = id,
    email = email,
    connecting = connecting,
    awaitingApproval = awaitingApproval,
    revocable = revocable,
    expiresAt = expiresAt,
)

private const val TraceId = "0123456789abcdef0123456789abcdef"

/**
 * External invites, the host side (web `externalInvites.ts`, `ExternalInviteSteps.tsx`,
 * `ExternalInviteRows.tsx`, botiverse/slock staging 0e8b4fab7440).
 */
class ExternalInvitesStoreTest {
    private fun channel(
        type: String? = "private",
        name: String = "vendors",
        archived: Boolean = false,
        jointRole: String? = null,
        hasExternalRelation: Boolean = false,
    ) = ExternalInviteChannel(type, name, archived, jointRole, hasExternalRelation)

    @Test
    fun accessNeedsTheFlagAnOwnerOrAdminAndAnInvitableChannel() {
        assertTrue(externalInviteAccess(true, "owner", channel()))
        assertTrue(externalInviteAccess(true, "admin", channel(type = "channel")))
        assertTrue(externalInviteAccess(true, "admin", channel(type = "joint", jointRole = "host")))
        listOf(
            externalInviteAccess(false, "owner", channel()),
            externalInviteAccess(true, "member", channel()),
            externalInviteAccess(true, null, channel()),
            externalInviteAccess(true, "owner", channel(name = "all", type = "channel")),
            externalInviteAccess(true, "owner", channel(archived = true)),
            // Another server's projection of a joint channel is the host's to share.
            externalInviteAccess(true, "owner", channel(type = "joint", jointRole = "member")),
            externalInviteAccess(true, "owner", channel(type = "dm")),
            externalInviteAccess(true, "owner", null),
        ).forEachIndexed { index, allowed -> assertFalse(allowed, "case $index") }
    }

    @Test
    fun managementOutlivesTheFlagWhileTheChannelIsStillExternal() {
        assertFalse(externalInviteManagement(false, "owner", channel()))
        assertTrue(externalInviteManagement(false, "owner", channel(hasExternalRelation = true)))
        assertFalse(externalInviteManagement(false, "member", channel(hasExternalRelation = true)))
    }

    @Test
    fun daysLeftRoundsUpAndIsAtLeastOne() {
        val now = 1_763_337_600_000L // 2025-11-17T00:00:00Z
        assertEquals(14, externalInviteDaysLeft("2025-12-01T00:00:00.000Z", now))
        assertEquals(2, externalInviteDaysLeft("2025-11-18T00:00:01.000Z", now))
        assertEquals(1, externalInviteDaysLeft("2025-11-17T00:00:00.000Z", now))
    }

    @Test
    fun errorsReadTheServersCodeThenTheStatus() {
        assertEquals(ExternalInviteError.FreeLimit, externalInviteErrorFor("free_external_limit", 409))
        assertEquals(ExternalInviteError.ChannelFull, externalInviteErrorFor("channel_full", 409))
        assertEquals(ExternalInviteError.DailyCap, externalInviteErrorFor(null, 429))
        assertEquals(ExternalInviteError.NotFound, externalInviteErrorFor(null, 404))
        assertEquals(ExternalInviteError.Forbidden, externalInviteErrorFor(null, 403))
        assertEquals(ExternalInviteError.Generic, externalInviteErrorFor("something_new", 500))
    }

    @Test
    fun reviewRowsListWhoIsInvitedAddedThenWhoIsNotAndWhy() {
        val rows =
            externalInviteReviewRows(
                ExternalInvitePreview(
                    valid = listOf("new@example.com"),
                    duplicates = listOf("new@example.com", "new@example.com"),
                    invalid = listOf("nope"),
                    alreadyPending = listOf("p@example.com"),
                    alreadyMembers = listOf("m@example.com"),
                    alreadyInChannel = listOf("c@example.com"),
                    guestsElsewhere = listOf(ExternalInviteGuestElsewhere("g@example.com", "vendor"), ExternalInviteGuestElsewhere("h@example.com")),
                )
            )
        assertEquals(
            listOf(
                ExternalInviteReviewStatus.WillInvite,
                ExternalInviteReviewStatus.WillAdd,
                ExternalInviteReviewStatus.Duplicate,
                ExternalInviteReviewStatus.AlreadyInvited,
                ExternalInviteReviewStatus.AlreadyInChannel,
                ExternalInviteReviewStatus.GuestElsewhere,
                ExternalInviteReviewStatus.GuestElsewhereUnnamed,
                ExternalInviteReviewStatus.Invalid,
            ),
            rows.map { it.status },
        )
        assertEquals("#vendor", rows.first { it.status == ExternalInviteReviewStatus.GuestElsewhere }.channel)
        assertEquals(listOf(true, true), rows.take(2).map { it.invites })
    }

    @Test
    fun slotsAreSaidOnlyWhenTheSendCouldReachThem() {
        assertNull(externalInviteSlotsLine(slotsLeft = null, taking = 3))
        assertEquals(ExternalInviteSlotsLine.Full, externalInviteSlotsLine(slotsLeft = 0, taking = 1))
        assertEquals(ExternalInviteSlotsLine.Over(room = 2, count = 1), externalInviteSlotsLine(slotsLeft = 2, taking = 3))
        assertEquals(ExternalInviteSlotsLine.Fills, externalInviteSlotsLine(slotsLeft = 3, taking = 3))
        assertEquals(ExternalInviteSlotsLine.Left(2), externalInviteSlotsLine(slotsLeft = 5, taking = 3))
        // Far from the slots (more than 20 left): nothing to say.
        assertNull(externalInviteSlotsLine(slotsLeft = 25, taking = 3))
        assertNull(externalInviteTypedSlotsLine(channelSlotsLeft = 5, entries = 0))
        assertEquals(ExternalInviteSlotsLine.Over(room = 1, count = 1), externalInviteTypedSlotsLine(channelSlotsLeft = 1, entries = 2))
    }

    @Test
    fun theDialogPreviewsThenSendsWhatWasReviewedAndToastsTheResult() = runTest {
        val source = FakeExternalInvitesDataSource()
        source.previewAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Ok(
                    ExternalInvitePreview(valid = listOf("new@example.com"), alreadyMembers = listOf("m@example.com"), invalid = listOf("x"), slotsLeft = 28)
                )
            )
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.OpenDialog)
        // Nothing typed: Next does nothing.
        store.dispatch(ExternalInvitesIntent.Next)
        assertEquals(emptyList(), source.previewCalls)

        store.dispatch(ExternalInvitesIntent.SetText("New@Example.com, m@example.com x"))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        assertEquals(listOf(listOf("New@Example.com", "m@example.com", "x")), source.previewCalls)
        val dialog = assertNotNull(store.state.value.dialog)
        assertEquals(2, dialog.actionCount)

        store.dispatch(ExternalInvitesIntent.Send(makePrivate = true))
        runCurrent()
        assertEquals(listOf(listOf("new@example.com", "m@example.com") to true), source.sendCalls)
        assertNull(store.state.value.dialog)
        assertEquals(listOf(ExternalInvitesEffect.Sent(1), ExternalInvitesEffect.Added(1)), store.state.value.effects)
        // The list is read again after a send.
        assertEquals(1, source.listCalls)
        assertEquals(listOf("i1"), store.state.value.invites.map { it.id })
    }

    @Test
    fun moreThanTwentyEntriesCannotBeChecked() = runTest {
        val source = FakeExternalInvitesDataSource()
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.OpenDialog)
        store.dispatch(ExternalInvitesIntent.SetText((1..21).joinToString(" ") { "p$it@example.com" }))
        assertEquals(1, store.state.value.dialog?.overBy)
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        assertEquals(emptyList(), source.previewCalls)
    }

    @Test
    fun theFreePlanLimitStaysInTheDialogWithUpgradeAndTheTraceId() = runTest {
        val source = FakeExternalInvitesDataSource()
        source.sendAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Refused(ExternalInviteRefusal(409, "free_external_limit", "limit", limitSide = "host", traceId = TraceId))
            )
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.OpenDialog)
        store.dispatch(ExternalInvitesIntent.SetText("new@example.com"))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()

        val failure = assertNotNull(store.state.value.dialog?.failure)
        assertEquals(ExternalInviteFailure(ExternalInviteError.FreeLimit, upgrade = true, traceId = TraceId), failure)
        assertEquals(false, store.state.value.dialog?.busy)
        assertEquals(emptyList(), store.state.value.effects)
        // Typing again clears it; Back returns to the emails.
        store.dispatch(ExternalInvitesIntent.Back)
        assertNull(store.state.value.dialog?.preview)
        assertNull(store.state.value.dialog?.failure)
    }

    @Test
    fun aSendPastTheSlotsIsNotSent() = runTest {
        val source = FakeExternalInvitesDataSource()
        source.previewAnswer =
            DataResult.Content(ExternalInviteAnswer.Ok(ExternalInvitePreview(valid = listOf("a@example.com", "b@example.com"), slotsLeft = 1)))
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.OpenDialog)
        store.dispatch(ExternalInvitesIntent.SetText("a@example.com b@example.com"))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        assertEquals(1, store.state.value.dialog?.overSlots)
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()
        assertEquals(emptyList(), source.sendCalls)
    }

    @Test
    fun anOlderListAnswerThatLandsLateIsDropped() = runTest {
        val source = FakeExternalInvitesDataSource()
        val first = CompletableDeferred<DataResult<ExternalInviteAnswer<ChannelExternalInviteList>>>()
        source.listResults.addLast(first)
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.Load)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Load)
        runCurrent()
        assertEquals(listOf("i1"), store.state.value.invites.map { it.id })
        first.complete(DataResult.Content(ExternalInviteAnswer.Ok(ChannelExternalInviteList(emptyList()))))
        runCurrent()
        assertEquals(listOf("i1"), store.state.value.invites.map { it.id })
    }

    @Test
    fun aFailedReadKeepsWhatWasListedAndCarriesTheTraceId() = runTest {
        val source = FakeExternalInvitesDataSource()
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.Load)
        runCurrent()
        source.listAnswer = DataResult.Error(ScreenError(code = "network_unavailable", traceId = TraceId))
        store.dispatch(ExternalInvitesIntent.Load)
        runCurrent()
        assertEquals(listOf("i1"), store.state.value.invites.map { it.id })
        assertEquals(TraceId, store.state.value.loadFailure?.traceId)
    }

    @Test
    fun resendIsForAPlainPendingInviteAndToastsEitherWay() = runTest {
        val source = FakeExternalInvitesDataSource()
        source.listAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Ok(
                    ChannelExternalInviteList(
                        listOf(externalTestInvite("i1", "a@example.com"), externalTestInvite("i2", "b@example.com", awaitingApproval = true))
                    )
                )
            )
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.Load)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Resend("i2"))
        runCurrent()
        assertEquals(emptyList(), source.resendCalls)

        store.dispatch(ExternalInvitesIntent.Resend("i1"))
        runCurrent()
        assertEquals(listOf("i1"), source.resendCalls)
        assertEquals(listOf(ExternalInvitesEffect.Resent("a@example.com")), store.state.value.effects)
        store.dispatch(ExternalInvitesIntent.ConsumeEffect(ExternalInvitesEffect.Resent("a@example.com")))

        source.resendAnswer = DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(429, "daily_cap", null, traceId = TraceId)))
        store.dispatch(ExternalInvitesIntent.Resend("i1"))
        runCurrent()
        assertEquals(
            listOf(ExternalInvitesEffect.Failed(ExternalInviteFailure(ExternalInviteError.DailyCap, traceId = TraceId))),
            store.state.value.effects,
        )
        assertEquals(emptySet(), store.state.value.rowBusy)
    }

    @Test
    fun withdrawIsConfirmedAndARefusalStaysInTheConfirm() = runTest {
        val source = FakeExternalInvitesDataSource()
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.Load)
        runCurrent()
        source.revokeAnswer = DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(409, "connecting", null, traceId = TraceId)))
        store.dispatch(ExternalInvitesIntent.RequestWithdraw("i1"))
        assertEquals("i1", store.state.value.withdrawConfirm?.id)
        store.dispatch(ExternalInvitesIntent.ConfirmWithdraw)
        runCurrent()
        assertEquals(listOf("i1"), source.revokeCalls)
        assertEquals("i1", store.state.value.withdrawConfirm?.id)
        assertEquals(ExternalInviteError.Connecting, store.state.value.withdrawFailure?.error)
        assertEquals(TraceId, store.state.value.withdrawFailure?.traceId)

        source.revokeAnswer = DataResult.Content(ExternalInviteAnswer.Ok(Unit))
        source.listAnswer = DataResult.Content(ExternalInviteAnswer.Ok(ChannelExternalInviteList(emptyList())))
        store.dispatch(ExternalInvitesIntent.ConfirmWithdraw)
        runCurrent()
        assertNull(store.state.value.withdrawConfirm)
        assertEquals(emptyList(), store.state.value.invites)
        assertEquals(listOf(ExternalInvitesEffect.Withdrawn("a@example.com")), store.state.value.effects)
    }
}
