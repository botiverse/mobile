package ai.slock.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Rule
import org.junit.Test

class SlockStructuralHomeShellTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun channelRowShowsDraftPencilWithoutHidingUnreadBadge() {
        composeRule.setContent {
            SlockTheme {
                SlockStructuralHomeShell(
                    channels = listOf(
                        SlockHomeConversationItem(
                            id = "thread-with-draft",
                            name = "android-dev",
                            unreadCount = 7,
                            hasDraft = true
                        ),
                        SlockHomeConversationItem(
                            id = "thread-without-draft",
                            name = "product",
                            unreadCount = 0,
                            hasDraft = false
                        )
                    ),
                    directMessages = emptyList(),
                    selectedChannelId = null,
                    showBottomBar = false
                )
            }
        }

        composeRule.onNodeWithText("android-dev").assertIsDisplayed()
        composeRule.onNodeWithText("product").assertIsDisplayed()
        composeRule.onNodeWithText("7").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Unsent draft").assertCountEquals(1)
    }
}
