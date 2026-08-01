package build.raft.app

import build.raft.app.resources.MR

// On iOS load the provider icon from its MR-bundled SVG asset (decoded via the
// SVG coder registered at startup). Mirrors the reaction-atlas asset pattern
// (`assets://<originalPath>`); #409's hr_customBundleUrlForFileName resolves the
// file inside build.raft.app.resources.MR.bundle.
internal actual fun providerIconIosSvgSource(key: ProviderIconKey): String? = when (key) {
    ProviderIconKey.GoogleColor -> "assets://${MR.assets.icons.provider_google.originalPath}"
    ProviderIconKey.GitHubBlack -> "assets://${MR.assets.icons.provider_github.originalPath}"
    ProviderIconKey.GitHubWhite -> "assets://${MR.assets.icons.provider_github_white.originalPath}"
    ProviderIconKey.AppleBlack -> "assets://${MR.assets.icons.provider_apple.originalPath}"
    ProviderIconKey.AppleWhite -> "assets://${MR.assets.icons.provider_apple_white.originalPath}"
}
