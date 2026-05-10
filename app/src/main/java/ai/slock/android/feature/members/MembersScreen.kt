package ai.slock.android.feature.members

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentActivityEntry
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.toGravatarAvatarUrl
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.feature.thread.toThreadTimestampLabel
import ai.slock.android.ui.components.SlockAgentStatusIndicator
import ai.slock.android.ui.components.SlockAdaptiveAppLayoutInfo
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockAvatar
import ai.slock.android.ui.components.SlockIconSquareButton
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockMemberAvatar
import ai.slock.android.ui.components.SlockPageDivider
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.components.SlockTitledPageHeader
import ai.slock.android.ui.components.SlockWorkspaceFileTree
import ai.slock.android.ui.components.SlockWorkspaceNode
import ai.slock.android.ui.components.SlockWorkspaceSamples
import ai.slock.android.ui.components.SlockWorkspaceTreeState
import ai.slock.android.ui.components.SlockWindowLayoutMode
import ai.slock.android.ui.components.agentStatusLabel
import ai.slock.android.ui.components.defaultAvatarColor
import ai.slock.android.ui.system.DeclareStatusBarColor
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

private val MemberAvatarSize = 34.dp
private val MemberPresenceSize = 12.dp
private val MemberRowMinHeight = 48.dp
private val MembersPageInset = SlockSpacing.Md
private val MemberDetailHeaderContentHeight = SlockSize.PageHeaderHeight - SlockStroke.Default
private const val AGENT_ACTIVITY_TIMELINE_TAG = "agent_activity_timeline"
internal const val MEMBERS_WIDE_DIVIDER_TAG = "members_wide_divider"
internal const val MEMBERS_WIDE_DETAIL_PANE_TAG = "members_wide_detail_pane"
private val MembersPageTitleType = SlockType.PageTitle.copy(fontSize = 19.sp, lineHeight = 23.sp)
private val MembersBodyType = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp)
private val MembersBodyStrongType = MembersBodyType.copy(fontWeight = FontWeight.Bold)
private val MembersDescriptionType = MembersBodyType.copy(fontWeight = FontWeight.Normal)
private val MembersCaptionType = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp)
private val MembersCaptionStrongType = MembersCaptionType.copy(fontWeight = FontWeight.Bold)
private val MembersButtonType = SlockType.Button.copy(fontSize = 13.sp, lineHeight = 17.sp)

@Composable
fun MembersPageShell(
    state: MembersUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onMemberClick: (MemberProfile) -> Unit = {},
    onAddHuman: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (val loadState = state.loadState) {
            ScreenLoadState.Loading -> MembersFeedback(
                title = "Loading members",
                detail = "Preparing local sample state.",
                onBack = onBack
            )
            is ScreenLoadState.Content -> {
                MembersContentView(
                    content = loadState.data,
                    onBack = onBack,
                    onAddHuman = onAddHuman,
                    onMemberClick = onMemberClick
                )
            }
            is ScreenLoadState.Empty -> MembersFeedback(
                title = "No members",
                detail = loadState.reason.name,
                onBack = onBack
            )
            is ScreenLoadState.Error -> MembersError(
                error = loadState.error,
                onBack = onBack,
                onRetry = onRetry
            )
            is ScreenLoadState.NoPermission -> MembersFeedback(
                title = "No permission",
                detail = loadState.blocker.requiredCapability ?: loadState.blocker.code,
                onBack = onBack
            )
        }
    }
}

@Composable
fun MembersRouteScreen(
    state: MembersUiState,
    modifier: Modifier = Modifier,
    adaptiveLayoutInfo: SlockAdaptiveAppLayoutInfo? = null,
    initialSelectedMemberId: String? = null,
    initialSelectedDetailTab: String = MemberDetailTab.Profile.name,
    onBack: () -> Unit = {},
    onDetailBack: () -> Unit = {},
    onDetailVisibleChange: (Boolean) -> Unit = {},
    onOpenMemberProfile: (MemberProfile) -> Unit = {},
    onOpenAgentDirectMessage: (MemberProfile) -> Unit = {},
    onSaveAgentProfile: (MemberProfile, String, String) -> Unit = { _, _, _ -> },
    onAddHuman: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    var selectedMemberId by rememberSaveable { mutableStateOf(initialSelectedMemberId) }
    var selectedDetailTab by rememberSaveable {
        mutableStateOf(initialSelectedDetailTab.toMemberDetailTabName())
    }
    LaunchedEffect(initialSelectedMemberId, initialSelectedDetailTab) {
        if (initialSelectedMemberId != null) {
            selectedMemberId = initialSelectedMemberId
            selectedDetailTab = initialSelectedDetailTab.toMemberDetailTabName()
            onDetailVisibleChange(true)
        }
    }
    val selectedMember = (state.loadState as? ScreenLoadState.Content)
        ?.data
        ?.members
        ?.firstOrNull { it.id.value == selectedMemberId }
    val wideMembersMode =
        adaptiveLayoutInfo?.mode != null &&
            adaptiveLayoutInfo.mode != SlockWindowLayoutMode.CompactSinglePane
    val openDetail: (MemberProfile) -> Unit = { member ->
        selectedMemberId = member.id.value
        selectedDetailTab = MemberDetailTab.Profile.name
        onDetailVisibleChange(true)
        onOpenMemberProfile(member)
    }

    if (wideMembersMode) {
        BackHandler(enabled = selectedMember != null) {
            selectedMemberId = null
            onDetailVisibleChange(false)
        }
        MembersWideRouteScreen(
            state = state,
            selectedMember = selectedMember,
            selectedTab = selectedDetailTab.toMemberDetailTab(),
            onTabSelected = { selectedDetailTab = it.name },
            onCloseDetail = {
                selectedMemberId = null
                onDetailVisibleChange(false)
            },
            onMemberClick = openDetail,
            onOpenAgentDirectMessage = {
                selectedMember?.let(onOpenAgentDirectMessage)
            },
            onSaveAgentProfile = { member, displayName, description ->
                onSaveAgentProfile(member, displayName, description)
            },
            onBack = onBack,
            onAddHuman = onAddHuman,
            onRetry = onRetry,
            modifier = modifier
        )
        return
    }

    if (selectedMember != null) {
        val closeDetail = {
            selectedMemberId = null
            onDetailVisibleChange(false)
            onDetailBack()
        }
        BackHandler { closeDetail() }
        if (selectedMember.kind == MemberKind.Human) {
            HumanMemberDetailScreen(
                member = selectedMember,
                onBack = closeDetail,
                modifier = modifier
            )
        } else {
            MemberDetailScreen(
                member = selectedMember,
                selectedTab = selectedDetailTab.toMemberDetailTab(),
                onTabSelected = { selectedDetailTab = it.name },
                agentDmsState = state.selectedAgentDmsState,
                agentRemindersState = state.selectedAgentRemindersState,
                agentActivityState = state.selectedAgentActivityState,
                agentProfileSaveState = state.agentProfileSaveState,
                onBack = closeDetail,
                onOpenAgentDirectMessage = { onOpenAgentDirectMessage(selectedMember) },
                onSaveAgentProfile = { displayName, description ->
                    onSaveAgentProfile(selectedMember, displayName, description)
                },
                modifier = modifier
            )
        }
    } else {
        onDetailVisibleChange(false)
        MembersPageShell(
            state = state,
            modifier = modifier,
            onBack = onBack,
            onMemberClick = openDetail,
            onAddHuman = onAddHuman,
            onRetry = onRetry
        )
    }
}

