package build.raft.app.core.data.diagnostics

import build.raft.app.data.RaftPrivacyMode
import build.raft.app.shared.handsUpdateRequestAllowed
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RAFT_PRIVACY_MODE` and the Hands SDK (owner ruling): its launch uploads (device ping, crashes,
 * ANRs, native crashes) and automatic update checks go only when every signed-in server answered
 * privacy mode off; a person's own Check for update always goes.
 */
class RaftHandsLaunchUploadsTest {
    private fun steps(ran: MutableList<String>, failing: String? = null) =
        listOf("device_ping", "crashes", "anrs", "native_crashes").map { name ->
            HandsLaunchUploadStep(name) {
                ran += name
                if (name == failing) error("upload failed")
            }
        }

    @Test
    fun allServersOffRunsEveryUploadInTheSdksOrder() = runTest {
        val ran = mutableListOf<String>()
        val logs = mutableListOf<String>()

        val result = runHandsLaunchUploads({ RaftPrivacyMode.Off }, steps(ran)) { _, line -> logs += line }

        assertTrue(result)
        assertEquals(listOf("device_ping", "crashes", "anrs", "native_crashes"), ran)
    }

    @Test
    fun anyServerOnOrUnknownSendsNothingAndKeepsItForTheNextLaunch() = runTest {
        for (mode in listOf(RaftPrivacyMode.On, RaftPrivacyMode.Unknown)) {
            val ran = mutableListOf<String>()
            val logs = mutableListOf<String>()

            val result = runHandsLaunchUploads({ mode }, steps(ran)) { _, line -> logs += line }

            assertFalse("mode $mode", result)
            assertEquals("nothing uploaded under $mode", emptyList<String>(), ran)
            assertTrue(logs.single(), logs.single().contains("mode=${mode.name.lowercase()}") && logs.single().contains("kept_for=next_launch"))
        }
    }

    @Test
    fun oneFailedUploadDoesNotStopTheOthers() = runTest {
        val ran = mutableListOf<String>()
        val logs = mutableListOf<String>()

        runHandsLaunchUploads({ RaftPrivacyMode.Off }, steps(ran, failing = "crashes")) { _, line -> logs += line }

        assertEquals(listOf("device_ping", "crashes", "anrs", "native_crashes"), ran)
        assertTrue(logs.any { it.startsWith("hands_launch_upload_failed step=crashes") })
    }

    /** The SDK's internal ANR pass is called by name; an SDK bump that renames or reshapes it fails here. */
    @Test
    fun theSdksAnrUploadIsReachable() {
        assertNotNull(HandsAnrUploadBridge.resolve())
    }

    @Test
    fun automaticUpdateChecksFollowPrivacyModeButAPersonsOwnCheckAlwaysGoes() {
        // Every signed-in server answered off.
        assertTrue(handsUpdateRequestAllowed("handsUpdateStatus", automaticAllowed = true))
        // Any server on or unknown: the automatic status check sends nothing.
        assertFalse(handsUpdateRequestAllowed("handsUpdateStatus", automaticAllowed = false))
        // Settings' Check for update, and Install, are the person's own.
        assertTrue(handsUpdateRequestAllowed("handsUpdateCheck", automaticAllowed = false))
        assertTrue(handsUpdateRequestAllowed("handsUpdateInstall", automaticAllowed = false))
    }
}
