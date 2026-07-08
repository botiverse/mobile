package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedBinaryDownloaderTest {
    @Test
    fun combineDownloadedChunksPreservesChunkOrderAndBytes() {
        val chunks =
            listOf(
                byteArrayOf(0x52, 0x41),
                byteArrayOf(0x46, 0x54),
                byteArrayOf(0x00, 0x7f)
            )

        assertContentEquals(
            byteArrayOf(0x52, 0x41, 0x46, 0x54, 0x00, 0x7f),
            combineDownloadedChunks(chunks, totalBytes = 6)
        )
    }

    @Test
    fun binaryDownloadUrlDiagnosticsRedactsPathAndQuery() {
        val diagnostics =
            binaryDownloadUrlDiagnostics(
                "https://api.slock.ai/api/attachments/download/sensitive-file-id.png?access_token=secret&signature=private"
            )

        assertTrue("scheme=https" in diagnostics)
        assertTrue("host=api.slock.ai" in diagnostics)
        assertTrue("pathHash=" in diagnostics)
        assertTrue("urlHash=" in diagnostics)
        assertFalse("sensitive-file-id" in diagnostics)
        assertFalse("access_token" in diagnostics)
        assertFalse("secret" in diagnostics)
        assertFalse("signature" in diagnostics)
        assertFalse("private" in diagnostics)
    }
}
