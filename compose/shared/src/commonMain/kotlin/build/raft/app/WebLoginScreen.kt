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
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.data.APP_LOGIN_BROWSER_CLOSED
import build.raft.app.data.APP_LOGIN_STALE_CALLBACK
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.RaftWebLoginCompletion
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WebLoginScreen(
    pager: Pager,
    bridge: BridgeModule,
    auth: SharedAuthDataSource,
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
    val attempt = remember(scope) { WebLoginAttempt(scope, logger = { raftLogInfo("SlockAppLogin", it) }) }
    var failed by remember { mutableStateOf(false) }
    var staleCallback by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, attempt) {
        // Coming back from the browser without a callback (cancelled, tab closed) re-enables the button.
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> attempt.onPaused()
                    Lifecycle.Event.ON_RESUME -> attempt.onResumed()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val completion by RaftWebLoginCompletion.status.collectAsState()
    LaunchedEffect(completion) {
        val status = completion
        attempt.onCompletion(status)
        when (status) {
            "app_shell", "choose_server" -> {
                // The exchange may have written the session through another store instance (no shell yet when
                // the callback arrived); read it again for a moment instead of failing on the first miss.
                when (val session = awaitStoredSession { auth.currentSession() }) {
                    is SharedAuthResult.Success -> pager.openPostAuthRoute(session.data)
                    else -> {
                        failed = true
                        attempt.onSessionMissing()
                    }
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
            APP_LOGIN_BROWSER_CLOSED -> RaftWebLoginCompletion.clear()
        }
    }
    WebLoginContent(
        consentAccepted = consentAccepted,
        pending = attempt.pending,
        error =
            when {
                failed -> stringResource(Res.string.auth_web_login_failed)
                staleCallback -> stringResource(Res.string.auth_web_login_stale_callback)
                else -> null
            },
        hintHost = webLoginHintHost(webUrl),
        onConsentChange = { consentAccepted = it },
        onOpenTerms = { linkNavigator.open(AUTH_TERMS_URL) },
        onOpenPrivacy = { linkNavigator.open(AUTH_PRIVACY_URL) },
        onSignIn = {
            startWebLoginWithConsent(consentAccepted, attempt.pending) {
                if (!attempt.tryBegin()) return@startWebLoginWithConsent
                failed = false
                staleCallback = false
                RaftWebLoginCompletion.clear()
                scope.launch {
                    val result = runCatchingPreservingCancellation { platform.startWebLogin() }.getOrNull()
                    val opened = result is DataResult.Content
                    failed = !opened
                    attempt.onBrowserOpened(opened)
                }
            }
        },
        onOpenPrivateDeployment = onOpenPrivateDeployment,
    )
}

/**
 * The sign-in page's form. While [pending] the button reads "Signing in…" and ignores taps, and
 * the self-hosted entry is disabled too, so a second request cannot start.
 */
@Composable
internal fun WebLoginContent(
    consentAccepted: Boolean,
    pending: Boolean,
    error: String?,
    hintHost: String,
    onConsentChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onSignIn: () -> Unit,
    onOpenPrivateDeployment: () -> Unit,
) {
    AuthScaffold(
        contentScrollable = true,
        scrollContentVerticalArrangement = Arrangement.Center,
    ) {
        AuthCenterMark(size = 52.dp)
        AuthFormColumn {
            AuthError(error)
            AuthTermsAgreement(
                checked = consentAccepted,
                onCheckedChange = onConsentChange,
                onOpenTerms = onOpenTerms,
                onOpenPrivacy = onOpenPrivacy,
            )
            AuthActionButton(
                text =
                    stringResource(
                        if (pending) Res.string.auth_web_login_button_pending else Res.string.auth_web_login_button
                    ),
                tone = AuthButtonTone.Pink,
                enabled = consentAccepted && !pending,
                loading = pending,
                // Busy, not unavailable: keep the pink, dimmed (react `disabled:opacity-50` while signing in).
                preserveDisabledVisual = pending,
                onClick = onSignIn,
            )
            Text(
                // Follows the current environment: the official web app by default, the private web address once set.
                text = stringResource(Res.string.auth_web_login_hint, hintHost),
                modifier = Modifier.fillMaxWidth(),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                textAlign = TextAlign.Center,
            )
            AuthTextLink(
                text = stringResource(Res.string.auth_web_login_private_entry),
                onClick = onOpenPrivateDeployment,
                enabled = !pending,
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
