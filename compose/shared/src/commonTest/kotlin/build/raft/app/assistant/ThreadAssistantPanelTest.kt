package build.raft.app.assistant

import build.raft.app.thread.RaftThreadMessageItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The otter's panel against web assistant/AssistantOtterPanel.tsx (`metaId`), AssistantOtter.tsx
 * (`data-[open]` over `data-composing`) and assistantOtterState.ts (`hasWaitingActionCard`).
 */
class ThreadAssistantPanelTest {
    @Test
    fun aCoveredPanelStaysOpenAndShowsAgainWhenItsPageIsBack() {
        val panel = ThreadAssistantPanelState()
        // Nothing to hide while closed.
        panel.onPageCovered()
        panel.requestOpen(true)
        assertTrue(panel.shown)

        // A page pushed over the conversation: hidden, still open; its window's close is ignored.
        panel.onPageCovered()
        panel.requestOpen(false)
        assertTrue(panel.open)
        assertFalse(panel.shown)

        // Back on the conversation: shown again; the person's close closes it.
        panel.onPageActive()
        assertTrue(panel.shown)
        panel.requestOpen(false)
        assertFalse(panel.open)
        assertFalse(panel.shown)
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
