package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthSessionStoreTest {
    @Test
    fun storedSessionExposesSharedSnapshotAndAuthTokens() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())

        val session = store.readSession()

        assertEquals("token-a", session?.toAuthTokens()?.accessToken)
        assertEquals("server-1", session?.toAuthTokens()?.selectedServerId)
        assertEquals("server-1", session?.toSessionSnapshot()?.currentServerId?.value)
        assertEquals("Artin", session?.toSessionSnapshot()?.currentMember?.displayName)
    }

    @Test
    fun updateSelectedServerAndClearSession() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())

        store.updateSelectedServer("server-2", "work")
        assertEquals("server-2", store.readSession()?.currentServerId)

        store.clearSession()
        assertNull(store.readSession())
    }

    @Test
    fun mmkvAuthSessionStorePersistsThroughAuthNamespaceOnly() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore)

        store.saveSession(sampleSession())
        assertEquals("server-1", store.readSession()?.currentServerId)

        store.updateSelectedServer("server-2", "work")
        assertEquals("server-2", store.readSession()?.currentServerId)

        store.clearSession()
        assertNull(store.readSession())
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "artin@slock.ai",
                    displayName = "Artin"
                ),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(
                        id = "server-1",
                        name = "Slock Community",
                        slug = "slock"
                    ),
                    StoredAuthServer(
                        id = "server-2",
                        name = "Work",
                        slug = "work"
                    )
                )
        )
}
