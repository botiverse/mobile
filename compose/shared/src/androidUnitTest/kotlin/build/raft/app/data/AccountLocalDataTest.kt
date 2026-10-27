package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.SharedThreadPayloadStore
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.DurablePartitionKey
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Owner decision 2026-10-27 (WeChat/QQ style), through the provider's real [AccountLocalData] on real stores
 * (SQLite for offline rows, reply windows and V2 messages; the preference store for the last-known copies):
 * - sign-out keeps the account's data for itself only: account B reads none of A's Saved list, notification
 *   mode or messages; A signing back in offline reads all three again;
 * - losing access to a server drops that server's offline rows, Home hints, Activity pages, notification
 *   preference, reply windows and messages, and keeps the account's other servers.
 */
class AccountLocalDataTest {
    // One test per kept copy, so a wipe re-added for any one of them turns exactly its test red.

    @Test
    fun signOutKeepsTheSavedListForItsAccountOnly() = signOutKeeps(
        write = { writeSaved(USER_A, S1, "message-1") },
        read = { user -> readSaved(user, S1) },
        expected = listOf("saved:message-1"),
    )

    @Test
    fun signOutKeepsTheNotificationModeForItsAccountOnly() = signOutKeeps(
        write = { notifications.write(USER_A, S1, mentionsOnly) },
        read = { user -> notifications.read(user, S1)?.mode },
        expected = ServerPushMode.Mentions,
    )

    @Test
    fun signOutKeepsTheMessagesForItsAccountOnly() = signOutKeeps(
        write = { enterChannel(partition(USER_A, S1)) },
        read = { user -> storedMessageSeqs(partition(user, S1)).takeIf { it.isNotEmpty() } },
        expected = listOf(10L, 20L),
    )

    /** A writes, A signs out; B reads nothing of it; A signs back in offline (new process) and reads it again. */
    private fun <T> signOutKeeps(
        write: suspend Device.() -> Unit,
        read: suspend Device.(userId: String) -> T?,
        expected: T,
    ) = withDevice { device, reopen ->
        device.write()
        assertEquals(expected, device.read(USER_A))

        device.accountLocalData.onSignOut()

        assertNull(device.read(USER_B), "the next account reads none of A's data")
        assertEquals(expected, reopen().read(USER_A), "A signing back in offline reads its data again")
    }

    @Test
    fun signOutFencesTheOldSessionsInFlightWrites() = withDevice { device, _ ->
        // A request of A's session minted its key before the sign-out ...
        val key = device.saved.key(USER_A, S1, Unit)!!
        device.accountLocalData.onSignOut()
        // ... so its late answer cannot be stored after it.
        device.saved.write(key, SavedListRecord(listOf(savedListRow("late")), totalCount = 1, hasMore = false))
        assertNull(device.readSaved(USER_A, S1))
    }

    @Test
    fun losingAServerDropsItsSavedList() = serverLossDrops(
        write = { server -> writeSaved(USER_A, server, "message-$server") },
        read = { server -> readSaved(USER_A, server) },
    )

    @Test
    fun losingAServerDropsItsHomeRowHints() = serverLossDrops(
        write = { server -> homeRowHints.write(hintKey(USER_A, server), "hint-$server") },
        read = { server -> homeRowHints.read(hintKey(USER_A, server)) },
    )

    @Test
    fun losingAServerDropsItsActivityPages() = serverLossDrops(
        write = { server -> activityPages.write(activityKey(USER_A, server), "activity-$server") },
        read = { server -> activityPages.read(activityKey(USER_A, server)) },
    )

    @Test
    fun losingAServerDropsItsNotificationPreference() = serverLossDrops(
        write = { server -> notifications.write(USER_A, server, mentionsOnly) },
        read = { server -> notifications.read(USER_A, server) },
    )

    @Test
    fun losingAServerDropsItsReplyWindows() = serverLossDrops(
        write = { server -> replyWindowStorage.write(replyPageKey(server, USER_A), "replies-$server") },
        read = { server -> replyWindowStorage.read(replyPageKey(server, USER_A)) },
    )

