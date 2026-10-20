package build.raft.app

import build.raft.app.data.AttachmentPreviewContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftFilePreviewXlsxTest {

    @Test
    fun xlsxDetectionMatchesTheWebContract() {
        assertTrue(raftFilePreviewIsXlsxAttachment("report.xlsx", "application/octet-stream"))
        assertTrue(raftFilePreviewIsXlsxAttachment("REPORT.XLSX", ""))
        assertTrue(raftFilePreviewIsXlsxAttachment("macro.xlsm", ""))
        assertTrue(
            raftFilePreviewIsXlsxAttachment(
                "sheet.bin",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
        // Legacy .xls and ODS stay on the download fallback (web #7168 scope).
        assertFalse(raftFilePreviewIsXlsxAttachment("legacy.xls", "application/vnd.ms-excel"))
        assertFalse(raftFilePreviewIsXlsxAttachment("sheet.ods", ""))
        assertFalse(raftFilePreviewIsXlsxAttachment("notes.csv", "text/csv"))
        assertFalse(raftFilePreviewIsXlsxAttachment("doc.pdf", "application/pdf"))
    }

    @Test
    fun xlsxCarrierRoundTripsThroughThePayloadJson() {
        val xlsx =
            AttachmentPreviewContent.Xlsx(
                sheets =
                    listOf(
                        build.raft.app.data.XlsxSheetPreview(
                            name = "Sheet1",
                            headers = listOf("A", "B"),
                            rows = listOf(listOf("1", "2"), listOf("3", "4")),
                            rowCount = 42,
                            columnCount = 2,
                            truncated = false,
                        )
                    ),
                sheetCount = 3,
                truncated = true,
            )
        val json = encodeRaftFilePreviewXlsx(xlsx)
        assertTrue(json.contains("\"Sheet1\""))
        val parsed = parseRaftFilePreviewXlsx(json)
        assertEquals(3, parsed?.sheetCount)
        assertEquals(true, parsed?.truncated)
        assertEquals(1, parsed?.sheets?.size)
        val sheet = parsed?.sheets?.first()
        assertEquals("Sheet1", sheet?.name)
        assertEquals(listOf("A", "B"), sheet?.headers)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), sheet?.rows)
        assertEquals(42, sheet?.rowCount)
        assertEquals(2, sheet?.columnCount)
    }

    @Test
    fun malformedXlsxPayloadJsonParsesToNull() {
        assertNull(parseRaftFilePreviewXlsx(""))
        assertNull(parseRaftFilePreviewXlsx("not-json"))
    }
}
