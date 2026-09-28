package build.raft.app

import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.KeyValueMembersAgentMachineGroupCollapseStore
import build.raft.app.data.MembersAgentMachineGroupNoMachineKey
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #409: members-page agent machine groups collapse per computer row, and
 * the state is remembered on this machine under the same key family web uses
 * (`slock:sidebarCollapsed:<userId>:agent-machine-group:<machineKey>`).
 */
class MembersAgentMachineGroupCollapseTest {

    @Test
    fun groupKeyPrefersComputerIdSoRenamesKeepTheCollapsedEntry() {
        val agents =
            listOf(
                member("a1", "computer:MacBook Pro", "computer-id:machine-1"),
                member("a2", "computer:Office Mini", "computer-id:machine-2"),
                member("a3", "computer:MacBook Pro", "computer-id:machine-1")
            )
        val groups = groupedAgentMembers(agents)
        assertEquals(2, groups.size)
        val machineOne = groups.single { it.key == "machine-1" }
        assertEquals("MacBook Pro", machineOne.label)
        assertEquals(listOf("a1", "a3"), machineOne.members.map { it.id.value })
    }

    @Test
    fun groupKeyFallsBackToNameThenNoMachineSentinel() {
        val agents =
            listOf(
                member("a1", "computer:MacBook Pro"),
                member("a2"),
                member("a3")
            )
        val groups = groupedAgentMembers(agents)
        assertEquals(2, groups.size)
        assertEquals("MacBook Pro", groups.first().key)
        assertEquals("MacBook Pro", groups.first().label)
        assertEquals(MembersAgentMachineGroupNoMachineKey, groups.last().key)
        assertEquals(membersNoComputerGroupLabel, groups.last().label)
        assertEquals(2, groups.last().members.size)
    }

    @Test
    fun collapseStoreRoundTripsOnlyUnderTheWebKeyFamily() {
        val backing = InMemorySlockKeyValueStore()
        val store = KeyValueMembersAgentMachineGroupCollapseStore(backing)
        assertFalse(store.isCollapsed("user-1", "machine-1"))
        store.setCollapsed("user-1", "machine-1", true)
        assertTrue(store.isCollapsed("user-1", "machine-1"))
        // The exact key is the contract with the web sidebar's reader/writer.
        assertEquals(
            "true",
            backing.getString("slock:sidebarCollapsed:user-1:agent-machine-group:machine-1")
        )
        // Other user/machine rows are independent.
        assertFalse(store.isCollapsed("user-2", "machine-1"))
        assertFalse(store.isCollapsed("user-1", "machine-2"))
        store.setCollapsed("user-1", "machine-1", false)
        assertFalse(store.isCollapsed("user-1", "machine-1"))
        assertEquals(
            "false",
            backing.getString("slock:sidebarCollapsed:user-1:agent-machine-group:machine-1")
        )
    }

    @Test
    fun collapseStoreIgnoresBlankScopeOrKey() {
        val backing = InMemorySlockKeyValueStore()
        val store = KeyValueMembersAgentMachineGroupCollapseStore(backing)
        store.setCollapsed("", "machine-1", true)
        store.setCollapsed("user-1", "", true)
        assertFalse(store.isCollapsed("", "machine-1"))
        assertFalse(store.isCollapsed("user-1", "machine-1"))
    }

    @Test
    fun lazyItemCountKeepsCollapsedGroupHeaderButDropsItsRows() {
        val agents =
            listOf(
                member("a1", "computer:mac", "computer-id:m1"),
                member("a2", "computer:mac", "computer-id:m1"),
                member("a3", "computer:linux", "computer-id:m2")
            )
        // All expanded: 3 fixed + 2 headers + 3 rows = 8.
        assertEquals(
            8,
            membersLazyItemCount(
                agents = agents,
                humansCount = 0,
                agentsExpanded = true,
                humansExpanded = false,
                showHumanDirectory = false,
                hasPullToRefreshItem = false
            )
        )
        // Collapsing m1 drops its 2 member rows but keeps both headers: 6.
        assertEquals(
            6,
            membersLazyItemCount(
                agents = agents,
                humansCount = 0,
                agentsExpanded = true,
                humansExpanded = false,
                collapsedAgentGroupKeys = setOf("m1"),
                showHumanDirectory = false,
                hasPullToRefreshItem = false
            )
        )
    }

    @Test
    fun collapseSurvivesAFreshStoreInstanceOnTheSameBacking() {
        // Re-entering the members page constructs a new collapse store over
        // the same preference store. The remembered state must still read.
        val backing = InMemorySlockKeyValueStore()
        KeyValueMembersAgentMachineGroupCollapseStore(backing)
            .setCollapsed("user-1", "machine-1", true)
        val reenteredStore = KeyValueMembersAgentMachineGroupCollapseStore(backing)
        assertTrue(reenteredStore.isCollapsed("user-1", "machine-1"))
        reenteredStore.setCollapsed("user-1", "machine-1", false)
        assertFalse(
            KeyValueMembersAgentMachineGroupCollapseStore(backing)
                .isCollapsed("user-1", "machine-1")
        )
    }

    private fun member(
        id: String,
        vararg badges: String
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = MemberKind.Agent,
            permissionBadges = badges.toList()
        )
}
