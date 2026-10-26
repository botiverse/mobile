package build.raft.app.resources

import build.raft.app.image.RaftAssetsScheme

/**
 * A file shipped in `composeResources/files` (the former kmmresource `MR.assets`). [path] is the
 * Compose resources path (`files/...`, read with `Res.readBytes`); [imageUrl] is the model the
 * Raft image loader resolves (see `RaftBundledAssetFetcher`).
 */
class RaftBundledFile internal constructor(val path: String) {
    val imageUrl: String get() = RaftAssetsScheme + RaftComposeResourcesAssetPrefix + path

    override fun equals(other: Any?): Boolean = other is RaftBundledFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = "RaftBundledFile($path)"
}

/** `assets://compose-resources/<path>`: a Compose resources file, not a platform bundle asset. */
internal const val RaftComposeResourcesAssetPrefix = "compose-resources/"

internal object RaftBundledFiles {
    val providerGithubWhite = RaftBundledFile("files/icons/provider_github_white.svg")
    val providerGithub = RaftBundledFile("files/icons/provider_github.svg")
    val providerGoogle = RaftBundledFile("files/icons/provider_google.svg")
    val providerApple = RaftBundledFile("files/icons/provider_apple.svg")
    val providerAppleWhite = RaftBundledFile("files/icons/provider_apple_white.svg")
    val reactionAtlasV1 = RaftBundledFile("files/reactions/reaction_atlas_v1.png")
    val reactionAtlasV1Manifest = RaftBundledFile("files/reactions/reaction_atlas_v1_manifest.json")
}
