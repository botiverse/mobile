package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.data.AuthInviteAgreement
import build.raft.app.data.AuthInviteInfo
import build.raft.app.data.AuthAuthorityChangedMessage
import build.raft.app.data.AuthInviteEnvironmentUnavailableCode
import build.raft.app.data.BridgeSlockAvatarNativeCache
import build.raft.app.data.isInviteEnvironmentIntegrityFailure
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedInviteAcceptResult
import build.raft.app.data.SharedInviteInfoResult
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.asGateState
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager

const val INVITE_ACCEPT_PAGE = "compose_invite_accept"
internal const val INVITE_TOKEN_KEY = "slockInviteToken"

@Page(INVITE_ACCEPT_PAGE, supportInLocal = true)
internal class ComposeInviteAcceptPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { InviteAcceptScreen() }
    }
}

private sealed interface InviteScreenState {
    data object Loading : InviteScreenState
    data class Ready(val info: AuthInviteInfo, val authenticated: Boolean) : InviteScreenState
    data class Error(val errorCode: String) : InviteScreenState
    data class Accepted(val serverName: String) : InviteScreenState
}

private data class PendingInviteAccept(
    val nonce: Int,
    val agreementId: String?,
)

@Composable
private fun InviteAcceptScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    rememberKmmResourceLocaleSeed(pager)
    val pageDataSignature = pager.pageData.params.toString()
    val token = remember(pageDataSignature) {
        pager.pageData.params.optString(INVITE_TOKEN_KEY).trim()
    }
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeSlockAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext(),
            )
        }
    val authDataSource =
        remember(sharedStores, avatarNativeCache, token) {
            sharedStores.createAuthDataSourceForInvite(token, avatarNativeCache)
        }
    var state by remember(token) { mutableStateOf<InviteScreenState>(InviteScreenState.Loading) }
    var accepting by remember(token) { mutableStateOf(false) }
    var agreementOverride by remember(token) { mutableStateOf<AuthInviteAgreement?>(null) }
    var pendingAccept by remember(token) { mutableStateOf<PendingInviteAccept?>(null) }

    LaunchedEffect(token) {
        val normalizedToken = normalizeSlockInviteTokenOrNull(token)
        if (normalizedToken == null) {
            state = InviteScreenState.Error("invalid_token")
            return@LaunchedEffect
        }
        val existingSession =
            (authDataSource.currentSession() as? SharedAuthResult.Success)?.data
        if (existingSession != null && inviteAcceptGateState(existingSession) !is SharedAuthGateState.Ready) {
            pager.openPostAuthRoute(existingSession, normalizedToken)
            return@LaunchedEffect
        }
        when (val infoResult = authDataSource.loadInviteInfo(normalizedToken)) {
            is SharedInviteInfoResult.Failure -> {
                // A failed preview is not a completed invite flow. In particular, an
                // auth_authority_changed result is the terminal Invalidated witness and must
                // survive page destruction/recreation and a later duplicate delivery.
                if (!isInviteEnvironmentIntegrityFailure(infoResult.errorCode)) {
                    sharedStores.clearInviteResume(normalizedToken)
                }
                state = InviteScreenState.Error(infoResult.errorCode)
            }
            is SharedInviteInfoResult.Success -> {
                val info = infoResult.data.copy(agreement = agreementOverride ?: infoResult.data.agreement)
                val session = authDataSource.currentSession()
                val authenticated = session is SharedAuthResult.Success
                state = InviteScreenState.Ready(info = info, authenticated = authenticated)
                if (
                    authenticated &&
                    info.agreement == null &&
                    !info.humanSeatLimitReached &&
                    pendingAccept == null &&
                    agreementOverride == null
                ) {
                    pendingAccept = PendingInviteAccept(nonce = 1, agreementId = null)
                }
            }
        }
    }

    LaunchedEffect(pendingAccept) {
        val request = pendingAccept ?: return@LaunchedEffect
        val normalizedToken = normalizeSlockInviteTokenOrNull(token)
        if (normalizedToken == null) {
            pendingAccept = null
            accepting = false
            state = InviteScreenState.Error("invalid_token")
            return@LaunchedEffect
        }
        accepting = true
        when (val accepted = authDataSource.acceptInvite(normalizedToken, request.agreementId)) {
            is SharedInviteAcceptResult.Success -> {
                val completed = finishInvite(
                    pager = pager,
                    authDataSource = authDataSource,
                    serverId = accepted.data.serverId,
                    ownerAccountId = accepted.ownerAccountId,
                    onAccepted = { state = InviteScreenState.Accepted(accepted.data.serverName) },
                )
                if (!completed) {
                    accepting = false
                    // Accept may already have committed server-side. Keep the provider-owned
                    // witness so a page recreation cannot re-capture this token under a new
                    // environment and replay the invite/finalization flow.
                    state = InviteScreenState.Error(AuthAuthorityChangedMessage)
                } else {
                    sharedStores.clearInviteResume(normalizedToken)
                }
            }
            is SharedInviteAcceptResult.AgreementRequired -> {
                agreementOverride = accepted.agreement
                accepting = false
                val current = state as? InviteScreenState.Ready
                if (current != null) {
                    state = current.copy(info = current.info.copy(agreement = accepted.agreement), authenticated = true)
                }
            }
            is SharedInviteAcceptResult.Failure -> {
                accepting = false
                // Typed authority invalidation is terminal and must survive page recreation;
                // ordinary retryable/expired failures retain the previous cleanup behavior.
                if (!isInviteEnvironmentIntegrityFailure(accepted.errorCode)) {
                    sharedStores.clearInviteResume(normalizedToken)
                }
                state = InviteScreenState.Error(accepted.errorCode)
            }
            SharedInviteAcceptResult.Unauthenticated -> {
                accepting = false
                val current = state as? InviteScreenState.Ready
                if (current != null) state = current.copy(authenticated = false)
            }
        }
        pendingAccept = null
    }

    AuthScaffold(contentScrollable = true) {
        when (val current = state) {
            InviteScreenState.Loading ->
                Text(
                    stringResource(MR.strings.invite_state_loading),
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                )
            is InviteScreenState.Error -> InviteErrorContent(current.errorCode)
            is InviteScreenState.Accepted -> InviteAcceptedContent(current.serverName)
            is InviteScreenState.Ready -> {
                Text(
                    text =
                        if (current.info.kind == "join_link") {
                            stringResource(MR.strings.invite_title_join_server)
                        } else {
                            stringResource(MR.strings.invite_title_invited)
                        },
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(MR.strings.invite_description, current.info.serverName),
                    style = SlockType.Body,
                    color = SlockColor.TextMutedGray,
                )
                if (
                    !current.info.insideCountsHidden &&
                        (current.info.memberCount > 0 || current.info.agentCount > 0)
                ) {
                    Spacer(Modifier.height(8.dp))
                    InviteCountText(count = current.info.memberCount, human = true)
                    InviteCountText(count = current.info.agentCount, human = false)
                }
                current.info.agreement?.let { agreement ->
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(agreement.title, style = SlockType.SectionLabel, color = SlockColor.TextBlack)
                        Text(
                            stringResource(MR.strings.invite_agreement_version, agreement.version),
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray,
                        )
                        Text(
                            agreement.bodyMarkdown,
                            style = SlockType.Body,
                            color = SlockColor.TextBlack,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (current.info.humanSeatLimitReached) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        current.info.humanSeatLimitMessage
                            ?: stringResource(MR.strings.invite_error_seat_limit),
                        style = SlockType.Body,
                        color = SlockColor.TextMutedGray,
                    )
                }
                Spacer(Modifier.height(20.dp))
                if (!current.authenticated) {
                    Text(
                        stringResource(MR.strings.invite_sign_in_prompt),
                        style = SlockType.Body,
                        color = SlockColor.TextMutedGray,
                    )
                    Spacer(Modifier.height(12.dp))
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.invite_action_sign_in),
                        onClick = { pager.openInviteAuthPage(LOGIN_PAGE, token) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !accepting && !current.info.humanSeatLimitReached,
                        backgroundColor = SlockColor.AccentPink,
                    )
                    Spacer(Modifier.height(8.dp))
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.invite_action_create_account),
                        onClick = { pager.openInviteAuthPage(REGISTER_PAGE, token) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !accepting && !current.info.humanSeatLimitReached,
                    )
                } else if (current.info.agreement != null) {
                    SlockBrutalActionButton(
                        label =
                            if (accepting) {
                                stringResource(MR.strings.invite_action_joining)
                            } else {
                                stringResource(MR.strings.invite_action_agree_join)
                            },
                        onClick = {
                            if (accepting) return@SlockBrutalActionButton
                            accepting = true
                            pendingAccept = PendingInviteAccept(
                                nonce = (pendingAccept?.nonce ?: 0) + 1,
                                agreementId = current.info.agreement?.id,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !accepting && !current.info.humanSeatLimitReached,
                        backgroundColor = SlockColor.AccentPink,
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteErrorContent(errorCode: String) {
    Text(stringResource(MR.strings.invite_error_title), style = SlockType.PageTitle, color = SlockColor.TextBlack)
    Spacer(Modifier.height(12.dp))
    Text(inviteErrorMessage(errorCode), style = SlockType.Body, color = SlockColor.TextMutedGray)
}

@Composable
private fun InviteAcceptedContent(serverName: String) {
    Text(stringResource(MR.strings.invite_accepted_title), style = SlockType.PageTitle, color = SlockColor.TextBlack)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(MR.strings.invite_accepted_description, serverName),
        style = SlockType.Body,
        color = SlockColor.TextMutedGray,
    )
}

/** Pending invite acceptance is admitted only after the canonical auth prerequisite ladder. */
internal fun inviteAcceptGateState(session: StoredAuthSession): SharedAuthGateState = session.asGateState()

@Composable
private fun InviteCountText(count: Int, human: Boolean) {
    val text =
        when {
            human && count == 0 -> stringResource(MR.strings.invite_human_count_zero)
            human && count == 1 -> stringResource(MR.strings.invite_human_count_one, count)
            human -> stringResource(MR.strings.invite_human_count_many, count)
            !human && count == 0 -> stringResource(MR.strings.invite_agent_count_zero)
            !human && count == 1 -> stringResource(MR.strings.invite_agent_count_one, count)
            else -> stringResource(MR.strings.invite_agent_count_many, count)
        }
    Text(text, style = SlockType.Body, color = SlockColor.TextMutedGray)
}

@Composable
private fun inviteErrorMessage(errorCode: String): String =
    when (errorCode.trim().lowercase().replace('-', '_')) {
        "invalid_token", "ios_mobile_oauth_invalid_invite_token" ->
            stringResource(MR.strings.invite_error_invalid)
        "auth_required" -> stringResource(MR.strings.invite_error_auth_required)
        "already_member" -> stringResource(MR.strings.invite_error_already_member)
        "seat_limit" -> stringResource(MR.strings.invite_error_seat_limit)
        "network" -> stringResource(MR.strings.invite_error_network)
        "rate_limited" -> stringResource(MR.strings.invite_error_rate_limited)
        AuthAuthorityChangedMessage -> stringResource(MR.strings.invite_error_authority_changed)
        AuthInviteEnvironmentUnavailableCode -> stringResource(MR.strings.invite_error_environment)
        else -> stringResource(MR.strings.invite_error_generic)
    }

private suspend fun finishInvite(
    pager: Pager,
    authDataSource: SharedAuthDataSource,
    serverId: String,
    ownerAccountId: String,
    onAccepted: () -> Unit,
): Boolean {
    authDataSource.logDiagnostics(
        "auth_invite_accept_write_observed serverId=$serverId ownerAccountId=$ownerAccountId"
    )
    val restored = runCatchingPreservingCancellation { authDataSource.restoreSession() }.getOrNull()
    val restoredSession = (restored as? SharedAuthResult.Success)?.data
    if (restoredSession == null) {
        authDataSource.logDiagnostics(
            "auth_invite_accept_completion_reconcile_result serverId=$serverId " +
                "ownerAccountId=$ownerAccountId result=restore_unavailable"
        )
        return false
    }
    if (ownerAccountId.isBlank() || restoredSession.user.id != ownerAccountId) {
        authDataSource.logCriticalDiagnostics(
            "auth_invite_accept_completion_reconcile_result serverId=$serverId " +
                "ownerAccountId=$ownerAccountId result=owner_changed"
        )
        return false
    }

    // Selection is a CAS against the current account/token generation. Whether it succeeds or
    // fails because the server is not in the cached list, read the latest session before routing;
    // never route with the pre-CAS snapshot after a concurrent A→B/logout transition.
    runCatchingPreservingCancellation {
        authDataSource.selectInviteServer(serverId, ownerAccountId)
    }
    val currentSession =
        (runCatchingPreservingCancellation { authDataSource.currentSession() }.getOrNull()
            as? SharedAuthResult.Success)?.data
            ?: return false
    if (currentSession.user.id != ownerAccountId) {
        authDataSource.logCriticalDiagnostics(
            "auth_invite_accept_completion_reconcile_result serverId=$serverId " +
                "ownerAccountId=$ownerAccountId result=owner_changed_after_select"
        )
        return false
    }
    if (!authDataSource.inviteEnvironmentIsCurrent()) {
        authDataSource.logCriticalDiagnostics(
            "auth_invite_accept_completion_reconcile_result serverId=$serverId " +
                "ownerAccountId=$ownerAccountId result=environment_changed"
        )
        return false
    }

    onAccepted()
    pager.openPostAuthRoute(currentSession)
    return true
}

internal fun Pager.openInviteAuthPage(pageName: String, token: String) {
    val normalizedToken = normalizeSlockInviteTokenOrNull(token) ?: return
    val capture =
        SlockSharedStoreRegistry.get(Utils.bridgeModule(pagerId)).captureInviteResume(normalizedToken)
    if (capture is build.raft.app.data.SharedInviteResumeCaptureResult.Invalidated) return
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        pageName,
        authPageData(
            pageName = pageName,
            replaceCurrent = true,
            pendingInviteToken = normalizedToken,
        ),
    )
}

internal fun Pager.openInviteAcceptPage(token: String, replaceCurrent: Boolean = true) {
    val normalized = normalizeSlockInviteTokenOrNull(token) ?: return
    val capture =
        SlockSharedStoreRegistry.get(Utils.bridgeModule(pagerId)).captureInviteResume(normalized)
    if (capture is build.raft.app.data.SharedInviteResumeCaptureResult.Invalidated) return
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        INVITE_ACCEPT_PAGE,
        inviteAcceptPageData(normalized, replaceCurrent),
    )
}

internal fun inviteAcceptPageData(token: String, replaceCurrent: Boolean = true): com.tencent.kuikly.core.nvi.serialization.json.JSONObject =
    com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
        put("pageName", INVITE_ACCEPT_PAGE)
        put(INVITE_TOKEN_KEY, token.trim())
        if (replaceCurrent) put("slockReplaceCurrentPage", true)
    }
