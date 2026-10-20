package build.raft.app.data

import kotlinx.coroutines.CancellationException

internal const val HEADER_ACCEPT_LANGUAGE: String = "Accept-Language"

/**
 * BCP 47 tag for server-rendered runtime form copy (slock runtime-form v2: the server answers in the
 * `Accept-Language` it is sent and falls back to English). The App Language choice wins; "System
 * default" sends the device language. Null sends no header, which the server treats as English.
 */
internal fun runtimeFormRequestLanguageTag(
    appLocale: () -> AppLocale = { KeyValueLocalePreferenceStore(raftSharedPreferenceStore()).readLocaleSync() },
    systemLanguageTag: () -> String? = ::currentSystemLanguageTag,
): String? {
    val locale =
        try {
            appLocale()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AppLocale.System
        }
    val tag = if (locale == AppLocale.System) systemLanguageTag() else locale.kmmResourceLocaleTag
    return tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
}

/** The `Accept-Language` header for a runtime form request, or no header when the language is unknown. */
internal fun runtimeFormLanguageHeaders(languageTag: String?): Map<String, String> =
    languageTag?.let { mapOf(HEADER_ACCEPT_LANGUAGE to it) }.orEmpty()
