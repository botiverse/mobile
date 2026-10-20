package build.raft.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import build.raft.app.data.AppLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import build.raft.designsystem.theme.RaftTypography
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftTextRenderingTest {
    private val tokens: List<TextStyle>
        get() =
            listOf(
                RaftType.PageTitle,
                RaftType.SectionLabel,
                RaftType.Body,
                RaftType.Caption,
                RaftType.Button,
                RaftType.ButtonBold,
                RaftType.TabLabel,
                RaftType.Input,
                RaftType.ListItem,
                RaftType.ListItemBold,
            )

    @Test
    fun everyTokenUsesTheCssLineBox() {
        // CSS half-leading: glyphs centred in the line box, nothing trimmed (Compose's default,
        // Proportional + Trim.Both, drops single lines low and ~2dp short of the Web's box).
        tokens.forEach { style ->
            assertEquals(RaftTypography.CssLineHeightStyle, style.lineHeightStyle)
        }
    }

    @Test
    fun weightAndFamilyHelpersKeepTheCssLineBox() {
        val plain = TextStyle(fontWeight = FontWeight.Bold)
        assertEquals(RaftTypography.CssLineHeightStyle, plain.withRaftFontWeight(FontWeight.Bold).lineHeightStyle)
        assertEquals(RaftTypography.CssLineHeightStyle, plain.withResolvedRaftFontFamily().lineHeightStyle)
    }

    @Test
    fun anExplicitLineHeightStyleWins() {
        val custom = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.Both)
        val style = RaftType.Body.copy(lineHeightStyle = custom)
        assertEquals(custom, style.withRaftFontWeight(FontWeight.Bold).lineHeightStyle)
        assertEquals(custom, style.withResolvedRaftFontFamily().lineHeightStyle)
    }

    @Test
    fun tokensCarryThePlatformTextMotion() {
        // Android: TextMotion.Animated (subpixel glyph positioning); iOS: platform default (null).
        tokens.forEach { style -> assertEquals(raftPlatformTextMotion(), style.textMotion) }
        assertEquals(raftPlatformTextMotion(), TextStyle().withResolvedRaftFontFamily().textMotion)
        assertEquals(raftPlatformTextMotion(), raftTextRenderingStyle().textMotion)
    }

    @Test
    fun uiTextFollowsTheAppLanguageForHanForms() {
        val zhHans = LocaleList("zh-Hans")
        val ja = LocaleList("ja")
        assertEquals(ja, raftUiTextLocaleList(AppLocale.Japanese) { "en-US" })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.SimplifiedChinese) { "ja-JP" })
        // English UI: the Web's sans stack (PingFang SC / Noto Sans SC) decides, whatever the device.
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.English) { "ja-JP" })
        assertEquals(ja, raftUiTextLocaleList(AppLocale.System) { "ja-JP" })
        assertEquals(ja, raftUiTextLocaleList(null) { "ja" })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.System) { "zh-Hant-TW" })
        assertEquals(zhHans, raftUiTextLocaleList(AppLocale.System) { null })
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

    @Test
    fun theUiLocaleIsCachedAndRefreshedOnlyOnALanguageChange() {
        val zhHans = LocaleList("zh-Hans")
        val ja = LocaleList("ja")
        try {
            RaftUiTextLocaleState.refresh(AppLocale.English, "en-US")
            assertEquals(zhHans, RaftUiTextLocaleState.localeList)
            assertEquals(zhHans, RaftType.Body.localeList)
            RaftUiTextLocaleState.refresh(AppLocale.Japanese, "en-US")
            assertEquals(ja, RaftUiTextLocaleState.localeList)
            assertEquals(ja, RaftType.Body.localeList)
            // User content keeps Simplified forms under a Japanese UI.
            assertEquals(zhHans, raftMessageBodyTextStyle(null).localeList)
            RaftUiTextLocaleState.refresh(AppLocale.System, "ja-JP")
            assertEquals(ja, RaftUiTextLocaleState.localeList)
            RaftUiTextLocaleState.refresh(AppLocale.System, "zh-CN")
            assertEquals(zhHans, RaftUiTextLocaleState.localeList)
        } finally {
            RaftUiTextLocaleState.refresh(AppLocale.English, "en-US")
        }
    }
}
