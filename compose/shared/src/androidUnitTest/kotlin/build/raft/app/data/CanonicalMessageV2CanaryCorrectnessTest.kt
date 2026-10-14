package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #168 step 3 on the real SQLite store, driven by real `message:new` / `message:updated` wire JSON
 * through [RealtimeEventMapper]: realtime messages are written to the offline cache of EVERY channel that has
 * a window committed in this process (the enter snapshot is the only baseline); a scope without one, and a
 * discontinuous one, is display only and never requests the network. Pairs share seqs and epochs and differ
 * only in the signal under test.
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
    private val channelA = SyncScopeKey(partition, "channel-a", "channel")
    private val channelB = SyncScopeKey(partition, "channel-b", "channel")
    private val channelC = SyncScopeKey(partition, "channel-c", "channel")

    // ---- T1 every windowed channel ------------------------------------------------------------------

    @Test
    fun t1TwoWindowedChannelsBothGetRealtimeAppends() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "a1" to 1L)
            rig.server.has(channelB, "b1" to 1L)
            rig.enter(channelA)
            rig.enter(channelB)
            val loads = rig.server.pageLoads()

            assertTrue(rig.deliver(rig.messageNew(channelA, "a2", 2L)))
            assertTrue(rig.deliver(rig.messageNew(channelB, "b2", 2L)))
            // Twin: a channel never opened gets the same frame and stays off disk.
            assertTrue(rig.deliver(rig.messageNew(channelC, "c2", 2L)))

            assertEquals(setOf("a1", "a2"), rig.storedIds(channelA))
            assertEquals(setOf("b1", "b2"), rig.storedIds(channelB))
            assertEquals(emptySet(), rig.storedIds(channelC))
            assertEquals(2, rig.store.incrementalWrites)
            assertEquals(loads, rig.server.pageLoads(), "a realtime append never loads")
        }
    }

    // ---- T2 no window: display only ----------------------------------------------------------------

    @Test
    fun t2AScopeWithoutAWindowIsDisplayedButNeverLoadsOrWrites() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            val first = rig.messageNew(channelC, "c1", 1L)
            val second = rig.messageNew(channelC, "c2", 2L)
            assertTrue(rig.deliver(first))
            assertTrue(rig.deliver(second))

            assertEquals(listOf<RealtimeEvent>(first, second), rig.projected, "displayed")
            assertEquals(emptyList(), rig.server.paths, "no request at all")
            assertEquals(0, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, rig.canary.continuityOf(channelC))

            // Twin: the same channel once opened appends the same kind of frame.
            rig.enter(channelC)
            assertTrue(rig.deliver(rig.messageNew(channelC, "c3", 3L)))
            assertEquals(setOf("c1", "c2", "c3"), rig.storedIds(channelC))
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    // ---- T3 hole A: a frame during the enter fetch survives the enter commit -------------------------

    @Test
    fun t3AFrameDuringTheEnterFetchSurvivesTheEnterCommit() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L)
            rig.enter(channelA)

            // The user re-opens the channel: the page is read (m10) and the response is held.
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            val m11 = rig.messageNew(channelA, "m11", 11L)
            assertTrue(rig.deliver(m11))
            assertEquals(m11, rig.projected.last(), "displayed while the page loads")
            hold.release.complete(Unit)
            entering.await()

            assertEquals(setOf("m10", "m11"), rig.storedIds(channelA), "m11 was not wiped by the enter commit")
            assertTrue(rig.deliver(rig.messageNew(channelA, "m12", 12L)))
            assertEquals(setOf("m10", "m11", "m12"), rig.storedIds(channelA), "no hole")
            assertEquals(12L, rig.storedCursor(channelA)?.latestSeq)
        }
    }

    @Test
    fun t3TwinWithoutAnEnterInFlightTheSameFrameIsAppendedDirectly() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L)
            rig.enter(channelA)
            assertTrue(rig.deliver(rig.messageNew(channelA, "m11", 11L)))
            assertTrue(rig.deliver(rig.messageNew(channelA, "m12", 12L)))
            assertEquals(setOf("m10", "m11", "m12"), rig.storedIds(channelA))
            assertEquals(2, rig.store.incrementalWrites)
        }
    }

    @Test
    fun t3AFirstEnterAlsoKeepsTheFramesOfItsFetch() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            assertTrue(rig.deliver(rig.messageNew(channelA, "m11", 11L)))
            hold.release.complete(Unit)
            entering.await()

            assertEquals(setOf("m10", "m11"), rig.storedIds(channelA))
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
        }
    }

    @Test
    fun aBufferedFrameTheSnapshotAlreadyHoldsIsNotWrittenTwice() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            // m10 is already in the page that was read.
            assertTrue(rig.deliver(rig.messageNew(channelA, "m10", 10L)))
            hold.release.complete(Unit)
            entering.await()

            assertEquals(setOf("m10"), rig.storedIds(channelA))
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
        }
    }

    @Test
    fun aFrameBufferedDuringTheFetchThatThePageMissesBelowItsLatestMarksTheWindowDiscontinuous() =
        withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m10" to 10L, "m12" to 12L)
                val hold = rig.server.holdNextPage()
                val entering = async { rig.enter(channelA) }
                hold.read.await()
                // seq 11 is inside the page's range, yet not in it.
                rig.deliverWithoutServer(rig.messageNew(channelA, "m11", 11L))
                hold.release.complete(Unit)
                entering.await()

                assertEquals(setOf("m10", "m12"), rig.storedIds(channelA))
                assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelA))
                assertTrue(rig.logs.any { "reason=replay_missing_from_snapshot" in it }, rig.logs.toString())
            }
        }

    @Test
    fun aFullBufferIsReplayedAndAnOverflowedOneLeavesTheWindowDiscontinuous() {
        fun run(frames: Int): Pair<Set<String>, CanonicalMessageV2ScopeContinuity> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m1" to 1L)
                val hold = rig.server.holdNextPage()
                val entering = async { rig.enter(channelA) }
                hold.read.await()
                (2L..frames + 1L).forEach { seq -> rig.deliver(rig.messageNew(channelA, "m$seq", seq)) }
                hold.release.complete(Unit)
                entering.await()
                rig.storedIds(channelA) to rig.canary.continuityOf(channelA)
            }
        }
        val full = run(CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP)
        assertEquals(CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP + 1, full.first.size)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, full.second)
        val overflowed = run(CANONICAL_MESSAGE_V2_SNAPSHOT_BUFFER_CAP + 1)
        assertEquals(setOf("m1"), overflowed.first, "an overflowed buffer is dropped, never partially replayed")
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, overflowed.second)
    }

    @Test
    fun aBufferedFrameOfAnOlderEpochIsSkippedAndANewerOneMarksTheWindowDiscontinuous() {
        fun run(bufferedEpoch: Long): Pair<Set<String>, CanonicalMessageV2ScopeContinuity> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.canary.noteTransportEpoch(partition, 2L)
                rig.server.has(channelA, "m1" to 1L)
                val hold = rig.server.holdNextPage()
                val entering = async { rig.enter(channelA) }
                hold.read.await()
                rig.deliver(rig.messageNew(channelA, "m5", 5L), epoch = bufferedEpoch)
                hold.release.complete(Unit)
                entering.await()
                rig.storedIds(channelA) to rig.canary.continuityOf(channelA)
            }
        }
        // A late frame of the old socket: shown, never written, continuity unchanged.
        assertEquals(setOf("m1") to CanonicalMessageV2ScopeContinuity.Continuous, run(bufferedEpoch = 1L))
        // A frame of a newer socket: the page predates it.
        assertEquals(setOf("m1") to CanonicalMessageV2ScopeContinuity.Discontinuous, run(bufferedEpoch = 3L))
        // Positive control: the request's own epoch is appended.
        assertEquals(setOf("m1", "m5") to CanonicalMessageV2ScopeContinuity.Continuous, run(bufferedEpoch = 2L))
    }

    @Test
    fun aFailedEnterLeavesAContinuousWindowDiscontinuousWhenFramesArrivedMeanwhile() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L)
            rig.enter(channelA)
            val hold = rig.server.holdNextPage(failing = true)
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            assertTrue(rig.deliver(rig.messageNew(channelA, "m11", 11L)))
            hold.release.complete(Unit)
            entering.await()

            assertEquals(setOf("m10"), rig.storedIds(channelA))
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelA))
            assertTrue(rig.deliver(rig.messageNew(channelA, "m12", 12L)))
            assertEquals(setOf("m10"), rig.storedIds(channelA), "m12 is never appended across the lost m11")
        }
    }

    @Test
    fun aResetDuringTheEnterFetchWritesNothingAndNothingReachesTheNewSession() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m1" to 1L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            rig.deliver(rig.messageNew(channelA, "m2", 2L))
            // Logout / account change while the page loads.
            rig.engine.clearPartition(partition)
            hold.release.complete(Unit)
            entering.await()

            assertEquals(0, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, rig.canary.continuityOf(channelA))

            // New session: its own enter; the old buffer (m2) is never replayed into it.
            rig.server.has(channelA, "m1" to 1L)
            rig.server.remove(channelA, "m2")
            rig.enter(channelA)
            assertEquals(setOf("m1"), rig.storedIds(channelA))
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    // ---- T4 epoch change → discontinuous → display only, until opened ------------------------------

    @Test
    fun t4AReconnectMakesTheWindowDisplayOnlyWithoutLoadsUntilTheChannelIsOpenedAgain() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canary.noteTransportEpoch(partition, 1L)
            rig.server.has(channelA, "m1" to 1L)
            rig.enter(channelA)
            val loads = rig.server.pageLoads()

            rig.canary.noteTransportEpoch(partition, 2L)
            val m3 = rig.messageNew(channelA, "m3", 3L)
            assertTrue(rig.deliver(m3, epoch = 2L))
            assertTrue(rig.deliver(rig.messageNew(channelA, "m4", 4L), epoch = 2L))
            assertEquals(m3, rig.projected[rig.projected.size - 2], "displayed")
            assertEquals(setOf("m1"), rig.storedIds(channelA))
            assertEquals(loads, rig.server.pageLoads(), "no background re-snapshot")
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelA))

            // The user opens the channel (request under epoch 2): continuous again.
            rig.enter(channelA)
            assertEquals(setOf("m1", "m3", "m4"), rig.storedIds(channelA))
            assertTrue(rig.deliver(rig.messageNew(channelA, "m5", 5L), epoch = 2L))
            assertEquals(setOf("m1", "m3", "m4", "m5"), rig.storedIds(channelA))
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    @Test
    fun oneReconnectMakesEveryWindowedChannelDiscontinuous() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canary.noteTransportEpoch(partition, 1L)
            rig.server.has(channelA, "a1" to 1L)
            rig.server.has(channelB, "b1" to 1L)
            rig.enter(channelA)
            rig.enter(channelB)
            val loads = rig.server.pageLoads()

            rig.canary.noteTransportEpoch(partition, 2L)
            rig.deliver(rig.messageNew(channelA, "a2", 2L), epoch = 2L)
            rig.deliver(rig.messageNew(channelB, "b2", 2L), epoch = 2L)

            assertEquals(setOf("a1"), rig.storedIds(channelA))
            assertEquals(setOf("b1"), rig.storedIds(channelB))
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelA))
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelB))
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(loads, rig.server.pageLoads())
        }
    }

    // ---- T5 room leave -------------------------------------------------------------------------------

    @Test
    fun t5ALeftRoomsNextFrameIsNotWritten() {
        fun run(leaveAllowed: Boolean): Set<String> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m1" to 1L)
                rig.enter(channelA)
                val rooms = SharedRealtimeRoomLeaseRegistry(
                    shellInstanceId = "shell-1",
                    leaveAllowed = { _, _ -> leaveAllowed },
                    onLeave = rig.canary::noteRoomLeft,
                )
                val owner = RealtimeRoomLeaseOwner("shell-1", "server-1", "thread-page", generation = 1L)
                rooms.acquire(owner, listOf("channel-a"))
                val transitions = rooms.release(owner)
                assertEquals(leaveAllowed, transitions.any { it.action == RealtimeRoomTransitionAction.Leave })

                rig.deliver(rig.messageNew(channelA, "m2", 2L))
                rig.storedIds(channelA)
            }
        }
        // A non-member room is left on page close: the socket no longer receives it.
        assertEquals(setOf("m1"), run(leaveAllowed = true))
        // Twin: a member room is never left (#2994): the next frame is appended.
        assertEquals(setOf("m1", "m2"), run(leaveAllowed = false))
    }

    // ---- non-member channels: the server joins the room asynchronously on open -----------------------

    @Test
    fun aMemberChannelsEnterIsContinuousAndANonMemberChannelsEnterStaysDisplayOnly() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.nonMembers += "channel-b"
            rig.server.has(channelA, "a1" to 1L)
            rig.server.has(channelB, "b1" to 1L)
            rig.enter(channelA)
            rig.enter(channelB)

            rig.deliver(rig.messageNew(channelA, "a2", 2L))
            rig.deliver(rig.messageNew(channelB, "b2", 2L))

            assertEquals(setOf("a1", "a2"), rig.storedIds(channelA), "member: auto-joined at connect")
            assertEquals(setOf("b1"), rig.storedIds(channelB), "non-member: the page may predate the room join")
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelB))
            assertTrue(rig.logs.any { "scope=channel-b" in it && "reason=non_member" in it }, rig.logs.toString())
        }
    }

    @Test
    fun aNonMemberChannelsFramesThatArriveAfterTheJoinDuringTheFetchAreNotReplayed() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            // join:channel is sent on open; the page is read before the server has joined the room, and m2
            // (pushed once the join took effect) arrives while the page is held.
            rig.nonMembers += "channel-b"
            rig.server.has(channelB, "b1" to 1L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelB) }
            hold.read.await()
            val pushed = rig.messageNew(channelB, "b2", 2L)
            assertTrue(rig.deliver(pushed))
            hold.release.complete(Unit)
            entering.await()

            assertEquals(pushed, rig.projected.last(), "displayed")
            assertEquals(setOf("b1"), rig.storedIds(channelB))
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelB))
        }
    }

    // ---- T6 message:updated -------------------------------------------------------------------------

    @Test
    fun t6AFullUpdateOfAWindowFactWithoutAThreadReplacesIt() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited") },
        expectedContent = "edited",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Continuous,
        expectedIncrementals = 1,
    )

    @Test
    fun t6AnUpdateNamingAThreadMarksTheWindowDiscontinuous() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited", threadId = "thread-6") },
        expectedContent = "body-m6",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Discontinuous,
    )

    @Test
    fun t6APartialUpdateMarksTheWindowDiscontinuous() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited", partial = true) },
        expectedContent = "body-m6",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Discontinuous,
    )

    @Test
    fun t6AnUpdateOfAFactMissingInsideTheWindowRangeMarksTheWindowDiscontinuous() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "x7", 7L, content = "edited") },
        expectedContent = "body-m6",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Discontinuous,
    )

    @Test
    fun t6AnUpdateBelowTheWindowIsIgnored() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m3", 3L, content = "edited") },
        expectedContent = "body-m6",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Continuous,
    )

    /** Window m5..m9 (oldest 5); one `message:updated`; then m10 shows whether the window still appends. */
    private fun updateCase(
        update: (Rig) -> MessageUpdated,
        expectedContent: String,
        expectedContinuity: CanonicalMessageV2ScopeContinuity,
        expectedIncrementals: Int = 0,
    ) = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, *(5L..9L).map { "m$it" to it }.toTypedArray())
            rig.enter(channelA)
            val event = update(rig)
            assertTrue(rig.deliver(event))
            assertEquals(event, rig.projected.last(), "displayed in every row")

            assertEquals(expectedContent, rig.storedContents(channelA).getValue("m6"))
            assertFalse("x7" in rig.storedIds(channelA) || "m3" in rig.storedIds(channelA), "never inserted")
            assertEquals(expectedContinuity, rig.canary.continuityOf(channelA))
            assertEquals(expectedIncrementals, rig.store.incrementalWrites)
            assertEquals(5L, rig.storedCursor(channelA)?.oldestLoadedSeq, "the cursor never moves for an update")

            rig.deliver(rig.messageNew(channelA, "m10", 10L))
            assertEquals(expectedContinuity == CanonicalMessageV2ScopeContinuity.Continuous, "m10" in rig.storedIds(channelA))
        }
    }

    // ---- write failure -------------------------------------------------------------------------------

    @Test
    fun aFailedRealtimeWriteStillDisplaysAndMakesTheWindowDiscontinuous() {
        fun run(fail: Boolean): Triple<Set<String>, CanonicalMessageV2ScopeContinuity, Int> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m1" to 1L)
                rig.enter(channelA)
                rig.store.failIncrementals = fail
                val m2 = rig.messageNew(channelA, "m2", 2L)
                assertTrue(rig.deliver(m2))
                assertEquals(m2, rig.projected.last(), "a failed write never hides a message")
                rig.store.failIncrementals = false
                rig.deliver(rig.messageNew(channelA, "m3", 3L))
                Triple(rig.storedIds(channelA), rig.canary.continuityOf(channelA), rig.projected.size)
            }
        }
        val failed = run(fail = true)
        assertEquals(setOf("m1"), failed.first, "m3 is never appended across the lost m2")
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, failed.second)
        assertEquals(2, failed.third)
        assertEquals(Triple(setOf("m1", "m2", "m3"), CanonicalMessageV2ScopeContinuity.Continuous, 2), run(fail = false))
    }

    // ---- T7 retention: ranking = last user-driven write ----------------------------------------------

    @Test
    fun t7ARealtimeWriteLeavesTheScopesUpdatedAtUnchanged() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.clock = 10L
            rig.server.has(channelA, "m1" to 1L)
            rig.enter(channelA)
            assertEquals(10L, rig.storedUpdatedAtMs(channelA))

            rig.clock = 50L
            rig.deliver(rig.messageNew(channelA, "m2", 2L))
            assertEquals(setOf("m1", "m2"), rig.storedIds(channelA))
            assertEquals(10L, rig.storedUpdatedAtMs(channelA), "realtime writes keep the rank")

            // Twin: a user-driven write at the same instant refreshes it.
            rig.enter(channelA)
            assertEquals(50L, rig.storedUpdatedAtMs(channelA))
        }
    }

    @Test
    fun t7AViewedQuietChannelSurvivesABusyUnviewedOneAtTheScopeCap() {
        /** Cap 2: B (busy) opened at 10, Q (quiet) at 20, B gets realtime traffic, then C is opened at 40. */
        fun run(reopenBusyAt30: Boolean): Pair<Set<String>, Set<String>> = withFreshDatabase { database ->
            val rig = Rig(database, retention = CanonicalMessageV2RetentionPolicy(maxScopesPerPartition = 2))
            bounded {
                rig.server.has(channelB, "b1" to 1L)
                rig.server.has(channelA, "q1" to 1L)
                rig.server.has(channelC, "c1" to 1L)
                rig.clock = 10L
                rig.enter(channelB)
                rig.clock = 20L
                rig.enter(channelA)
                rig.clock = 30L
                (2L..20L).forEach { seq -> rig.deliver(rig.messageNew(channelB, "b$seq", seq)) }
                if (reopenBusyAt30) rig.enter(channelB)
                rig.clock = 40L
                rig.enter(channelC)
                rig.storedIds(channelA) to rig.storedIds(channelB)
            }
        }
        val (quiet, busy) = run(reopenBusyAt30 = false)
        assertEquals(setOf("q1"), quiet, "the recently viewed quiet channel is kept")
        assertEquals(emptySet(), busy, "the busy channel nobody opened since is evicted")
        // Twin: the same traffic, but the user opened the busy channel again.
        val (quietTwin, busyTwin) = run(reopenBusyAt30 = true)
        assertEquals(emptySet(), quietTwin)
        assertEquals(20, busyTwin.size)
    }

    // ---- T8 one write per frame ------------------------------------------------------------------------

    @Test
    fun t8OneRealtimeFrameThroughEveryEntryIsWrittenOnce() = withFreshDatabase { database ->
        val rig = Rig(database)
        val processOwner = rig.owner()
        val shellOwner = rig.owner()
        bounded {
            // Android: AppShell → process owner, then the shell owner. iOS / OHOS additionally the process
            // ingress coordinator → process owner.
            suspend fun deliverEverywhere(event: RealtimeEvent) {
                rig.recordOnServer(event)
                processOwner.acceptProcessMutation(grant, event, "process_normal_realtime", presenceTransportEpoch = 3L)
                processOwner.acceptProcessMutation(grant, event, "process_realtime", presenceTransportEpoch = 3L)
                shellOwner.accept(grant, event, source = "app_shell", transportEpoch = 3L)
            }
            rig.canary.noteTransportEpoch(partition, 3L)
            rig.server.has(channelA, "m1" to 1L)
            rig.server.has(channelB, "n1" to 1L)
            rig.enter(channelA)
            rig.enter(channelB)

            deliverEverywhere(rig.messageNew(channelA, "m2", 2L))
            deliverEverywhere(rig.messageNew(channelB, "n2", 2L))
            assertEquals(2, rig.store.incrementalWrites, "one write per frame, per channel")

            // While an enter loads, the frame is buffered once and replayed once.
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            deliverEverywhere(rig.messageNew(channelA, "m3", 3L))
            hold.release.complete(Unit)
            entering.await()
            assertEquals(3, rig.store.incrementalWrites)
            assertEquals(setOf("m1", "m2", "m3"), rig.storedIds(channelA))

            // A late fourth delivery after the replay is still that frame again.
            shellOwner.accept(grant, rig.messageNew(channelA, "m3", 3L), source = "app_shell", transportEpoch = 3L)
            assertEquals(3, rig.store.incrementalWrites)
        }
    }

    @Test
    fun t8TwinTwoFramesThroughOneEntryAreWrittenTwice() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m1" to 1L)
            rig.enter(channelA)
            rig.deliver(rig.messageNew(channelA, "m2", 2L))
            rig.deliver(rig.messageNew(channelA, "m3", 3L))
            assertEquals(2, rig.store.incrementalWrites)
            // Two distinct edits of one message are two frames; the same edit again is that frame again.
            rig.deliver(rig.messageUpdated(channelA, "m1", 1L, content = "edit-1"))
            rig.deliver(rig.messageUpdated(channelA, "m1", 1L, content = "edit-2"))
            rig.deliver(rig.messageUpdated(channelA, "m1", 1L, content = "edit-2"))
            assertEquals(4, rig.store.incrementalWrites)
            assertEquals("edit-2", rig.storedContents(channelA).getValue("m1"))
        }
    }

    // ---- T9 cold start --------------------------------------------------------------------------------

    @Test
    fun t9AWindowRestoredFromDiskIsDisplayOnlyUntilTheChannelIsOpened() = withFreshDatabase { database ->
        bounded {
            val before = Rig(database)
            before.server.has(channelA, "m1" to 1L)
            before.enter(channelA)
            before.deliver(before.messageNew(channelA, "m2", 2L))
            assertEquals(setOf("m1", "m2"), before.storedIds(channelA))

            // New process over the same database: m3 may have been missed while it was dead.
            val after = Rig(database)
            after.server.has(channelA, "m1" to 1L, "m2" to 2L, "m3" to 3L)
            val m4 = after.messageNew(channelA, "m4", 4L)
            assertTrue(after.deliver(m4))
            assertEquals(listOf<RealtimeEvent>(m4), after.projected)
            assertEquals(setOf("m1", "m2"), after.storedIds(channelA), "never appended onto the restored window")
            assertEquals(emptyList(), after.server.paths, "and nothing was requested")
            assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, after.canary.continuityOf(channelA))

            after.enter(channelA)
            assertTrue(after.deliver(after.messageNew(channelA, "m5", 5L)))
            assertEquals(setOf("m1", "m2", "m3", "m4", "m5"), after.storedIds(channelA))
        }
    }

    // ---- #3002 rules kept on every window -----------------------------------------------------------

    @Test
    fun aLateNewNeverOverwritesTheEditedFactAlreadyOnDisk() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m1" to 1L)
            rig.server.edit(channelA, "m1", "edited")
            rig.enter(channelA)
            rig.deliver(rig.messageNew(channelA, "m1", 1L))
            assertEquals(mapOf("m1" to "edited"), rig.storedContents(channelA))

            // Twin: a New for an absent fact is inserted.
            rig.deliver(rig.messageNew(channelA, "m2", 2L))
            assertEquals(mapOf("m1" to "edited", "m2" to "body-m2"), rig.storedContents(channelA))
        }
    }

    @Test
    fun aFrameBelowTheWindowIsNeverWrittenAndNeverMovesTheEdge() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L, "m20" to 20L)
            rig.enter(channelA)
            rig.deliver(rig.messageNew(channelA, "m5", 5L))
            assertEquals(setOf("m10", "m20"), rig.storedIds(channelA))
            assertEquals(10L, rig.storedCursor(channelA)?.oldestLoadedSeq)
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_canary skip=below_window") })
            // Twin: a frame inside the window is written.
            rig.deliver(rig.messageNew(channelA, "m15", 15L))
            assertEquals(setOf("m10", "m15", "m20"), rig.storedIds(channelA))
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
        }
    }

    @Test
    fun aScopeOverTheCapMovesItsOldestEdgeAndForgetsItsBeginning() {
        fun run(storedFacts: Int): PersistedCursor = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, *(1L..storedFacts.toLong()).map { "m$it" to it }.toTypedArray())
                rig.server.reachesBeginning = true
                rig.enter(channelA)
                val next = storedFacts + 1L
                rig.deliver(rig.messageNew(channelA, "m$next", next))
                assertEquals(minOf(storedFacts + 1, 500), rig.storedIds(channelA).size)
                rig.storedCursor(channelA)!!.also { assertEquals(it, rig.engine.currentCursor(channelA)) }
            }
        }
        val under = run(storedFacts = 199)
        assertEquals(1L, under.oldestLoadedSeq)
        assertEquals(true, under.hasLoadedOldest)
        val over = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                // 500 facts on disk (enter page + earlier realtime appends), then one more.
                rig.server.has(channelA, *(1L..199L).map { "m$it" to it }.toTypedArray())
                rig.server.reachesBeginning = true
                rig.enter(channelA)
                (200L..501L).forEach { seq -> rig.deliver(rig.messageNew(channelA, "m$seq", seq)) }
                rig.storedCursor(channelA)!!
            }
        }
        assertEquals(2L, over.oldestLoadedSeq, "seq 1 was pruned")
        assertEquals(false, over.hasLoadedOldest, "the window no longer reaches the beginning")
    }

    // ---- rig ------------------------------------------------------------------------------------------

    /** Every hold-based test is bounded: a regression that blocks on a held page fails instead of hanging. */
    private fun <T> bounded(block: suspend CoroutineScope.() -> T): T = runBlocking {
        withTimeout(60_000L) { block() }
    }

    private class PageHold(val failing: Boolean) {
        /** Completed once the server state was read for the page. */
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** The server: per-channel messages in the real REST node shape; reads them when a page is requested. */
    private class ServerFake : SlockNetworkClient {
        val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val nodes = mutableMapOf<String, LinkedHashMap<String, Pair<Long, String>>>()
        @Volatile private var nextHold: PageHold? = null

        /** The page is short (the channel's beginning is in it) when true. */
        @Volatile var reachesBeginning = false

        fun has(scope: SyncScopeKey, vararg messages: Pair<String, Long>) = synchronized(nodes) {
            val channel = nodes.getOrPut(scope.scopeId) { linkedMapOf() }
            messages.forEach { (id, seq) -> if (id !in channel) channel[id] = seq to "body-$id" }
        }

        fun edit(scope: SyncScopeKey, id: String, content: String) = synchronized(nodes) {
            val channel = nodes.getValue(scope.scopeId)
            channel[id] = channel.getValue(id).first to content
        }

        fun remove(scope: SyncScopeKey, id: String) = synchronized(nodes) { nodes[scope.scopeId]?.remove(id) }

        fun holdNextPage(failing: Boolean = false): PageHold = PageHold(failing).also { nextHold = it }

        fun pageLoads(): Int = paths.count { it.startsWith("messages/channel/") }

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            paths += path
            if (path.startsWith("messages/channel/")) {
                val channelId = path.removePrefix("messages/channel/")
                val body = synchronized(nodes) {
                    val channel = nodes[channelId].orEmpty()
                    val page = channel.entries.sortedByDescending { it.value.first }.take(if (reachesBeginning) 500 else 200)
                    """{"messages":[${page.joinToString(",") { (id, value) -> node(channelId, id, value.first, value.second) }}],"historyLimited":false}"""
                }
                val hold = nextHold.also { nextHold = null }
                if (hold != null) {
                    hold.read.complete(Unit)
                    hold.release.await()
                    if (hold.failing) return HomeNetworkResponse(500, null)
                }
                return HomeNetworkResponse(200, body)
            }
            if (path.endsWith("/threads")) return HomeNetworkResponse(200, "{}")
            val channelId = path.removePrefix("channels/")
            return HomeNetworkResponse(200, """{"id":"$channelId","type":"channel"}""")
        }

        private fun node(channelId: String, id: String, seq: Long, content: String): String =
            """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1",""" +
                """"content":"$content","messageType":"chat","randomId":null,"threadId":null,""" +
                """"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":null,""" +
                """"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,"actionMetadata":null}"""
    }

    private inner class Rig(
        private val database: SlockSqlDatabase,
        retention: CanonicalMessageV2RetentionPolicy = CanonicalMessageV2RetentionPolicy(),
    ) {
        @Volatile var clock = 100L
        val store = CountingStore(SqlDelightCanonicalMessageV2Store(database, now = { clock }, retention = retention))
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
            logger = { logs += it },
        )
        val server = ServerFake()
        val nonMembers: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        private val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(server),
            isEnabledForServer = { true },
            logger = { logs += it },
            isKnownNonMember = { _, channelId -> channelId in nonMembers },
        )
        private val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(canary)
        val projected: MutableList<RealtimeEvent> = java.util.Collections.synchronizedList(mutableListOf())
        private val displayingOwner = owner().also { owner ->
            owner.registerGuardedSinks(RealtimeGuardedDomainEventSinks(applyHomeShell = { _, event -> projected += event }))
        }

        private val mapper = RealtimeEventMapper(
            messageShadowObserver = MessageShadowObserver { eventName, raw, _ ->
                val face = if (eventName == RealtimeSocketEvents.MessageUpdated) ProductionFace.EnrichedUpdated else ProductionFace.MessageNew
                decodeCanonicalMessageWire(raw, face)
            },
        )

        /** The user opens the channel: the real enter snapshot loader against [server]. */
        suspend fun enter(scope: SyncScopeKey) = loader.loadOnEnter(partition, scope.scopeId) { true }

        /** The server persisted the message before pushing it; then one delivery through the displaying owner. */
        suspend fun deliver(event: RealtimeEvent, epoch: Long? = null): Boolean {
            recordOnServer(event)
            return deliverWithoutServer(event, epoch)
        }

        suspend fun deliverWithoutServer(event: RealtimeEvent, epoch: Long? = null): Boolean =
            displayingOwner.accept(grant, event, source = "test", transportEpoch = epoch)

        fun recordOnServer(event: RealtimeEvent) {
            val (message, scope) = when (event) {
                is MessageNew -> event.decodedCanonical!!.row to SyncScopeKey(partition, event.decodedCanonical!!.row.channelId, "channel")
                is MessageUpdated -> event.decodedCanonical!!.row to SyncScopeKey(partition, event.decodedCanonical!!.row.channelId, "channel")
                else -> return
            }
            server.has(scope, message.id to message.seq)
            if (event is MessageUpdated) server.edit(scope, message.id, message.content)
        }

        fun owner(): SharedRealtimeDomainEventOwner =
            SharedRealtimeDomainEventOwner(
                runIfCurrentGrant = { _, block ->
                    block()
                    true
                },
                runWithCurrentMessageGrant = { _, block ->
                    block(CurrentMessageCommitContext(partition))
                    true
                },
            ).also { it.installCanonicalMessageV2OwnerGateway(gateway) }

        /** Real `message:new` wire JSON (server `projectMessageSocketPayload` + rich aggregates) via the mapper. */
        fun messageNew(scope: SyncScopeKey, id: String, seq: Long): MessageNew {
            val event = mapper.map(RealtimeSocketEvents.MessageNew, wire(scope, id, seq, "body-$id", threadId = null, partial = false))
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        /** Real `message:updated` wire JSON; [partial] drops `reactions` (a content-only update). */
        fun messageUpdated(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            content: String,
            threadId: String? = null,
            partial: Boolean = false,
        ): MessageUpdated {
            val event = mapper.map(RealtimeSocketEvents.MessageUpdated, wire(scope, id, seq, content, threadId, partial))
            val updated = assertIs<MessageUpdated>(event)
            requireNotNull(updated.decodedCanonical) { "the wire frame must decode canonically" }
            return updated
        }

        private fun wire(scope: SyncScopeKey, id: String, seq: Long, content: String, threadId: String?, partial: Boolean): String {
            val thread = threadId?.let { "\"$it\"" } ?: "null"
            val reactions = if (partial) "" else "\"reactions\": [],"
            return """
                {
                  "id": "$id", "seq": $seq, "channelId": "${scope.scopeId}",
                  "senderType": "user", "senderId": "user-1", "randomId": null,
                  "messageType": "chat", "content": "$content", "actionMetadata": null, "threadId": $thread,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
                  "senderName": "Alice", "senderDescription": null, "senderMembershipStatus": null,
                  "mentions": [], "attachments": [], $reactions
                  "conversationContext": { "channelType": "channel" }
                }
            """.trimIndent()
        }

        suspend fun storedContents(scope: SyncScopeKey): Map<String, String> =
            store.restore(scope).envelopes.associate { it.fact.id to it.fact.content }

        suspend fun storedIds(scope: SyncScopeKey): Set<String> = storedContents(scope).keys

        suspend fun storedCursor(scope: SyncScopeKey): PersistedCursor? = store.readCursor(scope)

        fun storedUpdatedAtMs(scope: SyncScopeKey): Long? =
            database.queries.selectSyncCursor(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
            ).executeAsOneOrNull()?.updatedAtMs
    }

    private class CountingStore(private val delegate: CanonicalMessageV2Store) : CanonicalMessageV2Store {
        @Volatile var snapshotWrites = 0
        @Volatile var incrementalWrites = 0

        /** The disk write of a realtime frame throws (SQLite full / IO error): nothing is committed. */
        @Volatile var failIncrementals = false

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applySnapshot(frame)
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            if (failIncrementals) throw IllegalStateException("disk full")
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
        override suspend fun containsFact(scope: SyncScopeKey, factId: String): Boolean = delegate.containsFact(scope, factId)
    }

    private fun <T> withFreshDatabase(block: (SlockSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            return block(SlockSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
