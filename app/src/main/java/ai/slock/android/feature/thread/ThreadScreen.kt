package ai.slock.android.feature.thread

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import ai.slock.android.core.data.preferences.MyMessageAlignmentPreference
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.ui.components.SlockAvatar
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
import ai.slock.android.ui.components.SlockMemberPickerItem
import ai.slock.android.ui.components.SlockMemberPickerMessage
import ai.slock.android.ui.components.SlockMemberPickerSection
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
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

@Composable
fun ThreadScreen(
    state: ThreadUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onModeSelected: (ThreadTab) -> Unit = {},
    onComposerChange: (String) -> Unit = {},
    onAsTaskChange: (Boolean) -> Unit = {},
    onAttachImage: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onRetryAttachment: () -> Unit = {},
    onSend: () -> Unit = {},
    onLoadOlderMessages: (MessageId?, Int) -> Unit = { _, _ -> },
    onRetryLoadOlderMessages: () -> Unit = {},
    onLoadNewerMessages: () -> Unit = {},
    onRetryLoadNewerMessages: () -> Unit = {},
    onRefreshThread: () -> Unit = {},
    onNearBottomChange: (Boolean) -> Unit = {},
    onOpenMessageThread: (Message) -> Unit = {},
    onOpenMemberProfile: (MemberProfile) -> Unit = {},
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit = {},
    attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState> = emptyMap(),
    attachmentPreviewState: AttachmentPreviewUiState? = null,
    onOpenAttachment: (Attachment) -> Unit = {},
    onDismissAttachmentPreview: () -> Unit = {},
    onOpenAttachmentPreviewExternally: (AttachmentPreviewUiState) -> Unit = {},
    onJoinChannel: (ConversationSummary) -> Unit = {},
    membersPanelState: ThreadMembersPanelState = rememberSampleThreadMembersPanelState(),
    onAddChannelMember: (String, MemberKind) -> Unit = { _, _ -> },
    onRemoveChannelMember: (MemberProfile) -> Unit = {},
    currentMemberId: MemberId? = null,
    myMessageAlignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Default,
    replyHeaderContext: ThreadReplyHeaderContext? = null,
    onOpenReplyHeaderTask: (Int) -> Unit = {}
) {
    var showMembersModal by rememberSaveable { mutableStateOf(false) }
    var showSettingsModal by rememberSaveable { mutableStateOf(false) }
    var showAddMember by rememberSaveable { mutableStateOf(false) }
    var addMemberQuery by rememberSaveable { mutableStateOf("") }
    var selectedAddMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAddMemberKind by rememberSaveable { mutableStateOf(MemberKind.Human) }
    var addMemberError by rememberSaveable { mutableStateOf(false) }
    var selectedChannelMemberDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissComposerKeyboard: () -> Unit = remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    val displayedMembers = membersPanelState.modalMembers
    val selectedChannelMemberDetail = displayedMembers.firstOrNull { it.id.value == selectedChannelMemberDetailId }
    val membersCount = membersPanelState.memberCount
    LaunchedEffect(membersPanelState.loadState) {
        val members = (membersPanelState.loadState as? ScreenLoadState.Content)?.data?.members.orEmpty()
        if (members.isNotEmpty()) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockMembers",
                "render_count source=thread_members_panel agentsCount=${members.count { it.kind == MemberKind.Agent }} " +
                    "humansCount=${members.count { it.kind == MemberKind.Human }}"
            )
        }
    }

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
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = membersCount,
                        onMembersClick = { showMembersModal = true },
                        replyHeaderContext = replyHeaderContext,
                        onOpenReplyHeaderTask = onOpenReplyHeaderTask
                    )
                    ThreadModeTabs(
                        selectedTab = state.selectedTab,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading thread...", style = SlockType.Button, color = SlockColor.TextMutedGray)
                    }
                    ThreadComposerBar(
                        composerState = state.composerState,
                        placeholder = "Message",
                        onComposerChange = onComposerChange,
                        onAsTaskChange = onAsTaskChange,
                        onAttachImage = onAttachImage,
                        onAttachFile = onAttachFile,
                        onRemoveAttachment = onRemoveAttachment,
                        onRetryAttachment = onRetryAttachment,
                        onSend = onSend
                    )
                }
                is ScreenLoadState.Content -> {
                    val header = loadState.data.header
                    val readOnlyPreview = !header.joined
                    val composerState = state.composerState.forHeader(header)
                    ThreadTopHeader(
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                        summary = header,
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = membersCount,
                        onMembersClick = { showMembersModal = true },
                        replyHeaderContext = replyHeaderContext,
                        onOpenReplyHeaderTask = onOpenReplyHeaderTask
                    )
                    ThreadModeTabs(
                        selectedTab = state.selectedTab,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                    )
                    ThreadRefreshBanner(
                        status = loadState.data.refreshStatus,
                        errorCode = loadState.data.refreshErrorCode,
                        onRetry = onRefreshThread
                    )
                    if (state.selectedTab == ThreadTab.Tasks) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Lg),
                            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)
                        ) {
                            ThreadTaskSummary(tasks = loadState.data.tasks)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            ThreadMessageList(
                                content = loadState.data,
                                threadId = header.threadId.value,
                                currentMemberId = currentMemberId,
                                myMessageAlignment = myMessageAlignment,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                                onLoadOlderMessages = onLoadOlderMessages,
                                onRetryLoadOlderMessages = onRetryLoadOlderMessages,
                                onLoadNewerMessages = onLoadNewerMessages,
                                onRetryLoadNewerMessages = onRetryLoadNewerMessages,
                                onNearBottomChange = onNearBottomChange,
                                onOpenMessageThread = onOpenMessageThread,
                                onOpenMemberProfile = onOpenMemberProfile,
                                onOpenMarkdownTag = onOpenMarkdownTag,
                                attachmentOpenStates = attachmentOpenStates,
                                onOpenAttachment = onOpenAttachment
                            )
                        }
                    }
                    if (readOnlyPreview) {
                        ThreadJoinComposerReplacement(
                            header = header,
                            status = state.channelJoinStatus,
                            errorCode = state.channelJoinErrorCode,
                            onJoin = { onJoinChannel(header) }
                        )
                    } else {
                        ThreadComposerBar(
                            composerState = composerState,
                            placeholder = "Message #${header.name}",
                            onComposerChange = onComposerChange,
                            onAsTaskChange = onAsTaskChange,
                            onAttachImage = onAttachImage,
                            onAttachFile = onAttachFile,
                            onRemoveAttachment = onRemoveAttachment,
                            onRetryAttachment = onRetryAttachment,
                            onSend = onSend
                        )
                    }
                }
                is ScreenLoadState.Empty -> {
                    ThreadTopHeader(
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = membersCount,
                        onMembersClick = { showMembersModal = true },
                        replyHeaderContext = replyHeaderContext,
                        onOpenReplyHeaderTask = onOpenReplyHeaderTask
                    )
                    ThreadModeTabs(
                        selectedTab = state.selectedTab,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockEmptyState(
                            title = "No messages yet",
                            detail = "Start the conversation when you are ready."
                        )
                    }
                    ThreadComposerBar(
                        composerState = state.composerState,
                        placeholder = "Message",
                        onComposerChange = onComposerChange,
                        onAsTaskChange = onAsTaskChange,
                        onAttachImage = onAttachImage,
                        onAttachFile = onAttachFile,
                        onRemoveAttachment = onRemoveAttachment,
                        onRetryAttachment = onRetryAttachment,
                        onSend = onSend
                    )
                }
                is ScreenLoadState.Error -> {
                    ThreadTopHeader(
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = membersCount,
                        onMembersClick = { showMembersModal = true },
                        replyHeaderContext = replyHeaderContext,
                        onOpenReplyHeaderTask = onOpenReplyHeaderTask
                    )
                    ThreadModeTabs(
                        selectedTab = state.selectedTab,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockErrorState(
                            title = "Thread failed to load",
                            detail = "Error code: ${loadState.error.code}",
                            onRetry = {}
                        )
                    }
                    ThreadComposerBar(
                        composerState = state.composerState.copy(enabled = false),
                        placeholder = "Message",
                        onComposerChange = onComposerChange,
                        onAsTaskChange = onAsTaskChange,
                        onAttachImage = onAttachImage,
                        onAttachFile = onAttachFile,
                        onRemoveAttachment = onRemoveAttachment,
                        onRetryAttachment = onRetryAttachment,
                        onSend = onSend
                    )
                }
                is ScreenLoadState.NoPermission -> {
                    ThreadTopHeader(
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                        onBack = onBack,
                        onSettingsClick = { showSettingsModal = true },
                        membersCount = membersCount,
                        onMembersClick = { showMembersModal = true },
                        replyHeaderContext = replyHeaderContext,
                        onOpenReplyHeaderTask = onOpenReplyHeaderTask
                    )
                    ThreadModeTabs(
                        selectedTab = state.selectedTab,
                        onModeSelected = onModeSelected,
                        modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                            .padding(SlockSpacing.Md),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockEmptyState(
                            title = "No permission",
                            detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                        )
                    }
                    ThreadComposerBar(
                        composerState = state.composerState.copy(enabled = false, noPermissionCode = loadState.blocker.code),
                        placeholder = "Message",
                        onComposerChange = onComposerChange,
                        onAsTaskChange = onAsTaskChange,
                        onAttachImage = onAttachImage,
                        onAttachFile = onAttachFile,
                        onRemoveAttachment = onRemoveAttachment,
                        onRetryAttachment = onRetryAttachment,
                        onSend = onSend
                    )
                }
            }
        }

        if (showMembersModal) {
            if (selectedChannelMemberDetail != null) {
                BackHandler(enabled = true) {
                    selectedChannelMemberDetailId = null
                }
                ThreadMemberDetailModal(
                    member = selectedChannelMemberDetail,
                    removeStatus = membersPanelState.removeStatus,
                    removingMemberId = membersPanelState.removingMemberId,
                    removeErrorCode = membersPanelState.removeErrorCode,
                    onBack = { selectedChannelMemberDetailId = null },
                    onRemoveMemberClick = onRemoveChannelMember,
                    onDismiss = {
                        showMembersModal = false
                        selectedChannelMemberDetailId = null
                        showAddMember = false
                        addMemberQuery = ""
                        selectedAddMemberId = null
                        addMemberError = false
                    }
                )
            } else {
                ThreadMembersModal(
                    members = displayedMembers,
                    showAddMember = showAddMember,
                    addMemberQuery = addMemberQuery,
                    selectedAddMemberId = selectedAddMemberId,
                    addMemberError = addMemberError,
                    membersLoadState = membersPanelState.loadState,
                    candidatesLoadState = membersPanelState.candidatesLoadState,
                    addMemberStatus = membersPanelState.addStatus,
                    addMemberErrorCode = membersPanelState.addErrorCode,
                    removeStatus = membersPanelState.removeStatus,
                    removingMemberId = membersPanelState.removingMemberId,
                    removeErrorCode = membersPanelState.removeErrorCode,
                    onAddMemberQueryChange = {
                        addMemberQuery = it
                        selectedAddMemberId = null
                        addMemberError = false
                    },
                    onAddMemberSelected = { member ->
                        selectedAddMemberId = member.id.value
                        selectedAddMemberKind = member.kind
                        addMemberError = false
                    },
                    onMemberClick = { member ->
                        selectedChannelMemberDetailId = member.id.value
                    },
                    onRemoveMemberClick = onRemoveChannelMember,
                    onAddMemberClick = {
                        val memberId = selectedAddMemberId
                        if (showAddMember && memberId == null) {
                            addMemberError = true
                        } else if (showAddMember) {
                            onAddChannelMember(memberId.orEmpty(), selectedAddMemberKind)
                            addMemberError = false
                        } else {
                            showAddMember = true
                        }
                    },
                    onDismiss = {
                        showMembersModal = false
                        selectedChannelMemberDetailId = null
                        showAddMember = false
                        addMemberQuery = ""
                        selectedAddMemberId = null
                        addMemberError = false
                    }
                )
            }
        }
        if (showSettingsModal) {
            ThreadSettingsModal(
                initialName = (state.loadState as? ScreenLoadState.Content)?.data?.header?.name ?: "onboarding-artin",
                onDismiss = { showSettingsModal = false }
            )
        }
        val previewState = attachmentPreviewState
        if (previewState != null && previewState.status != AttachmentPreviewStatus.Idle) {
            ThreadAttachmentPreviewModal(
                state = previewState,
                onDismiss = onDismissAttachmentPreview,
                onOpenExternally = { onOpenAttachmentPreviewExternally(previewState) }
            )
        }
    }
}

