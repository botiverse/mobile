package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.SessionSnapshot
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
