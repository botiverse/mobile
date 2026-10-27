package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.RaftInMemoryThreadReplyWindowStorage
import build.raft.app.network.sync.RaftThreadReplyWindowCache
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Owner decision 2026-10-27 (WeChat/QQ style): signing out keeps the account's stored data. Through the
 * provider's real sign-out reset (`AuthTerminalLocalDataReset`) on real stores:
 * - account A signs out, account B signs in: B reads none of A's Saved list, notification mode or messages;
 * - A signs back in (offline): A's Saved list, notification mode and messages are there again.
 */
class AuthTerminalLocalDataResetTest {
    @Test
    fun signOutKeepsTheAccountsStoredDataForItselfOnly() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            val database = RaftSqlDatabase(driver)
            val preferences = InMemoryRaftKeyValueStore()
            val device = Device(database, scope, preferences)
            runBlocking {
                // Account A, online: Saved list, workspace notification mode and the channel's messages.
                val savedA = device.saved.key(USER_A, SERVER, Unit)!!
                device.saved.write(savedA, SavedListRecord(listOf(savedListRow("message-1")), totalCount = 1, hasMore = false))
                device.notifications.write(USER_A, SERVER, mentionsOnly)
                device.enterChannel(partition(USER_A))
                assertEquals(listOf(10L, 20L), device.storedMessageSeqs(partition(USER_A)))

                // A signs out.
                device.reset.run()

                // B signs in on the same device: none of A's data is readable under B.
                assertNull(device.saved.read(device.saved.key(USER_B, SERVER, Unit)!!))
                assertNull(device.notifications.read(USER_B, SERVER))
                assertEquals(emptyList(), device.storedMessageSeqs(partition(USER_B)))

                // A signs back in, offline (a new process over the same disk): A's data is shown again.
                val coldStart = Device(database, scope, preferences)
                assertEquals(
                    listOf("saved:message-1"),
                    coldStart.saved.read(coldStart.saved.key(USER_A, SERVER, Unit)!!)?.items?.map { it.key },
                )
                assertEquals(ServerPushMode.Mentions, coldStart.notifications.read(USER_A, SERVER)?.mode)
                assertEquals(listOf(10L, 20L), coldStart.storedMessageSeqs(partition(USER_A)))
            }
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    @Test
    fun signOutFencesTheOldSessionsInFlightWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            val device = Device(RaftSqlDatabase(driver), scope, InMemoryRaftKeyValueStore())
            runBlocking {
                // A request of A's session minted its key before the sign-out ...
                val key = device.saved.key(USER_A, SERVER, Unit)!!
                device.reset.run()
                // ... so its late answer cannot be stored after it.
                device.saved.write(key, SavedListRecord(listOf(savedListRow("late")), totalCount = 1, hasMore = false))
                assertNull(device.saved.read(device.saved.key(USER_A, SERVER, Unit)!!))
            }
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private class Device(database: RaftSqlDatabase, scope: CoroutineScope, preferences: InMemoryRaftKeyValueStore) {
        val offline =
            RaftOfflineResourceCache(
                rows = SqlDelightRaftOfflineResourceRows(database),
                environmentId = { "https://api.example/" },
                scope = scope,
            )
        val saved = offline.resource(SavedListResourceSpec)
        val savedStateCache = SharedHomeSavedStateCache(saved)
        val notifications = SettingsServerNotificationLastKnownStore(preferences)
        private val engine =
            CanonicalMessageV2GenerationEngine(
                coordinator = CanonicalMessageV2BaselineCoordinator(),
                store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L }),
                snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
            )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        private val reader =
            CanonicalMessageV2DurableLastKnownReader(
                SqlDelightCanonicalMessageV2Store(database = database, now = { 2L }),
                isEnabledForServer = { true },
            )
        val reset =
            AuthTerminalLocalDataReset(
                homeSavedStateCache = savedStateCache,
                offlineResourceCache = offline,
                memberAvatarRefStore = RaftMemberAvatarRefStore(preferences),
                jointConnectionsCache = { RaftKeyedSnapshotCache() },
                agentDetailCache = RaftKeyedSnapshotCache(),
                replyWindowCache = RaftThreadReplyWindowCache(RaftInMemoryThreadReplyWindowStorage(), scope),
                threadPayloadStore = SharedThreadPayloadStore(scope),
                clearViewerMemoryCaches = {},
            )

        suspend fun enterChannel(partition: DurablePartitionKey) {
            canary.noteTransportEpoch(partition, 1L)
            canary.noteRoomsJoined(partition, 1L)
            val page = adjacentPageOf(adjacentPageNode("m10", 10L), adjacentPageNode("m20", 20L))
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(
                    RecordingNetworkClient("""{"messages":$page,"historyLimited":false}"""),
                ),
                isEnabledForServer = { true },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        suspend fun storedMessageSeqs(partition: DurablePartitionKey): List<Long> =
            reader.read(partition, "channel-1")?.envelopes.orEmpty().map { it.fact.seq }.sorted()
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val SERVER = "server-1"
        val mentionsOnly =
            SettingsServerNotificationPreferences(
                mode = ServerPushMode.Mentions,
                pushPreviewMode = PushPreviewMode.Full,
                legacyMuted = false,
                canonicalModeSupported = true,
            )

        fun partition(principalId: String) =
            DurablePartitionKey(SERVER, principalId, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "https://api.example")
    }
}
