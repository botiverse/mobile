package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.LinkCardOperationChanged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 076 §6.8 receipt hints (slock `services/linkCardReceiptHints.ts`) and the status read they lead to. */
class LinkCardOperationHintTest {
    private val operation = "0b4d1c1e-6c36-4c1b-9a43-1f1e2d3c4b5a"

    @Test
    fun hintIsSubscribedAndMapsFromTheSnakeCaseWire() {
        assertTrue(RealtimeSocketEvents.LinkCardOperationChanged in RealtimeSocketEvents.ServerWireEvents)
        val mapper = RealtimeEventMapper()
        assertEquals(
            LinkCardOperationChanged(operation, 3),
            mapper.map("link_card.operation_changed", """{"operation_id":"$operation","receipt_revision":3}"""),
        )
        listOf(
            """{"operation_id":"","receipt_revision":1}""",
            """{"operation_id":"$operation","receipt_revision":-1}""",
            """{"operationId":"$operation","receiptRevision":1}""",
        ).forEach { payload -> assertNull(mapper.map("link_card.operation_changed", payload), payload) }
    }

    @Test
    fun statusAnswerReadsTheReceiptAndItsHint() {
        val receipt = assertIs<AppLinkOperationStatus.Receipt>(
            parseAppLinkStatusAnswer(
                200,
                """{"operation_id":"$operation","state":"done","receipt_revision":4,"hint":{"tone":"success","text":"Done. Open the app to see the result."},"app_result":{"toast":{"text":" Merged "}}}""",
            ),
        )
        assertEquals("done", receipt.state)
        assertEquals(4L, receipt.receiptRevision)
        assertEquals("Done. Open the app to see the result.", receipt.hint)
        assertEquals("success", receipt.tone)
        assertEquals("Merged", receipt.appToast)
        assertTrue(receipt.isTerminal)

        val open = assertIs<AppLinkOperationStatus.Receipt>(
            parseAppLinkStatusAnswer(200, """{"operation_id":"$operation","state":"accepted","receipt_revision":2}"""),
        )
        assertEquals(false, open.isTerminal)
    }

    @Test
    fun statusAnswerClosesOrLeavesOpen() {
        assertEquals(AppLinkOperationStatus.Closed(reread = false), parseAppLinkStatusAnswer(404, """{"error":"Not found"}"""))
        assertEquals(
            AppLinkOperationStatus.Closed(reread = true),
            parseAppLinkStatusAnswer(200, """{"operation_id":"$operation","state":"not_visible"}"""),
        )
        assertEquals(AppLinkOperationStatus.Open, parseAppLinkStatusAnswer(429, """{"retry_after":5}"""))
        assertEquals(AppLinkOperationStatus.Open, parseAppLinkStatusAnswer(200, """{"operation_id":"$operation","state":"done"}"""))
    }
}
