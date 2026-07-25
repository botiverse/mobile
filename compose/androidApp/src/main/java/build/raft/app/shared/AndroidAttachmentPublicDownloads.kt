package build.raft.app.shared

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

internal const val ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME = "slock-attachments"

internal fun clearAndroidAttachmentPrivateStaging(directory: File): Boolean {
    if (!directory.exists()) return true
    if (!directory.isDirectory) return directory.delete() || !directory.exists()
    val children = directory.listFiles() ?: return false
    return children.all { child -> child.deleteRecursively() || !child.exists() }
}

internal data class AndroidAttachmentPublicIdentity(
    val principalKey: String,
    val attachmentId: String
) {
    init {
        require(principalKey.length == 64)
        require(attachmentId.isNotBlank())
    }

    val storageKey: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest("$principalKey\u0000$attachmentId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal enum class AndroidAttachmentPublicRecordPhase {
    Prepared,
    Committed
}

internal data class AndroidAttachmentPublicRecord(
    val identityKey: String,
    val generation: Long,
    val phase: AndroidAttachmentPublicRecordPhase,
    val uri: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

internal data class AndroidAttachmentPublicRecordsSnapshot(
    val records: Map<String, AndroidAttachmentPublicRecord> = emptyMap(),
    val lastGenerations: Map<String, Long> = emptyMap()
)

internal interface AndroidAttachmentPublicRecordStore {
    fun read(): AndroidAttachmentPublicRecordsSnapshot
    fun write(snapshot: AndroidAttachmentPublicRecordsSnapshot): Boolean
}

internal enum class AndroidAttachmentPublicUriAvailability {
    Published,
    Pending,
    Missing,
    Unknown
}

internal data class AndroidAttachmentPublicUriState(
    val availability: AndroidAttachmentPublicUriAvailability,
    val displayName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val relativePath: String = ""
)

internal interface AndroidAttachmentPublicUriBackend {
    fun inspectExact(uri: String): AndroidAttachmentPublicUriState
    fun deleteExact(uri: String): Boolean
}

internal data class AndroidAttachmentPublicOpenSpec(
    val uri: String,
    val mimeType: String,
    val displayName: String,
    val action: String,
    val grantsReadPermission: Boolean
)

internal fun androidAttachmentPublicOpenSpec(
    record: AndroidAttachmentPublicRecord,
    packageArchive: Boolean
): AndroidAttachmentPublicOpenSpec =
    AndroidAttachmentPublicOpenSpec(
        uri = checkNotNull(record.uri),
        mimeType = record.mimeType,
        displayName = record.displayName,
        action = if (packageArchive) "android.intent.action.INSTALL_PACKAGE" else "android.intent.action.VIEW",
        grantsReadPermission = true
    )

/**
 * Durable authority for the one public attachment copy.
 *
 * The public URI is never rediscovered by name or path. A Prepared record is a non-authoritative
 * crash journal; only a matching Committed record can be opened or reported as downloaded.
 */
internal class AndroidAttachmentPublicDownloadsAuthority(
    private val store: AndroidAttachmentPublicRecordStore,
    private val backend: AndroidAttachmentPublicUriBackend
) {
    private val stateLock = Any()
    private val identityLocks = ConcurrentHashMap<String, ReentrantLock>()
    private var snapshot = store.read()

    fun <T> withIdentityLock(identity: AndroidAttachmentPublicIdentity, block: () -> T): T =
        identityLocks.computeIfAbsent(identity.storageKey) { ReentrantLock(true) }.withLock(block)

    fun resolve(identity: AndroidAttachmentPublicIdentity): AndroidAttachmentPublicRecord? =
        synchronized(stateLock) {
            val record = snapshot.records[identity.storageKey] ?: return@synchronized null
            when (record.phase) {
                AndroidAttachmentPublicRecordPhase.Prepared -> {
                    if (!rollbackPreparedLocked(record)) {
                        throw AndroidAttachmentPublicStorageException("Prepared public attachment cleanup failed")
                    }
                    null
                }
                AndroidAttachmentPublicRecordPhase.Committed -> {
                    val state = record.uri?.let(backend::inspectExact)
                        ?: AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Missing)
                    when (state.availability) {
                        AndroidAttachmentPublicUriAvailability.Published -> {
                            if (state.matches(record)) {
                                record
                            } else {
                                if (!removeRecordLocked(record.identityKey)) {
                                    throw AndroidAttachmentPublicStorageException("Stale public attachment mapping cleanup failed")
                                }
                                null
                            }
                        }
                        AndroidAttachmentPublicUriAvailability.Missing -> {
                            if (!removeRecordLocked(record.identityKey)) {
                                throw AndroidAttachmentPublicStorageException("Missing public attachment mapping cleanup failed")
                            }
                            null
                        }
                        AndroidAttachmentPublicUriAvailability.Pending,
                        AndroidAttachmentPublicUriAvailability.Unknown -> {
                            throw AndroidAttachmentPublicStorageException("Public attachment state could not be verified")
                        }
                    }
                }
            }
        }

    fun begin(
        identity: AndroidAttachmentPublicIdentity,
        displayName: String,
        mimeType: String
    ): AndroidAttachmentPublicRecord = synchronized(stateLock) {
        check(snapshot.records[identity.storageKey] == null) { "Attachment identity already has a transaction" }
        val generation = (snapshot.lastGenerations[identity.storageKey] ?: 0L) + 1L
        val prepared =
            AndroidAttachmentPublicRecord(
                identityKey = identity.storageKey,
                generation = generation,
                phase = AndroidAttachmentPublicRecordPhase.Prepared,
                uri = null,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = 0L
            )
        val next =
            snapshot.copy(
                records = snapshot.records + (identity.storageKey to prepared),
                lastGenerations = snapshot.lastGenerations + (identity.storageKey to generation)
            )
        if (!store.write(next)) throw AndroidAttachmentPublicStorageException("Unable to start public attachment transaction")
        snapshot = next
        prepared
    }

    fun attachExactUri(prepared: AndroidAttachmentPublicRecord, uri: String): AndroidAttachmentPublicRecord? =
        synchronized(stateLock) {
            val current = currentPreparedLocked(prepared) ?: return@synchronized null
            if (current.uri != null && current.uri != uri) return@synchronized null
            val attached = current.copy(uri = uri)
            val next = snapshot.copy(records = snapshot.records + (current.identityKey to attached))
            if (!store.write(next)) return@synchronized null
            snapshot = next
            attached
        }

    fun commit(prepared: AndroidAttachmentPublicRecord, uri: String, sizeBytes: Long): AndroidAttachmentPublicRecord? =
        synchronized(stateLock) {
            val current = currentPreparedLocked(prepared) ?: return@synchronized null
            if (current.uri != uri || sizeBytes <= 0L) return@synchronized null
            val committed =
                current.copy(
                    phase = AndroidAttachmentPublicRecordPhase.Committed,
                    sizeBytes = sizeBytes
                )
            if (!backend.inspectExact(uri).matches(committed)) return@synchronized null
            val next = snapshot.copy(records = snapshot.records + (current.identityKey to committed))
            if (!store.write(next)) return@synchronized null
            snapshot = next
            committed
        }

    fun verifyPublished(prepared: AndroidAttachmentPublicRecord, uri: String, sizeBytes: Long): Boolean =
        synchronized(stateLock) {
            val current = currentPreparedLocked(prepared) ?: return@synchronized false
            current.uri == uri &&
                sizeBytes > 0L &&
                backend.inspectExact(uri).matches(current.copy(sizeBytes = sizeBytes))
        }

    fun abort(prepared: AndroidAttachmentPublicRecord): Boolean =
        synchronized(stateLock) {
            val current = currentPreparedLocked(prepared) ?: return@synchronized true
            rollbackPreparedLocked(current)
        }

    fun delete(identity: AndroidAttachmentPublicIdentity): Boolean =
        synchronized(stateLock) {
            val record = snapshot.records[identity.storageKey] ?: return@synchronized true
            val uri = record.uri
            if (uri != null && !deleteAndConfirmMissing(uri)) return@synchronized false
            removeRecordLocked(record.identityKey)
        }

    fun recoverPrepared(): Boolean {
        val identities =
            synchronized(stateLock) {
                snapshot.records.values
                    .filter { it.phase == AndroidAttachmentPublicRecordPhase.Prepared }
                    .map { it.identityKey }
            }
        identities.forEach { identityKey ->
            identityLocks.computeIfAbsent(identityKey) { ReentrantLock(true) }.withLock {
                synchronized(stateLock) {
                    snapshot.records[identityKey]
                        ?.takeIf { it.phase == AndroidAttachmentPublicRecordPhase.Prepared }
                        ?.let(::rollbackPreparedLocked)
                }
            }
        }
        return synchronized(stateLock) {
            snapshot.records.values.none { it.phase == AndroidAttachmentPublicRecordPhase.Prepared }
        }
    }

    private fun currentPreparedLocked(expected: AndroidAttachmentPublicRecord): AndroidAttachmentPublicRecord? =
        snapshot.records[expected.identityKey]?.takeIf {
            it.phase == AndroidAttachmentPublicRecordPhase.Prepared &&
                it.generation == expected.generation
        }

    private fun rollbackPreparedLocked(record: AndroidAttachmentPublicRecord): Boolean {
        val uri = record.uri
        if (uri != null && !deleteAndConfirmMissing(uri)) return false
        return removeRecordLocked(record.identityKey)
    }

    private fun deleteAndConfirmMissing(uri: String): Boolean {
        val before = backend.inspectExact(uri)
        if (before.availability == AndroidAttachmentPublicUriAvailability.Missing) return true
        backend.deleteExact(uri)
        return backend.inspectExact(uri).availability == AndroidAttachmentPublicUriAvailability.Missing
    }

    private fun removeRecordLocked(identityKey: String): Boolean {
        if (identityKey !in snapshot.records) return true
        val next = snapshot.copy(records = snapshot.records - identityKey)
        if (!store.write(next)) return false
        snapshot = next
        return true
    }

    private fun AndroidAttachmentPublicUriState.matches(record: AndroidAttachmentPublicRecord): Boolean =
        availability == AndroidAttachmentPublicUriAvailability.Published &&
            displayName == record.displayName &&
            mimeType == record.mimeType &&
            sizeBytes == record.sizeBytes &&
            relativePath.trimEnd('/') == ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH.trimEnd('/')
}

internal class AndroidAttachmentPublicStorageException(message: String) : IllegalStateException(message)

internal class AndroidAttachmentPublicDownloadTransaction(
    private val authority: AndroidAttachmentPublicDownloadsAuthority,
    identity: AndroidAttachmentPublicIdentity,
    displayName: String,
    mimeType: String,
    private val deleteExact: (String) -> Boolean
) {
    private data class VerifiedPublication(val uri: String, val sizeBytes: Long)

    private val prepared = authority.begin(identity, displayName, mimeType)
    private val current = AtomicReference(prepared)
    private val verified = AtomicReference<VerifiedPublication?>(null)

    fun attach(uri: String): Boolean {
        val attached = authority.attachExactUri(prepared, uri)
        if (attached == null) {
            deleteExact(uri)
            return false
        }
        current.set(attached)
        return true
    }

    fun verifyPublished(uri: String, sizeBytes: Long): Boolean {
        if (!authority.verifyPublished(current.get(), uri, sizeBytes)) return false
        verified.set(VerifiedPublication(uri, sizeBytes))
        return true
    }

    fun commitVerified(): Boolean =
        verified.get()?.let { publication ->
            authority.commit(current.get(), publication.uri, publication.sizeBytes) != null
        } ?: false

    fun abort(): Boolean = authority.abort(prepared)
}

internal fun finalizePublishedAttachmentDownload(
    transaction: AndroidAttachmentPublicDownloadTransaction,
    result: AttachmentDownloadsExportResult,
    deletePrivateStaging: () -> String
): AttachmentDownloadsExportResult {
    val privateClean = deletePrivateStaging() != "failed"
    if (result.status == "saved" && privateClean && transaction.commitVerified()) return result

    val journalClean = transaction.abort()
    if (result.status != "saved" && privateClean && journalClean) return result

    return AttachmentDownloadsExportResult(
        status = "storage_failed",
        message =
            when {
                !privateClean -> "Unable to remove private attachment staging"
                result.status == "saved" -> "Unable to commit downloaded attachment"
                else -> "Unable to clean up attachment storage"
            },
        failurePhase =
            when {
                !privateClean -> "private_cleanup"
                result.status == "saved" -> "export_commit"
                else -> "export_cleanup"
            }
    )
}

internal class AndroidAttachmentPublicAtomicFileRecordStore(file: File) : AndroidAttachmentPublicRecordStore {
    private val atomicFile = AtomicFile(file)

    override fun read(): AndroidAttachmentPublicRecordsSnapshot {
        if (!atomicFile.baseFile.exists()) return AndroidAttachmentPublicRecordsSnapshot()
        return runCatching {
            val root = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            if (root.optInt("schemaVersion") != SCHEMA_VERSION) return@runCatching AndroidAttachmentPublicRecordsSnapshot()
            val records = linkedMapOf<String, AndroidAttachmentPublicRecord>()
            val recordsJson = root.optJSONArray("records") ?: JSONArray()
            for (index in 0 until recordsJson.length()) {
                val item = recordsJson.optJSONObject(index) ?: continue
                val key = item.optString("identityKey").takeIf(String::isNotBlank) ?: continue
                val generation = item.optLong("generation", -1L).takeIf { it > 0L } ?: continue
                val phase = runCatching { AndroidAttachmentPublicRecordPhase.valueOf(item.optString("phase")) }.getOrNull()
                    ?: continue
                records[key] =
                    AndroidAttachmentPublicRecord(
                        identityKey = key,
                        generation = generation,
                        phase = phase,
                        uri = item.optString("uri").takeIf(String::isNotBlank),
                        displayName = item.optString("displayName"),
                        mimeType = item.optString("mimeType"),
                        sizeBytes = item.optLong("sizeBytes", 0L).coerceAtLeast(0L)
                    )
            }
            val generations = linkedMapOf<String, Long>()
            val generationsJson = root.optJSONObject("lastGenerations") ?: JSONObject()
            generationsJson.keys().forEach { key ->
                generationsJson.optLong(key, -1L).takeIf { it > 0L }?.let { generations[key] = it }
            }
            AndroidAttachmentPublicRecordsSnapshot(records, generations)
        }.getOrDefault(AndroidAttachmentPublicRecordsSnapshot())
    }

    override fun write(snapshot: AndroidAttachmentPublicRecordsSnapshot): Boolean {
        val root =
            JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put(
                    "records",
                    JSONArray().apply {
                        snapshot.records.values.sortedBy { it.identityKey }.forEach { record ->
                            put(
                                JSONObject().apply {
                                    put("identityKey", record.identityKey)
                                    put("generation", record.generation)
                                    put("phase", record.phase.name)
                                    record.uri?.let { put("uri", it) }
                                    put("displayName", record.displayName)
                                    put("mimeType", record.mimeType)
                                    put("sizeBytes", record.sizeBytes)
                                }
                            )
                        }
                    }
                )
                put(
                    "lastGenerations",
                    JSONObject().apply {
                        snapshot.lastGenerations.toSortedMap().forEach { (key, value) -> put(key, value) }
                    }
                )
            }
        var output: java.io.FileOutputStream? = null
        return try {
            output = atomicFile.startWrite()
            output.writer(Charsets.UTF_8).apply {
                write(root.toString())
                flush()
            }
            atomicFile.finishWrite(output)
            output = null
            true
        } catch (_: Throwable) {
            output?.let(atomicFile::failWrite)
            false
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal class AndroidAttachmentPublicMediaStoreBackend(
    private val resolver: ContentResolver
) : AndroidAttachmentPublicUriBackend {
    override fun inspectExact(uri: String): AndroidAttachmentPublicUriState =
        runCatching {
            resolver.query(
                Uri.parse(uri),
                arrayOf(
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Missing)
                } else {
                    val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    if (pendingIndex < 0) {
                        AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Unknown)
                    } else {
                        AndroidAttachmentPublicUriState(
                            availability =
                                if (cursor.getInt(pendingIndex) == 1) {
                                    AndroidAttachmentPublicUriAvailability.Pending
                                } else {
                                    AndroidAttachmentPublicUriAvailability.Published
                                },
                            displayName = cursor.stringColumn(MediaStore.MediaColumns.DISPLAY_NAME),
                            mimeType = cursor.stringColumn(MediaStore.MediaColumns.MIME_TYPE),
                            sizeBytes = cursor.longColumn(MediaStore.MediaColumns.SIZE),
                            relativePath = cursor.stringColumn(MediaStore.MediaColumns.RELATIVE_PATH)
                        )
                    }
                }
            } ?: AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Unknown)
        }.getOrDefault(AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Unknown))

    override fun deleteExact(uri: String): Boolean =
        runCatching { resolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)

    private fun android.database.Cursor.stringColumn(name: String): String {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.longColumn(name: String): Long {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }
}