@Composable
private fun MembersWideRouteScreen(
    state: MembersUiState,
    selectedMember: MemberProfile?,
    selectedTab: MemberDetailTab,
    onTabSelected: (MemberDetailTab) -> Unit,
    onCloseDetail: () -> Unit,
    onMemberClick: (MemberProfile) -> Unit,
    onOpenAgentDirectMessage: () -> Unit,
    onSaveAgentProfile: (MemberProfile, String, String) -> Unit,
    onBack: () -> Unit,
    onAddHuman: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedMember == null) {
        MembersPageShell(
            state = state,
            modifier = modifier,
            onBack = onBack,
            onMemberClick = onMemberClick,
            onAddHuman = onAddHuman,
            onRetry = onRetry
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MembersPageShell(
            state = state,
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            onBack = onBack,
            onMemberClick = onMemberClick,
            onAddHuman = onAddHuman,
            onRetry = onRetry
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
                .testTag(MEMBERS_WIDE_DIVIDER_TAG)
        )
        if (selectedMember.kind == MemberKind.Human) {
            HumanMemberDetailScreen(
                member = selectedMember,
                onBack = onCloseDetail,
                closeMode = true,
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .testTag(MEMBERS_WIDE_DETAIL_PANE_TAG)
            )
        } else {
            MemberDetailScreen(
                member = selectedMember,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                agentDmsState = state.selectedAgentDmsState,
                agentRemindersState = state.selectedAgentRemindersState,
                agentActivityState = state.selectedAgentActivityState,
                agentProfileSaveState = state.agentProfileSaveState,
                onBack = onCloseDetail,
                closeMode = true,
                onOpenAgentDirectMessage = onOpenAgentDirectMessage,
                onSaveAgentProfile = { displayName, description ->
                    onSaveAgentProfile(selectedMember, displayName, description)
                },
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .testTag(MEMBERS_WIDE_DETAIL_PANE_TAG)
            )
        }
    }
}

@Composable
private fun MembersHeader(
    content: MembersContent
) {
    SlockTitledPageHeader(title = "Members")
}

@Composable
private fun MembersContentView(
    content: MembersContent,
    onBack: () -> Unit,
    onAddHuman: () -> Unit,
    onMemberClick: (MemberProfile) -> Unit
) {
    LaunchedEffect(content.server.id, content.members) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockMembers",
            "render_count source=members_page serverId=${content.server.id.value} " +
                "agentsCount=${content.members.count { it.kind == MemberKind.Agent }} humansCount=${content.members.count { it.kind == MemberKind.Human }}"
        )
    }
    val grouped = content.members.groupBy {
        when (it.kind) {
            MemberKind.Human -> MemberSection.Humans
            MemberKind.Agent,
            MemberKind.System -> MemberSection.Agents
        }
    }

    MembersHeader(content = content)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MembersPageInset, vertical = SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersSection(
            section = MemberSection.Agents,
            members = grouped[MemberSection.Agents].orEmpty(),
            onAdd = onAddHuman,
            onMemberClick = onMemberClick
        )
        MembersSection(
            section = MemberSection.Humans,
            members = grouped[MemberSection.Humans].orEmpty(),
            onAdd = onAddHuman,
            onMemberClick = onMemberClick
        )
        Spacer(modifier = Modifier.height(180.dp))
    }
}

@Composable
private fun MembersDivider() {
    SlockPageDivider()
}

@Composable
private fun MembersSection(
    section: MemberSection,
    members: List<MemberProfile>,
    onAdd: () -> Unit,
    onMemberClick: (MemberProfile) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersSectionHeader(
            title = section.label,
            count = members.size,
            onAdd = onAdd
        )
        members.forEach { member ->
            MemberFlatRow(member = member, onClick = { onMemberClick(member) })
        }
    }
}

