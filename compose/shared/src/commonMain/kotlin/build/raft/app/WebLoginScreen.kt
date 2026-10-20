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
import build.raft.app.data.APP_LOGIN_STALE_CALLBACK
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.RaftWebLoginCompletion
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.MR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WebLoginScreen(
    pager: Pager,
    bridge: BridgeModule,
    auth: SharedAuthDataSource,
    onInteraction: () -> Unit = {},
    onOpenPrivateDeployment: () -> Unit = {},
    webUrl: String = "",
) {
    val scope = rememberCoroutineScope()
    val platform = remember(bridge) {
        BridgeSettingsPlatformCapabilities(bridge, "", scope.kuiklyBridgeCallContext())
    }
    // Consent is explicit and local to this login screen; never infer it from a link visit.
    var consentAccepted by remember { mutableStateOf(false) }
    val linkNavigator = rememberRaftLinkNavigator(
        pager = pager,
        bridge = bridge,
        routeScope = RaftInAppBrowserRouteScope.Unscoped,
    )
    var starting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var staleCallback by remember { mutableStateOf(false) }
    val completion by RaftWebLoginCompletion.status.collectAsState()
    LaunchedEffect(completion) {
        when (completion) {
            "app_shell", "choose_server" -> {
                // The exchange may have written the session through another store instance (no shell yet when
                // the callback arrived); read it again for a moment instead of failing on the first miss.
                when (val session = awaitStoredSession { auth.currentSession() }) {
                    is SharedAuthResult.Success -> pager.openPostAuthRoute(session.data)
                    else -> failed = true
                }
                RaftWebLoginCompletion.clear()
            }
            "login_error", "legal_acceptance", "retry" -> {
                failed = true
                RaftWebLoginCompletion.clear()
            }
            APP_LOGIN_STALE_CALLBACK -> {
                staleCallback = true
                RaftWebLoginCompletion.clear()
            }
        }
    }
    AuthScaffold(
        contentScrollable = true,
        scrollContentVerticalArrangement = Arrangement.Center,
    ) {
        AuthCenterMark(size = 52.dp)
        AuthFormColumn {
            AuthError(
                when {
                    failed -> stringResource(MR.strings.auth_web_login_failed)
                    staleCallback -> stringResource(MR.strings.auth_web_login_stale_callback)
                    else -> null
                }
            )
            AuthTermsAgreement(
                checked = consentAccepted,
                onCheckedChange = {
                    onInteraction()
                    consentAccepted = it
                },
                onOpenTerms = { linkNavigator.open(AUTH_TERMS_URL) },
                onOpenPrivacy = { linkNavigator.open(AUTH_PRIVACY_URL) },
            )
            AuthActionButton(
                text = stringResource(MR.strings.auth_web_login_button),
                tone = AuthButtonTone.Pink,
                enabled = consentAccepted && !starting,
                loading = starting,
                onClick = {
                    onInteraction()
                    startWebLoginWithConsent(consentAccepted, starting) {
                        starting = true
                        failed = false
                        staleCallback = false
                        RaftWebLoginCompletion.clear()
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
                // Follows the current environment: the official web app by default, the private web address once set.
                text = stringResource(MR.strings.auth_web_login_hint, webLoginHintHost(webUrl)),
                modifier = Modifier.fillMaxWidth(),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                textAlign = TextAlign.Center,
            )
            AuthTextLink(
                text = stringResource(MR.strings.auth_web_login_private_entry),
                onClick = onOpenPrivateDeployment,
            )
        }
    }
}

/** Host shown under the sign-in button; the official web app when the environment has no web address. */
internal fun webLoginHintHost(webUrl: String): String =
    webUrl.trim().substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { WEB_LOGIN_DEFAULT_HOST }

private const val WEB_LOGIN_DEFAULT_HOST = "app.raft.build"

/** Reads the stored session until it appears or [attempts] run out ([delayMs] apart). */
internal suspend fun <T> awaitStoredSession(
    attempts: Int = 10,
    delayMs: Long = 200L,
    read: suspend () -> SharedAuthResult<T>,
): SharedAuthResult<T> {
    var last = read()
    repeat(attempts - 1) {
        if (last is SharedAuthResult.Success) return last
        delay(delayMs)
        last = read()
    }
    return last
}
