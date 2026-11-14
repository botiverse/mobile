package build.raft.realtime.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftSocketIoClientAckTest {
    @Test
    fun clientAckRoundTripTimeoutAndConnectionLoss() = runTest {
        val harness = SocketHarness(this)
        // A long heartbeat so only the ack timeout is in play.
        val session = harness.connectRoot { open(pingInterval = 60_000, pingTimeout = 60_000) }
        val results = mutableListOf<RaftAckResult>()

        harness.root.emitWithAck("sync:horizon") { results += it }
        runCurrent()
        assertEquals(listOf("420[\"sync:horizon\"]"), session.receivedTexts())
        session.push("430[{\"horizon\":\"752\"}]")
        runCurrent()
        assertEquals(listOf<RaftAckResult>(RaftAckResult.Acked(listOf(buildJsonObject { put("horizon", "752") }))), results)

        // An older server never answers: the 10 s timeout (iOS's) reports it, once.
        harness.root.emitWithAck("sync:horizon") { results += it }
        runCurrent()
        assertEquals(listOf("421[\"sync:horizon\"]"), session.receivedTexts())
        advanceTimeBy(RaftSocketIoClient.DEFAULT_ACK_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertEquals(1, results.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(RaftAckResult.Timeout, results.last())
        session.push("431[{\"horizon\":\"late\"}]")
        runCurrent()
        assertTrue("dropped:ack:ack_unknown_id" in harness.listener.calls, "a late ack is dropped, not delivered")
        assertEquals(2, results.size)

        harness.root.emitWithAck("sync:horizon") { results += it }
        runCurrent()
        session.fail()
        runCurrent()
        assertEquals(RaftAckResult.NotDelivered("connection_closed"), results.last())
        advanceTimeBy(RaftSocketIoClient.DEFAULT_ACK_TIMEOUT_MILLIS * 2)
        runCurrent()
        assertEquals(3, results.size, "the timeout never fires for an ack already failed")
    }

    @Test
    fun emitWithAckWithoutAConnectionIsNotDelivered() = runTest {
        val harness = SocketHarness(this)
        val results = mutableListOf<RaftAckResult>()
        harness.root.emitWithAck("sync:horizon") { results += it }
        harness.root.emit("presence:activity")
        runCurrent()
        assertEquals(listOf<RaftAckResult>(RaftAckResult.NotDelivered("not_connected")), results)
        assertTrue(harness.logs.any { it.startsWith("realtime_event_dropped reason=not_connected event=presence:activity") }, harness.logs.toString())
    }

    @Test
    fun ackIdsWrapAroundAtTheLargestId() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        harness.root.setNextAckIdForTests(SocketIoCodec.MAX_ACK_ID)
        harness.root.emitWithAck("a") {}
        harness.root.emitWithAck("b") {}
        runCurrent()
        assertEquals(listOf("42${SocketIoCodec.MAX_ACK_ID}[\"a\"]", "420[\"b\"]"), session.receivedTexts())
    }

    @Test
    fun serverAsksForAnAckAndTheClientAnswersOnceOnThatConnection() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot(sid = "desk-1")
        // emitDesktopNotificationWithAck: the server waits for each desktop socket's { socketId }.
        session.push("425[\"notification:desktop\",{\"title\":\"Hi\"}]")
        runCurrent()
        val ack = harness.listener.acks.single()
        assertEquals(5L, ack.ackId)
        assertEquals("notification:desktop", harness.listener.events.single().name)

        ack.send(buildJsonObject { put("socketId", "desk-1") })
        ack.send(buildJsonObject { put("socketId", "again") })
        runCurrent()
        assertEquals(listOf("435[{\"socketId\":\"desk-1\"}]"), session.receivedTexts())
        assertTrue("dropped:notification:desktop:ack_already_sent" in harness.listener.calls)

        // An ack asked on a connection that is gone is never sent on the next one.
        session.push("426[\"notification:desktop\",{}]")
        runCurrent()
        val stale = harness.listener.acks.last()
        session.fail()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        val next = harness.server.last
        next.open()
        runCurrent()
        next.acceptConnect(sid = "desk-2")
        runCurrent()
        next.received()
        stale.send(JsonPrimitive("late"))
        runCurrent()
        assertEquals(emptyList(), next.receivedTexts())
        assertTrue("dropped:notification:desktop:ack_stale_connection" in harness.listener.calls)
    }

    @Test
    fun unlistedServerEventsAreLoggedOnceAndCountedNeverDelivered() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        session.push("42[\"read_progress:updated\",{}]")
        session.push("427[\"read_progress:updated\",{}]")
        session.push("42[\"message:new\",{\"seq\":1}]")
        runCurrent()
        assertEquals(listOf("message:new"), harness.listener.events.map { it.name })
        assertEquals(
            listOf("projection_value_unknown source=realtime_socket field=event_name value=read_progress:updated"),
            harness.logs.filter { it.startsWith("projection_value_unknown") },
        )
        assertEquals(1, harness.logs.count { it.startsWith("realtime_event_dropped reason=unlisted event=read_progress:updated") })
        assertEquals(mapOf("read_progress:updated" to 2), harness.diagnostics().unlistedEvents)
        assertEquals(emptyList(), session.receivedTexts(), "an unlisted event's ack request is not answered")
    }

    @Test
    fun aNullAllowlistDeliversEveryEvent() = runTest {
        val harness = SocketHarness(this, allowedEvents = null)
        val session = harness.connectRoot()
        session.push("42[\"anything:new\",1,\"two\"]")
        runCurrent()
        assertEquals(RaftSocketEvent("anything:new", listOf(JsonPrimitive(1), JsonPrimitive("two"))), harness.listener.events.single())
    }

    @Test
    fun malformedStrayAndForeignFramesAreDroppedWithAReason() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        session.push("42[\"message:new\"")
        session.push("49")
        session.push("42/admin,[\"x\"]")
        session.pushBinary(byteArrayOf(1, 2))
        session.push("7")
        runCurrent()
        val drops = harness.diagnostics().droppedByReason
        assertEquals(1, drops["socketio_json"])
        assertEquals(1, drops["socketio_unknown_type"])
        assertEquals(1, drops["namespace_unknown"])
        assertEquals(1, drops["binary_unexpected"])
        assertEquals(1, drops["engine_packet_invalid"])
        assertTrue("dropped:message:new:socketio_json" in harness.listener.calls)
        assertIs<RaftSocketState.Connected>(harness.root.state.value, "bad frames never break the connection")
    }

    @Test
    fun binaryEventsArriveWithTheirAttachmentsAndGoOutAsFrames() = runTest {
        val harness = SocketHarness(this)
        val session = harness.connectRoot()
        session.push("451-[\"file\",{\"name\":\"a.bin\",\"data\":{\"_placeholder\":true,\"num\":0}}]")
        runCurrent()
        assertEquals(emptyList(), harness.listener.events, "the event waits for its attachment")
        session.pushBinary(byteArrayOf(7, 8, 9))
        runCurrent()
        assertEquals(listOf(RaftSocketBinary(byteArrayOf(7, 8, 9))), harness.listener.events.single().attachments)

        // A text frame before the attachments abandons the binary packet (socket.io-parser does too).
        session.push("451-[\"file\",{\"_placeholder\":true,\"num\":0}]")
        session.push("42[\"message:new\",{}]")
        runCurrent()
        assertEquals(listOf("file", "message:new"), harness.listener.events.map { it.name })
        assertEquals(1, harness.diagnostics().droppedByReason["binary_interrupted"])

        harness.root.emit("upload", listOf(RaftSocketBinary.placeholder(0)), listOf(RaftSocketBinary(byteArrayOf(1, 2))))
        runCurrent()
        val frames = session.received()
        assertEquals(RaftWebSocketFrame.Text("451-[\"upload\",{\"_placeholder\":true,\"num\":0}]"), frames[0])
        assertEquals(RaftWebSocketFrame.Binary(byteArrayOf(1, 2)), frames[1])

        // A binary ack answers a client ack request.
        val results = mutableListOf<RaftAckResult>()
        harness.root.emitWithAck("download") { results += it }
        runCurrent()
        session.received()
        session.push("461-0[{\"_placeholder\":true,\"num\":0}]")
        session.pushBinary(byteArrayOf(5))
        runCurrent()
        assertEquals(
            listOf<RaftAckResult>(RaftAckResult.Acked(listOf(RaftSocketBinary.placeholder(0)), listOf(RaftSocketBinary(byteArrayOf(5))))),
            results,
        )
    }
}
