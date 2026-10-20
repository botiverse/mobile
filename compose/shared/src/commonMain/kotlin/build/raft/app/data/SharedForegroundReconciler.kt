package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val FOREGROUND_RECONCILE_COALESCE_MS = 10_000L

/**
 * Shared foreground reconciliation per mobile-foreground-reconciliation-spec
 * (2026-06-29): any foreground return with an authenticated current server
 * schedules a background member/status refresh, coalesced per server so
 * AppShell, Thread, and Members surfaces returning together do not issue
 * duplicate roster calls.
 *
 * Route-scoped effects (Thread refresh, visible-room force rejoin) stay at the
 * surface that owns the route identity; this object only owns the server-wide
 * current-state refresh and the coalescing window.
 */
class SharedForegroundReconciler internal constructor(
    private val authStore: RaftAuthSessionStore,
    private val refreshMembers: suspend (ServerId) -> Unit,
    private val scope: CoroutineScope,
    private val coalesceWindowMs: Long = FOREGROUND_RECONCILE_COALESCE_MS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var lastRefreshServerId: String? = null
    private var lastRefreshMark: TimeMark? = null

    fun onForeground(reason: String) {
        scope.launch {
            val serverId =
                try {
                    authStore.readSession()?.currentServerId?.takeIf { it.isNotBlank() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                } ?: return@launch
            val shouldRefresh =
                mutex.withLock {
                    val mark = lastRefreshMark
                    val withinWindow =
                        lastRefreshServerId == serverId &&
                            mark != null &&
                            mark.elapsedNow().inWholeMilliseconds < coalesceWindowMs
                    if (withinWindow) {
                        false
                    } else {
                        lastRefreshServerId = serverId
                        lastRefreshMark = timeSource.markNow()
                        true
                    }
                }
            if (!shouldRefresh) {
                diagnosticsLogger("foreground_reconcile_skip reason=$reason serverId=$serverId cause=coalesced")
                return@launch
            }
            diagnosticsLogger("foreground_reconcile_refresh reason=$reason serverId=$serverId")
            try {
                refreshMembers(ServerId(serverId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                diagnosticsLogger(
                    "foreground_reconcile_refresh_failed reason=$reason serverId=$serverId error=${error::class.simpleName}"
                )
            }
        }
    }
}
