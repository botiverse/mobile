package build.raft.app.data.realtime

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.model.Message
import kotlinx.serialization.json.JsonObject

/**
 * Flag-gated canonical projection gateway for each authoritative message projection produced by
 * [RealtimeEventMapper] (`sync_core_messages_v1`). The gateway is handed the raw socket payload and the
 * message the legacy path produced from it; it MUST NOT mutate state or affect the returned event.
 *
 * The observation outcome (parity/divergence diagnostics) is emitted via the implementation's own
 * diagnostics channel; the RETURN VALUE is the reusable decoded [CanonicalMessageCarry] — the one canonical
 * decode of the frame, attached to the event for the shadow fold+persist lane so it is never decoded twice.
 * Null when the flag is off (zero decode) or the decode failed closed.
 *
 * Kept decoupled from the sync-core module on purpose: the default [NoOp] means the mapper carries no
 * flag/pipeline dependency, and unit tests exercise the mapper without the shadow path. The provider wires
 * the real implementation (flag gate + canonical pipeline) only when shadow mode is active.
 */
fun interface MessageShadowObserver {
    /** [eventName] is the realtime socket event; [rawElement] the raw payload; [mapped] the legacy projection. */
    fun observe(eventName: String, rawElement: JsonObject, mapped: Message): CanonicalMessageCarry?

    companion object {
        val NoOp: MessageShadowObserver = MessageShadowObserver { _, _, _ -> null }
    }
}
