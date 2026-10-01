package build.raft.app

import build.raft.app.resources.MR

/**
 * OAuth provider icons ship as MR-bundled SVG assets and are loaded through the Kuikly image
 * path with an `assets://<originalPath>` source on every platform (task #60, closing task #436).
 *
 * Why one shared path: the previous per-platform split (iOS: MR asset; Android/OHOS: inline
 * `data:image/svg+xml` URI) rendered blank on OHOS, because the OHOS renderer treats a
 * `data:image` source as a memory-cache key rather than decoding it inline. `assets://` is
 * handled by all three hosts: Android's image adapter routes any `.svg` path through its SVG
 * decoder, iOS decodes through the SVG coder registered at startup, and OHOS resolves the path
 * under its resfile directory (the OHOS MR `originalPath` already carries the `assets/` prefix,
 * matching where `stage_ohos_mr_resources.sh` places MR assets in the HAP).
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
