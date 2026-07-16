package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentDownloadAtomicFileSinkTest {
    @Test
    fun shortWritesLoopUntilEveryByteIsPersisted() {
        val ops = RecordingFileOps(maxWriteSize = 2)
        val sink = AttachmentDownloadAtomicFileSink("/cache/final.bin", "attempt-1", ops)

        assertTrue(sink.begin())
        assertTrue(sink.write(byteArrayOf(1, 2, 3, 4, 5)))
        assertTrue(sink.prepare())
        assertTrue(sink.publish())

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), ops.publishedBytes)
        assertEquals(3, ops.writeCalls)
        assertEquals(1, ops.fsyncCalls)
        assertEquals(1, ops.closeCalls)
        assertEquals(1, ops.renameCalls)
    }

    @Test
    fun zeroWriteFailsAndAbortClosesAndUnlinksExactlyOnce() {
        val ops = RecordingFileOps(zeroWrite = true)
        val sink = AttachmentDownloadAtomicFileSink("/cache/final.bin", "attempt-2", ops)

        assertTrue(sink.begin())
        assertFalse(sink.write(byteArrayOf(1)))
        sink.abort()
        sink.abort()

        assertEquals(1, ops.closeCalls)
        assertEquals(2, ops.unlinkCalls, "one pre-open cleanup and one staging abort")
        assertEquals(0, ops.renameCalls)
    }

    @Test
    fun fsyncCloseAndRenameFailuresNeverPublishSuccess() {
        listOf("fsync", "close", "rename").forEach { failure ->
            val ops = RecordingFileOps(failAt = failure)
            val sink = AttachmentDownloadAtomicFileSink("/cache/final.bin", "attempt-$failure", ops)
            assertTrue(sink.begin())
            assertTrue(sink.write(byteArrayOf(1, 2)))
            if (failure == "rename") {
                assertTrue(sink.prepare(), failure)
                assertFalse(sink.publish(), failure)
            } else {
                assertFalse(sink.prepare(), failure)
            }
            sink.abort()
            assertFalse(ops.published)
            assertEquals(1, ops.closeCalls, "$failure must own close exactly once")
            assertEquals(if (failure == "rename") 1 else 0, ops.renameCalls)
        }
    }

    @Test
    fun renameSuccessIsFinalEvenIfTargetPreexistedAndAbortRunsLater() {
        val ops = RecordingFileOps(targetPreexists = true)
        val sink = AttachmentDownloadAtomicFileSink("/cache/final.bin", "attempt-3", ops)

        assertTrue(sink.begin())
        assertTrue(sink.write(byteArrayOf(4, 5, 6)))
        assertTrue(sink.prepare())
        assertTrue(sink.publish())
        sink.abort()

        assertTrue(ops.published)
        assertContentEquals(byteArrayOf(4, 5, 6), ops.publishedBytes)
        assertEquals(1, ops.renameCalls)
        assertEquals(1, ops.closeCalls)
        assertEquals(1, ops.unlinkCalls, "abort-after-commit must not unlink the published final")
    }
}

private class RecordingFileOps(
    private val maxWriteSize: Int = Int.MAX_VALUE,
    private val zeroWrite: Boolean = false,
    private val failAt: String? = null,
    targetPreexists: Boolean = false
) : AttachmentDownloadFileOps {
    private val staging = mutableListOf<Byte>()
    var publishedBytes = if (targetPreexists) byteArrayOf(9, 9) else ByteArray(0)
    var published = false
    var writeCalls = 0
    var fsyncCalls = 0
    var closeCalls = 0
    var renameCalls = 0
    var unlinkCalls = 0

    override fun open(path: String): Any = "handle"

    override fun write(handle: Any, bytes: ByteArray, offset: Int, length: Int): Int {
        writeCalls += 1
        if (zeroWrite) return 0
        val count = minOf(length, maxWriteSize)
        repeat(count) { index -> staging += bytes[offset + index] }
        return count
    }

    override fun fsync(handle: Any): Boolean {
        fsyncCalls += 1
        return failAt != "fsync"
    }

    override fun close(handle: Any): Boolean {
        closeCalls += 1
        return failAt != "close"
    }

    override fun rename(sourcePath: String, targetPath: String): Boolean {
        renameCalls += 1
        if (failAt == "rename") return false
        publishedBytes = staging.toByteArray()
        published = true
        return true
    }

    override fun unlink(path: String) {
        unlinkCalls += 1
        staging.clear()
    }
}
