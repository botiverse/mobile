package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire contract for the Activity tab Overview, verified against botiverse/slock
 * c1105a1766e9836429826d304f7ea26fdea1a1fe:
 * - packages/shared/src/activityTurnSummary.ts (`ActivityTurnSummary`), vector from
 *   packages/shared/src/activityTurnSummary.test.ts "folds a turn" (finish("completed", 8_000));
 * - packages/server/src/routes/agents.api.test.ts "activity-summaries hides where a turn came from"
 *   (`{ hidden }` triggers, `next` = "<endedAt>:<uuid>");
 * - packages/web/tests/agentActivityOverview.behavior.test.tsx (stored row `id`, reply context).
 */
class AgentActivityOverviewParserTest {
    private val sharedVector = """
        {"v":1,"startedAt":1000,"endedAt":8000,"outcome":"completed",
         "trigger":{"target":"#proj-runtime:9b526607","sender":"Tenny","messageId":"07a75d55"},
         "actions":[{"kind":"read_messages","count":1},{"kind":"search","count":1},{"kind":"read_file","count":1},
                    {"kind":"run_checks","count":1},{"kind":"edit_file","count":1}],
         "toolCalls":5,
         "problems":[{"kind":"run_checks","reason":"exit_nonzero","exitCode":1,"at":7600},
                     {"kind":"edit_file","reason":"call_failed","at":7710},
                     {"kind":"send_message","reason":"send_held","at":7800}],
         "problemCount":3,"messagesSent":2,"lastReplyMessageId":"d2c98a0b"}
    """.trimIndent()

    private fun base(startedAt: Long, endedAt: Long, trigger: String?): String =
        """{"v":1,"outcome":"completed","actions":[],"toolCalls":0,"problems":[],"problemCount":0,"messagesSent":0,""" +
            """"startedAt":$startedAt,"endedAt":$endedAt""" + (trigger?.let { ""","trigger":$it""" } ?: "") + "}"

    @Test
    fun parsesTheSharedAccumulatorVector() {
        val page = assertIsNotNull(parseSharedAgentActivityTurnPage("""{"summaries":[$sharedVector]}"""))
        assertNull(page.next)
        val turn = page.turns.single()
        assertNull(turn.id)
        assertEquals(1000L, turn.startedAtEpochMillis)
        assertEquals(8000L, turn.endedAtEpochMillis)
        assertEquals(AgentActivityTurnOutcome.Completed, turn.outcome)
        assertEquals(
            AgentActivityTurnTrigger.Visible(target = "#proj-runtime:9b526607", channelId = null, sender = "Tenny", messageId = "07a75d55"),
            turn.trigger
        )
        assertEquals(
            listOf(
                AgentActivityActionKind.ReadMessages,
                AgentActivityActionKind.Search,
                AgentActivityActionKind.ReadFile,
                AgentActivityActionKind.RunChecks,
                AgentActivityActionKind.EditFile
            ),
            turn.actions.map { it.kind }
        )
        assertEquals(5, turn.toolCalls)
        assertEquals(
            listOf(
                AgentActivityTurnProblem(AgentActivityActionKind.RunChecks, AgentActivityProblemReason.ExitNonzero, 1, 7600),
                AgentActivityTurnProblem(AgentActivityActionKind.EditFile, AgentActivityProblemReason.CallFailed, null, 7710),
                AgentActivityTurnProblem(AgentActivityActionKind.SendMessage, AgentActivityProblemReason.SendHeld, null, 7800)
            ),
            turn.problems
        )
        // Failed checks are process information, not a step that went wrong (web turnProblemCount).
        assertEquals(2, turn.problemStepCount)
        assertEquals(2, turn.messagesSent)
        assertEquals(MessageId("d2c98a0b"), turn.lastReplyMessageId)
        assertEquals(
            AgentActivityTriggerDescription.Thread(sender = "@Tenny", channel = "#proj-runtime"),
            turn.trigger.describe()
        )
    }

