package build.raft.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.MEMBER_DETAIL_PAGE
import build.raft.app.MemberDetailIdentity
import build.raft.app.MembersAgentDetailTab
import build.raft.app.RaftChannelKindIcon
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMembersPayload
import build.raft.app.buildThreadLaunchConversationIdentity
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArchivedAgent
import build.raft.app.data.ArchivedAgentWorkspace
import build.raft.app.data.ArchivedChannel
import build.raft.app.data.NetworkServerArchiveDataSource
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedServerArchiveIntent
import build.raft.app.data.SharedServerArchiveState
import build.raft.app.data.SharedServerArchiveStore
import build.raft.app.data.serverRoleCanArchiveChannels
import build.raft.app.get
import build.raft.app.iconName
import build.raft.app.isRaftExternalMembersChannel
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.openRaftPage
import build.raft.app.raftArgbHex
import build.raft.app.raftChannelRecordKindGlyph
import build.raft.app.raftLocalizedMediumDateTime
import build.raft.app.resources.*
import build.raft.app.serverOrderKey
import build.raft.app.settings.iconName
import build.raft.app.thread.THREAD_PAGE
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.app.thread.threadLaunchPageData
import build.raft.app.toMemberDetailPageData
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.raftAvatarInitials

/*
 * Settings › Server's Archived channels and Archived agents (web
 * `components/settings/ArchivedChannelsSection.tsx`, `UnarchiveChannelButton.tsx`,
 * `ArchivedAgentsSection.tsx`), between Public access and the Danger zone as the web's
 * ServerTabContent orders them. Archived channels: owner and admin only (`archiveChannels`), each
 * with Unarchive; archived agents: when, and where the workspace is.
 *
 * As on the web, a channel's row opens the channel (read-only, `nav.toChannel`), an agent's row
 * its read-only profile (`nav.toAgent`), and View workspace (a workspace still on a computer)
 * the agent's workspace (`nav.toAgentWorkspace`). The web's names are TextLinks; here the whole
 * row is the target (a list row, which a finger hits more surely than a name), its buttons apart.
 */

internal const val SettingsArchivedChannelsSectionTag = "archived-channels-section"
internal const val SettingsArchivedAgentsSectionTag = "archived-agents-section"

internal fun settingsArchivedChannelRowTag(id: String) = "archived-channel-row-$id"

internal fun settingsArchivedAgentRowTag(id: String) = "archived-agent-row-$id"

internal fun settingsArchivedAgentWorkspaceTag(id: String) = "archived-agent-workspace-$id"

/** The sections' state and intents; [canArchiveChannels]: the viewer's role may archive. */
internal class SettingsServerArchiveUi(
    val state: SharedServerArchiveState,
    val canArchiveChannels: Boolean,
    val dispatch: (SharedServerArchiveIntent) -> Unit,
    /** The clock the dates read in (null: the app's live one; screenshots fix it). */
    val timeContext: RaftTimeContext? = null,
    /** A channel's row: opens the (archived, read-only) channel. */
    val onOpenChannel: (ArchivedChannel) -> Unit = {},
    /** An agent's row: opens the agent's read-only profile, by agent id. */
    val onOpenAgent: (String) -> Unit = {},
    /** View workspace (a workspace on a computer): opens the agent's workspace, by agent id. */
    val onOpenAgentWorkspace: (String) -> Unit = {},
)

/** The page's store, read for [serverId] whenever it or the viewer's [role] changes. */
@Composable
internal fun rememberSettingsServerArchive(
    networkClient: RaftNetworkClient?,
    serverId: String?,
    role: String?,
    onOpenChannel: (ArchivedChannel) -> Unit = {},
    onOpenAgent: (String) -> Unit = {},
    onOpenAgentWorkspace: (String) -> Unit = {},
): SettingsServerArchiveUi? {
    if (networkClient == null) return null
    val scope = rememberCoroutineScope()
    val store = remember(networkClient, scope) { SharedServerArchiveStore(NetworkServerArchiveDataSource(networkClient), scope) }
    val canArchive = serverRoleCanArchiveChannels(role)
    LaunchedEffect(store, serverId, canArchive) { store.dispatch(SharedServerArchiveIntent.Load(serverId, canArchive)) }
    val state by store.state.collectAsState()
    return SettingsServerArchiveUi(
        state = state,
        canArchiveChannels = canArchive,
        dispatch = store::dispatch,
        onOpenChannel = onOpenChannel,
        onOpenAgent = onOpenAgent,
        onOpenAgentWorkspace = onOpenAgentWorkspace,
    )
}

