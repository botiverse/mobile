package build.raft.app.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidAvatarDurableKernelRecoveryTest {
    @Test
    fun corruptSoleIndexFailsClosedAndQuarantinesUntrustedEntry() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val commit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, commit, "trusted-once".toByteArray())
        val hit = kernel.readHit(key, 2L, commit)
        assertTrue(
            kernel.registerDecoded(
                hit.snapshot,
                hit.lease,
                RaftAndroidAvatarDecodedVariant(48, 48, "corrupt-index")
            )
        )
        hit.lease.close()
        assertTrue(rig.decoded.containsExact(entry))
        assertTrue(rig.files.exists(entry.entryPath))
        rig.files.corrupt(key.indexPath)

        val recovered = rig.kernel()

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, recovered.startupRecovery.status)
        assertNull(recovered.durableStateForTest(key))
        assertFalse(rig.decoded.containsExact(entry))
        assertFalse(rig.files.exists(entry.entryPath))
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.Empty),
            recovered.readSnapshot(recovered.acceptedFence(key, 2L), commit)
        )
    }

    @Test
    fun oversizedOrphanSetIsRecoveredInBoundedDurableBatchesWithoutCrashing() {
        val rig = AvatarKernelTestRig()
        val key = avatarKey(bytes = "bounded-orphans")
        repeat(RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS + 1) { index ->
            rig.files.put(
                "${key.relativeDirectory}/stage-${avatarOpaque("bounded-tx-$index")}-" +
                    avatarOpaque("bounded-file-$index"),
                byteArrayOf(index.toByte())
            )
        }

        val recovered = rig.kernel()

        assertEquals(
            RaftAndroidAvatarOperationStatus.CleanupPending,
            recovered.startupRecovery.status
        )
        assertEquals(
            RaftAndroidAvatarRequestStatus.AdmissionClosed,
            recovered.beginRequest(key, 1L).status
        )
        assertEquals(1, rig.files.paths().count { key.ownsManagedPath(it) })

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, recovered.retryRecovery().status)
        assertFalse(rig.files.paths().any { key.ownsManagedPath(it) })
        assertEquals(RaftAndroidAvatarRequestStatus.Accepted, recovered.beginRequest(key, 1L).status)
    }
}
