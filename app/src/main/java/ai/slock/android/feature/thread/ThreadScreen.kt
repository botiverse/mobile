package ai.slock.android.feature.thread

import ai.slock.android.core.data.preferences.MyMessageAlignmentPreference
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.feature.tasks.TasksContent
import ai.slock.android.feature.tasks.TasksPanel
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.android.ui.components.SlockAvatar
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockLabeledCheckbox
import ai.slock.android.ui.components.SlockMemberPickerItem
import ai.slock.android.ui.components.SlockMemberPickerMessage
import ai.slock.android.ui.components.SlockMemberPickerSection
import ai.slock.android.ui.components.SlockModal
import ai.slock.android.ui.components.SlockModalAction
import ai.slock.android.ui.components.SlockModalActionStyle
import ai.slock.android.ui.components.SlockModalTextField
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockNormalFontFamily
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

val ThreadMessageBodyTextStyle: TextStyle
    get() =
            SlockType.Body.copy(
                    fontWeight = FontWeight.Normal,
                    fontFamily = SlockNormalFontFamily,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
            )

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
        onOpenTask: (TaskSummary) -> Unit = {},
        onMarkTaskDone: (TaskSummary) -> Unit = {},
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit = { _, _ -> },
        onRetryTasks: () -> Unit = {},
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
        parentJumpTarget: ThreadParentJumpTarget? = null,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit = {},
        onRetryThreadLoad: () -> Unit = {},
        channelSuggestions: List<ConversationSummary> = emptyList()
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
    val dismissComposerKeyboard: () -> Unit =
            remember(focusManager, keyboardController) {
                {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            }
    val displayedMembers = membersPanelState.modalMembers
    val selectedChannelMemberDetail =
            displayedMembers.firstOrNull { it.id.value == selectedChannelMemberDetailId }
    val membersCount = membersPanelState.memberCount
    LaunchedEffect(membersPanelState.loadState) {
        val members =
                (membersPanelState.loadState as? ScreenLoadState.Content)?.data?.members.orEmpty()
        if (members.isNotEmpty()) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockMembers",
                    "render_count source=thread_members_panel agentsCount=${members.count { it.kind == MemberKind.Agent }} " +
                            "humansCount=${members.count { it.kind == MemberKind.Human }}"
            )
        }
    }

    Box(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite).imePadding()) {
        val showModeTabs = replyHeaderContext == null
        Column(
                modifier =
                        Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).drawBehind {
                            val stroke = SlockStroke.Default.toPx()
                            drawLine(
                                    color = SlockColor.BorderBlack,
                                    start = Offset(0f, stroke / 2f),
                                    end = Offset(size.width, stroke / 2f),
                                    strokeWidth = stroke
                            )
                        }
        ) {
            when (val loadState = state.loadState) {
                ScreenLoadState.Loading -> {
                    ThreadTopHeader(
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage
                    )
                    if (showModeTabs) {
                        ThreadModeTabs(
                                selectedTab = state.selectedTab,
                                onModeSelected = onModeSelected,
                                modifier =
                                        Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                        )
                    }
                    Box(
                            modifier =
                                    Modifier.weight(1f)
                                            .fillMaxWidth()
                                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                                            .padding(SlockSpacing.Md),
                            contentAlignment = Alignment.Center
                    ) {
                        Text(
                                "Loading thread...",
                                style = SlockType.Button,
                                color = SlockColor.TextMutedGray
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
                is ScreenLoadState.Content -> {
                    val header = loadState.data.header
                    val readOnlyPreview = !header.joined
                    val composerState = state.composerState.forHeader(header)
                    ThreadTopHeader(
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            summary = header,
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage
                    )
                    if (showModeTabs) {
                        ThreadModeTabs(
                                selectedTab = state.selectedTab,
                                onModeSelected = onModeSelected,
                                modifier =
                                        Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                        )
                    }
                    if (replyHeaderContext == null) {
                        ThreadRefreshBanner(
                                status = loadState.data.refreshStatus,
                                errorCode = loadState.data.refreshErrorCode,
                                onRetry = onRefreshThread
                        )
                    }
                    if (state.selectedTab == ThreadTab.Tasks) {
                        TasksPanel(
                                state = state.tasksState
                                        ?: loadState.data.tasks.toThreadTasksUiState(),
                                showChannelFilter = false,
                                onTaskClick = onOpenTask,
                                onTaskStatusChange = onTaskStatusChange,
                                onRetry = onRetryTasks,
                                modifier =
                                        Modifier.weight(1f)
                                                .fillMaxWidth()
                                                .dismissKeyboardOnPointerDown(
                                                        dismissComposerKeyboard
                                                )
                        )
                    } else {
                        Column(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            if (replyHeaderContext != null) {
                                ThreadOriginPanel(
                                        context = replyHeaderContext,
                                        parentJumpTarget = parentJumpTarget,
                                        onOpenParentMessage = onOpenParentMessage,
                                        modifier =
                                                Modifier.dismissKeyboardOnPointerDown(
                                                        dismissComposerKeyboard
                                                )
                                )
                            }
                            ThreadMessageList(
                                    content = loadState.data,
                                    threadId = header.threadId.value,
                                    currentMemberId = currentMemberId,
                                    myMessageAlignment = myMessageAlignment,
                                    modifier =
                                            Modifier.weight(1f)
                                                    .fillMaxWidth()
                                                    .dismissKeyboardOnPointerDown(
                                                            dismissComposerKeyboard
                                                    ),
                                    onLoadOlderMessages = onLoadOlderMessages,
                                    onRetryLoadOlderMessages = onRetryLoadOlderMessages,
                                    onLoadNewerMessages = onLoadNewerMessages,
                                    onRetryLoadNewerMessages = onRetryLoadNewerMessages,
                                    onNearBottomChange = onNearBottomChange,
                                    onOpenMessageThread = onOpenMessageThread,
                                    onOpenMemberProfile = onOpenMemberProfile,
                                    onOpenMarkdownTag = onOpenMarkdownTag,
                                    onOpenTask = onOpenTask,
                                    onMarkTaskDone = onMarkTaskDone,
                                    onRetryThreadLoad = onRetryThreadLoad,
                                    attachmentOpenStates = attachmentOpenStates,
                                    onOpenAttachment = onOpenAttachment,
                                    showOlderPageState = replyHeaderContext == null,
                                    showReplyAction = replyHeaderContext == null,
                                    resolveMemberName = { id ->
                                        (membersPanelState.loadState as? ScreenLoadState.Content)
                                            ?.data?.members
                                            ?.firstOrNull { it.id == id }
                                            ?.displayName
                                    }
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
                        val memberSuggestions =
                                (membersPanelState.loadState as? ScreenLoadState.Content)
                                        ?.data?.members.orEmpty()
                        ThreadComposerBar(
                                composerState = composerState,
                                placeholder = "Message #${header.name}",
                                onComposerChange = onComposerChange,
                                onAsTaskChange = onAsTaskChange,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onRemoveAttachment = onRemoveAttachment,
                                onRetryAttachment = onRetryAttachment,
                                onSend = onSend,
                                memberSuggestions = memberSuggestions,
                                channelSuggestions = channelSuggestions
                        )
                    }
                }
                is ScreenLoadState.Empty -> {
                    ThreadTopHeader(
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage
                    )
                    if (showModeTabs) {
                        ThreadModeTabs(
                                selectedTab = state.selectedTab,
                                onModeSelected = onModeSelected,
                                modifier =
                                        Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                        )
                    }
                    Box(
                            modifier =
                                    Modifier.weight(1f)
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
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage
                    )
                    if (showModeTabs) {
                        ThreadModeTabs(
                                selectedTab = state.selectedTab,
                                onModeSelected = onModeSelected,
                                modifier =
                                        Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                        )
                    }
                    Box(
                            modifier =
                                    Modifier.weight(1f)
                                            .fillMaxWidth()
                                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                                            .padding(SlockSpacing.Md),
                            contentAlignment = Alignment.Center
                    ) {
                        SlockErrorState(
                                title = "Thread failed to load",
                                detail = "Error code: ${loadState.error.code}",
                                onRetry = onRetryThreadLoad
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
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage
                    )
                    if (showModeTabs) {
                        ThreadModeTabs(
                                selectedTab = state.selectedTab,
                                onModeSelected = onModeSelected,
                                modifier =
                                        Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                        )
                    }
                    Box(
                            modifier =
                                    Modifier.weight(1f)
                                            .fillMaxWidth()
                                            .dismissKeyboardOnPointerDown(dismissComposerKeyboard)
                                            .padding(SlockSpacing.Md),
                            contentAlignment = Alignment.Center
                    ) {
                        SlockEmptyState(
                                title = "No permission",
                                detail = loadState.blocker.requiredCapability
                                                ?: loadState.blocker.code
                        )
                    }
                    ThreadComposerBar(
                            composerState =
                                    state.composerState.copy(
                                            enabled = false,
                                            noPermissionCode = loadState.blocker.code
                                    ),
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
                BackHandler(enabled = true) { selectedChannelMemberDetailId = null }
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
                        membersLoadState = membersPanelState.loadState,
                        addMemberStatus = membersPanelState.addStatus,
                        addMemberErrorCode = membersPanelState.addErrorCode,
                        removeStatus = membersPanelState.removeStatus,
                        removingMemberId = membersPanelState.removingMemberId,
                        removeErrorCode = membersPanelState.removeErrorCode,
                        onMemberClick = { member ->
                            selectedChannelMemberDetailId = member.id.value
                        },
                        onAddMemberClick = { showAddMember = true },
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
        if (showAddMember) {
            ThreadAddMemberModal(
                    members = displayedMembers,
                    addMemberQuery = addMemberQuery,
                    selectedAddMemberId = selectedAddMemberId,
                    selectedAddMemberKind = selectedAddMemberKind,
                    addMemberError = addMemberError,
                    membersLoadState = membersPanelState.loadState,
                    candidatesLoadState = membersPanelState.candidatesLoadState,
                    addMemberStatus = membersPanelState.addStatus,
                    addMemberErrorCode = membersPanelState.addErrorCode,
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
                    onAddMemberClick = {
                        val memberId = selectedAddMemberId
                        if (memberId == null) {
                            addMemberError = true
                        } else {
                            onAddChannelMember(memberId, selectedAddMemberKind)
                            addMemberError = false
                        }
                    },
                    onDismiss = {
                        showAddMember = false
                        addMemberQuery = ""
                        selectedAddMemberId = null
                        addMemberError = false
                    }
            )
        }
        if (showSettingsModal) {
            ThreadSettingsModal(
                    initialName = (state.loadState as? ScreenLoadState.Content)?.data?.header?.name
                                    ?: "onboarding-artin",
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
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(
                                    horizontal = threadHorizontalPadding(),
                                    vertical = SlockSpacing.Sm
                            ),
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
                    text =
                            if (status == ThreadRefreshStatus.Loading) {
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
private fun rememberSampleThreadMembersPanelState(): ThreadMembersPanelState = remember {
    ThreadMembersPanelState(
            loadState =
                    ScreenLoadState.Content(
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
    val joinEnabled =
            status != ChannelJoinStatus.Joining && status != ChannelJoinStatus.NoPermission
    val bottomPadding = threadComposerBottomPadding()
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(SlockColor.BorderBlack))
        SlockButton(
                text =
                        when (status) {
                            ChannelJoinStatus.Joining -> "Joining #${header.name}"
                            ChannelJoinStatus.NoPermission -> "Cannot join #${header.name}"
                            else -> "Join #${header.name}"
                        },
                onClick = onJoin,
                modifier =
                        Modifier.fillMaxWidth()
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
        parentJumpTarget: ThreadParentJumpTarget? = null,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val mobileLayout = maxWidth < 420.dp
        val horizontalPadding = threadHorizontalPadding()
        val subtitleMaxLines = 1
        val title =
                if (replyHeaderContext != null) {
                    "Threads"
                } else {
                    summary?.name ?: "onboarding-artin"
                }
        val subtitle =
                if (replyHeaderContext != null) {
                    replyHeaderContext.replyHeaderSubtitle()
                } else {
                    summary?.subtitle
                }
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .height(SlockSize.PageHeaderHeight)
                                .background(SlockColor.SurfaceWhite)
                                .drawBehind {
                                    val stroke = SlockStroke.Default.toPx()
                                    drawLine(
                                            color = SlockColor.BorderBlack,
                                            start = Offset(0f, size.height - stroke / 2f),
                                            end = Offset(size.width, size.height - stroke / 2f),
                                            strokeWidth = stroke
                                    )
                                }
                                .padding(
                                        horizontal = horizontalPadding,
                                        vertical = SlockSpacing.Sm
                                ),
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
                if (!subtitle.isNullOrBlank()) {
                    Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                    ) {
                        Text(
                                text = subtitle,
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray,
                                maxLines = subtitleMaxLines,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (parentJumpTarget != null) {
                HeaderIconButton(
                        SlockIcons.LogIn,
                        "Open original message",
                        onClick = { onOpenParentMessage(parentJumpTarget) }
                )
            }
            HeaderIconButton(SlockIcons.Settings, "Settings", onClick = onSettingsClick)
            HeaderIconButton(SlockIcons.LogOut, "Leave")
            ThreadMembersButton(count = membersCount, onClick = onMembersClick)
        }
    }
}

private fun ThreadReplyHeaderContext.replyHeaderSubtitle(): String? {
    val parentChannel =
            parentChannelName?.takeIf { it.isNotBlank() }?.let { name ->
                if (name.startsWith("#")) name else "#$name"
            }
    val parentPreview = parentMessagePreview?.takeIf { it.isNotBlank() }
    val origin =
            listOfNotNull(parentChannel, parentPreview).joinToString(" · ").takeIf {
                it.isNotBlank()
            }
    return origin?.let { "Origin: $it" }
}

@Composable
private fun ThreadOriginPanel(
        context: ThreadReplyHeaderContext,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit,
        modifier: Modifier = Modifier
) {
    Column(
            modifier =
                    modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .drawBehind {
                                val stroke = SlockStroke.Default.toPx()
                                drawLine(
                                        color = SlockColor.BorderBlack,
                                        start = Offset(0f, size.height - stroke / 2f),
                                        end = Offset(size.width, size.height - stroke / 2f),
                                        strokeWidth = stroke
                                )
                            }
                            .padding(
                                    horizontal = threadHorizontalPadding(),
                                    vertical = SlockSpacing.Md
                            ),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        val parentMessage = context.parentMessage
        if (parentMessage != null) {
            ThreadMessageItem(
                    message = parentMessage,
                    onOpenThread =
                            parentJumpTarget?.let {
                                { onOpenParentMessage(it) }
                            },
                    onOpenAuthorProfile = null
            )
        } else {
            ThreadOriginFallbackMessage(
                    context = context,
                    parentJumpTarget = parentJumpTarget,
                    onOpenParentMessage = onOpenParentMessage
            )
        }
    }
}

@Composable
private fun ThreadOriginFallbackMessage(
        context: ThreadReplyHeaderContext,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit
) {
    val preview = context.parentMessagePreview?.takeIf { it.isNotBlank() } ?: "Original message"
    val channelName =
            context.parentChannelName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name -> if (name.startsWith("#")) name else "#$name" }
    val fallbackAuthor =
            MemberProfile(
                    id = MemberId("thread-origin-fallback"),
                    displayName = channelName ?: "Thread origin",
                    handle = "thread-origin",
                    kind = MemberKind.Agent,
                    roleDescription = "Original message"
            )
    val fallbackMessage =
            Message(
                    id = parentJumpTarget?.parentMessageId ?: MessageId("thread-origin-fallback"),
                    threadId = ThreadId("thread-origin-fallback"),
                    author = fallbackAuthor,
                    timestampLabel = "",
                    body = preview
            )
    ThreadMessageItem(
            message = fallbackMessage,
            onOpenThread =
                    parentJumpTarget?.let {
                        { onOpenParentMessage(it) }
                    },
            onOpenAuthorProfile = null
    )
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

private val ThreadMemberModalItem.memberKey: String
    get() = "${kind.name}:${id.value}"

private fun ThreadMemberModalItem.toSlockMemberPickerItem(): SlockMemberPickerItem =
        SlockMemberPickerItem(
                id = memberKey,
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
    get() =
            when (val state = loadState) {
                is ScreenLoadState.Content ->
                        state.data.members.map { it.toThreadMemberModalItem() }
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
                role = roleDescription
                                ?: when (kind) {
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

private fun threadSampleMemberProfiles(): List<MemberProfile> =
        listOf(
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
    val disabled =
            status == ThreadSettingsModalStatus.Disabled ||
                    status == ThreadSettingsModalStatus.NoPermission
    val enabled = !saving && !disabled

    SlockModal(
            title = "EDIT CHANNEL",
            onDismiss = onDismiss,
            modifier = modifier,
            enabled = !disabled,
            dismissOnScrimClick = !saving,
            errorText =
                    when (status) {
                        ThreadSettingsModalStatus.Error -> "Unable to save sample channel settings."
                        ThreadSettingsModalStatus.NoPermission ->
                                "You do not have permission to edit this channel."
                        else -> null
                    },
            secondaryAction =
                    SlockModalAction(
                            text = "Cancel",
                            onClick = onDismiss,
                            style = SlockModalActionStyle.Outline,
                            enabled = !saving
                    ),
            primaryAction =
                    SlockModalAction(
                            text = if (saving) "Saving" else "Save Changes",
                            onClick = onDismiss,
                            enabled = enabled && name.isNotBlank(),
                            loading = saving
                    ),
            dangerActions =
                    listOf(
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
            errorText =
                    when (state.errorCode) {
                        null -> null
                        "no_handler" -> "No app can open this image externally."
                        "missing_url" -> "Image preview URL is missing."
                        else -> "Preview error: ${state.errorCode}"
                    },
            secondaryAction =
                    SlockModalAction(
                            text = "Close",
                            onClick = onDismiss,
                            style = SlockModalActionStyle.Outline
                    ),
            primaryAction =
                    SlockModalAction(
                            text = "Open externally",
                            onClick = onOpenExternally,
                            enabled = !state.url.isNullOrBlank()
                    )
    ) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            ThreadAttachmentRemoteImagePreview(url = state.url, name = title)
            Text(
                    text =
                            listOfNotNull(
                                            state.mimeType?.takeIf { it.isNotBlank() },
                                            state.attachmentId?.value
                                    )
                                    .joinToString(" · "),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ThreadAttachmentRemoteImagePreview(url: String?, name: String) {
    val imageBitmap by
            produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    initialValue = null,
                    key1 = url
            ) {
                value =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                        url?.takeIf { it.isNotBlank() }?.let {
                                            URL(it).openStream().use { input ->
                                                BitmapFactory.decodeStream(input)?.asImageBitmap()
                                            }
                                        }
                                    }
                                    .getOrNull()
                        }
            }
    Box(
            modifier =
                    Modifier.fillMaxWidth()
                            .heightIn(min = 220.dp)
                            .background(
                                    SlockColor.AppBgLightBlue,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clip(RoundedCornerShape(SlockRadius.Xs)),
            contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                    bitmap = imageBitmap!!,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier =
                            Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(SlockSpacing.Sm)
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
        membersLoadState: ScreenLoadState<ThreadMembersPanelContent> =
                ScreenLoadState.Content(ThreadMembersPanelContent(threadSampleMemberProfiles())),
        addMemberStatus: ThreadMemberAddStatus = ThreadMemberAddStatus.Idle,
        addMemberErrorCode: String? = null,
        removeStatus: ThreadMemberRemoveStatus = ThreadMemberRemoveStatus.Idle,
        removingMemberId: MemberId? = null,
        removeErrorCode: String? = null,
        onMemberClick: (MemberProfile) -> Unit = {},
        onAddMemberClick: () -> Unit,
        onDismiss: () -> Unit,
        enabled: Boolean = true,
        loading: Boolean = false,
        noPermission: Boolean = false
) {
    val membersLoading = loading || membersLoadState is ScreenLoadState.Loading
    val addLoading = addMemberStatus == ThreadMemberAddStatus.Adding
    val addBlocked =
            noPermission ||
                    addMemberStatus == ThreadMemberAddStatus.NoPermission ||
                    (membersLoadState as? ScreenLoadState.Content)?.data?.canAddMembers == false ||
                    membersLoadState is ScreenLoadState.NoPermission
    SlockModal(
            title = "MEMBERS (${members.size})",
            onDismiss = onDismiss,
            enabled = enabled && !membersLoading && !addLoading,
            errorText =
                    when {
                        addBlocked -> "You do not have permission to add members."
                        addMemberStatus == ThreadMemberAddStatus.Failed ->
                                "Unable to add member: ${addMemberErrorCode ?: "member_add_failed"}"
                        removeStatus == ThreadMemberRemoveStatus.Failed ->
                                "Unable to remove member: ${removeErrorCode ?: "member_remove_failed"}"
                        membersLoadState is ScreenLoadState.Error ->
                                "Unable to load members: ${membersLoadState.error.code}"
                        membersLoadState is ScreenLoadState.NoPermission ->
                                "Unable to load members: ${membersLoadState.blocker.code}"
                        else -> null
                    }
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(SlockColor.SurfaceWhite)
                                .border(SlockStroke.Default, SlockColor.BorderBlack)
                                .padding(SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                ThreadMembersList(
                        members = members,
                        membersLoading = membersLoading,
                        onMemberClick = onMemberClick
                )
            }
            SlockButton(
                    text = "+ Add Member",
                    onClick = onAddMemberClick,
                    modifier = Modifier.fillMaxWidth(),
                    variant = SlockButtonVariant.Pink,
                    enabled = enabled && !membersLoading && !addBlocked,
                    loading = addLoading
            )
        }
    }
}

@Composable
private fun ThreadAddMemberModal(
        members: List<ThreadMemberModalItem>,
        addMemberQuery: String,
        selectedAddMemberId: String? = null,
        selectedAddMemberKind: MemberKind = MemberKind.Human,
        addMemberError: Boolean,
        membersLoadState: ScreenLoadState<ThreadMembersPanelContent> =
                ScreenLoadState.Content(ThreadMembersPanelContent(threadSampleMemberProfiles())),
        candidatesLoadState: ScreenLoadState<ThreadMembersPickerContent> =
                ScreenLoadState.Content(ThreadMembersPickerContent(threadSampleMemberProfiles())),
        addMemberStatus: ThreadMemberAddStatus = ThreadMemberAddStatus.Idle,
        addMemberErrorCode: String? = null,
        onAddMemberQueryChange: (String) -> Unit,
        onAddMemberSelected: (ThreadMemberModalItem) -> Unit = {},
        onAddMemberClick: () -> Unit,
        onDismiss: () -> Unit,
        enabled: Boolean = true
) {
    val membersLoading = membersLoadState is ScreenLoadState.Loading
    val addLoading = addMemberStatus == ThreadMemberAddStatus.Adding
    val addBlocked =
            addMemberStatus == ThreadMemberAddStatus.NoPermission ||
                    (membersLoadState as? ScreenLoadState.Content)?.data?.canAddMembers == false ||
                    membersLoadState is ScreenLoadState.NoPermission
    val selectedMemberAlreadyJoined =
            selectedAddMemberId != null &&
                    members.any {
                        it.id.value == selectedAddMemberId && it.kind == selectedAddMemberKind
                    }
    SlockModal(
            title = "ADD MEMBER",
            onDismiss = onDismiss,
            enabled = enabled && !addLoading,
            errorText =
                    when {
                        addBlocked -> "You do not have permission to add members."
                        addMemberError -> "Select a member before adding."
                        addMemberStatus == ThreadMemberAddStatus.Failed ->
                                "Unable to add member: ${addMemberErrorCode ?: "member_add_failed"}"
                        candidatesLoadState is ScreenLoadState.Error ->
                                "Unable to load people: ${candidatesLoadState.error.code}"
                        candidatesLoadState is ScreenLoadState.NoPermission ->
                                "Unable to load people: ${candidatesLoadState.blocker.code}"
                        else -> null
                    },
            secondaryAction =
                    SlockModalAction(
                            text = "Back",
                            onClick = onDismiss,
                            style = SlockModalActionStyle.Outline,
                            enabled = !addLoading
                    ),
            primaryAction =
                    SlockModalAction(
                            text = "Add Selected",
                            onClick = onAddMemberClick,
                            enabled =
                                    enabled &&
                                            !membersLoading &&
                                            !addBlocked &&
                                            selectedAddMemberId != null &&
                                            !selectedMemberAlreadyJoined,
                            loading = addLoading
                    )
    ) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
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
                    selectedMemberKind = selectedAddMemberKind,
                    enabled = enabled && !membersLoading && !addLoading && !addBlocked,
                    onMemberSelected = onAddMemberSelected
            )
        }
    }
}

@Composable
private fun ThreadMembersList(
        members: List<ThreadMemberModalItem>,
        membersLoading: Boolean,
        onMemberClick: (MemberProfile) -> Unit
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
            LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                item(key = "agents") {
                    ThreadMemberSection(
                            label = "AGENTS",
                            members = members.filter { it.kind == MemberKind.Agent },
                            onMemberClick = onMemberClick
                    )
                }
                item(key = "humans") {
                    ThreadMemberSection(
                            label = "HUMANS",
                            members = members.filter { it.kind == MemberKind.Human },
                            onMemberClick = onMemberClick
                    )
                }
            }
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
    val isRemoving =
            removeStatus == ThreadMemberRemoveStatus.Removing && removingMemberId == member.id
    SlockModal(
            title = member.name.uppercase(),
            onDismiss = onDismiss,
            enabled = !isRemoving,
            errorText =
                    when {
                        removeStatus == ThreadMemberRemoveStatus.Failed &&
                                removingMemberId == member.id ->
                                "Unable to remove agent: ${removeErrorCode ?: "member_remove_failed"}"
                        removeStatus == ThreadMemberRemoveStatus.NoPermission &&
                                removingMemberId == member.id ->
                                "Unable to remove agent: no permission"
                        else -> null
                    },
            secondaryAction =
                    SlockModalAction(
                            text = "Back",
                            onClick = onBack,
                            style = SlockModalActionStyle.Outline,
                            enabled = !isRemoving
                    ),
            primaryAction =
                    if (member.kind == MemberKind.Agent) {
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
                            fallbackColor =
                                    if (member.kind == MemberKind.Agent) {
                                        SlockColor.AccentCyan
                                    } else {
                                        SlockColor.AccentLavender
                                    }
                    )
                }
                Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
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
        selectedMemberKind: MemberKind,
        enabled: Boolean,
        onMemberSelected: (ThreadMemberModalItem) -> Unit
) {
    val currentMemberKeys =
            remember(currentMembers) { currentMembers.map { it.memberKey }.toSet() }
    val normalizedQuery = query.trim()
    val candidates =
            (candidatesLoadState as? ScreenLoadState.Content)
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
                    .distinctBy { it.memberKey }
    val agentCandidates =
            candidates.filter { it.kind == MemberKind.Agent }
    val humanCandidates =
            candidates.filter { it.kind == MemberKind.Human }
    val selectedKeys =
            selectedMemberId?.let { id -> "${selectedMemberKind.name}:$id" }?.let(::setOf).orEmpty()
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .background(SlockColor.SurfaceWhite),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when (candidatesLoadState) {
            ScreenLoadState.Loading -> SlockMemberPickerMessage("Loading people...")
            is ScreenLoadState.Error ->
                    SlockMemberPickerMessage(
                            "Unable to load people: ${candidatesLoadState.error.code}"
                    )
            is ScreenLoadState.NoPermission ->
                    SlockMemberPickerMessage(
                            "Unable to load people: ${candidatesLoadState.blocker.requiredCapability ?: candidatesLoadState.blocker.code}"
                    )
            is ScreenLoadState.Empty -> SlockMemberPickerMessage("No people available.")
            is ScreenLoadState.Content -> {
                if (candidates.isEmpty()) {
                    SlockMemberPickerMessage("No matching people.")
                } else {
                    ThreadMemberPickerSection(
                            label = "AGENTS",
                            members = agentCandidates,
                            currentMemberKeys = currentMemberKeys,
                            selectedMemberKeys = selectedKeys,
                            enabled = enabled,
                            emptyText = "None",
                            onMemberSelected = onMemberSelected
                    )
                    ThreadMemberPickerSection(
                            label = "HUMANS",
                            members = humanCandidates,
                            currentMemberKeys = currentMemberKeys,
                            selectedMemberKeys = selectedKeys,
                            enabled = enabled,
                            emptyText = "None",
                            onMemberSelected = onMemberSelected
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
        currentMemberKeys: Set<String>,
        selectedMemberKeys: Set<String>,
        enabled: Boolean,
        emptyText: String,
        onMemberSelected: (ThreadMemberModalItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
        Text(text = label, style = SlockType.SectionLabel, color = SlockColor.TextMutedGray)
        if (members.isEmpty()) {
            Text(
                    text = emptyText,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.padding(vertical = SlockSpacing.Xs)
            )
        } else {
            members.forEach { member ->
                val alreadyJoined = member.memberKey in currentMemberKeys
                ThreadMemberPickerRow(
                        member = member,
                        selected = member.memberKey in selectedMemberKeys,
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
    val rowColor =
            when {
                alreadyJoined -> SlockColor.SurfaceCream
                selected -> SlockColor.AccentLime
                else -> SlockColor.SurfaceWhite
            }
    Row(
            modifier =
                    Modifier.fillMaxWidth()
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
                    fallbackColor =
                            if (member.kind == MemberKind.Agent) {
                                SlockColor.AccentCyan
                            } else {
                                SlockColor.AccentLavender
                            }
            )
            if (member.kind != MemberKind.Human) {
                Box(
                        modifier =
                                Modifier.align(Alignment.BottomEnd)
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
                    text =
                            if (member.kind == MemberKind.Human) member.role
                            else "${member.role} · ${member.presence.name.lowercase()}",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
        Text(
                text =
                        when {
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
        onMemberClick: (MemberProfile) -> Unit = {}
) {
    Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(text = label, style = SlockType.SectionLabel, color = SlockColor.TextMutedGray)
        if (members.isEmpty()) {
            Text(text = "None", style = SlockType.Caption, color = SlockColor.TextMutedGray)
        } else {
            members.forEach { member ->
                ThreadMemberRow(
                        member = member,
                        onClick = { onMemberClick(member.profile) }
                )
            }
        }
    }
}

@Composable
private fun ThreadMemberRow(
        member: ThreadMemberModalItem,
        onClick: () -> Unit = {}
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
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
                    fallbackColor =
                            if (member.kind == MemberKind.Agent) {
                                SlockColor.AccentCyan
                            } else {
                                SlockColor.AccentLavender
                            }
            )
            if (member.kind != MemberKind.Human) {
                Box(
                        modifier =
                                Modifier.align(Alignment.BottomEnd)
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
                    text =
                            if (member.kind == MemberKind.Human) member.role
                            else "${member.role} · ${member.presence.name.lowercase()}",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val Presence.presenceColor: Color
    get() =
            when (this) {
                Presence.Active -> SlockColor.AccentLime
                Presence.Away -> SlockColor.BrandYellow
                Presence.Offline -> SlockColor.TextMutedGray
            }

@Composable
private fun ThreadHashTile() {
    Box(
            modifier =
                    Modifier.size(40.dp)
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
            modifier =
                    modifier.size(28.dp)
                            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
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
            modifier =
                    Modifier.padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(shadow)
                            .size(38.dp)
                            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
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
private fun ThreadMembersButton(count: Int, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shadow = if (pressed) SlockShadow.None else SlockShadow.Small
    Row(
            modifier =
                    Modifier.padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(shadow)
                            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
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
        Icon(
                SlockIcons.Users,
                contentDescription = "Members",
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(18.dp)
        )
        Text(count.toString(), style = SlockType.Button, color = SlockColor.TextBlack)
    }
}

@Composable
fun ThreadModeTabs(
        selectedTab: ThreadTab,
        onModeSelected: (ThreadTab) -> Unit,
        modifier: Modifier = Modifier
) {
    val tabWidth = 116.dp
    val tabHeight = 38.dp
    val tabBorderWidth = SlockStroke.Default
    Column(
            modifier =
                    modifier
                            .fillMaxWidth()
                            .height(tabHeight)
                            .background(SlockColor.SurfaceWhite)
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ThreadTab.entries.forEachIndexed { index, mode ->
                val selected = selectedTab == mode
                Row(
                        modifier =
                                Modifier.width(tabWidth)
                                        .fillMaxHeight()
                                        .background(
                                                if (selected) SlockColor.BrandYellow
                                                else SlockColor.SurfaceWhite
                                        )
                                        .clickable(role = Role.Tab) { onModeSelected(mode) }
                                        .padding(
                                                horizontal = SlockSpacing.Md,
                                                vertical = SlockSpacing.Xs
                                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                                Arrangement.spacedBy(SlockSpacing.Sm, Alignment.CenterHorizontally)
                ) {
                    Icon(
                            imageVector =
                                    if (mode == ThreadTab.Chat) SlockIcons.MessageSquare
                                    else SlockIcons.ListTodo,
                            contentDescription = null,
                            tint = SlockColor.TextBlack,
                            modifier = Modifier.size(18.dp)
                    )
                    Text(mode.name.uppercase(), style = SlockType.Button, color = SlockColor.TextBlack)
                }
                Box(
                        modifier =
                                Modifier.height(tabHeight - tabBorderWidth)
                                        .width(tabBorderWidth)
                                        .background(SlockColor.BorderBlack)
                )
            }
            Box(
                    modifier =
                            Modifier.weight(1f)
                                    .fillMaxHeight()
                                    .background(SlockColor.SurfaceWhite)
            )
        }
        Box(
                modifier =
                        Modifier.fillMaxWidth()
                                .height(tabBorderWidth)
                                .background(SlockColor.BorderBlack)
        )
    }
}

@Composable
private fun ThreadContextNotice(text: String) {
    Text(
            text = text,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Thin, SlockColor.TextMutedGray)
                            .padding(SlockSpacing.Sm)
    )
}

@Composable
private fun ThreadInlineRetryNotice(
        text: String,
        retryLabel: String,
        onRetry: () -> Unit
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Thin, SlockColor.TextMutedGray)
                            .padding(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
                text = text,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
        )
        SlockButton(text = retryLabel, onClick = onRetry, variant = SlockButtonVariant.Outline)
    }
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
        onOpenTask: (TaskSummary) -> Unit,
        onMarkTaskDone: (TaskSummary) -> Unit,
        onRetryThreadLoad: () -> Unit,
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
        onOpenAttachment: (Attachment) -> Unit,
        showOlderPageState: Boolean = true,
        showReplyAction: Boolean = true,
        resolveMemberName: (MemberId) -> String? = { null }
) {
    val horizontalPadding = threadHorizontalPadding()
    val showOlderPageState = showOlderPageState && content.shouldShowOlderPageState
    val beforeSeq = content.messages.mapNotNull { it.seq }.minOrNull()
    val isLoadingOlder = content.olderMessagesStatus == MessagePageStatus.Loading
    val leadingItemCount =
            (if (showOlderPageState) 1 else 0) +
                    (if (content.historyLimited || content.hasGap) 1 else 0) +
                    (if (content.replyActionErrorCode != null) 1 else 0)
    val listState =
            rememberLazyListState(
                    initialFirstVisibleItemIndex =
                            (leadingItemCount + content.messages.lastIndex).coerceAtLeast(0)
            )
    val highlightedMessageIndex =
            content.highlightedMessageId?.let { highlightedId ->
                content.messages.indexOfFirst { it.id == highlightedId }.takeIf { it >= 0 }
            }
    val targetMessageIndex =
            highlightedMessageIndex ?: content.messages.lastIndex.takeIf { it >= 0 }
    val targetListIndex = targetMessageIndex?.let { leadingItemCount + it }
    val latestMessageListIndex =
            content.messages.lastIndex.takeIf { it >= 0 }?.let { leadingItemCount + it }
    var initialPositionApplied by remember(threadId) { mutableStateOf(false) }
    var observedLastMessageId by remember(threadId) { mutableStateOf<MessageId?>(null) }
    var followNewMessages by remember(threadId) { mutableStateOf(true) }
    val shouldLoadNewer by
            remember(content.canLoadMoreAfter, content.newerMessagesStatus) {
                derivedStateOf {
                    val totalItems = listState.layoutInfo.totalItemsCount
                    val lastVisibleIndex =
                            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
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
    val showBackToBottom by
            remember(latestMessageListIndex) {
                derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val lastVisibleItem = visibleItems.lastOrNull()
                    if (latestMessageListIndex == null ||
                                    layoutInfo.totalItemsCount <= 0 ||
                                    lastVisibleItem == null
                    ) {
                        false
                    } else {
                        val latestVisible = visibleItems.any { it.index == latestMessageListIndex }
                        val viewportBottom = layoutInfo.viewportEndOffset
                        val distanceBelowViewport =
                                if (latestVisible) {
                                    0
                                } else {
                                    layoutInfo
                                            .visibleItemsInfo
                                            .filter { it.index > lastVisibleItem.index }
                                            .sumOf { it.size }
                                            .takeIf { it > 0 }
                                            ?: (latestMessageListIndex - lastVisibleItem.index)
                                                    .coerceAtLeast(0) * 48
                                }
                        val lastItemHasBottomGap =
                                lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                                        lastVisibleItem.offset + lastVisibleItem.size <=
                                                viewportBottom + 24
                        !lastItemHasBottomGap &&
                                (lastVisibleItem.index < latestMessageListIndex ||
                                        distanceBelowViewport > 24)
                    }
                }
            }
    val lastMessageId = content.messages.lastOrNull()?.id
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(
            threadId,
            showOlderPageState,
            content.canLoadMoreBefore,
            content.olderMessagesStatus,
            beforeSeq,
            leadingItemCount,
            content.messages.size
    ) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadPaging",
                "older_state threadId=$threadId loadingRowVisible=$showOlderPageState canLoadMoreBefore=${content.canLoadMoreBefore} isLoadingOlder=$isLoadingOlder status=${content.olderMessagesStatus} beforeSeq=${beforeSeq ?: -1L} leadingItemCount=$leadingItemCount messageCount=${content.messages.size}"
        )
    }

    LaunchedEffect(
            threadId,
            content.canLoadMoreBefore,
            leadingItemCount,
            content.olderMessagesStatus
    ) {
        if (!content.canLoadMoreBefore || content.olderMessagesStatus != MessagePageStatus.Idle) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadPaging",
                    "older_watch_skip threadId=$threadId reason=disabled canLoadMoreBefore=${content.canLoadMoreBefore} isLoadingOlder=$isLoadingOlder status=${content.olderMessagesStatus} beforeSeq=${beforeSeq ?: -1L} firstVisible=${listState.firstVisibleItemIndex} firstOffset=${listState.firstVisibleItemScrollOffset} leadingItemCount=$leadingItemCount messageCount=${content.messages.size}"
            )
            return@LaunchedEffect
        }
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadPaging",
                "older_watch_start threadId=$threadId canLoadMoreBefore=${content.canLoadMoreBefore} isLoadingOlder=$isLoadingOlder beforeSeq=${beforeSeq ?: -1L} firstVisible=${listState.firstVisibleItemIndex} firstOffset=${listState.firstVisibleItemScrollOffset} leadingItemCount=$leadingItemCount messageCount=${content.messages.size}"
        )
        var requestSent = false
        snapshotFlow {
            OlderTopProbe(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    firstRealMessageIndex = leadingItemCount
            )
        }
                .distinctUntilChanged()
                .collect { probe ->
                    val isNearTop = probe.firstVisibleItemIndex <= probe.firstRealMessageIndex + 1
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadPaging",
                            "older_top_probe threadId=$threadId firstVisible=${probe.firstVisibleItemIndex} firstOffset=${probe.firstVisibleItemScrollOffset} firstRealMessageIndex=${probe.firstRealMessageIndex} nearTop=$isNearTop canLoadMoreBefore=${content.canLoadMoreBefore} isLoadingOlder=$isLoadingOlder status=${content.olderMessagesStatus} beforeSeq=${beforeSeq ?: -1L}"
                    )
                    if (!isNearTop) {
                        requestSent = false
                        return@collect
                    }
                    if (requestSent) {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockThreadPaging",
                                "older_trigger_skip threadId=$threadId reason=already_requested firstVisible=${probe.firstVisibleItemIndex} firstOffset=${probe.firstVisibleItemScrollOffset} canLoadMoreBefore=${content.canLoadMoreBefore} isLoadingOlder=$isLoadingOlder status=${content.olderMessagesStatus} beforeSeq=${beforeSeq ?: -1L}"
                        )
                        return@collect
                    }
                    val firstVisibleMessageItem =
                            listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                val messageIndex = item.index - leadingItemCount
                                messageIndex in content.messages.indices
                            }
                    val anchorMessageId =
                            firstVisibleMessageItem?.let { item ->
                                content.messages.getOrNull(item.index - leadingItemCount)?.id
                            }
                    val anchorOffset = firstVisibleMessageItem?.offset?.coerceAtLeast(0) ?: 0
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadPaging",
                            "older_trigger threadId=$threadId firstVisible=${listState.firstVisibleItemIndex} firstOffset=${listState.firstVisibleItemScrollOffset} leadingItemCount=$leadingItemCount messageCount=${content.messages.size} canLoadMoreBefore=${content.canLoadMoreBefore} isLoadingOlder=$isLoadingOlder status=${content.olderMessagesStatus} beforeSeq=${beforeSeq ?: -1L} anchorMessageId=${anchorMessageId?.value.orEmpty()}"
                    )
                    requestSent = true
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
            yield()
            val initialScrollOffset = 0
            listState.scrollToItem(targetListIndex, initialScrollOffset)
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadScroll",
                    "initial_scroll threadId=$threadId targetListIndex=$targetListIndex latestListIndex=${latestMessageListIndex ?: -1} messageCount=${content.messages.size} highlighted=${content.highlightedMessageId?.value.orEmpty()} offset=$initialScrollOffset"
            )
            initialPositionApplied = true
            observedLastMessageId = lastMessageId
            followNewMessages = content.highlightedMessageId == null
        }
    }
    LaunchedEffect(
            content.anchorMessageId,
            content.anchorOffset,
            leadingItemCount,
            content.messages
    ) {
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
    val density = LocalDensity.current
    val keyboardBottomPx = WindowInsets.ime.getBottom(density)
    LaunchedEffect(keyboardBottomPx) {
        if (initialPositionApplied && followNewMessages && latestMessageListIndex != null) {
            scrollThreadListToBottom(
                    listState = listState,
                    lastIndex = latestMessageListIndex,
                    threadId = threadId,
                    reason = "keyboard_inset"
            )
        }
    }
    LaunchedEffect(
            content.messages.size,
            latestMessageListIndex,
            initialPositionApplied,
            followNewMessages
    ) {
        if (initialPositionApplied && followNewMessages && latestMessageListIndex != null) {
            scrollThreadListToBottom(
                    listState = listState,
                    lastIndex = latestMessageListIndex,
                    threadId = threadId,
                    reason = "message_count_changed",
                    messageCount = content.messages.size
            )
        }
    }
    LaunchedEffect(latestMessageListIndex, initialPositionApplied, followNewMessages) {
        if (!initialPositionApplied || !followNewMessages || latestMessageListIndex == null) {
            return@LaunchedEffect
        }
        var firstVisibleItemIndex = listState.firstVisibleItemIndex
        var firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
        repeat(8) {
            delay(80)
            if (!followNewMessages) return@LaunchedEffect
            val currentFirstVisibleItemIndex = listState.firstVisibleItemIndex
            val currentFirstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            val userMovedAwayFromBottom =
                    currentFirstVisibleItemIndex < firstVisibleItemIndex ||
                            (currentFirstVisibleItemIndex == firstVisibleItemIndex &&
                                    currentFirstVisibleItemScrollOffset >
                                            firstVisibleItemScrollOffset + 24)
            if (userMovedAwayFromBottom) {
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadScroll",
                        "initial_layout_settle_stop threadId=$threadId reason=user_moved firstVisible=$currentFirstVisibleItemIndex firstOffset=$currentFirstVisibleItemScrollOffset previousFirstVisible=$firstVisibleItemIndex previousFirstOffset=$firstVisibleItemScrollOffset"
                )
                return@LaunchedEffect
            }
            val targetAtBottom = listState.isTargetItemAtBottom(latestMessageListIndex)
            if (!targetAtBottom) {
                scrollThreadListToBottom(
                        listState = listState,
                        lastIndex = latestMessageListIndex,
                        threadId = threadId,
                        reason = "initial_layout_settle",
                        messageCount = content.messages.size
                )
                firstVisibleItemIndex = listState.firstVisibleItemIndex
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            } else {
                firstVisibleItemIndex = currentFirstVisibleItemIndex
                firstVisibleItemScrollOffset = currentFirstVisibleItemScrollOffset
            }
        }
    }
    LaunchedEffect(lastMessageId, latestMessageListIndex) {
        if (lastMessageId != null &&
                        latestMessageListIndex != null &&
                        observedLastMessageId != null &&
                        observedLastMessageId != lastMessageId
        ) {
            val shouldFollow = followNewMessages
            if (shouldFollow) {
                scrollThreadListToBottom(
                        listState = listState,
                        lastIndex = latestMessageListIndex,
                        threadId = threadId,
                        reason = "last_message_changed",
                        messageCount = content.messages.size
                )
            }
            observedLastMessageId = lastMessageId
            followNewMessages = shouldFollow
        } else if (lastMessageId != null && observedLastMessageId == null && initialPositionApplied
        ) {
            observedLastMessageId = lastMessageId
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
                modifier =
                        Modifier.fillMaxSize()
                                .threadWheelScroll(listState)
                                .testTag(THREAD_MESSAGE_LIST_TAG),
                state = listState,
                contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                                horizontal = horizontalPadding,
                                vertical = SlockSpacing.Md
                        ),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            if (showOlderPageState) {
                item(key = "older-page-state") {
                    ThreadMessagePageState(
                            canLoadMore = content.canLoadMoreBefore,
                            status = content.olderMessagesStatus,
                            errorCode = content.olderMessagesErrorCode,
                            loadingText = "Loading older messages...",
                            idleText = "Scroll to top to load older messages",
                            endText = "Beginning of messages",
                            onRetry = onRetryLoadOlderMessages
                    )
                }
            }
            if (content.historyLimited || content.hasGap) {
                item(key = "context-notice") {
                    ThreadContextNotice(
                            text =
                                    if (content.historyLimited) {
                                        "History is limited in this preview"
                                    } else {
                                        "Message history has a gap"
                                    }
                    )
                }
            }
            if (content.replyActionErrorCode != null) {
                item(key = "reply-action-error") {
                    ThreadInlineRetryNotice(
                            text = "Reply thread failed: ${content.replyActionErrorCode}",
                            retryLabel = "Retry",
                            onRetry = onRetryThreadLoad
                    )
                }
            }
            itemsIndexed(items = content.messages, key = { _, message -> message.id.value }) {
                    index,
                    message ->
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    if (message.messageType == MessageType.System) {
                        ThreadSystemMessageItem(
                                message = message,
                                highlighted = message.id == content.highlightedMessageId,
                                onOpenThread =
                                        if (showReplyAction) {
                                            { onOpenMessageThread(message) }
                                        } else {
                                            null
                                        }
                        )
                    } else {
                        ThreadMessageItem(
                                message = message,
                                isCurrentUserMessage =
                                        message.isCurrentUserMessage(currentMemberId),
                                myMessageAlignment = myMessageAlignment,
                                highlighted = message.id == content.highlightedMessageId,
                                attachmentOpenStates = attachmentOpenStates,
                                onOpenAttachment = onOpenAttachment,
                                onOpenThread =
                                        if (showReplyAction) {
                                            { onOpenMessageThread(message) }
                                        } else {
                                            null
                                        },
                                onOpenAuthorProfile = { onOpenMemberProfile(message.author) },
                                onOpenTask = { onOpenTask(it) },
                                onMarkTaskDone = onMarkTaskDone,
                                onOpenMarkdownTag = onOpenMarkdownTag,
                                resolveMemberName = resolveMemberName
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
                    modifier =
                            Modifier.align(Alignment.BottomEnd)
                                    .padding(end = SlockSpacing.Md, bottom = SlockSpacing.Md)
                                    .testTag(THREAD_BACK_TO_BOTTOM_TAG),
                    onClick = {
                        val lastIndex =
                                latestMessageListIndex
                                        ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(
                                                0
                                        )
                        coroutineScope.launch {
                            scrollThreadListToBottom(
                                    listState = listState,
                                    lastIndex = lastIndex,
                                    threadId = threadId,
                                    reason = "back_to_bottom_click",
                                    messageCount = content.messages.size
                            )
                        }
                    }
            )
        }
    }
}

private suspend fun scrollThreadListToBottom(
        listState: LazyListState,
        lastIndex: Int,
        threadId: String,
        reason: String,
        messageCount: Int? = null
) {
    yield()
    listState.scrollToItem(lastIndex, 0)
    yield()
    listState.scrollToItem(lastIndex, 0)
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockThreadScroll",
            "scroll_to_bottom reason=$reason threadId=$threadId lastIndex=$lastIndex messageCount=${messageCount ?: -1} firstVisible=${listState.firstVisibleItemIndex} firstOffset=${listState.firstVisibleItemScrollOffset} totalItems=${listState.layoutInfo.totalItemsCount}"
    )
}

private fun LazyListState.isTargetItemAtBottom(targetIndex: Int): Boolean {
    val layoutInfo = layoutInfo
    val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    val lastItem = layoutInfo.visibleItemsInfo.lastOrNull()
    return targetItem != null &&
            lastItem != null &&
            lastItem.index >= targetIndex &&
            targetItem.offset + targetItem.size <= layoutInfo.viewportEndOffset + 24
}

private data class OlderTopProbe(
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int,
        val firstRealMessageIndex: Int
)

@Composable
private fun threadHorizontalPadding(): Dp =
        if (LocalConfiguration.current.screenWidthDp < 420) SlockSpacing.Sm else SlockSpacing.Md

@Composable
private fun ThreadBackToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shadow = if (pressed) SlockShadow.None else SlockShadow.Small
    Row(
            modifier =
                    modifier.heightIn(min = 28.dp)
                            .slockHardShadow(shadow)
                            .background(SlockColor.AccentCyan, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
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
                        val scrollDelta =
                                event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
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
        MessagePageStatus.Error ->
                Row(
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
        MessagePageStatus.Idle ->
                if (canLoadMore && idleText != null) {
                    ThreadContextNotice(text = idleText)
                }
        MessagePageStatus.EndReached ->
                if (endText != null) {
                    ThreadContextNotice(text = endText)
                }
    }
}

private val ThreadContent.shouldShowNewerPageState: Boolean
    get() =
            canLoadMoreAfter ||
                    newerMessagesStatus == MessagePageStatus.Loading ||
                    newerMessagesStatus == MessagePageStatus.Error

private val ThreadContent.shouldShowOlderPageState: Boolean
    get() =
            canLoadMoreBefore ||
                    olderMessagesStatus == MessagePageStatus.Loading ||
                    olderMessagesStatus == MessagePageStatus.Error ||
                    olderMessagesStatus == MessagePageStatus.EndReached

internal const val THREAD_MESSAGE_LIST_TAG = "thread-message-list"
internal const val THREAD_BACK_TO_BOTTOM_TAG = "thread-back-to-bottom"

private fun List<TaskSummary>.toThreadTasksUiState(): TasksUiState =
        if (isEmpty()) {
            TasksUiState(ScreenLoadState.Empty(EmptyReason.NoTasks))
        } else {
            TasksUiState(
                    ScreenLoadState.Content(
                            TasksContent(
                                    summaryCounts = summaryCounts(),
                                    tasks = this
                            )
                    )
            )
        }

private fun List<TaskSummary>.summaryCounts(): Map<TaskStatus, Int> =
        TaskStatus.entries.associateWith { status -> count { it.status == status } }

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
                Row(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(SlockColor.SurfaceWhite)
                                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                        .padding(SlockSpacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                                text =
                                        listOfNotNull(task.taskNumber?.let { "#$it" }, task.title)
                                                .joinToString(" "),
                                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                                color = SlockColor.TextBlack,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                        )
                        Text(
                                text =
                                        listOfNotNull(
                                                        task.assignee?.displayName,
                                                        task.updatedAtLabel
                                                )
                                                .joinToString(" · "),
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                            text = task.status.threadLabel.uppercase(),
                            style = SlockType.SectionLabel,
                            color = SlockColor.TextBlack,
                            modifier =
                                    Modifier.background(task.status.threadColor)
                                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp)
                    )
                }
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
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState> = emptyMap(),
        onOpenAttachment: (Attachment) -> Unit = {},
        onOpenThread: (() -> Unit)? = null,
        onOpenAuthorProfile: (() -> Unit)? = null,
        onOpenTask: ((TaskSummary) -> Unit)? = null,
        onMarkTaskDone: (TaskSummary) -> Unit = {},
        onShareMessage: (Message) -> Unit = {},
        onCopyMessageLink: (Message) -> Unit = {},
        onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit = {},
        resolveMemberName: (MemberId) -> String? = { null }
) {
    val context = LocalContext.current
    var showMessageActions by remember(message.id.value) { mutableStateOf(false) }
    var messageActionAnchor by remember(message.id.value) { mutableStateOf(IntOffset.Zero) }
    var messageActionAreaOffset by remember(message.id.value) { mutableStateOf(IntOffset.Zero) }
    var messageBodyOffset by remember(message.id.value) { mutableStateOf(IntOffset.Zero) }
    fun openMessageActions(anchor: IntOffset) {
        messageActionAnchor = anchor
        showMessageActions = true
    }
    fun toggleMessageActions(anchor: IntOffset) {
        if (showMessageActions) {
            showMessageActions = false
        } else {
            openMessageActions(anchor)
        }
    }
    var saveFeedbackVisible by remember(message.id.value) { mutableStateOf(false) }
    LaunchedEffect(saveFeedbackVisible) {
        if (saveFeedbackVisible) {
            delay(1_800)
            saveFeedbackVisible = false
        }
    }
    val horizontalPadding = threadHorizontalPadding()
    val alignCurrentUserRight =
            isCurrentUserMessage && myMessageAlignment == MyMessageAlignmentPreference.Right
    val messageMaxWidthFraction = if (alignCurrentUserRight) 0.82f else 1f
    val bodyFillWidth = !alignCurrentUserRight || !message.body.isPlainSingleLineMessage()
    Row(
            modifier =
                    modifier.fillMaxWidth()
                            .background(
                                    if (highlighted) SlockColor.BrandYellow.copy(alpha = 0.25f)
                                    else Color.Transparent
                            )
                            .border(
                                    width = if (highlighted) SlockStroke.Thin else 0.dp,
                                    color =
                                            if (highlighted) SlockColor.BorderBlack
                                            else Color.Transparent
                            )
                            .padding(horizontal = horizontalPadding, vertical = 6.dp),
            horizontalArrangement =
                    if (alignCurrentUserRight) {
                        Arrangement.spacedBy(SlockSpacing.Md, Alignment.End)
                    } else {
                        Arrangement.spacedBy(SlockSpacing.Md)
                    }
    ) {
        if (!alignCurrentUserRight) {
            ThreadAvatar(message.author, onClick = onOpenAuthorProfile)
        }
        Column(
                modifier =
                        Modifier.weight(1f)
                                .onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.boundsInRoot()
                                    messageActionAreaOffset =
                                            IntOffset(
                                                    bounds.left.roundToInt(),
                                                    bounds.top.roundToInt()
                                            )
                                }
                                .messageActionsTrigger(
                                        enabled =
                                                message.body.isNotBlank() ||
                                                        message.attachments.isNotEmpty() ||
                                                        message.replyCount > 0 ||
                                                        message.task != null,
                                        rootOffsetProvider = { messageActionAreaOffset },
                                        onOpen = ::toggleMessageActions,
                                        onDismiss = { showMessageActions = false }
                                )
                                .semantics {
                                    contentDescription = "Message actions"
                                    onClick(label = "Message actions") {
                                        showMessageActions = !showMessageActions
                                        true
                                    }
                                },
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                horizontalAlignment = if (alignCurrentUserRight) Alignment.End else Alignment.Start
        ) {
            Row(
                    modifier = Modifier.messageContentWidth(messageMaxWidthFraction),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement =
                            if (alignCurrentUserRight) {
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
                        text = message.author.roleDescription
                                        ?: message.author.kind.name.lowercase(),
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
            }
            Box(
                    modifier =
                            Modifier.messageContentWidth(
                                            maxWidthFraction = messageMaxWidthFraction,
                                            fill = bodyFillWidth,
                                            alignment = Alignment.End
                                    )
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInRoot()
                                        messageBodyOffset =
                                                IntOffset(
                                                        bounds.left.roundToInt(),
                                                        bounds.top.roundToInt()
                                                )
                                    }
            ) {
                SelectionContainer {
                    ThreadMarkdownBody(
                            body = message.body,
                            textAlign = if (alignCurrentUserRight) TextAlign.End else TextAlign.Start,
                            onTagClick = onOpenMarkdownTag
                    )
                }
            }
            if (saveFeedbackVisible) {
                ThreadContextNotice("Saved locally")
            }
            if (message.attachments.isNotEmpty()) {
                ThreadMessageAttachments(
                        attachments = message.attachments,
                        states = attachmentOpenStates,
                        onOpenAttachment = onOpenAttachment,
                        modifier = Modifier.messageContentWidth(messageMaxWidthFraction)
                )
            }
            if (message.task != null || message.replyCount > 0) {
                ThreadMessageFooterMeta(
                        message = message,
                        onOpenThread = onOpenThread,
                        alignEnd = alignCurrentUserRight,
                        modifier = Modifier.messageContentWidth(messageMaxWidthFraction)
                )
            }
            if (showMessageActions) {
                ThreadMessageActionsPopup(
                        message = message,
                        anchor =
                                if (messageActionAnchor == IntOffset.Zero) {
                                    messageActionAreaOffset
                                } else {
                                    messageActionAnchor
                                },
                        onDismiss = { showMessageActions = false },
                        onReply =
                                onOpenThread?.let { openThread ->
                                    {
                                        openThread()
                                        showMessageActions = false
                                    }
                                },
                        onCopy = {
                            context.copyThreadMessageBody(message.body)
                            showMessageActions = false
                        },
                        onCopyLink = {
                            onCopyMessageLink(message)
                            showMessageActions = false
                        },
                        onSave = {
                            saveFeedbackVisible = true
                            showMessageActions = false
                        },
                        onShare = {
                            onShareMessage(message)
                            showMessageActions = false
                        },
                        onMarkDone = {
                            message.task?.let(onMarkTaskDone)
                            showMessageActions = false
                        }
                )
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
internal const val THREAD_MESSAGE_ACTION_MENU_TAG = "thread-message-action-menu"
private val THREAD_MESSAGE_ACTION_MENU_WIDTH = 208.dp

private fun Modifier.messageActionsTrigger(
        enabled: Boolean,
        rootOffsetProvider: () -> IntOffset,
        onOpen: (IntOffset) -> Unit,
        onDismiss: () -> Unit
): Modifier =
        if (!enabled) {
            this
        } else {
            this.clickable(role = Role.Button, onClickLabel = "Message actions") {
                        onOpen(rootOffsetProvider())
                    }.pointerInput(onOpen, onDismiss) {
                        awaitEachGesture {
                            val down =
                                    awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial
                                    )
                            val pointerId = down.id
                            val rootOffset = rootOffsetProvider()
                            var totalPositionChange = androidx.compose.ui.geometry.Offset.Zero
                            var lastPosition = down.position
                            var wasDragDismissed = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes
                                        .firstOrNull { it.id == pointerId }
                                        ?.let { change ->
                                            totalPositionChange += change.positionChange()
                                            lastPosition = change.position
                                            if (event.type == PointerEventType.Release &&
                                                            !wasDragDismissed
                                            ) {
                                                change.consume()
                                            }
                                        }
                                if (event.changes.any {
                                                it.id == pointerId &&
                                                        it.pressed &&
                                                        totalPositionChange.getDistance() > 6f
                                        }
                                ) {
                                    wasDragDismissed = true
                                    onDismiss()
                                }
                            } while (event.changes.any { it.id == pointerId && it.pressed })
                            if (!wasDragDismissed) {
                                onOpen(
                                        IntOffset(
                                                rootOffset.x + lastPosition.x.roundToInt(),
                                                rootOffset.y + lastPosition.y.roundToInt()
                                        )
                                )
                            }
                        }
                    }
        }

@Composable
private fun ThreadMessageActionsPopup(
        message: Message,
        anchor: IntOffset,
        onDismiss: () -> Unit,
        onReply: (() -> Unit)?,
        onCopy: () -> Unit,
        onCopyLink: () -> Unit,
        onSave: () -> Unit,
        onShare: () -> Unit,
        onMarkDone: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val navigationBottomPx = WindowInsets.navigationBars.getBottom(density)
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val bottomInsetPx = maxOf(navigationBottomPx, imeBottomPx)
    val menuWidthPx =
            menuSize.width.takeIf { it > 0 }
                    ?: with(density) { THREAD_MESSAGE_ACTION_MENU_WIDTH.roundToPx() }
    val menuHeightPx = menuSize.height
    val menuOffset =
            remember(anchor, screenWidthPx, screenHeightPx, bottomInsetPx, menuWidthPx, menuHeightPx) {
                val gapPx = with(density) { SlockSpacing.Sm.roundToPx() }
                val bottomLimit = (screenHeightPx - bottomInsetPx - gapPx).coerceAtLeast(gapPx)
                val x = anchor.x.coerceIn(gapPx, (screenWidthPx - menuWidthPx - gapPx).coerceAtLeast(gapPx))
                val preferredBelowY = anchor.y + gapPx
                val preferredAboveY = anchor.y - menuHeightPx - gapPx
                val maxTopY = (bottomLimit - menuHeightPx).coerceAtLeast(gapPx)
                val y =
                        if (menuHeightPx > 0 && preferredBelowY + menuHeightPx > bottomLimit) {
                            if (preferredAboveY >= gapPx) {
                                preferredAboveY
                            } else {
                                maxTopY
                            }
                        } else {
                            preferredBelowY.coerceIn(
                                    gapPx,
                                    maxTopY
                            )
                        }
                IntOffset(x, y)
            }
    Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = onDismiss,
            properties =
                    PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true
                    )
    ) {
        Box(
                modifier =
                        Modifier.size(
                                width = configuration.screenWidthDp.dp,
                                height = configuration.screenHeightDp.dp
                        )
        ) {
            Box(
                    modifier =
                            Modifier.fillMaxSize()
                                    .clickable(
                                            indication = null,
                                            interactionSource = remember {
                                                MutableInteractionSource()
                                            },
                                            onClick = onDismiss
                                    )
            )
            Column(
                    modifier =
                            Modifier.offset { menuOffset }
                                    .padding(
                                            end = SlockShadow.Default.offsetX,
                                            bottom = SlockShadow.Default.offsetY
                                    )
                                    .slockHardShadow(SlockShadow.Default)
                                    .width(THREAD_MESSAGE_ACTION_MENU_WIDTH)
                                    .onGloballyPositioned { coordinates ->
                                        menuSize = coordinates.size
                                    }
                                    .testTag(THREAD_MESSAGE_ACTION_MENU_TAG)
                                    .background(SlockColor.SurfaceWhite)
                                    .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                ThreadMessageActionMenuItem(
                        label = "Share messages...",
                        icon = SlockIcons.Share2,
                        onClick = onShare
                )
                ThreadMessageActionDivider()
                ThreadMessageActionMenuItem(
                        label = "Copy link",
                        icon = SlockIcons.Link,
                        onClick = onCopyLink
                )
                ThreadMessageActionMenuItem(
                        label = "Copy markdown",
                        icon = SlockIcons.Copy,
                        onClick = onCopy
                )
                ThreadMessageActionDivider()
                ThreadMessageActionMenuItem(
                        label = "Save message",
                        icon = SlockIcons.Bookmark,
                        onClick = onSave
                )
                if (onReply != null) {
                    ThreadMessageActionMenuItem(
                            label = "Reply",
                            icon = SlockIcons.MessageSquare,
                            onClick = onReply
                    )
                }
                if (message.task != null && message.task.status != TaskStatus.Done) {
                    ThreadMessageActionDivider()
                    ThreadMessageActionMenuItem(
                            label = "Reopen task",
                            icon = SlockIcons.RefreshCw,
                            onClick = onMarkDone
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageActionMenuItem(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        onClick: () -> Unit
) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .defaultMinSize(minHeight = 46.dp)
                            .background(SlockColor.SurfaceWhite)
                            .clickable(role = Role.Button, onClick = onClick)
                            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(20.dp)
        )
        Text(
                text = label,
                style = SlockType.Body.copy(fontWeight = FontWeight.Normal),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadMessageActionDivider() {
    Box(
            modifier =
                    Modifier.fillMaxWidth()
                            .height(SlockStroke.Default)
                            .background(SlockColor.BorderBlack)
    )
}

private fun Context.copyThreadMessageBody(body: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Slock message", body))
}

internal const val THREAD_MESSAGE_FOOTER_META_TAG = "thread-message-footer-meta"
internal const val THREAD_MESSAGE_FOOTER_ASSIGNEE_TAG = "thread-message-footer-assignee"
internal const val THREAD_MESSAGE_FOOTER_REPLIES_TAG = "thread-message-footer-replies"

@Composable
private fun ThreadMessageFooterMeta(
        message: Message,
        onOpenThread: (() -> Unit)?,
        alignEnd: Boolean,
        modifier: Modifier = Modifier
) {
    val task = message.task
    val replyLabel = message.replyCount.takeIf { it > 0 }?.let { "$it replies" }
    if (task == null && replyLabel == null) return
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
    }
    Row(
            modifier =
                    modifier.testTag(THREAD_MESSAGE_FOOTER_META_TAG),
            horizontalArrangement =
                    if (alignEnd) {
                        Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End)
                    } else {
                        Arrangement.spacedBy(SlockSpacing.Sm)
                    },
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (task != null) {
            val taskLabel = task.taskNumber?.let { "#$it" } ?: "Task"
            val assigneeName = task.assignee?.resolvedFooterDisplayName()
            val taskMetaLabel =
                    listOfNotNull(taskLabel, assigneeName?.let { "@$it" }).joinToString(" ")
            ThreadChip(
                    label = taskMetaLabel,
                    color = task.status.threadColor,
                    icon = task.status.threadIcon,
                    modifier =
                            Modifier.weight(1f, fill = false)
                                    .widthIn(max = 260.dp)
                                    .testTag(THREAD_MESSAGE_FOOTER_ASSIGNEE_TAG),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
        if (replyLabel != null) {
            ThreadChip(
                    label = replyLabel,
                    color = SlockColor.AppBgLightBlue,
                    icon = SlockIcons.MessageSquare,
                    onClick = onOpenThread,
                    modifier =
                            Modifier.widthIn(max = 132.dp)
                                    .testTag(THREAD_MESSAGE_FOOTER_REPLIES_TAG)
            )
        }
    }
}

private fun MemberProfile.resolvedFooterDisplayName(): String? {
    val display = displayName.trim().takeIf { it.isNotBlank() && it != id.value }
    if (display != null) return display.removePrefix("@")
    return handle.trim()
            .removePrefix("@")
            .takeIf { it.isNotBlank() && it != id.value }
}

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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
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
    val thumbnailUrl = attachment.thumbnailUrl?.takeIf { it.isNotBlank() }
    val borderColor =
            if (state.status == AttachmentOpenStatus.Error) {
                SlockColor.AccentRed
            } else {
                SlockColor.BorderBlack
            }
    Box(
            modifier =
                    Modifier.size(92.dp)
                            .background(
                                    SlockColor.EmptyStateGray.copy(alpha = 0.22f),
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .border(
                                    SlockStroke.Default,
                                    borderColor,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clickable(enabled = !isLoading, role = Role.Button, onClick = onOpen)
                            .clip(RoundedCornerShape(SlockRadius.Xs)),
            contentAlignment = Alignment.Center
    ) {
        ThreadAttachmentThumbnail(
                thumbnailUrl = thumbnailUrl,
                isImage = isImage,
                isLoading = isLoading
        )
        ThreadAttachmentTileLabel(
                label =
                        when (state.status) {
                            AttachmentOpenStatus.Loading -> "Opening..."
                            AttachmentOpenStatus.Error ->
                                    state.errorCode?.takeIf { it.isNotBlank() } ?: "Retry"
                            AttachmentOpenStatus.Idle -> attachment.name
                        },
                isError = state.status == AttachmentOpenStatus.Error,
                modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun ThreadAttachmentThumbnail(
        thumbnailUrl: String?,
        isImage: Boolean,
        isLoading: Boolean
) {
    val imageBitmap by
            produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    initialValue = null,
                    key1 = thumbnailUrl
            ) {
                value =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                        thumbnailUrl?.let {
                                            URL(it).openStream().use { input ->
                                                BitmapFactory.decodeStream(input)?.asImageBitmap()
                                            }
                                        }
                                    }
                                    .getOrNull()
                        }
            }
    Box(
                modifier =
                        Modifier.fillMaxSize()
                                .background(
                                        if (imageBitmap != null) SlockColor.SurfaceWhite
                                        else if (isImage) SlockColor.AccentPink
                                        else SlockColor.SurfaceWhite,
                                        RoundedCornerShape(SlockRadius.Xs)
                ),
                contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = SlockColor.TextBlack,
                        trackColor = SlockColor.SurfaceWhite
                )
            } else if (imageBitmap != null) {
                Image(
                        bitmap = imageBitmap!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(SlockRadius.Xs))
                )
            } else {
                Icon(
                        imageVector = if (isImage) SlockIcons.Image else SlockIcons.FileText,
                        contentDescription = null,
                        tint = SlockColor.TextBlack,
                        modifier = Modifier.size(24.dp)
                )
            }
        }
}

@Composable
private fun ThreadAttachmentTileLabel(
        label: String,
        isError: Boolean,
        modifier: Modifier = Modifier
) {
    Text(
            text = label,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = if (isError) SlockColor.AccentRed else SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                    modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite.copy(alpha = 0.92f))
                            .padding(horizontal = SlockSpacing.Xs, vertical = 3.dp)
    )
}

@Composable
internal fun ThreadSystemMessageItem(
        message: Message,
        modifier: Modifier = Modifier,
        highlighted: Boolean = false,
        onOpenThread: (() -> Unit)? = null
) {
    val timestamp = message.timestampLabel.toThreadTimestampLabel()
    var expanded by rememberSaveable(message.id.value) { mutableStateOf(false) }
    val summary = message.collapsedSystemSummary(timestamp)
    val horizontalPadding = threadHorizontalPadding()
    Column(
            modifier =
                    modifier.fillMaxWidth()
                            .background(
                                    if (highlighted) SlockColor.BrandYellow.copy(alpha = 0.18f)
                                    else Color.Transparent
                            )
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
        if (message.task != null || message.replyCount > 0) {
            ThreadMessageFooterMeta(
                    message = message,
                    onOpenThread = onOpenThread,
                    alignEnd = false,
                    modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThreadSystemMessageExpandedContent(message: Message) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Sm))
                            .border(
                                    SlockStroke.Thin,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Sm)
                            )
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
            Text(text = metadata, style = SlockType.Caption, color = SlockColor.TextMutedGray)
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
                    metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                        if (key.isNotBlank() && value.isNotBlank()) {
                            add(
                                    "${key.sanitizeThreadSystemText()}: ${value.sanitizeThreadSystemText()}"
                            )
                        }
                    }
                }
                .joinToString("\n")

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

data class ThreadMarkdownTag(val label: String, val kind: ThreadMarkdownTagKind)

enum class ThreadMarkdownTagKind {
    Channel,
    Task,
    UserMention
}

internal fun threadMarkdownTaskReferenceKey(label: String): String =
        label.removePrefix("#").trim().lowercase()

internal fun threadMarkdownTagDisplayLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return trimmed
    val prefix = if (trimmed.startsWith("@")) "@" else if (trimmed.startsWith("#")) "#" else ""
    if (prefix.isEmpty()) return trimmed
    return prefix + trimmed.dropWhile { it == prefix.first() }
}

private fun threadInlineTagPlaceholderWidthDp(
        label: String,
        hasIcon: Boolean,
        density: Density,
        textMeasurer: TextMeasurer
): Dp {
    val horizontalPadding = SlockSpacing.Sm * 2
    val iconWidth = if (hasIcon) 12.dp + SlockSpacing.Xs else 0.dp
    return with(density) {
        textMeasurer.measure(text = label, style = SlockType.Caption).size.width.toDp()
    } + horizontalPadding + iconWidth + 2.dp
}

private fun threadInlineCodePlaceholderWidthDp(
        code: String,
        density: Density,
        textMeasurer: TextMeasurer
): Dp =
        with(density) {
            textMeasurer.measure(
                    text = code,
                    style =
                            SlockType.Caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.sp
                            )
            ).size.width.toDp()
        } + SlockSpacing.Sm * 2 + 2.dp

internal fun threadInlineTagEstimatedWidthSp(label: String, hasIcon: Boolean): Float {
    val iconWidth = if (hasIcon) 16f else 0f
    return 18f + iconWidth + label.length * 8f
}

internal fun threadInlineCodeEstimatedWidthSp(code: String): Float {
    return 14f + code.length * 9f
}

internal sealed interface InlineBodyPart {
    data class PlainText(val text: String) : InlineBodyPart
    data class Tag(val tag: ThreadMarkdownTag) : InlineBodyPart
    data class Code(val code: String) : InlineBodyPart
}

internal fun String.splitInlineBodyParts(): List<InlineBodyPart> {
    val parts = mutableListOf<InlineBodyPart>()
    // Inline code (backtick span) is matched first so tags inside code are not parsed as tags
    val combined =
            Regex(
                    """`([^`\n]+)`|(?<![\p{L}\p{N}_])@[A-Za-z0-9][A-Za-z0-9_.\-]{0,63}|(?<![\p{L}\p{N}_])#\d+|(?<![\p{L}\p{N}_])#[\p{L}][\p{L}\p{N}_-]{0,63}"""
            )
    var cursor = 0
    for (match in combined.findAll(this)) {
        if (match.range.first > cursor) {
            parts.add(InlineBodyPart.PlainText(this.substring(cursor, match.range.first)))
        }
        val fullMatch = match.value
        when {
            fullMatch.startsWith('`') -> {
                parts.add(InlineBodyPart.Code(match.groupValues[1]))
            }
            fullMatch.startsWith('@') -> {
                parts.add(InlineBodyPart.Tag(ThreadMarkdownTag(fullMatch, ThreadMarkdownTagKind.UserMention)))
            }
            fullMatch.drop(1).all { it.isDigit() } -> {
                parts.add(InlineBodyPart.Tag(ThreadMarkdownTag(fullMatch, ThreadMarkdownTagKind.Task)))
            }
            fullMatch.equals("#task", ignoreCase = true) -> {
                parts.add(InlineBodyPart.PlainText(fullMatch))
            }
            else -> {
                parts.add(InlineBodyPart.Tag(ThreadMarkdownTag(fullMatch, ThreadMarkdownTagKind.Channel)))
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < length) parts.add(InlineBodyPart.PlainText(this.substring(cursor)))
    return parts
}

@Composable
private fun ThreadInlineTagsBody(
        parts: List<InlineBodyPart>,
        style: androidx.compose.ui.text.TextStyle,
        textAlign: TextAlign,
        modifier: Modifier = Modifier,
        onTagClick: (ThreadMarkdownTag) -> Unit
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val textMeasurer = rememberTextMeasurer()
    var textLayoutResult by remember(parts) { mutableStateOf<TextLayoutResult?>(null) }

    val annotatedString = remember(parts) {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                when (part) {
                    is InlineBodyPart.PlainText -> append(part.text)
                    is InlineBodyPart.Tag -> {
                        pushStringAnnotation(ThreadInlineTagAnnotation, index.toString())
                        appendInlineContent("slock:$index", part.tag.label)
                        pop()
                    }
                    is InlineBodyPart.Code -> appendInlineContent("slock-code:$index", part.code)
                }
            }
        }
    }

    val inlineContent: Map<String, InlineTextContent> = remember(parts, fontScale, textMeasurer) {
        buildMap {
            parts.forEachIndexed { index, part ->
                when (part) {
                    is InlineBodyPart.Tag -> {
                        val tag = part.tag
                        val label = threadMarkdownTagDisplayLabel(tag.label)
                        val icon =
                                when (tag.kind) {
                                    ThreadMarkdownTagKind.Channel -> null
                                    ThreadMarkdownTagKind.Task -> null
                                    ThreadMarkdownTagKind.UserMention -> SlockIcons.User
                                }
                        val width =
                                with(density) {
                                    threadInlineTagPlaceholderWidthDp(
                                            label = label,
                                            hasIcon = icon != null,
                                            density = density,
                                            textMeasurer = textMeasurer
                                    ).toSp()
                                }
                        val heightSp = 16f + 6f / fontScale
                        put(
                                "slock:$index",
                                InlineTextContent(
                                        Placeholder(
                                                width = width,
                                                height = heightSp.sp,
                                                placeholderVerticalAlign =
                                                        PlaceholderVerticalAlign.Center
                                        )
                                ) {
                                    ThreadChip(
                                            label = label,
                                            color =
                                                    when (tag.kind) {
                                                        ThreadMarkdownTagKind.Channel ->
                                                                SlockColor.AccentCyan
                                                        ThreadMarkdownTagKind.Task ->
                                                                SlockColor.AccentOrange
                                                        ThreadMarkdownTagKind.UserMention ->
                                                                SlockColor.AccentLavender
                                                    },
                                            icon = icon,
                                            onClick = { onTagClick(tag) }
                                    )
                                }
                        )
                    }
                    is InlineBodyPart.Code -> {
                        val code = part.code
                        val width =
                                with(density) {
                                    threadInlineCodePlaceholderWidthDp(
                                            code = code,
                                            density = density,
                                            textMeasurer = textMeasurer
                                    ).toSp()
                                }
                        val heightSp = 18f + 6f / fontScale
                        put(
                                "slock-code:$index",
                                InlineTextContent(
                                        Placeholder(
                                                width = width,
                                                height = heightSp.sp,
                                                placeholderVerticalAlign =
                                                        PlaceholderVerticalAlign.Center
                                        )
                                ) {
                                    Box(
                                            modifier =
                                                    Modifier.fillMaxSize()
                                                            .background(
                                                                    SlockColor.BrandYellow.copy(
                                                                            alpha = 0.42f
                                                                    )
                                                            )
                                                            .border(
                                                                    SlockStroke.Thin,
                                                                    SlockColor.BorderBlack
                                                            )
                                                            .padding(horizontal = SlockSpacing.Sm),
                                            contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(
                                                text = code,
                                                style =
                                                        SlockType.Caption.copy(
                                                                fontFamily = FontFamily.Monospace,
                                                                fontWeight = FontWeight.Normal,
                                                                fontSize = 13.sp
                                                ),
                                                color = SlockColor.TextBlack,
                                                maxLines = 1,
                                                overflow = TextOverflow.Visible
                                        )
                                    }
                                }
                        )
                    }
                    is InlineBodyPart.PlainText -> Unit
                }
            }
        }
    }

    Text(
            text = annotatedString,
            style = style.copy(textAlign = textAlign),
            color = SlockColor.TextBlack,
            modifier = modifier,
            onTextLayout = { textLayoutResult = it },
            inlineContent = inlineContent
    )
}

private fun androidx.compose.ui.text.AnnotatedString.threadTagAtOffset(
        offset: Int,
        parts: List<InlineBodyPart>
): ThreadMarkdownTag? =
        listOf(offset, offset - 1, offset + 1)
                .asSequence()
                .filter { it >= 0 && it <= length }
                .mapNotNull { probe ->
                    getStringAnnotations(ThreadInlineTagAnnotation, probe, probe)
                            .firstOrNull()
                            ?.item
                            ?.toIntOrNull()
                            ?.let { index ->
                                (parts.getOrNull(index) as? InlineBodyPart.Tag)?.tag
                            }
                }
                .firstOrNull()

private const val ThreadInlineTagAnnotation = "thread_tag"

private fun String.maskMarkdownCodeRanges(): String {
    val chars = toCharArray()
    var index = 0
    while (index < chars.size) {
        if (startsWith("```", index)) {
            val end =
                    indexOf("```", startIndex = index + 3).let {
                        if (it < 0) chars.size - 3 else it
                    }
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

private sealed interface ThreadMarkdownSegment {
    data class Text(val value: String) : ThreadMarkdownSegment
    data class CodeBlock(val language: String?, val code: String) : ThreadMarkdownSegment
}

@Composable
private fun ThreadMarkdownBody(
        body: String,
        modifier: Modifier = Modifier,
        textAlign: TextAlign = TextAlign.Start,
        onTagClick: ((ThreadMarkdownTag) -> Unit)? = null
) {
    val segments = remember(body) { body.toThreadMarkdownSegments() }
    val hasInlineTags =
            remember(body, onTagClick) {
                onTagClick != null && (body.contains('@') || body.contains('#'))
            }
    val bodyTypography = ThreadMessageBodyTextStyle.copy(textAlign = textAlign)
    val codeTypography =
            bodyTypography.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
            )
    // Fast path: plain single-line message with no tags
    if (segments.size == 1 &&
                    segments.single() is ThreadMarkdownSegment.Text &&
                    body.isPlainSingleLineMessage() &&
                    !hasInlineTags
    ) {
        Text(
                text = body,
                style = bodyTypography,
                color = SlockColor.TextBlack,
                modifier = modifier,
                textAlign = textAlign
        )
        return
    }
    // Fast path: single text segment with inline tags — render as FlowRow chips
    if (hasInlineTags && segments.size == 1 && segments.single() is ThreadMarkdownSegment.Text) {
        val parts = remember(body) { body.splitInlineBodyParts() }
        ThreadInlineTagsBody(
                parts = parts,
                style = bodyTypography,
                textAlign = textAlign,
                modifier = modifier,
                onTagClick = onTagClick!!
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
                        val segmentHasTags =
                                hasInlineTags &&
                                        (segment.value.contains('@') ||
                                                segment.value.contains('#'))
                        if (segmentHasTags) {
                            val parts = remember(segment.value) {
                                segment.value.splitInlineBodyParts()
                            }
                            ThreadInlineTagsBody(
                                    parts = parts,
                                    style = bodyTypography,
                                    textAlign = textAlign,
                                    onTagClick = onTagClick!!
                            )
                        } else {
                            ThreadMarkdownContent(
                                    body = segment.value,
                                    bodyTypography = bodyTypography,
                                    codeTypography = codeTypography
                            )
                        }
                    }
                }
                is ThreadMarkdownSegment.CodeBlock ->
                        ThreadMarkdownCodeBlock(code = segment.code, language = segment.language)
            }
        }
    }
}

@Composable
private fun ThreadMarkdownContent(
        body: String,
        bodyTypography: TextStyle,
        codeTypography: TextStyle
) {
    Markdown(
            content = body,
            colors =
                    markdownColor(
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
            typography =
                    markdownTypography(
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
private fun ThreadMarkdownCodeBlock(code: String, language: String?) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceCream, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                    text = language.uppercase(),
                    style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    modifier =
                            Modifier.fillMaxWidth()
                                    .background(
                                            SlockColor.BrandYellow,
                                            RoundedCornerShape(
                                                    topStart = SlockRadius.Xs,
                                                    topEnd = SlockRadius.Xs
                                            )
                                    )
                                    .border(
                                            SlockStroke.Thin,
                                            SlockColor.BorderBlack,
                                            RoundedCornerShape(
                                                    topStart = SlockRadius.Xs,
                                                    topEnd = SlockRadius.Xs
                                            )
                                    )
                                    .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
            )
        }
        Text(
                text = code.trimEnd(),
                style =
                        SlockType.Caption.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                        ),
                color = SlockColor.TextBlack,
                modifier =
                        Modifier.fillMaxWidth()
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
            substring(index).takeIf { it.isNotBlank() }?.let {
                segments += ThreadMarkdownSegment.Text(it)
            }
            break
        }
        substring(index, fenceStart).takeIf { it.isNotBlank() }?.let {
            segments += ThreadMarkdownSegment.Text(it)
        }
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
private fun ThreadAvatar(author: MemberProfile, onClick: (() -> Unit)? = null) {
    val avatarModifier =
            if (onClick != null) {
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
    get() =
            when (this) {
                MemberKind.Human -> SlockColor.AccentLavender
                MemberKind.Agent -> SlockColor.AccentPink
                MemberKind.System -> SlockColor.AccentCyan
            }

private val MessageDeliveryState.threadLabel: String
    get() =
            when (this) {
                MessageDeliveryState.Sending -> "Sending"
                MessageDeliveryState.Sent -> "Sent"
                MessageDeliveryState.Failed -> "Failed"
            }

private val MessageDeliveryState.threadColor: Color
    get() =
            when (this) {
                MessageDeliveryState.Sending -> SlockColor.BrandYellow
                MessageDeliveryState.Sent -> SlockColor.TextMutedGray
                MessageDeliveryState.Failed -> SlockColor.AccentRed
            }

fun String.toThreadTimestampLabel(zoneId: ZoneId = ZoneId.systemDefault()): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return ""
    val zoned = trimmed.toThreadZonedDateTime(zoneId) ?: return trimmed
    return ThreadCompactTimeFormatter.format(zoned.withZoneSameInstant(zoneId))
}

private val ThreadCompactTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun String.toThreadZonedDateTime(zoneId: ZoneId): ZonedDateTime? {
    val trimmed = trim()
    val isoLikeTrimmed = trimmed.replace(' ', 'T')
    return parseOrNull { Instant.parse(trimmed).atZone(zoneId) }
            ?: parseOrNull { OffsetDateTime.parse(trimmed).toZonedDateTime() }
                    ?: parseOrNull { ZonedDateTime.parse(trimmed) }
                    ?: parseOrNull { LocalDateTime.parse(trimmed).atZone(zoneId) }
                            ?: parseOrNull { Instant.parse(isoLikeTrimmed).atZone(zoneId) }
                                    ?: parseOrNull {
                                        OffsetDateTime.parse(isoLikeTrimmed).toZonedDateTime()
                                    }
                                            ?: parseOrNull { ZonedDateTime.parse(isoLikeTrimmed) }
                                                    ?: parseOrNull {
                                                        LocalDateTime.parse(isoLikeTrimmed)
                                                                .atZone(zoneId)
                                                    }
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
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        maxLines: Int = 2,
        overflow: TextOverflow = TextOverflow.Clip
) {
    val chipModifier =
            modifier
                    .background(color)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .then(
                            if (onClick != null)
                                    Modifier.clickable(role = Role.Button, onClick = onClick)
                            else Modifier
                    )
                    .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
    Row(
            modifier = chipModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (icon != null) {
            Icon(
                    icon,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(12.dp)
            )
        }
        Text(
                label,
                style = SlockType.Caption,
                color = SlockColor.TextBlack,
                maxLines = maxLines,
                overflow = overflow
        )
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
        modifier: Modifier = Modifier,
        memberSuggestions: List<MemberProfile> = emptyList(),
        channelSuggestions: List<ConversationSummary> = emptyList()
) {
    var composerFieldValue by remember { mutableStateOf(TextFieldValue(composerState.value)) }
    if (composerFieldValue.text != composerState.value) {
        composerFieldValue =
                TextFieldValue(
                        text = composerState.value,
                        selection = TextRange(composerState.value.length)
                )
    }
    val horizontalPadding = threadHorizontalPadding()
    val bottomPadding = threadComposerBottomPadding()
    val density = LocalDensity.current
    val keyboardBottomPx = WindowInsets.ime.getBottom(density)
    val keyboardVisible = keyboardBottomPx > 0
    val composerTopPadding = SlockSpacing.Md
    val composerSectionSpacing = SlockSpacing.Sm
    val composerTextFieldMinHeight = 48.dp
    val composerTextFieldVerticalPadding = 12.dp
    val enabled =
            composerState.enabled &&
                    composerState.status != ComposerStatus.NoPermission &&
                    composerState.noPermissionCode == null
    fun recordImeDiagnostic(message: String) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d("SlockThreadIme", message)
    }
    fun diagnosticFields(source: String, topPx: Int, bottomPx: Int, heightPx: Int): String =
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
    val composerValueProvider = { composerFieldValue }
    val handleComposerFieldValueChange = { nextValue: TextFieldValue ->
        composerFieldValue = nextValue
        onComposerChange(nextValue.text)
    }
    val suggestionTrigger = remember(composerFieldValue) {
        detectComposerSuggestionTrigger(composerFieldValue)
    }
    val filteredMemberSuggestions = remember(suggestionTrigger, memberSuggestions) {
        when (val trigger = suggestionTrigger) {
            is ComposerSuggestionTrigger.MemberTrigger ->
                memberSuggestions.filter { member ->
                    val q = trigger.query.lowercase()
                    member.displayName.lowercase().contains(q) ||
                            member.handle.lowercase().contains(q)
                }.take(6)
            else -> emptyList()
        }
    }
    val filteredChannelSuggestions = remember(suggestionTrigger, channelSuggestions) {
        when (val trigger = suggestionTrigger) {
            is ComposerSuggestionTrigger.ChannelTrigger ->
                channelSuggestions.filter { channel ->
                    val q = trigger.query.lowercase()
                    channel.name.lowercase().contains(q)
                }.take(6)
            else -> emptyList()
        }
    }
    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        if (filteredMemberSuggestions.isNotEmpty()) {
            ComposerMemberSuggestionDropdown(
                    members = filteredMemberSuggestions,
                    onSelect = { member ->
                        (suggestionTrigger as? ComposerSuggestionTrigger.MemberTrigger)?.let { trigger ->
                            handleComposerFieldValueChange(
                                    composerFieldValue.replaceTriggerWith(
                                            trigger.triggerStart,
                                            "@${member.handle} "
                                    )
                            )
                        }
                    }
            )
        }
        if (filteredChannelSuggestions.isNotEmpty()) {
            ComposerChannelSuggestionDropdown(
                    channels = filteredChannelSuggestions,
                    onSelect = { channel ->
                        (suggestionTrigger as? ComposerSuggestionTrigger.ChannelTrigger)?.let { trigger ->
                            handleComposerFieldValueChange(
                                    composerFieldValue.replaceTriggerWith(
                                            trigger.triggerStart,
                                            "#${channel.name} "
                                    )
                            )
                        }
                    }
            )
        }
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .background(SlockColor.SurfaceWhite)
                                .drawBehind {
                                    val stroke = SlockStroke.Default.toPx()
                                    drawLine(
                                            color = SlockColor.BorderBlack,
                                            start = Offset(0f, stroke / 2f),
                                            end = Offset(size.width, stroke / 2f),
                                            strokeWidth = stroke
                                    )
                                }
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInWindow()
                                    recordImeDiagnostic(
                                            diagnosticFields(
                                                    source = "composer_bounds",
                                                    topPx = position.y.toInt(),
                                                    bottomPx =
                                                            (position.y + coordinates.size.height)
                                                                    .toInt(),
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
                        color =
                                if (composerState.status == ComposerStatus.Failed) {
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
            val insertComposerNewline = {
                handleComposerFieldValueChange(composerFieldValue.insertTextAtSelection("\n"))
            }
            val textToolbar = rememberComposerTextToolbar(onInsertNewline = insertComposerNewline)
            CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
                SlockTextField(
                    value = composerState.value,
                    onValueChange = onComposerChange,
                    fieldValue = composerFieldValue,
                    onFieldValueChange = handleComposerFieldValueChange,
                    placeholder = placeholder,
                    enabled = enabled,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    reserveErrorSpace = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions =
                            KeyboardActions(
                                    onSend = {
                                        if (composerState.canSend) {
                                            onSend()
                                        }
                                    }
                            ),
                    inputModifier =
                            Modifier.threadComposerNewlineInput(
                                    composerValueProvider,
                                    handleComposerFieldValueChange
                            ),
                    bottomContent = {
                        ThreadComposerInlineActions(
                                composerState = composerState,
                                enabled = enabled,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onAsTaskChange = onAsTaskChange,
                                onSend = onSend
                        )
                    },
                    minContentHeight = composerTextFieldMinHeight,
                    contentPaddingVertical = composerTextFieldVerticalPadding,
                    inputFontWeight = FontWeight.Normal,
                    inputFontFamily = SlockNormalFontFamily
                )
            }
            if (composerState.attachmentIds.isNotEmpty() && composerState.pendingAttachments.isEmpty()) {
                Text(
                        text = "${composerState.attachmentIds.size} files",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun threadComposerBottomPadding(): Dp = SlockSpacing.Xs

private sealed class ComposerSuggestionTrigger {
    data class MemberTrigger(val query: String, val triggerStart: Int) : ComposerSuggestionTrigger()
    data class ChannelTrigger(val query: String, val triggerStart: Int) : ComposerSuggestionTrigger()
    object None : ComposerSuggestionTrigger()
}

private fun detectComposerSuggestionTrigger(fieldValue: TextFieldValue): ComposerSuggestionTrigger {
    val text = fieldValue.text
    val cursor = fieldValue.selection.min.coerceIn(0, text.length)
    val textBeforeCursor = text.substring(0, cursor)
    var triggerIndex = -1
    var triggerChar = ' '
    for (i in textBeforeCursor.indices.reversed()) {
        val c = textBeforeCursor[i]
        if (c.isWhitespace()) break
        if (c == '@' || c == '#') {
            if (i == 0 || textBeforeCursor[i - 1].isWhitespace()) {
                triggerIndex = i
                triggerChar = c
            }
            break
        }
    }
    if (triggerIndex < 0) return ComposerSuggestionTrigger.None
    val query = textBeforeCursor.substring(triggerIndex + 1)
    return when (triggerChar) {
        '@' -> ComposerSuggestionTrigger.MemberTrigger(query, triggerIndex)
        '#' -> ComposerSuggestionTrigger.ChannelTrigger(query, triggerIndex)
        else -> ComposerSuggestionTrigger.None
    }
}

private fun TextFieldValue.replaceTriggerWith(triggerStart: Int, replacement: String): TextFieldValue {
    val end = selection.min.coerceIn(0, text.length)
    val nextText = text.replaceRange(triggerStart, end, replacement)
    val nextCursor = triggerStart + replacement.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

@Composable
private fun ComposerMemberSuggestionDropdown(
        members: List<MemberProfile>,
        onSelect: (MemberProfile) -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        Text(
                text = "IN THIS CHANNEL",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs)
        )
        members.forEach { member ->
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { onSelect(member) }
                                    .background(SlockColor.SurfaceWhite)
                                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                    .padding(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Box(modifier = Modifier.size(32.dp)) {
                    SlockAvatar(
                            avatarUrl = member.avatarUrl,
                            avatar = member.avatar,
                            label = member.avatarLabel,
                            modifier = Modifier.fillMaxSize(),
                            size = 32.dp
                    )
                }
                Text(
                        text = member.displayName,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextBlack,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
                Text(
                        text = "@${member.handle}",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun ComposerChannelSuggestionDropdown(
        channels: List<ConversationSummary>,
        onSelect: (ConversationSummary) -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        channels.forEach { channel ->
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { onSelect(channel) }
                                    .background(SlockColor.SurfaceWhite)
                                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                    .padding(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Box(
                        modifier =
                                Modifier.size(32.dp)
                                        .background(SlockColor.BrandYellow)
                                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                        contentAlignment = Alignment.Center
                ) {
                    Text(
                            text = "#",
                            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextBlack
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = channel.name,
                            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )
                    if (!channel.subtitle.isNullOrBlank()) {
                        Text(
                                text = channel.subtitle,
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun ThreadComposerInlineActions(
        composerState: ComposerState,
        enabled: Boolean,
        onAttachImage: () -> Unit,
        onAttachFile: () -> Unit,
        onAsTaskChange: (Boolean) -> Unit,
        onSend: () -> Unit
) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            CompactIconButton(
                    SlockIcons.ImagePlus,
                    "Attach image",
                    enabled = enabled,
                    onClick = onAttachImage
            )
            CompactIconButton(
                    SlockIcons.Paperclip,
                    "Attach file",
                    enabled = enabled,
                    onClick = onAttachFile
            )
        }
        Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockLabeledCheckbox(
                    label = "As Task",
                    checked = composerState.asTask,
                    onCheckedChange = onAsTaskChange,
                    enabled = enabled,
                    size = 18.dp
            )
            ThreadSendButton(
                    state = composerState.sendVisualState,
                    onClick = onSend
            )
        }
    }
}

private fun Modifier.threadComposerNewlineInput(
        valueProvider: () -> TextFieldValue,
        onValueChange: (TextFieldValue) -> Unit
): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isShiftPressed) {
        onValueChange(valueProvider().insertTextAtSelection("\n"))
        true
    } else {
        false
    }
}

private fun TextFieldValue.insertTextAtSelection(textToInsert: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val nextText = text.replaceRange(start, end, textToInsert)
    val nextCursor = start + textToInsert.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

@Composable
private fun rememberComposerTextToolbar(onInsertNewline: () -> Unit): TextToolbar {
    val view = LocalView.current
    val insertNewlineState = rememberUpdatedState(onInsertNewline)
    return remember(view) {
        ComposerTextToolbar(
                view = view,
                onInsertNewline = { insertNewlineState.value() }
        )
    }
}

private class ComposerTextToolbar(
        private val view: View,
        private val onInsertNewline: () -> Unit
) : TextToolbar {
    private var actionMode: ActionMode? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
    ) {
        val callback =
                ComposerTextActionModeCallback(
                        onCopyRequested = onCopyRequested,
                        onPasteRequested = onPasteRequested,
                        onCutRequested = onCutRequested,
                        onSelectAllRequested = onSelectAllRequested,
                        onInsertNewlineRequested = {
                            onInsertNewline()
                            hide()
                        },
                        onActionModeDestroyed = {
                            status = TextToolbarStatus.Hidden
                            actionMode = null
                        },
                        toolbarRect = rect.toLocalAndroidRect(view)
                )
        status = TextToolbarStatus.Shown
        actionMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    view.startActionMode(callback, ActionMode.TYPE_FLOATING)
                } else {
                    view.startActionMode(callback)
                }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }
}

private class ComposerTextActionModeCallback(
        private val onCopyRequested: (() -> Unit)?,
        private val onPasteRequested: (() -> Unit)?,
        private val onCutRequested: (() -> Unit)?,
        private val onSelectAllRequested: (() -> Unit)?,
        private val onInsertNewlineRequested: () -> Unit,
        private val onActionModeDestroyed: () -> Unit,
        private val toolbarRect: AndroidRect
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(0, MENU_INSERT_NEWLINE, 0, "换行")
        if (onCutRequested != null) {
            menu.add(0, android.R.id.cut, 1, android.R.string.cut)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onCopyRequested != null) {
            menu.add(0, android.R.id.copy, 2, android.R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onPasteRequested != null) {
            menu.add(0, android.R.id.paste, 3, android.R.string.paste)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onSelectAllRequested != null) {
            menu.add(0, android.R.id.selectAll, 4, android.R.string.selectAll)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            when (item.itemId) {
                MENU_INSERT_NEWLINE -> {
                    onInsertNewlineRequested()
                    true
                }
                android.R.id.cut -> {
                    onCutRequested?.invoke()
                    mode.finish()
                    true
                }
                android.R.id.copy -> {
                    onCopyRequested?.invoke()
                    mode.finish()
                    true
                }
                android.R.id.paste -> {
                    onPasteRequested?.invoke()
                    mode.finish()
                    true
                }
                android.R.id.selectAll -> {
                    onSelectAllRequested?.invoke()
                    true
                }
                else -> false
            }

    override fun onDestroyActionMode(mode: ActionMode) {
        onActionModeDestroyed()
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
        outRect.set(toolbarRect)
    }

    private companion object {
        const val MENU_INSERT_NEWLINE = 0x534c0a
    }
}

private fun Rect.toLocalAndroidRect(view: View): AndroidRect {
    val viewLocation = IntArray(2)
    view.getLocationOnScreen(viewLocation)
    return AndroidRect(
            (left - viewLocation[0]).toInt(),
            (top - viewLocation[1]).toInt(),
            (right - viewLocation[0]).toInt(),
            (bottom - viewLocation[1]).toInt()
    )
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
    val borderColor =
            if (attachment.status == PendingAttachmentStatus.Failed) {
                SlockColor.AccentRed
            } else {
                SlockColor.BorderBlack
            }
    Row(
            modifier =
                    Modifier.width(168.dp)
                            .heightIn(min = 54.dp)
                            .background(
                                    SlockColor.EmptyStateGray.copy(alpha = 0.28f),
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .border(
                                    SlockStroke.Default,
                                    borderColor,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .padding(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
                modifier =
                        Modifier.size(36.dp)
                                .background(
                                        if (attachment.isImage) SlockColor.AccentPink
                                        else SlockColor.SurfaceWhite,
                                        RoundedCornerShape(SlockRadius.Xs)
                                )
                                .border(
                                        SlockStroke.Default,
                                        SlockColor.BorderBlack,
                                        RoundedCornerShape(SlockRadius.Xs)
                                ),
                contentAlignment = Alignment.Center
        ) { PendingAttachmentPreview(attachment) }
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
                    color =
                            if (attachment.status == PendingAttachmentStatus.Failed) {
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
    val imageBitmap =
            remember(attachment.previewUri, attachment.isImage) {
                attachment.previewUri?.takeIf { attachment.isImage }?.let { uriString ->
                    runCatching {
                                context.contentResolver.openInputStream(Uri.parse(uriString))
                                        ?.use { input ->
                                            BitmapFactory.decodeStream(input)?.asImageBitmap()
                                        }
                            }
                            .getOrNull()
                }
            }
    if (imageBitmap != null) {
        Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(SlockRadius.Xs))
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
    get() =
            listOfNotNull(
                            mimeType?.takeIf { it.isNotBlank() },
                            sizeLabel.takeIf { it.isNotBlank() },
                            status
                                    .takeUnless { it == PendingAttachmentStatus.Ready }
                                    ?.name
                                    ?.lowercase()
                    )
                    .joinToString(" · ")
                    .ifBlank { "Ready" }

@Composable
private fun ThreadSendButton(
        state: ComposerSendVisualState,
        onClick: () -> Unit
) {
    val enabled = state == ComposerSendVisualState.Ready || state == ComposerSendVisualState.Failed
    Box(
            modifier =
                    Modifier.size(28.dp)
                            .background(SlockColor.AccentPink, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                            .padding(5.dp),
            contentAlignment = Alignment.Center
    ) {
        Icon(
                imageVector = SlockIcons.Send,
                contentDescription = state.label,
                tint = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                modifier = Modifier.size(16.dp)
        )
    }
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
    get() =
            when (status) {
                ComposerStatus.IdleEmpty -> ComposerSendVisualState.IdleEmpty
                ComposerStatus.Ready -> ComposerSendVisualState.Ready
                ComposerStatus.Uploading -> ComposerSendVisualState.Uploading
                ComposerStatus.Sending -> ComposerSendVisualState.Sending
                ComposerStatus.Failed -> ComposerSendVisualState.Failed
                ComposerStatus.NoPermission -> ComposerSendVisualState.NoPermission
            }

private val ComposerState.statusLabel: String?
    get() =
            when (status) {
                ComposerStatus.Uploading -> "Uploading attachment"
                ComposerStatus.Sending -> "Sending message"
                ComposerStatus.Failed -> "Send failed: ${errorCode ?: "retry"}"
                ComposerStatus.NoPermission ->
                        "No permission: ${noPermissionCode ?: "channel_readonly"}"
                ComposerStatus.IdleEmpty, ComposerStatus.Ready -> null
            }

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadScreenPreview() {
    SlockTheme { ThreadScreen(state = SlockStateSamples.threadContent) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadScreenLongTitlePreview() {
    SlockTheme {
        val baseState = SlockStateSamples.threadContent
        val content = (baseState.loadState as ScreenLoadState.Content).data
        ThreadScreen(
                state =
                        baseState.copy(
                                loadState =
                                        ScreenLoadState.Content(
                                                content.copy(
                                                        header =
                                                                content.header.copy(
                                                                        name =
                                                                                "very-long-channel-name-for-mobile-overflow-check",
                                                                        subtitle =
                                                                                "Members, settings, leave actions must stay visible"
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
        ThreadScreen(state = SlockStateSamples.threadContent.copy(selectedTab = ThreadTab.Tasks))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadMembersModalPreview() {
    SlockTheme {
        ThreadMembersModal(
                members = threadMemberModalItems(),
                onAddMemberClick = {},
                onDismiss = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun ThreadSettingsModalPreview() {
    SlockTheme { ThreadSettingsModal(initialName = "android-dev", onDismiss = {}) }
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
            ThreadMembersModal(members = emptyList(), onAddMemberClick = {}, onDismiss = {})
            ThreadMembersModal(
                    members = threadMemberModalItems(),
                    onAddMemberClick = {},
                    onDismiss = {}
            )
            ThreadAddMemberModal(
                    members = threadMemberModalItems(),
                    addMemberQuery = "@viewer",
                    addMemberError = false,
                    membersLoadState = ScreenLoadState.Loading,
                    onAddMemberQueryChange = {},
                    onAddMemberClick = {},
                    onDismiss = {}
            )
            ThreadAddMemberModal(
                    members = threadMemberModalItems(),
                    addMemberQuery = "@locked",
                    addMemberError = false,
                    membersLoadState = ScreenLoadState.NoPermission(PermissionBlocker("locked")),
                    onAddMemberQueryChange = {},
                    onAddMemberClick = {},
                    onDismiss = {},
                    enabled = false
            )
        }
    }
}

private val TaskStatus.threadColor: Color
    get() =
            when (this) {
                TaskStatus.Todo -> SlockColor.AccentOrange
                TaskStatus.InProgress -> SlockColor.AccentCyan
                TaskStatus.InReview -> SlockColor.AccentLavender
                TaskStatus.Done -> SlockColor.AccentLime
                TaskStatus.Closed -> SlockColor.AccentRed
            }

private val TaskStatus.threadIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() =
            when (this) {
                TaskStatus.Todo -> SlockIcons.Circle
                TaskStatus.InProgress -> SlockIcons.Play
                TaskStatus.InReview -> SlockIcons.Eye
                TaskStatus.Done -> SlockIcons.CircleCheckBig
                TaskStatus.Closed -> SlockIcons.Ban
            }

private val TaskStatus.threadLabel: String
    get() =
            when (this) {
                TaskStatus.Todo -> "Todo"
                TaskStatus.InProgress -> "In progress"
                TaskStatus.InReview -> "In review"
                TaskStatus.Done -> "Done"
                TaskStatus.Closed -> "Closed"
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
private fun ThreadInlineTagsPreview() {
    SlockTheme {
        ThreadMessageItem(
                message =
                        Message(
                                id = MessageId("inline-tags-preview-message"),
                                threadId = ThreadId("inline-tags-preview-thread"),
                                channelId = ChannelId("inline-tags-preview-channel"),
                                author =
                                        MemberProfile(
                                                id = MemberId("inline-tags-preview-author"),
                                                displayName = "Artin",
                                                handle = "artin",
                                                kind = MemberKind.Human,
                                                roleDescription = "Owner"
                                        ),
                                timestampLabel = "Now",
                                body =
                                        "Please review #22 and also check `#22` in `inline code` — the code should stay yellow, not become a task chip. Also ping @alice and visit #general.",
                                replyCount = 0
                        )
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ThreadMessageMarkdownPreview() {
    SlockTheme {
        ThreadMessageItem(
                message =
                        Message(
                                id = MessageId("markdown-preview-message"),
                                threadId = ThreadId("markdown-preview-thread"),
                                channelId = ChannelId("markdown-preview-channel"),
                                author =
                                        MemberProfile(
                                                id = MemberId("markdown-preview-author"),
                                                displayName = "Android Developer",
                                                handle = "android-developer",
                                                kind = MemberKind.Agent,
                                                roleDescription = "Agent"
                                        ),
                                timestampLabel = "Now",
                                body =
                                        """
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
                    message =
                            Message(
                                    id = MessageId("right-short-message"),
                                    threadId = ThreadId("right-thread"),
                                    channelId = ChannelId("right-channel"),
                                    author =
                                            MemberProfile(
                                                    id = MemberId("current-user"),
                                                    displayName = "Artin With A Long Display Name",
                                                    handle = "artin",
                                                    kind = MemberKind.Human,
                                                    roleDescription = "Owner"
                                            ),
                                    timestampLabel = "2026-05-07T11:18:00Z",
                                    body = "Short right-aligned note.",
                                    attachments =
                                            listOf(
                                                    Attachment(
                                                            id = AttachmentId("right-attachment"),
                                                            name =
                                                                    "very-long-file-name-for-right-layout.pdf",
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
                    message =
                            Message(
                                    id = MessageId("right-markdown-message"),
                                    threadId = ThreadId("right-thread"),
                                    channelId = ChannelId("right-channel"),
                                    author =
                                            MemberProfile(
                                                    id = MemberId("current-user"),
                                                    displayName = "Artin",
                                                    handle = "artin",
                                                    kind = MemberKind.Human
                                            ),
                                    timestampLabel = "Now",
                                    body =
                                            """
                        Right mode with `inline code`, a list, and a long word:

                        - metadata should stay near the bubble edge
                        - attachments should not stretch past the message width

                        superlongunbrokenidentifier_that_should_wrap_inside_the_right_column
                    """.trimIndent()
                            ),
                    isCurrentUserMessage = true,
                    myMessageAlignment = MyMessageAlignmentPreference.Right
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
