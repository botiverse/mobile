package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeOperationErrorCode
import build.raft.app.data.RuntimeUpgradeResult
import build.raft.app.data.RuntimeUpgradeUnsupportedReason
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.canViewMachineRuntimeAccountUsage
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2UnavailableChoice
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.members.MembersRuntimeAccountUsageAccess
import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Bundled `@botiverse/oar@0.137.0` suggested-versions floor (`package/assets/suggested-versions.json`,
 * `updatedAt = "2026-11-26"`): when a Computer's detected CLI version is strictly below this floor,
 * the model field offers a quiet inline upgrade hint (Layer 2, `botiverse/slock` `#17086`).
 */
internal val OAR_SUGGESTED_MIN_VERSIONS: Map<String, String> =
    mapOf(
        "codex" to "0.166.0",
        "claude" to "2.1.321",
    )

private val EXTRACT_SEMVER_PATTERN =
    Regex("""\bv?(\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?)\b""")

private val STRICT_SEMVER_PATTERN =
    Regex("""^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?$""")

private val NUMERIC_IDENTIFIER_PATTERN = Regex("""^\d+$""")

private val UPGRADE_REASON_PATTERN =
    Regex(
        """\b(?:update|upgrade|newer\s+version|cli\s+version|minimum\s+version|version\s+\d|v?\d+\.\d+\.\d+)\b|升级|更新.*版本""",
        RegexOption.IGNORE_CASE,
    )

private val SEMVER_IN_REASON_PATTERN =
    Regex("""\bv?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)\b""")

private val CJK_TERMINAL_PUNCT = Regex("""[。！？：]$""")

/** Extracts an embedded SemVer token from a raw CLI `--version` string (e.g. `"2.1.59 (Claude Code)"`). */
internal fun extractRuntimeSemver(versionText: String?): String? {
    val trimmed = versionText?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return EXTRACT_SEMVER_PATTERN.find(trimmed)?.groupValues?.getOrNull(1)
}

private data class ParsedRuntimeSemver(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>,
)

private fun parseRuntimeSemver(version: String): ParsedRuntimeSemver? {
    val match = STRICT_SEMVER_PATTERN.matchEntire(version.trim()) ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: return null
    val patch = match.groupValues[3].toLongOrNull() ?: return null
    val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
    return ParsedRuntimeSemver(major = major, minor = minor, patch = patch, prerelease = pre)
}

/**
 * SemVer 2.0.0 comparator matching `@botiverse/oar` `dist/shared/semver.js` (§11 precedence:
 * prerelease < release, numeric < alphanumeric, shorter prefix < longer). Returns `null` when
 * either input is not valid SemVer 2.0.0.
 */
internal fun compareRuntimeSemver(left: String, right: String): Int? {
    val a = parseRuntimeSemver(left) ?: return null
    val b = parseRuntimeSemver(right) ?: return null
    if (a.major != b.major) return a.major.compareTo(b.major)
    if (a.minor != b.minor) return a.minor.compareTo(b.minor)
    if (a.patch != b.patch) return a.patch.compareTo(b.patch)
    if (a.prerelease.isEmpty() && b.prerelease.isEmpty()) return 0
    if (a.prerelease.isEmpty()) return 1
    if (b.prerelease.isEmpty()) return -1
    val shared = minOf(a.prerelease.size, b.prerelease.size)
    for (i in 0 until shared) {
        val leftPart = a.prerelease[i]
        val rightPart = b.prerelease[i]
        if (leftPart == rightPart) continue
        val leftNum = NUMERIC_IDENTIFIER_PATTERN.matches(leftPart)
        val rightNum = NUMERIC_IDENTIFIER_PATTERN.matches(rightPart)
        return when {
            leftNum && rightNum -> compareUnsignedDecimalStrings(leftPart, rightPart)
            leftNum -> -1
            rightNum -> 1
            else -> leftPart.compareTo(rightPart)
        }
    }
    return a.prerelease.size.compareTo(b.prerelease.size)
}

private fun compareUnsignedDecimalStrings(left: String, right: String): Int {
    val a = left.trimStart('0').ifEmpty { "0" }
    val b = right.trimStart('0').ifEmpty { "0" }
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b)
}

