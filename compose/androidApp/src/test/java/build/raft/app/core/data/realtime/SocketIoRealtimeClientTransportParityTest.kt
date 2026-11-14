package build.raft.app.core.data.realtime

import build.raft.app.data.realtime.RaftRealtimeKmpClientFlag
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeSyncHorizon
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The reconnect / resume and sync-horizon behaviour of SocketIoRealtimeClient on both socket implementations
 * the app ships, through the production wiring (createAndroidRealtimeSocketTransport with
 * realtime_kmp_client_v1 off = socket.io-client-java, on = the common Kotlin client), against a real
 * socket.io 4.8.3 server that behaves like Raft's realtime server (scripts/socketio-interop/server.mjs,
 * PORT_C, started by the Android CI job; skipped when RAFT_SOCKETIO_INTEROP_RAFT_URL is not set).
 */
@RunWith(Parameterized::class)
class SocketIoRealtimeClientTransportParityTest(private val implementation: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun implementations(): List<Array<Any>> = listOf(arrayOf("legacy"), arrayOf("kmp"))

        private const val TIMEOUT_MS = 30_000L
    }

    private val baseUrl: String? = System.getenv("RAFT_SOCKETIO_INTEROP_RAFT_URL")?.takeIf { it.isNotBlank() }
    private val token = "parity-${UUID.randomUUID()}"
    private val events = CopyOnWriteArrayList<RealtimeEvent>()
    private val channelAcks = CopyOnWriteArrayList<String>()
    private val rawJson = CopyOnWriteArrayList<Pair<String, String>>()
    private var client: SocketIoRealtimeClient? = null

    @After
    fun tearDown() {
        client?.disconnect()
    }

    private fun start(): SocketIoRealtimeClient {
        val url = baseUrl
        assumeTrue("RAFT_SOCKETIO_INTEROP_RAFT_URL not set (the Android CI job starts the server)", url != null)
        val enabled = implementation == "kmp"
        val transport =
            createAndroidRealtimeSocketTransport(requireNotNull(url)) {
                RaftRealtimeKmpClientFlag.Decision(enabled, RaftRealtimeKmpClientFlag.Source.Local)
            }
        val created =
            SocketIoRealtimeClient(
                transport,
                watchdogScheduler = { {} },
                baselineHistorySent = { false },
                authRefreshRunner = { task -> task() },
            )
        created.observeEvents { events += it }
        created.observeRawEvents { _, eventName, payload -> rawJson += eventName to build.raft.app.shared.realtimeRawPayloadJson(payload) }
        created.observeChannelJoinAck { _, channelId, joined -> if (joined) channelAcks += channelId }
        client = created
        created.connect(RealtimeConnectRequest(auth = RealtimeAuth(token = token, serverId = ServerId("server-1"))))
        awaitLog("the first socket connected and asked for the starting horizon") { log ->
            log.events("connect").size == 1 && log.events("sync:horizon").isNotEmpty()
        }
        assertEquals(implementation, transport.activeImplementation)
        return created
    }

    @Test
    fun handshakeCarriesTheMobileAuthOnEitherSocket() {
        start()
        val auth = log().events("connect").single().getJSONObject("auth")
        assertEquals("mobile", auth.getString("clientKind"))
        assertEquals("server-1", auth.getString("serverId"))
        assertTrue(auth.getJSONArray("capabilities").length() > 0)
        assertEquals("only the common client marks itself", implementation == "kmp", auth.optString("clientImpl") == "kmp")
    }

    @Test
    fun aDroppedSocketReconnectsRejoinsAndResumesFromTheAppliedSeqAndHorizon() {
        val client = start()
        client.joinChannel(ChannelId("channel-1"))
        awaitTrue("channel:joined acknowledged") { "channel-1" in channelAcks }
        assertEquals(listOf("channel-1"), log().events("join:channel").map { it.getJSONArray("args").getString(0) })

        post("message", "seq=89")
        awaitTrue("message:new 89 delivered") { events.filterIsInstance<MessageNew>().any { it.message.seq == 89L } }
        // The raw fan-out (the app's shared stores) parses JSON text and gates on its top-level serverId.
        awaitTrue("message:new raw payload") { rawJson.any { it.first == "message:new" } }
        val raw = JSONObject(rawJson.first { it.first == "message:new" }.second)
        assertEquals("server-1", raw.getString("serverId"))
        assertEquals(89L, raw.getLong("seq"))

        post("drop")
        awaitLog("reconnected, rejoined and resumed after rooms:joined") { log ->
            log.events("connect").size == 2 && log.events("join:channel").size >= 2 && log.events("sync:resume").isNotEmpty()
        }
        val resume = log().events("sync:resume").single().getJSONArray("args").getJSONObject(0)
        assertEquals(89L, resume.getLong("lastSeq"))
        assertEquals("752", resume.getString(RealtimeSyncHorizon.SinceHorizonKey))
        assertEquals("the rejoin uses the bare channel id", "channel-1", log().events("join:channel").last().getJSONArray("args").getString(0))
        awaitTrue("sync:resume:response applied") { events.any { it is SyncResumeResponse } }
        assertTrue(events.filterIsInstance<RealtimeRefreshRequired>().isEmpty())

        post("message", "seq=90")
        awaitTrue("the reconnected socket delivers new messages") { events.filterIsInstance<MessageNew>().any { it.message.seq == 90L } }
    }

    @Test
    fun horizonResetAfterAReconnectRequestsTheCatchUpAndTheNextResumeUsesTheNewHorizon() {
        start()
        post("message", "seq=89")
        awaitTrue("message:new 89 delivered") { events.filterIsInstance<MessageNew>().any { it.message.seq == 89L } }
        post("config", "horizonReset=1")
        post("drop")
        awaitTrue("horizon reset catch-up requested") {
            events.filterIsInstance<RealtimeRefreshRequired>().any { it.reason == RealtimeSyncHorizon.ResetReason }
        }

        post("config", "horizonReset=0")
        post("drop")
        awaitLog("second resume") { log -> log.events("sync:resume").size == 2 }
        val resume = log().events("sync:resume").last().getJSONArray("args").getJSONObject(0)
        assertEquals("800", resume.getString(RealtimeSyncHorizon.SinceHorizonKey))
        assertFalse(log().events("sync:horizon").size > 1)
    }

    private class Log(val entries: JSONArray) {
        fun events(name: String): List<JSONObject> =
            (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.optString("event") == name }

        override fun toString(): String = entries.toString()
    }

    private fun log(): Log = Log(JSONArray(request("GET", "log", "")))

    private fun post(action: String, query: String = "") {
        request("POST", action, query)
    }

    private fun request(method: String, action: String, query: String): String {
        val connection = URL("$baseUrl/raft/$action?token=$token&$query").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun awaitLog(what: String, condition: (Log) -> Boolean) {
        var last: Log? = null
        awaitTrue(what, { "server log: $last" }) { log().also { last = it }.let(condition) }
    }

    private fun awaitTrue(what: String, detail: () -> String = { "events: ${events.map { it::class.simpleName }}; server log: ${runCatching { log() }.getOrNull()}" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("[$implementation] timed out waiting for: $what; ${detail()}")
    }
}
