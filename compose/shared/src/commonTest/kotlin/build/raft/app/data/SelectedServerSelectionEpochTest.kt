package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Regression coverage for the restart-stable selection fence used by pending sends. */
class SelectedServerSelectionEpochTest {
    @Test
    fun processRebuildReusesEpochAndOnlyARealServerSwitchAdvancesIt() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        val scope = RaftShellStoreScope.create("epoch-shell", "server-1")
        val first = store(epochs, scope)
        val session = session(selectedServerId = "server-1")

        assertEquals(0L, first.captureReplyCacheAuthority(session, authGeneration = 10L)?.selectionEpoch)
        assertEquals(0L, first.captureReplyCacheAuthority(session, authGeneration = 10L)?.selectionEpoch)

        first.select("server-2", "two")
        assertEquals(1L, first.captureReplyCacheAuthority(session.copy(selectedServerId = "server-2"), 10L)?.selectionEpoch)

        // A new process/store instance reads the durable epoch instead of resetting to its
        // in-memory authority generation.
        val rebuilt = store(epochs, RaftShellStoreScope.create("epoch-shell"))
        assertEquals(
            1L,
            rebuilt.captureReplyCacheAuthority(session.copy(selectedServerId = "server-2"), 10L)?.selectionEpoch,
        )
        assertEquals(1L, epochs.read("env-a", "epoch-shell", "user-1")?.epoch)
    }

    @Test
    fun environmentAndPrincipalAreIndependentEpochNamespaces() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        val scope = RaftShellStoreScope.create("same-shell", "server-1")

        assertEquals(0L, store(epochs, scope, environment = "env-a").captureReplyCacheAuthority(session(), 1L)?.selectionEpoch)
        assertEquals(0L, store(epochs, scope, environment = "env-b").captureReplyCacheAuthority(session(), 1L)?.selectionEpoch)
        assertEquals(
            0L,
            store(epochs, scope, environment = "env-a")
                .captureReplyCacheAuthority(session(userId = "user-2"), 1L)
                ?.selectionEpoch,
        )

        assertEquals(0L, epochs.read("env-a", "same-shell", "user-1")?.epoch)
        assertEquals(0L, epochs.read("env-b", "same-shell", "user-1")?.epoch)
        assertEquals(0L, epochs.read("env-a", "same-shell", "user-2")?.epoch)
    }

    @Test
    fun authRotationUpdatesRevisionButDoesNotAdvanceSelectionEpoch() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        val store = store(epochs, RaftShellStoreScope.create("auth-shell", "server-1"))
        val session = session()

        assertEquals(0L, store.captureReplyCacheAuthority(session, 4L)?.selectionEpoch)
        assertEquals(0L, store.captureReplyCacheAuthority(session, 5L)?.selectionEpoch)
        assertEquals(5L, epochs.read("env-a", "auth-shell", "user-1")?.authGeneration)

        // A delayed capture from the retired auth revision must not lower the durable revision or
        // create another selection epoch.
        assertEquals(0L, store.captureReplyCacheAuthority(session, 3L)?.selectionEpoch)
        val record = assertNotNull(epochs.read("env-a", "auth-shell", "user-1"))
        assertEquals(0L, record.epoch)
        assertEquals(5L, record.authGeneration)
    }

    @Test
    fun accountTransitionConvergencePersistsTheSuccessorSelectionBeforeCapture() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        // No route bootstrap override: the successor session's selected server is the
        // authoritative choice during an account transition.
        val scope = RaftShellStoreScope.create("transition-shell")
        val store = store(epochs, scope)

        assertEquals(
            0L,
            store.captureReplyCacheAuthority(
                session = session(userId = "user-1", selectedServerId = "server-1"),
                authGeneration = 8L,
            )?.selectionEpoch,
        )
        epochs.write(
            environmentId = "env-a",
            shellInstanceId = "transition-shell",
            principalId = "user-2",
            epoch = 4L,
            serverId = "server-1",
            authGeneration = 10L,
        )

        val converged =
            store.convergeReplyCacheAuthorityTransition(
                expectedPreviousOwner = "user-1",
                session = session(userId = "user-2", selectedServerId = "server-2"),
                authGeneration = 11L,
            )

        assertEquals("server-2", converged?.serverId)
        val successor = assertNotNull(epochs.read("env-a", "transition-shell", "user-2"))
        assertEquals("server-2", successor.serverId)
        assertEquals(5L, successor.epoch)
        assertEquals(11L, successor.authGeneration)

        // A process rebuild must observe the transition epoch even if no post-transition Thread
        // capture ran before the old process died.
        val rebuilt = store(epochs, RaftShellStoreScope.create("transition-shell"))
        assertEquals(
            5L,
            rebuilt.captureReplyCacheAuthority(
                session = session(userId = "user-2", selectedServerId = "server-2"),
                authGeneration = 11L,
            )?.selectionEpoch,
        )
    }

    @Test
    fun reconcileAndClearPersistAChangedSelectionBeforeAProcessDeathWindow() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        val scope = RaftShellStoreScope.create("clear-shell", "server-1")
        val store = store(epochs, scope)
        val session = session()

        store.captureReplyCacheAuthority(session, 7L)
        store.select("server-2", "two")
        assertEquals("server-2", epochs.read("env-a", "clear-shell", "user-1")?.serverId)
        assertEquals(1L, epochs.read("env-a", "clear-shell", "user-1")?.epoch)

        store.clearActiveSelection()
        val cleared = epochs.read("env-a", "clear-shell", "user-1")
        assertEquals(null, cleared?.serverId)
        assertEquals(2L, cleared?.epoch)
    }

    @Test
    fun logoutClearNeverPersistsTheRouteBootstrapServerForTheRetiredPrincipal() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        val scope = RaftShellStoreScope.create("clear-bootstrap-shell", "server-1")
        val store = store(epochs, scope)
        val session = session()

        store.captureReplyCacheAuthority(session, authGeneration = 12L)
        store.clearSelection()

        val retired = epochs.read("env-a", "clear-bootstrap-shell", "user-1")
        assertEquals(null, retired?.serverId)
        assertEquals(1L, retired?.epoch)

        // A later authenticated capture may deliberately select the bootstrap server again, but
        // it must do so under a fresh selection epoch rather than reviving the retired fence.
        val rebuilt = store(epochs, RaftShellStoreScope.create("clear-bootstrap-shell", "server-1"))
        val restored = rebuilt.captureReplyCacheAuthority(session, authGeneration = 13L)
        assertEquals("server-1", restored?.selection?.serverId)
        assertEquals(2L, restored?.selectionEpoch)
    }

    @Test
    fun selectionEpochSaturatesAtLongMaxInsteadOfWrappingIntoAnOldFence() = runBlocking {
        val epochs = InMemorySelectionEpochStore()
        epochs.write(
            environmentId = "env-a",
            shellInstanceId = "overflow-shell",
            principalId = "user-1",
            epoch = Long.MAX_VALUE,
            serverId = "server-1",
            authGeneration = 1L,
        )
        val store = store(epochs, RaftShellStoreScope.create("overflow-shell", "server-2"))

        val authority = store.captureReplyCacheAuthority(session(selectedServerId = "server-2"), 2L)

        assertEquals(Long.MAX_VALUE, authority?.selectionEpoch)
        assertEquals(Long.MAX_VALUE, epochs.read("env-a", "overflow-shell", "user-1")?.epoch)
        assertEquals("server-2", epochs.read("env-a", "overflow-shell", "user-1")?.serverId)
    }

    private fun store(
        epochs: InMemorySelectionEpochStore,
        scope: RaftShellStoreScope,
        environment: String = "env-a",
    ): SelectedServerStore =
        SelectedServerStore(
            scope = scope,
            selectionEpochStore = epochs,
            environmentIdProvider = { environment },
        )

    private fun session(
        userId: String = "user-1",
        selectedServerId: String? = "server-1",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.test"),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerId?.removePrefix("server-")?.let { if (it == "1") "one" else "two" },
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "One", slug = "one"),
                    StoredAuthServer(id = "server-2", name = "Two", slug = "two"),
                ),
        )

    private class InMemorySelectionEpochStore : SelectedServerSelectionEpochStore {
        private val values = mutableMapOf<Triple<String, String, String>, SelectedServerSelectionEpochRecord>()

        override fun read(environmentId: String, shellInstanceId: String, principalId: String): SelectedServerSelectionEpochRecord? =
            values[Triple(environmentId, shellInstanceId, principalId)]

        override fun write(
            environmentId: String,
            shellInstanceId: String,
            principalId: String,
            epoch: Long,
            serverId: String?,
            authGeneration: Long,
        ) {
            values[Triple(environmentId, shellInstanceId, principalId)] =
                SelectedServerSelectionEpochRecord(epoch, serverId, authGeneration)
        }
    }
}
