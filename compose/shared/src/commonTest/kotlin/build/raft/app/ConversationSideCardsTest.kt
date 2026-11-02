package build.raft.app

import build.raft.app.data.SharedPreferenceStore
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
    fun closingSavesAndNoSessionKeepsWhatIsShown() {
        val prefs = MapPreferences()
        val store = ConversationSideCardsStore { prefs }
        store.bindOwner("env|u1")
        store.open(ConversationSideCard.Tasks)
        store.open(ConversationSideCard.Files)
        store.closeAll()
        assertEquals("{\"tasks\":false,\"files\":false}", prefs.values["conversationSideCards:open:env|u1"])
        store.open(ConversationSideCard.Tasks)
        // A page whose session is not read yet does not reset the cards.
        store.bindOwner(null)
        assertTrue(store.state.value.tasks)
        // Before any person is bound nothing is written.
        val unbound = MapPreferences()
        ConversationSideCardsStore { unbound }.open(ConversationSideCard.Tasks)
        assertTrue(unbound.values.isEmpty())
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
