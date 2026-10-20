package build.raft.app

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import build.raft.app.data.RaftExternalShareEnvelopeHost
import build.raft.app.data.RaftExternalShareEnvelopeHostItem
import build.raft.app.data.RaftExternalShareEnvelopeHostMutationResult
import build.raft.app.data.RaftExternalShareEnvelopeHostProvider
import build.raft.app.data.RaftExternalShareEnvelopeHostReadResult
import build.raft.app.data.RaftExternalShareEnvelopeHostRecord
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val AndroidExternalShareIntent = "open_share_to_raft"
internal const val AndroidExternalShareTokenKey = "externalShareToken"

internal data class AndroidExternalShareLaunch(
    val token: String,
    val intent: String = AndroidExternalShareIntent
)

internal sealed interface AndroidExternalShareIntakeResult {
    data class Admitted(val launch: AndroidExternalShareLaunch) : AndroidExternalShareIntakeResult
    data class Rejected(val code: String) : AndroidExternalShareIntakeResult
}

/**
 * Process owner for the temporary URI grant. Every copy is serialized; Activity recreation only
 * replaces the listener. A newer share supersedes UI publication, never an in-flight file copy.
 */
internal class AndroidExternalShareAdmissionOwner<T>(
    scope: CoroutineScope,
    private val admit: suspend (T) -> AndroidExternalShareIntakeResult
) {
    private val queue = Channel<QueuedInput<T>>(Channel.UNLIMITED)
    private var latestGeneration = 0L
    private var listener: ((Long, AndroidExternalShareIntakeResult) -> Unit)? = null
    private var pending: PendingResult? = null

    init {
        scope.launch {
            for (queued in queue) {
                val result =
                    try {
                        admit(queued.input)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        AndroidExternalShareIntakeResult.Rejected("external_share_intake_failed")
                    }
                if (queued.generation == latestGeneration) {
                    pending = PendingResult(queued.generation, result)
                    deliverPending()
                }
            }
        }
    }

    fun submit(input: T) {
        latestGeneration += 1L
        pending = null
        check(queue.trySend(QueuedInput(latestGeneration, input)).isSuccess)
    }

    fun attach(nextListener: (Long, AndroidExternalShareIntakeResult) -> Unit): Boolean {
        listener = nextListener
        val hasPending = pending != null
        deliverPending()
        return hasPending
    }

    fun detach(currentListener: (Long, AndroidExternalShareIntakeResult) -> Unit) {
        if (listener === currentListener) listener = null
    }

    fun acknowledge(generation: Long) {
        if (pending?.generation == generation) pending = null
    }

    private fun deliverPending() {
        val ready = pending ?: return
        listener?.let { callback -> runCatching { callback(ready.generation, ready.result) } }
    }

    private data class QueuedInput<T>(val generation: Long, val input: T)
    private data class PendingResult(val generation: Long, val result: AndroidExternalShareIntakeResult)
}

internal object AndroidExternalShareProcessOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var inbox: AndroidExternalShareInbox? = null
    @Volatile private var owner: AndroidExternalShareAdmissionOwner<Intent>? = null

    fun inbox(context: Context): AndroidExternalShareInbox =
        inbox ?: synchronized(this) {
            inbox ?: AndroidExternalShareInbox(context.applicationContext).also { inbox = it }
        }

    fun admissionOwner(context: Context): AndroidExternalShareAdmissionOwner<Intent> =
        owner ?: synchronized(this) {
            owner
                ?: AndroidExternalShareAdmissionOwner<Intent>(
                    scope = scope,
                    admit = { intent ->
                        withContext(Dispatchers.IO) {
                            try {
                                inbox(context).admit(intent)
                            } finally {
                                intent.releaseAndroidExternalShareUriGrants(context.applicationContext)
                            }
                        }
                    }
                ).also { owner = it }
        }
}

fun installAndroidExternalShareInbox(context: Context) {
    val appContext = context.applicationContext
    RaftExternalShareEnvelopeHostProvider.installAppScopeHost {
        AndroidExternalShareProcessOwner.inbox(appContext)
    }
}

