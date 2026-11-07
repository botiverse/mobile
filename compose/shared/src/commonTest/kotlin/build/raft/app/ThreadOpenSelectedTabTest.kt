package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Which tab a conversation opens on (artin 2026-11-03: back on an agent's DM from Activity, the page
 * showed Files). A plain open (Activity, Home, DMs, search, a conversation link) lands on Chat
 * whatever the page showed last time; only a route that names a tab opens it; a tab picked inside
 * the open conversation stays picked.
 */
class ThreadOpenSelectedTabTest {
    private fun TestScope.newStore(api: FakeThreadApi = FakeThreadApi(failChannelMessages = true)): Pair<SharedThreadStore, SharedThreadDataSource> {
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        return SharedThreadStore(dataSource = dataSource, scope = storeScope()) to dataSource
    }

    /** The store's own scope, as the other store tests use: immediate, cancelled with the test. */
    private fun TestScope.storeScope(): CoroutineScope =
        CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler))

    private fun activityOpenRoute(channelId: String, anchorMessageId: String? = null): RaftThreadScreenPayload {
        val launch =
            requireNotNull(
                buildStandaloneConversationThreadLaunchPayload(
                    conversation =
                        buildThreadLaunchConversationIdentity(
                            kind = ConversationKind.Channel,
                            channelId = ChannelId(channelId),
                        ),
                    timeContext = null,
                    anchorMessageId = anchorMessageId,
                )
            )
        assertEquals("chat", launch.selectedTab, "an Activity / Home open names no tab")
        return launch.toInitialPayload(launch.instanceId)
    }

    @Test
    fun openFromActivityLandsOnChatAfterFilesWasShownLastVisit() = runTest {
        val (store, dataSource) = newStore()
        // The last visit left this conversation on Files, and its cached page says so.
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(selectedTab = "files"))

        store.accept(SharedThreadIntent.Start(activityOpenRoute("channel-1")))

        assertEquals("chat", store.state.value.payload.selectedTab)
    }

    @Test
    fun anotherConversationDoesNotInheritTheTabPickedInThePreviousOne() = runTest {
        val (first, dataSource) = newStore()
        first.accept(SharedThreadIntent.Start(basePayload()))
        first.accept(SharedThreadIntent.SelectTab("files"))
        assertEquals("files", first.state.value.payload.selectedTab)

        val second = SharedThreadStore(dataSource = dataSource, scope = storeScope())
        second.accept(SharedThreadIntent.Start(activityOpenRoute("channel-2")))

        assertEquals("chat", second.state.value.payload.selectedTab)
    }

    @Test
    fun aRouteThatNamesTasksOpensTasks() = runTest {
        val (store, _) = newStore()

        store.accept(SharedThreadIntent.Start(basePayload().copy(selectedTab = "tasks")))

        assertEquals("tasks", store.state.value.payload.selectedTab)
    }

    @Test
    fun aTabPickedInsideTheConversationStaysPicked() = runTest {
        val (store, _) = newStore()
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.SelectTab("files"))
        store.accept(SharedThreadIntent.SelectTab(""))

        assertEquals("files", store.state.value.payload.selectedTab)
    }
}
