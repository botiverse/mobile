package build.raft.app

import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.parseSharedAgentActivity
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate

/** A fixed clock for the Sub-agents view's goldens and UI tests (2026-06-19 12:05 at UTC+8). */
internal const val SubagentsFixtureNowMillis = 1_781_841_900_000L

internal val SubagentsFixtureTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 6, 19),
        nowEpochMillis = SubagentsFixtureNowMillis,
    )

/**
 * An agent's activity with three sub-agents, in the daemon's wire shape (slock
 * `SubagentLineage`): a Claude Explore run still working, a Codex run that finished and one
 * that failed.
 */
internal fun subagentsFixtureLog(): List<AgentActivityEntry> {
    val t0 = SubagentsFixtureNowMillis - 20 * 60_000L
    fun row(minutes: Int, seconds: Int, entry: String) = """{"timestamp":${t0 + minutes * 60_000L + seconds * 1000L},"entry":$entry}"""
    val explore = """"parentToolUseId":"toolu_a","subagentType":"Explore""""
    val tests = """"parentToolUseId":"call_b","taskId":"thread-b""""
    val schema = """"parentToolUseId":"call_c","taskId":"thread-c""""
    val rows = listOf(
        row(0, 0, """{"kind":"tool_start","toolName":"collab_tool_call","toolInput":"spawnAgent: Run the daemon tests","callId":"call_b"}"""),
        row(0, 1, """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{$tests,"phase":"started"}}"""),
        row(0, 20, """{"kind":"tool_start","toolName":"bash","toolInput":"pnpm --filter daemon test","subagent":{$tests}}"""),
        row(3, 5, """{"kind":"status","activity":"working","detail":"Subagent completed","detailKind":"subagent_activity","subagent":{$tests,"phase":"notification","outcome":"done"}}"""),
        row(4, 0, """{"kind":"tool_start","toolName":"collab_tool_call","toolInput":"spawnAgent: Check the schema migration","callId":"call_c"}"""),
        row(4, 1, """{"kind":"status","activity":"working","detail":"Subagent started","detailKind":"subagent_activity","subagent":{$schema,"phase":"started"}}"""),
        row(5, 30, """{"kind":"status","activity":"working","detail":"Subagent failed","detailKind":"subagent_activity","subagent":{$schema,"phase":"notification","outcome":"failed"}}"""),
        row(15, 0, """{"kind":"tool_start","toolName":"Agent","toolInput":"Find the stale call sites of isSecondaryThreadId","callId":"toolu_a"}"""),
        row(15, 1, """{"kind":"status","activity":"working","detail":"Subagent started (Explore)","detailKind":"subagent_activity","subagent":{$explore,"taskId":"task-1","phase":"started"}}"""),
        row(15, 10, """{"kind":"tool_start","toolName":"Grep","toolInput":"isSecondaryThreadId","callId":"toolu_g","subagent":{$explore}}"""),
        row(16, 0, """{"kind":"text","text":"Found 3 call sites in packages/web.","subagent":{$explore}}"""),
        row(17, 30, """{"kind":"tool_start","toolName":"Read","toolInput":"packages/web/src/store/threads.ts","callId":"toolu_r","subagent":{$explore}}"""),
    )
    return parseSharedAgentActivity(rows.joinToString(",", "[", "]"), MemberId("agent-subagents"))!!.activity
}
