package build.raft.app.thread

import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.app.components.RaftAnchoredMetaChip
import build.raft.app.data.map
import build.raft.app.externalInviteAccess
import build.raft.app.iconName
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import build.raft.app.optBoolean
import build.raft.app.raftAgentStatusIndicatorState
import build.raft.app.raftArgbHex
import build.raft.app.settings.iconName
import build.raft.app.thread.iconName
import build.raft.app.withRaftFontWeight
import build.raft.app.data.HomeChannelRow
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.ChannelSectionPlacement
import build.raft.app.ChannelSettingsCallbacks
import build.raft.app.ChannelSettingsContent
import build.raft.app.ChannelSettingsPermissions
import build.raft.app.ChannelSettingsScaffold
import build.raft.app.ChannelSettingsUiModel
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.members.iconName
import build.raft.app.ExternalInviteChannel
import build.raft.app.ExternalInvitesSection
import build.raft.app.ExternalInvitesState
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftColor
import build.raft.app.RaftHomeConversationItem
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftNotificationLevelTarget
import build.raft.app.RaftServerSwitcherItem
import build.raft.app.RaftStructuralHomeShell
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftType
import build.raft.app.ThreadChannelSettingsPanel
import build.raft.app.available
import build.raft.app.channelSettingsPermissions
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

@Composable
internal fun ThreadHeaderVisualScreen() {
    val payload = remember {
        RaftThreadScreenPayload(
            instanceId = "visual-thread-header",
            // The web case renders ChatPanel for the `channel-home` channel, whose header carries
            // Search ("Search this channel") beside Settings.
            sourceThreadId = "channel-home",
            title = "#首页专修",
            subtitle = "Home shell, server badge, and bottom dock polish",
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            selectedTab = "chat",
            showModeTabs = true,
            showSettingsAction = true,
            showMembersAction = true,
            membersCount = 4,
            showOpenInChannelAction = false
        )
    }
    // The real channel page (ThreadPageContent) hands the header its Task v2 Tasks / Files card
    // toggles; there is no Chat / Tasks / Files tab row any more (slock #16109). On a phone the
    // header folds to Search + ⋯ with Tasks, Files and Channel settings inside
    // (slock #16464). The explicit 120dp canvas matches the react element height
    // (1170x360px @3x = 390x120dp); the white slack under the header shows the height delta.
    val sideCardToggles =
        remember {
            ThreadHeaderSideCardToggles(
                tasksAvailable = true,
                cards = ConversationSideCardsState(),
                onToggle = {},
            )
        }
    Column(modifier = Modifier.width(390.dp).height(120.dp).background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
            sideCardToggles = sideCardToggles,
        )
    }
}

@Composable
internal fun Task797ThreadHeaderVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val applyTopSafeArea = pager.pageData.params.optBoolean("task797IncludeTopSafeArea", false)
    val agentIdentity =
        remember {
            MemberDetailIdentity(
                memberId = MemberId("agent-task797-pixel"),
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server-task797"),
            )
        }
    val payload =
        remember(agentIdentity) {
            RaftThreadScreenPayload(
                instanceId = "task797-thread-header-evidence",
                title = "Pixel visual verification for the unified secondary page header",
                subtitle = "Working - measuring safe-area alignment and action centerlines",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                streamChannelId = "dm-task797-pixel",
                selectedTab = "chat",
                showModeTabs = false,
                showSettingsAction = false,
                showMembersAction = false,
                showOpenInChannelAction = false,
                dmPeerIdentity = agentIdentity,
                dmPeerName = "Pixel visual verification",
                dmPeerAvatarLabel = "PX",
                activityMuted = false,
                activityMuteSupported = true,
            )
        }
    val headerDisplay =
        remember(agentIdentity) {
            ThreadHeaderDisplayModel(
                title = "Pixel visual verification for the unified secondary page header",
                subtitle = "Working - measuring safe-area alignment and action centerlines",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = RaftThreadSurfaceKind.DirectMessage,
                dmPeerAvatarLabel = "PX",
                dmPeerIdentity = agentIdentity,
            )
        }
    val activityState =
        remember {
            raftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Active,
                statusLabel = "Measuring safe-area alignment and action centerlines",
                external = false,
            )
        }
    val activityResolver: RaftAgentStatusResolver = remember(activityState) { { flowOf(activityState) } }
    Column(modifier = Modifier.width(390.dp).height(120.dp).background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            headerDisplay = headerDisplay,
            agentStatusResolver = activityResolver,
            applyTopSafeArea = applyTopSafeArea,
            activityMutedIndicatorVisible = payload.threadActivityMutedIndicatorVisible(null),
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
    }
}

