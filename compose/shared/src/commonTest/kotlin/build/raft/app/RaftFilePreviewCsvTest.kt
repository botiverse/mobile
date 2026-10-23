package build.raft.app

import build.raft.app.data.AttachmentPreviewContent
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftThreadApiClient
import build.raft.app.data.parseSharedAttachmentPreview
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSV attachments preview as a table (web `CsvPreviewPane`). Wire vectors are the server's own
 * outputs, botiverse/slock staging 8a10c1992:
 * `packages/server/src/services/attachmentPreviews/documentProviders.test.ts` (buildCsvPreview
 * results) wrapped as `registry.ts` answers (`{status:"ok", data, truncated}`), against the
 * `CsvAttachmentPreviewData` shape in `packages/shared/src/attachmentPreview.ts`.
 */
class RaftFilePreviewCsvTest {

    @Test
    fun serverCsvPayloadsParseToTheCsvContentWithTheirExactFields() {
        val cases =
            listOf(
                // "CSV parser handles quoted fields": the server already unquoted `"hello, world"`
                // and unescaped `""quote""`.
                CsvVector(
                    body = """{"status":"ok","data":{"kind":"csv","delimiter":",","headers":["name","count"],"rows":[["hello, world","2"],["escaped \"quote\"","3"]],"rowCount":2,"columnCount":2},"truncated":false}""",
                    delimiter = ",",
                    headers = listOf("name", "count"),
                    rows = listOf(listOf("hello, world", "2"), listOf("escaped \"quote\"", "3")),
                    rowCount = 2,
                    columnCount = 2,
                    truncated = false,
                ),
                // "CSV preview accepts one-column CSV files".
                CsvVector(
                    body = """{"status":"ok","data":{"kind":"csv","delimiter":",","headers":["email"],"rows":[["alpha@example.com"],["beta@example.com"]],"rowCount":2,"columnCount":1},"truncated":false}""",
                    delimiter = ",",
                    headers = listOf("email"),
                    rows = listOf(listOf("alpha@example.com"), listOf("beta@example.com")),
                    rowCount = 2,
                    columnCount = 1,
                    truncated = false,
                ),
                // Tab-detected file whose rows were cut (row cap / payload budget): `truncated` from
                // registry.ts isAttachmentPreviewTruncated (rows.length < rowCount).
                CsvVector(
                    body = """{"status":"ok","data":{"kind":"csv","delimiter":"\t","headers":["id","note"],"rows":[["1","a"]],"rowCount":5000,"columnCount":2},"truncated":true}""",
                    delimiter = "\t",
                    headers = listOf("id", "note"),
                    rows = listOf(listOf("1", "a")),
                    rowCount = 5000,
                    columnCount = 2,
                    truncated = true,
                ),
            )
        cases.forEach { vector ->
            val preview = assertNotNull(parseSharedAttachmentPreview(vector.body), vector.body)
            assertTrue(preview.isOk)
            assertEquals(vector.truncated, preview.truncated, vector.body)
            val csv = assertIs<AttachmentPreviewContent.Csv>(preview.content)
            assertEquals(vector.delimiter, csv.delimiter)
            assertEquals(vector.headers, csv.headers)
            assertEquals(vector.rows, csv.rows)
            assertEquals(vector.rowCount, csv.rowCount)
            assertEquals(vector.columnCount, csv.columnCount)
        }
    }

