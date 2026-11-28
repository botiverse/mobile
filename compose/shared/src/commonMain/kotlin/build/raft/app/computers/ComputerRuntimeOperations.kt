package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.data.RUNTIME_INSTALL_TIMEOUT_MILLIS
import build.raft.app.data.ResolvedServerRefusal
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeInstallFailureReason
import build.raft.app.data.RuntimeInstallPlan
import build.raft.app.data.RuntimeInstallResult
import build.raft.app.data.RuntimeInstallUnsupportedReason
import build.raft.app.data.RuntimeOperationErrorCode
import build.raft.app.data.RuntimeUpdateCheck
import build.raft.app.data.RuntimeUpgradeResult
import build.raft.app.data.RuntimeUpgradeUnsupportedReason
import build.raft.app.data.ServerRefusalInput
import build.raft.app.data.resolveServerRefusal
import build.raft.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/*
 * Upgrade and Install on the Computer page, as the web does (botiverse/slock #15117 940212580a and
 * #15214 ffbf283ad2: packages/web/src/features/computer/runtimeUpgradeOutcomeCopy.ts,
 * useRuntimeUpgrade.ts, runtimeInstallCopy.ts, useRuntimeInstall.ts; RuntimeUpgradeInstall.kt for the
 * wire). Raft never upgrades or installs a runtime on its own: only on the person's press, and an
 * install only once they read the commands it would run and confirmed.
 *
 * Upgrade follows the Computer's read-only update check (slock #15429 9523be7fc7, read at staging
 * 5881c7b53c: runtimeUpgradeOutcomeCopy.ts `runtimeUpdateCheckCopy` / `runtimeUpgradeButtonShown`).
 */

/** Why an upgrade or install request got no outcome from the Computer (web `runtimeUpgradeRefusal` / `runtimeInstallRefusal`). */
internal enum class ComputerRuntimeOperationRefusal {
    ComputerUpgradeRequired,
    ComputerOffline,

    /** The server's 504: only this one says it may still finish. */
    ComputerTimeout,

    /** The server's 429 (upgrades and installs per person and Computer): wait, not "try again" (web #15424). */
    RateLimited,
    Failed,
}

/** By the server's `code`; anything else (a network failure, an answer this app cannot read) is a plain failure. */
internal fun computerRuntimeOperationRefusal(code: String?): ComputerRuntimeOperationRefusal =
    when (code) {
        "computer_upgrade_required" -> ComputerRuntimeOperationRefusal.ComputerUpgradeRequired
        "computer_offline" -> ComputerRuntimeOperationRefusal.ComputerOffline
        "computer_timeout" -> ComputerRuntimeOperationRefusal.ComputerTimeout
        "rate_limited" -> ComputerRuntimeOperationRefusal.RateLimited
        else -> ComputerRuntimeOperationRefusal.Failed
    }

/** Resolves Tier 2 known codes first, then scrubbed Tier 3 server words for unrecognized 4xx refusals. */
internal fun RuntimeAuthRequestError.toOperationRefusal(): Pair<ComputerRuntimeOperationRefusal, String?> {
    val known = computerRuntimeOperationRefusal(code)
    if (known != ComputerRuntimeOperationRefusal.Failed) return known to null
    if (code == "malformed_response") return ComputerRuntimeOperationRefusal.Failed to null
    val httpStatus = status?.takeIf { it > 0 } ?: return ComputerRuntimeOperationRefusal.Failed to null
    val resolved =
        resolveServerRefusal(
            ServerRefusalInput(
                status = httpStatus,
                code = code,
                serverMessage = serverMessage,
                traceId = traceId,
            ),
        ) { _, _ -> null }
    val words = (resolved as? ResolvedServerRefusal.ServerWords)?.message
    return ComputerRuntimeOperationRefusal.Failed to words
}

/** What one press of Upgrade has learned (web `RuntimeUpgradeState`). */
internal sealed interface ComputerRuntimeUpgradeState {
    data object Idle : ComputerRuntimeUpgradeState

    data object Running : ComputerRuntimeUpgradeState

    data class Done(val result: RuntimeUpgradeResult) : ComputerRuntimeUpgradeState

    data class Refused(
        val reason: ComputerRuntimeOperationRefusal,
        val traceId: String? = null,
        val serverMessage: String? = null,
    ) : ComputerRuntimeUpgradeState
}

