package build.raft.app

import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Computer owner v1 on the Computers pages (web ComputerOwnerLabel / TransferComputerOwnerDialog, slock #13344). */
class RaftComputerOwnerModelTest {
    private val bea = SettingsComputerCreatorSnapshot(id = "user-2", name = "bea", displayName = "Bea")
    private val computer = SettingsComputerSnapshot(id = "m", name = "Studio", daemonVersion = "", isComputer = true, online = true)

    @Test
    fun theOwnerShowsOnlyWhileTheComputerHasOne() {
        assertEquals("Bea", raftComputerOwner(computer.copy(ownerUserId = "user-2", owner = bea))?.displayName)
        assertEquals("Bea", computer.copy(ownerUserId = "user-2", owner = bea).toRaftComputerRow().owner?.displayName)
        // Public, an older server, or an owner the server could not resolve: "Public (no owner)".
        assertNull(raftComputerOwner(computer))
        assertNull(raftComputerOwner(computer.copy(ownerUserId = null, owner = bea)))
        assertNull(raftComputerOwner(computer.copy(ownerUserId = "user-2", owner = null)))
    }

    @Test
    fun transferOffersThePeopleWhoAreNotGuestsNorTheCurrentOwner() {
        val members =
            listOf(
                human("user-1", "Ann", role = "owner"),
                human("user-2", "Bea", role = "member"),
                human("user-3", "Cy", role = "guest"),
                human("user-4", "Dee", role = "admin", status = "removed"),
                human("user-5", "", handle = "eve", role = "member"),
                MemberProfile(id = MemberId("agent-1"), displayName = "Bot", handle = "bot", kind = MemberKind.Agent, serverRole = "member"),
                human("user-1", "Ann again", role = "owner"),
            )
        val candidates = raftComputerOwnerCandidates(members, ownerUserId = "user-2", currentUserId = "user-1")
        assertEquals(
            listOf(
                RaftComputerOwnerCandidate("user-1", "Ann", isYou = true),
                RaftComputerOwnerCandidate("user-5", "eve", isYou = false),
            ),
            candidates
        )
        // Public: nobody is excluded as the owner.
        assertEquals(listOf("user-1", "user-2", "user-5"), raftComputerOwnerCandidates(members, null, null).map { it.id })
    }

    @Test
    fun theSearchMatchesTheShownName() {
        val people = listOf(RaftComputerOwnerCandidate("1", "Ann Lee", false), RaftComputerOwnerCandidate("2", "Bea", false))
        assertEquals(people, raftComputerOwnerCandidatesMatching(people, "  "))
        assertEquals(listOf(people[0]), raftComputerOwnerCandidatesMatching(people, "lee"))
        assertEquals(emptyList(), raftComputerOwnerCandidatesMatching(people, "zed"))
    }

    private fun human(id: String, name: String, handle: String = name.lowercase(), role: String, status: String = "active") =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = handle,
            kind = MemberKind.Human,
            serverRole = role,
            membershipStatus = status,
        )
}
