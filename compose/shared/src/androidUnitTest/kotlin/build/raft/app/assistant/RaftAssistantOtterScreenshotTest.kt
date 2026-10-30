package build.raft.app.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppAppearance
import build.raft.app.RaftRobolectricScreenshot
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The assistant's otter (web assistant/AssistantOtter.tsx) on a sample composer frame's top edge,
 * phone scale and inset, one frame per pose: idle (curious), working (thinking), new (wave + its
 * bubble), confirm (confused + its bubble), away (sleepy, no paws), the pop-in's running pose,
 * and idle slid into a composer that holds text (paws gone). Light and dark.
 *
 * Goldens: `src/androidUnitTest/screenshots/<name>.png`, recorded through CI
 * (`test.yml` with `record_screenshots=true`); verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h880dp-xxhdpi")
class RaftAssistantOtterScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun posesLight() = capture("assistant.otter.poses", RaftAppAppearance.Light)

    @Test fun posesDark() = capture("assistant.otter.poses.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(horizontal = 8.dp)) {
                    RaftOtterMood.entries.forEach { mood ->
                        SampleComposer {
                            RaftAssistantOtter(mood, RaftOtterTuck.None, onClick = {}, modifier = Modifier.fillMaxWidth(), animateEntrance = false)
                        }
                    }
                    SampleComposer {
                        RaftAssistantOtterLayout(
                            pose = raftOtterPose(RaftOtterMood.Idle, entering = true),
                            paws = raftOtterPaws(RaftOtterMood.Idle, entering = true),
                            bubble = null,
                            bubbleDot = false,
                            tuck = RaftOtterTuck.None,
                            label = "Open Assistant",
                            onClick = {},
                            scale = RaftAssistantOtterDefaults.PhoneScale,
                            endInset = RaftAssistantOtterDefaults.PhoneEndInset,
                            reducedMotion = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SampleComposer {
                        RaftAssistantOtter(RaftOtterMood.Idle, RaftOtterTuck.Composing, onClick = {}, modifier = Modifier.fillMaxWidth(), animateEntrance = false)
                    }
                }
            }
        }

    /** Room for the otter above, then a composer-like frame whose box carries the otter on its top edge. */
    @Composable
    private fun SampleComposer(otter: @Composable () -> Unit) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(RaftRadius.Container)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(RaftTheme.colors.layerFrame, shape)
                    .border(RaftStroke.Control, RaftTheme.colors.lineField, shape),
            )
            otter()
        }
    }

    private companion object {
        const val ReportDir = "build/reports/home-screenshots"
    }
}
