package ai.slock.android.core.data.mock

import ai.slock.compose.data.ConversationRepository
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.MemberRepository
import ai.slock.compose.data.ServerRepository
import ai.slock.compose.data.TaskFilter
import ai.slock.compose.data.TaskRepository
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ThreadId
import ai.slock.compose.data.DataRequestPriority
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockRepositoryTest {
    private val serverId = ServerId("server-slock-android")
    private val threadId = ThreadId("thread-onboarding-artin")

    @Test
    fun serverRepositoryExposesHomeBranches() {
        assertTrue(runSuspend { serverRepo(MockScenario.Content).getHomeSnapshot(serverId) } is DataResult.Content)
        assertTrue(runSuspend { serverRepo(MockScenario.Empty).getHomeSnapshot(serverId) } is DataResult.Empty)
        assertTrue(runSuspend { serverRepo(MockScenario.Error).getHomeSnapshot(serverId) } is DataResult.Error)
        assertTrue(runSuspend { serverRepo(MockScenario.NoPermission).getHomeSnapshot(serverId) } is DataResult.NoPermission)
    }

    @Test
    fun serverRepositoryExposesPhase1bBranches() {
        assertTrue(runSuspend { serverRepo(MockScenario.Content).getInbox(serverId) } is DataResult.Content)
        assertTrue(runSuspend { serverRepo(MockScenario.Empty).getChannels(serverId) } is DataResult.Empty)
        assertTrue(runSuspend { serverRepo(MockScenario.Error).getSettings(serverId) } is DataResult.Error)
        assertTrue(runSuspend { serverRepo(MockScenario.NoPermission).getSettings(serverId) } is DataResult.NoPermission)
    }

    @Test
    fun serverRepositoryExposesServerListAndSelectionBranches() {
        val servers = runSuspend { serverRepo(MockScenario.Content).getServers() }
        assertTrue(servers is DataResult.Content)
        assertEquals(serverId, (servers as DataResult.Content).data.selectedServer?.id)

        val selected = runSuspend { serverRepo(MockScenario.Content).selectServer(serverId) }
        assertTrue(selected is DataResult.Content)
        assertEquals(serverId, (selected as DataResult.Content).data.selectedServer?.id)

        assertTrue(runSuspend { serverRepo(MockScenario.Empty).getServers() } is DataResult.Empty)
        assertTrue(runSuspend { serverRepo(MockScenario.Error).selectServer(serverId) } is DataResult.Error)
        assertTrue(runSuspend { serverRepo(MockScenario.NoPermission).getServers() } is DataResult.NoPermission)
    }

    @Test
    fun conversationRepositoryExposesThreadAndSendBranches() {
        assertTrue(runSuspend { conversationRepo(MockScenario.Content).getThread(threadId) } is DataResult.Content)
        assertTrue(runSuspend { conversationRepo(MockScenario.Empty).getThread(threadId) } is DataResult.Empty)
        assertTrue(runSuspend { conversationRepo(MockScenario.Error).sendMessage(ChannelId(threadId.value), "fail") } is DataResult.Error)
        assertTrue(runSuspend { conversationRepo(MockScenario.NoPermission).sendMessage(ChannelId(threadId.value), "hello") } is DataResult.NoPermission)
    }

    @Test
    fun taskRepositoryExposesTaskBranches() {
        assertTrue(runSuspend { taskRepo(MockScenario.Content).getTasks(serverId, TaskFilter()) } is DataResult.Content)
        assertTrue(
            runSuspend {
                taskRepo(MockScenario.Content).getChannelTasks(
                    serverId,
                    ChannelId("channel-onboarding-artin"),
                    TaskFilter()
                )
            } is DataResult.Content
        )
        assertTrue(runSuspend { taskRepo(MockScenario.Empty).getTasks(serverId, TaskFilter()) } is DataResult.Empty)
        assertTrue(runSuspend { taskRepo(MockScenario.Error).getTasks(serverId, TaskFilter()) } is DataResult.Error)
        assertTrue(runSuspend { taskRepo(MockScenario.NoPermission).getTasks(serverId, TaskFilter()) } is DataResult.NoPermission)
    }

    @Test
    fun memberRepositoryExposesMemberBranches() {
        assertTrue(runSuspend { memberRepo(MockScenario.Content).getMembers(DataRequestPriority.Visible, serverId) } is DataResult.Content)
        assertTrue(runSuspend { memberRepo(MockScenario.Empty).getMembers(DataRequestPriority.Visible, serverId) } is DataResult.Empty)
        assertTrue(runSuspend { memberRepo(MockScenario.Error).getMembers(DataRequestPriority.Visible, serverId) } is DataResult.Error)
        assertTrue(runSuspend { memberRepo(MockScenario.NoPermission).getMembers(DataRequestPriority.Visible, serverId) } is DataResult.NoPermission)
    }

    private fun serverRepo(scenario: MockScenario): ServerRepository =
        MockServerRepository(scenario)

    private fun conversationRepo(scenario: MockScenario): ConversationRepository =
        MockConversationRepository(scenario)

    private fun taskRepo(scenario: MockScenario): TaskRepository =
        MockTaskRepository(scenario)

    private fun memberRepo(scenario: MockScenario): MemberRepository =
        MockMemberRepository(scenario)
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
