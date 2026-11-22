package build.raft.app.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The otter's pure rules against web assistant/AssistantOtter.tsx and assistantOtterState.ts. */
class RaftAssistantOtterTest {
    @Test
    fun bubbleOnlyForNewAndConfirmOnceSettled() {
        assertTrue(raftOtterShowsBubble(RaftOtterMood.New))
        assertTrue(raftOtterShowsBubble(RaftOtterMood.Confirm))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.Idle))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.Working))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.Away))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.New, entering = true))
    }

    @Test
    fun moodPrecedenceMatchesTheWeb() {
        fun mood(paused: Boolean = false, confirm: Boolean = false, unread: Boolean = false, working: Boolean = false, offline: Boolean = false) =
            raftOtterMood(paused = paused, confirm = confirm, unread = unread, working = working, offline = offline)
        assertEquals(RaftOtterMood.Idle, mood())
        assertEquals(RaftOtterMood.Away, mood(paused = true, confirm = true, unread = true, working = true))
        assertEquals(RaftOtterMood.Confirm, mood(confirm = true, unread = true, working = true, offline = true))
        assertEquals(RaftOtterMood.New, mood(unread = true, working = true, offline = true))
        assertEquals(RaftOtterMood.Working, mood(working = true))
        assertEquals(RaftOtterMood.Away, mood(offline = true))
    }
}
