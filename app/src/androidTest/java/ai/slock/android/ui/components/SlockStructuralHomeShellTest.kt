package ai.slock.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import ai.slock.android.SlockAppBottomBarHost
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Assert.assertTrue
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

    @Test
    fun inboxAndSavedActionRowsShowSummaryBadges() {
        composeRule.setContent {
            SlockTheme {
                SlockStructuralHomeShell(
                    channels = emptyList(),
                    directMessages = emptyList(),
                    inboxUnreadCount = 4,
                    savedCount = 12,
                    selectedChannelId = null,
                    showBottomBar = false
                )
            }
        }

        composeRule.onNodeWithText("Inbox").assertIsDisplayed()
        composeRule.onNodeWithText("Saved").assertIsDisplayed()
        composeRule.onNodeWithText("4").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun bottomTabBarDoesNotCoverLastDirectMessageRow() {
        val directMessages = (1..12).map { index ->
            SlockHomeConversationItem(
                id = "dm-$index",
                name = "Agent $index",
                avatarLabel = "A"
            )
        }

        composeRule.setContent {
            SlockTheme {
                SlockStructuralHomeShell(
                    channels = emptyList(),
                    directMessages = directMessages,
                    selectedChannelId = null,
                    showBottomBar = true
                )
            }
        }

        repeat(2) {
            composeRule.onNodeWithText("Agent 8").performTouchInput { swipeUp() }
        }
        composeRule.onNodeWithText("Agent 12").assertIsDisplayed()

        val lastRowBounds = composeRule.onNodeWithText("Agent 12").getUnclippedBoundsInRoot()
        val tabBounds = composeRule.onNodeWithText("HOME").getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()

        assertTrue(
            "Expected last DM row above bottom tab bar, rowBottom=${lastRowBounds.bottom}, tabTop=${tabBounds.top}",
            lastRowBounds.bottom < tabBounds.top
        )
        assertTrue(
            "Expected bottom tab bar to stay in lower half, tabTop=${tabBounds.top}, rootBottom=${rootBounds.bottom}",
            tabBounds.top > rootBounds.bottom / 2f
        )
    }

    @Test
    fun externalAppBottomBarDoesNotCoverLastDirectMessageRow() {
        val directMessages = (1..12).map { index ->
            SlockHomeConversationItem(
                id = "dm-$index",
                name = "External Agent $index",
                avatarLabel = "A"
            )
        }

        composeRule.setContent {
            SlockTheme {
                val appBottomBarContentPadding =
                    SlockSize.BottomBarHeight +
                        with(LocalDensity.current) {
                            WindowInsets.navigationBars.getBottom(this).toDp()
                        } +
                        SlockSpacing.Xl
                Box(Modifier.fillMaxSize()) {
                    SlockStructuralHomeShell(
                        channels = emptyList(),
                        directMessages = directMessages,
                        selectedChannelId = null,
                        showBottomBar = false,
                        bottomContentPadding = appBottomBarContentPadding,
                        outerPadding = 0.dp
                    )
                    SlockAppBottomBarHost(
                        items = listOf(
                            SlockBottomTabItem("home", "HOME", Icons.Outlined.Home),
                            SlockBottomTabItem("tasks", "TASKS", SlockIcons.ListTodo)
                        ),
                        selectedKey = "home",
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSelected = {}
                    )
                }
            }
        }

        repeat(2) {
            composeRule.onNodeWithText("External Agent 8").performTouchInput { swipeUp() }
        }
        composeRule.onNodeWithText("External Agent 12").assertIsDisplayed()

        val lastRowBounds = composeRule.onNodeWithText("External Agent 12").getUnclippedBoundsInRoot()
        val tabBounds = composeRule.onNodeWithText("HOME").getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()

        assertTrue(
            "Expected last DM row above external bottom tab bar, rowBottom=${lastRowBounds.bottom}, tabTop=${tabBounds.top}",
            lastRowBounds.bottom < tabBounds.top
        )
        assertTrue(
            "Expected external bottom tab bar to stay in lower half, tabTop=${tabBounds.top}, rootBottom=${rootBounds.bottom}",
            tabBounds.top > rootBounds.bottom / 2f
        )
    }
}
