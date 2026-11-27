package build.raft.app.members

import build.raft.app.resources.*
import build.raft.app.runtimeLabelWithStatus
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.app.shell.AppShellMembersListState
import build.raft.app.shell.AppShellScrollToTopRequestFence
import build.raft.app.shell.raftUnderAppShellTopBar
import build.raft.app.shell.toAppShellLazyListPosition
import build.raft.app.submit
import build.raft.app.thread.icon
import build.raft.app.traceId
import build.raft.app.withoutActivityDetail
import build.raft.app.withoutClearedLoginRequiredAttention
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import build.raft.app.components.raftCardSurface
import build.raft.designsystem.components.RaftButton
import build.raft.app.data.MembersAgentsGrouping
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftAgentCreateEntry
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.AGENT_CREATE_NAME_TAKEN
import build.raft.app.data.createAgentErrorText
import build.raft.app.data.isCreateAgentLimitError
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.hidesHumanDirectoryForViewer
import build.raft.app.raftArgbHex
import build.raft.app.raftAvatarCacheSubject
import build.raft.app.raftAvatarKind
import build.raft.app.raftAvatarName
import build.raft.app.rememberAgentAttentionParts
import build.raft.app.rememberAgentDeliveryHoldView
import build.raft.app.rememberAgentRuntimeRetiredState
import build.raft.app.rememberAgentRuntimeSignInResolution
import build.raft.app.rememberRaftAgentStatusIndicatorState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftFitToolbar
import build.raft.designsystem.components.RaftFitToolbarAction
import build.raft.designsystem.components.RaftFitToolbarChoice
import build.raft.designsystem.components.RaftFitToolbarOption
import build.raft.designsystem.components.RaftFitToolbarPrimary
import build.raft.designsystem.components.RaftFitToolbarScope
import build.raft.designsystem.components.RaftFitToolbarSearch
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import build.raft.app.components.RaftPullToRefreshBox
import build.raft.app.components.raftHeaderRefreshIndicatorVisible
import build.raft.app.currentRaftTimeContextProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.AgentRuntimeSignInResolution
import build.raft.app.EmptyRaftAgentStatusResolver
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAppAvatar
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAvatarWithStatus
import build.raft.app.RaftBackHandler
import build.raft.app.RaftColor
import build.raft.app.RaftCreateExternalAgentDialog
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMainTabPageHeader
import build.raft.app.RaftTraceIdNote
import build.raft.app.RaftType
import build.raft.app.SharedCreateAgentDialog
import build.raft.app.SharedCreateAgentDialogSubmit
import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.animateScrollToTopExpressive
import build.raft.app.busyThroughWorkers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import build.raft.app.data.map
import build.raft.app.dotColor
import build.raft.app.effectiveHoldingReadiness
import build.raft.app.fill
import build.raft.app.isBusyThroughWorkers
import build.raft.app.listedForMembersReader
import build.raft.app.localized
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.localizedText
import build.raft.app.members.localized
import build.raft.app.membersShowGuests
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.selected

@Composable
internal fun MembersRouteContent(
    payload: RaftMembersPayload,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    onAddHuman: () -> Unit,
    onOpenMemberDetail: (MemberProfile) -> Unit,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    createAgent: MembersCreateAgentInputs = MembersCreateAgentInputs(),
    inviteHuman: MembersInviteHumanInputs = MembersInviteHumanInputs(),
    /**
     * Permission gate for showing the agents "+". Defaults to false (no create).
     * When true, the agents "+" is rendered. When false, the "+" is hidden.
     */
    canCreateAgents: Boolean = false,
    agentsApiClient: build.raft.app.data.RaftAgentsApiClient? = null,
    /**
     * Task #409: current session's user id, used as the persistence scope for
     * per-machine agent-group collapse (same `slock:sidebarCollapsed:<userId>`
     * key family as web). Null keeps the groups collapsible but unpersisted
     * (smoke/test surfaces).
     */
    currentUserId: String? = null,
    agentGroupCollapseStore: build.raft.app.data.MembersAgentMachineGroupCollapseStore? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    scrollToTopRequest: Int = 0,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    paneLayout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
    selectedIdentity: MemberDetailIdentity? = null,
    selectedLocalServerId: ServerId? = null,
    /** The rows' menus and Edit agent buttons (web MemberActions); null: a row only opens the profile. */
    rowActions: MembersRowActions? = null,
    onRefresh: () -> Unit = {}
) {
    MembersListContent(
        payload = payload,
        rowActions = rowActions,
        selectedIdentity = selectedIdentity,
        selectedLocalServerId = selectedLocalServerId,
        refreshing = refreshing,
        onBack = onBack,
        onRefresh = onRefresh,
        onAddHuman = onAddHuman,
        onOpenMember = onOpenMemberDetail,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        canCreateAgents = canCreateAgents,
        createAgent = createAgent,
        inviteHuman = inviteHuman,
        bottomContentPadding = bottomContentPadding,
        scrollToTopRequest = scrollToTopRequest,
        initialListState = initialListState,
        onListStateSnapshot = onListStateSnapshot,
        paneLayout = paneLayout,
        currentUserId = currentUserId,
        agentGroupCollapseStore = agentGroupCollapseStore
    )
}

