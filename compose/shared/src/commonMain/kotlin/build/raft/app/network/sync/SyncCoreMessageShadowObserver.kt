package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.model.Message
import kotlinx.serialization.json.JsonObject

/**
 * Live-traffic implementation of the [MessageShadowObserver] canonical projection gateway for the
 * `sync_core_messages_v1` rollout.
 *
 * SHADOW mode only: for every authoritative message the legacy realtime path produces, it decodes the same
 * raw payload through the canonical ingress EXACTLY ONCE, runs the shadow-compare over that pre-decoded
 * result ([compareCanonicalShadow]) and emits a divergence diagnostic, then RETURNS the same decoded carry
 * so the fold+persist and reaction lanes reuse it (no second decode). It NEVER applies anything to the
 * store — the legacy path stays sole source of truth until sole-apply.
 *
 * The gate is [SharedServerFeatureFlags.isEnabledForAnyResolvedServer]: when the flag is off (default) this
 * is a single map lookup and returns null immediately — no decode, so the gateway is effectively free on
 * the hot path.
 */
class SyncCoreMessageShadowObserver(
    private val featureFlags: SharedServerFeatureFlags,
    private val diagnosticsLogger: (String) -> Unit = {},
    // The realtime mapper builds the author purely from wire (no directory enrichment), so identity
    // hydration is the correct parity input. Kept injectable for tests / future directory-parity work.
    private val memberHydration: CanonicalMemberHydration = { it },
    // The one canonical-decode seam — defaults to the real ingress. Injectable/countable so tests can
    // prove decode-once (flag on) and zero-decode (flag off) mechanically.
    private val decodeWire: (JsonObject, ProductionFace, (MessageWireCanonicalViolation) -> Unit) -> CanonicalMessageCarry? =
        ::decodeCanonicalMessageWire,
) : MessageShadowObserver {

    override fun observe(eventName: String, rawElement: JsonObject, mapped: Message): CanonicalMessageCarry? {
        if (!featureFlags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)) return null
        val face = shadowFaceForEvent(eventName) ?: return null
        val messageId = mapped.id.value
        var violation: MessageWireCanonicalViolation? = null
        val decoded = decodeWire(rawElement, face) { violation = it }
        val outcome = decoded?.let { compareCanonicalShadow(it, face, mapped, memberHydration) }
            ?: MessageShadowOutcome.DecodeFailed(face, violation?.missingOrInvalidRequiredFields ?: emptySet())
        when (outcome) {
            MessageShadowOutcome.Parity ->
                diagnosticsLogger("sync_core_shadow face=$face result=parity msg=$messageId")
            is MessageShadowOutcome.DecodeFailed ->
                diagnosticsLogger(
                    "sync_core_shadow face=$face result=decode_failed msg=$messageId " +
                        "missing=${outcome.missingOrInvalidRequiredFields.sorted().joinToString(",")}",
                )
            is MessageShadowOutcome.Divergent ->
                diagnosticsLogger(
                    "sync_core_shadow face=$face result=divergent msg=$messageId " +
                        "fields=${outcome.fields.joinToString(",")}",
                )
            is MessageShadowOutcome.ProjectionRejected ->
                diagnosticsLogger("sync_core_shadow face=$face result=projection_rejected msg=$messageId")
            is MessageShadowOutcome.EnvelopeDecodeFailed ->
                diagnosticsLogger("sync_core_shadow face=$face result=envelope_decode_failed msg=$messageId")
            is MessageShadowOutcome.EnvelopeRoundTripDivergent ->
                diagnosticsLogger(
                    "sync_core_shadow face=$face result=envelope_roundtrip_divergent msg=$messageId " +
                        "fields=${outcome.fields.joinToString(",")}",
                )
        }
        return decoded
    }
}
