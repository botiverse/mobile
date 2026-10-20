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
    fun oneValidMetadataCopyRecoversMissingOrCorruptPeer() {
        enumValues<MetadataDamage>().forEach { damage ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "metadata-$damage")
            val previous = avatarCommit(1L, "metadata-previous-$damage")
            val candidate = avatarCommit(2L, "metadata-candidate-$damage")
            rig.kernel().commitCandidate(key, 1L, previous, "old-$damage".toByteArray())
            val crashing = rig.kernel(crashAt = RaftAndroidAvatarDurableCut.SharedCommitted)
            val fence = crashing.acceptedFence(key, 2L)
            crashing.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previous,
                candidateCommitIdentity = candidate,
                bytes = "new-$damage".toByteArray(),
                contentType = "image/png"
            )
            try {
                crashing.finalizeCandidate(fence, candidate)
                throw AssertionError("expected shared-commit crash for $damage")
            } catch (crash: SimulatedAvatarCrash) {
                assertEquals(RaftAndroidAvatarDurableCut.SharedCommitted, crash.cut)
            }
            when (damage) {
                MetadataDamage.MissingIndex -> rig.files.removeWithoutSync(key.indexPath)
                MetadataDamage.CorruptIndex -> rig.files.corrupt(key.indexPath)
                MetadataDamage.MissingJournal -> rig.files.removeWithoutSync(key.journalPath)
                MetadataDamage.CorruptJournal -> rig.files.corrupt(key.journalPath)
            }

            val recovered = rig.kernel()

            assertEquals(
                "recovery status for $damage",
                RaftAndroidAvatarOperationStatus.Applied,
                recovered.startupRecovery.status
            )
            val hit = recovered.readHit(key, 3L, candidate)
            assertArrayEquals("new-$damage".toByteArray(), hit.snapshot.bytes)
            hit.lease.close()
            assertEquals(RaftAndroidAvatarDurablePhase.Idle, recovered.durableStateForTest(key)?.phase)
            assertFalse(rig.files.exists(key.journalPath))
        }
    }

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
    fun missingAndDigestMismatchedCurrentFilesAreLogicallyRetired() {
        enumValues<EntryDamage>().forEach { damage ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "entry-$damage")
            val commit = avatarCommit(1L, "entry-commit-$damage")
            val entry = rig.kernel().commitCandidate(key, 1L, commit, "valid-$damage".toByteArray())
            when (damage) {
                EntryDamage.Missing -> rig.files.removeWithoutSync(entry.entryPath)
                EntryDamage.DigestMismatch -> rig.files.put(entry.entryPath, "wrong-$damage".toByteArray())
            }

            val recovered = rig.kernel()

            assertEquals(
                "startup status for $damage",
                RaftAndroidAvatarOperationStatus.Applied,
                recovered.startupRecovery.status
            )
            assertEquals(
                RaftAndroidAvatarDurablePhase.Idle,
                recovered.durableStateForTest(key)?.phase
            )
            assertNull(recovered.durableStateForTest(key)?.current)
            assertEquals(1L, recovered.durableStateForTest(key)?.commitGenerationHighWater)
            assertFalse(rig.files.exists(entry.entryPath))
            assertEquals(
                RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.Empty),
                recovered.readSnapshot(recovered.acceptedFence(key, 2L), commit)
            )
        }
    }

    @Test
    fun transientEntryReadFailureFailsClosedWithoutTombstoningValidBytes() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val commit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, commit, "still-valid".toByteArray())
        rig.files.failNext(FakeAvatarFileOperation.Read, entry.entryPath)

        val transientMiss =
            kernel.readSnapshot(
                kernel.acceptedFence(key, 2L),
                commit
            )

        assertEquals(
            RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.RecoveryPending),
            transientMiss
        )
        assertEquals(entry, kernel.durableStateForTest(key)?.current?.identity)
        assertTrue(rig.files.exists(entry.entryPath))
        val retry = kernel.readHit(key, 3L, commit)
        assertArrayEquals("still-valid".toByteArray(), retry.snapshot.bytes)
        retry.lease.close()
    }

    @Test
    fun disappearedCurrentIsRetiredBeforeAnotherCandidateCanStage() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val currentCommit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, currentCommit, "current".toByteArray())
        rig.files.removeWithoutSync(entry.entryPath)
        val fence = kernel.acceptedFence(key, 2L)

        val firstAttempt =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = currentCommit,
                candidateCommitIdentity = avatarCommit(2L),
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, firstAttempt.status)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertNull(kernel.durableStateForTest(key)?.current)
        assertEquals(1L, kernel.durableStateForTest(key)?.commitGenerationHighWater)
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(2L),
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            ).status
        )
    }

    @Test
    fun orphanStageAndImmutableFilesAreDeletedWithoutAdoption() {
        val rig = AvatarKernelTestRig()
        val key = avatarKey()
        val orphanRevision = avatarOpaque("orphan-revision")
        val orphanFile = avatarOpaque("orphan-file")
        val orphanTx = avatarOpaque("orphan-tx")
        val orphanEntry = "${key.relativeDirectory}/entry-$orphanRevision-$orphanFile"
        val orphanStage = "${key.relativeDirectory}/stage-$orphanTx-$orphanFile"
        val unrelated = "unrelated/not-avatar.bin"
        rig.files.put(orphanEntry, "orphan-entry".toByteArray())
        rig.files.put(orphanStage, "orphan-stage".toByteArray())
        rig.files.put(unrelated, "leave-me".toByteArray())

        val recovered = rig.kernel()

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, recovered.startupRecovery.status)
        assertFalse(rig.files.exists(orphanEntry))
        assertFalse(rig.files.exists(orphanStage))
        assertTrue(rig.files.exists(unrelated))
        assertNull(recovered.durableStateForTest(key))
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

    @Test
    fun newlyObservedOrphanBlocksSameKeyPromotionUntilExplicitRecovery() {
        listOf(
            "metadata-tmp-${avatarOpaque("temp")}",
            "stage-${avatarOpaque("tx")}-${avatarOpaque("file")}"
        ).forEachIndexed { index, fileName ->
            val rig = AvatarKernelTestRig()
            val kernel = rig.kernel()
            val key = avatarKey(bytes = "late-orphan-$index")
            val orphan = "${key.relativeDirectory}/$fileName"
            rig.files.put(orphan, "late-orphan".toByteArray())
            val fence = kernel.acceptedFence(key, 1L)

            assertEquals(
                "same-key promote must be blocked by $fileName",
                RaftAndroidAvatarOperationStatus.Busy,
                kernel.stageCandidate(
                    fence = fence,
                    expectedCurrentCommitIdentity = null,
                    candidateCommitIdentity = avatarCommit(1L, "late-orphan-$index"),
                    bytes = "candidate".toByteArray(),
                    contentType = "image/png"
                ).status
            )
            assertEquals(RaftAndroidAvatarOperationStatus.Applied, kernel.retryRecovery().status)
            assertFalse(rig.files.exists(orphan))
            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                kernel.stageCandidate(
                    fence = fence,
                    expectedCurrentCommitIdentity = null,
                    candidateCommitIdentity = avatarCommit(1L, "late-orphan-$index"),
                    bytes = "candidate".toByteArray(),
                    contentType = "image/png"
                ).status
            )
        }
    }

    @Test
    fun failedOrphanDeleteKeepsNamedRecoveryAuthorityAndBlocksPromotion() {
        listOf(false, true).forEach { failAfterMutation ->
            val rig = AvatarKernelTestRig()
            val key = avatarKey(bytes = "delete-failure-$failAfterMutation")
            val orphan =
                "${key.relativeDirectory}/entry-${avatarOpaque("revision")}-" +
                    avatarOpaque("file")
            rig.files.put(orphan, "orphan".toByteArray())
            rig.files.failNext(
                operation = FakeAvatarFileOperation.Delete,
                path = orphan,
                afterMutation = failAfterMutation
            )

            val blocked = rig.kernel()

            assertEquals(
                "startup must retain retry authority, afterMutation=$failAfterMutation",
                RaftAndroidAvatarOperationStatus.CleanupPending,
                blocked.startupRecovery.status
            )
            assertEquals(
                RaftAndroidAvatarDurablePhase.RollbackCleanup,
                blocked.durableStateForTest(key)?.phase
            )
            assertTrue(rig.files.exists(key.indexPath))
            assertTrue(rig.files.exists(key.journalPath))
            assertEquals(
                RaftAndroidAvatarRequestStatus.AdmissionClosed,
                blocked.beginRequest(key, 1L).status
            )

            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                blocked.retryRecovery().status
            )
            assertNull(blocked.durableStateForTest(key))
            assertFalse(rig.files.exists(orphan))
            val fence = blocked.acceptedFence(key, 1L)
            assertEquals(
                RaftAndroidAvatarOperationStatus.Applied,
                blocked.stageCandidate(
                    fence = fence,
                    expectedCurrentCommitIdentity = null,
                    candidateCommitIdentity = avatarCommit(1L),
                    bytes = "new".toByteArray(),
                    contentType = "image/png"
                ).status
            )
        }
    }

    @Test
    fun incompleteExplicitRecoveryClosesExistingFencesUntilRetryFullyApplies() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "runtime-recovery-admission")
        val fence = kernel.acceptedFence(key, 1L)
        val orphan =
            "${key.relativeDirectory}/stage-${avatarOpaque("runtime-recovery-tx")}-" +
                avatarOpaque("runtime-recovery-file")
        rig.files.put(orphan, "orphan".toByteArray())
        rig.files.failNext(FakeAvatarFileOperation.Delete, orphan)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, kernel.retryRecovery().status)
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.AdmissionClosed),
            kernel.readSnapshot(fence, avatarCommit(1L, "runtime-recovery"))
        )
        assertEquals(
            RaftAndroidAvatarRequestStatus.AdmissionClosed,
            kernel.beginRequest(key, 2L).status
        )

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, kernel.retryRecovery().status)
        assertFalse(rig.files.exists(orphan))
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(1L, "runtime-recovery"),
                bytes = "new".toByteArray(),
                contentType = "image/png"
            ).status
        )
    }

    @Test
    fun candidateRenameDirectorySyncFailureRetainsSharedCommittedReceipt() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val previous = avatarCommit(1L)
        val candidate = avatarCommit(2L)
        kernel.commitCandidate(key, 1L, previous, "previous".toByteArray())
        val fence = kernel.acceptedFence(key, 2L)
        val staged =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previous,
                candidateCommitIdentity = candidate,
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, staged.status)
        rig.files.failNext(
            operation = FakeAvatarFileOperation.Move,
            path = assertNotNull(staged.entry).let { staged.entry!!.stagePath },
            afterMutation = true
        )

        val result = kernel.finalizeCandidate(fence, candidate)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, result.status)
        assertEquals(
            RaftAndroidAvatarDurablePhase.SharedCommitted,
            kernel.durableStateForTest(key)?.phase
        )
        val recovered = rig.kernel()
        val hit = recovered.readHit(key, 3L, candidate)
        assertArrayEquals("candidate".toByteArray(), hit.snapshot.bytes)
        hit.lease.close()
    }

    @Test
    fun sharedCommittedMissingCandidateReturnsMissAndKeepsPreviousExactOnly() {
        val rig = AvatarKernelTestRig()
        val key = avatarKey()
        val previous = avatarCommit(1L)
        val candidate = avatarCommit(2L)
        rig.kernel().commitCandidate(key, 1L, previous, "previous".toByteArray())
        val crashing = rig.kernel(crashAt = RaftAndroidAvatarDurableCut.SharedCommitted)
        val fence = crashing.acceptedFence(key, 2L)
        val staged =
            crashing.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previous,
                candidateCommitIdentity = candidate,
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )
        try {
            crashing.finalizeCandidate(fence, candidate)
            throw AssertionError("expected shared-commit crash")
        } catch (crash: SimulatedAvatarCrash) {
            assertEquals(RaftAndroidAvatarDurableCut.SharedCommitted, crash.cut)
        }
        rig.files.removeWithoutSync(staged.entry!!.stagePath)

        val recovered = rig.kernel()

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, recovered.startupRecovery.status)
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.CommitIdentityMismatch
            ),
            recovered.readSnapshot(recovered.acceptedFence(key, 3L), candidate)
        )
        val previousHit = recovered.readHit(key, 4L, previous)
        assertArrayEquals("previous".toByteArray(), previousHit.snapshot.bytes)
        previousHit.lease.close()
    }

    @Test
    fun failedPreviousCleanupPinsTransactionUntilRetry() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val previousCommit = avatarCommit(1L)
        val candidateCommit = avatarCommit(2L)
        val previous = kernel.commitCandidate(key, 1L, previousCommit, "previous".toByteArray())
        val fence = kernel.acceptedFence(key, 2L)
        kernel.stageCandidate(
            fence = fence,
            expectedCurrentCommitIdentity = previousCommit,
            candidateCommitIdentity = candidateCommit,
            bytes = "candidate".toByteArray(),
            contentType = "image/png"
        )
        rig.files.failNext(FakeAvatarFileOperation.Delete, previous.entryPath)

        val finalized = kernel.finalizeCandidate(fence, candidateCommit)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, finalized.status)
        assertEquals(
            RaftAndroidAvatarDurablePhase.CommitCleanup,
            kernel.durableStateForTest(key)?.phase
        )
        assertEquals(
            RaftAndroidAvatarOperationStatus.Busy,
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = candidateCommit,
                candidateCommitIdentity = avatarCommit(3L),
                bytes = "third".toByteArray(),
                contentType = "image/png"
            ).status
        )
        val candidateHit = kernel.readHit(key, 3L, candidateCommit)
        assertArrayEquals("candidate".toByteArray(), candidateHit.snapshot.bytes)
        candidateHit.lease.close()
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, kernel.retryRecovery().status)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertFalse(rig.files.exists(previous.entryPath))
    }

    @Test
    fun authoritativeRetirementDuringPostSwitchCleanupTombstonesTheWholeTransaction() {
        val decoded = FailableAvatarDecodedIdentityStore()
        val rig = AvatarKernelTestRig(decoded = decoded)
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "retire-post-switch")
        val previousCommit = avatarCommit(1L, "retire-post-switch-previous")
        val candidateCommit = avatarCommit(2L, "retire-post-switch-candidate")
        val previous =
            kernel.commitCandidate(key, 1L, previousCommit, "previous".toByteArray())
        val fence = kernel.acceptedFence(key, 2L)
        val staged =
            kernel.stageCandidate(
                fence = fence,
                expectedCurrentCommitIdentity = previousCommit,
                candidateCommitIdentity = candidateCommit,
                bytes = "candidate".toByteArray(),
                contentType = "image/png"
            )
        decoded.failNextExactEviction = true
        assertEquals(
            RaftAndroidAvatarOperationStatus.CleanupPending,
            kernel.finalizeCandidate(fence, candidateCommit).status
        )
        assertEquals(
            RaftAndroidAvatarDurablePhase.CommitCleanup,
            kernel.durableStateForTest(key)?.phase
        )
        val candidateHit = kernel.readHit(key, 3L, candidateCommit)
        candidateHit.lease.close()

        val retired = kernel.invalidateIfCurrent(candidateHit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, retired.status)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertNull(kernel.durableStateForTest(key)?.current)
        assertEquals(2L, kernel.durableStateForTest(key)?.commitGenerationHighWater)
        assertFalse(rig.files.exists(previous.entryPath))
        assertFalse(rig.files.exists(staged.entry!!.entryPath))
    }

    @Test
    fun authoritativeInvalidationEvictsDecodedBeforeRetryablePhysicalDelete() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val commit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, commit, "retire-me".toByteArray())
        val hit = kernel.readHit(key, 2L, commit)
        assertTrue(
            kernel.registerDecoded(
                hit.snapshot,
                hit.lease,
                RaftAndroidAvatarDecodedVariant(80, 80, "crop")
            )
        )
        hit.lease.close()
        rig.files.failNext(FakeAvatarFileOperation.Delete, entry.entryPath)

        val invalidated = kernel.invalidateIfCurrent(hit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, invalidated.status)
        assertFalse(rig.decoded.containsExact(entry))
        assertEquals(
            RaftAndroidAvatarDurablePhase.RollbackCleanup,
            kernel.durableStateForTest(key)?.phase
        )
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.Empty),
            kernel.readSnapshot(kernel.acceptedFence(key, 3L), commit)
        )
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, kernel.retryRecovery().status)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertNull(kernel.durableStateForTest(key)?.current)
        assertEquals(1L, kernel.durableStateForTest(key)?.commitGenerationHighWater)
    }

    @Test
    fun retiredCoordinateCannotReplaySameOrDifferentPayloadAfterRestart() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "retired-coordinate")
        val retiredCommit = avatarCommit(1L, "retired-coordinate")
        kernel.commitCandidate(key, 1L, retiredCommit, "retired-payload".toByteArray())
        val hit = kernel.readHit(key, 2L, retiredCommit)
        hit.lease.close()
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            kernel.invalidateIfCurrent(hit.snapshot).status
        )

        val restarted = rig.kernel()
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, restarted.startupRecovery.status)
        listOf("retired-payload", "different-retired-payload").forEachIndexed { index, payload ->
            assertEquals(
                RaftAndroidAvatarOperationStatus.IdentityMismatch,
                restarted.stageCandidate(
                    fence = restarted.acceptedFence(key, 3L + index),
                    expectedCurrentCommitIdentity = null,
                    candidateCommitIdentity = retiredCommit,
                    bytes = payload.toByteArray(),
                    contentType = "image/png"
                ).status
            )
        }
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            restarted.stageCandidate(
                fence = restarted.acceptedFence(key, 5L),
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(2L, "fresh-after-retirement"),
                bytes = "fresh-after-retirement".toByteArray(),
                contentType = "image/png"
            ).status
        )
    }

    @Test
    fun logicalTombstonePrecedesDecodedEvictionAndPhysicalDelete() {
        val decoded = FailableAvatarDecodedIdentityStore()
        val rig = AvatarKernelTestRig(decoded = decoded)
        val kernel = rig.kernel()
        val key = avatarKey()
        val commit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, commit, "ordered-retire".toByteArray())
        val hit = kernel.readHit(key, 2L, commit)
        assertTrue(
            kernel.registerDecoded(
                hit.snapshot,
                hit.lease,
                RaftAndroidAvatarDecodedVariant(72, 72, "crop")
            )
        )
        hit.lease.close()
        decoded.failNextExactEviction = true

        val first = kernel.invalidateIfCurrent(hit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, first.status)
        assertEquals(
            RaftAndroidAvatarDurablePhase.RollbackCleanup,
            kernel.durableStateForTest(key)?.phase
        )
        assertNull(kernel.durableStateForTest(key)?.current)
        assertTrue(decoded.containsExact(entry))
        assertTrue("physical bytes must wait for decoded eviction", rig.files.exists(entry.entryPath))

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, kernel.retryRecovery().status)
        assertFalse(decoded.containsExact(entry))
        assertFalse(rig.files.exists(entry.entryPath))
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, kernel.durableStateForTest(key)?.phase)
        assertNull(kernel.durableStateForTest(key)?.current)
        assertEquals(1L, kernel.durableStateForTest(key)?.commitGenerationHighWater)
    }

    @Test
    fun newerJournalSequencePreservesLogicalTombstoneAcrossPartialMetadataWrite() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val commit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, commit, "retire-atomically".toByteArray())
        val hit = kernel.readHit(key, 2L, commit)
        hit.lease.close()
        rig.files.failNext(FakeAvatarFileOperation.AtomicWrite, key.indexPath)

        val partial = kernel.invalidateIfCurrent(hit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, partial.status)
        assertTrue(rig.files.exists(entry.entryPath))
        val recovered = rig.kernel()
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, recovered.startupRecovery.status)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, recovered.durableStateForTest(key)?.phase)
        assertNull(recovered.durableStateForTest(key)?.current)
        assertEquals(1L, recovered.durableStateForTest(key)?.commitGenerationHighWater)
        assertFalse(rig.files.exists(entry.entryPath))
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.Empty),
            recovered.readSnapshot(recovered.acceptedFence(key, 3L), commit)
        )
    }

    @Test
    fun sameProcessRetryResyncsFinalMetadataDeleteThatMayAlreadyBeVisible() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "final-metadata-delete")
        val commit = avatarCommit(1L, "final-metadata-delete")
        kernel.commitCandidate(key, 1L, commit, "retire".toByteArray())
        val hit = kernel.readHit(key, 2L, commit)
        hit.lease.close()
        rig.files.failNext(
            operation = FakeAvatarFileOperation.Delete,
            path = key.journalPath,
            afterMutation = true
        )

        val first = kernel.invalidateIfCurrent(hit.snapshot)

        assertEquals(RaftAndroidAvatarOperationStatus.CleanupPending, first.status)
        assertTrue(rig.files.exists(key.indexPath))
        assertFalse(rig.files.exists(key.journalPath))
        val deletesBeforeRetry =
            rig.files.events.count { event ->
                event.operation == FakeAvatarFileOperation.Delete && event.path == key.journalPath
            }
        assertEquals(
            RaftAndroidAvatarOperationStatus.Busy,
            kernel.stageCandidate(
                fence = kernel.acceptedFence(key, 3L),
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = avatarCommit(2L, "blocked-by-final-delete"),
                bytes = "must-wait".toByteArray(),
                contentType = "image/png"
            ).status
        )

        assertEquals(RaftAndroidAvatarOperationStatus.Applied, kernel.retryRecovery().status)
        assertTrue(
            rig.files.events.count { event ->
                event.operation == FakeAvatarFileOperation.Delete && event.path == key.journalPath
            } > deletesBeforeRetry
        )
    }

    private enum class MetadataDamage {
        MissingIndex,
        CorruptIndex,
        MissingJournal,
        CorruptJournal
    }

    private enum class EntryDamage {
        Missing,
        DigestMismatch
    }
}
