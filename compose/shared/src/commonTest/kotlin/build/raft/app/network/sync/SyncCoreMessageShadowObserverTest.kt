/*
 * Shadow observer gate + no-apply invariants (sync_core_messages_v0).
 *
 * Nails two rollout-safety properties LiBai flagged for #700:
 *  - flag OFF => the observer does NO work: compare never runs, zero diagnostics, zero cost beyond one
 *    map lookup (the default-off hot path);
 *  - the observer's ONLY output is a diagnostic line. It holds no store/UI reference (constructor takes
 *    feature flags + a logger only), so "shadow never applies" is structural, and asserted here end to end.
 */
package build.raft.app.network.sync

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SlockFeatureFlagsApi
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
import kotlin.test.assertTrue

class SyncCoreMessageShadowObserverTest {

    private class FakeFlagsApi(private val enabled: Boolean) : SlockFeatureFlagsApi {
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

    private val mapper = RealtimeEventMapper()

    private fun legacyMessage(payload: JsonObject): Message =
        (mapper.map(RealtimeSocketEvents.MessageNew, payload) as MessageNew).message

    private fun payload(): JsonObject = buildJsonObject {
        put("id", "m-1")
        put("seq", 3L)
        put("channelId", "c-1")
        put("senderType", "human")
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
            put("senderType", "human")
            put("senderId", "u-1")
            put("messageType", "chat")
            put("randomId", JsonNull)
            put("threadId", JsonNull)
            put("createdAt", "2026-07-11T20:00:00Z")
        }
        observer.observe(RealtimeSocketEvents.MessageNew, p, legacyMessage(p))
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("result=decode_failed"), "expected decode_failed, got ${logs.single()}")
        assertTrue(logs.single().contains("content"), "diagnostic should name the missing required field")
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
