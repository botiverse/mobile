package build.raft.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftMobileNavDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A phone tab is 45dp tall, as web MobileNavItem (#proj-mobile:#321): the bar's bottom padding is the
 * bar's, under the items, so a tab's target ends with its label.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h400dp")
class BottomTabItemHeightUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aTabIsTheWebsFortyFiveDpAboveTheBarsPadding() = runRobolectricComposeUiTest {
        setContent {
            RaftDesignSystemTheme {
                RaftBottomTabBar(
                    items = listOf(
                        RaftBottomTabItemPayload(key = "home", label = "Home", selected = true),
                        RaftBottomTabItemPayload(key = "activity", label = "Activity"),
                    ),
                    onSelect = {},
                )
            }
        }
        waitForIdle()
        val tabs = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        assertEquals(2, tabs.size)
        tabs.forEach { tab ->
            val height = with(tab.layoutInfo.density) { tab.size.height.toDp() }
            assertEquals(45f, height.value, 0.5f, "tab height")
        }
        assertEquals(52.dp, RaftMobileNavDefaults.ContentHeight)
    }
}
