package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.thread.ConversationSideCard
import build.raft.app.thread.ConversationSideCardsState
import build.raft.app.thread.ConversationSideCardsStore
import build.raft.app.thread.RaftThreadTab
import build.raft.app.thread.ThreadTransientBackTarget
import build.raft.app.thread.conversationSideCard
import build.raft.app.thread.conversationSideCardsEnabled
import build.raft.app.thread.decodeConversationSideCardsOpen
import build.raft.app.thread.encodeOpenCards
import build.raft.app.thread.key
import build.raft.app.thread.threadTransientBackTarget
import build.raft.app.thread.toThreadTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task v2's Tasks / Files cards (ConversationSideCards.kt; web ChatPanel / channelSideCardStore). */
class ConversationSideCardsTest {
    @Test
    fun cardsReplaceTheTabRowWhereThePageHasOne() {
        assertTrue(conversationSideCardsEnabled(showModeTabs = true, assistantPanelEmbed = false))
        // No tab row today (a reply thread, a task conversation, the assistant's panel): no cards either.
        assertFalse(conversationSideCardsEnabled(showModeTabs = false, assistantPanelEmbed = false))
        assertFalse(conversationSideCardsEnabled(showModeTabs = true, assistantPanelEmbed = true))
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

    private class MapPreferences : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @Test
    fun openCardsSurviveARestartPerPerson() {
        val prefs = MapPreferences()
        val before = ConversationSideCardsStore { prefs }
        before.bindOwner("https://api.slock.ai|u1")
        before.open(ConversationSideCard.Files)
        // Web's key shape and value (slock:channelSideCards).
        assertEquals("{\"tasks\":false,\"files\":true}", prefs.values["conversationSideCards:open:https://api.slock.ai|u1"])

        // The app restarts: a new store, the same person.
        val after = ConversationSideCardsStore { prefs }
        assertFalse(after.state.value.anyOpen)
        after.bindOwner("https://api.slock.ai|u1")
        assertEquals(listOf(ConversationSideCard.Files), after.state.value.openCards)
        assertEquals(ConversationSideCard.Files, after.state.value.shown)

        // Another person on the device starts with their own (none).
        after.bindOwner("https://api.slock.ai|u2")
        assertFalse(after.state.value.anyOpen)
        // Back to the first person: their cards again.
        after.bindOwner("https://api.slock.ai|u1")
        assertTrue(after.state.value.files)
    }

    @Test
    fun incomingNavigationClosesCardsOnlyWhereTheyCoverTheConversation() {
        val store = ConversationSideCardsStore()
        // Nothing open: nothing covered, whatever the layout.
        assertFalse(store.closeForIncomingNavigation(coverConversation = true))
        store.open(ConversationSideCard.Tasks)
        store.open(ConversationSideCard.Files)
        // Beside the conversation (wide) the cards stay.
        assertFalse(store.closeForIncomingNavigation(coverConversation = false))
        assertEquals(ConversationSideCardsState(tasks = true, files = true, focus = ConversationSideCard.Files), store.state.value)
        // Over it (phone) every card closes, and the cover is reported.
        assertTrue(store.closeForIncomingNavigation(coverConversation = true))
        assertEquals(ConversationSideCardsState(), store.state.value)
    }

    @Test
    fun unreadableStoredCardsAreClosed() {
        assertEquals(ConversationSideCardsState(), decodeConversationSideCardsOpen(null))
        assertEquals(ConversationSideCardsState(), decodeConversationSideCardsOpen("not json"))
        assertEquals(ConversationSideCardsState(), decodeConversationSideCardsOpen("{\"tasks\":\"true\",\"files\":1}"))
        assertEquals(ConversationSideCardsState(tasks = true), decodeConversationSideCardsOpen("{\"tasks\":true}"))
        val both = ConversationSideCardsState(tasks = true, files = true)
        assertEquals(both, decodeConversationSideCardsOpen(both.encodeOpenCards()))
    }
}
