package build.raft.app.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRuntimeConfigTest {

    // ── validateAgentName ──

    @Test
    fun validateAgentNameAcceptsSimpleAsciiName() {
        assertNull(validateAgentName("Alice"))
        assertNull(validateAgentName("alice-2"))
        assertNull(validateAgentName("noel_bot"))
    }

    @Test
    fun validateAgentNameRejectsReservedNames() {
        assertEquals(
            "Agent name @all is reserved. Choose another name.",
            validateAgentName("all")
        )
        assertEquals(
            "Agent name @humans is reserved. Choose another name.",
            validateAgentName("Humans")
        )
    }

    @Test
    fun validateAgentNameRejectsEmptyAndTooLong() {
        assertEquals("Agent name is required", validateAgentName(""))
        assertEquals("Agent name is required", validateAgentName("   "))
        val tooLong = "a".repeat(NAME_MAX_LENGTH + 1)
        assertEquals(
            "Agent name must be at most $NAME_MAX_LENGTH characters",
            validateAgentName(tooLong)
        )
    }

    @Test
    fun validateAgentNameRejectsLeadingDigitAndSpecialChars() {
        assertNotNull(validateAgentName("1alice"))
        assertNotNull(validateAgentName("alice@home"))
        assertNotNull(validateAgentName("alice two"))
        assertNotNull(validateAgentName("alice.bot"))
    }

    // ── Runtimes / models ──

    @Test
    fun runtimesMirrorTheSharedCatalogAndMarkLegacyEntriesDeprecated() {
        // slock packages/shared/src/runtimeCatalog.ts RUNTIMES at f5fe2ca864 (PR #12065).
        val ids = RUNTIMES.map { it.id }
        assertEquals(
            listOf(
                "claude", "codex", "grok", "builtin", "antigravity", "kimi-sdk",
                "kimi", "copilot", "cursor", "gemini", "opencode", "pi", "qwen", "qoder"
            ),
            ids
        )
        assertEquals(setOf("antigravity", "kimi", "gemini", "copilot"), RUNTIMES.filter { it.deprecated }.map { it.id }.toSet())
        assertEquals(setOf("qwen", "qoder"), RUNTIMES.filter { it.experimental }.map { it.id }.toSet())
        assertEquals("Kimi CLI", RUNTIMES.single { it.id == "kimi" }.displayName)
    }

    @Test
    fun getDefaultModelFallsBackToSonnetForUnknownRuntime() {
        assertEquals("sonnet", getDefaultModel("unknown-runtime"))
        assertEquals("opus", getDefaultModel("claude"))
        assertEquals("gpt-5.6-sol", getDefaultModel("codex"))
        assertEquals("default", getDefaultModel("kimi"))
    }

    @Test
    fun codexPresentationPrefersAndOrdersGpt56WhenMachineSurfacedIt() {
        val presentation =
            canonicalizeRuntimeModelPresentation(
                runtimeId = "codex",
                models =
                    listOf(
                        RuntimeModelInfo("gpt-5.5", "GPT-5.5"),
                        RuntimeModelInfo("gpt-5.6-sol", "GPT-5.6 Sol"),
                        RuntimeModelInfo("gpt-5.6-terra", "GPT-5.6 Terra"),
                        RuntimeModelInfo("gpt-5.6-luna", "GPT-5.6 Luna"),
                        RuntimeModelInfo("gpt-5.4", "GPT-5.4"),
                        RuntimeModelInfo("host-only", "Host only")
                    ),
                machineDefault = "gpt-5.5"
            )

        assertEquals("gpt-5.6-sol", presentation.defaultModel)
        assertEquals(
            listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
                "gpt-5.4",
                "host-only"
            ),
            presentation.models.map { it.id }
        )
    }

    @Test
    fun codexPresentationFallsBackToMachineDefaultWhenSolIsMissing() {
        val presentation =
            canonicalizeRuntimeModelPresentation(
                runtimeId = "codex",
                models =
                    listOf(
                        RuntimeModelInfo("gpt-5.5", "GPT-5.5"),
                        RuntimeModelInfo("gpt-5.4", "GPT-5.4")
                    ),
                machineDefault = "gpt-5.5"
            )

        assertEquals("gpt-5.5", presentation.defaultModel)
    }

    @Test
    fun getModelLabelFallsBackToId() {
        assertEquals("Claude Sonnet", getModelLabel("claude", "sonnet"))
        assertEquals("custom-id", getModelLabel("claude", "custom-id"))
    }

    @Test
    fun freePlanLimitsAgentsToFive() {
        val limits = getEffectiveLimits(ServerPlan.free)
        assertEquals(5, limits.maxAgents)
        assertEquals(2, limits.maxMachines)
    }

    @Test
    fun founderPlanLimitsAreUnlimited() {
        val limits = getEffectiveLimits(ServerPlan.founder)
        assertEquals(-1, limits.maxAgents)
        assertEquals(-1, limits.maxMachines)
    }

    @Test
    fun capabilityPredicatesAreCorrect() {
        assertTrue(supportsRuntimeFastMode("claude"))
        assertTrue(supportsRuntimeFastMode("codex"))
        assertFalse(supportsRuntimeFastMode("opencode"))
        assertTrue(supportsRuntimeReasoningEffort("builtin"))
        assertTrue(supportsRuntimeReasoningEffort("grok"))
        assertFalse(supportsRuntimeReasoningEffort("opencode"))
    }

    // ── JSON round-trip ──

    @Test
    fun runtimeConfigJsonRoundTrips() {
        val cfg =
            RuntimeConfig(
                runtime = "claude",
                model = RuntimeModelConfig.Preset("sonnet"),
                mode = RuntimeModeConfig.Fast,
                reasoningEffort = "high",
                envVars = mapOf("FOO" to "bar")
            )
        val json = Json.encodeToString(RuntimeConfig.serializer(), cfg)
        val decoded = Json.decodeFromString(RuntimeConfig.serializer(), json)
        assertEquals(cfg, decoded)
        // RuntimeConfig must mirror the TypeScript contract: tagged unions use
        // a lowercase `kind`, not Kotlin class names.
        assertTrue(json.contains(""""mode":{"kind":"fast"}"""), "json should carry mode=fast, was: $json")
        assertTrue(json.contains(""""model":{"kind":"preset","id":"sonnet"}"""), "json should carry model preset, was: $json")
        assertTrue(json.contains("\"reasoningEffort\":\"high\""), "json should carry reasoningEffort=high")
    }
}
