package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Web `rack/rackUsageLines.ts` (botiverse/slock #14650, #14662, read at 9d710a10a). */
class RaftRackUsageLinesMemoryTest {
    @Test
    fun eachLineRemembersItsOwnShapeOnThisDevice() {
        val prefs = SyncTestPreferences()
        val memory = RaftRackUsageLinesMemory(prefs)
        assertFalse(memory.twoLines("server-1", "m1", RuntimeAccountUsageProvider.Claude), "never seen: one line")

        memory.remember("server-1", "m1", RuntimeAccountUsageProvider.Claude, twoLines = true)
        memory.remember("server-1", "m1", RuntimeAccountUsageProvider.Codex, twoLines = false)

        assertTrue(memory.twoLines("server-1", "m1", RuntimeAccountUsageProvider.Claude))
        assertFalse(memory.twoLines("server-1", "m1", RuntimeAccountUsageProvider.Codex))
        assertFalse(memory.twoLines("server-2", "m1", RuntimeAccountUsageProvider.Claude), "another server's computer")
        assertEquals("2", prefs.values["computers:rackUsageLines:server-1:m1:claude"])
        // A new answer with no model line shrinks it again; a new tab or app start reads the same.
        memory.remember("server-1", "m1", RuntimeAccountUsageProvider.Claude, twoLines = false)
        assertFalse(RaftRackUsageLinesMemory(prefs).twoLines("server-1", "m1", RuntimeAccountUsageProvider.Claude))
    }
}
