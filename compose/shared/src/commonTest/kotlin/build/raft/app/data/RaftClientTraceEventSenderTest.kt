package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Client trace events go the web's way (slock packages/web/src/utils/webAuthTrace.ts): a
 * `web-trace-batch:create` attestation, then `/api/web-traces` events batched after 4 s, with
 * `clientPlatform` beside each event's own attributes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftClientTraceEventSenderTest {
    private class RecordingTransport(var status: Int = 200) : RaftClientTraceTransport {
        val attested = mutableListOf<ServerId>()
        val batches = mutableListOf<RaftWebTraceBatchRequest>()

        override suspend fun attest(serverId: ServerId): RaftScopeAttestationResponse {
            attested += serverId
            return RaftScopeAttestationResponse(attestation = "att-${attested.size}", expiresAt = "2026-11-11T10:10:00Z")
        }

        override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
            batches += batch
            return status
        }
    }

    private val attrs = buildJsonObject { put("inbox.inplace.result", "match") }

    @Test
    fun eventsAreBatchedWithTheClientPlatformAndReuseTheAttestationUntilRejected() = runTest {
        val transport = RecordingTransport()
        // 2026-11-11T10:00:00Z: the attestation is valid for ten more minutes.
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { 1_794_391_200_000L },
                defaultAttrs = { mapOf("clientPlatform" to "android", "appVersion" to "1.2.3") }
            )
        sender.emit("server-1", RaftStateTransitionTraceName, attrs)
        assertTrue(transport.batches.isEmpty())
        sender.bindTransport(transport)
        sender.emit("server-1", RaftStateTransitionTraceName, attrs)
        sender.emit("server-1", RaftStateTransitionTraceName, attrs)
        advanceTimeBy(RaftClientTraceFlushDelayMillis - 1L)
        runCurrent()
        assertTrue(transport.batches.isEmpty(), "nothing before the flush delay")
        advanceTimeBy(2L)
        runCurrent()

        val batch = transport.batches.single()
        assertEquals("att-1", batch.attestation)
        assertEquals(2, batch.events.size, "an event emitted before the transport was bound is dropped")
        assertTrue(batch.records.isEmpty())
        with(batch.events.first()) {
            assertEquals("event", type)
            assertEquals(1, schemaVersion)
            assertEquals("slock.state.transition", name)
            assertEquals("web", surface)
            assertEquals("match", this.attrs.getValue("inbox.inplace.result").jsonPrimitive.content)
            assertEquals("android", this.attrs.getValue("clientPlatform").jsonPrimitive.content)
            assertEquals("1.2.3", this.attrs.getValue("appVersion").jsonPrimitive.content)
        }

        // The next flush reuses the attestation; a 401 drops it, so the one after asks again.
        transport.status = 401
        sender.emit("server-1", RaftStateTransitionTraceName, attrs)
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        transport.status = 200
        sender.emit("server-1", RaftStateTransitionTraceName, attrs)
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        assertEquals(listOf("att-1", "att-1", "att-2"), transport.batches.map { it.attestation })
        assertEquals(listOf(ServerId("server-1"), ServerId("server-1")), transport.attested)
    }
}
