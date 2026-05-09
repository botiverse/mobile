package ai.slock.android.feature.thread

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
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
    fun footerRepliesClickOpensThreadWithoutShowingActionOverlay() {
        var openedThread = false
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 4),
                    onOpenThread = { openedThread = true }
                )
            }
        }

        composeRule.onNodeWithText("4 replies").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(true, openedThread)
        }
        composeRule.onAllNodesWithText("Copy markdown").assertCountEquals(0)
    }

    @Test
    fun systemMessageRowShowsReplyCountAffordanceWhenRepliesExist() {
        var openedThread = false
        composeRule.setContent {
            SlockTheme {
                ThreadSystemMessageItem(
                    message = message(replyCount = 3).copy(messageType = MessageType.System),
                    onOpenThread = { openedThread = true }
                )
            }
        }

        composeRule.onNodeWithText("3 replies").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(true, openedThread)
        }
    }

    @Test
    fun messageFooterWrapsTaskIdAssigneeAndKeepsRepliesReadable() {
        val task = TaskSummary(
            id = TaskId("task-footer-wrap"),
            title = "Footer wrap",
            status = TaskStatus.InProgress,
            taskNumber = 382,
            assignee = MemberProfile(
                id = MemberId("long-assignee"),
                displayName = "A Very Very Long Assignee Name That Should Not Squeeze Replies",
                handle = "very-long-assignee",
                kind = MemberKind.Agent,
                roleDescription = "Agent"
            ),
            updatedAtLabel = "Now"
        )

        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 18).copy(task = task),
                    onOpenThread = {}
                )
            }
        }

        composeRule.onNodeWithTag(THREAD_MESSAGE_FOOTER_META_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_MESSAGE_FOOTER_ASSIGNEE_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_MESSAGE_FOOTER_REPLIES_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("#382 @A Very", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("@very-long-assignee", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("In progress").assertCountEquals(0)
        composeRule.onNodeWithText("18 replies").assertIsDisplayed()
    }

    @Test
    fun messageFooterOmitsUnresolvedAssigneeIdButKeepsRepliesReadable() {
        val task = TaskSummary(
            id = TaskId("task-footer-unresolved-assignee"),
            title = "Footer unresolved assignee",
            status = TaskStatus.InProgress,
            taskNumber = 382,
            assignee = MemberProfile(
                id = MemberId("raw-assignee-id"),
                displayName = "raw-assignee-id",
                handle = "raw-assignee-id",
                kind = MemberKind.Agent,
                roleDescription = "Agent"
            ),
            updatedAtLabel = "Now"
        )

        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 18).copy(task = task),
                    onOpenThread = {}
                )
            }
        }

        composeRule.onNodeWithText("#382").assertIsDisplayed()
        composeRule.onAllNodesWithText("@raw-assignee-id", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("18 replies").assertIsDisplayed()
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
        var statusChange: Pair<TaskId, TaskStatus>? = null
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
                    onOpenThread = {},
                    onTaskStatusChange = { changedTask, status ->
                        statusChange = changedTask.id to status
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Message actions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Copy markdown").assertIsDisplayed()
        composeRule.onNodeWithText("Save message").assertIsDisplayed()
        composeRule.onNodeWithText("Mark done").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(TaskId("task-message-action") to TaskStatus.Done, statusChange)
        }
    }

    @Test
    fun markDoneActionDoesNotRemoveMessageAttachmentUi() {
        var statusChange: Pair<TaskId, TaskStatus>? = null
        val task = TaskSummary(
            id = TaskId("task-message-action-attachment"),
            title = "Finish action menu",
            status = TaskStatus.InProgress,
            updatedAtLabel = "Now"
        )
        val attachment = Attachment(
            id = AttachmentId("attachment-action"),
            name = "preview.png",
            mimeType = "image/png",
            sizeLabel = "8 KB",
            thumbnailUrl = "https://example.invalid/preview.png"
        )

        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(
                        task = task,
                        attachments = listOf(attachment)
                    ),
                    onTaskStatusChange = { changedTask, status ->
                        statusChange = changedTask.id to status
                    }
                )
            }
        }

        composeRule.onNodeWithText("preview.png").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Message actions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Mark done").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("preview.png").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(TaskId("task-message-action-attachment") to TaskStatus.Done, statusChange)
        }
    }

    @Test
    fun doneTaskActionShowsReopenAndMovesBackToInProgress() {
        var statusChange: Pair<TaskId, TaskStatus>? = null
        val task = TaskSummary(
            id = TaskId("task-message-action-reopen"),
            title = "Reopen action menu",
            status = TaskStatus.Done,
            updatedAtLabel = "Now"
        )

        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(task = task),
                    onTaskStatusChange = { changedTask, status ->
                        statusChange = changedTask.id to status
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Message actions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reopen task").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(TaskId("task-message-action-reopen") to TaskStatus.InProgress, statusChange)
        }
    }

    @Test
    fun messageBodyTextClickShowsActionOverlayAndReplyOpensThread() {
        var openedThread = false
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0),
                    onOpenThread = { openedThread = true }
                )
            }
        }

        composeRule.onNodeWithText("Reply affordance test message").assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Reply").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(true, openedThread)
        }
    }

    @Test
    fun messageBodyWithInlineTagStillOpensActionOverlayFromPlainText() {
        var openedTag: ThreadMarkdownTag? = null
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(
                        body = "Plain body before #382"
                    ),
                    onOpenThread = {},
                    onOpenMarkdownTag = { openedTag = it }
                )
            }
        }

        val bodyNode = composeRule.onNodeWithText("Plain body before", substring = true)
            .assertIsDisplayed()
        bodyNode.performTouchInput {
            click(centerLeft)
        }
        composeRule.onNodeWithText("Copy markdown").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, openedTag)
        }
    }

    @Test
    fun messageActionOverlayCanOpenFromHeaderArea() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0),
                    onOpenThread = {}
                )
            }
        }

        composeRule.onNodeWithText("Reply Tester").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Copy markdown").assertIsDisplayed()
        composeRule.onNodeWithText("Save message").assertIsDisplayed()
    }

    @Test
    fun messageActionOverlayOpensNearTappedMessageArea() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0),
                    onOpenThread = {}
                )
            }
        }

        val bodyBounds =
            composeRule.onNodeWithText("Reply affordance test message")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        composeRule.onNodeWithText("Reply affordance test message").performClick()
        val menuBounds =
            composeRule.onNodeWithTag(THREAD_MESSAGE_ACTION_MENU_TAG, useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        assertEquals(true, menuBounds.top >= bodyBounds.top - 8.dp)
        assertEquals(true, menuBounds.top <= bodyBounds.bottom + 16.dp)
        assertEquals(true, menuBounds.bottom > bodyBounds.bottom)
    }

    @Test
    fun messageActionOverlayFlipsAboveBottomTapSoActionsRemainVisible() {
        composeRule.setContent {
            SlockTheme {
                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    Box(
                        modifier = androidx.compose.ui.Modifier.align(Alignment.BottomCenter)
                    ) {
                        ThreadMessageItem(
                            message = message(replyCount = 0).copy(
                                id = MessageId("bottom-message"),
                                task = TaskSummary(
                                    id = TaskId("bottom-task"),
                                    title = "Bottom task",
                                    status = TaskStatus.InProgress,
                                    taskNumber = 382,
                                    assignee = null,
                                    updatedAtLabel = "Now"
                                )
                            ),
                            onOpenThread = {}
                        )
                    }
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .testTag("thread-action-bottom-anchor")
                    )
                }
            }
        }

        composeRule.onNodeWithText("Reply affordance test message")
            .performTouchInput {
                click()
            }

        val menuBounds =
            composeRule.onNodeWithTag(THREAD_MESSAGE_ACTION_MENU_TAG, useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        composeRule.onNodeWithText("Mark done").assertIsDisplayed()
        val rootBottom = composeRule.onNodeWithTag("thread-action-bottom-anchor")
            .getUnclippedBoundsInRoot()
            .bottom
        assertEquals(true, menuBounds.bottom <= rootBottom)
    }

    @Test
    fun messageActionOverlayDismissesWhenTappingOutside() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0),
                    onOpenThread = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Message actions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Copy markdown").assertIsDisplayed()

        composeRule.onNodeWithText("Reply affordance test message").performClick()

        composeRule.onAllNodesWithText("Copy markdown").assertCountEquals(0)
    }

    @Test
    fun messageActionOverlayDismissesWhenListScrolls() {
        val screenState = mutableStateOf(true)
        composeRule.setContent {
            SlockTheme {
                if (screenState.value) {
                    ThreadMessageItem(
                        message = message(replyCount = 0),
                        onOpenThread = {}
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Message actions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Copy markdown").assertIsDisplayed()

        composeRule.runOnIdle {
            screenState.value = false
        }

        composeRule.onAllNodesWithText("Copy markdown").assertCountEquals(0)
    }

    @Test
    fun saveActionFeedbackAutoDismisses() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(message = message(replyCount = 0))
            }
        }

        composeRule.onNodeWithContentDescription("Message actions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Save message").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Saved locally").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Saved locally").assertCountEquals(0)
    }

    @Test
    fun messageBodyRecognizesConsecutiveHashTagsSeparatedBySlash() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(body = "#alpha/#beta"),
                    onOpenMarkdownTag = {}
                )
            }
        }

        composeRule.onNodeWithText("#alpha", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("#beta", substring = true).assertIsDisplayed()
    }

    @Test
    fun numericHashReferenceRendersWithoutDoubleHashLabel() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(body = "#123"),
                    onOpenMarkdownTag = {}
                )
            }
        }

        composeRule.onNodeWithText("##123", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("#123", substring = true).assertIsDisplayed()
    }

    @Test
    fun channelHashReferenceRendersWithoutDoubleHashLabel() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(body = "#general"),
                    onOpenMarkdownTag = {}
                )
            }
        }

        composeRule.onNodeWithText("##general", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("#general", substring = true).assertIsDisplayed()
    }

    @Test
    fun chineseChannelHashReferenceRendersFullLabel() {
        composeRule.setContent {
            SlockTheme {
                ThreadMessageItem(
                    message = message(replyCount = 0).copy(body = "#对话流专修 或 #日常需求池"),
                    onOpenMarkdownTag = {}
                )
            }
        }

        composeRule.onNodeWithText("#对话流专修", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("#日常需求池", substring = true).assertIsDisplayed()
    }

    @Test
    fun composerUsesInlineIconActionsAndIconOnlySend() {
        var sent = false
        var asTask: Boolean? = null
        var imageAttached = false
        var fileAttached = false

        composeRule.setContent {
            SlockTheme {
                ThreadComposerBar(
                    composerState = ComposerState(
                        value = "Composer test",
                        status = ComposerStatus.Ready
                    ),
                    placeholder = "Message",
                    onComposerChange = {},
                    onAsTaskChange = { asTask = it },
                    onAttachImage = { imageAttached = true },
                    onAttachFile = { fileAttached = true },
                    onSend = { sent = true }
                )
            }
        }

        composeRule.onAllNodesWithText("Send").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Attach image").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Attach file").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("As Task").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(true, imageAttached)
            assertEquals(true, fileAttached)
            assertEquals(true, asTask)
            assertEquals(true, sent)
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
