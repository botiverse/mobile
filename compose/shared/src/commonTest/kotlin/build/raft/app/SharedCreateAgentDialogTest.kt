package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.model.RUNTIME_PROVIDER_MODE_CUSTOM
import build.raft.app.model.RUNTIMES
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.supportsRuntimeCustomModelName
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedCreateAgentDialogTest {
    @Test
    fun externalMembersCreateModeSkipsComputerPicker() {
        assertTrue(MembersCreateAgentMode.External.requiresComputerSelection().not())
        assertTrue(MembersCreateAgentMode.Managed.requiresComputerSelection())
    }

    @Test
    fun runtimeDropdownUsesSupportedRuntimes() {
        val items = createAgentRuntimeDropdownItems()

        assertTrue(items.isNotEmpty())
        assertEquals(
            listOf("claude", "codex", "grok", "builtin", "antigravity", "kimi-sdk", "cursor", "opencode", "pi"),
            items.map { it.id }
        )
        assertFalse(items.any { it.custom })
    }

    @Test
    fun runtimeDropdownUsesSelectedMachineRuntimesWhenAvailable() {
        val items =
            createAgentRuntimeDropdownItems(
                listOf("codex", "claude", "custom-runtime", "codex", " ")
            )

        assertEquals(
            listOf("claude", "codex", "grok", "builtin", "antigravity", "kimi-sdk", "cursor", "opencode", "pi"),
            items.map { it.id }
        )
        assertEquals("Claude Code", items.first { it.id == "claude" }.label)
        assertEquals("Codex CLI", items.first { it.id == "codex" }.label)
        assertEquals("Pi (not installed)", items.first { it.id == "pi" }.label)
        assertTrue(items.first { it.id == "claude" }.enabled)
        assertTrue(items.first { it.id == "codex" }.enabled)
        assertFalse(items.first { it.id == "pi" }.enabled)
        assertFalse(items.any { it.custom })
    }

    @Test
    fun runtimeDropdownRetainsOnlyTheCurrentDeprecatedRuntimeWhileEditing() {
        val createIds = createAgentRuntimeDropdownItems().map { it.id }
        assertFalse("copilot" in createIds)
        assertFalse("kimi" in createIds)
        assertFalse("gemini" in createIds)

        val editingKimiIds = createAgentRuntimeDropdownItems(currentRuntimeId = "kimi").map { it.id }
        assertTrue("kimi" in editingKimiIds)
        assertFalse("gemini" in editingKimiIds)
    }

    @Test
    fun copilotIsHiddenForCreationAndSwitchingButRetainedForExistingAgent() {
        val installed = listOf("codex", "copilot")
        assertFalse(createAgentRuntimeDropdownItems(installed).any { it.id == "copilot" })
        assertFalse(createAgentRuntimeDropdownItems(installed, currentRuntimeId = "codex").any { it.id == "copilot" })
        val existing = createAgentRuntimeDropdownItems(installed, currentRuntimeId = "copilot")
        assertTrue(existing.single { it.id == "copilot" }.enabled)
        assertTrue(createAgentRuntimeDropdownItems(currentRuntimeId = "copilot").any { it.id == "copilot" })
    }

    @Test
    fun runtimeDropdownCanDisableStaticFallbackForMachineWithNoRuntimeData() {
        val items =
            createAgentRuntimeDropdownItems(
                machineRuntimeIds = listOf(" ", ""),
                allowStaticFallback = false
            )

        assertTrue(items.isEmpty())
    }

    @Test
    fun builtInRuntimeRequiresResolvedExactForm() {
        assertTrue(
            canEnableBuiltInRuntime(
                hasFormOverride = true,
                hasSupportedExactRef = false,
                hasResolvedExactForm = false
            )
        )
        assertFalse(
            canEnableBuiltInRuntime(
                hasFormOverride = false,
                hasSupportedExactRef = false,
                hasResolvedExactForm = false
            ),
            "selectable runtime row with null ref must stay disabled"
        )
        assertFalse(
            canEnableBuiltInRuntime(
                hasFormOverride = false,
                hasSupportedExactRef = false,
                hasResolvedExactForm = false
            ),
            "selectable runtime row with unsupported ref must stay disabled"
        )
        assertFalse(
            canEnableBuiltInRuntime(
                hasFormOverride = false,
                hasSupportedExactRef = true,
                hasResolvedExactForm = false
            ),
            "supported ref with failed definition or source resolution must stay disabled"
        )
        assertTrue(
            canEnableBuiltInRuntime(
                hasFormOverride = false,
                hasSupportedExactRef = true,
                hasResolvedExactForm = true
            )
        )
    }

    @Test
    fun builtInSubmitWaitsForDialogOpenContractRefresh() {
        assertFalse(
            isCreateAgentRuntimeContractReady(
                runtime = "builtin",
                builtInFormRefreshAdmitted = false,
                builtInFormLoading = true,
                hasBuiltInRuntimeConfig = true
            ),
            "a cached contract must not remain submittable while the dialog refreshes it"
        )
        assertFalse(
            isCreateAgentRuntimeContractReady(
                runtime = "builtin",
                builtInFormRefreshAdmitted = false,
                builtInFormLoading = false,
                hasBuiltInRuntimeConfig = true
            ),
            "a failed open refresh must not re-admit a cached contract"
        )
        assertFalse(
            isCreateAgentRuntimeContractReady(
                runtime = "builtin",
                builtInFormRefreshAdmitted = true,
                builtInFormLoading = false,
                hasBuiltInRuntimeConfig = false
            )
        )
        assertTrue(
            isCreateAgentRuntimeContractReady(
                runtime = "builtin",
                builtInFormRefreshAdmitted = true,
                builtInFormLoading = false,
                hasBuiltInRuntimeConfig = true
            )
        )
        assertTrue(
            isCreateAgentRuntimeContractReady(
                runtime = "codex",
                builtInFormRefreshAdmitted = false,
                builtInFormLoading = true,
                hasBuiltInRuntimeConfig = false
            ),
            "refreshing the Built-in contract must not block unrelated runtimes"
        )
    }

    @Test
    fun failedDialogRefreshKeepsCachedFormBlockedAndOffersRetry() {
        val initial =
            createAgentDialogFormRefreshInitialState(
                shouldRefresh = true,
                hasFormOverride = false
            )
        assertTrue(initial.loading)
        assertFalse(initial.admitted)
        assertFalse(initial.failureVisible)
        assertFalse(initial.retryAvailable)

        val failed =
            createAgentDialogFormRefreshCompleted(
                DataResult.Error(ScreenError("runtime_form_load_failed", retryable = true))
            )
        assertFalse(failed.loading)
        assertFalse(failed.admitted)
        assertTrue(failed.failureVisible)
        assertTrue(failed.retryAvailable)
        assertFalse(
            isCreateAgentRuntimeContractReady(
                runtime = "builtin",
                builtInFormRefreshAdmitted = failed.admitted,
                builtInFormLoading = failed.loading,
                hasBuiltInRuntimeConfig = true
            ),
            "a stale cached config must remain blocked while the refresh error offers Retry"
        )
    }

    @Test
    fun successfulRetryClearsFailureAndReceivesFreshnessAdmission() {
        val retrying = createAgentDialogFormRefreshStarted()
        assertTrue(retrying.loading)
        assertFalse(retrying.admitted)
        assertFalse(retrying.failureVisible)
        assertFalse(retrying.retryAvailable)

        val succeeded = createAgentDialogFormRefreshCompleted(DataResult.Content(Unit))
        assertFalse(succeeded.loading)
        assertTrue(succeeded.admitted)
        assertFalse(succeeded.failureVisible)
        assertFalse(succeeded.retryAvailable)
        assertTrue(
            isCreateAgentRuntimeContractReady(
                runtime = "builtin",
                builtInFormRefreshAdmitted = succeeded.admitted,
                builtInFormLoading = succeeded.loading,
                hasBuiltInRuntimeConfig = true
            )
        )
    }

    @Test
    fun explicitFormOverrideStartsAdmittedWithoutRefresh() {
        val state =
            createAgentDialogFormRefreshInitialState(
                shouldRefresh = false,
                hasFormOverride = true
            )
        assertFalse(state.loading)
        assertTrue(state.admitted)
        assertFalse(state.failureVisible)
        assertFalse(state.retryAvailable)
    }

    @Test
    fun selectedMachineWithoutRuntimeDataProducesBlockingError() {
        assertEquals(
            CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR,
            createAgentRuntimeAvailabilityError(
                selectedMachineId = "machine-empty",
                machineRuntimeIds = listOf(" ", ""),
                external = false
            )
        )
        assertEquals(
            null,
            createAgentRuntimeAvailabilityError(
                selectedMachineId = null,
                machineRuntimeIds = emptyList(),
                external = false
            )
        )
        assertEquals(
            null,
            createAgentRuntimeAvailabilityError(
                selectedMachineId = "machine-external",
                machineRuntimeIds = emptyList(),
                external = true
            )
        )
    }

    @Test
    fun machineDropdownDisablesOfflineAndNonRequiredComputers() {
        val items =
            createAgentMachineDropdownItems(
                machines =
                    listOf(
                        SharedCreateAgentMachineOption(
                            id = "machine-online",
                            name = "Maria Laptop",
                            status = "online"
                        ),
                        SharedCreateAgentMachineOption(
                            id = "machine-offline",
                            name = "Build Mini",
                            status = "offline"
                        ),
                        SharedCreateAgentMachineOption(
                            id = "machine-other",
                            name = "Lab Tower",
                            status = "online"
                        )
                    ),
                requiredComputer = "machine-online"
            )

        assertEquals(listOf("machine-online", "machine-offline", "machine-other"), items.map { it.id })
        assertEquals("Build Mini (offline)", items[1].label)
        assertTrue(items[0].enabled)
        assertFalse(items[1].enabled)
        assertFalse(items[2].enabled)
    }

    @Test
    fun initialMachineSelectionDoesNotFallbackWhenRequiredComputerIsOffline() {
        val machines =
            listOf(
                SharedCreateAgentMachineOption("required", "Required", "offline"),
                SharedCreateAgentMachineOption("fallback", "Fallback", "online")
            )

        assertEquals(
            null,
            createAgentInitialMachineSelection(
                machines = machines,
                requiredComputer = "required",
                suggestedComputer = null
            )
        )
        assertEquals(
            "fallback",
            createAgentInitialMachineSelection(
                machines = machines,
                requiredComputer = null,
                suggestedComputer = "required"
            )
        )
    }

    @Test
    fun runtimeRequestsRequireSelectedMachineInAvailableSnapshot() {
        val available =
            listOf(SharedCreateAgentMachineOption("machine-1", "Available", "online"))

        assertEquals(null, createAgentAvailableMachineId(emptyList(), "machine-1"))
        assertEquals(null, createAgentAvailableMachineId(available, "machine-missing"))
        assertEquals("machine-1", createAgentAvailableMachineId(available, "machine-1"))
    }

    @Test
    fun automaticRuntimeModelsRequestExecutesOnlyForMachineInCurrentSnapshot() {
        val available = listOf(SharedCreateAgentMachineOption("machine-1", "Available", "online"))
        val requests = mutableListOf<Triple<String, String, Boolean>>()

        assertFalse(
            executeCreateAgentRuntimeModelsRequestIfAvailable(
                machines = emptyList(),
                selectedMachineId = "machine-1",
                runtime = "codex",
                external = false,
                force = false,
                request = { machineId, runtime, force -> requests += Triple(machineId, runtime, force) }
            )
        )
        assertTrue(
            executeCreateAgentRuntimeModelsRequestIfAvailable(
                machines = available,
                selectedMachineId = "machine-1",
                runtime = "codex",
                external = false,
                force = false,
                request = { machineId, runtime, force -> requests += Triple(machineId, runtime, force) }
            )
        )

        assertEquals(listOf(Triple("machine-1", "codex", false)), requests)
    }

    @Test
    fun manualRuntimeModelsRefreshExecutesOnlyForMachineInCurrentSnapshot() {
        val available = listOf(SharedCreateAgentMachineOption("machine-1", "Available", "online"))
        val requests = mutableListOf<Triple<String, String, Boolean>>()

        assertFalse(
            executeCreateAgentRuntimeModelsRequestIfAvailable(
                machines = available,
                selectedMachineId = "machine-missing",
                runtime = "claude",
                external = false,
                force = true,
                request = { machineId, runtime, force -> requests += Triple(machineId, runtime, force) }
            )
        )
        assertTrue(
            executeCreateAgentRuntimeModelsRequestIfAvailable(
                machines = available,
                selectedMachineId = "machine-1",
                runtime = "claude",
                external = false,
                force = true,
                request = { machineId, runtime, force -> requests += Triple(machineId, runtime, force) }
            )
        )

        assertEquals(listOf(Triple("machine-1", "claude", true)), requests)
    }

    @Test
    fun builtInFormRefreshExecutesOnlyForMachineInCurrentSnapshot() = runBlocking {
        val available = listOf(SharedCreateAgentMachineOption("machine-1", "Available", "online"))
        val starts = mutableListOf<String>()
        val requests = mutableListOf<Pair<String, String>>()
        val request: suspend (String, String) -> DataResult<Unit> = { serverId, machineId ->
            requests += serverId to machineId
            DataResult.Content(Unit)
        }

        val staleResult =
            executeCreateAgentBuiltInFormRefreshIfAvailable(
                machines = emptyList(),
                selectedMachineId = "machine-1",
                serverId = "server-1",
                external = false,
                hasFormOverride = false,
                onStarted = { starts += "started" },
                request = request
            )
        assertNull(staleResult)
        assertTrue(starts.isEmpty())
        assertTrue(requests.isEmpty())

        val availableResult =
            executeCreateAgentBuiltInFormRefreshIfAvailable(
                machines = available,
                selectedMachineId = "machine-1",
                serverId = " server-1 ",
                external = false,
                hasFormOverride = false,
                onStarted = { starts += "started" },
                request = request
            )
        assertTrue(availableResult is DataResult.Content)
        assertEquals(listOf("started"), starts)
        assertEquals(listOf("server-1" to "machine-1"), requests)
    }

    @Test
    fun modelDropdownKeepsCustomAsLastOption() {
        val items = createAgentModelDropdownItems("claude")

        assertTrue(items.size > 1)
        assertEquals(CREATE_AGENT_CUSTOM_MODEL_ID, items.last().id)
        assertEquals("Custom", items.last().label)
        assertTrue(items.last().custom)
        assertFalse(items.dropLast(1).any { it.custom })
    }

    @Test
    fun modelDropdownUsesRuntimeOverrideBeforeCustomOption() {
        val items =
            createAgentModelDropdownItems(
                runtime = "claude",
                runtimeModelsOverride =
                    listOf(
                        SharedCreateAgentRuntimeModelOption("server-sonnet", "Server Sonnet"),
                        SharedCreateAgentRuntimeModelOption("server-opus", "Server Opus")
                    )
            )

        assertEquals(
            listOf("server-sonnet", "server-opus", CREATE_AGENT_CUSTOM_MODEL_ID),
            items.map { it.id }
        )
    }

    @Test
    fun onlyCanonicalCustomModelRuntimesAppendCustomAsLastOption() {
        RUNTIMES.filter { it.supported }.forEach { runtime ->
            val items =
                createAgentModelDropdownItems(
                    runtime = runtime.id,
                    runtimeModelsOverride =
                        listOf(SharedCreateAgentRuntimeModelOption("machine-model", "Machine Model")),
                    allowCustomModel = supportsRuntimeCustomModelName(runtime.id)
                )

            if (supportsRuntimeCustomModelName(runtime.id)) {
                assertEquals(CREATE_AGENT_CUSTOM_MODEL_ID, items.last().id, runtime.id)
                assertTrue(items.last().custom, runtime.id)
            } else {
                assertFalse(items.any { it.custom }, runtime.id)
                assertFalse(items.any { it.id == CREATE_AGENT_CUSTOM_MODEL_ID }, runtime.id)
            }
        }
    }

    @Test
    fun modelDropdownCanHideCustomOptionForRuntimesWithoutCustomModels() {
        val items = createAgentModelDropdownItems("opencode", allowCustomModel = false)

        assertTrue(items.isNotEmpty())
        assertFalse(items.any { it.custom })
        assertFalse(items.any { it.id == CREATE_AGENT_CUSTOM_MODEL_ID })
    }

    @Test
    fun unknownModelValueRehydratesAsCustomDraft() {
        val (customMode, modelValue) =
            createAgentModelSelectionForValue(runtime = "claude", model = "vendor/private-model")

        assertTrue(customMode)
        assertEquals("vendor/private-model", modelValue)
    }

    @Test
    fun gpt56PresetDoesNotRehydrateAsCustomDraft() {
        val (customMode, modelValue) =
            createAgentModelSelectionForValue(runtime = "codex", model = "gpt-5.6-sol")

        assertFalse(customMode)
        assertEquals("gpt-5.6-sol", modelValue)
    }

    @Test
    fun machineOnlyBuiltinPresetIsRecognizedByRuntimeCatalog() {
        val machineModels =
            listOf(
                SharedCreateAgentRuntimeModelOption(
                    id = "minimax/MiniMax-M2.7",
                    label = "MiniMax M2.7"
                )
            )

        val (customMode, modelValue) =
            createAgentModelSelectionForValue(
                runtime = "builtin",
                model = "minimax/MiniMax-M2.7",
                runtimeModelsOverride = machineModels
            )

        assertFalse(customMode)
        assertEquals("minimax/MiniMax-M2.7", modelValue)
    }

    @Test
    fun agentDetailRetainsPresetMissingFromCurrentMachineCatalog() {
        val items =
            createAgentModelDropdownItemsForSelection(
                runtime = "builtin",
                runtimeModelsOverride =
                    listOf(SharedCreateAgentRuntimeModelOption("other/model", "Other model")),
                selectedModel = "minimax/MiniMax-M2.7",
                customModelMode = false,
                retainUnavailableSelectedPreset = true
            )

        assertEquals(
            "minimax/MiniMax-M2.7 (not in this computer's config)",
            items.first { it.id == "minimax/MiniMax-M2.7" }.label
        )
        assertTrue(items.indexOfFirst { it.id == "minimax/MiniMax-M2.7" } < items.indexOfFirst { it.custom })
    }

    @Test
    fun runtimeCatalogDefaultWinsWhenCurrentSelectionBecomesUnavailable() {
        val models =
            listOf(
                SharedCreateAgentRuntimeModelOption("machine-default", "Machine Default"),
                SharedCreateAgentRuntimeModelOption("other", "Other")
            )

        assertEquals(
            "machine-default",
            createAgentPreferredModelId(
                runtime = "codex",
                runtimeModelsOverride = models,
                runtimeModelsDefault = "machine-default"
            )
        )
    }

    @Test
    fun blankModelValueFallsBackToRuntimeDefaultPreset() {
        val (customMode, modelValue) =
            createAgentModelSelectionForValue(runtime = "claude", model = "   ")

        assertFalse(customMode)
        assertEquals("opus", modelValue)
    }

    @Test
    fun providerDropdownUsesDefaultAndCustomOptions() {
        val items = createAgentProviderDropdownItems()

        assertEquals(
            listOf("default", "custom"),
            items.map { it.id }
        )
        assertEquals(listOf("Default", "Custom"), items.map { it.label })
    }

    @Test
    fun reasoningDropdownIncludesDefaultFirst() {
        val items = createAgentReasoningDropdownItems()

        assertEquals(CREATE_AGENT_DEFAULT_REASONING_ID, items.first().id)
        assertEquals("Default", items.first().label)
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            items.drop(1).map { it.id }
        )
        assertEquals(
            listOf("Low", "Medium", "High", "Extra High"),
            items.drop(1).map { it.label }
        )
    }

    @Test
    fun reasoningDropdownUsesCodexModelCapabilitiesAndCanonicalEnglishLabels() {
        val solItems =
            createAgentReasoningDropdownItems(
                defaultLabel = "默认",
                runtime = "codex",
                model = "gpt-5.6-sol"
            )
        val lunaItems =
            createAgentReasoningDropdownItems(
                defaultLabel = "默认",
                runtime = "codex",
                model = "gpt-5.6-luna"
            )

        assertEquals(
            listOf(CREATE_AGENT_DEFAULT_REASONING_ID, "low", "medium", "high", "xhigh", "max", "ultra"),
            solItems.map { it.id }
        )
        assertEquals(
            listOf("默认", "Low", "Medium", "High", "Extra High", "Max", "Ultra"),
            solItems.map { it.label }
        )
        assertEquals(
            listOf(CREATE_AGENT_DEFAULT_REASONING_ID, "low", "medium", "high", "xhigh", "max"),
            lunaItems.map { it.id }
        )
        assertFalse(lunaItems.any { it.id == "ultra" })
    }

    @Test
    fun reasoningDropdownPrefersLiveCapabilitiesAndKeepsUndeclaredModelsOnBaseSet() {
        val liveModels =
            listOf(
                SharedCreateAgentRuntimeModelOption(
                    id = "machine-codex",
                    label = "Machine Codex",
                    supportedReasoningEfforts = listOf("low", "high", "max"),
                    defaultReasoningEffort = "high"
                )
            )

        assertEquals(
            listOf(CREATE_AGENT_DEFAULT_REASONING_ID, "low", "high", "max"),
            createAgentReasoningDropdownItems(
                runtime = "codex",
                model = "machine-codex",
                runtimeModelsOverride = liveModels
            ).map { it.id }
        )
        assertEquals(
            listOf(CREATE_AGENT_DEFAULT_REASONING_ID, "low", "medium", "high", "xhigh"),
            createAgentReasoningDropdownItems(runtime = "claude", model = "opus").map { it.id }
        )
    }

    @Test
    fun reasoningSelectionReconcilesToDeclaredModelDefault() {
        assertEquals(
            "medium",
            reconcileCreateAgentReasoningEffort(
                runtime = "codex",
                model = "gpt-5.6-luna",
                current = "ultra"
            )
        )
        assertEquals(
            "max",
            reconcileCreateAgentReasoningEffort(
                runtime = "codex",
                model = "gpt-5.6-luna",
                current = "max"
            )
        )
        assertEquals(
            "medium",
            reconcileCreateAgentReasoningEffort(
                runtime = "codex",
                model = "gpt-5.6-luna",
                current = null
            )
        )
    }

    @Test
    fun runtimeModelSelectionAtomicallyReconcilesExplicitAndAsyncTargets() {
        val claude =
            createAgentRuntimeModelReasoningSelection(
                runtime = "claude",
                model = "opus",
                currentReasoningEffort = "ultra"
            )
        val luna =
            createAgentRuntimeModelReasoningSelection(
                runtime = "codex",
                model = "gpt-5.6-luna",
                currentReasoningEffort = "ultra"
            )
        val sol =
            createAgentRuntimeModelReasoningSelection(
                runtime = "codex",
                model = "gpt-5.6-sol",
                currentReasoningEffort = "ultra"
            )
        val live =
            createAgentRuntimeModelReasoningSelection(
                runtime = "codex",
                model = "machine-codex",
                currentReasoningEffort = "ultra",
                runtimeModelsOverride =
                    listOf(
                        SharedCreateAgentRuntimeModelOption(
                            id = "machine-codex",
                            label = "Machine Codex",
                            supportedReasoningEfforts = listOf("low", "high", "max"),
                            defaultReasoningEffort = "high"
                        )
                    )
            )

        assertEquals(
            CreateAgentRuntimeModelReasoningSelection("claude", "opus", null),
            claude
        )
        assertEquals(
            CreateAgentRuntimeModelReasoningSelection("codex", "gpt-5.6-luna", "medium"),
            luna
        )
        assertEquals(
            CreateAgentRuntimeModelReasoningSelection("codex", "gpt-5.6-sol", "ultra"),
            sol
        )
        assertEquals(
            CreateAgentRuntimeModelReasoningSelection("codex", "machine-codex", "high"),
            live
        )
    }

    @Test
    fun createSubmitFailsClosedWhenReasoningDoesNotBelongToFinalModel() {
        fun submit(runtime: String, model: String) =
            buildSharedCreateAgentDialogSubmit(
                name = "Reasoning Agent",
                description = "",
                selectedMachineId = "machine-1",
                requiredComputer = null,
                external = false,
                runtime = runtime,
                model = model,
                customModelMode = false,
                customModelName = "",
                providerMode = "default",
                providerApiUrl = "",
                providerApiKey = TextFieldValue(""),
                fastMode = false,
                reasoningEffort = "ultra",
                envVarEntries = emptyList(),
                command = ""
            ).runtimeConfig

        assertNull(assertNotNull(submit("claude", "opus")).reasoningEffort)
        assertEquals("medium", assertNotNull(submit("codex", "gpt-5.6-luna")).reasoningEffort)
        assertEquals("ultra", assertNotNull(submit("codex", "gpt-5.6-sol")).reasoningEffort)
    }

    @Test
    fun createSubmitPreservesExplicitDefaultAsNull() {
        val runtimeConfig =
            assertNotNull(
                buildSharedCreateAgentDialogSubmit(
                    name = "Default Agent",
                    description = "",
                    selectedMachineId = "machine-1",
                    requiredComputer = null,
                    external = false,
                    runtime = "codex",
                    model = "gpt-5.6-sol",
                    customModelMode = false,
                    customModelName = "",
                    providerMode = "default",
                    providerApiUrl = "",
                    providerApiKey = TextFieldValue(""),
                    fastMode = false,
                    reasoningEffort = null,
                    envVarEntries = emptyList(),
                    command = ""
                ).runtimeConfig
            )

        assertNull(runtimeConfig.reasoningEffort)
    }

    @Test
    fun codexMaxReasoningSelectionReachesRuntimeConfig() {
        val request =
            buildSharedCreateAgentDialogSubmit(
                name = "Max Agent",
                description = "",
                selectedMachineId = "machine-1",
                requiredComputer = null,
                external = false,
                runtime = "codex",
                model = "gpt-5.6-luna",
                customModelMode = false,
                customModelName = "",
                providerMode = "default",
                providerApiUrl = "",
                providerApiKey = TextFieldValue(""),
                fastMode = false,
                reasoningEffort = "max",
                envVarEntries = emptyList(),
                command = ""
            ).toCreateAgentRequest()

        assertEquals("max", assertNotNull(request.runtimeConfig).reasoningEffort)
    }

    @Test
    fun createAgentDialogSubmitIncludesPixelAvatar() {
        val request =
            SharedCreateAgentDialogSubmit(
                name = "Build Agent",
                description = "Runs builds",
                machineId = "machine-1"
            ).toCreateAgentRequest()

        val avatarUrl = assertNotNull(request.avatarUrl)
        assertTrue(avatarUrl.startsWith("pixel:"))
        assertTrue(avatarUrl.removePrefix("pixel:") in PixelAvatarPickerKeys)
    }

    @Test
    fun customModelSelectionAcceptsAndSavesUserInputForSupportedRuntime() {
        val request =
            buildSharedCreateAgentDialogSubmit(
                name = "Codex Agent",
                description = "",
                selectedMachineId = "machine-1",
                requiredComputer = null,
                external = false,
                runtime = "codex",
                model = "catalog-model",
                customModelMode = true,
                customModelName = " vendor/private-codex-model ",
                providerMode = "default",
                providerApiUrl = "",
                providerApiKey = TextFieldValue(""),
                fastMode = false,
                reasoningEffort = null,
                envVarEntries = emptyList(),
                command = ""
            ).toCreateAgentRequest()

        assertEquals(
            RuntimeModelConfig.Custom("vendor/private-codex-model"),
            assertNotNull(request.runtimeConfig).model
        )
    }

    @Test
    fun customModelSelectionKeepsCustomAsTheClosedSelectLabel() {
        val options =
            createAgentModelDropdownItemsForSelection(
                runtime = "codex",
                runtimeModelsOverride =
                    listOf(
                        SharedCreateAgentRuntimeModelOption(
                            id = "gpt-5.6-sol",
                            label = "GPT-5.6 Sol",
                            verified = null
                        )
                    ),
                selectedModel = "gpt-5.6-sol",
                customModelMode = true,
                retainUnavailableSelectedPreset = true
            )

        assertEquals(
            "Custom",
            createAgentModelSelectLabel(
                customModelMode = true,
                model = "gpt-5.6-sol",
                modelOptions = options
            )
        )
    }

    @Test
    fun createAgentDialogSubmitExtractsAllRuntimeFormFields() {
        val request =
            buildSharedCreateAgentDialogSubmit(
                name = " @Build_Agent ",
                description = "  Runs build checks  ",
                selectedMachineId = "machine-selected",
                requiredComputer = "machine-required",
                external = false,
                runtime = "claude",
                model = "sonnet",
                customModelMode = true,
                customModelName = " vendor/private-model ",
                providerMode = RUNTIME_PROVIDER_MODE_CUSTOM,
                providerApiUrl = " https://gateway.example.com ",
                providerApiKey = TextFieldValue(" dummy-key "),
                fastMode = true,
                reasoningEffort = "high",
                envVarEntries =
                    listOf(
                        " ANTHROPIC_API_KEY " to "secret",
                        "  " to "ignored",
                        "SLOCK_MODE" to "test"
                    ),
                command = " /usr/local/bin/claude "
            ).toCreateAgentRequest()

        assertEquals("Build_Agent", request.name)
        assertEquals("Runs build checks", request.description)
        assertEquals("machine-required", request.machineId)
        assertFalse(request.external)

        val runtimeConfig = assertNotNull(request.runtimeConfig)
        assertEquals("claude", runtimeConfig.runtime)
        assertEquals(RuntimeModelConfig.Custom("vendor/private-model"), runtimeConfig.model)
        assertEquals(
            RuntimeProviderConfig.Custom(
                apiUrl = "https://gateway.example.com",
                apiKey = "dummy-key"
            ),
            runtimeConfig.provider
        )
        assertEquals(RuntimeModeConfig.Fast, runtimeConfig.mode)
        assertEquals("high", runtimeConfig.reasoningEffort)
        assertEquals(
            mapOf(
                "ANTHROPIC_API_KEY" to "secret",
                "SLOCK_MODE" to "test"
            ),
            runtimeConfig.envVars
        )
        assertEquals("/usr/local/bin/claude", runtimeConfig.command)
        assertNotNull(request.avatarUrl)
    }

    @Test
    fun externalCreateAgentSubmitDoesNotLeakRuntimeFields() {
        val submit =
            buildSharedCreateAgentDialogSubmit(
                name = "External",
                description = "",
                selectedMachineId = "machine-1",
                requiredComputer = "machine-required",
                external = true,
                runtime = "claude",
                model = "sonnet",
                customModelMode = false,
                customModelName = "",
                providerMode = RUNTIME_PROVIDER_MODE_CUSTOM,
                providerApiUrl = "https://gateway.example.com",
                providerApiKey = TextFieldValue("dummy-key"),
                fastMode = true,
                reasoningEffort = "high",
                envVarEntries = listOf("ANTHROPIC_API_KEY" to "secret"),
                command = "/usr/local/bin/claude"
            )

        assertEquals("External", submit.name)
        assertEquals(null, submit.description)
        assertEquals(null, submit.machineId)
        assertEquals(null, submit.runtimeConfig)
        assertTrue(submit.external)
    }
}
