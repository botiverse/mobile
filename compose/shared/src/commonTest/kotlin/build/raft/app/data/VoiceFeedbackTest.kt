package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VoiceFeedbackTest {
    @AfterTest
    fun tearDownPrivacyMode() {
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
    }

    // -------------------------------------------------------------------------
    // 1. Request schema encoding parity with slock#17246 (z.strictObject)
    // -------------------------------------------------------------------------

    @Test
    fun dictationFeedbackRequestEncodingMatchesStrictServerSchema() {
        // Missing both rating and clientMetrics -> rejected locally
        assertNull(
            buildVoiceDictationFeedbackRequestBody(
                VoiceDictationFeedbackRequest(sessionId = "jti_1"),
            ),
        )
        // Blank or oversized sessionId -> rejected locally
        assertNull(
            buildVoiceDictationFeedbackRequestBody(
                VoiceDictationFeedbackRequest(sessionId = "   ", rating = VoiceFeedbackRating.Good),
            ),
        )
        assertNull(
            buildVoiceDictationFeedbackRequestBody(
                VoiceDictationFeedbackRequest(
                    sessionId = "a".repeat(VOICE_FEEDBACK_SESSION_ID_MAX_CHARS + 1),
                    rating = VoiceFeedbackRating.Good,
                ),
            ),
        )

        // Metrics-only implicit payload (no rating, no text, no traceId/streamUrlSource)
        val metricsBody =
            assertNotNull(
                buildVoiceDictationFeedbackRequestBody(
                    VoiceDictationFeedbackRequest(
                        sessionId = "jti_0123456789abcdef",
                        clientMetrics =
                            VoiceDictationClientMetrics(
                                dictatedChars = 42,
                                sentChars = 40,
                                editDistance = 3,
                                editRatio = 0.0714,
                                sentAsIs = false,
                                abandoned = false,
                                reRecorded = false,
                                sentBeforeRefineDone = false,
                                clientConnectMs = 185,
                                clientFirstPartialMs = 410,
                                clientPlatform = VoiceFeedbackClientPlatform.Mobile,
                            ),
                    ),
                ),
            )
        val metricsJson = Json.parseToJsonElement(metricsBody).jsonObject
        assertEquals(setOf("sessionId", "clientMetrics"), metricsJson.keys)
        assertEquals("jti_0123456789abcdef", metricsJson["sessionId"]?.jsonPrimitive?.content)
        val cm = metricsJson["clientMetrics"]!!.jsonObject
        assertFalse("streamUrlSource" in cm)
        assertFalse("traceId" in metricsJson)
        assertEquals(42, cm["dictatedChars"]?.jsonPrimitive?.int)
        assertEquals(40, cm["sentChars"]?.jsonPrimitive?.int)
        assertEquals(3, cm["editDistance"]?.jsonPrimitive?.int)
        assertEquals(0.0714, cm["editRatio"]?.jsonPrimitive?.double)
        assertEquals(false, cm["sentAsIs"]?.jsonPrimitive?.boolean)
        assertEquals(false, cm["abandoned"]?.jsonPrimitive?.boolean)
        assertEquals(false, cm["reRecorded"]?.jsonPrimitive?.boolean)
        assertEquals(false, cm["sentBeforeRefineDone"]?.jsonPrimitive?.boolean)
        assertEquals(185, cm["clientConnectMs"]?.jsonPrimitive?.int)
        assertEquals(410, cm["clientFirstPartialMs"]?.jsonPrimitive?.int)
        assertEquals("mobile", cm["clientPlatform"]?.jsonPrimitive?.content)

        // Explicit bad feedback with opt-in sample consent + deduped tags + bounded note/sample
        val badBody =
            assertNotNull(
                buildVoiceDictationFeedbackRequestBody(
                    VoiceDictationFeedbackRequest(
                        sessionId = "jti_bad_case",
                        rating = VoiceFeedbackRating.Bad,
                        tags =
                            listOf(
                                VoiceFeedbackTag.WrongWord,
                                VoiceFeedbackTag.BadRefine,
                                VoiceFeedbackTag.WrongWord,
                            ),
                        note = "  ${"n".repeat(VOICE_FEEDBACK_NOTE_MAX_CHARS + 50)}  ",
                        sample =
                            VoiceFeedbackSample(
                                consent = true,
                                raw = "r".repeat(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS + 100),
                                refined = "Open rafted.",
                                sent = "Open Raft.",
                            ),
                    ),
                ),
            )
        val badJson = Json.parseToJsonElement(badBody).jsonObject
        assertEquals("bad", badJson["rating"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("wrong_word", "bad_refine"),
            badJson["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(VOICE_FEEDBACK_NOTE_MAX_CHARS, badJson["note"]!!.jsonPrimitive.content.length)
        val sampleObj = badJson["sample"]!!.jsonObject
        assertEquals(true, sampleObj["consent"]?.jsonPrimitive?.boolean)
        assertEquals(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS, sampleObj["raw"]!!.jsonPrimitive.content.length)
        assertEquals("Open rafted.", sampleObj["refined"]?.jsonPrimitive?.content)
        assertEquals("Open Raft.", sampleObj["sent"]?.jsonPrimitive?.content)

        // Sample MUST be stripped client-side if consent=false OR rating != Bad
        val unconsented =
            Json.parseToJsonElement(
                assertNotNull(
                    buildVoiceDictationFeedbackRequestBody(
                        VoiceDictationFeedbackRequest(
                            sessionId = "jti_2",
                            rating = VoiceFeedbackRating.Bad,
                            sample = VoiceFeedbackSample(consent = false, raw = "a", refined = "b"),
                        ),
                    ),
                ),
            ).jsonObject
        assertFalse("sample" in unconsented)

        val goodWithSample =
            Json.parseToJsonElement(
                assertNotNull(
                    buildVoiceDictationFeedbackRequestBody(
                        VoiceDictationFeedbackRequest(
                            sessionId = "jti_3",
                            rating = VoiceFeedbackRating.Good,
                            sample = VoiceFeedbackSample(consent = true, raw = "a", refined = "b"),
                        ),
                    ),
                ),
            ).jsonObject
        assertFalse("sample" in goodWithSample)
    }

    @Test
    fun agentVoiceCallFeedbackRequestEncodingMatchesStrictServerSchema() {
        val minimal =
            Json.parseToJsonElement(
                buildAgentVoiceCallFeedbackRequestBody(
                    AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good, note = "   "),
                ),
            ).jsonObject
        assertEquals(setOf("rating"), minimal.keys)
        assertEquals("good", minimal["rating"]?.jsonPrimitive?.content)

        val detailed =
            Json.parseToJsonElement(
                buildAgentVoiceCallFeedbackRequestBody(
                    AgentVoiceCallFeedbackRequest(
                        rating = VoiceFeedbackRating.Bad,
                        tags =
                            listOf(
                                VoiceFeedbackTag.WrongAnswer,
                                VoiceFeedbackTag.TooSlow,
                                VoiceFeedbackTag.CutOff,
                                VoiceFeedbackTag.Interrupted,
                                VoiceFeedbackTag.Other,
                                VoiceFeedbackTag.TooSlow,
                            ),
                        note = "  Agent interrupted mid-sentence  ",
                    ),
                ),
            ).jsonObject
        assertEquals(setOf("rating", "tags", "note"), detailed.keys)
        assertEquals("bad", detailed["rating"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("wrong_answer", "too_slow", "cut_off", "interrupted", "other"),
            detailed["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("Agent interrupted mid-sentence", detailed["note"]?.jsonPrimitive?.content)
    }

    // -------------------------------------------------------------------------
    // 2. HTTP proxy routes, refusal handling & fail-closed privacy mode
    // -------------------------------------------------------------------------

    @Test
    fun dictationFeedbackApiSubmitsToRaftProxyRouteAndMapsAllRefusals() = runTest {
        val client = FakeFeedbackNetworkClient()
        val api =
            RaftVoiceFeedbackNetworkApi(
                networkClient = client,
                privacyModeProvider = { RaftPrivacyMode.Off },
            )
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val traceHeaders = mapOf("X-Trace-Id" to listOf(traceId))

        // 204 No Content -> Submitted (metrics-only uses Background priority)
        client.nextResponse = HomeNetworkResponse(code = 204, bodyText = null)
        val metricsOutcome =
            api.submitDictationFeedback(
                serverId = "srv-1",
                request =
                    VoiceDictationFeedbackRequest(
                        sessionId = "jti_1",
                        clientMetrics = VoiceDictationClientMetrics(sentAsIs = true),
                    ),
            )
        assertEquals(VoiceFeedbackSubmitOutcome.Submitted, metricsOutcome)
        assertEquals(DataRequestPriority.Background, client.lastPriority)
        assertEquals("voice/dictation/feedback", client.lastPath)
        assertEquals("POST", client.lastMethod)
        assertEquals(ServerId("srv-1"), client.lastServerIdOverride)

        // 200 OK with explicit rating uses Foreground priority
        client.nextResponse = HomeNetworkResponse(code = 200, bodyText = "")
        val ratingOutcome =
            api.submitDictationFeedback(
                serverId = "srv-1",
                request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Bad),
            )
        assertEquals(VoiceFeedbackSubmitOutcome.Submitted, ratingOutcome)
        assertEquals(DataRequestPriority.Foreground, client.lastPriority)

        // 503 voice_feedback_unavailable (e.g. server RAFT_PRIVACY_MODE)
        client.nextResponse =
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                errorBodyText = """{"code":"voice_feedback_unavailable","error":"Voice feedback isn't available right now."}""",
                responseHeaders = traceHeaders,
            )
        val unavailable =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Bad),
                ),
            )
        val unavailableFailure = assertIs<VoiceFeedbackSubmitFailure.Unavailable>(unavailable.failure)
        assertEquals(traceId, unavailableFailure.traceId)
        assertEquals("Voice feedback isn't available right now.", unavailableFailure.serverMessage)

        // 429 voice_feedback_rate_limited (body retryAfterSeconds wins over header)
        client.nextResponse =
            HomeNetworkResponse(
                code = 429,
                bodyText = null,
                errorBodyText = """{"code":"voice_feedback_rate_limited","error":"Too many feedback submissions.","retryAfterSeconds":12}""",
                responseHeaders = traceHeaders + ("Retry-After" to listOf("30")),
            )
        val rateLimited =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Bad),
                ),
            )
        val rateFailure = assertIs<VoiceFeedbackSubmitFailure.RateLimited>(rateLimited.failure)
        assertEquals(12, rateFailure.retryAfterSeconds)
        assertEquals(traceId, rateFailure.traceId)

        // 429 with header-only Retry-After fallback
        client.nextResponse =
            HomeNetworkResponse(
                code = 429,
                bodyText = null,
                errorBodyText = """{"code":"voice_feedback_rate_limited"}""",
                responseHeaders = traceHeaders + ("Retry-After" to listOf("45")),
            )
        val rateHeader =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Bad),
                ),
            )
        assertEquals(45, assertIs<VoiceFeedbackSubmitFailure.RateLimited>(rateHeader.failure).retryAfterSeconds)

        // 404 voice_session_not_found
        client.nextResponse =
            HomeNetworkResponse(
                code = 404,
                bodyText = null,
                errorBodyText = """{"code":"voice_session_not_found","error":"Voice dictation session not found."}""",
                responseHeaders = traceHeaders,
            )
        val notFound =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_missing", rating = VoiceFeedbackRating.Good),
                ),
            )
        val notFoundFailure = assertIs<VoiceFeedbackSubmitFailure.NotFound>(notFound.failure)
        assertEquals("voice_session_not_found", notFoundFailure.code)
        assertEquals(traceId, notFoundFailure.traceId)

        // 400 invalid_request
        client.nextResponse =
            HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = """{"code":"invalid_request","error":"Invalid voice dictation feedback payload."}""",
                responseHeaders = traceHeaders,
            )
        val invalid =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Good),
                ),
            )
        assertIs<VoiceFeedbackSubmitFailure.InvalidRequest>(invalid.failure)
    }

    @Test
    fun agentVoiceCallFeedbackApiSubmitsToRaftProxyRouteAndMapsRefusals() = runTest {
        val client = FakeFeedbackNetworkClient()
        val api =
            RaftVoiceFeedbackNetworkApi(
                networkClient = client,
                privacyModeProvider = { RaftPrivacyMode.Off },
            )
        val callId = "123e4567-e89b-12d3-a456-426614174000"

        client.nextResponse = HomeNetworkResponse(code = 204, bodyText = null)
        val ok =
            api.submitAgentVoiceCallFeedback(
                serverId = "srv-9",
                agentId = "agent/1",
                callId = callId,
                request =
                    AgentVoiceCallFeedbackRequest(
                        rating = VoiceFeedbackRating.Bad,
                        tags = listOf(VoiceFeedbackTag.WrongAnswer),
                        note = "Missed context",
                    ),
            )
        assertEquals(VoiceFeedbackSubmitOutcome.Submitted, ok)
        assertEquals(DataRequestPriority.Foreground, client.lastPriority)
        assertEquals("agents/agent%2F1/voice/calls/$callId/feedback", client.lastPath)
        assertEquals("POST", client.lastMethod)
        assertEquals(ServerId("srv-9"), client.lastServerIdOverride)

        // 404 agent_not_found
        client.nextResponse =
            HomeNetworkResponse(
                code = 404,
                bodyText = null,
                errorBodyText = """{"code":"agent_not_found","error":"Agent not found."}""",
            )
        val notFound =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitAgentVoiceCallFeedback(
                    serverId = "srv-9",
                    agentId = "agent-1",
                    callId = callId,
                    request = AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good),
                ),
            )
        assertEquals("agent_not_found", assertIs<VoiceFeedbackSubmitFailure.NotFound>(notFound.failure).code)

        // 503 voice_feedback_unavailable
        client.nextResponse =
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                errorBodyText = """{"code":"voice_feedback_unavailable","error":"Voice feedback isn't available right now."}""",
            )
        val unavailable =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitAgentVoiceCallFeedback(
                    serverId = "srv-9",
                    agentId = "agent-1",
                    callId = callId,
                    request = AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good),
                ),
            )
        assertIs<VoiceFeedbackSubmitFailure.Unavailable>(unavailable.failure)

        // Blank identifiers rejected locally without hitting network
        val callsBefore = client.requestCount
        val blankCall =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitAgentVoiceCallFeedback(
                    serverId = "srv-9",
                    agentId = "agent-1",
                    callId = "   ",
                    request = AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good),
                ),
            )
        assertIs<VoiceFeedbackSubmitFailure.InvalidRequest>(blankCall.failure)
        assertEquals(callsBefore, client.requestCount)
    }

    @Test
    fun voiceFeedbackApiFailsClosedUnderPrivacyModeUnknownAndOnWithZeroNetworkCalls() = runTest {
        val client = FakeFeedbackNetworkClient()
        client.nextResponse = HomeNetworkResponse(code = 204, bodyText = null)
        val api = RaftVoiceFeedbackNetworkApi(networkClient = client)

        // Unknown -> blocked with zero network requests
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
        assertFalse(voiceFeedbackAllowed())
        val unknownDictation =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Good),
                ),
            )
        assertEquals(
            VoiceFeedbackSubmitFailure.PrivacyModeBlocked(RaftPrivacyMode.Unknown),
            unknownDictation.failure,
        )
        val unknownCall =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitAgentVoiceCallFeedback(
                    serverId = "srv-1",
                    agentId = "agent-1",
                    callId = "call-1",
                    request = AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good),
                ),
            )
        assertEquals(
            VoiceFeedbackSubmitFailure.PrivacyModeBlocked(RaftPrivacyMode.Unknown),
            unknownCall.failure,
        )
        assertEquals(0, client.requestCount)

        // On -> blocked with zero network requests
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.On)
        assertFalse(voiceFeedbackAllowed())
        val onDictation =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitDictationFeedback(
                    serverId = "srv-1",
                    request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Bad),
                ),
            )
        assertEquals(
            VoiceFeedbackSubmitFailure.PrivacyModeBlocked(RaftPrivacyMode.On),
            onDictation.failure,
        )
        val onCall =
            assertIs<VoiceFeedbackSubmitOutcome.Refused>(
                api.submitAgentVoiceCallFeedback(
                    serverId = "srv-1",
                    agentId = "agent-1",
                    callId = "call-1",
                    request = AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Bad),
                ),
            )
        assertEquals(
            VoiceFeedbackSubmitFailure.PrivacyModeBlocked(RaftPrivacyMode.On),
            onCall.failure,
        )
        assertEquals(0, client.requestCount)

        // Off -> allowed and forwards to proxy
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off)
        assertTrue(voiceFeedbackAllowed())
        assertEquals(
            VoiceFeedbackSubmitOutcome.Submitted,
            api.submitDictationFeedback(
                serverId = "srv-1",
                request = VoiceDictationFeedbackRequest(sessionId = "jti_1", rating = VoiceFeedbackRating.Good),
            ),
        )
        assertEquals(
            VoiceFeedbackSubmitOutcome.Submitted,
            api.submitAgentVoiceCallFeedback(
                serverId = "srv-1",
                agentId = "agent-1",
                callId = "call-1",
                request = AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good),
            ),
        )
        assertEquals(2, client.requestCount)
    }

    // -------------------------------------------------------------------------
    // 3. Edit-distance metrics & VoiceDictationOutcomeTracker
    // -------------------------------------------------------------------------

    @Test
    fun computeDictationEditMetricsHandlesExactMatchCjkEmojiAbandonedAndBoundedLongDrafts() {
        assertEquals(
            DictationEditMetrics(
                dictatedChars = 10,
                sentChars = 10,
                editDistance = 0,
                editRatio = 0.0,
                sentAsIs = true,
            ),
            computeDictationEditMetrics("  Hello Raft  ", "Hello Raft"),
        )

        val cjk = computeDictationEditMetrics("打开拉夫特文档", "打开Raft文档")
        assertEquals(7, cjk.dictatedChars)
        assertEquals(8, cjk.sentChars)
        assertEquals(4, cjk.editDistance)
        assertEquals(0.5, cjk.editRatio)
        assertFalse(cjk.sentAsIs)

        // Unicode surrogate-pair emoji counts as 1 code point, matching JS Array.from()
        val emoji = computeDictationEditMetrics("Ship \uD83D\uDE80", "Ship \uD83C\uDF89")
        assertEquals(6, emoji.dictatedChars)
        assertEquals(6, emoji.sentChars)
        assertEquals(1, emoji.editDistance)
        assertEquals(0.1667, emoji.editRatio)
        assertFalse(emoji.sentAsIs)

        val cleared = computeDictationEditMetrics("discarded text", "")
        assertEquals(
            DictationEditMetrics(
                dictatedChars = 14,
                sentChars = 0,
                editDistance = 14,
                editRatio = 1.0,
                sentAsIs = false,
            ),
            cleared,
        )

        val bothEmpty = computeDictationEditMetrics("   ", "")
        assertEquals(
            DictationEditMetrics(
                dictatedChars = 0,
                sentChars = 0,
                editDistance = 0,
                editRatio = 0.0,
                sentAsIs = true,
            ),
            bothEmpty,
        )

        // Bounded tail length diff for drafts exceeding 2000 code points
        val longBase = "a".repeat(2_050)
        val longEdited = "a".repeat(2_060)
        val longMetrics = computeDictationEditMetrics(longBase, longEdited)
        assertEquals(2_050, longMetrics.dictatedChars)
        assertEquals(2_060, longMetrics.sentChars)
        assertEquals(10, longMetrics.editDistance)
        assertFalse(longMetrics.sentAsIs)
    }

    @Test
    fun outcomeTrackerCoversSentEditedClearedReRecordedCancelledAndPrivacySuppression() {
        assertEquals(VoiceFeedbackClientPlatform.Desktop, currentVoiceFeedbackClientPlatform("desktop"))
        assertEquals(VoiceFeedbackClientPlatform.Mobile, currentVoiceFeedbackClientPlatform("android"))
        assertEquals(VoiceFeedbackClientPlatform.Mobile, currentVoiceFeedbackClientPlatform("ios"))

        var nowMs = 50_000L
        var privacyMode = RaftPrivacyMode.Off
        val reports = mutableListOf<VoiceDictationOutcomeReport>()
        val tracker =
            VoiceDictationOutcomeTracker(
                nowMillis = { nowMs },
                clientPlatformProvider = { VoiceFeedbackClientPlatform.Mobile },
                privacyModeProvider = { privacyMode },
                onOutcomeReport = { reports += it },
            )

        // 1. Unedited send
        val t1 =
            assertNotNull(
                tracker.capture(
                    VoiceDictationOutcomeCaptureInput(
                        serverId = "srv-1",
                        sessionId = "jti_1",
                        composerText = "Ship it.",
                        transcript = DictationTranscriptCapture(v2LastRawText = "ship it", v2LastRefinedText = "Ship it."),
                        startedAtMs = nowMs - 1_000L,
                        connectedAtMs = nowMs - 850L,
                        firstPartialAtMs = nowMs - 600L,
                    ),
                ),
            )
        assertEquals("ship it", t1.raw)
        assertEquals("Ship it.", t1.refined)
        assertNull(t1.sent)

        val submitted1 = assertNotNull(tracker.reportSubmitted("Ship it."))
        assertEquals("Ship it.", submitted1.sent)
        assertEquals(1, reports.size)
        assertEquals(true, reports.last().clientMetrics.sentAsIs)
        assertEquals(0, reports.last().clientMetrics.editDistance)
        assertEquals(150, reports.last().clientMetrics.clientConnectMs)
        assertEquals(400, reports.last().clientMetrics.clientFirstPartialMs)
        assertEquals(VoiceFeedbackClientPlatform.Mobile, reports.last().clientMetrics.clientPlatform)
        assertFalse(tracker.hasPendingOutcome())

        // 2. Edited send with sentBeforeRefineDone = true returns null on capture, reports on submit
        val t2 =
            tracker.capture(
                VoiceDictationOutcomeCaptureInput(
                    serverId = "srv-1",
                    sessionId = "jti_2",
                    composerText = "hello world",
                    transcript = DictationTranscriptCapture(v2LastRawText = "hello world"),
                    startedAtMs = nowMs - 500L,
                    sentBeforeRefineDone = true,
                ),
            )
        assertNull(t2)
        assertTrue(tracker.hasPendingOutcome())
        tracker.reportSubmitted("hello Raft")
        assertEquals(2, reports.size)
        assertEquals(false, reports.last().clientMetrics.sentAsIs)
        assertEquals(true, reports.last().clientMetrics.sentBeforeRefineDone)
        assertEquals(5, reports.last().clientMetrics.editDistance)

        // 3. Cleared composer -> abandoned = true, reRecorded = false
        tracker.capture(
            VoiceDictationOutcomeCaptureInput(
                serverId = "srv-1",
                sessionId = "jti_3",
                composerText = "wrong text",
                transcript = DictationTranscriptCapture(v2LastRawText = "wrong text"),
                startedAtMs = nowMs,
            ),
        )
        tracker.onComposerTextChanged("still editing")
        assertEquals(2, reports.size)
        tracker.onComposerTextChanged("   ")
        assertEquals(3, reports.size)
        assertEquals(true, reports.last().clientMetrics.abandoned)
        assertEquals(false, reports.last().clientMetrics.reRecorded)
        assertEquals(0, reports.last().clientMetrics.sentChars)
        assertEquals(1.0, reports.last().clientMetrics.editRatio)

        // 4. Re-dictation within <= 10 s -> abandoned = true, reRecorded = true
        tracker.capture(
            VoiceDictationOutcomeCaptureInput(
                serverId = "srv-1",
                sessionId = "jti_4",
                composerText = "try one",
                transcript = DictationTranscriptCapture(v2LastRawText = "try one"),
                startedAtMs = nowMs,
            ),
        )
        nowMs += VOICE_DICTATION_RE_RECORD_WINDOW_MS
        tracker.onStartNewSession(nowMs)
        assertEquals(4, reports.size)
        assertEquals(true, reports.last().clientMetrics.abandoned)
        assertEquals(true, reports.last().clientMetrics.reRecorded)

        // 5. New dictation after > 10 s -> abandoned = true, reRecorded = false
        tracker.capture(
            VoiceDictationOutcomeCaptureInput(
                serverId = "srv-1",
                sessionId = "jti_5",
                composerText = "try two",
                transcript = DictationTranscriptCapture(v2LastRawText = "try two"),
                startedAtMs = nowMs,
            ),
        )
        nowMs += VOICE_DICTATION_RE_RECORD_WINDOW_MS + 1L
        tracker.onStartNewSession(nowMs)
        assertEquals(5, reports.size)
        assertEquals(true, reports.last().clientMetrics.abandoned)
        assertEquals(false, reports.last().clientMetrics.reRecorded)

        // 6. Explicit cancel clears pending outcome without emitting
        tracker.capture(
            VoiceDictationOutcomeCaptureInput(
                serverId = "srv-1",
                sessionId = "jti_6",
                composerText = "cancelled draft",
                transcript = DictationTranscriptCapture(v2LastRawText = "cancelled draft"),
                startedAtMs = nowMs,
            ),
        )
        tracker.clear()
        assertFalse(tracker.hasPendingOutcome())
        assertNull(tracker.reportSubmitted("cancelled draft"))
        assertEquals(5, reports.size)

        // 7. Privacy mode Unknown and On suppress emission completely
        for (blockedMode in listOf(RaftPrivacyMode.Unknown, RaftPrivacyMode.On)) {
            privacyMode = blockedMode
            tracker.capture(
                VoiceDictationOutcomeCaptureInput(
                    serverId = "srv-1",
                    sessionId = "jti_priv_${blockedMode.name}",
                    composerText = "private speech",
                    transcript = DictationTranscriptCapture(v2LastRawText = "private speech"),
                    startedAtMs = nowMs,
                ),
            )
            tracker.reportSubmitted("private speech")
            assertEquals(5, reports.size, "outcome must be dropped under privacyMode=$blockedMode")
        }
    }

    private class FakeFeedbackNetworkClient : RaftNetworkClient {
        var requestCount: Int = 0
        var lastPriority: DataRequestPriority? = null
        var lastPath: String? = null
        var lastMethod: String? = null
        var lastBodyText: String? = null
        var lastServerIdOverride: ServerId? = null
        var nextResponse: HomeNetworkResponse = HomeNetworkResponse(code = 204, bodyText = null)

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requestCount += 1
            lastPriority = priority
            lastPath = path
            lastMethod = method
            lastBodyText = bodyText
            lastServerIdOverride = serverIdOverride
            return nextResponse
        }
    }
}
