package build.raft.app

import build.raft.app.model.AvatarReference
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRequiredAction
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The App Bot badge on the surfaces AppFather listed after #3757, light and dark, against
 * botiverse/slock staging (`Sidebar.tsx`, `EntityResultRow`, `MessageInput`,
 * `ForwardComposerTargetList`, `ChannelMemberList`, `ChannelMembers`, `CreateChannelDialog`).
 * Every agent here has a live status (Online on a runtime, or Offline) so the frames show what a
 * Bot leaves out:
 *  - `Stamp` is a Bot named after its App: plain "Bot".
 *  - `Release helper` is a Bot shown under another name: "Bot · Listing Bot App".
 *  - `Volta` is an ordinary agent: its light, runtime, "Agent" tag and "Offline" line stay.
 *
 * Frames: Home DMs (badge after the name; no light, no runtime), search results (the outline Bot
 * tag replaces the kind tag; a Bot's subtitle is "@stamp", not "Agent · @stamp"; no light), @-mention suggestions (Bot replaces "Agent"; no light),
 * forward targets (open DM and new DM), channel members + Create Channel's member picker (badge
 * after the name; no light, no "Offline" line). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class AppBotSurfacesScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun homeDmsLight() = capture("home.app-bot.dm-rows", RaftAppAppearance.Light) { HomeDmRows() }

    @Test fun homeDmsDark() = capture("home.app-bot.dm-rows.dark", RaftAppAppearance.Dark) { HomeDmRows() }

    @Test fun searchResultsLight() = capture("search.app-bot.results", RaftAppAppearance.Light) { SearchRows() }

    @Test fun searchResultsDark() = capture("search.app-bot.results.dark", RaftAppAppearance.Dark) { SearchRows() }

    @Test fun mentionsLight() = capture("composer.app-bot.mentions", RaftAppAppearance.Light) { MentionRows() }

    @Test fun mentionsDark() = capture("composer.app-bot.mentions.dark", RaftAppAppearance.Dark) { MentionRows() }

    @Test fun forwardTargetsLight() = capture("forward.app-bot.targets", RaftAppAppearance.Light) { ForwardRows() }

    @Test fun forwardTargetsDark() = capture("forward.app-bot.targets.dark", RaftAppAppearance.Dark) { ForwardRows() }

    @Test fun channelMembersLight() = capture("members.app-bot.channel", RaftAppAppearance.Light) { ChannelMemberRows() }

    @Test fun channelMembersDark() = capture("members.app-bot.channel.dark", RaftAppAppearance.Dark) { ChannelMemberRows() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMemberAppBotResolver provides surfaceAppBots,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
                }
            }
        }
}

private val surfaceAppBots =
    raftStaticMemberAppBotResolver(
        mapOf(
            "agent-stamp" to RaftAppBotIdentity(handle = "stamp", appName = "Stamp", removed = false),
            "agent-helper" to RaftAppBotIdentity(handle = "listing-bot", appName = "Listing Bot App", removed = false),
        ),
    )

private fun surfaceStatus(status: AgentStatus, runtime: String) =
    SharedServerMemberStatus(status = status, presence = status.presence, runtime = runtime, external = runtime == "external")
        .toRaftAgentStatusIndicatorState()

/** Every agent has a status, Bots included: what a Bot row shows of it is the point. */
private val surfaceStatuses: RaftAgentStatusResolver = run {
    val byId =
        mapOf(
            "agent-stamp" to surfaceStatus(AgentStatus.Online, "external"),
            "agent-helper" to surfaceStatus(AgentStatus.Offline, "external"),
            "agent-volta" to surfaceStatus(AgentStatus.Online, "claude"),
        )
    ({ memberId -> flowOf(byId[memberId]) })
}

private val surfaceOfflineStatuses: RaftAgentStatusResolver = run {
    val byId =
        listOf("agent-stamp", "agent-helper", "agent-volta").associateWith { surfaceStatus(AgentStatus.Offline, "claude") }
    ({ memberId -> flowOf(byId[memberId]) })
}

@Composable
private fun HomeDmRows() {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        HomeSidebarDirectMessageRow(
            name = "Stamp",
            avatarLabel = "ST",
            peerType = "agent",
            memberId = "agent-stamp",
            agentStatusResolver = surfaceStatuses,
        )
        HomeSidebarDirectMessageRow(
            name = "Release helper for the mobile store listings",
            avatarLabel = "RH",
            description = "Drafts release notes",
            peerType = "agent",
            memberId = "agent-helper",
            agentStatusResolver = surfaceStatuses,
            hasDraft = true,
        )
        HomeSidebarDirectMessageRow(
            name = "Stamp",
            avatarLabel = "ST",
            peerType = "agent",
            memberId = "agent-stamp",
            agentStatusResolver = surfaceStatuses,
            unreadCount = 2,
            muted = true,
        )
        HomeSidebarDirectMessageRow(
            name = "Volta",
            avatarLabel = "VO",
            peerType = "agent",
            memberId = "agent-volta",
            agentStatusResolver = surfaceStatuses,
        )
        HomeSidebarDirectMessageRow(name = "Ada Lovelace", avatarLabel = "AL", peerType = "human", memberId = "human-ada")
    }
}

private fun searchResult(key: String, type: SearchEntityType, title: String, subtitle: String, memberId: String, kind: MemberKind) =
    SearchEntityResult(
        key = key,
        type = type,
        title = title,
        subtitle = subtitle,
        threadId = null,
        memberIdentity = MemberDetailIdentity(MemberId(memberId), kind),
        conversationIdentity = null,
        avatarWireValue = "pixel:robot".takeIf { kind == MemberKind.Agent },
        showPresence = kind == MemberKind.Agent,
        // As `toHomeSearchEntityResult` marks it: only the "@handle" after the kind word is data.
        subtitleDataRange =
            subtitle.indexOf('@').takeIf { it >= 0 }?.let { at ->
                at until subtitle.indexOf(" · ", at).let { end -> if (end < 0) subtitle.length else end }
            },
    )

