package ai.slock.android.feature.members

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.core.data.repository.AgentActivitySnapshot
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
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockAvatar
import ai.slock.android.ui.components.SlockIconSquareButton
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockPageDivider
import ai.slock.android.ui.components.SlockTitledPageHeader
import ai.slock.android.ui.components.SlockWorkspaceFileTree
import ai.slock.android.ui.components.SlockWorkspaceNode
import ai.slock.android.ui.components.SlockWorkspaceSamples
import ai.slock.android.ui.components.SlockWorkspaceTreeState
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

private val MemberAvatarSize = 34.dp
private val MemberPresenceSize = 12.dp
private val MemberRowMinHeight = 48.dp
private val MembersPageInset = SlockSpacing.Md

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
    initialSelectedMemberId: String? = null,
    onBack: () -> Unit = {},
    onDetailBack: () -> Unit = {},
    onDetailVisibleChange: (Boolean) -> Unit = {},
    onOpenMemberProfile: (MemberProfile) -> Unit = {},
    onAddHuman: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    var selectedMemberId by rememberSaveable { mutableStateOf(initialSelectedMemberId) }
    var selectedDetailTab by rememberSaveable { mutableStateOf(MemberDetailTab.Profile.name) }
    LaunchedEffect(initialSelectedMemberId) {
        if (initialSelectedMemberId != null) {
            selectedMemberId = initialSelectedMemberId
            selectedDetailTab = MemberDetailTab.Profile.name
            onDetailVisibleChange(true)
        }
    }
    val selectedMember = (state.loadState as? ScreenLoadState.Content)
        ?.data
        ?.members
        ?.firstOrNull { it.id.value == selectedMemberId }

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
                selectedTab = MemberDetailTab.valueOf(selectedDetailTab),
                onTabSelected = { selectedDetailTab = it.name },
                agentDmsState = state.selectedAgentDmsState,
                agentRemindersState = state.selectedAgentRemindersState,
                agentActivityState = state.selectedAgentActivityState,
                onBack = closeDetail,
                modifier = modifier
            )
        }
    } else {
        onDetailVisibleChange(false)
        MembersPageShell(
            state = state,
            modifier = modifier,
            onBack = onBack,
            onMemberClick = { member ->
                selectedMemberId = member.id.value
                selectedDetailTab = MemberDetailTab.Profile.name
                onDetailVisibleChange(true)
                onOpenMemberProfile(member)
            },
            onAddHuman = onAddHuman,
            onRetry = onRetry
        )
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
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
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
            style = SlockType.PageTitle,
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
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.5f, fill = false)
            )
            Text(
                text = member.inlineDescription,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (member.kind != MemberKind.Human) {
            MemberPresenceDot(presence = member.presence)
        }
    }
}

