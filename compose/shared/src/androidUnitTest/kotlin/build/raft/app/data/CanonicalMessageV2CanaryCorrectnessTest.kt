package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryMode
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoader
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #168 correctness fixes of the single-scope durable canary, on the real SQLite store:
 * #1 a failed durable write still displays; #2 a late `New` never overwrites a stored fact; #3 a frame
 * older than the window is never written; #4 the per-scope cap moves the oldest edge; #5 one realtime
 * frame is written once however many entries deliver it; #6 display never waits for the snapshot, which
 * loads in the background while later frames are buffered and replayed only when provably continuous.
 */
class CanonicalMessageV2CanaryCorrectnessTest {
    private val grant = RealtimeGrantRecord(
        slotKey = RealtimeSlotKey(scopeLeaseId = "scope-1", serverId = "server-1"),
        principalId = "principal-1",
        environmentId = "env-test",
        authGeneration = 1L,
        connectionFamilyId = "family-1",
        connectionEpochId = "epoch-1",
    )
    private val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    private val scope = SyncScopeKey(partition, "channel-1", "channel")

    // ---- #1 Held never hides the frame -------------------------------------------------------------

    @Test
    fun aSnapshotThatIsUnavailableStillDisplaysTheFrame() = withFreshDatabase { database ->
        val rig = Rig(database)
        rig.snapshotUnavailable = true
        val (owner, projected) = rig.displayingOwner()
        val event = rig.messageNew("m1", 1L)

        assertTrue(runBlocking { owner.accept(grant, event, source = "test") })
        runBlocking { rig.settle() }

        assertEquals(listOf<RealtimeEvent>(event), projected, "the durable write failed; display must not")
        assertEquals(0, rig.store.snapshotWrites)
    }

    @Test
    fun aCommittedSnapshotDisplaysTheFrameExactlyAsBefore() = withFreshDatabase { database ->
        val rig = Rig(database)
        val (owner, projected) = rig.displayingOwner()
        val event = rig.messageNew("m1", 1L)

        assertTrue(runBlocking { owner.accept(grant, event, source = "test") })
        runBlocking { rig.settle() }

        assertEquals(listOf<RealtimeEvent>(event), projected)
        assertEquals(1, rig.store.snapshotWrites)
    }

    // ---- #2 a late New is insert-only ---------------------------------------------------------------

    @Test
    fun aLateNewNeverOverwritesTheEditedFactAlreadyOnDisk() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            // The window comes from a snapshot that already has m1's edit.
            rig.baseline(rig.input("m1", 1L, content = "edited", kind = CanonicalMessageFoldKind.Updated))

            rig.accept(rig.input("m1", 1L, content = "original"))

            assertEquals(mapOf("m1" to "edited"), rig.storedContents())
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aNewForAnAbsentFactIsInserted() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m1", 1L, content = "edited", kind = CanonicalMessageFoldKind.Updated))

            rig.accept(rig.input("m2", 2L, content = "original"))

            assertEquals(mapOf("m1" to "edited", "m2" to "original"), rig.storedContents())
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aSnapshotStillReplacesAStoredFact() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m1", 1L, content = "first"))
            rig.accept(rig.input("m1", 1L, content = "edited", kind = CanonicalMessageFoldKind.Updated))

