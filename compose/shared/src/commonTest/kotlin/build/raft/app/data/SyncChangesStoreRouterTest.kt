package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pulled changes reach each store through its reducer (rows: botiverse/slock 0d030e341 list items). */
class SyncChangesStoreRouterTest {
    private class RecordingTargets : RaftSyncStoreTargets {
        var server: ServerSummary? = ServerSummary(id = ServerId("server-1"), name = "Workspace", slug = "workspace")
        var names: Map<String, String> = mapOf("m-1" to "Mac")
        /** What the roster's list reads answer (one per shell store), as the provider classifies them. */
        var rosterResults: List<DataResult<*>?> = listOf(DataResult.Content(Unit))
        var machineResults: List<DataResult<*>?> = listOf(DataResult.Content(Unit))
        var channelMembersOk = true
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

        /** Per conversation id; unlisted ids can't be told. */
        val remoteDoneOutcomes = mutableMapOf<String, ActivityRemoteDoneOutcome>()

        override fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome {
            calls += "remote_done:$channelId:$doneFrontierSeq"
            return remoteDoneOutcomes[channelId] ?: ActivityRemoteDoneOutcome.CannotTell
        }

        override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String): Boolean {
            calls += "channel_members"
            channelMemberRefreshes += channelIds
            return channelMembersOk
        }

        override suspend fun reloadRoster(serverId: String, reason: String): RaftSyncSnapshotRead {
            calls += "roster_reload"
            return rosterResults.syncSnapshotRead()
        }

        override suspend fun reloadMachines(serverId: String, reason: String): RaftSyncSnapshotRead {
            calls += "machines_reload"
            return machineResults.syncSnapshotRead()
        }

        val messages = mutableListOf<Message>()

