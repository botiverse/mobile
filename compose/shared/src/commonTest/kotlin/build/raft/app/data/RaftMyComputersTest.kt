package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Mine" for every list of Computers (web botiverse/slock #14479, sidebarMachineOrder.test.ts
 * `isMyComputer`): a Computer I attached or one I own; a Public one (no owner) is nobody's.
 */
class RaftMyComputersTest {
    @Test
    fun mineIsAComputerIAttachedOrOneIOwn() {
        data class Case(val attached: Boolean, val owner: String?, val userId: String?, val mine: Boolean)
        val cases =
            listOf(
                Case(attached = true, owner = "someone", userId = "me", mine = true),
                Case(attached = false, owner = "me", userId = "me", mine = true),
                Case(attached = false, owner = null, userId = "me", mine = false),
                Case(attached = false, owner = "someone", userId = "me", mine = false),
                // Signed out: only the attach flag counts, never a null owner.
                Case(attached = false, owner = null, userId = null, mine = false),
                Case(attached = false, owner = null, userId = "", mine = false),
                Case(attached = true, owner = null, userId = null, mine = true),
            )
        cases.forEach { assertEquals(it.mine, raftIsMyComputer(it.attached, it.owner, it.userId), "$it") }
    }

    @Test
    fun mineFirstKeepsEachPartsOrder() {
        val cases =
            listOf(
                listOf("a", "B", "c", "D") to listOf("B", "D", "a", "c"),
                listOf("a", "c") to listOf("a", "c"),
                emptyList<String>() to emptyList(),
            )
        cases.forEach { (input, expected) -> assertEquals(expected, input.raftMineFirst { it.first().isUpperCase() }, "$input") }
    }
}
