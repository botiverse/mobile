package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A server (or account) switch starts Activity on that server's last Filter choice, All when
 * there is none, like web `registerServerReset` (botiverse/slock #11383; before it,
 * 1bb70cbeb, always All).
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

    /**
     * Hands b91f94e5: after a switch the new server's inbox answered in 6s but its rows waited
     * another 6s for the server member list, so the old cached rows stayed up and the feed
     * looked never reloaded. The rows show when the inbox answers; profiles join when members land.
     */
    @Test
    fun aSwitchShowsTheNewServersRowsWithoutWaitingForItsMemberList() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val server2Members = CompletableDeferred<Unit>()
        val members =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
                    if (serverId.value == "server-2") server2Members.await()
                    return DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = serverId.value),
                            members =
                                listOf(
                                    MemberProfile(
                                        id = MemberId("agent-${serverId.value}"),
                                        displayName = "Agent ${serverId.value}",
                                        handle = "agent",
                                        kind = MemberKind.Agent
                                    )
                                )
                        )
                    )
                }
            }
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(listOf(activityItem("a-row")), serverId = "server-1"),
                        snapshot(listOf(activityItem("b-row")), serverId = "server-2")
                    )
            )
        val store = activityStore(backgroundScope, inbox, authStore = auth, memberRepository = members)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals("Agent server-1", store.state.value.senderProfiles["agent-server-1"]?.displayName)

        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()

        val beforeMembers = store.state.value
        assertEquals("server-2", beforeMembers.ownerServerId)
        assertEquals(listOf("channel:b-row"), beforeMembers.items.map { it.key }, "rows while members load")
        assertFalse(beforeMembers.loading || beforeMembers.refreshing, "the switch load finished")
        assertTrue(beforeMembers.senderProfiles.isEmpty(), "no server-1 profiles carried over: ${beforeMembers.senderProfiles}")

        server2Members.complete(Unit)
        runCurrent()
        assertEquals(listOf("channel:b-row"), store.state.value.items.map { it.key })
        assertEquals("Agent server-2", store.state.value.senderProfiles["agent-server-2"]?.displayName)
    }

    @Test
    fun aPickIsRememberedPerServerAndASwitchOpensThatServersChoice() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox = FilterRecordingInbox()
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)
        preferences.write("server-2", InboxFilter.Mentions)
        val store =
            activityStore(
                backgroundScope,
                inbox,
                authStore = auth,
                filterPreferences = preferences,
                nowMillis = { testScheduler.currentTime }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Unread))
        runCurrent()
        assertEquals(InboxFilter.Unread, preferences.read("server-1"), "the pick is server-1's from now on")

        // Shell switch to server-2: it opens on its own remembered Mentions, not server-1's Unread.
        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-2"))
        runCurrent()
        assertEquals(InboxFilter.Mentions, store.state.value.selectedFilter)

        // And back: server-1 opens on Unread again.
        auth.saveSession(twoServerSession(selected = "server-1"))
        store.dispatch(SharedActivityIntent.ServerSelected("server-1"))
        runCurrent()
        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)

        assertEquals(
            listOf(
                "server-1" to InboxFilter.All,
                "server-1" to InboxFilter.Unread,
                "server-2" to InboxFilter.Mentions,
                "server-1" to InboxFilter.Unread,
            ),
            inbox.loads
        )
        // The other server's choice was never overwritten by the switch.
        assertEquals(InboxFilter.Mentions, preferences.read("server-2"))
    }

    @Test
    fun theFirstLoadOpensTheRememberedFilterWithoutLoadingAllFirst() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox = FilterRecordingInbox()
        val preferences = ActivityFilterPreferenceRepository(InMemoryRaftKeyValueStore())
        preferences.write("server-1", InboxFilter.Pinned)
        val store = activityStore(backgroundScope, inbox, authStore = auth, filterPreferences = preferences)

        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals(InboxFilter.Pinned, store.state.value.selectedFilter)
        assertEquals(listOf("server-1" to InboxFilter.Pinned), inbox.loads)
    }

    @Test
    fun anAccountSwitchOnTheSameStoreOpensTheNewServersRememberedFilter() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val inbox = FilterRecordingInbox()
        val preferences = ActivityFilterPreferenceRepository(InMemoryRaftKeyValueStore())
        preferences.write("server-2", InboxFilter.Unread)
        val store = activityStore(backgroundScope, inbox, authStore = auth, filterPreferences = preferences)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // No ServerSelected: the load itself finds the owner changed (the load-path reset).
        auth.saveSession(twoServerSession(selected = "server-2"))
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        assertEquals(InboxFilter.Unread, store.state.value.selectedFilter)
        assertEquals(listOf("server-1" to InboxFilter.All, "server-2" to InboxFilter.Unread), inbox.loads)
    }

    @Test
    fun clearingTheChipBackToAllForgetsTheChoice() = runTest {
        val auth = InMemoryRaftAuthSessionStore(twoServerSession(selected = "server-1"))
        val keyValues = InMemoryRaftKeyValueStore()
        val preferences = ActivityFilterPreferenceRepository(keyValues)
        val store = activityStore(backgroundScope, FilterRecordingInbox(), authStore = auth, filterPreferences = preferences)
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.Mentions))
        runCurrent()
        assertEquals("mentions", keyValues.getString("raft.activity.filter.server-1"))

        store.dispatch(SharedActivityIntent.SetFilter(InboxFilter.All))
        runCurrent()

        assertEquals(InboxFilter.All, store.state.value.selectedFilter)
        assertEquals(null, keyValues.getString("raft.activity.filter.server-1"))
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
