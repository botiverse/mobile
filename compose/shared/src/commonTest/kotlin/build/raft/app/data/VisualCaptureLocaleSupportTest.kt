package build.raft.app.data

import build.raft.app.resources.StringDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VisualCaptureLocaleSupportTest {
    @Test
    fun kmmResourceLocaleTagsCoverEverySupportedAppLocale() {
        assertEquals(
            listOf(null, "en", "es", "pt-BR", "ru", "ko", "ja", "zh-CN", "zh-TW"),
            AppLocale.entries.map { it.kmmResourceLocaleTag }
        )
        assertEquals(StringDesc.LocaleType.System, AppLocale.System.stringDescLocaleType)
        assertIs<StringDesc.LocaleType.Custom>(AppLocale.English.stringDescLocaleType)
        assertIs<StringDesc.LocaleType.Custom>(AppLocale.SimplifiedChinese.stringDescLocaleType)
        assertIs<StringDesc.LocaleType.Custom>(AppLocale.Japanese.stringDescLocaleType)
    }
}
