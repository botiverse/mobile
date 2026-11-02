package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin (#wg-mobile-feedback, after #4093): a file that cannot be previewed shows the file page's
 * download card, not "Preview unavailable". Web `openDocumentPreview.ts` downloads instead of
 * previewing whenever the server answers `unsupported`. A load error (no response, the signed URL
 * refused, the file gone) stays an error, now with Retry.
 */
class RaftFilePreviewNoPreviewDownloadTest {

    @Test
    fun theServerAnswerKeepsUnsupportedApartFromAFailedRequest() {
        assertIs<RaftFilePreviewServerAnswer.Ok>(
            raftFilePreviewServerAnswer(200, """{"status":"ok","data":{"kind":"text","text":"hi"}}""")
        )
        assertEquals(
            RaftFilePreviewServerAnswer.Unsupported("too_large"),
            raftFilePreviewServerAnswer(200, """{"status":"unsupported","reason":"too_large"}""")
        )
        assertEquals(
            RaftFilePreviewServerAnswer.Unsupported("unsupported"),
            raftFilePreviewServerAnswer(200, """{"status":"unsupported"}""")
        )
        assertEquals(RaftFilePreviewServerAnswer.Failed, raftFilePreviewServerAnswer(500, null))
        assertEquals(RaftFilePreviewServerAnswer.Failed, raftFilePreviewServerAnswer(403, """{"error":"forbidden"}"""))
        assertEquals(RaftFilePreviewServerAnswer.Failed, raftFilePreviewServerAnswer(200, "not json"))
    }

    @Test
    fun onlyAFileTheServerCannotPreviewFallsBackToTheDownloadCard() {
        val unsupported = RaftFilePreviewServerAnswer.Unsupported("unsupported")
        assertTrue(raftFilePreviewRawFallbackShowsDownload(unsupported, httpCode = 415))
        assertTrue(raftFilePreviewRawFallbackShowsDownload(unsupported, httpCode = 400))
        // Load errors stay errors: no response, refused link, file gone, transient.
        listOf<Int?>(null, 401, 403, 404, 408, 410, 429, 500, 503).forEach { code ->
            assertFalse(raftFilePreviewRawFallbackShowsDownload(unsupported, code), "code=$code")
        }
        // Without the server's "no preview" a failed raw fetch is never the download card.
        assertFalse(raftFilePreviewRawFallbackShowsDownload(RaftFilePreviewServerAnswer.Failed, httpCode = 415))
    }

    @Test
    fun binaryBytesAreNotText() {
        assertTrue(raftFilePreviewLooksBinary("PK\u0003\u0004\u0000\u0000rest"))
        assertFalse(raftFilePreviewLooksBinary("page\towner\nindex.md\tagent\n"))
        assertFalse(raftFilePreviewLooksBinary("a".repeat(8000) + "\u0000"))
    }

    @Test
    fun anUnsupportedFileWhoseBytesAreBinaryShowsTheDownloadCard() = runTest {
        val network = FakeNoPreviewNetwork(rawCode = 200, rawBody = "\u0000\u0001binary\u0000")
        val loaded = network.load(name = "dump.log")

        assertEquals("unsupported", loaded.kind)
        assertNull(loaded.error)
        assertEquals(RaftFilePreviewNoPreviewReasonUnsupported, loaded.noPreviewReason)
        assertFalse(loaded.canRetryPreviewLoad())
    }

    @Test
    fun anUnsupportedFileTheStoreRefusesAsAFormatShowsTheDownloadCard() = runTest {
        val loaded = FakeNoPreviewNetwork(rawCode = 415, rawBody = null).load(name = "pages.tsv")

        assertEquals("unsupported", loaded.kind)
        assertNull(loaded.error)
    }

    @Test
    fun aNetworkFailureIsAnErrorWithRetry() = runTest {
        val loaded = FakeNoPreviewNetwork(rawCode = null, rawBody = null).load(name = "pages.tsv")

        assertEquals("error", loaded.kind)
        assertEquals("text_preview_fetch_failed", loaded.error)
        assertTrue(loaded.canRetryPreviewLoad())
        val reload = loaded.withPreviewReload()
        assertEquals("loading", reload.kind)
        assertNull(reload.error)
        assertEquals(loaded.attachmentId, reload.attachmentId)
        assertEquals(loaded.requestedKind, reload.requestedKind)
    }

    @Test
    fun anExpiredLinkOrAGoneFileIsAnErrorWithRetry() = runTest {
        listOf(403, 404, 410, 502).forEach { code ->
            val loaded = FakeNoPreviewNetwork(rawCode = code, rawBody = null).load(name = "pages.tsv")
            assertEquals("error", loaded.kind, "code=$code")
            assertTrue(loaded.canRetryPreviewLoad(), "code=$code")
        }
    }

    @Test
    fun aMissingSignedUrlIsAnErrorWithRetry() = runTest {
        val loaded = FakeNoPreviewNetwork(rawCode = 200, rawBody = "x", urlCode = 403).load(name = "pages.tsv")

        assertEquals("error", loaded.kind)
        assertEquals("text_preview_url_failed", loaded.error)
        assertTrue(loaded.canRetryPreviewLoad())
    }

    @Test
    fun aSpreadsheetTooLargeToPreviewShowsTheDownloadCardWithTheReason() = runTest {
        val loaded =
            FakeNoPreviewNetwork(rawCode = 200, rawBody = null, reason = "too_large")
                .load(name = "q3.xlsx", mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", kind = "xlsx")

        assertEquals("unsupported", loaded.kind)
        assertEquals(RaftFilePreviewNoPreviewReasonTooLarge, loaded.noPreviewReason)
        assertEquals(
            Res.string.file_preview_no_preview_too_large_download.desc(),
            raftFilePreviewUnsupportedFileMessage(loaded, downloadable = true)
        )
    }

    @Test
    fun theCardSaysWhyWhenTheServerGaveAReason() {
        fun message(reason: String) =
            raftFilePreviewUnsupportedFileMessage(
                RaftFilePreviewPayload(kind = "unsupported", noPreviewReason = reason),
                downloadable = true
            )
        assertEquals(Res.string.file_preview_no_preview_too_large_download.desc(), message("too_large"))
        assertEquals(Res.string.file_preview_no_preview_temporarily_download.desc(), message("unavailable"))
        assertEquals(Res.string.file_preview_download_to_open.desc(), message("unsupported"))
        assertEquals(Res.string.file_preview_download_to_open.desc(), message(""))
    }
}

/**
 * The server answers `unsupported` for the preview; the signed URL and the raw body answer as
 * configured ([rawCode] null: no response).
 */
private class FakeNoPreviewNetwork(
    private val rawCode: Int?,
    private val rawBody: String?,
    private val urlCode: Int = 200,
    private val reason: String = "unsupported",
) : RaftNetworkClient {
    suspend fun load(
        name: String,
        mimeType: String = "application/octet-stream",
        kind: String = "text",
    ): RaftFilePreviewPayload =
        assertNotNull(
            RaftThreadAttachmentItem(id = "att-1", name = name, mimeType = mimeType)
                .toThreadAttachmentLazyPreviewPayload(kind = kind)
                .loadThreadAttachmentPreview(
                    threadApi = RaftThreadApiClient(NetworkRaftThreadApi(this)),
                    networkClient = this,
                    priority = DataRequestPriority.Foreground,
                    truncatedPreviewHint = "(truncated)",
                )
        )

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        when (path) {
            "attachments/att-1/preview" ->
                HomeNetworkResponse(code = 200, bodyText = """{"status":"unsupported","reason":"$reason"}""")
            "attachments/att-1/url" ->
                if (urlCode == 200) {
                    HomeNetworkResponse(
                        code = 200,
                        bodyText = """{"url":"https://files.raft.test/f?X-Amz-Signature=redacted","expiresAt":"2026-11-02T10:00:00.000Z"}"""
                    )
                } else {
                    HomeNetworkResponse(code = urlCode, bodyText = null)
                }
            else -> HomeNetworkResponse(code = 404, bodyText = null)
        }

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse =
        rawCode?.let { HomeNetworkResponse(code = it, bodyText = rawBody) } ?: throw RuntimeException("offline")
}
