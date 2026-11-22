package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.commitEnterBaselineForTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun aScopeWithoutAWindowIsDisplayOnlyWithoutTouchingTheStore() = runBlocking {
        val fixture = Fixture()
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m1", 1L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(0, fixture.store.snapshotCalls)
        assertEquals(0, fixture.store.incrementalCalls)
        assertEquals(0, fixture.store.reads, "the display path never waits for a store lookup")
    }

    @Test
    fun anEnteredWindowAppendsTheNextNewIncrementally() = runBlocking {
        val fixture = Fixture()
        fixture.enter(1L)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(1, fixture.store.snapshotCalls)
        assertEquals(1, fixture.store.incrementalCalls)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, fixture.canary.continuityOf(scope))
    }

    @Test
    fun everyWindowedChannelIsWrittenNotOnlyOne() = runBlocking {
        val fixture = Fixture()
        fixture.enter(1L)
        fixture.enter(1L, channelId = "channel-2")

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("m2", 2L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(fixture.input("n2", 2L, channelId = "channel-2")))
        assertEquals(2, fixture.store.incrementalCalls)
    }

    @Test
    fun aThreadReplyIsNeverWrittenIntoAChannelWindow() = runBlocking {
        val fixture = Fixture()
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
        val fixture = Fixture()
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
    fun aRestoredWindowIsDisplayOnlyAfterProcessRestartUntilOpened() = runBlocking {
        val store = FakeStore()
        val beforeRestart = Fixture(store = store)
        beforeRestart.enter(1L)
        beforeRestart.accept(beforeRestart.input("m2", 2L))
        assertEquals(1, store.incrementalCalls)

        // New process: same durable store, fresh canary and engine. Frames may have been missed meanwhile.
        val afterRestart = Fixture(store = store)
        val readsBefore = store.reads
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, afterRestart.accept(afterRestart.input("m4", 4L)))
        assertEquals(1, store.incrementalCalls, "a restored window is never appended onto")
        assertEquals(readsBefore, store.reads, "and the frame never waited for a store lookup")

        afterRestart.enter(4L)
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, afterRestart.accept(afterRestart.input("m5", 5L)))
        assertEquals(2, store.incrementalCalls)
    }

    @Test
    fun aCatchUpBaselineIsRegisteredOnlyForAMemberWhoseConnectionSawRoomsJoined() = runBlocking {
        val fixture = Fixture()
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
    fun aLateFrameOfAnOlderConnectionIsShownButNeverWrittenAndChangesNothing() = runBlocking {
        val fixture = Fixture()
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


    // ---- task #14a: a reply thread's window is baselined like a channel's ---------------------------------
    //
    // Without a positive per-thread delivery signal (no connect-time join of thread rooms; task #14c: no
    // `channel:joined` for the thread before the enter) every reply-thread enter commit ends
    // `reason=thread_delivery_unconfirmed` and realtime replies are display only. Each case is paired with the
    // identical frame on a CHANNEL window, which is written.

    private val threadUnconfirmedLine =
        "canonical_v2_canary enter=committed server=server-1 scope=thread-1 scopeKind=thread epoch=1 buffered=0 " +
            "nonMember@request=false nonMember@commit=false parentNonMember@request=false parentNonMember@commit=false " +
            "continuity=discontinuous reason=thread_delivery_unconfirmed"

    @Test
    fun aReplyOfAnEnteredThreadIsDisplayOnlyBecauseThreadDeliveryIsUnconfirmed() = runBlocking {
        val fixture = Fixture()
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

    // ---- task #14c: a public-parent thread whose room join was acked before the enter is continuous ----
    //
    // Each case is paired with the identical sequence differing only in the signal under test.

    private fun threadEnterReason(fixture: Fixture, threadId: String = "thread-1"): String =
        fixture.logs.last { it.startsWith("canonical_v2_canary enter=committed server=server-1 scope=$threadId scopeKind=thread") }
            .substringAfter("continuity=")

    @Test
    fun aPublicParentThreadAckedBeforeTheEnterIsContinuousAndWritesTheProductionReplyPartial() = runBlocking {
        fun run(ack: Boolean): Fixture = runBlocking {
            val fixture = Fixture()
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

    // ---- task #14c: a late ack requests ONE catch-up whose baseline (registered after the ack) is continuous ----

    private fun threadCatchUpReason(fixture: Fixture): String =
        fixture.logs.last { it.startsWith("canonical_v2_canary catch_up=committed server=server-1 scope=thread-1 scopeKind=thread") }
            .substringAfter("continuity=")

    /** Runs [block] while collecting [CanonicalMessageV2ProductionCanary.threadJoinConfirmations] (an open page). */
    private suspend fun <T> withOpenPage(
        fixture: Fixture,
        block: suspend (received: List<CanonicalMessageV2ThreadJoinConfirmed>) -> T,
    ): T = coroutineScope {
        val received = mutableListOf<CanonicalMessageV2ThreadJoinConfirmed>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.canary.threadJoinConfirmations.collect { received += it }
        }
        try {
            block(received)
        } finally {
            job.cancel()
        }
    }

    private suspend fun settle() = repeat(5) { yield() }

    @Test
    fun anAckDuringTheEnterFetchRequestsOneCatchUpThatCommitsContinuous() = runBlocking {
        val fixture = Fixture()
        withOpenPage(fixture) { received ->
            fixture.connect(1L)
            fixture.enterThread(1L, beforeCommit = { fixture.joinAck(1L) })
            settle()
            assertEquals("discontinuous reason=thread_join_ack_after_request", threadEnterReason(fixture))
            assertEquals(
                listOf(CanonicalMessageV2ThreadJoinConfirmed(partition, "thread-1", "channel-1", 1L)),
                received,
            )
            assertTrue(
                fixture.logs.any {
                    it == "canonical_v2_canary thread_join_confirmed server=server-1 scope=thread-1 scopeKind=thread " +
                        "parent=channel-1 epoch=1 trigger=enter_commit catchUp=requested subscribers=1"
                },
                fixture.logs.toString(),
            )
            // The page's catch-up: registered after the ack, so the same rule makes it continuous.
            fixture.catchUpThread(1L)
            assertEquals("continuous", threadCatchUpReason(fixture))
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, fixture.accept(wireReply(extraKeys = "")))
            assertTrue(
                fixture.logs.any {
                    it == "canonical_v2_canary written server=server-1 scope=thread-1 source=incremental scopeKind=thread " +
                        "partial=SenderDescription,Reactions"
                },
                fixture.logs.toString(),
            )
            settle()
            assertEquals(1, received.size, "the catch-up's commit requests nothing more")
        }
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
        val fixture = Fixture()
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

    private inner class Fixture(val store: FakeStore = FakeStore()) {
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val logs = mutableListOf<String>()
        val canary = CanonicalMessageV2ProductionCanary(
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
            nonMemberAtCommit: Boolean = false,
            parentNonMemberAtCommit: Boolean = false,
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
                canary.onEnterCommitted(
                    baseline,
                    frame,
                    nonMemberAtCommit = nonMemberAtCommit,
                    parentNonMemberAtCommit = parentNonMemberAtCommit,
                )
            }
        }

        /**
         * Task #14c: the thread page's gap catch-up of `thread-1` (as the adjacent-page writer runs it): its baseline
         * is registered before the latest page (one reply at [seq]) commits; [fail] = the fetch failed (abandoned).
         * Returns whether a baseline was registered.
         */
        suspend fun catchUpThread(seq: Long, fail: Boolean = false, beforeCommit: suspend () -> Unit = {}): Boolean {
            val target = threadScope
            val baseline = canary.beginCatchUpBaseline(
                partition,
                target.scopeId,
                nonMember = false,
                replyParentScopeId = "channel-1",
                parentNonMember = false,
            ) ?: return false
            beforeCommit()
            if (fail) {
                canary.abandonEnterBaseline(baseline)
                return true
            }
            val envelope = requireNotNull(reply("c$seq", seq).toCanonicalMessageV2Envelope(partition, replyCount = 0))
            val frame = CanonicalMessageV2SnapshotFrame(
                target,
                listOf(envelope),
                cursor(seq).copy(parentScopeKind = "channel", parentScopeId = "channel-1", rootMessageId = "root-1"),
            )
            engine.withScopeWriter(target) {
                assertEquals(CanonicalMessageV2CommitResult.Committed, engine.applySnapshot(frame))
                canary.onEnterCommitted(baseline, frame)
            }
            return true
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
