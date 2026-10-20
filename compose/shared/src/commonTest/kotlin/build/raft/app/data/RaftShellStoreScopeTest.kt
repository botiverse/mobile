package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.MemberId
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftShellStoreScopeTest {
    @Test
    fun selectionFencePublishesBeforeStateFlowAndDisposeSynchronouslyClearsIt() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var store: SelectedServerStore
        var constructing = true
        store =
            SelectedServerStore(
                scope = RaftShellStoreScope.create("shell-fenced"),
                onSelectionChanged = { serverId ->
                    if (!constructing) {
                        events += "$serverId|flow=${store.selectedServerId.value}"
                    }
                }
            )
        constructing = false

        store.select("server-1", "one")
        store.dispose()

        assertEquals(
            listOf(
                "server-1|flow=null",
                "null|flow=server-1"
            ),
            events
        )
    }

    @Test
    fun nonNullInitialServerPublishesTheSelectionFenceDuringConstruction() {
        val events = mutableListOf<String?>()

        SelectedServerStore(
            scope = RaftShellStoreScope.create("shell-initial", "server-1"),
            onSelectionChanged = events::add
        )

        assertEquals(listOf<String?>("server-1"), events)
    }

    @Test
    fun selectedServerStoreOwnsSelectionWhenPersistedSessionDefaultLags() = runBlocking {
        val store = SelectedServerStore(RaftShellStoreScope.create("shell-selection", "server-1"))
        val staleSession = session().copy(selectedServerId = "server-1", selectedServerSlug = "one")

        store.reconcile(staleSession)
        store.select("server-2", "two")
        val reconciled = store.reconcile(staleSession)

        assertTrue(store.isSelected("server-2"))
        assertEquals("server-2", store.selectedServerId.value)
        assertEquals("server-2", reconciled.serverId)
        assertEquals("two", reconciled.serverSlug)
    }

    @Test
    fun cacheKeysByStableShellIdentityWhileInitialServerRemainsBootstrapData() {
        val cache = RaftShellScopedStoreCache<Any>()
        val shellA = RaftShellStoreScope.create("shell-a", "server-1")
        val shellAAfterSwitch = RaftShellStoreScope.create("shell-a", "server-2")
        val shellB = RaftShellStoreScope.create("shell-b", "server-1")

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
    fun childNavigationScopeUsesLiveSelectionAndDoesNotResurrectBootstrapServer() = runBlocking {
        val sourceScope = RaftShellStoreScope.create("shell-child-route", "server-a")
        val selectedServerStore = SelectedServerStore(sourceScope)

        selectedServerStore.select("server-b", "two")
        assertEquals(
            "server-b",
            childNavigationStoreScope(sourceScope, selectedServerStore).initialServerId
        )

        selectedServerStore.clearActiveSelection()
        assertEquals(
            null,
            childNavigationStoreScope(sourceScope, selectedServerStore).initialServerId
        )
        // No live store is a legacy/bootstrap path; preserve its source hint
        // only in that case.
        assertEquals(
            "server-a",
            childNavigationStoreScope(sourceScope, null).initialServerId
        )
    }

    @Test
    fun cacheCreatesOneValueForConcurrentSameScopeCallers() = runBlocking {
        val scope = RaftShellStoreScope.create("shell-concurrent", "server-1")
        val contenderCount = 2
        val callersEntered = atomic(0)
        val everyCallerEntered = CompletableDeferred<Unit>()
        val creationCount = atomic(0)
        val start = CompletableDeferred<Unit>()
        val cache =
            RaftShellScopedStoreCache<Any>(
                onGetOrPutEntered = {
                    if (callersEntered.incrementAndGet() == contenderCount) {
                        everyCallerEntered.complete(Unit)
                    }
                }
            )

        val values =
            coroutineScope {
                List(contenderCount) {
                    async(Dispatchers.Default) {
                        start.await()
                        cache.getOrPut(scope) {
                            // The first creator cannot finish until the other caller has
                            // crossed the operation boundary. Without an atomic get/create,
                            // both callers would construct a distinct live candidate here.
                            runBlocking { everyCallerEntered.await() }
                            creationCount.incrementAndGet()
                            Any()
                        }
                    }
                }.also { start.complete(Unit) }.awaitAll()
            }

        assertTrue(values.all { it === values.first() })
        assertEquals(contenderCount, callersEntered.value)
        assertEquals(1, creationCount.value)
        assertEquals(1, cache.size)
    }

    @Test
    fun shellSelectionsStayIndependentWhileProcessStoreTracksLastUsed() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(session())
        val runtimeA = SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
        val runtimeB = SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
        val shellA = RaftShellAuthSessionStore(processStore, runtimeA)
        val shellB = RaftShellAuthSessionStore(processStore, runtimeB)

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
        val processStore = InMemoryRaftAuthSessionStore(session())
        val runtimeB = SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
        val shellB = RaftShellAuthSessionStore(processStore, runtimeB)
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
            InMemoryRaftAuthSessionStore(
                session().copy(selectedServerId = null, selectedServerSlug = null)
            )
        val runtimeB = SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
        val shellB = RaftShellAuthSessionStore(processStore, runtimeB)

        val refreshedForShellB = shellB.readSession()!!.copy(accessToken = "access-next")
        shellB.saveSession(refreshedForShellB)

        assertEquals("server-2", shellB.readSession()?.selectedServerId)
        assertEquals(null, processStore.readSession()?.selectedServerId)
        assertEquals("access-next", processStore.readSession()?.accessToken)
    }

    @Test
    fun atomicTokenRotationPreservesProcessSelectionAndReturnsShellSelection() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(session())
        val shellStore =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
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
        val processStore = InMemoryRaftAuthSessionStore(session())
        val shellStore =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
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
    fun legacyTokenUpdateDoesNotOverwriteAConcurrentRotation() = runBlocking {
        val racingStore = RotateAfterReadStore(InMemoryRaftAuthSessionStore(session()))
        val shellStore =
            RaftShellAuthSessionStore(
                racingStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
            )
        racingStore.rotateAfterNextRead = true

        shellStore.updateTokens("stale-access", "stale-refresh")

        assertEquals("concurrent-access", racingStore.readSession()?.accessToken)
        assertEquals("concurrent-refresh", racingStore.readSession()?.refreshToken)
    }

    @Test
    fun authenticatedReplaceDoesNotOverwriteAConcurrentProcessSelection() = runBlocking {
        val racingStore = SelectBeforeAtomicReplaceStore(InMemoryRaftAuthSessionStore(session()))
        val shellStore =
            RaftShellAuthSessionStore(
                racingStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
            )
        val replacement = shellStore.readSession()!!.copy(accessToken = "replacement-access")
        racingStore.selectBeforeNextReplace = true

        shellStore.replaceAuthenticatedSession(replacement)

        assertEquals("server-4", racingStore.readSession()?.selectedServerId)
        assertEquals("replacement-access", racingStore.readSession()?.accessToken)
        assertEquals("server-2", shellStore.readSession()?.selectedServerId)
    }

    @Test
    fun realInboxRepositoriesRouteRequestsThroughTheirShellSelections() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(session())
        val runtimeA = SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
        val runtimeB = SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
        val shellA = RaftShellAuthSessionStore(processStore, runtimeA)
        val shellB = RaftShellAuthSessionStore(processStore, runtimeB)
        val networkA = ShellRecordingNetworkClient(shellA)
        val networkB = ShellRecordingNetworkClient(shellB)
        val repositoryA = RealInboxRepository(shellA, networkA)
        val repositoryB = RealInboxRepository(shellB, networkB)

        shellB.updateSelectedServer("server-2", "two")
        val resultA = repositoryA.loadInitial(ServerId("server-1"))
        val resultB = repositoryB.loadInitial(ServerId("server-2"))

        assertTrue(resultA is DataResult.Empty)
        assertTrue(resultB is DataResult.Empty)
        assertEquals(listOf("server-1"), networkA.requestServerIds)
        assertEquals(listOf("server-2"), networkB.requestServerIds)
        assertEquals("server-2", processStore.readSession()?.selectedServerId)
    }

    @Test
    fun scopedMembersStoreKeepsRefreshAndAgentMutationOnItsShellServer() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(session())
        val shellA =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
            )
        val shellB =
            RaftShellAuthSessionStore(
                processStore,
                SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
            )
        val networkA = MemberStoreRecordingNetworkClient(shellA)
        val membersStore =
            SharedServerMembersStore(
                memberRepository = RealMemberRepository(sessionStore = shellA, networkClient = networkA)
            )

        shellB.updateSelectedServer("server-2", "two")
        val refresh = membersStore.refreshMembers(ServerId("server-1"), force = true)
        val mutation =
            membersStore.updateAgentProfile(
                serverId = ServerId("server-1"),
                request =
                    AgentProfileUpdateRequest(
                        agentId = MemberId("agent-1"),
                        displayName = "Builder",
                        description = null
                    ),
                priority = DataRequestPriority.Foreground
            )

        assertTrue(refresh is DataResult.Content)
        assertTrue(mutation is DataResult.Content)
        assertEquals("server-2", processStore.readSession()?.selectedServerId)
        assertEquals(
            listOf(
                "server-1:servers/server-1/members:GET",
                "server-1:agents:GET",
                "server-1:servers/server-1/machines:GET",
                "server-1:agents/agent-1:PATCH"
            ),
            networkA.requests
        )
    }

    @Test
    fun processClearFansOutServerLossWithoutDisposingShellIdentity() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(session())
        val runtimes = RaftShellScopedStoreCache<SelectedServerStore>()
        val runtimeA = runtimes.getOrPut(RaftShellStoreScope.create("shell-a", "server-1")) {
            SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))
        }
        val runtimeB = runtimes.getOrPut(RaftShellStoreScope.create("shell-b", "server-2")) {
            SelectedServerStore(RaftShellStoreScope.create("shell-b", "server-2"))
        }
        RaftShellAuthSessionStore(processStore, runtimeA).readSession()
        RaftShellAuthSessionStore(processStore, runtimeB).readSession()
        val invalidationObserved = CompletableDeferred<Unit>()
        val observation =
            launch(start = CoroutineStart.UNDISPATCHED) {
                processStore.sessionInvalidations.collect {
                    runtimes.clearActiveServerSelections()
                    invalidationObserved.complete(Unit)
                }
            }

        processStore.clearSession(reason = "logout", source = "test")
        withTimeout(5_000) { invalidationObserved.await() }
        observation.cancel()

        assertEquals(null, runtimeA.currentServerId.value)
        assertEquals(null, runtimeB.currentServerId.value)
        assertFalse(runtimeA.disposed.value)
        assertFalse(runtimeB.disposed.value)
    }

    @Test
    fun processClearLetsSameUserLoginRestoreTheNewPersistedDefault() = runBlocking {
        val processStore = InMemoryRaftAuthSessionStore(session())
        val selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("shell-unselected"))
        val shellStore = RaftShellAuthSessionStore(processStore, selectedServerStore)

        assertEquals("server-1", shellStore.readSession()?.selectedServerId)

        selectedServerStore.clearActiveSelection()
        processStore.replaceAuthenticatedSession(
            session().copy(selectedServerId = "server-3", selectedServerSlug = "three")
        )

        assertEquals("server-3", shellStore.readSession()?.selectedServerId)
        assertTrue(selectedServerStore.isSelected("server-3"))
    }

    @Test
    fun processClearSerializesWithConcurrentSessionReconcile() = runBlocking {
        val mutationMutex = Mutex(locked = true)
        val selectedServerStore =
            SelectedServerStore(
                scope = RaftShellStoreScope.create("shell-concurrent-clear"),
                mutex = mutationMutex
            )
        val nextSession = session().copy(selectedServerId = "server-3", selectedServerSlug = "three")

        val reconcile = async(start = CoroutineStart.UNDISPATCHED) { selectedServerStore.reconcile(nextSession) }
        val clear = async(start = CoroutineStart.UNDISPATCHED) { selectedServerStore.clearActiveSelection() }
        mutationMutex.unlock()

        reconcile.await()
        clear.await()

        assertEquals(null, selectedServerStore.selectedServerId.value)
        assertEquals("server-3", selectedServerStore.reconcile(nextSession).serverId)
    }

    @Test
    fun disposedRuntimeStopsMatchingActiveServer() {
        val runtime = SelectedServerStore(RaftShellStoreScope.create("shell-a", "server-1"))

        assertEquals("server-1", runtime.currentServerId.value)
        assertFalse(runtime.disposed.value)

        runtime.dispose()

        assertTrue(runtime.disposed.value)
    }

    @Test
    fun explicitServerRequestDoesNotRequireASelectedServer() = runBlocking {
        val processStore =
            InMemoryRaftAuthSessionStore(
                session().copy(selectedServerId = null, selectedServerSlug = null)
            )
        val runtime = SelectedServerStore(RaftShellStoreScope.create("shell-unselected"))
        val shellStore = RaftShellAuthSessionStore(processStore, runtime)

        val session = shellStore.readSession()

        assertEquals(null, session?.selectedServerId)
        assertEquals(null, session?.currentServerId)
        assertEquals(null, runtime.currentServerId.value)

        val network = ShellRecordingNetworkClient(shellStore)
        val result = RealInboxRepository(shellStore, network).loadInitial(ServerId("server-1"))
        assertTrue(result is DataResult.Empty)
        assertEquals(listOf("server-1"), network.requestServerIds)
    }

    private class ShellRecordingNetworkClient(
        private val sessionStore: RaftAuthSessionStore
    ) : RaftNetworkClient {
        val requestServerIds = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val serverId = serverIdOverride?.value ?: sessionStore.readSession()?.currentServerId
            if (serverId.isNullOrBlank()) return HomeNetworkResponse(code = 401, bodyText = null)
            requestServerIds += serverId
            return HomeNetworkResponse(
                code = 200,
                bodyText = """{"items":[],"hasMore":false,"totalCount":0,"totalUnreadCount":0}"""
            )
        }
    }

    private class MemberStoreRecordingNetworkClient(
        private val sessionStore: RaftAuthSessionStore
    ) : RaftNetworkClient {
        val requests = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val serverId = serverIdOverride?.value ?: sessionStore.readSession()?.currentServerId
            if (serverId.isNullOrBlank()) return HomeNetworkResponse(code = 401, bodyText = null)
            requests += "$serverId:$path:$method"
            return HomeNetworkResponse(code = 200, bodyText = responseBody(path))
        }

        private fun responseBody(path: String): String =
            when (path) {
                "servers/server-1/members" ->
                    """{"members":[{"userId":"user-1","name":"artin","displayName":"Artin"}]}"""
                "agents" -> "[]"
                "servers/server-1/machines" -> "[]"
                "agents/agent-1" ->
                    """{"id":"agent-1","name":"builder","displayName":"Builder","status":"online"}"""
                else -> "{}"
            }
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
                    StoredAuthServer(id = "server-3", name = "Three", slug = "three"),
                    StoredAuthServer(id = "server-4", name = "Four", slug = "four")
                )
        )

    private class SelectBeforeAtomicReplaceStore(
        private val delegate: InMemoryRaftAuthSessionStore
    ) : RaftAuthSessionStore by delegate {
        var selectBeforeNextReplace: Boolean = false

        override suspend fun replaceAuthenticatedSessionPreservingSelection(session: StoredAuthSession) {
            if (selectBeforeNextReplace) {
                selectBeforeNextReplace = false
                delegate.updateSelectedServer("server-4", "four")
            }
            delegate.replaceAuthenticatedSessionPreservingSelection(session)
        }
    }

    private class RotateAfterReadStore(
        private val delegate: InMemoryRaftAuthSessionStore
    ) : RaftAuthSessionStore by delegate {
        var rotateAfterNextRead: Boolean = false

        override suspend fun readSession(): StoredAuthSession? {
            val captured = delegate.readSession()
            if (rotateAfterNextRead && captured != null) {
                rotateAfterNextRead = false
                delegate.rotateTokensIfMatch(
                    expectedAccessToken = captured.accessToken,
                    expectedRefreshToken = captured.refreshToken,
                    newAccessToken = "concurrent-access",
                    newRefreshToken = "concurrent-refresh"
                )
            }
            return captured
        }
    }
}
