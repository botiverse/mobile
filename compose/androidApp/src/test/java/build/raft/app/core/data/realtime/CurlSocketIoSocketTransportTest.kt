package build.raft.app.core.data.realtime

import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RealtimeSocketTransport
import build.raft.app.model.ServerId
import build.raft.app.network.RaftCurlSocketIoListener
import build.raft.app.network.RaftCurlSocketIoSession
import build.raft.app.network.RaftCurlSocketIoState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlSocketIoSocketTransportTest {
    private class FakeSession(
        val serverUrl: String,
        val authJson: String,
        val listener: RaftCurlSocketIoListener,
    ) : RaftCurlSocketIoSession {
        var started = 0
        var closed = 0
        val emitted = mutableListOf<Pair<String, String>>()

        override fun start(): Boolean {
            started += 1
            return true
        }

        override fun emit(eventName: String, payloadJson: String): Boolean {
            emitted += eventName to payloadJson
            return true
        }

        override fun close() {
            closed += 1
        }
    }

    private val sessions = mutableListOf<FakeSession>()
    private val delivered = mutableListOf<Triple<String, String, Any?>>()
    private val lifecycle = mutableListOf<String>()

    private fun transport(available: Boolean = true): CurlSocketIoSocketTransport =
        CurlSocketIoSocketTransport(
            socketUrl = "https://api.example",
            sessionFactory = { url, auth, listener ->
                if (available) FakeSession(url, auth, listener).also { sessions += it } else null
            },
            closeExecutor = { it.run() },
            logger = {},
            lifecycleLogger = { lifecycle += it },
        ).also { transport ->
            listOf(
                RealtimeSocketEvents.Connect,
                RealtimeSocketEvents.ConnectError,
                RealtimeSocketEvents.Disconnect,
                RealtimeSocketEvents.Reconnect,
                RealtimeSocketEvents.ReconnectAttempt,
                RealtimeSocketEvents.ReconnectError,
                RealtimeSocketEvents.RoomsJoined,
                RealtimeSocketEvents.MessageNew,
                "custom:string",
            ).forEach { eventName ->
                transport.on(eventName) { context, payload ->
                    delivered += Triple(eventName, context.serverId.value, payload)
                }
            }
        }

    private fun context(serverId: String) =
        RealtimeSocketConnectionContext(
            serverId = ServerId(serverId),
            witness = RealtimeConnectionWitnessToken("lease", "principal", 1L, "family-$serverId"),
        )

    private fun names() = delivered.map { it.first }

    @Test
    fun `connect creates and starts one session with the auth as JSON`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t1", "serverId" to "s1"), context("s1"))

        val session = sessions.single()
        assertEquals("https://api.example", session.serverUrl)
        val auth = JSONObject(session.authJson)
        assertEquals("t1", auth.getString("token"))
        assertEquals("s1", auth.getString("serverId"))
        assertEquals(1, session.started)
    }

    @Test
    fun `connection lifecycle is logged for release diagnostics`() {
        // Hands a9971638: no realtime events for 10 minutes on the curl stack and no release log of why.
        val transport = transport()
        transport.connect(mapOf("token" to "t", "serverId" to "s1"), context("s1"))
        val native = sessions.single().listener

        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.Connected, 0, "")
        native.onEvent(RealtimeSocketEvents.MessageNew, """{"serverId":"s1","id":"m1"}""")
        native.onEvent(RealtimeSocketEvents.MessageNew, """{"serverId":"s1","id":"m2"}""")
        native.onState(RaftCurlSocketIoState.Disconnected, 0, "transport_closed")

        assertTrue(lifecycle.first().startsWith("transport_connect_start stack=curl serverId=s1 hasToken=true"))
        assertTrue(lifecycle.any { it.startsWith("transport_state stack=curl state=Connected") })
        assertEquals(1, lifecycle.count { it.startsWith("transport_first_event stack=curl eventName=message:new") })
        assertTrue(
            lifecycle.any {
                it.startsWith("transport_state stack=curl state=Disconnected") && it.endsWith("eventsSinceConnect=2")
            }
        )
    }

    @Test
    fun `events reach listeners with the old library's payload types`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t"), context("s1"))
        val native = sessions.single().listener

        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.EngineOpen, 0, "")
        native.onState(RaftCurlSocketIoState.Connected, 0, "")
        // raft.44: `42["rooms:joined"]` arrives with payload "null".
        native.onEvent(RealtimeSocketEvents.RoomsJoined, "null")
        native.onEvent(RealtimeSocketEvents.MessageNew, """{"serverId":"s1","id":"m1"}""")
        native.onEvent("custom:string", "\"hello\"")

        assertEquals(
            listOf(RealtimeSocketEvents.Connect, RealtimeSocketEvents.RoomsJoined, RealtimeSocketEvents.MessageNew, "custom:string"),
            names(),
        )
        assertTrue(delivered.all { it.second == "s1" })
        assertNull(delivered[1].third)
        assertEquals("m1", (delivered[2].third as JSONObject).getString("id"))
        assertEquals("hello", delivered[3].third)
    }

    @Test
    fun `a reconnect cycle maps to the old library's manager events`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t"), context("s1"))
        val native = sessions.single().listener
        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.Connected, 0, "")
        delivered.clear()

        native.onState(RaftCurlSocketIoState.Disconnected, 0, "transport_closed")
        native.onState(RaftCurlSocketIoState.Reconnecting, 0, "500")
        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.Error, 6, "websocket_connect")
        native.onState(RaftCurlSocketIoState.Reconnecting, 0, "1000")
        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.EngineOpen, 0, "")
        native.onState(RaftCurlSocketIoState.Connected, 0, "")

        assertEquals(
            listOf(
                RealtimeSocketEvents.Disconnect,
                RealtimeSocketEvents.ReconnectAttempt,
                RealtimeSocketEvents.ReconnectError,
                RealtimeSocketEvents.ReconnectAttempt,
                RealtimeSocketEvents.Reconnect,
                RealtimeSocketEvents.Connect,
            ),
            names(),
        )
        assertEquals(listOf(1, 2), delivered.filter { it.first == RealtimeSocketEvents.ReconnectAttempt }.map { it.third })

        // A later cycle counts attempts from 1 again and reports no stale reconnect.
        delivered.clear()
        native.onState(RaftCurlSocketIoState.Disconnected, 0, "transport_closed")
        native.onState(RaftCurlSocketIoState.Reconnecting, 0, "500")
        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.Connected, 0, "")
        assertEquals(1, delivered.single { it.first == RealtimeSocketEvents.ReconnectAttempt }.third)
    }

    @Test
    fun `first connect failure is connect_error and a server CONNECT_ERROR keeps its payload`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t"), context("s1"))
        val native = sessions.single().listener

        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.Error, 7, "websocket_connect")
        native.onState(RaftCurlSocketIoState.Reconnecting, 0, "500")
        native.onState(RaftCurlSocketIoState.Connecting, 0, "")
        native.onState(RaftCurlSocketIoState.EngineOpen, 0, "")
        native.onState(RaftCurlSocketIoState.Error, 0, """{"message":"invalid token"}""")

        assertEquals(
            listOf(RealtimeSocketEvents.ConnectError, RealtimeSocketEvents.ReconnectAttempt, RealtimeSocketEvents.ConnectError),
            names(),
        )
        assertEquals("invalid token", (delivered.last().third as JSONObject).getString("message"))
    }

    @Test
    fun `binary frames and the final closed state deliver nothing`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t"), context("s1"))
        val native = sessions.single().listener
        native.onState(RaftCurlSocketIoState.Error, 0, "binary_frame_unsupported")
        native.onState(RaftCurlSocketIoState.Disconnected, 0, "closed")
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a replaced or disconnected session is closed and its callbacks are dropped`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t1"), context("s1"))
        val first = sessions.single()
        transport.connect(mapOf("token" to "t2"), context("s2"))
        val second = sessions.last()

        assertEquals(1, first.closed)
        first.listener.onState(RaftCurlSocketIoState.Connected, 0, "")
        first.listener.onEvent(RealtimeSocketEvents.MessageNew, "{}")
        assertTrue(delivered.isEmpty())

        second.listener.onState(RaftCurlSocketIoState.Connected, 0, "")
        assertEquals(listOf(Triple(RealtimeSocketEvents.Connect, "s2", null)), delivered)

        transport.disconnect()
        assertEquals(1, second.closed)
        second.listener.onEvent(RealtimeSocketEvents.MessageNew, "{}")
        assertEquals(1, delivered.size)
    }

    @Test
    fun `emit sends JSON text, channel ids as JSON strings`() {
        val transport = transport()
        transport.connect(mapOf("token" to "t"), context("s1"))
        transport.emit(RealtimeSocketEvents.JoinChannel, mapOf("channelId" to "c1"))
        transport.emit(RealtimeSocketEvents.SyncResume, mapOf("lastSeq" to 42L))

        val emitted = sessions.single().emitted
        assertEquals(RealtimeSocketEvents.JoinChannel to "\"c1\"", emitted[0])
        assertEquals(RealtimeSocketEvents.SyncResume, emitted[1].first)
        assertEquals(42L, JSONObject(emitted[1].second).getLong("lastSeq"))
    }

    @Test
    fun `unsubscribed listeners stop receiving`() {
        val transport = transport()
        val seen = mutableListOf<Any?>()
        val subscription = transport.on("custom:other") { _, payload -> seen += payload }
        transport.connect(mapOf("token" to "t"), context("s1"))
        val native = sessions.single().listener
        native.onEvent("custom:other", "1")
        subscription.close()
        native.onEvent("custom:other", "2")
        assertEquals(listOf<Any?>(1), seen)
    }

    @Test
    fun `an unavailable runtime reports connect_error instead of connecting`() {
        val transport = transport(available = false)
        transport.connect(mapOf("token" to "t"), context("s1"))
        assertEquals(listOf(Triple(RealtimeSocketEvents.ConnectError, "s1", "curl_socket_io_unavailable")), delivered)
    }

    @Test
    fun `payload decoding`() {
        assertNull(decodeSocketIoPayload("null"))
        assertNull(decodeSocketIoPayload(""))
        assertEquals(true, decodeSocketIoPayload("true"))
        assertEquals(3, decodeSocketIoPayload("3"))
        assertEquals("x", decodeSocketIoPayload("\"x\""))
        assertEquals(2, (decodeSocketIoPayload("[1,2]") as org.json.JSONArray).length())
        // Several arguments: the first one, like the old library's args[0].
        assertEquals("a", (decodeSocketIoPayload("""{"k":"a"},{"k":"b"}""") as JSONObject).getString("k"))
    }
}

class SelectableRealtimeSocketTransportTest {
    private class RecordingTransport(val name: String, val calls: MutableList<String>) : RealtimeSocketTransport {
        val listeners = mutableMapOf<String, MutableList<(RealtimeSocketConnectionContext, Any?) -> Unit>>()

        override fun <T> linearize(block: () -> T): T = block()

        override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
            calls += "$name.connect:${context.serverId.value}"
        }

        override fun disconnect() {
            calls += "$name.disconnect"
        }

        override fun emit(eventName: String, payload: Map<String, Any>) {
            calls += "$name.emit:$eventName"
        }

        override fun on(
            eventName: String,
            listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
        ): RealtimeSubscription {
            listeners.getOrPut(eventName) { mutableListOf() } += listener
            return RealtimeSubscription { listeners[eventName]?.remove(listener) }
        }
    }

    private val calls = mutableListOf<String>()
    private val legacy = RecordingTransport("legacy", calls)
    private val curl = RecordingTransport("curl", calls)
    private var useCurl = false

    private val transport =
        SelectableRealtimeSocketTransport(
            legacy = legacy,
            curl = curl,
            fence = RealtimeSocketGenerationFence(),
            useCurl = { useCurl },
            logger = {},
        )

    private fun context(serverId: String) =
        RealtimeSocketConnectionContext(
            serverId = ServerId(serverId),
            witness = RealtimeConnectionWitnessToken("lease", "principal", 1L, "family"),
        )

    @Test
    fun `each connect picks the stack and leaves the other one first`() {
        transport.connect(emptyMap(), context("s1"))
        transport.emit("e1")
        useCurl = true
        transport.connect(emptyMap(), context("s1"))
        transport.emit("e2")
        transport.connect(emptyMap(), context("s2"))
        useCurl = false
        transport.connect(emptyMap(), context("s2"))
        transport.disconnect()

        assertEquals(
            listOf(
                "legacy.connect:s1",
                "legacy.emit:e1",
                "legacy.disconnect",
                "curl.connect:s1",
                "curl.emit:e2",
                "curl.connect:s2",
                "curl.disconnect",
                "legacy.connect:s2",
                "legacy.disconnect",
            ),
            calls,
        )
    }

    @Test
    fun `a failing selector read keeps the old library`() {
        val failing =
            SelectableRealtimeSocketTransport(legacy, curl, RealtimeSocketGenerationFence(), { error("boom") }, {})
        failing.connect(emptyMap(), context("s1"))
        assertEquals(listOf("legacy.connect:s1"), calls)
    }

    @Test
    fun `listeners are registered on both stacks and removed from both`() {
        val subscription = transport.on("evt") { _, _ -> }
        assertEquals(1, legacy.listeners["evt"]?.size)
        assertEquals(1, curl.listeners["evt"]?.size)
        subscription.close()
        assertEquals(0, legacy.listeners["evt"]?.size)
        assertEquals(0, curl.listeners["evt"]?.size)
    }
}
