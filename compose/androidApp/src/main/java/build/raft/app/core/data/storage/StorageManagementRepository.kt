package build.raft.app.core.data.storage

import android.content.Context
import build.raft.app.core.data.diagnostics.RaftAndroidLocalDiagnostics
import build.raft.app.core.data.diagnostics.RaftGeneralLogRing
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.shared.ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME
import build.raft.app.shared.RAFT_ANDROID_IMAGE_CACHE_DIR_NAME
import java.io.File

private const val RaftDatabaseName = RaftAndroidSqlDatabaseContext.DatabaseFileName
// task #13: every name below comes from the code that writes it. Hand-typed copies
// drifted (the database row counted a non-existent `slock_shared.db` and always
// showed 0 B; the temp row counted dirs nothing writes and missed the logs).
private const val RaftImageDiskCacheDir = RAFT_ANDROID_IMAGE_CACHE_DIR_NAME
private const val RaftAttachmentDownloadCacheDir = ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME

/** Picked/compressed thread attachments; written by RaftCmpActivity. */
internal const val RAFT_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME = "slock-picked-thread-attachments"

data class StorageManagementSnapshot(
        val localDatabaseBytes: Long,
        val imageCacheBytes: Long,
        val attachmentDownloadBytes: Long,
        val temporaryAndLogBytes: Long
) {
    val totalBytes: Long
        get() = localDatabaseBytes + imageCacheBytes + attachmentDownloadBytes + temporaryAndLogBytes
}

enum class StorageClearTarget {
    ImageCache,
    AttachmentDownloads,
    TemporaryAndLogs
}

class StorageManagementRepository(private val roots: StorageManagementRoots) {
    constructor(context: Context) : this(StorageManagementRoots.fromContext(context))

    fun snapshot(): StorageManagementSnapshot =
            StorageManagementSnapshot(
                    localDatabaseBytes = roots.databaseFiles.sumExistingFileSizes(),
                    imageCacheBytes = roots.imageCacheDirs.sumExistingTreeSizes(),
                    attachmentDownloadBytes = roots.attachmentDownloadCacheDirs.sumExistingTreeSizes(),
                    temporaryAndLogBytes =
                            roots.temporaryAndLogDirs.sumExistingTreeSizes() +
                                    roots.diagnosticsLogDirs.sumExistingTreeSizes()
            )

    fun clear(target: StorageClearTarget): StorageManagementSnapshot {
        when (target) {
            StorageClearTarget.ImageCache -> roots.imageCacheDirs.forEach { it.deleteContents() }
            StorageClearTarget.AttachmentDownloads ->
                    error("Attachment staging requires target-fenced cleanup")
            StorageClearTarget.TemporaryAndLogs ->
                    roots.temporaryAndLogDirs.forEach { it.deleteContents() }
        }
        return snapshot()
    }

    fun clearAttachmentStaging(cleaner: (File) -> Boolean): StorageManagementSnapshot? {
        if (!roots.attachmentDownloadCacheDirs.all(cleaner)) return null
        return snapshot()
    }
}

data class StorageManagementRoots(
        val databaseFiles: List<File>,
        val imageCacheDirs: List<File>,
        val attachmentDownloadCacheDirs: List<File>,
        val temporaryAndLogDirs: List<File>,
        /**
         * Counted in the temporary-and-logs row but NOT deleted by [StorageManagementRepository.clear]:
         * the log ring holds them open, so they are cleared through
         * RaftAndroidLocalDiagnostics.clearLocalDiagnosticsFiles instead.
         */
        val diagnosticsLogDirs: List<File> = emptyList()
) {
    companion object {
        fun fromContext(context: Context): StorageManagementRoots {
            val database = context.getDatabasePath(RaftDatabaseName)
            return StorageManagementRoots(
                    databaseFiles =
                            listOf(
                                    database,
                                    File(database.path + "-wal"),
                                    File(database.path + "-shm"),
                                    File(database.path + "-journal")
                            ),
                    imageCacheDirs = listOf(context.cacheDir.resolve(RaftImageDiskCacheDir)),
                    attachmentDownloadCacheDirs = listOf(context.cacheDir.resolve(RaftAttachmentDownloadCacheDir)),
                    temporaryAndLogDirs =
                            listOf(context.cacheDir.resolve(RAFT_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME)),
                    diagnosticsLogDirs =
                            listOf(
                                    RaftGeneralLogRing.logRoot(context),
                                    RaftAndroidLocalDiagnostics.feedbackDir(context)
                            )
            )
        }
    }
}

internal fun List<File>.sumExistingFileSizes(): Long =
        fold(0L) { total, file -> total + file.takeIf { it.isFile }?.length().orZero() }

internal fun List<File>.sumExistingTreeSizes(): Long =
        fold(0L) { total, file -> total + file.treeSizeBytes() }

private fun File.treeSizeBytes(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    return listFiles()?.fold(0L) { total, child -> total + child.treeSizeBytes() } ?: 0L
}

private fun File.deleteContents() {
    if (!exists() || !isDirectory) return
    listFiles()?.forEach { child ->
        if (child.isDirectory) {
            child.deleteRecursively()
        } else {
            child.delete()
        }
    }
}

private fun Long?.orZero(): Long = this ?: 0L
