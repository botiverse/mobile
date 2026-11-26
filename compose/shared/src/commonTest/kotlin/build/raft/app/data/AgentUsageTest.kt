package build.raft.app.data

import build.raft.app.AgentUsagePluralForm
import build.raft.app.MemberDetailAccessMode
import build.raft.app.MembersAgentDetailTab
import build.raft.app.agentUsagePluralForm
import build.raft.app.membersAgentDetailTabsForAccess
import build.raft.app.membersOrderedAgentTabs
import build.raft.app.membersVisibleAgentTabs
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import build.raft.app.toMembersAgentDetailTabOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure contract, math, wire decoding, and tab-gating tests for the Agent Profile Usage tab
 * (`botiverse/slock` `agentUsage.ts` + `AgentDetailPanel.tsx`).
 */
class AgentUsageTest {
    private fun row(
        day: String,
        agentId: String = "agent-1",
        runtime: String? = "claude",
        model: String = "claude-sonnet-4-6",
        inputNew: Long? = 1_000L,
        cacheRead: Long? = 4_000L,
        cacheWrite: Long? = 500L,
        output: Long? = 300L,
        reasoningOutput: Long? = 100L,
        turns: Long = 2L,
    ) = AgentUsageRow(
        day = day,
        agentId = agentId,
        runtime = runtime,
        model = model,
        inputNew = inputNew,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        output = output,
        reasoningOutput = reasoningOutput,
        turns = turns,
    )

    @Test
    fun requestWindowRequestsDoubleRangeCappedAtNinetyDays() {
        assertEquals(AgentUsageDateWindow(from = "2026-03-01", to = "2026-03-14"), agentUsageRequestWindow(7, "2026-03-14"))
        assertEquals(AgentUsageDateWindow(from = "2026-02-15", to = "2026-03-14"), agentUsageRequestWindow(14, "2026-03-14"))
        assertEquals(AgentUsageDateWindow(from = "2026-01-14", to = "2026-03-14"), agentUsageRequestWindow(30, "2026-03-14"))
        // Capped at AGENT_USAGE_MAX_RANGE_DAYS (90)
        assertEquals(AgentUsageDateWindow(from = "2025-12-15", to = "2026-03-14"), agentUsageRequestWindow(60, "2026-03-14"))
        assertEquals(
            listOf("2026-03-08", "2026-03-09", "2026-03-10", "2026-03-11", "2026-03-12", "2026-03-13", "2026-03-14"),
            agentUsageRangeDays(AgentUsageRange.Days7, "2026-03-14"),
        )
    }

    @Test
    fun nullVsZeroSemanticsAndReasoningInsideOutput() {
        val unreported = sumAgentUsageRows(
            listOf(row("2026-03-10", inputNew = null, cacheRead = null, cacheWrite = null, output = null, reasoningOutput = null, turns = 1L))
        )
        assertNull(unreported.inputNew)
        assertNull(totalAgentUsageTokens(unreported))
        assertNull(chartAgentUsageTokens(unreported, withCacheReads = false))
        assertNull(cacheShareOfInput(unreported))
        assertNull(tokensPerTurn(unreported))

        val zeroRow = sumAgentUsageRows(
            listOf(row("2026-03-10", inputNew = 0L, cacheRead = 0L, cacheWrite = null, output = 0L, reasoningOutput = null, turns = 2L))
        )
        assertEquals(0L, totalAgentUsageTokens(zeroRow))
        assertEquals(0L, chartAgentUsageTokens(zeroRow, withCacheReads = false))
        assertNull(cacheShareOfInput(zeroRow))
        assertEquals(0.0, tokensPerTurn(zeroRow))

        // reasoningOutput is part of output, never double-counted in total or chartTokens.
        val mixed = sumAgentUsageRows(
            listOf(
                row("2026-03-10", inputNew = 1_000L, cacheRead = 3_000L, cacheWrite = 500L, output = 400L, reasoningOutput = 150L, turns = 2L),
                row("2026-03-10", inputNew = 200L, cacheRead = null, cacheWrite = null, output = 100L, reasoningOutput = null, turns = 3L),
            )
        )
        assertEquals(1_200L, mixed.inputNew)
        assertEquals(3_000L, mixed.cacheRead)
        assertEquals(500L, mixed.cacheWrite)
        assertEquals(500L, mixed.output)
        assertEquals(150L, mixed.reasoning)
        assertEquals(5L, mixed.turns)
        assertEquals(5_200L, totalAgentUsageTokens(mixed))
        assertEquals(2_200L, chartAgentUsageTokens(mixed, withCacheReads = false))
        assertEquals(5_200L, chartAgentUsageTokens(mixed, withCacheReads = true))
        assertEquals(3_000.0 / 4_700.0, cacheShareOfInput(mixed))
        assertEquals(1_040.0, tokensPerTurn(mixed))
    }

