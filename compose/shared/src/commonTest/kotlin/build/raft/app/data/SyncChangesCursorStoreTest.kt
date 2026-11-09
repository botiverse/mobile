package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncChangesCursorStoreTest {
    private val preferences = SyncTestPreferences()

    @Test
    fun `cursors are kept per environment account and server and survive a new store`() {
        val store = RaftSyncCursorStore(preferences)
        val otherServer = syncTestScope.copy(serverId = "server-2")
        val otherAccount = syncTestScope.copy(accountId = "user-2")
        val otherEnvironment = syncTestScope.copy(environmentId = "https://staging.example.test")
        store.write(syncTestScope, "c1.1.1")
        store.write(otherServer, "c1.2.1")
        store.write(otherAccount, "c1.3.1")
        store.write(syncTestScope, "c1.4.1")

        val reopened = RaftSyncCursorStore(preferences)
        assertEquals("c1.4.1", reopened.read(syncTestScope))
        assertEquals("c1.2.1", reopened.read(otherServer))
        assertEquals("c1.3.1", reopened.read(otherAccount))
        assertNull(reopened.read(otherEnvironment))
    }

    @Test
    fun `losing a server clears its cursor and sign out clears all`() {
        val store = RaftSyncCursorStore(preferences)
        val otherServer = syncTestScope.copy(serverId = "server-2")
        val otherAccount = syncTestScope.copy(accountId = "user-2")
        store.write(syncTestScope, "c1.1.1")
        store.write(otherServer, "c1.2.1")
        store.write(otherAccount, "c1.3.1")

        store.clearServer("server-1", accountId = "user-1")
        assertNull(store.read(syncTestScope))
        assertEquals("c1.3.1", store.read(otherAccount))
        assertEquals("c1.2.1", store.read(otherServer))

        store.clearAll()
        assertNull(store.read(otherServer))
        assertNull(preferences.values[SYNC_CHANGES_CURSOR_STORAGE_KEY])
    }

    @Test
    fun `failures are counted per cursor survive a new store and reset on write`() {
        val store = RaftSyncCursorStore(preferences)
        store.write(syncTestScope, "c1.1.1")
        assertEquals(1, store.recordFailure(syncTestScope, "c1.1.1"))

        val reopened = RaftSyncCursorStore(preferences)
        assertEquals(1, reopened.failures(syncTestScope))
        assertEquals(2, reopened.recordFailure(syncTestScope, "c1.1.1"))
        // A cursor that is no longer the stored one counts as a first failure and changes nothing.
        assertEquals(1, reopened.recordFailure(syncTestScope, "c1.0.0"))
        assertEquals(2, reopened.failures(syncTestScope))

        reopened.write(syncTestScope, "c1.1.1")
        assertEquals(0, reopened.failures(syncTestScope))
        reopened.recordFailure(syncTestScope, "c1.1.1")
        reopened.write(syncTestScope, "c1.2.1")
        assertEquals(0, reopened.failures(syncTestScope))
        assertEquals(0, reopened.failures(syncTestScope.copy(serverId = "server-2")))
    }

    @Test
    fun `a document written before the failure count reads as no failures`() {
        preferences.values[SYNC_CHANGES_CURSOR_STORAGE_KEY] =
            """{"version":1,"entries":[{"environmentId":"https://api.example.test","accountId":"user-1","serverId":"server-1","cursor":"c1.1.1"}]}"""
        val store = RaftSyncCursorStore(preferences)
        assertEquals("c1.1.1", store.read(syncTestScope))
        assertEquals(0, store.failures(syncTestScope))
    }

    @Test
    fun `scope needs every part and normalizes the environment`() {
        assertEquals(syncTestScope, RaftSyncScope.of(" https://api.example.test/ ", "user-1", " server-1"))
        assertNull(RaftSyncScope.of("https://api.example.test", " ", "server-1"))
        assertNull(RaftSyncScope.of(null, "user-1", "server-1"))
    }

    @Test
    fun `a corrupt document reads as no cursor`() {
        preferences.values[SYNC_CHANGES_CURSOR_STORAGE_KEY] = "{not json"
        val store = RaftSyncCursorStore(preferences)
        assertNull(store.read(syncTestScope))
        store.write(syncTestScope, "c1.1.1")
        assertEquals("c1.1.1", store.read(syncTestScope))
    }
}
