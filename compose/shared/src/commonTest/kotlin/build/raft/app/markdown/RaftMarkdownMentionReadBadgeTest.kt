package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** raft-design #794: the mention read mark exists only once the mention is read; unread has no placeholder at all. */
class RaftMarkdownMentionReadBadgeTest {
    private fun mention(read: Boolean): AnnotatedString =
        buildAnnotatedString {
            append("@alpha")
            appendRaftMarkdownMentionReadBadge(read)
        }

    @Test
    fun unreadAppendsNothing() {
        val text = mention(read = false)
        assertEquals("@alpha", text.text)
        assertTrue(text.getStringAnnotations(0, text.length).isEmpty())
    }

    @Test
    fun readAppendsTheMarkPlaceholder() {
        val text = mention(read = true)
        assertTrue(text.text.length > "@alpha".length)
        val content = raftMarkdownMentionReadInlineContent(14.sp)
        assertEquals(setOf("raft-mention-read:read"), content.keys)
        val placeholder = content.getValue("raft-mention-read:read").placeholder
        assertEquals(10f, placeholder.width.value)
        // Baseline up to the mark's top: the name's cap height (0.7em of 14sp = 9.8) plus the check's 2.5 ink inset.
        assertEquals(12.3f, placeholder.height.value, 0.001f)
        assertEquals(PlaceholderVerticalAlign.AboveBaseline, placeholder.placeholderVerticalAlign)
    }

    @Test
    fun markRiseScalesWithFontSize() {
        assertEquals(16.5f, raftMarkdownMentionReadMarkRiseSp(20.sp), 0.001f)
    }

    @Test
    fun revisionStillDistinguishesReadFromUnread() {
        assertTrue(mention(read = true).text != mention(read = false).text)
    }
}
