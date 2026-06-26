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
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )

        store.clearSession()
        assertNull(store.readSession())
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )
    }

    @Test
    fun mmkvAuthSessionStorePersistsThroughAuthNamespaceOnly() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore)

        store.saveSession(sampleSession())
        assertEquals("server-1", store.readSession()?.currentServerId)

        store.updateSelectedServer("server-2", "work")
        assertEquals("server-2", store.readSession()?.currentServerId)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )

        store.clearSession()
        assertNull(store.readSession())
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )
    }

    @Test
    fun mmkvAuthSessionStoreBroadcastsLocalClearOnly() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        val events = RecordingAuthSessionClearEventSink()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore, sessionClearEvents = events)

        store.saveSession(sampleSession())
        store.clearSession()
        assertNull(store.readSession())
        assertEquals(listOf("local"), events.reasons)

        store.saveSession(sampleSession())
        store.clearSessionFromSessionClearedNotify("remote")
        assertNull(store.readSession())
        assertEquals(listOf("local"), events.reasons)
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

    private class RecordingAuthSessionClearEventSink : SlockAuthSessionClearEventSink {
        val reasons = mutableListOf<String>()

        override fun postSessionCleared(reason: String) {
            reasons += reason
        }
    }
}
