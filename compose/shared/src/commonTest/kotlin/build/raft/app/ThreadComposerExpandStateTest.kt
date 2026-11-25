package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadComposerExpandStateTest {
    @Test
    fun canExpandRequiresOptInHostAndWideViewport() {
        assertFalse(threadComposerCanExpand(expandable = false, windowWidthDp = 1200.dp))
        assertFalse(threadComposerCanExpand(expandable = true, windowWidthDp = 393.dp))
        assertFalse(threadComposerCanExpand(expandable = true, windowWidthDp = 743.dp))
        assertTrue(threadComposerCanExpand(expandable = true, windowWidthDp = 744.dp))
        assertTrue(threadComposerCanExpand(expandable = true, windowWidthDp = 768.dp))
        assertTrue(threadComposerCanExpand(expandable = true, windowWidthDp = 1280.dp))
    }

    @Test
    fun expandStateIsConversationScopedAndCollapsesOnNarrowOrExplicitCollapse() {
        val state = ThreadComposerExpandState()
        assertFalse(state.isExpanded("c1", canExpand = true))
        assertNull(state.expandedConversationKey)

        assertTrue(state.toggle("c1", canExpand = true))
        assertTrue(state.isExpanded("c1", canExpand = true))
        assertFalse(state.isExpanded("c2", canExpand = true))
        assertFalse(state.isExpanded("c1", canExpand = false))

        assertTrue(state.toggle("c2", canExpand = true))
        assertFalse(state.isExpanded("c1", canExpand = true))
        assertTrue(state.isExpanded("c2", canExpand = true))

        assertFalse(state.toggle("c2", canExpand = true))
        assertFalse(state.isExpanded("c2", canExpand = true))

        assertFalse(state.toggle("", canExpand = true))
        assertFalse(state.toggle("c1", canExpand = false))

        assertTrue(state.toggle("c1", canExpand = true))
        state.collapse()
        assertFalse(state.isExpanded("c1", canExpand = true))
        assertNull(state.expandedConversationKey)
    }

    @Test
    fun expandConversationKeyDistinguishesChannelsStreamsAndReplyThreads() {
        val channel = ThreadComposerSurface(channelId = "chan-1")
        val stream = ThreadComposerSurface(channelId = "chan-1", streamChannelId = "stream-9")
        val thread = ThreadComposerSurface(channelId = "chan-1", parentMessageId = "msg-42")
        val otherThread = ThreadComposerSurface(channelId = "chan-1", parentMessageId = "msg-99")

        assertEquals("chan-1", channel.expandConversationKey)
        assertEquals("stream-9", stream.expandConversationKey)
        assertEquals("chan-1:msg-42", thread.expandConversationKey)
        assertEquals("chan-1:msg-99", otherThread.expandConversationKey)
    }
}
