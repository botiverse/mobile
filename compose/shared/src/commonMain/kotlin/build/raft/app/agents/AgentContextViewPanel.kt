package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AGENT_CONTEXT_VIEW_DEFAULT_RETENTION_DAYS
import build.raft.app.data.AgentContextViewAnswer
import build.raft.app.data.AgentContextViewBannerReason
import build.raft.app.data.AgentContextViewBodyState
import build.raft.app.data.AgentContextViewEmptyReason
import build.raft.app.data.AgentContextViewOutcome
import build.raft.app.data.AgentContextViewRecord
import build.raft.app.data.AgentContextViewRepository
import build.raft.app.data.AgentContextViewSourceKind
import build.raft.app.data.AgentContextViewStatus
import build.raft.app.data.AgentContextViewStatusEntry
import build.raft.app.data.AgentContextViewStatusState
import build.raft.app.data.AgentContextViewTooLargeCause
import build.raft.app.data.AgentContextViewVerification
import build.raft.app.data.MobileUiErrorCode
import build.raft.app.data.MobileUiErrorSurface
import build.raft.app.data.agentContextViewBodyState
import build.raft.app.iconName
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.thread.icon
import build.raft.designsystem.components.LocalRaftBarButtons
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftCloseButtonSize
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPanelNotice
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

internal const val AGENT_CONTEXT_PANEL_ROOT_TAG = "agent-context-panel-root"
internal const val AGENT_CONTEXT_PANEL_RETRY_TAG = "agent-context-panel-retry"
internal const val AGENT_CONTEXT_PANEL_COPY_RAW_TAG = "agent-context-panel-copy-raw"
internal const val AGENT_CONTEXT_PANEL_VERIFIED_TAG = "agent-context-panel-verified"
internal const val AGENT_CONTEXT_PANEL_EXCERPT_TAG = "agent-context-panel-excerpt"
internal const val AGENT_CONTEXT_PANEL_LOADING_TAG = "agent-context-panel-loading"

private val AgentContextSidePanelMaxWidth = 520.dp

internal data class AgentContextViewRequest(
    val agentId: String,
    val messageId: String,
    val agentName: String,
    val agentAvatarWireValue: String?,
    val messageBody: String,
    val createdAtLabel: String,
    val channelName: String?,
    val serverId: String?,
    val viewerUserId: String?,
)

/**
 * Full-screen (phone) or end-docked side panel (wide/desktop) overlay hosting Agent View Context
 * (`botiverse/slock` #12980, #14072, #14560).
 */
