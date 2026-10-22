package build.raft.app

import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task #7: switching an existing agent's runtime from the v2 edit dialog's Runtime select. */
class MembersRuntimeSwitchEntryTest {
    private val builtInConfig =
        RuntimeConfig(
            runtime = "builtin",
            provider = RuntimeProviderConfig.Preset("deepseek", ""),
            model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro")
        )

    @Test
    fun v2EditRuntimeOptionsAreTheComputerRuntimesWithNotInstalledDisabled() {
        val items =
            createAgentRuntimeDropdownItems(
                machineRuntimeIds = listOf("builtin", "codex"),
                allowStaticFallback = false,
                currentRuntimeId = "builtin"
            )
        val enabled = items.filter { it.enabled }.map { it.id }.toSet()
        assertTrue("builtin" in enabled && "codex" in enabled, "items=$items")
        val claude = items.single { it.id == "claude" }
        assertEquals(false, claude.enabled)
        assertTrue(claude.label.endsWith(" (not installed)"))
    }

    @Test
    fun pickingAnotherRuntimeInTheV2DialogHandsItToV1() {
        assertEquals("codex", membersRuntimeFormV2EditRuntimeSwitch(currentRuntimeId = "builtin", picked = "codex"))
    }

    @Test
    fun reselectingTheCurrentRuntimeInTheV2DialogStaysInV2() {
        assertNull(membersRuntimeFormV2EditRuntimeSwitch(currentRuntimeId = "builtin", picked = "builtin"))
        assertNull(membersRuntimeFormV2EditRuntimeSwitch(currentRuntimeId = "builtin", picked = " "))
    }

    @Test
    fun v1OpenedWithAnotherInitialRuntimeStartsOnItWithTheSelectReset() {
        val seeded =
            membersRuntimeConfigInitialRuntimeSwitch(
                initialConfig = builtInConfig,
                initialRuntime = "codex",
                nextRuntimeModels = emptyList()
            )
        // Identical to what the v1 dialog's own runtime select does from Built-in.
        val ownSelect =
            membersRuntimeConfigRuntimeSwitch(
                initialConfig = builtInConfig,
                currentRuntime = "builtin",
                nextRuntime = "codex",
                currentReasoningEffort = null,
                nextRuntimeModels = emptyList()
            )
        assertEquals(ownSelect, seeded)
        requireNotNull(seeded)
        assertEquals("codex", seeded.runtime)
        assertEquals("", seeded.model)
        assertEquals(true, seeded.awaitingServerModelForRuntime)
        assertEquals(true, seeded.clearCustomModel)
        // Built-in's select only resets the model draft.
        assertEquals(false, seeded.resetRuntimeSpecificFields)
    }

    @Test
    fun v1OpenedWithItsOwnRuntimeAsInitialRuntimeIsNotReset() {
        assertNull(membersRuntimeConfigInitialRuntimeSwitch(builtInConfig, initialRuntime = "builtin", emptyList()))
        assertNull(membersRuntimeConfigInitialRuntimeSwitch(builtInConfig, initialRuntime = null, emptyList()))
    }

    @Test
    fun switchFromANonBuiltInRuntimeAlsoClearsItsProviderAndCommandDrafts() {
        val codex = RuntimeConfig(runtime = "codex", model = RuntimeModelConfig.Preset("gpt-5"), command = "codex")
        val toClaude = membersRuntimeConfigInitialRuntimeSwitch(codex, initialRuntime = "claude", emptyList())
        requireNotNull(toClaude)
        assertEquals("claude", toClaude.runtime)
        assertEquals(true, toClaude.awaitingServerModelForRuntime)
        assertEquals(true, toClaude.resetRuntimeSpecificFields)

        val toBuiltIn = membersRuntimeConfigInitialRuntimeSwitch(codex, initialRuntime = "builtin", emptyList())
        requireNotNull(toBuiltIn)
        assertEquals("builtin", toBuiltIn.runtime)
        assertEquals("gpt-5", toBuiltIn.model)
        assertEquals(false, toBuiltIn.awaitingServerModelForRuntime)
        assertEquals(false, toBuiltIn.clearCustomModel)
        assertEquals(true, toBuiltIn.resetRuntimeSpecificFields)
    }
}
