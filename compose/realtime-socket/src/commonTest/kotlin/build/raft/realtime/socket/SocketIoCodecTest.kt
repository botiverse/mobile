package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    fun disconnectAndNamespacesAreParsed() {
        assertEquals(SocketIoPacket.Disconnect(), decode("1"))
        assertEquals("1", SocketIoCodec.encode(SocketIoPacket.Disconnect()))
        val admin = decode("2/admin,3[\"x\"]")
        assertEquals(SocketIoPacket.Event("x", ackId = 3, namespace = "/admin"), admin)
        assertEquals("2/admin,3[\"x\"]", SocketIoCodec.encode(admin))
        assertEquals(SocketIoPacket.Disconnect("/admin"), decode("1/admin,"))
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
    fun binaryPacketsEncodeTheHeaderAndTheirFramesInOrder() {
        val bytes = listOf(RaftSocketBinary(byteArrayOf(1, 2, 3)), RaftSocketBinary(byteArrayOf(9)))
        val event =
            SocketIoPacket.Event(
                name = "upload",
                args = listOf(RaftSocketBinary.placeholder(0), buildJsonObject { put("n", 1) }, RaftSocketBinary.placeholder(1)),
                ackId = 4,
                namespace = "/files",
                attachments = bytes,
            )
        val encoded = SocketIoCodec.encodeWithAttachments(event)
        assertEquals("52-/files,4[\"upload\",{\"_placeholder\":true,\"num\":0},{\"n\":1},{\"_placeholder\":true,\"num\":1}]", encoded.text)
        assertEquals(bytes, encoded.attachments)
        assertEquals("61-7[{\"_placeholder\":true,\"num\":0}]", SocketIoCodec.encode(SocketIoPacket.Ack(7, listOf(RaftSocketBinary.placeholder(0)), attachments = bytes.take(1))))
    }

    @Test
    fun reconstructorCompletesThePacketAfterItsLastFrame() {
        val reconstructor = SocketIoBinaryReconstructor()
        val header = assertIs<SocketIoDecodeResult.Packet>(SocketIoCodec.decode("52-[\"up\",{\"_placeholder\":true,\"num\":0},{\"_placeholder\":true,\"num\":1}]"))
        reconstructor.start(header.packet, header.attachmentsExpected)
        assertEquals(null, reconstructor.add(byteArrayOf(1)))
        val complete = assertIs<SocketIoPacket.Event>(reconstructor.add(byteArrayOf(2, 2)))
        assertEquals(listOf(RaftSocketBinary(byteArrayOf(1)), RaftSocketBinary(byteArrayOf(2, 2))), complete.attachments)
        assertEquals(false, reconstructor.pending)
        assertEquals(null, reconstructor.add(byteArrayOf(3)), "a stray frame completes nothing")
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

    @Test
    fun theLargestAckIdIsAcceptedAndOneMoreIsNot() {
        assertEquals(SocketIoCodec.MAX_ACK_ID, assertIs<SocketIoPacket.Ack>(decode("3${SocketIoCodec.MAX_ACK_ID}[]")).ackId)
        assertEquals("ack_id", invalid("3${SocketIoCodec.MAX_ACK_ID + 1}[]"))
    }

    @Test
    fun eventArgumentsKeepTheirJsonTypes() {
        val event = assertIs<SocketIoPacket.Event>(decode("2[\"heartbeat\",{\"seq\":41,\"ts\":1.5},[1,2],null,true]"))
        assertEquals(4, event.args.size)
        assertIs<JsonObject>(event.args[0])
        assertIs<JsonArray>(event.args[1])
    }
}
