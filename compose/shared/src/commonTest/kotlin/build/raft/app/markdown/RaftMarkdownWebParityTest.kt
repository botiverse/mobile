package build.raft.app.markdown

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
}
