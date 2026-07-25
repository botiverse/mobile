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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Owns the temporary Android share grant. Provider URIs are consumed only while copying into the
 * app-private inbox; the published envelope contains private paths and never external URIs.
 */
internal class AndroidExternalShareIntake(
    private val context: Context,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun admit(intent: Intent): AndroidExternalShareIntakeResult {
        val spec = intent.toExternalShareSpec()
            ?: return AndroidExternalShareIntakeResult.Rejected("external_share_action_unsupported")
        val now = clock()
        pruneExpiredInboxes(now)
        val retainedToken = intent.getStringExtra(PrivateTokenExtra).orEmpty()
        if (isSafeToken(retainedToken) && envelopeIsLive(retainedToken, now)) {
            return AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(retainedToken))
        }
        if (!spec.hasContent()) {
            return AndroidExternalShareIntakeResult.Rejected("external_share_empty")
        }
        if (spec.uris.size > MaxItems) {
            return AndroidExternalShareIntakeResult.Rejected("external_share_too_many_items")
        }

        val fingerprint = spec.fingerprint()
        pruneRedeliveryReceipts(now)
        RedeliveryReceipts[fingerprint]
            ?.takeIf { now - it.admittedAtEpochMillis <= RedeliveryWindowMillis }
            ?.takeIf { envelopeIsLive(it.token, now) }
            ?.let { receipt ->
                intent.putExtra(PrivateTokenExtra, receipt.token)
                return AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(receipt.token))
            }
        val token = tokenFactory().takeIf(::isSafeToken)
            ?: return AndroidExternalShareIntakeResult.Rejected("external_share_token_invalid")
        val inbox = File(context.noBackupFilesDir, "$InboxDirectory/$token")
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
            intent.putExtra(PrivateTokenExtra, token)
            AndroidExternalShareIntakeResult.Admitted(AndroidExternalShareLaunch(token))
        } catch (failure: AndroidExternalShareRejected) {
            inbox.deleteRecursively()
            AndroidExternalShareIntakeResult.Rejected(failure.code)
        } catch (_: Throwable) {
            inbox.deleteRecursively()
            AndroidExternalShareIntakeResult.Rejected("external_share_copy_failed")
        }
    }

    private fun copyItems(uris: List<Uri>, inbox: File): List<CopiedItem> {
        var totalBytes = 0L
        return uris.mapIndexed { index, uri ->
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                reject("external_share_uri_unsupported")
            }
            val metadata = queryMetadata(uri, index)
            if (metadata.declaredSize != null && metadata.declaredSize > MaxItemBytes) {
                reject("external_share_item_too_large")
            }
            if (metadata.declaredSize != null && totalBytes + metadata.declaredSize > MaxTotalBytes) {
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

    private fun copyBounded(uri: Uri, target: File, bytesBefore: Long): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: reject("external_share_item_unreadable")
        var copied = 0L
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(CopyBufferBytes)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied += read
                    if (copied > MaxItemBytes) reject("external_share_item_too_large")
                    if (bytesBefore + copied > MaxTotalBytes) reject("external_share_total_too_large")
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (copied <= 0L) reject("external_share_item_empty")
        return copied
    }

    private fun queryMetadata(uri: Uri, index: Int): ItemMetadata {
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
                    size = cursor.long(OpenableColumns.SIZE)?.takeIf { it >= 0L }
                }
            }
        }
        val safeDisplayName = sanitizeDisplayName(displayName ?: uri.lastPathSegment, index)
        val mimeType = context.contentResolver.getType(uri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.length <= MaxMimeTypeLength }
            ?: "application/octet-stream"
        return ItemMetadata(
            displayName = safeDisplayName,
            safeStorageName = safeDisplayName.replace(UnsafeStorageCharacters, "_").take(MaxFileNameLength),
            mimeType = mimeType,
            declaredSize = size,
        )
    }

    private fun publishEnvelope(inbox: File, envelope: JSONObject) {
        val atomicFile = AtomicFile(File(inbox, EnvelopeFileName))
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(envelope.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            output?.let(atomicFile::failWrite)
            throw failure
        }
    }

    private fun envelopeFile(token: String): File =
        File(context.noBackupFilesDir, "$InboxDirectory/$token/$EnvelopeFileName")

    private fun envelopeIsLive(token: String, now: Long): Boolean =
        envelopeFile(token).let { file ->
            file.isFile && now - file.lastModified() in 0..EnvelopeTtlMillis
        }

    private fun pruneExpiredInboxes(now: Long) {
        File(context.noBackupFilesDir, InboxDirectory).listFiles()?.forEach { directory ->
            val envelope = File(directory, EnvelopeFileName)
            if (!directory.isDirectory || !envelope.isFile || now - envelope.lastModified() !in 0..EnvelopeTtlMillis) {
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

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private fun reject(code: String): Nothing = throw AndroidExternalShareRejected(code)

    private class AndroidExternalShareRejected(val code: String) : RuntimeException(code)

    private companion object {
        const val InboxDirectory = "external-share/inbox"
        const val EnvelopeFileName = "envelope.json"
        const val PrivateTokenExtra = "build.raft.app.externalShareToken"
        const val MaxItems = 9
        const val MaxTextLength = 256 * 1024
        const val MaxFileNameLength = 120
        const val MaxMimeTypeLength = 255
        const val MaxItemBytes = 25L * 1024L * 1024L
        const val MaxTotalBytes = 100L * 1024L * 1024L
        const val CopyBufferBytes = 64 * 1024
        const val RedeliveryWindowMillis = 30L * 1000L
        const val EnvelopeTtlMillis = 24L * 60L * 60L * 1000L
        val UnsafeStorageCharacters = Regex("[^A-Za-z0-9._-]")
        val SafeToken = Regex("[A-Za-z0-9-]{16,80}")
        val RedeliveryReceipts = ConcurrentHashMap<String, RedeliveryReceipt>()

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

internal data class AndroidExternalShareSpec(
    val text: String,
    val subject: String,
    val uris: List<Uri>,
) {
    fun hasContent(): Boolean = text.isNotBlank() || subject.isNotBlank() || uris.isNotEmpty()
}

internal fun Intent.toExternalShareSpec(): AndroidExternalShareSpec? {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val uris = buildList {
        externalShareStreamUris().forEach { uri -> if (uri !in this) add(uri) }
        clipData.externalShareUris().forEach { uri -> if (uri !in this) add(uri) }
    }
    return AndroidExternalShareSpec(
        text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(256 * 1024),
        subject = getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString().orEmpty().take(256 * 1024),
        uris = uris,
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