@Composable
internal fun AgentContextViewOverlay(
    request: AgentContextViewRequest,
    repository: AgentContextViewRepository,
    onDismiss: () -> Unit,
    timeContext: RaftTimeContext? = null,
) {
    val serverId = remember(request.serverId) { request.serverId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId) }
    var retryNonce by remember(request.agentId, request.messageId) { mutableIntStateOf(0) }
    var statusEntry by remember(request.agentId, request.messageId) {
        mutableStateOf<AgentContextViewStatusEntry?>(repository.statusStore.entryFor(request.agentId, serverId))
    }
    var answer by remember(request.agentId, request.messageId) { mutableStateOf<AgentContextViewAnswer?>(null) }
    var viewedBy by remember(request.agentId, request.messageId) { mutableStateOf<List<AgentContextViewRecord>?>(null) }
    var retrying by remember(request.agentId, request.messageId) { mutableStateOf(false) }

    LaunchedEffect(request.agentId, request.messageId, serverId, retryNonce) {
        val force = retryNonce > 0
        retrying = force
        if (force) {
            answer = null
        }
        val freshStatus = repository.statusStore.loadStatus(request.agentId, serverId, force = force)
        statusEntry = freshStatus
        if (freshStatus.gone) {
            retrying = false
            onDismiss()
            return@LaunchedEffect
        }
        val status = freshStatus.status
        if (status == null) {
            answer = null
            retrying = false
            return@LaunchedEffect
        }
        when (status.state) {
            AgentContextViewStatusState.Available -> {
                answer = AgentContextViewAnswer.Loading
                val result = repository.fetchContext(request.agentId, request.messageId, serverId)
                if (result == AgentContextViewAnswer.Gone) {
                    retrying = false
                    onDismiss()
                    return@LaunchedEffect
                }
                answer = result
                retrying = false
                val ownOutcome =
                    when (result) {
                        is AgentContextViewAnswer.Ok -> AgentContextViewOutcome.Ok
                        is AgentContextViewAnswer.Failed -> AgentContextViewOutcome.fromFailureReason(result.reason)
                        AgentContextViewAnswer.Loading, AgentContextViewAnswer.Gone -> null
                    }
                viewedBy =
                    repository.fetchViewedBy(
                        agentId = request.agentId,
                        messageId = request.messageId,
                        viewerUserId = request.viewerUserId,
                        ownOutcome = ownOutcome,
                        serverId = serverId,
                    )
            }
            is AgentContextViewStatusState.Unavailable -> {
                answer = null
                retrying = false
                viewedBy =
                    repository.fetchViewedBy(
                        agentId = request.agentId,
                        messageId = request.messageId,
                        viewerUserId = request.viewerUserId,
                        ownOutcome = null,
                        serverId = serverId,
                    )
            }
        }
    }

    val copyAction = rememberRaftCopyAction()
    val toastHost = LocalRaftToastHostState.current
    val rawCopiedLabel = stringResource(Res.string.agent_context_view_copied)
    val rawCopyFailedLabel = stringResource(Res.string.agent_context_view_copy_failed)
    val callIdCopiedLabel = stringResource(Res.string.agent_context_view_part_call_id_copied)

    RaftWindowDialog(
        onDismissRequest = onDismiss,
        properties =
            RaftWindowDialogProperties(
                dismissOnBackPress = true,
                drawScrim = false,
                platformTransition = false,
            ),
    ) {
        RaftBackHandler(enabled = true, label = "agent_context_view", onBack = onDismiss)
        val isWide = RaftTheme.scale == RaftScale.Wide
        if (isWide) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(RaftTheme.scrim)
                        .raftClickable(onClick = onDismiss)
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .widthIn(max = AgentContextSidePanelMaxWidth)
                            .fillMaxWidth()
                            .background(RaftTheme.colors.layerCanvas)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    AgentContextViewPanelSurface(
                        request = request,
                        statusEntry = statusEntry,
                        answer = answer,
                        viewedBy = viewedBy,
                        retrying = retrying,
                        isSidePanel = true,
                        timeContext = timeContext,
                        onClose = onDismiss,
                        onRetry = { retryNonce += 1 },
                        onCopyRaw = { rawJson ->
                            val copied = runCatching { copyAction(rawJson) }.isSuccess
                            toastHost?.show(
                                title = if (copied) rawCopiedLabel else rawCopyFailedLabel,
                                intent = if (copied) RaftToastIntent.Success else RaftToastIntent.Error,
                            )
                        },
                        onCopyText = { text -> copyAction(text) },
                        onCopyCallId = { callId ->
                            copyAction(callId)
                            toastHost?.show(title = callIdCopiedLabel)
                        },
                    )
                }
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(RaftTheme.colors.layerCanvas)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                AgentContextViewPanelSurface(
                    request = request,
                    statusEntry = statusEntry,
                    answer = answer,
                    viewedBy = viewedBy,
                    retrying = retrying,
                    isSidePanel = false,
                    timeContext = timeContext,
                    onClose = onDismiss,
                    onRetry = { retryNonce += 1 },
                    onCopyRaw = { rawJson ->
                        val copied = runCatching { copyAction(rawJson) }.isSuccess
                        toastHost?.show(
                            title = if (copied) rawCopiedLabel else rawCopyFailedLabel,
                            intent = if (copied) RaftToastIntent.Success else RaftToastIntent.Error,
                        )
                    },
                    onCopyText = { text -> copyAction(text) },
                    onCopyCallId = { callId ->
                        copyAction(callId)
                        toastHost?.show(title = callIdCopiedLabel)
                    },
                )
            }
        }
    }
}

/**
 * Pure presentation surface of Agent View Context — driven directly by [statusEntry], [answer],
 * and [viewedBy] so every state is directly verifiable in Robolectric Compose UI tests.
 */
