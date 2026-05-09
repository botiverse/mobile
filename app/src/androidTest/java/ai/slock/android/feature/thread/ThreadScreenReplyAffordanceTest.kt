package ai.slock.android.feature.thread

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.ui.theme.SlockTheme
import org.junit.Assert.assertEquals
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

    @Test
    fun threadHeaderShowsOriginalMessageJumpOnlyWhenTargetExists() {
        composeRule.setContent {
            SlockTheme {
                ThreadTopHeader(
                    replyHeaderContext = ThreadReplyHeaderContext(
                        parentChannelName = "android-dev",
                        parentMessagePreview = "Parent task"
                    ),
                    parentJumpTarget = ThreadParentJumpTarget(MessageId("parent-message"))
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open original message").assertIsDisplayed()
    }

    @Test
    fun messageBodyClickShowsActionOverlayAndCanMarkTaskDone() {
        var markedDoneTaskId: TaskId? = null
        val task = TaskSummary(
            id = TaskId("task-message-action"),
            title = "Finish action menu",
            status = TaskStatus.InProgress,
            updatedAtLabel = "Now"
        )

        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(task = task),
                    onMarkTaskDone = { markedDoneTaskId = it.id }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Message actions").performClick()
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Mark done").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(TaskId("task-message-action"), markedDoneTaskId)
        }
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