@Composable
internal fun MembersListContent(
    payload: RaftMembersPayload,
    selectedIdentity: MemberDetailIdentity? = null,
    selectedLocalServerId: ServerId? = null,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    onAddHuman: () -> Unit,
    onOpenMember: (MemberProfile) -> Unit,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    canCreateAgents: Boolean = false,
    createAgent: MembersCreateAgentInputs = MembersCreateAgentInputs(),
    inviteHuman: MembersInviteHumanInputs = MembersInviteHumanInputs(),
    agentsApiClient: build.raft.app.data.RaftAgentsApiClient? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    scrollToTopRequest: Int = 0,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    paneLayout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
    onRefresh: () -> Unit = {},
    currentUserId: String? = null,
    agentGroupCollapseStore: build.raft.app.data.MembersAgentMachineGroupCollapseStore? = null,
    /** The tab the page opens on (screenshots); the reader's taps move it. */
    initialTab: MembersTab = MembersTab.All,
    /** The rows' menus and Edit agent buttons (web MemberActions); null: a row only opens the profile. */
    rowActions: MembersRowActions? = null,
) {
    val rowDialogs = remember { MembersRowDialogState() }
    val createAgentSubmit = createAgent.createAgentSubmit
    val createAgentMachines = createAgent.createAgentMachines
    val createAgentMachinesLoading = createAgent.createAgentMachinesLoading
    val createAgentMachinesErrorMessage = createAgent.createAgentMachinesErrorMessage
    val createAgentCurrentAgentCount = createAgent.createAgentCurrentAgentCount
    val createAgentMaxAgents = createAgent.createAgentMaxAgents
    val createAgentLoadMachines = createAgent.createAgentLoadMachines
    val createAgentRetryMachines = createAgent.createAgentRetryMachines
    val createAgentFormStore = createAgent.createAgentFormStore
    val createAgentServerId = createAgent.createAgentServerId
    val createAgentTakenAgentNames = createAgent.createAgentTakenAgentNames
    val inviteHumanDialog = inviteHuman.inviteHumanDialog
    val errorStrings = localizedErrorPresentationStrings()
    val displayTitle =
        canonicalMembersDisplayText(
            value = payload.title,
            canonicalDefault = "Members",
            localizedDefault = stringResource(Res.string.members_page_title)
        )
    val displayAgentsLabel =
        canonicalMembersDisplayText(
            value = payload.labels.agents,
            canonicalDefault = "AGENTS",
            localizedDefault = stringResource(Res.string.members_section_agents)
        )
    val displayHumansLabel =
        canonicalMembersDisplayText(
            value = payload.labels.humans,
            canonicalDefault = "HUMANS",
            localizedDefault = stringResource(Res.string.members_section_humans)
        )
    val groupedMembers = remember(payload.members) {
        payload.members.groupBy {
            when (it.kind) {
                MemberKind.Human -> payload.labels.humans
                MemberKind.Agent, MemberKind.System, MemberKind.External -> payload.labels.agents
            }
        }
    }
    // slock #13127: the tabs (All, Humans, Agents) and the search filter one population, A-Z by name.
    var membersTab by remember(payload.instanceId) { mutableStateOf(initialTab) }
    var membersQuery by remember(payload.instanceId) { mutableStateOf("") }
    val tabs = membersVisibleTabs(payload.showHumanDirectory)
    val tab = membersTab.takeIf { it in tabs } ?: MembersTab.All
    val allAgents = remember(groupedMembers) { membersByName(groupedMembers[payload.labels.agents].orEmpty()) }
    // Only owners and admins see the guests, among the Humans (web MembersPage `showGuests`).
    val allHumans =
        remember(groupedMembers, payload.showGuests) {
            membersByName(groupedMembers[payload.labels.humans].orEmpty().filter { it.listedForMembersReader(payload.showGuests) })
        }
    val agents = remember(allAgents, membersQuery) { allAgents.filter { membersMatchesSearch(it, membersQuery) } }
    // Web `layout.sidebar.noMachineGroupLabel`: external and cloud agents share this group.
    val noComputerGroupLabel = stringResource(Res.string.members_agent_group_no_computer)
    val humans = remember(allHumans, membersQuery) { allHumans.filter { membersMatchesSearch(it, membersQuery) } }
    // The computer an agent's runtime line names (web `machinesById.get(machineId)?.name`).
    fun agentMachineName(member: MemberProfile): String? =
        member.membersComputerId?.let { id -> createAgentMachines.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } }
            ?: member.membersComputerName
    val showAgents = tab != MembersTab.Humans
    val showHumans = payload.showHumanDirectory && tab != MembersTab.Agents
    // Web: the group headings are not toggles; the shell's snapshot keeps what it was given.
    val agentsExpanded = initialListState.agentsExpanded
    val humansExpanded = initialListState.humansExpanded
    val membersCurrentUserId = currentUserId?.trim().orEmpty()
    // slock #14068: one list of agents by default; By computer once the reader picks it (kept per user).
    var agentsGrouping by remember(membersCurrentUserId, agentGroupCollapseStore) {
        mutableStateOf(agentGroupCollapseStore?.agentsGrouping(membersCurrentUserId) ?: MembersAgentsGrouping.Default)
    }
    var activeCreateAgentMode by remember(payload.instanceId) { mutableStateOf<MembersCreateAgentMode?>(null) }
    var createAgentSubmitting by remember { mutableStateOf(false) }
    var createAgentError by remember { mutableStateOf<String?>(null) }
    // Runtime form v2: fields a rejected create pointed at (`/formValues/<field>`).
    var createAgentErrorFields by remember { mutableStateOf(emptySet<String>()) }
    // The last failure was the plan's agent limit: its banner offers "View your plan" (iOS).
    var createAgentErrorIsLimit by remember { mutableStateOf(false) }
    var activeInviteHuman by remember(payload.instanceId) { mutableStateOf(false) }
    val emptyResponseMessage = stringResource(Res.string.member_error_empty_response)
    val createAgentFailed = stringResource(Res.string.member_error_create_agent)
    val createAgentLimitReachedNeutral = stringResource(Res.string.agent_create_error_limit_reached_neutral)
    val createAgentForbidden = stringResource(Res.string.member_error_create_agent_forbidden)
    val createAgentNameTaken = stringResource(Res.string.agent_create_error_name_taken)
    val canInviteHumans = payload.canInviteHumans && inviteHumanDialog != null
    LaunchedEffect(canInviteHumans) {
        if (!canInviteHumans) {
            activeInviteHuman = false
        }
    }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialListState.listPosition.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialListState.listPosition.firstVisibleItemScrollOffset
        )
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) {
            listState.animateScrollToTopExpressive()
        }
    }
    val latestAgentsExpanded by rememberUpdatedState(agentsExpanded)
    val latestHumansExpanded by rememberUpdatedState(humansExpanded)
    val latestOnListStateSnapshot by rememberUpdatedState(onListStateSnapshot)
    DisposableEffect(listState) {
        onDispose {
            latestOnListStateSnapshot(
                AppShellMembersListState(
                    listPosition = listState.toAppShellLazyListPosition(),
                    agentsExpanded = latestAgentsExpanded,
                    humansExpanded = latestHumansExpanded
                )
            )
        }
    }
    var pullRefreshActive by remember(payload.instanceId) { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            pullRefreshActive = false
        }
    }
    val pullRefreshing = refreshing && pullRefreshActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RaftColor.SurfaceWhite)
    ) {
        // Under the wide top bar the Members list has no head (web Sidebar `hideTitle`, artin
        // task #8); a pushed Members page (with Back) keeps its own.
        val underTopBar = raftUnderAppShellTopBar() && onBack == null
        if (!underTopBar) {
            MembersPageHeader(
                title = displayTitle,
                refreshing = raftHeaderRefreshIndicatorVisible(refreshing, pullRefreshing),
                onBack = onBack
            )
        }
        // Web Members: no counts while the members load (the counts come with the members).
        val membersLoaded = payload.members.isNotEmpty()
        val allCount = (if (payload.showHumanDirectory) allHumans.size else 0) + allAgents.size
        if (underTopBar) {
            // Wide: the page's head, "Members" and its counts in the machine type (web PanelHeader + MembersCounts).
            RaftDesignSystemTheme {
                MembersWideHead(
                    title = displayTitle,
                    agents = allAgents,
                    memberCount = allCount.takeIf { membersLoaded },
                    agentStatusResolver = agentStatusResolver,
                )
            }
        }
        RaftDesignSystemTheme {
            MembersToolbar(
                tabs = tabs,
                tab = tab,
                counts =
                    if (membersLoaded) {
                        mapOf(MembersTab.All to allCount, MembersTab.Humans to allHumans.size, MembersTab.Agents to allAgents.size)
                    } else {
                        emptyMap()
                    },
                onTab = { membersTab = it },
                query = membersQuery,
                onQuery = { membersQuery = it },
                // The Group menu is the Agents tab's only (web `tab === "agents"`).
                grouping = agentsGrouping.takeIf { tab == MembersTab.Agents },
                onGrouping = { grouping ->
                    agentsGrouping = grouping
                    agentGroupCollapseStore?.setAgentsGrouping(membersCurrentUserId, grouping)
                },
                // Web: Invite human and Create agent end the toolbar on every tab, as the reader may.
                onInviteHuman = if (canInviteHumans) ({ activeInviteHuman = true }) else null,
                onCreateAgent =
                    if (canCreateAgents) {
                        { mode: MembersCreateAgentMode ->
                            if (createAgentSubmit != null) {
                                RaftProductEvents.agentCreateOpened(RaftAgentCreateEntry.MembersPage)
                                activeCreateAgentMode = mode
                                if (mode.requiresComputerSelection()) createAgentLoadMachines()
                            }
                        }
                    } else {
                        null
                    },
            )
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val lazyMaxWidth = maxWidth
        RaftPullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = {
                pullRefreshActive = true
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Inside the refresh layer: the design-system theme for the sidebar rows and headers.
            RaftDesignSystemTheme {
            LazyColumn(
                state = listState,
                // React parity: the web members view pads the scroll container itself (Sidebar
                // sidebarScrollClassName `px-2 pt-1`); the rows and headers (RaftSidebarItem /
                // RaftSidebarSectionHeader) carry their own `px-2`, 16dp in all (task #140).
                modifier = Modifier
                    .widthIn(max = membersListContentMaxWidth(lazyMaxWidth, paneLayout))
                    .fillMaxSize()
                    .padding(horizontal = MembersListContainerInset),
            ) {
                // `pt-2` with no head under the top bar.
                item { Spacer(Modifier.height(if (underTopBar) RaftAppSpacing.Sm else RaftAppSpacing.Xs)) }
                // The All tab heads each kind (Humans first, as on web) with a group heading, not a
                // toggle (web MembersPage); a kind's own tab lists it bare.
                if (showHumans && humans.isNotEmpty()) {
                    if (tab == MembersTab.All) {
                        item("heading_humans") { MembersListGroupHeading(label = displayHumansLabel, count = humans.size) }
                    }
                    items(humans.size, key = { "human_${humans[it].id.value}" }) { index ->
                        val member = humans[index]
                        MembersListActionRow(
                            member = member,
                            rowActions = rowActions,
                            rowDialogs = rowDialogs,
                            machines = createAgentMachines,
                            selected = member.matchesMemberSelection(selectedIdentity, selectedLocalServerId),
                            isSelf = membersHumanIsSelf(member, membersCurrentUserId),
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
                            onClick = { onOpenMember(member) }
                        )
                    }
                }
                if (showAgents && agents.isNotEmpty()) {
                    if (tab == MembersTab.All) {
                        item("heading_agents") { MembersListGroupHeading(label = displayAgentsLabel, count = agents.size) }
                    }
                    // Grouping is the Agents tab's choice; the All tab lists them by name.
                    if ((tab != MembersTab.Agents || agentsGrouping == MembersAgentsGrouping.None)) {
                        items(agents.size, key = { "agent_${agents[it].id.value}" }) { index ->
                            val member = agents[index]
                            MembersListActionRow(
                                member = member,
                                rowActions = rowActions,
                                rowDialogs = rowDialogs,
                                machines = createAgentMachines,
                                selected = member.matchesMemberSelection(selectedIdentity, selectedLocalServerId),
                                // Web MemberPhoneRow `runtimeLine={tab === "agents"}`, with the computer.
                                runtimeLine = tab == MembersTab.Agents,
                                machineName = if (tab == MembersTab.Agents) agentMachineName(member) else null,
                                agentStatusResolver = agentStatusResolver,
                                memberAvatarResolver = memberAvatarResolver,
                                onClick = { onOpenMember(member) }
                            )
                        }
                    }
                    if (tab == MembersTab.Agents && agentsGrouping == MembersAgentsGrouping.Computer) {
                        groupedAgentMembers(
                            agents,
                            // Web groupAgents: the reader's own computers first, then A-Z, no computer last.
                            machineOrder = membersAgentGroupMachineOrder(createAgentMachines),
                            noComputerLabel = noComputerGroupLabel
                        ) { machineId ->
                            createAgentMachines.firstOrNull { it.id == machineId }?.name
                        }.forEach { group ->
                            // Web: a heading over each computer's agents, not a toggle.
                            item("group_${group.key}") { MembersListGroupHeading(label = group.label, count = group.members.size) }
                            items(group.members.size, key = { "agent_${group.members[it].id.value}" }) { index ->
                                val member = group.members[index]
                                MembersListActionRow(
                                    member = member,
                                    rowActions = rowActions,
                                    rowDialogs = rowDialogs,
                                    machines = createAgentMachines,
                                    selected = member.matchesMemberSelection(selectedIdentity, selectedLocalServerId),
                                    // By computer: the group's heading names the computer.
                                    runtimeLine = true,
                                    agentStatusResolver = agentStatusResolver,
                                    memberAvatarResolver = memberAvatarResolver,
                                    onClick = { onOpenMember(member) }
                                )
                            }
                        }
                    }
                }
                val nothingShown = (!showHumans || humans.isEmpty()) && (!showAgents || agents.isEmpty())
                if (nothingShown && (membersQuery.isNotBlank() || membersLoaded)) {
                    item {
                        RaftText(
                            // Web `members.page.noMatches`, or `members.page.empty` with no search.
                            if (membersQuery.isNotBlank()) {
                                stringResource(Res.string.members_no_matches, membersQuery.trim())
                            } else {
                                stringResource(Res.string.members_page_empty)
                            },
                            Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = RaftAppSpacing.Sm, vertical = RaftAppSpacing.Md),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundMuted,
                        )
                    }
                }
                item { Spacer(Modifier.height(bottomContentPadding)) }
            }
            }
        }
        }
    }
    val submit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = createAgentSubmit
    val activeCreateAgent = activeCreateAgentMode != null
    val externalCreateHost = createAgent.createExternalAgent
    if (activeCreateAgentMode == MembersCreateAgentMode.External && externalCreateHost != null) {
        // The external agent's own two-step dialog (web MembersAddAgentDialogs, botiverse/slock #14627).
        RaftCreateExternalAgentDialog(host = externalCreateHost, onClose = { activeCreateAgentMode = null })
    } else if (activeCreateAgent && submit != null) {
        val scope = rememberCoroutineScope()
        SharedCreateAgentDialog(
            external = activeCreateAgentMode == MembersCreateAgentMode.External,
            offersHosting = true,
            submitting = createAgentSubmitting,
            errorMessage = createAgentError,
            errorFieldKeys = createAgentErrorFields,
            onViewPlan = createAgent.createAgentViewPlan,
            errorOffersViewPlan = createAgentErrorIsLimit,
            onUpgrade = createAgent.createAgentUpgrade,
            onUpgradeWithAgentName = createAgent.createAgentUpgradeWithName,
            onAddComputer = createAgent.createAgentAddComputer,
            machines = createAgentMachines,
            machinesLoading = createAgentMachinesLoading,
            machinesErrorMessage = createAgentMachinesErrorMessage,
            onMachinesRetry = createAgentRetryMachines,
            createAgentFormStore = createAgentFormStore,
            createAgentServerId = createAgentServerId,
            takenAgentNames = createAgentTakenAgentNames,
            currentAgentCount = createAgentCurrentAgentCount,
            maxAgents = createAgentMaxAgents,
            limitPlanLabel = createAgent.createAgentLimitPlanLabel,
            seatLimit = createAgent.createAgentSeatLimit,
            onDismiss = {
                if (!createAgentSubmitting) {
                    activeCreateAgentMode = null
                    createAgentError = null
                }
            },
            onSubmit = { request ->
                createAgentSubmitting = true
                createAgentError = null
                createAgentErrorFields = emptySet()
                createAgentErrorIsLimit = false
                scope.launch {
                    val result = submit(request)
                    createAgentErrorIsLimit = (result as? DataResult.Error)?.error?.let(::isCreateAgentLimitError) == true
                    createAgentSubmitting = false
                    createAgentErrorFields = (result as? DataResult.Error)?.error?.formFieldKeys.orEmpty().toSet()
                    when (result) {
                        is DataResult.Content -> {
                            activeCreateAgentMode = null
                            createAgentError = null
                        }
                        is DataResult.Empty -> {
                            createAgentError = emptyResponseMessage
                        }
                        is DataResult.Error -> {
                            createAgentError =
                                when (result.error.code) {
                                    AGENT_CREATE_NAME_TAKEN ->
                                        createAgentNameTaken
                                    else ->
                                        createAgentErrorText(
                                            result.error,
                                            createAgentFailed,
                                            limitReachedNeutral = createAgentLimitReachedNeutral,
                                            offersUpgrade = settingsBillingOffersUpgrade(),
                                        )
                                }
                        }
                        is DataResult.NoPermission -> {
                            createAgentError = createAgentForbidden
                        }
                    }
                }
            }
        )
    }
    if (activeInviteHuman && canInviteHumans) {
        inviteHumanDialog?.invoke { activeInviteHuman = false }
    }
    if (rowActions != null) {
        MembersRowActionDialogs(rowDialogs, rowActions)
    }
}

