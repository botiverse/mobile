package build.raft.app

import build.raft.app.data.BundledRuntimeCatalog
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
    fun runtimePickerOffersTheBundledCreatableRuntimesWithoutTheServerCatalog() {
        val rows = runtimePickerRows(BundledRuntimeCatalog, emptyList(), selectedRuntimeId = "", showAll = true).rows
        assertEquals(
            listOf("claude", "codex", "grok", "builtin", "kimi-sdk", "cursor", "opencode", "pi", "qwen", "qoder"),
            rows.map { it.id }.sortedBy { id -> BundledRuntimeCatalog.runtimes.indexOfFirst { it.id == id } }
        )
    }

    @Test
    fun runtimePickerUsesSelectedMachineRuntimes() {
        val rows =
            runtimePickerRows(
                BundledRuntimeCatalog,
                listOf("codex", "claude", "custom-runtime", "codex", " "),
                selectedRuntimeId = "codex",
                showAll = true
            ).rows.associateBy { it.id }
        assertEquals("Claude Code", rows.getValue("claude").name)
        assertEquals("Codex CLI", rows.getValue("codex").name)
        assertTrue(rows.getValue("claude").enabled)
        assertTrue(rows.getValue("codex").enabled)
        assertFalse(rows.getValue("pi").enabled)
        assertEquals(RuntimePickerSuffix.NotInstalled, rows.getValue("pi").suffix)
        assertFalse("custom-runtime" in rows)
    }

    @Test
    fun runtimePickerRetainsOnlyTheCurrentDeprecatedRuntimeWhileEditing() {
        val createIds = runtimePickerRows(BundledRuntimeCatalog, emptyList(), "", showAll = true).rows.map { it.id }
        assertFalse("copilot" in createIds)
        assertFalse("kimi" in createIds)
        assertFalse("gemini" in createIds)
        assertFalse("antigravity" in createIds)

        val editingKimiIds = runtimePickerRows(BundledRuntimeCatalog, emptyList(), "kimi", showAll = true, currentRuntimeId = "kimi").rows.map { it.id }
        assertTrue("kimi" in editingKimiIds)
        assertFalse("gemini" in editingKimiIds)
    }

    @Test
    fun copilotIsHiddenForCreationAndSwitchingButRetainedForExistingAgent() {
        val installed = listOf("codex", "copilot")
        assertFalse(runtimePickerRows(BundledRuntimeCatalog, installed, "codex", showAll = true).rows.any { it.id == "copilot" })
        assertFalse(
            runtimePickerRows(BundledRuntimeCatalog, installed, "codex", showAll = true, currentRuntimeId = "codex").rows.any { it.id == "copilot" }
        )
        val existing = runtimePickerRows(BundledRuntimeCatalog, installed, "copilot", showAll = true, currentRuntimeId = "copilot").rows
        assertTrue(existing.single { it.id == "copilot" }.enabled)
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
