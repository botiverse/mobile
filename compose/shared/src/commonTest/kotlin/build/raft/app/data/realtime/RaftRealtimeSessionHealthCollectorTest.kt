package build.raft.app.data.realtime

import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * `mobile.realtime.session_health` windows on a fake monotonic clock (RaftRealtimeSessionHealth.kt):
 * counts and durations of connect / drop / reconnect sequences, the longest gap, offline time and the
 * three-way split of the window, how a window ends (the 30-minute split included), what is not sent,
 * and that background time is never counted.
 */
class RaftRealtimeSessionHealthCollectorTest {
    private class Harness {
        var now = 100_000L
        var deadline: Long? = null
        val spans = mutableListOf<Pair<String, RaftWebSpanRecord>>()
        val lines = mutableListOf<String>()
        val collector =
            RaftRealtimeSessionHealthCollector(
                nowMillis = { now },
                nowEpochMillis = { EpochBase + now },
                emit = { serverId, span -> spans += serverId to span },
                log = { lines += it },
                newTraceIds = { RaftTraceIds(traceId = "a".repeat(32), spanId = "b".repeat(16)) },
                onDeadline = { deadline = it },
            )

        fun advance(millis: Long) {
            now += millis
        }

        fun attrs(index: Int = spans.lastIndex): JsonObject = spans[index].second.attrs

        fun ends(): List<String> = spans.map { it.second.attrs.text("end") }
    }

    private val socketA = Any()
    private val socketB = Any()
    private val socketC = Any()

    @Test
    fun aDropAndReconnectCountTheConnectsTheRetriesAndTheTimeEachSideOfTheDrop() {
        val h = Harness()
        val start = h.now
        h.collector.onSignedIn("s1", "u1")
        h.advance(2_000)
        h.collector.onConnectAttempt("s1")
        h.collector.onConnected("s1", socketA)
        repeat(3) { h.collector.onEventReceived("s1") }
        h.collector.onEventDropped("s1")
        h.advance(60_000)
        h.collector.onDisconnected("s1", socketA, RaftRealtimeHealthError.Timeout)
        h.advance(1_000)
        h.collector.onRetryScheduled("s1")
        h.advance(3_000)
        h.collector.onAttemptFailed("s1", RaftRealtimeHealthError.Dns)
        h.collector.onRetryScheduled("s1")
        h.advance(4_000)
        h.collector.onConnectAttempt("s1")
        h.collector.onConnected("s1", socketB)
        h.collector.onResumeOutcome("s1", ok = true)
        h.advance(30_000)
        h.collector.onForeground(false)

        val (serverId, span) = h.spans.single()
        assertEquals("s1", serverId)
        assertEquals(RaftRealtimeSessionHealthSpanName, span.name)
        assertEquals(raftEpochMillisUtcIso(EpochBase + start), span.startTime)
        assertEquals(raftEpochMillisUtcIso(EpochBase + start + 100_000L), span.endTime)
        val attrs = span.attrs
        assertEquals(100_000L, attrs.number("window_ms"))
        assertEquals(90_000L, attrs.number("connected_ms"))
        assertEquals(10_000L, attrs.number("disconnected_ms"), "2 s before the first connect, 8 s after the drop")
        assertEquals(0L, attrs.number("offline_ms"))
        assertEquals(8_000L, attrs.number("longest_gap_ms"))
        assertEquals(2L, attrs.number("connects"))
        assertEquals(1L, attrs.number("reconnects"))
        assertEquals(2L, attrs.number("reconnect_attempts"))
        assertEquals(1L, attrs.number("handshake_failures"))
        assertEquals("dns", attrs.text("last_error"), "the last failure, after the drop's timeout")
        assertEquals(1L, attrs.number("resume_ok"))
        assertEquals(0L, attrs.number("resume_fallback"))
        assertEquals(3L, attrs.number("events_received"))
        assertEquals(1L, attrs.number("events_dropped"))
        assertEquals("unknown", attrs.text("network"))
        assertEquals("background", attrs.text("end"))
        assertEquals(1.0, attrs.getValue("sample_rate").jsonPrimitive.double)
    }

    @Test
    fun theLongestGapIsTheLongestStretchWithoutAConnectionWhereverItFalls() {
        // (before the first connect, a drop mid-window spent half offline, still open at the end) -> longest.
        listOf(
            Triple(5_000L, 20_000L, 12_000L) to 20_000L,
            Triple(25_000L, 20_000L, 12_000L) to 25_000L,
            Triple(5_000L, 20_000L, 30_000L) to 30_000L,
        ).forEach { (gaps, longest) ->
            val (first, middle, last) = gaps
            val h = Harness()
            h.collector.onSignedIn("s1", "u1")
            h.advance(first)
            h.collector.onConnected("s1", socketA)
            h.advance(10_000)
            h.collector.onDisconnected("s1", socketA, RaftRealtimeHealthError.Network)
            h.advance(middle / 2)
            h.collector.onHostNetwork(false)
            h.advance(middle / 2)
            h.collector.onHostNetwork(true)
            h.collector.onConnected("s1", socketB)
            h.advance(10_000)
            h.collector.onDisconnected("s1", socketB, RaftRealtimeHealthError.ClosedByServer)
            h.advance(last)
            h.collector.onSignedOut()
            assertEquals(longest, h.attrs().number("longest_gap_ms"), "gaps=$gaps")
        }
    }

