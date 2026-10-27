package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pulled changes reach each store through its reducer (rows: botiverse/slock 0d030e341 list items). */
class SyncChangesStoreRouterTest {
    private class RecordingTargets : RaftSyncStoreTargets {
        var server: ServerSummary? = ServerSummary(id = ServerId("server-1"), name = "Workspace", slug = "workspace")
        var names: Map<String, String> = mapOf("m-1" to "Mac")
        var rosterOk = true
        val calls = mutableListOf<String>()
        val rosterUpserts = mutableListOf<MemberProfile>()
        val rosterDeletes = mutableListOf<Pair<MemberId, MemberKind>>()
        val machineUpserts = mutableListOf<ComputerSummary>()
        val machineDeletes = mutableSetOf<String>()
        val channelMemberRefreshes = mutableListOf<Set<String>?>()

        override fun rosterServer(serverId: String): ServerSummary? = server

        override fun machineNames(serverId: String): Map<String, String> = names

        override suspend fun supersedeRosterLoad(serverId: String, reason: String) {
            calls += "roster_supersede"
        }

        override suspend fun applyRosterRows(serverId: String, upserts: List<MemberProfile>, deletes: List<Pair<MemberId, MemberKind>>) {
            calls += "roster_rows"
            rosterUpserts += upserts
            rosterDeletes += deletes
        }

        override suspend fun applyMachineRows(serverId: String, upserts: List<ComputerSummary>, deletes: Set<String>) {
            calls += "machine_rows"
            machineUpserts += upserts
            machineDeletes += deletes
        }

        override fun refreshHome(serverId: String, reason: String) {
            calls += "home"
        }

        override fun refreshActivity(serverId: String, reason: String) {
            calls += "activity"
        }

        override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String) {
            calls += "channel_members"
            channelMemberRefreshes += channelIds
        }

        override suspend fun reloadRoster(serverId: String, reason: String): Boolean {
            calls += "roster_reload"
            return rosterOk
        }

