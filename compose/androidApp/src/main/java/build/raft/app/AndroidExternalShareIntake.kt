package build.raft.app

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import build.raft.app.shared.SlockKuiklyRouteKeys
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val AndroidExternalShareIntent = "open_share_to_raft"
internal const val AndroidExternalShareTokenKey = SlockKuiklyRouteKeys.EXTERNAL_SHARE_TOKEN

internal data class AndroidExternalShareLaunch(
    val token: String,
    val intent: String = AndroidExternalShareIntent,
)

internal sealed interface AndroidExternalShareIntakeResult {
    data class Admitted(val launch: AndroidExternalShareLaunch) : AndroidExternalShareIntakeResult

    data class Rejected(val code: String) : AndroidExternalShareIntakeResult
}

/** Serial process owner. A newer warm share supersedes only UI publication, never an in-flight copy. */
internal class AndroidExternalShareAdmissionOwner<T>(
    scope: CoroutineScope,
    private val admit: suspend (T) -> AndroidExternalShareIntakeResult,
    private val onLatestResult: (AndroidExternalShareIntakeResult) -> Unit,
) {
    private val queue = Channel<QueuedInput<T>>(Channel.UNLIMITED)
    private var latestGeneration = 0L

    init {
        scope.launch {
            for (queued in queue) {
                val result = admit(queued.intent)
                if (queued.generation == latestGeneration) onLatestResult(result)
            }
        }
    }

    fun submit(intent: T) {
        latestGeneration += 1L
        check(queue.trySend(QueuedInput(latestGeneration, intent)).isSuccess)
    }

    private data class QueuedInput<T>(val generation: Long, val intent: T)

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            onLatestResult: (AndroidExternalShareIntakeResult) -> Unit,
        ): AndroidExternalShareAdmissionOwner<Intent> =
            AndroidExternalShareAdmissionOwner(
                scope = scope,
                admit = { intent ->
                    withContext(Dispatchers.IO) { AndroidExternalShareIntake(context).admit(intent) }
                },
                onLatestResult = onLatestResult,
            )
    }
}

/**
 * Owns the temporary Android share grant. Provider URIs are consumed only while copying into the
 * app-private inbox; the published envelope contains private paths and never external URIs.
 */
