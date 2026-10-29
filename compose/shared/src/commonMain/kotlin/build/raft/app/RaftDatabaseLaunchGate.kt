package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftDatabaseLaunchAttempt
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.attemptRaftDatabaseLaunch

/**
 * A page's outermost layer ([build.raft.app.base.raftPageLayers]: the app shell, or the first
 * page in the process to need the store): creates the shared store provider (whose database-open entry
 * refuses a database newer than the app with a typed error, before SQLDelight opens it) and shows
 * the "database is newer than the app" recovery page instead of [content] when it does.
 *
 * It sits outside the environment indicator and every product surface, so nothing in the shell
 * reaches the database before this gate has classified the launch.
 */
@Composable
internal fun RaftDatabaseLaunchGate(content: @Composable () -> Unit) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    var retryEpoch by remember { mutableStateOf(0) }
    val attempt =
        remember(bridge, retryEpoch) {
            attemptRaftDatabaseLaunch { RaftSharedStoreRegistry.get(bridge) }
        }
    when (attempt) {
        is RaftDatabaseLaunchAttempt.Ready -> content()
        is RaftDatabaseLaunchAttempt.UpgradeRequired -> {
            val mismatch = attempt.mismatch
            LaunchedEffect(mismatch) {
                bridge.logWarn(
                    "database_launch_guard result=upgrade_required " +
                        "databaseVersion=${mismatch.databaseVersion} " +
                        "supportedVersion=${mismatch.supportedVersion}",
                )
            }
            RaftDatabaseLaunchRecoveryContent(
                mismatch = mismatch,
                onRetry = {
                    bridge.logWarn(
                        "database_launch_guard action=retry " +
                            "databaseVersion=${mismatch.databaseVersion} " +
                            "supportedVersion=${mismatch.supportedVersion}",
                    )
                    retryEpoch += 1
                },
            )
        }
        is RaftDatabaseLaunchAttempt.Fatal -> throw attempt.throwable
    }
}
