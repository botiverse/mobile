package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.commitEnterBaselineForTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The #2991 transport-epoch rules on the task #168 step 3 canary: a window comes only from an enter
 * snapshot (committed under the epoch recorded when it was requested); every realtime frame of another
 * connection makes it display only until the next enter — nothing re-snapshots in the background.
 */
class CanonicalMessageV2ProductionCanaryTest {
    private val partition =
        DurablePartitionKey("server-1", "principal-1", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private val scope = SyncScopeKey(partition, "channel-1", "channel")
    private val threadScope = SyncScopeKey(partition, "thread-1", "thread")

    @Test
    fun flagOffDoesZeroV2Work() = runBlocking {
        val fixture = Fixture(enabled = false)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(0, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(0, fixture.store.reads)
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aScopeWithoutAWindowIsDisplayOnlyWithoutTouchingTheStore() = runBlocking {
        val fixture = Fixture(enabled = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(0, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(0, fixture.store.reads, "the display path never waits for a store lookup")
    }

    @Test
    fun anEnteredWindowAppendsTheNextNewIncrementally() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(1, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    @Test
    fun everyWindowedChannelIsWrittenNotOnlyOne() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        fixture.enter(1L, channelId = "channel-2")

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("n2", 2L, channelId = "channel-2")))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun unsupportedProducerMetadataMakesTheWindowDiscontinuous() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        val unsupported = fixture.input("m2", 2L).copy(
            row = fixture.input("m2", 2L).row.copy(
                actionMetadata = buildJsonObject {
                    put("kind", JsonPrimitive("future-action"))
                    put("payload", JsonPrimitive("opaque"))
                },
            ),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(unsupported))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(scope))
        // The window now misses m2: m3 must not be appended after m1.
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m3", 3L)))
        assertEquals(0, fixture.store.incrementalCalls)
    }

    @Test
    fun flagRevokeClearsTheWindowWithoutANetworkBarrierAndReturnsTheScopeToLegacy() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        assertEquals(1, fixture.store.restore(scope).envelopes.size)

        fixture.enabled = false
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(emptyList(), fixture.store.restore(scope).envelopes)
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aThreadReplyIsNeverWrittenIntoAChannelWindow() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)
        val threadInput = fixture.input("reply-1", 2L).copy(
            row = fixture.input("reply-1", 2L).row.copy(
                conversationContext = CanonicalConversationContextWire(
                    channelType = "thread",
                    parentChannelId = "channel-1",
                    parentChannelType = "channel",
                    parentMessageId = "root-1",
                ),
            ),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(threadInput))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aReconnectMakesTheWindowDisplayOnlyUntilTheNextEnter() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        // m3 is delivered while the socket is down and never reaches the writer; the socket comes back as epoch 2.
        fixture.connect(2L)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m5", 5L)))
        assertEquals(1, fixture.store.incrementalCalls, "m4 / m5 must not be appended after m2")
        assertEquals(1, fixture.store.snapshotCalls, "no background re-snapshot (task #168 step 3)")
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(scope))

        // The user opens the channel: its enter snapshot (requested under epoch 2) is the new baseline.
        fixture.enter(5L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m6", 6L)))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun withoutAReconnectTheNextFrameStaysIncremental() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L))
        // A repeated signal for the same epoch is not a reconnect.
        fixture.connect(1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun aRestoredWindowIsDisplayOnlyAfterProcessRestartUntilOpened() = runBlocking {
        val store = FakeStore()
        val beforeRestart = Fixture(enabled = true, store = store)
        beforeRestart.enter(1L)
        beforeRestart.accept(beforeRestart.input("m2", 2L))
        assertEquals(1, store.incrementalCalls)

        // New process: same durable store, fresh canary and engine. Frames may have been missed meanwhile.
        val afterRestart = Fixture(enabled = true, store = store)
        val readsBefore = store.reads
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, afterRestart.accept(afterRestart.input("m4", 4L)))
        assertEquals(1, store.incrementalCalls, "a restored window is never appended onto")
        assertEquals(readsBefore, store.reads, "and the frame never waited for a store lookup")

        afterRestart.enter(4L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, afterRestart.accept(afterRestart.input("m5", 5L)))
        assertEquals(2, store.incrementalCalls)
    }

    @Test
    fun aReconnectOnAnotherServerOrAccountDoesNotTouchThisWindow() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.enter(1L)

        fixture.canary.noteTransportEpoch(partition.copy(serverId = "server-2"), 2L)
        fixture.canary.noteTransportEpoch(partition.copy(principalId = "principal-2"), 2L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun aRestartedTransportCountingFromOneIsStillAReconnect() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(5L)
        fixture.enter(1L)

        // The native transport object was rebuilt (server switch, re-login) and numbers from 1 again.
        fixture.connect(1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(0, fixture.store.incrementalCalls, "a smaller recorded epoch is a different connection too")
    }

    @Test
    fun aReconnectDuringTheEnterFetchKeepsTheNewWindowDisplayOnly() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        // The request is sent under epoch 1; the socket reconnects as epoch 2 before the page commits.
        fixture.enter(1L, beforeCommit = { fixture.connect(2L) })

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(0, fixture.store.incrementalCalls, "the page predates epoch 2")

        // Twin: no reconnect during the fetch.
        val twin = Fixture(enabled = true)
        twin.connect(1L)
        twin.enter(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, twin.accept(twin.input("m2", 2L)))
    }

    @Test
    fun anEnterRightAfterAReconnectIsDisplayOnlyUntilRoomsJoinedArrivedForThatConnection() = runBlocking {
        // Before rooms:joined the page may predate the connect-time room join: display only.
        val before = Fixture(enabled = true)
        before.connect(2L, roomsJoined = false)
        before.enter(1L)
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, before.canary.continuityOf(scope))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, before.accept(before.input("m2", 2L)))
        assertEquals(0, before.store.incrementalCalls)

