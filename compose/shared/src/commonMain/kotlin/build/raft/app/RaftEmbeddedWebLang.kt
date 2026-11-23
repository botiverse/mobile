package build.raft.app

import build.raft.app.format.RaftFormatLocale

/**
 * The query parameter an embedding host names the page's language with (web `resolveLangParamLocale`,
 * packages/web/src/i18n/locale.ts): it outranks the page's stored choice and the browser's languages,
 * and the web never stores it, so one embedded visit can't rewrite the person's own setting.
 */
internal const val RAFT_EMBEDDED_WEB_LANG_PARAM = "lang"

/**
 * The `lang` every Raft web page the App embeds is opened with: the App's current UI language (the
 * Language setting, or the device's matched to the App's catalogs when it is System;
 * [RaftFormatLocale.current]). Without it the page follows the WebView's own languages, which on iOS
 * are the App bundle's and on an English device are English, so an embedded page's menus and labels
 * came out in English under a Chinese App (huxijin, #proj-workspace:#388).
 */
internal fun raftEmbeddedWebLangTag(): String = raftEmbeddedWebLangTag(RaftFormatLocale.current())

/**
 * [locale] as the web's display-locale code (`en`, `es`, `pt-br`, `ru`, `ko`, `ja`, `zh-cn`, `zh-tw`;
 * slock packages/shared/src/displayLocales.ts, the value `normalizeDisplayLocale` matches exactly).
 */
internal fun raftEmbeddedWebLangTag(locale: RaftFormatLocale): String = locale.languageTag.lowercase()

/** [tag] as a `lang` value (BCP 47 letters and hyphens only), or null when it is none. */
internal fun raftEmbeddedWebLangOrNull(tag: String?): String? =
    tag?.trim()?.takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' } }

/**
 * [url] with `lang=[tag]`: a `lang` already there is replaced; the path, every other parameter and
 * the fragment stay as they are. [url] unchanged when [tag] is none.
 */
internal fun raftWebUrlWithLang(url: String, tag: String?): String {
    val lang = raftEmbeddedWebLangOrNull(tag) ?: return url
    val fragment = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
    val beforeFragment = url.removeSuffix(fragment)
    val path = beforeFragment.substringBefore('?')
    val kept =
        beforeFragment.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != RAFT_EMBEDDED_WEB_LANG_PARAM }
    return path + "?" + (kept + "$RAFT_EMBEDDED_WEB_LANG_PARAM=$lang").joinToString("&") + fragment
}
