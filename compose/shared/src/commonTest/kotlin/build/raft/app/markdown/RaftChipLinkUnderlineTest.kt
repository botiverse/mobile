package build.raft.app.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * artin 10-29 (Compose desktop): "@me" yellow chip showed an underline beside its label. A chip's
 * padding gaps carried a style-less copy of the chip's link, and Material3 `Text` styles every
 * link whose styles are null as `colorScheme.primary` + underline. No link the markdown renders
 * may have null styles, a boxed chip is never underlined in any interaction state, and the web
 * link underline filter treats a link over a chip's padding as the chip.
 */
class RaftChipLinkUnderlineTest {
    private val chip =
        RaftInlineBoxStyle(
            backgroundColor = Color.Yellow,
            paddingStart = 6.dp,
            paddingEnd = 6.dp,
            marginStart = 2.dp,
            marginEnd = 2.dp,
        )

    @Test
    fun boxedTagChipsAreNeverUnderlinedInAnyState() {
        val chips =
            listOf(
                RaftRichTextChromeKind.SelfMention,
                RaftRichTextChromeKind.Active,
                RaftRichTextChromeKind.Channel,
                RaftRichTextChromeKind.Thread,
                RaftRichTextChromeKind.Task,
            )
        for (kind in chips) {
            for (busy in listOf(false, true)) {
                val styles = raftMarkdownRichTextTagLinkStyles(kind, busy = busy)
                listOf(styles.style, styles.focusedStyle, styles.hoveredStyle, styles.pressedStyle).forEach { style ->
                    assertEquals(TextDecoration.None, assertNotNull(style, "$kind busy=$busy").textDecoration, "$kind busy=$busy")
                }
            }
        }
        listOf(raftMarkdownTaskChipStyle(null).linkStyles()).forEach { styles ->
            listOf(styles.style, styles.focusedStyle, styles.hoveredStyle, styles.pressedStyle).forEach { style ->
                assertEquals(TextDecoration.None, assertNotNull(style).textDecoration)
            }
        }
        // A plain @mention (no box) underlines on hover and press only (MessageReferenceText `hover:underline`).
        val mention = raftMarkdownRichTextTagLinkStyles(RaftRichTextChromeKind.OrdinaryMention)
        assertEquals(null, mention.style?.textDecoration)
        assertEquals(TextDecoration.Underline, mention.hoveredStyle?.textDecoration)
        assertEquals(TextDecoration.Underline, mention.pressedStyle?.textDecoration)
    }
}