/**
 * Opens an archived channel (read-only) over Settings, web `nav.toChannel(channelId)`: the same
 * launch as the archived-name banner's Open Channel (`ArchivedNameCollision.toThreadLaunchConversationIdentity`).
 */
internal fun Pager.openSettingsArchivedChannel(channel: ArchivedChannel, storeScope: RaftShellStoreScope, onWarn: (String) -> Unit) {
    val conversation =
        buildThreadLaunchConversationIdentity(
            kind = if (channel.type == "joint") ConversationKind.Joint else ConversationKind.Channel,
            channelId = ChannelId(channel.id),
            channelType = channel.type,
        )
    val launch = buildStandaloneConversationThreadLaunchPayload(conversation = conversation, timeContext = null, onWarn = onWarn)
    if (launch == null) {
        onWarn("settings_archived_channel_open_rejected reason=identity_unresolved")
        return
    }
    openRaftPage(THREAD_PAGE, threadLaunchPageData(launch), storeScopeOverride = storeScope)
}

/**
 * Opens an archived agent's read-only profile (web `nav.toAgent`), or with [workspace] its
 * Workspace tab (web `nav.toAgentWorkspace`), as Settings › Computers opens an agent.
 */
internal fun Pager.openSettingsArchivedAgent(
    agentId: String,
    serverId: String?,
    workspace: Boolean,
    storeScope: RaftShellStoreScope,
    onWarn: (String) -> Unit,
) {
    val payload =
        RaftMembersPayload(
            instanceId = "settings-archived-agent",
            initialAgentTab = if (workspace) MembersAgentDetailTab.Workspace.serverOrderKey else null,
        )
    val identity = MemberDetailIdentity(MemberId(agentId), MemberKind.Agent, serverId?.takeIf { it.isNotBlank() }?.let(::ServerId))
    val pageData = payload.toMemberDetailPageData(identity)
    if (pageData == null) {
        onWarn("settings_archived_agent_open_skip reason=invalid_agent")
        return
    }
    openRaftPage(
        pageName = MEMBER_DETAIL_PAGE,
        pageData = pageData,
        storeScopeOverride = storeScope,
        onMissingScope = { onWarn("settings_archived_agent_open_skip reason=missing_shell_scope") },
    )
}

/** The archive's date in the reader's zone, web `formatMediumDateTime`; the raw value if unreadable. */
@Composable
private fun settingsArchiveDate(iso: String, timeContext: RaftTimeContext?): String =
    raftLocalizedMediumDateTime(iso, timeContext)

internal val SettingsServerArchiveUi.channelsVisible: Boolean
    get() = canArchiveChannels && state.channels.isNotEmpty()

internal val SettingsServerArchiveUi.agentsVisible: Boolean
    get() = state.agents.isNotEmpty()

@Composable
internal fun SettingsArchivedChannelsSection(ui: SettingsServerArchiveUi) {
    if (!ui.channelsVisible) return
    val state = ui.state
    RaftDesignSystemTheme {
    SettingsFormSection(iconName = RaftLucideIconName.Archive, title = stringResource(Res.string.settings_archived_channels_title)) {
        Column(
            Modifier.fillMaxWidth().testTag(SettingsArchivedChannelsSectionTag).settingsSearchAnchor("archived-channels"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val error = state.unarchiveError ?: if (state.unarchiveFailedGeneric) stringResource(Res.string.channel_settings_unarchive_failed) else null
            error?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm) }
            RaftListRowGroup(Modifier.fillMaxWidth()) {
                state.channels.forEachIndexed { index, channel ->
                    SettingsArchivedChannelRow(
                        channel = channel,
                        divider = index > 0,
                        busy = state.unarchivingChannelId == channel.id,
                        enabled = state.unarchivingChannelId == null,
                        timeContext = ui.timeContext,
                        onOpen = { ui.onOpenChannel(channel) },
                    ) { ui.dispatch(SharedServerArchiveIntent.Unarchive(channel.id)) }
                }
            }
        }
    }
}
}

/** External first (a guest, a guest server or an open invite), as the channel's header tags it. */
@Composable
private fun settingsArchivedChannelTypeLabel(channel: ArchivedChannel): String =
    stringResource(
        when {
            // Web ArchivedChannelsSection `isExternalMembersChannel`: a Slack channel is not tagged External.
            isRaftExternalMembersChannel(channel.type, channel.hasExternalRelation) -> Res.string.settings_archived_channels_joint
            channel.type == "private" -> Res.string.settings_archived_channels_private
            else -> Res.string.settings_archived_channels_public
        }
    )

