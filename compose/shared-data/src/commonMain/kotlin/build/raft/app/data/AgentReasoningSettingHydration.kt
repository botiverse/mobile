package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.AgentReasoningSetting
import build.raft.app.model.RUNTIME_CONFIG_VERSION
import build.raft.app.reportUnknownTypeDropped
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.serialization.Serializable

/**
 * The fields of an agent read's `runtimeConfig` its reasoning is read from (shared `RuntimeConfig`).
 * The server sends the hydrated config on `GET /agents`, its sync rows and `GET /agents/:id` to
 * viewers who may edit the agent, and strips it for everyone else (botiverse/slock
 * `routes/agents.ts` `withAgentProjection` / `stripEnvVars`).
 */
@Serializable
@RaftCoreInternalApi data class AgentRuntimeConfigReasoningFields(
    val version: Int? = null,
    val runtime: String? = null,
    val reasoningEffort: String? = null,
)

/**
 * Shared `hydrateRuntimeConfig` (`hydrateLegacyRuntimeConfigWithTrace`, which web
 * `hydrateRuntimeConfigForm` wraps) narrowed to the runtime and the reasoning effort: the one read
 * behind the profile's Reasoning row and the Members list's (botiverse/slock #15691).
 * - A saved [config] counts only at [RUNTIME_CONFIG_VERSION]; one of another version is ignored.
 * - The runtime is the saved config's, else the agent's own [runtime], else `claude`.
 * - The level is the saved config's, else (none in it) the agent's own [reasoningEffort]. That
 *   legacy column never holds Pi's `off` / `minimal` or Kimi Code's levels: they live in the config
 *   alone.
 */
@RaftCoreInternalApi fun hydrateAgentReasoningSetting(
    config: AgentRuntimeConfigReasoningFields?,
    runtime: String?,
    reasoningEffort: String?,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): AgentReasoningSetting {
    val stored = config?.takeIf { it.version == RUNTIME_CONFIG_VERSION }
    if (config != null && stored == null) {
        reportUnknownTypeDropped(
            source = "agent_runtime_config",
            field = "version",
            value = config.version?.toString(),
            fallback = "agent_fields",
            diagnostics = diagnostics,
        )
    }
    return AgentReasoningSetting(
        runtime =
            stored?.runtime?.trim()?.takeIf { it.isNotEmpty() }
                ?: runtime?.trim()?.takeIf { it.isNotEmpty() }
                ?: "claude",
        reasoningEffort = stored?.reasoningEffort ?: reasoningEffort,
    )
}
