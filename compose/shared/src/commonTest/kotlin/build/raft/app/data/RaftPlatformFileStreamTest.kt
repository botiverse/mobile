package build.raft.app.data

import com.tencent.kmm.network.export.NetworkByteStreamSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RaftPlatformFileStreamTest {
    @Test
    fun exactFileReadsOnlyBoundedChunksAndClosesOnce() = runBlocking {
        val source = FakeChunkSource(mutableListOf(ByteArray(PlatformFileStreamChunkBytes), byteArrayOf(1, 2), ByteArray(0)))
        val received = mutableListOf<Int>()
        val stream = boundedPlatformFileNetworkStream(PlatformFileStreamChunkBytes.toLong() + 2L, source)

        stream.readChunks(
            object : NetworkByteStreamSink {
                override suspend fun write(bytes: ByteArray) {
                    received += bytes.size
                }
            }
        )
        stream.cancel()

        assertEquals(listOf(PlatformFileStreamChunkBytes, 2), received)
        assertTrue(source.requestedMaxBytes.all { it in 1..PlatformFileStreamChunkBytes })
        assertEquals(1, source.closeCount)
    }

    @Test
    fun shortAndGrowingFilesFailClosedAndCloseOnce() = runBlocking {
        val short = FakeChunkSource(mutableListOf(byteArrayOf(1), ByteArray(0)))
        assertIs<IllegalStateException>(failureOf {
            boundedPlatformFileNetworkStream(2, short).readChunks(DiscardingSink)
        })
        assertEquals(1, short.closeCount)

        val growing = FakeChunkSource(mutableListOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertIs<IllegalStateException>(failureOf {
            boundedPlatformFileNetworkStream(2, growing).readChunks(DiscardingSink)
        })
        assertEquals(1, growing.closeCount)
    }

    @Test
    fun oversizedChunkAndWholeFileFallbackFailWithoutAggregation() = runBlocking {
        val oversizedChunk = FakeChunkSource(mutableListOf(byteArrayOf(1, 2)))
        assertIs<IllegalStateException>(failureOf {
            boundedPlatformFileNetworkStream(1, oversizedChunk).readChunks(DiscardingSink)
        })
        assertEquals(1, oversizedChunk.closeCount)

        val wholeRead = FakeChunkSource(mutableListOf(byteArrayOf(1)))
        val stream = boundedPlatformFileNetworkStream(1, wholeRead)
        assertIs<IllegalStateException>(failureOf { stream.readAll() })
        assertEquals(1, wholeRead.closeCount)
    }

    @Test
    fun oldAttemptCancelCannotCloseOrAdvanceFreshAttempt() = runBlocking {
        val oldSource = FakeChunkSource(mutableListOf(byteArrayOf(7), ByteArray(0)))
        val freshSource = FakeChunkSource(mutableListOf(byteArrayOf(8), ByteArray(0)))
        val oldAttempt = boundedPlatformFileNetworkStream(1, oldSource)
        val freshAttempt = boundedPlatformFileNetworkStream(1, freshSource)

        oldAttempt.cancel()
        freshAttempt.readChunks(DiscardingSink)
        oldAttempt.cancel()

        assertEquals(1, oldSource.closeCount)
        assertEquals(1, freshSource.closeCount)
        assertEquals(listOf(1, 1), freshSource.requestedMaxBytes)
    }

    @Test
    fun frozenLargeLengthIsMetadataOnlyUntilTransportPullsChunks() {
        val source = FakeChunkSource(mutableListOf())
        val stream = boundedPlatformFileNetworkStream(199_229_440L, source)

        assertEquals(199_229_440L, stream.contentLength)
        assertTrue(source.requestedMaxBytes.isEmpty())
        assertEquals(0, source.closeCount)
        stream.cancel()
        assertEquals(1, source.closeCount)
    }

    private object DiscardingSink : NetworkByteStreamSink {
        override suspend fun write(bytes: ByteArray) = Unit
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        error("Expected failure")
    }

    private class FakeChunkSource(
        private val chunks: MutableList<ByteArray>
    ) : RaftPlatformFileChunkSource {
        val requestedMaxBytes = mutableListOf<Int>()
        var closeCount: Int = 0
            private set

        override suspend fun read(maxBytes: Int): ByteArray {
            requestedMaxBytes += maxBytes
            return if (chunks.isEmpty()) ByteArray(0) else chunks.removeAt(0)
        }

        override fun close() {
            closeCount += 1
        }
    }
}
