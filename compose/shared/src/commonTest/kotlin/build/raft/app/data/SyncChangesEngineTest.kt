package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pull loop of `GET /api/sync/changes` (botiverse/slock 0d030e341, PR #11459 client loop). */
class SyncChangesEngineTest {
    private val preferences = SyncTestPreferences()
    private val cursors = RaftSyncCursorStore(preferences)

    private fun engine(api: RaftSyncChangesApi, sink: RaftSyncChangesSink, maxPages: Int = SYNC_CHANGES_MAX_PAGES_PER_PULL) =
        RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink, maxPagesPerPull = maxPages)

    @Test
    fun `first sync reads the cursor then takes every snapshot then pulls from that cursor`() = runTest {
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.10.100")),
                syncPage(syncChangesBody("c1.12.100", changes = listOf(syncDelete(11, "agent", "a-1")))),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf(null, "c1.10.100"), api.sinces)
        assertEquals(listOf("snapshots:first_sync", "apply"), sink.log)
        assertEquals(RaftSyncSnapshot.All, sink.snapshotReloads.single().first)
        assertEquals(setOf("a-1"), sink.plans.single().deletesOf(RaftSyncRowEntity.Agent))
        assertEquals("c1.12.100", cursors.read(syncTestScope))
        assertEquals(RaftSyncPullResult.Synced(pages = 1, changes = 1, firstSync = true, resetReason = null, truncated = false), result)
    }

    @Test
    fun `a reconnect pull waits the jitter before its snapshots but not before its changes`() = runTest {
        val log = mutableListOf<String>()
        val sink = RecordingSyncChangesSink(calls = log)
        val engine =
            RaftSyncChangesEngine(
                api =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.10.100")),
                        syncPage(syncChangesBody("c1.11.100", changes = listOf(syncDelete(11, "agent", "a-1")))),
                        syncPage(syncChangesBody("c1.12.100", changes = listOf(syncDelete(12, "agent", "a-2")))),
                    ),
                cursors = cursors,
                sink = sink,
                snapshotJitterDelay = { log += "jitter" },
            )

        engine.pull(syncTestScope, trigger = "reconnect", jitterSnapshots = true)
        assertEquals(listOf("jitter", "snapshots:first_sync", "apply"), log)

        log.clear()
        engine.pull(syncTestScope, trigger = "reconnect", jitterSnapshots = true)
        assertEquals(listOf("apply"), log)
    }

    @Test
    fun `a reconnect pull waits the short pull jitter before its first request and others do not`() = runTest {
        val log = mutableListOf<String>()
        val api =
            object : RaftSyncChangesApi {
                val scripted =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.11.100")),
                        syncPage(syncChangesBody("c1.12.100")),
                        syncPage(syncChangesBody("c1.13.100")),
                    )

                override suspend fun changes(serverId: build.raft.app.model.ServerId, since: String?, limit: Int) =
                    scripted.changes(serverId, since, limit).also { log += "request:${since ?: "none"}" }
            }
        cursors.write(syncTestScope, "c1.10.100")
        val engine =
            RaftSyncChangesEngine(
                api = api,
                cursors = cursors,
                sink = RecordingSyncChangesSink(calls = log),
                snapshotJitterDelay = { log += "snapshot_jitter" },
                pullJitterDelay = { log += "pull_jitter" },
            )

        engine.pull(syncTestScope, trigger = "reconnect", jitterSnapshots = true, jitterPull = true)
        assertEquals(listOf("pull_jitter", "request:c1.10.100"), log)

        log.clear()
        engine.pull(syncTestScope, trigger = "foreground")
        engine.pull(syncTestScope, trigger = "server_selected")
        assertEquals(listOf("request:c1.11.100", "request:c1.12.100"), log)
    }

    @Test
    fun `the reconnect pull jitter is uniform up to two seconds`() {
        val random = kotlin.random.Random(7)
        val delays = List(500) { reconnectRefetchJitterMillis(random, SYNC_CHANGES_RECONNECT_PULL_JITTER_MAX_MILLIS) }
        assertTrue(delays.all { it in 0L..2_000L })
        assertTrue(delays.any { it < 500L } && delays.any { it > 1_500L })
    }

    @Test
    fun `a failed first snapshot keeps no cursor so the next pull starts over`() = runTest {
        val api = ScriptedSyncChangesApi(syncPage(syncChangesBody("c1.10.100")))
        val sink = RecordingSyncChangesSink(snapshotsSucceed = false)

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(RaftSyncPullResult.Failed("snapshot_failed"), result)
        assertNull(cursors.read(syncTestScope))
    }

    @Test
    fun `hasMore pulls again at once with each page cursor`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true, changes = listOf(syncDelete(2, "machine", "m-1")))),
                syncPage(syncChangesBody("c1.3.1.k", hasMore = true, changes = listOf(syncDelete(3, "machine", "m-2")))),
                syncPage(syncChangesBody("c1.3.2")),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf<String?>("c1.1.1", "c1.2.1.k", "c1.3.1.k"), api.sinces)
        assertEquals(2, sink.plans.size)
        assertEquals("c1.3.2", cursors.read(syncTestScope))
        assertEquals(3, assertIs<RaftSyncPullResult.Synced>(result).pages)
    }

    @Test
    fun `a pull stops at the page cap and keeps its cursor for the next trigger`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true)),
                syncPage(syncChangesBody("c1.3.1.k", hasMore = true)),
            )

        val result = engine(api, RecordingSyncChangesSink(), maxPages = 2).pull(syncTestScope, trigger = "test")

        assertEquals(true, assertIs<RaftSyncPullResult.Synced>(result).truncated)
        assertEquals("c1.3.1.k", cursors.read(syncTestScope))
    }

    @Test
    fun `reset retakes the named snapshots and continues from the returned cursor`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.90.500", reset = """{"reason":"too_old","snapshots":["channels","agents"]}""")),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf(setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.Agents) to "reset_too_old"), sink.snapshotReloads)
        assertTrue(sink.plans.isEmpty())
        assertEquals("c1.90.500", cursors.read(syncTestScope))
        assertEquals("too_old", assertIs<RaftSyncPullResult.Synced>(result).resetReason)
    }

    @Test
    fun `a reset whose snapshots fail keeps the old cursor`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(syncPage(syncChangesBody("c1.90.500", reset = """{"reason":"forced","snapshots":["agents"]}""")))

        val result = engine(api, RecordingSyncChangesSink(snapshotsSucceed = false)).pull(syncTestScope, trigger = "test")

        assertEquals(RaftSyncPullResult.Failed("snapshot_failed"), result)
        assertEquals("c1.1.1", cursors.read(syncTestScope))
    }

    @Test
    fun `an invalid cursor is forgotten and the pull starts over as a first sync`() = runTest {
        cursors.write(syncTestScope, "c0.bogus")
        val api =
            ScriptedSyncChangesApi(
                RaftSyncChangesFetch.InvalidCursor,
                syncPage(syncChangesBody("c1.5.5")),
                syncPage(syncChangesBody("c1.5.6")),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf("c0.bogus", null, "c1.5.5"), api.sinces)
        assertEquals(listOf("snapshots:first_sync"), sink.log)
        assertEquals("c1.5.6", cursors.read(syncTestScope))
        assertEquals(true, assertIs<RaftSyncPullResult.Synced>(result).firstSync)
    }

    @Test
    fun `a failed page keeps the cursor of the last applied page`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true)),
                RaftSyncChangesFetch.Failed("http_503"),
            )

        val result = engine(api, RecordingSyncChangesSink()).pull(syncTestScope, trigger = "test")

        assertEquals(RaftSyncPullResult.Failed("http_503"), result)
        assertEquals("c1.2.1.k", cursors.read(syncTestScope))
    }

    @Test
    fun `a pull for a retired binding applies and stores nothing`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        var current = true
        val api =
            object : RaftSyncChangesApi {
                override suspend fun changes(serverId: build.raft.app.model.ServerId, since: String?, limit: Int): RaftSyncChangesFetch {
                    current = false // signed out while the request was in flight
                    return syncPage(syncChangesBody("c1.2.1", changes = listOf(syncDelete(2, "agent", "a-1"))))
                }
            }
        val sink = RecordingSyncChangesSink()
        val engine = RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink, isCurrent = { current })

        assertEquals(RaftSyncPullResult.Failed("scope_retired"), engine.pull(syncTestScope, trigger = "test"))
        assertTrue(sink.log.isEmpty())
        assertEquals("c1.1.1", cursors.read(syncTestScope))
    }

    @Test
    fun `a page coalesces to the newest change per entity and id`() {
        val plan =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.1",
                    changes =
                        listOf(
                            syncUpsert(1, "agent", "a-1", """{"id":"a-1","name":"old"}"""),
                            syncDelete(2, "agent", "a-2"),
                            syncUpsert(3, "agent", "a-1", """{"id":"a-1","name":"new"}"""),
                            syncUpsert(4, "agent", "a-2", """{"id":"a-2","name":"back"}"""),
                            syncDelete(5, "machine", "m-1"),
                            syncStale(6, "ch-1", 7),
                            syncStale(7, "ch-1", null),
                            syncStale(8, "ch-1", 5),
                            """{"seq":9,"entity":"sidebar_order","id":"server-1","op":"rebuilt"}""",
                        ),
                )
            )!!.changes.toSyncApplyPlan()

        assertEquals(listOf("a-1", "a-2"), plan.upsertsOf(RaftSyncRowEntity.Agent).keys.toList())
        assertEquals(JsonPrimitive("new"), plan.upsertsOf(RaftSyncRowEntity.Agent).getValue("a-1")["name"])
        assertTrue(plan.deletesOf(RaftSyncRowEntity.Agent).isEmpty())
        assertEquals(setOf("m-1"), plan.deletesOf(RaftSyncRowEntity.Machine))
        assertEquals(mapOf<String, Long?>("ch-1" to 7L), plan.staleConversations)
        assertEquals(setOf(RaftSyncSnapshot.SidebarOrder), plan.snapshots)
    }

    @Test
    fun `a removed scope drops the changes made under it before the removal`() {
        val plan =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.1",
                    changes =
                        listOf(
                            syncUpsert(1, "channel", "room-1", """{"id":"room-1"}"""),
                            syncUpsert(2, "channel_member", "room-1:user:u-2", """{"id":"u-2"}"""),
                            syncStale(3, "room-1", 4),
                            syncUpsert(4, "channel", "room-2", """{"id":"room-2"}"""),
                            syncScope(5, "channel", "room-1", "removed", syncChannelScopeSnapshots),
                            syncScope(6, "channel", "room-3", "added", syncChannelScopeSnapshots),
                        ),
                )
            )!!.changes.toSyncApplyPlan()

        assertEquals(setOf("room-2"), plan.upsertsOf(RaftSyncRowEntity.Channel).keys)
        assertTrue(plan.upsertsOf(RaftSyncRowEntity.ChannelMember).isEmpty())
        assertTrue(plan.staleConversations.isEmpty())
        assertEquals(listOf(RaftSyncChange.ScopeRemoved(5, RaftSyncScopeKind.Channel, "room-1")), plan.removedScopes)
        assertEquals(setOf("room-3"), plan.addedChannelIds)
        assertEquals(
            setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.ChannelMembers, RaftSyncSnapshot.Inbox, RaftSyncSnapshot.Unread),
            plan.snapshots,
        )

        val serverGone =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.1",
                    changes = listOf(syncDelete(1, "agent", "a-1"), syncScope(2, "server", "server-1", "removed", syncAllSnapshotNames)),
                )
            )!!.changes.toSyncApplyPlan()
        assertTrue(serverGone.deletes.isEmpty())
        assertEquals(RaftSyncScopeKind.Server, serverGone.removedScopes.single().kind)
    }
}
