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
import build.raft.app.data.BridgeSlockAvatarNativeCache
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedInviteAcceptResult
import build.raft.app.data.SharedInviteInfoResult
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
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
    data class Error(val message: String) : InviteScreenState
    data class Accepted(val serverName: String) : InviteScreenState
}

private data class PendingInviteAccept(
    val nonce: Int,
    val agreementId: String?,
)

@Composable
private fun InviteAcceptScreen() {
    val pager = LocalActivity.current.getPager() as Pager
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
        remember(sharedStores, avatarNativeCache) {
            sharedStores.createAuthDataSourceForCurrentEnvironment(avatarNativeCache)
        }
    var state by remember(token) { mutableStateOf<InviteScreenState>(InviteScreenState.Loading) }
    var accepting by remember(token) { mutableStateOf(false) }
    var agreementOverride by remember(token) { mutableStateOf<AuthInviteAgreement?>(null) }
    var pendingAccept by remember(token) { mutableStateOf<PendingInviteAccept?>(null) }

    LaunchedEffect(token) {
        val normalizedToken = normalizeSlockInviteTokenOrNull(token)
        if (normalizedToken == null) {
            state = InviteScreenState.Error("This invite link is invalid or has expired.")
            return@LaunchedEffect
        }
        when (val infoResult = authDataSource.loadInviteInfo(normalizedToken)) {
            is SharedInviteInfoResult.Failure -> state = InviteScreenState.Error(infoResult.message)
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
            state = InviteScreenState.Error("This invite link is invalid or has expired.")
            return@LaunchedEffect
        }
        accepting = true
        when (val accepted = authDataSource.acceptInvite(normalizedToken, request.agreementId)) {
            is SharedInviteAcceptResult.Success -> finishInvite(
                pager = pager,
                authDataSource = authDataSource,
                serverId = accepted.data.serverId,
                serverName = accepted.data.serverName,
                onAccepted = { state = InviteScreenState.Accepted(accepted.data.serverName) },
            )
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
                state = InviteScreenState.Error(accepted.message)
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
            InviteScreenState.Loading -> Text("Loading invite…", style = SlockType.PageTitle, color = SlockColor.TextBlack)
            is InviteScreenState.Error -> InviteErrorContent(current.message)
            is InviteScreenState.Accepted -> InviteAcceptedContent(current.serverName)
            is InviteScreenState.Ready -> {
                Text(
                    text = if (current.info.kind == "join_link") "Join this server" else "You’re invited",
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Use this link to join ${current.info.serverName} on Raft.",
                    style = SlockType.Body,
                    color = SlockColor.TextMutedGray,
                )
                if (!current.info.insideCountsHidden && (current.info.memberCount > 0 || current.info.agentCount > 0)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Meet ${current.info.memberCount} human(s) and ${current.info.agentCount} agent(s) inside.",
                        style = SlockType.Body,
                        color = SlockColor.TextMutedGray,
                    )
                }
                current.info.agreement?.let { agreement ->
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(agreement.title, style = SlockType.SectionLabel, color = SlockColor.TextBlack)
                        Text("Version ${agreement.version}", style = SlockType.Caption, color = SlockColor.TextMutedGray)
                        Text(agreement.bodyMarkdown, style = SlockType.Body, color = SlockColor.TextBlack)
                    }
                }
                if (current.info.humanSeatLimitReached) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        current.info.humanSeatLimitMessage
                            ?: "This server has no available human seats right now.",
                        style = SlockType.Body,
                        color = SlockColor.TextMutedGray,
                    )
                }
                Spacer(Modifier.height(20.dp))
                if (!current.authenticated) {
                    Text(
                        "Sign in or create an account to accept this invite.",
                        style = SlockType.Body,
                        color = SlockColor.TextMutedGray,
                    )
                    Spacer(Modifier.height(12.dp))
                    SlockBrutalActionButton(
                        label = "Sign In",
                        onClick = { pager.openInviteAuthPage(LOGIN_PAGE, token) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !accepting && !current.info.humanSeatLimitReached,
                        backgroundColor = SlockColor.AccentPink,
                    )
                    Spacer(Modifier.height(8.dp))
                    SlockBrutalActionButton(
                        label = "Create Account",
                        onClick = { pager.openInviteAuthPage(REGISTER_PAGE, token) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !accepting && !current.info.humanSeatLimitReached,
                    )
                } else if (current.info.agreement != null) {
                    SlockBrutalActionButton(
                        label = if (accepting) "Joining server…" else "Agree & Join",
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
private fun InviteErrorContent(message: String) {
    Text("Invite error", style = SlockType.PageTitle, color = SlockColor.TextBlack)
    Spacer(Modifier.height(12.dp))
    Text(message, style = SlockType.Body, color = SlockColor.TextMutedGray)
}

@Composable
private fun InviteAcceptedContent(serverName: String) {
    Text("You’re in", style = SlockType.PageTitle, color = SlockColor.TextBlack)
    Spacer(Modifier.height(12.dp))
    Text("You’ve joined $serverName.", style = SlockType.Body, color = SlockColor.TextMutedGray)
}

private suspend fun finishInvite(
    pager: Pager,
    authDataSource: SharedAuthDataSource,
    serverId: String,
    serverName: String,
    onAccepted: () -> Unit,
) {
    val restored = runCatchingPreservingCancellation { authDataSource.restoreSession() }.getOrNull()
    val selected =
        if (restored is SharedAuthResult.Success) {
            runCatchingPreservingCancellation { authDataSource.selectServer(serverId) }.getOrNull()
        } else {
            null
        }
    val session = (selected as? SharedAuthResult.Success)?.data
        ?: (restored as? SharedAuthResult.Success)?.data
    onAccepted()
    if (session != null) {
        pager.openPostAuthRoute(session)
    }
}

internal fun Pager.openInviteAuthPage(pageName: String, token: String) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        pageName,
        authPageData(
            pageName = pageName,
            replaceCurrent = true,
            pendingInviteToken = token,
        ),
    )
}

internal fun Pager.openInviteAcceptPage(token: String, replaceCurrent: Boolean = true) {
    val normalized = token.trim()
    if (normalized.isBlank()) return
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
