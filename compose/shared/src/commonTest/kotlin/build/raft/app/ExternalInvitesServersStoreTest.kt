package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInviteRefusal
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.OwnConnectableServers
import build.raft.app.data.ServerConnectOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ConnectTraceId = "fedcba9876543210fedcba9876543210"

private fun ownServer(id: String, name: String, atLimit: Boolean = false) =
    InviteConnectableServer(id = id, name = name, slug = name.lowercase(), canConnectDirectly = true, atLimit = atLimit)

/**
 * "Connect your servers" in Invite people outside {server} (web `AddExternalMemberDialog`,
 * `ExternalInviteSteps`, `externalInvites.ts` `useOwnConnectableServers` /
 * `sendInvitesAndConnectServers`, botiverse/slock staging 97a3e293d71a).
 */
class ExternalInvitesServersStoreTest {
    private fun source(vararg servers: InviteConnectableServer) =
        FakeExternalInvitesDataSource().apply {
            ownServersAnswer = DataResult.Content(ExternalInviteAnswer.Ok(OwnConnectableServers(servers.toList())))
        }

    private fun TestScope.opened(source: FakeExternalInvitesDataSource, joint: Boolean = false): ExternalInvitesStore {
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.OpenDialog(joint = joint))
        runCurrent()
        return store
    }

    @Test
    fun yourServersAreReadOnOpenAndAJointChannelLeavesOutItsConnectedOnes() = runTest {
        val plain = source(ownServer("s2", "Beta"), ownServer("s3", "Gamma"))
        assertEquals(listOf("s2", "s3"), opened(plain).state.value.dialog?.servers?.map { it.id })
        assertEquals(1, plain.ownServersCalls)
        // Not joint: the channel's servers are not read.
        assertEquals(0, plain.connectedIdsCalls)

        val joint = source(ownServer("s2", "Beta"), ownServer("s3", "Gamma")).apply { connectedIds = setOf("s3") }
        assertEquals(listOf("s2"), opened(joint, joint = true).state.value.dialog?.servers?.map { it.id })
        // Their servers unknown: the list stays as it is (the server refuses a connected one).
        val unknown = source(ownServer("s2", "Beta")).apply { connectedIds = null }
        assertEquals(listOf("s2"), opened(unknown, joint = true).state.value.dialog?.servers?.map { it.id })
    }

    @Test
    fun onlyServersReviewWithoutAPreviewAndConnectWithoutASend() = runTest {
        val source = source(ownServer("s2", "Beta"), ownServer("s3", "Gamma"), ownServer("s4", "Delta"))
        source.connectAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Ok(
                    listOf(
                        ServerConnectOutcome.Connected("s2", "c2"),
                        ServerConnectOutcome.Connecting("s3"),
                        ServerConnectOutcome.Refused("s4", 409, "joint_party_limit", "too many"),
                    ),
                    traceId = ConnectTraceId,
                )
            )
        val store = opened(source)
        assertFalse(assertNotNull(store.state.value.dialog).canNext)
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s4", "s2", "s3")))
        assertTrue(assertNotNull(store.state.value.dialog).canNext)

        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        assertEquals(emptyList(), source.previewCalls)
        val review = assertNotNull(store.state.value.dialog)
        assertEquals(ExternalInvitePreview(), review.preview)
        // The list's order, not the order checked.
        assertEquals(listOf("s2", "s3", "s4"), review.chosenServers.map { it.id })
        assertEquals(3, review.actionCount)

        store.dispatch(ExternalInvitesIntent.Send(makePrivate = true))
        runCurrent()
        assertEquals(emptyList(), source.sendCalls)
        assertEquals(listOf(listOf("s2", "s3", "s4") to true), source.connectCalls)
        assertNull(store.state.value.dialog)
        assertEquals(
            listOf(
                ExternalInvitesEffect.ServersConnected(1),
                ExternalInvitesEffect.ServersConnecting(1),
                ExternalInvitesEffect.ServerRefused("Delta", ExternalInviteServerRefusal.PartyLimit, ConnectTraceId),
            ),
            store.state.value.effects,
        )
        // The invites are read again after the confirm.
        assertEquals(1, source.listCalls)
    }

    @Test
    fun emailsAndServersSendFirstThenConnectAndAFailedConnectKeepsTheSend() = runTest {
        val source = source(ownServer("s2", "Beta"))
        source.connectAnswer =
            DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(500, null, "boom", traceId = ConnectTraceId)))
        val store = opened(source)
        store.dispatch(ExternalInvitesIntent.SetText("new@example.com"))
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2")))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        assertEquals(listOf(listOf("new@example.com")), source.previewCalls)
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()

        assertEquals(listOf(listOf("new@example.com") to false), source.sendCalls)
        assertEquals(listOf(listOf("s2") to false), source.connectCalls)
        assertNull(store.state.value.dialog)
        assertEquals(
            listOf(ExternalInvitesEffect.Sent(1), ExternalInvitesEffect.Added(1), ExternalInvitesEffect.ServersNotConnected(ConnectTraceId)),
            store.state.value.effects,
        )
    }

    @Test
    fun emailsAndServersWholeRequestRefusalCarriesSpecificFailureOnServersNotConnected() = runTest {
        val source = source(ownServer("s2", "Beta"))
        val sentence = "This channel has 151 messages, which is more than can be moved to a shared channel right now."
        source.connectAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Refused(
                    ExternalInviteRefusal(409, "channel_conversion_too_large", sentence, traceId = ConnectTraceId)
                )
            )
        val store = opened(source)
        store.dispatch(ExternalInvitesIntent.SetText("new@example.com"))
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2")))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()

        assertNull(store.state.value.dialog)
        val expectedFailure =
            ExternalInviteFailure(
                error = ExternalInviteError.Generic,
                upgrade = false,
                traceId = ConnectTraceId,
                serverMessage = sentence,
            )
        assertEquals(
            listOf(
                ExternalInvitesEffect.Sent(1),
                ExternalInvitesEffect.Added(1),
                ExternalInvitesEffect.ServersNotConnected(ConnectTraceId, expectedFailure),
            ),
            store.state.value.effects,
        )
    }

    @Test
    fun serverOnlyWholeRequestRefusalClosesDialogAndEmitsServersNotConnected() = runTest {
        val source = source(ownServer("s2", "Beta"))
        source.connectAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Refused(
                    ExternalInviteRefusal(409, "free_external_limit", "Free plan limit reached.", traceId = ConnectTraceId)
                )
            )
        val store = opened(source)
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2")))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = true))
        runCurrent()

        assertNull(store.state.value.dialog, "server-only whole-request refusal must close the dialog like web")
        val expectedFailure =
            ExternalInviteFailure(
                error = ExternalInviteError.FreeLimit,
                upgrade = true,
                traceId = ConnectTraceId,
                serverMessage = null,
            )
        assertEquals(
            listOf(ExternalInvitesEffect.ServersNotConnected(ConnectTraceId, expectedFailure)),
            store.state.value.effects,
        )
    }

    @Test
    fun aRefusedSendConnectsNothingAndStaysInTheDialog() = runTest {
        val source = source(ownServer("s2", "Beta"))
        source.sendAnswer = DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(429, "daily_cap", "cap")))
        val store = opened(source)
        store.dispatch(ExternalInvitesIntent.SetText("new@example.com"))
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2")))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()
        assertEquals(emptyList(), source.connectCalls)
        assertEquals(ExternalInviteError.DailyCap, store.state.value.dialog?.failure?.error)
        assertEquals(emptyList(), store.state.value.effects)
    }

    @Test
    fun aServerAtTheFreeLimitIsListedNotChosen() = runTest {
        val store = opened(source(ownServer("s2", "Beta", atLimit = true)))
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2")))
        val dialog = assertNotNull(store.state.value.dialog)
        assertEquals(emptyList(), dialog.chosenServers)
        assertFalse(dialog.canNext)
    }

    @Test
    fun aGatedEntryOpensTheUpgradeWhenTheFreeExternalChannelIsTaken() = runTest {
        val taken = FakeExternalInvitesDataSource().apply { freeLimitAnswer = true }
        val store = ExternalInvitesStore(this, taken)
        store.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = true))
        runCurrent()
        assertNull(store.state.value.dialog)
        assertEquals(listOf<ExternalInvitesEffect>(ExternalInvitesEffect.FreeLimitUpgrade), store.state.value.effects)

        // Free, or a failed read: the dialog.
        for (answer in listOf(false, null)) {
            val source = FakeExternalInvitesDataSource().apply { freeLimitAnswer = answer }
            val open = ExternalInvitesStore(this, source)
            open.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = true))
            runCurrent()
            assertNotNull(open.state.value.dialog)
            assertEquals(1, source.freeLimitCalls)
        }

        // Not gated: no read.
        val ungated = FakeExternalInvitesDataSource().apply { freeLimitAnswer = true }
        val direct = ExternalInvitesStore(this, ungated)
        direct.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = false))
        runCurrent()
        assertNotNull(direct.state.value.dialog)
        assertEquals(0, ungated.freeLimitCalls)
        // A second tap keeps what the open dialog holds.
        direct.dispatch(ExternalInvitesIntent.SetText("kept@example.com"))
        direct.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = false))
        direct.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = true))
        runCurrent()
        assertEquals("kept@example.com", direct.state.value.dialog?.text)
        assertEquals(0, ungated.freeLimitCalls)
    }

    @Test
    fun prefetchFreeLimitOpensImmediatelyWhenFreeAndShowsLoadingOnReReadWhenTaken() = runTest {
        // Prefetched free (false): RequestOpen opens with zero additional network reads.
        val freeSource = FakeExternalInvitesDataSource().apply { freeLimitAnswer = false }
        val freeStore = ExternalInvitesStore(this, freeSource)
        freeStore.dispatch(ExternalInvitesIntent.PrefetchFreeLimit(gated = true))
        runCurrent()
        assertEquals(1, freeSource.freeLimitCalls)
        freeStore.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = true))
        runCurrent()
        assertNotNull(freeStore.state.value.dialog)
        assertEquals(1, freeSource.freeLimitCalls)
        assertFalse(freeStore.state.value.openingEntry)

        // Prefetched taken (true): RequestOpen re-reads with openingEntry = true before emitting upgrade.
        val gate = CompletableDeferred<Boolean?>()
        val takenSource = FakeExternalInvitesDataSource().apply { freeLimitAnswer = true }
        val takenStore = ExternalInvitesStore(this, takenSource)
        takenStore.dispatch(ExternalInvitesIntent.PrefetchFreeLimit(gated = true))
        runCurrent()
        assertEquals(1, takenSource.freeLimitCalls)
        takenSource.freeLimitDeferred = gate
        takenStore.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = true))
        runCurrent()
        assertTrue(takenStore.state.value.openingEntry)
        assertNull(takenStore.state.value.dialog)
        gate.complete(true)
        runCurrent()
        assertFalse(takenStore.state.value.openingEntry)
        assertEquals(2, takenSource.freeLimitCalls)
        assertEquals(listOf<ExternalInvitesEffect>(ExternalInvitesEffect.FreeLimitUpgrade), takenStore.state.value.effects)
    }

    @Test
    fun prefetchClearsStaleFalseOnRefetchAndUngatedSupersedesInFlightOpen() = runTest {
        // After a prefetched false, a new PrefetchFreeLimit(true) clears the stale cache immediately so
        // RequestOpen does not open with stale false while the fresh read is in flight.
        val source = FakeExternalInvitesDataSource().apply { freeLimitAnswer = false }
        val store = ExternalInvitesStore(this, source)
        store.dispatch(ExternalInvitesIntent.PrefetchFreeLimit(gated = true))
        runCurrent()
        assertEquals(1, source.freeLimitCalls)

        val refetchGate = CompletableDeferred<Boolean?>()
        source.freeLimitDeferred = refetchGate
        store.dispatch(ExternalInvitesIntent.PrefetchFreeLimit(gated = true))
        runCurrent()
        assertEquals(2, source.freeLimitCalls)

        val openGate = CompletableDeferred<Boolean?>()
        source.freeLimitDeferred = openGate
        store.dispatch(ExternalInvitesIntent.RequestOpen(joint = false, gated = true))
        runCurrent()
        assertTrue(store.state.value.openingEntry)
        assertNull(store.state.value.dialog)

        // Becoming ungated while RequestOpen is in flight must open the dialog instead of emitting a stale upgrade.
        store.dispatch(ExternalInvitesIntent.PrefetchFreeLimit(gated = false))
        openGate.complete(true)
        refetchGate.complete(true)
        runCurrent()
        assertFalse(store.state.value.openingEntry)
        assertNotNull(store.state.value.dialog)
        assertEquals(emptyList(), store.state.value.effects)
    }

    @Test
    fun mutationsNotifyChannelRefreshAndBusyIgnoresSetText() = runTest {
        val source = source(ownServer("s2", "Beta"))
        source.connectAnswer = DataResult.Content(ExternalInviteAnswer.Ok(listOf(ServerConnectOutcome.Connected("s2", "c2"))))
        val mutations = mutableListOf<Boolean>()
        val store = ExternalInvitesStore(this, source, onChannelMutated = { mutations += it })
        store.dispatch(ExternalInvitesIntent.Load)
        store.dispatch(ExternalInvitesIntent.OpenDialog(joint = false))
        runCurrent()
        store.dispatch(ExternalInvitesIntent.SetText("new@example.com"))
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2")))
        val previewGate = CompletableDeferred<DataResult<ExternalInviteAnswer<ExternalInvitePreview>>>()
        source.previewDeferred = previewGate
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        assertTrue(store.state.value.dialog?.busy == true)
        store.dispatch(ExternalInvitesIntent.SetText("ignored@example.com"))
        assertEquals("new@example.com", store.state.value.dialog?.text)
        previewGate.complete(source.previewAnswer)
        runCurrent()
        store.dispatch(ExternalInvitesIntent.Send(makePrivate = false))
        runCurrent()
        assertEquals(listOf(true), mutations)

        store.dispatch(ExternalInvitesIntent.RequestWithdraw("i1"))
        store.dispatch(ExternalInvitesIntent.ConfirmWithdraw)
        runCurrent()
        assertEquals(listOf(true, false), mutations)
    }
}
