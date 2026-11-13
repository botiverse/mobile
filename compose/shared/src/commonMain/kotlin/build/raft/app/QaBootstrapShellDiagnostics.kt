package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.StoredAuthSession
import kotlinx.coroutines.delay

/**
 * QA bootstrap (qa-acceptance) shell diagnostics and recovery. Everything here only runs for a
 * stored session whose `qaBootstrap` metadata is set, which a real sign-in never has.
 * Lines carry stage/code/state names only, never tokens or session contents.
 */
internal const val QA_BOOTSTRAP_LOG_TAG = "SlockQaBootstrap"
internal const val QA_BOOTSTRAP_WAITING_LOG_INTERVAL_MILLIS = 5_000L
internal const val QA_BOOTSTRAP_HOME_RETRY_LIMIT = 3
internal const val QA_BOOTSTRAP_HOME_RETRY_BASE_DELAY_MILLIS = 2_000L
private const val QA_BOOTSTRAP_RECOVERY_ATTEMPTS = 3
private const val QA_BOOTSTRAP_RECOVERY_BASE_DELAY_MILLIS = 1_000L

internal fun SharedHomeContentState.qaBootstrapHomeDiagnosticsName(): String =
    when (this) {
        is SharedHomeContentState.Loading -> "Loading"
        is SharedHomeContentState.Empty -> "Empty"
        is SharedHomeContentState.Content -> "Content"
        is SharedHomeContentState.Error -> "Error:${code.qaBootstrapDiagnosticsToken()}"
    }

/** Keeps a diagnostics value to a single safe token (codes only; no spaces or free text). */
internal fun String.qaBootstrapDiagnosticsToken(): String =
    replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(64).ifEmpty { "_" }

internal sealed interface QaBootstrapShellRecovery {
    data class Resumed(val session: StoredAuthSession) : QaBootstrapShellRecovery
    data class Failed(val code: String) : QaBootstrapShellRecovery
}

/**
 * Same server-checked path as QaSessionBootstrapActivity's recovery launch (no extras): confirm the
 * bootstrap is still consumed/ready, apply the status, and re-authorize the launch for this process.
 */
internal suspend fun recoverQaBootstrapLaunch(authDataSource: SharedAuthDataSource): QaBootstrapShellRecovery {
    var lastCode = "unknown"
    repeat(QA_BOOTSTRAP_RECOVERY_ATTEMPTS) { index ->
        if (index > 0) {
            val backoff = QA_BOOTSTRAP_RECOVERY_BASE_DELAY_MILLIS shl (index - 1)
            raftTraceToPlatformLog(
                QA_BOOTSTRAP_LOG_TAG,
                "QA_BOOTSTRAP_STAGE stage=shell_gate_recovery_retry attempt=${index + 1}/$QA_BOOTSTRAP_RECOVERY_ATTEMPTS " +
                    "code=$lastCode delayMs=$backoff"
            )
            delay(backoff)
        }
        val session = authDataSource.readStoredSession()
        if (session?.qaBootstrap == null) return QaBootstrapShellRecovery.Failed("session_missing")
        when (val status = authDataSource.statusQaBootstrap()) {
            is SharedAuthResult.Success -> {
                if (status.data.status != "consumed_pending_ready" && status.data.status != "ready") {
                    return QaBootstrapShellRecovery.Failed("terminal")
                }
                val applied = authDataSource.applyQaBootstrapStatus(status.data)
                    ?: return QaBootstrapShellRecovery.Failed("binding_changed")
                val authorized = authDataSource.authorizeQaBootstrapLaunch(applied)
                    ?.takeIf { it.qaBootstrap != null }
                    ?: return QaBootstrapShellRecovery.Failed("binding_changed")
                return QaBootstrapShellRecovery.Resumed(authorized)
            }
            SharedAuthResult.Unauthenticated -> return QaBootstrapShellRecovery.Failed("unauthenticated")
            is SharedAuthResult.Failure -> lastCode = status.code.name.lowercase()
        }
    }
    return QaBootstrapShellRecovery.Failed(lastCode)
}

private fun SharedHomeContentState.admitsQaBootstrapRouteReady(): Boolean =
    this is SharedHomeContentState.Content || this is SharedHomeContentState.Empty

/**
 * App-shell QA bootstrap effects (kept out of RaftAppShellReadyContent, which is at the JVM method
 * size limit). All are no-ops unless the gate session carries `qaBootstrap` metadata:
 * - emits QA_BOOTSTRAP_READY once the gate is Ready and Home is Content/Empty;
 * - while READY is still pending, logs `route_ready_waiting gate=.. home=..` on change and every 5 s
 *   (qa-acceptance run 43294979101: tasks hung here with no line at all);
 * - retries a Home load Error a bounded number of times with backoff.
 */
