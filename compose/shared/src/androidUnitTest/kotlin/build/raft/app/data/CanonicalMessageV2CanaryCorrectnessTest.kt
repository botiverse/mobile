package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.MessageAggregateField
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
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
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
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toThreadMessageItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    private val thread1 = SyncScopeKey(partition, "thread-1", "thread")
    private val thread1Parent = ThreadDurableReplyParent(parentChannelId = "channel-a", rootMessageId = "root-1")

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
                rig.connect(2L)
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
            rig.connect(1L)
            rig.server.has(channelA, "m1" to 1L)
            rig.enter(channelA)
            val loads = rig.server.pageLoads()

            rig.connect(2L)
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
            rig.connect(1L)
            rig.server.has(channelA, "a1" to 1L)
            rig.server.has(channelB, "b1" to 1L)
            rig.enter(channelA)
            rig.enter(channelB)
            val loads = rig.server.pageLoads()

            rig.connect(2L)
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

    // ---- gap catch-up of the viewed channel: continuous again only after rooms:joined ---------------

    /** Channel A open under epoch 1; the socket reconnects as 2 (m2, m3 missed); returns after [catchUp]. */
    private fun catchUpCase(
        roomsJoinedForEpoch2: Boolean,
        roomsJoinedForEpoch1: Boolean = true,
        nonMember: Boolean = false,
    ): Pair<Set<String>, CanonicalMessageV2ScopeContinuity> = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.connect(1L, roomsJoined = roomsJoinedForEpoch1)
            rig.server.has(channelA, "m1" to 1L)
            rig.enter(channelA)
            if (nonMember) rig.nonMembers += channelA.scopeId
            rig.server.has(channelA, "m2" to 2L, "m3" to 3L)
            rig.connect(2L, roomsJoined = roomsJoinedForEpoch2)
            rig.catchUp(channelA)
            assertEquals(setOf("m1", "m2", "m3"), rig.storedIds(channelA), "the catch-up page is written either way")
            val paths = rig.server.paths.toList()
            rig.deliver(rig.messageNew(channelA, "m4", 4L), epoch = 2L)
            assertEquals(paths, rig.server.paths, "a realtime frame never loads")
            rig.storedIds(channelA) to rig.canary.continuityOf(channelA)
        }
    }

    @Test
    fun aCatchUpAfterRoomsJoinedForTheCurrentConnectionMakesTheViewedChannelContinuousAgain() {
        assertEquals(setOf("m1", "m2", "m3", "m4") to CanonicalMessageV2ScopeContinuity.Continuous, catchUpCase(roomsJoinedForEpoch2 = true))
    }

    @Test
    fun aCatchUpBeforeRoomsJoinedForTheCurrentConnectionLeavesTheChannelDisplayOnly() {
        // No rooms:joined recorded at all yet (the signal has not arrived).
        assertEquals(
            setOf("m1", "m2", "m3") to CanonicalMessageV2ScopeContinuity.Discontinuous,
            catchUpCase(roomsJoinedForEpoch2 = false, roomsJoinedForEpoch1 = false),
        )
    }

    @Test
    fun aCatchUpWithRoomsJoinedOnlyForAnOlderConnectionLeavesTheChannelDisplayOnly() {
        // rooms:joined arrived for epoch 1 (before the reconnect), never for epoch 2.
        assertEquals(
            setOf("m1", "m2", "m3") to CanonicalMessageV2ScopeContinuity.Discontinuous,
            catchUpCase(roomsJoinedForEpoch2 = false, roomsJoinedForEpoch1 = true),
        )
    }

    @Test
    fun aCatchUpOfANonMemberChannelLeavesItDisplayOnly() {
        assertEquals(
            setOf("m1", "m2", "m3") to CanonicalMessageV2ScopeContinuity.Discontinuous,
            catchUpCase(roomsJoinedForEpoch2 = true, nonMember = true),
        )
    }

    @Test
    fun framesDuringTheCatchUpFetchAreReplayedAfterItsCommit() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.connect(1L)
            rig.server.has(channelA, "m1" to 1L)
            rig.enter(channelA)
            rig.server.has(channelA, "m2" to 2L)
            rig.connect(2L)
            val m3 = rig.messageNew(channelA, "m3", 3L)
            rig.catchUp(channelA) {
                // Pushed after the page was read: buffered, displayed, not on the page.
                assertTrue(rig.deliver(m3, epoch = 2L))
                assertEquals(setOf("m1"), rig.storedIds(channelA), "buffered, not written yet")
            }
            assertEquals(m3, rig.projected.last())
            assertEquals(setOf("m1", "m2", "m3"), rig.storedIds(channelA), "m3 survives the catch-up commit")
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
            rig.deliver(rig.messageNew(channelA, "m4", 4L), epoch = 2L)
            assertEquals(setOf("m1", "m2", "m3", "m4"), rig.storedIds(channelA))
        }
    }

    @Test
    fun anEnterRightAfterAReconnectAppendsOnlyOnceRoomsJoinedArrivedForThatConnection() {
        fun run(roomsJoined: Boolean): Set<String> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.connect(2L, roomsJoined = roomsJoined)
                rig.server.has(channelA, "m1" to 1L)
                rig.enter(channelA)
                rig.deliver(rig.messageNew(channelA, "m2", 2L), epoch = 2L)
                rig.storedIds(channelA)
            }
        }
        assertEquals(setOf("m1"), run(roomsJoined = false), "the page may predate the connect-time room join")
        assertEquals(setOf("m1", "m2"), run(roomsJoined = true))
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

    /** Task #14a: an update without `reactions` (allowed unknown) is written; the stored roster is kept. */
    @Test
    fun t6AnUpdateWithoutReactionsIsWrittenAndKeepsTheStoredRoster() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited", partial = true) },
        expectedContent = "edited",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Continuous,
        expectedIncrementals = 1,
    )

    /** Twin: an update also without `mentions` (not allowed unknown) marks the window discontinuous. */
    @Test
    fun t6AnUpdateWithoutMentionsMarksTheWindowDiscontinuous() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited", partial = true, omit = setOf("mentions")) },
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
            // Android: AppShell → process owner, then the shell owner. iOS additionally the process
            // ingress coordinator → process owner.
            suspend fun deliverEverywhere(event: RealtimeEvent) {
                rig.recordOnServer(event)
                processOwner.acceptProcessMutation(grant, event, "process_normal_realtime", presenceTransportEpoch = 3L)
                processOwner.acceptProcessMutation(grant, event, "process_realtime", presenceTransportEpoch = 3L)
                shellOwner.accept(grant, event, source = "app_shell", transportEpoch = 3L)
            }
            rig.connect(3L)
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

    // ---- task #14a: realtime replies of an entered reply thread without a join ack are display only -----
    //
    // Without a positive per-thread delivery signal (thread rooms are never joined at connect; task #14c: no
    // `channel:joined` for the thread before the enter), a reply-thread enter commits its page but leaves the
    // window `thread_delivery_unconfirmed`.

    @Test
    fun t10AnEnteredThreadKeepsItsPageButARealtimeReplyIsDisplayOnly() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
            rig.server.has(thread1, "r1" to 1L)
            rig.enterThread(thread1, thread1Parent)
            val loads = rig.server.pageLoads()

            val r2 = rig.replyNew(thread1, "r2", 2L)
            assertTrue(rig.deliver(r2))
            assertEquals(r2, rig.projected.last(), "displayed")

            assertEquals(setOf("r1"), rig.storedIds(thread1), "the enter page is written, the realtime reply is not")
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(loads, rig.server.pageLoads(), "a realtime frame never loads")
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(thread1))
            assertTrue(
                rig.logs.any {
                    it.startsWith("canonical_v2_canary enter=committed server=server-1 scope=thread-1 scopeKind=thread ") &&
                        it.endsWith(" continuity=discontinuous reason=thread_delivery_unconfirmed")
                },
                rig.logs.toString(),
            )
            val offline = CanonicalMessageV2DurableLastKnownReader(rig.store, isEnabledForServer = { true })
                .readReplyThread(partition, "thread-1", thread1Parent)
            assertEquals(listOf("r1"), offline?.envelopes?.map { it.fact.id }, "the durable read serves the enter page")

            // Control: the same kind of frame on an entered CHANNEL window is written.
            rig.server.has(channelA, "a1" to 1L)
            rig.enter(channelA)
            assertTrue(rig.deliver(rig.messageNew(channelA, "a2", 2L)))
            assertEquals(setOf("a1", "a2"), rig.storedIds(channelA))
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    @Test
    fun t10AReplyDuringTheThreadEnterFetchIsDisplayedButNotReplayed() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
            rig.server.has(thread1, "r1" to 1L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enterThread(thread1, thread1Parent) }
            hold.read.await()
            val r2 = rig.replyNew(thread1, "r2", 2L)
            assertTrue(rig.deliver(r2))
            assertEquals(r2, rig.projected.last(), "displayed while the page loads")
            hold.release.complete(Unit)
            entering.await()

            assertEquals(setOf("r1"), rig.storedIds(thread1), "a buffered reply is never replayed into an unconfirmed thread")
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(thread1))
            assertTrue(rig.deliver(rig.replyNew(thread1, "r3", 3L)))
            assertEquals(setOf("r1"), rig.storedIds(thread1))
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    // ---- task #14a: partial facts ("store partial, fill in when data arrives") -------------------------

    /**
     * The production `message:new` (no `senderDescription`, no `reactions` key) is written as a PARTIAL fact:
     * the window stays continuous (the next frame is appended), the durable read serves the message with no
     * description and no reactions, and the store's marker names both as unknown (never as empty).
     */
    @Test
    fun t11TheProductionNewIsWrittenPartialAndTheWindowStaysContinuous() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "a1" to 1L)
            rig.enter(channelA)

            assertTrue(rig.deliver(rig.messageNew(channelA, "a2", 2L, omit = PRODUCTION_NEW_OMITS)))

            assertEquals(setOf("a1", "a2"), rig.storedIds(channelA))
            assertEquals(mapOf("a2" to BOTH_UNKNOWN), rig.store.restore(channelA).unknownFieldsByFactId)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
            assertTrue(
                rig.logs.any {
                    it == "canonical_v2_canary written server=server-1 scope=channel-a source=incremental " +
                        "partial=SenderDescription,Reactions"
                },
                rig.logs.toString(),
            )
            val offline = assertNotNull(
                CanonicalMessageV2DurableLastKnownReader(rig.store, isEnabledForServer = { true }).read(partition, "channel-a"),
            )
            assertEquals(mapOf("a2" to BOTH_UNKNOWN), offline.unknownFieldsByFactId)
            val row = assertNotNull(offline.envelopes.single { it.fact.id == "a2" }.toThreadMessageItem(currentUserId = "user-9"))
            assertEquals(null, row.authorDescription)
            assertEquals(emptyList(), row.reactions)

            assertTrue(rig.deliver(rig.messageNew(channelA, "a3", 3L, omit = PRODUCTION_NEW_OMITS)))
            assertEquals(setOf("a1", "a2", "a3"), rig.storedIds(channelA), "the window kept appending")
        }
    }

    /** Twin: the same frame also without `mentions` (not allowed unknown) is display only, as before. */
    @Test
    fun t11AProductionNewAlsoMissingMentionsIsNotWrittenAndTheWindowTurnsDiscontinuous() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "a1" to 1L)
            rig.enter(channelA)

            assertTrue(rig.deliver(rig.messageNew(channelA, "a2", 2L, omit = PRODUCTION_NEW_OMITS + "mentions")))

            assertEquals(setOf("a1"), rig.storedIds(channelA))
            assertEquals(emptyMap(), rig.store.restore(channelA).unknownFieldsByFactId)
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelA))
            assertTrue(rig.deliver(rig.messageNew(channelA, "a3", 3L, omit = PRODUCTION_NEW_OMITS)))
            assertEquals(setOf("a1"), rig.storedIds(channelA), "display only until the next enter")
        }
    }

    /**
     * A partial fact is completed by the next full source of the same id — the enter snapshot, the gap
     * catch-up page, or a full `message:updated` — and its marker is cleared. Twin per source: without that
     * source the fact stays partial.
     */
    @Test
    fun t11APartialFactIsCompletedByEveryFullSource() {
        fun run(complete: suspend (Rig) -> Unit): Triple<Map<String, Set<MessageAggregateField>>, String?, List<String>> =
            withFreshDatabase { database ->
                val rig = Rig(database)
                bounded {
                    rig.server.has(channelA, "a1" to 1L)
                    rig.enter(channelA)
                    rig.deliver(rig.messageNew(channelA, "a2", 2L, omit = PRODUCTION_NEW_OMITS))
                    rig.server.describe("a2", senderDescription = "\"Release bot\"", reactions = ROSTER)
                    complete(rig)
                    val restored = rig.store.restore(channelA)
                    val a2 = restored.envelopes.single { it.fact.id == "a2" }.fact
                    Triple(
                        restored.unknownFieldsByFactId,
                        a2.senderDescription + "|" + a2.reactions.joinToString(",") { "${it.emoji}x${it.count}" },
                        rig.logs.filter { it.startsWith("canonical_v2_partial_completed") },
                    )
                }
            }
        val completedLine = listOf(
            "canonical_v2_partial_completed server=server-1 scope=channel-a scopeKind=channel msg=a2 " +
                "fields=SenderDescription,Reactions",
        )
        assertEquals(Triple(emptyMap(), "Release bot|👀x2", completedLine), run { it.enter(channelA) })
        assertEquals(Triple(emptyMap(), "Release bot|👀x2", completedLine), run { it.catchUp(channelA) })
        assertEquals(
            Triple(emptyMap(), "Release bot|👀x2", completedLine),
            run {
                it.deliver(
                    it.messageUpdated(channelA, "a2", 2L, content = "body-a2", reactions = ROSTER, senderDescription = "\"Release bot\""),
                )
            },
        )
        assertEquals(Triple(mapOf("a2" to BOTH_UNKNOWN), "null|", emptyList()), run { })
    }

    /** A partial frame for a fact already stored in full never erases what is known (insert-only `New` and update). */
    @Test
    fun t11APartialFrameNeverErasesAStoredFullFact() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "a1" to 1L, "a2" to 2L)
            rig.server.describe("a2", senderDescription = "\"Release bot\"", reactions = ROSTER)
            rig.enter(channelA)
            suspend fun a2() = rig.store.restore(channelA).envelopes.single { it.fact.id == "a2" }.fact

            // The same message's production `message:new` arriving late (a second entry / redelivery).
            assertTrue(rig.deliver(rig.messageNew(channelA, "a2", 2L, omit = PRODUCTION_NEW_OMITS)))
            assertEquals("Release bot", a2().senderDescription)
            assertEquals(listOf("👀" to 2), a2().reactions.map { it.emoji to it.count })

            // An edit whose frame omits both keys: content changes, the known aggregates stay.
            assertTrue(
                rig.deliver(rig.messageUpdated(channelA, "a2", 2L, content = "edited", omit = PRODUCTION_NEW_OMITS)),
            )
            assertEquals("edited", a2().content)
            assertEquals("Release bot", a2().senderDescription)
            assertEquals(listOf("👀" to 2), a2().reactions.map { it.emoji to it.count })
            assertEquals(emptyMap(), rig.store.restore(channelA).unknownFieldsByFactId)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
        }
    }

    /**
     * Reactions on a partial fact: the server announces a reaction change as a `message:updated` with the whole
     * roster, which makes the roster known (the description stays unknown); an update without `reactions` never
     * turns an unknown roster into an empty "known" one, nor a known one back into unknown.
     */
    @Test
    fun t11AReactionUpdateOnAPartialFactSetsTheWholeRosterAndNothingElseTouchesIt() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "a1" to 1L, "a3" to 3L)
            rig.enter(channelA)
            rig.deliver(rig.messageNew(channelA, "a4", 4L, omit = PRODUCTION_NEW_OMITS))
            rig.deliver(rig.messageNew(channelA, "a5", 5L, omit = PRODUCTION_NEW_OMITS))
            suspend fun fact(id: String) = rig.store.restore(channelA).envelopes.single { it.fact.id == id }.fact
            suspend fun unknown() = rig.store.restore(channelA).unknownFieldsByFactId

            // a4: a reaction was added (roster frame, no description key).
            assertTrue(
                rig.deliver(rig.messageUpdated(channelA, "a4", 4L, content = "body-a4", omit = setOf("senderDescription"), reactions = ROSTER)),
            )
            assertEquals(listOf("👀" to 2), fact("a4").reactions.map { it.emoji to it.count })
            assertEquals(setOf(MessageAggregateField.SenderDescription), unknown()["a4"])
            // ...then an edit without `reactions`: the known roster stays known.
            rig.deliver(rig.messageUpdated(channelA, "a4", 4L, content = "edited", omit = PRODUCTION_NEW_OMITS))
            assertEquals(listOf("👀" to 2), fact("a4").reactions.map { it.emoji to it.count })
            assertEquals(setOf(MessageAggregateField.SenderDescription), unknown()["a4"])

            // a5 (twin): only an edit without `reactions`: the roster stays UNKNOWN, not "known empty".
            rig.deliver(rig.messageUpdated(channelA, "a5", 5L, content = "edited", omit = PRODUCTION_NEW_OMITS))
            assertEquals(emptyList(), fact("a5").reactions)
            assertEquals(BOTH_UNKNOWN, unknown()["a5"])
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
        }
    }

    /**
     * Thread scope: the production reply `message:new` is display only (`thread_delivery_unconfirmed`); the
     * paired channel case is [t11TheProductionNewIsWrittenPartialAndTheWindowStaysContinuous].
     */
    @Test
    fun t11TheProductionReplyIsDisplayOnlyInAnEnteredThread() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
            rig.server.has(thread1, "r1" to 1L)
            rig.enterThread(thread1, thread1Parent)

            assertTrue(rig.deliver(rig.replyNew(thread1, "r2", 2L, omit = PRODUCTION_NEW_OMITS)))

            assertEquals(setOf("r1"), rig.storedIds(thread1))
            assertEquals(emptyMap(), rig.store.restore(thread1).unknownFieldsByFactId)
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(thread1))
            assertTrue(rig.logs.none { "partial=" in it }, rig.logs.toString())
            assertTrue(
                rig.logs.any {
                    it == "canonical_v2_canary skip=discontinuous server=server-1 scope=thread-1 scopeKind=thread kind=new msg=r2 seq=2"
                },
                rig.logs.toString(),
            )
        }
    }

    // ---- task #14c: a public-parent thread whose room join was acked before the enter is continuous ----

    private fun threadEnterContinuity(rig: Rig, scope: SyncScopeKey = thread1): String =
        rig.logs.last { it.startsWith("canonical_v2_canary enter=committed server=server-1 scope=${scope.scopeId} scopeKind=thread ") }
            .substringAfter("continuity=")

    /**
     * Paired: the same connection, page and production reply wire; only `channel:joined` for the thread before the
     * enter differs. With it the reply is written as a PARTIAL fact; without it (or on a server that never acks)
     * the reply stays display only.
     */
    @Test
    fun t12APublicParentThreadAckedBeforeTheEnterWritesTheProductionReplyPartial() {
        fun run(ack: Boolean): Triple<Set<String>, Map<String, Set<MessageAggregateField>>, String> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
                rig.server.has(thread1, "r1" to 1L)
                rig.connect(1L)
                if (ack) rig.joinAck(thread1, 1L)
                rig.enterThread(thread1, thread1Parent)
                val r2 = rig.replyNew(thread1, "r2", 2L, omit = PRODUCTION_NEW_OMITS)
                assertTrue(rig.deliver(r2))
                assertEquals(r2, rig.projected.last(), "displayed either way")
                Triple(rig.storedIds(thread1), rig.store.restore(thread1).unknownFieldsByFactId, threadEnterContinuity(rig))
            }
        }
        val acked = run(ack = true)
        assertEquals("continuous", acked.third)
        assertEquals(setOf("r1", "r2"), acked.first)
        assertEquals(mapOf("r2" to BOTH_UNKNOWN), acked.second)

        val unacked = run(ack = false)
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", unacked.third)
        assertEquals(setOf("r1"), unacked.first)
        assertEquals(emptyMap(), unacked.second)
    }

    @Test
    fun t12APrivateParentThreadStaysDisplayOnlyEvenWithAnAck() {
        fun run(parentType: String): Pair<Set<String>, String> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.channelTypes["channel-a"] = parentType
                rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
                rig.server.has(thread1, "r1" to 1L)
                rig.connect(1L)
                rig.joinAck(thread1, 1L)
                rig.enterThread(thread1, thread1Parent)
                assertTrue(rig.deliver(rig.replyNew(thread1, "r2", 2L, omit = PRODUCTION_NEW_OMITS, parentChannelType = parentType)))
                rig.storedIds(thread1) to threadEnterContinuity(rig)
            }
        }
        assertEquals(setOf("r1") to "discontinuous reason=thread_delivery_follower_only", run("private"))
        assertEquals(setOf("r1", "r2") to "continuous", run("channel"))
    }

    @Test
    fun t12AnAckOnAnOlderConnectionOrForAnotherThreadOrDeniedLeavesTheReplyDisplayOnly() {
        fun run(signal: suspend (Rig) -> Unit): Pair<Set<String>, String> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
                rig.server.has(thread1, "r1" to 1L)
                rig.connect(1L)
                signal(rig)
                rig.connect(2L)
                rig.enterThread(thread1, thread1Parent)
                assertTrue(rig.deliver(rig.replyNew(thread1, "r2", 2L), epoch = 2L))
                rig.storedIds(thread1) to threadEnterContinuity(rig)
            }
        }
        val thread2 = SyncScopeKey(partition, "thread-2", "thread")
        assertEquals(setOf("r1") to "discontinuous reason=thread_delivery_unconfirmed", run { it.joinAck(thread1, 1L) })
        assertEquals(setOf("r1") to "discontinuous reason=thread_delivery_unconfirmed", run { it.joinAck(thread2, 2L) })
        assertEquals(setOf("r1") to "discontinuous reason=join_denied", run { it.joinAck(thread1, 2L, joined = false) })
        // Positive control: the same sequence with the current connection's ack for this thread is written.
        assertEquals(setOf("r1", "r2") to "continuous", run { it.joinAck(thread1, 2L) })
    }

    @Test
    fun t12AReconnectAfterAContinuousThreadEnterStopsTheWritesUntilTheNextEnter() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
            rig.server.has(thread1, "r1" to 1L)
            rig.connect(1L)
            rig.joinAck(thread1, 1L)
            rig.enterThread(thread1, thread1Parent)
            assertTrue(rig.deliver(rig.replyNew(thread1, "r2", 2L), epoch = 1L))
            assertEquals(setOf("r1", "r2"), rig.storedIds(thread1))

            rig.connect(2L)
            rig.joinAck(thread1, 2L)
            assertTrue(rig.deliver(rig.replyNew(thread1, "r3", 3L), epoch = 2L))
            assertEquals(setOf("r1", "r2"), rig.storedIds(thread1), "a reconnect ends continuity even with a new ack")
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(thread1))

            rig.enterThread(thread1, thread1Parent)
            assertEquals("continuous", threadEnterContinuity(rig))
            assertTrue(rig.deliver(rig.replyNew(thread1, "r4", 4L), epoch = 2L))
            assertEquals(setOf("r1", "r2", "r3", "r4"), rig.storedIds(thread1))
        }
    }

    @Test
    fun t12AnAckDuringTheThreadEnterFetchDoesNotReplayItsBufferedReply() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
            rig.server.has(thread1, "r1" to 1L)
            rig.connect(1L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enterThread(thread1, thread1Parent) }
            hold.read.await()
            // The server read the page before this socket's thread-room join completed.
            rig.joinAck(thread1, 1L)
            assertTrue(rig.deliver(rig.replyNew(thread1, "r2", 2L)))
            hold.release.complete(Unit)
            entering.await()

            assertEquals("discontinuous reason=thread_join_ack_after_request", threadEnterContinuity(rig))
            assertEquals(setOf("r1"), rig.storedIds(thread1))
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    @Test
    fun t12TheThreadsOwnNonMembershipYieldsToTheAckButTheParentChannelsNeverDoes() {
        fun run(nonMember: String): String = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.nonMembers += nonMember
                rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
                rig.server.has(thread1, "r1" to 1L)
                rig.connect(1L)
                rig.joinAck(thread1, 1L)
                rig.enterThread(thread1, thread1Parent)
                threadEnterContinuity(rig)
            }
        }
        assertEquals("continuous", run(nonMember = "thread-1"))
        assertEquals("discontinuous reason=parent_non_member", run(nonMember = "channel-a"))
    }

    // ---- task #14c: a late ack on the open thread page requests one catch-up that commits continuous ----

    /**
     * The device run: the page's own `join:channel` is acked while the enter fetch is in flight, so the enter commits
     * `thread_join_ack_after_request`. Paired: the identical sequence where the late ack names the CURRENT connection
     * (one catch-up through the real latest-page writer, registered after the ack: continuous, and the next production
     * reply is written partial) or an OLDER one (no catch-up, the reply stays display only).
     */
    @Test
    fun t13ALateAckDuringTheThreadEnterRequestsOneCatchUpThatMakesTheWindowContinuous() {
        fun run(ackEpoch: Long): Triple<Set<String>, Int, List<String>> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.thread(thread1, parentChannelId = "channel-a", rootMessageId = "root-1")
                rig.server.has(thread1, "r1" to 1L)
                rig.connect(1L)
                rig.connect(2L)
                val catchUps = java.util.concurrent.atomic.AtomicInteger()
                // The open thread page: one gap catch-up per confirmation, as SharedThreadStore runs it.
                val page = launch(start = CoroutineStart.UNDISPATCHED) {
                    rig.canary.threadJoinConfirmations.collect { confirmed ->
                        assertEquals(thread1.scopeId to "channel-a", confirmed.threadScopeId to confirmed.replyParentScopeId)
                        catchUps.incrementAndGet()
                        rig.catchUpThread(thread1, thread1Parent)
                    }
                }
                val hold = rig.server.holdNextPage()
                val entering = async { rig.enterThread(thread1, thread1Parent) }
                hold.read.await()
                rig.joinAck(thread1, ackEpoch)
                hold.release.complete(Unit)
                entering.await()
                // Bounded either way; the twin without a catch-up gets the shorter wait.
                withTimeoutOrNull(if (ackEpoch == 2L) 30_000L else 2_000L) {
                    while (rig.logs.none { it.startsWith("canonical_v2_canary catch_up=committed") }) yield()
                }
                val r2 = rig.replyNew(thread1, "r2", 2L, omit = PRODUCTION_NEW_OMITS)
                assertTrue(rig.deliver(r2, epoch = 2L))
                assertEquals(r2, rig.projected.last(), "displayed either way")
                page.cancel()
                val commits = rig.logs
                    .filter { it.contains("=committed server=server-1 scope=thread-1 scopeKind=thread ") }
                    .map { it.substringAfter("canonical_v2_canary ").substringBefore(" server=") + " " + it.substringAfter("continuity=") }
                if (catchUps.get() > 0) {
                    assertTrue(
                        rig.logs.any {
                            it == "canonical_v2_canary written server=server-1 scope=thread-1 source=incremental scopeKind=thread " +
                                "partial=SenderDescription,Reactions"
                        },
                        rig.logs.toString(),
                    )
                }
                Triple(rig.storedIds(thread1), catchUps.get(), commits)
            }
        }
        assertEquals(
            Triple(
                setOf("r1", "r2"),
                1,
                listOf("enter=committed discontinuous reason=thread_join_ack_after_request", "catch_up=committed continuous"),
            ),
            run(ackEpoch = 2L),
        )
        assertEquals(
            Triple(setOf("r1"), 0, listOf("enter=committed discontinuous reason=thread_join_ack_after_request")),
            run(ackEpoch = 1L),
        )
    }

    /** A stored marker outside the allowed set (or out of canonical order) poisons the scope; a valid one reads. */
    @Test
    fun t11AnInvalidStoredMarkerPoisonsTheScope() {
        fun restoreWithMarker(marker: String): Result<RestoredCanonicalMessageV2Scope> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "a1" to 1L)
                rig.enter(channelA)
                database.queries.setCanonicalFactUnknownFields(
                    unknownFields = marker,
                    id = "a1",
                    environmentId = channelA.environmentId,
                    serverId = channelA.serverId,
                    principalId = channelA.principalId,
                    consumerId = channelA.consumerId,
                    scopeKind = channelA.scopeKind,
                    channelId = channelA.scopeId,
                )
                runCatching { rig.store.restore(channelA) }
            }
        }
        assertEquals(mapOf("a1" to BOTH_UNKNOWN), restoreWithMarker("SenderDescription,Reactions").getOrThrow().unknownFieldsByFactId)
        assertIs<CanonicalMessageV2ScopePoisoned>(restoreWithMarker("Mentions").exceptionOrNull())
        assertIs<CanonicalMessageV2ScopePoisoned>(restoreWithMarker("Reactions,SenderDescription").exceptionOrNull())
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
    private class ServerFake : RaftNetworkClient {
        val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val nodes = mutableMapOf<String, LinkedHashMap<String, Pair<Long, String>>>()
        @Volatile private var nextHold: PageHold? = null

        /** The page is short (the channel's beginning is in it) when true. */
        @Volatile var reachesBeginning = false

        /** Task #14a: what the REST node of a message carries for `senderDescription` / `reactions` (JSON values). */
        private val details = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

        fun describe(id: String, senderDescription: String, reactions: String) {
            details[id] = senderDescription to reactions
        }

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

        /** Task #14a: `channels/{thread}` answers a reply thread under this parent. */
        private val threads = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

        fun thread(scope: SyncScopeKey, parentChannelId: String, rootMessageId: String) {
            threads[scope.scopeId] = parentChannelId to rootMessageId
        }

        /** Task #14c: `channels/{id}` answers this `type` for a top-level channel (default the public `channel`). */
        val channelTypes = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun pageLoads(): Int = paths.count { it.startsWith("messages/channel/") }

        /** The channel's latest page (≤ 200 nodes) as the catch-up reads it, now. */
        fun latestPage(scope: SyncScopeKey): kotlinx.serialization.json.JsonArray = synchronized(nodes) {
            val page = nodes[scope.scopeId].orEmpty().entries.sortedByDescending { it.value.first }.take(200)
            kotlinx.serialization.json.Json.parseToJsonElement(
                "[" + page.joinToString(",") { (id, value) -> node(scope.scopeId, id, value.first, value.second) } + "]",
            ) as kotlinx.serialization.json.JsonArray
        }

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
            threads[channelId]?.let { (parentChannelId, rootMessageId) ->
                return HomeNetworkResponse(
                    200,
                    """{"id":"$channelId","type":"thread","parentChannelId":"$parentChannelId","parentMessageId":"$rootMessageId"}""",
                )
            }
            val type = channelTypes[channelId] ?: "channel"
            return HomeNetworkResponse(200, """{"id":"$channelId","type":"$type"}""")
        }

        private fun node(channelId: String, id: String, seq: Long, content: String): String {
            val (description, reactions) = details[id] ?: ("null" to "[]")
            return """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1",""" +
                """"content":"$content","messageType":"chat","randomId":null,"threadId":null,""" +
                """"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":$description,""" +
                """"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":$reactions,"commentRef":null,"actionMetadata":null}"""
        }
    }

    private inner class Rig(
        private val database: RaftSqlDatabase,
        retention: CanonicalMessageV2RetentionPolicy = CanonicalMessageV2RetentionPolicy(),
    ) {
        @Volatile var clock = 100L
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val store = CountingStore(
            SqlDelightCanonicalMessageV2Store(database, now = { clock }, retention = retention, factLogger = { logs += it }),
        )
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
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

        private var connected = false

        /** The socket (re)connected as [epoch]; [roomsJoined]: the server's `rooms:joined` arrived on it. */
        suspend fun connect(epoch: Long, roomsJoined: Boolean = true) {
            canary.noteTransportEpoch(partition, epoch)
            if (roomsJoined) canary.noteRoomsJoined(partition, epoch)
            connected = true
        }

        /**
         * Task #14c: the server answered this socket's `join:channel` for [scope] on [epoch] (`channel:joined`
         * when [joined], else `channel:join_denied`), as the ingress coordinator forwards it.
         */
        suspend fun joinAck(scope: SyncScopeKey, epoch: Long, joined: Boolean = true) {
            canary.noteChannelJoinAck(partition, scope.scopeId, epoch, joined)
        }

        /** The user opens the channel: the real enter snapshot loader against [server]. */
        suspend fun enter(scope: SyncScopeKey) {
            if (!connected) connect(1L)
            loader.loadOnEnter(partition, scope.scopeId) { true }
        }

        /** Task #14a: the user opens reply thread [scope]: the real reply-thread enter loader against [server]. */
        suspend fun enterThread(scope: SyncScopeKey, parent: ThreadDurableReplyParent) {
            if (!connected) connect(1L)
            loader.loadReplyThreadOnEnter(partition, scope.scopeId, parent) { true }
        }

        private val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(server),
            isEnabledForServer = { true },
            logger = { logs += it },
            isKnownNonMember = { _, channelId -> channelId in nonMembers },
        )

        /**
         * The gap catch-up of the viewed [scope], as SharedThreadStore runs it: the baseline is registered
         * before the latest page is read; [duringFetch] runs after the read, before the commit.
         */
        suspend fun catchUp(scope: SyncScopeKey, duringFetch: suspend () -> Unit = {}) {
            val baseline = writer.beginLatestPageBaseline(partition, scope.scopeId, null)
            val page = server.latestPage(scope)
            duringFetch()
            writer.applyLatestPageAfterBaseline(partition, scope.scopeId, page, 200, null, baseline) { true }
        }

        /**
         * Task #14c: the gap catch-up of the open reply thread [scope] (SharedThreadStore's, through the real latest-page
         * writer): its baseline names [parent] and is registered before the latest page is read.
         */
        suspend fun catchUpThread(scope: SyncScopeKey, parent: ThreadDurableReplyParent) {
            val baseline = writer.beginLatestPageBaseline(partition, scope.scopeId, parent)
            val page = server.latestPage(scope)
            writer.applyLatestPageAfterBaseline(partition, scope.scopeId, page, 200, parent, baseline) { true }
        }

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

        /**
         * Real `message:new` wire JSON (server `projectMessageSocketPayload` + rich aggregates) via the mapper.
         * [omit]: wire keys the frame leaves out — the production send pipeline omits `senderDescription` and
         * `reactions` ([PRODUCTION_NEW_OMITS]).
         */
        fun messageNew(scope: SyncScopeKey, id: String, seq: Long, omit: Set<String> = emptySet()): MessageNew {
            val event = mapper.map(
                RealtimeSocketEvents.MessageNew,
                wire(scope, id, seq, "body-$id", threadId = null, partial = false, omit = omit),
            )
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        /**
         * Task #14a: a reply's real `message:new` wire JSON: `channelId` is the thread channel, the context names
         * the thread's parent channel and root message.
         */
        fun replyNew(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            parentChannelId: String = "channel-a",
            rootMessageId: String = "root-1",
            omit: Set<String> = emptySet(),
            parentChannelType: String = "channel",
        ): MessageNew {
            val context = """{ "channelType": "thread", "parentChannelId": "$parentChannelId", """ +
                """"parentChannelType": "$parentChannelType", "parentMessageId": "$rootMessageId" }"""
            val event = mapper.map(
                RealtimeSocketEvents.MessageNew,
                wire(scope, id, seq, "body-$id", threadId = null, partial = false, conversationContext = context, omit = omit),
            )
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        /**
         * Real `message:updated` wire JSON; [partial] drops `reactions` (a content-only update), [omit] drops more
         * keys; [reactions] / [senderDescription] are the JSON values sent when the key is present.
         */
        fun messageUpdated(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            content: String,
            threadId: String? = null,
            partial: Boolean = false,
            omit: Set<String> = emptySet(),
            reactions: String = "[]",
            senderDescription: String = "null",
        ): MessageUpdated {
            val event = mapper.map(
                RealtimeSocketEvents.MessageUpdated,
                wire(scope, id, seq, content, threadId, partial, omit = omit, reactions = reactions, senderDescription = senderDescription),
            )
            val updated = assertIs<MessageUpdated>(event)
            requireNotNull(updated.decodedCanonical) { "the wire frame must decode canonically" }
            return updated
        }

        private fun wire(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            content: String,
            threadId: String?,
            partial: Boolean,
            conversationContext: String = """{ "channelType": "channel" }""",
            omit: Set<String> = emptySet(),
            reactions: String = "[]",
            senderDescription: String = "null",
        ): String {
            val thread = threadId?.let { "\"$it\"" } ?: "null"
            val omitted = if (partial) omit + "reactions" else omit
            fun key(name: String, value: String) = if (name in omitted) "" else "\"$name\": $value,"
            return """
                {
                  "id": "$id", "seq": $seq, "channelId": "${scope.scopeId}",
                  "senderType": "user", "senderId": "user-1", "randomId": null,
                  "messageType": "chat", "content": "$content", "actionMetadata": null, "threadId": $thread,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
                  "senderName": "Alice", ${key("senderDescription", senderDescription)} "senderMembershipStatus": null,
                  ${key("mentions", "[]")} "attachments": [], ${key("reactions", reactions)}
                  "conversationContext": $conversationContext
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

    private companion object {
        /** The keys botiverse/slock's ordinary `message:new` leaves out (task #14a). */
        val PRODUCTION_NEW_OMITS = setOf("senderDescription", "reactions")
        val BOTH_UNKNOWN = setOf(MessageAggregateField.SenderDescription, MessageAggregateField.Reactions)
        const val ROSTER = """[{"emoji":"👀","count":2,"reactorIds":["user-1","user-2"],"reactorNames":["Alice","Bob"]}]"""
    }

    private fun <T> withFreshDatabase(block: (RaftSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            return block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
