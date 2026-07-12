package build.raft.app.network.sync

import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.model.Message
import kotlinx.serialization.json.JsonObject

/**
 * Live-traffic implementation of [MessageShadowObserver] for the `sync_core_messages_v0` rollout.
 *
 * SHADOW mode only: for every authoritative message the legacy realtime path produces, it re-runs the same
 * raw payload through the canonical pipeline ([compareCanonicalShadow]) and emits a divergence diagnostic.
 * It NEVER applies anything to the store — the legacy path stays sole source of truth until sole-apply.
 *
 * The gate is [SharedServerFeatureFlags.isEnabledForAnyResolvedServer]: when the flag is off (default) this
 * is a single map lookup and returns immediately, so the observer is effectively free on the hot path.
 */
class SyncCoreMessageShadowObserver(
    private val featureFlags: SharedServerFeatureFlags,
    private val diagnosticsLogger: (String) -> Unit = {},
    // The realtime mapper builds the author purely from wire (no directory enrichment), so identity
    // hydration is the correct parity input. Kept injectable for tests / future directory-parity work.
    private val memberHydration: CanonicalMemberHydration = { it },
) : MessageShadowObserver {

    override fun observe(eventName: String, rawElement: JsonObject, mapped: Message) {
        if (!featureFlags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)) return
        val face = shadowFaceForEvent(eventName) ?: return
        val messageId = mapped.id.value
        when (val outcome = compareCanonicalShadow(rawElement, face, mapped, memberHydration)) {
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
        }
    }
}
