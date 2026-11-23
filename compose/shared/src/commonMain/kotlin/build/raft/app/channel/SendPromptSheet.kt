package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftFieldState
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.launch

internal const val SendPromptDialogHostRoute = "send_prompt"
internal const val SendPromptDialogTestTag = "send-prompt-dialog"
internal const val SendPromptNoAgentsTestTag = "send-prompt-no-agents"
internal const val SendPromptAgentTriggerTestTag = "send-prompt-agent"
internal const val SendPromptPreviewTestTag = "send-prompt-preview"
internal const val SendPromptMessageFieldTestTag = "send-prompt-message"
internal const val SendPromptSendButtonTestTag = "send-prompt-send"
internal const val SendPromptErrorBannerTestTag = "send-prompt-error"

private const val SendPromptPreviewLimit = 5

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
 * AppShell host for [SendPromptDialog] (1.29 parity with botiverse/slock #16226).
 *
 * Waits for the server's first agent list before mounting the sheet so default agent selection
 * sees the live roster, binds the sheet to the server it opened for, loads the last 5 messages
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
        )
    }
}

private suspend fun loadSendPromptDmPreview(
    conversationRepository: ConversationRepository,
    channelId: ChannelId,
): List<SendPromptPreviewMessage> =
    when (
        val result =
            conversationRepository.loadLatestMessages(
                threadId = ThreadId(channelId.value),
                limit = SendPromptPreviewLimit,
            )
    ) {
        is DataResult.Content ->
            result.data.messages.takeLast(SendPromptPreviewLimit).map { message ->
                SendPromptPreviewMessage(
                    id = message.id.value,
                    senderName = message.author.displayName.ifBlank { message.author.handle },
                    content = message.body,
                )
            }
        else -> emptyList()
    }

/**
 * Send-to-agent sheet / dialog (matches web `SendPromptDialog.tsx`, botiverse/slock #16226):
 * picks the suggested agent once on open, previews the last 5 messages of that agent's DM
 * read-only, lets the viewer edit the prefilled prompt, and sends it into the agent's DM.
 */
