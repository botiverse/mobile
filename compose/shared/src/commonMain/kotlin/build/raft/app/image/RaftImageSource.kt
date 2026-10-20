package build.raft.app.image

import build.raft.app.data.RaftAvatarContentIdentity
import build.raft.app.data.RaftAvatarMountedRenderDescriptor
import build.raft.app.data.parseRaftAvatarMountedRenderModel
import build.raft.app.data.raftAvatarContentIdentity
import build.raft.app.raftCanonicalIdentityBytes
import build.raft.app.raftSha256Hex
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options

/**
 * The image sources Raft resolves itself (everything Coil 3 core cannot load on its own). Every
 * other model string (`file://`, `content://`, `data:`, absolute paths) falls through to Coil's
 * built-in fetchers.
 *
 * This is the CMP replacement of the Kuikly image adapters (Android `SlockKuiklyImageAdapter`,
 * iOS `KuiklyRenderComponentExpandHandler.hr_setImageWithUrl`): same routes, same order —
 * managed avatar model first, then inline SVG, bundled `assets://`, then http(s).
 */
internal sealed interface RaftImageSource {
    /** Memory/disk cache identity (task #403: content fingerprint; Coil appends render params). */
    val cacheKey: String

    /** http(s) image loaded through NetworkKMM ([build.raft.app.network.SharedBinaryDownloader]). */
    class Network(val url: String) : RaftImageSource {
        override val cacheKey: String by lazy { raftNetworkImageCacheKey(url) }
    }

    /** `assets://<path>`: an MR / app bundled asset (Android APK assets, iOS main or MR bundle). */
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
    if (raw.startsWith("<svg", ignoreCase = true)) return RaftImageSource.InlineSvg(raw)
    if (raw.startsWith(RaftAssetsScheme)) {
        val path = raw.removePrefix(RaftAssetsScheme).trimStart('/')
        return if (path.isEmpty()) null else RaftImageSource.BundledAsset(path)
    }
    val lower = raw.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) return RaftImageSource.Network(raw)
    return null
}

/**
 * task #403 unified key, URL half: the same content identity the managed path derives its
 * `nativeBytesKey` from (`raftAvatarContentIdentity`: gravatar hash + query, raft immutable
 * avatar path, otherwise the fragment-less URL), without the owner domain — the adapter has no
 * owner context for a bare URL (accepted trade-off in the design, §5).
 */
internal fun raftNetworkImageCacheKey(url: String): String {
    val identity: RaftAvatarContentIdentity? = runCatching { raftAvatarContentIdentity(url) }.getOrNull()
    return if (identity != null) {
        "raft-img-v1:url:${identity.kind.storageValue}:${identity.fingerprint}"
    } else {
        "raft-img-v1:url-raw:${url.trim().substringBefore('#')}"
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
