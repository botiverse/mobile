package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.MembersAgentMachineGroupCollapseStore
import build.raft.app.data.MembersAgentsGrouping
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the server members list (`members.list`, the All tab; `members.list.by-computer`, the
 * Agents tab grouped By computer; `members.list.wide`, the Agents tab in a 1024dp window under the
 * shell's top bar, with the head and its counts): the production
 * [MembersListContent] with agents on two computers (online, working, offline) and humans (the
 * viewer, an owner, a guest), light and dark. Reports land in `build/reports/home-screenshots/`
 * (uploaded by CI as `shared-home-screenshots`); verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class MembersListScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun membersListLight() = capture("members.list", RaftAppAppearance.Light)

    @Test
    fun membersListDark() = capture("members.list.dark", RaftAppAppearance.Dark)

    /** The Agents list grouped By computer, as the reader chose it (the default is No grouping, slock #14068). */
    @Test
    fun membersListByComputer() = capture("members.list.by-computer", RaftAppAppearance.Light, MembersAgentsGrouping.Computer, MembersTab.Agents)

    /**
     * Wide, under the app shell's top bar (a 1024dp window): the head with "Members" and its counts
     * ("1 agent working · 6 members"), and the toolbar on one line with every label (the Agents tab:
     * tabs, search, Group, Invite human, Create agent ▾).
     */
    @Test
    @Config(qualifiers = "w1024dp-h640dp-mdpi")
    fun membersListWide() = capture("members.list.wide", RaftAppAppearance.Light, tab = MembersTab.Agents, underTopBar = true)

    @Test
    @Config(qualifiers = "w1024dp-h640dp-mdpi")
    fun membersListWideDark() = capture("members.list.wide.dark", RaftAppAppearance.Dark, tab = MembersTab.Agents, underTopBar = true)

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        grouping: MembersAgentsGrouping? = null,
        tab: MembersTab = MembersTab.All,
        underTopBar: Boolean = false,
    ) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            val topBar = remember(underTopBar) { if (underTopBar) RaftAppShellTopBarState() else null }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftAppShellTopBar provides topBar) {
                MembersListVisualContent(grouping, initialTab = tab)
            }
        }
    }
}

private fun agent(id: String, name: String, description: String?, computerId: String, computerName: String) =
    MemberProfile(
        id = MemberId(id),
        displayName = name,
        handle = name.lowercase(),
        kind = MemberKind.Agent,
        description = description,
        permissionBadges = listOf("computer:$computerName", "computer-id:$computerId"),
    )

private fun human(id: String, name: String, description: String? = null, serverRole: String = "member") =
    MemberProfile(
        id = MemberId(id),
        displayName = name,
        handle = name.lowercase(),
        kind = MemberKind.Human,
        description = description,
        serverRole = serverRole,
    )

/** A store holding the reader's grouping choice; no group collapsed. */
internal class MembersGroupingTestStore(var grouping: MembersAgentsGrouping?) : MembersAgentMachineGroupCollapseStore {
    override fun isCollapsed(userId: String, machineKey: String): Boolean = false

    override fun setCollapsed(userId: String, machineKey: String, collapsed: Boolean) {}

    override fun agentsGrouping(userId: String): MembersAgentsGrouping = grouping ?: MembersAgentsGrouping.Default

    override fun setAgentsGrouping(userId: String, grouping: MembersAgentsGrouping) {
        this.grouping = grouping
    }
}

/** A fixed roster and fixed agent statuses, so the capture is deterministic. [grouping] null: none chosen. */
@Composable
internal fun MembersListVisualContent(
    grouping: MembersAgentsGrouping? = null,
    store: MembersAgentMachineGroupCollapseStore? = remember(grouping) { grouping?.let(::MembersGroupingTestStore) },
    initialTab: MembersTab = MembersTab.All,
    showGuests: Boolean = true,
) {
    val payload =
        remember {
            RaftMembersPayload(
                instanceId = "members-list-screenshot",
                showGuests = showGuests,
                members =
                    listOf(
                        agent("agent-tenny", "Tenny", "Ships the mobile app", "machine-mbp", "MacBook Pro"),
                        agent("agent-lagoon", "Lagoon", "Reviews pull requests", "machine-mbp", "MacBook Pro"),
                        agent("agent-volta", "Volta", null, "machine-daybed", "daybed-earth"),
                        human("human-artin", "artin", description = "Product lead", serverRole = "owner"),
                        human("human-artea", "Artea", serverRole = "admin"),
                        human("human-quill", "Quill", description = "Design partner", serverRole = "guest"),
                    ),
            )
        }
    val resolver: RaftAgentStatusResolver =
        remember {
            { id ->
                val status =
                    when (id) {
                        "agent-tenny" -> AgentStatus.Online
                        "agent-lagoon" -> AgentStatus.Working
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
    MembersListContent(
        payload = payload,
        onAddHuman = {},
        onOpenMember = {},
        agentStatusResolver = resolver,
        canCreateAgents = true,
        inviteHuman = MembersInviteHumanInputs(inviteHumanDialog = {}),
        currentUserId = "human-artin",
        agentGroupCollapseStore = store,
        initialTab = initialTab,
    )
}
