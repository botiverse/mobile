package build.raft.app.core.data.storage

import android.content.Context
import java.io.File

private const val SlockDatabaseName = "slock_shared.db"
private const val SlockImageDiskCacheDir = "image_cache"
private const val SlockAttachmentDownloadCacheDir = "slock-attachments"

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
                    temporaryAndLogBytes = roots.temporaryAndLogDirs.sumExistingTreeSizes()
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
        val temporaryAndLogDirs: List<File>
) {
    companion object {
        fun fromContext(context: Context): StorageManagementRoots {
            val database = context.getDatabasePath(SlockDatabaseName)
            val externalCache = context.externalCacheDir
            val crashDir = context.getExternalFilesDir(null)?.resolve("crashes")
            return StorageManagementRoots(
                    databaseFiles =
                            listOf(
                                    database,
                                    File(database.path + "-wal"),
                                    File(database.path + "-shm"),
                                    File(database.path + "-journal")
                            ),
                    imageCacheDirs = listOf(context.cacheDir.resolve(SlockImageDiskCacheDir)),
                    attachmentDownloadCacheDirs = listOf(context.cacheDir.resolve(SlockAttachmentDownloadCacheDir)),
                    temporaryAndLogDirs =
                            listOfNotNull(
                                    context.cacheDir.resolve("tmp"),
                                    context.cacheDir.resolve("temp"),
                                    context.cacheDir.resolve("logs"),
                                    context.cacheDir.resolve("diagnostics"),
                                    externalCache?.resolve("tmp"),
                                    externalCache?.resolve("temp"),
                                    externalCache?.resolve("logs"),
                                    crashDir
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