internal data class RuntimeSuggestedVersionAdvice(
    val oarRuntimeId: String,
    val installedVersion: String,
    val suggestedMinVersion: String,
)

/** Returns Layer 2 advice when the Computer's detected CLI version is strictly below [floors]. */
internal fun suggestedVersionAdviceForRuntime(
    runtimeId: String?,
    installedVersion: String?,
    floors: Map<String, String> = OAR_SUGGESTED_MIN_VERSIONS,
): RuntimeSuggestedVersionAdvice? {
    val cleanId = runtimeId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val oarId = RuntimeAuthRuntime.forRaftRuntime(cleanId)?.wire ?: cleanId
    val minVersion = floors[oarId] ?: return null
    val extracted = extractRuntimeSemver(installedVersion) ?: return null
    val cmp = compareRuntimeSemver(extracted, minVersion) ?: return null
    return if (cmp < 0) {
        RuntimeSuggestedVersionAdvice(
            oarRuntimeId = oarId,
            installedVersion = extracted,
            suggestedMinVersion = minVersion,
        )
    } else {
        null
    }
}

/** How an `unavailableOptions` entry's reason displays in the model dropdown (`slock #17086`). */
internal sealed interface UnavailableModelReasonDisplay {
    data class UpgradeVersion(val version: String) : UnavailableModelReasonDisplay

    data object UpgradeGeneric : UnavailableModelReasonDisplay

    data class Raw(val text: String) : UnavailableModelReasonDisplay
}

internal fun isUpgradeRelatedUnavailableReason(reason: String?): Boolean {
    val trimmed = reason?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    return UPGRADE_REASON_PATTERN.containsMatchIn(trimmed)
}

internal fun classifyUnavailableModelReason(reason: String?): UnavailableModelReasonDisplay? {
    val trimmed = reason?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (!UPGRADE_REASON_PATTERN.containsMatchIn(trimmed)) {
        return UnavailableModelReasonDisplay.Raw(trimmed)
    }
    val version = SEMVER_IN_REASON_PATTERN.find(trimmed)?.groupValues?.getOrNull(1)
    return if (version != null) {
        UnavailableModelReasonDisplay.UpgradeVersion(version)
    } else {
        UnavailableModelReasonDisplay.UpgradeGeneric
    }
}

/** Counts non-connection unavailable models whose reason indicates a CLI version/upgrade requirement. */
internal fun countUpgradeGatedUnavailableModels(
    unavailableOptions: List<RuntimeFormV2UnavailableChoice>,
): Int =
    unavailableOptions.count { entry ->
        entry.connectionId == null && isUpgradeRelatedUnavailableReason(entry.reason)
    }

/**
 * Unified model-field upgrade hint (`botiverse/slock` `#17086`), ordered by priority so at most
 * one hint renders under the model field:
 * 1. [RuntimeOutdated] (`isDanger = true`) — `sourceStatus.reason == "runtime_outdated" && !stale`
 * 2. [UnavailableModels] (`isDanger = false`) — Layer 1: >= 1 upgrade-gated unavailable model
 * 3. [SuggestedVersion] (`isDanger = false`) — Layer 2: detected CLI version < OAR suggested floor
 */
internal sealed interface RuntimeModelUpgradeHint {
    val isDanger: Boolean

    data object RuntimeOutdated : RuntimeModelUpgradeHint {
        override val isDanger: Boolean = true
    }

    data class UnavailableModels(val count: Int) : RuntimeModelUpgradeHint {
        override val isDanger: Boolean = false
    }

    data class SuggestedVersion(
        val installedVersion: String,
        val suggestedMinVersion: String,
    ) : RuntimeModelUpgradeHint {
        override val isDanger: Boolean = false
    }
}

