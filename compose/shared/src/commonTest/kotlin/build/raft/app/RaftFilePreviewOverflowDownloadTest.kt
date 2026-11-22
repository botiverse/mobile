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

    @Test
    fun shareIsOfferedInOverflowAcrossAllFileTypesOnMobile() {
        val cases =
            listOf(
                // Video, image, markdown, generic, PDF: shown before and after download on mobile.
                Triple(true, RaftFilePreviewDownloadUiState.NotDownloaded, true) to true,
                Triple(true, RaftFilePreviewDownloadUiState.Downloaded, true) to true,
                // Hidden while downloading or still probing local download state.
                Triple(true, RaftFilePreviewDownloadUiState.Downloading, true) to false,
                Triple(true, null, true) to false,
                // Hidden when attachment cannot be fetched (e.g. local composer preview) or on desktop.
                Triple(false, RaftFilePreviewDownloadUiState.NotDownloaded, true) to false,
                Triple(true, RaftFilePreviewDownloadUiState.NotDownloaded, false) to false,
                Triple(true, RaftFilePreviewDownloadUiState.Downloaded, false) to false,
            )
        cases.forEach { (input, expected) ->
            val (canOpenWith, state, supportsShare) = input
            assertEquals(
                expected,
                raftFilePreviewOverflowShowsShare(canOpenWith, state, supportsAttachmentShare = supportsShare),
                "canOpenWith=$canOpenWith state=$state supportsShare=$supportsShare"
            )
        }
        // Un-downloaded PDF still preparing its initial copy waits; an already-downloaded PDF shares immediately.
        assertEquals(
            false,
            raftFilePreviewOverflowShowsShare(
                canOpenWith = true,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                supportsAttachmentShare = true,
                previewPreparing = true
            )
        )
        assertEquals(
            true,
            raftFilePreviewOverflowShowsShare(
                canOpenWith = true,
                downloadState = RaftFilePreviewDownloadUiState.Downloaded,
                supportsAttachmentShare = true,
                previewPreparing = true
            )
        )
    }

    @Test
    fun localFeedbackDraftUrisAreNotEligibleForRemoteShare() {
        assertEquals(
            true,
            raftFilePreviewPayloadSupportsRemoteShare(
                RaftFilePreviewPayload(
                    source = RaftFilePreviewSourceThreadAttachment,
                    attachmentId = "att-123",
                    kind = "video",
                    name = "demo.mp4"
                )
            )
        )
        assertEquals(
            true,
            raftFilePreviewPayloadSupportsRemoteShare(
                RaftFilePreviewPayload(
                    kind = "markdown",
                    name = "notes.md",
                    externalUrl = "https://files.raft.build/signed/notes.md"
                )
            )
        )
        // Feedback draft previews populate externalUrl with a local file:// or content:// URI.
        assertEquals(
            false,
            raftFilePreviewPayloadSupportsRemoteShare(
                RaftFilePreviewPayload(
                    kind = "image",
                    name = "screenshot.png",
                    externalUrl = "file:///data/user/0/build.raft.app/cache/feedback.png"
                )
            )
        )
        assertEquals(
            false,
            raftFilePreviewPayloadSupportsRemoteShare(
                RaftFilePreviewPayload(
                    kind = "image",
                    name = "screenshot.png",
                    externalUrl = "content://media/external/images/media/42"
                )
            )
        )
        assertEquals(
            false,
            raftFilePreviewPayloadSupportsRemoteShare(
                RaftFilePreviewPayload(
                    source = RaftFilePreviewSourceAgentWorkspace,
                    kind = "text",
                    name = "README.md"
                )
            )
        )
    }

    @Test
    fun lazyPdfLoadingPayloadMarksPreviewPreparingUntilReady() {
        val lazyPdfLoading =
            RaftFilePreviewPayload(
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "att-pdf",
                kind = "loading",
                requestedKind = RaftFilePreviewPdfKind,
                name = "spec.pdf"
            )
        val nonPdfLoading =
            RaftFilePreviewPayload(
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "att-bin",
                kind = "loading",
                requestedKind = "text",
                name = "notes.txt"
            )
        assertEquals(true, raftFilePreviewPdfIsPreparing(lazyPdfLoading, RaftFilePreviewPdfState.Idle))
        assertEquals(true, raftFilePreviewPdfIsPreparing(lazyPdfLoading, RaftFilePreviewPdfState.Downloading))
        assertEquals(false, raftFilePreviewPdfIsPreparing(lazyPdfLoading, RaftFilePreviewPdfState.Ready("/tmp/spec.pdf")))
        assertEquals(false, raftFilePreviewPdfIsPreparing(lazyPdfLoading, RaftFilePreviewPdfState.Failed("err")))
        assertEquals(false, raftFilePreviewPdfIsPreparing(nonPdfLoading, RaftFilePreviewPdfState.Idle))
    }
}
