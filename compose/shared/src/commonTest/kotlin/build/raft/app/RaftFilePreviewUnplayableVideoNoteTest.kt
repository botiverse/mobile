package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #triage:#442: a video the platform's player cannot play (WebM, MKV, TS/PS, FLV on iOS AVPlayer
 * and the macOS desktop player) opens the file page's download card, which now says why and
 * offers Download and Open in another app. Android's Media3 plays it, so Android never shows the note.
 */
class RaftFilePreviewUnplayableVideoNoteTest {

    private fun attachment(name: String, mimeType: String = "") =
        RaftThreadAttachmentItem(id = "att-video", name = name, mimeType = mimeType)
            .toThreadAttachmentLazyPreviewPayload(kind = RaftThreadAttachmentFileDetailKind)
            .withNoPreviewDownload()

    @Test
    fun aWebmOnIosShowsTheIphoneNoteAndOnAndroidPlays() {
        val webm = attachment("screen-recording.webm", "video/webm")

        assertEquals(
            Res.string.file_preview_video_format_unplayable_ios.desc(),
            raftFilePreviewUnsupportedFileMessage(webm, downloadable = true, videoProfile = RaftVideoPlayerFormatProfile.IosAvPlayer)
        )
        assertFalse(raftVideoPlayerFormatProfileSupports(RaftVideoPlayerFormatProfile.IosAvPlayer, RaftPreviewableVideoContainer.WebM))

        assertTrue(raftVideoPlayerFormatProfileSupports(RaftVideoPlayerFormatProfile.AndroidMedia3, RaftPreviewableVideoContainer.WebM))
        assertNull(raftFilePreviewUnplayableVideoNote(webm, RaftVideoPlayerFormatProfile.AndroidMedia3))
        assertEquals(
            Res.string.file_preview_download_to_open.desc(),
            raftFilePreviewUnsupportedFileMessage(webm, downloadable = true, videoProfile = RaftVideoPlayerFormatProfile.AndroidMedia3)
        )
    }

    @Test
    fun anUnplayableVideoOffersOpenInAnotherAppUntilItIsDownloaded() {
        val webm = attachment("screen-recording.webm", "video/webm")
        val ios = RaftVideoPlayerFormatProfile.IosAvPlayer

        assertTrue(raftFilePreviewUnplayableVideoOffersOpenInOtherApp(webm, RaftFilePreviewDownloadUiState.NotDownloaded, ios))
        assertTrue(raftFilePreviewUnplayableVideoOffersOpenInOtherApp(webm, RaftFilePreviewDownloadUiState.Downloading, ios))
        // Downloaded: the card's own Open and Share take over.
        assertFalse(raftFilePreviewUnplayableVideoOffersOpenInOtherApp(webm, RaftFilePreviewDownloadUiState.Downloaded, ios))
        // Android plays it; a playable MP4 never reaches the card.
        assertFalse(
            raftFilePreviewUnplayableVideoOffersOpenInOtherApp(
                webm,
                RaftFilePreviewDownloadUiState.NotDownloaded,
                RaftVideoPlayerFormatProfile.AndroidMedia3
            )
        )
        assertFalse(
            raftFilePreviewUnplayableVideoOffersOpenInOtherApp(attachment("clip.mp4", "video/mp4"), RaftFilePreviewDownloadUiState.NotDownloaded, ios)
        )
    }

    @Test
    fun everyContainerAvPlayerCannotDemuxGetsTheNoteByProfile() {
        listOf(
            attachment("clip.webm"),
            attachment("clip.mkv"),
            attachment("clip.flv", "video/x-flv"),
            attachment("clip.m2ts"),
            attachment("clip.mpg", "video/mpeg"),
        ).forEach { payload ->
            assertEquals(
                Res.string.file_preview_video_format_unplayable_ios.desc(),
                raftFilePreviewUnplayableVideoNote(payload, RaftVideoPlayerFormatProfile.IosAvPlayer),
                payload.name
            )
            assertEquals(
                Res.string.file_preview_video_format_unplayable_mac.desc(),
                raftFilePreviewUnplayableVideoNote(payload, RaftVideoPlayerFormatProfile.DesktopMacAvFoundation),
                payload.name
            )
            assertNull(raftFilePreviewUnplayableVideoNote(payload, RaftVideoPlayerFormatProfile.AndroidMedia3), payload.name)
        }
    }

    @Test
    fun playableVideosOtherFilesAndPlatformsWithoutAPlayerKeepTheirMessage() {
        // AVPlayer plays MP4/MOV, a PDF is no video, and desktop without its player has no profile.
        listOf(
            attachment("clip.mp4", "video/mp4") to RaftVideoPlayerFormatProfile.IosAvPlayer,
            attachment("clip.mov") to RaftVideoPlayerFormatProfile.DesktopMacAvFoundation,
            attachment("report.pdf", "application/pdf") to RaftVideoPlayerFormatProfile.IosAvPlayer,
            attachment("clip.webm", "video/webm") to null,
        ).forEach { (payload, profile) ->
            assertNull(raftFilePreviewUnplayableVideoNote(payload, profile), "${payload.name} $profile")
            assertEquals(
                Res.string.file_preview_download_to_open.desc(),
                raftFilePreviewUnsupportedFileMessage(payload, downloadable = true, videoProfile = profile),
                "${payload.name} $profile"
            )
        }
    }
}
