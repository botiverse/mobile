/* T2 REDs for legacy partition denial plus the existing reaction-shadow preservation gates. */
package build.raft.app.data

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedRealtimeDomainEventOwnerShadowFoldLaneTest {

    private val serverId = "server-1"
    private val channelId = "channel-1"
    private val scopeKey = SyncScopeKey(
        partition = DurablePartitionKey(serverId, "principal-1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
        scopeId = channelId,
        scopeKind = "channel",
    )

    private fun ownerWith(lane: SyncCoreMessageShadowFoldLane): Pair<SharedRealtimeDomainEventOwner, MutableList<Pair<String, RealtimeEvent>>> {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
        )
        owner.installMessageShadowFoldLane(lane)
        val homeEvents = mutableListOf<Pair<String, RealtimeEvent>>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { server, event -> homeEvents += server to event },
            ),
        )
        return owner to homeEvents
    }

    private fun canonicalRow(
        id: String,
        seq: Long,
        content: String = "body-$id",
        reactions: List<CanonicalReactionWire> = emptyList(),
    ) = CanonicalMessageRow(
        id = id,
        channelId = channelId,
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = content,
        messageType = "chat",
        createdAt = "2026-07-12T00:00:0${seq}Z",
        senderName = "Alice",
        reactions = reactions,
    )

    private fun messageNewWithCanonical(
        id: String,
        seq: Long,
        content: String = "body-$id",
        reactions: List<CanonicalReactionWire> = emptyList(),
    ): MessageNew =
        MessageNew(
            message = Message(
                id = MessageId(id),
                threadId = ThreadId(channelId),
                channelId = ChannelId(channelId),
                seq = seq,
                author = MemberProfile(MemberId("u1"), "Alice", "alice", MemberKind.Human),
                timestampLabel = "now",
                body = content,
            ),
            decodedCanonical = CanonicalMessageCarry(
                row = canonicalRow(id, seq, content, reactions),
                presentAggregates = emptySet<MessageAggregateField>(),
            ),
        )

    private fun recordingLane(logs: MutableList<String>) = SyncCoreMessageShadowFoldLane(
        logger = logs::add,
    )

    @Test
    fun legacyOwnerDeniesExactlyOneCanonicalFrameWithoutPersistingAndKeepsSinkByteEqual() {
        val logs = mutableListOf<String>()
        val lane = recordingLane(logs)
        val (owner, homeEvents) = ownerWith(lane)
        val event = messageNewWithCanonical(id = "m1", seq = 1)

        owner.accept(serverId, event, source = "app_shell")

        assertEquals<List<Pair<String, RealtimeEvent>>>(
            listOf(serverId to event),
            homeEvents,
            "denial must not alter legacy sink delivery",
        )
        assertEquals(1, logs.size, "one eligible canonical frame must produce one bounded denial")
        assertTrue(logs.single().contains("sync_core_partition_persist_denied"))
        assertTrue(logs.single().contains("reason=principal_unavailable"))
        assertTrue(logs.single().contains("consumer=sync-core.messages.v1"))
        assertTrue(logs.single().contains("deniedFrameCount=1"))
        assertFalse(logs.single().contains("m1"), "bounded denial must not include message identity")
        assertFalse(logs.single().contains("body-m1"), "bounded denial must not include message content")
        assertFalse(logs.single().contains("principal-1"), "bounded denial must not include principal")
    }

    @Test
    fun normalizedReactionShadowLoggerFailureCannotBlockLegacySink() {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
        )
        owner.installReactionShadowLane(
            SyncCoreReactionShadowLane(
                logger = { error("diagnostics unavailable") },
            ),
        )
        val homeEvents = mutableListOf<Pair<String, RealtimeEvent>>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { server, event -> homeEvents += server to event },
            ),
        )
        val event = messageNewWithCanonical(
            id = "m-v2-fail-open",
            seq = 1,
            reactions = listOf(
                CanonicalReactionWire("like", 2, listOf("u1", "u1"), listOf("Alice", "Other")),
            ),
        )

        owner.accept(serverId, event, source = "app_shell")

        assertEquals<List<Pair<String, RealtimeEvent>>>(listOf(serverId to event), homeEvents)
    }

    @Test
    fun committedV2CanaryProjectsExactlyOnceAfterCurrentGrantCheck() = runBlocking {
        var currentGrantChecks = 0
        var legacyRunnerChecks = 0
        val projected = mutableListOf<RealtimeEvent>()
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { _, block ->
                currentGrantChecks += 1
                block()
                true
            },
            runWithCurrentMessageGrant = { _, block ->
                legacyRunnerChecks += 1
                block(CurrentMessageCommitContext(scopeKey.partition))
                true
            },
        )
        owner.installCanonicalMessageV2OwnerGateway(
            CanonicalMessageV2OwnerGateway { _, _, _ -> CanonicalMessageV2CanaryOutcome.Committed },
        )
        owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { _, event -> projected += event },
            ),
        )
        val event = messageNewWithCanonical("v2-committed", 1L)

        assertTrue(owner.accept(testGrant(), event, source = "test"))
        // Message storage Phase 3a: the commit is bound in the exact-grant slot; display follows a fresh grant check.
        assertEquals(1, currentGrantChecks)
        assertEquals(1, legacyRunnerChecks)
        assertEquals<List<RealtimeEvent>>(listOf(event), projected)
    }

    @Test
    fun heldV2CanaryStillProjectsExactlyOnceAfterCurrentGrantCheck() = runBlocking {
        var currentGrantChecks = 0
        var legacyRunnerChecks = 0
        val projected = mutableListOf<RealtimeEvent>()
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { _, block ->
                currentGrantChecks += 1
                block()
                true
            },
            runWithCurrentMessageGrant = { _, block ->
                legacyRunnerChecks += 1
                block(CurrentMessageCommitContext(scopeKey.partition))
                true
            },
        )
        owner.installCanonicalMessageV2OwnerGateway(
            CanonicalMessageV2OwnerGateway { _, _, _ ->
                CanonicalMessageV2CanaryOutcome.Held("snapshot_failed")
            },
        )
        owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { _, event -> projected += event },
            ),
        )

        val event = messageNewWithCanonical("v2-held", 1L)

        // Task #168: Held = the durable write failed. The frame is still displayed, through the same
        // post-canary path as a committed one (fresh grant check, no shadow lane).
        assertTrue(owner.accept(testGrant(), event, source = "test"))
        // Message storage Phase 3a: the commit is bound in the exact-grant slot; display follows a fresh grant check.
        assertEquals(1, currentGrantChecks)
        assertEquals(1, legacyRunnerChecks)
        assertEquals<List<RealtimeEvent>>(listOf(event), projected)
    }

    @Test
    fun aThrowingOrStuckDurableCommitNeverBlocksOrSkipsTheDisplay() = runBlocking {
        // Message storage Phase 3a: fail-open. The commit runs first in the exact-grant slot; whatever it does,
        // each frame is displayed exactly once and in arrival order.
        val projected = mutableListOf<RealtimeEvent>()
        val logs = mutableListOf<String>()
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { _, block ->
                block()
                true
            },
            runWithCurrentMessageGrant = { _, block ->
                block(CurrentMessageCommitContext(scopeKey.partition))
                true
            },
        )
        owner.installCanonicalMessageV2OwnerGateway(
            CanonicalMessageV2OwnerGateway { _, foldInput, _ ->
                when (foldInput.row.id) {
                    "throws" -> throw IllegalStateException("disk full")
                    "stuck" -> kotlinx.coroutines.awaitCancellation()
                    else -> CanonicalMessageV2CanaryOutcome.Committed
                }
            },
        )
        owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { _, event -> projected += event },
                log = { logs += it },
            ),
        )
        val events = listOf(
            messageNewWithCanonical("throws", 1L),
            messageNewWithCanonical("stuck", 2L),
            messageNewWithCanonical("ok", 3L),
        )

        events.forEach { assertTrue(owner.accept(testGrant(), it, source = "test")) }

        assertEquals<List<RealtimeEvent>>(events, projected)
        assertTrue(logs.any { "outcome=display_only" in it && "msg=throws" in it && "reason=error=IllegalStateException" in it }, logs.toString())
        assertTrue(logs.any { "outcome=display_only" in it && "msg=stuck" in it && "reason=timeout" in it }, logs.toString())
        assertTrue(logs.none { "msg=ok" in it }, logs.toString())
    }

    @Test
    fun aStaleGrantNeverReachesTheDurableCommit() = runBlocking {
        val committed = mutableListOf<String>()
        val projected = mutableListOf<RealtimeEvent>()
        var current = true
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { _, block ->
                if (current) block()
                current
            },
            runWithCurrentMessageGrant = { _, block ->
                if (current) block(CurrentMessageCommitContext(scopeKey.partition))
                current
            },
        )
        owner.installCanonicalMessageV2OwnerGateway(
            CanonicalMessageV2OwnerGateway { _, foldInput, _ ->
                committed += foldInput.row.id
                CanonicalMessageV2CanaryOutcome.Committed
            },
        )
        owner.registerGuardedSinks(RealtimeGuardedDomainEventSinks(applyHomeShell = { _, event -> projected += event }))

        assertTrue(owner.accept(testGrant(), messageNewWithCanonical("current", 1L), source = "test"))
        current = false
        assertFalse(owner.accept(testGrant(), messageNewWithCanonical("stale", 2L), source = "test"))

        assertEquals(listOf("current"), committed)
        assertEquals(1, projected.size)
    }

    private fun testGrant() = RealtimeGrantRecord(
        slotKey = RealtimeSlotKey(scopeLeaseId = "scope-1", serverId = serverId),
        principalId = "principal-1",
        environmentId = "env-test",
        authGeneration = 1L,
        connectionFamilyId = "family-1",
        connectionEpochId = "epoch-1",
    )
}
