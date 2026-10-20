package build.raft.app.core.data.storage

import build.raft.app.data.RaftAndroidSqlDatabaseContext
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageManagementRepositoryTest {
    @Test
    fun diagnosticsLogsAreCountedButLeftToTheDiagnosticsCleaner() {
        val root = Files.createTempDirectory("slock-storage-test").toFile()
        try {
            val picked = root.resolve(RAFT_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME).also { it.mkdirs() }
            picked.resolve("picked.png").writeBytesOfSize(7)
            val logRing = root.resolve("raft_diagnostics_logs").also { it.mkdirs() }
            val openLog = logRing.resolve("launch-1/raft-diagnostics.jsonl").also { it.parentFile.mkdirs() }.writeBytesOfSize(20)
            val repository =
                    StorageManagementRepository(
                            roots =
                                    StorageManagementRoots(
                                            databaseFiles = emptyList(),
                                            imageCacheDirs = emptyList(),
                                            attachmentDownloadCacheDirs = emptyList(),
                                            temporaryAndLogDirs = listOf(picked),
                                            diagnosticsLogDirs = listOf(logRing)
                                    )
                    )

            assertEquals(27L, repository.snapshot().temporaryAndLogBytes)

            val cleared = repository.clear(StorageClearTarget.TemporaryAndLogs)

            // Picked attachments are removed; the open log ring is not touched here.
            assertEquals(20L, cleared.temporaryAndLogBytes)
            assertTrue(openLog.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotCountsDatabaseImageCacheAndTemporaryLogsSeparately() {
        val root = Files.createTempDirectory("slock-storage-test").toFile()
        try {
            val database = root.resolve(RaftAndroidSqlDatabaseContext.DatabaseFileName).writeBytesOfSize(10)
            val wal = root.resolve("${RaftAndroidSqlDatabaseContext.DatabaseFileName}-wal").writeBytesOfSize(4)
            val imageCache = root.resolve("image_cache").also { it.mkdirs() }
            imageCache.resolve("avatar.bin").writeBytesOfSize(8)
            val attachmentCache = root.resolve("slock-attachments").also { it.mkdirs() }
            attachmentCache.resolve("draft.apk").writeBytesOfSize(12)
            val logs = root.resolve("logs").also { it.mkdirs() }
            logs.resolve("crash.txt").writeBytesOfSize(6)
            val pickedAttachments = root.resolve("slock-picked-thread-attachments").also { it.mkdirs() }
            pickedAttachments.resolve("picked.png").writeBytesOfSize(7)

            val repository =
                    StorageManagementRepository(
                            roots =
                                    StorageManagementRoots(
                                            databaseFiles = listOf(database, wal),
                                            imageCacheDirs = listOf(imageCache),
                                            attachmentDownloadCacheDirs = listOf(attachmentCache),
                                            temporaryAndLogDirs = listOf(logs, pickedAttachments)
                                    )
                    )

            val snapshot = repository.snapshot()

            assertEquals(14L, snapshot.localDatabaseBytes)
            assertEquals(8L, snapshot.imageCacheBytes)
            assertEquals(12L, snapshot.attachmentDownloadBytes)
            assertEquals(13L, snapshot.temporaryAndLogBytes)
            assertEquals(47L, snapshot.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearOnlyDeletesTheSelectedRebuildableCategory() {
        val root = Files.createTempDirectory("slock-storage-clear-test").toFile()
        try {
            val database = root.resolve(RaftAndroidSqlDatabaseContext.DatabaseFileName).writeBytesOfSize(10)
            val imageCache = root.resolve("image_cache").also { it.mkdirs() }
            val image = imageCache.resolve("avatar.bin").writeBytesOfSize(8)
            val attachmentCache = root.resolve("slock-attachments").also { it.mkdirs() }
            val attachment = attachmentCache.resolve("draft.apk").writeBytesOfSize(12)
            val logs = root.resolve("logs").also { it.mkdirs() }
            val log = logs.resolve("crash.txt").writeBytesOfSize(6)

            val repository =
                    StorageManagementRepository(
                            roots =
                                    StorageManagementRoots(
                                            databaseFiles = listOf(database),
                                            imageCacheDirs = listOf(imageCache),
                                            attachmentDownloadCacheDirs = listOf(attachmentCache),
                                            temporaryAndLogDirs = listOf(logs)
                                    )
                    )

            val snapshot = repository.clear(StorageClearTarget.ImageCache)

            assertEquals(10L, snapshot.localDatabaseBytes)
            assertEquals(0L, snapshot.imageCacheBytes)
            assertEquals(12L, snapshot.attachmentDownloadBytes)
            assertEquals(6L, snapshot.temporaryAndLogBytes)
            assertTrue(database.exists())
            assertFalse(image.exists())
            assertTrue(attachment.exists())
            assertTrue(log.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearTemporaryFilesAlsoDeletesPickedThreadAttachmentStaging() {
        val root = Files.createTempDirectory("slock-storage-picked-clear-test").toFile()
        try {
            val database = root.resolve(RaftAndroidSqlDatabaseContext.DatabaseFileName).writeBytesOfSize(10)
            val pickedAttachments = root.resolve("slock-picked-thread-attachments").also { it.mkdirs() }
            val picked = pickedAttachments.resolve("picked.png").writeBytesOfSize(7)
            val logs = root.resolve("logs").also { it.mkdirs() }
            val log = logs.resolve("app.jsonl").writeBytesOfSize(6)
            val repository =
                    StorageManagementRepository(
                            roots =
                                    StorageManagementRoots(
                                            databaseFiles = listOf(database),
                                            imageCacheDirs = emptyList(),
                                            attachmentDownloadCacheDirs = emptyList(),
                                            temporaryAndLogDirs = listOf(logs, pickedAttachments)
                                    )
                    )

            val before = repository.snapshot()
            val after = repository.clear(StorageClearTarget.TemporaryAndLogs)

            assertEquals(13L, before.temporaryAndLogBytes)
            assertEquals(0L, after.temporaryAndLogBytes)
            assertTrue(database.exists())
            assertFalse(picked.exists())
            assertFalse(log.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearAttachmentDownloadsKeepsDatabaseImageCacheAndLogs() {
        val root = Files.createTempDirectory("slock-storage-attachment-clear-test").toFile()
        try {
            val database = root.resolve(RaftAndroidSqlDatabaseContext.DatabaseFileName).writeBytesOfSize(10)
            val imageCache = root.resolve("image_cache").also { it.mkdirs() }
            val image = imageCache.resolve("avatar.bin").writeBytesOfSize(8)
            val attachmentCache = root.resolve("slock-attachments").also { it.mkdirs() }
            val attachment = attachmentCache.resolve("draft.apk").writeBytesOfSize(12)
            val logs = root.resolve("logs").also { it.mkdirs() }
            val log = logs.resolve("crash.txt").writeBytesOfSize(6)

            val repository =
                    StorageManagementRepository(
                            roots =
                                    StorageManagementRoots(
                                            databaseFiles = listOf(database),
                                            imageCacheDirs = listOf(imageCache),
                                            attachmentDownloadCacheDirs = listOf(attachmentCache),
                                            temporaryAndLogDirs = listOf(logs)
                                    )
                    )

            val cleanedDirectories = mutableListOf<File>()
            val snapshot =
                    checkNotNull(
                            repository.clearAttachmentStaging { directory ->
                                cleanedDirectories += directory
                                directory.listFiles().orEmpty().all { it.delete() || !it.exists() }
                            }
                    )

            assertEquals(listOf(attachmentCache), cleanedDirectories)
            assertEquals(10L, snapshot.localDatabaseBytes)
            assertEquals(8L, snapshot.imageCacheBytes)
            assertEquals(0L, snapshot.attachmentDownloadBytes)
            assertEquals(6L, snapshot.temporaryAndLogBytes)
            assertTrue(database.exists())
            assertTrue(image.exists())
            assertFalse(attachment.exists())
            assertTrue(log.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun attachmentStagingCannotBypassFencedCleaner() {
        val root = Files.createTempDirectory("slock-storage-attachment-fence-test").toFile()
        try {
            val attachmentCache = root.resolve("slock-attachments").also { it.mkdirs() }
            val attachment = attachmentCache.resolve("draft.apk").writeBytesOfSize(12)
            val repository =
                    StorageManagementRepository(
                            roots =
                                    StorageManagementRoots(
                                            databaseFiles = emptyList(),
                                            imageCacheDirs = emptyList(),
                                            attachmentDownloadCacheDirs = listOf(attachmentCache),
                                            temporaryAndLogDirs = emptyList()
                                    )
                    )

            val bypass = runCatching { repository.clear(StorageClearTarget.AttachmentDownloads) }
            val failedFencedClear = repository.clearAttachmentStaging { false }

            assertTrue(bypass.isFailure)
            assertEquals(null, failedFencedClear)
            assertTrue(attachment.exists())
            assertEquals(12L, repository.snapshot().attachmentDownloadBytes)
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun File.writeBytesOfSize(size: Int): File {
    parentFile?.mkdirs()
    writeBytes(ByteArray(size) { 1 })
    return this
}
