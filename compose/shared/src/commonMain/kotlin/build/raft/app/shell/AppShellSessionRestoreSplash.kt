package build.raft.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftCachedImage
import build.raft.app.RaftColor
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.resources.*
import build.raft.app.thread.dispatch
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay

/**
 * How long the cold restore (no usable cached session) may show the plain splash before it offers
 * Try again and Sign out (web #15116 `AuthBootstrapStatus`). The web waits `MAX_AUTH_RESTORE_MS`
 * (30 s), its whole restore window; here one /auth/me already gives up at the 30 s request
 * timeout, so the way out comes earlier, while the request is still hanging.
 */
internal const val AppShellRestoreSlowAfterMillis: Long = 10_000L

internal const val AppShellRestoreSlowTestTag = "app-shell-restore-slow"
internal const val AppShellRestoreRetryTestTag = "app-shell-restore-retry"
internal const val AppShellRestoreSignOutTestTag = "app-shell-restore-sign-out"

/**
 * The cold-restore splash (the launch logo, centred where the native launch screen has it). Past
 * [slowAfterMillis] it says the restore is taking longer than usual and offers Sign out and Try
 * again (web #15116). Nothing changes before then and nothing signs out by itself. Try again hides
 * the actions and starts the wait over (the web reloads the page).
 */
@Composable
internal fun AppShellSessionRestoreSplash(
    signingOut: Boolean,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    slowAfterMillis: Long = AppShellRestoreSlowAfterMillis,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, slowAfterMillis) {
        slow = false
        delay(slowAfterMillis)
        slow = true
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (RaftAppThemeState.isDark) RaftColor.Canvas else RaftColor.SurfaceCream),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Equal weight above and below keeps the logo exactly where AppShellRestoreSplashContent has it.
        Spacer(Modifier.weight(1f))
        RaftCachedImage(
            source = "assets://raft/raft-launch-logo.png",
            contentDescription = null,
            modifier = Modifier.size(108.dp),
            contentScale = ContentScale.Fit,
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (slow || signingOut) {
                Column(
                    modifier = Modifier.padding(top = 24.dp).testTag(AppShellRestoreSlowTestTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RaftText(
                        text = stringResource(Res.string.auth_restore_slow),
                        style = RaftTheme.typography.body,
                        color = RaftTheme.colors.foregroundMuted,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftButton(
                            text = stringResource(Res.string.auth_restore_slow_sign_out),
                            onClick = onSignOut,
                            modifier = Modifier.testTag(AppShellRestoreSignOutTestTag),
                            variant = RaftButtonVariant.Default,
                            enabled = !signingOut,
                            loading = signingOut,
                        )
                        RaftButton(
                            text = stringResource(Res.string.auth_restore_slow_retry),
                            onClick = {
                                slow = false
                                attempt += 1
                                onRetry()
                            },
                            modifier = Modifier.testTag(AppShellRestoreRetryTestTag),
                            variant = RaftButtonVariant.Primary,
                            enabled = !signingOut,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sign out from the slow restore, the way the email-verification gate does it: revoke and clear the
 * stored session first, then leave realtime and move the gate to sign-in. The gate's SignedOut also
 * cancels the restore still in flight, so it cannot land afterwards and bring the session back
 * (what the web's reload after sign-out guards against).
 */
internal suspend fun appShellRestoreSignOut(
    logout: suspend () -> Unit,
    disconnectRealtime: () -> Unit,
    dispatch: (SharedAuthGateIntent) -> Unit,
) {
    logout()
    disconnectRealtime()
    dispatch(SharedAuthGateIntent.SignedOut)
}
