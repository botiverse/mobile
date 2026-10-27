package build.raft.app.data

import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Durable server-member directory (data-layer plan step 3).
 *
 * One row per environment + account + server holds the last authoritative roster (the same
 * [MemberSnapshot] the network returned, presence stripped: presence is live state and comes from
 * the process presence registry). A cold start seeds [SharedServerMembersStore] from it, so the
 * member list, names, avatars, roles and last known agent status paint before the directory
 * refetch; the store then refreshes once in the background and overwrites the row.
 *
 * Rules (Tenny):
 * 1. Keyed by environment + account + server. Only an account / environment boundary fences
 *    writes: [clearAll] and the eviction calls advance [epoch], and a write captured under an
 *    older epoch is dropped.
 * 2. Lost access evicts: [evictServer] (removed from the server / server deleted), member removal
 *    rewrites the row without the member ([removeMember]), [clearAll] clears every row (sign-out keeps them: rows are keyed by
 *    environment + account (owner decision 2026-10-27, see AccountLocalData)).
 * 3. Capped: at most [maxMembersPerServer] members per roster, [maxServersPerAccount] rosters per
 *    environment + account and [maxServers] in total, least recently written first.
 */
internal class RaftServerMembersDurableStore(
    private val storage: RaftServerMembersDurableStorage,
    private val now: () -> Long = ::defaultNowMillis,
    private val maxServersPerAccount: Int = DefaultMaxServersPerAccount,
    private val maxServers: Int = DefaultMaxServers,
    private val maxMembersPerServer: Int = DefaultMaxMembersPerServer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    // Advanced by every account boundary / eviction. Guarded by [mutex] for writes; reads are a
    // snapshot taken before a network request starts.
    @Volatile
    private var epochValue = 0L

    /** Capture before a request starts; pass to [write] so a write across a boundary is dropped. */
    val epoch: Long
        get() = epochValue

    suspend fun read(key: RaftServerMembersDurableKey): MemberSnapshot? {
        val id = key.rowId ?: return null
        return try {
            storage.read(id)
                ?.let { encoded -> DurableJson.decodeFromString(RaftServerMembersDurableRecord.serializer(), encoded) }
                ?.takeIf { record -> record.server.id.value.trim() == key.serverId.trim() }
                ?.let { record -> MemberSnapshot(server = record.server, members = record.members) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("server_members_durable_read result=failure type=${error::class.simpleName}")
            null
        }
    }

    /**
     * Persists [snapshot] for [key] unless an account boundary or eviction happened after
     * [expectedEpoch] was captured. Returns whether the row was written.
     */
    suspend fun write(
        key: RaftServerMembersDurableKey,
        snapshot: MemberSnapshot,
        expectedEpoch: Long,
    ): Boolean {
        val id = key.rowId ?: return false
        if (snapshot.server.id.value.trim() != key.serverId.trim()) return false
        return mutex.withLock {
            if (expectedEpoch != epochValue) {
                logger("server_members_durable_write result=fenced serverId=${key.serverId}")
                return@withLock false
            }
            try {
                val record =
                    RaftServerMembersDurableRecord(
                        server = snapshot.server,
                        members = snapshot.members.take(maxMembersPerServer.coerceAtLeast(1)).map { it.forDurableRoster() },
                        savedAtMs = now(),
                    )
                storage.write(id, DurableJson.encodeToString(RaftServerMembersDurableRecord.serializer(), record))
                val index = readIndexLocked().filterNot { it == id } + id
                val victims = capVictims(index)
                victims.forEach { victim -> storage.delete(victim) }
                writeIndexLocked(index.filterNot(victims::contains))
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger("server_members_durable_write result=failure type=${error::class.simpleName}")
                false
            }
        }
    }

    /**
     * Rewrites [key]'s row without [memberId] (member removed / agent deleted). Fire-and-forget
     * because the in-memory removal is synchronous; ordered with every other durable write.
     */
    fun removeMemberAsync(key: RaftServerMembersDurableKey, memberId: String) {
        val id = key.rowId ?: return
        val memberKey = memberId.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            mutex.withLock {
                runCatchingNonCancellation {
                    val encoded = storage.read(id) ?: return@runCatchingNonCancellation
                    val record = DurableJson.decodeFromString(RaftServerMembersDurableRecord.serializer(), encoded)
                    val next = record.members.filterNot { it.id.value.trim() == memberKey }
                    if (next.size != record.members.size) {
                        storage.write(
                            id,
                            DurableJson.encodeToString(RaftServerMembersDurableRecord.serializer(), record.copy(members = next))
                        )
                    }
                }
            }
        }
    }

    /** Lost access to [serverId] for [accountId] (every environment): drop its rows and fence writes. */
    suspend fun evictServer(serverId: String, accountId: String? = null) {
        val serverKey = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val accountKey = accountId?.trim()?.takeIf { it.isNotEmpty() }
        mutex.withLock {
            epochValue += 1L
            runCatchingNonCancellation {
                val ids = (readIndexLocked() + storage.ids().filterNot { it == IndexRowId }).distinct()
                val victims =
                    ids.filter { id ->
                        val parts = id.split(KeySeparator)
                        parts.size == 3 && parts[2] == serverKey && (accountKey == null || parts[1] == accountKey)
                    }
                victims.forEach { storage.delete(it) }
                writeIndexLocked(readIndexLocked().filterNot(victims::contains))
            }
        }
    }

    /** Every roster; in-flight writes are fenced. */
    suspend fun clearAll() {
        mutex.withLock {
            epochValue += 1L
            runCatchingNonCancellation {
                storage.ids().forEach { storage.delete(it) }
            }
        }
    }

    /** Non-suspending entry for synchronous cleanup paths (auth terminal cleanup). */
    fun clearAllAsync() {
        scope.launch { clearAll() }
    }

    /** Test seam: row ids in least-recently-written order. */
    internal suspend fun rowIdsForTest(): List<String> = mutex.withLock { readIndexLocked() }

    private fun capVictims(index: List<String>): Set<String> {
        val victims = linkedSetOf<String>()
        val perAccountCap = maxServersPerAccount.coerceAtLeast(1)
        index.groupBy { id -> id.substringBeforeLast(KeySeparator) }.values.forEach { ids ->
            if (ids.size > perAccountCap) victims += ids.take(ids.size - perAccountCap)
        }
        val survivors = index.filterNot(victims::contains)
        val totalCap = maxServers.coerceAtLeast(1)
        if (survivors.size > totalCap) victims += survivors.take(survivors.size - totalCap)
        return victims
    }

    private suspend fun readIndexLocked(): List<String> =
        storage.read(IndexRowId)
            ?.let { encoded -> runCatching { DurableJson.decodeFromString(IndexSerializer, encoded) }.getOrNull() }
            .orEmpty()

    private suspend fun writeIndexLocked(ids: List<String>) {
        if (ids.isEmpty()) {
            storage.delete(IndexRowId)
        } else {
            storage.write(IndexRowId, DurableJson.encodeToString(IndexSerializer, ids.distinct()))
        }
    }

    private suspend fun runCatchingNonCancellation(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("server_members_durable_mutation result=failure type=${error::class.simpleName}")
        }
    }

    internal companion object {
        const val DefaultMaxServersPerAccount = 12
        const val DefaultMaxServers = 24
        const val DefaultMaxMembersPerServer = 2000
        internal const val KeySeparator = "\u001f"
        private const val IndexRowId = "\u0000index"
        private val DurableJson = Json { ignoreUnknownKeys = true }
        private val IndexSerializer = ListSerializer(String.serializer())
    }
}

