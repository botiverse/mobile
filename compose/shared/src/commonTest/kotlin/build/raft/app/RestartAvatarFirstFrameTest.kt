package build.raft.app

import build.raft.app.data.ActorResolveOutcome
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.ProcessServerMembersOwner
import build.raft.app.data.RaftJointActorRefStore
import build.raft.app.data.RaftMemberAvatarRefStore
import build.raft.app.data.ResolvedActor
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Product-hypothesis tests for #proj-mobile:#290:
 * 1. After restart, persisted member and joint-channel actor avatars resolve synchronously on
 *    frame 1 without painting the default robot first.
 * 2. Before an agent's avatar is confirmed absent by the live roster or joint-actor directory,
 *    the slot stays pending (neutral frame) rather than flashing default `pixel:robot`; a
 *    non-authoritative candidate never overwrites a real persisted agent avatar with `pixel:robot`.
 */
class RestartAvatarFirstFrameTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restartedJointChannelRemoteAgentPaintsPersistedAvatarOnFrameOne() = runTest {
        val prefs = InMemoryRaftKeyValueStore()
        val serverId = ServerId(ServerKey)
        val owner = ProcessServerMembersOwner(environmentId = "https://api.raft.build", accountId = "user-1", serverId = ServerKey)
        val remoteAgentId = "0b6f7d1e-0000-4e4e-9b9b-000000000290"
        val firstAvatar = "https://cdn.raft.build/avatars/agents/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.webp"
        val updatedAvatar = "https://cdn.raft.build/avatars/agents/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.webp"

