package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * raft-ui 0.102.1 (raft-design #1291): in a message column at least 768dp wide the sender line keeps
 * its last fifth empty, so a long description stops at 80%; a narrower column keeps the full width.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageHeaderWideEndRoomUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /**
     * The width the header's content is given inside a column [column] wide (a child that fills
     * whatever it gets), in dp. Measured on the modifier itself: a fixture text's own width under
     * Robolectric doesn't reliably fill the line.
     */
    private fun contentWidth(column: Dp): Float {
        var width = Float.NaN
        runRobolectricComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    // Wider than the test window: required, so the column is exactly [column].
                    Box(Modifier.requiredWidth(column)) {
                        Box(Modifier.fillMaxWidth().threadMessageHeaderWideEndRoom()) {
                            Box(Modifier.fillMaxWidth().height(10.dp).onGloballyPositioned { width = it.size.width / density.density })
                        }
                    }
                }
            }
            waitForIdle()
        }
        return width
    }

    @Test
    fun aWideColumnsSenderLineStopsAt80Percent() {
        val width = contentWidth(1000.dp)
        assertEquals(800f, width, 1f, "a 1000dp column leaves its last fifth empty")
    }

    @Test
    fun theThresholdIs768dp() {
        assertEquals(768f * 0.8f, contentWidth(768.dp), 1f, "768dp is already wide")
        assertEquals(760f, contentWidth(760.dp), 1f, "760dp keeps the full width")
    }

    @Test
    fun aNarrowColumnsSenderLineKeepsTheFullWidth() {
        assertEquals(700f, contentWidth(700.dp), 1f, "a 700dp column keeps the full width")
    }
}