/**
 * The sentence an outcome reads as: [message] formatted with the runtime's name first, then [args];
 * [success] in ink, otherwise orange-strong (Raft DS: a problem is orange, never red).
 */
internal data class ComputerRuntimeOperationCopy(
    val message: StringResource,
    val args: List<String> = emptyList(),
    val success: Boolean,
    /** The failed request's trace id, shown under the sentence (AGENTS.md "Errors carry the trace ID"). */
    val traceId: String? = null,
    /** Scrubbed Tier 3 server refusal sentence when [message] is a generic fallback. */
    val serverMessage: String? = null,
)

/** Web `runtimeUpgradeCopy`: one plain sentence per outcome; null while idle or running. */
internal fun computerRuntimeUpgradeCopy(state: ComputerRuntimeUpgradeState): ComputerRuntimeOperationCopy? =
    when (state) {
        ComputerRuntimeUpgradeState.Idle, ComputerRuntimeUpgradeState.Running -> null
        is ComputerRuntimeUpgradeState.Refused ->
            ComputerRuntimeOperationCopy(
                when (state.reason) {
                    ComputerRuntimeOperationRefusal.ComputerUpgradeRequired -> Res.string.settings_computers_runtime_upgrade_computer_too_old
                    ComputerRuntimeOperationRefusal.ComputerOffline -> Res.string.settings_computers_runtime_upgrade_computer_offline
                    ComputerRuntimeOperationRefusal.ComputerTimeout -> Res.string.settings_computers_runtime_upgrade_timeout
                    // Pressing again won't help for a while (web: the sign-ins' wait sentence).
                    ComputerRuntimeOperationRefusal.RateLimited -> Res.string.settings_computers_runtime_auth_provider_error_rate_limited
                    ComputerRuntimeOperationRefusal.Failed -> Res.string.settings_computers_runtime_upgrade_error
                },
                success = false,
                traceId = state.traceId,
                serverMessage = state.serverMessage,
            )
        is ComputerRuntimeUpgradeState.Done ->
            when (val result = state.result) {
                is RuntimeUpgradeResult.Upgraded -> {
                    val from = result.from
                    val to = result.to
                    if (from != null && to != null) {
                        ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_upgraded_from_to, listOf(from, to), success = true)
                    } else {
                        ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_upgraded, success = true)
                    }
                }
                is RuntimeUpgradeResult.Current -> {
                    val version = result.version
                    if (version != null) {
                        ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_current_version, listOf(version), success = true)
                    } else {
                        ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_current, success = true)
                    }
                }
                is RuntimeUpgradeResult.Unchanged -> ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_unchanged, success = false)
                is RuntimeUpgradeResult.Failed -> ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_failed, success = false)
                is RuntimeUpgradeResult.Unsupported ->
                    ComputerRuntimeOperationCopy(
                        if (result.reason == RuntimeUpgradeUnsupportedReason.RequiresTerminal) {
                            Res.string.settings_computers_runtime_upgrade_requires_terminal
                        } else {
                            Res.string.settings_computers_runtime_upgrade_unsupported_installation
                        },
                        success = false,
                    )
                RuntimeUpgradeResult.NotInstalled -> ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_auth_error_not_installed, success = false)
                is RuntimeUpgradeResult.Error ->
                    ComputerRuntimeOperationCopy(
                        if (result.code == RuntimeOperationErrorCode.InProgress) {
                            Res.string.settings_computers_runtime_upgrade_in_progress
                        } else {
                            Res.string.settings_computers_runtime_upgrade_error
                        },
                        success = false,
                    )
            }
    }

/**
 * Web `upgradeAgainAfter` (slock #15482, RuntimeVersionLine.tsx): Upgrade is offered again after an
 * outcome only after a failure a second press may fix (the request failed, the Computer was offline,
 * an error other than one already running); never after it upgraded or found the latest, nor where
 * pressing again would not help (a timeout, too many upgrades, a Computer too old for it, an
 * installation it can't upgrade, a run that failed or left the version unchanged).
 */
internal fun computerRuntimeUpgradeAgainAfter(state: ComputerRuntimeUpgradeState): Boolean =
    when (state) {
        is ComputerRuntimeUpgradeState.Refused ->
            state.reason == ComputerRuntimeOperationRefusal.Failed || state.reason == ComputerRuntimeOperationRefusal.ComputerOffline
        is ComputerRuntimeUpgradeState.Done -> (state.result as? RuntimeUpgradeResult.Error)?.let { it.code != RuntimeOperationErrorCode.InProgress } == true
        else -> false
    }

