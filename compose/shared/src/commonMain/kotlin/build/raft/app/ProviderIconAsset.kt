package build.raft.app

/**
 * task #436: OAuth provider icons ship as SVG. iOS has no native
 * SVG rendering in Kuikly's image path, so the inline SVG data URIs rendered
 * blank. On iOS we instead load the icon from an MR-bundled `.svg` asset, which
 * flows through the SVG coder registered at app startup (see iOSApp.swift). Other
 * platforms already render the inline SVG data URI fine, so their actual returns
 * `null` and the caller keeps using the existing data URI — no regression there.
 */
internal enum class ProviderIconKey { GoogleColor, GitHubBlack, GitHubWhite, AppleBlack, AppleWhite }

internal expect fun providerIconIosSvgSource(key: ProviderIconKey): String?
