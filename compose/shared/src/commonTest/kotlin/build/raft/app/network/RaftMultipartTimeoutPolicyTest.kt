package build.raft.app.network

import com.tencent.kmm.network.export.NetworkByteStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #783 common contract teeth (pure, native-compatible: no coroutines-test,
 * no sleeps): only a multipart set containing a streaming fileRef part gets
 * the 3-minute upload budget; everything else keeps the ordinary bound.
 */
class RaftMultipartTimeoutPolicyTest {

    private fun streamingFile() =
        MultipartFile(
            name = "files",
            fileName = "large.bin",
            mimeType = "application/octet-stream",
            fileRef = "file:///cache/large.bin",
            sizeBytes = 199_229_440L,
            streamOpener =
                MultipartFileStreamOpener {
                    NetworkByteStream(
                        contentLength = 199_229_440L,
                        readAllBlock = { error("whole-file read forbidden") },
                        readChunksBlock = { }
                    )
                }
        )

    private fun bytesFile() =
        MultipartFile(
            name = "files",
            fileName = "small.txt",
            mimeType = "text/plain",
            bytes = "hello".encodeToByteArray()
        )

    @Test
    fun uploadBudgetIsThreeMinutes() {
        assertEquals(180_000L, RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS)
    }

    @Test
    fun streamingFileRefPartIsDetected() {
        assertTrue(listOf(streamingFile()).hasStreamingFileRefParts())
        assertTrue(listOf(bytesFile(), streamingFile()).hasStreamingFileRefParts())
    }

    @Test
    fun bytesOnlyMultipartIsNotStreaming() {
        assertFalse(listOf(bytesFile()).hasStreamingFileRefParts())
        assertFalse(emptyList<MultipartFile>().hasStreamingFileRefParts())
    }

    @Test
    fun openerWithoutFileRefStaysOnBytesPath() {
        val opener = MultipartFileStreamOpener {
            NetworkByteStream(
                contentLength = 0L,
                readAllBlock = { ByteArray(0) },
                readChunksBlock = { }
            )
        }
        val incomplete =
            MultipartFile(
                name = "files",
                fileName = "odd.bin",
                mimeType = "application/octet-stream",
                streamOpener = opener
            )
        assertFalse(
            listOf(incomplete).hasStreamingFileRefParts(),
            "a part without a fileRef maps to NetworkBody.Bytes and must keep the ordinary bound"
        )
    }
}
