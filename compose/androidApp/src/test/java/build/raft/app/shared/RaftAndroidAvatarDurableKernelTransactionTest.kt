package build.raft.app.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidAvatarDurableKernelTransactionTest {
    @Test
    fun exactInvalidationFromOldReadCannotDeleteNewEntryOrDecodedIdentity() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val firstCommit = avatarCommit(1L)
        val secondCommit = avatarCommit(2L)
        val firstEntry = kernel.commitCandidate(key, 1L, firstCommit, "first".toByteArray())
        val firstHit = kernel.readHit(key, 2L, firstCommit)
        assertTrue(
            kernel.registerDecoded(
                firstHit.snapshot,
                firstHit.lease,
                RaftAndroidAvatarDecodedVariant(64, 64, "center-crop")
            )
        )
        firstHit.lease.close()
        val secondEntry =
            kernel.commitCandidate(
                key = key,
                requestGeneration = 2L,
                commitIdentity = secondCommit,
                bytes = "second".toByteArray(),
                expectedCurrent = firstCommit
            )
        val secondHit = kernel.readHit(key, 2L, secondCommit)
        assertTrue(
            kernel.registerDecoded(
                secondHit.snapshot,
                secondHit.lease,
                RaftAndroidAvatarDecodedVariant(96, 96, "fit-center")
            )
        )
        secondHit.lease.close()

        val stale = kernel.invalidateIfCurrent(firstHit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.StaleRequest, stale.status)
        assertFalse(rig.decoded.containsExact(firstEntry))
        assertTrue(rig.decoded.containsExact(secondEntry))
        val surviving = kernel.readHit(key, 5L, secondCommit)
        assertArrayEquals("second".toByteArray(), surviving.snapshot.bytes)
        surviving.lease.close()
    }

    @Test
    fun crashCutsBeforeSharedReceiptRollbackAndLaterCutsCommit() {
        val rollbackCuts =
            listOf(
                RaftAndroidAvatarDurableCut.Prepared,
                RaftAndroidAvatarDurableCut.CandidateFileDurable,
                RaftAndroidAvatarDurableCut.CandidateDurable
            )
        val commitCuts =
            listOf(
                RaftAndroidAvatarDurableCut.SharedCommitted,
                RaftAndroidAvatarDurableCut.CandidateRenamed,
                RaftAndroidAvatarDurableCut.CandidateRenamedRecorded,
                RaftAndroidAvatarDurableCut.IndexSwitched,
                RaftAndroidAvatarDurableCut.IndexSwitchedRecorded,
                RaftAndroidAvatarDurableCut.CommitCleanup,
                RaftAndroidAvatarDurableCut.Idle
            )

        rollbackCuts.forEach { cut ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "rollback-$cut")
            val previous = avatarCommit(1L, "previous-$cut")
            val candidate = avatarCommit(2L, "candidate-$cut")
            rig.kernel().commitCandidate(key, 1L, previous, "old-$cut".toByteArray())
            val crashing = rig.kernel(crashAt = cut)
            val fence = crashing.acceptedFence(key, 2L)
            try {
                crashing.stageCandidate(
                    fence = fence,
                    expectedCurrentCommitIdentity = previous,
                    candidateCommitIdentity = candidate,
                    bytes = "new-$cut".toByteArray(),
                    contentType = "image/png"
                )
                throw AssertionError("expected simulated crash at $cut")
            } catch (crash: SimulatedAvatarCrash) {
                assertEquals(cut, crash.cut)
            }

            val recovered = rig.kernel()
            assertEquals(
                "startup recovery at $cut",
                RaftAndroidAvatarOperationStatus.Applied,
                recovered.startupRecovery.status
            )
            val oldHit = recovered.readHit(key, 3L, previous)
            assertArrayEquals("old-$cut".toByteArray(), oldHit.snapshot.bytes)
            oldHit.lease.close()
            assertEquals(
                "candidate must stay invisible at $cut",
                RaftAndroidAvatarReadResult.Miss(
                    RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
                ),
                recovered.readSnapshot(recovered.acceptedFence(key, 4L), candidate)
            )
        }

        commitCuts.forEach { cut ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "commit-$cut")
            val previous = avatarCommit(1L, "old-$cut")
            val candidate = avatarCommit(2L, "new-$cut")
            rig.kernel().commitCandidate(key, 1L, previous, "old-bytes-$cut".toByteArray())
            val crashing = rig.kernel(crashAt = cut)
            val fence = crashing.acceptedFence(key, 2L)
            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                crashing.stageCandidate(
                    fence = fence,
                    expectedCurrentCommitIdentity = previous,
                    candidateCommitIdentity = candidate,
                    bytes = "new-bytes-$cut".toByteArray(),
                    contentType = "image/png"
                ).status
            )
            try {
                crashing.finalizeCandidate(fence, candidate)
                throw AssertionError("expected simulated crash at $cut")
            } catch (crash: SimulatedAvatarCrash) {
                assertEquals(cut, crash.cut)
            }

            val recovered = rig.kernel()
            assertEquals(
                "startup recovery at $cut",
                RaftAndroidAvatarOperationStatus.Applied,
                recovered.startupRecovery.status
            )
            val newHit = recovered.readHit(key, 3L, candidate)
            assertArrayEquals("new-bytes-$cut".toByteArray(), newHit.snapshot.bytes)
            newHit.lease.close()
            assertEquals(RaftAndroidAvatarDurablePhase.Idle, recovered.durableStateForTest(key)?.phase)
        }
    }
}
