package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthShellServerSelectionTest {
    @Test
    fun freshLoginSelectionPublishesToShellAndProcessAuthorities() = runBlocking {
        val initial = authSessionFixture(userId = "user-1", servers = authServerFixtures(), selectedServerId = null)
        val processStore = InMemoryRaftAuthSessionStore(initial)
        val selectedServerStore =
            SelectedServerStore(RaftShellStoreScope.create("fresh-login-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)

        assertNull(shellStore.readSession()?.selectedServerId)

        val result =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                serverId = "server-2",
            )

        val selected = assertIs<SharedAuthResult.Success<StoredAuthSession>>(result).data
        assertEquals("server-2", selected.selectedServerId)
        assertEquals("two", selected.selectedServerSlug)
        assertEquals("server-2", selectedServerStore.selectedServerId.value)
        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
        assertEquals("server-2", processStore.readSession()?.selectedServerId)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "two"),
            processStore.readRememberedServerSelection(initial.user),
        )
    }

    @Test
    fun scopedChooserSelectionSurvivesAProcessOnlyRestoreRead() = runBlocking {
        val initial = authSessionFixture(userId = "user-1", servers = authServerFixtures(), selectedServerId = null)
        val processStore = InMemoryRaftAuthSessionStore(initial)
        val selectedServerStore =
            SelectedServerStore(RaftShellStoreScope.create("restore-read-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)

        // AppShell has already captured its own null selection before the chooser opens.
        assertNull(shellStore.readSession()?.selectedServerId)

        // This models the old process-wide chooser wiring: the process record changes, but the
        // mounted shell authority does not, so the subsequent shell restore still sees no server.
        val processOnlySelection =
            selectStoredAuthServer(
                sessionStore = processStore,
                expectedSession = initial,
                serverId = "server-2",
            )
        assertEquals(
            "server-2",
            assertIs<SharedAuthResult.Success<StoredAuthSession>>(processOnlySelection).data.selectedServerId,
        )
        assertNull(shellStore.readSession()?.selectedServerId)

        // The scoped chooser writes the live shell authority as well. A later process-level
        // restore/read cannot bypass that authority, even if its persisted selection is stale.
        val scopedSelection =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = requireNotNull(shellStore.readSession()),
                serverId = "server-2",
            )
        assertEquals(
            "server-2",
            assertIs<SharedAuthResult.Success<StoredAuthSession>>(scopedSelection).data.selectedServerId,
        )
        processStore.updateSelectedServer(serverId = null, serverSlug = null)

        assertNull(processStore.readSession()?.selectedServerId)
        assertEquals("server-2", selectedServerStore.selectedServerId.value)
        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
    }

    @Test
    fun staleAccountGenerationCannotSelectIntoCurrentAccount() = runBlocking {
        val staleSession =
            authSessionFixture(
                userId = "user-old",
                servers = listOf(StoredAuthServer(id = "server-old", name = "Old", slug = "old")),
                selectedServerId = null,
            )
        val currentSession =
            authSessionFixture(
                userId = "user-new",
                servers = listOf(StoredAuthServer(id = "server-new", name = "New", slug = "new")),
                selectedServerId = null,
            )
        val processStore = InMemoryRaftAuthSessionStore(currentSession)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("new-account-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()

        val result =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = staleSession,
                serverId = "server-old",
            )

        assertIs<SharedAuthResult.Unauthenticated>(result)
        assertEquals("user-new", processStore.readSession()?.user?.id)
        assertNull(processStore.readSession()?.selectedServerId)
        assertNull(selectedServerStore.selectedServerId.value)
    }

    @Test
    fun sameAccountSuccessorGenerationRejectsLateSelectionWithoutWrites() = runBlocking {
        val generationA =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = "server-1",
            ).copy(
                accessToken = "access-generation-a",
                refreshToken = "refresh-generation-a",
            )
        val generationB =
            generationA.copy(
                accessToken = "access-generation-b",
                refreshToken = "refresh-generation-b",
            )
        val processStore = InMemoryRaftAuthSessionStore(generationA)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("same-account-successor-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()
        processStore.saveRememberedServerSelection(
            generationA.user,
            StoredAuthServerSelection(serverId = "server-1", serverSlug = "one"),
        )
        processStore.replaceAuthenticatedSession(generationB)
        val events = mutableListOf<AuthSessionEvent>()
        val eventObserver =
            launch(start = CoroutineStart.UNDISPATCHED) {
                shellStore.authSessionEvents.collect { events += it }
            }
        val processBefore = processStore.readSession()

        val result =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = generationA,
                serverId = "server-2",
            )
        yield()

        assertIs<SharedAuthResult.Unauthenticated>(result)
        assertEquals(processBefore, processStore.readSession())
        assertEquals("server-1", selectedServerStore.selectedServerId.value)
        assertEquals("server-1", shellStore.readSession()?.selectedServerId)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-1", serverSlug = "one"),
            processStore.readRememberedServerSelection(generationB.user),
        )
        assertEquals(emptyList(), events)
        eventObserver.cancel()
    }

    @Test
    fun serverRemovedAfterCaptureCannotBeSelected() = runBlocking {
        val captured =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = null,
            )
        val latest = captured.copy(servers = captured.servers.filterNot { it.id == "server-2" })
        val processStore = InMemoryRaftAuthSessionStore(latest)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("removed-server-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()

        val result =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = captured,
                serverId = "server-2",
            )

        assertIs<SharedAuthResult.Unauthenticated>(result)
        assertNull(processStore.readSession()?.selectedServerId)
        assertNull(selectedServerStore.selectedServerId.value)
    }

    @Test
    fun existingSelectionSwitchAndRepeatStayConverged() = runBlocking {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = "server-1",
            )
        val processStore = InMemoryRaftAuthSessionStore(initial)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("existing-selection-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()

        val switched =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                serverId = "server-2",
            )
        val repeated =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = assertIs<SharedAuthResult.Success<StoredAuthSession>>(switched).data,
                serverId = "server-2",
            )

        assertEquals("server-2", assertIs<SharedAuthResult.Success<StoredAuthSession>>(repeated).data.selectedServerId)
        assertEquals("server-2", selectedServerStore.selectedServerId.value)
        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
        assertEquals("server-2", processStore.readSession()?.selectedServerId)
    }

    @Test
    fun staleSourceSelectionCannotOverwriteConcurrentCanonicalSwitch() = runBlocking {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures() + StoredAuthServer(id = "server-3", name = "Three", slug = "three"),
                selectedServerId = "server-1",
            )
        val processStore = InMemoryRaftAuthSessionStore(initial)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("source-race-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()

        val concurrent =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                expectedSelectedServerId = "server-1",
                serverId = "server-3",
            )
        val stale =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                expectedSelectedServerId = "server-1",
                serverId = "server-2",
            )

        assertEquals("server-3", assertIs<SharedAuthResult.Success<StoredAuthSession>>(concurrent).data.selectedServerId)
        assertIs<SharedAuthResult.Unauthenticated>(stale)
        assertEquals("server-3", selectedServerStore.selectedServerId.value)
        assertEquals("server-3", shellStore.readSession()?.selectedServerId)
        assertEquals("server-3", processStore.readSession()?.selectedServerId)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-3", serverSlug = "three"),
            processStore.readRememberedServerSelection(initial.user),
        )
    }

    @Test
    fun directCanonicalSelectionAndExpectedSourceSelectionShareOneMutationOrder() = runBlocking {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures() + StoredAuthServer(id = "server-3", name = "Three", slug = "three"),
                selectedServerId = "server-1",
            )
        val processStore = InMemoryRaftAuthSessionStore(initial)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("direct-source-race-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()

        shellStore.updateSelectedServer("server-3", "three")
        val stale =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                expectedSelectedServerId = "server-1",
                serverId = "server-2",
            )

        assertIs<SharedAuthResult.Unauthenticated>(stale)
        assertEquals("server-3", selectedServerStore.selectedServerId.value)
        assertEquals("server-3", shellStore.readSession()?.selectedServerId)
        assertEquals("server-3", processStore.readSession()?.selectedServerId)
    }

    @Test
    fun logoutCannotCutIntoExpectedSourceCommitRememberAndEventSequence() = runBlocking {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = "server-1",
            )
        val processStore = PausingRememberedSelectionStore(InMemoryRaftAuthSessionStore(initial))
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("logout-race-shell"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)
        shellStore.readSession()
        val events = mutableListOf<AuthSessionEvent>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { shellStore.authSessionEvents.collect { events += it } }

        val selection =
            async(start = CoroutineStart.UNDISPATCHED) {
                selectStoredAuthServer(
                    sessionStore = shellStore,
                    expectedSession = initial,
                    expectedSelectedServerId = "server-1",
                    serverId = "server-2",
                )
            }
        processStore.rememberEntered.await()
        val logout = async(start = CoroutineStart.UNDISPATCHED) { shellStore.clearSession("test", "test") }
        yield()
        assertEquals(false, logout.isCompleted)

        processStore.releaseRemember.complete(Unit)
        assertEquals("server-2", assertIs<SharedAuthResult.Success<StoredAuthSession>>(selection.await()).data.selectedServerId)
        logout.await()
        yield()

        assertNull(shellStore.readSession())
        assertNull(selectedServerStore.selectedServerId.value)
        assertEquals(1, events.filterIsInstance<AuthSessionEvent.BindingMetadataChanged>().size)
        observer.cancel()
    }

    @Test
    fun staleMetadataCompletionCannotRollbackExplicitSelection() = runBlocking {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = "server-1",
            )
        val processStore = InMemoryRaftAuthSessionStore(initial)
        val delayedStore = DelayedMetadataStore(processStore)
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("metadata-race-shell"))
        val shellStore = RaftShellAuthSessionStore(delayedStore, selectedServerStore)
        shellStore.readSession()

        val staleMetadata =
            async(start = CoroutineStart.UNDISPATCHED) {
                shellStore.updateMetadata(expectedAccountId = "user-1") { current ->
                    current.copy(user = current.user.copy(displayName = "Updated"))
                }
            }
        delayedStore.firstMetadataEntered.await()

        val selected =
            async(start = CoroutineStart.UNDISPATCHED) {
                selectStoredAuthServer(
                    sessionStore = shellStore,
                    expectedSession = initial,
                    serverId = "server-2",
                )
            }
        yield()
        assertEquals(false, selected.isCompleted)
        delayedStore.releaseFirstMetadata.complete(Unit)
        staleMetadata.await()

        assertEquals("server-2", assertIs<SharedAuthResult.Success<StoredAuthSession>>(selected.await()).data.selectedServerId)
        assertEquals("server-2", selectedServerStore.selectedServerId.value)
        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
        assertEquals("server-2", processStore.readSession()?.selectedServerId)
        assertEquals("Updated", processStore.readSession()?.user?.displayName)
    }

    @Test
    fun processSelectionEventCannotExposeOldShellSelection() = runBlocking {
        val initial = authSessionFixture(userId = "user-1", servers = authServerFixtures(), selectedServerId = null)
        val processStore = YieldingEventStore(InMemoryRaftAuthSessionStore(initial))
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("event-order-shell"))
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val shellStore =
                RaftShellAuthSessionStore(
                    delegate = processStore,
                    selectedServerStore = selectedServerStore,
                    eventScope = eventScope,
                )
            shellStore.readSession()
            val selectionAtFirstEvent = CompletableDeferred<String?>()
            val observation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    shellStore.authSessionEvents.first()
                    selectionAtFirstEvent.complete(selectedServerStore.selectedServerId.value)
                }

            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                serverId = "server-2",
            )

            assertEquals("server-2", selectionAtFirstEvent.await())
            observation.cancel()
        } finally {
            eventScope.cancel()
        }
    }

    private class DelayedMetadataStore(
        private val delegate: InMemoryRaftAuthSessionStore,
    ) : RaftAuthSessionStore by delegate {
        val firstMetadataEntered = CompletableDeferred<Unit>()
        val releaseFirstMetadata = CompletableDeferred<Unit>()
        private var delayNextMetadata = true

        override suspend fun updateMetadata(
            expectedAccountId: String?,
            transform: (StoredAuthSession) -> StoredAuthSession,
        ) {
            if (delayNextMetadata) {
                delayNextMetadata = false
                firstMetadataEntered.complete(Unit)
                releaseFirstMetadata.await()
            }
            delegate.updateMetadata(expectedAccountId, transform)
        }
    }

    private class PausingRememberedSelectionStore(
        private val delegate: InMemoryRaftAuthSessionStore,
    ) : RaftAuthSessionStore by delegate, RaftAuthServerSelectionMemory {
        val rememberEntered = CompletableDeferred<Unit>()
        val releaseRemember = CompletableDeferred<Unit>()

        override suspend fun readRememberedServerSelection(user: StoredAuthUser): StoredAuthServerSelection? =
            delegate.readRememberedServerSelection(user)

        override suspend fun saveRememberedServerSelection(
            user: StoredAuthUser,
            selection: StoredAuthServerSelection?,
        ) {
            rememberEntered.complete(Unit)
            releaseRemember.await()
            delegate.saveRememberedServerSelection(user, selection)
        }
    }

    private class YieldingEventStore(
        private val delegate: InMemoryRaftAuthSessionStore,
    ) : RaftAuthSessionStore by delegate, RaftAuthSessionEventSource {
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 1)
        private var generation = 0L

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

        override suspend fun selectServerIfSessionMatches(
            expectedAccountId: String,
            expectedAccessToken: String,
            expectedRefreshToken: String,
            expectedSelectedServerId: String?,
            serverId: String,
        ): StoredAuthSession? {
            val selected =
                delegate.selectServerIfSessionMatches(
                    expectedAccountId = expectedAccountId,
                    expectedAccessToken = expectedAccessToken,
                    expectedRefreshToken = expectedRefreshToken,
                    expectedSelectedServerId = expectedSelectedServerId,
                    serverId = serverId,
                ) ?: return null
            events.emit(AuthSessionEvent.BindingMetadataChanged(++generation))
            yield()
            return selected
        }

        override suspend fun updateMetadata(
            expectedAccountId: String?,
            transform: (StoredAuthSession) -> StoredAuthSession,
        ) {
            val before = delegate.readSession()
            delegate.updateMetadata(expectedAccountId, transform)
            if (delegate.readSession() != before) {
                events.emit(AuthSessionEvent.BindingMetadataChanged(++generation))
                yield()
            }
        }
    }
}
