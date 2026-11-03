package build.raft.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rail's tabs reordered on a real composition, wired as SettingsHomePage wires them (the
 * order the rail reports goes back in as its items): a tap opens the tab and moves nothing; a
 * finger or a mouse dragged past 6dp moves the tab and opens nothing. mdpi: 1dp = 1px, so an
 * item's slot is 40 + 6.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftRailTabReorderUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val selected = mutableListOf<String>()
    private val commits = mutableListOf<List<String>>()

    @Test
    fun aTapOpensTheTabAndMovesNothing() =
        runRobolectricComposeUiTest {
            setContent { Rail() }

            tab("tasks").performClick()
            waitForIdle()

            assertEquals(listOf("tasks"), selected)
            assertEquals(emptyList(), commits)
        }

    @Test
    fun aFingerDraggedPastTheThresholdMovesTheTabAndOpensNothing() =
        runRobolectricComposeUiTest {
            setContent { Rail() }

            tab("home").performTouchInput {
                down(center)
                moveBy(Offset(0f, 8f))
                moveBy(Offset(0f, 46f))
                moveBy(Offset(0f, 46f))
                up()
            }
            waitForIdle()

            assertEquals(listOf(listOf("activity", "tasks", "home", "members")), commits)
            assertEquals(emptyList(), selected)
            assertTrue(tab("home").top() > tab("tasks").top(), "the dropped tab shows in its new slot")
        }

    @Test
    fun aMouseDraggedPastTheThresholdMovesTheTabAndOpensNothing() =
        runRobolectricComposeUiTest {
            setContent { Rail() }

            tab("members").performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(0f, -8f))
                moveBy(Offset(0f, -46f))
                release()
            }
            waitForIdle()

            assertEquals(listOf(listOf("home", "activity", "members", "tasks")), commits)
            assertEquals(emptyList(), selected)
        }

    @androidx.compose.runtime.Composable
    private fun Rail() {
        var order by remember { mutableStateOf(listOf("home", "activity", "tasks", "members")) }
        Row(Modifier.fillMaxSize()) {
            RaftNavigationRailTabBar(
                items = order.map { key -> RaftBottomTabItemPayload(key, key, RaftLucideIconName.Circle, selected = key == "home") },
                onSelect = { selected += it },
                modifier = Modifier.fillMaxHeight(),
                reorder =
                    remember {
                        RaftRailTabReorder { next ->
                            commits += next
                            order = next
                        }
                    },
            )
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.tab(key: String): SemanticsNodeInteraction =
        onNodeWithTag(RaftRailReorderableTabTagPrefix + key)

    private fun SemanticsNodeInteraction.top(): Float = getBoundsInRoot().top.value
}