/**
 * Task #409: a group's collapsed state is the user's tap this page session if
 * they made one, otherwise the persisted value under `userId`. Resolving per
 * group (rather than snapshotting the stored set once) keeps groups that
 * arrive after the first read — the standalone members page fills its list
 * asynchronously — from silently rendering expanded.
 */
/**
 * The collapsed value a machine-group tap should write: the opposite of the
 * currently resolved collapsed state. Kept as a named pure function because a
 * previous implementation wrote the non-inverted state, which made taps a
 * no-op (task #409 regression).
 */
internal fun resolveAgentGroupCollapseToggle(currentlyCollapsed: Boolean): Boolean =
    !currentlyCollapsed

internal fun resolveAgentGroupCollapsed(
    groupKey: String,
    overrides: Map<String, Boolean>,
    userId: String,
    store: build.raft.app.data.MembersAgentMachineGroupCollapseStore?
): Boolean {
    overrides[groupKey]?.let { return it }
    if (userId.isBlank() || store == null) return false
    return store.isCollapsed(userId, groupKey)
}

@Composable
internal fun MembersPageHeader(
    title: String,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    RaftMainTabPageHeader(
        title = title,
        subtitle = null,
        refreshing = refreshing,
        onBack = onBack
    )
}

internal const val MembersAgentsGroupActionTestTag = "members-agents-group"