private fun ComposerState.forHeader(header: ConversationSummary): ComposerState =
    if (header.joined && header.permissions.canSend) {
        this
    } else {
        copy(
            enabled = false,
            noPermissionCode = if (!header.joined) "join_required" else "read_only"
        )
    }

@Composable
private fun ThreadRefreshBanner(
    status: ThreadRefreshStatus,
    errorCode: String?,
    onRetry: () -> Unit
) {
    if (status == ThreadRefreshStatus.Idle) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = threadHorizontalPadding(), vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        if (status == ThreadRefreshStatus.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = SlockColor.TextBlack
            )
        } else {
            Icon(
                imageVector = SlockIcons.RefreshCw,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(14.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (status == ThreadRefreshStatus.Loading) {
                    "Refreshing messages..."
                } else {
                    "Refresh failed"
                },
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (status == ThreadRefreshStatus.Error && !errorCode.isNullOrBlank()) {
                Text(
                    text = "Code: $errorCode",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (status == ThreadRefreshStatus.Error) {
            SlockButton(
                text = "Retry",
                onClick = onRetry,
                variant = SlockButtonVariant.Outline,
                modifier = Modifier.widthIn(min = 88.dp)
            )
        }
    }
}

private fun Modifier.dismissKeyboardOnPointerDown(onDismissKeyboard: () -> Unit): Modifier =
    pointerInput(onDismissKeyboard) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            onDismissKeyboard()
        }
    }

@Composable
private fun rememberSampleThreadMembersPanelState(): ThreadMembersPanelState =
    remember {
        ThreadMembersPanelState(
            loadState = ScreenLoadState.Content(
                ThreadMembersPanelContent(
                    members = threadSampleMemberProfiles(),
                    canAddMembers = true
                )
            )
        )
    }

@Composable
private fun ThreadJoinComposerReplacement(
    header: ConversationSummary,
    status: ChannelJoinStatus,
    errorCode: String?,
    onJoin: () -> Unit
) {
    val joinEnabled = status != ChannelJoinStatus.Joining && status != ChannelJoinStatus.NoPermission
    val bottomPadding = threadComposerBottomPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(SlockColor.BorderBlack)
        )
        SlockButton(
            text = when (status) {
                ChannelJoinStatus.Joining -> "Joining #${header.name}"
                ChannelJoinStatus.NoPermission -> "Cannot join #${header.name}"
                else -> "Join #${header.name}"
            },
            onClick = onJoin,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = threadHorizontalPadding(),
                    top = SlockSpacing.Md,
                    end = threadHorizontalPadding(),
                    bottom = bottomPadding
                ),
            variant = SlockButtonVariant.Pink,
            enabled = joinEnabled,
            leadingContent = {
                Icon(
                    imageVector = SlockIcons.LogIn,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(14.dp)
                )
            }
        )
    }
}

@Composable
fun ThreadTopHeader(
    modifier: Modifier = Modifier,
    summary: ConversationSummary? = null,
    onBack: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    membersCount: Int = 4,
    onMembersClick: () -> Unit = {},
    replyHeaderContext: ThreadReplyHeaderContext? = null,
    onOpenReplyHeaderTask: (Int) -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val mobileLayout = maxWidth < 420.dp
        val horizontalPadding = threadHorizontalPadding()
        val subtitleMaxLines = if (mobileLayout) 2 else 1
        val title = if (replyHeaderContext != null) {
            "Threads"
        } else {
            summary?.name ?: "onboarding-artin"
        }
        val subtitle = if (replyHeaderContext != null) {
            replyHeaderContext.replyHeaderSubtitle()
        } else {
            summary?.subtitle
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SlockSize.PageHeaderHeight)
                .background(SlockColor.SurfaceWhite)
                .border(width = SlockStroke.Thin, color = SlockColor.BorderBlack)
                .padding(horizontal = horizontalPadding, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            HeaderIconButton(SlockIcons.ArrowLeft, "Back", onClick = onBack)
            ThreadHashTile()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (mobileLayout) 2.dp else 0.dp)
            ) {
                Text(
                    text = title,
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank() || replyHeaderContext?.taskNumber != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                    ) {
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray,
                                maxLines = subtitleMaxLines,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        replyHeaderContext?.taskNumber?.let { taskNumber ->
                            ThreadChip(
                                label = "#$taskNumber",
                                color = SlockColor.AccentLavender,
                                icon = SlockIcons.ListTodo,
                                onClick = { onOpenReplyHeaderTask(taskNumber) }
                            )
                        }
                    }
                }
            }
            HeaderIconButton(SlockIcons.Settings, "Settings", onClick = onSettingsClick)
            HeaderIconButton(SlockIcons.LogOut, "Leave")
            ThreadMembersButton(count = membersCount, onClick = onMembersClick)
        }
    }
}

private fun ThreadReplyHeaderContext.replyHeaderSubtitle(): String? {
    val parentChannel = parentChannelName
        ?.takeIf { it.isNotBlank() }
        ?.let { name -> if (name.startsWith("#")) name else "#$name" }
    val parentPreview = parentMessagePreview?.takeIf { it.isNotBlank() }
    val origin = listOfNotNull(parentChannel, parentPreview)
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
    return origin?.let { "Origin: $it" }
}

private data class ThreadMemberModalItem(
    val profile: MemberProfile,
    val id: MemberId,
    val name: String,
    val handle: String,
    val role: String,
    val avatarLabel: String,
    val avatarUrl: String?,
    val avatar: AvatarReference?,
    val kind: MemberKind,
    val presence: Presence
)

private fun ThreadMemberModalItem.toSlockMemberPickerItem(): SlockMemberPickerItem =
    SlockMemberPickerItem(
        id = id.value,
        name = name,
        handle = handle,
        role = role,
        avatarLabel = avatarLabel,
        avatarUrl = avatarUrl,
        avatar = avatar,
        kind = kind,
        presence = presence
    )

private val ThreadMembersPanelState.modalMembers: List<ThreadMemberModalItem>
    get() = when (val state = loadState) {
        is ScreenLoadState.Content -> state.data.members.map { it.toThreadMemberModalItem() }
        else -> emptyList()
    }

private val ThreadMembersPanelState.memberCount: Int
    get() = (loadState as? ScreenLoadState.Content)?.data?.members?.size ?: 0

private fun MemberProfile.toThreadMemberModalItem(): ThreadMemberModalItem =
    ThreadMemberModalItem(
        profile = this,
        id = id,
        name = displayName,
        handle = handle,
        role = roleDescription ?: when (kind) {
            MemberKind.Agent -> "Agent"
            MemberKind.Human -> "Member"
            MemberKind.System -> "System"
        },
        avatarLabel = avatarLabel,
        avatarUrl = avatarUrl,
        avatar = avatar,
        kind = kind,
        presence = presence
    )

