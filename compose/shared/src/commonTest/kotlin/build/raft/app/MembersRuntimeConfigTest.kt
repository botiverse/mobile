package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.AgentResetMode
import build.raft.app.model.AgentStatus
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.data.AgentDetailSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersRuntimeConfigTest {
    @Test
    fun runtimeResolutionKeepsNonSecretBuiltInProviderIdentityForEditHydration() {
        val provider = RuntimeProviderConfig.Gateway(
            "openai-compatible",
            "https://gateway.example.test/v1",
            "",
            supportsImageInput = true
        )
        val resolution =
            AgentDetailSummary(
                id = "agent-1",
                name = "Agent One",
                runtime = "builtin",
                model = "vendor/model",
                runtimeModelKind = "custom",
                runtimeProvider = provider
            ).toMembersRuntimeConfigResolution()

        val config = (resolution as MembersAgentRuntimeConfigResolution.Content).config
        assertEquals(provider, config.provider)
        assertEquals(RuntimeModelConfig.Custom("vendor/model"), config.model)
    }

    @Test
    fun builtInWriteOnlyKeyRetainsOnlyForExactProviderIdentity() {
        val preset =
            RuntimeConfig(
                runtime = "builtin",
                provider = RuntimeProviderConfig.Preset("deepseek", ""),
                model = RuntimeModelConfig.Preset("deepseek/deepseek-v4-pro")
            )
        assertTrue(membersCanRetainBuiltInProviderSecret(preset, "builtin", "deepseek", "", ""))
        assertEquals(
            false,
            membersCanRetainBuiltInProviderSecret(preset, "builtin", "openai", "", "")
        )
        assertEquals(
            false,
            membersCanRetainBuiltInProviderSecret(preset, "builtin", "deepseek", "", "new-secret")
        )

        val gateway =
            RuntimeConfig(
                runtime = "builtin",
                provider = RuntimeProviderConfig.Gateway(
                    "openai-compatible",
                    "https://gateway.example.test/v1",
                    ""
                ),
                model = RuntimeModelConfig.Custom("vendor/model")
            )
        assertTrue(
            membersCanRetainBuiltInProviderSecret(
                gateway,
                "builtin",
                "openai-compatible",
                "https://gateway.example.test/v1",
                ""
            )
        )
        assertEquals(
            false,
            membersCanRetainBuiltInProviderSecret(
                gateway,
                "builtin",
                "openai-compatible",
                "https://other.example.test/v1",
                ""
            )
        )
    }

    @Test
    fun runtimeModelKindPreservesPresetVsCustomIdentity() {
        assertEquals(
            RuntimeModelConfig.Preset("minimax/MiniMax-M2.7"),
            membersRuntimeModelConfig("builtin", "minimax/MiniMax-M2.7", "preset")
        )
        assertEquals(
            RuntimeModelConfig.Custom("vendor/private-model"),
            membersRuntimeModelConfig("claude", "vendor/private-model", "custom")
        )
        assertEquals(
            RuntimeModelConfig.Preset("gpt-5.6-sol"),
            membersRuntimeModelConfig("codex", "gpt-5.6-sol", null)
        )
        assertEquals(
            RuntimeModelConfig.Custom("legacy/private-model"),
            membersRuntimeModelConfig("codex", "legacy/private-model", null)
        )
    }

    @Test
    fun runtimeChangeUsesSessionResetEvenWhenAgentIsInactive() {
        val current = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("sonnet")
        )
        val next = RuntimeConfig(
            runtime = "codex",
            model = RuntimeModelConfig.Preset("gpt-5")
        )

        assertEquals(AgentResetMode.Session, membersRuntimeConfigResetMode(current, next, activeAgent = false))
    }

    @Test
    fun sameRuntimeChangeOnlyRestartsActiveAgent() {
        val current = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("sonnet")
        )
        val next = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("opus")
        )

        assertEquals(AgentResetMode.Restart, membersRuntimeConfigResetMode(current, next, activeAgent = true))
        assertNull(membersRuntimeConfigResetMode(current, next, activeAgent = false))
    }

    @Test
    fun unknownRuntimeStatusBlocksMutationInsteadOfActingInactive() {
        assertFalse(membersAgentRuntimeStatusResolved(null))
        assertTrue(membersAgentRuntimeStatusResolved(AgentStatus.Offline))
        assertTrue(membersAgentRuntimeStatusResolved(AgentStatus.Working))

        assertFalse(membersAgentRuntimeActive(null))
        assertFalse(membersAgentRuntimeActive(AgentStatus.Offline))
        assertTrue(membersAgentRuntimeActive(AgentStatus.Working))
    }

    @Test
    fun activeRuntimeSwitchConfirmationMatchesReactResetCopy() {
        val current = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("sonnet")
        )
        val next = RuntimeConfig(
            runtime = "codex",
            model = RuntimeModelConfig.Preset("gpt-5"),
            mode = RuntimeModeConfig.Fast,
            reasoningEffort = "high"
        )

        val confirmation = membersRuntimeConfigSwitchConfirmation(current, next, activeAgent = true)

        assertEquals(Res.string.member_action_switch_runtime_config.desc(), confirmation?.title)
        assertEquals(Res.string.member_action_reset_runtime_session.desc(), confirmation?.confirmLabel)
        assertEquals(
            "Changing runtime configuration from Claude Code to Codex CLI will reset the current runtime session. " +
                "The next launch will use model GPT-5 with High reasoning in fast mode; MEMORY.md and workspace files are preserved.",
            confirmation?.message
        )
    }

    @Test
    fun inactiveRuntimeSwitchConfirmationMatchesReactFreshSessionCopy() {
        val current = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("sonnet")
        )
        val next = RuntimeConfig(
            runtime = "codex",
            model = RuntimeModelConfig.Preset("gpt-5"),
            reasoningEffort = "medium"
        )

        val confirmation = membersRuntimeConfigSwitchConfirmation(current, next, activeAgent = false)

        assertEquals(Res.string.member_action_save_runtime_change.desc(), confirmation?.confirmLabel)
        assertEquals(
            "Changing runtime configuration from Claude Code to Codex CLI will apply on the next start with a fresh runtime session. " +
                "The agent will launch with model GPT-5 and Medium reasoning; MEMORY.md and workspace files are preserved.",
            confirmation?.message
        )
    }

    @Test
    fun sameRuntimeDoesNotNeedSwitchConfirmation() {
        val current = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("sonnet")
        )
        val next = RuntimeConfig(
            runtime = "claude",
            model = RuntimeModelConfig.Preset("opus")
        )

        assertNull(membersRuntimeConfigSwitchConfirmation(current, next, activeAgent = true))
        assertTrue(membersRuntimeConfigSwitchConfirmation(current, next, activeAgent = false) == null)
    }
}
