package build.raft.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal fun authSessionFixture(
    userId: String = "user-1",
    servers: List<StoredAuthServer> = authServerFixtures(),
    selectedServerId: String? = servers.firstOrNull()?.id,
): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = userId, email = "$userId@example.com"),
        accessToken = "access-$userId",
        refreshToken = "refresh-$userId",
        selectedServerId = selectedServerId,
        selectedServerSlug = servers.firstOrNull { it.id == selectedServerId }?.slug,
        servers = servers,
    )

internal fun authServerFixtures(): List<StoredAuthServer> =
    listOf(
        StoredAuthServer(id = "server-1", name = "One", slug = "one"),
        StoredAuthServer(id = "server-2", name = "Two", slug = "two"),
    )

/** principal-a signed in on server-a. */
internal fun session(accessToken: String = "access") =
    StoredAuthSession(
        user = StoredAuthUser("principal-a", "principal@example.test", "Principal"),
        accessToken = accessToken,
        refreshToken = "refresh",
        selectedServerId = "server-a",
        servers = listOf(StoredAuthServer("server-a", "Server A", "server-a")),
    )

/** An in-memory session store and event source; [replace] swaps the session and emits its event. */
internal class FakeCanonicalFinalAuthStore : RaftAuthSessionStore, RaftAuthSessionEventSource {
    private var session: StoredAuthSession? = null
    private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 8)
    override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()
    override suspend fun readSession(): StoredAuthSession? = session
    override suspend fun saveSession(session: StoredAuthSession) { this.session = session }
    override suspend fun updateTokens(accessToken: String, refreshToken: String) = Unit
    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) = Unit
    override suspend fun clearSession(reason: String, source: String) { session = null }
    suspend fun replace(next: StoredAuthSession, event: AuthSessionEvent) {
        session = next
        events.emit(event)
    }
}
