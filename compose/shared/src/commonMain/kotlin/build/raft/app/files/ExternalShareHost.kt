package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ExternalShareComposerHandoff
import build.raft.app.data.ExternalShareDraftRepository
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.ExternalShareStore
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.KeyValueExternalShareDraftRepository
import build.raft.app.data.MemberRepository
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftExternalShareEnvelopeRepositoryProvider
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.compat.json.JSONObject

@Composable
internal fun ExternalShareLaunchHost(
    pageData: JSONObject,
    authStore: RaftAuthSessionStore,
    networkClient: RaftNetworkClient,
    authority: ExternalShareLaunchAuthority?,
    bridge: BridgeModule,
    onOpenComposer: suspend (ExternalShareComposerHandoff) -> Boolean,
    shellInstanceId: String? = null,
    channelRepository: ChannelRepository? = null,
    memberRepository: MemberRepository? = null,
    cachedHomeSnapshotProvider: (ServerId) -> HomeSnapshot? = { null },
    launchInbox: RaftExternalShareLaunchInbox = RaftExternalShareLaunchInbox.Process
) {
    val pageDataSignature = pageData.toString()
    val pageDataRequest = remember(pageDataSignature) { pageData.externalShareLaunchRequestOrNull() }
    val pendingInboxLaunch by launchInbox.pending.collectAsState()
    var activeRequest by remember { mutableStateOf<ExternalShareLaunchRequest?>(null) }
    var lastPageDataToken by remember { mutableStateOf<String?>(null) }
    val readyAuthority = authority?.normalizedOrNull()

    if (pageDataRequest == null && pageData.optString(ExternalShareLaunchIntentKey).trim() == ExternalShareLaunchIntent) {
        LaunchedEffect(pageDataSignature) {
            bridge.logWarn("external_share_launch_rejected reason=invalid_identity_or_payload")
        }
    }
    LaunchedEffect(pendingInboxLaunch, readyAuthority, shellInstanceId) {
        val pending = pendingInboxLaunch ?: return@LaunchedEffect
        if (readyAuthority == null) return@LaunchedEffect
        val taken = launchInbox.take(pending.id, shellInstanceId) ?: return@LaunchedEffect
        activeRequest = taken.request
    }
    LaunchedEffect(pageDataRequest) {
        if (pageDataRequest != null && pageDataRequest.token != lastPageDataToken) {
            lastPageDataToken = pageDataRequest.token
            activeRequest = pageDataRequest
        }
    }

    val latestCachedHomeSnapshotProvider by rememberUpdatedState(cachedHomeSnapshotProvider)
    val resolvedMemberRepository =
        memberRepository ?: remember(authStore, networkClient) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { bridge.log("external_share_member_repository $it") }
            )
        }
    val resolvedChannelRepository =
        channelRepository ?: remember(authStore, networkClient) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { bridge.log("external_share_channel_repository $it") }
            )
        }
    val targetRepository =
        remember(authStore, networkClient, resolvedMemberRepository, resolvedChannelRepository) {
            RealForwardMessageTargetRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                channelRepository = resolvedChannelRepository,
                memberRepository = resolvedMemberRepository,
                warningLogger = { bridge.log("external_share_target_repository $it") },
                cachedHomeSnapshotProvider = { serverId -> latestCachedHomeSnapshotProvider(serverId) }
            )
        }

    val request = activeRequest ?: return
    val activeAuthority = readyAuthority ?: return
    ExternalShareActiveSession(
        request = request,
        readyAuthority = activeAuthority,
        targetRepository = targetRepository,
        bridge = bridge,
        onOpenComposer = onOpenComposer
    )
}

@Composable
private fun ExternalShareActiveSession(
    request: ExternalShareLaunchRequest,
    readyAuthority: ExternalShareLaunchAuthority,
    targetRepository: RealForwardMessageTargetRepository,
    bridge: BridgeModule,
    onOpenComposer: suspend (ExternalShareComposerHandoff) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val draftRepository: ExternalShareDraftRepository =
        remember(readyAuthority.accountId) {
            KeyValueExternalShareDraftRepository(
                store = raftSharedPreferenceStore(),
                ownerAccountId = readyAuthority.accountId
            )
        }
    val store =
        remember(request.token, readyAuthority.accountId, draftRepository, targetRepository, scope) {
            ExternalShareStore(
                envelopeRepository = RaftExternalShareEnvelopeRepositoryProvider.create(),
                draftRepository = draftRepository,
                targetRepository = targetRepository,
                scope = scope,
                diagnosticsLogger = { bridge.log("external_share_store $it") }
            )
        }
    val coordinator = remember(store) { ExternalShareLaunchCoordinator(store::dispatch) }
    val state by store.state.collectAsState()

    LaunchedEffect(request, readyAuthority) {
        coordinator.accept(request)
        coordinator.reconcile(readyAuthority)
    }
    DisposableEffect(store) {
        onDispose { store.clear() }
    }
    ExternalShareSurface(
        state = state,
        onIntent = store::dispatch,
        onOpenComposer = onOpenComposer
    )
}

