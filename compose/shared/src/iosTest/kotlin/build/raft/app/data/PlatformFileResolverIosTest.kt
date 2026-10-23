@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import build.raft.app.network.RaftByteStreamSink
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close as posixClose
import platform.posix.creat as posixCreat
import platform.posix.write as posixWrite

class PlatformFileResolverIosTest {
    @Test
    fun tempFileStreamsInBoundedChunks() = runTest {
        withTempFile("a".repeat(PlatformFileStreamChunkBytes + 3)) { path ->
            val resolver = createDefaultPlatformFileResolver() ?: error("Missing iOS resolver")
            val stat = resolver.statFile(path)
            val chunkSizes = mutableListOf<Int>()

            boundedPlatformFileNetworkStream(
                expectedSizeBytes = stat.sizeBytes ?: error("Missing size"),
                source = resolver.openFileStream(path, stat.sizeBytes ?: error("Missing size"))
            )
                .readChunks(
                    object : RaftByteStreamSink {
                        override suspend fun write(bytes: ByteArray) {
                            chunkSizes += bytes.size
                        }
                    }
                )

            assertEquals(listOf(PlatformFileStreamChunkBytes, 3), chunkSizes)
            assertTrue(stat.success)
        }
    }

    @Test
    fun tempFileTruncateAndGrowthAfterStatFailBeforeStreaming() = runTest {
        withTempFile("abcd") { path ->
            val resolver = createDefaultPlatformFileResolver() ?: error("Missing iOS resolver")
            val expected = resolver.statFile(path).sizeBytes ?: error("Missing size")

            writeText(path, "ab")
            assertIs<IllegalStateException>(failureOf {
                resolver.openFileStream(path, expected)
            })

            writeText(path, "abcdef")
            assertIs<IllegalStateException>(failureOf {
                resolver.openFileStream(path, expected)
            })
        }
    }

    @Test
    fun missingTempFileFailsStatWithoutOpeningTransportStream() = runTest {
        val path = "${NSTemporaryDirectory().trimEnd('/')}/slock-missing-${NSUUID().UUIDString}"
        val resolver = createDefaultPlatformFileResolver() ?: error("Missing iOS resolver")

        val stat = resolver.statFile(path)

        assertEquals(400, stat.code)
        assertEquals("attachment_read_failed", stat.errorCode)
    }

    private inline fun withTempFile(text: String, block: (String) -> Unit) {
        val path = "${NSTemporaryDirectory().trimEnd('/')}/slock-stream-${NSUUID().UUIDString}"
        writeText(path, text)
        try {
            block(path)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    private fun writeText(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val descriptor = posixCreat(path, (S_IRUSR or S_IWUSR).convert())
        assertTrue(descriptor >= 0)
        try {
            var offset = 0
            while (offset < bytes.size) {
                val count =
                    bytes.usePinned { pinned ->
                        posixWrite(
                            descriptor,
                            pinned.addressOf(offset),
                            (bytes.size - offset).convert()
                        ).toInt()
                    }
                assertTrue(count > 0)
                offset += count
            }
        } finally {
            posixClose(descriptor)
        }
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        error("Expected failure")
    }
}
