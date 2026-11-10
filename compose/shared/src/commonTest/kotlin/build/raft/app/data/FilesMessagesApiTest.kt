package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FilesMessagesApiTest {
    @Test
    fun parseSharedAttachmentPreviewCoversUnionKindsAndUnknowns() {
        val text = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"text","text":"hello"},"truncated":false}""")
        assertEquals("hello", assertIs<AttachmentPreviewContent.Text>(text?.content).text)
        assertEquals(false, text?.truncated)

        val csv = parseSharedAttachmentPreview(
            """{"status":"ok","data":{"kind":"csv","delimiter":",","headers":["a"],"rows":[["1"],["2"]],"rowCount":9,"columnCount":1},"truncated":true}"""
        )
        val csvContent = assertIs<AttachmentPreviewContent.Csv>(csv?.content)
        assertEquals(9, csvContent.rowCount)
        assertEquals(true, csv?.truncated)

        val diff = parseSharedAttachmentPreview(
            """{"status":"ok","data":{"kind":"diff","stats":{"files":2,"hunks":3,"additions":10,"deletions":4}}}"""
        )
        assertEquals(2, assertIs<AttachmentPreviewContent.Diff>(diff?.content).files)
        val xlsx = parseSharedAttachmentPreview(
            """{"status":"ok","data":{"kind":"xlsx","sheets":[{"name":"Sheet1","headers":["A","B"],"rows":[["1","2"],["3","4"]],"rowCount":42,"columnCount":2,"truncated":false}],"sheetCount":3,"truncated":true},"truncated":false}"""
        )
        val xlsxContent = assertIs<AttachmentPreviewContent.Xlsx>(xlsx?.content)
        assertEquals(3, xlsxContent.sheetCount)
        assertEquals(true, xlsxContent.truncated)
        assertEquals(1, xlsxContent.sheets.size)
        val sheet = xlsxContent.sheets.first()
        assertEquals("Sheet1", sheet.name)
        assertEquals(listOf("A", "B"), sheet.headers)
        assertEquals(2, sheet.rows.size)
        assertEquals(42, sheet.rowCount)
        assertEquals(2, sheet.columnCount)
        assertEquals(false, sheet.truncated)

        // A sheet-less xlsx payload must fall back to the legacy path (content null), never render a blank grid.
        val sheetless = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"xlsx","sheets":[],"sheetCount":0,"truncated":false}}""")
        assertEquals("ok", sheetless?.status)
        assertEquals(null, sheetless?.content)

        val pdf = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"pdf"}}""")
        assertIs<AttachmentPreviewContent.Pdf>(pdf?.content)

        val unsupported = parseSharedAttachmentPreview("""{"status":"unsupported"}""")
        assertEquals("unsupported", unsupported?.status)
        assertEquals(null, unsupported?.content)
        assertEquals(false, unsupported?.isOk)

        val unknownKind = parseSharedAttachmentPreview("""{"status":"ok","data":{"kind":"hologram"}}""")
        assertEquals(null, unknownKind?.content)

        assertEquals(null, parseSharedAttachmentPreview("not json"))
    }

}
