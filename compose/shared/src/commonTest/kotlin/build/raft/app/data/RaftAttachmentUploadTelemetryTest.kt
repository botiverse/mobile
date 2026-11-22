package build.raft.app.data

import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.network.RaftTransportFailureKind
import build.raft.app.network.RaftUploadWireFacts
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
            // ("bucket" alone is in the attribute names size_bucket / elapsed_bucket.)
            listOf("bucket.r2", "r2.test", "Signature", "abc123", "IMG_0042", "holiday", "Kyoto", "private", "https").forEach { fragment ->
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

    private class Emitted(val serverId: String, val attrs: JsonObject, val traceId: String, val spanId: String)

    private fun attempt(
        serverId: String? = "server-1",
        attempt: Int = 1,
        logs: MutableList<String> = mutableListOf(),
        emitted: MutableList<Emitted>,
        elapsedMs: () -> Long = { 500L },
    ) = RaftAttachmentUploadAttempt(
        serverId = serverId,
        attempt = attempt,
        pickedSizeBytes = 1_024L,
        elapsedMs = elapsedMs,
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

    /** `cancel_reason`: the canceller's reason, on every `cancelled` event and on no other. */
    @Test
    fun aCancelSaysWhyWithEachReason() {
        RaftAttachmentUploadCancelReason.entries.forEach { reason ->
            val emitted = mutableListOf<Emitted>()
            val logs = mutableListOf<String>()
            val upload = attempt(logs = logs, emitted = emitted)

            upload.reportCancellation(true, RaftUploadCancelled(reason, site = "test_site"))

            val attrs = emitted.single().attrs
            assertEquals("cancelled", attrs.text("outcome"), "$reason")
            assertEquals(reason.wire, attrs.text("cancel_reason"), "$reason")
            assertNull(attrs["error_code"], "$reason")
            if (reason == RaftAttachmentUploadCancelReason.Other) {
                assertEquals(2, logs.size, "$reason: logs=$logs")
                assertEquals(
                    "thread_composer_attachment_upload_cancel_other site=test_site traceId=${upload.recorder.traceIds.traceId}",
                    logs[1],
                )
            } else {
                assertEquals(1, logs.size, "$reason: only the event's own line, logs=$logs")
            }
            assertTrue(logs.first().contains("cancel_reason=${reason.wire}"), logs.first())
        }
        assertEquals(
            listOf("user_removed", "draft_sent", "draft_cleared", "session_ended", "other"),
            RaftAttachmentUploadCancelReason.entries.map { it.wire },
        )
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

    // control_ms (botiverse/slock #16006): the first attempt of each control call, summed.

    private val controlAuthority = RaftExpectedAuthBinding("account", "access", "refresh", "server-1", "env")
    private val directCapabilities =
        AttachmentUploadCapabilities(maxBytes = 10_000L, directUploadEnabled = true, directUploadThresholdBytes = 10L)

    /** The attempt's clock: each fake call moves it on by what it "took". */
    private class ControlClock(var now: Long = 0L) {
        fun spend(ms: Long) {
            now += ms
        }
    }

    private class ClockedGateway(
        private val clock: ControlClock,
        private val recorder: RaftAttachmentUploadTimingRecorder,
    ) : DirectAttachmentUploadSessionGateway {
        var createMs = 200L
        var createResult: DataResult<AttachmentUploadSessionCreateResult>? = null
        /** Inside one create call: the request, a 401, the token refresh and the resend. */
        var createSteps: List<Long>? = null
        var putMs = 1_000L
        var putResult: DataResult<Unit> = DataResult.Content(Unit)
        var statusMs = 70L
        /** Each complete call's time and answer, in order. */
        val completes = ArrayDeque<Pair<Long, DataResult<AttachmentUploadSessionCompleteResult>>>()
        var completeCalls = 0

        override suspend fun capabilities(): DataResult<AttachmentUploadCapabilities> = error("probed through the cache")

        override suspend fun create(
            request: AttachmentUploadSessionCreateRequest,
        ): DataResult<AttachmentUploadSessionCreateResult> {
            // As the network client does when the call is handed over.
            recorder.uploadRequestStarting(RaftAttachmentUploadRouteDirect)
            createSteps?.forEach(clock::spend) ?: clock.spend(createMs)
            return createResult ?: DataResult.Content(
                AttachmentUploadSessionCreateResult(
                    uploadId = UPLOAD_ID,
                    attachmentId = AttachmentId(ATTACHMENT_ID),
                    state = "pending",
                    expiresAt = "2026-12-01T00:00:00Z",
                    upload = AttachmentUploadSessionPut(
                        method = "PUT",
                        url = "https://r2.example/object",
                        headers = mapOf("Content-Type" to "application/octet-stream", "If-None-Match" to "*"),
                    ),
                ),
            )
        }

        override suspend fun put(
            upload: AttachmentUploadSessionPut,
            file: PlatformFile,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
        ): DataResult<Unit> {
            clock.spend(putMs)
            return putResult
        }

        override suspend fun complete(uploadId: String): DataResult<AttachmentUploadSessionCompleteResult> {
            completeCalls += 1
            val (ms, answer) = completes.removeFirstOrNull() ?: (50L to completed())
            clock.spend(ms)
            return answer
        }

        override suspend fun cancel(uploadId: String): DataResult<AttachmentUploadSessionView> =
            DataResult.Content(AttachmentUploadSessionView(uploadId = uploadId, state = "canceled", expiresAt = ""))

        override suspend fun status(uploadId: String): DataResult<AttachmentUploadSessionView> {
            clock.spend(statusMs)
            return DataResult.Content(AttachmentUploadSessionView(uploadId = uploadId, state = "pending", expiresAt = ""))
        }

        companion object {
            const val UPLOAD_ID = "11111111-1111-4111-8111-111111111111"
            const val ATTACHMENT_ID = "22222222-2222-4222-8222-222222222222"

            fun completed() =
                DataResult.Content(
                    AttachmentUploadSessionCompleteResult(
                        uploadId = UPLOAD_ID,
                        state = "completed",
                        attachment = Attachment(
                            id = AttachmentId(ATTACHMENT_ID),
                            name = "a.bin",
                            mimeType = "application/octet-stream",
                            sizeLabel = "",
                            sizeBytes = 100,
                        ),
                    ),
                )
        }
    }

    private fun pickedFile() =
        PlatformFile(id = "local-1", fileName = "a.bin", mimeType = "application/octet-stream", sizeBytes = 100L, bytes = ByteArray(100) { 1 })

    @Test
    fun onTheDirectRouteControlMsIsTheProbeTheCreateAndTheFirstCompleteNotItsRetries() = runTest {
        val clock = ControlClock()
        val emitted = mutableListOf<Emitted>()
        val upload = attempt(emitted = emitted, elapsedMs = { clock.now })
        val gateway = ClockedGateway(clock, upload.recorder)
        // The first complete is told verification is still running; the retry answers.
        gateway.completes.add(
            300L to DataResult.Error(ScreenError(code = "http_409", retryable = true, serverCode = "UPLOAD_VERIFICATION_IN_PROGRESS")),
        )
        gateway.completes.add(450L to ClockedGateway.completed())
        val cache = AttachmentUploadCapabilitiesCache(nowMillis = { 0L })

        val result =
            withContext(upload.recorder) {
                cache.getOrFetch(controlAuthority) {
                    clock.spend(100L)
                    DataResult.Content(directCapabilities)
                }
                gateway.uploadOneThreadFile(ChannelId("channel-1"), pickedFile(), clientRequestId = "request-1")
            }
        upload.settle(result)
        upload.report()

        assertIs<DataResult.Content<*>>(result)
        assertEquals(2, gateway.completeCalls)
        val attrs = emitted.single().attrs
        assertEquals("direct", attrs.text("route"))
        assertEquals("ok", attrs.text("outcome"))
        // probe 100 + create 200 + first complete 300; the retry's 450 and the PUT's 1000 are not in it.
        assertEquals(600L, attrs.getValue("control_ms").jsonPrimitive.long)
        assertEquals(0L, attrs.getValue("prepare_ms").jsonPrimitive.long)
        assertEquals(2_050L, attrs.getValue("duration_ms").jsonPrimitive.long)
    }
}
