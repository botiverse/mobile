package build.raft.app.data

import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// #proj-mobile:#148: `GET /agents/:id/activity-log` as the server sends it (slock
// agentActivityLogService.listRecentAgentTrajectory: `{ timestamp, entry }` rows, oldest first).
// Daemons store each finished turn's `turn_summary` row in the same log, its `summary` an object;
// the parser read `summary` as a string, failed the whole response, and the Raw log opened empty.
class AgentActivityLogParserTest {
    private val agentId = MemberId("agent-1")

    private val turnSummaryRow =
        """{"timestamp":1794680678000,"entry":{"kind":"turn_summary","summary":{"v":1,"startedAt":1794680600000,""" +
            """"endedAt":1794680678000,"outcome":"done","trigger":{"kind":"message","channelId":"ch-1","messageId":"m-1"},""" +
            """"actions":[{"kind":"send","count":1}],"toolCalls":3,"problems":[],"problemCount":0,"messagesSent":1,""" +
            """"lastReplyMessageId":"m-2","lastText":"Done.","tokens":{"input":1200,"output":300}}}}"""

    private val body =
        """[
          {"timestamp":1794680600000,"entry":{"kind":"status","activity":"working","activityKind":"working","detail":"Message received","detailKind":"message_received","trigger":{"kind":"message","channelId":"ch-1","messageId":"m-1"}}},
          {"timestamp":1794680601000,"entry":{"kind":"thinking","text":"Reading the task"}},
          {"timestamp":1794680602000,"entry":{"kind":"tool_start","toolName":"Bash","toolInput":"git log -1","callId":"toolu_1"}},
          {"timestamp":1794680605000,"entry":{"kind":"status","activity":"working","detail":"Tool finished","detailKind":"tool_end","tool":{"callId":"toolu_1","ok":true,"durationMs":2500,"exitCode":0}}},
          {"timestamp":1794680670000,"entry":{"kind":"slock_action","title":"Sent message","text":"Done."}},
          $turnSummaryRow,
          {"timestamp":1794680678000,"entry":{"kind":"status","activity":"online","activityKind":"online","detail":"","detailKind":"idle"}}
        ]"""

    @Test
    fun aLogWithTurnSummaryRowsKeepsEveryOtherRow() {
        val snapshot = assertNotNull(parseSharedAgentActivity(body, agentId))
        assertEquals(
            listOf("status", "thinking", "tool_start", "status", "slock_action", "status"),
            snapshot.activity.map { it.type },
        )
        assertEquals(0, snapshot.droppedRowCount)
        assertEquals("Reading the task", snapshot.activity[1].body)
    }

    @Test
    fun oneUnreadableRowIsLeftOutAndCountedNotTheWholeLog() {
        val withBadRow =
            """[{"timestamp":"not-a-time","entry":{"kind":"text","text":"x"}},""" +
                """{"timestamp":1794680601000,"entry":{"kind":"text","text":"kept"}}]"""
        val snapshot = assertNotNull(parseSharedAgentActivity(withBadRow, agentId))
        assertEquals(listOf("kept"), snapshot.activity.map { it.body })
        assertEquals(1, snapshot.droppedRowCount)
    }

    @Test
    fun aBodyThatIsNotAnArrayIsNotALog() {
        assertNull(parseSharedAgentActivity("""{"error":"Agent not found"}""", agentId))
    }
}
