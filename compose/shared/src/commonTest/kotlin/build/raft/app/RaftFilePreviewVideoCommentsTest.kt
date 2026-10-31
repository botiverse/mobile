package build.raft.app

import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.data.ThreadAttachmentCommentAnchor
import build.raft.app.data.ThreadAttachmentCommentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftFilePreviewVideoCommentsTest {
    private val attachment = RaftThreadAttachmentItem(id = "att-1", name = "demo.mp4", mimeType = "video/mp4")

    @Test
    fun pausingInCommentModeAttachesTheCurrentTime() {
        // web VideoPreviewBody: `pause` (and `seeked` while paused) push {time: round(currentTime*1000)/1000}.
        assertEquals(12.345, raftVideoCommentAutoAnchorSeconds(commentMode = true, PlayState.PAUSED, 12_345))
        assertEquals(30.0, raftVideoCommentAutoAnchorSeconds(commentMode = true, PlayState.PLAY_END, 30_000))
        assertNull(raftVideoCommentAutoAnchorSeconds(commentMode = true, PlayState.PLAYING, 12_345))
        assertNull(raftVideoCommentAutoAnchorSeconds(commentMode = true, PlayState.BUFFERING, 12_345))
        assertNull(raftVideoCommentAutoAnchorSeconds(commentMode = false, PlayState.PAUSED, 12_345))
        // The opening frame (iOS preplay seeks to 40ms) is not a timestamp: web requires time > 0.
        assertNull(raftVideoCommentAutoAnchorSeconds(commentMode = true, PlayState.PAUSED, 0))
        assertNull(raftVideoCommentAutoAnchorSeconds(commentMode = true, PlayState.PAUSED, 40))

        val state = ThreadAttachmentCommentsDialogState(attachment = attachment, pendingAnchorLabel = "demo.mp4")
        val anchored = state.withPendingVideoTimestamp(12.345)

        assertEquals(ThreadAttachmentCommentAnchor.VideoTimestamp(12.345), anchored.pendingAnchor)
        assertEquals("0:12", anchored.pendingAnchorLabel)
        assertTrue(anchored.hasVideoTimestampAnchor)
        assertFalse(state.hasVideoTimestampAnchor)
    }

    @Test
    fun addTimestampButtonShowsOnlyWhenPausedPastStartWithoutAnchor() {
        assertTrue(raftVideoCommentCanAddPausedTimestamp(true, PlayState.PAUSED, 5_000, hasTimestampAnchor = false))
        assertTrue(raftVideoCommentCanAddPausedTimestamp(true, PlayState.PLAY_END, 5_000, hasTimestampAnchor = false))
        assertFalse(raftVideoCommentCanAddPausedTimestamp(true, PlayState.PAUSED, 5_000, hasTimestampAnchor = true))
        assertFalse(raftVideoCommentCanAddPausedTimestamp(true, PlayState.PLAYING, 5_000, hasTimestampAnchor = false))
        assertFalse(raftVideoCommentCanAddPausedTimestamp(false, PlayState.PAUSED, 5_000, hasTimestampAnchor = false))
        assertFalse(raftVideoCommentCanAddPausedTimestamp(true, PlayState.PAUSED, 0, hasTimestampAnchor = false))
    }

    @Test
    fun anchorIsKeptUntilAcceptedThenCleared() {
        val anchored =
            ThreadAttachmentCommentsDialogState(attachment = attachment).withPendingVideoTimestamp(83.5)

        // Removing the chip drops the anchor with its label.
        val removed = anchored.withoutPendingAnchor()
        assertNull(removed.pendingAnchor)
        assertNull(removed.pendingAnchorLabel)

        // Once the server accepts the comment the anchor it carried is cleared (web onAccepted).
        val accepted = anchored.afterAnchoredCommentAccepted()
        assertNull(accepted.pendingAnchor)
        assertNull(accepted.pendingAnchorLabel)

        // A whole-file comment keeps the file chip as before.
        val fileChip = ThreadAttachmentCommentsDialogState(attachment = attachment, pendingAnchorLabel = "demo.mp4")
        assertEquals("demo.mp4", fileChip.afterAnchoredCommentAccepted().pendingAnchorLabel)
    }

    @Test
    fun tappingTimestampChipRequestsSeekToItsTime() {
        val anchor = ThreadAttachmentCommentAnchor.VideoTimestamp(83.456)

        val first = (null as RaftFilePreviewVideoSeekRequest?).nextVideoTimestampJump(anchor)
        assertEquals(RaftFilePreviewVideoSeekRequest(id = 1, timeMs = 83_456), first)

        // Tapping the same chip again is a new request, so the player seeks again.
        val again = first.nextVideoTimestampJump(anchor)
        assertEquals(RaftFilePreviewVideoSeekRequest(id = 2, timeMs = 83_456), again)

        assertEquals(RaftFilePreviewVideoSeekRequest(id = 8, timeMs = 0), RaftFilePreviewVideoSeekRequest(7, 500).nextVideoSeekRequest(-10))
    }

    @Test
    fun nativeControllerStateIsMirroredSoJumpPauseApplies() {
        assertEquals(VideoPlayControl.PLAY, raftVideoCommentMirroredPlayControl(VideoPlayControl.PAUSE, PlayState.PLAYING))
        assertEquals(VideoPlayControl.PAUSE, raftVideoCommentMirroredPlayControl(VideoPlayControl.PREPLAY, PlayState.PAUSED))
        assertEquals(VideoPlayControl.PLAY, raftVideoCommentMirroredPlayControl(VideoPlayControl.PLAY, PlayState.PLAY_END))
        assertEquals(VideoPlayControl.PREPLAY, raftVideoCommentMirroredPlayControl(VideoPlayControl.PREPLAY, PlayState.BUFFERING))
        assertEquals(VideoPlayControl.STOP, raftVideoCommentMirroredPlayControl(VideoPlayControl.STOP, PlayState.PLAYING))
    }

    @Test
    fun commentsListPutsUnanchoredFirstThenTimestampsInOrder() {
        fun comment(id: String, time: Double?) =
            ThreadAttachmentCommentItem(
                id = id, channelId = "t", senderType = "user", senderId = "u", senderName = "A",
                content = id, createdAt = "2026-06-20T01:00:00Z", reactions = emptyList(),
                anchor = time?.let { ThreadAttachmentCommentAnchor.VideoTimestamp(it) }
            )

        val ordered =
            listOf(comment("late", 90.0), comment("file", null), comment("early", 5.0), comment("file2", null))
                .inAttachmentCommentAnchorOrder()

        assertEquals(listOf("file", "file2", "early", "late"), ordered.map { it.id })
    }

    @Test
    fun videoPreviewsOfferCommentsAndAudioDoesNot() {
        val video =
            attachment.toThreadAttachmentLazyPreviewPayload(kind = "video", attachmentCommentsEnabled = true)
        assertTrue(video.supportsVideoTimestampComments())
        assertTrue(video.supportsAttachmentComments())

        val audio =
            RaftThreadAttachmentItem(id = "att-2", name = "voice.mp3", mimeType = "audio/mpeg")
                .toThreadAttachmentLazyPreviewPayload(kind = "audio", attachmentCommentsEnabled = true)
        assertFalse(audio.supportsAttachmentComments())

        val disabled = attachment.toThreadAttachmentLazyPreviewPayload(kind = "video", attachmentCommentsEnabled = false)
        assertFalse(disabled.supportsAttachmentComments())
    }
}