internal fun resolveModelUpgradeHint(
    runtimeId: String?,
    installedVersion: String?,
    sourceStatus: RuntimeFormV2SourceStatus?,
    unavailableOptions: List<RuntimeFormV2UnavailableChoice>,
    floors: Map<String, String> = OAR_SUGGESTED_MIN_VERSIONS,
): RuntimeModelUpgradeHint? {
    if (sourceStatus != null) {
        return if (sourceStatus.reason == "runtime_outdated" && !sourceStatus.stale) {
            RuntimeModelUpgradeHint.RuntimeOutdated
        } else {
            null
        }
    }
    val upgradeGatedCount = countUpgradeGatedUnavailableModels(unavailableOptions)
    if (upgradeGatedCount > 0) {
        return RuntimeModelUpgradeHint.UnavailableModels(upgradeGatedCount)
    }
    val advice = suggestedVersionAdviceForRuntime(runtimeId, installedVersion, floors)
    if (advice != null) {
        return RuntimeModelUpgradeHint.SuggestedVersion(
            installedVersion = advice.installedVersion,
            suggestedMinVersion = advice.suggestedMinVersion,
        )
    }
    return null
}

/** Joins two localized sentences without inserting an extra ASCII space after CJK punctuation. */
internal fun joinLocalizedSentences(first: String, second: String): String {
    val left = first.trimEnd()
    val right = second.trimStart()
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left
    return if (CJK_TERMINAL_PUNCT.containsMatchIn(left)) "$left$right" else "$left $right"
}

/** Gap before an inline action link (`Upgrade` / `Retry`): empty after CJK full-width punctuation, else `" "`. */
internal fun inlineActionGap(beforeText: String): String {
    val trimmed = beforeText.trimEnd()
    if (trimmed.isEmpty()) return ""
    return if (CJK_TERMINAL_PUNCT.containsMatchIn(trimmed)) "" else " "
}

/** True when an inline model-field upgrade succeeded (either upgraded or already at latest). */
internal fun isRuntimeUpgradeSuccess(state: ComputerRuntimeUpgradeState): Boolean =
    state is ComputerRuntimeUpgradeState.Done &&
        (state.result is RuntimeUpgradeResult.Upgraded || state.result is RuntimeUpgradeResult.Current)

internal data class RuntimeModelUpgradeFailureCopy(
    val message: StringResource,
    val retryable: Boolean,
)

/** Specific inline failure copy & retryability for model-field CLI upgrades (`slock #17086`). */
internal fun runtimeModelUpgradeFailureCopy(state: ComputerRuntimeUpgradeState): RuntimeModelUpgradeFailureCopy? =
    when (state) {
        ComputerRuntimeUpgradeState.Idle, ComputerRuntimeUpgradeState.Running -> null
        is ComputerRuntimeUpgradeState.Refused ->
            when (state.reason) {
                ComputerRuntimeOperationRefusal.ComputerOffline ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_offline, retryable = true)
                ComputerRuntimeOperationRefusal.ComputerTimeout ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_timeout, retryable = true)
                ComputerRuntimeOperationRefusal.RateLimited ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_rate_limited, retryable = true)
                ComputerRuntimeOperationRefusal.ComputerUpgradeRequired ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_computer_too_old, retryable = false)
                ComputerRuntimeOperationRefusal.Failed ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed, retryable = true)
            }
        is ComputerRuntimeUpgradeState.Done ->
            when (val result = state.result) {
                is RuntimeUpgradeResult.Upgraded, is RuntimeUpgradeResult.Current -> null
                is RuntimeUpgradeResult.Unchanged ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_unchanged, retryable = true)
                is RuntimeUpgradeResult.Failed ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed, retryable = true)
                is RuntimeUpgradeResult.Unsupported ->
                    if (result.reason == RuntimeUpgradeUnsupportedReason.RequiresTerminal) {
                        RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_requires_terminal, retryable = false)
                    } else {
                        RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_unsupported_installation, retryable = false)
                    }
                RuntimeUpgradeResult.NotInstalled ->
                    RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_not_installed, retryable = false)
                is RuntimeUpgradeResult.Error ->
                    if (result.code == RuntimeOperationErrorCode.InProgress) {
                        RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_in_progress, retryable = true)
                    } else {
                        RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed, retryable = true)
                    }
            }
    }