/**
 * The standalone Members route's payload (`compose_members`): the roster [members] it lists, the
 * agent panel's tab order, and what [server] (the reader's role there) lets it show. A server not
 * known yet shows the people but not the guests (fail closed).
 */
internal fun RaftMembersPayload.forMembersRoute(
    members: List<MemberProfile>,
    agentPanelTabOrder: List<String>,
    server: ServerSummary?,
): RaftMembersPayload =
    copy(
        // botiverse/slock #11295: the directory keeps an App Bot removed from this server
        // (`inMemberLists: false`) so its old messages resolve; the list leaves it out.
        members = members.filter { it.isListedInMembers() },
        agentPanelTabOrder = agentPanelTabOrder,
        showHumanDirectory = server?.hidesHumanDirectoryForViewer() != true,
        showGuests = membersShowGuests(server?.role),
    )

/** The Members page's tabs (web `MembersTab`, slock #13127): guests have no tab of their own, they are Humans. */
internal enum class MembersTab { All, Humans, Agents }

/** Web `visibleTabs`: Humans goes where the server hides its people from this reader. */
internal fun membersVisibleTabs(showHumans: Boolean): List<MembersTab> =
    if (showHumans) listOf(MembersTab.All, MembersTab.Humans, MembersTab.Agents) else listOf(MembersTab.All, MembersTab.Agents)

/** Web `matchesSearch`: the shown name or the handle, case-insensitively. */
internal fun membersMatchesSearch(member: MemberProfile, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return member.displayName.ifBlank { member.handle }.lowercase().contains(q) || member.handle.lowercase().contains(q)
}

/** Web `byName`: every tab and every group reads by name, A-Z, then by handle. */
internal fun membersByName(members: List<MemberProfile>): List<MemberProfile> =
    members.sortedWith(compareBy({ it.displayName.ifBlank { it.handle }.lowercase() }, { it.handle.lowercase() }))

internal const val MembersSearchTestTag = "members-search"

internal const val MembersScopeTestTag = "members-tabs"

internal const val MembersInviteHumanTestTag = "members-invite-human"

internal const val MembersCreateAgentTestTag = "members-create-agent"

internal const val MembersCreateAgentMenuTestTag = "members-create-agent-more"

/**
 * The toolbar over the list (web Members toolbar, slock #14149; raft-design members.md), one line
 * on every width: the tabs with their counts, the search filling the line, the Agents tab's Group
 * menu ([grouping] non-null), Invite human, ⋯ More while it holds anything, and Create agent, a
 * split button whose ▾ offers Connect an external agent. As the line narrows it gives way as
 * [RaftFitToolbar] does: the Group menu shows its icon, Invite human its icon, Create agent
 * becomes +, the tabs become a Select, the Group menu and Invite human fold into More, and last
 * an empty search becomes its button. The web's Take over a local session (the desktop app's
 * hand-off) has no counterpart in the app.
 */