private fun threadSampleMemberProfiles(): List<MemberProfile> = listOf(
    MemberProfile(
        id = MemberId("sample-agent-android"),
        displayName = "Android-Developer",
        handle = "@android-developer",
        kind = MemberKind.Agent,
        roleDescription = "UI components",
        avatarUrl = "pixel:robot",
        avatarLabel = "A",
        presence = Presence.Active
    ),
    MemberProfile(
        id = MemberId("sample-agent-api"),
        displayName = "API-Integration-Developer",
        handle = "@api-integration-developer",
        kind = MemberKind.Agent,
        roleDescription = "Data contracts",
        avatarUrl = "random:api-integration-developer",
        avatarLabel = "I",
        presence = Presence.Away
    ),
    MemberProfile(
        id = MemberId("sample-human-artin"),
        displayName = "artin",
        handle = "@artin",
        kind = MemberKind.Human,
        roleDescription = "Product owner",
        avatarLabel = "A",
        presence = Presence.Active
    ),
    MemberProfile(
        id = MemberId("sample-human-qa"),
        displayName = "QA-Tester",
        handle = "@qa-tester",
        kind = MemberKind.Human,
        roleDescription = "Acceptance",
        avatarUrl = "pixel:star",
        avatarLabel = "Q",
        presence = Presence.Offline
    )
)

private fun threadMemberModalItems(): List<ThreadMemberModalItem> =
    threadSampleMemberProfiles().map { it.toThreadMemberModalItem() }

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
private fun ThreadAttachmentPreviewModal(
    state: AttachmentPreviewUiState,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit
) {
    val title = state.name?.takeIf { it.isNotBlank() } ?: "Image preview"
    SlockModal(
        title = title,
        onDismiss = onDismiss,
        errorText = when (state.errorCode) {
            null -> null
            "no_handler" -> "No app can open this image externally."
            "missing_url" -> "Image preview URL is missing."
            else -> "Preview error: ${state.errorCode}"
        },
        secondaryAction = SlockModalAction(
            text = "Close",
            onClick = onDismiss,
            style = SlockModalActionStyle.Outline
        ),
        primaryAction = SlockModalAction(
            text = "Open externally",
            onClick = onOpenExternally,
            enabled = !state.url.isNullOrBlank()
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            ThreadAttachmentRemoteImagePreview(
                url = state.url,
                name = title
            )
            Text(
                text = listOfNotNull(
                    state.mimeType?.takeIf { it.isNotBlank() },
                    state.attachmentId?.value
                ).joinToString(" · "),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ThreadAttachmentRemoteImagePreview(
    url: String?,
    name: String
) {
    val imageBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                url
                    ?.takeIf { it.isNotBlank() }
                    ?.let { URL(it).openStream().use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() } }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .background(SlockColor.AppBgLightBlue, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clip(RoundedCornerShape(SlockRadius.Xs)),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap!!,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .padding(SlockSpacing.Sm)
            )
        } else if (url.isNullOrBlank()) {
            Icon(
                imageVector = SlockIcons.Image,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier.size(40.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = SlockColor.TextBlack,
                trackColor = SlockColor.SurfaceWhite
            )
        }
    }
}

@Composable
private fun ThreadMembersModal(
    members: List<ThreadMemberModalItem>,
    showAddMember: Boolean,
    addMemberQuery: String,
    selectedAddMemberId: String? = null,
    addMemberError: Boolean,
    membersLoadState: ScreenLoadState<ThreadMembersPanelContent> = ScreenLoadState.Content(
        ThreadMembersPanelContent(threadSampleMemberProfiles())
    ),
    candidatesLoadState: ScreenLoadState<ThreadMembersPickerContent> = ScreenLoadState.Content(
        ThreadMembersPickerContent(threadSampleMemberProfiles())
    ),
    addMemberStatus: ThreadMemberAddStatus = ThreadMemberAddStatus.Idle,
    addMemberErrorCode: String? = null,
    removeStatus: ThreadMemberRemoveStatus = ThreadMemberRemoveStatus.Idle,
    removingMemberId: MemberId? = null,
    removeErrorCode: String? = null,
    onAddMemberQueryChange: (String) -> Unit,
    onAddMemberSelected: (ThreadMemberModalItem) -> Unit = {},
    onMemberClick: (MemberProfile) -> Unit = {},
    onRemoveMemberClick: (MemberProfile) -> Unit = {},
    onAddMemberClick: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    noPermission: Boolean = false
) {
    val membersLoading = loading || membersLoadState is ScreenLoadState.Loading
    val addLoading = addMemberStatus == ThreadMemberAddStatus.Adding
    val addBlocked = noPermission ||
        addMemberStatus == ThreadMemberAddStatus.NoPermission ||
        (membersLoadState as? ScreenLoadState.Content)?.data?.canAddMembers == false ||
        membersLoadState is ScreenLoadState.NoPermission
    val selectedMemberAlreadyJoined = selectedAddMemberId != null &&
        members.any { it.id.value == selectedAddMemberId }
    SlockModal(
        title = "MEMBERS (${members.size})",
        onDismiss = onDismiss,
        enabled = enabled && !membersLoading && !addLoading,
        errorText = when {
            addBlocked -> "You do not have permission to add members."
            addMemberError -> "Select a member before adding."
            addMemberStatus == ThreadMemberAddStatus.Failed -> "Unable to add member: ${addMemberErrorCode ?: "member_add_failed"}"
            removeStatus == ThreadMemberRemoveStatus.Failed -> "Unable to remove member: ${removeErrorCode ?: "member_remove_failed"}"
            membersLoadState is ScreenLoadState.Error -> "Unable to load members: ${membersLoadState.error.code}"
            membersLoadState is ScreenLoadState.NoPermission -> "Unable to load members: ${membersLoadState.blocker.code}"
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
                membersLoading -> {
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
                        members = members.filter { it.kind == MemberKind.Agent },
                        onMemberClick = onMemberClick,
                        onRemoveMemberClick = onRemoveMemberClick
                    )
                    ThreadMemberSection(
                        label = "HUMANS",
                        members = members.filter { it.kind == MemberKind.Human },
                        onMemberClick = onMemberClick,
                        onRemoveMemberClick = onRemoveMemberClick
                    )
                }
            }
            if (showAddMember) {
                SlockModalTextField(
                    value = addMemberQuery,
                    onValueChange = onAddMemberQueryChange,
                    label = "Search people",
                    placeholder = "Search users or agents",
                    enabled = enabled && !membersLoading && !addLoading && !addBlocked,
                    isError = addMemberError,
                    errorText = if (addMemberError) "Select a member before adding." else null,
                    required = true
                )
                ThreadMemberPicker(
                    candidatesLoadState = candidatesLoadState,
                    currentMembers = members,
                    query = addMemberQuery,
                    selectedMemberId = selectedAddMemberId,
                    enabled = enabled && !membersLoading && !addLoading && !addBlocked,
                    onMemberSelected = onAddMemberSelected
                )
            }
            SlockButton(
                text = if (showAddMember) "Add Selected" else "+ Add Member",
                onClick = onAddMemberClick,
                modifier = Modifier.fillMaxWidth(),
                variant = SlockButtonVariant.Pink,
                enabled = enabled &&
                    !membersLoading &&
                    !addBlocked &&
                    (!showAddMember || (selectedAddMemberId != null && !selectedMemberAlreadyJoined)),
                loading = addLoading
            )
        }
    }
}

