package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.coroutines.CompletableDeferred
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
 * `web-trace-batch:create` attestation, then `/api/web-traces` events batched after 15 s (or
 * immediately when a server batch reaches 100) while foregrounded, with `clientPlatform` beside
 * each event's own attributes.
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
        foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
        networkRestored: Flow<Any> = emptyFlow(),
        log: (String) -> Unit = {},
        signedIn: String? = "user-1",
    ): RaftClientTraceEventSender =
        RaftClientTraceEventSender(
            scope = backgroundScope,
            nowEpochMillis = { baseEpochMillis + currentTime },
            defaultAttrs = { mapOf("clientPlatform" to "android") },
            privacyMode = privacy,
            foreground = foreground,
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
                foreground = MutableStateFlow(true),
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
                foreground = MutableStateFlow(true),
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
                    foreground = MutableStateFlow(true),
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

    @Test
    fun fifteenSecondWindowCoalescesMultipleEventsIntoOnePost() = runTest {
        assertEquals(15_000L, RaftClientTraceFlushDelayMillis)
        val transport = RecordingTransport(clock = { currentTime })
        val sender = senderOnVirtualTime(transport)

        sender.emitNumbered(1)
        advance(4_000L)
        assertTrue(transport.batches.isEmpty(), "old 4s window must not flush yet")
        sender.emitNumbered(2)
        advance(10_000L)
        sender.emitNumbered(3)
        advance(999L)
        assertTrue(transport.batches.isEmpty(), "still waiting until the full 15s window elapses")

        advance(1L)
        assertEquals(1, transport.batches.size, "all three events coalesce into 1 POST")
        assertEquals(listOf(1, 2, 3), transport.batches.single().numbers())
        assertEquals(listOf(15_000L), transport.sendAtMillis)
        assertEquals(0, sender.queuedCount)
    }

    @Test
    fun reachingMaxBatchForOneServerFlushesImmediatelyWithoutBypassingRetryBackoff() = runTest {
        // Multi-server queues below 100 each stay batched until a single (serverId, account) group reaches 100.
        val transport = RecordingTransport(clock = { currentTime })
        val sender = senderOnVirtualTime(transport)

        repeat(60) { i -> sender.emitNumbered(i + 1, server = "server-1") }
        repeat(60) { i -> sender.emitNumbered(i + 101, server = "server-2") }
        runCurrent()
        assertTrue(transport.batches.isEmpty(), "60 + 60 across two servers must not flush early")
        assertEquals(120, sender.queuedCount)

        advance(2_000L)
        repeat(40) { i -> sender.emitNumbered(i + 61, server = "server-1") }
        runCurrent()

        assertEquals(2, transport.batches.size, "reaching 100 for server-1 triggers immediate flush at t=2s")
        assertEquals((1..100).toList(), transport.batches[0].numbers())
        assertEquals((101..160).toList(), transport.batches[1].numbers())
        assertEquals(listOf(2_000L, 2_000L), transport.sendAtMillis)
        assertEquals(0, sender.queuedCount)

        // While a retry backoff is waiting, queueing 100 more events must NOT bypass the retry delay.
        val retryTransport = RecordingTransport(status = 0, clock = { currentTime })
        val retrySender = senderOnVirtualTime(retryTransport)
        retrySender.emitNumbered(1)
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(1, retryTransport.batches.size, "initial attempt failed")
        assertEquals(1, retrySender.queuedCount)

        repeat(RaftClientTraceMaxBatch) { i -> retrySender.emitNumbered(i + 2) }
        runCurrent()
        advance(RaftClientTraceRetryDelaysMillis.first() - 1L)
        assertEquals(1, retryTransport.batches.size, "retry backoff stays intact even at >=100 queued")

        retryTransport.status = 200
        advance(1L)
        assertEquals((1..101).toList(), retryTransport.accepted.flatMap { it.numbers() })
        assertEquals(listOf(100, 1), retryTransport.accepted.map { it.events.size })
        assertEquals(0, retrySender.queuedCount)
    }

    @Test
    fun enteringBackgroundCancelsFlushAndRetryTimersAndSuppressesBackgroundWakeups() = runTest {
        val foreground = MutableStateFlow(true)
        val privacy = MutableStateFlow(RaftPrivacyMode.Off)
        val networkRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val transport = RecordingTransport(clock = { currentTime })
        val sender =
            senderOnVirtualTime(
                transport = transport,
                privacy = privacy,
                foreground = foreground,
                networkRestored = networkRestored,
            )

        // 1. Scheduled 15s flush timer is cancelled on background transition.
        sender.emitNumbered(1)
        advance(5_000L)
        foreground.value = false
        runCurrent()
        advance(60_000L)
        assertTrue(transport.batches.isEmpty() && transport.attested.isEmpty(), "background cancels pending flush timer")
        assertEquals(1, sender.queuedCount)

        // 2. Events, spans, networkRestored, and privacyMode transitions while backgrounded never wake network.
        sender.emitNumbered(2)
        sender.emitSpan(
            serverId = "server-1",
            span =
                RaftWebSpanRecord(
                    traceId = "0af7651916cd43dd8448eb211c80319c",
                    spanId = "b7ad6b7169203331",
                    name = "mobile.realtime.session_health",
                    startTime = raftEpochMillisUtcIso(baseEpochMillis + currentTime),
                    endTime = raftEpochMillisUtcIso(baseEpochMillis + currentTime + 100L),
                    attrs = buildJsonObject { put("window_ms", 100) },
                ),
            events = emptyList(),
        )
        privacy.value = RaftPrivacyMode.Unknown
        runCurrent()
        privacy.value = RaftPrivacyMode.Off
        networkRestored.emit(Unit)
        advance(600_000L)
        assertTrue(transport.batches.isEmpty() && transport.attested.isEmpty(), "nothing fires while backgrounded")
        assertEquals(3, sender.queuedCount)

        // 3. Return to foreground delivers queued records after the normal 15s batch window.
        foreground.value = true
        advance(RaftClientTraceFlushDelayMillis - 1L)
        assertTrue(transport.batches.isEmpty(), "foreground return waits out the 15s batch window")
        advance(1L)
        val firstBatch = transport.accepted.single()
        assertEquals(listOf(1, 2), firstBatch.numbers())
        assertEquals(1, firstBatch.records.size)
        assertEquals(0, sender.queuedCount)

        // 4. Retry timer is cancelled on background transition and networkRestored in background does not wake network.
        transport.status = 0
        sender.emitNumbered(3)
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(2, transport.batches.size, "failed flush schedules 4s retry")
        assertEquals(1, sender.queuedCount)

        foreground.value = false
        runCurrent()
        networkRestored.emit(Unit)
        advance(600_000L)
        assertEquals(2, transport.batches.size, "cancelled retry timer and background networkRestored stay silent")

        // 5. Returning to foreground respects the pending retry delay and delivers cleanly.
        transport.status = 200
        foreground.value = true
        runCurrent()
        advance(RaftClientTraceRetryDelaysMillis.first() - 1L)
        assertEquals(2, transport.batches.size, "foreground return respects existing retry delay")
        advance(1L)
        assertEquals(listOf(3), transport.accepted.last().numbers())
        assertEquals(0, sender.queuedCount)
    }

    @Test
    fun returningToForegroundFlushesFullBackgroundBatchImmediatelyAndPreservesInFlightBatches() = runTest {
        // Part A: 100 records queued while backgrounded flush immediately on foreground return.
        val foreground = MutableStateFlow(false)
        val transport = RecordingTransport(clock = { currentTime })
        val sender = senderOnVirtualTime(transport, foreground = foreground)
        runCurrent()

        repeat(RaftClientTraceMaxBatch) { i -> sender.emitNumbered(i + 1) }
        advance(60_000L)
        assertTrue(transport.batches.isEmpty(), "full batch held while backgrounded")
        assertEquals(RaftClientTraceMaxBatch, sender.queuedCount)

        foreground.value = true
        runCurrent()
        assertEquals(1, transport.accepted.size, "full batch flushes immediately on foreground return")
        assertEquals((1..RaftClientTraceMaxBatch).toList(), transport.accepted.single().numbers())
        assertEquals(0, sender.queuedCount)

        // Part B: In-flight request during background transition:
        // (i) status 0 retirement puts batch back cleanly with no background retry; foreground resumes it once.
        val fgResend = MutableStateFlow(true)
        val resendGate = CompletableDeferred<Unit>()
        val resendTransport = RecordingTransport(status = 0, clock = { currentTime })
        var resendGated = false
        resendTransport.onSend = {
            if (!resendGated) {
                resendGated = true
                resendGate.await()
            }
        }
        val resendSender = senderOnVirtualTime(resendTransport, foreground = fgResend)
        repeat(105) { i -> resendSender.emitNumbered(i + 1) }
        runCurrent()
        assertEquals(1, resendTransport.batches.size, "first 100-event batch is in flight")
        assertEquals(5, resendSender.queuedCount)

        fgResend.value = false
        runCurrent()
        resendGate.complete(Unit)
        runCurrent()
        assertEquals(105, resendSender.queuedCount, "retired in-flight batch returns to queue without loss")
        advance(600_000L)
        assertEquals(1, resendTransport.batches.size, "no retry or second batch runs while backgrounded")

        resendTransport.status = 200
        fgResend.value = true
        advance(RaftClientTraceRetryDelaysMillis.first())
        assertEquals((1..105).toList(), resendTransport.accepted.flatMap { it.numbers() })
        assertEquals(0, resendSender.queuedCount)

        // (ii) status 200 completion after background transition does not double-send and pauses before batch 2.
        val fgSuccess = MutableStateFlow(true)
        val successGate = CompletableDeferred<Unit>()
        val successTransport = RecordingTransport(status = 200, clock = { currentTime })
        var successGated = false
        successTransport.onSend = {
            if (!successGated) {
                successGated = true
                successGate.await()
            }
        }
        val successSender = senderOnVirtualTime(successTransport, foreground = fgSuccess)
        repeat(105) { i -> successSender.emitNumbered(i + 1) }
        runCurrent()
        assertEquals(1, successTransport.batches.size)

        fgSuccess.value = false
        runCurrent()
        successGate.complete(Unit)
        runCurrent()
        advance(600_000L)
        assertEquals(1, successTransport.accepted.size, "batch 1 settled cleanly and batch 2 paused in background")
        assertEquals(5, successSender.queuedCount)

        fgSuccess.value = true
        advance(RaftClientTraceFlushDelayMillis)
        assertEquals(2, successTransport.accepted.size)
        assertEquals((1..105).toList(), successTransport.accepted.flatMap { it.numbers() })
        assertEquals(0, successSender.queuedCount)
    }
}
