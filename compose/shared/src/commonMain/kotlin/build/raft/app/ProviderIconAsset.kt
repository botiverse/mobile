package build.raft.app

import build.raft.app.resources.MR

/**
 * OAuth provider icons ship as MR-bundled SVG assets and are loaded through the Kuikly image
 * path with an `assets://<originalPath>` source on every platform (task #60, closing task #436).
 *
 * Why one shared path: the previous per-platform split (iOS: MR asset; Android/OHOS: inline
 * `data:image/svg+xml` URI) rendered blank on OHOS, because the OHOS renderer treats a
 * `data:image` source as a memory-cache key rather than decoding it inline. `assets://` is
 * handled by every host: Android's image adapter routes any `.svg` path through its SVG
 * decoder and iOS decodes through the SVG coder registered at startup. (The OHOS host, frozen
 * at 1.14.x, resolved it under its HAP resfile directory.)
 *
 * The lookup is guarded because touching `MR.assets` may throw during bundle initialisation on
 * some hosts (see `ThreadReactionSpriteCatalog`); a `null` source renders nothing rather than
 * crashing.
 */
internal enum class ProviderIconKey { GoogleColor, GitHubBlack, GitHubWhite, AppleBlack, AppleWhite }

internal fun providerIconAssetSource(key: ProviderIconKey): String? =
    runCatching {
        val asset =
            when (key) {
                ProviderIconKey.GoogleColor -> MR.assets.icons.provider_google
                ProviderIconKey.GitHubBlack -> MR.assets.icons.provider_github
                ProviderIconKey.GitHubWhite -> MR.assets.icons.provider_github_white
                ProviderIconKey.AppleBlack -> MR.assets.icons.provider_apple
                ProviderIconKey.AppleWhite -> MR.assets.icons.provider_apple_white
            }
        "assets://${asset.originalPath}"
    }.getOrNull()
