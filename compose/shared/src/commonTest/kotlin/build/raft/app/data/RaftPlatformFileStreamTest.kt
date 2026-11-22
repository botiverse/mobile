package build.raft.app.data

import build.raft.app.network.RaftByteStreamSink
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
            object : RaftByteStreamSink {
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

    private object DiscardingSink : RaftByteStreamSink {
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
