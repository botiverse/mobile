package build.raft.app.shared

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import org.json.JSONObject

internal enum class AndroidSystemDownloadStatus {
    Pending,
    Running,
    Paused,
    Successful,
    Failed,
    Missing
}

internal data class AndroidSystemDownloadSnapshot(
    val status: AndroidSystemDownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val reason: Int? = null
)

internal data class AndroidSystemDownloadEnqueueRequest(
    val url: String,
    val displayName: String,
    val mimeType: String,
    val stagingPath: String
)

internal interface AndroidSystemDownloadBackend {
    fun enqueue(request: AndroidSystemDownloadEnqueueRequest): Long

    fun query(systemDownloadId: Long): AndroidSystemDownloadSnapshot

    fun remove(systemDownloadId: Long)
}

internal data class AndroidSystemDownloadRecord(
    val systemDownloadId: Long,
    val targetKey: String,
    val stagingPath: String,
    val mimeType: String,
    val attemptToken: String,
    val ownerKey: String
)

internal interface AndroidSystemDownloadRecordStore {
    fun read(targetKey: String): AndroidSystemDownloadRecord?

    fun write(record: AndroidSystemDownloadRecord)

    fun remove(targetKey: String)
}

internal data class AndroidAttachmentSystemProgress(
    val downloadId: String,
    val attemptToken: String,
    val sequence: Long,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val percent: Int?,
    val bytesPerSecond: Long?
)

internal data class AndroidAttachmentSystemResult(
    val status: String,
    val contentType: String?,
    val errorKind: String? = null,
    val message: String? = null
)

internal data class AndroidAttachmentSystemCacheState(
    val cached: Boolean,
    val active: Boolean,
    val sizeBytes: Long
)

private enum class PublishOutcome {
    Published,
    AlreadyPublished,
    Failed,
    Stale
}

/**
 * Durable attachment transfer owner for Android.
 *
 * DownloadManager owns network execution across Activity recreation, backgrounding and process
 * death. This engine owns only stable target dedupe, persisted system-id recovery and atomic
 * publication into the existing private attachment cache. Records and cache paths are bound to an
 * account/server owner key; a foreign owner leaves the system job untouched and cannot observe,
 * publish, cancel or reuse it until the original owner returns.
 */
