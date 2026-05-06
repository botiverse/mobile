package ai.slock.android.feature.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockLabeledCheckbox
import ai.slock.android.ui.components.SlockModal
import ai.slock.android.ui.components.SlockModalAction
import ai.slock.android.ui.components.SlockModalActionStyle
import ai.slock.android.ui.components.SlockModalTextField
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onModeSelected: (ThreadTab) -> Unit = {},
    onComposerChange: (String) -> Unit = {},
    onAsTaskChange: (Boolean) -> Unit = {},
    onSend: () -> Unit = {},
    onLoadOlderMessages: () -> Unit = {},
    onRetryLoadOlderMessages: () -> Unit = {},
    onLoadNewerMessages: () -> Unit = {},
    onRetryLoadNewerMessages: () -> Unit = {}
) {
    var showMembersModal by rememberSaveable { mutableStateOf(false) }
    var showSettingsModal by rememberSaveable { mutableStateOf(false) }
    var showAddMember by rememberSaveable { mutableStateOf(false) }
    var addMemberQuery by rememberSaveable { mutableStateOf("") }
    var addMemberError by rememberSaveable { mutableStateOf(false) }
    var addedMemberName by rememberSaveable { mutableStateOf<String?>(null) }
    val members = threadMemberModalItems()
    val displayedMembers = addedMemberName?.let { name ->
        members + ThreadMemberModalItem(
            name = name,
            role = "Invited member",
            avatarLabel = name.trim().take(1).uppercase().ifBlank { "M" },
            kind = MemberKind.Human,
            presence = Presence.Away
        )
    } ?: members

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            when (val loadState = state.loadState) {
                ScreenLoadState.Loading -> {
                    ThreadTopHeader(
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = members.size,
                        onMembersClick = { showMembersModal = true }
                    )
                    ThreadModeTabs(selectedTab = state.selectedTab, onModeSelected = onModeSelected)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading thread...", style = SlockType.Button, color = SlockColor.TextMutedGray)
                    }
                    ThreadComposerBar(state.composerState, "Message", onComposerChange, onAsTaskChange, onSend)
                }
                is ScreenLoadState.Content -> {
                    ThreadTopHeader(
                        summary = loadState.data.header,
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = members.size,
                        onMembersClick = { showMembersModal = true }
                    )
                    ThreadModeTabs(selectedTab = state.selectedTab, onModeSelected = onModeSelected)
                    if (state.selectedTab == ThreadTab.Tasks) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Lg),
                            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)
                        ) {
                            ThreadTaskSummary(tasks = loadState.data.tasks)
                        }
                    } else {
                        ThreadMessageList(
                            content = loadState.data,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            onLoadOlderMessages = onLoadOlderMessages,
                            onRetryLoadOlderMessages = onRetryLoadOlderMessages,
                            onLoadNewerMessages = onLoadNewerMessages,
                            onRetryLoadNewerMessages = onRetryLoadNewerMessages
                        )
                    }
                    ThreadComposerBar(
                        composerState = state.composerState,
                        placeholder = "Message #${loadState.data.header.name}",
                        onComposerChange = onComposerChange,
                        onAsTaskChange = onAsTaskChange,
                        onSend = onSend
                    )
                }
                is ScreenLoadState.Empty -> {
                    ThreadTopHeader(
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = members.size,
                        onMembersClick = { showMembersModal = true }
                    )
                    ThreadModeTabs(selectedTab = state.selectedTab, onModeSelected = onModeSelected)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockEmptyState(
                            title = "No messages yet",
                            detail = "Start the conversation when you are ready."
                        )
                    }
                    ThreadComposerBar(state.composerState, "Message", onComposerChange, onAsTaskChange, onSend)
                }
                is ScreenLoadState.Error -> {
                    ThreadTopHeader(
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = members.size,
                        onMembersClick = { showMembersModal = true }
                    )
                    ThreadModeTabs(selectedTab = state.selectedTab, onModeSelected = onModeSelected)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockErrorState(
                            title = "Thread failed to load",
                            detail = "Error code: ${loadState.error.code}",
                            onRetry = {}
                        )
                    }
                    ThreadComposerBar(state.composerState.copy(enabled = false), "Message", onComposerChange, onAsTaskChange, onSend)
                }
                is ScreenLoadState.NoPermission -> {
                    ThreadTopHeader(
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = members.size,
                        onMembersClick = { showMembersModal = true }
                    )
                    ThreadModeTabs(selectedTab = state.selectedTab, onModeSelected = onModeSelected)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockEmptyState(
                            title = "No permission",
                            detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                        )
                    }
                    ThreadComposerBar(
                        state.composerState.copy(enabled = false, noPermissionCode = loadState.blocker.code),
                        "Message",
                        onComposerChange,
                        onAsTaskChange,
                        onSend
                    )
                }
            }
        }

        if (showMembersModal) {
            ThreadMembersModal(
                members = displayedMembers,
                showAddMember = showAddMember,
                addMemberQuery = addMemberQuery,
                addMemberError = addMemberError,
                onAddMemberQueryChange = {
                    addMemberQuery = it
                    addMemberError = false
                },
                onAddMemberClick = {
                    if (showAddMember && addMemberQuery.isBlank()) {
                        addMemberError = true
                    } else if (showAddMember) {
                        addedMemberName = addMemberQuery
                        addMemberQuery = ""
                        addMemberError = false
                        showAddMember = false
                    } else {
                        showAddMember = true
                    }
                },
                onDismiss = {
                    showMembersModal = false
                    showAddMember = false
                    addMemberQuery = ""
                    addMemberError = false
                    addedMemberName = null
                }
            )
        }
        if (showSettingsModal) {
            ThreadSettingsModal(
                initialName = (state.loadState as? ScreenLoadState.Content)?.data?.header?.name ?: "onboarding-artin",
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@Composable
fun ThreadTopHeader(
    modifier: Modifier = Modifier,
    summary: ConversationSummary? = null,
    onBack: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    membersCount: Int = 4,
    onMembersClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SlockSize.PageHeaderHeight)
            .background(SlockColor.SurfaceWhite)
            .border(width = SlockStroke.Thin, color = SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        HeaderIconButton(SlockIcons.ArrowLeft, "Back", onClick = onBack)
        ThreadHashTile()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary?.name ?: "onboarding-artin",
                style = SlockType.PageTitle,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = summary?.subtitle
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HeaderIconButton(SlockIcons.Settings, "Settings", onClick = onSettingsClick)
        HeaderIconButton(SlockIcons.LogOut, "Leave")
        ThreadMembersButton(count = membersCount, onClick = onMembersClick)
    }
}

private data class ThreadMemberModalItem(
    val name: String,
    val role: String,
    val avatarLabel: String,
    val kind: MemberKind,
    val presence: Presence
)

private fun threadMemberModalItems(): List<ThreadMemberModalItem> = listOf(
    ThreadMemberModalItem(
        name = "Android-Developer",
        role = "UI components",
        avatarLabel = "A",
        kind = MemberKind.Agent,
        presence = Presence.Active
    ),
    ThreadMemberModalItem(
        name = "API-Integration-Developer",
        role = "Data contracts",
        avatarLabel = "I",
        kind = MemberKind.Agent,
        presence = Presence.Away
    ),
    ThreadMemberModalItem(
        name = "artin",
        role = "Product owner",
        avatarLabel = "A",
        kind = MemberKind.Human,
        presence = Presence.Active
    ),
    ThreadMemberModalItem(
        name = "QA-Tester",
        role = "Acceptance",
        avatarLabel = "Q",
        kind = MemberKind.Human,
        presence = Presence.Offline
    )
)

private enum class ThreadSettingsModalStatus {
    Default,
    Saving,
    Error,
    Disabled,
    NoPermission
}

@Composable
private fun ThreadSettingsModal(
    initialName: String,
    modifier: Modifier = Modifier,
    status: ThreadSettingsModalStatus = ThreadSettingsModalStatus.Default,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var description by rememberSaveable {
        mutableStateOf("Coordinate Android UI, sample state, and app integration.")
    }
    val saving = status == ThreadSettingsModalStatus.Saving
    val disabled = status == ThreadSettingsModalStatus.Disabled || status == ThreadSettingsModalStatus.NoPermission
    val enabled = !saving && !disabled

    SlockModal(
        title = "EDIT CHANNEL",
        onDismiss = onDismiss,
        modifier = modifier,
        enabled = !disabled,
        dismissOnScrimClick = !saving,
        errorText = when (status) {
            ThreadSettingsModalStatus.Error -> "Unable to save sample channel settings."
            ThreadSettingsModalStatus.NoPermission -> "You do not have permission to edit this channel."
            else -> null
        },
        secondaryAction = SlockModalAction(
            text = "Cancel",
            onClick = onDismiss,
            style = SlockModalActionStyle.Outline,
            enabled = !saving
        ),
        primaryAction = SlockModalAction(
            text = if (saving) "Saving" else "Save Changes",
            onClick = onDismiss,
            enabled = enabled && name.isNotBlank(),
            loading = saving
        ),
        dangerActions = listOf(
            SlockModalAction(
                text = "Archive Channel",
                onClick = {},
                style = SlockModalActionStyle.Warning,
                enabled = enabled,
                icon = SlockIcons.Archive
            ),
            SlockModalAction(
                text = "Delete Channel",
                onClick = {},
                style = SlockModalActionStyle.Danger,
                enabled = enabled,
                icon = SlockIcons.Trash
            )
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            Text(
                text = "#$initialName",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SlockModalTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                placeholder = "channel-name",
                required = true,
                enabled = enabled,
                isError = status == ThreadSettingsModalStatus.Error || name.isBlank(),
                errorText = if (name.isBlank()) "Name is required." else null
            )
            SlockModalTextField(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                placeholder = "What is this channel for?",
                optional = true,
                enabled = enabled,
                singleLine = false,
                minLines = 3,
                maxLines = 4
            )
        }
    }
}

@Composable
private fun ThreadMembersModal(
    members: List<ThreadMemberModalItem>,
    showAddMember: Boolean,
    addMemberQuery: String,
    addMemberError: Boolean,
    onAddMemberQueryChange: (String) -> Unit,
    onAddMemberClick: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    noPermission: Boolean = false
) {
    SlockModal(
        title = "MEMBERS (${members.size})",
        onDismiss = onDismiss,
        enabled = enabled && !loading,
        errorText = when {
            noPermission -> "You do not have permission to add members."
            addMemberError -> "Enter a name, handle, or email before adding."
            else -> null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            when {
                loading -> {
                    Text(
                        text = "Loading members...",
                        style = SlockType.Button,
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.padding(vertical = SlockSpacing.Lg)
                    )
                }
                members.isEmpty() -> {
                    Text(
                        text = "No members in this thread yet.",
                        style = SlockType.Body,
                        color = SlockColor.TextMutedGray
                    )
                }
                else -> {
                    ThreadMemberSection(
                        label = "AGENTS",
                        members = members.filter { it.kind == MemberKind.Agent }
                    )
                    ThreadMemberSection(
                        label = "HUMANS",
                        members = members.filter { it.kind == MemberKind.Human }
                    )
                }
            }
            if (showAddMember) {
                SlockModalTextField(
                    value = addMemberQuery,
                    onValueChange = onAddMemberQueryChange,
                    label = "Add member",
                    placeholder = "@teammate or email",
                    enabled = enabled && !loading && !noPermission,
                    isError = addMemberError,
                    errorText = if (addMemberError) "A member identifier is required." else null,
                    required = true
                )
            }
            SlockButton(
                text = if (showAddMember) "Add Member" else "+ Add Member",
                onClick = onAddMemberClick,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Pink,
                enabled = enabled && !loading && !noPermission,
                loading = loading
            )
        }
    }
}

@Composable
private fun ThreadMemberSection(
    label: String,
    members: List<ThreadMemberModalItem>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextMutedGray
        )
        if (members.isEmpty()) {
            Text(
                text = "None",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        } else {
            members.forEach { member ->
                ThreadMemberRow(member = member)
            }
        }
    }
}

