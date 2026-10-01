package build.raft.app

import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_FAILED
import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_REQUIRED
import build.raft.app.data.CreateAgentBody
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAgentCreateFormOption
import build.raft.app.data.SharedAgentCreateFormOptionSource
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import build.raft.app.data.buildCreateAgentBody
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedCreateAgentRecoveryTest {
    // ── Wire shape: absent optional fields must be omitted, never explicit null ──

    @Test
    fun createBodyOmitsAbsentOptionalFieldsLikeWeb() {
        val body =
            buildCreateAgentBody(
                CreateAgentBody(
                    name = "Noel",
                    machineId = "machine-1",
                    runtimeConfig =
                        RuntimeConfig(
                            runtime = "codex",
                            model = RuntimeModelConfig.Preset("gpt-5")
                        )
                )
            )

        assertFalse("formDefinitionRef" in body, body)
        assertFalse("\"external\"" in body, body)
        assertFalse("\"description\"" in body, body)
        assertFalse("null" in body, body)
        assertTrue("\"name\":\"Noel\"" in body, body)
        assertTrue("\"machineId\":\"machine-1\"" in body, body)
        assertTrue("\"runtime\":\"codex\"" in body, body)
    }

    @Test
    fun externalCreateBodyOmitsMachineRuntimeAndFormNulls() {
        val body =
            buildCreateAgentBody(
                CreateAgentBody(
                    name = "External Agent",
                    external = true
                )
            )

        assertTrue("\"external\":true" in body, body)
        assertFalse("machineId" in body, body)
        assertFalse("runtimeConfig" in body, body)
        assertFalse("formDefinitionRef" in body, body)
    }

    @Test
    fun createBodyKeepsPresentOptionalFields() {
        val body =
            buildCreateAgentBody(
                CreateAgentBody(
                    name = "Noel",
                    machineId = "machine-1",
                    formDefinitionRef = supportedRef,
                    external = false
                )
            )

        assertTrue("\"formDefinitionRef\"" in body, body)
        assertTrue("\"schemaVersion\"" in body, body)
        assertTrue("\"external\":false" in body, body)
    }

    // ── Revalidation against the refreshed contract ──

    @Test
    fun stillValidPresetSubmitRebindsToRefreshedRef() {
        val revalidated =
            builtInSubmit().revalidatedAgainstRefreshedSources(
                refreshedRef = supportedRef,
                providerSource = providerSource(),
                modelSource = modelSource()
            )

        assertEquals(supportedRef, revalidated?.formDefinitionRef)
        assertEquals(builtInSubmit().runtimeConfig, revalidated?.runtimeConfig)
    }

    @Test
    fun removedProviderOrModelFallsBackToExplicitConfirm() {
        assertNull(
            builtInSubmit().revalidatedAgainstRefreshedSources(
                refreshedRef = supportedRef,
                providerSource =
                    providerSource(options = listOf(option("other-provider", "preset"))),
                modelSource = modelSource()
            )
        )
        assertNull(
            builtInSubmit().revalidatedAgainstRefreshedSources(
                refreshedRef = supportedRef,
                providerSource = providerSource(),
                modelSource =
                    modelSource(models = mapOf("deepseek" to listOf(formOption("other-model"))))
            )
        )
    }

    @Test
    fun nonBuiltInExternalOrKindMismatchNeverAutoResubmits() {
        assertNull(
            builtInSubmit(external = true).revalidatedAgainstRefreshedSources(
                supportedRef, providerSource(), modelSource()
            )
        )
        assertNull(
            builtInSubmit(runtime = "codex").revalidatedAgainstRefreshedSources(
                supportedRef, providerSource(), modelSource()
            )
        )
        assertNull(
            builtInSubmit().revalidatedAgainstRefreshedSources(
                refreshedRef = supportedRef,
                providerSource = providerSource(options = listOf(option("deepseek", "gateway"))),
                modelSource = modelSource()
            )
        )
    }

    // ── One-tap retry pipeline ──

    @Test
    fun formRejectionWithStillValidInputsRetriesExactlyOnceAndSucceeds() = runPortableCoroutineTest {
        var retried = 0
        var retriedRef: SharedRuntimeFormDefinitionRef? = null

        val result =
            formRejection()
                .recoverRejectedCreateAgentFormOrRetryCore(
                    refreshForm = { _, _ -> DataResult.Content(resolvedFormStub()) },
                    revalidate = { builtInSubmit().copy(formDefinitionRef = supportedRef) },
                    serverId = "server-1",
                    machineId = "machine-1",
                    retry = { submit ->
                        retried += 1
                        retriedRef = submit.formDefinitionRef
                        DataResult.Content(Unit)
                    }
                )

        assertIs<DataResult.Content<Unit>>(result)
        assertEquals(1, retried)
        assertEquals(supportedRef, retriedRef)
    }

    @Test
    fun secondFormRejectionStopsAtExplicitConfirmWithoutMoreRetries() = runPortableCoroutineTest {
        var retried = 0

        val result =
            formRejection()
                .recoverRejectedCreateAgentFormOrRetryCore(
                    refreshForm = { _, _ -> DataResult.Content(resolvedFormStub()) },
                    revalidate = { builtInSubmit().copy(formDefinitionRef = supportedRef) },
                    serverId = "server-1",
                    machineId = "machine-1",
                    retry = {
                        retried += 1
                        formRejection()
                    }
                )

        assertEquals(
            AGENT_CREATE_FORM_REFRESH_REQUIRED,
            assertIs<DataResult.Error>(result).error.code
        )
        assertEquals(1, retried)
    }

    @Test
    fun changedContractOrFailedRefreshNeverRetries() = runPortableCoroutineTest {
        var retried = 0

        val changed =
            formRejection()
                .recoverRejectedCreateAgentFormOrRetryCore(
                    refreshForm = { _, _ -> DataResult.Content(resolvedFormStub()) },
                    revalidate = { null },
                    serverId = "server-1",
                    machineId = "machine-1",
                    retry = { retried += 1; DataResult.Content(Unit) }
                )
        val refreshFailed =
            formRejection()
                .recoverRejectedCreateAgentFormOrRetryCore(
                    refreshForm = { _, _ -> DataResult.Error(ScreenError("runtime_form_load_failed")) },
                    revalidate = { builtInSubmit() },
                    serverId = "server-1",
                    machineId = "machine-1",
                    retry = { retried += 1; DataResult.Content(Unit) }
                )
        val missingIdentity =
            formRejection()
                .recoverRejectedCreateAgentFormOrRetryCore(
                    refreshForm = { _, _ -> DataResult.Content(resolvedFormStub()) },
                    revalidate = { builtInSubmit() },
                    serverId = " ",
                    machineId = "machine-1",
                    retry = { retried += 1; DataResult.Content(Unit) }
                )

        assertEquals(AGENT_CREATE_FORM_REFRESH_REQUIRED, assertIs<DataResult.Error>(changed).error.code)
        assertEquals(AGENT_CREATE_FORM_REFRESH_FAILED, assertIs<DataResult.Error>(refreshFailed).error.code)
        assertEquals(AGENT_CREATE_FORM_REFRESH_FAILED, assertIs<DataResult.Error>(missingIdentity).error.code)
        assertEquals(0, retried)
    }

    @Test
    fun unrelatedErrorPassesThroughUntouched() = runPortableCoroutineTest {
        var retried = 0

        val result =
            DataResult.Error(ScreenError("http_409"))
                .recoverRejectedCreateAgentFormOrRetryCore(
                    refreshForm = { _, _ -> DataResult.Content(resolvedFormStub()) },
                    revalidate = { builtInSubmit() },
                    serverId = "server-1",
                    machineId = "machine-1",
                    retry = { retried += 1; DataResult.Content(Unit) }
                )

        assertEquals("http_409", assertIs<DataResult.Error>(result).error.code)
        assertEquals(0, retried)
    }

    // ── Fixtures ──

    private val supportedRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v3")

    private fun formRejection(): DataResult<Unit> =
        DataResult.Error(ScreenError("form_definition_ref_required", retryable = true))

    private fun builtInSubmit(
        external: Boolean = false,
        runtime: String = "builtin"
    ): SharedCreateAgentDialogSubmit =
        SharedCreateAgentDialogSubmit(
            name = "Noel",
            description = null,
            machineId = "machine-1",
            runtimeConfig =
                RuntimeConfig(
                    runtime = runtime,
                    provider = RuntimeProviderConfig.Preset("deepseek", "write-only-key"),
                    model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro")
                ),
            formDefinitionRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v0"),
            external = external
        )

    private fun option(value: String, kind: String): SharedAgentCreateFormOption =
        SharedAgentCreateFormOption(value = value, label = value, providerKind = kind)

    private fun formOption(value: String): SharedAgentCreateFormOption =
        SharedAgentCreateFormOption(value = value, label = value, providerKind = null)

    private fun providerSource(
        options: List<SharedAgentCreateFormOption> = listOf(option("deepseek", "preset"))
    ): SharedAgentCreateFormOptionSource.Select =
        SharedAgentCreateFormOptionSource.Select(
            ref = supportedRef,
            sourceId = "provider",
            pointer = "/provider",
            options = options,
            defaultValue = options.firstOrNull()?.value.orEmpty()
        )

    private fun modelSource(
        models: Map<String, List<SharedAgentCreateFormOption>> =
            mapOf("deepseek" to listOf(formOption("deepseek/deepseek-v4-pro")))
    ): SharedAgentCreateFormOptionSource.DependentSelect =
        SharedAgentCreateFormOptionSource.DependentSelect(
            ref = supportedRef,
            sourceId = "model",
            pointer = "/model",
            dependsOn = "provider",
            optionsByValue = models,
            defaultValueByValue = models.mapValues { (_, options) -> options.firstOrNull()?.value.orEmpty() },
            customValueAllowedByValue = models.mapValues { false }
        )

    private fun resolvedFormStub(): build.raft.app.data.SharedResolvedAgentCreateForm =
        build.raft.app.data.SharedResolvedAgentCreateForm(
            definition =
                build.raft.app.data.SharedAgentCreateFormDefinition(
                    ref = supportedRef,
                    dataSchema =
                        build.raft.app.data.SharedAgentCreateDataSchema(
                            required = emptySet(),
                            properties = emptyMap()
                        ),
                    uiSchema =
                        build.raft.app.data.SharedAgentCreateUiSchema(
                            order = emptyList(),
                            advancedPointers = emptySet(),
                            visibility = emptyList(),
                            localization = emptyMap()
                        ),
                    capabilities =
                        build.raft.app.data.SharedAgentCreateFormCapabilities(
                            providerKinds = setOf("preset"),
                            writeOnlyPointers = emptySet(),
                            forbiddenPointers = emptySet()
                        ),
                    optionSources = emptyMap()
                ),
            optionSources = mapOf("provider" to providerSource(), "model" to modelSource())
        )
}
