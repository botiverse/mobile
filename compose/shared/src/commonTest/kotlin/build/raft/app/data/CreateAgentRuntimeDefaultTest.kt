package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The create dialog's default runtime (web CreateAgentDialog `pickCreateAgentRuntime`, botiverse/slock
 * staging 6d6d78d4c4) and the per-server memory of the last create it prefers.
 */
class CreateAgentRuntimeDefaultTest {
    private val supported = setOf("claude", "codex", "grok", "builtin", "kimi-sdk", "cursor")

    @Test
    fun theRememberedRuntimeWinsWhileTheComputerStillOffersIt() {
        assertEquals("codex", pickCreateAgentRuntime(listOf("claude", "codex"), supported, onboarding = false, remembered = "codex"))
    }

    @Test
    fun aRememberedRuntimeTheComputerNoLongerOffersFallsBackToTheFirstCandidate() {
        assertEquals("claude", pickCreateAgentRuntime(listOf("claude", "codex"), supported, onboarding = false, remembered = "grok"))
        // Remembered but no longer creatable from the catalog: not picked either.
        assertEquals("claude", pickCreateAgentRuntime(listOf("claude", "retired"), supported, onboarding = false, remembered = "retired"))
    }

    @Test
    fun withoutAMemoryTheFirstSupportedCandidateElseTheFirstCandidate() {
        assertEquals("codex", pickCreateAgentRuntime(listOf("future", "codex"), supported, onboarding = false, remembered = null))
        assertEquals("future", pickCreateAgentRuntime(listOf("future"), supported, onboarding = false, remembered = null))
    }

    @Test
    fun onboardingGoesClaudeCodexGrokBuiltinAndIgnoresTheMemory() {
        assertEquals("claude", pickCreateAgentRuntime(listOf("cursor", "builtin", "claude"), supported, onboarding = true, remembered = "cursor"))
        assertEquals("codex", pickCreateAgentRuntime(listOf("builtin", "grok", "codex"), supported, onboarding = true, remembered = null))
        assertEquals("grok", pickCreateAgentRuntime(listOf("builtin", "grok"), supported, onboarding = true, remembered = null))
        assertEquals("builtin", pickCreateAgentRuntime(listOf("cursor", "builtin"), supported, onboarding = true, remembered = null))
        assertEquals("cursor", pickCreateAgentRuntime(listOf("cursor"), supported, onboarding = true, remembered = null))
    }

    @Test
    fun noCandidateSelectsNothing() {
        assertEquals("", pickCreateAgentRuntime(emptyList(), supported, onboarding = false, remembered = "claude"))
        assertEquals("", pickCreateAgentRuntime(emptyList(), supported, onboarding = true, remembered = null))
    }

    @Test
    fun candidatesAreOnlyTheRuntimeOptionsThisPersonCanPickHere() {
        val catalog =
            SharedCreateAgentRuntimeCatalog(
                machineId = "m1",
                options =
                    listOf(
                        option("claude", capability = "not_installed", canSelect = false),
                        option("builtin", capability = "update_required", canSelect = false),
                        option("kimi-sdk", capability = "update_required", canSelect = false),
                        option("qoder", capability = "available", canSelect = false),
                        option("cursor", capability = "available", canSelect = true),
                        option("codex", capability = "available", canSelect = true),
                    )
            )
        val candidates = createAgentRuntimeCandidates(catalog)
        assertEquals(listOf("cursor", "codex"), candidates)
        // Claude Code is remembered and first in the catalog, but not installed: never the default.
        assertEquals("cursor", pickCreateAgentRuntime(candidates, supported, onboarding = false, remembered = "claude"))
        assertEquals("codex", pickCreateAgentRuntime(candidates, supported, onboarding = true, remembered = null))
        // Nothing installed (or runtime-options not loaded): nothing selected, never a not-installed one.
        val nothingInstalled = SharedCreateAgentRuntimeCatalog("m1", catalog.options.filterNot { it.canSelectInThisContext })
        assertEquals("", pickCreateAgentRuntime(createAgentRuntimeCandidates(nothingInstalled), supported, onboarding = false, remembered = "claude"))
        assertEquals("", pickCreateAgentRuntime(createAgentRuntimeCandidates(null), supported, onboarding = false, remembered = "claude"))
    }

    @Test
    fun theLastCreateIsRememberedPerServer() {
        val prefs = MapPreferences()
        val repository = CreateAgentLastConfigRepository(prefs)
        assertNull(repository.read("s1"))
        repository.write("s1", CreateAgentLastConfig(machineId = "m1", runtime = "codex"))
        repository.write("s2", CreateAgentLastConfig(machineId = "m9", runtime = "claude"))
        assertEquals(CreateAgentLastConfig("m1", "codex"), CreateAgentLastConfigRepository(prefs).read("s1"))
        assertEquals("claude", repository.read("s2")?.runtime)
        // The web's storage shape and key, so the two read alike in a diagnostics dump.
        assertEquals("""{"machineId":"m1","runtime":"codex"}""", prefs.values["raft:create-agent:last-config:v1:s1"])
        // A blank server, a blank value or an unreadable stored value: nothing.
        repository.write(" ", CreateAgentLastConfig("m1", "codex"))
        repository.write("s3", CreateAgentLastConfig("m1", " "))
        assertNull(repository.read("s3"))
        assertNull(repository.read(null))
        prefs.values["raft:create-agent:last-config:v1:s4"] = "not json"
        assertNull(repository.read("s4"))
    }

    private fun option(id: String, capability: String, canSelect: Boolean) =
        SharedCreateAgentRuntimeOption(
            runtimeId = id,
            capabilityStatus = capability,
            admissionStatus = "available_for_new",
            admissionReason = null,
            current = false,
            availableForNew = true,
            manageableForCurrentAgent = false,
            canSelectInThisContext = canSelect
        )

    private class MapPreferences : SharedPreferenceStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
