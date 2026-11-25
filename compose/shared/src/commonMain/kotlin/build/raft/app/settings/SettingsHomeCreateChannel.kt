package build.raft.app

import build.raft.app.data.ArchivedNameCollision
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import androidx.compose.foundation.text.KeyboardActions
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import androidx.compose.ui.input.key.key
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.app.components.rememberRaftTextareaEditorModifier
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.isSubmittableChannelName
import build.raft.app.data.SettingsDiagnosticsFeedbackDraft
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.KeyboardParams
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import kotlinx.coroutines.launch

internal const val DiagnosticsFeedbackComposerSeedText: String =
    "Diagnostics zip attached.\n\n<!-- Describe what happened here. -->"

internal fun SettingsDiagnosticsFeedbackDraft.diagnosticsComposerMessage(
    seedText: String = DiagnosticsFeedbackComposerSeedText
): String = seedText

@Composable
internal fun SharedCreateChannelDialog(
    submitting: Boolean,
    membersLoading: Boolean,
    members: List<MemberProfile>,
    errorMessage: String?,
    errorTraceId: String? = null,
    onExternalCommitStart: () -> Unit = {},
    archivedCollision: ArchivedNameCollision? = null,
    onOpenArchivedCollision: () -> Unit = {},
    onUnarchiveArchivedCollision: () -> Unit = {},
    onClearArchivedCollision: () -> Unit = {},
    initialName: String = "",
    initialDescription: TextFieldValue = TextFieldValue(""),
    initialVisibility: SharedCreateChannelVisibility = SharedCreateChannelVisibility.Public,
    initialSelectedMemberKeys: Set<RaftMemberPickerKey> = emptySet(),
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    /** Web `useExternalChannelCreateAccess`: External is offered ([externalStore] reads the rest). */
    externalStore: CreateExternalChannelStore? = null,
    serverName: String = "",
    /** At the Free plan's one external channel: Upgrade for more. */
    onExternalUpgrade: () -> Unit = {},
    onDismiss: () -> Unit,
    onSubmit: (String, String?, String, List<String>, List<String>) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    // Web: External asked for by a viewer without it falls back to Private.
    var visibility by remember(initialVisibility) {
        mutableStateOf(
            if (initialVisibility == SharedCreateChannelVisibility.External && externalStore == null) {
                SharedCreateChannelVisibility.Private
            } else {
                initialVisibility
            }
        )
    }
    var step by remember { mutableStateOf(SharedCreateChannelStep.Details) }
    val externalState = externalStore?.state?.collectAsState()?.value
    val externalAvailability = externalState?.availability
    // Web `external` / `externalAtLimit`: External chosen and open, or chosen at the Free limit.
    val external = visibility == SharedCreateChannelVisibility.External && externalAvailability == ExternalChannelCreateAvailability.Open
    val externalAtLimit = visibility == SharedCreateChannelVisibility.External && externalAvailability == ExternalChannelCreateAvailability.FreeLimit
    val externalLoading = visibility == SharedCreateChannelVisibility.External && externalAvailability == ExternalChannelCreateAvailability.Loading
    LaunchedEffect(externalStore) { externalStore?.dispatch(CreateExternalChannelIntent.Start) }
    // External taken away while chosen (the viewer is no longer an owner or admin): Private,
    // said on the control, never a silent plain create behind an External label.
    LaunchedEffect(externalStore == null) {
        if (externalStore == null && visibility == SharedCreateChannelVisibility.External) {
            visibility = SharedCreateChannelVisibility.Private
            if (step == SharedCreateChannelStep.People) step = SharedCreateChannelStep.Details
        }
    }
    LaunchedEffect(externalStore, external) { if (external) externalStore?.dispatch(CreateExternalChannelIntent.LoadServers) }
    // Web handleCreateError: an archived-name collision goes back to step 1, where the name is.
    LaunchedEffect(archivedCollision) { if (archivedCollision != null) step = SharedCreateChannelStep.Details }
    // Caller-local TextFieldValue owns the member-search editor for this
    // dialog's mount; filtering reads the String projection (task #130 Phase D).
    var memberSearch by remember { mutableStateOf(TextFieldValue()) }
    var selectedMemberKeys by remember(initialSelectedMemberKeys) {
        mutableStateOf(initialSelectedMemberKeys)
    }
    val createChannelListState = rememberLazyListState()
    val createChannelScrollScope = rememberCoroutineScope()
    var createChannelViewport by remember { mutableStateOf<Rect?>(null) }
    val createChannelScrollCoordinator = remember {
        RaftFocusedEditorScrollRequestCoordinator()
    }
    DisposableEffect(createChannelScrollCoordinator) {
        onDispose { createChannelScrollCoordinator.invalidate() }
    }
    var memberSearchFocused by remember { mutableStateOf(false) }
    // The member picker's item in the list below: after name, description and visibility, and
    // after the archived-name warning when one is shown above them.
    // On step 2 it follows the emails and "Your servers" of an External channel.
    val memberPickerItemIndex =
        when {
            step == SharedCreateChannelStep.People ->
                if (visibility == SharedCreateChannelVisibility.External && externalAvailability == ExternalChannelCreateAvailability.Open) 2 else 0
            archivedCollision != null -> 4
            else -> 3
        }

    fun scrollToMemberPicker() {
        val requestGeneration = createChannelScrollCoordinator.begin()
        val job = createChannelScrollScope.launch {
            raftUnifiedDialogScrollToTargetItem(
                keyboardHeight = 1f,
                targetItemIndex = memberPickerItemIndex,
                targetItemScrollOffset = 0,
                awaitRelayoutFrame = { withFrameNanos { } },
                scrollToItem = { index, scrollOffset ->
                    createChannelListState.scrollToItem(index, scrollOffset)
                },
                isScrollInProgress = { createChannelListState.isScrollInProgress },
                isRequestCurrent = { createChannelScrollCoordinator.isCurrent(requestGeneration) },
                isTargetActive = { memberSearchFocused }
            )
        }
        createChannelScrollCoordinator.attach(job)
    }

    val trimmedName = name.trim()
    val nameError =
        if (trimmedName.isNotEmpty() && !trimmedName.isSubmittableChannelName()) {
            stringResource(Res.string.channel_create_error_name_format)
        } else {
            null
        }
    val canSubmit = trimmedName.isSubmittableChannelName() && !submitting
    // No Guest: a new channel is never its bound one (403 guest_other_channel; web isAddableToNewChannel, #15362).
    val selectableMembers =
        members.filter { (it.kind == MemberKind.Human || it.kind == MemberKind.Agent) && it.isAddableToNewChannel() }
    val onDetails = step == SharedCreateChannelStep.Details
    val externalBusy = externalState?.invite?.busy == true
    // The one commit: the footer's Create on a wide window, the phone sheet's head button.
    val commitLabel =
        when {
            onDetails -> stringResource(Res.string.channel_external_invite_next)
            external && externalBusy -> stringResource(Res.string.channel_external_invite_checking)
            external -> stringResource(Res.string.channel_create_external_submit)
            submitting -> stringResource(Res.string.channel_create_action_creating)
            else -> stringResource(Res.string.channel_create_action_create)
        }
    fun selectedMembers() = selectableMembers.filter { it.memberPickerKey in selectedMemberKeys }
    val commit: () -> Unit = {
        when {
            // Step 1's Next: the name is checked here so step 2 only creates.
            step == SharedCreateChannelStep.Details -> step = SharedCreateChannelStep.People
            // The review first (design E1): nothing is created until it is confirmed.
            external -> externalStore?.dispatch(CreateExternalChannelIntent.Review)
            else -> {
                val chosen = selectedMembers()
                onSubmit(
                    name,
                    description.text,
                    if (visibility == SharedCreateChannelVisibility.External) "private" else visibility.wireValue,
                    chosen.filter { it.kind == MemberKind.Agent }.map { it.id.value },
                    chosen.filter { it.kind == MemberKind.Human }.map { it.id.value }
                )
            }
        }
    }
    val commitEnabled =
        when {
            onDetails ->
                trimmedName.isSubmittableChannelName() && !submitting && !externalAtLimit && !externalLoading
            external -> canSubmit && externalState?.canReview == true
            else -> canSubmit
        }
    val reviewInvite = externalState?.invite?.takeIf { external && step == SharedCreateChannelStep.People && it.preview != null }
    if (reviewInvite != null && externalStore != null) {
        SharedCreateExternalChannelReview(
            state = externalState,
            serverName = serverName,
            onCommit = {
                val chosen = selectedMembers()
                onExternalCommitStart()
                externalStore.dispatch(
                    CreateExternalChannelIntent.Commit(
                        CreateExternalChannelDraft(
                            name = trimmedName,
                            description = description.text,
                            agentIds = chosen.filter { it.kind == MemberKind.Agent }.map { it.id.value },
                            userIds = chosen.filter { it.kind == MemberKind.Human }.map { it.id.value },
                        )
                    )
                )
            },
            onBack = { externalStore.dispatch(CreateExternalChannelIntent.BackToPeople) },
            onLeave = {
                if (externalState.created != null) externalStore.dispatch(CreateExternalChannelIntent.Leave) else onDismiss()
            },
            onUpgrade = onExternalUpgrade,
        )
        return
    }
    RaftUnifiedDialog(
        title =
            if (onDetails) {
                stringResource(Res.string.channel_create_title)
            } else {
                stringResource(Res.string.channel_create_people_title, trimmedName)
            },
        dismissEnabled = !submitting,
        dismissOnOutsideTap = false,
        keyboardAvoidanceEnabled = true,
        onDismiss = onDismiss,
        presentation = RaftDialogPresentation.Sheet,
        commitLabel = commitLabel,
        onCommit = commit,
        commitEnabled = commitEnabled
    ) {
        val focusedEditorBounds = rememberRaftFocusedInputBoundsResolver()
        val dialogKeyboardHeightChange = LocalRaftDialogKeyboardHeightChange.current

        // The dialog's own handler first: it lifts the centred card, or shrinks the phone sheet
        // onto the keyboard. Two frames later the list below (the card's or the sheet's body,
        // whichever is drawn) has its keyboard-reduced viewport, and the focused field is kept
        // inside it.
        fun keepFocusedCreateChannelEditorVisible(params: KeyboardParams) {
            dialogKeyboardHeightChange(params)
            val requestGeneration = createChannelScrollCoordinator.begin()
            if (params.height <= 0f) return
            val job = createChannelScrollScope.launch {
                if (memberSearchFocused) {
                    raftUnifiedDialogScrollToTargetItem(
                        keyboardHeight = params.height,
                        targetItemIndex = memberPickerItemIndex,
                        targetItemScrollOffset = 0,
                        awaitRelayoutFrame = { withFrameNanos { } },
                        scrollToItem = { index, scrollOffset ->
                            createChannelListState.scrollToItem(index, scrollOffset)
                        },
                        isScrollInProgress = { createChannelListState.isScrollInProgress },
                        isRequestCurrent = {
                            createChannelScrollCoordinator.isCurrent(requestGeneration)
                        },
                        isTargetActive = { memberSearchFocused }
                    )
                } else {
                    raftUnifiedDialogKeepFocusedEditorVisible(
                        keyboardHeight = params.height,
                        awaitRelayoutFrame = { withFrameNanos { } },
                        focusedBounds = focusedEditorBounds,
                        viewportBounds = { createChannelViewport },
                        currentScrollPosition = {
                            RaftUnifiedDialogListScrollPosition(
                                index = createChannelListState.firstVisibleItemIndex,
                                scrollOffset = createChannelListState.firstVisibleItemScrollOffset
                            )
                        },
                        scrollToItem = { index, scrollOffset ->
                            createChannelListState.scrollToItem(index, scrollOffset)
                        },
                        isScrollInProgress = { createChannelListState.isScrollInProgress },
                        isRequestCurrent = {
                            createChannelScrollCoordinator.isCurrent(requestGeneration)
                        },
                        isTargetActive = { !memberSearchFocused }
                    )
                }
            }
            createChannelScrollCoordinator.attach(job)
        }

        CompositionLocalProvider(
            LocalRaftDialogKeyboardHeightChange provides ::keepFocusedCreateChannelEditorVisible
        ) {
            LazyColumn(
                state = createChannelListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .onGloballyPositioned { createChannelViewport = it.boundsInRoot() },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            if (onDetails) {
            // Web CreateChannelDialog: the archived-name warning sits above the name it is about.
            archivedCollision?.let { collision ->
                item(key = "archived-name-collision") {
                    ArchivedNameCollisionBanner(
                        archivedChannelName = collision.archivedChannelName,
                        canUnarchive = collision.canUnarchive,
                        unarchiving = submitting,
                        onUnarchive = onUnarchiveArchivedCollision,
                        onOpen = onOpenArchivedCollision,
                        // Web `handleChangeCollidingName`: drop the warning and clear the name.
                        onChangeName = {
                            name = ""
                            onClearArchivedCollision()
                        },
                    )
                }
            }
            item {
                RaftField(
                    label = stringResource(Res.string.channel_create_label_name),
                    modifier = Modifier.fillMaxWidth(),
                    description = stringResource(Res.string.channel_create_hint_name_format),
                    error = nameError?.takeIf { it.isNotBlank() },
                    enabled = !submitting,
                    required = true
                ) {
                    val editor =
                        rememberRaftDesignSystemEditor(
                            keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                            keyboardActions = KeyboardActions.Default,
                            keyboardHeightChange = {},
                            focusRequester = remember { FocusRequester() },
                            autoFocus = false,
                            onFocus = {
                                memberSearchFocused = false
                                createChannelScrollCoordinator.invalidate()
                            },
                            onBlur = {}
                        )
                    RaftTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth().then(editor.modifier),
                        placeholder = stringResource(Res.string.channel_create_placeholder_name),
                        keyboardOptions = editor.keyboardOptions
                    )
                }
            }
            item {
                RaftField(
                    label = stringResource(Res.string.channel_create_label_description),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting,
                    optional = true
                ) {
                    // Two field lines (web `<textarea rows={2}>`): 42dp, growing to two 24dp lines in
                    // the textarea's 10dp vertical padding (68dp), then it scrolls.
                    RaftTextarea(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(Res.string.channel_create_placeholder_description),
                        minHeight = 42.dp,
                        maxHeight = maxOf(42.dp, 24.dp * 2 + 20.dp),
                        inputModifier =
                            rememberRaftTextareaEditorModifier(
                                onFocus = {
                                    memberSearchFocused = false
                                    createChannelScrollCoordinator.invalidate()
                                }
                            )
                    )
                }
            }
            item {
                SharedCreateChannelVisibilityControl(
                    selected = visibility,
                    enabled = !submitting,
                    externalOffered = externalStore != null,
                    // Web: External while it is still being checked is ignored.
                    onSelected = { chosen ->
                        if (chosen != SharedCreateChannelVisibility.External || externalAvailability != ExternalChannelCreateAvailability.Loading) {
                            visibility = chosen
                        }
                    },
                    hint =
                        when {
                            external -> stringResource(Res.string.channel_create_external_hint)
                            else -> null
                        },
                    freeLimitServerName = serverName.takeIf { externalAtLimit },
                    onUpgrade = onExternalUpgrade,
                )
            }
            }
            if (!onDetails && external) {
                val invite = externalState?.invite ?: ExternalInviteDialogState()
                item(key = "external-emails") {
                    ExternalInviteEmailsField(
                        text = invite.text,
                        onTextChange = { externalStore?.dispatch(CreateExternalChannelIntent.SetText(it)) },
                        overBy = invite.overBy,
                        enabled = !invite.busy,
                    )
                }
                item(key = "external-servers") {
                    ExternalInviteServersField(
                        servers = invite.servers,
                        values = invite.serverIds,
                        onValuesChange = { externalStore?.dispatch(CreateExternalChannelIntent.SetServers(it)) },
                        enabled = !invite.busy,
                        // A new channel has no earlier messages.
                        historyHint = false,
                    )
                }
                invite.failure?.let { failure ->
                    // Web: step 2 says the refusal as its error line (no Upgrade there).
                    item(key = "external-error") { ExternalInviteFailureBanner(failure.copy(upgrade = false), onUpgrade = {}) }
                }
            }
            if (!onDetails) item {
                SharedCreateChannelMemberPicker(
                    label =
                        if (external) stringResource(Res.string.channel_create_external_members_label, serverName) else null,
                    loading = membersLoading,
                    members = selectableMembers,
                    query = memberSearch,
                    onQueryChange = { memberSearch = it },
                    selectedKeys = selectedMemberKeys,
                    enabled = !submitting,
                    agentStatusResolver = agentStatusResolver,
                    fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
                    onToggle = { key ->
                        selectedMemberKeys = raftMemberPickerToggle(selectedMemberKeys, key, RaftMemberPickerMode.Multiple)
                    },
                    onFocus = {
                        memberSearchFocused = true
                        scrollToMemberPicker()
                    },
                    onBlur = {
                        memberSearchFocused = false
                        createChannelScrollCoordinator.invalidate()
                    }
                )
            }
            if (!errorMessage.isNullOrBlank()) {
                item {
                    val localizedError =
                        if (errorMessage == "channel_name_invalid") {
                            stringResource(Res.string.channel_create_error_name_format)
                        } else {
                            errorMessage
                        }
                    Column {
                        Text(
                            text = Res.string.channel_create_error.format(listOf(localizedError)).localized(),
                            style = RaftType.Caption,
                            color = RaftColor.AccentRed
                        )
                        RaftTraceIdNote(errorTraceId)
                    }
                }
            }
        }
        }
        RaftDialogFooter {
            if (onDetails) {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    enabled = !submitting,
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
            } else {
                // Back keeps everything filled in on both steps (web v4.9#1).
                RaftButton(
                    text = stringResource(Res.string.channel_external_invite_back),
                    enabled = !submitting && !externalBusy,
                    onClick = { step = SharedCreateChannelStep.Details },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
            }
            RaftButton(
                text = commitLabel,
                enabled = commitEnabled,
                modifier = Modifier.widthIn(min = 126.dp),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                onClick = commit
            )
        }
    }
}

