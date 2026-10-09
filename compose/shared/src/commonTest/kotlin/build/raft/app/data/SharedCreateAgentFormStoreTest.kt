package build.raft.app.data

import build.raft.app.refreshCreateAgentDialogBuiltInForm
import build.raft.app.runPortableCoroutineTest
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

class SharedCreateAgentFormStoreTest {
    @Test
    fun createRejectionForceRefreshesFormAndRequestsExplicitRetry() = runPortableCoroutineTest {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val recovery = async {
            DataResult.Error(ScreenError("form_definition_ref_required"))
                .recoverRejectedCreateAgentForm(store, "server-1", "machine-1")
        }
        api.definitionResponses.receive().complete(ok(canonicalDefinitionBody))

        val result = assertIs<DataResult.Error>(recovery.await())

        assertEquals(AGENT_CREATE_FORM_REFRESH_REQUIRED, result.error.code)
        assertEquals(1, api.definitionCallCount)
        assertTrue(store.state.value.resolvedForms.isNotEmpty())
    }

    @Test
    fun unrelatedCreateErrorDoesNotRefreshRuntimeForm() = runPortableCoroutineTest {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)

        val result =
            DataResult.Error(ScreenError("http_409"))
                .recoverRejectedCreateAgentForm(store, "server-1", "machine-1")

