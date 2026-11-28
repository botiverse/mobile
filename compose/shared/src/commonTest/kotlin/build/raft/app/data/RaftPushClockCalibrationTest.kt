@file:OptIn(ExperimentalCoroutinesApi::class, RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Push delivery clock calibration contract (`#proj-mobile:#405`):
 * - `clock_offset_ms = (t0 + (t1 - t0) / 2) - server_ms`
 * - `clock_rtt_ms = t1 - t0`
 * - `clock_source` = `"server_time"` (preferred) or `"http_date"` (RFC 7231 IMF-fixdate fallback)
 * - Out-of-range (`|offset| > 24h`) or backwards wall clock (`t1 < t0`) omits all three fields
 *   (never clamped).
 * - Only `mobile.push.received` / `mobile.push.presented` / `mobile.push.opened` are enriched.
 */
class RaftPushClockCalibrationTest {
    private val testJson = Json { ignoreUnknownKeys = true }

    private val pushEventAttrs =
        buildJsonObject {
            put("delivery_id", "550e8400-e29b-41d4-a716-446655440000")
            put("transport", "fcm")
            put("occurred_at_ms", 1_795_852_310_000L)
        }

    private fun assertNoClockAttrs(record: RaftWebEventRecord, label: String) {
        assertFalse(RaftPushClockOffsetAttr in record.attrs, "$label: clock_offset_ms must be omitted")
        assertFalse(RaftPushClockRttAttr in record.attrs, "$label: clock_rtt_ms must be omitted")
        assertFalse(RaftPushClockSourceAttr in record.attrs, "$label: clock_source must be omitted")
    }

    @Test
    fun parseHttpDateEpochMillisHandlesValidImfFixdateLeapYearsAndYearBoundaries() {
        assertEquals(0L, raftParseHttpDateEpochMillis("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(951_868_799_000L, raftParseHttpDateEpochMillis("Tue, 29 Feb 2000 23:59:59 GMT"))
        assertEquals(1_709_210_096_000L, raftParseHttpDateEpochMillis("Thu, 29 Feb 2024 12:34:56 GMT"))
        assertEquals(1_794_391_200_000L, raftParseHttpDateEpochMillis("Wed, 11 Nov 2026 10:00:00 GMT"))
        assertEquals(1_795_852_316_000L, raftParseHttpDateEpochMillis("Sat, 28 Nov 2026 07:51:56 GMT"))
        assertEquals(1_795_852_316_000L, raftParseHttpDateEpochMillis("  Sat, 28 Nov 2026 07:51:56 GMT  "))
        assertEquals(1_798_761_599_000L, raftParseHttpDateEpochMillis("Thu, 31 Dec 2026 23:59:59 GMT"))
        assertEquals(1_798_761_600_000L, raftParseHttpDateEpochMillis("Fri, 01 Jan 2027 00:00:00 GMT"))
        assertEquals(4_102_444_799_000L, raftParseHttpDateEpochMillis("Thu, 31 Dec 2099 23:59:59 GMT"))
    }

    @Test
    fun parseHttpDateEpochMillisRejectsInvalidDatesBoundsTimezonesAndGarbage() {
        val invalid =
            listOf(
                "",
                "   ",
                "garbage",
                "2026-11-28T07:51:56Z",
                // Non-leap Feb 29 (standard non-leap and century non-leap 2100).
                "Sun, 29 Feb 2026 12:34:56 GMT",
                "Mon, 29 Feb 2100 12:34:56 GMT",
                // Invalid days per month / zero day / 1-digit day.
                "Thu, 31 Apr 2026 00:00:00 GMT",
                "Tue, 31 Jun 2026 00:00:00 GMT",
                "Wed, 31 Sep 2026 00:00:00 GMT",
                "Mon, 31 Nov 2026 00:00:00 GMT",
                "Thu, 00 Jan 2026 00:00:00 GMT",
                "Sun, 32 Jan 2026 00:00:00 GMT",
                "Sat, 8 Nov 2026 07:51:56 GMT",
                // Bad month or weekday.
                "Sat, 28 Foo 2026 07:51:56 GMT",
                "Sat, 28 nov 2026 07:51:56 GMT",
                "Sxt, 28 Nov 2026 07:51:56 GMT",
                "sat, 28 Nov 2026 07:51:56 GMT",
                // Out-of-bounds hour / minute / second.
                "Sat, 28 Nov 2026 24:00:00 GMT",
                "Sat, 28 Nov 2026 07:60:00 GMT",
                "Sat, 28 Nov 2026 07:51:60 GMT",
                "Sat, 28 Nov 2026 07:51:99 GMT",
                // Non-GMT timezone or missing timezone.
                "Sat, 28 Nov 2026 07:51:56 UTC",
                "Sat, 28 Nov 2026 07:51:56 +0000",
                "Sat, 28 Nov 2026 07:51:56 PST",
                "Sat, 28 Nov 2026 07:51:56",
                // Pre-epoch year.
                "Wed, 31 Dec 1969 23:59:59 GMT",
            )
        for (raw in invalid) {
            assertNull(raftParseHttpDateEpochMillis(raw), "expected null for '$raw'")
        }
    }

    @Test
    fun computeCalibrationPrefersServerTimeComputesSignedOffsetsWithMidpointAndFallsBackToHttpDate() {
        // 2026-11-28T07:51:56.450Z
        val serverIsoMs = 1_795_852_316_450L
        val serverIso = "2026-11-28T07:51:56.450Z"
        // Deliberately different HTTP Date (2026-11-28T07:51:56Z) to prove serverTime wins.
        val httpDate = "Sat, 28 Nov 2026 07:51:56 GMT"
        val httpDateMs = 1_795_852_316_000L

        // Phone lags server by 4000ms; RTT = 200ms -> clientMid = serverIsoMs - 4000ms.
        val lagging =
            raftComputePushClockCalibration(
                t0Millis = serverIsoMs - 4_100L,
                t1Millis = serverIsoMs - 3_900L,
                serverTimeIso = serverIso,
                httpDateHeader = httpDate,
            )
        assertEquals(
            RaftClientClockCalibration(offsetMs = -4_000L, rttMs = 200L, source = RaftPushClockSource.ServerTime),
            lagging,
        )
        // Server-side query recovers server time via `occurred_at_ms - clock_offset_ms`.
        assertEquals(serverIsoMs, (serverIsoMs - 4_000L) - lagging!!.offsetMs)

        // Phone leads server by +2500ms; RTT = 100ms -> clientMid = serverIsoMs + 2500ms.
        val leading =
            raftComputePushClockCalibration(
                t0Millis = serverIsoMs + 2_450L,
                t1Millis = serverIsoMs + 2_550L,
                serverTimeIso = serverIso,
                httpDateHeader = httpDate,
            )
        assertEquals(
            RaftClientClockCalibration(offsetMs = 2_500L, rttMs = 100L, source = RaftPushClockSource.ServerTime),
            leading,
        )

        // Zero offset with odd RTT (integer division midpoint: t0 + 3/2 = t0 + 1).
        val zeroOffset =
            raftComputePushClockCalibration(
                t0Millis = serverIsoMs - 1L,
                t1Millis = serverIsoMs + 2L,
                serverTimeIso = serverIso,
                httpDateHeader = null,
            )
        assertEquals(
            RaftClientClockCalibration(offsetMs = 0L, rttMs = 3L, source = RaftPushClockSource.ServerTime),
            zeroOffset,
        )

        // Fallback to HTTP Date when serverTime is absent or unparseable.
        for (missingServerTime in listOf(null, "", "   ", "not-a-date")) {
            val fallback =
                raftComputePushClockCalibration(
                    t0Millis = httpDateMs - 1_250L,
                    t1Millis = httpDateMs - 1_150L,
                    serverTimeIso = missingServerTime,
                    httpDateHeader = httpDate,
                )
            assertEquals(
                RaftClientClockCalibration(offsetMs = -1_200L, rttMs = 100L, source = RaftPushClockSource.HttpDate),
                fallback,
                "serverTimeIso=$missingServerTime",
            )
        }

        // Neither valid -> null.
        assertNull(
            raftComputePushClockCalibration(
                t0Millis = serverIsoMs,
                t1Millis = serverIsoMs + 50L,
                serverTimeIso = "bad",
                httpDateHeader = "bad",
            )
        )
    }

    @Test
    fun computeCalibrationEnforcesExact24HourBoundsWithoutClampingAndRejectsBackwardsClock() {
        val serverMs = 1_795_852_316_000L
        val serverIso = "2026-11-28T07:51:56Z"
        val httpDate = "Sat, 28 Nov 2026 07:51:56 GMT"

        assertEquals(86_400_000L, RaftPushClockOffsetMaxAbsMillis)

        // Exact positive and negative 24h boundaries are accepted.
        val maxPos =
            raftComputePushClockCalibration(
                t0Millis = serverMs + RaftPushClockOffsetMaxAbsMillis - 50L,
                t1Millis = serverMs + RaftPushClockOffsetMaxAbsMillis + 50L,
                serverTimeIso = serverIso,
                httpDateHeader = null,
            )
        assertEquals(
            RaftClientClockCalibration(offsetMs = 86_400_000L, rttMs = 100L, source = RaftPushClockSource.ServerTime),
            maxPos,
        )

        val maxNeg =
            raftComputePushClockCalibration(
                t0Millis = serverMs - RaftPushClockOffsetMaxAbsMillis - 20L,
                t1Millis = serverMs - RaftPushClockOffsetMaxAbsMillis + 20L,
                serverTimeIso = serverIso,
                httpDateHeader = null,
            )
        assertEquals(
            RaftClientClockCalibration(offsetMs = -86_400_000L, rttMs = 40L, source = RaftPushClockSource.ServerTime),
            maxNeg,
        )

        // One millisecond outside ±24h returns null (never clamped, never falls back to httpDate).
        assertNull(
            raftComputePushClockCalibration(
                t0Millis = serverMs + RaftPushClockOffsetMaxAbsMillis + 1L,
                t1Millis = serverMs + RaftPushClockOffsetMaxAbsMillis + 1L,
                serverTimeIso = serverIso,
                httpDateHeader = httpDate,
            )
        )
        assertNull(
            raftComputePushClockCalibration(
                t0Millis = serverMs - RaftPushClockOffsetMaxAbsMillis - 1L,
                t1Millis = serverMs - RaftPushClockOffsetMaxAbsMillis - 1L,
                serverTimeIso = serverIso,
                httpDateHeader = httpDate,
            )
        )

        // Backwards wall clock during request (t1 < t0) returns null.
        assertNull(
            raftComputePushClockCalibration(
                t0Millis = serverMs + 100L,
                t1Millis = serverMs + 99L,
                serverTimeIso = serverIso,
                httpDateHeader = httpDate,
            )
        )
    }

    @Test
    fun senderEnrichesOnlyPushDeliveryEventsWithServerTimeAndHttpDateFallback() = runTest {
        val serverMs = 1_795_852_316_000L
        var wallClockMs = serverMs - 4_100L
        val batches = mutableListOf<RaftWebTraceBatchRequest>()
        var attestStepMs = 200L
        var responseServerTime: String? = "2026-11-28T07:51:56.000Z"
        var responseHttpDate: String? = "Sat, 28 Nov 2026 07:51:50 GMT"
        var attestCount = 0

        val transport =
            object : RaftClientTraceTransport {
                override suspend fun currentAccount(): String = "user-1"

                override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer {
                    attestCount += 1
                    wallClockMs += attestStepMs
                    return RaftScopeAttestationAnswer(
                        status = 200,
                        response =
                            RaftScopeAttestationResponse(
                                attestation = "att-$attestCount",
                                expiresAt = "2026-11-28T08:10:00Z",
                                serverTime = responseServerTime,
                            ),
                        httpDateHeader = responseHttpDate,
                    )
                }

                override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                    batches += batch
                    return 200
                }
            }

        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { wallClockMs },
                defaultAttrs = { mapOf("clientPlatform" to "android", "appVersion" to "1.32.0") },
                flushDelayMillis = 100L,
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                foreground = MutableStateFlow(true),
            )
        sender.bindTransport(transport)
        sender.setAccount("user-1")

        // Batch 1: mix all 3 push delivery events + non-push events + span.
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_RECEIVED, pushEventAttrs, occurredAtMillis = serverMs - 5_000L)
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_PRESENTED, pushEventAttrs, occurredAtMillis = serverMs - 4_500L)
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_OPENED, pushEventAttrs, occurredAtMillis = serverMs - 4_200L)
        sender.emit("server-1", RaftStateTransitionTraceName, buildJsonObject { put("step", "idle") })
        sender.emit("server-1", MOBILE_UI_ERROR_EVENT_NAME, buildJsonObject { put("surface", "home") })
        sender.emitSpan(
            serverId = "server-1",
            span =
                RaftWebSpanRecord(
                    traceId = "0af7651916cd43dd8448eb211c80319c",
                    spanId = "b7ad6b7169203331",
                    name = "mobile.realtime.session_health",
                    startTime = raftEpochMillisUtcIso(serverMs - 5_000L),
                    endTime = raftEpochMillisUtcIso(serverMs - 4_900L),
                    attrs = buildJsonObject { put("window_ms", 100) },
                ),
            events = emptyList(),
        )

        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, batches.size)
        assertEquals(1, attestCount)
        val batch1 = batches.single()
        assertEquals(5, batch1.events.size)
        for (i in 0..2) {
            val pushRecord = batch1.events[i]
            assertEquals(-4_000L, pushRecord.attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
            assertEquals(200L, pushRecord.attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
            assertEquals("server_time", pushRecord.attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)
            assertEquals("550e8400-e29b-41d4-a716-446655440000", pushRecord.attrs["delivery_id"]?.jsonPrimitive?.content)
            assertEquals("android", pushRecord.attrs["clientPlatform"]?.jsonPrimitive?.content)
        }
        assertNoClockAttrs(batch1.events[3], "slock.state.transition")
        assertNoClockAttrs(batch1.events[4], "mobile.ui_error")
        val span = batch1.records.single()
        assertFalse(RaftPushClockOffsetAttr in span.attrs)
        assertFalse(RaftPushClockRttAttr in span.attrs)
        assertFalse(RaftPushClockSourceAttr in span.attrs)

        // Batch 2: cached attestation reuse carries clockCalibration without re-attesting.
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_RECEIVED, pushEventAttrs, occurredAtMillis = serverMs - 3_000L)
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, attestCount, "cached attestation should be reused")
        assertEquals(2, batches.size)
        val batch2Record = batches[1].events.single()
        assertEquals("att-1", batches[1].attestation)
        assertEquals(-4_000L, batch2Record.attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(200L, batch2Record.attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
        assertEquals("server_time", batch2Record.attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)

        // Batch 3: expiry triggers re-attestation; absent serverTime falls back to HTTP Date header.
        // 2026-11-28T08:10:00Z = 1_795_853_400_000L (past expiry); t0 = +2450ms, t1 = +2550ms -> offset = +2500ms, rtt = 100ms.
        responseServerTime = null
        wallClockMs = 1_795_853_402_450L
        attestStepMs = 100L
        responseHttpDate = "Sat, 28 Nov 2026 08:10:00 GMT"
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_OPENED, pushEventAttrs, occurredAtMillis = wallClockMs)
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(2, attestCount, "expired attestation must trigger re-attestation")
        val batch3Record = batches[2].events.single()
        assertEquals("att-2", batches[2].attestation)
        assertEquals(2_500L, batch3Record.attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(100L, batch3Record.attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
        assertEquals("http_date", batch3Record.attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)
    }

    @Test
    fun senderOmitsAllThreeClockFieldsWhenOutOfRangeOrClockStepsBackwards() = runTest {
        val serverMs = 1_795_852_316_000L
        val serverIso = "2026-11-28T07:51:56Z"
        val cases =
            listOf(
                Triple("above +24h", serverMs + RaftPushClockOffsetMaxAbsMillis + 1L, 0L),
                Triple("below -24h", serverMs - RaftPushClockOffsetMaxAbsMillis - 1L, 0L),
                Triple("backwards wall clock", serverMs + 500L, -10L),
            )
        for ((label, startWallClockMs, attestDeltaMs) in cases) {
            var wallClockMs = startWallClockMs
            val batches = mutableListOf<RaftWebTraceBatchRequest>()
            val transport =
                object : RaftClientTraceTransport {
                    override suspend fun currentAccount(): String = "user-1"

                    override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer {
                        wallClockMs += attestDeltaMs
                        return RaftScopeAttestationAnswer(
                            status = 200,
                            response =
                                RaftScopeAttestationResponse(
                                    attestation = "att-1",
                                    expiresAt = "2099-01-01T00:00:00Z",
                                    serverTime = serverIso,
                                ),
                            httpDateHeader = "Sat, 28 Nov 2026 07:51:56 GMT",
                        )
                    }

                    override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                        batches += batch
                        return 200
                    }
                }
            val sender =
                RaftClientTraceEventSender(
                    scope = backgroundScope,
                    nowEpochMillis = { wallClockMs },
                    defaultAttrs = { mapOf("clientPlatform" to "ios") },
                    flushDelayMillis = 100L,
                    privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                    foreground = MutableStateFlow(true),
                )
            sender.bindTransport(transport)
            sender.setAccount("user-1")
            sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_RECEIVED, pushEventAttrs, occurredAtMillis = serverMs)

            advanceTimeBy(150L)
            runCurrent()

            assertEquals(1, batches.size, "$label: batch should still send")
            assertNoClockAttrs(batches.single().events.single(), label)
        }
    }

    @Test
    fun senderUsesCalibrationWhenExpiresAtNullRefreshesAfter401Or403AndDoesNotLeakStaleCalibrationAcross5xxRetries() = runTest {
        val serverMs = 1_795_852_316_000L
        var wallClockMs = serverMs - 4_100L
        var attestStepMs = 200L
        var sendStatus = 503
        var nextExpiresAt: String? = null
        var nextServerTime: String? = "2026-11-28T07:51:56.000Z"
        var nextHttpDate: String? = null
        var attestCount = 0
        val batches = mutableListOf<RaftWebTraceBatchRequest>()

        val transport =
            object : RaftClientTraceTransport {
                override suspend fun currentAccount(): String = "user-1"

                override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer {
                    attestCount += 1
                    wallClockMs += attestStepMs
                    return RaftScopeAttestationAnswer(
                        status = 200,
                        response =
                            RaftScopeAttestationResponse(
                                attestation = "att-$attestCount",
                                expiresAt = nextExpiresAt,
                                serverTime = nextServerTime,
                            ),
                        httpDateHeader = nextHttpDate,
                    )
                }

                override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
                    batches += batch
                    return sendStatus
                }
            }

        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { wallClockMs },
                defaultAttrs = { mapOf("clientPlatform" to "android") },
                flushDelayMillis = 100L,
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                foreground = MutableStateFlow(true),
            )
        sender.bindTransport(transport)
        sender.setAccount("user-1")

        // 1. Attempt 1: expiresAt == null still calibrates this batch; 503 puts original unmodified record back.
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_RECEIVED, pushEventAttrs, occurredAtMillis = serverMs - 5_000L)
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(1, batches.size)
        assertEquals(-4_000L, batches[0].events.single().attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(200L, batches[0].events.single().attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
        assertEquals("server_time", batches[0].events.single().attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)
        assertEquals(1, sender.queuedCount)

        // 2. Attempt 2 (retry after 4s): fresh attestation returns HTTP Date only -> updated calibration, no stale leak!
        wallClockMs = serverMs + 1_460L
        attestStepMs = 80L
        nextExpiresAt = "2099-01-01T00:00:00Z"
        nextServerTime = null
        nextHttpDate = "Sat, 28 Nov 2026 07:51:56 GMT"
        sendStatus = 503
        advanceTimeBy(RaftClientTraceRetryDelaysMillis[0])
        runCurrent()

        assertEquals(2, attestCount)
        assertEquals(2, batches.size)
        assertEquals(1_500L, batches[1].events.single().attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(80L, batches[1].events.single().attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
        assertEquals("http_date", batches[1].events.single().attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)

        // 3. Attempt 3 (retry after 15s): simulate cached attestation still valid on 5xx, then 200 succeeds.
        sendStatus = 200
        advanceTimeBy(RaftClientTraceRetryDelaysMillis[1])
        runCurrent()

        assertEquals(2, attestCount, "still-valid cached attestation is reused on 5xx retry")
        assertEquals(3, batches.size)
        assertEquals(1_500L, batches[2].events.single().attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(0, sender.queuedCount)

        // 4. 401 clears cached attestation and refreshes calibration to a new value; 403 clears it and omits when uncalibrated.
        sendStatus = 401
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_PRESENTED, pushEventAttrs, occurredAtMillis = serverMs)
        advanceTimeBy(150L)
        runCurrent()

        sendStatus = 200
        wallClockMs = serverMs - 2_000L
        attestStepMs = 0L
        nextExpiresAt = "2099-01-01T00:00:00Z"
        nextServerTime = "2026-11-28T07:51:56.000Z"
        nextHttpDate = null
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_OPENED, pushEventAttrs, occurredAtMillis = serverMs)
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(3, attestCount, "401 must invalidate cached attestation")
        assertEquals("att-3", batches.last().attestation)
        assertEquals(-2_000L, batches.last().events.single().attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(0L, batches.last().events.single().attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
        assertEquals("server_time", batches.last().events.single().attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)

        sendStatus = 403
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_PRESENTED, pushEventAttrs, occurredAtMillis = serverMs)
        advanceTimeBy(150L)
        runCurrent()

        sendStatus = 200
        wallClockMs = serverMs + 500L
        attestStepMs = 0L
        nextExpiresAt = null // Leave uncached so step 5 starts with a fresh attestation.
        nextServerTime = null
        nextHttpDate = null // No server time on refresh -> must omit clock attrs, not leak old ones.
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_OPENED, pushEventAttrs, occurredAtMillis = serverMs)
        advanceTimeBy(150L)
        runCurrent()

        assertEquals(4, attestCount, "403 must invalidate cached attestation")
        assertEquals("att-4", batches.last().attestation)
        assertNoClockAttrs(batches.last().events.single(), "after 403 refresh without time")

        // 5. Verify 5xx retry when refreshed attestation has NO valid calibration omits all three clock attrs.
        sendStatus = 503
        nextExpiresAt = null
        nextServerTime = "2026-11-28T07:51:56.000Z"
        wallClockMs = serverMs - 1_000L
        attestStepMs = 0L
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_RECEIVED, pushEventAttrs, occurredAtMillis = serverMs)
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(5, attestCount)
        assertEquals(-1_000L, batches.last().events.single().attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(1, sender.queuedCount)

        sendStatus = 200
        nextServerTime = null
        nextHttpDate = null
        advanceTimeBy(RaftClientTraceRetryDelaysMillis[0])
        runCurrent()
        assertEquals(6, attestCount)
        assertEquals(0, sender.queuedCount)
        assertNoClockAttrs(batches.last().events.single(), "5xx retry with uncalibrated fresh attestation")
        assertEquals("550e8400-e29b-41d4-a716-446655440000", batches.last().events.single().attrs["delivery_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun networkClientTraceTransportAttestParsesServerTimeAndCaseInsensitiveDateHeader() = runTest {
        var nextResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"attestation":"att-1","expiresAt":"2026-11-28T08:01:56Z","serverTime":"2026-11-28T07:51:56.123Z"}""",
                responseHeaders = mapOf("Date" to listOf("Sat, 28 Nov 2026 07:51:56 GMT")),
            )
        val postedBodies = mutableListOf<String>()
        val fakeClient =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse = nextResponse

                override suspend fun requestRawAbsoluteUrl(
                    priority: DataRequestPriority,
                    url: String,
                    method: String,
                    headers: Map<String, String>,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    bodyText?.let { postedBodies += it }
                    return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        val transport =
            RaftNetworkClientTraceTransport(
                networkClient = fakeClient,
                sessionAccount = { "user-1" },
                traceBaseUrl = "https://trace.example.test",
            )

        // 1. Both serverTime JSON and Date header present.
        val both = transport.attest(ServerId("server-1"))
        assertEquals(200, both.status)
        assertEquals("att-1", both.response?.attestation)
        assertEquals("2026-11-28T08:01:56Z", both.response?.expiresAt)
        assertEquals("2026-11-28T07:51:56.123Z", both.response?.serverTime)
        assertEquals("Sat, 28 Nov 2026 07:51:56 GMT", both.httpDateHeader)

        // 2. Lowercase "date" header with leading blank value and whitespace trimmed.
        nextResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"attestation":"att-2","expiresAt":"2026-11-28T08:01:56Z"}""",
                responseHeaders = mapOf("date" to listOf("   ", "  Sat, 28 Nov 2026 07:51:56 GMT  ")),
            )
        val lowerDate = transport.attest(ServerId("server-1"))
        assertNull(lowerDate.response?.serverTime)
        assertEquals("Sat, 28 Nov 2026 07:51:56 GMT", lowerDate.httpDateHeader)

        // 3. Uppercase "DATE" header.
        nextResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"attestation":"att-3","expiresAt":"2026-11-28T08:01:56Z"}""",
                responseHeaders = mapOf("DATE" to listOf("Sat, 28 Nov 2026 07:51:56 GMT")),
            )
        val upperDate = transport.attest(ServerId("server-1"))
        assertEquals("Sat, 28 Nov 2026 07:51:56 GMT", upperDate.httpDateHeader)

        // 4. Non-2xx response returns null response and null httpDateHeader.
        nextResponse =
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                responseHeaders = mapOf("Date" to listOf("Sat, 28 Nov 2026 07:51:56 GMT")),
            )
        val failed = transport.attest(ServerId("server-1"))
        assertEquals(503, failed.status)
        assertNull(failed.response)
        assertNull(failed.httpDateHeader)

        // 5. End-to-end wire serialization through RaftClientTraceEventSender + RaftNetworkClientTraceTransport.
        val serverMs = 1_795_852_316_000L
        nextResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"attestation":"att-e2e","expiresAt":"2099-01-01T00:00:00Z","serverTime":"2026-11-28T07:51:56.000Z"}""",
                responseHeaders = mapOf("Date" to listOf("Sat, 28 Nov 2026 07:51:50 GMT")),
            )
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { serverMs - 3_000L + currentTime },
                defaultAttrs = { mapOf("clientPlatform" to "android") },
                flushDelayMillis = 100L,
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                foreground = MutableStateFlow(true),
            )
        sender.bindTransport(transport)
        sender.setAccount("user-1")
        sender.emit("server-1", RAFT_PUSH_DELIVERY_EVENT_RECEIVED, pushEventAttrs, occurredAtMillis = serverMs - 5_000L)
        advanceTimeBy(150L)
        runCurrent()

        val decoded = testJson.decodeFromString(RaftWebTraceBatchRequest.serializer(), postedBodies.single())
        val wireEvent = decoded.events.single()
        assertEquals(-2_900L, wireEvent.attrs[RaftPushClockOffsetAttr]?.jsonPrimitive?.long)
        assertEquals(0L, wireEvent.attrs[RaftPushClockRttAttr]?.jsonPrimitive?.long)
        assertEquals("server_time", wireEvent.attrs[RaftPushClockSourceAttr]?.jsonPrimitive?.content)
        assertNotNull(wireEvent.attrs["delivery_id"])
    }
}