@Composable
private fun MembersSectionHeader(
    title: String,
    count: Int,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Icon(
            imageVector = SlockIcons.ChevronDown,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = title,
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            text = count.toString(),
            style = MembersBodyStrongType,
            color = SlockColor.TextMutedGray
        )
        Spacer(modifier = Modifier.weight(1f))
        MembersSectionAddButton(onClick = onAdd)
    }
}

@Composable
private fun MembersSectionAddButton(onClick: () -> Unit) {
    val shadow = SlockShadow.Small
    Box(
        modifier = Modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .size(34.dp)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+",
            style = MembersPageTitleType,
            color = SlockColor.TextBlack
        )
    }
}

@Composable
fun MemberFlatRow(
    member: MemberProfile,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MemberRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MemberFlatAvatar(member = member)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = member.displayName,
                style = MembersBodyStrongType,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.5f, fill = false)
            )
            Text(
                text = member.inlineDescription,
                style = MembersDescriptionType,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MemberFlatAvatar(member: MemberProfile) {
    Box(
        modifier = Modifier.size(MemberAvatarSize)
    ) {
        SlockMemberAvatar(
            member = member,
            size = MemberAvatarSize,
            fontSize = MembersButtonType.fontSize
        )
    }
}

@Composable
private fun MemberKindBadge(kind: MemberKind) {
    Text(
        text = kind.name.uppercase(),
        style = MembersCaptionStrongType,
        color = SlockColor.TextBlack,
        maxLines = 1,
        modifier = Modifier
            .background(kind.defaultAvatarColor, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Thin, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp)
    )
}

@Composable
private fun MembersFeedback(
    title: String,
    detail: String,
    onBack: () -> Unit
) {
    MembersStateHeader(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        SlockEmptyState(title = title, detail = detail)
    }
}

@Composable
private fun MembersError(
    error: ScreenError,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    MembersStateHeader(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(SlockSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        SlockErrorState(
            title = "Members failed to load",
            detail = error.code,
            onRetry = onRetry
        )
    }
}

@Composable
private fun MembersStateHeader(onBack: () -> Unit) {
    val emptyContent = MembersContent(
        server = ServerSummary(ServerId("members-state"), "Slock Android"),
        members = emptyList()
    )
    MembersHeader(content = emptyContent)
}

@Composable
private fun HumanMemberDetailScreen(
    member: MemberProfile,
    onBack: () -> Unit,
    closeMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MemberDetailHeader(member = member, onBack = onBack, closeMode = closeMode)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            HumanMemberProfileDetail(member = member)
            Spacer(modifier = Modifier.height(180.dp))
        }
    }
}

@Composable
private fun MemberDetailScreen(
    member: MemberProfile,
    selectedTab: MemberDetailTab,
    onTabSelected: (MemberDetailTab) -> Unit,
    agentDmsState: ScreenLoadState<AgentDmSnapshot>,
    agentRemindersState: ScreenLoadState<AgentReminderSnapshot>,
    agentActivityState: ScreenLoadState<AgentActivitySnapshot>,
    agentProfileSaveState: AgentProfileSaveState,
    onBack: () -> Unit,
    closeMode: Boolean = false,
    onOpenAgentDirectMessage: () -> Unit,
    onSaveAgentProfile: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var workspaceExpandedIds by rememberSaveable { mutableStateOf(SlockWorkspaceSamples.expandedIds.toList()) }
    var workspaceSelectedFileId by rememberSaveable { mutableStateOf("readme") }
    var workspacePreviewVisible by rememberSaveable { mutableStateOf(false) }
    val tabSwipeModifier = Modifier.memberDetailTabSwipe(selectedTab, onTabSelected)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MemberDetailHeader(member = member, onBack = onBack, closeMode = closeMode)
        Column(modifier = tabSwipeModifier.fillMaxSize()) {
            MemberDetailTabs(selectedTab = selectedTab, onTabSelected = onTabSelected)
            if (selectedTab == MemberDetailTab.Activity) {
                AgentActivityPanel(state = agentActivityState, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    when (selectedTab) {
                        MemberDetailTab.Profile ->
                            MemberProfileDetail(
                                member = member,
                                saveState = agentProfileSaveState,
                                onSave = onSaveAgentProfile
                            )
                        MemberDetailTab.AgentDms -> AgentDirectMessagesPanel(
                            state = agentDmsState,
                            onOpenAgentDirectMessage = onOpenAgentDirectMessage
                        )
                        MemberDetailTab.Reminders -> AgentRemindersPanel(state = agentRemindersState)
                        MemberDetailTab.Workspace -> MemberWorkspaceDetail(
                            expandedIds = workspaceExpandedIds.toSet(),
                            selectedFileId = workspaceSelectedFileId,
                            showPreview = workspacePreviewVisible,
                            onToggleFolder = { folder ->
                                workspaceExpandedIds = if (workspaceExpandedIds.contains(folder.id)) {
                                    workspaceExpandedIds - folder.id
                                } else {
                                    workspaceExpandedIds + folder.id
                                }
                            },
                            onSelectFile = { file ->
                                workspaceSelectedFileId = file.id
                                workspacePreviewVisible = true
                            },
                            onBackToTree = { workspacePreviewVisible = false }
                        )
                        MemberDetailTab.Activity -> Unit
                    }
                    Spacer(modifier = Modifier.height(180.dp))
                }
            }
        }
    }
}

