package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The file preview's overflow Download (artin 10-31: a previewed video could not be saved). */
class RaftFilePreviewOverflowDownloadTest {
    @Test
    fun downloadIsOfferedForPdfAndVideoUntilDownloaded() {
        val cases =
            listOf(
                Triple("video", RaftFilePreviewDownloadUiState.NotDownloaded, true),
                Triple(RaftFilePreviewPdfKind, RaftFilePreviewDownloadUiState.NotDownloaded, true),
                Triple("video", RaftFilePreviewDownloadUiState.Downloading, false),
                Triple("video", RaftFilePreviewDownloadUiState.Downloaded, false),
                Triple("video", null, false),
                // Other viewers keep the file page's own Download / Save to Photos.
                Triple("image", RaftFilePreviewDownloadUiState.NotDownloaded, false),
                Triple("markdown", RaftFilePreviewDownloadUiState.NotDownloaded, false),
            )
        cases.forEach { (kind, state, expected) ->
            assertEquals(expected, raftFilePreviewOverflowShowsDownload(kind, canOpenWith = true, downloadState = state), "kind=$kind state=$state")
        }
    }

    @Test
    fun downloadNeedsAnAttachmentThatCanBeFetched() {
        assertEquals(
            false,
            raftFilePreviewOverflowShowsDownload("video", canOpenWith = false, downloadState = RaftFilePreviewDownloadUiState.NotDownloaded)
        )
    }
}
