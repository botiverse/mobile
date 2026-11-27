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
import build.raft.app.thread.appendThreadQuoteToComposer
import build.raft.app.thread.key
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun draft(
        record: SharedThreadComposerDraftRecord,
        text: String,
        at: Long?,
        serverId: String? = null,
    ) = SharedThreadComposerDraftListing(
        record = record,
        text = text,
        editedAtEpochMillis = at,
        serverId = serverId,
    )

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
    fun anotherAccountOnTheSameServerDoesNotSeeTheFirstAccountsAnswers() = runTest {
        val lookups = RaftDraftPlaceLookups()
        val asked = mutableListOf<Pair<String, String>>()
        var account = "env|user-a"
        val fetch: suspend (String, String) -> RaftDraftLookupResult = { _, id ->
            asked += account to id
            if (account == "env|user-a") RaftDraftLookupResult.Found(RaftDraftChannelPlace(id, "channel")) else RaftDraftLookupResult.NotFound
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

    @Test
    fun aQuoteReplyDraftsRowReadsItsReplyOrTheQuotesWords() {
        // web DraftsPanel (botiverse/slock #16158): the draft through `extractQuoteReplyPreviewText`.
        val labels = RaftDraftRowLabels(threadIn = { "Thread in $it" }, taskUntitled = "Untitled", unknownConversation = "Unknown", prefix = { "$it ·" })
        fun preview(text: String) =
            RaftDraftEntry("k", text, 1L, RaftDraftPlace.Conversation("general", directMessage = false)).toRaftDraftRowModel(directory, labels).preview
        val quoted = appendThreadQuoteToComposer("", "> Status page banner goes live at 9:00 PT\n> msg=c54e1234")
        // Quoted, nothing typed yet: the quote's words, without `>` or its ref line.
        assertEquals("Status page banner goes live at 9:00 PT", preview(quoted))
        assertEquals("Can we move it to 8:30?", preview(quoted + "Can we move it to 8:30?"))
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

    @Test
    fun jointChannelRootAndReplyDraftsAreListedAndOpenableInBothServersDrafts() {
        val serverADirectory =
            RaftDraftDirectory(
                serverId = "server-a",
                channels =
                    mapOf(
                        "chan-a" to
                            HomeChannelRow(
                                threadId = topLevelThreadId(ChannelId("chan-a")),
                                channelId = ChannelId("chan-a"),
                                kind = ConversationKind.Joint,
                                name = "wg-shared",
                                jointChannelId = "joint-42",
                            ),
                    ),
                channelSymbols = mapOf("chan-a" to "⇄"),
            )
        val serverBDirectory =
            RaftDraftDirectory(
                serverId = "server-b",
                channels =
                    mapOf(
                        "chan-b" to
                            HomeChannelRow(
                                threadId = topLevelThreadId(ChannelId("chan-b")),
                                channelId = ChannelId("chan-b"),
                                kind = ConversationKind.Joint,
                                name = "wg-shared",
                                jointChannelId = "joint-42",
                            ),
                    ),
                channelSymbols = mapOf("chan-b" to "⇄"),
            )
        val unrelatedServerDirectory =
            RaftDraftDirectory(
                serverId = "server-c",
                channels =
                    mapOf(
                        "other" to
                            HomeChannelRow(
                                threadId = topLevelThreadId(ChannelId("other")),
                                channelId = ChannelId("other"),
                                name = "general",
                            ),
                    ),
            )

        val rootRecord =
            SharedThreadComposerDraftRecord(
                key = "send=chan-a|parent=_|mention=chan-a|joint=joint-42",
                sendChannelId = "chan-a",
                mentionChannelId = "chan-a",
                jointChannelId = "joint-42",
            )
        val replyRecord =
            SharedThreadComposerDraftRecord(
                key = "send=thread-a|parent=msg-9|mention=chan-a|joint=joint-42",
                sendChannelId = "thread-a",
                parentMessageId = "msg-9",
                mentionChannelId = "chan-a",
                jointChannelId = "joint-42",
            )
        val drafts =
            listOf(
                draft(rootRecord, "shared channel note", 100),
                draft(replyRecord, "shared thread note", 200),
            )

        val listingA = raftDraftListing(drafts, serverADirectory, lookups = emptyMap())
        assertEquals(emptySet(), listingA.lookupChannelIds)
        assertEquals(
            listOf(
                RaftDraftPlace.Thread("chan-a", "msg-9", threadChannelId = "thread-a"),
                RaftDraftPlace.Conversation("chan-a", directMessage = false),
            ),
            listingA.entries.map { it.place },
        )

        // Server B places both onto its own local projection `chan-b` with zero server lookups!
        val listingB = raftDraftListing(drafts, serverBDirectory, lookups = emptyMap())
        assertEquals(emptySet(), listingB.lookupChannelIds)
        assertEquals(
            listOf(
                RaftDraftPlace.Thread("chan-b", "msg-9", threadChannelId = null),
                RaftDraftPlace.Conversation("chan-b", directMessage = false),
            ),
            listingB.entries.map { it.place },
        )

        // Opening both entries on Server B routes directly into Server B's local joint channel / reply thread.
        val openThreadB = listingB.entries[0].toHomeMessageOpenRequest("server-b", serverBDirectory, SharedConversationDirectoryState())
        assertEquals(ConversationKind.Thread, openThreadB?.conversationIdentity?.kind)
        assertEquals(ChannelId("chan-b"), openThreadB?.conversationIdentity?.thread?.parentChannelId)
        assertEquals(MessageId("msg-9"), openThreadB?.conversationIdentity?.thread?.parentMessageId)
        assertNull(openThreadB?.conversationIdentity?.thread?.streamChannelId)
        assertEquals("joint-42", openThreadB?.conversationIdentity?.jointChannelId)

        val openRootB = listingB.entries[1].toHomeMessageOpenRequest("server-b", serverBDirectory, SharedConversationDirectoryState())
        assertEquals(ConversationKind.Joint, openRootB?.conversationIdentity?.kind)
        assertEquals(ChannelId("chan-b"), openRootB?.conversationIdentity?.channelId)
        assertEquals("joint-42", openRootB?.conversationIdentity?.jointChannelId)

        // Activity/resolver path without explicit jointChannelId inherits it from the parent projection in conversationDirectory.
        val serverBConversationDir =
            SharedConversationDirectoryState(
                projectionsByCanonicalId =
                    mapOf(
                        "chan-b" to
                            build.raft.app.data.SharedConversationDirectoryProjection(
                                serverId = "server-b",
                                canonicalChannelId = "chan-b",
                                aliases = setOf("chan-b"),
                                conversationKind = ConversationKind.Joint,
                                surfaceKind = RaftThreadSurfaceKind.Channel,
                                channelKind = "joint",
                                title = "wg-shared",
                                jointChannelId = "joint-42",
                            ),
                    ),
            )
        val resolvedFromDir =
            resolveOpenRequest(
                serverId = "server-b",
                sourceMessageIdentity =
                    ConversationOpenSourceMessageIdentity(
                        source = ConversationOpenSource.ActivityRow,
                        messageId = "msg-reply-1",
                        channelId = null,
                        threadChannelId = "thread-b",
                        parentChannelId = "chan-b",
                        parentMessageId = "msg-9",
                        conversationKind = ConversationKind.Thread,
                    ),
                conversationDirectory = serverBConversationDir,
            )
        assertIs<ConversationOpenResolution.Resolved>(resolvedFromDir)
        assertEquals("joint-42", resolvedFromDir.request.conversationIdentity.jointChannelId)

        // Unrelated server does not show the joint channel draft once lookup returns 404 (null).
        val listingC = raftDraftListing(drafts, unrelatedServerDirectory, lookups = mapOf("chan-a" to null))
        assertEquals(0, listingC.count)
    }

    @Test
    fun duplicateSiblingJointDraftsDeduplicateKeepingNewestEdit() {
        val serverBDirectory =
            RaftDraftDirectory(
                serverId = "server-b",
                channels =
                    mapOf(
                        "chan-b" to
                            HomeChannelRow(
                                threadId = topLevelThreadId(ChannelId("chan-b")),
                                channelId = ChannelId("chan-b"),
                                kind = ConversationKind.Joint,
                                name = "wg-shared",
                                jointChannelId = "joint-42",
                            ),
                    ),
            )
        val older =
            draft(
                SharedThreadComposerDraftRecord(
                    key = "send=chan-a|parent=_|mention=chan-a|joint=joint-42",
                    sendChannelId = "chan-a",
                    mentionChannelId = "chan-a",
                    jointChannelId = "joint-42",
                ),
                "older text",
                100,
            )
        val newer =
            draft(
                SharedThreadComposerDraftRecord(
                    key = "send=chan-b|parent=_|mention=chan-b|joint=joint-42",
                    sendChannelId = "chan-b",
                    mentionChannelId = "chan-b",
                    jointChannelId = "joint-42",
                ),
                "newer text",
                200,
            )
        val listing = raftDraftListing(listOf(older, newer), serverBDirectory, lookups = emptyMap())
        assertEquals(1, listing.count)
        assertEquals("newer text", listing.entries.single().text)
    }

    @Test
    fun channelLookupParsesOptionalJointChannelId() {
        assertEquals(
            RaftDraftChannelPlace("c1", "joint", jointChannelId = "j1"),
            parseRaftDraftChannelPlace("""{"id":"c1","type":"joint","jointChannelId":"j1"}"""),
        )
    }

    @Test
    fun nonJointDraftFromAnotherServerResolvesNotHereWithoutLookup() {
        val drafts =
            listOf(
                draft(root("chan-on-s2"), "channel on server 2", 100, serverId = "s2"),
                draft(reply("thread-on-s2", "msg-2", "chan-on-s2"), "reply on server 2", 200, serverId = "s2"),
                draft(root("task-on-s2"), "task on server 2", 300, serverId = "s2"),
                // Draft on this server still resolves normally.
                draft(root("general"), "channel on server 1", 400, serverId = "s1"),
                // Legacy draft without serverId still falls back to lookup.
                draft(root("legacy-unjoined"), "legacy draft", 500, serverId = null),
            )

        assertEquals(
            RaftDraftPlaceResolution.NotHere,
            root("chan-on-s2").resolveRaftDraftPlace(directory, lookups = emptyMap(), draftServerId = "s2"),
        )
        assertEquals(
            RaftDraftPlaceResolution.NotHere,
            reply("thread-on-s2", "msg-2", "chan-on-s2").resolveRaftDraftPlace(directory, lookups = emptyMap(), draftServerId = "s2"),
        )

        val listing = raftDraftListing(drafts, directory, lookups = emptyMap())
        assertEquals(listOf(RaftDraftPlace.Conversation("general", directMessage = false)), listing.entries.map { it.place })
        assertEquals(setOf("legacy-unjoined"), listing.lookupChannelIds)
    }

    @Test
    fun jointChannelDraftSavedOnSiblingServerStillResolvesViaJointChannelRow() {
        val serverADirectory =
            RaftDraftDirectory(
                serverId = "s1",
                channels =
                    mapOf(
                        "chan-a" to
                            HomeChannelRow(
                                threadId = topLevelThreadId(ChannelId("chan-a")),
                                channelId = ChannelId("chan-a"),
                                kind = ConversationKind.Joint,
                                name = "wg-shared",
                                jointChannelId = "joint-42",
                            ),
                    ),
            )
        val jointRootRecord =
            SharedThreadComposerDraftRecord(
                key = "send=chan-b|parent=_|mention=chan-b|joint=joint-42",
                sendChannelId = "chan-b",
                mentionChannelId = "chan-b",
                jointChannelId = "joint-42",
            )
        val jointReplyRecord =
            SharedThreadComposerDraftRecord(
                key = "send=thread-b|parent=msg-9|mention=chan-b|joint=joint-42",
                sendChannelId = "thread-b",
                parentMessageId = "msg-9",
                mentionChannelId = "chan-b",
                jointChannelId = "joint-42",
            )
        val drafts =
            listOf(
                draft(jointRootRecord, "joint root from s2", 100, serverId = "s2"),
                draft(jointReplyRecord, "joint reply from s2", 200, serverId = "s2"),
            )

        val listing = raftDraftListing(drafts, serverADirectory, lookups = emptyMap())
        assertEquals(emptySet(), listing.lookupChannelIds)
        assertEquals(
            listOf(
                RaftDraftPlace.Thread("chan-a", "msg-9", threadChannelId = null),
                RaftDraftPlace.Conversation("chan-a", directMessage = false),
            ),
            listing.entries.map { it.place },
        )
    }

    @Test
    fun legacyDraftNotFoundPersistsAcrossColdRestartWithZeroLookups() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        val legacyDrafts = listOf(draft(root("deleted-or-other-server"), "still kept", 100, serverId = null))
        val asked = mutableListOf<Pair<String, String>>()

        // Process 1: first cold start asks once, gets 404, remembers & persists it.
        val firstProcess = RaftDraftPlaceLookups(store = storage)
        firstProcess.bindAccount("env|user-a")
        val initialListing = raftDraftListing(legacyDrafts, directory, lookups = emptyMap())
        assertEquals(setOf("deleted-or-other-server"), initialListing.lookupChannelIds)

        assertTrue(
            firstProcess.ensure("s1", initialListing.lookupChannelIds) { serverId, channelId ->
                asked += serverId to channelId
                RaftDraftLookupResult.NotFound
            }
        )
        assertEquals(listOf("s1" to "deleted-or-other-server"), asked)
        val firstScope = RaftDraftPlaceLookups.Scope("env|user-a", "s1")
        assertEquals(mapOf("deleted-or-other-server" to null), firstProcess.state.value[firstScope])

        // Process 2 (simulated cold restart): fresh lookups instance on the same store hydrates 404s.
        val restartedProcess = RaftDraftPlaceLookups(store = storage)
        restartedProcess.bindAccount("env|user-a")
        val restartedAnswers = restartedProcess.state.value[firstScope].orEmpty()
        assertEquals(mapOf("deleted-or-other-server" to null), restartedAnswers)

        val restartedListing = raftDraftListing(legacyDrafts, directory, lookups = restartedAnswers)
        assertEquals(0, restartedListing.count)
        assertEquals(emptySet(), restartedListing.lookupChannelIds)

        assertTrue(
            restartedProcess.ensure("s1", listOf("deleted-or-other-server")) { serverId, channelId ->
                asked += serverId to channelId
                RaftDraftLookupResult.NotFound
            }
        )
        assertEquals(listOf("s1" to "deleted-or-other-server"), asked)

        // Persisted 404 on s1 does not block lookups on s2.
        assertTrue(
            restartedProcess.ensure("s2", listOf("deleted-or-other-server")) { serverId, channelId ->
                asked += serverId to channelId
                RaftDraftLookupResult.Found(RaftDraftChannelPlace(channelId, "channel"))
            }
        )
        assertEquals(
            listOf("s1" to "deleted-or-other-server", "s2" to "deleted-or-other-server"),
            asked,
        )
    }

    @Test
    fun forbiddenLookupStaysInMemoryOnlyAndRetriesAfterColdRestart() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        val asked = mutableListOf<String>()

        // Process 1: 403 is remembered in-memory for the session, never persisted.
        val firstProcess = RaftDraftPlaceLookups(store = storage)
        firstProcess.bindAccount("env|user-a")
        assertTrue(
            firstProcess.ensure("s1", listOf("private-chan")) { _, id ->
                asked += "p1:$id"
                RaftDraftLookupResult.Forbidden
            }
        )
        // Same session does not re-request.
        assertTrue(
            firstProcess.ensure("s1", listOf("private-chan")) { _, id ->
                asked += "p1-repeat:$id"
                RaftDraftLookupResult.Forbidden
            }
        )
        assertEquals(listOf("p1:private-chan"), asked)
        assertNull(storage.getString(RaftDraftPlaceNotFoundStorageKey))

        // Process 2 (cold restart): 403 was not persisted, so re-invited private channel recovers.
        val restartedProcess = RaftDraftPlaceLookups(store = storage)
        restartedProcess.bindAccount("env|user-a")
        assertNull(restartedProcess.state.value[RaftDraftPlaceLookups.Scope("env|user-a", "s1")])

        assertTrue(
            restartedProcess.ensure("s1", listOf("private-chan")) { _, id ->
                asked += "p2:$id"
                RaftDraftLookupResult.Found(RaftDraftChannelPlace(id, "private"))
            }
        )
        assertEquals(listOf("p1:private-chan", "p2:private-chan"), asked)
        assertEquals(
            RaftDraftChannelPlace("private-chan", "private"),
            restartedProcess.state.value[RaftDraftPlaceLookups.Scope("env|user-a", "s1")]?.get("private-chan"),
        )
    }

    @Test
    fun bindAccountSignOutAndAccountSwitchClearPersistedNotFoundMisses() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        val lookups = RaftDraftPlaceLookups(store = storage)
        lookups.bindAccount("env|user-a")
        lookups.ensure("s1", listOf("gone-1")) { _, _ -> RaftDraftLookupResult.NotFound }
        assertTrue(storage.getString(RaftDraftPlaceNotFoundStorageKey)?.contains("gone-1") == true)

        // Sign-out wipes persisted 404 misses.
        lookups.bindAccount(null)
        assertNull(storage.getString(RaftDraftPlaceNotFoundStorageKey))
        assertEquals(emptyMap(), lookups.state.value)

        // Re-binding account A after sign-out starts clean (no stale persisted misses).
        lookups.bindAccount("env|user-a")
        assertEquals(emptyMap(), lookups.state.value)

        // Persist again, then switch directly to account B: wipes persisted misses too.
        lookups.ensure("s1", listOf("gone-2")) { _, _ -> RaftDraftLookupResult.NotFound }
        assertTrue(storage.getString(RaftDraftPlaceNotFoundStorageKey)?.contains("gone-2") == true)
        lookups.bindAccount("env|user-b")
        assertNull(storage.getString(RaftDraftPlaceNotFoundStorageKey))
        assertEquals(emptyMap(), lookups.state.value)

        // Cold restart binding a different account purges stale persisted misses left by the previous account.
        lookups.ensure("s1", listOf("gone-b")) { _, _ -> RaftDraftLookupResult.NotFound }
        assertTrue(storage.getString(RaftDraftPlaceNotFoundStorageKey)?.contains("gone-b") == true)
        val restartedForA = RaftDraftPlaceLookups(store = storage)
        restartedForA.bindAccount("env|user-a")
        assertNull(storage.getString(RaftDraftPlaceNotFoundStorageKey))
        assertEquals(emptyMap(), restartedForA.state.value)
    }

    @Test
    fun persistedNotFoundMissesStayBoundedKeepingMostRecentEntries() = runTest {
        val storage = InMemoryRaftKeyValueStore()
        val firstProcess = RaftDraftPlaceLookups(store = storage, maxPersistedNotFoundPerServer = 3)
        firstProcess.bindAccount("env|user-a")
        for (index in 1..5) {
            firstProcess.ensure("s1", listOf("c$index")) { _, _ -> RaftDraftLookupResult.NotFound }
        }

        val restarted = RaftDraftPlaceLookups(store = storage, maxPersistedNotFoundPerServer = 3)
        restarted.bindAccount("env|user-a")
        val scope = RaftDraftPlaceLookups.Scope("env|user-a", "s1")
        assertEquals(setOf("c3", "c4", "c5"), restarted.state.value[scope]?.keys)
    }
}