/** Environment + account + server owner of one durable roster row. */
internal data class RaftServerMembersDurableKey(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
) {
    val rowId: String?
        get() {
            val environment = environmentId.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
            val account = accountId.trim().takeIf { it.isNotEmpty() } ?: return null
            val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
            if (listOf(environment, account, server).any { it.contains(RaftServerMembersDurableStore.KeySeparator) }) {
                return null
            }
            return listOf(environment, account, server).joinToString(RaftServerMembersDurableStore.KeySeparator)
        }
}

/** Raw row storage. Production uses the `localSnapshotEntity` table; tests use a map. */
internal interface RaftServerMembersDurableStorage {
    suspend fun read(id: String): String?

    suspend fun write(id: String, value: String)

    suspend fun delete(id: String)

    suspend fun ids(): List<String>
}

/** [RaftServerMembersDurableStorage] on `localSnapshotEntity` (scope [RaftServerMembersSnapshotScope]). */
internal class SqlDelightRaftServerMembersDurableStorage(
    private val database: RaftSqlDatabase,
    private val now: () -> Long = ::defaultNowMillis,
) : RaftServerMembersDurableStorage {
    override suspend fun read(id: String): String? =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshot(RaftServerMembersSnapshotScope, id).executeAsOneOrNull()
        }

    override suspend fun write(id: String, value: String) {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.upsertSnapshot(RaftServerMembersSnapshotScope, id, value, now())
        }
    }

    override suspend fun delete(id: String) {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.deleteSnapshot(RaftServerMembersSnapshotScope, id)
        }
    }

    override suspend fun ids(): List<String> =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshotIds(RaftServerMembersSnapshotScope).executeAsList()
        }
}

/** Persisted scope name: keep the spelling stable across releases. */
internal const val RaftServerMembersSnapshotScope = "raft_server_members_v1"

@Serializable
internal data class RaftServerMembersDurableRecord(
    val server: ServerSummary,
    val members: List<MemberProfile>,
    val savedAtMs: Long = 0L,
)

/** Presence is live state owned by the presence registry; a persisted roster never carries it. */
private fun MemberProfile.forDurableRoster(): MemberProfile =
    if (presence == Presence.Offline) this else copy(presence = Presence.Offline)