    @Test
    fun wireDecodeDistinguishesAllFiveUnreportedReasons() {
        fun decodeReason(agentJson: String): UsageUnreportedReason {
            val payload =
                """
                {
                  "from": "2026-03-01",
                  "to": "2026-03-14",
                  "as_of": "2026-03-14T12:00:00Z",
                  "since": null,
                  "visible_agents": 1,
                  "agents": [$agentJson],
                  "rows": [],
                  "context": []
                }
                """.trimIndent()
            val decoded = assertNotNull(decodeAgentUsageResponse(payload))
            assertFalse(usageReported(decoded, "a1"))
            val summary = summarizeAgentUsage(decoded, "a1", AgentUsageView(), withCacheReads = false)
            return assertIs<AgentUsageSummary.Unsupported>(summary).reason
        }

        // runtime_reports_usage: false takes precedence over computer_reports_usage
        assertEquals(
            UsageUnreportedReason.RuntimeNoUsage,
            decodeReason("""{"agent_id":"a1","since":null,"usage_reported":false,"computer_reports_usage":false,"runtime_reports_usage":false}"""),
        )
        assertEquals(
            UsageUnreportedReason.ComputerTooOld,
            decodeReason("""{"agent_id":"a1","since":null,"usage_reported":false,"computer_reports_usage":false,"runtime_reports_usage":true}"""),
        )
        assertEquals(
            UsageUnreportedReason.NotRun,
            decodeReason("""{"agent_id":"a1","since":null,"usage_reported":false,"computer_reports_usage":true,"runtime_reports_usage":true}"""),
        )
        // Explicit JSON null -> NoComputer (hosted / external / detached)
        assertEquals(
            UsageUnreportedReason.NoComputer,
            decodeReason("""{"agent_id":"a1","since":null,"usage_reported":false,"computer_reports_usage":null}"""),
        )
        // Omitted field (older server) -> Unknown
        assertEquals(
            UsageUnreportedReason.Unknown,
            decodeReason("""{"agent_id":"a1","since":null,"usage_reported":false}"""),
        )
        // Only ComputerTooOld and Unknown offer the Open computer action.
        assertTrue(UsageUnreportedReason.ComputerTooOld.offersComputer)
        assertTrue(UsageUnreportedReason.Unknown.offersComputer)
        assertFalse(UsageUnreportedReason.NotRun.offersComputer)
        assertFalse(UsageUnreportedReason.NoComputer.offersComputer)
        assertFalse(UsageUnreportedReason.RuntimeNoUsage.offersComputer)
    }

