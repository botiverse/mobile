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
    fun runtimesContainCanonicalTwelveAndMarkLegacyEntriesDeprecated() {
        val ids = RUNTIMES.map { it.id }
        assertEquals(
            listOf(
                "claude", "codex", "grok", "builtin", "antigravity", "kimi-sdk",
                "kimi", "copilot", "cursor", "gemini", "opencode", "pi"
            ),
            ids
        )
        assertEquals(setOf("kimi", "gemini", "copilot"), RUNTIMES.filter { it.deprecated }.map { it.id }.toSet())
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
    fun getModelLabelNamesVersionedClaudeModelsMissingFromTheTable() {
        assertEquals("Claude Opus 5.5", getModelLabel("claude", "claude-opus-5-5"))
        assertEquals("Claude Fable 5.1", getModelLabel("claude", "claude-fable-5-1"))
        assertEquals("Claude Opus 4.8", getModelLabel("claude", "claude-opus-4-8"))
        assertEquals("claude-opus-5-5", getModelLabel("codex", "claude-opus-5-5"))
        assertEquals("claude-opus-5-5[1m]", getModelLabel("claude", "claude-opus-5-5[1m]"))
    }

    @Test
    fun capabilityPredicatesAreCorrect() {
        assertTrue(supportsRuntimeApiUrl("claude"))
        assertFalse(supportsRuntimeApiUrl("codex"))
        assertTrue(supportsRuntimeCommand("claude"))
        assertFalse(supportsRuntimeCommand("codex"))
        assertTrue(supportsRuntimeFastMode("claude"))
        assertTrue(supportsRuntimeFastMode("codex"))
        assertFalse(supportsRuntimeFastMode("opencode"))
        assertTrue(supportsRuntimeCustomModelName("claude"))
        assertTrue(supportsRuntimeCustomModelName("codex"))
        assertTrue(supportsRuntimeCustomModelName("builtin"))
        assertTrue(supportsRuntimeCustomModelName("copilot"))
        assertTrue(supportsRuntimeCustomModelName("cursor"))
        assertTrue(supportsRuntimeCustomModelName("pi"))
        assertFalse(supportsRuntimeCustomModelName("opencode"))
        assertFalse(supportsRuntimeCustomModelName("gemini"))
        assertFalse(supportsRuntimeCustomModelName("antigravity"))
        assertFalse(supportsRuntimeCustomModelName("machine-runtime"))
        assertFalse(supportsRuntimeCustomModelName(""))
        assertTrue(supportsRuntimeReasoningEffort("builtin"))
        assertTrue(supportsRuntimeReasoningEffort("grok"))
        assertFalse(supportsRuntimeReasoningEffort("opencode"))
    }

    @Test
    fun reasoningReconciliationUsesTargetModelCapabilities() {
        assertNull(
            reconcileRuntimeReasoningEffort(
                runtime = "claude",
                model = "opus",
                current = "ultra"
            )
        )
        assertEquals(
            "high",
            reconcileRuntimeReasoningEffort(
                runtime = "claude",
                model = "opus",
                current = "high"
            )
        )
        assertEquals(
            "medium",
            reconcileRuntimeReasoningEffort(
                runtime = "codex",
                model = "gpt-5.6-luna",
                current = "ultra"
            )
        )
        assertEquals(
            "ultra",
            reconcileRuntimeReasoningEffort(
                runtime = "codex",
                model = "gpt-5.6-sol",
                current = "ultra"
            )
        )
    }

    // ── buildRuntimeConfig ──

    @Test
    fun buildRuntimeConfigDefaultsToClaudePreset() {
        val cfg = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = false
        )
        assertEquals("claude", cfg.runtime)
        assertEquals(RuntimeModelConfig.Preset("sonnet"), cfg.model)
        assertEquals(RuntimeModeConfig.Default, cfg.mode)
        // Mirrors React `buildRuntimeConfig` which emits provider: { kind: "default" }
        // whenever the runtime supports API URL, even without an explicit provider URL.
        assertEquals(RuntimeProviderConfig.Default, cfg.provider)
    }

    @Test
    fun buildRuntimeConfigSwitchesToCustomModel() {
        val cfg = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = true,
            customModelName = "my-custom-sonnet"
        )
        assertEquals(RuntimeModelConfig.Custom("my-custom-sonnet"), cfg.model)
    }

    @Test
    fun buildRuntimeConfigFastModeOnlyForSupportedRuntimes() {
        val fastOnClaude = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = false,
            fastMode = true
        )
        assertEquals(RuntimeModeConfig.Fast, fastOnClaude.mode)

        val fastOnOpencode = buildRuntimeConfig(
            runtime = "opencode",
            model = "default",
            customModelMode = false,
            fastMode = true
        )
        assertEquals(RuntimeModeConfig.Default, fastOnOpencode.mode)
    }

    @Test
    fun buildRuntimeConfigCustomProviderOnlyForClaude() {
        val claudeCustom = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = false,
            providerMode = RUNTIME_PROVIDER_MODE_CUSTOM,
            providerApiUrl = "https://api.example.com",
            providerApiKey = "sk_test_123"
        )
        assertEquals(
            RuntimeProviderConfig.Custom("https://api.example.com", "sk_test_123"),
            claudeCustom.provider
        )

        val codexDefault = buildRuntimeConfig(
            runtime = "codex",
            model = "gpt-5.4",
            customModelMode = false,
            providerMode = RUNTIME_PROVIDER_MODE_CUSTOM,
            providerApiUrl = "https://api.example.com",
            providerApiKey = "sk_test_123"
        )
        // codex does not support provider URL, so provider is null
        assertNull(codexDefault.provider)
    }

    @Test
    fun buildRuntimeConfigUsesConfiguredOrBuiltInPiProvider() {
        val configured = buildRuntimeConfig(
            runtime = "pi",
            model = "default",
            customModelMode = false,
            piProviderId = "configured",
            piProviderApiKey = "ignored"
        )
        assertEquals(RuntimeProviderConfig.Default, configured.provider)

        val deepSeek = buildRuntimeConfig(
            runtime = "pi",
            model = "deepseek/deepseek-v4-pro",
            customModelMode = false,
            piProviderId = " deepseek ",
            piProviderApiKey = " secret-value "
        )
        assertEquals(RuntimeProviderConfig.PiBuiltin("deepseek", "secret-value"), deepSeek.provider)
    }

    @Test
    fun buildRuntimeConfigIncludesCommandOnlyForClaude() {
        val claudeWithCmd = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = false,
            command = "/usr/local/bin/claude"
        )
        assertEquals("/usr/local/bin/claude", claudeWithCmd.command)

        val codexWithCmd = buildRuntimeConfig(
            runtime = "codex",
            model = "gpt-5.4",
            customModelMode = false,
            command = "/usr/local/bin/codex"
        )
        assertNull(codexWithCmd.command)
    }

    @Test
    fun buildRuntimeConfigIncludesReasoningOnlyForSupportedRuntimes() {
        val codexConfig = buildRuntimeConfig(
            runtime = "codex",
            model = "gpt-5.4",
            customModelMode = false,
            reasoningEffort = "high"
        )
        assertEquals("high", codexConfig.reasoningEffort)

        val claudeConfig = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = false,
            reasoningEffort = "high"
        )
        assertEquals("high", claudeConfig.reasoningEffort)

        val grokConfig = buildRuntimeConfig(
            runtime = "grok",
            model = "default",
            customModelMode = false,
            reasoningEffort = "medium"
        )
        assertEquals("medium", grokConfig.reasoningEffort)

        val builtInConfig = buildRuntimeConfig(
            runtime = "builtin",
            model = "default",
            customModelMode = false,
            reasoningEffort = "low"
        )
        assertEquals("low", builtInConfig.reasoningEffort)
    }

    @Test
    fun buildRuntimeConfigFailsClosedAgainstTheSubmittedModelCapabilities() {
        val claude =
            buildRuntimeConfig(
                runtime = "claude",
                model = "opus",
                customModelMode = false,
                reasoningEffort = "ultra"
            )
        val luna =
            buildRuntimeConfig(
                runtime = "codex",
                model = "gpt-5.6-luna",
                customModelMode = false,
                reasoningEffort = "ultra"
            )
        val sol =
            buildRuntimeConfig(
                runtime = "codex",
                model = "gpt-5.6-sol",
                customModelMode = false,
                reasoningEffort = "ultra"
            )
        val live =
            buildRuntimeConfig(
                runtime = "codex",
                model = "machine-codex",
                customModelMode = false,
                reasoningEffort = "ultra",
                reasoningSupportedEfforts = listOf("low", "high", "max"),
                reasoningDefaultEffort = "high"
            )

        assertNull(claude.reasoningEffort)
        assertEquals("medium", luna.reasoningEffort)
        assertEquals("ultra", sol.reasoningEffort)
        assertEquals("high", live.reasoningEffort)
    }

    @Test
    fun buildRuntimeConfigPreservesExplicitDefaultAcrossOpenSave() {
        val initial =
            RuntimeConfig(
                runtime = "codex",
                model = RuntimeModelConfig.Preset("gpt-5.6-sol"),
                reasoningEffort = null
            )
        val saved =
            buildRuntimeConfig(
                runtime = initial.runtime,
                model = (initial.model as RuntimeModelConfig.Preset).id,
                customModelMode = false,
                reasoningEffort = initial.reasoningEffort,
                reasoningSupportedEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
                reasoningDefaultEffort = "medium"
            )

        assertEquals(initial, saved)
    }

    // ── Plan limits ──

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

    // ── JSON round-trip ──

    @Test
    fun runtimeConfigJsonRoundTrips() {
        val cfg = buildRuntimeConfig(
            runtime = "claude",
            model = "sonnet",
            customModelMode = false,
            fastMode = true,
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
