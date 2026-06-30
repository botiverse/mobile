package build.raft.app.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun mmkvAuthSessionStoreBroadcastsChangedSessionFingerprint() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore)
        val firstChange = async(start = CoroutineStart.UNDISPATCHED) { store.sessionChanges.first() }

        store.saveSession(sampleSession())

        assertEquals(sampleSession().authSessionClearFingerprint(), firstChange.await())

        val duplicateChange = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(50) { store.sessionChanges.first() } }
        store.saveSession(sampleSession())
        assertNull(duplicateChange.await())

        val selectedServerChange = async(start = CoroutineStart.UNDISPATCHED) { store.sessionChanges.first() }
        store.updateSelectedServer("server-2", "work")
        assertEquals(
            sampleSession()
                .copy(selectedServerId = "server-2", selectedServerSlug = "work")
                .authSessionClearFingerprint(),
            selectedServerChange.await()
        )
    }

    @Test
    fun mmkvAuthSessionStoreBroadcastsLocalClearOnly() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        val events = RecordingAuthSessionClearEventSink()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore, sessionClearEvents = events)
        val staleSession = sampleSession()
        val staleFingerprint = staleSession.authSessionClearFingerprint()

        store.saveSession(staleSession)
        store.clearSession()
        assertNull(store.readSession())
        assertEquals(listOf(RecordedClearEvent("local", staleFingerprint)), events.events)

        assertTrue(
            store.clearSessionFromSessionClearedNotify(
                reason = "remote",
                previousSessionFingerprint = staleFingerprint
            )
        )
        assertNull(store.readSession())

        store.saveSession(staleSession)
        assertTrue(
            store.clearSessionFromSessionClearedNotify(
                reason = "remote",
                previousSessionFingerprint = staleFingerprint
            )
        )
        assertNull(store.readSession())
        assertEquals(listOf(RecordedClearEvent("local", staleFingerprint)), events.events)
    }

    @Test
    fun mmkvAuthSessionStoreSkipsStaleRemoteClearAfterNewLogin() = runBlocking {
        val keyValueStore = InMemorySlockKeyValueStore()
        val events = RecordingAuthSessionClearEventSink()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore, sessionClearEvents = events)
        val staleSession = sampleSession()
        val freshSession = sampleSession().copy(accessToken = "token-new", refreshToken = "token-new-r")

        store.saveSession(staleSession)
        store.clearSession()
        store.saveSession(freshSession)

        assertFalse(
            store.clearSessionFromSessionClearedNotify(
                reason = "remote",
                previousSessionFingerprint = events.events.single().previousSessionFingerprint
            )
        )
        assertEquals("token-new", store.readSession()?.accessToken)
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

    private data class RecordedClearEvent(
        val reason: String,
        val previousSessionFingerprint: String
    )

    private class RecordingAuthSessionClearEventSink : SlockAuthSessionClearEventSink {
        val events = mutableListOf<RecordedClearEvent>()

        override fun postSessionCleared(reason: String, previousSessionFingerprint: String) {
            events += RecordedClearEvent(reason, previousSessionFingerprint)
        }
    }
}
