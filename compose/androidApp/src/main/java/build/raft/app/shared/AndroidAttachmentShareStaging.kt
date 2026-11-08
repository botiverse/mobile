package build.raft.app.shared

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * Share (ACTION_SEND) staging.
 *
 * The private open cache stores attachments as `<principal>-<identity hash>` with no extension, and
 * the FileProvider URI's last path segment is that cache name. Receivers such as WeChat/QQ name and
 * type the received file from `Uri.lastPathSegment` and ignore OpenableColumns.DISPLAY_NAME, so a
 * shared PDF arrived as an extension-less hex name with an unknown ("?") type. Share therefore copies
 * the exact bytes the viewer reads into `cache/slock-share-files/<uuid>/<sanitised display name>`,
 * so the URI's last segment, DISPLAY_NAME and the received file name are all the clean name.
 */
internal const val ANDROID_ATTACHMENT_SHARE_DIR_NAME = "slock-share-files"
internal const val ANDROID_ATTACHMENT_SHARE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal const val ANDROID_ATTACHMENT_SHARE_MAX_ENTRIES = 16
private const val ANDROID_ATTACHMENT_SHARE_NAME_MAX_BYTES = 200
private const val ANDROID_ATTACHMENT_SHARE_FALLBACK_NAME = "attachment"
private val AndroidAttachmentShareEntryPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Server or upload types that say nothing about the content; the file extension is a better answer. */
private val AndroidAttachmentGenericMimeTypes =
    setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/binary",
        "application/unknown",
        "application/x-download",
        "application/force-download",
        "application/download"
    )

/**
 * The MIME type a receiver should see: parameters (`; charset=...`) dropped, lower-cased, and a
 * generic/unknown type replaced by the extension's type from [MimeTypeMap].
 */
internal fun androidAttachmentShareMimeType(displayName: String, mimeType: String?): String {
    val normalized =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.contains('/') && !it.startsWith('/') && !it.endsWith('/') }
    if (normalized != null && normalized !in AndroidAttachmentGenericMimeTypes) return normalized
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
    val fromExtension =
        extension.takeIf(String::isNotEmpty)?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    return fromExtension ?: normalized ?: "application/octet-stream"
}

/**
 * A single safe path segment for [displayName]: no separators, control or reserved characters,
 * no leading dots, bounded UTF-8 length with the extension kept, and an extension derived from
 * [mimeType] when the name has none.
 */
internal fun sanitizeAndroidAttachmentShareFileName(displayName: String, mimeType: String?): String {
    val leaf = displayName.substringAfterLast('/').substringAfterLast('\\')
    val cleaned =
        buildString {
            leaf.forEach { char ->
                append(
                    when {
                        char.isISOControl() -> '_'
                        char in "<>:\"|?*" -> '_'
                        else -> char
                    }
                )
            }
        }
            .trim()
            .trimStart('.')
            .trimEnd('.', ' ')
    var name = cleaned.ifBlank { ANDROID_ATTACHMENT_SHARE_FALLBACK_NAME }
    if (!name.contains('.')) {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
        val extension =
            normalized
                ?.takeIf { it !in AndroidAttachmentGenericMimeTypes }
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!extension.isNullOrBlank()) name = "$name.$extension"
    }
    return name.truncateUtf8KeepingExtension(ANDROID_ATTACHMENT_SHARE_NAME_MAX_BYTES)
}

private fun String.truncateUtf8KeepingExtension(maxBytes: Int): String {
    if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
    val dot = lastIndexOf('.')
    val extension = if (dot > 0 && length - dot <= 16) substring(dot) else ""
    val stem = if (extension.isEmpty()) this else substring(0, dot)
    val budget = maxBytes - extension.toByteArray(Charsets.UTF_8).size
    val out = StringBuilder()
    var used = 0
    var index = 0
    while (index < stem.length) {
        val codePoint = stem.codePointAt(index)
        val chars = Character.toChars(codePoint)
        val bytes = String(chars).toByteArray(Charsets.UTF_8).size
        if (used + bytes > budget) break
        out.append(chars)
        used += bytes
        index += chars.size
    }
    return out.toString().trimEnd('.', ' ').ifEmpty { ANDROID_ATTACHMENT_SHARE_FALLBACK_NAME } + extension
}

/**
 * Removes share entries older than [maxAgeMs], foreign children, and all but the newest
 * [maxEntries] entries. A receiver may keep reading a granted URI after the chooser returns, so a
 * fresh entry is never removed just because a new share started.
 */
internal fun sweepAndroidAttachmentShareStaging(
    shareRoot: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMs: Long = ANDROID_ATTACHMENT_SHARE_MAX_AGE_MS,
    maxEntries: Int = ANDROID_ATTACHMENT_SHARE_MAX_ENTRIES
): Boolean {
    if (!shareRoot.exists()) return true
    if (!shareRoot.isDirectory) return shareRoot.delete() || !shareRoot.exists()
    val children = shareRoot.listFiles() ?: return false
    val (kept, expired) =
        children.partition { child ->
            child.isDirectory &&
                child.name.matches(AndroidAttachmentShareEntryPattern) &&
                (nowMillis - child.lastModified()).coerceAtLeast(0L) <= maxAgeMs
        }
    val overflow = kept.sortedByDescending(File::lastModified).drop(maxEntries.coerceAtLeast(0))
    return (expired + overflow).all { child -> child.deleteRecursively() || !child.exists() }
}

/**
 * Copies [openInput]'s bytes into a fresh `<shareRoot>/<uuid>/<sanitised name>` file and returns it.
 * The copy must be exactly [expectedSizeBytes] long (when known) or it is removed and an error raised.
 */
internal fun stageAndroidAttachmentShareFile(
    shareRoot: File,
    displayName: String,
    mimeType: String?,
    expectedSizeBytes: Long,
    nowMillis: Long = System.currentTimeMillis(),
    openInput: () -> InputStream
): File {
    sweepAndroidAttachmentShareStaging(shareRoot, nowMillis)
    val entry = File(shareRoot, UUID.randomUUID().toString())
    check(entry.mkdirs() || entry.isDirectory) { "Share staging unavailable" }
    val target = File(entry, sanitizeAndroidAttachmentShareFileName(displayName, mimeType))
    try {
        val copied = openInput().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        check(copied > 0L && (expectedSizeBytes <= 0L || copied == expectedSizeBytes)) {
            "Share copy size mismatch"
        }
        entry.setLastModified(nowMillis)
        return target
    } catch (throwable: Throwable) {
        entry.deleteRecursively()
        throw throwable
    }
}

/**
 * Builds the FileProvider source for a staged share file. The plain three-argument
 * [FileProvider.getUriForFile] is used: the on-disk name already is the display name, so the URI
 * ends in it and FileProvider's `query()` answers the same DISPLAY_NAME and the real SIZE.
 */
internal fun androidAttachmentShareSource(
    context: Context,
    stagedFile: File,
    mimeType: String
): AndroidAttachmentContentUriSource {
    check(stagedFile.isFile && stagedFile.length() > 0L) { "Share copy missing" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", stagedFile)
    return AndroidAttachmentContentUriSource(
        kind = AndroidAttachmentOpenSourceKind.PrivateCache,
        contentUri = uri.toString(),
        mimeType = mimeType,
        displayName = stagedFile.name,
        sizeBytes = stagedFile.length(),
        durableCache = false
    )
}

internal fun androidAttachmentShareRoot(context: Context): File =
    File(context.cacheDir, ANDROID_ATTACHMENT_SHARE_DIR_NAME)