    @Test
    fun csvCarrierFollowsTheServerCapsAndTruncationRule() {
        val small =
            raftFilePreviewCsvCarrier(
                AttachmentPreviewContent.Csv(",", listOf("name", "count"), listOf(listOf("alpha", "1")), rowCount = 1, columnCount = 2),
                responseTruncated = false,
            )
        assertEquals(RaftFilePreviewCsvCarrier(listOf("name", "count"), listOf(listOf("alpha", "1")), 1, 2, truncated = false), small)

        // Fewer rows than the file holds is truncated even when the response flag is missing.
        val trimmed =
            raftFilePreviewCsvCarrier(
                AttachmentPreviewContent.Csv(",", listOf("a"), listOf(listOf("1")), rowCount = 300, columnCount = 1),
                responseTruncated = false,
            )
        assertEquals(true, trimmed?.truncated)
        assertEquals(300, trimmed?.rowCount)

        // A payload past the server caps is cut to 200 rows x 12 columns; columnCount keeps the file's width.
        val wide = List(20) { "h$it" }
        val oversized =
            assertNotNull(
                raftFilePreviewCsvCarrier(
                    AttachmentPreviewContent.Csv(",", wide, List(250) { row -> List(20) { "$row-$it" } }, rowCount = 250, columnCount = 20),
                    responseTruncated = false,
                )
            )
        assertEquals(RaftFilePreviewCsvMaxColumns, oversized.headers.size)
        assertEquals(RaftFilePreviewCsvMaxRows, oversized.rows.size)
        assertTrue(oversized.rows.all { it.size == RaftFilePreviewCsvMaxColumns })
        assertEquals(20, oversized.columnCount)
        assertEquals(true, oversized.truncated)

        // Nothing to draw as a table: no header row.
        assertNull(raftFilePreviewCsvCarrier(AttachmentPreviewContent.Csv(",", emptyList(), emptyList(), 0, 0), responseTruncated = false))
    }

    @Test
    fun csvCarrierRoundTripsThroughThePayloadJsonAndRejectsGarbage() {
        val carrier = RaftFilePreviewCsvCarrier(listOf("name", ""), listOf(listOf("a,b", "\"q\"")), rowCount = 7, columnCount = 3, truncated = true)
        assertEquals(carrier, parseRaftFilePreviewCsv(encodeRaftFilePreviewCsv(carrier)))
        assertNull(parseRaftFilePreviewCsv(""))
        assertNull(parseRaftFilePreviewCsv("not-json"))
        assertNull(parseRaftFilePreviewCsv("""{"headers":[],"rows":[],"rowCount":0,"columnCount":0,"truncated":false}"""))
    }

    @Test
    fun csvDetectionMatchesTheWebClient() {
        listOf(
            "metrics.csv" to "application/octet-stream",
            "METRICS.CSV" to "",
            "metrics" to "text/csv; charset=utf-8",
            "metrics" to "application/csv",
            "export.csv" to "application/vnd.ms-excel",
            "export" to "application/vnd.ms-excel",
        ).forEach { (name, mime) -> assertTrue(raftFilePreviewIsCsvAttachment(name, mime), "$name ($mime)") }
        listOf(
            "legacy.xls" to "application/vnd.ms-excel",
            "legacy.xls" to "text/csv",
            "macro.xlw" to "application/vnd.ms-excel",
            "notes.md" to "text/markdown",
            "book.xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "data.tsv" to "text/tab-separated-values",
        ).forEach { (name, mime) -> assertFalse(raftFilePreviewIsCsvAttachment(name, mime), "$name ($mime)") }
    }

    @Test
    fun loadingACsvAttachmentResolvesToTheCsvTableState() = runTest {
        val network =
            FakeCsvPreviewNetwork(
                previewBody = """{"status":"ok","data":{"kind":"csv","delimiter":",","headers":["name","count","owner"],"rows":[["alpha","1","koda"],["beta","2","martin"]],"rowCount":2,"columnCount":3},"truncated":false}"""
            )
        val loaded = assertNotNull(csvLoadingPayload().load(network))

        assertEquals("csv", loaded.kind)
        assertNull(loaded.error)
        assertEquals("", loaded.content)
        assertEquals(
            RaftFilePreviewCsvCarrier(
                headers = listOf("name", "count", "owner"),
                rows = listOf(listOf("alpha", "1", "koda"), listOf("beta", "2", "martin")),
                rowCount = 2,
                columnCount = 3,
                truncated = false,
            ),
            parseRaftFilePreviewCsv(loaded.csvTableJson)
        )
        // The table comes from the bounded preview alone: the raw file is never downloaded.
        assertEquals(listOf("GET attachments/att-csv/preview"), network.calls)
    }

