package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ChannelExternalInviteList
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.ExternalInviteRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invite people outside {server} and the Invited group on a real composition, wired as the members
 * page wires them (the store's state into [ExternalInviteRow], [ExternalInviteSheet] and
 * [ExternalInviteWithdrawDialog]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ExternalInvitesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val now = 1_763_337_600_000L // 2025-11-17T00:00:00Z

    private fun ComposeUiTest.show(source: FakeExternalInvitesDataSource, canResend: Boolean = true): ExternalInvitesStore {
        val store = ExternalInvitesStore(CoroutineScope(Dispatchers.Main.immediate), source)
        store.dispatch(ExternalInvitesIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                Column {
                    ExternalInviteEntryRow(serverName = "Acme", enabled = true, onClick = { store.dispatch(ExternalInvitesIntent.OpenDialog) })
                    state.invites.forEachIndexed { index, invite ->
                        ExternalInviteRow(
                            invite = invite,
                            nowEpochMillis = now,
                            canResend = canResend,
                            busy = invite.id in state.rowBusy,
                            divider = index > 0,
                            onResend = { store.dispatch(ExternalInvitesIntent.Resend(invite.id)) },
                            onWithdraw = { store.dispatch(ExternalInvitesIntent.RequestWithdraw(invite.id)) },
                        )
                    }
                }
                state.dialog?.let { dialog ->
                    ExternalInviteSheet(
                        dialog = dialog,
                        serverName = "Acme",
                        willBecomePrivate = true,
                        joint = false,
                        channelSlotsLeft = state.slotsLeft,
                        onDismiss = { store.dispatch(ExternalInvitesIntent.CloseDialog) },
                        onTextChange = { store.dispatch(ExternalInvitesIntent.SetText(it)) },
                        onNext = { store.dispatch(ExternalInvitesIntent.Next) },
                        onBack = { store.dispatch(ExternalInvitesIntent.Back) },
                        onSend = { store.dispatch(ExternalInvitesIntent.Send(makePrivate = true)) },
                        onUpgrade = { store.dispatch(ExternalInvitesIntent.CloseDialog) },
                    )
                }
                state.withdrawConfirm?.let { invite ->
                    ExternalInviteWithdrawDialog(
                        invite = invite,
                        busy = state.withdrawBusy,
                        failure = state.withdrawFailure,
                        joint = false,
                        onDismiss = { store.dispatch(ExternalInvitesIntent.DismissWithdraw) },
                        onConfirm = { store.dispatch(ExternalInvitesIntent.ConfirmWithdraw) },
                    )
                }
            }
        }
        waitForIdle()
        return store
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun eachInviteSaysWhereItStandsAndOffersResendOnlyWhenPending() = runRobolectricComposeUiTest {
        val source = FakeExternalInvitesDataSource()
        source.listAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Ok(
                    ChannelExternalInviteList(
                        listOf(
                            externalTestInvite("i1", "a@example.com", expiresAt = "2025-12-01T00:00:00.000Z"),
                            externalTestInvite("i2", "b@example.com", awaitingApproval = true),
                            externalTestInvite("i3", "c@example.com", connecting = true, revocable = false),
                        )
                    )
                )
            )
        show(source)
        assertTrue(shown("14 days left"))
        assertTrue(shown("Waiting for approval"))
        assertTrue(shown("Connecting a server"))
        assertEquals(3, count(ExternalInviteRowTag))
        assertEquals(1, count(ExternalInviteResendTag))
        onAllNodes(hasTestTag(ExternalInviteWithdrawTag), useUnmergedTree = true)[2].assertIsNotEnabled()

        onNodeWithTag(ExternalInviteResendTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("i1"), source.resendCalls)
    }

    @Test
    fun withdrawAsksFirstAndKeepsARefusalInTheDialogWithItsTraceId() = runRobolectricComposeUiTest {
        val source = FakeExternalInvitesDataSource()
        source.revokeAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Refused(ExternalInviteRefusal(409, "connecting", null, traceId = "0123456789abcdef0123456789abcdef"))
            )
        show(source)
        onNodeWithTag(ExternalInviteWithdrawTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("The invite link sent to a@example.com stops working."))
        onNodeWithTag(ExternalInviteWithdrawConfirmTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("i1"), source.revokeCalls)
        assertTrue(shown("A server is being connected to this channel."))
        assertTrue(shown("01234567"))
    }

    @Test
    fun theSheetReviewsTheEmailsThenInvites() = runRobolectricComposeUiTest {
        val source = FakeExternalInvitesDataSource()
        source.previewAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Ok(
                    ExternalInvitePreview(valid = listOf("new@example.com"), alreadyPending = listOf("a@example.com"), invalid = listOf("x"), slotsLeft = 28)
                )
            )
        val store = show(source)
        onNodeWithTag(ExternalInviteEntryTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, count(ExternalInviteSheetTag))
        assertTrue(shown("Invite people outside Acme"))
        assertEquals(1, count(ExternalInvitePrivateWarningTag))

        runOnIdle { store.dispatch(ExternalInvitesIntent.SetText("new@example.com a@example.com x")) }
        waitForIdle()
        onNodeWithText("Next", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(3, count(ExternalInviteReviewRowTag))
        assertTrue(shown("Will be invited"))
        assertTrue(shown("Already invited · no new invite"))
        assertTrue(shown("Not a valid email · no invite"))

        onNodeWithText("Invite", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(listOf("new@example.com") to true), source.sendCalls)
        assertEquals(0, count(ExternalInviteSheetTag))
    }

    @Test
    fun theFreePlanLimitOffersUpgradeWithTheTraceId() = runRobolectricComposeUiTest {
        val source = FakeExternalInvitesDataSource()
        source.previewAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Refused(
                    ExternalInviteRefusal(409, "free_external_limit", null, limitSide = "host", traceId = "0123456789abcdef0123456789abcdef")
                )
            )
        val store = show(source)
        runOnIdle {
            store.dispatch(ExternalInvitesIntent.OpenDialog)
            store.dispatch(ExternalInvitesIntent.SetText("new@example.com"))
        }
        waitForIdle()
        onNodeWithText("Next", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, count(ExternalInviteErrorTag))
        assertTrue(shown("On the Free plan, a server can share one channel with people outside it."))
        assertEquals(1, count(ExternalInviteUpgradeTag))
        assertTrue(shown("01234567"))
    }
}
