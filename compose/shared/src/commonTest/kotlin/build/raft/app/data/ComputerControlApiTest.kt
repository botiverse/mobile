package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Computer control and upgrade-state wire shapes, verified against botiverse/slock staging
 * 6b367a0cd40adf0532ae0fe226091a8dbd395140: `GET /servers/:id/machines` and
 * `POST /servers/:id/machines/:machineId/computer/:action` in packages/server/src/routes/servers.ts,
 * `projectComputerBroadcastPolicyDecision` (services/computerBroadcastPolicyService.ts),
 * `projectComputerUpgradeRequest` (services/computerUpgradeRequestService.ts), `ComputerVersionGate`
 * and `ComputerReleaseNotes` (packages/shared/src/index.ts, computerReleaseNotes.ts).
 */
class ComputerControlApiTest {
    @Test
    fun machinesRowsCarryTheUpgradePolicyRequestGateAndTheListReleaseNotes() = runPortableCoroutineTest {
        val client = RaftComputersApiClient(ControlComputersApi())

        val list = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(ServerId("server-1"))).data
        val computer = list.machines.first { it.id == "machine-1" }
        assertEquals(
            SettingsComputerBroadcastPolicySnapshot(
                eligibility = "eligible",
                targetVersion = "1.0.50",
                policyRevision = "rev-7",
                reasonCode = "eligible"
            ),
            computer.computerBroadcastPolicy
        )
        assertEquals(true, computer.computerBroadcastPolicy?.eligible)
        assertEquals(
            SettingsComputerUpgradeRequestSnapshot(
                id = "req-1",
                targetVersion = "1.0.49",
                requestedAt = "2026-10-20T10:00:00.000Z",
                state = "failed",
                observedVersion = "1.0.46",
                reason = "installer exited 1",
                resolvedAt = "2026-10-20T10:05:00.000Z"
            ),
            computer.upgradeRequest
        )
        assertEquals(true, computer.remoteUpgradeSupported)
        assertEquals(
            SettingsComputerVersionGateSnapshot(
                reason = "computer_version_unsupported",
                minVersion = "1.0.48",
                enforcedAt = "2026-11-01T00:00:00.000Z",
                enforced = false
            ),
            computer.versionGate
        )
        // The list-level hints travel on every row for the detail page.
        assertEquals("1.0.50", computer.latestComputerVersion)
        assertEquals(
            SettingsComputerReleaseNotesSnapshot(version = "1.0.50", en = "- Faster restarts", zhCn = "- 更快的重启"),
            computer.latestComputerReleaseNotes
        )

        val daemon = list.machines.first { it.id == "machine-2" }
        assertNull(daemon.computerBroadcastPolicy)
        assertNull(daemon.upgradeRequest)
        assertNull(daemon.remoteUpgradeSupported)
        assertNull(daemon.versionGate)

