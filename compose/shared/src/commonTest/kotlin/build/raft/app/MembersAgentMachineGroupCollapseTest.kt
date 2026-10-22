package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
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
    fun groupsFollowTheComputersListWithNoComputerLast() {
        // artin task #53: the agent groups follow the Computers list (mine first); a computer the
        // list doesn't show comes after the known ones, the group without a computer last.
        val agents =
            listOf(
                member("none"),
                member("a1", "computer:Old Box", "computer-id:machine-1"),
                member("a2", "computer:Hidden", "computer-id:machine-9"),
                member("a3", "computer:My Mac", "computer-id:machine-3")
            )
        val groups = groupedAgentMembers(agents, machineOrder = listOf("machine-3", "machine-1"))
        assertEquals(listOf("machine-3", "machine-1", "machine-9", MembersAgentMachineGroupNoMachineKey), groups.map { it.key })
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
    fun anAgentWithAMachineIdButNoNameIsNeverListedUnderNoComputer() {
        // Task #148: the list row carries machineId; the name is null until the agent first runs.
        val agents =
            listOf(
                member("fresh", "computer-id:machine-9"),
                member("other", "computer:Office Mini", "computer-id:machine-2"),
                member("none")
            )

        val named = groupedAgentMembers(agents) { id -> mapOf("machine-9" to "daybed-earth")[id] }
        assertEquals("daybed-earth", named.single { it.key == "machine-9" }.label, "named from the computer list")
        assertEquals("Office Mini", named.single { it.key == "machine-2" }.label)
        assertEquals(membersNoComputerGroupLabel, named.single { it.key == MembersAgentMachineGroupNoMachineKey }.label)

        val unloaded = groupedAgentMembers(agents)
        assertEquals("machine-9", unloaded.single { it.key == "machine-9" }.label, "computer list not loaded: show the id")
        assertEquals(1, unloaded.count { it.label == membersNoComputerGroupLabel }, "only the agent without a machine")

        val renamed = groupedAgentMembers(agents) { id -> mapOf("machine-2" to "Office Studio")[id] }
        assertEquals("Office Studio", renamed.single { it.key == "machine-2" }.label, "the computer list wins over a stale row name")
    }

    @Test
    fun collapseStoreRoundTripsOnlyUnderTheWebKeyFamily() {
        val backing = InMemoryRaftKeyValueStore()
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
        val backing = InMemoryRaftKeyValueStore()
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
                showHumanDirectory = false
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
                showHumanDirectory = false
            )
        )
    }

    @Test
    fun collapseSurvivesAFreshStoreInstanceOnTheSameBacking() {
        // Re-entering the members page constructs a new collapse store over
        // the same preference store. The remembered state must still read.
        val backing = InMemoryRaftKeyValueStore()
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

    @Test
    fun collapsedStateResolvesPerGroupSoLateArrivingMembersStillCollapse() {
        // Standalone members page: the session user id and the members list
        // both arrive asynchronously. If the user id resolves while the list
        // is still empty, no group exists to snapshot — the collapse state
        // must resolve per group when that group's row renders.
        val backing = InMemoryRaftKeyValueStore()
        val store = KeyValueMembersAgentMachineGroupCollapseStore(backing)
        store.setCollapsed("user-1", "machine-1", true)

        // The stored collapse applies when a group's row renders, whenever it
        // arrives — no snapshot of "which groups existed" is taken.
        assertTrue(resolveAgentGroupCollapsed("machine-1", emptyMap(), "user-1", store))
        assertFalse(resolveAgentGroupCollapsed("machine-2", emptyMap(), "user-1", store))
        // A tap this session overrides the stored default either way.
        assertFalse(
            resolveAgentGroupCollapsed(
                "machine-1",
                mapOf("machine-1" to false), // expand tap on a stored-collapsed group
                "user-1",
                store
            )
        )
        assertTrue(
            resolveAgentGroupCollapsed(
                "machine-2",
                mapOf("machine-2" to true), // collapse tap on a group with no stored state
                "user-1",
                store
            )
        )
        // Without a user id or store, groups render expanded.
        assertFalse(resolveAgentGroupCollapsed("machine-1", emptyMap(), "", store))
        assertFalse(resolveAgentGroupCollapsed("machine-1", emptyMap(), "user-1", null))
    }

    @Test
    fun `tap writes the inverted collapsed value so the row actually toggles`() {
        // Regression for the #409 no-op row: the handler must write the
        // inverted collapsed state (the current expanded value), not the
        // current collapsed value back into the slot.
        assertTrue(resolveAgentGroupCollapseToggle(false))  // expanded -> collapse
        assertFalse(resolveAgentGroupCollapseToggle(true))  // collapsed -> expand
        // The value written must differ from the resolved state for any input.
        for (collapsed in listOf(true, false)) {
            assertTrue(resolveAgentGroupCollapseToggle(collapsed) != collapsed)
        }
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
