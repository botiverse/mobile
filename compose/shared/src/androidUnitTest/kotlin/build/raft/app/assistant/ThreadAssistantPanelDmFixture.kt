package build.raft.app.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.RAFT_EMBEDDED_CHILD_PAGE_PARAM
import build.raft.app.RaftThreadScreenPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftTimeContextProvider
import build.raft.app.SharedThreadRouteContent
import build.raft.app.base.BasePager
import build.raft.app.base.Utils
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ProcessPresenceAuthContext
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.SharedThreadDataSource
import build.raft.app.get
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentityAuthority
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The assistant's DM as the real conversation page renders it, without a server: the same data
// path as the thread visual fixture compose_thread_newer_auto_pagination_visual
// (ThreadNewerAutoPaginationVisualFixture.kt): SharedThreadRouteContent over a SharedThreadDataSource
// whose only fake is the typed SharedThreadNetworkApi, answering with wire messages.

private const val DmServerId = "assistant-dm-fixture-server"
internal const val AssistantDmFixtureChannelId = "assistant-dm-fixture-dm"
private const val DmEnvironmentId = "https://assistant-dm.fixture.example.test"
private const val DmOwnerId = "assistant-dm-fixture-owner"
internal const val AssistantDmFixtureAgentId = "assistant-dm-fixture-otto"

/** 2026-10-30 09:30 UTC: the messages below are this morning's. */
private const val DmNowEpochMillis = 1_793_352_600_000L

/** The page args for [AssistantDmFixturePage], as RaftChildPage marks an embedded child page. */
internal fun assistantDmFixturePageArgs(): RaftCmpPageArgs =
    RaftCmpPageArgs(
        "assistant_dm_fixture",
        JsonObject(mapOf(RAFT_EMBEDDED_CHILD_PAGE_PARAM to JsonPrimitive(true), "instanceId" to JsonPrimitive("assistant-dm-fixture"))),
    )

/** The conversation page for the assistant's DM, its data from [AssistantDmFixtureNetworkApi]. */
internal class AssistantDmFixturePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AssistantDmFixtureContent() }
    }
}

@Composable
private fun AssistantDmFixtureContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val provider = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val storeScope = remember { RaftShellStoreScope.create(shellInstanceId = "assistant-dm-fixture", initialServerId = DmServerId) }
    val session = remember { assistantDmSession() }
    val sessionStore = remember { InMemoryRaftAuthSessionStore(session) }
    val replyCacheIdentityAuthority =
        remember {
            ThreadReplyCacheIdentityAuthority(
                sessionStore = sessionStore,
                selectedServerStore = SelectedServerStore(storeScope),
                authContextProvider = {
                    ProcessPresenceAuthContext(
                        environmentId = DmEnvironmentId,
                        accountId = session.user.id,
                        authorityGeneration = 1L,
                        writeGeneration = 1L,
                    )
                },
                environmentIdProvider = { DmEnvironmentId },
            )
        }
    val dataSource =
        remember {
            SharedThreadDataSource(
                networkApi = AssistantDmFixtureNetworkApi,
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { DmEnvironmentId },
                replyCacheIdentityAuthority = { replyCacheIdentityAuthority.capture() },
                threadPageSizeProvider = { 30 },
                diagnosticsLogger = {},
            )
        }
    val tasksDataSource = remember { SharedTasksDataSource(AssistantDmFixtureTasksApi) }
    val initialPayload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "assistant-dm-fixture",
                title = "Otto",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                sourceThreadId = AssistantDmFixtureChannelId,
                streamChannelId = AssistantDmFixtureChannelId,
                status = "loading",
                dmPeerName = "Otto",
                showMembersAction = false,
                showSettingsAction = false,
                localOffsetMinutes = 0,
                nowEpochMillis = DmNowEpochMillis,
            )
        }
    // A fixed clock: today is the messages' day, so the stamps are times, not dates.
    val timeContextProvider =
        remember { RaftTimeContextProvider(RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = DmNowEpochMillis)) }
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
    )
}

