package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftDraftChannelPlace
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.parseRaftDraftChannelPlace
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.topLevelThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drafts (drafts_section_v0, web botiverse/slock #12159 `useDrafts.ts` / `draftPlaces.ts`): which drafts
 * are listed and counted, where each is placed, their order, and the edit times kept beside the drafts.
 */
class RaftDraftsTest {
    private val directory =
        RaftDraftDirectory(
            serverId = "s1",
            channels =
                mapOf(
                    "general" to HomeChannelRow(threadId = topLevelThreadId(ChannelId("general")), channelId = ChannelId("general"), name = "general"),
                ),
            directMessages =
                mapOf(
                    "dm-alice" to
                        HomeDirectMessageRow(
                            threadId = topLevelThreadId(ChannelId("dm-alice")),
                            channelId = ChannelId("dm-alice"),
                            name = "Alice",
                        ),
                ),
            channelSymbols = mapOf("general" to "#"),
            tasks =
                listOf(
                    TaskSummary(
                        id = TaskId("task-7"),
                        title = "Ship drafts",
                        status = TaskStatus.InProgress,
                        taskNumber = 7,
                        channelId = ChannelId("general"),
                        conversationChannelId = ChannelId("conv-7"),
                        updatedAtLabel = "",
                    ),
                ),
        )

    private fun root(send: String) = record("send=$send|parent=_|mention=$send", send)

    private fun reply(stream: String, parent: String, channel: String) =
        record("send=$stream|parent=$parent|mention=$channel", stream, parent, channel)

    private fun record(key: String, send: String, parent: String? = null, mention: String? = null) =
        SharedThreadComposerDraftRecord(key = key, sendChannelId = send, parentMessageId = parent, mentionChannelId = mention ?: send)

    private fun draft(record: SharedThreadComposerDraftRecord, text: String, at: Long?) =
        SharedThreadComposerDraftListing(record = record, text = text, editedAtEpochMillis = at)

    @Test
    fun placesEachKindFromWhatTheDeviceHasWithoutAsking() {
        val drafts =
            listOf(
                draft(root("general"), "channel draft", 10),
                draft(root("dm-alice"), "dm draft", 20),
                draft(reply("thread-1", "msg-1", "general"), "thread draft", 30),
                // A thread nobody has replied to yet: the stream is still the parent channel.
                draft(reply("general", "msg-2", "general"), "first reply", 40),
                draft(root("conv-7"), "task draft", 50),
            )
        val listing = raftDraftListing(drafts, directory, lookups = emptyMap())

        assertEquals(emptySet(), listing.lookupChannelIds)
        assertEquals(5, listing.count)
        assertEquals(
            listOf(
                RaftDraftPlace.Task("conv-7", parentChannelId = "general", taskId = "task-7"),
                RaftDraftPlace.Thread("general", "msg-2", threadChannelId = null),
                RaftDraftPlace.Thread("general", "msg-1", threadChannelId = "thread-1"),
                RaftDraftPlace.Conversation("dm-alice", directMessage = true),
                RaftDraftPlace.Conversation("general", directMessage = false),
            ),
            listing.entries.map { it.place },
        )
    }

    @Test
    fun whitespaceOnlyDraftsAreNeitherListedNorCountedNorLookedUp() {
        val drafts =
            listOf(
                draft(root("general"), "   \n\t ", 10),
                draft(root("unknown"), "  ", 20),
                draft(root("dm-alice"), " hi ", 30),
            )
        val listing = raftDraftListing(drafts, directory, lookups = emptyMap())
        assertEquals(1, listing.count)
        assertEquals("send=dm-alice|parent=_|mention=dm-alice", listing.entries.single().key)
        assertEquals(emptySet(), listing.lookupChannelIds)
    }

    @Test
    fun newestEditFirstAndDraftsWithoutATimeLastInKeyOrder() {
        val drafts =
            listOf(
                draft(root("b-old"), "b", null),
                draft(root("general"), "older", 100),
                draft(root("a-old"), "a", null),
                draft(root("dm-alice"), "newer", 200),
            )
        val lookups =
            mapOf<String, RaftDraftChannelPlace?>(
                "a-old" to RaftDraftChannelPlace("a-old", "channel"),
                "b-old" to RaftDraftChannelPlace("b-old", "private"),
            )
        val keys = raftDraftListing(drafts, directory, lookups).entries.map { it.record() }
        assertEquals(listOf("dm-alice", "general", "a-old", "b-old"), keys)
    }

    private fun RaftDraftEntry.record(): String = key.substringAfter("send=").substringBefore('|')

    @Test
    fun unknownConversationsAreLookedUpAndOnlyAnswersPlaceThem() {
        val drafts =
            listOf(
                draft(root("conv-9"), "task elsewhere", 10),
                draft(root("other-server"), "not here", 20),
                draft(reply("thread-x", "msg-x", "unjoined"), "thread in an unjoined channel", 30),
            )
        val asking = raftDraftListing(drafts, directory, lookups = emptyMap())
        assertEquals(0, asking.count)
        assertEquals(setOf("conv-9", "other-server", "unjoined"), asking.lookupChannelIds)

        val answered =
            raftDraftListing(
                drafts,
                directory,
                lookups =
                    mapOf(
                        "conv-9" to RaftDraftChannelPlace("conv-9", "task", parentChannelId = "general", taskId = "task-9"),
                        // 404 / 403: remembered as "not here".
                        "other-server" to null,
                        "unjoined" to RaftDraftChannelPlace("unjoined", "channel"),
                    ),
            )
        assertEquals(emptySet(), answered.lookupChannelIds)
        assertEquals(
            listOf(
                RaftDraftPlace.Thread("unjoined", "msg-x", "thread-x"),
                RaftDraftPlace.Task("conv-9", "general", "task-9"),
            ),
            answered.entries.map { it.place },
        )
    }

    @Test
    fun channelLookupParsesTheChannelRow() {
        assertEquals(
            RaftDraftChannelPlace("c1", "task", parentChannelId = "p1", parentMessageId = null, taskId = "t1"),
            parseRaftDraftChannelPlace("""{"id":"c1","type":"Task","parentChannelId":"p1","taskId":"t1","name":"x"}"""),
        )
        assertNull(parseRaftDraftChannelPlace("""{"error":"Channel not found"}"""))
        assertNull(parseRaftDraftChannelPlace(null))
    }

    @Test
    fun lookupsRunThreeAtATimeRememberNotHereAndRetryFailures() = runTest {
        val lookups = RaftDraftPlaceLookups(maxConcurrent = 3)
        lookups.bindAccount("env|user-a")
        var running = 0
        var peak = 0
        val asked = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val first =
            async {
                lookups.ensure("s1", listOf("a", "b", "c", "d", "e")) { _, id ->
                    asked += id
                    running += 1
                    peak = maxOf(peak, running)
                    gate.await()
                    running -= 1
                    when (id) {
                        "a" -> RaftDraftLookupResult.Found(RaftDraftChannelPlace("a", "channel"))
                        "b" -> RaftDraftLookupResult.NotHere
                        else -> RaftDraftLookupResult.Failed
                    }
                }
            }
        repeat(5) { yield() }
        assertEquals(3, peak)
        gate.complete(Unit)
        assertFalse(first.await(), "a failure is reported so the caller asks again later")
        assertEquals(3, peak)
        assertEquals(
            mapOf("a" to RaftDraftChannelPlace("a", "channel"), "b" to null),
            lookups.state.value[RaftDraftPlaceLookups.Scope("env|user-a", "s1")],
        )

        asked.clear()
        assertTrue(
            lookups.ensure("s1", listOf("a", "b", "c")) { _, id ->
                asked += id
                RaftDraftLookupResult.NotHere
            }
        )
        assertEquals(listOf("c"), asked, "answers are not asked again; failures are")
        assertNull(lookups.state.value[RaftDraftPlaceLookups.Scope("env|user-a", "s2")], "answers are per server")
    }

    @Test
    fun anotherAccountOnTheSameServerDoesNotSeeTheFirstAccountsAnswers() = runTest {
        val lookups = RaftDraftPlaceLookups()
        val asked = mutableListOf<Pair<String, String>>()
        var account = "env|user-a"
        val fetch: suspend (String, String) -> RaftDraftLookupResult = { _, id ->
            asked += account to id
            if (account == "env|user-a") RaftDraftLookupResult.Found(RaftDraftChannelPlace(id, "channel")) else RaftDraftLookupResult.NotHere
        }

        // Signed out: nothing is asked or kept.
        assertTrue(lookups.ensure("s1", listOf("c1"), fetch))
        assertEquals(emptyList(), asked)

        lookups.bindAccount(account)
        lookups.ensure("s1", listOf("c1", "c2"), fetch)
        val a = RaftDraftPlaceLookups.Scope("env|user-a", "s1")
        assertEquals(setOf("c1", "c2"), lookups.state.value[a]?.keys)

        // Account B on the same server: A's answers (found and not-here alike) are gone and B asks again.
        account = "env|user-b"
        lookups.bindAccount(account)
        assertEquals(emptyMap(), lookups.state.value)
        assertEquals(RaftDraftPlaceLookups.Scope("env|user-b", "s1"), lookups.scope("s1"))
        lookups.ensure("s1", listOf("c1"), fetch)
        assertEquals(listOf("env|user-a" to "c1", "env|user-a" to "c2", "env|user-b" to "c1"), asked)
        assertEquals(mapOf("c1" to null), lookups.state.value[RaftDraftPlaceLookups.Scope("env|user-b", "s1")])
        assertNull(lookups.state.value[a])

        // Sign-out clears B's answers too.
        lookups.bindAccount(null)
        assertEquals(emptyMap(), lookups.state.value)
        assertNull(lookups.scope("s1"))
    }

    @Test
    fun editTimesAreKeptBesideTheDraftsAndOlderDraftsHaveNone() {
        val storage = InMemoryRaftKeyValueStore()
        var now = 1_000L
        val persistence = SharedThreadComposerDraftPersistence(storage, nowEpochMillis = { now })
        val key = "send=general|parent=_|mention=general"

        persistence.save(key, text = "hello", asTask = false)
        assertEquals(1_000L, persistence.draftListings().single().editedAtEpochMillis)

        // A save that leaves the text as it was (a flush, an attachment change) keeps the time.
        now = 2_000L
        persistence.save(key, text = "hello", asTask = true)
        assertEquals(1_000L, persistence.draftListings().single().editedAtEpochMillis)

        now = 3_000L
        persistence.save(key, text = "hello world", asTask = true)
        val listed = persistence.draftListings().single()
        assertEquals(3_000L, listed.editedAtEpochMillis)
        assertEquals("hello world", listed.text)
        assertEquals(key, listed.record.key)

        persistence.clear(key)
        assertEquals(emptyList(), persistence.draftListings())
        assertNull(storage.getString("${SharedThreadComposerDraftPersistence.DraftStoragePrefix}$key:editedAt"))

        // A draft written before edit times existed: listed, without a time, until its text changes.
        val legacy = "send=dm-alice|parent=_|mention=dm-alice"
        storage.putString("${SharedThreadComposerDraftPersistence.DraftStoragePrefix}$legacy:text", "from before")
        storage.putString(SharedThreadComposerDraftPersistence.ActiveIndexKey, legacy)
        assertNull(persistence.draftListings().single().editedAtEpochMillis)
        persistence.save(legacy, text = "from before", asTask = false)
        assertNull(persistence.draftListings().single().editedAtEpochMillis)
        now = 4_000L
        persistence.save(legacy, text = "from before, edited", asTask = false)
        assertEquals(4_000L, persistence.draftListings().single().editedAtEpochMillis)
    }

    private val labels =
        RaftDraftRowLabels(
            threadIn = { "Thread in $it" },
            taskUntitled = "Task",
            unknownConversation = "A conversation",
            prefix = { "$it ·" },
        )

    @Test
    fun rowsReadLikeActivityRows() {
        fun row(place: RaftDraftPlace, text: String = "first line\nsecond") =
            RaftDraftEntry(key = place.toString(), text = text, editedAtEpochMillis = 0L, place = place).toRaftDraftRowModel(directory, labels)

        val channel = row(RaftDraftPlace.Conversation("general", directMessage = false))
        assertEquals("general", channel.title)
        assertEquals("first line", channel.preview)
        assertNull(channel.prefix)
        assertEquals(ActivityConversationLeadingKind.Channel, channel.leading.leadingKind)
        assertEquals("1970-01-01T00:00:00Z", channel.editedAt)

        val dm = row(RaftDraftPlace.Conversation("dm-alice", directMessage = true))
        assertEquals("Alice", dm.title)
        assertEquals("Alice", dm.leading.dmPeer?.displayName)

        val thread = row(RaftDraftPlace.Thread("general", "msg-1", "thread-1"), text = "\n  \nreply")
        assertEquals("Thread in #general", thread.title)
        assertEquals("reply", thread.preview)
        assertEquals(ActivityConversationLeadingKind.Thread, thread.leading.leadingKind)
        assertFalse(thread.leading.taskConversation)

        val dmThread = row(RaftDraftPlace.Thread("dm-alice", "msg-2", null))
        assertEquals("Thread in Alice", dmThread.title)
        assertTrue(dmThread.leading.threadInDirectMessage)

        val task = row(RaftDraftPlace.Task("conv-7", "general", "task-7"))
        assertEquals("Ship drafts", task.title)
        assertEquals("#general:#7 ·", task.prefix)
        assertTrue(task.leading.taskConversation)

        val unknownTask = row(RaftDraftPlace.Task("conv-9", "general", "task-9"))
        assertEquals("Task", unknownTask.title)
        assertEquals("#general ·", unknownTask.prefix)

        val noTime = RaftDraftEntry("k", "x", null, RaftDraftPlace.Conversation("general", false)).toRaftDraftRowModel(directory, labels)
        assertNull(noTime.editedAt)
    }

    @Test
    fun opensWhereTheDraftWasTyped() {
        fun open(place: RaftDraftPlace) =
            RaftDraftEntry("k", "x", 1L, place).toHomeMessageOpenRequest("s1", directory, SharedConversationDirectoryState())

        val channel = open(RaftDraftPlace.Conversation("general", directMessage = false))
        assertEquals(ChannelId("general"), channel?.conversationIdentity?.channelId)
        assertEquals(ConversationKind.Channel, channel?.conversationIdentity?.kind)
        assertNull(channel?.anchorMessageId)

        val thread = open(RaftDraftPlace.Thread("general", "msg-1", "thread-1"))
        assertEquals(ConversationKind.Thread, thread?.conversationIdentity?.kind)
        assertEquals(ChannelId("general"), thread?.conversationIdentity?.thread?.parentChannelId)
        assertEquals(MessageId("msg-1"), thread?.conversationIdentity?.thread?.parentMessageId)
        assertEquals(ChannelId("thread-1"), thread?.conversationIdentity?.thread?.streamChannelId)

        val task = open(RaftDraftPlace.Task("conv-7", "general", "task-7"))
        assertEquals(ConversationKind.Task, task?.conversationIdentity?.kind)
        assertEquals(ChannelId("conv-7"), task?.conversationIdentity?.channelId)
        assertIs<ThreadId>(task?.conversationIdentity?.threadId)
    }
}