    @Test
    fun timeTheOsReportsNoNetworkIsOfflineWhateverTheSocketSays() {
        val h = Harness()
        h.collector.onHostNetwork(true)
        h.collector.onSignedIn("s1", "u1")
        h.advance(1_000)
        h.collector.onConnected("s1", socketA)
        h.advance(10_000)
        // The socket has not noticed yet: still offline time.
        h.collector.onHostNetwork(false)
        h.advance(20_000)
        h.collector.onDisconnected("s1", socketA, RaftRealtimeHealthError.Timeout)
        h.advance(10_000)
        h.collector.onHostNetwork(true)
        h.advance(3_000)
        h.collector.onConnected("s1", socketB)
        h.advance(10_000)
        h.collector.onForeground(false)
        val first = h.attrs()
        assertEquals(54_000L, first.number("window_ms"))
        assertEquals(20_000L, first.number("connected_ms"))
        assertEquals(30_000L, first.number("offline_ms"))
        assertEquals(4_000L, first.number("disconnected_ms"))
        assertEquals(33_000L, first.number("longest_gap_ms"), "offline while connected, then disconnected: one gap")
        assertEquals("none", first.text("network"), "no network for most of the window")

        h.advance(60_000)
        h.collector.onForeground(true)
        h.advance(20_000)
        h.collector.onSignedOut()
        val second = h.attrs()
        assertEquals(20_000L, second.number("connected_ms"))
        assertEquals(0L, second.number("offline_ms"))
        assertEquals("unknown", second.text("network"))
    }

    @Test
    fun connectedDisconnectedAndOfflineTimeAlwaysAddUpToTheWindow() {
        val random = Random(172)
        val h = Harness()
        val sockets = listOf(socketA, socketB, socketC)
        val servers = listOf("s1", "s2")
        repeat(2_000) {
            h.advance(random.nextLong(0L, 90_000L))
            val server = servers[random.nextInt(servers.size)]
            when (random.nextInt(40)) {
                in 0..7 -> h.collector.onConnected(server, sockets[random.nextInt(sockets.size)])
                in 8..15 -> h.collector.onDisconnected(server, sockets[random.nextInt(sockets.size)], RaftRealtimeHealthError.Network)
                in 16..18 -> h.collector.onHostNetwork(random.nextBoolean())
                19 -> h.collector.onAttemptFailed(server, RaftRealtimeHealthError.Timeout)
                20 -> h.collector.onRetryScheduled(server)
                21 -> h.collector.onConnectAttempt(server)
                22 -> h.collector.onForeground(false)
                23, 24 -> h.collector.onForeground(true)
                25 -> h.collector.onSignedIn(server, "u1")
                26 -> if (random.nextInt(4) == 0) h.collector.onSignedOut() else h.collector.tick()
                27, 28 -> h.collector.onResumeOutcome(server, random.nextBoolean())
                else -> h.collector.tick()
            }
        }
        h.collector.onSignedOut()
        assertTrue(h.spans.size > 20, "the walk ends many windows, ${h.spans.size}")
        assertTrue("periodic" in h.ends() && "server_switch" in h.ends() && "background" in h.ends(), "${h.ends().toSet()}")
        h.spans.forEach { (_, span) ->
            val attrs = span.attrs
            val windowMs = attrs.number("window_ms")
            assertEquals(windowMs, attrs.number("connected_ms") + attrs.number("disconnected_ms") + attrs.number("offline_ms"), "$attrs")
            assertTrue(attrs.number("longest_gap_ms") <= attrs.number("disconnected_ms") + attrs.number("offline_ms"), "$attrs")
            assertTrue(windowMs <= RaftRealtimeSessionHealthWindowMillis, "$attrs")
            assertTrue(attrs.number("reconnects") <= attrs.number("connects"), "$attrs")
        }
    }

