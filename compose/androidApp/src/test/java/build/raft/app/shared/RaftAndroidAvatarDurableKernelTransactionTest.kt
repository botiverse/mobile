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
    fun reservationAfterDurableRollbackAndRestartAdvancesBeyondRecoveredHighWater() {
        val rig = AvatarKernelTestRig()
        val key = avatarKey()
        val current = avatarCommit(1L, "reservation-current")
        val firstKernel = rig.kernel()
        firstKernel.commitCandidate(key, 1L, current, "current".toByteArray())
        val firstFence = firstKernel.acceptedFence(key, 2L)
        val firstReservation =
            firstKernel.reserveCandidateIdentity(firstFence, current)

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, firstReservation.status)
        assertEquals(2L, firstReservation.commitIdentity?.commitGeneration)
        val staged =
            firstKernel.stageCandidate(
                fence = firstFence,
                expectedCurrentCommitIdentity = current,
                candidateCommitIdentity = firstReservation.commitIdentity!!,
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, staged.status)
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            firstKernel.rollbackCandidate(key, firstReservation.commitIdentity).status
        )
        assertEquals(2L, firstKernel.durableStateForTest(key)?.commitGenerationHighWater)

        val restarted = rig.kernel()
        val secondReservation =
            restarted.reserveCandidateIdentity(restarted.acceptedFence(key, 1L), current)

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, secondReservation.status)
        assertEquals(3L, secondReservation.commitIdentity?.commitGeneration)
        assertNotEquals(firstReservation.commitIdentity?.nonce, secondReservation.commitIdentity?.nonce)
    }

    @Test
    fun currentReadAndExactRetirementUseTheCommittedNativeIdentity() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val committed = avatarCommit(4L, "current-read")
        val bytes = "render-fresh".toByteArray()
        kernel.commitCandidate(key, 1L, committed, bytes)

        val read = kernel.readCurrent(key)

        assertEquals(RaftAndroidAvatarCurrentReadStatus.Available, read.status)
        assertArrayEquals(bytes, read.snapshot?.bytes)
        assertEquals(committed, read.snapshot?.entryIdentity?.commitIdentity)
        assertEquals(
            RaftAndroidAvatarOperationStatus.IdentityMismatch,
            kernel.retireCurrentExact(key, avatarCommit(5L, "wrong-exact")).status
        )
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            kernel.retireCurrentExact(key, committed).status
        )
        assertEquals(RaftAndroidAvatarCurrentReadStatus.Empty, kernel.readCurrent(key).status)
        assertEquals(
            RaftAndroidAvatarOperationStatus.AlreadyApplied,
            kernel.retireCurrentExact(key, committed).status
        )
    }

    @Test
    fun committedEntryRequiresExactDurableCommitIdentity() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val committedIdentity = avatarCommit(7L)
        val bytes = "avatar-seven".toByteArray()

        val entry = kernel.commitCandidate(key, 1L, committedIdentity, bytes)

        assertEquals(committedIdentity, entry.commitIdentity)
        assertEquals(bytes.raftSha256Hex(), entry.sha256)
        assertEquals(bytes.size.toLong(), entry.byteLength)
        assertEquals("image/png", entry.contentType)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertEquals(entry, kernel.durableStateForTest(key)?.current?.identity)

        val hit = kernel.readHit(key, 2L, committedIdentity)
        assertArrayEquals(bytes, hit.snapshot.bytes)
        hit.lease.close()

        val mismatch =
            kernel.readSnapshot(
                kernel.acceptedFence(key, 3L),
                avatarCommit(8L)
            )
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
            ),
            mismatch
        )
        val noSharedIdentity = kernel.readSnapshot(kernel.acceptedFence(key, 4L), null)
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.NoExpectedCommitIdentity
            ),
            noSharedIdentity
        )
    }

    @Test
    fun oneKeyAllowsOnlyOneDurableTransaction() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val fence = kernel.acceptedFence(key, 1L)
        val firstIdentity = avatarCommit(1L)

        val first =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = firstIdentity,
                bytes = "first".toByteArray(),
                contentType = "image/jpeg"
            )
        val second =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(2L),
                bytes = "second".toByteArray(),
                contentType = "image/jpeg"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, first.status)
        assertEquals(RaftAndroidAvatarOperationStatus.Busy, second.status)
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            kernel.rollbackCandidate(key, firstIdentity).status
        )
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertNull(kernel.durableStateForTest(key)?.current)
        assertEquals(1L, kernel.durableStateForTest(key)?.commitGenerationHighWater)
    }

    @Test
    fun newerRequestGenerationHasASeparateFlightAndFencesOlderMutation() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val older = kernel.acceptedFence(key, 4L)
        val same = kernel.acceptedFence(key, 4L)
        val newer = kernel.acceptedFence(key, 5L)

        assertEquals(older.flightIdentity, same.flightIdentity)
        assertNotEquals(older.flightIdentity, newer.flightIdentity)
        assertEquals(
            RaftAndroidAvatarRequestStatus.Stale,
            kernel.beginRequest(key, 4L).status
        )
        assertEquals(
            RaftAndroidAvatarOperationStatus.StaleRequest,
            kernel.stageCandidate(
                fence = older,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(1L),
                bytes = "old".toByteArray(),
                contentType = "image/png"
            ).status
        )
    }

    @Test
    fun stageRejectsAnExplicitlyWrongCurrentIdentity() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val current = avatarCommit(1L)
        kernel.commitCandidate(key, 1L, current, "current".toByteArray())
        val fence = kernel.acceptedFence(key, 2L)

        val result =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = avatarCommit(99L),
                candidateCommitIdentity = avatarCommit(2L),
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.IdentityMismatch, result.status)
        assertEquals(current, kernel.durableStateForTest(key)?.current?.identity?.commitIdentity)
    }

    @Test
    fun stageRequiresExactNullVsPresentCurrentIdentityCas() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val current = avatarCommit(1L, "null-cas-current")
        kernel.commitCandidate(key, 1L, current, "current".toByteArray())

        val result =
            kernel.stageCandidate(
                fence = kernel.acceptedFence(key, 2L),
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(2L, "null-cas-candidate"),
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.IdentityMismatch, result.status)
        assertEquals(current, kernel.durableStateForTest(key)?.current?.identity?.commitIdentity)
    }

    @Test
    fun exactAlreadyCommittedCandidateIsAnIdempotentStageRetry() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val committed = avatarCommit(1L, "idempotent-stage")
        val entry = kernel.commitCandidate(key, 1L, committed, "committed".toByteArray())

        val retry =
            kernel.stageCandidate(
                fence = kernel.acceptedFence(key, 2L),
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = committed,
                bytes = "committed".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.AlreadyApplied, retry.status)
        assertEquals(entry, retry.entry)
        val hit = kernel.readHit(key, 3L, committed)
        assertArrayEquals("committed".toByteArray(), hit.snapshot.bytes)
        hit.lease.close()
    }

    @Test
    fun committedCoordinateWithDifferentPayloadFailsClosed() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val committed = avatarCommit(1L, "committed-payload-mismatch")
        val entry = kernel.commitCandidate(key, 1L, committed, "committed".toByteArray())

        val retry =
            kernel.stageCandidate(
                fence = kernel.acceptedFence(key, 2L),
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = committed,
                bytes = "different".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.IdentityMismatch, retry.status)
        assertEquals(entry, kernel.durableStateForTest(key)?.current?.identity)
    }

    @Test
    fun activeCoordinateReplaysOnlyForTheExactSamePayload() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val candidate = avatarCommit(1L, "active-idempotent-stage")
        val fence = kernel.acceptedFence(key, 1L)
        val first =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = candidate,
                bytes = "active-payload".toByteArray(),
                contentType = "image/png"
            )

        val samePayload =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = candidate,
                bytes = "active-payload".toByteArray(),
                contentType = "image/png; charset=binary"
            )
        val differentPayload =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = candidate,
                bytes = "different-active-payload".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, first.status)
        assertEquals(RaftAndroidAvatarOperationStatus.AlreadyApplied, samePayload.status)
        assertEquals(first.entry, samePayload.entry)
        assertEquals(RaftAndroidAvatarOperationStatus.IdentityMismatch, differentPayload.status)
        assertEquals(
            RaftAndroidAvatarDurablePhase.CandidateDurable,
            kernel.durableStateForTest(key)?.phase
        )
    }

    @Test
    fun stageRejectsNonAdvancingDurableCommitGeneration() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val current = avatarCommit(5L, "generation-current")
        kernel.commitCandidate(key, 1L, current, "current".toByteArray())
        val fence = kernel.acceptedFence(key, 2L)

        val result =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = current,
                candidateCommitIdentity = avatarCommit(5L, "different-nonce-same-generation"),
                bytes = "not-newer".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.IdentityMismatch, result.status)
        assertEquals(current, kernel.durableStateForTest(key)?.current?.identity?.commitIdentity)
    }

    @Test
    fun candidateGenerationNeverReadsPreviousGeneration() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val previous = avatarCommit(1L)
        val candidate = avatarCommit(2L)
        val previousBytes = "previous".toByteArray()
        kernel.commitCandidate(key, 1L, previous, previousBytes)
        val fence = kernel.acceptedFence(key, 2L)
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previous,
                candidateCommitIdentity = candidate,
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            ).status
        )

        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
            ),
            kernel.readSnapshot(fence, candidate)
        )
        val previousHit = kernel.readSnapshot(fence, previous) as RaftAndroidAvatarReadResult.Hit
        assertArrayEquals(previousBytes, previousHit.snapshot.bytes)
        previousHit.lease.close()
    }

    @Test
    fun rollbackRestoresPreviousOnlyForItsExactSharedIdentity() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val previous = avatarCommit(1L)
        val candidate = avatarCommit(2L)
        val previousBytes = "rollback-target".toByteArray()
        kernel.commitCandidate(key, 1L, previous, previousBytes)
        val fence = kernel.acceptedFence(key, 2L)
        kernel.stageCandidate(
            fence = fence,
            expectedCurrentCommitIdentity = previous,
            candidateCommitIdentity = candidate,
            bytes = "discarded".toByteArray(),
            contentType = "image/png"
        )

        val rollback = kernel.rollbackCandidate(key, candidate)

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, rollback.status)
        assertEquals(previous, rollback.entry?.commitIdentity)
        val hit = kernel.readSnapshot(kernel.acceptedFence(key, 3L), previous)
            as RaftAndroidAvatarReadResult.Hit
        assertArrayEquals(previousBytes, hit.snapshot.bytes)
        hit.lease.close()
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
            ),
            kernel.readSnapshot(kernel.acceptedFence(key, 4L), candidate)
        )
    }

    @Test
    fun rollbackAfterIndexSwitchRetainsTheRepointedPreviousFile() {
        val rig = AvatarKernelTestRig()
        val key = avatarKey()
        val previous = avatarCommit(1L, "switched-previous")
        val candidate = avatarCommit(2L, "switched-candidate")
        val previousEntry =
            rig.kernel().commitCandidate(key, 1L, previous, "previous-bytes".toByteArray())
        val crashing = rig.kernel(crashAt = RaftAndroidAvatarDurableCut.IndexSwitched)
        val fence = crashing.acceptedFence(key, 2L)
        crashing.stageCandidate(
            fence = fence,
            expectedCurrentCommitIdentity = previous,
            candidateCommitIdentity = candidate,
            bytes = "candidate-bytes".toByteArray(),
            contentType = "image/png"
        )
        try {
            crashing.finalizeCandidate(fence, candidate)
            throw AssertionError("expected index-switch crash")
        } catch (crash: SimulatedAvatarCrash) {
            assertEquals(RaftAndroidAvatarDurableCut.IndexSwitched, crash.cut)
        }

        val rollback = crashing.rollbackCandidate(key, candidate)

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, rollback.status)
        assertEquals(previous, rollback.entry?.commitIdentity)
        assertTrue(rig.files.exists(previousEntry.entryPath))
        val hit = crashing.readHit(key, 3L, previous)
        assertArrayEquals("previous-bytes".toByteArray(), hit.snapshot.bytes)
        hit.lease.close()
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
            ),
            crashing.readSnapshot(crashing.acceptedFence(key, 4L), candidate)
        )
    }

    @Test
    fun sharedCommittedGenerationCannotBeReusedAfterRollbackAndRestart() {
        listOf(
            RaftAndroidAvatarDurableCut.SharedCommitted,
            RaftAndroidAvatarDurableCut.IndexSwitched
        ).forEach { cut ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "burned-generation-$cut")
            val previous = avatarCommit(1L, "burned-previous-$cut")
            val burnedCandidate = avatarCommit(2L, "burned-candidate-$cut")
            rig.kernel().commitCandidate(
                key,
                1L,
                previous,
                "previous-$cut".toByteArray()
            )
            val crashing = rig.kernel(crashAt = cut)
            val fence = crashing.acceptedFence(key, 2L)
            crashing.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previous,
                candidateCommitIdentity = burnedCandidate,
                bytes = "first-candidate-$cut".toByteArray(),
                contentType = "image/png"
            )
            try {
                crashing.finalizeCandidate(fence, burnedCandidate)
                throw AssertionError("expected simulated crash at $cut")
            } catch (crash: SimulatedAvatarCrash) {
                assertEquals(cut, crash.cut)
            }

            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                crashing.rollbackCandidate(key, burnedCandidate).status
            )
            val restarted = rig.kernel()
            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                restarted.startupRecovery.status
            )
            val durable = restarted.durableStateForTest(key)
            assertEquals(previous, durable?.current?.identity?.commitIdentity)
            assertEquals(2L, durable?.commitGenerationHighWater)

            val reuse =
                restarted.stageCandidate(
                    fence = restarted.acceptedFence(key, 3L),
                    expectedCurrentCommitIdentity = previous,
                    candidateCommitIdentity = burnedCandidate,
                    bytes = "different-reused-bytes-$cut".toByteArray(),
                    contentType = "image/png"
                )
            val samePayloadReuse =
                restarted.stageCandidate(
                    fence = restarted.acceptedFence(key, 4L),
                    expectedCurrentCommitIdentity = previous,
                    candidateCommitIdentity = burnedCandidate,
                    bytes = "first-candidate-$cut".toByteArray(),
                    contentType = "image/png"
                )

            assertEquals(RaftAndroidAvatarOperationStatus.IdentityMismatch, reuse.status)
            assertEquals(
                RaftAndroidAvatarOperationStatus.IdentityMismatch,
                samePayloadReuse.status
            )
            assertEquals(previous, restarted.durableStateForTest(key)?.current?.identity?.commitIdentity)
            assertFalse(rig.files.exists(key.journalPath))
            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                restarted.stageCandidate(
                    fence = restarted.acceptedFence(key, 5L),
                    expectedCurrentCommitIdentity = previous,
                    candidateCommitIdentity = avatarCommit(3L, "fresh-after-burn-$cut"),
                    bytes = "fresh-after-burn-$cut".toByteArray(),
                    contentType = "image/png"
                ).status
            )
        }
    }

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
    fun preTerminalSnapshotCannotInvalidatePostTerminalCommit() {
        val kernel = AvatarKernelTestRig().kernel()
        val key = avatarKey()
        val firstCommit = avatarCommit(1L, "terminal-first")
        val secondCommit = avatarCommit(2L, "terminal-second")
        kernel.commitCandidate(key, 1L, firstCommit, "first".toByteArray())
        val oldHit = kernel.readHit(key, 2L, firstCommit)
        oldHit.lease.close()
        assertEquals(
            RaftAndroidAvatarTerminalStatus.Complete,
            kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey).status
        )
        kernel.commitCandidate(key, 3L, secondCommit, "second".toByteArray())

        val stale = kernel.invalidateIfCurrent(oldHit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.StaleRequest, stale.status)
        val current = kernel.readHit(key, 4L, secondCommit)
        assertArrayEquals("second".toByteArray(), current.snapshot.bytes)
        current.lease.close()
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

    @Test
    fun rollbackCrashCutsRecoverThePreviousEntry() {
        listOf(
            RaftAndroidAvatarDurableCut.RollbackRepointed,
            RaftAndroidAvatarDurableCut.RollbackCleanup
        ).forEach { cut ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "rollback-cut-$cut")
            val previous = avatarCommit(1L, "rollback-old-$cut")
            val candidate = avatarCommit(2L, "rollback-new-$cut")
            rig.kernel().commitCandidate(key, 1L, previous, "previous-$cut".toByteArray())
            val crashing = rig.kernel(crashAt = cut)
            val fence = crashing.acceptedFence(key, 2L)
            crashing.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previous,
                candidateCommitIdentity = candidate,
                bytes = "candidate-$cut".toByteArray(),
                contentType = "image/png"
            )
            try {
                crashing.rollbackCandidate(key, candidate)
                throw AssertionError("expected simulated rollback crash at $cut")
            } catch (crash: SimulatedAvatarCrash) {
                assertEquals(cut, crash.cut)
            }

            val recovered = rig.kernel()
            val hit = recovered.readHit(key, 3L, previous)
            assertArrayEquals("previous-$cut".toByteArray(), hit.snapshot.bytes)
            hit.lease.close()
            assertEquals(RaftAndroidAvatarDurablePhase.Idle, recovered.durableStateForTest(key)?.phase)
            assertEquals(2L, recovered.durableStateForTest(key)?.commitGenerationHighWater)
            assertEquals(
                RaftAndroidAvatarOperationStatus.IdentityMismatch,
                recovered.stageCandidate(
                    fence = recovered.acceptedFence(key, 4L),
                    expectedCurrentCommitIdentity = previous,
                    candidateCommitIdentity = candidate,
                    bytes = "candidate-$cut".toByteArray(),
                    contentType = "image/png"
                ).status
            )
        }
    }
}
