package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A toolbar row's field and trigger (web Artifacts home on a phone): raft-ui 0.78 InputGroup
 * `size="md"` is a picker trigger's 32dp, and a picker trigger with no label (`max-md:hidden`)
 * keeps one 6dp gap between its icon and chevron, as a flex row skips the hidden label.
 */
@OptIn(ExperimentalTestApi::class)
class RaftToolbarControlSizeTest {
    private fun assertNear(expected: Dp, actual: Dp, what: String) =
        assertTrue(abs(expected.value - actual.value) < 0.5f, "$what: expected $expected, was $actual")

    @Test
    fun aMdFieldIsAPickerTriggersHeightAndLgStaysTheControlHeight() =
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    androidx.compose.foundation.layout.Column {
                        RaftTextField("", {}, Modifier.width(200.dp).testTag("md"), placeholder = "Search", leading = { Box(Modifier.size(14.dp)) }, size = RaftTextFieldSize.Md)
                        RaftTextField("", {}, Modifier.width(200.dp).testTag("lg"), placeholder = "Search")
                        RaftPickerTriggerButton(text = "Sort", onClick = {}, modifier = Modifier.testTag("trigger"))
                    }
                }
            }
            val md = onNodeWithTag("md", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val lg = onNodeWithTag("lg", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val trigger = onNodeWithTag("trigger").getUnclippedBoundsInRoot()
            assertNear(32.dp, md.bottom - md.top, "md field")
            assertNear(trigger.bottom - trigger.top, md.bottom - md.top, "md field vs trigger")
            assertTrue(lg.bottom - lg.top > md.bottom - md.top, "lg is the taller control height")
        }

    @Test
    fun aTriggerWithNoLabelKeepsOneGapBetweenItsMarks() =
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    RaftPickerTriggerButton(text = "", onClick = {}, modifier = Modifier.testTag("icon"), leadingIcon = { Box(Modifier.size(14.dp)) })
                }
            }
            val icon = onNodeWithTag("icon").getUnclippedBoundsInRoot()
            // 10dp ends, the 14dp icon, one 6dp gap, the 14dp chevron.
            assertNear(10.dp + 14.dp + 6.dp + 14.dp + 10.dp, icon.right - icon.left, "icon-only trigger width")
        }
}
