package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftFieldDescription
import org.jetbrains.compose.resources.StringResource

/**
 * What a runtime can do in Raft on the host it runs on by default (slock shared
 * `RuntimeRaftCapabilities`): [midTurnSteering], a message sent while it works reaches it in the
 * same turn; [subagents], it can split work into parallel sub-agents; [compactionReported], it
 * tells Raft when it compacts its context.
 */
internal data class RuntimeRaftCapabilities(
    val midTurnSteering: Boolean,
    val subagents: Boolean,
    val compactionReported: Boolean
)

/**
 * slock `RUNTIME_RAFT_CAPABILITIES` and `RECOMMENDED_RUNTIME_IDS`
 * (packages/shared/src/runtimeCatalog.ts) at [SLOCK_SHA]. The vendored copy
 * `commonTest/resources/contract-cases/runtime-raft-capabilities/` carries the same values and SHA
 * (RuntimeRaftCapabilitiesContractTest); `scripts/sync_slock_runtime_capabilities.py --check`
 * (contract-cases-drift.yml) fails when slock's table moves on. Update both together.
 */
internal object RuntimeRaftCapabilityTable {
    const val SLOCK_SHA = "e053e8f202ede6acb8e6f65364380c34520e11f1"

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
            "dsh" to RuntimeRaftCapabilities(midTurnSteering = true, subagents = false, compactionReported = false)
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

/** [runtimeId]'s hint, or null when it has every capability, Raft doesn't know it, or no copy exists for its combination. */
internal fun runtimeCapabilityHint(runtimeId: String): RuntimeCapabilityHint? {
    val capabilities = RuntimeRaftCapabilityTable.capabilities[runtimeId] ?: return null
    val missing = missingRuntimeCapabilities(capabilities)
    return RuntimeCapabilityHint.entries.firstOrNull { it.missing == missing }
}

/** Claude Code and Codex (slock `isRecommendedRuntime`). */
internal fun isRecommendedRuntime(runtimeId: String): Boolean = runtimeId in RuntimeRaftCapabilityTable.recommendedRuntimeIds

/**
 * Limited: [runtimeId] can't take new messages while it works (no mid-turn steering; web
 * `isLimitedRuntime`, cindyz #triage:#524). From the table, not a list; an unknown runtime is not limited.
 */
internal fun isLimitedRuntime(runtimeId: String): Boolean = RuntimeRaftCapabilityTable.capabilities[runtimeId]?.midTurnSteering == false

/**
 * A runtime picker row's guidance (web `runtimePickerGuidance`): [recommended] and [limited] are
 * badges after the name in the list only; [hint] is said under the field once the runtime is
 * chosen. A recommended runtime has neither of the others; a [deprecated] one has none.
 */
internal data class RuntimePickerGuidance(
    val recommended: Boolean = false,
    val limited: Boolean = false,
    val hint: RuntimeCapabilityHint? = null
)

internal fun runtimePickerGuidance(runtimeId: String, deprecated: Boolean): RuntimePickerGuidance =
    when {
        deprecated -> RuntimePickerGuidance()
        isRecommendedRuntime(runtimeId) -> RuntimePickerGuidance(recommended = true)
        else -> RuntimePickerGuidance(limited = isLimitedRuntime(runtimeId), hint = runtimeCapabilityHint(runtimeId))
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
