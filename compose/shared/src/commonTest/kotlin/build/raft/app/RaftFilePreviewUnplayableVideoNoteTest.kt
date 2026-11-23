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
    fun fallbackCardOffersOpenInAnotherAppWithoutDuplicatingExistingOpenActions() {
        assertTrue(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = true,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded
            )
        )
        assertTrue(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = true,
                downloadState = RaftFilePreviewDownloadUiState.Downloading
            )
        )
        // Downloaded or probing: primary Open/Install takes over (or is still probing), so hide duplicate.
        assertFalse(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = true,
                downloadState = RaftFilePreviewDownloadUiState.Downloaded
            )
        )
        assertFalse(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = true,
                downloadState = null
            )
        )
        // Desktop saved-file card already shows Show in Finder / Open.
        assertFalse(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = true,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                hasSavedFileActions = true
            )
        )
        // No open-with handler available.
        assertFalse(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = false,
                hasPrimaryDownloadAction = true,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded
            )
        )
        // Cards without a primary download/open button (e.g. CSV / XLSX fallback) still offer Open in another app.
        assertTrue(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = false,
                downloadState = RaftFilePreviewDownloadUiState.Downloaded
            )
        )
        assertFalse(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = false,
                hasPrimaryDownloadAction = false,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded
            )
        )
    }
}
