package build.raft.app.data

import build.raft.app.raftMemberAvatarResolver
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hands dd79da45: Home DM rows and message authors resolve their avatar through the member store,
 * whose directory is network-only. The durable avatar references must let a cold-started process
 * (a fresh store over the same preference storage) render the last known avatar before the
 * directory refetch lands, and must never outrank a live authoritative value.
 */
class RaftMemberAvatarRefStoreTest {
    @Test
    fun authoritativeDirectoryLoadIsReadableByAColdStartedProcess() {
        val preferences = InMemoryRaftKeyValueStore()
        val firstProcess = SharedServerMembersStore(PresenceNoopMemberRepository, avatarRefStore = RaftMemberAvatarRefStore(preferences))
        firstProcess.seed(MemberSnapshot(server(), listOf(agent("agent-1", UploadedAvatar), human("human-1", HumanAvatar))))

        // Cold start: new store instances, empty in-memory directory, same durable storage.
        val coldStore = SharedServerMembersStore(PresenceNoopMemberRepository, avatarRefStore = RaftMemberAvatarRefStore(preferences))
        assertNull(coldStore.state.value.memberProfile(ServerId(ServerKey), "agent-1"), "directory is not loaded yet")

        val resolver = raftMemberAvatarResolver(coldStore, ServerKey)
        assertEquals(UploadedAvatar, resolver.current("agent-1"))
        assertEquals(HumanAvatar, resolver.current("human-1"))
        assertNull(resolver.current("unknown-member"))
        assertNull(raftMemberAvatarResolver(coldStore, "other-server").current("agent-1"), "references are server scoped")
    }

    @Test
    fun liveDirectoryValueWinsAndRewritesTheReference() = runTest {
        val preferences = InMemoryRaftKeyValueStore()
        SharedServerMembersStore(PresenceNoopMemberRepository, avatarRefStore = RaftMemberAvatarRefStore(preferences))
            .seed(MemberSnapshot(server(), listOf(agent("agent-1", UploadedAvatar))))

        val coldStore = SharedServerMembersStore(PresenceNoopMemberRepository, avatarRefStore = RaftMemberAvatarRefStore(preferences))
        val resolver = raftMemberAvatarResolver(coldStore, ServerKey)
        assertEquals(UploadedAvatar, resolver("agent-1").first(), "flow starts from the durable reference")

        coldStore.seed(MemberSnapshot(server(), listOf(agent("agent-1", NewAvatar))))
        assertEquals(NewAvatar, resolver.current("agent-1"))
        assertEquals(NewAvatar, resolver("agent-1").first())

        val nextColdStart = RaftMemberAvatarRefStore(preferences)
        assertEquals(NewAvatar, nextColdStart.avatarWireValue(ServerKey, "agent-1", null))
    }

    @Test
    fun nullAuthoritativeAvatarDropsTheReferenceAndClearAllWipesEverything() {
        val preferences = InMemoryRaftKeyValueStore()
        val refs = RaftMemberAvatarRefStore(preferences)
        refs.record(ServerKey, Owner, mapOf("a" to UploadedAvatar, "b" to HumanAvatar))
        refs.record("server-2", Owner, mapOf("a" to NewAvatar))

        refs.record(ServerKey, Owner, mapOf("a" to null))
        assertNull(RaftMemberAvatarRefStore(preferences).avatarWireValue(ServerKey, "a", null))
        assertEquals(HumanAvatar, RaftMemberAvatarRefStore(preferences).avatarWireValue(ServerKey, "b", null))

        refs.clearAll()
        val afterClear = RaftMemberAvatarRefStore(preferences)
        assertNull(afterClear.avatarWireValue(ServerKey, "b", null))
        assertNull(afterClear.avatarWireValue("server-2", "a", null))
        assertNull(refs.avatarWireValue("server-2", "a", null), "the in-memory view is cleared too")
    }

    @Test
    fun referencesAreOwnerScopedAndUnboundWritesAreDropped() {
        val preferences = InMemoryRaftKeyValueStore()
        val refs = RaftMemberAvatarRefStore(preferences)
        refs.record(ServerKey, "env|account-a", mapOf("m" to UploadedAvatar))
        refs.record(ServerKey, null, mapOf("m" to NewAvatar))

        val cold = RaftMemberAvatarRefStore(preferences)
        assertEquals(UploadedAvatar, cold.avatarWireValue(ServerKey, "m", "env|account-a"))
        assertEquals(UploadedAvatar, cold.avatarWireValue(ServerKey, "m", null), "owner not resolved yet")
        assertNull(cold.avatarWireValue(ServerKey, "m", "env|account-b"), "another account never reads it")
        assertNull(cold.avatarWireValue(ServerKey, "m", "other-env|account-a"))

        cold.record(ServerKey, "env|account-b", mapOf("n" to HumanAvatar))
        assertNull(RaftMemberAvatarRefStore(preferences).avatarWireValue(ServerKey, "m", null), "a new owner replaces the page")
    }

    @Test
    fun sizeBoundKeepsTheMostRecentlyConfirmedReferences() {
        val refs = RaftMemberAvatarRefStore(InMemoryRaftKeyValueStore(), maxEntriesPerServer = 2)
        refs.record(ServerKey, Owner, mapOf("a" to "pixel:a", "b" to "pixel:b"))
        refs.record(ServerKey, Owner, mapOf("a" to "pixel:a"))
        refs.record(ServerKey, Owner, mapOf("c" to "pixel:c"))

        assertEquals("pixel:a", refs.avatarWireValue(ServerKey, "a", null))
        assertNull(refs.avatarWireValue(ServerKey, "b", null))
        assertEquals("pixel:c", refs.avatarWireValue(ServerKey, "c", null))
    }

    private fun server(): ServerSummary = ServerSummary(id = ServerId(ServerKey), name = "Raft")

    private fun agent(id: String, avatarUrl: String): MemberProfile =
        MemberProfile(id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Agent, avatarUrl = avatarUrl)

    private fun human(id: String, avatarUrl: String): MemberProfile =
        MemberProfile(id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Human, avatarUrl = avatarUrl)

    private companion object {
        const val ServerKey = "server-1"
        const val Owner = "local|local"
        const val UploadedAvatar = "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        const val NewAvatar = "https://cdn.raft.build/avatars/agents/feedfacefeedfacefeedfacefeedface.webp"
        const val HumanAvatar = "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
    }
}
