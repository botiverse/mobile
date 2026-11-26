package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftComposerSendKey
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

    @Test
    fun expandedForcesModEnterSendKeyAndAlwaysShowsHintOnHardwareKeyboard() {
        assertEquals(RaftComposerSendKey.ModEnter, threadComposerActiveSendKey(expanded = true, composerSendKey = RaftComposerSendKey.Enter))
        assertEquals(RaftComposerSendKey.ModEnter, threadComposerActiveSendKey(expanded = true, composerSendKey = RaftComposerSendKey.ModEnter))
        assertEquals(RaftComposerSendKey.Enter, threadComposerActiveSendKey(expanded = false, composerSendKey = RaftComposerSendKey.Enter))
        assertEquals(RaftComposerSendKey.ModEnter, threadComposerActiveSendKey(expanded = false, composerSendKey = RaftComposerSendKey.ModEnter))

        // Expanded: Enter inserts a newline and Cmd/Ctrl+Enter sends even when account preference is Enter.
        val expandedMode = threadComposerActiveSendKey(expanded = true, composerSendKey = RaftComposerSendKey.Enter)
        assertEquals(
            RaftHardwareEnterOutcome.Newline,
            raftHardwareEnterOutcome(
                mode = expandedMode,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = false,
                shiftPressed = false,
                altPressed = false,
            ),
        )
        assertEquals(
            RaftHardwareEnterOutcome.Send,
            raftHardwareEnterOutcome(
                mode = expandedMode,
                hardwareKeyboard = true,
                softEnterSends = false,
                modPressed = true,
                shiftPressed = false,
                altPressed = false,
            ),
        )

        // Expanded always shows the send-key hint (empty box or not); collapsed shows it only in ModEnter while composing.
        assertTrue(
            threadComposerShowsSendKeyHint(
                showSendKeyHint = true,
                hardwareKeyboardConnected = true,
                expanded = true,
                composerSendKey = RaftComposerSendKey.Enter,
                composing = false,
            ),
        )
        assertFalse(
            threadComposerShowsSendKeyHint(
                showSendKeyHint = true,
                hardwareKeyboardConnected = true,
                expanded = false,
                composerSendKey = RaftComposerSendKey.Enter,
                composing = true,
            ),
        )
        assertFalse(
            threadComposerShowsSendKeyHint(
                showSendKeyHint = true,
                hardwareKeyboardConnected = true,
                expanded = false,
                composerSendKey = RaftComposerSendKey.ModEnter,
                composing = false,
            ),
        )
        assertTrue(
            threadComposerShowsSendKeyHint(
                showSendKeyHint = true,
                hardwareKeyboardConnected = true,
                expanded = false,
                composerSendKey = RaftComposerSendKey.ModEnter,
                composing = true,
            ),
        )
        assertFalse(
            threadComposerShowsSendKeyHint(
                showSendKeyHint = false,
                hardwareKeyboardConnected = true,
                expanded = true,
                composerSendKey = RaftComposerSendKey.Enter,
                composing = true,
            ),
        )
        assertFalse(
            threadComposerShowsSendKeyHint(
                showSendKeyHint = true,
                hardwareKeyboardConnected = false,
                expanded = true,
                composerSendKey = RaftComposerSendKey.Enter,
                composing = true,
            ),
        )
    }

    @Test
    fun formatBarFoldsBlockToolsBelow480Dp() {
        assertFalse(threadComposerFormatBarShowsInlineBlockTools(479.dp))
        assertTrue(threadComposerFormatBarShowsInlineBlockTools(480.dp))
        assertTrue(threadComposerFormatBarShowsInlineBlockTools(768.dp))
    }
}
