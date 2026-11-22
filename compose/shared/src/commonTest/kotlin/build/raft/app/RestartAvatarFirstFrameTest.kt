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

        // Session 2 (cold restart over the same preferences):
        // frame 1 resolves the persisted remote agent avatar synchronously without waiting on
        // network, then background revalidation refreshes it if the avatar changed while offline.
        val session2Scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var session2Calls = 0
        val session2Store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                presenceOwner = { owner },
                presenceOwnerFlow = { MutableStateFlow(owner) },
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
                jointActorRefStore = RaftJointActorRefStore(prefs),
                actorResolve = { _, refs ->
                    session2Calls += 1
                    ActorResolveOutcome.Resolved(
                        refs.map { ref ->
                            ResolvedActor(
                                kind = ref.kind,
                                id = ref.id,
                                homeServerId = "server-2",
                                name = "Imprint",
                                displayName = "Imprint",
                                avatarUrl = updatedAvatar,
                                gravatarHash = null,
                            )
                        }
                    )
                },
                actorResolveScope = session2Scope,
            )
        val session2Resolver = raftMemberAvatarResolver(session2Store, ServerKey).forJointSurface(true)
        assertEquals(firstAvatar, session2Resolver.current(remoteAgentId))
        assertFalse(session2Resolver.isAgentAvatarPending(remoteAgentId, joint = true))
        val session2Collector = launch { session2Resolver.jointActor(MemberKind.Agent, remoteAgentId).collect {} }
        advanceUntilIdle()
        assertEquals(1, session2Calls)
        assertEquals(updatedAvatar, session2Resolver.current(remoteAgentId))
        session2Collector.cancel()

        // Persisted row reflects the refreshed avatar, and ownerKey scoping prevents cross-account prewarm reads.
        val ownerKey = "${owner.environmentId}|${owner.accountId}"
        val session3RefStore = RaftJointActorRefStore(prefs)
        assertEquals(updatedAvatar, session3RefStore.readServer(ServerKey, ownerKey)[remoteAgentId]?.avatarUrl)
        assertEquals(listOf(updatedAvatar), session3RefStore.recentHttpAvatarUrls(maxCount = 8, ownerKey = ownerKey))
        assertTrue(session3RefStore.recentHttpAvatarUrls(maxCount = 8, ownerKey = "${owner.environmentId}|user-2").isEmpty())

        // Different account cannot read the previous account's persisted joint actors; sign-out clears all.
        assertTrue(session3RefStore.readServer(ServerKey, "${owner.environmentId}|user-2").isEmpty())
        session3RefStore.clearAll()
        assertTrue(session3RefStore.readServer(ServerKey, ownerKey).isEmpty())
    }

    @Test
    fun unconfirmedAgentStaysPendingAndSparseUpdatesNeverOverwriteCachedAvatar() {
        val prefs = InMemoryRaftKeyValueStore()
        val serverId = ServerId(ServerKey)
        val owner = ProcessServerMembersOwner(environmentId = "https://api.raft.build", accountId = "user-1", serverId = ServerKey)
        val ownerKey = "${owner.environmentId}|${owner.accountId}"
        val cachedAgentId = "agent-with-real-avatar"
        val bareAgentId = "agent-without-avatar"
        val deletedAgentId = "agent-deleted"
        val realAvatar = "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp"

        // Session 1: authoritative roster persists the real avatar to preferences.
        val session1Store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                presenceOwner = { owner },
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
            )
        session1Store.seed(
            MemberSnapshot(
                ServerSummary(id = serverId, name = "Raft"),
                listOf(agent(cachedAgentId, AvatarReference.fromWireValue(realAvatar))),
            )
        )

        // Session 2 (cold restart): a sparse profile slice with avatar=null lands before the full
        // directory roster. Its recordDurableAvatarRefs(authoritativeAbsence = false) must NOT
        // overwrite the real persisted avatar with pixel:robot.
        val coldStore =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                presenceOwner = { owner },
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
            )
        coldStore.seedProfiles(
            serverId = serverId,
            profiles = listOf(agent(cachedAgentId, avatar = null).copy(serverId = serverId)),
        )
        assertEquals(realAvatar, RaftMemberAvatarRefStore(prefs).avatarWireValue(ServerKey, cachedAgentId, ownerKey))

        val freshAfterRestart =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                presenceOwner = { owner },
                avatarRefStore = RaftMemberAvatarRefStore(prefs),
            )
        val resolver = raftMemberAvatarResolver(freshAfterRestart, ServerKey)
        assertEquals(realAvatar, resolver.current(cachedAgentId))
        assertFalse(resolver.isAgentAvatarPending(cachedAgentId))
        assertNull(resolver.current(bareAgentId))
        assertTrue(resolver.isAgentAvatarPending(bareAgentId))
        assertEquals(listOf(realAvatar), RaftMemberAvatarRefStore(prefs).recentHttpAvatarUrls(maxCount = 8, ownerKey = ownerKey))
        assertTrue(RaftMemberAvatarRefStore(prefs).recentHttpAvatarUrls(maxCount = 8, ownerKey = "${owner.environmentId}|user-2").isEmpty())

        // Once the live roster lands, the avatar-less agent is confirmed as pixel:robot and a
        // missing/deleted agent stops being pending so it never stays blank.
        freshAfterRestart.seed(
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
