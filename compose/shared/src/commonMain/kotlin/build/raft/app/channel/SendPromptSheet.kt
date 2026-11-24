package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.OpenedDirectMessageAuthority
import build.raft.app.data.RaftDirectMessageOpens
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.raftDialogSheetCentered
import build.raft.designsystem.components.rememberRaftStableWindowInsets
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.launch

internal const val SendPromptDialogHostRoute = "send_prompt"
internal const val SendPromptDialogTestTag = "send-prompt-dialog"
internal const val SendPromptNoAgentsTestTag = "send-prompt-no-agents"
internal const val SendPromptCloseButtonTestTag = "send-prompt-close"
internal const val SendPromptAgentTriggerTestTag = "send-prompt-agent"
internal const val SendPromptAgentDescriptionTestTag = "send-prompt-agent-description"
internal const val SendPromptPreviewTestTag = "send-prompt-preview"
internal const val SendPromptPreviewFailedTestTag = "send-prompt-preview-failed"
internal const val SendPromptFirstMessageTestTag = "send-prompt-first-message"
internal const val SendPromptRecentNoticeTestTag = "send-prompt-recent-notice"
internal const val SendPromptShowMoreTestTag = "send-prompt-show-more"
internal const val SendPromptComposerTestTag = "send-prompt-composer"
internal const val SendPromptErrorBannerTestTag = "send-prompt-error"

private const val SendPromptPreviewLimit = 5
private const val SendPromptPreviewClampLines = 3
private val SendPromptDesktopMaxWidth = 960.dp
private val SendPromptDesktopMaxHeight = 900.dp
private val SendPromptMessageAvatarSize = 35.dp
private val SendPromptMessageAvatarGap = 12.dp
private val SendPromptPickerMenuWidth = 300.dp

internal data class ActiveSendPromptRequest(
    val serverId: String,
    val prompt: String,
    val requestId: Long,
)

/**
 * State holder for the AppShell send-prompt sheet, kept out of `RaftAppShellReadyContent`
 * to minimize method bytecode growth.
 */
@Stable
internal class AppShellSendPromptHostState {
    private var nextRequestId: Long = 1L
    var activeRequest by mutableStateOf<ActiveSendPromptRequest?>(null)
        private set

    fun open(serverId: String?, prompt: String) {
        val cleanServerId = serverId?.trim()?.takeIf(String::isNotEmpty) ?: return
        val clamped = prompt.take(SEND_PROMPT_MAX_LENGTH)
        if (clamped.isBlank()) return
        activeRequest = ActiveSendPromptRequest(
            serverId = cleanServerId,
            prompt = clamped,
            requestId = nextRequestId++,
        )
    }

    fun dismiss() {
        activeRequest = null
    }
}

/**
 * AppShell host for [SendPromptDialog] (parity with botiverse/slock #16226 & #16396).
 *
 * Waits for the server's first agent list before mounting the dialog so default agent selection
 * sees the live roster, binds the dialog to the server it opened for, loads the last 5 messages
 * of the picked agent's DM read-only, opens/creates the DM and sends the prompt on commit, then
 * navigates into that DM.
 */
