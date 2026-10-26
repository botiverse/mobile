package build.raft.app.data

import build.raft.app.KmmResourceLocaleRuntime
import build.raft.app.resources.StringDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class VisualCaptureLocaleSupportTest {
    @Test
    fun kmmResourceLocaleTagsCoverEverySupportedAppLocale() {
        assertEquals(
            listOf(null, "en", "zh-CN", "ja"),
            AppLocale.entries.map { it.kmmResourceLocaleTag }
        )
        assertEquals(StringDesc.LocaleType.System, AppLocale.System.stringDescLocaleType)
        assertIs<StringDesc.LocaleType.Custom>(AppLocale.English.stringDescLocaleType)
        assertIs<StringDesc.LocaleType.Custom>(AppLocale.SimplifiedChinese.stringDescLocaleType)
        assertIs<StringDesc.LocaleType.Custom>(AppLocale.Japanese.stringDescLocaleType)
    }

    @Test
    fun captureLocaleParserAcceptsSupportedLanguageTagsAndRejectsUnknownValues() {
        assertNull(parseVisualCaptureLocale(null))
        assertNull(parseVisualCaptureLocale("  "))

        mapOf(
            "en" to AppLocale.English,
            "en-US" to AppLocale.English,
            "zh-CN" to AppLocale.SimplifiedChinese,
            "zh-Hans" to AppLocale.SimplifiedChinese,
            "ja" to AppLocale.Japanese,
            "ja-JP" to AppLocale.Japanese
        ).forEach { (tag, expected) ->
            assertEquals(expected, parseVisualCaptureLocale(tag), tag)
        }

        assertFailsWith<IllegalArgumentException> { parseVisualCaptureLocale("fr") }
    }

    @Test
    fun captureLocaleOverrideAppliesAndRestoresRuntimeAndStringResources() {
        val originalRuntimeLocale = KmmResourceLocaleRuntime.locale.value
        val originalStringDescLocaleType = StringDesc.localeType
        try {
            KmmResourceLocaleRuntime.replaceForTest(AppLocale.English)
            val englishLocaleType = AppLocale.English.stringDescLocaleType
            StringDesc.localeType = englishLocaleType

            val token = AppLocale.Japanese.applyVisualCaptureLocale()

            assertEquals(AppLocale.Japanese, KmmResourceLocaleRuntime.locale.value)
            assertIs<StringDesc.LocaleType.Custom>(StringDesc.localeType)

            restoreVisualCaptureLocale(token)

            assertEquals(AppLocale.English, KmmResourceLocaleRuntime.locale.value)
            assertEquals(englishLocaleType, StringDesc.localeType)
        } finally {
            KmmResourceLocaleRuntime.replaceForTest(originalRuntimeLocale)
            StringDesc.localeType = originalStringDescLocaleType
        }
    }

    @Test
    fun captureLocaleOverrideRejectsNondeterministicSystemLocale() {
        assertFailsWith<IllegalArgumentException> {
            AppLocale.System.applyVisualCaptureLocale()
        }
    }
}
