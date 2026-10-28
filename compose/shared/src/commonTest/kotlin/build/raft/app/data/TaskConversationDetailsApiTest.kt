package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task v2 head reads (web `TaskConversationDialog` / `TaskProperties` / `HistoryTimeline`). Payloads
 * are the shapes botiverse/slock@3f25e17ed writes: `routes/tasks.ts` `GET /:taskId/history` →
 * `{ events }`, `taskService.ts` `recordTaskEvent` payloads (created / assignee_changed /
 * status_changed / closed / reopened / amended / resource_receipt_recorded), and
 * `routes/channels.ts` `GET /:id/threads/:messageId`.
 */
class TaskConversationDetailsApiTest {
    private val historyBody =
        """
        {"events":[
          {"id":"e1","seq":1,"eventType":"created","actorType":"user","actorName":"artin","createdAt":"2026-10-28T01:00:00.000Z",
           "payload":{"taskNumber":7,"status":"todo","requiresResourceReceipt":false}},
          {"id":"e2","seq":2,"eventType":"assignee_changed","actorType":"user","actorName":"artin","createdAt":"2026-10-28T01:01:00.000Z",
           "payload":{"assigneeType":"agent","assigneeId":"agent-1","previousAssigneeType":null,"previousAssigneeId":null}},
          {"id":"e3","seq":3,"eventType":"status_changed","actorType":"agent","actorName":"Lagoon","createdAt":"2026-10-28T02:00:00.000Z",
           "payload":{"from":"todo","to":"in_progress","forced":false}},
          {"id":"e4","seq":4,"eventType":"amended","actorType":"agent","actorName":null,"createdAt":"2026-10-28T03:00:00.000Z",
           "payload":{"revision":3,"changes":{"title":{"from":"Old","to":"New"},"description":{"from":null,"to":"Body"}}}},
          {"id":"e5","seq":5,"eventType":"resource_receipt_recorded","actorType":"agent","actorName":"Lagoon","createdAt":"2026-10-28T03:30:00.000Z",
           "payload":{"receipt":{"kind":"vm"}}},
          {"id":"e6","seq":6,"eventType":"assignee_changed","actorType":"user","actorName":"artin","createdAt":"2026-10-28T04:00:00.000Z",
           "payload":{"assigneeType":null,"assigneeId":null,"previousAssigneeType":"agent","previousAssigneeId":"agent-1"}},
          {"id":"e7","seq":7,"eventType":"closed","actorType":"system","actorName":null,"createdAt":"2026-10-28T05:00:00.000Z",
           "payload":{"from":"in_progress","to":"closed","forced":true}},
          {"id":"e8","seq":8,"eventType":"reopened","actorType":"user","actorName":"artin","createdAt":"2026-10-28T06:00:00.000Z",
           "payload":{}}
        ]}
        """.trimIndent()

    @Test
    fun parsesTheHistoryEnvelopeInOrder() {
        val events = parseTaskHistory(historyBody)!!
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8"), events.map { it.id })
        assertEquals("created", events[0].eventType)
        assertEquals("artin", events[0].actorName)
        assertNull(events[3].actorName)
        assertEquals("2026-10-28T01:00:00.000Z", events[0].createdAt)
    }

    @Test
    fun rejectsABodyThatIsNotTheEnvelope() {
        assertNull(parseTaskHistory("[]"))
        assertNull(parseTaskHistory("not json"))
        assertEquals(emptyList(), parseTaskHistory("{}"))
    }

