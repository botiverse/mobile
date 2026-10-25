package build.raft.app.data

import build.raft.app.RaftThreadScreenPayload
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.ThreadId
import build.raft.app.toMessage
import build.raft.app.toRaftThreadMessageItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * RFC 077 `delegatedBy` (assistant launch, task #6) across every surface that carries it.
 *
 * Upstream: botiverse/slock staging dcb95d0ac330414b77e8c6421cf96be114c9c52d.
 * - Wire shape: `packages/server/src/services/messageService.ts`
 *   `DelegatedByLabel = { id, name, displayName, avatarUrl, deleted }`, on every message row
 *   (`delegatedBy: DelegatedByLabel | null`), `message:new`/`message:updated`
 *   (`messageRealtimeProducerRegistry.ts`), search hits (`searchService.ts`) and Activity rows as
 *   `latestDelegatedBy` (`withLatestDelegatedBy`).
 * - Fixtures are the web contract test's: `packages/web/tests/assistantDelegatedMessage.behavior.test.tsx`
 *   (`FRIDAY`, `aliceMessage`, the search hit `result`) and the server's envelope assertion in
 *   `packages/server/src/routes/assistant.api.test.ts` (`displayName ?? name`, `avatarUrl ?? null`).
 * - Reading rules: web `packages/web/src/components/message/delegatedMessage.ts` `readDelegatedBy`.
 */
class MessageDelegatedByContractTest {
    private val friday = """{ "id": "agent-friday", "name": "friday", "displayName": "Friday", "avatarUrl": null, "deleted": false }"""
    private val fridayLabel = MessageDelegatedBy(id = "agent-friday", name = "Friday", avatarUrl = null, deleted = false)

    private fun aliceMessage(extra: String = """, "delegatedBy": $friday"""): String =
        """
        {
          "id": "message-sent",
          "channelId": "channel-qa-x",
          "senderType": "user",
          "senderId": "user-alice",
          "senderName": "Alice",
          "content": "Hi team, the design review moves to Thursday 3pm.",
          "createdAt": "2026-10-21T16:49:00Z",
          "seq": 10,
          "messageType": "chat"$extra
        }
        """.trimIndent()

    private fun restMessage(row: String) =
        parseSharedChannelMessages(
            """{"messages":[$row],"historyLimited":false,"threadSummariesByParentMessageId":{}}""",
            fallbackThreadId = ThreadId("channel-qa-x"),
        ).messages.single()

    @Test
    fun restRealtimeAndThreadRowsReadTheUpstreamLabel() {
        assertEquals(fridayLabel, restMessage(aliceMessage()).delegatedBy)

        val mapper = RealtimeEventMapper()
        assertEquals(fridayLabel, assertIs<MessageNew>(mapper.map(RealtimeSocketEvents.MessageNew, aliceMessage())).message.delegatedBy)
        assertEquals(fridayLabel, assertIs<MessageUpdated>(mapper.map(RealtimeSocketEvents.MessageUpdated, aliceMessage())).message.delegatedBy)

        val wire = Json { ignoreUnknownKeys = true }.decodeFromString(WireMessage.serializer(), aliceMessage())
        val thread =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = RaftThreadScreenPayload(sourceThreadId = "channel-qa-x", status = "loading"),
                page = ThreadMessagesPage(messages = listOf(wire), canLoadMoreBefore = false, canLoadMoreAfter = null),
                session = null,
            )
        assertEquals(fridayLabel, thread.messages.single().delegatedBy)
    }

    @Test
    fun aRetractedMessageKeepsThePlaceholderAsBodyAndReadsDeletedAt() {
        // botiverse/slock staging 6a0429014ffdfc87f614537ce3f9d6c576460418:
        // assistantSendAsOwnerService.ts retractDelegatedMessage sets `deletedAt` and replaces
        // `content` with RETRACTED_DELEGATED_MESSAGE_CONTENT, then emits the row as message:updated.
        val placeholder = "This message was retracted by its sender."
        val retracted =
            aliceMessage(""", "delegatedBy": $friday, "deletedAt": "2026-10-25T12:00:00.000Z"""")
                .replace("Hi team, the design review moves to Thursday 3pm.", placeholder)

        val updated = assertIs<MessageUpdated>(RealtimeEventMapper().map(RealtimeSocketEvents.MessageUpdated, retracted)).message
        assertEquals(placeholder, updated.body)
        assertEquals("2026-10-25T12:00:00.000Z", updated.deletedAt)
        assertEquals("2026-10-25T12:00:00.000Z", restMessage(retracted).deletedAt)

        assertNull(restMessage(aliceMessage()).deletedAt, "absent")
        assertNull(restMessage(aliceMessage(""", "deletedAt": null""")).deletedAt, "null")
    }

    @Test
    fun labelSurvivesTheThreadItemRoundTrip() {
        val message = restMessage(aliceMessage())
        val item = message.toRaftThreadMessageItem(currentUserId = "user-alice")
        assertEquals(fridayLabel, item.delegatedBy)
        assertEquals(fridayLabel, item.toMessage(ThreadId("channel-qa-x"), ChannelId("channel-qa-x"))?.delegatedBy)
    }

    @Test
    fun readsTheLabelLikeWebReadDelegatedBy() {
        // (case, delegatedBy JSON, expected)
        val cases =
            listOf(
                Triple("absent", "", null),
                Triple("null", """, "delegatedBy": null""", null),
                Triple(
                    "deleted assistant",
                    """, "delegatedBy": { "id": "agent-friday", "name": "friday", "displayName": "Friday", "avatarUrl": null, "deleted": true }""",
                    fridayLabel.copy(deleted = true)
                ),
                Triple(
                    "blank displayName falls back to the handle; avatar kept",
                    """, "delegatedBy": { "id": "agent-friday", "name": "friday", "displayName": " ", "avatarUrl": "pixel:cat", "deleted": false }""",
                    MessageDelegatedBy(id = "agent-friday", name = "friday", avatarUrl = "pixel:cat", deleted = false)
                ),
                Triple(
                    "no name left reads as deleted",
                    """, "delegatedBy": { "id": "agent-friday", "name": "", "displayName": null, "avatarUrl": null, "deleted": false }""",
                    MessageDelegatedBy(id = "agent-friday", name = "", avatarUrl = null, deleted = true)
                ),
                Triple("no id is no label", """, "delegatedBy": { "id": "", "name": "friday", "displayName": "Friday", "avatarUrl": null, "deleted": false }""", null),
                // The agent API's `delegated_by: { name }` is another surface; never read as an alias.
                Triple("agent-api snake_case is not an alias", """, "delegated_by": { "name": "friday" }""", null),
            )
        cases.forEach { (name, extra, expected) ->
            assertEquals(expected, restMessage(aliceMessage(extra)).delegatedBy, name)
        }
    }

    @Test
    fun onlyAPersonsMessageCarriesTheLabel() {
        val agentRow = aliceMessage().replace("\"senderType\": \"user\"", "\"senderType\": \"agent\"")
        assertNull(restMessage(agentRow).delegatedBy)
        val systemRow = aliceMessage().replace("\"messageType\": \"chat\"", "\"messageType\": \"system\"")
        assertNull(restMessage(systemRow).delegatedBy)
    }

    @Test
    fun searchHitsAndActivityRowsCarryTheLabel() {
        val hit =
            """
            {
              "id": "message-sent", "channelId": "channel-qa-x", "threadId": null, "parentMessageId": null,
              "parentMessageContent": null, "parentChannelId": "channel-qa-x", "parentChannelName": "qa-x",
              "parentChannelType": "channel", "parentChannelArchivedAt": null, "senderId": "user-alice",
              "senderType": "user", "senderName": "Alice", "senderAvatarUrl": null, "channelName": "qa-x",
              "channelType": "channel", "channelArchivedAt": null, "content": "design review",
              "snippet": "design review", "createdAt": "2026-10-21T16:49:00Z", "delegatedBy": $friday
            }
            """.trimIndent()
        val search = parseSharedSearchItems("""{"results":[$hit],"hasMore":false}""") ?: error("search did not parse")
        assertEquals(fridayLabel, search.items.single().latestDelegatedBy)

        val channelRow =
            """
            {"kind":"channel","channelId":"channel-qa-x","channelName":"qa-x","channelType":"channel",
             "lastMessageId":"message-sent","lastMessageAt":"2026-10-21T16:49:00Z","lastMessagePreview":"design review",
             "lastMessageSenderType":"user","lastMessageSenderId":"user-alice","lastMessageSenderName":"Alice",
             "unreadCount":1,"hasMention":false,"latestDelegatedBy":$friday,"latestRetracted":false,"parentRetracted":false}
            """.trimIndent()
        val threadRow =
            """
            {"kind":"thread","threadChannelId":"t-1","parentMessageId":"p-1","parentChannelId":"channel-qa-x",
             "parentChannelName":"qa-x","parentChannelType":"channel","parentMessagePreview":"p",
             "parentMessageSenderType":"user","parentMessageSenderId":"user-alice","latestActivityPreview":"r",
             "latestActivitySenderType":"user","latestActivitySenderId":"user-alice","latestActivitySenderName":"Alice",
             "latestActivityMessageId":"r-1","lastActivityAt":"2026-10-21T16:49:00Z","replyCount":1,"unreadCount":0,
             "hasMention":false,"latestDelegatedBy":null,"latestRetracted":false,"parentRetracted":false}
            """.trimIndent()
        val inbox =
            parseSharedInboxSnapshot("""{"items":[$channelRow,$threadRow],"hasMore":false,"totalCount":2,"totalUnreadCount":1}""")
                ?: error("inbox did not parse")
        assertEquals(listOf(fridayLabel, null), inbox.items.map { it.latestDelegatedBy })
    }
}
