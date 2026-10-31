package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.RaftMemberAvatarRefStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one resolution order behind [RaftMemberAvatar]: the member directory, then the avatar saved
 * for the id, then what the row carries, then the kind's own default. Resolved through the real
 * members store and [raftMemberAvatarResolver], as the composable does.
 */
class RaftMemberAvatarTest {
    @Test
    fun pixelAvatarWithNoUrlResolvesToThePixelFace() {
        // The task head's "A" for an agent / Kirby's missing face: a pixel avatar has no URL, and
        // the URL-only copy the screens used to read was null.
        val store = store()
        store.seed(MemberSnapshot(server(), listOf(agent("kirby", AvatarReference.Pixel("antenna")))))
        val resolver = raftMemberAvatarResolver(store, ServerKey)

        assertEquals("pixel:antenna", resolve(MemberKind.Agent, "kirby", resolver))
        // The directory outranks what the row carries.
        assertEquals("pixel:antenna", resolve(MemberKind.Agent, "kirby", resolver, carried = "pixel:cat"))
        // Not in the directory: the row's own pixel face.
        assertEquals("pixel:cat", resolve(MemberKind.Agent, "ghost", resolver, carried = "pixel:cat"))
    }

    @Test
    fun agentWithNoAvatarWearsTheRobotDefault() {
        val store = store()
        store.seed(MemberSnapshot(server(), listOf(agent("plain", avatar = null))))
        val resolver = raftMemberAvatarResolver(store, ServerKey)

        assertEquals("pixel:robot", resolve(MemberKind.Agent, "plain", resolver))
        assertEquals("pixel:robot", resolve(MemberKind.Agent, "unknown-agent", resolver))
        assertEquals("pixel:robot", resolve(MemberKind.Agent, "no-store", EmptyRaftMemberAvatarResolver))
        // A person has no default face: the caller's label / placeholder shows.
        assertNull(resolve(MemberKind.Human, "unknown-human", resolver))
    }

    @Test
    fun unknownIdFallsBackToTheSavedAvatar() {
        val preferences = InMemoryRaftKeyValueStore()
        store(preferences).seed(MemberSnapshot(server(), listOf(human("artin", HumanAvatar), agent("kirby", AvatarReference.Pixel("pencil")))))
        // Cold start: a new store over the same storage, its directory not loaded yet.
        val cold = store(preferences)
        assertNull(cold.state.value.memberProfile(ServerId(ServerKey), "artin"), "directory is not loaded yet")
        val resolver = raftMemberAvatarResolver(cold, ServerKey)

        assertEquals(HumanAvatar, resolve(MemberKind.Human, "artin", resolver))
        assertEquals("pixel:pencil", resolve(MemberKind.Agent, "kirby", resolver))
        // The saved avatar outranks what the row carries, and is server scoped.
        assertEquals(HumanAvatar, resolve(MemberKind.Human, "artin", resolver, carried = OtherHumanAvatar))
        assertEquals(OtherHumanAvatar, resolve(MemberKind.Human, "artin", raftMemberAvatarResolver(cold, "other-server"), carried = OtherHumanAvatar))
    }

    @Test
    fun carriedValuesAreFilteredLikeAProfilesOwnAvatar() {
        val identity = RaftMemberAvatarIdentity(MemberKind.Human, "artin", ServerKey)
        // A person shows only an uploaded avatar or a Gravatar, as MemberProfile's helpers do.
        assertNull(raftMemberAvatarWireValue(identity, resolvedAvatarWireValue = null, carriedAvatarWireValue = "https://example.com/photo.png"))
        val gravatar = "gravatar:" + "a".repeat(64)
        assertEquals(gravatar, raftMemberAvatarWireValue(identity, resolvedAvatarWireValue = null, carriedAvatarWireValue = gravatar))
        // No person kind (an external author): the carried value as it is.
        assertEquals(
            "https://example.com/ext.png",
            raftMemberAvatarWireValue(RaftMemberAvatarIdentity(MemberKind.External, "x", null), null, "https://example.com/ext.png"),
        )
    }

    private fun resolve(kind: MemberKind, id: String, resolver: RaftMemberAvatarResolver, carried: String? = null): String? =
        raftMemberAvatarWireValue(RaftMemberAvatarIdentity(kind, id, ServerKey), resolver.current(id), carried)

    private fun store(preferences: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore()) =
        SharedServerMembersStore(PresenceNoopMemberRepository, avatarRefStore = RaftMemberAvatarRefStore(preferences))

    private fun server() = ServerSummary(id = ServerId(ServerKey), name = "Raft")

    private fun agent(id: String, avatar: AvatarReference?) =
        MemberProfile(id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Agent, avatar = avatar)

    private fun human(id: String, avatarUrl: String) =
        MemberProfile(id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Human, avatar = AvatarReference.fromWireValue(avatarUrl))

    private companion object {
        const val ServerKey = "server-1"
        const val HumanAvatar = "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        const val OtherHumanAvatar = "https://cdn.raft.build/avatars/users/fedcba9876543210fedcba9876543210.webp"
    }
}
