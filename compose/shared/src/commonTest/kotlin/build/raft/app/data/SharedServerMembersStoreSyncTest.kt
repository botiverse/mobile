package build.raft.app.data

import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Incremental sync `server_member` / `agent` rows patch the loaded roster (no full reload). */
class SharedServerMembersStoreSyncTest {
    private val serverId = ServerId("server-1")

    private fun profile(id: String, name: String, kind: MemberKind) =
        MemberProfile(id = MemberId(id), displayName = name, handle = name.lowercase(), kind = kind)

    @Test
    fun `upserts replace by kind and id or join and deletes leave`() = runTest {
        val repository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        profile("u-1", "Ann", MemberKind.Human),
                        profile("a-1", "Bot", MemberKind.Agent),
                        profile("a-2", "Old", MemberKind.Agent),
                    )
            )
        val store = SharedServerMembersStore(repository)
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(serverId, force = true))

        val changed =
            store.applySyncedRosterRows(
                serverId = serverId,
                upserts = listOf(profile("u-1", "Ann B", MemberKind.Human), profile("a-3", "New", MemberKind.Agent)),
                deletes = listOf(MemberId("a-2") to MemberKind.Agent),
            )

        assertTrue(changed)
        assertEquals(
            listOf("u-1" to "Ann B", "a-1" to "Bot", "a-3" to "New"),
            store.currentServerMembers(serverId).map { it.id.value to it.displayName },
        )
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.currentServerMembersEntry(serverId).status)
        // One network read: the load. Applying rows never fetched.
        assertEquals(1, repository.serverMemberRequests.size)
    }

    @Test
    fun `the same rows again change nothing`() = runTest {
        val store = SharedServerMembersStore(FakeMemberRepository(serverMembers = listOf(profile("u-1", "Ann", MemberKind.Human))))
        store.refreshMembers(serverId, force = true)
        val before = store.state.value

        assertFalse(store.applySyncedRosterRows(serverId, listOf(profile("u-1", "Ann", MemberKind.Human)), emptyList()))
        assertEquals(before, store.state.value)
    }

    @Test
    fun `a roster that is not loaded is left for its load`() = runTest {
        val store = SharedServerMembersStore(FakeMemberRepository())

        assertFalse(store.applySyncedRosterRows(serverId, listOf(profile("u-1", "Ann", MemberKind.Human)), emptyList()))
        assertNull(store.state.value.entryFor(serverId))
    }
}
