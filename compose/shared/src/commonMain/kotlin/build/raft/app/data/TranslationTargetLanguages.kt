package build.raft.app.data

internal data class TranslationTargetLanguageOption(
    val key: String,
    val label: String
)

internal object TranslationTargetLanguages {
    const val Browser = "browser"
    const val Fallback = "en"

    val options: List<TranslationTargetLanguageOption> =
        listOf(
            TranslationTargetLanguageOption(Browser, "Browser language"),
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

    fun normalize(value: String?): String? {
        val normalized = value?.trim()?.lowercase()?.replace('_', '-')
        if (normalized.isNullOrEmpty() || normalized == Browser || normalized == "other" || normalized == "system") {
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
}
