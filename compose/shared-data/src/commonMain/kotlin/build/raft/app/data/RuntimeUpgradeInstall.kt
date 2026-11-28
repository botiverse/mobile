package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.reportProjectionRowDropped
import build.raft.app.reportUnknownTypeDropped
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Upgrading and installing a Computer's runtimes from its page, as the web does: botiverse/slock
 * #15117 (940212580a, Upgrade) and #15214 (ffbf283ad2, Install); docs/contracts/runtime-auth.md
 * §6d/§6e, packages/shared/src/runtimeUpgrade.ts + runtimeInstall.ts, packages/server/src/routes/
 * runtimeAuthRoutes.ts, packages/web/src/utils/runtimeAuthClient.ts.
 *
 * - `GET …/runtime-auth` adds `runtimeUpgrade: true` + `upgradableRuntimes` (behind
 *   `runtime_upgrade_button`) and `runtimeInstall: true` + `installableRuntimes` (behind
 *   `runtime_install_button`), only for a viewer who may act on a Computer that runs them.
 * - `POST /api/servers/:id/machines/:machineId/runtimes/:runtime/upgrade` → `{result}`.
 * - `POST …/runtimes/:runtime/install-plan` → `{plan}` (runs nothing on the Computer).
 * - `POST …/runtimes/:runtime/install` → `{result}`.
 * Each takes `{respondAsync: true}` and may answer 202 `{operationId}` (MachineOperations.kt). A
 * refusal answers `{error, code?}`: 404 `runtime_upgrade_disabled` / `runtime_install_disabled` /
 * `runtime_not_upgradable` / `runtime_not_installable` / `computer_not_found`, 403 `forbidden`, 409
 * `computer_offline` / `computer_upgrade_required`, 429 `rate_limited`, 504 `computer_timeout`.
 * `:runtime` is the runtime's sign-in id ([RuntimeAuthRuntime.wire]). Raft never upgrades or installs
 * on its own: only on a person's press, and an install only after its plan was shown.
 *
 * The update check (botiverse/slock #15429 9523be7fc7, read at staging 5881c7b53ca7b447e4d4ac391390c68453cba952;
 * contract §6d "Update check"): with `upgradableRuntimes` the read also carries `runtimeUpdates`
 * (runtimeAuthRoutes.ts `offeredRuntimeUpdates`, only for the runtimes Upgrade is offered for), the
 * Computer's read-only check by runtime id (packages/shared/src/runtimeAuth.ts `runtimeUpdatesSchema`,
 * packages/shared/src/runtimeUpgrade.ts `runtimeUpdateCheckSchema`):
 *   `{"claude":{"kind":"ok","installed":"2.0.5","latest":"2.0.5","updateAvailable":false,"checkedAt":"…"},
 *     "grok":{"kind":"unavailable","reason":"lookup_failed","checkedAt":"…"}}`
 * Absent from an older server or Computer, and no entry for a runtime not checked yet: Upgrade as before.
 */

/** slock `RUNTIME_UPGRADE_RELAY_BUDGET_MS`. */
@RaftCoreInternalApi const val RUNTIME_UPGRADE_RELAY_BUDGET_MILLIS = 12 * 60_000L

/** slock `RUNTIME_INSTALL_TIMEOUT_MS`: the bound OAR uses for the installer run. */
@RaftCoreInternalApi const val RUNTIME_INSTALL_TIMEOUT_MILLIS = 10 * 60_000L

/** slock `RUNTIME_INSTALL_RELAY_BUDGET_MS`. */
@RaftCoreInternalApi const val RUNTIME_INSTALL_RELAY_BUDGET_MILLIS = 12 * 60_000L

/** slock `RUNTIME_INSTALL_PLAN_RELAY_BUDGET_MS`: the plan runs no installer, only local reads. */
@RaftCoreInternalApi const val RUNTIME_INSTALL_PLAN_RELAY_BUDGET_MILLIS = 60_000L

