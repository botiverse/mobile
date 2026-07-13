package build.raft.app.network.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

const val CANONICAL_MESSAGE_V2_SCHEMA_VERSION: Int = 5
const val CANONICAL_MESSAGE_V2_ENVELOPE_KIND: String = "normalized-message-v2"
const val CANONICAL_REACTION_PREVIEW_LIMIT: Int = 3
const val CANONICAL_REACTION_LIMIT: Int = 30

@Serializable
data class CanonicalMessageV2ReactionFact(
    val reactions: List<ReactionFact>,
)

@Serializable
data class CanonicalMessageV2ReactionEnvelope(
    val schemaVersion: Int = CANONICAL_MESSAGE_V2_SCHEMA_VERSION,
    val kind: String = CANONICAL_MESSAGE_V2_ENVELOPE_KIND,
    val fact: CanonicalMessageV2ReactionFact,
)

/** One product flag, with normalized-schema eligibility as a separate mechanical apply gate. */
enum class NormalizedReactionRolloutMode {
    Off,
    ShadowOnly,
    SoleApply,
}

/**
 * Pins the single-flag truth table. Schema/envelope eligibility never creates a second rollout lane:
 * the existing sync-core flag enables observation, while sole-apply additionally requires both the global
 * eligibility switch and an exact normalized envelope.
 */
fun normalizedReactionRolloutMode(
    syncCoreMessagesEnabled: Boolean,
    envelopeSoleApplyEligible: Boolean,
    globalSoleApplyEligible: Boolean = CANONICAL_FACT_SOLE_APPLY_ELIGIBLE,
): NormalizedReactionRolloutMode =
    when {
        !syncCoreMessagesEnabled -> NormalizedReactionRolloutMode.Off
        globalSoleApplyEligible && envelopeSoleApplyEligible -> NormalizedReactionRolloutMode.SoleApply
        else -> NormalizedReactionRolloutMode.ShadowOnly
    }

private val canonicalMessageV2TripwireJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/**
 * Dormant sole-apply tripwire for the normalized reaction subtree. This does not activate V2 or change
 * [CANONICAL_FACT_SOLE_APPLY_ELIGIBLE]; it only proves that a future switch cannot mistake V1/wider bytes
 * for the exact normalized schema-5 shape.
 */
fun isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(text: String): Boolean =
    runCatching {
        isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(
            canonicalMessageV2TripwireJson.parseToJsonElement(text),
        )
    }.getOrDefault(false)

fun isCanonicalMessageV2ReactionEnvelopeSoleApplyEligible(element: JsonElement): Boolean {
    val envelope = element as? JsonObject ?: return false
    if (!envelope.hasExactKeys("fact", "kind", "schemaVersion")) return false
    if ((envelope["schemaVersion"] as? JsonPrimitive)?.intOrNull != CANONICAL_MESSAGE_V2_SCHEMA_VERSION) return false
    if ((envelope["kind"] as? JsonPrimitive)?.canonicalStringOrNull() != CANONICAL_MESSAGE_V2_ENVELOPE_KIND) return false

    val fact = envelope["fact"] as? JsonObject ?: return false
    if (!fact.hasExactKeys("reactions")) return false
    val reactions = fact["reactions"] as? JsonArray ?: return false
    if (reactions.size > CANONICAL_REACTION_LIMIT) return false

    val emojis = mutableListOf<String>()
    for (reactionElement in reactions) {
        val reaction = reactionElement as? JsonObject ?: return false
        if (!reaction.hasExactKeys("count", "emoji", "previewK")) return false
        val emoji = (reaction["emoji"] as? JsonPrimitive)?.canonicalStringOrNull() ?: return false
        val count = (reaction["count"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return false
        val preview = reaction["previewK"] as? JsonArray ?: return false
        if (preview.size > CANONICAL_REACTION_PREVIEW_LIMIT || preview.size > count) return false

        val actorIds = mutableListOf<String>()
        for (actorElement in preview) {
            val actor = actorElement as? JsonObject ?: return false
            if (!actor.hasExactKeys("displayName", "id")) return false
            val id = (actor["id"] as? JsonPrimitive)?.canonicalStringOrNull() ?: return false
            (actor["displayName"] as? JsonPrimitive)?.canonicalStringOrNull() ?: return false
            actorIds += id
        }
        if (!actorIds.isStrictlyIncreasing()) return false
        emojis += emoji
    }
    return emojis.isStrictlyIncreasing()
}

private fun JsonObject.hasExactKeys(vararg expected: String): Boolean =
    keys.size == expected.size && keys == expected.toSet()

private fun JsonPrimitive.canonicalStringOrNull(): String? {
    if (!isString || content.isBlank() || content != content.trim()) return null
    return content
}

private fun List<String>.isStrictlyIncreasing(): Boolean =
    indices.all { index -> index == 0 || this[index - 1] < this[index] }
