package build.raft.app

import build.raft.app.model.RUNTIME_PROVIDER_MODE_CUSTOM
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedCreateAgentDialogTest {
    @Test
    fun runtimeDropdownUsesSupportedRuntimes() {
        val items = createAgentRuntimeDropdownItems()

        assertTrue(items.isNotEmpty())
        assertEquals(
            listOf("claude", "codex", "antigravity", "kimi", "copilot", "cursor", "gemini", "opencode", "pi"),
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
            listOf("claude", "codex", "antigravity", "kimi", "copilot", "cursor", "gemini", "opencode", "pi"),
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
    fun runtimeDropdownCanDisableStaticFallbackForMachineWithNoRuntimeData() {
        val items =
            createAgentRuntimeDropdownItems(
                machineRuntimeIds = listOf(" ", ""),
                allowStaticFallback = false
            )

        assertTrue(items.isEmpty())
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
    fun modelDropdownKeepsCustomAsLastOption() {
        val items = createAgentModelDropdownItems("claude")

        assertTrue(items.size > 1)
        assertEquals(CREATE_AGENT_CUSTOM_MODEL_ID, items.last().id)
        assertEquals("Custom...", items.last().label)
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

        assertTrue(items.size > 1)
        assertEquals(CREATE_AGENT_DEFAULT_REASONING_ID, items.first().id)
        assertEquals("Default", items.first().label)
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            items.drop(1).map { it.id }
        )
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
                providerApiKey = " sk-test ",
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
                apiKey = "sk-test"
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
                providerApiKey = "sk-test",
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
