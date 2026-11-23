package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.resources.*
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the channel members page (`channel.members`): the production
 * [ThreadMembersPageContent] with a fixed roster (four humans, one a channel admin; three
 * agents with fixed activity) in zh-CN, light and dark. Reports land in
 * `build/reports/home-screenshots/` (uploaded by CI as `shared-home-screenshots`); verify /
 * record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ChannelMembersPageScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelMembersLight() = capture("channel.members", RaftAppAppearance.Light)

    @Test
    fun channelMembersDark() = capture("channel.members.dark", RaftAppAppearance.Dark)

    // Channel role actions (web MemberRoleActions, #triage:#398): one ⋯ on every row the viewer may
    // change or remove (its menu: Make admin / Demote or the server admin's cannot-demote note, and
    // Remove from channel, #triage:#416), none on the viewer's own row, and the warning a refused
    // change leaves above the roster.
    @Test
    fun channelMembersRoleActionsLight() =
        capture("channel.members.role-actions", RaftAppAppearance.Light) {
            ChannelMembersVisualContent(
                roleActionVisualRoster(),
                roleChange = ChannelMemberRoleChangeState(failed = true),
            )
        }

    // Add Member (web ChannelMembers add view): nothing selected yet, as artin's web capture.
    @Test
    fun channelMembersAddLight() =
        capture("channel.members.add", RaftAppAppearance.Light) {
            ChannelAddMemberPanelVisualScreen(
                viewerRole = VisualFixtureData.Humans.Owner.ROLE,
                serverName = VisualFixtureData.Server.NAME,
                nowEpochMillis = VisualFixtureData.Locale.NOW_EPOCH_MILLIS,
            )
        }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit = { ChannelMembersVisualContent() }) {
        // zh-CN copy, restored here (not in a DisposableEffect: the activity outlives the
        // capture) so later screenshot tests in the same JVM keep their locale.
        val previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
        try {
            RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
                val pager =
                    remember {
                        object : Pager() {}.apply {
                            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                        }
                    }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
            }
        } finally {
            StringDesc.localeType = previousLocale
        }
    }
}

/** Fixed roster and agent activity, so the capture is deterministic. */
@Composable
private fun ChannelMembersVisualContent(
    roster: List<RaftThreadMemberItem>? = null,
    roleChange: ChannelMemberRoleChangeState = ChannelMemberRoleChangeState(),
    channelKind: String? = null,
    viewerServerName: String = "",
) {
    val members =
        remember {
            roster ?: listOf(
                RaftThreadMemberItem("human-artea", "Artea", handle = "artea", effectiveChannelRole = "admin"),
                RaftThreadMemberItem("human-artin", "artin", handle = "artin", description = "产品负责人"),
                RaftThreadMemberItem("human-cindyz", "cindyz", handle = "cindyz"),
                RaftThreadMemberItem("human-xxchan", "xxchan", handle = "xxchan", description = "我是笑笑"),
                RaftThreadMemberItem("agent-tenny", "Tenny", handle = "tenny", kind = "agent"),
                RaftThreadMemberItem("agent-lagoon", "Lagoon", handle = "lagoon", kind = "agent"),
                RaftThreadMemberItem("agent-volta", "Volta", handle = "volta", kind = "agent"),
            )
        }
    val resolver: RaftAgentStatusResolver =
        remember {
            { id ->
                val status =
                    when (id) {
                        "agent-tenny" -> AgentStatus.Online
                        "agent-lagoon" -> AgentStatus.Idle
                        else -> AgentStatus.Offline
                    }
                flowOf(
                    raftAgentStatusIndicatorState(
                        status = status,
                        presence = if (status == AgentStatus.Offline) Presence.Offline else Presence.Active,
                        statusLabel = status.name.lowercase(),
                        external = false,
                    )
                )
            }
        }
    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        ThreadMembersPageContent(
            members = members,
            filteredMembers = members,
            filteredExternal = emptyList(),
            externalMemberCount = 0,
            // One server member not in the channel yet, so Add Member is enabled (web `hasAvailable`).
            candidates = listOf(RaftThreadMemberItem("human-quill", "Quill", handle = "quill")),
            rosterQuery = TextFieldValue(""),
            onQueryChange = {},
            agentsExpanded = true,
            humansExpanded = true,
            externalExpanded = true,
            onAgentsExpandedChange = {},
            onHumansExpandedChange = {},
            onExternalExpandedChange = {},
            addDialogVisible = false,
            onAddVisibleChange = {},
            addMemberStatus = "idle",
            addMemberErrorCode = null,
            removalGate = ThreadMemberRemovalGateState(),
            membersFailed = false,
            membersLoading = false,
            refreshing = false,
            canAddMembers = true,
            agentStatusResolver = resolver,
            memberAvatarResolver = EmptyRaftMemberAvatarResolver,
            onBack = {},
            onRefresh = {},
            onOpenMember = {},
            onRemoveMember = {},
            onSubmit = {},
            roleChange = roleChange,
            channelKind = channelKind,
            viewerServerName = viewerServerName,
        )
    }
}

/** The roster a channel admin sees: their own row bare, everyone they may change or remove with a ⋯. */
private fun roleActionVisualRoster(): List<RaftThreadMemberItem> =
    listOf(
        RaftThreadMemberItem("human-artea", "Artea", handle = "artea", effectiveChannelRole = "admin", canRemove = false),
        RaftThreadMemberItem(
            "human-artin", "artin", handle = "artin", description = "产品负责人", effectiveChannelRole = "owner",
            roleAction = ChannelMemberRoleAction.ExplainProtectedDemote,
        ),
        RaftThreadMemberItem(
            "human-cindyz", "cindyz", handle = "cindyz", effectiveChannelRole = "admin",
            roleAction = ChannelMemberRoleAction.RemoveAdmin,
        ),
        RaftThreadMemberItem("human-xxchan", "xxchan", handle = "xxchan", description = "我是笑笑", roleAction = ChannelMemberRoleAction.MakeAdmin),
        RaftThreadMemberItem("agent-tenny", "Tenny", handle = "tenny", kind = "agent", roleAction = ChannelMemberRoleAction.MakeAdmin),
        RaftThreadMemberItem(
            "agent-lagoon", "Lagoon", handle = "lagoon", kind = "agent", effectiveChannelRole = "admin",
            roleAction = ChannelMemberRoleAction.RemoveAdmin,
        ),
    )
