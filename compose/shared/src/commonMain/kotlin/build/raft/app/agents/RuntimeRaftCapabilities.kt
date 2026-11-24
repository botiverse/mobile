package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftFieldDescription
import org.jetbrains.compose.resources.StringResource

/**
 * slock `RUNTIME_RAFT_CAPABILITIES` and `RECOMMENDED_RUNTIME_IDS`
 * (packages/shared/src/runtimeCatalog.ts) at [SLOCK_SHA]. The vendored copy
 * `commonTest/resources/contract-cases/runtime-raft-capabilities/` carries the same values and SHA
 * (RuntimeRaftCapabilitiesContractTest); `scripts/sync_slock_runtime_capabilities.py --check`
 * (contract-cases-drift.yml) fails when slock's table moves on. Update both together.
 */
internal object RuntimeRaftCapabilityTable {
    const val SLOCK_SHA = "c0f6195c6efacc2d906c69e28f130f097e12ba05"

    val recommendedRuntimeIds: List<String> = listOf("claude", "codex")

    val capabilities: Map<String, RuntimeRaftCapabilities> =
        mapOf(
            "claude" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = true, compactionReported = true),
            "codex" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = true, compactionReported = true),
            "grok" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = false, compactionReported = false),
            "builtin" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = false, compactionReported = true),
            "antigravity" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = false, compactionReported = false),
            "kimi-sdk" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = true, compactionReported = true),
            "kimi" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = false, compactionReported = true),
            "copilot" to RuntimeRaftCapabilities(midTurnSteering = false, subagents = false, compactionReported = false),
            "cursor" to RuntimeRaftCapabilities(midTurnSteering = false, subagents = false, compactionReported = true),
            "gemini" to RuntimeRaftCapabilities(midTurnSteering = false, subagents = false, compactionReported = false),
            "opencode" to RuntimeRaftCapabilities(midTurnSteering = false, subagents = false, compactionReported = false),
            "pi" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = false, compactionReported = true),
            "qwen" to RuntimeRaftCapabilities(midTurnSteering = false, subagents = true, compactionReported = false),
            "qoder" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = true, compactionReported = false),
            "dsh" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = true, compactionReported = true)
        )
}

/**
 * The capability hint's copy, one whole sentence per missing-capability combination (web
 * `runtimeCapabilityHint.ts` HINTS, keys `runtime.capabilityHint.*`). [missing] is the web's key:
 * what is missing among steering (mid-turn), sub-agents and compaction (reported).
 */
internal enum class RuntimeCapabilityHint(val missing: String, val text: StringResource) {
    NoSubagents("subagents", Res.string.runtime_capability_hint_no_subagents),
    CompactionUnreported("compaction", Res.string.runtime_capability_hint_compaction_unreported),
    NoSubagentsCompactionUnreported("subagents+compaction", Res.string.runtime_capability_hint_no_subagents_compaction_unreported),
    NoSteeringCompactionUnreported("steering+compaction", Res.string.runtime_capability_hint_no_steering_compaction_unreported),
    NoSteeringNoSubagents("steering+subagents", Res.string.runtime_capability_hint_no_steering_no_subagents),
    NoSteeringNoSubagentsCompactionUnreported(
        "steering+subagents+compaction",
        Res.string.runtime_capability_hint_no_steering_no_subagents_compaction_unreported
    )
}

/** The capabilities [capabilities] lacks, as the web's HINTS key ("" when it has them all). */
internal fun missingRuntimeCapabilities(capabilities: RuntimeRaftCapabilities): String =
    listOfNotNull(
        "steering".takeUnless { capabilities.midTurnSteering },
        "subagents".takeUnless { capabilities.subagents },
        "compaction".takeUnless { capabilities.compactionReported }
    ).joinToString("+")

private fun resolveCapabilities(
    runtimeId: String,
    capabilities: ResolvedRuntimeRaftCapabilities?
): RuntimeRaftCapabilities? =
    capabilities?.toRuntimeRaftCapabilities() ?: RuntimeRaftCapabilityTable.capabilities[runtimeId]

/**
 * [runtimeId]'s hint, or null when it has every capability, Raft doesn't know it, or no copy exists
 * for its combination. Prefers server/Computer-reported [capabilities], falling back to the bundled
 * [RuntimeRaftCapabilityTable] only when absent (web `runtimeCapabilityHintId`, slock #16178).
 */
internal fun runtimeCapabilityHint(
    runtimeId: String,
    capabilities: ResolvedRuntimeRaftCapabilities? = null
): RuntimeCapabilityHint? {
    val resolved = resolveCapabilities(runtimeId, capabilities) ?: return null
    val missing = missingRuntimeCapabilities(resolved)
    return RuntimeCapabilityHint.entries.firstOrNull { it.missing == missing }
}

