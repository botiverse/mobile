package build.raft.app

import build.raft.app.data.SharedAuthDataSource
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
