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
import kotlinx.coroutines.async
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
import kotlinx.serialization.json.long

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

        // Caller-supplied draftBefore & contactHints (#wg-call:#10): included when non-empty,
        // omitted when null, blank, or all entries normalize away.
        val withHintsJson =
            buildVoiceDictationV2PermitRequestBody(
                language = "zh-CN",
                channelId = validChannelUuid,
                draftBefore = "  Prior\u200B draft\r\nline 2  ",
                contactHints = listOf("  Alice\nChen  ", "alice chen", "wg-call"),
            )
        val withHintsObj = Json.parseToJsonElement(withHintsJson).jsonObject
        assertEquals(setOf("v", "language", "channelId", "draftBefore", "contactHints"), withHintsObj.keys)
        assertEquals("Prior draft\nline 2", withHintsObj["draftBefore"]?.jsonPrimitive?.content)
        assertEquals(
            """["Alice Chen","wg-call"]""",
            withHintsObj["contactHints"]?.toString(),
        )

        val omittedEmptyJson =
            buildVoiceDictationV2PermitRequestBody(
                language = "en",
                draftBefore = "   \u0000\u200B  ",
                contactHints = listOf("", "   ", "x".repeat(65)),
            )
        assertEquals("""{"v":2,"language":"en"}""", omittedEmptyJson)
        assertFalse(hasVoiceDictationPermitCallerContext(draftBefore = "   ", contactHints = emptyList()))
        assertTrue(hasVoiceDictationPermitCallerContext(draftBefore = "Hi"))
        assertTrue(hasVoiceDictationPermitCallerContext(contactHints = listOf("Alice")))
    }

    @Test
    fun permitApiRetriesOnceStrippedOn400InvalidRequestAndDoesNotRetryOtherRefusals() = runTest {
        // 1. 400 invalid_request when carrying draftBefore/contactHints -> retries ONCE stripped -> 201 Issued.
        val client = FakeDictationNetworkClient()
        client.responseProvider = { attempt, _ ->
            if (attempt == 1) {
                HomeNetworkResponse(
                    code = 400,
                    bodyText = null,
                    errorBodyText = """{"code":"invalid_request","error":"Unrecognized keys"}""",
                )
            } else {
                HomeNetworkResponse(code = 201, bodyText = validPermitJson)
            }
        }
        val api = RaftVoiceDictationV2PermitApi(client)
        val recovered =
            api.requestPermit(
                serverId = "srv-1",
                language = "zh-CN",
                channelId = validChannelUuid,
                draftBefore = "Prior draft",
                contactHints = listOf("Tenny"),
            )
        assertIs<VoiceDictationV2PermitOutcome.Issued>(recovered)
        assertEquals(2, client.sentBodies.size)
        val firstObj = Json.parseToJsonElement(assertNotNull(client.sentBodies[0])).jsonObject
        assertEquals("Prior draft", firstObj["draftBefore"]?.jsonPrimitive?.content)
        assertEquals("""["Tenny"]""", firstObj["contactHints"]?.toString())
        assertEquals(
            """{"v":2,"language":"zh","channelId":"$validChannelUuid"}""",
            client.sentBodies[1],
        )

        // 2. Persistent 400 stops after 2 attempts (never loops) and returns InvalidRequest.
        val persistentClient = FakeDictationNetworkClient()
        persistentClient.nextResponse =
            HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = """{"code":"invalid_request","error":"Bad request"}""",
            )
        val persistentOutcome =
            RaftVoiceDictationV2PermitApi(persistentClient).requestPermit(
                serverId = "srv-1",
                language = "en",
                channelId = null,
                draftBefore = "Draft",
                contactHints = listOf("Kate"),
            )
        assertIs<VoiceDictationV2PermitOutcome.Refused>(persistentOutcome)
        assertIs<VoiceDictationV2PermitFailure.InvalidRequest>(persistentOutcome.failure)
        assertEquals(2, persistentClient.sentBodies.size)

        // 3. 400 WITHOUT caller context (or with blank/empty context) does NOT retry.
        val bareClient = FakeDictationNetworkClient()
        bareClient.nextResponse =
            HomeNetworkResponse(
                code = 400,
                bodyText = null,
                errorBodyText = """{"code":"invalid_request"}""",
            )
        val bareOutcome =
            RaftVoiceDictationV2PermitApi(bareClient).requestPermit(
                serverId = "srv-1",
                language = "en",
                channelId = null,
                draftBefore = "   ",
                contactHints = emptyList(),
            )
        assertIs<VoiceDictationV2PermitOutcome.Refused>(bareOutcome)
        assertEquals(1, bareClient.sentBodies.size)

        // 4. Non-400 refusals (404, 429, 503) do NOT retry even when caller context is present.
        for (code in listOf(404, 429, 503)) {
            val non400Client = FakeDictationNetworkClient()
            non400Client.nextResponse = HomeNetworkResponse(code = code, bodyText = null, errorBodyText = "{}")
            val refused =
                RaftVoiceDictationV2PermitApi(non400Client).requestPermit(
                    serverId = "srv-1",
                    language = "en",
                    channelId = null,
                    draftBefore = "Draft",
                    contactHints = listOf("Kate"),
                )
            assertIs<VoiceDictationV2PermitOutcome.Refused>(refused)
            assertEquals(1, non400Client.sentBodies.size, "status $code must not trigger 400 fallback retry")
        }
    }

    @Test
    fun controllerForwardsDraftBeforeAndContactHintsKeepsDraftReadOnlyAndOmitsFromWsStartFrame() = runTest {
        val permit = assertNotNull(parseVoiceDictationV2Permit(validPermitJson))
        val permitApi = FakeDictationPermitApi(VoiceDictationV2PermitOutcome.Issued(permit))
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

        val existingDraft = "Meet with Alice tomorrow"
        val caret = 15 // right after "Meet with Alice"
        controller.start(
            serverId = "srv-1",
            channelId = validChannelUuid,
            language = "en-US",
            baseText = existingDraft,
            insertStart = caret,
            insertEnd = caret,
            draftBefore = extractVoiceDictationDraftBeforeCaret(existingDraft, caret),
            contactHints = listOf("Alice Chen", "wg-call"),
            onComposerUpdate = { updates += it },
        )
        runCurrent()

        val recordedCall = permitApi.contextCalls.single()
        assertEquals("srv-1", recordedCall.serverId)
        assertEquals("en-US", recordedCall.language)
        assertEquals(validChannelUuid, recordedCall.channelId)
        assertEquals("Meet with Alice", recordedCall.draftBefore)
        assertEquals(listOf("Alice Chen", "wg-call"), recordedCall.contactHints)

        // WS start frame stays strictly v2 minimal with zero caller context fields (and mode: "tap").
        val startObj = Json.parseToJsonElement(wsSession.sentText.single()).jsonObject
        assertEquals(setOf("deck", "v", "sample_rate", "mode"), startObj.keys)
        assertEquals("tap", startObj["mode"]?.jsonPrimitive?.content)
        assertFalse("draftBefore" in startObj)
        assertFalse("draft_before" in startObj)
        assertFalse("contactHints" in startObj)
        assertFalse("contact_hints" in startObj)

        // Read-only guarantee: spoken text inserts around the caret without mutating pre-existing prefix/suffix.
        wsSession.pushText("""{"deck":"ready"}""")
        wsSession.pushText("""{"deck":"text","text":"at noon","stable_len":7,"refined":true}""")
        runCurrent()
        assertEquals("Meet with Alice at noon tomorrow", updates.last().composerText)
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

        client.nextResponse = HomeNetworkResponse(code = 200, bodyText = validPermitJson)
        assertIs<VoiceDictationV2PermitOutcome.Issued>(api.requestPermit(serverId = "srv-1"))

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

        client.nextResponse =
            HomeNetworkResponse(
                code = 429,
                bodyText = null,
                errorBodyText = """{"code":"dictation_rate_limited","error":"Slow down."}""",
                responseHeaders = traceHeaders + ("Retry-After" to listOf("42")),
            )
        val rateLimitedHeader = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        assertEquals(42, assertIs<VoiceDictationV2PermitFailure.RateLimited>(rateLimitedHeader.failure).retryAfterSeconds)

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

        client.nextResponse =
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                errorBodyText = """{"code":"voice_dictation_unavailable","error":"Voice dictation isn't available right now."}""",
                responseHeaders = traceHeaders,
            )
        val unavailable = assertIs<VoiceDictationV2PermitOutcome.Refused>(api.requestPermit("srv-1"))
        assertEquals(traceId, assertIs<VoiceDictationV2PermitFailure.Unavailable>(unavailable.failure).traceId)

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

        assertFalse(connection.sendAudio(byteArrayOf(1, 2, 3, 4)))
        assertEquals(0, wsSession.sentBinary.size)
        assertEquals(0, wsSession.sentText.size)

        assertFailsWith<IllegalArgumentException> { connection.sendStart(7_999) }
        assertFailsWith<IllegalArgumentException> { connection.sendStart(48_001) }

        assertTrue(connection.sendStart(16_000))
        assertFalse(connection.sendStart(16_000), "duplicate start must be ignored")
        assertEquals(1, wsSession.sentText.size)
        val startObj = Json.parseToJsonElement(wsSession.sentText.single()).jsonObject
        assertEquals(setOf("deck", "v", "sample_rate", "mode"), startObj.keys)
        assertEquals("start", startObj["deck"]?.jsonPrimitive?.content)
        assertEquals(2, startObj["v"]?.jsonPrimitive?.int)
        assertEquals(16_000, startObj["sample_rate"]?.jsonPrimitive?.int)
        assertEquals("tap", startObj["mode"]?.jsonPrimitive?.content)
        for (forbidden in listOf("permit", "context", "hotwords", "language", "refine")) {
            assertFalse(forbidden in startObj, "v2 start must never include $forbidden")
        }

        val pcm1 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertTrue(connection.sendAudio(pcm1))
        assertEquals(1, wsSession.sentBinary.size)
        assertContentEquals(pcm1, wsSession.sentBinary.single())

        assertTrue(connection.sendStop())
        assertFalse(connection.sendStop(), "duplicate stop must be ignored")
        assertFalse(connection.sendAudio(byteArrayOf(0x05, 0x06)))
        assertEquals(1, wsSession.sentBinary.size)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            wsSession.sentText.toList(),
        )

        connection.close()

        // Explicit Hold mode encodes `mode: "hold"` on v2 start frame.
        val holdWs = FakeDictationWebSocketSession()
        val holdConn = VoiceDictationV2StreamClient { _, _ -> holdWs }.connect(url = url, permit = permit)
        assertTrue(holdConn.sendStart(16_000, VoiceDictationSessionMode.Hold))
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"hold"}"""),
            holdWs.sentText.toList(),
        )
        holdConn.close()
    }

    @Test
    fun streamClientDecodesClosedSetV2ServerFramesClampsStableLenAndIgnoresUnknownFrames() = runTest {
        val wsSession = FakeDictationWebSocketSession()
        val streamClient = VoiceDictationV2StreamClient { _, _ -> wsSession }
        val connection = streamClient.connect("wss://live.example.test/v1/stream", "hv2.k1.b.s")

        wsSession.pushText("""{"deck":"ready"}""")
        wsSession.pushText("not json at all")
        wsSession.pushText("""{"deck":"partial","text":"ignored v1"}""")
        wsSession.pushText("""{"deck":"final","text":"ignored v1"}""")
        wsSession.pushText("""{"deck":"refined","scope":"session","text":"ignored v1"}""")
        wsSession.pushBinary(byteArrayOf(9, 9))
        wsSession.pushText("""{"deck":"text","text":"hello"}""")
        wsSession.pushText("""{"deck":"text","text":"hello world","stable_len":-5}""")
        wsSession.pushText("""{"deck":"text","text":"hello world","stable_len":999,"refined":true}""")
        wsSession.pushText("""{"deck":"text","text":"hello world","stable_len":5,"refined":false}""")
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
        val latinMid = VoiceDictationComposerSpan(baseText = "hello.world", insertStart = 6, insertEnd = 6)
        val latinUpdate = assertNotNull(latinMid.applyText("new", stableLen = 3, refined = false))
        assertEquals("hello. new world", latinUpdate.composerText)
        assertEquals(6, latinUpdate.spanStart)
        assertEquals(7, latinUpdate.textStart)
        assertEquals(10, latinUpdate.textEnd)
        assertEquals(10, latinUpdate.caretOffset)
        assertEquals(11, latinUpdate.spanEnd)

        val numeric = VoiceDictationComposerSpan(baseText = "3.", insertStart = 2, insertEnd = 2)
        assertEquals("3.14", assertNotNull(numeric.applyText("14")).composerText)

        val alreadySpaced = VoiceDictationComposerSpan(baseText = "hello  world", insertStart = 6, insertEnd = 6)
        assertEquals("hello dear world", assertNotNull(alreadySpaced.applyText("dear")).composerText)

        val cjkMid = VoiceDictationComposerSpan(baseText = "你好世界", insertStart = 2, insertEnd = 2)
        assertEquals("你好今天世界", assertNotNull(cjkMid.applyText("今天")).composerText)
        assertEquals("你好Raft世界", assertNotNull(cjkMid.applyText("Raft")).composerText)

        val latinToCjk = VoiceDictationComposerSpan(baseText = "hello", insertStart = 5, insertEnd = 5)
        assertEquals("hello你好", assertNotNull(latinToCjk.applyText("你好")).composerText)

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
        untouched.onUserEditedComposer("Draft: ship it friday")
        assertFalse(untouched.ignoreSubsequentText)

        val refined = assertNotNull(untouched.applyText("Ship it Friday.", stableLen = 15, refined = true))
        assertEquals("Draft: Ship it Friday.", refined.composerText)
        assertTrue(refined.refined)
        assertEquals(15, refined.stableLen)

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
    // 4. Zero-wait start, 10 s pre-ready buffer, non-blocking stop, submit wait
    // -------------------------------------------------------------------------

    @Test
    fun controllerZeroWaitTransitionsConnectingToListeningImmediatelyOnMicStartBeforeReady() = runTest {
        val recorderGate = CompletableDeferred<VoiceDictationAudioStartResult>()
        val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val recorder = FakeDictationAudioRecorder(startGate = recorderGate)
        val wsSession = FakeDictationWebSocketSession()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = permitGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        // While mic acquisition is still pending, foreground is Connecting with flat audioLevel = 0.
        assertEquals(VoiceDictationV2Phase.Connecting, controller.state.value.phase)
        assertEquals(0f, controller.state.value.audioLevel)

        // The instant recorder.start() succeeds, transition immediately to Listening before permit/WS/ready.
        recorderGate.complete(VoiceDictationAudioStartResult.Started)
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)

        // Audio level updates immediately drive the halo while permit is still in flight.
        recorder.emitLevel(0.62f)
        assertEquals(0.62f, controller.state.value.audioLevel)

        // Complete permit and ready cleanly.
        permitGate.complete(defaultPermitOutcome())
        runCurrent()
        wsSession.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)

        controller.cancel()
        runCurrent()
    }

    @Test
    fun controllerPreReadyBufferFlushesUpTo100ChunksInFifoOrderAndFailsClosedOn101st() = runTest {
        // Part A: <= 100 chunks buffered before ready flush in strict FIFO order on ready.
        val ws1 = FakeDictationWebSocketSession()
        val rec1 = FakeDictationAudioRecorder()
        val controller1 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws1 },
                scope = backgroundScope,
                audioRecorderProvider = { rec1 },
            )

        controller1.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller1.state.value.phase)

        for (i in 0 until VOICE_DICTATION_MAX_PRE_READY_CHUNKS) {
            rec1.emitChunk(byteArrayOf(i.toByte(), (i + 1).toByte()))
        }
        runCurrent()
        assertEquals(0, ws1.sentBinary.size, "no audio may be sent before server ready")
        assertEquals(VoiceDictationV2Phase.Listening, controller1.state.value.phase)

        ws1.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(VOICE_DICTATION_MAX_PRE_READY_CHUNKS, ws1.sentBinary.size)
        for (i in 0 until VOICE_DICTATION_MAX_PRE_READY_CHUNKS) {
            assertContentEquals(byteArrayOf(i.toByte(), (i + 1).toByte()), ws1.sentBinary[i])
        }

        // Live chunk after ready appends cleanly in order.
        val liveChunk = byteArrayOf(120, 121)
        rec1.emitChunk(liveChunk)
        runCurrent()
        assertEquals(VOICE_DICTATION_MAX_PRE_READY_CHUNKS + 1, ws1.sentBinary.size)
        assertContentEquals(liveChunk, ws1.sentBinary.last())
        controller1.cancel()
        runCurrent()

        // Part B: 101st chunk before ready fails closed instead of silently dropping opening words.
        val ws2 = FakeDictationWebSocketSession()
        val rec2 = FakeDictationAudioRecorder()
        val controller2 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws2 },
                scope = backgroundScope,
                audioRecorderProvider = { rec2 },
            )

        controller2.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        for (i in 0 until VOICE_DICTATION_MAX_PRE_READY_CHUNKS) {
            rec2.emitChunk(byteArrayOf(i.toByte()))
        }
        assertEquals(VoiceDictationV2Phase.Listening, controller2.state.value.phase)
        assertNull(controller2.state.value.error)

        // 101st chunk triggers fail-closed ConnectionLost and stops recorder + closes socket.
        rec2.emitChunk(byteArrayOf(101))
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller2.state.value.phase)
        assertIs<VoiceDictationV2Error.ConnectionLost>(controller2.state.value.error)
        assertEquals(1, rec2.stopCalls)
        assertEquals(1, ws2.closeCalls)
        assertEquals(0, ws2.sentBinary.size)
    }

    @Test
    fun controllerHappyPathStreamsAudioAppliesBackgroundRefinedTextAndSettlesOnDone() = runTest {
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

        // Zero-wait: immediately Listening once recorder.start() succeeds, before ready arrives.
        assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
        assertEquals(1, recorder.startCalls)
        assertEquals(1, permitApi.calls.size)
        assertEquals(true, controller.state.value.quotaDegraded)
        assertEquals(600, controller.state.value.maxDurationSeconds)
        assertEquals(7080, controller.state.value.quota?.remainingSeconds)
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""), wsSession.sentText.toList())

        val preChunk = byteArrayOf(1, 2)
        recorder.emitChunk(preChunk)
        recorder.emitLevel(0.75f)
        runCurrent()
        assertEquals(0, wsSession.sentBinary.size)
        assertEquals(0.75f, controller.state.value.audioLevel)

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

        wsSession.pushText("""{"deck":"text","text":"ship it","stable_len":4}""")
        runCurrent()
        assertEquals(1, updates.size)
        assertEquals("Note: ship it", updates.last().composerText)
        assertFalse(updates.last().refined)

        // Non-blocking stop: foreground IMMEDIATELY returns to Idle while post-roll + background drain run.
        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertTrue(controller.state.value.hasBackgroundDrain)
        assertEquals(0f, controller.state.value.audioLevel)
        assertEquals(0, recorder.stopCalls)
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""), wsSession.sentText.toList())

        // Once the default 300 ms post-roll completes, recorder stops and `{"deck":"stop"}` is dispatched.
        advanceTimeBy(VOICE_DICTATION_DEFAULT_STOP_POST_ROLL_MS)
        runCurrent()
        assertEquals(1, recorder.stopCalls)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            wsSession.sentText.toList(),
        )

        // Late chunk after stop is ignored.
        recorder.emitLateChunkAfterStop(byteArrayOf(7, 8))
        runCurrent()
        assertEquals(2, wsSession.sentBinary.size)

        // Post-stop refined text still applies while untouched in background drain.
        wsSession.pushText("""{"deck":"text","text":"Ship it.","stable_len":8,"refined":true}""")
        runCurrent()
        assertEquals(2, updates.size)
        assertEquals("Note: Ship it.", updates.last().composerText)
        assertTrue(updates.last().refined)

        wsSession.pushText("""{"deck":"done"}""")
        wsSession.closeFromServer()
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertFalse(controller.state.value.hasBackgroundDrain)
        assertNull(controller.state.value.error)
        assertEquals(1, wsSession.closeCalls)
    }

    @Test
    fun controllerStopBeforeReadyFlushesPreReadyInBackgroundAndNewStartAbortsBackgroundDrain() = runTest {
        // Part A: stop while permit is still in flight still flushes pre-ready audio and delivers transcript.
        val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val ws1 = FakeDictationWebSocketSession()
        val rec1 = FakeDictationAudioRecorder()
        val updates1 = mutableListOf<VoiceDictationComposerUpdate>()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = permitGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws1 },
                scope = backgroundScope,
                audioRecorderProvider = { rec1 },
            )

        controller.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates1 += it })
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
        rec1.emitChunk(byteArrayOf(10))
        rec1.emitChunk(byteArrayOf(20))

        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertTrue(controller.state.value.hasBackgroundDrain)
        assertEquals(0, rec1.stopCalls)

        advanceTimeBy(VOICE_DICTATION_DEFAULT_STOP_POST_ROLL_MS)
        runCurrent()
        assertEquals(1, rec1.stopCalls)

        permitGate.complete(defaultPermitOutcome())
        runCurrent()
        ws1.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(2, ws1.sentBinary.size)
        assertContentEquals(byteArrayOf(10), ws1.sentBinary[0])
        assertContentEquals(byteArrayOf(20), ws1.sentBinary[1])
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            ws1.sentText.toList(),
        )

        ws1.pushText("""{"deck":"text","text":"Quick reply.","stable_len":12}""")
        ws1.pushText("""{"deck":"done"}""")
        runCurrent()
        assertEquals(listOf("Quick reply."), updates1.map { it.composerText })
        assertFalse(controller.state.value.hasBackgroundDrain)

        // Part B: starting a new session while a previous session is draining aborts the background session.
        val wsA = FakeDictationWebSocketSession()
        val wsB = FakeDictationWebSocketSession()
        var connectIndex = 0
        val rec2 = FakeDictationAudioRecorder()
        val updatesA = mutableListOf<VoiceDictationComposerUpdate>()
        val updatesB = mutableListOf<VoiceDictationComposerUpdate>()
        val controller2 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> if (connectIndex++ == 0) wsA else wsB },
                scope = backgroundScope,
                audioRecorderProvider = { rec2 },
            )

        controller2.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updatesA += it })
        runCurrent()
        wsA.pushText("""{"deck":"ready"}""")
        wsA.pushText("""{"deck":"text","text":"First"}""")
        runCurrent()
        controller2.stop()
        runCurrent()
        assertTrue(controller2.state.value.hasBackgroundDrain)

        // Immediately start session B before session A finishes draining.
        controller2.start(serverId = "srv-1", baseText = "First", onComposerUpdate = { updatesB += it })
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, controller2.state.value.phase)
        assertFalse(controller2.state.value.hasBackgroundDrain)
        assertEquals(1, wsA.closeCalls)

        // Late frame on aborted session A must be ignored.
        wsA.pushText("""{"deck":"text","text":"STALE A","refined":true}""")
        runCurrent()
        assertEquals(listOf("First"), updatesA.map { it.composerText })

        wsB.pushText("""{"deck":"ready"}""")
        wsB.pushText("""{"deck":"text","text":"second"}""")
        runCurrent()
        assertEquals(listOf("First second"), updatesB.map { it.composerText })
        controller2.cancel()
        runCurrent()
    }

    @Test
    fun controllerIgnoresPostStopBackgroundTextFramesAfterUserEditsComposer() = runTest {
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
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertTrue(controller.state.value.hasBackgroundDrain)
        controller.onUserEditedComposer("raw draft + user edit")

        wsSession.pushText("""{"deck":"text","text":"Refined draft.","refined":true}""")
        wsSession.pushText("""{"deck":"done"}""")
        wsSession.closeFromServer()
        runCurrent()

        assertEquals(listOf("raw draft"), updates.map { it.composerText })
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertFalse(controller.state.value.hasBackgroundDrain)
        assertNull(controller.state.value.error)
    }

    @Test
    fun controllerAwaitPendingFinalBeforeSendHandlesPostStopWysiwygAndLiveMicSplitRules() = runTest {
        // Case 0 (WYSIWYG): post-stop background refine (both during 300 ms post-roll and after
        // post-roll while refining) returns immediately without blocking Send, aborts refinement,
        // stops post-roll mic immediately, clears provisionalRange, and ignores late refine.
        val wsBg = FakeDictationWebSocketSession()
        val recBg = FakeDictationAudioRecorder()
        val updatesBg = mutableListOf<VoiceDictationComposerUpdate>()
        val controllerBg =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBg },
                scope = backgroundScope,
                audioRecorderProvider = { recBg },
            )
        controllerBg.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updatesBg += it })
        runCurrent()
        wsBg.pushText("""{"deck":"ready"}""")
        wsBg.pushText("""{"deck":"text","text":"Please ship","stable_len":0}""")
        runCurrent()
        assertEquals(VoiceDictationTextRange(0, 11), controllerBg.state.value.provisionalRange)

        controllerBg.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controllerBg.state.value.phase)
        assertTrue(controllerBg.state.value.hasBackgroundDrain)
        assertEquals(VoiceDictationTextRange(0, 11), controllerBg.state.value.provisionalRange)
        assertFalse(controllerBg.needsSubmitFinalWait(), "already-stopped submit must never block (WYSIWYG)")
        assertTrue(controllerBg.awaitPendingFinalBeforeSend(), "direct await when already stopped must return true immediately")
        assertFalse(controllerBg.state.value.awaitingSubmitFinal)

        controllerBg.finishForSubmit()
        runCurrent()
        assertNull(controllerBg.state.value.provisionalRange)
        assertFalse(controllerBg.state.value.hasBackgroundDrain)
        assertEquals(1, recBg.stopCalls, "post-roll mic must stop immediately on WYSIWYG submit")
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_SUBMIT), wsBg.closeEvents)

        // Late refine after submit must be ignored completely.
        wsBg.pushText("""{"deck":"text","text":"Please ship it on Friday.","stable_len":25,"refined":true}""")
        runCurrent()
        assertEquals(listOf("Please ship"), updatesBg.map { it.composerText })

        // Case A (Live mic): waits for confirmed post-stop snapshot, applies raw post-stop partial
        // live, deduplicates 2nd concurrent call, and resolves immediately on settled snapshot.
        val wsA = FakeDictationWebSocketSession()
        val recA = FakeDictationAudioRecorder()
        val updatesA = mutableListOf<VoiceDictationComposerUpdate>()
        val controllerA =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsA },
                scope = backgroundScope,
                audioRecorderProvider = { recA },
            )
        controllerA.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updatesA += it })
        runCurrent()
        wsA.pushText("""{"deck":"ready"}""")
        wsA.pushText("""{"deck":"text","text":"Please ship","stable_len":0}""")
        runCurrent()
        assertTrue(controllerA.needsSubmitFinalWait())

        val waitA1 = async { controllerA.awaitPendingFinalBeforeSend() }
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controllerA.state.value.phase)
        assertEquals(1, recA.stopCalls, "live-mic Send skips 300 ms post-roll")
        assertTrue(controllerA.state.value.awaitingSubmitFinal)
        assertFalse(controllerA.awaitPendingFinalBeforeSend(), "duplicate concurrent wait must return false")

        // Post-stop partial (stable_len < text.length) updates composer & underline live while keeping wait open.
        wsA.pushText("""{"deck":"text","text":"Please ship it","stable_len":6}""")
        runCurrent()
        assertEquals("Please ship it", updatesA.last().composerText)
        assertEquals(VoiceDictationTextRange(6, 14), controllerA.state.value.provisionalRange)
        assertTrue(controllerA.state.value.awaitingSubmitFinal)
        assertFalse(waitA1.isCompleted)

        // Confirmed post-stop snapshot resolves wait immediately with true and clears underline.
        wsA.pushText("""{"deck":"text","text":"Please ship it on Friday.","stable_len":25}""")
        runCurrent()
        assertTrue(waitA1.await())
        assertFalse(controllerA.state.value.awaitingSubmitFinal)
        assertNull(controllerA.state.value.provisionalRange)
        assertEquals("Please ship it on Friday.", updatesA.last().composerText)
        assertFalse(controllerA.needsSubmitFinalWait())
        assertTrue(controllerA.awaitPendingFinalBeforeSend(), "already stopped/finalized must return true immediately")
        controllerA.finishForSubmit()
        runCurrent()

        // Case B: user edits composer during live-mic submit wait -> releases immediately with true.
        val wsB = FakeDictationWebSocketSession()
        val controllerB =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsB },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        controllerB.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsB.pushText("""{"deck":"ready"}""")
        wsB.pushText("""{"deck":"text","text":"Draft","stable_len":0}""")
        runCurrent()

        val waitB = async { controllerB.awaitPendingFinalBeforeSend() }
        runCurrent()
        assertTrue(controllerB.state.value.awaitingSubmitFinal)
        controllerB.onUserEditedComposer("Draft!")
        runCurrent()
        assertTrue(waitB.await())
        assertFalse(controllerB.state.value.awaitingSubmitFinal)
        assertNull(controllerB.state.value.provisionalRange)
        controllerB.finishForSubmit()
        runCurrent()

        // Case C: live-mic Send hits 1,000 ms timeout while refine stalls -> sends raw trailing words cleanly.
        val wsC = FakeDictationWebSocketSession()
        val updatesC = mutableListOf<VoiceDictationComposerUpdate>()
        val controllerC =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsC },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        controllerC.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updatesC += it })
        runCurrent()
        wsC.pushText("""{"deck":"ready"}""")
        wsC.pushText("""{"deck":"text","text":"你可以拿一下我的日","stable_len":0}""")
        runCurrent()

        val waitC = async { controllerC.awaitPendingFinalBeforeSend() }
        runCurrent()
        wsC.pushText("""{"deck":"text","text":"你可以拿一下我的日志","stable_len":0}""")
        runCurrent()
        assertEquals("你可以拿一下我的日志", updatesC.last().composerText)
        advanceTimeBy(VOICE_DICTATION_DEFAULT_SUBMIT_FINAL_WAIT_MS - 1L)
        runCurrent()
        assertFalse(waitC.isCompleted)
        assertTrue(controllerC.state.value.awaitingSubmitFinal)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(waitC.await())
        assertFalse(controllerC.state.value.awaitingSubmitFinal)
        assertEquals("你可以拿一下我的日志", updatesC.last().composerText)
        controllerC.finishForSubmit()
        runCurrent()
        assertNull(controllerC.state.value.provisionalRange)

        // Case D: owner change / cancel / start during live-mic submit wait cancels submit (returns false).
        val wsD = FakeDictationWebSocketSession()
        val controllerD =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsD },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        controllerD.start(serverId = "srv-1", channelId = validChannelUuid, baseText = "", onComposerUpdate = {})
        runCurrent()
        wsD.pushText("""{"deck":"ready"}""")
        wsD.pushText("""{"deck":"text","text":"Old room"}""")
        runCurrent()

        val waitD = async { controllerD.awaitPendingFinalBeforeSend() }
        runCurrent()
        assertTrue(controllerD.state.value.awaitingSubmitFinal)
        controllerD.onOwnerChanged(serverId = "srv-1", channelId = "660e8400-e29b-41d4-a716-446655440000")
        runCurrent()
        assertFalse(waitD.await(), "owner change during submit wait must abort send with false")
        assertFalse(controllerD.state.value.awaitingSubmitFinal)
        assertFalse(controllerD.state.value.hasBackgroundDrain)
    }

    @Test
    fun buttonGestureHandlesShortTapHoldPermissionCancelAndKeyboardClick() = runTest {
        var active = false
        var startCount = 0
        var stopCount = 0
        val modes = mutableListOf<VoiceDictationGestureMode>()
        val gesture =
            VoiceDictationButtonGesture(
                scope = backgroundScope,
                isActive = { active },
                onStart = {
                    startCount += 1
                    active = true
                },
                onStop = {
                    stopCount += 1
                    active = false
                },
                onModeChange = { modes += it },
            )

        // 1. Short tap (< 350 ms) locks; trailing click suppressed; next tap stops.
        assertTrue(gesture.handlePointerDown(pointerId = 1L))
        assertEquals(VoiceDictationGestureMode.PendingHold, gesture.mode)
        assertEquals(1, startCount)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(VoiceDictationGestureMode.PendingHold, gesture.mode)
        gesture.handlePointerUp(pointerId = 1L)
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(0, stopCount)
        // Trailing click after pointer gesture must not double-toggle.
        gesture.handleClick()
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(0, stopCount)

        // Next tap stops cleanly.
        assertTrue(gesture.handlePointerDown(pointerId = 2L))
        gesture.handlePointerUp(pointerId = 2L)
        assertEquals(VoiceDictationGestureMode.Idle, gesture.mode)
        assertEquals(1, stopCount)
        gesture.handleClick()
        assertEquals(1, startCount)

        // 2. Press and hold (>= 350 ms) enters Holding; release stops.
        assertTrue(gesture.handlePointerDown(pointerId = 3L))
        assertEquals(2, startCount)
        advanceTimeBy(VOICE_DICTATION_HOLD_THRESHOLD_MS)
        runCurrent()
        assertEquals(VoiceDictationGestureMode.Holding, gesture.mode)
        gesture.handlePointerUp(pointerId = 3L)
        assertEquals(VoiceDictationGestureMode.Idle, gesture.mode)
        assertEquals(2, stopCount)

        // 3. PointerCancel during PendingHold (e.g. OS mic permission dialog) keeps Locked (never aborts!).
        assertTrue(gesture.handlePointerDown(pointerId = 4L))
        assertEquals(3, startCount)
        assertEquals(VoiceDictationGestureMode.PendingHold, gesture.mode)
        gesture.handlePointerCancel(pointerId = 4L)
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertTrue(active)
        assertEquals(2, stopCount)
        // Stop via keyboard click.
        gesture.handleClick()
        assertEquals(VoiceDictationGestureMode.Idle, gesture.mode)
        assertEquals(3, stopCount)

        // 4. PointerCancel during Holding stops cleanly so mic never sticks open.
        assertTrue(gesture.handlePointerDown(pointerId = 5L))
        advanceTimeBy(VOICE_DICTATION_HOLD_THRESHOLD_MS)
        runCurrent()
        assertEquals(VoiceDictationGestureMode.Holding, gesture.mode)
        gesture.handlePointerCancel(pointerId = 5L)
        assertEquals(VoiceDictationGestureMode.Idle, gesture.mode)
        assertFalse(active)
        assertEquals(4, stopCount)

        // 5. Keyboard activation toggles Idle -> Locked -> Idle cleanly.
        advanceTimeBy(10L)
        runCurrent()
        gesture.handleClick()
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(5, startCount)
        gesture.handleClick()
        assertEquals(VoiceDictationGestureMode.Idle, gesture.mode)
        assertEquals(5, stopCount)

        // 6. Trailing pointer click arriving up to <400 ms after pointerUp is still suppressed
        //    (prevents double-toggle / stray empty WS on frame lag or desktop mouse release),
        //    while a subsequent keyboard click after the 400 ms window still activates cleanly.
        assertTrue(gesture.handlePointerDown(pointerId = 6L))
        gesture.handlePointerUp(pointerId = 6L)
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(6, startCount)
        assertEquals(5, stopCount)
        advanceTimeBy(VOICE_DICTATION_CLICK_SUPPRESSION_RESET_MS - 1L)
        runCurrent()
        gesture.handleClick()
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(5, stopCount)

        // Pointer drifts off without trailing click: keyboard activation works after 400 ms reset.
        assertTrue(gesture.handlePointerDown(pointerId = 7L))
        gesture.handlePointerUp(pointerId = 7L)
        assertEquals(VoiceDictationGestureMode.Idle, gesture.mode)
        assertEquals(6, stopCount)
        advanceTimeBy(VOICE_DICTATION_CLICK_SUPPRESSION_RESET_MS)
        runCurrent()
        gesture.handleClick()
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(7, startCount)

        gesture.dispose()
    }

    @Test
    fun controllerQuotaExhaustedPersistsAcrossClearErrorAndClearsAfterResetOrOwnerChange() = runTest {
        var fakeNowMs = 1_795_700_000_000L // before 2026-11-27T00:00:00.000Z (1_795_737_600_000L)
        val resetsAtIso = "2026-11-27T00:00:00.000Z"
        val resetsAtEpochMs = 1_795_737_600_000L
        val quotaFailure =
            VoiceDictationV2PermitFailure.QuotaExceeded(
                usedSeconds = 7_200,
                limitSeconds = 7_200,
                resetsAtIso = resetsAtIso,
            )
        val permitApi = FakeDictationPermitApi(VoiceDictationV2PermitOutcome.Refused(quotaFailure))
        val wsSession = FakeDictationWebSocketSession()
        val controller =
            VoiceDictationV2Controller(
                permitApi = permitApi,
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSession },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
            )

        controller.start(serverId = "srv-1", channelId = validChannelUuid, onComposerUpdate = {})
        runCurrent()
        assertTrue(controller.state.value.quotaExhausted)
        assertEquals(resetsAtIso, controller.state.value.quotaExhaustedResetsAtIso)
        assertEquals(resetsAtEpochMs, controller.state.value.quotaExhaustedResetsAtEpochMs)
        assertTrue(controller.isQuotaExhausted())

        // Dismissing error banner clears error WITHOUT clearing quotaExhausted*.
        controller.clearError()
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.quotaExhausted)
        assertEquals(resetsAtIso, controller.state.value.quotaExhaustedResetsAtIso)
        assertTrue(controller.isQuotaExhausted())

        // Once clock reaches resetsAt, quota is no longer exhausted and refresh clears it.
        fakeNowMs = resetsAtEpochMs
        assertFalse(controller.isQuotaExhausted())
        controller.refreshQuotaExhaustedState()
        assertFalse(controller.state.value.quotaExhausted)
        assertNull(controller.state.value.quotaExhaustedResetsAtIso)

        // Mid-stream StreamError(QuotaExceeded) also latches persistent quotaExhausted using permit quota resetsAt,
        // survives clearError(), and clears on onOwnerChanged().
        fakeNowMs = resetsAtEpochMs - 60_000L
        permitApi.nextOutcome = defaultPermitOutcome()
        controller.start(serverId = "srv-1", channelId = validChannelUuid, onComposerUpdate = {})
        runCurrent()
        wsSession.pushText("""{"deck":"ready"}""")
        wsSession.pushText("""{"deck":"error","code":"quota_exceeded"}""")
        runCurrent()
        assertTrue(controller.state.value.quotaExhausted)
        assertEquals(resetsAtIso, controller.state.value.quotaExhaustedResetsAtIso)
        assertEquals(0, controller.state.value.quota?.remainingSeconds)
        controller.clearError()
        assertNull(controller.state.value.error)
        assertTrue(controller.isQuotaExhausted())

        controller.onOwnerChanged(serverId = "srv-2", channelId = validChannelUuid)
        assertFalse(controller.state.value.quotaExhausted)
        assertNull(controller.state.value.quotaExhaustedResetsAtIso)
        assertFalse(controller.isQuotaExhausted())
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
        // 1) Cancel while POST /permits is in flight (zero-wait: phase is already Listening once mic started)
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
        assertEquals(VoiceDictationV2Phase.Listening, controller1.state.value.phase)
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
        assertEquals(VoiceDictationV2Phase.Listening, controller2.state.value.phase)

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
        assertEquals(RaftWebSocketSession.NORMAL_CLOSURE, lateSocket.lastCloseCode)
        assertEquals(VOICE_DICTATION_CLOSE_REASON_USER_CANCEL, lateSocket.lastCloseReason)
        assertTrue(lateSocket.sentText.isEmpty(), "must not send start frame on an already-cancelled session")

        // 3) Cancel while AudioRecorder.start() is suspended (Connecting phase)
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

        controller.onOwnerChanged(serverId = "srv-1", channelId = "660e8400-e29b-41d4-a716-446655440000")
        wsSession.pushText("""{"deck":"text","text":"stale speech"}""")
        runCurrent()

        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, wsSession.closeCalls)
        assertTrue(oldComposerUpdates.isEmpty())
        assertEquals("660e8400-e29b-41d4-a716-446655440000", controller.state.value.owner?.channelId)

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
    fun controllerBackgroundStopDrainTimeoutDefaultsTo10500MsAndSettlesCleanly() = runTest {
        assertEquals(10_500L, VOICE_DICTATION_DEFAULT_STOP_DRAIN_TIMEOUT_MS)
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
        wsSession.pushText("""{"deck":"text","text":"kept transcript"}""")
        runCurrent()
        assertEquals(listOf("kept transcript"), updates.map { it.composerText })

        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertTrue(controller.state.value.hasBackgroundDrain)

        // Before 10.5 s timeout: background drain still open.
        advanceTimeBy(10_499L)
        runCurrent()
        assertTrue(controller.state.value.hasBackgroundDrain)
        assertEquals(0, wsSession.closeCalls)

        // At 10.5 s timeout: background drain settles cleanly, closes WS, keeps text with no error.
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertFalse(controller.state.value.hasBackgroundDrain)
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
        assertEquals(RaftWebSocketSession.NORMAL_CLOSURE, wsSession.lastCloseCode)
        assertEquals(VOICE_DICTATION_CLOSE_REASON_ERROR, wsSession.lastCloseReason)
    }

    @Test
    fun permitResponseParsesOptionalSessionIdTraceIdAndStreamUrlSourceAndToleratesOlderServers() {
        // Older server omitting sessionId / traceId / streamUrlSource still parses cleanly with nulls.
        val legacy = assertNotNull(parseVoiceDictationV2Permit(validPermitJson))
        assertNull(legacy.sessionId)
        assertNull(legacy.traceId)
        assertNull(legacy.streamUrlSource)

        // Newer server (slock#17246) returning all three optional metadata fields.
        val upperTraceId = "4BF92F3577B34DA6A3CE929D0E0E4736"
        val enrichedJson =
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
              },
              "sessionId": "jti_0123456789abcdef",
              "traceId": "$upperTraceId",
              "streamUrlSource": "raft_edge"
            }
            """.trimIndent()
        val enriched = assertNotNull(parseVoiceDictationV2Permit(enrichedJson))
        assertEquals("jti_0123456789abcdef", enriched.sessionId)
        assertEquals(upperTraceId.lowercase(), enriched.traceId)
        assertEquals(VoiceStreamUrlSource.RaftEdge, enriched.streamUrlSource)

        // Direct source parses cleanly; invalid traceId/sessionId/streamUrlSource fail soft to null (permit still valid).
        val invalidMetaJson =
            """
            {
              "permit": "hv2.k1.payload.sig",
              "url": "wss://live.holt.botiverse.build/v1/stream",
              "expiresAt": "2026-11-26T20:01:00.000Z",
              "maxDurationSeconds": 600,
              "quota": {
                "usedSeconds": 0,
                "limitSeconds": 7200,
                "remainingSeconds": 7200,
                "resetsAt": "2026-11-27T00:00:00.000Z"
              },
              "sessionId": "${"x".repeat(VOICE_FEEDBACK_SESSION_ID_MAX_CHARS + 1)}",
              "traceId": "00000000000000000000000000000000",
              "streamUrlSource": "unknown_route"
            }
            """.trimIndent()
        val invalidMeta = assertNotNull(parseVoiceDictationV2Permit(invalidMetaJson))
        assertNull(invalidMeta.sessionId)
        assertNull(invalidMeta.traceId)
        assertNull(invalidMeta.streamUrlSource)

        val directJson =
            enrichedJson.replace("\"raft_edge\"", "\"direct\"")
        assertEquals(VoiceStreamUrlSource.Direct, assertNotNull(parseVoiceDictationV2Permit(directJson)).streamUrlSource)
    }

    @Test
    fun streamClientAttachesTraceparentQueryParamWhenTraceIdPresentAndNeverAddsPermit() = runTest {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val spanId = "00f067aa0ba902b7"
        assertEquals(
            "wss://live.holt.botiverse.build/v1/stream?tp=00-$traceId-$spanId-01",
            buildVoiceDictationV2StreamUrl(
                baseUrl = "wss://live.holt.botiverse.build/v1/stream",
                traceId = traceId.uppercase(),
                spanId = spanId,
            ),
        )
        assertEquals(
            "wss://live.holt.botiverse.build/v1/stream?region=us&tp=00-$traceId-$spanId-01",
            buildVoiceDictationV2StreamUrl(
                baseUrl = "wss://live.holt.botiverse.build/v1/stream?region=us",
                traceId = traceId,
                spanId = spanId,
            ),
        )
        assertEquals(
            "wss://live.holt.botiverse.build/v1/stream",
            buildVoiceDictationV2StreamUrl(
                baseUrl = "wss://live.holt.botiverse.build/v1/stream",
                traceId = "not-a-valid-trace-id",
            ),
        )

        val wsSession = FakeDictationWebSocketSession()
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        val streamClient =
            VoiceDictationV2StreamClient { url, headers ->
                capturedUrl = url
                capturedHeaders = headers
                wsSession
            }
        val conn =
            streamClient.connect(
                url = "wss://live.holt.botiverse.build/v1/stream",
                permit = "hv2.k1.body.sig",
                traceId = traceId,
            )
        val actualUrl = assertNotNull(capturedUrl)
        assertTrue(actualUrl.startsWith("wss://live.holt.botiverse.build/v1/stream?tp=00-$traceId-"))
        assertTrue(actualUrl.endsWith("-01"))
        assertFalse(actualUrl.contains("permit="))
        assertEquals(mapOf("Authorization" to "Bearer hv2.k1.body.sig"), capturedHeaders)
        conn.close(RaftWebSocketSession.NORMAL_CLOSURE, VOICE_DICTATION_CLOSE_REASON_DONE)
        assertEquals(RaftWebSocketSession.NORMAL_CLOSURE, wsSession.lastCloseCode)
        assertEquals(VOICE_DICTATION_CLOSE_REASON_DONE, wsSession.lastCloseReason)
    }

    @Test
    fun controllerClosesWebSocketWithCode1000AndExplicitReasonAcrossEveryLifecyclePath() = runTest {
        // 1. Normal completion (`done`)
        val wsDone = FakeDictationWebSocketSession()
        val cDone =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsDone },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cDone.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsDone.pushText("""{"deck":"ready"}""")
        runCurrent()
        cDone.stop()
        runCurrent()
        wsDone.pushText("""{"deck":"done"}""")
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_DONE), wsDone.closeEvents)

        // 2. Explicit user cancel (`user_cancel`)
        val wsCancel = FakeDictationWebSocketSession()
        val cCancel =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsCancel },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cCancel.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsCancel.pushText("""{"deck":"ready"}""")
        runCurrent()
        cCancel.cancel()
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_USER_CANCEL), wsCancel.closeEvents)

        // 3. Owner changed (`owner_changed`)
        val wsOwner = FakeDictationWebSocketSession()
        val cOwner =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsOwner },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cOwner.start(serverId = "srv-1", channelId = validChannelUuid, onComposerUpdate = {})
        runCurrent()
        wsOwner.pushText("""{"deck":"ready"}""")
        runCurrent()
        cOwner.onOwnerChanged(serverId = "srv-2", channelId = validChannelUuid)
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_OWNER_CHANGED), wsOwner.closeEvents)

        // 4. Finish for submit (`submit`)
        val wsSubmit = FakeDictationWebSocketSession()
        val cSubmit =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSubmit },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cSubmit.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsSubmit.pushText("""{"deck":"ready"}""")
        runCurrent()
        cSubmit.stop()
        runCurrent()
        cSubmit.finishForSubmit()
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_SUBMIT), wsSubmit.closeEvents)

        // 5. Post-stop drain timeout (`drain_timeout`)
        val wsDrain = FakeDictationWebSocketSession()
        val cDrain =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsDrain },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cDrain.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsDrain.pushText("""{"deck":"ready"}""")
        runCurrent()
        cDrain.stop()
        runCurrent()
        advanceTimeBy(VOICE_DICTATION_DEFAULT_STOP_DRAIN_TIMEOUT_MS)
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_DRAIN_TIMEOUT), wsDrain.closeEvents)

        // 6. Pre-ready audio buffer overflow (`pre_ready_overflow`)
        val wsOverflow = FakeDictationWebSocketSession()
        val recOverflow = FakeDictationAudioRecorder()
        val cOverflow =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsOverflow },
                scope = backgroundScope,
                audioRecorderProvider = { recOverflow },
            )
        cOverflow.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        for (i in 0..VOICE_DICTATION_MAX_PRE_READY_CHUNKS) {
            recOverflow.emitChunk(byteArrayOf(i.toByte()))
        }
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_PRE_READY_OVERFLOW), wsOverflow.closeEvents)

        // 7. Superseded by new start (`superseded`)
        val wsFirst = FakeDictationWebSocketSession()
        val wsSecond = FakeDictationWebSocketSession()
        var idx = 0
        val cSuperseded =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> if (idx++ == 0) wsFirst else wsSecond },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cSuperseded.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsFirst.pushText("""{"deck":"ready"}""")
        runCurrent()
        cSuperseded.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_SUPERSEDED), wsFirst.closeEvents)
        cSuperseded.cancel()
        runCurrent()
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_USER_CANCEL), wsSecond.closeEvents)
    }

    @Test
    fun controllerTracksImplicitOutcomeMetricsAndFeedbackTargetAcrossSubmitClearAndReRecord() = runTest {
        var fakeNowMs = 10_000L
        val outcomes = mutableListOf<VoiceDictationOutcomeReport>()
        val ws1 = FakeDictationWebSocketSession()
        val controller =
            VoiceDictationV2Controller(
                permitApi =
                    FakeDictationPermitApi(
                        defaultPermitOutcome(
                            sessionId = "jti_sent_asis",
                            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                            streamUrlSource = VoiceStreamUrlSource.RaftEdge,
                        ),
                    ),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws1 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onOutcomeReport = { outcomes += it },
                privacyModeProvider = { RaftPrivacyMode.Off },
                clientPlatformProvider = { VoiceFeedbackClientPlatform.Mobile },
            )

        // 1. Sent as-is after clean `done`
        controller.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        fakeNowMs += 120L
        ws1.pushText("""{"deck":"ready"}""")
        runCurrent()
        fakeNowMs += 250L
        ws1.pushText("""{"deck":"text","text":"Ship it","stable_len":0}""")
        ws1.pushText("""{"deck":"text","text":"Ship it.","stable_len":8,"refined":true}""")
        runCurrent()
        controller.stop()
        runCurrent()
        ws1.pushText("""{"deck":"done"}""")
        runCurrent()

        assertEquals(
            VoiceDictationFeedbackTarget(
                serverId = "srv-1",
                sessionId = "jti_sent_asis",
                raw = "Ship it",
                refined = "Ship it.",
            ),
            controller.state.value.recentFeedbackTarget,
        )

        val submittedTarget = assertNotNull(controller.reportComposerSubmitted("Ship it."))
        assertEquals("Ship it.", submittedTarget.sent)
        assertEquals("Ship it.", controller.state.value.recentFeedbackTarget?.sent)
        assertEquals(1, outcomes.size)
        val first = outcomes.single()
        assertEquals("srv-1", first.serverId)
        assertEquals("jti_sent_asis", first.sessionId)
        assertEquals(true, first.clientMetrics.sentAsIs)
        assertEquals(0, first.clientMetrics.editDistance)
        assertEquals(0.0, first.clientMetrics.editRatio)
        assertEquals(false, first.clientMetrics.abandoned)
        assertEquals(false, first.clientMetrics.reRecorded)
        assertEquals(false, first.clientMetrics.sentBeforeRefineDone)
        assertEquals(120, first.clientMetrics.clientConnectMs)
        assertEquals(370, first.clientMetrics.clientFirstPartialMs)
        assertEquals(VoiceFeedbackClientPlatform.Mobile, first.clientMetrics.clientPlatform)

        // 2. Sent before background refine done (`finishForSubmit` while background drain active)
        val ws2 = FakeDictationWebSocketSession()
        val outcomes2 = mutableListOf<VoiceDictationOutcomeReport>()
        val c2 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(sessionId = "jti_early_send")),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws2 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onOutcomeReport = { outcomes2 += it },
                privacyModeProvider = { RaftPrivacyMode.Off },
                clientPlatformProvider = { VoiceFeedbackClientPlatform.Mobile },
            )
        c2.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        ws2.pushText("""{"deck":"ready"}""")
        ws2.pushText("""{"deck":"text","text":"hello world","stable_len":11}""")
        runCurrent()
        c2.stop()
        runCurrent()
        c2.finishForSubmit()
        runCurrent()
        c2.reportComposerSubmitted("hello Raft!")
        assertEquals(1, outcomes2.size)
        assertEquals(true, outcomes2.single().clientMetrics.sentBeforeRefineDone)
        assertEquals(false, outcomes2.single().clientMetrics.sentAsIs)
        assertTrue((outcomes2.single().clientMetrics.editRatio ?: 0.0) > 0.0)

        // 3. Clearing composer after completion reports abandoned = true, reRecorded = false
        val ws3 = FakeDictationWebSocketSession()
        val outcomes3 = mutableListOf<VoiceDictationOutcomeReport>()
        val c3 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(sessionId = "jti_cleared")),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws3 },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onOutcomeReport = { outcomes3 += it },
                privacyModeProvider = { RaftPrivacyMode.Off },
                clientPlatformProvider = { VoiceFeedbackClientPlatform.Mobile },
            )
        c3.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        ws3.pushText("""{"deck":"ready"}""")
        ws3.pushText("""{"deck":"text","text":"wrong words","stable_len":11}""")
        runCurrent()
        c3.stop()
        runCurrent()
        ws3.pushText("""{"deck":"done"}""")
        runCurrent()
        c3.onUserEditedComposer("")
        assertEquals(1, outcomes3.size)
        assertEquals("jti_cleared", outcomes3.single().sessionId)
        assertEquals(true, outcomes3.single().clientMetrics.abandoned)
        assertEquals(false, outcomes3.single().clientMetrics.reRecorded)

        // 4. Starting a new dictation within 10 s without sending reports abandoned = true, reRecorded = true
        val ws4a = FakeDictationWebSocketSession()
        val ws4b = FakeDictationWebSocketSession()
        var ws4Idx = 0
        val outcomes4 = mutableListOf<VoiceDictationOutcomeReport>()
        val c4 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(sessionId = "jti_rerecord")),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> if (ws4Idx++ == 0) ws4a else ws4b },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onOutcomeReport = { outcomes4 += it },
                privacyModeProvider = { RaftPrivacyMode.Off },
                clientPlatformProvider = { VoiceFeedbackClientPlatform.Mobile },
            )
        c4.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        ws4a.pushText("""{"deck":"ready"}""")
        ws4a.pushText("""{"deck":"text","text":"first try","stable_len":9}""")
        runCurrent()
        c4.stop()
        runCurrent()
        ws4a.pushText("""{"deck":"done"}""")
        runCurrent()
        fakeNowMs += 4_000L
        c4.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        assertEquals(1, outcomes4.size)
        assertEquals("jti_rerecord", outcomes4.single().sessionId)
        assertEquals(true, outcomes4.single().clientMetrics.abandoned)
        assertEquals(true, outcomes4.single().clientMetrics.reRecorded)
        c4.cancel()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // 10. Per-session telemetry (`slock.composer.voice_dictation_session`, #wg-mobile-sync:#357)
    // -------------------------------------------------------------------------

    @Test
    fun controllerEmitsSessionTelemetryWithHoldModeTimingsPauseGapsPreReadyChunksAndZeroLeakage() = runTest {
        var fakeNowMs = 10_000L
        val micGate = CompletableDeferred<VoiceDictationAudioStartResult>()
        val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val recorder = FakeDictationAudioRecorder(startGate = micGate)
        val ws = FakeDictationWebSocketSession()
        val emissions = mutableListOf<Pair<String, VoiceDictationSessionSummary>>()

        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = permitGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { serverId, summary -> emissions += serverId to summary },
            )

        val secretDraft = "SECRET_DRAFT_BEFORE_CARET"
        val secretHint = "SECRET_CONTACT_HINT"
        val secretSpoken = "SECRET_SPOKEN_UTTERANCE"

        controller.start(
            serverId = "srv-telemetry",
            channelId = validChannelUuid,
            baseText = secretDraft,
            insertStart = secretDraft.length,
            insertEnd = secretDraft.length,
            draftBefore = secretDraft,
            contactHints = listOf(secretHint),
            mode = VoiceDictationSessionMode.Tap,
            onComposerUpdate = {},
        )
        runCurrent()

        // Mic becomes live at +120 ms; hold gesture engages while listening.
        fakeNowMs += 120L
        micGate.complete(VoiceDictationAudioStartResult.Started)
        runCurrent()
        controller.setSessionMode(VoiceDictationSessionMode.Hold)

        // Emit 3 pre-ready PCM chunks while permit + WS connect are in flight.
        recorder.emitChunk(ByteArray(3_200) { 1 })
        recorder.emitChunk(ByteArray(3_200) { 2 })
        recorder.emitChunk(ByteArray(3_200) { 3 })

        // Permit resolves with streamUrlSource = raft_edge; transport becomes ready at +600 ms.
        fakeNowMs += 280L
        permitGate.complete(
            defaultPermitOutcome(
                sessionId = "jti_session_telemetry",
                streamUrlSource = VoiceStreamUrlSource.RaftEdge,
            ),
        )
        runCurrent()
        fakeNowMs += 200L
        ws.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(3, ws.sentBinary.size)

        // Post-ready live audio chunk does NOT inflate pre-ready counters.
        recorder.emitChunk(ByteArray(3_200) { 4 })
        runCurrent()
        assertEquals(4, ws.sentBinary.size)

        // Utterance 1 partials (+500 ms -> first partial at 1100 ms; +800 ms -> midsentence gap 800 ms) + final (+1200 ms).
        fakeNowMs += 500L
        ws.pushText("""{"deck":"text","text":"$secretSpoken","stable_len":0}""")
        runCurrent()
        fakeNowMs += 800L
        ws.pushText("""{"deck":"text","text":"$secretSpoken part two","stable_len":0}""")
        runCurrent()
        fakeNowMs += 1_200L
        val utt1 = "$secretSpoken part two."
        ws.pushText("""{"deck":"text","text":"$utt1","stable_len":${utt1.length}}""")
        runCurrent()

        // Utterance 2 after 2600 ms inter-final gap, 400 ms midsentence gap, then finalized.
        fakeNowMs += 2_600L
        ws.pushText("""{"deck":"text","text":"$utt1 Next","stable_len":${utt1.length}}""")
        runCurrent()
        fakeNowMs += 400L
        ws.pushText("""{"deck":"text","text":"$utt1 Next line","stable_len":${utt1.length}}""")
        runCurrent()
        fakeNowMs += 600L
        val utt2 = "$utt1 Next line."
        ws.pushText("""{"deck":"text","text":"$utt2","stable_len":${utt2.length}}""")
        runCurrent()

        // User stops 3.3 s after final -> duration 10_000 ms; background refine & done settle 950 ms later.
        fakeNowMs += 3_300L
        controller.stop(VoiceDictationStopReason.Button)
        runCurrent()
        assertTrue(emissions.isEmpty(), "telemetry emits once background drain settles")

        fakeNowMs += 400L
        ws.pushText("""{"deck":"text","text":"$utt2","stable_len":${utt2.length},"refined":true}""")
        runCurrent()
        fakeNowMs += 550L
        ws.pushText("""{"deck":"done"}""")
        runCurrent()

        // Repeated cancel / owner change after completion must never emit a duplicate event.
        controller.cancel()
        controller.onOwnerChanged(serverId = "srv-other")
        runCurrent()

        assertEquals(1, emissions.size)
        val (emittedServerId, summary) = emissions.single()
        assertEquals("srv-telemetry", emittedServerId)
        assertEquals(VoiceDictationSessionMode.Hold, summary.mode)
        assertEquals(VoiceDictationStopReason.Button, summary.stoppedBy)
        assertEquals(10_000L, summary.durationMs)
        assertEquals(2, summary.utterances)
        assertEquals(2, summary.finalsCount)
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
        assertEquals(VoiceStreamUrlSource.RaftEdge, summary.streamUrlSource)
        assertNull(summary.errorCode)

        val attrs = voiceDictationSessionTraceAttrs(summary)
        assertEquals("hold", attrs["mode"]?.jsonPrimitive?.content)
        assertEquals("button", attrs["stopped_by"]?.jsonPrimitive?.content)
        assertEquals("raft_edge", attrs["stream_url_source"]?.jsonPrimitive?.content)
        assertEquals(10_000L, attrs["duration_ms"]?.jsonPrimitive?.long)
        assertEquals(950L, attrs["finishing_ms"]?.jsonPrimitive?.long)
        assertFalse("error_code" in attrs)

        val serialized = attrs.toString()
        for (secret in listOf(secretDraft, secretHint, secretSpoken, "srv-telemetry", validChannelUuid, "jti_session_telemetry")) {
            assertFalse(secret in serialized, "secret '$secret' leaked into voice_dictation_session attrs: $serialized")
        }
    }

    @Test
    fun controllerEmitsExactStopReasonsAcrossAllLifecyclePathsAndIsolatesCallbackThrows() = runTest {
        var fakeNowMs = 1_000L

        // 1. Early button stop before mic resolves -> stoppedBy = Button, micStartMs = null, finishingMs = null.
        val earlyMicGate = CompletableDeferred<VoiceDictationAudioStartResult>()
        val earlyEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cEarlyStop =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder(startGate = earlyMicGate) },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> earlyEmissions += s },
            )
        cEarlyStop.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        fakeNowMs += 250L
        cEarlyStop.stop(VoiceDictationStopReason.Button)
        earlyMicGate.complete(VoiceDictationAudioStartResult.Started)
        runCurrent()
        assertEquals(1, earlyEmissions.size)
        assertEquals(VoiceDictationStopReason.Button, earlyEmissions.single().stoppedBy)
        assertEquals(250L, earlyEmissions.single().durationMs)
        assertNull(earlyEmissions.single().micStartMs)
        assertNull(earlyEmissions.single().finishingMs)

        // 2. Blur stop -> drains in background and emits stoppedBy = Blur with finishingMs.
        val wsBlur = FakeDictationWebSocketSession()
        val blurEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cBlur =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(streamUrlSource = VoiceStreamUrlSource.Direct)),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBlur },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> blurEmissions += s },
            )
        cBlur.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsBlur.pushText("""{"deck":"ready"}""")
        runCurrent()
        fakeNowMs += 400L
        cBlur.stop(VoiceDictationStopReason.Blur)
        runCurrent()
        fakeNowMs += 180L
        wsBlur.pushText("""{"deck":"done"}""")
        runCurrent()
        assertEquals(1, blurEmissions.size)
        assertEquals(VoiceDictationStopReason.Blur, blurEmissions.single().stoppedBy)
        assertEquals(180L, blurEmissions.single().finishingMs)
        assertEquals(VoiceStreamUrlSource.Direct, blurEmissions.single().streamUrlSource)

        // 3. Esc cancel (discardProvisional = true) -> stoppedBy = Esc, finishingMs = null.
        val wsEsc = FakeDictationWebSocketSession()
        val escEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cEsc =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsEsc },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> escEmissions += s },
            )
        cEsc.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsEsc.pushText("""{"deck":"ready"}""")
        wsEsc.pushText("""{"deck":"text","text":"draft","stable_len":0}""")
        runCurrent()
        cEsc.cancel(discardProvisional = true)
        runCurrent()
        assertEquals(1, escEmissions.size)
        assertEquals(VoiceDictationStopReason.Esc, escEmissions.single().stoppedBy)
        assertNull(escEmissions.single().finishingMs)

        // 4. Server-initiated Done while active -> stoppedBy = Server, finishingMs = 0.
        val wsServer = FakeDictationWebSocketSession()
        val serverEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cServer =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsServer },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> serverEmissions += s },
            )
        cServer.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsServer.pushText("""{"deck":"ready"}""")
        wsServer.pushText("""{"deck":"text","text":"Finished by server.","stable_len":19}""")
        wsServer.pushText("""{"deck":"done"}""")
        runCurrent()
        assertEquals(1, serverEmissions.size)
        assertEquals(VoiceDictationStopReason.Server, serverEmissions.single().stoppedBy)
        assertEquals(0L, serverEmissions.single().finishingMs)
        assertEquals(1, serverEmissions.single().utterances)

        // 5a. Submit while active -> stoppedBy = Submit, finishingMs = null.
        val wsSubmitActive = FakeDictationWebSocketSession()
        val submitActiveEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cSubmitActive =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSubmitActive },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> submitActiveEmissions += s },
            )
        cSubmitActive.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsSubmitActive.pushText("""{"deck":"ready"}""")
        runCurrent()
        cSubmitActive.finishForSubmit()
        runCurrent()
        assertEquals(1, submitActiveEmissions.size)
        assertEquals(VoiceDictationStopReason.Submit, submitActiveEmissions.single().stoppedBy)
        assertNull(submitActiveEmissions.single().finishingMs)

        // 5b. Submit while draining after Button stop -> preserves stoppedBy = Button and records finishingMs.
        val wsSubmitBg = FakeDictationWebSocketSession()
        val submitBgEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cSubmitBg =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSubmitBg },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> submitBgEmissions += s },
            )
        cSubmitBg.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsSubmitBg.pushText("""{"deck":"ready"}""")
        runCurrent()
        cSubmitBg.stop(VoiceDictationStopReason.Button)
        runCurrent()
        fakeNowMs += 320L
        cSubmitBg.finishForSubmit()
        runCurrent()
        assertEquals(1, submitBgEmissions.size)
        assertEquals(VoiceDictationStopReason.Button, submitBgEmissions.single().stoppedBy)
        assertEquals(320L, submitBgEmissions.single().finishingMs)

        // 6. Owner change -> stoppedBy = Reset, finishingMs = null.
        val wsReset = FakeDictationWebSocketSession()
        val resetEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cReset =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsReset },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> resetEmissions += s },
            )
        cReset.start(serverId = "srv-1", channelId = validChannelUuid, onComposerUpdate = {})
        runCurrent()
        wsReset.pushText("""{"deck":"ready"}""")
        runCurrent()
        cReset.onOwnerChanged(serverId = "srv-2")
        runCurrent()
        assertEquals(1, resetEmissions.size)
        assertEquals(VoiceDictationStopReason.Reset, resetEmissions.single().stoppedBy)
        assertNull(resetEmissions.single().finishingMs)

        // 7. Error paths: missing recorder, unsupported sample rate, mic permission, pre-ready overflow,
        //    background pre-ready refusal, and server stream error.
        val errorEmissions = mutableListOf<VoiceDictationSessionSummary>()
        val cNoMic =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { null },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> errorEmissions += s },
            )
        cNoMic.start(serverId = "srv-1", onComposerUpdate = {})
        assertEquals(VoiceDictationStopReason.Error, errorEmissions.last().stoppedBy)
        assertEquals(VoiceDictationSessionErrorCode.MicNone, errorEmissions.last().errorCode)

        val cBadRate =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder(sampleRateHz = 4_000) },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> errorEmissions += s },
            )
        cBadRate.start(serverId = "srv-1", onComposerUpdate = {})
        assertEquals(VoiceDictationStopReason.Error, errorEmissions.last().stoppedBy)
        assertEquals(VoiceDictationSessionErrorCode.MicUnsupported, errorEmissions.last().errorCode)

        val cPermDenied =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = {
                    FakeDictationAudioRecorder(startResult = VoiceDictationAudioStartResult.PermissionDenied)
                },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> errorEmissions += s },
            )
        cPermDenied.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationStopReason.Error, errorEmissions.last().stoppedBy)
        assertEquals(VoiceDictationSessionErrorCode.MicPermission, errorEmissions.last().errorCode)

        // Pre-ready buffer overflow -> connection_lost with 100 queued, 0 sent.
        val overflowRecorder = FakeDictationAudioRecorder()
        val overflowPermitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val cOverflow =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = overflowPermitGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { overflowRecorder },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> errorEmissions += s },
            )
        cOverflow.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        repeat(VOICE_DICTATION_MAX_PRE_READY_CHUNKS + 1) {
            overflowRecorder.emitChunk(ByteArray(320) { 1 })
        }
        runCurrent()
        assertEquals(VoiceDictationStopReason.Error, errorEmissions.last().stoppedBy)
        assertEquals(VoiceDictationSessionErrorCode.ConnectionLost, errorEmissions.last().errorCode)
        assertEquals(VOICE_DICTATION_MAX_PRE_READY_CHUNKS, errorEmissions.last().preReadyChunksQueued)
        assertEquals(0, errorEmissions.last().preReadyChunksSent)

        // Background stop before ready + permit failure -> stoppedBy = Error, errorCode = Unavailable.
        val bgFailGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val cBgFail =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = bgFailGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> FakeDictationWebSocketSession() },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> errorEmissions += s },
            )
        cBgFail.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        cBgFail.stop(VoiceDictationStopReason.Button)
        runCurrent()
        bgFailGate.complete(VoiceDictationV2PermitOutcome.Refused(VoiceDictationV2PermitFailure.Unavailable()))
        runCurrent()
        assertEquals(VoiceDictationStopReason.Error, errorEmissions.last().stoppedBy)
        assertEquals(VoiceDictationSessionErrorCode.Unavailable, errorEmissions.last().errorCode)
        assertNull(errorEmissions.last().finishingMs)

        // Stream error frame -> stoppedBy = Error, errorCode = SessionLimitReached.
        val wsStreamErr = FakeDictationWebSocketSession()
        val cStreamErr =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsStreamErr },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, s -> errorEmissions += s },
            )
        cStreamErr.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsStreamErr.pushText("""{"deck":"ready"}""")
        wsStreamErr.pushText("""{"deck":"error","code":"session_limit_reached","message":"limit reached"}""")
        runCurrent()
        assertEquals(VoiceDictationStopReason.Error, errorEmissions.last().stoppedBy)
        assertEquals(VoiceDictationSessionErrorCode.SessionLimitReached, errorEmissions.last().errorCode)

        // 8. Throwing onSessionTelemetry callback must never break dictation state transitions.
        val wsThrowing = FakeDictationWebSocketSession()
        var throwingCalls = 0
        val cThrowing =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsThrowing },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
                nowMillis = { fakeNowMs },
                onSessionTelemetry = { _, _ ->
                    throwingCalls += 1
                    error("simulated telemetry sink failure")
                },
            )
        cThrowing.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsThrowing.pushText("""{"deck":"ready"}""")
        wsThrowing.pushText("""{"deck":"text","text":"Still works.","stable_len":12}""")
        runCurrent()
        cThrowing.stop(VoiceDictationStopReason.Button)
        runCurrent()
        wsThrowing.pushText("""{"deck":"done"}""")
        runCurrent()
        assertEquals(1, throwingCalls)
        assertEquals(VoiceDictationV2Phase.Idle, cThrowing.state.value.phase)
        assertFalse(cThrowing.state.value.hasBackgroundDrain)
    }

    // -------------------------------------------------------------------------
    // 11. Stop post-roll (300 ms) & native stop() tail-chunk flush (#wg-call:#17)
    // -------------------------------------------------------------------------

    @Test
    fun controllerStopPostRollStreamsTailAudioAndFlushesRecorderStopTailBeforeWsStop() = runTest {
        assertEquals(300L, VOICE_DICTATION_DEFAULT_STOP_POST_ROLL_MS)
        val ws = FakeDictationWebSocketSession()
        val liveChunk = byteArrayOf(1, 2, 3, 4)
        val postRollChunk1 = byteArrayOf(5, 6, 7, 8)
        val postRollChunk2 = byteArrayOf(9, 10, 11, 12)
        val nativeStopTailChunk = byteArrayOf(13, 14)
        val recorder = FakeDictationAudioRecorder(onStopTailChunks = listOf(nativeStopTailChunk))
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        ws.pushText("""{"deck":"ready"}""")
        runCurrent()

        recorder.emitChunk(liveChunk)
        recorder.emitLevel(0.6f)
        runCurrent()
        assertEquals(1, ws.sentBinary.size)
        assertEquals(0.6f, controller.state.value.audioLevel)

        // Stopping immediately transitions foreground to Idle (audioLevel = 0f, hasBackgroundDrain = true)
        // while keeping the mic open for the 300 ms post-roll window.
        controller.stop()
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(0f, controller.state.value.audioLevel)
        assertTrue(controller.state.value.hasBackgroundDrain)
        assertEquals(0, recorder.stopCalls)
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""), ws.sentText.toList())

        // Audio captured during post-roll streams to WS while audioLevel callbacks stay ignored.
        advanceTimeBy(100L)
        runCurrent()
        recorder.emitChunk(postRollChunk1)
        recorder.emitLevel(0.95f)
        runCurrent()
        assertEquals(0f, controller.state.value.audioLevel)
        assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
        assertEquals(2, ws.sentBinary.size)
        assertContentEquals(postRollChunk1, ws.sentBinary[1])

        advanceTimeBy(199L)
        runCurrent()
        recorder.emitChunk(postRollChunk2)
        runCurrent()
        assertEquals(0, recorder.stopCalls, "recorder must stay open until full 300 ms post-roll elapses")
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""), ws.sentText.toList())
        assertEquals(3, ws.sentBinary.size)
        assertContentEquals(postRollChunk2, ws.sentBinary[2])

        // At 300 ms: recorder.stop() flushes its final <100 ms tail chunk synchronously, which MUST
        // be accepted by handleAudioChunk and sent strictly BEFORE `{"deck":"stop"}`.
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, recorder.stopCalls)
        assertEquals(4, ws.sentBinary.size)
        assertContentEquals(liveChunk, ws.sentBinary[0])
        assertContentEquals(postRollChunk1, ws.sentBinary[1])
        assertContentEquals(postRollChunk2, ws.sentBinary[2])
        assertContentEquals(nativeStopTailChunk, ws.sentBinary[3])
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            ws.sentText.toList(),
        )

        // Stray chunk after recorder.stop() finishes is dropped once micFlushed latches.
        recorder.emitLateChunkAfterStop(byteArrayOf(99, 100))
        runCurrent()
        assertEquals(4, ws.sentBinary.size)

        ws.pushText("""{"deck":"done"}""")
        runCurrent()
        assertFalse(controller.state.value.hasBackgroundDrain)
    }

    @Test
    fun controllerStopBeforeReadyCapturesPostRollAndStopTailIntoPreReadyQueueAndFlushesInOrder() = runTest {
        val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
        val ws = FakeDictationWebSocketSession()
        val openingChunk = byteArrayOf(1, 2)
        val postRollChunk = byteArrayOf(3, 4)
        val stopTailChunk = byteArrayOf(5, 6)
        val recorder = FakeDictationAudioRecorder(onStopTailChunks = listOf(stopTailChunk))
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome(), gate = permitGate),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
            )

        controller.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        recorder.emitChunk(openingChunk)

        controller.stop()
        runCurrent()
        assertEquals(0, recorder.stopCalls)

        advanceTimeBy(150L)
        runCurrent()
        recorder.emitChunk(postRollChunk)

        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1, recorder.stopCalls)
        assertEquals(0, ws.sentBinary.size)

        permitGate.complete(defaultPermitOutcome())
        runCurrent()
        ws.pushText("""{"deck":"ready"}""")
        runCurrent()

        assertEquals(3, ws.sentBinary.size)
        assertContentEquals(openingChunk, ws.sentBinary[0])
        assertContentEquals(postRollChunk, ws.sentBinary[1])
        assertContentEquals(stopTailChunk, ws.sentBinary[2])
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            ws.sentText.toList(),
        )

        ws.pushText("""{"deck":"done"}""")
        runCurrent()
        assertFalse(controller.state.value.hasBackgroundDrain)
    }

    @Test
    fun controllerInterruptionsDuringPostRollAbortImmediatelyAndReleaseRecorderBeforeRestart() = runTest {
        // 1. cancel() during post-roll aborts immediately, drops stop-tail, and never sends WS stop.
        val wsCancel = FakeDictationWebSocketSession()
        val recCancel = FakeDictationAudioRecorder(onStopTailChunks = listOf(byteArrayOf(42, 43)))
        val cCancel =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsCancel },
                scope = backgroundScope,
                audioRecorderProvider = { recCancel },
            )
        cCancel.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsCancel.pushText("""{"deck":"ready"}""")
        runCurrent()
        cCancel.stop()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(0, recCancel.stopCalls)

        cCancel.cancel()
        runCurrent()
        assertEquals(1, recCancel.stopCalls)
        assertEquals(0, wsCancel.sentBinary.size, "aborted stop tail must not be sent")
        assertEquals(listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""), wsCancel.sentText.toList())
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_USER_CANCEL), wsCancel.closeEvents)

        // 2. New start() during post-roll awaits previous recorder stop BEFORE reacquiring mic.
        val events = mutableListOf<String>()
        val stopGate = CompletableDeferred<Unit>()
        val wsFirst = FakeDictationWebSocketSession()
        val wsSecond = FakeDictationWebSocketSession()
        var wsIdx = 0
        val sharedRecorder =
            object : RaftVoiceDictationAudioRecorder {
                private var onChunk: ((ByteArray) -> Unit)? = null
                var activeStarts = 0

                override suspend fun start(
                    onPcmChunk: (ByteArray) -> Unit,
                    onAudioLevel: (Float) -> Unit,
                ): VoiceDictationAudioStartResult {
                    activeStarts += 1
                    events += "start#$activeStarts"
                    onChunk = onPcmChunk
                    return VoiceDictationAudioStartResult.Started
                }

                override suspend fun stop() {
                    events += "stop-begin#$activeStarts"
                    if (activeStarts == 1) {
                        stopGate.await()
                        onChunk?.invoke(byteArrayOf(77, 78))
                    }
                    events += "stop-end#$activeStarts"
                    onChunk = null
                }
            }
        val cRestart =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> if (wsIdx++ == 0) wsFirst else wsSecond },
                scope = backgroundScope,
                audioRecorderProvider = { sharedRecorder },
            )
        cRestart.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsFirst.pushText("""{"deck":"ready"}""")
        runCurrent()
        cRestart.stop()
        advanceTimeBy(100L)
        runCurrent()

        cRestart.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        assertEquals(listOf("start#1", "stop-begin#1"), events, "new start must wait for prior recorder release")
        stopGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("start#1", "stop-begin#1", "stop-end#1", "start#2"), events)
        assertEquals(0, wsFirst.sentBinary.size)
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_SUPERSEDED), wsFirst.closeEvents)
        assertEquals(VoiceDictationV2Phase.Listening, cRestart.state.value.phase)
        cRestart.cancel()
        runCurrent()

        // 3. finishForSubmit(), onOwnerChanged(), and server done during post-roll all release recorder immediately.
        val wsSubmit = FakeDictationWebSocketSession()
        val recSubmit = FakeDictationAudioRecorder()
        val cSubmit =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsSubmit },
                scope = backgroundScope,
                audioRecorderProvider = { recSubmit },
            )
        cSubmit.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsSubmit.pushText("""{"deck":"ready"}""")
        runCurrent()
        cSubmit.stop()
        advanceTimeBy(50L)
        runCurrent()
        cSubmit.finishForSubmit()
        runCurrent()
        assertEquals(1, recSubmit.stopCalls)
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_SUBMIT), wsSubmit.closeEvents)

        val wsOwner = FakeDictationWebSocketSession()
        val recOwner = FakeDictationAudioRecorder()
        val cOwner =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsOwner },
                scope = backgroundScope,
                audioRecorderProvider = { recOwner },
            )
        cOwner.start(serverId = "srv-1", channelId = validChannelUuid, onComposerUpdate = {})
        runCurrent()
        wsOwner.pushText("""{"deck":"ready"}""")
        runCurrent()
        cOwner.stop()
        advanceTimeBy(50L)
        runCurrent()
        cOwner.onOwnerChanged(serverId = "srv-2", channelId = validChannelUuid)
        runCurrent()
        assertEquals(1, recOwner.stopCalls)
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_OWNER_CHANGED), wsOwner.closeEvents)

        val wsDone = FakeDictationWebSocketSession()
        val recDone = FakeDictationAudioRecorder()
        val cDone =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsDone },
                scope = backgroundScope,
                audioRecorderProvider = { recDone },
            )
        cDone.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsDone.pushText("""{"deck":"ready"}""")
        runCurrent()
        cDone.stop()
        advanceTimeBy(50L)
        runCurrent()
        wsDone.pushText("""{"deck":"done"}""")
        runCurrent()
        assertEquals(1, recDone.stopCalls)
        assertFalse(cDone.state.value.hasBackgroundDrain)
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_DONE), wsDone.closeEvents)

        // 4. Explicit stopPostRollMs = 0L stops immediately while still flushing recorder stop tail.
        val wsZero = FakeDictationWebSocketSession()
        val tailZero = byteArrayOf(11, 22)
        val recZero = FakeDictationAudioRecorder(onStopTailChunks = listOf(tailZero))
        val cZero =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsZero },
                scope = backgroundScope,
                audioRecorderProvider = { recZero },
                stopPostRollMs = 0L,
            )
        cZero.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsZero.pushText("""{"deck":"ready"}""")
        runCurrent()
        cZero.stop()
        runCurrent()
        assertEquals(1, recZero.stopCalls)
        assertEquals(1, wsZero.sentBinary.size)
        assertContentEquals(tailZero, wsZero.sentBinary.single())
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            wsZero.sentText.toList(),
        )
        cZero.cancel()
        runCurrent()

        // 5. Blur stop skips 300 ms button post-roll immediately for instant privacy teardown.
        val wsBlur = FakeDictationWebSocketSession()
        val recBlur = FakeDictationAudioRecorder()
        val cBlur =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsBlur },
                scope = backgroundScope,
                audioRecorderProvider = { recBlur },
            )
        cBlur.start(serverId = "srv-1", onComposerUpdate = {})
        runCurrent()
        wsBlur.pushText("""{"deck":"ready"}""")
        runCurrent()
        cBlur.stop(VoiceDictationStopReason.Blur)
        runCurrent()
        assertEquals(1, recBlur.stopCalls, "blur stop must skip 300 ms button post-roll")
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            wsBlur.sentText.toList(),
        )
        cBlur.cancel()
        runCurrent()
    }

    @Test
    fun controllerAwaitPendingFinalBeforeSendFlushesActiveSessionWithZeroPostRollAndBounded1sTimeout() = runTest {
        // 1. Connecting (mic not yet live) -> needsSubmitFinalWait() is false; once Listening -> true;
        //    after manual edit -> false.
        val micGate = CompletableDeferred<VoiceDictationAudioStartResult>()
        val ws1 = FakeDictationWebSocketSession()
        val tail1 = byteArrayOf(21, 22, 23, 24)
        val rec1 = FakeDictationAudioRecorder(startGate = micGate, onStopTailChunks = listOf(tail1))
        val updates1 = mutableListOf<VoiceDictationComposerUpdate>()
        val c1 =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> ws1 },
                scope = backgroundScope,
                audioRecorderProvider = { rec1 },
            )
        c1.start(serverId = "srv-1", baseText = "", onComposerUpdate = { updates1 += it })
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Connecting, c1.state.value.phase)
        assertFalse(c1.needsSubmitFinalWait(), "Connecting before mic starts must not block Send")

        micGate.complete(VoiceDictationAudioStartResult.Started)
        runCurrent()
        ws1.pushText("""{"deck":"ready"}""")
        ws1.pushText("""{"deck":"text","text":"Ship this","stable_len":9}""")
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, c1.state.value.phase)
        assertTrue(c1.needsSubmitFinalWait(), "live Listening session must wait briefly on Send for final tail")

        // Sending while actively listening immediately stops mic with 0 ms post-roll, flushes native
        // tail chunk before WS stop, and waits up to 1 s for the confirmed post-stop snapshot.
        val wait1 = async { c1.awaitPendingFinalBeforeSend() }
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, c1.state.value.phase)
        assertTrue(c1.state.value.hasBackgroundDrain)
        assertTrue(c1.state.value.awaitingSubmitFinal)
        assertEquals(1, rec1.stopCalls, "Send-while-active must stop recorder with 0 ms post-roll")
        assertEquals(1, ws1.sentBinary.size)
        assertContentEquals(tail1, ws1.sentBinary.single())
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            ws1.sentText.toList(),
        )
        assertFalse(wait1.isCompleted)

        // Confirmed post-stop snapshot with final word resolves wait immediately with true.
        ws1.pushText("""{"deck":"text","text":"Ship this now.","stable_len":14}""")
        runCurrent()
        assertTrue(wait1.await())
        assertEquals("Ship this now.", updates1.last().composerText)
        assertFalse(c1.state.value.awaitingSubmitFinal)
        c1.finishForSubmit()
        runCurrent()

        // 2. Manual user edit while actively listening disables submit wait immediately.
        val wsEdit = FakeDictationWebSocketSession()
        val cEdit =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsEdit },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cEdit.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsEdit.pushText("""{"deck":"ready"}""")
        wsEdit.pushText("""{"deck":"text","text":"Draft","stable_len":5}""")
        runCurrent()
        assertTrue(cEdit.needsSubmitFinalWait())
        cEdit.onUserEditedComposer("Draft edited")
        assertFalse(cEdit.needsSubmitFinalWait())
        assertTrue(cEdit.awaitPendingFinalBeforeSend())
        cEdit.finishForSubmit()
        runCurrent()

        // 3. Server stalls after active-Send stop -> times out cleanly at 1,000 ms (not 1,300 ms) and returns true.
        val wsTimeout = FakeDictationWebSocketSession()
        val recTimeout = FakeDictationAudioRecorder()
        val cTimeout =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsTimeout },
                scope = backgroundScope,
                audioRecorderProvider = { recTimeout },
            )
        cTimeout.start(serverId = "srv-1", baseText = "", onComposerUpdate = {})
        runCurrent()
        wsTimeout.pushText("""{"deck":"ready"}""")
        wsTimeout.pushText("""{"deck":"text","text":"Still waiting","stable_len":13}""")
        runCurrent()

        val waitTimeout = async { cTimeout.awaitPendingFinalBeforeSend() }
        runCurrent()
        assertEquals(1, recTimeout.stopCalls)
        assertTrue(cTimeout.state.value.awaitingSubmitFinal)

        advanceTimeBy(VOICE_DICTATION_DEFAULT_SUBMIT_FINAL_WAIT_MS - 1L)
        runCurrent()
        assertFalse(waitTimeout.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(waitTimeout.await())
        assertFalse(cTimeout.state.value.awaitingSubmitFinal)
        cTimeout.finishForSubmit()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // 12. Option A gesture-settled v2 `start.mode: "hold" | "tap"` (#wg-call:#16)
    // -------------------------------------------------------------------------

    @Test
    fun controllerOptionADeferredStartModeSettlesHoldTapStopAndCancelWithZeroLostAudio() = runTest {
        // 1. deferStartMode = true: mic + permit + WS connect immediately in parallel while ONLY
        //    v2 `start` waits for mode settlement; pre-start PCM buffers and flushes in exact FIFO
        //    order once `setSessionMode(Hold)` sends `start` and server sends `ready`.
        val wsHold = FakeDictationWebSocketSession()
        val recHold = FakeDictationAudioRecorder()
        val permitApiHold = FakeDictationPermitApi(defaultPermitOutcome())
        var holdConnectCalls = 0
        val cHold =
            VoiceDictationV2Controller(
                permitApi = permitApiHold,
                streamConnector =
                    VoiceDictationV2StreamClient { _, _ ->
                        holdConnectCalls += 1
                        wsHold
                    },
                scope = backgroundScope,
                audioRecorderProvider = { recHold },
                stopPostRollMs = 0L,
            )

        cHold.start(serverId = "srv-1", deferStartMode = true, onComposerUpdate = {})
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Listening, cHold.state.value.phase)
        assertEquals(1, recHold.startCalls)
        assertEquals(1, permitApiHold.calls.size, "permit fetch must start immediately without waiting for hold threshold")
        assertEquals(1, holdConnectCalls, "WS connect must start immediately without waiting for hold threshold")
        assertTrue(wsHold.sentText.isEmpty(), "v2 start frame must wait while gesture is unsettled")

        val preStart1 = byteArrayOf(1, 2, 3)
        val preStart2 = byteArrayOf(4, 5, 6)
        recHold.emitChunk(preStart1)
        recHold.emitChunk(preStart2)
        runCurrent()
        assertTrue(wsHold.sentBinary.isEmpty())

        cHold.setSessionMode(VoiceDictationSessionMode.Hold)
        runCurrent()
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"hold"}"""),
            wsHold.sentText.toList(),
        )

        wsHold.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(2, wsHold.sentBinary.size, "pre-start PCM buffered during hold threshold must flush on ready")
        assertContentEquals(preStart1, wsHold.sentBinary[0])
        assertContentEquals(preStart2, wsHold.sentBinary[1])
        cHold.cancel()
        runCurrent()

        // 2. deferStartMode = true settled to Tap BEFORE WS connect finishes -> sends immediately on connect (0 ms wait).
        val wsGate = CompletableDeferred<RaftWebSocketSession>()
        val wsTapEarly = FakeDictationWebSocketSession()
        val cTapEarly =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsGate.await() },
                scope = backgroundScope,
                audioRecorderProvider = { FakeDictationAudioRecorder() },
            )
        cTapEarly.start(serverId = "srv-1", deferStartMode = true, onComposerUpdate = {})
        runCurrent()
        cTapEarly.setSessionMode(VoiceDictationSessionMode.Tap)
        wsGate.complete(wsTapEarly)
        runCurrent()
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""),
            wsTapEarly.sentText.toList(),
        )
        cTapEarly.cancel()
        runCurrent()

        // 3. stop() while still pending-hold settles cleanly to "tap" (never hangs!), flushes buffered PCM, and sends stop.
        val wsStopPending = FakeDictationWebSocketSession()
        val recStopPending = FakeDictationAudioRecorder()
        val cStopPending =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsStopPending },
                scope = backgroundScope,
                audioRecorderProvider = { recStopPending },
                stopPostRollMs = 0L,
            )
        cStopPending.start(serverId = "srv-1", deferStartMode = true, onComposerUpdate = {})
        runCurrent()
        assertTrue(wsStopPending.sentText.isEmpty())
        val pendingAudio = byteArrayOf(7, 8, 9)
        recStopPending.emitChunk(pendingAudio)

        cStopPending.stop(VoiceDictationStopReason.Button)
        runCurrent()
        assertEquals(VoiceDictationV2Phase.Idle, cStopPending.state.value.phase)
        assertEquals(1, recStopPending.stopCalls)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""),
            wsStopPending.sentText.toList(),
        )
        wsStopPending.pushText("""{"deck":"ready"}""")
        runCurrent()
        assertEquals(1, wsStopPending.sentBinary.size)
        assertContentEquals(pendingAudio, wsStopPending.sentBinary.single())
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}""", """{"deck":"stop"}"""),
            wsStopPending.sentText.toList(),
        )
        wsStopPending.pushText("""{"deck":"done"}""")
        runCurrent()
        assertFalse(cStopPending.state.value.hasBackgroundDrain)

        // 4. cancel() while pending-hold aborts cleanly WITHOUT sending `start` and releases mic + socket.
        val wsCancelPending = FakeDictationWebSocketSession()
        val recCancelPending = FakeDictationAudioRecorder()
        val cCancelPending =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsCancelPending },
                scope = backgroundScope,
                audioRecorderProvider = { recCancelPending },
            )
        cCancelPending.start(serverId = "srv-1", deferStartMode = true, onComposerUpdate = {})
        runCurrent()
        assertTrue(wsCancelPending.sentText.isEmpty())
        cCancelPending.cancel()
        runCurrent()
        cCancelPending.setSessionMode(VoiceDictationSessionMode.Hold)
        runCurrent()
        assertTrue(wsCancelPending.sentText.isEmpty(), "cancelled pending-hold session must never send start")
        assertEquals(1, recCancelPending.stopCalls)
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_USER_CANCEL), wsCancelPending.closeEvents)

        // 5. onOwnerChanged() while pending-hold aborts cleanly WITHOUT sending `start`.
        val wsOwnerPending = FakeDictationWebSocketSession()
        val recOwnerPending = FakeDictationAudioRecorder()
        val cOwnerPending =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector = VoiceDictationV2StreamClient { _, _ -> wsOwnerPending },
                scope = backgroundScope,
                audioRecorderProvider = { recOwnerPending },
            )
        cOwnerPending.start(
            serverId = "srv-1",
            channelId = validChannelUuid,
            deferStartMode = true,
            onComposerUpdate = {},
        )
        runCurrent()
        assertTrue(wsOwnerPending.sentText.isEmpty())
        cOwnerPending.onOwnerChanged(serverId = "srv-1", channelId = "660e8400-e29b-41d4-a716-446655440000")
        runCurrent()
        assertTrue(wsOwnerPending.sentText.isEmpty(), "owner-changed pending-hold session must never send start")
        assertEquals(1, recOwnerPending.stopCalls)
        assertEquals(listOf(1000 to VOICE_DICTATION_CLOSE_REASON_OWNER_CHANGED), wsOwnerPending.closeEvents)
    }

    @Test
    fun buttonGestureOptionASettlesShortTapHoldPermissionCancelAndKeyboardModesEndToEnd() = runTest {
        val sockets = mutableListOf<FakeDictationWebSocketSession>()
        val recorder = FakeDictationAudioRecorder()
        val controller =
            VoiceDictationV2Controller(
                permitApi = FakeDictationPermitApi(defaultPermitOutcome()),
                streamConnector =
                    VoiceDictationV2StreamClient { _, _ ->
                        FakeDictationWebSocketSession().also { sockets += it }
                    },
                scope = backgroundScope,
                audioRecorderProvider = { recorder },
                stopPostRollMs = 0L,
            )

        var gestureRef: VoiceDictationButtonGesture? = null
        val gesture =
            VoiceDictationButtonGesture(
                scope = backgroundScope,
                isActive = { controller.state.value.isActive },
                onStart = {
                    val deferStartMode = gestureRef?.mode == VoiceDictationGestureMode.PendingHold
                    controller.start(
                        serverId = "srv-1",
                        deferStartMode = deferStartMode,
                        onComposerUpdate = {},
                    )
                },
                onStop = { controller.stop(VoiceDictationStopReason.Button) },
                onModeChange = { nextMode ->
                    when (nextMode) {
                        VoiceDictationGestureMode.Holding -> controller.setSessionMode(VoiceDictationSessionMode.Hold)
                        VoiceDictationGestureMode.Locked -> controller.setSessionMode(VoiceDictationSessionMode.Tap)
                        else -> Unit
                    }
                },
            ).also { gestureRef = it }

        // 1. Short touch tap (< 350 ms): WS connects on down, start waits, up settles to "tap" and stays Locked.
        assertTrue(gesture.handlePointerDown(pointerId = 1L))
        runCurrent()
        assertEquals(1, sockets.size)
        assertTrue(sockets[0].sentText.isEmpty(), "start waits during PendingHold")
        advanceTimeBy(120L)
        runCurrent()
        assertTrue(sockets[0].sentText.isEmpty())
        gesture.handlePointerUp(pointerId = 1L)
        runCurrent()
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""),
            sockets[0].sentText.toList(),
        )
        sockets[0].pushText("""{"deck":"ready"}""")
        sockets[0].pushText("""{"deck":"done"}""")
        runCurrent()
        gesture.syncSessionInactive()

        // 2. Press-and-hold (>= 350 ms): WS connects on down, holds start until 350 ms -> sends "hold".
        assertTrue(gesture.handlePointerDown(pointerId = 2L))
        runCurrent()
        assertEquals(2, sockets.size)
        assertTrue(sockets[1].sentText.isEmpty())
        advanceTimeBy(VOICE_DICTATION_HOLD_THRESHOLD_MS - 1L)
        runCurrent()
        assertTrue(sockets[1].sentText.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(VoiceDictationGestureMode.Holding, gesture.mode)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"hold"}"""),
            sockets[1].sentText.toList(),
        )
        gesture.handlePointerUp(pointerId = 2L)
        runCurrent()
        sockets[1].pushText("""{"deck":"done"}""")
        runCurrent()
        gesture.syncSessionInactive()

        // 3. Pointer cancel during PendingHold (OS mic permission dialog) locks and settles to "tap".
        assertTrue(gesture.handlePointerDown(pointerId = 3L))
        runCurrent()
        assertEquals(3, sockets.size)
        assertTrue(sockets[2].sentText.isEmpty())
        gesture.handlePointerCancel(pointerId = 3L)
        runCurrent()
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""),
            sockets[2].sentText.toList(),
        )
        controller.cancel()
        runCurrent()
        gesture.syncSessionInactive()

        // 4. Keyboard click (handleClick): non-deferred start settles synchronously to "tap" (0 ms wait).
        advanceTimeBy(VOICE_DICTATION_CLICK_SUPPRESSION_RESET_MS)
        runCurrent()
        gesture.handleClick()
        runCurrent()
        assertEquals(4, sockets.size)
        assertEquals(VoiceDictationGestureMode.Locked, gesture.mode)
        assertEquals(
            listOf("""{"deck":"start","v":2,"sample_rate":16000,"mode":"tap"}"""),
            sockets[3].sentText.toList(),
        )
        controller.cancel()
        runCurrent()
        gesture.dispose()
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private fun defaultPermitOutcome(
        quotaDegraded: Boolean = false,
        sessionId: String? = null,
        traceId: String? = null,
        streamUrlSource: VoiceStreamUrlSource? = null,
    ): VoiceDictationV2PermitOutcome =
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
                            resetsAtEpochMs = 1_795_737_600_000L,
                        ),
                    quotaDegraded = quotaDegraded,
                    sessionId = sessionId,
                    traceId = traceId,
                    streamUrlSource = streamUrlSource,
                ),
        )

    private class FakeDictationNetworkClient : RaftNetworkClient {
        var lastPath: String? = null
        var lastMethod: String? = null
        var lastBodyText: String? = null
        var lastServerIdOverride: ServerId? = null
        var nextResponse: HomeNetworkResponse = HomeNetworkResponse(code = 201, bodyText = "{}")
        var responseProvider: ((attempt: Int, bodyText: String?) -> HomeNetworkResponse)? = null
        val sentBodies = mutableListOf<String?>()

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
            sentBodies += bodyText
            return responseProvider?.invoke(sentBodies.size, bodyText) ?: nextResponse
        }
    }

    private data class RecordedPermitContextCall(
        val serverId: String,
        val language: String?,
        val channelId: String?,
        val draftBefore: String?,
        val contactHints: List<String>?,
    )

    private class FakeDictationPermitApi(
        var nextOutcome: VoiceDictationV2PermitOutcome,
        private val gate: CompletableDeferred<VoiceDictationV2PermitOutcome>? = null,
    ) : VoiceDictationV2PermitApi {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        val contextCalls = mutableListOf<RecordedPermitContextCall>()

        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
        ): VoiceDictationV2PermitOutcome =
            requestPermit(
                serverId = serverId,
                language = language,
                channelId = channelId,
                draftBefore = null,
                contactHints = null,
            )

        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
            draftBefore: String?,
            contactHints: List<String>?,
        ): VoiceDictationV2PermitOutcome {
            calls += Triple(serverId, language, channelId)
            contextCalls +=
                RecordedPermitContextCall(
                    serverId = serverId,
                    language = language,
                    channelId = channelId,
                    draftBefore = draftBefore,
                    contactHints = contactHints,
                )
            return gate?.await() ?: nextOutcome
        }
    }

    private class FakeDictationAudioRecorder(
        override val sampleRateHz: Int = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ,
        private val startResult: VoiceDictationAudioStartResult = VoiceDictationAudioStartResult.Started,
        private val startGate: CompletableDeferred<VoiceDictationAudioStartResult>? = null,
        private val onStopTailChunks: List<ByteArray> = emptyList(),
    ) : RaftVoiceDictationAudioRecorder {
        var startCalls: Int = 0
        var stopCalls: Int = 0
        private var chunkCallback: ((ByteArray) -> Unit)? = null
        private var lastChunkCallback: ((ByteArray) -> Unit)? = null
        private var levelCallback: ((Float) -> Unit)? = null

        override suspend fun start(
            onPcmChunk: (ByteArray) -> Unit,
            onAudioLevel: (Float) -> Unit,
        ): VoiceDictationAudioStartResult {
            startCalls += 1
            val resolved = startGate?.await() ?: startResult
            if (resolved is VoiceDictationAudioStartResult.Started) {
                chunkCallback = onPcmChunk
                lastChunkCallback = onPcmChunk
                levelCallback = onAudioLevel
            }
            return resolved
        }

        override suspend fun stop() {
            stopCalls += 1
            for (tail in onStopTailChunks) {
                chunkCallback?.invoke(tail)
            }
            chunkCallback = null
            levelCallback = null
        }

        fun emitChunk(bytes: ByteArray) {
            chunkCallback?.invoke(bytes)
        }

        fun emitLateChunkAfterStop(bytes: ByteArray) {
            lastChunkCallback?.invoke(bytes)
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
        var lastCloseCode: Int? = null
        var lastCloseReason: String? = null
        val closeEvents = mutableListOf<Pair<Int, String>>()

        override suspend fun send(text: String) {
            sentText += text
        }

        override suspend fun sendBinary(bytes: ByteArray) {
            sentBinary += bytes.copyOf()
        }

        override suspend fun close(code: Int, reason: String) {
            closeCalls += 1
            lastCloseCode = code
            lastCloseReason = reason
            closeEvents += code to reason
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
