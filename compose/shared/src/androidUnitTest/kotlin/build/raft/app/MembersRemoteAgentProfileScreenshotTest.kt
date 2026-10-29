package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot of the profile of an agent from another server of a joint channel (web
 * AgentDetailPanel `isRemoteJointAgent`): "From <server>" over the hero (avatar, name,
 * @handle, the Agent tag, no runtime chip) and the description; no Info, Runtime, Skills or
 * Actions sections and no Message action. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MembersRemoteAgentProfileScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun remoteAgentLight() = capture("members.remote-agent-profile", RaftAppAppearance.Light)

    @Test
    fun remoteAgentDark() = capture("members.remote-agent-profile.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    MembersRemoteAgentProfileVisualContent(member = remoteAgent, detail = remoteAgentSeed)
                }
            }
        }
}

private val remoteAgent =
    MemberProfile(
        id = MemberId("agent-partner-ruby"),
        displayName = "Ruby",
        handle = "ruby",
        kind = MemberKind.Agent,
        description = "Triages partner bug reports.",
        serverId = ServerId("server-partner"),
        serverName = "Partner Labs",
    )

private val remoteAgentSeed =
    AgentDetailSnapshot(
        agentId = remoteAgent.id,
        summary =
            AgentDetailSummary(
                id = remoteAgent.id.value,
                name = "ruby",
                displayName = "Ruby",
                description = "Triages partner bug reports.",
                status = "active",
            ),
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = emptyList(),
    )