internal class AndroidAttachmentSystemDownloadEngine(
    private val backend: AndroidSystemDownloadBackend,
    private val recordStore: AndroidSystemDownloadRecordStore,
    private val stagingPath: (targetKey: String, displayName: String) -> String,
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val pollIntervalMillis: Long = 250L,
    private val isTargetUsable: (File) -> Boolean = { it.isFile && it.length() > 0L },
    private val beforePublishCommit: (String) -> Unit = {}
) {
    private val targetLocks = ConcurrentHashMap<String, Any>()
    private val activeByLogicalDownloadId = ConcurrentHashMap<String, AndroidSystemDownloadRecord>()
    private val committedByTarget = ConcurrentHashMap<String, AndroidSystemDownloadRecord>()
    private val cancelledSystemIds = ConcurrentHashMap.newKeySet<Long>()

    fun download(
        downloadId: String,
        ownerKey: String,
        targetFile: File,
        url: String,
        displayName: String,
        mimeType: String,
        onProgress: ((AndroidAttachmentSystemProgress) -> Unit)? = null
    ): AndroidAttachmentSystemResult {
        if (synchronized(targetLock(targetFile.absolutePath)) { isTargetUsable(targetFile) }) {
            return AndroidAttachmentSystemResult(status = "complete", contentType = mimeType)
        }
        val record = startOrReuse(downloadId, ownerKey, targetFile, url, displayName, mimeType)
            ?: return AndroidAttachmentSystemResult(
                status = "failed",
                contentType = null,
                errorKind = "owner_mismatch",
                message = "Download belongs to another signed-in owner"
            )
        var sequence = 0L
        var previousBytes = 0L
        var previousAt = nowMillis()
        while (true) {
            if (synchronized(targetLock(record.targetKey)) { isTargetUsable(targetFile) }) {
                finishSuccessfulIfCurrent(record)
                return AndroidAttachmentSystemResult(status = "complete", contentType = record.mimeType)
            }
            val snapshot = backend.query(record.systemDownloadId)
            when (snapshot.status) {
                AndroidSystemDownloadStatus.Pending,
                AndroidSystemDownloadStatus.Running,
                AndroidSystemDownloadStatus.Paused -> {
                    sequence += 1L
                    val now = nowMillis()
                    val elapsed = (now - previousAt).coerceAtLeast(1L)
                    val bytesPerSecond = androidSystemDownloadBytesPerSecond(
                        byteDelta = (snapshot.downloadedBytes - previousBytes).coerceAtLeast(0L),
                        elapsedMillis = elapsed
                    )
                    onProgress?.invoke(
                        AndroidAttachmentSystemProgress(
                            downloadId = downloadId,
                            attemptToken = record.attemptToken,
                            sequence = sequence,
                            downloadedBytes = snapshot.downloadedBytes,
                            totalBytes = snapshot.totalBytes,
                            percent = androidSystemDownloadPercent(snapshot.downloadedBytes, snapshot.totalBytes),
                            bytesPerSecond = bytesPerSecond.takeIf { it > 0L }
                        )
                    )
                    previousBytes = snapshot.downloadedBytes
                    previousAt = now
                    sleepMillis(pollIntervalMillis)
                }
                AndroidSystemDownloadStatus.Successful -> {
                    when (publishIfCurrent(record, targetFile)) {
                        PublishOutcome.Published,
                        PublishOutcome.AlreadyPublished -> {
                            return AndroidAttachmentSystemResult(status = "complete", contentType = record.mimeType)
                        }
                        PublishOutcome.Stale ->
                            return AndroidAttachmentSystemResult(
                                status = "cancelled",
                                contentType = null,
                                errorKind = "stale_attempt",
                                message = "Download was replaced or stopped"
                            )
                        PublishOutcome.Failed ->
                            return AndroidAttachmentSystemResult(
                                status = "failed",
                                contentType = null,
                                errorKind = "publish_failed",
                                message = "Unable to publish downloaded attachment"
                            )
                    }
                }
                AndroidSystemDownloadStatus.Failed -> {
                    finishFailedIfCurrent(record, removeBackend = true)
                    return AndroidAttachmentSystemResult(
                        status = "failed",
                        contentType = null,
                        errorKind = "system_download_failed",
                        message = "System download failed${snapshot.reason?.let { " ($it)" }.orEmpty()}"
                    )
                }
                AndroidSystemDownloadStatus.Missing -> {
                    val cancelled = cancelledSystemIds.remove(record.systemDownloadId)
                    finishFailedIfCurrent(record, removeBackend = false)
                    return AndroidAttachmentSystemResult(
                        status = if (cancelled) "cancelled" else "failed",
                        contentType = null,
                        errorKind = if (cancelled) "cancelled" else "system_download_missing",
                        message = if (cancelled) "Download stopped" else "System download is no longer available"
                    )
                }
            }
        }
    }

    fun cacheState(
        targetFile: File,
        ownerKey: String,
        logicalDownloadId: String? = null
    ): AndroidAttachmentSystemCacheState {
        val targetKey = targetFile.absolutePath
        val record = synchronized(targetLock(targetKey)) { recordStore.read(targetKey) }
        if (record == null) {
            return AndroidAttachmentSystemCacheState(
                cached = isTargetUsable(targetFile),
                active = false,
                sizeBytes = targetFile.takeIf(isTargetUsable)?.length() ?: 0L
            )
        }
        if (record.ownerKey != ownerKey) {
            return AndroidAttachmentSystemCacheState(cached = false, active = false, sizeBytes = 0L)
        }
        logicalDownloadId
            ?.takeIf { it.isNotBlank() }
            ?.let { activeByLogicalDownloadId[logicalMappingKey(it, ownerKey)] = record }
        return when (backend.query(record.systemDownloadId).status) {
            AndroidSystemDownloadStatus.Pending,
            AndroidSystemDownloadStatus.Running,
            AndroidSystemDownloadStatus.Paused ->
                AndroidAttachmentSystemCacheState(cached = false, active = true, sizeBytes = 0L)
            AndroidSystemDownloadStatus.Successful -> {
                val outcome = publishIfCurrent(record, targetFile)
                val published = outcome == PublishOutcome.Published || outcome == PublishOutcome.AlreadyPublished
                AndroidAttachmentSystemCacheState(
                    cached = published,
                    active = false,
                    sizeBytes = if (published) targetFile.length() else 0L
                )
            }
            AndroidSystemDownloadStatus.Failed -> {
                finishFailedIfCurrent(record, removeBackend = true)
                AndroidAttachmentSystemCacheState(
                    cached = isTargetUsable(targetFile),
                    active = false,
                    sizeBytes = targetFile.takeIf(isTargetUsable)?.length() ?: 0L
                )
            }
            AndroidSystemDownloadStatus.Missing -> {
                finishFailedIfCurrent(record, removeBackend = false)
                AndroidAttachmentSystemCacheState(
                    cached = isTargetUsable(targetFile),
                    active = false,
                    sizeBytes = targetFile.takeIf(isTargetUsable)?.length() ?: 0L
                )
            }
        }
    }

    fun control(
        downloadId: String,
        ownerKey: String,
        expectedAttemptToken: String?,
        action: String
    ): String {
        val mappingKey = logicalMappingKey(downloadId, ownerKey)
        val record = activeByLogicalDownloadId[mappingKey] ?: return "missing"
        if (record.ownerKey != ownerKey) return "missing"
        if (!expectedAttemptToken.isNullOrBlank() && expectedAttemptToken != record.attemptToken) return "stale"
        return when (action.lowercase()) {
            "stop", "cancel" -> stopIfCurrent(record)
            "pause", "resume" -> "unsupported"
            else -> "failed"
        }
    }

    fun deleteTarget(targetFile: File, ownerKey: String): Boolean {
        val targetKey = targetFile.absolutePath
        return synchronized(targetLock(targetKey)) {
            val current = recordStore.read(targetKey)
            if (current != null && current.ownerKey != ownerKey) {
                return@synchronized false
            }
            if (current != null) {
                cancelledSystemIds += current.systemDownloadId
                backend.remove(current.systemDownloadId)
                recordStore.remove(targetKey)
                File(current.stagingPath).delete()
                removeLogicalMappings(current)
            }
            committedByTarget.remove(targetKey)
            publicationFile(targetFile).delete()
            targetFile.delete()
            true
        }
    }

    private fun startOrReuse(
        downloadId: String,
        ownerKey: String,
        targetFile: File,
        url: String,
        displayName: String,
        mimeType: String
    ): AndroidSystemDownloadRecord? {
        val targetKey = targetFile.absolutePath
        return synchronized(targetLock(targetKey)) {
            val existing = recordStore.read(targetKey)
            if (existing != null) {
                if (existing.ownerKey != ownerKey) return@synchronized null
                when (backend.query(existing.systemDownloadId).status) {
                    AndroidSystemDownloadStatus.Pending,
                    AndroidSystemDownloadStatus.Running,
                    AndroidSystemDownloadStatus.Paused,
                    AndroidSystemDownloadStatus.Successful -> {
                        activeByLogicalDownloadId[logicalMappingKey(downloadId, ownerKey)] = existing
                        return@synchronized existing
                    }
                    AndroidSystemDownloadStatus.Failed -> cleanupCurrentLocked(existing, removeBackend = true)
                    AndroidSystemDownloadStatus.Missing -> cleanupCurrentLocked(existing, removeBackend = false)
                }
            }
            targetFile.parentFile?.mkdirs()
            val nextStagingPath = stagingPath(targetKey, displayName)
            File(nextStagingPath).apply {
                parentFile?.mkdirs()
                delete()
            }
            val systemDownloadId =
                backend.enqueue(
                    AndroidSystemDownloadEnqueueRequest(
                        url = url,
                        displayName = displayName,
                        mimeType = mimeType,
                        stagingPath = nextStagingPath
                    )
                )
            val record =
                AndroidSystemDownloadRecord(
                    systemDownloadId = systemDownloadId,
                    targetKey = targetKey,
                    stagingPath = nextStagingPath,
                    mimeType = mimeType,
                    attemptToken = "android-system-$systemDownloadId",
                    ownerKey = ownerKey
                )
            recordStore.write(record)
            activeByLogicalDownloadId[logicalMappingKey(downloadId, ownerKey)] = record
            record
        }
    }

    private fun publishIfCurrent(
        record: AndroidSystemDownloadRecord,
        targetFile: File
    ): PublishOutcome =
        synchronized(targetLock(record.targetKey)) {
            val current = recordStore.read(record.targetKey)
            if (current != record) {
                return@synchronized if (current == null && isTargetUsable(targetFile)) {
                    PublishOutcome.AlreadyPublished
                } else {
                    PublishOutcome.Stale
                }
            }
            beforePublishCommit(record.attemptToken)
            if (isTargetUsable(targetFile)) {
                committedByTarget[record.targetKey] = record
                cleanupCurrentLocked(record, removeBackend = true)
                return@synchronized PublishOutcome.AlreadyPublished
            }
            val stagingFile = File(record.stagingPath)
            if (!stagingFile.isFile || stagingFile.length() <= 0L) {
                cleanupCurrentLocked(record, removeBackend = true)
                return@synchronized PublishOutcome.Failed
            }
            val publicationFile = publicationFile(targetFile)
            val published =
                runCatching {
                    targetFile.parentFile?.mkdirs()
                    publicationFile.delete()
                    FileInputStream(stagingFile).use { input ->
                        FileOutputStream(publicationFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    check(publicationFile.length() == stagingFile.length() && publicationFile.length() > 0L)
                    if (targetFile.exists()) check(targetFile.delete())
                    check(publicationFile.renameTo(targetFile))
                    true
                }.getOrElse {
                    publicationFile.delete()
                    false
                }
            if (published) committedByTarget[record.targetKey] = record
            cleanupCurrentLocked(record, removeBackend = true)
            if (published) PublishOutcome.Published else PublishOutcome.Failed
        }

    private fun finishSuccessfulIfCurrent(record: AndroidSystemDownloadRecord) {
        synchronized(targetLock(record.targetKey)) {
            if (recordStore.read(record.targetKey) == record) {
                committedByTarget[record.targetKey] = record
                cleanupCurrentLocked(record, removeBackend = true)
            }
        }
    }

    private fun finishFailedIfCurrent(
        record: AndroidSystemDownloadRecord,
        removeBackend: Boolean
    ) {
        synchronized(targetLock(record.targetKey)) {
            if (recordStore.read(record.targetKey) == record) {
                cleanupCurrentLocked(record, removeBackend)
            }
        }
    }

    private fun stopIfCurrent(record: AndroidSystemDownloadRecord): String =
        synchronized(targetLock(record.targetKey)) {
            when (val current = recordStore.read(record.targetKey)) {
                record -> {
                    cancelledSystemIds += record.systemDownloadId
                    backend.remove(record.systemDownloadId)
                    recordStore.remove(record.targetKey)
                    File(record.stagingPath).delete()
                    committedByTarget.remove(record.targetKey)
                    publicationFile(File(record.targetKey)).delete()
                    File(record.targetKey).delete()
                    removeLogicalMappings(record)
                    "cancelled"
                }
                null -> {
                    // A publisher may have committed while Stop was waiting for this target lock.
                    // Delete only when that exact captured attempt is still the last committer;
                    // a successor that already committed must remain untouched.
                    if (committedByTarget[record.targetKey] == record) {
                        committedByTarget.remove(record.targetKey, record)
                        publicationFile(File(record.targetKey)).delete()
                        File(record.targetKey).delete()
                        File(record.stagingPath).delete()
                        removeLogicalMappings(record)
                        "cancelled"
                    } else {
                        "stale"
                    }
                }
                else -> if (current.ownerKey == record.ownerKey) "stale" else "missing"
            }
        }

    private fun cleanupCurrentLocked(record: AndroidSystemDownloadRecord, removeBackend: Boolean) {
        check(recordStore.read(record.targetKey) == record)
        recordStore.remove(record.targetKey)
        if (removeBackend) backend.remove(record.systemDownloadId)
        File(record.stagingPath).delete()
        removeLogicalMappings(record)
    }

    private fun removeLogicalMappings(record: AndroidSystemDownloadRecord) {
        activeByLogicalDownloadId.entries.removeAll { (_, mapped) -> mapped == record }
    }

    private fun targetLock(targetKey: String): Any = targetLocks.getOrPut(targetKey) { Any() }

    private fun logicalMappingKey(downloadId: String, ownerKey: String): String = "$ownerKey:$downloadId"

    private fun publicationFile(targetFile: File): File =
        File(targetFile.parentFile, "${targetFile.name}.system-publish")
}

internal class AndroidAttachmentSystemDownloads(context: Context) {
    private val appContext = context.applicationContext
    private val backend = AndroidDownloadManagerBackend(appContext)
    private val recordStore = AndroidSharedPreferencesDownloadRecordStore(
        appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    )
    private val engine =
        AndroidAttachmentSystemDownloadEngine(
            backend = backend,
            recordStore = recordStore,
            stagingPath = { targetKey, displayName -> stagingFile(targetKey, displayName).absolutePath },
            isTargetUsable = { file ->
                file.isFile &&
                    file.length() > 0L &&
                    System.currentTimeMillis() - file.lastModified() <= AttachmentCacheMaxAgeMillis
            }
        )

    fun download(
        downloadId: String,
        ownerKey: String,
        targetFile: File,
        url: String,
        displayName: String,
        mimeType: String,
        onProgress: ((AndroidAttachmentSystemProgress) -> Unit)? = null
    ): AndroidAttachmentSystemResult =
        engine.download(downloadId, ownerKey, targetFile, url, displayName, mimeType, onProgress)

    fun cacheState(
        targetFile: File,
        ownerKey: String,
        logicalDownloadId: String? = null
    ): AndroidAttachmentSystemCacheState =
        engine.cacheState(targetFile, ownerKey, logicalDownloadId)

    fun control(downloadId: String, ownerKey: String, expectedAttemptToken: String?, action: String): String =
        engine.control(downloadId, ownerKey, expectedAttemptToken, action)

    fun deleteTarget(targetFile: File, ownerKey: String): Boolean = engine.deleteTarget(targetFile, ownerKey)

    private fun stagingFile(targetKey: String, displayName: String): File {
        val root =
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.externalCacheDir
                ?: error("External storage is unavailable")
        val safeName =
            displayName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .map { character -> if (character.isLetterOrDigit() || character in "._-") character else '_' }
                .joinToString("")
                .take(96)
                .ifBlank { "attachment" }
        return File(File(root, StagingDirectory), "${sha256(targetKey).take(24)}-$safeName")
    }

    private companion object {
        const val PreferencesName = "slock_attachment_system_downloads"
        const val StagingDirectory = "slock-attachment-system"
        const val AttachmentCacheMaxAgeMillis = 24L * 60L * 60L * 1_000L
    }
}

private class AndroidDownloadManagerBackend(context: Context) : AndroidSystemDownloadBackend {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
        ?: error("DownloadManager is unavailable")

    override fun enqueue(request: AndroidSystemDownloadEnqueueRequest): Long {
        val stagingFile = File(request.stagingPath)
        stagingFile.parentFile?.mkdirs()
        val downloadRequest =
            DownloadManager.Request(Uri.parse(request.url))
                .setTitle(request.displayName)
                .setDescription("Downloading attachment")
                .setMimeType(request.mimeType)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(stagingFile))
        return downloadManager.enqueue(downloadRequest)
    }

    override fun query(systemDownloadId: Long): AndroidSystemDownloadSnapshot {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(systemDownloadId))
            ?: return AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Missing)
        cursor.use {
            if (!it.moveToFirst()) return AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Missing)
            val status =
                when (it.intColumn(DownloadManager.COLUMN_STATUS)) {
                    DownloadManager.STATUS_PENDING -> AndroidSystemDownloadStatus.Pending
                    DownloadManager.STATUS_RUNNING -> AndroidSystemDownloadStatus.Running
                    DownloadManager.STATUS_PAUSED -> AndroidSystemDownloadStatus.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> AndroidSystemDownloadStatus.Successful
                    DownloadManager.STATUS_FAILED -> AndroidSystemDownloadStatus.Failed
                    else -> AndroidSystemDownloadStatus.Missing
                }
            val total = it.longColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { value -> value > 0L }
            return AndroidSystemDownloadSnapshot(
                status = status,
                downloadedBytes = it.longColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0L),
                totalBytes = total,
                reason = it.intColumn(DownloadManager.COLUMN_REASON).takeIf { value -> value != 0 }
            )
        }
    }

    override fun remove(systemDownloadId: Long) {
        runCatching { downloadManager.remove(systemDownloadId) }
    }
}

