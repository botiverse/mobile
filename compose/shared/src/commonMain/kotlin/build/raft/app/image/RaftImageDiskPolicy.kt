package build.raft.app.image

import build.raft.app.data.RaftAvatarContentKind
import build.raft.app.data.raftAvatarContentIdentity

/**
 * Disk cache policy of the Raft network image fetcher.
 *
 *  - Only bytes that sniff as an image are committed: a 2xx body that is not an image (captive
 *    portal / proxy HTML, an error JSON) must not be pinned on disk under the image's key.
 *  - Entries of URLs whose content is not immutable (any external / non-raft-avatar
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

internal data class RaftHttpCacheControl(
    val noStore: Boolean = false,
    val noCache: Boolean = false,
    val immutable: Boolean = false,
    val maxAgeSeconds: Long? = null,
)

/**
 * Parses an HTTP `Cache-Control` response header (RFC 9111 §5.2.2) for image disk caching
 * (#proj-mobile:#293): recognizes `no-store`, `no-cache`, `immutable`, and `max-age=<seconds>`.
 */
internal fun parseRaftHttpCacheControl(header: String?): RaftHttpCacheControl {
    val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return RaftHttpCacheControl()
    var noStore = false
    var noCache = false
    var immutable = false
    var maxAgeSeconds: Long? = null
    raw.split(',').forEach { token ->
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@forEach
        val eq = trimmed.indexOf('=')
        val name = (if (eq >= 0) trimmed.substring(0, eq) else trimmed).trim().lowercase()
        val value = if (eq >= 0) trimmed.substring(eq + 1).trim().trim('"') else null
        when (name) {
            "no-store" -> noStore = true
            "no-cache" -> noCache = true
            "immutable" -> immutable = true
            "max-age" -> {
                val seconds = value?.toLongOrNull()
                if (seconds != null) {
                    if (seconds <= 0L) {
                        noCache = true
                        maxAgeSeconds = 0L
                    } else {
                        maxAgeSeconds = seconds
                    }
                }
            }
        }
    }
    return RaftHttpCacheControl(
        noStore = noStore,
        noCache = noCache,
        immutable = immutable,
        maxAgeSeconds = maxAgeSeconds,
    )
}