@Composable
private fun SearchRows() {
    listOf(
        searchResult("a", SearchEntityType.Agent, "Stamp", "Agent · @stamp", "agent-stamp", MemberKind.Agent),
        searchResult("b", SearchEntityType.Agent, "Release helper", "Agent · @listing-bot · Members", "agent-helper", MemberKind.Agent),
        searchResult("b2", SearchEntityType.DirectMessage, "Release helper", "Drafts release notes", "agent-helper", MemberKind.Agent),
        searchResult("c", SearchEntityType.Agent, "Volta", "Agent · @volta", "agent-volta", MemberKind.Agent),
        searchResult("d", SearchEntityType.Human, "Ada Lovelace", "Person · @ada", "human-ada", MemberKind.Human),
    ).forEachIndexed { index, result ->
        SharedHomeSearchEntityRow(
            result = result,
            query = "",
            agentStatusResolver = surfaceStatuses,
            showSeparator = index > 0,
            onOpen = {},
        )
    }
}

@Composable
private fun MentionRows() {
    Column(Modifier.padding(vertical = 8.dp)) {
        listOf(
            RaftComposerMemberItem(displayName = "Stamp", handle = "stamp", kind = MemberKind.Agent, memberId = "agent-stamp", avatarWireValue = "pixel:robot"),
            RaftComposerMemberItem(
                displayName = "Release helper",
                handle = "listing-bot",
                kind = MemberKind.Agent,
                memberId = "agent-helper",
                description = "Drafts release notes",
                avatarWireValue = "pixel:robot",
            ),
            RaftComposerMemberItem(displayName = "Volta", handle = "volta", kind = MemberKind.Agent, memberId = "agent-volta", avatarWireValue = "pixel:robot"),
            RaftComposerMemberItem(displayName = "Ada Lovelace", handle = "ada", kind = MemberKind.Human, memberId = "human-ada"),
        ).forEachIndexed { index, member ->
            ComposerMemberSuggestionRow(
                member = member,
                inChannel = true,
                selected = index == 0,
                agentStatusResolver = surfaceStatuses,
                onClick = {},
            )
        }
    }
}

@Composable
private fun ForwardRows() {
    val server = ServerId("srv-1")
    Column(Modifier.padding(vertical = 8.dp)) {
        listOf(
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.RecentDirectMessage,
                id = "dm-stamp",
                title = "Stamp",
                avatarWireValue = "pixel:robot",
                channelId = ChannelId("dm-stamp"),
                memberId = MemberId("agent-stamp"),
            ),
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                id = "agent-helper",
                title = "Release helper",
                avatarWireValue = "pixel:robot",
                memberId = MemberId("agent-helper"),
                requiredAction = ForwardMessageTargetRequiredAction.CreateDirectMessage,
            ),
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.RecentDirectMessage,
                id = "dm-volta",
                title = "Volta",
                avatarWireValue = "pixel:robot",
                channelId = ChannelId("dm-volta"),
                memberId = MemberId("agent-volta"),
            ),
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "ch-general",
                title = "general",
                channelId = ChannelId("ch-general"),
                channelType = "public",
            ),
        ).forEachIndexed { index, target ->
            RaftForwardPickerTargetRow(
                target = target.toRaftForwardTargetPickerItem(),
                selected = index == 1,
                showCheckbox = true,
                interactive = true,
                onClick = {},
            )
        }
    }
}

private fun pickerAgent(id: String, name: String, installId: String?, appName: String?) =
    MemberProfile(
        id = MemberId(id),
        displayName = name,
        handle = name.lowercase().replace(' ', '-'),
        kind = MemberKind.Agent,
        avatar = AvatarReference.fromWireValue("pixel:robot"),
        agentStatus = AgentStatus.Offline,
        managedByAppInstallId = installId,
        managedByAppName = appName,
    )

@Composable
private fun ChannelMemberRows() {
    // Channel members page roster (the Add Members panel uses the same lookup and badge).
    listOf(
        RaftThreadMemberItem(id = "agent-stamp", displayName = "Stamp", kind = "agent", avatarUrl = "pixel:robot"),
        RaftThreadMemberItem(id = "agent-helper", displayName = "Release helper", kind = "agent", avatarUrl = "pixel:robot", canRemove = false),
        RaftThreadMemberItem(id = "agent-volta", displayName = "Volta", kind = "agent", avatarUrl = "pixel:robot", canRemove = false),
    ).forEachIndexed { index, member ->
        ThreadMembersPagerRow(
            member = member,
            divider = index > 0,
            agentStatusResolver = surfaceOfflineStatuses,
            memberAvatarResolver = EmptyRaftMemberAvatarResolver,
            removing = false,
            onOpen = {},
            onRemove = {},
        )
    }
    // Create Channel's Members field (raft member picker), straight from the directory.
    RaftMemberPicker(
        members =
            listOf(
                pickerAgent("agent-stamp", "Stamp", "install-1", "Stamp"),
                pickerAgent("agent-helper", "Release helper", "install-2", "Listing Bot App"),
                pickerAgent("agent-volta", "Volta", null, null),
            ),
        query = TextFieldValue(),
        onQueryChange = {},
        selectedKeys = emptySet(),
        onToggle = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        agentStatusResolver = surfaceOfflineStatuses,
    )
}