private class AndroidSharedPreferencesDownloadRecordStore(
    private val preferences: SharedPreferences
) : AndroidSystemDownloadRecordStore {
    override fun read(targetKey: String): AndroidSystemDownloadRecord? {
        val payload = preferences.getString(preferenceKey(targetKey), null) ?: return null
        return runCatching {
            val json = JSONObject(payload)
            if (json.getString("targetKey") != targetKey) return null
            AndroidSystemDownloadRecord(
                systemDownloadId = json.getLong("systemDownloadId"),
                targetKey = targetKey,
                stagingPath = json.getString("stagingPath"),
                mimeType = json.optString("mimeType").ifBlank { "application/octet-stream" },
                attemptToken = json.getString("attemptToken"),
                ownerKey = json.getString("ownerKey").takeIf { it.isNotBlank() } ?: return null
            )
        }.getOrNull()
    }

    override fun write(record: AndroidSystemDownloadRecord) {
        val payload =
            JSONObject()
                .put("systemDownloadId", record.systemDownloadId)
                .put("targetKey", record.targetKey)
                .put("stagingPath", record.stagingPath)
                .put("mimeType", record.mimeType)
                .put("attemptToken", record.attemptToken)
                .put("ownerKey", record.ownerKey)
                .toString()
        preferences.edit().putString(preferenceKey(record.targetKey), payload).commit()
    }

    override fun remove(targetKey: String) {
        preferences.edit().remove(preferenceKey(targetKey)).commit()
    }

    private fun preferenceKey(targetKey: String): String = "target_${sha256(targetKey)}"
}

private fun android.database.Cursor.intColumn(name: String): Int {
    val index = getColumnIndex(name)
    return if (index >= 0) getInt(index) else 0
}

private fun android.database.Cursor.longColumn(name: String): Long {
    val index = getColumnIndex(name)
    return if (index >= 0) getLong(index) else 0L
}

private fun androidSystemDownloadPercent(downloadedBytes: Long, totalBytes: Long?): Int? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    if (downloadedBytes <= 0L) return null
    if (downloadedBytes >= total) return 100
    return ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(1, 99)
}

private fun androidSystemDownloadBytesPerSecond(byteDelta: Long, elapsedMillis: Long): Long {
    if (byteDelta <= 0L) return 0L
    val elapsed = elapsedMillis.coerceAtLeast(1L)
    return if (byteDelta > Long.MAX_VALUE / 1_000L) {
        Long.MAX_VALUE
    } else {
        (byteDelta * 1_000L) / elapsed
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun androidAttachmentOwnerKey(ownerFingerprint: String): String =
    sha256(ownerFingerprint.trim())
