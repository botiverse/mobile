package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InviteOpenChannel
import build.raft.app.data.InvitePublicChannel
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invite acceptance page UI tests (slock #16635 / #16643): guest copy, no-guest description,
 * host member alreadyInChannel + Open #channel, and publicChannel rules.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ExternalInvitePageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.mount(
        initial: ExternalInviteState,
        onIntent: (ExternalInviteIntent) -> Unit = {},
    ): MutableState<ExternalInviteState> {
        val state = mutableStateOf(initial)
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    ExternalInviteContent(state = state.value, email = "lena@contoso.com", onIntent = onIntent)
                }
            }
        }
        waitForIdle()
        return state
    }

    @Test
    fun optionsShowsUpdatedGuestCopyAndNoGuestDescription() = runRobolectricComposeUiTest {
        val state = mount(ExternalInviteState(invite = inviteTestPage(), step = InviteStep.Options))
        assertTrue(shown("Join just this channel yourself, including its earlier messages."))

        state.value = ExternalInviteState(invite = inviteTestPage(guest = false), step = InviteStep.Options)
        waitForIdle()
        assertTrue(shown("Lena invited you from Acme. You can't join as a guest, so join with a server instead."))
        assertEquals(0, count(ExternalInviteOptionGuestTag))
        assertEquals(1, count(ExternalInviteOptionConnectTag))
        assertEquals(1, count(ExternalInviteOptionNewServerTag))
    }

    @Test
    fun hostMemberAlreadyInChannelShowsInChannelTitleAndOpenChannelLink() = runRobolectricComposeUiTest {
        val intents = mutableListOf<ExternalInviteIntent>()
        mount(
            ExternalInviteState(
                invite =
                    inviteTestPage(guest = false).copy(
                        alreadyInChannel = true,
                        openChannel = InviteOpenChannel("host-1", "ch-launch", "launch"),
                    ),
                step = InviteStep.Options,
            ),
            onIntent = { intents += it },
        )
        assertTrue(shown("You're in #launch"))
        assertTrue(shown("To bring your teammates and agents too, connect a server."))
        assertEquals(1, count(ExternalInviteOptionConnectTag))
        assertEquals(1, count(ExternalInviteOpenChannelTag))
        assertTrue(shown("Open #launch"))

        onNodeWithTag(ExternalInviteOpenChannelTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf<ExternalInviteIntent>(ExternalInviteIntent.OpenInChannel), intents)
    }

    @Test
    fun publicRefusedHidesWaysInAndPromotesOpenChannelButton() = runRobolectricComposeUiTest {
        val intents = mutableListOf<ExternalInviteIntent>()
        mount(
            ExternalInviteState(
                invite =
                    inviteTestPage(guest = false).copy(
                        alreadyInChannel = true,
                        openChannel = InviteOpenChannel("host-1", "ch-launch", "launch"),
                        publicChannel = InvitePublicChannel(canMakePrivate = false),
                    ),
                step = InviteStep.Options,
            ),
            onIntent = { intents += it },
        )
        assertEquals(1, count(ExternalInvitePublicRefusedTag))
        assertTrue(shown("#launch is public. Ask an owner or admin to make it private first."))
        assertEquals(0, count(ExternalInviteOptionConnectTag))
        assertEquals(0, count(ExternalInviteOptionNewServerTag))
        assertEquals(1, count(ExternalInviteOpenChannelTag))
        onNodeWithTag(ExternalInviteOpenChannelTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf<ExternalInviteIntent>(ExternalInviteIntent.OpenInChannel), intents)
    }

    @Test
    fun publicChannelConnectAndNewServerShowBecomesPrivateBannerAndApprovalNote() = runRobolectricComposeUiTest {
        val publicInvite = inviteTestPage(guest = false).copy(publicChannel = InvitePublicChannel(canMakePrivate = true))
        val state = mount(ExternalInviteState(invite = publicInvite, step = InviteStep.Connect))
        assertEquals(1, count(ExternalInviteBecomesPrivateTag))
        assertTrue(shown("#launch will become private once connected."))
        assertEquals(1, count(ExternalInviteConnectPublicApprovalTag))
        assertTrue(shown("You'd need approval from Fabrikam, and #launch is public."))
        assertTrue(shown("Needs approval"))
        assertFalse(shown("Approve"))

        state.value = ExternalInviteState(invite = publicInvite, step = InviteStep.NewServer)
        waitForIdle()
        assertEquals(1, count(ExternalInviteBecomesPrivateTag))
        assertTrue(shown("#launch will become private once connected."))
    }
}