            assertEquals(mapOf("m1" to "edited"), rig.storedContents())
            assertEquals(2, rig.store.snapshotWrites)
        }
    }

    // ---- #3 below-window frames are display-only ----------------------------------------------------

    @Test
    fun aFrameInsideTheWindowIsWritten() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m20", 20L), oldestLoadedSeq = 10L, newestLoadedSeq = 20L)

            assertEquals(
                CanonicalMessageV2CanaryOutcome.Committed,
                rig.accept(rig.input("m15", 15L)),
            )

            assertEquals(setOf("m15", "m20"), rig.storedContents().keys)
            assertEquals(10L, rig.storedCursor()?.oldestLoadedSeq)
        }
    }

    @Test
    fun aFrameBelowTheWindowIsNeverWrittenAndNeverMovesTheEdge() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m20", 20L), oldestLoadedSeq = 10L, newestLoadedSeq = 20L)

            assertEquals(
                CanonicalMessageV2CanaryOutcome.NotSelected,
                rig.accept(rig.input("m5", 5L)),
                "display only",
            )

            assertEquals(setOf("m20"), rig.storedContents().keys)
            assertEquals(10L, rig.storedCursor()?.oldestLoadedSeq)
            assertEquals(0, rig.store.incrementalWrites)
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_canary skip=below_window") })
        }
    }

    // ---- #4 the per-scope cap moves the oldest edge -------------------------------------------------

    @Test
    fun aScopeAtTheCapKeepsItsBeginning() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.fillThenAppendOne(storedFacts = 499)

            val cursor = rig.storedCursor()!!
            assertEquals(1L, cursor.oldestLoadedSeq)
            assertEquals(true, cursor.hasLoadedOldest)
            assertEquals(cursor, rig.engine.currentCursor(scope))
            assertEquals(500, rig.storedContents().size)
        }
    }

    @Test
    fun aScopeOverTheCapMovesItsOldestEdgeAndForgetsItsBeginning() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.fillThenAppendOne(storedFacts = 500)

            val cursor = rig.storedCursor()!!
            assertEquals(2L, cursor.oldestLoadedSeq, "seq 1 was pruned")
            assertEquals(false, cursor.hasLoadedOldest, "the window no longer reaches the beginning")
            assertEquals(cursor, rig.engine.currentCursor(scope), "the engine commits what the store wrote")
            assertEquals(500, rig.storedContents().size)
        }
    }

    // ---- #5 one frame, one write --------------------------------------------------------------------

    @Test
    fun oneRealtimeFrameThroughEveryEntryIsWrittenOnce() = withFreshDatabase { database ->
        val rig = Rig(database)
        val processOwner = rig.owner()
        val shellOwner = rig.owner()
        runBlocking {
            // Android: AppShell → process owner, then the shell owner. iOS / OHOS additionally the
            // process ingress coordinator → process owner.
            suspend fun deliverEverywhere(event: RealtimeEvent) {
                processOwner.acceptProcessMutation(grant, event, "process_normal_realtime", presenceTransportEpoch = 3L)
                processOwner.acceptProcessMutation(grant, event, "process_realtime", presenceTransportEpoch = 3L)
                shellOwner.accept(grant, event, source = "app_shell", transportEpoch = 3L)
            }

            deliverEverywhere(rig.messageNew("m1", 1L))
            rig.settle()
            assertEquals(1, rig.store.snapshotWrites, "the baselining frame snapshots once")

            deliverEverywhere(rig.messageNew("m2", 2L))
            assertEquals(1, rig.store.incrementalWrites, "the next frame is appended once")
            assertEquals(setOf("m1", "m2"), rig.storedContents().keys)
        }
    }

    @Test
    fun twoRealtimeFramesThroughOneEntryAreWrittenTwice() = withFreshDatabase { database ->
        val rig = Rig(database)
        val processOwner = rig.owner()
        runBlocking {
            processOwner.acceptProcessMutation(grant, rig.messageNew("m1", 1L), "process_realtime", presenceTransportEpoch = 3L)
            rig.settle()
            assertEquals(1, rig.store.snapshotWrites)

            processOwner.acceptProcessMutation(grant, rig.messageNew("m2", 2L), "process_realtime", presenceTransportEpoch = 3L)
            processOwner.acceptProcessMutation(grant, rig.messageNew("m3", 3L), "process_realtime", presenceTransportEpoch = 3L)
            assertEquals(2, rig.store.incrementalWrites)
            assertEquals(setOf("m1", "m2", "m3"), rig.storedContents().keys)
        }
    }

    @Test
    fun twoDistinctEditsOfOneMessageAreBothWritten() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m1", 1L, content = "first"))
            rig.accept(rig.input("m1", 1L, content = "edit-1", kind = CanonicalMessageFoldKind.Updated))
            rig.accept(rig.input("m1", 1L, content = "edit-2", kind = CanonicalMessageFoldKind.Updated))
            // The same edit through another entry is that frame again.
            rig.accept(rig.input("m1", 1L, content = "edit-2", kind = CanonicalMessageFoldKind.Updated))

            assertEquals(3, rig.store.snapshotWrites)
            assertEquals(mapOf("m1" to "edit-2"), rig.storedContents())
        }
    }

    // ---- #6 display never waits for the snapshot ----------------------------------------------------

    @Test
    fun theFrameIsDisplayedBeforeItsSnapshotFinishesLoadingEvenWhenTheGrantIsRevokedMeanwhile() =
        withFreshDatabase { database ->
            val rig = Rig(database)
            val (owner, projected) = rig.displayingOwner()
            val event = rig.messageNew("m1", 1L)
            val hold = rig.holdNextSnapshot()
            bounded {
                val acceptance = async { owner.accept(grant, event, source = "test") }
                hold.started.await()

                // The snapshot is still loading (device: ~17 s offline). The frame must be on screen already.
                assertEquals(listOf<RealtimeEvent>(event), projected, "displayed before the snapshot load finished")
                assertEquals(0, rig.store.snapshotWrites)

                // The socket drops meanwhile: the grant is revoked before the load ends.
                rig.grantCurrent = false
                hold.release.complete(Unit)
                assertTrue(acceptance.await())
                rig.settle()

                assertEquals(listOf<RealtimeEvent>(event), projected, "displayed exactly once")
            }
        }

    @Test
    fun aSnapshotThatSucceedsInTheBackgroundDisplaysTheFrameOnceAndWritesItOnce() = withFreshDatabase { database ->
        val rig = Rig(database)
        val (owner, projected) = rig.displayingOwner()
        val event = rig.messageNew("m1", 1L)
        val hold = rig.holdNextSnapshot()
        bounded {
            val acceptance = async { owner.accept(grant, event, source = "test") }
            hold.started.await()
            assertEquals(listOf<RealtimeEvent>(event), projected, "displayed before the snapshot load finished")

            hold.release.complete(Unit)
            assertTrue(acceptance.await())
            rig.settle()

            assertEquals(listOf<RealtimeEvent>(event), projected, "displayed exactly once")
            assertEquals(1, rig.store.snapshotWrites, "written once, by the snapshot")
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(setOf("m1"), rig.storedContents().keys)
        }
    }

    @Test
    fun aSameEpochFrameAboveTheSnapshotCursorIsAppendedAfterTheCommit() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canary.noteTransportEpoch(partition, 2L)
            val hold = rig.holdNextSnapshot()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m1", 1L), 2L, settle = false))
            hold.started.await()

            // Arrives while the snapshot (read before it: latestSeq 1) loads.
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m2", 2L), 2L, settle = false))
            assertEquals(0, rig.store.incrementalWrites, "nothing is written while the snapshot loads")

            hold.release.complete(Unit)
            rig.settle()

            assertEquals(setOf("m1", "m2"), rig.storedContents().keys)
            assertEquals(1, rig.store.snapshotWrites)
            assertEquals(1, rig.store.incrementalWrites, "m2 is appended after the snapshot")
            assertEquals(2L, rig.storedCursor()?.latestSeq)

            // The window is continuous under epoch 2: the next frame appends without a load.
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(rig.input("m3", 3L), 2L))
            assertEquals(1, rig.loaderCalls)
            assertEquals(2, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aBufferedFrameAtTheSnapshotCursorIsNotWrittenTwice() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canary.noteTransportEpoch(partition, 2L)
            // The server already has m2 when the snapshot reads: the snapshot's latestSeq is 2.
            rig.serverHas(rig.input("m2", 2L))
            val hold = rig.holdNextSnapshot()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m1", 1L), 2L, settle = false))
            hold.started.await()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m2", 2L), 2L, settle = false))

            hold.release.complete(Unit)
            rig.settle()

            assertEquals(setOf("m1", "m2"), rig.storedContents().keys)
            assertEquals(2L, rig.storedCursor()?.latestSeq)
            assertEquals(1, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites, "seq == snapshot latestSeq: the snapshot already holds it")

            // The same delivery again (another entry) is that frame again; the window is still continuous.
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(rig.input("m2", 2L), 2L))
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(rig.input("m3", 3L), 2L))
            assertEquals(1, rig.store.incrementalWrites)
            assertEquals(1, rig.loaderCalls)
        }
    }

    @Test
    fun anOlderEpochFrameBufferedDuringTheLoadIsDisplayedButNeverWritten() = withFreshDatabase { database ->
        val rig = Rig(database)
        val (owner, projected) = rig.displayingOwner()
        bounded {
            rig.canary.noteTransportEpoch(partition, 2L)
            val hold = rig.holdNextSnapshot()
            val first = rig.messageNew("m1", 1L)
            assertTrue(owner.accept(grant, first, source = "test", transportEpoch = 2L))
            hold.started.await()

            // A late frame of the old socket, delivered while the epoch-2 snapshot loads.
            val late = rig.messageNew("m5", 5L)
            assertTrue(owner.accept(grant, late, source = "test", transportEpoch = 1L))
            assertEquals(listOf<RealtimeEvent>(first, late), projected, "both are on screen")

            hold.release.complete(Unit)
            rig.settle()

            assertEquals(setOf("m1"), rig.storedContents().keys, "the old socket's frame is never written")
            assertEquals(0, rig.store.incrementalWrites)
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_canary skip=stale_transport") && "replay=true" in it })

            // Twin: a frame of the snapshot's own epoch is appended; the window was not marked discontinuous.
            assertTrue(owner.accept(grant, rig.messageNew("m6", 6L), source = "test", transportEpoch = 2L))
            assertEquals(1, rig.store.incrementalWrites)
            assertEquals(1, rig.loaderCalls)
            assertEquals(setOf("m1", "m6"), rig.storedContents().keys)
        }
    }

    @Test
    fun aNewerEpochFrameBufferedDuringTheLoadMarksTheScopeDiscontinuous() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canary.noteTransportEpoch(partition, 2L)
            val hold = rig.holdNextSnapshot()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m1", 1L), 2L, settle = false))
            hold.started.await()
            // The socket reconnected as epoch 3 while the epoch-2 snapshot loads.
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m2", 2L), 3L, settle = false))

            hold.release.complete(Unit)
            rig.settle()

            assertEquals(setOf("m1"), rig.storedContents().keys)
            assertEquals(0, rig.store.incrementalWrites, "not appended across a possible gap")
            assertTrue(rig.logs.any { "snapshot=replay_discontinuous" in it && "reason=epoch_mismatch" in it })

            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m3", 3L), 3L))
            assertEquals(2, rig.loaderCalls, "the next frame snapshots again")
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aResetWhileTheSnapshotLoadsWritesNothingAndNothingReachesTheNewSession() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            val hold = rig.holdNextSnapshot()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m1", 1L), settle = false))
            hold.started.await()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m2", 2L), settle = false))

            // Logout / account change: the partition is revoked while the snapshot loads.
            rig.engine.clearPartition(partition)
            assertEquals(emptySet(), rig.canary.pendingSnapshotScopes(), "the job is cancelled")
            hold.release.complete(Unit)
            rig.settle()
            assertEquals(0, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(emptyMap(), rig.storedContents())

            // New session: its own first frame snapshots; the old buffer (m2) is never replayed into it.
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m3", 3L)))
            assertEquals(1, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    @Test
    fun anEvictionWhileTheSnapshotLoadsDropsEvenAJobThatIgnoresCancellation() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            // A transport that does not stop on cancellation: only the re-check inside the writer stops it.
            val stale = rig.holdNextSnapshot(ignoresCancellation = true)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m1", 1L), settle = false))
            stale.started.await()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m2", 2L), settle = false))

            rig.engine.clearScope(scope)
            assertEquals(emptySet(), rig.canary.pendingSnapshotScopes())

            // New session for the same scope, its snapshot also in flight.
            val fresh = rig.holdNextSnapshot()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m3", 3L), settle = false))
            fresh.started.await()

            // The stale job returns its (old) page while the new session exists.
            stale.release.complete(Unit)
            fresh.release.complete(Unit)
            rig.settle()

            assertEquals(1, rig.store.snapshotWrites, "only the new session's snapshot is written")
            assertEquals(0, rig.store.incrementalWrites, "the stale buffer (m2) is never replayed")
            assertEquals(setOf("m1", "m2", "m3"), rig.storedContents().keys, "the new session's page")
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_canary snapshot=stale_dropped") })
        }
    }

    @Test
    fun aFullBufferIsReplayedAfterTheCommit() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.fillBufferWhileLoading(frames = CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP)

            assertEquals(1, rig.store.snapshotWrites)
            assertEquals(CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP, rig.store.incrementalWrites)
            val next = CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP + 2L
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(rig.input("m$next", next), 2L))
            assertEquals(1, rig.loaderCalls)
        }
    }

    @Test
    fun aBufferOverflowMarksTheScopeDiscontinuousAndTheNextFrameSnapshotsOnce() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.fillBufferWhileLoading(frames = CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP + 1)

            assertEquals(1, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites, "an overflowed buffer is dropped, never partially replayed")
            assertEquals(setOf("m1"), rig.storedContents().keys)
            assertTrue(rig.logs.any { "snapshot=buffer_overflow" in it })

            val next = CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP + 3L
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(rig.input("m$next", next), 2L))
            assertEquals(2, rig.loaderCalls, "exactly one new snapshot")
            assertEquals(2, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aFrameDeliveredThroughEveryEntryWhileTheSnapshotLoadsIsWrittenOnce() = withFreshDatabase { database ->
        val rig = Rig(database)
        val processOwner = rig.owner()
        val shellOwner = rig.owner()
        bounded {
            val hold = rig.holdNextSnapshot()
            val m1 = rig.messageNew("m1", 1L)
            processOwner.acceptProcessMutation(grant, m1, "process_normal_realtime", presenceTransportEpoch = 3L)
            hold.started.await()
            processOwner.acceptProcessMutation(grant, m1, "process_realtime", presenceTransportEpoch = 3L)
            shellOwner.accept(grant, m1, source = "app_shell", transportEpoch = 3L)

            val m2 = rig.messageNew("m2", 2L)
            processOwner.acceptProcessMutation(grant, m2, "process_normal_realtime", presenceTransportEpoch = 3L)
            processOwner.acceptProcessMutation(grant, m2, "process_realtime", presenceTransportEpoch = 3L)
            shellOwner.accept(grant, m2, source = "app_shell", transportEpoch = 3L)

            hold.release.complete(Unit)
            rig.settle()
            assertEquals(1, rig.loaderCalls)
            assertEquals(1, rig.store.snapshotWrites, "m1: one snapshot however many entries delivered it")
            assertEquals(1, rig.store.incrementalWrites, "m2: buffered once, appended once")
            assertEquals(setOf("m1", "m2"), rig.storedContents().keys)

            // A late fourth delivery after the replay is still that frame again.
            shellOwner.accept(grant, m2, source = "app_shell", transportEpoch = 3L)
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    // ---- rig ------------------------------------------------------------------------------------------

    /** Every hold-based test is bounded: a regression that blocks on the held load fails instead of hanging. */
    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(30_000L) { block() }
    }

    /** One background snapshot load the test holds open: [started] once it read the server, [release] to return. */
    private class SnapshotHold(val ignoresCancellation: Boolean) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private inner class Rig(database: SlockSqlDatabase) {
        val store = CountingStore(SqlDelightCanonicalMessageV2Store(database, now = { 100L }))
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val logs = mutableListOf<String>()
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
            logger = { logs += it },
        )
        var snapshotUnavailable = false

        /** What the legacy display path sees as the current grant (the socket drop revokes it). */
        @Volatile var grantCurrent = true

        @Volatile private var nextHold: SnapshotHold? = null

        /** Read only after [settle] or while every load is held: incremented by the background job. */
        @Volatile var loaderCalls = 0

        /** What the server returns for a snapshot: the latest known version of each message. */
        private val serverMessages = linkedMapOf<String, CanonicalMessageFoldInput>()
        private var serverWindow: Pair<Long, Long>? = null

        private val mapper = RealtimeEventMapper(
            messageShadowObserver = MessageShadowObserver { _, raw, _ ->
                decodeCanonicalMessageWire(raw, ProductionFace.MessageNew)
            },
        )

        val loader = CanonicalMessageV2SnapshotLoader { request ->
            loaderCalls += 1
            val hold = nextHold.also { nextHold = null }
            if (snapshotUnavailable) return@CanonicalMessageV2SnapshotLoader CanonicalMessageV2SnapshotLoadResult.Unavailable("offline")
            // The server is read when the request starts; frames that arrive while it is held are not in it.
            val envelopes = serverMessages.values.map(::envelope)
            val seqs = envelopes.map { it.fact.seq }
            val (oldest, newest) = serverWindow ?: (seqs.min() to seqs.max())
            if (hold != null) {
                hold.started.complete(Unit)
                if (hold.ignoresCancellation) withContext(NonCancellable) { hold.release.await() } else hold.release.await()
            }
            CanonicalMessageV2SnapshotLoadResult.Loaded(
                CanonicalMessageV2SnapshotFrame(
                    scope = request.scope,
                    envelopes = envelopes,
                    cursor = PersistedCursor(
                        latestSeq = newest,
                        oldestLoadedSeq = oldest,
                        newestLoadedSeq = newest,
                        hasLoadedOldest = false,
                        hasLoadedNewest = true,
                    ),
                ),
            )
        }

        private val gateway = CanonicalMessageV2OwnerGateway { _, foldInput, transportEpoch ->
            remember(foldInput)
            canary.accept(partition, foldInput, loader, transportEpoch)
        }

        private fun remember(input: CanonicalMessageFoldInput) {
            serverMessages[input.row.id] = input
        }

        suspend fun baseline(
            input: CanonicalMessageFoldInput,
            oldestLoadedSeq: Long? = null,
            newestLoadedSeq: Long? = null,
        ) {
            serverWindow = if (oldestLoadedSeq != null && newestLoadedSeq != null) oldestLoadedSeq to newestLoadedSeq else null
            remember(input)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, canary.accept(partition, input, loader))
            settle()
            assertEquals(CanonicalMessageV2CanaryMode.SoleApply, canary.modeFor(scope.serverId, scope.scopeId))
            serverWindow = null
        }

        /**
         * A realtime frame; the server's own copy is not changed by a late `New`. Returns what accept returned;
         * with [settle] the background snapshot it may have started has finished too.
         */
        suspend fun accept(
            input: CanonicalMessageFoldInput,
            transportEpoch: Long? = null,
            settle: Boolean = true,
        ): CanonicalMessageV2CanaryOutcome {
            if (input.kind == CanonicalMessageFoldKind.Updated || input.row.id !in serverMessages) remember(input)
            return canary.accept(partition, input, loader, transportEpoch).also { if (settle) settle() }
        }

        /** The server has [input] without any frame for it reaching the client yet. */
        fun serverHas(input: CanonicalMessageFoldInput) = remember(input)

        /** The next snapshot load blocks after reading the server until the test releases it. */
        fun holdNextSnapshot(ignoresCancellation: Boolean = false): SnapshotHold =
            SnapshotHold(ignoresCancellation).also { nextHold = it }

        suspend fun settle() = canary.awaitSnapshotJobs()

        /** m1 schedules the epoch-2 snapshot; [frames] more frames arrive while it is held; then it commits. */
        suspend fun fillBufferWhileLoading(frames: Int) {
            canary.noteTransportEpoch(partition, 2L)
            val hold = holdNextSnapshot()
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, accept(input("m1", 1L), 2L, settle = false))
            hold.started.await()
            (2L..frames + 1L).forEach { seq ->
                assertEquals(CanonicalMessageV2CanaryOutcome.Pending, accept(input("m$seq", seq), 2L, settle = false))
            }
            hold.release.complete(Unit)
            settle()
        }

        suspend fun fillThenAppendOne(storedFacts: Int) {
            val envelopes = (1..storedFacts).map { envelope(input("m$it", it.toLong())) }
            assertEquals(
                CanonicalMessageV2CommitResult.Committed,
                engine.applySnapshot(
                    CanonicalMessageV2SnapshotFrame(
                        scope = scope,
                        envelopes = envelopes,
                        cursor = PersistedCursor(
                            latestSeq = storedFacts.toLong(),
                            oldestLoadedSeq = 1L,
                            newestLoadedSeq = storedFacts.toLong(),
                            hasLoadedOldest = true,
                            hasLoadedNewest = true,
                        ),
                    ),
                ),
            )
            val next = storedFacts + 1L
            assertEquals(
                CanonicalMessageV2CommitResult.Committed,
                engine.applyIncremental(
                    CanonicalMessageV2IncrementalFrame(
                        scope = scope,
                        envelopes = listOf(envelope(input("m$next", next))),
                        cursor = engine.currentCursor(scope)!!.copy(latestSeq = next, newestLoadedSeq = next),
                        insertOnly = true,
                    ),
                ),
            )
        }

        suspend fun storedContents(): Map<String, String> =
            store.restore(scope).envelopes.associate { it.fact.id to it.fact.content }

        suspend fun storedCursor(): PersistedCursor? = store.readCursor(scope)

        fun owner(): SharedRealtimeDomainEventOwner =
            SharedRealtimeDomainEventOwner(
                runIfCurrentGrant = { _, block ->
                    if (grantCurrent) {
                        block()
                        true
                    } else {
                        false
                    }
                },
                runWithCurrentMessageGrant = { _, block ->
                    block(CurrentMessageCommitContext(partition))
                    true
                },
            ).also { it.installCanonicalMessageV2OwnerGateway(gateway) }

        fun displayingOwner(): Pair<SharedRealtimeDomainEventOwner, List<RealtimeEvent>> {
            val projected = mutableListOf<RealtimeEvent>()
            val owner = owner()
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, event -> projected += event }),
            )
            return owner to projected
        }

        /** Real `message:new` wire JSON (server `projectMessageSocketPayload` + rich aggregates) via the mapper. */
        fun messageNew(id: String, seq: Long): MessageNew {
            val event = mapper.map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "$id", "seq": $seq, "channelId": "channel-1",
                  "senderType": "user", "senderId": "user-1", "randomId": null,
                  "messageType": "chat", "content": "body-$id", "actionMetadata": null, "threadId": null,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
                  "senderName": "Alice", "senderDescription": null, "senderMembershipStatus": null,
                  "mentions": [], "attachments": [], "reactions": [],
                  "conversationContext": { "channelType": "channel" }
                }
                """.trimIndent(),
            )
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        fun input(
            id: String,
            seq: Long,
            content: String = "body-$id",
            kind: CanonicalMessageFoldKind = CanonicalMessageFoldKind.New,
        ) = CanonicalMessageFoldInput(
            kind = kind,
            row = CanonicalMessageRow(
                id = id,
                channelId = "channel-1",
                seq = seq,
                senderType = "user",
                senderId = "user-1",
                content = content,
                messageType = "chat",
                createdAt = "2026-07-17T00:00:00.000Z",
                senderName = "Alice",
                conversationContext = CanonicalConversationContextWire("channel"),
            ),
            presentAggregates = MessageAggregateField.entries.toList(),
        )

        private fun envelope(input: CanonicalMessageFoldInput): CanonicalMessageEnvelopeV2 =
            requireNotNull(input.toCanonicalMessageV2Envelope(partition, replyCount = 0)) { "fixture must normalize" }
    }

    private class CountingStore(private val delegate: CanonicalMessageV2Store) : CanonicalMessageV2Store {
        // Incremented by the background snapshot job; read after it settled or while it is held.
        @Volatile var snapshotWrites = 0
        @Volatile var incrementalWrites = 0

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applySnapshot(frame)
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalWrites += 1
            return delegate.applyIncremental(frame)
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope = delegate.restore(scope)
        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> = delegate.clearScope(scope)
        override suspend fun clearPartition(partition: DurablePartitionKey) = delegate.clearPartition(partition)
        override suspend fun knownPartitions(): List<DurablePartitionKey> = delegate.knownPartitions()
        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> =
            delegate.baselinedScopeKinds(partition, scopeId)
        override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? = delegate.readCursor(scope)
    }

    private fun withFreshDatabase(block: (SlockSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            block(SlockSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
