package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.LocalRaftTooltipPreview
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.theme.RaftAppearance
import kotlin.test.Test

/**
 * A tooltip kept out of the host's top frame inset ([LocalRaftFrameInsets], raft-ui #860): its
 * button sits 8dp under a 64dp band (the grey stand-in), where Material would open it above, into
 * the band; it opens below instead, its caret on its top edge pointing up at the button.
 */
class FrameInsetsScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun tooltipBelowTopInset() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("frame_inset_tooltip_${appearance.name.lowercase()}", appearance, settleFrames = 2) {
                CompositionLocalProvider(
                    LocalRaftTooltipPreview provides true,
                    LocalRaftFrameInsets provides RaftFrameInsets(top = 64.dp),
                ) {
                    Box(Modifier.width(320.dp).height(200.dp)) {
                        Box(Modifier.fillMaxWidth().height(64.dp).background(Color(0x33808080)))
                        RaftIconButton(onClick = {}, tooltip = "Notifications", modifier = Modifier.padding(start = 140.dp, top = 72.dp)) {
                            RaftGlyphIcon(RaftGlyph.Info, size = 20.dp)
                        }
                    }
                }
            }
        }
}
