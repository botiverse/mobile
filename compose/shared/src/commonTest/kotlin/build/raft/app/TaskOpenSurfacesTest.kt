package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksEvent
import build.raft.app.data.SharedTasksOperation
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.ChannelId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where tapping a listed task goes, and the first send of a task without a conversation (slock
 * staging 3f25e17e web `useOpenTask.ts`, `TaskConversationDialog.tsx` "opening does not create",
 * `threadStore.ensureTaskModalThreadChannel`).
 */
class TaskOpenSurfacesTest {
    @Test
    fun eachKindOfListedTaskOpensWhereWebOpensIt() {
        // Task DTOs as the server projects them (taskService.ts: isLegacy, hostMessageId, conversation).
        val legacy = task("""{"messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":false,"isLegacy":true}""")
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val v2 = task("""{"messageId":"t-1","hostMessageId":null,"conversationChannelId":"conv-1","conversationAnchored":true,"isLegacy":false}""")
        val v2Lazy = task("""{"messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}""")
        // (case, task) -> (surface kind or null, page target)
        data class Case(val name: String, val task: TaskSummary, val surface: String?, val target: TaskOpenTarget)
        listOf(
            Case("legacy", legacy, "Legacy", TaskOpenTarget.LegacyPanel),
            Case("pre-v2", preV2, "Pending", TaskOpenTarget.PendingConversation),
            Case("v2", v2, null, TaskOpenTarget.Conversation(ChannelId("conv-1"))),
            Case("v2 without a conversation yet", v2Lazy, "Pending", TaskOpenTarget.PendingConversation),
        ).forEach { case ->
            assertEquals(case.target, case.task.taskOpenTarget(), case.name)
            assertEquals(case.surface, case.task.taskOpenSurface()?.let { it::class.simpleName }, case.name)
        }
    }

