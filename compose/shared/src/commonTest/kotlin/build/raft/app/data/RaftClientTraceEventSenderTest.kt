package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /**
     * A sender signed in as user-1 whose only event got no answer for 30 minutes and was given up:
     * one `aged_out` count waits for the next batch, nothing is queued and no retry waits. The
     * attestation requests (none was answered, so none is cached) answer again from then on.
     */
    private fun TestScope.senderWithOneAgedOutCount(
        transport: RecordingTransport,
        privacy: MutableStateFlow<RaftPrivacyMode> = MutableStateFlow(RaftPrivacyMode.Off),
    ): RaftClientTraceEventSender {
        transport.attestStatus = 0
        val sender = senderOnVirtualTime(transport, privacy = privacy)
        sender.emitNumbered(0)
        advance(RaftClientTraceGiveUpMillis + RaftClientTraceRetryDelaysMillis.last())
        assertEquals(1, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut), "setup: given up")
        assertEquals(0, sender.queuedCount, "setup: nothing held")
        transport.attestStatus = 200
        return sender
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

    /** A sampled unit (the `mobile.thread_open` span and its shift events) goes in one batch, under the same gate. */
    @Test
    fun aSpanAndItsEventsAreSentTogetherWithTheDefaultsAndNotUnderPrivacyMode() = runTest {
        for (mode in listOf(RaftPrivacyMode.Off, RaftPrivacyMode.On)) {
            val transport = RecordingTransport()
            val sender =
                RaftClientTraceEventSender(
                    scope = backgroundScope,
                    nowEpochMillis = { 1_794_391_200_000L },
                    defaultAttrs = { mapOf("clientPlatform" to "ios") },
                    privacyMode = MutableStateFlow(mode),
                )
            sender.bindTransport(transport)
            sender.setAccount("user-1")
            val span =
                RaftWebSpanRecord(
                    traceId = "0123456789abcdef0123456789abcdef",
                    spanId = "0123456789abcdef",
                    name = "mobile.thread_open",
                    startTime = "2026-11-11T10:00:00.000Z",
                    endTime = "2026-11-11T10:00:01.000Z",
                    attrs = buildJsonObject { put("end", "stable") },
                )
            val shift =
                RaftWebEventRecord(
                    name = "mobile.thread_open.shift",
                    time = "2026-11-11T10:00:00.500Z",
                    traceId = span.traceId,
                    spanId = span.spanId,
                    attrs = buildJsonObject { put("cause", "follow_latest") },
                )
            sender.emitSpan("server-1", span, listOf(shift))
            advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
            runCurrent()
            if (mode == RaftPrivacyMode.On) {
                assertTrue(transport.batches.isEmpty() && transport.attested.isEmpty(), "privacy mode: nothing sent")
                continue
            }
            val batch = transport.batches.single()
            with(batch.records.single()) {
                assertEquals("span", type)
                assertEquals("internal", kind)
                assertEquals("stable", attrs.getValue("end").jsonPrimitive.content)
                assertEquals("ios", attrs.getValue("clientPlatform").jsonPrimitive.content)
            }
            with(batch.events.single()) {
                assertEquals(span.traceId, traceId)
                assertEquals(span.spanId, spanId)
                assertEquals("ios", attrs.getValue("clientPlatform").jsonPrimitive.content)
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

    /** An HTTP refusal is final: the batch is dropped, not resent and not counted as given up. */
    @Test
    fun aRefusedBatchIsDroppedAndNeverResent() = runTest {
        val refusals: List<Pair<String, (RecordingTransport) -> Unit>> =
            listOf(
                "post 400" to { it.status = 400 },
                "post 401" to { it.status = 401 },
                "post 429" to { it.status = 429 },
                "attestation 403" to { it.attestStatus = 403 },
                "attestation 404" to { it.attestStatus = 404 },
            )
        for ((label, refuse) in refusals) {
            val transport = RecordingTransport()
            val sender = senderOnVirtualTime(transport)
            refuse(transport)
            sender.emitNumbered(1)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(0, sender.queuedCount, "$label: dropped")
            val requests = transport.attested.size + transport.batches.size

            transport.attestStatus = 200
            transport.status = 200
            advance(3_600_000L)
            assertEquals(requests, transport.attested.size + transport.batches.size, "$label: nothing is sent again")

            sender.emitNumbered(2)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf(2), transport.accepted.single().numbers(), label)
            assertTrue(transport.accepted.single().droppedReports().isEmpty(), "$label: a refusal is not a counted drop")
        }
    }

    /**
     * While nothing answers, flushes come 4 s, 15 s, 60 s and then every 5 min after the one before;
     * the first failure 30 min behind it, an event is given up. The next batch reports that once,
     * and keeps reporting it until a batch carrying the report is accepted.
     */
    @Test
    fun repeatedFailuresWaitLongerEachTimeThenGiveUpAfterThirtyMinutesAndReportTheCountOnce() = runTest {
        val transport = RecordingTransport(status = 0, clock = { currentTime })
        val logged = mutableListOf<String>()
        val sender = senderOnVirtualTime(transport, log = { logged += it })
        val startedAt = currentTime
        sender.emitNumbered(1)
        advance(7_200_000L)

        assertEquals(
            listOf(4_000L, 8_000L, 23_000L, 83_000L, 383_000L, 683_000L, 983_000L, 1_283_000L, 1_583_000L, 1_883_000L),
            transport.sendAtMillis.map { it - startedAt },
        )
        assertEquals(0, sender.queuedCount, "given up at the first failure 30 min after the first one")
        assertEquals(1, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut))
        assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.QueueCap))
        assertTrue(logged.any { "client_trace_dropped reason=aged_out count=1" in it }, "logged: $logged")

        // The batch that carries the report fails too: it is resent as it was, and the count is not lost.
        sender.emitNumbered(2)
        advance(RaftClientTraceFlushDelayMillis)
        val failed = transport.batches.last()
        assertEquals(listOf(2), failed.numbers())
        with(failed.droppedReports().single()) {
            assertEquals(1, attrs.getValue(RaftClientTraceDroppedCountAttr).jsonPrimitive.int)
            assertEquals("aged_out", attrs.getValue(RaftClientTraceDroppedReasonAttr).jsonPrimitive.content)
            assertEquals(1_883, attrs.getValue(RaftClientTraceDroppedOldestAgeAttr).jsonPrimitive.int)
            assertEquals("android", attrs.getValue("clientPlatform").jsonPrimitive.content)
            assertEquals(setOf("dropped_count", "reason", "oldest_dropped_age_s", "clientPlatform"), attrs.keys)
        }
        assertEquals(2, sender.queuedCount, "the report waits with its batch")

        transport.status = 200
        advance(RaftClientTraceRetryDelaysMillis.last())
        assertEquals(failed.events, transport.accepted.single().events, "resent unchanged: same report, same time")
        assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut))

        sender.emitNumbered(3)
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(listOf(3), transport.accepted.last().numbers())
        assertTrue(transport.accepted.last().droppedReports().isEmpty(), "reported once")
    }

    /** While sends fail, a full queue gives up its oldest events for newer ones and counts them. */
    @Test
    fun aFullQueueDropsItsOldestEventsWhileSendsFailAndReportsHowMany() = runTest {
        val transport = RecordingTransport(status = 0)
        val logged = mutableListOf<String>()
        val sender = senderOnVirtualTime(transport, log = { logged += it })
        sender.emitNumbered(0)
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(1, sender.queuedCount, "kept after the failed flush")

        for (n in 1..RaftClientTraceMaxQueued + 5) sender.emitNumbered(n)
        assertEquals(RaftClientTraceMaxQueued, sender.queuedCount, "never more than the cap")
        assertEquals(6, sender.droppedNotYetReported(RaftClientTraceDropReason.QueueCap))
        val dropLines = logged.filter { "client_trace_dropped" in it }
        assertTrue(dropLines.last().startsWith("client_trace_dropped reason=queue_cap count=1 notYetReported=6"), "logged: ${dropLines.last()}")
        assertTrue(logged.none { RaftStateTransitionTraceName in it || "\"n\"" in it }, "counts only, no event names or contents")

        transport.status = 200
        advance(RaftClientTraceRetryDelaysMillis.first())
        assertEquals((6..RaftClientTraceMaxQueued + 5).toList(), transport.accepted.flatMap { it.numbers() }, "the newest 500, in order, once")
        val report = transport.accepted.flatMap { it.droppedReports() }.single()
        assertEquals(transport.accepted.first().events.first(), report, "in the first accepted batch")
        assertEquals(6, report.attrs.getValue(RaftClientTraceDroppedCountAttr).jsonPrimitive.int)
        assertEquals("queue_cap", report.attrs.getValue(RaftClientTraceDroppedReasonAttr).jsonPrimitive.content)
        assertEquals(4, report.attrs.getValue(RaftClientTraceDroppedOldestAgeAttr).jsonPrimitive.int)
        assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.QueueCap), "reset once accepted")
        assertEquals(0, sender.queuedCount)
    }

    /** Privacy mode decides over kept events as over new ones: on drops them, unknown holds them unsent. */
    @Test
    fun privacyModeOnDropsKeptEventsAndUnknownHoldsThemWithoutSending() = runTest {
        for (answer in listOf(RaftPrivacyMode.On, RaftPrivacyMode.Unknown)) {
            val transport = RecordingTransport(status = 0)
            val privacy = MutableStateFlow(RaftPrivacyMode.Off)
            val sender = senderOnVirtualTime(transport, privacy = privacy)
            sender.emitNumbered(1)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(1, sender.queuedCount, "$answer: kept after the failed flush")

            privacy.value = answer
            runCurrent()
            transport.status = 200
            advance(3_600_000L)
            assertEquals(1, transport.batches.size, "$answer: nothing is sent while not off")
            when (answer) {
                RaftPrivacyMode.On -> {
                    assertEquals(0, sender.queuedCount, "on: kept events are dropped")
                    for (reason in RaftClientTraceDropReason.entries) {
                        assertEquals(0, sender.droppedNotYetReported(reason), "on: not a counted drop")
                    }
                }
                else -> {
                    assertEquals(1, sender.queuedCount, "unknown: still held")
                    privacy.value = RaftPrivacyMode.Off
                    runCurrent()
                    advance(RaftClientTraceFlushDelayMillis)
                    assertEquals(listOf(1), transport.accepted.single().numbers(), "unknown then off: sent")
                }
            }
        }
    }

    @Test
    fun onlyTheFailedBatchIsResentAfterAnEarlierOneWasAccepted() = runTest {
        val transport = RecordingTransport()
        val sender = senderOnVirtualTime(transport)
        sender.emitNumbered(1)
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(listOf(1), transport.accepted.single().numbers())

        transport.status = 0
        sender.emitNumbered(2)
        advance(RaftClientTraceFlushDelayMillis)
        transport.status = 200
        advance(3_600_000L)
        assertEquals(listOf(listOf(1), listOf(2)), transport.accepted.map { it.numbers() }, "each event is accepted once")
        assertEquals(listOf(listOf(1), listOf(2), listOf(2)), transport.batches.map { it.numbers() })
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
     * The network coming back brings a waiting retry forward, once per wait: a second signal in the
     * same wait neither delays it nor adds a send, and a signal in the next wait brings that one forward.
     */
    @Test
    fun theNetworkComingBackBringsAWaitingRetryForwardOncePerWait() = runTest {
        val transport = RecordingTransport(status = 0, clock = { currentTime })
        val networkRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val sender = senderOnVirtualTime(transport, networkRestored = networkRestored)
        sender.emitNumbered(1)
        advance(30_000L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L), transport.sendAtMillis, "the next retry is due at 83 s")

        networkRestored.tryEmit(Unit)
        advance(2_000L)
        networkRestored.tryEmit(Unit)
        advance(2_000L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L, 34_000L), transport.sendAtMillis, "4 s after the first signal, not after the second")
        advance(2_000L)
        assertEquals(4, transport.sendAtMillis.size, "the second signal added no send")

        // 34 s failed too (the 4th in a row, next retry due at 334 s); the next wait is brought forward again.
        advance(4_000L)
        networkRestored.tryEmit(Unit)
        transport.status = 200
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(listOf(4_000L, 8_000L, 23_000L, 34_000L, 44_000L), transport.sendAtMillis)
        assertEquals(listOf(1), transport.accepted.single().numbers())
        advance(3_600_000L)
        assertEquals(5, transport.sendAtMillis.size, "delivered once")
    }

    /** Only the scheduled retry sends while sends fail: new events, also at the cap, wait for it. */
    @Test
    fun queueingEventsDuringARetryWaitNeitherSendsNorShortensTheWait() = runTest {
        val transport = RecordingTransport(status = 0, clock = { currentTime })
        val sender = senderOnVirtualTime(transport)
        sender.emitNumbered(0)
        advance(30_000L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L), transport.sendAtMillis, "the next retry is due at 83 s")

        for (n in 1..RaftClientTraceMaxQueued + 100) {
            sender.emitNumbered(n)
            if (n % 100 == 0) advance(1_000L)
        }
        assertEquals(RaftClientTraceMaxQueued, sender.queuedCount)
        advance(83_000L - currentTime - 1L)
        assertEquals(3, transport.sendAtMillis.size, "nothing is sent before the retry is due")
        advance(1L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L, 83_000L), transport.sendAtMillis, "one request, at the scheduled time")
        advance(299_999L)
        assertEquals(4, transport.sendAtMillis.size, "and the wait after it is the next longer one")
        advance(1L)
        assertEquals(383_000L, transport.sendAtMillis.last())
    }

    /**
     * A batch whose request cannot be built (a record that does not encode) will never go through:
     * it is dropped at once, not retried, and the events queued behind it are sent by the same flush.
     */
    @Test
    fun aBatchThatFailsLocallyIsDroppedAndDoesNotBlockTheEventsBehindIt() = runTest {
        val transport = RecordingTransport(clock = { currentTime })
        transport.sendErrorFor = { batch -> IllegalStateException("cannot encode").takeIf { 1 in batch.numbers() } }
        val logged = mutableListOf<String>()
        val sender = senderOnVirtualTime(transport, log = { logged += it })
        for (n in 1..RaftClientTraceMaxBatch + 50) sender.emitNumbered(n)
        advance(RaftClientTraceFlushDelayMillis)

        assertEquals(listOf(4_000L, 4_000L), transport.sendAtMillis, "the bad batch, then the one behind it, in one flush")
        assertEquals((RaftClientTraceMaxBatch + 1..RaftClientTraceMaxBatch + 50).toList(), transport.accepted.single().numbers())
        assertEquals(0, sender.queuedCount, "the bad batch is not kept")
        assertTrue(logged.any { "reason=refused stage=local_error" in it && "count=$RaftClientTraceMaxBatch" in it }, "logged: $logged")
        advance(3_600_000L)
        assertEquals(2, transport.batches.size, "and never retried")
        for (reason in RaftClientTraceDropReason.entries) assertEquals(0, sender.droppedNotYetReported(reason))
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

    /**
     * user-1's batch got no answer and waits for its retry when a login replaces the session with
     * user-2's: the retry asks for nothing and sends nothing (no attestation under user-2's session
     * for user-1's records, user-1's cached one not used for user-2's first event), and user-2's
     * events go out under user-2's own attestation once the shell reports the account.
     */
    @Test
    fun aLoginReplacingTheSessionWhileARetryWaitsNeverSendsTheHeldRecordsUnderTheOtherAccount() = runTest {
        val failures: List<Pair<String, (RecordingTransport) -> Unit>> =
            listOf(
                "attestation without a response, nothing cached" to { it.attestStatus = 0 },
                "post without a response, user-1's attestation cached" to { it.status = 0 },
            )
        for ((label, breakIt) in failures) {
            val transport = RecordingTransport()
            val sender = senderOnVirtualTime(transport)
            breakIt(transport)
            sender.emitNumbered(1)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(1, sender.queuedCount, "$label: kept for the retry")
            val requests = transport.attested.size + transport.batches.size

            transport.account = "user-2"
            sender.emitNumbered(2, who = "user-2")
            transport.attestStatus = 200
            transport.status = 200
            advance(RaftClientTraceRetryDelaysMillis.first())
            assertEquals(requests, transport.attested.size + transport.batches.size, "$label: the retry asked for nothing and sent nothing")
            assertEquals(0, sender.queuedCount, "$label: dropped")

            sender.setAccount("user-2")
            sender.emitNumbered(3, who = "user-2")
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf(listOf(3)), transport.accepted.map { it.numbers() }, label)
            transport.assertEachPostCarriedOnlyItsAttestationsAccount(label)
            for (reason in RaftClientTraceDropReason.entries) assertEquals(0, sender.droppedNotYetReported(reason), "$label: not a counted drop")
        }
    }

    /** A count not yet reported belongs to the account and the privacy answer it was made under. */
    @Test
    fun aCountNotYetReportedIsForgottenOnSignOutAnotherAccountOrPrivacyOn() = runTest {
        // (what happens, then who sends the next batch)
        val changes: List<Triple<String, (RaftClientTraceEventSender, MutableStateFlow<RaftPrivacyMode>) -> Unit, String>> =
            listOf(
                Triple("sign-out", { sender, _ -> sender.setAccount(null) }, "user-2"),
                Triple("another account", { sender, _ -> sender.setAccount("user-2") }, "user-2"),
                Triple("privacy mode on", { _, privacy -> privacy.value = RaftPrivacyMode.On }, "user-1"),
            )
        for ((label, change, nextAccount) in changes) {
            val transport = RecordingTransport()
            val privacy = MutableStateFlow(RaftPrivacyMode.Off)
            val sender = senderWithOneAgedOutCount(transport, privacy)
            change(sender, privacy)
            runCurrent()
            assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut), "$label: forgotten")

            privacy.value = RaftPrivacyMode.Off
            runCurrent()
            transport.account = nextAccount
            sender.setAccount(nextAccount)
            sender.emitNumbered(1, who = nextAccount)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf(1), transport.accepted.single().numbers(), label)
            assertTrue(transport.accepted.single().droppedReports().isEmpty(), "$label: the count is not reported later")
        }
    }

    /** What happens while the batch carrying a count is out: during its attestation request or its POST. */
    private class InFlightCase(val label: String, val duringAttestation: Boolean, val postStatus: Int, val privacyOnAndOff: Boolean)

    /**
     * The batch carrying a count is out when the account changes or privacy mode goes on (even if it
     * is off again before the answer): whatever the answer, the count does not come back.
     */
    @Test
    fun aCountWhoseBatchIsOutDoesNotComeBackOnceTheAccountChangedOrPrivacyWentOn() = runTest {
        val cases =
            listOf(
                InFlightCase("another account during its attestation request", duringAttestation = true, postStatus = 200, privacyOnAndOff = false),
                InFlightCase("another account during its POST, then no answer", duringAttestation = false, postStatus = 0, privacyOnAndOff = false),
                InFlightCase("another account during its POST, then a 400", duringAttestation = false, postStatus = 400, privacyOnAndOff = false),
                InFlightCase("privacy on and off again during its POST, then a 400", duringAttestation = false, postStatus = 400, privacyOnAndOff = true),
            )
        for (case in cases) {
            val transport = RecordingTransport()
            val privacy = MutableStateFlow(RaftPrivacyMode.Off)
            val sender = senderWithOneAgedOutCount(transport, privacy)
            transport.status = case.postStatus
            val slow: suspend () -> Unit = { delay(1_000L) }
            if (case.duringAttestation) transport.onAttest = slow else transport.onSend = slow
            sender.emitNumbered(1)
            advance(RaftClientTraceFlushDelayMillis + 500L)
            assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut), "${case.label}: the count rides with the batch")

            if (case.privacyOnAndOff) {
                privacy.value = RaftPrivacyMode.On
                runCurrent()
                privacy.value = RaftPrivacyMode.Off
                runCurrent()
            } else {
                transport.account = "user-2"
                sender.setAccount("user-2")
            }
            advance(500L)
            assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut), "${case.label}: not put back")
            assertEquals(0, sender.queuedCount, "${case.label}: nothing kept")

            transport.onAttest = {}
            transport.onSend = {}
            transport.status = 200
            val nextAccount = if (case.privacyOnAndOff) "user-1" else "user-2"
            sender.emitNumbered(2, who = nextAccount)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf(2), transport.accepted.last().numbers(), case.label)
            assertTrue(transport.accepted.last().droppedReports().isEmpty(), "${case.label}: the count is not reported later")
        }
    }

    /** Refused (4xx) under the same account with privacy off: the count it carried goes to the next batch. */
    @Test
    fun aCountComesBackWhenItsBatchIsRefusedUnderTheSameAccountWithPrivacyOff() = runTest {
        val transport = RecordingTransport()
        val sender = senderWithOneAgedOutCount(transport)
        transport.status = 400
        sender.emitNumbered(1)
        advance(RaftClientTraceFlushDelayMillis)
        val refused = transport.batches.last()
        assertEquals(listOf(1), refused.numbers())
        assertEquals(1, refused.droppedReports().single().attrs.getValue(RaftClientTraceDroppedCountAttr).jsonPrimitive.int)
        assertEquals(1, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut), "put back")

        transport.status = 200
        sender.emitNumbered(2)
        advance(RaftClientTraceFlushDelayMillis)
        val delivered = transport.accepted.single()
        assertEquals(listOf(2), delivered.numbers())
        with(delivered.droppedReports().single()) {
            assertEquals(1, attrs.getValue(RaftClientTraceDroppedCountAttr).jsonPrimitive.int)
            assertEquals("aged_out", attrs.getValue(RaftClientTraceDroppedReasonAttr).jsonPrimitive.content)
        }
        assertEquals(0, sender.droppedNotYetReported(RaftClientTraceDropReason.AgedOut))
    }

    /** An exception while building a request is no answer: the wait before the next retry keeps growing. */
    @Test
    fun aLocalErrorDoesNotShortenTheWaitBeforeTheNextRetry() = runTest {
        val transport = RecordingTransport(status = 0, clock = { currentTime })
        val sender = senderOnVirtualTime(transport)
        sender.emitNumbered(1)
        advance(23_000L - 1L)
        assertEquals(listOf(4_000L, 8_000L), transport.sendAtMillis, "two failures; the next retry is due at 23 s")

        transport.sendErrorFor = { batch -> IllegalStateException("cannot encode").takeIf { 1 in batch.numbers() } }
        advance(1L)
        assertEquals(23_000L, transport.sendAtMillis.last())
        assertEquals(0, sender.queuedCount, "the retry failed locally: dropped")
        transport.sendErrorFor = { null }

        // The next failure is the third in a row: 60 s to the retry, not the 4 s of a first one.
        sender.emitNumbered(2)
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(27_000L, transport.sendAtMillis.last())
        advance(RaftClientTraceRetryDelaysMillis[2] - 1L)
        assertEquals(4, transport.sendAtMillis.size, "no retry before 60 s")
        advance(1L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L, 27_000L, 87_000L), transport.sendAtMillis)
    }

    /**
     * One server's attestation keeps answering 503: its batch waits for the retry while the same flush
     * sends the other server's records, each retry asks for its attestation once, and the wait still
     * grows although the other server's batches go through.
     */
    @Test
    fun aServerWhoseAttestationKeepsFailingDoesNotHoldUpAnotherServersRecords() = runTest {
        val transport = RecordingTransport(clock = { currentTime })
        transport.attestStatusByServer[ServerId("server-1")] = 503
        val sender = senderOnVirtualTime(transport)
        sender.emitNumbered(1, server = "server-1")
        sender.emitNumbered(2, server = "server-2")
        sender.emitNumbered(3, server = "server-1")
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(listOf(listOf(2)), transport.accepted.map { it.numbers() }, "server-2's record went in the same flush")
        assertEquals(listOf("server-1", "server-2"), transport.attested.map { it.value })
        assertEquals(2, sender.queuedCount, "server-1's records wait for the retry")

        sender.emitNumbered(4, server = "server-2")
        advance(RaftClientTraceRetryDelaysMillis[0])
        assertEquals(listOf(listOf(2), listOf(4)), transport.accepted.map { it.numbers() }, "the retry sends what server-2 queued meanwhile")
        assertEquals(listOf("server-1", "server-2", "server-1", "server-2"), transport.attested.map { it.value }, "server-1 once per flush")

        transport.attestStatusByServer.clear()
        advance(RaftClientTraceRetryDelaysMillis[1] - 1L)
        assertEquals(2, transport.accepted.size, "the second failed flush in a row waits 15 s")
        advance(1L)
        assertEquals(listOf(1, 3), transport.accepted.last().numbers(), "server-1's records, once, in order")
        assertEquals(0, sender.queuedCount)
        advance(3_600_000L)
        assertEquals(3, transport.accepted.size, "nothing sent twice")
    }
}
