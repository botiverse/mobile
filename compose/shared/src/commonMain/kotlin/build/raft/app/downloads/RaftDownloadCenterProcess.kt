package build.raft.app.downloads

import build.raft.app.data.raftSharedPreferenceStore
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide download centre. Its scope is the app's, not a page's: a download keeps
 * running after the page that started it is closed, for as long as the process lives (Android
 * keeps the process alive with the download foreground service while one is active).
 */
object RaftDownloadCenterProcess {
    /**
     * Host hook run once, on the main thread, when the centre is first created (the first
     * download page or the Storage page): Android attaches its download notifications here
     * without forcing the centre (and its preference store) to exist at app start.
     */
    var onCreated: ((RaftDownloadCenter) -> Unit)? = null

    val center: RaftDownloadCenter by lazy {
        RaftDownloadCenter(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            repository = RaftDownloadHistoryRepository(raftSharedPreferenceStore()),
            nowMillis = { Clock.System.now().toEpochMilliseconds() }
        ).also { created -> onCreated?.invoke(created) }
    }
}