    @Test
    fun parsesServerHiddenTriggersRowIdsAndCursor() {
        val body = """
            {"summaries":[
              ${base(5, 6, """{"target":"#summary-public","channelId":"c-public","sender":"richard","messageId":"m1"}""").dropLast(1)},"id":"00000000-0000-4000-8000-00000000000a"},
              ${base(3, 4, """{"hidden":"private_channel"}""")},
              ${base(1, 2, """{"hidden":"dm"}""")},
              ${base(0, 1, null)}
            ],"next":"1:00000000-0000-4000-8000-000000000001"}
        """.trimIndent()
        val page = assertIsNotNull(parseSharedAgentActivityTurnPage(body))
        assertEquals("1:00000000-0000-4000-8000-000000000001", page.next)
        assertEquals("00000000-0000-4000-8000-00000000000a", page.turns[0].id)
        assertEquals("00000000-0000-4000-8000-00000000000a", page.turns[0].key)
        assertEquals("3-4", page.turns[1].key)
        assertEquals(
            listOf(
                AgentActivityTriggerDescription.Message(sender = "@richard", channel = "#summary-public"),
                AgentActivityTriggerDescription.HiddenPrivateChannel,
                AgentActivityTriggerDescription.HiddenDm,
                null
            ),
            page.turns.map { it.trigger.describe() }
        )
    }

    @Test
    fun describesEveryVisibleTriggerShapeLikeWeb() {
        fun visible(target: String?, sender: String?) =
            AgentActivityTurnTrigger.Visible(target = target, channelId = "c", sender = sender, messageId = null)
        assertEquals(AgentActivityTriggerDescription.DmFrom("@richard"), visible("dm:@richard", "richard").describe())
        assertEquals(AgentActivityTriggerDescription.AppEvent, visible("agent-event:github", null).describe())
        assertEquals(AgentActivityTriggerDescription.Target("#ops"), visible("#ops", null).describe())
        assertEquals(AgentActivityTriggerDescription.Message("@ann", "#ops"), visible("#ops", "ann").describe())
        assertNull(visible(null, "ann").describe())
    }

    @Test
    fun unknownEnumsDegradeAndMalformedRowsAreDropped() {
        val newer = base(10, 20, null).replace("\"outcome\":\"completed\"", "\"outcome\":\"timed_out\"")
            .replace("\"actions\":[]", "\"actions\":[{\"kind\":\"browse\",\"count\":2}]")
        val missingRequired = """{"v":1,"startedAt":1,"outcome":"completed"}"""
        val page = assertIsNotNull(parseSharedAgentActivityTurnPage("""{"summaries":[$newer,$missingRequired]}"""))
        val turn = page.turns.single()
        assertEquals(AgentActivityTurnOutcome.Unknown, turn.outcome)
        assertEquals(listOf(AgentActivityActionCount(AgentActivityActionKind.Other, 2)), turn.actions)
    }

    @Test
    fun rejectsGuessedWrappersAndAliases() {
        assertNull(parseSharedAgentActivityTurnPage("""[${base(1, 2, null)}]"""))
        assertNull(parseSharedAgentActivityTurnPage("""{"items":[${base(1, 2, null)}]}"""))
        assertNull(parseSharedAgentActivityTurnPage("""{"data":{"summaries":[]}}"""))
        assertNull(parseSharedAgentActivityTurnPage("not json"))
        // snake_case is not the contract: the row is dropped, not guessed.
        val snake = """{"v":1,"started_at":1,"ended_at":2,"outcome":"completed","actions":[],"tool_calls":0,"problems":[],"problem_count":0,"messages_sent":0}"""
        assertTrue(assertIsNotNull(parseSharedAgentActivityTurnPage("""{"summaries":[$snake]}""")).turns.isEmpty())
    }

