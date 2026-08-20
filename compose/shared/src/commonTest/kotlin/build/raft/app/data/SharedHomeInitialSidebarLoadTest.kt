package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedHomeInitialSidebarLoadTest {
    @Test
    fun homeAndSidebarStartTogetherAndPublishOneCompleteSnapshot() = runBlocking {
        // OHOS commonTest still has no kotlinx-coroutines-test KLIB. These
        // explicit barriers make the required interleaving deterministic
        // without delay/sleep or a wall-clock duration assertion.
        val homeStarted = CompletableDeferred<Unit>()
        val sidebarStarted = CompletableDeferred<Unit>()
        val allowHome = CompletableDeferred<Unit>()
        val allowSidebar = CompletableDeferred<Unit>()
        val authStore = InMemorySlockAuthSessionStore(authSessionFixture())
        val api =
            object : SlockHomeApi {
                override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse {
                    homeStarted.complete(Unit)
                    allowHome.await()
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText = """[{"id":"channel-1","name":"general","type":"channel"}]"""
                    )
                }

                override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "[]")

                override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "{}")

                override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText = """[{"serverId":"server-1","unreadCount":0}]"""
                    )
            }
        val expectedSidebar =
            SettingsServerSidebarOrderSnapshot(
                pinnedChannelIds = listOf("channel-1"),
                pinnedOrder = listOf("channel-1")
            )
        val store =
            SharedHomeStore(
                authStore = authStore,
                dataSource =
                    SharedHomeDataSource(
                        sessionStore = authStore,
                        api = api,
                        parseHome = ::parseSlockHome
                    ),
                scope = this,
                loadSidebarOrder = {
                    sidebarStarted.complete(Unit)
                    allowSidebar.await()
                    DataResult.Content(expectedSidebar)
                }
            )

        val loadJob = launch { store.accept(SharedHomeIntent.Start) }
        homeStarted.await()
        repeat(10) {
            if (!sidebarStarted.isCompleted) yield()
        }
        assertTrue(
            sidebarStarted.isCompleted,
            "sidebar-order must start while the Home content request is still suspended"
        )

        allowHome.complete(Unit)
        repeat(10) { yield() }
        assertFalse(
            store.state.value.content is SharedHomeContentState.Content,
            "Home must not publish a channel-only snapshot before sidebar-order completes"
        )

        allowSidebar.complete(Unit)
        loadJob.join()

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals(listOf("channel-1"), content.snapshot.channels.map { it.channelId.value })
        assertEquals(expectedSidebar, content.snapshot.sidebarOrder)
        store.clear()
    }
}