        override suspend fun reloadMachines(serverId: String, reason: String): Boolean {
            calls += "machines_reload"
            return true
        }
    }

    private val targets = RecordingTargets()
    private val router = RaftSyncChangesStoreRouter(targets)

    private suspend fun apply(vararg changes: String) {
        router.apply(syncTestScope, parseSyncChangesPage(syncChangesBody("c1.9.1", changes = changes.toList()))!!.changes.toSyncApplyPlan())
    }

    @Test
    fun `server member and agent rows patch the roster row by row`() = runTest {
        apply(
            syncUpsert(1, "server_member", "u-2", """{"userId":"u-2","name":"ann","displayName":"Ann","role":"admin"}"""),
            syncUpsert(2, "agent", "a-1", """{"id":"a-1","name":"bot","displayName":"Bot","status":"active","runtime":"claude","machineId":"m-1"}"""),
            syncUpsert(3, "agent", "a-3", """{"id":"a-3","name":"gone","deletedAt":"2026-10-27T00:00:00.000Z"}"""),
            syncDelete(4, "agent", "a-2"),
            syncDelete(5, "server_member", "u-3"),
        )

        assertEquals(listOf("roster_rows"), targets.calls)
        val human = targets.rosterUpserts.single { it.kind == MemberKind.Human }
        assertEquals("u-2", human.id.value)
        assertEquals("Ann", human.displayName)
        assertEquals(ServerId("server-1"), human.serverId)
        val agent = targets.rosterUpserts.single { it.kind == MemberKind.Agent }
        assertEquals("a-1", agent.id.value)
        // The computer label comes from the machine list, as the full roster load resolves it.
        assertTrue("computer:Mac" in agent.permissionBadges, agent.permissionBadges.toString())
        assertEquals(
            setOf(MemberId("a-3") to MemberKind.Agent, MemberId("a-2") to MemberKind.Agent, MemberId("u-3") to MemberKind.Human),
            targets.rosterDeletes.toSet(),
        )
    }

    @Test
    fun `member rows retire the roster load in flight when none is loaded but deletes still apply`() = runTest {
        targets.server = null
        apply(
            syncUpsert(1, "server_member", "u-2", """{"userId":"u-2","name":"ann"}"""),
            syncDelete(2, "server_member", "u-3"),
        )
        assertTrue(targets.rosterUpserts.isEmpty())
        // The roster's first load may have been read before the rows: it must not win.
        assertEquals("roster_supersede", targets.calls.first())
        assertEquals(listOf(MemberId("u-3") to MemberKind.Human), targets.rosterDeletes)
    }

    @Test
    fun `a row that does not decode retakes its snapshot`() = runTest {
        apply(syncUpsert(1, "server_member", "u-2", """{"userId":"u-2"}"""))
        assertEquals(listOf("roster_reload"), targets.calls)
    }

    @Test
    fun `machine rows patch the computers list and a rename retakes the roster labels`() = runTest {
        apply(
            syncUpsert(1, "machine", "m-2", """{"id":"m-2","name":"Linux box","status":"offline"}"""),
            syncDelete(2, "machine", "m-3"),
        )
        assertEquals(listOf("machine_rows"), targets.calls)
        assertEquals(listOf("m-2"), targets.machineUpserts.map { it.id })
        assertEquals(setOf("m-3"), targets.machineDeletes)

        targets.calls.clear()
        apply(syncUpsert(3, "machine", "m-1", """{"id":"m-1","name":"Renamed Mac","status":"online"}"""))
        assertEquals(listOf("machine_rows", "roster_reload"), targets.calls)
    }

    @Test
    fun `channel and conversation changes reload Home and Activity once per page`() = runTest {
        apply(
            syncUpsert(1, "channel", "ch-1", """{"id":"ch-1","name":"general"}"""),
            syncDelete(2, "channel", "ch-2"),
            syncStale(3, "ch-1", 9),
        )
        assertEquals(listOf("home", "activity"), targets.calls)

        targets.calls.clear()
        apply(syncUpsert(4, "sidebar_order", "server-1", """{"channelOrder":[]}"""), syncUpsert(5, "server_order", "user-1", "{}"))
        assertEquals(listOf("home"), targets.calls)
    }

    @Test
    fun `channel member rows refetch the loaded rosters of their channels`() = runTest {
        apply(
            syncUpsert(1, "channel_member", "ch-1:user:u-2", """{"id":"u-2"}"""),
            syncDelete(2, "channel_member", "ch-1:agent:a-1"),
            syncDelete(3, "channel_member", "ch-2:user:u-3"),
        )
        assertEquals(listOf("channel_members"), targets.calls)
        assertEquals(listOf<Set<String>?>(setOf("ch-1", "ch-2")), targets.channelMemberRefreshes)
    }

    @Test
    fun `an added channel scope retakes the channel snapshots and only its member roster`() = runTest {
        apply(syncScope(1, "channel", "room-1", "added", syncChannelScopeSnapshots))
        assertEquals(listOf("home", "activity", "channel_members"), targets.calls)
        assertEquals(listOf<Set<String>?>(setOf("room-1")), targets.channelMemberRefreshes)
    }

    @Test
    fun `a removed channel reloads Home and Activity and a removed server validates its access`() = runTest {
        apply(syncScope(1, "channel", "room-1", "removed", syncChannelScopeSnapshots))
        assertEquals(listOf("home", "activity"), targets.calls)

        targets.calls.clear()
        apply(syncScope(2, "server", "server-1", "removed", syncAllSnapshotNames))
        assertEquals(listOf("home", "activity", "roster_reload", "machines_reload"), targets.calls)
    }

    @Test
    fun `an unknown op retakes only that entity`() = runTest {
        apply("""{"seq":1,"entity":"machine","id":"m-1","op":"quarantined"}""")
        assertEquals(listOf("machines_reload"), targets.calls)
    }

    @Test
    fun `snapshot reloads cover every store and fail when the roster fails`() = runTest {
        assertTrue(router.reloadSnapshots(syncTestScope, RaftSyncSnapshot.All, reason = "first_sync"))
        assertEquals(listOf("home", "activity", "roster_reload", "machines_reload", "channel_members"), targets.calls)
        assertEquals(listOf<Set<String>?>(null), targets.channelMemberRefreshes)

        targets.calls.clear()
        targets.rosterOk = false
        assertFalse(router.reloadSnapshots(syncTestScope, setOf(RaftSyncSnapshot.Agents), reason = "reset_forced"))
        assertEquals(listOf("roster_reload"), targets.calls)
    }
}
