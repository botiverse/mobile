package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #proj-mobile:#163: Web-aligned Activity realtime updates (unlisted followed-thread insert,
 * unfollowed-thread skip, sync_inbox_rows reload suppression, hidden-tab in-place maintenance,
 * sync-row drift checks, and foreground sync telemetry).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreWebAlignTest {
    private val owner =
        ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = "user-1", serverId = "server-1")

    private class RecordingInbox(private val lists: List<InboxSnapshot>) :
        InboxRepository by FakeInboxRepository(lists) {
        val listReads = mutableListOf<InboxFetchReason?>()
        val rowReads = mutableListOf<List<String>>()

        override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
            listReads += currentCoroutineContext()[InboxFetchReasonContext]?.reason
            return DataResult.Content(lists.getOrElse(listReads.size - 1) { lists.last() })
        }

        override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
            rowReads += rowIds
            return DataResult.Empty(EmptyReason.NoInboxItems)
        }
    }

    private fun threadUpdatedFrame(
        threadId: String = "thread-new",
        parentMessageId: String = "parent-msg-1",
        parentChannelId: String = "ch-parent",
        parentChannelType: String = "channel",
        replyId: String = "r-21",
        seq: Long = 21L,
        replyCount: Int = 3,
        senderId: String = "user-2",
        senderType: String = "user",
        createdAt: String = "2026-11-24T10:00:00.000Z",
        mentions: List<MessageMention> = emptyList(),
        messageType: MessageType = MessageType.Chat,
    ): ThreadUpdated =
        ThreadUpdated(
            parentMessageId = MessageId(parentMessageId),
            threadChannelId = ChannelId(threadId),
            replyCount = replyCount,
            lastReplyAt = createdAt,
            participantIds = listOf(MemberId("user-1"), MemberId(senderId)),
            latestReply =
                ReplyPreview(
                    messageId = replyId,
                    seq = seq,
                    content = "reply $replyId",
                    senderId = senderId,
                    senderType = senderType,
                    senderName = senderId,
                    createdAt = createdAt,
                ),
            latestReplyMentions = mentions,
            parentChannelId = ChannelId(parentChannelId),
            parentChannelType = parentChannelType,
            latestReplyChannelType = "thread",
            latestReplyMessageType = messageType,
        )

    private fun knownFollowed(
        threadId: String = "thread-new",
        parentMessageId: String = "parent-msg-1",
        parentChannelId: String = "ch-parent",
        parentChannelType: String = "channel",
        replyCount: Int = 2,
        unreadCount: Int = 0,
        latestActivitySeq: String = "20",
        parentInHistory: Boolean? = true,
    ) =
        ActivityKnownFollowedThread(
            threadChannelId = threadId,
            parentMessageId = parentMessageId,
            parentChannelId = parentChannelId,
            parentChannelName = "general",
            parentChannelType = parentChannelType,
            parentMessagePreview = "Parent message text",
            parentMessageSenderId = "user-3",
            parentMessageSenderKind = MemberKind.Human,
            parentInHistory = parentInHistory,
            latestActivitySeq = latestActivitySeq,
            replyCount = replyCount,
            unreadCount = unreadCount,
        )

    private fun knownParentChannel(
        id: String = "ch-parent",
        type: String = "channel",
        archived: Boolean = false,
    ) = ActivityKnownParentChannel(id = id, name = "general", type = type, archived = archived)

    private fun syncChanges(vararg changes: String): List<RaftSyncInboxRowChange> =
        parseSyncChangesPage(syncChangesBody("c1.1.1", changes = changes.toList()))!!
            .changes
            .filterIsInstance<RaftSyncInboxRowChange>()

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1_000L)
        runCurrent()
    }

    @Test
    fun unlistedFollowedThreadReplyInsertsRowInPlaceWithoutReloadOrSyncPull() = runTest {
        val existingRow =
            activityItem("ch-1", unreadCount = 1).copy(
                latestActivitySeq = "10",
                readStateLatestActivitySeq = "10",
                lastMessageAt = "2026-11-24T09:00:00.000Z",
            )
        val inbox = RecordingInbox(listOf(snapshot(listOf(existingRow))))
        val syncHints = mutableListOf<Pair<String, String>>()
        val checks = mutableListOf<ActivityInPlaceCheck>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                syncInboxRowsEnabled = { true },
                onSyncPullHint = { serverId, reason -> syncHints += serverId to reason },
                inPlaceCheckSink = { _, check -> checks += check },
                knownThreadContext = { _, threadId, _, _ ->
                    if (threadId == "thread-new") {
                        ActivityUnlistedThreadContext(
                            follow = ActivityKnownThreadFollow.Followed,
                            followed = knownFollowed(),
                            parentChannel = knownParentChannel(),
                        )
                    } else {
                        ActivityUnlistedThreadContext()
                    }
                },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.listReads.size)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(threadUpdatedFrame()))
        settle()

        assertEquals(1, inbox.listReads.size, "inserted in place: no full inbox reload")
        assertTrue(syncHints.isEmpty(), "inserted in place: no sync pull needed")
        assertEquals(listOf("thread:thread-new", "channel:ch-1"), store.state.value.items.map { it.key })
        val inserted = store.state.value.items.first()
        assertEquals(InboxItemKind.Thread, inserted.kind)
        assertEquals("21", inserted.latestActivitySeq)
        assertEquals(1, inserted.unreadCount)
        assertEquals(3, inserted.replyCount)
        assertEquals("Parent message text", inserted.parentMessagePreview)
        assertEquals(2 to 2, store.state.value.snapshot!!.totalCount to store.state.value.snapshot!!.totalUnreadCount)

        // Confirming sync_inbox_rows upsert judges the inserted row instead of expiring.
        val syncRow =
            """{"kind":"thread","threadChannelId":"thread-new","parentMessageId":"parent-msg-1",""" +
                """"parentChannelId":"ch-parent","parentChannelName":"general","parentChannelType":"channel",""" +
                """"parentMessagePreview":"Parent message text","latestActivityMessageId":"r-21",""" +
                """"latestActivityPreview":"reply r-21","latestActivityAt":"2026-11-24T10:00:00.000Z",""" +
                """"latestActivitySeq":"21","replyCount":3,"unreadCount":1,"hasMention":false,"isFollowing":true}"""
        val outcome = store.applySyncInboxRows("server-1", syncChanges(syncInboxRowUpsert(5, "thread-new", syncRow)))
        assertEquals(setOf("thread-new"), assertIs<RaftSyncInboxRowsOutcome.Applied>(outcome).settledIds)
        val judged = checks.single()
        assertEquals("thread:thread-new", judged.entityId)
        assertEquals(false, judged.rowExisted)
        assertEquals(ActivityInPlaceRereadInserted, judged.reread)
        assertEquals(ActivityInPlaceScopeSyncRow, judged.scope)
        assertEquals("match", judged.result)
        assertEquals(ActivityInPlaceCheckOutcome.Noop, judged.outcome)
    }

    @Test
    fun unlistedUnfollowedThreadReplySkipsReloadAndFlagsUnexpectedAppearanceAsConflict() = runTest {
        val existingRow =
            activityItem("ch-1", unreadCount = 0).copy(
                latestActivitySeq = "10",
                readStateLatestActivitySeq = "10",
                lastMessageAt = "2026-11-24T09:00:00.000Z",
            )
        val inbox = RecordingInbox(listOf(snapshot(listOf(existingRow), hasMore = false)))
        val syncHints = mutableListOf<Pair<String, String>>()
        val checks = mutableListOf<ActivityInPlaceCheck>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                syncInboxRowsEnabled = { true },
                onSyncPullHint = { serverId, reason -> syncHints += serverId to reason },
                inPlaceCheckSink = { _, check -> checks += check },
                knownThreadContext = { _, _, _, _ ->
                    ActivityUnlistedThreadContext(
                        follow = ActivityKnownThreadFollow.NotFollowed,
                        parentMessage = ActivityKnownParentMessage(senderId = "user-9", senderKind = MemberKind.Human),
                    )
                },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(threadUpdatedFrame(threadId = "thread-unfollowed")))
        settle()

        assertEquals(1, inbox.listReads.size, "unfollowed thread that cannot list skips reload")
        assertTrue(syncHints.isEmpty(), "unfollowed thread that cannot list skips sync pull")
        assertEquals(listOf("channel:ch-1"), store.state.value.items.map { it.key })

        // If sync unexpectedly brings the skipped row anyway, drift check records a conflict.
        val unexpectedRow =
            """{"kind":"thread","threadChannelId":"thread-unfollowed","parentMessageId":"parent-msg-1",""" +
                """"parentChannelId":"ch-parent","parentChannelName":"general","parentChannelType":"channel",""" +
                """"latestActivityMessageId":"r-21","latestActivityPreview":"reply r-21",""" +
                """"latestActivityAt":"2026-11-24T10:00:00.000Z","latestActivitySeq":"21","replyCount":3,"unreadCount":1}"""
        store.applySyncInboxRows("server-1", syncChanges(syncInboxRowUpsert(6, "thread-unfollowed", unexpectedRow)))
        val conflict = checks.single()
        assertEquals("thread:thread-unfollowed", conflict.entityId)
        assertEquals("appeared", conflict.result)
        assertEquals(ActivityInPlaceCheckOutcome.Conflict, conflict.outcome)
        assertEquals(ActivityInPlaceRereadUnfollowed, conflict.reread)
        assertEquals(ActivityInPlaceScopeSyncRow, conflict.scope)
    }

    @Test
    fun unfollowedThreadWithMentionOrAuthoredParentDoesNotSkip() {
        val baseReply = message("r-1", channelId = "thread-1", authorId = "user-2", seq = 10L)
        val otherParent = ActivityKnownParentMessage(senderId = "user-3", senderKind = MemberKind.Human)
        val ownParent = ActivityKnownParentMessage(senderId = "user-1", senderKind = MemberKind.Human)

        assertNull(
            threadReplyMayListUnfollowed(
                viewerId = "user-1",
                reply = baseReply,
                follow = ActivityKnownThreadFollow.NotFollowed,
                parentChannelType = "channel",
                replyChannelType = "thread",
                parentMessage = otherParent,
                filter = InboxFilter.All,
                listComplete = true,
            ),
            "plain non-follower on complete All list cannot list"
        )
        assertEquals(
            ActivityUnfollowedListingCause.Mentioned,
            threadReplyMayListUnfollowed(
                viewerId = "user-1",
                reply = baseReply.copy(mentions = listOf(MessageMention(type = "user", id = "user-1"))),
                follow = ActivityKnownThreadFollow.NotFollowed,
                parentChannelType = "channel",
                replyChannelType = "thread",
                parentMessage = otherParent,
                filter = InboxFilter.All,
                listComplete = true,
            ),
        )
        assertEquals(
            ActivityUnfollowedListingCause.Authored,
            threadReplyMayListUnfollowed(
                viewerId = "user-1",
                reply = baseReply,
                follow = ActivityKnownThreadFollow.NotFollowed,
                parentChannelType = "channel",
                replyChannelType = "thread",
                parentMessage = ownParent,
                filter = InboxFilter.All,
                listComplete = true,
            ),
        )
        assertEquals(
            ActivityUnfollowedListingCause.Overlay,
            threadReplyMayListUnfollowed(
                viewerId = "user-1",
                reply = baseReply,
                follow = ActivityKnownThreadFollow.NotFollowed,
                parentChannelType = "channel",
                replyChannelType = "thread",
                parentMessage = otherParent,
                filter = InboxFilter.All,
                listComplete = false,
            ),
        )
        assertNull(
            threadReplyMayListUnfollowed(
                viewerId = "user-1",
                reply = baseReply,
                follow = ActivityKnownThreadFollow.NotFollowed,
                parentChannelType = "channel",
                replyChannelType = "thread",
                parentMessage = otherParent,
                filter = InboxFilter.Unread,
                listComplete = false,
            ),
            "Unread has no unfollowed overlay even when hasMore is true"
        )
    }

    @Test
    fun absentMessageAndDmCreatedWithSyncInboxRowsUseSyncPullWithoutFullInboxReload() = runTest {
        val existingRow = activityItem("ch-1", unreadCount = 0).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
        val inbox = RecordingInbox(listOf(snapshot(listOf(existingRow))))
        val syncHints = mutableListOf<Pair<String, String>>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                syncInboxRowsEnabled = { true },
                onSyncPullHint = { serverId, reason -> syncHints += serverId to reason },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.listReads.size)

        // Absent channel message -> sync pull hint only, zero full /api/channels/inbox reloads.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-1", channelId = "ch-missing", seq = 50L))))
        // DirectMessageCreated -> sync pull hint only, zero full /api/channels/inbox reloads.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(DirectMessageCreated(channelId = ChannelId("dm-new"))))
        settle()

        assertEquals(1, inbox.listReads.size, "no full inbox reload when sync_inbox_rows_v0 is on")
        assertEquals(
            listOf("server-1" to "message_absent", "server-1" to "dm_new"),
            syncHints,
        )
    }

    @Test
    fun hiddenActivityMaintainsRowsAndBadgeInPlaceWithoutSummaryOrForegroundInboxReloads() = runTest {
        val foreground = MutableStateFlow(true)
        val summaryReads = mutableListOf<InboxFetchReason?>()
        val summary =
            ProcessActivityUnreadStore(
                fetchSummary = { _, _ ->
                    summaryReads += currentCoroutineContext()[InboxFetchReasonContext]?.reason
                    DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 99, totalCount = 99))
                },
                ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
                scope = backgroundScope,
                throttleMillis = 2_500L,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground,
            )
        val initialRow =
            activityItem("ch-1", unreadCount = 1).copy(
                latestActivitySeq = "10",
                readStateLatestActivitySeq = "10",
                lastMessageAt = "2026-11-24T09:00:00.000Z",
            )
        val inbox = RecordingInbox(listOf(snapshot(listOf(initialRow))))
        val syncHints = mutableListOf<Pair<String, String>>()
        val activity =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                appForeground = foreground,
                onSummaryFact = summary::submitFact,
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                syncInboxRowsEnabled = { true },
                onSyncPullHint = { serverId, reason -> syncHints += serverId to reason },
            )
        summary.followAppLifecycle()
        summary.deriveBadgeFromList(
            derives = { activity.state.value.derivesBadgeFor(it) },
            owns = { activity.ownsBadgeFor(it) },
        )
        activity.dispatch(SharedActivityIntent.SurfaceActive(false))
        activity.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertEquals(1, inbox.listReads.size)
        assertEquals(1, summary.currentProjection(ServerId("server-1")).totalUnreadCount)

        // User is on Home tab (Activity hidden): live message patches row in place and updates badge.
        activity.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                MessageNew(message("m-11", channelId = "ch-1", seq = 11L, timestamp = "2026-11-24T09:05:00.000Z")),
                activeConversationId = null,
                primarySurfaceVisible = true,
            )
        )
        // New DM while hidden requests sync pull instead of full inbox reload.
        activity.dispatch(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(
                DirectMessageCreated(channelId = ChannelId("dm-2")),
                activeConversationId = null,
                primarySurfaceVisible = true,
            )
        )
        // Foreground return with sync_inbox_rows_v0 on does not fire redundant /api/channels/inbox or limit=1.
        foreground.value = false
        runCurrent()
        foreground.value = true
        summary.onForeground(ServerId("server-1"), epoch = 1L, reason = "app_shell_resume")
        settle()

        assertEquals("11", activity.state.value.items.single().latestActivitySeq, "hidden row stayed in sync")
        assertEquals(2, activity.state.value.items.single().unreadCount)
        assertEquals(2, summary.currentProjection(ServerId("server-1")).totalUnreadCount)
        assertEquals(1, inbox.listReads.size, "no redundant foreground/realtime inbox reload")
        assertEquals(emptyList<InboxFetchReason?>(), summaryReads, "no limit=1 summary storm")
        assertEquals(listOf("server-1" to "dm_new"), syncHints)

        // Sync inbox_row confirming seq 11 does not double-count unread.
        activity.applySyncInboxRows(
            "server-1",
            syncChanges(syncInboxRowUpsert(5, "ch-1", syncInboxChannelRow("ch-1", "11", unread = 2, at = "2026-11-24T09:05:00.000Z"))),
        )
        runCurrent()
        assertEquals(2, activity.state.value.totalUnreadCount)
        assertEquals(2, summary.currentProjection(ServerId("server-1")).totalUnreadCount)
    }

    @Test
    fun syncInboxRowsJudgesPendingInPlaceDriftExpectationsInsteadOfExpiring() = runTest {
        val row = activityItem("ch-1", unreadCount = 0).copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10")
        val inbox = RecordingInbox(listOf(snapshot(listOf(row)), snapshot(listOf(row.copy(latestActivitySeq = "11", unreadCount = 1)))))
        val checks = mutableListOf<ActivityInPlaceCheck>()
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { true },
                syncInboxRowsEnabled = { true },
                inPlaceCheckSink = { _, check -> checks += check },
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()

        // Patch row in place (creates pending expectation at seq 11, unread 1).
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-11", channelId = "ch-1", seq = 11L))))
        runCurrent()
        assertEquals(0, checks.size)

        // Sync upsert matches -> judged immediately with scope=sync_row, expectation consumed.
        store.applySyncInboxRows("server-1", syncChanges(syncInboxRowUpsert(1, "ch-1", syncInboxChannelRow("ch-1", "11", unread = 1))))
        val match = checks.single()
        assertEquals("channel:ch-1", match.entityId)
        assertEquals("match", match.result)
        assertEquals(ActivityInPlaceCheckOutcome.Noop, match.outcome)
        assertEquals(ActivityInPlaceScopeSyncRow, match.scope)
        assertEquals("sync_inbox_rows", match.fetchReason)

        // Advancing past 90 s TTL + full pull does NOT emit a stale expired check.
        advanceTimeBy(ActivityInPlaceCheckTtlMillis + 5_000L)
        store.dispatch(SharedActivityIntent.PullRefresh)
        runCurrent()
        assertEquals(1, checks.size, "consumed expectation must not expire later")

        // Mismatched sync upsert emits conflict (sampleRate = 1).
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-12", channelId = "ch-1", seq = 12L))))
        runCurrent()
        store.applySyncInboxRows("server-1", syncChanges(syncInboxRowUpsert(2, "ch-1", syncInboxChannelRow("ch-1", "12", unread = 4))))
        val conflict = checks.last()
        assertEquals("unread", conflict.result)
        assertEquals(ActivityInPlaceCheckOutcome.Conflict, conflict.outcome)
        assertEquals(1, conflict.sampleRate)
        assertEquals(ActivityInPlaceScopeSyncRow, conflict.scope)
    }

    @Test
    fun foregroundSyncUpdatedTraceAttrsAndEngineInboxRowCountMatchContract() = runTest {
        val telemetry =
            ActivityForegroundSyncUpdated(
                serverId = "server-1",
                durationMillis = 142L,
                inboxRowsApplied = 3,
                syncedOk = true,
                timestampMillis = 1_700_000L,
            )
        val attrs = telemetry.stateTransitionTraceAttrs()
        val key = attrs.getValue("key").jsonObject
        assertEquals("inbox", key.getValue("domain").jsonPrimitive.content)
        assertEquals("activity:foreground_sync_updated", key.getValue("event").jsonPrimitive.content)
        assertEquals("applied", key.getValue("outcome").jsonPrimitive.content)
        assertEquals("server:server-1", key.getValue("entityId").jsonPrimitive.content)
        val meta = attrs.getValue("meta").jsonObject
        assertEquals("duration_ms=142;inbox_rows_applied=3;synced_ok=1", meta.getValue("outcomeDetail").jsonPrimitive.content)
        assertEquals(3L, meta.getValue("touched").jsonPrimitive.long)
        assertEquals(1_700_000L, meta.getValue("timestamp").jsonPrimitive.long)
        assertEquals("142", attrs.getValue("duration_ms").jsonPrimitive.content)
        assertEquals("3", attrs.getValue("inbox_rows_applied").jsonPrimitive.content)
        assertEquals("true", attrs.getValue("synced_ok").jsonPrimitive.content)

        // Failed pull sets outcome=failed and synced_ok=false.
        val failedAttrs = telemetry.copy(inboxRowsApplied = 0, syncedOk = false).stateTransitionTraceAttrs()
        assertEquals("failed", failedAttrs.getValue("key").jsonObject.getValue("outcome").jsonPrimitive.content)
        assertEquals("false", failedAttrs.getValue("synced_ok").jsonPrimitive.content)

        // Engine + router accumulate inboxRowsApplied across synced pages.
        val cursors = RaftSyncCursorStore(SyncTestPreferences())
        cursors.write(syncTestScope, "c1.1.1")
        val targets =
            object : RaftSyncStoreTargets {
                override fun rosterServer(serverId: String): build.raft.app.model.ServerSummary? = null
                override suspend fun supersedeRosterLoad(serverId: String, reason: String) = Unit
                override fun machineNames(serverId: String): Map<String, String> = emptyMap()
                override suspend fun applyRosterRows(serverId: String, upserts: List<build.raft.app.model.MemberProfile>, deletes: List<Pair<MemberId, MemberKind>>) = Unit
                override suspend fun applyMachineRows(serverId: String, upserts: List<ComputerSummary>, deletes: Set<String>) = Unit
                override fun refreshHome(serverId: String, reason: String) = Unit
                override fun refreshActivity(serverId: String, reason: String) = Unit
                override fun applyInboxRows(serverId: String, changes: List<RaftSyncInboxRowChange>): RaftSyncInboxRowsOutcome =
                    RaftSyncInboxRowsOutcome.Applied(changes.mapTo(linkedSetOf()) { it.id })
                override fun applyRemoteDone(serverId: String, channelId: String, doneFrontierSeq: String?) = ActivityRemoteDoneOutcome.Removed
                override suspend fun refreshChannelMembers(serverId: String, channelIds: Set<String>?, reason: String) =
                    RaftSyncChannelMembersRefreshResult.AllSucceeded
                override suspend fun reloadRoster(serverId: String, reason: String) = RaftSyncSnapshotRead.Taken
                override suspend fun reloadMachines(serverId: String, reason: String) = RaftSyncSnapshotRead.Taken
                override suspend fun applyMessageRows(serverId: String, messages: List<build.raft.app.model.Message>) = Unit
            }
        val api =
            ScriptedSyncChangesApi(
                syncPage(
                    syncChangesBody(
                        "c1.2.1",
                        hasMore = true,
                        changes = listOf(syncInboxRowUpsert(2, "ch-1", syncInboxChannelRow("ch-1", "2"))),
                    )
                ),
                syncPage(
                    syncChangesBody(
                        "c1.4.1",
                        hasMore = false,
                        changes =
                            listOf(
                                syncInboxRowUpsert(3, "ch-2", syncInboxChannelRow("ch-2", "3")),
                                syncInboxRowDelete(4, "ch-3", "4"),
                            ),
                    )
                ),
            )
        val result =
            RaftSyncChangesEngine(
                api = api,
                cursors = cursors,
                sink = RaftSyncChangesStoreRouter(targets),
            ).pull(syncTestScope, trigger = "foreground")
        val synced = assertIs<RaftSyncPullResult.Synced>(result)
        assertEquals(3, synced.inboxRowsApplied)
        assertEquals(2, synced.pages)
    }
}
