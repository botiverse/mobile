package build.raft.app.network

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAttachmentDownloadAtomicSinkTest {
    @Test
    fun commitAtomicallyReplacesTargetAndRemovesStagingFile() {
        val directory = Files.createTempDirectory("attachment-atomic-sink").toFile()
        try {
            val target = directory.resolve("attachment.bin")
            target.writeBytes(byteArrayOf(9, 9))
            val sink = createAttachmentDownloadAtomicSink(target.absolutePath, "attempt-1")

            assertTrue(sink.begin())
            assertTrue(sink.write(byteArrayOf(1, 2)))
            assertTrue(sink.write(byteArrayOf(3, 4)))
            assertTrue(sink.commit())

            assertContentEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
            assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".part-") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun abortKeepsExistingTargetAndDeletesOnlyThisAttemptsStagingFile() {
        val directory = Files.createTempDirectory("attachment-atomic-abort").toFile()
        try {
            val target = directory.resolve("attachment.bin")
            target.writeBytes(byteArrayOf(7, 8))
            val sink = createAttachmentDownloadAtomicSink(target.absolutePath, "attempt-2")

            assertTrue(sink.begin())
            assertTrue(sink.write(byteArrayOf(1, 2, 3)))
            sink.abort()

            assertContentEquals(byteArrayOf(7, 8), target.readBytes())
            assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".part-") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