/** Upgrade on a runtime's card, where the server offers it (web `SurfaceAuth.upgrade`): the last press's state and the press. */
internal data class ComputerRuntimeUpgradeOffer(
    val state: ComputerRuntimeUpgradeState,
    val onUpgrade: () -> Unit,
)

/** The card's update-check line: [message] formatted with [args], each a version drawn in the version line's mono. */
internal data class ComputerRuntimeUpdateCheckCopy(
    val message: StringResource,
    val args: List<String> = emptyList(),
)

/**
 * Web `runtimeUpdateCheckCopy`: the card's line for the Computer's update check, in place of the
 * version: "1.5.10 → 1.5.11 available", "1.5.11 · Latest", that its updates are managed outside Raft
 * (a package manager, updates turned off), or that it could not check, the last two after the
 * reported [version] when there is one. Null with no check (an older Computer or server): the
 * version line as before.
 */
internal fun computerRuntimeUpdateCheckCopy(update: RuntimeUpdateCheck?, version: String?): ComputerRuntimeUpdateCheckCopy? {
    val reported = version?.trim()?.takeIf { it.isNotEmpty() }
    return when (update) {
        null -> null
        is RuntimeUpdateCheck.Ok ->
            if (update.updateAvailable) {
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_update_available, listOf(update.installed, update.latest))
            } else {
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_latest, listOf(update.installed))
            }
        // A deliberate choice (a package manager, updates turned off): not a failure to check.
        is RuntimeUpdateCheck.Unavailable ->
            when {
                update.reason.managedElsewhere && reported != null ->
                    ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_managed_elsewhere_version, listOf(reported))
                update.reason.managedElsewhere -> ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_managed_elsewhere)
                reported != null -> ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_check_unavailable_version, listOf(reported))
                else -> ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_check_unavailable)
            }
    }
}

/**
 * Web `runtimeUpgradeButtonShown`: whether the card shows Upgrade where it is offered: when the
 * Computer's check found a newer version, or there is none (an older Computer or server: as
 * before); not when it is the latest or the check could not name one. Always while one runs, and
 * for a CLI too old to sign in ([tooOld], `version_unsupported`: known outdated).
 */
internal fun computerRuntimeUpgradeButtonShown(update: RuntimeUpdateCheck?, state: ComputerRuntimeUpgradeState, tooOld: Boolean): Boolean {
    if (state == ComputerRuntimeUpgradeState.Running || tooOld || update == null) return true
    return update is RuntimeUpdateCheck.Ok && update.updateAvailable
}

/** A plan OAR will not run, with the steps for the person to run by hand (web `RuntimeInstallManualPlan`). */
internal data class ComputerRuntimeInstallManualPlan(
    val reason: RuntimeInstallUnsupportedReason,
    val detail: String?,
    val steps: List<String>,
    val source: String?,
)

/**
 * What one press of Install on a Computer has learned (web `RuntimeInstallState`), for one runtime:
 * [runtime] is the sign-in id the request names, [raftRuntimeId] the chip's runtime, [runtimeName] its
 * display name.
 */
internal sealed interface ComputerRuntimeInstallState {
    data object Idle : ComputerRuntimeInstallState

    sealed interface ForRuntime : ComputerRuntimeInstallState {
        val runtime: RuntimeAuthRuntime
        val raftRuntimeId: String
        val runtimeName: String
    }

    /** The Computer is reading what it would run. */
    data class Planning(override val runtime: RuntimeAuthRuntime, override val raftRuntimeId: String, override val runtimeName: String) : ForRuntime

    /** The person reads the commands and confirms or cancels. */
    data class Confirm(
        override val runtime: RuntimeAuthRuntime,
        override val raftRuntimeId: String,
        override val runtimeName: String,
        val plan: RuntimeInstallPlan.Plan,
    ) : ForRuntime

    /** OAR will not run them here, but gives the steps to run by hand. */
    data class Manual(
        override val runtime: RuntimeAuthRuntime,
        override val raftRuntimeId: String,
        override val runtimeName: String,
        val plan: ComputerRuntimeInstallManualPlan,
    ) : ForRuntime

    /** The installer runs on the Computer. */
    data class Running(override val runtime: RuntimeAuthRuntime, override val raftRuntimeId: String, override val runtimeName: String) : ForRuntime