@Composable
private fun ThreadMemberDetailModal(
    member: ThreadMemberModalItem,
    removeStatus: ThreadMemberRemoveStatus,
    removingMemberId: MemberId?,
    removeErrorCode: String?,
    onBack: () -> Unit,
    onRemoveMemberClick: (MemberProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val isRemoving = removeStatus == ThreadMemberRemoveStatus.Removing && removingMemberId == member.id
    SlockModal(
        title = member.name.uppercase(),
        onDismiss = onDismiss,
        enabled = !isRemoving,
        errorText = when {
            removeStatus == ThreadMemberRemoveStatus.Failed && removingMemberId == member.id ->
                "Unable to remove agent: ${removeErrorCode ?: "member_remove_failed"}"
            removeStatus == ThreadMemberRemoveStatus.NoPermission && removingMemberId == member.id ->
                "Unable to remove agent: no permission"
            else -> null
        },
        secondaryAction = SlockModalAction(
            text = "Back",
            onClick = onBack,
            style = SlockModalActionStyle.Outline,
            enabled = !isRemoving
        ),
        primaryAction = if (member.kind == MemberKind.Agent) {
            SlockModalAction(
                text = "Remove Agent",
                onClick = { onRemoveMemberClick(member.profile) },
                style = SlockModalActionStyle.Danger,
                enabled = !isRemoving
            )
        } else {
            null
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.size(46.dp)) {
                    SlockAvatar(
                        avatarUrl = member.avatarUrl,
                        avatar = member.avatar,
                        label = member.avatarLabel,
                        modifier = Modifier.fillMaxSize(),
                        size = 46.dp,
                        fontSize = SlockType.Button.fontSize,
                        fallbackColor = if (member.kind == MemberKind.Agent) {
                            SlockColor.AccentCyan
                        } else {
                            SlockColor.AccentLavender
                        }
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    Text(
                        text = member.name,
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
                    Text(
                        text = member.role,
                        style = SlockType.Body,
                        color = SlockColor.TextBlack,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (member.kind == MemberKind.Agent) {
                Text(
                    text = "Agent detail stays inside the channel members surface.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun ThreadMemberPicker(
    candidatesLoadState: ScreenLoadState<ThreadMembersPickerContent>,
    currentMembers: List<ThreadMemberModalItem>,
    query: String,
    selectedMemberId: String?,
    enabled: Boolean,
    onMemberSelected: (ThreadMemberModalItem) -> Unit
) {
    val currentMemberIds = remember(currentMembers) { currentMembers.map { it.id.value }.toSet() }
    val normalizedQuery = query.trim()
    val candidates = (candidatesLoadState as? ScreenLoadState.Content)
        ?.data
        ?.candidates
        .orEmpty()
        .map { it.toThreadMemberModalItem() }
        .filter { member ->
            normalizedQuery.isBlank() ||
                member.name.contains(normalizedQuery, ignoreCase = true) ||
                member.handle.contains(normalizedQuery, ignoreCase = true) ||
                member.role.contains(normalizedQuery, ignoreCase = true) ||
                member.id.value.contains(normalizedQuery, ignoreCase = true)
        }
        .distinctBy { it.id }
    val candidateById = candidates.associateBy { it.id.value }
    val pickerCandidates = candidates.map { it.toSlockMemberPickerItem() }
    val selectedIds = selectedMemberId?.let(::setOf).orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when (candidatesLoadState) {
            ScreenLoadState.Loading -> SlockMemberPickerMessage("Loading people...")
            is ScreenLoadState.Error -> SlockMemberPickerMessage("Unable to load people: ${candidatesLoadState.error.code}")
            is ScreenLoadState.NoPermission -> SlockMemberPickerMessage(
                "Unable to load people: ${candidatesLoadState.blocker.requiredCapability ?: candidatesLoadState.blocker.code}"
            )
            is ScreenLoadState.Empty -> SlockMemberPickerMessage("No people available.")
            is ScreenLoadState.Content -> {
                if (candidates.isEmpty()) {
                    SlockMemberPickerMessage("No matching people.")
                } else {
                    SlockMemberPickerSection(
                        label = "AGENTS",
                        members = pickerCandidates.filter { it.kind == MemberKind.Agent },
                        selectedIds = selectedIds,
                        disabledIds = currentMemberIds,
                        enabled = enabled,
                        emptyText = "None",
                        onMemberSelected = { item ->
                            candidateById[item.id]?.let(onMemberSelected)
                        }
                    )
                    SlockMemberPickerSection(
                        label = "HUMANS",
                        members = pickerCandidates.filter { it.kind == MemberKind.Human },
                        selectedIds = selectedIds,
                        disabledIds = currentMemberIds,
                        enabled = enabled,
                        emptyText = "None",
                        onMemberSelected = { item ->
                            candidateById[item.id]?.let(onMemberSelected)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMemberPickerSection(
    label: String,
    members: List<ThreadMemberModalItem>,
    currentMemberIds: Set<String>,
    selectedMemberId: String?,
    enabled: Boolean,
    onMemberSelected: (ThreadMemberModalItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextMutedGray
        )
        if (members.isEmpty()) {
            Text(
                text = "None",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(vertical = SlockSpacing.Xs)
            )
        } else {
            members.forEach { member ->
                val alreadyJoined = member.id.value in currentMemberIds
                ThreadMemberPickerRow(
                    member = member,
                    selected = member.id.value == selectedMemberId,
                    alreadyJoined = alreadyJoined,
                    enabled = enabled && !alreadyJoined,
                    onClick = { onMemberSelected(member) }
                )
            }
        }
    }
}

@Composable
private fun ThreadMemberPickerMessage(text: String) {
    Text(
        text = text,
        style = SlockType.Caption,
        color = SlockColor.TextMutedGray,
        modifier = Modifier.padding(SlockSpacing.Sm)
    )
}

@Composable
private fun ThreadMemberPickerRow(
    member: ThreadMemberModalItem,
    selected: Boolean,
    alreadyJoined: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val rowColor = when {
        alreadyJoined -> SlockColor.SurfaceCream
        selected -> SlockColor.AccentLime
        else -> SlockColor.SurfaceWhite
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(modifier = Modifier.size(34.dp)) {
            SlockAvatar(
                avatarUrl = member.avatarUrl,
                avatar = member.avatar,
                label = member.avatarLabel,
                modifier = Modifier.fillMaxSize(),
                size = 34.dp,
                fontSize = SlockType.Caption.fontSize,
                fallbackColor = if (member.kind == MemberKind.Agent) {
                    SlockColor.AccentCyan
                } else {
                    SlockColor.AccentLavender
                }
            )
            if (member.kind != MemberKind.Human) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(9.dp)
                        .background(member.presence.presenceColor)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = if (alreadyJoined) SlockColor.TextMutedGray else SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (member.kind == MemberKind.Human) member.role else "${member.role} · ${member.presence.name.lowercase()}",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = when {
                alreadyJoined -> "Joined"
                selected -> "Selected"
                else -> "Add"
            },
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun ThreadMemberSection(
    label: String,
    members: List<ThreadMemberModalItem>,
    onMemberClick: (MemberProfile) -> Unit = {},
    onRemoveMemberClick: (MemberProfile) -> Unit = {}
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
                ThreadMemberRow(
                    member = member,
                    onClick = { onMemberClick(member.profile) },
                    onRemoveClick = if (member.kind == MemberKind.Agent) {
                        { onRemoveMemberClick(member.profile) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreadMemberRow(
    member: ThreadMemberModalItem,
    onClick: () -> Unit = {},
    onRemoveClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Box(modifier = Modifier.size(46.dp)) {
            SlockAvatar(
                avatarUrl = member.avatarUrl,
                avatar = member.avatar,
                label = member.avatarLabel,
                modifier = Modifier.fillMaxSize(),
                size = 46.dp,
                fontSize = SlockType.Button.fontSize,
                fallbackColor = if (member.kind == MemberKind.Agent) {
                    SlockColor.AccentCyan
                } else {
                    SlockColor.AccentLavender
                }
            )
            if (member.kind != MemberKind.Human) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .background(member.presence.presenceColor)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                )
            }
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
                text = if (member.kind == MemberKind.Human) member.role else "${member.role} · ${member.presence.name.lowercase()}",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRemoveClick != null && member.kind == MemberKind.Agent) {
            Text(
                text = "Remove",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.AccentRed,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRemoveClick)
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shadow = if (pressed && enabled) SlockShadow.None else SlockShadow.Small
    Box(
        modifier = Modifier
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(shadow)
            .size(38.dp)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shadow = if (pressed) SlockShadow.None else SlockShadow.Small
    Row(
        modifier = Modifier
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(shadow)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .heightIn(min = 38.dp)
            .defaultMinSize(minWidth = 54.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
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
    val horizontalPadding = threadHorizontalPadding()
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
    threadId: String,
    currentMemberId: MemberId?,
    myMessageAlignment: MyMessageAlignmentPreference,
    modifier: Modifier = Modifier,
    onLoadOlderMessages: (MessageId?, Int) -> Unit,
    onRetryLoadOlderMessages: () -> Unit,
    onLoadNewerMessages: () -> Unit,
    onRetryLoadNewerMessages: () -> Unit,
    onNearBottomChange: (Boolean) -> Unit,
    onOpenMessageThread: (Message) -> Unit,
    onOpenMemberProfile: (MemberProfile) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
    onOpenAttachment: (Attachment) -> Unit
) {
    val horizontalPadding = threadHorizontalPadding()
    val showOlderPageState = content.shouldShowOlderPageState
    val leadingItemCount = (if (showOlderPageState) 1 else 0) +
        (if (content.historyLimited || content.hasGap) 1 else 0) +
        (if (content.replyActionErrorCode != null) 1 else 0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (leadingItemCount + content.messages.lastIndex).coerceAtLeast(0)
    )
    val highlightedMessageIndex = content.highlightedMessageId?.let { highlightedId ->
        content.messages.indexOfFirst { it.id == highlightedId }.takeIf { it >= 0 }
    }
    val targetMessageIndex = highlightedMessageIndex ?: content.messages.lastIndex.takeIf { it >= 0 }
    val targetListIndex = targetMessageIndex?.let { leadingItemCount + it }
    val latestMessageListIndex = content.messages.lastIndex.takeIf { it >= 0 }?.let { leadingItemCount + it }
    var initialPositionApplied by remember(threadId) { mutableStateOf(false) }
    var observedLastMessageId by remember(threadId) { mutableStateOf<MessageId?>(null) }
    var followNewMessages by remember(threadId) { mutableStateOf(true) }
    val shouldLoadOlder by remember(content.canLoadMoreBefore, content.olderMessagesStatus, leadingItemCount) {
        derivedStateOf {
            val firstRealMessageIndex = leadingItemCount
            content.canLoadMoreBefore &&
                content.olderMessagesStatus == MessagePageStatus.Idle &&
                listState.firstVisibleItemIndex <= firstRealMessageIndex + 1
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
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val lastVisibleItem = visibleItems.lastOrNull()
            if (layoutInfo.totalItemsCount <= 0 || lastVisibleItem == null) {
                true
            } else {
                val viewportBottom = layoutInfo.viewportEndOffset
                lastVisibleItem.index >= layoutInfo.totalItemsCount - 2 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= viewportBottom + 24
            }
        }
    }
    val showBackToBottom by remember(latestMessageListIndex) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val lastVisibleItem = visibleItems.lastOrNull()
            if (latestMessageListIndex == null || layoutInfo.totalItemsCount <= 0 || lastVisibleItem == null) {
                false
            } else {
                val latestVisible = visibleItems.any { it.index == latestMessageListIndex }
                val viewportBottom = layoutInfo.viewportEndOffset
                val distanceBelowViewport = if (latestVisible) {
                    0
                } else {
                    layoutInfo.visibleItemsInfo
                        .filter { it.index > lastVisibleItem.index }
                        .sumOf { it.size }
                        .takeIf { it > 0 }
                        ?: (latestMessageListIndex - lastVisibleItem.index).coerceAtLeast(0) * 48
                }
                val lastItemHasBottomGap = lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= viewportBottom + 24
                !lastItemHasBottomGap && (lastVisibleItem.index < latestMessageListIndex || distanceBelowViewport > 24)
            }
        }
    }
    val lastMessageId = content.messages.lastOrNull()?.id
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            val firstVisibleMessageItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                val messageIndex = item.index - leadingItemCount
                messageIndex in content.messages.indices
            }
            val anchorMessageId = firstVisibleMessageItem
                ?.let { item -> content.messages.getOrNull(item.index - leadingItemCount)?.id }
            val anchorOffset = firstVisibleMessageItem?.offset?.coerceAtLeast(0) ?: 0
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadPaging",
                "older_trigger threadId=$threadId firstVisible=${listState.firstVisibleItemIndex} leadingItemCount=$leadingItemCount messageCount=${content.messages.size} canLoadMoreBefore=${content.canLoadMoreBefore} status=${content.olderMessagesStatus} anchorMessageId=${anchorMessageId?.value.orEmpty()}"
            )
            onLoadOlderMessages(anchorMessageId, anchorOffset)
        }
    }
    LaunchedEffect(shouldLoadNewer) {
        if (shouldLoadNewer) {
            onLoadNewerMessages()
        }
    }
    LaunchedEffect(isNearBottom, lastMessageId, observedLastMessageId, initialPositionApplied) {
        if (initialPositionApplied && lastMessageId == observedLastMessageId) {
            followNewMessages = isNearBottom
            onNearBottomChange(isNearBottom)
        }
    }
    LaunchedEffect(threadId, content.messages.size, content.highlightedMessageId, targetListIndex) {
        if (!initialPositionApplied && targetListIndex != null) {
            yield()
            listState.scrollToItem(targetListIndex)
            initialPositionApplied = true
            observedLastMessageId = lastMessageId
            followNewMessages = content.highlightedMessageId == null
        }
    }
    LaunchedEffect(content.anchorMessageId, content.anchorOffset, leadingItemCount, content.messages) {
        val anchorMessageId = content.anchorMessageId ?: return@LaunchedEffect
        val anchorMessageIndex = content.messages.indexOfFirst { it.id == anchorMessageId }
        if (anchorMessageIndex >= 0) {
            val anchorListIndex = leadingItemCount + anchorMessageIndex
            val anchorOffset = content.anchorOffset.coerceAtLeast(0)
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadPaging",
                "anchor_state threadId=$threadId anchorMessageId=${anchorMessageId.value} anchorIndex=$anchorMessageIndex listIndex=$anchorListIndex offset=$anchorOffset leadingItemCount=$leadingItemCount messageCount=${content.messages.size} firstVisible=${listState.firstVisibleItemIndex}"
            )
        }
    }
    LaunchedEffect(lastMessageId, latestMessageListIndex) {
        if (
            lastMessageId != null &&
            latestMessageListIndex != null &&
            observedLastMessageId != null &&
            observedLastMessageId != lastMessageId
        ) {
            val shouldFollow = followNewMessages
            if (shouldFollow) {
                yield()
                listState.animateScrollToItem(latestMessageListIndex)
            }
            observedLastMessageId = lastMessageId
            followNewMessages = shouldFollow
        } else if (lastMessageId != null && observedLastMessageId == null && initialPositionApplied) {
            observedLastMessageId = lastMessageId
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .threadWheelScroll(listState)
                .testTag(THREAD_MESSAGE_LIST_TAG),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = horizontalPadding,
                vertical = SlockSpacing.Lg
            ),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)
        ) {
            if (showOlderPageState) {
                item(key = "older-page-state") {
                    ThreadMessagePageState(
                        canLoadMore = content.canLoadMoreBefore,
                        status = content.olderMessagesStatus,
                        errorCode = content.olderMessagesErrorCode,
                        loadingText = "Loading older messages...",
                        idleText = null,
                        endText = "Beginning of messages",
                        onRetry = onRetryLoadOlderMessages
                    )
                }
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
            if (content.replyActionErrorCode != null) {
                item(key = "reply-action-error") {
                    ThreadContextNotice(
                        text = "Reply thread failed: ${content.replyActionErrorCode}. Try Reply again."
                    )
                }
            }
            itemsIndexed(
                items = content.messages,
                key = { _, message -> message.id.value }
            ) { index, message ->
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Lg)) {
                    if (message.messageType == MessageType.System) {
                        ThreadSystemMessageItem(
                            message = message,
                            highlighted = message.id == content.highlightedMessageId
                        )
                    } else {
                        ThreadMessageItem(
                            message = message,
                            isCurrentUserMessage = message.isCurrentUserMessage(currentMemberId),
                            myMessageAlignment = myMessageAlignment,
                            highlighted = message.id == content.highlightedMessageId,
                            showMentionChip = message.body.contains("@"),
                            attachmentOpenStates = attachmentOpenStates,
                            onOpenAttachment = onOpenAttachment,
                            onOpenThread = { onOpenMessageThread(message) },
                            onOpenAuthorProfile = { onOpenMemberProfile(message.author) },
                            onOpenMarkdownTag = onOpenMarkdownTag
                        )
                    }
                }
            }
            if (content.shouldShowNewerPageState) {
                item(key = "newer-page-state") {
                    ThreadMessagePageState(
                        canLoadMore = content.canLoadMoreAfter,
                        status = content.newerMessagesStatus,
                        errorCode = content.newerMessagesErrorCode,
                        loadingText = "Loading newer messages...",
                        idleText = "Scroll down to load newer messages",
                        endText = null,
                        onRetry = onRetryLoadNewerMessages
                    )
                }
            }
        }
        if (showBackToBottom) {
            ThreadBackToBottomButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = SlockSpacing.Md, bottom = SlockSpacing.Md)
                    .testTag(THREAD_BACK_TO_BOTTOM_TAG),
                onClick = {
                    val lastIndex = latestMessageListIndex
                        ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    coroutineScope.launch {
                        listState.animateScrollToItem(lastIndex)
                    }
                }
            )
        }
    }
}

@Composable
private fun threadHorizontalPadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp < 420) SlockSpacing.Sm else SlockSpacing.Md

@Composable
private fun ThreadBackToBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shadow = if (pressed) SlockShadow.None else SlockShadow.Small
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .slockHardShadow(shadow)
            .background(SlockColor.AccentCyan, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = SlockSpacing.Sm, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = "Back to bottom",
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Icon(
            imageVector = SlockIcons.ChevronDown,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(14.dp)
        )
    }
}

private fun Modifier.threadWheelScroll(listState: LazyListState): Modifier =
    pointerInput(listState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val scrollDelta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (scrollDelta != 0f) {
                        listState.dispatchRawDelta(scrollDelta)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }

@Composable
private fun ThreadMessagePageState(
    canLoadMore: Boolean,
    status: MessagePageStatus,
    errorCode: String?,
    loadingText: String,
    idleText: String?,
    endText: String?,
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
        MessagePageStatus.Idle -> if (canLoadMore && idleText != null) {
            ThreadContextNotice(text = idleText)
        }
        MessagePageStatus.EndReached -> if (endText != null) {
            ThreadContextNotice(text = endText)
        }
    }
}

private val ThreadContent.shouldShowNewerPageState: Boolean
    get() = canLoadMoreAfter ||
        newerMessagesStatus == MessagePageStatus.Loading ||
        newerMessagesStatus == MessagePageStatus.Error

private val ThreadContent.shouldShowOlderPageState: Boolean
    get() = olderMessagesStatus == MessagePageStatus.Loading ||
        olderMessagesStatus == MessagePageStatus.Error ||
        olderMessagesStatus == MessagePageStatus.EndReached

internal const val THREAD_MESSAGE_LIST_TAG = "thread-message-list"
internal const val THREAD_BACK_TO_BOTTOM_TAG = "thread-back-to-bottom"

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
fun ThreadMessageItem(
    message: Message,
    modifier: Modifier = Modifier,
    isCurrentUserMessage: Boolean = false,
    myMessageAlignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Default,
    highlighted: Boolean = false,
    showTaskChip: Boolean = false,
    showMentionChip: Boolean = false,
    attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState> = emptyMap(),
    onOpenAttachment: (Attachment) -> Unit = {},
    onOpenThread: (() -> Unit)? = null,
    onOpenAuthorProfile: (() -> Unit)? = null,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit = {}
) {
    val horizontalPadding = threadHorizontalPadding()
    val alignCurrentUserRight = isCurrentUserMessage && myMessageAlignment == MyMessageAlignmentPreference.Right
    val messageMaxWidthFraction = if (alignCurrentUserRight) 0.82f else 1f
    val bodyFillWidth = !alignCurrentUserRight || !message.body.isPlainSingleLineMessage()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (highlighted) SlockColor.BrandYellow.copy(alpha = 0.25f) else Color.Transparent)
            .border(
                width = if (highlighted) SlockStroke.Thin else 0.dp,
                color = if (highlighted) SlockColor.BorderBlack else Color.Transparent
            )
            .padding(horizontal = horizontalPadding, vertical = SlockSpacing.Sm),
        horizontalArrangement = if (alignCurrentUserRight) {
            Arrangement.spacedBy(SlockSpacing.Md, Alignment.End)
        } else {
            Arrangement.spacedBy(SlockSpacing.Md)
        }
    ) {
        if (!alignCurrentUserRight) {
            ThreadAvatar(message.author, onClick = onOpenAuthorProfile)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            horizontalAlignment = if (alignCurrentUserRight) Alignment.End else Alignment.Start
        ) {
            Row(
                modifier = Modifier.messageContentWidth(messageMaxWidthFraction),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (alignCurrentUserRight) {
                    Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End)
                } else {
                    Arrangement.spacedBy(SlockSpacing.Sm)
                }
            ) {
                if (!alignCurrentUserRight) {
                    Text(
                        text = message.author.displayName,
                        style = SlockType.Button,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Text(
                    text = message.author.roleDescription ?: message.author.kind.name.lowercase(),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (message.timestampLabel.isNotBlank()) {
                    Text(
                        text = message.timestampLabel.toThreadTimestampLabel(),
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (message.deliveryState != MessageDeliveryState.Sent) {
                    Text(
                        text = message.deliveryState.threadLabel,
                        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = message.deliveryState.threadColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (alignCurrentUserRight) {
                    Text(
                        text = message.author.displayName,
                        style = SlockType.Button,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showTaskChip) {
                    ThreadChip(
                        label = message.task?.let { "#${it.taskNumber ?: "task"}" } ?: "#9",
                        color = message.task?.status?.threadColor ?: SlockColor.AccentLavender,
                        icon = SlockIcons.Square
                    )
                }
            }
            ThreadMarkdownBody(
                body = message.body,
                modifier = Modifier.messageContentWidth(
                    maxWidthFraction = messageMaxWidthFraction,
                    fill = bodyFillWidth,
                    alignment = Alignment.End
                ),
                textAlign = if (alignCurrentUserRight) TextAlign.End else TextAlign.Start
            )
            val tags = remember(message.body) { message.body.threadMarkdownTags() }
            if (tags.isNotEmpty()) {
                ThreadMarkdownTagRow(
                    tags = tags,
                    onOpenTag = onOpenMarkdownTag,
                    alignEnd = alignCurrentUserRight,
                    modifier = Modifier.messageContentWidth(messageMaxWidthFraction)
                )
            }
            if (message.attachments.isNotEmpty()) {
                ThreadMessageAttachments(
                    attachments = message.attachments,
                    states = attachmentOpenStates,
                    onOpenAttachment = onOpenAttachment,
                    modifier = Modifier.messageContentWidth(messageMaxWidthFraction)
                )
            }
            val replyLabel = message.replyCount.takeIf { it > 0 }?.let { "$it replies" }
            val showActionRow = showMentionChip || (message.task != null && !showTaskChip) || replyLabel != null
            if (showActionRow) {
                Row(
                    modifier = Modifier.messageContentWidth(messageMaxWidthFraction),
                    horizontalArrangement = if (alignCurrentUserRight) {
                        Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End)
                    } else {
                        Arrangement.spacedBy(SlockSpacing.Sm)
                    }
                ) {
                    if (showMentionChip) {
                        ThreadChip("@API-Integration-Developer", SlockColor.AccentLavender)
                    }
                    if (message.task != null && !showTaskChip) {
                        ThreadChip(
                            label = "#${message.task.taskNumber ?: "task"}",
                            color = message.task.status.threadColor,
                            icon = SlockIcons.Square
                        )
                    }
                    if (replyLabel != null) {
                        LaunchedEffect(message.id, message.threadChannelId, message.replyCount, replyLabel) {
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockThreadReplies",
                                "render_affordance parentMessageId=${message.id.value} " +
                                    "channelId=${message.channelId?.value.orEmpty()} " +
                                    "threadChannelId=${message.threadChannelId?.value.orEmpty()} " +
                                    "replyCount=${message.replyCount} taskNumber=${message.task?.taskNumber ?: 0} " +
                                    "renderedAffordance=$replyLabel source=thread_message_item"
                            )
                        }
                        ThreadChip(
                            label = replyLabel,
                            color = SlockColor.AppBgLightBlue,
                            icon = SlockIcons.MessageSquare,
                            onClick = onOpenThread
                        )
                    }
                    when (message.deliveryState) {
                        MessageDeliveryState.Sent -> Unit
                        MessageDeliveryState.Sending,
                        MessageDeliveryState.Failed -> Unit
                    }
                }
            }
        }
        if (alignCurrentUserRight) {
            ThreadAvatar(message.author, onClick = onOpenAuthorProfile)
        }
    }
}

private fun Message.isCurrentUserMessage(currentMemberId: MemberId?): Boolean =
    author.id == currentMemberId || author.id.value == LocalComposerAuthorId

private const val LocalComposerAuthorId = "local-current-user"

private fun Modifier.messageContentWidth(
    maxWidthFraction: Float,
    fill: Boolean = true,
    alignment: Alignment.Horizontal = Alignment.Start
): Modifier {
    val constrained = widthIn(max = 680.dp)
    return if (fill) {
        constrained.fillMaxWidth(maxWidthFraction)
    } else {
        constrained.wrapContentWidth(alignment)
    }
}

@Composable
private fun ThreadMessageAttachments(
    attachments: List<Attachment>,
    states: Map<AttachmentId, AttachmentOpenUiState>,
    onOpenAttachment: (Attachment) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        attachments.forEach { attachment ->
            ThreadAttachmentCard(
                attachment = attachment,
                state = states[attachment.id] ?: AttachmentOpenUiState(),
                onOpen = { onOpenAttachment(attachment) }
            )
        }
    }
}

@Composable
private fun ThreadAttachmentCard(
    attachment: Attachment,
    state: AttachmentOpenUiState,
    onOpen: () -> Unit
) {
    val isImage = attachment.mimeType.startsWith("image/")
    val isLoading = state.status == AttachmentOpenStatus.Loading
    val borderColor = if (state.status == AttachmentOpenStatus.Error) {
        SlockColor.AccentRed
    } else {
        SlockColor.BorderBlack
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.EmptyStateGray.copy(alpha = 0.22f), RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, borderColor, RoundedCornerShape(SlockRadius.Xs))
            .clickable(enabled = !isLoading, role = Role.Button, onClick = onOpen)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(if (isImage) SlockColor.AccentPink else SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = SlockColor.TextBlack,
                    trackColor = SlockColor.SurfaceWhite
                )
            } else {
                Icon(
                    imageVector = if (isImage) SlockIcons.Image else SlockIcons.FileText,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = attachment.displayMeta(state),
                style = SlockType.Caption,
                color = if (state.status == AttachmentOpenStatus.Error) {
                    SlockColor.AccentRed
                } else {
                    SlockColor.TextMutedGray
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = when (state.status) {
                AttachmentOpenStatus.Loading -> "Opening"
                AttachmentOpenStatus.Error -> "Retry"
                AttachmentOpenStatus.Idle -> if (isImage) "Preview" else "Open"
            },
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = if (isLoading) SlockColor.TextMutedGray else SlockColor.TextBlack,
            modifier = Modifier
                .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                .border(SlockStroke.Thin, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
                .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
        )
    }
}

private fun Attachment.displayMeta(state: AttachmentOpenUiState): String =
    when (state.status) {
        AttachmentOpenStatus.Loading -> "Opening..."
        AttachmentOpenStatus.Error -> listOfNotNull(
            "Open failed",
            state.errorCode?.takeIf { it.isNotBlank() }
        ).joinToString(": ")
        AttachmentOpenStatus.Idle -> listOf(mimeType, sizeLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "Attachment" }
    }

@Composable
private fun ThreadSystemMessageItem(
    message: Message,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    val timestamp = message.timestampLabel.toThreadTimestampLabel()
    var expanded by rememberSaveable(message.id.value) { mutableStateOf(false) }
    val summary = message.collapsedSystemSummary(timestamp)
    val horizontalPadding = threadHorizontalPadding()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (highlighted) SlockColor.BrandYellow.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { expanded = !expanded }
            )
            .padding(horizontal = horizontalPadding, vertical = SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = summary,
                style = SlockType.Caption.copy(fontWeight = FontWeight.Medium),
                color = SlockColor.TextMutedGray,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expanded) {
            ThreadSystemMessageExpandedContent(message)
        }
    }
}

@Composable
private fun ThreadSystemMessageExpandedContent(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Sm))
            .border(SlockStroke.Thin, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Sm))
            .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = message.body.sanitizeThreadSystemText(),
            style = SlockType.Caption,
            color = SlockColor.TextBlack
        )
        val metadata = message.expandedSystemDetails()
        if (metadata.isNotEmpty()) {
            Text(
                text = metadata,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
    }
}

private fun Message.collapsedSystemSummary(timestamp: String): String =
    listOf(timestamp, body.sanitizeThreadSystemText())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

private fun Message.expandedSystemDetails(): String =
    buildList {
        systemEventType?.takeIf { it.isNotBlank() }?.let { add("Type: $it") }
        metadata.entries
            .sortedBy { it.key }
            .forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    add("${key.sanitizeThreadSystemText()}: ${value.sanitizeThreadSystemText()}")
                }
            }
    }.joinToString("\n")

private fun String.sanitizeThreadSystemText(): String =
    replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .decodeBasicHtmlEntities()
        .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

private fun String.decodeBasicHtmlEntities(): String =
    replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&#x2F;", "/")

data class ThreadMarkdownTag(
    val label: String,
    val kind: ThreadMarkdownTagKind
)

enum class ThreadMarkdownTagKind {
    Task,
    Channel
}

@Composable
private fun ThreadMarkdownTagRow(
    tags: List<ThreadMarkdownTag>,
    onOpenTag: (ThreadMarkdownTag) -> Unit,
    alignEnd: Boolean,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) {
            Arrangement.spacedBy(SlockSpacing.Xs, Alignment.End)
        } else {
            Arrangement.spacedBy(SlockSpacing.Xs)
        }
    ) {
        itemsIndexed(tags) { _, tag ->
            ThreadChip(
                label = tag.label,
                color = when (tag.kind) {
                    ThreadMarkdownTagKind.Task -> SlockColor.AccentLavender
                    ThreadMarkdownTagKind.Channel -> SlockColor.AccentCyan
                },
                icon = when (tag.kind) {
                    ThreadMarkdownTagKind.Task -> SlockIcons.ListTodo
                    ThreadMarkdownTagKind.Channel -> SlockIcons.Hash
                },
                onClick = { onOpenTag(tag) }
            )
        }
    }
}

private fun String.threadMarkdownTags(): List<ThreadMarkdownTag> {
    val masked = maskMarkdownCodeRanges()
    val matches = MarkdownTagRegex.findAll(masked)
        .map { match ->
            val label = match.value
            ThreadMarkdownTag(
                label = label,
                kind = if (label.drop(1).all { it.isDigit() }) {
                    ThreadMarkdownTagKind.Task
                } else {
                    ThreadMarkdownTagKind.Channel
                }
            )
        }
        .distinctBy { it.label.lowercase() }
        .toList()
    return matches.take(8)
}

private fun String.maskMarkdownCodeRanges(): String {
    val chars = toCharArray()
    var index = 0
    while (index < chars.size) {
        if (startsWith("```", index)) {
            val end = indexOf("```", startIndex = index + 3).let { if (it < 0) chars.size - 3 else it }
            for (maskIndex in index until (end + 3).coerceAtMost(chars.size)) chars[maskIndex] = ' '
            index = (end + 3).coerceAtMost(chars.size)
        } else if (chars[index] == '`') {
            val end = indexOf('`', startIndex = index + 1).let { if (it < 0) index else it }
            for (maskIndex in index..end) chars[maskIndex] = ' '
            index = (end + 1).coerceAtMost(chars.size)
        } else {
            index++
        }
    }
    return String(chars)
}

private val MarkdownTagRegex = Regex("""(?<![\w/])#[A-Za-z0-9][A-Za-z0-9_-]{0,63}""")

private sealed interface ThreadMarkdownSegment {
    data class Text(val value: String) : ThreadMarkdownSegment
    data class CodeBlock(val language: String?, val code: String) : ThreadMarkdownSegment
}

@Composable
private fun ThreadMarkdownBody(
    body: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    val segments = remember(body) { body.toThreadMarkdownSegments() }
    val bodyTypography = SlockType.Body.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        textAlign = textAlign
    )
    val codeTypography = bodyTypography.copy(fontSize = 14.sp, lineHeight = 20.sp)
    if (segments.size == 1 && segments.single() is ThreadMarkdownSegment.Text && body.isPlainSingleLineMessage()) {
        Text(
            text = body,
            style = bodyTypography,
            color = SlockColor.TextBlack,
            modifier = modifier,
            textAlign = textAlign
        )
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        horizontalAlignment = if (textAlign == TextAlign.End) Alignment.End else Alignment.Start
    ) {
        segments.forEach { segment ->
            when (segment) {
                is ThreadMarkdownSegment.Text -> {
                    if (segment.value.isNotBlank()) {
                        ThreadMarkdownContent(
                            body = segment.value,
                            bodyTypography = bodyTypography,
                            codeTypography = codeTypography
                        )
                    }
                }
                is ThreadMarkdownSegment.CodeBlock -> ThreadMarkdownCodeBlock(
                    code = segment.code,
                    language = segment.language
                )
            }
        }
    }
}

@Composable
private fun ThreadMarkdownContent(
    body: String,
    bodyTypography: androidx.compose.ui.text.TextStyle,
    codeTypography: androidx.compose.ui.text.TextStyle
) {
    Markdown(
        content = body,
        colors = markdownColor(
            text = SlockColor.TextBlack,
            codeText = SlockColor.TextBlack,
            inlineCodeText = SlockColor.TextBlack,
            linkText = SlockColor.TextBlack,
            codeBackground = SlockColor.SurfaceCream,
            inlineCodeBackground = SlockColor.BrandYellow.copy(alpha = 0.42f),
            dividerColor = SlockColor.BorderBlack,
            tableText = SlockColor.TextBlack,
            tableBackground = SlockColor.SurfaceWhite
        ),
        typography = markdownTypography(
            text = bodyTypography,
            paragraph = bodyTypography,
            ordered = bodyTypography,
            bullet = bodyTypography,
            list = bodyTypography,
            quote = bodyTypography,
            code = codeTypography,
            inlineCode = codeTypography
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ThreadMarkdownCodeBlock(
    code: String,
    language: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language.uppercase(),
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.BrandYellow, RoundedCornerShape(topStart = SlockRadius.Xs, topEnd = SlockRadius.Xs))
                    .border(SlockStroke.Thin, SlockColor.BorderBlack, RoundedCornerShape(topStart = SlockRadius.Xs, topEnd = SlockRadius.Xs))
                    .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
            )
        }
        Text(
            text = code.trimEnd(),
            style = SlockType.Caption.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            ),
            color = SlockColor.TextBlack,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(SlockSpacing.Sm)
        )
    }
}

