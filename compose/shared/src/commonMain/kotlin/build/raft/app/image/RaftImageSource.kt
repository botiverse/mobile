package build.raft.app.image

import build.raft.app.data.RaftAvatarContentIdentity
import build.raft.app.data.RaftAvatarMountedRenderDescriptor
import build.raft.app.data.parseRaftAvatarMountedRenderModel
import build.raft.app.data.percentDecodeStrict
import build.raft.app.data.raftAvatarContentIdentity
import build.raft.app.network.raftRewriteRetiredContentHost
import build.raft.app.raftCanonicalIdentityBytes
import build.raft.app.raftSha256Hex
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options

/**
 * The image sources Raft resolves itself (everything Coil 3 core cannot load on its own). Every
 * other model string (`file://`, `content://`, base64 `data:`, absolute paths) falls through to
 * Coil's built-in fetchers.
 *
 * This is the CMP replacement of the Kuikly image adapters (Android `SlockKuiklyImageAdapter`,
 * iOS `KuiklyRenderComponentExpandHandler.hr_setImageWithUrl`): same routes, same order —
 * managed avatar model first, then inline SVG, bundled `assets://`, then http(s).
 *
 * [raftImageSourceOf] is also the single place a stored image URL on the retired production
 * content host is moved to the CDN host ([raftRewriteRetiredContentHost]): every http(s) image
 * (attachment thumbnails, inline images, avatars, Markdown images) from the local database, cached
 * snapshots or server payloads is resolved here, and the cache key is derived from the rewritten
 * URL, so an old and a new payload of the same object share one cache entry.
 */
internal sealed interface RaftImageSource {
    /** Memory/disk cache identity (task #403: content fingerprint; Coil appends render params). */
    val cacheKey: String

    /** http(s) image loaded through the app Ktor client ([build.raft.app.network.SharedBinaryDownloader]). */
    class Network(val url: String) : RaftImageSource {
        override val cacheKey: String by lazy { raftNetworkImageCacheKey(url) }
    }

    /**
     * `assets://<path>`: an app bundled asset (Android APK assets, iOS main bundle), or a Compose
     * resources file when <path> starts with `compose-resources/`.
     */
    class BundledAsset(val path: String) : RaftImageSource {
        override val cacheKey: String get() = "raft-img-v1:asset:$path"
    }

    /** An inline `<svg ...>` document passed as the model itself. */
    class InlineSvg(val text: String) : RaftImageSource {
        override val cacheKey: String by lazy {
            "raft-img-v1:svg:" + raftSha256Hex(raftCanonicalIdentityBytes(listOf("raft-inline-svg-v1", text)))
        }
    }

    /**
     * `slock-avatar-render-v1:` managed avatar model. Bytes come from the host's durable avatar
     * cache ([RaftManagedImageReader]); the key is the content fingerprint (`nativeBytesKey`), so
     * a new commit of the same content reuses the decoded image (task #403). Commit freshness is
     * validated by [RaftManagedImageCommitInterceptor] instead of being part of the key.
     */
    /**
     * `raft-sent-attachment:<attachmentId>`: an image this device just uploaded, served from the
     * local copy in [RaftSentAttachmentLocalImages] instead of being downloaded again.
     */
    class SentAttachment(val attachmentId: String) : RaftImageSource {
        override val cacheKey: String get() = raftSentAttachmentCacheKey(attachmentId)
    }

    class ManagedAvatar(val model: String, val descriptor: RaftAvatarMountedRenderDescriptor) : RaftImageSource {
        override val cacheKey: String get() = raftManagedImageCacheKey(descriptor)
    }
}

internal const val RaftAssetsScheme = "assets://"

