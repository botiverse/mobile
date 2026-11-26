package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private val RuntimeServiceTierIdRegex = Regex("^[a-z][a-z0-9_-]{0,31}$")

/**
 * Codex tiers that are not a faster speed (shared `CODEX_NON_FAST_SERVICE_TIERS`,
 * `packages/shared/src/runtimeConfigPrimitives.ts`, botiverse/slock #16868).
 */
@RaftCoreInternalApi val CodexNonFastServiceTiers: Set<String> = setOf("default", "flex")

/**
 * Shared `parseActivityServiceTier` (`packages/shared/src/runtimeConfigPrimitives.ts`,
 * botiverse/slock #16868): parses an untrusted live `serviceTier` carrier from REST or realtime
 * JSON. Keeps only a valid runtime-native tier id (`^[a-z][a-z0-9_-]{0,31}$`, e.g. `default`,
 * `priority`, `fast`, `ultrafast`, `flex`); anything else returns null.
 */
@RaftCoreInternalApi fun parseAgentServiceTier(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return normalizeAgentServiceTier(primitive.content)
}

/** Keeps a valid runtime-native service tier id, normalizing blank or invalid values to null. */
@RaftCoreInternalApi fun normalizeAgentServiceTier(value: String?): String? =
    value?.takeIf { RuntimeServiceTierIdRegex.matches(it) }