private fun String.toThreadMarkdownSegments(): List<ThreadMarkdownSegment> {
    if (!contains("```")) return listOf(ThreadMarkdownSegment.Text(this))
    val segments = mutableListOf<ThreadMarkdownSegment>()
    var index = 0
    while (index < length) {
        val fenceStart = indexOf("```", startIndex = index)
        if (fenceStart < 0) {
            substring(index).takeIf { it.isNotBlank() }?.let { segments += ThreadMarkdownSegment.Text(it) }
            break
        }
        substring(index, fenceStart).takeIf { it.isNotBlank() }?.let { segments += ThreadMarkdownSegment.Text(it) }
        val infoStart = fenceStart + 3
        val firstLineEnd = indexOf('\n', startIndex = infoStart).let { if (it < 0) length else it }
        val language = substring(infoStart, firstLineEnd).trim().takeIf { it.isNotBlank() }
        val codeStart = (firstLineEnd + 1).coerceAtMost(length)
        val fenceEnd = indexOf("```", startIndex = codeStart)
        if (fenceEnd < 0) {
            segments += ThreadMarkdownSegment.CodeBlock(language, substring(codeStart))
            break
        }
        segments += ThreadMarkdownSegment.CodeBlock(language, substring(codeStart, fenceEnd))
        index = (fenceEnd + 3).coerceAtMost(length)
    }
    return segments.ifEmpty { listOf(ThreadMarkdownSegment.Text(this)) }
}

