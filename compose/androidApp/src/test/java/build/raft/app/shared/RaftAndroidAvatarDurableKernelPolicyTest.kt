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
    fun terminalClearClosesAdmissionDrainsDeliveryAndReachesPhysicalZero() {
        val admissionClosed = CountDownLatch(1)
        val rig = AvatarKernelTestRig()
        val kernel =
            rig.kernel(
                terminalObserver =
                    RaftAndroidAvatarTerminalObserver { _, cut ->
                        if (cut == RaftAndroidAvatarTerminalCut.AdmissionClosed) {
                            admissionClosed.countDown()
                        }
                    }
            )
        val key = avatarKey()
        val commit = avatarCommit(1L)
        kernel.commitCandidate(key, 1L, commit, "leased-avatar".toByteArray())
        val hit = kernel.readHit(key, 2L, commit)
        assertTrue(
            kernel.registerDecoded(
                hit.snapshot,
                hit.lease,
                RaftAndroidAvatarDecodedVariant(120, 120, "center-crop")
            )
        )
        val heldValidation =
            kernel.acquireLease(hit.snapshot.requestFence, RaftAndroidAvatarLeaseKind.Validation)!!
        val heldDecode = kernel.acquireLease(hit.snapshot.requestFence, RaftAndroidAvatarLeaseKind.Decode)!!
        val heldDelivery =
            kernel.acquireLease(hit.snapshot.requestFence, RaftAndroidAvatarLeaseKind.Delivery)!!
        assertEquals(RaftAndroidAvatarLeaseKind.Validation, heldValidation.kind)
        assertEquals(RaftAndroidAvatarLeaseKind.Decode, heldDecode.kind)
        assertEquals(RaftAndroidAvatarLeaseKind.Delivery, heldDelivery.kind)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val terminal = executor.submit<RaftAndroidAvatarTerminalResult> { kernel.clearAll() }
            assertTrue("terminal clear never closed admission", admissionClosed.await(2, TimeUnit.SECONDS))
            assertEquals(
                RaftAndroidAvatarRequestStatus.AdmissionClosed,
                kernel.beginRequest(key, 3L).status
            )
            assertFalse("clear returned while a delivery lease was active", terminal.isDone)
            assertFalse(
                kernel.registerDecoded(
                    hit.snapshot,
                    hit.lease,
                    RaftAndroidAvatarDecodedVariant(240, 240, "fit-center")
                )
            )

            hit.lease.close()
            heldValidation.close()
            heldDecode.close()
            assertFalse("clear returned before the final delivery lease drained", terminal.isDone)
            heldDelivery.close()
            val result = terminal.get(2, TimeUnit.SECONDS)

            assertEquals(RaftAndroidAvatarTerminalStatus.Complete, result.status)
            assertEquals(1, result.retiredKeys)
            assertEquals(0, kernel.activeLeaseCountForTest())
            assertEquals(0, kernel.decodedIdentityCountForTest())
            assertNull(kernel.durableStateForTest(key))
            assertFalse(rig.files.paths().any { key.ownsManagedPath(it) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun terminalCompletionStartsFreshNativeNamespaceAfterRestart() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(subject = "fresh-terminal-subject")
        val retiredCommit = avatarCommit(2L, "fresh-terminal-retired")
        val retiredEntry =
            kernel.commitCandidate(
                key = key,
                requestGeneration = 1L,
                commitIdentity = retiredCommit,
                bytes = "retired-avatar".toByteArray()
            )
        val retiredHit = kernel.readHit(key, 2L, retiredCommit)
        assertTrue(
            kernel.registerDecoded(
                retiredHit.snapshot,
                retiredHit.lease,
                RaftAndroidAvatarDecodedVariant(80, 80, "terminal-fresh")
            )
        )
        retiredHit.lease.close()

        val terminalScope =
            RaftAndroidAvatarTerminalScope.Subject(
                key.partitionKey,
                key.ownerKey,
                key.subjectKey
            )
        val terminal = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, terminal.status)
        assertFalse(rig.files.exists(retiredEntry.entryPath))
        assertFalse(rig.files.paths().any(key::ownsManagedPath))
        assertFalse(rig.files.exists(terminalScope.markerPath))
        assertEquals(0, kernel.decodedIdentityCountForTest())
        assertNull(kernel.durableStateForTest(key))

        val restarted = rig.kernel()
        assertEquals(RaftAndroidAvatarOperationStatus.Applied, restarted.startupRecovery.status)
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(RaftAndroidAvatarReadMissReason.Empty),
            restarted.readSnapshot(restarted.acceptedFence(key, 3L), retiredCommit)
        )
        val freshFence = restarted.acceptedFence(key, 4L)
        assertEquals(
            RaftAndroidAvatarReadResult.Miss(
                RaftAndroidAvatarReadMissReason.NoExpectedCommitIdentity
            ),
            restarted.readSnapshot(freshFence, expectedCommitIdentity = null)
        )
        val freshCommit = avatarCommit(1L, "fresh-terminal-current")
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            restarted.stageCandidate(
                fence = freshFence,
                expectedCurrentCommitIdentity = null,
                candidateCommitIdentity = freshCommit,
                bytes = "canonical-current-avatar".toByteArray(),
                contentType = "image/png"
            ).status
        )
        assertEquals(
            RaftAndroidAvatarOperationStatus.Applied,
            restarted.finalizeCandidate(freshFence, freshCommit).status
        )
        val currentHit = restarted.readHit(key, 5L, freshCommit)
        assertArrayEquals("canonical-current-avatar".toByteArray(), currentHit.snapshot.bytes)
        currentHit.lease.close()
    }

    @Test
    fun terminalScopePersistsEveryLogicalTombstoneBeforeDecodedEvictionOrPhysicalDelete() {
        val files = FakeAvatarDurableFileSystem()
        val firstKey = avatarKey(subject = "ordered-terminal", bytes = "ordered-first")
        val secondKey = avatarKey(subject = "ordered-terminal", bytes = "ordered-second")
        lateinit var entries: List<RaftAndroidAvatarEntryIdentity>
        var matchingEvictionObserved = false
        val decoded =
            FailableAvatarDecodedIdentityStore { scope ->
                assertEquals(
                    RaftAndroidAvatarTerminalScope.Subject(
                        firstKey.partitionKey,
                        firstKey.ownerKey,
                        firstKey.subjectKey
                    ),
                    scope
                )
                listOf(firstKey, secondKey).forEach { key ->
                    val encoded = assertNotNull(files.bytes(key.indexPath)).let {
                        files.bytes(key.indexPath)!!
                    }
                    val tombstone =
                        RaftAndroidAvatarDurableStateCodec.decode(
                            RaftAndroidAvatarDurableRecordKind.Index,
                            encoded
                        )
                    assertEquals(RaftAndroidAvatarDurablePhase.RollbackCleanup, tombstone?.phase)
                    assertNull(tombstone?.current)
                    assertEquals(
                        RaftAndroidAvatarTombstoneReason.TerminalRetirement,
                        tombstone?.tombstoneReason
                    )
                }
                entries.forEach { entry ->
                    assertTrue(
                        "physical bytes must remain until every key is tombstoned and decoded scope is evicted",
                        files.exists(entry.entryPath)
                    )
                }
                matchingEvictionObserved = true
            }
        val rig = AvatarKernelTestRig(files = files, decoded = decoded)
        val kernel = rig.kernel()
        val firstCommit = avatarCommit(1L, "ordered-first")
        val secondCommit = avatarCommit(1L, "ordered-second")
        entries =
            listOf(
                kernel.commitCandidate(firstKey, 1L, firstCommit, "first".toByteArray()),
                kernel.commitCandidate(secondKey, 1L, secondCommit, "second".toByteArray())
            )
        listOf(firstKey to firstCommit, secondKey to secondCommit).forEach { (key, commit) ->
            val hit = kernel.readHit(key, 2L, commit)
            assertTrue(
                kernel.registerDecoded(
                    hit.snapshot,
                    hit.lease,
                    RaftAndroidAvatarDecodedVariant(64, 64, "terminal-order")
                )
            )
            hit.lease.close()
        }

        val result =
            kernel.retireSubject(
                firstKey.partitionKey,
                firstKey.ownerKey,
                firstKey.subjectKey
            )

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, result.status)
        assertTrue(matchingEvictionObserved)
        assertEquals(0, kernel.decodedIdentityCountForTest())
        entries.forEach { entry -> assertFalse(files.exists(entry.entryPath)) }
    }

    @Test
    fun partialTerminalTombstonePreparationKeepsEveryPhysicalAndDecodedEntryUntilRetry() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val firstKey = avatarKey(subject = "partial-terminal", bytes = "partial-first")
        val secondKey = avatarKey(subject = "partial-terminal", bytes = "partial-second")
        val firstCommit = avatarCommit(1L, "partial-first")
        val secondCommit = avatarCommit(1L, "partial-second")
        val firstEntry =
            kernel.commitCandidate(firstKey, 1L, firstCommit, "first".toByteArray())
        val secondEntry =
            kernel.commitCandidate(secondKey, 1L, secondCommit, "second".toByteArray())
        listOf(firstKey to firstCommit, secondKey to secondCommit).forEach { (key, commit) ->
            val hit = kernel.readHit(key, 2L, commit)
            assertTrue(
                kernel.registerDecoded(
                    hit.snapshot,
                    hit.lease,
                    RaftAndroidAvatarDecodedVariant(80, 80, "partial-terminal")
                )
            )
            hit.lease.close()
        }
        val orderedKeys = listOf(firstKey, secondKey).sortedBy { it.relativeDirectory }
        rig.files.failNext(FakeAvatarFileOperation.AtomicWrite, orderedKeys[1].journalPath)

        val first =
            kernel.retireSubject(
                firstKey.partitionKey,
                firstKey.ownerKey,
                firstKey.subjectKey
            )

        assertEquals(RaftAndroidAvatarTerminalStatus.CleanupPending, first.status)
        assertEquals(2, kernel.decodedIdentityCountForTest())
        assertTrue(rig.files.exists(firstEntry.entryPath))
        assertTrue(rig.files.exists(secondEntry.entryPath))
        assertEquals(
            RaftAndroidAvatarDurablePhase.RollbackCleanup,
            kernel.durableStateForTest(orderedKeys[0])?.phase
        )
        assertEquals(
            RaftAndroidAvatarDurablePhase.Idle,
            kernel.durableStateForTest(orderedKeys[1])?.phase
        )

        val retry =
            kernel.retireSubject(
                firstKey.partitionKey,
                firstKey.ownerKey,
                firstKey.subjectKey
            )

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, retry.status)
        assertEquals(0, kernel.decodedIdentityCountForTest())
        assertFalse(rig.files.exists(firstEntry.entryPath))
        assertFalse(rig.files.exists(secondEntry.entryPath))
    }

    @Test
    fun terminalDeleteFailureKeepsAdmissionClosedUntilExactRetryCompletes() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val commit = avatarCommit(1L)
        val entry = kernel.commitCandidate(key, 1L, commit, "terminal".toByteArray())
        val oldFence = kernel.acceptedFence(key, 2L)
        rig.files.failNext(FakeAvatarFileOperation.Delete, entry.entryPath)

        val first =
            kernel.retireSubject(
                partitionKey = key.partitionKey,
                ownerKey = key.ownerKey,
                subjectKey = key.subjectKey
            )

        assertEquals(RaftAndroidAvatarTerminalStatus.CleanupPending, first.status)
        assertEquals(
            RaftAndroidAvatarRequestStatus.AdmissionClosed,
            kernel.beginRequest(key, 3L).status
        )
        assertEquals(
            RaftAndroidAvatarDurablePhase.RollbackCleanup,
            kernel.durableStateForTest(key)?.phase
        )
        assertTrue(rig.files.exists(entry.entryPath))

        val retry =
            kernel.retireSubject(
                partitionKey = key.partitionKey,
                ownerKey = key.ownerKey,
                subjectKey = key.subjectKey
            )

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, retry.status)
        val newFence = kernel.acceptedFence(key, 3L)
        assertNotEquals(oldFence.terminalEpochs, newFence.terminalEpochs)
        assertFalse(rig.files.paths().any { key.ownsManagedPath(it) })
    }

    @Test
    fun terminalRetryRetainsParentSyncAuthorityWhenEntryDeleteMayAlreadyBeVisible() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "terminal-visible-delete")
        val entry =
            kernel.commitCandidate(
                key,
                1L,
                avatarCommit(1L, "terminal-visible-delete"),
                "terminal".toByteArray()
            )
        rig.files.failNext(
            operation = FakeAvatarFileOperation.Delete,
            path = entry.entryPath,
            afterMutation = true
        )

        val first = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.CleanupPending, first.status)
        assertFalse(rig.files.exists(entry.entryPath))
        assertEquals(
            1,
            rig.files.events.count { event ->
                event.operation == FakeAvatarFileOperation.Delete && event.path == entry.entryPath
            }
        )

        val retry = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, retry.status)
        assertEquals(
            "retry must reissue the durable delete so a real filesystem re-fsyncs the parent",
            2,
            rig.files.events.count { event ->
                event.operation == FakeAvatarFileOperation.Delete && event.path == entry.entryPath
            }
        )
    }

    @Test
    fun terminalRetryReusesTransactionIdentityAcrossPartialMetadataRewrite() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "terminal-rewrite")
        val entry =
            kernel.commitCandidate(
                key,
                1L,
                avatarCommit(1L, "terminal-rewrite"),
                "terminal".toByteArray()
            )
        rig.files.failNext(FakeAvatarFileOperation.Delete, entry.entryPath)
        assertEquals(
            RaftAndroidAvatarTerminalStatus.CleanupPending,
            kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey).status
        )
        val originalTransaction = kernel.durableStateForTest(key)?.transactionId
        assertNotNull(originalTransaction)
        rig.files.failNext(FakeAvatarFileOperation.AtomicWrite, key.indexPath)

        val partialRetry =
            kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.CleanupPending, partialRetry.status)
        assertEquals(RaftAndroidAvatarDurablePhase.RollbackCleanup, kernel.durableStateForTest(key)?.phase)
        assertEquals(originalTransaction, kernel.durableStateForTest(key)?.transactionId)
        assertEquals(
            RaftAndroidAvatarTerminalStatus.Complete,
            kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey).status
        )
    }

    @Test
    fun terminalMarkerDeleteDurabilityFailureRecreatesAuthorityBeforeReopeningAdmission() {
        listOf(false, true).forEach { failAfterMutation ->
            val rig = AvatarKernelTestRig()
            val kernel = rig.kernel()
            val key = avatarKey(bytes = "marker-delete-$failAfterMutation")
            kernel.commitCandidate(
                key,
                1L,
                avatarCommit(1L, "marker-delete-$failAfterMutation"),
                "terminal-marker".toByteArray()
            )
            val scope =
                RaftAndroidAvatarTerminalScope.Subject(
                    key.partitionKey,
                    key.ownerKey,
                    key.subjectKey
                )
            rig.files.failNext(
                operation = FakeAvatarFileOperation.Delete,
                path = scope.markerPath,
                afterMutation = failAfterMutation
            )

            val first = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

            assertEquals(RaftAndroidAvatarTerminalStatus.CleanupPending, first.status)
            assertEquals(
                RaftAndroidAvatarRequestStatus.AdmissionClosed,
                kernel.beginRequest(key, 2L).status
            )
            assertFalse(rig.files.paths().any { key.ownsManagedPath(it) })

            val retry = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

            assertEquals(RaftAndroidAvatarTerminalStatus.Complete, retry.status)
            assertFalse(rig.files.exists(scope.markerPath))
            assertEquals(RaftAndroidAvatarRequestStatus.Accepted, kernel.beginRequest(key, 2L).status)
        }
    }

    @Test
    fun terminalMarkerCreationFailureStillClosesInProcessAdmissionUntilRetry() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "marker-create")
        val entry =
            kernel.commitCandidate(
                key,
                1L,
                avatarCommit(1L, "marker-create"),
                "terminal-marker".toByteArray()
            )
        val scope =
            RaftAndroidAvatarTerminalScope.Subject(
                key.partitionKey,
                key.ownerKey,
                key.subjectKey
            )
        rig.files.failNext(FakeAvatarFileOperation.NewWrite, scope.markerPath)

        val first = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.StorageFailure, first.status)
        assertEquals(
            RaftAndroidAvatarRequestStatus.AdmissionClosed,
            kernel.beginRequest(key, 2L).status
        )
        assertTrue(rig.files.exists(entry.entryPath))

        val retry = kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, retry.status)
        assertFalse(rig.files.exists(entry.entryPath))
        assertEquals(RaftAndroidAvatarRequestStatus.Accepted, kernel.beginRequest(key, 2L).status)
    }

    @Test
    fun broaderTerminalScopeConsumesContainedRetryMarkersBeforeReportingComplete() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey(bytes = "nested-terminal-marker")
        kernel.commitCandidate(
            key,
            1L,
            avatarCommit(1L, "nested-terminal-marker"),
            "nested".toByteArray()
        )
        val subjectScope =
            RaftAndroidAvatarTerminalScope.Subject(
                key.partitionKey,
                key.ownerKey,
                key.subjectKey
            )
        rig.files.failNext(FakeAvatarFileOperation.Delete, subjectScope.markerPath)
        assertEquals(
            RaftAndroidAvatarTerminalStatus.CleanupPending,
            kernel.retireSubject(key.partitionKey, key.ownerKey, key.subjectKey).status
        )
        assertTrue(rig.files.exists(subjectScope.markerPath))
        assertEquals(
            RaftAndroidAvatarRequestStatus.AdmissionClosed,
            kernel.beginRequest(key, 2L).status
        )

        val clear = kernel.clearAll()

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, clear.status)
        assertFalse(rig.files.exists(subjectScope.markerPath))
        assertFalse(rig.files.exists(RaftAndroidAvatarTerminalScope.All.markerPath))
        assertEquals(RaftAndroidAvatarRequestStatus.Accepted, kernel.beginRequest(key, 2L).status)
    }

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
    fun subjectAndOwnerRetirementDoNotDeleteOtherPartitions() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val owner = "same-owner"
        val first = avatarKey(partition = "server-a", owner = owner, subject = "first", bytes = "one")
        val second = avatarKey(partition = "server-a", owner = owner, subject = "second", bytes = "two")
        val otherServer = avatarKey(partition = "server-b", owner = owner, subject = "first", bytes = "one")
        val firstCommit = avatarCommit(1L, "scope-first")
        val secondCommit = avatarCommit(1L, "scope-second")
        val otherCommit = avatarCommit(1L, "scope-other")
        kernel.commitCandidate(first, 1L, firstCommit, "first".toByteArray())
        kernel.commitCandidate(second, 1L, secondCommit, "second".toByteArray())
        kernel.commitCandidate(otherServer, 1L, otherCommit, "other".toByteArray())

        val subjectResult =
            kernel.retireSubject(first.partitionKey, first.ownerKey, first.subjectKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, subjectResult.status)
        assertNull(kernel.durableStateForTest(first))
        val secondHit = kernel.readHit(second, 2L, secondCommit)
        assertArrayEquals("second".toByteArray(), secondHit.snapshot.bytes)
        secondHit.lease.close()
        val otherHit = kernel.readHit(otherServer, 2L, otherCommit)
        assertArrayEquals("other".toByteArray(), otherHit.snapshot.bytes)
        otherHit.lease.close()

        val ownerResult = kernel.retireOwner(second.partitionKey, second.ownerKey)

        assertEquals(RaftAndroidAvatarTerminalStatus.Complete, ownerResult.status)
        assertNull(kernel.durableStateForTest(second))
        val preservedOtherServer = kernel.readHit(otherServer, 3L, otherCommit)
        assertArrayEquals("other".toByteArray(), preservedOtherServer.snapshot.bytes)
        preservedOtherServer.lease.close()
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

    @Test
    fun lruCountsOnlyManagedDataAndEvictsOldestIdleEntry() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val firstKey = avatarKey(bytes = "lru-first")
        val secondKey = avatarKey(bytes = "lru-second")
        val firstCommit = avatarCommit(1L, "lru-first")
        val secondCommit = avatarCommit(1L, "lru-second")
        rig.files.put("other-cache/large.bin", ByteArray(4_096))
        kernel.commitCandidate(firstKey, 1L, firstCommit, ByteArray(5) { 1 })
        kernel.commitCandidate(secondKey, 1L, secondCommit, ByteArray(6) { 2 })

        assertEquals(11L, kernel.managedBytes())
        val result = kernel.trimToBudget(6L)

        assertEquals(RaftAndroidAvatarBudgetStatus.Evicted, result.status)
        assertEquals(6L, result.managedBytes)
        assertEquals(1, result.evictedEntries)
        assertEquals(
            RaftAndroidAvatarDurablePhase.Idle,
            kernel.durableStateForTest(firstKey)?.phase
        )
        assertNull(kernel.durableStateForTest(firstKey)?.current)
        assertEquals(1L, kernel.durableStateForTest(firstKey)?.commitGenerationHighWater)
        val survivor = kernel.readHit(secondKey, 2L, secondCommit)
        assertArrayEquals(ByteArray(6) { 2 }, survivor.snapshot.bytes)
        survivor.lease.close()
        assertTrue(rig.files.exists("other-cache/large.bin"))
    }

    @Test
    fun liveTransactionAndTombstoneArtifactsStayPinnedAgainstBudgetTrim() {
        val transactionRig = AvatarKernelTestRig()
        val transactionKernel = transactionRig.kernel()
        val transactionKey = avatarKey(bytes = "pinned-transaction")
        val previous = avatarCommit(1L, "pinned-previous")
        val candidate = avatarCommit(2L, "pinned-candidate")
        transactionKernel.commitCandidate(
            transactionKey,
            1L,
            previous,
            ByteArray(5) { 1 }
        )
        val fence = transactionKernel.acceptedFence(transactionKey, 2L)
        transactionKernel.stageCandidate(
            fence = fence,
            expectedCurrentCommitIdentity = previous,
            candidateCommitIdentity = candidate,
            bytes = ByteArray(8) { 2 },
            contentType = "image/png"
        )

        val transactionTrim = transactionKernel.trimToBudget(0L)

        assertEquals(RaftAndroidAvatarBudgetStatus.Pinned, transactionTrim.status)
        assertEquals(13L, transactionTrim.managedBytes)
        assertEquals(
            RaftAndroidAvatarDurablePhase.CandidateDurable,
            transactionKernel.durableStateForTest(transactionKey)?.phase
        )

        val tombstoneRig = AvatarKernelTestRig()
        val tombstoneKernel = tombstoneRig.kernel()
        val tombstoneKey = avatarKey(bytes = "pinned-tombstone")
        val tombstoneCommit = avatarCommit(1L, "pinned-tombstone")
        val entry =
            tombstoneKernel.commitCandidate(
                tombstoneKey,
                1L,
                tombstoneCommit,
                ByteArray(7) { 3 }
            )
        val hit = tombstoneKernel.readHit(tombstoneKey, 2L, tombstoneCommit)
        hit.lease.close()
        tombstoneRig.files.failNext(FakeAvatarFileOperation.Delete, entry.entryPath)
        assertEquals(
            RaftAndroidAvatarOperationStatus.CleanupPending,
            tombstoneKernel.invalidateIfCurrent(hit.snapshot).status
        )

        val tombstoneTrim = tombstoneKernel.trimToBudget(0L)

        assertEquals(RaftAndroidAvatarBudgetStatus.Pinned, tombstoneTrim.status)
        assertEquals(7L, tombstoneTrim.managedBytes)
        assertEquals(
            RaftAndroidAvatarDurablePhase.RollbackCleanup,
            tombstoneKernel.durableStateForTest(tombstoneKey)?.phase
        )
    }

    @Test
    fun lruDeleteFailureReturnsCleanupPendingWithDurableTombstone() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        val entry = kernel.commitCandidate(key, 1L, avatarCommit(1L), ByteArray(9) { 4 })
        rig.files.failNext(FakeAvatarFileOperation.Delete, entry.entryPath)

        val result = kernel.trimToBudget(0L)

        assertEquals(RaftAndroidAvatarBudgetStatus.CleanupPending, result.status)
        assertEquals(9L, result.managedBytes)
        assertEquals(
            RaftAndroidAvatarDurablePhase.RollbackCleanup,
            kernel.durableStateForTest(key)?.phase
        )
        assertTrue(rig.files.exists(key.indexPath))
        assertTrue(rig.files.exists(key.journalPath))
    }

    @Test
    fun metadataCodecRejectsChecksumDamageAndRecordKindSwap() {
        val rig = AvatarKernelTestRig()
        val kernel = rig.kernel()
        val key = avatarKey()
        kernel.commitCandidate(key, 1L, avatarCommit(1L), "codec".toByteArray())
        val index = assertNotNull(rig.files.bytes(key.indexPath)).let { rig.files.bytes(key.indexPath)!! }
        val decoded =
            RaftAndroidAvatarDurableStateCodec.decode(
                RaftAndroidAvatarDurableRecordKind.Index,
                index
            )
        assertEquals(key, decoded?.key)
        assertEquals(RaftAndroidAvatarDurablePhase.Idle, decoded?.phase)
        assertEquals(1L, decoded?.commitGenerationHighWater)

        val damaged = index.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }

        assertNull(
            RaftAndroidAvatarDurableStateCodec.decode(
                RaftAndroidAvatarDurableRecordKind.Index,
                damaged
            )
        )
        assertNull(
            RaftAndroidAvatarDurableStateCodec.decode(
                RaftAndroidAvatarDurableRecordKind.Journal,
                index
            )
        )
    }
}
