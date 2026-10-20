/*
 * Shadow gateway gate + no-apply invariants (sync_core_messages_v1).
 *
 * Nails the rollout-safety properties LiBai flagged for #700 plus the #607 decode-once contract:
 *  - flag OFF => the gateway does NO work: ZERO canonical decodes, compare never runs, zero diagnostics,
 *    null carry — nothing beyond one map lookup (the default-off hot path);
 *  - flag ON => the raw frame is decoded exactly ONCE, and that same decoded object feeds both the
 *    shadow-compare and the returned carry the fold+persist lane consumes (no double decode);
 *  - the gateway's only outputs are a diagnostic line and the returned carry. It holds no store/UI
 *    reference (constructor takes feature flags + a logger + seams only), so "shadow never applies" is
 *    structural, and asserted here end to end.
 */
package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
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

    private class FakeFlagsApi(private val enabled: Boolean) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"evaluations":[{"key":"$SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY","enabled":$enabled}]}""",
            )
    }

    private fun flags(enabled: Boolean, resolve: Boolean = true): SharedServerFeatureFlags {
        val f = SharedServerFeatureFlags(api = FakeFlagsApi(enabled))
        if (resolve) runBlocking { f.prefetch(ServerId("s-1")) }
        return f
    }

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
    fun flagOff_doesNoWorkAndEmitsNothing() {
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(flags(enabled = false), diagnosticsLogger = { logs.add(it) })
        val p = payload()
        observer.observe(RealtimeSocketEvents.MessageNew, p, legacyMessage(p))
        assertTrue(logs.isEmpty(), "flag off must produce zero diagnostics (no compare), got $logs")
    }

    @Test
    fun flagOff_zeroDecodesAndNullEventCarry() {
        // Gate (a): flag off => the gateway never invokes the canonical decoder AND the mapped event
        // carries a null canonical carry (flag-off pays zero decode cost on the hot path).
        val decoder = CountingDecoder()
        val observer = SyncCoreMessageShadowObserver(
            featureFlags = flags(enabled = false),
            decodeWire = decoder::decode,
        )
        val event = RealtimeEventMapper(messageShadowObserver = observer)
            .map(RealtimeSocketEvents.MessageNew, payload()) as MessageNew
        assertEquals(0, decoder.invocations, "flag off must never invoke the canonical decoder")
        assertNull(event.decodedCanonical, "flag off must attach a null carry to the event")
    }

    @Test
    fun flagOn_decodesOnce_compareAndCarryShareTheSameDecodedObject() {
        // Gate (b): flag on, one raw frame => exactly ONE decoder invocation; the shadow-compare (proven
        // to have run by its parity diagnostic) and the event carry the fold lane consumes are the SAME
        // decoded object — no double decode anywhere on the frame path.
        val decoder = CountingDecoder()
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(
            featureFlags = flags(enabled = true),
            diagnosticsLogger = { logs.add(it) },
            decodeWire = decoder::decode,
        )
        val event = RealtimeEventMapper(messageShadowObserver = observer)
            .map(RealtimeSocketEvents.MessageNew, payload()) as MessageNew
        assertEquals(1, decoder.invocations, "one frame must be decoded exactly once")
        assertTrue(logs.single().contains("result=parity"), "compare must run over the single decode, got $logs")
        assertNotNull(event.decodedCanonical, "flag on must attach the decoded carry to the event")
        assertSame(decoder.lastDecoded, event.decodedCanonical, "compare and fold-lane carry must share the ONE decode")
    }

    @Test
    fun flagUnresolved_isTreatedAsOff() {
        val logs = mutableListOf<String>()
        // Never prefetched: no resolved snapshot => gate defaults off, dark not broken.
        val observer = SyncCoreMessageShadowObserver(flags(enabled = true, resolve = false), diagnosticsLogger = { logs.add(it) })
        val p = payload()
        observer.observe(RealtimeSocketEvents.MessageNew, p, legacyMessage(p))
        assertTrue(logs.isEmpty(), "unresolved flags default OFF, got $logs")
    }

    @Test
    fun flagOn_emitsExactlyOneParityDiagnostic_andDoesNotMutateMessage() {
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(flags(enabled = true), diagnosticsLogger = { logs.add(it) })
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
    fun flagOn_decodeFailure_emitsDecodeFailedDiagnostic() {
        val logs = mutableListOf<String>()
        val observer = SyncCoreMessageShadowObserver(flags(enabled = true), diagnosticsLogger = { logs.add(it) })
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
        val observer = SyncCoreMessageShadowObserver(flags(enabled = true), diagnosticsLogger = { logs.add(it) })
        val p = payload()
        // A message event name the fold does not cover (e.g. dm:new) yields no face => no shadow work.
        observer.observe(RealtimeSocketEvents.DirectMessageNew, p, legacyMessage(p))
        assertTrue(logs.isEmpty(), "non-fold event must not run shadow compare, got $logs")
    }
}