/**
 * slock `RUNTIME_INSTALL_OUTPUT_TAIL_MAX_CHARS`: at most 2 KB of redacted last output lines
 * returned on a failed runtime install (botiverse/slock #17305).
 */
@RaftCoreInternalApi const val RUNTIME_INSTALL_OUTPUT_TAIL_MAX_CHARS = 2_048

/**
 * The body of an upgrade, an install plan or an install: the runtime is the path's. [failureReason]
 * and [outputTail] are sent (`true`) only on `install` to opt into OAR 0.138's typed failure reason
 * (botiverse/slock #17210) and a failed installer's redacted last lines (botiverse/slock #17305);
 * omitted (`null`) on `upgrade` and `install-plan`.
 */
@Serializable
@RaftCoreInternalApi data class RuntimeMachineOperationBody(
    val respondAsync: Boolean,
    val failureReason: Boolean? = null,
    val outputTail: Boolean? = null,
)

/** slock `RUNTIME_UPGRADE_ERROR_CODES` / `RUNTIME_INSTALL_ERROR_CODES`: why the Computer could not try. */
@RaftCoreInternalApi enum class RuntimeOperationErrorCode(val wire: String) {
    InProgress("in_progress"),
    Internal("internal");

    companion object {
        fun fromWire(value: String?): RuntimeOperationErrorCode? = entries.firstOrNull { it.wire == value }
    }
}

/** slock `RUNTIME_UPGRADE_UNSUPPORTED_REASONS`. */
@RaftCoreInternalApi enum class RuntimeUpgradeUnsupportedReason(val wire: String) {
    UnsupportedInstallation("unsupported_installation"),
    RequiresTerminal("requires_terminal");

    companion object {
        fun fromWire(value: String?): RuntimeUpgradeUnsupportedReason? = entries.firstOrNull { it.wire == value }
    }
}

/** slock `RuntimeUpgradeResult`: what an upgrade did, without the updater's output. */
@RaftCoreInternalApi sealed interface RuntimeUpgradeResult {
    data class Upgraded(val from: String? = null, val to: String? = null) : RuntimeUpgradeResult

    /** The update check found nothing newer, so no updater ran. */
    data class Current(val version: String? = null) : RuntimeUpgradeResult

    /** The updater ran and exited 0, yet the runtime reports the same version. */
    data class Unchanged(val version: String? = null) : RuntimeUpgradeResult

    data class Failed(val exitCode: Int? = null) : RuntimeUpgradeResult

    data class Unsupported(val reason: RuntimeUpgradeUnsupportedReason) : RuntimeUpgradeResult

    data object NotInstalled : RuntimeUpgradeResult

    data class Error(val code: RuntimeOperationErrorCode) : RuntimeUpgradeResult
}

/**
 * slock `RUNTIME_UPDATE_CHECK_MAX_AGE_MS`: an update check older than this (by its `checkedAt`) counts
 * as none, and the card offers Upgrade as before (web `runtimeUpdateOf`).
 */
@RaftCoreInternalApi const val RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS = 24 * 60 * 60_000L

/**
 * slock `RUNTIME_UPDATE_CHECK_UNAVAILABLE_REASONS` (OAR's `UpdateCheckUnavailableReason`): why a check
 * could not name the version the runtime's own updater would install. [managedElsewhere]: one of slock
 * `RUNTIME_UPDATE_CHECK_MANAGED_ELSEWHERE_REASONS`, a deliberate choice (a package manager, updates
 * turned off) rather than a failed lookup.
 */
@RaftCoreInternalApi enum class RuntimeUpdateCheckUnavailableReason(val wire: String, val managedElsewhere: Boolean = false) {
    UnsupportedInstallation("unsupported_installation", managedElsewhere = true),
    PackageManager("package_manager", managedElsewhere = true),
    UnmanagedInstallation("unmanaged_installation", managedElsewhere = true),
    UpdatesDisabled("updates_disabled", managedElsewhere = true),