        override suspend fun applyMessageRows(serverId: String, messages: List<Message>) {
            calls += "message_rows"
            this.messages += messages
        }
    }

    private val targets = RecordingTargets()
    private val ledger = RaftSyncReloadLedger()
    private val router = RaftSyncChangesStoreRouter(targets, ledger)
    private val cursors = RaftSyncCursorStore(SyncTestPreferences())

    private fun engine(vararg answers: RaftSyncChangesFetch) =
        RaftSyncChangesEngine(api = ScriptedSyncChangesApi(*answers), cursors = cursors, sink = router)

    /** `servers/:id/members` for a guest: slock routes/servers.ts:2121 answers 403 (MemberRepositoryDataSource maps it). */
    private val guestForbidden =
        DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access", traceId = null))
    private val serverUnavailable = DataResult.Error(ScreenError(code = "server_unavailable", traceId = null))

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
    fun `message rows replace the message where it is held and nothing else reloads`() = runTest {
        apply(
            syncUpsert(1, "message", "m1", messageCardRowJson(state = "Testing", revision = 2)),
            syncUpsert(2, "message", "m1", messageCardRowJson(state = "Ready for testing", revision = 3)),
            // A row that doesn't read is left to the conversation's next read: no snapshot.
            syncUpsert(3, "message", "m2", """{"id":"m2"}"""),
        )
        assertEquals(listOf("message_rows"), targets.calls)
        // The page's newest row per message.
        val message = targets.messages.single()
        assertEquals("m1", message.id.value)
        assertEquals("Raft App 1.19.0 (11900001) · Ready for testing", message.body)
        assertEquals(3, readMessageCardFromMetadata(message.metadata)?.revision)
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
    fun `another device's Done is patched in place and only what it can't patch reads the lists`() = runTest {
        val done = """{"done":true,"doneFrontierSeq":"92233720368547758070"}"""
        val read = """{"readSeq":12,"readStateVersion":3}"""
        targets.remoteDoneOutcomes += mapOf(
            "ch-removed" to ActivityRemoteDoneOutcome.Removed,
            "ch-kept" to ActivityRemoteDoneOutcome.KeptNewerActivity,
        )
        // (changes, what is read again): Done alone, removed or kept, reads nothing; a read that
        // came with it reads Home (and Activity when the row stays); one that can't be told reads both once.
        val cases =
            listOf(
                listOf(syncStaleWithState(1, "ch-removed", done = done)) to listOf("remote_done:ch-removed:92233720368547758070"),
                listOf(syncStaleWithState(1, "ch-kept", done = done)) to listOf("remote_done:ch-kept:92233720368547758070"),
                listOf(syncStaleWithState(1, "ch-removed", refSeq = 12, read = read, done = done)) to
                    listOf("remote_done:ch-removed:92233720368547758070", "home"),
                listOf(syncStaleWithState(1, "ch-kept", refSeq = 12, read = read, done = done)) to
                    listOf("remote_done:ch-kept:92233720368547758070", "home", "activity"),
                // A read in one change and the Done in the next: one state with both parts.
                listOf(syncStaleWithState(1, "ch-removed", refSeq = 12, read = read), syncStaleWithState(2, "ch-removed", done = done)) to
                    listOf("remote_done:ch-removed:92233720368547758070", "home"),
                // Web stops at the first it can't patch; the lists bring the rest.
                listOf(
                    syncStaleWithState(1, "ch-unknown", done = done),
                    syncStaleWithState(2, "ch-other-unknown", done = done),
                    syncStaleWithState(3, "ch-removed", done = done),
                ) to listOf("remote_done:ch-unknown:92233720368547758070", "home", "activity"),
            )
        for ((changes, expected) in cases) {
            targets.calls.clear()
            apply(*changes.toTypedArray())
            assertEquals(expected, targets.calls, changes.toString())
        }
    }

    @Test
    fun `conversation changes this client can't patch read Home and Activity without asking the store`() = runTest {
        val done = """{"done":true,"doneFrontierSeq":"5"}"""
        val unpatchable =
            listOf(
                listOf(syncStale(1, "ch-1", 9)),
                listOf(syncStaleWithState(1, "ch-1", refSeq = 9, read = """{"readSeq":9,"readStateVersion":2}""")),
                listOf(syncStaleWithState(1, "ch-1", done = """{"done":false,"doneFrontierSeq":"5"}""")),
                listOf(syncStaleWithState(1, "ch-1", done = done, follow = """{"followed":true,"threadDone":false}""")),
                // Malformed parts make the whole change a reload (web readSyncConversationState).
                listOf(syncStaleWithState(1, "ch-1", done = """{"done":true,"doneFrontierSeq":5}""")),
                listOf(syncStaleWithState(1, "ch-1", done = """{"done":true,"doneFrontierSeq":"5a"}""")),
                listOf(syncStaleWithState(1, "ch-1", done = """{"done":"true","doneFrontierSeq":"5"}""")),
                listOf(syncStaleWithState(1, "ch-1", read = """{"readSeq":-1,"readStateVersion":2}""", done = done)),
                // A stateless change for the conversation in the same page.
                listOf(syncStaleWithState(1, "ch-1", done = done), syncStale(2, "ch-1", null)),
            )
        for (changes in unpatchable) {
            targets.calls.clear()
            apply(*changes.toTypedArray())
            assertEquals(listOf("home", "activity"), targets.calls, changes.toString())
        }
    }

    @Test
    fun `a Done with a null frontier still reaches the store which cannot tell`() = runTest {
        apply(syncStaleWithState(1, "ch-1", done = """{"done":true,"doneFrontierSeq":null}"""))
        assertEquals(listOf("remote_done:ch-1:null", "home", "activity"), targets.calls)
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
        targets.rosterResults = listOf(serverUnavailable)
        assertFalse(router.reloadSnapshots(syncTestScope, setOf(RaftSyncSnapshot.Agents), reason = "reset_forced"))
        assertEquals(listOf("roster_reload"), targets.calls)
    }

    @Test
    fun `only a 403 that is the server's answer for this viewer counts as not readable`() {
        assertEquals(RaftSyncSnapshotRead.Taken, DataResult.Content(Unit).syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.Taken, DataResult.Empty(EmptyReason.NoMembers).syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.NotReadable, guestForbidden.syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.Failed, serverUnavailable.syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.Failed, authSessionRequiredResult().syncSnapshotRead())
        // An edge block is not the server's answer: it may pass on the next try.
        val edgeBlocked =
            DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", serverMessage = "edge_blocked", traceId = null))
        assertEquals(RaftSyncSnapshotRead.Failed, edgeBlocked.syncSnapshotRead())
        // Several shell stores: one read is enough; refused beats failed; nothing read is a failure.
        assertEquals(RaftSyncSnapshotRead.Taken, listOf(serverUnavailable, DataResult.Content(Unit)).syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.NotReadable, listOf(null, guestForbidden).syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.Failed, listOf<DataResult<*>?>(null).syncSnapshotRead())
        assertEquals(RaftSyncSnapshotRead.Failed, emptyList<DataResult<*>?>().syncSnapshotRead())
    }

    @Test
    fun `a guest's first sync completes and keeps its cursor though the server roster is a 403`() = runTest {
        // #proj-mobile:#112 must-fix: the 403 dropped the cursor, so every reconnect started over.
        targets.rosterResults = listOf(guestForbidden)
        val first =
            engine(syncPage(syncChangesBody("c1.10.100")), syncPage(syncChangesBody("c1.11.100")))
                .pull(syncTestScope, trigger = "server_selected")

        assertEquals(true, assertIs<RaftSyncPullResult.Synced>(first).firstSync)
        assertTrue("roster_reload" in targets.calls)
        assertEquals("c1.11.100", cursors.read(syncTestScope))

        // The reconnect pulls from that cursor: no snapshot is taken again.
        targets.calls.clear()
        val again = engine(syncPage(syncChangesBody("c1.12.100"))).pull(syncTestScope, trigger = "reconnect")
        assertEquals(false, assertIs<RaftSyncPullResult.Synced>(again).firstSync)
        assertEquals(emptyList(), targets.calls)
        assertEquals("c1.12.100", cursors.read(syncTestScope))
    }

    @Test
    fun `a viewer without viewMachines keeps its cursor through a machines 403 and a reset`() = runTest {
        targets.machineResults = listOf(guestForbidden)
        cursors.write(syncTestScope, "c1.1.1")
        val reset = """{"reason":"forced","snapshots":["machines"]}"""

        val result = engine(syncPage(syncChangesBody("c1.2.1", reset = reset))).pull(syncTestScope, trigger = "reconnect")

        assertEquals("forced", assertIs<RaftSyncPullResult.Synced>(result).resetReason)
        assertEquals(listOf("machines_reload"), targets.calls)
        assertEquals("c1.2.1", cursors.read(syncTestScope))
        // Not a failure: the next pull does not read it again.
        assertTrue(ledger.takeRetakes("server-1").isEmpty)
    }

    @Test
    fun `a 5xx roster still fails the first sync and keeps no cursor`() = runTest {
        targets.rosterResults = listOf(serverUnavailable)

        val result = engine(syncPage(syncChangesBody("c1.10.100"))).pull(syncTestScope, trigger = "server_selected")

        val failed = assertIs<RaftSyncPullResult.Failed>(result)
        assertEquals("snapshot_failed", failed.code)
        assertTrue(failed.needsFullRefetchFallback)
        assertNull(cursors.read(syncTestScope))
    }

    @Test
    fun `a failed Home reload after a page is read again by the next pull`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        engine(syncPage(syncChangesBody("c1.2.1", changes = listOf(syncUpsert(2, "channel", "ch-1", """{"id":"ch-1"}""")))))
            .pull(syncTestScope, trigger = "foreground")
        assertEquals(listOf("home", "activity"), targets.calls)
        assertEquals("c1.2.1", cursors.read(syncTestScope))

        // Home's load (sent after the request) fails; Activity's reads.
        val homeTicket = ledger.beginListRead("server-1", RaftSyncListRead.Home)
        val activityTicket = ledger.beginListRead("server-1", RaftSyncListRead.Activity)
        ledger.endListRead("server-1", RaftSyncListRead.Home, homeTicket, serverUnavailable.syncSnapshotRead())
        ledger.endListRead("server-1", RaftSyncListRead.Activity, activityTicket, RaftSyncSnapshotRead.Taken)

        // The next pull, with no changes, reads Home again (only Home), before its page.
        targets.calls.clear()
        engine(syncPage(syncChangesBody("c1.3.1"))).pull(syncTestScope, trigger = "foreground")
        assertEquals(listOf("home"), targets.calls)

        // That read succeeds: the pull after it reads nothing.
        val retakeTicket = ledger.beginListRead("server-1", RaftSyncListRead.Home)
        ledger.endListRead("server-1", RaftSyncListRead.Home, retakeTicket, RaftSyncSnapshotRead.Taken)
        targets.calls.clear()
        engine(syncPage(syncChangesBody("c1.4.1"))).pull(syncTestScope, trigger = "foreground")
        assertEquals(emptyList(), targets.calls)
    }

    @Test
    fun `a Home read sent before the change settles nothing and a later success clears a failure`() {
        val before = ledger.beginListRead("server-1", RaftSyncListRead.Home)
        ledger.requestListRead("server-1", RaftSyncListRead.Home)
        // The load in flight when the change came may predate it: its failure is not the reload's.
        ledger.endListRead("server-1", RaftSyncListRead.Home, before, RaftSyncSnapshotRead.Failed)
        assertTrue(ledger.takeRetakes("server-1").isEmpty)

        val after = ledger.beginListRead("server-1", RaftSyncListRead.Home)
        ledger.endListRead("server-1", RaftSyncListRead.Home, after, RaftSyncSnapshotRead.Failed)
        // A pull-to-refresh that reads Home before the next pull: nothing left to retake.
        ledger.endListRead("server-1", RaftSyncListRead.Home, ledger.beginListRead("server-1", RaftSyncListRead.Home), RaftSyncSnapshotRead.Taken)
        assertTrue(ledger.takeRetakes("server-1").isEmpty)

        // A request whose read fails is retaken once: the retake asks again.
        ledger.requestListRead("server-1", RaftSyncListRead.Activity)
        ledger.endListRead("server-1", RaftSyncListRead.Activity, ledger.beginListRead("server-1", RaftSyncListRead.Activity), RaftSyncSnapshotRead.Failed)
        assertEquals(setOf(RaftSyncListRead.Activity), ledger.takeRetakes("server-1").lists)
        assertTrue(ledger.takeRetakes("server-1").isEmpty)
    }

    @Test
    fun `failed channel member and roster reloads after a page are retaken by the next pull`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        targets.channelMembersOk = false
        targets.rosterResults = listOf(serverUnavailable)
        engine(
            syncPage(
                syncChangesBody(
                    "c1.2.1",
                    changes = listOf(syncDelete(2, "channel_member", "ch-1:user:u-2"), """{"seq":3,"entity":"agent","id":"a-1","op":"quarantined"}"""),
                )
            )
        ).pull(syncTestScope, trigger = "foreground")
        assertEquals(listOf("channel_members", "roster_reload"), targets.calls)
        // The cursor goes on: the rows were applied.
        assertEquals("c1.2.1", cursors.read(syncTestScope))

        targets.channelMembersOk = true
        targets.rosterResults = listOf(DataResult.Content(Unit))
        targets.calls.clear()
        engine(syncPage(syncChangesBody("c1.3.1"))).pull(syncTestScope, trigger = "foreground")
        assertEquals(listOf("roster_reload", "channel_members"), targets.calls)
        // Every loaded roster: which channels failed is not kept.
        assertEquals(null, targets.channelMemberRefreshes.last())

        targets.calls.clear()
        engine(syncPage(syncChangesBody("c1.4.1"))).pull(syncTestScope, trigger = "foreground")
        assertEquals(emptyList(), targets.calls)
    }
}