@Composable
internal fun QaBootstrapShellEffects(
    authGateState: SharedAuthGateState,
    homeContent: SharedHomeContentState,
    authDataSource: SharedAuthDataSource,
    onRefreshHome: () -> Unit,
) {
    val readySession = (authGateState as? SharedAuthGateState.Ready)?.session
    val gateSession = readySession ?: (authGateState as? SharedAuthGateState.BootstrapRecoveryRequired)?.session
    var readyTicketDigest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(readySession?.qaBootstrap?.ticketDigest, homeContent) {
        val digest = readySession?.qaBootstrap?.ticketDigest ?: return@LaunchedEffect
        if (homeContent.admitsQaBootstrapRouteReady() && raftPlatformCompleteQaBootstrapRouteReady(authDataSource)) {
            readyTicketDigest = digest
        }
    }

    val pendingDigest = gateSession?.qaBootstrap?.ticketDigest?.takeIf { it != readyTicketDigest }
    val gateName = pendingDigest?.let { authGateState.authGateDiagnosticsName() }
    val homeName = pendingDigest?.let { homeContent.qaBootstrapHomeDiagnosticsName() }
    val routeReadyAdmitted = readySession != null && homeContent.admitsQaBootstrapRouteReady()
    LaunchedEffect(pendingDigest, gateName, homeName, routeReadyAdmitted) {
        if (pendingDigest == null || routeReadyAdmitted) return@LaunchedEffect
        while (true) {
            raftTraceToPlatformLog(
                QA_BOOTSTRAP_LOG_TAG,
                "QA_BOOTSTRAP_STAGE stage=route_ready_waiting gate=$gateName home=$homeName"
            )
            delay(QA_BOOTSTRAP_WAITING_LOG_INTERVAL_MILLIS)
        }
    }

    val latestOnRefreshHome by rememberUpdatedState(onRefreshHome)
    var homeRetries by remember { mutableStateOf(0) }
    val homeError = (homeContent as? SharedHomeContentState.Error)?.takeIf { readySession?.qaBootstrap != null }
    LaunchedEffect(homeError, homeRetries) {
        val error = homeError ?: return@LaunchedEffect
        val code = error.code.qaBootstrapDiagnosticsToken()
        val attempt = homeRetries + 1
        if (attempt > QA_BOOTSTRAP_HOME_RETRY_LIMIT) {
            raftTraceToPlatformLog(QA_BOOTSTRAP_LOG_TAG, "QA_BOOTSTRAP_FAILURE stage=home_load code=$code retries=$homeRetries")
            return@LaunchedEffect
        }
        val backoffMillis = QA_BOOTSTRAP_HOME_RETRY_BASE_DELAY_MILLIS shl (attempt - 1)
        raftTraceToPlatformLog(
            QA_BOOTSTRAP_LOG_TAG,
            "QA_BOOTSTRAP_STAGE stage=home_load_retry attempt=$attempt/$QA_BOOTSTRAP_HOME_RETRY_LIMIT code=$code delayMs=$backoffMillis"
        )
        delay(backoffMillis)
        homeRetries = attempt
        latestOnRefreshHome()
    }
}

/**
 * BootstrapRecoveryRequired exit. Only a stored QA bootstrap session (never a real sign-in) lands
 * there: the process-only launch authorization was lost (process restart), and nothing dispatched
 * ResumeQaBootstrap, so the shell sat on a silent splash forever. Re-run the server-checked recovery
 * and resume; on failure log it and leave for sign-in instead of hanging.
 */
@Composable
internal fun QaBootstrapRecoveryEffect(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    dispatch: (SharedAuthGateIntent) -> Unit,
) {
    val latestDispatch by rememberUpdatedState(dispatch)
    LaunchedEffect(session.qaBootstrap?.ticketDigest) {
        raftTraceToPlatformLog(QA_BOOTSTRAP_LOG_TAG, "QA_BOOTSTRAP_FAILURE stage=shell_gate code=recovery_required")
        when (val outcome = recoverQaBootstrapLaunch(authDataSource)) {
            is QaBootstrapShellRecovery.Resumed -> {
                raftTraceToPlatformLog(QA_BOOTSTRAP_LOG_TAG, "QA_BOOTSTRAP_STAGE stage=shell_gate_recovered")
                latestDispatch(SharedAuthGateIntent.ResumeQaBootstrap(outcome.session))
            }
            is QaBootstrapShellRecovery.Failed -> {
                raftTraceToPlatformLog(QA_BOOTSTRAP_LOG_TAG, "QA_BOOTSTRAP_FAILURE stage=shell_gate_recovery code=${outcome.code}")
                latestDispatch(SharedAuthGateIntent.ShowSignIn)
            }
        }
    }
}
