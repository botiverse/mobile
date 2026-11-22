package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EngineIoCodecTest {
    @Test
    fun decodesEveryPacketTypeAndKeepsItsData() {
        val cases =
            mapOf(
                "0{}" to EngineIoPacket(EngineIoPacketType.Open, "{}"),
                "1" to EngineIoPacket(EngineIoPacketType.Close),
                "2" to EngineIoPacket(EngineIoPacketType.Ping),
                "3probe" to EngineIoPacket(EngineIoPacketType.Pong, "probe"),
                "42[\"a\"]" to EngineIoPacket(EngineIoPacketType.Message, "2[\"a\"]"),
                "5" to EngineIoPacket(EngineIoPacketType.Upgrade),
                "6" to EngineIoPacket(EngineIoPacketType.Noop),
            )
        cases.forEach { (frame, packet) ->
            assertEquals(packet, EngineIoCodec.decode(frame), frame)
            assertEquals(frame, EngineIoCodec.encode(packet), frame)
        }
    }

    @Test
    fun rejectsEmptyAndUnknownFrames() {
        assertNull(EngineIoCodec.decode(""))
        assertNull(EngineIoCodec.decode("7"))
        assertNull(EngineIoCodec.decode("x2"))
    }

    @Test
    fun openPacketTimersAndMaxPayloadComeFromTheServer() {
        // The values a socket.io 4.8 server sends, and deliberately non-default ones: nothing is assumed.
        val open =
            EngineIoCodec.parseOpen(
                """{"sid":"lv_VI97HAXpY6yYWAAAC","upgrades":[],"pingInterval":3000,"pingTimeout":1500,"maxPayload":4096}""",
            )
        assertEquals(EngineIoOpen("lv_VI97HAXpY6yYWAAAC", 3000, 1500, 4096, emptyList()), open)
    }

    @Test
    fun webSocketUrlTargetsEngineIoV4WebSocketTransport() {
        assertEquals(
            "wss://api.raft.build/socket.io/?EIO=4&transport=websocket",
            EngineIoCodec.webSocketUrl("https://api.raft.build"),
        )
        assertEquals(
            "ws://127.0.0.1:3001/socket.io/?EIO=4&transport=websocket",
            EngineIoCodec.webSocketUrl("http://127.0.0.1:3001/"),
        )
        assertEquals(
            "wss://staging.example/custom/?EIO=4&transport=websocket",
            EngineIoCodec.webSocketUrl("wss://staging.example/ignored-namespace", path = "custom/"),
        )
        assertFailsWith<IllegalArgumentException> { EngineIoCodec.webSocketUrl("ftp://x") }
        assertFailsWith<IllegalArgumentException> { EngineIoCodec.webSocketUrl("https://") }
    }
}
