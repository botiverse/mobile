package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.RuntimeHostUserState
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

class SharedCreateAgentFormStoreTest {
    @Test
    fun exactFormLoadsAreSingleFlightAndCachedByExactRef() = runBlocking {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val first = async { store.loadExactForm("server-1", "machine-1", supportedRef) }
        val firstResponse = api.definitionResponses.receive()
        val joined = async { store.loadExactForm("server-1", "machine-1", supportedRef) }

        assertTrue(api.definitionResponses.tryReceive().isFailure)
        firstResponse.complete(ok(canonicalDefinitionBody))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(first.await())
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(joined.await())

        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(
            store.loadExactForm("server-1", "machine-1", supportedRef)
        )
        assertEquals(1, api.definitionCallCount)
        assertEquals(listOf("provider", "model"), api.sourceCalls)
    }

    @Test
    fun staleForcedCompletionCannotReplaceNewerExactFormGeneration() = runBlocking {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val older = async { store.loadExactForm("server-1", "machine-1", supportedRef, force = true) }
        val olderResponse = api.definitionResponses.receive()
        val newer = async { store.loadExactForm("server-1", "machine-1", supportedRef, force = true) }
        val newerResponse = api.definitionResponses.receive()

        newerResponse.complete(ok(canonicalDefinitionBody.replace("Choose provider", "Newer provider")))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(newer.await())
        olderResponse.complete(ok(canonicalDefinitionBody.replace("Choose provider", "Stale provider")))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(older.await())

        val key = SharedCreateAgentExactFormKey("server-1", "machine-1", supportedRef)
        val published = requireNotNull(store.state.value.resolvedForms[key])
        assertEquals("Newer provider", published.definition.uiSchema.localization.getValue("providerId").hint)
    }

    @Test
    fun supportedBuiltInLoaderFailsClosedWithoutExactSelectableRef() = runBlocking {
        val unavailableApi = SequencedFormComputersApi(
            runtimeOptionsBody = canonicalRuntimeOptionsBody.replace(
                "\"canSelectInThisContext\":true",
                "\"canSelectInThisContext\":false"
            ).replace(
                "\"availableForNew\":true",
                "\"availableForNew\":false"
            )
        )
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(unavailableApi), this)

        val result = store.loadSupportedBuiltInForm("server-1", "machine-1")