@Composable
private fun MemberDetailHeader(
    member: MemberProfile,
    onBack: () -> Unit,
    closeMode: Boolean = false
) {
    DeclareStatusBarColor(SlockColor.SurfaceWhite)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .heightIn(min = MemberDetailHeaderContentHeight)
            .padding(
                start = SlockSpacing.Md,
                end = SlockSpacing.Md,
                top = SlockSpacing.Xs,
                bottom = SlockSpacing.Xs
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockIconSquareButton(
            icon = if (closeMode) SlockIcons.X else SlockIcons.ArrowLeft,
            contentDescription = if (closeMode) "Close member detail" else "Back to members",
            onClick = onBack,
            modifier = Modifier.size(38.dp)
        )
        SlockMemberAvatar(
            member = member,
            size = 38.dp,
            fontSize = MembersBodyStrongType.fontSize
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = member.displayName,
                style = MembersBodyStrongType,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text =
                    if (member.kind == MemberKind.Human) {
                        member.handle
                    } else {
                        member.agentStatus.agentStatusLabel(member.presence)
                    },
                style = MembersCaptionType,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    MembersDivider()
}

@Composable
private fun MemberDetailTabs(
    selectedTab: MemberDetailTab,
    onTabSelected: (MemberDetailTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(40.dp)
        ) {
            MemberDetailTab.entries.forEach { tab ->
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .background(if (selectedTab == tab) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .semantics { selected = selectedTab == tab }
                    .clickable(role = Role.Tab) { onTabSelected(tab) }
                    .padding(horizontal = SlockSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = tab.label.uppercase(),
                    style = SlockType.SectionLabel,
                    color = SlockColor.TextBlack,
                    maxLines = 1
                )
            }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .width(SlockStroke.Thin)
                        .background(SlockColor.BorderBlack)
                )
            }
        }
        MembersDivider()
    }
}

@Composable
private fun MemberProfileDetail(
    member: MemberProfile,
    saveState: AgentProfileSaveState,
    onSave: (String, String) -> Unit
) {
    var displayName by rememberSaveable(member.id.value) { mutableStateOf(member.displayName) }
    var description by rememberSaveable(member.id.value) {
        mutableStateOf(member.roleDescription.orEmpty())
    }
    var editing by rememberSaveable(member.id.value) { mutableStateOf(false) }
    LaunchedEffect(member.displayName, member.roleDescription) {
        displayName = member.displayName
        description = member.roleDescription.orEmpty()
    }
    LaunchedEffect(saveState) {
        if (saveState == AgentProfileSaveState.Saved) {
            editing = false
        }
    }
    val trimmedDisplayName = displayName.trim()
    val trimmedDescription = description.trim()
    val hasChanges =
        trimmedDisplayName != member.displayName.trim() ||
            trimmedDescription != member.roleDescription.orEmpty().trim()
    val isSaving = saveState is AgentProfileSaveState.Saving
    val canSave = trimmedDisplayName.isNotBlank() && hasChanges && !isSaving
    MemberDetailSection(title = "Profile") {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            if (editing) {
                SlockTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "Display Name",
                    placeholder = "Agent display name",
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                SlockTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Description",
                    placeholder = "Agent description",
                    enabled = !isSaving,
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlockButton(
                        text = "Save",
                        onClick = { onSave(trimmedDisplayName, trimmedDescription) },
                        enabled = canSave,
                        loading = isSaving,
                        variant = SlockButtonVariant.Pink,
                        modifier = Modifier.weight(1f)
                    )
                    SlockButton(
                        text = "Cancel",
                        onClick = {
                            displayName = member.displayName
                            description = member.roleDescription.orEmpty()
                            editing = false
                        },
                        enabled = !isSaving,
                        variant = SlockButtonVariant.Outline,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
                    ) {
                        MemberDetailLabeledValue("Display Name", member.displayName)
                        MemberDetailLabeledValue(
                            label = "Description",
                            value = member.roleDescription.orEmpty().ifBlank { "No description" }
                        )
                    }
                    SlockIconSquareButton(
                        icon = SlockIcons.Pencil,
                        contentDescription = "Edit agent profile",
                        onClick = {
                            displayName = member.displayName
                            description = member.roleDescription.orEmpty()
                            editing = true
                        },
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            when (saveState) {
                is AgentProfileSaveState.Error ->
                    Text(
                        text = "Save failed: ${saveState.error.code}",
                        style = MembersCaptionStrongType,
                        color = SlockColor.AccentRed
                    )
                AgentProfileSaveState.Saved ->
                    Text(
                        text = "Saved",
                        style = MembersCaptionStrongType,
                        color = SlockColor.AccentLime
                    )
                AgentProfileSaveState.Idle,
                AgentProfileSaveState.Saving -> Unit
            }
            MemberDetailLabeledValue("Handle", member.handle)
            MemberDetailLabeledValue("Kind", member.kind.name)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xl)
            ) {
                MemberDetailLabeledValue(
                    label = "Created",
                    value = "May 6, 2026",
                    modifier = Modifier.weight(1f)
                )
                MemberDetailLabeledValue(
                    label = "Creator",
                    value = "artin",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    MemberDetailPanel(title = "Runtime Configuration") {
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            MemberDetailChip("Codex CLI", SlockColor.AccentCyan)
            MemberDetailChip("GPT-5.5", SlockColor.AccentLavender)
            MemberDetailChip("high", SlockColor.BrandYellow)
        }
    }
    MemberSkillsSection()
    MemberDetailPanel(title = "Actions") {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            MemberDetailActionButton("Stop Agent", SlockColor.SurfaceWhite)
            MemberDetailActionButton(
                text = "Restart / Reset",
                color = SlockColor.SurfaceWhite,
                icon = SlockIcons.Activity
            )
            MemberDetailActionButton(
                text = "Report Issue",
                color = SlockColor.AccentPink,
                icon = SlockIcons.Bug
            )
            MemberDetailActionButton(
                text = "Delete Agent",
                color = SlockColor.AccentRed,
                icon = SlockIcons.Trash
            )
        }
    }
}

@Composable
private fun MemberWorkspaceDetail(
    expandedIds: Set<String>,
    selectedFileId: String,
    showPreview: Boolean,
    onToggleFolder: (SlockWorkspaceNode) -> Unit,
    onSelectFile: (SlockWorkspaceNode) -> Unit,
    onBackToTree: () -> Unit
) {
    MemberDetailPanel {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceTreeState.Content(
                rootPath = SlockWorkspaceSamples.root.path,
                root = SlockWorkspaceSamples.root,
                expandedNodeIds = expandedIds,
                selectedFileId = selectedFileId,
                showPreview = showPreview
            ),
            onToggleFolder = onToggleFolder,
            onSelectFile = onSelectFile,
            onBackToTree = onBackToTree
        )
    }
}

