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
 * JVM screenshots of the agent Activity tab Overview (`members.agent-activity.overview`): the
 * production [MembersAgentActivityOverview] over a fixed [agentActivityOverviewScreenshotState]
 * (web packages/web/src/components/agent/AgentActivityOverview.tsx). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp-xxhdpi")
class AgentActivityOverviewScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun overviewLight() = capture("members.agent-activity.overview", RaftAppAppearance.Light)

    @Test
    fun overviewDark() = capture("members.agent-activity.overview.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { AgentActivityOverviewVisualContent() }
            }
        }
    }
}

@Composable
private fun AgentActivityOverviewVisualContent() {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 6, 19),
            nowEpochMillis = 1_781_870_700_000L,
        )
    val binding =
        remember {
            MembersAgentActivityOverviewBinding(state = MutableStateFlow(agentActivityOverviewScreenshotState()), onIntent = {})
        }
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        MembersAgentActivityOverview(
            binding = binding,
            now = MembersAgentActivityNow(label = "Working", indicator = null),
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
