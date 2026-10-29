package build.raft.app

import build.raft.app.resources.*
import build.raft.app.model.AgentStatus
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.data.AgentDetailSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun unknownRuntimeStatusBlocksMutationInsteadOfActingInactive() {
        assertFalse(membersAgentRuntimeStatusResolved(null))
        assertTrue(membersAgentRuntimeStatusResolved(AgentStatus.Offline))
        assertTrue(membersAgentRuntimeStatusResolved(AgentStatus.Working))

        assertFalse(membersAgentRuntimeActive(null))
        assertFalse(membersAgentRuntimeActive(AgentStatus.Offline))
        assertTrue(membersAgentRuntimeActive(AgentStatus.Working))
    }

}
