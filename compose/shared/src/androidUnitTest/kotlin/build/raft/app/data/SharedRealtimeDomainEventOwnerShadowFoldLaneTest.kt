/*
 * `sync_core_messages_v0` "sole-apply t0" SHADOW fold+persist lane, at the SharedRealtimeDomainEventOwner
 * accept seam. The lane runs BEFORE the sink fan-out, is INVISIBLE to the sinks (never changes any sink
 * output), and is FAIL-OPEN (a fold/persist error can never block or throw into the legacy accept path).
 * This exercises those four properties on a REAL SQLite backend. Sole-apply stays OFF — this is a
 * shadow-of-record persist only, no read path is touched.
 */
package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.FoldPersistFrame
import build.raft.app.network.sync.FoldPersistenceAdapter
import build.raft.app.network.sync.RestoredFoldState
import build.raft.app.network.sync.SqlDelightFoldPersistStore
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncCoreReactionV2ShadowLane
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedRealtimeDomainEventOwnerShadowFoldLaneTest {

    private val serverId = "server-1"
    private val channelId = "channel-1"
    private val scopeKey = SyncScopeKey(serverId = serverId, scopeId = channelId, scopeKind = "channel")

    private class FakeFlagsApi(private val enabled: Boolean) : SlockFeatureFlagsApi {
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

    private fun resolvedV2Flags(enabled: Boolean): SharedServerFeatureFlags {
        val flags = SharedServerFeatureFlags(
            object : SlockFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_V2_NORMALIZED_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
                    )
            },
        )
        runBlocking { flags.prefetch(ServerId(serverId)) }
        return flags
    }

    private fun freshDatabase(): SlockSqlDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        build.raft.app.shared.data.local.SlockDatabase.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        return SlockSqlDatabase(driver)
    }

    private fun ownerWith(lane: SyncCoreMessageShadowFoldLane): Pair<SharedRealtimeDomainEventOwner, MutableList<Pair<String, RealtimeEvent>>> {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore()),
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
        reactions: List<CanonicalReactionWire> = emptyList(),
    ) = CanonicalMessageRow(
        id = id,
        channelId = channelId,
        seq = seq,
        senderType = "human",
        senderId = "u1",
        content = "body-$id",
        messageType = "chat",
        createdAt = "2026-07-12T00:00:0${seq}Z",
        senderName = "Alice",
        reactions = reactions,
    )

    private fun messageNewWithCanonical(
        id: String,
        seq: Long,
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
                body = "body-$id",
            ),
            decodedCanonical = CanonicalMessageCarry(
                row = canonicalRow(id, seq, reactions),
                presentAggregates = emptySet<MessageAggregateField>(),
            ),
        )

    private fun persistedFactIds(db: SlockSqlDatabase): List<String> = runBlocking {
        SqlDelightFoldPersistStore(db, now = { 100L }).readFacts(scopeKey).map { it.id }
    }

    @Test
    fun persistsFactToStoreWhenFlagOn() {
        val db = freshDatabase()
        val lane = SyncCoreMessageShadowFoldLane.create(
            featureFlags = resolvedFlags(enabled = true),
            sqlDatabase = db,
            now = { 100L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            logger = {},
        )
        val (owner, homeEvents) = ownerWith(lane)

        owner.accept(serverId, messageNewWithCanonical(id = "m1", seq = 1), source = "app_shell")

        assertEquals(listOf("m1"), persistedFactIds(db), "flag-on shadow lane must persist the canonical fact")
        // Shadow is invisible: the legacy sink still saw the event exactly once.
        assertEquals(1, homeEvents.size)
    }

    @Test
    fun shadowLaneIsInvisibleToSinksRegardlessOfFlag() {
        val event = messageNewWithCanonical(id = "m1", seq = 1)

        val laneOn = SyncCoreMessageShadowFoldLane.create(
            featureFlags = resolvedFlags(enabled = true),
            sqlDatabase = freshDatabase(),
            now = { 100L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            logger = {},
        )
        val (ownerOn, homeEventsOn) = ownerWith(laneOn)
        ownerOn.accept(serverId, event, source = "app_shell")

        val laneOff = SyncCoreMessageShadowFoldLane.create(
            featureFlags = resolvedFlags(enabled = false),
            sqlDatabase = freshDatabase(),
            now = { 100L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            logger = {},
        )
        val (ownerOff, homeEventsOff) = ownerWith(laneOff)
        ownerOff.accept(serverId, event, source = "app_shell")

        // The sink-facing output must be byte-identical whether the shadow lane is on or off.
        assertEquals(homeEventsOff, homeEventsOn)
        assertEquals<List<Pair<String, RealtimeEvent>>>(listOf(serverId to event), homeEventsOn)
    }

    @Test
    fun failOpenWhenPersistThrowsStillDeliversToSinks() {
        val throwingAdapter = object : FoldPersistenceAdapter {
            override suspend fun applyFrame(frame: FoldPersistFrame): Unit = throw RuntimeException("boom")
            override suspend fun restore(scope: SyncScopeKey): RestoredFoldState = RestoredFoldState(emptyList(), null)
        }
        val loggedFailures = mutableListOf<String>()
        val lane = SyncCoreMessageShadowFoldLane(
            featureFlags = resolvedFlags(enabled = true),
            adapter = throwingAdapter,
            scope = CoroutineScope(Dispatchers.Unconfined),
            logger = loggedFailures::add,
        )
        val (owner, homeEvents) = ownerWith(lane)
        val event = messageNewWithCanonical(id = "m1", seq = 1)

        // Must NOT throw out of accept, and the event must still reach the legacy sink.
        owner.accept(serverId, event, source = "app_shell")

        assertEquals<List<Pair<String, RealtimeEvent>>>(listOf(serverId to event), homeEvents)
        assertTrue(
            loggedFailures.any { it.contains("sync_core_t0_shadow_persist_failed") },
            "a swallowed persist failure must emit a bounded diagnostic",
        )
    }

    @Test
    fun noPersistWhenFlagOff() {
        val db = freshDatabase()
        val lane = SyncCoreMessageShadowFoldLane.create(
            featureFlags = resolvedFlags(enabled = false),
            sqlDatabase = db,
            now = { 100L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            logger = {},
        )
        val (owner, homeEvents) = ownerWith(lane)

        owner.accept(serverId, messageNewWithCanonical(id = "m1", seq = 1), source = "app_shell")

        assertEquals(emptyList(), persistedFactIds(db), "flag-off must not persist anything")
        assertEquals(1, homeEvents.size)
    }

    @Test
    fun normalizedV2ShadowRunsBeforeSinkAndRemainsInvisible() {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore()),
        )
        val logs = mutableListOf<String>()
        val callOrder = mutableListOf<String>()
        owner.installReactionV2ShadowLane(
            SyncCoreReactionV2ShadowLane(
                featureFlags = resolvedV2Flags(enabled = true),
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
    fun normalizedV2ShadowLoggerFailureCannotBlockLegacySink() {
        val owner = SharedRealtimeDomainEventOwner(
            cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore()),
        )
        owner.installReactionV2ShadowLane(
            SyncCoreReactionV2ShadowLane(
                featureFlags = resolvedV2Flags(enabled = true),
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
}