@Composable
private fun MemberSkillsSection() {
    MemberDetailPanel(title = "Skills") {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)) {
            MemberSkillGroup(
                icon = SlockIcons.Globe,
                title = "Global",
                count = 7
            ) {
                MemberSkillPathGroup(
                    path = "~/.codex/skills",
                    count = 2,
                    skills = listOf(
                        "generate2dmap" to "Generate and revise production-oriented 2D game maps with built-in image generation.",
                        "generate2dsprite" to "Generate and postprocess general 2D game assets and animation sheets."
                    )
                )
                MemberSkillPathGroup(
                    path = "~/.codex/skills/.system",
                    count = 5,
                    skills = listOf(
                        "imagegen" to "Generate or edit raster images for bitmap assets and visual variants.",
                        "openai-docs" to "Use official OpenAI documentation for up-to-date API and model guidance.",
                        "skill-creator" to "Guide for creating effective Codex skills."
                    )
                )
            }
            MemberSkillGroup(
                icon = SlockIcons.FolderOpen,
                title = "Workspace",
                count = 0
            ) {
                Text(
                    text = "No skills in this agent's workspace",
                    style = MembersCaptionType.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun MemberSkillGroup(
    icon: ImageVector,
    title: String,
    count: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = title,
                style = MembersCaptionStrongType,
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "($count)",
                style = MembersCaptionType,
                color = SlockColor.TextMutedGray
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md), content = content)
    }
}

@Composable
private fun MemberSkillPathGroup(
    path: String,
    count: Int,
    skills: List<Pair<String, String>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = path,
                style = MembersCaptionType.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "($count)",
                style = MembersCaptionType.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = SlockColor.TextMutedGray
            )
        }
        skills.forEach { (name, detail) ->
            MemberSkillCard(name = name, detail = detail)
        }
    }
}