    @Test
    fun firstSendCreatesTheConversationThenSendsIntoItAndFailuresKeepTheServersWords() = runTest {
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        // (case, POST answer, send answer) -> (result, calls)
        data class Case(val name: String, val post: HomeNetworkResponse, val send: ThreadApiResult<Unit>, val expected: TaskConversationStart, val calls: List<String>)
        val created = HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}""")
        listOf(
            Case("ok", created, ThreadApiResult.Success(Unit), TaskConversationStart.Started(ChannelId("conv-local")), listOf("post:t-1", "send:conv-local:hello")),
            Case(
                "archived channel",
                HomeNetworkResponse(409, """{"error":"Channel is archived"}"""),
                ThreadApiResult.Success(Unit),
                TaskConversationStart.Failed("Channel is archived"),
                listOf("post:t-1"),
            ),
            Case(
                "not a member",
                HomeNetworkResponse(403, """{"error":"You must join this channel to reply in this task"}"""),
                ThreadApiResult.Success(Unit),
                TaskConversationStart.Failed("You must join this channel to reply in this task"),
                listOf("post:t-1"),
            ),
            Case(
                "send refused",
                created,
                ThreadApiResult.Failure(code = 400, errorBodyText = """{"error":"Message too long"}"""),
                TaskConversationStart.Failed("Message too long"),
                listOf("post:t-1", "send:conv-local:hello"),
            ),
        ).forEach { case ->
            val calls = mutableListOf<String>()
            val api = ConversationApi(case.post, calls)
            val result =
                startTaskConversation(
                    task = preV2,
                    serverId = ServerId("server-1"),
                    body = "  hello ",
                    tasksDataSource = SharedTasksDataSource(api, diagnosticsLogger = {}),
                    randomId = "rid-1",
                    send = { channelId, body, _ ->
                        calls += "send:$channelId:$body"
                        case.send
                    },
                )
            assertEquals(case.expected, result, case.name)
            assertEquals(case.calls, calls, case.name)
        }
    }

    @Test
    fun aRetryAfterALostAnswerReusesTheConversationAndTheRandomIdSoTheServerDedupes() = runTest {
        // Tenny: POST /conversation succeeded, then the send failed — or timed out although the
        // server took it. The retry POSTs again (the server answers the same conversation) and
        // sends with the SAME randomId, so the server dedupes it: one message, not two.
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val calls = mutableListOf<String>()
        val api = ConversationApi(HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}"""), calls)
        val opened = mutableListOf<ChannelId>()
        val sends = mutableListOf<Pair<String, String>>()
        var attempt = 0
        var ids = 0
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-${++ids}" })
        flow.open(TaskOpenSurface.Pending(preV2))
        suspend fun send() =
            flow.send(
                text = "On it",
                serverId = "server-1",
                tasksDataSource = SharedTasksDataSource(api, diagnosticsLogger = {}),
                sendMessage = { channelId, _, randomId ->
                    sends += channelId to randomId
                    attempt += 1
                    // First attempt: the server took it, but the answer was lost (a timeout).
                    if (attempt == 1) ThreadApiResult.Failure(code = 504) else ThreadApiResult.Success(Unit)
                },
                currentServerId = { "server-1" },
                fallbackError = "Couldn't send the message",
            )
        assertEquals(null, send())
        assertEquals("Couldn't send the message", flow.errorText)
        assertEquals("On it", flow.draft("server-1", "t-1"), "the typed text stays after a failure")
        send()?.let { (_, conversation) -> opened += conversation }
        assertEquals(listOf("post:t-1", "post:t-1"), calls.filter { it.startsWith("post:") })
        assertEquals(listOf("conv-local" to "rid-1", "conv-local" to "rid-1"), sends, "same conversation, same randomId")
        assertEquals(listOf(ChannelId("conv-local")), opened)
        // The pending page stays (composer off) until the conversation page covers it.
        assertTrue(flow.handingOff)
        assertEquals(preV2, flow.surface?.task)
        // The conversation's own `task:updated` does not open it a second time.
        val anchored = preV2.copy(conversationChannelId = ChannelId("conv-local"), conversationAnchored = true)
        assertEquals(null, flow.onTaskChanged(anchored), "the page it opens is already on its way")
        flow.finishHandOff()
        assertEquals(null, flow.surface, "it gives way to the conversation page")
        assertFalse(flow.handingOff)
        assertEquals("", flow.draft("server-1", "t-1"))

        // The next draft gets a new id.
        flow.open(TaskOpenSurface.Pending(preV2))
        send()
        assertEquals("rid-2", sends.last().second)
    }

    @Test
    fun theOpenPageFollowsItsTaskAndGivesWayWhenItsConversationAppears() {
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid" })
        flow.open(TaskOpenSurface.Pending(preV2))
        flow.onDraftChange("server-1", "t-1", "half a thought")
        // Another task's update, and the same copy: nothing changes.
        assertEquals(null, flow.onTaskChanged(preV2.copy(id = TaskId("t-2"))))
        assertEquals(null, flow.onTaskChanged(preV2))
        // `task:updated` with a new status, still no conversation: the page shows the new copy.
        val reviewed = preV2.copy(status = TaskStatus.InReview, revision = 2L)
        assertEquals(null, flow.onTaskChanged(reviewed))
        assertEquals(reviewed, flow.surface?.task)
        // A store still holding the older revision does not put the old status back.
        assertEquals(null, flow.onTaskChanged(preV2))
        assertEquals(reviewed, flow.surface?.task)
        // Someone else wrote first: the conversation exists; the caller opens it.
        val anchored = reviewed.copy(conversationChannelId = ChannelId("conv-1"), conversationAnchored = true)
        assertEquals(anchored, flow.onTaskChanged(anchored))
        assertEquals(null, flow.surface)
        assertEquals("half a thought", flow.draft("server-1", "t-1"), "the draft is kept while the page lives")
    }

    @Test
    fun aSendThatFinishesOnAnotherServerOpensNothingAndClosingKeepsTheDraft() = runTest {
        val preV2 = task("""{"messageId":"msg-host","hostMessageId":"msg-host","conversationChannelId":null,"conversationAnchored":false,"isLegacy":false}""")
        val api = ConversationApi(HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}"""), mutableListOf())
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid" })
        flow.open(TaskOpenSurface.Pending(preV2))
        flow.onDraftChange("server-1", "t-1", "draft")
        assertTrue(flow.dismiss())
        assertEquals("draft", flow.draft("server-1", "t-1"))
        flow.open(TaskOpenSurface.Pending(preV2))
        var server = "server-1"
        val opened =
            flow.send(
                text = "On it",
                serverId = "server-1",
                tasksDataSource = SharedTasksDataSource(api, diagnosticsLogger = {}),
                sendMessage = { _, _, _ ->
                    server = "server-2"
                    ThreadApiResult.Success(Unit)
                },
                currentServerId = { server },
                fallbackError = "x",
            )
        assertEquals(null, opened, "web stops when the context changed meanwhile")
    }

    @Test
    fun aDoubleTapOpensOnce() {
        var now = 0L
        val gate = TaskOpenGate(repeatWindowMs = 700L, nowMs = { now })
        assertTrue(gate.tryBegin("t-1"))
        assertFalse(gate.tryBegin("t-1"), "second tap while the first resolves")
        assertFalse(gate.tryBegin("t-2"), "another task while one opens")
        gate.finish("t-1")
        now = 200L
        assertFalse(gate.tryBegin("t-1"), "repeat tap right after the page opened")
        assertTrue(gate.tryBegin("t-2"))
        gate.finish("t-2")
        now = 2_000L
        assertTrue(gate.tryBegin("t-1"), "a later open goes through")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun tasksTabOpenHydratesSummaryRowAndAlwaysReleasesGateOnMismatchOr404() = runTest {
        val summaryOnly =
            task("""{"messageId":"t-1","hostMessageId":null,"conversationAnchored":false,"isLegacy":false,"hasDescription":true,"sourceSenderType":"user","sourceSenderId":"user-1"}""")
        assertFalse(summaryOnly.conversationDetailsLoaded)

        // 1. Hydrates a summary-only row -> opens its Conversation page.
        var nextTaskByNumberResponse =
            HomeNetworkResponse(
                200,
                """{"task":{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,"title":"Ship it","status":"todo","sourceMessageId":"msg-host","conversationReplyCount":2,"createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,"conversationChannelId":"conv-7","conversationAnchored":true,"isLegacy":false}}""",
            )
        val api =
            object : RaftTasksApi {
                override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
                    HomeNetworkResponse(code = 501, bodyText = null)

                override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
                    HomeNetworkResponse(code = 501, bodyText = null)

                override suspend fun taskByNumber(
                    serverId: ServerId,
                    channelId: ChannelId,
                    taskNumber: Int,
                    priority: DataRequestPriority,
                ) = nextTaskByNumberResponse
            }
        val store = SharedTasksDataStore(currentServerIdProvider = { "server-1" })
        val dataSource = SharedTasksDataSource(api, diagnosticsLogger = {})
        var clockMs = 0L
        val gate = TaskOpenGate(repeatWindowMs = 700L, nowMs = { clockMs })
        val openedPages = mutableListOf<Pair<TaskSummary, ChannelId?>>()
        val openedSurfaces = mutableListOf<TaskOpenSurface>()
        var loadErrors = 0
        var notFounds = 0

        launchTasksTabTaskOpen(
            task = summaryOnly,
            serverId = "server-1",
            tasksDataStore = store,
            tasksDataSource = dataSource,
            gate = gate,
            scope = this,
            isStillActive = { true },
            openSurface = { openedSurfaces += it },
            openPage = { opened, convId -> openedPages += opened to convId },
            onLoadError = { loadErrors += 1 },
            onNoPermission = {},
            onNotFound = { notFounds += 1 },
            log = {},
        )
        advanceUntilIdle()
        assertEquals(1, openedPages.size)
        assertEquals(ChannelId("conv-7"), openedPages.single().first.conversationChannelId)
        assertTrue(openedSurfaces.isEmpty())

        // 2. ID mismatch releases gate and calls onNotFound instead of wedging TaskOpenGate.
        clockMs = 2_000L
        nextTaskByNumberResponse =
            HomeNetworkResponse(
                200,
                """{"task":{"id":"t-other","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,"title":"Wrong","status":"todo","revision":1,"conversationChannelId":"conv-other","conversationAnchored":true,"isLegacy":false}}""",
            )
        val freshStore = SharedTasksDataStore(currentServerIdProvider = { "server-1" })
        launchTasksTabTaskOpen(
            task = summaryOnly,
            serverId = "server-1",
            tasksDataStore = freshStore,
            tasksDataSource = dataSource,
            gate = gate,
            scope = this,
            isStillActive = { true },
            openSurface = { openedSurfaces += it },
            openPage = { opened, convId -> openedPages += opened to convId },
            onLoadError = { loadErrors += 1 },
            onNoPermission = {},
            onNotFound = { notFounds += 1 },
            log = {},
        )
        advanceUntilIdle()
        assertEquals(1, notFounds)
        clockMs = 4_000L
        assertTrue(gate.tryBegin("t-next"), "gate must be released after id mismatch")
        gate.finish("t-next")

        // 3. 404 reports load error rather than falsely opening Pending even when replyCount == 0.
        clockMs = 6_000L
        nextTaskByNumberResponse = HomeNetworkResponse(404, """{"error":"Task not found"}""")
        launchTasksTabTaskOpen(
            task = summaryOnly,
            serverId = "server-1",
            tasksDataStore = freshStore,
            tasksDataSource = dataSource,
            gate = gate,
            scope = this,
            isStillActive = { true },
            openSurface = { openedSurfaces += it },
            openPage = { opened, convId -> openedPages += opened to convId },
            onLoadError = { loadErrors += 1 },
            onNoPermission = {},
            onNotFound = { notFounds += 1 },
            log = {},
        )
        advanceUntilIdle()
        assertEquals(1, loadErrors)
        assertTrue(openedSurfaces.isEmpty())

        // 4. 403 forwards the full PermissionBlocker (serverMessage, serverCode, traceId) rather than only traceId.
        clockMs = 8_000L
        val traceId = "62ac7fa79a3785a6bee414a074f82634"
        val blockers = mutableListOf<PermissionBlocker>()
        nextTaskByNumberResponse =
            HomeNetworkResponse(
                code = 403,
                bodyText = null,
                errorBodyText = """{"error":"Private channel tasks require membership.","code":"channel_access_denied"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
            )
        launchTasksTabTaskOpen(
            task = summaryOnly,
            serverId = "server-1",
            tasksDataStore = freshStore,
            tasksDataSource = dataSource,
            gate = gate,
            scope = this,
            isStillActive = { true },
            openSurface = { openedSurfaces += it },
            openPage = { opened, convId -> openedPages += opened to convId },
            onLoadError = { loadErrors += 1 },
            onNoPermission = { blockers += it },
            onNotFound = { notFounds += 1 },
            log = {},
        )
        advanceUntilIdle()
        val blocker = blockers.single()
        assertEquals("tasks_403", blocker.code)
        assertEquals("Private channel tasks require membership.", blocker.serverMessage)
        assertEquals("channel_access_denied", blocker.serverCode)
        assertEquals(traceId, blocker.traceId)
    }

    @Test
    fun showTaskOperationFailedToastRoutesStatusAndAssignHttpRefusalsAndRespectsAuthGate() {
        val transport = HostBridgeTransport { _, _, _ -> }
        val capabilities = BridgeWireHostCapabilities(transport)
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit
                override fun closePage() = Unit
            }
        fun newBridge(): BridgeModule {
            val pager =
                object : Pager() {
                    override fun createExternalModules(): Map<String, Module> = mapOf(BridgeModule.MODULE_NAME to BridgeModule())
                }
            pager.pagerId = "task-open-surfaces-toast-test"
            pager.pageData = PageData(JSONObject())
            pager.host = RaftCmpHost(capabilities, navigator, bridgeTransport = transport)
            return pager.acquireModule(BridgeModule.MODULE_NAME)
        }
        fun withWindow(block: (RaftToastHostState, BridgeModule) -> Unit) {
            val hostState = RaftToastHostState()
            val window =
                RaftCmpToasts.attach(hostState).also {
                    it.setLifecycle(visible = true, resumed = true)
                    it.addOrigin(capabilities)
                }
            try {
                block(hostState, newBridge())
            } finally {
                window.detach()
            }
        }
        val traceId = "62ac7fa79a3785a6bee414a074f82634"
        val recorded = mutableListOf<Pair<String, ScreenError>>()
        val bridge = newBridge()

        // HTTP 403 status refusal has code "tasks_403" (not "tasks_status_*") and must still toast with full ScreenError.
        bridge.showTaskOperationFailedToast(
            event =
                SharedTasksEvent.OperationFailed(
                    code = "tasks_403",
                    traceId = traceId,
                    serverMessage = "Channel is archived.",
                    serverCode = "channel_archived",
                    operation = SharedTasksOperation.UpdateStatus,
                ),
            updateFailedLabel = "Unable to update task",
            assignFailedLabel = "Unable to update assignee",
            onToast = { title, error -> recorded += title to error },
        )
        assertEquals(
            "Unable to update task" to
                ScreenError(
                    code = "tasks_403",
                    retryable = false,
                    serverMessage = "Channel is archived.",
                    serverCode = "channel_archived",
                    traceId = traceId,
                ),
            recorded.removeAt(0),
        )

        // HTTP 400 assign refusal has code "tasks_400" (not "tasks_assign_*") and must still toast.
        bridge.showTaskOperationFailedToast(
            event =
                SharedTasksEvent.OperationFailed(
                    code = "tasks_400",
                    traceId = traceId,
                    serverMessage = "Assignee is not in channel.",
                    serverCode = "assignee_not_in_channel",
                    operation = SharedTasksOperation.AssignTask,
                ),
            updateFailedLabel = "Unable to update task",
            assignFailedLabel = "Unable to update assignee",
            onToast = { title, error -> recorded += title to error },
        )
        assertEquals(
            "Unable to update assignee" to
                ScreenError(
                    code = "tasks_400",
                    retryable = false,
                    serverMessage = "Assignee is not in channel.",
                    serverCode = "assignee_not_in_channel",
                    traceId = traceId,
                ),
            recorded.removeAt(0),
        )

        // Plan/quota serverCode (e.g. "channel_read_only") on status mutation also toasts cleanly.
        bridge.showTaskOperationFailedToast(
            event =
                SharedTasksEvent.OperationFailed(
                    code = "channel_read_only",
                    traceId = traceId,
                    serverMessage = "Channel is read-only.",
                    serverCode = "channel_read_only",
                    operation = SharedTasksOperation.UpdateStatus,
                ),
            updateFailedLabel = "Unable to update task",
            assignFailedLabel = "Unable to update assignee",
            onToast = { title, error -> recorded += title to error },
        )
        assertEquals("Unable to update task", recorded.removeAt(0).first)

        // Load and CreateTasks failures neither toast nor invoke routeAuthRequired.
        var authChecks = 0
        bridge.showTaskOperationFailedToast(
            event = SharedTasksEvent.OperationFailed(code = "auth_required", operation = SharedTasksOperation.Load),
            updateFailedLabel = "Unable to update task",
            assignFailedLabel = "Unable to update assignee",
            onToast = { title, error -> recorded += title to error },
        ) {
            authChecks += 1
            true
        }
        bridge.showTaskOperationFailedToast(
            event = SharedTasksEvent.OperationFailed(code = "tasks_403", operation = SharedTasksOperation.CreateTasks),
            updateFailedLabel = "Unable to update task",
            assignFailedLabel = "Unable to update assignee",
            onToast = { title, error -> recorded += title to error },
        ) {
            authChecks += 1
            false
        }
        assertEquals(0, authChecks)
        assertTrue(recorded.isEmpty())

        // Auth-routed mutation failures suppress the error toast.
        bridge.showTaskOperationFailedToast(
            event =
                SharedTasksEvent.OperationFailed(
                    code = "auth_required",
                    operation = SharedTasksOperation.UpdateStatus,
                ),
            updateFailedLabel = "Unable to update task",
            assignFailedLabel = "Unable to update assignee",
            onToast = { title, error -> recorded += title to error },
        ) { it == "auth_required" }
        assertTrue(recorded.isEmpty())

        // Default BridgeModule path posts an Error toast without resolving Compose string resources when traceId is null.
        withWindow { state, liveBridge ->
            liveBridge.showTaskOperationFailedToast(
                event =
                    SharedTasksEvent.OperationFailed(
                        code = "tasks_local_projection_missing",
                        traceId = null,
                        operation = SharedTasksOperation.UpdateStatus,
                    ),
                updateFailedLabel = "Unable to update task",
                assignFailedLabel = "Unable to update assignee",
            )
            val toast = state.visible.single()
            assertEquals("Unable to update task", toast.title)
            assertEquals(RaftToastIntent.Error, toast.intent)
        }
    }

    @Test
    fun timeContextProviderDateContextIgnoresMinuteTicksAndUpdatesOnMidnightOrOffsetShift() {
        val day1 = raftEpochDayForDate(2026, 11, 21)
        val day2 = raftEpochDayForDate(2026, 11, 22)
        val initial = RaftTimeContext(localOffsetMinutes = -420, todayEpochDay = day1, nowEpochMillis = 1_000L)
        val provider = RaftTimeContextProvider(initial)
        val firstDateContext = provider.dateContext
        assertEquals(RaftTimeContext(localOffsetMinutes = -420, todayEpochDay = day1, nowEpochMillis = null), firstDateContext)

        // 1-minute tick leaves dateContext equal (same instance, no recomposition invalidation).
        provider.update(initial.copy(nowEpochMillis = 61_000L))
        assertTrue(provider.dateContext === firstDateContext)

        // DST / offset change updates dateContext with paired offset + day.
        provider.update(initial.copy(localOffsetMinutes = -480, nowEpochMillis = 121_000L))
        assertEquals(RaftTimeContext(localOffsetMinutes = -480, todayEpochDay = day1, nowEpochMillis = null), provider.dateContext)

        // Midnight updates dateContext.
        provider.update(initial.copy(localOffsetMinutes = -480, todayEpochDay = day2, nowEpochMillis = 86_400_000L))
        assertEquals(RaftTimeContext(localOffsetMinutes = -480, todayEpochDay = day2, nowEpochMillis = null), provider.dateContext)
    }

    private fun task(fields: String): TaskSummary {
        val base =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"msg-host","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,"""
        val json = base + fields.trim().removePrefix("{")
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }

    private class ConversationApi(private val post: HomeNetworkResponse, private val calls: MutableList<String>) : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: build.raft.app.data.DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse {
            calls += "post:${taskId.value}"
            return post
        }
    }
}
