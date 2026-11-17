package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone Home's Assistant row (web botiverse/slock e136d7c9543fb84bf91330370d89e17c8d8fa044
 * `useRememberedSidebarEntry.ts` `useRememberedAssistantShown`, `sidebarSectionRowCounts.ts`).
 */
class HomeAssistantEntryTest {
    private val mine = MyAssistantRecord(id = "a1", name = "Ottie", agentId = "a1", dmChannelId = "dm1")

    @Test
    fun shownWhenReadyEvenWithoutAnAssistant() {
        fun answer(guest: Boolean, pending: Boolean, flag: Boolean, api: MyAssistantAnswer?) =
            homeAssistantEntryAnswer(myAssistantState(guest, pending, flag, api))
        assertEquals(true, answer(false, false, true, MyAssistantAnswer(true, null)), "no assistant yet: the page offers Create")
        assertEquals(true, answer(false, false, true, MyAssistantAnswer(true, mine)))
        assertEquals(false, answer(true, false, true, MyAssistantAnswer(true, mine)), "a guest never")
        assertEquals(false, answer(false, false, false, MyAssistantAnswer(true, mine)), "flag off")
        assertEquals(false, answer(false, false, true, MyAssistantAnswer.NotEnabled), "API not enabled")
        assertNull(answer(false, true, false, null), "flag still answering")
        assertNull(answer(false, false, true, null), "API still answering")
    }

    @Test
    fun theRememberedAnswerHoldsUntilTheAnswerLands() {
        assertTrue(homeAssistantEntryShown(answer = null, remembered = true))
        assertFalse(homeAssistantEntryShown(answer = null, remembered = false))
        assertFalse(homeAssistantEntryShown(answer = null, remembered = null), "never known here: hidden")
        // The answer wins once known.
        assertFalse(homeAssistantEntryShown(answer = false, remembered = true))
        assertTrue(homeAssistantEntryShown(answer = true, remembered = false))
    }

    @Test
    fun anUnreadableFlagHidesTheRowButIsNotRemembered() {
        assertTrue(homeAssistantEntryRemembers(answer = true, flagUndetermined = false))
        assertTrue(homeAssistantEntryRemembers(answer = false, flagUndetermined = false))
        assertFalse(homeAssistantEntryRemembers(answer = false, flagUndetermined = true))
        assertFalse(homeAssistantEntryRemembers(answer = null, flagUndetermined = false))
    }

    @Test
    fun rememberedPerPersonAndServer() {
        val prefs = SyncTestPreferences()
        val memory = HomeAssistantEntryMemory(prefs)
        assertNull(memory.read("u1", "s1"))
        memory.write("u1", "s1", true)
        memory.write("u1", "s2", false)
        assertEquals(true, memory.read("u1", "s1"))
        assertEquals(false, memory.read("u1", "s2"))
        assertNull(memory.read("u2", "s1"), "another person")
        assertEquals("1", prefs.values["raft:sidebarEntry:u1:s1:assistant"])
        // Nothing without both ids; an unreadable value is unknown.
        memory.write(null, "s1", true)
        memory.write("u1", " ", true)
        assertEquals(2, prefs.values.size)
        prefs.values["raft:sidebarEntry:u3:s1:assistant"] = "yes"
        assertNull(memory.read("u3", "s1"))
    }
}
