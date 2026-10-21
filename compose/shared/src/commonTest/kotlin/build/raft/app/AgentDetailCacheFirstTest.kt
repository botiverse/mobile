package build.raft.app

import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftAgentDetailCache
import build.raft.app.data.RaftCachedAgentDetail
import build.raft.app.data.RaftKeyedSnapshotCache
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailLoadStatus
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.agentDetailCacheKey
import build.raft.app.data.sharedAgentDetailOwner
import build.raft.app.data.EmptyReason
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-layer plan step 5 (agent detail): the last canonical detail per environment + account +
 * server + agent replays on the next open (no spinner, no edit authority until the refresh
 * answers), survives server switches, is fenced only by account boundaries, evicted on lost
 * access and capped per server (Tenny rules 1-3).
 */
class AgentDetailCacheFirstTest {
    @Test
    fun serverSwitchAToBToAReplaysTheCachedDetailOnTheFirstFrameThenOneRefreshReplacesIt() = runTest {
        val cache: RaftAgentDetailCache = RaftKeyedSnapshotCache()
        val ownerA = owner(SERVER_A)
        val ownerB = owner(SERVER_B)
        cache.write(ownerA.agentDetailCacheKey(AGENT), cached(SERVER_A, "Atlas"), cache.ticket())
        cache.write(ownerB.agentDetailCacheKey(AGENT), cached(SERVER_B, "Atlas on B"), cache.ticket())

        // Back on A: the first frame reads A's entry, never B's.
        val firstFrame = assertNotNull(cache.peek(ownerA.agentDetailCacheKey(AGENT)))
        assertEquals("Atlas", firstFrame.detail.summary?.name)
        val store = SharedAgentDetailStore()
        store.seedCachedAgentDetail(AGENT, ownerA, profileGeneration = 1L, cached = firstFrame)
        val seeded = store.state.value
        assertEquals(SharedAgentDetailLoadStatus.Content, seeded.detailLoadStatus)
        assertEquals("Atlas", seeded.detail?.summary?.name)
        assertEquals(ServerId(SERVER_A), seeded.detailServerId)
        assertTrue(seeded.detailFromCache, "a replay grants no profile edit authority")

        // The single background refresh answers under the same generation and takes over.
        store.dispatch(SharedAgentDetailIntent.SelectMember(AGENT, ownerA, 1L))
        store.dispatch(
            SharedAgentDetailIntent.SetDetail(
                agentId = AGENT,
                detail = detail("Atlas Prime"),
                serverId = ServerId(SERVER_A),
                owner = ownerA,
                profileGeneration = 1L
            )
        )
        assertEquals("Atlas Prime", store.state.value.detail?.summary?.name)
        assertFalse(store.state.value.detailFromCache)
    }

    @Test
    fun cachedEntriesAreAccountScopedAndFencedOnlyByAccountBoundaries() = runTest {
        val cache: RaftAgentDetailCache = RaftKeyedSnapshotCache()
        val key = owner(SERVER_A).agentDetailCacheKey(AGENT)
        // A late answer after a server switch still lands on A's key (same ticket epoch).
        val ticket = cache.ticket()
        assertTrue(cache.write(key, cached(SERVER_A, "Atlas"), ticket))
        assertNull(cache.peek(owner(SERVER_A, account = "user-2").agentDetailCacheKey(AGENT)))

        // Sign-out fences an in-flight answer.
        val inFlight = cache.ticket()
        cache.clearAll()
        assertFalse(cache.write(key, cached(SERVER_A, "Atlas"), inFlight))
        assertNull(cache.peek(key))
    }

    @Test
    fun lostAccessEvictsAndIsTerminalEvenOverACachedPage() = runTest {
        val cache: RaftAgentDetailCache = RaftKeyedSnapshotCache()
        val key = owner(SERVER_A).agentDetailCacheKey(AGENT)
        cache.write(key, cached(SERVER_A, "Atlas"), cache.ticket())
        cache.evict(key)
        assertNull(cache.peek(key))

        assertTrue(DataResult.Empty(EmptyReason.NoSettingsAvailable).isAgentDetailAccessLost())
        assertTrue(DataResult.Error(ScreenError("http_404")).isAgentDetailAccessLost())
        assertTrue(DataResult.NoPermission(PermissionBlocker("agent_forbidden")).isAgentDetailAccessLost())
        assertFalse(DataResult.Error(ScreenError("server_unavailable")).isAgentDetailAccessLost())
        // A transient failure keeps the cached page; lost access uses the cold (terminal) plan.
        val cachedPlan = memberDetailLoadPlan(hasCachedDetail = true)
        assertEquals(
            MemberDetailFailureTransition.KeepCached,
            memberDetailFailureResolution(cachedPlan, MemberDetailFailureKind.Error, "server_unavailable").transition
        )
        assertEquals(
            MemberDetailFailureTransition.SetDetailNoPermission,
            memberDetailFailureResolution(
                memberDetailLoadPlan(hasCachedDetail = false),
                MemberDetailFailureKind.NoPermission,
                "agent_forbidden"
            ).transition
        )

        // Removed from the server: every agent of that server leaves.
        cache.write(key, cached(SERVER_A, "Atlas"), cache.ticket())
        val other = owner(SERVER_B).agentDetailCacheKey(AGENT)
        cache.write(other, cached(SERVER_B, "Atlas"), cache.ticket())
        cache.dropServer(SERVER_A, "user-1")
        assertNull(cache.peek(key))
        assertNotNull(cache.peek(other))
    }

    @Test
    fun cacheIsCappedPerServerLeastRecentlyUsedFirst() = runTest {
        val cache: RaftAgentDetailCache = RaftKeyedSnapshotCache(maxPerServer = 2, maxTotal = 3)
        val ownerA = owner(SERVER_A)
        listOf("a1", "a2").forEach { id ->
            cache.write(ownerA.agentDetailCacheKey(MemberId(id)), cached(SERVER_A, id, id), cache.ticket())
        }
        cache.peek(ownerA.agentDetailCacheKey(MemberId("a1"))) // a1 is now the most recent
        cache.write(ownerA.agentDetailCacheKey(MemberId("a3")), cached(SERVER_A, "a3", "a3"), cache.ticket())
        assertNull(cache.peek(ownerA.agentDetailCacheKey(MemberId("a2"))))
        assertNotNull(cache.peek(ownerA.agentDetailCacheKey(MemberId("a1"))))
        val ownerB = owner(SERVER_B)
        cache.write(ownerB.agentDetailCacheKey(MemberId("b1")), cached(SERVER_B, "b1", "b1"), cache.ticket())
        cache.write(ownerB.agentDetailCacheKey(MemberId("b2")), cached(SERVER_B, "b2", "b2"), cache.ticket())
        assertEquals(3, cache.sizeForTest(), "total cap")
    }

    private fun owner(serverId: String, account: String = "user-1") =
        assertNotNull(sharedAgentDetailOwner(ENVIRONMENT, account, ServerId(serverId)))

    private fun detail(name: String, agentId: String = AGENT.value) =
        AgentDetailSnapshot(
            agentId = MemberId(agentId),
            summary = AgentDetailSummary(id = agentId, name = name),
            directMessages = emptyList(),
            reminders = emptyList(),
            activity = emptyList()
        )

    private fun cached(serverId: String, name: String, agentId: String = AGENT.value) =
        RaftCachedAgentDetail(
            server = ServerSummary(id = ServerId(serverId), name = serverId),
            currentUserId = "user-1",
            detail = detail(name, agentId)
        )

    private companion object {
        const val ENVIRONMENT = "https://api.example"
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
        val AGENT = MemberId("agent-1")
    }
}