internal data class RaftImageDiskMetadata(
    val mimeType: String?,
    val storedAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long? = null,
    val immutable: Boolean = false,
    val noCache: Boolean = false,
    val eTag: String? = null,
    val lastModified: String? = null,
) {
    fun encode(): String =
        listOf(
            mimeType.orEmpty().sanitizeMetadataLine(),
            storedAtEpochMillis?.toString().orEmpty(),
            expiresAtEpochMillis?.toString().orEmpty(),
            if (immutable) "1" else "",
            if (noCache) "1" else "",
            eTag.orEmpty().sanitizeMetadataLine(),
            lastModified.orEmpty().sanitizeMetadataLine(),
        ).joinToString("\n")

    fun refreshedOnNotModified(
        cacheControlHeader: String?,
        eTag: String?,
        lastModified: String?,
        ageSeconds: Long?,
        nowEpochMillis: Long,
    ): RaftImageDiskMetadata {
        val parsed = parseRaftHttpCacheControl(cacheControlHeader)
        val hasCacheControl = !cacheControlHeader.isNullOrBlank()
        val nextMaxAge = parsed.maxAgeSeconds
        val nextExpiresAt =
            if (nextMaxAge != null && nextMaxAge > 0L) {
                val effectiveSeconds = (nextMaxAge - (ageSeconds ?: 0L).coerceAtLeast(0L)).coerceAtLeast(0L)
                addSecondsMillisSaturating(nowEpochMillis, effectiveSeconds)
            } else if (hasCacheControl) {
                null
            } else {
                // Revalidated without a new Cache-Control header: preserve previous duration if any.
                val previousDuration =
                    if (expiresAtEpochMillis != null && storedAtEpochMillis != null && expiresAtEpochMillis > storedAtEpochMillis) {
                        expiresAtEpochMillis - storedAtEpochMillis
                    } else {
                        null
                    }
                previousDuration?.let { duration ->
                    if (duration > Long.MAX_VALUE - nowEpochMillis.coerceAtLeast(0L)) Long.MAX_VALUE else nowEpochMillis + duration
                }
            }
        return copy(
            storedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = nextExpiresAt,
            immutable = if (hasCacheControl) parsed.immutable else immutable,
            noCache = if (hasCacheControl) parsed.noCache else noCache,
            eTag = eTag?.trim()?.takeIf { it.isNotEmpty() } ?: this.eTag,
            lastModified = lastModified?.trim()?.takeIf { it.isNotEmpty() } ?: this.lastModified,
        )
    }

    companion object {
        /** Parses [encode]'s output; a legacy 1- or 2-line entry decodes cleanly with null validators. */
        fun decode(text: String?): RaftImageDiskMetadata {
            val lines = text.orEmpty().split('\n')
            return RaftImageDiskMetadata(
                mimeType = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                storedAtEpochMillis = lines.getOrNull(1)?.trim()?.toLongOrNull(),
                expiresAtEpochMillis = lines.getOrNull(2)?.trim()?.toLongOrNull(),
                immutable = lines.getOrNull(3)?.trim() == "1",
                noCache = lines.getOrNull(4)?.trim() == "1",
                eTag = lines.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() },
                lastModified = lines.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

private fun String.sanitizeMetadataLine(): String = replace('\n', ' ').replace('\r', ' ').trim()

private fun addSecondsMillisSaturating(nowEpochMillis: Long, seconds: Long): Long {
    val cleanSeconds = seconds.coerceAtLeast(0L)
    val maxAddSeconds = (Long.MAX_VALUE - nowEpochMillis.coerceAtLeast(0L)) / 1000L
    return if (cleanSeconds >= maxAddSeconds) Long.MAX_VALUE else nowEpochMillis + cleanSeconds * 1000L
}

/**
 * Builds disk cache metadata from HTTP response headers, or returns `null` when `Cache-Control: no-store`
 * forbids persisting the response on disk (#proj-mobile:#293).
 */
internal fun raftImageDiskMetadataFromHttpHeaders(
    mimeType: String?,
    cacheControlHeader: String?,
    eTag: String?,
    lastModified: String?,
    ageSeconds: Long?,
    nowEpochMillis: Long,
): RaftImageDiskMetadata? {
    val parsed = parseRaftHttpCacheControl(cacheControlHeader)
    if (parsed.noStore) return null
    val expiresAt =
        parsed.maxAgeSeconds
            ?.takeIf { it > 0L }
            ?.let { maxAge ->
                val remainingSeconds = (maxAge - (ageSeconds ?: 0L).coerceAtLeast(0L)).coerceAtLeast(0L)
                addSecondsMillisSaturating(nowEpochMillis, remainingSeconds)
            }
    return RaftImageDiskMetadata(
        mimeType = mimeType,
        storedAtEpochMillis = nowEpochMillis,
        expiresAtEpochMillis = expiresAt,
        immutable = parsed.immutable,
        noCache = parsed.noCache,
        eTag = eTag?.trim()?.takeIf { it.isNotEmpty() },
        lastModified = lastModified?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/** Whether a disk entry may be served without fetching or conditionally revalidating. */
internal fun raftImageDiskEntryFresh(
    metadata: RaftImageDiskMetadata,
    expires: Boolean,
    nowEpochMillis: Long,
    ttlMillis: Long = RAFT_IMAGE_EXTERNAL_DISK_TTL_MILLIS,
): Boolean {
    if (metadata.noCache) return false
    if (!expires) return true
    val storedAt = metadata.storedAtEpochMillis
    if (storedAt != null && nowEpochMillis < storedAt) return false
    val explicitExpiry = metadata.expiresAtEpochMillis
    if (explicitExpiry != null) {
        return nowEpochMillis < explicitExpiry
    }
    if (metadata.immutable) return true
    if (storedAt == null) return false
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
