package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * artin 10-31: a video in a message could not be saved. As in Telegram / WhatsApp / iMessage, a
 * long press on the message offers Download for its video.
 */
class ThreadMessageMenuDownloadVideoTest {
    private val video = RaftThreadAttachmentItem(id = "v1", name = "clip.mp4", mimeType = "video/mp4")
    private val otherVideo = RaftThreadAttachmentItem(id = "v2", name = "take2.mov", mimeType = "video/quicktime")
    private val document = RaftThreadAttachmentItem(id = "d1", name = "notes.pdf", mimeType = "application/pdf")

    @Test
    fun theMessagesOneSentVideoIsTheDownloadTarget() {
        val cases =
            listOf(
                listOf(video) to "v1",
                listOf(document, video) to "v1",
                listOf(document) to null,
                emptyList<RaftThreadAttachmentItem>() to null,
                // Two videos: the menu cannot tell which one; each downloads from its preview.
                listOf(video, otherVideo) to null,
                // Not uploaded yet: nothing to fetch.
                listOf(video.copy(id = "")) to null,
            )
        cases.forEach { (attachments, expected) ->
            assertEquals(expected, threadMessageMenuDownloadVideoAttachmentId(message(attachments)), "attachments=${attachments.map { it.name }}")
        }
    }

    @Test
    fun aMessageStillSendingHasNoDownload() {
        assertNull(threadMessageMenuDownloadVideoAttachmentId(message(listOf(video), deliveryState = ThreadMessageDeliveryFailed)))
    }

    @Test
    fun downloadRowSitsInTheMessageGroupAndRunsTheCallback() {
        var downloads = 0
        val actions = classicActions(onDownloadVideo = { downloads++ })
        val download = actions.single { it.label == Res.string.file_preview_action_download.desc() }
        assertEquals("message", download.group)
        assertEquals(RaftLucideIconName.Download, download.icon)
        download.onClick()
        assertEquals(1, downloads)

        val withoutVideo = classicActions(onDownloadVideo = null)
        assertEquals(actions.size - 1, withoutVideo.size)
    }

    private fun classicActions(onDownloadVideo: (() -> Unit)?): List<ThreadMessageMenuAction> =
        threadMessageMenuActions(
            message = message(listOf(video)),
            showReplyAction = true,
            showSaveAction = true,
            showFollowAction = false,
            showTaskStatusAction = false,
            showConvertToTaskAction = false,
            showCopyLinkAction = true,
            showShareMessagesAction = true,
            showCancelAction = false,
            showDeleteAction = false,
            onReply = {},
            onCopyLink = {},
            onCopy = {},
            onSelectText = {},
            onShareMessages = {},
            onSave = {},
            onFollowThread = {},
            onUnfollowThread = {},
            onTaskStatusChange = {},
            onConvertToTask = {},
            onCancel = {},
            onDelete = {},
            onDownloadVideo = onDownloadVideo
        )

    private fun message(
        attachments: List<RaftThreadAttachmentItem>,
        deliveryState: String = ThreadMessageDeliverySent
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = "m1",
            authorId = "member-1",
            authorName = "Member",
            messageType = "Chat",
            body = "",
            deliveryState = deliveryState,
            attachments = attachments
        )
}