@Composable
internal fun AppShellSendPromptHost(
    hostState: AppShellSendPromptHostState,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    homeState: SharedHomeState,
    homeStore: SharedHomeStore,
    serverMembersStore: SharedServerMembersStore,
    channelRepository: ChannelRepository,
    conversationRepository: ConversationRepository,
    onOpenDirectMessage: (ThreadLaunchConversationIdentity) -> Unit,
    onLog: (String) -> Unit = {},
) {
    val request = hostState.activeRequest ?: return
    val currentServerId = readyServerId?.trim()?.takeIf(String::isNotEmpty)
    LaunchedEffect(request.requestId, currentServerId) {
        if (currentServerId != request.serverId) {
            hostState.dismiss()
        }
    }
    if (currentServerId != request.serverId) return

    val membersEntry by remember(serverMembersStore, request.serverId) {
        serverMembersStore.serverMembersEntryFlow(ServerId(request.serverId))
    }.collectAsState(
        remember(serverMembersStore, request.serverId) {
            serverMembersStore.currentServerMembersEntry(ServerId(request.serverId))
        }
    )

    LaunchedEffect(request.serverId, membersEntry.status, membersEntry.seededFromDurable) {
        if (
            membersEntry.status == SharedServerMembersLoadStatus.Idle ||
            (membersEntry.status == SharedServerMembersLoadStatus.Loaded && membersEntry.seededFromDurable)
        ) {
            serverMembersStore.refreshMembers(
                serverId = ServerId(request.serverId),
                priority = DataRequestPriority.Foreground,
                force = false,
                reason = "send_prompt",
            )
        }
    }

    if (shouldWaitForSendPromptAgents(membersEntry)) return

    val homeSnapshot: HomeSnapshot? =
        (homeState.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.takeIf { it.server.id.value == request.serverId }
            ?: homeStore.snapshotFor(ServerId(request.serverId))
    val directMessages: List<HomeDirectMessageRow> = homeSnapshot?.directMessages.orEmpty()
    val currentUserId = readySession?.user?.id?.trim()?.takeIf(String::isNotEmpty)
    val officialOnboardingAgentId =
        readySession?.servers
            ?.firstOrNull { it.id == request.serverId }
            ?.officialOnboardingAgentId
            ?: homeSnapshot?.server?.officialOnboardingAgentId
    val recipients = remember(membersEntry.members, currentUserId) {
        sendPromptRecipients(membersEntry.members, currentUserId)
    }
    val dmByAgent = remember(directMessages) { sendPromptDmChannelByAgent(directMessages) }
    val recentAgentIds = remember(directMessages) { recentSendPromptAgentIds(directMessages) }

    key(request.requestId) {
        SendPromptDialog(
            prompt = request.prompt,
            serverId = request.serverId,
            recipients = recipients,
            officialOnboardingAgentId = officialOnboardingAgentId,
            recentAgentIds = recentAgentIds,
            dmChannelByAgent = dmByAgent,
            loadPreview = { channelId ->
                loadSendPromptDmPreview(conversationRepository, channelId)
            },
            onSend = { agent, draft ->
                val openResult =
                    RaftDirectMessageOpens.shared.open(
                        channelRepository = channelRepository,
                        serverId = ServerId(request.serverId),
                        peerKind = MemberKind.Agent,
                        peerId = agent.id,
                        homeSnapshot = homeSnapshot,
                    ) ?: channelRepository.openAgentDirectMessage(agent.id)
                val dm = (openResult as? DataResult.Content)?.data?.conversation ?: return@SendPromptDialog false
                if (currentUserId != null) {
                    homeStore.supplementOpenedDirectMessage(
                        authority = OpenedDirectMessageAuthority(
                            serverId = ServerId(request.serverId),
                            accountId = MemberId(currentUserId),
                        ),
                        conversation = dm,
                    )
                }
                val sendResult =
                    conversationRepository.sendMessage(
                        channelId = dm.channelId,
                        body = draft,
                    )
                if (sendResult !is DataResult.Content) return@SendPromptDialog false
                hostState.dismiss()
                onOpenDirectMessage(dm.toThreadLaunchConversationIdentity())
                true
            },
            onOpened = { defaultKind ->
                onLog("send_prompt_opened default_agent=${defaultKind.wire}")
            },
            onClosed = { outcome, edited, changedAgent ->
                onLog("send_prompt_closed outcome=$outcome edited=$edited changed_agent=$changedAgent")
            },
            onClose = { hostState.dismiss() },
            requestKey = request.requestId.toString(),
        )
    }
}

private suspend fun loadSendPromptDmPreview(
    conversationRepository: ConversationRepository,
    channelId: ChannelId,
): SendPromptDmPreviewResult =
    when (
        val result =
            conversationRepository.loadLatestMessages(
                threadId = ThreadId(channelId.value),
                limit = SendPromptPreviewLimit,
            )
    ) {
        is DataResult.Content ->
            SendPromptDmPreviewResult.Loaded(
                result.data.messages.takeLast(SendPromptPreviewLimit).map { message ->
                    SendPromptPreviewMessage(
                        id = message.id.value,
                        senderName = message.author.displayName.ifBlank { message.author.handle },
                        content = message.body,
                        senderId = message.author.id.value,
                        senderKind = message.author.kind,
                        senderAvatarWireValue = message.author.avatar?.wireValue,
                        senderAvatarLabel = message.author.avatarLabel,
                        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(message.timestampLabel),
                        timestampLabel = message.timestampLabel,
                        isSystem = message.messageType == MessageType.System,
                    )
                }
            )
        else -> SendPromptDmPreviewResult.Failed
    }

/**
 * Send-to-agent dialog drawn as the DM with the agent (matches web `SendPromptDialog.tsx`,
 * botiverse/slock #16396): the agent's avatar, name and description form the head (which opens
 * the picker to switch agent, suggested first), above the last 5 DM messages read-only (grouped,
 * date dividers, long messages clamped to 3 lines with Show more) and the real chat composer
 * prefilled with the prompt (compact, plain text, no attachments, no As task).
 */
@Composable
internal fun SendPromptDialog(
    prompt: String,
    serverId: String,
    recipients: List<MemberProfile>,
    officialOnboardingAgentId: String?,
    recentAgentIds: List<String>,
    dmChannelByAgent: Map<String, ChannelId>,
    loadPreview: suspend (ChannelId) -> SendPromptDmPreviewResult,
    onSend: suspend (MemberProfile, String) -> Boolean,
    onClose: () -> Unit,
    onOpened: (SendPromptDefaultAgentKind) -> Unit = {},
    onClosed: (outcome: String, edited: Boolean, changedAgent: Boolean) -> Unit = { _, _, _ -> },
    requestKey: String = "default",
) {
    // The suggestion and picker order are locked once when the dialog opens: later DM activity does not move them.
    val suggested = remember {
        defaultSendPromptAgent(
            recipients = recipients,
            officialOnboardingAgentId = officialOnboardingAgentId,
            recentAgentIds = recentAgentIds,
        )
    }
    val orderedIds = remember {
        orderSendPromptAgents(
            recipients = recipients,
            suggestedId = suggested.agent?.id?.value,
            recentAgentIds = recentAgentIds,
        ).map { it.id.value }
    }
    val ordered = remember(recipients, orderedIds) {
        val byId = recipients.associateBy { it.id.value }
        val listed = orderedIds.mapNotNull(byId::get)
        val listedSet = orderedIds.toSet()
        listed + recipients.filter { it.id.value !in listedSet }
    }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    val pickedAgent = remember(ordered, selectedAgentId, suggested) {
        val targetId = selectedAgentId ?: suggested.agent?.id?.value
        ordered.firstOrNull { it.id.value == targetId } ?: ordered.firstOrNull()
    }
    var draftText by remember(requestKey, prompt) { mutableStateOf(prompt) }
    var sending by remember { mutableStateOf(false) }
    var sendFailed by remember { mutableStateOf(false) }
    var closedRecorded by remember { mutableStateOf(false) }

    val changedAgent = pickedAgent != null && pickedAgent.id.value != suggested.agent?.id?.value
    val scope = rememberCoroutineScope()

    LaunchedEffect(suggested) {
        onOpened(suggested.kind)
    }

    fun cancel() {
        if (sending) return
        if (!closedRecorded) {
            closedRecorded = true
            onClosed("cancelled", draftText != prompt, changedAgent)
        }
        onClose()
    }

    fun submit(rawText: String) {
        val target = pickedAgent ?: return
        val trimmed = rawText.trim()
        if (sending || trimmed.isEmpty()) return
        sending = true
        sendFailed = false
        scope.launch {
            val succeeded =
                runCatchingPreservingCancellation { onSend(target, trimmed) }
                    .getOrDefault(false)
            if (succeeded) {
                if (!closedRecorded) {
                    closedRecorded = true
                    onClosed("sent", trimmed != prompt.trim(), changedAgent)
                }
                onClose()
            } else {
                sendFailed = true
                sending = false
            }
        }
    }

    val colors = RaftTheme.colors
    val typography = RaftTheme.typography

    if (recipients.isEmpty() || pickedAgent == null) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.send_prompt_heading),
            onDismiss = ::cancel,
            presentation = RaftDialogPresentation.Centered,
            hostRoute = SendPromptDialogHostRoute,
            contentPadding = RaftAppSpacing.Md,
            contentSpacing = RaftAppSpacing.Md,
        ) {
            RaftText(
                text = stringResource(Res.string.send_prompt_no_agents),
                style = typography.body,
                color = colors.foreground,
                modifier = Modifier.testTag(SendPromptNoAgentsTestTag),
            )
        }
        return
    }

    val pickedDmChannelId = pickedAgent.id.value.let(dmChannelByAgent::get)
    var previewState by remember {
        mutableStateOf<Pair<ChannelId?, SendPromptDmPreviewResult>>(
            null to SendPromptDmPreviewResult.Loaded(emptyList())
        )
    }
    LaunchedEffect(pickedDmChannelId) {
        if (pickedDmChannelId == null) {
            previewState = null to SendPromptDmPreviewResult.Loaded(emptyList())
            return@LaunchedEffect
        }
        previewState = pickedDmChannelId to SendPromptDmPreviewResult.Loading
        val loaded =
            runCatchingPreservingCancellation { loadPreview(pickedDmChannelId) }
                .getOrElse { SendPromptDmPreviewResult.Failed }
        previewState = pickedDmChannelId to loaded
    }
    val previewResult: SendPromptDmPreviewResult =
        when {
            pickedDmChannelId == null -> SendPromptDmPreviewResult.Loaded(emptyList())
            previewState.first == pickedDmChannelId -> previewState.second
            else -> SendPromptDmPreviewResult.Loading
        }

    SendPromptDmWindow(
        pickedAgent = pickedAgent,
        orderedAgents = ordered,
        serverId = serverId,
        prompt = prompt,
        requestKey = requestKey,
        sending = sending,
        sendFailed = sendFailed,
        previewResult = previewResult,
        onPickAgent = { picked -> selectedAgentId = picked.id.value },
        onDraftChange = { draftText = it },
        onSubmit = ::submit,
        onCancel = ::cancel,
    )
}