        assertEquals("runtime_form_unavailable_runtime_not_selectable", assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, unavailableApi.definitionCallCount)
    }

    @Test
    fun resolvedSourcesBuildPresetAndGatewayWithoutClientCatalogs() = runBlocking {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val load = async { store.loadExactForm("server-1", "machine-1", supportedRef) }
        api.definitionResponses.receive().complete(ok(canonicalDefinitionBody))
        val form = assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(load.await()).data

        val preset = requireNotNull(
            form.buildBuiltInRuntimeConfig(
                providerId = "deepseek",
                apiKey = "secret-preset",
                baseUrl = "",
                model = "deepseek/deepseek-v4-pro",
                envVars = mapOf("REGION" to "cn")
            )
        )
        assertEquals(RuntimeProviderConfig.Preset("deepseek", "secret-preset"), preset.provider)
        assertEquals(RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro"), preset.model)
        assertEquals(RuntimeHostUserState.Forbidden, preset.hostUserState)

        val gateway = requireNotNull(
            form.buildBuiltInRuntimeConfig(
                providerId = "openai-compatible",
                apiKey = "secret-gateway",
                baseUrl = "https://gateway.example.com/v1",
                model = "vendor/model-v2",
                envVars = null
            )
        )
        assertEquals(
            RuntimeProviderConfig.Gateway("openai-compatible", "https://gateway.example.com/v1", "secret-gateway"),
            gateway.provider
        )
        assertEquals(RuntimeModelConfig.Custom("vendor/model-v2"), gateway.model)
        assertEquals(null, form.buildBuiltInRuntimeConfig("deepseek", "secret", "", "unknown", null))
        assertEquals(null, form.buildBuiltInRuntimeConfig("openai-compatible", "secret", "", "model", null))
    }

    @Test
    fun writeOnlySecretClearsOnEveryContractIdentityChange() {
        val base = SharedBuiltInWriteOnlyIdentity(
            machineId = "machine-1",
            runtimeId = "builtin",
            ref = supportedRef,
            providerId = "deepseek",
            baseUrl = ""
        )
        assertEquals(false, shouldClearBuiltInWriteOnlySecret(null, base))
        assertEquals(false, shouldClearBuiltInWriteOnlySecret(base, base))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(machineId = "machine-2")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(runtimeId = "codex")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(ref = supportedRef.copy(schemaVersion = "v2"))))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(providerId = "openai-compatible")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(baseUrl = "https://gateway.example.com/v1")))
    }

    @Test
    fun storeStateAndDiagnosticsNeverContainWriteOnlySecret() = runBlocking {
        val logs = mutableListOf<String>()
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this, logs::add)
        val load = async { store.loadExactForm("server-1", "machine-1", supportedRef) }
        api.definitionResponses.receive().complete(ok(canonicalDefinitionBody))
        val form = assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(load.await()).data
        val sentinel = "sentinel-write-only-secret"

        requireNotNull(
            form.buildBuiltInRuntimeConfig(
                providerId = "deepseek",
                apiKey = sentinel,
                baseUrl = "",
                model = "deepseek/deepseek-v4-pro",
                envVars = null
            )
        )

        assertTrue(sentinel !in store.state.value.toString())
        assertTrue(logs.none { sentinel in it })
    }

    @Test
    fun resolvedFormRejectsProviderModelPolicyDrift() = runBlocking {
        val api = SequencedFormComputersApi(
            sourceBodies = mapOf(
                "provider" to canonicalProviderSourceBody,
                "model" to canonicalModelSourceBody.replace(
                    "\"openai-compatible\":true",
                    "\"openai-compatible\":false"
                )
            )
        )
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val load = async { store.loadExactForm("server-1", "machine-1", supportedRef) }
        api.definitionResponses.receive().complete(ok(canonicalDefinitionBody))

        assertEquals(
            "runtime_form_sources_contract_mismatch",
            assertIs<DataResult.Error>(load.await()).error.code
        )
        assertTrue(store.state.value.resolvedForms.isEmpty())
    }

    private class SequencedFormComputersApi(
        private val runtimeOptionsBody: String = canonicalRuntimeOptionsBody,
        private val sourceBodies: Map<String, String> =
            mapOf("provider" to canonicalProviderSourceBody, "model" to canonicalModelSourceBody)
    ) : SlockComputersApi {
        val definitionResponses = Channel<CompletableDeferred<HomeNetworkResponse>>(Channel.UNLIMITED)
        var definitionCallCount = 0
        val sourceCalls = mutableListOf<String>()

        override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse =
            ok(runtimeOptionsBody)

        override suspend fun runtimeFormDefinition(
            serverId: ServerId,
            machineId: String,
            ref: SharedRuntimeFormDefinitionRef
        ): HomeNetworkResponse {
            definitionCallCount += 1
            return CompletableDeferred<HomeNetworkResponse>().also { definitionResponses.send(it) }.await()
        }

        override suspend fun runtimeFormOptionSource(
            serverId: ServerId,
            machineId: String,
            ref: SharedRuntimeFormDefinitionRef,
            sourceId: String
        ): HomeNetworkResponse {
            sourceCalls += sourceId
            return ok(sourceBodies.getValue(sourceId))
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private companion object {
        val supportedRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v1")
        val canonicalRuntimeOptionsBody =
            """{"context":"new_agent","machineId":"machine-1","options":[{"runtimeId":"builtin","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true,"formDefinitionRef":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v1"}}]}"""
        val canonicalDefinitionBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v1","dataSchema":{"type":"object","additionalProperties":false,"required":["providerId","apiKey","model"],"properties":{"providerId":{"type":"string","title":"Provider","minLength":1},"apiKey":{"type":"string","title":"API Key","minLength":1,"writeOnly":true},"baseUrl":{"type":"string","title":"Base URL","minLength":1,"format":"uri"},"model":{"type":"string","title":"Model","minLength":1},"envVars":{"type":"object","title":"Environment Variables","additionalProperties":{"type":"string"}}}},"uiSchema":{"order":["providerId","apiKey","baseUrl","model","envVars"],"layout":{"advanced":["/envVars"]},"visibility":[{"pointer":"/baseUrl","when":{"pointer":"/providerId","in":["openai-compatible"]}}],"localization":{"providerId":{"label":"Provider","hint":"Choose provider"},"apiKey":{"label":"API Key","placeholder":"sk-..."},"baseUrl":{"label":"Base URL","placeholder":"https://gateway.example.com/v1"},"model":{"label":"Model","placeholder":"Model ID"},"envVars":{"label":"Environment Variables","hint":"Injected into runtime"}}},"capabilities":{"providerKinds":["preset","gateway"],"writeOnlyPointers":["/apiKey"],"forbiddenPointers":["/hostUserState"]},"optionSources":{"provider":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v1","sourceId":"provider","kind":"select","pointer":"/providerId"},"model":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v1","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId"}}}"""
        val canonicalProviderSourceBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v1","sourceId":"provider","kind":"select","pointer":"/providerId","options":[{"value":"deepseek","label":"DeepSeek","providerKind":"preset"},{"value":"openai-compatible","label":"OpenAI Compatible","providerKind":"gateway"}],"defaultValue":"deepseek"}"""
        val canonicalModelSourceBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v1","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId","optionsByValue":{"deepseek":[{"value":"deepseek/deepseek-v4-pro","label":"DeepSeek V4 Pro"}],"openai-compatible":[]},"defaultValueByValue":{"deepseek":"deepseek/deepseek-v4-pro"},"customValueAllowedByValue":{"deepseek":false,"openai-compatible":true}}"""

        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)
    }
}
