package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Web #10285: a reply thread header's meta line with the channel name as a link, at rest (top) and
 * under a mouse pointer (bottom: underlined). Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
class ThreadHeaderChannelLinkScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun light() = capture("thread.header.channel-link", RaftAppAppearance.Light)

    @Test
    fun dark() = capture("thread.header.channel-link.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme { ChannelLinkSubtitles() }
        }
}

@Composable
private fun ChannelLinkSubtitles() {
    val hovered = remember { MutableInteractionSource() }
    LaunchedEffect(hovered) { hovered.emit(HoverInteraction.Enter()) }
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ThreadHeaderChannelLinkSubtitle(channelLabel = "#wg-deck", replyCountLabel = "313 replies", onOpenInChannel = {})
        ThreadHeaderChannelLinkSubtitle(
            channelLabel = "#wg-deck",
            replyCountLabel = "313 replies",
            onOpenInChannel = {},
            interactionSource = hovered
        )
    }
}
