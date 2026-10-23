package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ik2: the mention read mark exists only once the mention is read; unread has no placeholder at all. */
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
        assertEquals(setOf("raft-mention-read:read"), RaftMarkdownMentionReadInlineContent.keys)
        val placeholder = RaftMarkdownMentionReadInlineContent.getValue("raft-mention-read:read").placeholder
        assertEquals(10f, placeholder.width.value)
        assertEquals(10f, placeholder.height.value)
    }

    @Test
    fun revisionStillDistinguishesReadFromUnread() {
        assertTrue(mention(read = true).text != mention(read = false).text)
    }
}
