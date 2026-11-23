package build.raft.app

import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A request to open the in-app Download Center (Settings › Storage) from a platform notification tap. */
internal data class RaftPendingDownloadCenterLaunch(
    val id: Long,
    val receivedAtEpochMillis: Long,
    val source: String,
    val targetShellInstanceId: String? = null,
)

/**
 * Process-wide single slot for opening the in-app Download Center (`SETTINGS_STORAGE_PAGE`) from
 * outside the Compose tree (e.g. Android download progress / fallback notification taps,
 * #proj-mobile:#21): newest wins, scoped to [RaftPendingDownloadCenterLaunch.targetShellInstanceId]
 * when specified so multi-window shells cannot steal another window's launch, taken once by an
 * authenticated app shell via [RaftShellNavigationIntake], and dropped after [ttlMillis].
 */
internal class RaftDownloadCenterLaunchInbox(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = RaftMessageLinkInbox.DefaultTtlMillis,
) {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingDownloadCenterLaunch?>(null)
    val pending: StateFlow<RaftPendingDownloadCenterLaunch?> = mutablePending.asStateFlow()

    fun submit(source: String, targetShellInstanceId: String? = null): Long =
        synchronized(lock) {
            val normalizedTarget = targetShellInstanceId?.trim()?.takeIf(String::isNotEmpty)
            val created =
                RaftPendingDownloadCenterLaunch(
                    id = nextId++,
                    receivedAtEpochMillis = nowEpochMillis(),
                    source = source,
                    targetShellInstanceId = normalizedTarget,
                )
            mutablePending.value = created
            created.id
        }

    /**
     * Takes the pending launch [id] for [shellInstanceId]. Returns null without clearing when
     * another shell owns [RaftPendingDownloadCenterLaunch.targetShellInstanceId]; clears and
     * returns null when expired.
     */
    fun take(id: Long, shellInstanceId: String? = null): RaftPendingDownloadCenterLaunch? =
        synchronized(lock) {
            val current = mutablePending.value?.takeIf { it.id == id } ?: return null
            val normalizedTarget = current.targetShellInstanceId
            val normalizedShell = shellInstanceId?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedTarget != null && normalizedTarget != normalizedShell) {
                return null
            }
            mutablePending.value = null
            current.takeIf { nowEpochMillis() - it.receivedAtEpochMillis <= ttlMillis }
        }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        /** The slot platform notification ingresses and every app shell share. */
        val Process: RaftDownloadCenterLaunchInbox = RaftDownloadCenterLaunchInbox()
    }
}

/** Platform entry point (Android `androidApp` -> `:shared`) for opening the in-app Download Center. */
object RaftDownloadCenterLaunchIngress {
    fun submit(source: String, targetShellInstanceId: String? = null): Long =
        RaftDownloadCenterLaunchInbox.Process.submit(source = source, targetShellInstanceId = targetShellInstanceId)

    fun clear() = RaftDownloadCenterLaunchInbox.Process.clear()
}