@Composable
private fun SendPromptDmWindow(
    pickedAgent: MemberProfile,
    orderedAgents: List<MemberProfile>,
    serverId: String,
    prompt: String,
    requestKey: String,
    sending: Boolean,
    sendFailed: Boolean,
    previewResult: SendPromptDmPreviewResult,
    onPickAgent: (MemberProfile) -> Unit,
    onDraftChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = RaftTheme.colors
    val scrim = RaftTheme.scrim
    val previewLabel = stringResource(Res.string.send_prompt_preview_label)
    val agentName = sendPromptAgentLabel(pickedAgent)

    RaftWindowDialog(
        onDismissRequest = onCancel,
        properties = RaftWindowDialogProperties(dismissOnBackPress = !sending, drawScrim = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val centered = raftDialogSheetCentered(maxWidth)
            val frameContent: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize().background(colors.layerPopover)) {
                    SendPromptDialogHead(
                        centered = centered,
                        picked = pickedAgent,
                        orderedAgents = orderedAgents,
                        serverId = serverId,
                        enabled = !sending,
                        onPick = onPickAgent,
                        onClose = onCancel,
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        key(pickedAgent.id.value) {
                            SendPromptDmLookBack(
                                agent = pickedAgent,
                                serverId = serverId,
                                previewResult = previewResult,
                                previewLabel = previewLabel,
                            )
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(
                                    Brush.verticalGradient(listOf(Color.Transparent, colors.layerPopover))
                                )
                        )
                    }
                    SendPromptComposerFooter(
                        agentName = agentName,
                        prompt = prompt,
                        requestKey = requestKey,
                        sending = sending,
                        sendFailed = sendFailed,
                        onDraftChange = onDraftChange,
                        onSubmit = onSubmit,
                    )
                }
            }
            if (centered) {
                // Desktop / tablet: Create-task frame (960 wide, min(86vh, 900) tall), no outside-tap dismiss.
                val dialogHeight = minOf(maxHeight * 0.86f, SendPromptDesktopMaxHeight)
                Box(
                    modifier = Modifier.fillMaxSize().background(scrim).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .widthIn(max = SendPromptDesktopMaxWidth)
                                .fillMaxWidth()
                                .height(dialogHeight)
                                .raftLookOverlaySurface(radius = RaftAppRadius.Dialog)
                                .testTag(SendPromptDialogTestTag),
                    ) {
                        frameContent()
                    }
                }
            } else {
                // Phone: full-screen sheet matching the DM surface.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(colors.layerPopover)
                            .windowInsetsPadding(rememberRaftStableWindowInsets(WindowInsets.safeDrawing))
                            .testTag(SendPromptDialogTestTag),
                ) {
                    frameContent()
                }
            }
        }
    }
}