@Composable
private fun MembersToolbar(
    tabs: List<MembersTab>,
    tab: MembersTab,
    counts: Map<MembersTab, Int>,
    onTab: (MembersTab) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    grouping: MembersAgentsGrouping?,
    onGrouping: (MembersAgentsGrouping) -> Unit,
    onInviteHuman: (() -> Unit)?,
    onCreateAgent: ((MembersCreateAgentMode) -> Unit)?,
) {
    val labels =
        mapOf(
            MembersTab.All to stringResource(Res.string.members_tab_all),
            MembersTab.Humans to stringResource(Res.string.members_section_humans),
            MembersTab.Agents to stringResource(Res.string.members_section_agents),
        )
    val groupingLabels =
        mapOf(
            MembersAgentsGrouping.None to stringResource(Res.string.members_group_none),
            MembersAgentsGrouping.Computer to stringResource(Res.string.members_group_by_computer),
        )
    val groupingLabel = groupingLabels.getValue(grouping ?: MembersAgentsGrouping.Default)
    val groupingName = stringResource(Res.string.members_group_trigger_label, groupingLabel)
    val groupHeading = stringResource(Res.string.list_toolbar_group)
    val searchLabel = stringResource(Res.string.members_search_label)
    val inviteLabel = stringResource(Res.string.member_action_invite_human)
    val createLabel = stringResource(Res.string.members_create_agent)
    val createMoreLabel = stringResource(Res.string.members_create_agent_more)
    val externalLabel = stringResource(Res.string.members_create_agent_external)
    // A search given way to a button, opened over the tabs: system Back closes it (AGENTS.md).
    var searchOpen by remember { mutableStateOf(false) }
    var searchOverTabs by remember { mutableStateOf(false) }
    // As Esc: text first, then the search itself. Only while it covers the tabs, so Back is never
    // swallowed by an inline search (a wide toolbar).
    RaftBackHandler(enabled = searchOverTabs, label = "members_toolbar_search") {
        if (query.isNotEmpty()) onQuery("") else searchOpen = false
    }
    RaftFitToolbar(
        scope =
            RaftFitToolbarScope(
                options = tabs.map { RaftFitToolbarOption(key = it.name, label = labels.getValue(it), count = counts[it]) },
                selectedKey = tab.name,
                onSelect = { key -> MembersTab.entries.firstOrNull { it.name == key }?.let(onTab) },
                label = stringResource(Res.string.members_scope_label),
                testTag = MembersScopeTestTag,
            ),
        search =
            RaftFitToolbarSearch(
                value = query,
                onValueChange = onQuery,
                label = searchLabel,
                clearLabel = stringResource(Res.string.list_toolbar_clear_search),
                testTag = MembersSearchTestTag,
                open = searchOpen,
                onOpenChange = { searchOpen = it },
                onShownOverScopeChange = { searchOverTabs = it },
            ),
        display =
            if (grouping == null) {
                emptyList()
            } else {
                listOf(
                    RaftFitToolbarChoice(
                        text = groupingLabel,
                        icon = { MembersToolbarIcon(RaftLucideIconName.Rows3) },
                        options = MembersAgentsGrouping.entries.map { RaftFitToolbarOption(key = it.name, label = groupingLabels.getValue(it)) },
                        selectedKey = grouping.name,
                        onSelect = { key -> MembersAgentsGrouping.entries.firstOrNull { it.name == key }?.let(onGrouping) },
                        heading = groupHeading,
                        contentDescription = groupingName,
                        // Filled `info-soft` while off its default, as an applied filter.
                        applied = grouping != MembersAgentsGrouping.Default,
                        testTag = MembersAgentsGroupActionTestTag,
                    ),
                )
            },
        secondary =
            if (onInviteHuman == null) {
                emptyList()
            } else {
                listOf(
                    RaftFitToolbarAction(
                        text = inviteLabel,
                        icon = { MembersToolbarIcon(RaftLucideIconName.UserPlus) },
                        onClick = onInviteHuman,
                        testTag = MembersInviteHumanTestTag,
                    ),
                )
            },
        primary =
            onCreateAgent?.let { create ->
                RaftFitToolbarPrimary(
                    text = createLabel,
                    onClick = { create(MembersCreateAgentMode.Managed) },
                    icon = { MembersToolbarIcon(RaftLucideIconName.Bot) },
                    menuLabel = createMoreLabel,
                    menu = { dismiss ->
                        RaftMenuItem(
                            text = externalLabel,
                            leadingIcon = { MembersMenuIcon(RaftLucideIconName.Plug) },
                            onClick = {
                                dismiss()
                                create(MembersCreateAgentMode.External)
                            },
                        )
                    },
                    testTag = MembersCreateAgentTestTag,
                    menuTestTag = MembersCreateAgentMenuTestTag,
                )
            },
        moreLabel = stringResource(Res.string.list_toolbar_more),
        horizontalPadding = MembersListContainerInset + RaftAppSpacing.Sm,
    )
}

/** A toolbar icon filling the box its control gives it, in the control's ink. */
@Composable
private fun MembersToolbarIcon(name: RaftLucideIconName) {
    RaftLucideIcon(name, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current))
}

/**
 * The page's head under the wide top bar (web Members `PanelHeader` with `MembersCounts`): the
 * title, then "2 agents working · 17 members" in the machine type; no counts while the members
 * load ([memberCount] null).
 */
