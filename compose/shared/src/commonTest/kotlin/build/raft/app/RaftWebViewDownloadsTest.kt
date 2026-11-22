package build.raft.app

import build.raft.app.compat.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** #proj-mobile:#202: a hosted page's saves stay in the app, named as the page names them. */
@OptIn(ExperimentalEncodingApi::class)
class RaftWebViewDownloadsTest {
    private val blob = "blob:https://app.raft.build/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

    @Test
    fun nameOrderIsPageNameThenContentDispositionThenUrl() {
        assertEquals(
            "周报 v3.zip",
            raftWebViewDownloadFileName("周报 v3.zip", "attachment; filename=\"x.zip\"", blob, "application/zip"),
        )
        assertEquals(
            "报告.md",
            raftWebViewDownloadFileName(null, "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.md", "https://app.raft.build/f", null),
        )
        assertEquals("chart.png", raftWebViewDownloadFileName("", null, "https://app.raft.build/api/a/files/chart.png?version=2", null))
    }

    private fun message(vararg pairs: Pair<String, Any>): String =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    @Test
    fun assemblesChunksInOrder() {
        val assembler = RaftWebViewBlobDownloadAssembler("d1", blob)
        val bytes = "hello, 世界".encodeToByteArray()
        assertNull(assembler.accept(message("t" to "d1", "k" to "start", "name" to "周报.md", "type" to "text/markdown", "size" to bytes.size)))
        assertNull(assembler.accept(message("t" to "d1", "k" to "chunk", "i" to 0, "d" to Base64.encode(bytes.copyOfRange(0, 5)))))
        assertNull(assembler.accept(message("t" to "d1", "k" to "chunk", "i" to 1, "d" to Base64.encode(bytes.copyOfRange(5, bytes.size)))))
        val done = assertIs<RaftWebViewDownload.Bytes>(assembler.accept(message("t" to "d1", "k" to "end", "n" to 2)))
        assertEquals("hello, 世界", done.bytes.decodeToString())
        assertEquals("周报.md", done.fileName)
        assertEquals("text/markdown", done.mimeType)
    }

    @Test
    fun sizeMismatchOrOutOfOrderChunksFail() {
        val shortOne = RaftWebViewBlobDownloadAssembler("d1", blob)
        shortOne.accept(message("t" to "d1", "k" to "start", "size" to 10))
        shortOne.accept(message("t" to "d1", "k" to "chunk", "i" to 0, "d" to Base64.encode(ByteArray(4))))
        assertIs<RaftWebViewDownload.Failed>(shortOne.accept(message("t" to "d1", "k" to "end", "n" to 1)))

        val outOfOrder = RaftWebViewBlobDownloadAssembler("d1", blob)
        outOfOrder.accept(message("t" to "d1", "k" to "start", "size" to 4))
        assertIs<RaftWebViewDownload.Failed>(outOfOrder.accept(message("t" to "d1", "k" to "chunk", "i" to 1, "d" to Base64.encode(ByteArray(4)))))
    }
}