        // rooms:joined recorded for an OLDER connection only: still display only.
        val older = Fixture(enabled = true)
        older.connect(1L)
        older.connect(2L, roomsJoined = false)
        older.enter(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, older.accept(older.input("m2", 2L)))
        assertEquals(0, older.store.incrementalCalls)

        // Twin: the same open once rooms:joined arrived for the current connection appends.
        val after = Fixture(enabled = true)
        after.connect(2L, roomsJoined = false)
        after.canary.noteRoomsJoined(partition, 2L)
        after.enter(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, after.accept(after.input("m2", 2L)))
        assertEquals(1, after.store.incrementalCalls)
    }

    @Test
    fun aCatchUpBaselineIsRegisteredOnlyForAMemberWhoseConnectionSawRoomsJoined() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(2L, roomsJoined = false)
        assertEquals(null, fixture.canary.beginCatchUpBaseline(partition, "channel-1", nonMember = false), "before rooms:joined")
        fixture.canary.noteRoomsJoined(partition, 2L)
        assertEquals(null, fixture.canary.beginCatchUpBaseline(partition, "channel-1", nonMember = true), "non-member")
        val registered = fixture.canary.beginCatchUpBaseline(partition, "channel-1", nonMember = false)
        assertEquals(2L, registered?.epochAtRequest)
        // A partition reset forgets rooms:joined with the epoch.
        fixture.canary.abandonEnterBaseline(registered!!)
        fixture.resetPartition()
        fixture.connect(2L, roomsJoined = false)
        assertEquals(null, fixture.canary.beginCatchUpBaseline(partition, "channel-1", nonMember = false), "after a reset")
    }

    @Test
    fun aFrameFromTheNewConnectionIsDisplayOnlyEvenWhenItBeatsTheReconnectSignal() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L), transportEpoch = 1L)
        assertEquals(1, fixture.store.incrementalCalls)

        // The socket came back as epoch 2, but its first frame is processed before the epoch-2 signal.
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls, "m4 must not be appended after m2")

        fixture.connect(2L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m5", 5L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls, "still display only: only an enter makes it continuous again")
    }

    @Test
    fun aLateFrameOfAnOlderConnectionIsShownButNeverWrittenAndChangesNothing() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(2L)
        fixture.enter(104L)

        // m110 left the old socket before it closed; its coroutine only now reaches the writer.
        assertEquals(
            CanonicalMessageV2CanaryOutcome.NotSelected,
            fixture.accept(fixture.input("m110", 110L), transportEpoch = 1L),
            "an older connection's frame goes to the legacy display path only",
        )
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope), "no state change")

        // Twin: the same frame under the current connection is appended.
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m110", 110L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun framesWithoutATransportEpochStillFollowTheReconnectSignal() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.enter(1L)
        fixture.accept(fixture.input("m2", 2L))
        assertEquals(1, fixture.store.incrementalCalls)

        fixture.connect(2L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m4", 4L)))
        assertEquals(1, fixture.store.incrementalCalls)
    }


    // ---- task #14a: a reply thread's window is baselined like a channel's ---------------------------------
    //
    // Without a positive per-thread delivery signal (no connect-time join of thread rooms; task #14c: no
    // `channel:joined` for the thread before the enter) every reply-thread enter commit ends
    // `reason=thread_delivery_unconfirmed` and realtime replies are display only. Each case is paired with the
    // identical frame on a CHANNEL window, which is written.

    private val threadUnconfirmedLine =
        "canonical_v2_canary enter=committed server=server-1 scope=thread-1 scopeKind=thread epoch=1 buffered=0 " +
            "continuity=discontinuous reason=thread_delivery_unconfirmed"

    @Test
    fun aReplyOfAnEnteredThreadIsDisplayOnlyBecauseThreadDeliveryIsUnconfirmed() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enterThread(1L)

        // The enter page itself is written (enter / paging writers are unchanged).
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(listOf("e1"), fixture.store.restore(threadScope).envelopes.map { it.fact.id })
        assertEquals(
            Triple("channel", "channel-1", "root-1"),
            fixture.store.restore(threadScope).cursor?.let { Triple(it.parentScopeKind, it.parentScopeId, it.rootMessageId) },
        )
        assertTrue(fixture.logs.any { it == threadUnconfirmedLine }, fixture.logs.toString())
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(threadScope))

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.reply("r2", 2L)))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(listOf("e1"), fixture.store.restore(threadScope).envelopes.map { it.fact.id })
        assertTrue(
            fixture.logs.any {
                it == "canonical_v2_canary skip=discontinuous server=server-1 scope=thread-1 scopeKind=thread kind=new msg=r2 seq=2"
            },
            fixture.logs.toString(),
        )
        assertTrue(fixture.logs.none { "source=incremental" in it }, fixture.logs.toString())
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, fixture.canary.continuityOf(scope), "the parent channel is untouched")

        // Control: the identical frame shape on an entered CHANNEL window is written.
        fixture.enter(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("r2", 2L)))
        assertEquals(1, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    @Test
    fun aReplyOfAThreadNeverEnteredInThisProcessIsDisplayOnlyWithoutAStoreRead() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enterThread(1L)
        val readsBefore = fixture.store.reads

        // thread-2 was never entered: no window, no write, no store lookup.
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.reply("s2", 2L, threadId = "thread-2")))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(readsBefore, fixture.store.reads)
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, fixture.canary.continuityOf(threadScope.copy(scopeId = "thread-2")))

        // Entering it again in a new process over the same store still never makes it continuous.
        val afterRestart = Fixture(enabled = true, store = fixture.store)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, afterRestart.accept(afterRestart.reply("s3", 3L)))
        afterRestart.enterThread(3L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, afterRestart.accept(afterRestart.reply("s4", 4L)))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, afterRestart.canary.continuityOf(threadScope))
    }

    /**
     * The realtime frame-parent check is unreachable while threads are never continuous (the plan stops at
     * `skip=discontinuous` first); the enter-commit parent check still runs, and it outranks the delivery gate.
     */
    @Test
    fun aThreadEnterWhoseCommittedCursorNamesAnotherParentEndsParentMismatch() = runBlocking {
        fun run(cursorParentId: String): Fixture = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.enterThread(1L, cursorParentId = cursorParentId)
            fixture.accept(fixture.reply("r2", 2L))
            fixture
        }
        val mismatch = run(cursorParentId = "channel-2")
        assertTrue(
            mismatch.logs.any { it.startsWith("canonical_v2_canary enter=committed server=server-1 scope=thread-1 scopeKind=thread") &&
                it.endsWith("continuity=discontinuous reason=parent_mismatch") },
            mismatch.logs.toString(),
        )
        assertEquals(0, mismatch.store.incrementalCalls)
        // Twin: the cursor naming the baseline's parent passes the parent check and stops at the delivery gate.
        val matching = run(cursorParentId = "channel-1")
        assertTrue(matching.logs.any { it == threadUnconfirmedLine }, matching.logs.toString())
        assertTrue(matching.logs.none { "parent_mismatch" in it }, matching.logs.toString())
        assertEquals(0, matching.store.incrementalCalls)
    }

    @Test
    fun aRoomLeaveDuringTheThreadFetchCountsTheThreadAndItsParentRoom() = runBlocking {
        fun reasonAfterLeaving(leftRoom: String): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.enterThread(1L, beforeCommit = { fixture.canary.noteRoomLeft("server-1", leftRoom) })
            fixture.accept(fixture.reply("r2", 2L))
            assertEquals(0, fixture.store.incrementalCalls)
            fixture.logs.single { it.startsWith("canonical_v2_canary enter=committed") }.substringAfter("reason=")
        }
        assertEquals("room_left", reasonAfterLeaving("thread-1"))
        assertEquals("room_left", reasonAfterLeaving("channel-1"), "replies may come through the parent channel's room")
        // Twin: an unrelated room leaves only the delivery gate.
        assertEquals("thread_delivery_unconfirmed", reasonAfterLeaving("channel-9"))
    }

    @Test
    fun aThreadUnderANonMemberParentEndsNonMember() = runBlocking {
        fun reason(nonMember: Boolean): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.enterThread(1L, nonMember = nonMember)
            fixture.accept(fixture.reply("r2", 2L))
            assertEquals(0, fixture.store.incrementalCalls)
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(threadScope))
            fixture.logs.single { it.startsWith("canonical_v2_canary enter=committed") }.substringAfter("reason=")
        }
        assertEquals("non_member", reason(nonMember = true))
        assertEquals("thread_delivery_unconfirmed", reason(nonMember = false))
    }

    @Test
    fun aReplyDuringTheThreadEnterFetchIsDisplayedButNeverReplayed() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enterThread(1L, beforeCommit = {
            assertEquals(
                CanonicalMessageV2CanaryOutcome.Pending,
                fixture.accept(fixture.reply("r2", 2L)),
                "buffered for the thread's enter, displayed now",
            )
        })
        assertEquals(listOf("e1"), fixture.store.restore(threadScope).envelopes.map { it.fact.id })
        assertEquals(0, fixture.store.incrementalCalls)
        assertTrue(
            fixture.logs.any {
                it == "canonical_v2_canary enter=committed server=server-1 scope=thread-1 scopeKind=thread epoch=1 buffered=1 " +
                    "continuity=discontinuous reason=thread_delivery_unconfirmed"
            },
            fixture.logs.toString(),
        )
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.reply("r3", 3L)))
        assertEquals(0, fixture.store.incrementalCalls)

        // Control: the identical buffered frame shape during a CHANNEL enter is replayed.
        fixture.enter(1L, beforeCommit = {
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, fixture.accept(fixture.input("m2", 2L)))
        })
        assertEquals(listOf("e1", "m2"), fixture.store.restore(scope).envelopes.map { it.fact.id })
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    // ---- task #14c: a public-parent thread whose room join was acked before the enter is continuous ----
    //
    // Each case is paired with the identical sequence differing only in the signal under test.

    private fun threadEnterReason(fixture: Fixture, threadId: String = "thread-1"): String =
        fixture.logs.last { it.startsWith("canonical_v2_canary enter=committed server=server-1 scope=$threadId scopeKind=thread") }
            .substringAfter("continuity=")

    @Test
    fun aPublicParentThreadAckedBeforeTheEnterIsContinuousAndWritesTheProductionReplyPartial() = runBlocking {
        fun run(ack: Boolean): Fixture = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            if (ack) fixture.joinAck(1L)
            fixture.enterThread(1L)
            fixture.accept(wireReply(extraKeys = ""))
            fixture
        }
        val acked = run(ack = true)
        assertEquals("continuous", threadEnterReason(acked))
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, acked.canary.continuityOf(threadScope))
        assertEquals(listOf("e1", "reply-2"), acked.store.restore(threadScope).envelopes.map { it.fact.id })
        assertEquals(
            mapOf("reply-2" to setOf(MessageAggregateField.SenderDescription, MessageAggregateField.Reactions)),
            acked.store.lastIncremental?.unknownFieldsByFactId,
            "the production reply (no senderDescription / reactions) is a PARTIAL fact",
        )
        assertTrue(
            acked.logs.any {
                it == "canonical_v2_canary written server=server-1 scope=thread-1 source=incremental scopeKind=thread " +
                    "partial=SenderDescription,Reactions"
            },
            acked.logs.toString(),
        )
        // Twin without the ack (also: a server that never sends it): display only, the safe default.
        val unacked = run(ack = false)
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", threadEnterReason(unacked))
        assertEquals(0, unacked.store.incrementalCalls)
        assertEquals(listOf("e1"), unacked.store.restore(threadScope).envelopes.map { it.fact.id })
    }

    @Test
    fun anAckArrivingOnlyDuringTheEnterFetchDoesNotMakeThatEnterContinuousButTheNextOne() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.enterThread(1L, beforeCommit = { fixture.joinAck(1L) })
        // The server may have read the page before it joined the room: a reply between is in neither.
        assertEquals("discontinuous reason=thread_join_ack_after_request", threadEnterReason(fixture))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.reply("r2", 2L)))
        assertEquals(0, fixture.store.incrementalCalls)

        // The next enter on the same connection is registered after the ack: continuous.
        fixture.enterThread(2L)
        assertEquals("continuous", threadEnterReason(fixture))
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.reply("r3", 3L)))
        assertEquals(1, fixture.store.incrementalCalls)
    }

    @Test
    fun anAckOfAnOlderConnectionNeverMakesTheThreadContinuous() = runBlocking {
        fun run(ackEpoch: Long): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            fixture.joinAck(ackEpoch)
            fixture.connect(2L)
            fixture.enterThread(1L)
            fixture.accept(fixture.reply("r2", 2L), transportEpoch = 2L)
            threadEnterReason(fixture).also {
                if (it != "continuous") assertEquals(0, fixture.store.incrementalCalls)
            }
        }
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", run(ackEpoch = 1L))
        assertEquals("continuous", run(ackEpoch = 2L))
    }

    @Test
    fun anAckForAnotherThreadHasNoEffect() = runBlocking {
        fun run(ackedThread: String): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            fixture.joinAck(1L, threadId = ackedThread)
            fixture.enterThread(1L)
            threadEnterReason(fixture)
        }
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", run(ackedThread = "thread-2"))
        assertEquals("continuous", run(ackedThread = "thread-1"))
    }

    @Test
    fun aJoinDeniedEndsJoinDeniedBeforeDuringOrAfterTheEnter() = runBlocking {
        // Denied as the latest answer before the enter (an ack before it is overridden).
        val before = Fixture(enabled = true)
        before.connect(1L)
        before.joinAck(1L)
        before.joinAck(1L, joined = false)
        before.enterThread(1L)
        assertEquals("discontinuous reason=join_denied", threadEnterReason(before))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, before.accept(before.reply("r2", 2L)))
        assertEquals(0, before.store.incrementalCalls)

        // Denied while the fetch is in flight, after an ack that would have made it continuous.
        val during = Fixture(enabled = true)
        during.connect(1L)
        during.joinAck(1L)
        during.enterThread(1L, beforeCommit = { during.joinAck(1L, joined = false) })
        assertEquals("discontinuous reason=join_denied", threadEnterReason(during))

        // Denied after a continuous commit: the window stops being written.
        val after = Fixture(enabled = true)
        after.connect(1L)
        after.joinAck(1L)
        after.enterThread(1L)
        assertEquals("continuous", threadEnterReason(after))
        after.joinAck(1L, joined = false)
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, after.canary.continuityOf(threadScope))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, after.accept(after.reply("r2", 2L)))
        assertEquals(0, after.store.incrementalCalls)
        assertTrue(
            after.logs.any { it == "canonical_v2_canary discontinuous server=server-1 scope=thread-1 scopeKind=thread reason=join_denied" },
            after.logs.toString(),
        )
        // Twin: a denial of an OLDER connection does not touch the window committed on the current one.
        val stale = Fixture(enabled = true)
        stale.connect(2L)
        stale.joinAck(2L)
        stale.enterThread(1L)
        stale.joinAck(1L, joined = false)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, stale.canary.continuityOf(threadScope))
    }

    @Test
    fun aPrivateDmOrJointParentThreadStaysFollowerOnlyEvenWithAnAck() = runBlocking {
        for (parentKind in listOf("private", "dm", "joint")) {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            fixture.joinAck(1L)
            fixture.enterThread(1L, parentKind = parentKind)
            assertEquals("discontinuous reason=thread_delivery_follower_only", threadEnterReason(fixture), parentKind)
            assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.reply("r2", 2L, parentKind = parentKind)))
            assertEquals(0, fixture.store.incrementalCalls, parentKind)
        }
    }

    @Test
    fun aReconnectAfterAContinuousThreadEnterMakesItDisplayOnlyUntilTheNextEnter() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.connect(1L)
        fixture.joinAck(1L)
        fixture.enterThread(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.reply("r2", 2L), transportEpoch = 1L))

        fixture.connect(2L)
        // The host rejoins the thread room and the server acks it on the new socket: still no write until re-enter.
        fixture.joinAck(2L)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.reply("r3", 3L), transportEpoch = 2L))
        assertEquals(1, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(threadScope))

        fixture.enterThread(4L)
        assertEquals("continuous", threadEnterReason(fixture))
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.reply("r5", 5L), transportEpoch = 2L))
    }

    @Test
    fun aLeaveOfTheThreadRoomAfterTheAckVoidsIt() = runBlocking {
        fun run(leaveAfterAck: Boolean, rejoinAck: Boolean = false): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            fixture.joinAck(1L)
            if (leaveAfterAck) fixture.canary.noteRoomLeft("server-1", "thread-1")
            // A later ack on the same socket may answer the join sent before the leave: never trusted.
            if (rejoinAck) fixture.joinAck(1L)
            fixture.enterThread(1L)
            threadEnterReason(fixture)
        }
        assertEquals("continuous", run(leaveAfterAck = false))
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", run(leaveAfterAck = true))
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", run(leaveAfterAck = true, rejoinAck = true))
    }

    /**
     * Review finding on #14c: the leave runs synchronously from the room-lease registry while epochs and acks reach
     * the canary asynchronously, so a leave on the new socket E2 can be noted BEFORE `noteTransportEpoch(E2)`
     * (or before any epoch at all), and the ack of a join sent before the leave can be processed after it. Any
     * leave of the thread room voids its acks on every epoch; the paired control without the leave is continuous.
     */
    @Test
    fun aLeaveNotedBeforeTheNewSocketsEpochStillVoidsItsLaterAck() = runBlocking {
        fun reconnected(leave: Boolean): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            // Socket E2: the host rejoined the thread room, the server acked; the user closed the thread (leave)
            // before shared processed E2's epoch; then the ack, then the epoch; the user reopens.
            if (leave) fixture.canary.noteRoomLeft("server-1", "thread-1")
            fixture.joinAck(2L)
            fixture.connect(2L)
            fixture.enterThread(1L)
            fixture.accept(fixture.reply("r2", 2L), transportEpoch = 2L)
            threadEnterReason(fixture).also {
                if (it != "continuous") assertEquals(0, fixture.store.incrementalCalls)
            }
        }
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", reconnected(leave = true))
        assertEquals("continuous", reconnected(leave = false))

        fun firstConnection(leave: Boolean): String = runBlocking {
            val fixture = Fixture(enabled = true)
            // The leave is noted before any epoch was recorded.
            if (leave) fixture.canary.noteRoomLeft("server-1", "thread-1")
            fixture.connect(1L)
            fixture.joinAck(1L)
            fixture.enterThread(1L)
            threadEnterReason(fixture)
        }
        assertEquals("discontinuous reason=thread_delivery_unconfirmed", firstConnection(leave = true))
        assertEquals("continuous", firstConnection(leave = false))
    }

    @Test
    fun theThreadsOwnNonMembershipYieldsToTheAckButTheParentsNeverDoes() = runBlocking {
        fun run(nonMember: Boolean, parentNonMember: Boolean, ack: Boolean): String = runBlocking {
            val fixture = Fixture(enabled = true)
            fixture.connect(1L)
            if (ack) fixture.joinAck(1L)
            fixture.enterThread(1L, nonMember = nonMember, parentNonMember = parentNonMember)
            threadEnterReason(fixture)
        }
        assertEquals("continuous", run(nonMember = true, parentNonMember = false, ack = true))
        assertEquals("discontinuous reason=non_member", run(nonMember = true, parentNonMember = false, ack = false))
        assertEquals("discontinuous reason=non_member", run(nonMember = false, parentNonMember = true, ack = true))
        assertEquals("continuous", run(nonMember = false, parentNonMember = false, ack = true))
    }

    // ---- device #3092: the real `message:new` wire, decoded exactly as the socket mapper does ----

    /**
     * botiverse/slock `broadcastAndDeliver` → `emitPersistedMessageToFrontend` (thread under a public channel):
     * the inserted row (storage-only keys projected out) + senderName / senderMembershipStatus / attachments /
     * mentions + conversationContext. No `senderDescription`, no `reactions` key. Task #14a: on a thread scope
     * the reply is display only (`thread_delivery_unconfirmed`); the identical wire on a channel window is
     * written as a PARTIAL fact naming the two unknown aggregates (never as "empty") and stays continuous.
     */
    @Test
    fun aRealThreadReplyIsDisplayOnlyAndTheSameWireOnAChannelIsWrittenAsAPartialFact() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enterThread(1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(wireReply(extraKeys = "")))
        assertEquals(listOf("e1"), fixture.store.restore(threadScope).envelopes.map { it.fact.id })
        assertEquals(0, fixture.store.incrementalCalls)
        assertTrue(fixture.logs.any { it == threadUnconfirmedLine }, fixture.logs.toString())
        assertTrue(fixture.logs.none { "partial=" in it }, fixture.logs.toString())

        // Control: the same wire as a channel message on an entered channel window.
        fixture.enter(1L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(wireReply(extraKeys = "", channel = true)))
        assertEquals(listOf("e1", "reply-2"), fixture.store.restore(scope).envelopes.map { it.fact.id })
        assertEquals(
            mapOf("reply-2" to setOf(MessageAggregateField.SenderDescription, MessageAggregateField.Reactions)),
            fixture.store.lastIncremental?.unknownFieldsByFactId,
        )
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
        assertTrue(
            fixture.logs.any {
                it == "canonical_v2_canary written server=server-1 scope=channel-1 source=incremental " +
                    "partial=SenderDescription,Reactions"
            },
            fixture.logs.toString(),
        )
    }

    /** Twin: the same channel wire also without `mentions` (not in the allowed set) is never written and says why. */
    @Test
    fun theSameWireAlsoMissingMentionsIsNeverWrittenAndSaysWhy() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)

        val noMentions = wireReply(extraKeys = "", mentionsKey = "", channel = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(noMentions))
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(listOf("e1"), fixture.store.restore(scope).envelopes.map { it.fact.id })
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, fixture.canary.continuityOf(scope))
        assertTrue(
            fixture.logs.any {
                it == "canonical_v2_canary discontinuous server=server-1 scope=channel-1 " +
                    "reason=not_full_v2 kind=new msg=reply-2 seq=2 missing=SenderDescription,Mentions,Reactions"
            },
            fixture.logs.toString(),
        )
        // The same frame through the owner's second entry: display only, and the line names it.
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(noMentions))
        assertTrue(
            fixture.logs.any {
                it == "canonical_v2_canary skip=discontinuous server=server-1 scope=channel-1 kind=new msg=reply-2 seq=2"
            },
            fixture.logs.toString(),
        )
    }

    /** Twin: the identical channel wire plus the two keys the producer contract lists for `message:new` is written full. */
    @Test
    fun theSameWireCarryingSenderDescriptionAndReactionsIsWrittenFull() = runBlocking {
        val fixture = Fixture(enabled = true)
        fixture.enter(1L)

        val full = wireReply(extraKeys = """"senderDescription":null,"reactions":[],""", channel = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(full))
        assertEquals(listOf("e1", "reply-2"), fixture.store.restore(scope).envelopes.map { it.fact.id })
        assertEquals(emptyMap(), fixture.store.lastIncremental?.unknownFieldsByFactId, "a full fact has no marker")
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
        assertTrue(fixture.logs.none { "discontinuous" in it || "partial=" in it }, fixture.logs.toString())
    }

    /** A frame missing only one aggregate names only that one (the detail is per field, not a fixed label). */
    @Test
    fun theNotFullDetailNamesEachMissingAggregateAndInvalidScalar() {
        val onlyReactions = wireReply(extraKeys = """"senderDescription":null,""")
        assertEquals(
            CanonicalMessageV2EnvelopeConversion.NotFull("missing=Reactions"),
            onlyReactions.toCanonicalMessageV2Conversion(partition, replyCount = 0),
        )
        val blankName = onlyReactions.copy(
            row = onlyReactions.row.copy(senderName = ""),
            presentAggregates = MessageAggregateField.entries.toList(),
        )
        assertEquals(
            CanonicalMessageV2EnvelopeConversion.NotFull("invalid=senderName"),
            blankName.toCanonicalMessageV2Conversion(partition, replyCount = 0),
        )
        assertIs<CanonicalMessageV2EnvelopeConversion.Converted>(
            blankName.copy(row = blankName.row.copy(senderName = "Alice"))
                .toCanonicalMessageV2Conversion(partition, replyCount = 0),
        )
        // Task #14a, realtime only: a missing allowed aggregate converts as unknown; an invalid scalar never does.
        val partial = assertIs<CanonicalMessageV2EnvelopeConversion.Converted>(
            onlyReactions.toCanonicalMessageV2Conversion(partition, replyCount = 0, allowPartial = true),
        )
        assertEquals(setOf(MessageAggregateField.Reactions), partial.unknownFields)
        assertEquals(emptyList(), partial.envelope.fact.reactions)
        assertEquals(
            CanonicalMessageV2EnvelopeConversion.NotFull("invalid=senderName"),
            onlyReactions.copy(row = onlyReactions.row.copy(senderName = ""))
                .toCanonicalMessageV2Conversion(partition, replyCount = 0, allowPartial = true),
        )
    }

    /**
     * The server's thread-reply `message:new` payload (public parent), decoded by the production decoder;
     * [channel]: the same payload as a top-level message of `channel-1`.
     */
    private fun wireReply(
        extraKeys: String,
        mentionsKey: String = "\"mentions\":[],",
        channel: Boolean = false,
    ): CanonicalMessageFoldInput {
        val channelId = if (channel) "channel-1" else "thread-1"
        val context = if (channel) {
            """{"channelType":"channel"}"""
        } else {
            """{"channelType":"thread","parentMessageId":"root-1","parentChannelId":"channel-1","parentChannelType":"channel"}"""
        }
        val raw = Json.parseToJsonElement(
            """
            {"id":"reply-2","seq":2,"channelId":"$channelId","senderType":"user","senderId":"user-1",
             "randomId":"mobile-abc","messageType":"chat","content":"hello from the phone",
             "actionMetadata":null,"threadId":null,"causalActorType":null,"causalActorId":null,
             "systemSubtype":null,"taskStatus":null,"taskNumber":null,"taskAssigneeType":null,
             "taskAssigneeId":null,"taskClaimedAt":null,"taskCompletedAt":null,
             "createdAt":"2026-10-18T06:30:54.100Z","updatedAt":"2026-10-18T06:30:54.100Z",
             "senderName":"alice","senderMembershipStatus":"active","attachments":[],$mentionsKey
             $extraKeys
             "conversationContext":$context}
            """.trimIndent(),
        ).jsonObject
        val carry = requireNotNull(decodeCanonicalMessageWire(raw, ProductionFace.MessageNew))
        return CanonicalMessageFoldInput.from(CanonicalMessageFoldKind.New, carry)
    }

    private inner class Fixture(var enabled: Boolean = true, val store: FakeStore = FakeStore()) {
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val logs = mutableListOf<String>()
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { this.enabled },
            generationEngine = engine,
            logger = { logs += it },
        )

        suspend fun resetPartition() = engine.clearPartition(partition)

        suspend fun accept(
            input: CanonicalMessageFoldInput,
            transportEpoch: Long? = null,
        ): CanonicalMessageV2CanaryOutcome =
            canary.accept(partition = partition, foldInput = input, frameTransportEpoch = transportEpoch)

        private var connected = false

        /** The socket (re)connected as [epoch]; [roomsJoined]: the server's `rooms:joined` arrived on it. */
        suspend fun connect(epoch: Long, roomsJoined: Boolean = true) {
            canary.noteTransportEpoch(partition, epoch)
            if (roomsJoined) canary.noteRoomsJoined(partition, epoch)
            connected = true
        }

        /** The user opens the channel: its enter page (one message at [seq]) commits as the baseline. */
        suspend fun enter(seq: Long, channelId: String = "channel-1", beforeCommit: suspend () -> Unit = {}) {
            if (!connected) connect(1L)
            val target = SyncScopeKey(partition, channelId, "channel")
            val envelope = requireNotNull(input("e$seq", seq, channelId).toCanonicalMessageV2Envelope(partition, replyCount = 0))
            val frame = CanonicalMessageV2SnapshotFrame(target, listOf(envelope), cursor(seq))
            val baseline = requireNotNull(canary.beginEnterBaseline(partition, channelId, nonMember = false))
            beforeCommit()
            engine.withScopeWriter(target) {
                assertEquals(CanonicalMessageV2CommitResult.Committed, engine.applySnapshot(frame))
                canary.onEnterCommitted(baseline, frame)
            }
        }

        /**
         * Task #14a: the user opens reply thread [threadId] (root `root-1` in `channel-1`): its enter page (one
         * reply at [seq]) commits as the thread scope's baseline under the server-confirmed parent (the window
         * itself always ends discontinuous: `thread_delivery_unconfirmed`).
         */
        suspend fun enterThread(
            seq: Long,
            threadId: String = "thread-1",
            nonMember: Boolean = false,
            /** The parent channel the committed cursor stores (the baseline always names `channel-1`). */
            cursorParentId: String = "channel-1",
            /** Task #14c: the server-confirmed parent kind the committed cursor stores. */
            parentKind: String = "channel",
            /** Task #14c: the directory says the user is not a member of the parent channel. */
            parentNonMember: Boolean = false,
            beforeCommit: suspend () -> Unit = {},
        ) {
            if (!connected) connect(1L)
            val target = SyncScopeKey(partition, threadId, "thread")
            val envelope = requireNotNull(
                reply("e$seq", seq, threadId, parentKind = parentKind).toCanonicalMessageV2Envelope(partition, replyCount = 0),
            )
            val frame = CanonicalMessageV2SnapshotFrame(
                target,
                listOf(envelope),
                cursor(seq).copy(parentScopeKind = parentKind, parentScopeId = cursorParentId, rootMessageId = "root-1"),
            )
            val baseline = requireNotNull(
                canary.beginEnterBaseline(
                    partition,
                    threadId,
                    nonMember = nonMember,
                    replyParentScopeId = "channel-1",
                    parentNonMember = parentNonMember,
                ),
            )
            beforeCommit()
            engine.withScopeWriter(target) {
                assertEquals(CanonicalMessageV2CommitResult.Committed, engine.applySnapshot(frame))
                canary.onEnterCommitted(baseline, frame)
            }
        }

        /** Task #14c: the server answered this socket's `join:channel` for [threadId] on [epoch]. */
        suspend fun joinAck(epoch: Long, joined: Boolean = true, threadId: String = "thread-1") {
            canary.noteChannelJoinAck(partition, threadId, epoch, joined)
        }

        /** A reply's `message:new`: it lives in its thread channel and names its parent in the context. */
        fun reply(
            id: String,
            seq: Long,
            threadId: String = "thread-1",
            parentKind: String = "channel",
            parentId: String = "channel-1",
            root: String = "root-1",
        ): CanonicalMessageFoldInput {
            val base = input(id, seq, channelId = threadId)
            return base.copy(
                row = base.row.copy(
                    conversationContext = CanonicalConversationContextWire(
                        channelType = "thread",
                        parentChannelId = parentId,
                        parentChannelType = parentKind,
                        parentMessageId = root,
                    ),
                ),
            )
        }

        fun input(
            id: String,
            seq: Long,
            channelId: String = "channel-1",
            kind: CanonicalMessageFoldKind = CanonicalMessageFoldKind.New,
        ): CanonicalMessageFoldInput =
            CanonicalMessageFoldInput(
                kind = kind,
                row = CanonicalMessageRow(
                    id = id,
                    channelId = channelId,
                    seq = seq,
                    senderType = "user",
                    senderId = "user-1",
                    content = "body-$id",
                    messageType = "chat",
                    createdAt = "2026-07-17T00:00:00.000Z",
                    senderName = "Alice",
                    conversationContext = CanonicalConversationContextWire("channel"),
                ),
                presentAggregates = MessageAggregateField.entries.toList(),
            )
    }

    private class FakeStore : CanonicalMessageV2Store {
        var snapshotCalls = 0
        var incrementalCalls = 0
        var lastIncremental: CanonicalMessageV2IncrementalFrame? = null

        /** Every read the canary could make on a frame's display path. */
        var reads = 0
        private val restored = mutableMapOf<SyncScopeKey, RestoredCanonicalMessageV2Scope>()

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotCalls += 1
            restored[frame.scope] = RestoredCanonicalMessageV2Scope(frame.envelopes, frame.cursor)
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalCalls += 1
            lastIncremental = frame
            val previous = restored[frame.scope]?.envelopes.orEmpty().associateBy { it.fact.id }
            restored[frame.scope] = RestoredCanonicalMessageV2Scope(
                envelopes = (previous + frame.envelopes.associateBy { it.fact.id }).values.toList(),
                cursor = frame.cursor,
            )
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope {
            reads += 1
            return restored[scope] ?: RestoredCanonicalMessageV2Scope(emptyList(), null)
        }

        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> {
            reads += 1
            return emptyList()
        }

        override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? {
            reads += 1
            return restored[scope]?.cursor
        }

        override suspend fun containsFact(scope: SyncScopeKey, factId: String): Boolean {
            reads += 1
            return restored[scope]?.envelopes.orEmpty().any { it.fact.id == factId }
        }

        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> {
            restored.remove(scope)
            return setOf(scope)
        }

        override suspend fun clearPartition(partition: DurablePartitionKey) {
            restored.keys.removeAll { it.partition == partition }
        }
    }

    private fun cursor(seq: Long) = PersistedCursor(
        latestSeq = seq,
        oldestLoadedSeq = seq,
        newestLoadedSeq = seq,
        hasLoadedOldest = false,
        hasLoadedNewest = true,
    )
}