internal class AndroidExternalShareInbox(
    private val root: File,
    private val source: AndroidExternalShareSource,
    private val limits: AndroidExternalShareLimits = AndroidExternalShareLimits(),
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val atomicWriter: (File, ByteArray) -> Unit = ::writeAtomic,
    private val atomicReader: (File) -> String = ::readAtomicText
) : RaftExternalShareEnvelopeHost {
    constructor(
        context: Context,
        tokenFactory: () -> String = { UUID.randomUUID().toString() },
        clock: () -> Long = System::currentTimeMillis
    ) : this(
        root = File(context.noBackupFilesDir, InboxDirectory),
        source = AndroidContentExternalShareSource(context.applicationContext),
        tokenFactory = tokenFactory,
        clock = clock
    )

    fun admit(intent: Intent): AndroidExternalShareIntakeResult {
        val spec = intent.toAndroidExternalShareSpec()
            ?: return AndroidExternalShareIntakeResult.Rejected("external_share_action_unsupported")
        return admit(spec)
    }

    internal fun admit(spec: AndroidExternalShareSpec): AndroidExternalShareIntakeResult {
        val now = clock()
        prune(now)
        if (spec.textTooLong) return rejected("external_share_text_too_large")
        if (!spec.hasContent()) return rejected("external_share_empty")
        if (spec.uris.size > limits.maxItems) return rejected("external_share_too_many_items")
        val fingerprint = spec.fingerprint()
        recentMatchingEnvelope(fingerprint, now)?.let { token ->
            writePendingToken(token)
            return AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(token))
        }
        val token = tokenFactory().takeIf(::isSafeToken)
            ?: return rejected("external_share_token_invalid")
        val directory = directory(token)
        if (!directory.mkdirs() && !directory.isDirectory) {
            return rejected("external_share_storage_unavailable")
        }
        return try {
            val items = copyItems(spec.uris, directory)
            val envelope =
                JSONObject()
                    .put("token", token)
                    .put("createdAtEpochMillis", now)
                    .put("sourcePlatform", "android")
                    .put("text", spec.text)
                    .put("subject", spec.subject)
                    .put("fingerprint", fingerprint)
                    .put(
                        "items",
                        JSONArray().apply {
                            items.forEach { item ->
                                put(
                                    JSONObject()
                                        .put("localRef", item.file.toURI().toString())
                                        .put("displayName", item.displayName)
                                        .put("mimeType", item.mimeType)
                                        .put("sizeBytes", item.sizeBytes)
                                )
                            }
                        }
                    )
            atomicWriter(envelopeFile(token), envelope.toString().toByteArray(Charsets.UTF_8))
            envelopeFile(token).setLastModified(now)
            writePendingToken(token)
            AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(token))
        } catch (failure: AndroidExternalShareRejected) {
            directory.deleteRecursively()
            rejected(failure.code)
        } catch (_: Throwable) {
            directory.deleteRecursively()
            rejected("external_share_copy_failed")
        }
    }

    fun pendingLaunchOrNull(): AndroidExternalShareLaunch? {
        val token = runCatching { atomicReader(pendingFile()).trim() }.getOrNull()
            ?.takeIf(::isSafeToken)
            ?: return null
        return if (liveEnvelopeFile(token, clock()) != null && !isConsumed(token)) {
            AndroidExternalShareLaunch(token)
        } else {
            clearPendingIfMatches(token)
            null
        }
    }

    override suspend fun load(token: String): RaftExternalShareEnvelopeHostReadResult =
        withContext(Dispatchers.IO) {
            if (!isSafeToken(token)) {
                return@withContext RaftExternalShareEnvelopeHostReadResult.Failure(
                    "external_share_identity_invalid",
                    retryable = false
                )
            }
            if (isConsumed(token)) return@withContext RaftExternalShareEnvelopeHostReadResult.Missing
            val file = liveEnvelopeFile(token, clock()) ?: return@withContext RaftExternalShareEnvelopeHostReadResult.Missing
            runCatching { decodeEnvelope(token, file) }
                .getOrElse {
                    RaftExternalShareEnvelopeHostReadResult.Failure(
                        "external_share_envelope_decode_failed",
                        retryable = false
                    )
                }
        }

    override suspend fun consume(
        token: String,
        consumptionId: String
    ): RaftExternalShareEnvelopeHostMutationResult =
        withContext(Dispatchers.IO) {
            if (!isSafeToken(token) || consumptionId != "external-share-consume:$token") {
                return@withContext RaftExternalShareEnvelopeHostMutationResult.Failure(
                    "external_share_consumption_identity_invalid",
                    retryable = false
                )
            }
            val directory = directory(token)
            if (!directory.isDirectory) return@withContext RaftExternalShareEnvelopeHostMutationResult.Missing
            val marker = consumedFile(token)
            val markerWasPresent = marker.exists()
            val existingConsumption = runCatching { atomicReader(marker) }.getOrNull()
            if (existingConsumption != null) {
                return@withContext if (existingConsumption == consumptionId) {
                    RaftExternalShareEnvelopeHostMutationResult.Success
                } else {
                    RaftExternalShareEnvelopeHostMutationResult.Failure(
                        "external_share_consumption_conflict",
                        retryable = false
                    )
                }
            }
            if (markerWasPresent) {
                return@withContext RaftExternalShareEnvelopeHostMutationResult.Failure(
                    "external_share_consumption_marker_invalid",
                    retryable = false
                )
            }
            if (liveEnvelopeFile(token, clock()) == null) {
                return@withContext RaftExternalShareEnvelopeHostMutationResult.Missing
            }
            runCatching {
                atomicWriter(marker, consumptionId.toByteArray(Charsets.UTF_8))
                clearPendingIfMatches(token)
                RaftExternalShareEnvelopeHostMutationResult.Success
            }.getOrElse {
                RaftExternalShareEnvelopeHostMutationResult.Failure(
                    "external_share_consume_failed",
                    retryable = true
                )
            }
        }

    override suspend fun delete(token: String) {
        withContext(Dispatchers.IO) {
            if (!isSafeToken(token)) return@withContext
            directory(token).deleteRecursively()
            clearPendingIfMatches(token)
        }
    }

    private fun decodeEnvelope(token: String, file: File): RaftExternalShareEnvelopeHostReadResult {
        val json = JSONObject(atomicReader(file))
        if (json.optString("token") != token) error("token mismatch")
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        if (itemsJson.length() > limits.maxItems) error("too many items")
        val canonicalDirectory = directory(token).canonicalFile
        var totalBytes = 0L
        val items =
            buildList {
                repeat(itemsJson.length()) { index ->
                    val item = itemsJson.optJSONObject(index) ?: error("invalid item")
                    val uri = URI(item.optString("localRef"))
                    if (uri.scheme != "file") error("invalid local ref")
                    val path = uri.path ?: error("missing local path")
                    val localFile = File(path).canonicalFile
                    if (localFile.parentFile != canonicalDirectory || !localFile.isFile) error("escaped local ref")
                    val sizeBytes = item.optLong("sizeBytes", -1L)
                    if (sizeBytes <= 0L || sizeBytes != localFile.length() || sizeBytes > limits.maxItemBytes) {
                        error("invalid item size")
                    }
                    totalBytes += sizeBytes
                    if (totalBytes > limits.maxTotalBytes) error("invalid total size")
                    add(
                        RaftExternalShareEnvelopeHostItem(
                            localRef = localFile.toURI().toString(),
                            displayName = sanitizeDisplayName(item.optString("displayName"), index),
                            mimeType = sanitizeMimeType(item.optString("mimeType")),
                            sizeBytes = sizeBytes
                        )
                    )
                }
            }
        return RaftExternalShareEnvelopeHostReadResult.Content(
            RaftExternalShareEnvelopeHostRecord(
                token = token,
                createdAtEpochMillis = json.optLong("createdAtEpochMillis"),
                sourcePlatform = "android",
                text = json.optString("text"),
                subject = json.optString("subject"),
                items = items
            )
        )
    }

    private fun copyItems(uris: List<String>, directory: File): List<CopiedItem> {
        var totalBytes = 0L
        return uris.mapIndexed { index, ref ->
            if (!source.isGrantedContentRef(ref)) reject("external_share_uri_unsupported")
            val metadata = source.metadata(ref)
            val declaredSize = metadata.declaredSize?.takeIf { it >= 0L }
            if (declaredSize != null && declaredSize > limits.maxItemBytes) {
                reject("external_share_item_too_large")
            }
            if (declaredSize != null && totalBytes + declaredSize > limits.maxTotalBytes) {
                reject("external_share_total_too_large")
            }
            val displayName = sanitizeDisplayName(metadata.displayName, index)
            val target = File(directory, "item-${index + 1}-${displayName.replace(UnsafeStorageCharacters, "_")}")
            val size = copyBounded(ref, target, totalBytes)
            totalBytes += size
            CopiedItem(target, displayName, sanitizeMimeType(metadata.mimeType), size)
        }
    }

    private fun copyBounded(ref: String, target: File, bytesBefore: Long): Long {
        val input = source.open(ref) ?: reject("external_share_item_unreadable")
        var copied = 0L
        input.use { sourceStream ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(limits.copyBufferBytes)
                while (true) {
                    val read = sourceStream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied += read
                    if (copied > limits.maxItemBytes) reject("external_share_item_too_large")
                    if (bytesBefore + copied > limits.maxTotalBytes) reject("external_share_total_too_large")
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (copied <= 0L) reject("external_share_item_empty")
        return copied
    }

    private fun recentMatchingEnvelope(fingerprint: String, now: Long): String? {
        val token =
            runCatching { atomicReader(pendingFile()).trim() }
                .getOrNull()
                ?.takeIf(::isSafeToken)
                ?: return null
        val envelope = liveEnvelopeFile(token, now) ?: return null
        if (now - envelope.lastModified() > RedeliveryWindowMillis || isConsumed(token)) return null
        val matches =
            runCatching { JSONObject(atomicReader(envelope)).optString("fingerprint") == fingerprint }
                .getOrDefault(false)
        return token.takeIf { matches }
    }

    private fun prune(now: Long) {
        if (!root.exists()) root.mkdirs()
        runCatching { atomicReader(pendingFile()) }
        root.listFiles().orEmpty().forEach { candidate ->
            if (candidate == pendingFile()) return@forEach
            val envelope = File(candidate, EnvelopeFileName)
            if (candidate.isDirectory) runCatching { atomicReader(envelope) }
            if (
                !candidate.isDirectory ||
                    !envelope.isFile ||
                    now - envelope.lastModified() !in 0..limits.envelopeTtlMillis
            ) {
                candidate.deleteRecursively()
            }
        }
        pendingLaunchOrNull()
    }

    private fun writePendingToken(token: String) {
        if (!root.exists() && !root.mkdirs()) reject("external_share_storage_unavailable")
        atomicWriter(pendingFile(), token.toByteArray(Charsets.UTF_8))
    }

    private fun clearPendingIfMatches(token: String) {
        val pending = pendingFile()
        if (runCatching { atomicReader(pending).trim() }.getOrNull() == token) {
            pending.delete()
        }
    }

    private fun liveEnvelopeFile(token: String, now: Long): File? =
        envelopeFile(token).takeIf { file ->
            runCatching { atomicReader(file) }.isSuccess &&
                file.isFile &&
                now - file.lastModified() in 0..limits.envelopeTtlMillis
        }

    private fun isConsumed(token: String): Boolean =
        consumedFile(token).let { marker ->
            marker.exists() || runCatching { atomicReader(marker) }.isSuccess
        }

    private fun directory(token: String): File = File(root, token)
    private fun envelopeFile(token: String): File = File(directory(token), EnvelopeFileName)
    private fun consumedFile(token: String): File = File(directory(token), ConsumedFileName)
    private fun pendingFile(): File = File(root, PendingFileName)

    private fun rejected(code: String) = AndroidExternalShareIntakeResult.Rejected(code)
    private fun reject(code: String): Nothing = throw AndroidExternalShareRejected(code)
    private class AndroidExternalShareRejected(val code: String) : RuntimeException(code)
    private data class CopiedItem(val file: File, val displayName: String, val mimeType: String, val sizeBytes: Long)

    companion object {
        internal const val InboxDirectory = "external-share/inbox"
        internal const val EnvelopeFileName = "envelope.json"
        internal const val ConsumedFileName = "consumed.txt"
        internal const val PendingFileName = "pending.txt"
        internal const val RedeliveryWindowMillis = 30_000L
        internal val UnsafeStorageCharacters = Regex("[^A-Za-z0-9._-]")
        internal val SafeToken = Regex("[A-Za-z0-9._~-]{16,200}")

        internal fun isSafeToken(value: String): Boolean = SafeToken.matches(value)

        internal fun sanitizeDisplayName(value: String?, index: Int): String {
            val normalized =
                value
                    ?.substringAfterLast('/')
                    ?.replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
                    ?.trim()
                    ?.take(120)
                    .orEmpty()
            return normalized.takeIf { it.isNotBlank() && it != "." && it != ".." }
                ?: "shared-item-${index + 1}"
        }

        internal fun sanitizeMimeType(value: String): String =
            value.substringBefore(';').trim().lowercase()
                .takeIf { it.isNotBlank() && it.length <= 255 }
                ?: "application/octet-stream"
    }
}

internal data class AndroidExternalShareLimits(
    val maxItems: Int = 9,
    val maxItemBytes: Long = 25L * 1024L * 1024L,
    val maxTotalBytes: Long = 100L * 1024L * 1024L,
    val copyBufferBytes: Int = 64 * 1024,
    val envelopeTtlMillis: Long = 24L * 60L * 60L * 1000L
)

internal data class AndroidExternalShareSourceMetadata(
    val displayName: String?,
    val mimeType: String,
    val declaredSize: Long?
)

internal interface AndroidExternalShareSource {
    fun isGrantedContentRef(ref: String): Boolean
    fun metadata(ref: String): AndroidExternalShareSourceMetadata
    fun open(ref: String): InputStream?
}

private class AndroidContentExternalShareSource(private val context: Context) : AndroidExternalShareSource {
    override fun isGrantedContentRef(ref: String): Boolean =
        runCatching { Uri.parse(ref).scheme == ContentResolver.SCHEME_CONTENT }.getOrDefault(false)

    override fun metadata(ref: String): AndroidExternalShareSourceMetadata {
        val uri = Uri.parse(ref)
        var displayName: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.string(OpenableColumns.DISPLAY_NAME)
                    size = cursor.long(OpenableColumns.SIZE)
                }
            }
        }
        return AndroidExternalShareSourceMetadata(
            displayName = displayName ?: uri.lastPathSegment,
            mimeType = context.contentResolver.getType(uri).orEmpty(),
            declaredSize = size
        )
    }

    override fun open(ref: String): InputStream? = context.contentResolver.openInputStream(Uri.parse(ref))

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
}