    @Test
    fun repositoryRequestsSummariesAndReplyContextAtVisiblePriority() = runTest {
        val client = RecordingActivityOverviewNetworkClient(
            mapOf(
                "agents/agent-1/activity-summaries" to HomeNetworkResponse(200, """{"summaries":[$sharedVector],"next":"8000:00000000-0000-4000-8000-000000000001"}"""),
                "messages/context/reply-1" to HomeNetworkResponse(200, replyContextBody)
            )
        )
        val repository = RealAgentActivityOverviewRepository(client)

        val page = assertIs<DataResult.Content<AgentActivityTurnPage>>(
            repository.loadTurns(DataRequestPriority.Visible, MemberId("agent-1"), before = "99:00000000-0000-4000-8000-000000000009")
        ).data
        assertEquals("8000:00000000-0000-4000-8000-000000000001", page.next)
        val reply = repository.loadReply(DataRequestPriority.Visible, MessageId("reply-1"))
        assertEquals(AgentActivityTurnReply.Loaded("Fixed it: tests pass now.", 1781870490000L), reply)

        assertEquals(
            listOf(
                RecordedActivityOverviewRequest(
                    DataRequestPriority.Visible,
                    "agents/agent-1/activity-summaries",
                    listOf("limit" to "30", "before" to "99:00000000-0000-4000-8000-000000000009")
                ),
                RecordedActivityOverviewRequest(
                    DataRequestPriority.Visible,
                    "messages/context/reply-1",
                    listOf("before" to "0", "after" to "0")
                )
            ),
            client.requests
        )
    }

    @Test
    fun repositoryMapsForbiddenFailuresAndUnreadableReplies() = runTest {
        val client = RecordingActivityOverviewNetworkClient(
            mapOf(
                "agents/agent-1/activity-summaries" to HomeNetworkResponse(403, null, """{"error":"The `editAgents` capability"}"""),
                "agents/agent-2/activity-summaries" to HomeNetworkResponse(500, null),
                "agents/agent-3/activity-summaries" to HomeNetworkResponse(200, """{"rows":[]}"""),
                "messages/context/hidden" to HomeNetworkResponse(403, null),
                "messages/context/flaky" to HomeNetworkResponse(503, null),
                "messages/context/offline" to HomeNetworkResponse(0, null)
            )
        )
        val repository = RealAgentActivityOverviewRepository(client)
        assertIs<DataResult.NoPermission>(repository.loadTurns(DataRequestPriority.Visible, MemberId("agent-1")))
        val serverError = assertIs<DataResult.Error>(repository.loadTurns(DataRequestPriority.Visible, MemberId("agent-2")))
        assertTrue(serverError.error.retryable)
        val mismatch = assertIs<DataResult.Error>(repository.loadTurns(DataRequestPriority.Visible, MemberId("agent-3")))
        assertEquals("activity_summaries_contract_mismatch", mismatch.error.code)
        assertEquals(AgentActivityTurnReply.Unavailable, repository.loadReply(DataRequestPriority.Visible, MessageId("hidden")))
        // Unmapped path: the recording client answers 404, a deleted reply.
        assertEquals(AgentActivityTurnReply.Unavailable, repository.loadReply(DataRequestPriority.Visible, MessageId("gone")))
        // Passing failures are not "unavailable": the store asks again.
        assertEquals(AgentActivityTurnReply.Failed, repository.loadReply(DataRequestPriority.Visible, MessageId("flaky")))
        assertEquals(AgentActivityTurnReply.Failed, repository.loadReply(DataRequestPriority.Visible, MessageId("offline")))
    }

    private fun <T : Any> assertIsNotNull(value: T?): T {
        kotlin.test.assertNotNull(value)
        return value
    }

    private val replyContextBody = """
        {"channelId":"c1","targetMessageId":"reply-1","hasOlder":false,"hasNewer":false,
         "messages":[{"id":"reply-1","channelId":"c1","seq":12,"content":"Fixed it: tests pass now.",
           "senderType":"agent","senderId":"agent-1","senderName":"agent-1",
           "messageType":"chat","createdAt":"2026-06-19T12:01:30.000Z"}]}
    """.trimIndent()
}

internal data class RecordedActivityOverviewRequest(
    val priority: DataRequestPriority,
    val path: String,
    val query: List<Pair<String, String>>,
)

/** Answers by path; records priority, path and query of every request. */
internal class RecordingActivityOverviewNetworkClient(
    private val responses: Map<String, HomeNetworkResponse>,
) : RaftNetworkClient {
    val requests = mutableListOf<RecordedActivityOverviewRequest>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        requests += RecordedActivityOverviewRequest(priority, path, query)
        return responses[path] ?: HomeNetworkResponse(404, null)
    }
}
