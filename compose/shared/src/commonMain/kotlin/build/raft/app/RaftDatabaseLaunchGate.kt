package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftDatabaseLaunchAttempt
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.attemptRaftDatabaseLaunch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A page's outermost layer ([build.raft.app.base.raftPageLayers]: the app shell, or the first
 * page in the process to need the store): creates the shared store provider (whose database-open entry
 * refuses a database newer than the app with a typed error, before SQLDelight opens it) and shows
 * the "database is newer than the app" recovery page instead of [content] when it does.
 *
 * It sits outside the environment indicator and every product surface, so nothing in the shell
 * reaches the database before this gate has classified the launch.
 *
 * The first creation (opening SQLite and building the store graph) runs off the main thread
 * (#113); the launch surface shows meanwhile. Once the store exists, the gate is synchronous again.
 */
@Composable
internal fun RaftDatabaseLaunchGate(content: @Composable () -> Unit) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    key(bridge) {
        RaftDatabaseLaunchGateContent(
            existingStore = {
                // Built already (a later page, a second window, a recreated Activity): today's
                // synchronous get, which also points the provider at this caller's event bus.
                if (RaftSharedStoreRegistry.currentOrNull() != null) RaftSharedStoreRegistry.get(bridge) else null
            },
            buildStore =
                remember {
                    // Resolved here, on the UI thread; only the build itself leaves it.
                    val host = bridge.hostCapabilities
                    val build: () -> Any = { RaftSharedStoreRegistry.getOrCreateWithoutUpdate(host) }
                    build
                },
            adoptStore = { RaftSharedStoreRegistry.get(bridge) },
            logWarn = bridge::logWarn,
            content = content,
        )
    }
}

/**
 * Where the database launch gate builds the store the first time: a background pool, never the
 * main thread and never the serial store dispatcher (the build makes blocking SQLite calls that
 * [build.raft.app.data.RaftStoreThreading.checkNotOnStoreDispatcher] reports there).
 */
internal val RaftDatabaseLaunchBuildDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default

/**
 * [RaftDatabaseLaunchGate] without its pager lookup, so a test can compose it.
 *
 * [existingStore] runs on the UI thread and must be cheap: the store when it already exists, else
 * null. Otherwise [buildStore] runs on [buildDispatcher] and the [launchSurface] shows until it
 * returns; a store it built is then handed to [adoptStore] on the UI thread before [content]
 * composes. A database newer than the app shows the recovery page (Retry builds again, the page
 * staying up meanwhile), any other failure is thrown from composition, as when the build ran there.
 */
@Composable
internal fun <T : Any> RaftDatabaseLaunchGateContent(
    existingStore: () -> T?,
    buildStore: () -> T,
    adoptStore: (T) -> Unit,
    logWarn: (String) -> Unit,
    content: @Composable () -> Unit,
    buildDispatcher: CoroutineDispatcher = RaftDatabaseLaunchBuildDispatcher,
    launchSurface: @Composable () -> Unit = { AppShellRestoreSplashContent() },
) {
    var retryEpoch by remember { mutableStateOf(0) }
    val existing: RaftDatabaseLaunchAttempt<T>? =
        remember(retryEpoch) { existingStore()?.let { RaftDatabaseLaunchAttempt.Ready(it) } }
    // Kept across a Retry: the recovery page stays up while the next attempt runs, as it did when
    // the attempt was synchronous.
    var built by remember { mutableStateOf<RaftDatabaseLaunchAttempt<T>?>(null) }
    if (existing == null) {
        LaunchedEffect(retryEpoch) {
            built = withContext(buildDispatcher) { attemptRaftDatabaseLaunch(buildStore) }
        }
    }
    when (val attempt = existing ?: built) {
        null -> launchSurface()
        is RaftDatabaseLaunchAttempt.Ready -> {
            // In composition, so on the UI thread whatever dispatcher resumed the build.
            if (existing == null) remember(attempt) { adoptStore(attempt.value) }
            LaunchedEffect(Unit) { RaftDatabaseLaunchReadiness.markSettled() }
            content()
        }
        is RaftDatabaseLaunchAttempt.UpgradeRequired -> {
            val mismatch = attempt.mismatch
            LaunchedEffect(mismatch) {
                RaftDatabaseLaunchReadiness.markSettled()
                logWarn(
                    "database_launch_guard result=upgrade_required " +
                        "databaseVersion=${mismatch.databaseVersion} " +
                        "supportedVersion=${mismatch.supportedVersion}",
                )
            }
            RaftDatabaseLaunchRecoveryContent(
                mismatch = mismatch,
                onRetry = {
                    logWarn(
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

/**
 * Process-wide: whether a database launch gate has had something to show besides the launch
 * surface (the app, or the database recovery page). A native host that covers the first frames
 * with its own splash (Android's home cold-start hold) keeps it up until then, so the launch goes
 * from that splash straight to the app (#113). Never reset: a later gate starts from a built store.
 */
object RaftDatabaseLaunchReadiness {
    private val settledState = MutableStateFlow(false)

    val settled: StateFlow<Boolean> = settledState.asStateFlow()

    internal fun markSettled() {
        settledState.value = true
    }

    /** Unit tests only. */
    internal fun resetForTests() {
        settledState.value = false
    }
}
