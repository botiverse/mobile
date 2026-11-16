package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire contract for the Activity overview's operator events, verified against botiverse/slock
 * 18d389311f299d559e237c3f8875d65491a227f3 (staging):
 * - packages/shared/src/agentOperatorEvents.ts (`AgentOperatorEventsPage`, `AgentOperatorEventView`,
 *   `AGENT_OPERATOR_EVENT_STORED_KINDS`);
 * - packages/server/src/routes/agentOperatorEvents.api.test.ts "GET operator-events: newest first in
 *   the window, with model changes, names only for members, and the overview's access" (the vector
 *   below: its five events, names and details as that test asserts them, ids and times filled in).
 */
class AgentOperatorEventsParserTest {
    private val serverVector = """
        {"events":[
          {"id":"c0000000-0000-4000-8000-000000000005","at":1794304500000,"kind":"config_changed",
           "actor":{"kind":"user","name":"opev-list-owner"},"details":{"beforeModel":"gpt-5","afterModel":"gpt-5.6"}},
          {"id":"a0000000-0000-4000-8000-000000000004","at":1794304440000,"kind":"system_restart","actor":{"kind":"system"}},
          {"id":"a0000000-0000-4000-8000-000000000003","at":1794304380000,"kind":"migration_started",
           "actor":{"kind":"user","name":"opev-list-owner"},"details":{"fromComputer":"laptop","toComputer":"hetzner-1"}},
          {"id":"a0000000-0000-4000-8000-000000000002","at":1794304320000,"kind":"stop","actor":{"kind":"user","name":null}},
          {"id":"a0000000-0000-4000-8000-000000000001","at":1794304260000,"kind":"restart","actor":{"kind":"user","name":"opev-list-owner"}}
        ],"truncated":false}
    """.trimIndent()

    @Test
    fun parsesTheServerVector() {
        val page = parseSharedAgentOperatorEventsPage(serverVector)!!
        assertEquals(false, page.truncated)
        assertEquals(
            listOf(
                AgentOperatorEventKind.ConfigChanged,
                AgentOperatorEventKind.SystemRestart,
                AgentOperatorEventKind.MigrationStarted,
                AgentOperatorEventKind.Stop,
                AgentOperatorEventKind.Restart,
            ),
            page.events.map { it.kind },
        )
        val (config, system, migration, stop, restart) = page.events
        assertEquals(AgentOperatorEventActor.User("opev-list-owner"), config.actor)
        assertEquals("gpt-5" to "gpt-5.6", config.beforeModel to config.afterModel)
        assertNull(config.beforeRuntime)
        assertEquals(AgentOperatorEventActor.System, system.actor)
        assertEquals("laptop" to "hetzner-1", migration.fromComputer to migration.toComputer)
        assertEquals(AgentOperatorEventActor.User(null), stop.actor, "a person no longer on the server is not named")
        assertEquals(1794304260000L, restart.atEpochMillis)
        assertEquals("a0000000-0000-4000-8000-000000000001", restart.id)
    }

    @Test
    fun everyKindTheServerCanSendIsKnown() {
        // AGENT_OPERATOR_EVENT_STORED_KINDS + "config_changed".
        val kinds = listOf(
            "start", "stop", "restart", "reset_session", "reset_full", "turn_stopped",
            "migration_started", "migration_cancelled", "system_restart", "config_changed",
        )
        val body = kinds.mapIndexed { index, kind ->
            """{"id":"e$index","at":$index,"kind":"$kind","actor":{"kind":"user","name":"Artin"}}"""
        }.joinToString(prefix = """{"events":[""", postfix = """],"truncated":true}""")
        val logs = mutableListOf<String>()
        val page = parseSharedAgentOperatorEventsPage(body) { logs += it }!!
        assertEquals(kinds, page.events.map { it.kind.wireValue })
        assertEquals(true, page.truncated)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun anUnknownKindOrActorIsDroppedWithATraceAndABrokenRowIsSkipped() {
        val body = """
            {"events":[
              {"id":"e1","at":1,"kind":"start","actor":{"kind":"user","name":"Artin"}},
              {"id":"e2","at":2,"kind":"hibernate","actor":{"kind":"user","name":"Artin"}},
              {"id":"e3","at":3,"kind":"stop","actor":{"kind":"robot"}},
              {"id":"e4","kind":"stop","actor":{"kind":"system"}}
            ],"truncated":false}
        """.trimIndent()
        val logs = mutableListOf<String>()
        val page = parseSharedAgentOperatorEventsPage(body) { logs += it }!!
        assertEquals(listOf("e1"), page.events.map { it.id })
        assertTrue(logs.any { it.startsWith("projection_value_unknown") && "field=kind" in it && "value=hibernate" in it }, "$logs")
        assertTrue(logs.any { it.startsWith("projection_value_unknown") && "value=robot" in it }, "$logs")
        assertTrue(logs.any { it.startsWith("projection_value_rejected") && "index=3" in it }, "$logs")
    }

    @Test
    fun anythingButTheEnvelopeIsNotAPage() {
        assertNull(parseSharedAgentOperatorEventsPage("""[{"id":"e1"}]"""))
        assertNull(parseSharedAgentOperatorEventsPage("""{"items":[]}"""))
        assertNull(parseSharedAgentOperatorEventsPage("<html>"))
        assertEquals(AgentOperatorEventsPage(emptyList(), truncated = false), parseSharedAgentOperatorEventsPage("""{"events":[]}"""))
    }

    private fun turn(id: String, startedAt: Long) = AgentActivityTurn(
        id = id,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = startedAt + 50,
        outcome = AgentActivityTurnOutcome.Completed,
        trigger = null,
        actions = emptyList(),
        toolCalls = 0,
        problems = emptyList(),
        problemCount = 0,
        messagesSent = 0,
        lastReplyMessageId = null,
    )

    private fun event(id: String, at: Long) =
        AgentOperatorEvent(id = id, atEpochMillis = at, kind = AgentOperatorEventKind.Restart, actor = AgentOperatorEventActor.System)

    /** Web `interleaveOperatorEvents` (operatorEventsTimeline.ts) and the overview's running split. */
    @Test
    fun eventsSitBeforeTheFirstTurnThatStartedAfterThem() {
        val turns = listOf(turn("t1", 100), turn("t2", 200), turn("t3", 300))
        // Any order in; time order out.
        val events = listOf(event("late", 450), event("b", 250), event("a", 50), event("c", 260), event("run", 500))
        val timeline = agentActivityOverviewTimeline(turns, events, runningStartEpochMillis = 480)
        assertEquals(
            listOf(
                AgentActivityOverviewSegment.Events(listOf(event("a", 50))),
                AgentActivityOverviewSegment.Turns(listOf(turn("t1", 100), turn("t2", 200))),
                AgentActivityOverviewSegment.Events(listOf(event("b", 250), event("c", 260))),
                AgentActivityOverviewSegment.Turns(listOf(turn("t3", 300))),
            ),
            timeline.segments,
        )
        assertEquals(listOf("late"), timeline.eventsBeforeRunning.map { it.id })
        assertEquals(listOf("run"), timeline.eventsDuringRunning.map { it.id })

        val idle = agentActivityOverviewTimeline(turns, events, runningStartEpochMillis = null)
        assertEquals(listOf("late", "run"), idle.eventsBeforeRunning.map { it.id })
        assertEquals(emptyList<AgentOperatorEvent>(), idle.eventsDuringRunning)
        assertEquals(listOf(AgentActivityOverviewSegment.Turns(turns)), agentActivityOverviewTimeline(turns, emptyList(), null).segments)
    }
}
