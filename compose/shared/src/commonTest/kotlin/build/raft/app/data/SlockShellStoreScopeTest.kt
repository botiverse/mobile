package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlockShellStoreScopeTest {
    @Test
    fun cacheKeysByStableShellIdentityWhileInitialServerRemainsBootstrapData() {
        val cache = SlockShellScopedStoreCache<Any>()
        val shellA = SlockShellStoreScope.create("shell-a", "server-1")
        val shellAAfterSwitch = SlockShellStoreScope.create("shell-a", "server-2")
        val shellB = SlockShellStoreScope.create("shell-b", "server-1")

        val firstA = cache.getOrPut(shellA) { Any() }
        val secondA = cache.getOrPut(shellAAfterSwitch) { Any() }
        val firstB = cache.getOrPut(shellB) { Any() }

        assertSame(firstA, secondA)
        assertNotSame(firstA, firstB)
        assertEquals(2, cache.size)
        assertSame(firstA, cache.remove(shellA))
        assertEquals(1, cache.size)
    }

    @Test
    fun shellSelectionsStayIndependentWhileProcessStoreTracksLastUsed() = runBlocking {
        val processStore = InMemorySlockAuthSessionStore(session())
        val runtimeA = SlockShellStoreRuntime(SlockShellStoreScope.create("shell-a", "server-1"))
        val runtimeB = SlockShellStoreRuntime(SlockShellStoreScope.create("shell-b", "server-2"))
        val shellA = SlockShellAuthSessionStore(processStore, runtimeA)
        val shellB = SlockShellAuthSessionStore(processStore, runtimeB)

        assertEquals("server-1", shellA.readSession()?.currentServerId)
        assertEquals("server-2", shellB.readSession()?.currentServerId)

        shellA.updateSelectedServer("server-3", "three")

        assertEquals("server-3", shellA.readSession()?.currentServerId)
        assertEquals("server-2", shellB.readSession()?.currentServerId)
        assertEquals("server-3", processStore.readSession()?.currentServerId)
        assertEquals("server-3", runtimeA.currentServerId.value)
        assertEquals("server-2", runtimeB.currentServerId.value)
    }

    @Test
    fun tokenRefreshDoesNotOverwriteAnotherShellsLastUsedServer() = runBlocking {
        val processStore = InMemorySlockAuthSessionStore(session())
        val runtimeB = SlockShellStoreRuntime(SlockShellStoreScope.create("shell-b", "server-2"))
        val shellB = SlockShellAuthSessionStore(processStore, runtimeB)
        processStore.updateSelectedServer("server-3", "three")

        val refreshedForShellB = shellB.readSession()!!.copy(accessToken = "access-next")
        shellB.saveSession(refreshedForShellB)

        assertEquals("server-2", shellB.readSession()?.currentServerId)
        assertEquals("server-3", processStore.readSession()?.currentServerId)
        assertEquals("access-next", processStore.readSession()?.accessToken)
    }

    @Test
    fun tokenRefreshPreservesMissingProcessSelectionInsteadOfPersistingBootstrapServer() = runBlocking {
        val processStore =
            InMemorySlockAuthSessionStore(
                session().copy(selectedServerId = null, selectedServerSlug = null)
            )
        val runtimeB = SlockShellStoreRuntime(SlockShellStoreScope.create("shell-b", "server-2"))
        val shellB = SlockShellAuthSessionStore(processStore, runtimeB)

        val refreshedForShellB = shellB.readSession()!!.copy(accessToken = "access-next")
        shellB.saveSession(refreshedForShellB)

        assertEquals("server-2", shellB.readSession()?.selectedServerId)
        assertEquals(null, processStore.readSession()?.selectedServerId)
        assertEquals("access-next", processStore.readSession()?.accessToken)
    }

    @Test
    fun atomicTokenRotationPreservesProcessSelectionAndReturnsShellSelection() = runBlocking {
        val processStore = InMemorySlockAuthSessionStore(session())
        val shellStore =
            SlockShellAuthSessionStore(
                processStore,
                SlockShellStoreRuntime(SlockShellStoreScope.create("shell-b", "server-2"))
            )
        processStore.updateSelectedServer("server-3", "three")

        val rotated =
            shellStore.rotateTokensIfMatch(
                expectedAccessToken = "access",
                expectedRefreshToken = "refresh",
                newAccessToken = "access-next",
                newRefreshToken = "refresh-next"
            )

        assertEquals("server-2", rotated?.selectedServerId)
        assertEquals("access-next", rotated?.accessToken)
        assertEquals("server-3", processStore.readSession()?.selectedServerId)
        assertEquals("access-next", processStore.readSession()?.accessToken)
    }

    @Test
    fun atomicMetadataUpdatePreservesTokensAndProcessSelection() = runBlocking {
        val processStore = InMemorySlockAuthSessionStore(session())
        val shellStore =
            SlockShellAuthSessionStore(
                processStore,
                SlockShellStoreRuntime(SlockShellStoreScope.create("shell-b", "server-2"))
            )
        processStore.updateSelectedServer("server-3", "three")

        shellStore.updateMetadata(expectedAccountId = "user-1") { current ->
            assertEquals("server-2", current.selectedServerId)
            current.copy(user = current.user.copy(email = "updated@example.com"))
        }

        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
        assertEquals("server-3", processStore.readSession()?.selectedServerId)
        assertEquals("updated@example.com", processStore.readSession()?.user?.email)
        assertEquals("access", processStore.readSession()?.accessToken)
        assertEquals("refresh", processStore.readSession()?.refreshToken)
    }

    @Test
    fun disposedRuntimeStopsMatchingActiveServer() {
        val runtime = SlockShellStoreRuntime(SlockShellStoreScope.create("shell-a", "server-1"))

        assertEquals("server-1", runtime.currentServerId.value)
        assertFalse(runtime.disposed.value)

        runtime.dispose()

        assertTrue(runtime.disposed.value)
    }

    @Test
    fun shellWithoutExplicitOrPersistedSelectionKeepsServerRequiredState() = runBlocking {
        val processStore =
            InMemorySlockAuthSessionStore(
                session().copy(selectedServerId = null, selectedServerSlug = null)
            )
        val runtime = SlockShellStoreRuntime(SlockShellStoreScope.create("shell-unselected"))
        val shellStore = SlockShellAuthSessionStore(processStore, runtime)

        val session = shellStore.readSession()

        assertEquals(null, session?.selectedServerId)
        assertEquals(null, runtime.currentServerId.value)
    }

    private fun session(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "one",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "One", slug = "one"),
                    StoredAuthServer(id = "server-2", name = "Two", slug = "two"),
                    StoredAuthServer(id = "server-3", name = "Three", slug = "three")
                )
        )
}