@Composable
private fun ThreadMemberRow(member: ThreadMemberModalItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(modifier = Modifier.size(46.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (member.kind == MemberKind.Agent) SlockColor.AccentCyan else SlockColor.AccentLavender)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = member.avatarLabel,
                    style = SlockType.Button,
                    color = SlockColor.TextBlack
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(member.presence.presenceColor)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${member.role} · ${member.presence.name.lowercase()}",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val Presence.presenceColor: Color
    get() = when (this) {
        Presence.Active -> SlockColor.AccentLime
        Presence.Away -> SlockColor.BrandYellow
        Presence.Offline -> SlockColor.TextMutedGray
    }

@Composable
private fun ThreadHashTile() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(SlockColor.BrandYellow)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = SlockIcons.Hash,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .size(38.dp)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ThreadMembersButton(
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .heightIn(min = 38.dp)
            .defaultMinSize(minWidth = 54.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Icon(SlockIcons.Users, contentDescription = "Members", tint = SlockColor.TextBlack, modifier = Modifier.size(18.dp))
        Text(count.toString(), style = SlockType.Button, color = SlockColor.TextBlack)
    }
}

@Composable
fun ThreadModeTabs(
    selectedTab: ThreadTab,
    onModeSelected: (ThreadTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        ThreadTab.entries.forEach { mode ->
            val selected = selectedTab == mode
            Row(
                modifier = Modifier
                    .width(116.dp)
                    .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .clickable(role = Role.Tab) { onModeSelected(mode) }
                    .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Icon(
                    imageVector = if (mode == ThreadTab.Chat) SlockIcons.MessageSquare else SlockIcons.ListTodo,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(18.dp)
                )
                Text(mode.name.uppercase(), style = SlockType.Button, color = SlockColor.TextBlack)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ThreadContextNotice(text: String) {
    Text(
        text = text,
        style = SlockType.Caption,
        color = SlockColor.TextMutedGray,
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Thin, SlockColor.TextMutedGray)
            .padding(SlockSpacing.Sm)
    )
}

@Composable
private fun ThreadMessageList(
    content: ThreadContent,
    modifier: Modifier = Modifier,
    onLoadOlderMessages: () -> Unit,
    onRetryLoadOlderMessages: () -> Unit,
    onLoadNewerMessages: () -> Unit,
    onRetryLoadNewerMessages: () -> Unit
) {
    val leadingItemCount = 1 +
        if (content.historyLimited || content.hasGap) 1 else 0 +
        if (content.newUpdateCount > 0) 1 else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (leadingItemCount + content.messages.lastIndex).coerceAtLeast(0)
    )
    val shouldLoadOlder by remember(content.canLoadMoreBefore, content.olderMessagesStatus) {
        derivedStateOf {
            content.canLoadMoreBefore &&
                content.olderMessagesStatus == MessagePageStatus.Idle &&
                listState.firstVisibleItemIndex <= 1
        }
    }
    val shouldLoadNewer by remember(content.canLoadMoreAfter, content.newerMessagesStatus) {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            content.canLoadMoreAfter &&
                content.newerMessagesStatus == MessagePageStatus.Idle &&
                totalItems > 0 &&
                lastVisibleIndex >= totalItems - 2
        }
    }
    val lastMessageId = content.messages.lastOrNull()?.id

    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            onLoadOlderMessages()
        }
    }
    LaunchedEffect(shouldLoadNewer) {
        if (shouldLoadNewer) {
            onLoadNewerMessages()
        }
    }
    LaunchedEffect(lastMessageId) {
        if (lastMessageId != null && listState.layoutInfo.totalItemsCount > 0) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val lastItemIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastVisibleIndex >= lastItemIndex - 2) {
                listState.animateScrollToItem(lastItemIndex)
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = SlockSpacing.Md,
            vertical = SlockSpacing.Lg
        ),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)
    ) {
        item(key = "older-page-state") {
            ThreadMessagePageState(
                canLoadMore = content.canLoadMoreBefore,
                status = content.olderMessagesStatus,
                errorCode = content.olderMessagesErrorCode,
                loadingText = "Loading older messages...",
                idleText = "Scroll up to load older messages",
                endText = "Beginning of message history",
                onRetry = onRetryLoadOlderMessages
            )
        }
        if (content.historyLimited || content.hasGap) {
            item(key = "context-notice") {
                ThreadContextNotice(
                    text = if (content.historyLimited) {
                        "History is limited in this preview"
                    } else {
                        "Message history has a gap"
                    }
                )
            }
        }
        if (content.newUpdateCount > 0) {
            item(key = "task-updates-top") {
                TaskUpdatesDivider(count = content.newUpdateCount)
            }
        }
        itemsIndexed(
            items = content.messages,
            key = { _, message -> message.id.value }
        ) { index, message ->
            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)) {
                ThreadMessageItem(
                    message = message,
                    highlighted = message.id == content.highlightedMessageId,
                    showMentionChip = message.body.contains("@"),
                    showCodeChip = message.body.contains("`") ||
                        message.body.contains("ScreenLoadState") ||
                        message.body.contains("*UiState")
                )
                if (index == 1 && content.newUpdateCount == 0) {
                    TaskUpdatesDivider(count = 2)
                }
            }
        }
        item(key = "newer-page-state") {
            ThreadMessagePageState(
                canLoadMore = content.canLoadMoreAfter,
                status = content.newerMessagesStatus,
                errorCode = content.newerMessagesErrorCode,
                loadingText = "Loading newer messages...",
                idleText = "Scroll down to load newer messages",
                endText = "You are caught up",
                onRetry = onRetryLoadNewerMessages
            )
        }
    }
}