@Composable
private fun MemberSkillCard(
    name: String,
    detail: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = name,
            style = MembersBodyStrongType,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = MembersCaptionType,
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MemberDetailAvatar(member: MemberProfile, size: Dp = 64.dp) {
    SlockMemberAvatar(
        member = member,
        size = size,
        fontSize = MembersPageTitleType.fontSize
    )
}

@Composable
private fun HumanMemberDetailAvatar(member: MemberProfile) {
    val avatarUrl = member.avatarUrl ?: member.gravatarHash?.toGravatarAvatarUrl()
    Box(modifier = Modifier.size(64.dp)) {
        if (avatarUrl != null || member.avatar != null) {
            SlockAvatar(
                avatarUrl = avatarUrl,
                avatar = member.avatar,
                label = member.avatarLabel,
                size = 64.dp,
                fontSize = MembersPageTitleType.fontSize,
                fallbackColor = member.kind.defaultAvatarColor
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SlockIcons.User,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun AgentStatusBadge(member: MemberProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockAgentStatusIndicator(
            status = member.agentStatus,
            presence = member.presence,
            size = MemberPresenceSize
        )
        Text(
            text = member.agentStatus.agentStatusLabel(member.presence),
            style = MembersCaptionStrongType,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AgentDirectMessagesPanel(
    state: ScreenLoadState<AgentDmSnapshot>,
    onOpenAgentDirectMessage: () -> Unit
) {
    AgentDetailListPanel(
        title = "Agent DMs",
        state = state,
        emptyTitle = "No agent DMs",
        emptyDetail = "This agent has no direct threads in the current workspace.",
        errorTitle = "Agent DMs failed to load",
        noPermissionTitle = "Agent DMs unavailable",
        noPermissionDetail = "agent_dms access denied"
    ) { snapshot ->
        MemberDetailActionButton(
            text = "Open direct message",
            color = SlockColor.BrandYellow,
            icon = SlockIcons.MessageSquare,
            onClick = onOpenAgentDirectMessage
        )
        if (snapshot.directMessages.isEmpty()) {
            AgentDetailStateCard(
                "No agent DMs",
                "This agent has no direct threads in the current workspace."
            )
        } else {
            snapshot.directMessages.forEach { dm ->
                AgentDetailCard(
                    title = dm.peerName,
                    subtitle = dm.lastMessagePreview ?: "No recent message",
                    meta = listOfNotNull(dm.peerId, dm.lastMessageAt).joinToString(" · "),
                    onClick = onOpenAgentDirectMessage
                )
            }
        }
    }
}

@Composable
private fun AgentRemindersPanel(
    state: ScreenLoadState<AgentReminderSnapshot>
) {
    AgentDetailListPanel(
        title = "Reminders",
        state = state,
        emptyTitle = "No scheduled reminders",
        emptyDetail = "This agent has no scheduled reminders.",
        errorTitle = "Reminders failed to load",
        noPermissionTitle = "Reminders unavailable",
        noPermissionDetail = "reminders access denied",
        isContentEmpty = { it.reminders.isEmpty() }
    ) { snapshot ->
        snapshot.reminders.forEach { reminder ->
            AgentDetailCard(
                title = reminder.title,
                subtitle = reminder.fireAt ?: "No scheduled time",
                meta = listOfNotNull(reminder.recurrenceDescription, reminder.status).joinToString(" · ")
            )
        }
    }
}

@Composable
private fun AgentActivityPanel(
    state: ScreenLoadState<AgentActivitySnapshot>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Thin)
                .background(SlockColor.TextBlack.copy(alpha = 0.10f))
        )
        Column(
            modifier = Modifier.padding(horizontal = SlockSpacing.Lg, vertical = SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = "ACTIVITY",
                style = SlockType.SectionLabel,
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "Following latest events",
                style = MembersCaptionType,
                color = SlockColor.TextMutedGray
            )
        }
        when (state) {
            ScreenLoadState.Loading ->
                AgentActivityStateCard(
                    title = "Loading",
                    detail = "Fetching real data...",
                    modifier = Modifier.padding(horizontal = SlockSpacing.Lg)
                )
            is ScreenLoadState.Content -> {
                if (state.data.activity.isEmpty()) {
                    AgentActivityStateCard(
                        title = "No agent activity",
                        detail = "No recent activity entries are available.",
                        modifier = Modifier.padding(horizontal = SlockSpacing.Lg)
                    )
                } else {
                    AgentActivityTimeline(entries = state.data.activity)
                }
            }
            is ScreenLoadState.Empty ->
                AgentActivityStateCard(
                    title = "No agent activity",
                    detail = "No recent activity entries are available.",
                    modifier = Modifier.padding(horizontal = SlockSpacing.Lg)
                )
            is ScreenLoadState.Error ->
                AgentActivityStateCard(
                    title = "Activity failed to load",
                    detail = state.error.code,
                    modifier = Modifier.padding(horizontal = SlockSpacing.Lg)
                )
            is ScreenLoadState.NoPermission ->
                AgentActivityStateCard(
                    title = "Activity unavailable",
                    detail = "activity access denied",
                    modifier = Modifier.padding(horizontal = SlockSpacing.Lg)
                )
        }
    }
}

@Composable
private fun AgentActivityTimeline(entries: List<AgentActivityEntry>) {
    val orderedEntries = entries.sortedBy { it.timestamp.activitySortKey() }
    val listState = rememberLazyListState()
    LaunchedEffect(orderedEntries.size, orderedEntries.lastOrNull()?.activityTimelineKey()) {
        if (orderedEntries.isNotEmpty()) {
            listState.scrollToItem(orderedEntries.lastIndex)
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AGENT_ACTIVITY_TIMELINE_TAG)
            .padding(horizontal = SlockSpacing.Lg),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        items(
            items = orderedEntries,
            key = { entry -> entry.activityTimelineKey() }
        ) { activity ->
            AgentActivityTimelineRow(activity = activity)
        }
        item { Spacer(modifier = Modifier.height(SlockSpacing.Lg)) }
    }
}

@Composable
private fun AgentActivityTimelineRow(activity: AgentActivityEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(activity.type.activityAccentColor(), CircleShape)
                    .border(SlockStroke.Default, SlockColor.BorderBlack, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SlockIcons.Activity,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(14.dp)
                )
            }
            Box(
                modifier = Modifier
                    .width(SlockStroke.Thin)
                    .height(22.dp)
                    .background(SlockColor.BorderBlack.copy(alpha = 0.22f))
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                .padding(SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                    text = activity.title,
                    style = MembersBodyStrongType,
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = activity.timestamp.activityDisplayTime(),
                    style = MembersCaptionStrongType,
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                MemberDetailChip(activity.type.activityTypeLabel(), activity.type.activityAccentColor())
            }
            Text(
                text = activity.activityPrimaryDetail(),
                style = MembersBodyType,
                color = SlockColor.TextBlack,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
            activity.details.takeIf { it.isNotEmpty() }?.let { details ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    details.forEach { (label, value) ->
                        Text(
                            text = "$label: $value",
                            style = MembersCaptionType,
                            color = SlockColor.TextMutedGray,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (activity.id.isNotBlank()) {
                Text(
                    text = "id ${activity.id}",
                    style = MembersCaptionType,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AgentActivityStateCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    AgentDetailStateCard(
        title = title,
        detail = detail,
        modifier = modifier
    )
}

@Composable
private fun <T> AgentDetailListPanel(
    title: String,
    state: ScreenLoadState<T>,
    emptyTitle: String,
    emptyDetail: String,
    errorTitle: String,
    noPermissionTitle: String,
    noPermissionDetail: String,
    isContentEmpty: (T) -> Boolean = { false },
    content: @Composable ColumnScope.(T) -> Unit
) {
    MemberDetailPanel(title = title) {
        when (state) {
            ScreenLoadState.Loading -> AgentDetailStateCard("Loading", "Fetching real data…")
            is ScreenLoadState.Content -> {
                if (isContentEmpty(state.data)) {
                    AgentDetailStateCard(emptyTitle, emptyDetail)
                } else {
                    content(state.data)
                }
            }
            is ScreenLoadState.Empty -> AgentDetailStateCard(emptyTitle, emptyDetail)
            is ScreenLoadState.Error -> AgentDetailStateCard(errorTitle, state.error.code)
            is ScreenLoadState.NoPermission -> AgentDetailStateCard(noPermissionTitle, noPermissionDetail)
        }
    }
}

@Composable
private fun AgentDetailCard(
    title: String,
    subtitle: String,
    meta: String,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .then(clickModifier)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MembersBodyStrongType,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MembersBodyType,
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MembersCaptionType,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AgentDetailStateCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MembersBodyStrongType,
            color = SlockColor.TextBlack
        )
        Text(
            text = detail,
            style = MembersCaptionType,
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun HumanMemberProfileDetail(member: MemberProfile) {
    MemberDetailPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            HumanMemberDetailAvatar(member = member)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                Text(
                    text = member.displayName,
                    style = MembersPageTitleType,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.handle,
                    style = MembersDescriptionType,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!member.roleDescription.isNullOrBlank()) {
                    Text(
                        text = member.roleDescription,
                        style = MembersDescriptionType,
                        color = SlockColor.TextBlack
                    )
                }
            }
        }
    }
    MemberDetailSection(title = "Profile") {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            MemberDetailLabeledValue("Display Name", member.displayName)
            MemberDetailLabeledValue("Handle", member.handle)
            MemberDetailLabeledValue("Role", member.roleDescription ?: "No profile description")
        }
    }
    MemberDetailSection(title = "Avatar") {
        MemberDetailValue(member.humanAvatarSourceLabel())
    }
}

private fun MemberProfile.humanAvatarSourceLabel(): String =
    avatarUrl
        ?: gravatarHash?.let { "gravatar:$it" }
        ?: avatar?.wireValue
        ?: "Default user icon"

@Composable
private fun MemberPresenceBadge(presence: Presence) {
    Text(
        text = presence.name.uppercase(),
        style = MembersCaptionStrongType,
        color = SlockColor.TextBlack,
        modifier = Modifier
            .background(presence.color, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Thin, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp)
    )
}

@Composable
private fun MemberDetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    MemberDetailPanel(title = title, content = content)
}

@Composable
private fun MemberDetailLabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MembersCaptionType,
            color = SlockColor.TextMutedGray
        )
        MemberDetailValue(value = value)
    }
}

@Composable
private fun MemberDetailValue(value: String) {
    Text(
        text = value,
        style = MembersBodyType,
        color = SlockColor.TextBlack,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MemberDetailSamplePanel(
    title: String,
    rows: List<String>
) {
    MemberDetailPanel(title = title) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MembersBodyType,
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                    .padding(SlockSpacing.Sm)
            )
        }
    }
}

@Composable
private fun MemberDetailPanel(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (title != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SlockStroke.Thin)
                    .background(SlockColor.TextBlack.copy(alpha = 0.10f))
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = SlockSpacing.Xl, vertical = SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = SlockType.SectionLabel,
                color = SlockColor.TextMutedGray
            )
        }
        content()
    }
    }
}

