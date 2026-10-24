package build.raft.app.format

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.data.AppLocale
import build.raft.app.data.currentSystemLanguageTag
import kotlin.math.roundToLong

/**
 * The one formatter for byte sizes and transfer speeds in UI text (attachments, file pages,
 * downloads, app update, storage, computers, migration). Do not add another: a size label built
 * anywhere else fails `scripts/ci/check_source_constraints.py` (byte-unit literals are only
 * allowed in this file).
 *
 * Units are binary (1 KB = 1024 B) and labelled `B`, `KB`, `MB`, `GB`, the same as the Web's
 * `formatFileSizeBytes` (`packages/web/src/utils/fileSizePresentation.ts`, botiverse/slock
 * ff9a1c10f1f1a9bbba4e697d43835fcc972895f0):
 *
 * - zero or negative: `0 B`;
 * - under 1 KB: whole bytes, `512 B`;
 * - from 1 KB up: one decimal, rounded half up like `toFixed(1)`, `1.5 KB`, `12.0 MB`;
 * - GB is the largest unit, `2048.0 GB`.
 *
 * Locale: the unit words are the same in every UI language (the Web's en, zh-CN and ja catalogs
 * all use `{value} KB`), and so is the decimal separator for the three UI languages the app has.
 * [RaftFormatLocale] carries the separator so a UI language that writes `1,5` adds one row there.
 */
object RaftFormat {
    private const val Kib = 1024.0
    private val Units = listOf("KB", "MB", "GB")

    /** `512 B`, `1.5 KB`, `12.0 MB`, `2.0 GB`. */
    fun bytes(bytes: Long, locale: RaftFormatLocale = RaftFormatLocale.current()): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        var value = bytes / Kib
        var unit = 0
        while (value >= Kib && unit < Units.lastIndex) {
            value /= Kib
            unit += 1
        }
        return "${oneDecimal(value, locale)} ${Units[unit]}"
    }

    /** [bytes] for a JavaScript number (the server sends some sizes as doubles); not finite is `0 B`. */
    fun bytes(bytes: Double, locale: RaftFormatLocale = RaftFormatLocale.current()): String =
        if (!bytes.isFinite() || bytes <= 0.0) bytes(0L, locale) else bytes(bytes.toLong(), locale)

    /** `850.0 KB/s`, or null while the speed is unknown (null, zero or negative). */
    fun speed(bytesPerSecond: Long?, locale: RaftFormatLocale = RaftFormatLocale.current()): String? =
        bytesPerSecond?.takeIf { it > 0L }?.let { "${bytes(it, locale)}/s" }

    /**
     * A limit the user must stay under: whole units when the limit is an exact number of them
     * (`50 MB`, `200 MB`, like the Web's attachment-limit copy), else [bytes].
     */
    fun byteLimit(bytes: Long, locale: RaftFormatLocale = RaftFormatLocale.current()): String {
        if (bytes >= 1024L) {
            var divisor = 1024L
            var unit = 0
            while (unit < Units.lastIndex && bytes % (divisor * 1024L) == 0L) {
                divisor *= 1024L
                unit += 1
            }
            if (bytes % divisor == 0L) return "${bytes / divisor} ${Units[unit]}"
        }
        return bytes(bytes, locale)
    }

    private fun oneDecimal(value: Double, locale: RaftFormatLocale): String {
        val tenths = (value * 10.0).roundToLong()
        return "${tenths / 10}${locale.decimalSeparator}${tenths % 10}"
    }
}

/**
 * Number conventions of one UI language. The app's UI languages are English, Simplified Chinese
 * and Japanese ([AppLocale]); a device in any other language shows the English UI, so it formats
 * as [English].
 */
enum class RaftFormatLocale(val languageTag: String, val decimalSeparator: Char) {
    English("en", '.'),
    SimplifiedChinese("zh-CN", '.'),
    Japanese("ja", '.');

    companion object {
        /** The current UI language: the App Language setting, or the device's when it is System. */
        fun current(): RaftFormatLocale =
            when (KmmResourceLocaleRuntime.locale.value) {
                AppLocale.English -> English
                AppLocale.SimplifiedChinese -> SimplifiedChinese
                AppLocale.Japanese -> Japanese
                null, AppLocale.System -> forLanguageTag(runCatching { currentSystemLanguageTag() }.getOrNull())
            }

        fun forLanguageTag(languageTag: String?): RaftFormatLocale {
            val language = languageTag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
            return when (language) {
                "zh" -> SimplifiedChinese
                "ja" -> Japanese
                else -> English
            }
        }
    }
}
