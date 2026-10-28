package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageV2Coverage
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2ThreadParent
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2LiveCoverageTracker
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.PersistFaultHook
import build.raft.app.network.sync.PersistFaultPoint
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.windowEnvelopes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Message storage design 2026-10-27, Phase 3a (artin: "realtime messages seen on screen are gone when re-entering —
 * store every realtime message"). Real SQLite on a file, the real realtime owner, canary (live coverage tracker) and
 * V2 ingest; "restart" = a new driver + store on the same file, read the way the offline first frame reads it.
 */
class CanonicalMessageV2RealtimeIngestRoundTripTest {
    // ---- (1) a never-entered scope ------------------------------------------------------------------

    @Test
    fun aColdStartRealtimeMessageOfANeverEnteredChannelIsReadAfterARestart() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            // The production `message:new` omits senderDescription and reactions: stored as a partial fact.
            assertTrue(rig.deliver(rig.messageNew(other, "m5", 5L, omit = PRODUCTION_NEW_OMITS), epoch = 1L))
            assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, rig.canary.continuityOf(other))
            assertEquals(listOf("m5"), rig.projectedIds())
        }
        file.rig().session { restarted ->
            val frame = assertNotNull(restarted.coldRead(other))
            assertEquals(listOf(5L), frame.seqs())
            // The first screen (SharedThreadStore's durable stand-in) projects windowEnvelopes(): the loose fact is there.
            assertEquals(listOf(5L), frame.windowEnvelopes().map { it.fact.seq })
            assertEquals(emptyList(), restarted.segments(other), "loose: no claim without proof")
            assertNull(restarted.lastUserAccessAtMs(other), "never opened: ranks least recently used")
        }
    }

    @Test
    fun aRealtimeReplyOfANeverEnteredThreadIsStoredUnderTheParentTheFrameClaims() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            assertTrue(rig.deliver(rig.replyNew(thread, "r7", 7L, omit = PRODUCTION_NEW_OMITS), epoch = 1L))
        }
        file.rig().session { restarted ->
            val frame = assertNotNull(restarted.coldReadReply(thread, ThreadDurableReplyParent("channel-1", "root-1")))
            assertEquals(listOf(7L), frame.seqs())
            assertEquals("channel", frame.cursor.parentScopeKind)
            // Any other parent is not this thread's stand-in.
            assertNull(restarted.coldReadReply(thread, ThreadDurableReplyParent("channel-1", "root-2")))
        }
    }

    // ---- (2) a scope entered earlier, then a reconnect ----------------------------------------------

    @Test
    fun aMessageAfterAReconnectOfAnEnteredChannelIsStoredLooseAndSurvivesTheRestart() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channel))
            rig.connect(2L)
            assertTrue(rig.deliver(rig.messageNew(channel, "m30", 30L), epoch = 2L))
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channel))
            assertEquals(listOf("m30"), rig.projectedIds())
        }
        file.rig().session { restarted ->
            val frame = assertNotNull(restarted.coldRead(channel))
            assertEquals(listOf(10L, 20L, 30L), frame.seqs())
            assertEquals(listOf(10L to 20L), restarted.segments(channel), "the epoch change proves nothing: 30 stays loose")
            // Loose above the head segment, and still on the first screen.
            assertEquals(listOf(10L, 20L, 30L), frame.windowEnvelopes().map { it.fact.seq }.sorted())
            // A new process: no window in memory at all; the next frame is stored too.
            restarted.connect(3L)
            assertTrue(restarted.deliver(restarted.messageNew(channel, "m40", 40L), epoch = 3L))
        }
        file.rig().session { again ->
            assertEquals(listOf(10L, 20L, 30L, 40L), assertNotNull(again.coldRead(channel)).seqs())
        }
    }

    // ---- (3) the commit fails ------------------------------------------------------------------------

    @Test
    fun aFailedCommitStillDisplaysTheMessageExactlyOnceAndWritesNothing() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            rig.killBeforeCommit = true
            val event = rig.messageNew(other, "m5", 5L)
            assertTrue(rig.deliver(event, epoch = 1L))
            assertEquals(listOf<RealtimeEvent>(event), rig.projected.toList())
            assertTrue(rig.logs.any { "canonical_v2_realtime outcome=failed" in it && "msg=m5" in it }, rig.logs.toString())
            // The next frame, once the disk recovers, is stored and displayed once too.
            rig.killBeforeCommit = false
            assertTrue(rig.deliver(rig.messageNew(other, "m6", 6L), epoch = 1L))
            assertEquals(listOf("m5", "m6"), rig.projectedIds())
        }
        file.rig().session { restarted ->
            assertEquals(listOf(6L), assertNotNull(restarted.coldRead(other)).seqs())
        }
    }

    // ---- (4) a stale grant ---------------------------------------------------------------------------

    @Test
    fun aFrameOfAStaleGrantIsNeitherWrittenNorDisplayed() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            // Positive control: the same rig and grant, while current.
            assertTrue(rig.deliver(rig.messageNew(other, "m5", 5L), epoch = 1L))
            assertEquals(listOf(5L), assertNotNull(rig.coldRead(other)).seqs())
            // Sign-out: the registry no longer holds the grant; a late frame of it arrives.
            rig.currentGrant = null
            assertFalse(rig.deliver(rig.messageNew(other, "m6", 6L), epoch = 1L))
            rig.ingest.awaitIdle()
            assertEquals(listOf(5L), assertNotNull(rig.coldRead(other)).seqs(), "the stale frame leaked to disk")
            // Server access lost: the grant is gone and the partition was cleared; a late frame must not refill it.
            rig.engine.clearPartition(partition)
            assertFalse(rig.deliver(rig.messageNew(channel, "m8", 8L), epoch = 1L))
            // The loss lands after the grant check but before the disk work: the commit is refused (I12).
            rig.currentGrant = rig.grant
            val event = rig.messageNew(channel, "m9", 9L)
            val commit = RealtimeIngestCanonicalMessageV2OwnerGateway(rig.ingest, rig.canary)
                .captureUnderGrant(rig.grant, CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, assertNotNull(event.decodedCanonical)), 1L)
            rig.engine.clearPartition(partition)
            commit()
            assertEquals(listOf("m5"), rig.projectedIds())
            assertTrue(rig.logs.any { "msg=m9" in it && "reason=partition_revoked" in it }, rig.logs.toString())
        }
        file.rig().session { restarted ->
            assertNull(restarted.coldRead(other))
            assertNull(restarted.coldRead(channel))
        }
    }

    // ---- (5) continuity: identical seqs, only the proof differs ---------------------------------------

    @Test
    fun onlyAProvenContinuousRunExtendsTheHeadSegment() {
        // The pair has identical seqs and the same epochs; only `rooms:joined` on the enter's connection differs.
        fun run(roomsJoined: Boolean): Pair<List<Pair<Long, Long>>, List<Long>> = withFile { file ->
            file.rig().session { rig ->
                rig.connect(1L, roomsJoined = roomsJoined)
                rig.enter(channel, 10L, 20L)
                assertTrue(rig.deliver(rig.messageNew(channel, "m30", 30L), epoch = 1L))
                assertEquals(listOf("m30"), rig.projectedIds())
            }
            file.rig().session { restarted ->
                restarted.segments(channel) to assertNotNull(restarted.coldRead(channel)).seqs()
            }
        }
        val proven = run(roomsJoined = true)
        val unproven = run(roomsJoined = false)
        assertEquals(listOf(10L to 30L), proven.first, "proven continuous: the head segment grows to 30")
        assertEquals(listOf(10L to 20L), unproven.first, "no proof: the head segment stays, 30 is loose")
        assertEquals(listOf(10L, 20L, 30L), proven.second)
        assertEquals(proven.second, unproven.second, "the fact is stored either way")
    }

    @Test
    fun aMessageUpdatedForAStoredLooseFactRewritesItButOneNamingAReplyThreadIsLeftToTheMarker() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            assertTrue(rig.deliver(rig.messageNew(other, "m5", 5L), epoch = 1L))
            val firstEdit = rig.messageUpdated(other, "m5", 5L, content = "first")
            assertTrue(rig.deliver(firstEdit, epoch = 1L))
            assertTrue(rig.deliver(rig.messageUpdated(other, "m5", 5L, content = "edited"), epoch = 1L))
            // The first edit delivered again through another entry never rolls the fact back.
            assertTrue(rig.deliver(firstEdit, epoch = 1L))
            assertTrue(rig.deliver(rig.messageUpdated(other, "m5", 5L, content = "thread", threadId = "thread-9"), epoch = 1L))
            // A redelivered older `New` never overwrites the newer stored fact.
            assertTrue(rig.deliver(rig.messageNew(other, "m5", 5L), epoch = 1L))
            assertTrue(rig.logs.any { "outcome=skipped" in it && "reason=updated_thread" in it }, rig.logs.toString())
        }
        file.rig().session { restarted ->
            assertEquals(listOf("edited"), assertNotNull(restarted.coldRead(other)).envelopes.map { it.fact.content })
        }
    }

    // ---- fail-open never costs a claim ----------------------------------------------------------------

    @Test
    fun aCommitStuckBehindTheScopeWriterIsDisplayedOnTimeAndStillLandsInOrder() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            val release = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            val holder = CoroutineScope(Dispatchers.IO).launch {
                rig.engine.withScopeWriter(channel) {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            // Each frame waits past the owner's timeout and is displayed; its commit stays queued, never cancelled.
            assertTrue(rig.deliver(rig.messageNew(channel, "m30", 30L), epoch = 1L))
            assertTrue(rig.deliver(rig.messageNew(channel, "m40", 40L), epoch = 1L))
            assertEquals(listOf("m30", "m40"), rig.projectedIds())
            assertEquals(2, rig.logs.count { "outcome=display_only" in it && "reason=timeout" in it }, rig.logs.toString())
            release.complete(Unit)
            holder.join()
            rig.ingest.awaitIdle()
            assertEquals(listOf(10L to 40L), rig.segments(channel), "both landed, in order, as a continuous run")
        }
    }

    @Test
    fun aFrameSkippedByAFullQueueEndsTheContinuitySoNoClaimCoversIt() = withFile { file ->
        file.rig(queueCapacity = 1).session { rig ->
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            val release = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            val holder = CoroutineScope(Dispatchers.IO).launch {
                rig.engine.withScopeWriter(channel) {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            assertTrue(rig.deliver(rig.messageNew(channel, "m30", 30L), epoch = 1L)) // on the worker, stuck
            assertTrue(rig.deliver(rig.messageNew(channel, "m40", 40L), epoch = 1L)) // queued
            assertTrue(rig.deliver(rig.messageNew(channel, "m50", 50L), epoch = 1L)) // queue full: skipped
            assertEquals(listOf("m30", "m40", "m50"), rig.projectedIds())
            release.complete(Unit)
            holder.join()
            rig.ingest.awaitIdle()
            // Same connection, no gap the canary could see by itself: only the skip report keeps 60 from claiming 50.
            assertTrue(rig.deliver(rig.messageNew(channel, "m60", 60L), epoch = 1L))
            rig.ingest.awaitIdle()
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channel))
            val segments = rig.segments(channel)
            assertTrue(segments.none { (low, high) -> 50L in low..high }, "a claim covers the skipped 50: $segments")
            assertEquals(listOf(10L, 20L, 30L, 40L, 60L), assertNotNull(rig.coldRead(channel)).seqs())
        }
    }

    @Test
    fun twoHundredRapidFramesNeverTouchTheDiskWhileTheGrantIsHeld() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            val started = System.nanoTime()
            repeat(200) { i ->
                // Half continuous on the entered channel, half loose across five never-entered channels.
                val scope = if (i % 2 == 0) channel else SyncScopeKey(partition, "channel-x${i % 5}", "channel")
                assertTrue(rig.deliver(rig.messageNew(scope, "r$i", 100L + i, omit = PRODUCTION_NEW_OMITS), epoch = 1L))
            }
            rig.ingest.awaitIdle()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("canonical_v2_realtime bench frames=200 elapsedMs=$elapsedMs")
            assertEquals(0, rig.storeCallsUnderGrant.get(), "store work ran while the authority registry was held")
            assertEquals(200, rig.projected.size)
            assertEquals(102, assertNotNull(rig.coldRead(channel)).seqs().size)
            assertEquals(listOf(10L to 298L), rig.segments(channel))
        }
    }

    // ---- hardening (follow-up to #3857) -----------------------------------------------------------------

    @Test
    fun aThrowingLogSinkNeverStopsStorageNorDelaysDisplay() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            rig.throwingLogger = true
            listOf(5L, 6L, 7L).forEach { seq -> assertTrue(rig.deliver(rig.messageNew(other, "m$seq", seq), epoch = 1L)) }
            rig.ingest.awaitIdle()
            assertEquals(listOf(5L, 6L, 7L), assertNotNull(rig.coldRead(other)).seqs())
            assertTrue(rig.logs.none { "outcome=display_only" in it }, "display waited out the timeout: ${rig.logs}")
        }
    }

    @Test
    fun aQueueFullSkipIsReportedBeforeTheNextFrameIsTrackedWithoutWaitingForIdle() = withFile { file ->
        file.rig(queueCapacity = 1).session { rig ->
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            rig.slowUnrecordedMs = 600L
            val release = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            val holder = CoroutineScope(Dispatchers.IO).launch {
                rig.engine.withScopeWriter(channel) {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            assertTrue(rig.deliver(rig.messageNew(channel, "m30", 30L), epoch = 1L)) // on the worker, stuck
            assertTrue(rig.deliver(rig.messageNew(channel, "m40", 40L), epoch = 1L)) // queued
            assertTrue(rig.deliver(rig.messageNew(channel, "m50", 50L), epoch = 1L)) // skipped
            release.complete(Unit)
            holder.join()
            // Straight on, no idle wait: the next frames must already see the skip.
            assertTrue(rig.deliver(rig.messageNew(channel, "m60", 60L), epoch = 1L))
            assertTrue(rig.deliver(rig.messageNew(channel, "m70", 70L), epoch = 1L))
            rig.ingest.awaitIdle()
            val segments = rig.segments(channel)
            assertTrue(segments.none { (low, high) -> 50L in low..high }, "a claim covers the skipped 50: $segments")
        }
    }

    @Test
    fun withTheFlagOffAFrameSkipsTheQueueAndEndsTheWindowsContinuity() = withFile { file ->
        file.rig().session { rig ->
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            rig.enabled = false
            assertTrue(rig.deliver(rig.messageNew(channel, "m30", 30L), epoch = 1L))
            rig.ingest.awaitIdle()
            assertTrue(rig.logs.none { "canonical_v2_realtime" in it && "msg=m30" in it }, rig.logs.toString())
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channel))
            // Back on: the frames missed meanwhile are never claimed over.
            rig.enabled = true
            assertTrue(rig.deliver(rig.messageNew(channel, "m40", 40L), epoch = 1L))
            assertEquals(listOf(10L to 20L), rig.segments(channel))
            assertEquals(listOf(10L, 20L, 40L), assertNotNull(rig.coldRead(channel)).seqs())
        }
    }

    @Test
    fun theT4RouteAlsoCommitsOffTheGrantAndBeforeDisplay() = withFile { file ->
        file.rig(t4 = true).session { rig ->
            rig.connect(1L)
            listOf(5L, 6L, 7L).forEach { seq -> assertTrue(rig.deliver(rig.messageNew(other, "m$seq", seq), epoch = 1L)) }
            assertEquals(0, rig.storeCallsUnderGrant.get(), "T4 route did disk work under the grant")
            assertEquals(listOf("m5", "m6", "m7"), rig.projectedIds())
            assertEquals(listOf(5L, 6L, 7L), assertNotNull(rig.coldRead(other)).seqs())
        }
    }

    // ---- retention -----------------------------------------------------------------------------------

    @Test
    fun anOpenedReplyThreadKeepsItsRealtimeOnlyParentFromBeingEvictedFirst() = withFile { file ->
        file.rig(partitionRetentionEvery = 1, retention = CanonicalMessageV2RetentionPolicy(maxScopesPerPartition = 1)).session { rig ->
            rig.connect(1L)
            // A reply of thread-1 (parent channel-1) arrives; the user opens that thread.
            assertTrue(rig.deliver(rig.replyNew(thread, "r7", 7L), epoch = 1L))
            rig.database.queries.touchSyncCursorUserAccess(
                atMs = 5_000L,
                environmentId = thread.environmentId,
                serverId = thread.serverId,
                principalId = thread.principalId,
                consumerId = thread.consumerId,
                scopeKind = thread.scopeKind,
                scopeId = thread.scopeId,
            )
            // Realtime-only headers: the thread's parent, then a newer unrelated channel. One channel fits.
            assertTrue(rig.deliver(rig.messageNew(channel, "m5", 5L), epoch = 1L))
            assertTrue(rig.deliver(rig.messageNew(other, "m6", 6L), epoch = 1L))
        }
        file.rig().session { restarted ->
            assertNotNull(restarted.coldReadReply(thread, ThreadDurableReplyParent("channel-1", "root-1")), "opened thread evicted")
            assertNotNull(restarted.coldRead(channel))
            assertNull(restarted.coldRead(other))
        }
    }

    // ---- rig -----------------------------------------------------------------------------------------

    private class TestFile(val path: Path) {
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        private var created = false

        fun rig(
            queueCapacity: Int = CANONICAL_MESSAGE_V2_REALTIME_QUEUE_CAPACITY,
            partitionRetentionEvery: Int = CANONICAL_MESSAGE_V2_REALTIME_PARTITION_RETENTION_EVERY,
            retention: CanonicalMessageV2RetentionPolicy = CanonicalMessageV2RetentionPolicy(),
            t4: Boolean = false,
        ): Rig {
            val driver = JdbcSqliteDriver(url)
            if (!created) {
                RaftSqlDatabase.Schema.create(driver).value
                created = true
            }
            return Rig(driver, queueCapacity, partitionRetentionEvery, retention, t4)
        }
    }

    private class Rig(
        private val driver: JdbcSqliteDriver,
        queueCapacity: Int,
        partitionRetentionEvery: Int,
        retention: CanonicalMessageV2RetentionPolicy,
        t4: Boolean,
    ) : AutoCloseable {
        /** The `sync_core_messages` flag for the server (canary and ingest read it). */
        @Volatile var enabled = true

        /** The ingest's log sink throws (a broken platform logger). */
        @Volatile var throwingLogger = false

        /** The tracker's skipped-frame report is slow (it races the worker's next frame in a racy implementation). */
        @Volatile var slowUnrecordedMs = 0L
        val database = RaftSqlDatabase(driver)
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var killBeforeCommit = false
        private val clock = java.util.concurrent.atomic.AtomicLong(1_000L)

        /** True while the fake authority registry runs a grant-checked block (its mutex is held). */
        @Volatile var holdingGrant = false

        /** Store writes / reads that ran while the registry was held: must stay 0 (Phase 3a: no disk under the grant). */
        val storeCallsUnderGrant = java.util.concurrent.atomic.AtomicInteger(0)
        val store = GrantGuardStore(
            SqlDelightCanonicalMessageV2Store(
                database,
                now = { clock.incrementAndGet() },
                faultHook = PersistFaultHook { point -> point == PersistFaultPoint.BeforeCommit && killBeforeCommit },
                retention = retention,
            ),
        ) { if (holdingGrant) storeCallsUnderGrant.incrementAndGet() }
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { enabled }, generationEngine = engine, logger = { logs += it })
        private val tracker = object : CanonicalMessageV2LiveCoverageTracker {
            override suspend fun accept(
                partition: DurablePartitionKey,
                foldInput: CanonicalMessageFoldInput,
                frameTransportEpoch: Long?,
            ) = canary.accept(partition, foldInput, frameTransportEpoch)

            override suspend fun noteFrameUnrecorded(partition: DurablePartitionKey, foldInput: CanonicalMessageFoldInput) {
                if (slowUnrecordedMs > 0) kotlinx.coroutines.delay(slowUnrecordedMs)
                canary.noteFrameUnrecorded(partition, foldInput)
            }
        }
        val ingest = CanonicalMessageV2RealtimeIngest(
            tracker = tracker,
            generationEngine = engine,
            isEnabledForServer = { enabled },
            logger = {
                if (throwingLogger) throw IllegalStateException("log sink down")
                logs += it
            },
            dispatcher = Dispatchers.IO,
            queueCapacity = queueCapacity,
            partitionRetentionEvery = partitionRetentionEvery,
        )
        val grant = canonicalV2TestGrant()

        /** What the authority registry holds now; a frame of any other grant never runs (sign-out, access lost). */
        @Volatile var currentGrant: RealtimeGrantRecord? = grant
        val projected: MutableList<RealtimeEvent> = java.util.Collections.synchronizedList(mutableListOf())
        private val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { captured, block ->
                if (captured == currentGrant) {
                    holdingGrant = true
                    try { block() } finally { holdingGrant = false }
                    true
                } else {
                    false
                }
            },
            runWithCurrentMessageGrant = { captured, block ->
                if (captured == currentGrant) {
                    holdingGrant = true
                    try {
                        block(CurrentMessageCommitContext(captured.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)))
                    } finally {
                        holdingGrant = false
                    }
                    true
                } else {
                    false
                }
            },
            canonicalFinalOwnerGateway = if (!t4) null else RealtimeCanonicalFinalOwnerGateway { _, captured, _, _, _, _, _, legacyWithCurrentT3, _ ->
                // The dormant T4 route (flag: T3 only): its own grant check around the legacy display.
                if (captured != currentGrant) return@RealtimeCanonicalFinalOwnerGateway false
                holdingGrant = true
                try {
                    legacyWithCurrentT3(CurrentMessageCommitContext(captured.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)))
                } finally {
                    holdingGrant = false
                }
                true
            },
            canonicalMessageV2CommitTimeoutMs = 300L,
        ).also { owner ->
            owner.installCanonicalMessageV2OwnerGateway(RealtimeIngestCanonicalMessageV2OwnerGateway(ingest, canary))
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, event -> projected += event },
                    log = { logs += it },
                ),
            )
        }
        private val mapper = RealtimeEventMapper(
            messageShadowObserver = MessageShadowObserver { eventName, raw, _ ->
                val face = if (eventName == RealtimeSocketEvents.MessageUpdated) ProductionFace.EnrichedUpdated else ProductionFace.MessageNew
                decodeCanonicalMessageWire(raw, face)
            },
        )

        fun projectedIds(): List<String> = projected.map {
            when (it) {
                is MessageNew -> it.message.id.value
                is MessageUpdated -> it.message.id.value
                else -> it.toString()
            }
        }

        suspend fun connect(epoch: Long, roomsJoined: Boolean = true) {
            canary.noteTransportEpoch(partition, epoch)
            if (roomsJoined) canary.noteRoomsJoined(partition, epoch)
        }

        /** The user opens [scope]: the real enter loader against a server answering [seqs]. */
        suspend fun enter(scope: SyncScopeKey, vararg seqs: Long) {
            val page = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it, channelId = scope.scopeId) }.toTypedArray())
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(
                    RecordingNetworkClient("""{"messages":$page,"historyLimited":false}""", channelIds = setOf(scope.scopeId)),
                ),
                isEnabledForServer = { true },
                logger = { logs += it },
            ).loadOnEnter(partition, scope.scopeId) { true }
        }

        suspend fun deliver(event: RealtimeEvent, epoch: Long?): Boolean =
            owner.accept(grant, event, source = "test", transportEpoch = epoch)

        private fun coldReader() = CanonicalMessageV2DurableLastKnownReader(
            SqlDelightCanonicalMessageV2Store(database, now = { 2_000L }),
            isEnabledForServer = { true },
        )

        suspend fun coldRead(scope: SyncScopeKey): ThreadDurableLastKnownFrame? = coldReader().read(partition, scope.scopeId)

        suspend fun coldReadReply(scope: SyncScopeKey, parent: ThreadDurableReplyParent): ThreadDurableLastKnownFrame? =
            coldReader().readReplyThread(partition, scope.scopeId, parent)

        suspend fun segments(scope: SyncScopeKey): List<Pair<Long, Long>> =
            SqlDelightCanonicalMessageV2Store(database, now = { 2_000L }).readSegments(scope).map { it.lowSeq to it.highSeq }

        fun messageNew(scope: SyncScopeKey, id: String, seq: Long, omit: Set<String> = emptySet()): MessageNew =
            assertIs<MessageNew>(mapper.map(RealtimeSocketEvents.MessageNew, wire(scope, id, seq, "body-$id", omit = omit)))
                .also { requireNotNull(it.decodedCanonical) { "the wire frame must decode canonically" } }

        fun replyNew(scope: SyncScopeKey, id: String, seq: Long, omit: Set<String> = emptySet()): MessageNew {
            val context = """{ "channelType": "thread", "parentChannelId": "channel-1", """ +
                """"parentChannelType": "channel", "parentMessageId": "root-1" }"""
            return assertIs<MessageNew>(
                mapper.map(RealtimeSocketEvents.MessageNew, wire(scope, id, seq, "body-$id", conversationContext = context, omit = omit)),
            ).also { requireNotNull(it.decodedCanonical) { "the wire frame must decode canonically" } }
        }

        fun messageUpdated(scope: SyncScopeKey, id: String, seq: Long, content: String, threadId: String? = null): MessageUpdated =
            assertIs<MessageUpdated>(mapper.map(RealtimeSocketEvents.MessageUpdated, wire(scope, id, seq, content, threadId = threadId)))
                .also { requireNotNull(it.decodedCanonical) { "the wire frame must decode canonically" } }

        private fun wire(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            content: String,
            threadId: String? = null,
            conversationContext: String = """{ "channelType": "${scope.scopeKind}" }""",
            omit: Set<String> = emptySet(),
        ): String {
            val thread = threadId?.let { "\"$it\"" } ?: "null"
            fun key(name: String, value: String) = if (name in omit) "" else "\"$name\": $value,"
            return """
                {
                  "id": "$id", "seq": $seq, "channelId": "${scope.scopeId}",
                  "senderType": "user", "senderId": "user-1", "randomId": null,
                  "messageType": "chat", "content": "$content", "actionMetadata": null, "threadId": $thread,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
                  "senderName": "Alice", ${key("senderDescription", "null")} "senderMembershipStatus": null,
                  "mentions": [], "attachments": [], ${key("reactions", "[]")}
                  "conversationContext": $conversationContext
                }
            """.trimIndent()
        }

        fun lastUserAccessAtMs(scope: SyncScopeKey): Long? =
            database.queries.selectSyncCursor(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
            ).executeAsOne().lastUserAccessAtMs

        override fun close() {
            ingest.close()
            driver.close()
        }
    }

    /** Delegates every store call, reporting each one to [onCall] (the test asserts none runs under the grant). */
    private class GrantGuardStore(
        private val delegate: CanonicalMessageV2Store,
        private val onCall: () -> Unit,
    ) : CanonicalMessageV2Store {
        private inline fun <T> guarded(block: () -> T): T {
            onCall()
            return block()
        }

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame) = guarded { delegate.applySnapshot(frame) }
        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame) = guarded { delegate.applyIncremental(frame) }
        override suspend fun restore(scope: SyncScopeKey) = guarded { delegate.restore(scope) }
        override suspend fun clearScope(scope: SyncScopeKey) = guarded { delegate.clearScope(scope) }
        override suspend fun clearPartition(partition: DurablePartitionKey) = guarded { delegate.clearPartition(partition) }
        override suspend fun knownPartitions() = guarded { delegate.knownPartitions() }
        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId) =
            guarded { delegate.baselinedScopeKinds(partition, scopeId) }
        override suspend fun readCursor(scope: SyncScopeKey) = guarded { delegate.readCursor(scope) }
        override suspend fun containsFact(scope: SyncScopeKey, factId: String) = guarded { delegate.containsFact(scope, factId) }
        override suspend fun readSegments(scope: SyncScopeKey) = guarded { delegate.readSegments(scope) }
        override suspend fun maxLocalRev(scope: SyncScopeKey) = guarded { delegate.maxLocalRev(scope) }
        override suspend fun applyCoverage(
            scope: SyncScopeKey,
            envelopes: List<CanonicalMessageEnvelopeV2>,
            coverage: CanonicalMessageV2Coverage?,
            revAtRequest: Long,
            userDriven: Boolean,
            threadParent: CanonicalMessageV2ThreadParent?,
            insertOnly: Boolean,
            unknownFieldsByFactId: Map<String, Set<MessageAggregateField>>,
            runPartitionRetention: Boolean,
        ) = guarded {
            delegate.applyCoverage(
                scope, envelopes, coverage, revAtRequest, userDriven, threadParent, insertOnly, unknownFieldsByFactId,
                runPartitionRetention,
            )
        }
    }

    private fun ThreadDurableLastKnownFrame.seqs(): List<Long> = envelopes.map { it.fact.seq }.sorted()

    private fun <T> Rig.session(block: suspend (Rig) -> T): T = try {
        runBlocking { block(this@session).also { ingest.awaitIdle() } }
    } finally {
        close()
    }

    private fun <T> withFile(block: (TestFile) -> T): T {
        val path = Files.createTempFile("slock-realtime-ingest-", ".db").also { it.deleteIfExists() }
        return try {
            block(TestFile(path))
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val channel = SyncScopeKey(partition, "channel-1", "channel")
        val other = SyncScopeKey(partition, "channel-9", "channel")
        val thread = SyncScopeKey(partition, "thread-1", "thread")

        /** The keys botiverse/slock's ordinary `message:new` leaves out (task #14a). */
        val PRODUCTION_NEW_OMITS = setOf("senderDescription", "reactions")
    }
}
