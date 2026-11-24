package build.raft.app.data

import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Shared `parseDeliveryHoldActivityDiagnostic` (`packages/shared/src/index.ts`, botiverse/slock #16333):
 * parses an untrusted `deliveryHold` carrier from REST or realtime JSON.
 *
 * - An unknown reason, non-object shape, or missing/invalid `since` returns null (no hold).
 * - An unknown `detail` is dropped while keeping the hold (forward compatibility with newer daemons).
 * - `untilMs` is kept only on `provider_limit` (and dropped if negative / non-finite / non-numeric).
 */
internal fun parseAgentDeliveryHold(element: JsonElement?): AgentDeliveryHold? {
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
            )
        AgentDeliveryHoldReason.ProviderLimit ->
            AgentDeliveryHold(
                reason = AgentDeliveryHoldReason.ProviderLimit,
                credentialDetail = null,
                providerLimitDetail = AgentProviderLimitHoldKind.fromWireValue(rawDetail),
                untilMs = obj.finiteNonNegativeMsOrNull("untilMs"),
                since = since,
            )
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
