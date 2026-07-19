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
    fun accountParserPreservesSupportedExplicitTarget() {
        val parsed =
            parseSharedLanguageRegionAccountPreferences(
                """{"preferredLanguage":"ja-JP","autoTranslationEnabled":true}"""
            )

        assertEquals("ja", parsed?.preferredLanguage)
    }
}