@Composable
private fun ThreadMessagePageState(
    canLoadMore: Boolean,
    status: MessagePageStatus,
    errorCode: String?,
    loadingText: String,
    idleText: String,
    endText: String,
    onRetry: () -> Unit
) {
    when (status) {
        MessagePageStatus.Loading -> ThreadContextNotice(text = loadingText)
        MessagePageStatus.Error -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockButton(
                text = "Retry history${errorCode?.let { " ($it)" } ?: ""}",
                onClick = onRetry,
                variant = SlockButtonVariant.Outline
            )
        }
        MessagePageStatus.Idle -> if (canLoadMore) {
            ThreadContextNotice(text = idleText)
        }
        MessagePageStatus.EndReached -> ThreadContextNotice(text = endText)
    }
}

@Composable
private fun ThreadTaskSummary(tasks: List<TaskSummary>) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        if (tasks.isEmpty()) {
            SlockEmptyState(
                title = "No thread tasks",
                detail = "Tasks created from this conversation will appear here."
            )
        } else {
            tasks.forEach { task ->
                ThreadChip(
                    label = "#${task.taskNumber ?: "task"} ${task.title}",
                    color = task.status.threadColor,
                    icon = SlockIcons.ListTodo
                )
            }
        }
    }
}

@Composable
fun TaskUpdatesDivider(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "There are $count task updates",
            style = SlockType.Body.copy(fontWeight = FontWeight.Normal),
            color = SlockColor.TextMutedGray
        )
        Icon(
            imageVector = SlockIcons.ChevronRight,
            contentDescription = null,
            tint = SlockColor.TextMutedGray,
            modifier = Modifier
                .padding(start = SlockSpacing.Xs)
                .size(16.dp)
                .rotate(90f)
        )
    }
}

