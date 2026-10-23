/*
 * Shadow gateway no-apply invariants (sync-core messages; the `sync_core_messages_v1` flag is retired).
 *
 * Nails the rollout-safety properties LiBai flagged for #700 plus the #607 decode-once contract:
 *  - the raw frame is decoded exactly ONCE, and that same decoded object feeds both the
 *    shadow-compare and the returned carry the fold+persist lane consumes (no double decode);
 *  - the gateway's only outputs are a diagnostic line and the returned carry. It holds no store/UI
 *    reference (constructor takes a logger + seams only), so "shadow never applies" is
 *    structural, and asserted here end to end.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncCoreMessageShadowObserverTest {

    /** Countable wrapper around the real ingress decode — the #607 decode-once/zero-decode proof seam. */
    private class CountingDecoder {
        var invocations = 0
        var lastDecoded: CanonicalMessageCarry? = null

        fun decode(
            rawJson: JsonObject,
            face: ProductionFace,
            onViolation: (MessageWireCanonicalViolation) -> Unit,
        ): CanonicalMessageCarry? {
            invocations++
            return decodeCanonicalMessageWire(rawJson, face, onViolation).also { lastDecoded = it }
        }
    }

    private val mapper = RealtimeEventMapper()

    private fun legacyMessage(payload: JsonObject): Message =
        (mapper.map(RealtimeSocketEvents.MessageNew, payload) as MessageNew).message

    private fun payload(): JsonObject = buildJsonObject {
        put("id", "m-1")
        put("seq", 3L)
        put("channelId", "c-1")
        put("senderType", "user")
        put("senderId", "u-1")
        put("content", "hi")
        put("messageType", "chat")
        put("randomId", JsonNull)
        put("threadId", JsonNull)
        put("createdAt", "2026-07-11T20:00:00Z")
        put("senderName", "Alice")
    }

    @Test
    fun decodesOnce_compareAndCarryShareTheSameDecodedObject() {
        // One raw frame => exactly ONE decoder invocation; the shadow-compare (proven
        // to have run by its parity diagnostic) and the event carry the fold lane consumes are the SAME
        // decoded object — no double decode anywhere on the frame path.
        val decoder = CountingDecoder()
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(
            diagnosticsLogger = { logs.add(it) },
            decodeWire = decoder::decode,
        )
        val event = RealtimeEventMapper(messageShadowObserver = observer)
            .map(RealtimeSocketEvents.MessageNew, payload()) as MessageNew
        assertEquals(1, decoder.invocations, "one frame must be decoded exactly once")
        assertTrue(logs.single().contains("result=parity"), "compare must run over the single decode, got $logs")
        assertNotNull(event.decodedCanonical, "the decoded carry must be attached to the event")
        assertSame(decoder.lastDecoded, event.decodedCanonical, "compare and fold-lane carry must share the ONE decode")
    }

    @Test
    fun emitsExactlyOneParityDiagnostic_andDoesNotMutateMessage() {
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(diagnosticsLogger = { logs.add(it) })
        val p = payload()
        val message = legacyMessage(p)
        observer.observe(RealtimeSocketEvents.MessageNew, p, message)
        assertEquals(1, logs.size, "shadow observe must emit exactly one diagnostic, got $logs")
        assertTrue(logs.single().contains("result=parity"), "expected parity, got ${logs.single()}")
        // The authoritative message is the observer's INPUT; it is an immutable data class and must be
        // returned to the caller untouched — the sole output is the diagnostic above (no-apply invariant).
        assertEquals(legacyMessage(p), message, "observer must not mutate the authoritative message")
    }

    @Test
    fun decodeFailure_emitsDecodeFailedDiagnostic() {
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(diagnosticsLogger = { logs.add(it) })
        // content omitted => canonical-required fail-close on the shadow path; legacy still yields a message.
        val p = buildJsonObject {
            put("id", "m-2")
            put("seq", 4L)
            put("channelId", "c-1")
            put("senderType", "user")
            put("senderId", "u-1")
            put("messageType", "chat")
            put("randomId", JsonNull)
            put("threadId", JsonNull)
            put("createdAt", "2026-07-11T20:00:00Z")
        }
        val carry = observer.observe(RealtimeSocketEvents.MessageNew, p, legacyMessage(p))
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("result=decode_failed"), "expected decode_failed, got ${logs.single()}")
        assertTrue(logs.single().contains("content"), "diagnostic should name the missing required field")
        assertNull(carry, "a fail-closed decode must return a null carry (fold lane skips the frame)")
    }

    @Test
    fun nonMessageEvent_isIgnored() {
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(diagnosticsLogger = { logs.add(it) })
        val p = payload()
        // A message event name the fold does not cover (e.g. dm:new) yields no face => no shadow work.
        observer.observe(RealtimeSocketEvents.DirectMessageNew, p, legacyMessage(p))
        assertTrue(logs.isEmpty(), "non-fold event must not run shadow compare, got $logs")
    }
}
