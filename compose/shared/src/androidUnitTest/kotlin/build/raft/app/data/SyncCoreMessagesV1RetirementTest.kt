package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.purgeSyncCoreMessagesV1Rows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Message storage Phase 0a on real SQLite: the V1 (`sync-core.messages.v1`) shadow rows are purged at
 * provider start, and no realtime frame writes them again, while display still runs.
 */
class SyncCoreMessagesV1RetirementTest {
    private val tables = listOf("messageEntity", "syncCursorEntity", "messageViewerOverlayEntity")

    @Test
    fun purgeDeletesEveryV1RowAndKeepsEveryOtherConsumerAndIsIdempotent() = runBlocking {
        val (driver, database) = freshDatabase()
        seedPartition(driver, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, principal = "principal-a")
        seedPartition(driver, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, principal = "principal-b")
        seedPartition(driver, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, principal = "principal-a")
        seedPartition(driver, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, principal = "principal-b")
        tables.forEach { table ->
            assertEquals(2L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "seeded v1 $table")
        }

        purgeSyncCoreMessagesV1Rows(database)

        tables.forEach { table ->
            assertEquals(0L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "v1 $table purged")
            assertEquals(2L, count(driver, table, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), "v2 $table kept")
        }

        purgeSyncCoreMessagesV1Rows(database)

        tables.forEach { table ->
            assertEquals(0L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID))
            assertEquals(2L, count(driver, table, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID))
        }
    }

    /**
     * The owner is built through the production factory and gets the production diagnostic lane. Selected,
     * background-unread and the principal-less legacy overload each display their frame, and the database
     * still holds no V1 row afterwards (before Phase 0a the exact-grant slot persisted one).
     */
    @Test
    fun realtimeFramesThroughTheProductionOwnerDisplayAndWriteNoV1Rows() = runBlocking {
        val (driver, database) = freshDatabase()
        seedPartition(driver, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, principal = "principal-a")
        purgeSyncCoreMessagesV1Rows(database)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val auth = FakeCanonicalFinalAuthStore()
            val provider =
                createSharedRealtimeAuthorityProvider(
                    registry =
                        RealtimeAuthorityRegistry(
                            authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                            processInstanceId = "v1-retirement",
                        ),
                    authStore = auth,
                    authEvents = auth,
                    currentEnvironmentId = { "env-test" },
                    onAuthRevisionAdvanced = {},
                )
            provider.start(scope)
            provider.registerScope("shell-a", "mount-a")
            auth.replace(session(), AuthSessionEvent.Authenticated(1))
            val grant = assertNotNull(provider.authorityFlow("shell-a").value?.grant("server-a"))
            val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
            val owner =
                createSharedRealtimeDomainEventOwner(
                    tasksDataStore = null,
                    cursorStore = cursorStore,
                    authorityProvider = provider,
                ).also { owner ->
                    owner.installMessageShadowFoldLane(
                        SyncCoreMessageShadowFoldLane(featureFlags = flagsOn(), logger = {}),
                    )
                }
            val displayed = mutableListOf<String>()
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(
                    applyHomeShell = { _, event -> displayed += "selected:${event.messageIdOrNull()}" },
                    applyHomeUnreadProjection = { _, event -> displayed += "unread:${event.messageIdOrNull()}" },
                ),
            )
            owner.registerSinks(
                RealtimeDomainEventSinks(
                    applyHomeShell = { _, event -> displayed += "legacy:${event.messageIdOrNull()}" },
                ),
            )

            assertTrue(owner.accept(grant, frame("m1", seq = 1), "selected"))
            assertTrue(owner.acceptHomeUnreadProjection(grant, frame("m2", seq = 2), "background"))
            owner.accept("server-a", frame("m3", seq = 3), "legacy")

            assertEquals(listOf("selected:m1", "unread:m2", "legacy:m3"), displayed)
            assertEquals(3L, cursorStore.readAppliedLastSeq(ServerId("server-a")))
            tables.forEach { table ->
                assertEquals(0L, count(driver, table, SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "no v1 $table row")
            }
        } finally {
            scope.cancel()
        }
    }

    private fun RealtimeEvent.messageIdOrNull(): String? = (this as? MessageNew)?.message?.id?.value

    private fun frame(id: String, seq: Long): MessageNew {
        val row = canonicalInput(id).row.copy(seq = seq)
        return canonicalEvent(id).let { event ->
            event.copy(
                message = event.message.copy(seq = seq),
                decodedCanonical = CanonicalMessageCarry(row, emptySet()),
            )
        }
    }

    private fun flagsOn(): SharedServerFeatureFlags {
        val flags =
            SharedServerFeatureFlags(
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        HomeNetworkResponse(
                            code = 200,
                            bodyText =
                                """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":true}]}""",
                        )
                },
            )
        runBlocking { flags.prefetch(ServerId("server-a")) }
        return flags
    }

    private fun freshDatabase(): Pair<JdbcSqliteDriver, RaftSqlDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        build.raft.app.shared.data.local.RaftDatabase.Schema.create(driver).value
        return driver to RaftSqlDatabase(driver)
    }

    private fun seedPartition(driver: JdbcSqliteDriver, consumerId: String, principal: String) {
        driver.execute(
            null,
            "INSERT INTO messageEntity (environmentId, serverId, principalId, consumerId, id, channelId, " +
                "scopeKind, seq, createdAt, canonicalFactJson, unknownFields) " +
                "VALUES ('env-test', 'server-a', '$principal', '$consumerId', 'm-$principal', 'channel-a', " +
                "'channel', 1, '2026-10-27T00:00:00Z', '{}', NULL)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO syncCursorEntity (scopeId, environmentId, serverId, principalId, consumerId, scopeKind, " +
                "latestSeq, updatedAtMs) " +
                "VALUES ('channel-a', 'env-test', 'server-a', '$principal', '$consumerId', 'channel', 1, 100)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO messageViewerOverlayEntity (environmentId, serverId, principalId, consumerId, scopeKind, " +
                "scopeId, subjectKind, subjectId, overlayKind, overlayVersion, completeness, payloadJson) " +
                "VALUES ('env-test', 'server-a', '$principal', '$consumerId', 'channel', 'channel-a', 'message', " +
                "'m-$principal', 'action-card', 1, 'complete', '{}')",
            0,
        )
    }

    private fun count(driver: JdbcSqliteDriver, table: String, consumerId: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $table WHERE consumerId = ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 1,
            binders = { bindString(0, consumerId) },
        ).value
}
