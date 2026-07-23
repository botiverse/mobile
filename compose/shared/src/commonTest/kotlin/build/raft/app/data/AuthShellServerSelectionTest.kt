package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AuthShellServerSelectionTest {
    @Test
    fun freshLoginSelectionPublishesToShellAndProcessAuthorities() = runTest {
        val initial = authSessionFixture(userId = "user-1", servers = authServerFixtures(), selectedServerId = null)
        val processStore = InMemorySlockAuthSessionStore(initial)
        val selectedServerStore =
            SelectedServerStore(SlockShellStoreScope.create("fresh-login-shell"))
        val shellStore = SlockShellAuthSessionStore(processStore, selectedServerStore)

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
    fun staleAccountGenerationCannotSelectIntoCurrentAccount() = runTest {
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
        val processStore = InMemorySlockAuthSessionStore(currentSession)
        val selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("new-account-shell"))
        val shellStore = SlockShellAuthSessionStore(processStore, selectedServerStore)
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
    fun serverRemovedAfterCaptureCannotBeSelected() = runTest {
        val captured =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = null,
            )
        val latest = captured.copy(servers = captured.servers.filterNot { it.id == "server-2" })
        val processStore = InMemorySlockAuthSessionStore(latest)
        val selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("removed-server-shell"))
        val shellStore = SlockShellAuthSessionStore(processStore, selectedServerStore)
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
    fun existingSelectionSwitchAndRepeatStayConverged() = runTest {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = "server-1",
            )
        val processStore = InMemorySlockAuthSessionStore(initial)
        val selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("existing-selection-shell"))
        val shellStore = SlockShellAuthSessionStore(processStore, selectedServerStore)
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
    fun staleMetadataCompletionCannotRollbackExplicitSelection() = runTest {
        val initial =
            authSessionFixture(
                userId = "user-1",
                servers = authServerFixtures(),
                selectedServerId = "server-1",
            )
        val processStore = InMemorySlockAuthSessionStore(initial)
        val delayedStore = DelayedMetadataStore(processStore)
        val selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("metadata-race-shell"))
        val shellStore = SlockShellAuthSessionStore(delayedStore, selectedServerStore)
        shellStore.readSession()

        val staleMetadata =
            async(start = CoroutineStart.UNDISPATCHED) {
                shellStore.updateMetadata(expectedAccountId = "user-1") { current ->
                    current.copy(user = current.user.copy(displayName = "Updated"))
                }
            }
        delayedStore.firstMetadataEntered.await()

        val selected =
            selectStoredAuthServer(
                sessionStore = shellStore,
                expectedSession = initial,
                serverId = "server-2",
            )
        delayedStore.releaseFirstMetadata.complete(Unit)
        staleMetadata.await()

        assertEquals("server-2", assertIs<SharedAuthResult.Success<StoredAuthSession>>(selected).data.selectedServerId)
        assertEquals("server-2", selectedServerStore.selectedServerId.value)
        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
        assertEquals("server-2", processStore.readSession()?.selectedServerId)
        assertEquals("Updated", processStore.readSession()?.user?.displayName)
    }

    @Test
    fun processSelectionEventCannotExposeOldShellSelection() = runTest {
        val initial = authSessionFixture(userId = "user-1", servers = authServerFixtures(), selectedServerId = null)
        val processStore = YieldingEventStore(InMemorySlockAuthSessionStore(initial))
        val selectedServerStore = SelectedServerStore(SlockShellStoreScope.create("event-order-shell"))
        val shellStore =
            SlockShellAuthSessionStore(
                delegate = processStore,
                selectedServerStore = selectedServerStore,
                eventScope = backgroundScope,
            )
        shellStore.readSession()
        runCurrent()
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
        runCurrent()

        assertEquals("server-2", selectionAtFirstEvent.await())
        observation.cancel()
    }

    private class DelayedMetadataStore(
        private val delegate: InMemorySlockAuthSessionStore,
    ) : SlockAuthSessionStore by delegate {
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

    private class YieldingEventStore(
        private val delegate: InMemorySlockAuthSessionStore,
    ) : SlockAuthSessionStore by delegate, SlockAuthSessionEventSource {
        private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 1)
        private var generation = 0L

        override val authSessionEvents: SharedFlow<AuthSessionEvent> = events.asSharedFlow()

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
