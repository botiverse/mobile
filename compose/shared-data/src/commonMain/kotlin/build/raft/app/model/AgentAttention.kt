package build.raft.app.model

import kotlinx.serialization.Serializable

/**
 * Shared `NON_RETRYABLE_SPAWN_FAILURE_REASONS` (`botiverse/slock` `packages/shared/src/index.ts`):
 * start failures that retrying cannot fix — only a person signing in, fixing runtime/model/env
 * configuration, or resetting the session gets the agent running again.
 */
val NON_RETRYABLE_SPAWN_FAILURE_REASONS: Set<String> =
    setOf(
        "model_not_configured",
        "runtime_login_required",
        "runtime_config_invalid",
        "session_needs_reset",
        "session_runtime_mismatch",
        "env_var_not_allowed",
        "model_unsupported",
    )

/**
 * Web `AgentAttention` (`packages/web/src/utils/activity.ts`, botiverse/slock #17124):
 * why an agent that is not running needs a person when neither its activity nor a `blocked`
 * state says so. Every status surface paints the error light and reads its short title (or
 * `"title: detail"` for accessible names and full status lines).
 */
@Serializable
sealed interface AgentAttention {
    /** Server-composed readiness is `blocked` (excluding `computer_offline`, which stays stone). */
    @Serializable
    data class Readiness(
        val readiness: AgentReadiness,
        val runtimeId: String? = null,
    ) : AgentAttention

    /**
     * Offline, non-stopped agent whose stored failed start (`lastRuntimeError.spawnFailureReason`)
     * is in [NON_RETRYABLE_SPAWN_FAILURE_REASONS].
     */
    @Serializable
    data class StartFailure(
        val spawnFailure: AgentSpawnFailure,
    ) : AgentAttention
}

/**
 * Web `storedStartFailureAttentionOf` (`packages/web/src/utils/activity.ts`, botiverse/slock #17124):
 * a stored failed start only a person gets out of, as an [AgentSpawnFailure] carrier for its copy.
 * Uses [liveSpawnFailure] when it carries the same reason (it knows the model and pause); otherwise
 * synthesizes a paused carrier from [lastRuntimeErrorSpawnFailureReason] and [model].
 */
fun storedStartFailureAttentionOf(
    lastRuntimeErrorSpawnFailureReason: String?,
    liveSpawnFailure: AgentSpawnFailure?,
    model: String?,
): AgentSpawnFailure? {
    val reason = lastRuntimeErrorSpawnFailureReason?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (reason !in NON_RETRYABLE_SPAWN_FAILURE_REASONS) return null
    if (liveSpawnFailure?.reason?.trim() == reason) return liveSpawnFailure
    return AgentSpawnFailure(
        reason = reason,
        model = model?.trim()?.takeIf { it.isNotEmpty() },
        autoRestartPaused = true,
    )
}