@Composable
private fun MemberDetailChip(
    text: String,
    color: Color
) {
    Text(
        text = text,
        style = MembersCaptionStrongType,
        color = SlockColor.TextBlack,
        modifier = Modifier
            .background(color, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs)
    )
}

@Composable
private fun MemberDetailActionButton(
    text: String,
    color: Color,
    icon: ImageVector = SlockIcons.Square,
    onClick: () -> Unit = {}
) {
    val shadow = SlockShadow.Default
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(color, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = SlockSpacing.Lg, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(SlockSpacing.Sm))
        Text(
            text = text,
            style = MembersButtonType.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private enum class MemberDetailTab(val label: String) {
    Profile("Profile"),
    AgentDms("Agent DMs"),
    Reminders("Reminders"),
    Workspace("Workspace"),
    Activity("Activity")
}

private fun String.toMemberDetailTab(): MemberDetailTab =
    MemberDetailTab.entries.firstOrNull { tab ->
        equals(tab.name, ignoreCase = true) ||
            equals(tab.label, ignoreCase = true) ||
            equals(tab.label.replace(" ", ""), ignoreCase = true) ||
            equals(tab.label.replace(" ", "_"), ignoreCase = true)
    } ?: MemberDetailTab.Profile

private fun String.toMemberDetailTabName(): String = toMemberDetailTab().name

private fun MemberDetailTab.neighbor(direction: Int): MemberDetailTab? {
    val tabs = MemberDetailTab.entries
    val nextIndex = tabs.indexOf(this) + direction
    return tabs.getOrNull(nextIndex)
}

private fun Modifier.memberDetailTabSwipe(
    selectedTab: MemberDetailTab,
    onTabSelected: (MemberDetailTab) -> Unit
): Modifier =
    pointerInput(selectedTab, onTabSelected) {
        var accumulatedDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulatedDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
                accumulatedDrag += dragAmount
                change.consume()
            },
            onDragEnd = {
                val targetTab =
                    when {
                        accumulatedDrag < -64f -> selectedTab.neighbor(direction = 1)
                        accumulatedDrag > 64f -> selectedTab.neighbor(direction = -1)
                        else -> null
                    }
                targetTab?.let(onTabSelected)
            }
        )
    }

private val MemberDetailTab.icon: ImageVector
    get() = when (this) {
        MemberDetailTab.Profile -> SlockIcons.Bot
        MemberDetailTab.AgentDms -> SlockIcons.MessageSquare
        MemberDetailTab.Reminders -> SlockIcons.BellRing
        MemberDetailTab.Workspace -> SlockIcons.FolderOpen
        MemberDetailTab.Activity -> SlockIcons.Activity
    }

private val Presence.color: Color
    get() = when (this) {
        Presence.Active -> SlockColor.AccentLime
        Presence.Away -> SlockColor.BrandYellow
        Presence.Offline -> SlockColor.TextMutedGray
    }

