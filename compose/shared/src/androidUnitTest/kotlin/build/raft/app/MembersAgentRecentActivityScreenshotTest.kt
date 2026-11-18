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
 * each, light and dark. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class MembersAgentRecentActivityScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun recentActivityLight() = capture("members.agent-profile.recent-activity", RaftAppAppearance.Light)

    @Test
    fun recentActivityDark() = capture("members.agent-profile.recent-activity.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { RecentActivityContent() }
            }
        }
}

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
private fun RecentActivityContent() {
    val state = remember { agentActivityOverviewScreenshotState() }
    val working = raftAgentStatusIndicatorState(status = AgentStatus.Working, presence = Presence.Active, external = false)
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 6, 19),
            nowEpochMillis = 1_781_870_700_000L,
        )
    // The profile's inset (web `px-5 py-4`).
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(horizontal = 20.dp, vertical = 16.dp)) {
        MembersAgentRecentActivityCard(
            finished = state.finishedTurns,
            running = state.running,
            otter = membersAgentRecentActivityOtter(working.status),
            timeContext = timeContext,
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