private fun String.isPlainSingleLineMessage(): Boolean =
    isNotBlank() &&
        !contains('\n') &&
        none { it == '`' || it == '*' || it == '[' || it == ']' || it == '>' }

@Composable
private fun ThreadAvatar(
    author: MemberProfile,
    onClick: (() -> Unit)? = null
) {
    val avatarModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    SlockAvatar(
        avatarUrl = author.avatarUrl,
        avatar = author.avatar,
        label = author.avatarLabel,
        size = 40.dp,
        fontSize = SlockType.Button.fontSize,
        fallbackColor = author.kind.avatarColor,
        modifier = avatarModifier
    )
}

private val MemberKind.avatarColor: Color
    get() = when (this) {
        MemberKind.Human -> SlockColor.AccentLavender
        MemberKind.Agent -> SlockColor.AccentPink
        MemberKind.System -> SlockColor.AccentCyan
    }

private val MessageDeliveryState.threadLabel: String
    get() = when (this) {
        MessageDeliveryState.Sending -> "Sending"
        MessageDeliveryState.Sent -> "Sent"
        MessageDeliveryState.Failed -> "Failed"
    }

private val MessageDeliveryState.threadColor: Color
    get() = when (this) {
        MessageDeliveryState.Sending -> SlockColor.BrandYellow
        MessageDeliveryState.Sent -> SlockColor.TextMutedGray
        MessageDeliveryState.Failed -> SlockColor.AccentRed
    }