@Composable
private fun MembersWideHead(
    title: String,
    agents: List<MemberProfile>,
    memberCount: Int?,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val colors = RaftTheme.colors
    val working = rememberMembersWorkingAgentCount(agents, agentStatusResolver)
    val counts = membersCountsText(working = working, members = memberCount ?: 0)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(RaftTheme.controls.headHeight - RaftStroke.Hairline)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = MembersListContainerInset + RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = RaftTheme.typography.headline,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (memberCount != null) {
                Spacer(Modifier.width(RaftAppSpacing.Sm))
                RaftText(
                    text = counts,
                    modifier = Modifier.weight(1f, fill = false).testTag(MembersCountsTestTag),
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}

internal const val MembersCountsTestTag = "members-counts"

/** Web `members.page.counts`: "1 agent working · 17 members". */
@Composable
private fun membersCountsText(working: Int, members: Int): String {
    val workingText =
        if (working == 1) {
            stringResource(Res.string.members_counts_working_one, working)
        } else {
            stringResource(Res.string.members_counts_working_other, working)
        }
    val membersText =
        if (members == 1) {
            stringResource(Res.string.members_counts_members_one, members)
        } else {
            stringResource(Res.string.members_counts_members_other, members)
        }
    return "$workingText · $membersText"
}

/**
 * Whether [state] counts toward the Members page "N agents working" header (web
 * `selectWorkingAgentCount`, botiverse/slock #16819): thinking, working, or online with
 * running Workers (by the busy-light rule, excluding holds, paused, blocked, external).
 */
internal fun isMembersWorkingCountAgent(
    state: RaftAgentStatusIndicatorState?,
    nowMs: Long = build.raft.app.data.defaultNowMillis(),
): Boolean =
    state != null &&
        !state.external &&
        state.blocked == null &&
        !state.paused &&
        (
            state.status == AgentStatus.Working ||
                state.status == AgentStatus.Thinking ||
                state.isBusyThroughWorkers(nowMs = nowMs)
        )

/**
 * Soonest future `ProviderLimit` hold expiry among online agents with running Workers, so the
 * Members working count wakes up when the hold clears without waiting for a new presence frame.
 */
internal fun nextMembersWorkingCountHoldExpiryMs(
    states: Array<RaftAgentStatusIndicatorState?>,
    nowMs: Long,
): Long? =
    states.mapNotNull { state ->
        if (state == null || state.status != AgentStatus.Online) return@mapNotNull null
        if (build.raft.app.data.normalizeAgentRunningWorkers(state.runningWorkers) == null) return@mapNotNull null
        val hold = state.deliveryHold ?: return@mapNotNull null
        if (hold.reason != build.raft.app.model.AgentDeliveryHoldReason.ProviderLimit) return@mapNotNull null
        hold.untilMs?.takeIf { it > nowMs }
    }.minOrNull()

/** How many of [agents] are busy — working, thinking, or through their Workers (web `selectWorkingAgentCount`), live. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberMembersWorkingAgentCount(agents: List<MemberProfile>, agentStatusResolver: RaftAgentStatusResolver): Int {
    val flow =
        remember(agents, agentStatusResolver) {
            val managedAgents = agents.filter { it.managedByAppInstallId.isNullOrBlank() }
            if (managedAgents.isEmpty()) {
                flowOf(0)
            } else {
                combine(managedAgents.map { agentStatusResolver(it.id.value) }) { states -> states }
                    .transformLatest { states ->
                        var clockFloorMs = Long.MIN_VALUE
                        while (true) {
                            val nowMs = maxOf(build.raft.app.data.defaultNowMillis(), clockFloorMs)
                            emit(states.count { isMembersWorkingCountAgent(it, nowMs = nowMs) })
                            val nextExpiryMs = nextMembersWorkingCountHoldExpiryMs(states, nowMs) ?: break
                            val waitMs = (nextExpiryMs - nowMs).coerceAtLeast(1L)
                            delay(waitMs)
                            clockFloorMs = maxOf(clockFloorMs, nowMs + waitMs)
                        }
                    }
                    .distinctUntilChanged()
            }
        }
    val count by flow.collectAsState(0)
    return count
}

/** A menu row's 16dp icon in the row's icon ink. */
@Composable
internal fun MembersMenuIcon(name: RaftLucideIconName) {
    RaftLucideIcon(name, Modifier.size(16.dp), color = raftArgbHex(LocalRaftContentColor.current))
}


/**
 * A member on a phone (web `MemberPhoneRow`): raft-ui SidebarItem at its phone size
 * ([RaftSidebarItem]), led by the 19dp avatar. An agent's status is the light on the avatar's
 * corner (web `MemberEntryAvatar`); an app-managed bot has none. After the name: the App Bot's
 * badge and the role tag (web `useMemberTileTag`: Owner, Admin or Guest, never Member; "You" or
 * "You · Owner" on the viewer's own row). The second line is, on the Agents tab ([runtimeLine]),
 * what the agent runs on (runtime · model · computer, the computer left out when [machineName] is
 * null) and its reasoning effort (the web list's Reasoning column); elsewhere an agent's model, a
 * Guest's channel, or a person's description.
 */
@Composable
private fun MembersListRow(
    member: MemberProfile,
    selected: Boolean = false,
    isSelf: Boolean = false,
    runtimeLine: Boolean = false,
    machineName: String? = null,
    modifier: Modifier = Modifier,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onLongClick: (() -> Unit)? = null,
    menuOpen: Boolean = false,
    onEditAgent: ((MemberProfile) -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val tag = membersRowTag(member, isSelf)
    // botiverse/slock #11273 `MemberAgentRow`: an App Bot's name carries the Bot badge.
    val appBot = remember(member) { member.appBotIdentityOrNull() }
    if (member.kind == MemberKind.Agent) build.raft.app.data.EnsureServerModelLabelCatalog(member.serverId?.value)
    val modelLabelCatalog =
        if (member.kind == MemberKind.Agent) {
            build.raft.app.data.RaftModelLabelCatalogs.observeCatalog(member.serverId?.value)
        } else {
            null
        }
    val subtitle =
        when {
            member.kind == MemberKind.Agent && runtimeLine -> {
                val runtime =
                    if (appBot != null) {
                        null
                    } else {
                        (if (member.isExternal) "external" else member.roleDescription?.trim()?.takeIf { it.isNotEmpty() })
                            ?.let { runtimeLabelWithStatus(it, member.serverId?.value) }
                    }
                // botiverse/slock #15691: the reasoning effort its profile shows ("Reasoning: High"),
                // last, so on a phone it gives way before the computer does.
                val reasoning = member.membersAgentReasoningEffortLine()?.localized()
                listOfNotNull(runtime, member.modelLabel(catalog = modelLabelCatalog), machineName?.takeIf { it.isNotBlank() }, reasoning)
                    .joinToString(" · ")
                    .takeIf { it.isNotEmpty() }
            }
            member.kind == MemberKind.Agent -> member.modelLabel(catalog = modelLabelCatalog)
            membersHumanRoleBadge(member.serverRole, member.roleDescription) == MembersHumanRoleBadge.Guest &&
                !member.guestChannel?.name.isNullOrBlank() -> "#${member.guestChannel?.name}"
            else -> member.description?.takeIf { it.isNotBlank() }
        }
    RaftSidebarItem(
        title = member.displayName,
        modifier = modifier,
        onClick = onClick,
        selected = selected,
        subtitle = subtitle,
        // Web MemberMenuTrigger: a long press opens the row's menu.
        onLongClick = onLongClick,
        onLongClickLabel = onLongClick?.let { stringResource(Res.string.home_sidebar_row_menu_action) },
        menuOpen = menuOpen,
        // Web EditAgentButton at the row's end, for an agent the reader may edit.
        trailing = if (onEditAgent != null) { { MembersRowEditAgentButton(member, onEditAgent) } } else null,
        leading = {
            MembersListRowAvatar(
                member = member,
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver
            )
        },
        titleTrailing =
            if (tag != null || appBot != null) {
                {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (appBot != null) RaftAppBotBadge(identity = appBot, botName = member.displayName)
                        if (tag != null) RaftBadge(text = tag, appearance = RaftBadgeAppearance.Outline)
                    }
                }
            } else {
                null
            }
    )
}

/**
 * [MembersListRow] with what it can do beyond opening the profile ([MembersRowActions]): its
 * long-press menu ([MembersRowMenu]; none when there is nothing to offer, as for a Guest) and an
 * editable agent's Edit agent button. Without [rowActions] it is the plain row.
 */
@Composable
private fun MembersListActionRow(
    member: MemberProfile,
    rowActions: MembersRowActions?,
    rowDialogs: MembersRowDialogState,
    machines: List<SharedCreateAgentMachineOption>,
    selected: Boolean = false,
    isSelf: Boolean = false,
    runtimeLine: Boolean = false,
    machineName: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onClick: () -> Unit
) {
    val canMessage = rowActions != null && membersRowCanMessage(member, rowActions)
    var menuOpen by remember(member.id.value) { mutableStateOf(false) }
    Box {
        MembersListRow(
            member = member,
            selected = selected,
            isSelf = isSelf,
            runtimeLine = runtimeLine,
            machineName = machineName,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver,
            onLongClick = if (canMessage) ({ menuOpen = true }) else null,
            menuOpen = menuOpen && canMessage,
            onEditAgent = rowActions?.takeIf { membersRowCanEditAgent(member, it) }?.editAgent,
            onClick = onClick
        )
        if (canMessage && rowActions != null) {
            MembersRowMenu(
                member = member,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                actions = rowActions,
                dialogs = rowDialogs,
                machines = machines,
                agentStatusResolver = agentStatusResolver
            )
        }
    }
}

/**
 * Web `useMemberTileTag`: the role word (Owner, Admin or Guest; Member is most of the server, so
 * it says nothing), with "You" before it on the viewer's own row. An agent's role is its
 * `serverRole`; a person's falls back to the role label the list carries.
 */
@Composable
private fun membersRowTag(member: MemberProfile, isSelf: Boolean): String? {
    val role =
        if (member.kind == MemberKind.Human) {
            membersHumanRoleBadge(member.serverRole, member.roleDescription)
        } else {
            membersHumanRoleBadge(member.serverRole, null)
        }
    val roleWord =
        when (role) {
            MembersHumanRoleBadge.Owner -> stringResource(Res.string.member_role_owner)
            MembersHumanRoleBadge.Admin -> stringResource(Res.string.member_role_admin)
            MembersHumanRoleBadge.Guest -> stringResource(Res.string.channel_member_role_guest)
            MembersHumanRoleBadge.Member, MembersHumanRoleBadge.Unknown -> null
        }
    if (!isSelf) return roleWord
    return if (roleWord != null) {
        stringResource(Res.string.members_tag_you_with_role, roleWord)
    } else {
        stringResource(Res.string.members_tag_you)
    }
}

/** A managed agent's row shows its status light; a human's or an App Bot's does not. */
private fun MemberProfile.membersListShowsAgentStatus(): Boolean =
    kind == MemberKind.Agent && managedByAppInstallId.isNullOrBlank()

/**
 * The row's avatar. An agent's status light reads only here, so an agent:activity push
 * recomposes the avatar, not the row. Same contract as the detail header: an unresolved
 * status is Unknown, not Offline, so there is no light rather than a grey one (task #253/#256).
 */
@Composable
private fun MembersListRowAvatar(
    member: MemberProfile,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver
) {
    val showsStatus = member.membersListShowsAgentStatus()
    val rawStatus =
        // The Members tab does not subscribe to detailed agent:activity: coarse state only.
        rememberRaftAgentStatusIndicatorState(agentStatusResolver, member.id.value.takeIf { showsStatus })
            ?.withoutActivityDetail()
    val holdingReadiness = if (showsStatus) effectiveHoldingReadiness(rawStatus) else null
    val runtimeRetired =
        if (showsStatus && holdingReadiness == null) {
            rememberAgentRuntimeRetiredState(
                state = rawStatus,
                fallbackRuntime = member.roleDescription,
            )
        } else {
            null
        }
    val signInResolution =
        if (showsStatus && holdingReadiness == null && runtimeRetired == null) {
            rememberAgentRuntimeSignInResolution(
                state = rawStatus,
                fallbackRuntime = member.roleDescription,
                fallbackName = member.displayName.takeIf { it.isNotBlank() } ?: member.handle,
            )
        } else {
            AgentRuntimeSignInResolution(state = null, clearedByLiveLogin = false)
        }
    val signIn = signInResolution.state
    val effectiveRawStatus = rawStatus?.withoutClearedLoginRequiredAttention(signInResolution.clearedByLiveLogin)
    val attentionParts =
        if (showsStatus && runtimeRetired == null && signIn == null) {
            rememberAgentAttentionParts(effectiveRawStatus)
        } else {
            null
        }
    val deliveryHold =
        if (showsStatus && holdingReadiness == null && runtimeRetired == null && signIn == null && attentionParts == null) {
            rememberAgentDeliveryHoldView(
                state = effectiveRawStatus,
                fallbackRuntime = member.roleDescription,
                fallbackModel = member.model,
            )
        } else {
            null
        }
    val workersBusyText =
        if (showsStatus && holdingReadiness == null && runtimeRetired == null && signIn == null && attentionParts == null && deliveryHold == null) {
            val nowMs = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: build.raft.app.data.defaultNowMillis()
            effectiveRawStatus?.busyThroughWorkers(nowMs = nowMs)?.localizedText()
        } else {
            null
        }
    val status =
        when {
            holdingReadiness != null -> effectiveRawStatus?.copy(color = holdingReadiness.dotColor())
            runtimeRetired != null -> effectiveRawStatus?.copy(color = RaftColor.AccentOrange)
            signIn != null -> effectiveRawStatus?.copy(color = RaftColor.AccentOrange)
            attentionParts != null -> effectiveRawStatus?.copy(color = RaftColor.AccentOrange)
            deliveryHold != null -> effectiveRawStatus?.copy(color = RaftColor.AccentOrange)
            else -> effectiveRawStatus
        }
    MembersAvatar(
        member = member,
        showStatusOverlay = showsStatus && status != null,
        statusIndicatorState = status,
        statusDescription = runtimeRetired?.detail ?: signIn?.detail ?: attentionParts?.fullText ?: deliveryHold?.text ?: workersBusyText,
        memberAvatarResolver = memberAvatarResolver
    )
}

@Composable
private fun MembersAvatar(
    member: MemberProfile,
    avatarSize: Dp = MembersListAvatarSize,
    statusDisplayState: MembersAgentStatusDisplayState = MembersUnknownAgentStatusDisplayState,
    statusIndicatorState: RaftAgentStatusIndicatorState? = null,
    statusDescription: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    showStatusOverlay: Boolean = true,
    modifier: Modifier = Modifier
) {
    val resolvedAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id.value)
    val fallbackAvatarWireValue = remember(member.avatar, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    val avatarWireValue = resolvedAvatarWireValue ?: fallbackAvatarWireValue
    RaftAvatarWithStatus(
        modifier = modifier,
        statusColor =
            if (showStatusOverlay && member.kind == MemberKind.Agent) {
                statusIndicatorState?.color ?: statusDisplayState.color
            } else {
                null
            },
        statusDescription = if (showStatusOverlay && member.kind == MemberKind.Agent) statusDescription else null,
        avatarSize = avatarSize,
    ) {
        RaftAppAvatar(
            kind = raftAvatarKind(member.kind),
            avatarWireValue = avatarWireValue,
            label = member.raftAvatarName(),
            avatarSize = avatarSize,
            cacheSubject = member.raftAvatarCacheSubject(),
            cacheServerId = member.serverId?.value,
        )
    }
}

internal data class MembersAgentGroup(
    /**
     * Task #409: the persisted-collapse key — the machine's `computer-id:`
     * badge when present (stable across renames, same reason web keys by
     * `machineId`), otherwise the display name, otherwise the shared
     * [build.raft.app.data.MembersAgentMachineGroupNoMachineKey] sentinel.
     */
    val key: String,
    val label: String,
    val members: List<MemberProfile>
)

/**
 * #70: Rotating Loader spinner used by the workspace file tree as a chevron
 * substitute while a directory's children are being fetched
 * (`loadingDirs.contains(node.path)`). Mirrors the React
 * `AgentWorkspace.tsx` `<Spinner size="xs" />` pattern, but the KMP form
 * drops the React "Loading..." text per @artin's 06-23 20:51 directive
 * ("不要用文字做到最后，就把那个文件夹的 icon 改成 loading spinner 就行").
 *
 * 900ms rotation cycle matches the visual rhythm of the React inline
 * spinner without being so fast it looks nervous on the cheap-ohm
 * emulator display.
 */
@Composable
internal fun ErrorBanner(
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    traceId: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .raftCardSurface(fill = RaftColor.FillMuted)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftLucideIcon(
                RaftLucideIconName.TriangleAlert,
                modifier = Modifier.size(16.dp),
                color = "#FFD32F2F"
            )
            Text(
                text = text,
                // Web Banner: text-callout (14/20 400).
                style = RaftType.Callout,
                color = RaftColor.TextBlack,
                modifier = Modifier.weight(1f)
            )
            if (action != null && onAction != null) {
                RaftButton(
                    text = action,
                    onClick = onAction,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md
                )
            }
        }
        RaftTraceIdNote(traceId)
    }
}