    @Test
    fun aWindowEndsAsTheAppLeavesTheForegroundTheServerOrTheAccount() {
        val cases: List<Pair<String, (Harness) -> Unit>> =
            listOf(
                "background" to { h: Harness -> h.collector.onForeground(false) },
                "server_switch" to { h: Harness -> h.collector.onSignedIn("s2", "u1") },
                "logout" to { h: Harness -> h.collector.onSignedOut() },
                "logout" to { h: Harness -> h.collector.onSignedIn("s1", "u2") },
            )
        cases.forEach { (end, trigger) ->
            val h = Harness()
            h.collector.onSignedIn("s1", "u1")
            h.collector.onSignedIn("s1", "u1")
            h.advance(10_000)
            trigger(h)
            assertEquals(listOf("s1"), h.spans.map { it.first }, end)
            assertEquals(end, h.attrs().text("end"))
            assertEquals(10_000L, h.attrs().number("window_ms"), end)
        }
    }

    @Test
    fun theNextWindowIsTheNewServersAndOpensOnlyInTheForeground() {
        val h = Harness()
        h.collector.onSignedIn("s1", "u1")
        h.advance(10_000)
        h.collector.onSignedIn("s2", "u1")
        h.collector.onConnected("s1", socketA)
        h.advance(6_000)
        h.collector.onForeground(false)
        assertEquals(listOf("s1" to "server_switch", "s2" to "background"), h.spans.map { it.first to it.second.attrs.text("end") })
        assertEquals(0L, h.attrs().number("connects"), "a socket of the previous server")
        assertNull(h.deadline)

        h.advance(60_000)
        h.collector.onSignedOut()
        h.collector.onSignedIn("s3", "u1")
        assertEquals(2, h.spans.size, "nothing opens in the background")
        h.collector.onForeground(true)
        h.advance(7_000)
        h.collector.onForeground(false)
        assertEquals("s3" to 7_000L, h.spans.last().first to h.attrs().number("window_ms"))
    }

    @Test
    fun aLongForegroundIsCutEveryThirtyMinutesAndTheNextWindowStartsAtTheCut() {
        val h = Harness()
        val start = h.now
        h.collector.onSignedIn("s1", "u1")
        assertEquals(start + RaftRealtimeSessionHealthWindowMillis, h.deadline)
        h.collector.onConnected("s1", socketA)
        h.advance(29 * 60_000L)
        h.collector.onDisconnected("s1", socketA, RaftRealtimeHealthError.Network)
        // No input until 65 minutes in: the deadline timer ticks.
        h.advance(36 * 60_000L)
        h.collector.tick()

        assertEquals(listOf("periodic", "periodic"), h.ends())
        val (first, second) = h.attrs(0) to h.attrs(1)
        assertEquals(listOf(1_800_000L, 1_800_000L), listOf(first.number("window_ms"), second.number("window_ms")))
        assertEquals(29 * 60_000L, first.number("connected_ms"))
        assertEquals(60_000L, first.number("disconnected_ms"))
        assertEquals(60_000L, first.number("longest_gap_ms"), "the gap is cut with its window")
        assertEquals(1_800_000L, second.number("disconnected_ms"))
        assertEquals(1_800_000L, second.number("longest_gap_ms"))
        assertEquals(0L, second.number("connects"))
        assertEquals(h.spans[0].second.endTime, h.spans[1].second.startTime)
        assertEquals(raftEpochMillisUtcIso(EpochBase + start + 3_600_000L), h.spans[1].second.endTime)
        assertEquals(start + 3 * RaftRealtimeSessionHealthWindowMillis, h.deadline)

        h.collector.onConnected("s1", socketB)
        h.advance(60_000)
        h.collector.onForeground(false)
        val third = h.attrs()
        assertEquals("background", third.text("end"))
        assertEquals(6 * 60_000L, third.number("window_ms"))
        assertEquals(1L, third.number("reconnects"), "the drop before the cuts still makes this connect a reconnect")
        assertNull(h.deadline)
    }

    @Test
    fun aWindowUnderFiveSecondsIsSentOnlyWhenTheConnectionDidSomething() {
        val cases: List<Triple<String, Long, (Harness) -> Unit>> =
            listOf(
                Triple("nothing", 3_000L, { _: Harness -> }),
                Triple("events only", 3_000L, { h: Harness -> h.collector.onEventReceived("s1") }),
                Triple("another server's connect", 3_000L, { h: Harness -> h.collector.onConnected("s2", socketA) }),
                Triple("a connect attempt", 3_000L, { h: Harness -> h.collector.onConnectAttempt("s1") }),
                Triple("a connect", 3_000L, { h: Harness -> h.collector.onConnected("s1", socketA) }),
                Triple("a retry", 3_000L, { h: Harness -> h.collector.onRetryScheduled("s1") }),
                Triple("five quiet seconds", 5_000L, { _: Harness -> }),
            )
        val sent =
            cases.associate { (name, length, during) ->
                val h = Harness()
                h.collector.onSignedIn("s1", "u1")
                h.advance(length / 2)
                during(h)
                h.advance(length - length / 2)
                h.collector.onForeground(false)
                assertEquals(h.spans.size, h.lines.size, name)
                name to h.spans.size
            }
        assertEquals(
            mapOf(
                "nothing" to 0,
                "events only" to 0,
                "another server's connect" to 0,
                "a connect attempt" to 1,
                "a connect" to 1,
                "a retry" to 1,
                "five quiet seconds" to 1,
            ),
            sent,
        )
    }

