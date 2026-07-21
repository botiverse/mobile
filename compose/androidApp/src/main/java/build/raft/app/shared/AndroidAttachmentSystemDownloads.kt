package build.raft.app.shared

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal enum class AndroidSystemDownloadPhase { Prepared, Enqueued, Complete }

internal data class AndroidSystemDownloadRecord(
    val targetKey: String,
    val principalKey: String,
    val logicalDownloadId: String,
    val requestToken: String,
    val attemptToken: String,
    val recordVersion: Long,
    val phase: AndroidSystemDownloadPhase,
    val url: String,
    val displayName: String,
    val mimeType: String,
    val stagingPath: String,
    val systemDownloadId: Long? = null,
    val committedAtMillis: Long? = null
)

internal data class AndroidSystemDownloadRow(
    val systemDownloadId: Long,
    val requestToken: String,
    val localPath: String?,
    val status: AndroidSystemDownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val reason: Int = 0
)

internal enum class AndroidSystemDownloadStatus { Pending, Running, Paused, Successful, Failed, Missing }

internal data class AndroidSystemDownloadEnqueueRequest(
    val requestToken: String,
    val url: String,
    val displayName: String,
    val mimeType: String,
    val stagingPath: String
)

internal interface AndroidSystemDownloadBackend {
    fun enqueue(request: AndroidSystemDownloadEnqueueRequest): Long
    fun taggedRows(): List<AndroidSystemDownloadRow>
    fun query(systemDownloadId: Long): AndroidSystemDownloadRow
    fun remove(systemDownloadId: Long)
}

internal interface AndroidSystemDownloadRecordStore {
    fun records(): List<AndroidSystemDownloadRecord>
    fun read(targetKey: String): AndroidSystemDownloadRecord?
    fun putIfAbsent(record: AndroidSystemDownloadRecord): AndroidSystemDownloadRecord
    fun compareAndSet(expected: AndroidSystemDownloadRecord, updated: AndroidSystemDownloadRecord): Boolean
    fun removeIfExact(expected: AndroidSystemDownloadRecord): Boolean
}

internal data class AndroidSystemDownloadProgress(
    val logicalDownloadId: String,
    val attemptToken: String,
    val recordVersion: Long,
    val phase: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int?
)

internal data class AndroidSystemDownloadResult(
    val status: String,
    val attemptToken: String? = null,
    val recordVersion: Long? = null,
    val cached: Boolean = false,
    val sizeBytes: Long = 0L,
    val errorKind: String? = null
)

internal data class AndroidSystemDownloadSpec(
    val principalKey: String,
    val logicalDownloadId: String,
    val targetFile: File,
    val stagingDirectory: File,
    val url: String,
    val displayName: String,
    val mimeType: String
)

/**
 * Process owner for Android DownloadManager attachment jobs.
 *
 * Lock order is mechanical: recovery lock -> target lock. Owner/auth gates are never acquired by
 * this engine. Private-cache publication is principal-owned and may finish after its UI scope retires.
 */
