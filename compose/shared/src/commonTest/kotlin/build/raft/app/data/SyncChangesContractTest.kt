package build.raft.app.data

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /api/sync/changes` parsing against botiverse/slock 0d030e341 (PR #11459) shapes. */
class SyncChangesContractTest {
    @Test
    fun `first call without since is the current cursor and nothing else`() {
        val page = parseSyncChangesPage("""{"cursor":"c1.42.900","hasMore":false,"changes":[],"reset":null}""")!!
        assertEquals("c1.42.900", page.cursor)
        assertEquals(false, page.hasMore)
        assertTrue(page.changes.isEmpty())
        assertNull(page.reset)
    }

    @Test
    fun `row upserts and deletes keep the list row and the server order`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.900.k",
                    hasMore = true,
                    changes =
                        listOf(
                            syncUpsert(3, "channel", "ch-1", """{"id":"ch-1","name":"general","type":"channel"}"""),
                            syncUpsert(4, "channel_member", "ch-1:agent:a-1", """{"id":"a-1","name":"bot"}"""),
                            syncUpsert(5, "server_member", "u-2", """{"userId":"u-2","name":"Ann","role":"member"}"""),
                            syncUpsert(6, "agent", "a-1", """{"id":"a-1","name":"bot","status":"active"}"""),
                            syncUpsert(7, "machine", "m-1", """{"id":"m-1","name":"Mac","status":"online"}"""),
                            syncUpsert(8, "sidebar_order", "server-1", """{"channelOrder":["ch-1"]}"""),
                            syncUpsert(9, "server_order", "user-1", """{"serverOrder":["server-1"]}"""),
                            syncDelete(10, "agent", "a-2"),
                        ),
                )
            )!!
        assertEquals(true, page.hasMore)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), page.changes.map { it.seq })
        val entities = page.changes.filterIsInstance<RaftSyncChange.Upsert>().map { it.entity }
        assertEquals(
            listOf(
                RaftSyncRowEntity.Channel,
                RaftSyncRowEntity.ChannelMember,
                RaftSyncRowEntity.ServerMember,
                RaftSyncRowEntity.Agent,
                RaftSyncRowEntity.Machine,
                RaftSyncRowEntity.SidebarOrder,
                RaftSyncRowEntity.ServerOrder,
            ),
            entities,
        )
        val channel = page.changes.first() as RaftSyncChange.Upsert
        assertEquals("ch-1", channel.id)
        assertEquals(JsonPrimitive("general"), channel.row["name"])
        assertEquals(RaftSyncChange.Delete(10, RaftSyncRowEntity.Agent, "a-2"), page.changes.last())
    }

    @Test
    fun `conversation stale carries its read position or null`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(cursor = "c1.2.1", changes = listOf(syncStale(1, "ch-1", 7), syncStale(2, "th-1", null)))
            )!!
        assertEquals(
            listOf(
                RaftSyncChange.ConversationStale(1, "ch-1", 7),
                RaftSyncChange.ConversationStale(2, "th-1", null),
            ),
            page.changes,
        )
    }

    @Test
    fun `scope added and removed name the channel or server and its snapshots`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.3.1",
                    changes =
                        listOf(
                            syncScope(1, "channel", "room-1", "added", syncChannelScopeSnapshots),
                            syncScope(2, "channel", "room-1", "removed", syncChannelScopeSnapshots),
                            syncScope(3, "server", "server-1", "added", syncAllSnapshotNames),
                        ),
                )
            )!!
        assertEquals(
            RaftSyncChange.ScopeAdded(
                1,
                RaftSyncScopeKind.Channel,
                "room-1",
                setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.ChannelMembers, RaftSyncSnapshot.Inbox, RaftSyncSnapshot.Unread),
            ),
            page.changes[0],
        )
        assertEquals(RaftSyncChange.ScopeRemoved(2, RaftSyncScopeKind.Channel, "room-1"), page.changes[1])
        assertEquals(RaftSyncSnapshot.All, (page.changes[2] as RaftSyncChange.ScopeAdded).snapshots)
    }

    @Test
    fun `an unknown entity is skipped and counted`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.2.1",
                    changes = listOf(syncUpsert(1, "reaction", "r-1", "{}"), syncDelete(2, "machine", "m-1")),
                )
            )!!
        assertEquals(listOf<RaftSyncChange>(RaftSyncChange.Delete(2, RaftSyncRowEntity.Machine, "m-1")), page.changes)
        assertEquals(1, page.skippedUnknownEntities)
    }

    @Test
    fun `a known entity with an unknown op retakes that entity snapshot`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.4.1",
                    changes =
                        listOf(
                            """{"seq":1,"entity":"agent","id":"a-1","op":"archived"}""",
                            """{"seq":2,"entity":"conversation","id":"ch-1","op":"muted"}""",
                            """{"seq":3,"entity":"scope","id":"x-1","op":"added","scopeKind":"workspace","snapshots":["agents"]}""",
                            """{"seq":4,"entity":"scope","id":"ch-1","op":"paused","scopeKind":"channel","snapshots":[]}""",
                        ),
                )
            )!!
        val resnapshots = page.changes.map { assertIs<RaftSyncChange.Resnapshot>(it).snapshots }
        assertEquals(
            listOf(setOf(RaftSyncSnapshot.Agents), RaftSyncConversationSnapshots, setOf(RaftSyncSnapshot.Agents), RaftSyncSnapshot.All),
            resnapshots,
        )
    }

    @Test
    fun `reset lists the snapshots to retake and skips unknown names`() {
        val page =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.50.1000",
                    reset = """{"reason":"too_old","snapshots":["channels","agents","reactions"]}""",
                )
            )!!
        assertEquals(RaftSyncReset("too_old", setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.Agents)), page.reset)
        // A guest's reset leaves out what its endpoints refuse (no machines, no server_members).
        val guest =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.50.1000",
                    reset = """{"reason":"forced","snapshots":[${syncAllSnapshotNames.filterNot { it == "machines" || it == "server_members" }.joinToString(",") { "\"$it\"" }}]}""",
                )
            )!!
        assertEquals(RaftSyncSnapshot.All - RaftSyncSnapshot.Machines - RaftSyncSnapshot.ServerMembers, guest.reset!!.snapshots)
    }

    @Test
    fun `contract mismatches are rejected`() {
        listOf(
            """{"hasMore":false,"changes":[],"reset":null}""",
            """{"cursor":"c1.1.1","changes":[],"reset":null}""",
            """{"cursor":"","hasMore":false,"changes":[],"reset":null}""",
            syncChangesBody(cursor = "c1.1.1", changes = listOf("""{"entity":"agent","id":"a-1","op":"delete"}""")),
            syncChangesBody(cursor = "c1.1.1", changes = listOf("""{"seq":1,"entity":"agent","id":"a-1","op":"upsert"}""")),
            "not json",
        ).forEach { body -> assertNull(parseSyncChangesPage(body), body) }
    }

    @Test
    fun `http answers map to a page an invalid cursor or a failure`() {
        assertIs<RaftSyncChangesFetch.Page>(syncChangesFetchOf(HomeNetworkResponse(200, syncChangesBody("c1.1.1"))))
        assertEquals(
            RaftSyncChangesFetch.InvalidCursor,
            syncChangesFetchOf(
                HomeNetworkResponse(400, null, """{"error":"since must be a cursor this endpoint returned","code":"invalid_cursor"}""")
            ),
        )
        assertEquals(
            RaftSyncChangesFetch.Failed("http_400"),
            syncChangesFetchOf(HomeNetworkResponse(400, null, """{"error":"limit must be a positive integer","code":"invalid_limit"}""")),
        )
        assertEquals(RaftSyncChangesFetch.Failed("http_500"), syncChangesFetchOf(HomeNetworkResponse(500, null)))
        assertEquals(RaftSyncChangesFetch.Failed("contract_mismatch"), syncChangesFetchOf(HomeNetworkResponse(200, "{}")))
    }
}
