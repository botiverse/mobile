package build.raft.app.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * artin 2026-11-02 (Mac app): a mouse press that starts on a link and drags selects text, as in a
 * browser; a click still opens the link. Policy and text rewrite behind RaftMarkdownMouseLinks.
 */
class RaftMarkdownMouseLinksTest {
    private val link = 4 until 8

    @Test
    fun aClickOnTheLinkOpensIt() {
        assertTrue(raftMarkdownMouseLinkClickOpens(pressedLink = link, releasedLink = link, movedBeyondSlop = false))
    }

    @Test
    fun aDragFromTheLinkSelectsInsteadOfOpening() {
        assertFalse(raftMarkdownMouseLinkClickOpens(pressedLink = link, releasedLink = link, movedBeyondSlop = true))
    }

    @Test
    fun aPressOffTheLinkOrLetGoElsewhereOpensNothing() {
        assertFalse(raftMarkdownMouseLinkClickOpens(pressedLink = null, releasedLink = link, movedBeyondSlop = false))
        assertFalse(raftMarkdownMouseLinkClickOpens(pressedLink = link, releasedLink = null, movedBeyondSlop = false))
        assertFalse(raftMarkdownMouseLinkClickOpens(pressedLink = link, releasedLink = 10 until 12, movedBeyondSlop = false))
    }

    @Test
    fun aFingerLongPressOnTheLinkSelectsInsteadOfOpening() {
        assertFalse(
            raftMarkdownMouseLinkClickOpens(
                pressedLink = link,
                releasedLink = link,
                movedBeyondSlop = false,
                longPressByFinger = true,
            )
        )
    }

    private val linkStyles =
        TextLinkStyles(
            style = SpanStyle(color = Color.Blue),
            hoveredStyle = SpanStyle(color = Color.Red),
            pressedStyle = SpanStyle(color = Color.Green),
        )

    private val text =
        buildAnnotatedString {
            append("See ")
            withLink(LinkAnnotation.Url("https://example.com", styles = linkStyles)) {
                append("do")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.Black)) { append("cs") }
            }
            append(" and ")
            withLink(LinkAnnotation.Url("https://plain.example")) { append("this") }
        }

    @Test
    fun styledLinksBecomeTheirLookSoNoFrameworkBoxTakesThePress() {
        assertEquals(listOf(4 until 8), text.raftMarkdownMouseLinks().map { it.span })
        val painted = text.withRaftMarkdownMouseLinks(hovered = null, pressed = null)
        assertEquals(text.text, painted.text)
        val links = painted.getLinkAnnotations(0, painted.length)
        // Only the link without styles stays a link (Material3 gives it the default link look).
        assertEquals(listOf("https://plain.example"), links.map { (it.item as LinkAnnotation.Url).url })
        val blue = painted.spanStyles.indexOfFirst { it.item.color == Color.Blue && it.start == 4 && it.end == 8 }
        val nested = painted.spanStyles.indexOfFirst { it.item.fontWeight == FontWeight.Bold }
        assertTrue(blue >= 0, "the link keeps its style")
        // The nested style comes after the link's, so it still wins over its words.
        assertTrue(nested > blue, "nested style order: blue=$blue nested=$nested")
    }

    @Test
    fun theHoveredAndPressedLinkTakeTheirStyles() {
        val hovered = text.withRaftMarkdownMouseLinks(hovered = 4 until 8, pressed = null)
        assertTrue(hovered.spanStyles.any { it.start == 4 && it.end == 8 && it.item.color == Color.Red })
        val pressed = text.withRaftMarkdownMouseLinks(hovered = 4 until 8, pressed = 4 until 8)
        assertTrue(pressed.spanStyles.any { it.start == 4 && it.end == 8 && it.item.color == Color.Green })
    }
}
