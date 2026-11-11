package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ExternalInviteForInvitee
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the external invite page (`external-invite.*`): the ways in, the connect
 * step and "isn't for this account" (#13942). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ExternalInvitePageScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val myServers = listOf(InviteMyServer("s1", "Contoso", "contoso", "free"))

    private val options = ExternalInviteState(invite = inviteTestPage(), step = InviteStep.Options, myServers = myServers)

    private val connect =
        options.copy(step = InviteStep.Connect, pickedServerIds = listOf("s1"))

    private val wrongAccount =
        ExternalInviteState(
            invite = ExternalInviteForInvitee(addressedToYou = false, invitedEmailMasked = "s•••@fabrikam.com"),
            step = InviteStep.WrongAccount("s•••@fabrikam.com"),
        )

    @Test
    fun optionsLight() = capture("external-invite.options", RaftAppAppearance.Light, options)

    @Test
    fun optionsDark() = capture("external-invite.options.dark", RaftAppAppearance.Dark, options)

    @Test
    fun connectLight() = capture("external-invite.connect", RaftAppAppearance.Light, connect)

    @Test
    fun wrongAccountLight() = capture("external-invite.wrong-account", RaftAppAppearance.Light, wrongAccount)

    private fun capture(name: String, appearance: RaftAppAppearance, state: ExternalInviteState) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                    ExternalInviteContent(state = state, email = "lena@contoso.com", onIntent = {})
                }
            }
        }
    }
}
