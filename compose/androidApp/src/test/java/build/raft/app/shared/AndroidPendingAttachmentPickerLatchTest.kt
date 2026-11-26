package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [AndroidPendingAttachmentPickerLatch] covering normal ActivityResult
 * completion, OEM orphaned-picker recovery on Activity resume, stale-token protection, and
 * camera null-state recovery (`f4a05254`).
 */
class AndroidPendingAttachmentPickerLatchTest {
    @Test
    fun normalResultBeforeResumeConsumesLatchWithoutSchedulingRecovery() {
        val latch = AndroidPendingAttachmentPickerLatch<String>()
        val token = latch.begin("picker-1")
        assertNotNull(token)
        assertTrue(latch.isBusy)
        assertNull("second launch while busy must be rejected", latch.begin("picker-2"))

        latch.onHostPausedOrStopped()
        // ActivityResultRegistry delivers at ON_START before ON_RESUME.
        assertEquals("picker-1", latch.consume())
        assertFalse(latch.isBusy)
        assertNull(latch.resumeRecoveryTokenOrNull())
        assertNull(latch.recoverIfOrphaned(token!!))
    }

    @Test
    fun orphanedLaunchAfterPauseAndResumeRecoversAndUnblocksNextLaunch() {
        val latch = AndroidPendingAttachmentPickerLatch<String>()
        val token = latch.begin("picker-orphaned")
        assertNotNull(token)

        // Resuming before the activity ever left foreground must not trigger recovery.
        assertNull(latch.resumeRecoveryTokenOrNull())

        latch.onHostPausedOrStopped()
        val recoveryToken = latch.resumeRecoveryTokenOrNull()
        assertEquals(token, recoveryToken)

        // Posted post-resume runnable finds the exact same launch still pending -> recovers it.
        assertEquals("picker-orphaned", latch.recoverIfOrphaned(recoveryToken!!))
        assertFalse(latch.isBusy)
        assertNull("recovery is one-shot", latch.recoverIfOrphaned(recoveryToken))

        // Next attachment picker tap succeeds immediately instead of returning 409 busy.
        val nextToken = latch.begin("picker-next")
        assertNotNull(nextToken)
        assertTrue(latch.isBusy)
    }

    @Test
    fun staleRecoveryTokenNeverClobbersANewerLaunch() {
        val latch = AndroidPendingAttachmentPickerLatch<String>()
        val firstToken = latch.begin("picker-1")!!
        latch.onHostPausedOrStopped()
        val postedToken = latch.resumeRecoveryTokenOrNull()!!
        assertEquals(firstToken, postedToken)

        // Real result arrives before posted runnable executes, then user opens a second picker.
        assertEquals("picker-1", latch.consume())
        val secondToken = latch.begin("picker-2")!!
        latch.onHostPausedOrStopped()

        // Stale runnable for firstToken must NOT clear secondToken.
        assertNull(latch.recoverIfOrphaned(postedToken))
        assertTrue(latch.isBusy)
        assertEquals("picker-2", latch.activePending)

        // Valid recovery for secondToken still works if secondToken itself is orphaned.
        assertEquals("picker-2", latch.recoverIfOrphaned(secondToken))
        assertFalse(latch.isBusy)
    }

    @Test
    fun cameraNullOrRecreatedResultRecoveryClearsPendingLatch() {
        val latch = AndroidPendingAttachmentPickerLatch<String>()
        assertNotNull(latch.begin("camera-upload"))
        latch.onHostPausedOrStopped()

        // Mirrors handleThreadCameraCaptureResult clearing latch when cameraCapture is null/recreated.
        assertEquals("camera-upload", latch.consume())
        assertFalse(latch.isBusy)
        assertNotNull(latch.begin("next-upload"))
    }
}
