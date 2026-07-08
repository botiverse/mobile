package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertContentEquals

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
}
