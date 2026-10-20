package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
