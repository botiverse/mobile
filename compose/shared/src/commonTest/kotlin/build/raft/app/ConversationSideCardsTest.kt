package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task v2's Tasks / Files cards (ConversationSideCards.kt; web ChatPanel / channelSideCardStore). */
class ConversationSideCardsTest {
    @Test
    fun flagOffKeepsTheTabs() {
        assertFalse(conversationSideCardsEnabled(taskV2Enabled = false, showModeTabs = true, assistantPanelEmbed = false))
        assertTrue(conversationSideCardsEnabled(taskV2Enabled = true, showModeTabs = true, assistantPanelEmbed = false))
        // No tab row today (a reply thread, a task conversation, the assistant's panel): no cards either.
        assertFalse(conversationSideCardsEnabled(taskV2Enabled = true, showModeTabs = false, assistantPanelEmbed = false))
        assertFalse(conversationSideCardsEnabled(taskV2Enabled = true, showModeTabs = true, assistantPanelEmbed = true))
    }

    @Test
    fun oldTabLinksOpenTheMatchingCard() {
        assertNull(RaftThreadTab.Chat.conversationSideCard())
        assertEquals(ConversationSideCard.Tasks, RaftThreadTab.fromWireValue("tasks").conversationSideCard())
        assertEquals(ConversationSideCard.Files, RaftThreadTab.fromWireValue("files").conversationSideCard())
        ConversationSideCard.entries.forEach { assertEquals(it, it.toThreadTab().conversationSideCard()) }
    }

    @Test
    fun toggleOpensAndClosesACard() {
        val tasks = ConversationSideCardsState().toggled(ConversationSideCard.Tasks)
        assertTrue(tasks.tasks)
        assertEquals(ConversationSideCard.Tasks, tasks.shown)
        val closed = tasks.toggled(ConversationSideCard.Tasks)
        assertFalse(closed.anyOpen)
        assertNull(closed.shown)
    }

    @Test
    fun bothOpenShowTheLastOpenedOrPickedUnderTasksFirstSwitch() {
        val both = ConversationSideCardsState().toggled(ConversationSideCard.Tasks).toggled(ConversationSideCard.Files)
        assertEquals(listOf(ConversationSideCard.Tasks, ConversationSideCard.Files), both.openCards)
        assertEquals(ConversationSideCard.Files, both.shown)
        assertEquals(ConversationSideCard.Tasks, both.focused(ConversationSideCard.Tasks).shown)
        // Closing the shown card shows the other.
        assertEquals(ConversationSideCard.Tasks, both.toggled(ConversationSideCard.Files).shown)
    }

    @Test
    fun backClosesEveryOpenCardBeforeTheSelectionOrThePage() {
        assertEquals(
            ThreadTransientBackTarget.SideCards,
            threadTransientBackTarget(activeModal = null, shareSelectionActive = true, inlineSearchVisible = false, sideCardsOpen = true)
        )
        // A dialog over the cards closes first (it handles its own Back).
        assertNull(threadTransientBackTarget(activeModal = "settings", shareSelectionActive = false, inlineSearchVisible = false, sideCardsOpen = true))
        // Cards closed: the page's own Back.
        assertNull(threadTransientBackTarget(activeModal = null, shareSelectionActive = false, inlineSearchVisible = false, sideCardsOpen = false))
        val store = ConversationSideCardsStore()
        store.open(ConversationSideCard.Tasks)
        store.open(ConversationSideCard.Files)
        store.closeAll()
        assertFalse(store.state.value.anyOpen)
    }

    @Test
    fun openCardsFollowThePersonAcrossConversations() {
        val store = ConversationSideCardsStore()
        store.toggle(ConversationSideCard.Files)
        // Another conversation page reads the same store: Files is still open there.
        assertTrue(store.state.value.files)
    }

    @Test
    fun aConversationWithoutTasksKeepsItsTasksCardShut() {
        val both = ConversationSideCardsState().toggled(ConversationSideCard.Tasks).toggled(ConversationSideCard.Files)
        val here = both.availableIn(tasksAvailable = false)
        assertEquals(listOf(ConversationSideCard.Files), here.openCards)
        assertEquals(both, both.availableIn(tasksAvailable = true))
    }
}
