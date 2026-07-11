package build.raft.app.data.realtime

import build.raft.app.model.Message
import kotlinx.serialization.json.JsonObject

/**
 * Observes each authoritative message projection produced by [RealtimeEventMapper] for the flag-gated
 * sync-core shadow-compare (`sync_core_messages_v0`). The observer is handed the raw socket payload and the
 * message the legacy path produced from it; it MUST NOT mutate state or affect the returned event.
 *
 * Kept decoupled from the sync-core module on purpose: the default [NoOp] means the mapper carries no
 * flag/pipeline dependency, and unit tests exercise the mapper without the shadow path. The provider wires
 * the real implementation (flag gate + canonical pipeline) only when shadow mode is active.
 */
fun interface MessageShadowObserver {
    /** [eventName] is the realtime socket event; [rawElement] the raw payload; [mapped] the legacy projection. */
    fun observe(eventName: String, rawElement: JsonObject, mapped: Message)

    companion object {
        val NoOp: MessageShadowObserver = MessageShadowObserver { _, _, _ -> }
    }
}
