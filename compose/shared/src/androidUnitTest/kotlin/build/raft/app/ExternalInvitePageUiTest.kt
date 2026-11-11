package build.raft.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteForInvitee
import build.raft.app.data.InviteServerSelection
import build.raft.app.data.InviteeAnswer
import build.raft.app.data.InviteeRefusal
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
 * The external invite page on a real composition, wired as the page wires it (the store's state
 * into [ExternalInviteContent]): the ways in, joining as a guest, connecting a server, and "isn't
 * for this account" with Switch account (#13942).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ExternalInvitePageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.show(source: FakeExternalInviteDataSource): ExternalInviteStore {
        val store = ExternalInviteStore(CoroutineScope(Dispatchers.Main.immediate), source)
        store.dispatch(ExternalInviteIntent.SetMyServers(listOf(InviteMyServer("s1", "Contoso", "contoso", "free"))))
        store.dispatch(ExternalInviteIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme { ExternalInviteContent(state = state, email = "lena@contoso.com", onIntent = store::dispatch) }
        }
        waitForIdle()
        return store
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun anInviteForAnotherAddressSaysSoAndOffersSwitchAccount() = runRobolectricComposeUiTest {
        val source = FakeExternalInviteDataSource()
        source.page = DataResult.Content(InviteeAnswer.Ok(ExternalInviteForInvitee(addressedToYou = false, invitedEmailMasked = "s•••@fabrikam.com")))
        val store = show(source)
        assertEquals(1, count(ExternalInviteWrongAccountTag))
        assertTrue(shown("This invite isn't for this account"))
        assertTrue(shown("This invite was sent to s•••@fabrikam.com. Sign in with that address to accept it."))
        assertTrue(shown("Signed in as lena@contoso.com"))
        onNodeWithTag(ExternalInviteSwitchAccountTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf<ExternalInviteEffect>(ExternalInviteEffect.SignOut), store.state.value.effects)
    }

    @Test
    fun anInvalidLinkSaysSo() = runRobolectricComposeUiTest {
        val source = FakeExternalInviteDataSource()
        source.page = DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(404)))
        show(source)
        assertTrue(shown("This invite link is not valid"))
        assertTrue(shown("Go to Raft"))
    }

    @Test
    fun theWaysInLeadToJoiningAsAGuest() = runRobolectricComposeUiTest {
        val source = FakeExternalInviteDataSource()
        val store = show(source)
        assertTrue(shown("Join #launch"))
        assertTrue(shown("Lena invited you from Acme."))
        assertEquals(1, count(ExternalInviteOptionGuestTag))
        assertEquals(1, count(ExternalInviteOptionConnectTag))
        assertEquals(1, count(ExternalInviteOptionNewServerTag))

        onNodeWithTag(ExternalInviteOptionGuestTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("You'll join #launch in Acme"))
        onNodeWithTag(ExternalInviteCommitTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(ExternalInviteEffect.OpenChannel("host", "c1"), store.state.value.effects.last())
    }

    @Test
    fun connectingAServerListsYoursAndARefusalShowsItsTraceId() = runRobolectricComposeUiTest {
        val source = FakeExternalInviteDataSource()
        source.connectAnswer =
            DataResult.Content(InviteeAnswer.Refused(InviteeRefusal(409, "already_connected", traceId = "0123456789abcdef0123456789abcdef")))
        show(source)
        onNodeWithTag(ExternalInviteOptionConnectTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("Needs approval"))
        assertTrue(shown("At external channel limit"))
        onNodeWithText("Contoso", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("Connect Contoso"))
        onNodeWithTag(ExternalInviteCommitTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(listOf(InviteServerSelection("s1"))), source.connectCalls)
        assertEquals(1, count(ExternalInviteAcceptErrorTag))
        assertTrue(shown("Contoso is already connected to #launch."))
        assertTrue(shown("01234567"))
    }
}
