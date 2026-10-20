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

    @Test
    fun activityWiresCameraIdentityThroughSaveRestoreAndTerminalCleanup() {
        val source = productionSource("androidApp/src/main/java/build/raft/app/shared/RaftCmpActivity.kt")

        assertTrue(
            "camera identity must be persisted before the external Activity can recreate the host",
            source.contains(
                "outState.putString(THREAD_CAMERA_CAPTURE_STATE_KEY, pending.file.canonicalPath)"
            ),
        )
        assertTrue(
            "the new Activity instance must restore an owned capture identity",
            source.contains("?.getString(THREAD_CAMERA_CAPTURE_STATE_KEY)") &&
                source.contains("restoredThreadCameraCaptureFile(applicationContext.cacheDir, it)"),
        )
        assertTrue(
            "a restored ActivityResult must enter the fail-closed branch",
            source.contains("if (cameraCapture.restoredAfterActivityRecreation)") &&
                source.contains("discardRestoredThreadCameraCaptureResult(cameraCapture.file)"),
        )
        assertTrue(
            "the discarded result must be audited instead of silently swallowed",
            source.contains("camera_result_discarded_after_activity_recreation") &&
                source.contains("cleaned=\$cleaned"),
        )
        assertFalse(
            "a recreated capture cannot retain or replay the old Kuikly callback",
            source.substringAfter("if (cameraCapture.restoredAfterActivityRecreation)")
                .substringBefore("if (!captured")
                .contains("callback?.invoke"),
        )
    }
}

private fun productionSource(relativePath: String): String {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(10) {
        val direct = File(current, "src/main/java/build/raft/app/shared/RaftCmpActivity.kt")
        if (relativePath.startsWith("androidApp/") && direct.isFile) return direct.readText()
        val candidate = File(current, relativePath)
        if (candidate.isFile) return candidate.readText()
        current = current.parentFile ?: return@repeat
    }
    error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
}
