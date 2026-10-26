package build.raft.app.data

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.resources.StringDesc

/** Opaque process-locale state returned by [applyVisualCaptureLocale]. */
class VisualCaptureLocaleRestoreToken internal constructor(
    internal val runtimeLocale: AppLocale?,
    internal val stringDescLocaleType: StringDesc.LocaleType
)

/**
 * Resolves the deterministic locale accepted by the visual capture harness.
 * An absent value keeps the process default; unsupported explicit values fail
 * closed so a mistyped lane cannot silently produce English evidence.
 */
fun parseVisualCaptureLocale(value: String?): AppLocale? {
    val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalized) {
        "en", "en-us", "english" -> AppLocale.English
        "zh", "zh-cn", "zh-hans", "zh-rcn" -> AppLocale.SimplifiedChinese
        "ja", "ja-jp", "japanese" -> AppLocale.Japanese
        else -> throw IllegalArgumentException("Unsupported visual capture locale: $value")
    }
}

/**
 * Applies a locale before a visual page's first composition and returns the
 * prior process state. The runtime projection is updated with StringDesc so an
 * app-shell locale seed cannot overwrite the requested capture lane.
 */
fun AppLocale.applyVisualCaptureLocale(): VisualCaptureLocaleRestoreToken {
    require(this != AppLocale.System) { "Visual capture requires a deterministic locale" }
    val token =
        VisualCaptureLocaleRestoreToken(
            runtimeLocale = KmmResourceLocaleRuntime.locale.value,
            stringDescLocaleType = StringDesc.localeType
        )
    KmmResourceLocaleRuntime.replaceForTest(this)
    StringDesc.localeType = stringDescLocaleType
    return token
}

/** Restores the process state saved by [applyVisualCaptureLocale]. */
fun restoreVisualCaptureLocale(token: VisualCaptureLocaleRestoreToken) {
    KmmResourceLocaleRuntime.replaceForTest(token.runtimeLocale)
    StringDesc.localeType = token.stringDescLocaleType
}
