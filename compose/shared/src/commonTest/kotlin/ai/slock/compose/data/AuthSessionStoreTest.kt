package ai.slock.compose.data

import kotlinx.serialization.encodeToString
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

    @Test
    fun mmkvAuthSessionStoreMigratesLegacyRelationalSessionOnce() = runBlocking {
        val legacySession = sampleSession().copy(selectedServerId = "server-2")
        val legacyStore = FakeLegacyAuthRelationalStore(
            encoded = authSessionStoreTestJson.encodeToString(StoredAuthSession.serializer(), legacySession)
        )
        val keyValueStore = InMemorySlockKeyValueStore()
        val store = KuiklyMMKVAuthSessionStore(keyValueStore, legacyStore)

        assertEquals("server-2", store.readSession()?.currentServerId)
        assertEquals("server-2", store.readSession()?.currentServerId)
        assertEquals(1, legacyStore.queryCount)
        assertEquals(1, legacyStore.deleteCount)
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

private val authSessionStoreTestJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private class FakeLegacyAuthRelationalStore(
    private val encoded: String
) : SlockRelationalStore {
    var queryCount = 0
    var deleteCount = 0

    override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
        if (sql.startsWith("DELETE", ignoreCase = true)) {
            deleteCount += 1
        }
    }

    override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows {
        queryCount += 1
        return RelationalRows(
            columns = listOf("value"),
            rows = listOf(listOf(RelationalValue.Text(encoded)))
        )
    }

    override suspend fun transaction(block: suspend SlockRelationalStore.() -> Unit) {
        block()
    }
}
