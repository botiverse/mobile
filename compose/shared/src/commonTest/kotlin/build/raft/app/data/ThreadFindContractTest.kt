package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Search in this thread's reads of `GET /messages/channel/:channelId` (botiverse/slock staging
 * e4c09353fdc2e06517df3565e31afaab63666e53):
 *
 * - the envelope is `packages/server/src/routes/messages.ts` `res.json({ messages,
 *   threadSummariesByParentMessageId, historyLimited, ...hiddenHistory, messageWindow })`;
 * - a row is the `messages` table's read columns (`db/schema.ts` `messages` without
 *   `searchText` / `searchVector`) after `messageService.enrichWithSenderNames` (`senderName`,
 *   `attachments`, `reactions`), oldest first (`listMessagesWithCoverage`);
 * - the web pages it with `limit=100` and `before` / `after` a seq
 *   (`packages/web/src/features/thread/useThreadSearch.ts` `loadThreadSearchCorpus`) and keeps the
 *   thread channel's rows only (`threadMessages.ts` `onlyThreadMessages`).
 */
class ThreadFindContractTest {
    @Test
    fun readsTheThreadsRowsOfAnUpstreamChannelPage() {
        val load = assertIs<ThreadFindPageLoad.Page>(parseSharedThreadFindPage(UPSTREAM_PAGE, THREAD))

        // A row of another channel (a permalink's parent) is not the thread's; it still counts as sent.
        assertEquals(3, load.rawCount)
        assertEquals(
            listOf(
                ThreadFindMessage(id = REPLY_1, seq = 41, senderName = "Artin", content = "Ship the **launch** checklist"),
                ThreadFindMessage(id = REPLY_2, seq = 42, senderName = "Lagoon", content = "Launch notes are in <#c-docs>"),
            ),
            load.messages,
        )
    }

    @Test
    fun aBodyItCannotReadIsAFailureNeverNoMessages() {
        listOf(
            "not json",
            """[{"id":"$REPLY_1"}]""",
            """{"messages":{"id":"$REPLY_1"}}""",
            // Rows were sent and none of them decode.
            """{"messages":[{"seq":"x"},{"content":3}],"historyLimited":false}""",
        ).forEach { body ->
            val failed = assertIs<ThreadFindPageLoad.Failed>(parseSharedThreadFindPage(body, THREAD), body)
            assertEquals("thread_find_contract_mismatch", failed.error.code)
        }
        // An empty page is the thread's end, not a failure.
        assertEquals(
            ThreadFindPageLoad.Page(emptyList(), rawCount = 0),
            parseSharedThreadFindPage("""{"messages":[],"threadSummariesByParentMessageId":{},"historyLimited":false}""", THREAD),
        )
    }

    @Test
    fun asksTheThreadChannelWithTheWebsCursorAndAnExplicitPriority() = runTest {
        val client = RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(200, UPSTREAM_PAGE) }
        val api = NetworkRaftThreadFindApi(client)

        assertIs<ThreadFindPageLoad.Page>(api.page(" $THREAD ", beforeSeq = 41, afterSeq = null, limit = THREAD_FIND_PAGE_LIMIT, DataRequestPriority.Visible))
        api.page(THREAD, beforeSeq = null, afterSeq = 42, limit = THREAD_FIND_PAGE_LIMIT, DataRequestPriority.Visible)

