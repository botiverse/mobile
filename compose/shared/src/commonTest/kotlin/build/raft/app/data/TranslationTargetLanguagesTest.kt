package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationTargetLanguagesTest {
    @Test
    fun catalogMatchesWebTargets() {
        assertEquals(
            listOf("browser", "en", "zh-cn", "zh-tw", "ja", "ko", "es", "fr", "de", "pt-br", "it"),
            TranslationTargetLanguages.options.map { it.key }
        )
    }

    @Test
    fun normalizesWebAliasesAndBcp47Variants() {
        assertEquals("zh-cn", TranslationTargetLanguages.normalize("zh-Hans"))
        assertEquals("zh-tw", TranslationTargetLanguages.normalize("zh-Hant"))
        assertEquals("pt-br", TranslationTargetLanguages.normalize("pt_BR"))
        assertEquals("es", TranslationTargetLanguages.normalize("es-MX"))
        assertNull(TranslationTargetLanguages.normalize("browser"))
        assertNull(TranslationTargetLanguages.normalize("unsupported"))
    }

    @Test
    fun systemLanguagePreservesChineseScriptAndRegion() {
        assertEquals("zh-cn", TranslationTargetLanguages.normalizeSystemLanguage("zh-Hans-CN"))
        assertEquals("zh-cn", TranslationTargetLanguages.normalizeSystemLanguage("zh-SG"))
        assertEquals("zh-tw", TranslationTargetLanguages.normalizeSystemLanguage("zh-Hant"))
        assertEquals("zh-tw", TranslationTargetLanguages.normalizeSystemLanguage("zh-TW"))
        assertEquals("zh-tw", TranslationTargetLanguages.normalizeSystemLanguage("zh-HK"))
    }

    @Test
    fun systemLanguageSupportsProductTargetsAndFallsBackToEnglish() {
        assertEquals("en", TranslationTargetLanguages.resolve("browser", "en-GB"))
        assertEquals("ja", TranslationTargetLanguages.resolve("system", "ja-JP"))
        assertEquals("ko", TranslationTargetLanguages.resolve("other", "ko-KR"))
        // Web (botiverse/slock#10449) stores the no-preference choice as "display".
        assertEquals("zh-cn", TranslationTargetLanguages.resolve("display", "zh-CN"))
        assertEquals("en", TranslationTargetLanguages.resolve("browser", "fr-FR"))
        assertEquals("en", TranslationTargetLanguages.resolve("browser", null))
    }

    @Test
    fun explicitTranslationTargetWinsOverSystemLanguage() {
        assertEquals("fr", TranslationTargetLanguages.resolve("fr", "ja-JP"))
    }
}