    @Test
    fun backgroundTimeAndWhatTheSocketDidMeanwhileAreNotCounted() {
        val h = Harness()
        h.collector.onSignedIn("s1", "u1")
        h.collector.onConnected("s1", socketA)
        h.advance(10_000)
        h.collector.onForeground(false)
        // Hidden (Android's keep-alive connection): a drop, retries and a reconnect nobody saw.
        h.advance(5_000)
        h.collector.onDisconnected("s1", socketA, RaftRealtimeHealthError.Network)
        h.collector.onRetryScheduled("s1")
        h.collector.onAttemptFailed("s1", RaftRealtimeHealthError.Timeout)
        h.collector.onEventReceived("s1")
        h.advance(600_000)
        h.collector.onConnected("s1", socketB)
        h.advance(5_000)
        h.collector.onForeground(true)
        h.advance(20_000)
        h.collector.onSignedOut()

        assertEquals(listOf(10_000L, 20_000L), h.spans.map { it.second.attrs.number("window_ms") })
        val back = h.attrs()
        assertEquals(20_000L, back.number("connected_ms"), "it came back connected")
        assertEquals(0L, back.number("longest_gap_ms"))
        listOf("connects", "reconnects", "reconnect_attempts", "handshake_failures", "events_received").forEach { key ->
            assertEquals(0L, back.number(key), key)
        }
        assertEquals("", back.text("last_error"))
    }

    @Test
    fun onlyTheWindowServersSocketsCountAndAReplacementThatConnectsFirstIsNoDrop() {
        val h = Harness()
        h.collector.onSignedIn("s1", "u1")
        h.collector.onConnected("s1", socketA)
        h.advance(10_000)
        // Another server's socket (multi-session) does not count here.
        val otherServers = Any()
        h.collector.onConnected("s2", otherServers)
        h.collector.onDisconnected("s2", otherServers, RaftRealtimeHealthError.Timeout)
        h.collector.onAttemptFailed("s2", RaftRealtimeHealthError.Tls)
        h.collector.onEventReceived("s2")
        // The app replaced the connection and the new one came up before the old one closed.
        h.collector.onConnected("s1", socketB)
        h.advance(1_000)
        h.collector.onDisconnected("s1", socketA, null)
        h.advance(10_000)
        h.collector.onDisconnected("s1", socketB, RaftRealtimeHealthError.ClosedByServer)
        h.advance(4_000)
        h.collector.onConnected("s1", socketC)
        h.advance(5_000)
        h.collector.onSignedOut()

        val attrs = h.attrs()
        assertEquals(3L, attrs.number("connects"))
        assertEquals(1L, attrs.number("reconnects"))
        assertEquals(26_000L, attrs.number("connected_ms"))
        assertEquals(4_000L, attrs.number("disconnected_ms"))
        assertEquals(4_000L, attrs.number("longest_gap_ms"))
        assertEquals(0L, attrs.number("handshake_failures"))
        assertEquals(0L, attrs.number("events_received"))
        assertEquals("closed_by_server", attrs.text("last_error"))
    }

    @Test
    fun aResumeOutcomeCountsOncePerReconnectAndNeverForAFirstConnect() {
        val h = Harness()
        h.collector.onSignedIn("s1", "u1")
        h.collector.onConnected("s1", socketA)
        h.collector.onResumeOutcome("s1", ok = true)
        h.advance(10_000)
        h.collector.onDisconnected("s1", socketA, RaftRealtimeHealthError.Network)
        h.collector.onConnected("s1", socketB)
        // The socket's `horizonReset`, then the client's catch-up request for it: one reconnect, one outcome.
        h.collector.onResumeOutcome("s1", ok = false)
        h.collector.onResumeOutcome("s1", ok = false)
        h.advance(10_000)
        h.collector.onDisconnected("s1", socketB, RaftRealtimeHealthError.Network)
        h.collector.onConnected("s1", socketC)
        h.collector.onResumeOutcome("s1", ok = true)
        h.advance(10_000)
        h.collector.onSignedOut()

        val attrs = h.attrs()
        assertEquals(2L, attrs.number("reconnects"))
        assertEquals(1L, attrs.number("resume_ok"))
        assertEquals(1L, attrs.number("resume_fallback"))
    }
}

private const val EpochBase = 1_794_391_200_000L

private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
