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
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockExternalShareEnvelopeRepositoryProvider
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.slockSharedPreferenceStore
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

@Composable
internal fun ExternalShareLaunchHost(
    pageData: JSONObject,
    authStore: SlockAuthSessionStore,
    networkClient: SlockNetworkClient,
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
                store = slockSharedPreferenceStore(),
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
                envelopeRepository = SlockExternalShareEnvelopeRepositoryProvider.create(),
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
                title = "Share to Raft",
                message = "Preparing shared content…",
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
                title = "Share to Raft",
                message = "Opening ${state.handoff.target.title}…",
                onClose = null
            )
        }
        is ExternalShareState.Error ->
            ExternalShareStatusSurface(
                title = "Could not open shared content",
                message = state.errorCode,
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
    SlockBackHandler(enabled = true, label = "external_share_status") {
        onClose?.invoke()
    }
    ExternalShareFullScreen {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = SlockType.PageTitle, color = SlockColor.TextBlack)
            Text(
                text = message,
                style = SlockType.Body,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(top = 12.dp)
            )
            retry?.let {
                SlockModalActionButton(
                    label = "Retry failed",
                    icon = SlockLucideIconName.RefreshCw,
                    onClick = it
                )
            }
            onClose?.let {
                SlockModalActionButton(
                    label = "Close",
                    icon = SlockLucideIconName.X,
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
                    .background(SlockColor.SurfaceWhite)
                    .slockNativeDispatchCapture()
        )
        Column(modifier = Modifier.fillMaxSize(), content = content)
    }
}