internal data class AndroidExternalShareSpec(
    val text: String,
    val subject: String,
    val uris: List<String>,
    val textTooLong: Boolean = false
) {
    fun hasContent(): Boolean = text.isNotBlank() || subject.isNotBlank() || uris.isNotEmpty()
}

internal fun Intent.toAndroidExternalShareSpec(): AndroidExternalShareSpec? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val uris = buildList {
        externalShareStreamUris().forEach { uri -> if (uri !in this) add(uri) }
        clipData.externalShareUris().forEach { uri -> if (uri !in this) add(uri) }
    }
    val textValue = getCharSequenceExtra(Intent.EXTRA_TEXT)
    val subjectValue = getCharSequenceExtra(Intent.EXTRA_SUBJECT)
    val textTooLong = (textValue?.length ?: 0) > MaxTextCharacters || (subjectValue?.length ?: 0) > MaxTextCharacters
    return AndroidExternalShareSpec(
        text = textValue?.takeUnless { textTooLong }?.toString().orEmpty(),
        subject = subjectValue?.takeUnless { textTooLong }?.toString().orEmpty(),
        uris = uris.map(Uri::toString),
        textTooLong = textTooLong
    )
}

internal fun Intent.isAndroidExternalShareIntent(): Boolean =
    action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE

internal fun Intent.releaseAndroidExternalShareUriGrants(context: Context) {
    val refs = (externalShareStreamUris() + clipData.externalShareUris()).distinct()
    refs.forEach { uri ->
        runCatching {
            context.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}

internal fun androidExternalSharePageData(launch: AndroidExternalShareLaunch): JSONObject =
    JSONObject()
        .put("intent", launch.intent)
        .put(AndroidExternalShareTokenKey, launch.token)

private fun AndroidExternalShareSpec.fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(text.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(subject.toByteArray(Charsets.UTF_8))
    uris.forEach { uri ->
        digest.update(0.toByte())
        digest.update(uri.toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

@Suppress("DEPRECATION")
private fun Intent.externalShareStreamUris(): List<Uri> =
    when (action) {
        Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

private fun ClipData?.externalShareUris(): List<Uri> =
    this?.let { clip ->
        buildList {
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
    }.orEmpty()

private fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.let { parent ->
        if (!parent.exists() && !parent.mkdirs()) error("Unable to create external share directory")
    }
    val atomicFile = AtomicFile(file)
    var output: FileOutputStream? = null
    try {
        output = atomicFile.startWrite()
        output.write(bytes)
        output.fd.sync()
        atomicFile.finishWrite(output)
    } catch (failure: Throwable) {
        output?.let(atomicFile::failWrite)
        throw failure
    }
}

private fun readAtomicText(file: File): String =
    AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }

private const val MaxTextCharacters = 256 * 1024
