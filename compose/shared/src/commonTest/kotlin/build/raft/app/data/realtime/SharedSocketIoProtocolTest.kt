package build.raft.app.data.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedSocketIoProtocolTest {
    @Test
    fun websocketUrlUsesSocketIoWebsocketTransport() {
        assertEquals(
            "wss://raft.example.com/socket.io/?EIO=4&transport=websocket",
            SharedSocketIoProtocol.websocketUrl("https://raft.example.com/")
        )
        assertEquals(
            "ws://localhost:3000/socket.io/?EIO=4&transport=websocket",
            SharedSocketIoProtocol.websocketUrl("http://localhost:3000")
        )
    }

    @Test
    fun connectFrameSendsSocketIoAuthPacket() {
        val frame =
            SharedSocketIoProtocol.connectFrame(
                mapOf(
                    "token" to "access-token",
                    "serverId" to "server-1"
                )
            )

        assertEquals("40", frame.take(2))
        val payload = SharedSocketIoProtocol.json.parseToJsonElement(frame.drop(2)).jsonObject
        assertEquals("access-token", payload["token"]?.jsonPrimitiveContent)
        assertEquals("server-1", payload["serverId"]?.jsonPrimitiveContent)
    }

    @Test
    fun eventFrameKeepsRoomCommandsWireCompatibleWithExistingTransports() {
        val frame =
            SharedSocketIoProtocol.eventFrame(
                RealtimeSocketEvents.JoinChannel,
                mapOf("channelId" to "channel-1")
            )

        assertEquals("42", frame.take(2))
        val payload = SharedSocketIoProtocol.json.parseToJsonElement(frame.drop(2)).jsonArray
        assertEquals(RealtimeSocketEvents.JoinChannel, payload[0].jsonPrimitiveContent)
        assertEquals("channel-1", payload[1].jsonPrimitiveContent)
    }

    @Test
    fun eventFrameEncodesObjectPayloads() {
        val frame =
            SharedSocketIoProtocol.eventFrame(
                RealtimeSocketEvents.SyncResume,
                mapOf("lastSeq" to 42L)
            )

        val payload = SharedSocketIoProtocol.json.parseToJsonElement(frame.drop(2)).jsonArray
        assertEquals(RealtimeSocketEvents.SyncResume, payload[0].jsonPrimitiveContent)
        assertEquals(JsonPrimitive(42L), payload[1].jsonObject["lastSeq"])
    }

    @Test
    fun decodeFrameHandlesEngineAndSocketPackets() {
        val open =
            assertIs<SharedSocketIoDecodedFrame.EngineOpen>(
                SharedSocketIoProtocol.decodeFrame("""0{"pingInterval":25000,"pingTimeout":20000}""")
            )
        assertEquals(25000L, open.pingInterval)
        assertEquals(20000L, open.pingTimeout)
        assertEquals(SharedSocketIoDecodedFrame.EnginePing, SharedSocketIoProtocol.decodeFrame("2"))
        assertEquals("3", SharedSocketIoProtocol.pongFrame())
        assertEquals(SharedSocketIoDecodedFrame.SocketConnect, SharedSocketIoProtocol.decodeFrame("40"))
    }

    @Test
    fun decodeFrameExtractsSocketEventsAndConnectErrors() {
        val event =
            assertIs<SharedSocketIoDecodedFrame.SocketEvent>(
                SharedSocketIoProtocol.decodeFrame("""42["message:new",{"id":"m1"}]""")
            )
        assertEquals("message:new", event.eventName)
        assertEquals("m1", event.payload.jsonObject["id"]?.jsonPrimitiveContent)

        val error =
            assertIs<SharedSocketIoDecodedFrame.SocketConnectError>(
                SharedSocketIoProtocol.decodeFrame("""44{"message":"unauthorized"}""")
            )
        assertEquals("""{"message":"unauthorized"}""", error.message)
    }

    private val kotlinx.serialization.json.JsonElement.jsonPrimitiveContent: String?
        get() = (this as? JsonPrimitive)?.contentOrNull
}
