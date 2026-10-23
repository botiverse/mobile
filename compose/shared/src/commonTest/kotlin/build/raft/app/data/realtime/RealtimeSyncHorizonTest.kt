package build.raft.app.data.realtime

import build.raft.app.data.message
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.SyncResumeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync horizon contract, pinned to botiverse/slock a476332d1fe6addab21bc92e1edd2d3fa96a553b (staging,
 * containing #10982): `packages/server/src/services/syncHorizon.ts` (+ `syncHorizon.test.ts`) and
 * `packages/server/src/socket/syncResume.ts` (+ `routes/messagesSyncHorizon.api.test.ts`).
 */
class RealtimeSyncHorizonTest {
    @Test
    fun horizonIsDecimalXid8TextExactlyAsTheServerAcceptsIt() {
        // Vectors from syncHorizon.test.ts "a since-horizon is the decimal string of a non-negative integer that fits xid8".
        listOf("0", "1", "752", "4294967296", "18446744073709551615").forEach { valid ->
            assertEquals(valid, RealtimeSyncHorizon.parse(valid), valid)
        }
        listOf(
            "", " 1", "1 ", "-1", "+1", "01", "00", "1.0", "1e3", "0x10", "abc",
            "18446744073709551616", "99999999999999999999999", "１２",
        ).forEach { invalid ->
            assertNull(RealtimeSyncHorizon.parse(invalid), "\"$invalid\"")
        }
        assertNull(RealtimeSyncHorizon.parse(null))
    }

    @Test
    fun resumeResponseHorizonIsReadOnlyFromAJsonString() {
        val mapper = RealtimeEventMapper()
        // Shape of `sync:resume:response` in socket/syncResume.ts; values from the api test's `ResumeResponse`.
        val current =
            assertIs<SyncResumeResponse>(
                mapper.map(
                    RealtimeSocketEvents.SyncResumeResponse,
                    """{"messages":[],"currentSeq":42,"hasMore":false,"horizon":"18446744073709551615","horizonReset":true}""",
                )
            )
        assertEquals("18446744073709551615", current.horizon, "kept as text: it does not fit a Long")
        assertTrue(current.horizonReset)
        assertEquals(42L, current.currentSeq)

        // An older server: `{ messages, currentSeq, hasMore }` only.
        val old =
            assertIs<SyncResumeResponse>(
                mapper.map(RealtimeSocketEvents.SyncResumeResponse, """{"messages":[],"currentSeq":42,"hasMore":false}""")
            )
        assertNull(old.horizon)
        assertFalse(old.horizonReset)

        listOf("752", "\"01\"", "null", "{\"horizon\":\"1\"}").forEach { notAHorizon ->
            val event =
                assertIs<SyncResumeResponse>(
                    mapper.map(
                        RealtimeSocketEvents.SyncResumeResponse,
                        """{"messages":[],"currentSeq":42,"hasMore":false,"horizon":$notAHorizon}""",
                    )
                )
            assertNull(event.horizon, notAHorizon)
        }
    }

    @Test
    fun resumeRequestSendsSinceHorizonAsTextOnlyWhenHeld() {
        assertEquals(mapOf<String, Any>("lastSeq" to 89L), RealtimeResumeRequest(89L).toWireMap())
        val withHorizon = RealtimeResumeRequest(89L, sinceHorizon = "752").toWireMap()
        assertEquals(89L, withHorizon["lastSeq"])
        assertEquals("752", withHorizon["sinceHorizon"])
    }

    @Test
    fun cursorKeepsTheHorizonPerServerLikeTheAppliedSeq() {
        val held = RealtimeCursorPolicy.fromAppliedSeq(89L).copy(horizon = "752")

        val sameServer = RealtimeCursorPolicy.reconnect(held, nextEpoch = "e2", sameServer = true)
        assertEquals(89L, sameServer.resumeLastSeq)
        assertEquals("752", sameServer.state.horizon)

        val otherServer = RealtimeCursorPolicy.reconnect(held, nextEpoch = "e2", sameServer = false)
        assertNull(otherServer.state.horizon, "a horizon never crosses servers")
        assertTrue(otherServer.catchUpRequired)

        val noWatermark = RealtimeCursorPolicy.reconnect(held.copy(appliedLastSeq = 0L), "e3", sameServer = true)
        assertNull(noWatermark.state.horizon, "rebaselined with the watermark")

        assertNull(RealtimeCursorPolicy.rebaseline(held, "e4").state.horizon)

        // A live message raises the watermark and keeps the horizon (it was computed before that frame).
        val afterLive = RealtimeCursorPolicy.updateFromEvent(held, MessageNew(message(id = "message-90", seq = 90L))).state
        assertEquals(90L, afterLive.appliedLastSeq)
        assertEquals("752", afterLive.horizon)
    }

    @Test
    fun connectKeepsTheHorizonOnlyForTheSameServerAndAWatermarkNotAboveIt() {
        val held = RealtimeCursorPolicy.fromAppliedSeq(89L).copy(horizon = "752")

        assertEquals("752", RealtimeCursorPolicy.connect(held, lastSeq = 89L, sameServer = true).horizon)
        assertEquals("752", RealtimeCursorPolicy.connect(held, lastSeq = 50L, sameServer = true).horizon)
        assertNull(RealtimeCursorPolicy.connect(held, lastSeq = 120L, sameServer = true).horizon, "raised from elsewhere")
        assertNull(RealtimeCursorPolicy.connect(held, lastSeq = 0L, sameServer = true).horizon)
        assertNull(RealtimeCursorPolicy.connect(held, lastSeq = 89L, sameServer = false).horizon)
        assertEquals(120L, RealtimeCursorPolicy.connect(held, lastSeq = 120L, sameServer = true).appliedLastSeq)
    }

    @Test
    fun eachResumeResponseStartsTheNextRoundAndAnOlderServerChangesNothing() {
        val held = RealtimeCursorPolicy.fromAppliedSeq(89L).copy(horizon = "752")

        val next =
            RealtimeCursorPolicy.updateFromEvent(
                held,
                SyncResumeResponse(messages = emptyList(), currentSeq = 95L, hasMore = false, horizon = "800"),
            ).state
        assertEquals(95L, next.appliedLastSeq)
        assertEquals("800", next.horizon)

        val older =
            RealtimeCursorPolicy.updateFromEvent(
                held,
                SyncResumeResponse(messages = emptyList(), currentSeq = 95L, hasMore = false),
            ).state
        assertEquals("752", older.horizon)
        val none =
            RealtimeCursorPolicy.updateFromEvent(
                RealtimeCursorPolicy.fromAppliedSeq(89L),
                SyncResumeResponse(messages = emptyList(), currentSeq = 95L, hasMore = false),
            ).state
        assertNull(none.horizon, "no horizon from an older server: exactly the old behaviour")
    }

    @Test
    fun startingHorizonIsUsedOnlyBeforeAnyBaselineHistoryRequestAndNeverReplacesOne() {
        val empty = RealtimeCursorPolicy.fromAppliedSeq(0L)

        val accepted = RealtimeCursorPolicy.acceptStartingHorizon(empty, "752", baselineHistorySent = false)
        assertTrue(accepted.accepted)
        assertEquals("752", accepted.state.horizon)

        val late = RealtimeCursorPolicy.acceptStartingHorizon(empty, "752", baselineHistorySent = true)
        assertFalse(late.accepted)
        assertEquals("history_request_sent_first", late.reason)
        assertNull(late.state.horizon)

        val held = RealtimeCursorPolicy.acceptStartingHorizon(empty.copy(horizon = "800"), "752", baselineHistorySent = false)
        assertEquals("horizon_held", held.reason)
        assertEquals("800", held.state.horizon, "a resume's horizon is newer than a stale starting ack")

        val error = RealtimeCursorPolicy.acceptStartingHorizon(empty, null, baselineHistorySent = false)
        assertEquals("no_horizon", error.reason)
        assertNull(error.state.horizon)
    }

    @Test
    fun baselineHistoryRequestsAreTheReadsTheClientKeepsAsState() {
        listOf(
            "messages/channel/channel-1",
            "messages/context/m-1",
            "messages/sync",
            "/channels",
            "channels/unread",
            "channels/inbox",
            "channels/channel-1/threads",
            "servers/unread-summary",
        ).forEach { path ->
            assertTrue(RealtimeBaselineHistoryRequests.isBaselineHistoryRequest("GET", path), path)
        }
        assertFalse(RealtimeBaselineHistoryRequests.isBaselineHistoryRequest("POST", "messages"))
        assertFalse(RealtimeBaselineHistoryRequests.isBaselineHistoryRequest("GET", "messages/search"))
        assertFalse(RealtimeBaselineHistoryRequests.isBaselineHistoryRequest("GET", "agents"))
        assertFalse(RealtimeBaselineHistoryRequests.isBaselineHistoryRequest("GET", "servers"))
    }

    @Test
    fun horizonResetIsTheHomeRefreshWithItsOwnReason() {
        assertTrue(
            RealtimeRefreshRequired(scope = RealtimeRefreshScope.Home, reason = RealtimeSyncHorizon.ResetReason)
                .isSyncHorizonReset()
        )
        assertFalse(
            RealtimeRefreshRequired(scope = RealtimeRefreshScope.Home, reason = "transport_reconnect_resume_unavailable")
                .isSyncHorizonReset()
        )
        val mapped =
            RealtimeEventMapper().map(
                RealtimeLocalEvents.CatchUpRequired,
                """{"serverId":"server-1","reason":"${RealtimeSyncHorizon.ResetReason}"}""",
            )
        assertTrue(assertIs<RealtimeRefreshRequired>(mapped).isSyncHorizonReset(), "the raw path the iOS host posts")
    }
}
