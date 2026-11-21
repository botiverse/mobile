package build.raft.app.data

import build.raft.app.model.AgentReasoningSetting
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [hydrateAgentReasoningSetting] against shared `hydrateLegacyRuntimeConfigWithTrace`
 * (botiverse/slock staging c0f6195c6 `packages/shared/src/runtimeConfigLegacy.ts`) and the
 * hydration cases of web `membersAgentReasoningColumn.behavior.test.tsx` (#15691).
 */
class AgentReasoningSettingHydrationTest {
    private data class Case(
        val name: String,
        val config: AgentRuntimeConfigReasoningFields?,
        val runtime: String?,
        val reasoningEffort: String?,
        val expected: AgentReasoningSetting,
        /** A saved config this client ignores leaves a trace. */
        val reported: Boolean = false,
    )

    @Test
    fun readsTheRuntimeAndLevelAsTheProfileHydratesThem() {
        val cases =
            listOf(
                Case("saved level wins", config(runtime = "claude", effort = "xhigh"), "claude", "low", AgentReasoningSetting("claude", "xhigh")),
                // Pi's off and Kimi Code's levels live in the saved config alone.
                Case("level only in the config", config(runtime = "pi", effort = "off"), "pi", null, AgentReasoningSetting("pi", "off")),
                Case("saved runtime wins", config(runtime = "gemini"), "claude", "high", AgentReasoningSetting("gemini", "high")),
                Case("saved config without a level", config(runtime = "codex"), "codex", "low", AgentReasoningSetting("codex", "low")),
                Case("blank saved runtime", config(runtime = " ", effort = "high"), "codex", null, AgentReasoningSetting("codex", "high")),
                // A member's read: the server strips the config.
                Case("no config", null, "codex", "high", AgentReasoningSetting("codex", "high")),
                Case("missing runtime defaults", null, null, null, AgentReasoningSetting("claude", null)),
                Case(
                    "another version is ignored",
                    config(version = 2, runtime = "gemini", effort = "high"),
                    "claude",
                    "medium",
                    AgentReasoningSetting("claude", "medium"),
                    reported = true,
                ),
                Case("no version", config(version = null, effort = "high"), "codex", null, AgentReasoningSetting("codex", null), reported = true),
            )
        for (case in cases) {
            val lines = mutableListOf<String>()
            val setting = hydrateAgentReasoningSetting(case.config, case.runtime, case.reasoningEffort, diagnostics = { lines += it })
            assertEquals(case.expected, setting, case.name)
            assertEquals(
                if (case.reported) listOf("projection_value_unknown source=agent_runtime_config field=version") else emptyList(),
                lines.map { it.substringBefore(" value=") },
                case.name,
            )
        }
    }

    private fun config(version: Int? = 1, runtime: String? = null, effort: String? = null) =
        AgentRuntimeConfigReasoningFields(version = version, runtime = runtime, reasoningEffort = effort)
}
