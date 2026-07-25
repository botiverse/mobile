package build.raft.app.shared

import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale

internal data class AndroidGalleryImageValidation(
    val mimeType: String,
    val width: Int,
    val height: Int
)

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
    if (claimed.isNotBlank() && normalizeAndroidImageMimeType(claimed) != actual) return false

    val extension = fileName.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .trim().lowercase(Locale.US)
    if (extension.isBlank()) return true
    val extensionMime = androidGalleryImageMimeTypeForExtension(extension)
    return extensionMime == actual
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