    @Test
    fun summarizeComparesFullDaysAgainstPriorPeriodAndPickedDayAgainstPriorWeekday() {
        val response =
            AgentUsageResponse(
                from = "2026-03-01",
                to = "2026-03-14",
                asOf = "2026-03-14T15:30:00Z",
                since = "2026-03-01",
                visibleAgents = 1,
                agents = listOf(AgentUsageAgentEntry(agentId = "agent-1", since = "2026-03-01", usageReported = true)),
                rows =
                    listOf(
                        // Prior 6 full days (2026-03-02..2026-03-07): 1,000 chart tokens on 03-05
                        row(day = "2026-03-05", inputNew = 800L, cacheWrite = 0L, output = 200L, cacheRead = 500L),
                        // Current 6 full days (2026-03-08..2026-03-13): 1,500 chart tokens on 03-12
                        row(day = "2026-03-12", inputNew = 1_000L, cacheWrite = 200L, output = 300L, cacheRead = 2_000L),
                        // Partial today (2026-03-14): excluded from full-day prior comparison
                        row(day = "2026-03-14", inputNew = 5_000L, cacheWrite = 0L, output = 1_000L, cacheRead = 0L),
                    ),
                context = listOf(AgentUsageContextEntry("agent-1", contextUsed = 64_000L, contextWindow = 200_000L, at = "2026-03-14T15:00:00Z")),
            )

        val rangeSummary = assertIs<AgentUsageSummary.Ready>(
            summarizeAgentUsage(response, "agent-1", AgentUsageView(AgentUsageRange.Days7, day = null), withCacheReads = false)
        )
        assertEquals(7, rangeSummary.figures.days.size)
        assertTrue(rangeSummary.figures.days.last().partial)
        assertFalse(rangeSummary.figures.days.first().partial)
        // Full days (03-08..03-13) = 1,500 vs prior 6 full days (03-02..03-07) = 1,000 => +50%
        assertEquals(1_500L, rangeSummary.figures.compared)
        assertEquals(1_000L, rangeSummary.figures.base)
        assertEquals(0.5, relativeChange(rangeSummary.figures.compared, rangeSummary.figures.base))
        assertEquals(AgentUsageContextReading(64_000L, 200_000L), rangeSummary.context)

        // Picked past day (2026-03-12) compares against same weekday a week earlier (2026-03-05)
        val pickedSummary = assertIs<AgentUsageSummary.Ready>(
            summarizeAgentUsage(response, "agent-1", AgentUsageView(AgentUsageRange.Days7, day = "2026-03-12"), withCacheReads = false)
        )
        assertEquals("2026-03-05", pickedSummary.figures.baseDay)
        assertEquals(1_500L, pickedSummary.figures.compared)
        assertEquals(1_000L, pickedSummary.figures.base)

        // Picked partial day (Today) has no comparison
        val todaySummary = assertIs<AgentUsageSummary.Ready>(
            summarizeAgentUsage(response, "agent-1", AgentUsageView(AgentUsageRange.Days7, day = "2026-03-14"), withCacheReads = false)
        )
        assertNull(todaySummary.figures.compared)
        assertNull(todaySummary.figures.base)
    }