/** Minimal ops interface for inline model-field upgrade (injectable in Robolectric UI tests). */
internal interface RuntimeModelUpgradeOps {
    suspend fun view(force: Boolean = false): RuntimeAuthView?

    suspend fun upgrade(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeUpgradeResult>
}

internal class StoreRuntimeModelUpgradeOps(
    private val store: SharedRuntimeAccountUsageStore,
    private val owner: SharedComputersOwner,
    private val serverId: String,
    private val machineId: String,
) : RuntimeModelUpgradeOps {
    override suspend fun view(force: Boolean): RuntimeAuthView? =
        store.runtimeAuthView(owner, serverId, machineId, force)

    override suspend fun upgrade(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeUpgradeResult> =
        store.upgradeRuntime(owner, serverId, machineId, runtime)
}

internal data class RuntimeModelUpgradeTarget(
    val serverId: String,
    val machineId: String,
    val runtimeId: String,
    val runtimeVersion: String? = null,
    /**
     * `true` = viewer may manage/upgrade this Computer; `false` = non-owner/non-admin (skips
     * `GET …/runtime-auth`, shows owner guidance); `null` = unknown/loading (no fetch yet, no
     * premature non-owner flash).
     */
    val canManage: Boolean? = null,
    val ops: RuntimeModelUpgradeOps? = null,
)

@Composable
internal fun rememberRuntimeModelUpgradeTarget(
    access: MembersRuntimeAccountUsageAccess?,
    serverId: String?,
    machineId: String?,
    runtimeId: String?,
    machineOption: SharedCreateAgentMachineOption? = null,
): RuntimeModelUpgradeTarget? {
    val cleanServerId = serverId?.trim().orEmpty()
    val cleanMachineId = machineId?.trim().orEmpty()
    val cleanRuntimeId = runtimeId?.trim().orEmpty()
    if (access == null || cleanServerId.isEmpty() || cleanMachineId.isEmpty() || cleanRuntimeId.isEmpty()) {
        return null
    }
    val computersState = access.computersStateFlow?.collectAsState()?.value
    val serverSlice = computersState?.selectComputerServerSlice(cleanServerId)
    val matchedMachine = serverSlice?.machines?.firstOrNull { it.id == cleanMachineId }
    // Prefer live ComputersStore snapshot (updates right after an upgrade) over static option snapshot.
    val runtimeVersion =
        matchedMachine?.runtimeVersions?.get(cleanRuntimeId)
            ?: machineOption?.runtimeVersions?.get(cleanRuntimeId)
    val canManage: Boolean? =
        when {
            access.canViewMachine != null -> access.canViewMachine.invoke(cleanServerId, cleanMachineId)
            access.viewer == null -> null
            access.viewer.canEditMachines -> true
            machineOption?.usageFactsKnown == true ->
                canViewMachineRuntimeAccountUsage(
                    attachedByCurrentUser = machineOption.attachedByCurrentUser,
                    creatorId = machineOption.creatorId,
                    currentUserId = access.viewer.currentUserId,
                    canEditMachines = access.viewer.canEditMachines,
                )
            matchedMachine != null ->
                canViewMachineRuntimeAccountUsage(
                    attachedByCurrentUser = matchedMachine.attachedByCurrentUser,
                    creatorId = matchedMachine.creatorUserId ?: matchedMachine.creator?.id,
                    currentUserId = access.viewer.currentUserId,
                    canEditMachines = access.viewer.canEditMachines,
                )
            serverSlice?.hasSnapshot == true -> false
            else -> null
        }
    val owner = access.owner
    val ops =
        remember(canManage, access.store, owner, cleanServerId, cleanMachineId) {
            if (canManage == true && owner != null) {
                StoreRuntimeModelUpgradeOps(access.store, owner, cleanServerId, cleanMachineId)
            } else {
                null
            }
        }
    return remember(cleanServerId, cleanMachineId, cleanRuntimeId, runtimeVersion, canManage, ops) {
        RuntimeModelUpgradeTarget(
            serverId = cleanServerId,
            machineId = cleanMachineId,
            runtimeId = cleanRuntimeId,
            runtimeVersion = runtimeVersion,
            canManage = canManage,
            ops = ops,
        )
    }
}
