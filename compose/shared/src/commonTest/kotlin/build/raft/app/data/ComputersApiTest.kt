package build.raft.app.data

import build.raft.app.runPortableCoroutineTest
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ComputersApiTest {
    @Test
    fun typedComputersApiAcceptsCanonicalUpstreamShapes() = runPortableCoroutineTest {
        val transport = RecordingComputersApi()
        val client = RaftComputersApiClient(transport)
        val serverId = ServerId("server-1")

        val machines = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(serverId)).data
        assertEquals(1, machines.machineCount)
        assertEquals("0.0.62", machines.latestComputerVersion)
        val firstMachine = machines.machines.single()
        assertEquals(ComputerStatus.Online, firstMachine.status)
        assertEquals(7L, firstMachine.statusVersion)
        assertEquals("sk_machine_abc", firstMachine.apiKeyPrefix)
        assertEquals(listOf("claude", "codex"), firstMachine.runtimes)
        assertEquals(true, firstMachine.isComputer)
        assertEquals("0.0.61", firstMachine.computerVersion)
        assertEquals("Runs production agents", firstMachine.description)
        assertEquals("2026-06-14T02:00:00.000Z", firstMachine.lastHeartbeat)
        assertEquals("2026-05-01T00:00:00.000Z", firstMachine.createdAt)

        assertEquals("machine-1", assertIs<DataResult.Content<ComputerRegistrationSummary>>(client.registerMachine(serverId, "Maria")).data.machine.id)
        assertEquals("Maria Laptop", assertIs<DataResult.Content<ComputerSummary>>(client.renameMachine(serverId, "machine-1", "Maria Laptop")).data.name)
        assertEquals(
            "Build host",
            assertIs<DataResult.Content<ComputerSummary>>(
                client.updateMachineDescription(serverId, "machine-1", "Build and release host")
            ).data.description
        )
        assertEquals(true, assertIs<DataResult.Content<ComputerCommandResult>>(client.deleteMachine(serverId, "machine-1")).data.ok)
        assertEquals("sk_machine_1", assertIs<DataResult.Content<ComputerApiKeyRotationSummary>>(client.rotateMachineKey(serverId, "machine-1")).data.apiKeyPrefix)
        assertEquals(2, assertIs<DataResult.Content<ComputerWorkspacePage>>(client.machineWorkspaces(serverId, "machine-1")).data.itemCount)
        val runtimeModels = assertIs<DataResult.Content<ComputerRuntimeModelsSummary>>(client.runtimeModels(serverId, "machine-1", "claude")).data
        assertEquals("sonnet", runtimeModels.defaultModel)
        assertEquals(listOf("sonnet", "opus"), runtimeModels.models.map { it.id })
        assertEquals(listOf("Claude Sonnet", "Claude Opus"), runtimeModels.models.map { it.label })
        assertEquals(listOf("launchable", null), runtimeModels.models.map { it.verified })
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            runtimeModels.models.first().supportedReasoningEfforts
        )
        assertEquals("medium", runtimeModels.models.first().defaultReasoningEffort)
        assertEquals(emptyList(), runtimeModels.models.last().supportedReasoningEfforts)
        assertEquals(null, runtimeModels.models.last().defaultReasoningEffort)
        val runtimeCatalog =
            assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(
                client.runtimeOptions(serverId, "machine-1")
            ).data
        val builtIn = runtimeCatalog.options.single { it.runtimeId == "builtin" }
        assertEquals(true, builtIn.runtimeFormV2)
        assertEquals(true, builtIn.selectable)
        assertEquals(false, runtimeCatalog.options.single { it.runtimeId == "codex" }.runtimeFormV2)
        assertEquals(true, assertIs<DataResult.Content<ComputerCommandResult>>(client.deleteMachineWorkspace(serverId, "machine-1", "agent-old")).data.ok)

        assertEquals(
            listOf(
                "GET servers/server-1/machines",
                """POST servers/server-1/machines body={"name":"Maria"}""",
                """PATCH servers/server-1/machines/machine-1 body={"name":"Maria Laptop"}""",
                """PATCH servers/server-1/machines/machine-1 body={"description":"Build and release host"}""",
                "DELETE servers/server-1/machines/machine-1",
                "POST servers/server-1/machines/machine-1/rotate-key",
                "GET servers/server-1/machines/machine-1/workspaces",
                "GET servers/server-1/machines/machine-1/runtime-models/claude",
                "GET servers/server-1/machines/machine-1/runtime-options",
                "DELETE servers/server-1/machines/machine-1/workspaces/agent-old"
            ),
            transport.calls
        )
    }

    @Test
    fun upgradeFlagMapsFromTheServerPolicyDecisionForComputersOnly() = runPortableCoroutineTest {
        // Wire fields per botiverse/slock c1105a176: MachineReadModel (services/machineReadModel.ts) and the
        // GET /servers/:id/machines projection (routes/servers.ts) that adds computerUpgradeAvailable.
        val body =
            """
            {"machines":[
              {"id":"c-1","name":"Studio","status":"online","isComputer":true,"computerVersion":"1.0.0",
               "computerUpgradeAvailable":true,"userId":"user-1","computerAttachedByCurrentUser":true,"agentCount":2},
              {"id":"c-2","name":"Laptop","status":"offline","isComputer":true,"computerVersion":"0.1.0",
               "computerUpgradeAvailable":false,"userId":"user-2","computerAttachedByCurrentUser":false,"agentCount":0},
              {"id":"d-1","name":"Daemon","status":"online","isComputer":false,"daemonVersion":"0.1.0",
               "computerUpgradeAvailable":null,"userId":"user-3","computerAttachedByCurrentUser":false,"agentCount":1}
            ],"latestDaemonVersion":null,"latestComputerVersion":"9.9.9"}
            """.trimIndent()
        val summary =
            assertIs<DataResult.Content<ComputerListSummary>>(
                RaftComputersApiClient(RecordingComputersApi(machinesBody = body)).machines(ServerId("server-1"), DataRequestPriority.Visible)
            ).data
        val snapshots = summary.machines.map { it.toSettingsComputerSnapshot() }.associateBy { it.id }

        assertEquals(true, snapshots.getValue("c-1").upgradeAvailable)
        assertEquals(false, snapshots.getValue("c-2").upgradeAvailable)
        assertEquals(false, snapshots.getValue("d-1").upgradeAvailable)
        assertEquals("user-1", snapshots.getValue("c-1").creatorUserId)
        assertEquals(true, snapshots.getValue("c-1").attachedByCurrentUser)
        assertEquals(2, snapshots.getValue("c-1").agentCount)
        assertEquals(1, snapshots.getValue("d-1").agentCount)
    }

    @Test
    fun machineCreatorMapsTheServerCreatorSummaryOrNone() = runPortableCoroutineTest {
        // Wire shape per botiverse/slock 0e4aeb3af: GET /servers/:id/machines (routes/servers.ts) sends
        // `creator: computerLinkedMachineCreators.get(m.id) ?? null`, a ComputerCreatorSummary
        // (services/computerCredentialService.ts) `{type:"human", id, name, displayName|null, avatarUrl|null, gravatarHash}`.
        val body =
            """
            {"machines":[
              {"id":"c-1","name":"Studio","status":"online","isComputer":true,"computerVersion":"1.0.0",
               "creator":{"type":"human","id":"user-1","name":"artin","displayName":"Artin","avatarUrl":"https://cdn.raft.build/avatars/user-1.png",
               "gravatarHash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}},
              {"id":"c-2","name":"Laptop","status":"offline","isComputer":true,"computerVersion":"0.1.0",
               "creator":{"type":"human","id":"user-2","name":"maria","displayName":null,"avatarUrl":null,"gravatarHash":"abc123"}},
              {"id":"c-3","name":"Spare","status":"offline","isComputer":true,"computerVersion":"0.1.0","creator":null},
              {"id":"d-1","name":"Daemon","status":"online","isComputer":false,"daemonVersion":"0.1.0","creator":null}
            ],"latestDaemonVersion":null,"latestComputerVersion":"9.9.9"}
            """.trimIndent()
        val summary =
            assertIs<DataResult.Content<ComputerListSummary>>(
                RaftComputersApiClient(RecordingComputersApi(machinesBody = body)).machines(ServerId("server-1"), DataRequestPriority.Visible)
            ).data
        val snapshots = summary.machines.map { it.toSettingsComputerSnapshot() }.associateBy { it.id }

        assertEquals(
            SettingsComputerCreatorSnapshot(
                id = "user-1",
                name = "artin",
                displayName = "Artin",
                avatarUrl = "https://cdn.raft.build/avatars/user-1.png",
                gravatarHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
            ),
            snapshots.getValue("c-1").creator
        )
        assertEquals(
            SettingsComputerCreatorSnapshot(id = "user-2", name = "maria", displayName = null, avatarUrl = null, gravatarHash = "abc123"),
            snapshots.getValue("c-2").creator
        )
        assertEquals(null, snapshots.getValue("c-3").creator)
        assertEquals(null, snapshots.getValue("d-1").creator)
    }

    @Test
    fun diskAndMemoryStatusMapFromTheMachineReadModel() = runPortableCoroutineTest {
        // Wire shape per botiverse/slock 424c5203: services/machineReadModel.ts sends
        // `diskStatus: status === "online" ? diskStatus : null` and the same for `memoryStatus`,
        // each a MachineDiskStatus / MachineMemoryStatus `{availableBytes, totalBytes}`
        // (packages/shared/src/index.ts). Computers older than the memory report (#11032) send
        // `memoryStatus: null`; an older server leaves both out.
        val body =
            """
            {"machines":[
              {"id":"c-1","name":"Studio Mac","status":"online","isComputer":true,"agentCount":3,
               "diskStatus":{"availableBytes":128849018880,"totalBytes":536870912000},
               "memoryStatus":{"availableBytes":12884901888,"totalBytes":34359738368}},
              {"id":"c-2","name":"Lab Box","status":"online","isComputer":true,"agentCount":1,
               "diskStatus":{"availableBytes":64424509440,"totalBytes":256060514304},"memoryStatus":null},
              {"id":"c-3","name":"Build Box","status":"offline","isComputer":true,"agentCount":0,"diskStatus":null,"memoryStatus":null},
              {"id":"c-4","name":"Old Server Row","status":"online","isComputer":true},
              {"id":"c-5","name":"Bad Report","status":"online","isComputer":true,
               "diskStatus":{"availableBytes":-1,"totalBytes":0},"memoryStatus":{"availableBytes":1}}
            ],"latestDaemonVersion":null,"latestComputerVersion":"1.0.53"}
            """.trimIndent()
        val machines =
            assertIs<DataResult.Content<ComputerListSummary>>(
                RaftComputersApiClient(RecordingComputersApi(machinesBody = body)).machines(ServerId("server-1"), DataRequestPriority.Visible)
            ).data.machines.associateBy { it.id }

        assertEquals(ComputerSizeStatus(availableBytes = 128849018880.0, totalBytes = 536870912000.0), machines.getValue("c-1").diskStatus)
        assertEquals(ComputerSizeStatus(availableBytes = 12884901888.0, totalBytes = 34359738368.0), machines.getValue("c-1").memoryStatus)
        assertEquals(3, machines.getValue("c-1").agentCount)
        assertEquals(64424509440.0, machines.getValue("c-2").diskStatus?.availableBytes)
        assertEquals(null, machines.getValue("c-2").memoryStatus)
        assertEquals(null, machines.getValue("c-3").diskStatus)
        assertEquals(null, machines.getValue("c-4").diskStatus)
        assertEquals(null, machines.getValue("c-4").memoryStatus)
        // Web readableDiskStatus: no positive total or a negative free size is not shown.
        assertEquals(null, machines.getValue("c-5").diskStatus)
        assertEquals(null, machines.getValue("c-5").memoryStatus)
    }

    @Test
    fun typedComputersApiAcceptsRawRowShapeForRename() = runPortableCoroutineTest {
        // Server returns the raw machines DB row for PATCH (no status / statusVersion
        // enrichment). The typed API must accept this shape, NOT flag it as contract
        // mismatch, and still produce a ComputerSummary with name + id propagated.
        val transport = RecordingComputersApi(
            renameBody = """{"id":"machine-1","name":"Maria Laptop","description":"Build host","apiKeyPrefix":"sk_machine_abc","hostname":"maria.local","createdAt":"2026-05-01T00:00:00.000Z"}"""
        )
        val client = RaftComputersApiClient(transport)
        val serverId = ServerId("server-1")

        val renamed = assertIs<DataResult.Content<ComputerSummary>>(client.renameMachine(serverId, "machine-1", "Maria Laptop")).data
        assertEquals("machine-1", renamed.id)
        assertEquals("Maria Laptop", renamed.name)
        assertEquals("sk_machine_abc", renamed.apiKeyPrefix)
        assertEquals("Build host", renamed.description)
        assertEquals(ComputerStatus.Unknown, renamed.status)
        assertEquals(0L, renamed.statusVersion)
    }

    @Test
    fun typedComputersApiMapsHttpErrorsToStructuredCodes() = runPortableCoroutineTest {
        // 307 fly-replay, 400 bad request, 409 has-agents, 503 machine offline each
        // produce a specific structured error code so the Settings page can show
        // human-readable copy instead of "http_307".
        val transport = RecordingComputersApi()
        val client = RaftComputersApiClient(transport)
        val serverId = ServerId("server-1")

        transport.respond = { code -> HomeNetworkResponse(code = code, bodyText = """{"error":"x"}""") }
        val replayError = assertIs<DataResult.Error>(client.machineWorkspaces(serverId, "m")).error
        assertEquals("machine_offline_replay", replayError.code)
        assertEquals(true, replayError.retryable)
        assertEquals("x", replayError.serverMessage)
        assertEquals("computer_bad_request", assertIs<DataResult.Error>(client.renameMachine(serverId, "m", "n")).error.code)
        assertEquals(false, assertIs<DataResult.Error>(client.renameMachine(serverId, "m", "n")).error.retryable)
        assertEquals("computer_bad_request", assertIs<DataResult.Error>(client.updateMachineDescription(serverId, "m", "d")).error.code)
        assertEquals("computer_has_agents", assertIs<DataResult.Error>(client.deleteMachine(serverId, "m")).error.code)
        assertEquals(false, assertIs<DataResult.Error>(client.deleteMachine(serverId, "m")).error.retryable)
        val forbidden = assertIs<DataResult.NoPermission>(client.machines(serverId)).blocker
        assertEquals("server_forbidden", forbidden.code)
        assertEquals("x", forbidden.serverMessage)
    }

    @Test
    fun typedComputersApiAcceptsOnlyTheCanonicalMachineErrorField() = runPortableCoroutineTest {
        val transport = RecordingComputersApi()
        val client = RaftComputersApiClient(transport)
        val serverId = ServerId("server-1")
        val fixtures =
            listOf(
                ComputersErrorFixture(
                    name = "canonical-error-body",
                    errorBody = """{"error":"This computer still has active agents"}""",
                    expected = "This computer still has active agents"
                ),
                ComputersErrorFixture(
                    name = "future-fields",
                    body = """{"error":"This computer still has active agents","future":{"agentCount":2}}""",
                    expected = "This computer still has active agents"
                ),
                ComputersErrorFixture("wrong-alias", body = """{"message":"must not be guessed"}"""),
                ComputersErrorFixture("wrong-type", body = """{"error":409}"""),
                ComputersErrorFixture("nested", body = """{"error":{"message":"must not be guessed"}}"""),
                ComputersErrorFixture("malformed", body = """{"error":"unterminated"""),
                ComputersErrorFixture("plain-text", body = "must not be guessed"),
                ComputersErrorFixture("blank", body = ""),
                ComputersErrorFixture("missing")
            )

        fixtures.forEach { fixture ->
            transport.respond = { code ->
                HomeNetworkResponse(
                    code = code,
                    bodyText = fixture.body,
                    errorBodyText = fixture.errorBody
                )
            }

            val error = assertIs<DataResult.Error>(client.deleteMachine(serverId, "machine-1"), fixture.name).error
            assertEquals("computer_has_agents", error.code, fixture.name)
            assertEquals(fixture.expected, error.serverMessage, fixture.name)
        }
    }

    @Test
    fun typedComputersApiRejectsGuessedWrappersAndAliases() = runPortableCoroutineTest {
        val client =
            RaftComputersApiClient(
                RecordingComputersApi(
                    machinesBody = """{"items":[{"id":"machine-1","name":"Maria"}]}""",
                    registerBody = """{"computer":{"id":"machine-1","name":"Maria"},"key":"secret"}""",
                    renameBody = """{"machine":{"id":"machine-1","name":"Maria"}}""",
                    commandBody = """{"success":true}""",
                    rotateBody = """{"key":"sk_machine_123"}""",
                    workspacesBody = """{"workspaces":[{"directoryName":"agent-1"}]}""",
                    runtimeModelsBody = """{"items":["sonnet"],"defaultModel":"sonnet"}"""
                )
            )
        val serverId = ServerId("server-1")

        assertEquals("computers_list_contract_mismatch", assertIs<DataResult.Error>(client.machines(serverId)).error.code)
        assertEquals("computer_register_contract_mismatch", assertIs<DataResult.Error>(client.registerMachine(serverId, "Maria")).error.code)
        assertEquals("computer_rename_contract_mismatch", assertIs<DataResult.Error>(client.renameMachine(serverId, "machine-1", "Maria")).error.code)
        assertEquals("computer_update_contract_mismatch", assertIs<DataResult.Error>(client.updateMachineDescription(serverId, "machine-1", "Maria host")).error.code)
        assertEquals("computer_delete_contract_mismatch", assertIs<DataResult.Error>(client.deleteMachine(serverId, "machine-1")).error.code)
        assertEquals("computer_rotate_key_contract_mismatch", assertIs<DataResult.Error>(client.rotateMachineKey(serverId, "machine-1")).error.code)
        assertEquals("computer_workspaces_contract_mismatch", assertIs<DataResult.Error>(client.machineWorkspaces(serverId, "machine-1")).error.code)
        assertEquals("computer_runtime_models_contract_mismatch", assertIs<DataResult.Error>(client.runtimeModels(serverId, "machine-1", "claude")).error.code)
    }

    @Test
    fun runtimeOptionsAcceptAdditiveServerFields() = runPortableCoroutineTest {
        // Hands c453ba5f: runtime-options rows are decoded leniently, so a key this client does not know
        // (a future hint, or the v1 `formDefinitionRef` this client no longer reads) never fails the catalog.
        val serverId = ServerId("server-1")
        val withFutureKeys =
            canonicalRuntimeOptionsBody
                .replace("{\"context\":\"new_agent\"", "{\"generatedAt\":\"2026-10-09T00:00:00Z\",\"context\":\"new_agent\"")
                .replace("\"runtimeId\":\"codex\",", "\"runtimeId\":\"codex\",\"someFutureHint\":{\"a\":1},")
                .replace(
                    "\"runtimeFormV2\":{\"protocolVersion\":2}",
                    "\"runtimeFormV2\":{\"protocolVersion\":2}," +
                        "\"formDefinitionRef\":{\"protocolVersion\":1,\"runtimeId\":\"builtin\",\"schemaVersion\":\"builtin-pi.create.v3\"}"
                )
        check("someFutureHint" in withFutureKeys && "generatedAt" in withFutureKeys && "formDefinitionRef" in withFutureKeys)
        val catalog =
            assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(
                RaftComputersApiClient(RecordingComputersApi(runtimeOptionsBody = withFutureKeys))
                    .runtimeOptions(serverId, "machine-1")
            ).data
        assertEquals(listOf("codex", "builtin"), catalog.options.map { it.runtimeId })
        assertEquals(true, catalog.options.single { it.runtimeId == "builtin" }.runtimeFormV2)
    }

    @Test
    fun runtimeFormV2MarkerIsReadOnlyForProtocolVersion2() = runPortableCoroutineTest {
        // slock runtimeAdmissionService: the marker is `{ protocolVersion: 2 }`; any other version is not a v2 form.
        val v3 = canonicalRuntimeOptionsBody.replace("\"runtimeFormV2\":{\"protocolVersion\":2}", "\"runtimeFormV2\":{\"protocolVersion\":3}")
        check(v3 != canonicalRuntimeOptionsBody)
        val catalog =
            assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(
                RaftComputersApiClient(RecordingComputersApi(runtimeOptionsBody = v3)).runtimeOptions(ServerId("server-1"), "machine-1")
            ).data
        assertEquals(false, catalog.options.single { it.runtimeId == "builtin" }.runtimeFormV2)
    }

    private class RecordingComputersApi(
        private val machinesBody: String =
            """{"machines":[{"id":"machine-1","name":"Maria Laptop","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"daemonVersion":"0.53.0","computerVersion":"0.0.61","isComputer":true,"hostname":"maria.local","os":"darwin","description":"Runs production agents","lastHeartbeat":"2026-06-14T02:00:00.000Z","createdAt":"2026-05-01T00:00:00.000Z"}],"latestDaemonVersion":"0.53.0","latestComputerVersion":"0.0.62"}""",
        private val registerBody: String =
            """{"machine":{"id":"machine-1","name":"Maria","status":"offline","statusVersion":0,"apiKeyPrefix":"sk_machine_123456","runtimes":[],"daemonVersion":null,"hostname":null,"os":null,"lastHeartbeat":null,"createdAt":null},"apiKey":"sk_machine_123456"}""",
        private val renameBody: String =
            """{"id":"machine-1","name":"Maria Laptop","status":"online","statusVersion":7,"apiKeyPrefix":"sk_machine_abc","runtimes":["claude","codex"],"daemonVersion":"0.53.0","hostname":"maria.local","os":"darwin","description":"Build host","lastHeartbeat":"2026-06-14T02:00:00.000Z","createdAt":"2026-05-01T00:00:00.000Z"}""",
        private val commandBody: String = """{"ok":true}""",
        private val rotateBody: String = """{"apiKey":"sk_machine_123456"}""",
        private val workspacesBody: String =
            """[{"directoryName":"agent-live","totalSizeBytes":2048,"lastModified":"2026-05-26T00:00:00.000Z","fileCount":12,"status":"active","agentName":"Noel","agentStatus":"active"},{"directoryName":"agent-old","totalSizeBytes":1,"lastModified":"2026-05-25T00:00:00.000Z","fileCount":1,"status":"orphan","agentName":null,"agentStatus":null}]""",
        private val runtimeModelsBody: String =
            """{"models":[{"id":"sonnet","label":"Claude Sonnet","verified":"launchable","supportedReasoningEfforts":["low","medium","high","xhigh"],"defaultReasoningEffort":"medium"},{"id":"opus","label":"Claude Opus"}],"default":"sonnet"}""",
        private val runtimeOptionsBody: String = canonicalRuntimeOptionsBody
    ) : RaftComputersApi {
        var respond: ((Int) -> HomeNetworkResponse)? = null
        val calls = mutableListOf<String>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines"
            return respond?.invoke(403) ?: ok(machinesBody)
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines body=$body"
            return respond?.invoke(403) ?: ok(registerBody)
        }

        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse {
            calls += "PATCH servers/${serverId.value}/machines/$machineId body=$body"
            return respond?.invoke(400) ?: ok(renameBody)
        }

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "DELETE servers/${serverId.value}/machines/$machineId"
            return respond?.invoke(409) ?: ok(commandBody)
        }

        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines/$machineId/rotate-key"
            return respond?.invoke(403) ?: ok(rotateBody)
        }

        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/workspaces"
            return respond?.invoke(307) ?: ok(workspacesBody)
        }

        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/runtime-models/$runtime"
            return respond?.invoke(503) ?: ok(runtimeModelsBody)
        }

        override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/runtime-options"
            return ok(runtimeOptionsBody)
        }

        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse {
            calls += "DELETE servers/${serverId.value}/machines/$machineId/workspaces/$directoryName"
            return ok(commandBody)
        }

        private fun ok(body: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = body)
    }

    private data class ComputersErrorFixture(
        val name: String,
        val body: String? = null,
        val errorBody: String? = null,
        val expected: String? = null
    )

    private companion object {
        val canonicalRuntimeOptionsBody =
            """{"context":"new_agent","machineId":"machine-1","options":[{"runtimeId":"codex","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true},{"runtimeId":"builtin","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true,"runtimeFormV2":{"protocolVersion":2}}]}"""

    }
}