@Composable
internal fun ExternalShareSurface(
    state: ExternalShareState,
    onIntent: (ExternalShareIntent) -> Unit,
    onOpenComposer: suspend (ExternalShareComposerHandoff) -> Boolean
) {
    // External Share renders full-screen overlays inside the AppShell; cover the host-drawn
    // native iOS tab bar so it cannot sit on top of the picker's Continue footer.
    RaftNativeTabBarCover(active = state != ExternalShareState.Idle)
    when (state) {
        ExternalShareState.Idle -> Unit
        is ExternalShareState.SelectingTargets -> ExternalShareTargetPickerSurface(state, onIntent)
        is ExternalShareState.LoadingEnvelope ->
            ExternalShareStatusSurface(
                title = stringResource(Res.string.external_share_title),
                message = stringResource(Res.string.external_share_preparing),
                onClose = { onIntent(ExternalShareIntent.Cancel) }
            )
        is ExternalShareState.ReadyForComposer -> {
            LaunchedEffect(state.handoff.handoffId) {
                if (onOpenComposer(state.handoff)) {
                    onIntent(ExternalShareIntent.HandoffCompleted(state.handoff.handoffId))
                } else {
                    onIntent(ExternalShareIntent.HandoffFailed(state.handoff.handoffId))
                }
            }
            ExternalShareStatusSurface(
                title = stringResource(Res.string.external_share_title),
                message = stringResource(Res.string.external_share_opening_target, state.handoff.target.title),
                onClose = null
            )
        }
        is ExternalShareState.Error ->
            ExternalShareStatusSurface(
                title = stringResource(Res.string.external_share_error_title),
                message = stringResource(Res.string.external_share_error_detail),
                onClose = { onIntent(ExternalShareIntent.Cancel) }
            )
    }
}

internal fun ForwardMessageTarget.toExternalShareConversationIdentityOrNull(
    resolvedJointChannelId: String? = null,
): ThreadLaunchConversationIdentity? {
    val destinationChannelId = channelId ?: return null
    val effectiveJointChannelId =
        jointChannelId?.trim()?.takeIf { it.isNotBlank() }
            ?: resolvedJointChannelId?.trim()?.takeIf { it.isNotBlank() }
    return when (channelKind) {
        ConversationKind.Task -> taskConversationLaunchIdentity(destinationChannelId)
        ConversationKind.Thread -> {
            val resolvedParentChannelId = parentChannelId ?: return null
            val resolvedParentMessageId = parentMessageId ?: return null
            val parentKind = parseConversationKindOrNull(parentChannelType)
            val normalizedParentChannelKind =
                parentChannelType.normalizedThreadChannelKind()
                    ?.takeIf { it in ExternalShareValidThreadParentChannelKinds }
                    ?: parentKind.threadChannelKindWireValue()
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Thread,
                channelId = destinationChannelId,
                threadChannelId = destinationChannelId,
                parentChannelId = resolvedParentChannelId,
                parentMessageId = resolvedParentMessageId,
                parentSurfaceKind = parentKind.threadParentSurfaceKindOrNull(),
                parentChannelKind = normalizedParentChannelKind,
                jointChannelId = if (parentKind == ConversationKind.DirectMessage) null else effectiveJointChannelId,
            )
        }
        else ->
            buildThreadLaunchConversationIdentity(
                kind = channelKind,
                channelId = destinationChannelId,
                channelType = channelType,
                jointChannelId = if (channelKind == ConversationKind.DirectMessage) null else effectiveJointChannelId,
            )
    }
}

internal fun ForwardMessageTarget.toExternalShareOriginFallbackOrNull(): ThreadLaunchOriginFallback? {
    if (channelKind != ConversationKind.Thread) return null
    val resolvedParentMessageId = parentMessageId?.value?.takeIf { it.isNotBlank() } ?: return null
    return ThreadLaunchOriginFallback(
        parentChannelId = parentChannelId?.value?.takeIf { it.isNotBlank() },
        parentMessageId = resolvedParentMessageId,
        preview = parentMessagePreview?.takeIf { it.isNotBlank() },
        senderId = null,
        senderKind = null,
        expected = true
    )
}

private val ExternalShareValidThreadParentChannelKinds =
    setOf("channel", "public", "private", "joint", "dm")

@Composable
private fun ExternalShareStatusSurface(
    title: String,
    message: String,
    onClose: (() -> Unit)?,
    retry: (() -> Unit)? = null
) {
    RaftBackHandler(enabled = true, label = "external_share_status", blocksBackSwipe = false) {
        onClose?.invoke()
    }
    ExternalShareFullScreen {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = RaftType.PageTitle, color = RaftColor.TextBlack)
            Text(
                text = message,
                style = RaftType.Body,
                color = RaftColor.TextMutedGray,
                modifier = Modifier.padding(top = 12.dp)
            )
            retry?.let {
                RaftModalActionButton(
                    label = stringResource(Res.string.external_share_retry_failed),
                    icon = RaftLucideIconName.RefreshCw,
                    onClick = it
                )
            }
            onClose?.let {
                RaftModalActionButton(
                    label = stringResource(Res.string.common_close),
                    icon = RaftLucideIconName.X,
                    onClick = it
                )
            }
        }
    }
}

@Composable
private fun ExternalShareFullScreen(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(200f)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RaftColor.SurfaceWhite)
                    .raftNativeDispatchCapture()
        )
        Column(modifier = Modifier.fillMaxSize(), content = content)
    }
}