/** Claude Code and Codex (slock `isRecommendedRuntime`). */
internal fun isRecommendedRuntime(runtimeId: String): Boolean = runtimeId in RuntimeRaftCapabilityTable.recommendedRuntimeIds

/**
 * Limited: [runtimeId] can't take new messages while it works (no mid-turn steering; web
 * `isLimitedRuntime`, cindyz #triage:#524, slock #16178). From live [capabilities] (or bundled
 * fallback when absent), not a list; an unknown runtime is not limited.
 */
internal fun isLimitedRuntime(
    runtimeId: String,
    capabilities: ResolvedRuntimeRaftCapabilities? = null
): Boolean = resolveCapabilities(runtimeId, capabilities)?.midTurnSteering == false

/**
 * Whether a Recommended runtime still shows a limitation hint: only when a Computer explicitly
 * reported a missing capability ([RuntimeCapabilitySource.Machine]). Default-table fallbacks
 * ([RuntimeCapabilitySource.Default] or absent) keep Recommended runtimes hint-free (web
 * `isMachineReportedCapabilities`, slock #16178).
 */
internal fun isMachineReportedCapabilities(capabilities: ResolvedRuntimeRaftCapabilities?): Boolean =
    capabilities?.source == RuntimeCapabilitySource.Machine

/**
 * A runtime picker row's guidance (web `runtimePickerGuidance`, slock #16178): [recommended] and
 * [limited] are badges after the name in the list only; [hint] is said under the field once the
 * runtime is chosen. Recommended runtimes always keep their Recommended badge and show a hint only
 * when a Computer explicitly reported missing capabilities (`source == Machine`); a [deprecated]
 * runtime keeps its deprecated label alone.
 */
internal data class RuntimePickerGuidance(
    val recommended: Boolean = false,
    val limited: Boolean = false,
    val hint: RuntimeCapabilityHint? = null
)

internal fun runtimePickerGuidance(
    runtimeId: String,
    deprecated: Boolean,
    capabilities: ResolvedRuntimeRaftCapabilities? = null
): RuntimePickerGuidance {
    if (deprecated) return RuntimePickerGuidance()
    val hint = runtimeCapabilityHint(runtimeId, capabilities)
    if (isRecommendedRuntime(runtimeId)) {
        return RuntimePickerGuidance(
            recommended = true,
            hint = hint.takeIf { isMachineReportedCapabilities(capabilities) }
        )
    }
    return RuntimePickerGuidance(
        limited = isLimitedRuntime(runtimeId, capabilities),
        hint = hint
    )
}

/** The hint of [runtimeId] when it is one of these rows (the field's chosen value); none otherwise. */
internal fun RuntimePickerRows.selectedCapabilityHint(runtimeId: String): RuntimeCapabilityHint? =
    rows.firstOrNull { it.id == runtimeId }?.guidance?.hint

/**
 * The chosen runtime's capability hint, the runtime field's description under its select (web
 * StableField `hint`, slock #15551): `text-caption` `foreground-hint`. Nothing without a [hint].
 */
@Composable
internal fun RuntimeCapabilityHintText(hint: RuntimeCapabilityHint?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (hint == null) return
    RaftFieldDescription(stringResource(hint.text), modifier.testTag(RuntimeCapabilityHintTag), enabled = enabled)
}

internal const val RuntimeCapabilityHintTag = "runtime_capability_hint"

private val AntigravityOrchestratorModelIdRegex = Regex("^gemini-pro-orchestrator(?:$|-)")
private val AntigravityOrchestratorLabelRegex = Regex("^orchestrator(?:\\s*\\(|$)")

/**
 * Whether a model id (or typed display label) selects Antigravity's dual-model Orchestrator
 * (`gemini-pro-orchestrator`, `-neon`, `-argon`, `-g1-pro`, or typed `"Orchestrator (...)"`).
 * Web parity: `isAntigravityOrchestratorModel` in `packages/web/src/utils/runtimeCapabilityHint.ts`
 * (botiverse/slock #16311).
 */
internal fun isAntigravityOrchestratorModel(modelId: String): Boolean {
    val normalized = modelId.trim().lowercase()
    return AntigravityOrchestratorModelIdRegex.containsMatchIn(normalized) ||
        AntigravityOrchestratorLabelRegex.containsMatchIn(normalized)
}

/**
 * Model-specific hint for a runtime's Model field, or null when the selected model has no extra
 * Raft-specific caveat (web `runtimeModelHintId`, botiverse/slock #16311).
 */
internal fun runtimeModelHint(runtimeId: String?, modelId: String): StringResource? =
    if (runtimeId == "antigravity" && isAntigravityOrchestratorModel(modelId)) {
        Res.string.runtime_model_hint_antigravity_orchestrator
    } else {
        null
    }
