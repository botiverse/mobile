package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #164: Home's two unread reads, `GET /channels/unread?summary=1` (per-row counts) and
 * `GET /servers/unread-summary` (server badges), while the read warehouse is behind. The answers
 * are the server's (botiverse/slock f6ace2b9): `routes/rwStaleRead.ts` serves a remembered body
 * with `X-Raft-Stale: rw_overloaded` (the summary also says `"stale": true` in its body); past 5
 * minutes or with nothing remembered `routes/errorResponse.ts` answers `503` + `Retry-After: 2` +
 * `{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}`.
 *
 * Runs the real [SharedHomeDataSource] and [parseRaftHome] under [SharedHomeStore] on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomeStoreRwStaleTest {

    @Test
    fun anOverloadedChannelUnreadKeepsTheShownCountsAndMarksThemStale() = runTest {
        val api = ScriptedUnreadHomeApi(channelUnread = mutableListOf(channelUnread(5), overloaded()))
        val store = homeStore(api)
        store.accept(SharedHomeIntent.Start)
        assertEquals(5, store.rowUnread())

        store.accept(SharedHomeIntent.Refresh)

        assertEquals(5, store.rowUnread(), "the row keeps its count, never zero")
        assertTrue(store.state.value.unreadStale)
    }

    @Test
    fun anOverloadedServerSummaryKeepsTheServerBadgesAndMarksThemStale() = runTest {
        val api =
            ScriptedUnreadHomeApi(
                serverUnread = mutableListOf(serverUnread("server-1" to 5, "server-2" to 7), overloaded())
            )
        val store = homeStore(api)
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.Refresh)

        val state = store.state.value
        assertEquals(mapOf("server-1" to 5, "server-2" to 7), state.serverUnreadCounts)
        assertEquals(5, state.appBadgeCount.count)
        assertTrue(state.unreadStale)
    }

    @Test
    fun aRememberedUnreadNeverReplacesShownCountsButMarksThemStale() = runTest {
        val api =
            ScriptedUnreadHomeApi(
                channelUnread = mutableListOf(channelUnread(5), stale(channelUnread(0))),
                serverUnread = mutableListOf(serverUnread("server-1" to 5), stale(serverUnread("server-1" to 0)))
            )
        val store = homeStore(api)
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.Refresh)

        val state = store.state.value
        assertEquals(5, store.rowUnread())
        assertEquals(mapOf("server-1" to 5), state.serverUnreadCounts)
        assertTrue(state.unreadStale)
    }

    @Test
    fun aFreshLoadClearsStaleThroughOneBackgroundRecoveryLoad() = runTest {
        val api =
            ScriptedUnreadHomeApi(
                channelUnread = mutableListOf(channelUnread(5), overloaded(), channelUnread(2))
            )
        val store = homeStore(api)
        store.accept(SharedHomeIntent.Start)
        store.accept(SharedHomeIntent.Refresh)
        assertTrue(store.state.value.unreadStale)
        assertEquals(2, api.unreadPriorities.size)

        advanceTimeBy(RecoveryDelayMillis - 1)
        runCurrent()
        assertEquals(2, api.unreadPriorities.size, "no load before the backoff")
        advanceTimeBy(1)
        runCurrent()

        assertEquals(DataRequestPriority.Background, api.unreadPriorities.getOrNull(2), "the recovery load runs at background priority")
        assertFalse(store.state.value.unreadStale, "the fresh unread clears the notice")
        assertEquals(2, store.rowUnread())
        advanceTimeBy(10 * RecoveryDelayMillis)
        runCurrent()
        assertEquals(3, api.unreadPriorities.size, "nothing more is scheduled once fresh")
    }

    @Test
    fun theFollowedThreadReconcileReadSurfacesStaleAndOverloadAsTypedFacts() = runTest {
        val api = ScriptedUnreadHomeApi(channelUnread = mutableListOf(stale(channelUnread(5)), overloaded()))
        val dataSource = homeDataSource(api)

        val remembered = dataSource.channelUnreadSummary(ServerId("server-1"), DataRequestPriority.Background)
        val overload = dataSource.channelUnreadSummary(ServerId("server-1"), DataRequestPriority.Background)

        assertTrue((remembered as DataResult.Content).data.stale, "the header becomes a typed flag")
        assertTrue(overload.isRwOverloadedError, "the 503 body's code becomes a typed error")
    }

    @Test
    fun coldStartWithACacheAndAnOverloadShowsTheLastKnownCountsAsStale() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        val first = homeStore(ScriptedUnreadHomeApi(channelUnread = mutableListOf(channelUnread(5))), storage)
        first.accept(SharedHomeIntent.Start)
        assertEquals(5, first.rowUnread())

        val reopened =
            homeStore(
                ScriptedUnreadHomeApi(channelUnread = mutableListOf(overloaded()), serverUnread = mutableListOf(overloaded())),
                storage
            )
        reopened.accept(SharedHomeIntent.Start)

        val content = reopened.content()
        assertFalse(content.isDurableIdentityCache, "the network Home has replaced the cache")
        assertEquals(5, content.displayRowHints()["channel-1"]?.unreadCount, "the last-known count stays on screen")
        assertFalse(content.snapshot.channels.single().unreadCountKnown, "and is never turned into a known zero")
        assertTrue(reopened.state.value.unreadStale)
    }

    /** A normal open shows the last-known counts without the notice; only the server's answer may raise it. */
    @Test
    fun coldStartShowsTheLastKnownCountsWithoutTheNoticeUntilTheServerSaysTheyAreStale() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        homeStore(ScriptedUnreadHomeApi(channelUnread = mutableListOf(channelUnread(5))), storage)
            .accept(SharedHomeIntent.Start)

        val answer = CompletableDeferred<Unit>()
        val reopened =
            homeStore(
                ScriptedUnreadHomeApi(channelUnread = mutableListOf(overloaded()), gate = answer),
                storage
            )
        val start = backgroundScope.launch { reopened.accept(SharedHomeIntent.Start) }
        runCurrent()
        assertEquals(5, reopened.content().displayRowHints()["channel-1"]?.unreadCount, "the cache is shown")
        assertFalse(reopened.state.value.unreadStale, "no notice before the server answers")

        answer.complete(Unit)
        start.join()
        assertTrue(reopened.state.value.unreadStale)
        assertEquals(5, reopened.content().displayRowHints()["channel-1"]?.unreadCount)
    }

    @Test
    fun noCacheAndAnOverloadLeavesTheCountsUnknownNotZero() = runTest {
        val store =
            homeStore(
                ScriptedUnreadHomeApi(channelUnread = mutableListOf(overloaded()), serverUnread = mutableListOf(overloaded()))
            )
        store.accept(SharedHomeIntent.Start)

        val state = store.state.value
        val row = store.content().snapshot.channels.single()
        assertFalse(row.unreadCountKnown, "the count is not obtained, which is not zero")
        assertTrue(state.appBadgeCount.isStale, "the app badge is never a fresh zero")
        assertTrue(state.unreadStale)
    }

    private fun TestScope.homeDataSource(api: ScriptedUnreadHomeApi): SharedHomeDataSource {
        val authStore = InMemoryRaftAuthSessionStore(session())
        return SharedHomeDataSource(
            sessionStore = authStore,
            api = api,
            parseHome = { payload, session -> parseRaftHome(payload, session) },
            parseDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
    }

    private fun TestScope.homeStore(
        api: ScriptedUnreadHomeApi,
        storage: InMemoryRaftKeyValueStore? = null,
        dataSource: SharedHomeDataSource = homeDataSource(api),
    ): SharedHomeStore =
        SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session()),
            dataSource = dataSource,
            scope = backgroundScope,
            staleRecoveryDelayMillis = { _, _ -> RecoveryDelayMillis },
            persistSlices =
                storage?.let {
                    listOf(
                        createSharedHomeIdentityPersistSlice(SharedPreferencePersistStorage(it)),
                        createSharedHomeRowHintPersistSlice(HomeRowHintPersistStorage(it)),
                    )
                }.orEmpty(),
        )

    private fun SharedHomeStore.content(): SharedHomeContentState.Content =
        state.value.content as? SharedHomeContentState.Content ?: error("Home has no content: ${state.value.content}")

    private fun SharedHomeStore.rowUnread(): Int =
        content().snapshot.channels.single { it.channelId == ChannelId("channel-1") }.unreadCount

    private fun session() =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"),
                    StoredAuthServer(id = "server-2", name = "Other", slug = "other"),
                ),
        )

    private fun channelUnread(count: Int): HomeNetworkResponse =
        HomeNetworkResponse(200, channelUnreadSummaryBody(mapOf("channel-1" to count)))

    private fun serverUnread(vararg counts: Pair<String, Int>): HomeNetworkResponse =
        HomeNetworkResponse(200, counts.joinToString(",", "[", "]") { (id, count) -> """{"serverId":"$id","unreadCount":$count}""" })

    /** The same body served from the server's remembered copy (`rwStaleRead.ts`). */
    private fun stale(response: HomeNetworkResponse): HomeNetworkResponse =
        response.copy(responseHeaders = mapOf("X-Raft-Stale" to listOf("rw_overloaded")))

    private fun overloaded(): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 503,
            bodyText = null,
            errorBodyText = """{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}""",
            responseHeaders = mapOf("Retry-After" to listOf("2"))
        )

    /** One joined channel; the two unread reads answer from their scripts (the last one repeats). */
    private class ScriptedUnreadHomeApi(
        private val channelUnread: MutableList<HomeNetworkResponse> =
            mutableListOf(HomeNetworkResponse(200, channelUnreadSummaryBody(mapOf("channel-1" to 5)))),
        private val serverUnread: MutableList<HomeNetworkResponse> =
            mutableListOf(HomeNetworkResponse(200, """[{"serverId":"server-1","unreadCount":5}]""")),
        /** Holds the unread answer until completed: a Home still waiting on the network. */
        private val gate: CompletableDeferred<Unit>? = null,
    ) : RaftHomeApi {
        val unreadPriorities = mutableListOf<DataRequestPriority>()
        private var serverUnreadCalls = 0

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, """[{"id":"channel-1","name":"general","type":"channel","joined":true}]""")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, "[]")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse {
            unreadPriorities += priority
            gate?.await()
            return channelUnread.getOrElse(unreadPriorities.size - 1) { channelUnread.last() }
        }

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            serverUnread.getOrElse(serverUnreadCalls++) { serverUnread.last() }
    }

    private companion object {
        const val RecoveryDelayMillis = 15_000L
    }
}
