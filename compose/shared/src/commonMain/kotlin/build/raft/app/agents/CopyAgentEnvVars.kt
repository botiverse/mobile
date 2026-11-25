package build.raft.app

import build.raft.app.data.AgentSummary
import build.raft.app.model.RUNTIME_CONFIG_VERSION
import build.raft.app.model.isControlledRuntimeEnvKey
import build.raft.app.model.isExternalAgentRuntime
import build.raft.app.model.stripControlledRuntimeEnvVars

/**
 * Mirrors web `copyAgentEnvVars.ts` & `runtimeEnvVarsCopyContext.ts` (botiverse/slock #16606,
 * `#proj-runtime:#99`): pure helpers for listing editable same-Computer agents with copyable
 * Advanced env vars and merging them into the current draft without overwriting existing keys.
 */

internal data class CopyableEnvVarEntry(
    val key: String,
    val value: String,
)

internal data class CopyableAgentEnvVars(
    val agentId: String,
    val name: String,
    val avatarUrl: String?,
    val entries: List<CopyableEnvVarEntry>,
)

internal data class MergedCopiedEnvVars(
    val next: List<CopyableEnvVarEntry>,
    val addedCount: Int,
    val keptKeys: List<String>,
)

internal data class RuntimeEnvVarsCopySource(
    val candidates: List<CopyableAgentEnvVars>,
    val computerName: String,
    /** Changes when the target Computer or runtime changes, clearing any previous copy status line. */
    val resetKey: String,
)

/** Switch from plain dropdown to searchable dropdown past 8 candidates (Kirby #proj-runtime:#99). */
internal const val COPY_ENV_VARS_SEARCHABLE_THRESHOLD = 8

/** Raft's internal onboarding marker stored in envVars; never offered as a user variable. */
private val INTERNAL_ENV_VAR_KEYS = setOf("SLOCK_ONBOARDING_MEMORY_SEED")

/**
 * Mirrors web `canEditAgentRuntimeConfig` (`agentVisibility.ts`):
 * - rejects bounded public projections (`channel_summary`), deleted agents, App Bots, and external agents.
 * - allows server-wide `editAgents` (owner/admin) OR the human creator (`creatorType == "user" && creatorId == currentUserId`).
 */
internal fun canEditAgentRuntimeConfig(
    agent: AgentSummary,
    currentUserId: String?,
    canEditAgents: Boolean,
): Boolean {
    if (agent.profileProjection == "channel_summary") return false
    if (!agent.deletedAt.isNullOrBlank()) return false
    if (!agent.managedByAppInstallId.isNullOrBlank()) return false
    if (agent.isExternal || isExternalAgentRuntime(agent.runtime)) return false
    if (canEditAgents) return true
    return !currentUserId.isNullOrBlank() && agent.creatorType == "user" && agent.creatorId == currentUserId
}

/**
 * Mirrors `hydrateRuntimeConfigForm(agent).envVars`: reads Advanced env vars from `runtimeConfig`
 * when version matches [RUNTIME_CONFIG_VERSION], else falls back to top-level `envVars`, stripping
 * keys controlled by the agent's own runtime.
 */
internal fun hydrateAgentAdvancedEnvVars(agent: AgentSummary): Map<String, String>? {
    val runtime = agent.runtime?.trim().orEmpty()
    val raw =
        if (agent.runtimeConfigVersion == RUNTIME_CONFIG_VERSION) {
            agent.runtimeConfigEnvVars
        } else {
            agent.orderedEnvVars.ifEmpty { agent.envVars }.takeIf { it.isNotEmpty() }
        }
    return if (runtime.isNotEmpty()) stripControlledRuntimeEnvVars(runtime, raw) else raw?.takeIf { it.isNotEmpty() }
}

/**
 * Other agents on the same Computer that the viewer can already edit ([canEditAgentRuntimeConfig])
 * and that have at least one user-defined env var valid for [targetRuntimeId].
 *
 * Uses [hydrateAgentAdvancedEnvVars] (Advanced env vars only, never provider-mirrored fields) and
 * silently drops internal markers plus keys controlled by [targetRuntimeId] (which the server
 * rejects on save). No extra client-side secret rules: what the viewer can already read on the
 * source agent copies verbatim (#proj-runtime:60466d76 msg 74e6a975).
 */
