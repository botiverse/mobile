package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Golden packets as socket.io-parser 4.x (protocol v5) writes them. */
class SocketIoCodecTest {
    private fun decode(text: String): SocketIoPacket = assertIs<SocketIoDecodeResult.Packet>(SocketIoCodec.decode(text), text).packet

    private fun invalid(text: String): String = assertIs<SocketIoDecodeResult.Invalid>(SocketIoCodec.decode(text), text).reason

    @Test
    fun connectCarriesTheAuthObjectOutAndTheSocketIdBack() {
        val auth = buildJsonObject { put("token", "t"); put("clientKind", "mobile") }
        assertEquals("0{\"token\":\"t\",\"clientKind\":\"mobile\"}", SocketIoCodec.encode(SocketIoPacket.Connect(data = auth)))
        assertEquals("0", SocketIoCodec.encode(SocketIoPacket.Connect()))
        val connected = assertIs<SocketIoPacket.Connect>(decode("0{\"sid\":\"oSO0OpakMV_3jnilAAAA\"}"))
        assertEquals(JsonPrimitive("oSO0OpakMV_3jnilAAAA"), connected.data?.get("sid"))
    }

    @Test
    fun eventsWithAndWithoutAckIdsRoundTrip() {
        val plain = SocketIoPacket.Event("message:new", listOf(buildJsonObject { put("seq", 5) }))
        assertEquals("2[\"message:new\",{\"seq\":5}]", SocketIoCodec.encode(plain))
        assertEquals(plain, decode("2[\"message:new\",{\"seq\":5}]"))

        // A server event asking the client for an ack (emitDesktopNotificationWithAck): a normal packet.
        val withAck = decode("212[\"notification:desktop\",{\"title\":\"Hi\"}]")
        assertEquals(SocketIoPacket.Event("notification:desktop", listOf(buildJsonObject { put("title", "Hi") }), ackId = 12), withAck)

        // The client's `sync:horizon` with no payload, only the ack id.
        assertEquals("27[\"sync:horizon\"]", SocketIoCodec.encode(SocketIoPacket.Event("sync:horizon", ackId = 7)))
        assertEquals("2[\"join:channel\",\"c1\"]", SocketIoCodec.encode(SocketIoPacket.Event("join:channel", listOf(JsonPrimitive("c1")))))
    }

    @Test
    fun acksRoundTripInBothDirections() {
        assertEquals(SocketIoPacket.Ack(7, listOf(buildJsonObject { put("horizon", "752") })), decode("37[{\"horizon\":\"752\"}]"))
        assertEquals("312[{\"socketId\":\"abc\"}]", SocketIoCodec.encode(SocketIoPacket.Ack(12, listOf(buildJsonObject { put("socketId", "abc") }))))
        assertEquals(SocketIoPacket.Ack(0, emptyList()), decode("30[]"))
    }

    @Test
    fun connectErrorIsTheServerMessageInV5AndLegacyForm() {
        val v5 = assertIs<SocketIoPacket.ConnectError>(decode("4{\"message\":\"Invalid or expired token\"}"))
        assertEquals("Invalid or expired token", v5.message)
        val withData = assertIs<SocketIoPacket.ConnectError>(decode("4{\"message\":\"SSO_REQUIRED\",\"data\":{\"serverId\":\"s\"}}"))
        assertEquals(buildJsonObject { put("serverId", "s") }, withData.data)
        assertEquals("Not a member of this server", assertIs<SocketIoPacket.ConnectError>(decode("4\"Not a member of this server\"")).message)
        assertEquals(
            SocketIoPacket.ConnectError("x", null),
            decode(SocketIoCodec.encode(SocketIoPacket.ConnectError("x"))),
        )
    }

    @Test
    fun binaryEventsAndAcksCarryAttachmentPlaceholders() {
        val header = assertIs<SocketIoDecodeResult.Packet>(SocketIoCodec.decode("51-[\"upload\",{\"_placeholder\":true,\"num\":0}]"))
        assertEquals(1, header.attachmentsExpected)
        assertEquals(SocketIoPacket.Event("upload", listOf(RaftSocketBinary.placeholder(0))), header.packet)

        val ack = assertIs<SocketIoDecodeResult.Packet>(SocketIoCodec.decode("62-/files,3[{\"a\":{\"_placeholder\":true,\"num\":1}},{\"_placeholder\":true,\"num\":0}]"))
        assertEquals(2, ack.attachmentsExpected)
        assertEquals("/files", ack.packet.namespace)
        assertEquals(3L, assertIs<SocketIoPacket.Ack>(ack.packet).ackId)

        // Zero attachments is legal (socket.io writes 50-... for an empty Buffer list).
        assertEquals(0, assertIs<SocketIoDecodeResult.Packet>(SocketIoCodec.decode("50-[\"x\"]")).attachmentsExpected)
    }

    @Test
    fun malformedPacketsHaveStableReasons() {
        val cases =
            mapOf(
                "" to "empty",
                "9" to "unknown_type",
                "2{\"not\":\"array\"}" to "bad_payload",
                "2[]" to "bad_payload",
                "2[5]" to "bad_payload",
                "2[\"x\"" to "json",
                "3[]" to "bad_payload",
                "37{}" to "bad_payload",
                "1{}" to "bad_payload",
                "0[]" to "bad_payload",
                "4{}" to "bad_payload",
                "45{\"message\":\"x\"}" to "bad_payload",
                "299999999999[\"x\"]" to "ack_id",
                "5[\"x\"]" to "attachments",
                "5x-[\"x\"]" to "attachments",
                "51-[\"x\",{\"_placeholder\":true,\"num\":1}]" to "attachments",
                "7" to "unknown_type",
            )
        cases.forEach { (text, reason) -> assertEquals(reason, invalid(text), text) }
    }
}
