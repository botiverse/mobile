package build.raft.app.shared

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadCameraCaptureRecoveryTest {
    @Test
    fun recreationRestoresOnlyOwnedCameraCaptureIdentity() {
        val cache = createTempDirectory("camera-recovery").toFile()
        val directory = File(cache, THREAD_CAMERA_CAPTURE_CACHE_DIR_NAME).apply { mkdirs() }
        val owned = File(directory, "camera-123.jpg").apply { writeBytes(byteArrayOf(1)) }
        val unrelated = File(cache, "camera-escape.jpg").apply { writeBytes(byteArrayOf(2)) }

        assertEquals(owned.canonicalFile, restoredThreadCameraCaptureFile(cache, owned.path))
        assertNull(restoredThreadCameraCaptureFile(cache, unrelated.path))
        assertNull(restoredThreadCameraCaptureFile(cache, File(directory, "other.jpg").path))
        assertNull(restoredThreadCameraCaptureFile(cache, File(directory, "camera-missing.jpg").path))

        assertTrue(discardRestoredThreadCameraCaptureResult(owned))
        assertFalse(owned.exists())
        assertTrue(discardRestoredThreadCameraCaptureResult(owned))

        cache.deleteRecursively()
    }
}