/** Web `Step` "details" / "people" (the External review is [SharedCreateExternalChannelReview]). */
private enum class SharedCreateChannelStep { Details, People }

internal enum class SharedCreateChannelVisibility(
    val wireValue: String,
    val label: String,
    val iconName: RaftLucideIconName
) {
    Public("public", "Public", RaftLucideIconName.Hash),
    Private("private", "Private", RaftLucideIconName.Lock),
    /** Web Create Channel's External: created private, then the invites (CreateExternalChannel.kt). */
    External("external", "External", RaftLucideIconName.ExternalChannel);

    companion object {
        fun fromWireValue(value: String?): SharedCreateChannelVisibility =
            values().firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Public
    }
}

@Composable
internal fun SharedCreateChannelVisibilityControl(
    selected: SharedCreateChannelVisibility,
    enabled: Boolean,
    onSelected: (SharedCreateChannelVisibility) -> Unit,
    externalOffered: Boolean = false,
    hint: String? = null,
    /** External chosen at the Free plan's one external channel: its line and Upgrade for more. */
    freeLimitServerName: String? = null,
    onUpgrade: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftFieldLabel(text = stringResource(Res.string.channel_create_label_visibility))
        SegmentedControl(
            selectedValue = selected.name,
            options =
                SharedCreateChannelVisibility.values().filter { it != SharedCreateChannelVisibility.External || externalOffered }.map { option ->
                    SegmentedControlOption(
                        value = option.name,
                        label =
                            when (option) {
                                SharedCreateChannelVisibility.Public -> stringResource(Res.string.channel_create_visibility_public)
                                SharedCreateChannelVisibility.Private -> stringResource(Res.string.channel_create_visibility_private)
                                SharedCreateChannelVisibility.External -> stringResource(Res.string.channel_create_external)
                            },
                        iconName = option.iconName
                    )
                },
            enabled = enabled,
            onValueSelected = { value ->
                SharedCreateChannelVisibility.values()
                    .firstOrNull { it.name == value }
                    ?.let(onSelected)
            }
        )
        if (freeLimitServerName != null) {
            // Web `create-channel-external-free-limit`: the limit line with its Upgrade for more.
            Column(Modifier.testTag(CreateChannelExternalFreeLimitTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    stringResource(Res.string.channel_create_external_free_limit, freeLimitServerName),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted,
                )
                RaftTextLink(
                    text = stringResource(Res.string.channel_create_upgrade_for_more),
                    onClick = onUpgrade,
                    variant = RaftTextLinkVariant.Strong,
                    style = RaftTheme.typography.footnote,
                )
            }
        } else if (hint != null) {
            RaftText(hint, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        }
    }
}

internal const val CreateChannelExternalFreeLimitTag = "create-channel-external-free-limit"

/** Create channel's Members field: its label over the shared [RaftMemberPicker]. */
@Composable
internal fun SharedCreateChannelMemberPicker(
    /** Web `externalMembersLabel` ("{server} members") for an External channel; null: Members. */
    label: String? = null,
    loading: Boolean,
    members: List<MemberProfile>,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    selectedKeys: Set<RaftMemberPickerKey>,
    enabled: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean,
    onToggle: (RaftMemberPickerKey) -> Unit,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftFieldLabel(text = label ?: stringResource(Res.string.channel_create_label_members), optional = true)
        RaftMemberPicker(
            members = members,
            query = query,
            onQueryChange = onQueryChange,
            selectedKeys = selectedKeys,
            onToggle = onToggle,
            loading = loading,
            enabled = enabled,
            agentStatusResolver = agentStatusResolver,
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
            onSearchFocus = onFocus,
            onSearchBlur = onBlur
        )
    }
}
