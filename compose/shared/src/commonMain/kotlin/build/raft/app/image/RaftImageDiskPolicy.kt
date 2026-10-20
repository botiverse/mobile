package build.raft.app.image

import build.raft.app.data.RaftAvatarContentKind
import build.raft.app.data.raftAvatarContentIdentity

/**
 * Disk cache policy of the Raft network image fetcher.
 *
 *  - Only bytes that sniff as an image are committed: a 2xx body that is not an image (captive
 *    portal / proxy HTML, an error JSON) must not be pinned on disk under the image's key.
 *  - Entries of URLs whose content is not immutable (Gravatar, any other external / non-raft-avatar
 *    URL) expire after [RAFT_IMAGE_EXTERNAL_DISK_TTL_MILLIS] and are fetched again; raft immutable
 *    avatar paths (content-addressed) never expire.
 *  - The metadata file stores the MIME type and the store time ([RaftImageDiskMetadata]).
 */
internal const val RAFT_IMAGE_EXTERNAL_DISK_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

/** True when a network image URL's disk entry must expire (its content can change upstream). */
internal fun raftNetworkImageExpires(url: String): Boolean {
    val kind = runCatching { raftAvatarContentIdentity(url).kind }.getOrNull()
    return kind != RaftAvatarContentKind.RaftImmutable
}

internal data class RaftImageDiskMetadata(
    val mimeType: String?,
    val storedAtEpochMillis: Long?,
) {
    fun encode(): String = "${mimeType.orEmpty()}\n${storedAtEpochMillis ?: ""}"

    companion object {
        /** Parses [encode]'s output; a legacy entry (MIME type only) has no store time. */
        fun decode(text: String?): RaftImageDiskMetadata {
            val lines = text.orEmpty().split('\n')
            return RaftImageDiskMetadata(
                mimeType = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                storedAtEpochMillis = lines.getOrNull(1)?.trim()?.toLongOrNull(),
            )
        }
    }
}

/** Whether a disk entry may be served without fetching again. */
internal fun raftImageDiskEntryFresh(
    metadata: RaftImageDiskMetadata,
    expires: Boolean,
    nowEpochMillis: Long,
    ttlMillis: Long = RAFT_IMAGE_EXTERNAL_DISK_TTL_MILLIS,
): Boolean {
    if (!expires) return true
    val storedAt = metadata.storedAtEpochMillis ?: return false
    val age = nowEpochMillis - storedAt
    return age in 0 until ttlMillis
}

/**
 * The image format [bytes] start with (MIME type), or null when they are not a recognisable
 * image. Covers every format the Raft loader decodes: PNG, JPEG, GIF, WebP, BMP, ICO, HEIC/HEIF,
 * AVIF and SVG (text, optionally behind an XML prolog / BOM / comments).
 */
internal fun raftSniffImageMimeType(bytes: ByteArray): String? {
    fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
    fun ascii(offset: Int, text: String): Boolean =
        text.indices.all { at(offset + it) == text[it].code }
    return when {
        at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        ascii(0, "BM") && bytes.size > 14 -> "image/bmp"
        at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0 -> "image/x-icon"
        ascii(4, "ftyp") -> {
            val brand = bytes.copyOfRange(8, minOf(bytes.size, 12)).decodeToString()
            when {
                brand.startsWith("avi") -> "image/avif"
                brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") -> "image/heic"
                else -> null
            }
        }
        raftLooksLikeSvg(bytes) -> "image/svg+xml"
        else -> null
    }
}

private fun raftLooksLikeSvg(bytes: ByteArray): Boolean {
    val head = bytes.copyOfRange(0, minOf(bytes.size, 1024)).decodeToString().trimStart('﻿').trimStart()
    if (head.startsWith("<svg", ignoreCase = true)) return true
    if (!head.startsWith("<?xml") && !head.startsWith("<!--") && !head.startsWith("<!DOCTYPE svg", ignoreCase = true)) {
        return false
    }
    return head.contains("<svg", ignoreCase = true) && !head.contains("<html", ignoreCase = true)
}
