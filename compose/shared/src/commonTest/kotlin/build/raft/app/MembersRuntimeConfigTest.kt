package build.raft.app

import build.raft.app.data.AgentResetMode
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersRuntimeConfigTest {
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

        assertEquals("Switch Runtime Config", confirmation?.title)
        assertEquals("Reset Runtime Session", confirmation?.confirmLabel)
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

        assertEquals("Save Runtime Change", confirmation?.confirmLabel)
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