@Composable
internal fun ThreadDmAgentActivityHeaderVisualScreen() {
    val agentIdentity =
        remember {
            MemberDetailIdentity(
                memberId = MemberId("agent-visual-friday"),
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server-visual")
            )
        }
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "visual-thread-dm-agent-activity",
                title = "Friday",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                dmPeerIdentity = agentIdentity,
                showModeTabs = false,
                showSettingsAction = false,
                showMembersAction = false,
                showOpenInChannelAction = false
            )
        }
    val headerDisplay =
        remember(agentIdentity) {
            ThreadHeaderDisplayModel(
                title = "Friday",
                subtitle = null,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = RaftThreadSurfaceKind.DirectMessage,
                dmPeerAvatarLabel = "FR",
                dmPeerIdentity = agentIdentity
            )
        }
    val activityState =
        remember {
            raftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Active,
                statusLabel = "Reviewing release readiness and visual diffs",
                external = false
            )
        }
    val activityResolver: RaftAgentStatusResolver = remember(activityState) { { flowOf(activityState) } }
    Column(modifier = Modifier.width(390.dp).height(120.dp).background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            headerDisplay = headerDisplay,
            agentStatusResolver = activityResolver,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {}
        )
    }
}

@Composable
internal fun ThreadActivityMuteVisualScreen() {
    val unmutedPayload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "visual-thread-activity-mute-unmuted",
                title = "#visual-testing",
                subtitle = "Visual parity and capture automation",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "channel-visual-testing",
                streamChannelId = "channel-visual-testing",
                selectedTab = "chat",
                showModeTabs = true,
                showSettingsAction = true,
                showMembersAction = true,
                membersCount = 8,
                activityMuted = false,
                activityMuteSupported = true
            )
        }
    val mutedPayload =
        remember {
            unmutedPayload.copy(
                instanceId = "visual-thread-activity-mute-muted",
                title = "#announcements",
                subtitle = "Release notes and high-signal updates",
                sourceThreadId = "channel-announcements",
                streamChannelId = "channel-announcements",
                membersCount = 3,
                activityMuted = true,
                muteFromSeq = 45
            )
        }
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = unmutedPayload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        ThreadPageHeader(
            payload = mutedPayload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        RaftStructuralHomeShell(
            modifier = Modifier.height(700.dp),
            currentServerName = "Raft Mobile",
            currentServerId = "server-visual",
            servers =
                listOf(
                    RaftServerSwitcherItem(
                        id = "server-visual",
                        name = "Raft Mobile",
                        slug = "mobile"
                    )
                ),
            channels =
                listOf(
                    RaftHomeConversationItem(
                        id = "channel-loud",
                        name = "critical",
                        kind = "channel",
                        unreadCount = 7,
                        joined = true,
                        activityMuted = false
                    ),
                    RaftHomeConversationItem(
                        id = "channel-muted",
                        name = "announcements",
                        kind = "channel",
                        unreadCount = 7,
                        joined = true,
                        activityMuted = true
                    )
                ),
            directMessages = emptyList(),
            showBottomBar = false,
            bottomContentPadding = 0.dp,
            outerPadding = 0.dp
        )
    }
}

