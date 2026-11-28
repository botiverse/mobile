package build.raft.app.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRuntimeConfigTest {

    // ── validateAgentName ──

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
        assertEquals(
            "Agent name @raft is reserved. Choose another name.",
            validateAgentName("RAFT")
        )
        assertEquals(
            "Agent name @s1ack is reserved. Choose another name.",
            validateAgentName("s1ack")
        )
        assertEquals(
            "Agent name @officiai is reserved. Choose another name.",
            validateAgentName("officiaI")
        )
        // People-only reserved handles remain allowed for agents (#17291).
        assertEquals(null, validateAgentName("security"))
        assertEquals(null, validateAgentName("Adrnin"))
        assertEquals(null, validateAgentName("admin"))
        // Mixed-script agent names are rejected with UTS #39 copy (#17270).
        assertEquals(
            "Agent name must use letters from one script only (Latin may be combined with Chinese, Japanese or Korean)",
            validateAgentName("оwner")
        )
        assertEquals(null, validateAgentName("bot中文"))
        assertEquals(null, validateAgentName("ラフトー"))
    }

    // ── Runtimes / models ──

    @Test
    fun runtimesMirrorTheSharedCatalogAndMarkLegacyEntriesDeprecated() {
        // slock packages/shared/src/runtimeCatalog.ts RUNTIMES at origin/staging 114dcb89eb (#15445, #15549).
        val ids = RUNTIMES.map { it.id }
        assertEquals(
            listOf(
                "claude", "codex", "grok", "builtin", "antigravity", "kimi-sdk",
                "kimi", "copilot", "cursor", "gemini", "opencode", "pi", "qwen", "qoder", "dsh"
            ),
            ids
        )
        assertEquals(setOf("kimi", "gemini", "copilot"), RUNTIMES.filter { it.deprecated }.map { it.id }.toSet())
        assertEquals(setOf("antigravity", "qwen", "qoder", "dsh"), RUNTIMES.filter { it.experimental }.map { it.id }.toSet())
        assertEquals("DeepSeek Harness", getRuntimeDisplayName("dsh"))
        assertEquals("Cursor", getRuntimeDisplayName("cursor"))
        assertEquals("Cursor", RUNTIMES.single { it.id == "cursor" }.displayName)
        // DeepSeek Harness: effort per model, only the levels the Computer lists for it (slock #15549).
        assertTrue(supportsRuntimeReasoningEffort("dsh"))
        assertFalse(runtimeOffersUndeclaredReasoningEfforts("dsh"))
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
    fun freePlanLimitsAgentsToFive() {
        val limits = getEffectiveLimits(ServerPlan.free)
        assertEquals(5, limits.maxAgents)
        assertEquals(2, limits.maxMachines)
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