@Composable
internal fun SendPromptDialog(
    prompt: String,
    serverId: String,
    recipients: List<MemberProfile>,
    officialOnboardingAgentId: String?,
    recentAgentIds: List<String>,
    dmChannelByAgent: Map<String, ChannelId>,
    loadPreview: suspend (ChannelId) -> List<SendPromptPreviewMessage>,
    onSend: suspend (MemberProfile, String) -> Boolean,
    onClose: () -> Unit,
    onOpened: (SendPromptDefaultAgentKind) -> Unit = {},
    onClosed: (outcome: String, edited: Boolean, changedAgent: Boolean) -> Unit = { _, _, _ -> },
) {
    // Suggested agent is locked once when the sheet opens: later DM activity does not move the pick.
    val suggested = remember {
        defaultSendPromptAgent(
            recipients = recipients,
            officialOnboardingAgentId = officialOnboardingAgentId,
            recentAgentIds = recentAgentIds,
        )
    }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    val pickedAgent = remember(recipients, selectedAgentId, suggested) {
        val targetId = selectedAgentId ?: suggested.agent?.id?.value
        recipients.firstOrNull { it.id.value == targetId } ?: recipients.firstOrNull()
    }
    var messageText by remember { mutableStateOf(prompt) }
    var sending by remember { mutableStateOf(false) }
    var sendFailed by remember { mutableStateOf(false) }
    var closedRecorded by remember { mutableStateOf(false) }

    val edited = messageText != prompt
    val changedAgent = selectedAgentId != null && selectedAgentId != suggested.agent?.id?.value
    val draft = messageText.trim()
    val canSend = !sending && draft.isNotEmpty() && pickedAgent != null
    val scope = rememberCoroutineScope()

    LaunchedEffect(suggested) {
        onOpened(suggested.kind)
    }

    fun cancel() {
        if (sending) return
        if (!closedRecorded) {
            closedRecorded = true
            onClosed("cancelled", edited, changedAgent)
        }
        onClose()
    }

    fun submit() {
        val target = pickedAgent ?: return
        if (!canSend) return
        sending = true
        sendFailed = false
        scope.launch {
            val succeeded =
                runCatchingPreservingCancellation { onSend(target, draft) }
                    .getOrDefault(false)
            if (succeeded) {
                if (!closedRecorded) {
                    closedRecorded = true
                    onClosed("sent", edited, changedAgent)
                }
            } else {
                sendFailed = true
                sending = false
            }
        }
    }

    val colors = RaftTheme.colors
    val typography = RaftTheme.typography

    if (recipients.isEmpty()) {
        RaftUnifiedDialog(
            title = stringResource(Res.string.send_prompt_heading),
            onDismiss = ::cancel,
            presentation = RaftDialogPresentation.Sheet,
            hostRoute = SendPromptDialogHostRoute,
            contentPadding = RaftAppSpacing.Md,
            contentSpacing = RaftAppSpacing.Md,
        ) {
            RaftText(
                text = stringResource(Res.string.send_prompt_no_agents),
                style = typography.body,
                color = colors.foregroundMuted,
                modifier = Modifier.testTag(SendPromptNoAgentsTestTag),
            )
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = ::cancel,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
            }
        }
        return
    }

    val pickedDmChannelId = pickedAgent?.id?.value?.let(dmChannelByAgent::get)
    var previewState by remember { mutableStateOf<Pair<ChannelId?, List<SendPromptPreviewMessage>?>>(null to emptyList()) }
    LaunchedEffect(pickedDmChannelId) {
        if (pickedDmChannelId == null) {
            previewState = null to emptyList()
            return@LaunchedEffect
        }
        previewState = pickedDmChannelId to null
        val loaded =
            runCatchingPreservingCancellation { loadPreview(pickedDmChannelId) }
                .getOrDefault(emptyList())
        previewState = pickedDmChannelId to loaded
    }
    val previewMessages: List<SendPromptPreviewMessage>? =
        if (pickedDmChannelId == null) {
            emptyList()
        } else if (previewState.first == pickedDmChannelId) {
            previewState.second
        } else {
            null
        }

    val sendLabel = stringResource(if (sending) Res.string.invite_human_sending else Res.string.send_prompt_send)
    val previewLabel = stringResource(Res.string.send_prompt_preview_label)

    RaftUnifiedDialog(
        title = stringResource(Res.string.send_prompt_heading),
        onDismiss = ::cancel,
        dismissEnabled = !sending,
        dismissOnOutsideTap = false,
        keyboardAvoidanceEnabled = true,
        hostRoute = SendPromptDialogHostRoute,
        contentPadding = RaftAppSpacing.Md,
        contentSpacing = RaftAppSpacing.Md,
        presentation = RaftDialogPresentation.Sheet,
        commitLabel = sendLabel,
        onCommit = ::submit,
        commitEnabled = canSend,
        commitLoading = sending,
        modifier = Modifier.testTag(SendPromptDialogTestTag),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
        ) {
            RaftText(
                text = stringResource(Res.string.send_prompt_description),
                style = typography.callout,
                color = colors.foregroundMuted,
            )
            if (sendFailed) {
                RaftBanner(
                    description = stringResource(Res.string.send_prompt_failed),
                    status = RaftBannerStatus.Warning,
                    modifier = Modifier.testTag(SendPromptErrorBannerTestTag),
                )
            }
            RaftField(
                label = stringResource(Res.string.send_prompt_to),
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending,
            ) {
                SendPromptAgentPicker(
                    selected = pickedAgent,
                    recipients = recipients,
                    serverId = serverId,
                    enabled = !sending,
                    onPick = { picked -> selectedAgentId = picked.id.value },
                )
            }
            SendPromptDmPreviewBox(
                previewMessages = previewMessages,
                previewLabel = previewLabel,
            )
            RaftField(
                label = stringResource(Res.string.send_prompt_message_label),
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending,
            ) {
                RaftTextarea(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.fillMaxWidth().testTag(SendPromptMessageFieldTestTag),
                    enabled = !sending,
                    minHeight = 120.dp,
                    maxHeight = 200.dp,
                )
            }
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = ::cancel,
                enabled = !sending,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            RaftButton(
                text = sendLabel,
                onClick = ::submit,
                enabled = canSend,
                loading = sending,
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                modifier = Modifier.testTag(SendPromptSendButtonTestTag),
            )
        }
    }
}