@Composable
internal fun ThreadReactionAtlasVisualScreen() {
    // task #376 (Hands 389d855b): this standalone visual screen renders reaction
    // glyphs outside ThreadMessagesList, so it provides its own hoisted atlas
    // image for LocalThreadReactionAtlasImage (glyphs no longer self-load).
    ThreadReactionAtlasProvider {
        Column(
            modifier =
                Modifier
                    .width(342.dp)
                    .background(RaftColor.SurfaceWhite)
                    .padding(RaftAppSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
        ) {
            ThreadReactionVisualSection("Atlas sprites") {
                ThreadReactionAtlasImagePreview()
            }
            ThreadReactionVisualSection("Quick row") {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F4EA), RoundedCornerShape(RaftAppRadius.Sm)) // raft-color: visual fixture backdrop
                            .border(RaftAppStroke.Default, RaftColor.BorderBlack, RoundedCornerShape(RaftAppRadius.Sm))
                            .padding(vertical = RaftAppSpacing.Xs)
                ) {
                    ThreadMessageReactionRow(
                        reactionAtlasImage = LocalThreadReactionAtlasImage.current,
                        onReact = {}
                    )
                }
            }
            ThreadReactionVisualSection("Chips") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                ) {
                    ThreadReactionChip(RaftThreadReactionItem(emoji = "👍", count = 3, selected = true))
                    ThreadReactionChip(RaftThreadReactionItem(emoji = "❤️", count = 2, selected = false))
                    ThreadReactionChip(RaftThreadReactionItem(emoji = "✅", count = 1, selected = true))
                }
            }
            ThreadReactionVisualSection("Reaction chips (before + optimistic)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                ) {
                    ThreadReactionChip(RaftThreadReactionItem(emoji = "👍", count = 2, selected = false), onClick = {})
                    ThreadReactionChip(RaftThreadReactionItem(emoji = "👍", count = 3, selected = true), onClick = {})
                }
            }
            ThreadReactionVisualSection("Task meta (before + optimistic)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                ) {
                    RaftAnchoredMetaChip(
                        label = "#88 @Nathan",
                        iconName = RaftLucideIconName.CircleDashed,
                        color = RaftColor.AccentCyan,
                        contentColor = RaftColor.StickerInk,
                        iconColorArgb = raftArgbHex(RaftColor.StickerInk),
                        onClick = {}
                    )
                    RaftAnchoredMetaChip(
                        label = "#88 @Nathan",
                        iconName = RaftLucideIconName.CircleCheck,
                        color = RaftColor.AccentLime,
                        contentColor = RaftColor.StickerInk,
                        iconColorArgb = raftArgbHex(RaftColor.StickerInk),
                        onClick = {}
                    )
                }
            }
            ThreadReactionVisualSection("Toolbar state (before + optimistic)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                ) {
                    RaftDesignSystemTheme {
                        RaftPickerTriggerButton(
                            text = "Todo",
                            onClick = {},
                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.CircleDashed) }
                        )
                        RaftPickerTriggerButton(
                            text = "In progress",
                            onClick = {},
                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.CircleCheck) }
                        )
                    }
                }
            }
            ThreadReactionVisualSection("Fallback") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
                ) {
                    ThreadQuickReactionGlyph("☑️", modifier = Modifier.size(22.dp))
                    ThreadQuickReactionGlyph("🧪", modifier = Modifier.size(22.dp))
                    ThreadReactionChip(RaftThreadReactionItem(emoji = "🧪", count = 1, selected = false))
                }
            }
        }
    }
}

