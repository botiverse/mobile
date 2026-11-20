package build.raft.app.data

import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.RaftTransportFailureKind
import build.raft.app.network.RaftUploadWireFacts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `slock.attachment.upload_finished` from the app, with the web's names (botiverse/slock
 * packages/web/src/utils/attachmentUploadOutcomeTrace.ts at 080597aea, slock #15924).
 */
class RaftAttachmentUploadTelemetryTest {
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

    @Test
    fun thePhasesArePresentAndNeverExceedTheTotal() {
        val cases =
            listOf(
                // total, prepare, connect, body, wait -> reported prepare, connect, body, wait
                listOf(5_000L, 400L, 150L, 3_000L, 900L) to RaftAttachmentUploadPhases(400L, 150L, 3_000L, 900L),
                // The engine's clock and the app's are read separately: a phase is capped by what is left.
                listOf(1_000L, 400L, 100L, 400L, 250L) to RaftAttachmentUploadPhases(400L, 100L, 400L, 100L),
                listOf(1_000L, 400L, 700L, 500L, 250L) to RaftAttachmentUploadPhases(400L, 600L, 0L, 0L),
                listOf(1_000L, 1_200L, 100L, 500L, 250L) to RaftAttachmentUploadPhases(1_000L, 0L, 0L, 0L),
                listOf(0L, 0L, 0L, 0L, 0L) to RaftAttachmentUploadPhases(0L, 0L, 0L, 0L),
            )

        cases.forEach { (input, expected) ->
            val (total, prepare, connect, body, wait) = input
            val phases =
                raftAttachmentUploadPhases(
                    total,
                    prepare,
                    RaftUploadWireFacts(connectMs = connect, requestBodyMs = body, responseWaitMs = wait),
                )
            assertEquals(expected, phases, "input=$input")
            val sum = phases.prepareMs!! + phases.connectMs!! + phases.requestBodyMs!! + phases.responseWaitMs!!
            assertTrue(sum <= total, "sum=$sum total=$total")
        }
    }

    @Test
    fun aPhaseThatWasNotMeasuredIsAbsent() {
        // Ended before any request: nothing but the total.
        assertEquals(RaftAttachmentUploadPhases(), raftAttachmentUploadPhases(800L, prepareMs = null, facts = null))
        // The platform gave no transfer times (or the request got no answer).
        assertEquals(
            RaftAttachmentUploadPhases(prepareMs = 120L),
            raftAttachmentUploadPhases(800L, prepareMs = 120L, facts = RaftUploadWireFacts(requestBytes = 9L)),
        )
        // A reused connection: no connect time, the other phases stand.
        assertEquals(
            RaftAttachmentUploadPhases(prepareMs = 120L, requestBodyMs = 500L, responseWaitMs = 30L),
            raftAttachmentUploadPhases(800L, prepareMs = 120L, facts = RaftUploadWireFacts(requestBodyMs = 500L, responseWaitMs = 30L)),
        )
        // A new connection that never got to send: the connect time alone.
        assertEquals(
            RaftAttachmentUploadPhases(prepareMs = 120L, connectMs = 90L),
            raftAttachmentUploadPhases(800L, prepareMs = 120L, facts = RaftUploadWireFacts(connectMs = 90L)),
        )
        // No body time, so no last byte to measure the wait from; negatives are not measurements.
        assertEquals(
            RaftAttachmentUploadPhases(),
            raftAttachmentUploadPhases(
                800L,
                prepareMs = -5L,
                facts = RaftUploadWireFacts(connectMs = -1L, requestBodyMs = null, responseWaitMs = 60L),
            ),
        )
    }