@Composable
fun ThreadMessageItem(
    message: Message,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    showTaskChip: Boolean = false,
    showMentionChip: Boolean = false,
    showCodeChip: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (highlighted) SlockColor.BrandYellow.copy(alpha = 0.25f) else Color.Transparent)
            .border(
                width = if (highlighted) SlockStroke.Thin else 0.dp,
                color = if (highlighted) SlockColor.BorderBlack else Color.Transparent
            )
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        ThreadAvatar(message.author.avatarLabel, message.author.kind)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                    text = message.author.displayName,
                    style = SlockType.Button,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = message.author.roleDescription ?: message.author.kind.name.lowercase(),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(message.timestampLabel, style = SlockType.Caption, color = SlockColor.TextMutedGray)
                if (showTaskChip) {
                    ThreadChip(
                        label = message.task?.let { "#${it.taskNumber ?: "task"}" } ?: "#9",
                        color = message.task?.status?.threadColor ?: SlockColor.AccentLavender,
                        icon = SlockIcons.Square
                    )
                }
            }
            Text(
                text = message.body,
                style = SlockType.Body,
                color = SlockColor.TextBlack
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                if (showMentionChip) {
                    ThreadChip("@API-Integration-Developer", SlockColor.AccentLavender)
                }
                if (showCodeChip) {
                    ThreadChip("ScreenLoadState", SlockColor.BrandYellow)
                }
                if (message.task != null && !showTaskChip) {
                    ThreadChip(
                        label = "#${message.task.taskNumber ?: "task"}",
                        color = message.task.status.threadColor,
                        icon = SlockIcons.Square
                    )
                }
                if (message.replyCount > 0) {
                    ThreadChip("${message.replyCount} replies", SlockColor.AppBgLightBlue, SlockIcons.MessageSquare)
                }
                when (message.deliveryState) {
                    MessageDeliveryState.Sending -> ThreadChip("Sending", SlockColor.BrandYellow)
                    MessageDeliveryState.Failed -> ThreadChip("Failed", SlockColor.AccentRed)
                    MessageDeliveryState.Sent -> Unit
                }
            }
        }
    }
}

