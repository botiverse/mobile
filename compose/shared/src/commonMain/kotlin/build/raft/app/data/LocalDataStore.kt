package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.SessionSnapshot
import build.raft.app.model.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

interface ComposeLocalDataStore {
    fun observeSession(): Flow<SessionSnapshot?>
    suspend fun readSession(): SessionSnapshot?
    suspend fun writeSession(session: SessionSnapshot?)

    fun observeHome(serverId: ServerId): Flow<HomeSnapshot?>
    suspend fun readHome(serverId: ServerId): HomeSnapshot?
    suspend fun writeHome(snapshot: HomeSnapshot)

    fun observeThread(threadId: ThreadId): Flow<ThreadSnapshot?>
    suspend fun readThread(threadId: ThreadId): ThreadSnapshot?
    suspend fun writeThread(snapshot: ThreadSnapshot)

    fun observeUnread(serverId: ServerId): Flow<ChannelUnreadSnapshot?>
    suspend fun readUnread(serverId: ServerId): ChannelUnreadSnapshot?
    suspend fun writeUnread(snapshot: ChannelUnreadSnapshot)
    suspend fun clearChannelUnread(channelId: ChannelId)
}

class InMemoryComposeLocalDataStore : ComposeLocalDataStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow(LocalState())

    override fun observeSession(): Flow<SessionSnapshot?> =
        state.map { it.session }

    override suspend fun readSession(): SessionSnapshot? =
        state.value.session

    override suspend fun writeSession(session: SessionSnapshot?) {
        mutate { copy(session = session) }
    }

    override fun observeHome(serverId: ServerId): Flow<HomeSnapshot?> =
        state.map { it.homes[serverId] }

    override suspend fun readHome(serverId: ServerId): HomeSnapshot? =
        state.value.homes[serverId]

    override suspend fun writeHome(snapshot: HomeSnapshot) {
        mutate { copy(homes = homes + (snapshot.server.id to snapshot)) }
    }

    override fun observeThread(threadId: ThreadId): Flow<ThreadSnapshot?> =
        state.map { it.threads[threadId] }

    override suspend fun readThread(threadId: ThreadId): ThreadSnapshot? =
        state.value.threads[threadId]

    override suspend fun writeThread(snapshot: ThreadSnapshot) {
        mutate { copy(threads = threads + (snapshot.conversation.threadId to snapshot)) }
    }

    override fun observeUnread(serverId: ServerId): Flow<ChannelUnreadSnapshot?> =
        state.map { it.unread[serverId] }

    override suspend fun readUnread(serverId: ServerId): ChannelUnreadSnapshot? =
        state.value.unread[serverId]

    override suspend fun writeUnread(snapshot: ChannelUnreadSnapshot) {
        mutate { copy(unread = unread + (snapshot.server.id to snapshot)) }
    }

    override suspend fun clearChannelUnread(channelId: ChannelId) {
        mutate {
            copy(
                unread = unread.mapValues { (_, snapshot) ->
                    snapshot.copy(
                        unreadCounts = snapshot.unreadCounts - channelId
                    )
                },
                homes = homes.mapValues { (_, snapshot) ->
                    snapshot.clearHomeChannelUnread(channelId)
                }
            )
        }
    }

    private suspend fun mutate(update: LocalState.() -> LocalState) {
        mutex.withLock {
            state.value = state.value.update()
        }
    }

    private data class LocalState(
        val session: SessionSnapshot? = null,
        val homes: Map<ServerId, HomeSnapshot> = emptyMap(),
        val threads: Map<ThreadId, ThreadSnapshot> = emptyMap(),
        val unread: Map<ServerId, ChannelUnreadSnapshot> = emptyMap()
    )
}

private fun HomeSnapshot.clearHomeChannelUnread(channelId: ChannelId): HomeSnapshot {
    var removedUnread = 0
    fun HomeChannelRow.clearIfMatched(): HomeChannelRow {
        if (!matchesLocalHomeChannel(channelId)) return this
        removedUnread += unreadCount
        return copy(unreadCount = 0, unreadCountKnown = true, firstUnreadMessageId = null)
    }
    fun HomeDirectMessageRow.clearIfMatched(): HomeDirectMessageRow {
        if (!matchesLocalHomeChannel(channelId)) return this
        removedUnread += unreadCount
        return copy(unreadCount = 0, unreadCountKnown = true, firstUnreadMessageId = null)
    }
    val nextChannels = channels.map { it.clearIfMatched() }
    val nextDirectMessages = directMessages.map { it.clearIfMatched() }
    val nextUnreadTotal = (unreadTotal - removedUnread).coerceAtLeast(0)
    val nextServerUnreadCount = (server.unreadCount - removedUnread).coerceAtLeast(0)
    return copy(
        channels = nextChannels,
        directMessages = nextDirectMessages,
        server = server.copy(unreadCount = nextServerUnreadCount),
        unreadTotal = nextUnreadTotal,
        serverUnreadCounts = serverUnreadCounts + (server.id.value to nextServerUnreadCount)
    )
}

private fun HomeConversationRow.matchesLocalHomeChannel(channelId: ChannelId): Boolean =
    this.channelId == channelId || threadId.value == channelId.value

