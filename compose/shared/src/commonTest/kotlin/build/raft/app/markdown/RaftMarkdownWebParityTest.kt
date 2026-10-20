package build.raft.app.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.RaftAppAppearance
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftType
import build.raft.app.raftFontFamilyForWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web MarkdownContent parity for message Markdown typography. */
class RaftMarkdownWebParityTest {
    @Test
    fun headingsUseWebEmScaleBoldAndTightLeading() {
        val base = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 22.sp)
        val typography = raftMarkdownTypography(base)
        val headings = listOf(typography.h1, typography.h2, typography.h3, typography.h4, typography.h5, typography.h6)
        listOf(18.004f, 16.002f, 14.994f, 14f, 14f, 14f).zip(headings).forEach { (expected, style) ->
            assertEquals(expected, style.fontSize.value, 0.01f)
            assertEquals(style.fontSize.value * 1.25f, style.lineHeight.value, 0.01f)
            assertEquals(raftFontFamilyForWeight(FontWeight.Bold), style.fontFamily)
        }
        assertEquals(RaftAppThemeState.colors.foregroundMuted, typography.h6.color)
    }

    @Test
    fun quoteTextIsForegroundMuted() {
        val typography = raftMarkdownTypography(RaftType.Body)
        assertEquals(RaftAppThemeState.colors.foregroundMuted, typography.quote.color)
    }

    @Test
    fun tableMatchesWebChatTableInBothThemes() {
        // web MarkdownContent th/td `px-2 py-1`.
        assertEquals(PaddingValues(horizontal = 8.dp, vertical = 4.dp), RaftMarkdownTableCellPadding)
        val initial = RaftAppThemeState.appearance
        try {
            listOf(RaftAppAppearance.Light, RaftAppAppearance.Dark).forEach { appearance ->
                RaftAppThemeState.apply(appearance)
                val theme = RaftAppThemeState.colors
                val colors = RaftMarkdownColorsDefault
                // th `bg-fill-muted text-foreground`, td inherits `text-foreground`.
                assertEquals(theme.fillMuted, colors.tableHeaderBackground, "$appearance header")
                assertEquals(theme.foreground, colors.tableText, "$appearance text")
                // every cell and the table `border border-line-hairline` (1px).
                assertEquals(theme.lineHairline, colors.tableStroke, "$appearance stroke")
                // no fill of its own, no zebra.
                assertEquals(Color.Transparent, colors.tableBackground, "$appearance background")
            }
        } finally {
            RaftAppThemeState.apply(initial)
        }
    }
}
