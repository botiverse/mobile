package build.raft.app.shared

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import build.raft.app.downloads.RaftShareFileName
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Hand-off staging for Share (ACTION_SEND) and Open with (ACTION_VIEW).
 *
 * The private open cache stores attachments as `<principal>-<identity hash>` with no extension, and
 * the FileProvider URI's last path segment is that cache name (a public Downloads source is a
 * MediaStore URI ending in a numeric row id). Receivers such as WeChat/QQ name and type the received
 * file from `Uri.lastPathSegment` and ignore OpenableColumns.DISPLAY_NAME, so a shared PDF arrived
 * as an extension-less hex name with an unknown ("?") type. Hand-offs therefore expose the exact
 * bytes the viewer reads as `cache/slock-share-files/<uuid>/<sanitised display name>` (a hard link
 * to the private cache file when possible, else a copy), so the URI's last segment, DISPLAY_NAME
 * and the received file name are all the clean name.
 */
internal const val ANDROID_ATTACHMENT_SHARE_DIR_NAME = "slock-share-files"
internal const val ANDROID_ATTACHMENT_SHARE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal const val ANDROID_ATTACHMENT_SHARE_MAX_ENTRIES = 16
private val AndroidAttachmentShareEntryPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun androidMimeTypeForExtension(extension: String): String? =
    runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }.getOrNull()

private fun androidExtensionForMimeType(mimeType: String): String? =
    runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) }.getOrNull()

/**
 * The MIME type a receiver should see: parameters (`; charset=...`) dropped, lower-cased, and a
 * generic/unknown type replaced by the extension's type from [MimeTypeMap].
 */
internal fun androidAttachmentShareMimeType(
    displayName: String,
    mimeType: String?,
    typeForExtension: (String) -> String? = ::androidMimeTypeForExtension
): String = RaftShareFileName.resolveMimeType(displayName, mimeType, typeForExtension)

/** [RaftShareFileName.sanitize] with the extension of [mimeType] (when specific) as the fallback. */
internal fun sanitizeAndroidAttachmentShareFileName(displayName: String, mimeType: String?): String {
    val fallbackExtension =
        RaftShareFileName.normalizedMimeType(mimeType)
            ?.takeUnless { RaftShareFileName.isGenericMimeType(it) }
            ?.let(::androidExtensionForMimeType)
    return RaftShareFileName.sanitize(displayName, fallbackExtension)
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
 * Exposes the source bytes as a fresh `<shareRoot>/<uuid>/<sanitised name>` file and returns it:
 * a hard link to [linkFrom] (same filesystem, no extra space or copy time for large videos) when
 * that works, else a copy of [openInput]. The result must be exactly [expectedSizeBytes] long (when
 * known) or it is removed and an error raised. The private cache publishes downloads by atomic
 * rename, so a later re-download never rewrites a linked inode in place.
 */
internal fun stageAndroidAttachmentShareFile(
    shareRoot: File,
    displayName: String,
    mimeType: String?,
    expectedSizeBytes: Long,
    nowMillis: Long = System.currentTimeMillis(),
    linkFrom: File? = null,
    linkFile: (source: File, target: File) -> Unit = { source, target ->
        android.system.Os.link(source.absolutePath, target.absolutePath)
    },
    openInput: () -> InputStream
): File {
    sweepAndroidAttachmentShareStaging(shareRoot, nowMillis)
    val entry = File(shareRoot, UUID.randomUUID().toString())
    check(entry.mkdirs() || entry.isDirectory) { "Share staging unavailable" }
    val target = File(entry, sanitizeAndroidAttachmentShareFileName(displayName, mimeType))
    try {
        val linked =
            linkFrom != null &&
                linkFrom.isFile &&
                runCatching { linkFile(linkFrom, target) }.isSuccess &&
                target.isFile
        val copied =
            if (linked) {
                target.length()
            } else {
                target.delete()
                openInput().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
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
