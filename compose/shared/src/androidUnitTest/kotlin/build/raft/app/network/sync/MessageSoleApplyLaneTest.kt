/*
 * Sole-apply slice-2 stage A — actor-level test for MessageSoleApplyLane: the thin per-scope actor
 * wrapping the pure engine. Commit-first delivery (fan-out only after the one-txn adapter returns),
 * post-failure darkness + single-flight rebaseline surfacing, and rebaseline recovery. Plain
 * coroutines (Unconfined — the mailbox consumer runs inline on the sender), NO SQLite: the adapter is
 * an in-memory fake. Production never constructs a lane in slice-2 (see the engine test's gate 8);
 * this constructs it directly, the stage-A test-only path.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageSoleApplyLaneTest {

    private val serverId = "server-1"
    private val channelId = "channel-1"
    private val scopeKey = SyncScopeKey(serverId = serverId, scopeId = channelId, scopeKind = "channel")

    /** In-memory one-txn stand-in: records applied frames; can be armed to fail the next N commits. */
    private class RecordingAdapter : FoldPersistenceAdapter {
        val applied = mutableListOf<FoldPersistFrame>()
        var failNextCommits = 0

        override suspend fun applyFrame(frame: FoldPersistFrame) {
            if (failNextCommits > 0) {
                failNextCommits--
                throw RuntimeException("commit failed (injected)")
            }
            applied += frame
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
            RestoredFoldState(emptyList(), null)
    }

    private fun canonicalRow(id: String, seq: Long) = CanonicalMessageRow(
        id = id,
        channelId = channelId,
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-13T00:00:0${seq}Z",
        senderName = "Alice",
    )

    private fun frame(id: String, seq: Long): MessageSoleApplyLane.Frame {
        val event = MessageNew(
            message = Message(
                id = MessageId(id),
                threadId = ThreadId(channelId),
                channelId = ChannelId(channelId),
                seq = seq,
                author = MemberProfile(MemberId("u1"), "Alice", "alice", MemberKind.Human),
                timestampLabel = "now",
                body = "body-$id",
            ),
        )
        return MessageSoleApplyLane.Frame(
            event = event,
            decoded = CanonicalMessageCarry(
                row = canonicalRow(id, seq),
                presentAggregates = emptySet<MessageAggregateField>(),
            ),
            updated = false,
            epoch = null,
        )
    }

    private fun laneWith(
        adapter: FoldPersistenceAdapter,
        fannedOut: MutableList<RealtimeEvent>,
        rebaselines: MutableList<SyncScopeKey>,
    ): MessageSoleApplyLane = MessageSoleApplyLane(
        scopeKey = scopeKey,
        adapter = adapter,
        fanOut = { fannedOut += it },
        requestRebaseline = { rebaselines += it },
        laneScope = CoroutineScope(Dispatchers.Unconfined),
        logger = {},
    )

    @Test
    fun commitSuccessFansOutInOrder_afterTheOneTxnCommit() {
        val adapter = RecordingAdapter()
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<SyncScopeKey>()
        val lane = laneWith(adapter, fannedOut, rebaselines)

        lane.enqueue(frame("m1", 1))
        lane.enqueue(frame("m2", 2))
        lane.enqueue(frame("m3", 3))

        // Commit-first, exact enqueue order, no drop/double.
        assertEquals(
            listOf("m1", "m2", "m3"),
            fannedOut.map { (it as MessageNew).message.id.value },
        )
        assertEquals(3, adapter.applied.size, "every fan-out was preceded by its own one-txn commit")
        assertEquals(
            listOf(1L, 2L, 3L),
            adapter.applied.map { it.cursor.latestSeq },
            "the committed cursor advances with each frame",
        )
        assertTrue(rebaselines.isEmpty(), "healthy commits surface no rebaseline request")
        lane.shutdown()
    }

    @Test
    fun commitThrow_noFanOut_rebaselineCallbackInvoked_recoveryViaRebaseline() {
        val adapter = RecordingAdapter().apply { failNextCommits = 1 }
        val fannedOut = mutableListOf<RealtimeEvent>()
        val rebaselines = mutableListOf<SyncScopeKey>()
        val lane = laneWith(adapter, fannedOut, rebaselines)

        lane.enqueue(frame("m1", 1))
        assertEquals(emptyList(), fannedOut, "a failed commit must not fan out")
        assertEquals(listOf(scopeKey), rebaselines, "the failure surfaces exactly one scope-qualified rebaseline")

        // Post-failure frames stay dark and do not re-request (single-flight, engine-enforced).
        lane.enqueue(frame("m2", 2))
        assertEquals(emptyList(), fannedOut, "no post-failure leak")
        assertEquals(1, rebaselines.size)

        // The host resolves the rebaseline: held frames are superseded (never replayed to sinks);
        // the scope is healthy again and fresh frames flow commit-first.
        lane.rebaselineResolved(success = true)
        assertEquals(emptyList(), fannedOut, "superseded frames never surface")
        lane.enqueue(frame("m3", 3))
        assertEquals(listOf("m3"), fannedOut.map { (it as MessageNew).message.id.value })
        assertEquals(listOf(3L), adapter.applied.map { it.cursor.latestSeq })
        lane.shutdown()
    }
}