    /** Also a check that threw or timed out on the Computer, and a reason this app does not know. */
    LookupFailed("lookup_failed"),
    VersionUnreadable("version_unreadable");

    companion object {
        fun fromWire(value: String?): RuntimeUpdateCheckUnavailableReason? = entries.firstOrNull { it.wire == value }
    }
}

/** slock `RuntimeUpdateCheck`: one runtime's read-only update check as the Computer reported it. */
@RaftCoreInternalApi sealed interface RuntimeUpdateCheck {
    /** `checkedAt`: when the Computer ran the check, in epoch milliseconds. */
    val checkedAtMillis: Long

    /** The installed version, the one the runtime's own updater would install, and its verdict on whether that is an update. */
    data class Ok(
        val installed: String,
        val latest: String,
        val updateAvailable: Boolean,
        override val checkedAtMillis: Long,
    ) : RuntimeUpdateCheck

    data class Unavailable(
        val reason: RuntimeUpdateCheckUnavailableReason,
        override val checkedAtMillis: Long,
    ) : RuntimeUpdateCheck
}

/** slock `RUNTIME_INSTALL_UNSUPPORTED_REASONS`: why OAR will not run the installer on this Computer. */
@RaftCoreInternalApi enum class RuntimeInstallUnsupportedReason(val wire: String) {
    Platform("platform"),
    RequiresPrivileges("requires_privileges"),
    RequiresGui("requires_gui"),
    MissingTool("missing_tool"),
    LocationUnknown("location_unknown"),
    Configuration("configuration"),
    Bundled("bundled"),
    LineRequired("line_required"),
    UnknownLine("unknown_line");

    companion object {
        fun fromWire(value: String?): RuntimeInstallUnsupportedReason? = entries.firstOrNull { it.wire == value }
    }
}

/** slock `RuntimeInstallPlan`: what Install would run, read on the Computer without running it. */
@RaftCoreInternalApi sealed interface RuntimeInstallPlan {
    /** The vendor's documented commands the person reads before confirming, and its https page. */
    data class Plan(val steps: List<String>, val source: String) : RuntimeInstallPlan

    /** OAR will not run it here; the manual reasons carry the steps to run by hand. */
    data class Unsupported(
        val reason: RuntimeInstallUnsupportedReason,
        val detail: String? = null,
        val steps: List<String>? = null,
        val source: String? = null,
    ) : RuntimeInstallPlan

    data object Error : RuntimeInstallPlan
}

/**
 * slock `RUNTIME_INSTALL_FAILURE_REASONS` (OAR 0.138 `KnownInstallFailureReason`,
 * botiverse/slock #17210): why a `failed` install failed, as OAR judged it. OAR's enum is open;
 * a reason this build does not name reads as null so the UI falls back to the exit code.
 */
@RaftCoreInternalApi enum class RuntimeInstallFailureReason(val wire: String) {
    DownloadFailed("download_failed"),
    NonScriptResponse("non_script_response"),
    TimedOut("timed_out"),
    InstallerFailed("installer_failed"),
    NotFoundAfterInstall("not_found_after_install"),
    UnusableAfterInstall("unusable_after_install");

