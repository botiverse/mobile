package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentActivitySubagentLineage
import build.raft.app.model.MemberId
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// Cases ported from botiverse/slock 853c6998bcef45df5c25e63b1035e2388608721d
// packages/web/tests/subagentGroups.test.ts (#proj-runtime:#41, #13383): the Sub-agents view is
// folded only from explicit lineage markers, here through the activity-log parser it reads.
class AgentActivitySubagentsTest {
    private val base = 1_793_728_800_000L

    private fun at(seconds: Int): Long = base + seconds * 1000L

    private fun log(vararg rows: Pair<Int, String>): List<AgentActivityEntry> =
        parseSharedAgentActivity(
            rows.joinToString(",", "[", "]") { (seconds, entry) -> """{"timestamp":${at(seconds)},"entry":$entry}""" },
            MemberId("agent-1"),
        )!!.activity

    @Test
    fun claudeSpawnRowTitlesItInnerRowsAreItsStepsAndTheEndMarkerSaysHowItEnded() {
        val sub = """"parentToolUseId":"toolu_a","subagentType":"Explore""""
        val groups = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"Agent","toolInput":"Find stale call sites","callId":"toolu_a"}""",
                1 to """{"kind":"status","activity":"working","detail":"Subagent started (Explore)","detailKind":"subagent_activity","subagent":{$sub,"taskId":"task-1","phase":"started"}}""",
                2 to """{"kind":"tool_start","toolName":"Grep","toolInput":"isSecondaryThreadId","callId":"toolu_g","subagent":{$sub}}""",
                3 to """{"kind":"text","text":"found 3","subagent":{$sub}}""",
                4 to """{"kind":"text","text":"the agent's own text"}""",
                9 to """{"kind":"status","activity":"working","detail":"Subagent completed (Explore)","detailKind":"subagent_activity","subagent":{$sub,"taskId":"task-1","phase":"notification","outcome":"done"}}""",
            )
        )
        val group = groups.single()
        assertEquals("Find stale call sites", group.title)
        assertEquals("Explore", group.subagentType)
        assertEquals("task-1", group.taskId)
        assertEquals(AgentActivitySubagentState.Done, group.state)
        assertEquals(1, group.steps)
        assertEquals("Grep", group.lastToolName)
        assertEquals(at(0), group.startedAtMillis)
        assertEquals(at(9), group.endedAtMillis)
        assertEquals(listOf("tool_start", "text"), group.items.map { it.type }, "its own rows, not the lifecycle or the agent's")
        assertNull(group.items[0].subagent, "the view is already this sub-agent's")
        assertEquals(false, group.items[0].isSubagent)
    }

    @Test
    fun codexSpawnDropsTheOperationRunningSortsFirstAndAFollowUpTurnReopensIt() {
        val a = """"parentToolUseId":"call_a","taskId":"thread-a""""
        val b = """"parentToolUseId":"call_b","taskId":"thread-b""""
        fun status(phase: String, lineage: String, outcome: String? = null) =
            """{"kind":"status","activity":"working","detail":"Subagent","detailKind":"subagent_activity","subagent":{$lineage,"phase":"$phase"${outcome?.let { ""","outcome":"$it"""" } ?: ""}}}"""
        val groups = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"collab_tool_call","toolInput":"spawnAgent: Run the daemon tests","callId":"call_a"}""",
                1 to status("started", a),
                2 to status("notification", a, "done"),
                3 to """{"kind":"tool_start","toolName":"collab_tool_call","toolInput":"spawnAgent: Check the schema","callId":"call_b"}""",
                4 to status("started", b),
                5 to status("notification", b, "failed"),
                6 to status("started", a),
            )
        )
        assertEquals(
            listOf("Run the daemon tests" to AgentActivitySubagentState.Running, "Check the schema" to AgentActivitySubagentState.Failed),
            groups.map { it.title to it.state },
        )
        assertNull(groups[0].endedAtMillis)
    }

    @Test
    fun olderDaemonsAndScrolledOutStartsReadAsEndedAtTheirLastRow() {
        val old = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"Agent","toolInput":"","callId":"toolu_old"}""",
                1 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{"parentToolUseId":"toolu_old","subagentType":"Plan","phase":"started"}}""",
                2 to """{"kind":"status","activity":"working","detail":"Subagent completed (Plan)","detailKind":"subagent_activity","subagent":{"parentToolUseId":"toolu_old","subagentType":"Plan","phase":"notification"}}""",
            )
        ).single()
        assertNull(old.title)
        assertEquals(AgentActivitySubagentState.Ended, old.state)

        val scrolledOut = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"Grep","toolInput":"x","subagent":{"parentToolUseId":"toolu_old"}}""",
                7 to """{"kind":"text","text":"done looking","subagent":{"parentToolUseId":"toolu_old"}}""",
            )
        ).single()
        assertEquals(AgentActivitySubagentState.Ended, scrolledOut.state)
        assertEquals(at(7), scrolledOut.endedAtMillis, "a fixed end, not a duration that keeps growing")
    }

    @Test
    fun rowsWithoutALineageMarkerMakeNoSubagentAndASpawnWithoutAPromptHasNoTitle() {
        assertEquals(
            emptyList(),
            groupAgentActivitySubagents(
                log(
                    0 to """{"kind":"tool_start","toolName":"Agent","toolInput":"x","callId":"toolu_x"}""",
                    1 to """{"kind":"text","text":"Subagent completed"}""",
                )
            ),
        )
        val untitled = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"collab_tool_call","toolInput":"spawnAgent","callId":"call_x"}""",
                1 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{"parentToolUseId":"call_x","taskId":"t","phase":"started"}}""",
            )
        ).single()
        assertNull(untitled.title)
    }

    @Test
    fun aHostedAgentsSystemRowsAreTheLifecycleItsToolEndsAreNot() {
        val sub = """"parentToolUseId":"send_1","taskId":"send_1""""
        val group = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"system","title":"Subagent started","text":"worker","subagent":{$sub,"phase":"started"}}""",
                1 to """{"kind":"tool_start","toolName":"Bash","toolInput":"ls","subagent":{$sub,"phase":"active"}}""",
                2 to """{"kind":"status","activity":"working","detail":"Tool finished","detailKind":"tool_end","subagent":{$sub,"phase":"active"}}""",
                5 to """{"kind":"system","title":"Subagent finished","text":"ceiling","subagent":{$sub,"phase":"ended","outcome":"ceiling","reportChars":10}}""",
            )
        ).single()
        assertEquals(AgentActivitySubagentState.Ceiling, group.state)
        assertEquals(at(5), group.endedAtMillis)
        assertEquals(1, group.steps)
    }

    @Test
    fun oneCallStartingSeveralGivesEachItsOwnRunAndClaudeInnerRowsJoinTheirCallsOneTask() {
        val a = """"parentToolUseId":"call_swarm","taskId":"sub-a""""
        val b = """"parentToolUseId":"call_swarm","taskId":"sub-b""""
        val swarm = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"Agent","toolInput":"Review both halves","callId":"call_swarm"}""",
                1 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{$a,"phase":"started"}}""",
                2 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{$b,"phase":"started"}}""",
                3 to """{"kind":"text","text":"left half ok","subagent":{$a}}""",
                4 to """{"kind":"status","activity":"working","detail":"Subagent completed","detailKind":"subagent_activity","subagent":{$a,"phase":"notification","outcome":"done"}}""",
            )
        )
        assertEquals(
            listOf(
                listOf("sub-b", "Review both halves", AgentActivitySubagentState.Running, 0),
                listOf("sub-a", "Review both halves", AgentActivitySubagentState.Done, 1),
            ),
            swarm.map { listOf(it.taskId, it.title, it.state, it.items.size) },
        )

        val claudeLog =
            log(
                0 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{"parentToolUseId":"toolu_1","taskId":"task-1","phase":"started"}}""",
                1 to """{"kind":"tool_start","toolName":"Grep","toolInput":"x","subagent":{"parentToolUseId":"toolu_1"}}""",
            )
        val claude = groupAgentActivitySubagents(claudeLog)
        assertEquals(listOf(Triple("task-1", "toolu_1", 1)), claude.map { Triple(it.taskId, it.parentToolUseId, it.steps) })
        // The raw log's badge on the inner row opens that same run.
        val resolve = agentActivitySubagentKeyResolver(claudeLog)
        assertEquals(claude.single().key, resolve(claudeLog[1].subagent!!))
    }

    @Test
    fun aKimiResumeUnderANewCallIsItsOwnRunWithItsOwnTitle() {
        val first = """"parentToolUseId":"call_1","taskId":"sub-1""""
        val resumed = """"parentToolUseId":"call_2","taskId":"sub-1""""
        val groups = groupAgentActivitySubagents(
            log(
                0 to """{"kind":"tool_start","toolName":"Agent","toolInput":"Write the parser","callId":"call_1"}""",
                1 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{$first,"phase":"started"}}""",
                2 to """{"kind":"status","activity":"working","detail":"Subagent completed","detailKind":"subagent_activity","subagent":{$first,"phase":"notification","outcome":"done"}}""",
                30 to """{"kind":"tool_start","toolName":"Agent","toolInput":"Now add the tests","callId":"call_2"}""",
                31 to """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{$resumed,"phase":"started"}}""",
                32 to """{"kind":"text","text":"adding tests","subagent":{$resumed}}""",
            )
        )
        assertEquals(
            listOf(
                listOf("call_2", "Now add the tests", AgentActivitySubagentState.Running, at(30)),
                listOf("call_1", "Write the parser", AgentActivitySubagentState.Done, at(0)),
            ),
            groups.map { listOf(it.parentToolUseId, it.title, it.state, it.startedAtMillis) },
        )
    }

    @Test
    fun aSubagentLifecycleSystemRowReadsAsItsStateNotAsAnError() {
        fun presence(phase: String?, outcome: String? = null) =
            agentActivitySubagentRowPresence(AgentActivitySubagentLineage(taskId = "t", phase = phase, outcome = outcome))
        assertEquals("busy", presence("started"))
        assertEquals("busy", presence("progress"))
        assertEquals("online", presence("notification", "done"))
        assertEquals("online", presence("notification", "stopped"))
        assertEquals("error", presence("notification", "failed"))
        assertNull(presence(null))
        assertNull(agentActivitySubagentRowPresence(null))
    }

    @Test
    fun theLiveActivityFrameCarriesTheSameLineageCallIdAndDetailKind() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {
                  "agentId": "agent-1",
                  "activity": "working",
                  "detail": "Subagent started",
                  "entries": [
                    { "kind": "tool_start", "toolName": "Agent", "toolInput": "Find it", "callId": "toolu_a" },
                    {
                      "kind": "status", "activity": "working", "detail": "Subagent started", "detailKind": "subagent_activity",
                      "subagent": { "parentToolUseId": "toolu_a", "subagentType": "Explore", "taskId": "task-1", "phase": "started" }
                    },
                    { "kind": "system", "title": "Subagent finished", "text": "ok", "subagent": { "taskId": "task-1", "phase": "ended", "outcome": "done" } }
                  ]
                }
                """.trimIndent()
            )
        val rows = assertIs<MemberPresenceUpdated>(event).activityEntries
        assertEquals("toolu_a", rows[0].callId)
        assertEquals("Starting sub-agent", rows[0].title)
        assertEquals("subagent_activity", rows[1].statusDetailKind)
        assertEquals(
            AgentActivitySubagentLineage(parentToolUseId = "toolu_a", subagentType = "Explore", taskId = "task-1", phase = "started"),
            rows[1].subagent,
        )
        assertEquals(true, rows[2].isSubagent)
        assertEquals("done", rows[2].subagent?.outcome)
    }

    @Test
    fun toolRowsReadAsTheWebNamesThemOnEveryRuntime() {
        // slock #13446 / #13461: OAR runtimes' calls arrive as bash / read_file / edit_file / grep.
        val cases =
            mapOf(
                "bash" to "Running command",
                "Bash" to "Running command",
                "read_file" to "Reading file",
                "edit_file" to "Editing file",
                "grep" to "Searching code",
                "Agent" to "Starting sub-agent",
                "collab_tool_call" to "Collaborating",
                "mcp__chat__check_messages" to "Checking messages",
                "mcp__fs__read_file" to "read_file",
                "my_tool" to "my_tool",
                "spawn_subagent" to "spawn_subagent",
            )
        cases.forEach { (tool, label) -> assertEquals(label, agentToolLogLabel(tool), tool) }
        assertEquals(true, agentToolStartHiddenInActivityLog("mcp__chat__send_message"))
        assertEquals(false, agentToolStartHiddenInActivityLog("check_messages"))
    }
}
