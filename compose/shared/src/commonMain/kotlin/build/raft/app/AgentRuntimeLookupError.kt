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
private val AgentRuntimeAuthFailedPattern =
    Regex("(?:auth(?:entication|orization)? (?:failed|error|invalid)|invalid api key|unauthorized)", RegexOption.IGNORE_CASE)

/**
 * Web `RuntimeErrorKind` (classifyRuntimeError.ts): the runtime errors told in the app's own words.
 * [ToolArgsInvalid] comes only from the typed diagnostic, never from the message text.
 */
internal enum class AgentRuntimeErrorKind {
    NotLoggedIn,
    NotInstalled,
    AuthFailed,
    ToolArgsInvalid,
}

/** Web `classifyRuntimeError`: only these stable patterns, in this order; anything else is null. */
internal fun agentClassifyRuntimeError(message: String): AgentRuntimeErrorKind? =
    when {
        AgentRuntimeNotLoggedInPattern.containsMatchIn(message) -> AgentRuntimeErrorKind.NotLoggedIn
        AgentRuntimeNotInstalledPattern.containsMatchIn(message) -> AgentRuntimeErrorKind.NotInstalled
        AgentRuntimeAuthFailedPattern.containsMatchIn(message) -> AgentRuntimeErrorKind.AuthFailed
        else -> null
    }

/**
 * Web AgentDetailPanel `typedRuntimeErrorErrKind`: the kept error's typed diagnostic (#688), read
 * only when it has both a class and a reason.
 */
internal fun agentTypedRuntimeErrorKind(errorClass: String?, errorReason: String?): AgentRuntimeErrorKind? {
    if (errorClass.isNullOrBlank() || errorReason.isNullOrBlank()) return null
    return when {
        errorReason == "auth_failed" || errorClass == "AuthError" -> AgentRuntimeErrorKind.AuthFailed
        errorReason == "model_tool_args_invalid" || errorClass == "ToolArgumentParseError" -> AgentRuntimeErrorKind.ToolArgsInvalid
        else -> null
    }
}

/** What the agent page's error notice says (web AgentDetailPanel `activityErrorText`). */
internal sealed interface AgentRuntimeErrorDisplay {
    /** A runtime "not installed": how the start's lookup ended, with the runtime's name. */
    data class Lookup(val display: AgentRuntimeLookupDisplay, val runtimeName: String) : AgentRuntimeErrorDisplay

    /** A known runtime error in the app's words (web `RUNTIME_ERROR_LABEL_ID`). */
    data class Known(val kind: AgentRuntimeErrorKind) : AgentRuntimeErrorDisplay

    /** Anything else: "Error: [detail]", or "Error" without one (web `formatActivityText(..., "error", raw)`). */
    data class Raw(val detail: String) : AgentRuntimeErrorDisplay
}

/**
 * Web AgentDetailPanel `activityErrorText`, from [runtimeError] (the live error, or the one the
 * server kept): the typed diagnostic first, then the message patterns; a "not installed" says how
 * the start's lookup ended; anything else keeps the server's own text.
 */
internal fun agentRuntimeErrorDisplay(
    runtimeError: String,
    typedErrorClass: String?,
    typedErrorReason: String?,
    spawnFailure: AgentSpawnFailure?,
    runtimeName: String?,
): AgentRuntimeErrorDisplay {
    val kind =
        agentTypedRuntimeErrorKind(typedErrorClass, typedErrorReason)
            ?: runtimeError.takeIf { it.isNotBlank() }?.let(::agentClassifyRuntimeError)
    if (kind == AgentRuntimeErrorKind.NotInstalled && runtimeName != null) {
        val lookup =
            agentRuntimeLookupDisplayForReason(spawnFailure?.takeIf { it.reason == "runtime_not_found" }?.lookupReason)
        return AgentRuntimeErrorDisplay.Lookup(lookup, runtimeName)
    }
    if (kind != null) return AgentRuntimeErrorDisplay.Known(kind)
    return AgentRuntimeErrorDisplay.Raw(membersAgentActivityErrorDisplayDetail(runtimeError))
}

/** The notice's text for [display], in the web's words. */
@Composable
internal fun agentRuntimeErrorDisplayText(display: AgentRuntimeErrorDisplay): String =
    when (display) {
        is AgentRuntimeErrorDisplay.Lookup -> agentRuntimeLookupErrorText(display.display, display.runtimeName)
        is AgentRuntimeErrorDisplay.Known ->
            when (display.kind) {
                AgentRuntimeErrorKind.NotLoggedIn -> stringResource(Res.string.agent_runtime_error_not_logged_in)
                AgentRuntimeErrorKind.NotInstalled -> stringResource(Res.string.agent_runtime_error_not_installed)
                AgentRuntimeErrorKind.AuthFailed -> stringResource(Res.string.agent_runtime_error_auth_failed)
                AgentRuntimeErrorKind.ToolArgsInvalid -> stringResource(Res.string.agent_runtime_error_tool_args_invalid)
            }
        is AgentRuntimeErrorDisplay.Raw ->
            if (display.detail.isBlank()) {
                stringResource(Res.string.agent_activity_error)
            } else {
                stringResource(Res.string.agent_activity_error_with_detail, display.detail)
            }
    }

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