    @Test
    fun attributesUseTheWebsNames() {
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(
                    route = RaftAttachmentUploadRouteLegacy,
                    attempt = 2,
                    // The picked size; the transport's file length decides the bucket.
                    sizeBytes = 64L * 1024L * 1024L,
                    durationMs = 5_200L,
                    prepareMs = 350L,
                    facts =
                        RaftUploadWireFacts(
                            requestBytes = 4_194_304L,
                            connectMs = 180L,
                            requestBodyMs = 3_900L,
                            responseWaitMs = 610L,
                            edgePop = "NRT57-P2",
                        ),
                    bodySends = 1,
                    bytesSent = 4_194_304L,
                    progressSpanMs = 3_800L,
                )
            )

        assertEquals(
            setOf(
                "route", "outcome", "attempt", "size_bucket", "elapsed_bucket", "duration_ms",
                "prepare_ms", "connect_ms", "request_body_ms", "response_wait_ms", "request_bytes", "edge_pop",
            ),
            attrs.keys,
        )
        assertEquals("legacy", attrs.text("route"))
        assertEquals("ok", attrs.text("outcome"))
        assertEquals(2, attrs.getValue("attempt").jsonPrimitive.int)
        assertEquals("<10MB", attrs.text("size_bucket"))
        assertEquals("1-10s", attrs.text("elapsed_bucket"))
        assertEquals(5_200L, attrs.getValue("duration_ms").jsonPrimitive.long)
        assertEquals(350L, attrs.getValue("prepare_ms").jsonPrimitive.long)
        assertEquals(180L, attrs.getValue("connect_ms").jsonPrimitive.long)
        assertEquals(3_900L, attrs.getValue("request_body_ms").jsonPrimitive.long)
        assertEquals(610L, attrs.getValue("response_wait_ms").jsonPrimitive.long)
        // A number only, and a JSON number.
        assertEquals(4_194_304L, attrs.getValue("request_bytes").jsonPrimitive.long)
        assertFalse(attrs.getValue("request_bytes").jsonPrimitive.isString)
        assertEquals("NRT57-P2", attrs.text("edge_pop"))
    }

    @Test
    fun unmeasuredValuesAreLeftOutOfTheEvent() {
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(route = null, errorCode = "attachment_size_unavailable", durationMs = 40L)
            )

        assertEquals(setOf("route", "outcome", "error_code", "attempt", "elapsed_bucket", "duration_ms"), attrs.keys)
        assertEquals("unknown", attrs.text("route"))
        assertEquals("error", attrs.text("outcome"))
        assertEquals("attachment_size_unavailable", attrs.text("error_code"))
        assertEquals("<1s", attrs.text("elapsed_bucket"))
    }

    /** A hang is a timeout or a cancel mid-send: those events say how far the body got. */
    @Test
    fun anUnfinishedBodyReportsHowLongItWasGoingOutAndHowFarItGot() {
        val base =
            RaftAttachmentUploadFinished(
                route = RaftAttachmentUploadRouteDirect,
                sizeBytes = 20L * 1024L * 1024L,
                durationMs = 70_000L,
                prepareMs = 200L,
                bytesSent = 6_000_000L,
                progressSpanMs = 58_000L,
            )

        // Android / desktop, the transport returned: the engine's own time of the unfinished body.
        val timedOut =
            raftAttachmentUploadFinishedAttrs(
                base.copy(
                    errorCode = "network_unavailable",
                    facts =
                        RaftUploadWireFacts(
                            requestBytes = 20L * 1024L * 1024L,
                            connectMs = 300L,
                            requestBodyPartialMs = 60_000L,
                            failureKind = RaftTransportFailureKind.TIMEOUT,
                            transportError = "okhttp:SocketTimeoutException",
                        ),
                )
            )
        assertEquals("timeout", timedOut.text("outcome"))
        assertEquals("transfer", timedOut.text("timeout_phase"))
        assertEquals("UPLOAD_STALLED", timedOut.text("error_code"))
        assertEquals(300L, timedOut.getValue("connect_ms").jsonPrimitive.long)
        assertEquals(60_000L, timedOut.getValue("request_body_partial_ms").jsonPrimitive.long)
        assertEquals(6_000_000L, timedOut.getValue("request_bytes_sent").jsonPrimitive.long)
        assertNull(timedOut["request_body_ms"])
        assertNull(timedOut["response_wait_ms"])

        // A cancel (and iOS): the transport returned nothing, so the progress reports' span stands in.
        val cancelled = raftAttachmentUploadFinishedAttrs(base.copy(cancelled = true))
        assertEquals("cancelled", cancelled.text("outcome"))
        assertNull(cancelled["error_code"])
        assertEquals(58_000L, cancelled.getValue("request_body_partial_ms").jsonPrimitive.long)
        assertEquals(6_000_000L, cancelled.getValue("request_bytes_sent").jsonPrimitive.long)

        // Progress counts multipart framing: never more than the file. A finished body has neither field.
        val overCounted =
            raftAttachmentUploadFinishedAttrs(base.copy(errorCode = "server_unavailable", sizeBytes = 5_000_000L))
        assertEquals(5_000_000L, overCounted.getValue("request_bytes_sent").jsonPrimitive.long)
        val finished =
            raftAttachmentUploadFinishedAttrs(
                base.copy(errorCode = "server_unavailable", facts = RaftUploadWireFacts(requestBodyMs = 900L, responseWaitMs = 50L))
            )
        assertNull(finished["request_body_partial_ms"])
        assertNull(finished["request_bytes_sent"])
    }

    @Test
    fun outcomeFollowsTheTransportNotErrorText() {
        val cases =
            listOf(
                // app code, cancelled, timeout phase -> outcome
                listOf(null, false, null) to "ok",
                // A PUT that timed out and was then recovered is a success.
                listOf(null, false, "transfer") to "ok",
                listOf(null, true, null) to "cancelled",
                listOf("cancelled", false, null) to "cancelled",
                listOf("network_timeout", false, null) to "timeout",
                listOf("attachment_presigned_upload_timeout", false, null) to "timeout",
                // The direct route: the stalled PUT's code says nothing of a timeout; the transport does.
                listOf("UPLOAD_OBJECT_PUT_UNCERTAIN", false, "transfer") to "timeout",
                listOf("attachment_upload_session_complete_failed", false, "complete") to "timeout",
                listOf("UPLOAD_TOO_LARGE", false, null) to "error",
                // "timeout" inside some other code or text decides nothing.
                listOf("failed: connect timeout to https://bucket.test/k", false, null) to "error",
            )
        cases.forEach { (input, expected) ->
            val (code, cancelled, phase) = input
            assertEquals(expected, raftAttachmentUploadOutcome(code as String?, cancelled as Boolean, phase as String?), "input=$input")
        }

        val timeout = RaftUploadWireFacts(failureKind = RaftTransportFailureKind.TIMEOUT)
        assertEquals("transfer", raftAttachmentUploadTimeoutPhase(timeout, controlTimeoutPhase = null))
        assertEquals("response", raftAttachmentUploadTimeoutPhase(timeout.copy(requestBodyMs = 800L), controlTimeoutPhase = "status"))
        assertEquals("complete", raftAttachmentUploadTimeoutPhase(RaftUploadWireFacts(requestBodyMs = 800L), controlTimeoutPhase = "complete"))
        assertNull(raftAttachmentUploadTimeoutPhase(RaftUploadWireFacts(failureKind = RaftTransportFailureKind.CONNECT), null))
    }

    /** The mapping table of `error_code`: every value is one of a bounded set. */
    @Test
    fun errorCodeIsOneOfABoundedSet() {
        fun code(outcome: String, app: String?, server: String? = null, transport: String? = null) =
            raftAttachmentUploadErrorCode(outcome, app, server, transport)

        // ok and cancelled carry none.
        assertNull(code("ok", null))
        assertNull(code("cancelled", "cancelled"))
        // 1. A timeout, as the web: UPLOAD_STALLED, or the uncertain PUT.
        assertEquals("UPLOAD_STALLED", code("timeout", "network_timeout", transport = "okhttp:SocketTimeoutException"))
        assertEquals("UPLOAD_STALLED", code("timeout", "attachment_upload_session_complete_failed", server = "UPLOAD_OBJECT_NOT_FOUND"))
        assertEquals("UPLOAD_OBJECT_PUT_UNCERTAIN", code("timeout", "UPLOAD_OBJECT_PUT_UNCERTAIN", server = "UPLOAD_OBJECT_PUT_UNCERTAIN"))
        // 2. The server's contract code.
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", code("error", "http_402", server = "FILE_UPLOAD_QUOTA_EXCEEDED"))
        assertEquals("UPLOAD_SESSION_EXPIRED", code("error", "http_410", server = " upload_session_expired "))
        assertEquals("UPLOAD_FORBIDDEN", code("error", "server_forbidden", server = "UPLOAD_FORBIDDEN"))
        // 3. The app's code when it is a contract code or names the web's condition.
        assertEquals("UPLOAD_TOO_LARGE", code("error", "UPLOAD_TOO_LARGE"))
        assertEquals("UPLOAD_TOO_LARGE", code("error", "attachment_too_large"))
        assertEquals("UPLOAD_INVALID_REQUEST", code("error", "attachment_upload_file_empty"))
        // 4. No HTTP answer: the platform's error name, else the app's own class of it.
        assertEquals("okhttp:UnknownHostException", code("error", "network_dns_failed", transport = "okhttp:UnknownHostException"))
        assertEquals("NSURLErrorDomain:-1005", code("error", "network_unavailable", transport = "NSURLErrorDomain:-1005"))
        assertEquals("ktor:HttpRequestTimeoutException", code("error", "network_unavailable", transport = "ktor:HttpRequestTimeoutException"))
        assertEquals("network_connect_failed", code("error", "network_connect_failed", transport = "other"))
        assertEquals("network_unavailable", code("error", "network_unavailable"))
        // 5. An HTTP status. 6. The app's own listed codes.
        assertEquals("http_409", code("error", "http_409", server = "SOMETHING_NEW_FROM_THE_SERVER"))
        assertEquals("server_unavailable", code("error", "server_unavailable"))
        assertEquals("attachment_size_unavailable", code("error", "attachment_size_unavailable"))
        // The platform's name only replaces a "no answer" code, never another one.
        assertEquals("server_unavailable", code("error", "server_unavailable", transport = "okhttp:IOException"))
        // 7. Everything else.
        assertEquals("other", code("error", "some_new_app_code"))
        assertEquals("other", code("error", "http_9999"))
        assertEquals("other", code("error", null))
        assertEquals("other", code("error", "attachment_upload_failed_because_x", server = "A_CODE_NOBODY_LISTED"))
    }

    /** Raw error text can hold a URL or a file name and is unbounded: none of it reaches the event. */
    @Test
    fun aMessageWithAUrlOrAFileNameNeverReachesTheEvent() {
        val url = "https://bucket.r2.test/obj/key?X-Amz-Signature=abc123"
        val fileName = "/private/var/mobile/IMG_0042 holiday in Kyoto.jpg"
        val leaky =
            listOf(
                // as the app code, as the server code, as the transport's name, and all three at once
                RaftAttachmentUploadFinished(route = "legacy", errorCode = "failed to read $fileName", durationMs = 10L),
                RaftAttachmentUploadFinished(route = "direct", errorCode = "PUT $url failed", durationMs = 10L),
                RaftAttachmentUploadFinished(route = "direct", errorCode = "http_500", serverCode = "see $url", durationMs = 10L),
                RaftAttachmentUploadFinished(
                    route = "legacy",
                    errorCode = "network_unavailable",
                    durationMs = 10L,
                    facts = RaftUploadWireFacts(transportError = "okhttp:$url"),
                ),
                RaftAttachmentUploadFinished(
                    route = "legacy",
                    errorCode = "network_unavailable",
                    durationMs = 10L,
                    facts = RaftUploadWireFacts(transportError = "unexpected end of stream on $url while sending $fileName"),
                ),
                RaftAttachmentUploadFinished(
                    route = "legacy",
                    errorCode = fileName,
                    serverCode = url,
                    durationMs = 10L,
                    facts = RaftUploadWireFacts(transportError = fileName, failureKind = RaftTransportFailureKind.TIMEOUT),
                ),
            )
        val allowed =
            RaftAttachmentUploadContractCodes + RaftAttachmentUploadAppCodes + setOf("other", "http_500")

        leaky.forEach { finished ->
            val attrs = raftAttachmentUploadFinishedAttrs(finished)
            val line = raftAttachmentUploadFinishedLogLine(attrs, "0af7651916cd43dd8448eb211c80319c")
            val everything = attrs.toString() + line
            listOf("bucket", "Signature", "abc123", "IMG_0042", "holiday", "Kyoto", "private", "https").forEach { fragment ->
                assertFalse(fragment in everything, "$fragment leaked: $everything")
            }
            assertTrue(attrs.text("error_code") in allowed, "error_code=${attrs.text("error_code")}")
        }
        assertEquals(
            listOf("other", "other", "http_500", "network_unavailable", "network_unavailable", "UPLOAD_STALLED"),
            leaky.map { raftAttachmentUploadFinishedAttrs(it).text("error_code") },
        )
    }

    @Test
    fun bucketsMatchTheWebs() {
        val mb = 1024L * 1024L
        assertEquals(
            listOf("<10MB", "10-50MB", "10-50MB", "50-200MB", ">200MB"),
            listOf(10L * mb - 1L, 10L * mb, 50L * mb - 1L, 50L * mb, 200L * mb).map(::raftAttachmentUploadSizeBucket),
        )
        assertEquals(
            listOf("<1s", "1-10s", "10-60s", "1-5m", ">5m"),
            listOf(999L, 1_000L, 10_000L, 60_000L, 300_000L).map(::raftAttachmentUploadElapsedBucket),
        )
    }

    @Test
    fun recorderKeepsTheFirstPrepareEndTheLastSendAndCountsTheSends() {
        var elapsed = 0L
        val recorder =
            RaftAttachmentUploadTimingRecorder(
                elapsedMs = { elapsed },
                traceIds = RaftTraceIds(traceId = "0af7651916cd43dd8448eb211c80319c", spanId = "b7ad6b7169203331"),
            )
        with(recorder.snapshot()) {
            assertNull(route)
            assertNull(prepareMs)
            assertNull(facts)
            assertEquals(0, bodySends)
            assertNull(bytesSent)
            assertNull(progressSpanMs)
        }

        elapsed = 210L
        recorder.uploadRequestStarting(RaftAttachmentUploadRouteLegacy)
        recorder.transferStarting(RaftAttachmentUploadRouteLegacy)
        elapsed = 300L
        recorder.transferProgress(1_000L)
        elapsed = 700L
        recorder.transferProgress(9_000L)
        with(recorder.snapshot()) {
            assertEquals(9_000L, bytesSent)
            assertEquals(400L, progressSpanMs)
        }
        // The transport itself re-sent after a connection reset: two sends in one call.
        recorder.transferFinished(RaftAttachmentUploadRouteLegacy, RaftUploadWireFacts(requestBodyMs = 10L, sends = 2))
        // The body goes again after a token refresh: preparation ended once, the last send is reported.
        elapsed = 900L
        recorder.uploadRequestStarting(RaftAttachmentUploadRouteLegacy)
        recorder.transferStarting(RaftAttachmentUploadRouteLegacy)
        assertNull(recorder.snapshot().bytesSent, "an earlier send's progress no longer counts")
        elapsed = 950L
        recorder.transferProgress(4_000L)
        val last = RaftUploadWireFacts(requestBytes = 7L, requestBodyMs = 30L, responseWaitMs = 5L)
        recorder.transferFinished(RaftAttachmentUploadRouteLegacy, last)

        with(recorder.snapshot()) {
            assertEquals("legacy", route)
            assertEquals(210L, prepareMs)
            assertEquals(last, facts)
            assertEquals(3, bodySends)
            assertEquals(4_000L, bytesSent)
            assertEquals(0L, progressSpanMs)
        }
        assertEquals(
            mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
            recorder.traceHeaders(),
        )
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(route = "legacy", durationMs = 1_000L, facts = last, bodySends = 3)
            )
        assertEquals(3, attrs.getValue("body_sends").jsonPrimitive.int)
    }

    @Test
    fun recorderRemembersTheControlCallThatTimedOutLast() {
        val recorder = RaftAttachmentUploadTimingRecorder(elapsedMs = { 0L })

        recorder.controlCallFinished(RaftAttachmentUploadPhaseCreate, timedOut = true)
        assertEquals("create", recorder.snapshot().controlTimeoutPhase)
        // The create call was replayed and answered: no timeout stands.
        recorder.controlCallFinished(RaftAttachmentUploadPhaseCreate, timedOut = false)
        assertNull(recorder.snapshot().controlTimeoutPhase)
        recorder.controlCallFinished(RaftAttachmentUploadPhaseStatus, timedOut = true)
        recorder.controlCallFinished(RaftAttachmentUploadPhaseComplete, timedOut = true)
        assertEquals("complete", recorder.snapshot().controlTimeoutPhase)
    }

    private class Emitted(val serverId: String, val attrs: JsonObject, val traceId: String, val spanId: String)

    private fun attempt(
        serverId: String? = "server-1",
        attempt: Int = 1,
        logs: MutableList<String> = mutableListOf(),
        emitted: MutableList<Emitted>,
    ) = RaftAttachmentUploadAttempt(
        serverId = serverId,
        attempt = attempt,
        pickedSizeBytes = 1_024L,
        elapsedMs = { 500L },
        log = { logs += it },
        emit = { server, attrs, traceId, spanId -> emitted += Emitted(server, attrs, traceId, spanId) },
    )

    /** The composer's wiring (ThreadPage `uploadComposerAttachmentFiles`): one event per attempt. */
    @Test
    fun anAttemptEmitsOneEventUnderItsOwnTraceAndTheServerItStartedOn() {
        val emitted = mutableListOf<Emitted>()
        val logs = mutableListOf<String>()
        val first = attempt(attempt = 1, logs = logs, emitted = emitted)

        first.recorder.uploadRequestStarting(RaftAttachmentUploadRouteLegacy)
        first.settle(DataResult.Error(ScreenError(code = "server_unavailable", retryable = true)))
        first.report()
        // The later catch blocks of the upload task report again: nothing more is emitted.
        first.report(cancelled = true)
        first.report(failureCode = "attachment_upload_failed")

        // The user taps Retry: a new attempt, a new event, a new trace.
        val retry = attempt(attempt = 2, logs = logs, emitted = emitted)
        retry.settle(DataResult.Content(Unit))
        retry.report()

        assertEquals(2, emitted.size)
        assertEquals(2, logs.size)
        val (failed, succeeded) = emitted
        assertEquals("server-1", failed.serverId)
        assertEquals("error", failed.attrs.text("outcome"))
        assertEquals("server_unavailable", failed.attrs.text("error_code"))
        assertEquals("legacy", failed.attrs.text("route"))
        assertEquals(1, failed.attrs.getValue("attempt").jsonPrimitive.int)
        assertEquals(500L, failed.attrs.getValue("duration_ms").jsonPrimitive.long)
        assertEquals("ok", succeeded.attrs.text("outcome"))
        assertEquals(2, succeeded.attrs.getValue("attempt").jsonPrimitive.int)
        assertEquals(first.recorder.traceIds.traceId, failed.traceId)
        assertEquals(first.recorder.traceIds.spanId, failed.spanId)
        assertEquals(retry.recorder.traceIds.traceId, succeeded.traceId)
        assertTrue(failed.traceId != succeeded.traceId, "each attempt has its own trace id")
        assertTrue(logs.first().endsWith("traceId=${failed.traceId}"), logs.first())
    }

    @Test
    fun theOutcomeIsTheUploadsResultNotTheScopesState() {
        val emitted = mutableListOf<Emitted>()

        // The upload finished, then its scope was cancelled as it returned: still a success.
        attempt(emitted = emitted).apply {
            settle(DataResult.Content(Unit))
            report(cancelled = true)
        }
        // Removed mid-upload: nothing settled, so it is a cancel.
        attempt(emitted = emitted).report(cancelled = true)
        // The task threw before a result: the fallback code.
        attempt(emitted = emitted).report(failureCode = "attachment_upload_failed")
        // A refusal keeps the server's contract code; the first result stands.
        attempt(emitted = emitted).apply {
            settle(DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", serverCode = "UPLOAD_FORBIDDEN")))
            settle(DataResult.Content(Unit))
            report()
        }
        attempt(emitted = emitted).apply {
            settle(DataResult.Empty(EmptyReason.NoMessages))
            report()
        }

        assertEquals(listOf("ok", "cancelled", "error", "error", "error"), emitted.map { it.attrs.text("outcome") })
        assertEquals(
            listOf(null, null, "attachment_upload_failed", "UPLOAD_FORBIDDEN", "empty_upload_result"),
            emitted.map { it.attrs.text("error_code") },
        )
    }

    @Test
    fun withoutAServerTheAttemptIsOnlyLogged() {
        for (serverId in listOf(null, "", "  ")) {
            val emitted = mutableListOf<Emitted>()
            val logs = mutableListOf<String>()

            attempt(serverId = serverId, logs = logs, emitted = emitted).apply {
                settle(DataResult.Content(Unit))
                report()
            }

            assertTrue(emitted.isEmpty(), "serverId=$serverId")
            assertEquals(1, logs.size, "serverId=$serverId")
        }
    }

    @Test
    fun attemptNumbersCountEachPickedFileOnItsOwn() = runTest {
        val ids = ThreadComposerDirectUploadClientRequestIds()
        val photo = ThreadAttachmentUploadAttemptIdentity(localAttachmentId = "picked-1", draftKey = "draft-a")
        val video = ThreadAttachmentUploadAttemptIdentity(localAttachmentId = "picked-2", draftKey = "draft-a")

        assertEquals(listOf(1, 2, 3), listOf(ids.nextAttempt(photo), ids.nextAttempt(photo), ids.nextAttempt(photo)))
        assertEquals(1, ids.nextAttempt(video))
        // The retries of one file keep its idempotency key.
        assertEquals(ids.idFor(photo), ids.idFor(photo))
    }

    @Test
    fun theDiagnosticsLineCarriesTheSameValuesUnquoted() {
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(
                    route = RaftAttachmentUploadRouteDirect,
                    durationMs = 12_000L,
                    prepareMs = 100L,
                    facts = RaftUploadWireFacts(requestBytes = 31_457_280L, requestBodyMs = 9_000L, responseWaitMs = 200L),
                )
            )

        val tokens = raftAttachmentUploadFinishedLogLine(attrs, "0af7651916cd43dd8448eb211c80319c").split(' ')

        assertEquals("thread_composer_attachment_upload_finished", tokens.first())
        val expected = attrs.map { (name, value) -> "$name=${value.jsonPrimitive.content}" } + "traceId=0af7651916cd43dd8448eb211c80319c"
        assertEquals(expected, tokens.drop(1))
    }
}
