package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.base.BasePager
import build.raft.app.base.Utils
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ProcessPresenceAuthContext
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.data.ThreadReplyCacheIdentityAuthority
import build.raft.app.get
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Task #35 in #proj-apps as artin opened it on his phone: the task's own conversation page
// (SharedThreadRouteContent, the page the app mounts), its head with Status "In Review" and
// Assignee artin, the messages, the composer. No server: the conversation's messages and its task
// ref come from a fake ConversationRepository, the task from a fake RaftTasksApi.

private const val TapServerId = "task-tap-server"
internal const val TaskTapConversationId = "task-tap-conv-35"
internal const val TaskTapParentChannelId = "task-tap-proj-apps"
private const val TapEnvironmentId = "https://task-tap.fixture.example.test"
private const val TapOwnerId = "user-artin"
private const val TapTaskId = "task-tap-35"

/** 2026-11-03 10:30 UTC. */
private const val TapNowEpochMillis = 1_793_701_800_000L

/** What the next [TaskTapFixturePage] shows: how many messages its conversation has. */
internal object TaskTapFixture {
    @Volatile
    var messageCount: Int = 3

    /** The page's conversation was answered (its messages), and its task. */
    @Volatile
    var threadServed: Boolean = false

    @Volatile
    var taskServed: Boolean = false

    /** What the page asked its host to open: conversations (by their channel) and member profiles. */
    val openedConversations: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val openedProfiles: MutableList<MemberDetailIdentity> = java.util.concurrent.CopyOnWriteArrayList()

    fun reset(messages: Int) {
        messageCount = messages
        threadServed = false
        taskServed = false
        openedConversations.clear()
        openedProfiles.clear()
    }
}

internal fun taskTapFixturePageArgs(): RaftCmpPageArgs =
    RaftCmpPageArgs("task_tap_fixture", JsonObject(mapOf("instanceId" to JsonPrimitive("task-tap-fixture"))))

internal class TaskTapFixturePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { TaskTapFixtureContent() }
    }
}

@Composable
private fun TaskTapFixtureContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val provider = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val storeScope = remember { RaftShellStoreScope.create(shellInstanceId = "task-tap-fixture", initialServerId = TapServerId) }
    val session = remember { taskTapSession() }
    val sessionStore = remember { InMemoryRaftAuthSessionStore(session) }
    val replyCacheIdentityAuthority =
        remember {
            ThreadReplyCacheIdentityAuthority(
                sessionStore = sessionStore,
                selectedServerStore = SelectedServerStore(storeScope),
                authContextProvider = {
                    ProcessPresenceAuthContext(
                        environmentId = TapEnvironmentId,
                        accountId = session.user.id,
                        authorityGeneration = 1L,
                        writeGeneration = 1L,
                    )
                },
                environmentIdProvider = { TapEnvironmentId },
            )
        }
    val dataSource =
        remember {
            SharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = sessionStore,
                conversationRepository = taskTapConversationRepository(TaskTapFixture.messageCount),
                cacheEnvironmentIdProvider = { TapEnvironmentId },
                replyCacheIdentityAuthority = { replyCacheIdentityAuthority.capture() },
            )
        }
    val tasksDataSource = remember { SharedTasksDataSource(TaskTapTasksApi) }
    val initialPayload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "task-tap-fixture",
                title = "#proj-apps:#35 Task header controls do nothing when tapped",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                sourceThreadId = TaskTapConversationId,
                streamChannelId = TaskTapConversationId,
                status = "loading",
                selectedTab = "chat",
                // As the app opens a task conversation (buildAppShellThreadPayload).
                showModeTabs = false,
                showMembersAction = false,
                showSettingsAction = false,
                joined = true,
                canSend = true,
                localOffsetMinutes = 0,
                nowEpochMillis = TapNowEpochMillis,
                composer = RaftThreadComposerPayload(surfaceKind = RaftThreadSurfaceKind.Channel, enabled = true, showAsTaskToggle = false),
            )
        }
    val timeContextProvider =
        remember { RaftTimeContextProvider(RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = TapNowEpochMillis)) }
    SharedThreadRouteContent(
        initialPayload = initialPayload,
        timeContextProvider = timeContextProvider,
        bridge = bridge,
        dataSource = dataSource,
        threadSendScope = provider.threadSendScope,
        authStore = sessionStore,
        tasksDataSource = tasksDataSource,
        tasksDataStore = provider.tasksDataStore,
        storeScope = storeScope,
        composerDraftPersistSlice = provider.composerDraftPersistSlice,
        eventBus = provider.eventBus,
        onBack = {},
        onOpenThreadPayload = { payload -> TaskTapFixture.openedConversations += payload.sourceThreadId.orEmpty() },
        onOpenMemberProfile = { _, _, identity -> TaskTapFixture.openedProfiles += identity },
    )
}

