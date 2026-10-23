package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A server (or account) switch starts Activity on All, like web
 * `registerServerReset` (botiverse/slock 1bb70cbeb, inboxStore.ts:1854-1898).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreServerSwitchTest {

    @Test
    fun switchingServerResetsTheFilterToAllAndLoadsAllForTheNewServer() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox = FilterRecordingInbox()
        val store = activityStore(backgroundScope, inbox, authStore = auth, nowMillis = { testScheduler.currentTime })
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)

        // Same server: the chosen filter is kept.
        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)

        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals(InboxFilter.All, store.state.value.selectedFilter)
        assertEquals("server-2", store.state.value.ownerServerId)
        assertEquals(
            listOf("server-1" to InboxFilter.Unread, "server-1" to InboxFilter.Unread, "server-2" to InboxFilter.All),
            inbox.loads
        )
        assertEquals(listOf("channel:server-2-row"), store.state.value.items.map { it.key })
    }

    /**
     * Hands 20db9f61: switching back to a server whose feed was loaded before showed nothing new
     * and never refetched. The switch shows that server's cached rows at once and revalidates them.
     */
    @Test
    fun switchingBackToALoadedServerShowsItsCachedFeedThenRefetchesIt() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val revalidation = CompletableDeferred<Unit>()
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("a-cached")), serverId = "server-1"),
                        snapshot(listOf(activityItem("b-row")), serverId = "server-2"),
                        snapshot(listOf(activityItem("a-fresh")), serverId = "server-1")
                    ),
                beforeInitialLoad = { call -> if (call == 3) revalidation.await() }
            )
        val store =
            activityStore(
                backgroundScope,
                inbox,
                authStore = auth,
                persistSlices = listOf(createSharedActivityPersistSlice(InMemoryPersistStorage()))
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(listOf("channel:a-cached"), store.state.value.items.map { it.key })

        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()
        assertEquals("server-2", store.state.value.ownerServerId)
        assertEquals(listOf("channel:b-row"), store.state.value.items.map { it.key })

        auth.saveSession(twoServerSession(selected = "server-1"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-1"))
        // Repeated intents for the server being switched to join the one load.
        store.dispatch(SharedActivityIntent.ServerSelected("server-1"))
        runCurrent()

        assertEquals(listOf("server-1", "server-2", "server-1"), inbox.initialLoadServerIds.map { it.value })
        val whileFetching = store.state.value
        assertEquals("server-1", whileFetching.ownerServerId)
        assertEquals(listOf("channel:a-cached"), whileFetching.items.map { it.key }, "cached A before the response")
        assertTrue(whileFetching.refreshing, "a quiet refresh over the cached rows")
        assertFalse(whileFetching.loading, "no blank loading state when A is cached")

        revalidation.complete(Unit)
        runCurrent()
        assertEquals(listOf("channel:a-fresh"), store.state.value.items.map { it.key })
        assertFalse(store.state.value.refreshing)

        // Same server again: no-op.
        store.dispatch(SharedActivityIntent.ServerSelected("server-1"))
        runCurrent()
        assertEquals(3, inbox.initialLoadCount)
    }

    private fun twoServerSession(selected: String): StoredAuthSession =
        sampleSession(serverId = selected).copy(
            servers = listOf(StoredAuthServer(id = "server-1", name = "One"), StoredAuthServer(id = "server-2", name = "Two"))
        )

    private class FilterRecordingInbox : InboxRepository {
        val loads = mutableListOf<Pair<String, InboxFilter>>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            loads += serverId.value to filter
            return DataResult.Content(snapshot(listOf(activityItem("${serverId.value}-row")), serverId = serverId.value))
        }

        override suspend fun loadMore(serverId: ServerId, filter: InboxFilter, offset: Int, limit: Int): DataResult<InboxSnapshot> =
            DataResult.Empty(EmptyReason.NoInboxItems)

        override suspend fun markDone(channelId: build.raft.app.model.ChannelId, frontier: InboxDoneFrontier): DataResult<InboxDoneResult> =
            error("unused")
    }
}