    @Test
    fun aRestoredRouteWithoutTheFileNameStillDrawsTheServerCsvTable() = runTest {
        val network =
            FakeCsvPreviewNetwork(
                previewBody = """{"status":"ok","data":{"kind":"csv","delimiter":";","headers":["a"],"rows":[["1"]],"rowCount":9,"columnCount":1},"truncated":true}"""
            )
        val restored = csvLoadingPayload().copy(name = "", path = "", externalFileName = "", externalMimeType = "")

        val loaded = assertNotNull(restored.load(network))

        assertEquals("csv", loaded.kind)
        val carrier = assertNotNull(parseRaftFilePreviewCsv(loaded.csvTableJson))
        assertEquals(true, carrier.truncated)
        assertEquals(9, carrier.rowCount)
    }

    @Test
    fun withoutAServerCsvPreviewATextCsvFallsBackToPlainTextAndAnExcelExportToNoPreview() = runTest {
        val unsupported = """{"status":"unsupported","reason":"too_large"}"""

        val textNetwork = FakeCsvPreviewNetwork(previewBody = unsupported, rawBody = "name,count\nalpha,1\n")
        val text = assertNotNull(csvLoadingPayload().load(textNetwork))
        assertEquals("text", text.kind)
        assertEquals("name,count\nalpha,1\n", text.content)
        assertEquals("", text.csvTableJson)

        // An Excel-MIME file the server will not read as CSV (a binary workbook) is never shown as text.
        val excelNetwork = FakeCsvPreviewNetwork(previewBody = unsupported, rawBody = "ÐÏ\u0011à")
        val excelExport =
            csvLoadingPayload().copy(
                requestedKind = RaftThreadAttachmentFileDetailKind,
                name = "export",
                path = "export",
                externalFileName = "export",
                externalMimeType = "application/vnd.ms-excel",
            )
        val excel = assertNotNull(excelExport.load(excelNetwork))
        assertEquals("unsupported", excel.kind)
        assertEquals("", excel.content)
        assertEquals(listOf("GET attachments/att-csv/preview"), excelNetwork.calls)
    }

    private fun csvLoadingPayload(): RaftFilePreviewPayload =
        RaftThreadAttachmentItem(id = "att-csv", name = "metrics.csv", mimeType = "text/csv")
            .toThreadAttachmentLazyPreviewPayload(kind = "text")

    private suspend fun RaftFilePreviewPayload.load(network: FakeCsvPreviewNetwork): RaftFilePreviewPayload? =
        loadThreadAttachmentPreview(
            threadApi = RaftThreadApiClient(NetworkRaftThreadApi(network)),
            networkClient = network,
            priority = DataRequestPriority.Foreground,
            truncatedPreviewHint = "(truncated)"
        )
}

private data class CsvVector(
    val body: String,
    val delimiter: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val rowCount: Int,
    val columnCount: Int,
    val truncated: Boolean,
)

private class FakeCsvPreviewNetwork(
    private val previewBody: String,
    private val rawBody: String = "",
) : RaftNetworkClient {
    val calls = mutableListOf<String>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += "$method $path"
        return when (path) {
            "attachments/att-csv/preview" -> HomeNetworkResponse(code = 200, bodyText = previewBody)
            "attachments/att-csv/url" ->
                HomeNetworkResponse(code = 200, bodyText = """{"url":"https://files.raft.test/metrics.csv","expiresAt":"2026-10-23T10:00:00.000Z"}""")
            else -> HomeNetworkResponse(code = 404, bodyText = null)
        }
    }

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += "RAW $url"
        return HomeNetworkResponse(code = 200, bodyText = rawBody)
    }
}
