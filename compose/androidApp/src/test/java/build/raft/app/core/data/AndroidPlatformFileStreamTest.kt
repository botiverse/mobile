package build.raft.app.core.data

import build.raft.app.data.PlatformFileStreamChunkBytes
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class AndroidPlatformFileStreamTest {
    @Test
    fun readsInBoundedChunksAndClosesExactlyOnce() = runTest {
        val input = CountingInputStream(ByteArray(PlatformFileStreamChunkBytes * 2 + 17) { 7 })
        val chunkSizes = mutableListOf<Int>()
        val source = input.toRaftPlatformFileChunkSource()

        while (true) {
            val bytes = source.read(PlatformFileStreamChunkBytes)
            if (bytes.isEmpty()) break
            chunkSizes += bytes.size
        }
        source.close()

        assertEquals(listOf(PlatformFileStreamChunkBytes, PlatformFileStreamChunkBytes, 17), chunkSizes)
        assertTrue(chunkSizes.all { it <= PlatformFileStreamChunkBytes })
        assertEquals(1, input.closeCount)
    }

    @Test
    fun eachAttemptOwnsAnIndependentInputAndCancelClosesOnce() {
        val first = CountingInputStream(byteArrayOf(1))
        val second = CountingInputStream(byteArrayOf(2))
        val firstSource = first.toRaftPlatformFileChunkSource()
        val secondSource = second.toRaftPlatformFileChunkSource()

        firstSource.close()
        secondSource.close()

        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun adapterNeverReturnsMoreThanRequestedAndExposesEof() = runTest {
        val input = CountingInputStream(ByteArray(10) { it.toByte() })
        val source = input.toRaftPlatformFileChunkSource()

        assertEquals(3, source.read(3).size)
        assertEquals(3, source.read(3).size)
        assertEquals(3, source.read(3).size)
        assertEquals(1, source.read(3).size)
        assertTrue(source.read(3).isEmpty())
        source.close()

        assertEquals(1, input.closeCount)
    }

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
            super.close()
        }
    }
}