/** Classifies a model string; null means "not ours, let Coil handle it". */
internal fun raftImageSourceOf(model: String): RaftImageSource? {
    val raw = model.trim()
    if (raw.isEmpty()) return null
    parseRaftAvatarMountedRenderModel(raw)?.let { descriptor ->
        return RaftImageSource.ManagedAvatar(raw, descriptor)
    }
    if (raw.startsWith(RaftSentAttachmentModelPrefix)) {
        val attachmentId = raw.removePrefix(RaftSentAttachmentModelPrefix).trim()
        return if (attachmentId.isEmpty()) null else RaftImageSource.SentAttachment(attachmentId)
    }
    if (raw.startsWith("<svg", ignoreCase = true)) return RaftImageSource.InlineSvg(raw)
    raftInlineSvgFromTextDataUri(raw)?.let { return RaftImageSource.InlineSvg(it) }
    if (raw.startsWith(RaftAssetsScheme)) {
        val path = raw.removePrefix(RaftAssetsScheme).trimStart('/')
        return if (path.isEmpty()) null else RaftImageSource.BundledAsset(path)
    }
    val lower = raw.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        // Stored payloads from before the R2 domain move still carry `content.raft.build`; load (and
        // key the memory/disk cache by) the CDN URL the server hands out today for the same object.
        return RaftImageSource.Network(raftRewriteRetiredContentHost(raw))
    }
    return null
}

/**
 * The SVG document of a textual `data:image/svg+xml[;utf8|;charset=...],<percent-encoded svg>`
 * URI, or null for anything else. Coil's own `DataUriFetcher` reads only the `;base64,` form and
 * rejects this one as an invalid data URI, so it rendered as the image placeholder; the Kuikly
 * adapters (and browsers) decode it. Base64 SVG data URIs stay with Coil.
 */
internal fun raftInlineSvgFromTextDataUri(raw: String): String? {
    if (!raw.startsWith("data:", ignoreCase = true)) return null
    val comma = raw.indexOf(',')
    if (comma < 0) return null
    val parameters = raw.substring("data:".length, comma).split(';').map { it.trim().lowercase() }
    if (parameters.first() != SVG_DATA_URI_MIME || "base64" in parameters) return null
    return percentDecodeStrict(raw.substring(comma + 1))?.takeIf { it.isNotBlank() }
}

private const val SVG_DATA_URI_MIME = "image/svg+xml"

/**
 * task #403 unified key, URL half: the same content identity the managed path derives its
 * `nativeBytesKey` from (`raftAvatarContentIdentity`: the raft immutable
 * avatar path, otherwise the fragment-less URL), without the owner domain — the adapter has no
 * owner context for a bare URL (accepted trade-off in the design, §5).
 */
internal const val RaftAttachmentImageCacheKeyPrefix = "raft-img-v1:attachment:"

/**
 * #proj-mobile:#293: stable Coil memory/disk cache key for an original attachment image, keyed by
 * attachment id rather than its 5-minute presigned R2 URL. An attachment's bytes are immutable once
 * uploaded, so viewing it in the message list and later opening full-screen preview (even after
 * re-signing) hits the exact same cache entry.
 */
internal fun raftAttachmentOriginalImageCacheKey(attachmentId: String?): String? {
    val clean = attachmentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RaftAttachmentImageCacheKeyPrefix + clean
}

internal fun raftImageDiskKeyIsImmutable(diskKey: String): Boolean =
    diskKey.startsWith(RaftAttachmentImageCacheKeyPrefix)

internal fun raftNetworkImageCacheKey(url: String): String {
    val canonical = raftRewriteRetiredContentHost(url.trim())
    val identity: RaftAvatarContentIdentity? = runCatching { raftAvatarContentIdentity(canonical) }.getOrNull()
    return if (identity != null) {
        "raft-img-v1:url:${identity.kind.storageValue}:${identity.fingerprint}"
    } else {
        "raft-img-v1:url-raw:${canonical.substringBefore('#')}"
    }
}

/**
 * task #403 unified key, managed half: the content fingerprint (`nativeBytesKey`).
 * TODO(cmp-switch): the design's optional `contentKey` model field (so url and managed loads of
 * the same image share one decoded entry) is not on the wire yet; until shared emits it the two
 * halves stay in separate key domains (same behaviour as the Kuikly adapters).
 */
internal fun raftManagedImageCacheKey(descriptor: RaftAvatarMountedRenderDescriptor): String =
    "raft-img-v1:managed:${descriptor.slot.nativeBytesKey}"

/** String model -> [RaftImageSource] (runs before Coil's own String -> Uri mapper). */
internal object RaftImageSourceMapper : Mapper<String, RaftImageSource> {
    override fun map(data: String, options: Options): RaftImageSource? = raftImageSourceOf(data)
}

internal object RaftImageSourceKeyer : Keyer<RaftImageSource> {
    override fun key(data: RaftImageSource, options: Options): String = data.cacheKey
}
