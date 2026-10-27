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
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot of the agent profile hero (web AgentDetailPanel profile hero), light and
 * dark: the sticker avatar, the name (PanelTitle, `text-headline` bold), the `@handle`
 * (Space Mono `text-mono-meta`, `foreground-hint`), the outline `Agent` tag, the runtime
 * chip led by Lucide `cpu` (web `RUNTIME_CHIP_CLASS`, Space Mono 400 on `info-soft`) and the
 * online status chip with its 10dp light; `.long` wraps a long runtime label at 360dp. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
class MembersAgentProfileHeaderScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun headerLight() = capture("members.agent-profile-header", RaftAppAppearance.Light)

    @Test
    fun headerDark() = capture("members.agent-profile-header.dark", RaftAppAppearance.Dark)

    // A long name, a long handle and the longest runtime label at a 360dp phone width: the
    // tags wrap (web `flex-wrap`) instead of cutting the runtime chip.
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h240dp-xxhdpi")
    fun headerLongLight() = capture("members.agent-profile-header.long", RaftAppAppearance.Light, long = true)

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h240dp-xxhdpi")
    fun headerLongDark() = capture("members.agent-profile-header.long.dark", RaftAppAppearance.Dark, long = true)

    private fun capture(name: String, appearance: RaftAppAppearance, long: Boolean = false) =
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
                RaftDesignSystemTheme { HeaderContent(if (long) longAgent else headerAgent, if (long) longSummary else headerSummary) }
            }
        }
}

private val headerAgent =
    MemberProfile(
        id = MemberId("agent-ruby"),
        displayName = "Ruby",
        handle = "Ruby",
        kind = MemberKind.Agent,
    )

private val headerSummary =
    AgentDetailSummary(
        id = "agent-ruby",
        name = "Ruby",
        displayName = "Ruby",
        status = "online",
        runtime = "builtin",
    )

private val longAgent =
    MemberProfile(
        id = MemberId("agent-long"),
        displayName = "Release Notes Wrangler for the Mobile Team",
        handle = "release-notes-wrangler-mobile-team",
        kind = MemberKind.Agent,
    )

private val longSummary =
    AgentDetailSummary(
        id = "agent-long",
        name = "release-notes-wrangler-mobile-team",
        displayName = "Release Notes Wrangler for the Mobile Team",
        status = "online",
        runtime = "gemini",
    )

@Composable
private fun HeaderContent(member: MemberProfile, summary: AgentDetailSummary) {
    val online = raftAgentStatusIndicatorState(status = AgentStatus.Online, presence = Presence.Active, external = false)
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(horizontal = 20.dp, vertical = 16.dp)) {
        MembersAgentProfileHeader(
            member = member,
            summary = summary,
            canEdit = false,
            agentStatusObservedState = online,
            agentStatusIndicatorState = online,
            onChangeAvatar = {},
        )
    }
}