private fun assistantDmSession(): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = DmOwnerId, email = "artin@example.invalid", name = "Artin"),
        accessToken = "assistant-dm-fixture-access",
        refreshToken = "assistant-dm-fixture-refresh",
        selectedServerId = DmServerId,
        selectedServerSlug = "assistant-dm-fixture",
        servers = listOf(StoredAuthServer(id = DmServerId, name = "Raft", slug = "assistant-dm-fixture", role = "owner")),
        allowImplicitServerFallback = false,
    )

/**
 * The DM's messages on the wire (botiverse/slock `GET /channels/:id/messages`): the assistant's
 * morning brief with a reaction and a reply thread (its summary in the response's embedded
 * `threadSummariesByParentMessageId`, which the reply chip reads), the owner's answer, and its reply.
 */
private val AssistantDmMessagesBody =
    """
    {"historyLimited":false,"messages":[
      {"id":"assistant-dm-m1","seq":1,"channelId":"$AssistantDmFixtureChannelId",
       "content":"Morning brief: 3 threads need you. The TestFlight build for 1.16 finished overnight and is waiting for review.",
       "createdAt":"2026-10-30T08:02:00Z","senderId":"$AssistantDmFixtureAgentId","senderName":"Otto","senderType":"agent","messageType":"chat",
       "reactions":[{"emoji":"👍","count":1,"reactorIds":["$DmOwnerId"],"reactorNames":["Artin"]}],
       "threadSummary":{"threadChannelId":"assistant-dm-fixture-thread","replyCount":2,"unreadCount":0},"replyCount":2},
      {"id":"assistant-dm-m2","seq":2,"channelId":"$AssistantDmFixtureChannelId",
       "content":"Thanks. Remind me at 3pm to check the build.",
       "createdAt":"2026-10-30T08:10:00Z","senderId":"$DmOwnerId","senderName":"Artin","senderType":"user","messageType":"chat"},
      {"id":"assistant-dm-m3","seq":3,"channelId":"$AssistantDmFixtureChannelId",
       "content":"Done. I'll ping you here at 15:00.",
       "createdAt":"2026-10-30T08:11:00Z","senderId":"$AssistantDmFixtureAgentId","senderName":"Otto","senderType":"agent","messageType":"chat"}
    ],
    "threadSummariesByParentMessageId":{"assistant-dm-m1":{
      "threadChannelId":"assistant-dm-fixture-thread","parentChannelId":"$AssistantDmFixtureChannelId","parentChannelType":"dm",
      "replyCount":2,"unreadCount":0,"lastReplyAt":"2026-10-30T08:06:00Z",
      "latestReplies":[
        {"messageId":"assistant-dm-r1","seq":1,"preview":"Which threads?","senderId":"$DmOwnerId","senderType":"user","senderName":"Artin","createdAt":"2026-10-30T08:05:00Z"},
        {"messageId":"assistant-dm-r2","seq":2,"preview":"#proj-mobile-release, #design and the Hands triage.","senderId":"$AssistantDmFixtureAgentId","senderType":"agent","senderName":"Otto","createdAt":"2026-10-30T08:06:00Z"}
      ]}}}
    """.trimIndent()

internal object AssistantDmFixtureNetworkApi : SharedThreadNetworkApi {
    /** The page's first window was answered: the capture waits for it. */
    @Volatile
    var latestServed: Boolean = false

    override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?): HomeNetworkResponse =
        if (beforeSeq != null || afterSeq != null) {
            HomeNetworkResponse(code = 200, bodyText = "{\"historyLimited\":false,\"messages\":[]}")
        } else {
            HomeNetworkResponse(code = 200, bodyText = AssistantDmMessagesBody).also { latestServed = true }
        }

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "[]")

    override suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String>,
        randomId: String?,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"results\":[]}")

    override suspend fun attachmentUrl(priority: DataRequestPriority, attachmentId: String, disposition: String?): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse = ok()

    override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse = ok()

    override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse = ok()

    override suspend fun saveMessage(messageId: String): HomeNetworkResponse = ok()

    override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse = ok()

    override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse = ok()

    override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse = ok()

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText = "{\"threadChannelId\":\"assistant-dm-fixture-thread\",\"parentMessageId\":\"$parentMessageId\",\"created\":false}",
        )

    override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse = ok()

    private fun ok(): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
}

private object AssistantDmFixtureTasksApi : RaftTasksApi {
    override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{\"tasks\":[]}")

    override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "{}")
}
