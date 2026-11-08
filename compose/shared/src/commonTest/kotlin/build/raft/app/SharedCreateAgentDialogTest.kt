package build.raft.app

import build.raft.app.data.HostedRuntimeModels
import build.raft.app.data.HostedRuntimeModelOption
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.EmptyReason
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
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

    @Test
    fun aCloudAgentIsPickedOrTheOnlyPlaceToRun() {
        val one = listOf(SharedCreateAgentMachineOption(id = "m1", name = "Mac", status = "online"))
        fun mode(available: Boolean, selected: Boolean, machines: List<SharedCreateAgentMachineOption>, loading: Boolean = false, error: String? = null) =
            createAgentHostedMode(available, selected, machines, loading, error)
        assertTrue(mode(available = true, selected = true, machines = one))
        assertFalse(mode(available = true, selected = false, machines = one), "computers come first")
        assertTrue(mode(available = true, selected = false, machines = emptyList()), "no computer: only the cloud")
        // Not yet known: the picker keeps its loading / retry states.
        assertFalse(mode(available = true, selected = false, machines = emptyList(), loading = true))
        assertFalse(mode(available = true, selected = false, machines = emptyList(), error = "Failed"))
        assertFalse(mode(available = false, selected = true, machines = emptyList()), "never without the server's yes")
    }

    @Test
    fun aCloudAgentSubmitsTheProviderAndNoComputerOrRuntime() {
        val submit =
            assertNotNull(
                createAgentDialogSubmit(
                    name = "@cloud-one",
                    description = " ",
                    machineId = "m1",
                    external = false,
                    runtime = "codex",
                    loaded = null,
                    values = emptyMap(),
                    hosted = true,
                    hostedModel = "gpt-5.6-luna"
                )
            )
        assertEquals("cloud-one", submit.name)
        assertEquals(null, submit.machineId)
        assertTrue(submit.external)
        assertEquals(null, submit.runtimeFormV2)
        val request = submit.toCreateAgentRequest()
        assertEquals("antiproton", request.hostedProvider)
        assertEquals("gpt-5.6-luna", request.hostedModel)
    }

    @Test
    fun aCreateNamesAModelOnlyWhereTheServerLetsItPick() {
        val offered = HostedRuntimeModels(listOf(HostedRuntimeModelOption("gpt-5.6-luna", "GPT-5.6 Luna")), defaultLabel = "DeepSeek Flash")
        val ready = CreateAgentHostedModels.Ready(offered)
        assertEquals("gpt-5.6-luna", createAgentHostedModelToSend(ready, "gpt-5.6-luna"))
        assertEquals(null, createAgentHostedModelToSend(ready, null), "the provider's default")
        assertEquals(null, createAgentHostedModelToSend(ready, "gone"), "no longer offered")
        assertEquals(null, createAgentHostedModelToSend(CreateAgentHostedModels.Ready(offered.copy(locked = true)), "gpt-5.6-luna"))
        assertEquals(null, createAgentHostedModelToSend(CreateAgentHostedModels.Ready(offered.copy(picker = false)), "gpt-5.6-luna"))
        assertEquals(null, createAgentHostedModelToSend(CreateAgentHostedModels.Failed, "gpt-5.6-luna"))
        assertTrue(createAgentHostedModelFieldShown(ready))
        assertTrue(createAgentHostedModelFieldShown(CreateAgentHostedModels.Failed), "says the list failed")
        assertFalse(createAgentHostedModelFieldShown(CreateAgentHostedModels.Ready(offered.copy(picker = false))))
        assertEquals(listOf("", "gpt-5.6-luna"), createAgentHostedModelOptions(offered, "Default (DeepSeek Flash)").map { it.value })
    }

    @Test
    fun noComputerDialogShowsOnlyForAManagedAgentWithALoadedEmptyComputerList() {
        val one = listOf(SharedCreateAgentMachineOption(id = "m1", name = "Mac", status = "offline"))
        // Web: `!isExternalMode && machines.length === 0` -> the short "Connect a computer first" dialog.
        assertTrue(createAgentNeedsComputer(external = false, machines = emptyList(), machinesLoading = false, machinesErrorMessage = null))
        assertTrue(createAgentNeedsComputer(external = false, machines = emptyList(), machinesLoading = false, machinesErrorMessage = " "))
        // An offline computer still counts: the form explains it.
        assertFalse(createAgentNeedsComputer(external = false, machines = one, machinesLoading = false, machinesErrorMessage = null))
        // External agents need no computer.
        assertFalse(createAgentNeedsComputer(external = true, machines = emptyList(), machinesLoading = false, machinesErrorMessage = null))
        // Not yet known: the form keeps its loading / retry states.
        assertFalse(createAgentNeedsComputer(external = false, machines = emptyList(), machinesLoading = true, machinesErrorMessage = null))
        assertFalse(createAgentNeedsComputer(external = false, machines = emptyList(), machinesLoading = false, machinesErrorMessage = "Failed"))
    }

    @Test
    fun computersPageOpensAddComputerOnceItsSnapshotLoads() {
        assertEquals(null, raftComputersOpenAddOnLoad(ScreenLoadState.Loading))
        assertEquals(true, raftComputersOpenAddOnLoad(ScreenLoadState.Content(Unit)))
        assertEquals(false, raftComputersOpenAddOnLoad(ScreenLoadState.Error(ScreenError(code = "network"))))
        assertEquals(false, raftComputersOpenAddOnLoad(ScreenLoadState.Empty(EmptyReason.values().first())))
    }

}
