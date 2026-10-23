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
 * 24873fd5be37f58097aa8aa735893b7c9bd658e9 (staging; first verified at c1105a1766e9):
 * - packages/shared/src/activityTurnSummary.ts (`ActivityTurnSummary`), vector from
 *   packages/shared/src/activityTurnSummary.test.ts "folds a turn" (finish("completed", 8_000));
 * - packages/server/src/routes/agents.api.test.ts "activity-summaries hides where a turn came from"
 *   (`{ hidden }` triggers, `next` = "<endedAt>:<uuid>");
 * - packages/web/tests/agentActivityOverview.behavior.test.tsx (stored row `id`, reply context);
 * - packages/server/src/routes/agents.api.test.ts "activity-summaries lists the app events ..."
 *   (`appEvents` rows, `trigger.appEvent`, `current` with `running: true` and no outcome),
 *   "... names a trigger's channel and sender from the server's rows" (`display`, `{ unresolved: true }`)
 *   and "... folds the running turn" (`current.sentMessageIds`); packages/shared/src/activityTurnSummary.ts
 *   (`ActivityTurnAppEventView`, `ActivityTurnTriggerDisplay`, `RunningActivityTurnSummary`).
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
        // A non-zero exit (failed checks included) is process information and a held send the
        // normal flow: only the failed tool call went wrong (web turnProblemCount).
        assertEquals(1, turn.problemStepCount)
        assertEquals(listOf(false, true, false), turn.problems.map { it.countsAsProblem })
        assertEquals(listOf(false, false, true), turn.problems.map { it.isRoutineHold })
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

    /** agents.api.test.ts "activity-summaries lists the app events", the response's own shapes. */
    @Test
    fun parsesAppEventsTheirTriggerAndTheRunningTurn() {
        val t = 1781870400000L
        val body = """
            {"summaries":[
              {"v":1,"id":"00000000-0000-4000-8000-0000000000b2","startedAt":${t + 100_000},"endedAt":${t + 200_000},
               "outcome":"completed","trigger":{"target":"agent-event:deadbeef"},"actions":[],"toolCalls":0,
               "problems":[],"problemCount":0,"messagesSent":0,
               "appEvents":[{"id":"33333333-0000-4000-8000-000000000003","appName":"system.assistant","clientKey":"system.assistant",
                             "logoUrl":null,"summary":"Reminder fired","deliveredAt":${t + 76_000},"kind":"event"}],
               "appEventCount":1},
              {"v":1,"id":"00000000-0000-4000-8000-0000000000b1","startedAt":$t,"endedAt":${t + 50_000},
               "outcome":"completed","trigger":{"target":"agent-event:11111111","appEvent":{"appName":"Stamp","summary":"CI failed on #10827"}},
               "actions":[],"toolCalls":0,"problems":[],"problemCount":0,"messagesSent":0,
               "appEvents":[{"id":"11111111-0000-4000-8000-000000000001","appName":"Stamp","clientKey":"stamp-overview","logoUrl":null,
                             "summary":"CI failed on #10827","deliveredAt":${t + 10_000},"kind":"event"},
                            {"id":"22222222-0000-4000-8000-000000000002","appName":"Stamp","clientKey":"stamp-overview",
                             "logoUrl":"https://cdn.example/stamp.png","summary":"PR merged","deliveredAt":${t + 74_000},"kind":"event"}],
               "appEventCount":2}],
             "current":{"v":1,"running":true,"startedAt":${t + 300_000},"endedAt":${t + 400_000},
               "trigger":{"target":"agent-event:abcdef01"},"actions":[{"kind":"run_checks","count":1}],"toolCalls":1,
               "problems":[{"kind":"run_checks","reason":"exit_nonzero","exitCode":1,"at":${t + 310_000}}],"problemCount":1,
               "messagesSent":1,"sentMessageIds":["m-sent-1"],"sentMessageCount":1}}
        """.trimIndent()
        val page = assertIsNotNull(parseSharedAgentActivityTurnPage(body))
        assertEquals(listOf(listOf("33333333"), listOf("11111111", "22222222")), page.turns.map { turn -> turn.appEvents.map { it.id.take(8) } })
        assertEquals(listOf(1, 2), page.turns.map { it.appEventCount })
        val stamp = page.turns[1].appEvents
        assertEquals(
            AgentActivityAppEvent("11111111-0000-4000-8000-000000000001", "Stamp", "stamp-overview", null, "CI failed on #10827", t + 10_000),
            stamp[0]
        )
        assertEquals("https://cdn.example/stamp.png", stamp[1].logoUrl)
        // The trigger: named when the server resolved it, the generic sentence otherwise.
        assertEquals(AgentActivityTriggerDescription.AppEventFrom("Stamp", "CI failed on #10827"), page.turns[1].trigger.describe())
        assertEquals(AgentActivityTriggerDescription.AppEvent, page.turns[0].trigger.describe())

        val current = assertIsNotNull(page.current)
        assertTrue(current.running)
        assertEquals(AgentActivityTurnOutcome.Unknown, current.outcome)
        assertEquals("running:${t + 300_000}", current.key)
        assertEquals(listOf(MessageId("m-sent-1")), current.sentMessageIds)
        assertEquals(1, current.sentMessageCount)
        assertEquals(0, current.problemStepCount)
    }

    /** agents.api.test.ts "names a trigger's channel and sender from the server's rows". */
    @Test
    fun theServersDisplayNamesTheTriggerAndUnresolvedOnesSayNothing() {
        fun row(at: Long, trigger: String) =
            """{"v":1,"outcome":"completed","actions":[],"toolCalls":1,"problems":[],"problemCount":0,"messagesSent":0,""" +
                """"startedAt":$at,"endedAt":${at + 1_000},"trigger":$trigger}"""
        val body = """{"summaries":[""" + listOf(
            row(50, """{"target":"#ceo-private","channelId":"c-general","sender":"ceo","messageId":"m1","display":{"kind":"channel","channelName":"display-general","senderName":"activity-display-alice"}}"""),
            row(40, """{"unresolved":true}"""),
            row(30, """{"target":"#ceo-private:9b526607","channelId":"c-general","sender":"ceo","display":{"kind":"channel","channelName":"display-general"}}"""),
            row(20, """{"target":"dm:@ceo","channelId":"d1","sender":"ceo","display":{"kind":"dm","peerName":"activity-display-owner"}}"""),
            row(10, """{"target":"#x:1","channelId":"c2","sender":"bob","messageId":"m2","display":{"kind":"thread","channelName":"proj-runtime","senderName":"tenny"}}"""),
            row(0, """{"target":"dm:@ceo","channelId":"d1","display":{"kind":"dm"}}"""),
        ).joinToString(",") + "]}"
        val page = assertIsNotNull(parseSharedAgentActivityTurnPage(body))
        assertEquals(
            listOf(
                AgentActivityTriggerDescription.Message("@activity-display-alice", "#display-general"),
                AgentActivityTriggerDescription.Unresolved,
                AgentActivityTriggerDescription.Target("#display-general"),
                AgentActivityTriggerDescription.DmFrom("@activity-display-owner"),
                AgentActivityTriggerDescription.Thread("@tenny", "#proj-runtime"),
                AgentActivityTriggerDescription.Dm,
            ),
            page.turns.map { it.trigger.describe() }
        )
        // Never the producer's text for an unresolved trigger, and a link only with both ids.
        assertEquals(AgentActivityTurnTrigger.Unresolved, page.turns[1].trigger)
        assertEquals("c-general" to MessageId("m1"), page.turns[0].trigger.openableMessage())
        assertNull(page.turns[1].trigger.openableMessage())
        assertNull(page.turns[2].trigger.openableMessage())
    }

    /** activityTurnSummaryView.ts attachTurnSentMessages: visible ids, their count, and a hidden count. */
    @Test
    fun parsesSentMessagesAndARowWithoutOutcomeIsNoFinishedTurn() {
        val sent = base(1, 2, null).removeSuffix("}") + ""","sentMessageIds":["s1","s2"],"sentMessageCount":3,"hiddenSentCount":2}"""
        val noOutcome = base(3, 4, null).replace("\"outcome\":\"completed\",", "")
        val notRunning = base(5, 6, null).replace("\"outcome\":\"completed\",", "")
        val page = assertIsNotNull(parseSharedAgentActivityTurnPage("""{"summaries":[$sent,$noOutcome],"current":$notRunning}"""))
        val turn = page.turns.single()
        assertEquals(listOf(MessageId("s1"), MessageId("s2")), turn.sentMessageIds)
        assertEquals(3, turn.sentMessageCount)
        assertEquals(2, turn.hiddenSentCount)
        // `current` must say it is running.
        assertNull(page.current)
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
        assertEquals(AgentActivityTurnReply.Loaded("Fixed it: tests pass now.", 1781870490000L, channelId = "c1"), reply)

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
