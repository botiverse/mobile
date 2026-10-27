package build.raft.app.data

import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Saved list as the first offline resource (persistence audit item 4): a cold start / offline open
 * shows the stored list as a refreshing stand-in, the network replaces it, a failure keeps it, and only
 * confirmed unsaves change it.
 */
class SharedHomeSavedListOfflineTest {
    private class Device(scope: CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { "https://api.example" }, scope = scope)

        /** A new process over the same disk: fresh memory, same rows. */
        fun savedStateCache(): SharedHomeSavedStateCache =
            SharedHomeSavedStateCache(offline.resource(SavedListResourceSpec))

        suspend fun storedKeys(userId: String = "user-1", serverId: String = "server-1"): List<String>? {
            val resource = offline.resource(SavedListResourceSpec)
            return resource.read(resource.key(userId, serverId, Unit)!!)?.items?.map { it.key }
        }
    }

    @Test
    fun coldStartShowsStoredListThenNetworkReplacesIt() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, listOf(savedListRow("message-1"), savedListRow("message-2")))

        val network = PendingSavedRepository()
        val store = savedStore(device, network)
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        runCurrent()

        val standIn = store.state.value.saved
        assertEquals(listOf("saved:message-1", "saved:message-2"), standIn.items.map { it.key })
        assertEquals(false, standIn.loading)
        assertEquals(true, standIn.refreshing)

        network.complete(
            DataResult.Content(SavedSnapshot(server = searchTestServer(ServerId("server-1")), items = listOf(savedListRow("message-3"))))
        )
        runCurrent()

        assertEquals(listOf("saved:message-3"), store.state.value.saved.items.map { it.key })
        assertEquals(false, store.state.value.saved.refreshing)
        assertEquals(listOf("saved:message-3"), device.storedKeys())
        store.clear()
    }

    @Test
    fun coldStartOfflineKeepsStoredListInsteadOfErrorBlank() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, listOf(savedListRow("message-1")))

        val store = savedStore(device, FailingSavedRepository())
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        runCurrent()

        val saved = store.state.value.saved
        assertEquals(listOf("saved:message-1"), saved.items.map { it.key })
        assertEquals(false, saved.refreshing)
        assertEquals(false, saved.loading)
        assertEquals("saved_network", saved.errorCode)
        assertEquals(listOf("saved:message-1"), device.storedKeys())
        store.clear()
    }

    @Test
    fun storedListIsIsolatedPerUserAndServer() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, listOf(savedListRow("message-1")))

        listOf(sampleSession(userId = "user-2"), sampleSession(serverId = "server-2")).forEach { other ->
            val store = savedStore(device, PendingSavedRepository(), other)
            store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
            runCurrent()
            assertEquals(emptyList(), store.state.value.saved.items)
            assertEquals(true, store.state.value.saved.loading)
            store.clear()
        }
        assertNull(device.storedKeys(userId = "user-2"))
        assertNull(device.storedKeys(serverId = "server-2"))
        assertEquals(listOf("saved:message-1"), device.storedKeys())
    }

    @Test
    fun sessionResetKeepsStoredListButWipeAllClearsIt() = runTest {
        val device = Device(backgroundScope)
        val cache = device.savedStateCache()
        loadOnce(device, listOf(savedListRow("message-1")), cache)

        // Server switch / session reset: only the in-memory copy goes.
        cache.clear()
        assertEquals(listOf("saved:message-1"), device.storedKeys())

        // wipeAll (sign-out no longer calls it: AccountLocalDataTest) takes the Saved list with it.
        device.offline.wipeAllAsync()
        runCurrent()
        assertNull(device.storedKeys())
        val store = savedStore(device, PendingSavedRepository())
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        runCurrent()
        assertEquals(emptyList(), store.state.value.saved.items)
        store.clear()
    }

    @Test
    fun successfulUnsaveUpdatesStoredListAndFailedOneDoesNot() = runTest {
        val device = Device(backgroundScope)
        val network = DeferredUnsaveSavedRepository(items = listOf(savedListRow("message-1"), savedListRow("message-2")))
        val store = savedStore(device, network)
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        runCurrent()
        val (first, second) = store.state.value.saved.items

        store.dispatch(SharedHomeSearchSavedIntent.Unsave(first))
        runCurrent()
        // Optimistic: gone from the panel, still stored until the server confirms.
        assertEquals(listOf("saved:message-2"), store.state.value.saved.items.map { it.key })
        assertEquals(listOf("saved:message-1", "saved:message-2"), device.storedKeys())
        network.complete("message-1", DataResult.Content(SavedMessageMutationResult(MessageId("message-1"))))
        runCurrent()
        assertEquals(listOf("saved:message-2"), device.storedKeys())

        store.dispatch(SharedHomeSearchSavedIntent.Unsave(second))
        runCurrent()
        network.complete("message-2", DataResult.Error(ScreenError("delete_failed")))
        runCurrent()
        assertEquals(listOf("saved:message-2"), store.state.value.saved.items.map { it.key })
        assertEquals(listOf("saved:message-2"), device.storedKeys())
        store.clear()
    }

    @Test
    fun confirmedUnsaveFromMessageActionsLeavesStoredList() = runTest {
        val device = Device(backgroundScope)
        val cache = device.savedStateCache()
        loadOnce(device, listOf(savedListRow("message-1"), savedListRow("message-2")), cache)

        cache.recordMessageUnsaved("server-1", "user-1", MessageId("message-1"))

        assertEquals(listOf("saved:message-2"), cache.read("server-1", "user-1")?.items?.map { it.key })
        assertEquals(listOf("saved:message-2"), device.storedKeys())
    }

    @Test
    fun storedListKeepsTheFirstPageOnly() {
        val ids = (1..150).map { "message-$it" }
        val fitted =
            SavedListResourceSpec.fit(
                SavedListRecord(items = ids.map(::savedListRow), totalCount = 150, hasMore = false, nextOffset = 150)
            )
        assertEquals(100, fitted.items.size)
        assertEquals("saved:message-1", fitted.items.first().key)
        assertEquals(150, fitted.totalCount)
        assertEquals(true, fitted.hasMore)
        assertNull(fitted.nextOffset)
    }

    private suspend fun TestScope.loadOnce(
        device: Device,
        items: List<InboxItem>,
        cache: SharedHomeSavedStateCache = device.savedStateCache(),
    ) {
        val store = savedStore(device, DeferredUnsaveSavedRepository(items), cache = cache)
        store.dispatch(SharedHomeSearchSavedIntent.OpenSaved)
        runCurrent()
        assertEquals(items.map { it.key }, store.state.value.saved.items.map { it.key })
        assertEquals(items.map { it.key }, device.storedKeys())
        store.clear()
    }

    private fun TestScope.savedStore(
        device: Device,
        savedRepository: SavedRepository,
        session: StoredAuthSession = sampleSession(),
        cache: SharedHomeSavedStateCache = device.savedStateCache(),
    ): SharedHomeSearchSavedStore =
        SharedHomeSearchSavedStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            savedRepository = savedRepository,
            searchRepository = EmptySearchRepository,
            savedStateCache = cache,
            scope = backgroundScope,
        )
}