    data class Done(
        override val runtime: RuntimeAuthRuntime,
        override val raftRuntimeId: String,
        override val runtimeName: String,
        val result: RuntimeInstallResult,
        /** The plan that ran, when confirmed: a failed install offers its steps to run by hand (botiverse/slock #17185). */
        val plan: RuntimeInstallPlan.Plan? = null,
    ) : ForRuntime

    data class Refused(
        override val runtime: RuntimeAuthRuntime,
        override val raftRuntimeId: String,
        override val runtimeName: String,
        val reason: ComputerRuntimeOperationRefusal,
        val traceId: String? = null,
        val serverMessage: String? = null,
    ) : ForRuntime
}

/** Web `UNSUPPORTED_COPY`: why OAR will not run the installer here, one plain sentence each. */
internal fun computerRuntimeInstallUnsupportedCopy(reason: RuntimeInstallUnsupportedReason): StringResource =
    when (reason) {
        RuntimeInstallUnsupportedReason.Platform -> Res.string.settings_computers_runtime_install_unsupported_platform
        RuntimeInstallUnsupportedReason.RequiresPrivileges -> Res.string.settings_computers_runtime_install_unsupported_requires_privileges
        RuntimeInstallUnsupportedReason.RequiresGui -> Res.string.settings_computers_runtime_install_unsupported_requires_gui
        RuntimeInstallUnsupportedReason.MissingTool -> Res.string.settings_computers_runtime_install_unsupported_missing_tool
        RuntimeInstallUnsupportedReason.LocationUnknown -> Res.string.settings_computers_runtime_install_unsupported_location_unknown
        RuntimeInstallUnsupportedReason.Configuration -> Res.string.settings_computers_runtime_install_unsupported_configuration
        RuntimeInstallUnsupportedReason.Bundled,
        RuntimeInstallUnsupportedReason.LineRequired,
        RuntimeInstallUnsupportedReason.UnknownLine -> Res.string.settings_computers_runtime_install_unsupported_other
    }

/** Web `runtimeInstallCopy`: one plain sentence per outcome; null while nothing is known yet or the person is deciding. */
internal fun computerRuntimeInstallCopy(state: ComputerRuntimeInstallState): ComputerRuntimeOperationCopy? =
    when (state) {
        is ComputerRuntimeInstallState.Refused ->
            ComputerRuntimeOperationCopy(
                when (state.reason) {
                    ComputerRuntimeOperationRefusal.ComputerUpgradeRequired -> Res.string.settings_computers_runtime_install_computer_too_old
                    ComputerRuntimeOperationRefusal.ComputerOffline -> Res.string.settings_computers_runtime_install_computer_offline
                    ComputerRuntimeOperationRefusal.ComputerTimeout -> Res.string.settings_computers_runtime_install_timeout
                    // Pressing again won't help for a while (web: the sign-ins' wait sentence).
                    ComputerRuntimeOperationRefusal.RateLimited -> Res.string.settings_computers_runtime_auth_provider_error_rate_limited
                    ComputerRuntimeOperationRefusal.Failed -> Res.string.settings_computers_runtime_install_error
                },
                success = false,
                traceId = state.traceId,
                serverMessage = state.serverMessage,
            )
        is ComputerRuntimeInstallState.Done ->
            when (val result = state.result) {
                is RuntimeInstallResult.Installed -> {
                    val version = result.version
                    if (version != null) {
                        ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_installed_version, listOf(version), success = true)
                    } else {
                        ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_installed, success = true)
                    }
                }
                is RuntimeInstallResult.AlreadyInstalled -> ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_already_installed, success = true)
                is RuntimeInstallResult.Failed -> computerRuntimeInstallFailedCopy(result)
                is RuntimeInstallResult.Unsupported -> ComputerRuntimeOperationCopy(computerRuntimeInstallUnsupportedCopy(result.reason), success = false)
                is RuntimeInstallResult.Error ->
                    ComputerRuntimeOperationCopy(
                        if (result.code == RuntimeOperationErrorCode.InProgress) {
                            Res.string.settings_computers_runtime_install_in_progress
                        } else {
                            Res.string.settings_computers_runtime_install_error
                        },
                        success = false,
                    )
            }
        else -> null
    }

private val RuntimeInstallTimeoutMinutes: String = (RUNTIME_INSTALL_TIMEOUT_MILLIS / 60_000L).toString()

