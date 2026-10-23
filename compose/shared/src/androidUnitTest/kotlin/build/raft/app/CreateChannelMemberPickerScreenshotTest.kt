package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of create channel's Members field (`create-channel.members.*`), light and
 * dark: the shared [RaftMemberPicker] with an empty search and two members checked, and a
 * search that matches nothing. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h460dp-xxhdpi")
class CreateChannelMemberPickerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val picked =
        setOf(MemberPickerTestFixtures.roster[0].memberPickerKey, MemberPickerTestFixtures.roster[4].memberPickerKey)

    @Test
    fun selectedLight() = capture("create-channel.members", RaftAppAppearance.Light, "")

    @Test
    fun selectedDark() = capture("create-channel.members.dark", RaftAppAppearance.Dark, "")

    @Test
    fun noMatchesLight() = capture("create-channel.members.no-matches", RaftAppAppearance.Light, "zzz")

    @Test
    fun noMatchesDark() = capture("create-channel.members.no-matches.dark", RaftAppAppearance.Dark, "zzz")

    private fun capture(name: String, appearance: RaftAppAppearance, query: String) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            // The app shell provides the design-system theme the field label reads.
            RaftDesignSystemTheme { Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(24.dp)) {
                SharedCreateChannelMemberPicker(
                    loading = false,
                    members = MemberPickerTestFixtures.roster,
                    query = TextFieldValue(query),
                    onQueryChange = {},
                    selectedKeys = picked,
                    enabled = true,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    fixtureStatusFallbackEnabled = true,
                    onToggle = {},
                )
            } }
        }
    }
}
