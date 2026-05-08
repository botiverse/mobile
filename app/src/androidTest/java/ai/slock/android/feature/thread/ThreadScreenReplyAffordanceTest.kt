package ai.slock.android.feature.thread

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Rule
import org.junit.Test

class ThreadScreenReplyAffordanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun messageRowShowsReplyCountAffordanceOnlyWhenRepliesExist() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 4),
                    onOpenThread = {}
                )
            }
        }

        composeRule.onNodeWithText("4 replies").assertIsDisplayed()
        composeRule.onAllNodesWithText("Reply").assertCountEquals(0)
    }

    @Test
    fun messageRowDoesNotShowMisleadingAffordanceWhenNoReplies() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0, threadChannelId = ChannelId("thread-no-replies")),
                    onOpenThread = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Reply").assertCountEquals(0)
        composeRule.onAllNodesWithText("Open thread").assertCountEquals(0)
        composeRule.onAllNodesWithText("0 replies").assertCountEquals(0)
    }

    private fun message(
        replyCount: Int,
        threadChannelId: ChannelId? = null
    ) = Message(
        id = MessageId("message-reply-test-$replyCount"),
        threadId = ThreadId("thread-reply-test"),
        channelId = ChannelId("channel-reply-test"),
        threadChannelId = threadChannelId,
        author = MemberProfile(
            id = MemberId("author-reply-test"),
            displayName = "Reply Tester",
            handle = "reply-tester",
            kind = MemberKind.Agent,
            roleDescription = "Agent"
        ),
        timestampLabel = "Now",
        body = "Reply affordance test message",
        replyCount = replyCount
    )
}
