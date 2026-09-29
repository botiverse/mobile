package build.raft.app

import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Task #1122: the shape the deleted per-instance resolver got wrong, pinned on the test-side
 * authority that replaced it. One session store = one generation sequence, however many data
 * sources are built on it, and however late they are built.
 */
class SharedThreadTestIdentityAuthorityTest {
    @Test
    fun twoDataSourcesOnOneStoreCountGenerationsTogetherEvenWhenBuiltAfterASwitch() = runBlocking {
        val store = InMemorySlockAuthSessionStore(session("account-a"))
        val first = testSharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = store,
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
        )
        assertEquals(1L, generationOf(first))

        // Identity A -> B on the shared store. A data source built only now must see the sequence
        // where it stands (2), not start its own from 1 — that restart was the task #1122 defect.
        store.saveSession(session("account-b"))
        assertEquals(2L, generationOf(first))
        val second = testSharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = store,
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
        )
        assertEquals(2L, generationOf(second))

        // B -> A: the registry never re-uses a number, so A comes back as 3 on BOTH (not 1 / 3).
        store.saveSession(session("account-a"))
        assertEquals(3L, generationOf(second))
        assertEquals(3L, generationOf(first))
    }

    @Test
    fun sameIdentityAcrossCapturesIsStableAndIdentityChangeIncrements() = runBlocking {
        val store = InMemorySlockAuthSessionStore(session("account-a"))
        val authority = testThreadReplyCacheIdentityAuthority(store)
        // Pin a: repeated captures under one identity do not advance.
        assertEquals(1L, assertNotNull(authority.capture()).authGeneration)
        assertEquals(1L, assertNotNull(authority.capture()).authGeneration)
        // Pin b: each identity change advances by exactly one.
        store.saveSession(session("account-b"))
        assertEquals(2L, assertNotNull(authority.capture()).authGeneration)
        store.saveSession(session("account-c"))
        assertEquals(3L, assertNotNull(authority.capture()).authGeneration)
    }

    @Test
    fun identityIsTheRegistrysNormalisedSessionNotTheSelectedServer() = runBlocking {
        val store = InMemorySlockAuthSessionStore(session("account-a"))
        val authority = testThreadReplyCacheIdentityAuthority(store)
        assertEquals(1L, assertNotNull(authority.capture()).authGeneration)
        // Pin c: a token refresh and a selected-server change are not identity changes for the registry.
        store.saveSession(session("account-a").copy(accessToken = "access-2", refreshToken = "refresh-2"))
        assertEquals(1L, assertNotNull(authority.capture()).authGeneration)
        store.saveSession(session("account-a").copy(selectedServerId = "server-2"))
        assertEquals(1L, assertNotNull(authority.currentGeneration()))
        // ...but the server SET is: same principal, one more server => +1 (registry compares serverIds).
        store.saveSession(
            session("account-a").copy(
                servers = listOf(
                    StoredAuthServer(id = "server-1", name = "Server", slug = "server"),
                    StoredAuthServer(id = "server-2", name = "Other", slug = "other"),
                ),
            ),
        )
        assertEquals(2L, assertNotNull(authority.capture()).authGeneration)
    }

    @Test
    fun factoryRefusesAConflictingConfigurationForTheSameStore() {
        val store = InMemorySlockAuthSessionStore(session("account-a"))
        val first = testThreadReplyCacheIdentityAuthority(store, environmentId = "https://custom.example.test")
        // Specifying a different environment for the same store is a conflict.
        assertFailsWith<IllegalStateException> {
            testThreadReplyCacheIdentityAuthority(store, environmentId = TestThreadEnvironmentId)
        }
        // Not specifying anything means "reuse": same holder, no throw — symmetric for both parameters.
        assertSame(first, testThreadReplyCacheIdentityAuthority(store))
        assertSame(first, testThreadReplyCacheIdentityAuthority(store, environmentId = "https://custom.example.test"))

        // The common real-world order: a holder built by OMITTING the environment (so it records the
        // concrete fallback, TestThreadEnvironmentId — not null), then someone specifies a custom one.
        // "I specified A and would get an object configured for B" is exactly what the check is for.
        val omitted = InMemorySlockAuthSessionStore(session("account-a"))
        val byDefault = testThreadReplyCacheIdentityAuthority(omitted)
        assertEquals(TestThreadEnvironmentId, byDefault.environmentId)
        assertFailsWith<IllegalStateException> {
            testThreadReplyCacheIdentityAuthority(omitted, environmentId = "https://custom.example.test")
        }
        assertSame(byDefault, testThreadReplyCacheIdentityAuthority(omitted, environmentId = TestThreadEnvironmentId))
    }

    @Test
    fun factoryIsMemoisedPerStoreInstanceNotPerIdentity() = runBlocking {
        val store = InMemorySlockAuthSessionStore(session("account-a"))
        val other = InMemorySlockAuthSessionStore(session("account-a"))
        // Same store => same holder, even after the identity on it changed.
        val before = testThreadReplyCacheIdentityAuthority(store)
        store.saveSession(session("account-b"))
        assertSame(before, testThreadReplyCacheIdentityAuthority(store))
        // A different store (another test's "process") never shares a holder, identity or not.
        assertNotSame(before, testThreadReplyCacheIdentityAuthority(other))
    }

    private suspend fun generationOf(dataSource: SharedThreadDataSource): Long =
        assertNotNull(dataSource.captureReplyCacheProducerContext()).provenance.authGeneration

    private fun session(accountId: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = accountId, email = "$accountId@example.test", displayName = accountId),
            accessToken = "access-$accountId",
            refreshToken = "refresh-$accountId",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server")),
        )
}