@Composable
private fun ThreadAvatar(label: String, kind: MemberKind) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (kind == MemberKind.Human) SlockColor.AccentLavender else SlockColor.AccentPink)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Icon(SlockIcons.User, contentDescription = null, tint = SlockColor.TextBlack, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ThreadChip(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier
            .background(color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = SlockColor.TextBlack, modifier = Modifier.size(12.dp))
        }
        Text(label, style = SlockType.Caption, color = SlockColor.TextBlack, maxLines = 2)
    }
}

@Composable
fun ThreadComposerBar(
    composerState: ComposerState,
    placeholder: String,
    onComposerChange: (String) -> Unit,
    onAsTaskChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = composerState.enabled &&
        composerState.status != ComposerStatus.NoPermission &&
        composerState.noPermissionCode == null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        val statusLabel = composerState.statusLabel
        if (statusLabel != null) {
            Text(
                text = statusLabel,
                style = SlockType.Caption,
                color = if (composerState.status == ComposerStatus.Failed) {
                    SlockColor.AccentRed
                } else {
                    SlockColor.TextMutedGray
                }
            )
        }
        SlockTextField(
            value = composerState.value,
            onValueChange = onComposerChange,
            placeholder = placeholder,
            enabled = enabled,
            singleLine = false,
            minLines = 1,
            maxLines = 4,
            reserveErrorSpace = false
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactIconButton(SlockIcons.ImagePlus, "Attach image", enabled = enabled)
            Spacer(modifier = Modifier.width(SlockSpacing.Sm))
            CompactIconButton(SlockIcons.Paperclip, "Attach file", enabled = enabled)
            Spacer(modifier = Modifier.weight(1f))
            if (composerState.attachmentIds.isNotEmpty()) {
                Text(
                    text = "${composerState.attachmentIds.size} files",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
                Spacer(modifier = Modifier.width(SlockSpacing.Md))
            }
            AsTaskToggle(
                checked = composerState.asTask,
                enabled = enabled,
                onCheckedChange = onAsTaskChange
            )
            Spacer(modifier = Modifier.width(SlockSpacing.Md))
            ThreadSendButton(
                state = composerState.sendVisualState,
                onClick = onSend
            )
        }
    }
}

