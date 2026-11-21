package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInviteRefusal
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.OwnConnectableServers
import build.raft.app.data.ServerConnectOutcome
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
    fun aFailedReadDrawsNoServers() = runTest {
        val failed =
            FakeExternalInvitesDataSource().apply {
                ownServersAnswer = DataResult.Content(ExternalInviteAnswer.Refused(ExternalInviteRefusal(404, null, "Not found")))
            }
        assertEquals(emptyList(), opened(failed).state.value.dialog?.servers)
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
    fun checkedServersTakeASlotEach() = runTest {
        val source = source(ownServer("s2", "Beta"), ownServer("s3", "Gamma"))
        source.previewAnswer = DataResult.Content(ExternalInviteAnswer.Ok(ExternalInvitePreview(valid = listOf("a@example.com"), slotsLeft = 2)))
        val store = opened(source)
        store.dispatch(ExternalInvitesIntent.SetText("a@example.com"))
        store.dispatch(ExternalInvitesIntent.SetServers(setOf("s2", "s3")))
        store.dispatch(ExternalInvitesIntent.Next)
        runCurrent()
        val review = assertNotNull(store.state.value.dialog)
        assertEquals(1, review.overSlots)
        assertFalse(review.canSend)
        assertEquals(ExternalInviteSlotsLine.Over(room = 2, count = 1), externalInviteTypedSlotsLine(channelSlotsLeft = 2, entries = 1, servers = 2))
        // While typing nothing is said before an email is typed (web `typedSlotsHint`).
        assertNull(externalInviteTypedSlotsLine(channelSlotsLeft = 2, entries = 0, servers = 2))
    }

    @Test
    fun aRefusedServerIsWordedAsTheWebToastsIt() {
        fun refused(code: String?, side: String? = null, limitServerId: String? = null, status: Int = 409) =
            externalInviteServerRefusal(ServerConnectOutcome.Refused("s2", status, code, "x", limitSide = side, limitServerId = limitServerId))
        assertEquals(ExternalInviteServerRefusal.HostFreeLimit, refused("free_external_limit", side = "host"))
        assertEquals(ExternalInviteServerRefusal.HostFreeLimit, refused("joint_channel_plan_limit_reached"))
        assertEquals(ExternalInviteServerRefusal.PartyLimit, refused("joint_party_limit"))
        assertEquals(
            ExternalInviteServerRefusal.Invitee(InviteeMessage.TargetFreeLimit),
            refused("free_external_limit", side = "connecting", limitServerId = "s2"),
        )
        assertEquals(ExternalInviteServerRefusal.Invitee(InviteeMessage.AlreadyConnected), refused("already_connected"))
        assertEquals(ExternalInviteServerRefusal.Invitee(InviteeMessage.TooManyAttempts), refused(null, status = 429))
        assertEquals(ExternalInviteServerRefusal.Invitee(InviteeMessage.Generic), refused("internal_error", status = 500))
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
    fun onlyAFreeServersChannelNotHoldingTheSlotIsGated() {
        fun channel(type: String, external: Boolean = false) =
            ExternalInviteChannel(type = type, name = "c", archived = false, jointRole = "host", hasExternalRelation = external)
        assertTrue(externalInviteEntryGated("free", channel("private")))
        assertTrue(externalInviteEntryGated("free", channel("channel")))
        assertFalse(externalInviteEntryGated("free", channel("private", external = true)))
        assertFalse(externalInviteEntryGated("free", channel("joint")))
        assertFalse(externalInviteEntryGated("pro", channel("private")))
        assertFalse(externalInviteEntryGated(null, channel("private")))
    }
}
