package build.raft.app

import build.raft.app.data.ComputerProcJobSnapshot
import build.raft.app.data.ComputerProcJobState
import build.raft.app.data.ComputerProcJobStopOutcome
import build.raft.app.data.ComputerProcJobStopResult
import build.raft.app.data.ComputerProcJobsRead
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.settingsComputerCanControl
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract, formatting, and state-controller tests for `raft proc` jobs on the Computer page
 * (`botiverse/slock #15595`, `docs/contracts/proc-jobs.md`).
 */
class RaftComputerProcJobsTest {
    @Test
    fun wireReadParsesKnownUnknownAndErrors() = runPortableCoroutineTest {
        val api = FakeProcJobsComputersApi()
        val client = RaftComputersApiClient(api)

        api.nextProcJobsResponse =
            HomeNetworkResponse(
                200,
                """
                {
                  "status": "known",
                  "reportedAt": "2026-11-21T01:00:00Z",
                  "jobs": [
                    {
                      "agentId": "agent-1",
                      "name": "dev-server",
                      "state": "running",
                      "startedAt": "2026-11-21T00:00:00Z",
                      "memoryMaxBytes": 4294967296,
                      "rssBytes": 314572800
                    },
                    {
                      "agentId": "agent-1",
                      "name": "watcher",
                      "state": "exited",
                      "startedAt": "2026-11-20T22:00:00Z",
                      "exitedAt": "2026-11-21T00:55:00Z",
                      "exitReason": "owner_stop",
                      "stoppedBy": "tygg",
                      "memoryMaxBytes": 0,
                      "rssBytes": -1
                    },
                    {
                      "agentId": "agent-1",
                      "name": "bad-state",
                      "state": "paused",
                      "startedAt": "2026-11-20T22:00:00Z"
                    }
                  ]
                }
                """.trimIndent()
            )
        val known = assertIs<DataResult.Content<ComputerProcJobsRead>>(client.machineProcJobs(ServerId("s-1"), "m-1")).data
        val parsed = assertIs<ComputerProcJobsRead.Known>(known)
        assertEquals("2026-11-21T01:00:00Z", parsed.reportedAt)
        assertEquals(2, parsed.jobs.size)
        assertEquals(ComputerProcJobState.Running, parsed.jobs[0].state)
        assertEquals(4294967296L, parsed.jobs[0].memoryMaxBytes)
        assertEquals(314572800L, parsed.jobs[0].rssBytes)
        assertEquals(ComputerProcJobState.Exited, parsed.jobs[1].state)
        assertEquals("owner_stop", parsed.jobs[1].exitReason)
        assertEquals("tygg", parsed.jobs[1].stoppedBy)
        assertNull(parsed.jobs[1].memoryMaxBytes)
        assertNull(parsed.jobs[1].rssBytes)

        api.nextProcJobsResponse = HomeNetworkResponse(200, """{"status":"unknown"}""")
        val unknown =
            assertIs<DataResult.Content<ComputerProcJobsRead>>(
                client.machineProcJobs(ServerId("s-1"), "m-1", DataRequestPriority.Background)
            ).data
        assertIs<ComputerProcJobsRead.Unknown>(unknown)
        assertEquals(DataRequestPriority.Background, api.lastProcJobsPriority)

        api.nextProcJobsResponse =
            HomeNetworkResponse(
                200,
                """{"status":"known","reportedAt":"2026-11-21T01:00:00Z","jobs":[{"agentId":"a","name":"n","state":"paused","startedAt":"2026-11-21T00:00:00Z"}]}"""
            )
        val allDropped = assertIs<DataResult.Error>(client.machineProcJobs(ServerId("s-1"), "m-1"))
        assertEquals("computer_proc_jobs_contract_mismatch", allDropped.error.code)

        api.nextProcJobsResponse = HomeNetworkResponse(200, """{"status":"unexpected"}""")
        assertIs<DataResult.Error>(client.machineProcJobs(ServerId("s-1"), "m-1"))

        api.nextProcJobsResponse = HomeNetworkResponse(403, """{"code":"insufficient_role","error":"forbidden"}""")
        assertIs<DataResult.NoPermission>(client.machineProcJobs(ServerId("s-1"), "m-1"))

        assertIs<DataResult.Error>(client.machineProcJobs(ServerId("s-1"), "   "))
    }

