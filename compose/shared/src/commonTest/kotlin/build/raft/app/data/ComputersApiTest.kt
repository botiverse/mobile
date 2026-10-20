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
        val client = SlockComputersApiClient(transport)
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
        val formRef = requireNotNull(builtIn.formDefinitionRef)
        assertEquals("1:builtin:builtin-pi.create.v2", formRef.identityKey)
        assertEquals(true, builtIn.selectable)
        val definition =
            assertIs<DataResult.Content<SharedAgentCreateFormDefinition>>(
                client.runtimeFormDefinition(serverId, "machine-1", formRef)
            ).data
        assertEquals(
            listOf("providerId", "apiKey", "baseUrl", "supportsImageInput", "model", "envVars"),
            definition.uiSchema.order
        )
        assertEquals(setOf("/apiKey"), definition.capabilities.writeOnlyPointers)
        val providerSourceRef = requireNotNull(definition.optionSources["provider"])
        val providerSource =
            assertIs<SharedAgentCreateFormOptionSource.Select>(
                assertIs<DataResult.Content<SharedAgentCreateFormOptionSource>>(
                    client.runtimeFormOptionSource(serverId, "machine-1", formRef, providerSourceRef)
                ).data
            )
        assertEquals(
            listOf("deepseek", "openai-compatible", "anthropic-compatible"),
            providerSource.options.map { it.value }
        )
        val modelSourceRef = requireNotNull(definition.optionSources["model"])
        val modelSource =
            assertIs<SharedAgentCreateFormOptionSource.DependentSelect>(
                assertIs<DataResult.Content<SharedAgentCreateFormOptionSource>>(
                    client.runtimeFormOptionSource(serverId, "machine-1", formRef, modelSourceRef)
                ).data
            )
        assertEquals(true, modelSource.customValueAllowedByValue["openai-compatible"])
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
                "GET servers/server-1/machines/machine-1/runtime-form-definitions/builtin?schemaVersion=builtin-pi.create.v2",
                "GET servers/server-1/machines/machine-1/runtime-form-definitions/builtin/option-sources/provider?schemaVersion=builtin-pi.create.v2",
                "GET servers/server-1/machines/machine-1/runtime-form-definitions/builtin/option-sources/model?schemaVersion=builtin-pi.create.v2",
                "DELETE servers/server-1/machines/machine-1/workspaces/agent-old"
            ),
            transport.calls
        )
    }

    @Test
    fun typedComputersApiAcceptsRawRowShapeForRename() = runPortableCoroutineTest {
        // Server returns the raw machines DB row for PATCH (no status / statusVersion
        // enrichment). The typed API must accept this shape, NOT flag it as contract
        // mismatch, and still produce a ComputerSummary with name + id propagated.
        val transport = RecordingComputersApi(
            renameBody = """{"id":"machine-1","name":"Maria Laptop","description":"Build host","apiKeyPrefix":"sk_machine_abc","hostname":"maria.local","createdAt":"2026-05-01T00:00:00.000Z"}"""
        )
        val client = SlockComputersApiClient(transport)
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
        val client = SlockComputersApiClient(transport)
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
        val client = SlockComputersApiClient(transport)
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
            SlockComputersApiClient(
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
    fun runtimeFormContractFailsClosedOnUnknownOrMismatchedIdentity() = runPortableCoroutineTest {
        val serverId = ServerId("server-1")
        val unknownProtocol =
            SlockComputersApiClient(
                RecordingComputersApi(
                    runtimeOptionsBody = canonicalRuntimeOptionsBody.replace("\"protocolVersion\":1", "\"protocolVersion\":2")
                )
            )
        val catalog = assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(unknownProtocol.runtimeOptions(serverId, "machine-1")).data
        val unknownRef = requireNotNull(catalog.options.single { it.runtimeId == "builtin" }.formDefinitionRef)
        assertEquals("runtime_form_ref_unsupported", assertIs<DataResult.Error>(unknownProtocol.runtimeFormDefinition(serverId, "machine-1", unknownRef)).error.code)

        val expectedRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v2")
        val mismatchedDefinition =
            SlockComputersApiClient(
                RecordingComputersApi(
                    runtimeFormDefinitionBody = canonicalRuntimeFormDefinitionBody.replace("builtin-pi.create.v2", "builtin-pi.create.v3")
                )
            )
        assertEquals(
            "runtime_form_definition_contract_mismatch",
            assertIs<DataResult.Error>(mismatchedDefinition.runtimeFormDefinition(serverId, "machine-1", expectedRef)).error.code
        )

        val validClient = SlockComputersApiClient(RecordingComputersApi())
        val definition = assertIs<DataResult.Content<SharedAgentCreateFormDefinition>>(
            validClient.runtimeFormDefinition(serverId, "machine-1", expectedRef)
        ).data
        val sourceRef = requireNotNull(definition.optionSources["provider"])
        val mismatchedSource =
            SlockComputersApiClient(
                RecordingComputersApi(
                    runtimeFormOptionSourceBodies =
                        mapOf(
                            "provider" to canonicalProviderSourceBody.replace("\"sourceId\":\"provider\"", "\"sourceId\":\"other\"")
                        )
                )
            )
        assertEquals(
            "runtime_form_option_source_contract_mismatch",
            assertIs<DataResult.Error>(mismatchedSource.runtimeFormOptionSource(serverId, "machine-1", expectedRef, sourceRef)).error.code
        )
    }

    @Test
    fun runtimeFormContractRejectsSelectionAndPointerMutations() = runPortableCoroutineTest {
        val serverId = ServerId("server-1")
        val expectedRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v2")

        listOf(
            canonicalRuntimeOptionsBody.replace(
                "\"capabilityStatus\":\"available\",\"admissionStatus\":\"available_for_new\"",
                "\"capabilityStatus\":\"not_installed\",\"admissionStatus\":\"available_for_new\""
            ),
            canonicalRuntimeOptionsBody.replace(
                "\"protocolVersion\":1,\"runtimeId\":\"builtin\"",
                "\"protocolVersion\":1,\"runtimeId\":\"codex\""
            )
        ).forEach { body ->
            val result = SlockComputersApiClient(RecordingComputersApi(runtimeOptionsBody = body))
                .runtimeOptions(serverId, "machine-1")
            assertEquals("computer_runtime_options_contract_mismatch", assertIs<DataResult.Error>(result).error.code)
        }

        listOf(
            canonicalRuntimeFormDefinitionBody.replace(
                "\"writeOnlyPointers\":[\"/apiKey\"]",
                "\"writeOnlyPointers\":[\"apiKey\"]"
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"forbiddenPointers\":[\"/hostUserState\"]",
                "\"forbiddenPointers\":[\"/hostState\"]"
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"providerKinds\":[\"preset\",\"gateway\"]",
                "\"providerKinds\":[\"preset\"]"
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"required\":[\"providerId\",\"apiKey\",\"model\"]",
                "\"required\":[\"providerId\",\"apiKey\",\"model\",\"baseUrl\"]"
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"advanced\":[\"/envVars\"]",
                "\"advanced\":[\"/envVars\",\"/model\"]"
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"pointer\":\"/supportsImageInput\"",
                "\"pointer\":\"/model\""
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"supportsImageInput\":{\"type\":\"boolean\",\"title\":\"Image input\"}",
                "\"supportsImageInput\":{\"type\":\"string\",\"title\":\"Image input\"}"
            ),
            canonicalRuntimeFormDefinitionBody.replace(
                "\"properties\":{",
                "\"unknownTopology\":true,\"properties\":{"
            )
        ).forEach { body ->
            val result = SlockComputersApiClient(RecordingComputersApi(runtimeFormDefinitionBody = body))
                .runtimeFormDefinition(serverId, "machine-1", expectedRef)
            assertEquals("runtime_form_definition_contract_mismatch", assertIs<DataResult.Error>(result).error.code)
        }

        val validClient = SlockComputersApiClient(RecordingComputersApi())
        val definition = assertIs<DataResult.Content<SharedAgentCreateFormDefinition>>(
            validClient.runtimeFormDefinition(serverId, "machine-1", expectedRef)
        ).data
        val providerRef = requireNotNull(definition.optionSources["provider"])
        val mismatchedPointer = canonicalProviderSourceBody.replace(
            "\"pointer\":\"/providerId\"",
            "\"pointer\":\"/model\""
        )
        val sourceResult = SlockComputersApiClient(
            RecordingComputersApi(runtimeFormOptionSourceBodies = mapOf("provider" to mismatchedPointer))
        ).runtimeFormOptionSource(serverId, "machine-1", expectedRef, providerRef)
        assertEquals(
            "runtime_form_option_source_contract_mismatch",
            assertIs<DataResult.Error>(sourceResult).error.code
        )

        val unsupportedSchema = expectedRef.copy(schemaVersion = "builtin-pi.create.v3")
        assertEquals(
            "runtime_form_ref_unsupported",
            assertIs<DataResult.Error>(
                validClient.runtimeFormDefinition(serverId, "machine-1", unsupportedSchema)
            ).error.code
        )
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
        private val runtimeOptionsBody: String = canonicalRuntimeOptionsBody,
        private val runtimeFormDefinitionBody: String = canonicalRuntimeFormDefinitionBody,
        private val runtimeFormOptionSourceBodies: Map<String, String> =
            mapOf("provider" to canonicalProviderSourceBody, "model" to canonicalModelSourceBody)
    ) : SlockComputersApi {
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

        override suspend fun runtimeFormDefinition(
            serverId: ServerId,
            machineId: String,
            ref: SharedRuntimeFormDefinitionRef
        ): HomeNetworkResponse {
            calls +=
                "GET servers/${serverId.value}/machines/$machineId/runtime-form-definitions/${ref.runtimeId}" +
                    "?schemaVersion=${ref.schemaVersion}"
            return ok(runtimeFormDefinitionBody)
        }

        override suspend fun runtimeFormOptionSource(
            serverId: ServerId,
            machineId: String,
            ref: SharedRuntimeFormDefinitionRef,
            sourceId: String
        ): HomeNetworkResponse {
            calls +=
                "GET servers/${serverId.value}/machines/$machineId/runtime-form-definitions/${ref.runtimeId}" +
                    "/option-sources/$sourceId?schemaVersion=${ref.schemaVersion}"
            return ok(runtimeFormOptionSourceBodies[sourceId] ?: "{}")
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
            """{"context":"new_agent","machineId":"machine-1","options":[{"runtimeId":"codex","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true},{"runtimeId":"builtin","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true,"formDefinitionRef":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v2"}}]}"""

        val canonicalRuntimeFormDefinitionBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v2","dataSchema":{"type":"object","additionalProperties":false,"required":["providerId","apiKey","model"],"properties":{"providerId":{"type":"string","title":"Provider","minLength":1},"apiKey":{"type":"string","title":"API Key","minLength":1,"writeOnly":true},"baseUrl":{"type":"string","title":"Base URL","minLength":1,"format":"uri"},"supportsImageInput":{"type":"boolean","title":"Image input"},"model":{"type":"string","title":"Model","minLength":1},"envVars":{"type":"object","title":"Environment Variables","additionalProperties":{"type":"string"}}}},"uiSchema":{"order":["providerId","apiKey","baseUrl","supportsImageInput","model","envVars"],"layout":{"advanced":["/envVars"]},"visibility":[{"pointer":"/baseUrl","when":{"pointer":"/providerId","in":["openai-compatible","anthropic-compatible"]}},{"pointer":"/supportsImageInput","when":{"pointer":"/providerId","in":["openai-compatible","anthropic-compatible"]}}],"localization":{"providerId":{"label":"Provider","hint":"Choose provider"},"apiKey":{"label":"API Key","placeholder":"sk-..."},"baseUrl":{"label":"Base URL","placeholder":"https://gateway.example.com/v1"},"supportsImageInput":{"label":"Supports image input","hint":"Enable only when this gateway endpoint and model accept images."},"model":{"label":"Model","placeholder":"Model ID"},"envVars":{"label":"Environment Variables","hint":"Injected into runtime"}}},"capabilities":{"providerKinds":["preset","gateway"],"writeOnlyPointers":["/apiKey"],"forbiddenPointers":["/hostUserState"]},"optionSources":{"provider":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v2","sourceId":"provider","kind":"select","pointer":"/providerId"},"model":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v2","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId"}}}"""

        val canonicalProviderSourceBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v2","sourceId":"provider","kind":"select","pointer":"/providerId","options":[{"value":"deepseek","label":"DeepSeek","providerKind":"preset"},{"value":"openai-compatible","label":"OpenAI Compatible","providerKind":"gateway"},{"value":"anthropic-compatible","label":"Anthropic Compatible","providerKind":"gateway"}],"defaultValue":"deepseek"}"""

        val canonicalModelSourceBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v2","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId","optionsByValue":{"deepseek":[{"value":"deepseek/deepseek-v4-pro","label":"DeepSeek V4 Pro"}],"openai-compatible":[],"anthropic-compatible":[]},"defaultValueByValue":{"deepseek":"deepseek/deepseek-v4-pro"},"customValueAllowedByValue":{"deepseek":false,"openai-compatible":true,"anthropic-compatible":true}}"""
    }
}