/**
 * Web `failedCopy` / `exitCodeCopy` (botiverse/slock #17185, #17210): why the installer failed.
 * OAR 0.138's reason when the Computer sends a known one, otherwise the exit code alone (an older
 * Computer, OAR gave none, or a reason this build does not know).
 */
private fun computerRuntimeInstallFailedCopy(result: RuntimeInstallResult.Failed): ComputerRuntimeOperationCopy =
    when (result.reason) {
        RuntimeInstallFailureReason.DownloadFailed ->
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_failed_download_failed, success = false)
        RuntimeInstallFailureReason.NonScriptResponse ->
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_failed_not_a_script, success = false)
        RuntimeInstallFailureReason.TimedOut ->
            ComputerRuntimeOperationCopy(
                Res.string.settings_computers_runtime_install_failed_timed_out,
                args = listOf(RuntimeInstallTimeoutMinutes),
                success = false,
            )
        RuntimeInstallFailureReason.InstallerFailed ->
            if (result.exitCode == null) {
                ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_failed_no_exit_code, success = false)
            } else {
                ComputerRuntimeOperationCopy(
                    Res.string.settings_computers_runtime_install_failed_exit_code,
                    args = listOf(result.exitCode.toString()),
                    success = false,
                )
            }
        RuntimeInstallFailureReason.NotFoundAfterInstall ->
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_failed_not_found, success = false)
        RuntimeInstallFailureReason.UnusableAfterInstall ->
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_failed_unusable, success = false)
        null ->
            when (val exitCode = result.exitCode) {
                null ->
                    ComputerRuntimeOperationCopy(
                        Res.string.settings_computers_runtime_install_failed_timeout,
                        args = listOf(RuntimeInstallTimeoutMinutes),
                        success = false,
                    )
                0 -> ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_failed_not_found, success = false)
                else ->
                    ComputerRuntimeOperationCopy(
                        Res.string.settings_computers_runtime_install_failed_exit_code,
                        args = listOf(exitCode.toString()),
                        success = false,
                    )
            }
    }

/**
 * Web `runtimeInstallStateForPlan`: the confirm, the manual steps, or an outcome to say at once (a
 * refusal without steps reads as `unsupported`, a plan the Computer could not read as `error`).
 */
internal fun computerRuntimeInstallStateForPlan(
    runtime: RuntimeAuthRuntime,
    raftRuntimeId: String,
    runtimeName: String,
    plan: RuntimeInstallPlan,
): ComputerRuntimeInstallState =
    when (plan) {
        is RuntimeInstallPlan.Plan -> ComputerRuntimeInstallState.Confirm(runtime, raftRuntimeId, runtimeName, plan)
        is RuntimeInstallPlan.Unsupported -> {
            val steps = plan.steps
            if (!steps.isNullOrEmpty()) {
                ComputerRuntimeInstallState.Manual(runtime, raftRuntimeId, runtimeName, ComputerRuntimeInstallManualPlan(plan.reason, plan.detail, steps, plan.source))
            } else {
                ComputerRuntimeInstallState.Done(runtime, raftRuntimeId, runtimeName, RuntimeInstallResult.Unsupported(plan.reason))
            }
        }
        RuntimeInstallPlan.Error -> ComputerRuntimeInstallState.Done(runtime, raftRuntimeId, runtimeName, RuntimeInstallResult.Error(RuntimeOperationErrorCode.Internal))
    }

/** The install calls of one Computer for one signed-in person (the store, bound). */
internal interface ComputerRuntimeInstallOps {
    suspend fun view(force: Boolean): RuntimeAuthView?

