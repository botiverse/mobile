package build.raft.app.shared

import android.app.DownloadManager
import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.core.data.diagnostics.RaftAppLog
import java.util.concurrent.atomic.AtomicBoolean

/** The update APK's file name; [handsApkDownloadVersionCode] parses it back. */
internal fun handsApkDownloadFileName(versionCode: Long): String = "raft-hands-$versionCode.apk"

private val HANDS_APK_DOWNLOAD_NAME = Regex("""^raft-hands-(\d+)(?:-\d+)?\.apk$""")

/**
 * Version code of an in-app update APK from its DownloadManager local URI or file name, or null
 * for anything else. DownloadManager may suffix a clashing name (`raft-hands-11601002-1.apk`).
 */
internal fun handsApkDownloadVersionCode(localUriOrName: String?): Long? {
    val name = localUriOrName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
    return HANDS_APK_DOWNLOAD_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
}

/** One of this app's DownloadManager rows (DownloadManager only returns the caller's own). */
internal data class HandsApkDownloadRow(val id: Long, val status: Int, val localUri: String?)

/**
 * Rows to remove: finished (successful or failed) update APKs for a version at or below the one
 * running now. Such a row is dead weight, and while a successful one stays in DownloadManager
 * (requested with VISIBILITY_VISIBLE_NOTIFY_COMPLETED) the system download provider keeps its
 * "Raft x.y.z downloaded" notification and re-posts it on later provider updates.
 * In-progress rows and rows for a newer, not yet installed version are kept.
 */
internal fun staleHandsApkDownloadIds(rows: List<HandsApkDownloadRow>, installedVersionCode: Long): List<Long> =
    rows.filter { row ->
        val terminal = row.status == DownloadManager.STATUS_SUCCESSFUL || row.status == DownloadManager.STATUS_FAILED
        val version = handsApkDownloadVersionCode(row.localUri)
        terminal && version != null && version <= installedVersionCode
    }.map { it.id }

/**
 * Once per process: removes the in-app updater's finished DownloadManager rows for versions that
 * are already installed ([staleHandsApkDownloadIds]). Removing the row deletes the obsolete APK
 * and withdraws the system's completed-download notification for it.
 */
internal object HandsUpdateDownloadCleanup {
    private const val LOG_TAG = "SlockHandsUpdate"
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!BuildConfig.HANDS_APK_UPDATE_ENABLED) return
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val worker = Runnable {
            runCatching { cleanup(appContext) }.onFailure { error ->
                RaftAppLog.w(LOG_TAG, "stale_download_cleanup_failed errorType=${error::class.simpleName}")
            }
        }
        Thread(worker, "hands-download-cleanup").apply { isDaemon = true }.start()
    }

    private fun cleanup(context: Context) {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return
        val rows = mutableListOf<HandsApkDownloadRow>()
        manager.query(DownloadManager.Query())?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (idIndex < 0 || statusIndex < 0 || uriIndex < 0) return
            while (cursor.moveToNext()) {
                rows += HandsApkDownloadRow(cursor.getLong(idIndex), cursor.getInt(statusIndex), cursor.getString(uriIndex))
            }
        }
        val stale = staleHandsApkDownloadIds(rows, BuildConfig.VERSION_CODE.toLong())
        if (stale.isEmpty()) return
        val removed = manager.remove(*stale.toLongArray())
        RaftAppLog.record(LOG_TAG, "stale_download_removed count=$removed candidates=${stale.size}")
    }
}