        assertEquals("http_409", assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, api.definitionCallCount)
    }

    @Test
    fun createFormRecoveryFailsClosedWithoutServerOrMachineIdentity() = runPortableCoroutineTest {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)

        listOf("" to "machine-1", "server-1" to "").forEach { (serverId, machineId) ->
            val result =
                DataResult.Error(ScreenError("form_definition_ref_required"))
                    .recoverRejectedCreateAgentForm(store, serverId, machineId)
            assertEquals(AGENT_CREATE_FORM_REFRESH_FAILED, assertIs<DataResult.Error>(result).error.code)
        }

        assertEquals(0, api.definitionCallCount)
    }

    @Test
    fun createFormRecoveryReportsRefreshFailure() = runPortableCoroutineTest {
        val api =
            SequencedFormComputersApi(
                runtimeOptionsBody = canonicalRuntimeOptionsBody.replace(
                    "\"canSelectInThisContext\":true",
                    "\"canSelectInThisContext\":false"
                )
            )
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)

        val result =
            DataResult.Error(ScreenError("stale_form_schema"))
                .recoverRejectedCreateAgentForm(store, "server-1", "machine-1")

        assertEquals(AGENT_CREATE_FORM_REFRESH_FAILED, assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, api.definitionCallCount)
    }

    @Test
    fun exactFormLoadsAreSingleFlightAndCachedByExactRef() = runPortableCoroutineTest {
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
    fun dialogOpenRefreshesCachedCatalogAndExactFormContract() = runPortableCoroutineTest {
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val initialLoad = async { store.loadSupportedBuiltInForm("server-1", "machine-1") }
        api.definitionResponses.receive().complete(ok(canonicalDefinitionBody))
        val initial = assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(initialLoad.await()).data
        assertEquals(supportedRef, initial.definition.ref)

        val refresh = async {
            refreshCreateAgentDialogBuiltInForm(store, "server-1", "machine-1")
        }
        api.definitionResponses.receive().complete(
            ok(
                canonicalDefinitionBody
                    .replace("Choose provider", "Fresh provider")
            )
        )
        refresh.await()

        val machineKey = SharedCreateAgentMachineFormKey("server-1", "machine-1")
        val exactKey = SharedCreateAgentExactFormKey("server-1", "machine-1", supportedRef)
        assertEquals(2, api.runtimeOptionsCallCount)
        assertEquals(2, api.definitionCallCount)
        assertEquals(supportedRef, store.state.value.runtimeCatalogs.getValue(machineKey).options.single().formDefinitionRef)
        assertEquals(
            "Fresh provider",
            store.state.value.resolvedForms.getValue(exactKey).definition.uiSchema.localization.getValue("providerId").hint
        )
    }

    @Test
    fun staleForcedCompletionCannotReplaceNewerExactFormGeneration() = runPortableCoroutineTest {
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
    fun supportedBuiltInLoaderFailsClosedWithoutExactSelectableRef() = runPortableCoroutineTest {
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
    fun editLoaderAcceptsCurrentManageableBuiltInRuntimeWithoutOpeningCreateAdmission() =
        runPortableCoroutineTest {
            val currentManageableBody =
                canonicalRuntimeOptionsBody
                    .replace("\"current\":false", "\"current\":true")
                    .replace("\"availableForNew\":true", "\"availableForNew\":false")
                    .replace("\"canSelectInThisContext\":true", "\"canSelectInThisContext\":false")
            val api = SequencedFormComputersApi(runtimeOptionsBody = currentManageableBody)
            val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)

            val createResult = store.loadSupportedBuiltInForm("server-1", "machine-1")
            assertEquals(
                "runtime_form_unavailable_runtime_not_selectable",
                assertIs<DataResult.Error>(createResult).error.code
            )

            val editLoad = async {
                store.loadSupportedBuiltInFormForEdit("server-1", "machine-1")
            }
            api.definitionResponses.receive().complete(ok(canonicalDefinitionBody))
            assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(editLoad.await())
            assertEquals(1, api.definitionCallCount)
        }

    @Test
    fun resolvedSourcesBuildPresetAndGatewayWithoutClientCatalogs() = runPortableCoroutineTest {
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
                envVars = null,
                supportsImageInput = true
            )
        )
        assertEquals(
            RuntimeProviderConfig.Gateway("openai-compatible", "https://gateway.example.com/v1", "secret-gateway", true),
            gateway.provider
        )
        assertEquals(RuntimeModelConfig.Custom("vendor/model-v2"), gateway.model)
        assertEquals(null, form.buildBuiltInRuntimeConfig("deepseek", "secret", "", "unknown", null))
        assertEquals(null, form.buildBuiltInRuntimeConfig("openai-compatible", "secret", "", "model", null))
    }

    @Test
    fun silentTokenRefreshKeepsTheFormSessionAndANewLoginStartsANewOne() = runPortableCoroutineTest {
        val api = SequencedFormComputersApi()
        val generation = SharedAuthLoginGeneration()
        var session = formSession(user = "account-1", accessToken = "access-1")
        val store = SharedCreateAgentFormStore(
            SlockComputersApiClient(api),
            this,
            ownerProvider = { sharedCreateAgentFormOwner(session, "https://api.example.test/", generation.current()) }
        )
        val machineKey = SharedCreateAgentMachineFormKey("server-1", "machine-1")
        assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(store.loadRuntimeCatalog("server-1", "machine-1"))
        val owner = store.state.value.owner

        // The auth store rotates both tokens and announces it, as on a device after `auth_refresh_successor_commit`.
        session = session.copy(accessToken = "access-2", refreshToken = "refresh-2")
        generation.onAuthSessionEvent(AuthSessionEvent.TokensRotated(generation = 2))
        store.reconcileOwner()
        generation.onAuthSessionEvent(AuthSessionEvent.BindingMetadataChanged(generation = 3))
        store.reconcileOwner()

        assertEquals(owner, store.state.value.owner, "a refresh is the same session")
        assertTrue(machineKey in store.state.value.runtimeCatalogs, "the runtime list the form is showing survives")
        assertIs<DataResult.Content<SharedCreateAgentRuntimeCatalog>>(store.loadRuntimeCatalog("server-1", "machine-1"))
        assertEquals(1, api.runtimeOptionsCallCount, "and is not fetched again")

        generation.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 4))
        store.reconcileOwner()
        assertTrue(store.state.value.owner != owner, "logging in again is a new session")
        assertTrue(store.state.value.runtimeCatalogs.isEmpty())
    }

    @Test
    fun formOwnerIgnoresTokensAndFollowsAccountAndEnvironment() {
        val session = formSession(user = "account-1", accessToken = "access-1")
        val owner = sharedCreateAgentFormOwner(session, "https://api.example.test/", "1")
        assertEquals(SharedCreateAgentFormOwner("https://api.example.test", "account-1", "1"), owner)
        assertEquals(owner, sharedCreateAgentFormOwner(session.copy(accessToken = "access-2"), "https://api.example.test", "1"))
        assertTrue(owner != sharedCreateAgentFormOwner(formSession(user = "account-2", accessToken = "access-1"), "https://api.example.test", "1"))
        assertTrue(owner != sharedCreateAgentFormOwner(session, "https://other.example.test", "1"))
        assertEquals(null, sharedCreateAgentFormOwner(formSession(user = " ", accessToken = "access-1"), "https://api.example.test", "1"))
        assertEquals(null, sharedCreateAgentFormOwner(session, " ", "1"))

        val generation = SharedAuthLoginGeneration()
        val start = generation.current()
        generation.onAuthSessionEvent(AuthSessionEvent.TokensRotated(generation = 1))
        generation.onAuthSessionEvent(AuthSessionEvent.BindingMetadataChanged(generation = 2))
        assertEquals(start, generation.current())
        generation.onAuthSessionEvent(AuthSessionEvent.Cleared(generation = 3, reason = AuthSessionClearReason.Logout))
        val afterLogout = generation.current()
        assertTrue(afterLogout != start)
        generation.onAuthSessionEvent(AuthSessionEvent.Authenticated(generation = 4))
        assertTrue(generation.current() != afterLogout)
    }

    private fun formSession(user: String, accessToken: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = user, email = "user@raft.build"),
            accessToken = accessToken,
            refreshToken = "refresh-1",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
        )

    @Test
    fun writeOnlySecretClearsOnEveryContractIdentityChange() {
        val base = SharedBuiltInWriteOnlyIdentity(
            environmentId = "https://api.example.test",
            accountId = "account-1",
            sessionGeneration = "generation-1",
            serverId = "server-1",
            machineId = "machine-1",
            runtimeId = "builtin",
            ref = supportedRef,
            providerId = "deepseek",
            baseUrl = ""
        )
        assertEquals(false, shouldClearBuiltInWriteOnlySecret(null, base))
        assertEquals(false, shouldClearBuiltInWriteOnlySecret(base, base))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(environmentId = "https://other.example.test")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(accountId = "account-2")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(sessionGeneration = "generation-2")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(serverId = "server-2")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(machineId = "machine-2")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(runtimeId = "codex")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(ref = supportedRef.copy(schemaVersion = "v2"))))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(providerId = "openai-compatible")))
        assertEquals(true, shouldClearBuiltInWriteOnlySecret(base, base.copy(baseUrl = "https://gateway.example.com/v1")))
    }

    @Test
    fun storeStateAndDiagnosticsNeverContainWriteOnlySecret() = runPortableCoroutineTest {
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
    fun resolvedFormRejectsProviderModelPolicyDrift() = runPortableCoroutineTest {
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

    @Test
    fun accountEnvironmentGenerationChangeClearsCacheAndRejectsStaleCompletion() = runPortableCoroutineTest {
        var owner = SharedCreateAgentFormOwner("https://api-a.example.test", "account-a", "session-a")
        val api = SequencedFormComputersApi()
        val store = SharedCreateAgentFormStore(
            computersApiClient = SlockComputersApiClient(api),
            scope = this,
            ownerProvider = { owner }
        )
        val stale = async { store.loadExactForm("server-1", "machine-1", supportedRef, force = true) }
        val staleResponse = api.definitionResponses.receive()

        owner = SharedCreateAgentFormOwner("https://api-b.example.test", "account-b", "session-b")
        val current = async { store.loadExactForm("server-1", "machine-1", supportedRef, force = true) }
        val currentResponse = api.definitionResponses.receive()
        currentResponse.complete(ok(canonicalDefinitionBody.replace("Choose provider", "Current owner")))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(current.await())
        staleResponse.complete(ok(canonicalDefinitionBody.replace("Choose provider", "Stale owner")))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(stale.await())

        val key = SharedCreateAgentExactFormKey("server-1", "machine-1", supportedRef)
        assertEquals(owner, store.state.value.owner)
        assertEquals("Current owner", store.state.value.resolvedForms.getValue(key).definition.uiSchema.localization.getValue("providerId").hint)
    }

    private class SequencedFormComputersApi(
        var runtimeOptionsBody: String = canonicalRuntimeOptionsBody,
        var sourceBodies: Map<String, String> =
            mapOf("provider" to canonicalProviderSourceBody, "model" to canonicalModelSourceBody)
    ) : SlockComputersApi {
        val definitionResponses = Channel<CompletableDeferred<HomeNetworkResponse>>(Channel.UNLIMITED)
        var runtimeOptionsCallCount = 0
        var definitionCallCount = 0
        val sourceCalls = mutableListOf<String>()

        override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse {
            runtimeOptionsCallCount += 1
            return ok(runtimeOptionsBody)
        }

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
        val supportedRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v3")
        val canonicalRuntimeOptionsBody =
            """{"context":"new_agent","machineId":"machine-1","options":[{"runtimeId":"builtin","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true,"formDefinitionRef":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3"}}]}"""
        val canonicalDefinitionBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","dataSchema":{"type":"object","additionalProperties":false,"required":["providerId","apiKey","model"],"properties":{"providerId":{"type":"string","title":"Provider","minLength":1},"apiKey":{"type":"string","title":"API Key","minLength":1,"writeOnly":true},"baseUrl":{"type":"string","title":"Base URL","minLength":1,"format":"uri"},"supportsImageInput":{"type":"boolean","title":"Image input"},"loadLocalPlugins":{"type":"boolean","title":"Load local Pi plugins"},"model":{"type":"string","title":"Model","minLength":1},"envVars":{"type":"object","title":"Environment Variables","additionalProperties":{"type":"string"}}}},"uiSchema":{"order":["providerId","apiKey","baseUrl","supportsImageInput","model","loadLocalPlugins","envVars"],"layout":{"advanced":["/loadLocalPlugins","/envVars"]},"visibility":[{"pointer":"/baseUrl","when":{"pointer":"/providerId","in":["openai-compatible","anthropic-compatible"]}},{"pointer":"/supportsImageInput","when":{"pointer":"/providerId","in":["openai-compatible","anthropic-compatible"]}}],"localization":{"providerId":{"label":"Provider","hint":"Choose provider"},"apiKey":{"label":"API Key","placeholder":"sk-..."},"baseUrl":{"label":"Base URL","placeholder":"https://gateway.example.com/v1"},"supportsImageInput":{"label":"Supports image input","hint":"Enable only when this gateway endpoint and model accept images."},"model":{"label":"Model","placeholder":"Model ID"},"loadLocalPlugins":{"label":"Load local Pi plugins","hint":"Load enabled Pi extensions installed on the computer running this agent. Applying changes restarts a running agent."},"envVars":{"label":"Environment Variables","hint":"Injected into runtime"}}},"capabilities":{"providerKinds":["preset","gateway"],"writeOnlyPointers":["/apiKey"],"forbiddenPointers":["/hostUserState"]},"optionSources":{"provider":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","sourceId":"provider","kind":"select","pointer":"/providerId"},"model":{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId"}}}"""
        val canonicalProviderSourceBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","sourceId":"provider","kind":"select","pointer":"/providerId","options":[{"value":"deepseek","label":"DeepSeek","providerKind":"preset"},{"value":"openai-compatible","label":"OpenAI Compatible","providerKind":"gateway"},{"value":"anthropic-compatible","label":"Anthropic Compatible","providerKind":"gateway"}],"defaultValue":"deepseek"}"""
        val canonicalModelSourceBody =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId","optionsByValue":{"deepseek":[{"value":"deepseek/deepseek-v4-pro","label":"DeepSeek V4 Pro"}],"openai-compatible":[],"anthropic-compatible":[]},"defaultValueByValue":{"deepseek":"deepseek/deepseek-v4-pro"},"customValueAllowedByValue":{"deepseek":false,"openai-compatible":true,"anthropic-compatible":true}}"""

        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)
    }
}
