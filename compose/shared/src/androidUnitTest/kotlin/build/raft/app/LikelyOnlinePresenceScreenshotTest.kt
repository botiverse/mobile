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
import build.raft.app.data.likelyOnlineMemberStatusOrNull
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Likely online" (artin, #triage:#97; web botiverse/slock #11642), light and dark: an agent with
 * no presence fact and lifecycle `active` paints the green light, worded apart from Online.
 *  - `members.likely-online.list`: the production [MembersListContent]; Tenny is a real Online,
 *    Lagoon is the guess (green, like Online: list rows carry only the light), Volta is Offline.
 *  - `members.likely-online.profile-header`: the agent profile hero's status chip reads
 *    "Likely online" with the green light.
 * The guessed state comes from the store's own [likelyOnlineMemberStatusOrNull], not a hand-built
 * indicator. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LikelyOnlinePresenceScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h400dp-xxhdpi")
    fun listLight() = capture("members.likely-online.list", RaftAppAppearance.Light) { LikelyOnlineList() }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h400dp-xxhdpi")
    fun listDark() = capture("members.likely-online.list.dark", RaftAppAppearance.Dark) { LikelyOnlineList() }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
    fun profileHeaderLight() =
        capture("members.likely-online.profile-header", RaftAppAppearance.Light) { LikelyOnlineProfileHeader() }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
    fun profileHeaderDark() =
        capture("members.likely-online.profile-header.dark", RaftAppAppearance.Dark) { LikelyOnlineProfileHeader() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
        }
}

private fun likelyOnlineAgent(id: String, name: String, description: String? = null) =
    MemberProfile(
        id = MemberId(id),
        displayName = name,
        handle = name.lowercase(),
        kind = MemberKind.Agent,
        description = description,
        agentLifecycleStatus = "active",
        permissionBadges = listOf("computer:MacBook Pro", "computer-id:machine-mbp"),
    )

private fun likelyOnlineState(member: MemberProfile): RaftAgentStatusIndicatorState =
    assertNotNull(member.likelyOnlineMemberStatusOrNull()).toRaftAgentStatusIndicatorState()

private fun factState(status: AgentStatus): RaftAgentStatusIndicatorState =
    raftAgentStatusIndicatorState(
        status = status,
        presence = status.presence,
        external = false,
    )

@Composable
private fun LikelyOnlineList() {
    val lagoon = likelyOnlineAgent("agent-lagoon", "Lagoon", "Reviews pull requests")
    val payload =
        remember {
            RaftMembersPayload(
                instanceId = "members-likely-online-screenshot",
                members =
                    listOf(
                        likelyOnlineAgent("agent-tenny", "Tenny", "Ships the mobile app"),
                        lagoon,
                        likelyOnlineAgent("agent-volta", "Volta"),
                    ),
            )
        }
    val resolver: RaftAgentStatusResolver =
        remember {
            { id ->
                flowOf(
                    when (id) {
                        "agent-tenny" -> factState(AgentStatus.Online)
                        "agent-lagoon" -> likelyOnlineState(lagoon)
                        else -> factState(AgentStatus.Offline)
                    }
                )
            }
        }
    MembersListContent(
        payload = payload,
        onAddHuman = {},
        onOpenMember = {},
        agentStatusResolver = resolver,
        canCreateAgents = true,
        inviteHuman = MembersInviteHumanInputs(inviteHumanDialog = {}),
        currentUserId = "human-artin",
    )
}

@Composable
private fun LikelyOnlineProfileHeader() {
    val member = likelyOnlineAgent("agent-lagoon", "Lagoon")
    val state = likelyOnlineState(member)
    RaftDesignSystemTheme {
        Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(horizontal = 20.dp, vertical = 16.dp)) {
            MembersAgentProfileHeader(
                member = member,
                summary =
                    AgentDetailSummary(
                        id = "agent-lagoon",
                        name = "lagoon",
                        displayName = "Lagoon",
                        status = "active",
                        runtime = "claude",
                    ),
                canEdit = false,
                agentStatusObservedState = state,
                agentStatusIndicatorState = state,
                onChangeAvatar = {},
            )
        }
    }
}