internal class AndroidAttachmentSystemDownloadEngine(
    private val backend: AndroidSystemDownloadBackend,
    private val recordStore: AndroidSystemDownloadRecordStore,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
    private val pollIntervalMillis: Long = 250L,
    private val cacheMaxAgeMillis: Long = TimeUnit.HOURS.toMillis(24),
    private val afterPreparedPersisted: (AndroidSystemDownloadRecord) -> Unit = {},
    private val afterEnqueueCreated: (AndroidSystemDownloadRecord, Long) -> Unit = { _, _ -> },
    private val afterEnqueuedCommitted: (AndroidSystemDownloadRecord) -> Unit = {},
    private val beforePublishCommit: (AndroidSystemDownloadRecord) -> Unit = {}
) {
    private val recoveryLocks = ConcurrentHashMap<String, Any>()
    private val targetLocks = ConcurrentHashMap<String, Any>()

    fun download(
        spec: AndroidSystemDownloadSpec,
        observer: ((AndroidSystemDownloadProgress) -> Unit)? = null
    ): AndroidSystemDownloadResult {
        val record = ensureRecord(spec)
        if (record.principalKey != spec.principalKey) {
            return AndroidSystemDownloadResult(status = "owner_mismatch", errorKind = "owner_mismatch")
        }
        if (record.phase == AndroidSystemDownloadPhase.Complete && spec.targetFile.isUsable(cacheMaxAgeMillis)) {
            return AndroidSystemDownloadResult(
                status = "complete",
                attemptToken = record.attemptToken,
                recordVersion = record.recordVersion,
                cached = true,
                sizeBytes = spec.targetFile.length()
            )
        }
        val enqueued = reconcile(record)
        val systemId = enqueued.systemDownloadId
            ?: return AndroidSystemDownloadResult(status = "failed", errorKind = "missing_system_id")
        while (true) {
            val row = backend.query(systemId)
            observer?.invoke(row.toProgress(enqueued))
            when (row.status) {
                AndroidSystemDownloadStatus.Pending,
                AndroidSystemDownloadStatus.Running,
                AndroidSystemDownloadStatus.Paused -> sleepMillis(pollIntervalMillis)
                AndroidSystemDownloadStatus.Successful -> return publishIfCurrent(enqueued, spec.targetFile)
                AndroidSystemDownloadStatus.Failed,
                AndroidSystemDownloadStatus.Missing -> {
                    synchronized(targetLock(enqueued.targetKey)) {
                        recordStore.removeIfExact(enqueued)
                        File(enqueued.stagingPath).delete()
                    }
                    return AndroidSystemDownloadResult(
                        status = "failed",
                        attemptToken = enqueued.attemptToken,
                        recordVersion = enqueued.recordVersion,
                        errorKind = row.status.name.lowercase()
                    )
                }
            }
        }
    }

    fun cacheState(spec: AndroidSystemDownloadSpec): AndroidSystemDownloadResult {
        val existing = recordStore.read(spec.targetFile.absolutePath)
        if (existing == null) {
            return AndroidSystemDownloadResult(
                status = "ok",
                cached = spec.targetFile.isUsable(cacheMaxAgeMillis),
                sizeBytes = if (spec.targetFile.isUsable(cacheMaxAgeMillis)) spec.targetFile.length() else 0L
            )
        }
        if (existing.principalKey != spec.principalKey) {
            return AndroidSystemDownloadResult(status = "owner_mismatch", errorKind = "owner_mismatch")
        }
        if (existing.phase == AndroidSystemDownloadPhase.Complete) {
            val cached = spec.targetFile.isUsable(cacheMaxAgeMillis)
            return AndroidSystemDownloadResult(
                status = "ok",
                attemptToken = existing.attemptToken,
                recordVersion = existing.recordVersion,
                cached = cached,
                sizeBytes = if (cached) spec.targetFile.length() else 0L
            )
        }
        val active = reconcile(existing)
        val row = active.systemDownloadId?.let(backend::query)
        if (row?.status == AndroidSystemDownloadStatus.Successful) return publishIfCurrent(active, spec.targetFile)
        return AndroidSystemDownloadResult(
            status = "ok",
            attemptToken = active.attemptToken,
            recordVersion = active.recordVersion,
            cached = false,
            errorKind = if (row == null) "missing" else null
        )
    }

    fun control(
        principalKey: String,
        logicalDownloadId: String,
        expectedAttemptToken: String?,
        expectedRecordVersion: Long?,
        action: String
    ): String {
        if (expectedAttemptToken.isNullOrBlank() || expectedRecordVersion == null) return "missing"
        val record = recordStore.records().firstOrNull {
            it.principalKey == principalKey &&
                it.logicalDownloadId == logicalDownloadId &&
                it.attemptToken == expectedAttemptToken &&
                it.recordVersion == expectedRecordVersion
        } ?: return "missing"
        return synchronized(targetLock(record.targetKey)) {
            val current = recordStore.read(record.targetKey) ?: return@synchronized "missing"
            if (current != record) return@synchronized "missing"
            when (action.lowercase()) {
                "cancel", "stop" -> {
                    current.systemDownloadId?.let(backend::remove)
                    File(current.stagingPath).delete()
                    recordStore.removeIfExact(current)
                    "cancelled"
                }
                "delete" -> {
                    current.systemDownloadId?.let(backend::remove)
                    File(current.stagingPath).delete()
                    File(current.targetKey).delete()
                    recordStore.removeIfExact(current)
                    "deleted"
                }
                "pause", "resume" -> "unsupported"
                else -> "failed"
            }
        }
    }

    fun deleteTarget(
        principalKey: String,
        targetFile: File,
        expectedAttemptToken: String?,
        expectedRecordVersion: Long?
    ): String = synchronized(targetLock(targetFile.absolutePath)) {
        val current = recordStore.read(targetFile.absolutePath)
        if (current == null) {
            return@synchronized if (targetFile.delete() || !targetFile.exists()) "deleted" else "failed"
        }
        if (current.principalKey != principalKey) return@synchronized "missing"
        if (expectedAttemptToken.isNullOrBlank() || expectedRecordVersion == null) return@synchronized "missing"
        if (expectedAttemptToken != current.attemptToken) return@synchronized "missing"
        if (expectedRecordVersion != current.recordVersion) return@synchronized "missing"
        current.systemDownloadId?.let(backend::remove)
        File(current.stagingPath).delete()
        if (targetFile.exists() && !targetFile.delete()) return@synchronized "failed"
        if (!recordStore.removeIfExact(current)) return@synchronized "missing"
        "deleted"
    }

    fun recoverAll() {
        val records = recordStore.records()
        records.filter { it.phase != AndroidSystemDownloadPhase.Complete }.forEach(::reconcile)
        records.filter { it.phase == AndroidSystemDownloadPhase.Complete }.forEach { complete ->
            backend.taggedRows()
                .filter { it.requestToken == complete.requestToken }
                .forEach { row -> runCatching { backend.remove(row.systemDownloadId) } }
            File(complete.stagingPath).delete()
        }
        val knownTokens = recordStore.records().mapTo(mutableSetOf()) { it.requestToken }
        backend.taggedRows()
            .filter { it.requestToken !in knownTokens }
            .forEach { row ->
                backend.remove(row.systemDownloadId)
                row.localPath?.let { File(it).delete() }
            }
    }

    private fun ensureRecord(spec: AndroidSystemDownloadSpec): AndroidSystemDownloadRecord =
        synchronized(recoveryLock(spec.targetFile.absolutePath)) {
            synchronized(targetLock(spec.targetFile.absolutePath)) {
                val existing = recordStore.read(spec.targetFile.absolutePath)
                if (existing != null && existing.principalKey != spec.principalKey) {
                    return@synchronized existing
                }
                if (existing?.phase == AndroidSystemDownloadPhase.Complete) {
                    if (spec.targetFile.isUsable(cacheMaxAgeMillis)) return@synchronized existing
                    recordStore.removeIfExact(existing)
                    spec.targetFile.delete()
                }
                val current = recordStore.read(spec.targetFile.absolutePath)
                if (current != null) return@synchronized current
                spec.targetFile.parentFile?.mkdirs()
                val token = tokenFactory()
                val record = AndroidSystemDownloadRecord(
                    targetKey = spec.targetFile.absolutePath,
                    principalKey = spec.principalKey,
                    logicalDownloadId = spec.logicalDownloadId,
                    requestToken = token,
                    attemptToken = "android-system-$token",
                    recordVersion = 1L,
                    phase = AndroidSystemDownloadPhase.Prepared,
                    url = spec.url,
                    displayName = spec.displayName,
                    mimeType = spec.mimeType,
                    stagingPath = File(
                        spec.stagingDirectory,
                        ".${spec.principalKey}.${spec.targetFile.name}.$token.part"
                    ).absolutePath
                )
                spec.stagingDirectory.mkdirs()
                recordStore.putIfAbsent(record).also(afterPreparedPersisted)
            }
        }

    private fun reconcile(record: AndroidSystemDownloadRecord): AndroidSystemDownloadRecord =
        synchronized(recoveryLock(record.targetKey)) {
            synchronized(targetLock(record.targetKey)) {
                var current = recordStore.read(record.targetKey) ?: return@synchronized record
                if (current.phase == AndroidSystemDownloadPhase.Complete) return@synchronized current
                val rows = backend.taggedRows().filter { it.requestToken == current.requestToken }
                val winner = rows.winner()
                val systemId = winner?.systemDownloadId ?: backend.enqueue(current.toEnqueueRequest()).also { id ->
                    afterEnqueueCreated(current, id)
                }
                rows.filter { it.systemDownloadId != systemId }.forEach { backend.remove(it.systemDownloadId) }
                if (current.phase == AndroidSystemDownloadPhase.Prepared || current.systemDownloadId != systemId) {
                    val updated = current.copy(
                        phase = AndroidSystemDownloadPhase.Enqueued,
                        systemDownloadId = systemId,
                        recordVersion = current.recordVersion + 1L
                    )
                    if (recordStore.compareAndSet(current, updated)) {
                        current = updated
                        afterEnqueuedCommitted(updated)
                    } else {
                        current = recordStore.read(record.targetKey) ?: current
                    }
                }
                current
            }
        }

    private fun publishIfCurrent(
        record: AndroidSystemDownloadRecord,
        targetFile: File
    ): AndroidSystemDownloadResult =
        synchronized(targetLock(record.targetKey)) {
            val current = recordStore.read(record.targetKey)
                ?: return@synchronized AndroidSystemDownloadResult(status = "stale", errorKind = "stale_record")
            if (current != record) {
                if (
                    current.principalKey == record.principalKey &&
                    current.attemptToken == record.attemptToken &&
                    current.phase == AndroidSystemDownloadPhase.Complete &&
                    targetFile.isUsable(cacheMaxAgeMillis)
                ) {
                    return@synchronized AndroidSystemDownloadResult(
                        status = "complete",
                        attemptToken = current.attemptToken,
                        recordVersion = current.recordVersion,
                        cached = true,
                        sizeBytes = targetFile.length()
                    )
                }
                return@synchronized AndroidSystemDownloadResult(status = "stale", errorKind = "stale_record")
            }
            val staging = File(record.stagingPath)
            if (!staging.isFile || staging.length() <= 0L) {
                return@synchronized AndroidSystemDownloadResult(status = "failed", errorKind = "missing_staging")
            }
            beforePublishCommit(record)
            targetFile.parentFile?.mkdirs()
            val privateStaging = File(targetFile.parentFile, ".${targetFile.name}.${record.requestToken}.publish")
            runCatching { privateStaging.delete() }
            FileInputStream(staging).use { input ->
                FileOutputStream(privateStaging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (targetFile.exists() && !targetFile.delete()) {
                privateStaging.delete()
                return@synchronized AndroidSystemDownloadResult(status = "failed", errorKind = "target_delete")
            }
            if (!privateStaging.renameTo(targetFile)) {
                privateStaging.delete()
                return@synchronized AndroidSystemDownloadResult(status = "failed", errorKind = "publish_rename")
            }
            val complete = record.copy(
                phase = AndroidSystemDownloadPhase.Complete,
                systemDownloadId = null,
                recordVersion = record.recordVersion + 1L,
                committedAtMillis = nowMillis()
            )
            if (!recordStore.compareAndSet(record, complete)) {
                targetFile.delete()
                return@synchronized AndroidSystemDownloadResult(status = "stale", errorKind = "stale_commit")
            }
            record.systemDownloadId?.let { id -> runCatching { backend.remove(id) } }
            staging.delete()
            AndroidSystemDownloadResult(
                status = "complete",
                attemptToken = complete.attemptToken,
                recordVersion = complete.recordVersion,
                cached = true,
                sizeBytes = targetFile.length()
            )
        }

    private fun recoveryLock(targetKey: String): Any = recoveryLocks.computeIfAbsent(targetKey) { Any() }
    private fun targetLock(targetKey: String): Any = targetLocks.computeIfAbsent(targetKey) { Any() }

    private fun AndroidSystemDownloadRecord.toEnqueueRequest() =
        AndroidSystemDownloadEnqueueRequest(
            requestToken = requestToken,
            url = url,
            displayName = displayName,
            mimeType = mimeType,
            stagingPath = stagingPath
        )

    private fun AndroidSystemDownloadRow.toProgress(record: AndroidSystemDownloadRecord): AndroidSystemDownloadProgress {
        val percent = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else null
        return AndroidSystemDownloadProgress(
            logicalDownloadId = record.logicalDownloadId,
            attemptToken = record.attemptToken,
            recordVersion = record.recordVersion,
            phase = status.name.lowercase(),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            percent = percent
        )
    }

    private fun List<AndroidSystemDownloadRow>.winner(): AndroidSystemDownloadRow? =
        sortedWith(
            compareByDescending<AndroidSystemDownloadRow> { it.status == AndroidSystemDownloadStatus.Successful }
                .thenBy { it.systemDownloadId }
        ).firstOrNull()

    private fun File.isUsable(maxAgeMillis: Long): Boolean =
        isFile && length() > 0L && nowMillis() - lastModified() <= maxAgeMillis
}

internal class AndroidAttachmentSystemDownloads(context: Context) {
    private val appContext = context.applicationContext
    private val engine = AndroidAttachmentSystemDownloadEngine(
        backend = AndroidDownloadManagerBackend(appContext),
        recordStore = AtomicAndroidSystemDownloadRecordStore(
            File(appContext.noBackupFilesDir, "attachment-system-downloads.json")
        )
    )

    init {
        engine.recoverAll()
    }

    fun download(spec: AndroidSystemDownloadSpec, observer: ((AndroidSystemDownloadProgress) -> Unit)? = null) =
        engine.download(spec, observer)

    fun cacheState(spec: AndroidSystemDownloadSpec) = engine.cacheState(spec)

    fun control(
        principalKey: String,
        logicalDownloadId: String,
        expectedAttemptToken: String?,
        expectedRecordVersion: Long?,
        action: String
    ) = engine.control(principalKey, logicalDownloadId, expectedAttemptToken, expectedRecordVersion, action)

    fun deleteTarget(
        principalKey: String,
        targetFile: File,
        expectedAttemptToken: String?,
        expectedRecordVersion: Long?
    ) = engine.deleteTarget(principalKey, targetFile, expectedAttemptToken, expectedRecordVersion)
}

internal object ProcessAndroidAttachmentSystemDownloads {
    @Volatile private var instance: AndroidAttachmentSystemDownloads? = null

    fun get(context: Context): AndroidAttachmentSystemDownloads =
        instance ?: synchronized(this) {
            instance ?: AndroidAttachmentSystemDownloads(context.applicationContext).also { instance = it }
        }
}

private const val ANDROID_ATTACHMENT_TOKEN_PREFIX = "raft-attachment:"

private class AndroidDownloadManagerBackend(context: Context) : AndroidSystemDownloadBackend {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override fun enqueue(request: AndroidSystemDownloadEnqueueRequest): Long {
        File(request.stagingPath).parentFile?.mkdirs()
        return manager.enqueue(
            DownloadManager.Request(Uri.parse(request.url))
                .setTitle(request.displayName)
                .setDescription(ANDROID_ATTACHMENT_TOKEN_PREFIX + request.requestToken)
                .setMimeType(request.mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(File(request.stagingPath)))
        )
    }

    override fun taggedRows(): List<AndroidSystemDownloadRow> {
        val cursor = manager.query(DownloadManager.Query()) ?: return emptyList()
        return cursor.use {
            val rows = mutableListOf<AndroidSystemDownloadRow>()
            val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val descriptionIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)
            val localUriIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            while (cursor.moveToNext()) {
                val description = cursor.getString(descriptionIndex).orEmpty()
                if (!description.startsWith(ANDROID_ATTACHMENT_TOKEN_PREFIX)) continue
                rows += AndroidSystemDownloadRow(
                    systemDownloadId = cursor.getLong(idIndex),
                    requestToken = description.removePrefix(ANDROID_ATTACHMENT_TOKEN_PREFIX),
                    localPath = cursor.getString(localUriIndex)?.let(Uri::parse)?.path,
                    status = cursor.getInt(statusIndex).toSystemStatus(),
                    downloadedBytes = cursor.getLong(downloadedIndex),
                    totalBytes = cursor.getLong(totalIndex),
                    reason = cursor.getInt(reasonIndex)
                )
            }
            rows
        }
    }

    override fun query(systemDownloadId: Long): AndroidSystemDownloadRow =
        taggedRows().firstOrNull { it.systemDownloadId == systemDownloadId }
            ?: AndroidSystemDownloadRow(systemDownloadId, "", null, AndroidSystemDownloadStatus.Missing)

    override fun remove(systemDownloadId: Long) {
        manager.remove(systemDownloadId)
    }

    private fun Int.toSystemStatus(): AndroidSystemDownloadStatus =
        when (this) {
            DownloadManager.STATUS_PENDING -> AndroidSystemDownloadStatus.Pending
            DownloadManager.STATUS_RUNNING -> AndroidSystemDownloadStatus.Running
            DownloadManager.STATUS_PAUSED -> AndroidSystemDownloadStatus.Paused
            DownloadManager.STATUS_SUCCESSFUL -> AndroidSystemDownloadStatus.Successful
            DownloadManager.STATUS_FAILED -> AndroidSystemDownloadStatus.Failed
            else -> AndroidSystemDownloadStatus.Missing
        }
}

private class AtomicAndroidSystemDownloadRecordStore(file: File) : AndroidSystemDownloadRecordStore {
    private val lock = Any()
    private val atomicFile = AtomicFile(file)

    override fun records(): List<AndroidSystemDownloadRecord> = synchronized(lock) { readLocked().values.toList() }

    override fun read(targetKey: String): AndroidSystemDownloadRecord? = synchronized(lock) { readLocked()[targetKey] }

    override fun putIfAbsent(record: AndroidSystemDownloadRecord): AndroidSystemDownloadRecord = synchronized(lock) {
        val records = readLocked().toMutableMap()
        records[record.targetKey]?.let { return@synchronized it }
        records[record.targetKey] = record
        writeLocked(records)
        record
    }

    override fun compareAndSet(expected: AndroidSystemDownloadRecord, updated: AndroidSystemDownloadRecord): Boolean =
        synchronized(lock) {
            val records = readLocked().toMutableMap()
            if (records[expected.targetKey] != expected) return@synchronized false
            records[expected.targetKey] = updated
            writeLocked(records)
            true
        }

    override fun removeIfExact(expected: AndroidSystemDownloadRecord): Boolean = synchronized(lock) {
        val records = readLocked().toMutableMap()
        if (records[expected.targetKey] != expected) return@synchronized false
        records.remove(expected.targetKey)
        writeLocked(records)
        true
    }

    private fun readLocked(): Map<String, AndroidSystemDownloadRecord> {
        if (!atomicFile.baseFile.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(atomicFile.openRead().bufferedReader().use { it.readText() })
            val array = root.optJSONArray("records") ?: JSONArray()
            buildMap {
                repeat(array.length()) {
                    val record = array.getJSONObject(it).toRecord()
                    put(record.targetKey, record)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeLocked(records: Map<String, AndroidSystemDownloadRecord>) {
        atomicFile.baseFile.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            val array = JSONArray()
            records.values.forEach { array.put(it.toJson()) }
            stream.write(JSONObject().put("records", array).toString().encodeToByteArray())
            atomicFile.finishWrite(stream)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(stream)
            throw throwable
        }
    }

    private fun AndroidSystemDownloadRecord.toJson() = JSONObject()
        .put("targetKey", targetKey)
        .put("principalKey", principalKey)
        .put("logicalDownloadId", logicalDownloadId)
        .put("requestToken", requestToken)
        .put("attemptToken", attemptToken)
        .put("recordVersion", recordVersion)
        .put("phase", phase.name)
        .put("url", url)
        .put("displayName", displayName)
        .put("mimeType", mimeType)
        .put("stagingPath", stagingPath)
        .put("systemDownloadId", systemDownloadId)
        .put("committedAtMillis", committedAtMillis)

    private fun JSONObject.toRecord() = AndroidSystemDownloadRecord(
        targetKey = getString("targetKey"),
        principalKey = getString("principalKey"),
        logicalDownloadId = getString("logicalDownloadId"),
        requestToken = getString("requestToken"),
        attemptToken = getString("attemptToken"),
        recordVersion = getLong("recordVersion"),
        phase = AndroidSystemDownloadPhase.valueOf(getString("phase")),
        url = getString("url"),
        displayName = getString("displayName"),
        mimeType = getString("mimeType"),
        stagingPath = getString("stagingPath"),
        systemDownloadId = if (isNull("systemDownloadId")) null else getLong("systemDownloadId"),
        committedAtMillis = if (isNull("committedAtMillis")) null else getLong("committedAtMillis")
    )
}
