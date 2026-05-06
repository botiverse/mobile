package ai.slock.android.core.data.locale

import java.util.Locale

enum class AppLocale(
    val storageKey: String,
    val languageTag: String,
    val englishLabel: String,
    val nativeLabel: String
) {
    English(
        storageKey = "en",
        languageTag = "en",
        englishLabel = "English",
        nativeLabel = "English"
    ),
    SimplifiedChinese(
        storageKey = "zh-Hans",
        languageTag = "zh-Hans",
        englishLabel = "Simplified Chinese",
        nativeLabel = "简体中文"
    );

    fun toJavaLocale(): Locale = Locale.forLanguageTag(languageTag)

    companion object {
        val Default = English

        fun fromStorageKey(value: String?): AppLocale =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}
