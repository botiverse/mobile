package ai.slock.android.feature.inbox

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.core.state.ScreenLoadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InboxScreenRepliesAffordanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun messageRowShowsRepliesAffordanceWhenReplyCountExists() {
        val openCount = AtomicInteger(0)
        composeRule.setContent {
            SlockTheme {
                InboxScreen(
                    state = InboxUiState(
                        loadState = ScreenLoadState.Content(
                            InboxContent(
                                server = ServerSummary(ServerId("server-1"), "Slock Android"),
                                items = listOf(
                                    inboxItem(replyCount = 4)
                                )
                            )
                        )
                    ),
                    onBack = {},
                    onOpenThread = { openCount.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithText("4 replies").assertIsDisplayed().performClick()
        assertEquals(1, openCount.get())
    }

    @Test
    fun messageRowDoesNotShowRepliesAffordanceWhenReplyCountIsZero() {
        composeRule.setContent {
            SlockTheme {
                InboxScreen(
                    state = InboxUiState(
                        loadState = ScreenLoadState.Content(
                            InboxContent(
                                server = ServerSummary(ServerId("server-1"), "Slock Android"),
                                items = listOf(
                                    inboxItem(replyCount = 0)
                                )
                            )
                        )
                    ),
                    onBack = {},
                    onOpenThread = {}
                )
            }
        }

        composeRule.onAllNodesWithText("0 replies").assertCountEquals(0)
        composeRule.onAllNodesWithText("replies").assertCountEquals(0)
    }

    @Test
    fun checkButtonMarksItemReadWithoutOpeningThread() {
        val openCount = AtomicInteger(0)
        val markReadCount = AtomicInteger(0)
        composeRule.setContent {
            SlockTheme {
                InboxScreen(
                    state = InboxUiState(
                        loadState = ScreenLoadState.Content(
                            InboxContent(
                                server = ServerSummary(ServerId("server-1"), "Slock Android"),
                                items = listOf(
                                    inboxItem(replyCount = 0).copy(unreadCount = 2)
                                )
                            )
                        )
                    ),
                    onBack = {},
                    onOpenThread = { openCount.incrementAndGet() },
                    onMarkItemRead = { markReadCount.incrementAndGet() }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mark item as read").assertIsDisplayed().performClick()

        assertEquals(0, openCount.get())
        assertEquals(1, markReadCount.get())
    }

    private fun inboxItem(replyCount: Int) = InboxItem(
        key = "thread:reply-${replyCount}",
        kind = InboxItemKind.Thread,
        channelId = ChannelId("channel-reply-${replyCount}"),
        channelName = "channel-reply-${replyCount}",
        unreadCount = 0,
        lastMessagePreview = "Reply affordance sample message",
        lastMessageAt = "Now",
        lastMessageSenderName = "Reply Tester",
        parentChannelName = "android-dev",
        parentMessagePreview = "Parent message",
        replyCount = replyCount
    )
}
