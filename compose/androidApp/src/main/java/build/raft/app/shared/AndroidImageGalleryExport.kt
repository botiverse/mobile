package build.raft.app.shared

import android.database.SQLException
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale

internal data class AndroidGalleryImageValidation(
    val mimeType: String,
    val width: Int,
    val height: Int
)

internal data class LegacyAndroidGalleryInsertResult(
    val uri: String,
    val displayName: String,
    val absolutePath: String
)

internal const val LEGACY_ANDROID_GALLERY_MAX_INSERT_ATTEMPTS = 100

/**
 * Inserts a pre-Android-Q MediaStore image without ever binding to an existing user file.
 *
 * Old MediaProvider versions enforce a case-insensitive unique constraint on `_data`. A file
 * existence check handles the normal collision case, while advancing after a null/failed insert
 * closes the race between that check and the provider insert. Every candidate is attempted at
 * most once, so a persistent provider failure remains bounded.
 */
internal fun insertLegacyAndroidGalleryImage(
    directory: File,
    requestedDisplayName: String,
    pathExists: (File) -> Boolean = File::exists,
    insertCandidate: (displayName: String, absolutePath: String) -> String?
): LegacyAndroidGalleryInsertResult? {
    for (index in 0 until LEGACY_ANDROID_GALLERY_MAX_INSERT_ATTEMPTS) {
        val candidateName = legacyAndroidGalleryCandidateName(requestedDisplayName, index)
        val candidateFile = File(directory, candidateName)
        if (pathExists(candidateFile)) continue
        val uri = try {
            insertCandidate(candidateName, candidateFile.absolutePath)
        } catch (_: SQLException) {
            null
        } ?: continue
        return LegacyAndroidGalleryInsertResult(uri, candidateName, candidateFile.absolutePath)
    }
    return null
}

internal fun legacyAndroidGalleryCandidateName(displayName: String, collisionIndex: Int): String {
    require(collisionIndex >= 0)
    if (collisionIndex == 0) return displayName
    val lastDot = displayName.lastIndexOf('.')
    val hasExtension = lastDot > 0 && lastDot < displayName.lastIndex
    val basename = if (hasExtension) displayName.substring(0, lastDot) else displayName
    val extension = if (hasExtension) displayName.substring(lastDot) else ""
    return "$basename ($collisionIndex)$extension"
}

internal fun validateAndroidGalleryImage(
    source: File,
    fileName: String,
    claimedMimeType: String?
): AndroidGalleryImageValidation? {
    if (!source.isFile || source.length() <= 0L) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, options)
    val actualMimeType = normalizeAndroidImageMimeType(options.outMimeType) ?: return null
    if (options.outWidth <= 0 || options.outHeight <= 0) return null

    if (!androidGalleryImageMetadataMatches(fileName, claimedMimeType, actualMimeType)) return null
    return AndroidGalleryImageValidation(actualMimeType, options.outWidth, options.outHeight)
}

internal fun androidGalleryImageMetadataMatches(
    fileName: String,
    claimedMimeType: String?,
    actualMimeType: String
): Boolean {
    val actual = normalizeAndroidImageMimeType(actualMimeType) ?: return false
    val claimed = claimedMimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
    if (
        claimed.isNotBlank() &&
        claimed !in setOf("application/octet-stream", "binary/octet-stream") &&
        normalizeAndroidImageMimeType(claimed) != actual
    ) return false

    val extension = fileName.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .trim().lowercase(Locale.US)
    val extensionMime = androidGalleryImageMimeTypeForExtension(extension)
    return extensionMime == null || extensionMime == actual
}

internal fun androidGalleryImageMimeTypeForExtension(extension: String): String? =
    when (extension.trim().lowercase(Locale.US)) {
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heif"
        "bmp", "dib" -> "image/bmp"
        else -> null
    }

internal fun normalizeAndroidImageMimeType(value: String?): String? =
    when (value?.substringBefore(';')?.trim()?.lowercase(Locale.US)) {
        "image/jpg", "image/jpeg", "image/pjpeg" -> "image/jpeg"
        "image/png", "image/x-png" -> "image/png"
        "image/webp" -> "image/webp"
        "image/gif" -> "image/gif"
        "image/heic", "image/heif" -> "image/heif"
        "image/bmp", "image/x-ms-bmp" -> "image/bmp"
        else -> null
    }