private fun String.toThreadTimestampLabel(
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return ""
    val zoned = trimmed.toThreadZonedDateTime(zoneId) ?: return trimmed
    val local = zoned.withZoneSameInstant(zoneId)
    val today = LocalDate.now(zoneId)
    val formatter = if (local.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT).withLocale(locale)
    }
    return formatter.format(local)
}

private fun String.toThreadZonedDateTime(zoneId: ZoneId): ZonedDateTime? {
    val trimmed = trim()
    return parseOrNull { Instant.parse(trimmed).atZone(zoneId) }
        ?: parseOrNull { OffsetDateTime.parse(trimmed).toZonedDateTime() }
        ?: parseOrNull { ZonedDateTime.parse(trimmed) }
        ?: parseOrNull { LocalDateTime.parse(trimmed).atZone(zoneId) }
}

private inline fun <T> parseOrNull(block: () -> T): T? =
    try {
        block()
    } catch (_: DateTimeParseException) {
        null
    }

@Composable
private fun ThreadChip(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val chipModifier = Modifier
        .background(color)
        .border(SlockStroke.Thin, SlockColor.BorderBlack)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
    Row(
        modifier = chipModifier,
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
    onAttachImage: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onRetryAttachment: () -> Unit = {},
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalPadding = threadHorizontalPadding()
    val bottomPadding = threadComposerBottomPadding()
    val density = LocalDensity.current
    val keyboardBottomPx = WindowInsets.ime.getBottom(density)
    val keyboardVisible = keyboardBottomPx > 0
    val composerTopPadding = if (keyboardVisible) SlockSpacing.Xs else SlockSpacing.Md
    val composerSectionSpacing = if (keyboardVisible) SlockSpacing.Xs else SlockSpacing.Sm
    val composerTextFieldMinHeight = if (keyboardVisible) 40.dp else 48.dp
    val composerTextFieldVerticalPadding = if (keyboardVisible) 8.dp else 12.dp
    val composerSendButtonCompact = keyboardVisible
    val enabled = composerState.enabled &&
        composerState.status != ComposerStatus.NoPermission &&
        composerState.noPermissionCode == null
    fun recordImeDiagnostic(message: String) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d("SlockThreadIme", message)
    }
    fun diagnosticFields(
        source: String,
        topPx: Int,
        bottomPx: Int,
        heightPx: Int
    ): String =
        "$source topPx=$topPx bottomPx=$bottomPx heightPx=$heightPx " +
            "keyboardVisible=$keyboardVisible keyboardBottomPx=$keyboardBottomPx bottomPaddingDp=${bottomPadding.value}"
    SideEffect {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockThreadIme",
            "composer_layout keyboardVisible=$keyboardVisible keyboardBottomPx=$keyboardBottomPx " +
                "bottomPaddingDp=${bottomPadding.value} " +
                "sendRowVisible=true status=${composerState.status}"
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .imePadding()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                recordImeDiagnostic(
                    diagnosticFields(
                        source = "composer_bounds",
                        topPx = position.y.toInt(),
                        bottomPx = (position.y + coordinates.size.height).toInt(),
                        heightPx = coordinates.size.height
                    )
                )
            }
            .padding(
                start = horizontalPadding,
                top = composerTopPadding,
                end = horizontalPadding,
                bottom = bottomPadding
            ),
        verticalArrangement = Arrangement.spacedBy(composerSectionSpacing)
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
        if (composerState.pendingAttachments.isNotEmpty()) {
            PendingAttachmentTray(
                attachments = composerState.pendingAttachments,
                enabled = enabled,
                onRemoveAttachment = onRemoveAttachment,
                onRetryAttachment = onRetryAttachment
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
            reserveErrorSpace = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (composerState.canSend) {
                        onSend()
                    }
                }
            ),
            inputModifier = Modifier.threadComposerNewlineInput(
                value = composerState.value,
                onValueChange = onComposerChange
            ),
            minContentHeight = composerTextFieldMinHeight,
            contentPaddingVertical = composerTextFieldVerticalPadding
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    recordImeDiagnostic(
                        diagnosticFields(
                            source = "send_row_bounds",
                            topPx = position.y.toInt(),
                            bottomPx = (position.y + coordinates.size.height).toInt(),
                            heightPx = coordinates.size.height
                        )
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactIconButton(SlockIcons.ImagePlus, "Attach image", enabled = enabled, onClick = onAttachImage)
            Spacer(modifier = Modifier.width(SlockSpacing.Sm))
            CompactIconButton(SlockIcons.Paperclip, "Attach file", enabled = enabled, onClick = onAttachFile)
            Spacer(modifier = Modifier.weight(1f))
            if (composerState.attachmentIds.isNotEmpty() && composerState.pendingAttachments.isEmpty()) {
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
                onCheckedChange = onAsTaskChange,
                compact = composerSendButtonCompact
            )
            Spacer(modifier = Modifier.width(SlockSpacing.Md))
            ThreadSendButton(
                state = composerState.sendVisualState,
                onClick = onSend,
                compact = composerSendButtonCompact
            )
        }
    }
}

