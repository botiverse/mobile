package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivityOverviewState
import build.raft.app.data.AgentActivityOverviewStatus
import build.raft.app.data.AgentActivityTurn
import build.raft.app.data.AgentActivityTurnOutcome
import build.raft.app.data.AgentOperatorEvent
import build.raft.app.data.AgentOperatorEventActor
import build.raft.app.data.AgentOperatorEventKind
import build.raft.app.model.MemberId
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
 * The agent Activity overview's operator events between turns (web `ActivityOperatorEvents`,
 * botiverse/slock #13937): a run of two lines (a person's stop, then a migration between named
 * Computers), a run of three folded into "3 actions · latest: …", and Raft's own restart, fainter
 * and naming no one, before the last turn. Light and dark. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class AgentActivityOperatorEventsScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun operatorEventsLight() = capture("members.agent-activity.operator-events", RaftAppAppearance.Light)

    @Test
    fun operatorEventsDark() = capture("members.agent-activity.operator-events.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            val binding = remember { MembersAgentActivityOverviewBinding(state = MutableStateFlow(operatorEventsState()), onIntent = {}) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalInspectionMode provides true,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                        MembersAgentActivityOverview(
                            binding = binding,
                            now = MembersAgentActivityNow(label = "Online", indicator = null),
                            hasRawActivity = true,
                            hasComputer = true,
                            timeContext =
                                RaftTimeContext(
                                    localOffsetMinutes = 8 * 60,
                                    todayEpochDay = raftEpochDayForDate(2026, 6, 19),
                                    nowEpochMillis = 1_781_870_700_000L,
                                ),
                            onShowRaw = {},
                            onOpenComputer = null,
                            onOpenUrl = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// 2026-06-19 19:00 local (UTC+8).
private const val OperatorEventsBase = 1_781_866_800_000L
private const val Minute = 60_000L

private fun operatorTurn(id: String, startMinute: Long) =
    AgentActivityTurn(
        id = id,
        startedAtEpochMillis = OperatorEventsBase + startMinute * Minute,
        endedAtEpochMillis = OperatorEventsBase + startMinute * Minute + 42_000L,
        outcome = AgentActivityTurnOutcome.Completed,
        trigger = null,
        actions = emptyList(),
        toolCalls = 3,
        problems = emptyList(),
        problemCount = 0,
        messagesSent = 0,
        lastReplyMessageId = null,
    )

private fun operatorEvent(
    id: String,
    minute: Long,
    kind: AgentOperatorEventKind,
    actor: AgentOperatorEventActor = AgentOperatorEventActor.User("Artin"),
    from: String? = null,
    to: String? = null,
    beforeModel: String? = null,
    afterModel: String? = null,
) = AgentOperatorEvent(
    id = id,
    atEpochMillis = OperatorEventsBase + minute * Minute,
    kind = kind,
    actor = actor,
    fromComputer = from,
    toComputer = to,
    beforeModel = beforeModel,
    afterModel = afterModel,
)

private fun operatorEventsState(): AgentActivityOverviewState =
    AgentActivityOverviewState(
        agentId = MemberId("agent-activity-operator-events"),
        status = AgentActivityOverviewStatus.Loaded,
        // Newest first, as the server pages them.
        turns = listOf(operatorTurn("t4", 30), operatorTurn("t3", 15), operatorTurn("t2", 8), operatorTurn("t1", 0)),
        operatorEvents =
            listOf(
                operatorEvent("e6", 28, AgentOperatorEventKind.SystemRestart, actor = AgentOperatorEventActor.System),
                operatorEvent("e5", 14, AgentOperatorEventKind.Start),
                operatorEvent("e4", 12, AgentOperatorEventKind.ConfigChanged, beforeModel = "gpt-5", afterModel = "gpt-5.6"),
                operatorEvent("e3", 10, AgentOperatorEventKind.Restart, actor = AgentOperatorEventActor.User(null)),
                operatorEvent("e2", 6, AgentOperatorEventKind.MigrationStarted, from = "laptop", to = "hetzner-1"),
                operatorEvent("e1", 5, AgentOperatorEventKind.Stop),
            ),
    )
