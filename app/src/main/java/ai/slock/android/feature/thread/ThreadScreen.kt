package ai.slock.android.feature.thread

import ai.slock.android.R
import ai.slock.android.core.data.preferences.MyMessageAlignmentPreference
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.data.repository.ChannelFileItem
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
import ai.slock.android.core.model.unreadReplyCount
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.shared.SlockKuiklyActivity
import ai.slock.android.shared.SlockKuiklyPageIsland
import ai.slock.android.feature.tasks.TasksContent
import ai.slock.android.feature.tasks.TasksPanel
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.android.ui.components.SlockAvatar
import ai.slock.android.ui.components.SlockAgentStatusIndicator
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockLoadingState
import ai.slock.android.ui.components.SlockKeySurface
import ai.slock.android.ui.components.SlockLabeledCheckbox
import ai.slock.android.ui.components.SlockMemberAvatar
import ai.slock.android.ui.components.SlockMemberPickerItem
import ai.slock.android.ui.components.SlockMemberPickerMessage
import ai.slock.android.ui.components.SlockMemberPickerSection
import ai.slock.android.ui.components.SlockMenuOverlay
import ai.slock.android.ui.components.SlockMenuOverlayAction
import ai.slock.android.ui.components.SlockMenuOverlayDefaults
import ai.slock.android.ui.components.SlockModal
import ai.slock.android.ui.components.SlockModalAction
import ai.slock.android.ui.components.SlockModalActionStyle
import ai.slock.android.ui.components.SlockModalTextField
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.components.agentStatusLabel
import ai.slock.android.ui.components.defaultAvatarColor
import ai.slock.android.ui.system.DeclareStatusBarColor
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
import ai.slock.compose.markdown.SlockMarkdownPreviewSamples
import ai.slock.compose.markdown.ThreadInlineCodeMatch
import ai.slock.compose.markdown.ThreadInlineTagMatch
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.markdown.normalizeThreadMarkdownBody
import ai.slock.compose.markdown.threadInlineCodeMatches
import ai.slock.compose.markdown.threadInlineTagMatches
import ai.slock.compose.markdown.threadMarkdownTagDisplayLabel
import ai.slock.compose.markdown.threadMarkdownTaskReferenceKey
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
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.rememberMarkdownState
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.LinkedHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