        assertEquals(listOf("messages/channel/$THREAD"), client.calls.map { it.path }.distinct())
        assertEquals(listOf(DataRequestPriority.Visible), client.calls.map { it.priority }.distinct())
        assertEquals(
            listOf(
                listOf("limit" to "100", "before" to "41"),
                listOf("limit" to "100", "after" to "42"),
            ),
            client.calls.map { it.query },
        )
    }

    @Test
    fun aRefusedOrUnreadablePageKeepsItsTraceId() = runTest {
        listOf(
            HomeNetworkResponse(500, null, errorBodyText = """{"error":"Failed to list messages"}""", responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE))) to "server_unavailable",
            HomeNetworkResponse(403, null, errorBodyText = """{"error":"You do not have access to this channel"}""", responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE))) to "thread_find_http_403",
            HomeNetworkResponse(200, "not json", responseHeaders = mapOf("X-Trace-Id" to listOf(TRACE))) to "thread_find_contract_mismatch",
        ).forEach { (response, code) ->
            val api = NetworkRaftThreadFindApi(RecordingJointNetworkClient { _, _ -> response })
            val failed = assertIs<ThreadFindPageLoad.Failed>(api.page(THREAD, beforeSeq = 41, afterSeq = null, limit = THREAD_FIND_PAGE_LIMIT, DataRequestPriority.Visible))
            assertEquals(code, failed.error.code)
            assertEquals(TRACE, failed.error.traceId)
        }
    }

    private companion object {
        const val THREAD = "7d2a4a3e-55c1-4b1e-9a43-0b1b1c3d9e01"
        const val PARENT_CHANNEL = "0c9f3f8e-2f7a-4d55-8f6b-4b0f6c1d2e33"
        const val REPLY_1 = "a1f0c3d2-6b7e-4c8a-9d10-2e3f4a5b6c71"
        const val REPLY_2 = "a1f0c3d2-6b7e-4c8a-9d10-2e3f4a5b6c72"
        const val PARENT = "a1f0c3d2-6b7e-4c8a-9d10-2e3f4a5b6c70"
        const val TRACE = "0123456789abcdef0123456789abcdef"

        /** One page as the route sends it; field names and envelope from the slock SHA above. */
        val UPSTREAM_PAGE =
            """
            {
              "messages": [
                {
                  "id": "$PARENT", "seq": 40, "channelId": "$PARENT_CHANNEL", "serverId": "s-1",
                  "senderType": "user", "senderId": "u-1", "agentSendKey": null, "randomId": null,
                  "delegatedByAgentId": null, "voiceCallId": null, "deletedAt": null,
                  "messageType": "chat", "content": "The launch thread", "actionMetadata": null,
                  "threadId": "$THREAD", "causalActorType": null, "causalActorId": null, "systemSubtype": null,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-11-16T08:00:00.000Z", "updatedAt": "2026-11-16T08:00:00.000Z",
                  "senderName": "Artin", "attachments": [], "reactions": []
                },
                {
                  "id": "$REPLY_1", "seq": 41, "channelId": "$THREAD", "serverId": "s-1",
                  "senderType": "user", "senderId": "u-1", "agentSendKey": null, "randomId": "r-1",
                  "delegatedByAgentId": null, "voiceCallId": null, "deletedAt": null,
                  "messageType": "chat", "content": "Ship the **launch** checklist", "actionMetadata": null,
                  "threadId": null, "causalActorType": null, "causalActorId": null, "systemSubtype": null,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-11-16T08:01:00.000Z", "updatedAt": "2026-11-16T08:01:00.000Z",
                  "senderName": "Artin", "attachments": [], "reactions": []
                },
                {
                  "id": "$REPLY_2", "seq": 42, "channelId": "$THREAD", "serverId": "s-1",
                  "senderType": "agent", "senderId": "a-1", "agentSendKey": "k-1", "randomId": null,
                  "delegatedByAgentId": null, "voiceCallId": null, "deletedAt": null,
                  "messageType": "chat", "content": "Launch notes are in <#c-docs>", "actionMetadata": null,
                  "threadId": null, "causalActorType": null, "causalActorId": null, "systemSubtype": null,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-11-16T08:02:00.000Z", "updatedAt": "2026-11-16T08:02:00.000Z",
                  "senderName": "Lagoon", "attachments": [], "reactions": []
                }
              ],
              "threadSummariesByParentMessageId": {},
              "historyLimited": false,
              "messageWindow": {
                "schemaVersion": 1, "domain": "receiver_visible_messages_v1", "serverId": "s-1",
                "receiverKind": "user", "receiverId": "u-1", "scopeId": "$THREAD",
                "coveredAfterSeq": 0, "coveredFromSeq": 41, "coveredThroughSeq": 42,
                "remoteHighWaterSeq": 42, "hasGap": false, "hasNewer": false, "completeThroughLatest": true
              }
            }
            """.trimIndent()
    }
}