    @Test
    fun apiClientHitsExactServerAgentUsagePathAndQuery(): Unit = runPortableCoroutineTest<Unit> {
        var capturedServerId: ServerId? = null
        var capturedFrom: String? = null
        var capturedTo: String? = null
        var capturedAgentId: MemberId? = null
        val api =
            object : RaftAgentsApi {
                override suspend fun agentUsage(serverId: ServerId, from: String, to: String, agentId: MemberId?): HomeNetworkResponse {
                    capturedServerId = serverId
                    capturedFrom = from
                    capturedTo = to
                    capturedAgentId = agentId
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"from":"$from","to":"$to","as_of":"2026-03-14T12:00:00Z","since":"2026-03-01","visible_agents":1,"agents":[{"agent_id":"agent-1","since":"2026-03-01","usage_reported":true,"computer_reports_usage":true,"runtime_reports_usage":true}],"rows":[],"context":[]}""",
                    )
                }

                override suspend fun agents() = error("unused")
                override suspend fun agent(agentId: MemberId) = error("unused")
                override suspend fun createAgent(body: String) = error("unused")
                override suspend fun updateAgent(agentId: MemberId, body: String) = error("unused")
                override suspend fun startAgent(agentId: MemberId) = error("unused")
                override suspend fun stopAgent(agentId: MemberId) = error("unused")
                override suspend fun resetAgent(agentId: MemberId, body: String) = error("unused")
                override suspend fun deleteAgent(agentId: MemberId, body: String) = error("unused")
                override suspend fun assignMachine(agentId: MemberId, body: String) = error("unused")
                override suspend fun agentDirectMessages(agentId: MemberId) = error("unused")
                override suspend fun agentActivityLog(agentId: MemberId, limit: Int) = error("unused")
                override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?) = error("unused")
                override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String) = error("unused")
                override suspend fun agentSkills(agentId: MemberId) = error("unused")
                override suspend fun agentExternalStatus(agentId: MemberId) = error("unused")
                override suspend fun agentScopes(agentId: MemberId) = error("unused")
                override suspend fun updateAgentScopes(agentId: MemberId, body: String) = error("unused")
                override suspend fun resetAgentScopes(agentId: MemberId) = error("unused")
                override suspend fun issueBootstrapToken(agentId: MemberId, body: String) = error("unused")
            }
        val client = RaftAgentsApiClient(api)
        val result = client.agentUsage(ServerId("srv-1"), "2026-03-01", "2026-03-14", MemberId("agent-1"))
        val content = assertIs<DataResult.Content<AgentUsageResponse>>(result).data
        assertEquals(ServerId("srv-1"), capturedServerId)
        assertEquals("2026-03-01", capturedFrom)
        assertEquals("2026-03-14", capturedTo)
        assertEquals(MemberId("agent-1"), capturedAgentId)
        assertEquals(AgentUsageSummary.Empty, summarizeAgentUsage(content, "agent-1", AgentUsageView(), withCacheReads = false))
    }

    @Test
    fun formattersMatchWebCompactRules() {
        assertEquals("0", formatAgentUsageTokens(0L))
        assertEquals("420", formatAgentUsageTokens(420L))
        assertEquals("1.2K", formatAgentUsageTokens(1_240L))
        assertEquals("12K", formatAgentUsageTokens(12_000L))
        assertEquals("1M", formatAgentUsageTokens(999_960L))
        assertEquals("3.4M", formatAgentUsageTokens(3_400_000L))
        assertEquals("1B", formatAgentUsageTokens(999_995_000L))
        assertEquals("1.25B", formatAgentUsageTokens(1_250_000_000L))
        assertEquals("42.5%", formatAgentUsagePercent(0.425, 1))
        assertEquals("43%", formatAgentUsagePercent(0.425, 0))
        assertEquals("+12%", formatAgentUsageChange(0.124))
        assertEquals("-8%", formatAgentUsageChange(-0.081))
        assertEquals("0%", formatAgentUsageChange(0.0))
        // Plural buckets: English vs Russian Slavic rules
        assertEquals(AgentUsagePluralForm.One, agentUsagePluralForm(1L, isRussian = false))
        assertEquals(AgentUsagePluralForm.Other, agentUsagePluralForm(21L, isRussian = false))
        assertEquals(AgentUsagePluralForm.One, agentUsagePluralForm(21L, isRussian = true))
        assertEquals(AgentUsagePluralForm.Few, agentUsagePluralForm(22L, isRussian = true))
        assertEquals(AgentUsagePluralForm.Other, agentUsagePluralForm(12L, isRussian = true))
    }

    @Test
    fun usageTabGatingAndOrderingMatchesWebRules() {
        // Private surfaces -> Usage is unconditionally shown between Activity and Chat (slock #16937)
        val visibleTabs = membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = false)
        assertEquals(
            listOf(
                MembersAgentDetailTab.Profile,
                MembersAgentDetailTab.Activity,
                MembersAgentDetailTab.Usage,
                MembersAgentDetailTab.Chat,
                MembersAgentDetailTab.Reminders,
                MembersAgentDetailTab.Workspace,
                MembersAgentDetailTab.Apps,
            ),
            visibleTabs,
        )
        // Non-private viewer, deleted agent, App Bot, or remote profile -> hidden
        assertFalse(MembersAgentDetailTab.Usage in membersVisibleAgentTabs(canViewPrivateSurfaces = false, deleted = false))
        assertFalse(MembersAgentDetailTab.Usage in membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = true))
        assertFalse(MembersAgentDetailTab.Usage in membersVisibleAgentTabs(canViewPrivateSurfaces = true, deleted = false, managedByApp = true))
        assertEquals(
            listOf(MembersAgentDetailTab.Profile),
            membersAgentDetailTabsForAccess(MemberDetailAccessMode.RemoteProfileOnly, canViewPrivateSurfaces = true, deleted = false),
        )
        // Tab order persistence recognizes "usage"
        assertEquals(MembersAgentDetailTab.Usage, "usage".toMembersAgentDetailTabOrNull())
        val reordered = membersOrderedAgentTabs(visibleTabs, listOf("usage", "profile"))
        assertEquals(MembersAgentDetailTab.Usage, reordered.first())
    }
}