@Composable
private fun threadComposerBottomPadding(): Dp {
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    return if (keyboardVisible) SlockSpacing.Xs else SlockSpacing.Md
}

private fun Modifier.threadComposerNewlineInput(
    value: String,
    onValueChange: (String) -> Unit
): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed) {
            onValueChange(value + "\n")
            true
        } else {
            false
        }
    }

@Composable
private fun PendingAttachmentTray(
    attachments: List<PendingAttachment>,
    enabled: Boolean,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        itemsIndexed(attachments, key = { _, item -> item.id }) { _, attachment ->
            PendingAttachmentChip(
                attachment = attachment,
                enabled = enabled,
                onRemove = { onRemoveAttachment(attachment.id) },
                onRetry = onRetryAttachment
            )
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: PendingAttachment,
    enabled: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit
) {
    val borderColor = if (attachment.status == PendingAttachmentStatus.Failed) {
        SlockColor.AccentRed
    } else {
        SlockColor.BorderBlack
    }
    Row(
        modifier = Modifier
            .width(168.dp)
            .heightIn(min = 54.dp)
            .background(SlockColor.EmptyStateGray.copy(alpha = 0.28f), RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, borderColor, RoundedCornerShape(SlockRadius.Xs))
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (attachment.isImage) SlockColor.AccentPink else SlockColor.SurfaceWhite,
                    RoundedCornerShape(SlockRadius.Xs)
                )
                .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs)),
            contentAlignment = Alignment.Center
        ) {
            PendingAttachmentPreview(attachment)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = attachment.displayMeta,
                style = SlockType.Caption,
                color = if (attachment.status == PendingAttachmentStatus.Failed) {
                    SlockColor.AccentRed
                } else {
                    SlockColor.TextMutedGray
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            CompactIconButton(
                icon = SlockIcons.X,
                contentDescription = "Remove attachment",
                enabled = enabled || attachment.status == PendingAttachmentStatus.Failed,
                onClick = onRemove
            )
            if (attachment.status == PendingAttachmentStatus.Failed) {
                Text(
                    text = "Retry",
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack,
                    modifier = Modifier.clickable(enabled = enabled, onClick = onRetry)
                )
            }
        }
    }
}

@Composable
private fun PendingAttachmentPreview(attachment: PendingAttachment) {
    if (attachment.status == PendingAttachmentStatus.Uploading) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = SlockColor.TextBlack,
            trackColor = SlockColor.SurfaceWhite
        )
        return
    }
    val context = LocalContext.current
    val imageBitmap = remember(attachment.previewUri, attachment.isImage) {
        attachment.previewUri
            ?.takeIf { attachment.isImage }
            ?.let { uriString ->
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }
                }.getOrNull()
            }
    }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(SlockRadius.Xs))
        )
    } else {
        Icon(
            imageVector = if (attachment.isImage) SlockIcons.Image else SlockIcons.FileText,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(16.dp)
        )
    }
}

private val PendingAttachment.displayMeta: String
    get() = listOfNotNull(
        mimeType?.takeIf { it.isNotBlank() },
        sizeLabel.takeIf { it.isNotBlank() },
        status.takeUnless { it == PendingAttachmentStatus.Ready }?.name?.lowercase()
    ).joinToString(" · ").ifBlank { "Ready" }

@Composable
private fun ThreadSendButton(
    state: ComposerSendVisualState,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val enabled = state == ComposerSendVisualState.Ready || state == ComposerSendVisualState.Failed
    val minHeight = if (compact) 20.dp else 24.dp
    val horizontalPadding = if (compact) SlockSpacing.Xs else SlockSpacing.Sm
    val verticalPadding = if (compact) 1.dp else 2.dp
    Row(
        modifier = Modifier
            .heightIn(min = minHeight)
            .background(SlockColor.AccentPink, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
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
    onCheckedChange: (Boolean) -> Unit,
    compact: Boolean = false
) {
    SlockLabeledCheckbox(
        label = "As Task",
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        size = if (compact) 16.dp else 18.dp
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
private fun ThreadMessageMarkdownPreview() {
    SlockTheme {
        ThreadMessageItem(
            message = Message(
                id = MessageId("markdown-preview-message"),
                threadId = ThreadId("markdown-preview-thread"),
                channelId = ChannelId("markdown-preview-channel"),
                author = MemberProfile(
                    id = MemberId("markdown-preview-author"),
                    displayName = "Android Developer",
                    handle = "android-developer",
                    kind = MemberKind.Agent,
                    roleDescription = "Agent"
                ),
                timestampLabel = "Now",
                body = """
                    **Markdown** body under the sender:

                    - lists stay left aligned
                    - `inline code #999` wraps with the text
                    - tags like #283 and #android-dev become clickable chips

                    > quoted text remains inside the message column

                    ```kotlin
                    val longLine = "code blocks #123 should stay inside the row without replacing the author"
                    ```
                """.trimIndent(),
                replyCount = 2
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ThreadMessageRightAlignedPreview() {
    SlockTheme {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            ThreadMessageItem(
                message = Message(
                    id = MessageId("right-short-message"),
                    threadId = ThreadId("right-thread"),
                    channelId = ChannelId("right-channel"),
                    author = MemberProfile(
                        id = MemberId("current-user"),
                        displayName = "Artin With A Long Display Name",
                        handle = "artin",
                        kind = MemberKind.Human,
                        roleDescription = "Owner"
                    ),
                    timestampLabel = "2026-05-07T11:18:00Z",
                    body = "Short right-aligned note.",
                    attachments = listOf(
                        Attachment(
                            id = AttachmentId("right-attachment"),
                            name = "very-long-file-name-for-right-layout.pdf",
                            mimeType = "application/pdf",
                            sizeLabel = "248 KB"
                        )
                    ),
                    replyCount = 3
                ),
                isCurrentUserMessage = true,
                myMessageAlignment = MyMessageAlignmentPreference.Right
            )
            ThreadMessageItem(
                message = Message(
                    id = MessageId("right-markdown-message"),
                    threadId = ThreadId("right-thread"),
                    channelId = ChannelId("right-channel"),
                    author = MemberProfile(
                        id = MemberId("current-user"),
                        displayName = "Artin",
                        handle = "artin",
                        kind = MemberKind.Human
                    ),
                    timestampLabel = "Now",
                    body = """
                        Right mode with `inline code`, a list, and a long word:

                        - metadata should stay near the bubble edge
                        - attachments should not stretch past the message width

                        superlongunbrokenidentifier_that_should_wrap_inside_the_right_column
                    """.trimIndent()
                ),
                isCurrentUserMessage = true,
                myMessageAlignment = MyMessageAlignmentPreference.Right,
                showMentionChip = true
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
