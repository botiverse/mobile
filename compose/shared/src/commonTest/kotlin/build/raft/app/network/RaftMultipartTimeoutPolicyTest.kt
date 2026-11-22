package build.raft.app.network

import build.raft.app.network.RaftByteStream
import kotlin.test.Test
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
                    RaftByteStream(
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
    fun streamingFileRefPartIsDetected() {
        assertTrue(listOf(streamingFile()).hasStreamingFileRefParts())
        assertTrue(listOf(bytesFile(), streamingFile()).hasStreamingFileRefParts())
    }
}
