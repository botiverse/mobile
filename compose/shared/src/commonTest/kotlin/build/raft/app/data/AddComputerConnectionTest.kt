package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Web `resolveAddMachineConnectedMachine` (botiverse/slock staging, utils/addMachineConnection.ts). */
class AddComputerConnectionTest {
    private fun machine(id: String, online: Boolean, isComputer: Boolean = true, mine: Boolean = true) =
        SettingsComputerSnapshot(id = id, name = id, daemonVersion = "", isComputer = isComputer, attachedByCurrentUser = mine, online = online)

    @Test
    fun theRegisteredPlaceholderOnlineWinsWithoutConfirmation() {
        val match = resolveAddComputerConnectedMachine(listOf(machine("p", online = true, isComputer = false)), "p", emptyList())
        assertEquals("p", match?.machine?.id)
        assertEquals(false, match?.requiresConfirmation)
    }

    @Test
    fun onlyNewOrResumedComputersOfTheUserCount() {
        val before = listOf(machine("old", online = true), machine("theirs", online = false, mine = false))
        val baseline = before.map { it.toAddComputerBaselineEntry() }
        val now = listOf(machine("p", online = false, isComputer = false), machine("old", online = true), machine("theirs", online = true, mine = false))
        assertNull(resolveAddComputerConnectedMachine(now, "p", baseline))
    }

    @Test
    fun aFreshComputerIsUnambiguousOnlyWhenNoneOfTheUsersWasOnline() {
        val fresh = machine("new", online = true)
        assertEquals(false, resolveAddComputerConnectedMachine(listOf(fresh), "p", emptyList())?.requiresConfirmation)

        val baseline = listOf(machine("old", online = true).toAddComputerBaselineEntry())
        val match = resolveAddComputerConnectedMachine(listOf(machine("old", online = true), fresh), "p", baseline)
        assertEquals("new", match?.machine?.id)
        assertEquals(true, match?.requiresConfirmation)
    }

    @Test
    fun aResumedComputerAsksForConfirmation() {
        val baseline = listOf(machine("old", online = false).toAddComputerBaselineEntry())
        val match = resolveAddComputerConnectedMachine(listOf(machine("old", online = true)), "p", baseline)
        assertEquals("old", match?.machine?.id)
        assertEquals(true, match?.requiresConfirmation)
    }
}
