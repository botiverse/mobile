package build.raft.app.markdown

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for per-block RTL text direction and link bidi isolation
 * (botiverse/slock #15039 `tests/textDirection.test.ts`).
 */
class RaftTextDirectionTest {
    @Test
    fun rtlScriptDecidesRtlOtherLetterDecidesLtrAndNoneReturnsNull() {
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("אפשר לבדוק את TASK-123 היום?"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("مرحبا بالعالم"))
        assertEquals(RaftTextDirection.Ltr, raftTextDirection("Can you check TASK-123 today?"))
        assertEquals(RaftTextDirection.Ltr, raftTextDirection("你好"))
        assertNull(raftTextDirection("123 ?!"))
        assertNull(raftTextDirection(""))
    }

    @Test
    fun mentionsRefsLinksAndInlineCodeDoNotDecideDirection() {
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("@alice אפשר לבדוק את TASK-123 היום?"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("@alice @bob שלום"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("#general https://example.com `code` שלום"))
        assertNull(raftTextDirection("@alice"))
        assertNull(raftTextDirection("#general https://example.com `code`"))
    }

    @Test
    fun utf8BomDoesNotTriggerRtlAndAllowsSkippingLeadingMentions() {
        assertTrue(isRaftRtlScriptChar('\uFEFC'))
        assertFalse(isRaftRtlScriptChar('\uFEFD'))
        assertFalse(isRaftRtlScriptChar('\uFEFE'))
        assertFalse(isRaftRtlScriptChar('\uFEFF'))

        assertEquals(RaftTextDirection.Ltr, raftTextDirection("\uFEFF@Taylor 请看下这个问题。"))
        assertEquals(RaftTextDirection.Ltr, raftTextDirection("\uFEFF你好，世界。"))
        assertEquals(RaftTextDirection.Ltr, raftTextDirection("\uFEFF@alice hello world."))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("\uFEFF@alice אפשר לבדוק היום?"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("\uFEFFمرحبا بالعالم"))
        assertNull(raftTextDirection("\uFEFF"))
        assertNull(raftTextDirection("\uFEFF@Taylor"))
    }

    @Test
    fun rtlLinkBidiIsolationWrapsLinksPreservesAnnotationOffsetsAndCleansOnCopy() {
        val original =
            buildAnnotatedString {
                withLink(LinkAnnotation.Clickable(tag = "mention-1", linkInteractionListener = {})) {
                    pushStringAnnotation(tag = "box", annotation = "chip")
                    append("@alice")
                    pop()
                }
                append(" אפשר לבדוק ")
                withLink(LinkAnnotation.Url("https://example.com")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("https://example.com")
                    }
                }
                append(" היום?")
            }

        val isolated = original.withRaftRtlLinkBidiIsolation()
        assertEquals(
            "${RAFT_BIDI_FSI}@alice${RAFT_BIDI_PDI} אפשר לבדוק ${RAFT_BIDI_FSI}https://example.com${RAFT_BIDI_PDI} היום?",
            isolated.text,
        )

        val links = isolated.getLinkAnnotations(0, isolated.length)
        assertEquals(2, links.size)
        assertEquals("@alice", isolated.text.substring(links[0].start, links[0].end))
        assertEquals("https://example.com", isolated.text.substring(links[1].start, links[1].end))

        val box = isolated.getStringAnnotations("box", 0, isolated.length).single()
        assertEquals("@alice", isolated.text.substring(box.start, box.end))

        val bold = isolated.spanStyles.single()
        assertEquals("https://example.com", isolated.text.substring(bold.start, bold.end))

        // Copy / accessibility strips FSI/PDI back to exact rendered source.
        assertEquals(original.text, isolated.text.raftCopyText())
    }
}
