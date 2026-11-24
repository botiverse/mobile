package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.data.ComputerListSummary
import build.raft.app.data.ComputerRuntimeRescanResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeLimitResetTest
import build.raft.app.data.SLACK_BRIDGE_READ_FLOOR_MILLIS
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsComputerRuntimeDetection
import build.raft.app.data.SettingsComputerRuntimeDetectionReason
import build.raft.app.data.SettingsComputerRuntimeDetectionStatus
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.SlackBridgeReadFloor
import build.raft.app.data.toSettingsComputerSnapshot
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for `#wg-mobile-sync:#282` (1.30 web-parity polish bundle):
 * - slock `#16477`: runtimeDetection wire parsing, `POST …/runtimes/rescan` mapping, and `ComputerRuntimeRescanController`
 * - slock `#16501`: `computerRuntimeUsageCheckDue` and `ComputerRuntimeUsageController` auto-check & manual Check again
 * - slock `#16506`: `SlackBridgeReadFloor` and `ChannelSlackBridgeReadController`
 * - slock `#16350`: `RaftLucideIconName.DoorOpen` catalog & vector paths
 * - slock `#16398`: `isFinalFocusedMessageNotFoundError`, `withRouteContext`, and quiet fallback on focused 404
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebParityPolishBundle130Test {
    // --- #16477: Runtime detection & rescan -----------------------------------------------------

    @Test
    fun runtimeDetectionWireFieldsAndRescanEndpointMapCorrectly() = runTest {
        val machinesBody =
            """
            {"machines":[
              {"id":"m-pending","name":"Pending","status":"online","isComputer":true,
               "runtimeDetection":{"status":"pending","unfinishedRuntimes":[],"updatedAtMs":1700000000100}},
              {"id":"m-incomplete","name":"Incomplete","status":"online","isComputer":true,
               "runtimeDetection":{"status":"incomplete","unfinishedRuntimes":["codex","kimi"],"reason":"deadline","updatedAtMs":1700000000200}},
              {"id":"m-complete","name":"Complete","status":"online","isComputer":true,
               "runtimeDetection":{"status":"complete","unfinishedRuntimes":[],"updatedAtMs":1700000000300}},
              {"id":"m-old","name":"Old","status":"online","isComputer":true}
            ]}
            """.trimIndent()
        val rescanResponses =
            ArrayDeque(
                listOf(
                    { HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""") },
                    { HomeNetworkResponse(code = 403, bodyText = """{"error":"forbidden"}""") },
                    { HomeNetworkResponse(code = 409, bodyText = """{"error":"offline"}""") },
                    { HomeNetworkResponse(code = 0, bodyText = null) },
                    { HomeNetworkResponse(code = 500, bodyText = """{"error":"boom"}""") },
                    { throw RuntimeException("socket closed") },
                )
            )
        val api = RescanFakeComputersApi(machinesBody) { rescanResponses.removeFirst().invoke() }
        val client = RaftComputersApiClient(api)
        val serverId = ServerId("server-1")

        val list = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(serverId)).data
        val byId = list.machines.map { it.toSettingsComputerSnapshot() }.associateBy { it.id }

        assertEquals(
            SettingsComputerRuntimeDetection(
                status = SettingsComputerRuntimeDetectionStatus.Pending,
                unfinishedRuntimes = emptyList(),
                reason = null,
                updatedAtMs = 1700000000100L,
            ),
            byId.getValue("m-pending").runtimeDetection,
        )
        assertEquals(
            SettingsComputerRuntimeDetection(
                status = SettingsComputerRuntimeDetectionStatus.Incomplete,
                unfinishedRuntimes = listOf("codex", "kimi"),
                reason = SettingsComputerRuntimeDetectionReason.Deadline,
                updatedAtMs = 1700000000200L,
            ),
            byId.getValue("m-incomplete").runtimeDetection,
        )
        assertEquals(SettingsComputerRuntimeDetectionStatus.Complete, byId.getValue("m-complete").runtimeDetection?.status)
        assertNull(byId.getValue("m-old").runtimeDetection)

        assertEquals(ComputerRuntimeRescanResult.Accepted, client.rescanMachineRuntimes(serverId, "m-incomplete"))
        assertEquals(ComputerRuntimeRescanResult.Forbidden, client.rescanMachineRuntimes(serverId, "m-incomplete"))
        assertEquals(ComputerRuntimeRescanResult.Offline, client.rescanMachineRuntimes(serverId, "m-incomplete"))
        assertEquals(ComputerRuntimeRescanResult.NotSent, client.rescanMachineRuntimes(serverId, "m-incomplete"))
        assertEquals(ComputerRuntimeRescanResult.Failed, client.rescanMachineRuntimes(serverId, "m-incomplete"))
        assertEquals(ComputerRuntimeRescanResult.NotSent, client.rescanMachineRuntimes(serverId, "m-incomplete"))
        assertEquals(6, api.rescanCalls.size)
        assertEquals("server-1:m-incomplete", api.rescanCalls.first())
    }

    @Test
    fun runtimeRescanControllerWaitsForNewAnswerKeyAndTimesOutToNoResult() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val responses =
                ArrayDeque(
                    listOf(
                        ComputerRuntimeRescanResult.Accepted,
                        ComputerRuntimeRescanResult.Accepted,
                        ComputerRuntimeRescanResult.Forbidden,
                        ComputerRuntimeRescanResult.Offline,
                    )
                )
            val controller =
                ComputerRuntimeRescanController(
                    scope = scope,
                    request = { responses.removeFirst() },
                    noResultTimeoutMillis = 10_000L,
                )
            val initial =
                SettingsComputerRuntimeDetection(
                    status = SettingsComputerRuntimeDetectionStatus.Incomplete,
                    unfinishedRuntimes = listOf("codex"),
                    reason = SettingsComputerRuntimeDetectionReason.Deadline,
                    updatedAtMs = 100L,
                )
            val initialKey = computerRuntimeDetectionAnswerKey(initial)
            controller.updateAnswerKey(initialKey)
            assertEquals(ComputerRuntimeRescanState.Idle, controller.state)

            // Press Retry -> enters Running, stays Running on unchanged refetch, clears on newer answerKey.
            controller.rescan()
            runCurrent()
            assertEquals(ComputerRuntimeRescanState.Running, controller.state)
            controller.updateAnswerKey(computerRuntimeDetectionAnswerKey(initial.copy()))
            assertEquals(ComputerRuntimeRescanState.Running, controller.state)
            val newerKey = computerRuntimeDetectionAnswerKey(initial.copy(updatedAtMs = 200L))
            controller.updateAnswerKey(newerKey)
            assertEquals(ComputerRuntimeRescanState.Idle, controller.state)

            // Press Retry -> 200 accepted but no Computer update arrives within 10s -> Failed(NoResult).
            controller.rescan()
            runCurrent()
            assertEquals(ComputerRuntimeRescanState.Running, controller.state)
            advanceTimeBy(9_999L)
            runCurrent()
            assertEquals(ComputerRuntimeRescanState.Running, controller.state)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.NoResult), controller.state)

            // Newer answer key clears prior failure.
            controller.updateAnswerKey(computerRuntimeDetectionAnswerKey(initial.copy(updatedAtMs = 300L)))
            assertEquals(ComputerRuntimeRescanState.Idle, controller.state)

            // Forbidden & Offline map directly.
            controller.rescan()
            runCurrent()
            assertEquals(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.Forbidden), controller.state)

            controller.rescan()
            runCurrent()
            assertEquals(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.Offline), controller.state)
            controller.dispose()
        } finally {
            scope.cancel()
        }
    }

    // --- #16501: Runtime usage Check again / auto-refresh ---------------------------------------

    @Test
    fun runtimeUsageCheckDueRespectsTwoMinuteBoundaryAndUnsupported() {
        val collectedAt = "2026-11-24T10:00:00.000Z"
        // 2026-11-24T10:00:00Z = 1_795_514_400_000 ms
        val collectedMillis = 1_795_514_400_000L
        val freshLoad =
            RuntimeAccountUsageLoad.Loaded(
                RuntimeAccountUsageRead.Available(
                    stale = false,
                    snapshot =
                        RuntimeAccountUsageSnapshot(
                            provider = "claude",
                            collectedAt = collectedAt,
                            accounts = listOf(RuntimeAccountUsageAccount("a1", "Max", null, RuntimeAccountUsageHealth.Ok, emptyList())),
                        ),
                )
            )
        val staleLoad =
            RuntimeAccountUsageLoad.Loaded(
                RuntimeAccountUsageRead.Available(stale = true, snapshot = (freshLoad.read as RuntimeAccountUsageRead.Available).snapshot)
            )

        assertFalse(computerRuntimeUsageCheckDue(freshLoad, collectedMillis + 119_999L))
        assertTrue(computerRuntimeUsageCheckDue(freshLoad, collectedMillis + RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS))
        assertTrue(computerRuntimeUsageCheckDue(staleLoad, collectedMillis + 1_000L))
        assertTrue(computerRuntimeUsageCheckDue(RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing), collectedMillis))
        assertTrue(computerRuntimeUsageCheckDue(RuntimeAccountUsageLoad.Failed("network", retryable = true), collectedMillis))
        assertFalse(computerRuntimeUsageCheckDue(RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Unsupported), collectedMillis))
        assertFalse(computerRuntimeUsageCheckDue(null, collectedMillis))
    }

    @Test
    fun runtimeUsageControllerDoesNotRefreshOnChipStartAutoChecksOnOpenAndHandlesManualCheck() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            // 2026-11-24T10:00:00Z = 1_795_514_400_000 ms; clock starts 3 minutes later -> check due!
            var now = 1_795_514_400_000L + 180_000L
            val refreshCalls = mutableListOf<String>()
            val refreshReplies =
                ArrayDeque(
                    listOf(
                        HomeNetworkResponse(
                            code = 200,
                            bodyText =
                                """{"accepted":true,"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-24T10:03:00.000Z","accounts":[{"accountKey":"a1","health":"ok","windows":[]}]}}""",
                        ),
                        HomeNetworkResponse(code = 200, bodyText = """{"accepted":true,"state":"timeout"}"""),
                        HomeNetworkResponse(
                            code = 200,
                            bodyText =
                                """{"accepted":true,"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-24T10:06:00.000Z","accounts":[{"accountKey":"a1","health":"ok","windows":[]}]}}""",
                        ),
                    )
                )
            val api =
                UsageFakeComputersApi(
                    usageRespond = {
                        HomeNetworkResponse(
                            code = 200,
                            bodyText =
                                """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-24T10:00:00.000Z","accounts":[{"accountKey":"a1","health":"ok","windows":[]}]}}""",
                        )
                    },
                    refreshRespond = { body ->
                        refreshCalls += body
                        refreshReplies.removeFirst()
                    },
                )
            val owner = RuntimeLimitResetTest.OWNER
            val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { owner }, clock = { now })
            val controller =
                ComputerRuntimeUsageController(
                    key = RuntimeAccountUsageKey(owner, "s1", "m1", RuntimeAccountUsageProvider.Codex),
                    store = store,
                    scope = scope,
                    nowMillis = { now },
                )

            // start() reads only, never sends stale_or_missing.
            controller.start()
            advanceUntilIdle()
            assertEquals(emptyList(), refreshCalls)

            // onOpen() auto-checks silently when >= 2min old without flashing answerFlash.
            controller.onOpen()
            advanceUntilIdle()
            assertEquals(listOf("""{"reason":"stale_or_missing"}"""), refreshCalls)
            assertEquals(ComputerRuntimeUsageCheckState.Idle, controller.checkState)
            assertEquals(0, controller.answerFlash)

            // Foreground return while fresh (< 2min after 10:03:00Z) sends no refresh.
            now += 30_000L
            controller.onForegroundReturn()
            advanceUntilIdle()
            assertEquals(1, refreshCalls.size)

            // After 3 minutes, manual Check again hits timeout (gives back cooldown) -> then manual Retry succeeds and flashes!
            now += 150_000L
            controller.refreshNow()
            advanceUntilIdle()
            assertEquals(ComputerRuntimeUsageCheckState.Timeout, controller.checkState)
            assertEquals(0, controller.answerFlash)

            controller.refreshNow()
            advanceUntilIdle()
            assertEquals(ComputerRuntimeUsageCheckState.Idle, controller.checkState)
            assertEquals(1, controller.answerFlash)
            assertEquals(3, refreshCalls.size)
            controller.cancel()
        } finally {
            scope.cancel()
        }
    }

    // --- #16506: Slack channel menu read on open & foreground return ----------------------------

    @Test
    fun slackBridgeReadControllerRespectsFloorDefersAndKeepsReturnFailuresQuiet() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            SlackBridgeReadFloor.resetForTest()
            var now = 1_000_000L
            var foreground = true
            var reads = 0
            val outcomes =
                ArrayDeque(
                    listOf(
                        ChannelSlackBridgeReadOutcome.Failed, // 1: open menu fails -> sets failed = true
                        ChannelSlackBridgeReadOutcome.Success, // 2: retry waits out floor -> clears failed
                        ChannelSlackBridgeReadOutcome.Failed, // 3: foreground return fails -> stays quiet!
                        ChannelSlackBridgeReadOutcome.Success, // 4: deferred open read after floor expires
                    )
                )
            val controller =
                ChannelSlackBridgeReadController(
                    scope = scope,
                    read = {
                        reads += 1
                        SlackBridgeReadFloor.noteRead(now)
                        outcomes.removeFirst()
                    },
                    waitRemainingMillis = { SlackBridgeReadFloor.waitRemainingMillis(now) },
                    isForeground = { foreground },
                )

            // 1. Opening menu reads at once and reports failure.
            controller.onMenuOpenChange(true)
            runCurrent()
            assertEquals(1, reads)
            assertTrue(controller.failed)

            // 2. Retry inside the 1-minute floor waits until floor expires, then succeeds and clears failed.
            controller.retry()
            runCurrent()
            assertTrue(controller.retrying)
            assertEquals(1, reads)
            now += SLACK_BRIDGE_READ_FLOOR_MILLIS
            advanceTimeBy(SLACK_BRIDGE_READ_FLOOR_MILLIS)
            runCurrent()
            assertEquals(2, reads)
            assertFalse(controller.retrying)
            assertFalse(controller.failed)

            // 3. Foreground return after floor expires reads quietly and does NOT set failed on error.
            controller.onMenuOpenChange(false)
            now += SLACK_BRIDGE_READ_FLOOR_MILLIS
            controller.onForegroundReturn()
            runCurrent()
            assertEquals(3, reads)
            assertFalse(controller.failed)

            // 4. Opening menu inside floor defers one read until floor expires while menu stays open.
            controller.onMenuOpenChange(true)
            runCurrent()
            assertEquals(3, reads)
            now += SLACK_BRIDGE_READ_FLOOR_MILLIS
            advanceTimeBy(SLACK_BRIDGE_READ_FLOOR_MILLIS)
            runCurrent()
            assertEquals(4, reads)
            assertFalse(controller.failed)

            // Disabling clears failed state.
            controller.dispose()
            SlackBridgeReadFloor.resetForTest()
        } finally {
            scope.cancel()
        }
    }

    // --- #16350: DoorOpen icon in Lucide catalog ------------------------------------------------

    @Test
    fun doorOpenIconExistsInLucideCatalogWithPaths() {
        assertEquals("door-open", RaftLucideIconName.DoorOpen.wireName)
        val spec = RaftLucideIconPaths.spec(RaftLucideIconName.DoorOpen)
        assertEquals("door-open", spec.name)
        assertTrue(spec.paths.isNotEmpty())
    }

    // --- #16398: Quiet replayed message_not_found -----------------------------------------------

    @Test
    fun focusedMessageNotFoundClassifiesFinal404AndOpensQuietlyWithoutError() = runTest {
        assertTrue(isFinalFocusedMessageNotFoundError(ScreenError(code = "message_not_found", retryable = false)))
        assertTrue(isFinalFocusedMessageNotFoundError(ScreenError(code = "thread_focused_context", serverCode = "message_not_found", retryable = false)))
        assertTrue(isFinalFocusedMessageNotFoundError(ScreenError(code = "conversation_http_404", retryable = false)))
        assertTrue(isFinalFocusedMessageNotFoundError(ScreenError(code = "message_id_invalid", retryable = false)))
        assertFalse(isFinalFocusedMessageNotFoundError(ScreenError(code = "conversation_http_404", serverCode = "history_limited", retryable = false)))
        assertFalse(isFinalFocusedMessageNotFoundError(ScreenError(code = "conversation_http_500", retryable = true)))
        assertFalse(isFinalFocusedMessageNotFoundError(ScreenError(code = "network_unavailable", retryable = true)))

        // withRouteContext drops focus when focusedTargetMissing = true.
        val route = basePayload().copy(focusedMessageId = "deleted-msg", focusedMessageRequestNonce = 7)
        val quiet = basePayload().copy(focusedTargetMissing = true).withRouteContext(route)
        assertNull(quiet.focusedMessageId)
        assertEquals(0, quiet.focusedMessageRequestNonce)
        assertTrue(quiet.focusedTargetMissing)
        assertFalse(quiet.lacksFocusedAnchor())

        // End-to-end SharedThreadDataSource: 404 message_not_found falls back quietly to latest messages with cleared focus.
        val api = FakeThreadApi(bodyText = messagesResponse("latest"))
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "latest message")),
                messageContextResult = DataResult.Error(ScreenError(code = "conversation_http_404", serverCode = "message_not_found", retryable = false)),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )

        val fetched =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.fetchThreadPayload(route)
            ).data
        assertEquals(listOf(MessageId("deleted-msg")), repository.getMessageContextRequests)
        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(listOf("latest message"), fetched.messages.map { it.body })
        assertNull(fetched.focusedMessageId)
        assertEquals(0, fetched.focusedMessageRequestNonce)
        assertTrue(fetched.focusedTargetMissing)
        assertFalse(fetched.lacksFocusedAnchor())
    }

    private class RescanFakeComputersApi(
        private val machinesBody: String,
        private val rescanRespond: () -> HomeNetworkResponse,
    ) : RaftComputersApi {
        val rescanCalls = mutableListOf<String>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = machinesBody)

        override suspend fun rescanMachineRuntimes(serverId: ServerId, machineId: String): HomeNetworkResponse {
            rescanCalls += "${serverId.value}:$machineId"
            return rescanRespond()
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unexpected")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unexpected")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unexpected")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unexpected")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unexpected")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unexpected")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unexpected")
    }

    private class UsageFakeComputersApi(
        private val usageRespond: () -> HomeNetworkResponse,
        private val refreshRespond: (String) -> HomeNetworkResponse,
    ) : RaftComputersApi {
        override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
            usageRespond()

        override suspend fun refreshRuntimeAccountUsage(
            serverId: ServerId,
            machineId: String,
            provider: String,
            body: String,
            priority: DataRequestPriority,
        ): HomeNetworkResponse = refreshRespond(body)

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unexpected")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unexpected")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unexpected")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unexpected")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unexpected")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unexpected")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unexpected")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unexpected")
    }
}
