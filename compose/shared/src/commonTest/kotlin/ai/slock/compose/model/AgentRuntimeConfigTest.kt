package ai.slock.compose.model

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
    fun runtimesContainCanonicalNine() {
        val ids = RUNTIMES.map { it.id }
        assertTrue("claude" in ids)
        assertTrue("codex" in ids)
        assertTrue("opencode" in ids)
        assertEquals(9, ids.size)
    }

    @Test
    fun getDefaultModelFallsBackToSonnetForUnknownRuntime() {
        assertEquals("sonnet", getDefaultModel("unknown-runtime"))
        assertEquals("opus", getDefaultModel("claude"))
        assertEquals("default", getDefaultModel("kimi"))
    }

    @Test
    fun getModelLabelFallsBackToId() {
        assertEquals("Claude Sonnet", getModelLabel("claude", "sonnet"))
        assertEquals("custom-id", getModelLabel("claude", "custom-id"))
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
        assertTrue(supportsRuntimeCustomModelName("cursor"))
        assertTrue(supportsRuntimeCustomModelName("pi"))
        assertFalse(supportsRuntimeCustomModelName("opencode"))
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