    @Test
    fun losingAServerDropsItsMessages() = serverLossDrops(
        write = { server -> enterChannel(partition(USER_A, server)) },
        read = { server -> storedMessageSeqs(partition(USER_A, server)).takeIf { it.isNotEmpty() } },
    )

    /** A has the copy for S1 and S2; access to S1 is lost: S1's copy is gone on disk, S2's stays. */
    private fun serverLossDrops(
        write: suspend Device.(serverId: String) -> Unit,
        read: suspend Device.(serverId: String) -> Any?,
    ) = withDevice { device, reopen ->
        device.write(S1)
        device.write(S2)
        val keptS2 = assertNotNull(device.read(S2))

        device.accountLocalData.onServerAccessLost(S1, USER_A, partition(USER_A, S1))

        val coldStart = reopen()
        assertNull(coldStart.read(S1), "S1's copy is dropped")
        assertEquals(keptS2, coldStart.read(S2), "S2's copy stays")
    }

    /** One device: the SQLite file and the preference store survive [reopen] (a new process over the same disk). */
    private fun withDevice(block: suspend (Device, reopen: () -> Device) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            val database = RaftSqlDatabase(driver)
            val preferences = InMemoryRaftKeyValueStore()
            runBlocking { block(Device(database, scope, preferences)) { Device(database, scope, preferences) } }
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private class Device(database: RaftSqlDatabase, scope: CoroutineScope, preferences: InMemoryRaftKeyValueStore) {
        private val offline =
            RaftOfflineResourceCache(
                rows = SqlDelightRaftOfflineResourceRows(database),
                environmentId = { "https://api.example/" },
                scope = scope,
            )
        val saved = offline.resource(SavedListResourceSpec)
        val notifications = SettingsServerNotificationLastKnownStore(preferences)
        val homeRowHints = HomeRowHintPersistStorage(preferences)
        val activityPages = SharedActivityPersistStorage(preferences)
        val replyWindowStorage = SqlDelightRaftThreadReplyWindowStorage(database, now = { 1L })
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
        val accountLocalData =
            AccountLocalData(
                homeSavedStateCache = SharedHomeSavedStateCache(saved),
                offlineResourceCache = offline,
                homeRowHints = homeRowHints,
                activityFirstPages = activityPages,
                notificationPreferences = notifications,
                messageEngine = engine,
                memberAvatarRefStore = RaftMemberAvatarRefStore(preferences),
                jointConnectionsCache = { RaftKeyedSnapshotCache() },
                agentDetailCache = RaftKeyedSnapshotCache(),
                replyWindowCache = RaftThreadReplyWindowCache(replyWindowStorage, scope),
                threadPayloadStore = SharedThreadPayloadStore(scope),
                clearViewerMemoryCaches = {},
            )

        suspend fun writeSaved(userId: String, serverId: String, messageId: String) {
            saved.write(
                saved.key(userId, serverId, Unit)!!,
                SavedListRecord(listOf(savedListRow(messageId)), totalCount = 1, hasMore = false),
            )
        }

        suspend fun readSaved(userId: String, serverId: String): List<String>? =
            saved.read(saved.key(userId, serverId, Unit)!!)?.items?.map { it.key }

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
        const val S1 = "server-1"
        const val S2 = "server-2"
        val mentionsOnly =
            SettingsServerNotificationPreferences(
                mode = ServerPushMode.Mentions,
                pushPreviewMode = PushPreviewMode.Full,
                legacyMuted = false,
                canonicalModeSupported = true,
            )

        fun partition(principalId: String, serverId: String) =
            DurablePartitionKey(serverId, principalId, SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "https://api.example")

        fun hintKey(userId: String, serverId: String) = "mvi:persist:home:hints:v1:user:$userId:server:$serverId"

        fun activityKey(userId: String, serverId: String) = "mvi:persist:activity:v1:user:$userId:server:$serverId:filter:all"

        fun replyPageKey(serverId: String, principalId: String) =
            listOf(serverId, principalId, "channel", "channel-1").joinToString("\u001f")
    }
}