@Composable
private fun ThreadReactionVisualSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
        Text(
            text = title,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = RaftColor.TextBlack.copy(alpha = 0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun ThreadReactionAtlasImagePreview() {
    Box(
        modifier =
            Modifier
                .width(252.dp)
                .height(36.dp)
                .background(RaftColor.SurfaceCream),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThreadReactionSpriteCatalogV1.reactions.forEach { sprite ->
                ThreadQuickReactionGlyph(sprite.value, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * `components.channel.settings.panel` (and `.pinned-panel`): the #design channel details as the
 * web shows them (ChannelDetailsSurface): the identity, the members card (one person, one working
 * agent), Channel settings (Public with Make private), Your preferences (Notifications, the sidebar
 * section, Channels or Pinned for [pinned], Collapse long messages off), then an owner's actions:
 * Archive, Leave, Stop all agents, Delete.
 */
@Composable
internal fun ChannelSettingsPanelVisualScreen(pinned: Boolean = false) {
    val panel = channelSettingsPanelVisualPanel(pinned)
    val channel = panel.channel ?: return
    // The web case's server runs the unified notification API, so Your preferences shows the
    // Notifications select (inherited "All new messages"), not the older servers' Mute switch. The
    // app learns that from `GET notification-levels`, which a fixture never sends: say it here, and
    // put the answer back when the page leaves so no later case inherits it.
    val levelsServerId = panel.notificationLevelTarget?.serverId
    if (levelsServerId != null) {
        DisposableEffect(levelsServerId) {
            val before = build.raft.app.data.RaftNotificationCapability.isSupported(levelsServerId)
            build.raft.app.data.RaftNotificationCapability.recordServer(levelsServerId, supported = true)
            onDispose { build.raft.app.data.RaftNotificationCapability.restoreServer(levelsServerId, before) }
        }
    }
    ChannelSettingsScaffold(refreshing = false, onBack = {}) {
        ChannelSettingsContent(
            model =
                ChannelSettingsUiModel(
                    channel = channel,
                    memberSlice = panel.members ?: SharedServerChannelMembersSlice(channelId = channel.channelId.value),
                    pinned = pinned,
                    muted = false,
                    permissions = panel.permissions ?: ChannelSettingsPermissions(),
                    writeActionsEnabled = true,
                    canStopAllAgents = panel.canStopAllAgents,
                    section = panel.section,
                    serverName = "Botiverse",
                ),
            longMessageCollapseSetting =
                panel.longMessageCollapse
                    ?: RaftLongMessageCollapseConversationSetting(
                        preference = build.raft.app.data.RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                        available = true,
                        onSetEnabled = {},
                    ),
            agentStatusResolver = panel.agentStatusResolver,
            notificationLevelTarget = panel.notificationLevelTarget,
            refreshNotificationLevels = panel.refreshNotificationLevels,
            callbacks = ChannelSettingsCallbacks(),
        )
    }
}

/** The fixture panel of [ChannelSettingsPanelVisualScreen] (also the drawer screenshot tests). */
internal fun channelSettingsPanelVisualPanel(pinned: Boolean = false, serverId: String = "visual-server"): ThreadChannelSettingsPanel {
    val channel =
        HomeChannelRow(
            threadId = ThreadId("channel-design"),
            channelId = ChannelId("channel-design"),
            channelType = "channel",
            name = "design",
            subtitle = "Product and UI decisions",
            memberCount = 2,
            channelCapabilities =
                ChannelCapabilities(
                    editChannelMetadata = true,
                    changeChannelVisibility = true,
                    archiveChannels = true,
                    deleteChannels = true,
                    federateChannels = true,
                ),
        )
    return ThreadChannelSettingsPanel(
        channel = channel,
        permissions = channelSettingsPermissions(channel, canManage = true),
        canStopAllAgents = true,
        members =
            SharedServerChannelMembersSlice(
                serverId = serverId,
                channelId = "channel-design",
                members =
                    listOf(
                        MemberProfile(id = MemberId("visual-user"), displayName = "artin", handle = "artin", kind = MemberKind.Human),
                        MemberProfile(
                            id = MemberId("agent-cindy"),
                            displayName = "Cindy",
                            handle = "Cindy",
                            kind = MemberKind.Agent,
                            avatar = build.raft.app.model.AvatarReference.Pixel("robot"),
                        ),
                    ),
                status = SharedServerMembersLoadStatus.Loaded,
            ),
        // The web strip carries the agent's live light: Cindy is working.
        agentStatusResolver = { memberId ->
            flowOf(
                memberId?.takeIf { it == "agent-cindy" }?.let {
                    RaftAgentStatusIndicatorState(
                        status = AgentStatus.Working,
                        color = RaftColor.BrandYellow,
                        label = "Working",
                        external = false
                    )
                }
            )
        },
        section = ChannelSectionPlacement(customSections = emptyList(), pinned = pinned, placementSectionId = null),
        notificationLevelTarget =
            RaftNotificationLevelTarget(
                serverId = serverId,
                scope = build.raft.app.data.RaftNotificationLevelScope.Channel,
                id = "channel-design",
                muteSupported = true,
            ),
        refreshNotificationLevels = false,
        longMessageCollapse =
            RaftLongMessageCollapseConversationSetting(
                preference = build.raft.app.data.RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                available = true,
                onSetEnabled = {},
            ),
    )
}

/**
 * The DM settings drawer for Friday (a DM with an App bot, so it can be muted): Move to section
 * on Pinned ([pinned]) or at home in Direct Messages, with my two sections to pick from, then
 * Mute (on) and Collapse long messages. [mutable] false is a DM with a person, which the server
 * does not let you mute: no Mute row (web `canToggleActivityMute`).
 */
@Composable
internal fun DirectMessageSettingsPanelVisualScreen(
    pinned: Boolean = true,
    mutable: Boolean = true,
    // A phone shows the page, a wide window the drawer, as the conversation opens them.
    asPage: Boolean = rememberThreadSettingsOpensAsPage(),
) {
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        ThreadDirectMessageSettingsDrawer(
            name = if (mutable) "Friday" else "Alice Chen",
            activityMuteState =
                ThreadActivityMuteUiState(
                    channelId = "dm-visual-friday",
                    muted = true,
                    supported = true,
                    muteFromSeq = 42
                ).takeIf { mutable },
            section =
                ChannelSectionPlacement(
                    customSections =
                        listOf(
                            build.raft.app.data.SettingsServerSidebarCustomSection(id = "section-launch", name = "Launch", emoji = "\uD83D\uDE80"),
                            build.raft.app.data.SettingsServerSidebarCustomSection(id = "section-ops", name = "Ops"),
                        ),
                    pinned = pinned,
                    placementSectionId = null,
                    directMessage = true,
                ),
            sectionEnabled = true,
            longMessageCollapseSetting =
                RaftLongMessageCollapseConversationSetting(
                    preference = build.raft.app.data.RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                    available = true,
                    onSetEnabled = {},
                ),
            onDismiss = {},
            onToggleActivityMute = {},
            onMoveSection = {},
            onCreateSection = { _, _ -> },
            asPage = asPage,
        )
    }
}

/**
 * `components.channel.members.add-panel`: the members page in Add Member (web ChannelMembers
 * `presentation="page"` add view) for #design. The candidates are what the React capture
 * lists (see [channelPanelVisualCandidates]), nothing is selected yet, and the owner may
 * create agents.
 */
@Composable
internal fun ChannelAddMemberPanelVisualScreen(
    viewerRole: String = "owner",
    serverName: String = "Visual Server",
    nowEpochMillis: Long = 1_781_872_200_000L,
) {
    val candidates = remember { channelPanelVisualCandidates() }
    // fixtureData.json: every candidate agent is `active` / `working`.
    val workingState =
        remember {
            raftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = AgentStatus.Working.presence,
                external = false,
            )
        }
    val agentStatusResolver: RaftAgentStatusResolver =
        remember(workingState) {
            { memberId -> flowOf(workingState.takeIf { memberId?.startsWith("agent-") == true }) }
        }
    // Web VisualTestingCases seeds owner role on Visual Server / #design (`useExternalInviteAccess`),
    // so Add Member renders the bottom "Invite people outside Visual Server" row (#wg-mobile-sync:#207).
    val externalInviteAccessOn =
        externalInviteAccess(
            serverRole = viewerRole,
            channel =
                ExternalInviteChannel(
                    type = "channel",
                    name = "design",
                    archived = false,
                    jointRole = null,
                    hasExternalRelation = false,
                ),
        )
    ThreadMembersPageContent(
        members = emptyList(),
        filteredMembers = emptyList(),
        filteredExternal = emptyList(),
        externalMemberCount = 0,
        candidates = candidates,
        rosterQuery = TextFieldValue(""),
        onQueryChange = {},
        agentsExpanded = true,
        humansExpanded = true,
        externalExpanded = true,
        onAgentsExpandedChange = {},
        onHumansExpandedChange = {},
        onExternalExpandedChange = {},
        addDialogVisible = true,
        onAddVisibleChange = {},
        addMemberStatus = "idle",
        addMemberErrorCode = null,
        removalGate = ThreadMemberRemovalGateState(),
        membersFailed = false,
        membersLoading = false,
        refreshing = false,
        canAddMembers = true,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
        onBack = {},
        onRefresh = {},
        onOpenMember = {},
        onRemoveMember = {},
        onSubmit = {},
        canCreateAgents = true,
        channelName = "design",
        externalInvites =
            ExternalInvitesSection(
                state = ExternalInvitesState(),
                access = externalInviteAccessOn,
                management = externalInviteAccessOn,
                serverName = serverName,
                nowEpochMillis = nowEpochMillis,
            ),
    )
}

// Add-member candidates: web components.channel.members.add-panel lists the server's
// fixtureData.json agents that are not in the #design roster (Cindy is), every one
// wearing `pixel:robot`, then the humans not in it: artin (Owner) and Designer.
private fun channelPanelVisualCandidates(): List<RaftThreadMemberItem> =
    listOf(
        RaftThreadMemberItem(
            id = "agent-product-ux",
            displayName = "Product UX Designer",
            handle = "Product-UX-Designer",
            roleLabel = "",
            description = "Turns product feedback, screenshots, and release notes into actionable UX specs.",
            avatarUrl = "pixel:robot",
            avatarLabel = "PU",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "agent-computer-offline",
            displayName = "Computer Offline Probe",
            handle = "Computer-Offline-Probe",
            roleLabel = "",
            description = "Bound to a computer the store knows and reports offline: web shows a gray dot and Offline.",
            avatarUrl = "pixel:robot",
            avatarLabel = "CO",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "agent-computer-missing",
            displayName = "Computer Missing Probe",
            handle = "Computer-Missing-Probe",
            roleLabel = "",
            description = "machineId points at a computer the store has never seen: web shows No computer assigned, no dot.",
            avatarUrl = "pixel:robot",
            avatarLabel = "CM",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "agent-no-computer",
            displayName = "No Computer Probe",
            handle = "No-Computer-Probe",
            roleLabel = "",
            description = "No machineId at all: web shows No computer assigned, no dot.",
            avatarUrl = "pixel:robot",
            avatarLabel = "NC",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "agent-long-machine",
            displayName = "Long Machine Name Probe",
            handle = "Long-Machine-Name-Probe",
            roleLabel = "",
            description = "Bound to a computer whose name cannot fit one 390dp line, so wrap vs truncate is observable.",
            avatarUrl = "pixel:robot",
            avatarLabel = "LM",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "agent-daemon-only",
            displayName = "Daemon Only Probe",
            handle = "Daemon-Only-Probe",
            roleLabel = "",
            description = "Bound to a bare daemon (not a managed Computer): web shows Connected · daemon vX, never a computer version.",
            avatarUrl = "pixel:robot",
            avatarLabel = "DO",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "agent-no-membership",
            displayName = "No Membership Probe",
            handle = "No-Membership-Probe",
            roleLabel = "",
            description = "Has no server membership row, so the wire omits serverRole (artin 2026-09-03 16:54: hide the role chip).",
            avatarUrl = "pixel:robot",
            avatarLabel = "NM",
            kind = "agent"
        ),
        RaftThreadMemberItem(
            id = "visual-user",
            displayName = "artin",
            handle = "artin",
            roleLabel = "",
            description = "Owner",
            avatarLabel = "AR",
            kind = "human"
        ),
        RaftThreadMemberItem(
            id = "visual-human-designer",
            displayName = "Designer",
            handle = "designer",
            roleLabel = "",
            description = "Product design",
            avatarLabel = "DE",
            kind = "human"
        )
    )
