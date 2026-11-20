package build.raft.app.data

import build.raft.app.network.RaftTraceIds
import build.raft.app.network.RaftUploadWireFacts
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `slock.attachment.upload_finished` from the app, with the web's names (botiverse/slock
 * packages/web/src/utils/attachmentUploadOutcomeTrace.ts at 080597aea, slock #15924).
 */
class RaftAttachmentUploadTelemetryTest {
    @Test
    fun threePhasesArePresentAndNeverExceedTheTotal() {
        val cases =
            listOf(
                // total, prepare, body, wait -> reported prepare, body, wait
                listOf(5_000L, 400L, 3_000L, 900L) to RaftAttachmentUploadPhases(400L, 3_000L, 900L),
                // The engine's clock and the app's are read separately: a phase is capped by what is left.
                listOf(1_000L, 400L, 500L, 250L) to RaftAttachmentUploadPhases(400L, 500L, 100L),
                listOf(1_000L, 1_200L, 500L, 250L) to RaftAttachmentUploadPhases(1_000L, 0L, 0L),
                listOf(0L, 0L, 0L, 0L) to RaftAttachmentUploadPhases(0L, 0L, 0L),
            )

        cases.forEach { (input, expected) ->
            val (total, prepare, body, wait) = input
            val phases = raftAttachmentUploadPhases(total, prepare, RaftUploadWireFacts(requestBodyMs = body, responseWaitMs = wait))
            assertEquals(expected, phases, "input=$input")
            val sum = phases.prepareMs!! + phases.requestBodyMs!! + phases.responseWaitMs!!
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
        assertEquals(
            RaftAttachmentUploadPhases(prepareMs = 120L, requestBodyMs = 500L),
            raftAttachmentUploadPhases(800L, prepareMs = 120L, facts = RaftUploadWireFacts(requestBodyMs = 500L)),
        )
        // No body time, so no last byte to measure the wait from; negatives are not measurements.
        assertEquals(
            RaftAttachmentUploadPhases(),
            raftAttachmentUploadPhases(800L, prepareMs = -5L, facts = RaftUploadWireFacts(requestBodyMs = null, responseWaitMs = 60L)),
        )
    }

    @Test
    fun attributesUseTheWebsNames() {
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(
                    route = RaftAttachmentUploadRouteLegacy,
                    sizeBytes = 4L * 1024L * 1024L,
                    durationMs = 5_200L,
                    prepareMs = 350L,
                    facts =
                        RaftUploadWireFacts(
                            requestBytes = 4_194_723L,
                            requestBodyMs = 3_900L,
                            responseWaitMs = 610L,
                            edgePop = "NRT57-P2",
                        ),
                )
            )

        assertEquals(
            setOf(
                "route", "outcome", "size_bucket", "elapsed_bucket", "duration_ms",
                "prepare_ms", "request_body_ms", "response_wait_ms", "request_bytes", "edge_pop",
            ),
            attrs.keys,
        )
        assertEquals("legacy", attrs.getValue("route").jsonPrimitive.content)
        assertEquals("ok", attrs.getValue("outcome").jsonPrimitive.content)
        assertEquals("<10MB", attrs.getValue("size_bucket").jsonPrimitive.content)
        assertEquals("1-10s", attrs.getValue("elapsed_bucket").jsonPrimitive.content)
        assertEquals(5_200L, attrs.getValue("duration_ms").jsonPrimitive.long)
        assertEquals(350L, attrs.getValue("prepare_ms").jsonPrimitive.long)
        assertEquals(3_900L, attrs.getValue("request_body_ms").jsonPrimitive.long)
        assertEquals(610L, attrs.getValue("response_wait_ms").jsonPrimitive.long)
        // A number only, and a JSON number.
        assertEquals(4_194_723L, attrs.getValue("request_bytes").jsonPrimitive.long)
        assertTrue(!attrs.getValue("request_bytes").jsonPrimitive.isString)
        assertEquals("NRT57-P2", attrs.getValue("edge_pop").jsonPrimitive.content)
    }

    @Test
    fun unmeasuredValuesAreLeftOutOfTheEvent() {
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(route = null, errorCode = "attachment_size_unavailable", durationMs = 40L)
            )

        assertEquals(setOf("route", "outcome", "error_code", "elapsed_bucket", "duration_ms"), attrs.keys)
        assertEquals("unknown", attrs.getValue("route").jsonPrimitive.content)
        assertEquals("error", attrs.getValue("outcome").jsonPrimitive.content)
        assertEquals("attachment_size_unavailable", attrs.getValue("error_code").jsonPrimitive.content)
        assertEquals("<1s", attrs.getValue("elapsed_bucket").jsonPrimitive.content)
    }

    @Test
    fun outcomeIsTheWebsClosedSetAndTheErrorCodeIsAnIdentifierOnly() {
        val outcomes =
            listOf(
                Triple(null, false, "ok"),
                Triple(null, true, "cancelled"),
                Triple("cancelled", false, "cancelled"),
                Triple("attachment_presigned_upload_timeout", false, "timeout"),
                Triple("network_timeout", false, "timeout"),
                Triple("UPLOAD_TOO_LARGE", false, "error"),
            )
        outcomes.forEach { (code, cancelled, expected) ->
            assertEquals(expected, raftAttachmentUploadOutcome(code, cancelled), "code=$code cancelled=$cancelled")
        }

        fun errorCodeOf(code: String?, cancelled: Boolean = false) =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(route = RaftAttachmentUploadRouteDirect, errorCode = code, cancelled = cancelled, durationMs = 1L)
            )["error_code"]?.jsonPrimitive?.content

        assertEquals("UPLOAD_TOO_LARGE", errorCodeOf("UPLOAD_TOO_LARGE"))
        assertEquals("other", errorCodeOf("failed to read /private/var/mobile/photo 1.jpg"))
        assertNull(errorCodeOf(null))
        assertNull(errorCodeOf("cancelled"))
        assertNull(errorCodeOf(null, cancelled = true))
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
    fun recorderKeepsTheFirstPrepareEndAndTheLastSend() {
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
        }

        elapsed = 210L
        recorder.uploadRequestStarting(RaftAttachmentUploadRouteLegacy)
        recorder.transferFinished(RaftAttachmentUploadRouteLegacy, RaftUploadWireFacts(requestBodyMs = 10L))
        // The body goes again after a token refresh: preparation ended once, the last send is reported.
        elapsed = 900L
        recorder.uploadRequestStarting(RaftAttachmentUploadRouteLegacy)
        val second = RaftUploadWireFacts(requestBytes = 7L, requestBodyMs = 30L, responseWaitMs = 5L)
        recorder.transferFinished(RaftAttachmentUploadRouteLegacy, second)

        with(recorder.snapshot()) {
            assertEquals("legacy", route)
            assertEquals(210L, prepareMs)
            assertEquals(second, facts)
        }
        assertEquals(
            mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"),
            recorder.traceHeaders(),
        )
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
