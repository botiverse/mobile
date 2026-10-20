package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import build.raft.app.base.BridgeModule
import build.raft.app.data.ExternalShareComposerHandoff
import build.raft.app.data.ExternalShareDraftRepository
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.ExternalShareStore
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.KeyValueExternalShareDraftRepository
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftExternalShareEnvelopeRepositoryProvider
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.MR
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
import com.tencent.tmm.kmmresource.compose.stringResource

@Composable
internal fun ExternalShareLaunchHost(
    pageData: JSONObject,
    authStore: RaftAuthSessionStore,
    networkClient: RaftNetworkClient,
    authority: ExternalShareLaunchAuthority?,
    bridge: BridgeModule,
    onOpenComposer: suspend (ExternalShareComposerHandoff) -> Boolean
) {
    val pageDataSignature = pageData.toString()
    val request = remember(pageDataSignature) { pageData.externalShareLaunchRequestOrNull() }
    if (request == null) {
        if (pageData.optString(ExternalShareLaunchIntentKey).trim() == ExternalShareLaunchIntent) {
            LaunchedEffect(pageDataSignature) {
                bridge.logWarn("external_share_launch_rejected reason=invalid_identity_or_payload")
            }
        }
        return
    }
    val readyAuthority = authority?.normalizedOrNull() ?: return
    val scope = rememberCoroutineScope()
    val draftRepository: ExternalShareDraftRepository =
        remember(readyAuthority.accountId) {
            KeyValueExternalShareDraftRepository(
                store = raftSharedPreferenceStore(),
                ownerAccountId = readyAuthority.accountId
            )
        }
    val memberRepository =
        remember(authStore, networkClient) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { bridge.log("external_share_member_repository $it") }
            )
        }
    val channelRepository =
        remember(authStore, networkClient) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { bridge.log("external_share_channel_repository $it") }
            )
        }
    val targetRepository =
        remember(authStore, networkClient, memberRepository, channelRepository) {
            RealForwardMessageTargetRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                channelRepository = channelRepository,
                memberRepository = memberRepository,
                warningLogger = { bridge.log("external_share_target_repository $it") }
            )
        }
    val store =
        remember(request.token, readyAuthority, draftRepository, targetRepository, scope) {
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
private fun ExternalShareSurface(
    state: ExternalShareState,
    onIntent: (ExternalShareIntent) -> Unit,
    onOpenComposer: suspend (ExternalShareComposerHandoff) -> Boolean
) {
    when (state) {
        ExternalShareState.Idle -> Unit
        is ExternalShareState.SelectingTargets -> ExternalShareTargetPickerSurface(state, onIntent)
        is ExternalShareState.LoadingEnvelope ->
            ExternalShareStatusSurface(
                title = stringResource(MR.strings.external_share_title),
                message = stringResource(MR.strings.external_share_preparing),
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
                title = stringResource(MR.strings.external_share_title),
                message = stringResource(MR.strings.external_share_opening_target, state.handoff.target.title),
                onClose = null
            )
        }
        is ExternalShareState.Error ->
            ExternalShareStatusSurface(
                title = stringResource(MR.strings.external_share_error_title),
                message = stringResource(MR.strings.external_share_error_detail),
                onClose = { onIntent(ExternalShareIntent.Cancel) }
            )
    }
}

internal fun ForwardMessageTarget.toExternalShareConversationIdentityOrNull(): ThreadLaunchConversationIdentity? {
    val destinationChannelId = channelId ?: return null
    return buildThreadLaunchConversationIdentity(
        kind = channelKind,
        channelId = destinationChannelId,
        channelType = channelType
    )
}

@Composable
private fun ExternalShareStatusSurface(
    title: String,
    message: String,
    onClose: (() -> Unit)?,
    retry: (() -> Unit)? = null
) {
    RaftBackHandler(enabled = true, label = "external_share_status") {
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
                    label = stringResource(MR.strings.external_share_retry_failed),
                    icon = RaftLucideIconName.RefreshCw,
                    onClick = it
                )
            }
            onClose?.let {
                RaftModalActionButton(
                    label = stringResource(MR.strings.common_close),
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
