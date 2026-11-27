package build.raft.app

import androidx.compose.ui.text.intl.LocaleList
import build.raft.app.data.AppLocale
import build.raft.app.thread.raftMessageBodyTextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTextRenderingTest {
    @Test
    fun uiTextFollowsTheAppLanguageForHanForms() {
        val zhHans = LocaleList("zh-Hans")
        val ja = LocaleList("ja")
        assertEquals(ja, raftUiTextLocaleList(AppLocale.Japanese) { listOf("en-US") })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.SimplifiedChinese) { listOf("ja-JP") })
        // English UI: the Web's sans stack (PingFang SC / Noto Sans SC) decides, whatever the device.
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.English) { listOf("ja-JP") })
        assertEquals(ja, raftUiTextLocaleList(AppLocale.System) { listOf("ja-JP") })
        assertEquals(ja, raftUiTextLocaleList(null) { listOf("ja") })
        assertEquals(LocaleList("zh-Hant"), raftUiTextLocaleList(AppLocale.System) { listOf("zh-Hant-TW") })
        assertEquals(LocaleList("zh-Hant"), raftUiTextLocaleList(AppLocale.TraditionalChinese) { listOf("en-US") })
        assertEquals(LocaleList("ko"), raftUiTextLocaleList(AppLocale.Korean) { listOf("en-US") })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.Spanish) { listOf("ja-JP") })
        // Space Grotesk has no Cyrillic: a Russian UI must not carry the zh-Hans hint that would
        // send Cyrillic to a CJK face; it falls back to the platform's Roboto / SF.
        assertEquals(LocaleList("ru"), raftUiTextLocaleList(AppLocale.Russian) { listOf("ja-JP") })
        assertEquals(LocaleList("ru"), raftUiTextLocaleList(AppLocale.System) { listOf("ru-RU") })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.System) { emptyList() })
        // The whole preferred-language list, like the string lookup: French has no catalog, so a
        // [fr, ja] device shows a Japanese UI with Japanese kanji forms.
        assertEquals(ja, raftUiTextLocaleList(AppLocale.System) { listOf("fr-FR", "ja-JP") })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.System) { listOf("en-US", "ja-JP") })
    }

    @Test
    fun userContentAlwaysUsesSimplifiedChineseForms() {
        val zhHans = LocaleList("zh-Hans")
        assertEquals(zhHans, raftMessageBodyTextStyle(null).localeList)
        assertEquals(zhHans, raftMessageBodyTextStyle("lg").localeList)
        assertEquals(zhHans, RaftType.Input.localeList)
        assertEquals(zhHans, RaftType.Caption.withRaftUserContentLocale().localeList)
        // A weight change keeps the content locale.
        assertEquals(zhHans, raftMessageBodyTextStyle(null).withRaftFontWeight(FontWeight.Bold).localeList)
    }
}