@Composable
private fun MemberFlatAvatar(member: MemberProfile) {
    Box(
        modifier = Modifier.size(MemberAvatarSize)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            SlockAvatar(
                avatarUrl = member.avatarUrl,
                avatar = member.avatar,
                label = member.avatarLabel,
                size = MemberAvatarSize,
                fontSize = SlockType.Button.fontSize,
                fallbackColor = member.kind.avatarColor
            )
        }
        if (member.kind != MemberKind.Human) {
            MemberPresenceDot(
                presence = member.presence,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun MemberPresenceDot(
    presence: Presence,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(MemberPresenceSize)
            .background(presence.color, CircleShape)
            .border(SlockStroke.Thin, SlockColor.BorderBlack, CircleShape)
    )
}

@Composable
private fun MemberKindBadge(kind: MemberKind) {
    Text(
        text = kind.name.uppercase(),
        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        maxLines = 1,
        modifier = Modifier
            .background(kind.avatarColor, RoundedCornerShape(SlockRadius.Xs))
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MemberDetailHeader(member = member, onBack = onBack)
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var workspaceExpandedIds by rememberSaveable { mutableStateOf(SlockWorkspaceSamples.expandedIds.toList()) }
    var workspaceSelectedFileId by rememberSaveable { mutableStateOf("readme") }
    var workspacePreviewVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MemberDetailHeader(member = member, onBack = onBack)
        MemberDetailTabs(selectedTab = selectedTab, onTabSelected = onTabSelected)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            when (selectedTab) {
                MemberDetailTab.Profile -> MemberProfileDetail(member = member)
                MemberDetailTab.AgentDms -> AgentDirectMessagesPanel(state = agentDmsState)
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
                MemberDetailTab.Activity -> AgentActivityPanel(state = agentActivityState)
            }
            Spacer(modifier = Modifier.height(180.dp))
        }
    }
}

@Composable
private fun MemberDetailHeader(
    member: MemberProfile,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .heightIn(min = 58.dp)
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
            icon = SlockIcons.ArrowLeft,
            contentDescription = "Back to members",
            onClick = onBack,
            modifier = Modifier.size(38.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = member.roleDescription ?: member.handle,
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
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
private fun MemberProfileDetail(member: MemberProfile) {
    MemberDetailPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            MemberDetailAvatar(member = member)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    Text(
                        text = member.displayName,
                        style = SlockType.PageTitle,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    MemberKindBadge(kind = member.kind)
                }
                Text(
                    text = member.handle,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.roleDescription ?: "Member profile sample",
                    style = SlockType.Body,
                    color = SlockColor.TextBlack
                )
            }
        }
    }
    MemberDetailSection(title = "Display Name") {
        MemberDetailValue(member.displayName)
    }
    MemberDetailSection(title = "Description") {
        MemberDetailValue(member.roleDescription ?: "No description configured")
    }
    MemberDetailSection(title = "Info") {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            MemberDetailLabeledValue("Computer", "Jiachengs-MacBook-Pro.local · connected")
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
                    style = SlockType.Caption.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
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
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "($count)",
                style = SlockType.Caption,
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
                style = SlockType.Caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "($count)",
                style = SlockType.Caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
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
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MemberDetailAvatar(member: MemberProfile) {
    Box(modifier = Modifier.size(64.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            SlockAvatar(
                avatarUrl = member.avatarUrl,
                avatar = member.avatar,
                label = member.avatarLabel,
                size = 64.dp,
                fontSize = SlockType.PageTitle.fontSize,
                fallbackColor = member.kind.avatarColor
            )
        }
    }
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
                fontSize = SlockType.PageTitle.fontSize,
                fallbackColor = member.kind.avatarColor
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
        if (member.kind != MemberKind.Human) {
            MemberPresenceDot(
                presence = member.presence,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
private fun AgentDirectMessagesPanel(
    state: ScreenLoadState<AgentDmSnapshot>
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
        snapshot.directMessages.forEach { dm ->
            AgentDetailCard(
                title = dm.peerName,
                subtitle = dm.lastMessagePreview ?: "No recent message",
                meta = listOfNotNull(dm.peerId, dm.lastMessageAt).joinToString(" · ")
            )
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
        noPermissionDetail = "reminders access denied"
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
    state: ScreenLoadState<AgentActivitySnapshot>
) {
    AgentDetailListPanel(
        title = "Activity",
        state = state,
        emptyTitle = "No agent activity",
        emptyDetail = "No recent activity entries are available.",
        errorTitle = "Activity failed to load",
        noPermissionTitle = "Activity unavailable",
        noPermissionDetail = "activity access denied"
    ) { snapshot ->
        snapshot.activity.forEach { activity ->
            AgentDetailCard(
                title = activity.title,
                subtitle = activity.body ?: activity.type,
                meta = activity.timestamp.orEmpty()
            )
        }
    }
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
    content: @Composable ColumnScope.(T) -> Unit
) {
    MemberDetailPanel(title = title) {
        when (state) {
            ScreenLoadState.Loading -> AgentDetailStateCard("Loading", "Fetching real data…")
            is ScreenLoadState.Content -> content(state.data)
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
    meta: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = SlockType.Body,
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = SlockType.Caption,
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
    detail: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = detail,
            style = SlockType.Caption,
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
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.handle,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!member.roleDescription.isNullOrBlank()) {
                    Text(
                        text = member.roleDescription,
                        style = SlockType.Body,
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
        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
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
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        MemberDetailValue(value = value)
    }
}

@Composable
private fun MemberDetailValue(value: String) {
    Text(
        text = value,
        style = SlockType.Body,
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
                style = SlockType.Body,
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
        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
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
    icon: ImageVector = SlockIcons.Square
) {
    val shadow = SlockShadow.Default
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .background(color, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = {})
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
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
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

private val MemberDetailTab.icon: ImageVector
    get() = when (this) {
        MemberDetailTab.Profile -> SlockIcons.Bot
        MemberDetailTab.AgentDms -> SlockIcons.MessageSquare
        MemberDetailTab.Reminders -> SlockIcons.BellRing
        MemberDetailTab.Workspace -> SlockIcons.FolderOpen
        MemberDetailTab.Activity -> SlockIcons.Activity
    }

private enum class MemberSection(val label: String) {
    Agents("AGENTS"),
    Humans("HUMANS")
}

private val MemberKind.avatarColor: Color
    get() = when (this) {
        MemberKind.Human -> SlockColor.AccentLavender
        MemberKind.Agent -> SlockColor.AccentPink
        MemberKind.System -> SlockColor.AccentCyan
    }

private val Presence.color: Color
    get() = when (this) {
        Presence.Active -> SlockColor.AccentLime
        Presence.Away -> SlockColor.BrandYellow
        Presence.Offline -> SlockColor.EmptyStateGray
    }

private val Presence.readableLabel: String
    get() = when (this) {
        Presence.Active -> "Active"
        Presence.Away -> "Away"
        Presence.Offline -> "Offline"
    }

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
