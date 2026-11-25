package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentProviderLimitWindow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Longest `deliveryHold.model` kept (shared `DELIVERY_HOLD_MODEL_MAX_LENGTH`, botiverse/slock
 * #16629): a display name such as `"Fable"`; a longer one is dropped.
 */
@RaftCoreInternalApi const val DeliveryHoldModelMaxLength: Int = 64

/**
 * Shared `parseDeliveryHoldActivityDiagnostic` (`packages/shared/src/index.ts`, botiverse/slock
 * #16333, #16629): parses an untrusted `deliveryHold` carrier from REST or realtime JSON.
 *
 * - An unknown reason, non-object shape, or missing/invalid `since` returns null (no hold).
 * - An unknown `detail` or `window` is dropped while keeping the hold (forward compatibility with newer daemons).
 * - `untilMs`, `model`, `window`, and `resetsAtMs` are kept only on `provider_limit`.
 * - `model` is trimmed and dropped if empty or longer than [DeliveryHoldModelMaxLength] (64) chars.
 * - `untilMs` and `resetsAtMs` are dropped if negative / non-finite / non-numeric.
 */
@RaftCoreInternalApi fun parseAgentDeliveryHold(element: JsonElement?): AgentDeliveryHold? {
    val obj = element as? JsonObject ?: return null
    val reasonStr = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val reason = AgentDeliveryHoldReason.fromWireValue(reasonStr) ?: return null
    val since = obj.finiteNonNegativeMsOrNull("since") ?: return null
    val rawDetail = (obj["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (reason) {
        AgentDeliveryHoldReason.CredentialInvalid ->
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.CredentialInvalid,
                credentialDetail = AgentCredentialHoldReason.fromWireValue(rawDetail),
                providerLimitDetail = null,
                untilMs = null,
                since = since,
                model = null,
                window = null,
                resetsAtMs = null,
            )
        AgentDeliveryHoldReason.ProviderLimit -> {
            val rawModel = (obj["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            val rawWindow = (obj["window"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                credentialDetail = null,
                providerLimitDetail = AgentProviderLimitHoldKind.fromWireValue(rawDetail),
                untilMs = obj.finiteNonNegativeMsOrNull("untilMs"),
                since = since,
                model = rawModel?.takeIf { it.isNotEmpty() && it.length <= DeliveryHoldModelMaxLength },
                window = AgentProviderLimitWindow.fromWireValue(rawWindow),
                resetsAtMs = obj.finiteNonNegativeMsOrNull("resetsAtMs"),
            )
        }
    }
}

private fun JsonObject.finiteNonNegativeMsOrNull(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.longOrNull?.let { return it.takeIf { value -> value >= 0L } }
    val doubleValue = primitive.doubleOrNull ?: return null
    if (!doubleValue.isFinite() || doubleValue < 0.0) return null
    return doubleValue.toLong()
}
