package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import build.raft.app.base.BridgeModule
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.data.ExternalShareDraftRepository
import build.raft.app.data.ExternalShareDeliveryStatus
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.ExternalShareStore
import build.raft.app.data.KeyValueExternalShareDraftRepository
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationAuthorityBoundAttachmentUploader
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealExternalShareSender
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockExternalShareEnvelopeRepositoryProvider
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.slockSharedPreferenceStore
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

@Composable
internal fun ExternalShareLaunchHost(
    pageData: JSONObject,
    authStore: SlockAuthSessionStore,
    networkClient: SlockNetworkClient,
    authority: ExternalShareLaunchAuthority?,
    bridge: BridgeModule
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
    val conversationRepository =
        remember(authStore, networkClient) {
            RealConversationRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { bridge.log("external_share_conversation_repository $it") }
            )
        }
    val sender =
        remember(targetRepository, conversationRepository, authStore) {
            RealExternalShareSender(
                targetRepository = targetRepository,
                conversationRepository = conversationRepository,
                authorityBoundUploader = RealConversationAuthorityBoundAttachmentUploader(conversationRepository),
                sessionStore = authStore
            )
        }
    val store =
        remember(request.token, readyAuthority, draftRepository, targetRepository, sender, scope) {
            ExternalShareStore(
                envelopeRepository = SlockExternalShareEnvelopeRepositoryProvider.create(),
                draftRepository = draftRepository,
                targetRepository = targetRepository,
                sender = sender,
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
    ExternalShareSurface(state = state, onIntent = store::dispatch)
}

@Composable
private fun ExternalShareSurface(
    state: ExternalShareState,
    onIntent: (ExternalShareIntent) -> Unit
) {
    when (state) {
        ExternalShareState.Idle -> Unit
        is ExternalShareState.SelectingTargets -> ExternalShareTargetPickerSurface(state, onIntent)
        is ExternalShareState.Confirming -> ExternalShareConfirmationSurface(state, onIntent)
        is ExternalShareState.LoadingEnvelope ->
            ExternalShareStatusSurface(
                title = "Share to Raft",
                message = "Preparing shared content…",
                onClose = { onIntent(ExternalShareIntent.Cancel) }
            )
        is ExternalShareState.Sending ->
            ExternalShareStatusSurface(
                title = "Sending",
                message =
                    "${state.deliveries.count { it.status == ExternalShareDeliveryStatus.Sent }} of " +
                        "${state.deliveries.size} destinations complete",
                onClose = null
            )
        is ExternalShareState.Completed ->
            ExternalShareCompletedSurface(state, onIntent)
        is ExternalShareState.Error ->
            ExternalShareStatusSurface(
                title = "Could not open shared content",
                message = state.errorCode,
                onClose = { onIntent(ExternalShareIntent.Cancel) }
            )
    }
}

@Composable
private fun ExternalShareConfirmationSurface(
    state: ExternalShareState.Confirming,
    onIntent: (ExternalShareIntent) -> Unit
) {
    val ui = state.toExternalShareConfirmationUiState()
    SlockBackHandler(enabled = true, label = "external_share_confirmation") {
        onIntent(ExternalShareIntent.BackToTargets)
    }
    ExternalShareFullScreen {
        SlockTopPageHeader(
            title = "Share to Raft",
            subtitle = "Confirm destinations",
            iconName = SlockLucideIconName.Send,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = { onIntent(ExternalShareIntent.BackToTargets) },
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Content",
                    style = SlockType.Caption.copy(fontSize = 12.sp),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            items(ui.contentLines.size) { index ->
                Text(text = ui.contentLines[index], style = SlockType.Body, color = SlockColor.TextBlack)
            }
            item {
                Text(
                    text = "Destinations",
                    style = SlockType.Caption.copy(fontSize = 12.sp),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(ui.selectedTargets.size, key = { index -> ui.selectedTargets[index].rowKey }) { index ->
                val target = ui.selectedTargets[index]
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(target.title, style = SlockType.Body, color = SlockColor.TextBlack)
                    SlockModalActionButton(
                        label = "Remove",
                        onClick = { onIntent(ExternalShareIntent.RemoveTarget(target.stableKey)) }
                    )
                }
            }
            item {
                SlockBrutalTextField(
                    label = "Add a note",
                    value = ui.comment,
                    placeholder = "Optional message",
                    maxLines = 4,
                    fieldMinHeight = 88.dp,
                    onValueChange = { onIntent(ExternalShareIntent.ChangeComment(it)) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SlockModalFullWidthActionButton(
                label = "Send",
                icon = SlockLucideIconName.Send,
                onClick = { onIntent(ExternalShareIntent.Send) },
                enabled = ui.canSend
            )
        }
    }
}

@Composable
private fun ExternalShareCompletedSurface(
    state: ExternalShareState.Completed,
    onIntent: (ExternalShareIntent) -> Unit
) {
    val failed = state.deliveries.filter { it.status == ExternalShareDeliveryStatus.Failed }
    ExternalShareStatusSurface(
        title = if (failed.isEmpty()) "Shared" else "Some destinations failed",
        message =
            if (failed.isEmpty()) {
                "Sent to ${state.sentTargets.size} destinations"
            } else {
                failed.joinToString("\n") { "${it.target.title}: ${it.errorCode.orEmpty()}" }
            },
        onClose = { onIntent(ExternalShareIntent.Cancel) },
        retry = if (failed.isEmpty()) null else ({ onIntent(ExternalShareIntent.RetryFailed) })
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
