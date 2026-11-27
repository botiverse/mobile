package build.raft.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inbox_row capability of `GET /api/sync/changes` (#proj-mobile:#167; botiverse/slock #15312, merged as
 * 2082d6ff45: `SyncInboxRowChange`, `inboxReset`, `retryAfterMs` in packages/shared/src/syncChanges.ts,
 * shapes as packages/server/src/routes/syncChanges.inboxRows.api.test.ts asserts them): parsing, the
 * page plan, the engine and the router. The Activity store's side is SharedActivityStoreSyncInboxRowsTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncChangesInboxRowsTest {
    // ---- contract -------------------------------------------------------------------------------

    @Test
    fun `inbox_row upserts and deletes parse with their row source position and reason`() {
        val row = syncInboxChannelRow("ch-1", seq = "92233720368547758070")
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.900.m77.901",
                    changes =
                        listOf(
                            syncStale(5, "ch-1", 12),
                            syncInboxRowUpsert(5, "ch-1", row, source = "log"),
                            syncInboxRowDelete(6, "ch-2", "41", reason = "done"),
                            syncInboxRowDelete(7, "ch-3", null, reason = "left"),
                            syncInboxRowUpsert(9, "th-1", syncInboxChannelRow("th-1", seq = "3"), source = "message"),
                        ),
                    inboxReset = """{"reason":"held_too_long","snapshots":["inbox"]}""",
                    retryAfterMs = 2000,
                )
            )!!
        assertEquals("c1.9.900.m77.901", page.cursor, "the cursor stays opaque")
        val upsert = assertIs<RaftSyncChange.InboxRowUpsert>(page.changes[1])
        assertEquals("ch-1", upsert.id)
        assertEquals(RaftSyncInboxRowSource.Log, upsert.source)
        assertEquals(JsonPrimitive("92233720368547758070"), upsert.row["latestActivitySeq"])
        assertEquals(RaftSyncChange.InboxRowDelete(6, "ch-2", "41", RaftSyncInboxRowDeleteReason.Done), page.changes[2])
        assertEquals(RaftSyncChange.InboxRowDelete(7, "ch-3", null, RaftSyncInboxRowDeleteReason.Left), page.changes[3])
        assertEquals(RaftSyncInboxRowSource.Message, assertIs<RaftSyncChange.InboxRowUpsert>(page.changes[4]).source)
        assertEquals(RaftSyncInboxReset("held_too_long"), page.inboxReset)
        assertEquals(2000L, page.retryAfterMillis)
        assertEquals(0, page.skippedUnknownEntities)
    }

    @Test
    fun `without the capability fields the page is as before`() {
        val page = parseSyncChangesPage(syncChangesBody("c1.1.1", changes = listOf(syncStale(1, "ch-1", null))))!!
        assertNull(page.inboxReset)
        assertNull(page.retryAfterMillis)
        val nullReset = parseSyncChangesPage(syncChangesBody("c1.1.1", inboxReset = "null"))!!
        assertNull(nullReset.inboxReset)
    }

    @Test
    fun `unknown inbox_row values are never dropped silently`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.2.1",
                    changes =
                        listOf(
                            // An unknown source still applies (it does not change how).
                            syncInboxRowUpsert(1, "ch-1", syncInboxChannelRow("ch-1", "4"), source = "backfill"),
                            // An unknown reason or op retakes the inbox snapshot.
                            syncInboxRowDelete(2, "ch-2", "4", reason = "archived"),
                            """{"seq":3,"entity":"inbox_row","id":"ch-3","op":"move"}""",
                        ),
                    inboxReset = """{"reason":"brand_new","snapshots":["inbox"]}""",
                )
            )!!
        assertEquals(RaftSyncInboxRowSource.Unknown, assertIs<RaftSyncChange.InboxRowUpsert>(page.changes[0]).source)
        assertEquals(RaftSyncChange.Resnapshot(2, "inbox_row", "delete", setOf(RaftSyncSnapshot.Inbox)), page.changes[1])
        assertEquals(RaftSyncChange.Resnapshot(3, "inbox_row", "move", setOf(RaftSyncSnapshot.Inbox)), page.changes[2])
        assertEquals(RaftSyncInboxReset("brand_new"), page.inboxReset, "an unknown reset reason still retakes the inbox")
    }

    @Test
    fun `malformed inbox_row changes are contract mismatches`() {
        listOf(
            """{"seq":1,"entity":"inbox_row","id":"ch-1","op":"upsert","source":"log"}""",
            """{"seq":1,"entity":"inbox_row","id":"ch-1","op":"delete","position":41,"reason":"done"}""",
        ).forEach { change -> assertNull(parseSyncChangesPage(syncChangesBody("c1.1.1", changes = listOf(change))), change) }
    }

    @Test
    fun `the capability query is sent only when asked for`() {
        assertNull(syncChangesCapabilitiesQuery(emptySet()))
        assertEquals("inbox_row", syncChangesCapabilitiesQuery(setOf(SYNC_CAPABILITY_INBOX_ROW)))
        assertEquals("a,inbox_row", syncChangesCapabilitiesQuery(setOf(" inbox_row ", "a", "")))
    }

    // ---- plan -----------------------------------------------------------------------------------

    @Test
    fun `the plan keeps inbox rows in seq order one per id and a removed server drops them`() {
        val changes =
            parseSyncChangesPage(
                syncChangesBody(
                    "c1.9.1",
                    changes =
                        listOf(
                            syncInboxRowUpsert(9, "ch-1", syncInboxChannelRow("ch-1", "9")),
                            syncInboxRowDelete(4, "ch-2", "3"),
                            syncInboxRowUpsert(7, "ch-3", syncInboxChannelRow("ch-3", "7")),
                            syncInboxRowDelete(10, "ch-1", "9"),
                        ),
                )
            )!!.changes
        val plan = changes.toSyncApplyPlan()
        assertEquals(listOf(4L to "ch-2", 7L to "ch-3", 10L to "ch-1"), plan.inboxRows.map { it.seq to it.id })
        assertTrue(!plan.isEmpty)
        val removed = (changes + RaftSyncChange.ScopeRemoved(11, RaftSyncScopeKind.Server, "server-1")).toSyncApplyPlan()
        assertTrue(removed.inboxRows.isEmpty())
    }

    // ---- engine ---------------------------------------------------------------------------------

    private val cursors = RaftSyncCursorStore(SyncTestPreferences())

    @Test
    fun `an inboxReset retakes the inbox after the page's changes and keeps the cursor going`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val sink = RecordingSyncChangesSink()
        val api =
            ScriptedSyncChangesApi(
                syncPage(
                    syncChangesBody(
                        "c1.5.1.m3.1",
                        changes = listOf(syncInboxRowUpsert(5, "ch-1", syncInboxChannelRow("ch-1", "5"))),
                        inboxReset = """{"reason":"init","snapshots":["inbox"]}""",
                    )
                ),
            )
        val result = RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink).pull(syncTestScope, "test")
        val synced = assertIs<RaftSyncPullResult.Synced>(result)
        assertEquals(listOf("apply", "inbox:inbox_reset_init"), sink.log)
        assertEquals(1, sink.plans.single().inboxRows.size, "the changes still apply")
        assertEquals("init", synced.inboxResetReason)
        assertNull(synced.inboxReloadAfterMillis)
        assertNull(synced.retryAfterMillis)
        assertEquals("c1.5.1.m3.1", cursors.read(syncTestScope))
    }

    @Test
    fun `retryAfterMs asks the caller to wait clamped and delays an inboxReset that came with it`() = runTest {
        for ((serverMillis, expected) in listOf(2_000L to 2_000L, 10L to 1_000L, 600_000L to 30_000L)) {
            cursors.write(syncTestScope, "c1.1.1")
            val sink = RecordingSyncChangesSink()
            val api =
                ScriptedSyncChangesApi(
                    syncPage(
                        syncChangesBody(
                            "c1.2.1.m3.1",
                            inboxReset = """{"reason":"held_too_long","snapshots":["inbox"]}""",
                            retryAfterMs = serverMillis,
                        )
                    ),
                )
            val synced = assertIs<RaftSyncPullResult.Synced>(RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink).pull(syncTestScope, "test"))
            assertEquals(expected, synced.retryAfterMillis, "retryAfterMs=$serverMillis")
            assertEquals(expected, synced.inboxReloadAfterMillis, "retryAfterMs=$serverMillis")
            assertTrue(sink.log.none { it.startsWith("inbox:") }, "the snapshot waits: ${sink.log}")
        }
    }

    @Test
    fun `a full reset ignores the inbox fields and hasMore keeps pulling`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val sink = RecordingSyncChangesSink()
        val api =
            ScriptedSyncChangesApi(
                syncPage(
                    syncChangesBody(
                        "c1.2.1.m2.1.k",
                        hasMore = true,
                        changes = listOf(syncInboxRowUpsert(2, "ch-1", syncInboxChannelRow("ch-1", "2"))),
                        retryAfterMs = 2000,
                    )
                ),
                syncPage(syncChangesBody("c1.9.1.m9.1", reset = """{"reason":"too_old","snapshots":["inbox"]}""", inboxReset = """{"reason":"init","snapshots":["inbox"]}""")),
            )
        val synced = assertIs<RaftSyncPullResult.Synced>(RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink).pull(syncTestScope, "test"))
        assertEquals(listOf("apply", "snapshots:reset_too_old"), sink.log)
        assertNull(synced.retryAfterMillis, "only the last page's wait counts")
        assertNull(synced.inboxResetReason)
        assertEquals(listOf<String?>("c1.1.1", "c1.2.1.m2.1.k"), api.sinces)
    }

    // ---- router ---------------------------------------------------------------------------------

    private class InboxTargets(var outcome: (List<RaftSyncInboxRowChange>) -> RaftSyncInboxRowsOutcome) : RaftSyncStoreTargets {
        val calls = mutableListOf<String>()
        val applied = mutableListOf<List<RaftSyncInboxRowChange>>()

        override fun rosterServer(serverId: String): build.raft.app.model.ServerSummary? = null

        override suspend fun supersedeRosterLoad(serverId: String, reason: String) = Unit

        override fun machineNames(serverId: String): Map<String, String> = emptyMap()

        override suspend fun applyRosterRows(serverId: String, upserts: List<build.raft.app.model.MemberProfile>, deletes: List<Pair<build.raft.app.model.MemberId, build.raft.app.model.MemberKind>>) = Unit

        override suspend fun applyMachineRows(serverId: String, upserts: List<ComputerSummary>, deletes: Set<String>) = Unit

        override fun refreshHome(serverId: String, reason: String) {
            calls += "home"
        }

        override fun refreshActivity(serverId: String, reason: String) {
            calls += "activity:$reason"
        }

        override fun applyInboxRows(serverId: String, changes: List<RaftSyncInboxRowChange>): RaftSyncInboxRowsOutcome {
            calls += "inbox_rows"
            applied += changes
            return outcome(changes)
        }

        override fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome {
            calls += "remote_done:$channelId"
            return ActivityRemoteDoneOutcome.Removed
        }

        override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String) =
            RaftSyncChannelMembersRefreshResult.AllSucceeded

        override suspend fun reloadRoster(serverId: String, reason: String) = RaftSyncSnapshotRead.Taken

        override suspend fun reloadMachines(serverId: String, reason: String) = RaftSyncSnapshotRead.Taken

        override suspend fun applyMessageRows(serverId: String, messages: List<build.raft.app.model.Message>) = Unit
    }

    private suspend fun RaftSyncChangesStoreRouter.applyPage(vararg changes: String) =
        apply(syncTestScope, parseSyncChangesPage(syncChangesBody("c1.9.1", changes = changes.toList()))!!.changes.toSyncApplyPlan())

    @Test
    fun `rows no Activity store could take leave every conversation change as before`() = runTest {
        val targets = InboxTargets { RaftSyncInboxRowsOutcome.NotApplied }
        val router = RaftSyncChangesStoreRouter(targets)
        router.applyPage(syncStaleWithState(5, "ch-1", done = """{"done":true,"doneFrontierSeq":"5"}"""), syncInboxRowDelete(5, "ch-1", "5"))
        assertEquals(listOf("inbox_rows", "remote_done:ch-1"), targets.calls)
        targets.calls.clear()
        router.applyPage(syncStale(6, "ch-1", null), syncInboxRowUpsert(6, "ch-1", syncInboxChannelRow("ch-1", "6")))
        assertEquals(listOf("inbox_rows", "home", "activity:sync_changes"), targets.calls)
    }

    // ---- retry after ----------------------------------------------------------------------------

    private fun synced(retryAfter: Long? = null, inboxReloadAfter: Long? = null) =
        RaftSyncPullResult.Synced(1, 0, false, null, truncated = false, retryAfterMillis = retryAfter, inboxReloadAfterMillis = inboxReloadAfter)

    @Test
    fun `a held row pulls again after the wait unless another pull came first and only in the foreground`() = runTest {
        var foreground = true
        val retry = RaftSyncRetryAfter(backgroundScope, isForeground = { foreground })
        val runs = mutableListOf<String>()
        retry.onPullFinished(syncTestScope, synced(retryAfter = 2_000L), repull = { runs += "pull" }, reloadInbox = { runs += "inbox" })
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(emptyList<String>(), runs)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("pull"), runs)

        // Another pull first: the waiting one goes.
        retry.onPullFinished(syncTestScope, synced(retryAfter = 2_000L), repull = { runs += "pull2" }, reloadInbox = {})
        retry.onTriggerPull(syncTestScope)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(listOf("pull"), runs)

        // In the background it does not pull (the return to the foreground does).
        foreground = false
        retry.onPullFinished(syncTestScope, synced(retryAfter = 1_000L), repull = { runs += "pull3" }, reloadInbox = {})
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("pull"), runs)
        assertTrue(!retry.isRepullPending(syncTestScope))
    }
}
