package build.raft.app

import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftMemberAvatarResolverTest {
    @Test
    fun resolverExposesSynchronousSeedBeforeFlowCollection() {
        val updates = MutableStateFlow<String?>("pixel:seed")
        val resolver =
            RaftMemberAvatarResolver(
                currentAvatar = { memberId -> "pixel:${memberId ?: "none"}" },
                avatarFlow = { updates }
            )

        assertEquals("pixel:member-1", resolver.current("member-1"))
        assertEquals("pixel:seed", runBlocking { resolver("member-1").first() })
    }

    @Test
    fun resolverWithoutSeedStillPublishesLaterUpdate() = runBlocking {
        val updates = MutableStateFlow<String?>(null)
        val resolver =
            RaftMemberAvatarResolver(
                currentAvatar = { null },
                avatarFlow = { updates }
            )

        assertNull(resolver.current("member-1"))
        updates.value = "pixel:later"
        assertEquals("pixel:later", resolver("member-1").first { it != null })
    }

    @Test
    fun alreadyMountedRowsObserveTheSameHydratedAvatarUpdate() = runBlocking {
        val updates = MutableStateFlow<String?>(null)
        val resolver =
            RaftMemberAvatarResolver(
                currentAvatar = { updates.value },
                avatarFlow = { updates }
            )
        val firstMountedRow = async { resolver("agent-1").first { it != null } }
        val secondMountedRow = async { resolver("agent-1").first { it != null } }
        val uploadedAvatar = "https://cdn.raft.build/avatars/agents/deadbeef.webp"

        updates.value = uploadedAvatar

        assertEquals(uploadedAvatar, firstMountedRow.await())
        assertEquals(uploadedAvatar, secondMountedRow.await())
    }

    @Test
    fun currentSnapshotUsesTypedServerScopeForHumanAndAgent() {
        val sharedMemberId = MemberId("shared-member")
        val state =
            SharedServerMembersState(
                entriesByServerId =
                    mapOf(
                        "server-a" to
                            SharedServerMembersEntry(
                                serverId = "server-a",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = sharedMemberId,
                                            displayName = "Human A",
                                            handle = "human-a",
                                            kind = MemberKind.Human,
                                            avatar =
                                                AvatarReference.fromWireValue(
                                                    "https://cdn.slock.ai/api/avatars/users/deadbeef.webp"
                                                ),
                                            serverId = ServerId("server-a")
                                        )
                                    )
                            ),
                        "server-b" to
                            SharedServerMembersEntry(
                                serverId = "server-b",
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = sharedMemberId,
                                            displayName = "Agent B",
                                            handle = "agent-b",
                                            kind = MemberKind.Agent,
                                            avatar = AvatarReference.fromWireValue("pixel:agent-b"),
                                            serverId = ServerId("server-b")
                                        )
                                    )
                            )
                    )
            )

        assertEquals(
            "https://cdn.slock.ai/api/avatars/users/deadbeef.webp",
            state.currentMemberAvatarWireValue(ServerId("server-a"), sharedMemberId.value)
        )
        assertEquals(
            "pixel:agent-b",
            state.currentMemberAvatarWireValue(ServerId("server-b"), sharedMemberId.value)
        )
        assertNull(state.currentMemberAvatarWireValue(ServerId("server-c"), sharedMemberId.value))
    }

    @Test
    fun missingServerScopeReturnsEmptyAvatarResolver() {
        assertNull(raftMemberAvatarResolver(null, null).current("member-1"))
    }
}
