package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertTrue
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

    /** Where the line's last part (the time, after a sender cluster long enough to fill the line) ends, in dp from the column's start. */
    private fun timeEnd(column: Dp): Float {
        var start = Float.NaN
        var end = Float.NaN
        runRobolectricComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    // Wider than the test window: measured from the column's own start.
                    Box(Modifier.requiredWidth(column).onGloballyPositioned { start = it.positionInRoot().x / density.density }) {
                        ThreadMessageHeaderRow(
                            alignEnd = false,
                            modifier = Modifier.fillMaxWidth(),
                            time = { timeModifier ->
                                RaftText(
                                    "4:40 PM",
                                    timeModifier.onGloballyPositioned { end = (it.positionInRoot().x + it.size.width) / density.density },
                                    style = RaftTheme.typography.monoMeta,
                                )
                            },
                            meta = {
                                RaftText(
                                    "A very long description ".repeat(20),
                                    Modifier.weight(1f, fill = false).alignByBaseline(),
                                    style = RaftTheme.typography.caption,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        ) { inCluster, nameText ->
                            RaftText("Alice", inCluster.then(nameText), style = RaftTheme.typography.subhead)
                        }
                    }
                }
            }
            waitForIdle()
        }
        return end - start
    }

    @Test
    fun aWideColumnsSenderLineStopsAt80Percent() {
        val end = timeEnd(1000.dp)
        assertTrue(end <= 800f + 1f, "the line ends by 80% of a 1000dp column, not at ${end}dp")
        assertTrue(end >= 780f, "the long line still fills its 80% (${end}dp)")
    }

    @Test
    fun aNarrowColumnsSenderLineKeepsTheFullWidth() {
        val end = timeEnd(700.dp)
        assertTrue(end > 700f * 0.8f + 20f, "a 700dp column keeps the full width (${end}dp)")
    }
}
