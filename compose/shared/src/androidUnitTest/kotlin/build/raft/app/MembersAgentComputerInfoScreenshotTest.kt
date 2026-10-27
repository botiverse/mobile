package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import build.raft.app.data.AgentCreatorSummary
import build.raft.app.data.AgentDetailSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
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
 * JVM screenshot of the agent profile's Computer / Created / Creator rows and the Runtime
 * Config rows below them (web AgentDetailPanel `AgentProfileInfo`), light and dark: the
 * value type per row against the web InfoRow spans (DescriptionDetails `text-callout`,
 * `font-mono` for machine text, `foreground-hint` for empty values).
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class MembersAgentComputerInfoScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun computerInfoLight() = capture("members.agent-computer-info", RaftAppAppearance.Light)

    @Test
    fun computerInfoDark() = capture("members.agent-computer-info.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { ComputerInfoContent() }
            }
        }
}

private val computerInfoAgent =
    MemberProfile(
        id = MemberId("agent-ruby"),
        displayName = "Ruby",
        handle = "Ruby",
        kind = MemberKind.Agent,
    )

private val computerInfoSummary =
    AgentDetailSummary(
        id = "agent-ruby",
        name = "Ruby",
        displayName = "Ruby",
        runtime = "claude",
        machineId = "m-1",
        machineName = "daybed-earth",
        createdAt = "2026-10-20T08:30:00.000Z",
        creator = AgentCreatorSummary(type = "human", id = "u-artin", name = "artin", displayName = "Artin"),
    )

private val computerInfoTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 27),
        nowEpochMillis = 1_793_066_400_000L,
    )

@Composable
private fun ComputerInfoContent() {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
    ) {
        MembersAgentComputerInfoRows(
            member = computerInfoAgent,
            summary = computerInfoSummary,
            machineStatusRow = {
                MembersMachineStatusRow.Machine(online = true, runLabel = MembersMachineRunLabel.ComputerVersion("0.62.1"))
            },
            onOpenComputer = {},
            timeContext = computerInfoTimeContext,
            memberAvatarResolver = EmptyRaftMemberAvatarResolver,
        )
        MembersAgentRuntimeConfigRows(
            resolution =
                remember {
                    MembersAgentRuntimeConfigResolution.Content(
                        RuntimeConfig(
                            runtime = "claude",
                            provider = null,
                            model = membersRuntimeModelConfig("claude", "claude-opus-4-1", "preset"),
                            mode = RuntimeModeConfig.Default,
                            reasoningEffort = null,
                            envVars = null,
                        )
                    )
                },
            runtimeModelsCatalog = null,
            machineId = null,
        )
    }
}