        // Session 1: resolve remote joint-channel agent and persist to RaftJointActorRefStore.
        val session1Scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var session1Calls = 0
        val session1Store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                presenceOwner = { owner },
                presenceOwnerFlow = { MutableStateFlow(owner) },
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
                jointActorRefStore = RaftJointActorRefStore(prefs),
                actorResolve = { _, refs ->
                    session1Calls += 1
                    ActorResolveOutcome.Resolved(
                        refs.map { ref ->
                            ResolvedActor(
                                kind = ref.kind,
                                id = ref.id,
                                homeServerId = "server-2",
                                name = "Imprint",
                                displayName = "Imprint",
                                avatarUrl = firstAvatar,
                                gravatarHash = null,
                            )
                        }
                    )
                },
                actorResolveScope = session1Scope,
            )
        val session1Resolver = raftMemberAvatarResolver(session1Store, ServerKey).forJointSurface(true)
        // Before resolve settles, the unknown remote agent is pending (no default pixel:robot flash).
        assertNull(session1Resolver.current(remoteAgentId))
        assertTrue(session1Resolver.isAgentAvatarPending(remoteAgentId, joint = true))

        val collector = launch { session1Resolver.jointActor(MemberKind.Agent, remoteAgentId).collect {} }
        advanceUntilIdle()
        assertEquals(1, session1Calls)
        assertEquals(firstAvatar, session1Resolver.current(remoteAgentId))
        assertFalse(session1Resolver.isAgentAvatarPending(remoteAgentId, joint = true))
        collector.cancel()

        // Session 2 (cold restart over the same preferences, network offline):
        // frame 1 resolves the real remote agent avatar synchronously without calling actors/resolve.
        val session2Scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var session2Calls = 0
        val session2Store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                presenceOwner = { owner },
                presenceOwnerFlow = { MutableStateFlow(owner) },
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
                jointActorRefStore = RaftJointActorRefStore(prefs),
                actorResolve = { _, _ ->
                    session2Calls += 1
                    ActorResolveOutcome.Unavailable
                },
                actorResolveScope = session2Scope,
            )
        val session2Resolver = raftMemberAvatarResolver(session2Store, ServerKey).forJointSurface(true)
        assertEquals(firstAvatar, session2Resolver.current(remoteAgentId))
        assertFalse(session2Resolver.isAgentAvatarPending(remoteAgentId, joint = true))
        val session2Collector = launch { session2Resolver.jointActor(MemberKind.Agent, remoteAgentId).collect {} }
        advanceUntilIdle()
        assertEquals(0, session2Calls)
        session2Collector.cancel()

        // Realtime actor:updated updates the persisted row for the next restart.
        session2Store.jointActors?.applyActorUpdated(
            serverId,
            ResolvedActor(
                kind = MemberKind.Agent,
                id = remoteAgentId,
                homeServerId = "server-2",
                name = "Imprint",
                displayName = "Imprint",
                avatarUrl = updatedAvatar,
                gravatarHash = null,
            ),
        )
        val session3RefStore = RaftJointActorRefStore(prefs)
        assertEquals(updatedAvatar, session3RefStore.readServer(ServerKey, "${owner.environmentId}|${owner.accountId}")[remoteAgentId]?.avatarUrl)
        assertEquals(listOf(updatedAvatar), session3RefStore.recentHttpAvatarUrls(maxCount = 8))

        // Different account cannot read the previous account's persisted joint actors; sign-out clears all.
        assertTrue(session3RefStore.readServer(ServerKey, "${owner.environmentId}|user-2").isEmpty())
        session3RefStore.clearAll()
        assertTrue(session3RefStore.readServer(ServerKey, "${owner.environmentId}|${owner.accountId}").isEmpty())
    }

    @Test
    fun unconfirmedAgentStaysPendingAndNonAuthoritativeCandidateNeverOverwritesCachedAvatar() {
        val prefs = InMemoryRaftKeyValueStore()
        val serverId = ServerId(ServerKey)
        val cachedAgentId = "agent-with-real-avatar"
        val bareAgentId = "agent-without-avatar"
        val deletedAgentId = "agent-deleted"
        val realAvatar = "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp"

        // Session 1: authoritative roster persists the real avatar, then a non-authoritative
        // message-author candidate with avatar=null arrives. It must NOT overwrite the real ref
        // with pixel:robot.
        val session1Store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
            )
        session1Store.seed(
            MemberSnapshot(
                ServerSummary(id = serverId, name = "Raft"),
                listOf(agent(cachedAgentId, AvatarReference.fromWireValue(realAvatar))),
            )
        )
        session1Store.seedProfileCandidates(
            serverId,
            listOf(agent(cachedAgentId, avatar = null).copy(serverId = serverId)),
        )

        // Session 2 (cold restart): cached agent paints its real avatar on frame 1; unconfirmed
        // agent stays pending instead of flashing default pixel:robot.
        val coldStore =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
            )
        val resolver = raftMemberAvatarResolver(coldStore, ServerKey)
        assertEquals(realAvatar, resolver.current(cachedAgentId))
        assertFalse(resolver.isAgentAvatarPending(cachedAgentId))
        assertNull(resolver.current(bareAgentId))
        assertTrue(resolver.isAgentAvatarPending(bareAgentId))
        assertEquals(listOf(realAvatar), RaftMemberAvatarRefStore(prefs).recentHttpAvatarUrls(maxCount = 8))

        // Once the live roster lands, the avatar-less agent is confirmed as pixel:robot and a
        // missing/deleted agent stops being pending so it never stays blank.
        coldStore.seed(
            MemberSnapshot(
                ServerSummary(id = serverId, name = "Raft"),
                listOf(
                    agent(cachedAgentId, AvatarReference.fromWireValue(realAvatar)),
                    agent(bareAgentId, avatar = null),
                ),
            )
        )
        assertEquals("pixel:robot", resolver.current(bareAgentId))
        assertFalse(resolver.isAgentAvatarPending(bareAgentId))
        assertFalse(resolver.isAgentAvatarPending(deletedAgentId))
    }

    private fun agent(id: String, avatar: AvatarReference?) =
        MemberProfile(id = MemberId(id), displayName = id, handle = id, kind = MemberKind.Agent, avatar = avatar)

    private companion object {
        const val ServerKey = "server-1"
    }
}