/**
 * Web `groupAgents` (membersPageModel.ts): the computer groups in order, the reader's own computers
 * first (`isMyComputer`: attached or owned, [SharedCreateAgentMachineOption.mine]), then the rest, each A-Z by name; the group without a computer stays last ([groupedAgentMembers]).
 */
internal fun membersAgentGroupMachineOrder(machines: List<SharedCreateAgentMachineOption>): List<String> =
    machines
        .sortedWith(compareBy<SharedCreateAgentMachineOption>({ if (it.mine) 0 else 1 }, { it.name.lowercase() }, { it.id }))
        .map { it.id }

/**
 * A group's heading on the Members page (web MembersPage `heading`): the label in footnote bold
 * `foreground-hint`, truncated, then the count in the machine type; `px-5 pt-3` over its rows. Not
 * a toggle: a group is always open.
 */
@Composable
private fun MembersListGroupHeading(label: String, count: Int) {
    val colors = RaftTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .raftSafeAreaHorizontalPadding()
            .padding(start = MembersListMachineLabelInset, end = MembersListMachineLabelInset, top = RaftAppSpacing.Md, bottom = RaftAppSpacing.Xs)
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
    ) {
        RaftText(
            text = label,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).alignByBaseline()
        )
        RaftText(
            text = count.toString(),
            style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Normal),
            color = colors.foregroundHint,
            maxLines = 1,
            modifier = Modifier.alignByBaseline()
        )
    }
}

