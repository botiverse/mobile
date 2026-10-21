package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Data-layer plan: ThreadPage (and the secondary Home router) collect Home state with
 * `collectAsState()` on [homeStateForPage], so their first frame starts from the Home store's
 * current value (pin state, rows) instead of an empty `SharedHomeState()`.
 */
class SharedHomeStateForPageTest {
    @Test
    fun pageHomeStateStartsFromTheStoresCurrentValue() = runTest {
        val authStore = InMemoryRaftAuthSessionStore(authSessionFixture())
        val store =
            SharedHomeStore(
                authStore = authStore,
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = authStore,
                        api = RootEmptyHomeApi,
                        parseHome = { _, _ -> DataResult.Content(homeSnapshot()) },
                    ),
                scope = backgroundScope,
            )
        store.accept(SharedHomeIntent.Start)
        testScheduler.advanceUntilIdle()
        val loaded = store.state.value
        val content = assertIs<SharedHomeContentState.Content>(loaded.content, "store loaded: $loaded")
        assertEquals(listOf("general"), content.snapshot.channels.map { it.name })

        // What collectAsState() uses as the first-frame value.
        val initial = store.homeStateForPage().value
        assertSame(loaded, initial, "the page's first frame is the store's current state")

        // A page without a Home store still gets a stable (empty) state.
        val absent: SharedHomeStore? = null
        assertEquals(SharedHomeState(), absent.homeStateForPage().value)
    }

    private fun homeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin",
                    kind = MemberKind.Human,
                ),
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        name = "general",
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = 0,
        )
}
