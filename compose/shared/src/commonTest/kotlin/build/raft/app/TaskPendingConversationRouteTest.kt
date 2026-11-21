package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE
import build.raft.app.nav.RaftMobileStackMotion
import build.raft.app.nav.RaftPageKey
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.raftPageRouteIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Tests for the pushed pending task route ([TASK_PENDING_CONVERSATION_PAGE], #proj-mobile:#161):
 * identity-only pageData and route deduplication, seed store session lifecycle, live task candidate
 * resolution, channel membership / write checks, first-send + `task:updated` replace-in-place
 * transitions, and zero-motion stack replacement.
 */
class TaskPendingConversationRouteTest {
    private val scope = RaftShellStoreScope.create(shellInstanceId = "shell-1", initialServerId = "server-1")

    @Test
    fun pageDataIsIdentityOnlyRoundTripsAndDedupesAcrossVolatileClockFields() {
        val pending = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val first =
            taskPendingPageData(
                task = pending,
                storeScope = scope,
                timeContext = RaftTimeContext(localOffsetMinutes = 480, todayEpochDay = 20_000L, nowEpochMillis = 1_000L),
                alignOwnMessagesRight = true,
                compactConsecutiveMessages = false,
            )
        val second =
            taskPendingPageData(
                task = pending,
                storeScope = scope,
                timeContext = RaftTimeContext(localOffsetMinutes = 480, todayEpochDay = 20_000L, nowEpochMillis = 9_999L),
                alignOwnMessagesRight = true,
                compactConsecutiveMessages = false,
            )

        val parsed = parseTaskPendingRouteParams(first, scope)
        assertEquals("server-1", parsed.serverId)
        assertEquals("t-1", parsed.taskId)
        assertEquals(ChannelId("channel-1"), parsed.channelId)
        assertTrue(parsed.alignOwnMessagesRight)
        assertFalse(parsed.compactConsecutiveMessages)

        val firstJson = Json.parseToJsonElement(first.toString()).jsonObject
        val secondJson = Json.parseToJsonElement(second.toString()).jsonObject
        val dropped = mutableListOf<String>()
        RaftPageKey.create(TASK_PENDING_CONVERSATION_PAGE, firstJson, id = 1L) { dropped += it }
        assertTrue(dropped.isEmpty(), "identity-only pageData must not contain blocked keys: $dropped")
        assertEquals(
            raftPageRouteIdentity(TASK_PENDING_CONVERSATION_PAGE, firstJson),
            raftPageRouteIdentity(TASK_PENDING_CONVERSATION_PAGE, secondJson),
            "double-tap with a newer clock must resolve to the same route identity",
        )
    }

    @Test
    fun seedStoreFollowsSessionAndClearsOnConversationCreation() {
        val pendingA = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val pendingB = pendingA.copy(id = TaskId("t-2"))
        val store = TaskPendingDraftStore()

        store.seed("server-1", pendingA, channelJoined = false, canWriteChannel = false)
        store.seed("server-2", pendingB, channelJoined = true, canWriteChannel = true)
        assertEquals(pendingA, store.seededTask("server-1", "t-1"))
        assertEquals(false, store.seeded("server-1", "t-1")?.channelJoined)
        assertEquals(false, store.seeded("server-1", "t-1")?.canWriteChannel)

        // Clearing after first send drops draft, randomId, and seed for that task.
        store.update("server-1", "t-1", "draft")
        store.clear("server-1", "t-1")
        assertNull(store.seededTask("server-1", "t-1"))
        assertEquals("", store.draft("server-1", "t-1"))
        assertEquals(pendingB, store.seededTask("server-2", "t-2"))

        // Leaving a server drops its seeds; signing out drops all seeds.
        store.seed("server-1", pendingA)
        store.followSession(signedOut = false, accountId = "user-1", serverIds = listOf("server-1"))
        assertEquals(pendingA, store.seededTask("server-1", "t-1"))
        assertNull(store.seededTask("server-2", "t-2"))

        store.followSession(signedOut = true, accountId = null, serverIds = null)
        assertNull(store.seededTask("server-1", "t-1"))
    }

    @Test
    fun resolvePendingTaskSummaryPrefersConversationThenHighestRevisionThenCallerOrder() {
        val seed = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val optimisticSameRevision = seed.copy(status = TaskStatus.InProgress, revision = 1L)
        val higherRevision = seed.copy(status = TaskStatus.InReview, revision = 2L)
        val conversationArrived = seed.copy(conversationChannelId = ChannelId("conv-1"), conversationAnchored = true, revision = 1L)
        val otherTask = seed.copy(id = TaskId("t-other"), revision = 99L)

        // Optimistic same-revision edit from channelTasks beats the opener seed.
        assertEquals(
            optimisticSameRevision,
            resolvePendingTaskSummary("t-1", optimisticSameRevision, seed, otherTask),
        )
        // Higher revision from a store beats a stale copy.
        assertEquals(
            higherRevision,
            resolvePendingTaskSummary("t-1", seed, higherRevision),
        )
        // A copy with a conversation always wins so the page hands off.
        assertEquals(
            conversationArrived,
            resolvePendingTaskSummary("t-1", higherRevision, conversationArrived),
        )
        // At equal revision with no conversation yet, a hydrated row beats a summary-only row.
        val summaryOnly = seed.copy(conversationDetailsLoaded = false, revision = 2L)
        val hydrated = seed.copy(conversationDetailsLoaded = true, revision = 2L)
        assertEquals(
            hydrated,
            resolvePendingTaskSummary("t-1", summaryOnly, hydrated),
        )
        // A revisioned summary-only row still beats a revisionless hydrated copy.
        val revisionlessHydrated = seed.copy(conversationDetailsLoaded = true, revision = null)
        assertEquals(
            summaryOnly,
            resolvePendingTaskSummary("t-1", summaryOnly, revisionlessHydrated),
        )
        assertNull(resolvePendingTaskSummary("missing", seed, higherRevision))
    }

    @Test
    fun channelAccessUsesDirectoryProjectionLocalJoinAndSeedFallback() {
        val pending = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val emptyDirectory = emptyList<SharedConversationDirectoryProjection>().toSharedConversationDirectoryState()

        // Without directory row, opener seed hints govern until locally joined.
        val unjoinedFromSeed =
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = emptyDirectory,
                joinedLocally = false,
                seed = TaskPendingSeed(pending, channelJoined = false, canWriteChannel = false),
            )
        assertEquals(TaskPendingChannelAccess(joined = false, canWriteChannel = false), unjoinedFromSeed)

        val joinedAfterTap =
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = emptyDirectory,
                joinedLocally = true,
                seed = TaskPendingSeed(pending, channelJoined = false, canWriteChannel = false),
            )
        assertEquals(TaskPendingChannelAccess(joined = true, canWriteChannel = true), joinedAfterTap)

        // Live directory projection: unjoined public channel -> joined=false, canWriteChannel=false; archived -> canWriteChannel=false.
        val unjoinedProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "general",
                joined = false,
                archived = false,
            )
        assertEquals(
            TaskPendingChannelAccess(joined = false, canWriteChannel = false),
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = listOf(unjoinedProjection).toSharedConversationDirectoryState(),
                joinedLocally = false,
            ),
        )
        // System #all channel is always postable even if joined=false.
        val allProjection = unjoinedProjection.copy(title = "all")
        assertEquals(
            TaskPendingChannelAccess(joined = true, canWriteChannel = true),
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = listOf(allProjection).toSharedConversationDirectoryState(),
                joinedLocally = false,
            ),
        )
        // Archived joined channel stays non-writable even if joinedLocally=true.
        val archivedProjection = unjoinedProjection.copy(joined = true, archived = true)
        assertEquals(
            TaskPendingChannelAccess(joined = true, canWriteChannel = false),
            resolveTaskPendingChannelAccess(
                task = pending,
                serverId = "server-1",
                directory = listOf(archivedProjection).toSharedConversationDirectoryState(),
                joinedLocally = true,
            ),
        )
    }

    @Test
    fun controllerReusesRandomIdAcrossRetryClearsDraftAndClaimsArrivalOnce() = runTest {
        val pending = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val drafts = TaskPendingDraftStore()
        drafts.seed("server-1", pending)
        var ids = 0
        val controller = TaskPendingPageController(drafts, newRandomId = { "rid-${++ids}" })
        val api = StubTasksApi(HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}"""))
        val dataSource = SharedTasksDataSource(api, diagnosticsLogger = {})
        val sentIds = mutableListOf<String>()
        var attempts = 0

        val firstAttempt =
            controller.send(
                task = pending,
                text = "First note",
                serverId = "server-1",
                tasksDataSource = dataSource,
                sendMessage = { _, _, randomId ->
                    sentIds += randomId
                    attempts += 1
                    if (attempts == 1) ThreadApiResult.Failure(code = 504) else ThreadApiResult.Success(Unit)
                },
                currentServerId = { "server-1" },
                fallbackError = "Send failed",
            )
        assertNull(firstAttempt)
        assertEquals("Send failed", controller.errorText)
        assertEquals("First note", controller.draft("server-1", "t-1"))
        assertFalse(controller.handingOff)

        val secondAttempt =
            controller.send(
                task = pending,
                text = "First note",
                serverId = "server-1",
                tasksDataSource = dataSource,
                sendMessage = { _, _, randomId ->
                    sentIds += randomId
                    attempts += 1
                    ThreadApiResult.Success(Unit)
                },
                currentServerId = { "server-1" },
                fallbackError = "Send failed",
            )
        assertEquals(ChannelId("conv-local"), secondAttempt)
        assertEquals(listOf("rid-1", "rid-1"), sentIds)
        assertTrue(controller.handingOff)
        assertEquals("", controller.draft("server-1", "t-1"))
        assertNull(drafts.seededTask("server-1", "t-1"))

        // Once handing off, a concurrent task:updated arrival does not trigger a second replace.
        val arrived = pending.copy(conversationChannelId = ChannelId("conv-local"), conversationAnchored = true)
        assertFalse(controller.claimConversationArrived(arrived, taskV2Enabled = true))

        // On a fresh controller, task:updated claims arrival once and refuses duplicates; resetHandOff recovers.
        val arrivalController = TaskPendingPageController(TaskPendingDraftStore())
        assertFalse(arrivalController.claimConversationArrived(pending, taskV2Enabled = true))
        assertTrue(arrivalController.claimConversationArrived(arrived, taskV2Enabled = true))
        assertFalse(arrivalController.claimConversationArrived(arrived, taskV2Enabled = true))
        arrivalController.resetHandOff()
        assertFalse(arrivalController.handingOff)
        assertTrue(arrivalController.claimConversationArrived(arrived, taskV2Enabled = true))

        // Pre-v2 task (conversationAnchored = false) receiving conversationChannelId resolves to Conversation,
        // never falling back to HostThread when resolveTaskOpenConversationChannelId is called unconditionally.
        val preV2Arrived = pending.copy(conversationChannelId = ChannelId("conv-pre-v2"), conversationAnchored = false)
        assertEquals(TaskOpenTarget.Conversation(ChannelId("conv-pre-v2")), preV2Arrived.taskOpenTarget(taskV2Enabled = true))
        val resolvedPreV2Id =
            resolveTaskOpenConversationChannelId(
                task = preV2Arrived,
                serverId = "server-1",
                tasksDataSource = dataSource,
                taskV2Enabled = true,
                log = {},
            )
        assertEquals(ChannelId("conv-pre-v2"), resolvedPreV2Id)
        val preV2Payload =
            buildStandaloneTaskThreadLaunchPayload(
                task = preV2Arrived,
                serverId = "server-1",
                conversationDirectory = emptyList<SharedConversationDirectoryProjection>().toSharedConversationDirectoryState(),
                timeContext = null,
                resolvedConversationChannelId = resolvedPreV2Id,
            )!!
        assertEquals("conv-pre-v2", preV2Payload.sourceThreadId)
    }

    @Test
    fun replaceConversationPageDataReplacesPendingPageInPlaceWithoutMotion() {
        val pending = task("""{"messageId":"msg-1","hostMessageId":"msg-1","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val launchPayload =
            buildStandaloneTaskThreadLaunchPayload(
                task = pending,
                serverId = "server-1",
                conversationDirectory = emptyList<SharedConversationDirectoryProjection>().toSharedConversationDirectoryState(),
                timeContext = null,
                resolvedConversationChannelId = ChannelId("conv-local"),
            )!!
        val replaceData = taskPendingReplaceConversationPageData(launchPayload)
        assertTrue(replaceData.optBoolean(RAFT_REPLACE_CURRENT_PAGE, false))
        assertTrue(replaceData.optBoolean(RAFT_OPEN_WITHOUT_ANIMATION, false))

        // Verify RaftPageStack replaces the pending page in place (Back returns straight to the shell)
        // and RaftMobileStackMotion suppresses the replaced page's pop-exit animation.
        val stack = RaftPageStack()
        val shell = stack.pages.single()
        val pendingJson = Json.parseToJsonElement(taskPendingPageData(pending, scope).toString()).jsonObject
        stack.navigatorFor(shell).openPage(TASK_PENDING_CONVERSATION_PAGE, pendingJson)
        val pendingPage = stack.pages.last()
        val motion = RaftMobileStackMotion()
        motion.update(stack.pages.map { it.contentKey }, topOpensWithoutMotion = false)
        assertFalse(motion.exitsWithoutMotion(pendingPage.contentKey))

        val replaceJson = Json.parseToJsonElement(replaceData.toString()).jsonObject
        stack.navigatorFor(pendingPage).openPage(THREAD_PAGE, replaceJson)
        assertEquals(listOf(RAFT_APP_SHELL_PAGE, THREAD_PAGE), stack.pages.map { it.args.pageName })
        motion.update(stack.pages.map { it.contentKey }, topOpensWithoutMotion = true)
        assertTrue(motion.exitsWithoutMotion(pendingPage.contentKey))

        // A normal pop of THREAD_PAGE back to the shell keeps its pop-exit animation.
        val threadPage = stack.pages.last()
        assertTrue(stack.back())
        assertEquals(listOf(RAFT_APP_SHELL_PAGE), stack.pages.map { it.args.pageName })
        motion.update(stack.pages.map { it.contentKey }, topOpensWithoutMotion = false)
        assertFalse(motion.exitsWithoutMotion(threadPage.contentKey))
    }

    private fun task(fields: String): TaskSummary {
        val base =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"msg-host","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,"""
        val json = base + fields.trim().removePrefix("{")
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }

    private class StubTasksApi(private val postResponse: HomeNetworkResponse) : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse = postResponse
    }
}