internal class AndroidExternalShareIntake(
    private val inboxRoot: File,
    private val provider: AndroidExternalShareProvider,
    private val limits: AndroidExternalShareLimits = AndroidExternalShareLimits(),
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val atomicEnvelopeWriter: (File, ByteArray) -> Unit = ::androidWriteAtomicEnvelope,
) {
    constructor(
        context: Context,
        tokenFactory: () -> String = { UUID.randomUUID().toString() },
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        inboxRoot = File(context.noBackupFilesDir, InboxDirectory),
        provider = AndroidContentExternalShareProvider(context),
        tokenFactory = tokenFactory,
        clock = clock,
    )

    fun admit(intent: Intent): AndroidExternalShareIntakeResult {
        val spec = intent.toExternalShareSpec()
            ?: return AndroidExternalShareIntakeResult.Rejected("external_share_action_unsupported")
        return admit(
            spec = spec,
            retainedToken = intent.getStringExtra(PrivateTokenExtra).orEmpty(),
            retainToken = { token -> intent.putExtra(PrivateTokenExtra, token) },
        )
    }

    internal fun admit(
        spec: AndroidExternalShareSpec,
        retainedToken: String = "",
        retainToken: (String) -> Unit = {},
    ): AndroidExternalShareIntakeResult {
        val now = clock()
        pruneExpiredInboxes(now)
        if (isSafeToken(retainedToken) && envelopeIsLive(retainedToken, now)) {
            return AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(retainedToken))
        }
        if (spec.textTooLong) {
            return AndroidExternalShareIntakeResult.Rejected("external_share_text_too_large")
        }
        if (!spec.hasContent()) {
            return AndroidExternalShareIntakeResult.Rejected("external_share_empty")
        }
        if (spec.uris.size > limits.maxItems) {
            return AndroidExternalShareIntakeResult.Rejected("external_share_too_many_items")
        }

        val fingerprint = spec.fingerprint()
        pruneRedeliveryReceipts(now)
        RedeliveryReceipts[fingerprint]
            ?.takeIf { now - it.admittedAtEpochMillis <= RedeliveryWindowMillis }
            ?.takeIf { envelopeIsLive(it.token, now) }
            ?.let { receipt ->
                retainToken(receipt.token)
                return AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(receipt.token))
            }
        val token = tokenFactory().takeIf(::isSafeToken)
            ?: return AndroidExternalShareIntakeResult.Rejected("external_share_token_invalid")
        val inbox = File(inboxRoot, token)
        if (!inbox.mkdirs() && !inbox.isDirectory) {
            return AndroidExternalShareIntakeResult.Rejected("external_share_storage_unavailable")
        }

        return try {
            val items = copyItems(spec.uris, inbox)
            val envelope = JSONObject()
                .put("token", token)
                .put("createdAtEpochMillis", now)
                .put("sourcePlatform", "android")
                .put("text", spec.text)
                .put("subject", spec.subject)
                .put("items", JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject()
                                .put("localRef", item.file.absolutePath)
                                .put("displayName", item.displayName)
                                .put("mimeType", item.mimeType)
                                .put("sizeBytes", item.sizeBytes)
                        )
                    }
                })
            publishEnvelope(inbox, envelope)
            RedeliveryReceipts[fingerprint] = RedeliveryReceipt(token, now)
            retainToken(token)
            AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(token))
        } catch (failure: AndroidExternalShareRejected) {
            inbox.deleteRecursively()
            AndroidExternalShareIntakeResult.Rejected(failure.code)
        } catch (_: Throwable) {
            inbox.deleteRecursively()
            AndroidExternalShareIntakeResult.Rejected("external_share_copy_failed")
        }
    }

    private fun copyItems(uris: List<String>, inbox: File): List<CopiedItem> {
        var totalBytes = 0L
        return uris.mapIndexed { index, uri ->
            if (!provider.isGrantedContentRef(uri)) reject("external_share_uri_unsupported")
            val metadata = queryMetadata(uri, index)
            if (metadata.declaredSize != null && metadata.declaredSize > limits.maxItemBytes) {
                reject("external_share_item_too_large")
            }
            if (metadata.declaredSize != null && totalBytes + metadata.declaredSize > limits.maxTotalBytes) {
                reject("external_share_total_too_large")
            }
            val target = File(inbox, "item-${index + 1}-${metadata.safeStorageName}")
            val size = copyBounded(uri, target, totalBytes)
            totalBytes += size
            CopiedItem(
                file = target,
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                sizeBytes = size,
            )
        }
    }

    private fun copyBounded(uri: String, target: File, bytesBefore: Long): Long {
        val input = provider.open(uri)
            ?: reject("external_share_item_unreadable")
        var copied = 0L
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(limits.copyBufferBytes)
                while (true) {
                    val read = source.read(buffer)
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

    private fun queryMetadata(uri: String, index: Int): ItemMetadata {
        val source = provider.metadata(uri)
        val safeDisplayName = sanitizeDisplayName(source.displayName, index)
        val mimeType = source.mimeType.substringBefore(';').trim().lowercase()
            .takeIf { it.isNotBlank() && it.length <= MaxMimeTypeLength } ?: "application/octet-stream"
        return ItemMetadata(
            displayName = safeDisplayName,
            safeStorageName = safeDisplayName.replace(UnsafeStorageCharacters, "_").take(MaxFileNameLength),
            mimeType = mimeType,
            declaredSize = source.declaredSize?.takeIf { it >= 0L },
        )
    }

    private fun publishEnvelope(inbox: File, envelope: JSONObject) {
        atomicEnvelopeWriter(File(inbox, EnvelopeFileName), envelope.toString().toByteArray(Charsets.UTF_8))
    }

    private fun envelopeFile(token: String): File =
        File(inboxRoot, "$token/$EnvelopeFileName")

    private fun envelopeIsLive(token: String, now: Long): Boolean =
        envelopeFile(token).let { file ->
            file.isFile && now - file.lastModified() in 0..limits.envelopeTtlMillis
        }

    private fun pruneExpiredInboxes(now: Long) {
        inboxRoot.listFiles()?.forEach { directory ->
            val envelope = File(directory, EnvelopeFileName)
            if (!directory.isDirectory || !envelope.isFile || now - envelope.lastModified() !in 0..limits.envelopeTtlMillis) {
                directory.deleteRecursively()
            }
        }
    }

    private fun pruneRedeliveryReceipts(now: Long) {
        RedeliveryReceipts.entries.forEach { (fingerprint, receipt) ->
            if (now - receipt.admittedAtEpochMillis > RedeliveryWindowMillis) {
                RedeliveryReceipts.remove(fingerprint, receipt)
            }
        }
    }

    private data class CopiedItem(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private data class ItemMetadata(
        val displayName: String,
        val safeStorageName: String,
        val mimeType: String,
        val declaredSize: Long?,
    )

    private data class RedeliveryReceipt(
        val token: String,
        val admittedAtEpochMillis: Long,
    )

    private fun reject(code: String): Nothing = throw AndroidExternalShareRejected(code)

    private class AndroidExternalShareRejected(val code: String) : RuntimeException(code)

    companion object {
        internal const val InboxDirectory = "external-share/inbox"
        const val EnvelopeFileName = "envelope.json"
        const val PrivateTokenExtra = "build.raft.app.externalShareToken"
        const val MaxFileNameLength = 120
        const val MaxMimeTypeLength = 255
        const val RedeliveryWindowMillis = 30L * 1000L
        val UnsafeStorageCharacters = Regex("[^A-Za-z0-9._-]")
        val SafeToken = Regex("[A-Za-z0-9-]{16,80}")
        private val RedeliveryReceipts = ConcurrentHashMap<String, RedeliveryReceipt>()

        fun isSafeToken(value: String): Boolean = SafeToken.matches(value)

        fun sanitizeDisplayName(value: String?, index: Int): String {
            val normalized = value
                ?.substringAfterLast('/')
                ?.replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
                ?.trim()
                ?.take(MaxFileNameLength)
                .orEmpty()
            return normalized.takeIf { it.isNotBlank() && it != "." && it != ".." }
                ?: "shared-item-${index + 1}"
        }

    }
}

private fun androidWriteAtomicEnvelope(file: File, bytes: ByteArray) {
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

internal data class AndroidExternalShareLimits(
    val maxItems: Int = 9,
    val maxItemBytes: Long = 25L * 1024L * 1024L,
    val maxTotalBytes: Long = 100L * 1024L * 1024L,
    val copyBufferBytes: Int = 64 * 1024,
    val envelopeTtlMillis: Long = 24L * 60L * 60L * 1000L,
)

internal data class AndroidExternalShareSourceMetadata(
    val displayName: String?,
    val mimeType: String,
    val declaredSize: Long?,
)

internal interface AndroidExternalShareProvider {
    fun isGrantedContentRef(ref: String): Boolean
    fun metadata(ref: String): AndroidExternalShareSourceMetadata
    fun open(ref: String): InputStream?
}

private class AndroidContentExternalShareProvider(private val context: Context) : AndroidExternalShareProvider {
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
                null,
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
            declaredSize = size,
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
    val textTooLong: Boolean = false,
) {
    fun hasContent(): Boolean = text.isNotBlank() || subject.isNotBlank() || uris.isNotEmpty()
}

internal fun Intent.toExternalShareSpec(): AndroidExternalShareSpec? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val uris = buildList {
        externalShareStreamUris().forEach { uri -> if (uri !in this) add(uri) }
        clipData.externalShareUris().forEach { uri -> if (uri !in this) add(uri) }
    }
    val textValue = getCharSequenceExtra(Intent.EXTRA_TEXT)
    val subjectValue = getCharSequenceExtra(Intent.EXTRA_SUBJECT)
    val textTooLong = (textValue?.length ?: 0) > 256 * 1024 || (subjectValue?.length ?: 0) > 256 * 1024
    return AndroidExternalShareSpec(
        text = textValue?.takeUnless { textTooLong }?.toString().orEmpty(),
        subject = subjectValue?.takeUnless { textTooLong }?.toString().orEmpty(),
        uris = uris.map(Uri::toString),
        textTooLong = textTooLong,
    )
}

private fun AndroidExternalShareSpec.fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(text.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(subject.toByteArray(Charsets.UTF_8))
    uris.forEach { uri ->
        digest.update(0.toByte())
        digest.update(uri.toString().toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

@Suppress("DEPRECATION")
private fun Intent.externalShareStreamUris(): List<Uri> =
    when (action) {
        Intent.ACTION_SEND -> listOfNotNull(getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE ->
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

private fun ClipData?.externalShareUris(): List<Uri> =
    this?.let { clip ->
        buildList {
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
    }.orEmpty()

internal fun androidExternalSharePageData(launch: AndroidExternalShareLaunch): JSONObject =
    JSONObject()
        .put(SlockKuiklyRouteKeys.INTENT, launch.intent)
        .put(AndroidExternalShareTokenKey, launch.token)
