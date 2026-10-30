package build.raft.app.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    private fun url(u: String) = LinkAnnotation.Url(u, styles = RaftUnstyledLinkStyles)

    private fun AnnotatedString.underlinedTexts(): List<String> =
        withRaftMarkdownLinkUnderlines()
            .getStringAnnotations(RAFT_INLINE_UNDERLINE_ANNOTATION_TAG, 0, length)
            .map { text.substring(it.start, it.end) }

    @Test
    fun underlineFilterSkipsChipsIncludingTheirPaddingAndKeepsWebLinks() {
        val text =
            buildAnnotatedString {
                append("see ")
                // A chip whose link covers only its label.
                withRaftInlineChipGaps(chip) { withLink(url("https://a.example")) { withRaftInlineBox(chip) { append("#a") } } }
                append(" and ")
                // A chip whose link spans its padding gaps as well as its label.
                withLink(url("https://b.example")) { withRaftInlineChipGaps(chip) { withRaftInlineBox(chip) { append("#b") } } }
                append(" and ")
                // A chip with letter-spaced padding spacers inside its box (Skia), linked across the box.
                withRaftInlineChipGaps(chip) {
                    withLink(url("https://c.example")) {
                        withRaftInlineBox(chip) {
                            appendRaftInlineSpacer(4.dp, leading = true, asPlaceholder = false)
                            append("#c")
                            appendRaftInlineSpacer(4.dp, leading = false, asPlaceholder = false)
                        }
                    }
                }
                append(" and ")
                // The same with placeholder padding spacers (Android).
                withRaftInlineChipGaps(chip) {
                    withLink(url("https://d.example")) {
                        withRaftInlineBox(chip) {
                            appendRaftInlineSpacer(4.dp, leading = true, asPlaceholder = true)
                            append("#d")
                            appendRaftInlineSpacer(4.dp, leading = false, asPlaceholder = true)
                        }
                    }
                }
                append(" at ")
                withLink(url("https://web.example")) { append("web.example") }
            }
        assertEquals(listOf("web.example"), text.underlinedTexts())
        val extents = text.raftInlineChipExtents().map { text.text.substring(it.first, it.last + 1) }
        assertEquals(listOf("￼⁠#a⁠￼", "￼⁠#b⁠￼", "￼⁠⁠#c⁠⁠￼", "￼⁠￼⁠#d⁠￼⁠￼"), extents)
    }

    @Test
    fun aLinkReachingPastTheChipPaddingKeepsItsUnderline() {
        val text =
            buildAnnotatedString {
                withLink(url("https://x.example")) {
                    append("docs ")
                    withRaftInlineChipGaps(chip) { withRaftInlineBox(chip) { append("`x`") } }
                }
            }
        assertEquals(listOf("docs ￼⁠`x`⁠￼"), text.underlinedTexts())
    }

    @Test
    fun chipGapLinksAndBodyClickNeverHaveNullStyles() {
        val chipLink = LinkAnnotation.Clickable("tag", styles = raftChipLinkStyles(androidx.compose.ui.text.SpanStyle()), linkInteractionListener = null)
        val text =
            buildAnnotatedString {
                append("hi ")
                withRaftInlineChipGaps(chip) { withLink(chipLink) { withRaftInlineBox(chip) { append("@me") } } }
                append(" there")
            }
                .withRaftInlineChipGapLinks()
                .withRaftMarkdownBodyClick {}
        val links = text.getLinkAnnotations(0, text.length)
        // The chip, its two gap copies and the body-click runs around it.
        assertTrue(links.size >= 4, "links=$links")
        links.forEach { range -> assertNotNull(range.item.styles, "null styles on ${text.text.substring(range.start, range.end)}") }
        links.forEach { range ->
            val styles = range.item.styles!!
            listOf(styles.style, styles.focusedStyle, styles.hoveredStyle, styles.pressedStyle).forEach { style ->
                val decoration = style?.textDecoration
                assertTrue(decoration == null || decoration == TextDecoration.None, "underlined ${text.text.substring(range.start, range.end)}")
            }
        }
    }

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