@Composable
private fun SettingsArchivedChannelRow(
    channel: ArchivedChannel,
    divider: Boolean,
    busy: Boolean,
    enabled: Boolean,
    timeContext: RaftTimeContext?,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val typeLabel = settingsArchivedChannelTypeLabel(channel)
    val openLabel = stringResource(Res.string.settings_archived_channels_open_label, channel.name)
    RaftListRow(
        title = stringResource(Res.string.settings_archived_channels_channel_name, channel.name),
        modifier =
            Modifier.testTag(settingsArchivedChannelRowTag(channel.id)).semantics {
                onClick(label = openLabel) {
                    onOpen()
                    true
                }
            },
        onClick = onOpen,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        subtitle = stringResource(Res.string.settings_archived_channels_metadata, typeLabel, settingsArchiveDate(channel.archivedAt, timeContext)),
        leading = {
            RaftIconTile(size = RaftIconTileSize.Sm) {
                RaftChannelKindIcon(
                    glyph =
                        raftChannelRecordKindGlyph(
                            channel.type,
                            hasExternalRelation = channel.hasExternalRelation,
                            bridgeProvider = channel.bridgeProvider,
                        ),
                    size = RaftIconTileSize.Sm.icon,
                    color = raftArgbHex(RaftColor.ForegroundMuted),
                )
            }
        },
        trailing = {
            RaftButton(
                text = stringResource(if (busy) Res.string.channel_settings_unarchiving else Res.string.joint_create_unarchive),
                onClick = onUnarchive,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = enabled,
            )
        },
    )
}

@Composable
internal fun SettingsArchivedAgentsSection(ui: SettingsServerArchiveUi) {
    if (!ui.agentsVisible) return
    RaftDesignSystemTheme {
    SettingsFormSection(iconName = RaftLucideIconName.Archive, title = stringResource(Res.string.settings_archived_agents_title)) {
        RaftListRowGroup(Modifier.fillMaxWidth().testTag(SettingsArchivedAgentsSectionTag).settingsSearchAnchor("archived-agents")) {
            ui.state.agents.forEachIndexed { index, agent ->
                SettingsArchivedAgentRow(
                    agent,
                    divider = index > 0,
                    timeContext = ui.timeContext,
                    onOpen = { ui.onOpenAgent(agent.agentId) },
                    onOpenWorkspace = { ui.onOpenAgentWorkspace(agent.agentId) },
                )
            }
        }
    }
}
}

@Composable
private fun SettingsArchivedAgentRow(
    agent: ArchivedAgent,
    divider: Boolean,
    timeContext: RaftTimeContext?,
    onOpen: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val workspace =
        when (agent.workspace) {
            ArchivedAgentWorkspace.Deleting -> stringResource(Res.string.settings_archived_agents_workspace_deleting)
            ArchivedAgentWorkspace.OnComputer -> stringResource(Res.string.settings_archived_agents_workspace_on_computer, agent.machineName.orEmpty())
            ArchivedAgentWorkspace.Deleted -> stringResource(Res.string.settings_archived_agents_workspace_deleted)
            ArchivedAgentWorkspace.Unknown -> stringResource(Res.string.settings_archived_agents_workspace_unknown)
        }
    val openLabel = stringResource(Res.string.settings_archived_agents_open_label, agent.name)
    val workspaceLabel = stringResource(Res.string.settings_archived_agents_view_workspace_label, agent.name)
    RaftListRow(
        title = agent.name,
        modifier =
            Modifier.testTag(settingsArchivedAgentRowTag(agent.agentId)).semantics {
                onClick(label = openLabel) {
                    onOpen()
                    true
                }
            },
        onClick = onOpen,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        subtitle = stringResource(Res.string.settings_archived_agents_metadata, settingsArchiveDate(agent.archivedAt, timeContext), workspace),
        leading = {
            // Web: the agent's avatar, greyed (an archived agent).
            RaftAvatar(
                type = RaftAvatarType.Agent,
                size = RaftAvatarSize.Sm,
                initials = raftAvatarInitials(agent.name, RaftAvatarSize.Sm).ifBlank { "A" },
            )
        },
        // Web: an outline View workspace, only while the workspace is on a computer to browse.
        trailing =
            if (agent.workspace == ArchivedAgentWorkspace.OnComputer) {
                {
                    RaftButton(
                        text = stringResource(Res.string.settings_archived_agents_view_workspace),
                        onClick = onOpenWorkspace,
                        modifier =
                            Modifier.testTag(settingsArchivedAgentWorkspaceTag(agent.agentId)).semantics {
                                onClick(label = workspaceLabel) {
                                    onOpenWorkspace()
                                    true
                                }
                            },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                    )
                }
            } else {
                null
            },
    )
}