@Composable
internal fun AgentContextViewPanelSurface(
    request: AgentContextViewRequest,
    statusEntry: AgentContextViewStatusEntry?,
    answer: AgentContextViewAnswer?,
    viewedBy: List<AgentContextViewRecord>?,
    retrying: Boolean,
    isSidePanel: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onCopyRaw: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onCopyCallId: (String) -> Unit,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    val colors = RaftTheme.colors
    val bodyState = remember(statusEntry, answer) { agentContextViewBodyState(statusEntry, answer) }
    val okAnswer = answer as? AgentContextViewAnswer.Ok
    val failedAnswer = answer as? AgentContextViewAnswer.Failed
    val loadedContext = okAnswer?.context
    val isVerified = loadedContext?.verification == AgentContextViewVerification.Verified
    var showVerifiedInfo by remember(request.messageId, loadedContext?.verification) { mutableStateOf(false) }

    CompositionLocalProvider(LocalRaftSurfaceColor provides colors.layerCanvas) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(colors.layerCanvas)
                    .testTag(AGENT_CONTEXT_PANEL_ROOT_TAG),
        ) {
            AgentContextPanelHeader(
                request = request,
                isSidePanel = isSidePanel,
                isVerified = isVerified,
                onToggleVerified = { showVerifiedInfo = !showVerifiedInfo },
                rawJson = okAnswer?.rawJson,
                onCopyRaw = onCopyRaw,
                onClose = onClose,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RaftStroke.Hairline)
                    .background(colors.lineSeam)
            )

            // Top notices (loading strip, verification notices, trigger-not-found notice).
            if (bodyState == AgentContextViewBodyState.Loading) {
                RaftPanelNotice(
                    message = LocalRaftStrings.current.loading,
                    status = RaftBannerStatus.Info,
                    pending = true,
                )
            }
            if (showVerifiedInfo && loadedContext != null && isVerified) {
                val verifiedCopy =
                    if (loadedContext.source.kind == AgentContextViewSourceKind.Captured) {
                        stringResource(Res.string.agent_context_view_verified_tooltip_captured)
                    } else {
                        stringResource(Res.string.agent_context_view_verified_tooltip)
                    }
                RaftPanelNotice(
                    message = verifiedCopy,
                    status = RaftBannerStatus.Success,
                    maxLines = 4,
                )
            }
            if (loadedContext != null) {
                if (loadedContext.trigger == null) {
                    RaftPanelNotice(
                        message = stringResource(Res.string.agent_context_view_trigger_not_found, request.agentName),
                        status = RaftBannerStatus.Info,
                        maxLines = 4,
                    )
                }
                when (loadedContext.verification) {
                    AgentContextViewVerification.CountMatched -> {
                        val runtimeName = observeAgentRuntimeDisplayName(loadedContext.runtime)
                        RaftPanelNotice(
                            message = stringResource(Res.string.agent_context_view_count_matched, runtimeName),
                            status = RaftBannerStatus.Info,
                            maxLines = 5,
                        )
                    }
                    null -> {
                        val suppressUnverified =
                            loadedContext.source.kind == AgentContextViewSourceKind.Captured && loadedContext.trigger == null
                        if (!suppressUnverified) {
                            val unverifiedCopy =
                                if (loadedContext.source.kind == AgentContextViewSourceKind.Captured) {
                                    stringResource(Res.string.agent_context_view_unverified_captured)
                                } else {
                                    stringResource(
                                        Res.string.agent_context_view_unverified_native,
                                        observeAgentRuntimeDisplayName(loadedContext.runtime),
                                    )
                                }
                            RaftPanelNotice(
                                message = unverifiedCopy,
                                status = RaftBannerStatus.Info,
                                maxLines = 4,
                            )
                        }
                    }
                    AgentContextViewVerification.Verified -> Unit
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgentContextMessageExcerptCard(request = request)

                when (bodyState) {
                    AgentContextViewBodyState.Loading -> {
                        AgentContextLoadingSkeleton()
                    }
                    AgentContextViewBodyState.Gone -> Unit
                    is AgentContextViewBodyState.Banner -> {
                        AgentContextFailureBanner(
                            state = bodyState,
                            status = statusEntry?.status,
                            failedAnswer = failedAnswer,
                            agentName = request.agentName,
                            retrying = retrying,
                            onRetry = onRetry,
                        )
                    }
                    is AgentContextViewBodyState.Empty -> {
                        AgentContextUnavailableEmptyState(reason = bodyState.reason)
                    }
                    is AgentContextViewBodyState.Content -> {
                        AgentContextViewBodySections(
                            context = bodyState.context,
                            status = statusEntry?.status,
                            agentName = request.agentName,
                            timeContext = timeContext,
                            onCopyText = onCopyText,
                            onCopyCallId = onCopyCallId,
                        )
                    }
                }

                if (bodyState != AgentContextViewBodyState.Loading && !viewedBy.isNullOrEmpty()) {
                    AgentContextViewedBySection(
                        records = viewedBy,
                        viewerUserId = request.viewerUserId,
                        serverId = request.serverId,
                        timeContext = timeContext,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentContextPanelHeader(
    request: AgentContextViewRequest,
    isSidePanel: Boolean,
    isVerified: Boolean,
    onToggleVerified: () -> Unit,
    rawJson: String?,
    onCopyRaw: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = RaftTheme.colors
    val controls = RaftTheme.controls
    CompositionLocalProvider(LocalRaftBarButtons provides true) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = controls.headHeight)
                    .padding(
                        start = if (isSidePanel) 16.dp else RaftBackButtonDefaults.EdgeInset,
                        end = RaftBackButtonDefaults.EdgeInset,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isSidePanel) {
                RaftBackButton(
                    onClick = onClose,
                    label = stringResource(Res.string.agent_context_view_back),
                )
            }
            RaftMemberAvatar(
                identity =
                    RaftMemberAvatarIdentity(
                        kind = MemberKind.Agent,
                        memberId = request.agentId,
                        serverId = request.serverId,
                    ),
                label = request.agentName,
                avatarSize = 28.dp,
                carriedAvatarWireValue = request.agentAvatarWireValue,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_title),
                        style = RaftTheme.typography.headline,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isVerified) {
                        RaftBadge(
                            text = stringResource(Res.string.agent_context_view_verified),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Success,
                            icon = { RaftSlotIcon(RaftLucideIconName.ShieldCheck) },
                            onClick = onToggleVerified,
                            modifier = Modifier.testTag(AGENT_CONTEXT_PANEL_VERIFIED_TAG),
                        )
                    }
                }
                RaftText(
                    text = stringResource(Res.string.agent_context_view_meta, request.agentName, request.createdAtLabel),
                    style = RaftTheme.typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!rawJson.isNullOrEmpty()) {
                RaftIconButton(
                    onClick = { onCopyRaw(rawJson) },
                    tooltip = stringResource(Res.string.agent_context_view_copy_raw),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    modifier = Modifier.testTag(AGENT_CONTEXT_PANEL_COPY_RAW_TAG),
                ) {
                    RaftSlotIcon(RaftLucideIconName.FileBraces)
                }
            }
            if (isSidePanel) {
                RaftCloseButton(
                    onClick = onClose,
                    label = stringResource(Res.string.agent_context_view_close),
                    size = RaftCloseButtonSize.Bar,
                )
            }
        }
    }
}

