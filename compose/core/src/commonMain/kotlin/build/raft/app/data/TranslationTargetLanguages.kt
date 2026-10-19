package build.raft.app.data

import build.raft.app.SlockCoreInternalApi

@SlockCoreInternalApi
data class TranslationTargetLanguageOption(
    val key: String,
    val label: String
)

@SlockCoreInternalApi
object TranslationTargetLanguages {
    /**
     * Persisted/wire value retained for compatibility with the web setting.
     * On mobile this option means the device's current system language.
     */
    const val System = "browser"
    const val Browser = System
    const val Fallback = "en"

    val options: List<TranslationTargetLanguageOption> =
        listOf(
            TranslationTargetLanguageOption(System, "System language"),
            TranslationTargetLanguageOption("en", "English"),
            TranslationTargetLanguageOption("zh-cn", "Simplified Chinese"),
            TranslationTargetLanguageOption("zh-tw", "Traditional Chinese"),
            TranslationTargetLanguageOption("ja", "Japanese"),
            TranslationTargetLanguageOption("ko", "Korean"),
            TranslationTargetLanguageOption("es", "Spanish"),
            TranslationTargetLanguageOption("fr", "French"),
            TranslationTargetLanguageOption("de", "German"),
            TranslationTargetLanguageOption("pt-br", "Portuguese (Brazil)"),
            TranslationTargetLanguageOption("it", "Italian")
        )

    private val supportedKeys = options.drop(1).mapTo(mutableSetOf()) { it.key }

    fun resolve(targetLanguage: String?, systemLanguage: String?): String =
        normalize(targetLanguage)
            ?: if (isSystem(targetLanguage)) normalizeSystemLanguage(systemLanguage) else Fallback

    fun normalizeSystemLanguage(value: String?): String {
        val normalized = value.normalizedLanguageTag() ?: return Fallback
        val parts = normalized.split('-').filter { it.isNotBlank() }
        return when (parts.firstOrNull()) {
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            "zh" -> {
                val subtags = parts.drop(1).toSet()
                when {
                    "hant" in subtags || subtags.any { it in setOf("tw", "hk", "mo") } -> "zh-tw"
                    else -> "zh-cn"
                }
            }
            else -> Fallback
        }
    }

    fun normalize(value: String?): String? {
        val normalized = value.normalizedLanguageTag()
        if (normalized == null || isSystem(normalized)) {
            return null
        }
        val aliased =
            when (normalized) {
                "zh" -> "zh-cn"
                "zh-hans" -> "zh-cn"
                "zh-hant" -> "zh-tw"
                else -> normalized
            }
        if (aliased in supportedKeys) return aliased
        val base = aliased.substringBefore('-')
        return base.takeIf { it in supportedKeys }
    }

    private fun isSystem(value: String?): Boolean =
        value.normalizedLanguageTag().let { it == null || it == System || it == "other" || it == "system" }

    private fun String?.normalizedLanguageTag(): String? =
        this?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
}
