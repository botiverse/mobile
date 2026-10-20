package build.raft.app

import androidx.compose.ui.text.TextStyle
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
}
