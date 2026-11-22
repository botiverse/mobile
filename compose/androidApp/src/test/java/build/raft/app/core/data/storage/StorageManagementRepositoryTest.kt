package build.raft.app.core.data.storage

import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.shared.cleanupLegacyAndroidAvatarCacheDir
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageManagementRepositoryTest {
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

    @Test
    fun cleanupLegacyAndroidAvatarCacheDirDeletesOnlyRetiredAvatarDirectory() {
        val root = Files.createTempDirectory("slock-legacy-avatar-cleanup-test").toFile()
        try {
            val legacyAvatarDir = root.resolve("image_cache").resolve("avatar_durable_v1").also { it.mkdirs() }
            legacyAvatarDir.resolve("entry.bin").writeBytesOfSize(16)
            val siblingCache = root.resolve("raft_image_cache").also { it.mkdirs() }
            val coilEntry = siblingCache.resolve("coil.bin").writeBytesOfSize(8)

            assertTrue(cleanupLegacyAndroidAvatarCacheDir(root))
            assertFalse(legacyAvatarDir.exists())
            assertTrue(coilEntry.exists())
            assertFalse(cleanupLegacyAndroidAvatarCacheDir(root))
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