class RelationalComposeLocalDataStore(
    private val store: RaftRelationalStore,
    private val clock: () -> Long = { 0L },
    private val diagnosticsLogger: (String) -> Unit = {}
) : ComposeLocalDataStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = true
    }

    override fun observeSession(): Flow<SessionSnapshot?> =
        flow { emit(readSession()) }

    override suspend fun readSession(): SessionSnapshot? =
        readSnapshot(SESSION_SCOPE, SESSION_ID)?.let { encoded ->
            decodeLocalSnapshot("session", encoded) { json.decodeFromString<SessionSnapshot>(encoded) }
        }

    override suspend fun writeSession(session: SessionSnapshot?) {
        if (session == null) {
            deleteSnapshot(SESSION_SCOPE, SESSION_ID)
        } else {
            writeSnapshot(SESSION_SCOPE, SESSION_ID, json.encodeToString(session))
        }
    }

    override fun observeHome(serverId: ServerId): Flow<HomeSnapshot?> =
        flow { emit(null) }

    override suspend fun readHome(serverId: ServerId): HomeSnapshot? =
        null

    override suspend fun writeHome(snapshot: HomeSnapshot) {
        // Durable HomeSnapshot blob storage has been removed. Keep the
        // interface method only while older tests/fakes migrate away from it.
    }

    override fun observeThread(threadId: ThreadId): Flow<ThreadSnapshot?> =
        flow { emit(readThread(threadId)) }

    override suspend fun readThread(threadId: ThreadId): ThreadSnapshot? =
        readSnapshot(THREAD_SCOPE, threadId.value)?.let { encoded ->
            decodeLocalSnapshot("thread", encoded) { json.decodeFromString<ThreadSnapshot>(encoded) }
        }

    override suspend fun writeThread(snapshot: ThreadSnapshot) {
        writeSnapshot(THREAD_SCOPE, snapshot.conversation.threadId.value, json.encodeToString(snapshot))
    }

    override fun observeUnread(serverId: ServerId): Flow<ChannelUnreadSnapshot?> =
        flow { emit(readUnread(serverId)) }

    override suspend fun readUnread(serverId: ServerId): ChannelUnreadSnapshot? =
        readSnapshot(UNREAD_SCOPE, serverId.value)?.let { encoded ->
            decodeLocalSnapshot("unread", encoded) { json.decodeFromString<ChannelUnreadSnapshot>(encoded) }
        }

    private suspend inline fun <T> decodeLocalSnapshot(source: String, encoded: String, crossinline decoder: () -> T): T? {
        val startedAt = TimeSource.Monotonic.markNow()
        val result = withContext(RaftJsonParseDispatcher) {
            runCatching(decoder)
        }
        diagnosticsLogger(
            "local_cache_decode source=$source result=${if (result.isSuccess) "success" else "failure"} " +
                "responseBytes=${encoded.encodeToByteArray().size} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        return result.getOrNull()
    }

    override suspend fun writeUnread(snapshot: ChannelUnreadSnapshot) {
        writeSnapshot(UNREAD_SCOPE, snapshot.server.id.value, json.encodeToString(snapshot))
    }

    override suspend fun clearChannelUnread(channelId: ChannelId) {
        val serverIds = querySnapshotIds(UNREAD_SCOPE)
        serverIds.forEach { serverIdValue ->
            val serverId = ServerId(serverIdValue)
            val snapshot = readUnread(serverId) ?: return@forEach
            writeUnread(snapshot.copy(unreadCounts = snapshot.unreadCounts - channelId))
        }
    }

    private suspend fun readSnapshot(scope: String, id: String): String? {
        ensureTable()
        val rows =
            store.query(
                "SELECT value FROM slock_local_snapshots WHERE scope = ? AND id = ? LIMIT 1",
                listOf(RelationalValue.Text(scope), RelationalValue.Text(id))
            )
        return rows.rows.firstOrNull()?.firstOrNull()?.asText()
    }

    private suspend fun writeSnapshot(scope: String, id: String, value: String) {
        ensureTable()
        store.execute(
            "INSERT OR REPLACE INTO slock_local_snapshots(scope, id, value, updated_at_ms) VALUES(?, ?, ?, ?)",
            listOf(
                RelationalValue.Text(scope),
                RelationalValue.Text(id),
                RelationalValue.Text(value),
                RelationalValue.LongNumber(clock())
            )
        )
    }

    private suspend fun deleteSnapshot(scope: String, id: String) {
        ensureTable()
        store.execute(
            "DELETE FROM slock_local_snapshots WHERE scope = ? AND id = ?",
            listOf(RelationalValue.Text(scope), RelationalValue.Text(id))
        )
    }

    private suspend fun querySnapshotIds(scope: String): List<String> {
        ensureTable()
        val rows =
            store.query(
                "SELECT id FROM slock_local_snapshots WHERE scope = ?",
                listOf(RelationalValue.Text(scope))
            )
        return rows.rows.mapNotNull { row -> row.firstOrNull()?.asText() }
    }

    private suspend fun ensureTable() {
        store.execute(
            "CREATE TABLE IF NOT EXISTS slock_local_snapshots(" +
                "scope TEXT NOT NULL, " +
                "id TEXT NOT NULL, " +
                "value TEXT NOT NULL, " +
                "updated_at_ms INTEGER NOT NULL, " +
                "PRIMARY KEY(scope, id)" +
                ")"
        )
    }

    private fun RelationalValue.asText(): String? =
        when (this) {
            is RelationalValue.Text -> value
            is RelationalValue.LongNumber -> value.toString()
            is RelationalValue.DoubleNumber -> value.toString()
            is RelationalValue.BooleanValue -> value.toString()
            RelationalValue.Null -> null
        }

    private companion object {
        const val SESSION_SCOPE = "session"
        const val SESSION_ID = "current"
        const val THREAD_SCOPE = "thread"
        const val UNREAD_SCOPE = "unread"
    }
}
