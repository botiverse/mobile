package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.ServerSummary
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/api/sync/changes` incremental `task` entities and `tasks` snapshot handling matching
 * botiverse/slock #14975 (`eeb984b99`): wire parsing, coalescing, `SharedTasksDataStore`
 * application (upsert, move, delete, channel resnapshot, `tasks` reset), malformed-entry
 * resilience, and server-switch isolation.
 */
class SyncChangesTasksTest {
    private fun wireTask(
        id: String,
        channelId: String,
        title: String = "Task $id",
        status: String = "todo",
        revision: Long? = 1L,
        channelName: String? = null,
    ): TaskSummary =
        requireNotNull(
            TaskWireDto(
                id = id,
                revision = revision,
                channelId = channelId,
                channelName = channelName,
                title = title,
                status = status,
            ).toTaskSummary()
        )

    private class StoreBackedTaskTargets(
        private val tasksStore: SharedTasksDataStore,
        var activeServerIds: Set<String> = setOf("server-1", "server-2"),
        var fetchServerPage: suspend (String) -> DataResult<TaskSnapshot> = { DataResult.Empty(EmptyReason.NoTasks) },
        var fetchChannelTasks: suspend (String, ChannelId) -> DataResult<TaskSnapshot> = { _, _ -> DataResult.Empty(EmptyReason.NoTasks) },
    ) : RaftSyncStoreTargets {
        val calls = mutableListOf<String>()
        val serverRefreshes = mutableListOf<String>()
        val channelRefreshes = mutableListOf<Pair<String, String>>()

        override fun rosterServer(serverId: String): ServerSummary? = null

        override suspend fun supersedeRosterLoad(serverId: String, reason: String) = Unit

        override fun machineNames(serverId: String): Map<String, String> = emptyMap()

        override suspend fun applyRosterRows(
            serverId: String,
            upserts: List<MemberProfile>,
            deletes: List<Pair<MemberId, MemberKind>>,
        ) {
            calls += "roster_rows:$serverId"
        }

        override suspend fun applyMachineRows(serverId: String, upserts: List<ComputerSummary>, deletes: Set<String>) {
            calls += "machine_rows:$serverId"
        }

        override fun refreshHome(serverId: String, reason: String) {
            calls += "home:$serverId"
        }

        override fun refreshActivity(serverId: String, reason: String) {
            calls += "activity:$serverId"
        }

        override fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?): ActivityRemoteDoneOutcome =
            ActivityRemoteDoneOutcome.CannotTell

        override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String): Boolean {
            calls += "channel_members:$serverId"
            return true
        }

        override suspend fun reloadRoster(serverId: String, reason: String): RaftSyncSnapshotRead {
            calls += "roster_reload:$serverId"
            return RaftSyncSnapshotRead.Taken
        }

        override suspend fun reloadMachines(serverId: String, reason: String): RaftSyncSnapshotRead {
            calls += "machines_reload:$serverId"
            return RaftSyncSnapshotRead.Taken
        }

        override suspend fun applyMessageRows(serverId: String, messages: List<Message>) {
            calls += "message_rows:$serverId"
        }

        override suspend fun applyTaskChanges(serverId: String, mutations: List<SharedSyncedTaskMutation>): Set<String> {
            calls += "task_changes:$serverId:${mutations.size}"
            if (serverId !in activeServerIds) return emptySet()
            return tasksStore.applySyncedTaskChangesForCurrentOwner(serverId, mutations).conflictedChannelIds
        }

        override suspend fun dropChannelTasks(serverId: String, channelIds: Set<String>) {
            calls += "drop_channel_tasks:$serverId:${channelIds.sorted().joinToString(",")}"
            channelIds.forEach { channelId -> tasksStore.dropChannelTasksForSync(serverId, channelId) }
        }

        override suspend fun refreshTasks(serverId: String, channelIds: Set<String>?, reason: String): Boolean {
            val scopeLabel = channelIds?.sorted()?.joinToString(",") ?: "all"
            calls += "refresh_tasks:$serverId:$scopeLabel:$reason"
            return tasksStore.refreshForSync(
                serverId = serverId,
                channelIds = channelIds,
                isServerActive = { serverId in activeServerIds },
                fetchServerPage = {
                    serverRefreshes += serverId
                    fetchServerPage(serverId)
                },
                fetchChannelTasks = { channelId ->
                    channelRefreshes += serverId to channelId.value
                    fetchChannelTasks(serverId, channelId)
                },
            )
        }
    }

    // ---- wire parsing ---------------------------------------------------------------------------

    @Test
    fun `task upsert delete and resnapshot parse with their wire fields`() {
        val logs = mutableListOf<String>()
        val page =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.20.1",
                        changes =
                            listOf(
                                syncTaskUpsert(
                                    seq = 10,
                                    id = "t-1",
                                    channelId = "ch-1",
                                    title = "Ship sync tasks",
                                    status = "in_progress",
                                    revision = 4L,
                                    extraFields =
                                        """"conversationAnchored":true,"conversationFollowed":true,"futureField":"ignored"""",
                                ),
                                syncTaskDelete(seq = 11, id = "t-2", channelId = "ch-1"),
                                syncTaskResnapshot(seq = 12, channelId = "ch-2"),
                            ),
                    ),
                    diagnostics = logs::add,
                )
            )

        assertEquals(0, page.skippedUnknownEntities)
        assertTrue(page.ignoredEntities.isEmpty())
        assertTrue(logs.isEmpty())

        val upsert = assertIs<RaftSyncChange.TaskUpsert>(page.changes[0])
        assertEquals(10L, upsert.seq)
        assertEquals("t-1", upsert.id)
        assertEquals(JsonPrimitive("ch-1"), upsert.row["channelId"])
        val parsed = requireNotNull(parseSharedTaskRow(upsert.row))
        assertEquals(TaskId("t-1"), parsed.id)
        assertEquals(ChannelId("ch-1"), parsed.channelId)
        assertEquals("Ship sync tasks", parsed.title)
        assertEquals(TaskStatus.InProgress, parsed.status)
        assertEquals(4L, parsed.revision)
        assertEquals(true, parsed.conversationAnchored)
        assertEquals(true, parsed.conversationFollowed)

        assertEquals(RaftSyncChange.TaskDelete(seq = 11, id = "t-2", channelId = "ch-1"), page.changes[1])
        assertEquals(RaftSyncChange.TaskResnapshot(seq = 12, channelId = "ch-2"), page.changes[2])
    }

    @Test
    fun `reset naming tasks parses to tasks snapshot and unknown snapshot falls back to all including tasks`() {
        val tasksOnly =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.50.1",
                        reset = """{"reason":"forced","snapshots":["tasks"]}""",
                    )
                )
            )
        val tasksOnlyReset = requireNotNull(tasksOnly.reset)
        assertEquals(RaftSyncReset("forced", setOf(RaftSyncSnapshot.Tasks)), tasksOnlyReset)

        val unknownSnapshot =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.51.1",
                        reset = """{"reason":"too_old","snapshots":["channels","future_entity"]}""",
                    )
                )
            )
        val unknownReset = requireNotNull(unknownSnapshot.reset)
        assertEquals(RaftSyncSnapshot.All, unknownReset.snapshots)
        assertTrue(RaftSyncSnapshot.Tasks in unknownReset.snapshots)

        // Older server without "tasks" in its reset list still parses cleanly without adding Tasks.
        val legacyNames = syncAllSnapshotNames.filterNot { it == "tasks" }.joinToString(",") { "\"$it\"" }
        val legacy =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.52.1",
                        reset = """{"reason":"too_old","snapshots":[$legacyNames]}""",
                    )
                )
            )
        val legacyReset = requireNotNull(legacy.reset)
        assertEquals(RaftSyncSnapshot.All - RaftSyncSnapshot.Tasks, legacyReset.snapshots)
    }

    @Test
    fun `malformed task entries fail closed without discarding valid changes in the same batch`() {
        val logs = mutableListOf<String>()
        val page =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.30.1",
                        changes =
                            listOf(
                                // Missing row on upsert
                                """{"seq":1,"entity":"task","id":"t-bad-1","op":"upsert"}""",
                                // Non-object row on upsert
                                """{"seq":2,"entity":"task","id":"t-bad-2","op":"upsert","row":"oops"}""",
                                // Mismatched row.id with valid channelId -> channel resnapshot
                                """{"seq":3,"entity":"task","id":"t-bad-3","op":"upsert","row":{"id":"t-other","channelId":"ch-mismatch","status":"todo"}}""",
                                // Missing channelId on delete
                                """{"seq":4,"entity":"task","id":"t-bad-4","op":"delete"}""",
                                // Non-string channelId on delete
                                """{"seq":5,"entity":"task","id":"t-bad-5","op":"delete","channelId":123}""",
                                // Unknown op on task
                                """{"seq":6,"entity":"task","id":"t-bad-6","op":"archived"}""",
                                // Missing id on task
                                """{"seq":7,"entity":"task","op":"resnapshot"}""",
                                // Valid changes in the exact same batch MUST survive!
                                syncTaskUpsert(seq = 8, id = "t-good", channelId = "ch-good", title = "Valid task", status = "todo"),
                                syncDelete(seq = 9, entity = "agent", id = "a-1"),
                            ),
                    ),
                    diagnostics = logs::add,
                )
            )

        assertEquals(9, page.changes.size)
        assertEquals(RaftSyncChange.Resnapshot(1, "task", "upsert", setOf(RaftSyncSnapshot.Tasks)), page.changes[0])
        assertEquals(RaftSyncChange.Resnapshot(2, "task", "upsert", setOf(RaftSyncSnapshot.Tasks)), page.changes[1])
        assertEquals(RaftSyncChange.TaskResnapshot(3, "ch-mismatch"), page.changes[2])
        assertEquals(RaftSyncChange.Resnapshot(4, "task", "delete", setOf(RaftSyncSnapshot.Tasks)), page.changes[3])
        assertEquals(RaftSyncChange.Resnapshot(5, "task", "delete", setOf(RaftSyncSnapshot.Tasks)), page.changes[4])
        assertEquals(RaftSyncChange.Resnapshot(6, "task", "archived", setOf(RaftSyncSnapshot.Tasks)), page.changes[5])
        assertEquals(RaftSyncChange.Resnapshot(7, "task", "resnapshot", setOf(RaftSyncSnapshot.Tasks)), page.changes[6])
        assertIs<RaftSyncChange.TaskUpsert>(page.changes[7])
        assertEquals(RaftSyncChange.Delete(9, RaftSyncRowEntity.Agent, "a-1"), page.changes[8])
        assertEquals(7, logs.size)
    }

    @Test
    fun `older server minimal task row omits optional fields and decodes cleanly`() {
        val page =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.1.1",
                        changes =
                            listOf(
                                syncUpsert(1, "task", "t-legacy", """{"id":"t-legacy","channelId":"ch-1","status":"todo","title":"Legacy task"}"""),
                            ),
                    )
                )
            )
        val upsert = assertIs<RaftSyncChange.TaskUpsert>(page.changes.single())
        val parsed = requireNotNull(parseSharedTaskRow(upsert.row))
        assertEquals(TaskId("t-legacy"), parsed.id)
        assertEquals(ChannelId("ch-1"), parsed.channelId)
        assertEquals(TaskStatus.Todo, parsed.status)
        assertNull(parsed.revision)
        assertNull(parsed.conversationFollowed)
        assertFalse(parsed.conversationAnchored)
        assertFalse(parsed.sourceMessageReported)
    }

    // ---- plan coalescing ------------------------------------------------------------------------

    @Test
    fun `apply plan preserves both legs of a task move and drops tasks under a removed channel or server`() {
        val changes =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.40.1",
                        changes =
                            listOf(
                                // Move t-move from ch-1 to ch-2: both delete(ch-1) and upsert(ch-2) stay.
                                syncTaskDelete(seq = 1, id = "t-move", channelId = "ch-1"),
                                syncTaskUpsert(seq = 2, id = "t-move", channelId = "ch-2", title = "Moved"),
                                // Same channel upsert then delete -> coalesces to delete.
                                syncTaskUpsert(seq = 3, id = "t-gone", channelId = "ch-2", title = "Temp"),
                                syncTaskDelete(seq = 4, id = "t-gone", channelId = "ch-2"),
                                // Channel resnapshot supersedes earlier row changes for ch-3 only.
                                syncTaskUpsert(seq = 5, id = "t-bulk", channelId = "ch-3", title = "Old"),
                                syncTaskResnapshot(seq = 6, channelId = "ch-3"),
                                // Removed channel drops prior task changes and resnapshot for ch-drop only.
                                syncTaskUpsert(seq = 7, id = "t-drop", channelId = "ch-drop", title = "Dropped"),
                                syncTaskResnapshot(seq = 8, channelId = "ch-drop"),
                                syncScope(seq = 9, kind = "channel", id = "ch-drop", op = "removed", snapshots = syncChannelScopeSnapshots),
                            ),
                    )
                )
            ).changes

        val plan = changes.toSyncApplyPlan()
        assertEquals(
            listOf(
                Triple(1L, "t-move", "delete"),
                Triple(2L, "t-move", "upsert"),
                Triple(4L, "t-gone", "delete"),
            ),
            plan.taskChanges.map { change ->
                when (change) {
                    is RaftSyncChange.TaskUpsert -> Triple(change.seq, change.id, "upsert")
                    is RaftSyncChange.TaskDelete -> Triple(change.seq, change.id, "delete")
                    is RaftSyncChange.TaskResnapshot -> Triple(change.seq, change.channelId, "resnapshot")
                }
            },
        )
        assertEquals(setOf("ch-3"), plan.taskResnapshotChannelIds)

        val serverRemovedPlan =
            (changes + RaftSyncChange.ScopeRemoved(10, RaftSyncScopeKind.Server, "server-1")).toSyncApplyPlan()
        assertTrue(serverRemovedPlan.taskChanges.isEmpty())
        assertTrue(serverRemovedPlan.taskResnapshotChannelIds.isEmpty())
    }

    // ---- SharedTasksDataStore + router + engine integration -------------------------------------

    @Test
    fun `task upsert creates and updates tasks and delete removes without dropping unrelated channels`() = runTest {
        val store = SharedTasksDataStore(scope = backgroundScope)
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-2")),
            snapshot = TaskSnapshot(listOf(wireTask("t-keep", "ch-2", title = "Keep me", status = "todo", revision = 1L)), emptyMap()),
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-1")),
            snapshot = TaskSnapshot(listOf(wireTask("t-1", "ch-1", title = "Initial", status = "todo", revision = 1L)), emptyMap()),
        )

        val targets = StoreBackedTaskTargets(store)
        val router = RaftSyncChangesStoreRouter(targets)

        // Upsert updates t-1 and creates t-new in ch-1; ch-2 stays intact.
        val page1 =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.2.1",
                        changes =
                            listOf(
                                syncTaskUpsert(seq = 1, id = "t-1", channelId = "ch-1", title = "Updated", status = "in_progress", revision = 2L),
                                syncTaskUpsert(seq = 2, id = "t-new", channelId = "ch-1", title = "Created", status = "todo", revision = 1L),
                            ),
                    )
                )
            )
        router.apply(syncTestScope, page1.changes.toSyncApplyPlan())

        val ch1AfterUpsert = store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1")))
        assertEquals(SharedTasksDataStoreStatus.Loaded, ch1AfterUpsert.status)
        assertTrue(ch1AfterUpsert.isExactScopeLoaded)
        assertEquals(
            listOf(Triple("t-1", "Updated", TaskStatus.InProgress), Triple("t-new", "Created", TaskStatus.Todo)),
            ch1AfterUpsert.tasks.map { Triple(it.id.value, it.title, it.status) },
        )
        assertEquals(listOf("t-keep"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-2"))).tasks.map { it.id.value })

        // Delete removes t-1 from ch-1 while keeping t-new in ch-1 and t-keep in ch-2.
        val page2 =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.3.1",
                        changes = listOf(syncTaskDelete(seq = 3, id = "t-1", channelId = "ch-1")),
                    )
                )
            )
        router.apply(syncTestScope, page2.changes.toSyncApplyPlan())

        assertEquals(listOf("t-new"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks.map { it.id.value })
        assertEquals(listOf("t-keep"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-2"))).tasks.map { it.id.value })
        assertEquals(setOf("t-new", "t-keep"), store.projection("server-1", SharedTasksScope.Server).tasks.mapTo(mutableSetOf()) { it.id.value })
    }

    @Test
    fun `task move removes from old channel and upserts into new channel regardless of arrival order`() = runTest {
        // Order A: delete(ch-1) then upsert(ch-2) in one batch.
        val storeA = SharedTasksDataStore(scope = backgroundScope)
        storeA.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-1")),
            snapshot = TaskSnapshot(listOf(wireTask("t-move", "ch-1", title = "Move A", status = "todo", revision = 1L)), emptyMap()),
        )
        storeA.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-2")),
            snapshot = TaskSnapshot(emptyList(), emptyMap()),
        )
        val routerA = RaftSyncChangesStoreRouter(StoreBackedTaskTargets(storeA))
        val moveA =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.5.1",
                        changes =
                            listOf(
                                syncTaskDelete(seq = 1, id = "t-move", channelId = "ch-1"),
                                syncTaskUpsert(seq = 2, id = "t-move", channelId = "ch-2", title = "Move A", status = "in_progress", revision = 2L),
                            ),
                    )
                )
            )
        routerA.apply(syncTestScope, moveA.changes.toSyncApplyPlan())

        assertEquals(emptyList(), storeA.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks)
        val movedInCh2A = storeA.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-2"))).tasks.single()
        assertEquals(TaskId("t-move"), movedInCh2A.id)
        assertEquals(ChannelId("ch-2"), movedInCh2A.channelId)
        assertEquals(TaskStatus.InProgress, movedInCh2A.status)
        assertEquals(ChannelId("ch-2"), storeA.projection("server-1", SharedTasksScope.Server).tasks.single().channelId)

        // Order B: upsert(ch-2) arrives before delete(ch-1) (e.g. realtime raced sync delete).
        val storeB = SharedTasksDataStore(scope = backgroundScope)
        storeB.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-1")),
            snapshot = TaskSnapshot(listOf(wireTask("t-move", "ch-1", title = "Move B", status = "todo", revision = 1L)), emptyMap()),
        )
        storeB.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-2")),
            snapshot = TaskSnapshot(emptyList(), emptyMap()),
        )
        val routerB = RaftSyncChangesStoreRouter(StoreBackedTaskTargets(storeB))
        val moveB =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.6.1",
                        changes =
                            listOf(
                                syncTaskUpsert(seq = 1, id = "t-move", channelId = "ch-2", title = "Move B", status = "in_progress", revision = 2L),
                                syncTaskDelete(seq = 2, id = "t-move", channelId = "ch-1"),
                            ),
                    )
                )
            )
        routerB.apply(syncTestScope, moveB.changes.toSyncApplyPlan())

        assertEquals(emptyList(), storeB.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks)
        val movedInCh2B = storeB.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-2"))).tasks.single()
        assertEquals(TaskId("t-move"), movedInCh2B.id)
        assertEquals(ChannelId("ch-2"), movedInCh2B.channelId)
        assertEquals(ChannelId("ch-2"), storeB.projection("server-1", SharedTasksScope.Server).tasks.single().channelId)
    }

    @Test
    fun `task resnapshot refreshes only the named channel and malformed task row triggers fallback without dropping valid tasks`() = runTest {
        val store = SharedTasksDataStore(scope = backgroundScope)
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-1")),
            snapshot =
                TaskSnapshot(
                    listOf(
                        wireTask("t-stale-1", "ch-1", title = "Cleaned", status = "done", revision = 1L),
                        wireTask("t-stale-2", "ch-1", title = "Kept", status = "todo", revision = 1L),
                    ),
                    emptyMap(),
                ),
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-2")),
            snapshot = TaskSnapshot(listOf(wireTask("t-other", "ch-2", title = "Other", status = "todo", revision = 1L)), emptyMap()),
        )

        val targets =
            StoreBackedTaskTargets(
                tasksStore = store,
                fetchChannelTasks = { _, channelId ->
                    when (channelId.value) {
                        "ch-1" ->
                            DataResult.Content(
                                TaskSnapshot(
                                    listOf(wireTask("t-stale-2", "ch-1", title = "Kept", status = "todo", revision = 2L)),
                                    emptyMap(),
                                )
                            )
                        "ch-2" ->
                            DataResult.Content(
                                TaskSnapshot(
                                    listOf(
                                        wireTask("t-other", "ch-2", title = "Other", status = "todo", revision = 1L),
                                        wireTask("t-recovered", "ch-2", title = "Recovered", status = "in_progress", revision = 1L),
                                    ),
                                    emptyMap(),
                                )
                            )
                        else -> DataResult.Empty(EmptyReason.NoTasks)
                    }
                },
            )
        val router = RaftSyncChangesStoreRouter(targets)

        // Bulk cleanup resnapshot on ch-1 refreshes ch-1 only; ch-unopened is skipped and ch-2 stays untouched.
        val resnapshotPage =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.10.1",
                        changes =
                            listOf(
                                syncTaskResnapshot(seq = 10, channelId = "ch-1"),
                                syncTaskResnapshot(seq = 11, channelId = "ch-unopened"),
                            ),
                    )
                )
            )
        router.apply(syncTestScope, resnapshotPage.changes.toSyncApplyPlan())

        assertEquals(listOf("server-1" to "ch-1"), targets.channelRefreshes)
        assertTrue(targets.serverRefreshes.isEmpty())
        assertEquals(listOf("t-stale-2"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks.map { it.id.value })
        assertEquals(listOf("t-other"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-2"))).tasks.map { it.id.value })

        // A task row with blank status in ch-2 is rejected by strict parsing and falls back to
        // ch-2 resnapshot while the valid ch-1 upsert in the same batch still applies!
        targets.channelRefreshes.clear()
        val mixedPage =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.11.1",
                        changes =
                            listOf(
                                syncUpsert(11, "task", "t-bad-status", """{"id":"t-bad-status","channelId":"ch-2","status":""}"""),
                                syncTaskUpsert(12, "t-good", "ch-1", title = "Applied cleanly", status = "in_progress", revision = 1L),
                            ),
                    )
                )
            )
        router.apply(syncTestScope, mixedPage.changes.toSyncApplyPlan())

        assertEquals(listOf("server-1" to "ch-2"), targets.channelRefreshes)
        assertEquals(
            setOf("t-stale-2", "t-good"),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks.mapTo(mutableSetOf()) { it.id.value },
        )
        assertEquals(
            setOf("t-other", "t-recovered"),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-2"))).tasks.mapTo(mutableSetOf()) { it.id.value },
        )
    }

    @Test
    fun `reset with tasks snapshot refreshes held scopes and skips network when no tasks are loaded`() = runTest {
        val store = SharedTasksDataStore(scope = backgroundScope)
        val targets =
            StoreBackedTaskTargets(
                tasksStore = store,
                fetchServerPage = {
                    DataResult.Content(
                        TaskSnapshot(
                            listOf(wireTask("t-server-fresh", "ch-1", title = "Server fresh", status = "in_progress", revision = 3L)),
                            emptyMap(),
                        )
                    )
                },
                fetchChannelTasks = { _, channelId ->
                    DataResult.Content(
                        TaskSnapshot(
                            listOf(wireTask("t-ch-fresh", channelId.value, title = "Channel fresh", status = "done", revision = 4L)),
                            emptyMap(),
                        )
                    )
                },
            )
        val ledger = RaftSyncReloadLedger()
        val router = RaftSyncChangesStoreRouter(targets, ledger)
        val cursors = RaftSyncCursorStore(SyncTestPreferences())
        cursors.write(syncTestScope, "c1.1.1")

        // No held scopes yet -> reset(["tasks"]) succeeds with zero task fetches and does not touch other stores.
        val engine1 =
            RaftSyncChangesEngine(
                api =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.2.1", reset = """{"reason":"forced","snapshots":["tasks"]}""")),
                    ),
                cursors = cursors,
                sink = router,
            )
        val synced1 = assertIs<RaftSyncPullResult.Synced>(engine1.pull(syncTestScope, trigger = "test"))
        assertEquals("forced", synced1.resetReason)
        assertTrue(targets.serverRefreshes.isEmpty())
        assertTrue(targets.channelRefreshes.isEmpty())
        assertFalse(targets.calls.any { it.startsWith("home:") || it.startsWith("activity:") || it.startsWith("roster_") })

        // Now populate both server and ch-1 snapshots -> reset(["tasks"]) refreshes Server first, then ch-1.
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(listOf(wireTask("t-old", "ch-1", revision = 1L)), emptyMap()),
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-1")),
            snapshot = TaskSnapshot(listOf(wireTask("t-old", "ch-1", revision = 1L)), emptyMap()),
        )
        targets.calls.clear()

        val engine2 =
            RaftSyncChangesEngine(
                api =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.3.1", reset = """{"reason":"too_old","snapshots":["tasks"]}""")),
                    ),
                cursors = cursors,
                sink = router,
            )
        val synced2 = assertIs<RaftSyncPullResult.Synced>(engine2.pull(syncTestScope, trigger = "test"))
        assertEquals("too_old", synced2.resetReason)
        assertEquals(listOf("server-1"), targets.serverRefreshes)
        assertEquals(listOf("server-1" to "ch-1"), targets.channelRefreshes)
        assertEquals(listOf("t-ch-fresh"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks.map { it.id.value })
        assertTrue(store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).isExactScopeLoaded)
        assertEquals("c1.3.1", cursors.read(syncTestScope))
    }

    @Test
    fun `task resnapshot failure is retaken on next pull while 404 drops the inaccessible channel`() = runTest {
        val store = SharedTasksDataStore(scope = backgroundScope)
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-retry")),
            snapshot = TaskSnapshot(listOf(wireTask("t-1", "ch-retry", revision = 1L)), emptyMap()),
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-gone")),
            snapshot = TaskSnapshot(listOf(wireTask("t-gone", "ch-gone", revision = 1L)), emptyMap()),
        )

        var retryChannelAttempts = 0
        val targets =
            StoreBackedTaskTargets(
                tasksStore = store,
                fetchChannelTasks = { _, channelId ->
                    when (channelId.value) {
                        "ch-gone" -> DataResult.Error(ScreenError(code = "tasks_404", retryable = false))
                        "ch-retry" -> {
                            retryChannelAttempts += 1
                            if (retryChannelAttempts == 1) {
                                DataResult.Error(ScreenError(code = "tasks_503", retryable = true))
                            } else {
                                DataResult.Content(
                                    TaskSnapshot(
                                        listOf(wireTask("t-1", "ch-retry", title = "Retaken", status = "done", revision = 2L)),
                                        emptyMap(),
                                    )
                                )
                            }
                        }
                        else -> DataResult.Empty(EmptyReason.NoTasks)
                    }
                },
            )
        val ledger = RaftSyncReloadLedger()
        val router = RaftSyncChangesStoreRouter(targets, ledger)
        val cursors = RaftSyncCursorStore(SyncTestPreferences())
        cursors.write(syncTestScope, "c1.1.1")

        // Pull 1: ch-gone returns 404 (dropped cleanly), ch-retry returns 503 (recorded in reload ledger).
        val engine =
            RaftSyncChangesEngine(
                api =
                    ScriptedSyncChangesApi(
                        syncPage(
                            syncChangesBody(
                                "c1.2.1",
                                changes =
                                    listOf(
                                        syncTaskResnapshot(seq = 1, channelId = "ch-gone"),
                                        syncTaskResnapshot(seq = 2, channelId = "ch-retry"),
                                    ),
                            )
                        ),
                        syncPage(syncChangesBody("c1.3.1")),
                    ),
                cursors = cursors,
                sink = router,
            )
        assertIs<RaftSyncPullResult.Synced>(engine.pull(syncTestScope, trigger = "test"))
        val chGoneAfterDrop = store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-gone")))
        assertFalse(chGoneAfterDrop.isExactScopeLoaded)
        assertEquals(emptyList(), chGoneAfterDrop.tasks)
        assertFalse("ch-gone" in store.heldScopesForServer("server-1").channelIds)
        assertEquals("t-1", store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-retry"))).tasks.single().id.value)

        // Pull 2 (even with empty changes): retakes failed Tasks snapshot and updates ch-retry only.
        assertIs<RaftSyncPullResult.Synced>(engine.pull(syncTestScope, trigger = "foreground"))
        assertTrue(targets.serverRefreshes.isEmpty())
        val retaken = store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-retry"))).tasks.single()
        assertEquals("Retaken", retaken.title)
        assertEquals(TaskStatus.Done, retaken.status)
    }

    @Test
    fun `server switch isolates synced task upserts deletes resnapshots and resets across servers`() = runTest {
        val store = SharedTasksDataStore(scope = backgroundScope)
        // Same task id across two servers (e.g. joint channel local projections) plus distinct tasks.
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-s1")),
            snapshot =
                TaskSnapshot(
                    listOf(
                        wireTask("t-joint", "ch-s1", title = "Joint", status = "todo", revision = 2L),
                        wireTask("t-s1", "ch-s1", title = "S1 only", status = "todo", revision = 1L),
                    ),
                    emptyMap(),
                ),
        )
        store.publishSnapshot(
            serverId = "server-2",
            scope = SharedTasksScope.Channel(ChannelId("ch-s2")),
            snapshot =
                TaskSnapshot(
                    listOf(
                        wireTask("t-joint", "ch-s2", title = "Joint", status = "todo", revision = 2L),
                        wireTask("t-s2", "ch-s2", title = "S2 only", status = "todo", revision = 1L),
                    ),
                    emptyMap(),
                ),
        )

        val scopeS1 = RaftSyncScope("https://api.example.test", "user-1", "server-1")
        val scopeS2 = RaftSyncScope("https://api.example.test", "user-1", "server-2")
        val targets =
            StoreBackedTaskTargets(
                tasksStore = store,
                activeServerIds = setOf("server-1"),
                fetchChannelTasks = { serverId, channelId ->
                    DataResult.Content(
                        TaskSnapshot(
                            listOf(wireTask("t-fresh-$serverId", channelId.value, title = "Fresh $serverId", status = "done", revision = 5L)),
                            emptyMap(),
                        )
                    )
                },
            )
        val router = RaftSyncChangesStoreRouter(targets)

        // Delete t-joint and upsert t-new on server-1: server-2 keeps BOTH t-joint and t-s2 intact!
        val pageS1 =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.2.1",
                        changes =
                            listOf(
                                syncTaskDelete(seq = 1, id = "t-joint", channelId = "ch-s1"),
                                syncTaskUpsert(seq = 2, id = "t-s1-new", channelId = "ch-s1", title = "New S1", status = "in_progress", revision = 1L),
                            ),
                    )
                )
            )
        router.apply(scopeS1, pageS1.changes.toSyncApplyPlan())

        assertEquals(
            setOf("t-s1", "t-s1-new"),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-s1"))).tasks.mapTo(mutableSetOf()) { it.id.value },
        )
        assertEquals(
            setOf("t-joint", "t-s2"),
            store.projection("server-2", SharedTasksScope.Channel(ChannelId("ch-s2"))).tasks.mapTo(mutableSetOf()) { it.id.value },
        )

        // Now user switches active server from server-1 to server-2:
        // a late apply or resnapshot targeting server-1 does nothing to either server!
        targets.activeServerIds = setOf("server-2")
        targets.channelRefreshes.clear()
        val lateS1 =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.3.1",
                        changes =
                            listOf(
                                syncTaskDelete(seq = 3, id = "t-s1", channelId = "ch-s1"),
                                syncTaskResnapshot(seq = 4, channelId = "ch-s1"),
                            ),
                    )
                )
            )
        router.apply(scopeS1, lateS1.changes.toSyncApplyPlan())
        assertTrue(targets.channelRefreshes.isEmpty())
        assertEquals(
            setOf("t-s1", "t-s1-new"),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-s1"))).tasks.mapTo(mutableSetOf()) { it.id.value },
        )

        // Meanwhile server-2's own resnapshot updates ONLY server-2.
        val resnapshotS2 =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.4.1",
                        changes = listOf(syncTaskResnapshot(seq = 5, channelId = "ch-s2")),
                    )
                )
            )
        router.apply(scopeS2, resnapshotS2.changes.toSyncApplyPlan())
        assertEquals(listOf("server-2" to "ch-s2"), targets.channelRefreshes)
        assertEquals(
            listOf("t-fresh-server-2"),
            store.projection("server-2", SharedTasksScope.Channel(ChannelId("ch-s2"))).tasks.map { it.id.value },
        )
        assertEquals(
            setOf("t-s1", "t-s1-new"),
            store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-s1"))).tasks.mapTo(mutableSetOf()) { it.id.value },
        )
    }

    @Test
    fun `sync channel resnapshot supersedes an older in flight load for the same channel`() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = SharedTasksDataStore(scope = appScope)
            val olderStarted = CompletableDeferred<Unit>()
            val releaseOlder = CompletableDeferred<Unit>()
            val olderLoad =
                async {
                    store.loadSnapshot(
                        serverId = "server-1",
                        scope = SharedTasksScope.Channel(ChannelId("ch-1")),
                    ) {
                        olderStarted.complete(Unit)
                        releaseOlder.await()
                        DataResult.Content(
                            TaskSnapshot(listOf(wireTask("t-stale", "ch-1", title = "Stale load", revision = 1L)), emptyMap())
                        )
                    }
                }
            olderStarted.await()

            // Sync resnapshot runs while olderLoad is still in flight.
            val freshResult =
                store.reloadSnapshotForSync(
                    serverId = "server-1",
                    scope = SharedTasksScope.Channel(ChannelId("ch-1")),
                ) {
                    DataResult.Content(
                        TaskSnapshot(listOf(wireTask("t-fresh", "ch-1", title = "Fresh sync", revision = 2L)), emptyMap())
                    )
                }
            assertIs<DataResult.Content<TaskSnapshot>>(freshResult)

            // Releasing the older load must NOT overwrite the fresher sync resnapshot!
            releaseOlder.complete(Unit)
            olderLoad.await()

            val finalTasks = store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-1"))).tasks
            assertEquals(listOf("t-fresh"), finalTasks.map { it.id.value })
        } finally {
            appScope.cancel()
        }
    }

    @Test
    fun `channel scope removed drops that channel tasks without touching other channels`() = runTest {
        val store = SharedTasksDataStore(scope = backgroundScope)
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot =
                TaskSnapshot(
                    listOf(
                        wireTask("t-drop", "ch-drop", revision = 1L),
                        wireTask("t-keep", "ch-keep", revision = 1L),
                    ),
                    emptyMap(),
                ),
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-drop")),
            snapshot = TaskSnapshot(listOf(wireTask("t-drop", "ch-drop", revision = 1L)), emptyMap()),
        )
        store.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Channel(ChannelId("ch-keep")),
            snapshot = TaskSnapshot(listOf(wireTask("t-keep", "ch-keep", revision = 1L)), emptyMap()),
        )

        val router = RaftSyncChangesStoreRouter(StoreBackedTaskTargets(store))
        val page =
            requireNotNull(
                parseSyncChangesPage(
                    syncChangesBody(
                        cursor = "c1.20.1",
                        changes = listOf(syncScope(20, "channel", "ch-drop", "removed", syncChannelScopeSnapshots)),
                    )
                )
            )
        router.apply(syncTestScope, page.changes.toSyncApplyPlan())

        val chDrop = store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-drop")))
        assertFalse(chDrop.isExactScopeLoaded)
        assertEquals(emptyList(), chDrop.tasks)
        assertFalse("ch-drop" in store.heldScopesForServer("server-1").channelIds)
        assertEquals(listOf("t-keep"), store.projection("server-1", SharedTasksScope.Channel(ChannelId("ch-keep"))).tasks.map { it.id.value })
        assertEquals(listOf("t-keep"), store.projection("server-1", SharedTasksScope.Server).tasks.map { it.id.value })
    }
}
