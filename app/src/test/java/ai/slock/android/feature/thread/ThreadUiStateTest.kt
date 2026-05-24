package ai.slock.android.feature.thread

import ai.slock.android.core.model.AttachmentId
import ai.slock.compose.data.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.TaskCreated
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.unreadReplyCount
import ai.slock.android.core.model.withUnreadReplyCount
import ai.slock.compose.data.ChannelUnreadSnapshot
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.mergeRealtimeUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadUiStateTest {
    @Test
    fun readyComposerWithAttachmentShowsStableReadyLabel() {
        val composer =
            ComposerState(
                status = ComposerStatus.Ready,
                attachmentIds = listOf(AttachmentId("attachment-1"))
            )

        assertEquals("1 attachment ready", composer.activityLabel)
    }

    @Test
    fun sendingComposerWithAttachmentsUsesAttachmentCountLabel() {
        val composer =
            ComposerState(
                status = ComposerStatus.Sending,
                attachmentIds = listOf(
                    AttachmentId("attachment-1"),
                    AttachmentId("attachment-2")
                )
            )

        assertEquals("Sending 2 attachments", composer.activityLabel)
    }

    @Test
    fun sendingComposerWithoutAttachmentsKeepsMessageLabel() {
        val composer = ComposerState(status = ComposerStatus.Sending)

        assertEquals("Sending message", composer.activityLabel)
    }

    @Test
    fun failedComposerWithTextCanSendForRetry() {
        val composer = ComposerState(
            value = "Retry this message",
            status = ComposerStatus.Failed,
            errorCode = "send_failed"
        )

        assertTrue(composer.canSend)
    }

    @Test
    fun failedComposerWithAttachmentsCanSendForRetry() {
        val composer = ComposerState(
            status = ComposerStatus.Failed,
            attachmentIds = listOf(AttachmentId("attachment-1")),
            errorCode = "send_failed"
        )

        assertTrue(composer.canSend)
    }

    @Test
    fun failedComposerWithoutDraftContentCannotSend() {
        val composer = ComposerState(
            status = ComposerStatus.Failed,
            errorCode = "send_failed"
        )

        assertFalse(composer.canSend)
    }

    @Test
    fun threadRefreshLoadingPreservesVisibleContent() {
        val message = message("message-1", seq = 10)
        val state = ThreadUiState(
            loadState = ScreenLoadState.Content(
                ThreadContent(
                    header = conversation("thread-1"),
                    messages = listOf(message)
                )
            )
        )

        val refreshed = state.withThreadRefreshStatus(ThreadRefreshStatus.Loading)
        val content = (refreshed.loadState as ScreenLoadState.Content).data

        assertEquals(ThreadRefreshStatus.Loading, content.refreshStatus)
        assertEquals(listOf(message), content.messages)
        assertNull(content.refreshErrorCode)
    }

    @Test
    fun threadRefreshFailurePreservesVisibleContentAndStoresErrorCode() {
        val message = message("message-1", seq = 10)
        val state = ThreadUiState(
            loadState = ScreenLoadState.Content(
                ThreadContent(
                    header = conversation("thread-1"),
                    messages = listOf(message)
                )
            )
        )

        val failed = state.withThreadRefreshStatus(ThreadRefreshStatus.Error, "network_timeout")
        val content = (failed.loadState as ScreenLoadState.Content).data

        assertEquals(ThreadRefreshStatus.Error, content.refreshStatus)
        assertEquals("network_timeout", content.refreshErrorCode)
        assertEquals(listOf(message), content.messages)
    }

    @Test
    fun refreshedThreadSnapshotMergesMessagesAndPreservesAnchor() {
        val anchor = MessageId("message-2")
        val state = ThreadUiState(
            loadState = ScreenLoadState.Content(
                ThreadContent(
                    header = conversation("thread-1"),
                    messages = listOf(message("message-2", seq = 20), message("message-3", seq = 30)),
                    canLoadMoreBefore = true,
                    anchorMessageId = anchor,
                    anchorOffset = 42,
                    refreshStatus = ThreadRefreshStatus.Loading,
                    refreshErrorCode = "stale_error"
                )
            )
        )
        val snapshot = ThreadSnapshot(
            conversation = conversation("thread-1", name = "Updated thread"),
            messages = listOf(message("message-1", seq = 10), message("message-2", seq = 20, body = "edited")),
            canLoadMoreBefore = false,
            canLoadMoreAfter = true,
            hasGap = true,
            historyLimited = true
        )

        val merged = state.mergeRefreshedThreadSnapshot(snapshot)
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(ThreadRefreshStatus.Idle, content.refreshStatus)
        assertNull(content.refreshErrorCode)
        assertEquals(anchor, content.anchorMessageId)
        assertEquals(42, content.anchorOffset)
        assertEquals("Updated thread", content.header.name)
        assertEquals(false, content.canLoadMoreBefore)
        assertEquals(true, content.canLoadMoreAfter)
        assertEquals(listOf("message-1", "message-2", "message-3"), content.messages.map { it.id.value })
        assertEquals("edited", content.messages.first { it.id.value == "message-2" }.body)
    }

    @Test
    fun refreshedThreadSnapshotDoesNotMoveMissingSeqOlderMessageToBottom() {
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(
                            header = conversation("thread-1"),
                            messages =
                                listOf(
                                    message(
                                        id = "message-middle",
                                        seq = 20,
                                        timestampLabel = "2026-05-11T10:10:00Z"
                                    ),
                                    message(
                                        id = "message-latest",
                                        seq = 30,
                                        timestampLabel = "2026-05-11T10:20:00Z"
                                    )
                                )
                        )
                    )
            )
        val snapshot =
            ThreadSnapshot(
                conversation = conversation("thread-1"),
                messages =
                    listOf(
                        message(
                            id = "message-older-without-seq",
                            seq = null,
                            timestampLabel = "2026-05-11T10:00:00Z"
                        ),
                        message(
                            id = "message-middle",
                            seq = 20,
                            timestampLabel = "2026-05-11T10:10:00Z",
                            body = "edited"
                        )
                    )
            )

        val merged = state.mergeRefreshedThreadSnapshot(snapshot)
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(
            listOf("message-older-without-seq", "message-middle", "message-latest"),
            content.messages.map { it.id.value }
        )
        assertEquals("edited", content.messages.first { it.id.value == "message-middle" }.body)
    }

    @Test
    fun refreshedThreadSnapshotUsesTimeBeforeSeq() {
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(
                            header = conversation("thread-1"),
                            messages =
                                listOf(
                                    message(
                                        id = "message-newer-low-seq",
                                        seq = 10,
                                        timestampLabel = "2026-05-11T10:20:00Z"
                                    )
                                )
                        )
                    )
            )
        val snapshot =
            ThreadSnapshot(
                conversation = conversation("thread-1"),
                messages =
                    listOf(
                        message(
                            id = "message-older-high-seq",
                            seq = 99,
                            timestampLabel = "2026-05-11T10:00:00Z"
                        )
                    )
            )

        val merged = state.mergeRefreshedThreadSnapshot(snapshot)
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(
            listOf("message-older-high-seq", "message-newer-low-seq"),
            content.messages.map { it.id.value }
        )
    }

    @Test
    fun refreshedThreadSnapshotUsesTimestampWhenSeqIsMissingForNewMessage() {
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(
                            header = conversation("thread-1"),
                            messages =
                                listOf(
                                    message(
                                        id = "zzz-existing-bottom",
                                        seq = null,
                                        timestampLabel = "2026-05-12T18:00:00Z"
                                    )
                                )
                        )
                    )
            )
        val snapshot =
            ThreadSnapshot(
                conversation = conversation("thread-1"),
                messages =
                    listOf(
                        message(
                            id = "aaa-new-sent",
                            seq = null,
                            timestampLabel = "2026-05-12T18:05:00Z",
                            body = "new"
                        )
                    )
            )

        val merged = state.mergeRefreshedThreadSnapshot(snapshot)
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(
            listOf("zzz-existing-bottom", "aaa-new-sent"),
            content.messages.map { it.id.value }
        )
    }

    @Test
    fun refreshedThreadSnapshotPreservesCachedMemberCountWhenResponseOmitsIt() {
        val state = ThreadUiState(
            loadState = ScreenLoadState.Content(
                ThreadContent(
                    header = conversation("thread-1").copy(memberCount = 7),
                    messages = listOf(message("message-2", seq = 20))
                )
            )
        )
        val snapshot = ThreadSnapshot(
            conversation = conversation("thread-1", name = "Updated thread"),
            messages = listOf(message("message-2", seq = 20))
        )

        val merged = state.mergeRefreshedThreadSnapshot(snapshot)
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(7, content.header.memberCount)
    }

    @Test
    fun applyReplyUnreadCountsUpdatesReplyMessageMetadataByThreadChannelId() {
        val replyMessage =
            message("message-reply-parent", seq = 20).copy(
                threadChannelId = ChannelId("reply-thread-1")
            )
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(header = conversation("thread-1"), messages = listOf(replyMessage))
                    )
            )

        val updated =
            state.applyReplyUnreadCounts(
                ChannelUnreadSnapshot(
                    server = server(),
                    unreadCounts = mapOf(ChannelId("reply-thread-1") to 3)
                )
            )
        val content = (updated.loadState as ScreenLoadState.Content).data

        assertEquals(3, content.messages.single().unreadReplyCount())
    }

    @Test
    fun clearReplyUnreadResetsReplyMessageMetadataForMatchingThread() {
        val replyMessage =
            message("message-reply-parent", seq = 20)
                .copy(threadChannelId = ChannelId("reply-thread-1"))
                .withUnreadReplyCount(3)
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(header = conversation("thread-1"), messages = listOf(replyMessage))
                    )
            )

        val updated = state.clearReplyUnread(ChannelId("reply-thread-1"))
        val content = (updated.loadState as ScreenLoadState.Content).data

        assertEquals(0, content.messages.single().unreadReplyCount())
    }

    @Test
    fun realtimeTaskCreatedMergesIntoVisibleThreadMessage() {
        val message = message("message-task-parent", seq = 20)
        val task =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Assigned from socket",
                status = TaskStatus.InProgress,
                taskNumber = 382,
                messageId = message.id,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-1"),
                        displayName = "Cindy",
                        handle = "cindy",
                        kind = MemberKind.Agent
                    ),
                updatedAtLabel = "Now"
            )
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(header = conversation("thread-1"), messages = listOf(message))
                    )
            )

        val merged = state.mergeRealtimeTaskEvent(TaskCreated(listOf(task)))
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(382, content.messages.single().task?.taskNumber)
        assertEquals("Cindy", content.messages.single().task?.assignee?.displayName)
    }

    @Test
    fun realtimeTaskCreatedDoesNotAttachToEveryMessageInSameThread() {
        val targetMessage = message("message-task-parent", seq = 20).copy(
            threadChannelId = ChannelId("reply-thread-1")
        )
        val siblingMessage = message("message-sibling", seq = 21).copy(
            threadChannelId = ChannelId("reply-thread-1")
        )
        val task =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Assigned from socket",
                status = TaskStatus.InProgress,
                taskNumber = 382,
                messageId = targetMessage.id,
                threadId = ThreadId("reply-thread-1"),
                updatedAtLabel = "Now"
            )
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(
                            header = conversation("thread-1"),
                            messages = listOf(targetMessage, siblingMessage)
                        )
                    )
            )

        val merged = state.mergeRealtimeTaskEvent(TaskCreated(listOf(task)))
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(382, content.messages.first { it.id == targetMessage.id }.task?.taskNumber)
        assertNull(content.messages.first { it.id == siblingMessage.id }.task)
    }

    @Test
    fun taskStatusUpdateRefreshesVisibleMessageParentAndThreadTasks() {
        val initialTask =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Finish reply thread",
                status = TaskStatus.InProgress,
                taskNumber = 382,
                messageId = MessageId("message-task-parent"),
                updatedAtLabel = "Earlier"
            )
        val updatedTask = initialTask.copy(status = TaskStatus.Done, updatedAtLabel = "Now")
        val parentMessage = message("message-task-parent", seq = 10).copy(task = initialTask)
        val replyMessage = message("message-reply", seq = 20).copy(task = initialTask)
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(
                            header = conversation("reply-thread-1"),
                            parentMessage = parentMessage,
                            messages = listOf(replyMessage),
                            tasks = listOf(initialTask)
                        )
                    )
            )

        val merged = state.withTaskStatusUpdate(updatedTask)
        val content = (merged.loadState as ScreenLoadState.Content).data

        assertEquals(TaskStatus.Done, content.parentMessage?.task?.status)
        assertEquals(TaskStatus.Done, content.messages.single().task?.status)
        assertEquals(TaskStatus.Done, content.tasks.single().status)
        assertEquals("Now", content.messages.single().task?.updatedAtLabel)
    }

    @Test
    fun taskStatusUpdateMatchesServerMessageIdTaskIdAndPreservesReferences() {
        val initialTask =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Finish reply thread",
                status = TaskStatus.InProgress,
                taskNumber = 382,
                messageId = MessageId("message-task-parent"),
                parentMessageId = MessageId("parent-message"),
                channelId = ChannelId("channel-1"),
                threadId = ThreadId("thread-1"),
                isThreadTask = true,
                assignee =
                    MemberProfile(
                        id = MemberId("agent-1"),
                        displayName = "Cindy",
                        handle = "cindy",
                        kind = MemberKind.Agent
                    ),
                channelName = "对话流专修",
                updatedAtLabel = "Earlier"
            )
        val sparseUpdate =
            TaskSummary(
                id = TaskId("message-task-parent"),
                title = "Finish reply thread",
                status = TaskStatus.Done,
                updatedAtLabel = "Now"
            )
        val message = message("message-task-parent", seq = 20).copy(task = initialTask)
        val state =
            ThreadUiState(
                loadState =
                    ScreenLoadState.Content(
                        ThreadContent(header = conversation("thread-1"), messages = listOf(message))
                    )
            )

        val merged = state.withTaskStatusUpdate(sparseUpdate)
        val updatedTask =
            (merged.loadState as ScreenLoadState.Content).data.messages.single().task

        assertEquals(TaskStatus.Done, updatedTask?.status)
        assertEquals(382, updatedTask?.taskNumber)
        assertEquals(MessageId("message-task-parent"), updatedTask?.messageId)
        assertEquals(MessageId("parent-message"), updatedTask?.parentMessageId)
        assertEquals("Cindy", updatedTask?.assignee?.displayName)
        assertEquals("对话流专修", updatedTask?.channelName)
    }

    @Test
    fun realtimeMessageUpdateDoesNotClearExistingAuthorAvatar() {
        val existing =
            message("message-avatar", seq = 20).copy(
                author =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "artin",
                        handle = "artin",
                        kind = MemberKind.Human,
                        avatarUrl = "pixel:diamond",
                        gravatarHash = "abc123"
                    )
            )
        val update =
            message("message-avatar", seq = 21, body = "Edited").copy(
                author =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "artin",
                        handle = "artin",
                        kind = MemberKind.Human
                    )
            )

        val merged = existing.mergeRealtimeUpdate(update)

        assertEquals("Edited", merged.body)
        assertEquals("pixel:diamond", merged.author.avatarUrl)
        assertEquals("abc123", merged.author.gravatarHash)
        assertEquals("pixel:diamond", merged.author.avatar?.wireValue)
    }

    private fun conversation(
        id: String,
        name: String = "Thread"
    ): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.Channel,
            name = name
        )

    private fun server(): ServerSummary =
        ServerSummary(
            id = ServerId("server-1"),
            slug = "server-1",
            name = "Server"
        )

    private fun message(
        id: String,
        seq: Long?,
        timestampLabel: String = "9:41 AM",
        body: String = id
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId("thread-1"),
            channelId = ChannelId("thread-1"),
            seq = seq,
            author = MemberProfile(
                id = MemberId("member-1"),
                displayName = "Member One",
                handle = "@member-one",
                kind = MemberKind.Human
            ),
            timestampLabel = timestampLabel,
            body = body
        )
}