        val snapshot = computer.toSettingsComputerSnapshot()
        assertEquals(computer.computerBroadcastPolicy, snapshot.broadcastPolicy)
        assertEquals(computer.upgradeRequest, snapshot.upgradeRequest)
        assertEquals(computer.latestComputerReleaseNotes, snapshot.latestComputerReleaseNotes)
    }

    @Test
    fun releaseNotesWithoutAnyUsableLocaleAreDropped() = runPortableCoroutineTest {
        val body = machinesBody(releaseNotes = """{"version":"1.0.50","en":"  ","fr":"- Bonjour"}""")
        val client = RaftComputersApiClient(ControlComputersApi(machinesBody = body))

        val list = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(ServerId("server-1"))).data

        assertNull(list.machines.first().latestComputerReleaseNotes)
    }

    /**
     * `agentScopes` (slock packages/shared/src/agentMemoryLimit.ts `MachineAgentScopesStatus`, served by
     * services/machineReadModel.ts since botiverse/slock #10860): parsed when well-formed, dropped otherwise.
     */
    @Test
    fun machinesRowsCarryTheAgentScopesStatus() = runPortableCoroutineTest {
        val body =
            """
            {"machines":[
              {"id":"m-1","name":"A","status":"online","agentScopes":{"state":"unavailable","limitsConfigured":true,"limitsNotApplied":true,
               "reason":"memory_controller_not_delegated","memoryMax":"6G"}},
              {"id":"m-2","name":"B","status":"online","agentScopes":{"state":"available","limitsConfigured":true,"limitsNotApplied":false,
               "memoryMax":"6G","memoryHigh":"5G"}},
              {"id":"m-3","name":"C","status":"offline","agentScopes":null},
              {"id":"m-4","name":"D","status":"online"},
              {"id":"m-5","name":"E","status":"online","agentScopes":{"state":"weird","limitsConfigured":true,"limitsNotApplied":true}},
              {"id":"m-6","name":"F","status":"online","agentScopes":{"state":"suspended","limitsConfigured":true,"limitsNotApplied":true,"reason":"Not An Enum"}}
            ]}
            """.trimIndent()
        val client = RaftComputersApiClient(ControlComputersApi(machinesBody = body))

        val machines = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(ServerId("server-1"))).data.machines.associateBy { it.id }

        val notApplied =
            SettingsComputerAgentScopesSnapshot(
                state = "unavailable",
                limitsConfigured = true,
                limitsNotApplied = true,
                reason = "memory_controller_not_delegated",
                memoryMax = "6G"
            )
        assertEquals(notApplied, machines.getValue("m-1").agentScopes)
        assertEquals(notApplied, machines.getValue("m-1").toSettingsComputerSnapshot().agentScopes)
        assertEquals(
            SettingsComputerAgentScopesSnapshot(state = "available", limitsConfigured = true, limitsNotApplied = false, memoryMax = "6G", memoryHigh = "5G"),
            machines.getValue("m-2").agentScopes
        )
        assertNull(machines.getValue("m-3").agentScopes)
        assertNull(machines.getValue("m-4").agentScopes)
        assertNull(machines.getValue("m-5").agentScopes)
        assertEquals(
            SettingsComputerAgentScopesSnapshot(state = "suspended", limitsConfigured = true, limitsNotApplied = true),
            machines.getValue("m-6").agentScopes
        )
    }

    @Test
    fun restartPostsAnEmptyBodyAndReturnsTheRequestId() = runPortableCoroutineTest {
        val api = ControlComputersApi()
        val client = RaftComputersApiClient(api)

        val restart = assertIs<DataResult.Content<ComputerRestartSummary>>(client.restartComputer(ServerId("server-1"), "machine-1")).data

        assertEquals("op-dispatch-1", restart.requestId)
        assertEquals(false, restart.queued)
        assertEquals(listOf(ControlCall("server-1", "machine-1", "restart", "{}")), api.controlCalls)
    }

    @Test
    fun upgradePostsTheConsentedTargetAndReturnsTheAcceptedRequest() = runPortableCoroutineTest {
        val api = ControlComputersApi()
        val client = RaftComputersApiClient(api)

        val request =
            assertIs<DataResult.Content<SettingsComputerUpgradeRequestSnapshot>>(
                client.upgradeComputer(ServerId("server-1"), "machine-1", " 1.0.50 ")
            ).data

        assertEquals("req-2", request.id)
        assertEquals("pending", request.state)
        assertEquals("1.0.50", request.targetVersion)
        assertEquals(listOf(ControlCall("server-1", "machine-1", "upgrade", """{"targetVersion":"1.0.50"}""")), api.controlCalls)
    }

    @Test
    fun controlRefusalsKeepTheServerCode() = runPortableCoroutineTest {
        listOf(
            HomeNetworkResponse(409, null, """{"error":"Computer is not online","code":"computer_offline"}""") to "computer_offline",
            HomeNetworkResponse(403, null, """{"error":"Remote Computer upgrade is not enabled for this server","code":"remote_upgrade_disabled"}""") to
                "remote_upgrade_disabled",
            HomeNetworkResponse(409, """{"error":"x","code":"computer_broadcast_not_eligible","policy":{"eligibility":"no_broadcast"}}""") to
                "computer_broadcast_not_eligible",
        ).forEach { (response, code) ->
            val client = RaftComputersApiClient(ControlComputersApi(controlResponse = response))
            val error = assertIs<DataResult.Error>(client.upgradeComputer(ServerId("server-1"), "machine-1", "1.0.50")).error
            assertEquals(code, error.code, "response=$response")
        }
        // Without a code the status mapping of the other Computer routes applies (403 → no permission).
        val forbidden =
            RaftComputersApiClient(ControlComputersApi(controlResponse = HomeNetworkResponse(403, null, """{"error":"forbidden"}""")))
        assertEquals(
            "server_forbidden",
            assertIs<DataResult.NoPermission>(forbidden.restartComputer(ServerId("server-1"), "machine-1")).blocker.code
        )
    }

    private data class ControlCall(val serverId: String, val machineId: String, val action: String, val body: String)

    private class ControlComputersApi(
        private val machinesBody: String = machinesBody(),
        private val controlResponse: HomeNetworkResponse? = null,
    ) : RaftComputersApi {
        val controlCalls = mutableListOf<ControlCall>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = HomeNetworkResponse(200, machinesBody)

        override suspend fun controlComputer(serverId: ServerId, machineId: String, action: String, body: String): HomeNetworkResponse {
            controlCalls += ControlCall(serverId.value, machineId, action, body)
            controlResponse?.let { return it }
            return when (action) {
                "restart" -> HomeNetworkResponse(200, """{"ok":true,"action":"restart","requestId":"op-dispatch-1","operationId":"op-1"}""")
                else ->
                    HomeNetworkResponse(
                        201,
                        """{"upgradeRequest":{"id":"req-2","targetVersion":"1.0.50","requestedAt":"2026-10-21T01:00:00.000Z","state":"pending","observedVersion":null,"reason":null,"resolvedAt":null}}"""
                    )
            }
        }

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

    private companion object {
        fun machinesBody(
            releaseNotes: String = """{"version":"1.0.50","en":"- Faster restarts","zh-CN":"- 更快的重启"}""",
        ): String =
            """
            {"machines":[
              {"id":"machine-1","name":"Studio","status":"online","statusVersion":3,"runtimes":["claude"],"daemonVersion":null,
               "computerVersion":"1.0.46","isComputer":true,"hostname":"studio.local","os":"darwin","description":null,
               "lastHeartbeat":"2026-10-21T00:59:00.000Z","createdAt":"2026-05-01T00:00:00.000Z","userId":"user-1",
               "computerAttachedByCurrentUser":true,"agentCount":2,
               "versionGate":{"reason":"computer_version_unsupported","minVersion":"1.0.48","enforcedAt":"2026-11-01T00:00:00.000Z","enforced":false},
               "computerUpgradeAvailable":true,
               "computerBroadcastPolicy":{"eligibility":"eligible","targetVersion":"1.0.50","targetRole":"post_K","migrationClass":"seamless","policyRevision":"rev-7","reasonCode":"eligible"},
               "upgradeRequest":{"id":"req-1","targetVersion":"1.0.49","requestedAt":"2026-10-20T10:00:00.000Z","state":"failed","observedVersion":"1.0.46","reason":"installer exited 1","resolvedAt":"2026-10-20T10:05:00.000Z"},
               "remoteUpgradeSupported":true},
              {"id":"machine-2","name":"Old box","status":"offline","statusVersion":1,"runtimes":[],"daemonVersion":"0.65.0",
               "computerVersion":null,"isComputer":false,"hostname":null,"os":"linux","description":null,"lastHeartbeat":null,
               "createdAt":"2026-01-01T00:00:00.000Z","versionGate":null,"computerUpgradeAvailable":null,
               "computerBroadcastPolicy":null,"remoteUpgradeSupported":null}
            ],
            "latestDaemonVersion":null,"latestComputerVersion":"1.0.50","latestComputerReleaseNotes":$releaseNotes}
            """.trimIndent()
    }
}