val ThreadMessageBodyTextStyle: TextStyle
    get() =
            SlockType.Body.copy(
                    fontWeight = FontWeight.Normal,
                    fontFamily = SlockNormalFontFamily,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
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
        onOpenSlockWebLink: (String) -> Boolean = { false },
        onOpenTask: (TaskSummary) -> Unit = {},
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit = { _, _ -> },
        onSaveMessage: (Message) -> Unit = {},
        onRetryTasks: () -> Unit = {},
        onRetryFiles: () -> Unit = {},
        onLoadMoreFiles: () -> Unit = {},
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState> = emptyMap(),
        attachmentPreviewState: AttachmentPreviewUiState? = null,
        onOpenAttachment: (Attachment) -> Unit = {},
        onDismissAttachmentPreview: () -> Unit = {},
        onOpenAttachmentPreviewExternally: (AttachmentPreviewUiState) -> Unit = {},
        onJoinChannel: (ConversationSummary) -> Unit = {},
        membersPanelState: ThreadMembersPanelState = rememberSampleThreadMembersPanelState(),
        onAddChannelMember: (String, MemberKind) -> Unit = { _, _ -> },
        onRemoveChannelMember: (MemberProfile) -> Unit = {},
        archiveChannelStatus: ThreadSettingsModalStatus = ThreadSettingsModalStatus.Default,
        onArchiveChannel: () -> Unit = {},
        onDeleteChannel: () -> Unit = {},
        onLeaveChannel: () -> Unit = {},
        currentMemberId: MemberId? = null,
        myMessageAlignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Default,
        replyHeaderContext: ThreadReplyHeaderContext? = null,
        parentJumpTarget: ThreadParentJumpTarget? = null,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit = {},
        onRetryThreadLoad: () -> Unit = {},
        channelSuggestions: List<ConversationSummary> = emptyList(),
        showComposer: Boolean = true,
        showReplyActions: Boolean = true,
        headerNavigationIcon: ThreadHeaderNavigationIcon = ThreadHeaderNavigationIcon.Back,
        showTopBorder: Boolean = true,
        showConversationActions: Boolean = true,
        fallbackComposerHeader: ConversationSummary? = null,
        selectedThreadId: ThreadId? = null,
        composerFocusRequestNonce: Int = 0
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
    val selectedThreadFallbackJoinHeader: () -> ConversationSummary? =
            remember(selectedThreadId) {
                {
                    selectedThreadId?.let { threadId ->
                        ConversationSummary(
                                threadId = threadId,
                                channelId = ChannelId(threadId.value),
                                kind = ConversationKind.Channel,
                                name = threadId.value,
                                joined = false
                        )
                    }
                }
            }
    val displayedMembers = membersPanelState.modalMembers
    val selectedChannelMemberDetail =
            displayedMembers.firstOrNull { it.id.value == selectedChannelMemberDetailId }
    val membersCount = membersPanelState.memberCount
    val attachmentPreviewVisible =
            attachmentPreviewState != null && attachmentPreviewState.status != AttachmentPreviewStatus.Idle
    val hasTransientOverlay =
            showAddMember || selectedChannelMemberDetail != null || showMembersModal ||
                    showSettingsModal || attachmentPreviewVisible
    BackHandler(enabled = hasTransientOverlay) {
        when {
            showAddMember -> {
                showAddMember = false
                addMemberQuery = ""
                selectedAddMemberId = null
                addMemberError = false
            }
            selectedChannelMemberDetail != null -> {
                selectedChannelMemberDetailId = null
            }
            showMembersModal -> {
                showMembersModal = false
                selectedChannelMemberDetailId = null
                showAddMember = false
                addMemberQuery = ""
                selectedAddMemberId = null
                addMemberError = false
            }
            showSettingsModal -> {
                showSettingsModal = false
            }
            attachmentPreviewVisible -> onDismissAttachmentPreview()
        }
    }
    Box(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite).imePadding()) {
        val showModeTabs = replyHeaderContext == null
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .background(SlockColor.SurfaceWhite)
                                .then(
                                        if (showTopBorder) {
                                            Modifier.drawBehind {
                                                val stroke = SlockStroke.Default.toPx()
                                                drawLine(
                                                        color = SlockColor.BorderBlack,
                                                        start = Offset(0f, stroke / 2f),
                                                        end = Offset(size.width, stroke / 2f),
                                                        strokeWidth = stroke
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }
                                )
        ) {
            when (val loadState = state.loadState) {
                ScreenLoadState.Loading -> {
                    ThreadTopHeader(
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            onLeaveChannel = onLeaveChannel,
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage,
                            navigationIcon = headerNavigationIcon,
                            showConversationActions = showConversationActions
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
                    ) {
                        SlockLoadingState(title = "Loading thread")
                    }
                    if (showComposer) {
                        val loadingComposerState =
                                fallbackComposerHeader?.let(state.composerState::forHeader)
                                        ?: state.composerState
                        ThreadComposerBar(
                                composerState = loadingComposerState,
                                placeholder =
                                        fallbackComposerHeader?.let { "Message #${it.name}" }
                                                ?: "Message",
                                onComposerChange = onComposerChange,
                                onAsTaskChange = onAsTaskChange,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onRemoveAttachment = onRemoveAttachment,
                                onRetryAttachment = onRetryAttachment,
                                onSend = onSend,
                                focusRequestNonce = composerFocusRequestNonce
                        )
                    }
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
                            onLeaveChannel = onLeaveChannel,
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage,
                            navigationIcon = headerNavigationIcon,
                            showConversationActions = showConversationActions,
                            refreshStatus = loadState.data.refreshStatus,
                            refreshErrorCode = loadState.data.refreshErrorCode
                    )
                    if (showModeTabs) {
                        ThreadModeTabs(
                                selectedTab = state.selectedTab,
                                onModeSelected = onModeSelected,
                                modifier =
                                        Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
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
                                                .threadTabSwipe(
                                                        selectedTab = state.selectedTab,
                                                        enabled = showModeTabs,
                                                        onModeSelected = onModeSelected
                                                )
                                                .dismissKeyboardOnPointerDown(
                                                        dismissComposerKeyboard
                                                )
                        )
                    } else if (state.selectedTab == ThreadTab.Files) {
                        ThreadFilesPanel(
                                state = state.filesState,
                                onOpenAttachment = onOpenAttachment,
                                onRetry = onRetryFiles,
                                onLoadMore = onLoadMoreFiles,
                                modifier =
                                        Modifier.weight(1f)
                                                .fillMaxWidth()
                                                .threadTabSwipe(
                                                        selectedTab = state.selectedTab,
                                                        enabled = showModeTabs,
                                                        onModeSelected = onModeSelected
                                                )
                                                .dismissKeyboardOnPointerDown(
                                                        dismissComposerKeyboard
                                                )
                        )
                    } else {
                        Column(
                                modifier =
                                        Modifier.weight(1f)
                                                .fillMaxWidth()
                                                .threadTabSwipe(
                                                        selectedTab = state.selectedTab,
                                                        enabled = showModeTabs,
                                                        onModeSelected = onModeSelected
                                                ),
                        ) {
                            if (replyHeaderContext != null) {
                                ThreadOriginPanel(
                                        context = replyHeaderContext,
                                        parentJumpTarget = parentJumpTarget,
                                        onOpenParentMessage = onOpenParentMessage,
                                        attachmentOpenStates = attachmentOpenStates,
                                        onOpenAttachment = onOpenAttachment,
                                        showReplyAffordance = false,
                                        modifier =
                                                Modifier.dismissKeyboardOnPointerDown(
                                                        dismissComposerKeyboard
                                                )
                                )
                            }
                            val showReplyAffordanceInList =
                                    showReplyActions && replyHeaderContext == null
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
                                    onOpenSlockWebLink = onOpenSlockWebLink,
                                    onOpenTask = onOpenTask,
                                    onTaskStatusChange = onTaskStatusChange,
                                    onSaveMessage = onSaveMessage,
                                    onRetryThreadLoad = onRetryThreadLoad,
                                    attachmentOpenStates = attachmentOpenStates,
                                    onOpenAttachment = onOpenAttachment,
                                    showOlderPageState = replyHeaderContext == null,
                                    showNewerPageState = replyHeaderContext == null,
                                    showReplyAction = showReplyAffordanceInList,
                                    resolveMemberName = { id ->
                                        (membersPanelState.loadState as? ScreenLoadState.Content)
                                            ?.data?.members
                                            ?.firstOrNull { it.id == id }
                                            ?.displayName
                                    }
                            )
                        }
                    }
                    if (!showComposer) {
                        // Pane previews keep the parent conversation readable while the active
                        // reply thread owns composing and reply creation.
                    } else if (readOnlyPreview) {
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
                                placeholder =
                                        if (replyHeaderContext != null) {
                                            "Message Threads"
                                        } else {
                                            "Message #${header.name}"
                                        },
                                onComposerChange = onComposerChange,
                                onAsTaskChange = onAsTaskChange,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onRemoveAttachment = onRemoveAttachment,
                                onRetryAttachment = onRetryAttachment,
                                onSend = onSend,
                                focusRequestNonce = composerFocusRequestNonce,
                                memberSuggestions = memberSuggestions,
                                channelSuggestions = channelSuggestions,
                                showAsTaskToggle = replyHeaderContext == null
                        )
                    }
                }
                is ScreenLoadState.Empty ->
                        ThreadScreenLoadFailure(
                                selectedTab = state.selectedTab,
                                showModeTabs = showModeTabs,
                                onBack = onBack,
                                onSettingsClick = { showSettingsModal = true },
                                onLeaveChannel = onLeaveChannel,
                                membersCount = membersCount,
                                onMembersClick = { showMembersModal = true },
                                replyHeaderContext = replyHeaderContext,
                                parentJumpTarget = parentJumpTarget,
                                onOpenParentMessage = onOpenParentMessage,
                                title = "Thread failed to load",
                                detail = "Missing thread context: ${loadState.reason.name}",
                                onRetry = onRetryThreadLoad,
                                dismissComposerKeyboard = dismissComposerKeyboard,
                                navigationIcon = headerNavigationIcon,
                                showConversationActions = showConversationActions,
                                fallbackComposerHeader = fallbackComposerHeader,
                                composerState = state.composerState,
                                showComposer = showComposer,
                                onComposerChange = onComposerChange,
                                onAsTaskChange = onAsTaskChange,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onRemoveAttachment = onRemoveAttachment,
                                onRetryAttachment = onRetryAttachment,
                                onSend = onSend,
                                focusRequestNonce = composerFocusRequestNonce
                        )
                is ScreenLoadState.Error ->
                        ThreadScreenLoadFailure(
                                selectedTab = state.selectedTab,
                                showModeTabs = showModeTabs,
                                onBack = onBack,
                                onSettingsClick = { showSettingsModal = true },
                                onLeaveChannel = onLeaveChannel,
                                membersCount = membersCount,
                                onMembersClick = { showMembersModal = true },
                                replyHeaderContext = replyHeaderContext,
                                parentJumpTarget = parentJumpTarget,
                                onOpenParentMessage = onOpenParentMessage,
                                title = "Thread failed to load",
                                detail = "Error code: ${loadState.error.code}",
                                onRetry = onRetryThreadLoad,
                                dismissComposerKeyboard = dismissComposerKeyboard,
                                navigationIcon = headerNavigationIcon,
                                showConversationActions = showConversationActions,
                                fallbackComposerHeader = fallbackComposerHeader,
                                composerState = state.composerState,
                                showComposer = showComposer,
                                onComposerChange = onComposerChange,
                                onAsTaskChange = onAsTaskChange,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onRemoveAttachment = onRemoveAttachment,
                                onRetryAttachment = onRetryAttachment,
                                onSend = onSend,
                                focusRequestNonce = composerFocusRequestNonce
                        )
                is ScreenLoadState.NoPermission -> {
                    ThreadTopHeader(
                            modifier =
                                    Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
                            onBack = onBack,
                            onSettingsClick = { showSettingsModal = true },
                            onLeaveChannel = onLeaveChannel,
                            membersCount = membersCount,
                            onMembersClick = { showMembersModal = true },
                            replyHeaderContext = replyHeaderContext,
                            parentJumpTarget = parentJumpTarget,
                            onOpenParentMessage = onOpenParentMessage,
                            navigationIcon = headerNavigationIcon,
                            showConversationActions = showConversationActions
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
                    if (showComposer) {
                        val fallbackHeader = selectedThreadFallbackJoinHeader()
                        if (fallbackHeader != null) {
                            ThreadJoinComposerReplacement(
                                    header = fallbackHeader,
                                    status = state.channelJoinStatus,
                                    errorCode = state.channelJoinErrorCode,
                                    onJoin = { onJoinChannel(fallbackHeader) }
                            )
                        } else {
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
            }
        }

        if (showMembersModal) {
            if (selectedChannelMemberDetail != null) {
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
                        val sameMemberSelected =
                                selectedAddMemberId == member.id.value &&
                                        selectedAddMemberKind == member.kind
                        if (sameMemberSelected) {
                            selectedAddMemberId = null
                        } else {
                            selectedAddMemberId = member.id.value
                            selectedAddMemberKind = member.kind
                        }
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
                                    ?: "Unknown thread",
                    status = archiveChannelStatus,
                    onArchiveChannel = onArchiveChannel,
                    onDeleteChannel = onDeleteChannel,
                    onLeaveChannel = onLeaveChannel,
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
private fun ColumnScope.ThreadScreenLoadFailure(
        selectedTab: ThreadTab,
        showModeTabs: Boolean,
        onBack: () -> Unit,
        onSettingsClick: () -> Unit,
        onLeaveChannel: () -> Unit,
        membersCount: Int?,
        onMembersClick: () -> Unit,
        replyHeaderContext: ThreadReplyHeaderContext?,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit,
        title: String,
        detail: String,
        onRetry: () -> Unit,
        dismissComposerKeyboard: () -> Unit,
        navigationIcon: ThreadHeaderNavigationIcon,
        showConversationActions: Boolean,
        fallbackComposerHeader: ConversationSummary?,
        composerState: ComposerState,
        showComposer: Boolean,
        onComposerChange: (String) -> Unit,
        onAsTaskChange: (Boolean) -> Unit,
        onAttachImage: () -> Unit,
        onAttachFile: () -> Unit,
        onRemoveAttachment: (String) -> Unit,
        onRetryAttachment: () -> Unit,
        onSend: () -> Unit,
        focusRequestNonce: Int
) {
    ThreadTopHeader(
            modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard),
            onBack = onBack,
            onSettingsClick = onSettingsClick,
            onLeaveChannel = onLeaveChannel,
            membersCount = membersCount,
            onMembersClick = onMembersClick,
            replyHeaderContext = replyHeaderContext,
            parentJumpTarget = parentJumpTarget,
            onOpenParentMessage = onOpenParentMessage,
            navigationIcon = navigationIcon,
            showConversationActions = showConversationActions
    )
    if (showModeTabs) {
        ThreadModeTabs(
                selectedTab = selectedTab,
                onModeSelected = {},
                modifier = Modifier.dismissKeyboardOnPointerDown(dismissComposerKeyboard)
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
        SlockErrorState(title = title, detail = detail, onRetry = onRetry)
    }
    if (showComposer && fallbackComposerHeader != null) {
        ThreadComposerBar(
                composerState = composerState.forHeader(fallbackComposerHeader),
                placeholder = "Message #${fallbackComposerHeader.name}",
                onComposerChange = onComposerChange,
                onAsTaskChange = onAsTaskChange,
                onAttachImage = onAttachImage,
                onAttachFile = onAttachFile,
                onRemoveAttachment = onRemoveAttachment,
                onRetryAttachment = onRetryAttachment,
                onSend = onSend,
                focusRequestNonce = focusRequestNonce
        )
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
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .navigationBarsPadding()
                            .background(SlockColor.SurfaceWhite)
    ) {
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
        onLeaveChannel: () -> Unit = {},
        membersCount: Int? = 4,
        onMembersClick: () -> Unit = {},
        replyHeaderContext: ThreadReplyHeaderContext? = null,
        parentJumpTarget: ThreadParentJumpTarget? = null,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit = {},
        navigationIcon: ThreadHeaderNavigationIcon = ThreadHeaderNavigationIcon.Back,
        showConversationActions: Boolean = true,
        refreshStatus: ThreadRefreshStatus = ThreadRefreshStatus.Idle,
        refreshErrorCode: String? = null
) {
    DeclareStatusBarColor(SlockColor.SurfaceWhite)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val titleStatusInline = maxWidth >= 520.dp
        val horizontalPadding = threadHorizontalPadding()
        val subtitleMaxLines = 1
        val title =
                if (replyHeaderContext != null) {
                    "Threads"
                } else {
                    summary?.name ?: "Unknown thread"
                }
        val subtitle =
                if (replyHeaderContext != null) {
                    replyHeaderContext.replyHeaderSubtitle()
                } else {
                    summary?.subtitle
                }
        val isDirectMessage = summary?.kind == ConversationKind.DirectMessage && replyHeaderContext == null
        val showManagementActions =
                showConversationActions && replyHeaderContext == null && parentJumpTarget == null
        val showNavigationIconAtStart = navigationIcon == ThreadHeaderNavigationIcon.Back
        val showCloseActionAtEnd = navigationIcon == ThreadHeaderNavigationIcon.Close
        val refreshText = when (refreshStatus) {
            ThreadRefreshStatus.Loading -> "拉取最新信息..."
            ThreadRefreshStatus.Error -> if (!refreshErrorCode.isNullOrBlank()) {
                "刷新失败 ($refreshErrorCode)"
            } else {
                "刷新失败"
            }
            ThreadRefreshStatus.Idle -> null
        }
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .heightIn(min = SlockSize.PageHeaderHeight)
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
            if (showNavigationIconAtStart) {
                HeaderIconButton(SlockIcons.ArrowLeft, "Back", onClick = onBack)
            }
            if (replyHeaderContext == null) {
                if (isDirectMessage) {
                    summary?.let { ThreadConversationAvatar(summary = it) }
                } else {
                    ThreadHashTile()
                }
            }
            Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (refreshText != null && titleStatusInline) {
                    Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
                    ) {
                        Text(
                                text = title,
                                style = SlockType.PageTitle,
                                color = SlockColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                        )
                        ThreadHeaderRefreshLabel(
                                text = refreshText,
                                loading = refreshStatus == ThreadRefreshStatus.Loading,
                                modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                } else {
                    Text(
                            text = title,
                            style = SlockType.PageTitle,
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )
                    if (refreshText != null) {
                        ThreadHeaderRefreshLabel(
                                text = refreshText,
                                loading = refreshStatus == ThreadRefreshStatus.Loading
                        )
                    }
                }
                if (refreshText == null && !subtitle.isNullOrBlank()) {
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
                        SlockIcons.ExternalLink,
                        "Open original message",
                        onClick = { onOpenParentMessage(parentJumpTarget) }
                )
            }
            if (showCloseActionAtEnd) {
                HeaderIconButton(SlockIcons.X, "Close", onClick = onBack)
            }
            if (showManagementActions && !isDirectMessage) {
                HeaderIconButton(SlockIcons.Settings, "Settings", onClick = onSettingsClick)
                ThreadMembersButton(count = membersCount, onClick = onMembersClick)
            }
        }
    }
}

@Composable
private fun ThreadHeaderRefreshLabel(
        text: String,
        loading: Boolean,
        modifier: Modifier = Modifier
) {
    Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (loading) {
            CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = SlockColor.TextMutedGray
            )
        }
        Text(
                text = text,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadConversationAvatar(summary: ConversationSummary) {
    Box(modifier = Modifier.size(40.dp)) {
        SlockAvatar(
                avatarUrl = summary.avatarUrl,
                avatar = summary.avatar,
                label = summary.name.take(1).uppercase(),
                modifier = Modifier.fillMaxSize(),
                size = 40.dp,
                fontSize = SlockType.Button.fontSize,
                fallbackColor = SlockColor.AccentLavender
        )
        val agentStatus = summary.agentStatus
        if (summary.kind == ConversationKind.DirectMessage && agentStatus != null) {
            SlockAgentStatusIndicator(
                    status = agentStatus,
                    presence = summary.presence ?: agentStatus.presence,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    size = 12.dp
            )
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
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
        onOpenAttachment: (Attachment) -> Unit,
        showReplyAffordance: Boolean,
        modifier: Modifier = Modifier
) {
    var originExpanded by remember(context.parentMessage?.id, context.parentMessagePreview) {
        mutableStateOf(false)
    }
    val collapsedMaxHeight = threadOriginCollapsedMaxHeight()
    val shouldCollapseOrigin = remember(context.parentMessage, context.parentMessagePreview) {
        context.shouldCollapseOriginMessage()
    }
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
            ThreadCollapsibleOriginMessage(
                    expanded = originExpanded,
                    collapsedMaxHeight = collapsedMaxHeight,
                    collapsible = shouldCollapseOrigin,
                    onExpandedChange = { originExpanded = it }
            ) {
                ThreadMessageItem(
                        message = parentMessage,
                        attachmentOpenStates = attachmentOpenStates,
                        onOpenAttachment = onOpenAttachment,
                        showReplyAffordance = showReplyAffordance,
                        onOpenThread =
                                parentJumpTarget?.let {
                                    { onOpenParentMessage(it) }
                                },
                        onOpenAuthorProfile = null
                )
            }
        } else {
            ThreadCollapsibleOriginMessage(
                    expanded = originExpanded,
                    collapsedMaxHeight = collapsedMaxHeight,
                    collapsible = shouldCollapseOrigin,
                    onExpandedChange = { originExpanded = it }
            ) {
                ThreadOriginFallbackMessage(
                        context = context,
                        parentJumpTarget = parentJumpTarget,
                        onOpenParentMessage = onOpenParentMessage,
                        showReplyAffordance = showReplyAffordance
                )
            }
        }
    }
}

private fun ThreadReplyHeaderContext.shouldCollapseOriginMessage(): Boolean {
    val originText = parentMessage?.body ?: parentMessagePreview.orEmpty()
    val lineCount = originText.lineSequence().count()
    return lineCount > 7 ||
            originText.length > 640 ||
            parentMessage?.attachments?.isNotEmpty() == true
}

@Composable
private fun threadOriginCollapsedMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    return (configuration.screenHeightDp.dp * 0.18f).coerceIn(88.dp, 132.dp)
}

@Composable
private fun threadOriginExpandedMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    return (configuration.screenHeightDp.dp * 0.48f).coerceIn(220.dp, 360.dp)
}

@Composable
private fun ThreadCollapsibleOriginMessage(
        expanded: Boolean,
        collapsedMaxHeight: Dp,
        collapsible: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        content: @Composable () -> Unit
) {
    val expandedMaxHeight = threadOriginExpandedMaxHeight()
    val expandedScrollState = rememberScrollState()
    Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
                modifier =
                        Modifier.fillMaxWidth()
                                .then(
                                        if (!expanded && collapsible) {
                                            Modifier.heightIn(max = collapsedMaxHeight)
                                                    .drawWithContent {
                                                        drawContent()
                                                        val fadeHeight = 20.dp.toPx()
                                                        drawRect(
                                                                color = SlockColor.SurfaceWhite.copy(
                                                                        alpha = 0.86f
                                                                ),
                                                                topLeft =
                                                                        Offset(
                                                                                0f,
                                                                                (size.height -
                                                                                                fadeHeight)
                                                                                        .coerceAtLeast(
                                                                                                0f
                                                                                        )
                                                                        ),
                                                                size =
                                                                        androidx.compose.ui.geometry
                                                                                .Size(
                                                                                        size.width,
                                                                                        fadeHeight
                                                                                )
                                                        )
                                                    }
                                        } else {
                                            if (expanded && collapsible) {
                                                Modifier.heightIn(max = expandedMaxHeight)
                                                        .verticalScroll(expandedScrollState)
                                            } else {
                                                Modifier
                                            }
                                        }
                                )
        ) {
            content()
        }
        if (collapsible) {
            ThreadOriginCompactToggle(
                                        expanded = expanded,
                    text = if (expanded) "Collapse origin" else "Expand origin",
                    onClick = { onExpandedChange(!expanded) },
                    modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThreadOriginCompactToggle(
        expanded: Boolean,
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
) {
    Box(
            modifier = modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
    ) {
        Row(
                modifier = Modifier.padding(horizontal = SlockSpacing.Sm, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            Text(
                    text = text,
                    style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Icon(
                    imageVector = SlockIcons.ChevronDown,
                    contentDescription = null,
                    tint = SlockColor.TextMutedGray,
                    modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

@Composable
private fun ThreadOriginFallbackMessage(
        context: ThreadReplyHeaderContext,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit,
        showReplyAffordance: Boolean
) {
    val requestedParentMessageId = context.parentMessageId ?: parentJumpTarget?.parentMessageId
    if (context.parentMessageRefreshing) {
        ThreadOriginRefreshingMessage(requestedParentMessageId)
        return
    }
    if (requestedParentMessageId != null) {
        ThreadOriginMissingMessage(
                context = context,
                parentMessageId = requestedParentMessageId,
                parentJumpTarget = parentJumpTarget,
                onOpenParentMessage = onOpenParentMessage
        )
        return
    }
    val preview = context.parentMessagePreview?.takeIf { it.isNotBlank() } ?: "Original message"
    val fallbackAuthor =
            MemberProfile(
                    id = MemberId("thread-origin-fallback"),
                    displayName = "Original message",
                    handle = "thread-origin",
                    kind = MemberKind.Agent,
                    roleDescription = null
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
            showReplyAffordance = showReplyAffordance,
            onOpenThread =
                    parentJumpTarget?.let {
                        { onOpenParentMessage(it) }
                    },
            onOpenAuthorProfile = null
    )
}

@Composable
private fun ThreadOriginRefreshingMessage(parentMessageId: MessageId?) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .background(SlockColor.SurfaceWhite)
                            .padding(SlockSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = SlockColor.TextBlack
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                    text = "Refreshing original message...",
                    style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            parentMessageId?.value?.takeIf { it.isNotBlank() }?.let { id ->
                Text(
                        text = "parentMessageId=$id",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadOriginMissingMessage(
        context: ThreadReplyHeaderContext,
        parentMessageId: MessageId,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit
) {
    val localContext = LocalContext.current
    val diagnostics = remember(context, parentMessageId) {
        context.originDiagnostics(parentMessageId)
    }
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .background(SlockColor.SurfaceWhite)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
                text = "Missing original message: ${parentMessageId.value}",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack
        )
        if (!context.parentMessageRefreshErrorCode.isNullOrBlank()) {
            Text(
                    text = "Refresh failed: ${context.parentMessageRefreshErrorCode}",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
        Text(
                text = diagnostics,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            parentJumpTarget?.let { target ->
                SlockButton(
                        text = "Open origin",
                        onClick = { onOpenParentMessage(target) },
                        variant = SlockButtonVariant.Outline,
                        modifier = Modifier.weight(1f)
                )
            }
            SlockButton(
                    text = "Copy diagnostics",
                    onClick = { localContext.copyThreadOriginDiagnostics(diagnostics) },
                    variant = SlockButtonVariant.Outline,
                    modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun ThreadReplyHeaderContext.originDiagnostics(parentMessageId: MessageId): String =
        buildString {
            appendLine("missing_origin_message=true")
            appendLine("parentMessageId=${parentMessageId.value}")
            appendLine("parentChannelName=${parentChannelName.orEmpty()}")
            appendLine("parentMessagePreview=${parentMessagePreview.orEmpty()}")
            appendLine("parentMessagePresent=${parentMessage != null}")
            appendLine("parentMessageBody=${parentMessage?.body.orEmpty()}")
            appendLine("parentMessageThreadId=${parentMessage?.threadId?.value.orEmpty()}")
            appendLine("parentMessageChannelId=${parentMessage?.channelId?.value.orEmpty()}")
            appendLine("parentMessageThreadChannelId=${parentMessage?.threadChannelId?.value.orEmpty()}")
            appendLine("parentMessageReplyCount=${parentMessage?.replyCount ?: 0}")
            parentMessageRefreshErrorCode?.takeIf { it.isNotBlank() }?.let { errorCode ->
                appendLine("parentMessageRefreshErrorCode=$errorCode")
            }
        }.trimEnd()

private fun Context.copyThreadOriginDiagnostics(diagnostics: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Slock origin diagnostics", diagnostics))
    android.widget.Toast.makeText(this, "Origin diagnostics copied", android.widget.Toast.LENGTH_SHORT)
            .show()
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
        val presence: Presence,
        val agentStatus: AgentStatus?
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
                presence = presence,
                agentStatus = agentStatus
        )

private val ThreadMembersPanelState.modalMembers: List<ThreadMemberModalItem>
    get() =
            when (val state = loadState) {
                is ScreenLoadState.Content ->
                        state.data.members.map { it.toThreadMemberModalItem() }
                else -> emptyList()
            }

private val ThreadMembersPanelState.memberCount: Int?
    get() =
            (loadState as? ScreenLoadState.Content)?.data?.members?.size
                    ?: fallbackMemberCount?.takeIf { fallbackMemberCountKnown }

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
                presence = presence,
                agentStatus = agentStatus
        )

private fun threadSampleMemberProfiles(): List<MemberProfile> =
        listOf(
                MemberProfile(
                        id = MemberId("sample-agent-android"),
                        displayName = "Android-Developer",
                        handle = "@android-developer",
                        kind = MemberKind.Agent,
                        description = "UI components",
                        roleDescription = "agent",
                        avatarUrl = "pixel:robot",
                        avatarLabel = "A",
                        presence = Presence.Active
                ),
                MemberProfile(
                        id = MemberId("sample-agent-api"),
                        displayName = "API-Integration-Developer",
                        handle = "@api-integration-developer",
                        kind = MemberKind.Agent,
                        description = "Data contracts",
                        roleDescription = "agent",
                        avatarUrl = "random:api-integration-developer",
                        avatarLabel = "I",
                        presence = Presence.Away
                ),
                MemberProfile(
                        id = MemberId("sample-human-artin"),
                        displayName = "artin",
                        handle = "@artin",
                        kind = MemberKind.Human,
                        description = "Product owner",
                        avatarLabel = "A",
                        presence = Presence.Active
                ),
                MemberProfile(
                        id = MemberId("sample-human-qa"),
                        displayName = "QA-Tester",
                        handle = "@qa-tester",
                        kind = MemberKind.Human,
                        description = "Acceptance",
                        avatarUrl = "pixel:star",
                        avatarLabel = "Q",
                        presence = Presence.Offline
                )
        )

private fun threadMemberModalItems(): List<ThreadMemberModalItem> =
        threadSampleMemberProfiles().map { it.toThreadMemberModalItem() }

enum class ThreadSettingsModalStatus {
    Default,
    Saving,
    Deleting,
    Error,
    Disabled,
    NoPermission
}

enum class ThreadDangerAction {
    Leave,
    Archive,
    Delete
}

@Composable
private fun ThreadSettingsModal(
        initialName: String,
        modifier: Modifier = Modifier,
        status: ThreadSettingsModalStatus = ThreadSettingsModalStatus.Default,
        onArchiveChannel: () -> Unit = {},
        onDeleteChannel: () -> Unit = {},
        onLeaveChannel: () -> Unit = {},
        onDismiss: () -> Unit
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var description by rememberSaveable {
        mutableStateOf("Coordinate Android UI, sample state, and app integration.")
    }
    var confirmingAction by rememberSaveable { mutableStateOf<ThreadDangerAction?>(null) }
    var confirmationName by rememberSaveable(confirmingAction) { mutableStateOf("") }
    val archiving = status == ThreadSettingsModalStatus.Saving
    val deleting = status == ThreadSettingsModalStatus.Deleting
    val busy = archiving || deleting
    val disabled =
            status == ThreadSettingsModalStatus.Disabled ||
                    status == ThreadSettingsModalStatus.NoPermission
    val enabled = !busy && !disabled
    val dangerAction = confirmingAction
    val confirmMatches = confirmationName.trim() == initialName

    SlockModal(
            title =
                    when (dangerAction) {
                        ThreadDangerAction.Leave -> "LEAVE CHANNEL"
                        ThreadDangerAction.Archive -> "ARCHIVE CHANNEL"
                        ThreadDangerAction.Delete -> "DELETE CHANNEL"
                        null -> "EDIT CHANNEL"
                    },
            onDismiss = onDismiss,
            modifier = modifier,
            enabled = !disabled,
            dismissOnScrimClick = !busy,
            errorText =
                    when (status) {
                        ThreadSettingsModalStatus.Error ->
                                when (dangerAction) {
                                    ThreadDangerAction.Leave -> "Unable to leave channel."
                                    ThreadDangerAction.Delete -> "Unable to delete channel."
                                    else -> "Unable to archive channel."
                                }
                        ThreadSettingsModalStatus.NoPermission ->
                                when (dangerAction) {
                                    ThreadDangerAction.Leave ->
                                            "You do not have permission to leave this channel."
                                    else -> "You do not have permission to edit this channel."
                                }
                        else -> null
                    },
            secondaryAction =
                    SlockModalAction(
                            text = if (dangerAction == null) "Cancel" else "Back",
                            onClick = {
                                if (dangerAction == null) {
                                    onDismiss()
                                } else if (!busy) {
                                    confirmingAction = null
                                    confirmationName = ""
                                }
                            },
                            style = SlockModalActionStyle.Outline,
                            enabled = !busy
                    ),
            primaryAction =
                    when (dangerAction) {
                        ThreadDangerAction.Leave ->
                                SlockModalAction(
                                        text = if (deleting) "Leaving" else "Leave Channel",
                                        onClick = onLeaveChannel,
                                        style = SlockModalActionStyle.Warning,
                                        enabled = enabled,
                                        loading = deleting,
                                        icon = SlockIcons.LogOut
                                )
                        ThreadDangerAction.Archive ->
                                SlockModalAction(
                                        text = if (archiving) "Archiving" else "Archive Channel",
                                        onClick = onArchiveChannel,
                                        style = SlockModalActionStyle.Warning,
                                        enabled = enabled && confirmMatches,
                                        loading = archiving,
                                        icon = SlockIcons.Archive
                                )
                        ThreadDangerAction.Delete ->
                                SlockModalAction(
                                        text = if (deleting) "Deleting" else "Delete Channel",
                                        onClick = onDeleteChannel,
                                        style = SlockModalActionStyle.Danger,
                                        enabled = enabled && confirmMatches,
                                        loading = deleting,
                                        icon = SlockIcons.Trash
                                )
                        null ->
                                SlockModalAction(
                                        text = "Save Changes",
                                        onClick = onDismiss,
                                        enabled = enabled && name.isNotBlank(),
                                        loading = false
                                )
                    },
            dangerActions =
                    if (dangerAction == null) {
                        listOf(
                                SlockModalAction(
                                        text = "Leave Channel",
                                        onClick = {
                                            confirmationName = ""
                                            confirmingAction = ThreadDangerAction.Leave
                                        },
                                        style = SlockModalActionStyle.Warning,
                                        enabled = enabled,
                                        icon = SlockIcons.LogOut
                                ),
                                SlockModalAction(
                                        text = "Archive Channel",
                                        onClick = {
                                            confirmationName = ""
                                            confirmingAction = ThreadDangerAction.Archive
                                        },
                                        style = SlockModalActionStyle.Warning,
                                        enabled = enabled,
                                        icon = SlockIcons.Archive
                                ),
                                SlockModalAction(
                                        text = "Delete Channel",
                                        onClick = {
                                            confirmationName = ""
                                            confirmingAction = ThreadDangerAction.Delete
                                        },
                                        style = SlockModalActionStyle.Danger,
                                        enabled = enabled,
                                        icon = SlockIcons.Trash
                                )
                        )
                    } else {
                        emptyList()
                    }
    ) {
        if (dangerAction == null) {
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
        } else {
            ThreadDangerConfirmationContent(
                    action = dangerAction,
                    channelName = initialName,
                    confirmationName = confirmationName,
                    onConfirmationNameChange = { confirmationName = it },
                    enabled = enabled,
                    isError = confirmationName.isNotBlank() && !confirmMatches
            )
        }
    }
}

@Composable
private fun ThreadDangerConfirmationContent(
        action: ThreadDangerAction,
        channelName: String,
        confirmationName: String,
        onConfirmationNameChange: (String) -> Unit,
        enabled: Boolean,
        isError: Boolean
) {
    Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when (action) {
            ThreadDangerAction.Leave -> {
                Text(
                        text =
                                "Leave #$channelName? You will be removed from this channel, but the channel itself will remain.",
                        style = SlockType.Body,
                        color = SlockColor.TextBlack
                )
                Text(
                        text = "You can rejoin later if your permissions still allow it.",
                        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextMutedGray
                )
            }
            ThreadDangerAction.Archive,
            ThreadDangerAction.Delete -> {
                Text(
                        text =
                                when (action) {
                                    ThreadDangerAction.Archive ->
                                            "Archive #$channelName? Members keep read access and history, but writes will be frozen and the channel will be hidden from sidebars."
                                    ThreadDangerAction.Delete ->
                                            "Delete #$channelName? This permanently removes the channel and its messages."
                                    ThreadDangerAction.Leave -> ""
                                },
                        style = SlockType.Body,
                        color = SlockColor.TextBlack
                )
                Text(
                        text = "Type $channelName to confirm.",
                        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextMutedGray
                )
                SlockModalTextField(
                        value = confirmationName,
                        onValueChange = onConfirmationNameChange,
                        label = "Channel name",
                        placeholder = channelName,
                        required = true,
                        enabled = enabled,
                        isError = isError,
                        errorText = if (isError) "Channel name does not match." else null
                )
            }
        }
    }
}

@Composable
private fun ThreadAttachmentPreviewModal(
        state: AttachmentPreviewUiState,
        onDismiss: () -> Unit,
        onOpenExternally: () -> Unit
) {
    val isTextPreview = state.isTextPreview
    val title =
            state.name?.takeIf { it.isNotBlank() }
                    ?: if (isTextPreview) "Text preview" else "Image preview"
    SlockModal(
            title = title,
            onDismiss = onDismiss,
            errorText =
                    when (state.errorCode) {
                        null -> null
                        "no_handler" ->
                                if (isTextPreview) {
                                    "No app can open this file externally."
                                } else {
                                    "No app can open this image externally."
                                }
                        "missing_url" -> "Preview URL is missing."
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
            if (isTextPreview) {
                ThreadAttachmentTextPreview(text = state.textContent.orEmpty())
            } else {
                ThreadAttachmentRemoteImagePreview(
                        url = state.url,
                        thumbnailUrl = state.thumbnailUrl,
                        name = title
                )
            }
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
private fun ThreadAttachmentTextPreview(text: String) {
    val scrollState = rememberScrollState()
    Box(
            modifier =
                    Modifier.fillMaxWidth()
                            .heightIn(min = 220.dp, max = 420.dp)
                            .background(
                                    SlockColor.AppBgLightBlue,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clip(RoundedCornerShape(SlockRadius.Xs))
    ) {
        SelectionContainer {
            Text(
                    text = text,
                    style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                    color = SlockColor.TextBlack,
                    modifier =
                            Modifier.fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .padding(SlockSpacing.Sm)
            )
        }
    }
}

@Composable
private fun ThreadAttachmentRemoteImagePreview(
        url: String?,
        thumbnailUrl: String?,
        name: String
) {
    val imageUrl = url?.takeIf { it.isNotBlank() }
    val fallbackUrl =
            thumbnailUrl?.takeIf { it.isNotBlank() && it != imageUrl }
    val context = LocalPlatformContext.current
    val cacheKey = remember(imageUrl) { imageUrl?.let { "attachment-preview:$it" } }
    val request =
            remember(context, imageUrl, cacheKey) {
                imageUrl?.let {
                    ImageRequest.Builder(context)
                            .data(it)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey(cacheKey)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .build()
                }
            }
    val fallbackCacheKey =
            remember(fallbackUrl) { fallbackUrl?.let { "attachment-preview:$it" } }
    val fallbackRequest =
            remember(context, fallbackUrl, fallbackCacheKey) {
                fallbackUrl?.let {
                    ImageRequest.Builder(context)
                            .data(it)
                            .memoryCacheKey(fallbackCacheKey)
                            .diskCacheKey(fallbackCacheKey)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .build()
                }
            }
    var hasFallbackImage by remember(fallbackUrl) { mutableStateOf(fallbackUrl != null) }
    var loadState by remember(imageUrl) {
        mutableStateOf(
                if (imageUrl == null) RemoteImageLoadState.Missing
                else RemoteImageLoadState.Loading
        )
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
        if ((loadState == RemoteImageLoadState.Missing || loadState == RemoteImageLoadState.Error) &&
                        !hasFallbackImage
        ) {
            Icon(
                    imageVector = SlockIcons.Image,
                    contentDescription = null,
                    tint = SlockColor.TextMutedGray,
                    modifier = Modifier.size(40.dp)
            )
        } else if (loadState == RemoteImageLoadState.Loading && !hasFallbackImage) {
            CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = SlockColor.TextBlack,
                    trackColor = SlockColor.SurfaceWhite
            )
        }
        if (fallbackRequest != null) {
            AsyncImage(
                    model = fallbackRequest,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    onSuccess = { hasFallbackImage = true },
                    modifier =
                            Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(SlockSpacing.Sm)
            )
        }
        if (request != null) {
            AsyncImage(
                    model = request,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    onLoading = { loadState = RemoteImageLoadState.Loading },
                    onSuccess = { loadState = RemoteImageLoadState.Success },
                    onError = { loadState = RemoteImageLoadState.Error },
                    modifier =
                            Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(SlockSpacing.Sm)
            )
        }
    }
}

private enum class RemoteImageLoadState {
    Missing,
    Loading,
    Success,
    Error
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
            enabled = enabled,
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
            enabled = enabled,
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
                            enabled = true
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
            onBack = onBack,
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
                    SlockMemberAvatar(
                            avatarUrl = member.avatarUrl,
                            avatar = member.avatar,
                            label = member.avatarLabel,
                            kind = member.kind,
                            presence = member.presence,
                            agentStatus = member.agentStatus,
                            modifier = Modifier.fillMaxSize(),
                            size = 46.dp,
                            fontSize = SlockType.Button.fontSize,
                            fallbackColor = member.kind.defaultAvatarColor
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
            SlockMemberAvatar(
                    avatarUrl = member.avatarUrl,
                    avatar = member.avatar,
                    label = member.avatarLabel,
                    kind = member.kind,
                    presence = member.presence,
                    agentStatus = member.agentStatus,
                    modifier = Modifier.fillMaxSize(),
                    size = 34.dp,
                    fontSize = SlockType.Caption.fontSize,
                    fallbackColor = member.kind.defaultAvatarColor,
                    statusSize = 9.dp
            )
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
                            else "${member.role} · ${member.agentStatus.agentStatusLabel(member.presence)}",
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
    val openAvatarDescription = "Open ${member.name} details"
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
        Box(
                modifier =
                        Modifier.size(46.dp)
                                .semantics { contentDescription = openAvatarDescription }
                                .clickable(role = Role.Button, onClick = onClick)
        ) {
            SlockMemberAvatar(
                    avatarUrl = member.avatarUrl,
                    avatar = member.avatar,
                    label = member.avatarLabel,
                    kind = member.kind,
                    presence = member.presence,
                    agentStatus = member.agentStatus,
                    modifier = Modifier.fillMaxSize(),
                    size = 46.dp,
                    fontSize = SlockType.Button.fontSize,
                    fallbackColor = member.kind.defaultAvatarColor
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
                    text =
                            if (member.kind == MemberKind.Human) member.role
                            else "${member.role} · ${member.agentStatus.agentStatusLabel(member.presence)}",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
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
    SlockKeySurface(
            onClick = onClick,
            enabled = enabled,
            restingShadow = SlockShadow.Small,
            pressedShadow = SlockShadow.None,
            surfaceModifier = Modifier.size(38.dp)
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
private fun ThreadMembersButton(count: Int?, onClick: () -> Unit) {
    SlockKeySurface(
            onClick = onClick,
            restingShadow = SlockShadow.Small,
            pressedShadow = SlockShadow.None,
            surfaceModifier = Modifier.heightIn(min = 38.dp).defaultMinSize(minWidth = 54.dp)
    ) {
        Row(
                modifier = Modifier.padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Icon(
                    SlockIcons.Users,
                    contentDescription = "Members",
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(18.dp)
            )
            Text(count?.toString() ?: "--", style = SlockType.Button, color = SlockColor.TextBlack)
        }
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
                            imageVector = mode.tabIcon,
                            contentDescription = null,
                            tint = SlockColor.TextBlack,
                            modifier = Modifier.size(18.dp)
                    )
                    Text(
                            text = stringResource(mode.labelRes).uppercase(),
                            style = SlockType.Button,
                            color = SlockColor.TextBlack
                    )
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
        onOpenSlockWebLink: (String) -> Boolean,
        onOpenTask: (TaskSummary) -> Unit,
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
        onSaveMessage: (Message) -> Unit,
        onRetryThreadLoad: () -> Unit,
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
        onOpenAttachment: (Attachment) -> Unit,
        showOlderPageState: Boolean = true,
        showNewerPageState: Boolean = true,
        showReplyAction: Boolean = true,
        resolveMemberName: (MemberId) -> String? = { null }
) {
    val horizontalPadding = threadHorizontalPadding()
    val showOlderPageState = showOlderPageState && content.shouldShowOlderPageState
    val showNewerPageState = showNewerPageState && content.shouldShowNewerPageState
    val beforeSeq = content.messages.mapNotNull { it.seq }.minOrNull()
    val isLoadingOlder = content.olderMessagesStatus == MessagePageStatus.Loading
    val bottomItemCount = if (showNewerPageState) 1 else 0
    val topItemCount =
            (if (showOlderPageState) 1 else 0) +
                    (if (content.historyLimited || content.hasGap) 1 else 0) +
                    (if (content.replyActionErrorCode != null) 1 else 0)
    val highlightedMessageIndex =
            content.highlightedMessageId?.let { highlightedId ->
                content.messages.indexOfFirst { it.id == highlightedId }.takeIf { it >= 0 }
            }
    val latestWindowStartIndex =
            (content.messages.size - ThreadMessageRenderWindowChunkSize).coerceAtLeast(0)
    val initialOldestRenderedMessageIndex =
            highlightedMessageIndex?.let { minOf(latestWindowStartIndex, it) }
                    ?: latestWindowStartIndex
    val highlightedMessageKey = content.highlightedMessageId?.value.orEmpty()
    val scrollStateKey = remember(threadId, highlightedMessageKey) {
        ThreadMessageScrollStateKey(
                threadId = threadId,
                highlightedMessageId = highlightedMessageKey
        )
    }
    val restoredScrollState = remember(scrollStateKey) {
        ThreadMessageScrollStateStore.restore(scrollStateKey)
    }
    val listState = remember(scrollStateKey) {
        LazyListState(
                firstVisibleItemIndex = restoredScrollState?.firstVisibleItemIndex ?: 0,
                firstVisibleItemScrollOffset = restoredScrollState?.firstVisibleItemScrollOffset ?: 0
        )
    }
    var oldestRenderedMessageIndex by remember(scrollStateKey) {
        mutableStateOf(restoredScrollState?.oldestRenderedMessageIndex ?: initialOldestRenderedMessageIndex)
    }
    var initialPositionApplied by remember(scrollStateKey) {
        mutableStateOf(restoredScrollState?.initialPositionApplied ?: false)
    }
    var observedLastMessageIdValue by remember(scrollStateKey) {
        mutableStateOf(restoredScrollState?.observedLastMessageId)
    }
    var followNewMessages by remember(scrollStateKey) {
        mutableStateOf(restoredScrollState?.followNewMessages ?: true)
    }
    val observedLastMessageId = observedLastMessageIdValue?.let(::MessageId)
    LaunchedEffect(
            scrollStateKey,
            listState,
            oldestRenderedMessageIndex,
            initialPositionApplied,
            observedLastMessageIdValue,
            followNewMessages
    ) {
        snapshotFlow {
            ThreadMessageScrollStateSnapshot(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    oldestRenderedMessageIndex = oldestRenderedMessageIndex,
                    initialPositionApplied = initialPositionApplied,
                    observedLastMessageId = observedLastMessageIdValue,
                    followNewMessages = followNewMessages
            )
        }.collect { snapshot ->
            ThreadMessageScrollStateStore.save(scrollStateKey, snapshot)
        }
    }
    DisposableEffect(scrollStateKey, listState) {
        onDispose {
            ThreadMessageScrollStateStore.save(
                    scrollStateKey,
                    ThreadMessageScrollStateSnapshot(
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                            oldestRenderedMessageIndex = oldestRenderedMessageIndex,
                            initialPositionApplied = initialPositionApplied,
                            observedLastMessageId = observedLastMessageIdValue,
                            followNewMessages = followNewMessages
                    )
            )
        }
    }
    LaunchedEffect(content.messages.size, highlightedMessageIndex, followNewMessages) {
        val nextLatestWindowStartIndex =
                (content.messages.size - ThreadMessageRenderWindowChunkSize).coerceAtLeast(0)
        val requiredWindowStartIndex =
                highlightedMessageIndex?.let { minOf(nextLatestWindowStartIndex, it) }
                        ?: if (followNewMessages) nextLatestWindowStartIndex
                        else oldestRenderedMessageIndex.coerceAtMost(nextLatestWindowStartIndex)
        oldestRenderedMessageIndex =
                requiredWindowStartIndex.coerceIn(0, content.messages.size.coerceAtLeast(1) - 1)
    }
    val displayedMessages =
            remember(content.messages, oldestRenderedMessageIndex) {
                val startIndex = oldestRenderedMessageIndex.coerceIn(0, content.messages.size)
                content.messages.subList(startIndex, content.messages.size).asReversed()
            }
    val targetMessageIndex =
            highlightedMessageIndex ?: content.messages.lastIndex.takeIf { it >= 0 }
    val targetListIndex =
            targetMessageIndex
                    ?.takeIf { it >= oldestRenderedMessageIndex }
                    ?.let { bottomItemCount + content.messages.lastIndex - it }
    val latestMessageListIndex = content.messages.lastIndex.takeIf { it >= 0 }?.let { bottomItemCount }
    val oldestMessageListIndex =
            content.messages.lastIndex
                    .takeIf { it >= 0 && oldestRenderedMessageIndex in content.messages.indices }
                    ?.let { bottomItemCount + it - oldestRenderedMessageIndex }
    val hasMoreRenderedWindowBefore = oldestRenderedMessageIndex > 0
    val density = LocalDensity.current
    val keyboardBottomPx = WindowInsets.ime.getBottom(density)
    val keyboardVisible = keyboardBottomPx > 0
    var previousKeyboardVisible by remember(threadId) { mutableStateOf(keyboardVisible) }
    var keyboardInsetSettling by remember(threadId) { mutableStateOf(false) }
        var initialLayoutSettling by remember(threadId) { mutableStateOf(false) }
        var measuredLayoutRevision by remember(threadId) { mutableStateOf(0) }
        var explicitBottomScrollSettling by remember(threadId) { mutableStateOf(false) }
    val shouldLoadNewer by
                        remember(content.canLoadMoreAfter, content.newerMessagesStatus, bottomItemCount) {
                derivedStateOf {
                    val totalItems = listState.layoutInfo.totalItemsCount
                                        val firstVisibleIndex =
                                                        listState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: 0
                    content.canLoadMoreAfter &&
                            content.newerMessagesStatus == MessagePageStatus.Idle &&
                            totalItems > 0 &&
                                                        firstVisibleIndex <= bottomItemCount + 1
                }
            }
    val isNearBottom by remember(latestMessageListIndex, listState) {
        derivedStateOf {
            latestMessageListIndex == null || listState.isTargetItemAtBottom(latestMessageListIndex)
        }
    }
    val showBackToBottom by remember(latestMessageListIndex, listState) {
        derivedStateOf {
            latestMessageListIndex != null && !listState.isTargetItemAtBottom(latestMessageListIndex)
        }
    }
    val lastMessageId = content.messages.lastOrNull()?.id
    val coroutineScope = rememberCoroutineScope()
    val itemHeightCache = rememberThreadItemHeightCache()

    LaunchedEffect(
            threadId,
                        content.canLoadMoreBefore,
                        hasMoreRenderedWindowBefore,
            oldestMessageListIndex,
            bottomItemCount,
            content.olderMessagesStatus
    ) {
                if (!hasMoreRenderedWindowBefore &&
                                                (!content.canLoadMoreBefore ||
                                                                content.olderMessagesStatus != MessagePageStatus.Idle)
        ) {
            return@LaunchedEffect
        }
        var requestSent = false
        snapshotFlow {
            OlderTopProbe(
                    topVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index }
                            ?: listState.firstVisibleItemIndex,
                    topVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    oldestMessageListIndex = oldestMessageListIndex ?: -1
            )
        }
                .distinctUntilChanged()
                .collect { probe ->
                    val isNearTop =
                            probe.oldestMessageListIndex >= 0 &&
                                    probe.topVisibleItemIndex >= probe.oldestMessageListIndex - 1
                    if (!isNearTop) {
                        requestSent = false
                        return@collect
                    }
                    if (requestSent) {
                        return@collect
                    }
                                        if (oldestRenderedMessageIndex > 0) {
                                                oldestRenderedMessageIndex =
                                                                (oldestRenderedMessageIndex - ThreadMessageRenderWindowChunkSize)
                                                                                .coerceAtLeast(0)
                                                requestSent = false
                                                return@collect
                                        }
                                        if (!content.canLoadMoreBefore ||
                                                                        content.olderMessagesStatus != MessagePageStatus.Idle
                                        ) {
                                                return@collect
                                        }
                    val firstVisibleMessageItem =
                            listState.layoutInfo.visibleItemsInfo
                                    .filter { item ->
                                        val messageIndex =
                                                content.messages.lastIndex -
                                                        (item.index - bottomItemCount)
                                        messageIndex in content.messages.indices
                                    }
                                    .maxByOrNull { it.index }
                    val anchorMessageIndex =
                            firstVisibleMessageItem?.let { item ->
                                content.messages.lastIndex - (item.index - bottomItemCount)
                            }
                    val anchorMessageId =
                            anchorMessageIndex?.let { content.messages.getOrNull(it)?.id }
                    val anchorOffset = firstVisibleMessageItem?.offset?.coerceAtLeast(0) ?: 0
                    requestSent = true
                    onLoadOlderMessages(anchorMessageId, anchorOffset)
                }
    }
    LaunchedEffect(shouldLoadNewer) {
        if (shouldLoadNewer) {
            onLoadNewerMessages()
        }
    }
    LaunchedEffect(
            isNearBottom,
            lastMessageId,
            observedLastMessageId,
            initialPositionApplied,
            initialLayoutSettling
    ) {
                if (keyboardInsetSettling || initialLayoutSettling) {
            return@LaunchedEffect
        }
        if (initialPositionApplied && lastMessageId == observedLastMessageId) {
            followNewMessages = isNearBottom
            onNearBottomChange(isNearBottom)
        }
    }
    LaunchedEffect(threadId, highlightedMessageKey, content.messages.size, targetListIndex) {
        if (!initialPositionApplied && targetListIndex != null) {
            yield()
            yield()
            scrollThreadListItemToRenderedBottom(
                    listState = listState,
                    targetIndex = targetListIndex,
                    threadId = threadId,
                    reason = "initial_scroll",
                    messageCount = content.messages.size,
                    scrollToListBottom = content.highlightedMessageId == null
            )
            initialPositionApplied = true
            observedLastMessageIdValue = lastMessageId?.value
            followNewMessages = content.highlightedMessageId == null
        }
    }
    LaunchedEffect(keyboardVisible) {
        val keyboardVisibilityChanged = keyboardVisible != previousKeyboardVisible
        previousKeyboardVisible = keyboardVisible
        if (!initialPositionApplied || !followNewMessages || latestMessageListIndex == null) {
            keyboardInsetSettling = false
            return@LaunchedEffect
        }
        if (keyboardVisibilityChanged) {
            keyboardInsetSettling = true
            delay(120)
            scrollThreadListItemToRenderedBottom(
                    listState = listState,
                    targetIndex = latestMessageListIndex,
                    threadId = threadId,
                    reason = "keyboard_visibility",
                    scrollToListBottom = true
            )
            keyboardInsetSettling = false
            val targetAtBottom = listState.isTargetItemAtBottom(latestMessageListIndex)
            followNewMessages = targetAtBottom
            onNearBottomChange(targetAtBottom)
        }
    }
    LaunchedEffect(latestMessageListIndex, initialPositionApplied, followNewMessages) {
        if (!initialPositionApplied || !followNewMessages || latestMessageListIndex == null) {
            return@LaunchedEffect
        }
        initialLayoutSettling = true
        try {
            var firstVisibleItemIndex = listState.firstVisibleItemIndex
            var firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            repeat(12) {
                delay(80)
                if (!followNewMessages) return@LaunchedEffect
                val currentFirstVisibleItemIndex = listState.firstVisibleItemIndex
                val currentFirstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
                val userMovedAwayFromBottom =
                        currentFirstVisibleItemIndex > firstVisibleItemIndex ||
                                (currentFirstVisibleItemIndex == firstVisibleItemIndex &&
                                        currentFirstVisibleItemScrollOffset >
                                                firstVisibleItemScrollOffset + 24)
                if (userMovedAwayFromBottom) {
                    return@LaunchedEffect
                }
                val targetAtBottom = listState.isTargetItemAtBottom(latestMessageListIndex)
                if (!targetAtBottom) {
                    scrollThreadListItemToRenderedBottom(
                            listState = listState,
                            targetIndex = latestMessageListIndex,
                            threadId = threadId,
                            reason = "initial_layout_settle",
                            messageCount = content.messages.size,
                            scrollToListBottom = true
                    )
                    firstVisibleItemIndex = listState.firstVisibleItemIndex
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
                } else {
                    firstVisibleItemIndex = currentFirstVisibleItemIndex
                    firstVisibleItemScrollOffset = currentFirstVisibleItemScrollOffset
                }
            }
        } finally {
            initialLayoutSettling = false
        }
    }
    LaunchedEffect(
            measuredLayoutRevision,
            latestMessageListIndex,
            initialPositionApplied,
            followNewMessages,
            initialLayoutSettling,
            keyboardInsetSettling,
            explicitBottomScrollSettling
    ) {
        if (initialLayoutSettling || keyboardInsetSettling || explicitBottomScrollSettling) {
            return@LaunchedEffect
        }
        if (initialPositionApplied &&
                        followNewMessages &&
                        latestMessageListIndex != null &&
                        measuredLayoutRevision > 0
        ) {
            delay(96)
            if (!followNewMessages || initialLayoutSettling || keyboardInsetSettling) {
                return@LaunchedEffect
            }
            scrollThreadListItemToRenderedBottom(
                    listState = listState,
                    targetIndex = latestMessageListIndex,
                    threadId = threadId,
                    reason = "measured_height_changed",
                    messageCount = content.messages.size,
                    scrollToListBottom = true
            )
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
                scrollThreadListItemToRenderedBottom(
                        listState = listState,
                        targetIndex = latestMessageListIndex,
                        threadId = threadId,
                        reason = "last_message_changed",
                        messageCount = content.messages.size,
                        scrollToListBottom = true
                )
            }
            observedLastMessageIdValue = lastMessageId.value
            followNewMessages = shouldFollow
        } else if (lastMessageId != null && observedLastMessageId == null && initialPositionApplied
        ) {
            observedLastMessageIdValue = lastMessageId.value
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
                                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
                        if (showNewerPageState) {
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
                        itemsIndexed(items = displayedMessages, key = { _, message -> message.id.value }) {
                                        index,
                                        message ->
                                ThreadMessageListItemHeightCache(
                                                message = message,
                                                highlighted = message.id == content.highlightedMessageId,
                                                showReplyAffordance = showReplyAction,
                                                cache = itemHeightCache,
                                                onHeightChanged = { measuredLayoutRevision += 1 }
                                ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                                                if (message.messageType == MessageType.System) {
                                                        ThreadSystemMessageItem(
                                                                        message = message,
                                                                        highlighted = message.id == content.highlightedMessageId,
                                                                        showReplyAffordance = showReplyAction,
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
                                                                        showReplyAffordance = showReplyAction,
                                                                        onOpenThread =
                                                                                        if (showReplyAction) {
                                                                                                { onOpenMessageThread(message) }
                                                                                        } else {
                                                                                                null
                                                                                        },
                                                                        onOpenAuthorProfile = { onOpenMemberProfile(message.author) },
                                                                        onOpenTask = { onOpenTask(it) },
                                                                        onTaskStatusChange = onTaskStatusChange,
                                                                        onSaveMessage = onSaveMessage,
                                                                        onOpenMarkdownTag = onOpenMarkdownTag,
                                                                        onOpenSlockWebLink = onOpenSlockWebLink,
                                                                        resolveMemberName = resolveMemberName
                                                        )
                                                }
                                        }
                                }
                        }
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
        }
        if (showBackToBottom) {
            ThreadBackToBottomButton(
                    modifier =
                            Modifier.align(Alignment.BottomEnd)
                                    .padding(end = SlockSpacing.Md, bottom = SlockSpacing.Md)
                                    .testTag(THREAD_BACK_TO_BOTTOM_TAG),
                    onClick = {
                        val targetIndex =
                                latestMessageListIndex
                                        ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(
                                                0
                        )
                        coroutineScope.launch {
                            explicitBottomScrollSettling = true
                            try {
                                scrollThreadListItemToRenderedBottom(
                                        listState = listState,
                                        targetIndex = targetIndex,
                                        threadId = threadId,
                                        reason = "back_to_bottom_click",
                                        messageCount = content.messages.size,
                                        scrollToListBottom = true
                                )
                                followNewMessages = true
                                onNearBottomChange(true)
                            } finally {
                                delay(120)
                                explicitBottomScrollSettling = false
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun rememberThreadItemHeightCache(): ThreadItemHeightCache =
        remember { GlobalThreadItemHeightCache }

@Composable
internal fun ThreadMessageListItemHeightCache(
        message: Message,
        highlighted: Boolean,
        showReplyAffordance: Boolean = true,
        cache: ThreadItemHeightCache,
        onHeightChanged: () -> Unit = {},
        content: @Composable () -> Unit
) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val signature = remember(message, highlighted, showReplyAffordance) {
            message.threadItemHeightSignature(
                highlighted = highlighted,
                showReplyAffordance = showReplyAffordance
            )
        }
        val cachedHeight = cache.get(message.id, signature, widthPx)
        var measuredHeightPx by remember(message.id, signature, widthPx) {
            mutableStateOf(cachedHeight ?: 0)
        }
        Box(
                modifier =
                        Modifier.then(
                                        cachedHeight?.let { height ->
                                            Modifier.defaultMinSize(
                                                    minHeight = with(density) { height.toDp() }
                                            )
                                        } ?: Modifier
                                )
                                .onGloballyPositioned { coordinates ->
                                    val heightPx = coordinates.size.height
                                    if (heightPx > 0 && measuredHeightPx != heightPx) {
                                        measuredHeightPx = heightPx
                                        onHeightChanged()
                                    }
                                    cache.put(
                                            message.id,
                                            signature,
                                            coordinates.size.width,
                                            heightPx
                                    )
                                }
        ) {
            content()
        }
    }
}

internal interface ThreadItemHeightCache {
    fun get(messageId: MessageId, signature: Int, widthPx: Int): Int?
    fun put(messageId: MessageId, signature: Int, widthPx: Int, heightPx: Int)
}

internal class ThreadItemHeightCacheLru(private val maxEntries: Int = 512) : ThreadItemHeightCache {
    private val entries =
            object : LinkedHashMap<String, ThreadItemHeightCacheEntry>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, ThreadItemHeightCacheEntry>
                ): Boolean = size > maxEntries
    }

    @Synchronized
    override fun get(messageId: MessageId, signature: Int, widthPx: Int): Int? {
        val entry = entries[messageId.value] ?: return null
        return entry.heightPx.takeIf {
            entry.signature == signature && entry.widthPx == widthPx && it > 0
        }
    }

    @Synchronized
    override fun put(messageId: MessageId, signature: Int, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        entries[messageId.value] = ThreadItemHeightCacheEntry(signature, widthPx, heightPx)
    }
}

private object GlobalThreadItemHeightCache : ThreadItemHeightCache by ThreadItemHeightCacheLru()

private data class ThreadItemHeightCacheEntry(
        val signature: Int,
        val widthPx: Int,
        val heightPx: Int
)

internal fun Message.threadItemHeightSignature(
        highlighted: Boolean,
        showReplyAffordance: Boolean = true
): Int =
        listOf(
                        threadId.value,
                        channelId?.value.orEmpty(),
                        threadChannelId?.value.orEmpty(),
                        seq ?: -1L,
                        author.id.value,
                        author.displayName,
                        author.handle,
                        author.kind,
                        author.description.orEmpty(),
                        author.roleDescription.orEmpty(),
                        author.avatar?.wireValue.orEmpty(),
                        author.agentStatus,
                        timestampLabel,
                        body,
                        messageType,
                        systemEventType.orEmpty(),
                        attachments.map { attachment ->
                            listOf(
                                            attachment.id.value,
                                            attachment.name,
                                            attachment.mimeType,
                                            attachment.sizeLabel,
                                            attachment.thumbnailUrl.orEmpty()
                                    )
                                    .hashCode()
                        },
                        deliveryState,
                        task?.id?.value.orEmpty(),
                        task?.taskNumber ?: -1,
                        task?.status,
                        task?.assignee?.id?.value.orEmpty(),
                        task?.assignee?.displayName.orEmpty(),
                        task?.assignee?.handle.orEmpty(),
                        if (showReplyAffordance) replyCount else 0,
                        parentMessageId?.value.orEmpty(),
                        updatedAtLabel.orEmpty(),
                        showReplyAffordance,
                        highlighted
                )
                .hashCode()

private suspend fun scrollThreadListItemToRenderedBottom(
        listState: LazyListState,
        targetIndex: Int,
        threadId: String,
        reason: String,
        messageCount: Int? = null,
        scrollToListBottom: Boolean = false,
        reverseLayout: Boolean = true
) {
    yield()
        if (scrollToListBottom) {
                repeat(8) {
                        yield()
                        if (listState.layoutInfo.totalItemsCount > targetIndex) return@repeat
                        delay(16)
        }
                if (listState.layoutInfo.totalItemsCount <= targetIndex) {
                        return
                }
                if (reverseLayout) {
                        listState.scrollToItem(0, 0)
                        repeat(6) {
                                yield()
                                if (listState.firstVisibleItemIndex == 0 &&
                                                listState.firstVisibleItemScrollOffset <=
                                                                ThreadRenderedBottomTolerancePx
                                ) {
                                        return@repeat
                                }
                                listState.scrollToItem(0, 0)
                                delay(16)
                        }
                        return
                }
                listState.scrollToItem(targetIndex, 0)
                repeat(6) {
                        yield()
                        val targetItem =
                                        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
                        if (targetItem == null) {
                                listState.scrollToItem(targetIndex, 0)
                                delay(16)
                                return@repeat
                        }
                        val bottomDelta =
                                        targetItem.offset + targetItem.size - listState.layoutInfo.viewportEndOffset
                        if (kotlin.math.abs(bottomDelta) <= 1) {
                                return@repeat
                        }
                        listState.scrollBy(bottomDelta.toFloat())
                        delay(16)
                }
                return
        }
    listState.scrollToItem(targetIndex, 0)
    repeat(3) {
        yield()
        val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (targetItem == null) {
            listState.scrollToItem(targetIndex, 0)
            return@repeat
        }
        val bottomDelta =
                targetItem.offset + targetItem.size - listState.layoutInfo.viewportEndOffset
        if (kotlin.math.abs(bottomDelta) <= 1) {
            return@repeat
        }
        val scrollDelta = if (reverseLayout) -bottomDelta.toFloat() else bottomDelta.toFloat()
        listState.scrollBy(scrollDelta)
    }
}

private const val ThreadRenderedBottomTolerancePx = 24
private const val ThreadMessageRenderWindowChunkSize = 30
private const val ThreadMessageScrollStateStoreMaxSize = 64

private data class ThreadMessageScrollStateKey(
        val threadId: String,
        val highlightedMessageId: String
)

private data class ThreadMessageScrollStateSnapshot(
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int,
        val oldestRenderedMessageIndex: Int,
        val initialPositionApplied: Boolean,
        val observedLastMessageId: String?,
        val followNewMessages: Boolean
)

private object ThreadMessageScrollStateStore {
    private val snapshots =
            LinkedHashMap<ThreadMessageScrollStateKey, ThreadMessageScrollStateSnapshot>(
                    ThreadMessageScrollStateStoreMaxSize,
                    0.75f,
                    true
            )

    @Synchronized
    fun restore(key: ThreadMessageScrollStateKey): ThreadMessageScrollStateSnapshot? = snapshots[key]

    @Synchronized
    fun save(key: ThreadMessageScrollStateKey, snapshot: ThreadMessageScrollStateSnapshot) {
        snapshots[key] = snapshot
        while (snapshots.size > ThreadMessageScrollStateStoreMaxSize) {
            val oldestKey = snapshots.entries.firstOrNull()?.key ?: return
            snapshots.remove(oldestKey)
        }
    }
}

private fun LazyListState.isTargetItemAtBottom(targetIndex: Int): Boolean {
    val layoutInfo = layoutInfo
    if (firstVisibleItemIndex == 0 &&
                    firstVisibleItemScrollOffset <= ThreadRenderedBottomTolerancePx &&
                    layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
    ) {
        return true
    }
    val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    val targetBottom = targetItem?.let { it.offset + it.size } ?: return false
    return kotlin.math.abs(targetBottom - layoutInfo.viewportEndOffset) <= ThreadRenderedBottomTolerancePx
}

private data class OlderTopProbe(
        val topVisibleItemIndex: Int,
        val topVisibleItemScrollOffset: Int,
        val oldestMessageListIndex: Int
)

@Composable
private fun threadHorizontalPadding(): Dp =
        if (LocalConfiguration.current.screenWidthDp < 420) SlockSpacing.Sm else SlockSpacing.Md

@Composable
private fun ThreadBackToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    SlockKeySurface(
            onClick = onClick,
            modifier = modifier,
            backgroundColor = SlockColor.AccentCyan,
            restingShadow = SlockShadow.Small,
            pressedShadow = SlockShadow.None,
            surfaceModifier = Modifier.heightIn(min = 28.dp)
    ) {
        Row(
                modifier = Modifier.padding(horizontal = SlockSpacing.Sm, vertical = 4.dp),
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

private fun Modifier.threadTabSwipe(
        selectedTab: ThreadTab,
        enabled: Boolean,
        onModeSelected: (ThreadTab) -> Unit
): Modifier =
        if (!enabled) {
            this
        } else {
            pointerInput(selectedTab, onModeSelected) {
                val requiredDistance = ThreadTabSwipeThreshold.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var switched = false
                    while (!switched) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        totalX += delta.x
                        totalY += delta.y
                        val horizontalDistance = abs(totalX)
                        val verticalDistance = abs(totalY)
                        if (verticalDistance > horizontalDistance * ThreadTabSwipeVerticalTolerance) {
                            break
                        }
                        if (horizontalDistance >= requiredDistance &&
                                        horizontalDistance >
                                                verticalDistance *
                                                        ThreadTabSwipeHorizontalDominance
                        ) {
                            val targetTab =
                                    if (totalX < 0f) {
                                        ThreadTab.entries.getOrNull(selectedTab.ordinal + 1)
                                    } else {
                                        ThreadTab.entries.getOrNull(selectedTab.ordinal - 1)
                                    }
                            if (targetTab != null) {
                                change.consume()
                                onModeSelected(targetTab)
                                switched = true
                            }
                        }
                    }
                }
            }
        }

private val ThreadTabSwipeThreshold = 56.dp
private const val ThreadTabSwipeHorizontalDominance = 1.35f
private const val ThreadTabSwipeVerticalTolerance = 1.1f

private val ThreadTab.labelRes: Int
    get() =
            when (this) {
                ThreadTab.Chat -> R.string.thread_tab_chat
                ThreadTab.Tasks -> R.string.thread_tab_tasks
                ThreadTab.Files -> R.string.thread_tab_files
            }

private val ThreadTab.tabIcon
    get() =
            when (this) {
                ThreadTab.Chat -> SlockIcons.MessageSquare
                ThreadTab.Tasks -> SlockIcons.ListTodo
                ThreadTab.Files -> SlockIcons.Paperclip
            }

@Composable
private fun ThreadFilesPanel(
        state: ThreadFilesUiState?,
        onOpenAttachment: (Attachment) -> Unit,
        onRetry: () -> Unit,
        onLoadMore: () -> Unit,
        modifier: Modifier = Modifier
) {
    val loadState = state?.loadState ?: ScreenLoadState.Loading
    val payloadJson = remember(state) { state.toKuiklyThreadFilesPayload().toString() }
    SideEffect {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadFiles",
                "kuikly_panel_render status=${loadState.threadFilesStatusName()} items=${loadState.threadFilesItemCount()} hasMore=${loadState.threadFilesHasMore()} loadingMore=${state?.loadingMore == true} payloadBytes=${payloadJson.length}"
        )
    }
    val attachmentsById =
            remember(loadState) {
                ((loadState as? ScreenLoadState.Content)?.data?.items.orEmpty())
                        .associate { it.attachment.id.value to it.attachment }
            }
    SlockKuiklyPageIsland(
            pageName = SlockKuiklyActivity.PAGE_THREAD_FILES,
            pageData = JSONObject().put("payload", payloadJson),
            modifier = modifier.fillMaxSize(),
            onEvent = { event ->
                val action = event.optString("action")
                val attachmentId = event.optString("attachmentId")
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadFiles",
                        "kuikly_panel_event action=$action attachmentId=$attachmentId hasAttachment=${attachmentsById.containsKey(attachmentId)} mapSize=${attachmentsById.size}"
                )
                when (action) {
                    "open", "download" ->
                            attachmentsById[attachmentId]?.let(onOpenAttachment)
                    "loadMore" -> onLoadMore()
                    "retry" -> onRetry()
                }
            }
    )
}

private fun ScreenLoadState<ThreadFilesContent>.threadFilesStatusName(): String =
        when (this) {
            ScreenLoadState.Loading -> "loading"
            is ScreenLoadState.Content -> "content"
            is ScreenLoadState.Empty -> "empty"
            is ScreenLoadState.Error -> "error:${error.code}"
            is ScreenLoadState.NoPermission -> "no_permission:${blocker.code}"
        }

private fun ScreenLoadState<ThreadFilesContent>.threadFilesItemCount(): Int =
        (this as? ScreenLoadState.Content)?.data?.items?.size ?: 0

private fun ScreenLoadState<ThreadFilesContent>.threadFilesHasMore(): Boolean =
        (this as? ScreenLoadState.Content)?.data?.hasMore == true

private fun ThreadFilesUiState?.toKuiklyThreadFilesPayload(): JSONObject {
    val loadState = this?.loadState ?: ScreenLoadState.Loading
    val payload =
            JSONObject()
                    .put("loadingMore", this?.loadingMore == true)
                    .put("emptyTitle", "No files yet")
                    .put("emptyDetail", "Files shared in this thread will show up here.")
    return when (loadState) {
        ScreenLoadState.Loading -> payload.put("status", "loading")
        is ScreenLoadState.Content -> {
            val items = loadState.data.items
            payload
                    .put("status", if (items.isEmpty()) "empty" else "content")
                    .put("hasMore", loadState.data.hasMore)
                    .put(
                            "items",
                            JSONArray().apply {
                                items.forEach { item -> put(item.toKuiklyThreadFileJson()) }
                            }
                    )
        }
        is ScreenLoadState.Empty ->
                payload.put("status", "empty")
        is ScreenLoadState.Error ->
                payload.put("status", "error").put("errorCode", loadState.error.code)
        is ScreenLoadState.NoPermission ->
                payload
                        .put("status", "no_permission")
                        .put(
                                "errorCode",
                                loadState.blocker.requiredCapability ?: loadState.blocker.code
                        )
    }
}

private fun ChannelFileItem.toKuiklyThreadFileJson(): JSONObject =
        JSONObject()
                .put("attachmentId", attachment.id.value)
                .put("name", attachment.name)
                .put("sizeLabel", attachment.sizeLabel.toHumanReadableFileSizeLabel())
                .put("createdAtLabel", createdAt?.toThreadTimestampLabel().orEmpty())
                .put("sourceLabel", sourceLabel())
                .put("mimeType", attachment.mimeType)
                .put("thumbnailUrl", attachment.thumbnailUrl.orEmpty())

private fun String.toHumanReadableFileSizeLabel(): String {
    val raw = trim()
    if (raw.isBlank()) {
        return ""
    }
    val bytes =
            raw.toLongOrNull()
                    ?: Regex("""^(\d+)\s*(?:B|bytes?)$""", RegexOption.IGNORE_CASE)
                            .find(raw)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                    ?: return raw
    return when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)} GB"
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
}

private fun ChannelFileItem.sourceLabel(): String =
        sourceParentMessageShortId?.takeIf { it.isNotBlank() }?.let { "#thread:$it" }
                ?: messageId?.value?.take(8)?.takeIf { it.isNotBlank() }?.let { "#$it" }
                ?: sourceType?.replace('_', ' ').orEmpty()

@Composable
private fun LegacyThreadFilesPanel(
        state: ThreadFilesUiState?,
        onOpenAttachment: (Attachment) -> Unit,
        onRetry: () -> Unit,
        onLoadMore: () -> Unit,
        modifier: Modifier = Modifier
) {
    val loadState = state?.loadState ?: ScreenLoadState.Loading
    Column(
            modifier = modifier.background(SlockColor.SurfaceWhite).padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when (loadState) {
            ScreenLoadState.Loading -> {
                SlockLoadingState(title = stringResource(R.string.thread_files_loading_title))
            }
            is ScreenLoadState.Content -> {
                val items = loadState.data.items
                if (items.isEmpty()) {
                    SlockEmptyState(
                            title = stringResource(R.string.thread_files_empty_title),
                            detail = stringResource(R.string.thread_files_empty_detail)
                    )
                } else {
                    LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                            modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(items.size) { index ->
                            ThreadFileRow(
                                    item = items[index],
                                    onClick = { onOpenAttachment(items[index].attachment) }
                            )
                        }
                        if (loadState.data.hasMore) {
                            item {
                                SlockButton(
                                        text =
                                                if (state?.loadingMore == true) {
                                                    stringResource(R.string.thread_files_loading_more)
                                                } else {
                                                    stringResource(R.string.thread_files_load_more)
                                                },
                                        onClick = onLoadMore,
                                        enabled = state?.loadingMore != true,
                                        loading = state?.loadingMore == true,
                                        variant = SlockButtonVariant.Outline,
                                        modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
            is ScreenLoadState.Empty -> {
                SlockEmptyState(
                        title = stringResource(R.string.thread_files_empty_title),
                        detail = stringResource(R.string.thread_files_empty_detail)
                )
            }
            is ScreenLoadState.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
                    SlockErrorState(
                            title = stringResource(R.string.thread_files_error_title),
                            detail =
                                    stringResource(
                                            R.string.thread_files_error_detail,
                                            loadState.error.code
                                    )
                    )
                    SlockButton(
                            text = stringResource(R.string.settings_retry),
                            onClick = onRetry,
                            variant = SlockButtonVariant.Outline
                    )
                }
            }
            is ScreenLoadState.NoPermission -> {
                SlockEmptyState(
                        title = stringResource(R.string.thread_files_empty_title),
                        detail = loadState.blocker.requiredCapability ?: loadState.blocker.code
                )
            }
        }
    }
}

@Composable
private fun ThreadFileRow(
        item: ChannelFileItem,
        onClick: () -> Unit
) {
    val metadata = listOfNotNull(
            item.attachment.sizeLabel.takeIf { it.isNotBlank() },
            item.createdAt?.toThreadTimestampLabel()
    ).joinToString(" · ")
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(
                                    SlockColor.SurfaceWhite,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .border(
                                    SlockStroke.Thin,
                                    SlockColor.EmptyStateGray,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clickable(role = Role.Button, onClick = onClick)
                            .padding(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        ThreadFileThumbnail(item = item)
        Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                    text = item.attachment.name,
                    style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
            if (metadata.isNotBlank()) {
                Text(
                        text = metadata,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
            item.sourceType?.takeIf { it.isNotBlank() }?.let { sourceType ->
                ThreadFileSourceBadge(text = sourceType.replace('_', ' '))
            }
        }
        Icon(
                imageVector = SlockIcons.ChevronRight,
                contentDescription = null,
                tint = SlockColor.TextMutedGray,
                modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ThreadFileSourceBadge(
        text: String
) {
    Text(
            text = text.uppercase(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier =
                    Modifier.background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Xs, vertical = 1.dp),
            maxLines = 1
    )
}

@Composable
private fun ThreadFileThumbnail(item: ChannelFileItem) {
    val thumbnailUrl = item.attachment.thumbnailUrl
    val isImage = item.attachment.mimeType.startsWith("image/")
    if (isImage && !thumbnailUrl.isNullOrBlank()) {
        AsyncImage(
                model =
                        ImageRequest.Builder(LocalPlatformContext.current)
                                .data(thumbnailUrl)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                        Modifier.size(42.dp)
                                .clip(RoundedCornerShape(SlockRadius.Xs))
                                .border(
                                        SlockStroke.Thin,
                                        SlockColor.EmptyStateGray,
                                        RoundedCornerShape(SlockRadius.Xs)
                                )
        )
    } else {
        Box(
                modifier =
                        Modifier.size(42.dp)
                                .background(
                                        SlockColor.SurfaceCream,
                                        RoundedCornerShape(SlockRadius.Xs)
                                )
                                .border(
                                        SlockStroke.Thin,
                                        SlockColor.EmptyStateGray,
                                        RoundedCornerShape(SlockRadius.Xs)
                                ),
                contentAlignment = Alignment.Center
        ) {
            Icon(
                    imageVector =
                            if (isImage) SlockIcons.Image else SlockIcons.FileText,
                    contentDescription = null,
                    tint = SlockColor.TextMutedGray,
                    modifier = Modifier.size(18.dp)
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
internal const val THREAD_COMPOSER_FIELD_TAG = "thread-composer-field"
internal const val THREAD_COMPOSER_TEXT_AREA_TAG = "thread-composer-text-area"
internal const val THREAD_COMPOSER_SUGGESTIONS_TAG = "thread-composer-suggestions"
internal const val THREAD_INLINE_CODE_TAG = "thread-inline-code"
internal const val THREAD_MESSAGE_HEADER_DESCRIPTION_TAG = "thread-message-header-description"
internal const val THREAD_MESSAGE_HEADER_TIME_TAG = "thread-message-header-time"

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
        showReplyAffordance: Boolean = true,
        onOpenThread: (() -> Unit)? = null,
        onOpenAuthorProfile: (() -> Unit)? = null,
        onOpenTask: ((TaskSummary) -> Unit)? = null,
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit = { _, _ -> },
        onSaveMessage: (Message) -> Unit = {},
        onShareMessage: (Message) -> Unit = {},
        onCopyMessageLink: (Message) -> Unit = {},
        onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit = {},
        onOpenSlockWebLink: (String) -> Boolean = { false },
        resolveMemberName: (MemberId) -> String? = { null }
) {
    val context = LocalContext.current
    var showMessageActions by remember(message.id.value) { mutableStateOf(false) }
        var messageActionAnchor by remember(message.id.value) { mutableStateOf<IntOffset?>(null) }
    var messageActionAreaOffset by remember(message.id.value) { mutableStateOf(IntOffset.Zero) }
    var messageBodyOffset by remember(message.id.value) { mutableStateOf(IntOffset.Zero) }
    var messageInteractiveAreaBounds by remember(message.id.value) { mutableStateOf<List<Rect>>(emptyList()) }
    var messageBodyInteractiveAreaBounds by remember(message.id.value) {
        mutableStateOf<List<Rect>>(emptyList())
    }
    var messageAttachmentInteractiveAreaBounds by remember(message.id.value) {
        mutableStateOf<List<Rect>>(emptyList())
    }
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
    val hasFooterMeta = message.task != null || (showReplyAffordance && message.replyCount > 0)
    val authorDescription =
            message.author.description
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
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
                                                        message.task != null,
                                        rootOffsetProvider = { messageActionAreaOffset },
                                        interactiveBoundsProvider = {
                                            messageInteractiveAreaBounds +
                                                    messageBodyInteractiveAreaBounds +
                                                    messageAttachmentInteractiveAreaBounds
                                        },
                                        onOpen = ::toggleMessageActions,
                                        onDismiss = { showMessageActions = false }
                                )
                                .semantics {
                                    contentDescription = "Message actions"
                                    onClick(label = "Message actions") {
                                        toggleMessageActions(messageActionAreaOffset)
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
                Text(
                        text = message.author.displayName,
                        style = SlockType.Button,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp)
                )
                if (authorDescription != null) {
                    Text(
                            text = authorDescription,
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                    Modifier.weight(1f)
                                            .testTag(THREAD_MESSAGE_HEADER_DESCRIPTION_TAG)
                    )
                }
                if (message.timestampLabel.isNotBlank()) {
                    Text(
                            text = message.timestampLabel.toThreadTimestampLabel(),
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.testTag(THREAD_MESSAGE_HEADER_TIME_TAG)
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
                            onTagClick = onOpenMarkdownTag,
                            onOpenSlockWebLink = onOpenSlockWebLink,
                            onInteractiveBoundsChanged = { bounds ->
                                messageBodyInteractiveAreaBounds = bounds
                            }
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
                        alignEnd = alignCurrentUserRight,
                        onOpenAttachment = onOpenAttachment,
                        onInteractiveBoundsChanged = { bounds ->
                            messageAttachmentInteractiveAreaBounds = bounds
                        },
                        modifier = Modifier.messageContentWidth(messageMaxWidthFraction)
                )
            } else if (messageAttachmentInteractiveAreaBounds.isNotEmpty()) {
                messageAttachmentInteractiveAreaBounds = emptyList()
            }
                        if (hasFooterMeta) {
                ThreadMessageFooterMeta(
                        message = message,
                        onTaskStatusChange = onTaskStatusChange,
                        onOpenThread = onOpenThread,
                        showReplyAffordance = showReplyAffordance,
                        alignEnd = alignCurrentUserRight,
                        modifier =
                                Modifier.messageContentWidth(messageMaxWidthFraction)
                                        .padding(top = ThreadMarkdownParagraphSpacing)
                                        .onGloballyPositioned { coordinates ->
                                            messageInteractiveAreaBounds =
                                                    listOf(coordinates.boundsInRoot())
                                        }
                )
            } else if (messageInteractiveAreaBounds.isNotEmpty()) {
                messageInteractiveAreaBounds = emptyList()
            }
            if (showMessageActions) {
                ThreadMessageActionsPopup(
                        message = message,
                                                anchor = messageActionAnchor ?: IntOffset.Zero,
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
                            onSaveMessage(message)
                            saveFeedbackVisible = true
                            showMessageActions = false
                        },
                        onShare = {
                            onShareMessage(message)
                            showMessageActions = false
                        },
                        onTaskStatusChange = { nextStatus ->
                            message.task?.let { task -> onTaskStatusChange(task, nextStatus) }
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

private fun Modifier.messageActionsTrigger(
        enabled: Boolean,
        rootOffsetProvider: () -> IntOffset,
        interactiveBoundsProvider: () -> List<Rect>,
        onOpen: (IntOffset) -> Unit,
        onDismiss: () -> Unit
): Modifier =
        if (!enabled) {
            this
        } else {
            this.pointerInput(onOpen, onDismiss) {
                        awaitEachGesture {
                            val down =
                                    awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial
                                    )
                            val rootOffset = rootOffsetProvider()
                            val downInRoot =
                                    Offset(
                                            rootOffset.x + down.position.x,
                                            rootOffset.y + down.position.y
                                    )
                            if (interactiveBoundsProvider().any { bounds ->
                                            downInRoot.x >= bounds.left &&
                                                    downInRoot.x <= bounds.right &&
                                                    downInRoot.y >= bounds.top &&
                                                    downInRoot.y <= bounds.bottom
                                    }
                            ) {
                                return@awaitEachGesture
                            }
                            val pointerId = down.id
                            var totalPositionChange = androidx.compose.ui.geometry.Offset.Zero
                            var lastPosition = down.position
                            var wasDragDismissed = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                event.changes
                                        .firstOrNull { it.id == pointerId }
                                        ?.let { change ->
                                            totalPositionChange += change.positionChange()
                                            lastPosition = change.position
                                            if (change.isConsumed) {
                                                wasDragDismissed = true
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
        onTaskStatusChange: (TaskStatus) -> Unit
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
                    ?: with(density) { SlockMenuOverlayDefaults.Width.roundToPx() }
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
    BackHandler(enabled = true, onBack = onDismiss)
    Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = onDismiss,
            properties =
                    PopupProperties(
                            focusable = true,
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
            SlockMenuOverlay(
                    actions = message.threadMessageActions(
                            onReply = onReply,
                            onCopy = onCopy,
                            onCopyLink = onCopyLink,
                            onSave = onSave,
                            onShare = onShare,
                            onTaskStatusChange = onTaskStatusChange
                    ),
                    modifier =
                            Modifier.offset { menuOffset }
                                    .onGloballyPositioned { coordinates ->
                                        menuSize = coordinates.size
                                    }
                                    .testTag(THREAD_MESSAGE_ACTION_MENU_TAG)
            )
        }
    }
}

private fun Message.threadMessageActions(
        onReply: (() -> Unit)?,
        onCopy: () -> Unit,
        onCopyLink: () -> Unit,
        onSave: () -> Unit,
        onShare: () -> Unit,
        onTaskStatusChange: (TaskStatus) -> Unit
): List<SlockMenuOverlayAction> =
        buildList {
            add(
                    SlockMenuOverlayAction(
                            label = "Share messages...",
                            icon = SlockIcons.Share2,
                            group = "share",
                            onClick = onShare
                    )
            )
            add(
                    SlockMenuOverlayAction(
                            label = "Copy link",
                            icon = SlockIcons.Link,
                            group = "message-copy",
                            onClick = onCopyLink
                    )
            )
            add(
                    SlockMenuOverlayAction(
                            label = "Copy markdown",
                            icon = SlockIcons.Copy,
                            group = "message-copy",
                            onClick = onCopy
                    )
            )
            add(
                    SlockMenuOverlayAction(
                            label = "Save message",
                            icon = SlockIcons.Bookmark,
                            group = "message",
                            onClick = onSave
                    )
            )
            if (onReply != null) {
                add(
                        SlockMenuOverlayAction(
                                label = "Reply",
                                icon = SlockIcons.MessageSquare,
                                group = "message",
                                onClick = onReply
                        )
                )
            }
            task?.let { task ->
                val actionStatus =
                        when (task.status) {
                            TaskStatus.Done, TaskStatus.Closed -> TaskStatus.InProgress
                            else -> TaskStatus.Done
                        }
                val actionLabel =
                        when (task.status) {
                            TaskStatus.Done, TaskStatus.Closed -> "Reopen task"
                            else -> "Mark done"
                        }
                val actionIcon =
                        when (task.status) {
                            TaskStatus.Done, TaskStatus.Closed -> SlockIcons.RefreshCw
                            else -> SlockIcons.CircleCheckBig
                        }
                add(
                        SlockMenuOverlayAction(
                                label = actionLabel,
                                icon = actionIcon,
                                group = "task",
                                onClick = { onTaskStatusChange(actionStatus) }
                        )
                )
            }
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
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
        onOpenThread: (() -> Unit)?,
        showReplyAffordance: Boolean,
        alignEnd: Boolean,
        modifier: Modifier = Modifier
) {
        val task = message.task?.withMessageThreadContext(message)
        val unreadReplyCount = message.unreadReplyCount()
        val replyLabel =
                message.replyCount.takeIf { showReplyAffordance && it > 0 }?.let { replyCount ->
                    if (unreadReplyCount > 0) {
                        stringResource(
                                R.string.thread_message_reply_unread_count,
                                replyCount,
                                unreadReplyCount
                        )
                    } else {
                        stringResource(R.string.thread_message_reply_count, replyCount)
                    }
                }
        if (task == null && replyLabel == null) return
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
            ThreadTaskStatusChip(
                    task = task,
                    label = taskMetaLabel,
                    onTaskStatusChange = onTaskStatusChange,
                    textStyle = ThreadFooterMetaTextStyle,
                    modifier =
                            Modifier.weight(1f, fill = false)
                                    .widthIn(max = 260.dp)
                                    .testTag(THREAD_MESSAGE_FOOTER_ASSIGNEE_TAG)
            )
        }
        if (replyLabel != null) {
                    ThreadChip(
                            label = replyLabel,
                            color = SlockColor.AppBgLightBlue,
                            icon = SlockIcons.MessageSquare,
                            onClick = onOpenThread,
                            textStyle = ThreadFooterMetaTextStyle,
                            modifier =
                                    Modifier.widthIn(max = 220.dp)
                                            .testTag(THREAD_MESSAGE_FOOTER_REPLIES_TAG)
                    )
        }
    }
}

@Composable
private fun ThreadTaskStatusChip(
        task: TaskSummary,
        label: String,
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
        textStyle: TextStyle,
        modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(task.id.value, task.status) { mutableStateOf(false) }
    Box(modifier = modifier) {
        ThreadChip(
                label = label,
                color = task.status.threadColor,
                icon = task.status.threadIcon,
                onClick = { expanded = true },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textStyle = textStyle
        )
        if (expanded) {
            Popup(
                    alignment = Alignment.BottomStart,
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true)
            ) {
                SlockMenuOverlay(
                        actions =
                                TaskStatus.entries.map { status ->
                                    SlockMenuOverlayAction(
                                            label = status.threadLabel,
                                            icon = SlockIcons.Check.takeIf {
                                                status == task.status
                                            },
                                            onClick = {
                                                expanded = false
                                                onTaskStatusChange(task, status)
                                            }
                                    )
                                },
                        width = 184.dp
                )
        }
        }
    }
}

private fun TaskSummary.withMessageThreadContext(message: Message): TaskSummary =
        copy(
                messageId = messageId ?: message.id,
                parentMessageId = parentMessageId ?: message.id,
                channelId = channelId ?: message.channelId,
                threadId = threadId ?: message.threadChannelId?.let { ThreadId(it.value) }
        )

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
        alignEnd: Boolean,
        onOpenAttachment: (Attachment) -> Unit,
        onInteractiveBoundsChanged: (List<Rect>) -> Unit = {},
        modifier: Modifier = Modifier
) {
    val attachmentIds = remember(attachments) { attachments.map { it.id }.toSet() }
    var attachmentCardBounds by remember(attachmentIds) {
        mutableStateOf<Map<AttachmentId, Rect>>(emptyMap())
    }
    Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        attachments.forEach { attachment ->
            ThreadAttachmentCard(
                    attachment = attachment,
                    state = states[attachment.id] ?: AttachmentOpenUiState(),
                    onOpen = { onOpenAttachment(attachment) },
                    modifier =
                            Modifier.onGloballyPositioned { coordinates ->
                                val nextBounds =
                                        (attachmentCardBounds +
                                                        (attachment.id to
                                                                coordinates.boundsInRoot()))
                                                .filterKeys { it in attachmentIds }
                                attachmentCardBounds = nextBounds
                                onInteractiveBoundsChanged(nextBounds.values.toList())
                            }
            )
        }
    }
}

@Composable
private fun ThreadAttachmentCard(
        attachment: Attachment,
        state: AttachmentOpenUiState,
        onOpen: () -> Unit,
        modifier: Modifier = Modifier
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
                    modifier.size(92.dp)
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
    val imageUrl = thumbnailUrl?.takeIf { it.isNotBlank() }
    val context = LocalPlatformContext.current
    val cacheKey = remember(imageUrl) { imageUrl?.let { "attachment-thumbnail:$it" } }
    val request =
            remember(context, imageUrl, cacheKey) {
                imageUrl?.let {
                    ImageRequest.Builder(context)
                            .data(it)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey(cacheKey)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .build()
                }
            }
    var loadState by remember(imageUrl) {
        mutableStateOf(
                if (imageUrl == null) RemoteImageLoadState.Missing
                else RemoteImageLoadState.Loading
        )
    }
    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .background(
                                    if (loadState == RemoteImageLoadState.Success) {
                                        SlockColor.SurfaceWhite
                                    } else if (isImage) {
                                        SlockColor.AccentPink
                                    } else {
                                        SlockColor.SurfaceWhite
                                    },
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
        } else {
            if (request != null) {
                AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onLoading = { loadState = RemoteImageLoadState.Loading },
                        onSuccess = { loadState = RemoteImageLoadState.Success },
                        onError = { loadState = RemoteImageLoadState.Error },
                        modifier =
                                Modifier.fillMaxSize().clip(RoundedCornerShape(SlockRadius.Xs))
                )
            }
            if (loadState != RemoteImageLoadState.Success) {
                Icon(
                        imageVector = if (isImage) SlockIcons.Image else SlockIcons.FileText,
                        contentDescription = null,
                        tint = SlockColor.TextBlack,
                        modifier = Modifier.size(24.dp)
                )
            }
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
        showReplyAffordance: Boolean = true,
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
        if (message.task != null || (showReplyAffordance && message.replyCount > 0)) {
            ThreadMessageFooterMeta(
                    message = message,
                    onTaskStatusChange = { _, _ -> },
                    onOpenThread = onOpenThread,
                    showReplyAffordance = showReplyAffordance,
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
                text = "Body",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray
        )
        ThreadMarkdownBody(
                body = message.body.sanitizeThreadSystemText(),
                modifier = Modifier.fillMaxWidth()
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

internal fun String.escapeThreadMarkdownHtmlTagsForThread(): String {
    if (!contains('<')) return this
    val protectedRanges = threadMarkdownCodeProtectionRanges()
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        if (protectedRanges.any { index in it }) {
            builder.append(this[index])
            index += 1
            continue
        }
        val tagMatch = ThreadMarkdownHtmlTagRegex.find(this, index)
        if (tagMatch != null && tagMatch.range.first == index) {
            builder.append(tagMatch.value.escapeMarkdownHtmlTagText())
            index = tagMatch.range.last + 1
        } else {
            builder.append(this[index])
            index += 1
        }
    }
    return builder.toString()
}

private fun String.threadMarkdownCodeProtectionRanges(): List<IntRange> =
        buildList {
            addAll(threadMarkdownFenceRanges())
            addAll(threadInlineCodeMatches().map { it.range })
        }

private fun String.threadMarkdownFenceRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var cursor = 0
    var fenceStart: Int? = null
    var fenceMarker: String? = null
    while (cursor < length) {
        val lineEnd = indexOf('\n', startIndex = cursor).let { if (it < 0) length else it }
        val line = substring(cursor, lineEnd)
        val trimmedStart = line.trimStart()
        val marker =
                when {
                    trimmedStart.startsWith("```") -> "```"
                    trimmedStart.startsWith("~~~") -> "~~~"
                    else -> null
                }
        if (marker != null) {
            if (fenceStart == null) {
                fenceStart = cursor
                fenceMarker = marker
            } else if (marker == fenceMarker) {
                ranges += fenceStart..lineEnd
                fenceStart = null
                fenceMarker = null
            }
        }
        cursor = (lineEnd + 1).coerceAtMost(length)
        if (lineEnd == length) break
    }
    fenceStart?.let { ranges += it until length }
    return ranges
}

private val ThreadMarkdownHtmlTagRegex =
        Regex(
                """</?[A-Za-z][A-Za-z0-9:_-]*(?:\s+[^<>]*)?/?>|<!--[\s\S]*?-->|<![A-Z][A-Z0-9\s-]*>"""
        )

private fun String.escapeMarkdownHtmlTagText(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun threadMarkdownTagColor(kind: ThreadMarkdownTagKind): Color =
        when (kind) {
            ThreadMarkdownTagKind.Channel -> SlockColor.AccentCyan
            ThreadMarkdownTagKind.Task -> SlockColor.AccentOrange
            ThreadMarkdownTagKind.UserMention -> SlockColor.AccentLavender
        }

private fun threadInlineTagPlaceholderWidthDp(
        label: String,
        hasIcon: Boolean,
        density: Density,
        textMeasurer: TextMeasurer
): Dp {
    val iconWidth = if (hasIcon) 14.dp + SlockSpacing.Xs else 0.dp
    return with(density) {
                textMeasurer.measure(text = label, style = ThreadInlineChipTextStyle).size.width.toDp()
        } + ThreadInlineChipHorizontalPadding * 2 + iconWidth +
            SlockStroke.Thin * 2 + ThreadInlineTagPlaceholderSlack
}

// Slot height must equal body lineHeight EXACTLY (no sp<->dp round-trip), otherwise
// fontScale / floating-point drift makes the slot slightly taller than the line box,
// stretching the last line (white edge below) and shifting the chip off-center.
private fun threadInlineChipPlaceholderHeightSp(@Suppress("UNUSED_PARAMETER") density: Density) =
        ThreadMessageBodyTextStyle.lineHeight

private val ThreadInlineChipHorizontalPadding = 10.dp
private val ThreadInlineChipVerticalPaddingTop = 1.dp
private val ThreadInlineChipVerticalPaddingBottom = 4.dp
private val ThreadFooterChipVerticalPadding = 3.dp
private val ThreadInlineTagPlaceholderSlack = 8.dp

private val ThreadInlineChipTextStyle: TextStyle
        get() =
                        SlockType.Caption.copy(
                                        fontSize = 14.sp,
                                        lineHeight = 16.sp,
                                        lineHeightStyle =
                                                LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                ),
                                        platformStyle =
                                                PlatformTextStyle(includeFontPadding = false)
                        )

private val ThreadFooterMetaTextStyle: TextStyle
        get() =
                ThreadInlineChipTextStyle.copy(
                        fontSize = 12.sp,
                        lineHeight = 14.sp
                )

@Composable
private fun ThreadMarkdownBody(
        body: String,
        modifier: Modifier = Modifier,
        textAlign: TextAlign = TextAlign.Start,
        onTagClick: ((ThreadMarkdownTag) -> Unit)? = null,
        onOpenSlockWebLink: (String) -> Boolean = { false },
        onInteractiveBoundsChanged: (List<Rect>) -> Unit = {}
) {
    val normalizedBody =
            remember(body) {
                body.normalizeThreadMarkdownBody().escapeThreadMarkdownHtmlTagsForThread()
            }
    ThreadOfficialMarkdown(
            body = normalizedBody,
            textAlign = textAlign,
            modifier = modifier,
            onTagClick = onTagClick,
            onOpenSlockWebLink = onOpenSlockWebLink,
            onInteractiveBoundsChanged = onInteractiveBoundsChanged
    )
}

@Composable
private fun ThreadOfficialMarkdown(
        body: String,
        textAlign: TextAlign = TextAlign.Start,
        modifier: Modifier = Modifier,
        onTagClick: ((ThreadMarkdownTag) -> Unit)? = null,
        onOpenSlockWebLink: (String) -> Boolean = { false },
        onInteractiveBoundsChanged: (List<Rect>) -> Unit = {}
) {
    val markdownState = rememberMarkdownState(content = body, retainState = true)
    val inlineTagMatches = remember(body, onTagClick != null) {
        if (onTagClick == null) emptyList() else body.threadInlineTagMatches()
    }
    val inlineTagById = remember(inlineTagMatches) { inlineTagMatches.associateBy { it.id } }
        val inlineCodeMatches = remember(body) { body.threadInlineCodeMatches() }
        val inlineCodeById = remember(inlineCodeMatches) { inlineCodeMatches.associateBy { it.id } }
    val inlineTagBounds = remember(body) { mutableStateMapOf<String, Rect>() }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
        val isDarkTheme = isSystemInDarkTheme()
        val highlightsBuilder = remember(isDarkTheme) {
                Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
        }
    val fallbackUriHandler = LocalUriHandler.current
    val uriHandler =
            remember(fallbackUriHandler, onOpenSlockWebLink) {
                object : UriHandler {
                    override fun openUri(uri: String) {
                        if (!onOpenSlockWebLink(uri)) {
                            fallbackUriHandler.openUri(uri)
                        }
                    }
                }
            }

    LaunchedEffect(body, onTagClick != null) {
        inlineTagBounds.clear()
        onInteractiveBoundsChanged(emptyList())
    }

    val annotator =
                        remember(inlineTagById, inlineCodeById) {
                markdownAnnotator { content, child ->
                                        if (child.type == MarkdownElementTypes.CODE_SPAN) {
                                                val codeMatch =
                                                        inlineCodeById["slock-code:${child.startOffset}"]
                                                                ?: inlineCodeMatches.firstOrNull {
                                                                        child.startOffset in it.range
                                                                }
                                                if (codeMatch != null) {
                                                        appendInlineContent(codeMatch.id, codeMatch.code)
                                                        return@markdownAnnotator true
                                                }
                    }
                                        if (child.type != MarkdownTokenTypes.TEXT ||
                                                                        child.parent?.type == MarkdownElementTypes.CODE_SPAN
                                        ) return@markdownAnnotator false

                    val nodeText = child.getTextInNode(content).toString()
                    val nodeMatches =
                            nodeText.threadInlineTagMatches(baseOffset = child.startOffset)
                                    .filter { inlineTagById.containsKey(it.id) }
                    if (nodeMatches.isEmpty()) return@markdownAnnotator false

                    var cursor = 0
                    nodeMatches.forEach { match ->
                        if (match.range.first > cursor) {
                            append(nodeText.substring(cursor, match.range.first))
                        }
                        appendInlineContent(match.id, match.tag.label)
                        cursor = match.range.last + 1
                    }
                    if (cursor < nodeText.length) append(nodeText.substring(cursor))
                    true
                }
            }

    val inlineContent =
            markdownInlineContent(
                                        remember(inlineTagById, inlineCodeById, density, textMeasurer) {
                                                val tagContent = inlineTagById.mapValues { (id, match) ->
                            val tag = match.tag
                            val label = threadMarkdownTagDisplayLabel(tag.label)
                            val width =
                                    with(density) {
                                        threadInlineTagPlaceholderWidthDp(
                                                label = label,
                                                hasIcon = false,
                                                density = density,
                                                textMeasurer = textMeasurer
                                        ).toSp()
                                    }
                            InlineTextContent(
                                    Placeholder(
                                            width = width,
                                            height = threadInlineChipPlaceholderHeightSp(density),
                                            placeholderVerticalAlign =
                                                    PlaceholderVerticalAlign.TextCenter
                                    )
                            ) {
                                // Center the chip inside the slot at its intrinsic height so
                                // CJK-tall lines (~21sp) don't stretch the chip background.
                                Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                ) {
                                    ThreadChip(
                                            label = label,
                                            color = threadMarkdownTagColor(tag.kind),
                                            icon = null,
                                            onClick = { onTagClick?.invoke(tag) },
                                            verticalPaddingTop = ThreadInlineChipVerticalPaddingTop,
                                            verticalPaddingBottom =
                                                    ThreadInlineChipVerticalPaddingBottom,
                                            maxLines = 1,
                                            modifier =
                                                    Modifier.onGloballyPositioned { coordinates ->
                                                        inlineTagBounds[id] =
                                                                coordinates.boundsInRoot()
                                                        onInteractiveBoundsChanged(
                                                                inlineTagBounds.values.toList()
                                                        )
                                                    }
                                    )
                                }
                            }
                        }
                        val codeContent = inlineCodeById.mapValues { (_, match) ->
                            val width =
                                    threadInlineCodePlaceholderWidthSp(
                                            code = match.code,
                                            density = density,
                                            textMeasurer = textMeasurer
                                    )
                            InlineTextContent(
                                    Placeholder(
                                            width = width,
                                            height = threadInlineChipPlaceholderHeightSp(density),
                                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                                    )
                            ) {
                                Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                ) {
                                    ThreadInlineCodeChip(match.code)
                                }
                            }
                        }
                        tagContent + codeContent
                    }
            )
    val components =
            markdownComponents(
                    codeBlock = {
                        MarkdownCodeBlock(
                                content = it.content,
                                node = it.node,
                                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace)
                        ) { code, language, style ->
                            ThreadMarkdownCodeBlock(
                                    code = code,
                                    language = language,
                                    style = style,
                                    highlightsBuilder = highlightsBuilder
                            )
                        }
                    },
                    codeFence = {
                        MarkdownCodeFence(
                                content = it.content,
                                node = it.node,
                                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace)
                        ) { code, language, style ->
                            ThreadMarkdownCodeBlock(
                                    code = code,
                                    language = language,
                                    style = style,
                                    highlightsBuilder = highlightsBuilder
                            )
                        }
                    },
                    paragraph = {
                        MarkdownParagraph(
                                content = it.content,
                                node = it.node,
                                                                modifier = threadMarkdownParagraphModifier(it.node),
                                style = it.typography.paragraph.copy(textAlign = textAlign)
                        )
                    },
                    checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) }
            )

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
                markdownState = markdownState,
                colors =
                        markdownColor(
                                text = SlockColor.TextBlack,
                                codeBackground = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                                inlineCodeBackground = SlockColor.BrandYellow.copy(alpha = 0.4f)
                        ),
                typography =
                        markdownTypography(
                                text = ThreadMessageBodyTextStyle.copy(textAlign = textAlign),
                                paragraph = ThreadMessageBodyTextStyle.copy(textAlign = textAlign),
                                h1 =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 20.sp,
                                                lineHeight = 26.sp,
                                                textAlign = textAlign
                                        ),
                                h2 =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                lineHeight = 24.sp,
                                                textAlign = textAlign
                                        ),
                                h3 =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp,
                                                lineHeight = 23.sp,
                                                textAlign = textAlign
                                        ),
                                h4 =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                lineHeight = 22.sp,
                                                textAlign = textAlign
                                        ),
                                h5 =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp,
                                                lineHeight = 21.sp,
                                                textAlign = textAlign
                                        ),
                                h6 =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp,
                                                lineHeight = 21.sp,
                                                textAlign = textAlign
                                        ),
                                code =
                                        SlockType.Caption.copy(
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = 18.sp
                                        ),
                                inlineCode =
                                        ThreadMessageBodyTextStyle.copy(
                                                fontFamily = FontFamily.Monospace,
                                                textAlign = textAlign
                                        )
                        ),
                imageTransformer = Coil3ImageTransformerImpl,
                annotator = annotator,
                inlineContent = inlineContent,
                components = components,
                modifier = modifier.fillMaxWidth()
        )
    }
}

private val ThreadMarkdownParagraphSpacing = 8.dp

private fun threadMarkdownParagraphModifier(node: ASTNode): Modifier {
        val isLastBlock = node.parent?.children?.lastOrNull() == node
        return if (isLastBlock) Modifier else Modifier.padding(bottom = ThreadMarkdownParagraphSpacing)
}

@Composable
private fun ThreadMarkdownCodeBlock(
        code: String,
        language: String?,
        style: TextStyle,
        highlightsBuilder: Highlights.Builder
) {
    val clipboard = LocalClipboardManager.current
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(vertical = SlockSpacing.Xs)
                            .clip(RoundedCornerShape(SlockRadius.Xs))
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                            .border(
                                    SlockStroke.Thin,
                                    SlockColor.BorderBlack.copy(alpha = 0.16f),
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
    ) {
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(
                                        start = SlockSpacing.Sm,
                                        top = SlockSpacing.Xs,
                                        end = SlockSpacing.Xs,
                                        bottom = 4.dp
                                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = language?.takeIf { it.isNotBlank() }?.uppercase() ?: "CODE",
                    style =
                            SlockType.Caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                            ),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
            )
            ThreadMarkdownCopyButton {
                clipboard.setText(AnnotatedString(code))
            }
        }
        MarkdownHighlightedCode(
                code = code,
                language = language,
                style = style,
                highlightsBuilder = highlightsBuilder,
                showHeader = false
        )
    }
}

@Composable
private fun ThreadMarkdownCopyButton(onClick: () -> Unit) {
    Box(
            modifier =
                    Modifier.size(28.dp)
                            .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
    ) {
        Icon(
                imageVector = SlockIcons.Copy,
                contentDescription = "Copy code",
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(16.dp)
        )
    }
}

private val ThreadInlineCodeTextStyle: TextStyle
    get() =
            ThreadInlineChipTextStyle

private fun threadInlineCodePlaceholderWidthSp(
        code: String,
        density: Density,
        textMeasurer: TextMeasurer
) = with(density) {
    val textWidth = textMeasurer.measure(text = code, style = ThreadInlineCodeTextStyle).size.width.toDp()
        (
                textWidth +
                        ThreadInlineChipHorizontalPadding * 2 +
                        SlockStroke.Thin * 2 +
                        ThreadInlineCodePlaceholderSlack
                ).toSp()
}

private val ThreadInlineCodePlaceholderSlack = 8.dp

@Composable
private fun ThreadInlineCodeChip(code: String) {
    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .background(SlockColor.BrandYellow.copy(alpha = 0.4f))
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(
                                    start = ThreadInlineChipHorizontalPadding,
                                    end = ThreadInlineChipHorizontalPadding,
                                    top = ThreadInlineChipVerticalPaddingTop,
                                    bottom = ThreadInlineChipVerticalPaddingBottom
                            ),
            contentAlignment = Alignment.CenterStart
    ) {
        Text(
                text = code,
                style = ThreadInlineCodeTextStyle,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun String.isPlainSingleLineMessage(): Boolean =
        isNotBlank() &&
                !normalizeThreadMarkdownBody().contains('\n') &&
                none { it == '`' || it == '*' || it == '[' || it == ']' || it == '>' }

@Composable
private fun ThreadAvatar(author: MemberProfile, onClick: (() -> Unit)? = null) {
    val openAvatarDescription = "Open ${author.displayName} profile"
    val avatarModifier =
            if (onClick != null) {
                Modifier
                        .semantics { contentDescription = openAvatarDescription }
                        .clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            }
    Box(modifier = avatarModifier.size(40.dp)) {
        SlockMemberAvatar(
                member = author,
                size = 40.dp,
                fontSize = SlockType.Button.fontSize,
                modifier = Modifier.fillMaxSize()
        )
    }
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
    return zoned.withZoneSameInstant(zoneId).toThreadTimestampLabel(LocalDate.now(zoneId))
}

private val ThreadCompactTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ThreadMonthDayTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val ThreadYearMonthDayTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun String.toThreadTimestampLabel(
        zoneId: ZoneId,
        today: LocalDate
): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return ""
    val zoned = trimmed.toThreadZonedDateTime(zoneId) ?: return trimmed
    return zoned.withZoneSameInstant(zoneId).toThreadTimestampLabel(today)
}

private fun ZonedDateTime.toThreadTimestampLabel(today: LocalDate): String =
        when {
            toLocalDate() == today -> ThreadCompactTimeFormatter.format(this)
            year == today.year -> ThreadMonthDayTimeFormatter.format(this)
            else -> ThreadYearMonthDayTimeFormatter.format(this)
        }

private val ThreadFlexibleDateTimeFormatters =
        listOf(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatterBuilder()
                        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                        .optionalStart()
                        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                        .optionalEnd()
                        .optionalStart()
                        .appendOffset("+HHMM", "Z")
                        .optionalEnd()
                        .toFormatter(),
                DateTimeFormatterBuilder()
                        .appendPattern("yyyy-MM-dd HH:mm:ss")
                        .optionalStart()
                        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                        .optionalEnd()
                        .optionalStart()
                        .appendOffset("+HHMM", "Z")
                        .optionalEnd()
                        .toFormatter()
        )

private fun String.toThreadZonedDateTime(zoneId: ZoneId): ZonedDateTime? {
    val trimmed = trim()
    val isoLikeTrimmed = trimmed.replace(' ', 'T')
    val normalizedOffsetTrimmed = trimmed.normalizeShortOffsetSuffix()
    val normalizedIsoLikeTrimmed = normalizedOffsetTrimmed.replace(' ', 'T')
    return trimmed.toEpochInstantOrNull()?.atZone(zoneId)
            ?: parseOrNull { Instant.parse(trimmed).atZone(zoneId) }
            ?: parseOrNull { OffsetDateTime.parse(trimmed).toZonedDateTime() }
                    ?: parseOrNull { ZonedDateTime.parse(trimmed) }
                    ?: parseOrNull { LocalDateTime.parse(trimmed).atZone(zoneId) }
                            ?: parseOrNull {
                                OffsetDateTime.parse(normalizedOffsetTrimmed).toZonedDateTime()
                            }
                                    ?: parseOrNull {
                                        ZonedDateTime.parse(normalizedOffsetTrimmed)
                                    }
                            ?: parseOrNull { Instant.parse(isoLikeTrimmed).atZone(zoneId) }
                                    ?: parseOrNull {
                                        OffsetDateTime.parse(isoLikeTrimmed).toZonedDateTime()
                                    }
                                            ?: parseOrNull { ZonedDateTime.parse(isoLikeTrimmed) }
                                                    ?: parseOrNull {
                                                        OffsetDateTime.parse(
                                                                normalizedIsoLikeTrimmed
                                                        ).toZonedDateTime()
                                                    }
                                                            ?: parseOrNull {
                                                                ZonedDateTime.parse(
                                                                        normalizedIsoLikeTrimmed
                                                                )
                                                            }
                                                    ?: parseOrNull {
                                                        LocalDateTime.parse(isoLikeTrimmed)
                                                                .atZone(zoneId)
                                                    }
                                                            ?: trimmed.toFlexibleThreadDateTime(
                                                                    zoneId
                                                            )
}

private val ThreadShortOffsetSuffixPattern = Regex("([+-]\\d{2})$")

private fun String.normalizeShortOffsetSuffix(): String =
        replace(ThreadShortOffsetSuffixPattern) { match -> "${match.value}:00" }

private fun String.toFlexibleThreadDateTime(zoneId: ZoneId): ZonedDateTime? {
    for (formatter in ThreadFlexibleDateTimeFormatters) {
        parseOrNull { return OffsetDateTime.parse(this, formatter).toZonedDateTime() }
        parseOrNull { return LocalDateTime.parse(this, formatter).atZone(zoneId) }
    }
    return null
}

private fun String.toEpochInstantOrNull(): Instant? {
    if (!all { it.isDigit() }) return null
    val epoch = toLongOrNull() ?: return null
    return when (length) {
        in 10..10 -> Instant.ofEpochSecond(epoch)
        in 13..13 -> Instant.ofEpochMilli(epoch)
        else -> null
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
        verticalPaddingTop: Dp = ThreadFooterChipVerticalPadding,
        verticalPaddingBottom: Dp = ThreadFooterChipVerticalPadding,
        maxLines: Int = 2,
        overflow: TextOverflow = TextOverflow.Clip,
        textStyle: TextStyle = ThreadInlineChipTextStyle
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
                    .padding(
                            start = ThreadInlineChipHorizontalPadding,
                            end = ThreadInlineChipHorizontalPadding,
                            top = verticalPaddingTop,
                            bottom = verticalPaddingBottom
                    )
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
                    modifier = Modifier.size(14.dp)
            )
        }
        Text(
                label,
                style = textStyle,
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
        channelSuggestions: List<ConversationSummary> = emptyList(),
        showAsTaskToggle: Boolean = true,
        onComposerTextToolbarShown: () -> Unit = {},
        onComposerTextToolbarHidden: () -> Unit = {},
        focusRequestNonce: Int = 0
) {
    var composerFieldValue by remember { mutableStateOf(TextFieldValue(composerState.value)) }
    var pendingPreviewAttachmentId by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreComposerToolbarAfterSelectAll by rememberSaveable { mutableStateOf(false) }
    var composerFieldFocused by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    if (composerFieldValue.text != composerState.value) {
        composerFieldValue =
                TextFieldValue(
                        text = composerState.value,
                        selection = TextRange(composerState.value.length)
                )
    }
    LaunchedEffect(focusRequestNonce) {
        if (focusRequestNonce <= 0 || !composerState.enabled) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        composerFieldValue =
                TextFieldValue(
                        text = composerState.value,
                        selection = TextRange(composerState.value.length)
                )
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    val horizontalPadding = threadHorizontalPadding()
    val bottomPadding = threadComposerBottomPadding()
    val context = LocalContext.current
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
    val composerValueProvider = { composerFieldValue }
    val handleComposerFieldValueChange = { nextValue: TextFieldValue ->
        composerFieldValue = nextValue
        onComposerChange(nextValue.text)
    }
    var composerFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var composerTextAreaBounds by remember { mutableStateOf<Rect?>(null) }
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
                }
            else -> emptyList()
        }
    }
    val filteredChannelSuggestions = remember(suggestionTrigger, channelSuggestions) {
        when (val trigger = suggestionTrigger) {
            is ComposerSuggestionTrigger.ChannelTrigger ->
                channelSuggestions.filter { channel ->
                    val q = trigger.query.lowercase()
                    channel.name.lowercase().contains(q)
                }
            else -> emptyList()
        }
    }
    val pendingPreviewAttachment =
            composerState.pendingAttachments.firstOrNull { attachment ->
                attachment.id == pendingPreviewAttachmentId && attachment.hasLocalImagePreview
            }
    val insertComposerNewline = {
        handleComposerFieldValueChange(composerFieldValue.insertTextAtSelection("\n"))
    }
    val textToolbar =
            rememberComposerTextToolbar(
                    onInsertNewline = insertComposerNewline,
                    onToolbarHidden = onComposerTextToolbarHidden
            )
    DisposableEffect(textToolbar) {
        onDispose {
            textToolbar.hide()
        }
    }
    LaunchedEffect(pendingPreviewAttachmentId, composerState.pendingAttachments) {
        if (pendingPreviewAttachmentId != null && pendingPreviewAttachment == null) {
            pendingPreviewAttachmentId = null
        }
    }
    var suggestionSelectionIndex by rememberSaveable { mutableStateOf(0) }
    val showMemberSuggestions = composerFieldFocused && filteredMemberSuggestions.isNotEmpty()
    val showChannelSuggestions = composerFieldFocused && filteredChannelSuggestions.isNotEmpty()
    val visibleSuggestionCount =
            when {
                showMemberSuggestions -> filteredMemberSuggestions.size
                showChannelSuggestions -> filteredChannelSuggestions.size
                else -> 0
            }
    LaunchedEffect(
            suggestionTrigger,
            showMemberSuggestions,
            showChannelSuggestions,
            filteredMemberSuggestions.size,
            filteredChannelSuggestions.size
    ) {
        suggestionSelectionIndex =
                when {
                    visibleSuggestionCount <= 0 -> 0
                    suggestionSelectionIndex >= visibleSuggestionCount -> visibleSuggestionCount - 1
                    else -> suggestionSelectionIndex
                }
    }
    fun applySelectedSuggestion(): Boolean {
        return when {
            showMemberSuggestions -> {
                val member = filteredMemberSuggestions.getOrNull(suggestionSelectionIndex) ?: return false
                val trigger = suggestionTrigger as? ComposerSuggestionTrigger.MemberTrigger ?: return false
                handleComposerFieldValueChange(
                        composerFieldValue.replaceTriggerWith(
                                trigger.triggerStart,
                                "@${member.handle} "
                        )
                )
                true
            }
            showChannelSuggestions -> {
                val channel = filteredChannelSuggestions.getOrNull(suggestionSelectionIndex) ?: return false
                val trigger = suggestionTrigger as? ComposerSuggestionTrigger.ChannelTrigger ?: return false
                handleComposerFieldValueChange(
                        composerFieldValue.replaceTriggerWith(
                                trigger.triggerStart,
                                "#${channel.name} "
                        )
                )
                true
            }
            else -> false
        }
    }
    BackHandler(enabled = showMemberSuggestions || showChannelSuggestions) {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val suggestionDropdownWidth = maxWidth - horizontalPadding * 2
            val suggestionPopupPositionProvider =
                    remember(composerFieldBounds, density, horizontalPadding) {
                        object : PopupPositionProvider {
                            override fun calculatePosition(
                                    anchorBounds: IntRect,
                                    windowSize: IntSize,
                                    layoutDirection: LayoutDirection,
                                    popupContentSize: IntSize
                            ): IntOffset {
                                val horizontalPaddingPx =
                                        with(density) { horizontalPadding.roundToPx() }
                                val fieldTopPx =
                                        composerFieldBounds?.top?.roundToInt()
                                                ?: popupContentSize.height
                                return IntOffset(
                                        x =
                                                horizontalPaddingPx.coerceIn(
                                                        0,
                                                        (windowSize.width - popupContentSize.width)
                                                                .coerceAtLeast(0)
                                                ),
                                        y = (fieldTopPx - popupContentSize.height).coerceAtLeast(0)
                                )
                            }
                        }
                    }
            if (showMemberSuggestions || showChannelSuggestions) {
                Popup(
                        popupPositionProvider = suggestionPopupPositionProvider,
                        properties =
                                PopupProperties(
                                        focusable = false,
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = false
                                )
                ) {
                    Box(
                            modifier =
                                    Modifier.width(suggestionDropdownWidth)
                                            .heightIn(max = 244.dp)
                                            .zIndex(1f)
                    ) {
                        if (showMemberSuggestions) {
                            ComposerMemberSuggestionDropdown(
                                    members = filteredMemberSuggestions,
                                    selectedIndex = suggestionSelectionIndex,
                                    onSelect = { member ->
                                        suggestionSelectionIndex =
                                                filteredMemberSuggestions.indexOf(member).coerceAtLeast(0)
                                        applySelectedSuggestion()
                                    }
                            )
                        } else {
                            ComposerChannelSuggestionDropdown(
                                    channels = filteredChannelSuggestions,
                                    selectedIndex = suggestionSelectionIndex,
                                    onSelect = { channel ->
                                        suggestionSelectionIndex =
                                                filteredChannelSuggestions.indexOf(channel).coerceAtLeast(0)
                                        applySelectedSuggestion()
                                    }
                            )
                        }
                    }
                }
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
                                .padding(
                                        start = horizontalPadding,
                                        top = composerTopPadding,
                                        end = horizontalPadding,
                                        bottom = bottomPadding
                                ),
                verticalArrangement = Arrangement.spacedBy(composerSectionSpacing)
        ) {
            val statusLabel = composerState.activityLabel
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
                        onRetryAttachment = onRetryAttachment,
                        onPreviewAttachment = { pendingPreviewAttachmentId = it }
                )
            }
            val showComposerToolbar = {
                onComposerTextToolbarShown()
                textToolbar.showComposerMenu(
                        rect =
                                composerTextAreaBounds
                                        ?: Rect(
                                                left = 0f,
                                                top = 0f,
                                                right = 1f,
                                                bottom = 1f
                        ),
                        fieldValueProvider = composerValueProvider,
                        onValueChange = handleComposerFieldValueChange,
                        context = context,
                        onSelectAllApplied = { restoreComposerToolbarAfterSelectAll = true }
                )
            }
            LaunchedEffect(
                    restoreComposerToolbarAfterSelectAll,
                    composerFieldValue.text,
                    composerFieldValue.selection,
                    composerTextAreaBounds
            ) {
                if (!restoreComposerToolbarAfterSelectAll) return@LaunchedEffect
                val fullSelection = TextRange(0, composerFieldValue.text.length)
                if (composerFieldValue.text.isEmpty() || composerFieldValue.selection != fullSelection) {
                    return@LaunchedEffect
                }
                withFrameNanos { }
                showComposerToolbar()
                restoreComposerToolbarAfterSelectAll = false
            }
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
                            Modifier.focusRequester(focusRequester)
                                    .testTag(THREAD_COMPOSER_FIELD_TAG)
                                    .threadComposerNewlineInput(
                                            composerValueProvider,
                                            handleComposerFieldValueChange,
                                            canSend = composerState.canSend,
                                            onSend = onSend,
                                            onSelectSuggestion = ::applySelectedSuggestion,
                                            onMoveSuggestionSelection = { direction ->
                                                if (visibleSuggestionCount > 0) {
                                                    suggestionSelectionIndex =
                                                            (suggestionSelectionIndex + direction)
                                                                    .coerceIn(
                                                                            0,
                                                                            visibleSuggestionCount - 1
                                                                    )
                                                }
                                            },
                                            consumeDirectionalKeys =
                                                    showMemberSuggestions || showChannelSuggestions
                                    )
                                    .onFocusChanged { focusState ->
                                        composerFieldFocused = focusState.isFocused
                                        if (!focusState.isFocused) {
                                            textToolbar.hide()
                                        }
                                    }
                                    .onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInWindow()
                                        composerFieldBounds =
                                                Rect(
                                                        left = position.x,
                                                        top = position.y,
                                                        right = position.x + coordinates.size.width,
                                                        bottom =
                                                                position.y +
                                                                        coordinates.size.height
                                                )
                                    },
                    textAreaModifier =
                            Modifier.testTag(THREAD_COMPOSER_TEXT_AREA_TAG)
                                    .threadComposerDoubleTapToolbar(showComposerToolbar)
                                    .onGloballyPositioned { coordinates ->
                                        val position = coordinates.positionInWindow()
                                        composerTextAreaBounds =
                                                Rect(
                                                        left = position.x,
                                                        top = position.y,
                                                        right = position.x + coordinates.size.width,
                                                        bottom =
                                                                position.y +
                                                                        coordinates.size.height
                                                )
                                    },
                    bottomContent = {
                        ThreadComposerInlineActions(
                                composerState = composerState,
                                enabled = enabled,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                showAsTaskToggle = showAsTaskToggle,
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
        }
    }
    if (pendingPreviewAttachment != null) {
        PendingAttachmentPreviewModal(
                attachment = pendingPreviewAttachment,
                onDismiss = { pendingPreviewAttachmentId = null }
        )
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
            if (i == 0 || textBeforeCursor[i - 1].isComposerSuggestionBoundary()) {
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

private fun Char.isComposerSuggestionBoundary(): Boolean = !isLetterOrDigit()

private fun TextFieldValue.replaceTriggerWith(triggerStart: Int, replacement: String): TextFieldValue {
    val end = selection.min.coerceIn(0, text.length)
    val nextText = text.replaceRange(triggerStart, end, replacement)
    val nextCursor = triggerStart + replacement.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

@Composable
private fun ComposerMemberSuggestionDropdown(
        members: List<MemberProfile>,
        selectedIndex: Int,
        onSelect: (MemberProfile) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .testTag(THREAD_COMPOSER_SUGGESTIONS_TAG)
                            .verticalScroll(scrollState)
    ) {
        Text(
                text = "IN THIS CHANNEL",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs)
        )
        members.forEachIndexed { index, member ->
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { onSelect(member) }
                                    .background(
                                            if (index == selectedIndex) {
                                                SlockColor.BrandYellow.copy(alpha = 0.4f)
                                            } else {
                                                SlockColor.SurfaceWhite
                                            }
                                    )
                                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                                    .padding(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Box(modifier = Modifier.size(32.dp)) {
                    SlockMemberAvatar(
                            member = member,
                            modifier = Modifier.fillMaxSize(),
                            size = 32.dp,
                            statusSize = 9.dp
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
        selectedIndex: Int,
        onSelect: (ConversationSummary) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .testTag(THREAD_COMPOSER_SUGGESTIONS_TAG)
                            .verticalScroll(scrollState)
    ) {
        channels.forEachIndexed { index, channel ->
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { onSelect(channel) }
                                    .background(
                                            if (index == selectedIndex) {
                                                SlockColor.BrandYellow.copy(alpha = 0.4f)
                                            } else {
                                                SlockColor.SurfaceWhite
                                            }
                                    )
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
                    val channelSubtitle = channel.subtitle
                    if (!channelSubtitle.isNullOrBlank()) {
                        Text(
                                text = channelSubtitle,
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
        showAsTaskToggle: Boolean,
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
            if (showAsTaskToggle) {
                SlockLabeledCheckbox(
                        label = "As Task",
                        checked = composerState.asTask,
                        onCheckedChange = onAsTaskChange,
                        enabled = enabled,
                        size = 18.dp,
                        labelSpacing = SlockSpacing.Xs,
                        labelStyle = SlockType.Button
                )
            }
            ThreadSendButton(
                    state = composerState.sendVisualState,
                    onClick = onSend
            )
        }
    }
}

private fun Modifier.threadComposerNewlineInput(
        valueProvider: () -> TextFieldValue,
        onValueChange: (TextFieldValue) -> Unit,
        canSend: Boolean = false,
        onSend: () -> Unit = {},
        onSelectSuggestion: () -> Boolean = { false },
        onMoveSuggestionSelection: (Int) -> Unit = {},
        consumeDirectionalKeys: Boolean = false
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) {
        return@onPreviewKeyEvent false
    }
    when {
        event.key == Key.Enter && event.isShiftPressed -> {
            onValueChange(valueProvider().insertTextAtSelection("\n"))
            true
        }
        event.key == Key.Enter && consumeDirectionalKeys && onSelectSuggestion() -> true
        event.key == Key.Enter && canSend -> {
            onSend()
            true
        }
        consumeDirectionalKeys && event.key == Key.DirectionUp -> {
            onMoveSuggestionSelection(-1)
            true
        }
        consumeDirectionalKeys && event.key == Key.DirectionDown -> {
            onMoveSuggestionSelection(1)
            true
        }
        else -> false
    }
}

private fun Modifier.threadComposerDoubleTapToolbar(onDoubleTap: () -> Unit): Modifier =
        pointerInput(onDoubleTap) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                val secondDown =
                        withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                            )
                        }
                if (secondDown != null) {
                    onDoubleTap()
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                }
            }
        }

private fun TextFieldValue.insertTextAtSelection(textToInsert: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val nextText = text.replaceRange(start, end, textToInsert)
    val nextCursor = start + textToInsert.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

private fun TextFieldValue.replaceSelection(textToInsert: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val nextText = text.replaceRange(start, end, textToInsert)
    val nextCursor = start + textToInsert.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

private fun TextFieldValue.selectedText(): String? {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    return text.substring(start, end).takeIf { it.isNotEmpty() }
}

private fun TextFieldValue.selectAll(): TextFieldValue = copy(selection = TextRange(0, text.length))

@Composable
private fun rememberComposerTextToolbar(
        onInsertNewline: () -> Unit,
        onToolbarHidden: () -> Unit = {}
): TextToolbar {
    val view = LocalView.current
    val insertNewlineState = rememberUpdatedState(onInsertNewline)
    val toolbarHiddenState = rememberUpdatedState(onToolbarHidden)
    val insertNewlineLabel = stringResource(R.string.thread_composer_insert_newline)
    return remember(view, insertNewlineLabel) {
        ComposerTextToolbar(
                view = view,
                insertNewlineLabel = insertNewlineLabel,
                onInsertNewline = { insertNewlineState.value() },
                onToolbarHidden = { toolbarHiddenState.value() }
        )
    }
}

private fun TextToolbar.showComposerMenu(
        rect: Rect,
        fieldValueProvider: () -> TextFieldValue,
        onValueChange: (TextFieldValue) -> Unit,
        context: Context,
        onSelectAllApplied: () -> Unit = {}
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val fieldValue = fieldValueProvider()
    showMenu(
            rect = rect,
            onCopyRequested =
                    fieldValue.selectedText()?.let {
                        {
                                                        fieldValueProvider().selectedText()?.let { selectedText ->
                                                                clipboard.setPrimaryClip(
                                                                                ClipData.newPlainText("Composer text", selectedText)
                                                                )
                                                        }
                        }
                    },
            onPasteRequested = {
                val pasteText =
                        clipboard.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                if (pasteText.isNotEmpty()) {
                                        onValueChange(fieldValueProvider().replaceSelection(pasteText))
                }
            },
            onCutRequested =
                                        fieldValue.selectedText()?.let {
                        {
                                                        val currentValue = fieldValueProvider()
                                                        currentValue.selectedText()?.let { selectedText ->
                                                                clipboard.setPrimaryClip(
                                                                                ClipData.newPlainText("Composer text", selectedText)
                                                                )
                                                                onValueChange(currentValue.replaceSelection(""))
                                                        }
                        }
                    },
            onSelectAllRequested = {
                                val selectedValue = fieldValueProvider().selectAll()
                onValueChange(selectedValue)
                onSelectAllApplied()
            }
    )
}

private class ComposerTextToolbar(
        private val view: View,
        private val insertNewlineLabel: String,
        private val onInsertNewline: () -> Unit,
        private val onToolbarHidden: () -> Unit
) : TextToolbar {
    private var actionMode: ActionMode? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    private fun markHidden() {
        if (status != TextToolbarStatus.Hidden) {
            status = TextToolbarStatus.Hidden
            onToolbarHidden()
        }
    }

    override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
    ) {
        actionMode?.finish()
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
                            markHidden()
                            actionMode = null
                        },
                        insertNewlineLabel = insertNewlineLabel,
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
        markHidden()
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
        private val insertNewlineLabel: String,
        private val toolbarRect: AndroidRect
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(0, MENU_INSERT_NEWLINE, 0, insertNewlineLabel)
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
                    mode.finish()
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
        onRetryAttachment: () -> Unit,
        onPreviewAttachment: (String) -> Unit
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
                    onRetry = onRetryAttachment,
                    onPreview = attachment.takeIf { it.hasLocalImagePreview }?.let {
                        { onPreviewAttachment(it.id) }
                    }
            )
        }
    }
}

@Composable
private fun PendingAttachmentChip(
        attachment: PendingAttachment,
        enabled: Boolean,
        onRemove: () -> Unit,
        onRetry: () -> Unit,
        onPreview: (() -> Unit)? = null
) {
    val borderColor =
            if (attachment.status == PendingAttachmentStatus.Failed) {
                SlockColor.AccentRed
            } else {
                SlockColor.BorderBlack
            }
    val previewContentDescription =
            if (onPreview != null) {
                "Preview ${attachment.fileName}"
            } else {
                null
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
                                )
                                .then(
                                        if (onPreview != null) {
                                            Modifier.clickable(
                                                    enabled = enabled,
                                                    role = Role.Button,
                                                    onClick = onPreview
                                            ).semantics {
                                                contentDescription =
                                                        previewContentDescription.orEmpty()
                                            }
                                        } else {
                                            Modifier
                                        }
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
    val imageBitmap = rememberPendingAttachmentImageBitmap(attachment.previewUri, attachment.isImage)
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

@Composable
private fun PendingAttachmentPreviewModal(
        attachment: PendingAttachment,
        onDismiss: () -> Unit
) {
    val title = attachment.fileName.ifBlank { "Image preview" }
    SlockModal(
            title = title,
            onDismiss = onDismiss
    ) {
        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            PendingAttachmentLocalImagePreview(
                    previewUri = attachment.previewUri,
                    name = title
            )
            Text(
                    text = attachment.displayMeta,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PendingAttachmentLocalImagePreview(previewUri: String?, name: String) {
    val imageBitmap = rememberPendingAttachmentImageBitmap(previewUri, isImage = true)
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
                    bitmap = imageBitmap,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier =
                            Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(SlockSpacing.Sm)
            )
        } else {
            Icon(
                    imageVector = SlockIcons.Image,
                    contentDescription = null,
                    tint = SlockColor.TextMutedGray,
                    modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun rememberPendingAttachmentImageBitmap(
        previewUri: String?,
        isImage: Boolean
): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    return remember(previewUri, isImage) {
        previewUri?.takeIf { isImage && it.isNotBlank() }?.let { uriString ->
            runCatching {
                        context.contentResolver.openInputStream(Uri.parse(uriString))
                                ?.use { input ->
                                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                                }
                    }
                    .getOrNull()
        }
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

private val PendingAttachment.hasLocalImagePreview: Boolean
    get() = isImage && !previewUri.isNullOrBlank()

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
                                                description = "Owner"
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
                                                description = "Markdown renderer",
                                                roleDescription = "agent"
                                        ),
                                timestampLabel = "Now",
                                body =
                                        """
                    **Markdown** body under the sender:

                    - lists stay left aligned
                    - `inline code #999` wraps with the text
                    - tags like #283 and #android-dev become clickable chips

                    this paragraph should stay separated after two newlines

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

@Preview(showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun ThreadSharedMarkdownSamplesPreview() {
        SlockTheme {
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
                        SlockMarkdownPreviewSamples.all.forEachIndexed { index, sample ->
                                ThreadMessageItem(
                                                message =
                                                                Message(
                                                                                id = MessageId("shared-markdown-preview-$index"),
                                                                                threadId = ThreadId("shared-markdown-preview-thread"),
                                                                                channelId = ChannelId("shared-markdown-preview-channel"),
                                                                                author =
                                                                                                MemberProfile(
                                                                                                                id = MemberId("shared-markdown-preview-author"),
	                                                                                                                displayName = sample.title,
	                                                                                                                handle = "markdown-preview",
	                                                                                                                kind = MemberKind.Agent,
	                                                                                                                description = sample.description,
                                                                                                                    roleDescription = "agent"
                                                                                                ),
                                                                                timestampLabel = "Preview",
                                                                                body = sample.body,
                                                                                replyCount = 0
                                                                )
                                )
                        }
                }
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
                                                    description = "Owner"
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
