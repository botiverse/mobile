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
        // slock packages/shared/src/runtimeCatalog.ts RUNTIMES at origin/staging 0b369d1aef (as at f5fe2ca864).
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
        assertEquals("default", getDefaultModel("kimi"))
    }

    @Test
    fun theModelTableMirrorsSlockStaging() {
        // slock packages/shared/src/index.ts RUNTIME_MODELS at origin/staging 0b369d1aef.
        assertEquals(
            listOf("external", "builtin", "claude", "codex", "grok", "antigravity", "copilot", "cursor", "gemini", "opencode", "pi", "kimi", "kimi-sdk"),
            RUNTIME_MODELS.keys.toList()
        )
        // Defaults (the first row): none of these falls through to Claude's "sonnet" any more.
        assertEquals("gpt-6.1-sol", getDefaultModel("codex"))
        assertEquals("grok-4.5", getDefaultModel("grok"))
        assertEquals("deepseek/deepseek-v4-pro", getDefaultModel("builtin"))
        assertEquals("kimi-code/kimi-for-coding", getDefaultModel("kimi-sdk"))
        assertEquals("external", getDefaultModel("external"))
        assertEquals("External agent", getModelLabel("external", "external"))
        assertEquals(
            listOf(
                "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                "gpt-5.5", "gpt-5.4", "gpt-5.3-codex", "gpt-5.3-codex-spark", "gpt-5.2-codex", "gpt-5.2",
                "gpt-5.1-codex-max", "gpt-5.1-codex", "gpt-5-codex", "gpt-5"
            ),
            RUNTIME_MODELS.getValue("codex").map { it.id }
        )
        val sol61 = RUNTIME_MODELS.getValue("codex").first()
        assertEquals(
            RuntimeModelInfo(
                "gpt-6.1-sol",
                "GPT-6.1 Sol",
                verified = "launchable",
                supportedReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
                defaultReasoningEffort = "low"
            ),
            sol61
        )
        assertEquals("GPT-6-Astra", getModelLabel("codex", "gpt-6-astra"))
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), RUNTIME_MODELS.getValue("codex").single { it.id == "gpt-6-luna" }.supportedReasoningEfforts)
        assertEquals(
            listOf(
                RuntimeModelInfo("grok-4.5", "Grok 4.5", verified = "launchable", supportedReasoningEfforts = listOf("high", "medium", "low"), defaultReasoningEffort = "high"),
                RuntimeModelInfo("grok-4.6", "Grok 4.6", verified = "suggestion_only"),
                RuntimeModelInfo("grok-composer-2.5-fast", "Composer 2.5", verified = "launchable")
            ),
            RUNTIME_MODELS.getValue("grok")
        )
        // Built-in Pi: every Pi provider's models (slock PI_BUILTIN_PROVIDER_MODELS), all launchable.
        val builtin = RUNTIME_MODELS.getValue("builtin")
        assertEquals(576, builtin.size)
        assertEquals(builtin.size, builtin.map { it.id }.toSet().size)
        assertTrue(builtin.all { it.verified == "launchable" && it.label.isNotBlank() })
        assertEquals("DeepSeek V4.1 Flash", getModelLabel("builtin", "deepseek/deepseek-flash"))
        // OpenCode: slock formatRuntimeProviderModelLabel.
        assertEquals("Claude Opus 4.5 · Anthropic via OpenRouter", getModelLabel("opencode", "openrouter/anthropic/claude-opus-4.5"))
    }

    @Test
    fun codexPresentationPrefersTheBundledDefaultAndOrdersPresetsWhenMachineSurfacedIt() {
        val presentation =
            canonicalizeRuntimeModelPresentation(
                runtimeId = "codex",
                models =
                    listOf(
                        RuntimeModelInfo("gpt-5.5", "GPT-5.5"),
                        RuntimeModelInfo("gpt-5.6-sol", "GPT-5.6 Sol"),
                        RuntimeModelInfo("gpt-6-luna", "GPT-6 Luna"),
                        RuntimeModelInfo("gpt-6.1-sol", "GPT-6.1 Sol"),
                        RuntimeModelInfo("gpt-6-astra", "GPT-6-Astra"),
                        RuntimeModelInfo("gpt-5.4", "GPT-5.4"),
                        RuntimeModelInfo("host-only", "Host only")
                    ),
                machineDefault = "gpt-5.5"
            )

        assertEquals("gpt-6.1-sol", presentation.defaultModel)
        assertEquals(
            listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.5", "gpt-5.4", "host-only"),
            presentation.models.map { it.id }
        )
    }

    @Test
    fun codexPresentationWithoutTheBundledDefaultKeepsTheMachineDefault() {
        // web canonicalizeCodexPresentation: GPT-5.6 Sol is no longer the preferred model; a machine
        // that lacks GPT-6.1 Sol keeps its own default.
        val presentation =
            canonicalizeRuntimeModelPresentation(
                runtimeId = "codex",
                models = listOf(RuntimeModelInfo("gpt-5.5", "GPT-5.5"), RuntimeModelInfo("gpt-5.6-sol", "GPT-5.6 Sol")),
                machineDefault = "gpt-5.5"
            )
        assertEquals("gpt-5.5", presentation.defaultModel)
        assertEquals(listOf("gpt-5.6-sol", "gpt-5.5"), presentation.models.map { it.id })
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
        // slock #14619: Antigravity's effort is agy's thought level, only for a model that lists levels.
        assertTrue(supportsRuntimeReasoningEffort("antigravity"))
        assertFalse(runtimeOffersUndeclaredReasoningEfforts("antigravity"))
        assertTrue(runtimeOffersUndeclaredReasoningEfforts("claude"))
        assertEquals("default", runtimeChoiceModel("antigravity")?.id)
        assertNull(runtimeChoiceModel("claude"))
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
