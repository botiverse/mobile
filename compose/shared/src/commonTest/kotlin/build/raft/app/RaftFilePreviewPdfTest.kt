package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-app PDF preview: which attachments open the PDF viewer (web `attachmentPreview.ts` parity:
 * a `.pdf` name or `application/pdf`), and the page's download -> ready -> error states.
 */
class RaftFilePreviewPdfTest {
    @Test
    fun pdfIsRecognisedByMimeTypeOrByExtension() {
        val pdf =
            listOf(
                "report.pdf" to "application/pdf",
                "scan" to "application/pdf", // no extension: the type alone
                "scan.bin" to "Application/PDF; charset=binary", // type with parameters, any case
                "Quarterly.PDF" to "", // extension alone, any case
                "notes.pdf" to "application/octet-stream", // generic type, PDF name
                "export.pdf?download=1" to "", // URL-ish name
            )
        val notPdf =
            listOf(
                "report.pdf.zip" to "application/zip",
                "pdf" to "text/plain",
                "readme.md" to "text/markdown",
                "spec.pdfx" to "",
            )
        pdf.forEach { (name, mime) -> assertTrue(raftFilePreviewIsPdfAttachment(name, mime), "$name ($mime)") }
        notPdf.forEach { (name, mime) -> assertFalse(raftFilePreviewIsPdfAttachment(name, mime), "$name ($mime)") }
    }

    @Test
    fun pdfAttachmentsOpenThePdfPreviewFromTheCardAndTheFilesPanel() {
        // The message card and the channel files panel both route through threadAttachmentOpenRoute.
        listOf("spec.pdf" to "application/pdf", "spec.pdf" to "", "spec" to "application/pdf").forEach { (name, mime) ->
            val attachment = RaftThreadAttachmentItem(id = "att-pdf", name = name, mimeType = mime)
            val route = attachment.threadAttachmentOpenRoute()
            assertEquals(RaftThreadAttachmentOpenRoute.Preview(RaftFilePreviewPdfKind), route, "$name ($mime)")

            val payload = attachment.toThreadAttachmentLazyPreviewPayload(kind = route.requestedPreviewKind)
            val launch = raftFilePreviewLaunchDescriptor(sessionId = "s-pdf", payload = payload)
            assertEquals("loading", payload.kind)
            assertEquals(RaftFilePreviewPdfKind, payload.requestedKind)
            assertEquals(RaftFilePreviewRouteThreadAttachment, launch.routeKind)
            assertEquals("att-pdf", launch.attachmentId)
            assertEquals(RaftFilePreviewPdfKind, launch.previewKind)
        }
    }

    @Test
    fun downloadThenReadyShowsTheCachedCopy() {
        val progress = BridgeAttachmentDownloadProgress(percent = 40, downloadedBytes = 400L, totalBytes = 1000L)
        val downloading = raftFilePreviewPdfDownloadStarted()
        assertEquals(RaftFilePreviewPdfState.Downloading(), downloading)

        val moving = downloading.onPdfDownloadProgress(progress)
        assertEquals(40, (moving as RaftFilePreviewPdfState.Downloading).progress?.percent)

        val ready = moving.onPdfPrepared(status = "ready", path = " /cache/att/spec.pdf ")
        assertEquals(RaftFilePreviewPdfState.Ready("/cache/att/spec.pdf"), ready)
        // A late progress sample or a second answer does not move a shown document.
        assertEquals(ready, ready.onPdfDownloadProgress(progress))
        assertEquals(ready, ready.onPdfPrepared(status = "download_failed", path = null))
    }

    @Test
    fun hostAnswersOtherThanReadyEndTheDownload() {
        // (status, path) -> expected state after a running download.
        listOf(
            Triple("download_failed", null, RaftFilePreviewPdfState.Failed(RaftFilePreviewPdfFailure.Download, "Network down")),
            Triple("storage_failed", null, RaftFilePreviewPdfState.Failed(RaftFilePreviewPdfFailure.Download, "Network down")),
            Triple("ready", "", RaftFilePreviewPdfState.Failed(RaftFilePreviewPdfFailure.Download, "Network down")),
            Triple(null, null, RaftFilePreviewPdfState.Failed(RaftFilePreviewPdfFailure.Download, "Network down")),
            Triple("cancelled", null, RaftFilePreviewPdfState.Idle),
        ).forEach { (status, path, expected) ->
            assertEquals(
                expected,
                RaftFilePreviewPdfState.Downloading().onPdfPrepared(status = status, path = path, message = "Network down"),
                "status=$status path=$path"
            )
        }
        val failed = RaftFilePreviewPdfState.Downloading().onPdfPrepared(status = "download_failed", path = null, message = " ")
        assertNull((failed as RaftFilePreviewPdfState.Failed).message, "a blank message falls back to the default copy")
        assertTrue(failed.canRetry)
    }

    @Test
    fun aPasswordOrADamagedFileFailsTheShownDocumentWithoutRetry() {
        val ready = RaftFilePreviewPdfState.Ready("/cache/att/spec.pdf")
        listOf(
            RaftPdfDocumentError.PasswordProtected to RaftFilePreviewPdfFailure.PasswordProtected,
            RaftPdfDocumentError.Corrupt to RaftFilePreviewPdfFailure.Corrupt,
        ).forEach { (error, failure) ->
            val failed = ready.onPdfRenderError(error)
            assertEquals(RaftFilePreviewPdfState.Failed(failure), failed, "$error")
            assertFalse(failed.canRetry, "$error: retrying the same file cannot help; open it elsewhere")
        }
        // A render error only applies to a shown document.
        assertIs<RaftFilePreviewPdfState.Downloading>(
            RaftFilePreviewPdfState.Downloading().onPdfRenderError(RaftPdfDocumentError.Corrupt)
        )
    }

    @Test
    fun pageIndicatorIsOneBasedAndWaitsForThePageCount() {
        assertNull(raftFilePreviewPdfPageIndicatorArgs(currentPage = 0, pageCount = 0))
        assertEquals("3" to "12", raftFilePreviewPdfPageIndicatorArgs(currentPage = 2, pageCount = 12))
        assertEquals("12" to "12", raftFilePreviewPdfPageIndicatorArgs(currentPage = 40, pageCount = 12))
    }
}