@Composable
private fun SendPromptAgentPicker(
    selected: MemberProfile?,
    recipients: List<MemberProfile>,
    serverId: String,
    enabled: Boolean,
    onPick: (MemberProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val triggerFocus = remember { FocusRequester() }
    val fieldState = LocalRaftFieldState.current

    Box(modifier = Modifier.fillMaxWidth()) {
        RaftSelectTrigger(
            value = selected?.let(::sendPromptAgentLabel),
            onClick = { expanded = true },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(triggerFocus)
                    .onSizeChanged { triggerWidthPx = it.width }
                    .testTag(SendPromptAgentTriggerTestTag),
            enabled = enabled,
            label = fieldState.label,
            expanded = expanded,
            onCollapse = { expanded = false },
            leadingIcon =
                selected?.let { agent ->
                    {
                        SendPromptAgentAvatar(agent = agent, serverId = serverId)
                    }
                },
        )
        RaftDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = maxOf(220.dp, with(density) { triggerWidthPx.toDp() }),
            offset = 4.dp,
            returnFocusTo = triggerFocus,
        ) {
            recipients.forEach { agent ->
                val roleLabel = sendPromptAgentRoleLabel(agent.serverRole)
                RaftMenuItem(
                    text = sendPromptAgentLabel(agent),
                    description = roleLabel,
                    checked = agent.id == selected?.id,
                    leadingIcon = {
                        SendPromptAgentAvatar(agent = agent, serverId = serverId)
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
) {
    RaftAppAvatar(
        kind = RaftAvatarKind.Agent,
        avatarWireValue = agent.avatar?.wireValue,
        label = agent.avatarLabel,
        avatarSize = 16.dp,
        borderWidth = 0.dp,
        cacheSubject = agent.raftAvatarCacheSubject(),
        cacheServerId = serverId,
    )
}

@Composable
private fun SendPromptDmPreviewBox(
    previewMessages: List<SendPromptPreviewMessage>?,
    previewLabel: String,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Control)
    val scrollState = rememberScrollState()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 192.dp)
                .background(colors.fillMuted.copy(alpha = 0.4f), shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = previewLabel }
                .testTag(SendPromptPreviewTestTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            previewMessages == null -> {
                RaftSkeleton(
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                    variant = RaftSkeletonVariant.Line,
                )
            }
            previewMessages.isEmpty() -> {
                RaftText(
                    text = stringResource(Res.string.send_prompt_first_message),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                )
            }
            else -> {
                previewMessages.forEach { message ->
                    val line =
                        buildAnnotatedString {
                            if (message.senderName.isNotBlank()) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = colors.foreground)) {
                                    append(message.senderName)
                                    append(": ")
                                }
                            }
                            append(message.content)
                        }
                    RaftText(
                        text = line,
                        style = typography.callout,
                        color = colors.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun sendPromptAgentLabel(agent: MemberProfile): String =
    agent.displayName.ifBlank { agent.handle }

@Composable
private fun sendPromptAgentRoleLabel(serverRole: String?): String? =
    when (serverRole?.trim()?.lowercase()) {
        "owner" -> stringResource(Res.string.member_role_owner)
        "admin" -> stringResource(Res.string.member_role_admin)
        "member" -> stringResource(Res.string.member_role_member)
        else -> null
    }
