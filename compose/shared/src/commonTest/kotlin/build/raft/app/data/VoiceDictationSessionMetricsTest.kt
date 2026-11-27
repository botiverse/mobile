package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationSessionMetricsTest {
    @Test
    fun snapshotMetricsTrackMultiUtteranceGapsTimingsAndIgnoreRefinedFrames() {
        val metrics = VoiceDictationSessionMetrics(mode = VoiceDictationSessionMode.Tap, startedAtMs = 10_000L)
        metrics.micReadyAtMs = 10_120L
        metrics.streamUrlSource = VoiceStreamUrlSource.Direct
        metrics.preReadyChunksQueued = 3
        metrics.connectedAtMs = 10_600L
        metrics.preReadyChunksSent = 3

        // Utterance 1: two partials separated by 800 ms, then finalized at +1200 ms.
        metrics.recordSnapshot(
            text = "Please move",
            stableLen = 0,
            refined = false,
            timestampMs = 11_100L,
            isActiveSession = true,
        )
        metrics.recordSnapshot(
            text = "Please move the release to",
            stableLen = 0,
            refined = false,
            timestampMs = 11_900L,
            isActiveSession = true,
        )
        metrics.recordSnapshot(
            text = "Please move the release to.",
            stableLen = 27,
            refined = false,
            timestampMs = 13_100L,
            isActiveSession = true,
        )
        // Duplicate identical finalized snapshot does not double-count finals or utterances.
        metrics.recordSnapshot(
            text = "Please move the release to.",
            stableLen = 27,
            refined = false,
            timestampMs = 13_200L,
            isActiveSession = true,
        )
        // Refined snapshot must be ignored completely (never increments finals or affects gaps).
        metrics.recordSnapshot(
            text = "Please move the release to:",
            stableLen = 27,
            refined = true,
            timestampMs = 13_500L,
            isActiveSession = true,
        )

        // Utterance 2: starts 2600 ms after utterance 1 final, 400 ms midsentence gap (max stays 800 ms).
        metrics.recordSnapshot(
            text = "Please move the release to. next Thursday",
            stableLen = 27,
            refined = false,
            timestampMs = 15_700L,
            isActiveSession = true,
        )
        metrics.recordSnapshot(
            text = "Please move the release to. next Thursday morning",
            stableLen = 27,
            refined = false,
            timestampMs = 16_100L,
            isActiveSession = true,
        )
        metrics.recordSnapshot(
            text = "Please move the release to. Next Thursday morning.",
            stableLen = 50,
            refined = false,
            timestampMs = 16_700L,
            isActiveSession = true,
        )

        // User stops 3.3 s after last final.
        metrics.freezeStop(VoiceDictationStopReason.Button, stopAtMs = 20_000L)
        // Idempotent freezeStop never overwrites initial stop timestamps/reason.
        metrics.freezeStop(VoiceDictationStopReason.Reset, stopAtMs = 25_000L)

        // Post-stop snapshot (isActiveSession = false) increments final/utterance counts without shifting stop delays.
        metrics.recordSnapshot(
            text = "Please move the release to. Next Thursday morning. Thanks.",
            stableLen = 58,
            refined = false,
            timestampMs = 20_500L,
            isActiveSession = false,
        )
        metrics.recordSnapshot(
            text = "Please move the release to next Thursday morning. Thanks!",
            stableLen = 57,
            refined = true,
            timestampMs = 20_800L,
            isActiveSession = false,
        )

        val summary =
            assertNotNull(
                metrics.buildSummary(
                    stoppedBy = VoiceDictationStopReason.Button,
                    finishingMs = 950L,
                    errorCode = null,
                    nowMs = 20_950L,
                ),
            )
        assertEquals(VoiceDictationSessionMode.Tap, summary.mode)
        assertEquals(VoiceDictationStopReason.Button, summary.stoppedBy)
        assertEquals(10_000L, summary.durationMs)
        assertEquals(3, summary.utterances)
        assertEquals(3, summary.finalsCount)
        assertEquals(3_300L, summary.stopAfterFinalMs)
        assertEquals(3_300L, summary.stopAfterLastPartialMs)
        assertEquals(800L, summary.maxMidSentenceGapMs)
        assertEquals(2_600L, summary.maxGapBetweenFinalsMs)
        assertEquals(3, summary.preReadyChunksQueued)
        assertEquals(3, summary.preReadyChunksSent)
        assertEquals(120L, summary.micStartMs)
        assertEquals(600L, summary.connectMs)
        assertEquals(1_100L, summary.firstPartialMs)
        assertEquals(950L, summary.finishingMs)
        assertEquals(VoiceStreamUrlSource.Direct, summary.streamUrlSource)
        assertNull(summary.errorCode)

        // Single-emission guarantee: subsequent buildSummary calls return null.
        assertNull(
            metrics.buildSummary(
                stoppedBy = VoiceDictationStopReason.Reset,
                finishingMs = null,
                errorCode = null,
                nowMs = 21_000L,
            ),
        )
    }

    @Test
    fun snapshotAdvancingStableLenWithNewPartialCommitsPrefixAndOpensNextUtterance() {
        val metrics = VoiceDictationSessionMetrics(mode = VoiceDictationSessionMode.Hold, startedAtMs = 1_000L)
        metrics.streamUrlSource = VoiceStreamUrlSource.RaftEdge

        // First partial opens utterance 1.
        metrics.recordSnapshot("Hello", stableLen = 0, refined = false, timestampMs = 1_400L, isActiveSession = true)
        // Combined frame commits "Hello. " (stableLen = 7) and starts live partial "world".
        metrics.recordSnapshot("Hello. world", stableLen = 7, refined = false, timestampMs = 2_000L, isActiveSession = true)
        // Second partial in utterance 2 measures 350 ms midsentence gap.
        metrics.recordSnapshot("Hello. world today", stableLen = 7, refined = false, timestampMs = 2_350L, isActiveSession = true)
        // Stop 500 ms after last partial, 850 ms after last final.
        metrics.freezeStop(VoiceDictationStopReason.Button, stopAtMs = 2_850L)

        val summary =
            assertNotNull(
                metrics.buildSummary(
                    stoppedBy = VoiceDictationStopReason.Button,
                    finishingMs = 200L,
                    errorCode = null,
                    nowMs = 3_050L,
                ),
            )
        assertEquals(VoiceDictationSessionMode.Hold, summary.mode)
        assertEquals(1, summary.utterances)
        assertEquals(1, summary.finalsCount)
        assertEquals(850L, summary.stopAfterFinalMs)
        assertEquals(500L, summary.stopAfterLastPartialMs)
        assertEquals(350L, summary.maxMidSentenceGapMs)
        assertEquals(0L, summary.maxGapBetweenFinalsMs)
        assertEquals(400L, summary.firstPartialMs)
        assertEquals(VoiceStreamUrlSource.RaftEdge, summary.streamUrlSource)
    }

    @Test
    fun directBackToBackFinalsMeasureBetweenFinalsGapWithoutMidSentenceGap() {
        val metrics = VoiceDictationSessionMetrics(mode = VoiceDictationSessionMode.Tap, startedAtMs = 5_000L)
        metrics.recordSnapshot("One.", stableLen = 4, refined = false, timestampMs = 5_500L, isActiveSession = true)
        metrics.recordSnapshot("One. Two.", stableLen = 9, refined = false, timestampMs = 7_900L, isActiveSession = true)
        metrics.freezeStop(VoiceDictationStopReason.Blur, stopAtMs = 8_200L)

        val summary =
            assertNotNull(
                metrics.buildSummary(
                    stoppedBy = VoiceDictationStopReason.Blur,
                    finishingMs = 150L,
                    errorCode = null,
                    nowMs = 8_350L,
                ),
            )
        assertEquals(2, summary.utterances)
        assertEquals(2, summary.finalsCount)
        assertEquals(500L, summary.firstPartialMs)
        assertNull(summary.maxMidSentenceGapMs)
        assertEquals(2_400L, summary.maxGapBetweenFinalsMs)
        assertEquals(300L, summary.stopAfterFinalMs)
        assertEquals(300L, summary.stopAfterLastPartialMs)
    }

    @Test
    fun traceAttrsSerializeExactClosedSetKeysOmitNullsClampNegativesAndGateErrorCode() {
        val fullSummary =
            VoiceDictationSessionSummary(
                mode = VoiceDictationSessionMode.Tap,
                stoppedBy = VoiceDictationStopReason.Button,
                durationMs = 6_420L,
                utterances = 2,
                finalsCount = 2,
                stopAfterFinalMs = 1_851L,
                stopAfterLastPartialMs = 1_851L,
                maxMidSentenceGapMs = 620L,
                maxGapBetweenFinalsMs = 2_451L,
                preReadyChunksQueued = 8,
                preReadyChunksSent = 8,
                micStartMs = 112L,
                connectMs = 646L,
                firstPartialMs = 890L,
                finishingMs = 1_180L,
                streamUrlSource = VoiceStreamUrlSource.Direct,
                // Must be ignored when stoppedBy != Error.
                errorCode = VoiceDictationSessionErrorCode.ConnectionLost,
            )
        val attrs = voiceDictationSessionTraceAttrs(fullSummary)
        assertEquals(
            setOf(
                "mode",
                "stopped_by",
                "duration_ms",
                "utterances",
                "finals_count",
                "stop_after_final_ms",
                "stop_after_last_partial_ms",
                "max_midsentence_gap_ms",
                "max_gap_between_finals_ms",
                "pre_ready_chunks_queued",
                "pre_ready_chunks_sent",
                "mic_start_ms",
                "connect_ms",
                "first_partial_ms",
                "finishing_ms",
                "stream_url_source",
            ),
            attrs.keys,
        )
        assertEquals("tap", attrs["mode"]?.jsonPrimitive?.content)
        assertEquals("button", attrs["stopped_by"]?.jsonPrimitive?.content)
        assertEquals(6_420L, attrs["duration_ms"]?.jsonPrimitive?.long)
        assertEquals(2, attrs["utterances"]?.jsonPrimitive?.int)
        assertEquals(2, attrs["finals_count"]?.jsonPrimitive?.int)
        assertEquals(1_851L, attrs["stop_after_final_ms"]?.jsonPrimitive?.long)
        assertEquals(1_851L, attrs["stop_after_last_partial_ms"]?.jsonPrimitive?.long)
        assertEquals(620L, attrs["max_midsentence_gap_ms"]?.jsonPrimitive?.long)
        assertEquals(2_451L, attrs["max_gap_between_finals_ms"]?.jsonPrimitive?.long)
        assertEquals(8, attrs["pre_ready_chunks_queued"]?.jsonPrimitive?.int)
        assertEquals(8, attrs["pre_ready_chunks_sent"]?.jsonPrimitive?.int)
        assertEquals(112L, attrs["mic_start_ms"]?.jsonPrimitive?.long)
        assertEquals(646L, attrs["connect_ms"]?.jsonPrimitive?.long)
        assertEquals(890L, attrs["first_partial_ms"]?.jsonPrimitive?.long)
        assertEquals(1_180L, attrs["finishing_ms"]?.jsonPrimitive?.long)
        assertEquals("direct", attrs["stream_url_source"]?.jsonPrimitive?.content)
        assertFalse("error_code" in attrs)
        assertFalse("client_scheme" in attrs)

        // Error stop attaches error_code (defaulting to "failed" when null), clamps negatives, omits nulls.
        val errorSummary =
            VoiceDictationSessionSummary(
                mode = VoiceDictationSessionMode.Hold,
                stoppedBy = VoiceDictationStopReason.Error,
                durationMs = -50L,
                utterances = -1,
                finalsCount = -2,
                stopAfterFinalMs = null,
                stopAfterLastPartialMs = null,
                maxMidSentenceGapMs = null,
                maxGapBetweenFinalsMs = null,
                preReadyChunksQueued = -3,
                preReadyChunksSent = -4,
                micStartMs = null,
                connectMs = null,
                firstPartialMs = null,
                finishingMs = null,
                streamUrlSource = null,
                errorCode = null,
            )
        val errorAttrs = voiceDictationSessionTraceAttrs(errorSummary, clientScheme = "app")
        assertEquals(
            setOf(
                "mode",
                "stopped_by",
                "duration_ms",
                "utterances",
                "finals_count",
                "pre_ready_chunks_queued",
                "pre_ready_chunks_sent",
                "error_code",
                "client_scheme",
            ),
            errorAttrs.keys,
        )
        assertEquals("hold", errorAttrs["mode"]?.jsonPrimitive?.content)
        assertEquals("error", errorAttrs["stopped_by"]?.jsonPrimitive?.content)
        assertEquals(0L, errorAttrs["duration_ms"]?.jsonPrimitive?.long)
        assertEquals(0, errorAttrs["utterances"]?.jsonPrimitive?.int)
        assertEquals(0, errorAttrs["finals_count"]?.jsonPrimitive?.int)
        assertEquals(0, errorAttrs["pre_ready_chunks_queued"]?.jsonPrimitive?.int)
        assertEquals(0, errorAttrs["pre_ready_chunks_sent"]?.jsonPrimitive?.int)
        assertEquals("failed", errorAttrs["error_code"]?.jsonPrimitive?.content)
        assertEquals("app", errorAttrs["client_scheme"]?.jsonPrimitive?.content)

        // Explicit unrecognized client_scheme coerces to closed-set "other" so raw strings never leak.
        val unknownSchemeAttrs = voiceDictationSessionTraceAttrs(errorSummary, clientScheme = "custom-host-leak")
        assertEquals("other", unknownSchemeAttrs["client_scheme"]?.jsonPrimitive?.content)
    }

    @Test
    fun errorCodeClassifierCoversAllVoiceDictationV2Errors() {
        assertEquals(
            VoiceDictationSessionErrorCode.MicPermission,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.PermissionDenied),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.MicNone,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.DeviceUnavailable),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.Failed,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.AudioCaptureFailed("device boom")),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.Disabled,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.Disabled())),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.InvalidRequest,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.InvalidRequest())),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.QuotaExceeded,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.QuotaExceeded())),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.RateLimited,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.RateLimited())),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.Unavailable,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.Unavailable())),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.ConnectionLost,
            voiceDictationSessionErrorCode(
                VoiceDictationV2Error.PermitRefused(
                    VoiceDictationV2PermitFailure.Failed(code = "voice_dictation_network_error"),
                ),
            ),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.Failed,
            voiceDictationSessionErrorCode(
                VoiceDictationV2Error.PermitRefused(
                    VoiceDictationV2PermitFailure.Failed(statusCode = 500, code = "internal_error"),
                ),
            ),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.SessionLimitReached,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.SessionLimitReached)),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.QuotaExceeded,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.QuotaExceeded)),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.Busy,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.Busy)),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.Unavailable,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.Unavailable)),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.ConnectionLost,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.InvalidPermit)),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.ConnectionLost,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.ProtocolError)),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.RateLimited,
            voiceDictationSessionErrorCode(
                VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.Unknown("upstream_rate_cap")),
            ),
        )
        assertEquals(
            VoiceDictationSessionErrorCode.ConnectionLost,
            voiceDictationSessionErrorCode(VoiceDictationV2Error.ConnectionLost("socket closed")),
        )
    }

    @Test
    fun clientTraceSenderEmitsVoiceDictationSessionRespectingPrivacyModeWithZeroTextOrIds() = runTest {
        val privacyMode = MutableStateFlow(RaftPrivacyMode.Off)
        val foreground = MutableStateFlow(true)
        val sentBatches = mutableListOf<RaftWebTraceBatchRequest>()
        val transport =
            object : RaftClientTraceTransport {
                override suspend fun currentAccount(): String = "user-secret-id"

                override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer =
                    RaftScopeAttestationAnswer(
                        status = 200,
                        response =
                            RaftScopeAttestationResponse(
                                attestation = "att-token",
                                expiresAt = "2026-11-28T00:00:00.000Z",
                            ),
                    )

                override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                    sentBatches += batch
                    return 200
                }
            }
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { 1_795_723_200_000L },
                defaultAttrs = { mapOf("clientPlatform" to "ios", "appVersion" to "1.32.0") },
                flushDelayMillis = 100L,
                privacyMode = privacyMode,
                foreground = foreground,
            )
        sender.bindTransport(transport)
        sender.setAccount("user-secret-id")

        val metrics = VoiceDictationSessionMetrics(mode = VoiceDictationSessionMode.Hold, startedAtMs = 1_000L)
        metrics.micReadyAtMs = 1_080L
        metrics.connectedAtMs = 1_300L
        metrics.streamUrlSource = VoiceStreamUrlSource.RaftEdge
        metrics.preReadyChunksQueued = 2
        metrics.preReadyChunksSent = 2
        val secretTranscript = "SECRET_SPOKEN_TRANSCRIPT_SHOULD_NEVER_LEAK"
        metrics.recordSnapshot(
            text = secretTranscript,
            stableLen = secretTranscript.length,
            refined = false,
            timestampMs = 1_600L,
            isActiveSession = true,
        )
        metrics.freezeStop(VoiceDictationStopReason.Button, stopAtMs = 2_400L)
        val summary =
            assertNotNull(
                metrics.buildSummary(
                    stoppedBy = VoiceDictationStopReason.Button,
                    finishingMs = 300L,
                    errorCode = null,
                    nowMs = 2_700L,
                ),
            )

        sender.emit(
            serverId = "srv-secret-id",
            name = VoiceDictationSessionEventName,
            attrs = voiceDictationSessionTraceAttrs(summary),
        )
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, sentBatches.size)
        val event = sentBatches.single().events.single()
        assertEquals(VoiceDictationSessionEventName, event.name)
        assertEquals("web", event.surface)
        assertEquals("hold", event.attrs["mode"]?.jsonPrimitive?.content)
        assertEquals("button", event.attrs["stopped_by"]?.jsonPrimitive?.content)
        assertEquals("raft_edge", event.attrs["stream_url_source"]?.jsonPrimitive?.content)
        assertEquals("ios", event.attrs["clientPlatform"]?.jsonPrimitive?.content)
        assertEquals("1.32.0", event.attrs["appVersion"]?.jsonPrimitive?.content)

        val serializedAttrs = event.attrs.toString()
        assertFalse(secretTranscript in serializedAttrs)
        assertFalse("srv-secret-id" in serializedAttrs)
        assertFalse("user-secret-id" in serializedAttrs)
        assertFalse("channel" in serializedAttrs.lowercase())

        // RAFT_PRIVACY_MODE = On drops subsequent events completely.
        privacyMode.value = RaftPrivacyMode.On
        runCurrent()
        sender.emit(
            serverId = "srv-secret-id",
            name = VoiceDictationSessionEventName,
            attrs = voiceDictationSessionTraceAttrs(summary),
        )
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, sentBatches.size)
        assertTrue(sender.queuedCount == 0)
    }
}
