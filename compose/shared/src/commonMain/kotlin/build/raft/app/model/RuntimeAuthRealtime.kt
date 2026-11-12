package build.raft.app.model

import kotlinx.serialization.Serializable

/*
 * Signing a Computer's runtimes in and out from the app (botiverse/slock
 * docs/contracts/runtime-auth.md; packages/shared/src/runtimeAuth.ts, staging
 * 853c6998bcef45df5c25e63b1035e2388608721d). The server relays these on the socket:
 *
 * - `machine:runtime_auth:updated {machineId}` to the server room
 *   (packages/server/src/services/agentOrchestrator.ts): the Computer sent a new sign-in status;
 *   content-free, every viewer reads `GET …/runtime-auth` again.
 * - `runtime_auth:login:event` / `runtime_auth:login:result`
 *   `{serverId, machineId, runtime, loginId, event | result}` to the person who started that
 *   login only (`user:<id>`, `relayRuntimeAuthLogin`).
 */

/** `machine:runtime_auth:updated`: [machineId]'s runtime sign-in status changed; read it again. */
@Serializable
data class RuntimeAuthUpdated(val machineId: String) : RealtimeEvent

/** `runtime_auth:login:event`: what the person sees while a login runs. Never a token or a pasted code. */
@Serializable
data class RuntimeAuthLoginEventPushed(
    val serverId: String,
    val machineId: String,
    /** The auth runtime's wire id (slock `RuntimeAuthRuntime`: `claude`, `codex`, `kimi`, ...). */
    val runtime: String,
    val loginId: String,
    val event: RuntimeAuthLoginStep,
) : RealtimeEvent

/** `runtime_auth:login:result`: how the login ended. */
@Serializable
data class RuntimeAuthLoginResultPushed(
    val serverId: String,
    val machineId: String,
    val runtime: String,
    val loginId: String,
    val result: RuntimeAuthLoginOutcome,
) : RealtimeEvent

/** slock `runtimeAuthAccountSchema`: the account as shown, its email masked on the Computer. */
@Serializable
data class RuntimeAuthAccountLabel(
    val maskedLabel: String? = null,
    val planLabel: String? = null,
    val methodLabel: String? = null,
) {
    /** Web `accountText`: "masked email · plan", or null when neither is known. */
    val text: String?
        get() = listOfNotNull(maskedLabel, planLabel).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** slock `runtimeAuthLoginEventSchema`. */
@Serializable
sealed interface RuntimeAuthLoginStep {
    /** A sign-in page to open (https only). The runtime's own `instructions` are relayed, not shown (web). */
    @Serializable
    data class AuthUrl(val url: String) : RuntimeAuthLoginStep

    /** A code to enter on [verificationUri]; nothing is pasted back. */
    @Serializable
    data class DeviceCode(val userCode: String, val verificationUri: String, val expiresInSeconds: Int? = null) : RuntimeAuthLoginStep

    @Serializable
    data class Info(val message: String) : RuntimeAuthLoginStep

    /** The runtime waits for the code its sign-in page shows (Claude; Antigravity also takes the landing address). */
    @Serializable
    data class CodePrompt(val message: String? = null, val retry: Boolean = false) : RuntimeAuthLoginStep
}

/** slock `runtimeAuthLoginResultSchema`. */
@Serializable
sealed interface RuntimeAuthLoginOutcome {
    @Serializable
    data class LoggedIn(val account: RuntimeAuthAccountLabel? = null) : RuntimeAuthLoginOutcome

    /** [reason]: `timed_out`, `rejected`, `not_logged_in`, `interaction_failed`, `process_failed`. */
    @Serializable
    data class Failed(val reason: String) : RuntimeAuthLoginOutcome

    @Serializable
    data object Cancelled : RuntimeAuthLoginOutcome

    /** [reason]: `unsupported_installation`, `version_unsupported`, `not_installed`. */
    @Serializable
    data class Unsupported(val reason: String) : RuntimeAuthLoginOutcome

    /** Another login for this runtime is already running on the Computer. */
    @Serializable
    data object Busy : RuntimeAuthLoginOutcome
}
