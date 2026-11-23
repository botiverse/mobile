package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentDetailSummary
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the agent profile's Recent activity card (`members.agent-profile.recent-activity`,
 * web features/agent/AgentRecentActivity.tsx + components/agent/AgentNowCard.tsx): the production
 * [MembersAgentRecentActivityCard] with the profile's status chip as its first line, a running turn
 * (its focus line) and the three finished turns of [agentActivityOverviewScreenshotState] one line
 * each, light and dark; and the same card just past midnight, where one row is today's ("00:18")
 * and two are the day before's ("Jun 18, 23:57"): the rows' texts start at one x. Verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h440dp-xxhdpi")
class MembersAgentRecentActivityScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun recentActivityLight() = capture("members.agent-profile.recent-activity", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance, case: AgentRecentActivityCase = agentRecentActivitySameDay()) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { RecentActivityContent(case) }
            }
        }
}

/** The card's turns and the viewer's clock they are read against. */
internal class AgentRecentActivityCase(
    val finished: List<AgentActivityTurn>,
    val running: AgentActivityTurn?,
    val timeContext: RaftTimeContext,
)

/** [agentActivityOverviewScreenshotState] as it is: 2026-06-19 around 20:00 (UTC+8), every turn that day. */
internal fun agentRecentActivitySameDay(): AgentRecentActivityCase {
    val state = agentActivityOverviewScreenshotState()
    return AgentRecentActivityCase(
        finished = state.finishedTurns,
        running = state.running,
        timeContext =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 6, 19),
                nowEpochMillis = 1_781_870_700_000L,
            ),
    )
}

/**
 * The same turns moved to just past midnight, 2026-06-19 00:20 (UTC+8): the running turn began at
 * 00:19, the finished ones at 00:18 and, the day before, 23:57 and 23:41 (product lead's report,
 * Android 1.27.0: "00:18" above "11-20 23:57" and "11-20 23:41").
 */
internal fun agentRecentActivityAcrossDays(): AgentRecentActivityCase {
    val state = agentActivityOverviewScreenshotState()
    val starts = listOf(ACROSS_DAYS_MIDNIGHT + 18 * 60_000L, ACROSS_DAYS_MIDNIGHT - 3 * 60_000L, ACROSS_DAYS_MIDNIGHT - 19 * 60_000L)
    return AgentRecentActivityCase(
        finished =
            state.finishedTurns.mapIndexed { index, turn ->
                turn.copy(
                    startedAtEpochMillis = starts[index],
                    endedAtEpochMillis = starts[index] + (turn.endedAtEpochMillis - turn.startedAtEpochMillis),
                )
            },
        running =
            state.running?.copy(
                startedAtEpochMillis = ACROSS_DAYS_MIDNIGHT + 19 * 60_000L,
                endedAtEpochMillis = ACROSS_DAYS_MIDNIGHT + 20 * 60_000L,
            ),
        timeContext =
            RaftTimeContext(
                localOffsetMinutes = 8 * 60,
                todayEpochDay = raftEpochDayForDate(2026, 6, 19),
                nowEpochMillis = ACROSS_DAYS_MIDNIGHT + 20 * 60_000L,
            ),
    )
}

/** 2026-06-19 00:00 at UTC+8 (the fixture's 20:00 turn, 1781870400000, less twenty hours). */
private const val ACROSS_DAYS_MIDNIGHT = 1_781_798_400_000L

private val recentActivityAgent =
    MemberProfile(
        id = MemberId("agent-activity-overview"),
        displayName = "Ruby",
        handle = "Ruby",
        kind = MemberKind.Agent,
    )

private val recentActivitySummary =
    AgentDetailSummary(
        id = "agent-activity-overview",
        name = "Ruby",
        displayName = "Ruby",
        status = "active",
        activity = "working",
        runtime = "builtin",
    )

@Composable
private fun RecentActivityContent(case: AgentRecentActivityCase) {
    val working = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false)
    // The profile's inset (web `px-5 py-4`).
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(horizontal = 20.dp, vertical = 16.dp)) {
        MembersAgentRecentActivityCard(
            finished = case.finished,
            running = case.running,
            otter = membersAgentRecentActivityOtter(working.status),
            timeContext = case.timeContext,
            onOpenActivity = {},
        ) {
            MembersAgentProfileStatus(
                member = recentActivityAgent,
                summary = recentActivitySummary,
                agentStatusObservedState = working,
                agentStatusIndicatorState = working,
                itemTop = 0.dp,
            )
        }
    }
}
