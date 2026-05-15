package ai.slock.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import ai.slock.android.ui.components.SlockAppBottomBarHost
import ai.slock.android.ui.components.SlockBottomTabItem
import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Rule
import org.junit.Test

class AppBottomBarHostTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appBottomBarHostIsPinnedToRootBottom() {
        composeRule.setContent {
            SlockTheme {
                Box(Modifier.fillMaxSize()) {
                    SlockAppBottomBarHost(
                        items =
                            listOf(
                                SlockBottomTabItem("home", "HOME", LucideIcons.House),
                                SlockBottomTabItem(
                                    "tasks",
                                    "TASKS",
                                    LucideIcons.SquareCheckBig
                                )
                        ),
                        selectedKey = "home",
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSelected = {}
                    )
                }
            }
        }

        val homeBounds = composeRule.onNodeWithText("HOME").getBoundsInRoot()
        val tasksBounds = composeRule.onNodeWithText("TASKS").getBoundsInRoot()
        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val rootHeight = rootBounds.bottom - rootBounds.top

        assert(homeBounds.top > rootHeight / 2f) {
            "Expected bottom tab label below root midpoint, but HOME top=${homeBounds.top} rootHeight=$rootHeight"
        }
        assert(tasksBounds.top > rootHeight / 2f) {
            "Expected bottom tab label below root midpoint, but TASKS top=${tasksBounds.top} rootHeight=$rootHeight"
        }
    }
}
