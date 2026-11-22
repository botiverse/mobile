package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.RaftMemberAvatarRefStore
import build.raft.app.data.ResolvedActor
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun aJointChannelsAgentFromTheOtherServerWearsItsResolvedFaceNotTheRobot() {
        // #wg-deck:#286: the hand-off card drew "@Gogo" (the other server's agent) as the default
        // robot while Gogo's message rows, which read POST /api/actors/resolve, had the right face.
        val store = store()
        store.seed(MemberSnapshot(server(), listOf(agent("kirby", AvatarReference.Pixel("antenna")))))
        val resolver = raftMemberAvatarResolver(store, ServerKey)
        val gogo =
            ResolvedActor(MemberKind.Agent, "gogo", homeServerId = "server-2", name = "Gogo", displayName = "Gogo", avatarUrl = "pixel:frog", gravatarHash = null)
        val jointAvatar = gogo.explicitAvatarWireValue()
        assertNotNull(jointAvatar)
        val gogoIdentity = RaftMemberAvatarIdentity(MemberKind.Agent, "gogo", ServerKey)
        assertEquals(jointAvatar, raftMemberAvatarWireValue(gogoIdentity, resolver.current("gogo"), jointActorAvatarWireValue = jointAvatar))
        // Unresolved (or off a joint surface): the robot, as before.
        assertEquals("pixel:robot", raftMemberAvatarWireValue(gogoIdentity, resolver.current("gogo")))
        // What the row carries is fresher than the resolved actor; this server's directory beats both.
        assertEquals("pixel:cat", raftMemberAvatarWireValue(gogoIdentity, null, carriedAvatarWireValue = "pixel:cat", jointActorAvatarWireValue = jointAvatar))
        assertEquals(
            "pixel:antenna",
            raftMemberAvatarWireValue(RaftMemberAvatarIdentity(MemberKind.Agent, "kirby", ServerKey), resolver.current("kirby"), jointActorAvatarWireValue = jointAvatar),
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
