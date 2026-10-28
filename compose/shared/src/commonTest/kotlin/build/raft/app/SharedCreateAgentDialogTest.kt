package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

}
