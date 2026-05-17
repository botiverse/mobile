package ai.slock.android.core.data.auth

import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeAuthSessionStoreAdapterTest {
    @Test
    fun readsAndWritesSharedSessionThroughAndroidStore() = runBlocking {
        val delegate = FakeAuthSessionStore()
        val adapter = ComposeAuthSessionStoreAdapter(delegate)

        adapter.saveSession(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
            )
        )

        assertEquals("access", delegate.session?.accessToken)
        assertEquals("server-1", adapter.readSession()?.currentServerId)

        adapter.updateSelectedServer("server-2", "work")
        assertEquals("server-2", adapter.readSession()?.currentServerId)

        adapter.clearSession()
        assertNull(adapter.readSession())
    }

    private class FakeAuthSessionStore : AuthSessionStore {
        var session: AuthSession? = null

        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }
}
