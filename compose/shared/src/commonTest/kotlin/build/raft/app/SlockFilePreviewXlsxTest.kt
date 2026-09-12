package build.raft.app

import build.raft.app.data.AttachmentPreviewContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockFilePreviewXlsxTest {

    @Test
    fun xlsxDetectionMatchesTheWebContract() {
        assertTrue(slockFilePreviewIsXlsxAttachment("report.xlsx", "application/octet-stream"))
        assertTrue(slockFilePreviewIsXlsxAttachment("REPORT.XLSX", ""))
        assertTrue(slockFilePreviewIsXlsxAttachment("macro.xlsm", ""))
        assertTrue(
            slockFilePreviewIsXlsxAttachment(
                "sheet.bin",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
        // Legacy .xls and ODS stay on the download fallback (web #7168 scope).
        assertFalse(slockFilePreviewIsXlsxAttachment("legacy.xls", "application/vnd.ms-excel"))
        assertFalse(slockFilePreviewIsXlsxAttachment("sheet.ods", ""))
        assertFalse(slockFilePreviewIsXlsxAttachment("notes.csv", "text/csv"))
        assertFalse(slockFilePreviewIsXlsxAttachment("doc.pdf", "application/pdf"))
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
        val json = encodeSlockFilePreviewXlsx(xlsx)
        assertTrue(json.contains("\"Sheet1\""))
        val parsed = parseSlockFilePreviewXlsx(json)
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
        assertNull(parseSlockFilePreviewXlsx(""))
        assertNull(parseSlockFilePreviewXlsx("not-json"))
    }
}
