package build.raft.app.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Names of files handed to other apps (Android Share/Open with, iOS share sheet, Save to Files) and
 * of picked uploads: receivers name and type the file from its last path component.
 */
class RaftShareFileNameTest {
    private val types = mapOf("pdf" to "application/pdf", "png" to "image/png", "mp4" to "video/mp4")

    @Test
    fun displayNamesKeepUnicodeSpacesAndTheirExtension() {
        assertEquals("invoice.pdf", RaftShareFileName.sanitize("invoice.pdf", null))
        assertEquals("发票 2026.pdf", RaftShareFileName.sanitize("发票 2026.pdf", null))
        assertEquals("請求書（最終）.pdf", RaftShareFileName.sanitize("請求書（最終）.pdf", null))
        assertEquals("Q3 report (final).pdf", RaftShareFileName.sanitize("Q3 report (final).pdf", null))
        assertEquals("😀 photo.png", RaftShareFileName.sanitize("😀 photo.png", null))
    }

    @Test
    fun pathsReservedAndControlCharactersAreNeutralised() {
        assertEquals("b_c_.pdf", RaftShareFileName.sanitize("../a/b:c?.pdf", null))
        assertEquals("evil.pdf", RaftShareFileName.sanitize("..\\..\\evil.pdf", null))
        assertEquals("hidden.pdf", RaftShareFileName.sanitize("..hidden.pdf", null))
        assertEquals("a_b.txt", RaftShareFileName.sanitize("a\u0000b.txt", null))
        assertEquals("x_y_z_.txt", RaftShareFileName.sanitize("x<y>z|.txt", null))
        assertEquals("trailing.pdf", RaftShareFileName.sanitize("trailing.pdf. ", null))
    }

    @Test
    fun aMissingNameOrExtensionIsFilledIn() {
        assertEquals("attachment", RaftShareFileName.sanitize(null, null))
        assertEquals("attachment.pdf", RaftShareFileName.sanitize("   ", "pdf"))
        assertEquals("attachment.pdf", RaftShareFileName.sanitize("..", "pdf"))
        assertEquals("report.pdf", RaftShareFileName.sanitize("report", "pdf"))
        assertEquals("report.pdf", RaftShareFileName.sanitize("report", ".pdf"))
        assertEquals("report.txt", RaftShareFileName.sanitize("report.txt", "pdf"))
        assertEquals("report", RaftShareFileName.sanitize("report", "p/df"))
    }

    @Test
    fun longNamesAreBoundedInUtf8AndKeepTheExtension() {
        val ascii = RaftShareFileName.sanitize("a".repeat(400) + ".pdf", null)
        assertEquals(RaftShareFileName.MAX_UTF8_BYTES, ascii.encodeToByteArray().size)
        assertTrue(ascii.endsWith(".pdf"))
        val cjk = RaftShareFileName.sanitize("长".repeat(300) + ".pdf", null)
        assertTrue(cjk.encodeToByteArray().size <= RaftShareFileName.MAX_UTF8_BYTES)
        assertTrue(cjk.endsWith(".pdf"))
        assertTrue(cjk.removeSuffix(".pdf").all { it == '长' })
        val emoji = RaftShareFileName.sanitize("😀".repeat(100) + ".png", null)
        assertTrue(emoji.encodeToByteArray().size <= RaftShareFileName.MAX_UTF8_BYTES)
        // No surrogate pair is split.
        assertEquals(0, emoji.removeSuffix(".png").length % 2)
    }

    @Test
    fun mimeTypesAreNormalisedAndGenericOnesDeferToTheExtension() {
        val lookup: (String) -> String? = { types[it] }
        assertEquals("application/pdf", RaftShareFileName.resolveMimeType("invoice.pdf", "application/PDF; charset=binary", lookup))
        assertEquals("application/pdf", RaftShareFileName.resolveMimeType("invoice.pdf", "application/octet-stream", lookup))
        assertEquals("application/pdf", RaftShareFileName.resolveMimeType("invoice.PDF", null, lookup))
        assertEquals("application/pdf", RaftShareFileName.resolveMimeType("invoice.pdf", "", lookup))
        assertEquals("text/plain", RaftShareFileName.resolveMimeType("notes.pdf", "text/plain;charset=utf-8", lookup))
        assertEquals("application/octet-stream", RaftShareFileName.resolveMimeType("blob", "application/octet-stream", lookup))
        assertEquals("application/octet-stream", RaftShareFileName.resolveMimeType("blob.xyz", "garbage", lookup))
        assertEquals("application/octet-stream", RaftShareFileName.resolveMimeType("x.pdf", null) { error("lookup unavailable") })
    }

    @Test
    fun genericMimeTypeDetection() {
        assertTrue(RaftShareFileName.isGenericMimeType(null))
        assertTrue(RaftShareFileName.isGenericMimeType(""))
        assertTrue(RaftShareFileName.isGenericMimeType("Application/Octet-Stream; x=y"))
        assertTrue(RaftShareFileName.isGenericMimeType("binary/octet-stream"))
        assertTrue(RaftShareFileName.isGenericMimeType("pdf"))
        assertFalse(RaftShareFileName.isGenericMimeType("application/pdf"))
        assertEquals(null, RaftShareFileName.normalizedMimeType("a/b/c"))
        assertEquals("image/png", RaftShareFileName.normalizedMimeType(" IMAGE/PNG ;q=1"))
    }
}