private fun taskTapSession(): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = TapOwnerId, email = "artin@example.invalid", name = "artin"),
        accessToken = "task-tap-access",
        refreshToken = "task-tap-refresh",
        selectedServerId = TapServerId,
        selectedServerSlug = "task-tap",
        servers = listOf(StoredAuthServer(id = TapServerId, name = "Raft", slug = "task-tap", role = "owner")),
        allowImplicitServerFallback = false,
    )

private fun taskTapConversationRepository(messageCount: Int): ConversationRepository {
    val conversation =
        SharedThreadStoreTest.conversationSummary(TaskTapConversationId).copy(
            kind = ConversationKind.Task,
            channelType = RaftTaskConversationChannelKind,
            name = "Task header controls do nothing when tapped",
        )
    val messages =
        (1..messageCount).map { index ->
            SharedThreadStoreTest.message(
                threadId = TaskTapConversationId,
                id = "task-tap-m$index",
                seq = index.toLong(),
                body = "Message $index: the 负责人 chip in the task head does nothing when tapped.",
            )
        }
    val empty = DataResult.Content(SharedThreadStoreTest.threadSnapshot(threadId = TaskTapConversationId, messages = emptyList(), conversation = conversation))
    val fake =
        SharedThreadStoreTestFakes.FakeConversationRepository(
            threadResult = DataResult.Content(SharedThreadStoreTest.threadSnapshot(threadId = TaskTapConversationId, messages = messages, conversation = conversation)),
            olderResult = empty,
            newerResult = empty,
        )
    return object : ConversationRepository by fake {
        override suspend fun getThread(threadId: build.raft.app.model.ThreadId, includeFollowedSummaries: Boolean, limit: Int) =
            fake.getThread(threadId, includeFollowedSummaries, limit).also { TaskTapFixture.threadServed = true }

        override suspend fun loadTaskConversationRef(conversationChannelId: ChannelId): DataResult<TaskConversationChannelRef> =
            DataResult.Content(TaskConversationChannelRef(conversationChannelId, ChannelId(TaskTapParentChannelId), TaskId(TapTaskId)))
    }
}

/** botiverse/slock `GET /tasks/channel/:id`: task #35, In Review, assigned to artin, created by AppFather. */
private val TaskTapTaskRow =
    """{"id":"$TapTaskId","messageId":"$TapTaskId","hostMessageId":null,"sourceMessageId":null,"conversationReplyCount":3,""" +
        """"conversationChannelId":"$TaskTapConversationId","conversationAnchored":true,"channelId":"$TaskTapParentChannelId",""" +
        """"channelName":"proj-apps","channelType":"channel","taskNumber":35,"title":"Task header controls do nothing when tapped",""" +
        """"description":null,"status":"in_review","createdByType":"agent","createdById":"agent-appfather","createdByName":"AppFather",""" +
        """"claimedByType":"user","claimedById":"$TapOwnerId","claimedByName":"artin","claimedAt":"2026-11-03T10:00:00.000Z",""" +
        """"completedAt":null,"createdAt":"2026-11-03T09:00:00.000Z","updatedAt":"2026-11-03T10:00:00.000Z","revision":3,""" +
        """"isLegacy":false,"readOnlyReason":null}"""

private object TaskTapTasksApi : RaftTasksApi {
    override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"tasks\":[$TaskTapTaskRow]}")

    override suspend fun channelTasks(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"tasks\":[$TaskTapTaskRow]}").also { TaskTapFixture.taskServed = true }

    override suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"tasks\":[$TaskTapTaskRow]}").also { TaskTapFixture.taskServed = true }

    override suspend fun taskByNumber(serverId: ServerId, channelId: ChannelId, taskNumber: Int, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"task\":$TaskTapTaskRow}")

    override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{}")
}