    @Test
    fun mapsEveryEventTypeAsWebDoes() {
        val rows = parseTaskHistory(historyBody)!!.toTaskHistoryRows()
        // Resource receipts are CLI-only: not in the person-facing timeline.
        assertEquals(listOf("e1", "e2", "e3", "e4", "e6", "e7", "e8"), rows.map { it.id })

        val created = rows[0]
        assertEquals(TaskHistoryKind.Created, created.kind)
        assertEquals(TaskHistoryDetail.Created(taskNumber = "7", status = TaskStatus.Todo), created.detail)
        assertEquals(TaskStatus.Todo, created.markerStatus)
        assertEquals("artin", created.actor)

        assertEquals(TaskHistoryKind.AssigneeChanged, rows[1].kind)
        assertEquals(TaskHistoryDetail.Assigned(assigneeType = "agent", assigneeId = "agent-1"), rows[1].detail)
        assertNull(rows[1].markerStatus)

        assertEquals(TaskHistoryKind.StatusChanged, rows[2].kind)
        assertEquals(TaskHistoryDetail.Transition(TaskStatus.Todo, TaskStatus.InProgress), rows[2].detail)
        assertEquals(TaskStatus.InProgress, rows[2].markerStatus)

        assertEquals(TaskHistoryKind.Amended, rows[3].kind)
        assertEquals(
            TaskHistoryDetail.FieldChanges(
                listOf(
                    TaskHistoryFieldChange(TaskHistoryField.Title, "Old", "New"),
                    TaskHistoryFieldChange(TaskHistoryField.Description, "—", "Body"),
                )
            ),
            rows[3].detail,
        )
        // No actor name: the raw actor type stands in (web `actorName ?? actorType`).
        assertEquals("agent", rows[3].actor)

        assertEquals(TaskHistoryDetail.Unassigned, rows[4].detail)

        assertEquals(TaskHistoryKind.Closed, rows[5].kind)
        assertEquals(TaskHistoryDetail.Transition(TaskStatus.InProgress, TaskStatus.Closed), rows[5].detail)
        assertEquals("system", rows[5].actor)

        // A reopen without from / to: the marker still reads In Progress; the payload has nothing to say.
        assertEquals(TaskHistoryKind.Reopened, rows[6].kind)
        assertEquals(TaskStatus.InProgress, rows[6].markerStatus)
        assertEquals(TaskHistoryDetail.Raw(""), rows[6].detail)
    }

    @Test
    fun unknownEventsAndStatusesFallBackToRawText() {
        val body =
            """
            {"events":[
              {"id":"x1","eventType":"escalated","actorType":"agent","actorName":"Bot","createdAt":"2026-10-28T01:00:00Z",
               "payload":{"level":2,"note":"urgent","silent":false,"extra":null,"meta":{"a":1}}},
              {"id":"x2","eventType":"status_changed","actorType":"user","actorName":"A","createdAt":"2026-10-28T01:00:00Z",
               "payload":{"from":"todo","to":"blocked"}},
              {"id":"x3","eventType":"created","actorType":"user","actorName":"A","createdAt":"2026-10-28T01:00:00Z",
               "payload":{}}
            ]}
            """.trimIndent()
        val rows = parseTaskHistory(body)!!.toTaskHistoryRows()
        assertEquals(TaskHistoryKind.Other("escalated"), rows[0].kind)
        assertEquals(TaskHistoryDetail.Raw("level: 2 · note: urgent · meta: {\"a\":1}"), rows[0].detail)
        assertNull(rows[0].markerStatus)
        assertEquals(TaskHistoryDetail.TransitionText("todo", "blocked"), rows[1].detail)
        // `from` is the first status the marker can use when `to` is not one.
        assertEquals(TaskStatus.Todo, rows[1].markerStatus)
        assertEquals(TaskHistoryDetail.Created(taskNumber = "—", status = TaskStatus.Todo), rows[2].detail)
    }

    @Test
    fun readsTheSourceMessageOutOfAContextPage() {
        val body =
            """
            {"channelId":"ch-1","targetMessageId":"m-1","messages":[
              {"id":"m-1","channelId":"ch-1","seq":10,"senderType":"user","senderId":"u-1","senderName":"artin",
               "content":"\n  \nThe app's task detail lacks a lot\nsecond line","messageType":"chat","createdAt":"2026-10-27T09:00:00.000Z"}
            ],"hasOlder":false,"hasNewer":false}
            """.trimIndent()
        val preview = parseTaskSourceMessage(body, ChannelId("ch-1"), MessageId("m-1"))!!
        assertEquals(MessageId("m-1"), preview.messageId)
        assertEquals("The app's task detail lacks a lot", preview.firstLine)
        assertEquals("2026-10-27T09:00:00.000Z", preview.createdAt)
        assertEquals(MemberKind.Human, preview.author?.kind)
        assertNull(parseTaskSourceMessage(body, ChannelId("ch-1"), MessageId("other")))
    }

    @Test
    fun readsTheEarlierDiscussionThreadLookup() {
        val parsed =
            parseTaskEarlierDiscussion(
                """{"threadChannelId":"th-1","replyCount":4,"lastReplyAt":"2026-10-01T00:00:00.000Z"}""",
                MessageId("host-1"),
            )!!
        assertEquals(4, parsed.replyCount)
        assertEquals(ChannelId("th-1"), parsed.threadChannelId)
        assertEquals(MessageId("host-1"), parsed.hostMessageId)
    }

    @Test
    fun previewLineIsTheFirstNonBlankLine() {
        assertEquals("hello", taskSourcePreviewLine("\n   \n  hello  \nworld"))
        assertEquals("", taskSourcePreviewLine(" \n "))
    }
}
