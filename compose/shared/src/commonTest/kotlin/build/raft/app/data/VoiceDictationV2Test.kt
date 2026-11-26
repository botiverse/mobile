package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.realtime.socket.RaftWebSocketConnector
import build.raft.realtime.socket.RaftWebSocketFrame
import build.raft.realtime.socket.RaftWebSocketSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDictationV2Test {
    private val validChannelUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val validPermitJson =
        """
        {
          "permit": "hv2.k1.payload.sig",
          "url": "wss://live.holt.botiverse.build/v1/stream",
          "expiresAt": "2026-11-26T20:01:00.000Z",
          "maxDurationSeconds": 600,
          "quota": {
            "usedSeconds": 120,
            "limitSeconds": 7200,
            "remainingSeconds": 7080,
            "resetsAt": "2026-11-27T00:00:00.000Z"
          }
        }
        """.trimIndent()

    @AfterTest
    fun tearDownRegistry() {
        RaftVoiceDictationAudioRecorderRegistry.unregister()
    }

    // -------------------------------------------------------------------------
    // 1. Permit HTTP API: request encoding, response parsing, refusal handling
    // -------------------------------------------------------------------------

    @Test
    fun permitRequestBodyEncodesV2NormalizesLanguageAndOmitsUnsupportedFields() {
        assertEquals("""{"v":2}""", buildVoiceDictationV2PermitRequestBody())
        assertEquals(
            """{"v":2,"language":"zh","channelId":"$validChannelUuid"}""",
            buildVoiceDictationV2PermitRequestBody(language = "zh-Hans-CN", channelId = "  $validChannelUuid  "),
        )
        assertEquals(
            """{"v":2,"language":"en"}""",
            buildVoiceDictationV2PermitRequestBody(language = "EN_us", channelId = "not-a-uuid"),
        )
        assertEquals(
            """{"v":2}""",
            buildVoiceDictationV2PermitRequestBody(language = "it-IT", channelId = "   "),
        )
        for (lang in listOf("zh", "en", "ja", "ko", "es", "pt", "ru", "fr", "de")) {
            assertEquals(lang, normalizeVoiceDictationLanguage(lang.uppercase()))
            assertEquals(lang, normalizeVoiceDictationLanguage("$lang-TEST"))
        }
        assertNull(normalizeVoiceDictationLanguage("ar"))
        assertNull(normalizeVoiceDictationLanguage("auto"))
        assertNull(normalizeVoiceDictationLanguage(""))
        assertNull(normalizeVoiceDictationLanguage(null))
    }

    @Test
    fun permitResponseParses201And200WithQuotaDegradedAndFailsClosedOnInvalidBodies() = runTest {
        val parsed = assertNotNull(parseVoiceDictationV2Permit(validPermitJson))
        assertEquals("hv2.k1.payload.sig", parsed.permit)
        assertEquals("wss://live.holt.botiverse.build/v1/stream", parsed.url)
        assertEquals("2026-11-26T20:01:00.000Z", parsed.expiresAtIso)
        assertTrue(parsed.expiresAtEpochMs > 0L)
        assertEquals(600, parsed.maxDurationSeconds)
        assertEquals(120, parsed.quota.usedSeconds)
        assertEquals(7200, parsed.quota.limitSeconds)
        assertEquals(7080, parsed.quota.remainingSeconds)
        assertEquals("2026-11-27T00:00:00.000Z", parsed.quota.resetsAtIso)
        assertNotNull(parsed.quota.resetsAtEpochMs)
        assertFalse(parsed.quotaDegraded)

        // Snake-case quota_degraded (primary per #17149) and camelCase fallback + ws:// for local dev
        val degradedSnake =
            assertNotNull(
                parseVoiceDictationV2Permit(
                    """
                    {
                      "permit": "hv2.k1.p.s",
                      "url": "ws://localhost:8787/v1/stream",
                      "expiresAt": "2026-11-26T20:01:00.000Z",
                      "maxDurationSeconds": 180,
                      "quota": {"usedSeconds": 0, "limitSeconds": 7200, "remainingSeconds": 180, "resetsAt": "2026-11-27T00:00:00.000Z"},
                      "quota_degraded": true
                    }
                    """.trimIndent(),
                ),
            )
        assertTrue(degradedSnake.quotaDegraded)
        assertEquals(180, degradedSnake.maxDurationSeconds)
        assertEquals("ws://localhost:8787/v1/stream", degradedSnake.url)

        val degradedCamel =
            assertNotNull(
                parseVoiceDictationV2Permit(
                    """
                    {
                      "permit": "hv2.k1.p.s",
                      "url": "wss://live.example.test/v1/stream",
                      "expiresAt": "2026-11-26T20:01:00.000Z",
                      "maxDurationSeconds": 180,
                      "quota": {"usedSeconds": 0, "limitSeconds": 7200, "remainingSeconds": 180, "resetsAt": "2026-11-27T00:00:00.000Z"},
                      "quotaDegraded": true
                    }
                    """.trimIndent(),
                ),
            )
        assertTrue(degradedCamel.quotaDegraded)

        // quota_degraded takes precedence over quotaDegraded when both appear
        val snakeWins =
            assertNotNull(
                parseVoiceDictationV2Permit(
                    """
                    {
                      "permit": "hv2.k1.p.s",
                      "url": "wss://live.example.test/v1/stream",
                      "expiresAt": "2026-11-26T20:01:00.000Z",
                      "maxDurationSeconds": 180,
                      "quota": {"usedSeconds": 0, "limitSeconds": 7200, "remainingSeconds": 180, "resetsAt": "2026-11-27T00:00:00.000Z"},
                      "quota_degraded": false,
                      "quotaDegraded": true
                    }
                    """.trimIndent(),
                ),
            )
        assertFalse(snakeWins.quotaDegraded)

        // Fail-closed checks
        assertNull(parseVoiceDictationV2Permit("not json"))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("hv2.k1.payload.sig", "   ")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("hv2.k1.payload.sig", "hv2 bad token")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("wss://", "https://")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("wss://live.holt.botiverse.build/v1/stream", "wss:///v1/stream")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("wss://live.holt.botiverse.build/v1/stream", "wss://")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("wss://live.holt.botiverse.build/v1/stream", "ws://localhost:notaport/v1")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("wss://live.holt.botiverse.build/v1/stream", "ws://localhost:70000/v1")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("\"maxDurationSeconds\": 600", "\"maxDurationSeconds\": 0")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("\"maxDurationSeconds\": 600", "\"maxDurationSeconds\": -10")))
        assertNull(parseVoiceDictationV2Permit(validPermitJson.replace("2026-11-26T20:01:00.000Z", "not-a-date")))
        assertNull(
            parseVoiceDictationV2Permit(
                """{"permit":"hv2.k1.p.s","url":"wss://live.example.test/v1/stream","expiresAt":"2026-11-26T20:01:00.000Z","maxDurationSeconds":600}""",
            ),
        )
        assertNull(
            parseVoiceDictationV2Permit(
                validPermitJson.replace("\"limitSeconds\": 7200", "\"limitSeconds\": 0"),
            ),
        )
        assertNull(
            parseVoiceDictationV2Permit(
                validPermitJson.replace("\"usedSeconds\": 120", "\"usedSeconds\": -1"),
            ),
        )

        // Network API sends POST voice/dictation/permits with X-Server-Id override and propagates trace ID
        val client = FakeDictationNetworkClient()
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        client.nextResponse =
            HomeNetworkResponse(
                code = 201,
                bodyText = validPermitJson,
                responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
            )
        val api = RaftVoiceDictationV2PermitApi(client)
        val outcome = api.requestPermit(serverId = "srv-1", language = "zh-CN", channelId = validChannelUuid)
        assertIs<VoiceDictationV2PermitOutcome.Issued>(outcome)
        assertEquals(traceId, outcome.traceId)
        assertEquals("voice/dictation/permits", client.lastPath)
        assertEquals("POST", client.lastMethod)
        assertEquals(ServerId("srv-1"), client.lastServerIdOverride)
        assertEquals("""{"v":2,"language":"zh","channelId":"$validChannelUuid"}""", client.lastBodyText)

        // Also accepts HTTP 200
        client.nextResponse = HomeNetworkResponse(code = 200, bodyText = validPermitJson)
        assertIs<VoiceDictationV2PermitOutcome.Issued>(api.requestPermit(serverId = "srv-1"))

        // Malformed 201 body fails closed as contract mismatch
        client.nextResponse =
            HomeNetworkResponse(
                code = 201,
                bodyText = """{"permit":"hv2.only"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
            )
        val mismatch = api.requestPermit(serverId = "srv-1")
        assertIs<VoiceDictationV2PermitOutcome.Refused>(mismatch)
        val mismatchFailure = assertIs<VoiceDictationV2PermitFailure.Failed>(mismatch.failure)
        assertEquals("voice_dictation_contract_mismatch", mismatchFailure.code)
        assertEquals(traceId, mismatchFailure.traceId)
    }

    @Test
    fun permitRefusalsDistinguishAllContractCodesRetryAfterQuotaDetailsAndTraceId() = runTest {
        val client = FakeDictationNetworkClient()
        val api = RaftVoiceDictationV2PermitApi(client)
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val traceHeaders = mapOf("X-Trace-Id" to listOf(traceId))

        // 404 voice_dictation_disabled
        client.nextResponse =
            HomeNetworkResponse(
                code = 404,
                bodyText = null,
                errorBodyText = """{"code":"voice_dictation_disabled","error":"Voice dictation is not enabled on this Server."}""",
                responseHeaders = traceHeaders,
            )
        val disabled = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        val disabledFailure = assertIs<VoiceDictationV2PermitFailure.Disabled>(disabled.failure)
        assertEquals(traceId, disabledFailure.traceId)
        assertEquals("Voice dictation is not enabled on this Server.", disabledFailure.serverMessage)

        // 429 dictation_rate_limited (body retryAfterSeconds)
        client.nextResponse =
            HomeNetworkResponse(
                code = 429,
                bodyText = null,
                errorBodyText = """{"code":"dictation_rate_limited","error":"Too many dictation requests.","retryAfterSeconds":15}""",
                responseHeaders = traceHeaders + ("Retry-After" to listOf("20")),
            )
        val rateLimited = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        val rateLimitedFailure = assertIs<VoiceDictationV2PermitFailure.RateLimited>(rateLimited.failure)
        assertEquals(15, rateLimitedFailure.retryAfterSeconds)
        assertEquals(traceId, rateLimitedFailure.traceId)

        // 429 dictation_rate_limited (header Retry-After fallback when body omits retryAfterSeconds)
        client.nextResponse =
            HomeNetworkResponse(
                code = 429,
                bodyText = null,
                errorBodyText = """{"code":"dictation_rate_limited","error":"Slow down."}""",
                responseHeaders = traceHeaders + ("Retry-After" to listOf("42")),
            )
        val rateLimitedHeader = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        assertEquals(42, assertIs<VoiceDictationV2PermitFailure.RateLimited>(rateLimitedHeader.failure).retryAfterSeconds)

        // 429 dictation_quota_exceeded with details + retryAfterSeconds
        client.nextResponse =
            HomeNetworkResponse(
                code = 429,
                bodyText = null,
                errorBodyText =
                    """
                    {
                      "code": "dictation_quota_exceeded",
                      "error": "Daily voice dictation limit reached.",
                      "retryAfterSeconds": 3600,
                      "details": {
                        "usedSeconds": 7200,
                        "limitSeconds": 7200,
                        "resetsAt": "2026-11-27T00:00:00.000Z"
                      }
                    }
                    """.trimIndent(),
                responseHeaders = traceHeaders + ("Retry-After" to listOf("3600")),
            )
        val quotaExceeded = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        val quotaFailure = assertIs<VoiceDictationV2PermitFailure.QuotaExceeded>(quotaExceeded.failure)
        assertEquals(7200, quotaFailure.usedSeconds)
        assertEquals(7200, quotaFailure.limitSeconds)
        assertEquals("2026-11-27T00:00:00.000Z", quotaFailure.resetsAtIso)
        assertEquals(3600, quotaFailure.retryAfterSeconds)
        assertEquals(traceId, quotaFailure.traceId)

        // 503 voice_dictation_unavailable
        client.nextResponse =
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                errorBodyText = """{"code":"voice_dictation_unavailable","error":"Voice dictation isn't available right now."}""",
                responseHeaders = traceHeaders,
            )
        val unavailable = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        assertEquals(traceId, assertIs<VoiceDictationV2PermitFailure.Unavailable>(unavailable.failure).traceId)

        // 400 invalid_request
        client.nextResponse =
            HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = """{"code":"invalid_request","error":"Unexpected dictation field."}""",
                responseHeaders = traceHeaders,
            )
        val invalidReq = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        assertEquals(traceId, assertIs<VoiceDictationV2PermitFailure.InvalidRequest>(invalidReq.failure).traceId)
    }

    // -------------------------------------------------------------------------
    // 2. Thin WebSocket v2 framing
    // -------------------------------------------------------------------------

    @Test
    fun streamClientUsesBearerHeaderNeverQueryParamAndEnforcesV2FramingOrder() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        val connector =
            RaftWebSocketConnector { url, headers ->
                capturedUrl = url
                capturedHeaders = headers
                wsSession
            }
        val streamClient = VoiceDictationV2StreamClient(connector)
        val url = "wss://live.holt.botiverse.build/v1/stream"
        val permit = "hv2.k1.body.sig"

        val connection = streamClient.connect(url = url, permit = permit)
        assertEquals(url, capturedUrl)
        assertFalse(capturedUrl!!.contains("permit="), "native clients must NEVER put permit in query string")
        assertEquals(mapOf("Authorization" to "Bearer $permit"), capturedHeaders)

        // Binary audio and stop before start are refused without sending frames on the wire
        assertFalse(connection.sendAudio(byteArrayOf(1, 2, 3, 4)))
        assertEquals(0, wsSession.sentBinary.size)
        assertEquals(0, wsSession.sentText.size)

        // Out-of-range sample rates fail fast
        assertFailsWith<IllegalArgumentException> { connection.sendStart(7_999) }
        assertFailsWith<IllegalArgumentException> { connection.sendStart(48_001) }

        // Strict v2 start frame contains ONLY deck, v, sample_rate (zero forbidden fields)
        assertTrue(connection.sendStart(16_000))
        assertFalse(connection.sendStart(16_000), "duplicate start must be ignored")
        assertEquals(1, wsSession.sentText.size)
        val startObj = Json.parseToJsonElement(wsSession.sentText.single()).jsonObject
        assertEquals(setOf("deck", "v", "sample_rate"), startObj.keys)
        assertEquals("start", startObj["deck"]?.jsonPrimitive?.content)
        assertEquals(2, startObj["v"]?.jsonPrimitive?.int)
        assertEquals(16_000, startObj["sample_rate"]?.jsonPrimitive?.int)
        for (forbidden in listOf("permit", "context", "hotwords", "language", "refine")) {
            assertFalse(forbidden in startObj, "v2 start must never include $forbidden")
        }

        // Binary PCM audio allowed strictly after start and before stop
        val pcm1 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertTrue(connection.sendAudio(pcm1))
        assertEquals(1, wsSession.sentBinary.size)
        assertContentEquals(pcm1, wsSession.sentBinary.single())

        // Stop frame sent once, after which binary audio is never sent
        assertTrue(connection.sendStop())
        assertFalse(connection.sendStop(), "duplicate stop must be ignored")
        assertFalse(connection.sendAudio(byteArrayOf(0x05, 0x06)))
        assertEquals(1, wsSession.sentBinary.size)
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000}""", """{"deck":"stop"}"""), wsSession.sentText.toList())

        connection.close()
    }

    @Test
    fun streamClientDecodesClosedSetV2ServerFramesClampsStableLenAndIgnoresUnknownFrames() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        val streamClient = VoiceDictationV2StreamClient { _, _ -> wsSession }
        val connection = streamClient.connect("wss://live.example.test/v1/stream", "hv2.k1.b.s")

        wsSession.pushText("""{"deck":"ready"}""")
        // Unknown / v1 / malformed frames must not crash or emit
        wsSession.pushText("not json at all")
        wsSession.pushText("""{"deck":"partial","text":"ignored v1"}""")
        wsSession.pushText("""{"deck":"final","text":"ignored v1"}""")
        wsSession.pushText("""{"deck":"refined","scope":"session","text":"ignored v1"}""")
        wsSession.pushBinary(byteArrayOf(9, 9))
        // Valid v2 text frames with absent, negative, and oversized stable_len
        wsSession.pushText("""{"deck":"text","text":"hello"}""")
        wsSession.pushText("""{"deck":"text","text":"hello world","stable_len":-5}""")
        wsSession.pushText("""{"deck":"text","text":"hello world","stable_len":999,"refined":true}""")
        wsSession.pushText("""{"deck":"text","text":"hello world","stable_len":5,"refined":false}""")
        // All 6 known error codes + unknown code
        wsSession.pushText("""{"deck":"error","code":"invalid_permit","message":"revoked"}""")
        wsSession.pushText("""{"deck":"error","code":"session_limit_reached","message":"10m"}""")
        wsSession.pushText("""{"deck":"error","code":"quota_exceeded"}""")
        wsSession.pushText("""{"deck":"error","code":"busy"}""")
        wsSession.pushText("""{"deck":"error","code":"unavailable"}""")
        wsSession.pushText("""{"deck":"error","code":"protocol_error"}""")
        wsSession.pushText("""{"deck":"error","code":"future_code","message":"future"}""")
        wsSession.pushText("""{"deck":"done"}""")
        wsSession.closeFromServer()

        val events = connection.events.toList()
        assertEquals(
            listOf<VoiceDictationV2StreamEvent>(
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Ready),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Text(text = "hello", stableLen = 5, refined = false)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Text(text = "hello world", stableLen = 0, refined = false)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Text(text = "hello world", stableLen = 11, refined = true)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Text(text = "hello world", stableLen = 5, refined = false)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.InvalidPermit, "revoked")),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.SessionLimitReached, "10m")),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.QuotaExceeded, null)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.Busy, null)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.Unavailable, null)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.ProtocolError, null)),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Error(VoiceDictationV2ErrorCode.Unknown("future_code"), "future")),
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Done),
                VoiceDictationV2StreamEvent.Closed(unexpected = false),
            ),
            events,
        )

        // Unexpected close without done/error/local-close emits Closed(unexpected = true)
        val unexpectedWs = FakeDictationWebSocketSession()
        val unexpectedConn = VoiceDictationV2StreamClient { _, _ -> unexpectedWs }.connect("wss://live.example.test/v1/stream", "hv2.k1.b.s")
        unexpectedWs.pushText("""{"deck":"ready"}""")
        unexpectedWs.closeFromServer(RuntimeException("socket reset"))
        val unexpectedEvents = unexpectedConn.events.toList()
        assertEquals(
            listOf<VoiceDictationV2StreamEvent>(
                VoiceDictationV2StreamEvent.Frame(VoiceDictationV2ServerFrame.Ready),
                VoiceDictationV2StreamEvent.Closed(unexpected = true, causeMessage = "socket reset"),
            ),
            unexpectedEvents,
        )
    }

    // -------------------------------------------------------------------------
    // 3. Composer span helper
    // -------------------------------------------------------------------------

    @Test
    fun composerSpanHandlesMidTextLatinAndCjkBoundariesAndInitialSelection() {
        // Mid-text ASCII tokens get a single boundary space on each side that would fuse
        val latinMid = VoiceDictationComposerSpan(baseText = "hello.world", insertStart = 6, insertEnd = 6)
        val latinUpdate = assertNotNull(latinMid.applyText("new", stableLen = 3, refined = false))
        assertEquals("hello. new world", latinUpdate.composerText)
        assertEquals(6, latinUpdate.spanStart)
        assertEquals(7, latinUpdate.textStart)
        assertEquals(10, latinUpdate.textEnd)
        assertEquals(10, latinUpdate.caretOffset)
        assertEquals(11, latinUpdate.spanEnd)

        // Numeric punctuation (3.14) must not insert a space
        val numeric = VoiceDictationComposerSpan(baseText = "3.", insertStart = 2, insertEnd = 2)
        assertEquals("3.14", assertNotNull(numeric.applyText("14")).composerText)

        // Already-spaced boundary must not double space
        val alreadySpaced = VoiceDictationComposerSpan(baseText = "hello  world", insertStart = 6, insertEnd = 6)
        assertEquals("hello dear world", assertNotNull(alreadySpaced.applyText("dear")).composerText)

        // CJK boundaries never insert a space (CJK-CJK, CJK-Latin, Latin-CJK)
        val cjkMid = VoiceDictationComposerSpan(baseText = "你好世界", insertStart = 2, insertEnd = 2)
        assertEquals("你好今天世界", assertNotNull(cjkMid.applyText("今天")).composerText)
        assertEquals("你好Raft世界", assertNotNull(cjkMid.applyText("Raft")).composerText)

        val latinToCjk = VoiceDictationComposerSpan(baseText = "hello", insertStart = 5, insertEnd = 5)
        assertEquals("hello你好", assertNotNull(latinToCjk.applyText("你好")).composerText)

        // Empty initial text snapshot must not delete an initial non-empty selection
        val selected = VoiceDictationComposerSpan(baseText = "hello bad world", insertStart = 6, insertEnd = 9)
        assertNull(selected.applyText(""))
        assertEquals("hello good world", assertNotNull(selected.applyText("good")).composerText)
    }

    @Test
    fun composerSpanAppliesPostStopRefinedWhenUntouchedAndLatchesOnUserEdit() {
        val untouched = VoiceDictationComposerSpan(baseText = "Draft: ", insertStart = 7, insertEnd = 7)
        val live = assertNotNull(untouched.applyText("ship it friday", stableLen = 7, refined = false))
        assertEquals("Draft: ship it friday", live.composerText)
        assertEquals(14, live.stableEnd)

        untouched.markStopping()
        // Echoing our own programmatic write does NOT latch
        untouched.onUserEditedComposer("Draft: ship it friday")
        assertFalse(untouched.ignoreSubsequentText)

        // Final refined text arriving before done IS applied when untouched
        val refined = assertNotNull(untouched.applyText("Ship it Friday.", stableLen = 15, refined = true))
        assertEquals("Draft: Ship it Friday.", refined.composerText)
        assertTrue(refined.refined)
        assertEquals(15, refined.stableLen)

        // Once user edits after stop, ALL subsequent text frames are permanently ignored
        val edited = VoiceDictationComposerSpan(baseText = "", insertStart = 0, insertEnd = 0)
        assertEquals("hello world", assertNotNull(edited.applyText("hello world")).composerText)
        edited.markStopping()
        edited.onUserEditedComposer("hello world — edited by me")
        assertTrue(edited.ignoreSubsequentText)
        assertNull(edited.applyText("Hello, world.", stableLen = 13, refined = true))
        assertNull(edited.applyText("Hello, world!!", stableLen = 14, refined = false))
        assertEquals("hello world", edited.lastText)
    }

    // -------------------------------------------------------------------------
    // 4. Controller lifecycle, refusals, races, owner switch, drain timeout
    // -------------------------------------------------------------------------

    @Test
    fun controllerHappyPathStreamsAudioAppliesRefinedTextAndReturnsIdleOnDone() = runTest {
        val permitApi = FakeDictationPermitApi(defaultPermitOutcome(quotaDegraded = true))
        val wsSession = FakeDictationWebSocketSession()
        val recorder = FakeDictationAudioRecorder()
        val updates = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = permitApi,
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(
            serverId = "srv-1",
            channelId = validChannelUuid,
            language = "en-US",
            baseText = "Note:",
            insertStart = 5,
            insertEnd = 5,
            onComposerUpdate = { updates += it },
        )
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Connecting, controller.state.value.phase)
        assertEquals(1, recorder.startCalls)
        assertEquals(1, permitApi.calls.size)
        assertEquals(true, controller.state.value.quotaDegraded)
        assertEquals(600, controller.state.value.maxDurationSeconds)
        assertEquals(7080, controller.state.value.quota?.remainingSeconds)
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000}"""), wsSession.sentText.toList())

        // Pre-ready audio chunk is buffered and not sent before ready
        val preChunk = byteArrayOf(1, 2)
        recorder.emitChunk(preChunk)
        recorder.emitLevel(0.75f)
        runCurrent()
        assertEquals(0, wsSession.sentBinary.size)
        assertEquals(0.75f, controller.state.value.audioLevel)

        // Ready transitions to Listening and flushes pre-roll + live PCM chunks
        wsSession.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
        assertEquals(1, wsSession.sentBinary.size)
        assertContentEquals(preChunk, wsSession.sentBinary[0])

        val liveChunk = byteArrayOf(3, 4, 5, 6)
        recorder.emitChunk(liveChunk)
        runCurrent()
        assertEquals(2, wsSession.sentBinary.size)
        assertContentEquals(liveChunk, wsSession.sentBinary[1])

        // Streaming text frame updates composer with boundary space
        wsSession.pushText("""{"deck":"text","text":"ship it","stable_len":4}""")
        runCurrent()
        assertEquals(1, updates.size)
        assertEquals("Note: ship it", updates.last().composerText)
        assertFalse(updates.last().refined)

        // Stop -> Stopping, recorder stopped, stop frame sent, post-stop refined text applied, done -> Idle
        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Stopping, controller.state.value.phase)
        assertEquals(0f, controller.state.value.audioLevel)
        assertEquals(1, recorder.stopCalls)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000}""", """{"deck":"stop"}"""),
            wsSession.sentText.toList(),
        )

        // Late chunk after stop is ignored
        recorder.emitChunk(byteArrayOf(7, 8))
        runCurrent()
        assertEquals(2, wsSession.sentBinary.size)

        wsSession.pushText("""{"deck":"text","text":"Ship it.","stable_len":8,"refined":true}""")
        runCurrent()
        assertEquals(2, updates.size)
        assertEquals("Note: Ship it.", updates.last().composerText)
        assertTrue(updates.last().refined)

        wsSession.pushText("""{"deck":"done"}""")
        wsSession.closeFromServer()
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.error)
        assertEquals(1, wsSession.closeCalls)
    }

    @Test
    fun controllerIgnoresPostStopTextFramesAfterUserEditsComposer() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        val recorder = FakeDictationAudioRecorder()
        val updates = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates += it })
        runCurrent()
        wsSession.pushText("""{"deck":"ready"}""")
        wsSession.pushText("""{"deck":"text","text":"raw draft"}""")
        runCurrent()
        assertEquals(listOf("raw draft"), updates.map { it.composerText })

        controller.stop()
        runCurrent()
        controller.onUserEditedComposer("raw draft + user edit")

        wsSession.pushText("""{"deck":"text","text":"Refined draft.","refined":true}""")
        wsSession.pushText("""{"deck":"done"}""")
        wsSession.closeFromServer()
        runCurrent()

        assertEquals(listOf("raw draft"), updates.map { it.composerText })
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.error)
    }

    @Test
    fun controllerPermissionDeniedNeverRequestsPermitOrOpensWebSocket() = runTest {
        val permitApi = FakeDictationPermitApi(defaultPermitOutcome())
        var wsConnectCalls = 0
        val recorder = FakeDictationAudioRecorder(startResult = VoiceDictationAudioStartResult.PermissionDenied)
        val controller =
            VoiceDictationV2Controller(
                permitApi = permitApi,
                streamConnector = VoiceDictationV2StreamClient { _, _ ->
                    wsConnectCalls += 1
                    FakeDictationWebSocketSession()
                },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(VoiceDictationV2Error.PermissionDenied, controller.state.value.error)
        assertEquals(0, permitApi.calls.size)
        assertEquals(0, wsConnectCalls)
        assertEquals(0, recorder.stopCalls)
    }

    @Test
    fun controllerHandlesQuotaExceededAndUnavailableRefusalsAndStopsRecorder() = runTest {
        var wsConnectCalls = 0
        val recorder = FakeDictationAudioRecorder()
        val quotaFailure =
            VoiceDictationV2PermitFailure.QuotaExceeded(
                usedSeconds = 7200,
                limitSeconds = 7200,
                resetsAtIso = "2026-11-27T00:00:00.000Z",
                retryAfterSeconds = 1200,
                traceId = "trace-quota",
            )
        val permitApi = FakeDictationPermitApi(VoiceDictationV2PermitOutcome.Refused(quotaFailure))
        val controller =
            VoiceDictationV2Controller(
                permitApi = permitApi,
                streamConnector = VoiceDictationV2StreamClient { _, _ ->
                    wsConnectCalls += 1
                    FakeDictationWebSocketSession()
                },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(VoiceDictationV2Error.PermitRefused(quotaFailure), controller.state.value.error)
        assertEquals(0, wsConnectCalls)
        assertEquals(1, recorder.stopCalls)

        val unavailableFailure = VoiceDictationV2PermitFailure.Unavailable(traceId = "trace-503")
        permitApi.nextOutcome = VoiceDictationV2PermitOutcome.Refused(unavailableFailure)
        controller.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(VoiceDictationV2Error.PermitRefused(unavailableFailure), controller.state.value.error)
        assertEquals(0, wsConnectCalls)
        assertEquals(2, recorder.stopCalls)
    }

    @Test
    fun controllerMidFlightCancelDuringPermitFetchAndWsConnectCleansUpRecorderAndSocket() = runTest {
        // 1) Cancel while POST /permits is in flight
        val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = permitGate)
        var wsConnectCalls = 0
        val recorder1 = FakeDictationAudioRecorder()
        val updates1 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller1 =
            VoiceDictationV2Controller(
                permitApi = permitApi,
                streamConnector = VoiceDictationV2StreamClient { _, _ ->
                    wsConnectCalls += 1
                    FakeDictationWebSocketSession()
                },
                scope = backgroundScope,
                audioRecorderProvider = { recorder1 },
            )

        controller1.start(serverId = "srv-1", onComposerUpdate = { updates1 += it })
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Connecting, controller1.state.value.phase)
        assertEquals(1, recorder1.startCalls)

        controller1.cancel()
        runCurrent()
        permitGate.complete(defaultPermitOutcome())
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller1.state.value.phase)
        assertNull(controller1.state.value.error)
        assertEquals(1, recorder1.stopCalls)
        assertEquals(0, wsConnectCalls)
        assertTrue(updates1.isEmpty())

        // 2a) Cancel while WebSocket connect is suspended in flight
        val wsGate = CompletableDeferred<RaftWebSocketSession>()
        val recorder2 = FakeDictationAudioRecorder()
        val updates2 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller2 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsGate.await() },
                scope = backgroundScope,
                audioRecorderProvider = { recorder2 },
            )

        controller2.start(serverId = "srv-1", onComposerUpdate = { updates2 += it })
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Connecting, controller2.state.value.phase)

        controller2.cancel()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller2.state.value.phase)
        assertEquals(1, recorder2.stopCalls)
        assertTrue(updates2.isEmpty())

        // 2b) Race where cancel() wins just as WebSocket connect returns a live socket
        val lateSocket = FakeDictationWebSocketSession()
        val recorder2b = FakeDictationAudioRecorder()
        var controller2b: VoiceDictationV2Controller? = null
        controller2b =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector =
                    VoiceDictationV2StreamClient { _, _ ->
                        controller2b?.cancel()
                        lateSocket
                    },
                scope = backgroundScope,
                audioRecorderProvider = { recorder2b },
            )
        controller2b.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller2b.state.value.phase)
        assertEquals(1, recorder2b.stopCalls)
        assertEquals(1, lateSocket.closeCalls, "socket returned after concurrent cancel must be closed immediately")
        assertTrue(lateSocket.sentText.isEmpty(), "must not send start frame on an already-cancelled session")

        // 3) Cancel while AudioRecorder.start() is suspended (e.g. OS permission prompt)
        val recorderGate = CompletableDeferred<VoiceDictationAudioStartResult>()
        val recorder3 = FakeDictationAudioRecorder(startGate = recorderGate)
        val permitApi3 = FakeDictationPermitApi(defaultPermitOutcome())
        val controller3 =
            VoiceDictationV2Controller(
                permitApi = permitApi3,
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { recorder3 },
            )
        controller3.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Connecting, controller3.state.value.phase)
        assertEquals(1, recorder3.startCalls)

        controller3.cancel()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller3.state.value.phase)
        assertEquals(1, recorder3.stopCalls)
        assertEquals(0, permitApi3.calls.size)
    }

    @Test
    fun audioRecorderRegistryTracksSupportAndUnregistersCleanly() {
        assertFalse(RaftVoiceDictationAudioRecorderRegistry.isSupported())
        val recorder = FakeDictationAudioRecorder()
        RaftVoiceDictationAudioRecorderRegistry.register(recorder)
        assertTrue(RaftVoiceDictationAudioRecorderRegistry.isSupported())
        assertEquals(recorder, RaftVoiceDictationAudioRecorderRegistry.currentOrNull())

        RaftVoiceDictationAudioRecorderRegistry.unregister()
        assertFalse(RaftVoiceDictationAudioRecorderRegistry.isSupported())
        assertNull(RaftVoiceDictationAudioRecorderRegistry.currentOrNull())
    }

    @Test
    fun controllerServerOrChannelChangeAbortsCleanlyWithoutTouchingNewComposer() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        val recorder = FakeDictationAudioRecorder()
        val oldComposerUpdates = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(
            serverId = "srv-1",
            channelId = validChannelUuid,
            baseText = "old",
            onComposerUpdate = { oldComposerUpdates += it },
        )
        runCurrent()
        wsSession.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)

        // Switching channel aborts immediately, closes recorder & socket, and ignores any late frame
        controller.onOwnerChanged(serverId = "srv-1", channelId = "660e8400-e29b-41d4-a716-446655440000")
        wsSession.pushText("""{"deck":"text","text":"stale speech"}""")
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, wsSession.closeCalls)
        assertTrue(oldComposerUpdates.isEmpty())
        assertEquals("660e8400-e29b-41d4-a716-446655440000", controller.state.value.owner?.channelId)

        // Also test server switch during in-flight permit fetch
        val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val recorder2 = FakeDictationAudioRecorder()
        val controller2 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = permitGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { recorder2 },
            )
        controller2.start(serverId = "srv-a", channelId = validChannelUuid, onComposerUpdate = {})
        runCurrent()
        controller2.onOwnerChanged(serverId = "srv-b", channelId = validChannelUuid)
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller2.state.value.phase)
        assertEquals(1, recorder2.stopCalls)
        assertEquals("srv-b", controller2.state.value.owner?.serverId)
    }

    @Test
    fun controllerStopDrainTimeoutFallsBackCleanlyToIdleKeepingText() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        val recorder = FakeDictationAudioRecorder()
        val updates = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
                stopDrainTimeoutMs = 5_000L,
            )

        controller.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates += it })
        runCurrent()
        wsSession.pushText("""{"deck":"ready"}""")
        wsSession.pushText("""{"deck":"text","text":"kept transcript"}""")
        runCurrent()
        assertEquals(listOf("kept transcript"), updates.map { it.composerText })

        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Stopping, controller.state.value.phase)

        // Before timeout: still draining
        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Stopping, controller.state.value.phase)

        // At timeout: falls back cleanly to Idle, closes WS, keeps already-landed text with no error
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.error)
        assertEquals("kept transcript", controller.state.value.lastUpdate?.composerText)
        assertEquals(1, wsSession.closeCalls)
    }

    @Test
    fun controllerServerErrorFramePreservesFlushedTextAndSurfacesTypedCode() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        val recorder = FakeDictationAudioRecorder()
        val updates = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates += it })
        runCurrent()
        wsSession.pushText("""{"deck":"ready"}""")
        wsSession.pushText("""{"deck":"text","text":"ten minute transcript","stable_len":21}""")
        wsSession.pushText("""{"deck":"error","code":"session_limit_reached","message":"Reached 10 min cap"}""")
        wsSession.closeFromServer()
        runCurrent()

        assertEquals(listOf("ten minute transcript"), updates.map { it.composerText })
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(
            VoiceDictationV2Error.StreamError(
                code = VoiceDictationV2ErrorCode.SessionLimitReached,
                message = "Reached 10 min cap",
            ),
            controller.state.value.error,
        )
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, wsSession.closeCalls)
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private fun defaultPermitOutcome(quotaDegraded: Boolean = false): VoiceDictationV2PermitOutcome =
        VoiceDictationV2PermitOutcome.Issued(
            permit =
                VoiceDictationV2Permit(
                    permit = "hv2.k1.payload.sig",
                    url = "wss://live.holt.botiverse.build/v1/stream",
                    expiresAtIso = "2026-11-26T20:01:00.000Z",
                    expiresAtEpochMs = 1_795_723_260_000L,
                    maxDurationSeconds = 600,
                    quota =
                        VoiceDictationV2Quota(
                            usedSeconds = 120,
                            limitSeconds = 7200,
                            remainingSeconds = 7080,
                            resetsAtIso = "2026-11-27T00:00:00.000Z",
                        ),
                    quotaDegraded = quotaDegraded,
                ),
        )

    private class FakeDictationNetworkClient : RaftNetworkClient {
        var lastPath: String? = null
        var lastMethod: String? = null
        var lastBodyText: String? = null
        var lastServerIdOverride: ServerId? = null
        var nextResponse: HomeNetworkResponse = HomeNetworkResponse(code = 201, bodyText = "{}")

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            lastPath = path
            lastMethod = method
            lastBodyText = bodyText
            lastServerIdOverride = serverIdOverride
            return nextResponse
        }
    }

    private class FakeDictationPermitApi(
        var nextOutcome: VoiceDictationV2PermitOutcome,
        private val gate: CompletableDeferred<VoiceDictationV2PermitOutcome>? = null,
    ) : VoiceDictationV2PermitApi {
        val calls = mutableListOf<Triple<String, String?, String?>>()

        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
        ): VoiceDictationV2PermitOutcome {
            calls += Triple(serverId, language, channelId)
            return gate?.await() ?: nextOutcome
        }
    }

    private class FakeDictationAudioRecorder(
        override val sampleRateHz: Int = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ,
        private val startResult: VoiceDictationAudioStartResult = VoiceDictationAudioStartResult.Started,
        private val startGate: CompletableDeferred<VoiceDictationAudioStartResult>? = null,
    ) : RaftVoiceDictationAudioRecorder {
        var startCalls: Int = 0
        var stopCalls: Int = 0
        private var chunkCallback: ((ByteArray) -> Unit)? = null
        private var levelCallback: ((Float) -> Unit)? = null

        override suspend fun start(
            onPcmChunk: (ByteArray) -> Unit,
            onAudioLevel: (Float) -> Unit,
        ): VoiceDictationAudioStartResult {
            startCalls += 1
            val resolved = startGate?.await() ?: startResult
            if (resolved is VoiceDictationAudioStartResult.Started) {
                chunkCallback = onPcmChunk
                levelCallback = onAudioLevel
            }
            return resolved
        }

        override suspend fun stop() {
            stopCalls += 1
            chunkCallback = null
            levelCallback = null
        }

        fun emitChunk(bytes: ByteArray) {
            chunkCallback?.invoke(bytes)
        }

        fun emitLevel(level: Float) {
            levelCallback?.invoke(level)
        }
    }

    private class FakeDictationWebSocketSession : RaftWebSocketSession {
        private val toClient = Channel<RaftWebSocketFrame>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<RaftWebSocketFrame> = toClient

        val sentText = mutableListOf<String>()
        val sentBinary = mutableListOf<ByteArray>()
        var closeCalls: Int = 0

        override suspend fun send(text: String) {
            sentText += text
        }

        override suspend fun sendBinary(bytes: ByteArray) {
            sentBinary += bytes.copyOf()
        }

        override suspend fun close(code: Int, reason: String) {
            closeCalls += 1
            toClient.close()
        }

        fun pushText(text: String) {
            toClient.trySend(RaftWebSocketFrame.Text(text))
        }

        fun pushBinary(bytes: ByteArray) {
            toClient.trySend(RaftWebSocketFrame.Binary(bytes))
        }

        fun closeFromServer(cause: Throwable? = null) {
            toClient.close(cause)
        }
    }
}