internal object ProcessAndroidAttachmentPublicDownloads {
    private val initLock = Any()
    private val recoveryStarted = AtomicBoolean(false)
    private val recoveryCompleted = CountDownLatch(1)
    @Volatile private var recoverySucceeded = false
    private val recoveryWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attachment-public-recovery").apply { isDaemon = true }
    }
    @Volatile private var authority: AndroidAttachmentPublicDownloadsAuthority? = null

    fun get(context: Context): AndroidAttachmentPublicDownloadsAuthority {
        authority?.let { return it }
        return synchronized(initLock) {
            authority ?: run {
                val appContext = context.applicationContext
                AndroidAttachmentPublicDownloadsAuthority(
                    store =
                        AndroidAttachmentPublicAtomicFileRecordStore(
                            File(appContext.filesDir, "attachment-public-downloads-v1.json")
                        ),
                    backend = AndroidAttachmentPublicMediaStoreBackend(appContext.contentResolver)
                ).also { authority = it }
            }
        }
    }

    fun start(context: Context) {
        if (!recoveryStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val resolved = get(appContext)
        recoveryWorker.execute {
            try {
                val privateStagingCleared =
                    clearAndroidAttachmentPrivateStaging(
                        File(appContext.cacheDir, ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME)
                    )
                val preparedRecovered = resolved.recoverPrepared()
                recoverySucceeded = privateStagingCleared && preparedRecovered
            } finally {
                recoveryCompleted.countDown()
            }
        }
    }

    fun awaitRecovery(timeoutMs: Long = 10_000L): Boolean =
        recoveryCompleted.await(timeoutMs, TimeUnit.MILLISECONDS) && recoverySucceeded
}