    companion object {
        fun fromWire(value: String?): RuntimeInstallFailureReason? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * slock `RuntimeInstallResult`: what an install did. A `failed` outcome may carry OAR 0.138's
 * typed failure `reason` (botiverse/slock #17210) and the installer's redacted last lines
 * (`outputTail`, botiverse/slock #17305) when requested and answered to the person who attached
 * the Computer; `outputTail` is never logged or traced.
 */
@RaftCoreInternalApi sealed interface RuntimeInstallResult {
    data class Installed(val version: String? = null) : RuntimeInstallResult

    /** The probe found it before any installer ran. */
    data class AlreadyInstalled(val version: String? = null) : RuntimeInstallResult

    /**
     * No usable copy after the installer, or no installer ran: [exitCode] is the installer's (or
     * curl's for [RuntimeInstallFailureReason.DownloadFailed]; null when stopped, could not start,
     * or nothing ran), [reason] is OAR 0.138's known failure reason when sent, and [outputTail] is
     * the redacted last lines of the installer's output when sent by the Computer.
     */
    data class Failed(
        val exitCode: Int? = null,
        val reason: RuntimeInstallFailureReason? = null,
        val outputTail: String? = null,
    ) : RuntimeInstallResult

    data class Unsupported(val reason: RuntimeInstallUnsupportedReason) : RuntimeInstallResult

    data class Error(val code: RuntimeOperationErrorCode) : RuntimeInstallResult
}

/** The `{result}` / `{plan}` member on the wire: every variant's fields, read by `kind`. */
@Serializable
private data class RuntimeOperationOutcomeDto(
    val kind: String? = null,
    val from: String? = null,
    val to: String? = null,
    val version: String? = null,
    val exitCode: Int? = null,
    val outputTail: JsonElement? = null,
    val reason: JsonElement? = null,
    val code: String? = null,
    val detail: String? = null,
    val steps: List<String>? = null,
    val source: String? = null,
)

private val runtimeOperationJson = Json { ignoreUnknownKeys = true }

/** slock `runtimeVersionSchema`: a release version (`2.0.14`, `1.0.0-beta.3`), never a path. */
private val RuntimeVersionShape = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]*$")

/** slock `RUNTIME_INSTALL_FAILURE_REASON_PATTERN`: OAR's snake_case words, bounded. */
private val RuntimeInstallFailureReasonShape = Regex("^[a-z][a-z0-9_]{0,47}$")

private fun JsonElement?.stringContentOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A version as the result may carry it; one that is not a version is left out (web `runtimeUpgradeVersion`). */
@RaftCoreInternalApi fun runtimeOperationVersion(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && RuntimeVersionShape.matches(it) }

private fun JsonElement.toRuntimeOperationOutcomeDto(): RuntimeOperationOutcomeDto? =
    runCatching { runtimeOperationJson.decodeFromJsonElement(RuntimeOperationOutcomeDto.serializer(), this) }.getOrNull()

/** The upgrade's `result`; null when it is not one (the caller says the upgrade failed). */
@RaftCoreInternalApi fun JsonElement.toRuntimeUpgradeResult(diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger): RuntimeUpgradeResult? {
    val dto = toRuntimeOperationOutcomeDto() ?: return null
    return when (dto.kind) {
        "upgraded" -> RuntimeUpgradeResult.Upgraded(runtimeOperationVersion(dto.from), runtimeOperationVersion(dto.to))
        "current" -> RuntimeUpgradeResult.Current(runtimeOperationVersion(dto.version))
        "unchanged" -> RuntimeUpgradeResult.Unchanged(runtimeOperationVersion(dto.version))
        "failed" -> RuntimeUpgradeResult.Failed(dto.exitCode)
        "unsupported" -> RuntimeUpgradeUnsupportedReason.fromWire(dto.reason.stringContentOrNull())?.let(RuntimeUpgradeResult::Unsupported)
        "not_installed" -> RuntimeUpgradeResult.NotInstalled
        "error" -> RuntimeOperationErrorCode.fromWire(dto.code)?.let(RuntimeUpgradeResult::Error)
        else -> {
            reportUnknownTypeDropped("runtime_upgrade_result", "kind", dto.kind, fallback = "failed", diagnostics = diagnostics)
            null
        }
    }
}

/**
 * One `runtimeUpdates` entry on the wire (slock `runtimeUpdateCheckSchema`): every variant's fields,
 * read by `kind`. Not strict, as slock's: a key this app does not know is ignored.
 */
@Serializable
private data class RuntimeUpdateCheckDto(
    val kind: String? = null,
    val installed: String? = null,
    val latest: String? = null,
    val updateAvailable: Boolean? = null,
    val reason: String? = null,
    val checkedAt: String? = null,
)

/**
 * The read's `runtimeUpdates` (slock `runtimeUpdatesSchema`) by runtime; null when it is not an object
 * (an older server or Computer sends none). As in slock, a runtime this app does not know and an entry
 * that does not read are dropped on their own (one entry never costs the others), each logged. A
 * `kind` this app does not know reads as no check (the card offers Upgrade as before); an
 * `unavailable` reason it does not know as `lookup_failed`, as slock reads a newer Computer's.
 */
@RaftCoreInternalApi fun JsonElement.toRuntimeUpdates(
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): Map<RuntimeAuthRuntime, RuntimeUpdateCheck>? {
    val record = this as? JsonObject ?: return null
    return record.mapNotNull { (key, value) ->
        val runtime =
            RuntimeAuthRuntime.fromWire(key)
                ?: run {
                    reportUnknownTypeDropped(RuntimeUpdatesSource, "runtime", key, diagnostics = diagnostics)
                    return@mapNotNull null
                }
        value.toRuntimeUpdateCheck(diagnostics)?.let { runtime to it }
    }.toMap()
}

private const val RuntimeUpdatesSource = "runtime_updates"

private fun JsonElement.toRuntimeUpdateCheck(diagnostics: (String) -> Unit): RuntimeUpdateCheck? {
    val dto = runCatching { runtimeOperationJson.decodeFromJsonElement(RuntimeUpdateCheckDto.serializer(), this) }.getOrNull()
    if (dto == null) {
        reportProjectionRowDropped(RuntimeUpdatesSource, "decode_failed", diagnostics = diagnostics)
        return null
    }
    val checkedAtMillis = dto.checkedAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
    val check =
        when (dto.kind) {
            "ok" -> {
                val installed = dto.installed.runtimeUpdateCheckVersion()
                val latest = dto.latest.runtimeUpdateCheckVersion()
                val updateAvailable = dto.updateAvailable
                if (installed != null && latest != null && updateAvailable != null && checkedAtMillis != null) {
                    RuntimeUpdateCheck.Ok(installed, latest, updateAvailable, checkedAtMillis)
                } else {
                    null
                }
            }
            "unavailable" -> {
                val reason = dto.reason?.takeIf { it.length <= 64 }
                if (reason != null && checkedAtMillis != null) {
                    val known =
                        RuntimeUpdateCheckUnavailableReason.fromWire(reason)
                            ?: RuntimeUpdateCheckUnavailableReason.LookupFailed.also {
                                reportUnknownTypeDropped(RuntimeUpdatesSource, "reason", reason, fallback = it.wire, diagnostics = diagnostics)
                            }
                    RuntimeUpdateCheck.Unavailable(known, checkedAtMillis)
                } else {
                    null
                }
            }
            null -> null
            else -> {
                reportUnknownTypeDropped(RuntimeUpdatesSource, "kind", dto.kind, fallback = "no_update_check", diagnostics = diagnostics)
                return null
            }
        }
    if (check == null) reportProjectionRowDropped(RuntimeUpdatesSource, "decode_failed", diagnostics = diagnostics)
    return check
}

/** slock `runtimeVersionSchema` as the check carries it: exactly a release version (`2.0.14`), never a path, not trimmed. */
private fun String?.runtimeUpdateCheckVersion(): String? = this?.takeIf { it.length <= 64 && RuntimeVersionShape.matches(it) }

/** slock `installStepSchema` (1..2 000 characters), 1..10 of them. */
private fun List<String>?.installSteps(): List<String>? =
    this?.takeIf { it.size in 1..10 && it.all { step -> step.isNotEmpty() && step.length <= 2_000 } }

/** slock `installSourceSchema`: an https URL of at most 500 characters (drawn as a link). */
private fun String?.installSource(): String? =
    this?.takeIf { it.length in "https://x".length..500 && it.startsWith("https://") && it.none(Char::isWhitespace) }

/** The install plan's `plan`; null when it is not one (the caller says the install failed). */
@RaftCoreInternalApi fun JsonElement.toRuntimeInstallPlan(diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger): RuntimeInstallPlan? {
    val dto = toRuntimeOperationOutcomeDto() ?: return null
    return when (dto.kind) {
        "plan" -> {
            val steps = dto.steps.installSteps() ?: return null
            val source = dto.source.installSource() ?: return null
            RuntimeInstallPlan.Plan(steps, source)
        }
        "unsupported" -> {
            val reason = RuntimeInstallUnsupportedReason.fromWire(dto.reason.stringContentOrNull()) ?: return null
            RuntimeInstallPlan.Unsupported(
                reason = reason,
                detail = dto.detail?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1_000 },
                steps = dto.steps.installSteps(),
                source = dto.source.installSource(),
            )
        }
        "error" -> RuntimeInstallPlan.Error
        else -> {
            reportUnknownTypeDropped("runtime_install_plan", "kind", dto.kind, fallback = "failed", diagnostics = diagnostics)
            null
        }
    }
}

/**
 * slock `runtimeInstallResultSchema`'s `reason` (botiverse/slock #17210): OAR 0.138's open
 * failure reason. Known values map to [RuntimeInstallFailureReason]; unknown values matching
 * `RUNTIME_INSTALL_FAILURE_REASON_PATTERN` log and return null so the UI falls back to `exitCode`;
 * absent, null, non-string, or out-of-shape values become null without dropping the failure outcome.
 */
private fun JsonElement?.runtimeInstallFailureReason(diagnostics: (String) -> Unit): RuntimeInstallFailureReason? {
    val raw = stringContentOrNull() ?: return null
    if (!RuntimeInstallFailureReasonShape.matches(raw)) return null
    return RuntimeInstallFailureReason.fromWire(raw)
        ?: run {
            reportUnknownTypeDropped("runtime_install_result", "reason", raw, fallback = "exit_code", diagnostics = diagnostics)
            null
        }
}

/**
 * slock `runtimeInstallResultSchema`'s `outputTail`: redacted last lines of a failed installer,
 * trimmed and non-blank, within [RUNTIME_INSTALL_OUTPUT_TAIL_MAX_CHARS]. Absent, null, blank,
 * non-string, or oversized values become null; never logged.
 */
private fun JsonElement?.runtimeInstallOutputTail(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.trim().takeIf { it.isNotEmpty() && it.length <= RUNTIME_INSTALL_OUTPUT_TAIL_MAX_CHARS }
}

/** The install's `result`; null when it is not one (the caller says the install failed). */
@RaftCoreInternalApi fun JsonElement.toRuntimeInstallResult(diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger): RuntimeInstallResult? {
    val dto = toRuntimeOperationOutcomeDto() ?: return null
    return when (dto.kind) {
        "installed" -> RuntimeInstallResult.Installed(runtimeOperationVersion(dto.version))
        "already_installed" -> RuntimeInstallResult.AlreadyInstalled(runtimeOperationVersion(dto.version))
        "failed" ->
            RuntimeInstallResult.Failed(
                exitCode = dto.exitCode,
                reason = dto.reason.runtimeInstallFailureReason(diagnostics),
                outputTail = dto.outputTail.runtimeInstallOutputTail(),
            )
        "unsupported" -> RuntimeInstallUnsupportedReason.fromWire(dto.reason.stringContentOrNull())?.let(RuntimeInstallResult::Unsupported)
        "error" -> RuntimeOperationErrorCode.fromWire(dto.code)?.let(RuntimeInstallResult::Error)
        else -> {
            reportUnknownTypeDropped("runtime_install_result", "kind", dto.kind, fallback = "failed", diagnostics = diagnostics)
            null
        }
    }
}
