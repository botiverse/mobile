package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
    /**
     * [status] / [attestStatus]: what the trace POST / the attestation request answer (0: no HTTP
     * response); [attestStatusByServer] answers instead for its servers. [sendErrorFor]: the POST of
     * that batch throws instead. [account]: who the session is signed in as, so what every request
     * authenticates as. [onAttest] runs as an attestation request leaves (before the session it
     * authenticates with is read), [onSend] while a POST is out (before its answer). [batches] is
     * every POST tried, [accepted] those answered 2xx, [sendAtMillis] when each was tried on [clock],
     * [issuedTo] the account each attestation was issued to.
     */
    private class RecordingTransport(
        var status: Int = 200,
        var attestStatus: Int = 200,
        var sendErrorFor: (RaftWebTraceBatchRequest) -> Throwable? = { null },
        private val clock: () -> Long = { 0L },
    ) : RaftClientTraceTransport {
        var account: String? = "user-1"
        val attestStatusByServer = mutableMapOf<ServerId, Int>()
        var onAttest: suspend () -> Unit = {}
        var onSend: suspend () -> Unit = {}
        val attested = mutableListOf<ServerId>()
        val issuedTo = mutableMapOf<String, String?>()
        val batches = mutableListOf<RaftWebTraceBatchRequest>()
        val accepted = mutableListOf<RaftWebTraceBatchRequest>()
        val sendAtMillis = mutableListOf<Long>()

        override suspend fun currentAccount(): String? = account

        override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer {
            attested += serverId
            onAttest()
            val signedIn = account
            val answer = attestStatusByServer[serverId] ?: attestStatus
            if (answer !in 200..299) return RaftScopeAttestationAnswer(answer)
            val token = "att-${attested.size}"
            issuedTo[token] = signedIn
            return RaftScopeAttestationAnswer(
                status = answer,
                response = RaftScopeAttestationResponse(attestation = token, expiresAt = "2026-11-11T10:10:00Z"),
            )
        }

        override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
            batches += batch
            sendAtMillis += clock()
            onSend()
            sendErrorFor(batch)?.let { throw it }
            val answer = status
            if (answer in 200..299) accepted += batch
            return answer
        }
    }

    /** 2026-11-11T10:00:00Z; [senderOnVirtualTime]'s clock is this plus the test's virtual time. */
    private val baseEpochMillis = 1_794_391_200_000L

    /** [signedIn]: the account the sender is told about ([RecordingTransport.account]'s); null: none yet. */
    private fun TestScope.senderOnVirtualTime(
        transport: RecordingTransport,
        privacy: MutableStateFlow<RaftPrivacyMode> = MutableStateFlow(RaftPrivacyMode.Off),
        networkRestored: Flow<Any> = emptyFlow(),
        log: (String) -> Unit = {},
        signedIn: String? = "user-1",
    ): RaftClientTraceEventSender =
        RaftClientTraceEventSender(
            scope = backgroundScope,
            nowEpochMillis = { baseEpochMillis + currentTime },
            defaultAttrs = { mapOf("clientPlatform" to "android") },
            privacyMode = privacy,
            networkRestored = networkRestored,
            log = log,
        ).also { sender ->
            sender.bindTransport(transport)
            signedIn?.let { sender.setAccount(it) }
        }

    /**
     * Event [n] of [server], noting [who] recorded it (the account the test's session was signed in
     * as); [numbers] reads them back from a batch (the dropped-count event has none).
     */
    private fun RaftClientTraceEventSender.emitNumbered(
        n: Int,
        server: String = "server-1",
        who: String = "user-1",
        traceId: String? = null,
        spanId: String? = null,
    ) {
        emit(
            server,
            RaftStateTransitionTraceName,
            buildJsonObject {
                put("n", n)
                put("who", who)
            },
            traceId = traceId,
            spanId = spanId,
        )
    }

    private fun RaftWebTraceBatchRequest.numbers(): List<Int> = events.mapNotNull { it.attrs["n"]?.jsonPrimitive?.int }

    /** Every POST tried carried only records of the account its attestation was issued to. */
    private fun RecordingTransport.assertEachPostCarriedOnlyItsAttestationsAccount(label: String) {
        for (batch in batches) {
            val recordedBy = batch.events.mapNotNull { it.attrs["who"]?.jsonPrimitive?.content }.toSet()
            val issued = issuedTo[batch.attestation]
            assertTrue(
                recordedBy.all { it == issued },
                "$label: ${batch.numbers()} recorded by $recordedBy went out under an attestation issued to $issued",
            )
        }
    }

    private fun RaftWebTraceBatchRequest.droppedReports(): List<RaftWebEventRecord> =
        events.filter { it.name == RaftClientTraceDroppedEventName }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
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
                defaultAttrs = { mapOf("clientPlatform" to "android", "appVersion" to "1.2.3") },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
            )
        sender.setAccount("user-1")
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

    /** `slock.attachment.upload_finished` joins the trace its upload requests sent as `traceparent`. */
    @Test
    fun anEventCanCarryTheTraceItBelongsTo() = runTest {
        val transport = RecordingTransport()
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { 1_794_391_200_000L },
                defaultAttrs = { mapOf("clientPlatform" to "ios") },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
            )
        sender.bindTransport(transport)
        sender.setAccount("user-1")

        sender.emit(
            "server-1",
            RaftAttachmentUploadFinishedTraceName,
            buildJsonObject { put("route", "legacy") },
            traceId = "0af7651916cd43dd8448eb211c80319c",
            spanId = "b7ad6b7169203331",
        )
        sender.emit("server-1", RaftStateTransitionTraceName, attrs)
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()

        val (upload, other) = transport.batches.single().events
        assertEquals("slock.attachment.upload_finished", upload.name)
        assertEquals("0af7651916cd43dd8448eb211c80319c", upload.traceId)
        assertEquals("b7ad6b7169203331", upload.spanId)
        assertEquals("legacy", upload.attrs.getValue("route").jsonPrimitive.content)
        assertEquals("ios", upload.attrs.getValue("clientPlatform").jsonPrimitive.content)
        assertEquals(null, other.traceId)
        assertEquals(null, other.spanId)
    }

    /** RAFT_PRIVACY_MODE (slock docs/observability/privacy-mode.md, web webTelemetryPrivacyHold.ts). */
    @Test
    fun eventsAreHeldUntilPrivacyModeIsKnownThenSentWhenOffAndDroppedWhenOn() = runTest {
        for (answer in listOf(RaftPrivacyMode.Off, RaftPrivacyMode.On)) {
            val transport = RecordingTransport()
            val privacy = MutableStateFlow(RaftPrivacyMode.Unknown)
            val sender =
                RaftClientTraceEventSender(
                    scope = backgroundScope,
                    nowEpochMillis = { 1_794_391_200_000L },
                    defaultAttrs = { emptyMap() },
                    privacyMode = privacy,
                )
            sender.bindTransport(transport)
            sender.setAccount("user-1")
            sender.emit("server-1", RaftStateTransitionTraceName, attrs)
            sender.emit("server-1", RaftStateTransitionTraceName, attrs)
            advanceTimeBy(3 * RaftClientTraceFlushDelayMillis)
            runCurrent()
            assertTrue(transport.attested.isEmpty() && transport.batches.isEmpty(), "$answer: nothing leaves while unknown, not even the attestation")
            assertEquals(2, sender.queuedCount, "$answer: held in memory")

            privacy.value = answer
            runCurrent()
            sender.emit("server-1", RaftStateTransitionTraceName, attrs)
            advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
            runCurrent()
            when (answer) {
                RaftPrivacyMode.Off -> assertEquals(listOf(3), transport.batches.map { it.events.size }, "held events are sent with the next one")
                else -> {
                    assertTrue(transport.attested.isEmpty() && transport.batches.isEmpty(), "on: nothing is ever sent")
                    assertEquals(0, sender.queuedCount, "on: what was held is dropped")
                }
            }
        }
    }

    /**
     * No HTTP answer (status 0) or a 5xx, from the attestation request or the
     * trace POST: the batch is kept and the next flush delivers it once, in order, as first built.
     */
    @Test
    fun aBatchThatGotNoAnswerOrA5xxIsResentOnceInOrderWithTheSameIdsAndTimes() = runTest {
        val failures: List<Pair<String, (RecordingTransport) -> Unit>> =
            listOf(
                "attestation without a response" to { it.attestStatus = 0 },
                "attestation 502" to { it.attestStatus = 502 },
                "post without a response" to { it.status = 0 },
                "post 503" to { it.status = 503 },
            )
        for ((label, breakIt) in failures) {
            val transport = RecordingTransport()
            val sender = senderOnVirtualTime(transport)
            breakIt(transport)
            val firstAt = baseEpochMillis + currentTime
            sender.emitNumbered(1, traceId = "0af7651916cd43dd8448eb211c80319c", spanId = "b7ad6b7169203331")
            advance(1_000L)
            sender.emitNumbered(2)
            advance(RaftClientTraceFlushDelayMillis - 1_000L)
            assertTrue(transport.accepted.isEmpty(), "$label: the first flush failed")
            assertEquals(2, sender.queuedCount, "$label: both events are kept")

            transport.attestStatus = 200
            transport.status = 200
            advance(RaftClientTraceRetryDelaysMillis.first())
            advance(3_600_000L)

            val delivered = transport.accepted.single().events
            assertEquals(listOf(1, 2), transport.accepted.single().numbers(), "$label: delivered once, in order")
            assertEquals(
                listOf(raftEpochMillisUtcIso(firstAt), raftEpochMillisUtcIso(firstAt + 1_000L)),
                delivered.map { it.time },
                "$label: the times are those of the events, not of the resend",
            )
            assertEquals(listOf("0af7651916cd43dd8448eb211c80319c", null), delivered.map { it.traceId }, label)
            assertEquals(listOf("b7ad6b7169203331", null), delivered.map { it.spanId }, label)
            assertTrue(transport.batches.all { it.events == delivered }, "$label: every attempt carried the same records")
            assertEquals(0, sender.queuedCount, label)
        }
    }

    /**
     * What is held belongs to the account that recorded it: sign-out or another account drops it and
     * the cached attestation, and an event recorded while no account is known is not kept for the
     * next one.
     */
    @Test
    fun signOutOrAnotherAccountDropsKeptEventsAndTheCachedAttestation() = runTest {
        val beforeSignIn = senderOnVirtualTime(RecordingTransport(), signedIn = null)
        beforeSignIn.emitNumbered(0)
        assertEquals(0, beforeSignIn.queuedCount, "before the first sign-in: not kept for whoever signs in")

        for (next in listOf<String?>(null, "user-2")) {
            val transport = RecordingTransport()
            val sender = senderOnVirtualTime(transport)
            sender.emitNumbered(1)
            sender.setAccount("user-1")
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf("att-1"), transport.accepted.map { it.attestation }, "$next: the same account again keeps what is queued")

            transport.status = 0
            sender.emitNumbered(2)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(1, sender.queuedCount)
            sender.setAccount(next)
            assertEquals(0, sender.queuedCount, "$next: dropped")
            // A late event (an upload settling after the sign-out, or the new account's first one).
            sender.emitNumbered(3, who = next ?: "user-1")
            assertEquals(if (next == null) 0 else 1, sender.queuedCount, "$next: nothing is kept while signed out")

            transport.status = 200
            transport.account = "user-2"
            sender.setAccount("user-2")
            // Short of the attestation's expiry, so a reused one would show below.
            advance(60_000L)
            sender.emitNumbered(4, who = "user-2")
            advance(RaftClientTraceFlushDelayMillis)
            val sentAfter = transport.accepted.drop(1)
            assertEquals(if (next == null) listOf(4) else listOf(3, 4), sentAfter.flatMap { it.numbers() }, "$next: the kept event is never sent")
            assertTrue(sentAfter.none { it.attestation == "att-1" }, "$next: user-1's attestation is not reused")
            assertTrue(sentAfter.all { it.droppedReports().isEmpty() }, "$next: not a counted drop")
            transport.assertEachPostCarriedOnlyItsAttestationsAccount("$next")
        }
    }

    /**
     * A login replaces user-1's session with user-2's while user-1's batch is out, before the app
     * shell reports the new account: user-1's records never go out under user-2's attestation,
     * user-2's first event (queued as user-1's) never goes out under user-1's, and an attestation
     * user-2's session got is not kept for user-1.
     */
    @Test
    fun aLoginReplacingTheSessionWhileABatchIsOutNeverSendsRecordsUnderTheOtherAccount() = runTest {
        val moments: List<Pair<String, (RecordingTransport) -> Unit>> =
            listOf(
                "as the attestation request leaves" to { transport -> transport.onAttest = { transport.account = "user-2" } },
                "during a POST that gets no answer" to { transport ->
                    transport.status = 0
                    transport.onSend = { transport.account = "user-2" }
                },
            )
        for ((label, switchDuring) in moments) {
            val transport = RecordingTransport()
            val logged = mutableListOf<String>()
            val sender = senderOnVirtualTime(transport, log = { logged += it })
            switchDuring(transport)
            sender.emitNumbered(1)
            advance(RaftClientTraceFlushDelayMillis)
            sender.emitNumbered(2, who = "user-2")
            transport.onAttest = {}
            transport.onSend = {}
            transport.status = 200
            advance(3_600_000L)
            assertTrue(transport.accepted.isEmpty(), "$label: nothing delivered under either account, got ${transport.accepted.map { it.numbers() }}")
            assertEquals(0, sender.queuedCount, "$label: dropped, not kept")

            sender.setAccount("user-2")
            sender.emitNumbered(3, who = "user-2")
            advance(RaftClientTraceFlushDelayMillis)
            val delivered = transport.accepted.single()
            assertEquals(listOf(3), delivered.numbers(), label)
            assertEquals("user-2", transport.issuedTo[delivered.attestation], "$label: under an attestation user-2's session asked for")
            transport.assertEachPostCarriedOnlyItsAttestationsAccount(label)
            for (reason in RaftClientTraceDropReason.entries) assertEquals(0, sender.droppedNotYetReported(reason), "$label: not a counted drop")
            assertTrue(logged.any { it.startsWith("client_trace_dropped reason=account_changed") }, "$label: logged: $logged")
        }
    }
}