/**
 * Groups agents by computer. The list row of an agent carries its `machineId` but a name only via
 * `runtimeProfile.current.machineName`, which is null until the agent first runs; such an agent is
 * still on a computer, so its group is named from [machineName] (the server's computer list), then
 * any row's own name, then the id. Only an agent without a `machineId` is "no computer" (task #147
 * follow-up #148: a new Built-in Pi agent on daybed-earth was listed under "no computer").
 */
internal fun groupedAgentMembers(
    members: List<MemberProfile>,
    machineOrder: List<String> = emptyList(),
    noComputerLabel: String = membersNoComputerGroupLabel,
    machineName: (machineId: String) -> String? = { null }
): List<MembersAgentGroup> {
    val groups = members
        .groupBy { member ->
            member.membersComputerId
                ?: member.membersComputerName
                ?: build.raft.app.data.MembersAgentMachineGroupNoMachineKey
        }
        .map { (key, groupedMembers) ->
            val machineId = groupedMembers.firstNotNullOfOrNull { it.membersComputerId }
            MembersAgentGroup(
                key = key,
                label = machineId?.let(machineName)?.takeIf { it.isNotBlank() }
                    ?: groupedMembers.firstNotNullOfOrNull { it.membersComputerName }
                    ?: machineId
                    ?: noComputerLabel,
                members = groupedMembers
            )
        }
    // Web `orderAgentMachineGroups` (artin task #53): the groups follow the Computers list
    // ([machineOrder], see `inComputerListOrder`), a computer it doesn't show after the known ones
    // in the order it first appeared, and the group without a computer last.
    val rank = machineOrder.withIndex().associate { (index, id) -> id to index }
    return groups
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<MembersAgentGroup>>(
                { if (it.value.key == build.raft.app.data.MembersAgentMachineGroupNoMachineKey) 1 else 0 },
                { rank[it.value.key] ?: Int.MAX_VALUE },
                { it.index },
            )
        )
        .map { it.value }
}

/**
 * Web `members.page.group.noComputer` ("External & cloud"): the one group of every agent without
 * a computer -- external agents and cloud (hosted) agents alike. The web keeps them together on
 * purpose (SidebarMembersRail): `hostedRuntime` reaches managers only, so splitting on it would put
 * the same agent in different groups for different viewers. Localized at the render site
 * (`members_agent_group_no_computer`); this is the English default for non-UI callers.
 */
internal const val membersNoComputerGroupLabel = "External & cloud"

/**
 * The Members page's list: the store's roster, then the rows this page added for just-created
 * agents that the roster doesn't have yet. An agent archived in this process ([archivedAgentIds],
 * `SharedServerMembersStore.deletedAgentIdsFlow`) is left out, whichever list it came from.
 */
internal fun membersPageMergedMembers(
    storeMembers: List<MemberProfile>,
    optimisticMembers: List<MemberProfile>,
    archivedAgentIds: Set<String>
): List<MemberProfile> {
    val merged = LinkedHashMap<String, MemberProfile>()
    storeMembers.forEach { member ->
        member.id.value.trim().takeIf { it.isNotBlank() }?.let { merged[it] = member }
    }
    optimisticMembers.forEach { member ->
        member.id.value.trim().takeIf { it.isNotBlank() && it !in merged }?.let { merged[it] = member }
    }
    if (archivedAgentIds.isEmpty()) return merged.values.toList()
    return merged.values.filterNot { it.kind == MemberKind.Agent && it.id.value.trim() in archivedAgentIds }
}

/**
 * The members row for an agent that was just created, shown until the members refresh lands. It
 * carries the agent's computer (the create response's machineId, else the one the dialog sent; the
 * name from the response, else the dialog's machine list) so the row never groups under "no
 * computer" (task #148; the shell members screen used to add it without any computer).
 */
internal fun createdAgentMemberProfile(
    agent: build.raft.app.data.AgentSummary,
    submit: SharedCreateAgentDialogSubmit,
    machines: List<SharedCreateAgentMachineOption>
): MemberProfile {
    val machineId = agent.machineId?.takeIf { it.isNotBlank() }
        ?: submit.machineId?.takeIf { it.isNotBlank() }
    val machineName = agent.machineName?.takeIf { it.isNotBlank() }
        ?: machineId?.let { id -> machines.firstOrNull { it.id == id }?.name?.takeIf { name -> name.isNotBlank() } }
    return MemberProfile(
        id = MemberId(agent.id),
        displayName = agent.displayName?.takeIf { it.isNotBlank() } ?: agent.name,
        handle = agent.name,
        kind = MemberKind.Agent,
        description = agent.description,
        roleDescription = agent.model,
        avatar = AvatarReference.fromWireValue(agent.avatarUrl),
        presence = Presence.Offline,
        permissionBadges = listOfNotNull(
            machineName?.let { "computer:$it" },
            machineId?.let { "computer-id:$it" }
        )
    )
}
