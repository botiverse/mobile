package build.raft.app

import androidx.compose.ui.text.buildAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which @mention of a rendered text the pointer is over (the profile preview card's trigger):
 * the spans come from the mention annotation, and a hit needs the pointer inside the
 * character's box, so the gap after a line's last word or after the text opens nothing.
 */
class RaftMarkdownMentionPreviewCardsTest {
    // "Ask @ada and @bugen" — @ada is 4 until 8, @bugen is 13 until 19.
    private val text =
        buildAnnotatedString {
            append("Ask ")
            pushStringAnnotation(RaftMarkdownMentionAnnotationTag, "@ada")
            append("@ada")
            pop()
            append(" and ")
            pushStringAnnotation(RaftMarkdownMentionAnnotationTag, "@bugen")
            append("@bugen")
            pop()
            pushStringAnnotation("other", "x")
            append("!")
            pop()
        }
    private val spans = text.raftMarkdownMentionSpans()

    @Test
    fun spansAreTheMentionAnnotationsInTextOrder() {
        assertEquals(
            listOf(RaftMarkdownMentionSpan(4, 8, "@ada"), RaftMarkdownMentionSpan(13, 19, "@bugen")),
            spans,
        )
    }

    @Test
    fun theCharacterUnderThePointerPicksTheMention() {
        val inside = { _: Int -> true }
        // The caret before or after a character of the mention.
        assertEquals(0, raftMarkdownMentionIndexAt(spans, text.length, caret = 4, inBox = inside))
        assertEquals(0, raftMarkdownMentionIndexAt(spans, text.length, caret = 8, inBox = inside))
        assertEquals(1, raftMarkdownMentionIndexAt(spans, text.length, caret = 15, inBox = inside))
        // Plain words between them.
        assertNull(raftMarkdownMentionIndexAt(spans, text.length, caret = 10, inBox = inside))
        // Past the end of the text.
        assertNull(raftMarkdownMentionIndexAt(spans, text.length, caret = 40, inBox = inside))
    }

    @Test
    fun outsideTheCharacterBoxIsNoMention() {
        // The pointer right of a line's last character: the caret is next to the mention, but
        // the pointer is not on it.
        assertNull(raftMarkdownMentionIndexAt(spans, text.length, caret = 19) { false })
        // Only the character after the caret is under the pointer: the one before is ignored.
        assertNull(raftMarkdownMentionIndexAt(spans, text.length, caret = 8) { offset -> offset == 8 })
        assertEquals(0, raftMarkdownMentionIndexAt(spans, text.length, caret = 8) { offset -> offset == 7 })
    }

    @Test
    fun noMentionsOrNoTextIsNoHit() {
        assertNull(raftMarkdownMentionIndexAt(emptyList(), text.length, caret = 5) { true })
        assertNull(raftMarkdownMentionIndexAt(spans, textLength = 0, caret = 0) { true })
    }
}