    @Test
    fun wireStopSendsExactBodyAndMapsOutcomesAndUpgradeRequired() = runPortableCoroutineTest {
        val api = FakeProcJobsComputersApi()
        val client = RaftComputersApiClient(api)

        api.nextStopResponse = HomeNetworkResponse(200, """{"outcome":"stopped"}""")
        val stopped = client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        assertEquals(ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped), stopped)
        assertEquals(StopCall("s-1", "m-1", """{"agentId":"agent-1","name":"dev-server"}"""), api.stopCalls.last())

        api.nextStopResponse = HomeNetworkResponse(200, """{"outcome":"already_exited"}""")
        assertEquals(
            ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.AlreadyExited),
            client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        )

        api.nextStopResponse = HomeNetworkResponse(200, """{"outcome":"not_found"}""")
        assertEquals(
            ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.NotFound),
            client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        )

        val failedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        api.nextStopResponse =
            HomeNetworkResponse(
                200,
                """{"outcome":"failed"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(failedTraceId))
            )
        assertEquals(
            ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Failed, traceId = failedTraceId),
            client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        )

        api.nextStopResponse =
            HomeNetworkResponse(
                409,
                """{"code":"computer_upgrade_required","error":"This Computer needs an upgrade before a job can be stopped from here."}"""
            )
        assertEquals(
            ComputerProcJobStopResult.UpgradeRequired,
            client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        )

        val offlineTraceId = "abcdef0123456789abcdef0123456789"
        api.nextStopResponse =
            HomeNetworkResponse(
                409,
                """{"code":"computer_offline","error":"This Computer is offline."}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(offlineTraceId))
            )
        assertEquals(
            ComputerProcJobStopResult.Error("computer_offline", traceId = offlineTraceId),
            client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        )

        api.nextStopResponse = HomeNetworkResponse(200, """{"outcome":"unknown_value"}""")
        assertEquals(
            ComputerProcJobStopResult.Error("computer_proc_job_stop_contract_mismatch"),
            client.stopMachineProcJob(ServerId("s-1"), "m-1", "agent-1", "dev-server")
        )
    }

    @Test
    fun stoppedByHandleValidationMatchesWebContract() {
        assertTrue(isValidComputerProcStoppedBy("tygg"))
        assertTrue(isValidComputerProcStoppedBy("alice_01-dev.io"))
        assertTrue(isValidComputerProcStoppedBy("a".repeat(64)))
        assertFalse(isValidComputerProcStoppedBy(null))
        assertFalse(isValidComputerProcStoppedBy(""))
        assertFalse(isValidComputerProcStoppedBy("a".repeat(65)))
        assertFalse(isValidComputerProcStoppedBy("@tygg"))
        assertFalse(isValidComputerProcStoppedBy("alice smith"))
        assertFalse(isValidComputerProcStoppedBy("line\nbreak"))
    }

    @Test
    fun groupJobsPutsRunningBeforeExitedAndNewestFirst() {
        val jobs =
            listOf(
                sampleJob("a1", "exited-new", ComputerProcJobState.Exited, "2026-11-21T01:00:00Z"),
                sampleJob("a1", "running-old", ComputerProcJobState.Running, "2026-11-21T00:10:00Z"),
                sampleJob("a1", "running-new", ComputerProcJobState.Running, "2026-11-21T00:50:00Z"),
                sampleJob("a1", "exited-old", ComputerProcJobState.Exited, "2026-11-20T23:00:00Z"),
                sampleJob("a2", "solo", ComputerProcJobState.Running, "2026-11-21T00:30:00Z")
            )
        val grouped = groupComputerProcJobsByAgent(jobs)
        assertEquals(
            listOf("running-new", "running-old", "exited-new", "exited-old"),
            grouped["a1"]?.map { it.name }
        )
        assertEquals(listOf("solo"), grouped["a2"]?.map { it.name })
    }

    @Test
    fun uptimeBucketsMatchWebThresholds() {
        assertEquals(ComputerProcJobUptimeFormat.Seconds(0), computerProcJobUptimeFormat(-5_000L))
        assertEquals(ComputerProcJobUptimeFormat.Seconds(45), computerProcJobUptimeFormat(45_900L))
        assertEquals(ComputerProcJobUptimeFormat.Minutes(1), computerProcJobUptimeFormat(60_000L))
        assertEquals(ComputerProcJobUptimeFormat.Minutes(59), computerProcJobUptimeFormat(59 * 60_000L))
        assertEquals(ComputerProcJobUptimeFormat.Hours(3, 5), computerProcJobUptimeFormat((3 * 60 + 5) * 60_000L))
        assertEquals(ComputerProcJobUptimeFormat.Hours(47, 59), computerProcJobUptimeFormat((47 * 60 + 59) * 60_000L))
        assertEquals(ComputerProcJobUptimeFormat.Days(2, 0), computerProcJobUptimeFormat(48 * 3_600_000L))
        assertEquals(ComputerProcJobUptimeFormat.Days(2, 4), computerProcJobUptimeFormat(52 * 3_600_000L))
    }

    @Test
    fun exitFormatFollowsExactPriorityOrder() {
        assertEquals(
            ComputerProcJobExitFormat.OwnerStop("tygg"),
            computerProcJobExitFormat(
                sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
                    .copy(exitReason = "owner_stop", stoppedBy = "tygg", exitCode = 143, exitSignal = "SIGTERM")
            )
        )
        assertEquals(
            ComputerProcJobExitFormat.OwnerStopUnknown,
            computerProcJobExitFormat(
                sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
                    .copy(exitReason = "owner_stop", stoppedBy = "bad handle!")
            )
        )
        assertEquals(
            ComputerProcJobExitFormat.MemoryLimit,
            computerProcJobExitFormat(
                sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
                    .copy(exitReason = "memory_limit", exitCode = 137)
            )
        )
        assertEquals(
            ComputerProcJobExitFormat.Code(0),
            computerProcJobExitFormat(
                sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
                    .copy(exitCode = 0, exitSignal = "SIGKILL")
            )
        )
        assertEquals(
            ComputerProcJobExitFormat.Signal("SIGTERM"),
            computerProcJobExitFormat(
                sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
                    .copy(exitSignal = "SIGTERM")
            )
        )
        assertEquals(
            ComputerProcJobExitFormat.Plain,
            computerProcJobExitFormat(sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z"))
        )
    }

    @Test
    fun memoryFormatHandlesRunningAndExitedJobs() {
        val runningBoth =
            sampleJob("a1", "j", ComputerProcJobState.Running, "2026-11-21T00:00:00Z")
                .copy(rssBytes = 1024L * 1024L, memoryMaxBytes = 4L * 1024L * 1024L * 1024L)
        assertIs<ComputerProcJobMemoryFormat.UsedOfCap>(computerProcJobMemoryFormat(runningBoth))

        val runningOnlyRss =
            sampleJob("a1", "j", ComputerProcJobState.Running, "2026-11-21T00:00:00Z")
                .copy(rssBytes = 0L, memoryMaxBytes = null)
        assertIs<ComputerProcJobMemoryFormat.UsedOnly>(computerProcJobMemoryFormat(runningOnlyRss))

        val exitedWithRssAndCap =
            sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
                .copy(rssBytes = 512L * 1024L, memoryMaxBytes = 2L * 1024L * 1024L * 1024L)
        assertIs<ComputerProcJobMemoryFormat.CapOnly>(computerProcJobMemoryFormat(exitedWithRssAndCap))

        val exitedNone = sampleJob("a1", "j", ComputerProcJobState.Exited, "2026-11-21T00:00:00Z")
        assertNull(computerProcJobMemoryFormat(exitedNone))
    }

    @Test
    fun epochMillisToIsoUtcFormatsCalendarBoundaries() {
        assertEquals("1970-01-01T00:00:00Z", computerProcEpochMillisToIsoUtc(0L))
        // 2024-02-29T23:59:59Z
        assertEquals("2024-02-29T23:59:59Z", computerProcEpochMillisToIsoUtc(1_709_251_199_000L))
    }

    @Test
    fun settingsComputerCanControlMatchesWebRules() {
        val base =
            SettingsComputerSnapshot(
                id = "m-1",
                name = "Studio",
                daemonVersion = "",
                computerVersion = "1.0.55",
                isComputer = true,
                online = true
            )
        assertTrue(settingsComputerCanControl(serverRole = "owner", currentUserId = "u-1", computer = base))
        assertTrue(settingsComputerCanControl(serverRole = "admin", currentUserId = "u-1", computer = base))
        assertFalse(settingsComputerCanControl(serverRole = "member", currentUserId = "u-1", computer = base))
        assertTrue(
            settingsComputerCanControl(
                serverRole = "member",
                currentUserId = "u-1",
                computer = base.copy(attachedByCurrentUser = true)
            )
        )
        assertTrue(
            settingsComputerCanControl(
                serverRole = "member",
                currentUserId = "u-1",
                computer = base.copy(creator = SettingsComputerCreatorSnapshot(id = "u-1", name = "alice"))
            )
        )
        assertTrue(
            settingsComputerCanControl(
                serverRole = "member",
                currentUserId = "u-1",
                computer = base.copy(isComputer = true, controlledByCurrentUser = true)
            )
        )
        // Revoked Computer (`isComputer == false`): controlledByCurrentUser must not grant control.
        assertFalse(
            settingsComputerCanControl(
                serverRole = "member",
                currentUserId = "u-1",
                computer = base.copy(isComputer = false, controlledByCurrentUser = true)
            )
        )
    }

    @Test
    fun controllerDropsStaleReadAnswersAndClearsWhenOffline() = runTest {
        val firstGate = CompletableDeferred<DataResult<ComputerProcJobsRead>>()
        val secondGate = CompletableDeferred<DataResult<ComputerProcJobsRead>>()
        var calls = 0
        val controller =
            RaftComputerProcJobsController(
                loadProcJobs = { _, _, _ ->
                    calls += 1
                    if (calls == 1) firstGate.await() else secondGate.await()
                },
                stopProcJobApi = { _, _, _, _ -> ComputerProcJobStopResult.Error("unused") }
            )

        val first = async { controller.refresh(ServerId("s-1"), "m-1", online = true) }
        val second = async { controller.refresh(ServerId("s-1"), "m-1", online = true) }

        val newerJob = sampleJob("a1", "newer", ComputerProcJobState.Running, "2026-11-21T01:00:00Z")
        val staleJob = sampleJob("a1", "stale", ComputerProcJobState.Running, "2026-11-21T00:00:00Z")
        secondGate.complete(DataResult.Content(ComputerProcJobsRead.Known("2026-11-21T01:00:00Z", listOf(newerJob))))
        second.await()
        assertEquals(listOf("newer"), controller.jobsByAgent["a1"]?.map { it.name })

        // Earlier read finishes late: must not overwrite newer snapshot.
        firstGate.complete(DataResult.Content(ComputerProcJobsRead.Known("2026-11-21T00:00:00Z", listOf(staleJob))))
        first.await()
        assertEquals(listOf("newer"), controller.jobsByAgent["a1"]?.map { it.name })

        // Going offline clears jobs without hitting the API.
        controller.refresh(ServerId("s-1"), "m-1", online = false)
        assertNull(controller.answer)
        assertTrue(controller.jobsByAgent.isEmpty())
    }

    @Test
    fun controllerStopNotifiesOutcomeBeforeRefreshOverlaysExitedStateAndRefreshesAfterDelay() = runTest {
        val runningJob =
            sampleJob("a1", "dev-server", ComputerProcJobState.Running, "2026-11-21T00:00:00Z")
                .copy(rssBytes = 10_000L, memoryMaxBytes = 1_000_000L)
        val events = mutableListOf<String>()
        var readCount = 0
        val controller =
            RaftComputerProcJobsController(
                loadProcJobs = { _, _, _ ->
                    readCount += 1
                    events += "read:$readCount"
                    if (readCount <= 2) {
                        // First load + immediate post-stop read still carry stale running report.
                        DataResult.Content(ComputerProcJobsRead.Known("2026-11-21T01:00:00Z", listOf(runningJob)))
                    } else {
                        // Delayed post-stop read returns the Computer's own report naming who stopped it.
                        DataResult.Content(
                            ComputerProcJobsRead.Known(
                                "2026-11-21T01:00:03Z",
                                listOf(
                                    runningJob.copy(
                                        state = ComputerProcJobState.Exited,
                                        exitedAt = "2026-11-21T01:00:01Z",
                                        exitReason = "owner_stop",
                                        stoppedBy = "tygg",
                                        rssBytes = null
                                    )
                                )
                            )
                        )
                    }
                },
                stopProcJobApi = { _, _, _, _ ->
                    ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped)
                },
                nowMillisProvider = { 1_709_251_199_000L },
                afterStopDelayMs = 50L
            )

        controller.refresh(ServerId("s-1"), "m-1", online = true)
        assertEquals(ComputerProcJobState.Running, controller.jobsByAgent["a1"]?.single()?.state)

        val result =
            controller.stopJob(
                job = runningJob,
                scope = this,
                serverId = ServerId("s-1"),
                machineId = "m-1",
                onOutcome = { events += "outcome" }
            )
        assertEquals(ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped), result)
        assertEquals(listOf("read:1", "outcome", "read:2"), events)
        val optimistic = requireNotNull(controller.jobsByAgent["a1"]?.single())
        assertEquals(ComputerProcJobState.Exited, optimistic.state)
        assertEquals("owner_stop", optimistic.exitReason)
        assertNull(optimistic.stoppedBy)
        assertNull(optimistic.rssBytes)

        delay(100L)
        assertEquals(3, readCount)
        val confirmed = requireNotNull(controller.jobsByAgent["a1"]?.single())
        assertEquals(ComputerProcJobState.Exited, confirmed.state)
        assertEquals("tygg", confirmed.stoppedBy)
    }

    @Test
    fun controllerStopSkipsPostStopRefreshIfComputerWentOffline() = runTest {
        val runningJob = sampleJob("a1", "dev-server", ComputerProcJobState.Running, "2026-11-21T00:00:00Z")
        val stopGate = CompletableDeferred<ComputerProcJobStopResult>()
        var readCount = 0
        val controller =
            RaftComputerProcJobsController(
                loadProcJobs = { _, _, _ ->
                    readCount += 1
                    DataResult.Content(ComputerProcJobsRead.Known("2026-11-21T01:00:00Z", listOf(runningJob)))
                },
                stopProcJobApi = { _, _, _, _ -> stopGate.await() },
                afterStopDelayMs = 50L
            )

        controller.refresh(ServerId("s-1"), "m-1", online = true)
        assertEquals(1, readCount)

        val stopCall = async { controller.stopJob(runningJob, this, ServerId("s-1"), "m-1") }
        runCurrent()
        // Computer goes offline while stop request is in flight.
        controller.refresh(ServerId("s-1"), "m-1", online = false)
        stopGate.complete(ComputerProcJobStopResult.Completed(ComputerProcJobStopOutcome.Stopped))
        stopCall.await()
        delay(100L)
        assertEquals(1, readCount)
        assertNull(controller.answer)
    }

    private fun sampleJob(
        agentId: String,
        name: String,
        state: ComputerProcJobState,
        startedAt: String
    ): ComputerProcJobSnapshot =
        ComputerProcJobSnapshot(
            agentId = agentId,
            name = name,
            state = state,
            startedAt = startedAt
        )

    private data class StopCall(val serverId: String, val machineId: String, val body: String)

    private class FakeProcJobsComputersApi : RaftComputersApi {
        var nextProcJobsResponse: HomeNetworkResponse = HomeNetworkResponse(200, """{"status":"unknown"}""")
        var nextStopResponse: HomeNetworkResponse = HomeNetworkResponse(200, """{"outcome":"stopped"}""")
        var lastProcJobsPriority: DataRequestPriority? = null
        val stopCalls = mutableListOf<StopCall>()

        override suspend fun machineProcJobs(serverId: ServerId, machineId: String): HomeNetworkResponse = nextProcJobsResponse

        override suspend fun machineProcJobs(
            serverId: ServerId,
            machineId: String,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            lastProcJobsPriority = priority
            return nextProcJobsResponse
        }

        override suspend fun stopMachineProcJob(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse {
            stopCalls += StopCall(serverId.value, machineId, body)
            return nextStopResponse
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = unused()
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = unused()
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = unused()
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = unused()
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = unused()
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = unused()
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = unused()
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            unused()

        private fun unused(): HomeNetworkResponse = error("not used by this test")
    }
}