@Composable
private fun ThreadSendButton(
    state: ComposerSendVisualState,
    onClick: () -> Unit
) {
    val enabled = state == ComposerSendVisualState.Ready || state == ComposerSendVisualState.Failed
    Row(
        modifier = Modifier
            .heightIn(min = 24.dp)
            .background(SlockColor.AccentPink, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = state.label,
            style = SlockType.Caption,
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        Icon(
            imageVector = SlockIcons.Send,
            contentDescription = null,
            tint = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun AsTaskToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SlockLabeledCheckbox(
        label = "As Task",
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        size = 18.dp
    )
}

private enum class ComposerSendVisualState(val label: String) {
    IdleEmpty("Send"),
    Ready("Send"),
    Uploading("Uploading"),
    Sending("Sending"),
    Failed("Retry"),
    NoPermission("Send")
}

private val ComposerState.sendVisualState: ComposerSendVisualState
    get() = when (status) {
        ComposerStatus.IdleEmpty -> ComposerSendVisualState.IdleEmpty
        ComposerStatus.Ready -> ComposerSendVisualState.Ready
        ComposerStatus.Uploading -> ComposerSendVisualState.Uploading
        ComposerStatus.Sending -> ComposerSendVisualState.Sending
        ComposerStatus.Failed -> ComposerSendVisualState.Failed
        ComposerStatus.NoPermission -> ComposerSendVisualState.NoPermission
    }

private val ComposerState.statusLabel: String?
    get() = when (status) {
        ComposerStatus.Uploading -> "Uploading attachment"
        ComposerStatus.Sending -> "Sending message"
        ComposerStatus.Failed -> "Send failed: ${errorCode ?: "retry"}"
        ComposerStatus.NoPermission -> "No permission: ${noPermissionCode ?: "channel_readonly"}"
        ComposerStatus.IdleEmpty,
        ComposerStatus.Ready -> null
    }

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadScreenPreview() {
    SlockTheme {
        ThreadScreen(
            state = SlockStateSamples.threadContent
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadScreenLongTitlePreview() {
    SlockTheme {
        val baseState = SlockStateSamples.threadContent
        val content = (baseState.loadState as ScreenLoadState.Content).data
        ThreadScreen(
            state = baseState.copy(
                loadState = ScreenLoadState.Content(
                    content.copy(
                        header = content.header.copy(
                            name = "very-long-channel-name-for-mobile-overflow-check",
                            subtitle = "Members, settings, leave actions must stay visible"
                        )
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadScreenTasksTabPreview() {
    SlockTheme {
        ThreadScreen(
            state = SlockStateSamples.threadContent.copy(selectedTab = ThreadTab.Tasks)
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadMembersModalPreview() {
    SlockTheme {
        ThreadMembersModal(
            members = threadMemberModalItems(),
            showAddMember = false,
            addMemberQuery = "",
            addMemberError = false,
            onAddMemberQueryChange = {},
            onAddMemberClick = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadSettingsModalPreview() {
    SlockTheme {
        ThreadSettingsModal(
            initialName = "android-dev",
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun ThreadSettingsModalStatesPreview() {
    SlockTheme {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            ThreadSettingsModal(
                initialName = "android-dev",
                status = ThreadSettingsModalStatus.Saving,
                onDismiss = {}
            )
            ThreadSettingsModal(
                initialName = "android-dev",
                status = ThreadSettingsModalStatus.Error,
                onDismiss = {}
            )
            ThreadSettingsModal(
                initialName = "android-dev",
                status = ThreadSettingsModalStatus.NoPermission,
                onDismiss = {}
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun ThreadMembersModalStatesPreview() {
    SlockTheme {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            ThreadMembersModal(
                members = emptyList(),
                showAddMember = false,
                addMemberQuery = "",
                addMemberError = false,
                onAddMemberQueryChange = {},
                onAddMemberClick = {},
                onDismiss = {}
            )
            ThreadMembersModal(
                members = threadMemberModalItems(),
                showAddMember = true,
                addMemberQuery = "",
                addMemberError = true,
                onAddMemberQueryChange = {},
                onAddMemberClick = {},
                onDismiss = {}
            )
            ThreadMembersModal(
                members = threadMemberModalItems(),
                showAddMember = true,
                addMemberQuery = "@viewer",
                addMemberError = false,
                onAddMemberQueryChange = {},
                onAddMemberClick = {},
                onDismiss = {},
                loading = true
            )
            ThreadMembersModal(
                members = threadMemberModalItems(),
                showAddMember = true,
                addMemberQuery = "@locked",
                addMemberError = false,
                onAddMemberQueryChange = {},
                onAddMemberClick = {},
                onDismiss = {},
                enabled = false,
                noPermission = true
            )
        }
    }
}

private val TaskStatus.threadColor: Color
    get() = when (this) {
        TaskStatus.Todo -> SlockColor.AccentOrange
        TaskStatus.InProgress -> SlockColor.AccentCyan
        TaskStatus.InReview -> SlockColor.BrandYellow
        TaskStatus.Done -> SlockColor.AccentLime
    }

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ThreadScreenStatesPreview() {
    SlockTheme {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            ThreadScreen(
                state = SlockStateSamples.threadBranches[2],
                modifier = Modifier.heightIn(min = 520.dp)
            )
            ThreadScreen(
                state = SlockStateSamples.threadBranches[3],
                modifier = Modifier.heightIn(min = 520.dp)
            )
            ThreadScreen(
                state = SlockStateSamples.threadBranches[4],
                modifier = Modifier.heightIn(min = 520.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ThreadComposerStatesPreview() {
    SlockTheme {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            SlockStateSamples.composerBranches.forEach { state ->
                ThreadComposerBar(
                    composerState = state.composerState,
                    placeholder = "#onboarding-artin",
                    onComposerChange = {},
                    onAsTaskChange = {},
                    onSend = {}
                )
            }
        }
    }
}