private enum class MemberSection(val label: String) {
    Agents("AGENTS"),
    Humans("HUMANS")
}

private fun String?.activitySortKey(): Long =
    when {
        isNullOrBlank() -> Long.MIN_VALUE
        all { it.isDigit() } -> toLongOrNull() ?: Long.MIN_VALUE
        else -> this
            .filter { it.isDigit() }
            .take(14)
            .padEnd(14, '0')
            .toLongOrNull() ?: Long.MIN_VALUE
    }

private fun String?.activityDisplayTime(): String =
    takeUnless { it.isNullOrBlank() }?.toThreadTimestampLabel()?.takeIf { it.isNotBlank() } ?: "No time"

private fun AgentActivityEntry.activityPrimaryDetail(): String =
    body?.takeIf { it.isNotBlank() }
        ?: details.firstOrNull()?.let { (label, value) -> "$label: $value" }
        ?: "No detail payload"

private fun String.activityTypeLabel(): String =
    replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { "Activity" }
        .replaceFirstChar { it.uppercase() }

private fun String.activityAccentColor(): Color =
    when (lowercase()) {
        "error", "failed", "failure" -> SlockColor.AccentRed
        "tool_start", "tool", "working" -> SlockColor.AccentCyan
        "thinking", "running", "active", "online" -> SlockColor.AccentLime
        "text", "message", "system" -> SlockColor.AccentLavender
        else -> SlockColor.BrandYellow
    }

private fun AgentActivityEntry.activityTimelineKey(): String =
    id.ifBlank { "${type}:${timestamp.orEmpty()}:${body.orEmpty()}" }

private val MemberProfile.inlineDescription: String
    get() = roleDescription ?: handle

private val membersPageSample = MembersUiState(
    loadState = ScreenLoadState.Content(
        MembersContent(
            server = ServerSummary(ServerId("members-sample"), "Slock Android"),
            members = listOf(
                MemberProfile(
                    id = MemberId("cindy"),
                    displayName = "Cindy",
                    handle = "Onboarding Assistant",
                    kind = MemberKind.Agent,
                    roleDescription = "Onboarding Assistant",
                    avatarUrl = "pixel:heart",
                    avatarLabel = "C",
                    presence = Presence.Active
                ),
                MemberProfile(
                    id = MemberId("product"),
                    displayName = "Product-UX-Designer",
                    handle = "负责把 @artin 的想法、截图和反馈整理成可执行的产品方案与交互设计",
                    kind = MemberKind.Agent,
                    roleDescription = "负责把 @artin 的想法、截图...",
                    avatarUrl = "random:product-ux-designer",
                    avatarLabel = "P",
                    presence = Presence.Away
                ),
                MemberProfile(
                    id = MemberId("android"),
                    displayName = "Android-Developer",
                    handle = "负责在 ~/AndroidStudioProjects/Slock 中实现 Android app",
                    kind = MemberKind.Agent,
                    roleDescription = "负责在 ~/AndroidStudioProjec...",
                    avatarUrl = "pixel:diamond",
                    avatarLabel = "A",
                    presence = Presence.Active
                ),
                MemberProfile(
                    id = MemberId("qa"),
                    displayName = "QA-Tester",
                    handle = "负责为 Android 多 agent 聊天 app 制定和执行测试",
                    kind = MemberKind.Agent,
                    roleDescription = "负责为 Android 多 agent 聊天 app 制定和...",
                    avatarUrl = "pixel:star",
                    avatarLabel = "Q",
                    presence = Presence.Active
                ),
                MemberProfile(
                    id = MemberId("api"),
                    displayName = "API-Integration-Developer",
                    handle = "负责 Slock Android 的数据与接口层开发",
                    kind = MemberKind.Agent,
                    roleDescription = "负责 Slock Android 的...",
                    avatarUrl = "random:api-integration-developer",
                    avatarLabel = "AI",
                    presence = Presence.Active
                ),
                MemberProfile(
                    id = MemberId("integrator"),
                    displayName = "Android-App-Integrator",
                    handle = "负责 Slock Android 的真实 app 集成层",
                    kind = MemberKind.Agent,
                    roleDescription = "负责 Slock Android 的真...",
                    avatarUrl = "pixel:robot",
                    avatarLabel = "AP",
                    presence = Presence.Away
                ),
                MemberProfile(
                    id = MemberId("artin"),
                    displayName = "artin (you)",
                    handle = "@artin",
                    kind = MemberKind.Human,
                    roleDescription = null,
                    avatarUrl = "https://cdn.example.com/artin.png",
                    avatarLabel = "人",
                    presence = Presence.Offline
                )
            )
        )
    )
)

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MembersPageShellPreview() {
    SlockTheme {
        MembersPageShell(state = membersPageSample)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MembersPageStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            MemberFlatRow(
                member = MemberProfile(
                    id = MemberId("long"),
                    displayName = "Very Long Agent Name With Mixed 中文 English",
                    handle = "A very long role line should ellipsis before the status dot",
                    kind = MemberKind.Agent,
                    roleDescription = "Long role description with 中文 and English text that should truncate",
                    avatarUrl = "random:long-agent",
                    avatarLabel = "L",
                    presence = Presence.Active
                )
            )
            MembersPageShell(
                state = MembersUiState(ScreenLoadState.Empty(EmptyReason.NoMembers))
            )
            MembersPageShell(
                state = MembersUiState(ScreenLoadState.Error(ScreenError("members_failed")))
            )
            MembersPageShell(
                state = MembersUiState(
                    ScreenLoadState.NoPermission(PermissionBlocker("members_hidden", "view_members"))
                )
            )
        }
    }
}
