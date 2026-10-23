package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the agent Activity tab Overview while the server has paused automatic wakes
 * after repeated stops at the Computer's per-agent memory limit
 * (`members.agent-activity.wake-pause`): the production [MembersAgentActivityOverview] over the
 * turn that was stopped, with the live status the server sends (web AgentStatusBadge,
 * botiverse/slock #10860). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h720dp-xxhdpi")
class AgentActivityWakePauseScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun wakePauseLight() = capture("members.agent-activity.wake-pause", RaftAppAppearance.Light)

    @Test
    fun wakePauseDark() = capture("members.agent-activity.wake-pause.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { AgentActivityWakePauseVisualContent() }
            }
        }
    }
}

/** The server's pause status (agentOrchestrator `broadcastActivity`, botiverse/slock 24873fd5be37). */
private const val WakePauseDetail =
    "Automatic wake paused: stopped 3 times in a row at this Computer's per-agent memory limit"

@Composable
private fun AgentActivityWakePauseVisualContent() {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 6, 19),
            nowEpochMillis = 1_781_870_700_000L,
        )
    val binding =
        remember {
            val overview = agentActivityOverviewScreenshotState()
            MembersAgentActivityOverviewBinding(
                // Only the turn that was stopped: the "now" card stays on screen.
                state = MutableStateFlow(overview.copy(turns = overview.turns.filter { it.problemCount > 0 }.take(1), running = null, next = null)),
                onIntent = {},
            )
        }
    val indicator =
        raftAgentStatusIndicatorState(
            status = AgentStatus.Offline,
            presence = Presence.Offline,
            statusLabel = WakePauseDetail,
            external = false,
        ).copy(detailKind = "wake_crash_loop_blocked")
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        MembersAgentActivityOverview(
            binding = binding,
            now = MembersAgentActivityNow(label = WakePauseDetail, indicator = indicator),
            hasRawActivity = true,
            hasComputer = true,
            timeContext = timeContext,
            onShowRaw = {},
            onOpenComputer = null,
            onOpenUrl = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
