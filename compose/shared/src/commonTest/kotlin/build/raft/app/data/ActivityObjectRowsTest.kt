package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ActivityChanged
import build.raft.app.model.InboxItemKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Activity rows for first-party objects (botiverse/slock feat/workspace-artifacts 542e922d6,
 * `packages/shared/src/activityObjectsContract.ts`; the row below follows its `ObjectInboxItem`
 * with an `ArtifactCard` summary, `workspaceArtifacts.ts`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityObjectRowsTest {
    private val objectId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"

    private fun objectRow(
        eventKind: String = "comment",
        reason: String = "following",
        unreadCount: Int = 2,
        hasMention: Boolean = false,
        targetVersion: Int = 3,
        summary: String = card(),
    ) =
        """
        {"kind":"object","objectKind":"artifact","objectId":"$objectId","summary":$summary,
         "home":{"kind":"channel","label":"proj-workspace","channelId":"c1"},
         "latestEvent":{"eventKind":"$eventKind","reason":"$reason","actor":{"type":"user","id":"u2","name":"kirby"},"at":"2026-10-26T10:00:00.000Z"},
         "preview":{"text":"Looks good","truncated":false,"author":{"type":"user","id":"u2","name":"kirby"},"at":"2026-10-26T10:00:00.000Z"},
         "target":{"version":$targetVersion,"comment":"cm_1"},
         "unreadCount":$unreadCount,"hasMention":$hasMention,"isFollowing":true,
         "lastActivityAt":"2026-10-26T10:00:00.000Z","doneFrontierSeq":"1842"}
        """.trimIndent()

    private fun card() =
        """
        {"id":"$objectId","url":"https://app.raft.build/artifacts/$objectId","title":"Q3 report","description":null,"kind":"csv",
         "facts":{"sizeBytes":10,"fileCount":1,"rows":5,"columns":4},"preview":{"kind":"none"},"version":4,"pinned":false,"latestVersion":4,
         "author":{"type":"agent","id":"a1","name":"lagoon","displayName":"Lagoon","avatarUrl":null},
         "createdAt":"2026-10-20T10:00:00.000Z","updatedAt":"2026-10-21T10:00:00.000Z","commentCount":2,
         "home":{"kind":"conversation","conversation":{"id":"c1","name":"proj-workspace","type":"channel"}}}
        """.trimIndent()

    private val channelRow =
        """{"kind":"channel","channelId":"c9","channelName":"general","channelType":"channel","unreadCount":3,"hasMention":false,"lastMessageAt":"2026-10-26T09:00:00.000Z"}"""

    @Test
    fun parsesObjectRowsBesideConversationRowsWithTheirOwnCounts() {
        val snapshot =
            parseSharedInboxSnapshot(
                """
                {"items":[${objectRow(hasMention = true)},$channelRow],"hasMore":false,"totalCount":1,"totalUnreadCount":3,
                 "activeUnreadCount":3,"objectUnreadCount":1,"objectMentionUnreadCount":1,"objectCount":1}
                """.trimIndent(),
            )!!
        assertEquals(listOf("object:artifact:$objectId", "channel:c9"), snapshot.items.map { it.key })
        assertEquals(2, snapshot.rawItemCount, "object rows page with the others")
        val item = snapshot.items.first()
        assertEquals(InboxItemKind.Object, item.kind)
        assertEquals(2, item.unreadCount)
        assertTrue(item.hasMention)
        assertEquals("2026-10-26T10:00:00.000Z", item.latestActivityAt)
        val row = item.activityObject!!
        assertEquals("Q3 report", row.title)
        assertEquals("csv", row.artifactKind)
        assertEquals("#proj-workspace", row.homeText)
        assertEquals("kirby", row.actorName)
        assertEquals(3, row.targetVersion)
        assertEquals("cm_1", row.targetComment)
        assertEquals("1842", row.doneFrontierSeq)
        assertEquals(3, row.openVersion, "the row's version is pinned when it is not the latest")
        // The server's conversation counts stay apart from the object counts.
        assertEquals(3, snapshot.totalUnreadCount)
        assertEquals(ActivityObjectCounts(1, 1, 1), snapshot.objectCounts)
    }

    @Test
    fun withObjectAppsOffNothingChanges() {
        val snapshot = parseSharedInboxSnapshot("""{"items":[$channelRow],"hasMore":false}""")!!
        assertNull(snapshot.objectCounts)
        assertEquals(3, snapshot.totalUnreadCount, "derived from conversation rows only")
        // An object row of a kind this client doesn't draw, or an unreadable one, is dropped.
        val other = parseSharedInboxSnapshot("""{"items":[${objectRow().replace("\"objectKind\":\"artifact\"", "\"objectKind\":\"board\"")},{"kind":"object"}]}""")!!
        assertTrue(other.items.isEmpty())
        assertEquals(2, other.rawItemCount)
    }

    @Test
    fun theVerbComesFromEventKindAndReason() {
        fun verb(eventKind: String, reason: String, version: Int?) =
            ActivityObjectRow(eventKind = eventKind, reason = reason, targetVersion = version, latestVersion = 5).eventVerb()
        assertEquals(ActivityObjectEventVerb.MentionedYou to null, verb("comment", "mentioned", 3))
        assertEquals(ActivityObjectEventVerb.MentionedYou to null, verb("comment_edited", "mentioned", 3))
        assertEquals(ActivityObjectEventVerb.Commented to null, verb("comment", "following", 3))
        assertEquals(ActivityObjectEventVerb.EditedComment to null, verb("comment_edited", "owner", 3))
        assertEquals(ActivityObjectEventVerb.PublishedVersion to 3, verb("version", "mentioned", 3))
        assertEquals(ActivityObjectEventVerb.PublishedVersion to 5, verb("version", "following", null))
        assertEquals(ActivityObjectEventVerb.Published to null, ActivityObjectRow(eventKind = "version").eventVerb())
        assertEquals(ActivityObjectEventVerb.Updated to null, verb("renamed", "following", 3))
    }

    @Test
    fun homeTextAndCountsPerFilter() {
        assertEquals("@kirby", ActivityObjectRow(homeKind = "dm", homeLabel = "kirby").homeText)
        assertNull(ActivityObjectRow(homeKind = "server").homeText)
        assertNull(ActivityObjectRow(homeKind = "orphan").homeText)
        val counts = ActivityObjectCounts(objectUnreadCount = 4, objectMentionUnreadCount = 2, objectCount = 6)
        assertEquals(4, counts.unreadFor(InboxFilter.All))
        assertEquals(2, counts.unreadFor(InboxFilter.Mentions))
        assertEquals(6, counts.unreadFor(InboxFilter.Unread))
        assertEquals(0, counts.unreadFor(InboxFilter.Pinned))
    }

    @Test
    fun badgesAddTheObjectUnreadTheOldFieldsExclude() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(
            ServerUnreadSummaryDto.serializer(),
            """{"serverId":"s1","unreadCount":5,"activityUnreadCount":3,"activityObjectUnreadCount":2}""",
        )
        assertEquals(5L, dto.knownActivityUnreadCount)
        assertEquals(3L, ServerUnreadSummaryDto("s1", 5, activityUnreadCount = 3).knownActivityUnreadCount)
        assertNull(ServerUnreadSummaryDto("s1", 5, activityObjectUnreadCount = 2).knownActivityUnreadCount, "unknown stays unknown")
        val snapshot = parseSharedInboxSnapshot("""{"items":[${objectRow()},$channelRow],"totalCount":1,"totalUnreadCount":3,"objectUnreadCount":1,"objectCount":1}""")!!
        val inbox = InboxSnapshot(server = build.raft.app.model.ServerSummary(ServerId("s1"), "S"), items = snapshot.items, totalCount = 1, totalUnreadCount = 3, objectCounts = snapshot.objectCounts)
        assertEquals(4 to 2, inbox.activityVisibleSummaryCounts())
    }

    @Test
    fun activityChangedMapsAndRefreshesActivity() {
        val event = RealtimeEventMapper().map(RealtimeSocketEvents.ActivityChanged, """{"serverId":"s1","source":"object"}""")
        assertEquals(ActivityChanged(ServerId("s1"), "object"), event)
        assertTrue(event!!.affectsActivityContent())
        assertTrue(RealtimeSocketEvents.ActivityChanged in RealtimeSocketEvents.ServerWireEvents)
        assertEquals("activity:changed", RealtimeSocketEvents.ActivityChanged)
    }

    @Test
    fun requestBodiesAreTheContractShapes() {
        val row = ActivityObjectRow(objectKind = "artifact", objectId = objectId, doneFrontierSeq = "1842")
        val done = Json.parseToJsonElement(encodeObjectActivityInput(row, withThroughSeq = true)).jsonObject
        assertEquals(setOf("kind", "objectId", "throughSeq"), done.keys)
        assertEquals("1842", done["throughSeq"]?.jsonPrimitive?.content)
        val read = Json.parseToJsonElement(encodeObjectActivityInput(row, withThroughSeq = false)).jsonObject
        assertEquals(setOf("kind", "objectId"), read.keys)
    }

    private class FakeObjectsRepository : ActivityObjectsRepository {
        val calls = mutableListOf<String>()
        override suspend fun act(serverId: String, action: ActivityObjectAction, row: ActivityObjectRow): DataResult<Unit> {
            calls += "${action.name}:${row.objectId}"
            return DataResult.Content(Unit)
        }

        override suspend fun unfollow(serverId: String, row: ActivityObjectRow): DataResult<Unit> {
            calls += "Unfollow:${row.objectId}"
            return DataResult.Content(Unit)
        }
    }

    @Test
    fun doneReadUndoAndUnfollowGoThroughTheObjectRoutes() = runTest {
        val parsed = parseSharedInboxSnapshot("""{"items":[${objectRow()},$channelRow],"totalCount":1,"totalUnreadCount":3,"objectUnreadCount":1,"objectCount":1}""")!!
        val initial = snapshot(parsed.items, totalCount = 1, totalUnreadCount = 3).copy(objectCounts = parsed.objectCounts)
        val repository = FakeObjectsRepository()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = FakeInboxRepository(initialSnapshots = listOf(initial)),
                activityObjectsRepository = repository,
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        val item =
            store.state.value.items.firstOrNull { it.kind == InboxItemKind.Object }
                ?: error("no object row loaded, state=${store.state.value.items.map { it.key }} loaded=${store.state.value.loaded} error=${store.state.value.errorCode}")

        store.dispatch(SharedActivityIntent.MarkRead(item))
        runCurrent()
        val read = store.state.value.items.first { it.key == item.key }
        assertEquals(0, read.unreadCount)
        assertEquals(0, store.state.value.snapshot!!.objectCounts!!.objectUnreadCount)
        assertEquals(3, store.state.value.totalUnreadCount, "conversation counts untouched")

        store.dispatch(SharedActivityIntent.MarkDone(read))
        runCurrent()
        assertTrue(store.state.value.items.none { it.key == item.key })
        assertEquals(0, store.state.value.snapshot!!.objectCounts!!.objectCount)

        store.dispatch(SharedActivityIntent.UndoObjectDone(read))
        store.dispatch(SharedActivityIntent.UnfollowObject(read))
        runCurrent()
        assertEquals(listOf("Read:$objectId", "Done:$objectId", "Undone:$objectId", "Unfollow:$objectId"), repository.calls)
    }
}
