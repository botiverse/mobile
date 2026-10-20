package build.raft.app.network

import build.raft.app.data.RaftPlatformFileChunkSource
import build.raft.app.data.boundedPlatformFileNetworkStream
import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkByteStreamSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NetworkKmmMultipartBodyTest {
    /*
     * These tests own Slock's MultipartFile -> FileRef mapping and platform opener semantics.
     * The pinned NetworkKMM raft.29 composite itself is covered at upstream exact 730f164 by
     * NetworkModelsTest.multipartCancellationReachesOpenedFileRefDerivedStream,
     * multipartAttemptFailureKeepsLogicalFileRepeatableForNextAttempt, and
     * multipartAttemptCancelOwnsDerivedStreamOpenedAfterCancellation.
     */
    @Test
    fun platformFileRefMapsToRepeatableStreamingBodyWithKnownLength() = runTest {
        var opens = 0
        val file =
            MultipartFile(
                name = "files",
                fileName = "large.bin",
                mimeType = "application/octet-stream",
                fileRef = "file:///cache/large.bin",
                sizeBytes = 199_229_440L,
                streamOpener =
                    MultipartFileStreamOpener {
                        opens += 1
                        NetworkByteStream(
                            contentLength = 199_229_440L,
                            readAllBlock = { error("whole-file read forbidden") },
                            readChunksBlock = { }
                        )
                    }
            )

        val body = assertIs<NetworkBody.FileRef>(file.toNetworkMultipartBody())

        assertEquals("file:///cache/large.bin", body.path)
        assertEquals("application/octet-stream", body.contentType)
        assertEquals(199_229_440L, body.contentLength)
        assertEquals(true, body.repeatable)
        body.openStream()
        body.openStream()
        assertEquals(2, opens, "each HTTP attempt must open a fresh stream")
    }

    @Test
    fun inMemoryFileKeepsBytesBody() {
        val body =
            assertIs<NetworkBody.Bytes>(
                MultipartFile(
                    name = "files",
                    fileName = "small.txt",
                    mimeType = "text/plain",
                    bytes = byteArrayOf(1, 2, 3),
                    sizeBytes = 3
                ).toNetworkMultipartBody()
            )

        assertEquals(listOf<Byte>(1, 2, 3), body.bytes.toList())
        assertEquals("text/plain", body.contentType)
    }

    @Test
    fun cancelledAttemptReopensFromOffsetZeroWithoutTouchingFreshAttempt() = runTest {
        val sources =
            mutableListOf(
                FakeSource(mutableListOf(byteArrayOf(1, 2), byteArrayOf(3, 4), ByteArray(0))),
                FakeSource(mutableListOf(byteArrayOf(1, 2), byteArrayOf(3, 4), ByteArray(0)))
            )
        val opened = mutableListOf<FakeSource>()
        val body =
            assertIs<NetworkBody.FileRef>(
                MultipartFile(
                    name = "files",
                    fileName = "retry.bin",
                    mimeType = "application/octet-stream",
                    fileRef = "file:///retry.bin",
                    sizeBytes = 4,
                    streamOpener =
                        MultipartFileStreamOpener {
                            val source = sources.removeAt(0)
                            opened += source
                            boundedPlatformFileNetworkStream(4, source)
                        }
                ).toNetworkMultipartBody()
            )

        val firstAttempt = body.openStream() ?: error("missing first stream")
        var firstWrites = 0
        val firstFailure = try {
            firstAttempt.readChunks(
                object : NetworkByteStreamSink {
                    override suspend fun write(bytes: ByteArray) {
                        firstWrites += 1
                        firstAttempt.cancel()
                    }
                }
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        assertIs<IllegalStateException>(firstFailure)
        assertEquals(1, firstWrites)
        assertEquals(1, opened[0].closeCount)

        val secondAttempt = body.openStream() ?: error("missing retry stream")
        val retryBytes = mutableListOf<Byte>()
        secondAttempt.readChunks(
            object : NetworkByteStreamSink {
                override suspend fun write(bytes: ByteArray) {
                    retryBytes += bytes.toList()
                }
            }
        )
        firstAttempt.cancel()

        assertEquals(listOf<Byte>(1, 2, 3, 4), retryBytes)
        assertEquals(1, opened[0].closeCount)
        assertEquals(1, opened[1].closeCount)
        assertEquals(2, opened.size)
    }

    @Test
    fun fileRefAttemptSurfacesShortAndLongReadErrors() = runTest {
        val shortBody = fileRefBody(FakeSource(mutableListOf(byteArrayOf(1), ByteArray(0))), 2)
        val shortFailure = captureFailure {
            (shortBody.openStream() ?: error("missing short stream")).readChunks(DiscardingSink)
        }
        assertIs<IllegalStateException>(shortFailure)

        val longBody = fileRefBody(FakeSource(mutableListOf(byteArrayOf(1, 2), byteArrayOf(3))), 2)
        val longFailure = captureFailure {
            (longBody.openStream() ?: error("missing long stream")).readChunks(DiscardingSink)
        }
        assertIs<IllegalStateException>(longFailure)
    }

    private fun fileRefBody(source: FakeSource, sizeBytes: Long): NetworkBody.FileRef =
        assertIs<NetworkBody.FileRef>(
            MultipartFile(
                name = "files",
                fileName = "length.bin",
                mimeType = "application/octet-stream",
                fileRef = "file:///length.bin",
                sizeBytes = sizeBytes,
                streamOpener = MultipartFileStreamOpener { boundedPlatformFileNetworkStream(sizeBytes, source) }
            ).toNetworkMultipartBody()
        )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        error("Expected failure")
    }

    private object DiscardingSink : NetworkByteStreamSink {
        override suspend fun write(bytes: ByteArray) = Unit
    }

    private class FakeSource(
        private val chunks: MutableList<ByteArray>
    ) : RaftPlatformFileChunkSource {
        var closeCount: Int = 0
            private set

        override suspend fun read(maxBytes: Int): ByteArray {
            check(closeCount == 0) { "attempt source already closed" }
            val chunk = if (chunks.isEmpty()) ByteArray(0) else chunks.removeAt(0)
            assertTrue(chunk.size <= maxBytes)
            return chunk
        }

        override fun close() {
            closeCount += 1
        }
    }
}
