package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.resources.*

/**
 * Web `RuntimeLookupDisplay` (classifyRuntimeError.ts, task #735): how a start that failed for a
 * runtime that is "not installed" is told. Only a confirmed miss may say the runtime is not there;
 * a timed-out, refused or unresolved lookup (and an older daemon that sends no verdict) each get
 * their own copy.
 */
internal enum class AgentRuntimeLookupDisplay {
    ConfirmedMissing,
    Timeout,
    PermissionDenied,
    Unconfirmed,
}

/** Web `runtimeLookupDisplayForReason`: detect-side and launch-side lookup reasons. */
internal fun agentRuntimeLookupDisplayForReason(reason: String?): AgentRuntimeLookupDisplay =
    when (reason) {
        "not_installed", "not_on_path" -> AgentRuntimeLookupDisplay.ConfirmedMissing
        "probe_timeout", "lookup_timed_out" -> AgentRuntimeLookupDisplay.Timeout
        "permission_denied", "lookup_permission_denied" -> AgentRuntimeLookupDisplay.PermissionDenied
        // probe_failed / lookup_failed / absent (older daemon) / an unknown value.
        else -> AgentRuntimeLookupDisplay.Unconfirmed
    }

private val AgentRuntimeNotLoggedInPattern = Regex("is not logged in", RegexOption.IGNORE_CASE)
private val AgentRuntimeNotInstalledPattern = Regex("not installed", RegexOption.IGNORE_CASE)

/**
 * Web AgentDetailPanel `startLookupDisplay`: when the agent's error is the runtime "not installed"
 * (web `classifyRuntimeError`, which tries "is not logged in" first), how the start's lookup ended,
 * read from the `runtime_not_found` start failure; null for every other error.
 */
internal fun agentStartLookupDisplay(runtimeError: String?, spawnFailure: AgentSpawnFailure?): AgentRuntimeLookupDisplay? {
    val error = runtimeError?.takeIf { it.isNotBlank() } ?: return null
    if (AgentRuntimeNotLoggedInPattern.containsMatchIn(error)) return null
    if (!AgentRuntimeNotInstalledPattern.containsMatchIn(error)) return null
    return agentRuntimeLookupDisplayForReason(
        spawnFailure?.takeIf { it.reason == "runtime_not_found" }?.lookupReason
    )
}

/**
 * Web `RUNTIME_LOOKUP_DISPLAY_MESSAGE_ID` with the runtime's name. A confirmed miss names the
 * runtime and the ways out (botiverse/slock #12729): install it there, move the agent to a
 * Computer that has it, or switch the agent's runtime.
 */
@Composable
internal fun agentRuntimeLookupErrorText(display: AgentRuntimeLookupDisplay, runtimeName: String): String =
    when (display) {
        AgentRuntimeLookupDisplay.ConfirmedMissing -> stringResource(Res.string.agent_runtime_error_not_installed_named, runtimeName)
        AgentRuntimeLookupDisplay.Timeout -> stringResource(Res.string.agent_runtime_lookup_timeout, runtimeName)
        AgentRuntimeLookupDisplay.PermissionDenied -> stringResource(Res.string.agent_runtime_lookup_permission_denied, runtimeName)
        AgentRuntimeLookupDisplay.Unconfirmed -> stringResource(Res.string.agent_runtime_lookup_unresolved, runtimeName)
    }
