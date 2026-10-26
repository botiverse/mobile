package build.raft.app

import build.raft.app.resources.RaftBundledFiles

/**
 * OAuth provider icons ship as Compose resources files (`composeResources/files/icons`) and are
 * loaded through the Raft image loader with an `assets://` source on every platform (task #60,
 * closing task #436).
 *
 * Why one shared path: the previous per-platform split (iOS: MR asset; Android: inline
 * `data:image/svg+xml` URI) rendered blank on a renderer that treated a
 * `data:image` source as a memory-cache key rather than decoding it inline. `assets://` is
 * handled by every host: Android's image adapter routes any `.svg` path through its SVG
 * decoder and iOS decodes through the SVG coder registered at startup.
 *
 * The lookup stays guarded (it used to touch the kmmresource bundle, which could throw during
 * initialisation); a `null` source renders nothing rather than crashing.
 */
internal enum class ProviderIconKey { GoogleColor, GitHubBlack, GitHubWhite, AppleBlack, AppleWhite }

internal fun providerIconAssetSource(key: ProviderIconKey): String? =
    runCatching {
        val asset =
            when (key) {
                ProviderIconKey.GoogleColor -> RaftBundledFiles.providerGoogle
                ProviderIconKey.GitHubBlack -> RaftBundledFiles.providerGithub
                ProviderIconKey.GitHubWhite -> RaftBundledFiles.providerGithubWhite
                ProviderIconKey.AppleBlack -> RaftBundledFiles.providerApple
                ProviderIconKey.AppleWhite -> RaftBundledFiles.providerAppleWhite
            }
        asset.imageUrl
    }.getOrNull()