    suspend fun installPlan(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallPlan>

    suspend fun install(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallResult>
}

/**
 * Install on one Computer (web `useRuntimeInstall` + MachineRuntimeChips' runtime-auth read), one
 * runtime at a time: [start] reads what the Computer would run and puts it before the person,
 * [confirm] runs it (only on that press), [cancel] drops the plan. Once the Computer answers an
 * install, its runtime-auth view is read again (it re-detects its runtimes after one was installed).
 */
internal class ComputerRuntimeInstallController(
    private val ops: ComputerRuntimeInstallOps,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onIdentityChanged: () -> Unit = {},
) {
    var view by mutableStateOf<RuntimeAuthView?>(null)
        private set
    var state by mutableStateOf<ComputerRuntimeInstallState>(ComputerRuntimeInstallState.Idle)
        private set
    private var readSeq = 0

    val busy: Boolean
        get() = state is ComputerRuntimeInstallState.Planning || state is ComputerRuntimeInstallState.Running

    /** The sign-in runtime Install would name for this Raft runtime, when the server offers it here; else null. */
    fun installableAs(raftRuntimeId: String): RuntimeAuthRuntime? =
        RuntimeAuthRuntime.forRaftRuntime(raftRuntimeId)?.takeIf { view?.installOffered(it) == true }

    fun read(force: Boolean = false) {
        val seq = ++readSeq
        scope.launch {
            val next = ops.view(force)
            if (seq == readSeq && next != null) view = next
        }
    }

    fun start(runtime: RuntimeAuthRuntime, raftRuntimeId: String, runtimeName: String) {
        if (busy) return
        state = ComputerRuntimeInstallState.Planning(runtime, raftRuntimeId, runtimeName)
        scope.launch {
            val answer = ops.installPlan(runtime)
            log("computer_runtime_install_plan runtime=${runtime.wire} result=${answer.operationLabel()}")
            state =
                when (answer) {
                    is RuntimeAuthCall.Done -> computerRuntimeInstallStateForPlan(runtime, raftRuntimeId, runtimeName, answer.value)
                    is RuntimeAuthCall.Refused -> {
                        val (reason, serverMessage) = answer.error.toOperationRefusal()
                        ComputerRuntimeInstallState.Refused(
                            runtime = runtime,
                            raftRuntimeId = raftRuntimeId,
                            runtimeName = runtimeName,
                            reason = reason,
                            traceId = answer.error.traceId,
                            serverMessage = serverMessage,
                        )
                    }
                    RuntimeAuthCall.Superseded -> superseded()
                }
        }
    }

    fun confirm() {
        val open = state as? ComputerRuntimeInstallState.Confirm ?: return
        state = ComputerRuntimeInstallState.Running(open.runtime, open.raftRuntimeId, open.runtimeName)
        scope.launch {
            val answer = ops.install(open.runtime)
            log("computer_runtime_install runtime=${open.runtime.wire} result=${answer.operationLabel()}")
            state =
                when (answer) {
                    is RuntimeAuthCall.Done ->
                        ComputerRuntimeInstallState.Done(
                            runtime = open.runtime,
                            raftRuntimeId = open.raftRuntimeId,
                            runtimeName = open.runtimeName,
                            result = answer.value,
                            plan = open.plan,
                        )
                    is RuntimeAuthCall.Refused -> {
                        val (reason, serverMessage) = answer.error.toOperationRefusal()
                        ComputerRuntimeInstallState.Refused(
                            runtime = open.runtime,
                            raftRuntimeId = open.raftRuntimeId,
                            runtimeName = open.runtimeName,
                            reason = reason,
                            traceId = answer.error.traceId,
                            serverMessage = serverMessage,
                        )
                    }
                    RuntimeAuthCall.Superseded -> superseded()
                }
            if (state !is ComputerRuntimeInstallState.Idle) read(force = true)
        }
    }

    /** Closes the confirm or the manual steps; anything else stays. */
    fun cancel() {
        if (state is ComputerRuntimeInstallState.Confirm || state is ComputerRuntimeInstallState.Manual) state = ComputerRuntimeInstallState.Idle
    }

    private fun superseded(): ComputerRuntimeInstallState {
        view = null
        onIdentityChanged()
        return ComputerRuntimeInstallState.Idle
    }
}

/** For the diagnostics line: never the plan's steps (they can name a home path) or a failed install's `outputTail`. */
internal fun RuntimeAuthCall<*>.operationLabel(): String =
    when (this) {
        is RuntimeAuthCall.Done ->
            when (val outcome = value) {
                is RuntimeInstallPlan.Plan -> "plan"
                is RuntimeInstallPlan.Unsupported -> "unsupported:${outcome.reason.wire}"
                RuntimeInstallPlan.Error -> "error"
                is RuntimeInstallResult -> outcome::class.simpleName.orEmpty()
                is RuntimeUpgradeResult -> outcome::class.simpleName.orEmpty()
                else -> "done"
            }
        is RuntimeAuthCall.Refused -> "refused:${error.status ?: "none"}:${error.code ?: "none"}"
        RuntimeAuthCall.Superseded -> "superseded"
    }
