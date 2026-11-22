package build.raft.app.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidAvatarDurableKernelPolicyTest {
    @Test
    fun durableTerminalMarkerRestoresClosedAdmissionAfterProcessRestart() {
        val rig = AvatarKernelTestRig()
        val key = avatarKey()
        val entry =
            rig.kernel().commitCandidate(
                key,
                1L,
                avatarCommit(1L),
                "restart-terminal".toByteArray()
            )
        repeat(8) {
            rig.files.failNext(FakeAvatarFileOperation.Delete, entry.entryPath)
        }

        val first = rig.kernel().retireOwner(key.partitionKey, key.ownerKey)

        val scope = RaftAndroidAvatarTerminalScope.Owner(key.partitionKey, key.ownerKey)
        assertEquals(RaftAndroidAvatarTerminalStatus.CleanupPending, first.status)
        assertTrue(rig.files.exists(scope.markerPath))

        val restarted = rig.kernel()

        assertEquals(
            RaftAndroidAvatarOperationStatus.CleanupPending,
            restarted.startupRecovery.status
        )
        assertEquals(
            RaftAndroidAvatarRequestStatus.AdmissionClosed,
            restarted.beginRequest(key, 2L).status
        )
        assertTrue(rig.files.exists(scope.markerPath))

        rig.files.clearFailures()
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, restarted.retryRecovery().status)
        assertFalse(rig.files.exists(scope.markerPath))
        assertEquals(
            RaftAndroidAvatarRequestStatus.Accepted,
            restarted.beginRequest(key, 2L).status
        )
        assertFalse(rig.files.paths().any { key.ownsManagedPath(it) })
    }

    @Test
    fun clearAllNeverScansOrDeletesOutsideManagedAvatarPaths() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val unrelated =
            "other-cache/entry-${avatarOpaque("not-avatar")}"
        rig.files.put(unrelated, ByteArray(512) { 7 })
        kernel.commitCandidate(key, 1L, avatarCommit(1L), "avatar".toByteArray())

        val cleared = kernel.clearAll()

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, cleared.status)
        assertTrue(rig.files.exists(unrelated))
        assertFalse(
            rig.files.events.any { event ->
                event.operation == FakeAvatarFileOperation.Delete && event.path == unrelated
            }
        )
    }
}
