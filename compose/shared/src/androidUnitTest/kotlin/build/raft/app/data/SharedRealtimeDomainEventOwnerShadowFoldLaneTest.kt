/* T2 REDs for legacy partition denial plus the existing reaction-shadow preservation gates. */
package build.raft.app.data

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreReactionShadowLane
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PartitionDenialReason
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

    private class FakeFlagsApi(private val enabled: Boolean) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
            )
    }

    private fun resolvedFlags(enabled: Boolean): SharedServerFeatureFlags {
        val flags = SharedServerFeatureFlags(FakeFlagsApi(enabled))
        runBlocking { flags.prefetch(ServerId(serverId)) }
        return flags
    }

    private fun resolvedReactionFlags(enabled: Boolean): SharedServerFeatureFlags {
        val flags = SharedServerFeatureFlags(
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
                    )
            },
        )
        runBlocking { flags.prefetch(ServerId(serverId)) }
        return flags
    }

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

    private fun messageUpdatedWithCanonical(
        id: String,
        seq: Long,
        content: String = "updated-$id",
    ): MessageUpdated = MessageUpdated(
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
            row = canonicalRow(id, seq, content),
            presentAggregates = emptySet<MessageAggregateField>(),
        ),
    )

    private fun recordingLane(logs: MutableList<String>) = SyncCoreMessageShadowFoldLane(
        featureFlags = resolvedFlags(enabled = true),
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
    fun nonMessageAndMessageWithoutCanonicalCarryProduceZeroDenialAndZeroPersist() {
        val logs = mutableListOf<String>()
        val lane = recordingLane(logs)
        val (owner, homeEvents) = ownerWith(lane)
        val heartbeat = Heartbeat(seq = 1L)
        val messageWithoutCarry = messageNewWithCanonical(id = "m-no-carry", seq = 2L).copy(decodedCanonical = null)
        val resume = SyncResumeResponse(messages = emptyList(), currentSeq = 2L, hasMore = false)

        owner.accept(serverId, heartbeat, source = "app_shell")
        owner.accept(serverId, messageWithoutCarry, source = "app_shell")
        owner.accept(serverId, resume, source = "app_shell")

        assertTrue(logs.none { it.contains("sync_core_partition_persist_denied") })
        assertEquals<List<Pair<String, RealtimeEvent>>>(
            listOf(serverId to messageWithoutCarry, serverId to resume),
            homeEvents,
        )
    }

    @Test
    fun legacyOwnerTreatsCanonicalMessageUpdatedAsEligibleAndDeniesExactlyOnce() {
        val logs = mutableListOf<String>()
        val lane = recordingLane(logs)
        val (owner, homeEvents) = ownerWith(lane)
        val event = messageUpdatedWithCanonical(id = "m-updated", seq = 3L)

        owner.accept(serverId, event, source = "app_shell")

        assertEquals<List<Pair<String, RealtimeEvent>>>(listOf(serverId to event), homeEvents)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("sync_core_partition_persist_denied"))
        assertTrue(logs.single().contains("deniedFrameCount=1"))
    }

    @Test
    fun namedDenialCarriesBoundedFrameCount() {
        val logs = mutableListOf<String>()
        val lane = recordingLane(logs)

        val one = lane.partitionDenied(serverId, PartitionDenialReason.PrincipalUnavailable)
        assertEquals(
            "PartitionDenied(reason=PrincipalUnavailable, deniedFrameCount=1)",
            one.toString(),
        )
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("deniedFrameCount=1"))
    }

    @Test
    fun countedDenialEmitsOneBoundedLogForTwoEligibleFrames() {
        val logs = mutableListOf<String>()
        val lane = recordingLane(logs)

        val countedMethod = lane.javaClass.getDeclaredMethod(
            "partitionDenied",
            String::class.java,
            PartitionDenialReason::class.java,
            Int::class.javaPrimitiveType,
        )
        val two = countedMethod.invoke(lane, serverId, PartitionDenialReason.PrincipalUnavailable, 2)
        assertEquals("PartitionDenied(reason=PrincipalUnavailable, deniedFrameCount=2)", two?.toString())
        assertEquals(1, logs.size, "one denied batch must emit exactly one diagnostic")
        assertTrue(logs.single().contains("deniedFrameCount=2"))
        assertFalse(logs.single().contains("messageId="))
        assertFalse(logs.single().contains("content="))
        assertFalse(logs.single().contains("principalId="))
    }

    @Test
    fun shadowLaneIsInvisibleToSinksRegardlessOfFlag() {
        val event = messageNewWithCanonical(id = "m1", seq = 1)

        val laneOn = SyncCoreMessageShadowFoldLane(
            featureFlags = resolvedFlags(enabled = true),
            logger = {},
        )
        val (ownerOn, homeEventsOn) = ownerWith(laneOn)
        ownerOn.accept(serverId, event, source = "app_shell")

        val laneOff = SyncCoreMessageShadowFoldLane(
            featureFlags = resolvedFlags(enabled = false),
            logger = {},
        )
        val (ownerOff, homeEventsOff) = ownerWith(laneOff)
        ownerOff.accept(serverId, event, source = "app_shell")

        // The sink-facing output must be byte-identical whether the shadow lane is on or off.
        assertEquals(homeEventsOff, homeEventsOn)
        assertEquals<List<Pair<String, RealtimeEvent>>>(listOf(serverId to event), homeEventsOn)
    }

    @Test
    fun normalizedReactionShadowRunsBeforeSinkAndRemainsInvisible() {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
        )
        val logs = mutableListOf<String>()
        val callOrder = mutableListOf<String>()
        owner.installReactionShadowLane(
            SyncCoreReactionShadowLane(
                featureFlags = resolvedReactionFlags(enabled = true),
                logger = { message ->
                    logs += message
                    callOrder += "shadow"
                },
            ),
        )
        val homeEvents = mutableListOf<Pair<String, RealtimeEvent>>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { server, event ->
                    callOrder += "sink"
                    homeEvents += server to event
                },
            ),
        )
        val event = messageNewWithCanonical(
            id = "m-v2",
            seq = 1,
            reactions = listOf(
                CanonicalReactionWire("like", 2, listOf("u1", "u1"), listOf("Alice", "Other")),
            ),
        )

        owner.accept(serverId, event, source = "app_shell")

        assertEquals<List<Pair<String, RealtimeEvent>>>(listOf(serverId to event), homeEvents)
        assertEquals(listOf("shadow", "sink"), callOrder)
        assertEquals(1, logs.size, "malformed shadow input should diagnose exactly once")
        assertTrue(logs.single().contains("result=violations"))
    }

    @Test
    fun normalizedReactionShadowLoggerFailureCannotBlockLegacySink() {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore()),
        )
        owner.installReactionShadowLane(
            SyncCoreReactionShadowLane(
                featureFlags = resolvedReactionFlags(enabled = true),
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
    fun pendingV2CanaryProjectsExactlyOnceAfterCurrentGrantCheck() = runBlocking {
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
            CanonicalMessageV2OwnerGateway { _, _, _ -> CanonicalMessageV2CanaryOutcome.Pending },
        )
        owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { _, event -> projected += event },
            ),
        )

        val event = messageNewWithCanonical("v2-pending", 1L)

        // Task #168: Pending = the write belongs to a background snapshot. Displayed now, the same way.
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

    @Test
    fun everyEntryHandsTheFramesTransportEpochToTheDurableCanary() = runBlocking {
        val seen = mutableListOf<Long?>()
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
            CanonicalMessageV2OwnerGateway { _, _, transportEpoch ->
                seen += transportEpoch
                CanonicalMessageV2CanaryOutcome.Committed
            },
        )

        owner.acceptProcessMutation(testGrant(), messageNewWithCanonical("p", 1L), "test", presenceTransportEpoch = 7L)
        owner.accept(testGrant(), messageNewWithCanonical("s", 2L), "test", transportEpoch = 8L)
        owner.acceptHomeUnreadProjection(testGrant(), messageNewWithCanonical("b", 3L), "test", transportEpoch = 9L)
        owner.accept(testGrant(), messageNewWithCanonical("n", 4L), "test")

        assertEquals(listOf<Long?>(7L, 8L, 9L, null), seen)
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
