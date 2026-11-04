package build.raft.app.data

import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.currentMemberAvatarWireValue
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.UserProfileUpdated
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `user:profile-updated` (botiverse/slock d311e6f0f9 #12688): a person's new name or avatar is
 * patched in place into the member directory and, for the signed-in person, their stored user.
 * No member-list reload.
 */
class UserProfileUpdatedTest {
    private val serverA = "7f2c6a1e-1111-4a5b-9c3d-000000000001"
    private val serverB = "7f2c6a1e-1111-4a5b-9c3d-000000000002"
    private val personId = "0b3e2d4c-2222-4f6a-8b1c-000000000010"
    private val otherId = "0b3e2d4c-2222-4f6a-8b1c-000000000011"
    private val gravatar = "5d41402abc4b2a76b9719d911017c592ee8a5f2b8c5f7a1d3e4b6c8d0f1a2b3c"

    private fun event(
        serverId: String = serverA,
        userId: String = personId,
        name: String = "ann",
        displayName: String? = "Ann B",
        avatarUrl: String? = "https://api.raft.test/api/avatars/users/4e5f6a7b.webp",
    ) = UserProfileUpdated(ServerId(serverId), MemberId(userId), name, displayName, avatarUrl, gravatar)

    // --- Wire ---------------------------------------------------------------------------------

    @Test
    fun mapperReadsTheUpstreamPayload() {
        assertEquals("user:profile-updated", RealtimeSocketEvents.UserProfileUpdated)
        assertTrue(RealtimeSocketEvents.UserProfileUpdated in RealtimeSocketEvents.ServerWireEvents)
        val mapper = RealtimeEventMapper()
        // packages/server/src/routes/authProfileUpdatedEvent.api.test.ts `expected()`: exactly
        // { serverId, userId, name, displayName, avatarUrl, gravatarHash }.
        assertEquals(
            event(),
            mapper.map(
                RealtimeSocketEvents.UserProfileUpdated,
                """{"serverId":"$serverA","userId":"$personId","name":"ann","displayName":"Ann B",""" +
                    """"avatarUrl":"https://api.raft.test/api/avatars/users/4e5f6a7b.webp","gravatarHash":"$gravatar"}""",
            ),
        )
        // Nullable displayName / avatarUrl (avatar removed, no display name).
        assertEquals(
            event(displayName = null, avatarUrl = null),
            mapper.map(
                RealtimeSocketEvents.UserProfileUpdated,
                """{"serverId":"$serverA","userId":"$personId","name":"ann","displayName":null,"avatarUrl":null,"gravatarHash":"$gravatar"}""",
            ),
        )
        assertNull(
            mapper.map(
                RealtimeSocketEvents.UserProfileUpdated,
                """{"serverId":"$serverA","name":"ann","displayName":null,"avatarUrl":null,"gravatarHash":"$gravatar"}""",
            ),
        )
        assertNull(
            mapper.map(
                RealtimeSocketEvents.UserProfileUpdated,
                """{"serverId":"","userId":"$personId","name":"ann","displayName":null,"avatarUrl":null,"gravatarHash":"$gravatar"}""",
            ),
        )
    }

    // --- Member directory ---------------------------------------------------------------------

    private fun human(id: String, name: String, avatar: String? = null) =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Human,
            avatar = AvatarReference.fromWireValue(avatar),
        )

    @Test
    fun theOnePersonIsPatchedInPlaceWithoutAReload() = runTest {
        val repository =
            FakeMemberRepository(
                serverMembers = listOf(human(personId, "Ann", "https://api.raft.test/api/avatars/users/0a1b2c3d.webp"), human(otherId, "Bob")),
            )
        val store = SharedServerMembersStore(repository)
        store.refreshMembers(ServerId(serverA), force = true)
        val otherBefore = store.currentServerMembers(ServerId(serverA)).first { it.id.value == otherId }

        assertTrue(store.applyUserProfileUpdated(event()))

        val person = store.currentServerMembers(ServerId(serverA)).first { it.id.value == personId }
        assertEquals("Ann B", person.displayName)
        assertEquals("ann", person.handle)
        assertEquals(gravatar, person.gravatarHash)
        assertEquals(
            "https://api.raft.test/api/avatars/users/4e5f6a7b.webp",
            store.state.value.currentMemberAvatarWireValue(ServerId(serverA), personId),
        )
        // Every other member keeps its object; the roster was read once (the load).
        assertSame(otherBefore, store.currentServerMembers(ServerId(serverA)).first { it.id.value == otherId })
        assertEquals(1, repository.serverMemberRequests.size)
    }

    @Test
    fun aRemovedAvatarFallsBackAndTheSameProfileAgainChangesNothing() = runTest {
        val store =
            SharedServerMembersStore(
                FakeMemberRepository(serverMembers = listOf(human(personId, "Ann", "https://api.raft.test/api/avatars/users/0a1b2c3d.webp"))),
            )
        store.refreshMembers(ServerId(serverA), force = true)

        assertTrue(store.applyUserProfileUpdated(event(displayName = null, avatarUrl = null)))
        val person = store.currentServerMembers(ServerId(serverA)).single()
        assertNull(person.avatar)
        // No display name: the name is the label.
        assertEquals("ann", person.displayName)

        val before = store.state.value
        assertFalse(store.applyUserProfileUpdated(event(displayName = null, avatarUrl = null)))
        assertSame(before, store.state.value)
    }

    @Test
    fun anotherServerOrARosterNotHeldIsLeftAlone() = runTest {
        val store = SharedServerMembersStore(FakeMemberRepository(serverMembers = listOf(human(personId, "Ann"))))
        store.refreshMembers(ServerId(serverA), force = true)

        assertFalse(store.applyUserProfileUpdated(event(serverId = serverB)))
        assertEquals("Ann", store.currentServerMembers(ServerId(serverA)).single().displayName)
        assertNull(store.state.value.entryFor(ServerId(serverB)))
    }

    // --- Realtime routing and the signed-in user ----------------------------------------------

    private fun grant(serverId: String, principalId: String) =
        RealtimeGrantRecord(
            slotKey = RealtimeSlotKey("process-realtime-host", serverId),
            principalId = principalId,
            environmentId = "env-test",
            authGeneration = 1L,
            connectionFamilyId = "family-1",
            connectionEpochId = "epoch-1",
        )

    private fun session(userId: String = personId) =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = userId,
                    email = "ann@raft.test",
                    name = "ann",
                    displayName = "Ann",
                    avatarUrl = "https://api.raft.test/api/avatars/users/0a1b2c3d.webp",
                ),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = serverA,
        )

    @Test
    fun theProcessOwnerPatchesTheMembersAndTheSignedInUser() = runTest {
        val members = SharedServerMembersStore(FakeMemberRepository(serverMembers = listOf(human(personId, "Ann"))))
        members.refreshMembers(ServerId(serverA), force = true)
        val auth = InMemoryRaftAuthSessionStore(session())
        val owner = SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true })
        owner.installUserProfileUpdatedSink { principalId, event ->
            members.applyUserProfileUpdated(event)
            auth.applyRealtimeUserProfileUpdated(principalId, event)
        }

        owner.acceptProcessMutation(grant(serverA, personId), event())

        assertEquals("Ann B", members.currentServerMembers(ServerId(serverA)).single().displayName)
        val user = auth.readSession()!!.user
        assertEquals("Ann B", user.displayName)
        assertEquals("https://api.raft.test/api/avatars/users/4e5f6a7b.webp", user.avatarUrl)

        // Sent per server room: a frame naming another server than the connection's is not applied.
        owner.acceptProcessMutation(grant(serverA, personId), event(serverId = serverB, displayName = "Elsewhere"))
        assertEquals("Ann B", auth.readSession()!!.user.displayName)
    }

    @Test
    fun anotherPersonsChangeLeavesTheSignedInUser() = runTest {
        val auth = InMemoryRaftAuthSessionStore(session())

        assertFalse(auth.applyRealtimeUserProfileUpdated(personId, event(userId = otherId)))
        // A grant of another account never writes this account's user.
        assertFalse(auth.applyRealtimeUserProfileUpdated(otherId, event()))
        assertEquals("Ann", auth.readSession()!!.user.displayName)
        assertTrue(auth.applyRealtimeUserProfileUpdated(personId, event()))
        assertFalse(auth.applyRealtimeUserProfileUpdated(personId, event()))
    }
}