internal fun copyableSameComputerEnvVarAgents(
    agents: List<AgentSummary>,
    machineId: String?,
    targetRuntimeId: String,
    excludeAgentId: String? = null,
    currentUserId: String? = null,
    canEditAgents: Boolean = false,
): List<CopyableAgentEnvVars> {
    val cleanMachineId = machineId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val cleanRuntime = targetRuntimeId.trim()
    val result = mutableListOf<CopyableAgentEnvVars>()
    for (agent in agents) {
        if (agent.machineId != cleanMachineId) continue
        if (!excludeAgentId.isNullOrBlank() && agent.id == excludeAgentId) continue
        if (!canEditAgentRuntimeConfig(agent, currentUserId, canEditAgents)) continue

        val rawEnv = hydrateAgentAdvancedEnvVars(agent) ?: continue
        val entries = mutableListOf<CopyableEnvVarEntry>()
        val seenKeys = HashSet<String>()
        for ((rawKey, value) in rawEnv) {
            val key = rawKey.trim()
            if (key.isEmpty()) continue
            val upper = key.uppercase()
            if (upper in INTERNAL_ENV_VAR_KEYS) continue
            if (cleanRuntime.isNotEmpty() && isControlledRuntimeEnvKey(cleanRuntime, key)) continue
            if (!seenKeys.add(upper)) continue
            entries += CopyableEnvVarEntry(key = key, value = value)
        }
        if (entries.isEmpty()) continue

        result +=
            CopyableAgentEnvVars(
                agentId = agent.id,
                name = agent.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: agent.name,
                avatarUrl = agent.avatarUrl?.takeIf { it.isNotBlank() },
                entries = entries,
            )
    }
    return result.sortedWith(compareBy<CopyableAgentEnvVars> { it.name.lowercase() }.thenBy { it.name })
}

/**
 * Append copied env vars without overwriting existing keys (case-insensitive).
 * Any completely blank placeholder row (`key.trim() == "" && value == ""`) is removed when new
 * rows are added so clicking "+ Add env var" then copying does not leave an empty row above the
 * copied ones.
 */
internal fun mergeCopiedEnvVars(
    current: List<CopyableEnvVarEntry>,
    source: List<CopyableEnvVarEntry>,
): MergedCopiedEnvVars {
    val existingKeys = HashMap<String, String>()
    for (entry in current) {
        val key = entry.key.trim()
        if (key.isEmpty()) continue
        val upper = key.uppercase()
        if (upper !in existingKeys) existingKeys[upper] = key
    }

    val toAdd = mutableListOf<CopyableEnvVarEntry>()
    val keptKeys = mutableListOf<String>()
    val seenSourceKeys = HashSet<String>()
    for (entry in source) {
        val key = entry.key.trim()
        if (key.isEmpty()) continue
        val upper = key.uppercase()
        if (!seenSourceKeys.add(upper)) continue
        val existing = existingKeys[upper]
        if (existing != null) {
            keptKeys += existing
            continue
        }
        toAdd += CopyableEnvVarEntry(key = key, value = entry.value)
    }

    if (toAdd.isEmpty()) {
        return MergedCopiedEnvVars(next = current.toList(), addedCount = 0, keptKeys = keptKeys)
    }

    val base = current.filter { it.key.trim().isNotEmpty() || it.value.isNotEmpty() }
    return MergedCopiedEnvVars(
        next = base + toAdd,
        addedCount = toAdd.size,
        keptKeys = keptKeys,
    )
}

/** Filters copy candidates by agent name or any env var key (case-insensitive), matching web `candidateSearchLabel`. */
internal fun filterCopyableAgentEnvVars(
    candidates: List<CopyableAgentEnvVars>,
    query: String,
): List<CopyableAgentEnvVars> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return candidates
    return candidates.filter { candidate ->
        candidate.name.lowercase().contains(needle) ||
            candidate.entries.any { it.key.lowercase().contains(needle) }
    }
}
