package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
     * response). [sendErrorFor]: the POST of that batch throws instead. [batches] is every POST tried, [accepted] those
     * answered 2xx, [sendAtMillis] when each was tried on [clock].
     */
    private class RecordingTransport(
        var status: Int = 200,
        var attestStatus: Int = 200,
        var sendErrorFor: (RaftWebTraceBatchRequest) -> Throwable? = { null },
        private val clock: () -> Long = { 0L },
    ) : RaftClientTraceTransport {
        val attested = mutableListOf<ServerId>()
        val batches = mutableListOf<RaftWebTraceBatchRequest>()
        val accepted = mutableListOf<RaftWebTraceBatchRequest>()
        val sendAtMillis = mutableListOf<Long>()

        override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer {
            attested += serverId
            if (attestStatus !in 200..299) return RaftScopeAttestationAnswer(attestStatus)
            return RaftScopeAttestationAnswer(
                status = attestStatus,
                response = RaftScopeAttestationResponse(attestation = "att-${attested.size}", expiresAt = "2026-11-11T10:10:00Z"),
            )
        }

        override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
            batches += batch
            sendAtMillis += clock()
            sendErrorFor(batch)?.let { throw it }
            if (status in 200..299) accepted += batch
            return status
        }
    }

    /** 2026-11-11T10:00:00Z; [senderOnVirtualTime]'s clock is this plus the test's virtual time. */
    private val baseEpochMillis = 1_794_391_200_000L

    private fun TestScope.senderOnVirtualTime(
        transport: RecordingTransport,
        privacy: MutableStateFlow<RaftPrivacyMode> = MutableStateFlow(RaftPrivacyMode.Off),
        networkRestored: Flow<Any> = emptyFlow(),
        log: (String) -> Unit = {},
    ): RaftClientTraceEventSender =
        RaftClientTraceEventSender(
            scope = backgroundScope,
            nowEpochMillis = { baseEpochMillis + currentTime },
            defaultAttrs = { mapOf("clientPlatform" to "android") },
            privacyMode = privacy,
            networkRestored = networkRestored,
            log = log,
        ).also { it.bindTransport(transport) }

    /** A numbered event; [numbers] reads them back from a batch (the dropped-count event has none). */
    private fun RaftClientTraceEventSender.emitNumbered(n: Int, traceId: String? = null, spanId: String? = null) {
        emit("server-1", RaftStateTransitionTraceName, buildJsonObject { put("n", n) }, traceId = traceId, spanId = spanId)
    }

    private fun RaftWebTraceBatchRequest.numbers(): List<Int> = events.mapNotNull { it.attrs["n"]?.jsonPrimitive?.int }

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

    /** What is held belongs to the account that was signed in: sign-out or another account drops it. */
    @Test
    fun signOutOrAnotherAccountDropsKeptEventsAndTheCachedAttestation() = runTest {
        for (next in listOf<String?>(null, "user-2")) {
            val transport = RecordingTransport()
            val sender = senderOnVirtualTime(transport)
            sender.emitNumbered(1)
            sender.setAccount("user-1")
            sender.setAccount("user-1")
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf("att-1"), transport.accepted.map { it.attestation }, "the first account keeps what was queued")

            transport.status = 0
            sender.emitNumbered(2)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(1, sender.queuedCount)
            sender.setAccount(next)
            assertEquals(0, sender.queuedCount, "$next: dropped")

            transport.status = 200
            sender.setAccount("user-2")
            // Short of the attestation's expiry, so a reused one would show below.
            advance(60_000L)
            assertEquals(1, transport.accepted.size, "$next: the kept event is never sent")
            sender.emitNumbered(3)
            advance(RaftClientTraceFlushDelayMillis)
            assertEquals(listOf(3), transport.accepted.last().numbers())
            assertEquals("att-2", transport.accepted.last().attestation, "$next: the other account's attestation is not reused")
            assertTrue(transport.accepted.last().droppedReports().isEmpty(), "$next: not a counted drop")
        }
    }

    @Test
    fun theNetworkComingBackBringsAWaitingRetryForwardOnce() = runTest {
        val transport = RecordingTransport(status = 0, clock = { currentTime })
        val networkRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val sender = senderOnVirtualTime(transport, networkRestored = networkRestored)
        sender.emitNumbered(1)
        advance(30_000L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L), transport.sendAtMillis, "the next retry is due at 83 s")

        transport.status = 200
        networkRestored.tryEmit(Unit)
        networkRestored.tryEmit(Unit)
        advance(3_600_000L)
        assertEquals(listOf(4_000L, 8_000L, 23_000L, 34_000L), transport.sendAtMillis)
        assertEquals(listOf(1), transport.accepted.single().numbers())
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
}