@Composable
private fun AgentContextMessageExcerptCard(
    request: AgentContextViewRequest,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(AGENT_CONTEXT_PANEL_EXCERPT_TAG)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftMemberAvatar(
            identity =
                RaftMemberAvatarIdentity(
                    kind = MemberKind.Agent,
                    memberId = request.agentId,
                    serverId = request.serverId,
                ),
            label = request.agentName,
            avatarSize = 28.dp,
            carriedAvatarWireValue = request.agentAvatarWireValue,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = request.agentName,
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                request.channelName?.takeIf { it.isNotBlank() }?.let { channel ->
                    RaftText(
                        text = "#$channel",
                        style = RaftTheme.typography.caption,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                RaftText(
                    text = request.createdAtLabel,
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                )
            }
            if (request.messageBody.isNotBlank()) {
                RaftText(
                    text = request.messageBody.trim(),
                    style = RaftTheme.typography.callout,
                    color = colors.foregroundMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgentContextLoadingSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AGENT_CONTEXT_PANEL_LOADING_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RaftSkeleton(Modifier.fillMaxWidth(0.55f), variant = RaftSkeletonVariant.Line)
        RaftSkeleton(Modifier.fillMaxWidth().height(92.dp), variant = RaftSkeletonVariant.Block)
        RaftSkeleton(Modifier.fillMaxWidth(0.7f), variant = RaftSkeletonVariant.Line)
        RaftSkeleton(Modifier.fillMaxWidth().height(120.dp), variant = RaftSkeletonVariant.Block)
    }
}

@Composable
private fun AgentContextFailureBanner(
    state: AgentContextViewBodyState.Banner,
    status: AgentContextViewStatus?,
    failedAnswer: AgentContextViewAnswer.Failed?,
    agentName: String,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    val computerName =
        failedAnswer?.computer?.name?.takeIf { it.isNotBlank() }
            ?: status?.computer?.name?.takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.agent_context_view_unknown)
    val ownerName =
        failedAnswer?.computerOwner?.name?.takeIf { it.isNotBlank() }
            ?: status?.computerOwner?.name?.takeIf { it.isNotBlank() }
    val retentionDays = status?.retentionDays ?: AGENT_CONTEXT_VIEW_DEFAULT_RETENTION_DAYS

    val description =
        when (state.reason) {
            AgentContextViewBannerReason.Offline ->
                stringResource(Res.string.agent_context_view_offline, computerName)
            AgentContextViewBannerReason.OldDaemon ->
                stringResource(Res.string.agent_context_view_old_daemon, computerName)
            AgentContextViewBannerReason.StatusFailed ->
                stringResource(Res.string.agent_context_view_status_failed)
            AgentContextViewBannerReason.AgentMoved ->
                stringResource(Res.string.agent_context_view_agent_moved, agentName)
            AgentContextViewBannerReason.NotFound ->
                stringResource(
                    if (retentionDays == 1) {
                        Res.string.agent_context_view_not_found_one
                    } else {
                        Res.string.agent_context_view_not_found_other
                    },
                    computerName,
                    retentionDays,
                )
            AgentContextViewBannerReason.Expired ->
                stringResource(Res.string.agent_context_view_expired)
            AgentContextViewBannerReason.TooLarge ->
                when (state.tooLargeCause) {
                    AgentContextViewTooLargeCause.SessionFile ->
                        stringResource(Res.string.agent_context_view_too_large_session_file)
                    AgentContextViewTooLargeCause.Request ->
                        stringResource(Res.string.agent_context_view_too_large_request)
                    AgentContextViewTooLargeCause.RestoreLimit ->
                        stringResource(Res.string.agent_context_view_too_large_restore_limit)
                    null -> stringResource(Res.string.agent_context_view_too_large)
                }
            AgentContextViewBannerReason.ReadFailed ->
                if (!ownerName.isNullOrBlank()) {
                    stringResource(Res.string.agent_context_view_read_failed, computerName, ownerName)
                } else {
                    stringResource(Res.string.agent_context_view_read_failed_no_owner, computerName)
                }
            AgentContextViewBannerReason.ContextMismatch ->
                stringResource(Res.string.agent_context_view_context_mismatch)
            AgentContextViewBannerReason.MediaRemoved ->
                stringResource(Res.string.agent_context_view_media_removed)
            AgentContextViewBannerReason.NewerSessionFormat ->
                stringResource(Res.string.agent_context_view_newer_session_format, computerName)
            AgentContextViewBannerReason.NotRecorded ->
                stringResource(Res.string.agent_context_view_not_recorded)
        }

    RaftBanner(
        description = AnnotatedString(description),
        status = RaftBannerStatus.Warning,
        size = RaftBannerSize.Md,
        action =
            if (state.retry) {
                {
                    RaftButton(
                        text = stringResource(Res.string.agent_context_view_retry),
                        onClick = onRetry,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        loading = retrying,
                        modifier = Modifier.testTag(AGENT_CONTEXT_PANEL_RETRY_TAG),
                    )
                }
            } else {
                null
            },
        content =
            if (!state.traceId.isNullOrBlank()) {
                {
                    RaftTraceIdNote(
                        traceId = state.traceId,
                        uiErrorSurface = MobileUiErrorSurface.ScreenError,
                        uiErrorRoute = "agent_context_view",
                        uiErrorCode = MobileUiErrorCode.Unknown,
                    )
                }
            } else {
                null
            },
    )
}

@Composable
private fun AgentContextUnavailableEmptyState(
    reason: AgentContextViewEmptyReason,
) {
    val (titleRes, iconName) =
        when (reason) {
            AgentContextViewEmptyReason.UnsupportedRuntime ->
                Res.string.agent_context_view_unsupported_runtime_title to RaftLucideIconName.Ban
            AgentContextViewEmptyReason.NoSession ->
                Res.string.agent_context_view_no_session_title to RaftLucideIconName.Clock
            AgentContextViewEmptyReason.NoComputer ->
                Res.string.agent_context_view_no_computer_title to RaftLucideIconName.MonitorX
        }
    RaftEmptyState(
        title = stringResource(titleRes),
        icon = {
            RaftLucideIcon(
                name = iconName,
                modifier = Modifier.size(36.dp),
                color = raftArgbHex(RaftTheme.colors.foregroundHint),
            )
        },
    )
}
