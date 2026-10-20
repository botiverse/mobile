package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi
data class TranslationTargetLanguageOption(
    val key: String,
    val label: String
)

@RaftCoreInternalApi
object TranslationTargetLanguages {
    /**
     * The no-preference choice: translate into the app's current display (UI)
     * language, like web "Same as display language" (botiverse/slock#10449).
     * The persisted value keeps its legacy `browser` spelling.
     */
    const val System = "browser"
    const val Browser = System
    const val Fallback = "en"

    val options: List<TranslationTargetLanguageOption> =
        listOf(
            TranslationTargetLanguageOption(System, "Same as display language"),
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

    /**
     * The language to translate into: an explicit [targetLanguage], else (no
     * preference) the [displayLanguage] tag, the app's current UI language.
     */
    fun resolve(targetLanguage: String?, displayLanguage: String?): String =
        normalize(targetLanguage)
            ?: if (isSystem(targetLanguage)) normalizeSystemLanguage(displayLanguage) else Fallback

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
        value.normalizedLanguageTag().let { it == null || it == System || it == "display" || it == "other" || it == "system" }

    private fun String?.normalizedLanguageTag(): String? =
        this?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
}
