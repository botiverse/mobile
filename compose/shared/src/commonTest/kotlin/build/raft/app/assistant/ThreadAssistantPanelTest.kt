package build.raft.app.assistant

import build.raft.app.RaftThreadMessageItem
import build.raft.app.resources.Res
import build.raft.app.resources.assistant_header_meta
import build.raft.app.resources.assistant_header_meta_with_dms
import build.raft.app.resources.assistant_otter_meta_confirm
import build.raft.app.resources.assistant_otter_meta_working
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The otter's panel against web assistant/AssistantOtterPanel.tsx (`metaId`), AssistantOtter.tsx
 * (`data-[open]` over `data-composing`) and assistantOtterState.ts (`hasWaitingActionCard`).
 */
class ThreadAssistantPanelTest {
    @Test
    fun theMetaLineSaysWhatItDoesElseWhatItReads() {
        assertEquals(Res.string.assistant_otter_meta_working, assistantPanelMeta(RaftOtterMood.Working, readsDms = true))
        assertEquals(Res.string.assistant_otter_meta_confirm, assistantPanelMeta(RaftOtterMood.Confirm, readsDms = false))
        assertEquals(Res.string.assistant_header_meta_with_dms, assistantPanelMeta(RaftOtterMood.Idle, readsDms = true))
        assertEquals(Res.string.assistant_header_meta, assistantPanelMeta(RaftOtterMood.Idle, readsDms = false))
        // New and away keep the reading line, as on web.
        assertEquals(Res.string.assistant_header_meta, assistantPanelMeta(RaftOtterMood.New, readsDms = false))
        assertEquals(Res.string.assistant_header_meta_with_dms, assistantPanelMeta(RaftOtterMood.Away, readsDms = true))
    }

    @Test
    fun theOpenPanelTucksTheOtterFurtherThanTyping() {
        assertEquals(RaftOtterTuck.None, threadAssistantOtterTuck(composing = false, panelOpen = false))
        assertEquals(RaftOtterTuck.Composing, threadAssistantOtterTuck(composing = true, panelOpen = false))
        assertEquals(RaftOtterTuck.PanelOpen, threadAssistantOtterTuck(composing = false, panelOpen = true))
        assertEquals(RaftOtterTuck.PanelOpen, threadAssistantOtterTuck(composing = true, panelOpen = true))
    }

    @Test
    fun aCardFromTheAssistantWaitsWhilePreparedOrToReconfirm() {
        assertTrue(hasWaitingAssistantActionCard(listOf(card("m1", AGENT, "prepared")), AGENT))
        assertTrue(hasWaitingAssistantActionCard(listOf(text("m0"), card("m1", AGENT, "reconfirm_required")), AGENT))
        // A card without a state is prepared (the app's ThreadActionCard default, as the server sends it).
        assertTrue(hasWaitingAssistantActionCard(listOf(card("m1", AGENT, state = null)), AGENT))
        // Answered cards, someone else's card, and plain messages do not wait.
        assertFalse(hasWaitingAssistantActionCard(listOf(card("m1", AGENT, "executed")), AGENT))
        assertFalse(hasWaitingAssistantActionCard(listOf(card("m1", AGENT, "cancelled")), AGENT))
        assertFalse(hasWaitingAssistantActionCard(listOf(card("m1", "another-agent", "prepared")), AGENT))
        assertFalse(hasWaitingAssistantActionCard(listOf(text("m0")), AGENT))
        assertFalse(hasWaitingAssistantActionCard(emptyList(), AGENT))
    }

    private fun text(id: String) = RaftThreadMessageItem(id = id, authorId = AGENT, authorName = "Ada", body = "Morning brief")

    private fun card(id: String, authorId: String, state: String?) =
        RaftThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = "Ada",
            body = "",
            metadata =
                buildMap {
                    put("actionCard.kind", "action-card")
                    put("actionCard.type", "channel:create")
                    if (state != null) put("actionCard.state", state)
                },
        )

    private companion object {
        const val AGENT = "agent-ada"
    }
}