@Composable
private fun SendPromptDialogHead(
    centered: Boolean,
    picked: MemberProfile,
    orderedAgents: List<MemberProfile>,
    serverId: String,
    enabled: Boolean,
    onPick: (MemberProfile) -> Unit,
    onClose: () -> Unit,
) {
    val colors = RaftTheme.colors
    val closeLabel = stringResource(Res.string.common_close)
    Column(Modifier.fillMaxWidth().background(colors.layerPopover)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftDialogSheetDefaults.HeadHeight)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!centered) {
                RaftCloseButton(
                    onClick = onClose,
                    label = closeLabel,
                    enabled = enabled,
                    modifier = Modifier.testTag(SendPromptCloseButtonTestTag),
                )
            }
            SendPromptAgentHeadPicker(
                picked = picked,
                agents = orderedAgents,
                serverId = serverId,
                enabled = enabled,
                onPick = onPick,
                modifier = Modifier.weight(1f),
            )
            if (centered) {
                RaftCloseButton(
                    onClick = onClose,
                    label = closeLabel,
                    enabled = enabled,
                    modifier = Modifier.testTag(SendPromptCloseButtonTestTag),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}

@Composable
private fun SendPromptAgentHeadPicker(
    picked: MemberProfile,
    agents: List<MemberProfile>,
    serverId: String,
    enabled: Boolean,
    onPick: (MemberProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val triggerFocus = remember { FocusRequester() }
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val name = sendPromptAgentLabel(picked)
    val description = picked.description?.trim()?.takeIf(String::isNotEmpty)
    val switchLabel = stringResource(Res.string.send_prompt_switch_agent, name)

    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .focusRequester(triggerFocus)
                    .raftClickable(
                        enabled = enabled,
                        role = Role.Button,
                        shape = RoundedCornerShape(RaftRadius.Control),
                        onClick = { expanded = true },
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics { contentDescription = switchLabel }
                    .testTag(SendPromptAgentTriggerTestTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SendPromptAgentAvatar(agent = picked, serverId = serverId, size = 24.dp)
            RaftText(
                text = name,
                style = typography.title.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                RaftText(
                    text = description,
                    style = typography.callout,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag(SendPromptAgentDescriptionTestTag),
                )
            }
            RaftGlyphIcon(
                glyph = RaftGlyph.ChevronDown,
                size = 16.dp,
                color = colors.foregroundHint,
            )
        }
        RaftDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = SendPromptPickerMenuWidth,
            offset = 6.dp,
            returnFocusTo = triggerFocus,
        ) {
            agents.forEach { agent ->
                RaftMenuItem(
                    text = sendPromptAgentLabel(agent),
                    checked = agent.id == picked.id,
                    leadingIcon = {
                        SendPromptAgentAvatar(agent = agent, serverId = serverId, size = 18.dp)
                    },
                    onClick = {
                        expanded = false
                        onPick(agent)
                    },
                    modifier = Modifier.testTag("send-prompt-agent-${agent.id.value}"),
                )
            }
        }
    }
}

@Composable
private fun SendPromptAgentAvatar(
    agent: MemberProfile,
    serverId: String,
    size: Dp,
) {
    RaftAppAvatar(
        kind = RaftAvatarKind.Agent,
        avatarWireValue = agent.avatar?.wireValue,
        label = agent.avatarLabel,
        avatarSize = size,
        borderWidth = 0.dp,
        cacheSubject = agent.raftAvatarCacheSubject(),
        cacheServerId = serverId,
    )
}

@Composable
private fun SendPromptDmLookBack(
    agent: MemberProfile,
    serverId: String,
    previewResult: SendPromptDmPreviewResult,
    previewLabel: String,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scrollState = rememberScrollState()
    val timeContext = currentRaftTimeContext(null)

    LaunchedEffect(previewResult) {
        if (previewResult is SendPromptDmPreviewResult.Loaded && previewResult.messages.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = previewLabel }
                    .testTag(SendPromptPreviewTestTag),
            verticalArrangement = Arrangement.Bottom,
        ) {
            when (previewResult) {
                SendPromptDmPreviewResult.Loading -> {
                    RaftSkeleton(
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        variant = RaftSkeletonVariant.Line,
                    )
                }
                SendPromptDmPreviewResult.Failed -> {
                    RaftText(
                        text = stringResource(Res.string.send_prompt_preview_failed),
                        style = typography.callout,
                        color = colors.foreground,
                        modifier = Modifier.testTag(SendPromptPreviewFailedTestTag),
                    )
                }
                is SendPromptDmPreviewResult.Loaded -> {
                    if (previewResult.messages.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().testTag(SendPromptFirstMessageTestTag),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SendPromptAgentAvatar(agent = agent, serverId = serverId, size = 56.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                RaftText(
                                    text = sendPromptAgentLabel(agent),
                                    style = typography.headline.copy(fontWeight = FontWeight.Bold),
                                    color = colors.foreground,
                                )
                                RaftText(
                                    text = stringResource(Res.string.send_prompt_first_message),
                                    style = typography.callout,
                                    color = colors.foreground,
                                )
                            }
                        }
                    } else {
                        val rows = remember(previewResult.messages, timeContext) {
                            computeSendPromptPreviewGrouping(previewResult.messages, timeContext)
                        }
                        val noticeText =
                            if (previewResult.messages.size == 1) {
                                stringResource(Res.string.send_prompt_recent_messages_one)
                            } else {
                                stringResource(Res.string.send_prompt_recent_messages_other, previewResult.messages.size)
                            }
                        RaftText(
                            text = noticeText,
                            style = typography.callout,
                            color = colors.foregroundMuted,
                            modifier = Modifier.padding(bottom = 8.dp).testTag(SendPromptRecentNoticeTestTag),
                        )
                        rows.forEach { row ->
                            key(row.message.id) {
                                if (row.showDayDivider && row.day != null) {
                                    ThreadDateDivider(row.day.localizedLabel())
                                }
                                SendPromptPreviewMessageRow(
                                    row = row,
                                    serverId = serverId,
                                    timeContext = timeContext,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SendPromptPreviewMessageRow(
    row: SendPromptPreviewRowState,
    serverId: String,
    timeContext: RaftTimeContext?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val message = row.message
    val messageTimeFormat = rememberRaftMessageTimeFormat()
    val clockLabel =
        remember(message.timestampLabel, timeContext, messageTimeFormat) {
            if (message.timestampLabel.isBlank()) {
                null
            } else {
                raftMessageTimeLabel(message.timestampLabel, timeContext, messageTimeFormat)
            }
        }

    if (message.isSystem) {
        RaftText(
            text = message.content,
            style = typography.callout,
            color = colors.foregroundMuted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (row.showHeader) 10.dp else 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SendPromptMessageAvatarGap),
    ) {
        if (row.showHeader) {
            RaftAppAvatar(
                kind = raftAvatarKind(message.senderKind),
                avatarWireValue = message.senderAvatarWireValue,
                label = message.senderAvatarLabel,
                avatarSize = SendPromptMessageAvatarSize,
                borderWidth = 0.dp,
                cacheServerId = serverId,
            )
        } else {
            Spacer(Modifier.width(SendPromptMessageAvatarSize))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (row.showHeader) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RaftText(
                        text = message.senderName,
                        style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!clockLabel.isNullOrBlank()) {
                        RaftText(
                            text = clockLabel,
                            style = typography.monoMeta,
                            color = colors.foregroundHint,
                            maxLines = 1,
                        )
                    }
                }
            }
            SendPromptClampedMessageBody(messageId = message.id, content = message.content)
        }
    }
}

@Composable
private fun SendPromptClampedMessageBody(
    messageId: String,
    content: String,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    var expanded by remember(messageId) { mutableStateOf(false) }
    var visualOverflow by remember(messageId, content) { mutableStateOf(false) }
    val explicitLineOverflow = remember(content) { content.lineSequence().count() > SendPromptPreviewClampLines }
    val canToggle = expanded || visualOverflow || explicitLineOverflow

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            text = content,
            style = typography.body,
            color = colors.foreground,
            maxLines = if (expanded) Int.MAX_VALUE else SendPromptPreviewClampLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (!expanded && layout.hasVisualOverflow) {
                    visualOverflow = true
                }
            },
        )
        if (canToggle) {
            RaftTextLink(
                text = stringResource(if (expanded) Res.string.member_label_show_less else Res.string.member_label_show_more),
                onClick = { expanded = !expanded },
                variant = RaftTextLinkVariant.Muted,
                style = typography.callout,
                modifier = Modifier.testTag(SendPromptShowMoreTestTag),
            )
        }
    }
}

@Composable
private fun SendPromptComposerFooter(
    agentName: String,
    prompt: String,
    requestKey: String,
    sending: Boolean,
    sendFailed: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    Column(modifier = Modifier.fillMaxWidth().background(colors.layerPopover)) {
        if (sendFailed) {
            RaftBanner(
                description = stringResource(Res.string.send_prompt_failed),
                status = RaftBannerStatus.Warning,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(SendPromptErrorBannerTestTag),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(SendPromptComposerTestTag),
        ) {
            ThreadComposerContent(
                payload =
                    RaftThreadComposerPayload(
                        instanceId = "send-prompt:$requestKey",
                        seedText = prompt,
                        placeholder = stringResource(Res.string.thread_composer_placeholder_named, agentName),
                        enabled = !sending,
                        attachmentActionsEnabled = false,
                        originalImageOptionEnabled = false,
                        showAsTaskToggle = false,
                        composerMode = if (sending) "sending" else "idle",
                    ),
                onEvent = { action, value, _, _ ->
                    if (action == "change" && value != null) {
                        onDraftChange(value)
                    }
                },
                onSendRequest = { request ->
                    val text = request.value.text
                    if (sending || text.isBlank()) {
                        ThreadComposerSendAdmission.Rejected
                    } else {
                        onSubmit(text)
                        // Keep text in composer until the dialog closes so a failed send preserves edits.
                        ThreadComposerSendAdmission.Deferred
                    }
                },
                attachmentReorderEnabled = false,
                attachmentPreviewEnabled = false,
            )
        }
    }
}

internal fun sendPromptAgentLabel(agent: MemberProfile): String =
    agent.displayName.ifBlank { agent.handle }
