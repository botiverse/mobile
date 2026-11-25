package build.raft.webcontainer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebContainerDownloadsTest {
    @Test
    fun fileNameSanitizationAndResolutionFollowStrictPrecedence() {
        assertEquals("a_b_c.txt", sanitizeWebDownloadFileName("a/b\\c.txt"))
        assertNull(sanitizeWebDownloadFileName(".."))
        assertNull(sanitizeWebDownloadFileName("   "))
        assertNull(sanitizeWebDownloadFileName(".   ."))
        assertEquals("clean.pdf", sanitizeWebDownloadFileName("clean\u0007.pdf"))

        assertEquals(
            "季度报告.pdf",
            extractContentDispositionFileName("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf"),
        )
        assertEquals(
            "hello \"world\".csv",
            extractContentDispositionFileName("""attachment; filename="hello \"world\".csv""""),
        )
        assertEquals(
            "notes.md",
            extractUrlLastSegmentFileName("https://app.raft.build/files/notes.md?sig=123#top"),
        )
        assertEquals(
            "report.pdf",
            extractUrlLastSegmentFileName("https://app.raft.build/report.pdf"),
        )
        assertNull(extractUrlLastSegmentFileName("blob:https://app.raft.build/550e8400"))

        // Precedence: pageFileName > Content-Disposition > URL > default (+ MIME extension)
        assertEquals(
            "custom.txt",
            resolveWebDownloadFileName("custom.txt", "attachment; filename=other.pdf", "https://app.raft.build/a.json", "application/json"),
        )
        assertEquals(
            "other.pdf",
            resolveWebDownloadFileName(null, "attachment; filename=other.pdf", "https://app.raft.build/a.json", "application/json"),
        )
        assertEquals(
            "report.pdf",
            resolveWebDownloadFileName("report", null, "blob:https://app.raft.build/1", "application/pdf"),
        )
        assertEquals(
            "download.zip",
            resolveWebDownloadFileName(null, null, "blob:https://app.raft.build/1", "application/zip"),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun blobDownloadAssemblerReassemblesValidChunksAndRejectsTampering() {
        val payload = "Hello Raft WebContainer downloads!".encodeToByteArray()
        val part0 = Base64.encode(payload.copyOfRange(0, 12))
        val part1 = Base64.encode(payload.copyOfRange(12, payload.size))

        val assembler = WebContainerBlobDownloadAssembler(token = "t1", url = "blob:https://app.raft.build/abc")
        // Unrelated token ignored
        assertNull(assembler.accept("""{"t":"other","k":"start","size":1}"""))
        assertNull(assembler.accept("""{"t":"t1","k":"start","name":"export","type":"text/plain","size":${payload.size}}"""))
        assertNull(assembler.accept("""{"t":"t1","k":"chunk","i":0,"d":"$part0"}"""))
        assertNull(assembler.accept("""{"t":"t1","k":"chunk","i":1,"d":"$part1"}"""))
        val done = assembler.accept("""{"t":"t1","k":"end","n":2}""")
        val bytesResult = assertIs<WebDownloadResult.Bytes>(done)
        assertContentEquals(payload, bytesResult.data)
        assertEquals("export.txt", bytesResult.fileName)
        assertEquals("text/plain", bytesResult.mimeType)
        // Single-shot completion
        assertNull(assembler.accept("""{"t":"t1","k":"end","n":2}"""))

        // Out-of-order chunk fails cleanly
        val outOfOrder = WebContainerBlobDownloadAssembler(token = "t2", url = "blob:https://app.raft.build/2")
        assertNull(outOfOrder.accept("""{"t":"t2","k":"start","size":5}"""))
        assertEquals(
            WebDownloadResult.Failed("chunk_order"),
            outOfOrder.accept("""{"t":"t2","k":"chunk","i":1,"d":"$part0"}"""),
        )

        // Size limit violation fails cleanly before decoding oversized base64 chunk
        val oversized = WebContainerBlobDownloadAssembler(token = "t3", url = "blob:https://app.raft.build/3", maxBytes = 8)
        assertEquals(
            WebDownloadResult.Failed("too_large"),
            oversized.accept("""{"t":"t3","k":"start","size":16}"""),
        )
        val oversizedChunk = WebContainerBlobDownloadAssembler(token = "t3b", url = "blob:https://app.raft.build/3b", maxBytes = 8)
        assertNull(oversizedChunk.accept("""{"t":"t3b","k":"start","size":4}"""))
        assertEquals(
            WebDownloadResult.Failed("too_large"),
            oversizedChunk.accept("""{"t":"t3b","k":"chunk","i":0,"d":"${"A".repeat(32)}"}"""),
        )

        // Size mismatch on end fails cleanly
        val mismatch = WebContainerBlobDownloadAssembler(token = "t4", url = "blob:https://app.raft.build/4")
        assertNull(mismatch.accept("""{"t":"t4","k":"start","size":100}"""))
        assertEquals(
            WebDownloadResult.Failed("size_mismatch"),
            mismatch.accept("""{"t":"t4","k":"end","n":0}"""),
        )

        // Explicit page error propagates cleanly
        val errored = WebContainerBlobDownloadAssembler(token = "t5", url = "blob:https://app.raft.build/5")
        assertEquals(
            WebDownloadResult.Failed("blob_read_failed"),
            errored.accept("""{"t":"t5","k":"error","m":"blob_read_failed"}"""),
        )

        val readScript = buildWebContainerBlobReadScript("blob:https://app.raft.build/x", "tok1")
        assertTrue(readScript.contains(WEB_CONTAINER_DOWNLOAD_PORT_NAME))
        assertTrue(readScript.contains("tok1"))
        assertTrue(WEB_CONTAINER_DOWNLOAD_NAME_HOOK_SCRIPT.contains("window.top!==window.self"))
    }
}
