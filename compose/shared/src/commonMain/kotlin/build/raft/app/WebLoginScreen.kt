package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SlockWebLoginCompletion
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.launch

@Composable
internal fun WebLoginScreen(pager: Pager, bridge: BridgeModule, auth: SharedAuthDataSource) {
    val scope = rememberCoroutineScope()
    val platform = remember(bridge) {
        BridgeSettingsPlatformCapabilities(bridge, "", scope.kuiklyBridgeCallContext())
    }
    // Consent is explicit and local to this login screen; never infer it from a link visit.
    var consentAccepted by remember { mutableStateOf(false) }
    val linkNavigator = rememberSlockLinkNavigator(
        pager = pager,
        bridge = bridge,
        routeScope = SlockInAppBrowserRouteScope.Unscoped,
    )
    var starting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val completion by SlockWebLoginCompletion.status.collectAsState()
    LaunchedEffect(completion) {
        when (completion) {
            "app_shell", "choose_server" -> {
                when (val session = auth.currentSession()) {
                    is SharedAuthResult.Success -> pager.openPostAuthRoute(session.data)
                    else -> failed = true
                }
                SlockWebLoginCompletion.clear()
            }
            "login_error", "legal_acceptance", "retry" -> {
                failed = true
                SlockWebLoginCompletion.clear()
            }
        }
    }
    AuthScaffold(
        contentScrollable = true,
        scrollContentVerticalArrangement = Arrangement.Center,
    ) {
        AuthCenterMark(size = 52.dp)
        AuthFormColumn {
            AuthError(if (failed) stringResource(MR.strings.auth_web_login_failed) else null)
            AuthTermsAgreement(
                checked = consentAccepted,
                onCheckedChange = { consentAccepted = it },
                onOpenTerms = { linkNavigator.open(AUTH_TERMS_URL) },
                onOpenPrivacy = { linkNavigator.open(AUTH_PRIVACY_URL) },
            )
            AuthActionButton(
                text = stringResource(MR.strings.auth_web_login_button),
                tone = AuthButtonTone.Pink,
                enabled = consentAccepted && !starting,
                loading = starting,
                onClick = {
                    startWebLoginWithConsent(consentAccepted, starting) {
                        starting = true
                        failed = false
                        SlockWebLoginCompletion.clear()
                        scope.launch {
                            try {
                                val result = runCatchingPreservingCancellation { platform.startWebLogin() }.getOrNull()
                                failed = result !is DataResult.Content
                            } finally {
                                starting = false
                            }
                        }
                    }
                },
            )
            Text(
                text = stringResource(MR.strings.auth_web_login_hint),
                modifier = Modifier.fillMaxWidth(),
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                textAlign = TextAlign.Center,
            )
            AuthTextLink(
                text = stringResource(MR.strings.auth_web_login_private_entry),
                onClick = {
                    pager.openAuthPage(AUTH_ABOUT_PAGE, destination = AUTH_DESTINATION_API_ENVIRONMENT)
                },
            )
        }
    }
}
